package tomato.gui.glance.home;

import java.util.function.Consumer;
import tomato.history.link.VisitRef;

/**
 * Home's drill-downs. TomatoGUI routes each one through the Navigator, so Back returns to Home. {@code characters} receives the
 * hero's journal key: its sheet opens at Overview, or the Characters list when the key is null.
 */
public record HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests) {}
