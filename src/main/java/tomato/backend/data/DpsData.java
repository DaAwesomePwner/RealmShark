package tomato.backend.data;

import packets.Packet;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;

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
    public ArrayList<Packet> debugPackets;
    private LocalPlayerContext localPlayerContext;

    public DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog) {
        this(m, entityHitList, deathNotifications, totalDungeonPcTime, timePcFirst, dpsPacketLog,
            inferLocalPlayer(entityHitList));
    }

    /** Call while closing the encounter, before clearing the capture-owned player. */
    public DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog, Entity localPlayer) {
        this(m, entityHitList, deathNotifications, totalDungeonPcTime, timePcFirst, dpsPacketLog,
            localPlayer == null ? inferLocalPlayer(entityHitList) : LocalPlayerContext.capture(localPlayer));
    }

    private DpsData(MapInfoPacket m, HashMap<Integer, Entity> entityHitList, ArrayList<NotificationPacket> deathNotifications, long totalDungeonPcTime, long timePcFirst, ArrayList<Packet> dpsPacketLog, LocalPlayerContext context) {
        this.map = m;
        this.hitList = entityHitList;
        this.deathNotifications = deathNotifications;
        this.totalDungeonPcTime = totalDungeonPcTime;
        this.dungeonStartTime = timePcFirst;
        debugPackets = dpsPacketLog;
        localPlayerContext = context;
    }

    public DpsData getSaveFile(boolean saveDebugData) {
        return new DpsData(map, new HashMap<>(hitList), new ArrayList<>(deathNotifications),
            totalDungeonPcTime, dungeonStartTime,
            saveDebugData && debugPackets != null ? new ArrayList<>(debugPackets) : null, localPlayerContext);
    }

    public LocalPlayerContext getLocalPlayerContext() { return localPlayerContext; }

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
