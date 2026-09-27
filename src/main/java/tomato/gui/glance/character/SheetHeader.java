package tomato.gui.glance.character;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.gui.kit.*;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * The sheet header's identity (spec §6.2): 54 px skin sprite, name, class · level · fame, and chips for maxed (hidden while
 * unknown), playing now, seasonal, marked dead and "Played <ago>" (the last time in game; "Seen <ago>" only for a character never
 * played, as the gallery's cards). That time is the accessible description, not part of the name, so assistive technology is
 * not re-announced as time passes. EDT only.
 */
final class SheetHeader extends JPanel {
    private final JLabel sprite = named(new JLabel(), "character-sheet-sprite");
    private final KitText name = named(new KitText("", Type.title(), Tokens.Role.TEXT), "character-sheet-name");
    private final KitText meta = named(KitText.caption(""), "character-sheet-meta");
    private final Chip maxed = named(new Chip("", Tokens.Tone.WARN), "character-sheet-maxed");
    private final Chip playing = named(new Chip("Playing now", Tokens.Tone.GOOD), "character-sheet-playing");
    private final Chip seasonal = named(new Chip("Seasonal", Tokens.Tone.INFO), "character-sheet-seasonal");
    private final Chip dead = named(new Chip("Marked dead", Tokens.Tone.BAD), "character-sheet-dead");
    private final Chip seen = named(new Chip("", Tokens.Tone.NEUTRAL), "character-sheet-seen");
    private SheetModel.Identity shown;
    private String shownSeen = "";

    SheetHeader() {
        super(new BorderLayout(Tokens.M, 0));
        setOpaque(false);
        setName("character-sheet-identity");
        add(sprite, BorderLayout.WEST);
        add(KitLayouts.stack(Tokens.XS, name, meta, row(maxed, playing, seasonal, dead, seen)), BorderLayout.CENTER);
        clearIdentity();
    }

    /** Called for every model and once a second; an unchanged identity re-reads only its relative "Played" text. */
    void apply(SheetModel.Identity identity) {
        String seenText = seen(identity);
        if (identity != null && identity.equals(shown) && seenText.equals(shownSeen)) return;
        shown = identity;
        shownSeen = seenText;
        if (identity == null) { clearIdentity(); return; }
        String kind = identity.className();
        sprite.setIcon(Sprites.sprite(identity.skin() != null && identity.skin() > 0 ? identity.skin() : identity.classId(), 54));
        sprite.setToolTipText(kind);
        name.setText(identity.name());
        meta.setText(kind + (identity.level() == null ? "" : " · Level " + identity.level()) + " · Fame " + identity.fame().display());
        meta.setToolTipText(identity.fame().tooltip());
        maxed.setVisible(identity.maxed() >= 0); // -1 is unknown: never shown as 0/8
        if (identity.maxed() >= 0) {
            maxed.setText(identity.maxed() + "/8 maxed");
            maxed.setTone(identity.maxed() >= 8 ? Tokens.Tone.GOOD : Tokens.Tone.WARN);
        }
        playing.setVisible(identity.playing());
        seasonal.setVisible(Boolean.TRUE.equals(identity.seasonal()));
        dead.setVisible(identity.dead());
        seen.setText(seenText);
        seen.setVisible(!seenText.isEmpty());
        getAccessibleContext().setAccessibleName(identity.name() + ", " + kind + (identity.level() == null ? "" : " level " + identity.level())
            + (identity.maxed() >= 0 ? ", " + identity.maxed() + " of 8 maxed" : "") + (identity.playing() ? ", playing now" : ""));
        getAccessibleContext().setAccessibleDescription(seenText.isEmpty() ? null : seenText);
        revalidate();
        repaint();
    }

    /** "Played 2 h ago" from the last time in game, "Seen …" from the last capture only when never played; "" while in game. */
    private static String seen(SheetModel.Identity identity) {
        if (identity == null || identity.playing()) return "";
        if (identity.lastPlayed() > 0) return "Played " + KitFormat.relative(identity.lastPlayed());
        return identity.lastSeen() > 0 ? "Seen " + KitFormat.relative(identity.lastSeen()) : "";
    }

    private void clearIdentity() {
        sprite.setIcon(null);
        name.setText("");
        meta.setText("");
        for (Chip chip : new Chip[]{maxed, playing, seasonal, dead, seen}) chip.setVisible(false);
        // Otherwise assistive technology keeps announcing the previous character while the next one loads.
        getAccessibleContext().setAccessibleName(null);
        getAccessibleContext().setAccessibleDescription(null);
    }
}
