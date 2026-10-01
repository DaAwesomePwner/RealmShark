package tomato.history.index;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Read-only UTF-8 prefix reader. A concurrent append or incomplete final line is never consumed. */
final class JournalLines {
    @FunctionalInterface interface Sink { void line(long offset, String text) throws Exception; }
    static void read(Path file, Sink sink) throws Exception {
        long size = Files.size(file);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) { read(input, size, sink); }
    }
    static void read(InputStream input, long size, Sink sink) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long offset = 0, position = 0;
        byte[] buffer = new byte[32768];
        while (position < size) {
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, size - position));
            if (count < 0) throw new EOFException("Journal shortened during indexing");
            for (int i = 0; i < count; i++) {
                position++;
                if (buffer[i] == '\n') {
                    sink.line(offset, new String(line.toByteArray(), StandardCharsets.UTF_8));
                    line.reset(); offset = position;
                } else line.write(buffer[i]);
            }
        }
    }
}
