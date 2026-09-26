package packets.packetcapture.logger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.*;

/** One module's successful coverage write must never hide another module's failed write. */
public class CoverageErrorAggregationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void aLaterSuccessForOneModuleKeepsTheOtherModulesFailureVisible() throws Exception {
        DiscoveryLog log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        try {
            log.setSaving(false);
            assertEquals("", log.snapshot().coverageError);
            log.coverageWriteCompleted("runs", new IOException("disk full"));
            log.coverageWriteCompleted("timeline", null);
            String error = log.snapshot().coverageError;
            assertTrue(error, error.contains("runs") && error.contains("IOException"));
            assertFalse("Only the failing module is named", error.contains("timeline"));
            log.coverageWriteCompleted("runs", null);
            assertEquals("The error clears once that module's own write succeeds", "", log.snapshot().coverageError);
        } finally { log.close(); }
    }
}
