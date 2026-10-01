package tomato.history;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort startup cleanup, restricted to recognizably staged files and scratch directories. */
public final class Housekeeping {
    public static final Path ARCHIVE_SCRATCH = Paths.get(System.getProperty("java.io.tmpdir"), "realmshark-archive");
    private static final long HOUR = 60 * 60 * 1000L;
    private static final AtomicBoolean historyStarted = new AtomicBoolean(), archiveStarted = new AtomicBoolean();
    private static volatile Result historyResult = new Result(), archiveResult = new Result();
    private Housekeeping() { }
    public static final class Result {
        public long deleted, failures;
    }
    public static Result historyResult() { return historyResult; }
    public static Result archiveResult() { return archiveResult; }

    public static void startHistory(Path root, String current, boolean preview) {
        if (preview || !historyStarted.compareAndSet(false, true)) return;
        background("History temporary-file cleanup", () -> historyResult = sweepHistory(root, current, System.currentTimeMillis()));
    }
    public static void startArchive(boolean preview) {
        if (preview || !archiveStarted.compareAndSet(false, true)) return;
        background("Archive scratch cleanup", () -> archiveResult = sweepArchive(ARCHIVE_SCRATCH, System.currentTimeMillis()));
    }
    private static void background(String name, Runnable work) {
        Thread thread = new Thread(work, name); thread.setDaemon(true); thread.start();
    }

    static Result sweepHistory(Path root, String current, long now) {
        Result result = new Result();
        try { if (!safeDirectory(root)) return result; }
        catch (IOException | RuntimeException ignored) { result.failures++; return result; }
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(root)) {
            for (Path session : sessions) try {
                if (session.getFileName().toString().equals(current)) continue;
                BasicFileAttributes attributes = Files.readAttributes(session, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || isLink(attributes)) continue;
                historyFolder(session, now - HOUR, true, result);
            } catch (IOException | RuntimeException ignored) { result.failures++; }
        } catch (IOException | RuntimeException ignored) { result.failures++; }
        return result;
    }
    private static void historyFolder(Path folder, long cutoff, boolean modules, Result result) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) try {
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (isLink(attributes)) continue;
                String name = entry.getFileName().toString();
                if (attributes.isRegularFile() && ((name.startsWith(".history-") && name.endsWith(".tmp"))
                        || name.matches("\\..+\\.json-[0-9]+\\.tmp"))
                        && attributes.lastModifiedTime().toMillis() < cutoff) {
                    if (Files.deleteIfExists(entry)) result.deleted++;
                } else if (modules && attributes.isDirectory()) historyFolder(entry, cutoff, false, result);
            } catch (IOException | RuntimeException ignored) { result.failures++; }
        } catch (IOException | RuntimeException ignored) { result.failures++; }
    }

    static Result sweepArchive(Path root, long now) {
        Result result = new Result();
        try { if (!safeDirectory(root)) return result; }
        catch (IOException | RuntimeException ignored) { result.failures++; return result; }
        try {
            // Workspaces place their temporary directories below module-specific scratch folders.
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (isLink(attributes)) return FileVisitResult.SKIP_SUBTREE;
                    String name = directory.getFileName().toString();
                    if (!directory.equals(root) && (name.startsWith("archive-pin-") || name.startsWith("archive-result-"))) {
                        cleanArchive(directory, now - 24 * HOUR, result);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) {
                    result.failures++; return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    if (failure != null) result.failures++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) { result.failures++; }
        return result;
    }
    private static void cleanArchive(Path directory, long cutoff, Result result) {
        try {
            long[] newest = {Long.MIN_VALUE}; boolean[] preserve = {false};
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
                    if (isLink(attributes)) { preserve[0] = true; return FileVisitResult.SKIP_SUBTREE; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (isLink(attributes) || !attributes.isRegularFile()) preserve[0] = true;
                    else newest[0] = Math.max(newest[0], attributes.lastModifiedTime().toMillis());
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    preserve[0] = true; result.failures++; return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path folder, IOException failure) {
                    if (failure != null) { preserve[0] = true; result.failures++; }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (newest[0] == Long.MIN_VALUE) newest[0] = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (preserve[0] || newest[0] >= cutoff) return;
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) throws IOException {
                    if (isLink(attributes)) throw new IOException("Scratch directory changed during cleanup");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (isLink(attributes) || !attributes.isRegularFile() || attributes.lastModifiedTime().toMillis() >= cutoff)
                        throw new IOException("Scratch directory changed during cleanup");
                    Files.delete(file); result.deleted++; return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(folder); result.deleted++; return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) { result.failures++; }
    }
    private static boolean safeDirectory(Path directory) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isDirectory() && !isLink(attributes);
        } catch (NoSuchFileException absent) { return false; }
    }
    private static boolean isLink(BasicFileAttributes attributes) { return attributes.isSymbolicLink() || attributes.isOther(); }
}
