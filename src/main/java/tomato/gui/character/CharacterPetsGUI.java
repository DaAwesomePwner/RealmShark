package tomato.gui.character;

import packets.data.ObjectData;
import tomato.backend.data.*;
import tomato.gui.glance.character.PetSummary;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.TileList;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.Formatters;
import tomato.realmshark.ParseEquipment;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Characters › Pets (spec §6.2): a gallery of the current account's pets as painted cards ({@link PetGalleryModel}), with the
 * local feeding calculator in a collapsed drawer that computes for the selected card's pet (the first card when none is selected).
 * - Pets: the account's equipped pets from the character journal, plus every Pet Yard pet ProgressionData holds while the player is
 *   in the yard this connection. The account is Home's current account (in game, else last seen this run, else the journal's most
 *   recently played character).
 * - Threading: the journal is read only on the "character-pets" thread, when a cheap token moved (checked once at construction,
 *   when the tab shows and every 500 ms while it shows: the ProgressionData, journal and live-character revisions and the loaded
 *   pet names). The EDT applies only the newest result. Without TomatoData (tests, the legacy preview) the panel owns a detached
 *   ProgressionData fed by {@link #addPet} and, having no journal to read, builds on the EDT.
 * - Estimates are local formulas ({@link PetFeeding}); they change nothing in game.
 */
public class CharacterPetsGUI extends JPanel {
    private static volatile CharacterPetsGUI INSTANCE;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "character-pets"); thread.setDaemon(true); return thread;
    });
    private final TomatoData data;
    private final ProgressionData source;
    /** Runs gallery builds one at a time: the shared "character-pets" thread, or (no journal to read) the calling EDT. */
    private final Executor worker;
    private final PetCardRenderer renderer = new PetCardRenderer();
    private final TileList<PetGalleryModel.PetCard> cards =
        new TileList<>("pet-cards", renderer, PetGalleryModel.PetCard::key, PetCardRenderer::accessibleName);
    private final EmptyState empty = new EmptyState("No pets yet", "Enter the Pet Yard to see your pets.", null);
    private final Body body = new Body(cards, empty);
    private final JPanel estimates = new JPanel(new BorderLayout(0, 8));
    private final JTextArea subject = ContentStyle.wrappingText("");
    private final Collapsible feeding;
    private final JTextField feed = new JTextField("500", 8), itemSearch = new JTextField(16);
    private final JComboBox<String> items = new JComboBox<>();
    private final JLabel validation = new SemanticLabel(" ", "rose");
    private final JTextArea context = ContentStyle.wrappingText(""), catalogStatus = ContentStyle.wrappingText("Loading local feed items…");
    private final JButton calculate = new JButton("Recalculate feeding costs");
    private final javax.swing.Timer refreshTimer;
    private final Map<String, Integer> catalog = new LinkedHashMap<>();
    private int feedPower = 500;
    private boolean choosing, applying;
    private long itemRequest;
    private SwingWorker<ParseEquipment.Equipment, Void> itemWorker;
    private String scenario = "Manual override";
    /** Resolved on the worker (TomatoData creates and loads the journal on first use); the EDT only reads its revision. */
    private volatile CharacterJournal journal;
    /** The worker's last journal read, reused while the revision is unchanged (worker only). */
    private Read lastRead;
    private Token token;
    private long generation;
    /** EDT: the gallery last applied (null until the first build), what the calculator shows, the failure last logged and shown. */
    private Built shown;
    private Calculation calculated;
    private String logged, failed;

    private record Token(long yard, long journal, long live, PetDefinitions definitions) {}
    private record Read(long revision, List<CharacterJournal.CharacterRecord> records, List<CharacterJournal.AccountRecord> accounts,
                        CharacterJournal.CharacterRecord mostRecent) {}
    /** One build: the account shown (null none) and its label, the cards, and the pet names they were built with. */
    private record Built(String account, String accountLabel, List<PetGalleryModel.PetCard> cards, PetDefinitions definitions) {}
    /** What the calculator renders: the pet's card, whether the user chose it, and the scenario. */
    private record Calculation(PetGalleryModel.PetCard card, boolean chosen, int feedPower, String scenario) {}

    public CharacterPetsGUI(TomatoData data) {
        this.data = data;
        feed.setName("pet-feed-power");
        source = data == null ? new ProgressionData() : data.progression();
        worker = data == null ? Runnable::run : WORKER;
        setLayout(new BorderLayout(8, 8)); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        cards.getAccessibleContext().setAccessibleName("Pets");
        cards.getAccessibleContext().setAccessibleDescription("Select a pet for the feeding calculator; Enter opens it");
        empty.setName("pet-empty");
        // The page scrolls the cards (spec §9): this pane keeps its name but never scrolls or squeezes them itself.
        JScrollPane scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, body.getPreferredSize().height + insets.top + insets.bottom);
            }
            @Override public Dimension getPreferredSize() { return getMinimumSize(); }
        };
        scroll.setName("pet-list-scroll"); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setWheelScrollingEnabled(false); // the wheel scrolls the page
        context.setName("pet-capture-context"); context.getAccessibleContext().setAccessibleName("Pet account and capture context");
        ContentStyle.font(subject, ContentStyle.emphasis(ContentStyle.body())); // a role refreshFonts keeps

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
        JPanel calculator = new JPanel(new BorderLayout(0, 8));
        calculator.add(estimates, BorderLayout.NORTH); calculator.add(form, BorderLayout.CENTER);
        feeding = new Collapsible("pets-feeding", "Feeding calculator", calculator, false);
        feeding.setName("pets-feeding");
        JScrollPane page = ContentStyle.page(context, scroll, feeding);
        page.setName("pet-page-scroll");
        page.getAccessibleContext().setAccessibleName("Pets; scroll for captured pets, feeding estimates and scenario controls");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{context, catalogStatus, itemSearch, items, useId, feed, calculate, feeding.toggle()}) revealOnFocus(control);
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
        // The calculator follows the selected card; a selection the gallery restores while it applies a model is rendered once after.
        cards.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || applying) return;
            showCalculator();
            if (cards.isFocusOwner()) revealSelected();
        });
        cards.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { revealSelected(); }
        });
        cards.onOpen(card -> { feeding.setExpanded(true); ContentStyle.reveal(feeding.toggle(), new Rectangle(0, 0, feeding.toggle().getWidth(), feeding.toggle().getHeight())); });
        refreshTimer = new javax.swing.Timer(500, e -> refresh()); refreshTimer.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refresh(); refreshTimer.start(); } else refreshTimer.stop();
            }
        });
        showContext();
        showCalculator();
        ContentStyle.refreshFonts(this); INSTANCE = this;
        loadCatalog(); refresh();
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

    /** The focused card, through every enclosing viewport (the cards' own pane never scrolls, so JList's reveal stops short). */
    private void revealSelected() {
        int index = Math.max(0, cards.getSelectedIndex());
        Rectangle cell = index < cards.getModel().getSize() ? cards.getCellBounds(index, index) : null;
        if (cell != null) ContentStyle.reveal(cards, cell);
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
    /** Applies the feed power at once to the pet shown, and rebuilds the gallery from fresh observations. */
    private void recalculate() {
        if (!validateFeed()) return;
        feedPower = Integer.parseInt(feed.getText().trim());
        request();
        showCalculator();
    }

    /** EDT: rebuild when a source moved; the capture line follows capture starting or stopping on every check. */
    private void refresh() {
        if (!token().equals(token)) request();
        showContext();
    }

    private Token token() {
        CharacterJournal known = journal;
        return new Token(source.snapshot().revision, known == null ? -1 : known.revision(),
            data == null ? -1 : data.liveCharacter.revision(), PetDefinitions.current());
    }

    private void request() {
        token = token();
        long requested = ++generation;
        PetDefinitions definitions = token.definitions();
        worker.execute(() -> {
            Built built = null;
            Throwable failure = null;
            // Any Throwable: an Error escaping here would kill the worker silently and, the token having moved, never retry.
            try { built = build(definitions); }
            catch (Throwable e) {
                failure = e;
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            Built result = built;
            Throwable failed = failure;
            if (SwingUtilities.isEventDispatchThread()) deliver(requested, result, failed);
            else SwingUtilities.invokeLater(() -> deliver(requested, result, failed));
        });
    }

    /** The worker: one journal read (reused while its revision is unchanged) and the gallery model, over deep copies. */
    private Built build(PetDefinitions definitions) {
        ProgressionData.Snapshot yard = source.snapshot();
        if (data == null) // The detached preview: no journal and no verified account; its own pets are the yard.
            return new Built(null, null, PetGalleryModel.build(List.of(), null, PetGalleryModel.yard(yard, null), definitions), definitions);
        CharacterJournal journal = this.journal;
        if (journal == null) this.journal = journal = data.characterJournal();
        Read read = lastRead;
        synchronized (journal) {
            long revision = journal.revision();
            if (read == null || read.revision() != revision)
                read = new Read(revision, journal.characters(), journal.accounts(), journal.mostRecentCharacter());
        }
        lastRead = read;
        LiveCharacter live = data.liveCharacter;
        String account = PetGalleryModel.account(live.current(), live.lastKnown(), read.mostRecent());
        List<PetGalleryModel.YardPet> pets = account == null ? List.of() : PetGalleryModel.yard(yard, account);
        return new Built(account, account == null ? null : accountLabel(read.accounts(), account),
            PetGalleryModel.build(read.records(), account, pets, definitions), definitions);
    }

    /** "Account · <saved name>", else the first six characters of the hashed account key (as capture's scope names it). */
    private static String accountLabel(List<CharacterJournal.AccountRecord> accounts, String key) {
        for (CharacterJournal.AccountRecord a : accounts)
            if (key.equals(a.key) && a.name != null && !a.name.isBlank()) return "Account · " + a.name;
        return "Account · " + key.substring(0, Math.min(6, key.length()));
    }

    /** EDT: one build's outcome. Only the newest request applies; a failure is logged (once while it repeats) and retried. */
    private void deliver(long requested, Built built, Throwable failure) {
        if (failure != null) {
            log(failure);
            // The cleared token makes the next refresh try again; meanwhile the context line says so (the cards stay as they were).
            if (requested == generation) { token = null; failed = String.valueOf(failure); showContext(); }
            return;
        }
        if (requested != generation) return;
        logged = null; failed = null;
        shown = built;
        renderer.setDefinitions(built.definitions());
        applying = true;
        try { cards.setItems(built.cards()); } finally { applying = false; }
        body.display(built.cards().isEmpty() ? empty : cards);
        cards.repaint(); // names that load later change the painted family without changing the cards' keys
        showContext();
        showCalculator();
    }

    private void log(Throwable failure) {
        StringWriter trace = new StringWriter();
        try (PrintWriter out = new PrintWriter(trace)) { failure.printStackTrace(out); }
        String text = trace.toString();
        if (text.equals(logged)) return;
        logged = text;
        System.err.println("[Pets] A pets gallery build failed; it is retried on the next refresh: " + text);
    }

    /** The account shown and whether capture runs, then what the gallery holds. */
    private void showContext() {
        String capture = data == null ? "Unverified preview" : tomato.Tomato.isCaptureRunning() ? "Capture running" : "Capture stopped";
        String text;
        if (shown == null) text = (data == null ? "Account not verified" : "Reading pets…") + " · " + capture;
        else {
            String account = shown.account() != null ? shown.accountLabel() : data == null ? "Account not verified" : "No account yet";
            int yard = 0, equipped = 0, total = shown.cards().size();
            for (PetGalleryModel.PetCard card : shown.cards()) { if (card.inYard()) yard++; if (!card.equippedBy().isEmpty()) equipped++; }
            String pets = total == 0 ? (data != null && shown.account() == null ? "Pets appear once capture has seen your account."
                    : "No pets captured for this account yet.")
                : total + (total == 1 ? " pet" : " pets") + " · " + (yard > 0 ? yard + " in the Pet Yard now" : "Pet Yard pets show while you are in the Pet Yard")
                    + " · " + equipped + " equipped by your characters";
            text = account + " · " + capture + "\n" + pets;
        }
        if (failed != null) text += "\nPets could not be read; retrying: " + failed;
        if (!text.equals(context.getText())) context.setText(text);
    }

    /** EDT: the feeding calculator for the selected card's pet, else the first card's; rebuilt only when what it shows changed. */
    private void showCalculator() {
        PetGalleryModel.PetCard card = cards.getSelectedValue();
        boolean chosen = card != null;
        if (card == null && !cards.items().isEmpty()) card = cards.items().get(0);
        Calculation next = new Calculation(card, chosen, feedPower, scenario);
        if (next.equals(calculated)) return;
        calculated = next;
        estimates.removeAll();
        if (card == null) estimates.add(detail("Enter the Pet Yard to see your pets."), BorderLayout.NORTH);
        else {
            PetSummary pet = card.pet();
            subject.setText("Estimates for " + pet.title() + (pet.instanceId() == null ? "" : " (instance " + pet.instanceId() + ")")
                + (chosen ? "" : ". Select a pet card to estimate another pet."));
            estimates.add(subject, BorderLayout.NORTH);
            JPanel abilities = ContentStyle.responsiveGrid(3, 230, 8);
            for (int i = 0; i < 3; i++) abilities.add(abilityDetails(pet, i));
            estimates.add(abilities, BorderLayout.CENTER);
        }
        estimates.revalidate(); estimates.repaint();
    }

    /**
     * One ability's inputs and estimate, in the wording the calculator has always used, with the estimated counts marked "≈"
     * (captured inputs and unknowns never are). The gallery's pet is one merged observation
     * (PetGalleryModel), so each value's evidence is that observation's source and time.
     */
    private JPanel abilityDetails(PetSummary pet, int index) {
        JPanel details = new JPanel(new BorderLayout(0, 4));
        PetSummary.Ability ability = pet.abilities().get(index);
        Integer level = ability.level(), points = ability.points(), cap = pet.maxLevel();
        PetFeeding.Estimate estimate = PetFeeding.estimate(index, level, points, cap, feedPower);
        details.add(detail(abilityName(ability.type()) + " · Level " + known(level)), BorderLayout.NORTH);
        String text = "Captured points: " + known(points) + " · " + evidence(pet, points)
            + "\nLevel: " + evidence(pet, level) + "\nMax level: " + evidence(pet, cap)
            + "\n" + scenario + " · " + feedPower + " FP/item · Ability multiplier " + PetFeeding.multiplier(index)
            + "\nNext level items: " + estimated(estimate, estimate.nextItems) + " · Fame: " + estimated(estimate, estimate.nextFame)
            + "\nItems to max: " + estimated(estimate, estimate.maxItems) + " · Fame to max: " + estimated(estimate, estimate.maxFame)
            + "\n" + (estimate.fullyFed ? "Fully fed at captured maximum level. " : "") + estimate.reason;
        JTextArea explanation = ContentStyle.wrappingText(text); explanation.setFocusable(true);
        revealOnFocus(explanation);
        explanation.getAccessibleContext().setAccessibleName("Ability " + (index + 1) + " feeding inputs and estimate");
        details.add(explanation, BorderLayout.CENTER);
        if (estimate.locked) details.add(new SemanticLabel("Locked ability", "muted"), BorderLayout.SOUTH);
        return details;
    }
    private static String evidence(PetSummary pet, Integer value) {
        return value == null ? "Not captured" : (pet.source() == null ? "Source unknown" : pet.source()) + " · " + timestamp(pet.observedAt());
    }
    private static String timestamp(long at) { return at <= 0 ? "Time unknown" : Formatters.formatTimestamp(at); }
    private static String known(Object value) { return value == null ? "Not captured / unavailable" : value.toString(); }
    /**
     * An item or fame count from PetFeeding's local formula: "≈ 12" (spec §1: estimates show ≈, as the sheet's Pet tab does).
     * Unknown stays "Not captured / unavailable"; a fully fed ability's zeros are exact, not estimates.
     */
    private static String estimated(PetFeeding.Estimate estimate, Long value) {
        return value == null || estimate.fullyFed ? known(value) : "≈ " + value;
    }
    private static JLabel detail(String text) { JLabel label = new JLabel(text); label.setFont(ContentStyle.body()); return label; }
    /** PetDefinitions' ability name; the panel's own wording for an uncaptured or unnamed ability id. */
    static String abilityName(Integer id) {
        if (id == null) return "Ability not captured";
        String name = PetDefinitions.ability(id);
        return name == null ? "Unknown ability #" + id : name;
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
        if (panel != null) SwingUtilities.invokeLater(panel::request);
    }

    /** The gallery's body: the cards, the empty state, or (until the first build applies) neither, at the shown one's size. */
    private static final class Body extends JPanel implements Scrollable {
        Body(JComponent... children) {
            super(null);
            setOpaque(false);
            for (JComponent child : children) { child.setVisible(false); add(child); }
        }
        void display(JComponent child) {
            boolean changed = false;
            for (Component c : getComponents()) if (c.isVisible() != (c == child)) { c.setVisible(c == child); changed = true; }
            if (changed) { revalidate(); repaint(); }
        }
        private Component shown() { for (Component c : getComponents()) if (c.isVisible()) return c; return null; }
        @Override public void doLayout() { Component c = shown(); if (c != null) c.setBounds(0, 0, getWidth(), getHeight()); }
        @Override public Dimension getPreferredSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 40; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(40, visible.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }
    private static class SemanticLabel extends JLabel {
        private final String role;
        SemanticLabel(String text, String role) { super(text); this.role = role; setForeground(ContentStyle.color(role)); }
        @Override public void updateUI() { super.updateUI(); if (role != null) setForeground(ContentStyle.color(role)); }
    }
}
