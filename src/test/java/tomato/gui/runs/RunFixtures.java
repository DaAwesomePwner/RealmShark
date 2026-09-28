package tomato.gui.runs;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.stats.LootTestDrops;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import static tomato.gui.glance.home.HomeHistoryFixture.at;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/**
 * Synthetic saved runs for the run feed and recap (synthetic names only): sessions with the runs module's capture intervals,
 * visits, exactly linked and foreign loot, visit-tagged fame readings and combat records, in the {@code SessionStore} layout.
 * {@link #write} is a two-session scenario on {@link HomeHistoryFixture#DAY} in {@link HomeHistoryFixture#ZONE}:
 * <ul>
 * <li>{@link #A} (ended 10:00, captured 08:00–10:00): {@code v1} Lost Halls completed, party 6, exalt progress 2, two bags
 * (6 items), fame +240, two recordings (the 300 s one is the longest; the verified local row is #2 of 6 with 30 % and one
 * death); {@code v2} Snake Pit left, one UT, fame a known 0, one recording without a verified local row; {@code v3} Pirate
 * Cave never closed (App ended) without loot, fame or recordings; a Nexus visit (not a dungeon run).</li>
 * <li>{@link #B} (never saved its end: a crash): {@code v1} Ice Citadel completed (the same visit ID as A's) and {@code v4}
 * Lost Halls never closed (App ended). B's folder also holds a bag, a fame reading and a record tagged with A's {@code v1}:
 * none of them may join B's {@code v1}.</li>
 * </ul>
 */
public final class RunFixtures {
    public static final String A = HomeHistoryFixture.id("runs-a"), B = HomeHistoryFixture.id("runs-b");
    /** The scenario's clock: noon on the fixture day. */
    public static final long NOW = at(0, 12, 0);
    public static final String ACCOUNT = "c".repeat(64);
    public static final VisitRef A1 = new VisitRef(A, "v1"), A2 = new VisitRef(A, "v2"), A3 = new VisitRef(A, "v3"),
        B1 = new VisitRef(B, "v1"), B4 = new VisitRef(B, "v4");

    private RunFixtures() {}

    public static void write(Path root) throws IOException {
        session(root, A, at(0, 8, 0), at(0, 10, 0), new SessionStore.Interval(at(0, 8, 0), at(0, 10, 0), "Capture stopped"));
        ActivityJournal.Visit v1 = HomeHistoryFixture.visit("v1", "Lost Halls", at(0, 8, 5), at(0, 8, 30), true);
        v1.rosterSize = 6; v1.exaltIncrease = 2;
        HomeHistoryFixture.runs(root, A, v1, HomeHistoryFixture.visit("v2", "Snake Pit", at(0, 8, 35), at(0, 8, 50), false),
            HomeHistoryFixture.visit("v3", "Pirate Cave", at(0, 9, 0), 0, false), HomeHistoryFixture.visit("hub", "Nexus", at(0, 9, 40), at(0, 9, 45), false));
        HomeHistoryFixture.loot(root, A,
            LootTestDrops.drop("White", "Lost Halls", at(0, 8, 20), A1, item(501, POTION), item(502, UT)),
            LootTestDrops.drop("Orange", "Lost Halls", at(0, 8, 25), A1, item(503, PLAIN), item(504, TIERED), item(505, POTION), item(506, ST)),
            LootTestDrops.drop("White", "Snake Pit", at(0, 8, 45), A2, item(601, UT)),
            LootTestDrops.drop("Brown", "Pirate Cave", at(0, 9, 10), null, item(701, PLAIN)));   // no recorded visit
        HomeHistoryFixture.fame(root, A, fame(null, 10_000, at(0, 8, 1)), fame(A1, 10_100, at(0, 8, 10)), fame(A1, 10_240, at(0, 8, 25)),
            fame(A2, 10_240, at(0, 8, 40)));
        CombatRecord longest = record("r-v1-long", A1, 2, 300, 9_000, 6_000, 3_000, 1_500, 400, 100);
        longest.players.get(1).deaths = 1;
        CombatFixtures.writeRecord(root, A, longest);
        CombatFixtures.writeRecord(root, A, record("r-v1-short", A1, 2, 30, 900, 500));
        CombatFixtures.writeRecord(root, A, record("r-v2", A2, null, 120, 4_000, 3_000, 2_000, 1_000));

        session(root, B, at(0, 11, 0), 0);
        HomeHistoryFixture.runs(root, B, HomeHistoryFixture.visit("v1", "Ice Citadel", at(0, 11, 5), at(0, 11, 25), true),
            HomeHistoryFixture.visit("v4", "Lost Halls", at(0, 11, 30), 0, false));
        // Tagged with A's v1 but saved in B's folder: never B's v1 (another session, equal visit ID).
        HomeHistoryFixture.loot(root, B, LootTestDrops.drop("White", "Ice Citadel", at(0, 11, 20), A1, item(801, UT)));
        HomeHistoryFixture.fame(root, B, fame(null, 20_000, at(0, 11, 1)), fame(A1, 20_500, at(0, 11, 10)));
        CombatFixtures.writeRecord(root, B, record("r-foreign", A1, 1, 600, 50_000, 1_000));
    }

    /** A session folder whose metadata declares the runs module's capture intervals (none: coverage unknown). */
    public static void session(Path root, String id, long started, long ended, SessionStore.Interval... runs) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1); json.addProperty("id", id); json.addProperty("label", "Runs fixture");
        json.addProperty("version", "fixture"); json.addProperty("started", started); json.addProperty("ended", ended);
        if (runs.length > 0) {
            JsonObject availability = new JsonObject();
            availability.add("runs", SessionStore.JSON.toJsonTree(new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.PARTIAL,
                "Fixture", runs[0].from, runs[runs.length - 1].until, List.of(runs), false)));
            json.add("availability", availability);
        }
        Files.write(Files.createDirectories(root.resolve(id)).resolve("session.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A Wizard's fame reading of {@link #ACCOUNT}'s character 1, tagged with {@code visit} (null = untagged). */
    public static AppHistory.FameSample fame(VisitRef visit, long fame, long time) {
        return new AppHistory.FameSample(1, ACCOUNT, fame, time, "Wizard", visit, visit == null ? null : "Lost Halls");
    }

    /**
     * A card record of players 1..n with these damages ("Player1"…, ranked by damage descending then object ID, no deaths),
     * linked to {@code visit}; {@code local} is the verified local object ID or null (not verified).
     */
    public static CombatRecord record(String recordingId, VisitRef visit, Integer local, double windowSeconds, long... damages) {
        CombatRecord record = new CombatRecord();
        record.recordingId = recordingId; record.map = record.mapName = "Lost Halls";
        record.visitSession = visit == null ? null : visit.sessionId; record.visitId = visit == null ? null : visit.visitId;
        record.localObjectId = local; record.windowSeconds = windowSeconds;
        List<CombatRecord.PlayerLine> players = new ArrayList<>();
        for (int i = 0; i < damages.length; i++) {
            CombatRecord.PlayerLine line = new CombatRecord.PlayerLine();
            line.objectId = i + 1; line.name = "Player" + (i + 1); line.classType = CombatFixtures.CLASSES[i % CombatFixtures.CLASSES.length];
            line.damage = damages[i]; line.hits = damages[i] / 100; line.maxHit = 400; line.deaths = 0;
            line.local = local != null && local == line.objectId;
            players.add(line);
            record.totalDamage += damages[i];
        }
        players.sort(Comparator.comparingLong((CombatRecord.PlayerLine p) -> -p.damage).thenComparingInt(p -> p.objectId));
        for (int i = 0; i < players.size(); i++) players.get(i).rank = i + 1;
        record.players = players; record.contributors = players.size();
        if (local != null) record.localClassType = CombatFixtures.CLASSES[(local - 1) % CombatFixtures.CLASSES.length];
        return record;
    }
}
