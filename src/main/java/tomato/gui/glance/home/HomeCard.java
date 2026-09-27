package tomato.gui.glance.home;

import tomato.gui.kit.Card;
import tomato.gui.kit.DisplayModeModel;

/**
 * A Home card: explicit loading and unavailable states (spec §7; an unavailable reason is a warn banner inside the card) and
 * Evidence text that is rewritten only when it changes.
 */
abstract class HomeCard extends Card {
    private final HomeViews.Reason status;
    private String explained;

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
