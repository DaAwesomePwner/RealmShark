package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.link.VisitRef;

import javax.swing.*;
import java.awt.*;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Selected-visit workbench: grouped evidence plus keyboard-accessible actions that carry the exact session +
 * visit reference. An action is enabled only when the installed navigator accepts its route; otherwise the
 * reason is shown beside the actions. EDT only.
 */
final class RunWorkbenchPanel extends JPanel {
    private final JTextArea unavailable = ContentStyle.wrappingText("");

    /** Shows the grouped evidence in {@code evidence} (the view's accessible detail area) and adds the actions. */
    RunWorkbenchPanel(JTextArea evidence, VisitRef ref, ActivityQueries.Row row, ActivityJournal.Visit visit, ZoneId zone, String header, String footer) {
        super(new BorderLayout(0, 6));
        setName("run-workbench");
        evidence.setText(header + "\n\n" + RunWorkbench.text(RunWorkbench.sections(ref, row, visit, zone)) + footer);
        evidence.setCaretPosition(0);
        JPanel controls = ContentStyle.controls();
        controls.setName("run-workbench-actions");
        List<String> reasons = new ArrayList<>();
        add(controls, "Inspect players", "run-open-inspect", ActivityRoutes.visit(Destination.INSPECT, ref), reasons);
        add(controls, "Open Timeline", "run-open-timeline", ActivityRoutes.visit(Destination.TIMELINE, ref), reasons);
        if (visit.completionObservedAt > 0 && ref != null)
            add(controls, "Timeline around completion evidence (±30 s)", "run-open-completion-window",
                ActivityRoutes.timelineAround(ref, visit.completionObservedAt, ActivityRoutes.AROUND_MILLIS), reasons);
        add(controls, "Open Resources", "run-open-resources", ActivityRoutes.visit(Destination.RESOURCES, ref), reasons);
        add(controls, "Open Loot", "run-open-loot", ActivityRoutes.visit(Destination.LOOT, ref), reasons);
        unavailable.setName("run-workbench-unavailable");
        unavailable.getAccessibleContext().setAccessibleName("Unavailable run actions and reasons");
        unavailable.setText(reasons.isEmpty() ? "" : "Unavailable here: " + String.join("; ", reasons)
            + ". Nothing is matched by dungeon name or time; a disabled action's tooltip repeats its reason.");
        unavailable.setVisible(!reasons.isEmpty());
        // One scrollable column: evidence first, then actions, so compact or enlarged layouts keep every line reachable.
        Column column = new Column();
        column.add(evidence, BorderLayout.NORTH);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(controls, BorderLayout.NORTH); south.add(unavailable, BorderLayout.CENTER);
        column.add(south, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(column);
        scroll.setName("run-workbench-scroll"); scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setPreferredSize(new Dimension(600, 190));
        add(scroll, BorderLayout.CENTER);
    }

    /** Tracks the viewport width so wrapped evidence lines stay readable without horizontal scrolling. */
    private static final class Column extends JPanel implements Scrollable {
        Column() { super(new BorderLayout(0, 6)); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(24, o == SwingConstants.VERTICAL ? r.height - 24 : r.width - 24); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private void add(JPanel controls, String label, String name, Route route, List<String> reasons) {
        JButton button = new JButton(label);
        button.setName(name);
        String reason = ActivityRoutes.unavailable(route, label);
        button.setEnabled(reason == null);
        button.setToolTipText(reason == null ? label + " for this exact session and visit" : reason);
        button.getAccessibleContext().setAccessibleDescription(button.getToolTipText());
        if (reason != null) reasons.add(reason);
        button.addActionListener(e -> {
            if (route == null || !Navigator.current().open(route)) {
                unavailable.setText(label + " could not open this exact visit; nothing was changed.");
                unavailable.setVisible(true);
            }
        });
        controls.add(button);
    }
}
