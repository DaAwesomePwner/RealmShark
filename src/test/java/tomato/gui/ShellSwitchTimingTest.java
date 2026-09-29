package tomato.gui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.InvocationEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.DamageSource;
import tomato.backend.data.DpsSnapshot;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.DungeonListGUI;
import tomato.gui.dps.MeterDpsGUI;
import tomato.gui.glance.character.SheetFixtures;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.loot.LootHighlights;
import tomato.gui.loot.LootPage;
import tomato.gui.loot.LootTab;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.NavEntry;
import tomato.gui.modern.NavLayout;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.runs.DungeonsView;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunsDpsPage;
import tomato.gui.runs.RunsTab;
import tomato.gui.runs.RunsViewRule;
import tomato.gui.stats.LootTestDrops;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFixtures;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * S8 (spec §1, §9): switching between core destinations takes at most 100 ms at p95 on the large synthetic history, on the real
 * shell (research R4 §2–§3; this reuses its probe). Synthetic data only: no capture, no bridge, isolated preferences and history.
 * - Fixture: {@code HomeHistoryFixture.writeLarge(30, 40)} plus {@code CombatFixtures.writeLarge} (1,200 runs with linked combat
 *   records) and 24 characters, in a visible 1240 × 800 frame. Loot › Explore, Chat and the Runs table read saved history (all
 *   sessions), and so do Recordings (the fixture is older than its default 30 days), all settled before measuring. A live fight of
 *   1,200 s × 8 players × 300 enemies × 10 hits/s (96,000 hits) is republished off the EDT, as the capture thread does, before
 *   every Live meter entry: the meter renders only a new snapshot, so without one the switch would cost nothing (R4 §0.7).
 * - P6a: Loot is a page with the tabs Highlights · Explore, so it is entered twice, like Runs &amp; DPS, and a third test switches
 *   its two tabs. The large fixture is dated in 2025, so this app run's session also saves {@value #TODAY_BAGS} bags dated today
 *   (a UT every fifth bag, STs, stat potions): Highlights' Today then shows full tiles, the 200 newest notable drops and four
 *   dungeons, read off the EDT on its first show (a Highlights read in flight counts as loading).
 * - Frame: one EDT turn of the switch (the shell's {@code select}, or the tab strip's), {@code validateInvalidComponents} and
 *   {@code paintDirtyRegions}. Follow-up: every later EDT event until nothing showing is loading (a showing Live meter that has
 *   not rendered the newest snapshot counts as loading) and the EDT is quiet. The longest follow-up event is bounded as well, so
 *   work moved to {@code invokeLater} cannot pass.
 * - Asserted: frame p95 ≤ 100 ms per destination (or tab) and overall; the p95 of the longest follow-up event ≤ 100 ms likewise;
 *   canaries: each Live meter sample rendered the snapshot published just before it (its summary names 300 enemies), Loot ›
 *   Explore and Chat show a saved page, and Loot › Highlights shows a read of today's bags. A miss is measured once more and the
 *   first result printed (the S9 approach); a second miss samples the EDT stack for the report, then fails.
 * - Logged ("S8 " lines in the test output): select, layout, paint, frame, the longest and the summed follow-up events and settle
 *   per destination, the follow-up events of at least 8 ms, the publish times, and a full-page paint per destination.
 */
public class ShellSwitchTimingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    /** The feed opens on its default Cards view; the Runs table is its hidden Table view, read over saved history. */
    @Rule public final RunsViewRule runsView = RunsViewRule.cards();

    private static final long BUDGET = TimeUnit.MILLISECONDS.toNanos(100), SLOW_EVENT = TimeUnit.MILLISECONDS.toNanos(8);
    private static final int WARMUP = 3, ROUNDS = Integer.getInteger("s8.rounds", 20);
    private static final long SETTLE_CAP = TimeUnit.SECONDS.toNanos(3);
    private static final int FIGHT_SECONDS = 1_200, FIGHT_PLAYERS = 8, FIGHT_ENEMIES = 300, FIGHT_RATE = 10;
    private static final String RUNS_PAGE = NavEntry.forId("runs").id(), LOOT_PAGE = NavEntry.forId("loot").id();
    /** Bags this app run's session saves today, so Loot › Highlights' Today has tiles, notable drops and dungeons to show. */
    private static final int TODAY_BAGS = 400;
    /** Preference prefixes cleared, so the shell opens on its defaults; every key the test changes is restored afterwards. */
    private static final String[] CLEARED = {"ux.archive.", CustomizableTabs.PREFIX, "ui.nav.", "ui.quests.", Collapsible.PREFIX,
        "ui.filters.", "ui.order.", DungeonsView.VIEW_KEY, LootHighlights.WINDOW_KEY};

    /**
     * One switch target: a core destination (its sidebar ID and page) and, on Runs &amp; DPS, the tab in front (null elsewhere); on
     * Loot, the Loot tab in front (null elsewhere).
     */
    private record Target(String id, String name, String page, RunsTab tab, LootTab lootTab) {
        Target(String id, String name, String page, RunsTab tab) { this(id, name, page, tab, null); }
    }

    private final Map<Field, Object> original = new LinkedHashMap<>();
    private final Map<String, String> savedPrefs = new LinkedHashMap<>();
    private final Random random = new Random(Long.getLong("s8.seed", 8L));
    private final List<Long> publishes = new ArrayList<>();
    private String temporaryDirectory, historyDirectory;
    private DisplayModeModel.Mode mode;
    private SessionStore store;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;
    private JFrame frame;
    private JPanel cards;
    private RunsDpsPage runs;
    private DpsGUI dps;
    private MeterDpsGUI meter;
    private LootPage loot;
    private LootHighlights highlights;
    private TimingQueue queue;
    private String currentPage;
    private RunsTab currentTab;
    private LootTab currentLootTab;
    private long setupNanos;

    @Before public void open() throws Exception {
        long begun = System.nanoTime();
        Properties props = properties();
        props.stringPropertyNames().forEach(key -> savedPrefs.put(key, props.getProperty(key)));
        for (String key : props.stringPropertyNames())
            for (String prefix : CLEARED) if (key.startsWith(prefix)) { PropertiesManager.setProperties(key, ""); break; }
        PropertiesManager.setProperties("chat.filters", "{}");
        PropertiesManager.setProperties("chat.showIgnoredPlayers", "false");
        SwingUtilities.invokeAndWait(() -> { mode = DisplayModeModel.application().mode(); DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE); });
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        Path root = temp.newFolder("history").toPath();
        historyDirectory = System.getProperty("realmshark.historyDir");
        System.setProperty("realmshark.historyDir", root.toString());
        long written = System.nanoTime();
        HomeHistoryFixture.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        CombatFixtures.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        log("fixture written in " + ms(System.nanoTime() - written) + " ms (" + HomeHistoryFixture.LARGE_SESSIONS + " sessions x "
            + HomeHistoryFixture.LARGE_RUNS + " runs, each with a combat record)");
        store = new SessionStore(root, true, "synthetic");
        // This app run's session saves today's bags (the large fixture is dated in 2025): Loot › Highlights' Today reads them.
        // Dated within the last 20 s, never before local midnight, so all of them are today's.
        long now = System.currentTimeMillis(), first = Math.max(now - 20_000L,
            java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        String[] areas = {"Lost Halls", "Ice Citadel", "Pirate Cave", "Snake Pit"};
        for (int b = 0; b < TODAY_BAGS; b++)
            store.append("loot", LootTestDrops.drop(b % 5 == 0 ? "White" : b % 5 == 1 ? "Orange" : "Purple", areas[b % areas.length],
                first + (now - first) * b / TODAY_BAGS, null,LootTestDrops.item(40_000 + b, b % 5 == 0 ? LootTestDrops.Kind.UT : b % 5 == 1 ? LootTestDrops.Kind.ST : LootTestDrops.Kind.TIERED),
                LootTestDrops.item(2793 + b % 2, LootTestDrops.Kind.POTION), LootTestDrops.item(50_000 + b, LootTestDrops.Kind.PLAIN)));
        store.flush();
        remember(AppHistory.class, "store", store);
        remember(Tomato.class, "preview", true);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) { field.setAccessible(true); original.put(field, field.get(null)); }
        data = new TomatoData();
        CharacterJournal journal = new CharacterJournal(temp.newFolder("journal").toPath().resolve("journal.json"));
        for (int i = 1; i <= 24; i++) journal.observe(CharacterJournalTest.player("s8-fixture", CombatFixtures.CLASSES[i % 8]), i);
        SheetFixtures.inject(data, journal);
        long fought = System.nanoTime();
        int hits = installFight();
        log("live fight: " + hits + " hits, " + FIGHT_PLAYERS + " players x " + FIGHT_ENEMIES + " enemies over " + FIGHT_SECONDS
            + " s, built in " + ms(System.nanoTime() - fought) + " ms");
        long built = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> {
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
            frame = new JFrame("S8 page switch timing - synthetic validation");
            frame.setContentPane(shell);
            frame.setSize(1240, 800);
            frame.setVisible(true);
            cards = named(shell, "workspace-cards", JPanel.class);
            runs = named(shell, "runs-dps-page", RunsDpsPage.class);
            dps = find(shell, DpsGUI.class);
            meter = dps == null ? null : (MeterDpsGUI) field(DpsGUI.class, "displayMeter", dps);
            loot = named(shell, "loot-page", LootPage.class);
            highlights = named(shell, "loot-highlights", LootHighlights.class);
            currentPage = shell.selectedPage();
            currentTab = runs == null ? null : runs.selectedTab();
            currentLootTab = loot == null ? null : loot.selectedTab();
        });
        assertNotNull("Runs & DPS is page " + RUNS_PAGE, runs);
        assertNotNull("The Live meter tab holds the single DPS meter", meter);
        assertEquals("Every Runs & DPS tab is shown (ui.tabs.runs cleared)", tabIds(), edt(() -> runs.tabs().visibleIds()));
        assertNotNull("Loot is page " + LOOT_PAGE, loot);
        assertNotNull("Loot › Highlights", highlights);
        assertEquals("Both Loot tabs are shown (ui.tabs.loot cleared)", List.of(LootTab.HIGHLIGHTS.id(), LootTab.EXPLORE.id()), edt(() -> loot.tabs().visibleIds()));
        log("shell built and shown in " + ms(System.nanoTime() - built) + " ms; landing page " + currentPage);
        queue = new TimingQueue();
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
        settle("the shell", TimeUnit.SECONDS.toNanos(20));
        // Saved history, as a user browsing it leaves these views: Loot, Chat and the Runs table over every session, and Recordings
        // over all sessions (read while its tab shows, so the wait covers the read); then back to the page the shell opened on.
        SwingUtilities.invokeAndWait(() -> { for (String module : new String[] {"loot", "chat", "runs"}) workspace(module).selectSession(SessionStore.ALL); });
        settle("saved views", TimeUnit.SECONDS.toNanos(30));
        SwingUtilities.invokeAndWait(() -> { shell.select(RUNS_PAGE); runs.bring(RunsTab.RECORDINGS); });
        settle("saved views", TimeUnit.SECONDS.toNanos(30));
        SwingUtilities.invokeAndWait(() -> named(runs, "encounter-scope-1", AbstractButton.class).doClick());
        settle("saved views", TimeUnit.SECONDS.toNanos(30));
        String recordings = edt(() -> named(runs, "encounter-summary", javax.swing.text.JTextComponent.class).getText());
        // Loot › Highlights reads today's bags on its first show, and Explore shows its saved page once (P6a).
        SwingUtilities.invokeAndWait(() -> { shell.select(LOOT_PAGE); loot.bring(LootTab.HIGHLIGHTS); });
        settle("Loot highlights", TimeUnit.SECONDS.toNanos(30));
        String read = edt(this::highlightsProblem);
        assertNull("Loot › Highlights read today's bags: " + read, read);
        SwingUtilities.invokeAndWait(() -> loot.bring(LootTab.EXPLORE));
        settle("saved views", TimeUnit.SECONDS.toNanos(30));
        String landing = currentPage;
        SwingUtilities.invokeAndWait(() -> { runs.bring(RunsTab.FEED); loot.bring(LootTab.HIGHLIGHTS); shell.select(landing); });
        currentTab = RunsTab.FEED;
        currentLootTab = LootTab.HIGHLIGHTS;
        settle("saved views", TimeUnit.SECONDS.toNanos(30));
        setupNanos = System.nanoTime() - begun;
        log("saved views settled (Recordings: " + recordings + "; Loot highlights: " + edt(() -> named(highlights, "loot-highlights-source", JLabel.class).getText()) + "); setup " + ms(setupNanos) + " ms");
    }

    @After public void close() throws Exception {
        if (queue != null) queue.detach();
        SwingUtilities.invokeAndWait(() -> {
            try { if (gui != null) gui.closeWorkspace(); }
            finally {
                if (frame != null) frame.dispose();
                try { for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue()); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
                if (mode != null) DisplayModeModel.application().set(mode);
            }
        });
        Properties props = properties();
        Set<String> keys = new HashSet<>(props.stringPropertyNames());
        keys.addAll(savedPrefs.keySet());
        for (String key : keys)
            if (!savedPrefs.getOrDefault(key, "").equals(props.getProperty(key, ""))) PropertiesManager.setProperties(key, savedPrefs.getOrDefault(key, ""));
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (historyDirectory != null) System.setProperty("realmshark.historyDir", historyDirectory);
        else System.clearProperty("realmshark.historyDir");
        if (store != null) store.close();
    }

    /**
     * The core destinations of the default sidebar, Runs & DPS entered twice (its Feed and its Live meter tab) and Loot entered
     * twice (its Highlights and its Explore tab), each tab selected while its page is hidden: 3 warm-up rounds, then 20 rounds
     * (160 samples) in a seeded order that never enters the page shown.
     */
    @Test public void switchingCoreDestinationsStaysWithinOneHundredMsAtP95() throws Exception {
        long begun = System.nanoTime();
        List<Target> targets = destinations();
        assertEquals("Six core destinations with Runs & DPS and Loot each entered twice (S7, P6a)", 8, targets.size());
        Pass pass = pass(targets, WARMUP, ROUNDS, target -> target.page, null);
        List<String> misses = pass.misses(targets);
        if (!misses.isEmpty()) {
            report("first run, missed " + misses + "; measuring once more", pass, targets);
            pass = pass(targets, WARMUP, ROUNDS, target -> target.page, null);
            misses = pass.misses(targets);
        }
        report("core destinations, large history, saved views, live fight of " + FIGHT_ENEMIES + " enemies", pass, targets);
        logFullPaints(targets);
        if (!misses.isEmpty()) diagnose(targets, target -> target.page);
        log("core destinations: " + ms(System.nanoTime() - begun) + " ms measuring, " + ms(setupNanos) + " ms setup");
        assertTrue("S8: core destination switches within 100 ms at p95 (frame and longest follow-up event), canaries hold: " + misses, misses.isEmpty());
    }

    /** The four Runs & DPS tabs, switched through the tab strip while the page shows: 3 warm-up rounds, then 20 (80 samples). */
    @Test public void switchingRunsAndDpsTabsStaysWithinOneHundredMsAtP95() throws Exception {
        long begun = System.nanoTime();
        List<Target> targets = new ArrayList<>();
        for (RunsTab tab : RunsTab.values()) targets.add(new Target("runs", "Runs & DPS tab > " + tab.title(), RUNS_PAGE, tab));
        SwingUtilities.invokeAndWait(() -> { runs.tabs().select(RunsTab.FEED.id()); shell.select(RUNS_PAGE); });
        currentPage = RUNS_PAGE; currentTab = RunsTab.FEED;
        settle("the Feed tab", SETTLE_CAP);
        Pass pass = pass(targets, WARMUP, ROUNDS, target -> target.tab.ordinal(), null);
        List<String> misses = pass.misses(targets);
        if (!misses.isEmpty()) {
            report("first run, missed " + misses + "; measuring once more", pass, targets);
            pass = pass(targets, WARMUP, ROUNDS, target -> target.tab.ordinal(), null);
            misses = pass.misses(targets);
        }
        report("Runs & DPS tabs, large history, saved views, live fight of " + FIGHT_ENEMIES + " enemies", pass, targets);
        if (!misses.isEmpty()) diagnose(targets, target -> target.tab.ordinal());
        log("Runs & DPS tabs: " + ms(System.nanoTime() - begun) + " ms measuring, " + ms(setupNanos) + " ms setup");
        assertTrue("S8: Runs & DPS tab switches within 100 ms at p95 (frame and longest follow-up event), canaries hold: " + misses, misses.isEmpty());
    }

    /** P6a: the two Loot tabs, switched through the tab strip while the page shows: 3 warm-up rounds, then 20 (40 samples). */
    @Test public void switchingLootTabsStaysWithinOneHundredMsAtP95() throws Exception {
        long begun = System.nanoTime();
        List<Target> targets = new ArrayList<>();
        for (LootTab tab : LootTab.values()) targets.add(new Target("loot", "Loot tab > " + tab.title(), LOOT_PAGE, null, tab));
        SwingUtilities.invokeAndWait(() -> { loot.tabs().select(LootTab.HIGHLIGHTS.id()); shell.select(LOOT_PAGE); });
        currentPage = LOOT_PAGE; currentLootTab = LootTab.HIGHLIGHTS;
        settle("the Highlights tab", SETTLE_CAP);
        Pass pass = pass(targets, WARMUP, ROUNDS, target -> target.lootTab.ordinal(), null);
        List<String> misses = pass.misses(targets);
        if (!misses.isEmpty()) {
            report("first run, missed " + misses + "; measuring once more", pass, targets);
            pass = pass(targets, WARMUP, ROUNDS, target -> target.lootTab.ordinal(), null);
            misses = pass.misses(targets);
        }
        report("Loot tabs, large history, saved views, " + TODAY_BAGS + " bags saved today", pass, targets);
        if (!misses.isEmpty()) diagnose(targets, target -> target.lootTab.ordinal());
        log("Loot tabs: " + ms(System.nanoTime() - begun) + " ms measuring, " + ms(setupNanos) + " ms setup");
        assertTrue("S8: Loot tab switches within 100 ms at p95 (frame and longest follow-up event), canaries hold: " + misses, misses.isEmpty());
    }

    /**
     * {@code new NavLayout(k -> null, (k, v) -> {}).core()}: the default core destinations, Runs & DPS as Feed and as Live meter,
     * Loot as Highlights and as Explore.
     */
    private static List<Target> destinations() {
        List<Target> result = new ArrayList<>();
        for (NavEntry entry : new NavLayout(k -> null, (k, v) -> {}).core()) {
            if (entry.id().equals(LOOT_PAGE)) {
                for (LootTab tab : LootTab.values()) result.add(new Target(entry.id(), entry.title() + " > " + tab.title(), entry.id(), null, tab));
                continue;
            }
            if (!entry.id().equals(RUNS_PAGE)) { result.add(new Target(entry.id(), entry.title(), entry.id(), null)); continue; }
            for (RunsTab tab : new RunsTab[] {RunsTab.FEED, RunsTab.LIVE_METER})
                result.add(new Target(entry.id(), entry.title() + " > " + tab.title(), entry.id(), tab));
        }
        return result;
    }

    private static List<String> tabIds() {
        List<String> ids = new ArrayList<>();
        for (RunsTab tab : RunsTab.values()) ids.add(tab.id());
        return ids;
    }

    // ---- measuring ----

    /** Warm-up rounds, then measured rounds; each round visits every target once in a seeded order ({@link #order}). */
    private Pass pass(List<Target> targets, int warmup, int rounds, Function<Target, Object> key, Sampler sampler) throws Exception {
        Pass pass = new Pass();
        for (int round = 0; round < warmup + rounds; round++)
            for (Target target : order(targets, key)) {
                Sample sample = enter(target, pass, sampler);
                if (round >= warmup) pass.samples.add(sample);
            }
        return pass;
    }

    /** A seeded shuffle in which no target has the key of the one before it (the page, or the tab), starting from what is shown. */
    private List<Target> order(List<Target> targets, Function<Target, Object> key) {
        List<Target> order = new ArrayList<>(targets);
        Object shown = key.apply(new Target("", "", currentPage, currentTab, currentLootTab));
        while (true) {
            Collections.shuffle(order, random);
            Object previous = shown;
            boolean alternates = true;
            for (Target target : order) { Object next = key.apply(target); if (Objects.equals(next, previous)) { alternates = false; break; } previous = next; }
            if (alternates) return order;
        }
    }

    /**
     * One switch to {@code target}: a live fight published first when it is the Live meter; a page switch (a Runs & DPS or Loot
     * tab is selected while the page is hidden) or, on the page shown, a tab switch; then the frame, the follow-up and the canary.
     */
    private Sample enter(Target target, Pass pass, Sampler sampler) throws Exception {
        DpsSnapshot published = null;
        if (target.tab == RunsTab.LIVE_METER) {
            DpsSnapshot before = DpsGUI.latestSnapshot();
            long start = System.nanoTime();
            DpsGUI.updateMapPacket(data);   // off the EDT, as the capture thread publishes
            publishes.add(System.nanoTime() - start);
            published = DpsGUI.latestSnapshot();
            // Without a new snapshot the meter renders nothing and the switch costs nothing (R4 §0.7): never measure that.
            if (published == null || published == before) pass.canary(target, "no new live snapshot was published before the switch");
        }
        Runnable action;
        if (!target.page.equals(currentPage)) {
            if (target.tab != null) SwingUtilities.invokeAndWait(() -> runs.tabs().select(target.tab.id()));
            if (target.lootTab != null) SwingUtilities.invokeAndWait(() -> loot.tabs().select(target.lootTab.id()));
            action = () -> shell.select(target.page);
        } else if (target.lootTab != null) action = () -> loot.tabs().select(target.lootTab.id());
        else action = () -> runs.tabs().select(target.tab.id());
        Sample sample = measure(target, action, sampler);
        currentPage = target.page;
        if (target.tab != null) currentTab = target.tab;
        if (target.lootTab != null) currentLootTab = target.lootTab;
        String problem = canary(target, published);
        if (problem != null) pass.canary(target, problem);
        return sample;
    }

    /** The frame (action, layout and dirty-region paint in one EDT turn), then every EDT event until the destination settles. */
    private Sample measure(Target target, Runnable action, Sampler sampler) throws Exception {
        Sample s = new Sample(target);
        queue.clear();
        SwingUtilities.invokeAndWait(() -> {
            RepaintManager repaints = RepaintManager.currentManager(shell);
            if (sampler != null) sampler.phase = "switch";
            long t0 = System.nanoTime();
            action.run();
            long t1 = System.nanoTime();
            if (sampler != null) sampler.phase = "layout";
            repaints.validateInvalidComponents();
            long t2 = System.nanoTime();
            if (sampler != null) sampler.phase = "paint";
            repaints.paintDirtyRegions();
            long t3 = System.nanoTime();
            if (sampler != null) sampler.phase = null;
            s.start = t0; s.select = t1 - t0; s.layout = t2 - t1; s.paint = t3 - t2; s.frameEnd = t3;
        });
        if (sampler != null) sampler.follow = true;
        s.timedOut = awaitQuiet(SETTLE_CAP) < 0;
        if (sampler != null) sampler.follow = false;
        long lastEnd = s.frameEnd;
        for (TimedEvent event : queue.since(s.frameEnd)) {
            String what = event.describe();
            if (what.contains(ShellSwitchTimingTest.class.getSimpleName())) continue;   // this test's own EDT checks
            long duration = event.end - event.start;
            s.followBusy += duration;
            if (duration > s.followMax) { s.followMax = duration; s.followMaxWhat = what; }
            if (duration >= SLOW_EVENT) s.slow.put(shortEvent(what), Math.max(duration, s.slow.getOrDefault(shortEvent(what), 0L)));
            if (duration >= 1_000_000) lastEnd = Math.max(lastEnd, event.end);
        }
        s.settle = lastEnd - s.start;
        return s;
    }

    /**
     * After the destination settled: the Live meter rendered the snapshot published just before the switch (its summary names
     * every enemy, live), Loot › Explore and Chat show a saved page, Loot › Highlights shows its read of today's bags, and a Runs &amp;
     * DPS or Loot target shows its tab. Null when it holds.
     */
    private String canary(Target target, DpsSnapshot published) throws Exception {
        return edt(() -> {
            if (!shell.selectedPage().equals(target.page)) return "page " + shell.selectedPage() + " is shown";
            if (target.tab != null && runs.selectedTab() != target.tab) return "the " + runs.selectedTab() + " tab is in front";
            if (target.lootTab != null && loot.selectedTab() != target.lootTab) return "the " + loot.selectedTab() + " Loot tab is in front";
            if (target.lootTab == LootTab.HIGHLIGHTS) {
                if (!highlights.isShowing()) return "Loot highlights are not showing";
                String problem = highlightsProblem();
                if (problem != null) return problem;
            }
            if (target.lootTab == LootTab.EXPLORE && !workspace("loot").isShowing()) return "the Loot workspace is not showing";
            if (target.tab == RunsTab.LIVE_METER) {
                if (!meter.isShowing()) return "the meter is not showing";
                if (field(DpsGUI.class, "rendered", dps) != published) return "the meter did not render the snapshot published before the switch";
                String summary = ((JLabel) field(MeterDpsGUI.class, "summary", meter)).getText();
                String enemies = DisplayFormat.formatInteger(FIGHT_ENEMIES) + " enemies";
                if (summary == null || !summary.contains(enemies) || !summary.contains("LIVE")) return "the meter summary reads '" + summary + "', not LIVE with " + enemies;
            }
            if (target.id.equals("loot") || target.id.equals("chat")) {
                ArchiveWorkspace<?, ?, ?> workspace = workspace(target.id);
                if (!workspace.state().archive) return "the workspace left saved history";
                if (workspace.displayedPage() == null) return "no saved page is displayed";
            }
            return null;
        });
    }

    /** EDT: Loot › Highlights shows a read of today's bags (content, UT drops counted, notable drops listed); null when it does. */
    private String highlightsProblem() {
        Object state = call(highlights, "state");
        if (!"content".equals(state)) return "Loot highlights show their " + state + " state";
        String ut = named(highlights, "loot-tile-ut", tomato.gui.kit.StatTile.class).valueText();
        if (!String.valueOf(TODAY_BAGS / 5).equals(ut)) return "Loot highlights count " + ut + " UT drops, not " + TODAY_BAGS / 5;
        JList<?> notable = named(highlights, "loot-notable-grid", JList.class);
        if (notable == null || notable.getModel().getSize() == 0) return "Loot highlights list no notable drop";
        return null;
    }

    /** Setup: {@link #awaitQuiet} within {@code cap}, or fails (measuring an unsettled shell would time its reads, not the switch). */
    private void settle(String what, long cap) throws Exception {
        assertTrue(what + " settled within " + ms(cap) + " ms", awaitQuiet(cap) >= 0);
    }

    /** Waits until nothing showing is loading and three EDT round trips in a row take under 2 ms; -1 on timeout. */
    private long awaitQuiet(long cap) throws Exception {
        long start = System.nanoTime();
        int quiet = 0;
        while (System.nanoTime() < start + cap) {
            long r0 = System.nanoTime();
            boolean busy = edtCheck(() -> busy(cards) || renderPending());
            if (!busy && System.nanoTime() - r0 < 2_000_000) { if (++quiet >= 3) return System.nanoTime() - start; } else quiet = 0;
            Thread.sleep(4);
        }
        return -1;
    }

    /**
     * EDT: the Live meter shows but has not rendered the newest snapshot yet. Counted as loading, so a render deferred past the
     * frame is awaited and measured (its event is a follow-up, bounded like any other) and the canary never races it.
     */
    private boolean renderPending() {
        return meter.isShowing() && field(DpsGUI.class, "latest", dps) != field(DpsGUI.class, "rendered", dps);
    }

    /** EDT: a showing archive workspace, run feed, Dungeons view, Recordings tab or Loot highlights has a read in flight. */
    private static boolean busy(Container root) {
        for (Component child : root.getComponents()) {
            if (!child.isShowing()) continue;
            if (child instanceof ArchiveWorkspace && ((ArchiveWorkspace<?, ?, ?>) child).loading()) return true;
            if ((child instanceof RunFeedView || child instanceof DungeonsView || child instanceof DungeonListGUI || child instanceof LootHighlights)
                && loading(child)) return true;
            if (child instanceof Container && busy((Container) child)) return true;
        }
        return false;
    }

    private static final Map<Class<?>, Method> LOADING = new HashMap<>();
    /** The view's own package-private {@code loading()} (reads in flight). */
    private static boolean loading(Component view) {
        try {
            Method method = LOADING.get(view.getClass());
            if (method == null) {
                Class<?> type = view.getClass();
                while (method == null && type != null) {
                    try { method = type.getDeclaredMethod("loading"); } catch (NoSuchMethodException e) { type = type.getSuperclass(); }
                }
                if (method == null) throw new AssertionError("No loading() on " + view.getClass());
                method.setAccessible(true);
                LOADING.put(view.getClass(), method);
            }
            return (Boolean) method.invoke(view);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /**
     * The live fight in {@code data} ({@link CombatFixtures#installLive}): {@value #FIGHT_PLAYERS} players each hitting
     * {@value #FIGHT_RATE} times a second for {@value #FIGHT_SECONDS} s across {@value #FIGHT_ENEMIES} enemies of 40 types (two
     * of them bosses), with four damage sources. Returns the hit count.
     */
    private int installFight() {
        CombatFixtures.Fight fight = CombatFixtures.fight("Lost Halls");
        List<Entity> party = new ArrayList<>(), foes = new ArrayList<>();
        for (int p = 1; p <= FIGHT_PLAYERS; p++) party.add(fight.player(p, CombatFixtures.CLASSES[(p - 1) % 8], "Player" + p));
        for (int e = 0; e < FIGHT_ENEMIES; e++)
            foes.add(fight.enemy(1000 + e, 5000 + e % 40, "Enemy type " + (e % 40), 50_000 + 1000 * (e % 7), e % 40 >= 38));
        DamageSource[] sources = {DamageSource.WEAPON, DamageSource.ABILITY, DamageSource.SUMMON, DamageSource.ITEM_EFFECT};
        long start = System.currentTimeMillis() - FIGHT_SECONDS * 1000L;
        int hits = 0;
        for (int s = 0; s < FIGHT_SECONDS; s++) for (int p = 0; p < FIGHT_PLAYERS; p++) for (int k = 0; k < FIGHT_RATE; k++, hits++)
            fight.hit(foes.get((s * 7 + p * 3 + k) % FIGHT_ENEMIES), party.get(p), 100 + (p * 37 + s * 13 + k * 7) % 400,
                start + s * 1000L + k * (1000L / FIGHT_RATE), sources[(s + p + k) % sources.length], 10_000 + (s + k) % 8);
        CombatFixtures.installLive(data, fight.ticks(start, FIGHT_SECONDS * 1000L).build());
        return hits;
    }

    /** Logged only: a full-page paint of the shell on each destination, settled (5 paints after one warm-up; median and max). */
    private void logFullPaints(List<Target> targets) throws Exception {
        StringBuilder line = new StringBuilder("full-page paint (logged), ms median/max:");
        for (Target target : order(targets, t -> t.page)) {
            enter(target, new Pass(), null);
            long[] paints = new long[5];
            for (int i = -1; i < paints.length; i++) {
                int sample = i;
                SwingUtilities.invokeAndWait(() -> {
                    long t0 = System.nanoTime();
                    shell.paintImmediately(0, 0, shell.getWidth(), shell.getHeight());
                    if (sample >= 0) paints[sample] = System.nanoTime() - t0;
                });
            }
            Arrays.sort(paints);
            line.append(String.format(Locale.ROOT, "  %s %s/%s", target.name, fmt(paints[2]), fmt(paints[4])));
        }
        log(line.toString());
    }

    /** After a second miss: one sampled pass (5 rounds) to name the handlers that dominate the EDT; the report only. */
    private void diagnose(List<Target> targets, Function<Target, Object> key) throws Exception {
        Sampler sampler = new Sampler(edtThread());
        try { pass(targets, 0, 5, key, sampler); }
        finally { sampler.close(); }
        sampler.report();
    }

    // ---- results and reporting ----

    private static final class Pass {
        final List<Sample> samples = new ArrayList<>();
        /** Each failed canary ("target: problem") and on how many switches it failed, warm-ups included. */
        final Map<String, Integer> canaries = new LinkedHashMap<>();

        void canary(Target target, String problem) { canaries.merge(target.name + ": " + problem, 1, Integer::sum); }

        /** Each target's and the overall frame p95 and longest-follow-up p95 above 100 ms, and every failed canary. */
        List<String> misses(List<Target> targets) {
            List<String> misses = new ArrayList<>();
            canaries.forEach((problem, count) -> misses.add(problem + " (" + count + (count == 1 ? " switch)" : " switches)")));
            for (Target target : targets) misses.addAll(misses(target.name, rows(target)));
            misses.addAll(misses("overall", samples.toArray(new Sample[0])));
            return misses;
        }
        Sample[] rows(Target target) { return samples.stream().filter(s -> s.to.equals(target)).toArray(Sample[]::new); }
        private static List<String> misses(String name, Sample[] rows) {
            List<String> misses = new ArrayList<>();
            if (rows.length == 0) { misses.add(name + ": no samples"); return misses; }
            long frame = p95(sorted(rows, Sample::frame)), follow = p95(sorted(rows, s -> s.followMax));
            if (frame > BUDGET) misses.add(name + " frame p95 " + fmt(frame) + " ms");
            if (follow > BUDGET) misses.add(name + " longest follow-up event p95 " + fmt(follow) + " ms");
            return misses;
        }
    }

    private void report(String label, Pass pass, List<Target> targets) {
        log(label + ": " + pass.samples.size() + " switches (" + ROUNDS + " rounds x " + targets.size() + "), ms as p50/p95/max");
        String columns = "  %-34s %-17s %-17s %-17s %-17s %-17s %-17s %-17s";
        log(String.format(Locale.ROOT, columns, "destination", "select", "layout", "paint", "frame", "follow-max", "follow-sum", "settle"));
        for (Target target : targets) line(columns, target.name, pass.rows(target));
        line(columns, "ALL", pass.samples.toArray(new Sample[0]));
        long timeouts = pass.samples.stream().filter(s -> s.timedOut).count();
        if (timeouts > 0) log("  settle timed out (3 s) on " + timeouts + " switches");
        Map<String, long[]> slow = new TreeMap<>();
        for (Sample s : pass.samples)
            for (Map.Entry<String, Long> event : s.slow.entrySet()) {
                long[] seen = slow.computeIfAbsent(s.to.name + " <- " + event.getKey(), k -> new long[2]);
                seen[0]++; seen[1] = Math.max(seen[1], event.getValue());
            }
        if (slow.isEmpty()) log("  no follow-up event took 8 ms or more");
        else {
            log("  follow-up events of 8 ms or more (destination <- event: switches, max ms):");
            for (Map.Entry<String, long[]> event : slow.entrySet()) log("    " + event.getKey() + ": " + event.getValue()[0] + ", " + fmt(event.getValue()[1]));
        }
        if (!publishes.isEmpty()) {
            long[] times = publishes.stream().mapToLong(Long::longValue).sorted().toArray();
            log("  live fight publishes so far (DpsGUI.updateMapPacket off the EDT): " + times.length + ", " + stats(times));
        }
        if (!pass.canaries.isEmpty()) log("  canaries failed: " + pass.canaries);
    }

    private static void line(String columns, String name, Sample[] rows) {
        log(String.format(Locale.ROOT, columns, name + " (" + rows.length + ")",
            stats(sorted(rows, s -> s.select)), stats(sorted(rows, s -> s.layout)), stats(sorted(rows, s -> s.paint)),
            stats(sorted(rows, Sample::frame)), stats(sorted(rows, s -> s.followMax)), stats(sorted(rows, s -> s.followBusy)),
            stats(sorted(rows, s -> s.settle))));
    }

    private static long[] sorted(Sample[] rows, java.util.function.ToLongFunction<Sample> value) {
        return Arrays.stream(rows).mapToLong(value).sorted().toArray();
    }
    private static String shortEvent(String what) {
        if (what == null) return "-";
        int at = what.indexOf("runnable=");
        String s = at < 0 ? what : what.substring(at + 9);
        int end = s.indexOf(','); if (end > 0) s = s.substring(0, end);
        int lambda = s.indexOf("$$Lambda"); if (lambda > 0) return s.substring(0, lambda) + "$$Lambda";
        int hash = s.indexOf('@'); return hash > 0 ? s.substring(0, hash) : s;
    }
    private static String stats(long[] sorted) {
        return sorted.length == 0 ? "-" : fmt(sorted[sorted.length / 2]) + "/" + fmt(p95(sorted)) + "/" + fmt(sorted[sorted.length - 1]);
    }
    /** The nearest-rank p95 ({@code sorted[ceil(.95 n) - 1]}; with n = 20, the 19th sample: one outlier is tolerated). */
    private static long p95(long[] sorted) { return sorted[(int) Math.ceil(sorted.length * .95) - 1]; }
    private static String fmt(long nanos) { return String.format(Locale.ROOT, "%.1f", nanos / 1e6); }
    private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    private static void log(String line) { System.out.println("S8 " + line); }

    // ---- support ----

    private static final class Sample {
        final Target to;
        long start, select, layout, paint, frameEnd, followBusy, followMax, settle;
        String followMaxWhat;
        boolean timedOut;
        /** Follow-up events of at least 8 ms: the event and its longest duration. */
        final Map<String, Long> slow = new LinkedHashMap<>();
        Sample(Target to) { this.to = to; }
        long frame() { return select + layout + paint; }
    }

    private static final class TimedEvent {
        final AWTEvent event; final long start, end;
        TimedEvent(AWTEvent event, long start, long end) { this.event = event; this.start = start; this.end = end; }
        String describe() { return event instanceof InvocationEvent ? event.toString() : event.getClass().getSimpleName() + "#" + event.getID(); }
    }

    /** Times every EDT dispatch (a pushed EventQueue keeps the same dispatch thread). */
    private static final class TimingQueue extends EventQueue {
        private final List<TimedEvent> events = new ArrayList<>();
        @Override protected void dispatchEvent(AWTEvent event) {
            long start = System.nanoTime();
            try { super.dispatchEvent(event); }
            finally { long end = System.nanoTime(); synchronized (this) { if (events.size() < 200_000) events.add(new TimedEvent(event, start, end)); } }
        }
        synchronized void clear() { events.clear(); }
        synchronized List<TimedEvent> since(long nanos) {
            List<TimedEvent> result = new ArrayList<>();
            for (TimedEvent e : events) if (e.start >= nanos) result.add(e);
            return result;
        }
        void detach() { pop(); }
    }

    /**
     * Diagnostics only: samples the EDT stack every ~0.5 ms during the switch turn ("phase: handler .. deepest app frame", the
     * handler being the outermost app frame under the switch) and its follow-up events. Perturbs timing; counts only.
     */
    private static final class Sampler implements AutoCloseable {
        private static final Set<String> ENTRY = Set.of(WorkspaceShell.class.getName(), CustomizableTabs.class.getName());
        private final Thread edt, thread;
        private final Map<String, Integer> counts = new HashMap<>();
        private volatile boolean running = true, follow;
        volatile String phase;
        private int total;
        Sampler(Thread edt) { this.edt = edt; thread = new Thread(this::run, "s8-sampler"); thread.setDaemon(true); thread.start(); }
        private void run() {
            while (running) {
                String p = phase;
                if (p != null || follow) {
                    StackTraceElement[] stack = edt.getStackTrace();
                    String key = p != null ? p + ": " + outer(stack) + " .. " + deepest(stack)
                        : !dispatching(stack) || own(stack) ? null : "follow-up: " + outer(stack) + " .. " + deepest(stack);
                    if (key != null) synchronized (this) { counts.merge(key, 1, Integer::sum); total++; }
                }
                LockSupport.parkNanos(500_000);
            }
        }
        private static boolean app(StackTraceElement e) {
            return e.getClassName().startsWith("tomato.") && !e.getClassName().startsWith(ShellSwitchTimingTest.class.getName());
        }
        private static boolean dispatching(StackTraceElement[] stack) {
            for (StackTraceElement e : stack) if (e.getClassName().equals("java.awt.EventQueue") && e.getMethodName().equals("dispatchEvent")) return true;
            return false;
        }
        /** This test's own EDT checks (not its timing queue, which wraps every dispatch). */
        private static boolean own(StackTraceElement[] stack) {
            for (StackTraceElement e : stack)
                if (e.getClassName().startsWith(ShellSwitchTimingTest.class.getName()) && !e.getClassName().contains("TimingQueue")
                    && (e.getMethodName().startsWith("lambda$") || e.getMethodName().equals("busy"))) return true;
            return false;
        }
        /** The outermost app frame that is not the switch entry point itself (the shell's select, the tab strip). */
        private static String outer(StackTraceElement[] stack) {
            for (int i = stack.length - 1; i >= 0; i--) if (app(stack[i]) && !ENTRY.contains(stack[i].getClassName())) return frame(stack[i]);
            return "(jdk)";
        }
        private static String deepest(StackTraceElement[] stack) {
            for (StackTraceElement e : stack) if (app(e)) return frame(e);
            return stack.length == 0 ? "-" : "(jdk) " + frame(stack[0]);
        }
        private static String frame(StackTraceElement e) {
            String c = e.getClassName(); c = c.substring(c.lastIndexOf('.') + 1);
            int lambda = c.indexOf("$$Lambda"); if (lambda >= 0) c = c.substring(0, lambda) + "$$Lambda";
            return c + "." + e.getMethodName();
        }
        void report() {
            List<Map.Entry<String, Integer>> sorted;
            synchronized (this) { sorted = new ArrayList<>(counts.entrySet()); }
            sorted.sort((x, y) -> y.getValue() - x.getValue());
            log("sampled EDT stacks (0.5 ms, 5 rounds): " + total + " samples; top 25");
            for (int i = 0; i < Math.min(25, sorted.size()); i++) log(String.format(Locale.ROOT, "  %5d  %s", sorted.get(i).getValue(), sorted.get(i).getKey()));
        }
        @Override public void close() throws InterruptedException { running = false; thread.join(1000); }
    }

    private static Thread edtThread() throws Exception {
        Thread[] t = new Thread[1];
        SwingUtilities.invokeAndWait(() -> t[0] = Thread.currentThread());
        return t[0];
    }
    private ArchiveWorkspace<?, ?, ?> workspace(String module) {
        ArchiveWorkspace<?, ?, ?> result = named(shell, module + "-session-view", ArchiveWorkspace.class);
        assertNotNull(module + " workspace", result);
        return result;
    }
    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); field.set(null, next);
    }
    private static Object field(Class<?> type, String name, Object owner) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    /** The owner's own package-private no-argument method (a view's test accessor, such as Loot highlights' {@code state()}). */
    private static Object call(Object owner, String name) {
        try { Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Properties properties() {
        Properties copy = new Properties(); copy.putAll((Properties) field(PropertiesManager.class, "properties", null)); return copy;
    }
    interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> value) throws Exception {
        Object[] result = new Object[1]; Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> { try { result[0] = value.get(); } catch (Exception e) { failure[0] = e; } });
        if (failure[0] != null) throw failure[0];
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }
    private static boolean edtCheck(BooleanSupplier condition) throws Exception {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = condition.getAsBoolean());
        return result[0];
    }
    private static <T> T find(Container root, Class<T> type) { return named(root, null, type); }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (name == null || name.equals(c.getName()))) return type.cast(c);
            if (c instanceof Container) { T found = named((Container) c, name, type); if (found != null) return found; }
        }
        return null;
    }
}
