package tomato.gui.activity;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class ActivityLiveFilterBarTest {
    @Test public void liveRunFacetsLiveInTheDrawerAndTimelineKeepsVisitAndTypeInItsDrawer() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                ActivityPanel runs = new ActivityPanel(log, ActivityPanel.Mode.RUNS);
                FilterBar bar = find(runs, FilterBar.class, "activity-runs-filter-bar");
                assertNotNull(bar); assertFalse(bar.drawerOpen());
                assertTrue(SwingUtilities.isDescendingFrom(find(runs, JComboBox.class, "live-run-issues"), bar.drawerContent()));
                JTextField search = find(runs, JTextField.class, "activity-search");
                assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, bar.drawerContent()));
                ActivityQueries.Filters f = new ActivityQueries.Filters(); f.outcomes.add(ActivityQueries.Outcome.COMPLETED);
                f.captureIssues = ActivityQueries.Presence.ABSENT; f.maximumDurationMillis = 600_000L; runs.setRunFilters(f);
                assertEquals(Arrays.asList("Outcome: Completed", "No capture issues", "Duration ≤ 600 s"), ArchiveNativeSupport.chipLabels(bar));
                ArchiveNativeSupport.removeChip(bar, "No capture issues");
                assertEquals(ActivityQueries.Presence.ANY, runs.runFilters().captureIssues); assertEquals(1, runs.runFilters().outcomes.size());
                find(runs, AbstractButton.class, "activity-runs-clear-filters").doClick();
                assertTrue(runs.runFilters().outcomes.isEmpty()); assertEquals(0, bar.activeCount());
                ActivityPanel timeline = new ActivityPanel(log, ActivityPanel.Mode.TIMELINE);
                FilterBar timelineBar = find(timeline, FilterBar.class, "activity-timeline-filter-bar");
                // P6b: Timeline's visit and type moved from beside search into its Filters drawer (spec §6.7, R2 decision 8).
                assertNotNull("Timeline keeps visit and type in its Filters drawer", timelineBar.drawerContent());
                assertTrue(SwingUtilities.isDescendingFrom(find(timeline, JComboBox.class, "activity-visit"), timelineBar));
                assertTrue(SwingUtilities.isDescendingFrom(find(timeline, JComboBox.class, "activity-kind"), timelineBar));
                assertTrue(SwingUtilities.isDescendingFrom(find(timeline, JComboBox.class, "activity-visit"), timelineBar.drawerContent()));
                assertTrue(SwingUtilities.isDescendingFrom(find(timeline, JComboBox.class, "activity-kind"), timelineBar.drawerContent()));
            });
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
