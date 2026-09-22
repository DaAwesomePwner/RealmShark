package tomato.gui.security;

import assets.IdToAsset;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.realmshark.enums.CharacterClass;

import java.util.Arrays;

public class Player {
    int[] inv = new int[4];
    String[] itemName = new String[4];
    Entity playerEntity;

    public final static String[] equipmentNames = {"weapon", "ability", "armor", "ring"};
    private final static String[] statNames = new String[]{"HP", "MP", "Atk", "Def", "Spd", "Dex", "Vit", "Wis"};

    public Player(Entity playerEntity) {
        this.playerEntity = playerEntity;
        this.updateInv();
    }

    public boolean updateInv() {
        int[] newInv = new int[4];

        for (int i = 0; i < newInv.length; i++) {
            packets.data.StatData value = playerEntity.stat.get(StatType.INVENTORY_0_STAT.get() + i);
            newInv[i] = value == null ? -1 : value.statValue;
        }

        boolean didInvChange = !Arrays.equals(inv, newInv);
        this.inv = newInv;

        return didInvChange;
    }

    /**
     * Computes the missing pots needed to max the character.
     */
    public int[] statMissing() {
        Entity player = this.playerEntity;
        if (!CharacterClass.hasStats(player.objectType) || player.baseStats == null || player.baseStats.length != 8) return new int[]{-1,-1,-1,-1,-1,-1,-1,-1};
        int[] stats = new int[8];
        stats[0] = (int) Math.ceil((CharacterClass.getLife(player.objectType) - player.baseStats[0]) / 5.0);
        stats[1] = (int) Math.ceil((CharacterClass.getMana(player.objectType) - player.baseStats[1]) / 5.0);
        stats[2] = CharacterClass.getAtk(player.objectType) - player.baseStats[2];
        stats[3] = CharacterClass.getDef(player.objectType) - player.baseStats[3];
        stats[4] = CharacterClass.getSpd(player.objectType) - player.baseStats[4];
        stats[5] = CharacterClass.getDex(player.objectType) - player.baseStats[5];
        stats[6] = CharacterClass.getVit(player.objectType) - player.baseStats[6];
        stats[7] = CharacterClass.getWis(player.objectType) - player.baseStats[7];
        for (int i = 0; i < stats.length; i++) stats[i] = baseStat(i) < 0 ? -1 : Math.max(0, stats[i]);
        return stats;
    }

    /**
     * Gets the characters maxed stat count.
     */
    public int statsMaxed() {
        Entity player = this.playerEntity;
        if (!CharacterClass.hasStats(player.objectType) || capturedStatCount() != 8) return -1;
        int outOf8 = 0;
        if (CharacterClass.getLife(player.objectType) == player.baseStats[0]) outOf8++;
        if (CharacterClass.getMana(player.objectType) == player.baseStats[1]) outOf8++;
        if (CharacterClass.getAtk(player.objectType) == player.baseStats[2]) outOf8++;
        if (CharacterClass.getDef(player.objectType) == player.baseStats[3]) outOf8++;
        if (CharacterClass.getSpd(player.objectType) == player.baseStats[4]) outOf8++;
        if (CharacterClass.getDex(player.objectType) == player.baseStats[5]) outOf8++;
        if (CharacterClass.getVit(player.objectType) == player.baseStats[6]) outOf8++;
        if (CharacterClass.getWis(player.objectType) == player.baseStats[7]) outOf8++;

        return outOf8;
    }

    public String statsDescription() {
        StringBuilder text = new StringBuilder("Base stats · ").append(statsMaxed() < 0 ? "Maxed total unavailable" : statsMaxed() + " / 8 maxed")
            .append(" · ").append(capturedStatCount()).append(" / 8 captured");
        int[] missing = statMissing();
        for (int i = 0; i < statNames.length; i++) {
            text.append('\n').append(statNames[i]).append(": ");
            if (baseStat(i) < 0) text.append("Not captured");
            else { text.append(baseStat(i));if(missing[i]>=0)text.append(" (potions to max: ").append(missing[i]).append(')'); }
        }
        return text.toString();
    }

    /**
     * Computes the skin ID for a player.
     */
    public int getSkinId() {
        Entity player = this.playerEntity;
        int skinId = player.stat.get(StatType.SKIN_ID) == null ? 0 : player.stat.get(StatType.SKIN_ID).statValue;
        if (skinId == 0) skinId = player.objectType;

        return skinId;
    }

    private int baseStat(int index) {
        return playerEntity.baseStats == null || index >= playerEntity.baseStats.length ? -1 : playerEntity.baseStats[index];
    }

    public int capturedStatCount() {
        int count = 0;
        for (int i = 0; i < 8; i++) if (baseStat(i) >= 0) count++;
        return count;
    }

    public static Boolean seasonal(Entity entity) {
        packets.data.StatData value = entity.stat.get(StatType.SEASONAL);
        return value == null || (value.statValue != 0 && value.statValue != 1) ? null : value.statValue == 1;
    }

    public static Boolean crucible(Entity entity) {
        packets.data.StatData value = entity.stat.get(StatType.CRUCIBLE_STAT);
        return value == null || value.stringStatValue == null ? null : !value.stringStatValue.isEmpty();
    }

    public static String modeDescription(Entity entity) {
        Boolean seasonal = seasonal(entity), crucible = crucible(entity);
        return (seasonal == null ? "Seasonal: Not captured" : seasonal ? "Seasonal" : "Non-seasonal")
            + " · " + (crucible == null ? "Crucible: Not captured" : crucible ? "Crucible" : "Not Crucible");
    }

    public String toString() {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject(), equipment = new com.google.gson.JsonObject(), deficits = new com.google.gson.JsonObject();
        json.addProperty("name", playerEntity.name());
        json.addProperty("class", CharacterClass.getName(playerEntity.objectType));
        packets.data.StatData level = playerEntity.stat.get(StatType.LEVEL_STAT);
        json.addProperty("level", level == null ? null : level.statValue);
        json.addProperty("guild", playerEntity.getStatGuild());
        json.addProperty("seasonal", seasonal(playerEntity));
        json.addProperty("crucible", crucible(playerEntity));
        for (int i = 0; i < 4; i++) {
            boolean known = playerEntity.stat.get(StatType.INVENTORY_0_STAT.get() + i) != null;
            equipment.addProperty(equipmentNames[i], known ? IdToAsset.objectName(inv[i]) : null);
            equipment.addProperty(equipmentNames[i] + "id", known ? inv[i] : null);
        }
        json.add("equipment", equipment);
        json.addProperty("maxstats", statsMaxed() < 0 ? null : statsMaxed());
        int[] missing = statMissing();
        for (int i = 0; i < missing.length; i++) if (missing[i] != 0)
            deficits.addProperty(statNames[i], missing[i] < 0 ? null : missing[i]);
        json.add("missingstats", deficits);
        return new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(json);
    }
}
