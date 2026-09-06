package packets.packetcapture.sniff;

import org.junit.Test;
import pcap.spi.Pcap;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SnifferRoutingTest {
    @Test(timeout = 4000) public void vpnPacketsQueuedBeforeProcessingReachBothOriginalStreamHandlers() throws Exception {
        BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        Sniffer sniffer = new Sniffer(new PProcessor() {
            public void resetIncoming() {}
            public void resetOutgoing() {}
            public void incomingStream(byte[] bytes, byte[] address) { calls.add("incoming:" + bytes.length); }
            public void outgoingStream(byte[] bytes, byte[] address) { calls.add("outgoing:" + bytes.length); }
        });
        Pcap vpn = mockHandle(), other = mockHandle();
        sniffer.acceptPacket(other, "unrelated", CaptureTestPackets.packet(1, false, 443, 55000));
        sniffer.acceptPacket(vpn, "WireGuard Tunnel", CaptureTestPackets.packet(12, false, 2050, 55000));
        sniffer.acceptPacket(other, "duplicate", CaptureTestPackets.packet(1, false, 2050, 55000));
        sniffer.acceptPacket(vpn, "WireGuard Tunnel", CaptureTestPackets.packet(12, false, 55000, 2050));
        Thread consumer = new Thread(sniffer::processBufferedPackets);
        consumer.start();
        try {
            assertEquals("incoming:3", calls.poll(1, TimeUnit.SECONDS));
            assertEquals("outgoing:3", calls.poll(1, TimeUnit.SECONDS));
            assertTrue(calls.isEmpty());
        } finally { sniffer.closeSniffers(); consumer.join(1000); }
        assertFalse("Stop must release the consumer", consumer.isAlive());
    }
    private static Pcap mockHandle() {
        return (Pcap) Proxy.newProxyInstance(Pcap.class.getClassLoader(), new Class<?>[] {Pcap.class}, (p, method, args) -> null);
    }
}
