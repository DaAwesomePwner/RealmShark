package tomato.history.encounter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.*;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

/**
 * Synthetic combat recordings for tests (synthetic names only; no capture): a {@link Fight} builder for {@link DpsData}
 * in the style of {@code DpsInvestigationTest}/{@code MeterSummaryTest}, the {@code SessionStore.put} file layout for
 * saved records and details, and {@link #writeLarge} beside {@code HomeHistoryFixture.writeLarge}.
 */
public final class CombatFixtures {
    /** Class types used for synthetic players, cycled by player index. */
    public static final int[] CLASSES = {768, 775, 782, 784, 797, 798, 799, 800};
    private static final String[] DUNGEONS = {"Lost Halls", "Ice Citadel", "Pirate Cave", "Snake Pit"}; // HomeHistoryFixture's cycle
    private static final DamageSource[] SOURCES = {DamageSource.WEAPON, DamageSource.ABILITY, DamageSource.SUMMON, DamageSource.ITEM_EFFECT};

    private CombatFixtures() {}

    public static Fight fight(String map) { return new Fight(map); }

    /** A PlayerDeath notification whose message carries {@code name} where {@code DeathParser} reads it; null gives an unparsable one. */
    public static NotificationPacket death(String name, int graveIcon) {
        NotificationPacket packet = new NotificationPacket();
        packet.effect = packets.data.enums.NotificationEffectType.PlayerDeath;
        packet.message = name == null ? "{\"k\":\"s.death\"}" : "{\"k\":\"s.death\",\"t\":{\"player\":\"" + name + "\",\"level\":\"20\"}}";
        packet.pictureType = graveIcon;
        return packet;
    }

    /** One synthetic encounter under construction. Hits go through {@code Entity.genericDamageHit} as capture records them. */
    public static final class Fight {
        private final MapInfoPacket map;
        private final HashMap<Integer, Entity> hitList = new HashMap<>();
        private final ArrayList<NotificationPacket> deaths = new ArrayList<>();
        private final List<Projectile> projectiles = new ArrayList<>();
        private long firstTick = -1, elapsed;
        private EncounterContext context;
        private boolean legacy = true;
        private Entity captured;

        private Fight(String name) { map = new MapInfoPacket(); map.name = name; map.displayName = name; }

        public MapInfoPacket map() { return map; }
        /** Every projectile behind a recorded hit, so a test can reset them as {@code TomatoData.clear()} does. */
        public List<Projectile> projectiles() { return projectiles; }

        /** A remote player (never the user); {@code name} null records no NAME stat. */
        public Entity player(int id, int classType, String name) {
            Entity player = new Entity(null, id, 0); player.objectType = classType; player.markPlayerIdentity();
            if (name != null) { StatData stat = new StatData(); stat.stringStatValue = name; player.stat.set(StatType.NAME_STAT, stat); }
            return player;
        }
        /** The capture's own character ({@code isUser}); whether it is the verified local row depends on the context only. */
        public Entity user(int id, int classType, String name) { Entity user = player(id, classType, name); user.setUser(7); return user; }

        /** An enemy object; {@code maxHp} null records no MAX_HP stat. */
        public Entity enemy(int id, int type, String name, Integer maxHp, boolean boss) {
            Entity enemy = new Entity(null, id, 0) {
                @Override public String name() { return name; }
                @Override public boolean isBossMob() { return boss; }
            };
            enemy.objectType = type;
            if (maxHp != null) { StatData hp = new StatData(); hp.statValue = maxHp; enemy.stat.set(StatType.MAX_HP_STAT, hp); }
            hitList.put(id, enemy);
            return enemy;
        }

        /** An outgoing hit with no recorded source ({@code owner} null = unattributed); time -1 = before the first tick. */
        public Fight hit(Entity enemy, Entity owner, int damage, long time) { return hit(enemy, owner, damage, time, null, 0); }
        public Fight hit(Entity enemy, Entity owner, int damage, long time, DamageSource source, int item) {
            Projectile projectile = new Projectile(damage);
            if (source != null) projectile.setSource(source, item);
            projectiles.add(projectile);
            enemy.genericDamageHit(owner, projectile, time);
            enemy.updateDamageTaken(time);
            return this;
        }
        /** Incoming damage on a player, as DAMAGE packets record it. */
        public Fight taken(Entity player, Entity from, int damage, long time) { player.getDamageList().add(new Damage(from, time, damage)); return this; }
        public Fight death(String name, int graveIcon) { deaths.add(CombatFixtures.death(name, graveIcon)); return this; }
        public Fight ticks(long first, long elapsedMs) { firstTick = first; elapsed = elapsedMs; return this; }
        /** The entry-frozen identity; {@code visit} or {@code localId} may be null. Without a call the recording is legacy. */
        public Fight context(VisitRef visit, Integer localId, long capturedAt) { context = new EncounterContext(visit, localId, capturedAt); legacy = false; return this; }
        /** The capture-owned player at close (the local class and guild context). */
        public Fight captured(Entity player) { captured = player; return this; }

        public DpsData build() {
            return legacy ? new DpsData(map, hitList, deaths, elapsed, firstTick, null)
                : new DpsData(map, hitList, deaths, elapsed, firstTick, null, captured, context);
        }
    }

    /**
     * A dense fight of {@code seconds} s: {@code players} players (object IDs 1..n, "Player1"…, player 1 is the user and
     * the verified local row) hitting {@code enemies} enemies of {@code enemies / 3} types (the last two types bosses, one
     * type without max HP) twice a second each, with four sources of two items each, incoming hits every 5 s and one death.
     * The first tick is {@code start}, the entry 5 s before it.
     */
    public static DpsData typical(VisitRef visit, long start, int seconds, int players, int enemies) {
        Fight fight = fight("Lost Halls");
        List<Entity> party = new ArrayList<>();
        for (int p = 1; p <= players; p++) {
            int classType = CLASSES[(p - 1) % CLASSES.length];
            party.add(p == 1 ? fight.user(p, classType, "Player" + p) : fight.player(p, classType, "Player" + p));
        }
        int types = Math.max(1, enemies / 3);
        List<Entity> foes = new ArrayList<>();
        for (int e = 0; e < enemies; e++) {
            int type = e % types;
            foes.add(fight.enemy(1000 + e, 5000 + type, "Enemy type " + type, type == 1 ? null : 1000 * (type + 1), type >= types - 2));
        }
        for (int s = 0; s < seconds; s++) for (int p = 0; p < players; p++) for (int k = 0; k < 2; k++) {
            int index = (s + p + k) % SOURCES.length;
            Entity target = foes.get((s * players + p * 2 + k) % foes.size());
            fight.hit(target, party.get(p), 100 + (p * 37 + s * 13 + k * 7) % 400, start + s * 1000L + p * 50L + k * 500L,
                SOURCES[index], 10_000 + index * 100 + (s + k) % 2);
        }
        for (int s = 0; s < seconds; s += 5) for (int p = 0; p < players; p++)
            fight.taken(party.get(p), foes.get((s + p) % foes.size()), 40 + p, start + s * 1000L + 250);
        fight.death("Player" + Math.min(3, players), 7);
        return fight.ticks(start, seconds * 1000L).context(visit, 1, start - 5_000).captured(party.get(0)).build();
    }

    /**
     * Makes {@code fight} the encounter in progress in {@code data}, as capture leaves it before it publishes a snapshot: the hit
     * list (a copy of the fight's map), its first and current tick ({@link Fight#ticks}: the live elapsed time is the fight's),
     * the map and the death notifications, set by reflection where {@code TomatoData} keeps them private (as
     * {@code SheetFixtures.inject} does). Call it before capture starts, off the EDT; then publish with
     * {@code DpsGUI.updateMapPacket(data)}.
     */
    public static void installLive(TomatoData data, DpsData fight) {
        try {
            java.lang.reflect.Field hits = TomatoData.class.getDeclaredField("entityHitList"); hits.setAccessible(true);
            hits.set(data, new HashMap<>(fight.hitList));
            java.lang.reflect.Field first = TomatoData.class.getDeclaredField("timePcFirst"); first.setAccessible(true);
            first.setLong(data, fight.dungeonStartTime);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        data.timePc = fight.dungeonStartTime + fight.totalDungeonPcTime;
        data.map = fight.map;
        data.getDeathNotifications().clear();
        data.getDeathNotifications().addAll(fight.deathNotifications);
    }

    /** Writes a record where {@code SessionStore.put("encounters", recordingId, record)} would. */
    public static Path writeRecord(Path root, String session, CombatRecord record) throws IOException {
        return write(root, session, "encounters", record.recordingId, SessionStore.JSON.toJson(record));
    }
    /** Writes a detail where {@code SessionStore.put("encounter-detail", recordingId, detail)} would. */
    public static Path writeDetail(Path root, String session, CombatDetail detail) throws IOException {
        return write(root, session, "encounter-detail", detail.recordingId, SessionStore.JSON.toJson(detail));
    }
    /** Writes raw JSON (for malformed or future files) in the {@code put} layout. */
    public static Path write(Path root, String session, String module, String key, String json) throws IOException {
        Path folder = Files.createDirectories(root.resolve(session).resolve(module));
        Path file = folder.resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** The recording ID {@link #writeLarge} gives run {@code visitId} of {@code session}. */
    public static String largeRecordingId(String session, String visitId) {
        return UUID.nameUUIDFromBytes(("combat-fixture:" + session + ":" + visitId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Adds one record and one detail per run of {@code HomeHistoryFixture.writeLarge(root, sessions, runs)} (same session
     * and visit IDs, map and times): a real {@link CombatSummaries} summary of an 8-player, 60-enemy, 150 s fight, first
     * tick 100 ms after the visit started (inside its 150 s), linked by the exact visit with player 1 as the verified local row.
     */
    public static void writeLarge(Path root, int sessions, int runs) throws IOException {
        long templateStart = HomeHistoryFixture.NOW;
        CombatSummaries.Result template = CombatSummaries.build(typical(new VisitRef(HomeHistoryFixture.id("large-0"), "L0-0"), templateStart, 150, 8, 60));
        CombatRecord record = template.record(); CombatDetail detail = template.detail();
        long started = record.startedAt, first = record.firstHitAt, last = record.lastHitAt, origin = detail.bucketOrigin;
        for (int s = 0; s < sessions; s++) {
            String session = HomeHistoryFixture.id("large-" + s);
            long start = HomeHistoryFixture.NOW - (4L * s + 2) * HomeHistoryFixture.HOUR;
            for (int r = 0; r < runs; r++) {
                long begin = start + r * 3 * HomeHistoryFixture.MINUTE, shift = begin + 100 - templateStart;
                String visitId = "L" + s + "-" + r, id = largeRecordingId(session, visitId);
                record.recordingId = detail.recordingId = id;
                record.map = record.mapName = DUNGEONS[r % DUNGEONS.length];
                record.visitSession = session; record.visitId = visitId;
                record.enteredAt = begin; record.startedAt = started + shift; record.firstHitAt = first + shift; record.lastHitAt = last + shift;
                detail.bucketOrigin = origin + shift;
                writeRecord(root, session, record);
                writeDetail(root, session, detail);
            }
        }
    }
}
