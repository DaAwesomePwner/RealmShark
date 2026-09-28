package tomato.gui.glance.character;

import java.awt.*;
import java.util.Objects;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import static tomato.gui.glance.character.SheetViews.named;

/**
 * Sheet › Fame (spec §6.2): this character's fame now and its saved fame history, read exactly by account and character id
 * (FameHistory). Three tiles (Fame, Fame / hour, Recorded gain; they wrap to fewer columns before a value or sub-line would be
 * cut), the chart of every session's readings, and two captions for what was left out: readings with no recorded account (older
 * readings; they may be another account's character with the same id) and saved sessions that could not be read. No readings:
 * an empty state inviting play with capture on. Null (a new key loading) shows nothing; a failed read shows a warn banner over
 * whatever is shown. EDT only.
 */
final class FameTab extends JPanel {
    static final String EMPTY_TITLE = "No fame history for this character yet";
    static final String EMPTY_BODY = "Fame history records while you play this character with capture on.";
    static final String HOUR_TIP = "Fame per hour of readings in the newest session with at least 10 minutes of readings";
    private final StatTile fame = new StatTile("Fame"), perHour = new StatTile("Fame / hour"), gained = new StatTile("Recorded gain");
    private final FameChart chart = new FameChart();
    private final EmptyState empty = named(new EmptyState(EMPTY_TITLE, EMPTY_BODY, null), "character-fame-empty");
    private final JTextArea untagged = named(ContentStyle.wrappingText(""), "character-fame-untagged");
    private final Banner unreadable = new Banner("character-fame-unreadable"), failed = new Banner("character-fame-failed");
    private final JPanel content;
    private FameModel shown;
    private String shownFame = "", shownHour = "";
    private boolean applied;

    FameTab() {
        super(new BorderLayout());
        setOpaque(false);
        setName("character-fame");
        fame.setToolTipText("This character's fame: live while it is in game, else as the character journal last saved it");
        perHour.setToolTipText(HOUR_TIP);
        gained.setToolTipText("Fame gained while RealmShark recorded this character");
        unreadable.setTone(Tokens.Tone.WARN);
        failed.setTone(Tokens.Tone.WARN);
        failed.setVisible(false);
        // Three tiles across on a wide sheet, fewer when narrow or when a tile would be narrower than its value or sub-line (never
        // cut at 680 px or font 18).
        JPanel tiles = ContentStyle.responsiveGrid(3, 170, Tokens.S, true);
        tiles.setOpaque(false);
        tiles.add(fame); tiles.add(perHour); tiles.add(gained);
        content = named(KitLayouts.stack(Tokens.M, tiles, chart, empty, untagged, unreadable), "character-fame-content");
        add(KitLayouts.stack(Tokens.M, failed, content), BorderLayout.NORTH);
        apply(null);
    }

    /**
     * EDT. Called for every model and once a second (the presenter's refresh): an equal model is skipped while the Fame tile's
     * "as of …" age and the Fame / hour session's age read the same.
     */
    void apply(FameModel model) {
        String line = fameLine(model), hour = hourLine(model);
        if (applied && Objects.equals(model, shown) && line.equals(shownFame) && hour.equals(shownHour)) return;
        applied = true;
        shown = model;
        shownFame = line;
        shownHour = hour;
        content.setVisible(model != null); // loading: nothing, never an empty state
        if (model != null) {
            fame.setValue(model.current().value(), line.isEmpty() ? null : line);
            FameModel.RateBasis basis = model.perHourBasis();
            perHour.setValue(model.perHour(), hour.isEmpty() ? null : hour);
            perHour.setToolTipText(basis == null ? HOUR_TIP : "Fame per hour of readings in " + basis.fullName()
                + ", the newest session with at least 10 minutes of readings");
            gained.setValue(model.gained(), model.gainedBasis());
            boolean any = !model.sessions().isEmpty();
            chart.setSessions(model.sessions());
            chart.setVisible(any);
            empty.setVisible(!any);
            untagged.setText(untaggedText(model.untagged()));
            untagged.setVisible(model.untagged() > 0);
            unreadable.setText(unreadableText(model.unreadable()));
            unreadable.setVisible(model.unreadable() > 0);
        }
        revalidate();
        repaint();
    }

    /** EDT: a failed read's reason in the warn banner (what is shown stays); null hides it. */
    void failed(String reason) {
        boolean show = reason != null;
        if (show) failed.setText("Fame history could not be read: " + reason + ". It retries automatically while this tab shows.");
        if (failed.isVisible() == show) return;
        failed.setVisible(show);
        revalidate();
        repaint();
    }

    /** "Live", "as of 2 h ago" for a saved (stale) value, or "" (unknown: the reason is the tooltip). */
    private static String fameLine(FameModel model) {
        if (model == null) return "";
        FameModel.Current current = model.current();
        if (current.live()) return "Live";
        if (current.value().state != DisplayValue.State.STALE) return "";
        return "as of " + (current.savedAt() > 0 ? KitFormat.relative(current.savedAt()) : "an unknown time");
    }

    /** The Fame / hour sub-line: "this session", "session 4 days ago" (a week or older: its date), or "" without a rate. */
    static String hourLine(FameModel model) {
        FameModel.RateBasis basis = model == null ? null : model.perHourBasis();
        if (basis == null) return "";
        return basis.current() ? "this session" : "session " + KitFormat.relative(basis.start());
    }

    static String untaggedText(int count) {
        return count == 1 ? "1 older reading has no recorded account and is not shown"
            : count + " older readings have no recorded account and are not shown";
    }

    static String unreadableText(int count) { return count + (count == 1 ? " saved session" : " saved sessions") + " could not be read"; }
}
