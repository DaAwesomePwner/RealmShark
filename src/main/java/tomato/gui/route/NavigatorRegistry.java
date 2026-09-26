package tomato.gui.route;

/**
 * Registration entry point for module-owned {@link RouteTarget}s. EDT only. Targets register with the
 * installed shell navigator; without one (headless or preview composition) nothing is registered and
 * every route keeps being rejected rather than approximated.
 */
public final class NavigatorRegistry {
    private NavigatorRegistry() {}

    /** Returns false when no shell navigator is installed. */
    public static boolean register(RouteTarget target) {
        Navigator current = Navigator.current();
        if (!(current instanceof ShellNavigator)) return false;
        ((ShellNavigator) current).register(target);
        return true;
    }

    public static boolean unregister(RouteTarget target) {
        Navigator current = Navigator.current();
        return current instanceof ShellNavigator && ((ShellNavigator) current).unregister(target);
    }
}
