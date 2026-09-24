package tomato.gui.security;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.*;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;

public class InspectArchiveNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void actualInspectFactoryKeepsExactSessionRosterAndReenablesCachedNativeControls() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); List<String> sources = ActivityNativeFixtures.seed(root); Memory memory = new Memory();
        try (SessionStore store = new SessionStore(root,true,"native-reader"); DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSaving(false);
            SecurityGUI live = edt(() -> new SecurityGUI(log));
            ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace = edt(() -> SecurityGUI.workspace(store,live,scratch,memory.states));
            JComponent shell = edt(() -> shell(workspace,2));
            try {
                edt(() -> { evidence.show(shell,"Inspect live default",1240,800,13); assertFalse(workspace.state().archive); return null; });
                evidence.settle(); edt(() -> { evidence.capture("inspect-live-default"); workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 150);
                for (int session = 0; session < sources.size(); session++) {
                    String source = sources.get(session); final int expectedPlayer = session;
                    edt(() -> {
                        ActivityQueries.Filters exact = new ActivityQueries.Filters(); exact.visitId = "shared-visit-1"; exact.visitSession = source;
                        workspace.changeQuery(workspace.state().query.withFacets(exact)); return null;
                    });
                    await(() -> ready(workspace) && workspace.displayedPage().matches == 1);
                    edt(() -> { named(workspace,"saved-activity-table",JTable.class).setRowSelectionInterval(0,0); return null; });
                    await(() -> savedRoster(workspace) != null);
                    ParsePanelGUI cached = edt(() -> savedRoster(workspace));
                    edt(() -> {
                        JTable roster = find(cached,JTable.class,c -> true); assertEquals(1,roster.getRowCount());
                        assertTrue(roster.getValueAt(0,0).toString().contains("Native player " + expectedPlayer));
                        assertEquals(source,workspace.state().selected.get(0).session); return null;
                    });
                }
                ParsePanelGUI cached = edt(() -> savedRoster(workspace));
                JComboBox<?> disabled = edt(() -> named(cached,"inspect-facet-0",JComboBox.class));
                JTable retired = edt(() -> named(workspace,"saved-activity-table",JTable.class));
                edt(() -> { disabled.setEnabled(false); workspace.refresh(); return null; });
                await(() -> ready(workspace) && savedRoster(workspace) == cached && named(workspace,"saved-activity-table",JTable.class) != retired);
                edt(() -> { assertFalse(retired.isEnabled()); assertFalse(disabled.isEnabled());
                    for (Component child : disabled.getComponents()) if (child instanceof AbstractButton) assertFalse(child.isEnabled());
                    disabled.setEnabled(true); button(workspace,"Current live view").doClick(); workspace.showSaved(); return null; });
                await(() -> ready(workspace) && savedRoster(workspace) == cached);
                matrix(evidence,layouts,shell,"inspect-exact-cached",() -> ready(workspace) && savedRoster(workspace) != null,() -> {
                    ParsePanelGUI roster = savedRoster(workspace);
                    assertTrue(named(roster,"inspect-roster-search",JTextField.class).isEnabled());
                    assertTrue(named(roster,"inspect-facet-0",JComboBox.class).isEnabled());
                    tableRows(find(roster,JTable.class,c -> true));
                    completeButton(button(roster,"Reset display filters")); completeButton(button(roster,"Actions…"));
                    archiveControls(workspace,"inspect","saved-activity-table",null);
                });
                String preview = preview(workspace,evidence,"inspect-linked-preview");
                assertTrue(preview.contains(sources.get(1))); assertTrue(preview.contains("8 linked Timeline events"));
            } finally { edt(() -> { workspace.close(); evidence.closeWindow(); ParsePanelGUI.clear(); return null; }); }
        }
    }

    private static ParsePanelGUI savedRoster(ArchiveWorkspace<?,?,?> workspace) {
        JComponent archive = ActivityArchiveUiTest.named(workspace,JComponent.class,"activity-archive-runs");
        return archive == null ? null : ActivityArchiveUiTest.named(archive,ParsePanelGUI.class,null);
    }
}
