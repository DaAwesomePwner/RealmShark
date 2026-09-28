package tomato.gui.settings;

import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.history.encounter.CombatSettings;
import util.PropertiesManager;

/**
 * Settings › General. Combat history: summaries are always saved; Keep full combat detail (off by default) also keeps
 * every hit, for a chosen number of days; summaries are kept forever unless a shorter period is chosen. Every control
 * saves at once and then announces the change through {@link CombatSettings#changed()}, so pruning can re-run.
 */
public final class GeneralSection extends JPanel {
    // Full detail from the P5 storage research; a summary (record plus detail) as CombatStorageMeasurementTest measured it: about
    // 12 KB for a 150 s dungeon, 30 KB for 600 s and 157 KB for a one-hour Realm-like fight. A no-break space keeps each number
    // beside its unit when the note wraps.
    static final String FULL_DETAIL_HELP = "Also saves every hit of each fight so it can be re-opened in the meter, about 14\u00a0MB per "
        + "100,000 hits. The debug packet log is never saved.";
    static final String SUMMARIES_HELP = "A summary is saved for every fight with its combat facts only: totals, each player's damage, "
        + "damage over time, enemies and deaths (about 12–30\u00a0KB for a dungeon run, more for long Realm visits). Older files are "
        + "removed at startup and after a change here; the current session is never touched.";
    static final String DAYS_UNAVAILABLE = "Turn on Keep full combat detail to choose how long it is kept.";
    /** Labels in the order of CombatSettings.FULL_DETAIL_DAYS_VALUES and SUMMARY_RETENTION_VALUES. */
    private static final String[] DAYS = {"7 days", "30 days", "90 days", "1 year"}, SUMMARIES = {"Forever", "1 year", "90 days"};

    private final List<JTextArea> notes = new ArrayList<>();
    private final JCheckBox fullDetail = new JCheckBox("Keep full combat detail");
    private final JComboBox<String> fullDays = new JComboBox<>(DAYS), summaries = new JComboBox<>(SUMMARIES);
    private final JLabel fullDaysLabel = label("Keep full detail for", fullDays);

    public GeneralSection() {
        this(PropertiesManager::getProperty, PropertiesManager::setProperties, CombatSettings::changed);
    }

    /** @param changed runs after each write; production announces it to CombatSettings' listeners */
    GeneralSection(Function<String, String> read, BiConsumer<String, String> write, Runnable changed) {
        super(new BorderLayout());
        setName("settings-general");
        setOpaque(false);
        fullDetail.setName("settings-combat-full-detail");
        fullDays.setName("settings-combat-full-days");
        fullDaysLabel.setName("settings-combat-full-days-label");
        summaries.setName("settings-combat-summaries");
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(group("Combat history", FULL_DETAIL_HELP, fullDetail));
        body.add(group(null, null, fullDaysLabel, fullDays));
        JLabel summariesLabel = label("Keep combat summaries", summaries);
        summariesLabel.setName("settings-combat-summaries-label");
        body.add(group(null, SUMMARIES_HELP, summariesLabel, summaries));

        // Restore before listening: showing what is saved never writes or announces anything.
        CombatSettings.Values saved = CombatSettings.read(read);
        fullDetail.setSelected(saved.keepFullDetail());
        fullDays.setSelectedIndex(Math.max(0, CombatSettings.FULL_DETAIL_DAYS_VALUES.indexOf(Integer.toString(saved.fullDetailDays()))));
        summaries.setSelectedIndex(Math.max(0, CombatSettings.SUMMARY_RETENTION_VALUES.indexOf(
            saved.summaryDays() == null ? CombatSettings.FOREVER : saved.summaryDays().toString())));
        fullDaysAvailability();

        fullDetail.addActionListener(e -> {
            fullDaysAvailability();
            write.accept(CombatSettings.KEEP_FULL_DETAIL, Boolean.toString(fullDetail.isSelected()));
            changed.run();
        });
        // Item events fire only when the selection changes, so re-choosing the shown value saves nothing.
        fullDays.addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED) return;
            write.accept(CombatSettings.FULL_DETAIL_DAYS, CombatSettings.FULL_DETAIL_DAYS_VALUES.get(fullDays.getSelectedIndex()));
            changed.run();
        });
        summaries.addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED) return;
            write.accept(CombatSettings.SUMMARY_RETENTION, CombatSettings.SUMMARY_RETENTION_VALUES.get(summaries.getSelectedIndex()));
            changed.run();
        });
        add(ContentStyle.page(null, body, null));
        refreshColors();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (notes != null) refreshColors();
    }

    /** The day count only applies while full detail is kept; disabled, it still shows the saved choice. */
    private void fullDaysAvailability() {
        boolean on = fullDetail.isSelected();
        fullDays.setEnabled(on);
        fullDaysLabel.setEnabled(on);
        fullDays.setToolTipText(on ? null : DAYS_UNAVAILABLE);
        fullDays.getAccessibleContext().setAccessibleDescription(on ? null : DAYS_UNAVAILABLE);
    }

    private static JLabel label(String text, JComponent control) {
        JLabel label = new JLabel(text);
        label.setLabelFor(control);
        control.getAccessibleContext().setAccessibleName(text);
        return label;
    }

    /** As AppearanceSection's groups: an optional header, one wrapping control row and a muted help note. */
    private JComponent group(String title, String help, JComponent... controls) {
        JPanel group = new JPanel(new BorderLayout(0, Tokens.XS)) {
            @Override public Dimension getMaximumSize() {
                // Keep controls beside their help text instead of stretching each group down a tall page.
                Dimension size = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, size.height);
            }
        };
        group.setOpaque(false);
        group.setAlignmentX(LEFT_ALIGNMENT);
        group.setBorder(new EmptyBorder(title == null ? 0 : Tokens.M, 0, Tokens.S, 0));
        if (title != null) group.add(new SectionHeader(title), BorderLayout.NORTH);
        JPanel row = ContentStyle.controls();
        row.setOpaque(false);
        for (JComponent control : controls) row.add(control);
        group.add(row, BorderLayout.CENTER);
        if (help != null) {
            JTextArea note = ContentStyle.wrappingText(help);
            note.setName(controls[controls.length - 1].getName() + "-help");
            notes.add(note);
            group.add(note, BorderLayout.SOUTH);
        }
        return group;
    }

    private void refreshColors() {
        for (JTextArea note : notes) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }
}
