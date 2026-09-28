package ui;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import assets.IdToAsset;
import packets.data.QuestData;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.Motion;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.SegmentBar;
import tomato.gui.kit.TileList;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.quest.QuestGUI;
import tomato.gui.quest.QuestPlanning;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.planning.PlanData;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * P4 Quests evidence (spec §6.5, §11) in the real workspace, from synthetic quests with QuestFixtures' item names (installed as the
 * asset names, restored after), the user's type labels and pins in the quest preferences node (restored after) and saved plans of
 * two synthetic accounts in the shared planning store (restored after). Preview mode; no capture, no personal data.
 * 15 screenshots in {@code redesign-p4-quests} (Home's Quests card adds 2 from {@link HomeEvidenceTest}):
 * - Board: cards grouped by chest tier (1240×800 font 13 Simple; 680×520 font 18 Analyst), grouped by type label, the detail
 *   drawer (Simple: no expiration; Analyst at both sizes: the raw server value), the Table view, a stale list, the empty Board.
 * - Planner: plan cards under the All plans summary (both sizes), the compact page as it opens (account, view toggle, status), the
 *   Manual stock drawer open, an offline account without confirmed stock for most items ("Stock unconfirmed"), and the Table view.
 * Every capture asserts that nothing scrolls or is cut off sideways (a data table that scrolls its own columns is listed on standard
 * output instead, as in P3b) and the content it is evidence of, so a screenshot is never a pass merely because it was written.
 */
public class QuestsEvidenceTest {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(Map.entry("chat.filters", "{}"), Map.entry("chat.showIgnoredPlayers", "false"),
        Map.entry("ui.quests.view", ""), Map.entry("ui.quests.group", ""), Map.entry("ui.quests.pinned-first", ""), Map.entry("ui.quests.plan-view", ""),
        Map.entry("ui.collapse.quest-plan-stock", ""), Map.entry("ui.filters.quests.open", ""), Map.entry("ui.tabs.quests", ""));
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    /** The account in game (its list is current, so the Planner is verified for it) and an offline account with saved plans only. */
    private static final String ACCOUNT = CharacterJournal.accountKey("p4-quests-evidence"), OFFLINE = CharacterJournal.accountKey("p4-quests-evidence-offline");
    /** A synthetic item beyond QuestFixtures', so one quest needs five distinct items (the card shows four and "+1"). */
    private static final int SPIRIT_SHARD = 20;
    /** Shown verbatim in the Analyst detail only; nothing parses it (the expiry countdown is deferred). */
    private static final String EXPIRATION = "synthetic-raw-expiration-86399";
    private static final long MINUTE = 60_000L;
    private static final List<String> TIERS = List.of("Mighty quest chests", "Epic quest chests", "Standard quest chests", "Beginner quest chests",
        "Other quest chests", "No quest chest", "Rewards not captured");
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p4-quests");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> saved = new HashMap<>(), archive = new HashMap<>();
    /** The quest preferences node's category labels before the test (null: absent); the synthetic account's pins are removed after. */
    private final Map<String, String> labels = new HashMap<>();
    /** The Characters roster's saved view goes here, not to the shared realmShark.properties (P3a finding 10). */
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    /** The shared planning store's plans of the synthetic accounts before a Planner test saved its own (restored after). */
    private final Map<String, PlanData.AccountPlan> savedPlans = new LinkedHashMap<>();
    private DisplayModeModel.Mode savedMode;
    private String temporaryDirectory;
    private SessionStore store;
    private CharacterJournal journal;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;

    @Before public void open() throws Exception {
        PropertiesManager.preload(); // merge the disk file first, so the archive keys snapshot below sees every saved key
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            saved.put(entry.getKey(), PropertiesManager.getProperty(entry.getKey()));
            PropertiesManager.setProperties(entry.getKey(), entry.getValue());
        }
        for (String key : archiveKeys()) { archive.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        Path history = temp.newFolder("history").toPath();
        store = new SessionStore(history, true, "p4-quests");
        remember(AppHistory.class, "store", store);
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class, CharacterPetsGUI.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !statics.containsKey(field)) {
                    field.setAccessible(true); statics.put(field, field.get(null));
                }
        // Item names as the asset lookup gives them: a copy of the current map with QuestFixtures' names (the original map returns after).
        Field objects = IdToAsset.class.getDeclaredField("objectID"); objects.setAccessible(true);
        @SuppressWarnings("unchecked") HashMap<Integer, IdToAsset> names = new HashMap<>((Map<Integer, IdToAsset>) objects.get(null));
        Map<Integer, String> synthetic = new HashMap<>(NAMES);
        synthetic.put(SPIRIT_SHARD, "Spirit Shard");
        synthetic.forEach((id, name) -> names.put(id, new IdToAsset("", id, name, name, "", null, "", "", "")));
        remember(IdToAsset.class, "objectID", names);
        // The user's own type labels (spec: never inferred): category 5 Daily, 8 Event, 9 left unlabeled.
        Preferences node = Preferences.userNodeForPackage(QuestGUI.class);
        for (String key : List.of("category.5", "category.8", "category.9")) labels.put(key, node.get(key, null));
        node.put("category.5", "Daily"); node.put("category.8", "Event"); node.remove("category.9");
    }

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            evidence.closeWindow();
            if (gui != null) gui.closeWorkspace();
            DisplayModeModel.application().set(savedMode);
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        if (journal != null) journal.close();
        Preferences node = Preferences.userNodeForPackage(QuestGUI.class);
        for (Map.Entry<String, String> entry : labels.entrySet())
            if (entry.getValue() == null) node.remove(entry.getKey()); else node.put(entry.getKey(), entry.getValue());
        if (node.nodeExists("accounts/" + ACCOUNT)) node.node("accounts/" + ACCOUNT).removeNode();
        for (Map.Entry<String, String> entry : saved.entrySet())
            PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? DEFAULTS.get(entry.getKey()) : entry.getValue());
        for (String key : archiveKeys()) { String value = archive.get(key); PropertiesManager.setProperties(key, value == null ? "" : value); }
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (store != null) store.close();
        for (Map.Entry<String, PlanData.AccountPlan> entry : savedPlans.entrySet()) { // last: the rest is restored even if this fails
            PlanningStore plans = PlanningStore.shared();
            assertTrue("The planning store's plan is restored", plans.update(entry.getKey(), plans.snapshot(entry.getKey()).revision, entry.getValue())
                .get(5, TimeUnit.SECONDS).saved);
        }
    }

    /**
     * 8 captures of the Board: two quests pinned from the detail drawer, then the cards by chest tier at both sizes, by type label, the
     * drawer of the quest with a raw expiration (Simple, then Analyst at both sizes), the Table view, and the list once capture stops.
     */
    @Test public void boardShowsGroupedCardsTheDetailDrawerTheTableViewAndAStaleList() throws Exception {
        build();
        publish(quests(), System.currentTimeMillis() - 14 * MINUTE);
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(SIMPLE);
            evidence.show(shell, "Quests pins", 1240, 800, 13);
            assertTrue(Navigator.current().open(Route.to(Destination.QUESTS)));
            page().openBoard();
        });
        await("the cards", () -> cards("epic") != null && cards("mighty") != null);
        for (String[] pin : new String[][] {{"epic", "Royal tribute"}, {"mighty", "Festival exchange"}}) { // as the user pins: from the drawer
            SwingUtilities.invokeAndWait(() -> {
                open(pin[0], pin[1]);
                AbstractButton button = VisualEvidence.named(shell, "quest-detail-pin", AbstractButton.class);
                assertEquals("A bound page pins for the account in game", "Pin for account", button.getText());
                button.doClick();
                VisualEvidence.named(shell, "quest-detail-close", AbstractButton.class).doClick();
            });
        }
        Runnable tiers = () -> {
            assertEquals(TIERS, groupTitles());
            assertEquals("Pinned first within the group", "Festival exchange, pinned, repeatable, Event; pick 1 of 5: Mighty Quest Chest, "
                + "Royal Epic Quest Chest, Cultish Epic Quest Chest, Standard Quest Chest and 1 more; bring: 3 Festival Token", names(cards("mighty")).get(0));
            assertTrue(names(cards("mighty")).toString(), names(cards("mighty")).get(1).startsWith("Mighty haul, repeatable, done; you get: Mighty Quest Chest"));
            assertTrue(names(cards("epic")).toString(), names(cards("epic")).get(0).startsWith("Royal tribute, pinned, one-time, Daily; you get: Royal Epic Quest Chest"));
            assertEquals("Rewards and requirements unknown are said so", List.of("Unknown loot, one-time, Event; rewards not captured; requirements not captured"),
                names(cards("not-captured")));
            assertTrue(names(cards("no-chest")).toString(), names(cards("no-chest")).get(0).endsWith("nothing listed to bring"));
            assertSummary(false);
            JTabbedPane tabs = VisualEvidence.named(page(), "quests-tabs", JTabbedPane.class);
            assertEquals("Board", tabs.getTitleAt(0)); assertEquals("Planner", tabs.getTitleAt(1));
            assertEquals("The Board is in front", 0, tabs.getSelectedIndex());
            assertFalse("No drawer open", VisualEvidence.named(shell, "quest-detail", JComponent.class).isVisible());
            assertTrue("The capture shows the first group and its cards", inView(shown(shell, "quest-group-mighty")) && inView(cards("mighty")));
        };
        board("board-tier", 1240, 800, 13, SIMPLE, () -> {}, tiers);
        board("board-tier", 680, 520, 18, ANALYST, () -> {}, tiers);
        board("board-type", 1240, 800, 13, SIMPLE, () -> VisualEvidence.named(shell, "quest-group-by", JComboBox.class).setSelectedItem("Type label"), () -> {
            assertEquals("The user's labels, then unlabeled categories", List.of("Daily", "Event", "No type label"), groupTitles());
            assertEquals(4, cards("type-5").getModel().getSize());
            assertEquals(4, cards("type-8").getModel().getSize());
            assertTrue(names(cards("no-type")).get(0).startsWith("Mighty haul"));
            assertTrue("The capture shows the Daily cards", inView(cards("type-5")));
        });
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "quest-group-by", JComboBox.class).setSelectedItem("Chest tier"));
        detail(1240, 800, 13, SIMPLE);
        detail(1240, 800, 13, ANALYST);
        detail(680, 520, 18, ANALYST);
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "quest-detail-close", AbstractButton.class).doClick());
        board("board-table", 1240, 800, 13, SIMPLE, () -> VisualEvidence.named(shell, "quests-filter-bar", FilterBar.class).overflow().item("Table view").doClick(), () -> {
            JTable table = VisualEvidence.named(shell, "quest-table", JTable.class);
            assertTrue("The Table view shows the quest table", table.isShowing());
            assertEquals("Every open quest is a row", 9, table.getRowCount());
            assertFalse("…and the cards are hidden", VisualEvidence.named(shell, "quest-board", JComponent.class).isShowing());
            assertEquals("Simple: the ⋯ menu offers the cards back", "Cards view",
                VisualEvidence.named(shell, "quests-filter-bar", FilterBar.class).overflow().item("Cards view").getText());
            assertSummary(false);
        });
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "quests-filter-bar", FilterBar.class).overflow().item("Cards view").doClick());
        data.captureStopped(); // the list stays, but no longer matches the capture
        await("the stale summary", () -> summary().getText().endsWith(" · stale"));
        board("board-stale", 1240, 800, 13, SIMPLE, () -> {}, () -> {
            assertSummary(true);
            assertEquals("Stale reads in the warn tone", Tokens.tone(Tokens.Tone.WARN), summary().getForeground());
            assertEquals("The cards stay", TIERS, groupTitles());
        });
    }

    /** 1 capture: no quest list captured yet: the summary and the Board's one empty state say how to load one. */
    @Test public void anEmptyBoardSaysHowToLoadTheList() throws Exception {
        build();
        board("board-empty", 1240, 800, 13, SIMPLE, () -> {}, () -> {
            assertEquals("No quest list captured yet", summary().getText());
            EmptyState empty = VisualEvidence.named(shell, "quest-board-empty", EmptyState.class);
            assertTrue("The Board's empty state shows", empty.isShowing());
            assertTrue(showsText(empty, "Enter the Daily Quest Room during capture"));
            assertEquals("No groups", List.of(), groupTitles());
        });
    }

    /**
     * 6 captures of the Planner: the verified account's four plans under the All plans summary (both sizes), the compact page from its
     * top, its Manual stock drawer open, the offline account whose stock is mostly unconfirmed, and the Table view.
     */
    @Test public void plannerShowsPlanCardsTheSummaryTheStockDrawerUnknownStockAndTheTableView() throws Exception {
        long now = System.currentTimeMillis();
        savePlans(now);
        build();
        publish(quests(), now - 14 * MINUTE);
        Runnable account = () -> {
            assertEquals(4, planCards().getModel().getSize());
            assertEquals(List.of(
                "Royal tribute: Saved requirements; verify server; Requirements covered by manual stock; server eligibility unverified",
                "Festival exchange: Saved requirements; verify server; More items needed",
                "Cultist tribute: Saved requirements; verify server; More items needed",
                "Beginner errand: Saved requirements; verify server; Requirements covered by manual stock; server eligibility unverified"), names(planCards()));
            assertTrue("The All plans summary shows", VisualEvidence.named(shell, "quest-plan-summary", JComponent.class).isShowing());
            assertEquals("More items needed", text("quest-plan-summary-readiness"));
            // Combined over every plan, so the unreserved stock counts once (QuestPlanning.totals over all ids).
            assertEquals("Mark of the Forgotten King (#1): need 10 · reserved 4 · covered 6 · missing 0", text("quest-plan-summary-1"));
            assertEquals("Mark of Malus (#2): need 3 · reserved 0 · covered 1 · missing 2", text("quest-plan-summary-2"));
            assertEquals("Festival Token (#3): need 9 · reserved 2 · covered 2 · missing 5", text("quest-plan-summary-3"));
            for (int item : new int[] {FORGOTTEN_KING, MALUS, FESTIVAL_TOKEN})
                assertTrue("Known stock draws a bar", VisualEvidence.named(shell, "quest-plan-summary-" + item + "-bar", SegmentBar.class).known());
            assertTrue(text("quest-plan-status").endsWith("Verified snapshot available for import/reconfirmation."));
            JTabbedPane tabs = VisualEvidence.named(page(), "quests-tabs", JTabbedPane.class);
            assertEquals("The Planner is in front", "Planner", tabs.getTitleAt(tabs.getSelectedIndex()));
            assertTrue("The capture shows the summary's first bar", inView(shown(shell, "quest-plan-summary-1-bar")));
        };
        planner("planner-cards", 1240, 800, 13, SIMPLE, ACCOUNT, () -> {}, () -> planCards().getModel().getSize() == 4, account);
        planner("planner-cards", 680, 520, 18, ANALYST, ACCOUNT, () -> {}, () -> planCards().getModel().getSize() == 4, account);
        planner("planner-top", 680, 520, 18, ANALYST, ACCOUNT, true, () -> {}, () -> planCards().getModel().getSize() == 4, () -> {
            assertTrue("Analyst: the Cards/Table toggle shows", VisualEvidence.named(shell, "quest-plan-view", JComponent.class).isShowing());
            JComboBox<?> list = VisualEvidence.named(shell, "quest-plan-account", JComboBox.class);
            assertTrue("The account list shows", inView(list));
            // The 64-character account key is cut by the renderer: the list and its drop-down arrow stay whole inside the row.
            assertEquals("The whole account list shows: " + list.getWidth() + " px wide (preferred " + list.getPreferredSize().width + ")",
                list.getWidth(), list.getVisibleRect().width);
            assertEquals("The tooltip keeps the whole key", ACCOUNT, list.getToolTipText());
            assertTrue(text("quest-plan-status").endsWith("Verified snapshot available for import/reconfirmation."));
        });
        planner("planner-stock", 1240, 800, 13, SIMPLE, ACCOUNT, () -> VisualEvidence.named(shell, "collapsible-quest-plan-stock", AbstractButton.class).doClick(),
            () -> {
                JComponent content = VisualEvidence.named(shell, "quest-plan-stock-content", JComponent.class);
                return VisualEvidence.named(shell, "quest-plan-stock", Collapsible.class).expanded() && content.isShowing()
                    && content.getHeight() >= content.getPreferredSize().height;
            }, () -> {
                String held = text("quest-plan-held-values");
                assertTrue(held, held.startsWith("Held stock (account-wide, manual):"));
                assertTrue("Manual values say so: " + held, held.contains("Mark of the Forgotten King (#1): 12 (manual) · unallocated 8"));
                assertTrue(held, held.contains("Festival Token (#3): 4 (manual) · unallocated 2"));
                assertTrue(held, held.contains("Synthetic count; vault checked manually"));
                assertTrue("The drawer's editor shows", VisualEvidence.named(shell, "quest-plan-held", JButton.class).isShowing());
            });
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "collapsible-quest-plan-stock", AbstractButton.class).doClick());
        planner("planner-unknown-stock", 1240, 800, 13, SIMPLE, OFFLINE, () -> {}, () -> planCards().getModel().getSize() == 3, () -> {
            assertEquals(List.of(
                "Standard delivery: Saved requirements; verify server; Unknown — requirements or manual stock unconfirmed",
                "Token swap: Saved requirements; verify server; Requirements covered by manual stock; server eligibility unverified",
                "Unknown loot: Requirements unknown; Unknown — requirements or manual stock unconfirmed"), names(planCards()));
            assertEquals("Unknown — requirements or manual stock unconfirmed", text("quest-plan-summary-readiness"));
            assertEquals("Unknown stock is never 0", "Mark of the Forgotten King (#1): need 1 · Stock unconfirmed", text("quest-plan-summary-1"));
            assertNull("…and draws no bar", shown(shell, "quest-plan-summary-1-bar"));
            assertEquals("Mark of Malus (#2): need 2 · reserved 0 · covered 2 · missing 0", text("quest-plan-summary-2"));
            assertNotNull("Confirmed stock keeps its bar", shown(shell, "quest-plan-summary-2-bar"));
            assertTrue(text("quest-plan-status").endsWith("Offline manual editing; server eligibility and snapshot freshness are unverified."));
            assertTrue("The capture shows the cards", inView(planCards()));
        });
        planner("planner-table", 1240, 800, 13, SIMPLE, ACCOUNT,
            () -> VisualEvidence.named(shell, "quest-plan-overflow", OverflowMenu.class).item("Table view").doClick(),
            () -> VisualEvidence.named(shell, "quest-plan-table", JTable.class).isShowing(), () -> {
                assertEquals("One row per plan", 4, VisualEvidence.named(shell, "quest-plan-table", JTable.class).getRowCount());
                assertNull("The cards are hidden", shown(shell, "quest-plan-cards"));
                assertTrue("The combined totals of the selection show", VisualEvidence.named(shell, "quest-plan-totals", JTextArea.class).isShowing());
            });
    }

    /**
     * Nine synthetic quests over QuestFixtures' item names; categories 5 and 8 are labeled Daily and Event, 9 is unlabeled.
     * Mighty: Festival exchange (Event; repeatable; pick 1 of 5 chests), Mighty haul (unlabeled; repeatable and done). Epic: Royal
     * tribute (Daily; ten marks; a raw expiration), Cultist tribute (Daily). Standard: Standard delivery (Daily; five items to bring;
     * two tokens with the chest). Beginner: Beginner errand (Event). Other quest chests: Golden cache (Event). No quest chest: Token
     * swap (Daily; nothing listed to bring). Rewards not captured: Unknown loot (Event; neither list captured).
     */
    private static QuestData[] quests() {
        QuestData royal = data("Royal tribute", 5, repeat(FORGOTTEN_KING, 10), ROYAL_EPIC_CHEST);
        royal.expiration = EXPIRATION;
        QuestData cultist = data("Cultist tribute", 5, new int[] {MALUS, MALUS}, CULTISH_EPIC_CHEST);
        QuestData festival = data("Festival exchange", 8, new int[] {FESTIVAL_TOKEN, FESTIVAL_TOKEN, FESTIVAL_TOKEN},
            MIGHTY_CHEST, ROYAL_EPIC_CHEST, CULTISH_EPIC_CHEST, STANDARD_CHEST, BEGINNER_CHEST);
        festival.itemOfChoice = true; festival.repeatable = true;
        QuestData haul = data("Mighty haul", 9, new int[] {FORGOTTEN_KING, MALUS}, MIGHTY_CHEST);
        haul.repeatable = true; haul.completed = true;
        QuestData delivery = data("Standard delivery", 5, new int[] {FORGOTTEN_KING, MALUS, MALUS, FESTIVAL_TOKEN, FESTIVAL_TOKEN, FESTIVAL_TOKEN,
            UNKNOWN_ITEM, SPIRIT_SHARD}, STANDARD_CHEST, FESTIVAL_TOKEN, FESTIVAL_TOKEN);
        QuestData errand = data("Beginner errand", 8, new int[] {MALUS}, BEGINNER_CHEST);
        QuestData golden = data("Golden cache", 8, new int[] {FESTIVAL_TOKEN, FESTIVAL_TOKEN}, GOLDEN_CHEST);
        QuestData swap = data("Token swap", 5, new int[0], FESTIVAL_TOKEN);
        QuestData unknown = data("Unknown loot", 8, null); unknown.rewards = null;
        return new QuestData[] {royal, cultist, festival, haul, delivery, errand, golden, swap, unknown};
    }

    private static int[] repeat(int id, int count) { int[] ids = new int[count]; Arrays.fill(ids, id); return ids; }

    private static QuestData quest(String name) {
        for (QuestData q : quests()) if (q.name.equals(name)) return q;
        throw new AssertionError("No quest " + name);
    }

    /**
     * Saves the Planner's plans (the previous plans of both accounts are restored after). The account in game: four plans, held
     * stock confirmed for every item they need, reservations for two. The offline account: three plans, stock confirmed only for
     * Mark of Malus, one plan with requirements not captured and one with nothing to bring.
     */
    private void savePlans(long now) throws Exception {
        PlanData.AccountPlan inGame = new PlanData.AccountPlan();
        for (String name : List.of("Royal tribute", "Festival exchange", "Cultist tribute", "Beginner errand")) put(inGame, name, now);
        inGame.quests.get("Festival exchange").desiredRepeats = 3;
        QuestPlanning.held(inGame, FORGOTTEN_KING, 12, "Synthetic count; vault checked manually", false, now - 30 * MINUTE);
        QuestPlanning.held(inGame, FESTIVAL_TOKEN, 4, "Synthetic count", false, now - 30 * MINUTE);
        QuestPlanning.held(inGame, MALUS, 1, "", false, now - 30 * MINUTE);
        QuestPlanning.reserve(inGame, "Royal tribute", FORGOTTEN_KING, 4);
        QuestPlanning.reserve(inGame, "Festival exchange", FESTIVAL_TOKEN, 2);
        PlanData.AccountPlan offline = new PlanData.AccountPlan();
        for (String name : List.of("Standard delivery", "Token swap", "Unknown loot")) put(offline, name, now);
        QuestPlanning.held(offline, MALUS, 3, "Synthetic count", false, now - 60 * MINUTE);
        PlanningStore plans = PlanningStore.shared();
        for (Map.Entry<String, PlanData.AccountPlan> plan : Map.of(ACCOUNT, inGame, OFFLINE, offline).entrySet()) {
            await("the planning store", () -> plans.snapshot(plan.getKey()).ready);
            PlanningStore.Snapshot before = plans.snapshot(plan.getKey());
            assertFalse("The planning store is writable: " + before.status, before.readOnly);
            savedPlans.put(plan.getKey(), before.plan());
            assertTrue(plans.update(plan.getKey(), before.revision, plan.getValue()).get(5, TimeUnit.SECONDS).saved);
        }
    }

    /** A plan entry exactly as "Add to account plan" snapshots the captured quest (QuestPlanning.snapshot of the page's copy). */
    private static void put(PlanData.AccountPlan plan, String name, long now) throws Exception {
        Class<?> copy = Class.forName("tomato.gui.quest.QuestGUI$Quest");
        Constructor<?> constructor = copy.getDeclaredConstructor(QuestData.class); constructor.setAccessible(true);
        Method snapshot = QuestPlanning.class.getMethod("snapshot", copy, long.class, long.class);
        PlanData.QuestPlanEntry entry = (PlanData.QuestPlanEntry) snapshot.invoke(null, constructor.newInstance(quest(name)), now - 20 * MINUTE, 1L);
        plan.quests.put(entry.entryId, entry);
    }

    private void build() throws Exception {
        journal = new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
        });
    }

    /** The account in game receives {@code rows} as the Daily Quest Room sends them. */
    private void publish(QuestData[] rows, long at) {
        ProgressionData source = data.progression();
        source.reset(ACCOUNT, "Synthetic identified account");
        assertTrue(source.quests(source.scope(), rows, at));
    }

    /**
     * One capture of the Board ({@code arrange} runs after the route, e.g. a grouping or view change), from the top of the page as it
     * opens; compact, where the header and the filter row fill the first screen, scrolled to the first group as the user would.
     * {@code check} reads what the capture is evidence of.
     */
    private void board(String state, int width, int height, int font, DisplayModeModel.Mode mode, Runnable arrange, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Quests " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.QUESTS)));
            page().openBoard();
            arrange.run();
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane page = VisualEvidence.named(shell, "quest-page-scroll", JScrollPane.class);
            if (width < 1000) scrollToTop(page, VisualEvidence.named(shell, "quest-groups", JComponent.class));
            else page.getVerticalScrollBar().setValue(0);
        });
        pause();
        capture(state, width, font, mode, check);
    }

    /**
     * One capture of Royal tribute's detail drawer, opened as Enter opens a card (the page scrolls to the drawer itself). In Analyst,
     * the raw expiration line is then scrolled into view (compact, the drawer is taller than the first screen).
     */
    private void detail(int width, int height, int font, DisplayModeModel.Mode mode) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Quests board detail", width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.QUESTS)));
            page().openBoard();
        });
        pause();
        SwingUtilities.invokeAndWait(() -> open("epic", "Royal tribute"));
        pause();
        if (mode == ANALYST) {
            SwingUtilities.invokeAndWait(() -> {
                JComponent expiration = VisualEvidence.named(shell, "quest-detail-expiration", JComponent.class);
                expiration.scrollRectToVisible(new Rectangle(0, 0, expiration.getWidth(), expiration.getHeight()));
            });
            pause();
        }
        capture("board-detail", width, font, mode, () -> {
            JComponent drawer = VisualEvidence.named(shell, "quest-detail", JComponent.class);
            assertTrue("The drawer shows", drawer.isShowing());
            assertEquals("Royal tribute", VisualEvidence.named(drawer, "quest-detail-name", JTextArea.class).getText());
            assertTrue("The full requirement list with its count", showsText(drawer, "10 × Mark of the Forgotten King"));
            assertWholeInWindow(drawer, "The drawer");
            Component expiration = shown(drawer, "quest-detail-expiration");
            if (mode == ANALYST) {
                assertNotNull("Analyst shows the raw expiration", expiration);
                assertEquals("Expiration (raw server value): " + EXPIRATION, ((JTextArea) expiration).getText());
                assertEquals("…whole in the capture", expiration.getHeight(), ((JComponent) expiration).getVisibleRect().height);
            } else {
                assertNull("Simple shows no expiration", expiration);
                assertFalse("…anywhere in the drawer", showsText(drawer, EXPIRATION));
            }
        });
    }

    /**
     * One capture of the Planner for {@code account}, selected in its account list after the route; {@code arrange} runs next, then
     * the capture waits for {@code ready}. The page shows from its top; compact, from the All plans summary (the header fills the
     * first screen); an open Manual stock drawer is scrolled into view.
     */
    private void planner(String state, int width, int height, int font, DisplayModeModel.Mode mode, String account, Runnable arrange,
                         BooleanSupplier ready, Runnable check) throws Exception {
        planner(state, width, height, font, mode, account, false, arrange, ready, check);
    }

    /** {@code top}: the page exactly as it opens, from its top, at any size. */
    private void planner(String state, int width, int height, int font, DisplayModeModel.Mode mode, String account, boolean top, Runnable arrange,
                         BooleanSupplier ready, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Quests " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.QUESTS)));
            page().openPlans();
            VisualEvidence.named(shell, "quest-plan-account", JComboBox.class).setSelectedItem(account);
            assertEquals(account, VisualEvidence.named(shell, "quest-plan-account", JComboBox.class).getSelectedItem());
        });
        pause();
        SwingUtilities.invokeAndWait(arrange);
        Thread.sleep(Motion.MAX_MILLIS + 50); // a drawer's motion (at most this long) runs before it measures its full height
        await(state, ready);
        pause();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane page = VisualEvidence.named(shell, "quest-plan-scroll", JScrollPane.class);
            page.getVerticalScrollBar().setValue(0);
            Collapsible stock = VisualEvidence.named(shell, "quest-plan-stock", Collapsible.class);
            JComponent summary = VisualEvidence.named(shell, "quest-plan-summary", JComponent.class);
            if (stock.expanded()) stock.scrollRectToVisible(new Rectangle(0, 0, stock.getWidth(), stock.getHeight()));
            else if (width < 1000 && !top && summary.isShowing()) scrollToTop(page, summary);
        });
        pause();
        capture(state, width, font, mode, check);
    }

    private void capture(String state, int width, int font, DisplayModeModel.Mode mode, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String name = "p4-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT);
            evidence.capture(name);
            errors.checkSucceeds(() -> { nothingSideways(shell, name); return null; });
            errors.checkSucceeds(() -> { check.run(); return null; });
        });
    }

    private QuestGUI page() { return VisualEvidence.find(shell, QuestGUI.class, q -> true); }
    private JTextArea summary() { return VisualEvidence.named(shell, "quest-summary", JTextArea.class); }
    private TileList<?> planCards() { return VisualEvidence.named(shell, "quest-plan-cards", TileList.class); }
    private String text(String name) { return VisualEvidence.named(shell, name, JTextArea.class).getText(); }

    /** The showing card list of the group {@code key}, or null. */
    private TileList<?> cards(String key) { return (TileList<?>) shown(shell, "quest-cards-" + key); }

    /** "9 quests · 2 pinned · captured N min ago" (the age ticks each minute), with " · stale" once capture stops. */
    private void assertSummary(boolean stale) {
        String text = summary().getText();
        assertTrue(text, text.startsWith("9 quests · 2 pinned · captured ") && text.contains(" min ago"));
        assertEquals(text, stale, text.endsWith(" · stale"));
    }

    /** Scrolls {@code page} so that {@code part} starts at the top of its viewport (as far as the page scrolls). */
    private static void scrollToTop(JScrollPane page, JComponent part) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), page.getViewport().getView());
        page.getVerticalScrollBar().setValue(placed.y);
    }

    /** Whether some of {@code part} is inside the visible part of its scroll pane (the capture shows it). */
    private static boolean inView(Component part) { return part != null && part.isShowing() && ((JComponent) part).getVisibleRect().height > 0; }

    /** The titles of the showing group headers, top to bottom. */
    private List<String> groupTitles() {
        List<String> titles = new ArrayList<>();
        collectTitles(VisualEvidence.named(shell, "quest-board", JComponent.class), titles);
        return titles;
    }

    private static void collectTitles(Container root, List<String> titles) {
        List<Component> children = new ArrayList<>(Arrays.asList(root.getComponents()));
        children.sort(Comparator.comparingInt(Component::getY)); // the sections stack vertically
        for (Component child : children) {
            if (child instanceof SectionHeader && child.isShowing() && child.getName() != null && child.getName().startsWith("quest-group-"))
                titles.add(((SectionHeader) child).title());
            else if (child instanceof Container) collectTitles((Container) child, titles);
        }
    }

    /** Selects the card whose announced name starts with {@code quest} in the group {@code key} and opens it as Enter does. */
    private void open(String key, String quest) {
        TileList<?> list = cards(key);
        assertNotNull("Missing group " + key, list);
        List<String> names = names(list);
        int index = -1;
        for (int i = 0; i < names.size(); i++) if (names.get(i).startsWith(quest + ",")) index = i;
        assertTrue("No card " + quest + " in " + names, index >= 0);
        list.setSelectedIndex(index);
        list.getActionMap().get(TileList.OPEN).actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, TileList.OPEN));
    }

    /** What a screen reader announces for each card (the painted text is not in the component tree). */
    private static List<String> names(JList<?> tiles) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tiles.getModel().getSize(); i++)
            names.add(tiles.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName());
        return names;
    }

    /** Polls {@code condition} on the EDT (the page applies its lists on later EDT turns) for up to 15 s. */
    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > end) fail(what + " did not settle within 15 s");
            Thread.sleep(50);
        }
    }

    /**
     * No showing scroll pane scrolls or cuts its content sideways: no horizontal scroll bar, and the view no wider than the viewport.
     * A data table scrolls its own columns sideways by design and is listed on standard output instead (as in P3b).
     */
    private static void nothingSideways(Container root, String capture) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && child.isShowing()) {
                JScrollPane scroll = (JScrollPane) child;
                Component view = scroll.getViewport().getView();
                String where = scroll.getName() != null ? scroll.getName() : view == null ? "an empty scroll pane" : view.getClass().getSimpleName();
                if (view instanceof JTable) System.out.println(capture + ": table " + where + " " + view.getWidth() + " px in a " + scroll.getViewport().getWidth()
                    + " px viewport, horizontal bar " + (scroll.getHorizontalScrollBar().isShowing() ? "shown" : "hidden"));
                else {
                    assertFalse(capture + ": a horizontal scroll bar in " + where, scroll.getHorizontalScrollBar().isShowing());
                    if (view != null) assertTrue(capture + ": " + where + " is " + view.getWidth() + " px wide in a " + scroll.getViewport().getWidth() + " px viewport",
                        view.getWidth() <= scroll.getViewport().getWidth());
                }
            }
            if (child instanceof Container) nothingSideways((Container) child, capture);
        }
    }

    /** Inside the window horizontally (the page may be scrolled vertically). */
    private void assertWholeInWindow(JComponent part, String what) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), shell);
        assertTrue(what + " is whole: " + placed + " in a window " + shell.getWidth() + " wide", part.isShowing() && placed.x >= 0
            && placed.x + placed.width <= shell.getWidth());
    }

    /** The first showing component named {@code name} under {@code root}, or null. */
    private static Component shown(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && child.isShowing()) return child;
            if (child instanceof Container) { Component found = shown((Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    private static boolean showsText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c.isShowing() && (c instanceof JLabel && String.valueOf(((JLabel) c).getText()).contains(text)
                || c instanceof javax.swing.text.JTextComponent && ((javax.swing.text.JTextComponent) c).getText().contains(text))) return true;
            if (c instanceof Container && showsText((Container) c, text)) return true;
        }
        return false;
    }

    /** The page applies lists on later EDT turns and Collapsible motion takes at most Motion.MAX_MILLIS: settle, wait, settle. */
    private void pause() throws Exception { evidence.settle(); Thread.sleep(400); evidence.settle(); }

    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); statics.put(field, field.get(null)); field.set(null, next);
    }

    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties) field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive."));
        return keys;
    }
}
