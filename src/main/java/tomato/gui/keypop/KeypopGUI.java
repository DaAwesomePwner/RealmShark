package tomato.gui.keypop;

import packets.data.enums.NotificationEffectType;
import packets.incoming.NotificationPacket;
import tomato.backend.data.TomatoData;
import tomato.realmshark.Sound;
import tomato.realmshark.RealmCharacterStats;
import tomato.realmshark.enums.CharacterStatistics;
import tomato.gui.modern.ContentStyle;
import tomato.gui.maingui.DraftSaveStatus;
import util.PropertiesManager;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GUI class for popping dungeons.
 */
public class KeypopGUI extends JPanel {
    private JComponent queriedWorkspace;
    /** Coordinator shell registration: keypopPanel.workspace(). */
    public JComponent workspace() {
        if (queriedWorkspace != null) return queriedWorkspace;
        dashboard.enableLiveState(tomato.gui.history.ViewStateStore.application());
        tomato.history.SessionStore store = tomato.history.AppHistory.store();
        if (store == null) return this;
        KeyPopArchiveClient client = new KeyPopArchiveClient(store.directory().resolve(".query-scratch/keypops"));
        tomato.gui.history.ArchiveWorkspace<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> workspace =
            tomato.gui.history.SessionPanel.queried(store, "keypops", this, client, tomato.gui.history.ViewStateStore.application());
        client.bind(workspace); queriedWorkspace = workspace; return workspace;
    }
    public static tomato.gui.history.SessionPanel.Loaded history(tomato.history.SessionStore store, String scope, int page, String query) throws IOException {
        tomato.gui.history.HistoryPage<KeyPopEvent> events = tomato.gui.history.HistoryPage.read(store, scope, "keypops", KeyPopEvent.class, page, query,
                event -> event.time + " " + event.player + " " + event.kind + " " + event.item);
        return new tomato.gui.history.SessionPanel.Loaded(() -> {
            KeyPopHistory history = new KeyPopHistory();for (KeyPopEvent event : events.values) history.add(event);
            return new KeyPopDashboard(history,true);
        }, events.more(), events.description());
    }

    private static final KeyPopHistory history = new KeyPopHistory();
    private static KeyPopDashboard dashboard;
    private static volatile boolean logToFile = false;
    private static volatile String loggingError = "";
    private static final String LOG_FILE = "keypops.log";
    private static final Pattern calloutParsePlayer = Pattern.compile("([^;]+);");

    private static volatile Set<String> selectedDungeons = Collections.emptySet();

    public KeypopGUI() {
        loadDungeonChoices();
        loadLoggingPreference();

        setLayout(new BorderLayout());
        dashboard = new KeyPopDashboard(history);
        add(dashboard);

        JPanel south = ContentStyle.controls();
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        JButton clearButton = new JButton("Clear history");
        clearButton.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Clear the live key-pop buffer and statistics? Saved session history is kept.", "Clear key pops", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) dashboard.clearHistory();
        });

        JButton notificationButton = new JButton("Notifications");
        notificationButton.addActionListener(e -> tomato.gui.TomatoGUI.openNotifications("Key pops"));
        south.add(notificationButton);

        JCheckBox logCheckbox = new JCheckBox("Log to file");
        logCheckbox.setSelected(logToFile);
        logCheckbox.addActionListener(e -> {
            logToFile = logCheckbox.isSelected();
            saveLoggingPreference();
        });
        south.add(logCheckbox);

        JButton exportButton = new JButton("Export events (retained CSV)");
        exportButton.addActionListener(e -> dashboard.exportCsv());
        south.add(exportButton);
        JButton summaryExport = new JButton("Export current tab (retained CSV)"); summaryExport.addActionListener(e -> dashboard.exportCurrentTab()); south.add(summaryExport);
        south.add(clearButton);

        add(south, BorderLayout.SOUTH);
    }

    /**
     * Packet parser for notification packets that will be used to add key, vial, rune or inc pops.
     *
     * @param data
     * @param packet Notification packet containing info about who pops keys, vial, runes or inc pops.
     */
    public static void packet(TomatoData data, NotificationPacket packet) {
        KeyPopEvent event = KeyPopEvent.fromPacket(packet, Instant.now());
        if (event != null) {
            record(event);
            if (event.kind == KeyPopEvent.Kind.KEY) playDungeonSound(data, event.item);
        } else if (packet != null && packet.message != null && packet.effect == NotificationEffectType.PlayerCallout) {
            String msg = packet.message;
            Matcher playerMatcher = calloutParsePlayer.matcher(msg);
            String playerName = "";
            if (playerMatcher.find()) {
                playerName = playerMatcher.group(1);
            }
            String dungeonName = KeyPopEvent.field(msg, "name");

            if (!playerName.isEmpty() && !dungeonName.isEmpty()) {
                playDungeonSound(data, dungeonName);
            }
        }
    }

    private static void record(KeyPopEvent event) {
        tomato.history.AppHistory.append("keypops", event);
        history.add(event);
        if (logToFile) logToFile(event.logLine());
    }

    public static void playDungeonSound(TomatoData data, String dungeonName) {
        if (Sound.keypop == null) return;
        if (shouldNotify(dungeonName, data == null ? null : data.getCurrentDungeonStats())) {
            Sound.keypop.play();
        }
    }

    /**
     * Dungeon the currently playing character missing a complete on.
     *
     * @param data        Character data containing dungeon completes.
     * @param dungeonName Name of the dungeon the player is missing
     * @return True if missing the dungeon.
     */
    private static boolean isMissingDungeon(RealmCharacterStats data, String dungeonName) {
        if (data == null) return false;
        int completes = data.getDungeonInfoByName(dungeonName);

        return completes == 0;
    }

    /**
     * Accept the legacy time [player]: item format and optionally log to file.
     *
     * @param s The text to be added at the end of text area.
     */
    public static void appendTextAreaKeypop(String s) {
        if (s == null) return;
        for (String line : s.split("\\R")) {
            KeyPopEvent event = KeyPopEvent.fromLegacy(line, Instant.now());
            if (event != null) record(event);
        }
    }

    /**
     * Logs the key pop message to a file with timestamp.
     *
     * @param message The message to log
     */
    private static synchronized void logToFile(String message) {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(LOG_FILE), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String timestamp = KeyPopEvent.DATE_TIME.format(Instant.now());
            writer.write(timestamp + " " + message);
            writer.newLine();
            loggingError = "";
        } catch (IOException e) {
            if (!e.getMessage().equals(loggingError)) {
                loggingError = e.getMessage();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(dashboard, "Key pop logging failed: " + loggingError, "Log file unavailable", JOptionPane.ERROR_MESSAGE));
            }
        }
    }

    /**
     * Sets the font of the text area.
     *
     * @param font Font to be set.
     */
    public static void editFont(Font font) {
        Runnable change = () -> { if (dashboard != null) dashboard.editFont(font); };
        if (SwingUtilities.isEventDispatchThread()) change.run(); else SwingUtilities.invokeLater(change);
    }

    /**
     * Dialog window used to display notification dungeons.
     */
    private static void showConfigureDialog() {
        createConfigureDialog().setVisible(true);
    }

    static JDialog createConfigureDialog() {
        JDialog configureDialog = new JDialog(SwingUtilities.getWindowAncestor(dashboard), "Key pop notifications", Dialog.ModalityType.APPLICATION_MODAL);
        realmshark.branding.AppIdentity.apply(configureDialog);
        configureDialog.setLayout(new BorderLayout());
        configureDialog.setMinimumSize(new Dimension(600, 380));

        JCheckBox[] checkboxes = createDungeonCheckboxes();
        JCheckBox missingDungeons = new JCheckBox("Missing dungeon completes on current character", isMissingDungeonsSelected());
        missingDungeons.setToolTipText("Notifies dungeon pops for current character missing dungeon completes");
        JTextField find = new JTextField();
        find.putClientProperty("JTextField.placeholderText", "Find a dungeon…");
        find.getAccessibleContext().setAccessibleName("Find notification dungeon");

        JButton selectAllButton = new JButton("Select shown");
        selectAllButton.addActionListener(e -> {
            for (JCheckBox checkbox : checkboxes) {
                if (checkbox.getParent() != null) checkbox.setSelected(true);
            }
        });

        JButton unselectAllButton = new JButton("Unselect shown");
        unselectAllButton.addActionListener(e -> {
            for (JCheckBox checkbox : checkboxes) {
                if (checkbox.getParent() != null) checkbox.setSelected(false);
            }
        });

        JButton applyButton = new JButton("Apply");
        DraftSaveStatus saving = new DraftSaveStatus(applyButton, "keypop-save-status");
        java.util.List<Set<String>> expected = new java.util.ArrayList<>(); expected.add(getSelectedDungeons());
        applyButton.addActionListener(e -> {
            Set<String> next = new TreeSet<>();
            if (missingDungeons.isSelected()) {
                next.add("missingDungeons");
            }
            for (JCheckBox checkbox : checkboxes) {
                if (checkbox.isSelected()) {
                    next.add(checkbox.getText());
                }
            }
            saving.submit(() -> {
                if (!getSelectedDungeons().equals(expected.get(0))) throw new IllegalStateException("Dungeon choices changed elsewhere. Reopen this editor; your draft remains here.");
                setSelectedDungeons(next); expected.set(0, getSelectedDungeons()); return PropertiesManager.flush();
            });
        });

        JPanel buttonPanel = ContentStyle.controls();
        buttonPanel.add(selectAllButton);
        buttonPanel.add(unselectAllButton);
        JButton cancel = new JButton("Cancel"); cancel.addActionListener(e -> configureDialog.dispose()); buttonPanel.add(cancel);
        buttonPanel.add(applyButton);
        JButton test = new JButton("Test sound"); test.addActionListener(e -> Sound.keypop.preview(null)); buttonPanel.add(test);
        for (JCheckBox box : checkboxes) box.addItemListener(e -> saving.edited());
        missingDungeons.addItemListener(e -> saving.edited());

        JPanel checkboxPanel = new JPanel(new GridLayout(0, 2, 8, 5));
        for (JCheckBox checkbox : checkboxes) {
            checkboxPanel.add(checkbox);
        }

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        north.add(missingDungeons, BorderLayout.NORTH); north.add(find);
        configureDialog.add(north, BorderLayout.NORTH);
        JPanel list = new JPanel(new BorderLayout()); list.add(checkboxPanel, BorderLayout.NORTH);
        list.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        configureDialog.add(new JScrollPane(list), BorderLayout.CENTER);
        KeyPopDashboard.onChange(find, () -> {
            String query = find.getText().trim().toLowerCase(java.util.Locale.ROOT);
            checkboxPanel.removeAll();
            for (JCheckBox checkbox : checkboxes) if (checkbox.getText().toLowerCase(java.util.Locale.ROOT).contains(query)) checkboxPanel.add(checkbox);
            checkboxPanel.revalidate(); checkboxPanel.repaint();
        });
        JPanel footer = new JPanel(new BorderLayout()); footer.add(saving.status); footer.add(buttonPanel, BorderLayout.SOUTH);
        configureDialog.add(footer, BorderLayout.SOUTH);
        configureDialog.setSize(700, 500);
        configureDialog.setLocationRelativeTo(dashboard);
        return configureDialog;
    }

    /**
     * Notification selection list creation.
     *
     * @return Checkbox objects from dungeon list.
     */
    private static JCheckBox[] createDungeonCheckboxes() {
        TreeSet<String> names = new TreeSet<>(CharacterStatistics.DUNGEON_NAMES);
        names.addAll(selectedDungeons); names.remove("missingDungeons"); names.remove("");
        JCheckBox[] checkboxes = new JCheckBox[names.size()];
        int i = 0;
        for (String o : names) {
            checkboxes[i] = new JCheckBox(o);
            checkboxes[i].setSelected(selectedDungeons.contains(o));
            i++;
        }
        return checkboxes;
    }

    public static Set<String> getSelectedDungeons() { return new TreeSet<>(selectedDungeons); }
    public static void setSelectedDungeons(Set<String> selected) {
        selectedDungeons = Collections.unmodifiableSet(new TreeSet<>(selected));
        saveDungeonChoices();
    }
    public static Set<String> getDungeonNames() {
        TreeSet<String> names = new TreeSet<>(CharacterStatistics.DUNGEON_NAMES);
        names.addAll(selectedDungeons); names.remove("missingDungeons"); names.remove("");
        return names;
    }
    public static boolean shouldNotify(String dungeonName, RealmCharacterStats stats) {
        return selectedDungeons.contains(dungeonName)
                || (isMissingDungeonsSelected() && isMissingDungeon(stats, dungeonName));
    }

    private static boolean isMissingDungeonsSelected() {
        return selectedDungeons.contains("missingDungeons");
    }

    /**
     * Saves selection to disk
     */
    private static void saveDungeonChoices() {
        StringBuilder sb = new StringBuilder();
        boolean first = false;
        for (String s : selectedDungeons) {
            if (first) {
                sb.append(",");
            }
            first = true;
            sb.append(s);
        }
        String string = sb.toString();
        PropertiesManager.setProperties("keypopSound", string);
    }

    /**
     * Loads selection from disk
     */
    private static void loadDungeonChoices() {
        String keySound = PropertiesManager.getProperty("keypopSound");

        if (keySound != null) {
            String[] list = keySound.split(",");
            selectedDungeons = new HashSet<>(Arrays.asList(list));
        }
    }

    /**
     * Loads logging preference from disk
     */
    private static void loadLoggingPreference() {
        String loggingPref = PropertiesManager.getProperty("keypopLogging");
        logToFile = loggingPref != null && Boolean.parseBoolean(loggingPref);
    }

    /**
     * Saves logging preference to disk
     */
    private static void saveLoggingPreference() {
        PropertiesManager.setProperties("keypopLogging", String.valueOf(logToFile));
    }
}
