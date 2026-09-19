package tomato.backend.data;

import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import java.util.*;

/** Capture-thread-owned copy published to Swing; no live combat collections cross the boundary. */
public final class DpsSnapshot {
    public final MapInfoPacket map;
    public final Entity[] targets;
    public final Entity player;
    public final ArrayList<NotificationPacket> notifications;
    public final long elapsed;

    private DpsSnapshot(TomatoData data) {
        // Decoded map/notification packets are never subsequently mutated by capture.
        map=data.map; elapsed=data.dungeonTime();
        notifications=new ArrayList<>(data.getDeathNotifications());
        IdentityHashMap<Entity,Entity> copies=new IdentityHashMap<>();
        targets=data.getEntityHitList();
        for(int i=0;i<targets.length;i++) targets[i]=targets[i].copyForDisplay(copies);
        player=data.player==null?null:data.player.copyForDisplay(copies);
    }

    /** Call on the packet producer, or before capture has started. */
    public static DpsSnapshot capture(TomatoData data) { return new DpsSnapshot(data); }
}
