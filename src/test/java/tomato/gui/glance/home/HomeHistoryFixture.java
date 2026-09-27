package tomato.gui.glance.home;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.stats.LootTestDrops;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import static tomato.gui.stats.LootTestDrops.Kind.*;
import static tomato.gui.stats.LootTestDrops.item;

/**
 * Synthetic saved history for Home. Normal: three sessions around one local midnight (America/New_York) with
 * dungeon and hub visits, exactly linked and unlinked loot, fame readings. Large: sessions x runs for timing.
 */
public final class HomeHistoryFixture {
    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    public static final LocalDate DAY = LocalDate.of(2025, 1, 15);
    public static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    public static final long MIDNIGHT = at(0, 0, 0), NOW = at(0, 10, 0);
    public static final String YESTERDAY = id("yesterday"), ACROSS = id("across-midnight"), MORNING = id("morning");
    public static final int LARGE_SESSIONS = 30, LARGE_RUNS = 40;
    private static final String[] DUNGEONS = {"Lost Halls", "Ice Citadel", "Pirate Cave", "Snake Pit"};

    private HomeHistoryFixture() {}

    /** Local wall-clock time on DAY + dayOffset as epoch milliseconds. */
    public static long at(int dayOffset, int hour, int minute) {
        return DAY.plusDays(dayOffset).atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }
    public static String id(String name) { return UUID.nameUUIDFromBytes(("home-fixture:" + name).getBytes(StandardCharsets.UTF_8)).toString(); }
    public static VisitRef ref(String session, String visit) { return new VisitRef(session, visit); }
    /** {@code ended} 0 = never ended; the loader closes it at lastSeen because its session ended. */
    public static ActivityJournal.Visit visit(String id, String map, long started, long ended, boolean completed) {
        ActivityJournal.Visit visit = new ActivityJournal.Visit();
        visit.id = id; visit.map = map; visit.started = started; visit.ended = ended;
        visit.lastSeen = ended > 0 ? ended : started + 20 * MINUTE;
        if (completed) { visit.completionEvidence = "Server victory"; visit.completionObservedAt = visit.lastSeen; }
        return visit;
    }
    public static AppHistory.FameSample sample(int character, long fame, long time) { return new AppHistory.FameSample(character, fame, time, "Wizard"); }

    public static void write(Path root) throws IOException {
        session(root, YESTERDAY, at(-1, 20, 0), at(-1, 22, 0));
        runs(root, YESTERDAY, visit("y1", "Pirate Cave", at(-1, 20, 5), at(-1, 20, 25), true),
            visit("y2", "Snake Pit", at(-1, 20, 40), at(-1, 21, 10), false), visit("yn", "Nexus", at(-1, 21, 15), at(-1, 21, 20), false));
        loot(root, YESTERDAY, LootTestDrops.drop("White", "Pirate Cave", at(-1, 20, 20), ref(YESTERDAY, "y1"), item(101, UT)));
        fame(root, YESTERDAY, sample(1, 1_000, at(-1, 20, 0)), sample(1, 1_200, at(-1, 21, 0)));

        session(root, ACROSS, at(-1, 23, 0), at(0, 2, 0));
        runs(root, ACROSS, visit("b1", "Snake Pit", at(-1, 23, 30), at(-1, 23, 50), true),
            visit("b2", "Lost Halls", at(0, 0, 30), at(0, 1, 20), true), visit("bn", "Nexus", at(0, 1, 30), at(0, 1, 40), false));
        loot(root, ACROSS, LootTestDrops.drop("B.White", "Snake Pit", at(-1, 23, 45), ref(ACROSS, "b1"), item(201, ST)),
            LootTestDrops.drop("White", "Lost Halls", at(0, 0, 50), ref(ACROSS, "b2"), item(203, POTION), item(202, UT)));
        fame(root, ACROSS, sample(1, 1_300, at(-1, 23, 10)), sample(1, 1_400, at(0, 0, 20)), sample(1, 1_500, at(0, 1, 50)));

        session(root, MORNING, at(0, 8, 0), at(0, 9, 30));
        runs(root, MORNING, visit("c1", "Ice Citadel", at(0, 8, 10), at(0, 8, 35), true),
            visit("c2", "Lost Halls", at(0, 8, 40), at(0, 9, 0), false), visit("c3", "Pirate Cave", at(0, 9, 5), 0, false));
        loot(root, MORNING,
            LootTestDrops.drop("Orange", "Lost Halls", at(0, 8, 55), ref(MORNING, "c2"), item(303, PLAIN), item(302, POTION), item(301, TIERED)),
            LootTestDrops.drop("B.White", "Lost Halls", at(0, 8, 58), ref(MORNING, "c9"), item(304, ST)),   // same map and time, another visit
            LootTestDrops.drop("Brown", "Lost Halls", at(0, 8, 59), null, item(305, PLAIN)));             // no recorded visit
        fame(root, MORNING, sample(1, 1_500, at(0, 8, 0)), sample(2, 20, at(0, 8, 5)), sample(1, 1_450, at(0, 8, 30)),
            sample(1, 1_600, at(0, 9, 0)), sample(2, 80, at(0, 9, 20)));
    }

    /** Session s runs 2 h and starts (4s + 2) h before NOW; every run has one bag and one fame reading. */
    public static void writeLarge(Path root, int sessions, int runs) throws IOException {
        for (int s = 0; s < sessions; s++) {
            String session = id("large-" + s);
            long start = NOW - (4L * s + 2) * HOUR;
            session(root, session, start, start + 2 * HOUR);
            List<ActivityJournal.Visit> visits = new ArrayList<>(); List<Object> drops = new ArrayList<>(); List<AppHistory.FameSample> samples = new ArrayList<>();
            for (int r = 0; r < runs; r++) {
                long begin = start + r * 3 * MINUTE; String visitId = "L" + s + "-" + r, map = DUNGEONS[r % DUNGEONS.length];
                visits.add(visit(visitId, map, begin, begin + 150_000, r % 2 == 0));
                drops.add(LootTestDrops.drop(r % 10 == 0 ? "White" : "Orange", map, begin + 120_000, ref(session, visitId),
                    item(10_000 + r, r % 10 == 0 ? UT : TIERED), item(20_000 + r, POTION), item(30_000 + r, PLAIN)));
                samples.add(new AppHistory.FameSample(1 + s % 3, 10_000L * s + 25L * r, begin + 150_000, "Knight"));
            }
            runs(root, session, visits.toArray(new ActivityJournal.Visit[0]));
            loot(root, session, drops.toArray());
            fame(root, session, samples.toArray(new AppHistory.FameSample[0]));
        }
    }

    public static void session(Path root, String id, long started, long ended) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1); json.addProperty("id", id); json.addProperty("label", "Home fixture");
        json.addProperty("version", "fixture"); json.addProperty("started", started); json.addProperty("ended", ended);
        Files.write(Files.createDirectories(root.resolve(id)).resolve("session.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static void runs(Path root, String session, ActivityJournal.Visit... visits) throws IOException {
        Path folder = Files.createDirectories(root.resolve(session).resolve("runs"));
        for (ActivityJournal.Visit visit : visits)
            Files.write(folder.resolve(UUID.nameUUIDFromBytes(visit.id.getBytes(StandardCharsets.UTF_8)) + ".json"),
                SessionStore.JSON.toJson(visit).getBytes(StandardCharsets.UTF_8));
    }
    public static void loot(Path root, String session, Object... drops) throws IOException { lines(root.resolve(session).resolve("loot.jsonl"), drops); }
    public static void fame(Path root, String session, AppHistory.FameSample... samples) throws IOException {
        lines(root.resolve(session).resolve("fame.jsonl"), (Object[]) samples);
    }
    private static void lines(Path file, Object... values) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Object value : values) lines.add(SessionStore.JSON.toJson(value));
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
