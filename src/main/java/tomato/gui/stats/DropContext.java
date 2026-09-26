package tomato.gui.stats;

import packets.incoming.MapInfoPacket;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;
import java.util.*;
import tomato.backend.data.Entity;
import tomato.backend.data.FieldCapture;
import packets.data.StatData;
import packets.data.enums.StatType;

/** Allowlisted, detached map evidence frozen before asynchronous UI publication. */
public final class DropContext {
    public final int version=1;
    public final long capturedAt;
    public final VisitRef visit;
    public final List<Integer> modifierIds;
    public final int unresolvedModifiers;
    public final boolean mapRecorded;
    public final String grade;
    public final Float difficulty;
    public final Integer lootDropSeconds,lootTierSeconds,crucible;
    public final Long lootDropCapturedAt,lootTierCapturedAt,seasonalCapturedAt,crucibleCapturedAt;
    public final Boolean seasonal;
    private DropContext(long at,VisitRef visit,List<Integer> ids,int unresolved,boolean known,String grade,Float difficulty,Entity player){
        this.capturedAt=at;this.visit=visit;this.modifierIds=Collections.unmodifiableList(new ArrayList<>(ids));this.unresolvedModifiers=unresolved;this.mapRecorded=known;this.grade=grade;this.difficulty=difficulty;
        lootDropSeconds=value(player,StatType.LD_TIMER_STAT);lootTierSeconds=value(player,StatType.LT_TIMER_STAT);
        lootDropCapturedAt=receipt(player,StatType.LD_TIMER_STAT);lootTierCapturedAt=receipt(player,StatType.LT_TIMER_STAT);
        Integer season=value(player,StatType.SEASONAL);seasonal=season==null||season<0||season>1?null:season==1;
        seasonalCapturedAt=receipt(player,StatType.SEASONAL);crucible=value(player,StatType.CRUCIBLE_STAT);crucibleCapturedAt=receipt(player,StatType.CRUCIBLE_STAT);
    }
    public static DropContext capture(MapInfoPacket map,long at,VisitRef visit){
        return capture(map,null,at,visit);
    }
    private static Integer value(Entity player,StatType type){StatData s=player==null?null:player.stat.get(type);return s==null||player.fieldCapture(type)==null?null:s.statValue;}
    private static Long receipt(Entity player,StatType type){FieldCapture c=player==null?null:player.fieldCapture(type);return c==null||c.at<=0?null:c.at;}
    public static DropContext capture(MapInfoPacket map,Entity player,long at,VisitRef visit){
        List<Integer> ids=new ArrayList<>();int unresolved=0;
        if(map!=null)for(String field:new String[]{map.dungeonModifiers,map.dungeonModifiers2,map.dungeonModifiers3,map.dungeonModifiers4}){
            if(field==null||field.isEmpty())continue;
            for(String token:field.split(";")){if(token.isEmpty())continue;int[] mapped=ParseDungeon.getModIds(token);if(mapped.length==0)unresolved++;else for(int id:mapped)ids.add(id);}
        }
        String grade=map!=null&&map.dungeonGrade!=null&&map.dungeonGrade.matches("[SABCDF]")?map.dungeonGrade:null;
        Float difficulty=map==null||!Float.isFinite(map.difficulty)?null:map.difficulty;
        return new DropContext(at,visit,ids,unresolved,map!=null,grade,difficulty,player);
    }
    private String timer(Integer seconds,Long receipt){return seconds==null?"Not recorded":seconds+" seconds captured at "+(receipt==null?"unknown time":new Date(receipt))+"; remaining at drop "+(receipt==null||seconds<0||receipt>capturedAt?"unknown":Math.max(0,seconds*1000L-(capturedAt-receipt))+" ms");}
    public String describe(){return "Captured map context v"+version+" at "+new Date(capturedAt)+"; map "+(mapRecorded?"recorded":"not recorded")
        +"; modifier IDs "+modifierIds+"; unresolved modifiers "+unresolvedModifiers+"; grade "+(grade==null?"Not recorded":grade)
        +"; difficulty "+(difficulty==null?"Not recorded":difficulty)+"; exact visit "+(visit==null?"Not recorded":visit)
        +"; loot drop boost "+timer(lootDropSeconds,lootDropCapturedAt)+"; loot tier boost "+timer(lootTierSeconds,lootTierCapturedAt)
        +"; seasonal "+(seasonal==null?"Not recorded":seasonal+" (captured at "+seasonalCapturedAt+")")
        +"; crucible raw captured value "+(crucible==null?"Not recorded":crucible+" (captured at "+crucibleCapturedAt+")");}
}
