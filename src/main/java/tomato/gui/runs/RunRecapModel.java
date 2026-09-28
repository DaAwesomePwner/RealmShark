package tomato.gui.runs;

import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.kit.DisplayValue;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;

/**
 * The run recap's view model: one exact saved run with its linked combat recordings, loot, fame, players, resources,
 * timeline and evidence (spec §6.3). Built off the EDT by {@link RunRecapBuilder} from detached reads; the EDT only applies it
 * and never changes it. Every link is exact ({@link VisitRef} equality or the recording's own fields, never a name or a
 * time); unknown values are null, or a {@link DisplayValue} that shows "—" with its reason, never 0.
 *
 * <p>When the run is not in saved history, {@link #unavailable} holds the archive's wording and every section is null.
 *
 * @param capturedAt when the model was built (epoch ms), so a saved in-progress run can say how old its checkpoint read is
 * @param tiles      the tile row in {@link Tile} id order: your DPS, damage share, deaths, fame, loot, exalt progress
 * @param evidence   the Runs archive workbench's four sections as text ({@code RunWorkbench.text}), for Analyst
 */
public record RunRecapModel(VisitRef ref, String unavailable, long capturedAt, Header header, List<Tile> tiles, Damage damage,
                            Loot loot, Players players, Resources resources, Timeline timeline, String evidence) {
    public RunRecapModel {
        Objects.requireNonNull(ref, "ref");
        tiles = tiles == null ? List.of() : List.copyOf(tiles);
        if (unavailable == null && (header == null || damage == null || loot == null || players == null || resources == null
                || timeline == null || evidence == null)) throw new IllegalArgumentException("An available recap needs every section");
    }

    /** A run that is not in saved history: only the reason. */
    public static RunRecapModel unavailable(VisitRef ref, String reason, long capturedAt) {
        return new RunRecapModel(ref, Objects.requireNonNull(reason, "reason"), capturedAt, null, List.of(), null, null, null, null, null, null);
    }

    public boolean available() { return unavailable == null; }

    /** The tile with this id ({@link Tile#DPS} …), or null. */
    public Tile tile(String id) {
        for (Tile tile : tiles) if (tile.id().equals(id)) return tile;
        return null;
    }

    /**
     * The header facts of the visit record. {@code map} is the saved (canonical) map name, {@code mapName} its display text
     * ("Unknown area" when none was saved); {@code entered} null when no entry time was saved; {@code durationMs} the observed
     * span from entry to last seen (not a verified clear time), null when unknown; {@code partySize} the observed RotMG party
     * ({@code Visit.rosterSize}), null when no party was observed; {@code character} the class and character id of the fame
     * readings tagged with this run ("Wizard #3"), null when none was recorded.
     */
    public record Header(String map, String mapName, int portalId, RunOutcome outcome, Long entered, Long durationMs,
                         Integer partySize, String character) {
        public Header { Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(mapName, "mapName"); }
    }

    /**
     * One tile: a value with its source (known) or reason (unknown) in {@link DisplayValue#detail}, and an optional short
     * subline under the value ("#2 of 6", "1 UT · 2 potions"); null when there is none.
     */
    public record Tile(String id, String label, DisplayValue value, String subline) {
        /** Tile ids; the recap view names them {@code run-recap-tile-<id>}. */
        public static final String DPS = "dps", SHARE = "share", DEATHS = "deaths", FAME = "fame", LOOT = "loot", EXALT = "exalt";
        public Tile { Objects.requireNonNull(id, "id"); Objects.requireNonNull(label, "label"); Objects.requireNonNull(value, "value"); }
    }

    /**
     * The Damage section: one of the run's recordings (all contributors, as the meter records them). {@code recordings} are the
     * run's exactly linked recordings, longest first (the one cards, tiles and Home use); {@code selected} is the shown one's id,
     * null when none is linked. {@code rows} are its meter rows in rank order; {@code series} its damage over time (the verified
     * local player plus the top 12 contributors) in buckets of {@code bucketSeconds} s from {@code bucketOrigin} (epoch ms).
     * {@code total} and {@code unattributed} are the recording's totals; {@code windowSeconds} its shared first-to-last-hit
     * window (null: DPS unknown). {@code hitsBeforeFirstTick} were counted in the totals but not bucketed.
     *
     * <p>Reasons (null when not applicable): {@code reason} why there are no rows (no recording linked, or no player damage);
     * {@code localReason} why no row is marked as yours; {@code detailReason} why the damage over time and the damage by
     * source are missing (the recording's detail is not saved or unreadable).
     */
    public record Damage(List<Recording> recordings, String selected, List<Row> rows, List<Series> series, int bucketSeconds,
                         long bucketOrigin, long total, long unattributed, Double windowSeconds, int hitsBeforeFirstTick,
                         String reason, String localReason, String detailReason) {
        public Damage {
            recordings = List.copyOf(recordings); rows = List.copyOf(rows); series = List.copyOf(series);
            if (bucketSeconds < 1) throw new IllegalArgumentException("bucketSeconds must be positive");
        }

        /** The shown recording, or null when none is linked. */
        public Recording selectedRecording() {
            for (Recording recording : recordings) if (recording.id().equals(selected)) return recording;
            return null;
        }
        /** The verified local player's row, or null. */
        public Row local() {
            for (Row row : rows) if (row.local()) return row;
            return null;
        }

        /** One recording linked to this run, for the picker; {@code entered} and {@code windowSeconds} null when unknown. */
        public record Recording(String id, Long entered, Double windowSeconds, int contributors) {
            public Recording { Objects.requireNonNull(id, "id"); }
        }

        /**
         * One contributor's meter row. {@code dps} null without a hit window; {@code share} null without recorded damage;
         * {@code taken} null when incoming damage was unavailable for this row; {@code deaths} null when the name is unknown or
         * shared by another row of this recording (deaths are matched by name inside one recording only). {@code sources} is
         * empty when the recording's detail is missing.
         */
        public record Row(int objectId, String name, int classType, long damage, Double dps, Double share, long hits, long maxHit,
                          Long taken, Integer deaths, boolean local, int rank, List<Source> sources) {
            public Row { sources = List.copyOf(sources); }
        }

        /** Damage from one source ({@code DamageSource} name and label), with the items behind it, largest first. */
        public record Source(String source, String label, long damage, long hits, List<Item> items) {
            public Source { items = List.copyOf(items); }
        }

        /** Damage from one item or summon object type. */
        public record Item(int itemId, long damage, long hits) {}

        /** One player's outgoing damage per bucket; {@code name} null when none was captured. */
        public record Series(int objectId, String name, int classType, boolean local, int[] values) {
            public Series { values = values == null ? new int[0] : values.clone(); }
            @Override public int[] values() { return values.clone(); }
            @Override public boolean equals(Object other) {
                return other instanceof Series s && objectId == s.objectId && Objects.equals(name, s.name) && classType == s.classType
                    && local == s.local && Arrays.equals(values, s.values);
            }
            @Override public int hashCode() { return Objects.hash(objectId, name, classType, local, Arrays.hashCode(values)); }
        }
    }

    /**
     * The Loot section: bags whose drop recorded exactly this run, oldest first. {@code count} is their items, {@code summary}
     * "1 UT · 2 potions" ("" when none of those kinds); {@code reason} says why there are no bags (null when there are).
     */
    public record Loot(List<Bag> bags, int count, String summary, String reason) {
        public Loot { bags = List.copyOf(bags); Objects.requireNonNull(summary, "summary"); }

        /** One bag; {@code bag} is its saved bag name ("White", "Orange", …) or null when none was saved. */
        public record Bag(String bag, long time, List<LootFacts.Item> items) {
            public Bag { items = List.copyOf(items); }
        }
    }

    /**
     * The Players section: the last-recorded loadouts of the players seen in this run (Inspect), with the run's own Inspect
     * damage, which covers inspected players only and is labeled {@link #INSPECT_DAMAGE}. {@code reason} says why there are
     * none; {@code damageReason} why Inspect damage is unknown (null when it was tracked).
     */
    public record Players(List<Player> players, int inspectedPlayerCount, String reason, String damageReason) {
        public static final String INSPECT_DAMAGE = "Inspect damage (inspected players only)";
        public Players { players = List.copyOf(players); }

        /**
         * One player's last-recorded loadout. {@code name} null when none was recorded; {@code equipment} the four equipped
         * item ids (weapon, ability, armor, ring), an entry null when that slot was not captured, -1 when it was empty;
         * {@code inspectDamage} null when Inspect damage was not tracked for this run.
         */
        public record Player(int objectId, String name, String className, int classType, List<Integer> equipment, Long inspectDamage,
                             long observedAt) {
            public Player { equipment = Collections.unmodifiableList(new ArrayList<>(equipment)); }
        }
    }

    /**
     * The Resources section: the full saved visit for {@code CombatTimelineChart.setVisit}, or null with the reason when it
     * holds no resource or condition samples (old aggregate-only visits). The visit is a detached read; nothing changes it.
     */
    public record Resources(ActivityJournal.Visit visit, String reason) {
        public Resources { if ((visit == null) == (reason == null)) throw new IllegalArgumentException("Either a visit or a reason"); }
    }

    /**
     * The Timeline section: saved events of exactly this session and visit id, oldest first, at most
     * {@code RunRecapBuilder.EVENT_LIMIT}; {@code total} counts all of them; {@code reason} says why there are none.
     */
    public record Timeline(List<Event> events, int total, String reason) {
        public Timeline { events = List.copyOf(events); }

        /** One event: its time, kind and plain-language text ({@code ActivitySummaries.event}). */
        public record Event(long time, String kind, String text) {}
    }
}
