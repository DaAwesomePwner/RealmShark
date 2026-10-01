package tomato.gui.modern;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * A shell page with customizable tabs and one composite Back state across all its route targets. Startup selects the first
 * visible tab without changing preferences; explicit navigation shows hidden tabs. Each tab may have an unwrapped owner
 * whose state is captured and restored after its tab comes forward. A rejected open re-selects the previous tab and re-hides
 * the destination tab if it was hidden, before the rejection reaches the navigator.
 * Closing reaches hidden content too, is idempotent, and closes all content before rethrowing the first failure. EDT only.
 * The concrete state records remain on the pages to preserve their public, typed Back-state API.
 */
public abstract class TabbedRoutePage<T extends Enum<T>, S extends TabbedRoutePage.TabState<T>> extends JPanel implements AutoCloseable {
    /** Detached Back state: the selected tab and its owner's state, or null when it has no owner. */
    public interface TabState<T> { T tab(); Object inner(); }

    private final CustomizableTabs tabs;
    private final Map<T, RouteTarget> owners;
    private final Function<T, String> id;
    private final Function<String, T> lookup;
    private final Class<S> stateType;
    private final BiFunction<T, Object, S> stateFactory;
    private final String title;
    private boolean closed;

    protected TabbedRoutePage(String group, String name, String title, Class<T> tabType, Function<T, String> id,
            Function<String, T> lookup, Class<S> stateType, BiFunction<T, Object, S> stateFactory) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the " + title + " page on the EDT");
        this.title = title;
        this.id = id;
        this.lookup = lookup;
        this.stateType = stateType;
        this.stateFactory = stateFactory;
        owners = new EnumMap<>(tabType);
        tabs = new CustomizableTabs(group);
        setName(name);
        setLayout(new BorderLayout());
        setOpaque(false);
        tabs.component().setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabs.component(), BorderLayout.CENTER);
    }

    /** Called after the subclass fields are ready, so content factories may safely create holders. */
    protected final void populate(T[] values, Function<T, String> title, Function<T, JComponent> content) {
        for (T tab : values) tabs.add(id.apply(tab), title.apply(tab), content.apply(tab));
        List<String> visible = tabs.visibleIds();
        if (!visible.isEmpty()) tabs.select(visible.get(0));
    }

    public final CustomizableTabs tabs() { return tabs; }
    /** The tab in front, or null when none is. */
    public final T selectedTab() { return lookup.apply(tabs.selectedId()); }

    /** Explicit navigation shows a hidden tab, updating the saved hidden set, then selects it. */
    public final void bring(T tab) {
        String key = id.apply(Objects.requireNonNull(tab, "tab"));
        tabs.show(key);
        tabs.select(key);
    }

    /** The tab's own unwrapped state owner; null removes it. Page targets would recursively capture the page and are refused. */
    public final void owner(T tab, RouteTarget owner) {
        Objects.requireNonNull(tab, "tab");
        if (owner instanceof TabbedRoutePage<?, ?>.PageTarget target && target.stateType() == stateType)
            throw new IllegalArgumentException("An owner is the tab's own target, not a " + title + " page target");
        if (owner == null) owners.remove(tab); else owners.put(tab, owner);
    }

    /** Wraps a tab target, preserving its destination, acceptance and redirect, with this page's opening and Back behavior. */
    public final RouteTarget routes(T tab, RouteTarget inner) {
        Objects.requireNonNull(tab, "tab");
        Objects.requireNonNull(inner, "inner");
        return new PageTarget() {
            @Override public Destination destination() { return inner.destination(); }
            @Override public boolean accepts(Route route) { return inner.accepts(route); }
            @Override public Route redirect(Route route) { return inner.redirect(route); }
            @Override public void open(Route route) { openOn(tab, () -> inner.open(route)); }
        };
    }

    protected final void openOn(T tab, Runnable open) {
        T before = selectedTab();
        boolean hidden = tabs.hiddenIds().contains(id.apply(tab));
        bring(tab);
        try { open.run(); }
        catch (RuntimeException failed) {
            if (before != null) tabs.select(id.apply(before));
            if (hidden) tabs.hide(id.apply(tab));
            throw failed;
        }
    }

    private S capture() {
        T tab = selectedTab();
        RouteTarget owner = tab == null ? null : owners.get(tab);
        return stateFactory.apply(tab, owner == null ? null : owner.captureState());
    }

    private void restore(Object state) {
        if (!stateType.isInstance(state)) throw new IllegalArgumentException("Not a " + title + " page state");
        S saved = stateType.cast(state);
        if (saved.tab() == null) return;
        bring(saved.tab());
        RouteTarget owner = owners.get(saved.tab());
        if (owner != null && saved.inner() != null) owner.restoreState(saved.inner());
    }

    /** Whether a route carries any reference besides its payload. */
    protected static boolean referenced(Route route) {
        return route.query != null || route.visit != null || route.record != null || route.recordingId != null
            || route.localObjectId != null || route.from != null || route.until != null;
    }

    protected abstract class PageTarget implements RouteTarget {
        private Class<?> stateType() { return stateType; }
        @Override public final Object captureState() { return capture(); }
        @Override public final void restoreState(Object state) { restore(state); }
    }

    protected final boolean closed() { return closed; }
    protected Component contentOf(Component tab) { return tab; }

    @Override public final void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Component tab : tabs.contents()) {
            Component content = contentOf(tab);
            if (!(content instanceof AutoCloseable)) continue;
            try { ((AutoCloseable) content).close(); }
            catch (Exception failed) {
                if (failure == null) failure = failed instanceof RuntimeException ? (RuntimeException) failed : new IllegalStateException(failed);
                else failure.addSuppressed(failed);
            }
        }
        if (failure != null) throw failure;
    }
}
