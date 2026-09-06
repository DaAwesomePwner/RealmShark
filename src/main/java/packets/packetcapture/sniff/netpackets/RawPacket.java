package packets.packetcapture.sniff.netpackets;

import java.time.Instant;
import java.util.Arrays;

/**
 * Raw packet constructor for retrieving packets of the wire.
 */
public class RawPacket {

    private final Instant instant;
    private final int payloadSize;
    private final byte[] payload;
    private final int dataLink;

    public static final int DLT_NULL = 0, DLT_EN10MB = 1, DLT_RAW = 12,
            DLT_LOOP = 108, DLT_IPV4 = 228;

    public static RawPacket newPacket(byte[] rawData, Instant ts) {
        return new RawPacket(rawData, ts);
    }

    public static RawPacket newPacket(byte[] rawData, Instant ts, int dataLink) {
        return new RawPacket(rawData, ts, dataLink);
    }

    public RawPacket(byte[] data, Instant ins) {
        this(data, ins, DLT_EN10MB);
    }

    public RawPacket(byte[] data, Instant ins, int dataLink) {
        instant = ins;
        payloadSize = data.length;
        payload = data;
        this.dataLink = dataLink;
    }

    public Instant getInstant() {
        return instant;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public byte[] getPayload() {
        return payload;
    }

    public EthernetPacket getNewEthernetPacket() {
        if (dataLink != DLT_EN10MB || payload.length < 14) return null;
        return new EthernetPacket(payload, this);
    }

    public int getDataLink() { return dataLink; }

    public static boolean supportsDataLink(int type) {
        return type == DLT_EN10MB || type == DLT_RAW || type == DLT_NULL
                || type == DLT_LOOP || type == DLT_IPV4;
    }

    /** Decode the link type reported by pcap, rather than assuming Ethernet. */
    public Ip4Packet getNewIp4Packet() {
        byte[] ip;
        EthernetPacket ethernet = null;
        switch (dataLink) {
            case DLT_EN10MB:
                if (payload.length < 34) return null;
                if (UtilNetPackets.getShort(payload, 12) == 0x8100 && payload.length < 38) return null;
                ethernet = getNewEthernetPacket();
                if (ethernet.getEtherType() != 0x0800) return null;
                ip = ethernet.getPayload();
                break;
            case DLT_RAW:
            case DLT_IPV4:
                ip = payload;
                break;
            case DLT_NULL:
            case DLT_LOOP:
                if (payload.length < 4) return null;
                java.nio.ByteOrder order = dataLink == DLT_LOOP
                        ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.nativeOrder();
                if (java.nio.ByteBuffer.wrap(payload, 0, 4).order(order).getInt() != 2) return null;
                ip = Arrays.copyOfRange(payload, 4, payload.length);
                break;
            default:
                return null;
        }
        if (ip.length < 20 || (ip[0] >>> 4 & 15) != 4) return null;
        int headerLength = (ip[0] & 15) * 4;
        int totalLength = UtilNetPackets.getShort(ip, 2);
        if (headerLength < 20 || headerLength > ip.length || totalLength < headerLength || totalLength > ip.length) return null;
        return new Ip4Packet(ip, ethernet);
    }

    @Override
    public String toString() {
        return "RawPacket{" +
                "\n instant=" + instant +
                "\n payloadSize=" + payloadSize +
                "\n payload=" + Arrays.toString(payload);
    }
}
