package tomato.gui.dps;

import java.io.ObjectInputStream;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.*;

public class DpsFormattingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void liveAndSavedMeterAndLegacyTextUseExplicitLocalePrecisionAndDpsBytesDoNotChange() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        int filter = Filter.filter, equipment = DpsDisplayOptions.equipmentOption;
        Entity alice = entity(12345, 768, "Alice"), bob = entity(2, 775, "Bob"), zero = entity(3, 768, "Zero");
        zero.setUser(7);
        Entity enemy = entity(45678, 100, "Boss");
        StatData hp = new StatData(); hp.statValue = 1000000; enemy.stat.set(StatType.MAX_HP_STAT, hp);
        enemy.genericDamageHit(alice, new Projectile(105000), 1000);
        enemy.genericDamageHit(bob, new Projectile(895000), 3000);
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(3000);
        Damage guarded = enemy.getPlayerDamageList().stream().filter(hit -> hit.owner == alice).findFirst().get();
        guarded.oryx3GuardDmg = true; guarded.counterHits = 1234; guarded.counterDmg = 12345;
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Formatting encounter";
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(enemy.id, enemy);
        DpsData recording = new DpsData(map, hits, new ArrayList<>(), 2123, 1000, null);
        Path folder = temp.newFolder().toPath();
        try {
            Filter.filter = 0; DpsDisplayOptions.equipmentOption = 0;
            byte[] original = null;
            for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                TimeZone.setDefault(TimeZone.getTimeZone(locale.equals(Locale.US) ? "UTC" : "Europe/Berlin"));
                Path file = DpsExport.write(folder, "Encounter", recording);
                byte[] bytes = Files.readAllBytes(file);
                if (original == null) original = bytes; else assertArrayEquals(original, bytes);
                DpsData saved;
                try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) { saved = (DpsData)input.readObject(); }
                SwingUtilities.invokeAndWait(() -> {
                    boolean german = locale.equals(Locale.GERMANY);
                    String[][] liveCells = null;
                    for (boolean live : new boolean[]{true, false}) {
                        DpsData data = live ? recording : saved;
                        MeterDpsGUI view = new MeterDpsGUI(); view.setContext(data, zero);
                        view.renderData(data.map, new ArrayList<>(data.hitList.values()), data.deathNotifications, data.totalDungeonPcTime, live);
                        JTable table = field(view, "table", JTable.class);
                        table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
                        assertEquals("Alice", table.getValueAt(0, 0));
                        assertEquals(german ? "105.000" : "105,000", cell(table, 0, 2));
                        assertEquals(german ? "52.500,0" : "52,500.0", cell(table, 0, 3));
                        assertEquals(german ? "10,5%" : "10.5%", cell(table, 0, 4));
                        assertEquals("—", cell(table, 0, 8)); assertEquals("0", cell(table, 2, 8));
                        assertEquals(german ? "0,0" : "0.0", cell(table, 2, 3));
                        assertEquals(Long.class, table.getColumnClass(2)); assertEquals(Double.class, table.getColumnClass(3));
                        String[][] rendered = new String[3][8];
                        for (int r = 0; r < 3; r++) for (int c = 2; c < 10; c++) rendered[r][c - 2] = cell(table, r, c);
                        if (live) liveCells = rendered; else assertTrue(Arrays.deepEquals(liveCells, rendered));
                        table.setRowSelectionInterval(0, 0);
                        assertTrue(field(view, "details", JTextArea.class).getText().contains(german ? "105.000" : "105,000"));
                        field(view, "metric", JComboBox.class).setSelectedIndex(1);
                        int aliceRow = table.convertRowIndexToView(0);
                        JLabel bar = (JLabel)table.prepareRenderer(table.getCellRenderer(aliceRow, 0), aliceRow, 0);
                        assertTrue(bar.getToolTipText().contains(german ? "52.500,0" : "52,500.0"));
                        table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
                        assertEquals(0L, table.getValueAt(0, 2)); assertEquals(105000L, table.getValueAt(1, 2));
                    }
                    String text = DpsToString.stringDmgRealtime(map, Collections.singletonList(enemy), new ArrayList<>(), null, 2123);
                    assertTrue(text.contains(german ? "HP: 1.000.000" : "HP: 1,000,000"));
                    assertTrue(text.contains(german ? "10,500%" : "10.500%"));
                    assertTrue(text.contains(german ? "[00:00:02,123]" : "[00:00:02.123]"));
                    String tag = german ? "[Guarded Hits:1.234 Dmg:12.345]" : "[Guarded Hits:1,234 Dmg:12,345]";
                    assertTrue(text.contains(tag));
                    IconDpsGUI icons = new IconDpsGUI(null);
                    java.awt.Font font = field(icons, "mainFont", java.awt.Font.class);
                    try {
                        icons.editFont(new java.awt.Font("Dialog", 0, 12));
                        icons.renderData(map, Collections.singletonList(enemy), new ArrayList<>(), 2123, false);
                        assertTrue(hasLabel(icons, german ? "DMG: 105.000 10,500%" : "DMG: 105,000 10.500%"));
                        assertTrue(hasLabel(icons, tag));
                    } finally { icons.editFont(font); }
                    assertEquals(1234, guarded.counterHits); assertEquals(12345, guarded.counterDmg);
                });
            }
        } finally {
            Filter.filter = filter; DpsDisplayOptions.equipmentOption = equipment;
            Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone);
        }
    }

    @Test public void aMissingDpsIntervalDoesNotTurnIntoAnObservedZeroRate() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        int filter = Filter.filter;
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); Filter.filter = 0;
            SwingUtilities.invokeAndWait(() -> {
                Entity player = entity(1, 768, "Alice"), target = entity(2, 100, "Boss");
                target.genericDamageHit(player, new Projectile(1000), 1000);
                target.updateDamageTaken(1000);
                MeterDpsGUI view = new MeterDpsGUI();
                view.renderData(null, Collections.singletonList(target), new ArrayList<>(), 0, false);
                JTable table = field(view, "table", JTable.class);
                assertEquals("1.000", cell(table, 0, 2)); assertEquals("—", cell(table, 0, 3));
                assertNull(table.getValueAt(0, 3));
            });
        } finally { Filter.filter = filter; Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    private static Entity entity(int id, int type, String name) {
        NamedEntity entity = new NamedEntity(id); entity.objectType = type;
        StatData stat = new StatData(); stat.stringStatValue = name; entity.stat.set(StatType.NAME_STAT, stat);
        return entity;
    }
    private static final class NamedEntity extends Entity {
        NamedEntity(int id) { super(null, id, 0); }
        @Override public String name() { return getStatName(); }
    }
    private static boolean hasLabel(java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel)child).getText())) return true;
            if (child instanceof java.awt.Container && hasLabel((java.awt.Container)child, text)) return true;
        }
        return false;
    }
}
