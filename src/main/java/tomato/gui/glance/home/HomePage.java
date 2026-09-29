package tomato.gui.glance.home;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import util.PropertiesManager;

/**
 * Home, the shell's {@code home} page (spec §6.1): the hero row; Now | Today; Recent runs | Quests at 1.5 : 1; everything stacks in the same
 * order below 1000 px. HomeRefresher reads the sources on its own threads while this page is showing and its window is not
 * minimized, and pauses otherwise; the EDT only applies the immutable models it publishes (S9). Under the same conditions,
 * while the applied Now is a live run with a start time, a 1 s EDT timer advances only Now's elapsed time. With null
 * sources the page shows only the models applied to it (tests and evidence).
 */
public final class HomePage extends JPanel {
    static final String WINDOW_KEY = "ui.home.window";
    private final HeroCard hero;
    private final NowCard now;
    private final TodayTiles today;
    private final RecentRunsCard runs;
    private final QuestsCard quests;
    private final HomeRefresher refresher;
    private final LongSupplier clock;
    private final Timer elapsedTick;   // EDT: Now's elapsed time, each second
    private final WindowAdapter minimized = new WindowAdapter() {
        @Override public void windowIconified(WindowEvent event) { iconified = true; showingChanged(); }
        @Override public void windowDeiconified(WindowEvent event) { iconified = false; showingChanged(); }
    };
    private Window window;
    private boolean iconified, closed;
    private HomeModel model = HomeModel.LOADING, shown;
    private long timedAt;

    public HomePage(HomeSources sources, HomeActions actions) {
        this(sources, actions, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties,
            System::currentTimeMillis);
    }

    HomePage(HomeSources sources, HomeActions actions, DisplayModeModel mode, Function<String, String> read,
             BiConsumer<String, String> write, LongSupplier clock) {
        super(new BorderLayout());
        setName("home-page");
        setOpaque(false);
        this.clock = clock;
        HomeArchive.Window initial = "session".equals(read.apply(WINDOW_KEY)) ? HomeArchive.Window.SESSION : HomeArchive.Window.TODAY;
        hero = new HeroCard(actions.characters(), actions.build(), mode);
        now = new NowCard(actions.meter(), mode);
        elapsedTick = new Timer(1_000, event -> now.tick(clock.getAsLong()));
        today = new TodayTiles(window -> windowChanged(window, write), initial, mode, actions.loot());
        runs = new RecentRunsCard(actions.run(), mode);
        quests = new QuestsCard(actions.quests(), mode);
        JPanel grid = new JPanel(new Grid());
        grid.setName("home-grid");
        grid.setOpaque(false);
        grid.setBorder(new EmptyBorder(Tokens.L, Tokens.L, Tokens.L, Tokens.L));
        for (JComponent card : new JComponent[] {hero, now, today, runs, quests}) grid.add(card);
        add(ContentStyle.page(null, grid, null), BorderLayout.CENTER);
        refresher = sources == null ? null : new HomeRefresher(sources, this::apply, clock, initial);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & (HierarchyEvent.SHOWING_CHANGED | HierarchyEvent.PARENT_CHANGED)) != 0) showingChanged();
        });
        apply(HomeModel.LOADING);
    }

    private void windowChanged(HomeArchive.Window window, BiConsumer<String, String> write) {
        write.accept(WINDOW_KEY, window == HomeArchive.Window.SESSION ? "session" : "today");
        if (refresher != null && !closed) refresher.setWindow(window);
    }

    /** 1 Hz refresh only while Home is visible (spec §3.1): not while another page is shown or the window is minimized. */
    private void showingChanged() {
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        if (ancestor != window) {
            if (window != null) window.removeWindowListener(minimized);
            window = closed ? null : ancestor;
            iconified = window instanceof Frame && (((Frame) window).getExtendedState() & Frame.ICONIFIED) != 0;
            if (window != null) window.addWindowListener(minimized);
        }
        updateElapsedTick();
        if (refresher == null || closed) return;
        if (isShowing() && !iconified) refresher.start();
        else refresher.stop();
    }

    /**
     * Applies a model from HomeRefresher or a test. EDT only. A card whose section is the same object as last time is skipped,
     * except that every REBUILD_MILLIS each card is offered the page clock so relative times ("12 min ago") stay current; the
     * cards then update prebuilt components in place, and only what changed (S9).
     */
    public void apply(HomeModel next) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Apply Home models on the EDT");
        model = next == null ? HomeModel.LOADING : next;
        long time = clock.getAsLong();
        HomeModel previous = shown;
        boolean retime = previous == null || time - timedAt >= HomeRefresher.REBUILD_MILLIS || time < timedAt;
        if (retime) timedAt = time;
        shown = model;
        if (retime || previous.hero() != model.hero()) hero.apply(model.hero(), time);
        if (retime || previous.now() != model.now()) now.apply(model.now(), time);
        if (previous == null || previous.today() != model.today()) today.apply(model.today());
        if (retime || previous.runs() != model.runs()) runs.apply(model.runs(), time);
        if (retime || previous.quests() != model.quests()) quests.apply(model.quests(), time);
        updateElapsedTick();
    }

    /** Runs the 1 s elapsed tick only while Home is showing, not minimized or closed, and Now is a live run with a start time. */
    private void updateElapsedTick() {
        HomeModel.Now live = shown == null ? null : shown.now();
        boolean run = !closed && !iconified && isShowing() && live != null && live.state() == HomeModel.State.LIVE && live.startedAt() != null;
        if (run && !elapsedTick.isRunning()) elapsedTick.start();
        else if (!run && elapsedTick.isRunning()) elapsedTick.stop();
    }
    boolean elapsedTicking() { return elapsedTick.isRunning(); }

    public HomeModel model() { return model; }

    /** Stops the refresher for good; TomatoGUI.closeWorkspace calls this. EDT only. */
    public void close() {
        closed = true;
        elapsedTick.stop();
        if (window != null) { window.removeWindowListener(minimized); window = null; }
        if (refresher != null) refresher.close();
    }

    /** Wide means the window's root pane is at least 1000 px (HomeViews.WIDE), the width at which the shell leaves compact mode. */
    static final class Grid implements LayoutManager {
        static final int GAP = 10, WIDE = HomeViews.WIDE; // spec §5.2: 10 px between cards
        private static final double NOW_SHARE = .5, RUNS_SHARE = .6; // Recent runs : Quests = 1.5 : 1

        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension preferredLayoutSize(Container target) { return size(target); }
        @Override public Dimension minimumLayoutSize(Container target) { return size(target); }
        @Override public void layoutContainer(Container target) { place(target, true); }

        /** No width floor (the page tracks the viewport width, so nothing scrolls sideways); the height the cards need at this width. */
        private static Dimension size(Container target) {
            Insets insets = target.getInsets();
            return new Dimension(insets.left + insets.right, place(target, false));
        }

        static boolean wide(Container target) { return HomeViews.wide(target); }

        private static int place(Container target, boolean apply) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int width = Math.max(0, available(target) - insets.left - insets.right), x = insets.left, y = insets.top;
                Component[] cards = target.getComponents();
                if (cards.length == 5 && wide(target)) {
                    y = row(cards[0], null, x, y, width, 1, apply);
                    y = row(cards[1], cards[2], x, y, width, NOW_SHARE, apply);
                    y = row(cards[3], cards[4], x, y, width, RUNS_SHARE, apply);
                } else {
                    for (Component card : cards) if (card.isVisible()) y = row(card, null, x, y, width, 1, apply);
                }
                return (y > insets.top ? y - GAP : y) + insets.bottom;
            }
        }

        /** One card across the width, or two sharing it at `share` for the left one, both at the taller preferred height. */
        private static int row(Component left, Component right, int x, int y, int width, double share, boolean apply) {
            if (right == null) {
                int height = left.getPreferredSize().height;
                if (apply) left.setBounds(x, y, width, height);
                return y + height + GAP;
            }
            int leftWidth = (int) Math.round((width - GAP) * share), rightWidth = Math.max(0, width - GAP - leftWidth);
            int height = Math.max(left.getPreferredSize().height, right.getPreferredSize().height);
            if (apply) {
                left.setBounds(x, y, leftWidth, height);
                right.setBounds(x + leftWidth + GAP, y, rightWidth, height);
            }
            return y + height + GAP;
        }

        private static int available(Container target) {
            if (target.getWidth() > 0) return target.getWidth();
            Container parent = target.getParent();
            return parent == null ? 0 : parent.getWidth();
        }
    }
}
