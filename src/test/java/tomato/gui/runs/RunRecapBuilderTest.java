package tomato.gui.runs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DamageSource;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.activity.ActivitySummaries;
import tomato.gui.activity.RunWorkbench;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.dps.EncounterLink;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Portals;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatDetail;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.drop;
import static tomato.gui.stats.LootTestDrops.item;

/**
 * The run recap's model over synthetic saved history (synthetic names only): one exact visit read, loot, fame, combat
 * records and timeline events joined by the exact VisitRef only, the longest recording by default with a picker, and
 * unknowns as reasons, never 0.
 */
public class RunRecapBuilderTest {
    @Test public void aPlayersEnchantsAlwaysMatchTheirEquipment() {
        RunRecapModel.Players.Player shortList = new RunRecapModel.Players.Player(1, "A", "Wizard", 782, Arrays.asList(1, 2, 3, 4), null, 0L,
            List.of(EnchantInfo.ofSlotCount(2)));
        assertEquals(4, shortList.enchants().size());
        assertEquals(EnchantInfo.ofSlotCount(2), shortList.enchants().get(0));
        assertSame(EnchantInfo.notRecorded(), shortList.enchants().get(3));
        RunRecapModel.Players.Player nullEntry = new RunRecapModel.Players.Player(1, "A", "Wizard", 782, Arrays.asList(1, 2), null, 0L,
            Arrays.asList(null, EnchantInfo.unreadable(), EnchantInfo.unreadable()));
        assertEquals(Arrays.asList(EnchantInfo.notRecorded(), EnchantInfo.unreadable()), nullEntry.enchants());
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** Session A holds the runs under test; B reuses visit id v1 (never joined); C crashed with a visit left open. */
    private static final String A = id("recap-a"), B = id("recap-b"), C = id("recap-c");
    private static final long T0 = at(0, 10, 0);
    private static final VisitRef V1 = ref(A, "v1"), V2 = ref(A, "v2"), V3 = ref(A, "v3"), OTHER_V1 = ref(B, "v1");

    private Path root;
    private SessionStore store;
    private RunRecapBuilder builder;
    /** A/v1's two recordings (60 s and 20 s) and A/v3's unverified one (no detail saved). */
    private CombatSummaries.Result longRun, shortRun, unverified;

    @Before public void write() throws Exception {
        root = temp.newFolder().toPath();
        session(root, A, T0 - HOUR, T0 + 2 * HOUR);
        ActivityJournal.Visit v1 = visit("v1", "Lost Halls", T0, T0 + 10 * MINUTE, true);
        v1.rosterSize = 4; v1.partyId = 12; v1.exaltIncrease = 2;
        v1.resourceTimeline.add(point(T0 + 1_000, 500, 200)); v1.resourceTimeline.add(point(T0 + 2_000, 450, 180));
        v1.damageTracked = true; v1.totalDamage = 999; v1.firstDamageAt = T0 + 1_000; v1.lastDamageAt = T0 + 9_000;
        InspectSnapshot alpha = snapshot(1, 782, "Alpha", "AAIE_wU,,,", 2001, 2002), bravo = snapshot(2, 775, "Bravo");
        v1.inspectedPlayers.put(bravo.key(), bravo); v1.inspectedPlayers.put(alpha.key(), alpha); v1.inspectedPlayerCount = 2;
        v1.playerDamage.put(alpha.key(), 700L);   // Bravo has none recorded: a tracked zero
        ActivityJournal.Visit v2 = visit("v2", "Ice Citadel", T0 + 15 * MINUTE, T0 + 25 * MINUTE, false);   // aggregate-only: no samples
        ActivityJournal.Visit v3 = visit("v3", "Snake Pit", T0 + 30 * MINUTE, T0 + 40 * MINUTE, false);
        v3.resourceTimeline.add(point(T0 + 31 * MINUTE, 300, 100));
        runs(root, A, v1, v2, v3);
        loot(root, A, drop("White", "Lost Halls", T0 + 9 * MINUTE, V1, item(101, UT), item(102, POTION)),
            drop("Brown", "Lost Halls", T0 + 9 * MINUTE, null, item(103, PLAIN)),            // no recorded visit
            drop("Orange", "Lost Halls", T0 + 9 * MINUTE, OTHER_V1, item(104, ST)));        // another session's v1
        fame(root, A, sample(1, 1_000, T0 - 5 * MINUTE), tagged(V1, 1_100, T0 + 2 * MINUTE), tagged(V1, 1_250, T0 + 8 * MINUTE),
            tagged(OTHER_V1, 9_000, T0 + 12 * MINUTE), tagged(V2, 1_300, T0 + 20 * MINUTE));
        lines(root.resolve(A).resolve("timeline.jsonl"), event("a-2", V1.visitId, T0 + 5 * MINUTE, "Area entered"),
            event("a-1", V1.visitId, T0 + MINUTE, "Party roster"), event("a-3", V2.visitId, T0 + 16 * MINUTE, "Area entered"),
            event("a-4", null, T0 + 11 * MINUTE, "Capture issue"));

        longRun = CombatSummaries.build(CombatFixtures.typical(V1, T0 + MINUTE, 60, 3, 9));
        shortRun = CombatSummaries.build(CombatFixtures.typical(V1, T0 + 5 * MINUTE, 20, 4, 9));
        unverified = CombatSummaries.build(twins(V3, T0 + 32 * MINUTE));
        for (CombatSummaries.Result result : List.of(longRun, shortRun)) {
            CombatFixtures.writeRecord(root, A, result.record()); CombatFixtures.writeDetail(root, A, result.detail());
        }
        CombatFixtures.writeRecord(root, A, unverified.record());
        // Linked to B's v1 but saved in A's folder: never A/v1's.
        CombatFixtures.writeRecord(root, A, CombatSummaries.build(CombatFixtures.typical(OTHER_V1, T0, 300, 2, 3)).record());

        session(root, B, T0 - 3 * HOUR, T0 - 2 * HOUR);
        runs(root, B, visit("v1", "Lost Halls", T0 - 150 * MINUTE, T0 - 140 * MINUTE, true));
        loot(root, B, drop("White", "Lost Halls", T0 - 141 * MINUTE, OTHER_V1, item(105, UT)));
        fame(root, B, sample(1, 5_000, T0 - 170 * MINUTE), tagged(OTHER_V1, 6_000, T0 - 145 * MINUTE));
        lines(root.resolve(B).resolve("timeline.jsonl"), event("b-1", "v1", T0 - 149 * MINUTE, "Area entered"));
        CombatFixtures.writeRecord(root, B, CombatSummaries.build(CombatFixtures.typical(OTHER_V1, T0 - 149 * MINUTE, 90, 5, 9)).record());

        session(root, C, T0 - 5 * HOUR, 0);   // crashed: no end saved
        runs(root, C, visit("c1", "Pirate Cave", T0 - 4 * HOUR, 0, false));

        store = new SessionStore(root, false, "fixture");
        builder = new RunRecapBuilder(store, ZONE, () -> NOW);
    }

    @After public void close() { if (store != null) store.close(); }

    // ---- fixtures ----

    /** A session folder whose runs coverage is one capture interval over the whole session (fame gains need one). */
    private static void session(Path root, String id, long started, long ended) throws Exception {
        tomato.gui.glance.home.HomeHistoryFixture.session(root, id, started, ended);
        Path file = root.resolve(id).resolve("session.json");
        JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        long until = ended > 0 ? ended : started + 3 * HOUR;
        JsonObject availability = new JsonObject();
        availability.add("runs", SessionStore.JSON.toJsonTree(new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.PARTIAL,
            "Fixture", started, until, List.of(new SessionStore.Interval(started, until, "Capture stopped")), false)));
        json.add("availability", availability);
        Files.write(file, json.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static AppHistory.FameSample tagged(VisitRef visit, long fame, long time) {
        return new AppHistory.FameSample(1, fame, time, "Wizard", visit, "Lost Halls");
    }
    private static ActivityJournal.ResourcePoint point(long time, int hp, int mp) {
        ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint(); point.time = time; point.hp = hp; point.mp = mp; return point;
    }
    private static ActivityJournal.Entry event(String id, String visitId, long time, String kind) {
        ActivityJournal.Entry entry = new ActivityJournal.Entry();
        entry.id = id; entry.visitId = visitId; entry.time = time; entry.kind = kind; entry.map = "Lost Halls"; entry.detail = "Synthetic " + id;
        entry.values = new LinkedHashMap<>(Map.of("partyId", 12, "memberCount", 4));
        return entry;
    }
    private static void lines(Path file, Object... values) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Object value : values) lines.add(SessionStore.JSON.toJson(value));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
    /** A player's last-recorded loadout; {@code equipment} fills the first slots, the rest are not captured. */
    private static InspectSnapshot snapshot(int id, int classType, String name, int... equipment) {
        Entity player = new Entity(null, id, 0); player.objectType = classType; player.markPlayerIdentity();
        StatData named = new StatData(); named.stringStatValue = name + ",GuildName"; player.stat.set(StatType.NAME_STAT, named);
        StatType[] slots = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
        for (int i = 0; i < equipment.length; i++) { StatData slot = new StatData(); slot.statValue = equipment[i]; player.stat.set(slots[i], slot); }
        return new InspectSnapshot(player, T0 + 1_000);
    }
    /** As {@link #snapshot(int, int, String, int...)}, with the player's UNIQUE_DATA_STRING (one entry per equipped slot). */
    private static InspectSnapshot snapshot(int id, int classType, String name, String enchants, int... equipment) {
        InspectSnapshot plain = snapshot(id, classType, name, equipment);
        Entity player = plain.toEntity();
        StatData stat = new StatData(); stat.stringStatValue = enchants; player.stat.set(StatType.UNIQUE_DATA_STRING, stat);
        return new InspectSnapshot(player, T0 + 1_000);
    }
    /**
     * An unverified-local recording (no local object id in its context): Alpha, two players both named Twin, and Solo; deaths
     * name Twin once and Solo twice. Deaths per row are known only for unique names.
     */
    private static tomato.backend.data.DpsData twins(VisitRef visit, long start) {
        CombatFixtures.Fight fight = CombatFixtures.fight("Snake Pit");
        Entity alpha = fight.user(1, 782, "Alpha"), twinA = fight.player(2, 775, "Twin"), twinB = fight.player(3, 784, "Twin"), solo = fight.player(4, 797, "Solo");
        Entity boss = fight.enemy(100, 5000, "Synthetic boss", 20_000, true);
        long t = start + 1_000;
        for (Entity player : List.of(alpha, twinA, twinB, solo)) {
            fight.hit(boss, player, 200 + player.id * 10, t, DamageSource.WEAPON, 3000 + player.id);
            fight.hit(boss, player, 100, t + 4_000, DamageSource.ABILITY, 0);
        }
        fight.death("Twin", 7).death("Solo", 7).death("Solo", 7);
        return fight.ticks(start, 10_000).context(visit, null, start - 1_000).captured(alpha).build();
    }

    private RunRecapModel build(VisitRef ref) throws Exception { return builder.build(ref, null, new Cancellation()); }

    // ---- tests ----

    @Test public void aLinkedRunWithTwoRecordingsSelectsTheLongestAndListsBothInThePicker() throws Exception {
        RunRecapModel model = build(V1);
        assertTrue(model.available()); assertNull(model.unavailable()); assertEquals(V1, model.ref()); assertEquals(NOW, model.capturedAt());
        RunRecapModel.Header header = model.header();
        assertEquals("Lost Halls", header.map()); assertEquals("Lost Halls", header.mapName());
        assertEquals(Portals.spriteId("Lost Halls"), header.portalId());
        assertEquals(RunOutcome.COMPLETED, header.outcome());
        assertEquals(Long.valueOf(T0), header.entered()); assertEquals(Long.valueOf(10 * MINUTE), header.durationMs());
        assertEquals("The observed RotMG party", Integer.valueOf(4), header.partySize());
        assertEquals("From the fame readings tagged with this run", "Wizard #1", header.character());

        CombatRecord longest = longRun.record();
        RunRecapModel.Damage damage = model.damage();
        assertEquals("A/v1's two recordings only: B's v1 recording (even in A's folder) is another run", 2, damage.recordings().size());
        assertEquals("Longest first", longest.recordingId, damage.recordings().get(0).id());
        assertEquals(shortRun.record().recordingId, damage.recordings().get(1).id());
        assertEquals(CombatFacts.longest(List.of(longRun.record(), shortRun.record())).recordingId, damage.selected());
        assertEquals(longest.windowSeconds, damage.recordings().get(0).windowSeconds());
        assertEquals(longest.enteredAt, damage.recordings().get(0).entered());
        assertEquals(3, damage.recordings().get(0).contributors());
        assertNull(damage.reason()); assertNull(damage.localReason()); assertNull(damage.detailReason());
        assertEquals("The recording's totals (all contributors), not the run's Inspect damage", longest.totalDamage, damage.total());
        assertNotEquals(999L, damage.total());
        assertEquals(longest.unattributedDamage, damage.unattributed());
        assertEquals(longest.windowSeconds, damage.windowSeconds());
        assertEquals(longest.hitsBeforeFirstTick, damage.hitsBeforeFirstTick());

        assertEquals(3, damage.rows().size());
        for (int i = 0; i < 3; i++) {
            CombatRecord.PlayerLine line = longest.players.get(i);
            RunRecapModel.Damage.Row row = damage.rows().get(i);
            assertEquals(line.objectId, row.objectId()); assertEquals(line.name, row.name()); assertEquals(line.classType, row.classType());
            assertEquals(line.damage, row.damage()); assertEquals(longest.dps(line), row.dps()); assertEquals(longest.share(line), row.share());
            assertEquals(line.hits, row.hits()); assertEquals(line.maxHit, row.maxHit()); assertEquals(line.taken, row.taken());
            assertEquals(line.deaths, row.deaths()); assertEquals(line.local, row.local()); assertEquals(i + 1, row.rank());
        }
        RunRecapModel.Damage.Row you = damage.local();
        assertNotNull("The verified local row", you); assertEquals(1, you.objectId());

        CombatDetail detail = longRun.detail();
        assertEquals(detail.bucketSeconds, damage.bucketSeconds()); assertEquals(detail.bucketOrigin, damage.bucketOrigin());
        assertEquals(detail.series.size(), damage.series().size());
        for (int i = 0; i < detail.series.size(); i++) {
            assertEquals(detail.series.get(i).objectId, damage.series().get(i).objectId());
            assertEquals(detail.series.get(i).local, damage.series().get(i).local());
            assertArrayEquals(detail.series.get(i).values, damage.series().get(i).values());
        }
        for (RunRecapModel.Damage.Series series : damage.series()) {
            CombatRecord.PlayerLine line = longest.players.stream().filter(p -> p.objectId == series.objectId()).findFirst().orElseThrow();
            assertEquals("Series names and classes come from the same recording's rows", line.name, series.name());
            assertEquals(line.classType, series.classType());
        }
        assertEquals(1, damage.series().stream().filter(RunRecapModel.Damage.Series::local).count());
        CombatDetail.PlayerSources mine = detail.sources.stream().filter(s -> s.objectId == 1).findFirst().orElseThrow();
        assertEquals(mine.sources.size(), you.sources().size());
        RunRecapModel.Damage.Source top = you.sources().get(0);
        assertEquals(mine.sources.get(0).source, top.source()); assertEquals(DamageSource.valueOf(top.source()).label, top.label());
        assertEquals(mine.sources.get(0).damage, top.damage()); assertEquals(mine.sources.get(0).hits, top.hits());
        assertEquals(mine.sources.get(0).items.size(), top.items().size());
        assertEquals(mine.sources.get(0).items.get(0).itemId, top.items().get(0).itemId());

        CombatRecord.PlayerLine local = longest.local();
        RunRecapModel.Tile dps = model.tile(RunRecapModel.Tile.DPS);
        assertEquals("Your DPS", dps.label());
        assertEquals(DisplayValue.State.KNOWN, dps.value().state);
        assertEquals(KitFormat.compact(longest.dps(local)), dps.value().text());
        assertEquals("#" + local.rank + " of 3", dps.subline());
        RunRecapModel.Tile share = model.tile(RunRecapModel.Tile.SHARE);
        assertEquals(DisplayValue.State.KNOWN, share.value().state);
        assertTrue(share.value().text(), share.value().text().endsWith("%"));
        RunRecapModel.Tile deaths = model.tile(RunRecapModel.Tile.DEATHS);
        assertEquals("Your deaths inside the recording: a real zero", DisplayValue.State.ZERO, deaths.value().state);
        assertEquals(List.of("dps", "share", "deaths", "fame", "loot", "exalt"), model.tiles().stream().map(RunRecapModel.Tile::id).toList());
        RunRecapModel.Tile exalt = model.tile(RunRecapModel.Tile.EXALT);
        assertEquals(DisplayValue.State.KNOWN, exalt.value().state); assertEquals("+2", exalt.value().text());
    }

    @Test public void selectingTheOtherRecordingRebuildsTheRowsWhileTheTilesKeepTheLongest() throws Exception {
        RunRecapModel longest = build(V1);
        RunRecapModel other = builder.build(V1, shortRun.record().recordingId, new Cancellation());
        assertEquals(shortRun.record().recordingId, other.damage().selected());
        assertEquals("Still both in the picker, longest first", longest.damage().recordings(), other.damage().recordings());
        assertEquals(4, other.damage().rows().size());
        assertEquals(shortRun.record().totalDamage, other.damage().total());
        assertEquals(shortRun.detail().bucketOrigin, other.damage().bucketOrigin());
        assertArrayEquals(shortRun.detail().series.get(0).values, other.damage().series().get(0).values());
        assertEquals("Cards, tiles and Home use the longest recording", longest.tiles(), other.tiles());

        assertEquals("An id that is not this run's falls back to the longest",
            longRun.record().recordingId, builder.build(V1, "no-such-recording", new Cancellation()).damage().selected());
        assertEquals("Another run's recording is never shown for this run",
            longRun.record().recordingId, builder.build(V1, unverified.record().recordingId, new Cancellation()).damage().selected());
    }

    @Test public void anUnverifiedLocalRowLeavesNoLocalRowAndTheTilesSayWhy() throws Exception {
        RunRecapModel model = build(V3);
        RunRecapModel.Damage damage = model.damage();
        assertEquals(1, damage.recordings().size()); assertEquals(unverified.record().recordingId, damage.selected());
        assertEquals(4, damage.rows().size());
        assertNull("Another row is never marked as yours", damage.local());
        assertTrue(damage.rows().stream().noneMatch(RunRecapModel.Damage.Row::local));
        String why = new RecordedEncounter("r", "Snake Pit", null, null, EncounterLink.live(new EncounterContext(V3, null, 1L))).unavailableReason();
        assertEquals("The existing wording", why, RunRecapBuilder.LOCAL_UNVERIFIED);
        assertEquals(why, damage.localReason());
        for (String id : List.of(RunRecapModel.Tile.DPS, RunRecapModel.Tile.SHARE, RunRecapModel.Tile.DEATHS)) {
            RunRecapModel.Tile tile = model.tile(id);
            assertEquals(id, DisplayValue.State.UNKNOWN, tile.value().state);
            assertEquals(id, "—", tile.value().text());
            assertEquals(id, why, tile.value().detail);
        }
        assertTrue("No detail saved: rows stay, the chart and sources say why", damage.series().isEmpty());
        assertNotNull(damage.detailReason());
        assertTrue(damage.rows().stream().allMatch(row -> row.sources().isEmpty()));
    }

    /** P5b Recordings: a summary-only recording shows the recap's Damage section of that recording alone (RecordingSummaryPanel). */
    @Test public void oneRecordingsDamageIsTheRecapsDamageSectionOfThatRecordingAlone() throws Exception {
        CombatRecord record = shortRun.record();
        RunRecapModel.Damage alone = RunRecapBuilder.damage(record, shortRun.detail());
        RunRecapModel.Damage inRecap = builder.build(V1, record.recordingId, new Cancellation()).damage();
        assertEquals("Only this recording: the picker stays hidden",
            List.of(new RunRecapModel.Damage.Recording(record.recordingId, record.enteredAt, record.windowSeconds, record.contributors)), alone.recordings());
        assertEquals(record.recordingId, alone.selected());
        assertEquals("The rows the recap shows for it", inRecap.rows(), alone.rows());
        assertEquals(inRecap.series().size(), alone.series().size());
        for (int i = 0; i < alone.series().size(); i++) assertArrayEquals(inRecap.series().get(i).values(), alone.series().get(i).values());
        assertEquals(inRecap.total(), alone.total()); assertEquals(inRecap.unattributed(), alone.unattributed());
        assertEquals(inRecap.windowSeconds(), alone.windowSeconds()); assertEquals(inRecap.bucketSeconds(), alone.bucketSeconds());
        assertEquals(inRecap.bucketOrigin(), alone.bucketOrigin()); assertEquals(inRecap.hitsBeforeFirstTick(), alone.hitsBeforeFirstTick());
        assertNull(alone.reason()); assertEquals(inRecap.localReason(), alone.localReason()); assertNull(alone.detailReason());

        RunRecapModel.Damage bare = RunRecapBuilder.damage(unverified.record(), null);
        assertEquals("Without saved detail the rows stay and the chart says why", 4, bare.rows().size());
        assertTrue(bare.series().isEmpty()); assertNotNull(bare.detailReason());
        assertEquals("Another row is never yours", RunRecapBuilder.LOCAL_UNVERIFIED, bare.localReason()); assertNull(bare.local());
        assertEquals("A detail that could not be read says so instead", "Damaged detail.",
            RunRecapBuilder.damage(unverified.record(), null, "Damaged detail.").detailReason());
        try { RunRecapBuilder.damage(null, null); fail("A summary needs its record"); } catch (NullPointerException expected) { }
    }

    @Test public void aVerifiedLocalPlayerWithoutDamageIsARealZeroAndNoOtherRowIsYours() throws Exception {
        String session = id("recap-quiet");
        session(root, session, T0 - HOUR, T0 + HOUR);
        VisitRef quiet = ref(session, "q1");
        runs(root, session, visit("q1", "Pirate Cave", T0, T0 + 5 * MINUTE, true));
        CombatFixtures.Fight fight = CombatFixtures.fight("Pirate Cave");
        Entity other = fight.player(2, 775, "Bravo"), boss = fight.enemy(100, 5000, "Synthetic boss", 1_000, true);
        fight.hit(boss, other, 300, T0 + 10_000).hit(boss, other, 200, T0 + 14_000);
        CombatFixtures.writeRecord(root, session, CombatSummaries.build(fight.ticks(T0 + 5_000, 20_000).context(quiet, 9, T0).build()).record());
        RunRecapModel model = build(quiet);
        assertEquals("Verified, no recorded damage: a real zero", DisplayValue.State.ZERO, model.tile(RunRecapModel.Tile.DPS).value().state);
        assertEquals("No recorded damage", model.tile(RunRecapModel.Tile.DPS).subline());
        assertEquals(DisplayValue.State.ZERO, model.tile(RunRecapModel.Tile.SHARE).value().state);
        assertEquals("No row to match a death to: unknown", DisplayValue.State.UNKNOWN, model.tile(RunRecapModel.Tile.DEATHS).value().state);
        assertNull("Bravo is never shown as yours", model.damage().local());
        assertNotNull(model.damage().localReason()); assertNotEquals(RunRecapBuilder.LOCAL_UNVERIFIED, model.damage().localReason());
    }

    @Test public void theTimelineKeepsAtMostTheLimitAndCountsEveryEvent() throws Exception {
        String session = id("recap-busy");
        session(root, session, T0 - HOUR, T0 + HOUR);
        runs(root, session, visit("busy", "Lost Halls", T0, T0 + 30 * MINUTE, false));
        Object[] events = new Object[RunRecapBuilder.EVENT_LIMIT + 10];
        for (int i = 0; i < events.length; i++) events[i] = event("busy-" + i, "busy", T0 + events.length - i, "Resources");
        lines(root.resolve(session).resolve("timeline.jsonl"), events);
        RunRecapModel.Timeline timeline = build(ref(session, "busy")).timeline();
        assertEquals(RunRecapBuilder.EVENT_LIMIT + 10, timeline.total());
        assertEquals(RunRecapBuilder.EVENT_LIMIT, timeline.events().size());
        assertEquals("The oldest first", T0 + 1, timeline.events().get(0).time());
    }

    @Test public void deathsArePerRowOnlyForUniqueNamesInsideTheRecording() throws Exception {
        Map<String, List<Integer>> deaths = new HashMap<>();
        for (RunRecapModel.Damage.Row row : build(V3).damage().rows()) deaths.computeIfAbsent(row.name(), n -> new ArrayList<>()).add(row.deaths());
        assertEquals("Two rows share the name Twin: not attributable", Arrays.asList(null, null), deaths.get("Twin"));
        assertEquals(List.of(2), deaths.get("Solo"));
        assertEquals("A unique name with no notification: a real zero", List.of(0), deaths.get("Alpha"));
    }

    @Test public void aRunWithoutARecordingSaysSoInTheDamageSectionAndTiles() throws Exception {
        RunRecapModel model = build(V2);
        RunRecapModel.Damage damage = model.damage();
        assertTrue(damage.recordings().isEmpty()); assertNull(damage.selected()); assertNull(damage.selectedRecording());
        assertTrue(damage.rows().isEmpty()); assertTrue(damage.series().isEmpty());
        assertEquals(RunRecapBuilder.NO_RECORDING, damage.reason());
        assertEquals("No combat recording is linked to this run.", RunRecapBuilder.NO_RECORDING);
        for (String id : List.of(RunRecapModel.Tile.DPS, RunRecapModel.Tile.SHARE, RunRecapModel.Tile.DEATHS)) {
            assertEquals(id, DisplayValue.State.UNKNOWN, model.tile(id).value().state);
            assertEquals(id, RunRecapBuilder.NO_RECORDING, model.tile(id).value().detail);
        }
        RunRecapModel.Tile exalt = model.tile(RunRecapModel.Tile.EXALT);
        assertEquals("No positive progress with evidence: —, not 0", DisplayValue.State.UNKNOWN, exalt.value().state);
        assertFalse(exalt.value().detail.isEmpty());
        assertEquals(RunOutcome.LEFT, model.header().outcome());
        assertNull("No party observed", model.header().partySize());
    }

    /** The recap's loot line is the feed card's wording: "N items" when nothing is notable, "" without items. */
    @Test public void lootSummaryIsTheFeedCardsWording() {
        tomato.gui.stats.LootFacts.Item plain = new tomato.gui.stats.LootFacts.Item(7, false, false, false, false);
        tomato.gui.stats.LootFacts.Bag bag = new tomato.gui.stats.LootFacts.Bag("s", T0, false, "Brown", null, List.of(plain, plain));
        assertEquals("2 items", RunCardModel.summary(List.of(bag)));
        assertEquals("", RunCardModel.summary(List.of()));
    }

    @Test public void lootAndFameAreJoinedByTheExactVisitOnly() throws Exception {
        RunRecapModel model = build(V1);
        RunRecapModel.Loot loot = model.loot();
        assertEquals("Only the bag recorded inside A/v1: not the unlinked bag, not another session's v1", 1, loot.bags().size());
        assertEquals("White", loot.bags().get(0).bag()); assertEquals(T0 + 9 * MINUTE, loot.bags().get(0).time());
        assertEquals(List.of(101, 102), loot.bags().get(0).items().stream().map(tomato.gui.stats.LootFacts.Item::id).toList());
        assertEquals(2, loot.count()); assertEquals("1 UT · 1 potion", loot.summary()); assertNull(loot.reason());
        RunRecapModel.Tile lootTile = model.tile(RunRecapModel.Tile.LOOT);
        assertEquals("2", lootTile.value().text()); assertEquals("1 UT · 1 potion", lootTile.subline());
        RunRecapModel.Tile fame = model.tile(RunRecapModel.Tile.FAME);
        assertEquals("1,000 → 1,100 → 1,250: the first step belongs to this run too", DisplayValue.State.KNOWN, fame.value().state);
        assertEquals("+" + tomato.gui.modern.DisplayFormat.formatInteger(250), fame.value().text());

        RunRecapModel second = build(V2);
        assertTrue(second.loot().bags().isEmpty()); assertNotNull(second.loot().reason());
        assertEquals("Loot was saved in this session, so none inside this run is a real zero", DisplayValue.State.ZERO,
            second.tile(RunRecapModel.Tile.LOOT).value().state);
        assertEquals("1,250 → 1,300 (B's reading between them is ignored)",
            "+" + tomato.gui.modern.DisplayFormat.formatInteger(50), second.tile(RunRecapModel.Tile.FAME).value().text());

        RunRecapModel.Tile unknownFame = build(V3).tile(RunRecapModel.Tile.FAME);
        assertEquals("No reading during the run: unknown, never 0", DisplayValue.State.UNKNOWN, unknownFame.value().state);
        assertFalse(unknownFame.value().detail.isEmpty());
        assertNull(build(V3).header().character());

        RunRecapModel crashed = build(ref(C, "c1"));
        assertEquals("No loot saved in that session at all: unknown, not 0", DisplayValue.State.UNKNOWN,
            crashed.tile(RunRecapModel.Tile.LOOT).value().state);
        assertNotNull(crashed.loot().reason());

        RunRecapModel foreign = build(OTHER_V1);
        assertEquals("B's own v1 sees only B's bag", List.of(105), foreign.loot().bags().get(0).items().stream().map(tomato.gui.stats.LootFacts.Item::id).toList());
        assertEquals("B's own v1 sees only B's recording", 1, foreign.damage().recordings().size());
        assertEquals(5, foreign.damage().rows().size());
    }

    @Test public void theTimelineHoldsOnlyThisVisitsEventsInTimeOrder() throws Exception {
        RunRecapModel.Timeline timeline = build(V1).timeline();
        assertEquals(2, timeline.total()); assertNull(timeline.reason());
        assertEquals(List.of(T0 + MINUTE, T0 + 5 * MINUTE), timeline.events().stream().map(RunRecapModel.Timeline.Event::time).toList());
        ActivityJournal.Entry roster = event("a-1", V1.visitId, T0 + MINUTE, "Party roster");
        assertEquals("Party roster", timeline.events().get(0).kind());
        assertEquals(ActivitySummaries.event(roster), timeline.events().get(0).text());

        RunRecapModel.Timeline none = build(V3).timeline();
        assertTrue(none.events().isEmpty()); assertEquals(0, none.total());
        assertEquals("No saved Timeline events for this exact visit. Recording coverage is unknown; an empty window is not proof that nothing happened.",
            none.reason());
        assertEquals("B's v1 has its own single event", 1, build(OTHER_V1).timeline().total());
    }

    @Test public void anAggregateOnlyVisitGivesTheResourcesReason() throws Exception {
        RunRecapModel.Resources aggregate = build(V2).resources();
        assertNull(aggregate.visit());
        assertTrue(aggregate.reason(), aggregate.reason().contains("Old aggregate-only visits cannot reconstruct charts."));
        RunRecapModel.Resources charted = build(V1).resources();
        assertNull(charted.reason());
        assertEquals("v1", charted.visit().id); assertEquals(2, charted.visit().resourceTimeline.size());
    }

    @Test public void aMissingVisitIsUnavailableWithTheArchiveWording() throws Exception {
        for (VisitRef missing : List.of(ref(A, "nope"), ref(id("recap-deleted"), "v1"), ref("not-a-session", "v1"))) {
            RunRecapModel model = build(missing);
            assertFalse(missing.toString(), model.available());
            assertTrue(model.unavailable(), model.unavailable().startsWith("Linked visit unavailable: session " + missing.sessionId
                + " · visit " + missing.visitId + " is not in this saved history."));
            assertTrue(model.unavailable(), model.unavailable().contains("No other visit (including one with the same dungeon name) is substituted."));
            assertNull(model.header()); assertNull(model.damage()); assertTrue(model.tiles().isEmpty()); assertNull(model.evidence());
        }
    }

    @Test public void playersShowTheRunsInspectDamageLabeledAsInspectedPlayersOnly() throws Exception {
        RunRecapModel.Players players = build(V1).players();
        assertEquals("Inspect damage (inspected players only)", RunRecapModel.Players.INSPECT_DAMAGE);
        assertEquals(2, players.inspectedPlayerCount()); assertNull(players.reason()); assertNull(players.damageReason());
        RunRecapModel.Players.Player alpha = players.players().get(0), bravo = players.players().get(1);
        assertEquals("Most Inspect damage first", "Alpha", alpha.name());
        assertEquals(Long.valueOf(700), alpha.inspectDamage());
        assertEquals("Tracked with no damage recorded: a real zero", Long.valueOf(0), bravo.inspectDamage());
        assertEquals(1, alpha.objectId()); assertEquals(782, alpha.classType());
        assertEquals(snapshot(1, 782, "Alpha").className(), alpha.className());
        assertEquals("Uncaptured slots stay unknown", Arrays.asList(2001, 2002, null, null), alpha.equipment());
        assertEquals(T0 + 1_000, alpha.observedAt());
        assertEquals(EnchantInfo.Rarity.UNCOMMON, alpha.enchants().get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, alpha.enchants().get(1).rarity());
        assertEquals("A snapshot without the enchant stat records none", java.util.Collections.nCopies(4, EnchantInfo.notRecorded()), bravo.enchants());

        RunRecapModel.Players none = build(V2).players();
        assertTrue(none.players().isEmpty()); assertNotNull(none.reason());
        assertNotNull("Not tracked for v2", none.damageReason());
    }

    @Test public void evidenceIsTheWorkbenchSectionsAndACrashedSessionsOpenVisitIsAppEnded() throws Exception {
        ActivityJournal.Visit saved = store.readCheckpoint(A, "runs", "v1", ActivityJournal.Visit.class).orElseThrow();
        saved.normalizePlayers();
        assertEquals(RunWorkbench.text(RunWorkbench.sections(V1, ActivityQueries.visit(saved), saved, ZONE)), build(V1).evidence());

        RunRecapModel crashed = build(ref(C, "c1"));
        assertEquals("Never in progress: its launch ended without closing it", RunOutcome.APP_ENDED, crashed.header().outcome());
        assertTrue(crashed.evidence(), crashed.evidence().contains("Visit ended: App ended"));
        assertEquals("Observed span, entry to last seen", Long.valueOf(20 * MINUTE), crashed.header().durationMs());
    }

    @Test public void theCurrentSessionsOpenVisitIsInProgress() throws Exception {
        try (SessionStore live = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            ActivityJournal.Visit open = visit("live-1", "Lost Halls", T0, 0, false);
            live.put("runs", open.id, open); live.flush();
            RunRecapModel model = new RunRecapBuilder(live, ZONE, () -> NOW).build(ref(live.currentId(), "live-1"), null, new Cancellation());
            assertEquals(RunOutcome.IN_PROGRESS, model.header().outcome());
            assertTrue(model.evidence(), model.evidence().contains("exit not recorded"));
        }
    }

    @Test public void buildRefusesTheEventDispatchThreadAndHonorsCancellation() throws Exception {
        Throwable[] thrown = {null};
        SwingUtilities.invokeAndWait(() -> { try { build(V1); } catch (Throwable failure) { thrown[0] = failure; } });
        assertTrue(String.valueOf(thrown[0]), thrown[0] instanceof IllegalStateException);
        Cancellation cancelled = new Cancellation(); cancelled.cancel();
        assertThrows(CancellationException.class, () -> builder.build(V1, null, cancelled));
    }
}
