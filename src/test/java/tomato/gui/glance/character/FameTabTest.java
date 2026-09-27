package tomato.gui.glance.character;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.StatTile;
import tomato.gui.modern.DisplayFormat;
import tomato.history.SessionStore;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.glance.character.FameFixtures.*;
import static tomato.gui.glance.character.SheetFixtures.named;

/**
 * Sheet › Fame on synthetic history: tiles, the chart's description, the captions and the empty state; the presenter reads on open
 * and at most every 30 s while the tab shows, and applies only the newest result for the key still shown.
 */
public class FameTabTest {
    private static final String ORDER = "ui.tabs.character";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder;
    private Consumer<String> savedLog;

    @Before public void remember() {
        savedOrder = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, "");
        savedLog = SheetPresenter.errorLog;
    }
    @After public void restore() {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SheetPresenter.errorLog = savedLog;
    }

    private static String date(long time) { return DisplayFormat.DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())); }
    private static String estimate(String text) { return DisplayValue.estimate(text, "").text(); }
    private static StatTile tile(JComponent root, String name) { return named(root, name, StatTile.class); }
    private static boolean shows(JComponent root, String name) { return named(root, name, JComponent.class).isVisible(); }
    private static String caption(JComponent root, String name) { return named(root, name, JTextArea.class).getText(); }

    @Test public void tilesChartAndCaptionsFollowTheModel() throws Exception {
        FameModel model = FameModel.build(KEY, series(3, 2,
            readings(FIRST, false, T0, 1_000, T0 + 4 * MINUTE, 990, T0 + 12 * MINUTE, 1_200),
            readings(SECOND, false, T1, 1_300, T1 + 9 * MINUTE, 1_400)), record(1_234L, System.currentTimeMillis() - 2 * HOUR), null);
        SwingUtilities.invokeAndWait(() -> {
            FameTab tab = new FameTab();
            assertEquals("character-fame", tab.getName());
            assertFalse("Nothing while loading", shows(tab, "character-fame-content"));
            tab.apply(model);
            assertTrue(shows(tab, "character-fame-content"));
            StatTile fame = tile(tab, "tile-fame");
            assertEquals(model.current().value(), fame.value());
            assertEquals("Fame: 1,234 (stale), as of 2 h ago", fame.getAccessibleContext().getAccessibleName());
            StatTile hour = tile(tab, "tile-fame-hour");
            assertEquals(DisplayValue.State.ESTIMATE, hour.value().state);
            assertEquals("Fame / hour: " + estimate("1,050") + ", session of " + date(T0), hour.getAccessibleContext().getAccessibleName());
            StatTile gained = tile(tab, "tile-recorded-gain");
            assertEquals("Recorded gain: " + estimate("+310") + ", over 2 sessions", gained.getAccessibleContext().getAccessibleName());
            FameChart chart = named(tab, "character-fame-chart", FameChart.class);
            assertTrue(chart.isVisible()); assertFalse(shows(tab, "character-fame-empty"));
            assertEquals(model.sessions(), chart.sessions());
            assertEquals("Fame from " + estimate("1,000") + " to " + estimate("1,400") + " over 2 sessions, " + date(T0) + " to " + date(T1)
                + "; estimated from captured experience", chart.getAccessibleContext().getAccessibleDescription());
            assertTrue(shows(tab, "character-fame-untagged"));
            assertEquals("3 older readings have no recorded account and are not shown", caption(tab, "character-fame-untagged"));
            Banner unreadable = named(tab, "character-fame-unreadable", Banner.class);
            assertTrue(unreadable.isVisible()); assertTrue(unreadable.warns());
            assertEquals("2 saved sessions could not be read", unreadable.text());
            assertFalse(shows(tab, "character-fame-failed"));
        });
    }

    @Test public void oneOfEachReadsSingularAndZeroCountsHideTheirCaption() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FameTab tab = new FameTab();
            tab.apply(FameModel.build(KEY, series(1, 1, readings(FIRST, false, T0, 1_000)), record(null, 0), live(ACCOUNT, 7, 1_500L)));
            assertEquals("1 older reading has no recorded account and is not shown", caption(tab, "character-fame-untagged"));
            assertEquals("1 saved session could not be read", named(tab, "character-fame-unreadable", Banner.class).text());
            assertEquals("Fame: 1,500, Live", tile(tab, "tile-fame").getAccessibleContext().getAccessibleName());
            assertEquals("Fame from " + estimate("1,000") + " to " + estimate("1,000") + " over 1 session, " + date(T0)
                + "; estimated from captured experience", named(tab, "character-fame-chart", FameChart.class).getAccessibleContext().getAccessibleDescription());
            tab.apply(FameModel.build(KEY, series(0, 0, readings(FIRST, false, T0, 1_000)), record(null, 0), null));
            assertFalse(shows(tab, "character-fame-untagged")); assertFalse(shows(tab, "character-fame-unreadable"));
            assertEquals("Unknown fame: a dash, its reason in the tooltip", SheetModelBuilder.FAME_UNKNOWN, tile(tab, "tile-fame").value().detail);
            assertEquals("Fame: " + DisplayFormat.UNAVAILABLE, tile(tab, "tile-fame").getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void noReadingsShowTheEmptyStateAndLoadingShowsNothing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FameTab tab = new FameTab();
            tab.apply(FameModel.build(KEY, series(5, 0), record(1_234L, T1), null));
            EmptyState empty = named(tab, "character-fame-empty", EmptyState.class);
            assertTrue(empty.isVisible());
            assertEquals("No fame history for this character yet", empty.getAccessibleContext().getAccessibleName());
            assertEquals("Fame history records while you play this character with capture on.", empty.getAccessibleContext().getAccessibleDescription());
            assertFalse("No chart without readings", shows(tab, "character-fame-chart"));
            assertTrue("Untagged readings are still counted", shows(tab, "character-fame-untagged"));
            assertEquals(DisplayValue.State.UNKNOWN, tile(tab, "tile-recorded-gain").value().state);
            assertEquals(DisplayValue.State.STALE, tile(tab, "tile-fame").value().state);
            tab.apply(null);
            assertFalse("A new key loading shows nothing of the previous one", shows(tab, "character-fame-content"));
        });
    }

    @Test public void onlyTheNewestResultForTheKeyStillShownApplies() throws Exception {
        Path root = temp.newFolder().toPath();
        FameFixtures.write(root);
        String eight = ACCOUNT + ":8";
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            ManualExecutor worker = new ManualExecutor();
            FamePresenter presenter = presenter(store, worker, new long[]{T1});
            SwingUtilities.invokeAndWait(() -> {
                presenter.open(KEY);   // read A: #7
                presenter.open(eight); // read B: #8, the newest
            });
            assertEquals(2, worker.size());
            worker.run(1); worker.run(0); // B, then the late A for #7
            SwingUtilities.invokeAndWait(() -> {
                assertNull("A read waits for the sheet's build of its key (the Fame tile's inputs)", presenter.model());
                presenter.current(KEY, FameModel.current(KEY, record(1_234L, T1), null)); // a late build for #7: not this key
                assertNull(presenter.model());
                presenter.current(eight, FameModel.current(eight, null, live(ACCOUNT, 8, 700L)));
                assertEquals("A result for another key never applies", eight, presenter.model().key());
                assertEquals(List.of(new FameHistory.Point(T0 + 6 * MINUTE, 300)), presenter.model().sessions().get(0).points());
                assertTrue("The Fame tile follows the sheet's build", presenter.model().current().live());
                assertTrue(shows(presenter.tab(), "character-fame-content"));
                presenter.open(eight); // C: an older read of #8
                presenter.open(eight); // D: the newest
            });
            worker.run(1); // D
            FameFixtures.fame(root, FIRST, sample(ACCOUNT, 8, 350, T0 + 7 * MINUTE)); // only C (run later) sees this reading
            worker.run(0); // C
            SwingUtilities.invokeAndWait(() -> assertEquals("An older result for the same key never replaces the newest one",
                1, presenter.model().sessions().get(0).points().size()));
            SwingUtilities.invokeAndWait(() -> presenter.current(eight, FameModel.current(eight, null, live(ACCOUNT, 8, 750L))));
            SwingUtilities.invokeAndWait(() -> assertEquals("A new tile applies without reading history", "750",
                presenter.model().current().value().text()));
            assertEquals(0, worker.size());
        }
    }

    @Test public void historyIsReadOnOpenThenAtMostEveryThirtySecondsWhileTheFameTabShows() throws Exception {
        Path root = temp.newFolder().toPath();
        FameFixtures.write(root);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            ManualExecutor worker = new ManualExecutor();
            long[] clock = {T1};
            FamePresenter presenter = presenter(store, worker, clock);
            SwingUtilities.invokeAndWait(() -> presenter.open(KEY));
            assertEquals("Once when the sheet opens the key", 1, worker.size());
            worker.run(0);
            SwingUtilities.invokeAndWait(() -> {
                clock[0] = T1 + 10_000; presenter.refresh(true);
                assertEquals("Not again within 30 s", 0, worker.size());
                clock[0] = T1 + 40_000; presenter.refresh(false);
                assertEquals("Never while another tab shows", 0, worker.size());
                presenter.refresh(true);
                assertEquals("Due while the Fame tab shows", 1, worker.size());
                clock[0] = T1 + 50_000; presenter.refresh(true);
                assertEquals(1, worker.size());
            });
        }
    }

    @Test public void aFailedReadIsReportedLoggedOnceAndRetried() throws Exception {
        List<String> logged = new ArrayList<>();
        SheetPresenter.errorLog = logged::add;
        Path root = temp.newFolder().toPath();
        FameFixtures.write(root);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            boolean[] broken = {true};
            ManualExecutor worker = new ManualExecutor();
            long[] clock = {T1};
            FamePresenter presenter = new FamePresenter(new FameTab(), new FameHistory(() -> {
                if (broken[0]) throw new IllegalStateException("Synthetic history failure");
                return store;
            }), () -> clock[0], worker);
            SwingUtilities.invokeAndWait(() -> {
                presenter.open(KEY);
                presenter.current(KEY, FameModel.current(KEY, record(1_234L, T1), null));
            });
            for (long at : new long[]{T1, T1 + 31_000}) { // the open's read, then the retry 30 s later: the same failure, from one call site
                if (at > T1) SwingUtilities.invokeAndWait(() -> { clock[0] = at; presenter.refresh(true); });
                assertEquals(1, worker.size());
                worker.run(0);
                SwingUtilities.invokeAndWait(() -> {
                    Banner failed = named(presenter.tab(), "character-fame-failed", Banner.class);
                    assertTrue(failed.isVisible()); assertTrue(failed.warns());
                    assertEquals("Fame history could not be read: Synthetic history failure. It retries automatically while this tab shows.", failed.text());
                });
            }
            assertEquals("Logged once while the same failure repeats", 1, logged.size());
            assertTrue(logged.get(0), logged.get(0).contains("Synthetic history failure") && logged.get(0).contains("\tat "));
            broken[0] = false;
            SwingUtilities.invokeAndWait(() -> { clock[0] = T1 + 62_000; presenter.refresh(true); });
            worker.run(0);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse("A good read hides the warning", shows(presenter.tab(), "character-fame-failed"));
                assertEquals(2, presenter.model().sessions().size());
            });
        }
    }

    @Test public void theSheetHostsTheFameTabAndItsTileFollowsTheCharacterInGame() throws Exception {
        try (CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("fame.json"))) {
            RealmCharacter c = new RealmCharacter();
            c.charId = 7; c.classNum = 782; c.level = 20; c.fame = 2_000; c.receivedAt = System.currentTimeMillis() - HOUR;
            c.supplied("class"); c.supplied("level"); c.supplied("fame");
            journal.mergeRoster(ACCOUNT, List.of(c));
            TomatoData data = new TomatoData();
            CharacterSheet[] shown = new CharacterSheet[1];
            SwingUtilities.invokeAndWait(() -> {
                shown[0] = new CharacterSheet(new SheetContext(data, journal, RosterDefinitions::empty, new DisplayModeModel(key -> null, (key, value) -> {}),
                    System::currentTimeMillis, PlanningStore.shared()));
                shown[0].open(KEY, "fame");
            });
            CharacterSheet sheet = shown[0];
            // Whatever history this JVM has, it holds no reading of this synthetic account: the saved fame shows, stale.
            await(() -> tile(sheet, "tile-fame").value().state == DisplayValue.State.STALE);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("fame", sheet.selectedTab());
                assertNotNull("The Fame slot holds the Fame tab", named(named(sheet, "character-tab-fame", JPanel.class), "character-fame", FameTab.class));
                assertEquals("2,000", tile(sheet, "tile-fame").value().text());
                assertEquals("Fame: 2,000 (stale), as of 1 h ago", tile(sheet, "tile-fame").getAccessibleContext().getAccessibleName());
                assertTrue(shows(sheet, "character-fame-empty"));
            });
            data.liveCharacter.publish(live(OTHER, 7, 9_000L));
            SwingUtilities.invokeAndWait(sheet::refresh);
            await(() -> sheet.model() != null && sheet.model().live() != null && sheet.model().live().key().equals(OTHER + ":7"));
            SwingUtilities.invokeAndWait(() -> assertEquals("Another account's #7 in game is not this character", DisplayValue.State.STALE,
                tile(sheet, "tile-fame").value().state));
            data.liveCharacter.publish(live(ACCOUNT, 7, 2_500L));
            SwingUtilities.invokeAndWait(sheet::refresh);
            await(() -> tile(sheet, "tile-fame").value().state == DisplayValue.State.KNOWN);
            SwingUtilities.invokeAndWait(() -> assertEquals("Only this account's #7 in game is live", "Fame: 2,500, Live",
                tile(sheet, "tile-fame").getAccessibleContext().getAccessibleName()));
        }
    }

    private static FamePresenter presenter(SessionStore store, Executor worker, long[] clock) throws Exception {
        FamePresenter[] made = new FamePresenter[1];
        SwingUtilities.invokeAndWait(() -> made[0] = new FamePresenter(new FameTab(), new FameHistory(() -> store), () -> clock[0], worker));
        return made[0];
    }

    /** Holds queued reads; {@link #run} runs one on the calling thread (off the EDT, as "character-fame" would), in any order. */
    private static final class ManualExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<>();
        @Override public synchronized void execute(Runnable task) { queued.add(task); }
        synchronized int size() { return queued.size(); }
        void run(int index) {
            Runnable task;
            synchronized (this) { task = queued.remove(index); }
            task.run();
            try { SwingUtilities.invokeAndWait(() -> { }); } catch (Exception e) { throw new AssertionError(e); } // its result reaches the EDT
        }
    }
}
