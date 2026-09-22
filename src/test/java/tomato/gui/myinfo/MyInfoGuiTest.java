package tomato.gui.myinfo;
import ui.UiTestLayout;

import org.junit.Test;
import static org.junit.Assert.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.VioletTheme;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.RealmCharacter;

public class MyInfoGuiTest {
    @Test public void hiddenBurstsCoalesceAndCopyBuildStatsBeforeEdtDelivery() throws Exception {
        MyInfoGUI[] panel = new MyInfoGUI[1]; JFrame[] frame = new JFrame[1];
        JPanel[] pages = new JPanel[1];
        TomatoData data = new TomatoData(); data.setUserId(42, 7, "AAAAAA==");
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                VioletTheme.install(); panel[0] = new MyInfoGUI(data);
                pages[0] = new JPanel(new CardLayout()); pages[0].add(panel[0], "build"); pages[0].add(new JPanel(), "other");
                frame[0] = new JFrame("Build refresh validation"); frame[0].setContentPane(pages[0]);
                frame[0].setSize(680, 520); frame[0].setVisible(true);
                ((CardLayout)pages[0].getLayout()).show(pages[0], "other");
                find(panel[0], JTable.class).getModel().addTableModelListener(e -> {
                    assertTrue(SwingUtilities.isEventDispatchThread()); changes.incrementAndGet();
                });
                Thread producer = new Thread(() -> {
                    Entity value = new Entity(data, 42, 0); data.player = value;
                    for (int i = 0; i < 200; i++) { put(value, StatType.HP_STAT, i); MyInfoGUI.updatePlayer(value); }
                    put(value, StatType.HP_STAT, 9999);
                });
                producer.start();
                try { producer.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
                assertFalse("Capture must not wait for the EDT", producer.isAlive());
            });
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Hidden views should not rebuild", 0, changes.get());
                ((CardLayout)pages[0].getLayout()).show(pages[0], "build");
                UiTestLayout.settle(frame[0]);
                assertEquals(1, changes.get());
                find(panel[0], JTextField.class).setText("Character Health");
                JTable table = find(panel[0], JTable.class);
                assertEquals(1, table.getRowCount()); assertEquals(199d, (Double)table.getValueAt(0, 2), 0);
                assertTrue("Short windows retain a reachable data viewport", table.getParent().getHeight() >= 100);
            });
        } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
    }

    private static void put(Entity e, StatType type, int value) {
        StatData stat = new StatData(); stat.statValue = value; e.stat.set(type, stat);
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type); if (found != null) return found; }
        }
        return null;
    }
    @Test public void partialCaptureSortSearchPetAndRendering() throws Exception {
        final MyInfoGUI[] panel = new MyInfoGUI[1];
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        string(player, StatType.ACCOUNT_ID_STAT, "A");
        put(player, StatType.ATTACK_STAT, 100);
        put(player, StatType.DEXTERITY_STAT, 9);
        put(player, StatType.WISDOM_STAT, 75);
        put(player, StatType.MAX_HP_STAT, 900);
        put(player, StatType.HP_STAT, 820);
        put(player, StatType.MAX_MP_STAT, 385);
        put(player, StatType.MP_STAT, 310);
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            panel[0] = new MyInfoGUI(data);
            assertEquals(0, find(panel[0], JTable.class).getRowCount());
            MyInfoGUI.updatePlayer(player);
        });
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(panel[0], JTable.class);
            assertTrue(table.getRowCount() > 20);
            JTextField search = find(panel[0], JTextField.class);
            search.setText("Character");
            table.getRowSorter().toggleSortOrder(2);
            double last = -1;
            for (int i = 0; i < table.getRowCount(); i++) {
                Double value = (Double)table.getValueAt(i, 2);
                if (value != null) { assertTrue(value >= last); last = value; }
            }
            search.setText("[");
            assertEquals(0, table.getRowCount());
            search.setText("Damage Ability damage");
            assertEquals(1, table.getRowCount());
            assertNull(table.getValueAt(0, 2));
            search.setText("");
            equipPet(data, 408);
        });
        SwingUtilities.invokeAndWait(() -> {
            JTextField search = find(panel[0], JTextField.class);
            JTable table = find(panel[0], JTable.class);
            search.setText("Magic heal");
            assertTrue(table.getRowCount() >= 1);
            find(panel[0], JComboBox.class).setSelectedItem("Pet");
            search.setText("Magic heal");
            assertEquals(1, table.getRowCount());
            assertEquals(45d, (Double)table.getValueAt(0, 2), .001);
            search.setText("");
            find(panel[0], JComboBox.class).setSelectedItem("All details");
            JFrame frame = new JFrame("My Info — sample capture");
            frame.setContentPane(panel[0]);
            try {
                for (int width : new int[] {1080, 680}) {
                    frame.setSize(width, 720); frame.setVisible(true); frame.validate();
                    UiTestLayout.settle(frame);
                    BufferedImage image = new BufferedImage(width, 720, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
                    File output = new File("my-info-" + width + ".png");
                    try { ImageIO.write(image, "png", output); } catch (Exception e) { throw new RuntimeException(e); }
                }
            } finally { frame.dispose(); }
            data.clear();
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(0, find(panel[0], JTable.class).getRowCount()));
    }

    @Test public void recoveryRequiresAllFourEnchantSlotsAndPreservesKnownEmptyAndValidEffects() throws Exception {
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); data.player = player;
        string(player, StatType.ACCOUNT_ID_STAT, "A");
        put(player, StatType.WISDOM_STAT, 75); put(player, StatType.MAX_MP_STAT, 400); put(player, StatType.MAX_HP_STAT, 900);
        MyInfoGUI[] view = new MyInfoGUI[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new MyInfoGUI(data));
        equipPet(data, 407); // Known pet without Magic Heal contributes an observed zero.
        assertRecovery(view[0], null, null);

        String effect = enchant(0x5ff), truncated = java.util.Base64.getUrlEncoder().encodeToString(new byte[] {0, 2, 4, 1});
        for (String code : new String[] {null, "!!!", effect, effect + ",", effect + ",,"}) {
            string(player, StatType.UNIQUE_DATA_STRING, code); MyInfoGUI.updatePlayer(player);
            assertRecovery(view[0], null, null);
        }
        for (int slot = 0; slot < 4; slot++) {
            for (String invalid : new String[] {"!!!", "AA==", "AAAE", truncated}) {
                String[] codes = {"", "", "", ""}; codes[slot] = invalid;
                string(player, StatType.UNIQUE_DATA_STRING, String.join(",", codes)); MyInfoGUI.updatePlayer(player);
                assertRecovery(view[0], null, null);
                final String name = new String[] {"Weapon", "Ability", "Armor", "Ring"}[slot];
                SwingUtilities.invokeAndWait(() -> assertTrue(notes(view[0], name).contains("Malformed enchant data")));
            }
        }
        for (String empty : new String[] {"", ",,,", enchant() + ",,,", "AAIE_f_9__3__f8=,,,"}) {
            string(player, StatType.UNIQUE_DATA_STRING, empty); MyInfoGUI.updatePlayer(player);
            assertRecovery(view[0], 0d, 9d);
        }
        for (int slot = 0; slot < 4; slot++) {
            String[] codes = {"", "", "", ""}; codes[slot] = effect;
            string(player, StatType.UNIQUE_DATA_STRING, String.join(",", codes)); MyInfoGUI.updatePlayer(player);
            assertRecovery(view[0], 2d, 11d);
        }
        // A malformed backpack entry does not invalidate the four equipped slots.
        string(player, StatType.UNIQUE_DATA_STRING, effect + ",,,,!!!"); MyInfoGUI.updatePlayer(player);
        assertRecovery(view[0], 2d, 11d);
        SwingUtilities.invokeAndWait(() -> find(view[0], JCheckBox.class).doClick());
        assertRecovery(view[0], 4d, 13d);
        string(player, StatType.UNIQUE_DATA_STRING, effect.replace("=", "") + ",,,"); MyInfoGUI.updatePlayer(player);
        assertRecovery(view[0], 4d, 13d);
        // HP effects use local XML definitions; the isolated test working directory has no game assets.
        try (AutoCloseable definitions = healthRegenDefinitions()) {
            assertEquals(4f, ParseEnchants.getLifeRegenPerSecondFromEnchants(enchant(0x2d), 900, false), .001f);
            assertEquals(4.5f, ParseEnchants.getLifeRegenPerSecondFromEnchants(enchant(0x35), 900, false), .001f);
            // Preserve flat and percentage health effects, and percent-based mana, in every recovery mode.
            string(player, StatType.UNIQUE_DATA_STRING, enchant(0x604) + ",," + enchant(0x2d) + "," + enchant(0x35));
            MyInfoGUI.updatePlayer(player);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(6d, (Double) detail(view[0], "Enchant mana recovery", 2), .001);
                assertEquals(15d, (Double) detail(view[0], "Estimated mana recovery", 2), .001);
                assertEquals(17d, (Double) detail(view[0], "Enchant health recovery", 2), .001);
                find(view[0], JCheckBox.class).doClick();
                assertEquals(3d, (Double) detail(view[0], "Enchant mana recovery", 2), .001);
                assertEquals(8.5d, (Double) detail(view[0], "Enchant health recovery", 2), .001);
            });
        }
    }

    /** Exercise the real XML loader without depending on external assets or leaking definitions to other tests. */
    @SuppressWarnings("unchecked")
    private static AutoCloseable healthRegenDefinitions() throws Exception {
        java.util.List<Runnable> restore = new java.util.ArrayList<>();
        for (String name : new String[] {"ENCHANTS", "ENCHANT_EFFECTS", "ENCHANT_REGEN", "ENCHANT_LOOT_BONUS"}) {
            java.lang.reflect.Field field = ParseEnchants.class.getDeclaredField(name); field.setAccessible(true);
            java.util.Map<Object, Object> definitions = (java.util.Map<Object, Object>) field.get(null);
            java.util.Map<Object, Object> before = new java.util.HashMap<>(definitions);
            restore.add(() -> {
                try { field.set(null, new java.util.HashMap<>(before)); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
        }
        java.nio.file.Path fixture = java.nio.file.Files.createTempFile("myinfo-health-enchants-", ".xml");
        try {
            // Transcribed from enchantments.xml: 0x2d is +4 HP/sec; 0x35 is +0.5% maximum HP/sec.
            String xml = "<Enchantments><Enchantment id='Flat_Life_Regen_1' type='0x2d'><Mutators>"
                + "<ActivateOnEquip amount='4' stat='HP'>FlatRegen</ActivateOnEquip></Mutators></Enchantment>"
                + "<Enchantment id='Percentage_Life_Regen_1' type='0x35'><Mutators>"
                + "<ActivateOnEquip amount='0.005' stat='HP'>PercentageRegen</ActivateOnEquip></Mutators></Enchantment></Enchantments>";
            java.nio.file.Files.write(fixture, xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.lang.reflect.Method load = ParseEnchants.class.getDeclaredMethod("loadEnchants", String.class);
            load.setAccessible(true); load.invoke(null, fixture.toString());
        } catch (Exception e) {
            restore.forEach(Runnable::run);
            throw e;
        } finally { java.nio.file.Files.deleteIfExists(fixture); }
        return () -> restore.forEach(Runnable::run);
    }

    private static void equipPet(TomatoData data, int firstAbility) {
        RealmCharacter character = new RealmCharacter(); character.charId = 7; character.equipment = new int[0];
        character.petAbilitys = new int[] {0, 100, firstAbility, 0, 0, 409, 0, 0, 410};
        data.characterListUpdate(new java.util.ArrayList<>(java.util.Collections.singletonList(character)));
    }

    private static void string(Entity player, StatType type, String value) {
        StatData stat = new StatData(); stat.stringStatValue = value; player.stat.set(type, stat);
    }

    private static String enchant(int... ids) {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(3 + ids.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 0).putShort((short) 1026);
        for (int id : ids) bytes.putShort((short) id);
        return java.util.Base64.getUrlEncoder().encodeToString(bytes.array());
    }

    private static Object detail(MyInfoGUI view, String name, int column) {
        javax.swing.table.TableModel model = find(view, JTable.class).getModel();
        for (int i = 0; i < model.getRowCount(); i++) if (name.equals(model.getValueAt(i, 1))) return model.getValueAt(i, column);
        throw new AssertionError("Missing detail: " + name);
    }

    private static String notes(MyInfoGUI view, String name) { return (String) detail(view, name, 4); }

    private static void assertRecovery(MyInfoGUI view, Double enchant, Double total) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(enchant, detail(view, "Enchant mana recovery", 2));
            assertEquals(total, detail(view, "Estimated mana recovery", 2));
            if (enchant == null) assertNull(detail(view, "Enchant health recovery", 2));
            else assertEquals(0d, (Double) detail(view, "Enchant health recovery", 2), .001);
        });
    }
}
