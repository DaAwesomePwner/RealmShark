package tomato.bridge;

import assets.IdToAsset;
import com.google.gson.JsonObject;
import java.util.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.CharacterClass;

/** Wire contract adapted from LastEternity/RealmShark 25db3791 (MIT; see docs/BRIDGE.md).
 * Deliberately no new fields in HTTP loot payloads: enchant descriptions stay local. */
public final class BridgePayload {
    private BridgePayload() {}
    public static final class Item {
        public final int id, enchantCount;
        public final String rawName, baseName, group, label, rarity, enchants, raritySource;
        public final boolean shiny, divine, ut, st;
        public Item(int id, String name, String group, String label, String enchants, boolean malformed) {
            this.id=id; this.rawName=name.trim(); this.group=group==null?"":group; this.label=label==null?"":label;
            boolean suffix=rawName.toLowerCase(Locale.ROOT).endsWith("(shiny)");
            baseName=suffix?rawName.substring(0,rawName.length()-7).trim():rawName;
            List<String> tokens=Arrays.asList((this.label+" "+this.group).toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"));
            shiny=suffix||tokens.contains("SHINY"); ut=tokens.contains("UT"); st=tokens.contains("ST");
            this.enchants=enchants==null?"":enchants.trim();
            // Preserve upstream's line-count mapping, including its locked/empty special cases.
            int count=0;
            if(malformed) count=-1;
            else if(!this.enchants.isEmpty() && !this.enchants.equalsIgnoreCase("empty") && !this.enchants.equalsIgnoreCase("[locked]"))
                for(String line:this.enchants.split("\\R")) if(!line.trim().isEmpty()) count++;
            enchantCount=count;
            String resolved=null;
            for(String token:tokens) if(Arrays.asList("COMMON","UNCOMMON","RARE","LEGENDARY","DIVINE").contains(token)) {resolved=token.toLowerCase(Locale.ROOT);break;}
            if(resolved!=null) {rarity=resolved; raritySource="metadata_token";}
            else {rarity=count>=0&&count<=4?new String[]{"common","uncommon","rare","legendary","divine"}[count]:"unknown"; raritySource=count>=0&&count<=4?"enchant_count":"fallback_unknown_default";}
            divine=tokens.contains("DIVINE")||Arrays.asList(baseName.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")).contains("DIVINE")||rarity.equals("divine");
        }
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
            String text=""; boolean malformed=false;
            if(i<encoded.length&&!encoded[i].isEmpty()&&!encoded[i].equals("AAIE_f_9__3__f8=")) {
                try {text=ParseEnchants.parse(encoded[i]);} catch(RuntimeException ex) {malformed=true;text="Unable to decode enchant data";}
            }
            Item item=new Item(s.statValue,name,IdToAsset.getIdGroup(s.statValue),IdToAsset.getIdLabel(s.statValue),text,malformed);
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
