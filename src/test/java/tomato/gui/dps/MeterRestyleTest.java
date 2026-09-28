package tomato.gui.dps;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import ui.UiTestLayout;
import static org.junit.Assert.*;

/**
 * P5b Live meter restyle: the enemy list refills in one pass, the true metric rank, your row, enemy cards with a Boss
 * chip, the hit-details drawer with an always-visible Explore footer, and class sprites. Synthetic names only.
 */
public class MeterRestyleTest {
    private int previousFilter;
    private Locale previousLocale;
    private DisplayModeModel.Mode previousMode;

    @Before public void isolate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFilter = Filter.filter; previousLocale = Locale.getDefault(Locale.Category.FORMAT);
            previousMode = DisplayModeModel.application().mode();
            Filter.disable(); Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
        });
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Filter.filter = previousFilter; Locale.setDefault(Locale.Category.FORMAT, previousLocale);
            DisplayModeModel.application().set(previousMode);
        });
    }

    /** S8 (R4 H1): one refill adds the 301 cards in one model event, so the list measures each card about once. */
    @Test public void refillingThreeHundredEnemiesRendersEachCardAboutOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            Entity alpha = player(data, 1, "Alpha", 768);
            List<Entity> targets = new ArrayList<>();
            for (int i = 0; i < 300; i++) targets.add(enemy(data, 100 + i, 1000 + i, alpha, 10 + i));
            MapInfoPacket map = map("Synthetic realm");
            MeterDpsGUI meter = new MeterDpsGUI(); meter.setContext(map, alpha);
            meter.renderData(map, targets, new ArrayList<>(), 0, true);
            JList<Entity> list = enemyList(meter);
            ListCellRenderer<? super Entity> real = list.getCellRenderer();
            int[] calls = {0};
            list.setCellRenderer((l, value, index, selected, focus) -> { calls[0]++; return real.getListCellRendererComponent(l, value, index, selected, focus); });
            list.getPreferredSize(); calls[0] = 0; // the renderer swap re-measures once; count only the refill
            meter.renderData(map, targets, new ArrayList<>(), 0, true);
            list.getPreferredSize();
            assertEquals(301, list.getModel().getSize());
            System.out.println("MeterRestyleTest: one refill of 301 enemy cards made " + calls[0] + " renderer calls");
            assertTrue("One refill of 301 cards made " + calls[0] + " renderer calls", calls[0] <= 2 * 301);
        });
    }

    /** The renderers' cached formats print exactly what DisplayFormat and KitFormat print, and follow the FORMAT locale. */
    @Test public void cachedRendererFormatsMatchDisplayFormatInEveryLocale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MeterDpsGUI.Formats formats = new MeterDpsGUI.Formats();
            double[] values = {0, 0.04, 0.05, 1, 25.55, 999.94, 999.95, 1234.5, 41_249, 999_949, 999_950, 2_345_678, 4_000_000_000.0, -1234.5, Double.NaN};
            for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY, Locale.FRANCE)) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                for (double value : values) {
                    assertEquals(locale + " " + value, tomato.gui.kit.KitFormat.compact(value), formats.compact(value));
                    assertEquals(locale + " " + value, tomato.gui.modern.DisplayFormat.formatRate(value, 1), formats.decimal(value));
                    assertEquals(locale + " " + value, tomato.gui.modern.DisplayFormat.formatPercentage(value, 1), formats.percentage(value));
                    if (Double.isFinite(value))
                        assertEquals(locale + " " + value, tomato.gui.modern.DisplayFormat.formatInteger((long) value), formats.integer((long) value));
                }
            }
        });
    }

    @Test public void rankIsTheTrueMetricRankUnderHeaderSortsAndFilters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            Entity alpha = player(data, 1, "Alpha", 768), bravo = player(data, 2, "Bravo", 775), charlie = player(data, 3, "Charlie", 782);
            Entity boss = enemy(data, 11, 900000, alpha, 300);
            boss.genericDamageHit(bravo, new Projectile(500), 1500);
            boss.genericDamageHit(charlie, new Projectile(60), 1600); boss.genericDamageHit(charlie, new Projectile(40), 1700);
            MeterDpsGUI meter = rendered(boss);
            JTable table = field(meter, "table", JTable.class);
            assertEquals("Bravo", table.getValueAt(0, 0));
            assertEquals("#1", rank(table, "Bravo")); assertEquals("#2", rank(table, "Alpha")); assertEquals("#3", rank(table, "Charlie"));
            JLabel bar = bar(table, row(table, "Alpha"));
            assertEquals("The rank is a painted prefix, not part of the name", "Alpha", bar.getText());
            assertNotNull("Rank prefix", bar.getIcon());
            int width = bar.getIcon().getIconWidth();
            assertTrue(width > 0);
            for (int r = 0; r < table.getRowCount(); r++) assertEquals("Fixed-width rank prefix", width, bar(table, r).getIcon().getIconWidth());

            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            assertEquals("Alpha", table.getValueAt(0, 0));
            assertEquals("A header re-sort does not renumber", "#2", rank(table, "Alpha"));
            JTextField search = field(meter, "search", JTextField.class);
            search.setText("charlie");
            assertEquals(1, table.getRowCount());
            assertEquals("A name filter does not renumber", "#3", rank(table, "Charlie"));
            search.setText("");

            JComboBox<?> metric = field(meter, "metric", JComboBox.class);
            metric.setSelectedIndex(2); // Hits dealt: Charlie 2, Alpha 1, Bravo 1 (ties by object ID)
            assertEquals("#1", rank(table, "Charlie")); assertEquals("#2", rank(table, "Alpha")); assertEquals("#3", rank(table, "Bravo"));
            metric.setSelectedIndex(3); // Damage taken: no incoming events were recorded, so nobody has a rank
            assertEquals("Unknown taken is unranked, never a number", "—", rank(table, "Bravo"));
        });
    }

    /** The painted cell: nothing over the left padding, the rank in its prefix and the name after it (not on top of it). */
    @Test public void playerCellPaintsTheRankPrefixThenTheName() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MeterDpsGUI meter = rendered(threePlayerBoss());
            JTable table = field(meter, "table", JTable.class);
            JLabel cell = (JLabel) cell(table, 0, 0, false);
            int width = 360, height = table.getRowHeight();
            cell.setSize(width, height); cell.doLayout();
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try { cell.paint(g); } finally { g.dispose(); }
            int background = cell.getBackground().getRGB(), fill = image.getRGB(cell.getInsets().left - 1, height / 2);
            for (int y = 0; y < height; y++) for (int x = 0; x < 4; x++)
                assertEquals("Only background left of the bar at " + x + "," + y, background, image.getRGB(x, y));
            int prefix = cell.getInsets().left, name = prefix + cell.getIcon().getIconWidth() + cell.getIconTextGap();
            assertTrue("The rank prefix is painted", inked(image, prefix, name - cell.getIconTextGap(), fill, background));
            assertTrue("The name is painted after the prefix", inked(image, name, name + 24, fill, background));
        });
    }

    private static boolean inked(java.awt.image.BufferedImage image, int from, int to, int fill, int background) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = from; x < to; x++) {
            int rgb = image.getRGB(x, y);
            if (rgb != fill && rgb != background) return true;
        }
        return false;
    }

    @Test public void yourRowIsWashedAndSaysYouInEveryRenderer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            Entity alpha = player(data, 1, "Alpha", 768), bravo = player(data, 2, "Bravo", 775);
            alpha.setUser(7);
            Entity boss = enemy(data, 11, 900000, alpha, 300);
            boss.genericDamageHit(bravo, new Projectile(500), 1500);
            MeterDpsGUI meter = rendered(boss);
            JTable table = field(meter, "table", JTable.class);
            int mine = row(table, "Alpha"), other = row(table, "Bravo");
            assertEquals("Alpha (you)", bar(table, mine).getText());
            assertEquals("Bravo", bar(table, other).getText());
            Color wash = Tokens.color(Tokens.Role.ACCENT_WASH);
            for (int column : new int[]{0, 1, 2, 3, 5, 9}) {
                assertEquals("Your row, column " + column, wash, cell(table, mine, column, false).getBackground());
                assertNotEquals("Other row, column " + column, wash, cell(table, other, column, false).getBackground());
                assertEquals("Selection wins, column " + column, table.getSelectionBackground(), cell(table, mine, column, true).getBackground());
            }
        });
    }

    @Test public void enemyCardsShowBossChipHpDamageWindowAndIdOnlyInAnalyst() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            Entity alpha = player(data, 1, "Alpha", 768);
            Entity boss = new Entity(data, 12, 0) {
                @Override public boolean isBossMob() { return true; }
                @Override public String name() { return "Synthetic boss"; }
            };
            boss.genericDamageHit(alpha, new Projectile(41200), 1000);
            boss.updateDamageTaken(1000); boss.updateDamageTaken(13300);
            Entity minion = enemy(data, 11, 900000, alpha, 1200);
            MeterDpsGUI meter = new MeterDpsGUI();
            meter.renderData(map("Synthetic dungeon"), Arrays.asList(boss, minion), new ArrayList<>(), 0, true);
            JList<Entity> list = enemyList(meter);
            assertSame("Highest enemy HP first", minion, list.getModel().getElementAt(1));
            assertEquals("All enemies · 2", slot(card(list, 0), BorderLayout.NORTH).getText());
            assertEquals("Encounter totals", slot(card(list, 0), BorderLayout.SOUTH).getText());

            JPanel minionCard = card(list, 1);
            assertEquals("900,000 HP · 1.2k dmg · 2.0 s", slot(minionCard, BorderLayout.SOUTH).getText());
            Component chip = layout(minionCard).getLayoutComponent(BorderLayout.EAST);
            assertTrue("Boss chip slot", chip instanceof Chip);
            assertFalse("No chip on an ordinary enemy", chip.isVisible());

            JPanel bossCard = card(list, 2);
            assertEquals("Synthetic boss", slot(bossCard, BorderLayout.NORTH).getText());
            Chip badge = (Chip) layout(bossCard).getLayoutComponent(BorderLayout.EAST);
            assertTrue(badge.isVisible()); assertEquals("Boss", badge.getText()); assertEquals(Tokens.Tone.WARN, badge.tone());
            assertEquals("Unknown max HP is \"HP —\", never 0", "HP — · 41.2k dmg · 12.3 s", slot(bossCard, BorderLayout.SOUTH).getText());
            assertTrue("The accessible name says Boss in words", bossCard.getAccessibleContext().getAccessibleName().contains("Boss"));

            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertEquals("HP — · 41.2k dmg · 12.3 s · #12", slot(card(list, 2), BorderLayout.SOUTH).getText());
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertFalse("No object ID in Simple", slot(card(list, 2), BorderLayout.SOUTH).getText().contains("#"));

            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("Numbers follow the FORMAT locale", "900.000 HP · 1,2k dmg · 2,0 s", slot(card(list, 1), BorderLayout.SOUTH).getText());
        });
    }

    @Test public void detailsDrawerOpensOnSelectionAndEscapeOrCloseClearsIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MeterDpsGUI meter = rendered(threePlayerBoss());
            JTable table = field(meter, "table", JTable.class);
            JComponent drawer = named(meter, "dps-details-drawer", JComponent.class);
            assertNotNull("The hit details sit in a drawer", drawer);
            JSplitPane right = (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, field(meter, "details", JTextArea.class));
            assertSame("The drawer stays inside the right split", drawer, right.getBottomComponent());
            assertEquals(JSplitPane.VERTICAL_SPLIT, right.getOrientation());
            assertFalse("Closed without a selection", drawer.isVisible());
            int closed = meter.usableHeight();

            table.setRowSelectionInterval(0, 0);
            assertTrue("Open on a selection", drawer.isVisible());
            assertTrue(named(meter, "dps-details-title", JLabel.class).getText().startsWith("Details · Bravo · "));
            assertTrue("usableHeight counts the drawer when open", meter.usableHeight() > closed);

            assertTrue("Escape is bound while the drawer is open", escape(table));
            assertEquals("Escape clears the selection", -1, table.getSelectedRow());
            assertFalse("…and closes the drawer", drawer.isVisible());
            assertEquals("usableHeight counts the drawer only when open", closed, meter.usableHeight());

            assertFalse("A closed drawer lets Escape pass on", escape(table));

            table.setRowSelectionInterval(1, 1);
            assertTrue(drawer.isVisible());
            named(meter, "dps-details-close", AbstractButton.class).doClick();
            assertEquals("× clears the selection", -1, table.getSelectedRow());
            assertFalse(drawer.isVisible());
        });
    }

    @Test public void footerKeepsTheExploreReasonVisibleAndTheOpenDrawerLeavesThreeTableRows() throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                MeterDpsGUI meter = rendered(threePlayerBoss());
                frame[0] = new JFrame("Meter restyle");
                frame[0].setContentPane(meter); frame[0].setSize(1000, 620); frame[0].setVisible(true);
                UiTestLayout.settle(frame[0]);
                JTable table = meter.table();
                JLabel reason = named(meter, "dps-explore-reason", JLabel.class);
                assertTrue(reason.getText(), reason.isShowing() && reason.getText().contains("Select a player") && reason.getWidth() > 0);
                JScrollPane details = named(meter, "dps-hit-details-scroll", JScrollPane.class);
                assertFalse("No hit details without a selection", details.isShowing());

                table.setRowSelectionInterval(0, 0);
                UiTestLayout.settle(frame[0]);
                JTextArea text = field(meter, "details", JTextArea.class);
                int line = text.getFontMetrics(text.getFont()).getHeight();
                assertTrue("Details open with a line visible: " + details.getViewport().getSize(), details.isShowing() && details.getViewport().getHeight() >= line);
                int rows = meter.tableScroll().getViewport().getHeight() / table.getRowHeight();
                assertTrue("The open drawer leaves " + rows + " table rows", rows >= 3);
                JButton explore = named(meter, "dps-explore-events", JButton.class);
                assertTrue(explore.isShowing() && explore.isEnabled());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }

    @Test public void classColumnShowsTheClassSpriteBesideTheName() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            Entity alpha = player(data, 1, "Alpha", 768);
            MeterDpsGUI meter = rendered(enemy(data, 11, 900000, alpha, 300));
            JTable table = field(meter, "table", JTable.class);
            JLabel classes = (JLabel) cell(table, 0, 1, false);
            assertSame("The sprite names the class beside its hue", Sprites.sprite(768, 16), classes.getIcon());
            assertEquals(table.getValueAt(0, 1), classes.getText());
        });
    }

    private static Entity threePlayerBoss() {
        TomatoData data = new TomatoData();
        Entity alpha = player(data, 1, "Alpha", 768), bravo = player(data, 2, "Bravo", 775), charlie = player(data, 3, "Charlie", 782);
        Entity boss = enemy(data, 11, 900000, alpha, 300);
        boss.genericDamageHit(bravo, new Projectile(500), 1500); boss.genericDamageHit(charlie, new Projectile(100), 1600);
        return boss;
    }

    private static MeterDpsGUI rendered(Entity... targets) {
        MapInfoPacket map = map("Synthetic dungeon");
        MeterDpsGUI meter = new MeterDpsGUI(); meter.setContext(map, null);
        meter.renderData(map, Arrays.asList(targets), new ArrayList<>(), 3000, false);
        return meter;
    }

    private static Entity player(TomatoData data, int id, String name, int type) {
        Entity player = new Entity(data, id, 0) { @Override public String name() { return name; } };
        player.objectType = type;
        return player;
    }

    private static Entity enemy(TomatoData data, int id, int hp, Entity owner, int damage) {
        Entity enemy = new Entity(data, id, 0);
        StatData stat = new StatData(); stat.statValue = hp; enemy.stat.set(StatType.MAX_HP_STAT, stat);
        enemy.genericDamageHit(owner, new Projectile(damage), 1000);
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(3000);
        return enemy;
    }

    private static MapInfoPacket map(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = name; return map; }

    private static int row(JTable table, String name) {
        for (int r = 0; r < table.getRowCount(); r++) if (name.equals(table.getValueAt(r, 0))) return r;
        throw new AssertionError("No row " + name);
    }

    private static JLabel bar(JTable table, int row) { return (JLabel) table.prepareRenderer(table.getCellRenderer(row, 0), row, 0); }

    /** The rank the player cell shows, read from its tooltip ("… · Rank #2"). */
    private static String rank(JTable table, String name) {
        String tip = bar(table, row(table, name)).getToolTipText();
        int at = tip == null ? -1 : tip.indexOf("Rank ");
        assertTrue("Rank in the tooltip: " + tip, at >= 0);
        String rest = tip.substring(at + 5);
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    /**
     * Presses Escape on {@code focused} the way JComponent.processKeyBindings resolves it (its WHEN_FOCUSED map, then
     * the WHEN_ANCESTOR_OF_FOCUSED_COMPONENT maps from itself upwards); a key event would need a showing, focused
     * window. Returns whether an enabled binding handled it.
     */
    private static boolean escape(JComponent focused) {
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        KeyEvent event = new KeyEvent(focused, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        if (press(focused, JComponent.WHEN_FOCUSED, escape, event)) return true;
        for (Container c = focused; c != null && !(c instanceof Window); c = c.getParent())
            if (c instanceof JComponent && press((JComponent) c, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, escape, event)) return true;
        return false;
    }
    private static boolean press(JComponent component, int condition, KeyStroke stroke, KeyEvent event) {
        Object key = component.getInputMap(condition).get(stroke);
        Action action = key == null ? null : component.getActionMap().get(key);
        return action != null && SwingUtilities.notifyAction(action, stroke, event, component, 0);
    }

    private static JComponent cell(JTable table, int row, int column, boolean selected) {
        return (JComponent) table.getCellRenderer(row, column).getTableCellRendererComponent(table, table.getValueAt(row, column), selected, false, row, column);
    }

    private static JPanel card(JList<Entity> list, int index) {
        return (JPanel) list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index, false, false);
    }

    private static BorderLayout layout(JPanel card) { return (BorderLayout) card.getLayout(); }
    private static JLabel slot(JPanel card, String where) { return (JLabel) layout(card).getLayoutComponent(where); }

    @SuppressWarnings("unchecked") private static JList<Entity> enemyList(MeterDpsGUI meter) { return field(meter, "enemyList", JList.class); }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
