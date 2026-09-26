package tomato.gui.myinfo;

import org.junit.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.*;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

import javax.swing.*;
import java.util.*;

import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;

/** INFO-2: current estimate to a linked encounter's verified local row; unlinked handoffs are explained. Synthetic only. */
public class RecordedDpsHandoffTest {
    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    @After public void uninstall() throws Exception { edt(() -> { Navigator.install(null); return null; }); }

    static DpsData encounter(TomatoData data, String name, EncounterContext context) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name;
        Entity self = new Entity(data, 7, 0) { @Override public String name() { return "Self"; } }; self.objectType = 768;
        Entity other = new Entity(data, 8, 0) { @Override public String name() { return "Ally"; } }; other.objectType = 768;
        Entity enemy = new Entity(data, 50, 0); StatData hp = new StatData(); hp.statValue = 1000; enemy.stat.set(StatType.MAX_HP_STAT, hp);
        enemy.genericDamageHit(self, new tomato.backend.data.Projectile(25), 1000); enemy.genericDamageHit(other, new tomato.backend.data.Projectile(99), 1500);
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(2000);
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(enemy.id, enemy);
        return context == null ? new DpsData(map, hits, new ArrayList<>(), 60_000, 1_700_000_000_000L, null)
            : new DpsData(map, hits, new ArrayList<>(), 60_000, 1_700_000_000_000L, null, self, context);
    }

    @Test public void linkedLocalRowOpensAsHistoricalRecordingWhileUnverifiedRowsExplainWhy() throws Exception {
        tomato.gui.dps.Filter.disable();
        TomatoData data = new TomatoData();
        DpsData linked = encounter(data, "Lost Halls", new EncounterContext(new VisitRef(SESSION, "journal:1"), 7, 1));
        DpsData unverified = encounter(data, "Lost Halls", new EncounterContext(new VisitRef(SESSION, "journal:2"), null, 2));
        DpsData legacy = encounter(data, "Lost Halls", null);
        data.dpsData.addAll(Arrays.asList(linked, unverified, legacy));
        int[] page = {6};
        DpsGUI dps = edt(() -> new DpsGUI(data, DiscoveryLog.historyView(new ActivityJournal.State())));
        ShellNavigator navigator = edt(() -> {
            ShellNavigator created = new ShellNavigator(() -> page[0], value -> page[0] = value, WorkspaceShell::pageOf, 20);
            Navigator.install(created); created.register(dps.encounterRouteTarget()); return created;
        });
        RecordedDpsPanel panel = edt(() -> new RecordedDpsPanel(DpsGUI::recordedEncounters, () -> "1,234 weapon DPS (est.)"));
        edt(() -> {
            assertEquals(3, panel.choice().getItemCount());
            select(panel, linked.getRecordingId());
            String text = panel.explanationText();
            assertTrue(text, text.contains("Current estimate: 1,234 weapon DPS (est.)") && text.contains("not a recording"));
            assertTrue(text, text.contains("Historical recording: Lost Halls · entered") && text.contains("first-to-last recorded hit window"));
            assertTrue(panel.openButton().isEnabled());
            panel.openButton().doClick(); return null;
        });
        assertEquals(7, page[0]);
        edt(() -> {
            assertNotNull("The exact recording is displayed", dps.currentEncounterId());
            JTable meter = named(dps, JTable.class, "dps-player-table");
            assertTrue(meter.getSelectedRow() >= 0);
            assertEquals("Only the verified local row is selected", "Self", meter.getValueAt(meter.getSelectedRow(), meter.convertColumnIndexToView(0)));
            String notice = named(dps, JTextArea.class, "dps-route-notice").getText();
            assertTrue(notice, notice.contains("Historical recorded DPS") && notice.contains("not your current-build estimate") && notice.contains("object #7"));
            assertTrue(navigator.back());
            assertEquals(6, page[0]);

            select(panel, unverified.getRecordingId());
            assertFalse(panel.openButton().isEnabled());
            assertTrue(panel.explanationText().contains("not verified"));
            assertFalse("Another player's row is never substituted", navigator.canOpen(Route.to(Destination.ENCOUNTER).withRecording(unverified.getRecordingId(), 8)));
            select(panel, legacy.getRecordingId());
            assertFalse(panel.openButton().isEnabled());
            assertTrue(panel.explanationText().contains("Legacy recording"));
            return null;
        });
    }
    private static void select(RecordedDpsPanel panel, String recording) {
        for (int i = 0; i < panel.choice().getItemCount(); i++) {
            RecordedEncounter item = panel.choice().getItemAt(i);
            if (recording.equals(item.recordingId)) { panel.choice().setSelectedIndex(i); return; }
        }
        fail("Missing recording " + recording);
    }
}
