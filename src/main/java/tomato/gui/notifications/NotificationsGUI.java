package tomato.gui.notifications;

import tomato.gui.TomatoGUI;
import tomato.gui.keypop.KeypopGUI;
import tomato.realmshark.RealmEventAlerts;
import tomato.realmshark.Sound;
import tomato.gui.modern.ContentStyle;
import tomato.gui.maingui.DraftSaveStatus;
import tomato.gui.maingui.AlertRuleEditor;
import util.PropertiesManager;
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

/** Automatic sound controls and explicitly submitted rule drafts. */
public final class NotificationsGUI extends JPanel {
    /** Title of the Recent decisions tab (ALERT-4). */
    public static final String DECISIONS = "Recent decisions";
    /** Tab title for key-pop sounds and dungeons, matching the navigation's "Key-pops". */
    public static final String KEY_POPS = "Key-pops";
    /** The key-pop {@link Sound#group}; an internal key, not shown. */
    static final String SOUND_GROUP_KEY_POPS = "Key pops";
    private static volatile NotificationsGUI displayed;
    final JTabbedPane tabs = new JTabbedPane();
    final JSlider master = new JSlider(0, 100);
    final JCheckBox mute = new JCheckBox("Mute all");
    final JTextField dungeonSearch = new JTextField();
    final JCheckBox dungeonSelectedOnly = new JCheckBox("Selected only");
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
    private long dungeonSaveRequest;
    final JTextArea focusText = note("");
    final JPanel focusBanner = new JPanel(new BorderLayout(8, 4));
    private final JButton focusBack = new JButton("Back"), focusClear = new JButton("Done");
    private String focusedDungeon, priorSearch;
    private boolean priorSelectedOnly;
    private Runnable focusReturn;
    final RecentDecisionsPanel decisions = new RecentDecisionsPanel(tomato.realmshark.AlertDecisions.INSTANCE, this);

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
        JTextArea controlsNote = note("Sound and dungeon controls apply and save automatically. Rule editors use Save; drafts stay local until submitted. Test plays the chosen sound, including disabled alerts; Mute all and volume still apply.");
        controlsNote.setName("sound-controls-note"); top.add(controlsNote, BorderLayout.SOUTH);
        master.addChangeListener(e -> { if (!syncing && !master.getValueIsAdjusting()) Sound.setVolume(master.getValue()); masterValue.setText(master.getValue() + "%"); });
        mute.addActionListener(e -> Sound.setMuted(mute.isSelected()));
        for (String group : new String[]{"Messages", "Bags", SOUND_GROUP_KEY_POPS, "Realm events", "Other alerts"}) {
            JPanel content = stack();
            if (group.equals("Bags")) content.add(note("Bag alerts include boosted bags. Loot visibility filters do not mute sounds."));
            for (Sound sound : Sound.ALERTS) if (sound.group.equals(group)) content.add(row(sound));
            if (group.equals(SOUND_GROUP_KEY_POPS)) content.add(dungeons());
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
            tabs.addTab(group.equals(SOUND_GROUP_KEY_POPS) ? KEY_POPS : group, scroll);
        }
        // Recent decisions scrolls within its own page so its table can take the spare height and reveal rows itself.
        decisions.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2)); tabs.addTab(DECISIONS, decisions);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // The controls note explains editing; Recent decisions is read-only, so it gives that height to the rows.
        tabs.addChangeListener(e -> { boolean editing = tabs.getSelectedComponent() != decisions;
            if (controlsNote.isVisible() != editing) { controlsNote.setVisible(editing); top.revalidate(); top.repaint(); } });
        status.setRows(2); status.setName("sound-status");
        JPanel sections = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() {
                // Keep a tab strip and a useful slice of its scrollable controls below a long status/header.
                int line = tabs.getFontMetrics(tabs.getFont()).getHeight();
                Rectangle tab = tabs.getBoundsAt(tabs.getSelectedIndex());
                Insets insets = tabs.getInsets();
                int strip = tab == null ? line * 2 : tab.y + tab.height;
                return new Dimension(0, Math.max(line * 2, strip) + line * 6 + insets.top + insets.bottom);
            }
        };
        sections.add(tabs);
        JScrollPane page = ContentStyle.page(top, sections, status);
        page.setName("notifications-page"); add(page, BorderLayout.CENTER);
        addComponentListener(new ComponentAdapter() { @Override public void componentShown(ComponentEvent e) { refreshDungeons(); refresh(); } });
        refresh();
    }
    @Override public void addNotify() { super.addNotify(); displayed = this; Sound.addListener(update); timer.start(); refresh(); }
    @Override public void removeNotify() { if (displayed == this) displayed = null; Sound.removeListener(update); timer.stop(); super.removeNotify(); }
    /** The Notifications page currently attached to a window, or null. EDT only. */
    public static NotificationsGUI displayed() { return displayed; }

    /**
     * KEY-3: shows an exact known dungeon in Key-pops with its current choice visible. No dungeon or
     * alert choice changes; only the view filter is adjusted and restored by Back/Done. Unknown names
     * are reported explicitly and nothing is focused. {@code back} (optional) returns to the source.
     */
    public boolean focusDungeon(String name, Runnable back) {
        refreshDungeons();
        selectSection(KEY_POPS);
        JCheckBox box = name == null ? null : dungeonChoices.get(name);
        if (box == null) {
            showFocus("\u201c" + name + "\u201d is not a known notification dungeon, so nothing was focused or changed.", back);
            return false;
        }
        if (focusedDungeon == null) { priorSearch = dungeonSearch.getText(); priorSelectedOnly = dungeonSelectedOnly.isSelected(); }
        focusedDungeon = name;
        dungeonSelectedOnly.setSelected(false); dungeonSearch.setText(name); filterDungeons();
        showFocus("From Key-pops: " + name + " is currently " + (box.isSelected() ? "selected" : "not selected")
            + " for key-pop alerts. Nothing was changed; use its checkbox to change it.", back);
        box.scrollRectToVisible(new Rectangle(box.getSize())); box.requestFocusInWindow();
        return true;
    }
    String focusedDungeon() { return focusedDungeon; }
    /** ALERT-4: selects a recorded decision in Recent decisions. Returns false when it is no longer retained. */
    public boolean focusDecision(long id) { selectSection(DECISIONS); decisions.refresh(); decisions.scrollPageToTop(); return decisions.select(id, true); }
    boolean focusRealmRule(String id) {
        selectSection("Realm events");
        for (Component c : realmList.getComponents()) {
            JTextField phrase = find(c, "realm-phrase-" + id);
            if (phrase != null) { phrase.scrollRectToVisible(new Rectangle(phrase.getSize())); phrase.requestFocusInWindow(); return true; }
        }
        return false;
    }
    boolean focusSound(String id) {
        for (AlertRow row : rows) if (row.sound.id.equals(id)) {
            selectSection(row.sound.group); row.enabled.scrollRectToVisible(new Rectangle(row.enabled.getSize())); row.enabled.requestFocusInWindow(); return true;
        }
        return false;
    }
    private static JTextField find(Component c, String name) {
        if (c instanceof JTextField && name.equals(c.getName())) return (JTextField)c;
        if (c instanceof Container) for (Component child : ((Container)c).getComponents()) { JTextField found = find(child, name); if (found != null) return found; }
        return null;
    }
    private void showFocus(String message, Runnable back) {
        focusText.setText(message); focusReturn = back; focusBack.setVisible(back != null);
        focusBanner.setVisible(true); focusBanner.revalidate();
    }
    /** Hides the focus banner and restores the dungeon search and Selected-only filter used before the handoff. */
    public void clearFocus() {
        if (focusedDungeon != null) {
            dungeonSelectedOnly.setSelected(priorSelectedOnly); dungeonSearch.setText(priorSearch == null ? "" : priorSearch); filterDungeons();
        }
        focusedDungeon = null; focusReturn = null; focusBanner.setVisible(false); focusBanner.revalidate();
    }
    /** Detached view state for Back navigation: tab, dungeon search/filter and focus. */
    Object viewState() { return new Object[]{tabs.getSelectedIndex(), dungeonSearch.getText(), dungeonSelectedOnly.isSelected()}; }
    void restoreViewState(Object state) {
        if (!(state instanceof Object[])) return;
        Object[] values = (Object[])state;
        focusedDungeon = null; focusReturn = null; focusBanner.setVisible(false);
        int tab = (Integer)values[0]; if (tab >= 0 && tab < tabs.getTabCount()) tabs.setSelectedIndex(tab);
        dungeonSelectedOnly.setSelected((Boolean)values[2]); dungeonSearch.setText((String)values[1]); filterDungeons();
    }
    private final class TimerHolder {
        final javax.swing.Timer value = new javax.swing.Timer(1000, e -> { if (isShowing()) realmStatus.setText(RealmEventAlerts.INSTANCE.getLastMatchLabel()); });
        void start() { value.start(); } void stop() { value.stop(); }
    }
    public void selectSection(String section) {
        refreshDungeons(); refresh();
        // Sound groups and older callers name the key-pop tab "Key pops"; the tab shows the navigation term.
        String title = SOUND_GROUP_KEY_POPS.equals(section) ? KEY_POPS : section;
        if (title != null) for (int i = 0; i < tabs.getTabCount(); i++) if (title.equals(tabs.getTitleAt(i))) tabs.setSelectedIndex(i);
    }
    private AlertRow row(Sound sound) { AlertRow row = new AlertRow(sound); rows.add(row); return row; }
    private void refresh() {
        syncing = true;
        if (!master.getValueIsAdjusting()) master.setValue(Sound.getMasterVolume());
        masterValue.setText(master.getValue() + "%"); mute.setSelected(Sound.isMuted());
        status.setText(Sound.getLastStatus() + "\n" + Sound.getPreferenceStatus()); for (AlertRow row : rows) row.refresh();
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
        JPanel header = stack();
        focusText.setName("sound-dungeon-focus"); focusBanner.setName("sound-dungeon-focus-banner"); focusBanner.setVisible(false);
        focusBack.setName("sound-dungeon-focus-back"); focusClear.setName("sound-dungeon-focus-done");
        focusBack.addActionListener(e -> { Runnable back = focusReturn; clearFocus(); if (back != null) back.run(); });
        focusClear.addActionListener(e -> clearFocus());
        // A handoff focus belongs to the visit that opened it: leaving the page ends it and restores the filters.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing() && focusBanner.isVisible()) clearFocus();
        });
        JPanel focusActions = ContentStyle.controls(); focusActions.add(focusBack); focusActions.add(focusClear);
        focusBanner.add(focusText); focusBanner.add(focusActions, BorderLayout.SOUTH);
        focusBanner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, ContentStyle.color("violet")), BorderFactory.createEmptyBorder(2, 6, 2, 2)));
        header.add(focusBanner);
        header.add(note("Choose dungeons to hear their key-pops and portal callouts. Unselected dungeons remain in Key-pops history."));
        missing.setToolTipText("Dungeons with zero completes on the currently playing character"); missing.addActionListener(e -> saveDungeons()); header.add(missing);
        dungeonSearch.putClientProperty("JTextField.placeholderText", "Find a dungeon..."); dungeonSearch.setName("sound-dungeon-search");
        dungeonSearch.getAccessibleContext().setAccessibleName("Find notification dungeon"); header.add(dungeonSearch);
        JPanel buttons = ContentStyle.controls();
        JButton select = new JButton("Select shown"), clear = new JButton("Clear shown");
        select.addActionListener(e -> selectShown(true)); clear.addActionListener(e -> selectShown(false));
        dungeonSelectedOnly.setName("sound-dungeon-selected-only"); dungeonSelectedOnly.addActionListener(e -> filterDungeons());
        buttons.add(select); buttons.add(clear); buttons.add(dungeonSelectedOnly); buttons.add(dungeonCount); header.add(buttons);
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
        int shownSelected = 0;
        for (Map.Entry<String, JCheckBox> item : dungeonChoices.entrySet()) if (item.getKey().toLowerCase(Locale.ROOT).contains(query)
                && (!dungeonSelectedOnly.isSelected() || item.getValue().isSelected())) {
            dungeonList.add(item.getValue()); if (item.getValue().isSelected()) shownSelected++;
        }
        if (dungeonList.getComponentCount() == 0) dungeonList.add(new JLabel("No dungeons match this search."));
        long selected = dungeonChoices.values().stream().filter(AbstractButton::isSelected).count();
        dungeonCount.setText(selected + " selected · " + shownSelected + " selected shown · " + (selected - shownSelected) + " outside filter"); dungeonList.revalidate(); dungeonList.repaint();
    }
    private void selectShown(boolean selected) {
        for (Component c : dungeonList.getComponents()) if (c instanceof JCheckBox) ((JCheckBox)c).setSelected(selected); saveDungeons();
    }
    private void saveDungeons() {
        Set<String> selected = new TreeSet<>(); if (missing.isSelected()) selected.add("missingDungeons");
        dungeonChoices.forEach((name, box) -> { if (box.isSelected()) selected.add(name); });
        KeypopGUI.setSelectedDungeons(selected); filterDungeons();
        long request = ++dungeonSaveRequest;
        PropertiesManager.flush().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != dungeonSaveRequest || !KeypopGUI.getSelectedDungeons().equals(selected)) return;
            status.setText(failure == null && result != null && result.isSuccess() ? "Dungeon choices saved." : "Dungeon choices active; disk save failed. Change a choice to retry.");
        }));
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
            DraftSaveStatus saving = new DraftSaveStatus(save, "realm-save-" + rule.id);
            String[] expected = {rule.getPhrase()};
            phrase.getDocument().addDocumentListener(AlertRuleEditor.changes(saving::edited));
            save.addActionListener(e -> saving.submit(() -> {
                if (!RealmEventAlerts.INSTANCE.getRules().contains(rule) || !expected[0].equals(rule.getPhrase()))
                    throw new IllegalStateException("The active rule changed; reopen Notifications to load it.");
                RealmEventAlerts.INSTANCE.setPhrase(rule, phrase.getText()); expected[0] = rule.getPhrase(); return PropertiesManager.flush();
            }));
            JButton cancel = new JButton("Cancel draft"); cancel.addActionListener(e -> phrase.setText(rule.getPhrase()));
            remove.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Remove the alert for " + rule.name + "?", "Remove realm event", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    RealmEventAlerts.INSTANCE.remove(rule); rebuildRealmRules();
                }
            });
            save.getAccessibleContext().setAccessibleName("Save phrase for " + rule.name); remove.getAccessibleContext().setAccessibleName("Remove " + rule.name + " alert");
            editor.add(save); editor.add(cancel); editor.add(remove); card.add(editor); card.add(saving.status, BorderLayout.SOUTH); card.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0)); card.setMinimumSize(new Dimension(0, 0)); realmList.add(card);
        }
        if (realmList.getComponentCount() == 0) realmList.add(note("No realm event rules. Add an event and the phrase used in its announcement."));
        ContentStyle.refreshFonts(realmList); realmList.revalidate(); realmList.repaint(); refresh();
    }
    private void addRealmRule() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Add realm event", Dialog.ModalityType.APPLICATION_MODAL);
        realmshark.branding.AppIdentity.apply(dialog); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(createRealmDraft(PropertiesManager::flush)); dialog.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(640, screen.width), Math.min(450, screen.height)); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }
    JPanel createRealmDraft(java.util.function.Supplier<java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult>> durability) {
        JTextField name = new JTextField(), phrase = new JTextField();
        JLabel nameLabel = new JLabel("Event name"), phraseLabel = new JLabel("Announcement contains"); nameLabel.setLabelFor(name); phraseLabel.setLabelFor(phrase);
        name.getAccessibleContext().setAccessibleName("Realm event name"); phrase.getAccessibleContext().setAccessibleName("Realm event announcement phrase");
        name.setName("realm-draft-name"); phrase.setName("realm-draft-phrase");
        JPanel panel = new JPanel(new BorderLayout(8, 8)), fields = stack(), actions = ContentStyle.controls();
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        fields.add(note("Save creates a disabled event and fixes its name. Its phrase stays editable. Changes apply immediately; saving failures retain this editor. Close discards only unsubmitted edits."));
        fields.add(nameLabel); fields.add(name); fields.add(phraseLabel); fields.add(phrase);
        JButton save = new JButton("Save event"), cancel = new JButton("Cancel");
        DraftSaveStatus saving = new DraftSaveStatus(save, "realm-draft-save-status");
        RealmEventAlerts.Rule[] applied = new RealmEventAlerts.Rule[1];
        name.getDocument().addDocumentListener(AlertRuleEditor.changes(saving::edited));
        phrase.getDocument().addDocumentListener(AlertRuleEditor.changes(saving::edited));
        save.addActionListener(e -> saving.submit(() -> {
            if (applied[0] == null) { applied[0] = RealmEventAlerts.INSTANCE.add(name.getText(), phrase.getText()); name.setEditable(false); }
            else {
                if (!RealmEventAlerts.INSTANCE.getRules().contains(applied[0])) throw new IllegalStateException("Event was removed; close and create a new draft.");
                RealmEventAlerts.INSTANCE.setPhrase(applied[0], phrase.getText());
            }
            rebuildRealmRules(); return durability.get();
        }));
        cancel.addActionListener(e -> { Window window = SwingUtilities.getWindowAncestor(panel); if (window != null) window.dispose(); });
        actions.add(cancel); actions.add(save); panel.add(ContentStyle.page(fields, saving.status, new JPanel())); panel.add(actions, BorderLayout.SOUTH);
        ContentStyle.refreshFonts(panel); return panel;
    }
    private static String toneLabel(String tone) {
        switch (tone) {
            case "pm": return "Whisper chime";
            case "keypop": return "Key-pop";
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
            @Override protected void addImpl(Component component, Object constraints, int index) {
                if (component instanceof JComponent) ((JComponent)component).setAlignmentX(Component.LEFT_ALIGNMENT);
                super.addImpl(component, constraints, index);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel;
    }
    private static JPanel left(Component c) { JPanel panel = ContentStyle.controls(); panel.add(c); return panel; }
    private static JTextArea note(String text) {
        JTextArea area = ContentStyle.wrappingText(text);
        area.setFocusable(false); area.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4)); return area;
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
