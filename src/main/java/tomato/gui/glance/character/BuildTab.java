package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.backend.data.LiveCharacter;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import static tomato.gui.glance.character.SheetViews.named;

/**
 * Sheet › Build (spec §6.1–6.2): the app's single Build page (MyInfoGUI). Build describes the character in game or, after capture
 * stops or while a map change clears it, the last one that was. So the tab shows Build only on that character's sheet; on any
 * other sheet an empty state says "Build shows the character you're playing" and, while someone is in game, offers that
 * character's Build. While another character's sheet loads, or after a failed build, a neutral card shows neither. MyInfoGUI
 * stays parented in its hidden card (never a second instance). EDT only.
 */
final class BuildTab extends JPanel {
    static final String POINTER = "Build shows the character you're playing";
    private final CardLayout cards = new CardLayout();
    private final JPanel host = named(new JPanel(new BorderLayout()), "character-build-host");
    private final JPanel other = named(new JPanel(new BorderLayout()), "character-build-other");
    private final Consumer<String> openLive;
    private JComponent build;
    private String card = "", liveKey, pointerFor;

    /** {@code openLive} opens the sheet's Build tab for a journal key (the character in game). */
    BuildTab(Consumer<String> openLive) {
        this.openLive = Objects.requireNonNull(openLive, "openLive");
        setLayout(cards);
        setOpaque(false);
        setName("character-build");
        host.setOpaque(false);
        other.setOpaque(false);
        add(host, "build");
        add(other, "other");
        add(named(new EmptyState("Build is not available here", "Build opens in the RealmShark window.", null), "character-build-unhosted"), "unhosted");
        add(named(new EmptyState(CharacterSheet.LOADING, "Build shows here once this character's sheet has loaded.", null), "character-build-loading"), "loading");
        show("unhosted");
    }

    /** The journal key of the character Build describes: the one in game, else the last one (capture stopped); null when none. */
    static String shownKey(LiveCharacter live) {
        if (live == null) return null;
        LiveCharacter.Snapshot shown = live.current() != null ? live.current() : live.lastKnown();
        return shown == null ? null : shown.journalKey();
    }

    /** Parents the app's single MyInfoGUI here (TomatoGUI calls this once). */
    void host(JComponent value) {
        if (build == value) return;
        if (build != null) host.remove(build);
        build = value;
        host.add(value, BorderLayout.CENTER);
        host.revalidate();
        if ("unhosted".equals(card)) show("build");
    }

    JComponent hosted() { return build; }

    /** "build", "other", "loading" or "unhosted". */
    String card() { return card; }

    /**
     * The neutral card, while another character's sheet loads or after a failed build: neither MyInfoGUI nor an Open button, so
     * nothing of the previous character stays on screen or acts. The next {@link #apply} picks the card again.
     */
    void loading() {
        liveKey = null;
        pointerFor = null;
        other.removeAll();
        other.revalidate();
        other.repaint();
        show(build == null ? "unhosted" : "loading");
    }

    /**
     * {@code buildKey}: the character Build describes ({@link #shownKey}). This sheet's own character shows Build; any other shows
     * the pointer, with an Open button only while someone is in game. A null model (loading, or not in the journal) changes nothing.
     */
    void apply(SheetModel model, String buildKey) {
        if (build == null) { show("unhosted"); return; }
        if (model == null) return;
        if (model.key().equals(buildKey)) { show("build"); return; }
        pointer(model.live());
        show("other");
    }

    /**
     * The pointer for another sheet; {@code live} null: nobody is in game, so there is nothing to open. Worded by class, never
     * by name: the game's name stat is the account name, shared by every character on it.
     */
    private void pointer(SheetModel.Live live) {
        liveKey = live == null ? null : live.key(); // read at click time: the same class may belong to another character later
        String shownFor = live == null ? "" : live.className();
        if (shownFor.equals(pointerFor)) return;
        pointerFor = shownFor;
        EmptyState state;
        if (live == null) state = new EmptyState(POINTER, "Start capture and enter the game with this character.", null);
        else {
            KitButton open = named(KitButton.primary("Open the " + shownFor + "'s Build"), "character-build-open-live");
            open.addActionListener(e -> { if (liveKey != null) openLive.accept(liveKey); });
            state = new EmptyState(POINTER, "Your " + shownFor + " is in game now.", open);
        }
        other.removeAll();
        other.add(named(state, "character-build-other-state"));
        other.revalidate();
        other.repaint();
    }

    private void show(String name) {
        if (name.equals(card)) return;
        card = name;
        cards.show(this, name);
    }
}
