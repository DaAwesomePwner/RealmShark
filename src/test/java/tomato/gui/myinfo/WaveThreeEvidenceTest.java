package tomato.gui.myinfo;

import java.util.Arrays;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.DpsData;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/** Wave 3 visual evidence for the INFO-2 My Info recorded-DPS footer: linked, unavailable and empty. Synthetic only. */
public class WaveThreeEvidenceTest {
    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);

    private final int previousFilter = tomato.gui.dps.Filter.filter;

    @After public void restore() throws Exception { run(() -> { Navigator.install(Navigator.NONE); tomato.gui.dps.Filter.filter = previousFilter; }); }

    private static void put(Entity player, StatType type, int value, String text) {
        StatData stat = new StatData(); stat.statValue = value; stat.stringStatValue = text; player.stat.set(type, stat);
    }

    @Test public void myInfoRecordedDpsLinkedUnavailableAndEmpty() throws Exception {
        tomato.gui.dps.Filter.disable();
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        put(player, StatType.ACCOUNT_ID_STAT, 0, "synthetic-myinfo-account");
        put(player, StatType.HP_STAT, 820, ""); put(player, StatType.MAX_HP_STAT, 900, "");
        put(player, StatType.MP_STAT, 310, ""); put(player, StatType.MAX_MP_STAT, 400, "");
        put(player, StatType.WISDOM_STAT, 75, ""); put(player, StatType.UNIQUE_DATA_STRING, 0, "");
        TomatoData library = new TomatoData();
        DpsData linked = RecordedDpsHandoffTest.encounter(library, "Lost Halls", new EncounterContext(new VisitRef(SESSION, "journal:1"), 7, 1));
        DpsData unverified = RecordedDpsHandoffTest.encounter(library, "Lost Halls", new EncounterContext(new VisitRef(SESSION, "journal:2"), null, 2));
        DpsData legacy = RecordedDpsHandoffTest.encounter(library, "Lost Halls", null);
        library.dpsData.addAll(Arrays.asList(linked, unverified, legacy));
        MyInfoGUI[] view = new MyInfoGUI[1];
        WorkspaceShell shell = edt(() -> {
            DpsGUI dps = new DpsGUI(library, DiscoveryLog.historyView(new ActivityJournal.State()));
            view[0] = new MyInfoGUI(data); MyInfoGUI.updatePlayer(player); MyInfoGuiTest.equipPet(data, 408);
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
            pages[6] = view[0]; pages[7] = dps;
            WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Preview must not capture"), true);
            ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
            navigator.register(dps.encounterRouteTarget());
            created.select(6); return created;
        });
        try {
            run(() -> select(panel(view[0]), linked.getRecordingId()));
            footer(shell, view[0], "myinfo-recorded-dps-linked", () -> {
                RecordedDpsPanel panel = panel(view[0]);
                assertTrue(panel.openButton().isEnabled());
                assertTrue(panel.explanationText(), panel.explanationText().contains("not a recording") && panel.explanationText().contains("Historical recording: Lost Halls"));
                // The verified local row's recorded value and its window from the saved encounter (Self: 25 damage over 1 s).
                assertTrue(panel.explanationText(), panel.explanationText().contains("Recorded (verified local row, object #7): 25.0 DPS")
                    && panel.explanationText().contains("25 damage over 1.0 s first-to-last hit window"));
            });
            run(() -> select(panel(view[0]), unverified.getRecordingId()));
            footer(shell, view[0], "myinfo-recorded-dps-unverified", () -> {
                assertFalse(panel(view[0]).openButton().isEnabled());
                assertTrue(panel(view[0]).explanationText().contains("not verified"));
                assertTrue(panel(view[0]).explanationText().contains("not attributable to you"));
                assertFalse(panel(view[0]).explanationText().contains("Recorded (verified local row"));
            });
            run(() -> select(panel(view[0]), legacy.getRecordingId()));
            footer(shell, view[0], "myinfo-recorded-dps-legacy", () -> {
                assertFalse(panel(view[0]).openButton().isEnabled());
                assertTrue(panel(view[0]).explanationText().contains("Legacy recording"));
                assertTrue(panel(view[0]).explanationText().contains("not attributable to you"));
            });
            // An empty library (a fresh DPS Logger) says so rather than showing another recording.
            run(() -> { new DpsGUI(new TomatoData(), DiscoveryLog.historyView(new ActivityJournal.State())); panel(view[0]).reload(); });
            footer(shell, view[0], "myinfo-recorded-dps-empty", () -> {
                assertEquals(0, panel(view[0]).choice().getItemCount());
                assertTrue(panel(view[0]).explanationText().contains("No recorded encounters"));
            });
        } finally { run(evidence::closeWindow); }
    }

    /** Shows the whole My Info page, then scrolls the footer into its viewport for the capture. */
    private void footer(WorkspaceShell shell, MyInfoGUI view, String name, Check assertions) throws Exception {
        for (boolean compact : new boolean[]{false, true}) frame(evidence, shell, name, compact, () -> {
            RecordedDpsPanel panel = panel(view);
            VisualEvidence.reachable(panel);
            VisualEvidence.completeText(named(panel, "myinfo-recorded-explanation", JTextArea.class));
            assertions.run();
        });
    }

    private static RecordedDpsPanel panel(MyInfoGUI view) { return named(view, "myinfo-recorded-dps", RecordedDpsPanel.class); }

    private static void select(RecordedDpsPanel panel, String recording) {
        for (int i = 0; i < panel.choice().getItemCount(); i++) {
            RecordedEncounter item = panel.choice().getItemAt(i);
            if (recording.equals(item.recordingId)) { panel.choice().setSelectedIndex(i); return; }
        }
        fail("Missing recording " + recording);
    }
}
