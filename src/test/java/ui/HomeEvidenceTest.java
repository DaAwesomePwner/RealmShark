package ui;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.home.HomeModel;
import tomato.gui.glance.home.HomeModels;
import tomato.gui.glance.home.HomePage;
import tomato.gui.glance.home.LiveHomeSources;
import tomato.gui.kit.Card;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static ui.VisualEvidence.completeButton;
import static ui.VisualEvidence.named;

/**
 * P2 Home evidence (spec §11), Simple and Analyst: populated at 1240×800 and 680×520, fonts 13 and 18; empty, stale and
 * unavailable at 1240×800, font 13; preview (live sources over a fresh TomatoData with a temporary journal and history) at
 * 1240×800, font 13. 16 screenshots; synthetic data only; no capture.
 */
public class HomeEvidenceTest {
    private static final String WINDOW_KEY = "ui.home.window";
    private static final String[] CARDS = {"home-hero", "home-now", "home-today", "home-recent-runs", "home-quests"};
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p2-home");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private DisplayModeModel.Mode savedMode;
    private String savedWindow;
    @Before public void remember() throws Exception {
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        savedWindow = PropertiesManager.getProperty(WINDOW_KEY);
        PropertiesManager.setProperties(WINDOW_KEY, "today");
    }
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
        PropertiesManager.setProperties(WINDOW_KEY, savedWindow == null ? "" : savedWindow);
    }
    @Test public void homeStatesRenderWideAndCompactInSimpleAndAnalyst() throws Exception {
        long now = System.currentTimeMillis();
        for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) {
            for (int[] size : new int[][] {{1240, 800}, {680, 520}})
                for (int font : new int[] {13, 18}) capture("populated", HomeModels.populated(now), mode, size[0], size[1], font);
            capture("empty", HomeModels.empty(), mode, 1240, 800, 13);
            capture("stale", HomeModels.stale(now), mode, 1240, 800, 13);
            capture("unavailable", HomeModels.unavailable(), mode, 1240, 800, 13);
        }
    }
    @Test public void previewShowsEveryEmptyStateFromEmptySources() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters/journal.json"));
        TomatoData data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
        try (SessionStore store = new SessionStore(temp.newFolder("history").toPath(), true, "p2-home-preview")) {
            for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) {
                HomePage[] page = new HomePage[1];
                SwingUtilities.invokeAndWait(() -> {
                    DisplayModeModel.application().set(mode);
                    page[0] = new HomePage(new LiveHomeSources(data, () -> store), HomeModels.NO_ACTIONS);
                    evidence.show(page[0], "Home preview", 1240, 800, 13);
                });
                try {
                    awaitSettled(page[0]);
                    evidence.settle();
                    SwingUtilities.invokeAndWait(() -> {
                        evidence.capture("p2-home-preview-1240-13-" + mode.name().toLowerCase(Locale.ROOT));
                        HomeModel model = page[0].model();
                        for (HomeModel.State state : new HomeModel.State[] {model.hero().state(), model.now().state(), model.today().state(),
                                model.runs().state(), model.quests().state()})
                            errors.checkThat("Empty sources give empty states (spec §7), not loading or unavailable", state, is(HomeModel.State.EMPTY));
                        errors.checkSucceeds(() -> { assertWhole(page[0], mode, 1240); return null; });
                    });
                } finally {
                    SwingUtilities.invokeAndWait(page[0]::close);
                }
            }
        } finally { journal.close(); }
    }
    private void capture(String state, HomeModel model, DisplayModeModel.Mode mode, int width, int height, int font) throws Exception {
        HomePage[] page = new HomePage[1];
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            page[0] = new HomePage(null, HomeModels.NO_ACTIONS);
            page[0].apply(model);
            evidence.show(page[0], "Home " + state, width, height, font);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("p2-home-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT));
            errors.checkSucceeds(() -> { assertWhole(page[0], mode, width); return null; });
            if ("stale".equals(state))
                errors.checkSucceeds(() -> named(page[0], "home-now-empty", EmptyState.class)); // capture off: Now shows its empty state
        });
    }
    /** Every card is shown and never blank, Evidence follows the mode, header controls are whole, nothing scrolls sideways. */
    private static void assertWhole(HomePage page, DisplayModeModel.Mode mode, int width) {
        for (String name : CARDS) {
            Card card = named(page, name, Card.class);
            assertTrue(name + " is shown: " + card.getBounds(), card.isShowing() && card.getWidth() > 0 && card.getHeight() > 0);
            assertEquals(name + " Evidence in " + mode, mode == DisplayModeModel.Mode.ANALYST, card.evidenceShown());
        }
        for (String control : new String[] {"home-build", "home-today-window-0", "home-today-window-1"})
            completeButton(named(page, control, AbstractButton.class));
        JViewport viewport = VisualEvidence.find(page, JScrollPane.class, scroll -> true).getViewport();
        assertEquals("No sideways scrolling at " + width + " px", viewport.getWidth(), viewport.getView().getWidth());
    }
    private static void awaitSettled(HomePage page) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            boolean[] settled = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                HomeModel model = page.model();
                settled[0] = model.hero().state() != HomeModel.State.LOADING && model.now().state() != HomeModel.State.LOADING
                    && model.today().state() != HomeModel.State.LOADING && model.runs().state() != HomeModel.State.LOADING
                    && model.quests().state() != HomeModel.State.LOADING;
            });
            if (settled[0]) return;
            Thread.sleep(50);
        }
        fail("Home stayed in its loading state with empty preview sources");
    }
}
