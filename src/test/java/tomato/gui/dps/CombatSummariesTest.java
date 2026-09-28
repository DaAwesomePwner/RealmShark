package tomato.gui.dps;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatDetail;
import tomato.history.encounter.CombatFixtures;
import tomato.history.encounter.CombatFixtures.Fight;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** Saved combat summaries use the damage meter's definitions exactly; unknown stays null. Synthetic recordings only. */
public class CombatSummariesTest {
    private static final long T = 1_700_000_000_000L;
    private static final VisitRef VISIT = new VisitRef("0f8fad5b-d9cb-469f-a165-70867728950e", "journal:4");

    private static List<Entity> enemies(DpsData data) {
        List<Entity> targets = new ArrayList<>();
        for (Entity e : data.hitList.values()) if (!e.isPlayerCharacter()) targets.add(e);
        return targets;
    }
    private static CombatRecord.PlayerLine row(CombatRecord record, int objectId) {
        for (CombatRecord.PlayerLine line : record.players) if (line.objectId == objectId) return line;
        return null;
    }
    private static long sum(int[] values) { long total = 0; for (int v : values) total += v; return total; }

    @Test public void totalsRanksAndWindowEqualTheMeter() {
        DpsData data = CombatFixtures.typical(VISIT, T, 40, 5, 12);
        Entity someone = data.hitList.values().iterator().next();
        someone.genericDamageHit(null, new Projectile(77), T + 3_000); // unattributed hit: in the total, on no row
        CombatSummaries.Result result = CombatSummaries.build(data);
        CombatRecord record = result.record();
        CombatMeterData meter = new CombatMeterData(enemies(data), null, true);
        assertEquals(meter.total, record.totalDamage); assertEquals(meter.unattributed, record.unattributedDamage);
        assertEquals(77, record.unattributedDamage);
        assertEquals(meter.seconds, record.windowSeconds, 0); assertEquals(Long.valueOf(meter.first), record.firstHitAt);
        assertEquals(Long.valueOf(meter.last), record.lastHitAt);
        assertEquals(meter.rows.size(), record.players.size()); assertEquals(record.players.size(), record.contributors);
        for (CombatMeterData.Row expected : meter.rows) {
            CombatRecord.PlayerLine line = row(record, expected.player.id);
            assertEquals(expected.damage, line.damage); assertEquals(expected.hits, line.hits); assertEquals(expected.biggest, line.maxHit);
            assertEquals(Long.valueOf(expected.taken), line.taken); assertEquals(expected.player.objectType, line.classType);
            assertEquals(expected.player.name(), line.name);
            assertEquals(meter.dps(expected), record.dps(line)); assertEquals(meter.share(expected), record.share(line));
        }
        MeterSummary summary = MeterSummary.of(enemies(data), null, "Lost Halls", 100);
        assertEquals(summary.players(), record.contributors);
        for (int i = 0; i < record.players.size(); i++) {
            assertEquals("rank order is the meter summary's", summary.top().get(i).damage(), record.players.get(i).damage);
            assertEquals(summary.top().get(i).name(), record.players.get(i).name);
            assertEquals(i + 1, record.players.get(i).rank);
        }
        assertEquals("Lost Halls", record.map); assertEquals("Lost Halls", record.mapName);
        assertEquals(data.getRecordingId(), record.recordingId); assertEquals(data.getRecordingId(), result.detail().recordingId);
        assertEquals(1, record.schemaVersion); assertEquals(1, result.detail().schemaVersion); assertFalse(record.fullDetail);
    }

    @Test public void ranksBreakDamageTiesByObjectIdAndLeaveOutPlayersWithoutDamage() {
        Fight fight = CombatFixtures.fight("Snake Pit");
        Entity a = fight.player(9, 768, "Nine"), b = fight.player(4, 775, "Four"), c = fight.player(6, 782, "Six");
        Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, a, 300, T).hit(boss, b, 300, T + 500).hit(boss, c, 500, T + 1000);
        CombatRecord record = CombatSummaries.build(fight.context(VISIT, null, T).build()).record();
        assertEquals(Arrays.asList(6, 4, 9), Arrays.asList(record.players.get(0).objectId, record.players.get(1).objectId, record.players.get(2).objectId));
        assertEquals(3, record.contributors);
    }

    @Test public void theLocalFlagIsOnlyForTheVerifiedObjectId() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity user = fight.user(1, 768, "Self"), verified = fight.player(2, 782, "Other");
        Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, user, 900, T).hit(boss, verified, 400, T + 1000);
        CombatRecord record = CombatSummaries.build(fight.context(VISIT, 2, T - 1000).captured(verified).build()).record();
        assertEquals(Integer.valueOf(2), record.localObjectId); assertEquals(Integer.valueOf(782), record.localClassType);
        assertFalse("isUser alone never marks the local row", row(record, 1).local);
        assertTrue(row(record, 2).local); assertSame(row(record, 2), record.local());
        assertEquals(Long.valueOf(400), record.localDamage());
    }

    @Test public void anUnverifiedLocalPlayerGivesNoLocalRowAndAVerifiedOneWithoutDamageIsZero() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity user = fight.user(1, 768, "Self"); Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, user, 900, T).captured(user);
        CombatRecord unverified = CombatSummaries.build(fight.context(VISIT, null, T).build()).record();
        assertNull(unverified.localObjectId); assertNull(unverified.localClassType); assertNull(unverified.local());
        assertNull("unknown, never 0", unverified.localDamage());
        for (CombatRecord.PlayerLine line : unverified.players) assertFalse(line.local);
        for (CombatDetail.Series series : CombatSummaries.build(fight.build()).detail().series) assertFalse(series.local);

        Fight idle = CombatFixtures.fight("Lost Halls");
        Entity self = idle.user(3, 775, "Idle"), other = idle.player(4, 782, "Busy"); Entity foe = idle.enemy(100, 5000, "Boss", 5000, true);
        idle.hit(foe, other, 500, T);
        CombatRecord verifiedIdle = CombatSummaries.build(idle.context(VISIT, 3, T).captured(self).build()).record();
        assertEquals(Integer.valueOf(3), verifiedIdle.localObjectId); assertNull(verifiedIdle.local());
        assertEquals("a verified local player without recorded damage dealt 0", Long.valueOf(0), verifiedIdle.localDamage());
        assertEquals(Integer.valueOf(775), verifiedIdle.localClassType);
    }

    @Test public void takenIsNullWhenIncomingDamageIsUnavailableForTheRow() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity user = fight.user(1, 768, "Self"), hit = fight.player(2, 782, "Hit"), quiet = fight.player(3, 784, "Quiet");
        Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, user, 100, T).hit(boss, hit, 100, T + 100).hit(boss, quiet, 100, T + 200).taken(hit, boss, 30, T + 5_000);
        CombatRecord record = CombatSummaries.build(fight.context(VISIT, 1, T).build()).record();
        assertEquals("the user's incoming stream is captured: none recorded is 0", Long.valueOf(0), row(record, 1).taken);
        assertEquals("whole recording, outside the hit window too", Long.valueOf(30), row(record, 2).taken);
        assertNull("remote player without incoming events: unavailable, not 0", row(record, 3).taken);
    }

    @Test public void maxHpIsNullWhenTheStatIsAbsentAndEnemiesAreGroupedByTypeWithBossesListed() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity self = fight.user(1, 768, "Self");
        Entity bossKnown = fight.enemy(100, 5000, "Known boss", 90_000, true), bossUnknown = fight.enemy(101, 5001, "Unknown boss", null, true);
        Entity minionA = fight.enemy(200, 6000, "Minion", 500, false), minionB = fight.enemy(201, 6000, "Minion", 800, false);
        Entity minionC = fight.enemy(202, 6000, "Minion", null, false), bare = fight.enemy(300, 7000, "Bare", null, false);
        fight.hit(bossKnown, self, 1000, T).hit(bossUnknown, self, 2000, T + 100).hit(minionA, self, 10, T + 200)
            .hit(minionB, self, 20, T + 300).hit(minionC, null, 30, T + 400).hit(bare, self, 5, T + 500);
        CombatSummaries.Result result = CombatSummaries.build(fight.context(VISIT, 1, T).build());
        CombatRecord record = result.record();
        assertEquals(6, record.enemies); assertEquals(4, record.enemyTypes);
        assertEquals("one entry per boss type", 2, record.bosses.size()); assertEquals(0, record.bossTypesOmitted);
        assertEquals(5001, record.bosses.get(0).type); assertEquals("Unknown boss", record.bosses.get(0).name); assertEquals(1, record.bosses.get(0).count);
        assertNull("absent MAX_HP is unknown, never 0", record.bosses.get(0).maxHp); assertEquals(2000, record.bosses.get(0).damage);
        assertEquals(5000, record.bosses.get(1).type); assertEquals(1, record.bosses.get(1).count);
        assertEquals(Integer.valueOf(90_000), record.bosses.get(1).maxHp);
        List<CombatDetail.EnemyType> types = result.detail().enemies;
        assertEquals(Arrays.asList(5001, 5000, 6000, 7000), Arrays.asList(types.get(0).type, types.get(1).type, types.get(2).type, types.get(3).type));
        CombatDetail.EnemyType minions = types.get(2);
        assertEquals(3, minions.count); assertEquals("Minion", minions.name); assertEquals(Integer.valueOf(800), minions.maxHp);
        assertEquals("unattributed hits count on the enemy", 60, minions.damage); assertEquals(3, minions.hits); assertFalse(minions.boss);
        assertNull(types.get(3).maxHp); assertTrue(types.get(0).boss);
    }

    @Test public void manyBossObjectsOfOneTypeBecomeOneEntryWithItsCount() {
        int sentry = 2_000_000_001, warden = 2_000_000_002, shade = 2_000_000_003; // types no asset list names
        Fight fight = CombatFixtures.fight("Realm");
        Entity self = fight.user(1, 768, "Self");
        Integer[] maxHp = {1_000, null, 3_000, 2_000, null};
        for (int i = 0; i < 5; i++) {
            Entity boss = fight.enemy(100 + i, sentry, i == 0 ? null : "Sentry", maxHp[i], true);
            fight.hit(boss, i == 4 ? null : self, 10 * (i + 1), T + i * 100);
        }
        fight.hit(fight.enemy(200, warden, "Warden", null, true), self, 100, T + 1_000);
        fight.hit(fight.enemy(300, shade, "Shade", null, true), self, 150, T + 1_100);
        fight.hit(fight.enemy(301, shade, "Shade", null, true), self, 1, T + 1_200);
        CombatRecord record = CombatSummaries.build(fight.context(VISIT, 1, T).build()).record();
        assertEquals(3, record.bosses.size()); assertEquals(0, record.bossTypesOmitted);
        CombatRecord.Boss first = record.bosses.get(0);
        assertEquals(shade, first.type); assertEquals(2, first.count); assertEquals(151, first.damage);
        assertNull("no max HP known for the type", first.maxHp);
        CombatRecord.Boss sentries = record.bosses.get(1);
        assertEquals(sentry, sentries.type); assertEquals(5, sentries.count);
        assertEquals("summed, unattributed hits included", 150, sentries.damage);
        assertEquals("the largest known", Integer.valueOf(3_000), sentries.maxHp); assertEquals("the first known name", "Sentry", sentries.name);
        assertEquals(warden, record.bosses.get(2).type); assertEquals(1, record.bosses.get(2).count);
        assertEquals(8, record.enemies); assertEquals(3, record.enemyTypes);
    }

    @Test public void tenBossTypesKeepTheEightLargestAndCountTheRest() {
        Fight fight = CombatFixtures.fight("Realm");
        Entity self = fight.user(1, 768, "Self");
        for (int t = 0; t < 10; t++) fight.hit(fight.enemy(100 + t, 5000 + t, "Boss " + t, 1_000, true), self, 100 * (t + 1), T + t * 100);
        CombatSummaries.Result result = CombatSummaries.build(fight.context(VISIT, 1, T).build());
        CombatRecord record = result.record();
        assertEquals(CombatRecord.BOSS_TYPES, record.bosses.size()); assertEquals(2, record.bossTypesOmitted);
        for (int i = 0; i < 8; i++) assertEquals("damage descending", 5009 - i, record.bosses.get(i).type);
        assertEquals("the detail keeps every type with its boss flag", 10, result.detail().enemies.size());
        for (CombatDetail.EnemyType type : result.detail().enemies) assertTrue(type.boss);
        fight.hit(fight.enemy(200, 5001, "Boss 1", 1_000, true), self, 800, T + 2_000); // 5001 now ties 5009 at 1,000
        record = CombatSummaries.build(fight.build()).record();
        assertEquals("equal damage orders by type", 5001, record.bosses.get(0).type); assertEquals(2, record.bosses.get(0).count);
        assertEquals(1_000, record.bosses.get(0).damage); assertEquals(5009, record.bosses.get(1).type); assertEquals(2, record.bossTypesOmitted);
    }

    @Test public void aRealmLikeFightWithManyBossObjectsKeepsTheRecordCardSized() {
        Fight fight = CombatFixtures.fight("Realm");
        List<Entity> party = new ArrayList<>();
        for (int p = 1; p <= 20; p++) party.add(p == 1 ? fight.user(p, 768, "Player" + p) : fight.player(p, CombatFixtures.CLASSES[p % 8], "Player" + p));
        for (int b = 0; b < 200; b++) {
            Entity boss = fight.enemy(1_000 + b, 7_000 + b % 20, "Boss type " + b % 20, 50_000 + b % 20, true);
            for (int p = 0; p < party.size(); p++) fight.hit(boss, party.get(p), 200 + (b * 7 + p * 13) % 300, T + b * 3_000L + p * 50);
        }
        for (int e = 0; e < 300; e++) fight.hit(fight.enemy(5_000 + e, 8_000 + e % 50, "Enemy type " + e % 50, 900, false),
            party.get(e % party.size()), 150, T + e * 2_000L);
        CombatSummaries.Result result = CombatSummaries.build(fight.ticks(T, 600_000).context(VISIT, 1, T - 5_000).build());
        int record = SessionStore.JSON.toJson(result.record()).getBytes(StandardCharsets.UTF_8).length;
        int detail = SessionStore.JSON.toJson(result.detail()).getBytes(StandardCharsets.UTF_8).length;
        System.out.println("Combat summary JSON (Realm-like: 20 players, 200 boss objects of 20 types, 300 other enemies): record "
            + record + " B, detail " + detail + " B");
        assertEquals(8, result.record().bosses.size()); assertEquals(12, result.record().bossTypesOmitted);
        for (CombatRecord.Boss boss : result.record().bosses) assertEquals(10, boss.count);
        assertEquals(500, result.record().enemies); assertEquals(70, result.record().enemyTypes);
        assertTrue("record " + record + " B", record <= 16 * 1024);
    }

    @Test public void deathsArePerRowOnlyForKnownNamesUniqueInTheRecording() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity alice = fight.player(1, 768, "Alice"), bob = fight.player(2, 775, "Bob"), bob2 = fight.player(3, 782, "Bob");
        Entity nameless = fight.player(4, 784, null), cara = fight.player(5, 797, "Cara");
        Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        for (Entity p : Arrays.asList(alice, bob, bob2, nameless, cara)) fight.hit(boss, p, 100 + p.id, T + p.id);
        fight.death("Alice", 3).death("Bob", 4).death(null, 5).death("Alice", 3).death("Zed", 6);
        CombatSummaries.Result result = CombatSummaries.build(fight.context(VISIT, 1, T).build());
        CombatRecord record = result.record();
        assertEquals("every death notification", 5, record.deaths);
        assertEquals(Integer.valueOf(2), row(record, 1).deaths);
        assertNull("a name shared by two rows is never attributed", row(record, 2).deaths); assertNull(row(record, 3).deaths);
        assertNull("unknown name", row(record, 4).deaths);
        assertEquals("known, unique, no death", Integer.valueOf(0), row(record, 5).deaths);
        List<CombatDetail.Death> deaths = result.detail().deaths;
        assertEquals(5, deaths.size()); assertEquals("Alice", deaths.get(0).name); assertEquals(3, deaths.get(0).graveIcon);
        assertNull(deaths.get(2).name); assertEquals(5, deaths.get(2).graveIcon); assertEquals("Zed", deaths.get(4).name);
    }

    @Test public void seriesAreTheVerifiedLocalPlayerPlusTheTopTwelve() {
        Fight fight = CombatFixtures.fight("Realm");
        Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        for (int p = 1; p <= 15; p++) { Entity player = fight.player(p, 768, "P" + p); fight.hit(boss, player, 1000 - p * 10, T + p * 100); }
        CombatSummaries.Result outside = CombatSummaries.build(fight.context(VISIT, 14, T).build());
        List<CombatDetail.Series> series = outside.detail().series;
        assertEquals(13, series.size());
        for (int i = 0; i < 12; i++) { assertEquals(i + 1, series.get(i).objectId); assertFalse(series.get(i).local); }
        assertEquals(14, series.get(12).objectId); assertTrue(series.get(12).local);
        for (CombatDetail.Series s : series) assertEquals(row(outside.record(), s.objectId).damage, sum(s.values));
        assertEquals("sources for every contributor", 15, outside.detail().sources.size());

        List<CombatDetail.Series> inside = CombatSummaries.build(fight.context(VISIT, 3, T).build()).detail().series;
        assertEquals(12, inside.size()); assertTrue(inside.get(2).local); assertEquals(3, inside.get(2).objectId);
        assertEquals(12, CombatSummaries.build(fight.context(VISIT, null, T).build()).detail().series.size());
    }

    @Test public void bucketsStartAtTheFirstTickAndWidenBeyondThirtyMinutes() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity self = fight.user(1, 768, "Self"); Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, self, 10, T + 2_500).hit(boss, self, 20, T + 2_900).hit(boss, self, 40, T + 1_799_999);
        CombatDetail detail = CombatSummaries.build(fight.ticks(T, 1_800_000).context(VISIT, 1, T - 5_000).build()).detail();
        assertEquals(T, detail.bucketOrigin); assertEquals(1, detail.bucketSeconds);
        int[] values = detail.series.get(0).values;
        assertEquals("1,800 one-second buckets", 1800, values.length);
        assertEquals(30, values[2]); assertEquals(40, values[1799]); assertEquals(70, sum(values));

        fight.hit(boss, self, 80, T + 1_800_000); // 1,801 seconds: widen to 2 s
        detail = CombatSummaries.build(fight.build()).detail();
        assertEquals(2, detail.bucketSeconds); assertEquals(901, detail.series.get(0).values.length);
        assertEquals(150, sum(detail.series.get(0).values)); assertEquals(30, detail.series.get(0).values[1]);

        fight.hit(boss, self, 5, T + 3 * 3_600_000L); // 10,801 seconds: ceil(10801 / 1800) = 7 s
        detail = CombatSummaries.build(fight.build()).detail();
        assertEquals(7, detail.bucketSeconds); assertTrue(detail.series.get(0).values.length <= 1800);
        assertEquals(1543, detail.series.get(0).values.length); assertEquals(155, sum(detail.series.get(0).values));

        Fight untimed = CombatFixtures.fight("Lost Halls");
        Entity player = untimed.user(1, 768, "Self"); Entity foe = untimed.enemy(100, 5000, "Boss", 5000, true);
        untimed.hit(foe, player, 10, T + 4_000).hit(foe, player, 10, T + 6_500);
        CombatSummaries.Result noTicks = CombatSummaries.build(untimed.context(VISIT, 1, T).build());
        assertEquals("no tick: the first hit is the origin", T + 4_000, noTicks.detail().bucketOrigin);
        assertArrayEquals(new int[]{10, 0, 10}, noTicks.detail().series.get(0).values);
        assertNull(noTicks.record().startedAt); assertNull(noTicks.record().elapsedMs);
    }

    @Test public void hitsBeforeTheFirstTickCountInTotalsAndAreReportedNotBucketed() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity self = fight.user(1, 768, "Self"), other = fight.player(2, 782, "Other");
        Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, self, 500, -1).hit(boss, other, 300, -1).hit(boss, self, 100, T + 1_000).hit(boss, other, 50, T + 2_000);
        CombatSummaries.Result result = CombatSummaries.build(fight.ticks(T, 3_000).context(VISIT, 1, T - 500).build());
        CombatRecord record = result.record();
        assertEquals(950, record.totalDamage); assertEquals(600, record.local().damage); assertEquals(2, record.local().hits);
        assertEquals(2, record.hitsBeforeFirstTick);
        assertEquals(100, sum(result.detail().series.get(0).values)); assertEquals(50, sum(result.detail().series.get(1).values));
        assertEquals(Long.valueOf(T), record.startedAt); assertEquals(Long.valueOf(3_000), record.elapsedMs);
        assertEquals(Long.valueOf(T - 500), record.enteredAt);
        assertEquals(Long.valueOf(T + 1_000), record.firstHitAt); assertEquals(1.0, record.windowSeconds, 0);
    }

    @Test public void legacyRecordingsHaveNoVisitNoLocalRowAndNoEntryTime() {
        Fight fight = CombatFixtures.fight("Pirate Cave");
        Entity self = fight.user(1, 768, "Self"); Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, self, 100, T).hit(boss, self, 100, T + 2_000);
        CombatRecord record = CombatSummaries.build(fight.build()).record();
        assertNull(record.visitSession); assertNull(record.visitId); assertNull(record.visit());
        assertNull(record.localObjectId); assertNull(record.local()); assertNull(record.enteredAt); assertNull(record.localClassType);
        assertEquals(200, record.totalDamage); assertEquals(2.0, record.windowSeconds, 0);

        Fight unlinked = CombatFixtures.fight("Pirate Cave");
        Entity user = unlinked.user(1, 768, "Self"); Entity foe = unlinked.enemy(100, 5000, "Boss", 5000, true);
        unlinked.hit(foe, user, 100, T);
        CombatRecord noVisit = CombatSummaries.build(unlinked.context(null, 1, T - 1).build()).record();
        assertNull(noVisit.visitSession); assertNull(noVisit.visitId); assertEquals(Integer.valueOf(1), noVisit.localObjectId);
        assertNull("a single instant is no window", noVisit.windowSeconds); assertNull(noVisit.dps(noVisit.local()));

        CombatRecord linked = CombatSummaries.build(CombatFixtures.typical(VISIT, T, 10, 2, 3)).record();
        assertEquals(VISIT, linked.visit()); assertEquals(VISIT.sessionId, linked.visitSession); assertEquals(VISIT.visitId, linked.visitId);

        CombatSummaries.Result empty = CombatSummaries.build(CombatFixtures.fight("Nexus").context(VISIT, null, T).build());
        assertEquals(0, empty.record().contributors); assertNull(empty.record().windowSeconds); assertNull(empty.record().firstHitAt);
        assertNull(empty.record().lastHitAt); assertTrue(empty.detail().series.isEmpty()); assertEquals(0, empty.record().deaths);
    }

    @Test public void sourcesGroupByDamageSourceThenItemLargestFirst() {
        Fight fight = CombatFixtures.fight("Lost Halls");
        Entity self = fight.user(1, 768, "Self"); Entity boss = fight.enemy(100, 5000, "Boss", 5000, true);
        fight.hit(boss, self, 100, T, DamageSource.WEAPON, 2001).hit(boss, self, 150, T + 100, DamageSource.WEAPON, 2002)
            .hit(boss, self, 120, T + 200, DamageSource.WEAPON, 2001).hit(boss, self, 500, T + 300, DamageSource.ABILITY, 0)
            .hit(boss, self, 40, T + 400);
        CombatDetail detail = CombatSummaries.build(fight.context(VISIT, 1, T).build()).detail();
        List<CombatDetail.SourceLine> lines = detail.sources.get(0).sources;
        assertEquals(1, detail.sources.get(0).objectId);
        assertEquals(Arrays.asList("ABILITY", "WEAPON", "UNKNOWN"), Arrays.asList(lines.get(0).source, lines.get(1).source, lines.get(2).source));
        assertEquals(500, lines.get(0).damage); assertTrue("no item captured: only in the source total", lines.get(0).items.isEmpty());
        CombatDetail.SourceLine weapon = lines.get(1);
        assertEquals(370, weapon.damage); assertEquals(3, weapon.hits);
        assertEquals(2001, weapon.items.get(0).itemId); assertEquals(220, weapon.items.get(0).damage); assertEquals(2, weapon.items.get(0).hits);
        assertEquals(2002, weapon.items.get(1).itemId);
    }

    @Test public void aRecordBuiltAfterTheCaptureClearedItsProjectilesKeepsDamageAndSources() throws Exception {
        TomatoData data = new TomatoData();
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "The Nest"; data.map = map;
        Entity self = new Entity(data, 1, 0); self.objectType = 768; data.player = self;
        Entity boss = new Entity(data, 20, 0); boss.objectType = 5000;
        Field hitList = TomatoData.class.getDeclaredField("entityHitList"); hitList.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, Entity> targets = (Map<Integer, Entity>) hitList.get(data);
        targets.put(boss.id, boss);
        Field shared = TomatoData.class.getDeclaredField("projectiles"); shared.setAccessible(true);
        Projectile[] projectiles = (Projectile[]) shared.get(data);
        Projectile weapon = new Projectile(250); weapon.setSource(DamageSource.WEAPON, 2001); projectiles[3] = weapon;
        boss.genericDamageHit(self, weapon, T); boss.updateDamageTaken(T);
        boss.genericDamageHit(self, weapon, T + 1_000); boss.updateDamageTaken(T + 1_000);
        data.clear();
        assertEquals("TomatoData.clear() reset the shared projectile after building the recording", 0, weapon.getDamage());
        DpsData closed = data.dpsData.get(0);
        CombatSummaries.Result result = CombatSummaries.build(closed);
        assertEquals(500, result.record().totalDamage); assertEquals(250, result.record().players.get(0).maxHit);
        CombatDetail.SourceLine line = result.detail().sources.get(0).sources.get(0);
        assertEquals("WEAPON", line.source); assertEquals(500, line.damage); assertEquals(2001, line.items.get(0).itemId);
        assertEquals(500, sum(result.detail().series.get(0).values));

        String before = SessionStore.JSON.toJson(result.record()) + SessionStore.JSON.toJson(result.detail());
        boss.genericDamageHit(self, new Projectile(999), T + 2_000);
        String rebuilt = SessionStore.JSON.toJson(CombatSummaries.build(closed).record());
        assertEquals("detached: later changes to the graph never reach a built result",
            before, SessionStore.JSON.toJson(result.record()) + SessionStore.JSON.toJson(result.detail()));
        assertNotEquals(SessionStore.JSON.toJson(result.record()), rebuilt);
    }

    @Test public void aTypicalFightIsCardSizedAndItsDetailIsCompact() {
        CombatSummaries.Result result = CombatSummaries.build(CombatFixtures.typical(VISIT, T, 150, 8, 60));
        int record = SessionStore.JSON.toJson(result.record()).getBytes(StandardCharsets.UTF_8).length;
        int detail = SessionStore.JSON.toJson(result.detail()).getBytes(StandardCharsets.UTF_8).length;
        System.out.println("Combat summary JSON (150 s, 8 players, 60 enemies): record " + record + " B, detail " + detail + " B");
        assertEquals(8, result.record().contributors); assertEquals(60, result.record().enemies); assertEquals(20, result.record().enemyTypes);
        assertEquals(150, result.detail().series.get(0).values.length); assertEquals(8, result.detail().series.size());
        assertEquals(1, result.record().deaths); assertEquals(Integer.valueOf(1), row(result.record(), 3).deaths);
        assertTrue("record " + record + " B", record <= 16 * 1024);
        assertTrue("detail " + detail + " B", detail <= 64 * 1024);
    }
}
