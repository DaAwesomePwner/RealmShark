package tomato.gui.glance.home;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.NavLayout;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.WorkspaceShell;
import ui.UiTestLayout;
import ui.VisualEvidence;
import static org.junit.Assert.*;

/**
 * Spec §6.1: hero row; Now | Today; Recent runs | Quests at 1.5 : 1; stacked in order below 1000 px; never wider than the
 * window. S1: in the real shell at 1240×800 the maxed state, today's fame and the last run's loot show without a click.
 */
public class HomePageLayoutTest {
    private static final String[] ORDER = {"home-hero", "home-now", "home-today", "home-recent-runs", "home-quests"};
    /** S1: the current character's maxed state, today's fame and the last run's loot. */
    private static final String[] S1 = {"home-hero-maxed", "home-tile-fame", "home-run-0-loot-0"};
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p2-layout");
    private final Map<String, String> prefs = new HashMap<>();

    @Test public void wideLayoutPairsCardsAndNarrowWindowsStackThemInOrder() throws Exception {
        long now = System.currentTimeMillis();
        HomePage[] page = new HomePage[1];
        SwingUtilities.invokeAndWait(() -> {
            page[0] = new HomePage(null, HomeModels.NO_ACTIONS, new DisplayModeModel(prefs::get, prefs::put), prefs::get, prefs::put, () -> now);
            page[0].apply(HomeModels.populated(now));
            evidence.show(page[0], "Home layout", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            Rectangle hero = bounds(page[0], ORDER[0]), current = bounds(page[0], ORDER[1]), today = bounds(page[0], ORDER[2]);
            Rectangle runs = bounds(page[0], ORDER[3]), quests = bounds(page[0], ORDER[4]);
            assertTrue("Hero sits above Now and Today", hero.y + hero.height <= current.y);
            assertEquals("Now and Today share a row", current.y, today.y);
            assertTrue("Now is left of Today", current.x + current.width < today.x);
            assertEquals("The hero spans both columns", hero.width, today.x + today.width - current.x);
            assertEquals("Recent runs and Quests share a row", runs.y, quests.y);
            assertTrue(Math.max(current.y + current.height, today.y + today.height) <= runs.y);
            assertEquals("Recent runs : Quests = 1.5 : 1", 1.5, runs.width / (double) quests.width, 0.02);
            // The compact hero: identity and chips, gear and both estimates on one row; 2 × 4 bars; needs and account on one line.
            Rectangle maxed = shown(page[0], "home-hero-maxed"), slot = shown(page[0], "home-hero-slot-0"), dps = shown(page[0], "home-hero-weapon-dps");
            assertTrue("Chips, gear and estimates share one row: " + maxed + " " + slot + " " + dps, overlap(maxed, slot) && overlap(slot, dps));
            assertTrue(maxed.x < slot.x && slot.x < dps.x);
            assertEquals("Four bars per row", shown(page[0], "home-hero-bar-0").y, shown(page[0], "home-hero-bar-3").y);
            assertTrue("Two rows of bars", shown(page[0], "home-hero-bar-4").y > shown(page[0], "home-hero-bar-0").y);
            Rectangle needs = shown(page[0], "home-hero-needs"), account = shown(page[0], "home-hero-account");
            assertTrue("Needs (left) and account (right) share a line: " + needs + " " + account, overlap(needs, account) && needs.x < account.x);
            System.out.println("Home hero at 1240x800, font 13: " + hero.height + " px tall");
        });
        for (int[] size : new int[][] {{990, 760}, {680, 520}}) for (int font : new int[] {13, 18}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(page[0], "Home layout", size[0], size[1], font));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                Rectangle previous = null;
                for (String name : ORDER) {
                    Rectangle card = bounds(page[0], name);
                    if (previous != null) {
                        assertEquals(name + " stacks in one column at " + size[0], previous.x, card.x);
                        assertTrue(name + " stacks below the previous card at " + size[0], card.y >= previous.y + previous.height);
                    }
                    previous = card;
                }
                Rectangle maxed = shown(page[0], "home-hero-maxed"), slot = shown(page[0], "home-hero-slot-0");
                assertTrue("Below 1000 px the gear wraps under the identity: " + maxed + " " + slot, slot.y >= maxed.y + maxed.height);
                JViewport viewport = VisualEvidence.find(page[0], JScrollPane.class, scroll -> true).getViewport();
                assertEquals("The page never scrolls sideways", viewport.getWidth(), viewport.getView().getWidth());
                for (String name : new String[] {"home-build", "home-today-window-0", "home-today-window-1", "home-hero-mp", "home-hero-account",
                        "home-run-0-loot-7", "home-quest-0-reward-3"}) {
                    JComponent control = VisualEvidence.named(page[0], name, JComponent.class);
                    Rectangle placed = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), viewport.getView());
                    assertTrue(name + " fits at " + size[0] + " px, font " + font + ": " + placed, placed.x >= 0 && placed.x + placed.width <= viewport.getWidth());
                }
            });
        }
    }

    /**
     * S1 in the real window chrome: a WorkspaceShell (sidebar, page header, status line) in a 1240×800 frame with a menu bar
     * like TomatoMenuBar's (File, Edit, Info), Home selected, Simple mode. At body font 13 the maxed chip, the Fame tile and
     * the first loot sprite of the last run lie fully inside the visible viewport; font 18 is measured and reported.
     */
    @Test public void s1MaxedFameAndLastRunLootShowWithoutScrollingInTheRealWindow() throws Exception {
        long now = System.currentTimeMillis();
        DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
        JFrame[] frame = new JFrame[1];
        HomePage[] page = new HomePage[1];
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(DisplayModeModel.Mode.SIMPLE, mode.mode());
            Map<String, JComponent> pages = TestPages.placeholders();
            page[0] = new HomePage(null, HomeModels.NO_ACTIONS, mode, prefs::get, prefs::put, () -> now);
            page[0].apply(HomeModels.populated(now));
            pages.put(WorkspaceShell.pageOf(tomato.gui.route.Destination.HOME), page[0]);
            WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, false, null, null, null, new NavLayout(prefs::get, prefs::put), mode);
            frame[0] = new JFrame("Home in the shell - synthetic validation");
            JMenuBar menu = new JMenuBar();
            for (String name : new String[] {"File", "Edit", "Info"}) menu.add(new JMenu(name)); // TomatoMenuBar's top-level menus
            frame[0].setJMenuBar(menu);
            frame[0].setContentPane(shell);
            frame[0].setSize(1240, 800);
            frame[0].setVisible(true);
            shell.select(WorkspaceShell.pageOf(tomato.gui.route.Destination.HOME));
        });
        try {
            for (int font : new int[] {13, 18}) {
                SwingUtilities.invokeAndWait(() -> {
                    ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font));
                    ContentStyle.applyFontDefaults();
                    ContentStyle.refreshFonts(frame[0].getRootPane());
                });
                for (int pass = 0; pass < 4; pass++) SwingUtilities.invokeAndWait(() -> UiTestLayout.settle(frame[0]));
                SwingUtilities.invokeAndWait(() -> {
                    JViewport viewport = VisualEvidence.find(page[0], JScrollPane.class, scroll -> true).getViewport();
                    Rectangle visible = new Rectangle(0, 0, viewport.getWidth(), viewport.getHeight());
                    StringBuilder report = new StringBuilder("S1 real shell 1240x800, font " + font + ": frame " + frame[0].getSize().width + "x"
                        + frame[0].getSize().height + ", menu bar " + frame[0].getJMenuBar().getHeight() + " px, viewport " + visible.width + "x"
                        + visible.height + ", hero " + bounds(page[0], "home-hero").height + " px tall");
                    boolean all = true;
                    for (String name : S1) {
                        JComponent part = VisualEvidence.named(page[0], name, JComponent.class);
                        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), viewport);
                        boolean inside = part.isShowing() && visible.contains(placed);
                        all &= inside;
                        report.append("; ").append(name).append(" y=").append(placed.y).append("..").append(placed.y + placed.height)
                            .append(inside ? " visible" : " below the fold");
                    }
                    System.out.println(report);
                    evidence.capture(frame[0], "p2-home-shell-1240-" + font);
                    if (font == 13) assertTrue("S1: visible without scrolling at font 13 in the real window. " + report, all);
                });
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    private static Rectangle bounds(HomePage page, String name) { return VisualEvidence.named(page, name, JComponent.class).getBounds(); }
    /** A component's bounds in page coordinates. */
    private static Rectangle shown(HomePage page, String name) {
        JComponent part = VisualEvidence.named(page, name, JComponent.class);
        return SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), page);
    }
    private static boolean overlap(Rectangle a, Rectangle b) { return a.y < b.y + b.height && b.y < a.y + a.height; }
}
