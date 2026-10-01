package tomato.history;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HousekeepingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long NOW = 2_000_000_000_000L, HOUR = 3_600_000L;

    @Test public void historyRemovesOnlyOldMatchingTempsAtTheTwoSupportedLevels() throws Exception {
        Path root = temp.newFolder().toPath();
        Path old = file(root.resolve("old/.history-a.tmp"), NOW - 2 * HOUR);
        Path module = file(root.resolve("old/runs/.history-b.tmp"), NOW - 2 * HOUR);
        Path young = file(root.resolve("old/.history-young.tmp"), NOW - HOUR + 1);
        Path boundary = file(root.resolve("old/.history-boundary.tmp"), NOW - HOUR);
        Path other = file(root.resolve("old/keep.tmp"), NOW - 2 * HOUR);
        Path deep = file(root.resolve("old/runs/deeper/.history-c.tmp"), NOW - 2 * HOUR);
        Path current = file(root.resolve("current/runs/.history-d.tmp"), NOW - 2 * HOUR);
        Path rootTemp = file(root.resolve(".history-root.tmp"), NOW - 2 * HOUR);
        Housekeeping.Result result = Housekeeping.sweepHistory(root, "current", NOW);
        assertEquals(2, result.deleted); assertEquals(0, result.failures);
        assertFalse(Files.exists(old)); assertFalse(Files.exists(module));
        for (Path retained : new Path[]{young, boundary, other, deep, current, rootTemp}) assertTrue(retained.toString(), Files.exists(retained));
    }

    @Test public void archiveUsesNewestFileAndFindsWorkspaceScratchWithoutRemovingOtherNames() throws Exception {
        Path root = temp.newFolder().toPath();
        Path old = file(root.resolve("loot/archive-pin-old/data"), NOW - 25 * HOUR).getParent();
        Path nested = file(root.resolve("stats/archive-result-old/nested/data"), NOW - 25 * HOUR).getParent().getParent();
        Path young = file(root.resolve("archive-result-young/old"), NOW - 25 * HOUR).getParent();
        file(young.resolve("new"), NOW - HOUR);
        Path boundary = file(root.resolve("archive-pin-boundary/data"), NOW - 24 * HOUR).getParent();
        Path other = file(root.resolve("keep/data"), NOW - 25 * HOUR).getParent();
        Path namedFile = file(root.resolve("archive-pin-file"), NOW - 25 * HOUR);
        Path emptyOld = Files.createDirectory(root.resolve("archive-result-empty-old"));
        Files.setLastModifiedTime(emptyOld, FileTime.fromMillis(NOW - 25 * HOUR));
        Path emptyYoung = Files.createDirectory(root.resolve("archive-pin-empty-young"));
        Files.setLastModifiedTime(emptyYoung, FileTime.fromMillis(NOW));
        Housekeeping.Result result = Housekeeping.sweepArchive(root, NOW);
        assertEquals(0, result.failures);
        assertFalse(Files.exists(old)); assertFalse(Files.exists(nested)); assertFalse(Files.exists(emptyOld));
        for (Path retained : new Path[]{young, boundary, other, namedFile, emptyYoung}) assertTrue(Files.exists(retained));
    }

    @Test public void keepsSymlinksAndNeverSweepsTheirTargets() throws Exception {
        Path root = temp.newFolder().toPath(), outside = temp.newFolder().toPath();
        Path target = file(outside.resolve(".history-outside.tmp"), NOW - 25 * HOUR);
        Path session = Files.createDirectory(root.resolve("session"));
        Path link = session.resolve(".history-link.tmp");
        // Windows requires Developer Mode or symlink privilege; only this capability-specific case is conditional.
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) { Assume.assumeNoException(unsupported); }
        Path linkedSession = root.resolve("linked-session"); Files.createSymbolicLink(linkedSession, outside);
        Housekeeping.sweepHistory(root, "current", NOW);
        assertTrue(Files.isSymbolicLink(link)); assertTrue(Files.isSymbolicLink(linkedSession)); assertTrue(Files.exists(target));
        Path scratch = Files.createDirectory(root.resolve("scratch"));
        Path linkedArchive = scratch.resolve("archive-pin-link"); Files.createSymbolicLink(linkedArchive, outside);
        Path candidate = file(scratch.resolve("archive-result-with-link/old"), NOW - 25 * HOUR).getParent();
        Files.createSymbolicLink(candidate.resolve("link"), target);
        Housekeeping.sweepArchive(scratch, NOW);
        assertTrue(Files.isSymbolicLink(linkedArchive)); assertTrue(Files.isSymbolicLink(candidate.resolve("link")));
        assertTrue(Files.exists(candidate.resolve("old"))); assertTrue(Files.exists(target));
        assertEquals(0, Housekeeping.sweepHistory(linkedSession, "current", NOW).deleted);
        assertEquals(0, Housekeeping.sweepArchive(linkedArchive, NOW).deleted);
        assertLinkedAncestorAllowsRealRoots(outside, linkedSession);
    }

    @Test public void junctionsArePreservedAtEverySweepLevel() throws Exception {
        Assume.assumeTrue("Junctions are a Windows filesystem feature", java.io.File.separatorChar == '\\');
        Path history = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(), outside = temp.newFolder().toPath();
        Path historyTarget = file(outside.resolve(".history-outside.tmp"), NOW - 25 * HOUR);
        Path archiveTarget = file(outside.resolve("archive-result-outside/data"), NOW - 25 * HOUR);
        Path session = Files.createDirectory(history.resolve("session"));
        Path candidate = file(scratch.resolve("archive-result-contained/old"), NOW - 25 * HOUR).getParent();
        java.util.List<Path> junctions = new java.util.ArrayList<>();
        try {
            junction(history.resolve("linked-session"), outside, junctions);
            junction(session.resolve("linked-module"), outside, junctions);
            junction(scratch.resolve("workspace"), outside, junctions);
            junction(scratch.resolve("archive-pin-junction"), outside, junctions);
            junction(candidate.resolve("nested-junction"), outside, junctions);
            assertEquals(0, Housekeeping.sweepHistory(history, "current", NOW).deleted);
            assertEquals(0, Housekeeping.sweepArchive(scratch, NOW).deleted);
            assertTrue(Files.exists(historyTarget)); assertTrue(Files.exists(archiveTarget));
            assertTrue(Files.exists(candidate.resolve("old")));
            for (Path link : junctions) assertTrue(Files.readAttributes(link, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isOther());
            assertEquals(0, Housekeeping.sweepHistory(junctions.get(0), "current", NOW).deleted);
            assertEquals(0, Housekeeping.sweepArchive(junctions.get(3), NOW).deleted);
            assertLinkedAncestorAllowsRealRoots(outside, junctions.get(0));
        } finally {
            // Delete only the junction entries, never their target trees (including on assertion failure).
            for (Path link : junctions) Files.deleteIfExists(link);
        }
    }

    private static void junction(Path link, Path target, java.util.List<Path> created) throws Exception {
        created.add(link);
        Process process;
        try { process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start(); }
        catch (IOException | SecurityException unavailable) { Assume.assumeNoException(unavailable); return; }
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("Junction creation timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        Assume.assumeTrue("Host cannot create a junction: " + output, process.exitValue() == 0);
    }

    private static void assertLinkedAncestorAllowsRealRoots(Path outside, Path linkedAncestor) throws IOException {
        Path historyFile = file(outside.resolve("real-history/session/.history-old.tmp"), NOW - 2 * HOUR);
        Path archiveFile = file(outside.resolve("real-scratch/archive-result-old/data"), NOW - 25 * HOUR);
        assertEquals(1, Housekeeping.sweepHistory(linkedAncestor.resolve("real-history"), "current", NOW).deleted);
        assertFalse(Files.exists(historyFile));
        assertTrue(Housekeeping.sweepArchive(linkedAncestor.resolve("real-scratch"), NOW).deleted > 0);
        assertFalse(Files.exists(archiveFile.getParent()));
    }

    private static Path file(Path path, long modified) throws IOException {
        Files.createDirectories(path.getParent()); Files.writeString(path, "fixture");
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified)); return path;
    }
}
