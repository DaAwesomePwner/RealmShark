package packets.packetcapture.sniff.assembly;

import packets.packetcapture.sniff.netpackets.TcpPacket;

import java.util.*;

/**
 * Stream constructor ordering TCP packets in sequence. Payload is extracted and sent back in its raw form.
 */
public class TcpStreamBuilder {

    private final List<TcpPacket> pending = new ArrayList<>();
    private final Set<String> retired = new LinkedHashSet<>();
    private String connection;
    private long nextSequence;
    private long synSequence = -1;
    private boolean initialized;
    private PStream packetStream;
    private PReset packetReset;

    /**
     * Constructor of StreamConstructor which needs a reset class to reset if reset
     * packet is retrieved and a constructor class to send ordered packets to.
     *
     * @param preset  Reset class if a reset packet is retrieved.
     * @param pstream Constructor class to send ordered packets to.
     */
    public TcpStreamBuilder(PReset preset, PStream pstream) {
        packetReset = preset;
        packetStream = pstream;
    }

    /**
     * Build method for ordering packets according to index used by TCP.
     *
     * @param packet TCP packets needing to be ordered.
     */
    public void streamBuilder(TcpPacket packet) {
        String key = Arrays.toString(packet.getIp4Packet().getSrcAddr()) + ":" + packet.getSrcPort()
                + ">" + Arrays.toString(packet.getIp4Packet().getDstAddr()) + ":" + packet.getDstPort();
        // ACK-only packets must not select a flow or accumulate behind a missing segment.
        if (!packet.isSyn() && !packet.isRst() && !packet.isFin() && packet.getPayloadSize() == 0) return;
        if (!key.equals(connection)) {
            if (retired.contains(key) && !packet.isSyn()) return;
            if (connection != null) {
                retired.add(connection);
                if (retired.size() > 256) retired.remove(retired.iterator().next());
                resetStream();
            }
            retired.remove(key);
            connection = key;
        }
        long sequence = packet.getSequenceNumber();
        if (packet.isSyn()) {
            if (synSequence == sequence) return;
            resetStream();
            synSequence = sequence;
            nextSequence = (sequence + 1) & 0xffffffffL;
            initialized = true;
        }
        if (packet.isRst()) {
            retired.add(key);
            resetStream();
            connection = null;
            return;
        }
        if (!initialized) {
            nextSequence = sequence;
            initialized = true;
        }
        if (packet.getPayloadSize() == 0 && !packet.isFin()) return;
        for (TcpPacket part : pending) {
            if (part.getSequenceNumber() == sequence && part.getPayloadSize() == packet.getPayloadSize()
                    && part.isFin() == packet.isFin()) return;
        }
        pending.add(packet);
        boolean advanced;
        do {
            advanced = false;
            for (Iterator<TcpPacket> it = pending.iterator(); it.hasNext();) {
                TcpPacket part = it.next();
                long start = (part.getSequenceNumber() + (part.isSyn() ? 1 : 0)) & 0xffffffffL;
                // Serial numbers wrap at 2^32; buffered windows remain below 2^31.
                int ahead = (int)(start - nextSequence);
                if (ahead > 0) continue;
                int consumed = -ahead;
                byte[] bytes = part.getPayload();
                it.remove();
                if (consumed >= 0 && consumed < bytes.length) {
                    byte[] fresh = consumed == 0 ? bytes : Arrays.copyOfRange(bytes, consumed, bytes.length);
                    nextSequence = (nextSequence + fresh.length) & 0xffffffffL;
                    packetStream.stream(fresh, part.getIp4Packet().getSrcAddr());
                    advanced = true;
                }
                if (part.isFin() && nextSequence == ((start + bytes.length) & 0xffffffffL)) {
                    nextSequence = (nextSequence + 1) & 0xffffffffL;
                    advanced = true;
                }
            }
        } while (advanced);
        int bufferedBytes = 0;
        for (TcpPacket part : pending) bufferedBytes += part.getPayloadSize();
        if (pending.size() > 256 || bufferedBytes > 2097152) {
            // Never pass a hole in the byte stream to the stateful cipher.
            throw new IllegalStateException("TCP capture gap exceeded the reorder buffer; reconnect capture");
        }
    }

    /**
     * Reset method if a reset packet is retrieved.
     */
    public void reset() {
        resetStream();
        connection = null;
        retired.clear();
    }

    private void resetStream() {
        packetReset.reset();
        pending.clear();
        nextSequence = 0;
        synSequence = -1;
        initialized = false;
    }
}
