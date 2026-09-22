package tomato.gui.character;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.ObjectData;
import packets.data.enums.StatType;
import tomato.backend.data.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.Formatters;
import tomato.realmshark.ParseEquipment;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.*;
import java.util.List;

/** Detached, source-scoped pet observations and explicit local feeding scenarios. */
public class CharacterPetsGUI extends JPanel {
    private static volatile CharacterPetsGUI INSTANCE;
    private final TomatoData data;
    private final ProgressionData source;
    private final JPanel petPanel = new PetList();
    private final JTextField feed = new JTextField("500", 8), itemSearch = new JTextField(16);
    private final JComboBox<String> items = new JComboBox<>();
    private final JLabel validation = new SemanticLabel(" ", "rose");
    private final JTextArea context = ContentStyle.wrappingText(""), catalogStatus = ContentStyle.wrappingText("Loading local feed items…");
    private final JButton calculate = new JButton("Recalculate feeding costs");
    private final javax.swing.Timer refreshTimer;
    private final Map<String, Integer> catalog = new LinkedHashMap<>();
    private int feedPower = 500;
    private long displayedRevision = -1;
    private boolean choosing;
    private long itemRequest;
    private SwingWorker<ParseEquipment.Equipment, Void> itemWorker;
    private String scenario = "Manual override";
    private static final StatType[] POINTS = {StatType.PET_FIRST_ABILITY_POINT_STAT, StatType.PET_SECOND_ABILITY_POINT_STAT, StatType.PET_THIRD_ABILITY_POINT_STAT};
    private static final StatType[] LEVELS = {StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT};
    private static final StatType[] ABILITIES = {StatType.PET_FIRST_ABILITY_TYPE_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT};

    public CharacterPetsGUI(TomatoData data) {
        this.data = data;
        feed.setName("pet-feed-power");
        source = data == null ? new ProgressionData() : data.progression();
        setLayout(new BorderLayout(8, 8)); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        petPanel.setLayout(new BoxLayout(petPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(petPanel) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, petPanel.getFontMetrics(ContentStyle.body()).getHeight() * 5 + insets.top + insets.bottom);
            }
        };
        scroll.setName("pet-list-scroll"); scroll.getVerticalScrollBar().setUnitIncrement(40);
        context.setName("pet-capture-context"); context.getAccessibleContext().setAccessibleName("Pet account and capture context");

        JPanel form = new JPanel(new BorderLayout(0, 4));
        JPanel controls = ContentStyle.controls();
        JLabel label = new JLabel("Feed power per item"); label.setLabelFor(feed);
        controls.add(label); controls.add(feed); controls.add(calculate);
        JPanel selector = ContentStyle.controls();
        JLabel searchLabel = new JLabel("Find feed item / enter ID"); searchLabel.setLabelFor(itemSearch);
        selector.add(searchLabel); selector.add(itemSearch);
        items.setPrototypeDisplayValue("Item #12345 · Feed item · 1000 FP");
        items.getAccessibleContext().setAccessibleName("Feed item from local assets"); selector.add(items);
        JButton useId = new JButton("Use item ID"); selector.add(useId);
        JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(selector, BorderLayout.NORTH); top.add(catalogStatus, BorderLayout.SOUTH);
        form.add(top, BorderLayout.NORTH); form.add(controls); form.add(validation, BorderLayout.SOUTH);
        JScrollPane page = ContentStyle.page(context, scroll, form);
        page.setName("pet-page-scroll");
        page.getAccessibleContext().setAccessibleName("Pets; scroll for captured pets, feeding estimates and scenario controls");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{context, catalogStatus, itemSearch, items, useId, feed, calculate}) revealOnFocus(control);
        validation.getAccessibleContext().setAccessibleName("Feed power validation");
        calculate.addActionListener(e -> recalculate()); feed.addActionListener(e -> recalculate());
        feed.getDocument().addDocumentListener(changes(() -> { itemRequest++; scenario = "Manual override"; validateFeed(); }));
        itemSearch.getDocument().addDocumentListener(changes(this::filterItems));
        items.addActionListener(e -> {
            if (choosing) return;
            String selected = (String)items.getSelectedItem();
            if (selected != null && catalog.containsKey(selected)) chooseFeed(selected, catalog.get(selected));
        });
        useId.addActionListener(e -> lookupItem()); itemSearch.addActionListener(e -> lookupItem());
        refreshTimer = new javax.swing.Timer(500, e -> refreshPets()); refreshTimer.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refreshPets(); refreshTimer.start(); } else refreshTimer.stop();
            }
        });
        ContentStyle.refreshFonts(this); INSTANCE = this;
        loadCatalog(); refreshPets();
    }

    private static DocumentListener changes(Runnable action) { return new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { action.run(); }
        public void removeUpdate(DocumentEvent e) { action.run(); }
        public void changedUpdate(DocumentEvent e) { action.run(); }
    }; }

    private static void revealOnFocus(JComponent control) {
        control.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                if (control instanceof JTextArea) revealCaret((JTextArea) control);
                else ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight()));
            }
        });
        if (control instanceof JTextArea) {
            JTextArea text = (JTextArea) control;
            text.addCaretListener(e -> {
                if (text.isFocusOwner()) SwingUtilities.invokeLater(() -> { if (text.isFocusOwner()) revealCaret(text); });
            });
        }
    }

    private static void revealCaret(JTextArea text) {
        try {
            Rectangle caret = text.modelToView(text.getCaretPosition());
            if (caret != null) ContentStyle.reveal(text, caret);
        } catch (javax.swing.text.BadLocationException e) { throw new IllegalStateException("Cannot reveal pet details", e); }
    }

    private void loadCatalog() {
        new SwingWorker<Map<String, Integer>, Void>() {
            protected Map<String, Integer> doInBackground() {
                Map<String, Integer> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                try {
                    for (ParseEquipment.Equipment item : ParseEquipment.getParseItems()) if (item.feedpower > 0)
                        result.put(itemLabel(item), item.feedpower);
                } catch (RuntimeException | LinkageError unavailable) { /* Manual scenario remains usable. */ }
                return result;
            }
            protected void done() {
                try { catalog.putAll(get()); }
                catch (Exception unavailable) { catalog.clear(); }
                catalogStatus.setText(catalog.isEmpty() ? "Local feed catalog unavailable. Enter feed power manually."
                    : "Search local equipment, or enter any item ID (including food). Positive catalog feed power only; manual override available.");
                filterItems();
            }
        }.execute();
    }
    private static String itemLabel(ParseEquipment.Equipment item) { return "Item #" + item.id + " · " + item.name() + " · " + item.feedpower + " FP"; }
    private void filterItems() {
        choosing = true;
        String query = itemSearch.getText().trim().toLowerCase(Locale.ROOT);
        items.removeAllItems(); items.addItem("Manual override");
        for (String label : catalog.keySet()) if (label.toLowerCase(Locale.ROOT).contains(query)) items.addItem(label);
        choosing = false;
    }
    private void lookupItem() {
        final int id;
        try { id = Integer.decode(itemSearch.getText().trim()); }
        catch (NumberFormatException invalid) { catalogStatus.setText("Choose a matching item, or enter its numeric/hex item ID."); return; }
        if (itemWorker != null && !itemWorker.isDone()) { catalogStatus.setText("Item lookup in progress; please wait."); return; }
        final long request = ++itemRequest;
        itemWorker = new SwingWorker<ParseEquipment.Equipment, Void>() {
            protected ParseEquipment.Equipment doInBackground() {
                try { return ParseEquipment.getEquipmentById(id); } catch (RuntimeException | LinkageError unavailable) { return null; }
            }
            protected void done() {
                if (request != itemRequest) return;
                try {
                    ParseEquipment.Equipment item = get();
                    if (item != null && item.feedpower > 0) {
                        String label = itemLabel(item); catalog.put(label, item.feedpower); chooseFeed(label, item.feedpower);
                        catalogStatus.setText("Using " + label + ". Edit feed power for a manual override.");
                    } else catalogStatus.setText("No usable catalog feed power for item #" + id + ". Enter feed power manually.");
                } catch (Exception unavailable) { catalogStatus.setText("Item lookup unavailable. Enter feed power manually."); }
            }
        };
        itemWorker.execute();
    }
    private void chooseFeed(String label, int power) { feed.setText(Integer.toString(power)); scenario = label; recalculate(); }
    private boolean validateFeed() {
        boolean valid;
        try { valid = Integer.parseInt(feed.getText().trim()) > 0; } catch (NumberFormatException e) { valid = false; }
        calculate.setEnabled(valid);
        String message = valid ? " " : "Enter a positive whole number (1–2,147,483,647).";
        validation.setText(message); feed.getAccessibleContext().setAccessibleDescription(valid ? "Feed power of each item used in the estimate" : message);
        return valid;
    }
    private void recalculate() {
        if (!validateFeed()) return;
        feedPower = Integer.parseInt(feed.getText().trim()); displayedRevision = -1; refreshPets();
    }

    private void refreshPets() {
        ProgressionData.Snapshot snapshot = source.snapshot();
        context.setText(snapshot.scope.description() + " · " + (snapshot.scope.accepting ? "Captured observations" : "Capture stopped")
            + "\nEquipped pet: " + (snapshot.equipped == TomatoData.PetAvailability.ABSENT ? "Explicitly absent"
            : snapshot.equippedId == null ? "Not captured" : "Instance " + snapshot.equippedId));
        if (displayedRevision == snapshot.revision) return;
        displayedRevision = snapshot.revision;
        List<ProgressionData.Pet> values = new ArrayList<>(snapshot.pets);
        values.sort(Comparator.comparing((ProgressionData.Pet p) -> p.value(StatType.PET_MAX_ABILITY_POWER_STAT), Comparator.nullsLast(Comparator.reverseOrder())));
        petPanel.removeAll();
        if (values.isEmpty()) petPanel.add(detail("Enter the Pet Yard to see your pets."));
        for (ProgressionData.Pet pet : values) petPanel.add(petCard(pet, snapshot));
        petPanel.revalidate(); petPanel.repaint();
    }

    private JPanel petCard(ProgressionData.Pet pet, ProgressionData.Snapshot snapshot) {
        JPanel card = new JPanel(new BorderLayout(8, 8)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); card.setAlignmentX(LEFT_ALIGNMENT);
        Integer skin = pet.value(StatType.SKIN_ID), id = pet.value(StatType.PET_INSTANCE_ID_STAT), cap = pet.value(StatType.PET_MAX_ABILITY_POWER_STAT);
        String name = pet.text(StatType.PET_NAME_STAT);
        if (name == null && skin != null) name = IdToAsset.objectName(skin);
        String equipped = snapshot.equipped == TomatoData.PetAvailability.ABSENT ? "Not equipped"
            : snapshot.equippedId == null || id == null ? "Equipped state unknown" : snapshot.equippedId.equals(id) ? "Equipped" : "Not equipped";
        String heading = (name == null ? "Pet" : name) + " · " + (id == null ? "Instance not captured (object " + pet.objectId + ")" : "Instance " + id)
            + " · " + equipped + "\nMax level " + known(cap) + " · " + pet.source + " · " + timestamp(pet.capturedAt);
        JTextArea title = ContentStyle.wrappingText(heading); title.setFont(ContentStyle.emphasis(ContentStyle.body()));
        revealOnFocus(title);
        JPanel identity = new JPanel(new BorderLayout(8, 0)); identity.add(title, BorderLayout.CENTER);
        if (skin != null) identity.add(new JLabel(ImageBuffer.getOutlinedIcon(skin, 28)), BorderLayout.WEST);
        card.add(identity, BorderLayout.NORTH);
        JPanel abilities = ContentStyle.responsiveGrid(3, 230, 8);
        for (int i = 0; i < 3; i++) abilities.add(abilityDetails(pet, i, cap));
        card.add(abilities, BorderLayout.CENTER); return card;
    }

    private JPanel abilityDetails(ProgressionData.Pet pet, int index, Integer cap) {
        JPanel details = new JPanel(new BorderLayout(0, 4));
        Integer level = pet.value(LEVELS[index]), points = pet.value(POINTS[index]);
        PetFeeding.Estimate estimate = PetFeeding.estimate(index, level, points, cap, feedPower);
        details.add(detail(abilityName(pet.value(ABILITIES[index])) + " · Level " + known(level)), BorderLayout.NORTH);
        String text = "Captured points: " + known(points) + " · " + evidence(pet.field(POINTS[index]))
            + "\nLevel: " + evidence(pet.field(LEVELS[index])) + "\nMax level: " + evidence(pet.field(StatType.PET_MAX_ABILITY_POWER_STAT))
            + "\n" + scenario + " · " + feedPower + " FP/item · Ability multiplier " + PetFeeding.multiplier(index)
            + "\nNext level items: " + known(estimate.nextItems) + " · Fame: " + known(estimate.nextFame)
            + "\nItems to max: " + known(estimate.maxItems) + " · Fame to max: " + known(estimate.maxFame)
            + "\n" + (estimate.fullyFed ? "Fully fed at captured maximum level. " : "") + estimate.reason;
        JTextArea explanation = ContentStyle.wrappingText(text); explanation.setFocusable(true);
        revealOnFocus(explanation);
        explanation.getAccessibleContext().setAccessibleName("Ability " + (index + 1) + " feeding inputs and estimate");
        details.add(explanation, BorderLayout.CENTER);
        if (estimate.locked) details.add(new SemanticLabel("Locked ability", "muted"), BorderLayout.SOUTH);
        return details;
    }
    private static String evidence(FieldCapture field) { return field == null ? "Not captured" : field.source + " · " + timestamp(field.at); }
    private static String timestamp(long at) { return at <= 0 ? "Time unknown" : Formatters.formatTimestamp(at); }
    private static String known(Object value) { return value == null ? "Not captured / unavailable" : value.toString(); }
    private static JLabel detail(String text) { JLabel label = new JLabel(text); label.setFont(ContentStyle.body()); return label; }
    private static String abilityName(Integer id) {
        if (id == null) return "Ability not captured";
        switch (id) { case 402: return "Attack close"; case 404: return "Attack mid"; case 405: return "Attack far";
            case 406: return "Electric"; case 407: return "Heal"; case 408: return "Magic heal"; case 409: return "Savage";
            case 410: return "Decoy"; case 411: return "Rising fury"; default: return "Unknown ability #" + id; }
    }

    /** Legacy standalone fixture/preview entry points cannot publish into a bound live source. */
    public static void addPet(ObjectData object) {
        CharacterPetsGUI panel = INSTANCE;
        if (panel == null || panel.data != null || object == null || object.status == null || object.status.stats == null) return;
        Stat stats = new Stat(object.status.stats);
        panel.source.pet(panel.source.scope(), object.status.objectId, stats, System.currentTimeMillis(), "Unverified preview", Collections.emptyMap());
    }
    public static void clearPets() {
        CharacterPetsGUI panel = INSTANCE;
        if (panel != null && panel.data == null) panel.source.clearPets();
    }
    public static void updateEquipedPet() {
        CharacterPetsGUI panel = INSTANCE;
        if (panel != null) SwingUtilities.invokeLater(() -> { panel.displayedRevision = -1; panel.refreshPets(); });
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
        SemanticLabel(String text, String role) { super(text); this.role = role; setForeground(ContentStyle.color(role)); }
        @Override public void updateUI() { super.updateUI(); if (role != null) setForeground(ContentStyle.color(role)); }
    }
}
