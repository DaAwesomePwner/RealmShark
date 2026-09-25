package tomato.gui.activity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.history.SessionStore;

public final class ActivityNativeFixtures {
    private ActivityNativeFixtures() {}
    public static List<String> seed(Path root) throws Exception {
        List<String> sessions = new ArrayList<>();
        for (int session = 0; session < 2; session++) try (SessionStore source = new SessionStore(root,true,"native-activity")) {
            sessions.add(source.currentId());
            for (int index = 1; index <= 75; index++) {
                String id = "shared-visit-" + index;
                ActivityJournal.Visit visit = ActivityArchiveTest.visit(id,index);
                visit.status = index % 15 == 0 ? "Completed" : "Left";
                visit.completionEvidence = index % 15 == 0 ? "Server victory notification" : "";
                Entity player = new Entity(null,42,0);
                StatData name = new StatData(); name.stringStatValue = "Native player " + session; player.stat.set(StatType.NAME_STAT,name);
                StatData level = new StatData(); level.statValue = 20; player.stat.set(StatType.LEVEL_STAT,level);
                InspectSnapshot snapshot = new InspectSnapshot(player,visit.started);
                visit.inspectedPlayers.put(snapshot.key(),snapshot); visit.inspectedPlayerCount = 1;
                source.put("runs",id,visit);
                for (int n = 0; n < 8; n++) {
                    ActivityJournal.Entry event = ActivityArchiveTest.event(id,index * 8 + n);
                    event.id = id + "-event-" + n; event.kind = n == 7 ? "Capture issue" : "Equipment changed";
                    event.detail = "Native source " + session; source.append("timeline",event);
                }
            }
            source.flush();
        }
        return sessions;
    }
}
