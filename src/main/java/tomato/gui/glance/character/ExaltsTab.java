package tomato.gui.glance.character;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Exalts (spec §6.2), for this character's class. Each of the 8 stats shows its tier pips (5), its completions and how
 * many more reach the next tier, the saved live stat bonus for this class, and the dungeon that grants it. The header shows the
 * total completions and the lowest tier. With no saved counts for this class, an empty state replaces the rows; a null section (the
 * sheet is loading) shows neither. EDT only.
 */
final class ExaltsTab extends JPanel {
    private final KitText totals = named(KitText.emphasis(""), "character-exalts-totals");
    private final KitText observed = named(KitText.caption(""), "character-exalts-observed");
    private final KitText bonusSeen = named(KitText.caption(""), "character-exalts-bonus-observed");
    private final PipMeter[] pips = new PipMeter[8];
    private final KitText[] counts = new KitText[8], next = new KitText[8], bonus = new KitText[8], earn = new KitText[8];
    private final JPanel content;
    private final EmptyState empty = named(new EmptyState("No exalt progress for this class yet",
        "Exalt progress arrives when capture reads your character list.", null), "character-exalts-empty");
    private SheetModel.Exalts shown;

    ExaltsTab() {
        super(new BorderLayout());
        setOpaque(false);
        setName("character-exalts");
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(Tokens.XS, 0, Tokens.XS, Tokens.M);
        for (int i = 0; i < 8; i++) {
            pips[i] = named(new PipMeter(5), "character-exalt-pips-" + i);
            pips[i].getAccessibleContext().setAccessibleName(CharacterJournal.STATS[i] + " exalt tier");
            counts[i] = named(KitText.body(""), "character-exalt-count-" + i);
            next[i] = named(KitText.caption(""), "character-exalt-next-" + i);
            bonus[i] = named(new KitText("", Type.caption(), Tokens.Role.ACCENT_TEXT), "character-exalt-bonus-" + i);
            earn[i] = named(KitText.caption(""), "character-exalt-earn-" + i);
            c.gridy = i;
            c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            rows.add(KitText.body(CharacterJournal.STATS[i]), c);
            c.gridx = 1;
            rows.add(pips[i], c);
            c.gridx = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            rows.add(row(counts[i], next[i], bonus[i], earn[i]), c); // wraps at 680 px and font 18
        }
        content = named(KitLayouts.stack(Tokens.S, totals, row(observed, bonusSeen), rows), "character-exalts-content");
        add(KitLayouts.stack(Tokens.M, content, empty), BorderLayout.NORTH);
        apply(null);
    }

    /** EDT. Called for every model and once a second; an unchanged section re-reads only its relative "Changed" and "observed" texts. */
    void apply(SheetModel.Exalts exalts) {
        // seenAt is when this class's counts last changed in the journal (exaltSeenByClass), not when they were last read.
        String seen = exalts == null || exalts.seenAt() <= 0 ? "" : "Changed " + KitFormat.relative(exalts.seenAt());
        String bonusText = exalts == null || exalts.liveBonus() == null ? "" : "Live bonus observed "
            + (exalts.liveObservedAt() > 0 ? KitFormat.relative(exalts.liveObservedAt()) : "at an unknown time");
        if (exalts != null && exalts.equals(shown) && seen.equals(observed.getText()) && bonusText.equals(bonusSeen.getText())) return;
        shown = exalts;
        boolean known = exalts != null && exalts.known();
        content.setVisible(known);
        empty.setVisible(exalts != null && !known); // null: nothing to show yet (loading), never "no progress"
        if (!known) return;
        totals.setText("Total completions " + DisplayFormat.formatInteger(exalts.total()) + " · Lowest tier " + exalts.lowest() + "/5");
        observed.setText(seen);
        observed.setVisible(!seen.isEmpty());
        bonusSeen.setText(bonusText);
        bonusSeen.setVisible(!bonusText.isEmpty());
        List<Integer> live = exalts.liveBonus();
        for (int i = 0; i < 8; i++) {
            int count = exalts.completions().get(i), tier = exalts.tiers().get(i), toNext = exalts.toNext().get(i);
            pips[i].setFilled(tier);
            pips[i].setColor(tier >= 5 ? Tokens.Role.GOOD : Tokens.Role.ACCENT);
            counts[i].setText(DisplayFormat.formatInteger(count) + (count == 1 ? " completion" : " completions"));
            next[i].setText(toNext == 0 ? "Maxed" : toNext + " to next tier");
            next[i].role(toNext == 0 ? Tokens.Role.GOOD : Tokens.Role.TEXT_MUTED);
            // Only this class's saved bonus: never another class's, and never a live snapshot of another character.
            bonus[i].setVisible(live != null);
            bonus[i].setText(live == null ? "" : "+" + live.get(i));
            bonus[i].setToolTipText(live == null ? null : "Live " + CharacterJournal.STATS[i] + " bonus from exaltation, saved for this class");
            String where = exalts.earnIn() == null ? "" : exalts.earnIn().get(i);
            earn[i].setText("Earn in: " + (where.isEmpty() ? DisplayFormat.UNAVAILABLE : where));
            earn[i].setToolTipText(exalts.earnIn() == null ? "Dungeon mapping loads with the selected game assets"
                : where.isEmpty() ? "Not mapped in the selected game assets" : null);
        }
        revalidate();
        repaint();
    }
}
