package tomato.gui.glance.home;

import java.util.function.Consumer;
import tomato.history.link.VisitRef;

/**
 * Home's drill-downs. TomatoGUI routes each one through the Navigator, so Back returns to Home. {@code characters} receives the
 * hero's journal key: its sheet opens at Overview, or the Characters list when the key is null. {@code loot} opens Loot ›
 * Highlights from the Today card's Notable loot tile (P6a) and receives the window the card shows, Today or This session (P6b);
 * null leaves that tile a plain tile.
 */
public record HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests,
                          Consumer<HomeArchive.Window> loot) {
    /** The five drill-downs without a loot action (tests and fixtures): the Notable loot tile is then not activatable. */
    public HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests) {
        this(characters, build, meter, run, quests, (Consumer<HomeArchive.Window>) null);
    }

    /** The P6a form: a loot action that ignores the window (null: none). */
    public HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests, Runnable loot) {
        this(characters, build, meter, run, quests, loot == null ? null : window -> loot.run());
    }
}
