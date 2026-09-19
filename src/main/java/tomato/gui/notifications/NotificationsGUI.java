package tomato.gui.notifications;

import tomato.gui.TomatoGUI;
import tomato.gui.keypop.KeypopGUI;
import tomato.realmshark.RealmEventAlerts;
import tomato.realmshark.Sound;
import tomato.gui.modern.ContentStyle;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.util.*;
import java.util.List;

/** One home for sound profiles and notification rules. All edits persist immediately. */
public final class NotificationsGUI extends JPanel {
    final JTabbedPane tabs = new JTabbedPane();
    final JSlider master = new JSlider(0, 100);
    final JCheckBox mute = new JCheckBox("Mute all");
    final JTextField dungeonSearch = new JTextField();
    final JCheckBox missing = new JCheckBox("Also alert for missing dungeon completes");
    final JPanel dungeonList = new JPanel();
    final JLabel dungeonCount = new JLabel();
    private final JLabel masterValue = new JLabel();
    private final JTextArea status = note("");
    private final List<AlertRow> rows = new ArrayList<>();
    private final Map<String, JCheckBox> dungeonChoices = new TreeMap<>();
    private final JPanel realmList = stack();
    private final JLabel realmStatus = new JLabel();
    private final Runnable update = () -> SwingUtilities.invokeLater(this::refresh);
    private final TimerHolder timer = new TimerHolder();
    private boolean syncing;

    public NotificationsGUI() {
        super(new BorderLayout(0, 8));
        JPanel top = new JPanel(new BorderLayout(0, 6)), masterControls = ContentStyle.controls();
        JPanel volume = new JPanel(new BorderLayout(8, 0));
        JLabel masterLabel = new JLabel("Master volume"); masterLabel.setLabelFor(master);
        masterValue.setFont(ContentStyle.metadata(ContentStyle.body()));
        dungeonCount.setFont(ContentStyle.metadata(ContentStyle.body())); realmStatus.setFont(ContentStyle.metadata(ContentStyle.body()));
        volume.add(masterLabel, BorderLayout.WEST); volume.add(master); volume.add(masterValue, BorderLayout.EAST);
        master.setName("sound-master"); master.getAccessibleContext().setAccessibleName("Master volume");
        mute.setName("sound-mute"); masterControls.add(volume); masterControls.add(mute); top.add(masterControls);
        top.add(note("Settings save automatically. Test plays the chosen sound, including disabled alerts; Mute all and volume still apply."), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        master.addChangeListener(e -> { if (!syncing && !master.getValueIsAdjusting()) Sound.setVolume(master.getValue()); masterValue.setText(master.getValue() + "%"); });
        mute.addActionListener(e -> Sound.setMuted(mute.isSelected()));
        for (String group : new String[]{"Messages", "Bags", "Key pops", "Realm events", "Other alerts"}) {
            JPanel content = stack();
            if (group.equals("Bags")) content.add(note("Bag alerts include boosted bags. Loot visibility filters do not mute sounds."));
            for (Sound sound : Sound.ALERTS) if (sound.group.equals(group)) content.add(row(sound));
            if (group.equals("Key pops")) content.add(dungeons());
            if (group.equals("Realm events")) {
                content.add(note("Alerts match public Oryx/system announcements while in a Realm. Presets match event names; edit a phrase to narrow it to the spawn announcement. Common defeat messages are skipped; repeat alerts pause for 30 seconds."));
                JButton add = new JButton("Add realm event..."); add.setName("realm-add"); add.addActionListener(e -> addRealmRule());
                content.add(left(add)); content.add(realmList); content.add(left(realmStatus)); rebuildRealmRules();
            }
            if (group.equals("Other alerts")) {
                content.add(note("Choose which chat phrases, items, entities and enchantments should trigger these sounds."));
                JPanel actions = ContentStyle.responsiveGrid(2, 220, 8);
                action(actions, "Chat message rules...", TomatoGUI::openChatPingMessage);
                action(actions, "Item drop rules...", TomatoGUI::openItemPing);
                action(actions, "Entity rules...", TomatoGUI::openEntityIdPing);
                action(actions, "Enchantment rules...", TomatoGUI::openEnchantPing);
                content.add(actions);
            }
            JPanel wrapper = new WidthTrackingPanel(); wrapper.add(content, BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(wrapper); scroll.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            tabs.addTab(group, scroll);
        }
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabs); status.setRows(2); status.setName("sound-status"); add(status, BorderLayout.SOUTH);
        addComponentListener(new ComponentAdapter() { @Override public void componentShown(ComponentEvent e) { refreshDungeons(); refresh(); } });
        refresh();
    }
    @Override public void addNotify() { super.addNotify(); Sound.addListener(update); timer.start(); refresh(); }
    @Override public void removeNotify() { Sound.removeListener(update); timer.stop(); super.removeNotify(); }
    private final class TimerHolder {
        final javax.swing.Timer value = new javax.swing.Timer(1000, e -> { if (isShowing()) realmStatus.setText(RealmEventAlerts.INSTANCE.getLastMatchLabel()); });
        void start() { value.start(); } void stop() { value.stop(); }
    }
    public void selectSection(String section) {
        refreshDungeons(); refresh();
        if (section != null) for (int i = 0; i < tabs.getTabCount(); i++) if (section.equals(tabs.getTitleAt(i))) tabs.setSelectedIndex(i);
    }
    private AlertRow row(Sound sound) { AlertRow row = new AlertRow(sound); rows.add(row); return row; }
    private void refresh() {
        syncing = true;
        if (!master.getValueIsAdjusting()) master.setValue(Sound.getMasterVolume());
        masterValue.setText(master.getValue() + "%"); mute.setSelected(Sound.isMuted());
        status.setText(Sound.getLastStatus()); for (AlertRow row : rows) row.refresh();
        realmStatus.setText(RealmEventAlerts.INSTANCE.getLastMatchLabel()); syncing = false;
    }
    private final class AlertRow extends JPanel {
        final Sound sound;
        final JCheckBox enabled = new JCheckBox();
        final JSlider volume = new JSlider(0, 100);
        final JLabel value = new JLabel();
        final JComboBox<String> tone = new JComboBox<>();
        @Override public void updateUI() {
            super.updateUI(); setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ContentStyle.color("border")), BorderFactory.createEmptyBorder(4, 0, 6, 0)));
        }
        AlertRow(Sound sound) {
            super(new BorderLayout(0, 4)); this.sound = sound;
            setMinimumSize(new Dimension(0, 0));
            enabled.setText(sound.label); enabled.setName("sound-" + sound.id + "-enabled");
            enabled.setFont(ContentStyle.body()); enabled.addActionListener(e -> sound.setEnabled(enabled.isSelected()));
            value.setFont(ContentStyle.metadata(ContentStyle.body()));
            JPanel heading = ContentStyle.controls(); heading.add(enabled);
            JPanel gain = new JPanel(new BorderLayout(4, 0)); gain.add(volume); gain.add(value, BorderLayout.EAST);
            volume.setPreferredSize(new Dimension(150, volume.getPreferredSize().height)); value.setLabelFor(volume);
            volume.setName("sound-" + sound.id + "-volume"); volume.getAccessibleContext().setAccessibleName(sound.label + " volume");
            volume.addChangeListener(e -> { value.setText(volume.getValue() + "%"); if (!syncing && !volume.getValueIsAdjusting()) sound.setAlertVolume(volume.getValue()); });
            heading.add(gain); add(heading, BorderLayout.NORTH);
            tone.setPrototypeDisplayValue("Whisper chime"); tone.setName("sound-" + sound.id + "-tone"); tone.getAccessibleContext().setAccessibleName(sound.label + " sound");
            tone.setRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> list, Object item, int index, boolean selected, boolean focus) {
                String text = String.valueOf(item); text = toneLabel(text);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }});
            tone.addActionListener(e -> { if (!syncing && tone.getSelectedItem() != null) sound.setTone(tone.getSelectedItem().toString()); });
            JPanel controls = ContentStyle.controls(); controls.add(tone);
            JButton browse = new JButton("WAV..."); browse.setToolTipText("Choose a local WAV, up to 10 MB / 30 seconds");
            browse.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser(); chooser.setFileFilter(new FileNameExtensionFilter("WAV audio", "wav"));
                if (chooser.showOpenDialog(NotificationsGUI.this) == JFileChooser.APPROVE_OPTION) {
                    browse.setEnabled(false);
                    sound.chooseFile(chooser.getSelectedFile(), message -> SwingUtilities.invokeLater(() -> { browse.setEnabled(true); refresh(); status.setText(message); }));
                }
            });
            JButton reset = new JButton("Default"); reset.addActionListener(e -> sound.setTone(sound.getDefaultTone()));
            JButton test = new JButton("Test"); test.setName("sound-" + sound.id + "-test");
            browse.getAccessibleContext().setAccessibleName("Choose WAV for " + sound.label);
            reset.getAccessibleContext().setAccessibleName("Restore default sound for " + sound.label);
            test.getAccessibleContext().setAccessibleName("Test " + sound.label);
            test.addActionListener(e -> sound.preview(message -> SwingUtilities.invokeLater(() -> status.setText(message))));
            controls.add(browse); controls.add(reset); controls.add(test); add(controls);
        }
        void refresh() {
            enabled.setSelected(sound.isEnabled()); if (!volume.getValueIsAdjusting()) volume.setValue(sound.getVolume()); value.setText(volume.getValue() + "%");
            if (!sound.getTone().equals(tone.getSelectedItem())) {
                tone.removeAllItems(); for (String preset : Sound.BUILT_INS) tone.addItem(preset);
                if (!Arrays.asList(Sound.BUILT_INS).contains(sound.getTone())) tone.addItem(sound.getTone());
                tone.setSelectedItem(sound.getTone());
            }
            tone.setToolTipText(sound.getTone());
        }
    }
    private JPanel dungeons() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel header = stack(); header.add(note("Choose dungeons to hear their key pops and portal callouts. Unselected dungeons remain in Key Pops history."));
        missing.setToolTipText("Dungeons with zero completes on the currently playing character"); missing.addActionListener(e -> saveDungeons()); header.add(missing);
        dungeonSearch.putClientProperty("JTextField.placeholderText", "Find a dungeon..."); dungeonSearch.setName("sound-dungeon-search");
        dungeonSearch.getAccessibleContext().setAccessibleName("Find notification dungeon"); header.add(dungeonSearch);
        JPanel buttons = ContentStyle.controls();
        JButton select = new JButton("Select shown"), clear = new JButton("Unselect shown");
        select.addActionListener(e -> selectShown(true)); clear.addActionListener(e -> selectShown(false));
        buttons.add(select); buttons.add(clear); buttons.add(dungeonCount); header.add(buttons);
        panel.add(header, BorderLayout.NORTH); dungeonList.setLayout(new GridLayout(0, 1, 0, 2)); panel.add(dungeonList);
        dungeonSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterDungeons(); } public void removeUpdate(DocumentEvent e) { filterDungeons(); } public void changedUpdate(DocumentEvent e) { filterDungeons(); }
        });
        refreshDungeons(); return panel;
    }
    private void refreshDungeons() {
        Set<String> selected = KeypopGUI.getSelectedDungeons();
        missing.setSelected(selected.contains("missingDungeons"));
        for (String name : KeypopGUI.getDungeonNames()) {
            JCheckBox box = dungeonChoices.get(name);
            if (box == null) { box = new JCheckBox(name); box.addActionListener(e -> saveDungeons()); dungeonChoices.put(name, box); }
            box.setSelected(selected.contains(name));
        }
        filterDungeons();
    }
    private void filterDungeons() {
        String query = dungeonSearch.getText().trim().toLowerCase(Locale.ROOT); dungeonList.removeAll();
        for (Map.Entry<String, JCheckBox> item : dungeonChoices.entrySet()) if (item.getKey().toLowerCase(Locale.ROOT).contains(query)) dungeonList.add(item.getValue());
        if (dungeonList.getComponentCount() == 0) dungeonList.add(new JLabel("No dungeons match this search."));
        long selected = dungeonChoices.values().stream().filter(AbstractButton::isSelected).count();
        dungeonCount.setText(selected + " selected"); dungeonList.revalidate(); dungeonList.repaint();
    }
    private void selectShown(boolean selected) {
        for (Component c : dungeonList.getComponents()) if (c instanceof JCheckBox) ((JCheckBox)c).setSelected(selected); saveDungeons();
    }
    private void saveDungeons() {
        Set<String> selected = new TreeSet<>(); if (missing.isSelected()) selected.add("missingDungeons");
        dungeonChoices.forEach((name, box) -> { if (box.isSelected()) selected.add(name); });
        KeypopGUI.setSelectedDungeons(selected); filterDungeons();
    }
    private void rebuildRealmRules() {
        rows.removeIf(row -> row.sound.group.equals("Realm events")); realmList.removeAll();
        for (RealmEventAlerts.Rule rule : RealmEventAlerts.INSTANCE.getRules()) {
            JPanel card = new JPanel(new BorderLayout(0, 4)); card.add(row(rule.sound), BorderLayout.NORTH);
            JTextField phrase = new JTextField(rule.getPhrase(), 18); phrase.setName("realm-phrase-" + rule.id); phrase.setToolTipText("Literal case-insensitive phrase in a public announcement");
            phrase.getAccessibleContext().setAccessibleName(rule.name + " announcement phrase");
            JPanel editor = ContentStyle.controls(), phraseBox = new JPanel(new BorderLayout(6, 0));
            JLabel phraseLabel = new JLabel("Phrase"); phraseLabel.setLabelFor(phrase); phraseBox.add(phraseLabel, BorderLayout.WEST); phraseBox.add(phrase); editor.add(phraseBox);
            JButton save = new JButton("Save phrase"), remove = new JButton("Remove");
            save.addActionListener(e -> { try { RealmEventAlerts.INSTANCE.setPhrase(rule, phrase.getText()); status.setText("Saved announcement phrase for " + rule.name + "."); } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); } });
            remove.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Remove the alert for " + rule.name + "?", "Remove realm event", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    RealmEventAlerts.INSTANCE.remove(rule); rebuildRealmRules();
                }
            });
            save.getAccessibleContext().setAccessibleName("Save phrase for " + rule.name); remove.getAccessibleContext().setAccessibleName("Remove " + rule.name + " alert");
            editor.add(save); editor.add(remove); card.add(editor); card.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0)); card.setMinimumSize(new Dimension(0, 0)); realmList.add(card);
        }
        if (realmList.getComponentCount() == 0) realmList.add(note("No realm event rules. Add an event and the phrase used in its announcement."));
        ContentStyle.refreshFonts(realmList); realmList.revalidate(); realmList.repaint(); refresh();
    }
    private void addRealmRule() {
        JTextField name = new JTextField(), phrase = new JTextField();
        JLabel nameLabel = new JLabel("Event name"), phraseLabel = new JLabel("Announcement contains"); nameLabel.setLabelFor(name); phraseLabel.setLabelFor(phrase);
        name.getAccessibleContext().setAccessibleName("Realm event name"); phrase.getAccessibleContext().setAccessibleName("Realm event announcement phrase");
        Object[] fields = {nameLabel, name, phraseLabel, phrase};
        if (JOptionPane.showConfirmDialog(this, fields, "Add realm event", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try { RealmEventAlerts.INSTANCE.add(name.getText(), phrase.getText()); rebuildRealmRules(); }
            catch (IllegalArgumentException e) { JOptionPane.showMessageDialog(this, e.getMessage()); }
        }
    }
    private static String toneLabel(String tone) {
        switch (tone) {
            case "pm": return "Whisper chime";
            case "keypop": return "Key pop";
            case "whitebag": return "White bag";
            case "orangebag": return "Orange bag";
            case "redbag": return "Red bag";
            case "goldbag": return "Gold bag";
            case "eggbag": return "Egg basket";
            case "bluebag": return "Blue bag";
            case "party": return "Party chime";
            case "guild": return "Guild chime";
            case "trade": return "Trade chime";
            case "custom": return "Custom alert chime";
            default: return "Custom: " + new File(tone).getName();
        }
    }
    private static JPanel stack() {
        JPanel panel = new JPanel() {
            @Override public Dimension getPreferredSize() {
                // Wrapping children change height during width layout; discard BoxLayout's old row heights.
                ((BoxLayout)getLayout()).invalidateLayout(this);
                return super.getPreferredSize();
            }
            @Override protected void addImpl(Component component, Object constraints, int index) {
                if (component instanceof JComponent) ((JComponent)component).setAlignmentX(Component.LEFT_ALIGNMENT);
                super.addImpl(component, constraints, index);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel;
    }
    private static JPanel left(Component c) { JPanel panel = ContentStyle.controls(); panel.add(c); return panel; }
    private static JTextArea note(String text) {
        JTextArea area = new JTextArea(text) {
            @Override public Dimension getPreferredSize() {
                // Wrapped text has no useful preferred height before its first layout.
                int available = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : 500;
                Insets insets = getInsets(); available = Math.max(100, available - insets.left - insets.right - 20);
                FontMetrics metrics = getFontMetrics(getFont()); int lines = 0;
                for (String paragraph : getText().split("\n", -1)) {
                    int used = 0; lines++;
                    for (String word : paragraph.split(" ")) {
                        int length = metrics.stringWidth(word + " ");
                        if (used > 0 && used + length > available) { lines++; used = 0; }
                        used += length;
                    }
                }
                return new Dimension(100, metrics.getHeight() * Math.max(getRows(), lines) + insets.top + insets.bottom);
            }
        }; area.setEditable(false); area.setFocusable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false);
        area.setFont(ContentStyle.metadata(ContentStyle.body())); area.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4)); return area;
    }
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel() { super(new BorderLayout()); }
        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth(); super.setBounds(x, y, width, height);
            if (changed) SwingUtilities.invokeLater(this::revalidate);
        }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(24, r.height - 24); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static void action(JPanel parent, String label, Runnable action) { JButton b = new JButton(label); b.addActionListener(e -> action.run()); parent.add(b); }
}
