package tomato.gui.stats;

import org.junit.Test;
import static org.junit.Assert.*;

/** The invalid-input surface must not imply the retained export revision was changed. */
public class CohortExportNoticeTest {
    @Test public void invalidInputsExplainRetainedExportRevision(){
        assertTrue(LootArchiveClient.STALE_COHORT.contains("Export still uses the last applied comparison"));
        assertTrue(LootArchiveClient.STALE_COHORT.contains("not these invalid inputs"));
    }
}
