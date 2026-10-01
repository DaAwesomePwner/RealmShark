package tomato.gui.dps;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import static org.junit.Assert.*;

public class EncounterCatalogTest {
    @Test public void twentyFirstCloseDropsOldestCaptureAndItsCheck() {
        TomatoData data = new TomatoData(); EncounterCatalog catalog = new EncounterCatalog();
        DpsData first = DpsRetentionTest.close(data, 0, false);
        catalog.capture(data::closedDpsSnapshot);
        String firstId = catalog.find(first).id; catalog.check(firstId, true);
        for (int i = 1; i <= 20; i++) {
            DpsRetentionTest.close(data, i, false); catalog.capture(data::closedDpsSnapshot);
        }
        assertEquals(20, data.dpsData.size()); assertEquals(20, catalog.entries().size());
        assertFalse(data.dpsData.contains(first)); assertNull(catalog.find(first)); assertNull(catalog.find(firstId));
        assertTrue(catalog.checkedEntries().isEmpty());
    }

    @Test public void staleCatalogSelectionCannotPinAnAlreadyEvictedFight() throws Exception {
        TomatoData data = new TomatoData();
        DpsData first = DpsRetentionTest.close(data, 0, false);
        DpsGUI[] view = new DpsGUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> view[0] = new DpsGUI(data));
        EncounterCatalog.Entry stale = view[0].encounters().find(first);
        for (int i = 1; i <= 20; i++) DpsRetentionTest.close(data, i, false);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            assertNotNull("The catalog has not received the next publication yet", view[0].encounters().find(first));
            assertFalse("A stale lookup must not pin an already evicted recording", view[0].showEncounter(stale.id));
        });
        DpsGUI.updateMapPacket(data);
        assertNull(view[0].encounters().find(first));
    }

    @Test public void shownCaptureIsProtectedUntilGoingLive() throws Exception {
        TomatoData data = new TomatoData();
        DpsData first = DpsRetentionTest.close(data, 0, false);
        DpsGUI[] view = new DpsGUI[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            view[0] = new DpsGUI(data); assertTrue(view[0].showEncounter(view[0].encounters().find(first).id));
        });
        DpsData second = DpsRetentionTest.close(data, 1, false);
        DpsGUI.updateMapPacket(data);
        EncounterCatalog.Entry evicted = view[0].encounters().find(second);
        view[0].encounters().check(evicted.id, true);
        for (int i = 2; i <= 20; i++) {
            DpsRetentionTest.close(data, i, false); DpsGUI.updateMapPacket(data);
        }
        assertEquals(20, data.dpsData.size()); assertEquals(20, view[0].encounters().entries().size());
        assertSame(first, data.dpsData.get(0)); assertNotNull(view[0].encounters().find(first));
        assertFalse(data.dpsData.contains(second)); assertNull(view[0].encounters().find(second));
        assertFalse(view[0].encounters().checked(evicted.id));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            assertFalse(view[0].showEncounter(evicted.id)); view[0].setIndex(-1);
        });
        DpsRetentionTest.close(data, 21, false); DpsGUI.updateMapPacket(data);
        assertFalse(data.dpsData.contains(first)); assertNull(view[0].encounters().find(first));
        javax.swing.SwingUtilities.invokeAndWait(DpsGUI::clearDpsLogs);
        assertTrue(data.dpsData.isEmpty()); assertTrue(view[0].encounters().entries().isEmpty());
    }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static DpsData encounter(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name; return new DpsData(map, new HashMap<>(), new ArrayList<>(), 1000, 1000, null); }
    static void write(Path file, DpsData data) throws IOException { try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(data); } }
    @Test public void sameNamesAndTimesNeverMergeAndExportCopiesKeepRecordingIdentity() throws Exception {
        EncounterCatalog catalog = new EncounterCatalog(); DpsData[] values = new DpsData[20]; Set<String> recordings = new HashSet<>();
        for (int i = 0; i < values.length; i++) { values[i] = encounter("Same dungeon"); assertTrue(recordings.add(values[i].getRecordingId())); }
        catalog.captured(values); assertEquals(20, catalog.entries().size());
        EncounterCatalog.Entry chosen = catalog.entries().get(9); catalog.check(chosen.id, true);
        Collections.reverse(Arrays.asList(values)); catalog.captured(values);
        assertSame(chosen, catalog.find(chosen.id)); assertSame(chosen, catalog.checkedEntries().get(0));
        DpsData copy = chosen.data.getSaveFile(false); assertEquals(chosen.data.getRecordingId(), copy.getRecordingId());
        Path file = temp.getRoot().toPath().resolve("saved.dps"); write(file, copy);
        assertEquals(copy.getRecordingId(), EncounterImport.read(file).data.getRecordingId());
    }
    @Test public void identicalBytesDeduplicateAcrossNamesButSameIdDifferentPayloadIsPreserved() throws Exception {
        Path first = temp.getRoot().toPath().resolve("first.dps"), renamed = temp.getRoot().toPath().resolve("renamed.dps"), variant = temp.getRoot().toPath().resolve("variant.dps");
        DpsData data = encounter("Same dungeon"); write(first, data); Files.copy(first, renamed);
        DpsData changed = data.getSaveFile(false); changed.totalDungeonPcTime = 9000; write(variant, changed);
        EncounterCatalog catalog = new EncounterCatalog(); EncounterCatalog.Admission one = catalog.add(EncounterImport.read(first));
        catalog.check(one.entry.id, true);
        EncounterCatalog.Admission two = catalog.add(EncounterImport.read(renamed));
        assertTrue(two.duplicate); assertSame(one.entry, two.entry); assertEquals(1, catalog.entries().size());
        EncounterCatalog.Admission three = catalog.add(EncounterImport.read(variant));
        assertFalse(three.duplicate); assertTrue(three.sameRecordingId); assertNotEquals(one.entry.id, three.entry.id);
        assertEquals(2, catalog.entries().size()); assertEquals(1000, one.entry.data.totalDungeonPcTime); assertEquals(9000, three.entry.data.totalDungeonPcTime);
        assertTrue(catalog.checked(one.entry.id)); assertFalse(catalog.checked(three.entry.id));
        assertEquals("first.dps", one.entry.origin.fileName); assertEquals(one.entry.origin.fingerprint, two.entry.origin.fingerprint);
    }
    @Test public void summaryCountsOutgoingOwnersAndDoesNotDoubleCountAggregateFallback() {
        DpsData data = encounter("Run"); Entity owner = new Entity(null, 7, 0); owner.markPlayerIdentity();
        Entity first = new Entity(null, 11, 0); first.genericDamageHit(owner, new Projectile(100), 1000);
        Entity second = new Entity(null, 12, 0); second.genericDamageHit(owner, new Projectile(200), 2000); second.getDamageList().clear();
        Entity playerTarget = new Entity(null, 13, 0); playerTarget.markPlayerIdentity(); playerTarget.genericDamageHit(owner, new Projectile(999), 2000);
        data.hitList.put(11, first); data.hitList.put(12, second); data.hitList.put(13, playerTarget);
        EncounterSummary summary = new EncounterSummary(data);
        assertEquals(300, summary.damage); assertEquals(1, summary.contributors); assertTrue(summary.coverage.contains("aggregate-only")); assertEquals("Unavailable", summary.localContext);
    }
    @Test public void typedQueryUsesSourceContextAndLiteralSearchWithoutChangingChecks() throws Exception {
        EncounterCatalog catalog = new EncounterCatalog(); catalog.captured(new DpsData[]{encounter("[Run]")});
        EncounterCatalog.Entry entry = catalog.entries().get(0); catalog.check(entry.id, true);
        assertTrue(new EncounterQuery("[run]", EncounterQuery.Source.CAPTURED, "Unavailable").matches(entry, entry.summary()));
        assertTrue(new EncounterQuery(tomato.gui.modern.DisplayFormat.formatTimestamp(entry.summary().started), EncounterQuery.Source.ANY, null).matches(entry, entry.summary()));
        assertFalse(new EncounterQuery("", EncounterQuery.Source.IMPORTED, null).matches(entry, entry.summary()));
        assertFalse(new EncounterQuery(".*", EncounterQuery.Source.ANY, null).matches(entry, entry.summary()));
        assertEquals(1, catalog.checkedEntries().size());
        long generation = catalog.generation(); catalog.clear(); assertTrue(catalog.generation() > generation); assertTrue(catalog.checkedEntries().isEmpty());
    }
    /** A saved full-detail file as {@code CombatAutosave} places it, read with {@link EncounterImport#readSaved}. */
    private EncounterImport saved(DpsData data) throws IOException {
        Path file = CombatAutosave.fullDetailFile(temp.getRoot().toPath().resolve(UUID.randomUUID().toString()), data.getRecordingId());
        Files.createDirectories(file.getParent()); write(file, data.getSaveFile(false));
        return EncounterImport.readSaved(file);
    }
    @Test public void entriesSayWhetherTheyWereCapturedImportedOrLoadedFromSavedHistory() throws Exception {
        EncounterCatalog catalog = new EncounterCatalog(); catalog.captured(new DpsData[]{encounter("Captured")});
        Path file = temp.getRoot().toPath().resolve("import.dps"); write(file, encounter("Imported"));
        EncounterCatalog.Entry captured = catalog.entries().get(0), imported = catalog.add(EncounterImport.read(file)).entry;
        EncounterCatalog.Entry saved = catalog.addSaved(saved(encounter("Saved")), e -> false);
        assertEquals(EncounterCatalog.Kind.CAPTURED, captured.kind()); assertFalse(captured.imported());
        assertEquals(EncounterCatalog.Kind.IMPORTED, imported.kind()); assertTrue(imported.imported());
        assertEquals(EncounterCatalog.Kind.SAVED, saved.kind()); assertFalse("Saved full detail is not a user import", saved.imported());
        assertEquals("Captured", captured.source()); assertEquals("import.dps", imported.source()); assertEquals("Saved full detail", saved.source());
        assertEquals(List.of(captured, imported, saved), catalog.entries());
        assertSame(saved, catalog.find(saved.id)); assertSame(saved, catalog.resolve(EncounterCatalog.reference(saved)));
        try { catalog.add(saved(encounter("Wrong door"))); fail("Saved full detail goes through addSaved"); } catch (IllegalArgumentException expected) { }
        try { catalog.addSaved(EncounterImport.read(file), e -> false); fail("A user import is not saved full detail"); } catch (IllegalArgumentException expected) { }
        catalog.clear(); assertEquals(List.of(), catalog.entries());
    }
    @Test public void atMostTwoSavedEntriesStayAndTheOneOnScreenIsNeverEvicted() throws Exception {
        EncounterCatalog catalog = new EncounterCatalog();
        EncounterCatalog.Entry first = catalog.addSaved(saved(encounter("First")), e -> false);
        EncounterCatalog.Entry second = catalog.addSaved(saved(encounter("Second")), e -> false);
        catalog.check(second.id, true);
        long revision = catalog.revision();
        EncounterCatalog.Entry third = catalog.addSaved(saved(encounter("Third")), e -> e == first);   // first is on screen
        assertEquals("The oldest not on screen went", List.of(first, third), catalog.entries());
        assertTrue(catalog.revision() > revision); assertNull(catalog.find(second.id)); assertTrue(catalog.checkedEntries().isEmpty());
        EncounterCatalog.Entry fourth = catalog.addSaved(saved(encounter("Fourth")), e -> false);
        assertEquals(List.of(third, fourth), catalog.entries());
        EncounterCatalog.Entry fifth = catalog.addSaved(saved(encounter("Fifth")), e -> true);
        assertEquals("Nothing on screen is evicted, even past the limit", List.of(third, fourth, fifth), catalog.entries());
        assertEquals(EncounterCatalog.SAVED_KEPT, 2);
    }
    @Test public void aSavedCopyOfARecordingAlreadyInMemoryIsNotAddedAgain() throws Exception {
        EncounterCatalog catalog = new EncounterCatalog(); DpsData live = encounter("Captured"); catalog.captured(new DpsData[]{live});
        EncounterCatalog.Entry captured = catalog.entries().get(0);
        assertSame("In memory wins: a second copy would make routes ambiguous", captured, catalog.addSaved(saved(live), e -> false));
        DpsData other = encounter("Saved");
        EncounterCatalog.Entry saved = catalog.addSaved(saved(other), e -> false);
        assertSame(saved, catalog.addSaved(saved(other), e -> false));
        assertEquals(List.of(captured, saved), catalog.entries());
        long generation = catalog.generation(); EncounterImport late = saved(encounter("Late")); catalog.clear();
        assertNull("A load finished after Clear adds nothing", catalog.addSaved(late, e -> false, generation));
        assertEquals(List.of(), catalog.entries());
    }
    @Test public void completedImportFromBeforeClearCannotRepopulateLibrary() throws Exception {
        Path file = temp.getRoot().toPath().resolve("old-job.dps"); write(file, encounter("Old job"));
        EncounterCatalog catalog = new EncounterCatalog(); long generation = catalog.generation();
        EncounterImport candidate = EncounterImport.read(file); catalog.clear();
        assertNull(catalog.add(candidate, generation)); assertTrue(catalog.entries().isEmpty());
        assertFalse(catalog.add(candidate, catalog.generation()).duplicate); assertEquals(1, catalog.entries().size());
    }
}
