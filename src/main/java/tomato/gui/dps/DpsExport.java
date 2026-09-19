package tomato.gui.dps;

import tomato.backend.data.DpsData;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Background file writer. Existing destinations are never opened for writing. */
final class DpsExport {
    private DpsExport() { }

    static Path write(Path folder, String base, DpsData saved) throws IOException {
        Path staged = Files.createTempFile(folder, ".realmshark-dps-", ".tmp");
        try {
            try (ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)))) {
                output.writeObject(saved);
            }
            return publish(folder, base, output -> Files.copy(staged, output));
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @FunctionalInterface
    interface ContentWriter { void write(OutputStream output) throws IOException; }

    static Path publish(Path folder, String base, ContentWriter content) throws IOException {
        for (long suffix = 1; ; suffix++) {
            Path destination = folder.resolve(base + (suffix == 1 ? "" : " (" + suffix + ")") + ".dps");
            OutputStream claimed;
            try {
                // CREATE_NEW is exclusive even across workers/processes. A check followed by a
                // move (including ATOMIC_MOVE on some providers) can race and replace a file.
                claimed = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException failure) {
                // Providers may report an existing directory as AccessDenied rather than
                // FileAlreadyExists. This check only retries; CREATE_NEW still owns the claim.
                if (failure instanceof FileAlreadyExistsException || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
                throw failure;
            }
            try (OutputStream output = claimed) {
                content.write(output);
            } catch (IOException | RuntimeException | Error failure) {
                try { Files.deleteIfExists(destination); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return destination;
        }
    }
}
