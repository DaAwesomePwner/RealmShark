package tomato.gui.history;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.TomatoData;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.character.CharacterJournalGUI;
import tomato.gui.chat.ChatArchiveClient;
import tomato.gui.chat.ChatGUI;
import tomato.gui.keypop.KeyPopArchiveClient;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.FilterBar;
import tomato.gui.quest.QuestGUI;
import tomato.gui.stats.HistoricalStatistics;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;

/** S6 evidence: adopted pages with filters collapsed and open, 1240×800 and 680×520, fonts 13 and 18. Synthetic data only; no capture. */
public class FilterBarEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("redesign-p1c");
    private final Map<String, String> savedPreferences = new LinkedHashMap<>();

    @Before public void isolatePreferences() throws Exception {
        edt(() -> {
            for (String key : new String[]{"ux.archive.characters-live-roster", "ui.tabs.character-detail", "ui.tabs.quests",
                    "ui.filters.runs.open", "ui.filters.loot.open", "ui.filters.chat.open", "ui.filters.keypops.open",
                    "ui.filters.characters.open", "ui.filters.quests.open"}) {
                savedPreferences.put(key, util.PropertiesManager.getProperty(key));
                util.PropertiesManager.setProperties(key, "");
            }
            return null;
        });
    }

    @After public void restorePreferences() throws Exception {
        // Disposal can queue a final view-state save; restore only after that EDT work drains.
        edt(() -> null);
        edt(() -> { savedPreferences.forEach((key, value) -> util.PropertiesManager.setProperties(key, value == null ? "" : value)); return null; });
    }

    private static final class Page {
        final String name; final JComponent root; final FilterBar bar; final BooleanSupplier ready;
        Page(String name, JComponent root, FilterBar bar, BooleanSupplier ready) { this.name = name; this.root = root; this.bar = bar; this.ready = ready; }
    }

    @Test @SuppressWarnings("unchecked") public void adoptedPagesShowOneFilterRowUntilTheDrawerOpens() throws Exception {
        Path root = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        Path runsScratch = temp.newFolder().toPath(), lootScratch = temp.newFolder().toPath(), chatScratch = temp.newFolder().toPath(), popsScratch = temp.newFolder().toPath();
        try (SessionStore store = new SessionStore(root, true, "p1c-evidence"); DiscoveryLog log = new DiscoveryLog(null)) {
            for (int i = 0; i < 6; i++) {
                ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "visit-" + i; visit.map = i % 2 == 0 ? "Lost Halls" : "Ice Citadel";
                visit.started = 1_790_000_000_000L + i * 600_000L; visit.lastSeen = visit.ended = visit.started + 420_000L; store.put("runs", visit.id, visit);
            }
            store.flush();
            List<Page> pages = edt(() -> {
                List<Page> built = new ArrayList<>();
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs =
                    ActivityPanel.workspace(store, new ActivityPanel(log, ActivityPanel.Mode.RUNS), ActivityPanel.Mode.RUNS, runsScratch, memory.states);
                ActivityQueries.Filters run = runs.state().query.facets(); run.outcomes.add(ActivityQueries.Outcome.LEFT); run.minimumDurationMillis = 60_000L;
                runs.changeQuery(runs.state().query.withFacets(run)); built.add(archive("runs", runs));
                ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> loot = HistoricalStatistics.lootWorkspace(store, new LootDashboard(), lootScratch, memory.states);
                LootQuery.Facets items = loot.state().query.facets(); items.bags.add("White"); items.kind = LootQuery.Kind.UT_EQUIPMENT;
                loot.changeQuery(loot.state().query.withFacets(items)); built.add(archive("loot", loot));
                ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> chat =
                    (ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort>) new ChatGUI(new TomatoData()).workspace(store, chatScratch, memory.states);
                ChatArchiveClient.Facets channel = chat.state().query.facets(); channel.channel = "GUILD"; channel.starredOnly = true;
                chat.changeQuery(chat.state().query.withFacets(channel)); built.add(archive("chat", chat));
                ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> keypops =
                    (ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort>) new KeypopGUI().workspace(store, popsScratch, memory.states);
                KeyPopArchiveClient.Facets pops = keypops.state().query.facets(); pops.exactPlayer = "Ann"; pops.kinds.add("KEY");
                keypops.changeQuery(keypops.state().query.withFacets(pops)); built.add(archive("keypops", keypops));
                CharacterJournalGUI characters = new CharacterJournalGUI(new TomatoData().characterJournal());
                VisualEvidence.named(characters, "character-facet-2", JComboBox.class).setSelectedIndex(1);
                VisualEvidence.named(characters, "character-facet-4", JComboBox.class).setSelectedIndex(2);
                built.add(new Page("characters", characters, VisualEvidence.named(characters, "characters-filter-bar", FilterBar.class), () -> true));
                QuestGUI quests = new QuestGUI();
                VisualEvidence.named(quests, "quest-repeat-mode", JComboBox.class).setSelectedIndex(1);
                VisualEvidence.named(quests, "quest-pinned-only", AbstractButton.class).doClick();
                built.add(new Page("quests", quests, VisualEvidence.named(quests, "quests-filter-bar", FilterBar.class), () -> true));
                return built;
            });
            try {
                for (Page page : pages) for (int font : new int[]{13, 18}) for (int[] size : new int[][]{{1240, 800}, {680, 520}}) for (boolean open : new boolean[]{false, true}) {
                    edt(() -> { ArchiveNativeSupport.drawer(page.bar, open); evidence.show(page.root, page.name, size[0], size[1], font); return null; });
                    ArchiveNativeSupport.await(page.ready); evidence.settle(); ArchiveNativeSupport.await(page.ready);
                    edt(() -> {
                        evidence.capture("p1c-" + page.name + "-" + size[0] + "-" + font + (open ? "-filters-open" : "-filters-closed"));
                        assertEquals(open, page.bar.drawerOpen());
                        assertEquals(page.name + " drawer visibility", open, page.bar.drawerContent().isShowing());
                        assertTrue(page.name + " shows its active filters as chips", page.bar.activeCount() > 0);
                        if (!open && size[0] == 1240 && font == 13) assertOneFilterRow(page.name, page.bar);
                        return null;
                    });
                }
            } finally {
                edt(() -> {
                    for (Page page : pages) { ArchiveNativeSupport.drawer(page.bar, false); if (page.root instanceof ArchiveWorkspace) ((ArchiveWorkspace<?, ?, ?>) page.root).close(); }
                    evidence.closeWindow(); return null;
                });
            }
        }
    }

    private static Page archive(String name, ArchiveWorkspace<?, ?, ?> workspace) {
        return new Page(name, workspace, workspace.filterBar(), () -> ArchiveNativeSupport.ready(workspace) && workspace.state().archive);
    }

    /** S6 at desktop width: with the drawer closed, the search slot and the Filters toggle share one row. */
    private static void assertOneFilterRow(String name, FilterBar bar) {
        AbstractButton filters = VisualEvidence.named(bar, bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class);
        Component slot = filters.getParent().getComponent(0);
        int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
        assertTrue(name + ": the search slot and Filters share one row", Math.abs(slotY - filtersY) < filters.getHeight());
    }
}
