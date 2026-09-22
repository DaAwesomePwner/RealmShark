package tomato.gui.logging;

import com.google.gson.*;
import java.awt.Container;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.TableColumn;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.ViewStateStore;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.modern.FormattingTestSupport.*;

public class LoggingViewStateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String KEY="ux.archive.logging-live";

    @Test public void recreateRestoresIndependentTabsQueriesSortColumnsAndExactSelectionAndNamedActions() throws Exception {
        LoggingStateTestSupport.Memory memory=new LoggingStateTestSupport.Memory();
        memory.values.put("ux.archive.chat","other module bytes");
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI first=view(log,memory.store); String[] selectedKey=new String[1];
            SwingUtilities.invokeAndWait(() -> {
                tabs(first).setSelectedIndex(4); search(first).setText("HP_STAT");
                combo(first,"object").setSelectedItem("777"); combo(first,"area").setSelectedItem("0");
                field(first,"changedOnly",JCheckBox.class).doClick();
                table(first).getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4,SortOrder.DESCENDING)));
                table(first).moveColumn(2,0); TableColumn packet=table(first).getColumnModel().getColumn(0);
                packet.setPreferredWidth(333); packet.setWidth(333); table(first).setRowSelectionInterval(0,0);
                selectedKey[0]=first.captureViewState().tabs.get("events").selection.key;
                field(first,"split",JSplitPane.class).setDividerLocation(180);
                state(first).names.getEditor().setItem("HP evidence"); state(first).saveNamed.doClick();
                tabs(first).setSelectedIndex(2); search(first).setText("NEWTICK");
                field(first,"observedOnly",JCheckBox.class).doClick();
                state(first).save();
            });
            LoggingGUI second=view(log,memory.store);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(2,tabs(second).getSelectedIndex()); assertEquals("NEWTICK",search(second).getText());
                assertTrue(field(second,"observedOnly",JCheckBox.class).isSelected());
                tabs(second).setSelectedIndex(4);
                assertEquals("HP_STAT",search(second).getText()); assertEquals(1,table(second).getRowCount());
                assertEquals("777",combo(second,"object").getSelectedItem());
                assertEquals("0",combo(second,"area").getSelectedItem());
                assertEquals(4,table(second).getRowSorter().getSortKeys().get(0).getColumn());
                assertEquals(2,table(second).getColumnModel().getColumn(0).getModelIndex());
                assertEquals(333,table(second).getColumnModel().getColumn(0).getWidth());
                assertEquals(selectedKey[0],second.captureViewState().tabs.get("events").selection.key);
                assertEquals(180,field(second,"split",JSplitPane.class).getDividerLocation());
                search(second).setText("different draft");
                state(second).names.setSelectedItem("HP evidence"); state(second).loadNamed.doClick();
                assertEquals("HP_STAT",search(second).getText()); assertEquals(4,tabs(second).getSelectedIndex());
                state(second).deleteNamed.doClick(); assertEquals(0,state(second).names.getItemCount());
                assertEquals("other module bytes",memory.values.get("ux.archive.chat"));
            });
        }
    }

    @Test public void realPreferencesRestartRestoresNamedIntentButNeverFrozenDataOrCollectionControls() throws Exception {
        Path path=temp.getRoot().toPath().resolve("preferences.properties");
        PreferencesStore preferences=new PreferencesStore(path); preferences.preload();
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI first=view(log,ViewStateStore.preferences(preferences));
            SwingUtilities.invokeAndWait(() -> {
                tabs(first).setSelectedIndex(4); search(first).setText("800");
                field(first,"freeze",JCheckBox.class).setSelected(true);
                state(first).names.getEditor().setItem("Frozen investigation intent"); state(first).saveNamed.doClick();
            });
            assertTrue(preferences.flush().toCompletableFuture().get(5,TimeUnit.SECONDS).isSuccess());
            String encoded=preferences.getProperty(KEY);
            assertFalse(encoded.contains("statChanges")); assertFalse(encoded.contains("displayPaused"));
            preferences.shutdown(5,TimeUnit.SECONDS,Assert::fail);
            log.clearDiagnostics(); LoggingQueryTest.tick(log,700,80); log.setEnabled(false); log.setSaving(true); log.setSampleMillis(1000);
            PreferencesStore restarted=new PreferencesStore(path); assertTrue(restarted.preload().isSuccess());
            try {
                LoggingGUI second=view(log,ViewStateStore.preferences(restarted));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("800",search(second).getText()); assertFalse(field(second,"freeze",JCheckBox.class).isSelected());
                    assertEquals(1,state(second).names.getItemCount());
                    assertEquals(1,field(second,"snapshot",DiscoveryLog.Snapshot.class).events.size());
                    assertEquals(0,table(second).getRowCount()); // The frozen 800 sample was not restored.
                    assertFalse(log.isEnabled()); assertTrue(log.isSaving());
                    assertEquals(1000,field(second,"snapshot",DiscoveryLog.Snapshot.class).sampleMillis);
                    state(second).names.setSelectedItem("Frozen investigation intent"); state(second).loadNamed.doClick();
                    assertFalse(field(second,"freeze",JCheckBox.class).isSelected());
                    assertFalse(log.isEnabled()); assertTrue(log.isSaving());
                });
            } finally { restarted.shutdown(5,TimeUnit.SECONDS,Assert::fail); }
        }
    }

    @Test public void oldCaptureObjectFacetAndSelectionNeverAttachToNewCaptureAndAreaMustMatch() throws Exception {
        LoggingStateTestSupport.Memory memory=new LoggingStateTestSupport.Memory();
        try (DiscoveryLog old=LoggingQueryTest.fixture(); DiscoveryLog current=LoggingQueryTest.fixture()) {
            LoggingGUI first=view(old,memory.store);
            SwingUtilities.invokeAndWait(() -> {
                tabs(first).setSelectedIndex(4); combo(first,"object").setSelectedItem("777");
                table(first).setRowSelectionInterval(0,0); state(first).save();
            });
            String original=memory.values.get(KEY);
            LoggingGUI next=view(current,memory.store);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0,table(next).getRowCount()); assertEquals("777",combo(next,"object").getSelectedItem());
                assertNull(next.captureViewState().tabs.get("events").selection);
                combo(next,"object").setSelectedItem("Any"); assertEquals(2,table(next).getRowCount());
                assertEquals(-1,table(next).getSelectedRow());
            });
            SwingUtilities.invokeAndWait(() -> {});
            JsonObject doc=JsonParser.parseString(original).getAsJsonObject();
            doc.getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets")
                .getAsJsonObject("tabs").getAsJsonObject("events").getAsJsonObject("selection").addProperty("area",99);
            memory.values.put(KEY,doc.toString());
            LoggingGUI wrongArea=view(old,memory.store);
            SwingUtilities.invokeAndWait(() -> assertEquals(-1,table(wrongArea).getSelectedRow()));
        }
    }

    @Test public void futureDocumentOrNamedPayloadIsPreservedUntilExplicitReset() throws Exception {
        for (boolean nested:new boolean[]{false,true}) {
            LoggingStateTestSupport.Memory memory=new LoggingStateTestSupport.Memory();
            try (DiscoveryLog log=LoggingQueryTest.fixture()) {
                LoggingGUI seed=view(log,memory.store);
                SwingUtilities.invokeAndWait(() -> { state(seed).save(); state(seed).saveNamed("future"); });
                JsonObject doc=JsonParser.parseString(memory.values.get(KEY)).getAsJsonObject();
                if (nested) doc.getAsJsonObject("named").getAsJsonObject("future").getAsJsonObject("query").getAsJsonObject("facets").addProperty("version",99);
                else doc.addProperty("version",99);
                String original=doc.toString(); memory.values.put(KEY,original);
                LoggingGUI view=view(log,memory.store);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(state(view).status.getText().contains("Reset saved"));
                    search(view).setText("working draft"); state(view).saveNamed("replacement");
                    assertEquals(original,memory.values.get(KEY)); assertEquals("working draft",search(view).getText());
                    state(view).reset.doClick(); assertEquals("",search(view).getText());
                    assertEquals(0,state(view).names.getItemCount());
                });
                SwingUtilities.invokeAndWait(() -> {});
                assertNotEquals(original,memory.values.get(KEY));
            }
        }
    }

    @Test public void staleDurabilityCallbacksCannotClearNewerFailureAndRetryWritesLatestIntent() throws Exception {
        LoggingStateTestSupport.Memory memory=new LoggingStateTestSupport.Memory(); memory.delayed=true;
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI view=view(log,memory.store);
            AtomicReference<CompletableFuture<PreferencesStore.SaveResult>> old=new AtomicReference<>(), latest=new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                search(view).setText("older"); old.set(state(view).save().toCompletableFuture());
                search(view).setText("latest"); latest.set(state(view).save().toCompletableFuture());
            });
            latest.get().complete(PreferencesStore.SaveResult.failed(2,new java.io.IOException("synthetic disk failure")));
            await(() -> state(view).retry.isEnabled());
            old.get().complete(PreferencesStore.SaveResult.saved(1));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(state(view).status.getText().contains("failed")); assertEquals("latest",search(view).getText());
                state(view).retry.doClick();
            });
            CompletableFuture<PreferencesStore.SaveResult> retry=memory.completions.get(memory.completions.size()-1);
            assertNotSame(latest.get(),retry); retry.complete(PreferencesStore.SaveResult.saved(3));
            await(() -> state(view).status.getText().equals("Logging view saved."));
            assertTrue(memory.values.get(KEY).contains("latest"));
            SwingUtilities.invokeAndWait(() -> {
                search(view).setText("detach"); latest.set(state(view).save().toCompletableFuture()); state(view).detach();
            });
            latest.get().complete(PreferencesStore.SaveResult.failed(4,new java.io.IOException("late failure")));
            SwingUtilities.invokeAndWait(() -> assertFalse(state(view).retry.isEnabled()));
        }
    }

    @Test public void realDiskFailureKeepsMemoryAcrossRecreationAndRetryBecomesDurable() throws Exception {
        Path directory=temp.getRoot().toPath().resolve("missing-parent"), path=directory.resolve("prefs.properties");
        PreferencesStore preferences=new PreferencesStore(path); assertTrue(preferences.preload().isSuccess());
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI first=view(log,ViewStateStore.preferences(preferences));
            AtomicReference<CompletionStage<PreferencesStore.SaveResult>> saving=new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> { search(first).setText("retained after disk failure"); saving.set(state(first).save()); });
            assertFalse(saving.get().toCompletableFuture().get(5,TimeUnit.SECONDS).isSuccess());
            await(() -> state(first).retry.isEnabled());
            LoggingGUI recreated=view(log,ViewStateStore.preferences(preferences));
            SwingUtilities.invokeAndWait(() -> assertEquals("retained after disk failure",search(recreated).getText()));
            Files.createDirectories(directory);
            SwingUtilities.invokeAndWait(() -> { assertTrue(state(recreated).retry.isEnabled()); state(recreated).retry.doClick(); });
            assertTrue(preferences.flush().toCompletableFuture().get(5,TimeUnit.SECONDS).isSuccess());
            await(() -> !state(recreated).retry.isEnabled());
            assertTrue(Files.exists(path));
        } finally { preferences.shutdown(5,TimeUnit.SECONDS,message -> {}); }
    }

    private static LoggingGUI view(DiscoveryLog log,ViewStateStore store) throws Exception {
        LoggingGUI[] view=new LoggingGUI[1]; SwingUtilities.invokeAndWait(() -> { view[0]=new LoggingGUI(log,store); view[0].refresh(); });
        await(() -> field(view[0],"snapshot",DiscoveryLog.Snapshot.class)!=null); return view[0];
    }
    private static LoggingViewState state(LoggingGUI view) { return field(view,"viewState",LoggingViewState.class); }
    private static JTabbedPane tabs(LoggingGUI view) { return field(view,"tabs",JTabbedPane.class); }
    private static JTextField search(LoggingGUI view) { return field(view,"search",JTextField.class); }
    private static JTable table(LoggingGUI view) { return named((Container)tabs(view).getSelectedComponent(),null,JTable.class); }
    private static JComboBox<?> combo(LoggingGUI view,String key) { return field(view,key+"Facet",JComboBox.class); }
}
