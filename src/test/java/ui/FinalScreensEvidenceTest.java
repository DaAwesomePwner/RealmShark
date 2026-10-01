package ui;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.dnd.DragSource;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.TomatoData;
import tomato.bridge.BridgeService;
import tomato.gui.TomatoGUI;
import tomato.gui.bridge.BridgeReviewGUI;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.glance.home.HomeModel;
import tomato.gui.glance.home.HomePage;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ScopeChip;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.kit.TileList;
import tomato.gui.loot.LootFocus;
import tomato.gui.loot.LootTab;
import tomato.gui.modern.NavLayout;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.Themes;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.runs.DungeonsView;
import tomato.gui.runs.RunsFocus;
import tomato.gui.runs.RunsTab;
import tomato.gui.settings.SettingsPage;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P6b Task 18: the final Simple/Analyst screenshot set of every page and tab (spec §3.2, §4, §6, S6, S7; R4 §2), in the real
 * workspace ({@link EvidenceWorkspace}: {@code TomatoGUI.createWorkspace} in preview mode over {@link FinalScreensFixture}'s
 * synthetic history and app run). Opt-in: skipped unless {@code REALMSHARK_FINAL_SCREENS=1}, so the normal suite's time is
 * unchanged. Run it alone (focus-sensitive).
 * <p>The set, in {@code screenshots/redesign-final}:
 * - primary, 1240×800 font 13, dark: every page and tab in Simple and Analyst (Analyst-only tabs and views once), live and saved on
 *   the seven archive pages (Runs' Table view, Resources, Party, Loot Explore, Chat, Key-pops, Timeline), plus the Scope menu open,
 *   a filter drawer open, the sidebar with Advanced expanded, the Alt+M menu, a sidebar drag in progress with its drop line and the
 *   compact rail with its menu;
 * - Bridge Review populated over a fake in-process service in a {@code TestPages} shell (labeled as such; the real shell's bridge
 *   is the unconfigured preview singleton);
 * - compact, 680×520 font 18, Analyst: each destination's landing tab;
 * - light, 1240×800 font 13, Simple: each destination's landing tab.
 * Captures paint the frame's root pane ({@link VisualEvidence#captureRoot}), sized so the root pane is exactly W×H: no title band,
 * no padding. Navigation is by routes, page IDs and tab IDs only. Every capture runs the R4 §2.4 checks (the shell exactly W×H;
 * the page and tab in front; the mode, with Analyst-only tabs absent in Simple; nothing scrolls sideways; no view-state warning;
 * no absolute path in any showing text; at 1240×800 font 13 {@link FilterBarAssert#assertOneRow} on every showing filter row and
 * the Scope chip in the bar each archive workspace shows) plus its own. A failing check never stops the run: every result is
 * recorded in {@code INDEX.md} (one row per capture) and the test fails at the end with the list. {@code index.html} is the contact
 * sheet (Simple and Analyst side by side, relative links).
 */
public class FinalScreensEvidenceTest {
    static final String FOLDER = "redesign-final";
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    private static final DisplayModeModel.Mode[] BOTH = {SIMPLE, ANALYST};
    /** Analyst-only tabs by tab group: absent in Simple, present in Analyst wherever their group is in the tree. */
    private static final Map<String, String> ANALYST_TABS = Map.of("characters-tabs", "fame-history", "character-tabs", "evidence", "inspect-tabs", "ability");
    /** An absolute path fragment: a Unix root folder or a drive letter. */
    private static final Pattern ABSOLUTE = Pattern.compile("(?<![\\w.])/(?:home|root|tmp|Users|var|mnt|opt|private)/|\\b[A-Za-z]:\\\\");

    private final VisualEvidence evidence = new VisualEvidence(FOLDER);
    private final EvidenceWorkspace workspace = new EvidenceWorkspace(evidence);
    /** The opt-in gate runs before any other rule, so a skipped run touches no theme, preference or static (reported as skipped). */
    @Rule public final RuleChain rules = RuleChain.outerRule(optIn()).around(evidence).around(workspace);

    private final List<Row> rows = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private WorkspaceShell shell;
    /** What the current capture shows: the real shell, or the fake-service Bridge shell. */
    private JComponent content;
    private VisualEvidence frame;
    private int width = 1240, height = 800, font = 13;
    private DisplayModeModel.Mode mode = SIMPLE;
    private String theme = "dark", section = "primary";
    private long started;

    private static TestRule optIn() {
        return (base, description) -> new Statement() {
            @Override public void evaluate() throws Throwable {
                Assume.assumeTrue("The final screenshot set is opt-in: set REALMSHARK_FINAL_SCREENS=1", "1".equals(System.getenv("REALMSHARK_FINAL_SCREENS")));
                base.evaluate();
            }
        };
    }

    @Test public void everyPageAndTabInSimpleAndAnalyst() throws Exception {
        started = System.nanoTime();
        try {
            build();
            primary();
            bridgePopulated();
            compact();
            lightSet();
        } finally {
            writeIndex();
        }
        System.out.println("Final set: " + rows.size() + " captures, " + failures.size() + " failed checks, "
            + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + " s");
        if (!failures.isEmpty())
            fail(failures.size() + " failed checks over " + rows.size() + " captures (redesign-final/INDEX.md):\n" + String.join("\n", failures));
    }

    // ---- the workspace ----

    private void build() throws Exception {
        long now = System.currentTimeMillis();
        Path history = workspace.folder("history");
        workspace.names(FinalScreensFixture.assets(), FinalScreensFixture::label);
        workspace.pin(CharacterFixtures.installDefinitions());
        workspace.pin(CharacterFixtures.installWeaponGroups());
        workspace.pin(CharacterFixtures.installDungeonMapping(workspace.folder("assets")));
        workspace.pin(PetDefinitions.install(CharacterFixtures.petNames(workspace.folder("pets"))));
        FinalScreensFixture.writeHistory(history, now);
        workspace.plan(CharacterFixtures.ACCOUNT, before -> {
            try { return FinalScreensFixture.plan(before, now); } catch (Exception e) { throw new AssertionError(e); }
        });
        SessionStore store = workspace.openStore(history, "p6b-final");
        FinalScreensFixture.currentSession(store, now);
        CharacterJournal journal = CharacterFixtures.evidenceJournal(workspace.folder("journal").resolve("Characters").resolve("journal.json"), now);
        journal.save();   // as the app's saver does soon after a change, so the roster shows its saved status rather than a pending save
        TomatoData data = FinalScreensFixture.data(journal, now);
        shell = workspace.build(data, journal);
        content = shell;
        frame = evidence;
        FinalScreensFixture.live(data, shell, workspace.folder("imports"), now);
        size(1240, 800, 13);
    }

    /** Shows the current content so that its root pane is exactly {@code w}×{@code h}, at {@code f} pt, and settles. */
    private void size(int w, int h, int f) throws Exception {
        width = w; height = h; font = f;
        edt(() -> {
            frame.show(content, "Final set", w, h, f);
            Window window = SwingUtilities.getWindowAncestor(content);
            Insets insets = window.getInsets();
            if (insets.left + insets.right + insets.top + insets.bottom > 0)
                window.setSize(w + insets.left + insets.right, h + insets.top + insets.bottom);
        });
        pause();
    }

    // ---- primary: 1240×800 font 13, dark ----

    private void primary() throws Exception {
        section = "primary";
        for (DisplayModeModel.Mode m : BOTH) shot("home", null, "populated", m).go(() -> open(Route.to(Destination.HOME))).when(this::homeReady)
            .check("home sections", () -> {
                HomeModel model = VisualEvidence.find(shell, HomePage.class, p -> true).model();
                assertEquals("Hero", HomeModel.State.LIVE, model.hero().state());
                // Now reads Tomato.isCaptureRunning() (a live packet processor), which a preview never has: the live fight and the
                // last pop show on the Live meter and Key-pops, and Now honestly says nothing is live.
                assertEquals("Now in preview (capture never runs)", HomeModel.State.EMPTY, model.now().state());
                assertEquals("Today", HomeModel.State.LIVE, model.today().state());
                assertEquals("Recent runs", HomeModel.State.LIVE, model.runs().state());
                assertEquals("Quests", HomeModel.State.LIVE, model.quests().state());
            }).take();
        navigation();
        characters();
        runs();
        loot();
        quests();
        chat();
        party();
        keyPops();
        timeline();
        logging();
        bridge();
        settings();
    }

    private void navigation() throws Exception {
        shot("home", null, "sidebar-advanced", SIMPLE).go(() -> {
            open(Route.to(Destination.HOME));
            AbstractButton advanced = VisualEvidence.named(shell, "nav-advanced", AbstractButton.class);
            if (!"Expanded".equals(advanced.getAccessibleContext().getAccessibleDescription())) advanced.doClick();
        }).check("sidebar", () -> {
            assertEquals(List.of("home", "characters", "runs", "loot", "quests", "chat", "party", "key-pops", "timeline", "logging", "bridge-review"),
                TestPages.listedRows(shell));
            assertEquals("Advanced (5)", VisualEvidence.named(shell, "nav-advanced", AbstractButton.class).getText());
        }).then(() -> VisualEvidence.named(shell, "nav-advanced", AbstractButton.class).doClick()).take();
        shot("home", null, "navigation-menu", SIMPLE).go(() -> shell.getActionMap().get("open-navigation").actionPerformed(null))
            .when(() -> popup("compact-navigation-popup") != null)
            .check("Alt+M menu", () -> assertEquals(List.of("home", "characters", "runs", "loot", "quests", "chat", "party", "key-pops", "timeline",
                "logging", "bridge-review", "settings"), TestPages.menuPages(popup("compact-navigation-popup"))))
            .then(() -> { MenuSelectionManager.defaultManager().clearSelectedPath(); hidePopups(); }).take();
        String order = PropertiesManager.getProperty(NavLayout.ORDER_KEY);
        shot("home", null, "sidebar-drag", SIMPLE).go(() -> {
            JComponent loot = VisualEvidence.named(shell, "nav-loot", JComponent.class), characters = VisualEvidence.named(shell, "nav-characters", JComponent.class);
            Point start = new Point(loot.getWidth() / 2, loot.getHeight() / 2);
            mouse(loot, MouseEvent.MOUSE_PRESSED, start, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
            mouse(loot, MouseEvent.MOUSE_DRAGGED, new Point(start.x, start.y - DragSource.getDragThreshold() - 1), InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
            mouse(loot, MouseEvent.MOUSE_DRAGGED, SwingUtilities.convertPoint(characters, characters.getWidth() / 2, 3, loot), InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
        }).check("drag in progress", () -> {
            JComponent loot = VisualEvidence.named(shell, "nav-loot", JComponent.class);
            assertTrue("Esc can cancel: a drag is in progress", loot.getActionMap().get("nav-drag-cancel").isEnabled());
            assertEquals("The row shows the move cursor", Cursor.MOVE_CURSOR, loot.getCursor().getType());
        }).then(() -> {
            JComponent loot = VisualEvidence.named(shell, "nav-loot", JComponent.class);
            loot.getActionMap().get("nav-drag-cancel").actionPerformed(new ActionEvent(loot, ActionEvent.ACTION_PERFORMED, "Escape"));
            assertEquals("Esc wrote no order", order, PropertiesManager.getProperty(NavLayout.ORDER_KEY));
        }).take();
        size(680, 520, 13);
        shot("home", null, "compact-rail-menu", SIMPLE).go(() -> shell.getActionMap().get("open-navigation").actionPerformed(null))
            .when(() -> popup("compact-navigation-popup") != null)
            .check("compact", () -> assertTrue("Compact below 1000 px", shell.isCompact()))
            .then(() -> { MenuSelectionManager.defaultManager().clearSelectedPath(); hidePopups(); }).take();
        size(1240, 800, 13);
    }

    private void characters() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("characters", "roster", "gallery", m).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("roster"); })
            .when(() -> shown("character-cards") != null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("characters", "roster", "table", m).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("roster"); VisualEvidence.named(shell, "character-view-1", AbstractButton.class).doClick(); })
            .when(() -> shown("character-roster") != null)
            .then(() -> VisualEvidence.named(shell, "character-view-0", AbstractButton.class).doClick()).take();
        String[][] sheet = {{"overview", "character-overview-pet"}, {"gear", null}, {"exalts", null}, {"pet", "character-pet-content"},
            {"fame", "character-fame-chart"}, {"build", null}, {"goals", "character-goals-cards"}, {"notes", null}};
        for (String[] tab : sheet) for (DisplayModeModel.Mode m : BOTH) sheetShot(FinalScreensFixture.WIZARD, tab[0], tab[0], tab[1], m);
        sheetShot(FinalScreensFixture.WIZARD, "evidence", "evidence", null, ANALYST);
        for (DisplayModeModel.Mode m : BOTH) sheetShot(FinalScreensFixture.DEAD, "death", "death-dead-character", null, m);
        for (DisplayModeModel.Mode m : BOTH) shot("characters", "exalts", "grid", m).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("exalts"); })
            .when(() -> shown("character-exalt-tiles") != null && ((JList<?>) shown("character-exalt-tiles")).getModel().getSize() > 0).take();
        for (DisplayModeModel.Mode m : BOTH) shot("characters", "exalts", "class-detail", m).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("exalts"); })
            .when(() -> shown("character-exalt-tiles") != null && ((JList<?>) shown("character-exalt-tiles")).getModel().getSize() > 0)
            .go(() -> {
                TileList<?> tiles = (TileList<?>) shown("character-exalt-tiles");
                tiles.selectKey("784", true);
                tiles.getActionMap().get(TileList.OPEN).actionPerformed(new ActionEvent(tiles, ActionEvent.ACTION_PERFORMED, TileList.OPEN));
            }).when(() -> shown("character-exalts-back") != null)
            .then(() -> ((AbstractButton) shown("character-exalts-back")).doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("characters", "pets", "pet-yard", m).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("pets"); })
            .when(() -> shown("pet-cards") != null && ((JList<?>) shown("pet-cards")).getModel().getSize() > 0).take();
        shot("characters", "fame-history", "saved", ANALYST).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("fame-history"); })
            .when(() -> savedWorkspaceReady("character-fame-session-view")).take();
    }

    private void sheetShot(String key, String tab, String state, String ready, DisplayModeModel.Mode m) throws Exception {
        shot("characters", tab, "sheet-" + state, m).tabs("character-tabs")
            .go(() -> open(Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, tab))))
            .when(() -> shown("character-tabs") != null && (ready == null || shown(ready) != null)).take();
    }

    private void runs() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "feed", "cards", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RUNS)))
            .when(() -> shown("run-feed-summary") != null && text("run-feed-summary").startsWith("Saved runs")).take();
        shot("runs", "feed", "cards-filters-drawer-open", SIMPLE).tabs("runs-tabs")
            .go(() -> { open(Route.to(Destination.RUNS)); VisualEvidence.named(shell, "run-feed-filters", AbstractButton.class).doClick(); })
            .check("drawer open", () -> assertTrue(VisualEvidence.named(shell, "run-feed-filter-bar", FilterBar.class).drawerOpen()))
            .then(() -> VisualEvidence.named(shell, "run-feed-filters", AbstractButton.class).doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "feed", "table-live", m).tabs("runs-tabs")
            .go(() -> { open(Route.to(Destination.RUNS)); menuItem("run-feed-view-item").doClick(); })
            .when(() -> showingWorkspace() != null).check("live", () -> assertFalse(showingWorkspace().state().archive)).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "feed", "table-saved", m).tabs("runs-tabs")
            .go(() -> scope("all")).when(this::savedReady).go(() -> selectRun("Lost Halls")).check("picked run selected", this::runStaysSelected)
            .check("saved", () -> assertTrue(showingWorkspace().state().archive))
            .then(m == ANALYST ? () -> { scope("live"); menuItem("run-feed-cards-item").doClick(); } : null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "feed", "recap", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RUN_RECAP).withVisit(FinalScreensFixture.A1)))
            .when(() -> shown("run-recap-meter") != null && ((JTable) shown("run-recap-meter")).getRowCount() > 0)
            .then(() -> VisualEvidence.named(shell, "run-recap-back", AbstractButton.class).doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "feed", "recap-loot-players-resources-timeline", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RUN_RECAP).withVisit(FinalScreensFixture.A1)))
            .when(() -> shown("run-recap-meter") != null)
            .go(() -> { for (String id : new String[] {"players", "resources", "timeline"}) expand("run-recap-" + id); })
            .go(() -> scrollToTop("run-recap-scroll", "run-recap-loot"))
            .then(() -> VisualEvidence.named(shell, "run-recap-back", AbstractButton.class).doClick()).take();
        shot("runs", "feed", "recap-evidence", ANALYST).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RUN_RECAP).withVisit(FinalScreensFixture.A1)))
            .when(() -> shown("run-recap-evidence") != null)
            .go(() -> expand("run-recap-evidence")).go(() -> scrollToTop("run-recap-scroll", "run-recap-evidence"))
            .then(() -> VisualEvidence.named(shell, "run-recap-back", AbstractButton.class).doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "dungeons", "cards", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.DUNGEONS))))
            .when(() -> shown("dungeons-cards") != null && ((JList<?>) shown("dungeons-cards")).getModel().getSize() >= 4).take();
        shot("runs", "dungeons", "analysis", ANALYST).tabs("runs-tabs")
            .go(() -> { open(Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.DUNGEONS))); VisualEvidence.named(shell, "dungeons-view", DungeonsView.class).analyze("Lost Halls"); })
            .when(() -> savedWorkspaceReady("dungeon-analysis-session-view"))
            .then(() -> VisualEvidence.named(shell, "dungeons-view-mode-0", AbstractButton.class).doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "live-meter", "meters-live-fight", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.ENCOUNTER)))
            .when(() -> shown("dps-player-table") != null && ((JTable) shown("dps-player-table")).getRowCount() == 6)
            .check("Live meter tab", () -> assertEquals("live-meter", tabs("runs-tabs").selectedId())).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "resources", "resources-live", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RESOURCES)))
            .when(() -> showingWorkspace() != null)
            .check("Resources tab", () -> assertEquals("resources", tabs("runs-tabs").selectedId())).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "resources", "resources-saved", m).tabs("runs-tabs")
            .go(() -> scope("all")).when(this::savedReady).go(() -> selectRun("Lost Halls")).check("picked run selected", this::runStaysSelected)
            .check("Resources tab", () -> assertEquals("resources", tabs("runs-tabs").selectedId()))
            .then(m == ANALYST ? () -> { scope("live"); tabs("runs-tabs").select("live-meter"); } : null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("runs", "recordings", "library", m).tabs("runs-tabs")
            .go(() -> open(Route.to(Destination.RUNS).withPayload(RunsFocus.of(RunsTab.RECORDINGS))))
            .when(() -> shown("saved-encounters") != null && ((JTable) shown("saved-encounters")).getRowCount() >= 8).take();
    }

    private void loot() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("loot", "highlights", "today", m).tabs("loot-tabs")
            .go(() -> open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS))))
            .when(() -> shown("loot-highlights") != null && "content".equals(call(shown("loot-highlights"), "state"))
                && !(Boolean) call(shown("loot-highlights"), "loading")).take();
        for (DisplayModeModel.Mode m : BOTH) shot("loot", "explore", "live", m).tabs("loot-tabs")
            .go(() -> open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE))))
            .when(() -> shown("loot-view-0") != null && ((JTable) shown("loot-view-0")).getRowCount() > 0).take();
        for (DisplayModeModel.Mode m : BOTH) shot("loot", "explore", "saved-all-sessions", m).tabs("loot-tabs")
            .go(() -> { open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE))); scope("all"); })
            .when(this::savedReady).take();
        shot("loot", "explore", "saved-only-view", ANALYST).tabs("loot-tabs")
            .go(() -> { open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE))); scope("live"); })
            .go(() -> VisualEvidence.named(shell, "loot-views", JComboBox.class).setSelectedItem(LootQuery.View.RATES))
            .when(this::savedReady)
            .check("caption", () -> assertEquals("Saved history only", VisualEvidence.named(shell, "loot-archive-view-caption", JLabel.class).getText()))
            .then(() -> { VisualEvidence.named(shell, "loot-views", JComboBox.class).setSelectedItem(LootQuery.View.ITEMS); scope("live"); }).take();
    }

    private void quests() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("quests", "captured", "board", m).tabs("quests-tabs")
            .go(() -> { open(Route.to(Destination.QUESTS)); tabs("quests-tabs").select("captured"); })
            .when(() -> shown("quest-cards-epic") != null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("quests", "captured", "detail-drawer", m).tabs("quests-tabs")
            .go(() -> { open(Route.to(Destination.QUESTS)); tabs("quests-tabs").select("captured"); })
            .when(() -> shown("quest-cards-epic") != null)
            .go(() -> openCard("quest-cards-epic", "Royal tribute"))
            .when(() -> shown("quest-detail") != null)
            .then(() -> VisualEvidence.named(shell, "quest-detail-close", AbstractButton.class).doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("quests", "captured", "table", m).tabs("quests-tabs")
            .go(() -> { open(Route.to(Destination.QUESTS)); tabs("quests-tabs").select("captured"); menuItem("quest-view-item").doClick(); })
            .when(() -> shownTable() != null)
            .then(() -> menuItem("quest-view-item").doClick()).take();
        for (DisplayModeModel.Mode m : BOTH) shot("quests", "plans", "planner", m).tabs("quests-tabs")
            .go(() -> { open(Route.to(Destination.QUESTS)); tabs("quests-tabs").select("plans"); })
            // The Planner edits the account the user picks explicitly (never inferred): pick the account in game.
            .go(() -> VisualEvidence.named(shell, "quest-plan-account", JComboBox.class).setSelectedItem(CharacterFixtures.ACCOUNT))
            .when(() -> shown("quest-plan-cards") != null && ((JList<?>) shown("quest-plan-cards")).getModel().getSize() == 4)
            .then(() -> tabs("quests-tabs").select("captured")).take();
    }

    private void chat() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("chat", null, "live", m).go(() -> shell.select("chat")).when(() -> tableWithRows() != null).take();
        shot("chat", null, "live-scope-menu-open", SIMPLE).go(() -> shell.select("chat"))
            .go(() -> chip().doClick()).when(() -> chip().menu().isShowing())
            .check("Scope menu", () -> {
                ScopeChip chip = chip();
                assertEquals("Scope", chip.getAccessibleContext().getAccessibleName());
                assertTrue("Live is chosen", ((JRadioButtonMenuItem) chip.item("live")).isSelected());
                assertNotNull("History library…", chip.item("library"));
                assertNotNull("Refresh session list", chip.item("refresh"));
            }).then(() -> chip().menu().setVisible(false)).take();
        for (DisplayModeModel.Mode m : BOTH) shot("chat", null, "saved-all-sessions", m).go(() -> { shell.select("chat"); scope("all"); })
            .when(this::savedReady).then(m == ANALYST ? () -> scope("live") : null).take();
    }

    private void party() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("party", "area", "live-roster", m).tabs("inspect-tabs")
            .go(() -> { shell.select("party"); tabs("inspect-tabs").select("area"); }).when(() -> tableWithRows() != null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("party", "runs", "live", m).tabs("inspect-tabs")
            .go(() -> { shell.select("party"); tabs("inspect-tabs").select("runs"); }).take();
        shot("party", "ability", "live", ANALYST).tabs("inspect-tabs")
            .go(() -> { shell.select("party"); tabs("inspect-tabs").select("ability"); }).when(() -> tableWithRows() != null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("party", "runs", "saved-all-sessions", m).tabs("inspect-tabs")
            .go(() -> { shell.select("party"); tabs("inspect-tabs").select("runs"); scope("all"); }).when(this::savedReady)
            .go(() -> selectRun("Lost Halls")).check("picked run selected", this::runStaysSelected)
            .then(m == ANALYST ? () -> { scope("live"); tabs("inspect-tabs").select("area"); } : null).take();
    }

    private void keyPops() throws Exception {
        for (String tab : new String[] {"events", "by-player", "by-item"})
            for (DisplayModeModel.Mode m : BOTH) shot("key-pops", tab, "live", m).tabs("keypops-live-tabs")
                .go(() -> { shell.select("key-pops"); tabs("keypops-live-tabs").select(tab); }).when(() -> tableWithRows() != null).take();
        for (DisplayModeModel.Mode m : BOTH) shot("key-pops", null, "saved-all-sessions", m)
            .go(() -> { shell.select("key-pops"); tabs("keypops-live-tabs").select("events"); scope("all"); }).when(this::savedReady)
            .then(m == ANALYST ? () -> scope("live") : null).take();
    }

    private void timeline() throws Exception {
        for (DisplayModeModel.Mode m : BOTH) shot("timeline", null, "live", m).go(() -> shell.select("timeline")).take();
        for (DisplayModeModel.Mode m : BOTH) shot("timeline", null, "saved-all-sessions", m).go(() -> { shell.select("timeline"); scope("all"); })
            .when(this::savedReady).then(m == ANALYST ? () -> scope("live") : null).take();
    }

    private void logging() throws Exception {
        for (String tab : new String[] {"discovery", "reentry", "packets", "stats", "events", "fields"})
            for (DisplayModeModel.Mode m : BOTH) shot("logging", tab, "collected", m).tabs("logging-tabs")
                .go(() -> { open(Route.to(Destination.LOGGING)); tabs("logging-tabs").select(tab); }).take();
        edt(() -> tabs("logging-tabs").select("discovery"));
    }

    private void bridge() throws Exception {
        for (String tab : new String[] {"review", "settings", "logs", "saved-review"})
            for (DisplayModeModel.Mode m : BOTH) shot("bridge-review", tab, "unconfigured-preview", m).tabs("bridge-tabs")
                .go(() -> { shell.select("bridge-review"); tabs("bridge-tabs").select(tab); }).take();
        edt(() -> tabs("bridge-tabs").select("review"));
    }

    private void settings() throws Exception {
        for (String id : new String[] {SettingsPage.NOTIFICATIONS, SettingsPage.GENERAL, SettingsPage.APPEARANCE, SettingsPage.LOOT_FILTERS,
                SettingsPage.CHAT, SettingsPage.ABOUT})
            for (DisplayModeModel.Mode m : BOTH) shot("settings", id, "section", m).go(() -> TomatoGUI.openSettings(id))
                .check("section", () -> assertEquals(id, VisualEvidence.named(shell, "settings-page", SettingsPage.class).currentSection())).take();
    }

    // ---- Bridge Review over a fake service (TestPages shell), compact and light ----

    /** One capture of Bridge Review › Review populated over a fake in-process service, in a TestPages shell of its own window. */
    private void bridgePopulated() throws Exception {
        section = "bridge-fake-service";
        BridgeService service = null;
        VisualEvidence own = new VisualEvidence(FOLDER);
        try {
            service = FinalScreensFixture.bridge(workspace.folder("bridge"));
            BridgeService bridge = service;
            JComponent[] made = new JComponent[1];
            edt(() -> {
                DisplayModeModel.application().set(SIMPLE);
                made[0] = TestPages.shell("bridge-review", new BridgeReviewGUI(bridge, DisplayModeModel.application()));
            });
            content = made[0]; frame = own;
            size(1240, 800, 13);
            WorkspaceShell fake = (WorkspaceShell) made[0];
            shot("bridge-review", "review", "populated-fake-service-testpages-shell", SIMPLE).in(fake).tabs("bridge-tabs")
                .go(() -> tabs(fake, "bridge-tabs").select("review"))
                .check("four detected drops", () -> assertEquals(4, VisualEvidence.named(fake, "bridge-review-table", JTable.class).getRowCount()))
                .take();
        } finally {
            edt(own::closeWindow);
            if (service != null) service.close();
            content = shell; frame = evidence;
        }
    }

    /** Each destination's landing tab, 680×520 font 18, Analyst. */
    private void compact() throws Exception {
        section = "compact";
        size(680, 520, 18);
        landings(ANALYST);
    }

    /** Each destination's landing tab, 1240×800 font 13, Simple, in the light theme (switched live, as Settings › Appearance does). */
    private void lightSet() throws Exception {
        section = "light";
        theme = "light";
        edt(() -> {
            DisplayModeModel.application().set(SIMPLE);
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(SwingUtilities.getWindowAncestor(shell));
        });
        size(1240, 800, 13);
        landings(SIMPLE);
    }

    private void landings(DisplayModeModel.Mode m) throws Exception {
        shot("home", null, "populated", m).go(() -> open(Route.to(Destination.HOME))).when(this::homeReady).take();
        shot("characters", "roster", "gallery", m).tabs("characters-tabs")
            .go(() -> { open(Route.to(Destination.CHARACTERS)); tabs("characters-tabs").select("roster"); })
            .when(() -> shown("character-cards") != null).take();
        shot("runs", "feed", "cards", m).tabs("runs-tabs").go(() -> open(Route.to(Destination.RUNS)))
            .when(() -> shown("run-feed-summary") != null).take();
        shot("loot", "highlights", "today", m).tabs("loot-tabs").go(() -> open(Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.HIGHLIGHTS))))
            .when(() -> shown("loot-highlights") != null && "content".equals(call(shown("loot-highlights"), "state"))).take();
        shot("quests", "captured", "board", m).tabs("quests-tabs").go(() -> { open(Route.to(Destination.QUESTS)); tabs("quests-tabs").select("captured"); })
            .when(() -> shown("quest-cards-epic") != null).take();
        shot("chat", null, "live", m).go(() -> shell.select("chat")).when(() -> tableWithRows() != null).take();
        shot("party", "area", "live-roster", m).tabs("inspect-tabs").go(() -> { shell.select("party"); tabs("inspect-tabs").select("area"); }).take();
        shot("key-pops", "events", "live", m).tabs("keypops-live-tabs").go(() -> { shell.select("key-pops"); tabs("keypops-live-tabs").select("events"); }).take();
        shot("timeline", null, "live", m).go(() -> shell.select("timeline")).take();
        shot("logging", "discovery", "collected", m).tabs("logging-tabs").go(() -> { open(Route.to(Destination.LOGGING)); tabs("logging-tabs").select("discovery"); }).take();
        shot("bridge-review", "review", "unconfigured-preview", m).tabs("bridge-tabs").go(() -> { shell.select("bridge-review"); tabs("bridge-tabs").select("review"); }).take();
        shot("settings", SettingsPage.NOTIFICATIONS, "section", m).go(() -> TomatoGUI.openSettings(SettingsPage.NOTIFICATIONS)).take();
    }

    // ---- one capture ----

    private Shot shot(String page, String tab, String state, DisplayModeModel.Mode m) { return new Shot(page, tab, state, m); }

    /** One capture: navigation steps (each run on the EDT, then settled, then waited on), the capture, the checks and a restore. */
    private final class Shot {
        final String page, tab, state;
        final DisplayModeModel.Mode mode;
        final List<Runnable> steps = new ArrayList<>();
        final List<BooleanSupplier> waits = new ArrayList<>();
        final Map<String, Runnable> checks = new LinkedHashMap<>();
        String tabs;
        Runnable restore;
        WorkspaceShell in = shell;

        Shot(String page, String tab, String state, DisplayModeModel.Mode mode) { this.page = page; this.tab = tab; this.state = state; this.mode = mode; }
        Shot go(Runnable step) { steps.add(step); waits.add(null); return this; }
        Shot when(BooleanSupplier ready) { waits.set(waits.size() - 1, ready); return this; }
        Shot tabs(String pane) { tabs = pane; return this; }
        Shot check(String name, Runnable check) { checks.put(name, check); return this; }
        Shot then(Runnable after) { restore = after; return this; }
        Shot in(WorkspaceShell other) { in = other; return this; }
        void take() throws Exception { capture(this); }
    }

    private void capture(Shot shot) throws Exception {
        FinalScreensEvidenceTest.this.mode = shot.mode;
        String name = "final-" + shot.page + (shot.tab == null ? "" : "-" + shot.tab) + (shot.state == null ? "" : "-" + shot.state) + "-" + width + "-" + font
            + "-" + shot.mode.name().toLowerCase(Locale.ROOT) + ("light".equals(theme) ? "-light" : "");
        List<String> results = new ArrayList<>();
        boolean navigated = true;
        try {
            edt(() -> {
                DisplayModeModel.application().set(shot.mode);
                // Programmatic navigation moves no focus: drop the previous capture's focus ring (a user's click would move it).
                KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            });
        } catch (Throwable failure) { results.add(failed("mode", failure)); }
        for (int i = 0; i < shot.steps.size() && navigated; i++) {
            Runnable step = shot.steps.get(i);
            try { edt(step::run); } catch (Throwable failure) { results.add(failed("navigate", failure)); navigated = false; }
            pause();
            BooleanSupplier ready = shot.waits.get(i);
            if (navigated && ready != null && !await(ready)) results.add("✗ ready: step " + (i + 1) + " did not settle within 20 s");
        }
        pause();
        edt(() -> {
            try { frame.captureRoot(name); } catch (Throwable failure) { results.add(failed("capture", failure)); return; }
            check(results, "size", () -> {
                assertEquals("The shown root pane is exactly " + width + "×" + height, new Dimension(width, height), content.getRootPane().getSize());
                assertEquals("The shell fills it", new Dimension(width, height), content.getSize());
            });
            check(results, "page", () -> assertEquals(shot.page, shot.in.selectedPage()));
            if (shot.tabs != null) check(results, "tab", () -> assertEquals(shot.tab, tabs(shot.in, shot.tabs).selectedId()));
            check(results, "mode", () -> {
                assertEquals(shot.mode, DisplayModeModel.application().mode());
                for (Map.Entry<String, String> analyst : ANALYST_TABS.entrySet()) {
                    CustomizableTabs group = group(shot.in, analyst.getKey());
                    if (group != null) assertEquals(analyst.getValue() + " in " + analyst.getKey() + " is Analyst-only", shot.mode == ANALYST,
                        group.visibleIds().contains(analyst.getValue()));
                }
            });
            check(results, "sideways", () -> VisualEvidence.nothingSideways(content, name));
            check(results, "view-state", () -> {
                List<String> shown = new ArrayList<>();
                for (Banner banner : all(content, Banner.class))
                    if (banner.isShowing() && banner.getName() != null && banner.getName().endsWith("-view-state")) shown.add(banner.getName() + ": " + banner.text());
                assertTrue("View-state warnings: " + shown, shown.isEmpty());
            });
            check(results, "paths", () -> {
                List<String> texts = new ArrayList<>();
                texts(content, texts);
                List<String> found = new ArrayList<>();
                String[] roots = {workspace.root(), System.getProperty("user.dir"), System.getProperty("user.home") + java.io.File.separator,
                    Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString()};
                for (String text : texts) {
                    if (text == null || text.isEmpty()) continue;
                    boolean path = ABSOLUTE.matcher(text).find();
                    for (String root : roots) path |= root != null && root.length() > 1 && text.contains(root);
                    if (path) found.add(text.length() > 160 ? text.substring(0, 160) + "…" : text);
                }
                assertTrue("Absolute paths in showing text: " + found, found.isEmpty());
            });
            if (width == 1240 && font == 13) check(results, "one-row", () -> {
                List<String> wrapped = new ArrayList<>(), measured = new ArrayList<>();
                for (FilterBar bar : all(content, FilterBar.class)) {
                    if (!bar.isShowing()) continue;
                    measured.add(bar.getName());
                    try { FilterBarAssert.assertOneRow(bar); } catch (Throwable failure) { wrapped.add(failure.getMessage()); }
                }
                assertTrue(String.join("; ", wrapped), wrapped.isEmpty());
                if (!measured.isEmpty()) System.out.println(name + ": one-row checked " + measured);
            });
            check(results, "scope", () -> {
                for (ArchiveWorkspace<?, ?, ?> archive : all(content, ArchiveWorkspace.class))
                    if (archive.isShowing() && !archive.savedOnly()) FilterBarAssert.assertChipInVisibleBar(archive);
            });
            for (Map.Entry<String, Runnable> extra : shot.checks.entrySet()) check(results, extra.getKey(), extra.getValue());
        });
        if (shot.restore != null) try { edt(shot.restore::run); pause(); } catch (Throwable failure) { results.add(failed("restore", failure)); }
        rows.add(new Row(section, name, shot.page, shot.tab, shot.state, width, height, font, shot.mode, theme, results));
        for (String result : results) if (result.startsWith("✗")) failures.add(name + ": " + result);
    }

    private static void check(List<String> results, String name, Runnable check) {
        try { check.run(); results.add("✓ " + name); } catch (Throwable failure) { results.add(failed(name, failure)); }
    }

    private static String failed(String name, Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return "✗ " + name + ": " + message.replace('\n', ' ');
    }

    private record Row(String section, String file, String page, String tab, String state, int width, int height, int font,
                       DisplayModeModel.Mode mode, String theme, List<String> checks) {
        boolean passed() { return checks.stream().noneMatch(check -> check.startsWith("✗")); }
        String where() { return page + (tab == null ? "" : " › " + tab); }
        String modeLabel() { return mode == SIMPLE ? "Simple" : "Analyst"; }
    }

    // ---- navigation helpers (EDT) ----

    private static void open(Route route) { assertTrue("A target accepts " + route.destination, Navigator.current().open(route)); }

    private CustomizableTabs tabs(String pane) { return tabs(shell, pane); }

    private static CustomizableTabs tabs(Container root, String pane) {
        CustomizableTabs group = group(root, pane);
        assertNotNull("Tab group " + pane, group);
        return group;
    }

    /** The CustomizableTabs behind the tab pane named {@code pane} (a showing one first), or null. */
    private static CustomizableTabs group(Container root, String pane) {
        JTabbedPane found = null;
        for (JTabbedPane tabs : all(root, JTabbedPane.class))
            if (pane.equals(tabs.getName()) && (found == null || tabs.isShowing() && !found.isShowing())) found = tabs;
        Object group = found == null ? null : found.getClientProperty(CustomizableTabs.class);
        return group instanceof CustomizableTabs ? (CustomizableTabs) group : null;
    }

    /** The first showing ArchiveWorkspace that is not saved-only (the page's live/saved workspace), or null. */
    private ArchiveWorkspace<?, ?, ?> showingWorkspace() {
        for (ArchiveWorkspace<?, ?, ?> archive : all(shell, ArchiveWorkspace.class)) if (archive.isShowing() && !archive.savedOnly()) return archive;
        return null;
    }

    private ScopeChip chip() {
        ArchiveWorkspace<?, ?, ?> archive = showingWorkspace();
        assertNotNull("A live/saved workspace shows", archive);
        return ArchiveNativeSupport.scope(archive);
    }

    /** Picks a Scope menu item of the showing workspace (as a click on it does: through the chip's actions and request()). */
    private void scope(String suffix) {
        ArchiveWorkspace<?, ?, ?> archive = showingWorkspace();
        assertNotNull("A live/saved workspace shows", archive);
        ArchiveNativeSupport.scopeItem(archive, suffix).doClick();
    }

    /** Selects the first row of the showing workspace's table whose first column reads {@code area} (the newest such run), as a click does. */
    private void selectRun(String area) {
        ArchiveWorkspace<?, ?, ?> archive = showingWorkspace();
        assertNotNull("A live/saved workspace shows", archive);
        for (JTable table : all(archive, JTable.class))
            if (table.isShowing()) for (int row = 0; row < table.getRowCount(); row++)
                if (area.equals(String.valueOf(table.getValueAt(row, 0)))) {
                    table.setRowSelectionInterval(row, row);
                    table.scrollRectToVisible(table.getCellRect(row, 0, true));
                    return;
                }
        throw new AssertionError("No " + area + " row");
    }

    /** The run picked by {@link #selectRun} is still the table's selected row when the capture is taken, and the workspace remembers it. */
    private void runStaysSelected() {
        ArchiveWorkspace<?, ?, ?> archive = showingWorkspace();
        assertNotNull("A live/saved workspace shows", archive);
        JTable table = null;
        for (JTable each : all(archive, JTable.class)) if (each.isShowing() && table == null) table = each;
        assertNotNull("The saved table shows", table);
        int row = table.getSelectedRow();
        assertTrue("The picked run is still selected at capture time: selected row " + row + ", workspace selection " + archive.state().selected
            + ", Export selected… enabled " + menuItemIn(archive, "Export selected…"), row >= 0 && "Lost Halls".equals(String.valueOf(table.getValueAt(row, 0))));
    }

    private static String menuItemIn(Container root, String text) {
        for (tomato.gui.kit.OverflowMenu menu : all(root, tomato.gui.kit.OverflowMenu.class))
            for (Component item : menu.menu().getComponents())
                if (item instanceof JMenuItem && text.equals(((JMenuItem) item).getText())) return String.valueOf(item.isEnabled());
        return "absent";
    }

    private boolean savedReady() {
        ArchiveWorkspace<?, ?, ?> archive = showingWorkspace();
        return archive != null && archive.state().archive && ArchiveNativeSupport.ready(archive);
    }

    private boolean savedWorkspaceReady(String name) {
        for (ArchiveWorkspace<?, ?, ?> archive : all(shell, ArchiveWorkspace.class))
            if (name.equals(archive.getName()) && archive.isShowing()) return ArchiveNativeSupport.ready(archive);
        return false;
    }

    private boolean homeReady() {
        HomePage home = VisualEvidence.find(shell, HomePage.class, p -> true);
        HomeModel model = home.model();
        return model.hero().state() != HomeModel.State.LOADING && model.now().state() != HomeModel.State.LOADING
            && model.today().state() != HomeModel.State.LOADING && model.runs().state() != HomeModel.State.LOADING
            && model.quests().state() != HomeModel.State.LOADING;
    }

    /** A menu item (⋯ items are outside the tree until shown) by name, from any overflow menu in the shell. */
    private JMenuItem menuItem(String name) {
        for (tomato.gui.kit.OverflowMenu menu : all(shell, tomato.gui.kit.OverflowMenu.class))
            for (Component item : menu.menu().getComponents()) if (name.equals(item.getName())) return (JMenuItem) item;
        throw new AssertionError("No menu item " + name);
    }

    private void expand(String section) {
        Collapsible collapsible = VisualEvidence.named(shell, section, Collapsible.class);
        if (!collapsible.expanded()) VisualEvidence.named(shell, "collapsible-" + section, AbstractButton.class).doClick();
    }

    private void scrollToTop(String scroll, String part) {
        JScrollPane pane = VisualEvidence.named(shell, scroll, JScrollPane.class);
        JComponent target = VisualEvidence.named(shell, part, JComponent.class);
        Rectangle placed = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), pane.getViewport().getView());
        pane.getVerticalScrollBar().setValue(placed.y);
    }

    private void openCard(String list, String name) {
        TileList<?> tiles = (TileList<?>) shown(list);
        assertNotNull(list, tiles);
        int index = -1;
        for (int i = 0; i < tiles.getModel().getSize(); i++)
            if (String.valueOf(tiles.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName()).startsWith(name + ",")) index = i;
        assertTrue("A card " + name, index >= 0);
        tiles.setSelectedIndex(index);
        tiles.getActionMap().get(TileList.OPEN).actionPerformed(new ActionEvent(tiles, ActionEvent.ACTION_PERFORMED, TileList.OPEN));
    }

    private JPopupMenu popup(String name) {
        for (MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath())
            if (element instanceof JPopupMenu && name.equals(((JPopupMenu) element).getName()) && ((JPopupMenu) element).isShowing()) return (JPopupMenu) element;
        return null;
    }

    private static void hidePopups() {
        for (MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath())
            if (element instanceof JPopupMenu) ((JPopupMenu) element).setVisible(false);
    }

    private static void mouse(JComponent row, int id, Point at, int modifiers, int button) {
        row.dispatchEvent(new MouseEvent(row, id, System.currentTimeMillis(), modifiers, at.x, at.y, 1, false, button));
    }

    private Component shown(String name) { return shown(shell, name); }

    private static Component shown(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && child.isShowing()) return child;
            if (child instanceof Container) { Component found = shown((Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    private JTable tableWithRows() {
        for (JTable table : all(shell, JTable.class)) if (table.isShowing() && table.getRowCount() > 0) return table;
        return null;
    }

    private JTable shownTable() {
        for (JTable table : all(shell, JTable.class)) if (table.isShowing()) return table;
        return null;
    }

    private String text(String name) {
        Component component = shown(name);
        if (component instanceof JTextComponent) return ((JTextComponent) component).getText();
        if (component instanceof AbstractButton) return ((AbstractButton) component).getText();
        return component instanceof JLabel ? ((JLabel) component).getText() : "";
    }

    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> found) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container) collect((Container) child, type, found);
        }
    }

    /** Every showing text: labels, buttons, text components, tooltips, accessible names and descriptions, table cells and headers. */
    private static void texts(Container root, List<String> texts) {
        for (Component child : root.getComponents()) {
            if (!child.isShowing()) continue;
            if (child instanceof JTextComponent) texts.add(((JTextComponent) child).getText());
            if (child instanceof JLabel) texts.add(((JLabel) child).getText());
            if (child instanceof AbstractButton) texts.add(((AbstractButton) child).getText());
            if (child instanceof JComponent) {
                JComponent component = (JComponent) child;
                texts.add(component.getToolTipText());
                if (component.getAccessibleContext() != null) {
                    texts.add(component.getAccessibleContext().getAccessibleName());
                    texts.add(component.getAccessibleContext().getAccessibleDescription());
                }
            }
            if (child instanceof JComboBox && ((JComboBox<?>) child).getSelectedItem() != null) texts.add(String.valueOf(((JComboBox<?>) child).getSelectedItem()));
            if (child instanceof JList) {
                JList<?> list = (JList<?>) child;
                for (int i = 0; i < list.getModel().getSize(); i++)
                    texts.add(String.valueOf(list.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName()));
            }
            if (child instanceof JTable) {
                JTable table = (JTable) child;
                for (int row = 0; row < table.getRowCount(); row++) for (int column = 0; column < table.getColumnCount(); column++) {
                    texts.add(String.valueOf(table.getValueAt(row, column)));
                    Component cell = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                    if (cell instanceof JLabel) { texts.add(((JLabel) cell).getText()); texts.add(((JLabel) cell).getToolTipText()); }
                }
            }
            if (child instanceof JTableHeader) {
                JTableHeader header = (JTableHeader) child;
                for (int column = 0; column < header.getColumnModel().getColumnCount(); column++)
                    texts.add(String.valueOf(header.getColumnModel().getColumn(column).getHeaderValue()));
            }
            if (child instanceof Container) texts((Container) child, texts);
        }
    }

    /** The owner's own package-private no-argument method (a view's test accessor, such as Loot highlights' {@code state()}). */
    private static Object call(Object owner, String name) {
        try { Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    // ---- threading ----

    private interface Step { void run() throws Exception; }

    private static void edt(Step body) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> { try { body.run(); } catch (Throwable t) { failure[0] = t; } });
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
    }

    /** Polls {@code condition} on the EDT for up to 20 s; false when it never held (recorded, never thrown). */
    private static boolean await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> { try { met[0] = condition.getAsBoolean(); } catch (RuntimeException | AssertionError e) { met[0] = false; } });
            if (met[0]) return true;
            if (System.nanoTime() > end) return false;
            Thread.sleep(50);
        }
    }

    /** Pages apply reads on later EDT turns and motion takes at most Motion.MAX_MILLIS: settle, wait, settle. */
    private void pause() throws Exception { frame.settle(); Thread.sleep(400); frame.settle(); }

    // ---- the index ----

    private void writeIndex() throws Exception {
        Path folder = Paths.get("screenshots", FOLDER);
        Files.createDirectories(folder);
        long failed = rows.stream().filter(row -> !row.passed()).count();
        StringBuilder md = new StringBuilder("# Final Simple/Analyst screenshot set (P6b Task 18)\n\n");
        md.append("Generated by `ui.FinalScreensEvidenceTest` (`REALMSHARK_FINAL_SCREENS=1`) in the real workspace over a synthetic history. ")
            .append(rows.size()).append(" captures, ").append(failed).append(" with failed checks, ")
            .append(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).append(" s. Contact sheet: [index.html](index.html).\n\n")
            .append("Checks: size (root pane and shell exactly W×H), page, tab, mode (Analyst-only tabs absent in Simple), sideways (no page ")
            .append("scrolls sideways), view-state (no warning), paths (no absolute path in any showing text), one-row (`FilterBarAssert.assertOneRow` ")
            .append("on every showing filter row at 1240×800 font 13), scope (the Scope chip in the bar each archive workspace shows), then the capture's own.\n\n");
        if (!failures.isEmpty()) {
            md.append("## Failed checks (").append(failures.size()).append(")\n\n");
            for (String failure : failures) md.append("- ").append(escape(failure)).append('\n');
            md.append('\n');
        }
        md.append("## Captures\n\n| File | Page › tab | State | Size/font | Mode | Theme | Checks |\n|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            List<String> passed = new ArrayList<>(), other = new ArrayList<>();
            for (String check : row.checks()) if (check.startsWith("✓ ")) passed.add(check.substring(2)); else other.add(check);
            String checks = (passed.isEmpty() ? "" : "✓ " + String.join(", ", passed)) + (other.isEmpty() ? "" : (passed.isEmpty() ? "" : "<br>") + String.join("<br>", other));
            md.append("| [").append(row.file()).append("](").append(row.file()).append(".png) | ").append(escape(row.where())).append(" | ")
                .append(escape(String.valueOf(row.state()))).append(" | ").append(row.width()).append('×').append(row.height()).append('/').append(row.font())
                .append(" | ").append(row.modeLabel()).append(" | ").append(row.theme()).append(" | ").append(escape(checks)).append(" |\n");
        }
        Files.write(folder.resolve("INDEX.md"), md.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(folder.resolve("index.html"), html().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String text) { return text.replace("|", "\\|"); }

    /** The contact sheet: primary pairs (Simple beside Analyst) grouped by page, then the fake-service Bridge, compact and light sets. */
    private String html() {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Final screenshot set</title>")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>")
            .append(":root{--bg:#f6f5fb;--fg:#1d1b26;--muted:#5c5870;--line:#d9d6e4;--bad:#b3261e;--ok:#2e7d32}")
            .append("@media (prefers-color-scheme:dark){:root{--bg:#15131c;--fg:#ecebf3;--muted:#a9a5ba;--line:#34303f;--bad:#ff8a80;--ok:#81c784}}")
            .append("body{margin:0 16px 32px;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}h1{font-size:22px}h2{margin-top:28px;border-bottom:1px solid var(--line)}")
            .append("table{border-collapse:collapse;width:100%}td,th{vertical-align:top;padding:6px;border-bottom:1px solid var(--line);text-align:left}")
            .append("img{max-width:100%;height:auto;border:1px solid var(--line)}.cell{width:40%}.bad{color:var(--bad)}.ok{color:var(--ok)}small{color:var(--muted)}")
            .append("</style></head><body><h1>Final Simple/Analyst screenshot set</h1><p><small>").append(rows.size()).append(" captures · ")
            .append(failures.size()).append(" failed checks · details in <a href=\"INDEX.md\">INDEX.md</a></small></p>");
        Map<String, Map<DisplayModeModel.Mode, Row>> pairs = new LinkedHashMap<>();
        for (Row row : rows) if (row.section().equals("primary"))
            pairs.computeIfAbsent(row.where() + " · " + row.state() + " · " + row.width() + "×" + row.height() + "/" + row.font(), key -> new EnumMap<>(DisplayModeModel.Mode.class))
                .put(row.mode(), row);
        String page = null;
        for (Map.Entry<String, Map<DisplayModeModel.Mode, Row>> pair : pairs.entrySet()) {
            Row any = pair.getValue().values().iterator().next();
            if (!any.page().equals(page)) {
                if (page != null) html.append("</table>");
                page = any.page();
                html.append("<h2>").append(page).append("</h2><table><tr><th>Page › tab · state</th><th class=\"cell\">Simple</th><th class=\"cell\">Analyst</th></tr>");
            }
            html.append("<tr><td>").append(pair.getKey()).append("</td>");
            for (DisplayModeModel.Mode m : BOTH) html.append("<td class=\"cell\">").append(cell(pair.getValue().get(m))).append("</td>");
            html.append("</tr>");
        }
        if (page != null) html.append("</table>");
        for (String[] set : new String[][] {{"bridge-fake-service", "Bridge Review over a fake service (TestPages shell)"},
                {"compact", "Compact · 680×520 font 18 · Analyst"}, {"light", "Light theme · 1240×800 font 13 · Simple"}}) {
            html.append("<h2>").append(set[1]).append("</h2><table><tr><th>Page › tab · state</th><th class=\"cell\">Capture</th></tr>");
            for (Row row : rows) if (row.section().equals(set[0]))
                html.append("<tr><td>").append(row.where()).append(" · ").append(row.state()).append("</td><td class=\"cell\">").append(cell(row)).append("</td></tr>");
            html.append("</table>");
        }
        return html.append("</body></html>").toString();
    }

    private static String cell(Row row) {
        if (row == null) return "<small>— (Analyst-only or Simple-only state)</small>";
        StringBuilder cell = new StringBuilder("<a href=\"").append(row.file()).append(".png\"><img loading=\"lazy\" width=\"310\" src=\"")
            .append(row.file()).append(".png\" alt=\"").append(row.file()).append("\"></a><br><small>").append(row.file()).append("</small>");
        for (String check : row.checks()) if (check.startsWith("✗"))
            cell.append("<br><small class=\"bad\">").append(check.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</small>");
        if (row.passed()) cell.append("<br><small class=\"ok\">all checks passed</small>");
        return cell.toString();
    }
}
