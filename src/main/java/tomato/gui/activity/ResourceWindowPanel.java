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
final class ResourceWindowPanel extends JPanel {
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
                    lane.active / 1000.0, lane.observed / 1000.0, lane.unknown / 1000.0, lane.observedUptime()});
            JTable table = HistoryTables.table("resource-window-lanes", new String[]{"Condition", "Family", "Active s", "Observed s", "Unknown s", "Observed uptime %"},
                new Class<?>[]{String.class, String.class, Double.class, Double.class, Double.class, Double.class}, rows);
            lanes.add(HistoryTables.page(table, rows.isEmpty() ? "No condition flags were recorded for this visit; coverage of every flag is unknown."
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
