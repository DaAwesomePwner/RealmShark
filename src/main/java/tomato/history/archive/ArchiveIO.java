package tomato.history.archive;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;

final class ArchiveIO {
    static final int MAX_RECORD = 16 * 1024 * 1024;
    private ArchiveIO() { }
    static void offEdt() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Archive I/O must run off the EDT");
    }
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> iterator = paths.sorted(Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) Files.deleteIfExists(iterator.next());
        }
    }
    static void write(DataOutput out, byte[] bytes) throws IOException {
        if (bytes.length > MAX_RECORD) throw new IOException("Archive record exceeds the 16 MiB limit");
        out.writeInt(bytes.length); out.write(bytes);
    }
    static byte[] read(DataInputStream in) throws IOException {
        int first = in.read(); if (first < 0) return null;
        int size = (first << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (size < 0 || size > MAX_RECORD) throw new IOException("Invalid archive spool record length");
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
}
