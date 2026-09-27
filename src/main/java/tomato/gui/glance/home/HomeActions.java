package tomato.gui.glance.home;

import java.util.function.Consumer;
import tomato.history.link.VisitRef;

/** Home's drill-downs. TomatoGUI routes each one through the Navigator, so Back returns to Home. */
public record HomeActions(Runnable characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests) {}
