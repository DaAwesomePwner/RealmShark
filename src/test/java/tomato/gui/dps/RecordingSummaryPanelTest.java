package tomato.gui.dps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatRecord;
import static org.junit.Assert.*;

public class RecordingSummaryPanelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SESSION = HomeHistoryFixture.id("summary-lookup");
    private static final String ID = "synthetic-recording";

    private static RecordingItem item() {
        return new RecordingItem(ID, ID, RecordingItem.Kind.SAVED, "Synthetic", "Synthetic", null, null,
            null, null, 0, 123L, null, null, RecordingItem.Link.LEGACY, null, SESSION, true,
            RecordingItem.FullDetail.NONE, null, null, null, null);
    }
    private static CombatRecord record() {
        CombatRecord record = new CombatRecord(); record.recordingId = ID; record.totalDamage = 123;
        record.players = null; record.bosses = null;
        return record;
    }

    @Test public void readsOnlyTheKeyedRecordWithoutListingTheSessionOrScanningOtherRecords() throws Exception {
        Path root = temp.newFolder("direct").toPath();
        Path target = CombatFixtures.writeRecord(root, SESSION, record());
        // No session metadata: catalog scanning would find nothing. A duplicate under a different key must not win.
        CombatRecord duplicate = record(); duplicate.totalDamage = 999;
        Files.writeString(target.resolveSibling("000-first.json"), SessionStore.JSON.toJson(duplicate));
        Files.writeString(target.resolveSibling("001-damaged.json"), "{");
        try (SessionStore store = new SessionStore(root, false, "test")) {
            assertEquals(123, RecordingSummaryPanel.read(store, item(), new Cancellation()).total());
        }
    }

    @Test public void missingUnreadableNewerAndMismatchedRecordsRemainUnavailable() throws Exception {
        Path root = temp.newFolder("unavailable").toPath();
        try (SessionStore store = new SessionStore(root, false, "test")) {
            assertNull(RecordingSummaryPanel.read(store, item(), new Cancellation()));
            Path target = CombatFixtures.writeRecord(root, SESSION, record());
            Files.writeString(target, "{");
            assertNull(RecordingSummaryPanel.read(store, item(), new Cancellation()));
            CombatRecord newer = record(); newer.schemaVersion = CombatRecord.SCHEMA_VERSION + 1;
            CombatFixtures.writeRecord(root, SESSION, newer);
            assertNull(RecordingSummaryPanel.read(store, item(), new Cancellation()));
            CombatRecord other = record(); other.recordingId = "another-recording";
            Files.writeString(target, SessionStore.JSON.toJson(other));
            assertNull(RecordingSummaryPanel.read(store, item(), new Cancellation()));
            Files.writeString(target, "null");
            assertNull(RecordingSummaryPanel.read(store, item(), new Cancellation()));
        }
    }

    @Test public void damagedDetailKeepsSummaryAndExistingMessageAndReadsStayOffEdt() throws Exception {
        Path root = temp.newFolder("detail").toPath();
        CombatFixtures.writeRecord(root, SESSION, record());
        Path detail = root.resolve(SESSION).resolve(CombatFacts.DETAILS).resolve(SessionStore.checkpointName(ID) + ".json");
        Files.createDirectories(detail.getParent()); Files.writeString(detail, "{");
        try (SessionStore store = new SessionStore(root, false, "test")) {
            var damage = RecordingSummaryPanel.read(store, item(), new Cancellation());
            assertEquals(123, damage.total()); assertEquals(RecordingSummaryPanel.DETAIL_UNREADABLE, damage.detailReason());
            SwingUtilities.invokeAndWait(() -> {
                try { RecordingSummaryPanel.read(store, item(), new Cancellation()); fail("Read on the EDT"); }
                catch (IllegalStateException expected) { }
                catch (IOException unexpected) { throw new AssertionError(unexpected); }
            });
        }
        try { RecordingSummaryPanel.read(null, item(), new Cancellation()); fail("No history"); }
        catch (IOException expected) { assertEquals(RecordingSummaryPanel.NO_HISTORY, expected.getMessage()); }
    }
}
