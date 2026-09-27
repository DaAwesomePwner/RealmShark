package tomato.gui.dps;

import java.util.*;
import tomato.backend.data.DpsSnapshot;
import tomato.backend.data.Entity;

/**
 * Home's compact reading of the live damage meter: the rows the Meters page shows for "All enemies" (whole encounter),
 * ranked by damage. Built off the EDT from an immutable {@link DpsSnapshot}; players without recorded damage are not on
 * the meter.
 */
public final class MeterSummary {
    /** {@code dps} is damage per second over the encounter's first-to-last-hit window; NaN when that window is zero (unknown). */
    public record Row(String name, int classId, String className, long damage, double dps, boolean local) {}

    public static final MeterSummary EMPTY = new MeterSummary(Collections.emptyList(), 0, 0, null);

    private final List<Row> top;
    private final int localRank, players;
    private final String map;

    private MeterSummary(List<Row> top, int localRank, int players, String map) {
        this.top = Collections.unmodifiableList(new ArrayList<>(top));
        this.localRank = localRank; this.players = players; this.map = map;
    }

    /** At most the requested number of rows, highest damage first. */
    public List<Row> top() { return top; }
    /** The local player's 1-based rank, or 0 when the local player has no recorded damage. */
    public int localRank() { return localRank; }
    /** Players with recorded damage. */
    public int players() { return players; }
    /** The snapshot's map name, or null. */
    public String map() { return map; }

    public static MeterSummary of(DpsSnapshot snapshot, int limit) {
        if (snapshot == null) return EMPTY;
        return of(Arrays.asList(snapshot.targets), snapshot.player, snapshot.map == null ? null : snapshot.map.name, limit);
    }

    static MeterSummary of(List<Entity> targets, Entity local, String map, int limit) {
        List<Entity> enemies = new ArrayList<>(targets);
        enemies.removeIf(Entity::isPlayerCharacter);
        CombatMeterData meter = new CombatMeterData(enemies, local, true);
        List<CombatMeterData.Row> ranked = new ArrayList<>();
        for (CombatMeterData.Row row : meter.rows) if (row.damage > 0) ranked.add(row);
        ranked.sort(Comparator.comparingLong((CombatMeterData.Row row) -> row.damage).reversed().thenComparingInt(row -> row.player.id));
        List<Row> top = new ArrayList<>();
        int localRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            CombatMeterData.Row row = ranked.get(i);
            boolean mine = row.player.isUser() || local != null && row.player.id == local.id;
            if (mine && localRank == 0) localRank = i + 1;
            if (i >= limit) continue;
            Double dps = meter.dps(row);
            String name = row.player.name();
            top.add(new Row(name == null || name.isEmpty() ? "Unknown player" : name, row.player.objectType, row.className(),
                row.damage, dps == null ? Double.NaN : dps, mine));
        }
        return new MeterSummary(top, localRank, ranked.size(), map);
    }
}
