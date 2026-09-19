package tomato.gui.keypop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Capture writes here; Swing reads one consistent, bounded snapshot on its own thread. */
final class KeyPopHistory {
    static final int CAPACITY = 10000;
    private final ArrayDeque<KeyPopEvent> events = new ArrayDeque<>();
    private long revision;
    private long discarded;

    synchronized void add(KeyPopEvent event) {
        if (events.size() == CAPACITY) { events.removeFirst(); discarded++; }
        events.addLast(event);
        revision++;
    }

    synchronized void clear() { events.clear(); discarded = 0; revision++; }
    synchronized long revision() { return revision; }
    synchronized Snapshot snapshot() { return new Snapshot(new ArrayList<>(events), revision, discarded); }

    static final class Snapshot {
        final List<KeyPopEvent> events;
        final long revision;
        final long discarded;
        Snapshot(List<KeyPopEvent> events, long revision, long discarded) {
            this.events = events; this.revision = revision; this.discarded = discarded;
        }
    }
}
