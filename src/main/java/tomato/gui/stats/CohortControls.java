package tomato.gui.stats;

import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootQuery.*;
import tomato.history.SessionStore;

/** Explicit baseline/candidate cohort editor. Shared predicates (dungeon, outcome) apply to both; Apply submits query intent. */
final class CohortControls extends JPanel {
    private final Side baseline, candidate;
    private final JTextField dungeons;
    private final JComboBox<Outcome> outcome = new JComboBox<>(Outcome.values());
    private final JLabel error = new JLabel(" ");
    private final Facets initial;
    private final ZoneId zone;

    CohortControls(Facets facets, Map<String,String> sessions, ZoneId zone, Consumer<Facets> apply) {
        super(new BorderLayout(0, 4)); initial = facets; this.zone = zone;
        JPanel grid = ContentStyle.responsiveGrid(2, 220, 6);
        baseline = new Side("Baseline", "cohort-baseline", facets.baseline, sessions, zone);
        candidate = new Side("Candidate", "cohort-candidate", facets.candidate, sessions, zone);
        grid.add(baseline); grid.add(candidate); add(grid);
        JPanel shared = ContentStyle.controls();
        dungeons = new JTextField(String.join(";", facets.dungeons), 16); dungeons.setName("cohort-dungeons");
        dungeons.getAccessibleContext().setAccessibleName("Shared exact dungeons separated by semicolons");
        outcome.setName("cohort-outcome"); outcome.getAccessibleContext().setAccessibleName("Shared run outcome");
        outcome.setSelectedItem(facets.outcome == null ? Outcome.ANY : facets.outcome);
        JButton applyButton = new JButton("Compare cohorts"); applyButton.setName("cohort-apply");
        applyButton.addActionListener(e -> { try { Facets next = value(); next.validate(); error.setText(" "); apply.accept(next); } catch (RuntimeException failure) { error.setText(failure.getMessage()); } });
        error.setName("cohort-error"); error.putClientProperty("html.disable", true);
        shared.add(new JLabel("Both cohorts · dungeons (semicolon-separated)")); shared.add(dungeons); shared.add(outcome); shared.add(applyButton); shared.add(error);
        add(shared, BorderLayout.SOUTH);
    }
    Facets value() {
        Facets next = SessionStore.JSON.fromJson(SessionStore.JSON.toJson(initial), Facets.class);
        next.baseline = baseline.value(zone); next.candidate = candidate.value(zone);
        next.dungeons = new LinkedHashSet<>(); for (String name : dungeons.getText().split(";")) if (!name.trim().isEmpty()) next.dungeons.add(name.trim());
        Outcome chosen = (Outcome)outcome.getSelectedItem(); next.outcome = chosen == Outcome.ANY ? null : chosen;
        return next;
    }

    private static final class Side extends JPanel {
        final JList<String> sessions; final List<String> ids = new ArrayList<>();
        final JTextField from = new JTextField(14), until = new JTextField(14);
        Side(String title, String name, Cohort cohort, Map<String,String> labels, ZoneId zone) {
            super(new BorderLayout(0, 3));
            List<String> display = new ArrayList<>();
            Set<String> known = new TreeSet<>(labels.keySet()); if (cohort != null) known.addAll(cohort.sessions);
            for (String id : known) { ids.add(id); display.add(labels.getOrDefault(id, "Session not in scope") + " · " + id); }
            sessions = new JList<>(display.toArray(new String[0])); sessions.setName(name + "-sessions"); sessions.setVisibleRowCount(4);
            sessions.getAccessibleContext().setAccessibleName(title + " sessions (none selected = every session in scope)");
            DefaultListCellRenderer renderer = new DefaultListCellRenderer(); renderer.putClientProperty("html.disable", true); sessions.setCellRenderer(renderer);
            if (cohort != null) for (int i = 0; i < ids.size(); i++) if (cohort.sessions.contains(ids.get(i))) sessions.addSelectionInterval(i, i);
            from.setName(name + "-from"); until.setName(name + "-until");
            from.getAccessibleContext().setAccessibleName(title + " visit entry from, inclusive ISO timestamp");
            until.getAccessibleContext().setAccessibleName(title + " visit entry until, exclusive ISO timestamp");
            if (cohort != null && cohort.from != null) from.setText(Instant.ofEpochMilli(cohort.from).atZone(zone).toOffsetDateTime().toString());
            if (cohort != null && cohort.until != null) until.setText(Instant.ofEpochMilli(cohort.until).atZone(zone).toOffsetDateTime().toString());
            JLabel heading = new JLabel(title + " sessions (none = all in scope)"); heading.setLabelFor(sessions);
            add(heading, BorderLayout.NORTH); add(new JScrollPane(sessions));
            JPanel bounds = ContentStyle.controls(); bounds.add(new JLabel("Entry from [")); bounds.add(from); bounds.add(new JLabel("until )")); bounds.add(until);
            add(bounds, BorderLayout.SOUTH);
        }
        Cohort value(ZoneId zone) {
            List<String> chosen = new ArrayList<>(); for (int index : sessions.getSelectedIndices()) chosen.add(ids.get(index));
            return new Cohort(chosen, LootQuery.resolveTime(from.getText(), zone), LootQuery.resolveTime(until.getText(), zone));
        }
    }
}
