package tomato.gui.stats;

import tomato.gui.modern.Evidence;
import java.util.*;

/**
 * One dungeon, session or A/B cohort's loot profile: the shared rate and coverage semantics of
 * {@link StatisticsArchiveAdapter} and {@link CohortArchiveAdapter}. A session is eligible only with at least one saved bag
 * (partial module evidence, never complete recording); run-only imports and sessions without saved loot evidence are
 * excluded and counted; zero-loot visits within evidenced sessions stay in the denominators; any unassigned bag, a missing
 * visit duration or an empty denominator makes the rate unavailable (null), never zero. Unknown loot counts are null.
 */
final class LootProfile {
    long runs,completed,millis,items,whites,uts,sts,potions,damage;boolean unassigned;
    long bags,unassignedBags,excludedRuns,unknownRuns,unknownMillis,ongoing;
    boolean imported,missingDuration,lootEvidence;
    final Set<String> lootRuns=new HashSet<>();
    boolean damageKnown=true;
    void add(LootDashboard.Drop drop,boolean linked){
        lootEvidence=true;bags++;if(!linked)unassignedBags++;unassigned|=!linked;items+=drop.items.size();if(drop.bag.equals("White")||drop.bag.equals("B.White"))whites++;
        for(LootDashboard.Item item:drop.items){if(item.ut)uts++;if(item.st)sts++;if(item.potion)potions++;}
    }
    Long lootValue(long count){return lootEvidence?count:null;}
    String coverage(){return lootEvidence?Evidence.Coverage.PARTIAL+" · session loot evidence; gaps unknown":imported||excludedRuns>0&&unknownRuns==0&&runs==0?Evidence.Coverage.NOT_CAPTURED+" · run-only import":"No saved loot · coverage unknown";}
    Double perRun(long count){return !lootEvidence||unassigned||runs==0?null:count/(double)runs;}
    Double perHour(long count){return !lootEvidence||unassigned||missingDuration||millis<=0?null:count*3600000.0/millis;}
    String explanation(){return coverage()+"\nNumerators: "+(!lootEvidence?"unavailable — no saved loot evidence.":items+" items, "+whites+" white bags, "+uts+" UT gear, "+sts+" ST gear, "+potions+" stat potions (observed, not owned).")
        +"\nPer run = numerator / "+runs+" eligible observed visits; "+(runs-lootRuns.size())+" with no linked bags; "+ongoing+" ongoing visits included."
        +"\nPer hour = numerator × 3,600,000 / "+millis+" observed milliseconds (first-to-last observation for each visit; includes gaps)."
        +"\nExcluded: "+excludedRuns+" run-only imported visits; "+unknownRuns+" unknown-coverage visits ("+unknownMillis+" observed milliseconds) from sessions without saved loot evidence. Unassigned bags: "+unassignedBags+" (session + visit + canonical dungeon must agree)."
        +"\nEligibility requires at least one saved bag in the same session; even an empty or unassigned bag establishes partial module evidence, not complete recording."
        +"\n"+(!lootEvidence?"Rates unavailable: no saved loot evidence; absence does not establish zero.":unassigned?"Rates unavailable: unassigned drops would mix numerator and denominator scopes.":runs==0?"Rates unavailable: no eligible observed visits.":"Zero-loot visits within evidenced sessions remain in the denominator; unknown-coverage sessions do not. No claim of complete recording or true drop probability.")
        +(missingDuration?" Hourly rates unavailable: at least one visit has no positive observed duration.":"");}
}
