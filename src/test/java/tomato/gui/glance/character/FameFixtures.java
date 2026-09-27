package tomato.gui.glance.character;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.history.AppHistory;
import tomato.history.SessionStore;

/**
 * Synthetic fame history for Sheet › Fame: hashed synthetic accounts (CharacterJournal.accountKey values, the only accounts a fame
 * sample keeps), fixed epoch times, no capture and no personal data.
 */
public final class FameFixtures {
    public static final String ACCOUNT = CharacterJournal.accountKey("fame-fixture"), OTHER = CharacterJournal.accountKey("fame-fixture-other");
    public static final int CHARACTER = 7;
    public static final String KEY = ACCOUNT + ":" + CHARACTER;
    public static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    /** The finished sessions' starts: FIRST at T0, SECOND a day later. */
    public static final long T0 = 1_700_000_000_000L, T1 = T0 + DAY;
    public static final String FIRST = id("first"), SECOND = id("second"), BROKEN = id("broken"), CORRUPT = id("corrupt");

    private FameFixtures() {}

    public static String id(String name) { return UUID.nameUUIDFromBytes(("fame-fixture:" + name).getBytes(StandardCharsets.UTF_8)).toString(); }

    /** A reading stamped with {@code account} (a journal account key; anything else is dropped by the sample to null). */
    public static AppHistory.FameSample sample(String account, int character, long fame, long time) {
        return new AppHistory.FameSample(character, account, fame, time, "Wizard");
    }

    /** A reading written before fame samples recorded the account. */
    public static AppHistory.FameSample legacy(int character, long fame, long time) { return new AppHistory.FameSample(character, fame, time, "Wizard"); }

    /**
     * Four saved sessions:
     * - FIRST (T0, 30 min): ACCOUNT #7 reads 1,000, 1,100, 1,050 (a decrease) and 1,250 over 12 minutes, then its fame-latest
     *   checkpoint 1,250 at 20 minutes (newer than its last line) and a stale checkpoint at 5 minutes (not newer: never added);
     *   OTHER #7 (the same character id on another account) reads 5,000 and 5,400; two untagged #7 readings and one undated one;
     *   ACCOUNT #8 reads 300.
     * - SECOND (T1, 15 min): ACCOUNT #7 reads 1,300 and 1,400 nine minutes apart; one untagged #7 reading.
     * - BROKEN: metadata that cannot be read.
     * - CORRUPT: readable metadata, a corrupt middle line in fame.jsonl (ACCOUNT #7 readings on either side).
     */
    public static void write(Path root) throws IOException {
        session(root, FIRST, T0, T0 + 30 * MINUTE);
        fame(root, FIRST, sample(ACCOUNT, 7, 1_000, T0 + MINUTE), sample(OTHER, 7, 5_000, T0 + 2 * MINUTE), legacy(7, 900, T0 + 3 * MINUTE),
            sample(ACCOUNT, 7, 1_100, T0 + 5 * MINUTE), sample(ACCOUNT, 8, 300, T0 + 6 * MINUTE), sample(ACCOUNT, 7, 1_050, T0 + 8 * MINUTE),
            legacy(7, 950, T0 + 9 * MINUTE), legacy(7, 960, -1), sample(OTHER, 7, 5_400, T0 + 10 * MINUTE), sample(ACCOUNT, 7, 1_250, T0 + 13 * MINUTE));
        latest(root, FIRST, KEY, sample(ACCOUNT, 7, 1_250, T0 + 21 * MINUTE));
        latest(root, FIRST, ACCOUNT + ":7-stale", sample(ACCOUNT, 7, 1_100, T0 + 5 * MINUTE));
        latest(root, FIRST, OTHER + ":7", sample(OTHER, 7, 5_400, T0 + 10 * MINUTE));
        session(root, SECOND, T1, T1 + 15 * MINUTE);
        fame(root, SECOND, sample(ACCOUNT, 7, 1_300, T1 + MINUTE), legacy(7, 1_000, T1 + 2 * MINUTE), sample(ACCOUNT, 7, 1_400, T1 + 10 * MINUTE));
        Files.createDirectories(root.resolve(BROKEN));
        Files.writeString(root.resolve(BROKEN).resolve("session.json"), "{broken");
        fame(root, BROKEN, sample(ACCOUNT, 7, 9_999, T0 - DAY));
        session(root, CORRUPT, T0 - DAY, T0 - DAY + HOUR);
        Files.write(root.resolve(CORRUPT).resolve("fame.jsonl"), List.of(SessionStore.JSON.toJson(sample(ACCOUNT, 7, 800, T0 - DAY + MINUTE)),
            "{not json", SessionStore.JSON.toJson(sample(ACCOUNT, 7, 850, T0 - DAY + 2 * MINUTE))), StandardCharsets.UTF_8);
    }

    public static void session(Path root, String id, long started, long ended) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1); json.addProperty("id", id); json.addProperty("label", "Fame fixture");
        json.addProperty("version", "fixture"); json.addProperty("started", started); json.addProperty("ended", ended);
        Files.write(Files.createDirectories(root.resolve(id)).resolve("session.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Appends readings to {@code session}'s fame.jsonl. */
    public static void fame(Path root, String session, AppHistory.FameSample... samples) throws IOException {
        List<String> lines = new ArrayList<>();
        for (AppHistory.FameSample sample : samples) lines.add(SessionStore.JSON.toJson(sample));
        Files.write(Files.createDirectories(root.resolve(session)).resolve("fame.jsonl"), lines, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** A fame-latest checkpoint, named as SessionStore names it (the UUID of its key). */
    public static void latest(Path root, String session, String key, AppHistory.FameSample sample) throws IOException {
        Path folder = Files.createDirectories(root.resolve(session).resolve("fame-latest"));
        Files.writeString(folder.resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".json"), SessionStore.JSON.toJson(sample));
    }

    /** One session's readings as the reader returns them: {@code timeThenFame} pairs, oldest first. */
    public static FameHistory.Session readings(String id, boolean current, long... timeThenFame) {
        List<FameHistory.Point> points = new ArrayList<>();
        for (int i = 0; i + 1 < timeThenFame.length; i += 2) points.add(new FameHistory.Point(timeThenFame[i], timeThenFame[i + 1]));
        return new FameHistory.Session(id, current, points);
    }

    public static FameHistory.Series series(int untagged, int unreadable, FameHistory.Session... sessions) {
        return new FameHistory.Series(List.of(sessions), untagged, unreadable);
    }

    /** The sheet's saved record of KEY: fame as the journal saved it (null: unknown), last seen at {@code lastSeen}. */
    public static CharacterRecord record(Long fame, long lastSeen) {
        CharacterRecord r = new CharacterRecord();
        r.key = KEY; r.account = ACCOUNT; r.characterId = CHARACTER; r.classId = 782; r.className = "Wizard";
        r.fame = fame; r.lastSeen = lastSeen;
        return r;
    }

    /** A character in game: {@code account} #{@code character} with live character fame {@code fame} (null: not captured). */
    public static LiveCharacter.Snapshot live(String account, int character, Long fame) {
        return new LiveCharacter.Snapshot(account, character, 782, "Sample", 0, 20, fame, null, null, null, null, null, null, null, null, T1);
    }
}
