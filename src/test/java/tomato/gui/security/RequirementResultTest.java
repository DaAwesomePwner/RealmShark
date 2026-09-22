package tomato.gui.security;

import java.io.StringReader;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.*;
import static org.junit.Assert.*;

public class RequirementResultTest {
    static RosterDefinitions definitions() throws Exception {
        return RosterDefinitions.parse(new StringReader("<Objects><Object type='782'><MaxHitPoints max='100'/><MaxMagicPoints max='100'/>"
            + "<Attack max='10'/><Defense max='0'/><Speed max='10'/><Dexterity max='10'/><HpRegen max='10'/><MpRegen max='10'/></Object></Objects>"),
            new StringReader("<Objects><Object type='42'><Tier>5</Tier><Labels>T5,TIERED,WEAPON</Labels></Object>"
                + "<Object type='43'><Labels>TIERED</Labels></Object><Object type='44'><Labels>UT</Labels></Object></Objects>"));
    }
    static Player player(int id) {
        Entity entity = new Entity(null, id, 0); entity.objectType = 782; entity.markPlayerIdentity(); entity.baseStats = new int[]{100,100,10,0,10,10,10,10};
        for (int i = 0; i < 4; i++) put(entity, StatType.INVENTORY_0_STAT.get() + i, -1);
        put(entity, StatType.SKIN_ID.get(), 0); StatData name = new StatData(); name.stringStatValue = "Player" + id; entity.stat.set(StatType.NAME_STAT, name);
        return new Player(entity);
    }
    static void put(Entity e, int type, int value) { StatData s = new StatData(); s.statTypeNum = type; s.statValue = value; for (StatType t : StatType.values()) if (t.get() == type) { s.statType = t; e.stat.set(t, s); } }
    static SecurityFilter rules() { SecurityFilter rules = new SecurityFilter(); rules.name = "Fixture"; rules.minTier.put(0, 4); rules.itemPoint.put(42, 10); rules.classPoint.put(782, 10); rules.statMaxed[0] = true; return rules; }
    @Test public void qualifyingBelowAndUncapturedBuildsHaveDistinctVerdictsAndReasons() throws Exception {
        Player p = player(1); put(p.playerEntity, 8, 42); SecurityFilter rules = rules();
        RequirementResult pass = rules.evaluate(p, definitions()); assertEquals(RequirementResult.Verdict.PASS, pass.verdict); assertEquals(10, pass.knownPoints);
        rules.minTier.put(0, 6); p.playerEntity.baseStats[0] = -1;
        RequirementResult below = rules.evaluate(p, definitions()); assertEquals(RequirementResult.Verdict.BELOW, below.verdict);
        assertTrue(below.reasons.stream().anyMatch(r -> r.code.equals("tier-below"))); assertTrue(below.reasons.stream().anyMatch(r -> r.code.equals("stat-missing")));
        p.playerEntity.stat.set(StatType.INVENTORY_0_STAT, null);
        RequirementResult unknown = rules.evaluate(p, definitions()); assertEquals(RequirementResult.Verdict.UNKNOWN, unknown.verdict); assertFalse(unknown.scoreComplete);
        assertFalse(unknown.reasons.stream().anyMatch(r -> r.code.equals("points-below")));
    }
    @Test public void zeroCapIsKnownButMissingDefinitionsAndEmptySlotsHaveDifferentOutcomes() throws Exception {
        Player p = player(1); SecurityFilter rules = new SecurityFilter(); rules.isWhitelistFilter = false; rules.statMaxed[3] = true;
        assertEquals(RequirementResult.Verdict.PASS, rules.evaluate(p, definitions()).verdict);
        assertEquals(RequirementResult.Verdict.UNKNOWN, rules.evaluate(p, RosterDefinitions.empty()).verdict);
        rules.statMaxed[3] = false; rules.minTier.put(0, 0);
        assertEquals(RequirementResult.Verdict.BELOW, rules.evaluate(p, definitions()).verdict);
        put(p.playerEntity, 8, 43); assertEquals(RequirementResult.Verdict.UNKNOWN, rules.evaluate(p, definitions()).verdict);
        put(p.playerEntity, 8, 44); assertEquals(RequirementResult.Verdict.PASS, rules.evaluate(p, definitions()).verdict);
    }
    @Test public void skinScoringAndInvalidRulesDoNotCreateFalseShortfallsOrPasses() throws Exception {
        Player p = player(1); SecurityFilter rules = new SecurityFilter(); rules.isWhitelistFilter = false; rules.exaltSkinPoints = 15; rules.classPoint.put(782, 10);
        p.playerEntity.stat.set(StatType.SKIN_ID, null);
        assertEquals(RequirementResult.Verdict.UNKNOWN, rules.evaluate(p, definitions()).verdict);
        put(p.playerEntity, StatType.SKIN_ID.get(), SecurityFilter.exaltedSkinIds[0]);
        assertEquals(RequirementResult.Verdict.PASS, rules.evaluate(p, definitions()).verdict);
        SecurityFilter snapshot = rules.snapshot(); rules.classPoint.put(782, 100);
        assertEquals(RequirementResult.Verdict.PASS, snapshot.evaluate(p, definitions()).verdict);
        rules.statMaxed = new boolean[1]; assertEquals(RequirementResult.Verdict.UNKNOWN, rules.evaluate(p, definitions()).verdict);
        assertEquals(RequirementResult.Verdict.NOT_EVALUATED, RequirementResult.notEvaluated().verdict);
    }
    @Test public void displayFacetsSeparateCapturedEmptyGuildAndUnknownModes() throws Exception {
        Player p = player(1); RequirementResult result = RequirementResult.notEvaluated();
        InspectRosterQuery query = new InspectRosterQuery("Player", 782, InspectRosterQuery.Guild.UNKNOWN, null,
            InspectRosterQuery.Mode.UNKNOWN, InspectRosterQuery.Mode.UNKNOWN, RequirementResult.Verdict.NOT_EVALUATED, true, false, 8, 8);
        assertTrue(query.matches(p, result, p.statsMaxed(definitions()), "Wizard"));
        StatData guild = new StatData(); guild.stringStatValue = ""; p.playerEntity.stat.set(StatType.GUILD_NAME_STAT, guild);
        assertFalse(query.matches(p, result, p.statsMaxed(definitions()), "Wizard"));
        put(p.playerEntity, StatType.SEASONAL.get(), 0);
        assertEquals(Boolean.FALSE, Player.seasonal(p.playerEntity));
    }
}
