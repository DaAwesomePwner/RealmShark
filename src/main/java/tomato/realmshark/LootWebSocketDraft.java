package tomato.realmshark;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.Opcode;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.exceptions.LimitExceededException;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.Handshakedata;

/** Bounds the pinned 1.5.3 decoder, including its otherwise unchecked intermediate fragments. */
final class LootWebSocketDraft extends Draft_6455 {
    static final int MAX_MESSAGE_BYTES = 64 * 1024;
    static final int MAX_FRAGMENTS = 128;
    static final int MAX_HANDSHAKE_BYTES = 16 * 1024;
    private final Consumer<String> abort;
    private long fragmentBytes;
    private int fragments;

    LootWebSocketDraft(Consumer<String> abort) {
        super(Collections.emptyList(), MAX_MESSAGE_BYTES);
        this.abort = abort;
    }

    @Override public Draft copyInstance() { return new LootWebSocketDraft(abort); }

    @Override public Handshakedata translateHandshake(ByteBuffer bytes) throws InvalidHandshakeException {
        // Find the header terminator without treating coalesced post-handshake frames as headers.
        int end = bytes.limit();
        for (int i = bytes.position(); i + 3 < bytes.limit(); i++) {
            if (bytes.get(i) == '\r' && bytes.get(i + 1) == '\n'
                    && bytes.get(i + 2) == '\r' && bytes.get(i + 3) == '\n') { end = i + 4; break; }
        }
        if (end - bytes.position() > MAX_HANDSHAKE_BYTES) {
            abort.accept("Loot handshake exceeds byte limit");
            throw new InvalidHandshakeException("Loot handshake exceeds byte limit");
        }
        return super.translateHandshake(bytes);
    }

    @Override public List<Framedata> translateFrame(ByteBuffer bytes) throws InvalidDataException {
        try { return super.translateFrame(bytes); }
        catch (InvalidDataException ex) {
            // Abort rather than enqueue a close handshake behind a potentially blocked writer.
            abort.accept("Loot inbound frame rejected: " + ex.getMessage());
            throw ex;
        }
    }

    @Override public void processFrame(WebSocketImpl connection, Framedata frame) throws InvalidDataException {
        if (!connection.isOpen()) return;
        Opcode opcode = frame.getOpcode();
        if (opcode == Opcode.TEXT || opcode == Opcode.BINARY || opcode == Opcode.CONTINUOUS) {
            if (!frame.isFin() || opcode == Opcode.CONTINUOUS) {
                fragmentBytes += frame.getPayloadData().remaining();
                fragments++;
                // The count also bounds retained zero-length fragments (a byte cap alone cannot).
                if (fragmentBytes > MAX_MESSAGE_BYTES || fragments > MAX_FRAGMENTS) {
                    abort.accept("Loot fragmented message exceeds byte/count limit");
                    throw new LimitExceededException("Loot fragmented message exceeds byte/count limit", MAX_MESSAGE_BYTES);
                }
            }
        }
        try { super.processFrame(connection, frame); }
        catch (InvalidDataException ex) { abort.accept("Loot inbound message rejected: " + ex.getMessage()); throw ex; }
        if (opcode == Opcode.CONTINUOUS && frame.isFin()) { fragmentBytes = 0; fragments = 0; }
    }

    @Override public void reset() { super.reset(); fragmentBytes = 0; fragments = 0; }
}
