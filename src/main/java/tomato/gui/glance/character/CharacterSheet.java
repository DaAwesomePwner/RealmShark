package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterDeathPanel;
import tomato.gui.character.CharacterPlanningPanel;
import tomato.gui.kit.Banner;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.Navigator;
import tomato.gui.stats.Formatters;

/**
 * One character's full page on the Characters Roster tab: a header (back link, identity, Mark dead or Restore alive, status
 * banners and the snapshot evidence) over {@code CustomizableTabs("character")}. Overview, Gear and Exalts are slots whose
 * content SheetPresenter sets ({@link #setTab}); Death annotation shows only while the character is marked dead (spec §6.2).
 * - SheetPresenter reads the journal and builds the model off the EDT; {@link #loaded} shows each read. Until the read of the
 *   opened key arrives the sheet says "Loading…" and nothing acts ({@link #ready}); a failed build shows a warn banner (spec §7).
 * - Snapshot evidence and the tab hint are provenance: Analyst mode only (spec §3.2).
 * - A notes draft is saved when another character opens and whenever the sheet hides (another card, Back, another Characters
 *   tab, closing the workspace); refreshes never replace it.
 * - An unreadable journal or a failed save shows a warn banner.
 * The refresh timer runs only while the sheet shows. EDT only.
 */
public final class CharacterSheet extends JPanel {
    /** Title of the unavailable state, for a key the journal does not hold. */
    public static final String UNAVAILABLE = "This character is not in the journal";
    /** The static loading line (spec §5.8: no animated loaders). */
    static final String LOADING = "Loading…";
    private static final String TABS_CARD = "tabs", UNAVAILABLE_CARD = "unavailable";

    private final SheetContext context;
    private final CustomizableTabs tabs = new CustomizableTabs("character");
    private final Map<String, JPanel> slots = new HashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final KitButton back = KitButton.ghost("‹ Characters");
    private final JTextArea seen = ContentStyle.wrappingText(" ");
    private final JTextArea hint = ContentStyle.wrappingText("Base stats exclude captured boosts. Caps use local game assets; missing values stay unknown.");
    /** The header row: SheetHeader's identity block (setIdentity) and Mark dead / Restore alive. */
    private final JPanel identity = new JPanel(new BorderLayout(8, 0));
    /** Marking a character dead is destructive; Restore alive takes its place while the character is marked dead. */
    private final KitButton death = KitButton.danger("Mark dead"), restore = KitButton.secondary("Restore alive");
    private final KitButton saveNotes = KitButton.secondary("Save notes");
    /** "Loading…" until the opened character's read arrives; a failed build in the warn tone. */
    private final Banner status = new Banner("character-sheet-status");
    /** The journal cannot be read, or its last save failed. */
    private final Banner storage = new Banner("character-sheet-storage");
    private final JTextArea notes = new JTextArea(3, 30);
    private final DefaultTableModel metadataModel = model("Field", "Value", "Field evidence");
    private final CharacterPlanningPanel planning;
    private final CharacterDeathPanel deathPanel;
    private final javax.swing.Timer timer;
    /** Header identity and the Overview, Gear, Exalts and Build tabs (Tasks 5–8), built off the EDT. */
    private final SheetPresenter presenter;
    private Runnable backAction = () -> { };
    /** {@code loadedKey}: the key whose journal read this sheet shows; the actions wait until it equals {@code key}. */
    private String key, filledKey, loadedKey;
    /**
     * The tab {@link #open} (explicit navigation) or {@link #selectTab} (restoring a saved tab) last asked for, until it is
     * reached or abandoned; {@code pendingTabShows} is true only for the former, which may show a hidden tab, never for the
     * latter (spec: restore only selects). A conditional tab (Death) may not be offered yet while the record loads, so one
     * more attempt happens in {@link #loaded}; whatever the outcome, the request does not survive past that single retry, and
     * any other settled selection (the user's own choice, or an unrelated fallback) abandons it immediately (see the
     * {@code tabs.onSelect} listener in the constructor) — so a later, unrelated rebuild never pulls the user off a tab they
     * since chose, and never re-shows a tab they since re-hid.
     */
    private String pendingTab;
    private boolean pendingTabShows;
    /** True only during {@link #open}'s own reset of the previous character's tabs; see {@link #resettingTabs()}. */
    private boolean resettingTabs;
    private CharacterRecord record;
    private List<CharacterRecord> records = Collections.emptyList();
    private List<AccountRecord> accounts = Collections.emptyList();
    private RosterDefinitions definitions = RosterDefinitions.empty();
    private long revision = -1;

    public CharacterSheet(SheetContext context) {
        super(new BorderLayout());
        this.context = Objects.requireNonNull(context, "context");
        setName("character-sheet");
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        planning = new CharacterPlanningPanel(context.plans());
        deathPanel = new CharacterDeathPanel(context.journal());
        back.setName("character-sheet-back"); back.setToolTipText("Back to the character list");
        back.getAccessibleContext().setAccessibleName("Back to Characters");
        back.addActionListener(e -> backAction.run());
        death.setName("character-sheet-death"); restore.setName("character-sheet-restore"); saveNotes.setName("character-notes-save");
        restore.setVisible(false);
        seen.setName("character-snapshot-evidence"); hint.setName("character-sheet-hint");
        status.setVisible(false);
        storage.setTone(Tokens.Tone.WARN); storage.setVisible(false);
        JPanel backRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); backRow.add(back);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, Tokens.XS, 0)); actions.add(death); actions.add(restore);
        identity.add(actions, BorderLayout.EAST); // SheetHeader takes the center (setIdentity)
        JPanel header = new JPanel(new BorderLayout(0, 4)); header.setName("character-sheet-header");
        header.add(backRow, BorderLayout.NORTH); header.add(identity, BorderLayout.CENTER);
        header.add(KitLayouts.stack(Tokens.XS, status, storage, seen), BorderLayout.SOUTH);

        JPanel notePanel = new JPanel(new BorderLayout(8, 8)); notes.setLineWrap(true); notes.setWrapStyleWord(true);
        notes.setName("character-notes"); notes.setFont(ContentStyle.body()); notes.getAccessibleContext().setAccessibleName("Character notes");
        JScrollPane noteScroll = new JScrollPane(notes) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, notes.getFontMetrics(notes.getFont()).getHeight() * 3 + insets.top + insets.bottom);
            }
        };
        notePanel.add(noteScroll, BorderLayout.CENTER); notePanel.add(saveNotes, BorderLayout.SOUTH);
        tabs.add("overview", "Overview", slot("overview", new JPanel())) // SheetPresenter sets the Overview tab
            .add("gear", "Gear", slot("gear", new JPanel())) // SheetPresenter sets the Gear tab
            .add("exalts", "Exalts", slot("exalts", new JPanel())) // SheetPresenter sets the Exalts tab
            .add("goals", "Goals", planning)
            .add("notes", "Notes", notePanel)
            // Raw field provenance is diagnostic: Analyst mode only (spec §3.2); the saved order still includes it.
            .addAnalyst("evidence", "Snapshot evidence", ContentStyle.tableScroll(table(metadataModel), 3))
            // Only while the character is marked dead (spec §6.2); skipping it never rewrites the saved order (spec §4.4).
            .addWhen("death", "Death annotation", deathPanel, () -> record != null && record.dead);
        // A still-unreached pending tab is abandoned the moment anything else settles as selected: the user's own choice, or
        // an unrelated fallback. Only the request that is still current when a selection lands is ever honored.
        tabs.onSelect(id -> { if (!Objects.equals(id, pendingTab)) pendingTab = null; });
        JTabbedPane strip = tabs.component(); strip.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        hint.setToolTipText("Potion estimates use +5 Life/Mana and +1 other stats. Exalts are account/class progress shared across characters.");
        JPanel content = new JPanel(new BorderLayout(0, 8)) {
            // Tab chrome and usable rows must fit after font and width changes; the page scrolls instead of squeezing them.
            @Override public Dimension getMinimumSize() { return new Dimension(0, strip.getMinimumSize().height + (hint.isVisible() ? hint.getPreferredSize().height + 8 : 0)); }
        };
        content.add(strip, BorderLayout.CENTER); content.add(hint, BorderLayout.SOUTH);
        KitButton showAll = KitButton.secondary("Show all characters"); showAll.setName("character-sheet-unavailable-back");
        showAll.addActionListener(e -> backAction.run());
        EmptyState missing = new EmptyState(UNAVAILABLE, "The journal has no saved character with this reference. Choose one from the character list.", showAll);
        missing.setName("character-sheet-unavailable");
        body.add(content, TABS_CARD); body.add(missing, UNAVAILABLE_CARD);
        JScrollPane page = ContentStyle.page(header, body, null); page.setName("character-sheet-scroll");
        page.getAccessibleContext().setAccessibleName("Character sheet; scroll for tabs and actions at large text sizes");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{back, death, restore, saveNotes}) control.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); }
        });
        death.addActionListener(e -> mark(true));
        restore.addActionListener(e -> mark(false));
        saveNotes.addActionListener(e -> {
            if (!ready() || record == null) return;
            context.journal().notes(record.key, notes.getText()); record.notes = notes.getText(); refresh();
        });
        // Provenance is diagnostic (spec §3.2): Simple mode shows neither the snapshot evidence nor the tab hint.
        context.mode().bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            seen.setVisible(analyst); hint.setVisible(analyst); revalidate(); repaint();
        });
        timer = new javax.swing.Timer(1000, e -> refresh());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            // The timer runs only while the sheet shows. Hiding it (another card, Back, another Characters tab, closing) keeps the draft.
            if (isShowing()) { timer.start(); refresh(); } else { timer.stop(); saveDraft(); }
        });
        fill();
        presenter = new SheetPresenter(this, context); // after every tab exists: it sets the identity and the tabs it owns
    }

    @Override public void removeNotify() { saveDraft(); timer.stop(); super.removeNotify(); }

    /**
     * Shows one character. A different key first saves the previous character's changed notes and clears everything shown, so
     * nothing of that character stays on screen or acts while this one loads. A non-null tab is explicit navigation: it is shown
     * if hidden, then selected; a conditional tab (Death) may not exist yet while the record loads, so the request is retried
     * once {@link #loaded} applies it. A key the journal does not hold shows the unavailable state once its read arrives.
     */
    public void open(String key, String tab) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Open the character sheet on the EDT");
        if (!Objects.equals(this.key, key)) {
            saveDraft();
            this.key = key;
            record = null; loadedKey = null;
            fill();
            // Clearing the previous character can drop a conditional tab (Death) it had selected, forcing a fallback
            // selection that belongs to no character; resettingTabs marks that single call so it is never reported as
            // this character's tab (see resettingTabs()).
            resettingTabs = true;
            try { tabs.refreshConditions(); } finally { resettingTabs = false; }
            shown();
            status.setTone(Tokens.Tone.NEUTRAL); status.setText(LOADING); status.setVisible(true);
        }
        presenter.open(key);
        requestTab(tab, true); // explicit navigation may show a hidden tab
    }
    public String key() { return key; }
    /** True once the sheet shows the journal's read of its current key: that character, or its unavailable state. */
    public boolean ready() { return key != null && key.equals(loadedKey); }
    /**
     * True only while {@link #open} is clearing the previous character's tabs for a new key. A tab-selection listener (the
     * Roster tab saves the sheet's settled tab) should ignore a selection reported during this single call: it is a fallback
     * forced by a conditional tab (Death) disappearing, not the tab of the character now opening.
     */
    public boolean resettingTabs() { return resettingTabs; }
    public String selectedTab() { return tabs.selectedId(); }
    public CustomizableTabs tabs() { return tabs; }
    /**
     * Selects a tab without showing it: startup and saved-state restore keep a hidden tab hidden. A conditional tab (Death)
     * may not be offered yet while the record loads, so this is retried once, select-only, from {@link #loaded}.
     */
    public void selectTab(String id) { requestTab(id, false); }
    /** What the "‹ Characters" link and the unavailable state's button do; the Roster tab sets it on every open. */
    public void onBack(Runnable action) { backAction = Objects.requireNonNull(action); }
    public void bindNavigator(Navigator navigator) { deathPanel.bindNavigator(navigator); }
    /** Moves keyboard focus to the back link, the sheet's first control. */
    public void focusBackLink() { back.requestFocusInWindow(); }

    /**
     * Saves the shown character's changed notes to the journal: before another character opens, whenever the sheet hides
     * (another card, Back, the Characters page's other tabs) and when the workspace closes. A draft equal to the saved notes,
     * or one typed for another character, saves nothing. EDT.
     */
    public void saveDraft() {
        if (filledKey == null || record == null || !filledKey.equals(record.key) || Objects.equals(record.notes, notes.getText())) return;
        context.journal().notes(filledKey, notes.getText()); record.notes = notes.getText();
    }

    /** Replaces a slot tab's content (overview, gear, exalts); its id, title, order and hidden state are unchanged. */
    void setTab(String id, JComponent content) {
        JPanel slot = slots.get(id);
        if (slot == null) throw new IllegalArgumentException("Not a replaceable sheet tab: " + id);
        slot.removeAll(); slot.add(content, BorderLayout.CENTER); slot.revalidate(); slot.repaint();
    }
    /** Puts the header's identity block (SheetHeader) beside Mark dead; the back link, banners and snapshot evidence stay. */
    void setIdentity(JComponent value) { identity.add(value, BorderLayout.CENTER); identity.revalidate(); identity.repaint(); }

    /** Registers {@code id} as the tab to reach ({@code shows}: see {@link #pendingTab}) and makes one immediate attempt. */
    private void requestTab(String id, boolean shows) {
        pendingTab = id;
        pendingTabShows = shows;
        applyPendingTab();
    }

    /** One attempt at {@link #pendingTab}; clears it once reached. A select-only request never shows a hidden tab. */
    private void applyPendingTab() {
        if (pendingTab == null) return;
        if (pendingTabShows) tabs.show(pendingTab);
        tabs.select(pendingTab);
        if (pendingTab.equals(tabs.selectedId())) pendingTab = null;
    }
    /** Asks the presenter for a new read when a token moved, then advances the snapshot age. EDT; skipped while hidden. */
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        if (key != null && (isShowing() || !isDisplayable())) { presenter.refresh(); shown(); }
    }

    private JPanel slot(String id, JComponent content) {
        JPanel slot = new JPanel(new BorderLayout()); slot.setName("character-tab-" + id);
        slot.add(content, BorderLayout.CENTER); slots.put(id, slot);
        return slot;
    }

    /**
     * Shows one journal read the presenter made for {@code forKey} off the EDT; a read for any other key is ignored. The tables,
     * notes and actions refill only when the character, the journal revision or the definitions changed. The Death annotation
     * tab follows the dead flag.
     */
    void loaded(String forKey, CharacterRecord read, List<CharacterRecord> all, List<AccountRecord> known, RosterDefinitions defs, long at) {
        if (!Objects.equals(forKey, key)) return;
        boolean changed = !Objects.equals(forKey, loadedKey) || at != revision || defs != definitions;
        record = read; records = all; accounts = known; definitions = defs; revision = at; loadedKey = forKey;
        status.setVisible(false);
        if (changed) fill();
        tabs.refreshConditions();
        applyPendingTab();
        pendingTab = null; // one retry only: a request that is still unreached here is abandoned, not retried on a later rebuild
        shown();
    }

    /** A build failed (spec §7: never silent): a warn banner; nothing acts until a later build shows this character. */
    void failed(RuntimeException failure) {
        String reason = failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
        status.setTone(Tokens.Tone.WARN); status.setText("This character could not be shown: " + reason); status.setVisible(true);
    }

    /** On every refresh: the snapshot age (time advances after capture stops), the storage warning, the death panel and Goals. */
    private void shown() {
        refreshTimeEvidence();
        String problem = context.journal().storageProblem();
        storage.setText(problem == null ? "" : problem);
        storage.setVisible(problem != null);
        deathPanel.showRecord(record);
        planning.refresh(records, accounts, definitions);
    }

    /** Mark dead or Restore alive, then wait for the re-read that shows the new state: a second click never acts on the old one. */
    private void mark(boolean dead) {
        if (!ready() || record == null) return;
        context.journal().markDead(record.key, dead);
        loadedKey = null;
        actions();
        refresh();
    }

    /** Mark dead or Restore alive (whichever applies) and Save notes act only on the character this sheet has loaded. */
    private void actions() {
        boolean dead = record != null && record.dead, acts = ready() && record != null;
        death.setVisible(!dead); restore.setVisible(dead);
        death.setEnabled(acts); restore.setEnabled(acts); saveNotes.setEnabled(acts); notes.setEnabled(acts);
    }

    private void fill() {
        CharacterRecord r = record;
        metadataModel.setRowCount(0);
        actions();
        cards.show(body, ready() && r == null ? UNAVAILABLE_CARD : TABS_CARD);
        // A refresh never replaces an unsaved draft; only a different character does.
        if (r == null) { notes.setText(""); filledKey = null; }
        else if (!r.key.equals(filledKey)) { notes.setText(r.notes); filledKey = r.key; }
        if (r == null) {
            seen.setToolTipText(null);
            return;
        }
        restore.setText(r.observedAgainAt > 0 ? "Observed again—restore?" : "Restore alive");
        seen.setToolTipText(r.source);
        String[] fields = {"class", "level", "skin", "fame", "seasonal", "created"};
        Object[] values = {r.className, r.level, r.skin, r.fame, r.seasonal == null ? null : r.seasonal ? "Seasonal" : "Regular", r.created};
        for (int i = 0; i < fields.length; i++) metadataModel.addRow(new Object[]{fields[i], unknown(values[i]), evidence(r, fields[i], values[i] != null)});
    }

    /** Time advances even after capture stops; refresh just this text, not tables or editable drafts. */
    private void refreshTimeEvidence() {
        CharacterRecord r = record;
        String text = " ";
        if (r != null) {
            long age = r.lastSeen <= 0 ? -1 : Math.max(0, (context.clock().getAsLong() - r.lastSeen) / 1000);
            text = "Last observed alive " + date(r.lastObservedAlive) + "  •  Roster received " + date(r.rosterReceivedAt)
                + "\nSnapshot update age: " + (age < 0 ? "Unknown" : age + "s") + " · "
                + Arrays.stream(r.stats).filter(Objects::nonNull).count() + "/8 known stats · "
                + Arrays.stream(r.equipment).filter(Objects::nonNull).count() + "/28 known slots (may be retained)"
                + (r.dead ? "\nMarked dead manually " + date(r.diedAt) + "; preserved snapshot."
                    + (r.observedAgainAt > 0 ? " Reported again " + date(r.observedAgainAt) + ". Restore explicitly to accept updates." : "") : "");
        }
        if (!seen.getText().equals(text)) seen.setText(text);
    }

    private static String evidence(CharacterRecord r, String key, boolean known) {
        if (!known) return "Not captured";
        FieldCapture field = r.fields.get(key);
        if (field == null) return "Legacy / provenance unknown";
        return SheetViews.fieldEvidence(field.source, field.at, r.lastSeen);
    }
    private static Object unknown(Object value) { return value == null ? "Unknown" : value; }
    private static String date(long time) { return time <= 0 ? "Unknown" : Formatters.formatTimestamp(time); }
    private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int col) { return false; }
        @Override public Class<?> getColumnClass(int col) { for (int i = 0; i < getRowCount(); i++) { Object v = getValueAt(i, col); if (v != null) return v instanceof Number ? v.getClass() : String.class; } return String.class; }
    }; }
    private static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model); ContentStyle.table(t, ContentStyle.Density.DENSE);
        t.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                int row = Math.max(0, t.getSelectedRow()), column = Math.max(0, t.getSelectedColumn());
                ContentStyle.reveal(t, t.getCellRect(row, column, true));
            }
        });
        t.getTableHeader().setReorderingAllowed(false); return t;
    }
}
