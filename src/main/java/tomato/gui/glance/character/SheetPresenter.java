package tomato.gui.glance.character;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.home.HomeModelBuilder;
import tomato.planning.PlanningMetadata;

/**
 * Feeds the character sheet (spec §3.1: glance screens own no data). It rebuilds when the sheet opens a key and, while the sheet
 * shows, whenever a cheap token moved (CharacterSheet.refresh checks once a second): the key, the journal and live-character
 * revisions, the loaded definitions, the pet names and the dungeon mapping. The "character-sheet" thread reads the journal's
 * deep copies (reused while the journal's revision is unchanged) and runs SheetModelBuilder. The EDT applies a result only while it is the newest
 * request and its key is still the sheet's, so a late result for another character, or an older one for this character, is
 * dropped. A failed build (any Throwable, applying included) is logged with its stack trace, reported in the sheet (spec §7),
 * never swallowed or rethrown on the EDT, and tried again on the next refresh.
 */
final class SheetPresenter {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "character-sheet"); thread.setDaemon(true); return thread;
    });
    /**
     * Where a failed build is logged, with its stack trace: standard error, where TomatoData's printStackTrace and Home's
     * refresher report failures (Util.printLogs would print to standard output, as the app configures it). Tests replace it.
     */
    static volatile Consumer<String> errorLog = message -> System.err.println(message); // reads System.err when it logs
    private final CharacterSheet sheet;
    private final SheetContext context;
    /** Runs one build at a time: the shared "character-sheet" thread, or a test's executor. */
    private final Executor worker;
    private final SheetHeader header = new SheetHeader();
    private final OverviewTab overview;
    private final GearTab gear;
    private final ExaltsTab exalts = new ExaltsTab();
    private final PetTab pet = new PetTab();
    private final BuildTab build = new BuildTab(key -> tomato.gui.route.Navigator.current().open(tomato.gui.myinfo.BuildRoute.sheet(key)));
    private String key;
    private Token token;
    private long generation;
    private SheetModel model;
    /** The build thread's last journal read, reused while the key and the journal revision are unchanged (build thread only). */
    private Read lastRead;
    /** The failure last logged, so one that repeats on every retry is logged once until a build applies again (EDT only). */
    private String logged;

    /** {@code pets}, like {@code definitions} and {@code planning}, compares by identity: a new object once the pet names load. */
    private record Token(String key, long journal, long live, boolean graceOver, RosterDefinitions definitions, PetDefinitions pets,
                         PlanningMetadata planning) {}
    /** One journal read at one revision: the character's record (null when the journal lacks it) and the lists Goals shows. */
    private record Read(String key, long revision, CharacterRecord record, List<CharacterRecord> records, List<AccountRecord> accounts) {}
    /** One build for {@code key}. */
    private record Built(String key, SheetModel model, Read read, RosterDefinitions definitions) {}

    SheetPresenter(CharacterSheet sheet, SheetContext context) { this(sheet, context, WORKER); }

    /**
     * {@code worker} must run builds one at a time ({@link #lastRead} is confined to whichever thread runs the current build);
     * tests pass one that holds builds and runs them in any order, to prove only the newest result applies.
     */
    SheetPresenter(CharacterSheet sheet, SheetContext context, Executor worker) {
        this.sheet = sheet;
        this.context = context;
        this.worker = Objects.requireNonNull(worker, "worker");
        // Opening the Overview's pet card is explicit navigation: it may show a hidden Pet tab.
        overview = new OverviewTab(context.mode(), context.clock(), () -> sheet.openTab("pet"));
        sheet.setIdentity(header);
        sheet.setTab("overview", SheetViews.scroll(overview));
        gear = new GearTab(context.mode());
        sheet.setTab("gear", SheetViews.scroll(gear));
        sheet.setTab("exalts", SheetViews.scroll(exalts));
        sheet.setTab("pet", SheetViews.scroll(pet)); // the Fame slot stays the sheet's placeholder until the Fame tab sets it
        sheet.setTab("build", build); // the sheet's build slot, registered right after Fame
    }

    /** EDT: the sheet now shows {@code key}; rebuild at once. A new key clears what is shown until its own result applies. */
    void open(String key) {
        // Nothing of the previous character stays on screen while this one loads, Build included.
        if (!Objects.equals(key, this.key)) { show(null); build.loading(); }
        this.key = key;
        request();
    }

    /** EDT: the model last applied, or null (loading, failed, or a key the journal lacks). */
    SheetModel model() { return model; }

    /** EDT, once a second while the sheet shows: rebuild when a token moved, else re-read only the relative times. */
    void refresh() {
        if (key == null) return;
        if (!token().equals(token)) request();
        else times();
    }

    /** Relative times ("Played …", the vault's age, the pet's "Observed …") change without a new model. */
    private void times() {
        if (model == null) return;
        header.apply(model.identity());
        overview.apply(model); // re-reads only the vault age
        exalts.apply(model.exalts()); // and when this class's counts last changed
        pet.apply(model.pet()); // and when the pet was observed
    }

    /**
     * {@code graceOver} mirrors Home's map-change grace (LiveHomeSources.revisions): true once a TRANSIENT clear's grace has
     * elapsed with no new publish. live.revision() alone does not move again when the grace merely expires, so without this the
     * token would never change and "Playing now" (and the live boosts) would stay stuck past the grace window.
     */
    private Token token() {
        LiveCharacter live = live();
        long now = context.clock().getAsLong();
        boolean graceOver = live.current() == null && live.lastKnown() != null
            && !HomeModelBuilder.stillCurrent(live.lastSeenAt(), live.lastBoundary(), now);
        return new Token(key, context.journal().revision(), live.revision(), graceOver, context.definitions().get(), PetDefinitions.current(),
            PlanningMetadata.current());
    }

    private void request() {
        token = token();
        long requested = ++generation;
        String target = key;
        CharacterJournal journal = context.journal();
        LiveCharacter live = live();
        long now = context.clock().getAsLong();
        worker.execute(() -> {
            Built built = null;
            Throwable failure = null;
            // Any Throwable: an Error escaping here would kill the thread silently, leaving the sheet on "Loading…" (or on the
            // previous model with no warning) and, since the token already moved, never retried.
            try { built = build(target, journal, live, now); }
            catch (Throwable e) {
                failure = e;
                // build() declares no InterruptedException; if one is thrown anyway, keep the thread's interrupt status.
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            Built result = built;
            Throwable failed = failure;
            SwingUtilities.invokeLater(() -> deliver(requested, target, result, failed));
        });
    }

    /**
     * EDT: one build's outcome. Every failure is logged, even one whose result is dropped. Only the newest request for the key
     * the sheet still shows applies; a failure there (building or applying) is reported in the sheet and retried on the next
     * refresh. Nothing is rethrown on the EDT.
     */
    private void deliver(long requested, String target, Built result, Throwable failure) {
        if (failure != null) log(failure);
        if (requested != generation || !Objects.equals(target, key)) return;
        if (failure == null) {
            try { apply(result); logged = null; return; }
            catch (RuntimeException | Error e) { log(e); failure = e; }
        }
        // The cleared token makes the next refresh try again; meanwhile Build says it is unavailable and shows no one (live
        // state may have moved on since the last model).
        token = null;
        build.failed();
        sheet.failed(failure);
    }

    /** Logs {@code failure} with its stack trace, once while the same failure repeats on every retry. EDT. */
    private void log(Throwable failure) {
        StringWriter trace = new StringWriter();
        try (PrintWriter out = new PrintWriter(trace)) { failure.printStackTrace(out); }
        String text = trace.toString();
        if (text.equals(logged)) return;
        logged = text;
        errorLog.accept("[Character sheet] A sheet build failed; it is retried on the next refresh: " + text);
    }

    /** The build thread: one journal read (reused while the revision is unchanged) and the model, over deep copies. */
    private Built build(String target, CharacterJournal journal, LiveCharacter live, long now) {
        RosterDefinitions definitions = context.definitions().get();
        PetDefinitions pets = PetDefinitions.current(); // never blocks: loading() until the pet names are read
        Read read = lastRead;
        synchronized (journal) {
            long revision = journal.revision();
            if (read == null || !Objects.equals(read.key(), target) || read.revision() != revision)
                read = new Read(target, revision, target == null ? null : journal.characterCopy(target), journal.characters(), journal.accounts());
        }
        lastRead = read;
        AccountRecord account = null;
        if (read.record() != null) for (AccountRecord a : read.accounts()) if (a.key.equals(read.record().account)) account = a;
        return new Built(target, SheetModelBuilder.build(read.record(), account, SheetModelBuilder.inGame(live, now), pets, definitions, now), read, definitions);
    }

    private LiveCharacter live() { return context.data().liveCharacter; }

    /** EDT: shows a build for the key the sheet still shows. */
    private void apply(Built built) {
        Read read = built.read();
        sheet.loaded(built.key(), read.record(), read.records(), read.accounts(), built.definitions(), read.revision());
        show(built.model());
        gear.analyst(read.record(), built.definitions()); // re-renders only when the slots it shows or the definitions changed
        build.apply(built.model(), BuildTab.shownKey(live())); // Build shows only on the sheet of the character it describes
    }

    /** EDT: parents the app's single MyInfoGUI in the Build tab. */
    void hostBuild(javax.swing.JComponent value) { build.host(value); }

    /** EDT: the header and the tabs this presenter owns show {@code value}; null (loading, or not in the journal) clears them. */
    private void show(SheetModel value) {
        model = value;
        header.apply(value == null ? null : value.identity());
        overview.apply(value);
        gear.apply(value == null ? null : value.gear());
        exalts.apply(value == null ? null : value.exalts());
        pet.apply(value == null ? null : value.pet());
    }
}
