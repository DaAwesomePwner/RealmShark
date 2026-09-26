package tomato.gui.route;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * The shell's {@link Navigator}: a registry of destination adapters plus a bounded Back stack. EDT only.
 * Opening a route captures the origin page and, when that page has a registered target, its detached
 * state before anything changes. A route that no target accepts is rejected without any change.
 * Ordinary sidebar selection does not use this class, so module scopes stay independent.
 */
public final class ShellNavigator implements Navigator {
    public static final int DEFAULT_CAPACITY = 20;
    /** Page value for destinations that are not a shell page (for example a modeless draft dialog). */
    public static final int NO_PAGE = -1;

    private final IntSupplier selected;
    private final IntConsumer select;
    private final ToIntFunction<Destination> pageOf;
    private final int capacity;
    // Newest registration first, so a module's specific target supersedes a generic adapter.
    private final List<RouteTarget> targets = new ArrayList<>();
    private final Map<Integer, RouteTarget> shown = new HashMap<>();
    private final ArrayDeque<Origin> back = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public ShellNavigator(IntSupplier selected, IntConsumer select, ToIntFunction<Destination> pageOf, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Back stack needs a positive capacity");
        this.selected = Objects.requireNonNull(selected); this.select = Objects.requireNonNull(select);
        this.pageOf = Objects.requireNonNull(pageOf); this.capacity = capacity;
    }

    /** Registers a destination adapter; a later registration for the same destination is tried first. */
    public void register(RouteTarget target) {
        requireEdt();
        Objects.requireNonNull(target.destination(), "destination");
        targets.remove(target); targets.add(0, target);
    }
    public boolean unregister(RouteTarget target) {
        requireEdt();
        shown.values().removeIf(value -> value == target);
        return targets.remove(target);
    }
    /** Called after every stack change so the shell can update its visible Back action. */
    public void addChangeListener(Runnable listener) { requireEdt(); listeners.add(Objects.requireNonNull(listener)); }

    @Override public boolean canOpen(Route route) { requireEdt(); return route != null && target(route) != null; }
    @Override public boolean canGoBack() { requireEdt(); return !back.isEmpty(); }
    /** Shell page Back returns to, or {@link #NO_PAGE} when the stack is empty. */
    public int backPage() { requireEdt(); return back.isEmpty() ? NO_PAGE : back.peekLast().page; }
    public int depth() { requireEdt(); return back.size(); }

    @Override public boolean open(Route route) {
        requireEdt();
        RouteTarget target = route == null ? null : target(route);
        if (target == null) return false;
        int destinationPage = pageOf.applyAsInt(route.destination);
        int originPage = selected.getAsInt();
        // Capture before navigating; a failed capture leaves everything unchanged.
        Origin origin = null;
        if (destinationPage != NO_PAGE) {
            RouteTarget originTarget = shownOn(originPage);
            origin = new Origin(originPage, originTarget, originTarget == null ? null : originTarget.captureState());
        }
        // The destination applies its state before its page is shown, so showing it starts no second load.
        try { target.open(route); }
        catch (RuntimeException failure) { return false; } // The origin page stays current.
        if (destinationPage != NO_PAGE) select.accept(destinationPage);
        if (origin != null) {
            shown.put(destinationPage, target);
            back.addLast(origin);
            while (back.size() > capacity) back.removeFirst();
        }
        changed();
        return true;
    }

    @Override public boolean back() {
        requireEdt();
        Origin origin = back.pollLast();
        if (origin == null) return false;
        try {
            if (origin.target != null && targets.contains(origin.target)) {
                origin.target.restoreState(origin.state);
                shown.put(origin.page, origin.target);
            }
        } finally { select.accept(origin.page); changed(); }
        return true;
    }

    private RouteTarget target(Route route) {
        for (RouteTarget target : targets) {
            if (target.destination() != route.destination) continue;
            try { if (target.accepts(route)) return target; }
            catch (RuntimeException rejected) { /* A failing validation is a rejection, not a crash. */ }
        }
        return null;
    }
    private RouteTarget shownOn(int page) {
        RouteTarget current = shown.get(page);
        if (current != null && targets.contains(current)) return current;
        for (RouteTarget target : targets) if (pageOf.applyAsInt(target.destination()) == page) return target;
        return null;
    }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Navigation requires the EDT");
    }

    private static final class Origin {
        final int page;
        final RouteTarget target;
        final Object state;
        Origin(int page, RouteTarget target, Object state) { this.page = page; this.target = target; this.state = state; }
    }
}
