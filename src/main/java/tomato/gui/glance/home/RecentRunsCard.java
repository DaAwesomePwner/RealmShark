package tomato.gui.glance.home;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.history.link.VisitRef;

/**
 * The last five dungeon runs, newest first (spec §6.1): portal, dungeon, outcome badge, time ago, your DPS only when a
 * combat recording is linked to that exact visit, and up to eight loot sprites. Each row opens its exact run. A failed
 * re-read keeps the last rows under a warn banner saying when they were read and why the new read failed.
 */
final class RecentRunsCard extends HomeCard {
    static final int LOOT = 8;
    private static final String EVIDENCE = "The last five dungeon runs in saved history, newest first. Loot shows only drops recorded"
        + " inside that exact run. Your DPS appears only when a combat recording is linked to that exact run; runs are never matched"
        + " by dungeon name or time.";
    private final RunRow[] rows = new RunRow[HomeArchive.RECENT];
    private final HomeViews.Reason note = new HomeViews.Reason("home-runs-note");
    private final EmptyState empty = HomeViews.named(new EmptyState("No runs yet",
        "Finish a dungeon with capture on and it shows up here with its loot.", null), "home-runs-empty");
    private final JComponent content;
    private HomeModel.Runs shown;
    private String shownTimes = "";

    RecentRunsCard(Consumer<VisitRef> open, DisplayModeModel mode) {
        super(mode, "home-recent-runs", EVIDENCE);
        title("Recent runs");
        for (int i = 0; i < rows.length; i++) rows[i] = new RunRow(i, open);
        note.setVisible(false);
        // The stale banner sits above the rows and takes no space (nor gap) while hidden.
        content = HomeViews.named(HomeViews.stack(0, HomeViews.beside(HomeViews.stack(Tokens.XS, rows), note, BorderLayout.NORTH, Tokens.XS)),
            "home-runs-content");
        status(HomeViews.LOADING, "Recent runs: loading");
    }

    /** EDT only; rows are prebuilt and updated in place, and a section that shows exactly what is shown is skipped (S9). */
    void apply(HomeModel.Runs runs, long now) {
        String times = times(runs, now);
        if (runs != null && runs.equals(shown) && times.equals(shownTimes)) return;
        shown = runs;
        shownTimes = times;
        if (runs == null || runs.state() == HomeModel.State.LOADING) { status(HomeViews.LOADING, "Recent runs: loading"); return; }
        if (runs.state() == HomeModel.State.UNAVAILABLE) {
            header().setCount(null);
            unavailable(text(runs.reason(), "Saved history is not available."), "Recent runs: unavailable");
            return;
        }
        List<HomeArchive.RecentRun> list = runs.rows() == null ? List.of() : runs.rows();
        int shownRows = Math.min(rows.length, list.size());
        header().setCount(shownRows == 0 ? null : String.valueOf(shownRows));
        if (runs.state() == HomeModel.State.EMPTY || shownRows == 0) {
            body(empty);
            getAccessibleContext().setAccessibleName("Recent runs: none yet");
            getAccessibleContext().setAccessibleDescription(null);
            return;
        }
        for (int i = 0; i < rows.length; i++) {
            rows[i].setVisible(i < shownRows);
            if (i < shownRows) rows[i].set(list.get(i), now);
        }
        boolean stale = runs.state() == HomeModel.State.STALE;
        String reason = stale ? text(runs.reason(), "Showing the last successful read of saved history.") : "";
        note.setText(reason, true);
        note.setVisible(stale);
        getAccessibleContext().setAccessibleName("Recent runs: " + shownRows + (shownRows == 1 ? " run" : " runs") + (stale ? ", last successful read" : ""));
        getAccessibleContext().setAccessibleDescription(stale ? reason : null);
        body(content);
    }

    /** The rows' relative times, so a re-apply is skipped only while none of them would change. */
    private static String times(HomeModel.Runs runs, long now) {
        if (runs == null || runs.rows() == null) return "";
        StringBuilder text = new StringBuilder();
        for (HomeArchive.RecentRun run : runs.rows()) text.append(when(run, now)).append('|');
        return text.toString();
    }

    private static String when(HomeArchive.RecentRun run, long now) { return HomeViews.ago(run.ended() != null ? run.ended() : run.started(), now); }

    private static String text(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }

    /** One run. Focusable; click (including on its sprites), Enter or Space opens its exact VisitRef. */
    private static final class RunRow extends JPanel {
        private final JLabel portal = new JLabel();
        private final HomeViews.Text map = HomeViews.emphasis(""), when = HomeViews.caption(""), dps = HomeViews.caption("");
        private final Chip outcome = new Chip("", Tokens.Tone.NEUTRAL);
        private final ItemSlot[] loot = new ItemSlot[LOOT];
        private final JPanel strip = HomeViews.clear(new FlowLayout(FlowLayout.LEADING, 2, 0));
        private HomeArchive.RecentRun run;
        private boolean hovered;

        RunRow(int index, Consumer<VisitRef> open) {
            super(new BorderLayout(Tokens.S, 0));
            String id = "home-run-" + index;
            setName(id);
            setOpaque(false);
            setFocusable(true);
            setBorder(new EmptyBorder(Tokens.XS, Tokens.S, Tokens.XS, Tokens.S));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            map.setName(id + "-map"); outcome.setName(id + "-outcome"); when.setName(id + "-when"); dps.setName(id + "-dps");
            for (int i = 0; i < LOOT; i++) strip.add(loot[i] = HomeViews.named(new ItemSlot(20), id + "-loot-" + i));
            add(portal, BorderLayout.WEST);
            add(HomeViews.stack(2, HomeViews.wrap(map, outcome, when, dps), strip), BorderLayout.CENTER);
            Runnable activate = () -> { if (run != null) open.accept(run.visit()); };
            // Children with tooltips receive their own mouse events, so every descendant forwards clicks (as Card does).
            listen(this, new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) activate.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover(true); }
                @Override public void mouseExited(MouseEvent e) { hover(contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), RunRow.this))); }
            });
            for (KeyStroke key : new KeyStroke[] {KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)})
                getInputMap(WHEN_FOCUSED).put(key, "open-run");
            getActionMap().put("open-run", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { activate.run(); }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }

        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJPanel() {
                @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PUSH_BUTTON; }
            };
            return accessibleContext;
        }

        void set(HomeArchive.RecentRun value, long now) {
            run = value;
            String name = text(value.map(), "Unknown dungeon"), result = text(value.outcome(), "Unknown outcome");
            portal.setIcon(Sprites.sprite(HomeViews.portalId(value.map()), 24));
            map.setText(name);
            outcome.setText(result.startsWith("Left") ? "Left" : result);
            outcome.setTone("Completed".equals(result) ? Tokens.Tone.GOOD : result.startsWith("In progress") ? Tokens.Tone.ACCENT : Tokens.Tone.NEUTRAL);
            outcome.setToolTipText(result);
            when.setText(when(value, now));
            boolean linked = value.localDps() != null; // only through an exact VisitRef link (HomeArchive)
            dps.setText(linked ? "DPS " + KitFormat.compact(value.localDps()) : "");
            dps.setVisible(linked);
            dps.setToolTipText(linked ? "Your DPS from the combat recording linked to this exact run" : null);
            List<Integer> ids = value.lootIds() == null ? List.of() : value.lootIds();
            int shown = 0;
            for (int i = 0; i < LOOT; i++) {
                Integer item = i < ids.size() ? ids.get(i) : null;
                boolean present = item != null && item > 0;
                loot[i].setVisible(present);
                if (present) {
                    loot[i].setItem(item, HomeViews.tier(item));
                    shown++;
                }
            }
            strip.setVisible(shown > 0);
            // "12 min ago" ticks, so it is the description; the name only changes with the run itself.
            getAccessibleContext().setAccessibleName(name + ", " + result + (linked ? ", " + dps.getText() : "")
                + ", " + (shown == 0 ? "no linked loot" : shown == 1 ? "1 loot item" : shown + " loot items") + ". Open run");
            getAccessibleContext().setAccessibleDescription(when.getText());
        }

        private void hover(boolean value) {
            if (hovered == value) return;
            hovered = value;
            repaint();
        }

        private static void listen(Component component, MouseListener mouse) {
            component.addMouseListener(mouse);
            if (component instanceof Container) for (Component child : ((Container) component).getComponents()) listen(child, mouse);
        }

        @Override protected void paintComponent(Graphics graphics) {
            boolean focused = isFocusOwner();
            if (!hovered && !focused) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (hovered) {
                g.setColor(Tokens.color(Tokens.Role.ACCENT_WASH));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
            if (focused) {
                g.setColor(Tokens.color(Tokens.Role.ACCENT));
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
            g.dispose();
        }
    }
}
