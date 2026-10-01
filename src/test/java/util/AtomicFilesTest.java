package util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AtomicFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void firstWriteCleansOnlyOldMatchingRegularOrphansOncePerTarget() throws Exception {
        Path folder = temp.getRoot().toPath(), target = folder.resolve("prefs[1].json");
        long old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;
        Path orphan = staged(folder.resolve(".prefs[1].json-orphan.tmp"), old);
        Path young = staged(folder.resolve(".prefs[1].json-young.tmp"), System.currentTimeMillis());
        Path other = staged(folder.resolve(".other.json-orphan.tmp"), old);
        Path similar = staged(folder.resolve(".prefs1.json-orphan.tmp"), old);
        Path suffix = staged(folder.resolve(".prefs[1].json-orphan.tmp.backup"), old);
        Path directory = Files.createDirectory(folder.resolve(".prefs[1].json-directory.tmp"));
        Path retained = staged(directory.resolve("keep"), old);
        Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(old));
        AtomicFiles.write(target, new byte[]{1}, false);
        assertFalse(Files.exists(orphan));
        for (Path keep : new Path[]{young, other, similar, suffix, directory, retained}) assertTrue(keep.toString(), Files.exists(keep));
        Path later = staged(folder.resolve(".prefs[1].json-later.tmp"), old);
        AtomicFiles.write(folder.resolve(".").resolve(target.getFileName()), new byte[]{2}, false);
        assertTrue("An already swept target is not swept again", Files.exists(later));
        AtomicFiles.write(folder.resolve("other.json"), new byte[]{3}, false);
        assertFalse("Each target gets its own initial sweep", Files.exists(other));
        assertTrue(Files.exists(later)); assertTrue(Files.exists(young));
    }
    private static Path staged(Path file, long modified) throws IOException {
        Files.writeString(file, "staged"); Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modified));
        return file;
    }
    @Test public void replacesContentAndSupportsClosingAStreamWriterBeforeSync() throws Exception {
        Path file = temp.getRoot().toPath().resolve("value");
        Files.writeString(file, "old");
        AtomicFiles.write(file, "new".getBytes(StandardCharsets.UTF_8), false);
        assertEquals("new", Files.readString(file));
        AtomicFiles.write(file, stream -> {
            try (ObjectOutputStream output = new ObjectOutputStream(stream)) { output.writeObject("saved"); }
        }, true);
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) { assertEquals("saved", input.readObject()); }
        assertOnlyTarget(file);
    }
    @Test public void onlyUnsupportedAtomicMovesFallBackToReplacement() throws Exception {
        Path file = temp.getRoot().toPath().resolve("value"); Files.writeString(file, "old");
        AtomicInteger moves = new AtomicInteger();
        AtomicFiles.write(file, out -> out.write(42), false, (source, target) -> AtomicFiles.replace(source, target, (from, to, options) -> {
            if (moves.incrementAndGet() == 1) {
                assertArrayEquals(new CopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}, options);
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture");
            }
            assertArrayEquals(new CopyOption[]{StandardCopyOption.REPLACE_EXISTING}, options);
            Files.move(from, to, options);
        }));
        assertEquals(2, moves.get()); assertArrayEquals(new byte[]{42}, Files.readAllBytes(file)); assertOnlyTarget(file);
        moves.set(0);
        assertThrows(IOException.class, () -> AtomicFiles.write(file, out -> out.write(1), false,
            (source, target) -> AtomicFiles.replace(source, target, (from, to, options) -> {
                moves.incrementAndGet(); throw new IOException("denied");
            })));
        assertEquals(1, moves.get()); assertArrayEquals(new byte[]{42}, Files.readAllBytes(file)); assertOnlyTarget(file);
    }
    @Test public void failedWriterCleansUniqueStagingAndPreservesOldFile() throws Exception {
        Path file = temp.getRoot().toPath().resolve("value"); Files.writeString(file, "old");
        Path unrelated = file.resolveSibling(".value-other.tmp"); Files.writeString(unrelated, "other writer");
        assertThrows(IOException.class, () -> AtomicFiles.write(file, out -> { out.write(1); throw new IOException("disk full"); }, true));
        assertEquals("old", Files.readString(file)); assertEquals("other writer", Files.readString(unrelated));
        Files.delete(unrelated); assertOnlyTarget(file);
    }
    private void assertOnlyTarget(Path target) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(target.getParent())) {
            assertArrayEquals(new Path[]{target}, files.toArray(Path[]::new));
        }
    }
}
