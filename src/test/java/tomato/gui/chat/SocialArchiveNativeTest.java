package tomato.gui.chat;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.TomatoData;
import tomato.gui.history.*;
import tomato.gui.keypop.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.realmshark.AlertRules;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;

public class SocialArchiveNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();
    private static final LocalDateTime BASE = LocalDateTime.of(2026,9,22,12,0);

    @Test @SuppressWarnings("unchecked") public void realChatFactoryPagesGlobalMatchesAndRetainsNamedStateThroughSaveAndExportErrors() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath();
        for (int session = 0; session < 2; session++) try (SessionStore source = new SessionStore(root,true,"native-chat")) {
            for (int row = 0; row < 700; row++) source.append("chat", new ChatMessage(BASE.plusSeconds(session * 1000L + row),
                row < 600 ? ChatMessage.Channel.GUILD : ChatMessage.Channel.WORLD, "Ann", "", "Ann", "Needle " + session + ":" + row, ""));
            source.flush();
        }
        Memory memory = new Memory();
        try (SessionStore store = new SessionStore(root,true,"native-reader")) {
            ChatGUI live = edt(() -> new ChatGUI(new TomatoData(),new ChatFilters(),false,
                new AlertRules(key -> null,(key,value) -> { throw new AssertionError("No alert-rule write"); }), sound -> fail("Archive browsing must be silent")));
            ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> workspace = edt(() ->
                (ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort>)live.workspace(store,scratch,memory.states));
            JComponent shell = edt(() -> shell(workspace,0));
            try {
                edt(() -> { evidence.show(shell,"Chat live default",1240,800,13); assertFalse(workspace.state().archive); return null; });
                evidence.settle(); edt(() -> { evidence.capture("chat-live-default"); workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 1400);
                edt(() -> {
                    named(workspace,"chat-history-search",JTextField.class).setText("Needle");
                    named(workspace,"chat-history-search",JTextField.class).postActionEvent(); return null;
                });
                await(() -> ready(workspace));
                edt(() -> { named(workspace,"chat-archive-channel",JComboBox.class).setSelectedItem(ChatMessage.Channel.GUILD); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 1200);
                edt(() -> { workspace.selectPage(1); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().page == 1);
                edt(() -> {
                    assertEquals(200,workspace.displayedPage().rows.size());
                    JTable table = named(workspace,"chat-archive-messages",JTable.class); table.setRowSelectionInterval(2,2);
                    find(workspace,JComboBox.class,c -> "Column preset".equals(c.getAccessibleContext().getAccessibleName())).setSelectedItem("Conversation");
                    assertEquals(4,table.getColumnCount()); return null;
                });
                ViewState<ChatArchiveClient.Facets,ChatArchiveClient.Sort> saved = edt(workspace::state);
                edt(() -> workspace.saveNamed("Guild page")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                matrix(evidence,layouts,shell,"chat-all-filtered",() -> ready(workspace),() -> {
                    assertEquals(1200,workspace.displayedPage().matches); assertEquals(1,workspace.state().page);
                    assertTrue(named(workspace,"chat-archive-detail",JTextArea.class).getText().contains("Needle"));
                    archiveControls(workspace,"chat","chat-archive-messages","chat-archive-detail");
                });
                edt(() -> { workspace.changeQuery(ChatArchiveClient.query().withScope(SessionStore.ALL).withText("absent literal [query]")); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 0);
                edt(() -> { workspace.loadNamed("Guild page"); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 1200);
                assertEquals(saved.query,edt(() -> workspace.state().query)); assertEquals(saved.selected,edt(() -> workspace.state().selected));
                memory.failSaves = true;
                assertFalse(edt(() -> workspace.saveNamed("Retry guild view")).toCompletableFuture().get(5,TimeUnit.SECONDS).isSuccess());
                await(() -> textPresent(workspace,"save failed"));
                matrix(evidence,layouts,shell,"chat-save-error",() -> ready(workspace),() -> {
                    assertEquals(1200,workspace.displayedPage().matches); assertTrue(textPresent(workspace,"save failed"));
                    archiveControls(workspace,"chat","chat-archive-messages","chat-archive-detail");
                });
                memory.failSaves = false; assertTrue(edt(() -> workspace.saveNamed("Retry guild view")).toCompletableFuture().get(5,TimeUnit.SECONDS).isSuccess());
                failExport(workspace);
                matrix(evidence,layouts,shell,"chat-export-error",() -> ready(workspace),() -> {
                    assertTrue(textPresent(workspace,"Export failed:")); archiveControls(workspace,"chat","chat-archive-messages","chat-archive-detail");
                });
                Path file = edt(() -> workspace.exportTo(output,"guild",ExportSelection.all(),ArchiveExport.Format.JSON)).get(15,TimeUnit.SECONDS);
                assertEquals(1200,json(file).getAsJsonArray("rows").size());
            } finally { edt(() -> { workspace.close(); evidence.closeWindow(); return null; }); }
        }
    }

    @Test @SuppressWarnings("unchecked") public void realKeyPopFactoryKeepsExactPlayerPagingSummaryDenominatorsAndSavedTabs() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(), output = temp.newFolder().toPath();
        for (int session = 0; session < 2; session++) try (SessionStore source = new SessionStore(root,true,"native-keypops")) {
            KeyPopNativeFixtures.seed(source,BASE.toInstant(ZoneOffset.UTC),session);
            source.flush();
        }
        Memory memory = new Memory();
        try (SessionStore store = new SessionStore(root,true,"native-reader")) {
            ArchiveWorkspace<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> workspace = edt(() ->
                (ArchiveWorkspace<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort>)new KeypopGUI().workspace(store,scratch,memory.states));
            JComponent shell = edt(() -> shell(workspace,1));
            try {
                edt(() -> { evidence.show(shell,"Key-pops live default",1240,800,13); assertFalse(workspace.state().archive); return null; });
                evidence.settle(); edt(() -> { evidence.capture("keypops-live-default"); workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 1120);
                edt(() -> { JTextField player = named(workspace,"keypop-archive-player",JTextField.class); player.setText("Ann"); player.postActionEvent(); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 1100);
                edt(() -> { workspace.selectPage(1); return null; }); await(() -> ready(workspace) && workspace.displayedPage().page == 1);
                edt(() -> { assertEquals(100,workspace.displayedPage().rows.size()); named(workspace,"keypop-archive-rows",JTable.class).setRowSelectionInterval(0,0); return null; });
                matrix(evidence,layouts,shell,"keypops-all-filtered",() -> ready(workspace),() -> {
                    assertEquals(1100,workspace.displayedPage().counts.get("events").value);
                    archiveControls(workspace,"keypops","keypop-archive-rows","keypop-archive-detail");
                });
                edt(() -> { named(workspace,"keypop-archive-tabs",JTabbedPane.class).setSelectedIndex(1); return null; });
                await(() -> ready(workspace) && workspace.state().query.facets().mode == KeyPopArchiveClient.Mode.BY_PLAYER);
                edt(() -> { assertEquals(1,workspace.displayedPage().matches); assertEquals(1100,workspace.displayedPage().rows.get(0).value.pops);
                    named(workspace,"keypop-archive-rows",JTable.class).setRowSelectionInterval(0,0); return null; });
                edt(() -> workspace.saveNamed("Ann contributions")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                matrix(evidence,layouts,shell,"keypops-summary",() -> ready(workspace),() -> {
                    assertEquals(1100,workspace.displayedPage().rows.get(0).value.matchingEvents);
                    archiveControls(workspace,"keypops","keypop-archive-rows","keypop-archive-detail");
                });
                edt(() -> { named(workspace,"keypop-archive-tabs",JTabbedPane.class).setSelectedIndex(2); return null; });
                await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
                edt(() -> { workspace.loadNamed("Ann contributions"); return null; });
                await(() -> ready(workspace) && workspace.state().query.facets().mode == KeyPopArchiveClient.Mode.BY_PLAYER);
                Path file = edt(() -> workspace.exportTo(output,"contributors",ExportSelection.all(),ArchiveExport.Format.JSON)).get(15,TimeUnit.SECONDS);
                assertEquals(1,json(file).getAsJsonArray("rows").size());
                assertEquals(1100,json(file).getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("value").get("matchingEvents").getAsLong());
            } finally { edt(() -> { workspace.close(); evidence.closeWindow(); return null; }); }
        }
    }
}
