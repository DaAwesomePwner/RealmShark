package tomato.gui.stats;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.history.*;
import tomato.gui.stats.LootQuery.*;
import tomato.history.*;
import tomato.history.archive.*;
import tomato.realmshark.ParseEnchants;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;

public class StatisticsArchiveNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();

    private Path history() throws Exception {
        Path root = temp.newFolder().toPath();
        for (int session = 0; session < 2; session++) try (SessionStore source = new SessionStore(root,true,"native-evidence")) {
            ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "same-local-visit"; visit.map = "Lost Halls";
            visit.started = 1000; visit.ended = visit.lastSeen = 121000; source.put("runs",visit.id,visit);
            for (int row = 0; row < 70; row++) source.append("loot",new LootDashboard.Drop(row < 65 ? "White" : "Orange", "Lost Halls", "Synthetic boss",
                2000 + row * 1000L, Collections.singletonList(new LootDashboard.Item(910000 + row,row < 65 ? "Needle blade " + row : "Other robe",
                    row < 65 ? "EQUIPMENT,WEAPON,UT" : "EQUIPMENT,ARMOR,ST",ParseEnchants.summarize(""))),visit.id));
            source.append("fame",new AppHistory.FameSample(7,100,1000,"Wizard")); source.append("fame",new AppHistory.FameSample(7,125,121000,"Wizard"));
            source.flush();
        }
        return root;
    }

    @Test public void actualLootFactoryFiltersBeforePagingAndRestoresNamedOccurrenceSelectionThroughExportFailure() throws Exception {
        Path root = history(), scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath(); Memory memory = new Memory();
        try (SessionStore store = new SessionStore(root,true,"reader")) {
            ArchiveWorkspace<Row,Facets,Sort> workspace = edt(() -> HistoricalStatistics.lootWorkspace(store,new LootDashboard(),scratch,memory.states));
            JComponent shell = edt(() -> shell(workspace,8));
            try {
                edt(() -> { evidence.show(shell,"Loot live default",1240,800,13); assertFalse(workspace.state().archive); return null; });
                evidence.settle(); edt(() -> { evidence.capture("loot-live-default"); workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 140);
                edt(() -> { named(workspace,"loot-history-search",JTextField.class).setText("Needle"); named(workspace,"loot-history-search",JTextField.class).postActionEvent(); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 130);
                edt(() -> {
                    named(workspace,"loot-kind",JComboBox.class).setSelectedItem(Kind.UT_EQUIPMENT);
                    named(workspace,"loot-apply-facets",JButton.class).doClick(); return null;
                });
                await(() -> ready(workspace) && workspace.state().query.facets().kind == Kind.UT_EQUIPMENT);
                edt(() -> { workspace.selectPage(1); return null; }); await(() -> ready(workspace) && workspace.displayedPage().page == 1);
                edt(() -> { assertEquals(30,workspace.displayedPage().rows.size()); named(workspace,"loot-archive-table",JTable.class).setRowSelectionInterval(3,3); return null; });
                ViewState<Facets,Sort> saved = edt(workspace::state);
                edt(() -> workspace.saveNamed("Needle occurrences")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                matrix(evidence,layouts,shell,"loot-all-filtered",() -> ready(workspace),() -> {
                    assertEquals(130,workspace.displayedPage().matches); assertTrue(named(workspace,"loot-archive-details",JTextArea.class).getText().contains("Origin:"));
                    archiveControls(workspace,"loot","loot-archive-table","loot-archive-details");
                });
                edt(() -> { JTabbedPane tabs = named(workspace,"loot-archive-tabs",JTabbedPane.class); tabs.setSelectedIndex(tabs.indexOfTab("By Bag")); return null; });
                await(() -> ready(workspace) && workspace.state().query.facets().view == View.BAGS);
                assertEquals(1,edt(() -> workspace.displayedPage().matches).longValue());
                edt(() -> { workspace.loadNamed("Needle occurrences"); return null; });
                await(() -> ready(workspace) && workspace.state().query.facets().view == View.OCCURRENCES);
                assertEquals(saved.selected,edt(() -> workspace.state().selected)); assertEquals(saved.query,edt(() -> workspace.state().query));
                failExport(workspace);
                matrix(evidence,layouts,shell,"loot-export-error",() -> ready(workspace),() -> {
                    assertTrue(textPresent(workspace,"Export failed:")); archiveControls(workspace,"loot","loot-archive-table","loot-archive-details");
                });
                Path file = edt(() -> workspace.exportTo(output,"occurrences",ExportSelection.all(),ArchiveExport.Format.JSON)).get(15,TimeUnit.SECONDS);
                assertEquals(130,json(file).getAsJsonArray("rows").size());
            } finally { edt(() -> { workspace.close(); evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void actualStatisticsFactoryKeepsSessionAndFamePopulationsAndNamedAnalyticalTabs() throws Exception {
        Path root = history(), scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath(); Memory memory = new Memory();
        try (SessionStore store = new SessionStore(root,true,"reader")) {
            ArchiveWorkspace<Row,Facets,Sort> workspace = edt(() -> HistoricalStatistics.statisticsWorkspace(store,new StatisticsGUI(new TomatoData()),scratch,memory.states));
            JComponent shell = edt(() -> shell(workspace,4));
            try {
                edt(() -> { evidence.show(shell,"Statistics live default",1240,800,13); assertFalse(workspace.state().archive); return null; });
                evidence.settle(); edt(() -> { evidence.capture("statistics-live-default"); workspace.changeQuery(workspace.state().query.withScope(SessionStore.ALL).withText("native-evidence")); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
                edt(() -> { named(workspace,"loot-archive-table",JTable.class).setRowSelectionInterval(0,0); return null; });
                edt(() -> workspace.saveNamed("Source sessions")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                matrix(evidence,layouts,shell,"statistics-sessions",() -> ready(workspace),() -> {
                    assertEquals(2,workspace.displayedPage().matches); assertEquals(View.SESSIONS,workspace.state().query.facets().view);
                    archiveControls(workspace,"statistics","loot-archive-table","loot-archive-details");
                    completeButton(named(workspace,"archive-open-fame",JButton.class));
                });
                edt(() -> { workspace.changeQuery(workspace.state().query.withText("")); return null; }); await(() -> ready(workspace));
                edt(() -> { JTabbedPane tabs = named(workspace,"loot-archive-tabs",JTabbedPane.class); tabs.setSelectedIndex(tabs.indexOfTab("Character fame")); return null; });
                await(() -> ready(workspace) && workspace.state().query.facets().view == View.FAME && workspace.displayedPage().matches == 2);
                edt(() -> { for (ArchiveRow<Row> row : workspace.displayedPage().rows) assertEquals(Double.valueOf(25),row.value.gain);
                    named(workspace,"loot-archive-table",JTable.class).setRowSelectionInterval(0,0); return null; });
                matrix(evidence,layouts,shell,"statistics-fame",() -> ready(workspace),() -> archiveControls(workspace,"statistics","loot-archive-table","loot-archive-details"));
                Path file = edt(() -> workspace.exportTo(output,"fame",ExportSelection.all(),ArchiveExport.Format.JSON)).get(15,TimeUnit.SECONDS);
                assertEquals(2,json(file).getAsJsonArray("rows").size());
                edt(() -> { workspace.loadNamed("Source sessions"); return null; }); await(() -> ready(workspace) && workspace.state().query.facets().view == View.SESSIONS);
                assertEquals("native-evidence",edt(() -> workspace.state().query.text()));
            } finally { edt(() -> { workspace.close(); evidence.closeWindow(); return null; }); }
        }
    }
}
