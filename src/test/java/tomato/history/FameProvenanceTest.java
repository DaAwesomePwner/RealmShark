package tomato.history;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.stats.FameTableBridge;
import static org.junit.Assert.*;

/**
 * Fame samples carry the journal's hashed account key (exact provenance for the character sheet's fame history). Legacy samples
 * and samples taken before the account is known have none. Character ids are unique per test: AppHistory's change detection is
 * static for the JVM.
 */
public class FameProvenanceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String A = CharacterJournal.accountKey("provenance-a"), B = CharacterJournal.accountKey("provenance-b");
    private Field storeField;
    private Object previousStore;
    private java.util.function.Supplier<DiscoveryLog.CurrentVisit> previousVisit;
    private SessionStore store;

    @Before public void installStore() throws Exception {
        storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        previousStore = storeField.get(null); previousVisit = AppHistory.currentVisit;
        store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        storeField.set(null, store); AppHistory.currentVisit = () -> null;
    }
    @After public void restoreStore() throws Exception {
        AppHistory.currentVisit = previousVisit; storeField.set(null, previousStore);
        store.close();
    }

    private List<AppHistory.FameSample> read(String module) throws Exception {
        store.flush();
        return store.read(store.currentId(), module, AppHistory.FameSample.class);
    }
    private List<String> lines() throws Exception {
        store.flush();
        return Files.readAllLines(store.directory().resolve(store.currentId()).resolve("fame.jsonl"), StandardCharsets.UTF_8);
    }
    /** SessionStore names a checkpoint file after the UUID of its key. */
    private Path latest(String key) {
        return store.directory().resolve(store.currentId()).resolve("fame-latest")
            .resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    @Test public void aSampleWithAnAccountRoundTripsWithItsAccount() throws Exception {
        AppHistory.fame(9101, A, 100, 1_000, "Wizard");
        List<AppHistory.FameSample> samples = read("fame");
        assertEquals(1, samples.size());
        AppHistory.FameSample sample = samples.get(0);
        assertEquals(A, sample.account); assertEquals(9101, sample.character); assertEquals(100, sample.fame);
        assertEquals(1_000, sample.time); assertEquals("Wizard", sample.className);
        assertTrue(lines().get(0), lines().get(0).contains("\"account\":\"" + A + "\""));
        AppHistory.FameSample direct = new AppHistory.FameSample(7, A, 5, 9, "Priest");
        assertEquals(A, SessionStore.JSON.fromJson(SessionStore.JSON.toJson(direct), AppHistory.FameSample.class).account);
        assertNull("Only a journal account key (64 hex) is recorded", new AppHistory.FameSample(7, "raw-account-id", 5, 9, "Priest").account);
        assertNull(new AppHistory.FameSample(7, A.toUpperCase(Locale.ROOT), 5, 9, "Priest").account);
    }

    @Test public void aLegacySampleHasNoAccountAndKeepsItsJsonShape() throws Exception {
        AppHistory.FameSample legacy = SessionStore.JSON.fromJson("{\"character\":7,\"fame\":5,\"time\":9,\"className\":\"Priest\"}", AppHistory.FameSample.class);
        assertNull(legacy.account); assertEquals(5, legacy.fame);
        store.append("fame", new AppHistory.FameSample(7, 5, 9, "Priest"));
        store.append("fame", new AppHistory.FameSample(7, 6, 10, "Priest", null, null));
        assertEquals("Without an account the JSON is unchanged", "{\"character\":7,\"fame\":5,\"time\":9,\"className\":\"Priest\"}", lines().get(0));
        assertFalse(lines().get(1).contains("account"));
        for (AppHistory.FameSample sample : read("fame")) assertNull(sample.account);
    }

    @Test public void twoAccountsWithTheSameCharacterIdKeepTwoLatestEntriesAndTwoStreams() throws Exception {
        AppHistory.fame(9102, A, 100, 1_000, "Wizard");
        AppHistory.fame(9102, B, 100, 1_100, "Wizard");   // same id and value, another account: a new reading
        AppHistory.fame(9102, A, 100, 1_200, "Wizard");   // unchanged for A: not appended
        AppHistory.fame(9102, B, 140, 1_300, "Wizard");
        AppHistory.fame(9102, null, 100, 1_400, "Wizard"); // account not known yet: its own stream
        AppHistory.fame(9102, null, 100, 1_500, "Wizard");
        List<AppHistory.FameSample> samples = read("fame");
        assertEquals(4, samples.size());
        assertEquals(Arrays.asList(A, B, B, null), Arrays.asList(samples.get(0).account, samples.get(1).account, samples.get(2).account, samples.get(3).account));
        assertEquals(Arrays.asList(1_000L, 1_100L, 1_300L, 1_400L), Arrays.asList(samples.get(0).time, samples.get(1).time, samples.get(2).time, samples.get(3).time));
        List<AppHistory.FameSample> latest = read("fame-latest");
        assertEquals("One checkpoint per account and character, plus the unattributed one", 3, latest.size());
        assertTrue(Files.isRegularFile(latest(A + ":9102"))); assertTrue(Files.isRegularFile(latest(B + ":9102")));
        assertTrue("Unknown account keeps the legacy key", Files.isRegularFile(latest("9102")));
        Map<String, Long> byAccount = new HashMap<>();
        for (AppHistory.FameSample sample : latest) byAccount.put(String.valueOf(sample.account), sample.time);
        assertEquals(Long.valueOf(1_200), byAccount.get(A)); assertEquals(Long.valueOf(1_300), byAccount.get(B));
        assertEquals(Long.valueOf(1_500), byAccount.get("null"));
    }

    @Test public void theFourArgumentFormStillRecordsWithANullAccount() throws Exception {
        AppHistory.fame(9103, 10, 1_000, "Knight");
        AppHistory.fame(9103, 10, 1_100, "Knight");
        AppHistory.fame(9103, 12, 1_200, "Knight");
        List<AppHistory.FameSample> samples = read("fame");
        assertEquals(2, samples.size());
        for (AppHistory.FameSample sample : samples) assertNull(sample.account);
        assertTrue(Files.isRegularFile(latest("9103")));
        assertEquals(1, read("fame-latest").size());
    }

    @Test public void entityFameStampsTheAccountKeyOfItsOwnAccountStat() throws Exception {
        Field panel = FameTableBridge.class.getDeclaredField("fameTablePanel"), tracker = FameTableBridge.class.getDeclaredField("fameTrackerGUI");
        panel.setAccessible(true); tracker.setAccessible(true);
        FameTableBridge bridge = FameTableBridge.getInstance();
        Object previousPanel, previousTracker;
        synchronized (bridge) { previousPanel = panel.get(bridge); previousTracker = tracker.get(bridge); panel.set(bridge, null); tracker.set(bridge, null); }
        try {
            Method fame = Entity.class.getDeclaredMethod("fame", long.class); fame.setAccessible(true);
            Entity entity = new Entity(new TomatoData(), 7, 0); entity.setUser(9104);
            StatData experience = new StatData(); experience.stringStatValue = Long.toString(100 * 2000L - 40071);
            entity.stat.set(StatType.EXP_STAT, experience);
            fame.invoke(entity, 1_000L);   // the account stat has not arrived yet
            StatData account = new StatData(); account.stringStatValue = "synthetic-account";
            entity.stat.set(StatType.ACCOUNT_ID_STAT, account);
            experience.stringStatValue = Long.toString(110 * 2000L - 40071);
            fame.invoke(entity, 2_000L);
            StatData blank = new StatData(); blank.stringStatValue = "  ";
            entity.stat.set(StatType.ACCOUNT_ID_STAT, blank);
            experience.stringStatValue = Long.toString(120 * 2000L - 40071);
            fame.invoke(entity, 3_000L);
            List<AppHistory.FameSample> samples = read("fame");
            assertEquals(3, samples.size());
            assertNull("Unknown until the account stat arrives", samples.get(0).account);
            assertEquals("The same hashed key the journal uses", CharacterJournal.accountKey("synthetic-account"), samples.get(1).account);
            assertEquals(110, samples.get(1).fame);
            assertNull("A blank account stat is unknown", samples.get(2).account);
        } finally {
            synchronized (bridge) { panel.set(bridge, previousPanel); tracker.set(bridge, previousTracker); }
        }
    }
}
