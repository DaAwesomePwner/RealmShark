package tomato.gui.dps;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.DpsData;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class DpsExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void existingFilesAndDirectoriesAreNeverOverwritten() throws Exception {
        Path folder = temporary.newFolder().toPath();
        byte[] marker = {1, 2, 3, 4};
        Files.write(folder.resolve("Encounter.dps"), marker);
        Files.createDirectory(folder.resolve("Encounter (2).dps"));

        Path exported = DpsExport.write(folder, "Encounter", encounter("New"));

        assertEquals("Encounter (3).dps", exported.getFileName().toString());
        assertArrayEquals(marker, Files.readAllBytes(folder.resolve("Encounter.dps")));
        assertTrue(Files.isDirectory(folder.resolve("Encounter (2).dps")));
        assertEquals("New", read(exported).map.name);
        assertEquals(3, folder.toFile().list().length);
    }

    @Test public void serializationFailurePreservesReadableRecordingAndRemovesTemp() throws Exception {
        Path folder = temporary.newFolder().toPath();
        DpsData original = encounter("Debug-rich");
        original.debugPackets = new ArrayList<>(); original.debugPackets.add(new MapInfoPacket());
        Path existing = DpsExport.write(folder, "Encounter", original);
        byte[] before = Files.readAllBytes(existing);
        DpsData failing = encounter("Failed"); failing.map = new FailingMap();

        try {
            DpsExport.write(folder, "Encounter", failing);
            fail("The serialization error must be reported");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Serialization failed")); }

        assertArrayEquals(before, Files.readAllBytes(existing));
        assertEquals(1, read(existing).debugPackets.size());
        assertEquals("No failed destination or temporary file remains", 1, folder.toFile().list().length);
    }

    @Test public void failedFinalCopyRemovesOnlyItsOwnPartialDestination() throws Exception {
        Path folder = temporary.newFolder().toPath();
        Path existing = DpsExport.write(folder, "Encounter", encounter("Preserved"));
        byte[] before = Files.readAllBytes(existing);

        try {
            DpsExport.publish(folder, "Encounter", output -> {
                output.write(new byte[]{4, 5, 6});
                throw new IOException("Final copy failed");
            });
            fail("The failed copy must be reported");
        } catch (IOException expected) { assertEquals("Final copy failed", expected.getMessage()); }

        assertArrayEquals(before, Files.readAllBytes(existing));
        assertEquals("Preserved", read(existing).map.name);
        assertEquals(1, folder.toFile().list().length);
        assertEquals("Encounter (2).dps", DpsExport.write(folder, "Encounter", encounter("Retry")).getFileName().toString());
    }

    @Test public void concurrentExportsAllocateDistinctCompleteRecordings() throws Exception {
        Path folder = temporary.newFolder().toPath();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Path>> jobs = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final String name = "Worker " + i;
                jobs.add(pool.submit(() -> { start.await(); return DpsExport.write(folder, "Encounter", encounter(name)); }));
            }
            start.countDown();
            Set<Path> destinations = new HashSet<>();
            for (int i = 0; i < jobs.size(); i++) {
                Path file = jobs.get(i).get(10, TimeUnit.SECONDS);
                assertTrue(destinations.add(file));
                assertEquals("Worker " + i, read(file).map.name);
            }
            assertEquals(8, folder.toFile().list().length);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private static DpsData encounter(String name) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name;
        return new DpsData(map, new HashMap<>(), new ArrayList<>(), 1000, 1000, null);
    }

    private static DpsData read(Path file) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            return (DpsData) input.readObject();
        }
    }

    private static final class FailingMap extends MapInfoPacket {
        private void writeObject(ObjectOutputStream output) throws IOException {
            output.defaultWriteObject();
            throw new IOException("Serialization failed");
        }
    }
}
