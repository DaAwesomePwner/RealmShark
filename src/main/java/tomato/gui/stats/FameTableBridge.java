package tomato.gui.stats;

/**
 * Bridge connecting FameTablePanel and FameTrackerGUI.
 * Provides a centralized point for fame updates and session management. Its monitor
 * orders model commands, boundary snapshots and async save submissions. No rendering
 * or disk I/O runs under it; rendering takes detached model snapshots independently.
 */
public final class FameTableBridge {

    private static final FameTableBridge INSTANCE = new FameTableBridge();

    private FameTablePanel fameTablePanel;
    private FameTrackerGUI fameTrackerGUI;

    private FameTableBridge() {}

    public static FameTableBridge getInstance() {
        return INSTANCE;
    }

    public static void initialize() {
        getInstance();
    }

    // --- Registration ---

    public synchronized void setFameTablePanel(FameTablePanel panel) {
        this.fameTablePanel = panel;
    }

    public synchronized void setFameTrackerGUI(FameTrackerGUI gui) {
        this.fameTrackerGUI = gui;
    }

    // --- Fame Updates ---

    /** A captured observation is indivisible with respect to session saves and resets. */
    public static void observeFame(int charId, long fame, long time, String className) {
        FameTableBridge bridge = INSTANCE;
        synchronized (bridge) {
            tomato.history.AppHistory.fame(charId, fame, time, className);
            if (bridge.fameTrackerGUI != null) bridge.fameTrackerGUI.trackCapturedFame(charId, fame, time);
            if (bridge.fameTablePanel != null) bridge.fameTablePanel.updateFame(charId, fame, time, className);
        }
    }

    /** Table-only compatibility entry point; captured observations use observeFame. */
    public static void updateFame(
        int charId,
        long fame,
        long time,
        String className
    ) {
        FameTableBridge bridge = INSTANCE;
        synchronized (bridge) {
            if (bridge.fameTablePanel != null) bridge.fameTablePanel.updateFame(charId, fame, time, className);
        }
    }

    // --- Session Management ---

    synchronized void tableChanged(FameTablePanel source) {
        if (source == fameTablePanel && fameTrackerGUI != null) fameTrackerGUI.tableChanged();
    }

    public synchronized void triggerAutoSave() {
        if (fameTrackerGUI != null) {
            fameTrackerGUI.triggerAutoSave();
        }
    }

    public synchronized void triggerMapChangeAutoSave() {
        if (fameTrackerGUI != null) {
            fameTrackerGUI.triggerMapChangeAutoSave();
        }
    }

    public synchronized void clearCurrentSessionFile() {
        if (fameTrackerGUI != null) {
            fameTrackerGUI.clearCurrentSessionFile();
        }
    }

    public synchronized void startNewSessionFile() {
        if (fameTrackerGUI != null) {
            fameTrackerGUI.startNewSessionFile();
        }
    }

    public synchronized boolean hasFameGainedSinceLastSave() {
        return (
            fameTrackerGUI != null &&
            fameTrackerGUI.hasFameGainedSinceLastSave()
        );
    }
}
