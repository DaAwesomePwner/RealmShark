package tomato.backend.data;

import tomato.gui.stats.FameTrackerGUI;

public class FameTracker {
    public static void trackFame(int charId, long exp, long time) {
        long fame = (exp + 40071) / 2000;
        // The history owns change detection so resetting it also resets its baseline.
        FameTrackerGUI.trackFame(charId, fame, time);
    }
}
