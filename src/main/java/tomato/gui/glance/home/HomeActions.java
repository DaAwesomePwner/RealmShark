package tomato.gui.glance.home;

import java.util.function.Consumer;
import tomato.history.link.VisitRef;

/**
 * Home's drill-downs. TomatoGUI routes each one through the Navigator, so Back returns to Home. {@code characters} receives the
 * hero's journal key: its sheet opens at Overview, or the Characters list when the key is null. {@code loot} opens Loot ›
 * Highlights from the Today card's Notable loot tile (P6a); null leaves that tile a plain tile.
 */
public record HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests,
                          Runnable loot) {
    /** The five drill-downs without a loot action (tests and fixtures): the Notable loot tile is then not activatable. */
    public HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests) {
        this(characters, build, meter, run, quests, null);
    }
}
