package tomato.gui.myinfo;

import java.util.Arrays;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.WorkspaceShell;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;

public class MyInfoLayoutEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence();
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void settledPopulatedScenarioLabelAndMetricActionsFitAndRemainReachable() throws Exception {
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        put(player, StatType.ACCOUNT_ID_STAT, 0, "synthetic-myinfo-account");
        put(player, StatType.HP_STAT, 820, ""); put(player, StatType.MAX_HP_STAT, 900, "");
        put(player, StatType.MP_STAT, 310, ""); put(player, StatType.MAX_MP_STAT, 400, "");
        put(player, StatType.WISDOM_STAT, 75, ""); put(player, StatType.UNIQUE_DATA_STRING, 0, "");
        MyInfoGUI[] view = new MyInfoGUI[1];
        WorkspaceShell[] shell = new WorkspaceShell[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new MyInfoGUI(data); MyInfoGUI.updatePlayer(player); MyInfoGuiTest.equipPet(data, 408);
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel()); pages[6] = view[0];
            shell[0] = new WorkspaceShell(pages, () -> fail("Preview must not capture"), true,
                () -> fail("Preview must not choose real assets"), () -> fail("Preview must not retry real assets"), () -> shell[0].select(10));
            shell[0].select(6);
        });
        for (int font : new int[]{13, 24}) for (int width : new int[]{1240, 680}) {
            SwingUtilities.invokeAndWait(() -> evidence.show(shell[0], "Populated My Info", width, width == 680 ? 520 : 800, font));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                String name = "myinfo-" + width + "-" + font; evidence.capture(name);
                JTable table = find(view[0], JTable.class, c -> true);
                assertTrue("Populated build details", table.getRowCount() > 20);
                assertTrue(view[0].metricDetails(3).contains("Magic heal"));
                assertEquals(Double.valueOf(54), detailValue(table, "Estimated mana recovery"));
                AbstractButton scenario = button(view[0], "Estimate scenario: out of combat");
                layouts.checkSucceeds(() -> {
                    completeButton(scenario);
                    assertTrue("Three usable build rows", table.getParent().getHeight() >= table.getRowHeight() * 3);
                    return null;
                });
                evidence.capture(name + "-scenario");
                layouts.checkSucceeds(() -> {
                    for (int metric = 0; metric < 4; metric++) completeButton(named(view[0], "myinfo-metric-" + metric, JButton.class));
                    return null;
                });
                scenario.doClick();
                assertTrue(view[0].metricDetails(3).contains(scenario.isSelected() ? "scenario: out of combat" : "scenario: in combat"));
                assertEquals(75, player.stat.get(StatType.WISDOM_STAT).statValue);
                assertEquals("", player.stat.get(StatType.UNIQUE_DATA_STRING).stringStatValue);
            });
        }
    }

    private static void put(Entity player, StatType type, int value, String text) {
        StatData stat = new StatData(); stat.statValue = value; stat.stringStatValue = text; player.stat.set(type, stat);
    }

    private static Object detailValue(JTable table, String name) {
        for (int row = 0; row < table.getModel().getRowCount(); row++)
            if (name.equals(table.getModel().getValueAt(row, 1))) return table.getModel().getValueAt(row, 2);
        throw new AssertionError("Missing detail: " + name);
    }
}
