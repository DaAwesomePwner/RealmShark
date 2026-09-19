package packets.packetcapture.logger;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, non-blocking capture-side queue. One daemon owns the rotating files. */
final class DiscoveryWriter implements AutoCloseable {
    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1024);
    private final Path directory;
    private final long limit;
    private final Thread worker;
    private volatile boolean closed;
    private volatile String error = "";
    final AtomicLong dropped = new AtomicLong();
    DiscoveryWriter(Path directory, long limit) {
        this.directory = directory; this.limit = limit;
        worker = new Thread(this::run, "RealmShark discovery log writer");
        worker.setDaemon(true); worker.start();
    }
    void offer(Object event) { if (closed || !queue.offer(event)) dropped.incrementAndGet(); }
    String error() { return error; }
    private void run() {
        Gson gson = new Gson();
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("discovery.jsonl");
            long size = Files.exists(file) ? Files.size(file) : 0;
            while (!closed || !queue.isEmpty()) {
                Object event = queue.poll(250, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                byte[] bytes = (gson.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
                if (size + bytes.length > limit) {
                    for (int i = 3; i >= 1; i--) {
                        Path from = directory.resolve(i == 1 ? "discovery.jsonl" : "discovery." + (i - 1) + ".jsonl");
                        if (Files.exists(from)) Files.move(from, directory.resolve("discovery." + i + ".jsonl"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    size = 0;
                }
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                size += bytes.length;
            }
        } catch (Exception e) {
            error = "Log writing stopped: " + e.getClass().getSimpleName() + ". Check logs/discovery permissions and free space; restart the app to retry.";
            closed = true;
            dropped.addAndGet(queue.size() + 1); queue.clear();
        }
    }
    @Override public void close() {
        closed = true;
        try { worker.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
