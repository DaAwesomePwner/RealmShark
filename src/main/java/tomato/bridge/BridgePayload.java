package tomato.bridge;

import assets.IdToAsset;
import com.google.gson.JsonObject;
import java.util.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.enums.CharacterClass;

/** Wire contract adapted from LastEternity/RealmShark 25db3791 (MIT; see docs/BRIDGE.md).
 * Deliberately no new fields in HTTP loot payloads: enchant descriptions and the raw entry stay local. */
public final class BridgePayload {
    private BridgePayload() {}
    public static final class Item {
        /** Rarity sources. Saved entries may also hold LEGACY_ENCHANT_COUNT (upstream's decoded line count) or none; they are never recomputed. */
        public static final String METADATA_TOKEN="metadata_token", ENCHANT_SLOTS="enchant_slots", ENCHANT_DATA_ABSENT="enchant_data_absent",
            UNKNOWN_DEFAULT="fallback_unknown_default", LEGACY_ENCHANT_COUNT="enchant_count";
        /** The wire's rarity words by unlocked slot count (upstream's vocabulary: an unenchanted item is "common"). */
        private static final String[] WIRE_RARITIES={"common","uncommon","rare","legendary","divine"};
        /** enchantCount: applied enchantments, -1 when the entry is unreadable (saved legacy entries hold upstream's line count). */
        public final int id, enchantCount;
        public final String rawName, baseName, group, label, rarity, enchants, raritySource;
        /** The item's UNIQUE_DATA_STRING entry as captured; null when the bag had none for this slot, and in entries saved before it was kept. */
        public final String enchantData;
        public final boolean shiny, divine, ut, st;
        public Item(int id, String name, String group, String label, String enchantData) {
            this.id=id; this.rawName=name.trim(); this.group=group==null?"":group; this.label=label==null?"":label;
            boolean suffix=rawName.toLowerCase(Locale.ROOT).endsWith("(shiny)");
            baseName=suffix?rawName.substring(0,rawName.length()-7).trim():rawName;
            List<String> tokens=Arrays.asList((this.label+" "+this.group).toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"));
            shiny=suffix||tokens.contains("SHINY"); ut=tokens.contains("UT"); st=tokens.contains("ST");
            this.enchantData=enchantData;
            EnchantInfo info=EnchantInfo.of(enchantData);
            boolean unreadable=info.state()==EnchantInfo.State.UNREADABLE, absent=info.state()==EnchantInfo.State.NOT_RECORDED;
            int applied=0; for(EnchantInfo.Slot slot:info.slots()) if(!slot.empty()) applied++;
            enchantCount=unreadable?-1:applied;
            enchants=unreadable?"Unable to decode enchant data":String.join("\n",info.slotLines());
            String resolved=null;
            for(String token:tokens) if(Arrays.asList("COMMON","UNCOMMON","RARE","LEGENDARY","DIVINE").contains(token)) {resolved=token.toLowerCase(Locale.ROOT);break;}
            if(resolved!=null) {rarity=resolved; raritySource=METADATA_TOKEN;}
            else if(unreadable) {rarity="unknown"; raritySource=UNKNOWN_DEFAULT;}
            // No entry for this slot: upstream sent "common" here, so the wire keeps it; Review says why.
            else if(absent) {rarity="common"; raritySource=ENCHANT_DATA_ABSENT;}
            else {rarity=WIRE_RARITIES[info.rarity().ordinal()]; raritySource=ENCHANT_SLOTS;}
            divine=tokens.contains("DIVINE")||Arrays.asList(baseName.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")).contains("DIVINE")||rarity.equals("divine");
        }
        /** The rarity as Review and its exports show it: saved entries whose rarity came from upstream's line count say so. */
        public String rarityLabel() {
            String shown=rarity==null?"unknown":rarity;
            if(raritySource==null||LEGACY_ENCHANT_COUNT.equals(raritySource)) return shown+" (legacy count)";
            return ENCHANT_DATA_ABSENT.equals(raritySource)?shown+" (no enchant data)":shown;
        }
        /** The enchantments for the rarity pips, glow, rarity word and tooltip; null when no entry was kept (older entries, or none in the bag). Decode once per row build. */
        public EnchantInfo enchantInfo() { return enchantData==null?null:EnchantInfo.of(enchantData); }
    }
    /** Immutable capture-thread snapshot; no bag/player references cross onto the worker. */
    public static final class Drop {
        public final Item item;
        public final int characterId, slot, bagId;
        public final String characterName, characterClass, dungeon;
        public final boolean seasonal, lootBonus;
        public Drop(Item item,int characterId,String characterName,String characterClass,String dungeon,boolean seasonal,boolean lootBonus,int bagId,int slot) {
            this.item=item;this.characterId=characterId;this.characterName=characterName;this.characterClass=characterClass;
            this.dungeon=dungeon;this.seasonal=seasonal;this.lootBonus=lootBonus;this.bagId=bagId;this.slot=slot;
        }
    }
    public static List<Drop> snapshot(TomatoData data,MapInfoPacket map,Entity bag,Entity player,long time) {
        List<Drop> result=new ArrayList<>(); if(bag==null||player==null) return result;
        StatData unique=bag.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] encoded=unique==null||unique.stringStatValue==null?new String[0]:unique.stringStatValue.split(",",-1);
        StatData seasonal=player.stat.get(StatType.SEASONAL);
        for(int i=0;i<8;i++) {
            StatData s=bag.stat.get(StatType.INVENTORY_0_STAT.get()+i); if(s==null||s.statValue<1) continue;
            String name=IdToAsset.objectName(s.statValue); if(name==null||name.trim().isEmpty()) name="Unknown item #"+s.statValue;
            Item item=new Item(s.statValue,name,IdToAsset.getIdGroup(s.statValue),IdToAsset.getIdLabel(s.statValue),i<encoded.length?encoded[i]:null);
            result.add(new Drop(item,data==null?-1:data.getCharId(),player.name(),CharacterClass.isPlayerCharacter(player.objectType)?CharacterClass.getName(player.objectType):"",map==null?"":map.name,seasonal!=null&&seasonal.statValue==1,player.lootDropTime(time)>0,bag.id,i));
        }
        return result;
    }
    public static JsonObject settingsPing(BridgeConfig config) {
        JsonObject p=identity(config); p.addProperty("event_type","bridge_settings_test");p.addProperty("source","tomato"); return p;
    }
    private static JsonObject identity(BridgeConfig config) {
        JsonObject p=new JsonObject();p.addProperty("guild_id",Long.parseLong(config.guildId));p.addProperty("link_token",config.token);return p;
    }
    public static JsonObject loot(BridgeConfig config,Drop d) {
        JsonObject p=identity(config);Item i=d.item;
        p.addProperty("item_name",i.baseName);p.addProperty("shiny",i.shiny);p.addProperty("item_id",i.id);
        if(d.characterId>0)p.addProperty("character_id",d.characterId);
        optional(p,"character_name",d.characterName);optional(p,"character_class",d.characterClass);
        optional(p,"item_group",i.group);optional(p,"item_label",i.label);p.addProperty("item_rarity",i.rarity);
        p.addProperty("divine",i.divine);optional(p,"dungeon",d.dungeon);p.addProperty("is_seasonal",d.seasonal);
        p.addProperty("loot_drop_bonus",d.lootBonus);p.addProperty("source","tomato");return p;
    }
    private static void optional(JsonObject p,String key,String value) {if(value!=null&&!value.isEmpty())p.addProperty(key,value);}
    public static JsonObject redacted(JsonObject payload) {JsonObject copy=payload.deepCopy();if(copy.has("link_token"))copy.addProperty("link_token","[redacted]");return copy;}
}
