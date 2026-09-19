package tomato.backend.data;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.*;
import packets.data.enums.StatType;
import packets.incoming.*;
import packets.outgoing.HelloPacket;
import tomato.backend.TomatoPacketCapture;
import tomato.gui.character.CharacterPetsGUI;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

public class AccountMetadataTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Before @After public void resetExalts() { RealmCharacter.exalts = new TreeMap<>(); }

    @Test public void blockedHelloDoesNotBlockCaptureAndWorkerCannotPublishBeforeIdentity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread owner = Thread.currentThread();
        TomatoData data = data((token, endpoint) -> {
            assertNotSame(owner, Thread.currentThread()); assertFalse(SwingUtilities.isEventDispatchThread());
            entered.countDown(); await(release); return exalts(15);
        });
        ExecutorService probe = Executors.newSingleThreadExecutor();
        try {
            probe.submit(() -> data.updateToken("token-A")).get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown(); assertTrue(data.awaitMetadataIdle(2000));
            assertTrue(RealmCharacter.exalts.isEmpty());
            data.applyMetadataResponses(); assertTrue(RealmCharacter.exalts.isEmpty());
            identify(data, "A", 7);
            assertEquals(15, RealmCharacter.exalts.get(782)[7]);
            assertEquals(15, data.characterJournal().accounts().get(0).exalts.get(782)[7]);
        } finally { release.countDown(); data.awaitMetadataIdle(2000); probe.shutdownNow(); }
    }

    @Test public void tokenSwitchDropsOldResponseAndCannotAttachOldRosterToNewJournalAccount() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        TomatoData data = data((token, endpoint) -> {
            if ("token-A".equals(token)) { entered.countDown(); await(release); return exalts(99); }
            return endpoint.equals("char/list") ? roster("B", 8, 50) : exalts(20);
        });
        try {
            data.updateToken("token-A"); assertTrue(entered.await(2, TimeUnit.SECONDS));
            data.updateToken("token-B"); identify(data, "B", 8); data.charListHttpRequest();
            release.countDown(); assertTrue(data.awaitMetadataIdle(2000));
            assertTrue(RealmCharacter.exalts.isEmpty()); assertNull(data.chars);
            data.rememberCharacter();
            assertEquals(20, RealmCharacter.exalts.get(782)[7]);
            assertEquals(8, data.chars.get(0).charId);
            assertEquals(1, data.characterJournal().accounts().size());
            assertEquals(CharacterJournal.accountKey("B"), data.characterJournal().accounts().get(0).key);
        } finally { release.countDown(); data.awaitMetadataIdle(2000); }
    }

    @Test public void sameTokenReconnectAndNewCharacterInvalidateOldRoster() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger rosters = new AtomicInteger();
        TomatoData data = data((token, endpoint) -> {
            if (!endpoint.equals("char/list")) return exalts(1);
            int n = rosters.incrementAndGet();
            if (n == 1) { entered.countDown(); await(release); }
            return roster("A", n == 1 ? 7 : 8, n);
        });
        try {
            data.updateToken("same-token"); assertTrue(data.awaitMetadataIdle(2000)); identify(data, "A", 7);
            data.charListHttpRequest(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            data.updateToken("same-token"); identify(data, "A", 8);
            release.countDown(); assertTrue(data.awaitMetadataIdle(2000)); data.rememberCharacter();
            assertNull(data.chars);
            data.charListHttpRequest(); assertTrue(data.awaitMetadataIdle(2000)); data.rememberCharacter();
            assertEquals(8, data.chars.get(0).charId);
        } finally { release.countDown(); data.awaitMetadataIdle(2000); }
    }

    @Test public void burstsCoalesceAndDelayedRosterPreservesCapturedStatsAndPacketExalts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger rosters = new AtomicInteger();
        TomatoData data = data((token, endpoint) -> {
            if (!endpoint.equals("char/list")) return exalts(1);
            int n = rosters.incrementAndGet();
            if (n == 1) { entered.countDown(); await(release); }
            return roster("A", 7, n == 1 ? 10 : 20);
        });
        try {
            data.updateToken("token"); assertTrue(data.awaitMetadataIdle(2000)); identify(data, "A", 7);
            data.charListHttpRequest(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) data.charListHttpRequest();
            CharacterJournalTest.put(data.player, StatType.ATTACK_STAT, 95);
            CharacterJournalTest.put(data.player, StatType.ATTACK_BOOST_STAT, 20);
            CharacterJournalTest.put(data.player, StatType.CURR_FAME_STAT, 500);
            CharacterJournalTest.put(data.player, StatType.INVENTORY_0_STAT, 999);
            ExaltationUpdatePacket packet = new ExaltationUpdatePacket(); packet.objType=782; packet.healthProgress=30;
            data.exaltUpdate(packet);
            release.countDown(); assertTrue(data.awaitMetadataIdle(2000));
            assertNull(data.chars); assertEquals(30, RealmCharacter.exalts.get(782)[7]);
            data.rememberCharacter();
            assertEquals(2, rosters.get());
            assertEquals(75, data.chars.get(0).atk); assertEquals(500, data.chars.get(0).fame);
            assertEquals(999, data.chars.get(0).equipment[0]);
            assertEquals(30, RealmCharacter.exalts.get(782)[7]);
            assertEquals(Integer.valueOf(75), data.characterJournal().characters().get(0).stats[2]);
        } finally { release.countDown(); data.awaitMetadataIdle(2000); }
    }

    @Test public void mismatchedAccountAndRejectedResponsesDoNotReplaceKnownRoster() throws Exception {
        AtomicInteger rosters = new AtomicInteger();
        TomatoData data = data((token, endpoint) -> {
            if (!endpoint.equals("char/list")) return exalts(1);
            int n = rosters.incrementAndGet();
            return n == 1 ? roster("A", 7, 5) : n == 2 ? roster("B", 99, 9) : "<Error>Unavailable</Error>";
        });
        data.updateToken("token"); assertTrue(data.awaitMetadataIdle(2000)); identify(data, "A", 7);
        for (int i = 0; i < 3; i++) {
            data.charListHttpRequest(); assertTrue(data.awaitMetadataIdle(2000)); data.rememberCharacter();
            assertEquals(7, data.chars.get(0).charId); assertFalse(data.charMap.containsKey(99));
        }
        assertEquals(1, data.characterJournal().characters().size());
    }

    @Test public void rosterDecoderRetainsEquipmentPetAndPartialStatsWithoutGlobalParserSideEffects() throws Exception {
        TomatoData data = data((token, endpoint) -> endpoint.equals("char/list")
            ? "<Chars><Char id='8'><ObjectType>782</ObjectType><Level>20</Level><Texture>12</Texture>"
                + "<CreationDate>2026-01-02</CreationDate><HasBackpack>1</HasBackpack><Has3Quickslots>1</Has3Quickslots>"
                + "<Seasonal>True</Seasonal><Exp>30000</Exp><CurrentFame>15</CurrentFame><Attack>75</Attack>"
                + "<Equipment>123#abc,-1,456</Equipment><EquipQS>a,b,c</EquipQS><PCStats>AAAAAA==</PCStats>"
                + "<Pet name='Pet' createdOn='2025' instanceId='42' maxAbilityPower='70' rarity='2' skin='100' type='3'>"
                + "<Abilities><Ability type='1' power='50' points='1000'/><Ability type='2' power='40' points='800'/>"
                + "<Ability type='3' power='30' points='600'/></Abilities></Pet>"
                + "<PowerUpStats><ClassStats class='782'>1,2,3,4,5,6,7,8</ClassStats></PowerUpStats></Char></Chars>"
            : "<AccountPowerups/>");
        data.updateToken("token"); assertTrue(data.awaitMetadataIdle(2000)); identify(data,"A",7);
        data.charListHttpRequest(); assertTrue(data.awaitMetadataIdle(2000));
        assertTrue(RealmCharacter.exalts.isEmpty()); assertNull(data.chars);
        data.rememberCharacter();
        RealmCharacter c = data.charMap.get(8);
        assertArrayEquals(new int[]{123,-1,456}, c.equipment); assertArrayEquals(new String[]{"a","b","c"}, c.equipQS);
        assertEquals(4, c.capturedStatMask); assertEquals(75,c.atk); assertTrue(c.seasonal && c.backpack && c.qs3);
        assertEquals(42,c.petInstanceId); assertEquals("Pet",c.petName);
        assertArrayEquals(new int[]{1000,50,1,800,40,2,600,30,3},c.petAbilitys);
        assertNotNull(c.charStats); assertEquals("AAAAAA==",c.charStats.pcStats);
        assertEquals(8,RealmCharacter.exalts.get(782)[7]);
    }

    @Test public void observedAccountChangeDiscardsOldCredentialUntilNewHello() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        TomatoData data = data((token, endpoint) -> {
            requests.add(token + ":" + endpoint);
            if (endpoint.equals("account/listPowerUpStats")) return exalts(token.equals("token-A") ? 99 : 7);
            if (token.equals("token-A")) { entered.countDown(); await(release); return roster("A",7,99); }
            return roster("B",7,7);
        });
        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        try {
            connect(capture, "token-A", "Nexus");
            assertTrue(data.awaitMetadataIdle(2000));
            capture.packetCapture(update(object(1,782,stringStat(StatType.ACCOUNT_ID_STAT,"A"))));
            assertEquals(99, RealmCharacter.exalts.get(782)[7]);
            data.charListHttpRequest(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            capture.packetCapture(tick(status(1,stringStat(StatType.ACCOUNT_ID_STAT,"B"))));
            release.countDown(); assertTrue(data.awaitMetadataIdle(2000));
            capture.packetCapture(tick(status(1)));
            data.charListHttpRequest(); assertTrue(data.awaitMetadataIdle(2000));
            capture.packetCapture(tick(status(1)));
            assertNull(data.chars);
            assertTrue(RealmCharacter.exalts.isEmpty());
            assertEquals(2, requests.size()); // No request may reuse token-A after observing B.
            assertTrue(data.characterJournal().accounts().stream()
                .filter(a -> a.key.equals(CharacterJournal.accountKey("B"))).findFirst().get().exalts.isEmpty());
            assertEquals(99, data.characterJournal().accounts().stream()
                .filter(a -> a.key.equals(CharacterJournal.accountKey("A"))).findFirst().get().exalts.get(782)[7]);

            connect(capture,"token-B","Pet Yard");
            assertTrue(data.awaitMetadataIdle(2000));
            capture.packetCapture(update(object(1,782,stringStat(StatType.ACCOUNT_ID_STAT,"B"))));
            assertEquals(7, data.chars.get(0).charId);
            assertEquals(7, RealmCharacter.exalts.get(782)[7]);
            assertEquals(7, data.characterJournal().accounts().stream()
                .filter(a -> a.key.equals(CharacterJournal.accountKey("B"))).findFirst().get().exalts.get(782)[7]);
            assertEquals(4, requests.size());
            assertEquals(2,data.characterJournal().accounts().size());
            assertEquals(2,data.characterJournal().characters().size());
        } finally { release.countDown(); data.awaitMetadataIdle(2000); }
    }

    @Test public void petYardUpdateEstablishesLocalPlayerAndNextTickAppliesRosterWhileCollectingPets() throws Exception {
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        TomatoData data = data((token, endpoint) -> {
            if (!endpoint.equals("char/list")) return exalts(15);
            requested.countDown(); await(release); return roster("A",7,25);
        });
        AtomicReference<CharacterPetsGUI> petView = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> petView.set(new CharacterPetsGUI(null)));
        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        try {
            connect(capture,"token-A","Pet Yard");
            assertTrue(requested.await(2,TimeUnit.SECONDS));
            assertNull(data.player);
            capture.packetCapture(update(
                object(1,782,stringStat(StatType.ACCOUNT_ID_STAT,"A"),stat(StatType.CURR_FAME_STAT,500)),
                object(2,0,stat(StatType.PET_INSTANCE_ID_STAT,42),stat(StatType.PET_MAX_ABILITY_POWER_STAT,70))));
            assertNotNull(data.player); assertEquals(1,data.player.id); assertTrue(data.player.isUser());
            assertNull(data.chars);
            release.countDown(); assertTrue(data.awaitMetadataIdle(2000));
            capture.packetCapture(tick(status(1)));
            assertTrue(data.characterDataRecieved);
            assertEquals(500,data.charMap.get(7).fame); // The packet observation wins over HTTP's 25.
            assertEquals(CharacterJournal.accountKey("A"),data.characterJournal().characters().get(0).account);
            assertEquals(15,RealmCharacter.exalts.get(782)[7]);
            java.lang.reflect.Field pets = CharacterPetsGUI.class.getDeclaredField("pets"); pets.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Map<?,?> collected = (Map<?,?>)pets.get(petView.get());
                    assertEquals(1,collected.size());
                    assertEquals(70,((Stat)collected.get(42)).get(StatType.PET_MAX_ABILITY_POWER_STAT).statValue);
                } catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
        } finally { release.countDown(); data.awaitMetadataIdle(2000); SwingUtilities.invokeAndWait(CharacterPetsGUI::clearPets); }
    }

    private static void connect(TomatoPacketCapture capture, String token, String mapName) {
        HelloPacket hello = new HelloPacket(); hello.accessToken=token; capture.packetCapture(hello);
        MapInfoPacket map = new MapInfoPacket(); map.name=mapName; map.displayName=mapName; map.seed=1;
        capture.packetCapture(map);
        CreateSuccessPacket create = new CreateSuccessPacket(); create.objectId=1; create.charId=7; create.str="AAAAAA==";
        capture.packetCapture(create);
    }
    private static UpdatePacket update(ObjectData... objects) {
        UpdatePacket update = new UpdatePacket(); update.newObjects=objects; update.tiles=new GroundTileData[0]; update.drops=new int[0];
        update.pos=new WorldPosData(); return update;
    }
    private static NewTickPacket tick(ObjectStatusData... statuses) {
        NewTickPacket tick = new NewTickPacket(); tick.status=statuses; tick.serverRealTimeMS=1000; return tick;
    }
    private static ObjectData object(int id, int type, StatData... stats) {
        ObjectData object = new ObjectData(); object.objectType=type; object.status=status(id,stats); return object;
    }
    private static ObjectStatusData status(int id, StatData... stats) {
        ObjectStatusData status = new ObjectStatusData(); status.objectId=id; status.pos=new WorldPosData(); status.stats=stats; return status;
    }
    private static StatData stat(StatType type, int value) {
        StatData stat = new StatData(); stat.statType=type; stat.statTypeNum=type.get(); stat.statValue=value; return stat;
    }
    private static StatData stringStat(StatType type, String value) {
        StatData stat = stat(type,0); stat.stringStatValue=value; return stat;
    }

    private TomatoData data(TomatoData.MetadataClient client) throws IOException {
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
        return new TomatoData(client) { @Override public CharacterJournal characterJournal() { return journal; } };
    }
    private static void identify(TomatoData data, String account, int id) {
        data.setUserId(1, id, "AAAAAA==");
        data.player = new Entity(data, 1, 0); data.player.objectType=782;
        StatData identity = new StatData(); identity.stringStatValue=account; data.player.stat.set(StatType.ACCOUNT_ID_STAT, identity);
        data.rememberCharacter();
    }
    private static String exalts(int life) {
        return "<AccountPowerups><ClassPowerup><Class>782</Class><Life>" + life + "</Life></ClassPowerup></AccountPowerups>";
    }
    private static String roster(String account, int id, int fame) {
        return "<Chars><Account><AccountId>" + account + "</AccountId></Account><Char id='" + id
            + "'><ObjectType>782</ObjectType><Level>20</Level><Attack>20</Attack><Equipment>123,-1</Equipment><CurrentFame>"
            + fame + "</CurrentFame><PowerUpStats><ClassStats class='782'>0,0,0,0,0,0,0,2</ClassStats></PowerUpStats></Char></Chars>";
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Blocked request not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}
