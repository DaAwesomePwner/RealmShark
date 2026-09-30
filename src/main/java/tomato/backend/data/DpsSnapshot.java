package tomato.backend.data;

import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import java.util.*;

/** Capture-thread-owned copy published to Swing; no live combat collections cross the boundary. */
public final class DpsSnapshot {
    public final MapInfoPacket map;
    public final Entity[] targets;
    public final Entity player;
    public final DpsData.LocalPlayerContext localPlayerContext;
    public final ArrayList<NotificationPacket> notifications;
    public final long elapsed;
    /** Entry-frozen identity of this live encounter, or null when it was not entered through a map change. */
    public final tomato.history.link.EncounterContext context;
    /** The live encounter's presence timeline (a detached copy, never null). */
    public final PresenceTimeline presence;
    /** The live encounter's first tick, or -1 before it. */
    public final long startedAt;

    private DpsSnapshot(TomatoData data) {
        // Decoded map/notification packets are never subsequently mutated by capture.
        map=data.map; elapsed=data.dungeonTime();
        notifications=new ArrayList<>(data.getDeathNotifications());
        IdentityHashMap<Entity,Entity> copies=new IdentityHashMap<>();
        targets=data.getEntityHitList();
        for(int i=0;i<targets.length;i++) targets[i]=targets[i].copyForDisplay(copies);
        player=data.player==null?null:data.player.copyForDisplay(copies);
        localPlayerContext=DpsData.LocalPlayerContext.capture(player);
        context=data.currentEncounterContext();
        presence=data.presenceCopy();
        startedAt=data.encounterStartedAt();
    }

    /** Call on the packet producer, or before capture has started. */
    public static DpsSnapshot capture(TomatoData data) { return new DpsSnapshot(data); }
}
