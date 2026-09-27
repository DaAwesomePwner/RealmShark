package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.home.HomeModelBuilder;
import tomato.gui.kit.*;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.enums.CharacterClass;
import static tomato.gui.glance.character.SheetViews.named;

/**
 * Characters › Exalts (spec §6.2): one account's exalt progress as a grid of painted class tiles, with a class drill-down in place.
 * - Header: the loot boost of the class in game on this account, else of its last played class ("—" with the reason when the
 *   weapon group is not in the selected assets), and how many observed classes are fully exalted.
 * - The account is Home's current account (in game, else last known this run, else the journal's most recent character's); with
 *   more than one account holding saved counts, a selector appears. The choice is not persisted.
 * - Opening a tile (Enter, Space, double-click) swaps the grid for that class's detail: the sheet's Exalts tab, account-scoped,
 *   under the "account" name prefix. "‹ Exalts" or Escape returns to the grid with the same tile selected and focused. No route.
 * - While it shows, once a second, a cheap token (journal and live revisions, the map-change grace, definitions and dungeon mapping
 *   identities, the chosen account) is compared; on a change the "character-exalts" thread reads the journal's deep copies and
 *   builds the model, and the EDT applies only the newest result. A failed build is logged once and retried on the next tick.
 * Until the first build applies it shows neither tiles nor the empty state. EDT only.
 */
public final class ExaltsGrid extends JPanel {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "character-exalts"); thread.setDaemon(true); return thread;
    });
    /** Where a failed build is logged with its stack trace (standard error, as SheetPresenter); tests may replace it. */
    static volatile Consumer<String> errorLog = message -> System.err.println(message);
    private static final String GRID = "grid", CLASS = "class", BACK = "exalts-back";
    private final SheetContext context;
    private final Executor worker;
    private final IntFunction<int[]> weaponClasses;
    private final IntFunction<String> className;
    private final Supplier<PlanningMetadata> planning;
    private final CardLayout cards = new CardLayout();
    private final StatTile boost = new StatTile("Loot boost"), fullyExalted = new StatTile("Fully exalted");
    private final JComboBox<AccountExalts.Choice> account = named(new JComboBox<>(), "character-exalts-account");
    private final KitText accountLabel = KitText.caption("Account");
    private final JPanel accountRow = SheetViews.row(accountLabel, account), header;
    private final TileList<AccountExalts.Tile> tiles = new TileList<>("character-exalt-tiles", new ExaltTileRenderer(),
        tile -> Integer.toString(tile.classId()), ExaltTileRenderer::accessibleName);
    private final EmptyState empty = named(new EmptyState("No exalt progress yet", "Exalt progress arrives when capture reads your character list.", null),
        "character-exalts-grid-empty");
    private final KitButton back = named(KitButton.ghost("‹ Exalts"), "character-exalts-back");
    private final KitText classTitle = named(new KitText("", Type.title(), Tokens.Role.TEXT), "character-exalts-class");
    private final ExaltsTab detail = new ExaltsTab("account");
    private final Timer timer = new Timer(1000, e -> tick());
    private Token token;
    private long generation;
    private Built shown;
    /** The user's selector choice this run (null: Home's current account). */
    private String chosen;
    /** The class whose detail shows and its account (the account stays pinned while the detail shows); null on the grid. */
    private Integer detailClass;
    private String detailAccount;
    private boolean applying;
    /** The failure last logged, so one that repeats on every retry is logged once until a build applies again. */
    private String logged;

    private record Token(long journal, long live, boolean graceOver, RosterDefinitions definitions, PlanningMetadata planning, String preferred) {}
    /** One build: the grid's model and, for the shown account, every class's detail section. */
    private record Built(AccountExalts model, Map<Integer, SheetModel.Exalts> details) {}

    public ExaltsGrid(SheetContext context) {
        this(context, WORKER, CharacterClass::weaponClasses, id -> CharacterCardModel.className(id, null), PlanningMetadata::current);
    }

    /**
     * Tests: {@code worker} runs builds one at a time (the shared "character-exalts" thread in production), {@code weaponClasses}
     * and {@code className} stand in for the game assets' class data, {@code planning} for the dungeon mapping.
     */
    ExaltsGrid(SheetContext context, Executor worker, IntFunction<int[]> weaponClasses, IntFunction<String> className, Supplier<PlanningMetadata> planning) {
        this.context = Objects.requireNonNull(context, "context");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.weaponClasses = Objects.requireNonNull(weaponClasses, "weaponClasses");
        this.className = Objects.requireNonNull(className, "className");
        this.planning = Objects.requireNonNull(planning, "planning");
        setLayout(cards);
        setName("character-exalts-grid");
        setOpaque(false);
        account.getAccessibleContext().setAccessibleName("Exalts account");
        account.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof AccountExalts.Choice ? ((AccountExalts.Choice) value).label() : value,
                    index, selected, focus);
            }
        });
        account.addActionListener(e -> choose());
        tiles.getAccessibleContext().setAccessibleName("Exalt progress by class");
        tiles.onOpen(this::open);
        boost.setToolTipText("The exalt loot drop boost of the class in game, else of the class last played");
        header = KitLayouts.stack(Tokens.S, accountRow, SheetViews.row(boost, fullyExalted));
        back.setToolTipText("Back to every class");
        back.getAccessibleContext().setAccessibleName("Back to Exalts");
        back.addActionListener(e -> back());
        JPanel backRow = SheetViews.clear(new FlowLayout(FlowLayout.LEADING, 0, 0), back);
        JPanel classPage = KitLayouts.stack(Tokens.M, backRow, classTitle, detail);
        classPage.setBorder(BorderFactory.createEmptyBorder(0, Tokens.S, 0, Tokens.S));
        // Escape anywhere inside the class detail goes back, as the link does.
        classPage.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), BACK);
        classPage.getActionMap().put(BACK, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { back(); } });
        JPanel gridPage = KitLayouts.stack(Tokens.L, header, tiles, empty);
        gridPage.setBorder(BorderFactory.createEmptyBorder(0, Tokens.S, 0, Tokens.S));
        add(SheetViews.scroll(gridPage), GRID);
        add(SheetViews.scroll(classPage), CLASS);
        // Pending: nothing yet, never "no progress".
        header.setVisible(false);
        tiles.setVisible(false);
        empty.setVisible(false);
        // Poll only while shown: showing starts the timer and checks at once; hiding (another tab, closing) stops it.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) { timer.start(); refresh(); } else timer.stop();
        });
    }

    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

    /** EDT: rebuild when the token moved; otherwise the class detail re-reads only its relative "Changed" and "observed" texts. */
    void refresh() {
        if (!token().equals(token)) request();
        else if (detailClass != null) showDetail();
    }

    /** EDT: the model last applied, or null before the first build. */
    AccountExalts model() { return shown == null ? null : shown.model(); }

    /** EDT: whether the once-a-second check runs (only while the grid shows). */
    boolean polling() { return timer.isRunning(); }

    private void tick() {
        if (isShowing()) refresh(); else timer.stop();
    }

    /** The account a build asks for: the one whose class detail shows, else the user's choice, else Home's current account (null). */
    private String preferred() { return detailClass != null ? detailAccount : chosen; }

    /** Mirrors SheetPresenter's token, graceOver included: "in game" must end when the map-change grace does. */
    private Token token() {
        LiveCharacter live = context.data().liveCharacter;
        long now = context.clock().getAsLong();
        boolean graceOver = live.current() == null && live.lastKnown() != null
            && !HomeModelBuilder.stillCurrent(live.lastSeenAt(), live.lastBoundary(), now);
        return new Token(context.journal().revision(), live.revision(), graceOver, context.definitions().get(), planning.get(), preferred());
    }

    private void request() {
        token = token();
        long requested = ++generation;
        String preferred = token.preferred();
        PlanningMetadata mapping = token.planning();
        CharacterJournal journal = context.journal();
        LiveCharacter live = context.data().liveCharacter;
        long now = context.clock().getAsLong();
        worker.execute(() -> {
            Built built = null;
            Throwable failure = null;
            // Any Throwable: an Error escaping here would kill the thread silently and, since the token already moved, never retry.
            try { built = build(journal, live, preferred, mapping, now); }
            catch (Throwable e) {
                failure = e;
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            Built result = built;
            Throwable failed = failure;
            SwingUtilities.invokeLater(() -> deliver(requested, result, failed));
        });
    }

    /** The build thread: one consistent journal read (deep copies) and the pure model. */
    private Built build(CharacterJournal journal, LiveCharacter live, String preferred, PlanningMetadata mapping, long now) {
        List<AccountRecord> accounts;
        List<CharacterRecord> records;
        CharacterRecord recent;
        synchronized (journal) { accounts = journal.accounts(); records = journal.characters(); recent = journal.mostRecentCharacter(); }
        String wanted = preferred != null ? preferred : AccountExaltsBuilder.currentAccount(live.current(), live.lastKnown(), recent);
        AccountExalts model = AccountExaltsBuilder.build(accounts, records, wanted, SheetModelBuilder.inGame(live, now), weaponClasses, className);
        return new Built(model, AccountExaltsBuilder.details(accounts, model.account(), mapping.available ? mapping::dungeons : null));
    }

    /** EDT: only the newest request applies; a failure (building or applying) is logged and retried on the next tick. */
    private void deliver(long requested, Built result, Throwable failure) {
        if (failure == null && requested == generation) {
            try { apply(result); logged = null; return; }
            catch (RuntimeException | Error e) { failure = e; }
        }
        if (failure == null) return;
        log(failure);
        if (requested == generation) token = null; // the next tick tries again
    }

    private void log(Throwable failure) {
        StringWriter trace = new StringWriter();
        try (PrintWriter out = new PrintWriter(trace)) { failure.printStackTrace(out); }
        String text = trace.toString();
        if (text.equals(logged)) return;
        logged = text;
        errorLog.accept("[Exalts] The Exalts grid could not be built; it is retried on the next refresh: " + text);
    }

    /** EDT: shows one build. */
    private void apply(Built built) {
        shown = built;
        AccountExalts model = built.model();
        applying = true;
        try { accounts(model); } finally { applying = false; }
        boolean any = !model.tiles().isEmpty();
        header.setVisible(any);
        tiles.setItems(model.tiles());
        tiles.setVisible(any);
        empty.setVisible(!any);
        boost.setValue(boostValue(model), model.headerClass() == null ? null : model.headerClass() + " · " + model.headerBasis());
        fullyExalted.setValue(DisplayValue.count((long) model.fullyExalted(), "Observed classes whose eight saved counts are all 75 or more", null),
            "of " + model.observed() + " observed classes");
        if (detailClass != null) showDetail();
        revalidate();
        repaint();
    }

    private static DisplayValue boostValue(AccountExalts model) {
        if (model.headerBoost() != null)
            return DisplayValue.known("+" + model.headerBoost() + "%", "Exalt loot drop boost of the " + model.headerClass() + ", from this account's saved exalt counts");
        return DisplayValue.unknown(model.headerClass() != null ? ExaltTileRenderer.UNKNOWN_BOOST
            : "Needs a character of this account in game, or played with capture on");
    }

    /** The selector offers the model's accounts and selects the shown one; it shows only with a real choice. */
    private void accounts(AccountExalts model) {
        List<AccountExalts.Choice> choices = model.accounts();
        boolean same = choices.size() == account.getItemCount();
        for (int i = 0; same && i < choices.size(); i++) same = choices.get(i).equals(account.getItemAt(i));
        if (!same) { account.removeAllItems(); for (AccountExalts.Choice choice : choices) account.addItem(choice); }
        for (AccountExalts.Choice choice : choices) if (choice.key().equals(model.account()) && account.getSelectedItem() != choice) account.setSelectedItem(choice);
        boolean several = choices.size() > 1;
        account.setVisible(several);
        accountRow.setVisible(several);
    }

    /** The user picked an account: rebuild for it now (only a user's change; applying a model never counts). */
    private void choose() {
        if (applying || !(account.getSelectedItem() instanceof AccountExalts.Choice)) return;
        String key = ((AccountExalts.Choice) account.getSelectedItem()).key();
        if (key.equals(chosen)) return;
        if (chosen == null && shown != null && key.equals(shown.model().account())) return; // already the account shown
        chosen = key;
        request();
    }

    /** Drill-down: the class's detail replaces the grid in place; its account stays pinned until Back. */
    private void open(AccountExalts.Tile tile) {
        if (shown == null) return;
        detailClass = tile.classId();
        detailAccount = shown.model().account();
        classTitle.setText(tile.className());
        classTitle.setIcon(Sprites.sprite(tile.classId(), ExaltTileRenderer.SPRITE));
        showDetail();
        cards.show(this, CLASS);
        back.requestFocusInWindow();
    }

    /** The detail of the class that shows, from the newest build for its account (a build for another account never replaces it). */
    private void showDetail() {
        if (shown == null || !Objects.equals(shown.model().account(), detailAccount)) return;
        detail.apply(shown.details().get(detailClass));
    }

    /** Back to the grid with the class that was open selected and focused. */
    private void back() {
        if (detailClass == null) return;
        String key = Integer.toString(detailClass);
        detailClass = null;
        detailAccount = null;
        cards.show(this, GRID);
        tiles.selectKey(key, true);
        tiles.requestFocusInWindow();
    }
}
