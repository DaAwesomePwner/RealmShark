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
    @Test public void completedImportFromBeforeClearCannotRepopulateLibrary() throws Exception {
        Path file = temp.getRoot().toPath().resolve("old-job.dps"); write(file, encounter("Old job"));
        EncounterCatalog catalog = new EncounterCatalog(); long generation = catalog.generation();
        EncounterImport candidate = EncounterImport.read(file); catalog.clear();
        assertNull(catalog.add(candidate, generation)); assertTrue(catalog.entries().isEmpty());
        assertFalse(catalog.add(candidate, catalog.generation()).duplicate); assertEquals(1, catalog.entries().size());
    }
}
