package tomato.gui.dps;

import assets.IdToAsset;
import java.util.*;
import packets.data.enums.StatType;
import packets.incoming.NotificationPacket;
import tomato.backend.data.*;
import tomato.gui.dps.shared.DeathParser;
import tomato.history.encounter.CombatDetail;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.EncounterContext;

/**
 * Builds the saved summary of one closed recording: a card-sized {@link CombatRecord} and a recap-sized
 * {@link CombatDetail}. The meter's own projection ({@link CombatMeterData} over all enemies, whole encounter, no player
 * filter preset, as {@code DpsGUI.recordedEncounters}) defines damage, hits, biggest hit, taken, window and share, and
 * rows rank as {@link MeterSummary} does (damage descending, then object ID; players without recorded damage are not
 * ranked). Only {@code Damage.damage}, {@code DamageSource.of} and {@code DamageSource.itemOf} are read from hits:
 * {@code TomatoData.clear()} resets the shared projectiles after the recording is built. Pure and off the EDT (the combat
 * history worker); the result shares nothing with the recording.
 */
public final class CombatSummaries {
    /** The saved pair for one recording; both carry the recording's ID. */
    public record Result(CombatRecord record, CombatDetail detail) {}

    /** Contributors with a damage-over-time series besides the verified local player. */
    static final int SERIES_TOP = 12;
    /** A series never holds more values than this: longer fights widen the bucket. */
    static final int MAX_BUCKETS = 1800;

    private CombatSummaries() {}

    public static Result build(DpsData data) {
        Objects.requireNonNull(data, "data");
        EncounterContext context = data.getEncounterContext();
        Integer localId = context == null ? null : context.localPlayerObjectId;
        List<Entity> targets = new ArrayList<>();
        if (data.hitList != null) for (Entity e : data.hitList.values().toArray(new Entity[0])) if (e != null && !e.isPlayerCharacter()) targets.add(e);
        targets.sort(Comparator.comparingInt(e -> e.id));
        CombatMeterData meter = new CombatMeterData(targets, null, true);
        List<CombatMeterData.Row> ranked = new ArrayList<>();
        for (CombatMeterData.Row row : meter.rows) if (row.damage > 0) ranked.add(row);
        ranked.sort(Comparator.comparingLong((CombatMeterData.Row row) -> row.damage).reversed().thenComparingInt(row -> row.player.id));

        CombatRecord record = new CombatRecord();
        record.recordingId = data.getRecordingId();
        if (data.map != null) { record.map = data.map.name; record.mapName = data.map.displayName; }
        record.enteredAt = context == null ? null : context.capturedAt;
        record.startedAt = data.dungeonStartTime > 0 ? data.dungeonStartTime : null;
        record.elapsedMs = record.startedAt == null ? null : Math.max(0, data.totalDungeonPcTime);
        record.firstHitAt = meter.first == Long.MAX_VALUE ? null : meter.first;
        record.lastHitAt = record.firstHitAt == null || meter.last < 0 ? null : meter.last;
        record.windowSeconds = meter.seconds > 0 ? meter.seconds : null; // a single instant is no window: DPS unknown
        if (context != null && context.visit != null) { record.visitSession = context.visit.sessionId; record.visitId = context.visit.visitId; }
        record.localObjectId = localId;
        record.totalDamage = meter.total;
        record.unattributedDamage = meter.unattributed;
        record.contributors = ranked.size();

        List<NotificationPacket> notices = new ArrayList<>();
        if (data.deathNotifications != null) for (NotificationPacket n : data.deathNotifications.toArray(new NotificationPacket[0])) if (n != null) notices.add(n);
        record.deaths = notices.size();
        record.players = players(ranked, localId, notices);
        CombatRecord.PlayerLine local = record.local();
        if (local != null && local.classType > 0) record.localClassType = local.classType;
        else if (localId != null && local == null && data.getLocalPlayerContext() != null && data.getLocalPlayerContext().hasClass())
            record.localClassType = data.getLocalPlayerContext().classType; // verified, no recorded damage: the class captured at close

        CombatDetail detail = new CombatDetail();
        detail.recordingId = record.recordingId;
        enemies(targets, record, detail);
        for (NotificationPacket n : notices) {
            CombatDetail.Death death = new CombatDetail.Death();
            String name = DeathParser.extractName(n);
            death.name = name == null || name.isEmpty() ? null : name;
            death.graveIcon = n.pictureType;
            detail.deaths.add(death);
        }
        buckets(targets, ranked, localId, record, detail);
        for (CombatMeterData.Row row : ranked) detail.sources.add(sources(row));
        return new Result(record, detail);
    }

    /** Meter rows in rank order. Deaths are matched by name only inside this recording, and only for a unique known name. */
    private static List<CombatRecord.PlayerLine> players(List<CombatMeterData.Row> ranked, Integer localId, List<NotificationPacket> notices) {
        Map<String, Integer> names = new HashMap<>(), deaths = new HashMap<>();
        for (CombatMeterData.Row row : ranked) { String name = name(row.player); if (name != null) names.merge(name, 1, Integer::sum); }
        for (NotificationPacket n : notices) { String name = DeathParser.extractName(n); if (name != null && !name.isEmpty()) deaths.merge(name, 1, Integer::sum); }
        List<CombatRecord.PlayerLine> lines = new ArrayList<>(ranked.size());
        for (CombatMeterData.Row row : ranked) {
            CombatRecord.PlayerLine line = new CombatRecord.PlayerLine();
            line.objectId = row.player.id;
            line.name = name(row.player);
            line.classType = row.player.objectType;
            line.damage = row.damage; line.hits = row.hits; line.maxHit = row.biggest;
            line.taken = row.incomingAvailable ? row.taken : null; // whole encounter: taken == totalTaken
            line.local = localId != null && row.player.id == localId;
            line.deaths = line.name != null && names.get(line.name) == 1 ? deaths.getOrDefault(line.name, 0) : null;
            line.rank = lines.size() + 1;
            lines.add(line);
        }
        return lines;
    }

    private static String name(Entity player) { String name = player.name(); return name == null || name.isEmpty() ? null : name; }

    /** Enemies grouped by type for the detail; boss types (at most {@link CombatRecord#BOSS_TYPES}) in the record. */
    private static void enemies(List<Entity> targets, CombatRecord record, CombatDetail detail) {
        Map<Integer, CombatDetail.EnemyType> types = new LinkedHashMap<>();
        Map<Integer, CombatRecord.Boss> bosses = new LinkedHashMap<>();
        for (Entity e : targets) {
            long damage = 0, hits = 0;
            for (Damage hit : new ArrayList<>(e.getDamageList())) { damage += hit.damage; hits++; }
            Integer maxHp = e.stat.get(StatType.MAX_HP_STAT) == null ? null : e.maxHp();
            boolean isBoss = e.isBossMob();
            String name = enemyName(e);
            CombatDetail.EnemyType type = types.computeIfAbsent(e.objectType, t -> { CombatDetail.EnemyType created = new CombatDetail.EnemyType(); created.type = t; return created; });
            type.count++; type.damage += damage; type.hits += hits; type.boss |= isBoss;
            if (type.name == null) type.name = name;
            if (maxHp != null && (type.maxHp == null || maxHp > type.maxHp)) type.maxHp = maxHp;
            if (!isBoss) continue;
            CombatRecord.Boss boss = bosses.computeIfAbsent(e.objectType, t -> { CombatRecord.Boss created = new CombatRecord.Boss(); created.type = t; return created; });
            boss.count++; boss.damage += damage;
            if (boss.name == null) boss.name = name;
            if (maxHp != null && (boss.maxHp == null || maxHp > boss.maxHp)) boss.maxHp = maxHp;
        }
        record.enemies = targets.size();
        record.enemyTypes = types.size();
        List<CombatRecord.Boss> ordered = new ArrayList<>(bosses.values());
        ordered.sort(Comparator.comparingLong((CombatRecord.Boss b) -> b.damage).reversed().thenComparingInt(b -> b.type));
        record.bosses.addAll(ordered.subList(0, Math.min(CombatRecord.BOSS_TYPES, ordered.size())));
        record.bossTypesOmitted = ordered.size() - record.bosses.size();
        detail.enemies.addAll(types.values());
        detail.enemies.sort(Comparator.comparingLong((CombatDetail.EnemyType t) -> t.damage).reversed().thenComparingInt(t -> t.type));
    }

    private static String enemyName(Entity e) {
        String name = e.name();
        if (name == null || name.isEmpty()) try { name = IdToAsset.objectName(e.objectType); } catch (RuntimeException missingAssets) { name = null; }
        return name == null || name.isEmpty() ? null : name;
    }

    /**
     * Damage over time from the first tick (else the first hit): 1 s buckets while the hits span at most 1,800 one-second
     * buckets, else {@code ceil(seconds / 1800)} s. Hits stamped before the origin (time -1 before the first tick) are
     * counted in the totals but not bucketed.
     */
    private static void buckets(List<Entity> targets, List<CombatMeterData.Row> ranked, Integer localId, CombatRecord record, CombatDetail detail) {
        long origin = record.startedAt != null ? record.startedAt : record.firstHitAt != null ? record.firstHitAt : Long.MAX_VALUE;
        if (origin == Long.MAX_VALUE) for (CombatMeterData.Row row : ranked) for (Damage hit : row.outgoing) if (hit.time >= 0) origin = Math.min(origin, hit.time);
        if (origin == Long.MAX_VALUE) origin = 0;
        int before = 0;
        for (Entity e : targets) for (Damage hit : new ArrayList<>(e.getDamageList())) if (hit.time < 0 || hit.time < origin) before++;
        record.hitsBeforeFirstTick = before;
        detail.bucketOrigin = origin;
        long last = -1;
        for (CombatMeterData.Row row : ranked) for (Damage hit : row.outgoing) if (hit.time >= 0 && hit.time >= origin) last = Math.max(last, hit.time);
        if (last < 0) return;
        long seconds = (last - origin) / 1000 + 1;
        int width = seconds <= MAX_BUCKETS ? 1 : (int) ((seconds + MAX_BUCKETS - 1) / MAX_BUCKETS);
        detail.bucketSeconds = width;
        int length = (int) ((last - origin) / (width * 1000L)) + 1;
        for (int i = 0; i < ranked.size(); i++) {
            CombatMeterData.Row row = ranked.get(i);
            boolean local = localId != null && row.player.id == localId;
            if (i >= SERIES_TOP && !local) continue;
            CombatDetail.Series series = new CombatDetail.Series();
            series.objectId = row.player.id; series.local = local; series.values = new int[length];
            for (Damage hit : row.outgoing) {
                if (hit.time < 0 || hit.time < origin) continue;
                int bucket = (int) ((hit.time - origin) / (width * 1000L));
                series.values[bucket] = (int) Math.min(Integer.MAX_VALUE, (long) series.values[bucket] + hit.damage);
            }
            detail.series.add(series);
        }
    }

    /** One row's damage grouped by source and then by item or summon type, largest first (as the meter's breakdown). */
    private static CombatDetail.PlayerSources sources(CombatMeterData.Row row) {
        Map<DamageSource, CombatDetail.SourceLine> bySource = new EnumMap<>(DamageSource.class);
        Map<DamageSource, Map<Integer, CombatDetail.ItemLine>> items = new EnumMap<>(DamageSource.class);
        for (Damage hit : row.outgoing) {
            DamageSource source = DamageSource.of(hit);
            CombatDetail.SourceLine line = bySource.computeIfAbsent(source, s -> { CombatDetail.SourceLine created = new CombatDetail.SourceLine(); created.source = s.name(); return created; });
            line.damage += hit.damage; line.hits++;
            int item = DamageSource.itemOf(hit);
            if (item <= 0) continue;
            CombatDetail.ItemLine itemLine = items.computeIfAbsent(source, s -> new LinkedHashMap<>())
                .computeIfAbsent(item, id -> { CombatDetail.ItemLine created = new CombatDetail.ItemLine(); created.itemId = id; return created; });
            itemLine.damage += hit.damage; itemLine.hits++;
        }
        CombatDetail.PlayerSources result = new CombatDetail.PlayerSources();
        result.objectId = row.player.id;
        for (Map.Entry<DamageSource, CombatDetail.SourceLine> entry : bySource.entrySet()) {
            CombatDetail.SourceLine line = entry.getValue();
            Map<Integer, CombatDetail.ItemLine> byItem = items.get(entry.getKey());
            if (byItem != null) line.items.addAll(byItem.values());
            line.items.sort(Comparator.comparingLong((CombatDetail.ItemLine i) -> i.damage).reversed().thenComparingInt(i -> i.itemId));
            result.sources.add(line);
        }
        result.sources.sort(Comparator.comparingLong((CombatDetail.SourceLine s) -> s.damage).reversed()); // stable: source order on ties
        return result;
    }
}
