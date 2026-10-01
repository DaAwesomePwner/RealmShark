package tomato.backend.data;

import packets.Packet;
import packets.data.ObjectData;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import packets.incoming.UpdatePacket;
import packets.outgoing.EnemyHitPacket;
import packets.outgoing.PlayerShootPacket;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

public class DpsData implements Serializable {
    // serialver, build/code-review-20260919/libs/RealmShark-v1.2.3.jar (before adding context).
    private static final long serialVersionUID = 8052266513416820004L;

    public MapInfoPacket map;
    public HashMap<Integer, Entity> hitList;
    public ArrayList<NotificationPacket> deathNotifications;
    public long totalDungeonPcTime;
    public long dungeonStartTime;
    public volatile ArrayList<Packet> debugPackets;
    private transient boolean missingLocalSpawnWithoutPackets;
    private LocalPlayerContext localPlayerContext;
    // Optional in old streams. This identifies a recording, never a dungeon name/time match.
    private String recordingId;
    // Optional entry-frozen identity (Wave 3). Stored as JDK types so older readers can still open
    // new files; a null capture time means a legacy recording with no context of any kind.
    private String visitSessionId, visitId;
    private Integer localPlayerObjectId;
    private Long contextCapturedAt;
    // Optional (outcome tracking): who was present, died or left, and when the dungeon ended. Null in older streams.
    private PresenceTimeline presence;

    public DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog) {
        this(m, entityHitList, deathNotifications, totalDungeonPcTime, timePcFirst, dpsPacketLog,
            inferLocalPlayer(entityHitList));
    }

    /** Call while closing the encounter, before clearing the capture-owned player. */
    public DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog, Entity localPlayer) {
        this(m, entityHitList, deathNotifications, totalDungeonPcTime, timePcFirst, dpsPacketLog,
            localPlayer == null ? inferLocalPlayer(entityHitList) : LocalPlayerContext.capture(localPlayer));
    }

    /** A new recording with the identity frozen at encounter entry; {@code context} may be null. */
    public DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog, Entity localPlayer, EncounterContext context) {
        this(m, entityHitList, deathNotifications, totalDungeonPcTime, timePcFirst, dpsPacketLog, localPlayer);
        setEncounterContext(context);
    }

    private DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog, LocalPlayerContext context) {
        this.map = m;
        this.hitList = entityHitList;
        this.deathNotifications = deathNotifications;
        this.totalDungeonPcTime = totalDungeonPcTime;
        this.dungeonStartTime = timePcFirst;
        debugPackets = dpsPacketLog;
        localPlayerContext = context;
        recordingId = java.util.UUID.randomUUID().toString();
    }

    /** Uses retained packets when available; releasing them preserves the verdict for this in-memory fight. */
    public boolean missingLocalSpawn() {
        ArrayList<Packet> packets = debugPackets;
        return packets == null ? missingLocalSpawnWithoutPackets : missingLocalSpawn(packets);
    }

    /** Producer-only retention: publish the verdict before the volatile packet reference is cleared. */
    void releaseDebugPackets() {
        ArrayList<Packet> packets = debugPackets;
        if (packets == null) return;
        missingLocalSpawnWithoutPackets = missingLocalSpawn(packets);
        debugPackets = null;
    }

    private static boolean missingLocalSpawn(Iterable<Packet> packets) {
        int localId = -1;
        boolean spawned = false, missedShots = false, localHit = false;
        for (Packet packet : packets) {
            if (packet instanceof CreateSuccessPacket) localId = ((CreateSuccessPacket)packet).objectId;
            else if (packet instanceof UpdatePacket && localId >= 0) {
                for (ObjectData object : ((UpdatePacket)packet).newObjects)
                    if (object.status.objectId == localId) spawned = true;
            } else if (packet instanceof PlayerShootPacket && localId >= 0 && !spawned) missedShots = true;
            else if (packet instanceof EnemyHitPacket && localId >= 0 && ((EnemyHitPacket)packet).shooterID == localId) localHit = true;
        }
        return missedShots && localHit;
    }

    public DpsData getSaveFile(boolean saveDebugData) {
        ArrayList<Packet> packets = debugPackets;   // retention may release the log while an export starts
        DpsData copy = new DpsData(map, new HashMap<>(hitList), new ArrayList<>(deathNotifications),
            totalDungeonPcTime, dungeonStartTime,
            saveDebugData && packets != null ? new ArrayList<>(packets) : null, localPlayerContext);
        copy.recordingId = recordingId;
        copy.visitSessionId = visitSessionId; copy.visitId = visitId;
        copy.localPlayerObjectId = localPlayerObjectId; copy.contextCapturedAt = contextCapturedAt;
        copy.presence = presence == null ? null : presence.copy();
        return copy;
    }

    /**
     * Identity frozen when this encounter was entered, or null for legacy recordings. The visit is
     * present only when capture verified it at entry; the local object ID is meaningful only inside
     * this encounter. Returns a fresh detached value.
     */
    public EncounterContext getEncounterContext() {
        if (contextCapturedAt == null) return null;
        VisitRef visit = visitSessionId == null || visitId == null || visitSessionId.isEmpty() || visitId.isEmpty()
            ? null : new VisitRef(visitSessionId, visitId);
        return new EncounterContext(visit, localPlayerObjectId, contextCapturedAt);
    }

    private void setEncounterContext(EncounterContext context) {
        if (context == null) return;
        visitSessionId = context.visit == null ? null : context.visit.sessionId;
        visitId = context.visit == null ? null : context.visit.visitId;
        localPlayerObjectId = context.localPlayerObjectId;
        contextCapturedAt = context.capturedAt;
    }

    public String getRecordingId() { return recordingId; }

    public LocalPlayerContext getLocalPlayerContext() { return localPlayerContext; }

    /** The recording's presence timeline, or null when it was recorded before outcome tracking. */
    public PresenceTimeline getPresence() { return presence; }

    /** Set once by capture while closing the encounter, and by fixtures; the timeline is owned by this recording afterwards. */
    public void setPresence(PresenceTimeline presence) { this.presence = presence; }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        // Old streams have no context field. Never fall back to the current live character.
        if (localPlayerContext == null) localPlayerContext = inferLocalPlayer(hitList);
    }

    private static LocalPlayerContext inferLocalPlayer(HashMap<Integer, Entity> hits) {
        if (hits == null) return null;
        Set<Entity> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity entity : hits.values()) {
            if (entity == null) continue;
            if (entity.isUser()) candidates.add(entity);
            for (Damage hit : entity.getDamageList())
                if (hit != null && hit.owner != null && hit.owner.isUser()) candidates.add(hit.owner);
            // Some older histories only retained the per-player aggregate.
            for (Damage hit : entity.getPlayerDamageList())
                if (hit != null && hit.owner != null && hit.owner.isUser()) candidates.add(hit.owner);
        }
        Entity local = null;
        for (Entity candidate : candidates) {
            if (local != null && (local.id != candidate.id || local.objectType != candidate.objectType
                || !Objects.equals(local.getStatName(), candidate.getStatName())
                || !Objects.equals(local.getStatGuild(), candidate.getStatGuild()))) return null;
            local = candidate;
        }
        return LocalPlayerContext.capture(local);
    }

    /** Only immutable filter values are persisted, not another mutable combat/entity graph. */
    public static final class LocalPlayerContext implements Serializable {
        private static final long serialVersionUID = 1L;
        public final int classType;
        public final String guild;

        private LocalPlayerContext(int classType, String guild) {
            this.classType = classType;
            this.guild = guild;
        }

        public static LocalPlayerContext capture(Entity player) {
            return player == null ? null : new LocalPlayerContext(player.objectType, player.getStatGuild());
        }

        // A recorded local type is usable even when the current asset catalog cannot name it.
        public boolean hasClass() { return classType > 0; }
        public boolean hasGuild() { return guild != null && !guild.isEmpty(); }
    }
}
