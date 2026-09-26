package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.HistoryTables;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.link.VisitRef;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Selected-window analysis beside a {@link CombatTimelineChart}: raw HP/MP extrema and, per condition flag,
 * active / observed / unknown seconds with observed uptime. Zero-active lanes stay listed. Timeline handoffs
 * carry the exact saved visit and half-open bounds and are offered only when the navigator accepts them. EDT only.
 */
final class ResourceWindowPanel extends JPanel implements Scrollable {
    private final CombatTimelineChart chart;
    private final Supplier<VisitRef> reference;
    private final JTextArea summary = ContentStyle.wrappingText("", 3);
    private final JCheckBox allFlags = new JCheckBox("Include flags never active in this visit");
    private final JPanel lanes = new JPanel(new BorderLayout());
    private final JButton around = new JButton("Timeline ±30 s around sample"), window = new JButton("Open Timeline for this window");
    private final JTextArea handoff = ContentStyle.wrappingText("");
    private ResourceWindow analysis;

    ResourceWindowPanel(CombatTimelineChart chart, Supplier<VisitRef> reference) {
        super(new BorderLayout(0, 6));
        this.chart = chart; this.reference = reference;
        setName("resource-window");
        summary.setName("resource-window-summary"); summary.setFocusable(true);
        summary.getAccessibleContext().setAccessibleName("Selected resource window summary");
        allFlags.setName("resource-window-all-flags");
        around.setName("resource-timeline-around"); window.setName("resource-timeline-window");
        handoff.setName("resource-window-handoff"); handoff.getAccessibleContext().setAccessibleName("Timeline handoff availability");
        JButton whole = new JButton("Whole visit"), clear = new JButton("Clear window");
        whole.setName("resource-window-whole"); clear.setName("resource-window-clear");
        whole.addActionListener(e -> { ActivityJournal.Visit visit = chart.getVisit(); if (visit != null) chart.setSelection(visit.started, chart.wholeEnd()); });
        clear.addActionListener(e -> chart.setSelection(null, null));
        allFlags.addActionListener(e -> recompute());
        around.addActionListener(e -> open(aroundRoute()));
        window.addActionListener(e -> open(windowRoute()));
        JPanel controls = ContentStyle.controls();
        controls.add(whole); controls.add(clear); controls.add(allFlags); controls.add(around); controls.add(window);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(summary, BorderLayout.NORTH); top.add(controls, BorderLayout.CENTER); top.add(handoff, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH); add(lanes, BorderLayout.CENTER);
        chart.addPropertyChangeListener("selection", e -> recompute());
        chart.addPropertyChangeListener("inspectionSummary", e -> recompute());
        recompute();
    }

    ResourceWindow analysis() { return analysis; }

    /** Condition rows the lanes table always keeps visible before its host scrolls; more rows grow it up to MAX. */
    static final int MINIMUM_LANE_ROWS = 4, MAXIMUM_LANE_ROWS = 10;

    /**
     * Hosts this panel in a scroll pane whose minimum height is the panel's own minimum, so an enclosing page
     * (for example the saved Resources split) scrolls instead of shrinking the lanes table to a row or two.
     */
    static JScrollPane scroll(ResourceWindowPanel panel) {
        JScrollPane scroll = new JScrollPane(panel) {
            @Override public Dimension getMinimumSize() {
                Insets border = getInsets();
                return new Dimension(super.getMinimumSize().width, panel.getMinimumSize().height + border.top + border.bottom);
            }
        };
        scroll.setName("resource-window-scroll");
        return scroll;
    }

    /** Summary, controls and handoff notes at their wrapped height plus {@value #MINIMUM_LANE_ROWS} lane rows. */
    @Override public Dimension getMinimumSize() {
        Component top = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.NORTH);
        int lanesHeight = 0;
        for (Component c : lanes.getComponents()) lanesHeight = Math.max(lanesHeight, c.getMinimumSize().height);
        return new Dimension(0, (top == null ? 0 : top.getPreferredSize().height) + ((BorderLayout) getLayout()).getVgap() + lanesHeight);
    }

    /** Lanes table scroll sized in whole rows: at least {@value #MINIMUM_LANE_ROWS}, up to {@value #MAXIMUM_LANE_ROWS} when there are more. */
    private static JComponent lanesPage(JTable table, String note) {
        JScrollPane scroll = new JScrollPane(table) {
            private int height(int rows) {
                Insets border = getInsets();
                return table.getTableHeader().getPreferredSize().height + table.getRowHeight() * rows
                    + getHorizontalScrollBar().getPreferredSize().height + border.top + border.bottom;
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, height(MINIMUM_LANE_ROWS)); }
            @Override public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width, height(Math.max(MINIMUM_LANE_ROWS, Math.min(MAXIMUM_LANE_ROWS, table.getRowCount()))));
            }
        };
        scroll.setName("resource-window-lanes-scroll");
        JTextArea text = ContentStyle.wrappingText(note);
        JPanel panel = new JPanel(new BorderLayout(0, 6)) {
            @Override public Dimension getMinimumSize() {
                return new Dimension(0, scroll.getMinimumSize().height + 6 + text.getPreferredSize().height);
            }
        };
        panel.add(scroll); panel.add(text, BorderLayout.SOUTH);
        return panel;
    }

    // Hosted in a JScrollPane: track the viewport width so the summary and controls wrap instead of the
    // lanes table's natural width forcing a sideways page. The lanes table keeps its own horizontal scroll.
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height;
    }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 32; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(32, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 32);
    }

    void recompute() {
        ActivityJournal.Visit visit = chart.getVisit();
        lanes.removeAll();
        if (visit == null) {
            analysis = null;
            summary.setText("Select a recorded visit, then drag across the HP/MP plots or use [ and ] at inspected samples to choose a window.");
        } else {
            boolean selected = chart.getSelectionStart() != null;
            long from = selected ? chart.getSelectionStart() : visit.started, until = selected ? chart.getSelectionEnd() : chart.wholeEnd();
            analysis = ResourceWindow.of(visit, from, until, allFlags.isSelected());
            summary.setText(analysis.summary((selected ? "Selected window " : "Whole visit (no window selected) ")
                + DisplayFormat.formatDurationSeconds(from - visit.started, 1) + " s – " + DisplayFormat.formatDurationSeconds(until - visit.started, 1) + " s"));
            List<Object[]> rows = new ArrayList<>();
            for (ResourceWindow.Lane lane : analysis.lanes)
                rows.add(new Object[]{CombatTimelineChart.label(lane.name), lane.extra ? "Extra flags" : "Primary flags",
                    // Active time is unknown, not zero, where nothing in the window was observed (like uptime).
                    lane.observed <= 0 ? null : lane.active / 1000.0, lane.observed / 1000.0, lane.unknown / 1000.0, lane.observedUptime()});
            JTable table = HistoryTables.table("resource-window-lanes", new String[]{"Condition", "Family", "Active s", "Observed s", "Unknown s", "Observed uptime %"},
                new Class<?>[]{String.class, String.class, Double.class, Double.class, Double.class, Double.class}, rows);
            lanes.add(lanesPage(table, rows.isEmpty() ? "No condition flags were recorded for this visit; coverage of every flag is unknown."
                : "Local character only. Intervals are clipped to the window and unioned; zero-active lanes remain listed. Unknown is not inactive."));
        }
        updateHandoffs();
        lanes.revalidate(); lanes.repaint();
    }

    private Route aroundRoute() {
        VisitRef ref = reference.get(); Long time = chart.getInspectedTime();
        return ref == null || time == null ? null : ActivityRoutes.timelineAround(ref, time, ActivityRoutes.AROUND_MILLIS);
    }
    private Route windowRoute() {
        VisitRef ref = reference.get();
        return ref == null || chart.getSelectionStart() == null ? null : Route.to(tomato.gui.route.Destination.TIMELINE).withVisit(ref).withBounds(chart.getSelectionStart(), chart.getSelectionEnd());
    }
    private void updateHandoffs() {
        List<String> reasons = new ArrayList<>();
        enable(around, aroundRoute(), reference.get() == null ? "Timeline around a sample needs a saved visit (Browse saved)" : "Inspect a sample first (Left / Right in the chart)", reasons);
        enable(window, windowRoute(), reference.get() == null ? "Timeline for a window needs a saved visit (Browse saved)" : "Select a window first", reasons);
        handoff.setText(String.join("; ", reasons));
        handoff.setVisible(!reasons.isEmpty());
    }
    private static void enable(JButton button, Route route, String missing, List<String> reasons) {
        String reason = route == null ? missing : ActivityRoutes.unavailable(route, button.getText());
        button.setEnabled(reason == null);
        button.setToolTipText(reason == null ? button.getText() + " for this exact visit" : reason);
        if (reason != null) reasons.add(reason);
    }
    private void open(Route route) {
        if (route == null || !Navigator.current().open(route)) { handoff.setText("Timeline could not open this exact visit window; nothing was changed."); handoff.setVisible(true); }
    }
}
