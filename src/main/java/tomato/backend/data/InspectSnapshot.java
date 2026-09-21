package tomato.backend.data;

import packets.data.StatData;
import packets.data.enums.StatType;
import java.util.*;

/** Detached, allowlisted player data for the last observed loadout in a run. */
public final class InspectSnapshot {
    private static final StatType[] FIELDS = {StatType.NAME_STAT, StatType.GUILD_NAME_STAT,
            StatType.LEVEL_STAT, StatType.SKIN_ID, StatType.SEASONAL, StatType.CRUCIBLE_STAT,
            StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT,
            StatType.INVENTORY_3_STAT, StatType.UNIQUE_DATA_STRING};
    private final int id, objectType;
    private final int[] baseStats;
    private final StatData[] stats;
    private final long observedAt = System.currentTimeMillis();
    private final String className;

    public InspectSnapshot(Entity source) {
        id = source.id;
        objectType = source.objectType;
        className = tomato.realmshark.enums.CharacterClass.getName(objectType);
        baseStats = source.baseStats == null ? new int[]{-1, -1, -1, -1, -1, -1, -1, -1} : source.baseStats.clone();
        stats = new StatData[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) stats[i] = copy(source.stat.get(FIELDS[i]), FIELDS[i]);
    }

    private static StatData copy(StatData source, StatType type) {
        if (source == null) return null;
        StatData copy = new StatData();
        copy.statType = type; copy.statTypeNum = type.get();
        copy.statValue = source.statValue; copy.statValueTwo = source.statValueTwo;
        copy.stringStatValue = source.stringStatValue;
        return copy;
    }

    public Entity toEntity() {
        Entity entity = new Entity(null, id, 0);
        entity.markPlayerIdentity();
        entity.objectType = objectType; entity.baseStats = baseStats.clone();
        for (int i = 0; i < FIELDS.length; i++) if (stats[i] != null) entity.stat.set(FIELDS[i], copy(stats[i], FIELDS[i]));
        return entity;
    }

    public String key() {
        String name = stats[0] == null ? null : stats[0].stringStatValue;
        return playerKey(id, name);
    }

    public static String playerKey(int id, String name) {
        String normalized = name == null ? "" : name.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "object:" + id : "player:" + normalized;
    }
    public long observedAt() { return observedAt; }
    public String className() { return className; }

    public String anonymousKey() { return "object:" + id; }
    public int objectId() { return id; }

    public boolean sameDisplay(InspectSnapshot other) {
        if (other == null || id != other.id || objectType != other.objectType || !Arrays.equals(baseStats, other.baseStats)) return false;
        for (int i = 0; i < stats.length; i++) {
            StatData a = stats[i], b = other.stats[i];
            if (a == null || b == null) { if (a != b) return false; }
            else if (a.statValue != b.statValue || a.statValueTwo != b.statValueTwo || !Objects.equals(a.stringStatValue, b.stringStatValue)) return false;
        }
        return true;
    }

    public boolean isValid() {
        return baseStats != null && baseStats.length == 8 && stats != null && stats.length == FIELDS.length;
    }
}
