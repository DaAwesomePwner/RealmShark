package packets.packetcapture;

import packets.Packet;
import packets.PacketType;
import packets.incoming.ip.IpAddress;
import packets.packetcapture.encryption.RC4;
import packets.packetcapture.encryption.RotMGRC4Keys;
import packets.packetcapture.logger.PacketLogger;
import packets.packetcapture.logger.DiscoveryLog;
import packets.packetcapture.pconstructor.PacketConstructor;
import packets.packetcapture.register.Register;
import packets.packetcapture.sniff.PProcessor;
import packets.packetcapture.sniff.Sniffer;
import packets.reader.BufferReader;
import packets.packetcapture.sniff.gui.MissingNpcapGUI;

import java.nio.ByteBuffer;

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
    private volatile String stopReason = "Capture ended unexpectedly. See logs/capture-health.log.";
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

    public String getStopReason() { return stopReason; }

    private void recordTerminalFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        StackTraceElement[] frames = cause.getStackTrace();
        String location = frames.length == 0 ? "" : " in " + frames[0].getClassName().replaceAll(".*\\.", "")
                + "." + frames[0].getMethodName();
        stopReason = "Capture stopped: " + cause.getClass().getSimpleName() + location + ". See logs/capture-health.log.";
        if (failure instanceof LinkageError) {
            String missingClass = CaptureDiagnostics.missingClassName(cause);
            stopReason = "Restart RealmShark using Launch-RealmShark.cmd: Java could not load an application class"
                    + (missingClass.isEmpty() ? "" : " (" + missingClass + ")")
                    + ". Restarting capture alone cannot repair this. See logs/capture-health.log.";
        }
        CaptureDiagnostics.record("Capture worker terminated", failure);
    }

    protected Sniffer createSniffer() { return new Sniffer(this); }

    private void reportCapture(String message) {
        try { captureStatus.accept(message); }
        catch (RuntimeException e) { CaptureDiagnostics.record("Capture status listener failed", e); }
    }

    /**
     * Start method for PacketProcessor.
     */
    public void run() {
        try { tapPackets(); }
        catch (RuntimeException | LinkageError e) { recordTerminalFailure(e); }
        finally { stoppedListener.run(); }
    }

    /**
     * Stop method for PacketProcessor.
     */
    public void stopSniffer() {
        synchronized (lifecycle) {
            stopRequested = true;
            if (sniffer != null) closeAttempt(sniffer);
            lifecycle.notifyAll();
        }
    }

    /**
     * Method to start the packet sniffer that will send packets back to receivedPackets.
     */
    public void tapPackets() {
        logger.startLogger();
        int consecutiveFailures = 0;
        while (!stopRequested) {
            Sniffer attempt = null;
            long retryDelay = 1000;
            String retryReason = "Capture interrupted or idle";
            try {
                CaptureDiagnostics.record("Starting capture attempt", null);
                incomingPacketConstructor.reset();
                outgoingPacketConstructor.reset();
                incomingPacketConstructor.startResets();
                outgoingPacketConstructor.startResets();
                decodedPackets = decodedTicks = 0;
                attempt = createSniffer();
                attempt.setStatusListener(message -> reportCapture(message + " | Decoded: " + decodedPackets + " | Ticks: " + decodedTicks));
                synchronized (lifecycle) {
                    if (stopRequested) break;
                    sniffer = attempt;
                }
                attempt.startSniffer();
                consecutiveFailures = 0;
            } catch (UnsatisfiedLinkError e) {
                stopReason = "Capture stopped: Npcap could not load. Install or repair Npcap, then restart RealmShark.";
                CaptureDiagnostics.record("Npcap unavailable", e);
                javax.swing.SwingUtilities.invokeLater(MissingNpcapGUI::new);
                break;
            } catch (Exception e) {
                CaptureDiagnostics.record("Capture attempt failed", e);
                retryDelay = 1000L << consecutiveFailures;
                consecutiveFailures = Math.min(consecutiveFailures + 1, 5);
                retryReason = "Capture failed (" + e.getClass().getSimpleName() + ")";
            } finally {
                if (attempt != null) closeAttempt(attempt);
                sniffer = null;
            }
            synchronized (lifecycle) {
                if (stopRequested) break;
                reportCapture(retryReason + "; reopening adapters in " + (retryDelay / 1000) + "s...");
                CaptureDiagnostics.record("Reopening adapters after capture ended or became idle", null);
                try { lifecycle.wait(retryDelay); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopReason = "Capture worker was interrupted. Start capture to resume.";
                    CaptureDiagnostics.record("Capture worker interrupted while waiting to retry", null);
                    break;
                }
            }
        }
    }

    private void closeAttempt(Sniffer attempt) {
        try { attempt.closeSniffers(); }
        catch (RuntimeException e) { CaptureDiagnostics.record("Capture cleanup failed", e); }
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
            DiscoveryLog.INSTANCE.observe(type, size, null, "unknown-id", 0);
            return;
        }
        logger.addPacket(type, size);
        Packet packetType = PacketType.getPacket(type).factory();
        packetType.setData(data.array());
        BufferReader pData = new BufferReader(data);

        try {
            packetType.deserialize(pData);
        } catch (Exception e) {
            DiscoveryLog.INSTANCE.decodeFailure(type, size, pData, e);
            return;
        }
        DiscoveryLog.INSTANCE.observe(type, size, packetType,
            pData.isBufferFullyParsed() ? "decoded" : "trailing-bytes", pData.getRemainingBytes());
        decodedPackets++;
        if (type == PacketType.NEWTICK.getIndex()) decodedTicks++;
        Register.INSTANCE.emitPacketLogs(packetType);
    }

    /**
     * Closes the sniffer for shutdown.
     */
    public void closeSniffer() {
        stopSniffer();
    }

    @Override
    public void resetIncoming() {
        DiscoveryLog.INSTANCE.boundary();
        incomingPacketConstructor.reset();
    }

    @Override
    public void resetOutgoing() {
        outgoingPacketConstructor.reset();
    }
}
