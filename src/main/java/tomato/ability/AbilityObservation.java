package tomato.ability;

import tomato.history.link.VisitRef;
import java.util.Locale;
import java.util.UUID;

/** Detached heuristic evidence, never a successful-cast assertion. */
public final class AbilityObservation {
    public final String id = UUID.randomUUID().toString();
    public final String session, player, characterClass, ability, heuristic, explanation;
    public final VisitRef visit;
    public final long observedAt;
    public final int playerObjectId;
    public final Integer abilityItemId, previousMp, observedMp;
    public final int evidenceVersion = 1;
    public AbilityObservation(String session, VisitRef visit, long observedAt, int playerObjectId,
            String player, String characterClass, Integer abilityItemId, String ability,
            String heuristic, Integer previousMp, Integer observedMp, String explanation) {
        this.session = text(session); this.visit = visit; this.observedAt = observedAt;
        this.playerObjectId = playerObjectId; this.player = text(player); this.characterClass = text(characterClass);
        this.abilityItemId = abilityItemId; this.ability = text(ability); this.heuristic = text(heuristic);
        this.previousMp = previousMp; this.observedMp = observedMp; this.explanation = text(explanation);
    }
    private static String text(String value) { return value == null ? "Unknown" : value.substring(0, Math.min(value.length(), 1024)); }
    public boolean matches(String query) {
        return (player+" "+characterClass+" "+ability+" "+abilityItemId+" "+heuristic+" "+explanation)
            .toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }
    public String details() {
        return "Inferred ability evidence — not a confirmed cast\nObserved: " + new java.util.Date(observedAt)
            + "\nPlayer: " + player + "\nClass: " + characterClass + "\nAbility: " + ability + " (" + abilityItemId + ")"
            + "\nPrevious MP: " + previousMp + "\nIncoming MP: " + observedMp + "\nHeuristic: " + heuristic
            + " (version " + evidenceVersion + ")\nEvidence: " + explanation + "\nSession: " + session
            + "\nExact visit: " + (visit == null ? "Not recorded" : visit) + "\nObject ID: " + playerObjectId
            + " (local to captured session/visit; not permanent identity)\nObservation: " + id;
    }
}
