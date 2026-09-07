package packets.packetcapture.sniff.assembly;

import org.junit.Test;
import packets.packetcapture.sniff.netpackets.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TcpStreamBuilderTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final AtomicInteger resets = new AtomicInteger();
    private final TcpStreamBuilder stream = new TcpStreamBuilder(resets::incrementAndGet,
            (bytes, address) -> output.write(bytes, 0, bytes.length));

    private void feed(long sequence, int flags, String body) { feed(sequence, flags, body, 50000); }
    private void feed(long sequence, int flags, String body, int port) {
        byte[] text = body.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer ip = ByteBuffer.allocate(40 + text.length);
        ip.put(0, (byte)0x45); ip.putShort(2, (short)ip.capacity()); ip.put(9, (byte)6);
        ip.put(12, (byte)10); ip.put(16, (byte)11);
        ip.putShort(20, (short)2050); ip.putShort(22, (short)port);
        ip.putInt(24, (int)sequence); ip.put(32, (byte)0x50); ip.put(33, (byte)flags);
        ip.position(40); ip.put(text);
        stream.streamBuilder(new Ip4Packet(ip.array(), null).getNewTcpPacket());
    }
    private String result() { return new String(output.toByteArray(), StandardCharsets.US_ASCII); }

    @Test public void reordersAndTrimsOverlappingRetransmissions() {
        feed(100, 2, "");
        feed(104, 24, "def");
        feed(101, 24, "abcd");
        feed(101, 24, "abcdef");
        feed(105, 24, "efgh");
        feed(109, 24, "ij");
        assertEquals("abcdefghij", result());
    }
    @Test public void sequenceNumbersWrapWithoutStalling() {
        feed(0xfffffffdL, 2, "");
        feed(0xfffffffeL, 24, "abcd");
        feed(2, 24, "ef");
        assertEquals("abcdef", result());
    }
    @Test public void synPreservesOrderingEvenIfFirstPayloadArrivesLate() {
        feed(100, 2, ""); feed(104, 24, "def");
        assertEquals("", result());
        feed(100, 2, ""); feed(101, 24, "abc");
        assertEquals("abcdef", result());
        assertEquals(1, resets.get());
    }
    @Test public void newGameConnectionCannotBeContaminatedByOldTraffic() {
        feed(10, 2, ""); feed(11, 24, "old");
        feed(100, 2, "", 50001); feed(101, 24, "new", 50001);
        feed(14, 24, "stale"); feed(19, 4, "");
        feed(104, 24, "!", 50001);
        assertEquals("oldnew!", result());
    }
    @Test public void ackOnlyPacketsCannotCreateAnUnboundedGap() {
        feed(100, 2, "");
        for (int i = 0; i < 2000; i++) feed(400 + i, 16, "");
        feed(101, 24, "abc"); assertEquals("abc", result());
    }
    @Test(expected = IllegalStateException.class) public void unrecoverableGapTriggersRecoveryInsteadOfSkippingBytes() {
        feed(100, 2, "");
        for (int i = 0; i < 300; i++) feed(200 + i, 24, "x");
    }
    @Test public void finPayloadIsDelivered() {
        feed(100, 2, ""); feed(101, 17, "abc"); assertEquals("abc", result());
    }
}
