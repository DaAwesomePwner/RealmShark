package tomato.gui.character;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.ObjectData;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.Stat;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CharacterPetsGUI extends JPanel {
    private static volatile CharacterPetsGUI INSTANCE;
    private final TomatoData data;
    private final Object petsLock = new Object();
    private final Map<Integer, Stat> pets = new HashMap<>();
    private long revision;
    private long displayedRevision = -1;
    private final JPanel petPanel = new PetList();
    private final JTextField feed = new JTextField("500", 8);
    private final JLabel validation = new SemanticLabel(" ", "rose");
    private final JButton calculate = new JButton("Recalculate feeding costs");
    private final Timer refreshTimer;
    private int feedPower = 500;
    private static final float[] FEED_MULTIPLIER = {1f, 0.65f, 0.3f};
    private static final StatType[] POINTS = {StatType.PET_FIRST_ABILITY_POINT_STAT, StatType.PET_SECOND_ABILITY_POINT_STAT, StatType.PET_THIRD_ABILITY_POINT_STAT};
    private static final StatType[] LEVELS = {StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT};
    private static final StatType[] ABILITIES = {StatType.PET_FIRST_ABILITY_TYPE_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT};

    public CharacterPetsGUI(TomatoData data) {
        this.data = data;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setFont(ContentStyle.body());
        petPanel.setLayout(new BoxLayout(petPanel, BoxLayout.Y_AXIS));
        petPanel.add(emptyState());
        JScrollPane scroll = new JScrollPane(petPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(40);
        add(scroll, BorderLayout.CENTER);

        JPanel form = new JPanel(new BorderLayout(0, 4));
        JPanel controls = ContentStyle.controls();
        JLabel label = new JLabel("Feed power per item");
        label.setLabelFor(feed);
        controls.add(label);
        controls.add(feed);
        controls.add(calculate);
        validation.getAccessibleContext().setAccessibleName("Feed power validation");
        form.add(controls, BorderLayout.NORTH);
        form.add(validation, BorderLayout.SOUTH);
        add(form, BorderLayout.SOUTH);
        calculate.addActionListener(e -> recalculate());
        feed.addActionListener(e -> recalculate());
        feed.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { validateFeed(); }
            public void removeUpdate(DocumentEvent e) { validateFeed(); }
            public void changedUpdate(DocumentEvent e) { validateFeed(); }
        });
        refreshTimer = new Timer(100, e -> refreshPets());
        refreshTimer.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refreshPets(); refreshTimer.start(); }
                else refreshTimer.stop();
            }
        });
        ContentStyle.refreshFonts(this);
        INSTANCE = this;
    }

    private boolean validateFeed() {
        boolean valid;
        try { valid = Integer.parseInt(feed.getText().trim()) > 0; }
        catch (NumberFormatException e) { valid = false; }
        calculate.setEnabled(valid);
        String message = valid ? " " : "Enter a positive whole number (1–2,147,483,647).";
        validation.setText(message);
        feed.getAccessibleContext().setAccessibleDescription(valid ? "Feed power of each item used in the estimate" : message);
        return valid;
    }

    private void recalculate() {
        if (!validateFeed()) return;
        feedPower = Integer.parseInt(feed.getText().trim());
        synchronized (petsLock) { revision++; }
        refreshPets();
    }

    private static JLabel emptyState() {
        JLabel label = new JLabel("Enter the Pet Yard to see your pets.");
        label.setFont(ContentStyle.body());
        label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return label;
    }

    private void refreshPets() {
        if (!isShowing()) return;
        List<Stat> values;
        synchronized (petsLock) {
            if (displayedRevision == revision) return;
            values = new ArrayList<>(pets.values());
            displayedRevision = revision;
        }
        values.sort(Comparator.comparingInt((Stat s) -> value(s, StatType.PET_MAX_ABILITY_POWER_STAT, 0)).reversed());
        petPanel.removeAll();
        if (values.isEmpty()) petPanel.add(emptyState());
        for (Stat pet : values) petPanel.add(petCard(pet));
        petPanel.revalidate();
        petPanel.repaint();
    }

    private JPanel petCard(Stat pet) {
        JPanel card = new JPanel(new BorderLayout(8, 8)) {
            @Override public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                        ContentStyle.color("border")), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            }
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        card.setAlignmentX(LEFT_ALIGNMENT);
        int skin = value(pet, StatType.SKIN_ID, 1);
        int maxLevel = value(pet, StatType.PET_MAX_ABILITY_POWER_STAT, 0);
        String name = IdToAsset.objectName(skin);
        JLabel heading = new JLabel((name == null ? "Pet" : name) + " · Max level " + maxLevel,
                ImageBuffer.getOutlinedIcon(skin, 28), SwingConstants.LEFT);
        heading.setFont(ContentStyle.emphasis(ContentStyle.body()));
        card.add(heading, BorderLayout.NORTH);
        JPanel abilities = ContentStyle.responsiveGrid(3, 230, 8);
        for (int i = 0; i < 3; i++) abilities.add(abilityDetails(pet, i, maxLevel));
        card.add(abilities, BorderLayout.CENTER);
        return card;
    }

    private JPanel abilityDetails(Stat pet, int index, int maxLevel) {
        JPanel details = new JPanel(new GridLayout(0, 1, 0, 2));
        int level = value(pet, LEVELS[index], 0);
        int points = (int) (value(pet, POINTS[index], 0) / FEED_MULTIPLIER[index]);
        JLabel title = detail(abilityName(value(pet, ABILITIES[index], 0)) + " · Level " + level);
        title.setFont(ContentStyle.body());
        details.add(title);
        details.add(detail("Feed power: " + points));
        if ((index == 1 && maxLevel < 50) || (index == 2 && maxLevel < 90)) {
            JLabel locked = new SemanticLabel("Locked ability", "muted");
            locked.setFont(ContentStyle.body());
            details.add(locked);
            return details;
        }
        int nextLevel = Math.min(level + 1, maxLevel);
        int maxPoints = requiredPoints(index, maxLevel);
        int nextPoints = requiredPoints(index, nextLevel);
        int fullCount = (int) Math.ceil((double) (maxPoints - points) / feedPower);
        int nextCount = (int) Math.ceil((double) (nextPoints - points) / feedPower);
        int cost = feedCost(maxLevel);
        JLabel items = detail(fullCount > 0 ? "Items to max: " + fullCount : "Fully fed");
        items.setToolTipText("<html>Feed points " + points + " / " + maxPoints + "<br>Next level " + nextLevel
                + (level != maxLevel ? "<br>Feed missing " + (nextPoints - points) + "<br>Items needed " + nextCount
                + "<br>Fame needed " + ((long) nextCount * cost) : "") + "</html>");
        details.add(items);
        details.add(detail(fullCount > 0 ? "Fame to max: " + ((long) fullCount * cost) : "Fame to max: —"));
        return details;
    }

    private static JLabel detail(String text) {
        JLabel label = new JLabel(text);
        label.setFont(ContentStyle.body());
        return label;
    }

    private static int requiredPoints(int ability, int level) {
        return (int) (((20 / FEED_MULTIPLIER[ability]) * (Math.pow(1.08, level - 1) - 1)) / (1.08 - 1));
    }

    private static int feedCost(int level) {
        switch (level) {
            case 30: return 15;
            case 50: return 50;
            case 70: return 175;
            case 90: return 625;
            case 100: return 1750;
            default: return -1;
        }
    }

    private static String abilityName(int id) {
        switch (id) {
            case 402: return "Attack close";
            case 404: return "Attack mid";
            case 405: return "Attack far";
            case 406: return "Electric";
            case 407: return "Heal";
            case 408: return "Magic heal";
            case 409: return "Savage";
            case 410: return "Decoy";
            case 411: return "Rising fury";
            default: return "Unknown ability";
        }
    }

    private static int value(Stat stat, StatType type, int fallback) {
        StatData value = stat.get(type);
        return value == null ? fallback : value.statValue;
    }

    private static Stat snapshot(Stat source) {
        Stat copy = new Stat();
        for (StatType type : StatType.values()) {
            StatData original = source.get(type);
            if (original == null) continue;
            StatData value = new StatData();
            value.statType = type;
            value.statTypeNum = type.get();
            value.statValue = original.statValue;
            value.statValueTwo = original.statValueTwo;
            value.stringStatValue = original.stringStatValue;
            copy.set(type, value);
        }
        return copy;
    }

    public static void addPet(ObjectData object) {
        CharacterPetsGUI panel = INSTANCE;
        if (panel == null || object == null || object.status == null || object.status.stats == null) return;
        Stat copy = snapshot(new Stat(object.status.stats));
        boolean isPet = false;
        for (StatData stat : object.status.stats) if (stat.statTypeNum > 80 && stat.statTypeNum < 96) { isPet = true; break; }
        int id = value(copy, StatType.PET_INSTANCE_ID_STAT, -1);
        synchronized (panel.petsLock) {
            if (!isPet && !panel.pets.containsKey(id)) return;
            Stat previous = panel.pets.get(id);
            if (previous != null) {
                Stat merged = snapshot(previous);
                for (StatType type : StatType.values()) {
                    StatData value = copy.get(type);
                    if (value != null) merged.set(type, value);
                }
                copy = merged;
            }
            panel.pets.put(id, copy);
            panel.revision++;
        }
    }

    public static void clearPets() {
        CharacterPetsGUI panel = INSTANCE;
        if (panel == null) return;
        synchronized (panel.petsLock) { panel.pets.clear(); panel.revision++; }
    }

    public static void updateEquipedPet() {
        CharacterPetsGUI panel = INSTANCE;
        if (panel == null || panel.data == null) return;
        Entity pet = panel.data.pet;
        if (pet == null) return;
        Stat copy = snapshot(pet.stat);
        synchronized (panel.petsLock) {
            panel.pets.put(value(copy, StatType.PET_INSTANCE_ID_STAT, -1), copy);
            panel.revision++;
        }
    }

    private static class PetList extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(760, 500); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 40; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(40, visible.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static class SemanticLabel extends JLabel {
        private final String role;
        SemanticLabel(String text, String role) {
            super(text);
            this.role = role;
            setForeground(ContentStyle.color(role));
        }
        @Override public void updateUI() {
            super.updateUI();
            if (role != null) setForeground(ContentStyle.color(role));
        }
    }
}
