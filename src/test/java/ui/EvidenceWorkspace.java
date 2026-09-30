package ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.swing.SwingUtilities;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import assets.IdToAsset;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.Tomato;
import tomato.ability.AbilityObservation;
import tomato.ability.AbilityObservationStore;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.DpsDisplayOptions;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.Filter;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.Themes;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.security.SecurityGUI;
import tomato.gui.stats.LootCapture;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.planning.PlanData;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * The real workspace ({@code new TomatoGUI(data).createWorkspace()} in preview mode, so nothing captures and the bridge sends and
 * saves nothing) over a test's own history, with everything it touches isolated and restored:
 * - preferences: every key is snapshotted, the {@link #CLEARED} prefixes are emptied so pages open on their defaults, and every key
 *   that differs afterwards is put back (tabs, sidebar layout, filter drawers, saved workspaces and roster view states, Logging
 *   views, Filter Loot, Highlights, theme and contrast, {@code ui.mode});
 * - the application display mode ({@link DisplayModeModel#application()}), the theme (the dark theme is installed again), the zone
 *   (a fixed offset at which it is mid-afternoon, as {@link RunsEvidenceTest#afternoon}) and the FORMAT locale (US);
 * - statics: every non-final static of {@code TomatoGUI}, {@code ChatGUI}, {@code CharacterPetsGUI}, {@code DpsGUI}, {@code Filter},
 *   {@code DpsDisplayOptions}, {@code KeypopGUI}, {@code ParsePanelGUI} and {@code SecurityGUI}, the three DPS filter sets,
 *   {@code Tomato.preview} (true), {@code TomatoGUI.characterViewStates} (an in-memory store), {@code LootCapture.instance} (a fresh
 *   capture), the asset names ({@code IdToAsset.objectID}, a copy with the fixture's synthetic names), {@code AppHistory.store} and
 *   {@code java.io.tmpdir} (a scratch folder);
 * - JVM-wide logs: {@code DiscoveryLog.INSTANCE} (cleared, with its collection, saving and sampling put back), the key-pop live
 *   buffer, the Ability Use evidence store, and the shared {@link PlanningStore} plans the fixture changed.
 * The workspace, the journal and the store are closed first. Pins (asset and definition swaps) close newest first.
 */
public final class EvidenceWorkspace extends ExternalResource {
    /** Preference prefixes emptied before the build, so every page opens on its defaults. */
    public static final String[] CLEARED = {"ux.archive.", "ui.tabs.", "ui.nav.", "ui.filters.", "ui.loot.", "ui.collapse.", "ui.order.",
        "ui.runs.", "ui.dungeons.", "ui.characters.", "ui.quests.", "ui.logging", "logging.", "filter", "saveChat", DisplayModeModel.KEY,
        Themes.THEME_KEY, Themes.CONTRAST_KEY};
    private static final Class<?>[] STATIC_OWNERS = {TomatoGUI.class, ChatGUI.class, CharacterPetsGUI.class, DpsGUI.class, Filter.class,
        DpsDisplayOptions.class, KeypopGUI.class, ParsePanelGUI.class, SecurityGUI.class};

    private final VisualEvidence evidence;
    private final TemporaryFolder temp = new TemporaryFolder();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> savedPrefs = new LinkedHashMap<>();
    private final List<Set<?>> filterSets = new ArrayList<>(), filterCopies = new ArrayList<>();
    private final List<AutoCloseable> pins = new ArrayList<>();
    private final Map<String, PlanData.AccountPlan> savedPlans = new LinkedHashMap<>();
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    private DisplayModeModel.Mode savedMode;
    private TimeZone savedZone;
    private Locale savedLocale;
    private String temporaryDirectory;
    private boolean logEnabled, logSaving;
    private int logSample;
    private List<Object> keyPops;
    private List<AbilityObservation> abilities;
    private SessionStore store;
    private CharacterJournal journal;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;

    public EvidenceWorkspace(VisualEvidence evidence) { this.evidence = evidence; }

    @Override protected void before() throws Throwable {
        temp.create();
        PropertiesManager.preload(); // merge the disk file first, so the snapshot sees every saved key
        Properties props = properties();
        props.stringPropertyNames().forEach(key -> savedPrefs.put(key, props.getProperty(key)));
        for (String key : props.stringPropertyNames())
            for (String prefix : CLEARED) if (key.startsWith(prefix)) { PropertiesManager.setProperties(key, ""); break; }
        PropertiesManager.setProperties("chat.filters", "{}");
        PropertiesManager.setProperties("chat.showIgnoredPlayers", "false");
        PropertiesManager.setProperties("filterName", "Default");   // the DPS filter preset: none (read when the workspace is built)
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        savedZone = TimeZone.getDefault();
        savedLocale = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone.setDefault(TimeZone.getTimeZone(RunsEvidenceTest.afternoon(System.currentTimeMillis())));
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        for (Class<?> type : STATIC_OWNERS)
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !statics.containsKey(field)) {
                    field.setAccessible(true); statics.put(field, field.get(null));
                }
        for (Set<?> set : Arrays.asList(Filter.filterNames, Filter.filterGuilds, Filter.filterClasses)) { filterSets.add(set); filterCopies.add(new HashSet<>(set)); }
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        remember(LootCapture.class, "instance", null);   // a fresh loot capture: the live feed holds only this test's bags
        remember(AppHistory.class, "store", null);
        remember(KeypopGUI.class, "logToFile", false);
        DiscoveryLog log = DiscoveryLog.INSTANCE;
        logEnabled = log.isEnabled(); logSaving = log.isSaving(); logSample = log.snapshot().sampleMillis;
        log.setSaving(false);
        keyPops = new ArrayList<>(keyPopEvents(call(keyPopHistory(), "snapshot")));
        abilities = new ArrayList<>(AbilityObservationStore.application().snapshot(null, null, null, null).rows);
        Collections.reverse(abilities);   // the snapshot is newest first; put back oldest first
    }

    @Override protected void after() {
        List<Throwable> failures = new ArrayList<>();
        attempt(failures, () -> SwingUtilities.invokeAndWait(() -> {
            evidence.closeWindow();
            if (gui != null) gui.closeWorkspace();
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            DisplayModeModel.application().set(savedMode);
        }));
        attempt(failures, () -> SwingUtilities.invokeAndWait(() -> { }));   // a final view-state save queued by closing drains first
        attempt(failures, () -> { if (journal != null) journal.close(); });
        attempt(failures, () -> { if (store != null) store.close(); });
        attempt(failures, () -> SwingUtilities.invokeAndWait(() -> {
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            for (int i = 0; i < filterSets.size(); i++) restore(filterSets.get(i), filterCopies.get(i));
        }));
        attempt(failures, () -> {
            DiscoveryLog log = DiscoveryLog.INSTANCE;
            log.clear(); log.setEnabled(logEnabled); log.setSaving(logSaving); log.setSampleMillis(logSample);
        });
        attempt(failures, () -> {
            Object history = keyPopHistory();
            call(history, "clear");
            Method add = history.getClass().getDeclaredMethod("add", Class.forName("tomato.gui.keypop.KeyPopEvent"));
            add.setAccessible(true);
            for (Object event : keyPops) add.invoke(history, event);
        });
        attempt(failures, () -> {
            AbilityObservationStore evidenceStore = AbilityObservationStore.application();
            evidenceStore.reset();
            for (AbilityObservation row : abilities) evidenceStore.add(row);
        });
        for (int i = pins.size() - 1; i >= 0; i--) { AutoCloseable pin = pins.get(i); attempt(failures, pin::close); }
        attempt(failures, () -> {
            Properties props = properties();
            Set<String> keys = new HashSet<>(props.stringPropertyNames());
            keys.addAll(savedPrefs.keySet());
            for (String key : keys)
                if (!savedPrefs.getOrDefault(key, "").equals(props.getProperty(key, ""))) PropertiesManager.setProperties(key, savedPrefs.getOrDefault(key, ""));
            PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        });
        attempt(failures, () -> {
            if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
            if (savedZone != null) TimeZone.setDefault(savedZone);
            if (savedLocale != null) Locale.setDefault(Locale.Category.FORMAT, savedLocale);
        });
        for (Map.Entry<String, PlanData.AccountPlan> entry : savedPlans.entrySet()) attempt(failures, () -> {   // last: the rest is restored even if this fails
            PlanningStore plans = PlanningStore.shared();
            assertTrue("The planning store's plan is restored", plans.update(entry.getKey(), plans.snapshot(entry.getKey()).revision, entry.getValue())
                .get(5, TimeUnit.SECONDS).saved);
        });
        temp.delete();
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("Restoring the evidence workspace failed: " + failures.get(0));
            for (Throwable failure : failures) error.addSuppressed(failure);
            throw error;
        }
    }

    // ---- set-up for the fixture ----

    /** A new folder under the rule's own temporary folder (outside the scratch folder the app uses as java.io.tmpdir). */
    public Path folder(String name) {
        try { return temp.newFolder(name).toPath(); } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    /** The absolute root of every file the test writes: no capture text may name it. */
    public String root() { return temp.getRoot().getAbsolutePath(); }

    /** Keeps {@code pin} (an asset, definition or catalog swap) until {@link #after}, which closes pins newest first. */
    public void pin(AutoCloseable pin) { pins.add(pin); }

    /** Sets a static for the test; the value before the first change is put back afterwards. */
    public void remember(Class<?> type, String name, Object next) {
        try {
            Field field = type.getDeclaredField(name); field.setAccessible(true);
            if (!statics.containsKey(field)) statics.put(field, field.get(null));
            field.set(null, next);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** The asset catalog's names, plus {@code names} (synthetic, with {@code label}'s asset labels), until the test ends. */
    public void names(Map<Integer, String> names, Function<Integer, String> label) {
        try {
            Field objects = IdToAsset.class.getDeclaredField("objectID"); objects.setAccessible(true);
            @SuppressWarnings("unchecked") HashMap<Integer, IdToAsset> assets = new HashMap<>((Map<Integer, IdToAsset>) objects.get(null));
            for (Map.Entry<Integer, String> item : names.entrySet())
                assets.put(item.getKey(), new IdToAsset("", item.getKey(), item.getValue(), item.getValue(), "", null, "", label.apply(item.getKey()), ""));
            remember(IdToAsset.class, "objectID", assets);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Replaces {@code account}'s plan in the shared planning store with {@code change} of it; the plan before is put back afterwards. */
    public void plan(String account, UnaryOperator<PlanData.AccountPlan> change) throws Exception {
        PlanningStore plans = PlanningStore.shared();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!plans.snapshot(account).ready) {
            if (System.nanoTime() > end) fail("The planning store did not load within 15 s");
            Thread.sleep(50);
        }
        PlanningStore.Snapshot before = plans.snapshot(account);
        assertFalse("The planning store is writable: " + before.status, before.readOnly);
        if (!savedPlans.containsKey(account)) savedPlans.put(account, before.plan());
        assertTrue("The fixture's plan is saved", plans.update(account, before.revision, change.apply(before.plan())).get(5, TimeUnit.SECONDS).saved);
    }

    /** Opens the app's history store over {@code history} (its own current session is this app run's). */
    public SessionStore openStore(Path history, String label) throws Exception {
        store = new SessionStore(history, true, label);
        remember(AppHistory.class, "store", store);
        return store;
    }

    /** Builds the real workspace over {@code made} (whose journal is {@code characters}) on the EDT. */
    public WorkspaceShell build(TomatoData made, CharacterJournal characters) throws Exception {
        journal = characters; data = made;
        SwingUtilities.invokeAndWait(() -> {
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
        });
        return shell;
    }

    public WorkspaceShell shell() { return shell; }
    public TomatoData data() { return data; }
    public SessionStore store() { return store; }

    // ---- helpers ----

    /** The key-pop live buffer (the page's static {@code KeypopGUI.history}). */
    static Object keyPopHistory() throws ReflectiveOperationException {
        Field field = KeypopGUI.class.getDeclaredField("history"); field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings("unchecked") private static List<Object> keyPopEvents(Object snapshot) throws ReflectiveOperationException {
        Field events = snapshot.getClass().getDeclaredField("events"); events.setAccessible(true);
        return (List<Object>) events.get(snapshot);
    }

    static Object call(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return method.invoke(owner);
    }

    private interface Step { void run() throws Exception; }

    private static void attempt(List<Throwable> failures, Step step) {
        try { step.run(); } catch (Throwable failure) { failures.add(failure); }
    }

    @SuppressWarnings("unchecked") private static <T> void restore(Set<T> set, Set<?> copy) { set.clear(); set.addAll((Set<T>) copy); }

    private static Properties properties() {
        try {
            Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
            Properties copy = new Properties(); copy.putAll((Properties) field.get(null)); return copy;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
