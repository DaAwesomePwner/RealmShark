package tomato.history.index;

import java.util.Objects;
import tomato.history.SessionStore;

/** Exact source address. checkpointKey is the on-disk key (filename without .json), including for legacy imports. */
public record Locator(String session, String module, long byteOffset, String checkpointKey, int itemPosition) {
    public Locator {
        Objects.requireNonNull(session); Objects.requireNonNull(module);
        if (byteOffset < 0 && checkpointKey == null || byteOffset >= 0 && checkpointKey != null)
            throw new IllegalArgumentException("Exactly one source locator is required");
    }
    public static Locator offered(String session, String module, long offset, String key) {
        return new Locator(session, module, key == null ? offset : -1, key == null ? null : SessionStore.checkpointName(key), -1);
    }
    public Locator at(int position) { return new Locator(session, module, byteOffset, checkpointKey, position); }
    String source() { return checkpointKey == null ? "offset:" + byteOffset : "checkpoint:" + checkpointKey; }
    String identity() { return session + "/" + module + "/" + source() + "/" + itemPosition; }
}
