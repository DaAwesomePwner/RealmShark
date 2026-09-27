package tomato.gui.glance.character;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.home.HomeModelBuilder;
import tomato.planning.PlanningMetadata;

/**
 * Feeds the character sheet (spec §3.1: glance screens own no data). It rebuilds when the sheet opens a key and, while the sheet
 * shows, whenever a cheap token moved (CharacterSheet.refresh checks once a second): the key, the journal and live-character
 * revisions, the loaded definitions and the dungeon mapping. The "character-sheet" thread reads the journal's deep copies (reused
 * while the journal's revision is unchanged) and runs SheetModelBuilder. The EDT applies a result only while it is the newest
 * request and its key is still the sheet's, so a late result for another character is dropped. A failed build is reported in the
 * sheet (spec §7), never swallowed, and tried again on the next refresh.
 */
final class SheetPresenter {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "character-sheet"); thread.setDaemon(true); return thread;
    });
    private final CharacterSheet sheet;
    private final SheetContext context;
    private final SheetHeader header = new SheetHeader();
    private final OverviewTab overview;
    private String key;
    private Token token;
    private long generation;
    private SheetModel model;
    /** The build thread's last journal read, reused while the key and the journal revision are unchanged (build thread only). */
    private Read lastRead;

    private record Token(String key, long journal, long live, boolean graceOver, RosterDefinitions definitions, PlanningMetadata planning) {}
    /** One journal read at one revision: the character's record (null when the journal lacks it) and the lists Goals shows. */
    private record Read(String key, long revision, CharacterRecord record, List<CharacterRecord> records, List<AccountRecord> accounts) {}
    /** One build for {@code key}. */
    private record Built(String key, SheetModel model, Read read, RosterDefinitions definitions) {}

    SheetPresenter(CharacterSheet sheet, SheetContext context) {
        this.sheet = sheet;
        this.context = context;
        overview = new OverviewTab(context.mode());
        sheet.setIdentity(header);
        sheet.setTab("overview", SheetViews.scroll(overview));
    }

    /** EDT: the sheet now shows {@code key}; rebuild at once. A new key clears what is shown until its own result applies. */
    void open(String key) {
        if (!Objects.equals(key, this.key)) show(null); // nothing of the previous character stays on screen while this one loads
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

    /** Relative times ("Played …", the vault's age) change without a new model. */
    private void times() {
        if (model == null) return;
        header.apply(model.identity());
        overview.apply(model); // re-reads only the vault age
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
        return new Token(key, context.journal().revision(), live.revision(), graceOver, context.definitions().get(), PlanningMetadata.current());
    }

    private void request() {
        token = token();
        long requested = ++generation;
        String target = key;
        CharacterJournal journal = context.journal();
        LiveCharacter live = live();
        long now = context.clock().getAsLong();
        WORKER.execute(() -> {
            Built built = null;
            RuntimeException failure = null;
            try { built = build(target, journal, live, now); }
            catch (RuntimeException e) { failure = e; }
            Built result = built;
            RuntimeException failed = failure;
            SwingUtilities.invokeLater(() -> {
                // Only the newest request for the key the sheet still shows applies; a late result for another character is dropped.
                if (requested != generation || !Objects.equals(target, key)) return;
                if (failed != null) { token = null; sheet.failed(failed); return; } // the next refresh tries again
                apply(result);
            });
        });
    }

    /** The build thread: one journal read (reused while the revision is unchanged) and the model, over deep copies. */
    private Built build(String target, CharacterJournal journal, LiveCharacter live, long now) {
        RosterDefinitions definitions = context.definitions().get();
        Read read = lastRead;
        synchronized (journal) {
            long revision = journal.revision();
            if (read == null || !Objects.equals(read.key(), target) || read.revision() != revision)
                read = new Read(target, revision, target == null ? null : journal.characterCopy(target), journal.characters(), journal.accounts());
        }
        lastRead = read;
        AccountRecord account = null;
        if (read.record() != null) for (AccountRecord a : read.accounts()) if (a.key.equals(read.record().account)) account = a;
        return new Built(target, SheetModelBuilder.build(read.record(), account, SheetModelBuilder.inGame(live, now), definitions, now), read, definitions);
    }

    private LiveCharacter live() { return context.data().liveCharacter; }

    /** EDT: shows a build for the key the sheet still shows. */
    private void apply(Built built) {
        Read read = built.read();
        sheet.loaded(built.key(), read.record(), read.records(), read.accounts(), built.definitions(), read.revision());
        show(built.model());
    }

    /** EDT: the header and the tabs this presenter owns show {@code value}; null (loading, or not in the journal) clears them. */
    private void show(SheetModel value) {
        model = value;
        header.apply(value == null ? null : value.identity());
        overview.apply(value);
    }
}
