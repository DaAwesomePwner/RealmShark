package packets.packetcapture.sniff;

import packets.packetcapture.sniff.ardikars.NativeBridge;
import packets.packetcapture.CaptureDiagnostics;
import packets.packetcapture.sniff.assembly.Ip4Defragmenter;
import packets.packetcapture.sniff.assembly.TcpStreamErrorHandler;
import packets.packetcapture.sniff.assembly.TcpStreamBuilder;
import packets.packetcapture.sniff.netpackets.Ip4Packet;
import packets.packetcapture.sniff.netpackets.RawPacket;
import packets.packetcapture.sniff.netpackets.TcpPacket;
import pcap.spi.Address;
import pcap.spi.Interface;
import pcap.spi.Pcap;
import pcap.spi.Service;
import pcap.spi.exception.ErrorException;
import pcap.spi.exception.error.*;
import pcap.spi.option.DefaultLiveOptions;

import java.net.Inet4Address;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;

/** Discover the interface carrying game traffic, including raw-IP VPN tunnels. */
public class Sniffer {
    private static final int PORT = 2050;
    private final CaptureQueue<Pcap> queue = new CaptureQueue<>();
    // A handle is closed only by its reader after pcap_loop has returned.
    private final Set<Pcap> active = Collections.newSetFromMap(new IdentityHashMap<Pcap, Boolean>());
    private final TcpStreamBuilder incoming;
    private final TcpStreamBuilder outgoing;
    private boolean openingFinished;
    private volatile Consumer<String> statusListener = message -> {};
    private volatile String selectedName = "adapter";
    private long receivedPackets;

    public Sniffer(PProcessor processor) {
        incoming = new TcpStreamBuilder(processor::resetIncoming, processor::incomingStream);
        outgoing = new TcpStreamBuilder(processor::resetOutgoing, processor::outgoingStream);
    }

    public void setStatusListener(Consumer<String> listener) {
        statusListener = listener == null ? message -> {} : listener;
    }

    private void report(String message) {
        System.out.println("[Capture] " + message);
        statusListener.accept(message);
    }

    public void startSniffer() throws ErrorException, RadioFrequencyModeNotSupportedException,
            ActivatedException, InterfaceNotSupportTimestampTypeException,
            PromiscuousModePermissionDeniedException, InterfaceNotUpException,
            PermissionDeniedException, NoSuchDeviceException, TimestampPrecisionNotSupportedException {
        if (queue.isStopped()) return;
        try {
            Service service = Service.Creator.create("PcapService");
            Interface[] interfaces = NativeBridge.getInterfaces(service);
            report("Looking for game traffic on available adapters...");
            for (Interface adapter : interfaces) {
                if (queue.isStopped()) break;
                if (!usableOnMac(adapter)) continue;
                Pcap pcap = null;
                try {
                    DefaultLiveOptions options = new DefaultLiveOptions();
                    options.snapshotLength(262144);
                    options.timeout(250);
                    pcap = service.live(adapter, options);
                    if (!RawPacket.supportsDataLink(pcap.datalink())) {
                        System.err.println("[Capture] Unsupported link type " + pcap.datalink() + " on " + adapter.description());
                        pcap.close();
                        continue;
                    }
                    pcap.setFilter("tcp port " + PORT, true);
                    startPacketSniffer(pcap, adapter.description() == null ? adapter.name() : adapter.description());
                    pcap = null; // The reader now owns this handle.
                } catch (Exception e) {
                    if (pcap != null) pcap.close();
                    System.err.println("[Capture] Cannot open " + adapter.description() + ": " + e.getMessage());
                }
            }
            synchronized (active) {
                openingFinished = true;
                if (active.isEmpty()) queue.stop();
            }
            if (queue.isStopped()) {
                report("Capture stopped; no active adapter. Check Npcap and restart capture.");
                return;
            }
            closeUnusedSniffers();
            processBufferedPackets();
        } finally {
            closeSniffers();
        }
    }

    private boolean usableOnMac(Interface adapter) {
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) return true;
        if (adapter.addresses() == null) return false;
        for (Address addr : adapter.addresses()) {
            if (addr.address() instanceof Inet4Address) {
                Inet4Address ip = (Inet4Address) addr.address();
                if (!ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) return true;
            }
        }
        return false;
    }

    public void startPacketSniffer(Pcap pcap) {
        startPacketSniffer(pcap, "Network adapter");
    }

    private void startPacketSniffer(Pcap pcap, String name) {
        synchronized (active) {
            if (queue.isStopped()) { pcap.close(); return; }
            active.add(pcap);
        }
        Thread reader = new Thread(() -> {
            try {
                NativeBridge.loop(pcap, -1, packet -> acceptPacket(pcap, name, packet));
            } catch (RuntimeException e) {
                CaptureDiagnostics.record("Adapter reader failed", e);
                System.err.println("[Capture] " + name + ": " + e.getMessage());
            } finally {
                synchronized (active) {
                    active.remove(pcap);
                    try { pcap.close(); }
                    finally {
                        if (queue.owner() == pcap || (openingFinished && active.isEmpty())) queue.stop();
                    }
                }
            }
        }, "RealmShark capture: " + name);
        reader.setDaemon(true);
        reader.start();
    }

    void acceptPacket(Pcap source, String name, RawPacket packet) {
        if (packet == null || queue.isStopped()) return;
        Ip4Packet ip = packet.getNewIp4Packet();
        // Only a decodable game packet can select an adapter.
        boolean fragmentOnSelectedAdapter = queue.owner() == source && ip != null
                && ip.getProtocol() == 6 && ip.getFragmentOffset() != 0;
        if (!fragmentOnSelectedAdapter && !isGamePacket(ip)) return;
        boolean first;
        synchronized (queue) {
            first = queue.owner() == null;
            if (!queue.offer(source, packet)) return;
            TcpStreamErrorHandler.INSTANCE.logTCPPacket(packet);
        }
        if (first) {
            selectedName = name;
            report("Listening on " + name + " (link type " + packet.getDataLink() + ")");
        }
    }

    static boolean isGamePacket(Ip4Packet ip) {
        if (ip == null || ip.getProtocol() != 6 || ip.getFragmentOffset() != 0 || ip.getPayloadLength() < 20) return false;
        byte[] tcp = ip.getPayload();
        int tcpHeader = (tcp[12] >>> 4 & 15) * 4;
        if (tcpHeader < 20 || tcpHeader > tcp.length) return false;
        int src = (tcp[0] & 255) << 8 | tcp[1] & 255;
        int dst = (tcp[2] & 255) << 8 | tcp[3] & 255;
        return src == PORT || dst == PORT;
    }

    private void closeUnusedSniffers() {
        try {
            Pcap selected = queue.awaitOwnerFor(15000);
            if (selected == null) {
                report("No game traffic; refreshing available adapters...");
                closeSniffers();
                return;
            }
            synchronized (active) {
                for (Pcap pcap : active) if (pcap != selected) pcap.breakLoop();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeSniffers();
        }
    }

    void processBufferedPackets() {
        try {
            RawPacket packet;
            long lastReport = 0;
            while (!queue.isStopped()) {
                packet = queue.poll(15000);
                if (packet == null) {
                    if (!queue.isStopped()) report("No game traffic for 15 seconds; checking the VPN adapter again...");
                    break;
                }
                receivedPackets++;
                Ip4Packet ip = Ip4Defragmenter.defragment(packet.getNewIp4Packet());
                if (ip == null) continue;
                if (!isGamePacket(ip)) continue;
                TcpPacket tcp = ip.getNewTcpPacket();
                if (tcp == null) continue;
                if (tcp.getSrcPort() == PORT) incoming.streamBuilder(tcp);
                else if (tcp.getDstPort() == PORT) outgoing.streamBuilder(tcp);
                long now = System.nanoTime();
                if (now - lastReport >= 5000000000L) {
                    lastReport = now;
                    report("Listening on " + selectedName + " | TCP packets: " + receivedPackets);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void closeSniffers() {
        queue.stop(); // Wake Java waiters even when no packet has arrived.
        synchronized (active) {
            for (Pcap pcap : active) {
                try { pcap.breakLoop(); }
                catch (RuntimeException e) { CaptureDiagnostics.record("Unable to interrupt adapter reader", e); }
            }
        }
    }
}
