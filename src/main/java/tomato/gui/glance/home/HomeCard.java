package tomato.gui.glance.home;

import javax.swing.JComponent;
import tomato.gui.kit.Card;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.ContentStyle;

/**
 * A Home card: explicit loading and unavailable states (spec §7; an unavailable reason is a warn banner inside the card) and
 * Evidence text that is rewritten only when it changes.
 */
abstract class HomeCard extends Card {
    private final HomeViews.Reason status;
    private String explained;

    /**
     * Swaps the card's body. A part joining the card now (its content or empty state on the first model, the loading or unavailable
     * line) first takes the current font: a font change (Settings refreshes the window's tree) made while it was out of the card,
     * say while the card was loading, never reached it.
     */
    @Override public Card body(JComponent part) {
        if (part.getParent() == null) ContentStyle.refreshFonts(part);
        return super.body(part);
    }

    HomeCard(DisplayModeModel mode, String name, String initialEvidence) {
        super(mode);
        setName(name);
        status = new HomeViews.Reason(name + "-status");
        explained = initialEvidence;
        evidence(initialEvidence);
    }

    /** Shows the static loading text in place of the card's content. */
    final void status(String text, String accessibleName) { show(text, false, accessibleName); }

    /** Shows a one-line reason as a warn banner in place of the card's content. */
    final void unavailable(String reason, String accessibleName) { show(reason, true, accessibleName); }

    private void show(String text, boolean warn, String accessibleName) {
        status.setText(text, warn);
        body(status);
        getAccessibleContext().setAccessibleName(accessibleName);
        getAccessibleContext().setAccessibleDescription(warn ? text : null);
    }

    final String statusText() { return status.text(); }

    final boolean statusWarns() { return status.warns(); }

    final void explain(String text) {
        if (text == null || text.isEmpty() || text.equals(explained)) return;
        explained = text;
        evidence(text);
    }
}
