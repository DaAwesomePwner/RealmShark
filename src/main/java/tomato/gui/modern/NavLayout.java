package tomato.gui.modern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import util.PropertiesManager;

/**
 * The user's sidebar arrangement over the fixed destinations: the core order (including pinned
 * Advanced entries), hidden entries and whether the Advanced group is open. Saved values are
 * comma-separated NavEntry IDs; IDs this version does not know (including {@code my-info} and
 * {@code dps-logger}, the Build and DPS Logger pages, and {@code statistics}, all removed in P6a) are
 * ignored and dropped on the next write. The one exception is {@code dps-logger}:
 * still read as a raw string and still saved beside a hidden {@code runs} (see {@code savedHidden()}).
 * Settings is never hidden, and at least one core entry always stays visible. Use on the EDT.
 */
public final class NavLayout {
    public static final String ORDER_KEY = "ui.nav.order", HIDDEN_KEY = "ui.nav.hidden",
        PINNED_KEY = "ui.nav.pinned", ADVANCED_KEY = "ui.nav.advanced";
    private static final String HOME = "home", RUNS = "runs", DPS_LOGGER = "dps-logger";

    private final BiConsumer<String, String> write;
    private final List<String> order = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>(), pinned = new LinkedHashSet<>();
    private boolean advancedOpen;

    /** Reads and saves the application preferences. */
    public NavLayout() { this(PropertiesManager::getProperty, PropertiesManager::setProperties); }

    public NavLayout(Function<String, String> read, BiConsumer<String, String> write) {
        this.write = Objects.requireNonNull(write, "write");
        for (String id : ids(read.apply(PINNED_KEY))) if (group(id) == NavEntry.Group.ADVANCED) pinned.add(id);
        for (String id : ids(read.apply(ORDER_KEY))) if (inCore(id) && !order.contains(id)) order.add(id);
        // Home arrived after orders were saved, so it leads a saved order that lacks it. Only memory changes here;
        // the next move or pin writes the whole order, so this happens once.
        if (!order.isEmpty() && !order.contains(HOME)) order.add(0, HOME);
        List<String> savedHidden = ids(read.apply(HIDDEN_KEY));
        for (String id : savedHidden) {
            NavEntry.Group group = group(id);
            if (group == NavEntry.Group.CORE || group == NavEntry.Group.ADVANCED) hidden.add(id);
        }
        // P5b moved the live meter from DPS Logger into Runs & DPS (P6a then removed the DPS Logger page; its ID is read here as
        // a raw string). Someone who hid Runs but kept DPS Logger would lose the meter's row, so Runs shows again. Memory only,
        // like Home above: the next save writes it, and a hidden Runs is always saved with dps-logger beside it, so this happens
        // once and never undoes a later choice.
        if (savedHidden.contains(RUNS) && !savedHidden.contains(DPS_LOGGER)) hidden.remove(RUNS);
        advancedOpen = "true".equals(read.apply(ADVANCED_KEY));
        // A hand-edited file may hide every core entry; the sidebar and the landing page still need one.
        if (core().isEmpty()) hidden.remove(coreOrder().get(0).id());
    }

    /** Core entries in the user's order, including pinned Advanced entries and hidden ones; new ones append. */
    public List<NavEntry> coreOrder() {
        List<NavEntry> result = new ArrayList<>();
        for (String id : order) result.add(NavEntry.forId(id));
        for (NavEntry entry : NavEntry.defaults()) if (inCore(entry.id()) && !order.contains(entry.id())) result.add(entry);
        return result;
    }

    /** The core entries the sidebar lists. */
    public List<NavEntry> core() { return shown(coreOrder()); }

    /** Advanced entries that are not pinned, in the default order, including hidden ones. */
    public List<NavEntry> advancedOrder() {
        List<NavEntry> result = new ArrayList<>();
        for (NavEntry entry : NavEntry.defaults())
            if (entry.group() == NavEntry.Group.ADVANCED && !pinned.contains(entry.id())) result.add(entry);
        return result;
    }

    /** The Advanced entries the group lists when open. */
    public List<NavEntry> advanced() { return shown(advancedOrder()); }

    /** Hidden entries in the default order, for "Show hidden". */
    public List<NavEntry> hidden() {
        List<NavEntry> result = new ArrayList<>();
        for (NavEntry entry : NavEntry.defaults()) if (hidden.contains(entry.id())) result.add(entry);
        return result;
    }

    public NavEntry settings() {
        for (NavEntry entry : NavEntry.defaults()) if (entry.group() == NavEntry.Group.SETTINGS) return entry;
        throw new IllegalStateException("No Settings destination");
    }

    /** Where the app opens: the first visible core entry in the user's order. */
    public NavEntry landing() {
        List<NavEntry> core = core();
        return core.isEmpty() ? settings() : core.get(0);
    }

    /** True for core entries and pinned Advanced entries. */
    public boolean inCore(String id) {
        NavEntry.Group group = group(id);
        return group == NavEntry.Group.CORE || (group == NavEntry.Group.ADVANCED && pinned.contains(id));
    }

    public boolean isHidden(String id) { return hidden.contains(id); }
    public boolean isPinned(String id) { return pinned.contains(id); }
    public boolean advancedOpen() { return advancedOpen; }

    public void setAdvancedOpen(boolean open) {
        advancedOpen = open;
        write.accept(ADVANCED_KEY, Boolean.toString(open));
    }

    /** Whether a visible core entry can move by {@code delta} places among the visible core entries. */
    public boolean canMove(String id, int delta) {
        List<NavEntry> visible = core();
        int from = indexOf(visible, id), to = from + delta;
        return from >= 0 && delta != 0 && to >= 0 && to < visible.size();
    }

    public boolean move(String id, int delta) {
        if (!canMove(id, delta)) return false;
        List<NavEntry> visible = core();
        String neighbor = visible.get(indexOf(visible, id) + delta).id();
        List<String> all = idsOf(coreOrder());
        all.remove(id);
        int at = all.indexOf(neighbor);
        all.add(delta > 0 ? at + 1 : at, id);
        order.clear();
        order.addAll(all);
        write.accept(ORDER_KEY, String.join(",", order));
        return true;
    }

    /**
     * Moves a visible core entry (pinned Advanced entries included) to {@code index}, its final place among the visible core
     * entries ({@link #core()}): the sidebar's drop. It reuses {@link #move}, so hidden rows keep their relative spot and a change
     * is one ORDER write. Settings, unpinned Advanced, hidden (even the current page the sidebar still shows) and unknown entries,
     * an index out of range and a no-op return false and write nothing.
     */
    public boolean moveTo(String id, int index) {
        List<NavEntry> visible = core();
        int from = indexOf(visible, id);
        return from >= 0 && index >= 0 && index < visible.size() && index != from && move(id, index - from);
    }

    /** Only core and Advanced rows hide: Settings is always listed and the last visible core entry stays. */
    public boolean canHide(String id) {
        NavEntry.Group group = group(id);
        if ((group != NavEntry.Group.CORE && group != NavEntry.Group.ADVANCED) || hidden.contains(id)) return false;
        return !inCore(id) || core().size() > 1;
    }

    public boolean hide(String id) {
        if (!canHide(id)) return false;
        hidden.add(id);
        write.accept(HIDDEN_KEY, savedHidden());
        return true;
    }

    public boolean show(String id) {
        if (!hidden.remove(id)) return false;
        write.accept(HIDDEN_KEY, savedHidden());
        return true;
    }

    /** Moves an Advanced entry to the end of the core list. */
    public boolean pin(String id) {
        if (group(id) != NavEntry.Group.ADVANCED || pinned.contains(id)) return false;
        List<String> all = idsOf(coreOrder());
        pinned.add(id);
        all.add(id);
        order.clear();
        order.addAll(all);
        write.accept(PINNED_KEY, String.join(",", pinned));
        write.accept(ORDER_KEY, String.join(",", order));
        return true;
    }

    public boolean canUnpin(String id) {
        return pinned.contains(id) && (hidden.contains(id) || core().size() > 1);
    }

    public boolean unpin(String id) {
        if (!canUnpin(id)) return false;
        pinned.remove(id);
        order.remove(id);
        write.accept(PINNED_KEY, String.join(",", pinned));
        write.accept(ORDER_KEY, String.join(",", order));
        return true;
    }

    /** Back to the default order and grouping: nothing hidden or pinned, Advanced collapsed. */
    public void reset() {
        order.clear();
        hidden.clear();
        pinned.clear();
        advancedOpen = false;
        for (String key : new String[] {ORDER_KEY, HIDDEN_KEY, PINNED_KEY, ADVANCED_KEY}) write.accept(key, "");
    }

    /**
     * The hidden IDs as saved. Hiding Runs & DPS also hides the live meter, so {@code dps-logger} is saved beside a hidden
     * {@code runs}: the one-time un-hide in the constructor then leaves it hidden, and an older version hides both pages. P6a
     * removed the DPS Logger page, but the ID is still written so that a P5b build reading these preferences does not un-hide
     * Runs & DPS.
     */
    private String savedHidden() {
        List<String> saved = new ArrayList<>(hidden);
        if (hidden.contains(RUNS)) saved.add(DPS_LOGGER);
        return String.join(",", saved);
    }

    private List<NavEntry> shown(List<NavEntry> entries) {
        List<NavEntry> result = new ArrayList<>();
        for (NavEntry entry : entries) if (!hidden.contains(entry.id())) result.add(entry);
        return result;
    }

    private static NavEntry.Group group(String id) {
        NavEntry entry = NavEntry.forId(id);
        return entry == null ? null : entry.group();
    }

    private static int indexOf(List<NavEntry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) return i;
        return -1;
    }

    private static List<String> idsOf(List<NavEntry> entries) {
        List<String> ids = new ArrayList<>();
        for (NavEntry entry : entries) ids.add(entry.id());
        return ids;
    }

    private static List<String> ids(String saved) {
        List<String> result = new ArrayList<>();
        if (saved == null) return result;
        for (String id : saved.split(",")) if (!id.trim().isEmpty()) result.add(id.trim());
        return result;
    }
}
