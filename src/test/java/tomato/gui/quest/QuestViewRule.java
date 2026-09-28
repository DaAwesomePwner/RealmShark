package tomato.gui.quest;

import org.junit.rules.ExternalResource;
import util.PropertiesManager;

/**
 * Pins the quest Board's Table view for tests of the quest table (the Board opens on cards; the table is the Table view) and
 * restores the saved choice afterwards.
 */
public final class QuestViewRule extends ExternalResource {
    private static final String KEY = "ui.quests.view";
    private String saved;

    @Override protected void before() { saved = PropertiesManager.getProperty(KEY); PropertiesManager.setProperties(KEY, "table"); }
    @Override protected void after() { PropertiesManager.setProperties(KEY, saved == null ? "" : saved); }
}
