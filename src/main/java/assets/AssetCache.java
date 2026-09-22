package assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

/** An atomic pointer selects a validated extraction generation; legacy/user files are left intact. */
public final class AssetCache {
    private static final Path LEGACY = Paths.get("assets");
    private static final Path GENERATIONS = LEGACY.resolve(".realmshark-cache");
    private static final Path POINTER = LEGACY.resolve("realmshark-cache.current");
    private static final String VERSION = "realmshark-assets-v1";
    private static volatile Generation current = readCurrent();

    private static final class Generation {
        final Path root;
        final String stamp;
        Generation(Path root, String stamp) { this.root = root; this.stamp = stamp; }
    }
    private AssetCache() { }
    public static Path root() { return current.root; }
    public static Path path(String relative) { return current.root.resolve(relative); }
    static String stamp() { return current.stamp; }

    private static Generation readCurrent() {
        try { return readPointer(true); }
        catch (IOException | RuntimeException unavailable) { return new Generation(LEGACY, null); }
    }
    private static Generation readPointer(boolean requireDirectory) throws IOException {
        List<String> lines = Files.readAllLines(POINTER, StandardCharsets.UTF_8);
        if (lines.size() != 3 || !VERSION.equals(lines.get(0)) || !lines.get(1).matches("generation-[A-Za-z0-9-]+"))
            throw new IOException("Unrecognized asset cache pointer; existing file preserved.");
        Path root = GENERATIONS.resolve(lines.get(1));
        if (requireDirectory && !Files.isDirectory(root)) throw new IOException("Asset cache generation is unavailable.");
        return new Generation(root, lines.get(2));
    }
    static Path createGeneration() throws IOException {
        Files.createDirectories(GENERATIONS);
        return Files.createTempDirectory(GENERATIONS, "generation-");
    }

    /** Prepared catalogs are published only after this commit succeeds. No non-atomic pointer fallback. */
    static void publish(Path root, String stamp) throws IOException {
        publish(root, stamp, (temporary, target) -> Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    @FunctionalInterface interface PointerMove { void move(Path temporary, Path target) throws IOException; }

    static void publish(Path root, String stamp, PointerMove move) throws IOException {
        if (!root.toAbsolutePath().getParent().equals(GENERATIONS.toAbsolutePath())
                || stamp.contains("\n") || stamp.contains("\r")) throw new IOException("Invalid asset cache generation.");
        if (Files.exists(POINTER)) readPointer(false); // A recognized pointer with a missing generation can be repaired.
        Path temporary = Files.createTempFile(LEGACY, "realmshark-cache-", ".tmp");
        boolean committed = false;
        try {
            Files.write(temporary, Arrays.asList(VERSION, root.getFileName().toString(), stamp), StandardCharsets.UTF_8);
            move.move(temporary, POINTER);
            current = new Generation(root, stamp);
            committed = true;
        } finally { if (!committed) Files.deleteIfExists(temporary); }
    }

    /** Only called for the uncommitted, application-created directory from createGeneration(). */
    static void discard(Path root) {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
        } catch (IOException failure) {
            // An abandoned private staging directory is preferable to deleting a previous cache.
            System.err.println("Could not remove an incomplete private asset generation.");
        }
    }
}
