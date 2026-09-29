package tomato.gui.dps;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class DungeonListTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void keyboardSelectionAndExportChecksSurviveHistoryRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            data.dpsData.add(encounter("First")); data.dpsData.add(encounter("Second"));
            DpsGUI dps = new DpsGUI(data); dps.setIndex(0);
            DungeonListGUI chooser = new DungeonListGUI(dps, data, null);
            chooser.refreshEncounters();   // the Recordings tab reads on first show; this one is never shown
            JTable table = table(chooser);
            await(() -> table.getRowCount() == 3);
            assertEquals("First", dungeon(table, table.getSelectedRow()));
            table.getActionMap().get("toggle-export").actionPerformed(new ActionEvent(table, 0, "SPACE"));
            assertEquals(Boolean.TRUE, table.getValueAt(row(table, "First"), 0));
            table.setRowSelectionInterval(row(table, "Second"), row(table, "Second"));
            assertEquals("Recordings is its own tab; opening is explicit", 0, dps.getIndex());
            chooser.open();
            assertEquals(1, dps.getIndex());
            data.dpsData.add(encounter("Third")); DpsGUI.updateMapPacket(data); chooser.refreshEncounters();
            await(() -> table.getRowCount() == 4);
            assertEquals("Second", dungeon(table, table.getSelectedRow()));
            assertEquals(Boolean.TRUE, table.getValueAt(row(table, "First"), 0));
            table.setFont(table.getFont().deriveFont(30f));
            assertTrue(table.getRowHeight() > table.getFontMetrics(table.getFont()).getHeight());
            int live = row(table, "Live"); table.setRowSelectionInterval(live, live);
            assertEquals("Recordings is its own tab; opening is explicit", 1, dps.getIndex());
            chooser.open(); assertEquals(-1, dps.getIndex());
            assertFalse(table.isCellEditable(live, 0));
        });
    }

    @Test public void filesRunOffEdtAndExportUsesCapturedChecksAndOptions() throws Exception {
        File folder = temporary.newFolder("exports");
        TomatoData data = new TomatoData();
        BlockingMap map = new BlockingMap(); map.name = map.displayName = "Saved";
        DpsData saved = encounter("Saved"); saved.map = map;
        saved.debugPackets = new ArrayList<>(); saved.debugPackets.add(new MapInfoPacket());
        data.dpsData.add(saved);
        DungeonListGUI[] chooser = new DungeonListGUI[1];
        SwingWorker<?, ?>[] job = new SwingWorker<?, ?>[1];
        CountDownLatch exported = new CountDownLatch(1), imported = new CountDownLatch(1);
        BlockingMap.started = new CountDownLatch(1); BlockingMap.release = new CountDownLatch(1);
        BlockingMap.wroteOffEdt.set(false); BlockingMap.readRan.set(false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                chooser[0] = new DungeonListGUI(new DpsGUI(data), data, null);
                chooser[0].refreshEncounters();   // the Recordings tab reads on first show; this one is never shown
                await(() -> table(chooser[0]).getRowCount() == 2);
                table(chooser[0]).setValueAt(true, row(table(chooser[0]), "Saved"), 0);
                job[0] = chooser[0].exportFiles(folder, false);
                onDone(job[0], exported);
            });
            assertTrue(BlockingMap.started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                // File serialization is blocked, yet selection and encounter history remain usable.
                table(chooser[0]).setValueAt(false, row(table(chooser[0]), "Saved"), 0);
                data.dpsData.clear(); data.dpsData.add(encounter("Replacement"));
                DpsGUI.updateMapPacket(data);
                chooser[0].refreshEncounters();
            });
            BlockingMap.release.countDown();
            assertEquals(1, job[0].get(5, TimeUnit.SECONDS));
            assertTrue(exported.await(5, TimeUnit.SECONDS));
            assertTrue(BlockingMap.wroteOffEdt.get());
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".dps"));
            assertNotNull(files); assertEquals(1, files.length);
            assertTrue(files[0].getName().startsWith("Saved "));
            try (ObjectInputStream exportedBytes = new ObjectInputStream(new FileInputStream(files[0]))) {   // the test's own bytes
                DpsData written = (DpsData) exportedBytes.readObject();
                assertEquals("Saved", written.map.name); assertNull("Exported without the debug packet log", written.debugPackets);
            }
            assertEquals(1, saved.debugPackets.size());
            // The export holds this test's class: the import's filter rejects it by name before any of its code runs.
            BlockingMap.readRan.set(false);   // the unfiltered read above ran it
            CountDownLatch rejected = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> { job[0] = chooser[0].importFile(files[0]); onDone(job[0], rejected); });
            try { job[0].get(5, TimeUnit.SECONDS); fail("A class outside RealmShark's recording graph was read"); }
            catch (ExecutionException expected) {
                assertEquals("This file contains data RealmShark does not read: " + BlockingMap.class.getName(), expected.getCause().getMessage());
            }
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertFalse(BlockingMap.readRan.get());
            // Off-EDT probe: the same encounter with RealmShark's own map class, read on the reader thread.
            File plain = new File(folder, "Plain.dps");
            DpsData copy = saved.getSaveFile(false); MapInfoPacket plainMap = new MapInfoPacket(); plainMap.name = plainMap.displayName = "Saved"; copy.map = plainMap;
            try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(plain))) { output.writeObject(copy); }
            java.util.List<String> readers = new CopyOnWriteArrayList<>();
            EncounterImport.beforeRead = () -> readers.add((SwingUtilities.isEventDispatchThread() ? "EDT " : "") + Thread.currentThread().getName());
            SwingUtilities.invokeAndWait(() -> {
                job[0] = chooser[0].importFile(plain);
                onDone(job[0], imported);
            });
            DpsData loaded = (DpsData)job[0].get(5, TimeUnit.SECONDS);
            assertTrue(imported.await(5, TimeUnit.SECONDS));
            assertEquals(java.util.List.of(EncounterImport.THREAD), readers);
            assertEquals("Saved", loaded.map.name); assertNull(loaded.debugPackets);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Imports no longer mutate the capture-owned history list", 1, data.dpsData.size());
                await(() -> table(chooser[0]).getRowCount() == 3);
                assertEquals(3, table(chooser[0]).getRowCount());
                assertEquals("Live", dungeon(table(chooser[0]), table(chooser[0]).getSelectedRow()));
            });
        } finally { BlockingMap.release.countDown(); EncounterImport.beforeRead = () -> { }; }
    }

    private static void onDone(SwingWorker<?, ?> worker, CountDownLatch done) {
        worker.addPropertyChangeListener(e -> {
            if ("state".equals(e.getPropertyName()) && e.getNewValue() == SwingWorker.StateValue.DONE) done.countDown();
        });
    }

    @Test public void collidingBatchAndRepeatedExportKeepDebugRichRecordings() throws Exception {
        File folder = temporary.newFolder("repeated");
        TomatoData data = new TomatoData();
        DpsData first = encounter("Same encounter"), second = encounter("Same encounter");
        first.debugPackets = new ArrayList<>(); first.debugPackets.add(new MapInfoPacket());
        second.debugPackets = new ArrayList<>(); second.debugPackets.add(new MapInfoPacket());
        second.debugPackets.add(new MapInfoPacket());
        data.dpsData.add(first); data.dpsData.add(second);
        DungeonListGUI[] chooser = new DungeonListGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            chooser[0] = new DungeonListGUI(new DpsGUI(data), data, null);
            chooser[0].refreshEncounters();   // the Recordings tab reads on first show; this one is never shown
            await(() -> table(chooser[0]).getRowCount() == 3);
            for (int row = 0; row < table(chooser[0]).getRowCount(); row++) if (table(chooser[0]).isCellEditable(row, 0)) table(chooser[0]).setValueAt(true, row, 0);
        });
        export(chooser[0], folder, true);
        Map<File, byte[]> originals = new HashMap<>();
        for (File file : folder.listFiles()) originals.put(file, Files.readAllBytes(file.toPath()));
        assertEquals(2, originals.size());

        export(chooser[0], folder, false);

        assertEquals(4, folder.listFiles().length);
        Set<Integer> debugSizes = new HashSet<>();
        for (File file : folder.listFiles()) {
            try (ObjectInputStream input = new ObjectInputStream(new FileInputStream(file))) {
                DpsData saved = (DpsData) input.readObject();
                if (originals.containsKey(file)) {
                    assertArrayEquals(originals.get(file), Files.readAllBytes(file.toPath()));
                    debugSizes.add(saved.debugPackets.size());
                } else assertNull(saved.debugPackets);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), debugSizes);
        assertEquals(1, first.debugPackets.size()); assertEquals(2, second.debugPackets.size());
    }

    private static void export(DungeonListGUI chooser, File folder, boolean debug) throws Exception {
        SwingWorker<?, ?>[] job = new SwingWorker<?, ?>[1];
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> { job[0] = chooser.exportFiles(folder, debug); onDone(job[0], done); });
        assertEquals(2, job[0].get(5, TimeUnit.SECONDS));
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
    private static DpsData encounter(String name) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name;
        return new DpsData(map, new HashMap<>(), new ArrayList<>(), 1000, 1000, null);
    }
    private static JTable table(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable) return (JTable)child;
            if (child instanceof Container) { JTable table = table((Container)child); if (table != null) return table; }
        }
        return null;
    }
    /** The Dungeon (model column 2) of view row {@code row}: the view orders the columns and Simple hides some. */
    private static Object dungeon(JTable table, int row) { return table.getModel().getValueAt(table.convertRowIndexToModel(row), 2); }
    private static int row(JTable table, String dungeon) {
        for (int row = 0; row < table.getRowCount(); row++) if (dungeon.equals(dungeon(table, row))) return row;
        throw new AssertionError("Missing encounter " + dungeon);
    }
    private static final class BlockingMap extends MapInfoPacket {
        static CountDownLatch started, release;
        static final AtomicBoolean wroteOffEdt = new AtomicBoolean(), readRan = new AtomicBoolean();
        private void writeObject(ObjectOutputStream output) throws IOException {
            wroteOffEdt.set(!SwingUtilities.isEventDispatchThread()); started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Export was not released"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            output.defaultWriteObject();
        }
        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            readRan.set(true); input.defaultReadObject();
        }
    }
}
