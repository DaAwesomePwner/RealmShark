package tomato.gui.dps;

import tomato.backend.data.*;
import tomato.realmshark.enums.CharacterClass;
import java.util.*;

/** Read-only projections of the recorded hit stream; never changes saved encounters. */
final class CombatMeterData {
    static final class Row {
        final Entity player;
        long damage, hits, biggest, taken, incomingHits, totalTaken, totalIncomingHits;
        boolean incomingAvailable;
        final List<Damage> outgoing = new ArrayList<>(), incoming = new ArrayList<>();
        Row(Entity player) { this.player = player; }
        String className() {
            String name = CharacterClass.getName(player.objectType);
            return name == null ? "Unknown" : name;
        }
    }
    final List<Row> rows = new ArrayList<>();
    long total, unattributed, first = Long.MAX_VALUE, last = Long.MIN_VALUE;
    double seconds;

    CombatMeterData(List<Entity> targets, Entity localPlayer) {
        this(targets, localPlayer, false);
    }

    CombatMeterData(List<Entity> targets, Entity localPlayer, boolean wholeEncounter) {
        Map<Integer, Row> players = new LinkedHashMap<>();
        for (Entity target : targets) {
            if (target.isPlayerCharacter()) continue;
            if (target.getFirstDamageTaken() >= 0) first = Math.min(first, target.getFirstDamageTaken());
            last = Math.max(last, target.getLastDamageTaken());
            for (Damage hit : new ArrayList<>(target.getDamageList())) {
                total += hit.damage;
                if (hit.owner == null) { unattributed += hit.damage; continue; }
                Row row = players.computeIfAbsent(hit.owner.id, id -> new Row(hit.owner));
                row.damage += hit.damage;
                row.hits++;
                row.biggest = Math.max(row.biggest, hit.damage);
                row.outgoing.add(hit);
            }
        }
        if (localPlayer != null) players.computeIfAbsent(localPlayer.id, id -> new Row(localPlayer));
        seconds = last > first ? (last - first) / 1000.0 : 0;
        for (Row row : players.values()) {
            List<Damage> recorded = new ArrayList<>(row.player.getDamageList());
            // DAMAGE packets retain incoming events on remote players as well as the user.
            row.incomingAvailable = row.player.isUser() || !recorded.isEmpty();
            for (Damage hit : recorded) {
                row.totalTaken += hit.damage;
                row.totalIncomingHits++;
                // Includes both boundaries, and each event once even when fights overlap.
                if (!wholeEncounter && (hit.time < first || hit.time > last)) continue;
                row.taken += hit.damage;
                row.incomingHits++;
                row.incoming.add(hit);
            }
            row.outgoing.sort(Comparator.comparingLong(hit -> hit.time));
            row.incoming.sort(Comparator.comparingLong(hit -> hit.time));
            rows.add(row);
        }
    }
    /** One player's outgoing damage from one source, with per-item totals; ordered by damage. */
    static final class SourceShare {
        final DamageSource source;
        long damage, hits;
        final Map<String, long[]> items = new LinkedHashMap<>(); // item name -> {damage, hits}
        SourceShare(DamageSource source) { this.source = source; }
    }

    /** Groups every outgoing hit of the row by source, then item, largest damage first. */
    static List<SourceShare> sources(Row row) {
        Map<DamageSource, SourceShare> bySource = new EnumMap<>(DamageSource.class);
        for (Damage hit : row.outgoing) {
            SourceShare share = bySource.computeIfAbsent(DamageSource.of(hit), SourceShare::new);
            share.damage += hit.damage; share.hits++;
            String item = DamageSource.itemName(hit);
            if (item == null) continue;
            long[] totals = share.items.computeIfAbsent(item, name -> new long[2]);
            totals[0] += hit.damage; totals[1]++;
        }
        List<SourceShare> result = new ArrayList<>(bySource.values());
        result.sort((a, b) -> Long.compare(b.damage, a.damage));
        for (SourceShare share : result) {
            List<Map.Entry<String, long[]>> items = new ArrayList<>(share.items.entrySet());
            items.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            share.items.clear();
            for (Map.Entry<String, long[]> item : items) share.items.put(item.getKey(), item.getValue());
        }
        return result;
    }

    Double dps(Row row) { return seconds > 0 ? row.damage / seconds : null; }
    Double share(Row row) { return total > 0 ? row.damage * 100.0 / total : null; }
    static long windowMillis(Entity entity) { return Math.max(0,entity.getLastDamageTaken()-entity.getFirstDamageTaken()); }
    static final String WINDOW_DEFINITION = "First to last recorded hit on selected enemies; same duration for every player. Incoming fight bounds include both endpoints, once per event.";
    static final String POPULATION = "Incoming rankings include represented outgoing contributors plus the captured local player when available; not a full player roster. Remote players without incoming events are unavailable, not zero.";
}
