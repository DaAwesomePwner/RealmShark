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
    private final JTextArea error = ContentStyle.wrappingText("");
    private final Facets initial;
    private final ZoneId zone;

    /** Example accepted by the entry bounds: an ISO local date-time (resolved in the query zone) or one with an explicit offset. */
    static final String TIME_FORMAT = "YYYY-MM-DDTHH:MM, e.g. 2026-09-21T09:30 or 2026-09-21T09:30-05:00";

    /** {@code invalid} runs on the EDT after an input error so the owner can clear or mark results that no longer match the inputs. */
    CohortControls(Facets facets, Map<String,String> sessions, ZoneId zone, Consumer<Facets> apply, Runnable invalid) {
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
        applyButton.addActionListener(e -> {
            try { Facets next = value(); next.validate(); showError(null); apply.accept(next); }
            catch (IllegalArgumentException failure) { showError(failure.getMessage()); invalid.run(); }
            catch (RuntimeException failure) { showError("The cohorts could not be compared: check both cohorts' sessions and entry bounds."); invalid.run(); }
        });
        error.setName("cohort-error"); error.setForeground(ContentStyle.color("rose")); error.setVisible(false);
        error.getAccessibleContext().setAccessibleName("Cohort input error");
        shared.add(new JLabel("Both cohorts · dungeons (semicolon-separated)")); shared.add(dungeons); shared.add(outcome); shared.add(applyButton);
        JPanel south = new JPanel(new BorderLayout(0, 2)); south.add(shared, BorderLayout.NORTH); south.add(error, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }
    private void showError(String message) {
        error.setText(message == null ? "" : message); error.setVisible(message != null);
        error.getAccessibleContext().setAccessibleDescription(message);
        revalidate(); repaint();
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
        final String title;
        Side(String title, String name, Cohort cohort, Map<String,String> labels, ZoneId zone) {
            super(new BorderLayout(0, 3)); this.title = title;
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
            Long start = time(from, "entry from", zone), end = time(until, "until", zone);
            if (start != null && end != null && start >= end) throw new IllegalArgumentException(title + " entry from must be earlier than its until time.");
            return new Cohort(chosen, start, end);
        }
        /** Names the field and the expected format instead of surfacing the parser's message. */
        private Long time(JTextField field, String label, ZoneId zone) {
            try { return LootQuery.resolveTime(field.getText(), zone); }
            catch (java.time.format.DateTimeParseException failure) {
                throw new IllegalArgumentException(title + " " + label + ": “" + field.getText().trim() + "” is not a date and time. Use " + TIME_FORMAT + ".");
            }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(title + " " + label + ": that local time is ambiguous or skipped in " + zone.getId() + ". Add a UTC offset, e.g. 2026-09-21T09:30-05:00.");
            }
        }
    }
}
