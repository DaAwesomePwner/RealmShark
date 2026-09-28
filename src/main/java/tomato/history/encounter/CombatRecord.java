package tomato.history.encounter;

import java.util.ArrayList;
import java.util.List;
import tomato.history.link.VisitRef;

/**
 * Card-sized summary of one closed combat recording, saved in the {@code encounters} history module keyed by
 * {@link #recordingId}. A plain Gson class: the public no-arg constructor keeps the initializers for fields an older
 * file lacks, and {@code SessionStore.JSON} omits nulls, so every boxed field that is null (absent) means unknown,
 * never zero. Built by {@code tomato.gui.dps.CombatSummaries} with the damage meter's definitions; read through
 * {@link CombatFacts}. Written once and never rewritten, so an older build ignores fields a newer one adds.
 */
public final class CombatRecord {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** The recording's own ID ({@code DpsData.getRecordingId()}); records without one are never read. */
    public String recordingId;
    /** MapInfo name and display name of the area, as captured; null when the recording has no map. */
    public String map, mapName;
    /** MAPINFO entry time (the encounter context's capture time, epoch ms); null for legacy recordings without context. */
    public Long enteredAt;
    /** First tick (epoch ms) and first-to-last tick span (ms); both null when no tick was recorded. */
    public Long startedAt, elapsedMs;
    /** First and last recorded hit on an enemy (epoch ms), as the meter bounds its window; null without timed hits. */
    public Long firstHitAt, lastHitAt;
    /** The meter's shared first-to-last-hit window in seconds (the same for every player); null without a positive window. */
    public Double windowSeconds;
    /** The exact visit verified at entry, flattened; both or neither. Use {@link #visit()}, which re-validates. */
    public String visitSession, visitId;
    /** The verified local player's object ID inside this recording; null when not verified (another row is never used). */
    public Integer localObjectId;
    /** The verified local player's class type; null when the local player is not verified or its class is unknown. */
    public Integer localClassType;
    /** All recorded damage on enemies, including hits without an owner ({@link #unattributedDamage}). */
    public long totalDamage, unattributedDamage;
    /** Players with recorded damage (the size of {@link #players}). */
    public int contributors;
    /** Every contributor, damage descending then object ID (the meter summary's ranking). */
    public List<PlayerLine> players = new ArrayList<>();
    /** Death notifications recorded in this recording (named or not). */
    public int deaths;
    /** Enemy objects hit and their distinct types. */
    public int enemies, enemyTypes;
    /** Boss and miniboss objects hit, damage descending then type and object order. */
    public List<Boss> bosses = new ArrayList<>();
    /** Hits stamped before the first tick (time -1): counted in every total, not placed in damage-over-time buckets. */
    public int hitsBeforeFirstTick;
    /** Whether a full-detail file was saved beside this record. */
    public boolean fullDetail;

    public CombatRecord() {}

    /** The exact visit this recording was entered in, or null unless both identities are present and non-empty. */
    public VisitRef visit() {
        return visitSession == null || visitId == null || visitSession.isEmpty() || visitId.isEmpty() ? null : new VisitRef(visitSession, visitId);
    }

    /** The verified local player's row, or null (not verified, or the verified local player has no recorded damage). */
    public PlayerLine local() {
        if (localObjectId == null || players == null) return null;
        for (PlayerLine line : players) if (line != null && line.local && line.objectId == localObjectId) return line;
        return null;
    }

    /**
     * The verified local player's recorded damage: its row's damage, 0 when it is verified but has no row (no recorded
     * damage, as {@code RecordedEncounter.localDamage}), or null when the local player is not verified.
     */
    public Long localDamage() {
        if (localObjectId == null) return null;
        PlayerLine local = local();
        return local == null ? 0L : local.damage;
    }

    /** The meter's DPS of a row: damage over the shared window, or null when there is no positive window. */
    public Double dps(PlayerLine line) {
        return line == null || windowSeconds == null || !(windowSeconds > 0) || windowSeconds.isInfinite() ? null : line.damage / windowSeconds;
    }

    /** The meter's recorded damage share of a row in percent (unattributed damage in the denominator), or null without damage. */
    public Double share(PlayerLine line) {
        return line == null || totalDamage <= 0 ? null : line.damage * 100.0 / totalDamage;
    }

    /** One contributor's meter row. */
    public static final class PlayerLine {
        public int objectId;
        /** The recorded player name; null when none was captured. */
        public String name;
        public int classType;
        public long damage, hits, maxHit;
        /** Recorded incoming damage over the whole recording; null when incoming damage is unavailable for this row. */
        public Long taken;
        /** Whether this is the verified local player's row. */
        public boolean local;
        /** Death notifications naming this player; null when the name is unknown or shared with another row here. */
        public Integer deaths;
        /** 1-based position in {@link CombatRecord#players}. */
        public int rank;

        public PlayerLine() {}
    }

    /** A boss or miniboss object hit in this recording. */
    public static final class Boss {
        public int type;
        /** The captured object name; null when unknown. */
        public String name;
        /** Maximum HP when the MAX_HP stat was captured, else null. */
        public Integer maxHp;
        /** All recorded damage on this object. */
        public long damage;

        public Boss() {}
    }
}
