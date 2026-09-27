package tomato.gui.glance.home;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.KitText;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.DisplayFormat;

/**
 * Pinned quests at a glance (spec §6.1): up to three pinned quests (open first) with their reward sprites, the pinned /
 * repeatable / done counts and the list's age with a stale state. Unpinned quests are not listed. The whole card opens Quests.
 */
final class QuestsCard extends HomeCard {
    static final int SHOWN = 3, REWARDS = 4;
    private final KitText counts = HomeViews.named(HomeViews.caption(""), "home-quests-counts");
    private final KitText age = HomeViews.named(HomeViews.caption(""), "home-quests-age");
    private final KitText none = HomeViews.named(HomeViews.body("No pinned quests · Pin quests on the Quests page"), "home-quests-none");
    private final Chip stale = HomeViews.named(new Chip("May be out of date", Tokens.Tone.WARN), "home-quests-stale");
    private final QuestRow[] rows = new QuestRow[SHOWN];
    private final EmptyState empty = HomeViews.named(new EmptyState("No quests captured",
        "Visit the Daily Quest Room with capture on to load your quests.", null), "home-quests-empty");
    private final JComponent content;
    private HomeModel.Quests shown;
    private String shownAge = "";

    QuestsCard(Runnable openQuests, DisplayModeModel mode) {
        super(mode, "home-quests", "No quest list captured yet.");
        title("Quests");
        stale.setToolTipText("The quest list may have changed since it was captured. Visit the Daily Quest Room to refresh it.");
        JComponent[] lines = new JComponent[SHOWN + 3];
        lines[0] = counts;
        for (int i = 0; i < SHOWN; i++) lines[i + 1] = rows[i] = new QuestRow(i);
        lines[SHOWN + 1] = none;
        lines[SHOWN + 2] = HomeViews.wrap(age, stale);
        content = HomeViews.named(HomeViews.stack(Tokens.XS, lines), "home-quests-content");
        onOpen("Open quests", openQuests);
        status(HomeViews.LOADING, "Quests: loading");
    }

    /**
     * EDT only; skips a section that shows exactly what is shown. No expiry countdown in P2: the format of
     * QuestData.expiration is unconfirmed until P4 (spec O1), so nothing is parsed or guessed here.
     */
    void apply(HomeModel.Quests quests, long now) {
        String captured = ageText(quests, now);
        if (quests != null && quests.equals(shown) && captured.equals(shownAge)) return;
        shown = quests;
        shownAge = captured;
        if (quests == null || quests.state() == HomeModel.State.LOADING) { status(HomeViews.LOADING, "Quests: loading"); return; }
        if (quests.state() == HomeModel.State.UNAVAILABLE) { unavailable("Quest data is unavailable right now.", "Quests: unavailable"); return; }
        if (quests.state() == HomeModel.State.EMPTY) {
            explain("No quest list has been captured yet.");
            body(empty);
            getAccessibleContext().setAccessibleName("Quests: none captured yet. Open quests");
            getAccessibleContext().setAccessibleDescription(null);
            return;
        }
        counts.setText(quests.pinned() + " pinned · " + quests.repeatable() + " repeatable · " + quests.done() + " done");
        List<HomeModel.QuestLine> top = quests.top() == null ? List.of() : quests.top();
        int shownRows = Math.min(SHOWN, top.size());
        for (int i = 0; i < SHOWN; i++) {
            rows[i].setVisible(i < shownRows);
            if (i < shownRows) rows[i].set(top.get(i));
        }
        none.setVisible(shownRows == 0);
        boolean old = quests.stale() || quests.state() == HomeModel.State.STALE;
        age.setText(captured);
        stale.setVisible(old);
        explain("Quest list captured " + (quests.capturedAt() > 0 ? DisplayFormat.formatTimestamp(quests.capturedAt()) : "at an unknown time")
            + " in the Daily Quest Room. Only quests pinned on the Quests page for this account are listed, open ones first."
            + (old ? " The list is stale: it may no longer match the game." : "")
            + " Expiry countdowns are not shown until the expiry format is confirmed.");
        // The list's age ticks, so it is the description; the name changes only with the counts.
        getAccessibleContext().setAccessibleName("Quests: " + counts.getText().replace(" · ", ", ") + (old ? ", may be out of date" : "") + ". Open quests");
        getAccessibleContext().setAccessibleDescription(captured);
        body(content);
    }

    private static String ageText(HomeModel.Quests quests, long now) {
        if (quests == null || quests.state() != HomeModel.State.LIVE && quests.state() != HomeModel.State.STALE) return "";
        return quests.capturedAt() > 0 ? "Captured " + HomeViews.ago(quests.capturedAt(), now) : "Capture time unknown";
    }

    /** One pinned quest: name, Repeatable / One-time and Done badges, up to four reward sprites and "+N". */
    private static final class QuestRow extends JPanel {
        private final KitText name = HomeViews.body(""), more = HomeViews.caption("");
        private final Chip kind = new Chip("", Tokens.Tone.INFO), done = new Chip(HomeViews.glyph('✓', "Done"), Tokens.Tone.GOOD);
        private final ItemSlot[] rewards = new ItemSlot[REWARDS];

        QuestRow(int index) {
            super(new BorderLayout(Tokens.S, 0));
            setOpaque(false);
            String id = "home-quest-" + index;
            setName(id);
            name.setName(id + "-name"); kind.setName(id + "-kind"); done.setName(id + "-done"); more.setName(id + "-more");
            JPanel right = HomeViews.clear(new FlowLayout(FlowLayout.TRAILING, 2, 0));
            for (int i = 0; i < REWARDS; i++) right.add(rewards[i] = HomeViews.named(new ItemSlot(24), id + "-reward-" + i));
            right.add(more);
            add(HomeViews.wrap(name, kind, done), BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
        }

        void set(HomeModel.QuestLine line) {
            String title = line.name() == null || line.name().isEmpty() ? "Unnamed quest" : line.name();
            name.setText(title);
            kind.setText(line.repeatable() ? HomeViews.glyph('↻', "Repeatable") : "One-time");
            kind.setTone(line.repeatable() ? Tokens.Tone.INFO : Tokens.Tone.NEUTRAL);
            done.setVisible(line.done());
            int[] ids = line.rewardIds() == null ? new int[0] : line.rewardIds();
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < REWARDS; i++) {
                boolean shown = i < ids.length && ids[i] > 0;
                rewards[i].setVisible(shown);
                if (!shown) continue;
                rewards[i].setItem(ids[i], HomeViews.tier(ids[i]));
                names.append(names.length() == 0 ? "" : ", ").append(Sprites.name(ids[i]));
            }
            int extra = Math.max(0, ids.length - REWARDS);
            more.setVisible(extra > 0);
            more.setText(extra > 0 ? "+" + extra : "");
            getAccessibleContext().setAccessibleName(title + (line.repeatable() ? ", repeatable" : ", one-time") + (line.done() ? ", done" : "")
                + (names.length() == 0 ? ", no rewards listed" : ", rewards: " + names) + (extra > 0 ? " and " + extra + " more" : ""));
        }
    }
}
