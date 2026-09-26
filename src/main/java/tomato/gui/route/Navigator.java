package tomato.gui.route;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Typed navigation with a bounded Back stack. EDT only. Feature code calls {@link #current()} and
 * never depends on the shell directly; until the shell installs its navigator, routes are rejected.
 */
public interface Navigator {
    /** Captures the origin for Back, then opens the route. Returns false when no target accepts it. */
    boolean open(Route route);
    /** Restores the most recent origin. Returns false when the stack is empty. */
    boolean back();
    boolean canGoBack();
    /** Whether some registered target would accept the route; use to enable or explain an action. */
    boolean canOpen(Route route);

    Navigator NONE = new Navigator() {
        public boolean open(Route route) { return false; }
        public boolean back() { return false; }
        public boolean canGoBack() { return false; }
        public boolean canOpen(Route route) { return false; }
    };

    static Navigator current() { return Holder.CURRENT.get(); }
    static void install(Navigator navigator) { Holder.CURRENT.set(navigator == null ? NONE : navigator); }

    final class Holder {
        private static final AtomicReference<Navigator> CURRENT = new AtomicReference<>(NONE);
        private Holder() {}
    }
}
