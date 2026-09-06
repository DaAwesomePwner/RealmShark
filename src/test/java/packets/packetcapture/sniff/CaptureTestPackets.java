package packets.packetcapture.sniff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import packets.packetcapture.sniff.netpackets.RawPacket;

final class CaptureTestPackets {
    static byte[] ip(int src, int dst) {
        byte[] bytes = new byte[43];
        bytes[0] = 0x45; bytes[3] = 43; bytes[8] = 64; bytes[9] = 6;
        bytes[12] = 10; bytes[15] = 1; bytes[16] = 10; bytes[19] = 2;
        bytes[20] = (byte)(src >>> 8); bytes[21] = (byte)src;
        bytes[22] = (byte)(dst >>> 8); bytes[23] = (byte)dst;
        bytes[27] = 1; bytes[32] = 0x50; bytes[33] = 0x18;
        bytes[40] = 1; bytes[41] = 2; bytes[42] = 3;
        return bytes;
    }

    static RawPacket packet(int link, boolean vlan, int src, int dst) {
        byte[] ip = ip(src, dst);
        int prefix = link == RawPacket.DLT_EN10MB ? (vlan ? 18 : 14)
                : link == RawPacket.DLT_NULL || link == RawPacket.DLT_LOOP ? 4 : 0;
        byte[] bytes = new byte[ip.length + prefix];
        System.arraycopy(ip, 0, bytes, prefix, ip.length);
        if (link == RawPacket.DLT_EN10MB) {
            bytes[12] = vlan ? (byte)0x81 : 8;
            if (vlan) bytes[16] = 8;
        } else if (prefix == 4) {
            ByteBuffer.wrap(bytes).order(link == RawPacket.DLT_LOOP ? ByteOrder.BIG_ENDIAN : ByteOrder.nativeOrder()).putInt(2);
        }
        return RawPacket.newPacket(bytes, Instant.ofEpochSecond(123, 456000), link);
    }
}
