package tomato.gui.logging;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.DiscoveryLog;
import javax.swing.*;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.modern.FormattingTestSupport.*;

/** EDT-only Swing components; no windows, focus, clipboard, capture, or desktop operations. */
public class LoggingWorkflowTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void packetStatAndFieldActionsAreUsableAndFacetStateStaysContextual() throws Exception {
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI view=view(log);
            SwingUtilities.invokeAndWait(() -> {
                JTabbedPane tabs=tabs(view); tabs.setSelectedIndex(2);
                assertTrue(field(view,"observedOnly",JCheckBox.class).isVisible());
                field(view,"observedOnly",JCheckBox.class).doClick();
                JTable table=table(view); assertEquals(1,table.getRowCount());
                table.setRowSelectionInterval(0,0); button(view,"logging-samples-link").doClick();
                assertEquals(4,tabs.getSelectedIndex()); assertEquals(2,table(view).getRowCount());
                assertEquals("NEWTICK",combo(view,"packet").getSelectedItem());
                assertFalse(field(view,"observedOnly",JCheckBox.class).isVisible());
                assertFalse(field(view,"issuesOnly",JCheckBox.class).isVisible());
                assertTrue(combo(view,"object").getParent().isVisible());
                field(view,"changedOnly",JCheckBox.class).doClick(); assertEquals(1,table(view).getRowCount());
                combo(view,"object").setSelectedItem("777"); combo(view,"stat").setSelectedItem("1: HP_STAT");
                assertEquals(1,table(view).getRowCount());
                combo(view,"object").setSelectedItem("888"); assertEquals(0,table(view).getRowCount());
                assertTrue(named(view,"logging-counts",JLabel.class).getText().contains("No matches"));
                button(view,"logging-reset").doClick(); assertEquals(2,table(view).getRowCount());
                assertFalse(field(view,"changedOnly",JCheckBox.class).isSelected());
                named(view,"logging-search",JTextField.class).setText("HP_STAT"); assertEquals(2,table(view).getRowCount());
                table(view).setRowSelectionInterval(0,0);
                JTextArea detail=named(view,"logging-details",JTextArea.class);
                assertTrue(detail.getText().contains("900 → 800")); assertTrue(detail.getText().contains("timestamp"));
                JComboBox<?> path=named(view,"logging-field-choice",JComboBox.class);
                path.setSelectedItem("status[].stats[].statValue"); button(view,"logging-field-link").doClick();
                assertEquals(5,tabs.getSelectedIndex()); assertEquals(1,table(view).getRowCount());
                assertEquals("status[].stats[].statValue",table(view).getValueAt(table(view).getSelectedRow(),1));
                assertTrue(detail.getText().contains("Decoder definition only"));
                assertFalse(combo(view,"object").getParent().isVisible());
                tabs.setSelectedIndex(3); table(view).setRowSelectionInterval(0,0);
                button(view,"logging-samples-link").doClick();
                assertEquals(4,tabs.getSelectedIndex()); assertEquals("1: HP_STAT",combo(view,"stat").getSelectedItem());
                assertEquals("Any",combo(view,"packet").getSelectedItem()); assertEquals(2,table(view).getRowCount());
                tabs.setSelectedIndex(2); assertTrue(field(view,"observedOnly",JCheckBox.class).isSelected());
                tabs.setSelectedIndex(0); assertFalse(combo(view,"packet").getParent().isVisible());
                for (int i=0;i<tabs.getTabCount();i++) {
                    tabs.setSelectedIndex(i);
                    assertNotNull(table(view).getAccessibleContext().getAccessibleName());
                    assertNotNull(table(view).getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
                }
                assertNotNull(button(view,"logging-copy-detail"));
            });
        }
    }

    @Test public void thirdFieldSelectionSurvivesFilteringSortingAndNewDiagnosticRevision() throws Exception {
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI view=view(log); String[] selected=new String[1];
            SwingUtilities.invokeAndWait(() -> {
                tabs(view).setSelectedIndex(5); combo(view,"packet").setSelectedItem("NEWTICK");
                JTable table=table(view); table.setRowSelectionInterval(2,2); selected[0]=(String)table.getValueAt(2,1);
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(1,SortOrder.DESCENDING)));
                named(view,"logging-search",JTextField.class).setText(selected[0]);
                assertEquals(selected[0],table.getValueAt(table.getSelectedRow(),1));
                named(view,"logging-search",JTextField.class).setText("");
                assertEquals(selected[0],table.getValueAt(table.getSelectedRow(),1));
            });
            LoggingQueryTest.tick(log,700,80); SwingUtilities.invokeAndWait(view::refresh);
            await(() -> field(view,"snapshot",DiscoveryLog.Snapshot.class).events.size()==3);
            SwingUtilities.invokeAndWait(() -> {
                JTable table=table(view); assertEquals(selected[0],table.getValueAt(table.getSelectedRow(),1));
                assertEquals("NEWTICK",table.getValueAt(table.getSelectedRow(),0));
                tabs(view).setSelectedIndex(4); named(view,"logging-search",JTextField.class).setText("No match at all");
                assertEquals(0,table(view).getRowCount()); assertEquals(java.time.Instant.class,table(view).getColumnClass(0));
                assertEquals(Long.class,table(view).getColumnClass(1));
            });
        }
    }

    @Test public void frozenAAndFreshBHaveConsistentSourcesCountsIntervalsAndCollisionSafeFiles() throws Exception {
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI view=view(log); Path dir=temp.newFolder().toPath();
            DiscoveryLog.Snapshot a=field(view,"snapshot",DiscoveryLog.Snapshot.class);
            SwingUtilities.invokeAndWait(() -> {
                tabs(view).setSelectedIndex(4); field(view,"freeze",JCheckBox.class).setSelected(true);
                named(view,"logging-search",JTextField.class).setText("800");
            });
            log.clearDiagnostics(); MapInfoPacket map=new MapInfoPacket(); map.name="Nexus";
            log.observe(PacketType.MAPINFO.getIndex(),40,map,"decoded",0);
            Map<LoggingReport.Source,JsonObject> reports=new EnumMap<>(LoggingReport.Source.class);
            Set<Path> files=new HashSet<>();
            for (LoggingReport.Source source:LoggingReport.Source.values()) {
                AtomicReference<SwingWorker<Path,Void>> worker=new AtomicReference<>();
                synchronized (log) {
                    SwingUtilities.invokeAndWait(() -> {
                        worker.set(view.exportTo(dir,source)); assertNull(view.exportTo(dir,source));
                    });
                    CountDownLatch heartbeat=new CountDownLatch(1); SwingUtilities.invokeLater(heartbeat::countDown);
                    assertTrue(heartbeat.await(2,TimeUnit.SECONDS));
                    if (source==LoggingReport.Source.CURRENT) assertFalse("Fresh acquisition waits off EDT",worker.get().isDone());
                    else assertNotNull("Frozen export never acquires the current observer",worker.get().get(5,TimeUnit.SECONDS));
                }
                Path file=worker.get().get(5,TimeUnit.SECONDS); assertTrue(files.add(file));
                reports.put(source,new Gson().fromJson(new String(Files.readAllBytes(file),StandardCharsets.UTF_8),JsonObject.class));
                await(() -> !field(view,"exporting",Boolean.class));
            }
            JsonObject frozen=reports.get(LoggingReport.Source.DISPLAYED), fresh=reports.get(LoggingReport.Source.CURRENT);
            assertEquals(2,frozen.getAsJsonObject("observations").getAsJsonArray("events").size());
            assertEquals(1,fresh.getAsJsonObject("observations").getAsJsonArray("events").size());
            assertEquals(0,frozen.getAsJsonArray("reentryTrace").size()); assertEquals(1,fresh.getAsJsonArray("reentryTrace").size());
            assertEquals(LoggingReport.revision(a),frozen.getAsJsonObject("manifest").get("snapshotRevision").getAsString());
            assertNotEquals(frozen.getAsJsonObject("manifest").get("snapshotRevision"),fresh.getAsJsonObject("manifest").get("snapshotRevision"));
            for (JsonObject report:reports.values()) {
                JsonObject manifest=report.getAsJsonObject("manifest"), data=report.getAsJsonObject("observations");
                assertEquals(data.getAsJsonArray("events").size(),manifest.get("retainedEventSamples").getAsInt());
                assertEquals(data.getAsJsonArray("stats").size(),manifest.get("statCounterRows").getAsInt());
                assertEquals(data.getAsJsonArray("events").get(0).getAsJsonObject().get("timestamp"),manifest.get("retainedFromUtc"));
                assertTrue(data.get("activity").isJsonNull());
                JsonObject context=manifest.getAsJsonObject("displayContext");
                assertEquals(LoggingReport.revision(a),context.get("snapshotRevision").getAsString());
                assertEquals("800",context.getAsJsonObject("filters").get("literalSearch").getAsString());
                assertEquals(1,context.get("matchingRows").getAsInt()); assertEquals(2,context.get("availableRows").getAsInt());
                assertFalse(context.get("filtersAppliedToExport").getAsBoolean());
            }
            assertSame(a,field(view,"snapshot",DiscoveryLog.Snapshot.class));
            assertTrue(LoggingReport.preview(LoggingReport.Source.DISPLAYED,a,Collections.emptyMap()).contains("display filters are recorded, not applied"));
        }
    }

    @Test public void missingDisplayedRevisionAndWriteFailureAreActionableAndRetryable() throws Exception {
        try (DiscoveryLog log=new DiscoveryLog(null)) {
            LoggingGUI[] view=new LoggingGUI[1]; AtomicReference<SwingWorker<Path,Void>> worker=new AtomicReference<>();
            Path invalid=temp.newFile().toPath(), valid=temp.newFolder().toPath();
            SwingUtilities.invokeAndWait(() -> {
                view[0]=new LoggingGUI(log,LoggingStateTestSupport.memoryStore()); assertNull(view[0].exportTo(valid,LoggingReport.Source.DISPLAYED));
                assertTrue(field(view[0],"exportStatus",JLabel.class).getText().contains("No diagnostic revision"));
                worker.set(view[0].exportTo(invalid));
            });
            try { worker.get().get(5,TimeUnit.SECONDS); fail("Expected directory failure"); } catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
            await(() -> !field(view[0],"exporting",Boolean.class));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(field(view[0],"exportStatus",JLabel.class).getText().contains("Export failed"));
                worker.set(view[0].exportTo(valid));
            });
            assertTrue(Files.exists(worker.get().get(5,TimeUnit.SECONDS)));
            await(() -> !field(view[0],"exporting",Boolean.class));
        }
    }

    @Test public void compactLayoutKeepsTablesAndDetailsReachableWithoutNativeWindow() throws Exception {
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI view=view(log);
            SwingUtilities.invokeAndWait(() -> {
                view.setSize(680,520);
                for (int i=0;i<tabs(view).getTabCount();i++) {
                    tabs(view).setSelectedIndex(i);
                    for (int pass=0;pass<4;pass++) layout(view);
                    assertTrue("Tab viewport at tab " + i + ": " + tabs(view).getHeight(),tabs(view).getHeight()>=70);
                    JTextArea detail=named(view,"logging-details",JTextArea.class);
                    assertTrue("Full detail viewport at tab " + i + ": " + detail.getParent().getHeight()
                        + "; split=" + named(view,null,JSplitPane.class).getSize()
                        + "; tabs=" + tabs(view).getHeight()
                        + "; detail panel=" + detail.getParent().getParent().getParent().getSize(),detail.getParent().getHeight()>=55);
                }
            });
        }
    }

    @Test public void refreshKeepsKeyboardTargetsAndEvictedFacetsDoNotSilentlyBroaden() throws Exception {
        try (DiscoveryLog log=LoggingQueryTest.fixture()) {
            LoggingGUI view=view(log); JButton[] reset=new JButton[1]; ComboBoxModel<?>[] paths=new ComboBoxModel<?>[1];
            SwingUtilities.invokeAndWait(() -> {
                tabs(view).setSelectedIndex(4); combo(view,"object").setSelectedItem("777");
                table(view).setRowSelectionInterval(0,0); reset[0]=button(view,"logging-reset");
                paths[0]=named(view,"logging-field-choice",JComboBox.class).getModel();
            });
            LoggingQueryTest.tick(log,700,80); SwingUtilities.invokeAndWait(view::refresh);
            await(() -> field(view,"snapshot",DiscoveryLog.Snapshot.class).events.size()==3);
            SwingUtilities.invokeAndWait(() -> {
                assertSame(reset[0],button(view,"logging-reset"));
                assertSame(paths[0],named(view,"logging-field-choice",JComboBox.class).getModel());
            });
            log.clearDiagnostics(); SwingUtilities.invokeAndWait(view::refresh);
            await(() -> field(view,"snapshot",DiscoveryLog.Snapshot.class).events.isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("777",combo(view,"object").getSelectedItem()); assertEquals(0,table(view).getRowCount());
                button(view,"logging-reset").doClick(); assertEquals("Any",combo(view,"object").getSelectedItem());
            });
        }
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (java.awt.Component child:parent.getComponents()) if (child instanceof Container) layout((Container)child);
    }

    private static LoggingGUI view(DiscoveryLog log) throws Exception {
        LoggingGUI[] result=new LoggingGUI[1]; SwingUtilities.invokeAndWait(() -> { result[0]=new LoggingGUI(log,LoggingStateTestSupport.memoryStore()); result[0].refresh(); });
        await(() -> field(result[0],"snapshot",DiscoveryLog.Snapshot.class)!=null); return result[0];
    }
    private static JTabbedPane tabs(LoggingGUI view) { return named(view,null,JTabbedPane.class); }
    private static JTable table(LoggingGUI view) { return named((Container)tabs(view).getSelectedComponent(),null,JTable.class); }
    private static JButton button(LoggingGUI view,String name) { return named(view,name,JButton.class); }
    private static JComboBox<?> combo(LoggingGUI view,String name) { return named(view,"logging-facet-"+name,JComboBox.class); }
}
