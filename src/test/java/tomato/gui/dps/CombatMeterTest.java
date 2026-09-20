package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.gui.modern.VioletTheme;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class CombatMeterTest {
    @Test public void missingLocalSpawnIsDistinguishedFromAnOrdinaryCompleteRecording() {
        packets.incoming.CreateSuccessPacket create = new packets.incoming.CreateSuccessPacket(); create.objectId = 3166;
        packets.outgoing.PlayerShootPacket shot = new packets.outgoing.PlayerShootPacket();
        packets.outgoing.EnemyHitPacket hit = new packets.outgoing.EnemyHitPacket(); hit.shooterID = 3166;
        ArrayList<packets.Packet> packets = new ArrayList<>(Arrays.asList(create, shot, hit));
        DpsData saved = new DpsData(new MapInfoPacket(), new HashMap<>(), new ArrayList<>(), 0, 0, packets);
        assertTrue(MeterDpsGUI.missingLocalSpawn(saved));
        packets.incoming.UpdatePacket update = new packets.incoming.UpdatePacket();
        packets.data.ObjectData player = new packets.data.ObjectData();
        player.status = new packets.data.ObjectStatusData(); player.status.objectId = 3166;
        update.newObjects = new packets.data.ObjectData[]{player}; packets.add(1, update);
        assertFalse(MeterDpsGUI.missingLocalSpawn(saved));
        player.status.objectId = 99; assertTrue(MeterDpsGUI.missingLocalSpawn(saved));
        saved.debugPackets = null; assertFalse(MeterDpsGUI.missingLocalSpawn(saved));
    }
    private Entity player(TomatoData data, int id, String name) {
        return new Entity(data, id, 0) { public String name() { return name; } };
    }
    private Entity enemy(TomatoData data, int id, int hp, Entity owner, int damage) {
        Entity e = new Entity(data, id, 0);
        StatData stat = new StatData(); stat.statValue = hp; e.stat.set(StatType.MAX_HP_STAT, stat);
        e.genericDamageHit(owner, new Projectile(damage), 1000);
        e.updateDamageTaken(1000); e.updateDamageTaken(3000);
        return e;
    }
    @Test public void incomingUsesInclusiveWindowWithoutDoubleCountingOverlaps() throws Exception {
        TomatoData data = new TomatoData(); Entity self = player(data, 1, "Self");
        Field user = Entity.class.getDeclaredField("isUser"); user.setAccessible(true); user.setBoolean(self, true);
        Entity a = enemy(data, 11, 1000, self, 200), b = enemy(data, 12, 9000, self, 400);
        self.getDamageList().add(new Damage(a, 999, 99));
        self.getDamageList().add(new Damage(a, 1000, 10));
        self.getDamageList().add(new Damage(null, 3000, 20));
        self.getDamageList().add(new Damage(a, 3001, 99));
        CombatMeterData meter = new CombatMeterData(Arrays.asList(a, b), self);
        assertEquals(600, meter.total); assertEquals(1, meter.rows.size());
        CombatMeterData.Row row = meter.rows.get(0);
        assertEquals(2, row.hits); assertEquals(400, row.biggest);
        assertEquals(30, row.taken); assertEquals(2, row.incomingHits);
        assertEquals(300.0, meter.dps(row), .001);
    }
    @Test public void zeroDurationDoesNotInventDpsAndTotalsUseLongs() {
        TomatoData data = new TomatoData(); Entity owner = player(data, 1, "Self");
        Entity a = enemy(data, 11, 9000, owner, 2000000000);
        a.updateDamageTaken(1000);
        a.genericDamageHit(owner, new Projectile(2000000000), 1000);
        CombatMeterData meter = new CombatMeterData(Collections.singletonList(a), null);
        assertEquals(4000000000L, meter.total); assertNull(meter.dps(meter.rows.get(0)));
        assertEquals(2, a.getDamageList().size());
    }
    @Test public void remotePlayerIncomingMatchesLegacyDungeonTotalAndKeepsFightScope() {
        TomatoData data = new TomatoData(); Entity remote = player(data, 2, "Remote");
        Entity boss = enemy(data, 11, 900000, remote, 1200);
        remote.getDamageList().add(new Damage(null, 500, 400));
        remote.getDamageList().add(new Damage(null, 2000, 600));
        remote.getDamageList().add(new Damage(null, 4000, 500));
        assertFalse(remote.isUser());
        CombatMeterData all = new CombatMeterData(Collections.singletonList(boss), null, true);
        CombatMeterData.Row total = all.rows.get(0);
        assertTrue(total.incomingAvailable);
        assertEquals(remote.damageTaken(null)[0], total.taken);
        assertEquals(remote.damageTaken(null)[1], total.incomingHits);
        assertEquals(1500, total.taken); assertEquals(3, total.incoming.size());
        CombatMeterData.Row fight = new CombatMeterData(Collections.singletonList(boss), null).rows.get(0);
        assertTrue(fight.incomingAvailable);
        assertEquals(remote.damageTaken(boss)[0], fight.taken);
        assertEquals(600, fight.taken); assertEquals(1, fight.incomingHits);
        assertEquals(1500, fight.totalTaken); assertEquals(3, fight.totalIncomingHits);
    }
    @SuppressWarnings("unchecked")
    private <T> T field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return (T)f.get(obj);
    }
    @Test public void playerFiltersReuseHitAggregateAndMetersRescaleWithoutChangingShares() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                TomatoData data = new TomatoData();
                Entity alice = player(data, 1, "Alice"), bob = player(data, 2, "Bob");
                alice.objectType = 768; bob.objectType = 775;
                Entity target = enemy(data, 11, 1000, alice, 200);
                target.genericDamageHit(bob, new Projectile(800), 2000);
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.renderData(null, Collections.singletonList(target), new ArrayList<>(), 0, false);
                CombatMeterData aggregate = field(meter, "snapshot");
                JTextField search = field(meter, "search");
                JComboBox<String> classes = field(meter, "classes");
                JTable table = field(meter, "table");
                search.setText("Alice");
                assertSame(aggregate, field(meter, "snapshot"));
                assertEquals(200.0, (Double)field(meter, "meterMaximum"), .001);
                assertEquals(20.0, (Double)table.getValueAt(0, 4), .001);
                classes.setSelectedItem(aggregate.rows.get(0).className());
                assertSame(aggregate, field(meter, "snapshot"));
                target.genericDamageHit(alice, new Projectile(100), 2500);
                search.setText("");
                assertEquals("Filtering does not read mutable hit streams", 1000, aggregate.total);
                meter.renderData(null, Collections.singletonList(target), new ArrayList<>(), 0, false);
                CombatMeterData next = field(meter, "snapshot");
                assertNotSame(aggregate, next); assertEquals(1100, next.total);
                assertEquals(1000, aggregate.total);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
    @Test public void meterFiltersSortsDrillsDownAndPreservesSelectionOnRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            try {
                VioletTheme.install(); Filter.disable();
                TomatoData data = new TomatoData();
                Entity alice = player(data, 1, "Alice"), bob = player(data, 2, "Bob");
                alice.objectType = 768; bob.objectType = 775;
                Entity small = enemy(data, 11, 1000, alice, 200), boss = enemy(data, 12, 900000, bob, 1200);
                boss.genericDamageHit(alice, new Projectile(350), 2000);
                MapInfoPacket map = new MapInfoPacket(); map.name = "Meter validation encounter";
                MeterDpsGUI view = new MeterDpsGUI(); view.setContext(map, null);
                view.renderData(map, Arrays.asList(small, boss), new ArrayList<>(), 3000, false);
                JTable table = field(view, "table"); JList<Entity> enemies = field(view, "enemyList");
                JTextField search = field(view, "search"); JComboBox<String> metric = field(view, "metric");
                JTextArea details = field(view, "details");
                assertSame(boss, enemies.getModel().getElementAt(1));
                assertEquals(2, table.getRowCount()); assertEquals("Bob", table.getValueAt(0, 0));
                assertNull(table.getValueAt(0, 8));
                table.setRowSelectionInterval(0, 0); assertTrue(details.getText().contains(tomato.gui.modern.DisplayFormat.formatInteger(1200)));
                search.setText("alice"); assertEquals(1, table.getRowCount());
                assertEquals(550L, table.getValueAt(0, 2));
                // Shares do not inflate when a class/name filter hides other players.
                assertEquals(550.0 / 1750 * 100, (Double)table.getValueAt(0, 4), .001);
                search.setText("missing"); assertEquals(0, table.getRowCount());
                search.setText(""); enemies.setSelectedIndex(1);
                assertEquals(2, table.getRowCount()); assertEquals(1200L, table.getValueAt(0, 2));
                table.setRowSelectionInterval(0, 0);
                table.getRowSorter().toggleSortOrder(2);
                java.util.List<? extends RowSorter.SortKey> sort = new ArrayList<>(table.getRowSorter().getSortKeys());
                view.renderData(map, Arrays.asList(small, boss), new ArrayList<>(), 4000, false);
                assertSame(boss, enemies.getSelectedValue()); assertEquals(sort, table.getRowSorter().getSortKeys());
                assertEquals("Bob", table.getValueAt(table.getSelectedRow(), 0));
                metric.setSelectedIndex(3); assertTrue(details.getText().contains("unavailable"));
                bob.getDamageList().add(new Damage(null, 500, 400));
                bob.getDamageList().add(new Damage(null, 2000, 1100));
                view.renderData(map, Arrays.asList(small, boss), new ArrayList<>(), 4000, false);
                assertEquals(1100L, table.getValueAt(0, 8));
                assertEquals(1L, table.getValueAt(0, 9));
                table.setRowSelectionInterval(0, 0);
                assertTrue(details.getText().contains("Full dungeon taken: " + tomato.gui.modern.DisplayFormat.formatInteger(1500)));
                enemies.setSelectedIndex(0);
                assertEquals(1500L, table.getValueAt(0, 8));
                assertEquals(2L, table.getValueAt(0, 9));
                table.setRowSelectionInterval(0, 0);
                assertTrue(details.getText().contains("full dungeon"));
                metric.setSelectedIndex(0);
                frame.setContentPane(view); frame.setSize(1180, 760); frame.setVisible(true); frame.validate();
                File directory = new File("screenshots"); directory.mkdirs();
                for (int width : new int[]{1180, 680}) {
                    frame.setSize(width, 760); frame.validate();
                    BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics(); view.printAll(g); g.dispose();
                    ImageIO.write(image, "png", new File(directory, "dps-meters-" + width + ".png"));
                }
            } catch (Exception ex) { throw new RuntimeException(ex); }
            finally { frame.dispose(); }
        });
    }
}
