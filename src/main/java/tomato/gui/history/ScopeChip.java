package tomato.gui.history;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import tomato.gui.kit.KitButton;
import tomato.gui.modern.LineIcon;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;

/**
 * The archive filter row's one "Scope: … ▾" chip (spec §5.6): live view or saved history, and which saved sessions. It replaces
 * the Browse saved / Current live view button, the session combo and the scope ⟳ icon. {@link ArchiveWorkspace} owns the state:
 * {@link #show} only reflects it (silently, firing nothing) and a pick reports through {@link Actions}. The menu is built eagerly
 * and kept in step, so tests can {@code doClick} its items without showing it. EDT only.
 */
public final class ScopeChip extends KitButton {
    /** What a pick asks the workspace to do; {@link #show} never calls these. */
    public interface Actions {
        /** The live view of this app run. */
        void live();
        /** Saved history of {@code scope}: {@link ArchiveQuery#CURRENT}, {@link SessionStore#ALL} or a session id. */
        void saved(String scope);
        /** The History library dialog. */
        void library();
        /** Read the saved-session list again. */
        void refreshList();
    }

    /** One readable saved session: its id, the menu text (its full label) and the chip's short text. */
    public static final class SessionChoice {
        public final String id, label, shortLabel;
        public SessionChoice(String id, String label) { this(id, label, label); }
        public SessionChoice(String id, String label, String shortLabel) {
            this.id = Objects.requireNonNull(id, "id");
            this.label = Objects.requireNonNull(label, "label");
            this.shortLabel = Objects.requireNonNull(shortLabel, "shortLabel");
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof SessionChoice)) return false;
            SessionChoice that = (SessionChoice) other;
            return id.equals(that.id) && label.equals(that.label) && shortLabel.equals(that.shortLabel);
        }
        @Override public int hashCode() { return Objects.hash(id, label, shortLabel); }
    }

    /** A session's short text is cut at this many characters, with "…"; the tooltip and the menu keep it whole. */
    static final int LABEL_LIMIT = 18;
    /** The client property holding a session item's scope id. */
    public static final String SCOPE = "scope";

    private final String module;
    private final Actions actions;
    private final JPopupMenu menu = new JPopupMenu();
    private final ButtonGroup group = new ButtonGroup();
    private final JRadioButtonMenuItem live, current, all;
    /** The session radios after "All sessions", including a pending one for an unlisted scope. */
    private final List<JRadioButtonMenuItem> sessions = new ArrayList<>();
    private List<SessionChoice> recent = Collections.emptyList();
    private String pendingScope;
    private boolean archive;
    /** Whether the chip had focus when its menu opened: a pick then returns focus to it, wherever the workspace moved it. */
    private boolean refocus;
    private String scope = ArchiveQuery.CURRENT, currentId = "";

    public ScopeChip(String module, boolean savedOnly, Actions actions) {
        super(Variant.SECONDARY, "Scope: Live", new LineIcon(LineIcon.CHEVRON_DOWN, 14));
        this.module = Objects.requireNonNull(module, "module");
        this.actions = Objects.requireNonNull(actions, "actions");
        setName(module + "-scope");
        setHorizontalTextPosition(SwingConstants.LEADING);
        putClientProperty("html.disable", Boolean.TRUE);   // a session label is user-editable
        getAccessibleContext().setAccessibleName("Scope");
        menu.setName(module + "-scope-menu");
        menu.getAccessibleContext().setAccessibleName("Scope");
        live = savedOnly ? null : radio("live", "Live · this app run", actions::live);
        if (live != null) menu.add(live);
        JMenuItem header = new JMenuItem("Saved history");
        header.setEnabled(false);
        header.putClientProperty("html.disable", Boolean.TRUE);
        menu.add(header);
        current = radio("current", "This session", () -> actions.saved(ArchiveQuery.CURRENT));
        all = radio("all", "All sessions", () -> actions.saved(SessionStore.ALL));
        menu.add(current);
        menu.add(all);
        menu.addSeparator();
        menu.add(plain("library", "History library…", actions::library));
        menu.add(plain("refresh", "Refresh session list", actions::refreshList));
        addActionListener(e -> open());
        // Space clicks the button, which opens the menu; Enter, Alt+Down and F4 open it too. A key listener that ignores keys
        // while the menu is open, rather than an InputMap entry: the open menu's Up, Down, Enter and Esc are window-level
        // bindings, which a chip binding for Enter would shadow while the chip still holds focus.
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (menu.isVisible() || !opens(e)) return;
                e.consume();
                open();
            }
        });
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) {
                refocus = false;
                // Esc returns to the chip; a click elsewhere keeps the focus it gave.
                AWTEvent event = EventQueue.getCurrentEvent();
                if (event instanceof KeyEvent && ((KeyEvent) event).getKeyCode() == KeyEvent.VK_ESCAPE)
                    SwingUtilities.invokeLater(() -> { if (isShowing()) requestFocusInWindow(); });
            }
        });
        show(false, ArchiveQuery.CURRENT, "", Collections.emptyList());
    }

    /**
     * Reflects the workspace's state: the label, the tooltip and accessible description (the full wording), the session list
     * and the selected radio. Fires nothing. {@code recent} is the readable saved sessions in the catalog's order.
     */
    public void show(boolean archive, String scope, String currentId, List<SessionChoice> recent) {
        this.archive = archive || live == null;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.currentId = currentId == null ? "" : currentId;
        List<SessionChoice> choices = Collections.unmodifiableList(new ArrayList<>(recent));
        String pending = thisSession() || SessionStore.ALL.equals(scope) || choice(choices, scope) != null ? null : scope;
        if (!choices.equals(this.recent) || !Objects.equals(pending, pendingScope)) rebuildSessions(choices, pending);
        String text, tip;
        SessionChoice listed = choice(this.recent, scope);
        if (!this.archive) { text = "Scope: Live"; tip = "Live view of this app run, not saved history"; }
        else if (thisSession()) { text = "Scope: Saved · this session"; tip = "Saved history of this app run"; }
        else if (SessionStore.ALL.equals(scope)) { text = "Scope: Saved · all sessions"; tip = "Saved history of all sessions"; }
        else if (listed != null) { text = "Scope: Saved · " + cut(listed.shortLabel); tip = "Saved history of " + listed.label; }
        else { text = "Scope: Saved · selected session"; tip = "Selected session · " + scope; }
        setText(text);
        setToolTipText(tip);
        getAccessibleContext().setAccessibleDescription(tip);
        select();
    }

    /** The menu, built eagerly and kept in step with {@link #show}. */
    public JPopupMenu menu() { return menu; }

    /**
     * An item by its name suffix: "live", "current", "all", "session" (the first session), "session:&lt;id&gt;", "library" or
     * "refresh"; null when absent.
     */
    public JMenuItem item(String suffix) {
        String id = null, wanted = suffix;
        if (suffix.startsWith("session:")) { id = suffix.substring("session:".length()); wanted = "session"; }
        String name = module + "-scope-" + wanted;
        for (Component component : menu.getComponents())
            if (component instanceof JMenuItem && name.equals(component.getName())
                    && (id == null || id.equals(((JComponent) component).getClientProperty(SCOPE)))) return (JMenuItem) component;
        return null;
    }

    /** The popup is outside the component tree until shown, so theme changes must reach it here (as OverflowMenu does). */
    @Override public void updateUI() {
        super.updateUI();
        if (menu != null) SwingUtilities.updateComponentTreeUI(menu); // null during KitButton's constructor
    }

    /** A short session text cut at {@link #LABEL_LIMIT} characters with "…". */
    static String cut(String text) {
        return text.length() <= LABEL_LIMIT ? text : text.substring(0, LABEL_LIMIT - 1).trim() + "…";
    }

    private boolean thisSession() { return ArchiveQuery.CURRENT.equals(scope) || (!currentId.isEmpty() && currentId.equals(scope)); }

    private static SessionChoice choice(List<SessionChoice> choices, String scope) {
        for (SessionChoice choice : choices) if (choice.id.equals(scope)) return choice;
        return null;
    }

    private void rebuildSessions(List<SessionChoice> choices, String pending) {
        for (JRadioButtonMenuItem item : sessions) { menu.remove(item); group.remove(item); }
        sessions.clear();
        recent = choices;
        pendingScope = pending;
        int at = menu.getComponentIndex(all) + 1;
        for (SessionChoice choice : choices) sessions.add(session(choice.id, choice.label));
        if (pending != null) sessions.add(session(pending, "Selected session · " + pending));
        for (JRadioButtonMenuItem item : sessions) menu.insert(item, at++);
        menu.revalidate();
    }

    private JRadioButtonMenuItem session(String id, String label) {
        JRadioButtonMenuItem item = radio("session", label, () -> actions.saved(id));
        item.putClientProperty(SCOPE, id);
        return item;
    }

    private JRadioButtonMenuItem radio(String suffix, String label, Runnable action) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label);
        named(item, suffix, action);
        group.add(item);
        return item;
    }

    private JMenuItem plain(String suffix, String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        named(item, suffix, action);
        return item;
    }

    private void named(JMenuItem item, String suffix, Runnable action) {
        item.setName(module + "-scope-" + suffix);
        item.putClientProperty("html.disable", Boolean.TRUE);
        // A pick that the workspace refuses (or that changes nothing) must not leave a stale radio behind. A pick may move the
        // chip into another bar; focus follows it there (after the menu has given focus back to its old owner).
        item.addActionListener(e -> {
            boolean focus = refocus;
            refocus = false;
            action.run();
            select();
            if (focus) SwingUtilities.invokeLater(() -> { if (isShowing() && isEnabled()) requestFocusInWindow(); });
        });
    }

    /** The radio for the shown state. */
    private JRadioButtonMenuItem selected() {
        if (!archive) return live;
        if (thisSession()) return current;
        if (SessionStore.ALL.equals(scope)) return all;
        for (JRadioButtonMenuItem item : sessions) if (scope.equals(item.getClientProperty(SCOPE))) return item;
        return null;
    }

    private void select() {
        JRadioButtonMenuItem item = selected();
        if (item == null) group.clearSelection();
        else if (!item.isSelected()) group.setSelected(item.getModel(), true);
    }

    private static boolean opens(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_F4) return e.getModifiersEx() == 0;
        return (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_KP_DOWN) && e.isAltDown();
    }

    /** Opens the menu under the chip with the selected radio highlighted; Up, Down, Enter and Esc work as in any menu. */
    private void open() {
        if (!isShowing()) return;
        refocus = isFocusOwner();
        menu.show(this, 0, getHeight());
        JRadioButtonMenuItem item = selected();
        if (item != null) MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {menu, item});
    }
}
