package tomato.gui.activity;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;

public class ActivityArchiveNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void runsShowGlobalFilteredPagesAndPreviewTheExactLinkedPopulation() throws Exception { exercise(ActivityPanel.Mode.RUNS,"runs",10); }
    @Test public void timelineShowsGlobalTypeMatchesAndPreservesExactEventDetails() throws Exception { exercise(ActivityPanel.Mode.TIMELINE,"timeline",11); }
    @Test public void resourcesRestoreSavedUptimeTabAndSelectedPinnedVisit() throws Exception { exercise(ActivityPanel.Mode.COMBAT,"combat",7); }

    private void exercise(ActivityPanel.Mode mode, String key, int page) throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath();
        ActivityNativeFixtures.seed(root); Memory memory = new Memory();
        try (SessionStore store = new SessionStore(root,true,"native-reader"); DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSaving(false);
            ActivityPanel live = edt(() -> new ActivityPanel(log,mode));
            ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace = edt(() -> ActivityPanel.workspace(store,live,mode,scratch,memory.states));
            JComponent shell = edt(() -> shell(workspace,page));
            try {
                edt(() -> { evidence.show(shell,key + " live default",1240,800,13); assertFalse(workspace.state().archive); return null; });
                evidence.settle(); edt(() -> { evidence.capture(key + "-live-default"); workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == (mode == ActivityPanel.Mode.TIMELINE ? 1200 : 150));
                edt(() -> {
                    ActivityQueries.Filters filters = workspace.state().query.facets();
                    if (mode == ActivityPanel.Mode.TIMELINE) filters.kinds.add("Equipment changed");
                    else filters.outcomes.add(ActivityQueries.Outcome.LEFT);
                    workspace.changeQuery(workspace.state().query.withFacets(filters)); return null;
                });
                long count = mode == ActivityPanel.Mode.TIMELINE ? 1050 : 140;
                await(() -> ready(workspace) && workspace.displayedPage().matches == count);
                edt(() -> { workspace.selectPage(1); return null; }); await(() -> ready(workspace) && workspace.displayedPage().page == 1);
                edt(() -> { named(workspace,"saved-activity-table",JTable.class).setRowSelectionInterval(0,0); return null; });
                await(() -> textPresent(workspace,mode == ActivityPanel.Mode.TIMELINE ? "Raw fields" : "Buff coverage"));
                ArchiveRow.Ref selected = edt(() -> workspace.state().selected.get(0));
                if (mode == ActivityPanel.Mode.COMBAT) edt(() -> { named(workspace,"saved-resource-tabs",JTabbedPane.class).setSelectedIndex(2); return null; });
                matrix(evidence,layouts,shell,key + "-all-filtered",() -> ready(workspace),() -> {
                    assertEquals(count,workspace.displayedPage().matches); assertEquals(selected,workspace.state().selected.get(0));
                    assertTrue(named(workspace,"activity-archive-detail",JTextArea.class).getText().contains(selected.session));
                    archiveControls(workspace,key,"saved-activity-table","activity-archive-detail");
                    completeText(named(workspace,"activity-archive-counts",JTextArea.class));
                });
                if (mode != ActivityPanel.Mode.TIMELINE) {
                    String preview = preview(workspace,evidence,key + "-linked-preview");
                    assertTrue(preview,preview.contains("1 selected visit + 8 linked Timeline events")); assertTrue(preview.contains(selected.session));
                    Path file = edt(() -> workspace.exportTo(output,key,ExportSelection.selected(Collections.singleton(selected)),ArchiveExport.Format.JSON)).get(15,TimeUnit.SECONDS);
                    assertEquals(9,json(file).getAsJsonArray("rows").size());
                    assertEquals(8,json(file).getAsJsonObject("manifest").get("linkedEventCount").getAsInt());
                }
                if (mode == ActivityPanel.Mode.COMBAT) {
                    edt(() -> { named(workspace,"saved-resource-tabs",JTabbedPane.class).setSelectedIndex(1); return null; });
                    await(() -> ActivityArchiveUiTest.named(workspace,JTable.class,"saved-buff-uptime") != null);
                    edt(() -> workspace.saveNamed("Pinned uptime")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                    matrix(evidence,layouts,shell,"resources-uptime",() -> ready(workspace),() -> {
                        JTable uptime = named(workspace,"saved-buff-uptime",JTable.class); assertEquals(50.0,uptime.getValueAt(0,3)); tableRows(uptime);
                        archiveControls(workspace,key,"saved-activity-table",null);
                    });
                    edt(() -> { named(workspace,"saved-resource-tabs",JTabbedPane.class).setSelectedIndex(0); workspace.loadNamed("Pinned uptime"); return null; });
                    await(() -> ready(workspace) && "uptime".equals(workspace.state().tab));
                    assertEquals(selected,edt(() -> workspace.state().selected.get(0)));
                }
            } finally { edt(() -> { workspace.close(); evidence.closeWindow(); return null; }); }
        }
    }
}
