package packets.packetcapture;

import packets.Packet;
import packets.PacketType;
import packets.incoming.ip.IpAddress;
import packets.packetcapture.encryption.RC4;
import packets.packetcapture.encryption.RotMGRC4Keys;
import packets.packetcapture.logger.PacketLogger;
import packets.packetcapture.pconstructor.PacketConstructor;
import packets.packetcapture.register.Register;
import packets.packetcapture.sniff.PProcessor;
import packets.packetcapture.sniff.Sniffer;
import packets.reader.BufferReader;
import packets.packetcapture.sniff.gui.MissingNpcapGUI;
import util.Util;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The core class to process packets. First the network tap is sniffed to receive all packets. The packets
 * are filtered for port 2050, the rotmg port, and TCP packets. Then the packets are stitched together in
 * streamConstructor and rotmgConstructor class. After the packets are constructed the RC4 cipher is used
 * decrypt the data. The data is then matched with target classes and emitted through the registry.
 */
public class PacketProcessor extends Thread implements PProcessor {
    private final PacketConstructor incomingPacketConstructor;
    private final PacketConstructor outgoingPacketConstructor;
    private volatile Sniffer sniffer;
    private volatile boolean stopRequested;
    private volatile java.util.function.Consumer<String> captureStatus = message -> {};
    private volatile Runnable stoppedListener = () -> {};
    private final Object lifecycle = new Object();
    private volatile long decodedPackets;
    private volatile long decodedTicks;
    private final PacketLogger logger;
    private final byte[] srcAddr;

    /**
     * Basic constructor of packetProcessor
     * TODO: Add linux and mac support later
     */
    public PacketProcessor() {
        setName("RealmShark packet processor");
        incomingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.INCOMING_STRING));
        outgoingPacketConstructor = new PacketConstructor(this, new RC4(RotMGRC4Keys.OUTGOING_STRING));
        logger = new PacketLogger();
        srcAddr = new byte[4];
    }

    public void setCaptureStatusListener(java.util.function.Consumer<String> listener) {
        captureStatus = listener == null ? message -> {} : listener;
    }

    public void setStoppedListener(Runnable listener) {
        stoppedListener = listener == null ? () -> {} : listener;
    }

    protected Sniffer createSniffer() { return new Sniffer(this); }

    private void reportCapture(String message) {
        captureStatus.accept(message);
    }

    /**
     * Start method for PacketProcessor.
     */
    public void run() {
        try { tapPackets(); }
        catch (RuntimeException | LinkageError e) { CaptureDiagnostics.record("Capture worker terminated", e); }
        finally { stoppedListener.run(); }
    }

    /**
     * Stop method for PacketProcessor.
     */
    public void stopSniffer() {
        synchronized (lifecycle) {
            stopRequested = true;
            if (sniffer != null) sniffer.closeSniffers();
            lifecycle.notifyAll();
        }
    }

    /**
     * Method to start the packet sniffer that will send packets back to receivedPackets.
     */
    public void tapPackets() {
        logger.startLogger();
        while (!stopRequested) {
            incomingPacketConstructor.reset();
            outgoingPacketConstructor.reset();
            incomingPacketConstructor.startResets();
            outgoingPacketConstructor.startResets();
            decodedPackets = decodedTicks = 0;
            Sniffer attempt = createSniffer();
            attempt.setStatusListener(message -> reportCapture(message + " | Decoded: " + decodedPackets + " | Ticks: " + decodedTicks));
            synchronized (lifecycle) {
                if (stopRequested) break;
                sniffer = attempt;
            }
            try {
                CaptureDiagnostics.record("Starting capture attempt", null);
                attempt.startSniffer();
            } catch (UnsatisfiedLinkError e) {
                CaptureDiagnostics.record("Npcap unavailable", e);
                javax.swing.SwingUtilities.invokeLater(MissingNpcapGUI::new);
                break;
            } catch (Exception e) {
                CaptureDiagnostics.record("Capture attempt failed", e);
            } finally {
                attempt.closeSniffers();
            }
            synchronized (lifecycle) {
                if (stopRequested) break;
                reportCapture("Capture interrupted or idle; reopening adapters...");
                CaptureDiagnostics.record("Reopening adapters after capture ended or became idle", null);
                try { lifecycle.wait(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    /**
     * Incoming byte data received from incoming TCP packets.
     *
     * @param data    Incoming byte stream
     * @param srcAddr Source IP of incoming packets.
     */
    @Override
    public void incomingStream(byte[] data, byte[] srcAddr) {
        if (stopRequested) return;
        logger.addIncoming(data.length);
        ipEmitter(srcAddr);
        incomingPacketConstructor.build(data);
        Register.INSTANCE.emitLogs(logger);
    }

    /**
     * Outgoing byte data received from outgoing TCP packets.
     *
     * @param data Outgoing byte stream
     */
    @Override
    public void outgoingStream(byte[] data, byte[] srcAddr) {
        if (stopRequested) return;
        logger.addOutgoing(data.length);
        outgoingPacketConstructor.build(data);
        Register.INSTANCE.emitLogs(logger);
    }

    /**
     * Emits IP changes as incoming packet.
     *
     * @param srcIp Source IP of incoming packets.
     */
    private void ipEmitter(byte[] srcIp) {
        for (int i = 0; i < srcAddr.length; i++) {
            if (srcAddr[i] != srcIp[i]) {
                System.arraycopy(srcIp, 0, srcAddr, 0, srcAddr.length);
                Register.INSTANCE.emitPacketLogs(new IpAddress(srcIp));
                return;
            }
        }
    }

    /**
     * Completed packets constructed by stream and rotmg constructor returned to packet constructor.
     * Decoded by the cipher and sent back to the processor to be emitted to subscribed users.
     *
     * @param type Constructed packet type.
     * @param size size of the packet.
     * @param data Constructed packet data.
     */
    public void processPackets(int type, int size, ByteBuffer data) {
        if (stopRequested) return;
        if (!PacketType.containsKey(type)) {
            System.err.println("Unknown packet type:" + type + " Data:" + Arrays.toString(data.array()));
            return;
        }
        logger.addPacket(type, size);
        Packet packetType = PacketType.getPacket(type).factory();
        packetType.setData(data.array());
        BufferReader pData = new BufferReader(data);

        try {
            packetType.deserialize(pData);
            if (!pData.isBufferFullyParsed()) {
                pData.printError(packetType);
            }
        } catch (Exception e) {
            Util.printLogs("Buffer exploded: " + pData.getIndex() + "/" + pData.size());
            debugPackets(type, data.array());
            return;
        }
        decodedPackets++;
        if (type == PacketType.NEWTICK.getIndex()) decodedTicks++;
        Register.INSTANCE.emitPacketLogs(packetType);
    }

    /**
     * Helper for debugging packets
     */
    private void debugPackets(int type, byte[] data) {
        Packet packetType = PacketType.getPacket(type).factory();
        Util.printLogs(PacketType.byOrdinal(type) + " " + packetType);
        Util.printLogs(Arrays.toString(data));
    }

    /**
     * Closes the sniffer for shutdown.
     */
    public void closeSniffer() {
        stopSniffer();
    }

    @Override
    public void resetIncoming() {
        incomingPacketConstructor.reset();
    }

    @Override
    public void resetOutgoing() {
        outgoingPacketConstructor.reset();
    }
}
