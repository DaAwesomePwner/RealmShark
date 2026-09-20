package packets.packetcapture.logger;

import packets.Packet;
import packets.PacketType;
import packets.data.*;
import packets.data.enums.ConditionBits;
import packets.data.enums.ConditionNewBits;
import packets.data.enums.NotificationEffectType;
import packets.incoming.*;
import packets.outgoing.UseItemPacket;
import tomato.backend.data.InspectSnapshot;
import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterStatistics;
import java.util.*;

/** Gameplay history; Inspect adds allowlisted player loadouts, never account identifiers or chat. */
public final class ActivityJournal {
    public static final int RUN_LIMIT = 200, EVENT_LIMIT = 1000;
    public static final int INSPECT_PLAYER_LIMIT = 300;
    private final List<Visit> visits = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, int[]> exaltBaseline = new HashMap<>();
    private final Map<Integer, String> exaltVisit = new HashMap<>();
    private final Map<Integer, int[]> pendingExalts = new HashMap<>();
    // Used only to compare identities in memory. Never included in snapshots or logs.
    private String accountScope;
    private boolean identityVerified;
    private final LinkedHashMap<Integer, Integer> ownerCandidates = new LinkedHashMap<>(), shotOwners = new LinkedHashMap<>();
    private Visit current;
    private final Map<Integer, String> inspectedIdentities = new HashMap<>();
    private CompletionBaseline completionBaseline;
    private int[] pendingCompletions;
    private Integer pendingCompletionCharacter;
    private Integer localId, characterId, condition, conditionNew;
    private long lastTick, lastConditionTime, lastResourceEvent, lastResourceSample;
    private int nextVisit;
    private long nextEntry;
    private long revision, summariesRevision, choicesRevision, entriesRevision;
    private long viewCopies, fullCopies, copiedVisits, copiedEntries, copiedPoints, copiedSlices, pinnedReferences, copyOnWriteReferences;
    private final String session = UUID.randomUUID().toString();
    private static final String[] EXALTS = {"Dexterity", "Speed", "Vitality", "Wisdom", "Defense", "Attack", "Mana", "Health"};

    public ActivityJournal() {}
    ActivityJournal(State saved) {
        if (saved == null) return;
        for (Visit v : saved.visits) {
            Visit copy=new Visit(v);
            if (copy.ended == 0) { copy.ended = copy.lastSeen; copy.endReason = "App ended"; if (copy.completionEvidence.isEmpty()) copy.status = "App ended; completion unknown"; }
            visits.add(copy);
        }
        for (Entry entry : saved.entries) entries.add(copyEntry(entry));
        trim(); limitTimelines();
    }

    public void boundary(long now, String reason) {
        finish(now, reason);
        inspectedIdentities.clear(); pendingCompletions = null;
        if (!reason.startsWith("Connection boundary")) completionBaseline = null;
        localId = characterId = condition = conditionNew = null;
        lastTick = 0;
        // Preserve progress only for comparison after the same account is independently observed again.
        identityVerified = false; pendingExalts.clear(); ownerCandidates.clear(); shotOwners.clear();
    }
    public void clear() {
        boolean hadHistory=!visits.isEmpty() || !entries.isEmpty();
        boundary(System.currentTimeMillis(), "Cleared"); visits.clear(); entries.clear();
        exaltBaseline.clear(); exaltVisit.clear(); accountScope = null;
        completionBaseline = null;
        if (hadHistory) summariesRevision = choicesRevision = entriesRevision = ++revision;
    }

    public void observe(Packet packet, PacketType type, String outcome, long now, Map<String, Object> diagnostic) {
        // A pinned history owns the old version. Only subsequently mutated visits are copied.
        writableCurrent();
        if (current != null) changed(current);
        if (!"decoded".equals(outcome)) {
            if (current != null) { current.issues++; current.lastSeen = now; }
            add(now, "Capture issue", type == null ? "Unknown packet" : type.name(), diagnostic);
            condition = conditionNew = null; lastTick = 0;
            invalidateResources(now);
            // A missing progress or identity packet invalidates the comparison baseline.
            if (type == PacketType.EXALTATION_BONUS_CHANGED || type == PacketType.CREATE_SUCCESS) {
                exaltBaseline.clear(); pendingExalts.clear(); identityVerified = false;
            }
            if (type == PacketType.CREATE_SUCCESS || type == PacketType.MAPINFO) { completionBaseline = null; pendingCompletions = null; }
            if (type == PacketType.MAPINFO) boundary(now, "Map transition could not be decoded");
            return;
        }
        if (packet instanceof MapInfoPacket) {
            finish(now, "Area left; completion unknown");
            MapInfoPacket p = (MapInfoPacket)packet;
            current = new Visit(); current.id = session + ":" + (++nextVisit);
            current.damageTracked = true;
            inspectedIdentities.clear(); pendingCompletions = null;
            current.map = tomato.realmshark.ParseDungeon.canonicalMapName(p);
            current.started = current.lastSeen = now; current.difficulty = Float.isFinite(p.difficulty) ? p.difficulty : 0;
            current.realmStart = current.realmLatest = p.currentRealmScore < 0 ? null : p.currentRealmScore;
            current.realmMaximum = p.maxRealmScore < 0 ? null : p.maxRealmScore;
            visits.add(current); localId = condition = conditionNew = null; lastTick = 0;
            changed(current); choicesRevision = ++revision;
            identityVerified = false; pendingExalts.clear();
            ownerCandidates.clear(); shotOwners.clear();
            add(now, "Area entered", current.map, Collections.emptyMap()); trim();
        }
        if (current != null) { current.frames++; current.lastSeen = now; }
        if (packet instanceof CreateSuccessPacket) {
            CreateSuccessPacket p = (CreateSuccessPacket)packet;
            if (!Objects.equals(characterId, p.charId)) identityVerified = false;
            characterId = p.charId; localId = p.objectId; condition = conditionNew = null; lastTick = 0;
            invalidateResources(now);
            if (current != null) current.equipment.clear();
        } else if (packet instanceof NotificationPacket && ((NotificationPacket)packet).effect == NotificationEffectType.Victory) {
            completeCurrent(now, "Server victory notification");
        } else if (packet instanceof TextPacket) {
            String evidence = completionDialogue((TextPacket)packet);
            if (evidence != null) completeCurrent(now, evidence);
        } else if (packet instanceof ExaltationUpdatePacket) {
            exalts((ExaltationUpdatePacket)packet, now);
        } else if (packet instanceof IncomingPartyMemberInfoPacket) {
            Map<String, Object> values = DiscoveryCatalog.values(packet, type);
            if (current != null) {
                current.partyId = ((IncomingPartyMemberInfoPacket)packet).partyId;
                current.rosterSize = (Integer)values.get("memberCount");
            }
            add(now, "Party roster", "Observed roster; membership identity unverified", values);
        } else if (packet instanceof PartyMemberAddedPacket || packet instanceof PartyActionResultPacket) {
            add(now, "Party activity", type.name(), DiscoveryCatalog.values(packet, type));
        } else if (type == PacketType.PARTY_JOIN_REQUEST || type == PacketType.PARTY_ACTION
                || type == PacketType.PARTY_REQUEST_RESPONSE || type == PacketType.INCOMING_PARTY_INVITE) {
            add(now, "Party request / response", "Separate observation; a request does not establish membership", DiscoveryCatalog.values(packet, type));
        } else if (packet instanceof UseItemPacket) {
            UseItemPacket p = (UseItemPacket)packet;
            if (current != null) current.useRequests++;
            Map<String, Object> values = DiscoveryCatalog.values(packet, type);
            if (p.slotObject != null) {
                values.put("slotLabel", p.slotObject.slotId == 1000000 ? "Potion storage" : "Slot " + p.slotObject.slotId);
                if (current != null) increment(current.requestedItems, p.slotObject.objectType);
            }
            add(now, "Item / ability request", "Server acceptance not established", values);
        } else if (type == PacketType.INVSWAP || type == PacketType.INVRESULT) {
            add(now, type == PacketType.INVSWAP ? "Inventory request" : "Inventory result", "Independent observation; not paired with a use request", DiscoveryCatalog.values(packet, type));
        } else if (packet instanceof RealmScoreUpdatePacket && current != null) {
            int score = ((RealmScoreUpdatePacket)packet).score;
            if (current.realmStart == null) current.realmStart = score;
            current.realmLatest = score;
        } else if (packet instanceof ShowEffectPacket && current != null) {
            increment(current.effects, Byte.toUnsignedInt(((ShowEffectPacket)packet).effectType));
        } else if (packet instanceof ServerPlayerShootPacket) {
            ServerPlayerShootPacket p = (ServerPlayerShootPacket)packet;
            if (p.summonerId > 0) {
                putBounded(shotOwners, p.ownerId, p.summonerId);
                compareOwner(p.ownerId, p.summonerId, ownerCandidates.get(p.ownerId), now);
            }
        } else if (packet instanceof NewTickPacket) {
            NewTickPacket p = (NewTickPacket)packet;
            if (current != null) {
                current.ticks++; current.tickMillis += p.tickTime;
                current.maxTickMillis = Math.max(current.maxTickMillis, p.tickTime);
                advanceConditions(now);
                lastTick = now;
            }
            if (p.status != null) for (ObjectStatusData s : p.status) status(s, now);
        } else if (packet instanceof UpdatePacket) {
            UpdatePacket p = (UpdatePacket)packet;
            if (p.newObjects != null) for (ObjectData object : p.newObjects) if (object != null) status(object.status, now);
            if (p.drops != null) for (int id : p.drops) {
                ownerCandidates.remove(id); shotOwners.remove(id);
                if (Objects.equals(localId, id)) { condition = conditionNew = null; lastTick = 0; invalidateResources(now); }
            }
        }
    }

    private void status(ObjectStatusData s, long now) {
        if (s == null || s.stats == null) return;
        if (Objects.equals(localId, s.objectId)) advanceConditions(now);
        Map<String, Object> changes = new LinkedHashMap<>();
        for (StatData stat : s.stats) {
            if (stat == null) continue;
            if (stat.statTypeNum == 38 && Objects.equals(localId, s.objectId) && stat.stringStatValue != null && !stat.stringStatValue.isEmpty()) {
                if (!stat.stringStatValue.equals(accountScope)) {
                    exaltBaseline.clear(); exaltVisit.clear(); accountScope = stat.stringStatValue;
                    completionBaseline = null;
                }
                identityVerified = true;
                for (Map.Entry<Integer,int[]> pending : pendingExalts.entrySet()) compareExalts(pending.getKey(), pending.getValue(), now);
                pendingExalts.clear();
                compareCompletions();
            }
            if (stat.stringStatValue != null) continue;
            if (stat.statTypeNum == 114) {
                Integer before = ownerCandidates.get(s.objectId);
                putBounded(ownerCandidates, s.objectId, stat.statValue);
                if (!Objects.equals(before, stat.statValue)) compareOwner(s.objectId, stat.statValue, shotOwners.get(s.objectId), now);
            }
            if (current == null || !Objects.equals(localId, s.objectId)) continue;
            int value = stat.statValue;
            switch (stat.statTypeNum) {
                case 0: current.maxHp = value; break;
                case 3: current.maxMp = value; break;
                case 1:
                    current.hpSamples++; current.hpMin = min(current.hpMin, value); current.hpMax = max(current.hpMax, value);
                    if (current.hp != null) { long delta = (long)value - current.hp; if (delta > 0) current.hpRises += delta; else current.hpFalls -= delta; }
                    if (!Objects.equals(current.hp, value)) changes.put("hp", value);
                    current.hp = value; break;
                case 4:
                    current.mpSamples++; current.mpMin = min(current.mpMin, value); current.mpMax = max(current.mpMax, value);
                    if (!Objects.equals(current.mp, value)) changes.put("mp", value);
                    current.mp = value; break;
                case 29: condition = value; break;
                case 96: conditionNew = value; break;
                default:
                    if (stat.statTypeNum >= 8 && stat.statTypeNum <= 11) {
                        Integer before = current.equipment.put(stat.statTypeNum - 8, value);
                        if (before != null && before != value) {
                            Map<String, Object> gear = new LinkedHashMap<>(); gear.put("slot", stat.statTypeNum - 8);
                            gear.put("before", before); gear.put("after", value);
                            add(now, "Equipment changed", "Observed equipped item", gear);
                        }
                    }
            }
        }
        if (!changes.isEmpty() && now - lastResourceEvent >= 1000) {
            add(now, "Resources", "Local HP/MP sample; changes do not identify healing or damage sources", changes);
            lastResourceEvent = now;
        }
        if (current != null && Objects.equals(localId,s.objectId) && (current.hp != null || current.mp != null)
                && (current.resourceTimeline.isEmpty() || now-lastResourceSample>=1000)) {
            ResourcePoint point=new ResourcePoint(); point.time=now; point.hp=current.hp; point.mp=current.mp;
            current.resourceTimeline.add(point); limitTimelines(); lastResourceSample=now;
        }
    }

    private void advanceConditions(long now) {
        if (current == null) return;
        if (lastTick != 0 && now >= lastTick && now - lastTick <= 2000 && now >= lastConditionTime) {
            long elapsed = now - lastConditionTime;
            if (elapsed > 0 && (condition != null || conditionNew != null)) {
                List<ConditionSlice> slices = current.conditionTimeline;
                ConditionSlice last = slices.isEmpty() ? null : slices.get(slices.size()-1);
                if (last != null && last.end == lastConditionTime && Objects.equals(last.primary,condition) && Objects.equals(last.secondary,conditionNew)) {
                    // Timeline elements may belong to an older pinned visit: replace, never mutate.
                    ConditionSlice extended=new ConditionSlice(); extended.start=last.start; extended.end=now;
                    extended.primary=last.primary; extended.secondary=last.secondary; slices.set(slices.size()-1,extended); copiedSlices++;
                }
                else {
                    ConditionSlice slice=new ConditionSlice(); slice.start=lastConditionTime; slice.end=now;
                    slice.primary=condition; slice.secondary=conditionNew; slices.add(slice); limitTimelines();
                }
            }
            if (condition != null) {
                current.conditionObservedMillis += elapsed;
                for (ConditionBits bit : ConditionBits.getEffects(condition)) current.conditions.merge(bit.name(), elapsed, Long::sum);
            }
            if (conditionNew != null) {
                current.extraConditionObservedMillis += elapsed;
                for (ConditionNewBits bit : ConditionNewBits.getEffects(conditionNew)) current.extraConditions.merge(bit.name(), elapsed, Long::sum);
            }
        } else if (lastTick != 0) { condition = conditionNew = null; invalidateResources(now); current.timingGaps++; lastTick = 0; }
        lastConditionTime = now;
    }

    private void invalidateResources(long now) {
        if (current == null) return;
        if (current.hp != null || current.mp != null) {
            // A null marker prevents the renderer joining fresh samples across even a short decode failure.
            ResourcePoint gap = new ResourcePoint(); gap.time = now;
            current.resourceTimeline.add(gap); limitTimelines();
        }
        current.hp = current.mp = null;
    }

    private void compareOwner(int object, int owner, Integer other, long now) {
        if (other == null || current == null) return;
        boolean matches = other == owner;
        if (matches) current.ownerMatches++; else current.ownerConflicts++;
        // Deduplicate repeated shots after the first cross-check. No damage attribution is changed.
        ownerCandidates.remove(object); shotOwners.remove(object);
        Map<String, Object> values = new LinkedHashMap<>(); values.put("objectId", object); values.put("ownerId", owner);
        values.put("otherSourceOwner", other);
        add(now, "Ownership check", matches ? "Stat 114 agrees with summon shot" : "Stat 114 conflicts with summon shot", values);
    }

    private void exalts(ExaltationUpdatePacket p, long now) {
        int classId = Short.toUnsignedInt(p.objType);
        int[] next = {p.dexterityProgress,p.speedProgress,p.vitalityProgress,p.wisdomProgress,p.defenseProgress,p.attackProgress,p.manaProgress,p.healthProgress};
        if (!identityVerified) {
            if (pendingExalts.size() < 32 || pendingExalts.containsKey(classId)) pendingExalts.put(classId, next);
            Map<String, Object> values = new LinkedHashMap<>(); values.put("classId", classId); values.put("progress", next.clone());
            add(now, "Exalt snapshot", "Awaiting local identity; no progress inferred yet", values);
            return;
        }
        compareExalts(classId, next, now);
    }
    private void compareExalts(int classId, int[] next, long now) {
        String visitId = current == null ? "" : current.id;
        String previousVisit = localId == null ? null : exaltVisit.put(classId, visitId);
        int[] before = localId == null ? null : exaltBaseline.put(classId, next);
        Map<String, Object> values = new LinkedHashMap<>(); values.put("classId", classId); values.put("progress", next.clone());
        if (before == null) { add(now, "Exalt baseline", "Snapshot; no earned progress inferred", values); return; }
        for (int i = 0; i < next.length; i++) {
            if (next[i] == before[i]) continue;
            Map<String, Object> delta = new LinkedHashMap<>(); delta.put("classId", classId); delta.put("stat", EXALTS[i]);
            delta.put("before", before[i]); delta.put("after", next[i]); delta.put("delta", (long)next[i] - before[i]);
            boolean sameVisit = visitId.equals(previousVisit);
            delta.put("sameVisit", sameVisit);
            add(now, "Exalt change", next[i] > before[i] ? "Observed progress increase; dungeon completion unconfirmed" : "Progress decreased; baseline refreshed", delta);
            if (!sameVisit) {
                Entry entry = entries.get(entries.size() - 1); entry.visitId = ""; entry.map = "Between visits; source unassigned";
            }
            if (sameVisit && current != null && next[i] > before[i]) current.exaltIncrease += (long)next[i] - before[i];
        }
    }
    private void finish(long now, String status) {
        if (current != null) {
            writableCurrent(); current.ended = now; current.endReason = status;
            if (current.completionEvidence.isEmpty()) current.status = status;
            changed(current); current = null;
        }
    }

    private void completeCurrent(long now, String evidence) {
        if (current != null && ParseDungeon.isDungeon(current.map)) complete(visits.size() - 1, now, evidence);
    }

    private void complete(int index, long now, String evidence) {
        Visit visit = visits.get(index);
        if (!visit.completionEvidence.isEmpty()) return;
        visit = writable(index);
        visit.completionEvidence = evidence; visit.completionObservedAt = now;
        visit.status = "Completed"; changed(visit);
    }

    // Exact server boss dialogue already used by loot attribution; miniboss lines are deliberately absent.
    private String completionDialogue(TextPacket p) {
        if (current == null || p.name == null || !p.name.startsWith("#") || p.text == null) return null;
        if ("Moonlight Village".equals(current.map) && (
                ("#Kitsune Umi".equals(p.name) && "This fully concludes the Moonlight Festival!".equals(p.text)) ||
                ("#Dancer Miko".equals(p.name) && "Thank you all for coming tonight.".equals(p.text)) ||
                ("#Umi, Goddess of Revelry".equals(p.name) && "This fully concludes the Moonlight Festival.".equals(p.text))))
            return "Final boss dialogue: " + p.name.substring(1);
        if ("The Void".equals(current.map) && "#Void Entity".equals(p.name)
                && "You fools... You can never truly defeat me! I am in all of you! I AM all of you!".equals(p.text))
            return "Final boss dialogue: Void Entity";
        if ("The Shatters".equals(current.map) && (
                ("#The Accursed King".equals(p.name) && "...do you truly think your end will be any different?".equals(p.text)) ||
                ("#King Azamoth".equals(p.name) && "This fate is mine to bear... not hers.".equals(p.text))))
            return "Final boss dialogue: " + p.name.substring(1);
        return null;
    }

    public void completionStats(int character, int[] counts) {
        if (current == null) return;
        if (counts == null || counts.length != CharacterStatistics.DUNGEON_NAMES.size()) {
            pendingCompletions = null; completionBaseline = null; return;
        }
        pendingCompletionCharacter = character; pendingCompletions = counts.clone();
        if (identityVerified) compareCompletions();
    }

    private void compareCompletions() {
        if (current == null || pendingCompletions == null || !Objects.equals(characterId, pendingCompletionCharacter)) return;
        if (completionBaseline != null && completionBaseline.character == characterId && visits.size() >= 2) {
            Visit previous = visits.get(visits.size() - 2);
            long gap = current.started - previous.lastSeen;
            if (previous.id.equals(completionBaseline.visit) && gap >= 0 && gap <= 30000 && ParseDungeon.isDungeon(previous.map)) {
                for (int i = 0; i < pendingCompletions.length; i++) {
                    String dungeon = ParseDungeon.canonicalName(CharacterStatistics.DUNGEON_NAMES.get(i));
                    if (previous.map.equals(dungeon) && completionBaseline.counts[i] >= 0
                            && (long)pendingCompletions[i] - completionBaseline.counts[i] == 1) {
                        complete(visits.size() - 2, current.lastSeen, "Server dungeon-completion counter increased on the next area entry");
                        break;
                    }
                }
            }
        }
        completionBaseline = new CompletionBaseline(characterId, current.id, pendingCompletions);
        pendingCompletions = null;
    }

    private static final class CompletionBaseline {
        final int character; final String visit; final int[] counts;
        CompletionBaseline(int character, String visit, int[] counts) { this.character = character; this.visit = visit; this.counts = counts; }
    }
    private void add(long now, String kind, String detail, Map<String, Object> values) {
        Entry entry = new Entry(); entry.id = session + ":event:" + (++nextEntry); entry.time = now; entry.visitId = current == null ? "" : current.id;
        entry.map = current == null ? "Outside an observed visit" : current.map;
        entry.kind = kind; entry.detail = detail; entry.values = copyValues(values);
        entries.add(entry); entriesRevision = ++revision; trim();
    }
    private void trim() {
        while (visits.size() > RUN_LIMIT) { visits.remove(0); summariesRevision = choicesRevision = ++revision; }
        while (entries.size() > EVENT_LIMIT) {
            int discard = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).kind.equals("Resources") || entries.get(i).kind.equals("Ownership check")) { discard = i; break; }
            }
            entries.remove(discard); entriesRevision = ++revision;
        }
    }
    private void limitTimelines() {
        int points=0, slices=0;
        for (int i=0; i<visits.size(); i++) {
            Visit visit=visits.get(i);
            if (visit.resourceTimeline.size()>1000 || visit.conditionTimeline.size()>1000) { visit=writable(i); changed(visit); }
            while(visit.resourceTimeline.size()>1000) { visit.resourceTimeline.remove(0); visit.timelineOmitted++; }
            while(visit.conditionTimeline.size()>1000) { visit.conditionTimeline.remove(0); visit.timelineOmitted++; }
            points+=visit.resourceTimeline.size(); slices+=visit.conditionTimeline.size();
        }
        for (int i=0; i<visits.size(); i++) {
            Visit visit=visits.get(i);
            if ((points>12000 && !visit.resourceTimeline.isEmpty()) || (slices>12000 && !visit.conditionTimeline.isEmpty())) { visit=writable(i); changed(visit); }
            while(points>12000 && !visit.resourceTimeline.isEmpty()) { visit.resourceTimeline.remove(0); visit.timelineOmitted++; points--; }
            while(slices>12000 && !visit.conditionTimeline.isEmpty()) { visit.conditionTimeline.remove(0); visit.timelineOmitted++; slices--; }
        }
    }
    private static Integer min(Integer a, int b) { return a == null ? b : Math.min(a, b); }
    private static Integer max(Integer a, int b) { return a == null ? b : Math.max(a, b); }
    private static void increment(Map<Integer, Long> map, int key) {
        if (map.size() < 256 || map.containsKey(key)) map.merge(key, 1L, Long::sum);
    }
    private static void putBounded(LinkedHashMap<Integer, Integer> map, int id, int value) {
        map.put(id, value); if (map.size() > 20000) map.remove(map.keySet().iterator().next());
    }
    public State snapshot() {
        // Copy all mutable rows before handing them to the UI or background writer.
        fullCopies++;
        State state = new State();
        for (Visit visit : visits) state.visits.add(copyVisit(visit, true));
        for (Entry entry : entries) { state.entries.add(copyEntry(entry)); copiedEntries++; }
        return state;
    }
    public boolean inspectPlayer(InspectSnapshot player) {
        if (current == null || !ParseDungeon.isDungeon(current.map)) return false;
        String key = player.key();
        if (player.sameDisplay(current.inspectedPlayers.get(key))) { inspectedIdentities.put(player.objectId(), key); return false; }
        if (!current.inspectedPlayers.containsKey(key) && !current.inspectedPlayers.containsKey(player.anonymousKey())
                && current.inspectedPlayers.size() >= INSPECT_PLAYER_LIMIT) return false;
        writableCurrent();
        if (!key.equals(player.anonymousKey())) {
            current.inspectedPlayers.remove(player.anonymousKey());
            Long anonymousDamage = current.playerDamage.remove(player.anonymousKey());
            if (anonymousDamage != null) current.playerDamage.merge(key, anonymousDamage, Long::sum);
        }
        current.inspectedPlayers.put(key, player);
        inspectedIdentities.put(player.objectId(), key);
        current.inspectedPlayerCount = current.inspectedPlayers.size();
        changed(current);
        return true;
    }

    public boolean hasInspectedPlayer(int objectId) { return current != null && inspectedIdentities.containsKey(objectId); }

    public boolean inspectDamage(int objectId, long amount, long time) {
        if (current == null || amount <= 0 || time < current.started) return false;
        String key = inspectedIdentities.get(objectId);
        if (key == null || !current.inspectedPlayers.containsKey(key)) return false;
        writableCurrent();
        current.playerDamage.merge(key, amount, Long::sum); current.totalDamage += amount;
        current.firstDamageAt = current.firstDamageAt < 0 ? time : Math.min(current.firstDamageAt, time);
        current.lastDamageAt = Math.max(current.lastDamageAt, time);
        changed(current); return true;
    }

    public enum View { RUNS, TIMELINE, COMBAT, INSPECT }

    /** Opaque comparison token: valid only for this journal, view and selected visit. */
    public static final class ViewRevision {
        private final transient ActivityJournal owner;
        private final View view;
        private final String selected;
        private final long rows, choices;
        private final int eventCount;
        private ViewRevision(ActivityJournal owner, View view, String selected, long rows, long choices, int eventCount) {
            this.owner=owner; this.view=view; this.selected=selected; this.rows=rows; this.choices=choices; this.eventCount=eventCount;
        }
        private boolean same(ViewRevision other) {
            return other!=null && owner==other.owner && view==other.view && selected.equals(other.selected)
                && rows==other.rows && choices==other.choices && eventCount==other.eventCount;
        }
    }
    public static final class VisitChoice {
        public final String id, map;
        public final long started;
        private VisitChoice(Visit visit) { id=visit.id; map=visit.map; started=visit.started; }
    }
    public static final class ViewSnapshot {
        public final ViewRevision revision;
        public final State data;
        public final List<VisitChoice> choices;
        public final String selectedVisit;
        public final int visitCount, eventCount;
        private final transient History history;
        private ViewSnapshot(ViewRevision revision, State data, List<VisitChoice> choices, int visitCount, int eventCount, History history) {
            this.revision=revision; this.data=data; this.choices=Collections.unmodifiableList(choices);
            selectedVisit=revision.selected; this.visitCount=visitCount; this.eventCount=eventCount; this.history=history;
        }
        /** Materialize on a worker. Never returns the mutable payload used by the view. */
        public State fullHistory() { return history.snapshot(); }
        /** A different Combat selection at this same pinned revision; no other charts are copied. */
        public Visit combatVisit(String id) {
            for (Visit visit:history.visits) if (visit.id.equals(id)) return new Visit(visit);
            return null;
        }
    }
    /** Returns null without copying rows or pinning history when the requested view is unchanged. */
    public ViewSnapshot viewSnapshot(View view, String selectedId, ViewRevision known) {
        Objects.requireNonNull(view);
        Visit selected=null;
        if (view==View.COMBAT || view==View.INSPECT) {
            for (Visit visit:visits) if (visit.id.equals(selectedId)) { selected=visit; break; }
            if (selected==null) for (int i=visits.size()-1;i>=0;i--) {
                if (view==View.COMBAT || ParseDungeon.isDungeon(visits.get(i).map)) { selected=visits.get(i); break; }
            }
        }
        String id=selected==null ? "" : selected.id;
        long rows=view==View.RUNS || view==View.INSPECT ? summariesRevision : view==View.TIMELINE ? entriesRevision : selected==null ? 0 : selected.revision;
        ViewRevision next=new ViewRevision(this,view,id,rows,view==View.RUNS ? 0 : choicesRevision,view==View.COMBAT ? entries.size() : 0);
        if (next.same(known)) return null;
        viewCopies++;
        State data=new State(); List<VisitChoice> choices=new ArrayList<>();
        if (view==View.RUNS || view==View.INSPECT) for (Visit visit:visits) {
            Visit copy=copyVisit(visit,false);
            if (view==View.INSPECT && visit==selected) {
                copy.inspectedPlayers.putAll(visit.inspectedPlayers); copy.playerDamage.putAll(visit.playerDamage);
            }
            data.visits.add(copy);
        }
        else for (Visit visit:visits) choices.add(new VisitChoice(visit));
        if (view==View.TIMELINE) for (Entry entry:entries) { data.entries.add(copyEntry(entry)); copiedEntries++; }
        if (selected!=null && view==View.COMBAT) data.visits.add(copyVisit(selected,true));
        // Shallow, private pins preserve displayed-revision exports without copying all chart data.
        // Entries are append-only; the next mutation obtains a private visit/list version.
        for (Visit visit:visits) visit.shared=true;
        pinnedReferences+=visits.size()+entries.size();
        return new ViewSnapshot(next,data,choices,visits.size(),entries.size(),new History(visits,entries));
    }
    private static final class History {
        private final List<Visit> visits;
        private final List<Entry> entries;
        private History(List<Visit> visits,List<Entry> entries) { this.visits=new ArrayList<>(visits); this.entries=new ArrayList<>(entries); }
        private State snapshot() {
            State state=new State();
            for (Visit visit:visits) state.visits.add(new Visit(visit));
            for (Entry entry:entries) state.entries.add(copyEntry(entry));
            return state;
        }
    }
    private void changed(Visit visit) { visit.revision=++revision; summariesRevision=revision; }
    private Visit writable(int index) {
        Visit visit=visits.get(index);
        if (visit.shared) {
            Visit copy=copyVisit(visit,false); copy.revision=visit.revision;
            // Resource points and completed slices are never modified internally. A growing final
            // condition slice is replaced in advanceConditions, so these private lists can share elements.
            copy.resourceTimeline.addAll(visit.resourceTimeline); copy.conditionTimeline.addAll(visit.conditionTimeline);
            copy.inspectedPlayers.putAll(visit.inspectedPlayers);
            copy.playerDamage.putAll(visit.playerDamage);
            copyOnWriteReferences+=visit.resourceTimeline.size()+visit.conditionTimeline.size();
            visits.set(index,copy); if (current==visit) current=copy; visit=copy;
        }
        return visit;
    }
    private void writableCurrent() { if (current!=null && current.shared) writable(visits.size()-1); }
    private Visit copyVisit(Visit visit,boolean charts) {
        copiedVisits++;
        if (charts) { copiedPoints+=visit.resourceTimeline.size(); copiedSlices+=visit.conditionTimeline.size(); }
        return new Visit(visit,charts);
    }
    private static Entry copyEntry(Entry entry) {
        Entry copy=new Entry(); copy.id=entry.id; copy.time=entry.time; copy.visitId=entry.visitId; copy.map=entry.map;
        copy.kind=entry.kind; copy.detail=entry.detail; copy.values=copyValues(entry.values); return copy;
    }
    long revision() { return revision; }
    public SnapshotStats snapshotStats() { return new SnapshotStats(viewCopies,fullCopies,copiedVisits,copiedEntries,copiedPoints,copiedSlices,pinnedReferences,copyOnWriteReferences); }
    /** Observer-owned copy counts, including copy-on-write. Off-lock pinned export copies are excluded. */
    public static final class SnapshotStats {
        public final long views, full, visits, entries, resourcePoints, conditionSlices, pinnedReferences, copyOnWriteReferences;
        private SnapshotStats(long views,long full,long visits,long entries,long points,long slices,long pins,long cowReferences) {
            this.views=views; this.full=full; this.visits=visits; this.entries=entries; resourcePoints=points; conditionSlices=slices; pinnedReferences=pins; copyOnWriteReferences=cowReferences;
        }
    }
    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map) return copyValues((Map<String,Object>)value);
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>(); for (Object item : (List<?>)value) copy.add(copyValue(item)); return copy;
        }
        if (value instanceof int[]) return ((int[])value).clone();
        return value; // Remaining allowlisted values are immutable numbers, booleans and strings.
    }
    static Map<String,Object> copyValues(Map<String,Object> values) {
        Map<String,Object> copy = new LinkedHashMap<>(); values.forEach((key,value) -> copy.put(key,copyValue(value))); return copy;
    }
    public static final class State {
        public int schemaVersion = 1;
        public String captureRunId = "", checkpointTime = "";
        public Map<String,Long> packetCounts = new LinkedHashMap<>(), decodeFailures = new LinkedHashMap<>(), statObservations = new LinkedHashMap<>();
        public List<Visit> visits = new ArrayList<>();
        public List<Entry> entries = new ArrayList<>();
    }
    public static final class Visit {
        public Visit() {}
        Visit(Visit v) { this(v,true); }
        private Visit(Visit v, boolean charts) {
            id=v.id; map=v.map; status=v.status; started=v.started; ended=v.ended; lastSeen=v.lastSeen;
            frames=v.frames; issues=v.issues; ticks=v.ticks; tickMillis=v.tickMillis; timingGaps=v.timingGaps;
            difficulty=v.difficulty; maxTickMillis=v.maxTickMillis; partyId=v.partyId; rosterSize=v.rosterSize;
            realmStart=v.realmStart; realmLatest=v.realmLatest; realmMaximum=v.realmMaximum;
            useRequests=v.useRequests; exaltIncrease=v.exaltIncrease; hpSamples=v.hpSamples; mpSamples=v.mpSamples;
            hpRises=v.hpRises; hpFalls=v.hpFalls; ownerMatches=v.ownerMatches; ownerConflicts=v.ownerConflicts;
            hp=v.hp; mp=v.mp; maxHp=v.maxHp; maxMp=v.maxMp; hpMin=v.hpMin; hpMax=v.hpMax; mpMin=v.mpMin; mpMax=v.mpMax;
            conditionObservedMillis=v.conditionObservedMillis; extraConditionObservedMillis=v.extraConditionObservedMillis;
            conditions.putAll(v.conditions); extraConditions.putAll(v.extraConditions);
            requestedItems.putAll(v.requestedItems); effects.putAll(v.effects); equipment.putAll(v.equipment);
            timelineOmitted=v.timelineOmitted;
            inspectedPlayerCount=v.inspectedPlayerCount;
            completionEvidence=v.completionEvidence; completionObservedAt=v.completionObservedAt; endReason=v.endReason;
            damageTracked=v.damageTracked; totalDamage=v.totalDamage; firstDamageAt=v.firstDamageAt; lastDamageAt=v.lastDamageAt;
            if (charts) {
                inspectedPlayers.putAll(v.inspectedPlayers);
                playerDamage.putAll(v.playerDamage);
                for(ResourcePoint point:v.resourceTimeline) { ResourcePoint p=new ResourcePoint(); p.time=point.time; p.hp=point.hp; p.mp=point.mp; resourceTimeline.add(p); }
                for(ConditionSlice slice:v.conditionTimeline) { ConditionSlice s=new ConditionSlice(); s.start=slice.start; s.end=slice.end; s.primary=slice.primary; s.secondary=slice.secondary; conditionTimeline.add(s); }
            }
        }
        private transient long revision;
        private transient boolean shared;
        public String id, map, status = "In progress; completion unknown";
        public long started, ended, lastSeen, frames, issues, ticks, tickMillis, timingGaps;
        public float difficulty;
        public int maxTickMillis;
        public Integer partyId, rosterSize, realmStart, realmLatest, realmMaximum;
        public long useRequests, exaltIncrease, hpSamples, mpSamples, hpRises, hpFalls, ownerMatches, ownerConflicts;
        public Integer hp, mp, maxHp, maxMp, hpMin, hpMax, mpMin, mpMax;
        public long conditionObservedMillis, extraConditionObservedMillis;
        public Map<String, Long> conditions = new LinkedHashMap<>(), extraConditions = new LinkedHashMap<>();
        public Map<Integer, Long> requestedItems = new LinkedHashMap<>(), effects = new LinkedHashMap<>();
        public Map<Integer, Integer> equipment = new LinkedHashMap<>();
        public long timelineOmitted;
        public int inspectedPlayerCount;
        public Map<String, InspectSnapshot> inspectedPlayers = new LinkedHashMap<>();
        public Map<String, Long> playerDamage = new LinkedHashMap<>();
        public boolean damageTracked;
        public long totalDamage, firstDamageAt = -1, lastDamageAt = -1;
        public String completionEvidence = "", endReason = "";
        public long completionObservedAt;
        public String runStatus() {
            if (!completionEvidence.isEmpty()) return "Completed";
            if ("Completed".equals(status)) return status;
            return ended > 0 ? "Left · completion unconfirmed" : "In progress";
        }
        public Long damage(String key) { return damageTracked ? playerDamage.getOrDefault(key, 0L) : null; }
        public Double dps(Long damage) { return damage == null || lastDamageAt <= firstDamageAt ? null : damage * 1000.0 / (lastDamageAt - firstDamageAt); }
        public long observedMillis() { return Math.max(0, lastSeen - started); }
        public List<ResourcePoint> resourceTimeline = new ArrayList<>();
        public List<ConditionSlice> conditionTimeline = new ArrayList<>();
    }
    public static final class ResourcePoint { public long time; public Integer hp, mp; }
    public static final class ConditionSlice { public long start, end; public Integer primary, secondary; }
    public static final class Entry {
        public long time;
        public String id, visitId, map, kind, detail;
        public Map<String, Object> values;
    }
}
