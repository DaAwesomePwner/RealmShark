package tomato.history.encounter;

import java.util.ArrayList;
import java.util.List;

/**
 * The recap-sized part of one closed combat recording, saved in the {@code encounter-detail} history module keyed by
 * {@link #recordingId}: damage over time, damage by source, enemies grouped by type and the death list. A plain Gson
 * class (public no-arg constructor, {@link #schemaVersion}); null boxed fields mean unknown. Read one at a time through
 * {@link CombatFacts#detail}.
 */
public final class CombatDetail {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public String recordingId;
    /**
     * Epoch ms of bucket 0: the first tick, else the first timed hit (0 when neither exists). Bucket {@code i} covers
     * {@code [origin + i * bucketSeconds s, origin + (i + 1) * bucketSeconds s)}.
     */
    public long bucketOrigin;
    /** Bucket width: 1 s while the fight spans at most 1,800 seconds, else {@code ceil(seconds / 1800)} s. */
    public int bucketSeconds = 1;
    /** The verified local player plus the top 12 contributors, in rank order; values end at the last bucketed hit. */
    public List<Series> series = new ArrayList<>();
    /** Damage by source for every contributor, in rank order. */
    public List<PlayerSources> sources = new ArrayList<>();
    /** Enemies hit, grouped by object type, damage descending then type. */
    public List<EnemyType> enemies = new ArrayList<>();
    /** Death notifications in arrival order (no time or object ID is recorded for them). */
    public List<Death> deaths = new ArrayList<>();

    public CombatDetail() {}

    /** One player's outgoing damage per bucket. */
    public static final class Series {
        public int objectId;
        public boolean local;
        public int[] values = new int[0];

        public Series() {}
    }

    /** One contributor's outgoing damage grouped by source, largest first. */
    public static final class PlayerSources {
        public int objectId;
        public List<SourceLine> sources = new ArrayList<>();

        public PlayerSources() {}
    }

    /** Damage from one {@code DamageSource} (its enum name), with the items behind it, largest first. */
    public static final class SourceLine {
        public String source;
        public long damage, hits;
        /** Hits with a captured item or summon type ({@code DamageSource.itemOf} > 0); hits without one are only in the source total. */
        public List<ItemLine> items = new ArrayList<>();

        public SourceLine() {}
    }

    /** Damage from one item or summon object type. */
    public static final class ItemLine {
        public int itemId;
        public long damage, hits;

        public ItemLine() {}
    }

    /** Enemies of one object type. */
    public static final class EnemyType {
        public int type;
        /** The captured object name; null when unknown. */
        public String name;
        public int count;
        /** The largest captured MAX_HP of these objects; null when none was captured. */
        public Integer maxHp;
        public boolean boss;
        /** All recorded damage and hits on these objects, including hits without an owner. */
        public long damage, hits;

        public EnemyType() {}
    }

    /** One death notification: the parsed player name (null when it cannot be parsed) and the grave icon. */
    public static final class Death {
        public String name;
        public int graveIcon;

        public Death() {}
    }
}
