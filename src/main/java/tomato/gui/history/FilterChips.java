package tomato.gui.history;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;
import tomato.gui.kit.FilterBar;

/** FilterBar rebuilds its whole row when chips or slots change; these helpers keep that invisible to the keyboard. */
public final class FilterChips {
    private static final String LABELS = "filterChips.labels";
    private FilterChips() {}

    /**
     * Replaces the chips when their labels change (always when force is true, e.g. a new archive render whose remove
     * actions carry a new binding). Live panels pass force=false; their remove actions read current state when clicked.
     */
    public static void update(FilterBar bar, List<FilterBar.ActiveFilter> active, Runnable clear, boolean force) {
        List<String> labels = new ArrayList<>();
        for (FilterBar.ActiveFilter filter : active) labels.add(filter.label);
        if (!force && labels.equals(bar.getClientProperty(LABELS))) return;
        bar.putClientProperty(LABELS, labels);
        keepingFocus(() -> bar.setActive(active, clear));
    }

    /** Runs a change that removes and re-adds controls, then returns keyboard focus to the control that had it. */
    public static void keepingFocus(Runnable change) {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        change.run();
        if (focused != null && focused.isShowing() && focused.isEnabled() && focused.isFocusable()) focused.requestFocusInWindow();
    }
}
