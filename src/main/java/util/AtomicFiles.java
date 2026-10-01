package util;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Publishes a fully written, uniquely staged file beside its destination. */
public final class AtomicFiles {
    private static final java.util.Set<Path> cleanedTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private AtomicFiles() { }
    @FunctionalInterface public interface IOConsumer<T> { void accept(T value) throws IOException; }
    @FunctionalInterface interface Replacement { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface Mover { void move(Path source, Path target, CopyOption... options) throws IOException; }

    public static void write(Path target, byte[] bytes, boolean sync) throws IOException {
        write(target, out -> out.write(bytes), sync);
    }
    public static void write(Path target, IOConsumer<OutputStream> writer, boolean sync) throws IOException {
        write(target, writer, sync, AtomicFiles::replace);
    }
    static void write(Path target, IOConsumer<OutputStream> writer, boolean sync, Replacement replacement) throws IOException {
        Path absolute = target.toAbsolutePath();
        cleanOrphans(absolute);
        Path temporary = Files.createTempFile(absolute.getParent(), "." + absolute.getFileName() + "-", ".tmp");
        Throwable failure = null;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                // A caller may close its encoder/serializer; keep the channel open until force completes.
                OutputStream output = new FilterOutputStream(Channels.newOutputStream(channel)) {
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
                    @Override public void close() throws IOException { flush(); }
                };
                writer.accept(output);
                output.flush();
                if (sync) channel.force(true);
            }
            replacement.move(temporary, absolute);
        } catch (IOException | RuntimeException | Error error) {
            failure = error; throw error;
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }
    private static void cleanOrphans(Path target) {
        if (!cleanedTargets.add(target.normalize())) return;
        String prefix = "." + target.getFileName() + "-";
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000L;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(target.getParent())) {
            for (Path entry : entries) try {
                String name = entry.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".tmp")) continue;
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther()
                        && attributes.lastModifiedTime().toMillis() < cutoff) Files.deleteIfExists(entry);
            } catch (IOException | RuntimeException ignored) { /* Best-effort cleanup must not prevent a save. */ }
        } catch (IOException | RuntimeException ignored) { /* Retry normal publication even when cleanup is unavailable. */ }
    }
    static void replace(Path source, Path target) throws IOException { replace(source, target, Files::move); }
    static void replace(Path source, Path target, Mover mover) throws IOException {
        try { mover.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { mover.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
