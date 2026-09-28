package tomato.gui.settings;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.kit.SectionHeader;
import tomato.history.encounter.CombatSettings;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.settings.SettingsPageTest.named;

public class GeneralSectionTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p5a-settings");
    private final Map<String, String> store = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private int changed;

    private GeneralSection section() {
        return new GeneralSection(store::get, (key, value) -> { writes.add(key + "=" + value); store.put(key, value); }, () -> changed++);
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> combo(Container root, String name) { return named(root, name, JComboBox.class); }

    @Test public void controlsRestoreTheSavedValuesWithoutWriting() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GeneralSection defaults = section();
            JCheckBox keep = named(defaults, "settings-combat-full-detail", JCheckBox.class);
            JComboBox<String> days = combo(defaults, "settings-combat-full-days"), summaries = combo(defaults, "settings-combat-summaries");
            assertFalse("Full detail is off by default", keep.isSelected());
            assertEquals("30 days", days.getSelectedItem());
            assertFalse("The day count is unavailable while full detail is off", days.isEnabled());
            assertFalse("Its label is dimmed with it", named(defaults, "settings-combat-full-days-label", JLabel.class).isEnabled());
            assertEquals("Forever", summaries.getSelectedItem());
            assertTrue(summaries.isEnabled());

            store.put(CombatSettings.KEEP_FULL_DETAIL, "true");
            store.put(CombatSettings.FULL_DETAIL_DAYS, "90");
            store.put(CombatSettings.SUMMARY_RETENTION, "365");
            GeneralSection saved = section();
            assertTrue(named(saved, "settings-combat-full-detail", JCheckBox.class).isSelected());
            assertEquals("90 days", combo(saved, "settings-combat-full-days").getSelectedItem());
            assertTrue(combo(saved, "settings-combat-full-days").isEnabled());
            assertEquals("1 year", combo(saved, "settings-combat-summaries").getSelectedItem());

            store.put(CombatSettings.KEEP_FULL_DETAIL, "false");
            store.put(CombatSettings.FULL_DETAIL_DAYS, "7");
            store.put(CombatSettings.SUMMARY_RETENTION, "90");
            GeneralSection off = section();
            JComboBox<String> offDays = combo(off, "settings-combat-full-days");
            assertFalse(offDays.isEnabled());
            assertEquals("The disabled day count still shows the saved value", "7 days", offDays.getSelectedItem());
            assertEquals("90 days", combo(off, "settings-combat-summaries").getSelectedItem());

            store.put(CombatSettings.KEEP_FULL_DETAIL, "yes");
            store.put(CombatSettings.FULL_DETAIL_DAYS, "45");
            store.put(CombatSettings.SUMMARY_RETENTION, "forever");
            GeneralSection invalid = section();
            assertFalse("Invalid values show the defaults", named(invalid, "settings-combat-full-detail", JCheckBox.class).isSelected());
            assertEquals("30 days", combo(invalid, "settings-combat-full-days").getSelectedItem());
            assertEquals("Forever", combo(invalid, "settings-combat-summaries").getSelectedItem());

            assertTrue("Restoring never writes: " + writes, writes.isEmpty());
            assertEquals("Restoring never announces a change", 0, changed);
        });
    }

    @Test public void eachChangeWritesItsKeyThenAnnouncesIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GeneralSection section = section();
            JCheckBox keep = named(section, "settings-combat-full-detail", JCheckBox.class);
            JComboBox<String> days = combo(section, "settings-combat-full-days"), summaries = combo(section, "settings-combat-summaries");
            keep.doClick();
            assertEquals(List.of("combat.keepFullDetail=true"), writes);
            assertEquals(1, changed);
            assertTrue("Turning full detail on enables the day count", days.isEnabled());
            assertTrue(named(section, "settings-combat-full-days-label", JLabel.class).isEnabled());
            days.setSelectedItem("1 year");
            assertEquals("combat.fullDetailDays=365", writes.get(1));
            assertEquals(2, changed);
            days.setSelectedItem("7 days");
            assertEquals("combat.fullDetailDays=7", writes.get(2));
            summaries.setSelectedItem("90 days");
            assertEquals("combat.summaryRetention=90", writes.get(3));
            summaries.setSelectedItem("Forever");
            assertEquals("combat.summaryRetention=all", writes.get(4));
            summaries.setSelectedItem("1 year");
            assertEquals("combat.summaryRetention=365", writes.get(5));
            assertEquals(6, changed);
            keep.doClick();
            assertEquals("combat.keepFullDetail=false", writes.get(6));
            assertEquals(7, changed);
            assertFalse(days.isEnabled());
            assertEquals("Turning full detail off keeps the chosen day count", "7 days", days.getSelectedItem());
            assertEquals("The saved keys read back as what the controls show",
                new CombatSettings.Values(false, 7, 365), CombatSettings.read(store::get));
            days.setSelectedItem("7 days");
            assertEquals("Re-selecting the shown value writes nothing", 7, writes.size());
            assertEquals(7, changed);
        });
    }

    @Test public void namesLabelsAndHelpDescribeWhatIsSaved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GeneralSection section = section();
            assertEquals("settings-general", section.getName());
            assertNotNull("The group is titled", VisualEvidence.find(section, SectionHeader.class, header -> "Combat history".equals(header.title())));
            assertEquals("Keep full combat detail", named(section, "settings-combat-full-detail", JCheckBox.class).getText());
            JComboBox<String> days = combo(section, "settings-combat-full-days"), summaries = combo(section, "settings-combat-summaries");
            assertEquals(List.of("7 days", "30 days", "90 days", "1 year"), items(days));
            assertEquals(List.of("Forever", "1 year", "90 days"), items(summaries));
            assertEquals("Keep full detail for", label(section, days).getText());
            assertEquals("Keep combat summaries", label(section, summaries).getText());
            assertEquals("Keep full detail for", days.getAccessibleContext().getAccessibleName());
            assertEquals("Keep combat summaries", summaries.getAccessibleContext().getAccessibleName());
            assertEquals("The disabled day count says why", "Turn on Keep full combat detail to choose how long it is kept.",
                days.getToolTipText());
            named(section, "settings-combat-full-detail", JCheckBox.class).doClick();
            assertNull("No reason while it is available", days.getToolTipText());
            StringBuilder help = new StringBuilder();
            for (JTextArea note : notes(section)) help.append(note.getText()).append('\n');
            String text = help.toString().replace('\u00a0', ' ');
            assertTrue("Numbers stay beside their units", help.indexOf("14\u00a0MB") >= 0 && help.indexOf("20–80\u00a0KB") >= 0);
            for (String fact : new String[] {"every hit", "re-opened in the meter", "about 14 MB per 100,000 hits",
                    "debug packet log is never saved", "saved for every fight", "totals", "each player's damage", "damage over time",
                    "about 20–80 KB each", "at startup and after a change", "current session is never touched"})
                assertTrue("Help mentions '" + fact + "': " + text, text.contains(fact));
        });
    }

    @Test public void fitsAt680By520WithFont18WithoutScrollingSideways() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        SwingUtilities.invokeAndWait(() -> {
            page[0] = new SettingsPage(new JPanel(), () -> {}, section(), new JPanel());
            page[0].showSection(SettingsPage.GENERAL);
            evidence.show(page[0], "Settings General", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            GeneralSection section = VisualEvidence.find(page[0], GeneralSection.class, component -> true);
            assertTrue(section.isShowing());
            JViewport viewport = VisualEvidence.find(section, JScrollPane.class, scroll -> true).getViewport();
            assertEquals("The section never scrolls sideways", viewport.getWidth(), viewport.getView().getWidth());
            for (String name : new String[] {"settings-section-notifications", "settings-section-general", "settings-section-appearance"})
                VisualEvidence.completeButton(named(page[0], name, AbstractButton.class));
            VisualEvidence.completeButton(named(section, "settings-combat-full-detail", JCheckBox.class));
            for (JComponent control : new JComponent[] {named(section, "settings-combat-full-detail", JCheckBox.class),
                    combo(section, "settings-combat-full-days"), combo(section, "settings-combat-summaries"),
                    label(section, combo(section, "settings-combat-full-days")), label(section, combo(section, "settings-combat-summaries"))}) {
                Rectangle placed = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), viewport.getView());
                assertTrue(control.getName() + " fits at 680 px, font 18: " + placed, placed.x >= 0 && placed.x + placed.width <= viewport.getWidth());
                assertTrue(control.getName() + " is whole: " + control.getSize(), control.getWidth() >= control.getPreferredSize().width);
                VisualEvidence.reachable(control);
            }
            for (JTextArea note : notes(section)) VisualEvidence.completeText(note);
            assertNotNull(named(section, "settings-combat-full-detail-help", JTextArea.class));
            assertNotNull(named(section, "settings-combat-summaries-help", JTextArea.class));
            evidence.capture("settings-general-off-680-18");
            named(section, "settings-combat-full-detail", JCheckBox.class).doClick();
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> days = combo(page[0], "settings-combat-full-days");
            assertTrue(days.isEnabled());
            VisualEvidence.reachable(days);
            evidence.capture("settings-general-on-680-18");
        });
    }

    private static List<String> items(JComboBox<String> combo) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) items.add(combo.getItemAt(i));
        return items;
    }

    private static JLabel label(Container root, Component control) {
        return VisualEvidence.find(root, JLabel.class, label -> label.getLabelFor() == control);
    }

    private static List<JTextArea> notes(Container root) {
        List<JTextArea> notes = new ArrayList<>();
        collect(root, notes);
        assertFalse("The section has help text", notes.isEmpty());
        return notes;
    }

    private static void collect(Container root, List<JTextArea> notes) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea) notes.add((JTextArea) child);
            else if (child instanceof Container) collect((Container) child, notes);
        }
    }
}
