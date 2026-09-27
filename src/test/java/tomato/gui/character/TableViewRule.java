package tomato.gui.character;

import org.junit.rules.ExternalResource;
import util.PropertiesManager;

/** Pins the roster's Table view for tests of the roster table (the page opens on the gallery) and restores the saved choice. */
public final class TableViewRule extends ExternalResource {
    private static final String KEY = "ui.characters.view";
    private String saved;

    @Override protected void before() { saved = PropertiesManager.getProperty(KEY); PropertiesManager.setProperties(KEY, "table"); }
    @Override protected void after() { PropertiesManager.setProperties(KEY, saved == null ? "" : saved); }
}
