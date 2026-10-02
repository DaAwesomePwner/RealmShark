package tomato.realmshark;

import assets.IdToAsset;
import com.google.gson.Gson;
import util.PropertiesManager;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Realm world-boss alerts from two passive signals: the quest arrow moving to a listed boss (QUESTOBJID, the
 * closest sign of a fresh spawn) and a listed boss entering view in the client's updates. Each boss object (ID and
 * type, since the server may reuse an ID) alerts once per map visit, and the same boss name pauses for 30 s so multi-part events (towers, statues)
 * play once. Bosses are recognised by asset name from an editable list; nothing is persisted but that list.
 */
public final class WorldBossAlerts {
    public static final String REALM = "Realm of the Mad God";
    static final String KEY = "sound.worldboss.names";
    private static final long COOLDOWN_NANOS = 30_000_000_000L;
    private static final int MAX_NAMES = 64, MAX_NAME = 80, MAX_OUT_OF_VIEW = 50_000;
    /** Realm event bosses by asset name. Edit the list in Notifications when the game renames or adds one. */
    public static final List<String> DEFAULT_NAMES = Collections.unmodifiableList(Arrays.asList(
        "Cube God", "Skull Shrine", "Pentaract Tower", "Grand Sphinx", "Lord of the Lost Lands", "Hermit God",
        "Ghost Ship", "Dragon Head", "Lucky Ent God", "Lucky Djinn", "Avatar of the Forgotten King",
        "Killer Bee Nest", "Killer Bee Queen", "Jade Statue", "Garnet Statue", "Crystal Prisoner", "Legion General"));
    /** Declared after the defaults it reads. */
    public static final WorldBossAlerts INSTANCE = new WorldBossAlerts();

    public enum Signal { QUEST, VIEW }

    private final IntFunction<List<String>> namesOf;
    private final Sound sound;
    private volatile List<String> names;
    private final Set<String> normalized = new HashSet<>();
    /** Keys of {@link #key(int, int)}: boss objects alerted and unlisted quest targets recorded on this map. */
    private final Set<Long> alerted = new HashSet<>(), unlisted = new HashSet<>();
    /** IDs that left view; their last known type may be stale if the server reuses the ID. */
    private final Set<Integer> outOfView = new HashSet<>();
    private final Map<String, Long> lastByName = new HashMap<>();
    private boolean inRealm;
    private int pendingQuest = -1;
    private volatile String lastMatchLabel = "No world boss alert this session.";

    WorldBossAlerts() { this(WorldBossAlerts::assetNames, Sound.worldBoss); }
    WorldBossAlerts(IntFunction<List<String>> namesOf, Sound sound) {
        this.namesOf = namesOf; this.sound = sound;
        List<String> loaded = null;
        String saved = PropertiesManager.getProperty(KEY);
        if (saved != null) {
            try { String[] parsed = new Gson().fromJson(saved, String[].class); if (parsed != null) loaded = clean(Arrays.asList(parsed)); }
            catch (RuntimeException ignored) { }
        }
        apply(loaded == null ? DEFAULT_NAMES : loaded);
    }
    private static List<String> assetNames(int type) {
        List<String> result = new ArrayList<>(2);
        String id = IdToAsset.getObjectIdName(type), display = IdToAsset.getDisplayName(type);
        if (id != null && !id.isEmpty()) result.add(id);
        if (display != null && !display.isEmpty() && !display.equals(id)) result.add(display);
        return result;
    }
    static String normalize(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim(); }
    /** Trims, drops blanks and case-insensitive duplicates; rejects overlong names and lists. */
    static List<String> clean(Collection<String> input) {
        List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (String name : input) {
            if (name == null || name.trim().isEmpty()) continue;
            String trimmed = name.trim().replaceAll("\\s+", " ");
            if (trimmed.length() > MAX_NAME) throw new IllegalArgumentException("Boss names are limited to " + MAX_NAME + " characters.");
            if (seen.add(normalize(trimmed))) result.add(trimmed);
        }
        if (result.size() > MAX_NAMES) throw new IllegalArgumentException("Up to " + MAX_NAMES + " world boss names are supported.");
        return result;
    }
    private synchronized void apply(List<String> list) {
        names = Collections.unmodifiableList(new ArrayList<>(list));
        normalized.clear(); for (String name : list) normalized.add(normalize(name));
    }
    public List<String> getNames() { return names; }
    /** Replaces the boss list (one name per line in the editor) and saves it. */
    public void setNames(Collection<String> input) {
        List<String> list = clean(input);
        apply(list); PropertiesManager.setProperties(KEY, new Gson().toJson(list));
    }
    public String getLastMatchLabel() { return lastMatchLabel; }
    public Sound sound() { return sound; }

    /** A new map: alerts reset, and only the Realm is watched. */
    public synchronized void enterMap(String mapName) {
        inRealm = REALM.equals(mapName); alerted.clear(); unlisted.clear(); outOfView.clear(); lastByName.clear(); pendingQuest = -1;
    }
    /** The quest arrow moved to {@code objectId}; {@code knownType} is its object type when already seen, otherwise null. */
    public void questTarget(int objectId, Integer knownType) {
        Hit hit = onQuest(objectId, knownType, System.nanoTime());
        if (hit != null) sound.play(hit.decision);
    }
    /** An object entered view in the client's updates (each UPDATE new object, including returns and reused IDs). */
    public void objectAppeared(int objectId, int type) {
        Hit hit = onAppeared(objectId, type, System.nanoTime());
        if (hit != null) sound.play(hit.decision);
    }
    /** An object left view. */
    public synchronized void objectDropped(int objectId) {
        if (!inRealm) return;
        if (outOfView.size() >= MAX_OUT_OF_VIEW) outOfView.clear(); // Only a staleness hint; losing it is harmless.
        outOfView.add(objectId);
    }
    private static long key(int objectId, int type) { return ((long) objectId << 32) | (type & 0xffffffffL); }

    static final class Hit {
        final String name; final Signal signal; final long decision;
        Hit(String name, Signal signal, long decision) { this.name = name; this.signal = signal; this.decision = decision; }
    }
    synchronized Hit onQuest(int objectId, Integer knownType, long now) {
        if (!inRealm) return null;
        if (objectId < 0) { pendingQuest = -1; return null; }
        // An unknown type waits for the object to enter view. A type last seen before the object left view is used now
        // but re-checked when it returns, in case the ID now belongs to another object.
        pendingQuest = knownType == null || outOfView.contains(objectId) ? objectId : -1;
        return knownType == null ? null : evaluate(objectId, knownType, Signal.QUEST, now);
    }
    synchronized Hit onAppeared(int objectId, int type, long now) {
        if (!inRealm) return null;
        outOfView.remove(objectId);
        if (objectId == pendingQuest) { pendingQuest = -1; return evaluate(objectId, type, Signal.QUEST, now); }
        return listedName(type) == null ? null : evaluate(objectId, type, Signal.VIEW, now);
    }
    private String listedName(int type) {
        List<String> candidates = namesOf.apply(type);
        if (candidates != null) for (String candidate : candidates) if (candidate != null && normalized.contains(normalize(candidate))) return candidate;
        return null;
    }
    /** Decision point: records the quest target, its list match, repeats, the alert switch and the name cooldown. */
    private Hit evaluate(int objectId, int type, Signal signal, long now) {
        String name = listedName(type);
        if (name == null) {
            // Only quest moves reach here unlisted. Each target is recorded once per map so retargeting cannot crowd out other no-matches.
            if (!unlisted.add(key(objectId, type))) return null;
            List<String> candidates = namesOf.apply(type);
            String shown = candidates == null || candidates.isEmpty() ? "Object type " + type : candidates.get(0);
            AlertDecisions.INSTANCE.record(new AlertDecisions.Entry(AlertDecisions.Source.WORLD_BOSS).result(AlertDecisions.Result.NO_MATCH)
                .subject(shown + " · quest target").sample(shown, type)
                .explain("Your quest arrow moved to " + shown + ", which is not on the world boss list. Add its name in Realm events to alert for it."));
            return null;
        }
        if (!alerted.add(key(objectId, type))) return null;
        String where = signal == Signal.QUEST ? "quest target" : "in view";
        AlertDecisions.Entry entry = new AlertDecisions.Entry(AlertDecisions.Source.WORLD_BOSS).sound(sound)
            .subject(name + " · " + where).sample(name, type);
        String seen = signal == Signal.QUEST
            ? "Your quest arrow moved to " + name + ": it just spawned, or was already up when you arrived."
            : name + " came into view; it may have spawned earlier.";
        if (!sound.isEnabled()) {
            AlertDecisions.INSTANCE.record(entry.result(AlertDecisions.Result.SOUND_OFF).explain(seen + " World boss alerts are turned off."));
            return null;
        }
        String key = normalize(name);
        Long last = lastByName.get(key);
        if (last != null && now - last < COOLDOWN_NANOS) {
            AlertDecisions.INSTANCE.record(entry.result(AlertDecisions.Result.COOLDOWN).explain(seen + " Another " + name + " alerted "
                + Math.max(0, (now - last) / 1_000_000_000L) + " s ago; the same boss pauses for 30 s."));
            return null;
        }
        lastByName.put(key, now);
        lastMatchLabel = "Last world boss: " + name + " (" + where + ") at " + java.time.LocalTime.now().withNano(0) + ".";
        return new Hit(name, signal, AlertDecisions.INSTANCE.record(entry.explain(seen)));
    }
}
