package packets.packetcapture.pconstructor;

import org.junit.Test;
import packets.PacketType;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.encryption.RC4;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

public class PacketConstructorRecoveryTest {
    private final List<byte[]> received = new ArrayList<>();
    private final PacketConstructor constructor = new PacketConstructor(new PacketProcessor() {
        @Override public void processPackets(int type, int size, ByteBuffer data) {
            received.add(Arrays.copyOfRange(data.array(), 5, size));
        }
    }, new RC4(new byte[]{1,2,3}));

    private byte[] frame() {
        byte[] body = {10,20,30};
        new RC4(new byte[]{1,2,3}).decrypt(body);
        return ByteBuffer.allocate(8).putInt(8).put((byte)PacketType.CREATE_SUCCESS.getIndex()).put(body).array();
    }

    @Test public void capturedHandshakePreservesFirstPacketAfterStartingMidstream() {
        constructor.startResets();
        constructor.reset(); // Captured SYN.
        byte[] frame = frame();
        constructor.build(Arrays.copyOfRange(frame,0,3));
        constructor.build(Arrays.copyOfRange(frame,3,frame.length));
        assertEquals(1,received.size()); assertArrayEquals(new byte[]{10,20,30},received.get(0));
        constructor.reset(); constructor.build(frame());
        assertEquals(2,received.size()); assertArrayEquals(received.get(0),received.get(1));
    }

    @Test public void invalidLengthFailsPromptlyAndCanRecoverOnANewHandshake() {
        constructor.reset();
        for (int length : new int[]{-1,0,4,200001}) {
            try {
                constructor.build(ByteBuffer.allocate(4).putInt(length).array());
                fail("Invalid length must not poison all subsequent traffic");
            } catch (IllegalStateException expected) { constructor.reset(); }
        }
        constructor.build(frame()); assertEquals(1,received.size());
    }

    @Test public void startingMidstreamDoesNotPretendToHaveAValidCipher() {
        constructor.startResets(); constructor.build(frame()); constructor.build(frame());
        assertTrue(received.isEmpty());
    }
}
