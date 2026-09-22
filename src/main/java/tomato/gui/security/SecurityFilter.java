package tomato.gui.security;

import com.google.gson.Gson;
import tomato.backend.data.RosterDefinitions;
import packets.data.StatData;
import packets.data.enums.StatType;
import java.util.*;
import static tomato.gui.security.RequirementResult.Kind.*;

public class SecurityFilter {
    public String name;
    public transient String json;
    public int exaltSkinPoints;
    public boolean[] statMaxed = new boolean[8];
    public boolean isWhitelistFilter = true; // default to whitelist
    public TreeMap<Integer, Integer> itemPoint = new TreeMap<>();
    public TreeMap<Integer, Integer> classPoint = new TreeMap<>();
    public TreeMap<Integer, Integer> minTier = new TreeMap<>();

    public final static int[] exaltedSkinIds = {
        9497, //Rogue
        9499, //Archer
        9501, //Wizard
        9503, //Priest
        9505, //Warrior
        9507, //Knight
        9509, //Paladin
        9511, //Assassin
        9513, //Necromancer
        9515, //Huntress
        9519, //Trickster
        9517, //Mystic
        9521, //Sorcerer
        9523, //Ninja
        9525, //Samurai
        9527, //Bard
        30721, //Summoner
        31238, //Kensei
    };

    public static SecurityFilter loadJson(String json) {
        try {
            SecurityFilter sf = new Gson().fromJson(json, SecurityFilter.class);
            sf.json = json;
            return sf;
        } catch (Exception e) {
            System.err.printf("Could not load inspect filters... \n%s%n", e.getMessage());
            return null;
        }
    }

    public ParsedPlayerObject parsePlayer(Player player) {
        RequirementResult result = evaluate(player, RosterDefinitions.current());
        ArrayList<String> missing = new ArrayList<>();
        for (RequirementResult.Reason reason : result.reasons) if (reason.kind != INFO) missing.add(reason.message);
        return new ParsedPlayerObject(player, missing, (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, result.knownPoints)),
            result.requiredPoints == null ? 0 : result.requiredPoints, result.nonPassing());
    }

    public SecurityFilter snapshot() {
        SecurityFilter copy = new SecurityFilter(); copy.name = name; copy.exaltSkinPoints = exaltSkinPoints; copy.isWhitelistFilter = isWhitelistFilter;
        copy.statMaxed = statMaxed == null ? null : statMaxed.clone();
        copy.itemPoint = itemPoint == null ? null : new TreeMap<>(itemPoint);
        copy.classPoint = classPoint == null ? null : new TreeMap<>(classPoint);
        copy.minTier = minTier == null ? null : new TreeMap<>(minTier); return copy;
    }
    public boolean valid() {
        if (statMaxed == null || statMaxed.length != 8 || itemPoint == null || classPoint == null || minTier == null) return false;
        if (itemPoint.containsValue(null) || classPoint.containsValue(null) || minTier.containsValue(null)) return false;
        for (Map.Entry<Integer, Integer> tier : minTier.entrySet()) if (tier.getKey() < 0 || tier.getKey() > 3 || tier.getValue() < 0) return false;
        return true;
    }
    public RequirementResult evaluate(Player player, RosterDefinitions definitions) {
        List<RequirementResult.Reason> reasons = new ArrayList<>();
        if (!valid()) {
            reasons.add(new RequirementResult.Reason(UNKNOWN, "invalid-preset", "Preset fields are invalid or incomplete; edit the requirements."));
            return new RequirementResult(RequirementResult.Verdict.UNKNOWN, reasons, 0, null, false);
        }
        String[] statNames = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
        for (int i = 0; i < 8; i++) if (statMaxed[i]) {
            Integer cap = definitions.cap(player.playerEntity.objectType, i); int value = player.baseStat(i);
            if (value < 0) reasons.add(new RequirementResult.Reason(UNKNOWN, "stat-missing", statNames[i] + ": base stat not captured."));
            else if (cap == null) reasons.add(new RequirementResult.Reason(UNKNOWN, "cap-missing", statNames[i] + ": cap definition unavailable."));
            else if (value < cap) reasons.add(new RequirementResult.Reason(FAILURE, "stat-below", statNames[i] + ": " + value + " below cap " + cap + "."));
        }
        long points = 0; boolean completeScore = true;
        Integer required = player.playerEntity.objectType > 0 ? classPoint.getOrDefault(player.playerEntity.objectType, 0) : null;
        if (required == null) reasons.add(new RequirementResult.Reason(UNKNOWN, "class-missing", "Character class not captured; points threshold unknown."));
        if (exaltSkinPoints != 0) {
            StatData skin = player.playerEntity.stat.get(StatType.SKIN_ID);
            if (skin == null) { completeScore = false; reasons.add(new RequirementResult.Reason(UNKNOWN, "skin-missing", "Skin not captured; exalted-skin points unknown.")); }
            else for (int id : exaltedSkinIds) if (id == skin.statValue) { points += exaltSkinPoints; break; }
        }
        for (int slot = 0; slot < 4; slot++) {
            StatData captured = player.playerEntity.stat.get(StatType.INVENTORY_0_STAT.get() + slot);
            String label = Player.equipmentNames[slot]; Integer tier = minTier.get(slot);
            if (captured == null) {
                if (tier != null || isWhitelistFilter || !itemPoint.isEmpty()) {
                    completeScore = false; reasons.add(new RequirementResult.Reason(UNKNOWN, "equipment-missing", label + ": not captured."));
                }
                continue;
            }
            if (captured.statValue < 0) {
                if (tier != null) reasons.add(new RequirementResult.Reason(FAILURE, "slot-empty", label + ": captured empty; requires tier " + tier + "."));
                continue;
            }
            RosterDefinitions.Item item = definitions.item(captured.statValue);
            if (item == null || item.labels == null) {
                completeScore = false; reasons.add(new RequirementResult.Reason(UNKNOWN, "item-definition-missing", label + " #" + captured.statValue + ": item definition unavailable.")); continue;
            }
            if (tier != null) {
                if (item.special()) reasons.add(new RequirementResult.Reason(INFO, "special-tier", label + ": UT/ST uses the preset's legacy tier exemption."));
                else if (item.tier == null || !item.labels.contains("TIERED")) reasons.add(new RequirementResult.Reason(UNKNOWN, "tier-unknown", label + ": tier applicability/definition unavailable."));
                else if (item.tier < tier) reasons.add(new RequirementResult.Reason(FAILURE, "tier-below", label + ": T" + item.tier + " below T" + tier + "."));
            }
            Boolean parsable = item.parsable();
            if (parsable == null) { completeScore = false; reasons.add(new RequirementResult.Reason(UNKNOWN, "item-policy-unknown", label + ": item-policy applicability unknown.")); }
            else if (parsable) {
                Integer award = itemPoint.get(captured.statValue);
                if (isWhitelistFilter && award == null || !isWhitelistFilter && award != null)
                    reasons.add(new RequirementResult.Reason(FAILURE, "item-disallowed", label + " #" + captured.statValue + ": " + (isWhitelistFilter ? "not whitelisted" : "blacklisted") + "."));
                else if (award != null) points += award;
            }
        }
        if (completeScore && required != null && points < required)
            reasons.add(new RequirementResult.Reason(FAILURE, "points-below", "Points " + points + " below required " + required + "."));
        boolean below = reasons.stream().anyMatch(r -> r.kind == FAILURE), unknown = reasons.stream().anyMatch(r -> r.kind == UNKNOWN);
        if (reasons.isEmpty()) reasons.add(new RequirementResult.Reason(INFO, "requirements-met", "All applicable requirements are satisfied by captured evidence."));
        return new RequirementResult(below ? RequirementResult.Verdict.BELOW : unknown ? RequirementResult.Verdict.UNKNOWN : RequirementResult.Verdict.PASS,
            reasons, points, required, completeScore);
    }

    public static class ParsedPlayerObject {
        public Player player;
        public ArrayList<String> missing;
        public int points;
        public int classPoints;
        public boolean isUnderReqs;

        public ParsedPlayerObject(Player player, ArrayList<String> missing, int points, int classPoints, boolean isUnderReqs) {
            this.player = player;
            this.missing = missing;
            this.points = points;
            this.classPoints = classPoints;
            this.isUnderReqs = isUnderReqs;
        }
    }
}
