package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.backend.data.Projectile;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

import static org.junit.Assert.*;

public class MeterOutcomeTest {
    private static Entity enemy(int id, Entity alice, Entity bob) {
        Entity e = new Entity(null, id, 0);
        StatData hp = new StatData(); hp.statValue = 1000; e.stat.set(StatType.MAX_HP_STAT, hp);
        e.genericDamageHit(alice, new Projectile(200), 1000); e.genericDamageHit(bob, new Projectile(800), 2000);
        e.updateDamageTaken(1000); e.updateDamageTaken(3000);
        return e;
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(obj);
    }
    private static Map<String, Object> outcomesByName(JTable table) {
        Map<String, Object> byName = new HashMap<>();
        for (int i = 0; i < table.getModel().getRowCount(); i++) byName.put(String.valueOf(table.getModel().getValueAt(i, 0)), table.getModel().getValueAt(i, 10));
        return byName;
    }
    private static int viewRow(JTable table, String name) {
        for (int i = 0; i < table.getRowCount(); i++) if (name.equals(table.getValueAt(i, 0))) return i;
        throw new AssertionError(name);
    }

    @Test public void theOutcomeColumnAndSummaryFollowThePresenceTimeline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
                PresenceTimeline presence = new PresenceTimeline();
                presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
                presence.recordLeft(2, 126, 700, 161_000);
                presence.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.setPresence(presence, 1_000);
                meter.renderData(null, Collections.singletonList(enemy(11, alice, bob)), new ArrayList<>(), 0, false);
                JTable table = field(meter, "table"); JLabel line = field(meter, "outcomeLine");
                assertEquals("Outcome", table.getModel().getColumnName(10));
                Map<String, Object> byName = outcomesByName(table);
                assertEquals("Completed", byName.get("Alice").toString());
                assertEquals("Nexused 2:40 · 18% HP", byName.get("Bob").toString());
                assertEquals("2 players · 1 completed · 1 nexused", line.getText());
                assertEquals("dps-outcome-summary", line.getName());

                table.clearSelection();
                int row = viewRow(table, "Bob");
                Component name = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                assertEquals("A player who did not complete is muted", ContentStyle.color("muted"), name.getForeground());
                assertTrue(((JComponent) name).getToolTipText().endsWith("Nexused 2:40 · 18% HP"));
                Component outcome = table.prepareRenderer(table.getCellRenderer(row, 10), row, 10);
                assertTrue(((JComponent) outcome).getToolTipText().contains("did not return before the dungeon ended"));
                int alicesRow = viewRow(table, "Alice");
                Component completed = table.prepareRenderer(table.getCellRenderer(alicesRow, 0), alicesRow, 0);
                assertNotEquals(ContentStyle.color("muted"), completed.getForeground());
                assertEquals("DPS numbers stay for every player", 800L, table.getModel().getValueAt(table.convertRowIndexToModel(row), 2));
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void aLiveEncounterBeforeTheEndIsInProgress() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
                PresenceTimeline presence = new PresenceTimeline();
                presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.setPresence(presence, 1_000);
                meter.renderData(null, Collections.singletonList(enemy(11, alice, bob)), new ArrayList<>(), 0, true);
                JTable table = field(meter, "table"); JLabel line = field(meter, "outcomeLine");
                assertEquals("In progress", outcomesByName(table).get("Bob").toString());
                assertEquals("2 players · outcome pending", line.getText());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void aRecordingWithoutATimelineShowsNameMatchedDeathsOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
                ArrayList<NotificationPacket> notes = new ArrayList<>(List.of(EncounterOutcomesTest.death("Bob", 0x0723)));
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.setPresence(null, -1);
                meter.renderData(null, Collections.singletonList(enemy(11, alice, bob)), notes, 0, false);
                JTable table = field(meter, "table"); JLabel line = field(meter, "outcomeLine");
                Map<String, Object> byName = outcomesByName(table);
                assertEquals("Died", byName.get("Bob").toString());
                assertEquals("Unknown", byName.get("Alice").toString());
                assertEquals("Outcomes unavailable (recorded before this feature)", line.getText());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
