package tomato.gui.runs;

import org.junit.rules.ExternalResource;
import util.PropertiesManager;

/**
 * Sets the Runs page's view preference ({@link RunFeedView#VIEW_KEY}) for a test and restores the saved choice afterwards. The
 * Runs page opens on the run cards; the archive table is its Table view. {@code new RunsViewRule()} pins the Table view for tests
 * that drive the archive table; {@link #cards()} starts on the default Cards view and restores whatever routes or Browse saved
 * history wrote.
 */
public final class RunsViewRule extends ExternalResource {
    private final String value;
    private String saved;

    /** The Table view. */
    public RunsViewRule() { this(RunFeedView.TABLE); }
    private RunsViewRule(String value) { this.value = value; }

    /** The default Cards view (the preference cleared). */
    public static RunsViewRule cards() { return new RunsViewRule(""); }

    @Override protected void before() { saved = PropertiesManager.getProperty(RunFeedView.VIEW_KEY); PropertiesManager.setProperties(RunFeedView.VIEW_KEY, value); }
    @Override protected void after() { PropertiesManager.setProperties(RunFeedView.VIEW_KEY, saved == null ? "" : saved); }
}
