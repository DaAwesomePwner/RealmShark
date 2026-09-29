package tomato.gui.loot;

import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootFilters;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * Loot › Highlights (spec §6.4; P6a decisions): what dropped Today or This session at a glance.
 * - Header: the source caption ({@code loot-highlights-source}: "Saved history · Today", or "This app run · not saved" with
 *   "latest 1,000 bags" when the live list is capped), the Today / This session choice ({@code loot-highlights-window}, kept in
 *   {@link #WINDOW_KEY}, Today by default; restoring it only selects) and ⋯ ({@code loot-highlights-more}: Refresh plus the
 *   page's {@link #addOverflowAction} entries).
 * - Four StatTiles: UT drops, ST drops, potions (the sub-line lists stats, "2 Life · 1 Mana · 3 Def") and white bags (the
 *   sub-line says of how many bags, and how many had no saved bag name). Unknown is "—" with its reason, never 0; a partial
 *   read (◐) and a stale one are labeled on the tiles and in a warn line above them. One row whenever each tile can be
 *   {@value #TILE_MIN} px wide (a long sub-line wraps inside its tile, {@link SubLine}); else two by two or one per row.
 * - Notable drops ({@code loot-notable-grid}): a painted TileList ({@link NotableDropRenderer}). Filter Loot ({@link LootFilters})
 *   decides which bag colors are listed, re-filtered on change without a read, and a line says how many drops it hides; the
 *   tiles and the strip still count every observed drop. Enter, Space, a double-click or the context menu's "Open run recap"
 *   opens a drop's exact run ({@link #onOpenRun}); a drop without one says "Not linked to a run" and opens nothing.
 * - By dungeon ({@code loot-dungeon-strip}, {@link DungeonStripRenderer}): Enter, Space or a double-click runs {@link #onDungeon}
 *   with the area (null = Unknown area).
 * - States: loading (before the first result for the chosen window), unavailable (the reason and Try again), empty ("No notable
 *   drops yet" with what to do), stale (the last good read kept after a failed one) and partial (◐ with the count).
 * - Reads (S8): never on the EDT and never on every show. The first show reads; so do a window change, ⋯ Refresh and a nudge:
 *   while showing, every {@value #CHECK_MILLIS} ms the live feed's revision is compared with the last one read, and a change
 *   reads again after {@value #SETTLE_MILLIS} ms (saved history drains every 250 ms, so the new bag is on disk by then); every
 *   {@value #PERIODIC_TICKS} checks without one (30 s) it reads anyway (midnight, other writers). One daemon worker ("RealmShark
 *   loot highlights"); a newer request cancels the older one and only the newest result is applied. {@link #close} stops it.
 * EDT only, except the reads.
 */
public final class LootHighlights extends JPanel implements AutoCloseable {
    public static final String WINDOW_KEY = "ui.loot.highlights";
    /** The nudge check while showing, the settle delay before a nudged read, and the checks between reads without a nudge (30 s). */
    static final int CHECK_MILLIS = 2_000, SETTLE_MILLIS = 750, PERIODIC_TICKS = 15;
    static final String LOADING = "loading", UNAVAILABLE = "unavailable", CONTENT = "content";
    /** A tile's narrowest width in px: four tiles share a row whenever each can have this much (Home's tiles use the same). */
    static final int TILE_MIN = 180;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** How the view reads: a {@link HighlightsSource} in production. {@code read} runs off the EDT. */
    interface Reader {
        HighlightsModel read(HighlightsModel.Window window, Cancellation cancel) throws Exception;
        /** The live feed's revision (any thread; cheap). */
        long revision();
    }

    private final Reader reader;
    private final ZoneId zone;
    private final BiConsumer<String, String> write;
    private final ThreadPoolExecutor worker;
    private final SegmentedControl windowControl = new SegmentedControl("loot-highlights-window", "Today", "This session");
    private final KitText sourceCaption = KitText.caption("");
    private final OverflowMenu more = new OverflowMenu("loot-highlights-more");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JPanel unavailableHolder = new JPanel(new BorderLayout());
    private final Banner stale = new Banner("loot-highlights-stale"), partial = new Banner("loot-highlights-partial");
    private final StatTile ut = tile("UT drops", "loot-tile-ut"), st = tile("ST drops", "loot-tile-st"),
        potions = tile("Potions", "loot-tile-potions"), whites = tile("White bags", "loot-tile-whites");
    private final SectionHeader notableHeader = new SectionHeader("Notable drops"), stripHeader = new SectionHeader("By dungeon");
    private final KitText filtered = KitText.caption(""), notes = KitText.caption(""), stripNote = KitText.caption("");
    private final TileList<HighlightsModel.Notable> notableList;
    private final TileList<HighlightsModel.DungeonCell> strip;
    private final JPanel notableEmptyHolder = new JPanel(new BorderLayout());
    private final JScrollPane page;
    private final javax.swing.Timer check, settle;
    private final Runnable filterChanged = this::renderNotable;
    private Consumer<VisitRef> openRun = visit -> { };
    private Consumer<String> openDungeon = dungeon -> { };
    private HighlightsModel.Window window;
    /** The newest successful model (any window), the failure a later read of its window met, and a failure with nothing to show. */
    private HighlightsModel model;
    private String staleReason, unavailableReason, emptyKey, unavailableKey, state = LOADING;
    private long staleAt, generation, readRevision;
    private int quietTicks;
    private boolean loading, closed, requested, listening;
    private Cancellation cancel = new Cancellation();

    /** The production tab: {@code source}'s saved history (or live capture), the window kept in the app's preferences. */
    public LootHighlights(HighlightsSource source) {
        this(reader(source), source.zone(), PropertiesManager::getProperty, PropertiesManager::setProperties, CHECK_MILLIS, SETTLE_MILLIS);
    }

    /**
     * As above with the reader, the zone drops' times are written in, the preference store, and the check and settle delays in ms
     * (tests: 0 = no timer; {@link #tick} and {@link #request} then run directly).
     */
    LootHighlights(Reader reader, ZoneId zone, Function<String, String> read, BiConsumer<String, String> write, int checkMillis, int settleMillis) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Loot highlights on the EDT");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.write = Objects.requireNonNull(write, "write");
        setName("loot-highlights");
        setOpaque(false);
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "RealmShark loot highlights");
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);
        readRevision = reader.revision();
        window = HighlightsModel.Window.of(read.apply(WINDOW_KEY));
        check = checkMillis > 0 ? new javax.swing.Timer(checkMillis, e -> tick()) : null;
        settle = settleMillis > 0 ? new javax.swing.Timer(settleMillis, e -> request()) : null;
        if (settle != null) settle.setRepeats(false);

        // Header: the source caption at the left; the window choice and ⋯ at the right.
        sourceCaption.setName("loot-highlights-source");
        windowControl.setSelected(window == HighlightsModel.Window.SESSION ? 1 : 0);
        windowControl.getAccessibleContext().setAccessibleName("Highlights period");
        windowControl.setToolTipText("Today is the local calendar day; This session is since RealmShark started");
        windowControl.onChange(index -> choose(index == 1 ? HighlightsModel.Window.SESSION : HighlightsModel.Window.TODAY));
        more.add("Refresh", this::refresh).setName("loot-highlights-refresh");
        add(KitLayouts.spread(Tokens.S, sourceCaption, windowControl, more), BorderLayout.NORTH);

        // Tiles: four across whenever each gets TILE_MIN px (1240×800 font 13 in the shell), else two by two or one per row. A long
        // sub-line wraps inside its tile (HighlightTile) rather than widening it, and the grid gives a row one height.
        stale.setTone(Tokens.Tone.WARN);
        partial.setTone(Tokens.Tone.WARN);
        stale.setVisible(false);
        partial.setVisible(false);
        JPanel tiles = ContentStyle.responsiveGrid(4, TILE_MIN, Tokens.S, true);
        tiles.setName("loot-highlights-tiles");
        tiles.setOpaque(false);
        for (StatTile tile : new StatTile[] {ut, st, potions, whites}) tiles.add(tile);

        // Notable drops.
        NotableDropRenderer renderer = new NotableDropRenderer(zone, this::now);
        notableList = new TileList<>("loot-notable-grid", renderer, HighlightsModel.Notable::key, drop -> NotableDropRenderer.accessibleName(drop, zone, now()));
        notableList.getAccessibleContext().setAccessibleName("Notable drops, newest first");
        notableList.getAccessibleContext().setAccessibleDescription("Arrow keys move between drops; Enter or Space opens the drop's run; Shift+F10 opens its menu");
        notableList.onOpen(this::open);
        notableList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        for (KeyStroke key : new KeyStroke[] {KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0)})
            notableList.getInputMap(JComponent.WHEN_FOCUSED).put(key, "loot-notable-menu");
        notableList.getActionMap().put("loot-notable-menu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int index = notableList.getSelectedIndex();
                Rectangle cell = index < 0 ? null : notableList.getCellBounds(index, index);
                if (cell != null) notableMenu(notableList.getModel().getElementAt(index)).show(notableList, cell.x + Tokens.M, cell.y + Tokens.M);
            }
        });
        selectFirstOnFocus(notableList);
        filtered.setName("loot-notable-filtered");
        notes.setName("loot-notable-notes");
        for (KitText line : new KitText[] {filtered, notes, stripNote}) line.setBorder(new EmptyBorder(0, Tokens.S, 0, 0));   // under the section titles
        filtered.setVisible(false);
        notes.setVisible(false);
        notableEmptyHolder.setOpaque(false);
        notableEmptyHolder.setVisible(false);

        // By dungeon.
        strip = new TileList<>("loot-dungeon-strip", new DungeonStripRenderer(), cell -> String.valueOf(cell.dungeon()), DungeonStripRenderer::accessibleName);
        strip.getAccessibleContext().setAccessibleName("Bags by dungeon");
        strip.getAccessibleContext().setAccessibleDescription("Arrow keys move between areas; Enter or Space shows the area's loot");
        strip.onOpen(this::openDungeon);
        selectFirstOnFocus(strip);
        stripNote.setName("loot-dungeon-strip-note");
        stripNote.setVisible(false);

        JPanel content = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }   // the page scrolls, never squeezes
        };
        content.setOpaque(false);
        content.add(KitLayouts.stack(Tokens.M, KitLayouts.stack(Tokens.XS, stale, partial), tiles,
            KitLayouts.stack(Tokens.S, notableHeader, filtered, notes, notableList, notableEmptyHolder),
            KitLayouts.stack(Tokens.S, stripHeader, stripNote, strip)), BorderLayout.NORTH);
        page = ContentStyle.page(null, content, null);
        page.setName("loot-highlights-scroll");
        // Transparent through to the host tab, so both themes show one background behind the raised tiles and cards.
        page.setOpaque(false);
        page.getViewport().setOpaque(false);
        ((JComponent) page.getViewport().getView()).setOpaque(false);
        page.addPropertyChangeListener("UI", e -> page.setBorder(null));   // a theme switch would reinstall the scroll pane's border
        page.getVerticalScrollBar().setUnitIncrement(32);
        page.getAccessibleContext().setAccessibleName("Loot highlights; scroll for more");

        EmptyState loadingState = new EmptyState("Loading loot highlights", "Tiles, notable drops and dungeons appear once saved loot has been read.", null);
        loadingState.setName("loot-highlights-loading");
        unavailableHolder.setOpaque(false);
        body.setOpaque(false);
        body.add(loadingState, LOADING);
        body.add(unavailableHolder, UNAVAILABLE);
        body.add(page, CONTENT);
        add(body, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || closed) return;
            if (isShowing()) shown(); else hidden();
        });
        render();
    }

    private static Reader reader(HighlightsSource source) {
        Objects.requireNonNull(source, "source");
        return new Reader() {
            @Override public HighlightsModel read(HighlightsModel.Window window, Cancellation cancel) { return source.read(window, cancel); }
            @Override public long revision() { return source.revision(); }
        };
    }

    /** A ⋯ entry (Task 11: "Loot sharing status…", "Loot filter settings…"), named {@code name}. EDT. */
    public void addOverflowAction(String name, String label, Runnable action) {
        Objects.requireNonNull(action, "action");
        more.add(label, action).setName(name);
    }

    /** What opening a notable drop runs: its exact run (drops without one open nothing). */
    public void onOpenRun(Consumer<VisitRef> action) { openRun = Objects.requireNonNull(action, "action"); }

    /** What opening a strip cell runs: the canonical dungeon name, or null for Unknown area. */
    public void onDungeon(Consumer<String> action) { openDungeon = Objects.requireNonNull(action, "action"); }

    /** Reads the chosen window again now (⋯ Refresh, Try again). EDT. */
    public void refresh() { request(); }

    /** Stops the worker and the checks. Idempotent. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (check != null) check.stop();
        if (settle != null) settle.stop();
        worker.shutdownNow();
        LootFilters.get().removeListener(filterChanged);
        listening = false;
    }

    // Tests.
    HighlightsModel model() { return model; }
    boolean loading() { return loading; }
    HighlightsModel.Window window() { return window; }
    String state() { return state; }
    TileList<HighlightsModel.Notable> notableList() { return notableList; }
    TileList<HighlightsModel.DungeonCell> stripList() { return strip; }

    /** Opens the drop's exact run; a drop without one opens nothing (the cell says "Not linked to a run"). EDT. */
    void open(HighlightsModel.Notable drop) {
        if (closed || drop == null || drop.visit() == null) return;
        openRun.accept(drop.visit());
    }

    /** Opens the area's loot (null = Unknown area). EDT. */
    void openDungeon(HighlightsModel.DungeonCell cell) {
        if (closed || cell == null) return;
        openDungeon.accept(cell.dungeon());
    }

    /** The drop's menu: Open run recap, disabled with the reason when the drop recorded no exact run. */
    JPopupMenu notableMenu(HighlightsModel.Notable drop) {
        JPopupMenu menu = new JPopupMenu(Sprites.name(drop.itemId()));
        menu.setName("loot-notable-menu");
        JMenuItem recap = new JMenuItem("Open run recap");
        recap.setName("loot-notable-open-run");
        recap.setEnabled(drop.visit() != null);
        if (drop.visit() == null) recap.setToolTipText(NotableDropRenderer.NOT_LINKED);
        recap.getAccessibleContext().setAccessibleDescription(drop.visit() == null ? NotableDropRenderer.NOT_LINKED : "Opens the run this drop came from");
        recap.addActionListener(e -> open(drop));
        menu.add(recap);
        return menu;
    }

    /**
     * A check while showing: the feed's revision changed since the last nudge (a bag was observed) → read after the settle delay;
     * else every {@value #PERIODIC_TICKS} checks read anyway. EDT.
     */
    void tick() {
        if (closed || !isShowing()) return;
        long revision = reader.revision();
        if (revision != readRevision) {
            readRevision = revision;
            if (settle == null) request(); else settle.restart();
            return;
        }
        if (++quietTicks >= PERIODIC_TICKS && !loading) request();
    }

    private void shown() {
        if (check != null) check.start();
        if (!listening) { LootFilters.get().addListener(filterChanged); listening = true; }
        renderNotable();   // Filter Loot may have changed while hidden
        if (!requested) request();   // the first show reads; later shows only resume the checks
    }

    private void hidden() {
        if (check != null) check.stop();
        // A nudged read still settling is not lost: the first check after the next show sees a changed revision again.
        if (settle != null && settle.isRunning()) { settle.stop(); readRevision = -1; }
        if (listening) { LootFilters.get().removeListener(filterChanged); listening = false; }
    }

    /** A user's window choice: kept, and read. */
    private void choose(HighlightsModel.Window next) {
        if (closed || next == window) return;
        window = next;
        write.accept(WINDOW_KEY, next.key());
        staleReason = null;
        unavailableReason = null;
        request();
    }

    /** Starts a read of the chosen window on the worker; a newer request makes older results inert. EDT. */
    void request() {
        if (closed) return;
        requested = true;
        quietTicks = 0;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        HighlightsModel.Window asked = window;
        loading = true;
        render();
        try {
            worker.execute(() -> {
                HighlightsModel result;
                try {
                    result = Objects.requireNonNull(reader.read(asked, token), "highlights");
                } catch (CancellationException cancelled) {
                    return;   // a newer request replaced this one
                } catch (Exception | Error failed) {
                    result = HighlightsModel.unavailable(asked, HighlightsModel.Source.SAVED,
                        "Loot highlights could not be read: " + HighlightsSource.words(failed), System.currentTimeMillis());
                }
                HighlightsModel done = result;
                SwingUtilities.invokeLater(() -> apply(ticket, done));
            });
        } catch (RejectedExecutionException shutDown) { loading = false; }
    }

    /** EDT: applies the newest request's result; a failed read keeps a good one of the same window, labeled stale. */
    private void apply(long ticket, HighlightsModel result) {
        if (closed || ticket != generation) return;
        loading = false;
        if (result.unavailable() == null) {
            model = result;
            staleReason = null;
            unavailableReason = null;
        } else if (model != null && model.window() == result.window()) {
            staleReason = result.unavailable();
            staleAt = model.capturedAt();
        } else {
            model = null;
            unavailableReason = result.unavailable();
        }
        render();
    }

    /** The model of the chosen window, or null while none was read for it. */
    private HighlightsModel current() { return model != null && model.window() == window ? model : null; }

    private long now() { HighlightsModel shown = model; return shown == null ? System.currentTimeMillis() : shown.capturedAt(); }

    /** EDT: the card, caption, banners, tiles, grid and strip for the current state. */
    private void render() {
        HighlightsModel shown = current();
        if (shown == null) {
            state = !loading && unavailableReason != null ? UNAVAILABLE : LOADING;
            if (state.equals(UNAVAILABLE)) showUnavailable(unavailableReason);
            cards.show(body, state);
            sourceCaption.setText(window.label() + (loading ? " · reading…" : ""));
            revalidate();
            repaint();
            return;
        }
        state = CONTENT;
        cards.show(body, CONTENT);
        sourceCaption.setText(shown.sourceLabel() + (loading ? " · refreshing…" : ""));
        boolean old = staleReason != null;
        stale.setText(old ? "Showing the last successful read (" + clock(staleAt) + "): " + staleReason : "");
        stale.setVisible(old);
        int skipped = shown.sessionsSkipped();
        partial.setText(skipped > 0 ? "◐ " + HighlightsModel.unreadable(skipped) + "; their loot is missing from these counts." : "");
        partial.setVisible(skipped > 0);
        ut.setValue(staled(shown.ut()), null);
        st.setValue(staled(shown.st()), null);
        boolean known = shown.whites().state != DisplayValue.State.UNKNOWN;
        String stats = HighlightsModel.potionLine(shown.potionsByStat());
        potions.setValue(staled(shown.potions()), known && !stats.isEmpty() ? stats : null);
        whites.setValue(staled(shown.whites()), known ? "of " + count(shown.bags(), "bag")
            + (shown.unnamedBags() > 0 ? " · " + DisplayFormat.formatInteger(shown.unnamedBags()) + " without a bag name" : "") : null);
        renderNotable();
        strip.setItems(shown.dungeons());   // an equal list fires nothing
        strip.setVisible(!shown.dungeons().isEmpty());
        stripHeader.setCount(shown.dungeons().isEmpty() ? null : count(shown.bags(), "bag"));
        stripNote.setText(shown.dungeons().isEmpty() ? "No bags in this period." : "");
        stripNote.setVisible(shown.dungeons().isEmpty());
        revalidate();
        repaint();
    }

    /** EDT: the notable grid under Filter Loot (bag visibility), the hidden-drops line, the notes and the grid's empty state. */
    private void renderNotable() {
        HighlightsModel shown = current();
        if (shown == null || closed) return;
        LootFilters filters = LootFilters.get();
        List<HighlightsModel.Notable> visible = new ArrayList<>();
        for (HighlightsModel.Notable drop : shown.notable()) if (filters.showsBagName(drop.bag())) visible.add(drop);
        int held = shown.notable().size(), hidden = held - visible.size();
        notableList.setItems(visible);
        notableList.setVisible(!visible.isEmpty());
        notableHeader.setCount(shown.notableTotal() == 0 ? null : DisplayFormat.formatInteger(shown.notableTotal()));
        filtered.setText(hidden > 0 ? "Filter Loot hides " + hidden + " of " + held + " notable drops; the tiles still count them" : "");
        filtered.setVisible(hidden > 0);
        List<String> lines = new ArrayList<>();
        if (shown.notableTotal() > held) lines.add("Showing the newest " + held + " of " + DisplayFormat.formatInteger(shown.notableTotal()) + " notable drops");
        if (shown.enchantUnknown() > 0) lines.add(count(shown.enchantUnknown(), "item") + " without recorded enchant slots "
            + (shown.enchantUnknown() == 1 ? "is" : "are") + " not listed as enchanted");
        notes.setText(String.join(" · ", lines));
        notes.setVisible(!lines.isEmpty());
        String title = null, text = null;
        if (visible.isEmpty() && held == 0) {
            title = "No notable drops yet";
            boolean nothing = shown.ut().state == DisplayValue.State.UNKNOWN;
            text = nothing ? (shown.source() == HighlightsModel.Source.LIVE_UNSAVED ? "No loot was observed in this app run yet." : HighlightsModel.NO_LOOT + ".")
                + " Start capture and run a dungeon: UT, ST, stat potion and enchanted drops appear here."
                : "UT, ST, stat potion and enchanted (rare or better) drops appear here as they are observed.";
        } else if (visible.isEmpty()) {
            title = "Filter Loot hides every notable drop";
            text = count(held, "notable drop") + " hidden by Filter Loot (Edit › Filter Loot); the tiles still count them.";
        }
        if (title == null) { notableEmptyHolder.setVisible(false); emptyKey = null; }
        else {
            String key = title + "\n" + text;
            if (!key.equals(emptyKey)) {
                notableEmptyHolder.removeAll();
                EmptyState empty = new EmptyState(title, text, null);
                empty.setName("loot-notable-empty");
                notableEmptyHolder.add(empty, BorderLayout.CENTER);
                emptyKey = key;
            }
            notableEmptyHolder.setVisible(true);
        }
        revalidate();
        repaint();
    }

    /** The unavailable card: the reason and Try again. */
    private void showUnavailable(String reason) {
        String body = (reason.endsWith(".") ? reason.substring(0, reason.length() - 1) : reason) + ". Try again.";
        if (body.equals(unavailableKey)) return;
        unavailableHolder.removeAll();
        KitButton retry = KitButton.secondary("Try again");
        retry.setName("loot-highlights-retry");
        retry.addActionListener(e -> refresh());
        EmptyState empty = new EmptyState("Loot highlights are unavailable", body, retry);
        empty.setName("loot-highlights-unavailable");
        unavailableHolder.add(empty, BorderLayout.CENTER);
        unavailableKey = body;
    }

    /** A value of the last successful read while a newer read failed: stale, with when and why; unknown stays unknown. */
    private DisplayValue staled(DisplayValue value) {
        if (staleReason == null || value.state == DisplayValue.State.UNKNOWN) return value;
        return DisplayValue.stale(value.text(), "Last read at " + clock(staleAt) + "; the newer read failed: " + staleReason);
    }

    private String clock(long at) { return CLOCK.format(Instant.ofEpochMilli(at).atZone(zone)); }

    private static String count(int value, String noun) { return DisplayFormat.formatInteger(value) + " " + noun + (value == 1 ? "" : "s"); }

    private static StatTile tile(String label, String name) {
        StatTile tile = new HighlightTile(label);
        tile.setName(name);
        return tile;
    }

    /**
     * A StatTile whose sub-line wraps ({@link SubLine}) instead of widening the tile (Polish B2): StatTile's own one-line sub-line
     * made "2 Life · 1 Mana · 1 Att · 1 Def · +2 more" the tile's preferred width, so the four tiles went two by two at desktop
     * width. The wrapping sub-line takes StatTile's place under the value (child 2); StatTile's own stays hidden. The accessible
     * name is StatTile's ("Potions: 7, 2 Life · …").
     */
    static final class HighlightTile extends StatTile {
        /** Null while StatTile's constructor sets the first value. */
        private final SubLine sub;

        HighlightTile(String label) {
            super(label);
            sub = new SubLine();
            sub.setVisible(false);
            add(sub, 2);
        }

        @Override public void setValue(DisplayValue shown, String subline) {
            super.setValue(shown, null);
            if (sub == null) return;
            boolean has = subline != null && !subline.isEmpty();
            sub.setText(has ? subline : "");
            sub.setVisible(has);
            if (has) getAccessibleContext().setAccessibleName(getAccessibleContext().getAccessibleName() + ", " + subline);
        }
    }

    /**
     * A tile's sub-line that wraps at the tile's width, between its " · " parts ("2 Life · 1 Mana · 1 Att" / "1 Def · +2 more"),
     * inside a part at its spaces only when the part alone is wider than the tile. Its preferred width is its widest part, so a tile
     * row keeps every part whole; its height follows the width it is given, and a change in the line count lays the row out again
     * once the current layout has finished. The text stays the plain sub-line (screen readers read it whole). A word wider than the
     * tile, the one thing that cannot wrap, ends in "…" and the whole sub-line is the tooltip.
     */
    static final class SubLine extends JLabel {
        static final String SEPARATOR = " · ";
        /** The line count the last preferred size was measured with (-1: never measured). */
        private int measured = -1;
        private List<String> painted = List.of();

        SubLine() {
            putClientProperty("html.disable", Boolean.TRUE);
            setAlignmentX(LEFT_ALIGNMENT);
            ContentStyle.font(this, Type.caption());
            ToolTipManager.sharedInstance().registerComponent(this);   // a tooltip only when a word is cut (getToolTipText)
        }

        /** {@code text}'s lines at {@code width} px: parts joined by " · " while they fit; a part wider than a line breaks at spaces. */
        static List<String> lines(String text, FontMetrics metrics, int width) {
            List<String> lines = new ArrayList<>();
            if (text == null || text.isEmpty()) return lines;
            String line = null;
            for (String part : text.split(java.util.regex.Pattern.quote(SEPARATOR), -1)) {
                String joined = line == null ? part : line + SEPARATOR + part;
                if (line != null && metrics.stringWidth(joined) <= width) { line = joined; continue; }
                if (line != null) lines.add(line);
                line = null;
                if (metrics.stringWidth(part) <= width) { line = part; continue; }
                for (String word : part.split(" ", -1)) {   // a part alone is too wide: its words, as many a line as fit
                    String words = line == null ? word : line + " " + word;
                    if (line == null || metrics.stringWidth(words) <= width) line = words;
                    else { lines.add(line); line = word; }
                }
            }
            if (line != null) lines.add(line);
            return lines;
        }

        private String text() { return getText() == null ? "" : getText(); }

        /** The width the text wraps at: this label's, or before its first layout its parent's (the tile's inner width). */
        private int wrapWidth() {
            Insets insets = getInsets();
            int width = getWidth();
            if (width <= 0 && getParent() != null) {
                Insets parent = getParent().getInsets();
                width = getParent().getWidth() - parent.left - parent.right;
            }
            return width - insets.left - insets.right;
        }

        private int lineCount() {
            int width = wrapWidth();
            return width <= 0 ? 1 : Math.max(1, lines(text(), getFontMetrics(getFont()), width).size());
        }

        @Override public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) return super.getPreferredSize();
            FontMetrics metrics = getFontMetrics(getFont());
            Insets insets = getInsets();
            int widest = 0;
            for (String part : text().split(java.util.regex.Pattern.quote(SEPARATOR), -1)) widest = Math.max(widest, metrics.stringWidth(part));
            measured = lineCount();
            return new Dimension(widest + insets.left + insets.right, measured * metrics.getHeight() + insets.top + insets.bottom);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        /** As wide as the tile (BoxLayout stretches it to the tile's inner width), never taller than its lines. */
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override public void setBounds(int x, int y, int width, int height) {
            boolean resized = width != getWidth();
            super.setBounds(x, y, width, height);
            // The row measured this label at another width; if that changes the line count, lay the row out again after this pass.
            if (resized && measured >= 0 && lineCount() != measured) SwingUtilities.invokeLater(this::revalidate);
        }

        /** The whole sub-line, only when a word wider than the tile is cut. */
        @Override public String getToolTipText() {
            int width = wrapWidth();
            if (width <= 0) return null;
            FontMetrics metrics = getFontMetrics(getFont());
            for (String line : lines(text(), metrics, width)) if (metrics.stringWidth(line) > width) return text();
            return null;
        }

        @Override public void updateUI() {
            super.updateUI();
            setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));   // follows a theme switch, as StatTile's own sub-line
        }

        /** The lines the last paint drew (tests). */
        List<String> painted() { return painted; }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
                if (hints instanceof java.util.Map) g.addRenderingHints((java.util.Map<?, ?>) hints);
                g.setFont(getFont());
                g.setColor(getForeground());
                FontMetrics metrics = g.getFontMetrics();
                Insets insets = getInsets();
                int width = getWidth() - insets.left - insets.right, baseline = insets.top + metrics.getAscent();
                List<String> drawn = new ArrayList<>();
                for (String line : lines(text(), metrics, width)) {
                    String fitted = NotableDropRenderer.fit(line, metrics, width);
                    g.drawString(fitted, insets.left, baseline);
                    drawn.add(fitted);
                    baseline += metrics.getHeight();
                }
                painted = List.copyOf(drawn);
            } finally {
                g.dispose();
            }
        }
    }

    /** Tab into a list selects its first item, so Enter opens something and a reader announces an item. */
    private static void selectFirstOnFocus(TileList<?> list) {
        list.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { if (list.isSelectionEmpty() && list.getModel().getSize() > 0) list.setSelectedIndex(0); }
        });
    }

    /** A right click (the platform's popup trigger) selects the drop under it and opens its menu. */
    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = notableList.locationToIndex(e.getPoint());
        Rectangle cell = index < 0 ? null : notableList.getCellBounds(index, index);
        if (cell == null || !cell.contains(e.getPoint())) return;
        notableList.setSelectedIndex(index);
        notableMenu(notableList.getModel().getElementAt(index)).show(notableList, e.getX(), e.getY());
    }
}
