package packets.packetcapture;

/** Readiness comes from the capture worker, never from whether a view has matching rows. */
public enum CaptureState {
    STOPPED("Capture connection stopped"), STOPPING("Stopping capture connection"), WAITING("Waiting for game connection"),
    RECEIVING("Receiving decoded game data"), NPCAP_UNAVAILABLE("Npcap unavailable"),
    FAILED("Capture connection failed");

    private final String label;
    CaptureState(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
