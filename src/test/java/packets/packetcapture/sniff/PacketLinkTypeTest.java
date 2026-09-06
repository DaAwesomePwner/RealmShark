package packets.packetcapture.sniff;

import org.junit.Test;
import packets.packetcapture.sniff.netpackets.*;
import java.time.Instant;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PacketLinkTypeTest {
    @Test public void allSupportedLinksDecodeTheSameIncomingAndOutgoingStream() {
        for (int link : new int[] {0, 1, 12, 108, 228}) {
            for (boolean incoming : new boolean[] {true, false}) {
                RawPacket raw = CaptureTestPackets.packet(link, false, incoming ? 2050 : 55000, incoming ? 55000 : 2050);
                assertEquals(link, raw.getDataLink());
                Ip4Packet ip = raw.getNewIp4Packet(); assertNotNull(ip);
                assertTrue(Sniffer.isGamePacket(ip));
                TcpPacket tcp = ip.getNewTcpPacket();
                assertEquals(incoming ? 2050 : 55000, tcp.getSrcPort());
                assertEquals(incoming ? 55000 : 2050, tcp.getDstPort());
                assertArrayEquals(new byte[] {1, 2, 3}, tcp.getPayload());
                assertEquals(Instant.ofEpochSecond(123, 456000), raw.getInstant());
                if (link != 1) assertNull(raw.getNewEthernetPacket());
            }
        }
    }

    @Test public void existingEthernetFactoryAndVlanFramesStillWork() {
        RawPacket tagged = CaptureTestPackets.packet(1, true, 2050, 55000);
        assertTrue(Sniffer.isGamePacket(tagged.getNewIp4Packet()));
        RawPacket ethernet = CaptureTestPackets.packet(1, false, 2050, 55000);
        assertNotNull(RawPacket.newPacket(ethernet.getPayload(), Instant.EPOCH).getNewEthernetPacket().getNewIp4Packet());
    }

    @Test public void rawVpnFramesWouldNotDecodeWithTheOldEthernetAssumption() {
        RawPacket vpn = CaptureTestPackets.packet(12, false, 2050, 55000);
        assertNull(RawPacket.newPacket(vpn.getPayload(), Instant.EPOCH).getNewEthernetPacket().getNewIp4Packet());
        assertNotNull(vpn.getNewIp4Packet());
    }

    @Test public void malformedTruncatedIpv6AndUnsupportedFramesCannotSelectAnAdapter() {
        for (int type : new int[] {0, 1, 12, 108, 228, 999}) {
            for (int length = 0; length < 43; length++) {
                RawPacket valid = CaptureTestPackets.packet(type, false, 2050, 55000);
                assertNull(RawPacket.newPacket(Arrays.copyOf(valid.getPayload(), length), Instant.EPOCH, type).getNewIp4Packet());
            }
        }
        byte[] ipv6 = CaptureTestPackets.ip(2050, 55000); ipv6[0] = 0x60;
        assertNull(RawPacket.newPacket(ipv6, Instant.EPOCH, 12).getNewIp4Packet());
        byte[] badIhl = CaptureTestPackets.ip(2050, 55000); badIhl[0] = 0x41;
        assertNull(RawPacket.newPacket(badIhl, Instant.EPOCH, 12).getNewIp4Packet());
        byte[] badTcp = CaptureTestPackets.ip(2050, 55000); badTcp[32] = 0x10;
        assertFalse(Sniffer.isGamePacket(RawPacket.newPacket(badTcp, Instant.EPOCH, 12).getNewIp4Packet()));
        assertFalse(Sniffer.isGamePacket(CaptureTestPackets.packet(12, false, 443, 55000).getNewIp4Packet()));
    }
}
