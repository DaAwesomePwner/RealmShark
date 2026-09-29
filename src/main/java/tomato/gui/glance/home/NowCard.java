package tomato.gui.glance.home;

import java.awt.*;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.dps.MeterDpsGUI;
import tomato.gui.dps.MeterSummary;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.KitText;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * What is happening now (spec §6.1): portal, area and elapsed time; in a run, the top three live meter rows with your row
 * highlighted, "You're #2 of 8" and the last key pop as its own fact. Outside a run: area and capture state only. The
 * whole card opens the Live meter tab of the runs page (Runs & DPS).
 */
final class NowCard extends HomeCard {
    static final int SHOWN = 3;
    private final JLabel portal = HomeViews.named(new JLabel(), "home-now-portal");
    private final KitText area = HomeViews.named(HomeViews.emphasis(""), "home-now-area");
    private final KitText elapsed = HomeViews.named(HomeViews.caption(""), "home-now-elapsed");
    private final KitText rank = HomeViews.named(HomeViews.caption(""), "home-now-rank");
    private final KitText pop = HomeViews.named(HomeViews.body(""), "home-now-pop");
    private final Chip capture = HomeViews.named(new Chip("Capture off", Tokens.Tone.NEUTRAL), "home-now-capture");
    private final MeterRow[] rows = new MeterRow[SHOWN];
    private final EmptyState empty = HomeViews.named(new EmptyState("Nothing live yet",
        "Start capture and enter the game. Your area, the live meter and the last key pop show here.", null), "home-now-empty");
    private final JComponent content;
    private HomeModel.Now shown;
    private String shownTimes = "";

    NowCard(Runnable openMeter, DisplayModeModel mode) {
        super(mode, "home-now", "Nothing captured yet.");
        title("Now");
        JComponent[] lines = new JComponent[SHOWN + 3];
        lines[0] = HomeViews.wrap(portal, area, elapsed, capture);
        for (int i = 0; i < SHOWN; i++) lines[i + 1] = rows[i] = new MeterRow(i);
        lines[SHOWN + 1] = rank;
        lines[SHOWN + 2] = pop;
        content = HomeViews.named(HomeViews.stack(Tokens.XS, lines), "home-now-content");
        onOpen("Open live meter", openMeter);
        status(HomeViews.LOADING, "Now: loading");
    }

    /** EDT only; `time` is the page clock used for the elapsed and "ago" text. Skips a section that shows exactly what is shown. */
    void apply(HomeModel.Now now, long time) {
        String times = elapsedText(now, time) + "|" + popText(now, time);
        if (now != null && now.equals(shown) && times.equals(shownTimes)) return;
        shown = now;
        shownTimes = times;
        if (now == null || now.state() == HomeModel.State.LOADING) { status(HomeViews.LOADING, "Now: loading"); return; }
        if (now.state() == HomeModel.State.UNAVAILABLE) { unavailable("Live capture data is unavailable right now.", "Now: unavailable"); return; }
        if (now.state() == HomeModel.State.EMPTY) {
            explain("Nothing captured yet: there is no current area, live meter or key pop.");
            body(empty);
            getAccessibleContext().setAccessibleName("Now: nothing live yet. Open live meter");
            getAccessibleContext().setAccessibleDescription(null);
            return;
        }
        String where = now.area() == null || now.area().isEmpty() ? "Unknown area" : now.area();
        portal.setIcon(Sprites.sprite(HomeViews.portalId(now.area()), 32));
        area.setText(where);
        area.role(now.state() == HomeModel.State.STALE ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT);
        String timed = elapsedText(now, time);
        elapsed.setText(timed);
        elapsed.setVisible(!timed.isEmpty());
        capture.setText(now.capturing() ? "Capturing" : "Capture off");
        capture.setTone(now.capturing() ? Tokens.Tone.GOOD : Tokens.Tone.NEUTRAL);
        List<MeterSummary.Row> top = now.top() == null ? List.of() : now.top();
        // Outside a run the builder leaves the meter empty; the card then shows only the area and capture state (spec §6.1).
        boolean run = !top.isEmpty();
        for (int i = 0; i < SHOWN; i++) {
            rows[i].setVisible(run && i < top.size());
            if (run && i < top.size()) rows[i].set(i + 1, top.get(i));
        }
        rank.setVisible(run);
        rank.setText(!run ? "" : now.localRank() > 0 ? "You're #" + now.localRank() + " of " + now.players()
            : "You're not on the meter yet · " + now.players() + (now.players() == 1 ? " player" : " players"));
        String popped = popText(now, time);
        pop.setVisible(!popped.isEmpty());
        pop.setText(popped);
        explain("Area and elapsed time from the current visit" + (timed.isEmpty() ? "" : ", entered " + DisplayFormat.formatTimestamp(now.startedAt()))
            + (run ? ". Meter: the top " + Math.min(SHOWN, top.size()) + " of " + now.players() + " players by damage in the live DPS"
                + " snapshot, shown only when its encounter belongs to this exact visit"
                : ". Outside a run only the area and capture state are shown")
            + (popped.isEmpty() ? "." : ". The last key pop comes from key-pop history as its own fact; it is not tied to this run."));
        // Elapsed time and the pop's age tick: they are the description, so the name is not re-announced every few seconds.
        getAccessibleContext().setAccessibleName("Now: " + where + ", " + capture.getText().toLowerCase(Locale.ROOT)
            + (run ? ", " + rank.getText() : "") + ". Open live meter");
        String description = (timed.isEmpty() ? "" : timed + " in this area") + (popped.isEmpty() ? "" : (timed.isEmpty() ? "" : ". ") + popped);
        getAccessibleContext().setAccessibleDescription(description.isEmpty() ? null : description);
        body(content);
    }

    /**
     * EDT only: HomePage's 1 s tick while it shows a live run. Only the elapsed text changes, and only when it differs; the
     * pop's age and the spoken description keep the 10 s age tick, so a screen reader is not interrupted every second.
     */
    void tick(long time) {
        if (shown == null || shown.state() != HomeModel.State.LIVE) return;
        String timed = elapsedText(shown, time);
        if (timed.isEmpty() || timed.equals(elapsed.getText())) return;
        elapsed.setText(timed);
        elapsed.setVisible(true);
    }

    private static String elapsedText(HomeModel.Now now, long time) {
        boolean shown = now != null && (now.state() == HomeModel.State.LIVE || now.state() == HomeModel.State.STALE)
            && now.startedAt() != null && now.startedAt() > 0 && time >= now.startedAt();
        return shown ? KitFormat.duration(time - now.startedAt()) : "";
    }

    /** The last key pop, only in a run (spec §6.1); empty otherwise. */
    private static String popText(HomeModel.Now now, long time) {
        KeypopGUI.LastPop last = now == null || now.top() == null || now.top().isEmpty()
            || now.state() == HomeModel.State.EMPTY || now.state() == HomeModel.State.UNAVAILABLE ? null : now.lastPop();
        if (last == null) return "";
        String who = last.player() == null || last.player().isEmpty() ? "Someone" : last.player();
        String where = last.dungeon() == null || last.dungeon().isEmpty() ? "a dungeon" : last.dungeon();
        return who + " popped " + where + (last.time() == null ? "" : " · " + HomeViews.ago(last.time().toEpochMilli(), time));
    }

    /** One live meter row: place, class color, name and DPS. Your row is tinted and says "(you)", so color is never the only cue. */
    private static final class MeterRow extends JPanel {
        private final Swatch swatch = new Swatch();
        private final KitText place = HomeViews.caption(""), who = HomeViews.body(""), dps = HomeViews.body("");
        private boolean local;

        MeterRow(int index) {
            super(new BorderLayout(Tokens.S, 0));
            setOpaque(false);
            setName("home-now-row-" + index);
            who.setName(getName() + "-name");
            dps.setName(getName() + "-dps");
            setBorder(new EmptyBorder(2, Tokens.XS, 2, Tokens.XS));
            add(HomeViews.clear(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0), place, swatch, who), BorderLayout.CENTER);
            add(dps, BorderLayout.EAST);
        }

        void set(int number, MeterSummary.Row row) {
            local = row.local();
            String name = row.name() == null || row.name().isEmpty() ? "Unknown player" : row.name();
            String kind = row.className() == null || row.className().isEmpty() ? "Unknown class" : row.className();
            place.setText(number + ".");
            swatch.color = MeterDpsGUI.classColor(row.classId());
            swatch.setToolTipText(kind);
            who.setText(local ? name + " (you)" : name);
            who.role(local ? Tokens.Role.ACCENT_TEXT : Tokens.Role.TEXT);
            dps.setText(KitFormat.compact(row.dps()) + " DPS");
            dps.setToolTipText(DisplayFormat.formatInteger(row.damage()) + " damage");
            getAccessibleContext().setAccessibleName(number + ". " + name + ", " + kind + ", " + dps.getText() + (local ? ", you" : ""));
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (!local) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.color(Tokens.Role.ACCENT_WASH));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            g.dispose();
        }
    }

    /** The live meter's class color (game meaning, not status). */
    private static final class Swatch extends JComponent {
        private Color color = Color.GRAY;

        @Override public Dimension getPreferredSize() {
            int side = Math.max(8, Math.round(ContentStyle.body().getSize2D() * .7f));
            return new Dimension(side, side);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 3, 3);
            g.dispose();
        }
    }
}
