package tomato.gui.dps;

import java.util.*;
import tomato.backend.data.*;

/** Unfiltered retained outgoing evidence, separate from the meter's selected enemies/hit window. */
public final class EncounterSummary {
    public final String dungeon, localContext, contextDescription, coverage;
    public final Long started, elapsed;
    public final int contributors;
    public final long damage;
    EncounterSummary(DpsData data) {
        dungeon = data.map == null ? "Unknown encounter" : Objects.toString(data.map.displayName, Objects.toString(data.map.name, "Unknown encounter"));
        started = data.dungeonStartTime > 0 ? data.dungeonStartTime : null;
        elapsed = data.totalDungeonPcTime >= 0 ? data.totalDungeonPcTime : null;
        Set<Integer> owners = new HashSet<>(); long total = 0; boolean aggregate = false;
        if (data.hitList != null) for (Entity target : data.hitList.values()) {
            if (target == null || target.isPlayerCharacter()) continue;
            List<Damage> hits = target.getDamageList();
            if (hits.isEmpty() && !target.getPlayerDamageList().isEmpty()) { hits = target.getPlayerDamageList(); aggregate = true; }
            for (Damage hit : hits) if (hit != null) {
                total += hit.damage; if (hit.owner != null) owners.add(hit.owner.id);
            }
        }
        contributors = owners.size(); damage = total;
        coverage = aggregate ? "Includes aggregate-only legacy damage; not combined again with raw hits" : "Recorded outgoing damage, including unattributed hits; contributors are captured owner object IDs";
        DpsData.LocalPlayerContext context = data.getLocalPlayerContext();
        localContext = context == null ? "Unavailable" : context.hasClass() && context.guild != null ? "Available" : "Partial";
        contextDescription = context == null ? "No retained local-player context; the live character is not substituted"
            : (context.hasClass() ? "Class #" + context.classType : "Class not captured") + " · "
                + (context.guild == null ? "Guild not captured" : context.guild.isEmpty() ? "No guild (captured)" : "Guild " + context.guild);
    }
}
