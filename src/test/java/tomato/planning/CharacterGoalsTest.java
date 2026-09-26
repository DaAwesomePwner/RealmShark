package tomato.planning;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import tomato.backend.data.*;

public class CharacterGoalsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private RosterDefinitions caps(int life) throws Exception { return RosterDefinitions.parse(new StringReader("<Objects><Object type='782'><MaxHitPoints max='" + life + "'/></Object></Objects>"), null); }
    private CharacterJournal.CharacterRecord record() { CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord(); r.key = "A:1"; r.account = "A"; r.classId = 782; r.stats[0] = 654; return r; }
    @Test public void lifeDeficit16IsFourPotionsAndMissingCapsStayUnknown() throws Exception {
        PlanData.AccountPlan plan = new PlanData.AccountPlan(); CharacterJournal.CharacterRecord r = record();
        CharacterGoals.pinCharacter(plan, r, 0, 670, caps(670), 10); PlanData.CharacterGoal g = plan.characterGoals.get("A:1:0");
        assertEquals(Integer.valueOf(4), CharacterGoals.character(g, r, caps(670)).remaining);
        assertNull(CharacterGoals.character(g, r, RosterDefinitions.empty()).remaining);
        r.stats[0] = null; assertNull(CharacterGoals.character(g, r, caps(670)).remaining);
    }
    @Test public void changedCapPreservesIntentAndRequiresReconfirmation() throws Exception {
        PlanData.AccountPlan plan = new PlanData.AccountPlan(); CharacterJournal.CharacterRecord r = record();
        CharacterGoals.pinCharacter(plan, r, 0, 670, caps(670), 10); PlanData.CharacterGoal g = plan.characterGoals.get("A:1:0");
        assertTrue(CharacterGoals.character(g, r, caps(700)).metadataChanged); assertEquals(670, g.targetBaseValue);
        assertNull(CharacterGoals.character(g, r, caps(660)).remaining);
        CharacterGoals.pinCharacter(plan, r, 0, 660, caps(660), 20); assertEquals(10, plan.characterGoals.get("A:1:0").createdAt);
        assertFalse(CharacterGoals.character(plan.characterGoals.get("A:1:0"), r, caps(660)).metadataChanged);
    }
    @Test public void targetsPersistAcrossRestartAndNeverAdvanceWhenComplete() throws Exception {
        Path path = temp.getRoot().toPath().resolve("plans.json");
        PlanData.AccountPlan plan = new PlanData.AccountPlan(); CharacterJournal.CharacterRecord r = record();
        CharacterGoals.pinCharacter(plan, r, 0, 654, caps(670), 10);
        CharacterGoals.pinExalt(plan, 782, 0, 2, PlanningMetadata.unavailable(), 10);
        try (PlanningStore store = new PlanningStore(path)) { await(store); assertTrue(store.update("A", 0, plan).get().saved); }
        try (PlanningStore store = new PlanningStore(path)) {
            await(store); PlanData.AccountPlan loaded = store.snapshot("A").plan(); r.dead = true;
            assertEquals(Integer.valueOf(0), CharacterGoals.character(loaded.characterGoals.get("A:1:0"), r, caps(670)).remaining);
            PlanData.ExaltGoal exalt = loaded.exaltGoals.get("782:0"); assertEquals(Integer.valueOf(0), CharacterGoals.exalt(exalt, 30, PlanningMetadata.unavailable()).remaining); assertEquals(2, exalt.targetTier);
            assertTrue(store.snapshot("B").plan().characterGoals.isEmpty()); assertTrue(store.snapshot("B").plan().exaltGoals.isEmpty());
        }
    }
    @Test public void metadataUsesOnlySelectedGenerationAndVersionsExactMappingBytes() throws Exception {
        Path root = temp.newFolder().toPath(); Files.createDirectories(root.resolve("xml"));
        Path file = root.resolve("xml/exaltationConfig.xml"); String xml = "<Exaltation><Dungeons><Dungeon><Name>Fixture Vault</Name><PowerUp>LIFE</PowerUp></Dungeon><Dungeon><Name>Unsupported</Name><PowerUp>OTHER</PowerUp></Dungeon></Dungeons></Exaltation>";
        Files.write(file, xml.getBytes(StandardCharsets.UTF_8)); PlanningMetadata first = PlanningMetadata.read(root);
        assertTrue(first.available); assertEquals("Fixture Vault", first.dungeons(0).get(0)); assertTrue(first.dungeons(1).isEmpty()); assertTrue(first.status.contains("1 unsupported"));
        Files.write(file, xml.replace("Fixture Vault", "Changed Vault").getBytes(StandardCharsets.UTF_8)); assertNotEquals(first.version, PlanningMetadata.read(root).version);
        assertFalse(PlanningMetadata.read(temp.newFolder().toPath()).available); assertEquals(75, PlanningMetadata.threshold(5));
    }
    @Test public void unknownProgressCannotSelectAutomaticNextTier() {
        try { CharacterGoals.nextTier(null); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(2, CharacterGoals.nextTier(5)); assertEquals(5, CharacterGoals.nextTier(99));
    }
    private static void await(PlanningStore store) throws Exception { long end = System.nanoTime() + 5000000000L; while (!store.snapshot("A").ready && System.nanoTime() < end) Thread.sleep(5); assertTrue(store.snapshot("A").ready); }
}
