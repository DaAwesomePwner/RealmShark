package tomato.gui.security;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import assets.ImageBuffer;
import com.formdev.flatlaf.FlatLightLaf;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import tomato.realmshark.enums.CharacterClass;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ParsePanelRefreshTest {
    private JFrame frame;
    private ParsePanelGUI panel;
    private String oldFilters, oldSelected, oldSort;
    private Map<Integer, CharacterClass> classes;
    private Set<Integer> characterIds;
    private CharacterClass oldClass;
    private boolean hadId;
    private LookAndFeel oldLookAndFeel;
    private Font oldFont;

    @Before @SuppressWarnings("unchecked") public void isolatePreferences() throws Exception {
        oldLookAndFeel = UIManager.getLookAndFeel();
        oldFont = ContentStyle.body();
        oldFilters = PropertiesManager.getProperty("securityFilters");
        oldSelected = PropertiesManager.getProperty("securityFilterName");
        oldSort = PropertiesManager.getProperty("sortCheckBox");
        PropertiesManager.setProperties("securityFilters", "");
        PropertiesManager.setProperties("securityFilterName", "");
        PropertiesManager.setProperties("sortCheckBox", "true");
        // The isolated UI-test working directory intentionally has no downloaded game assets.
        Field classField = CharacterClass.class.getDeclaredField("CHARACTER_CLASS");
        classField.setAccessible(true);
        classes = (Map<Integer, CharacterClass>) classField.get(null);
        Field idsField = CharacterClass.class.getDeclaredField("CHARACTER_IDS");
        idsField.setAccessible(true);
        characterIds = (Set<Integer>) idsField.get(null);
        oldClass = classes.get(782);
        hadId = characterIds.contains(782);
        Constructor<CharacterClass> constructor = CharacterClass.class.getDeclaredConstructor(int.class, String.class,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, int[].class, int[].class);
        constructor.setAccessible(true);
        classes.put(782, constructor.newInstance(782, "Wizard", 670, 385, 75, 25, 50, 75, 40, 60, new int[0], new int[0]));
        characterIds.add(782);
    }

    @After public void cleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI.clear();
            if (frame != null) frame.dispose();
            ContentStyle.setBodyFont(oldFont);
            try { UIManager.setLookAndFeel(oldLookAndFeel); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
        PropertiesManager.setProperties("securityFilters", oldFilters == null ? "" : oldFilters);
        PropertiesManager.setProperties("securityFilterName", oldSelected == null ? "" : oldSelected);
        PropertiesManager.setProperties("sortCheckBox", oldSort == null ? "" : oldSort);
        if (classes != null) { if (oldClass == null) classes.remove(782); else classes.put(782, oldClass); }
        if (characterIds != null && !hadId) characterIds.remove(782);
    }

    @Test public void copyAndExportButtonsWorkThroughKeyboardActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionPanel actions = new ActionPanel();
            pressSpace(button(actions, "Copy names"));
            assertEquals("names", actions.last);
            pressSpace(button(actions, "Copy all (JSON)"));
            assertEquals("json", actions.last);
            pressSpace(button(actions, "Export names…"));
            assertEquals("export-names", actions.last);
            pressSpace(button(actions, "Export JSON…"));
            assertEquals("export-json", actions.last);
            AbstractButton copy = button(actions, "Copy names");
            copy.getAction().actionPerformed(new ActionEvent(copy, ActionEvent.ACTION_PERFORMED, "copy", ActionEvent.SHIFT_MASK));
            assertEquals("export-names", actions.last);
            copy = button(actions, "Copy all (JSON)");
            copy.getAction().actionPerformed(new ActionEvent(copy, ActionEvent.ACTION_PERFORMED, "copy", ActionEvent.SHIFT_MASK));
            assertEquals("export-json", actions.last);
        });
    }

    @Test public void hiddenBurstSnapshotsRefreshOnceOnShowAndDoNotWaitForEdt() throws Exception {
        AtomicInteger events = new AtomicInteger();
        AtomicReference<Throwable> captureFailure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel = new ParsePanelGUI();
            frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(800, 600);
            JTable table = find(panel, JTable.class);
            table.getModel().addTableModelListener(e -> {
                assertTrue("Model mutation must be on EDT", SwingUtilities.isEventDispatchThread());
                events.incrementAndGet();
            });
            Thread capture = new Thread(() -> {
                try {
                    Entity first = player(1, "First", "Zulu");
                    ParsePanelGUI.addPlayer(1, first);
                    for (int i = 1; i <= 1000; i++) {
                        first.stat.get(StatType.LEVEL_STAT).statValue = i;
                        ParsePanelGUI.update(first);
                    }
                    // Mutating the producer's record after publishing must not change the view.
                    first.stat.get(StatType.LEVEL_STAT).statValue = -999;
                    first.stat.get(StatType.NAME_STAT).stringStatValue = "Mutated";
                    first.baseStats[0] = -999;
                    ParsePanelGUI.addPlayer(2, player(2, "Second", "Alpha"));
                    ParsePanelGUI.addPlayer(3, player(3, "Dropped", "Beta"));
                    ParsePanelGUI.removePlayer(3);
                } catch (Throwable failure) { captureFailure.set(failure); }
            }, "test-capture");
            capture.start();
            try { capture.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse("Capture must finish while the EDT is occupied", capture.isAlive());
            assertNull(captureFailure.get());
            assertEquals(0, table.getRowCount());
            assertEquals(0, events.get());
            frame.setVisible(true);
            assertEquals(1, events.get());
            assertEquals(2, table.getRowCount());
            assertEquals("Second [20]", table.getValueAt(0, 0));
            assertEquals("First [1000]", table.getValueAt(1, 0));
            assertEquals("Non-seasonal", table.getValueAt(1, 8));
            frame.setVisible(false);
            ParsePanelGUI.clear();
            ParsePanelGUI.addPlayer(4, player(4, "New map", ""));
            assertEquals(1, events.get());
            frame.setVisible(true);
            assertEquals(2, events.get());
            assertEquals(1, table.getRowCount());
            assertEquals("New map [20]", table.getValueAt(0, 0));
        });
    }

    @Test public void filteredExportsUseLatestSnapshotsEvenBeforeTheRosterIsShown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionPanel actions = new ActionPanel();
            SecurityFilter filter = new SecurityFilter();
            filter.name = "Required points";
            filter.classPoint.put(782, 10);
            filter.exaltSkinPoints = 15;
            actions.getFilters().put(filter.name, filter);
            ParsePanelGUI.currentFilter = filter;
            actions.filterUpdate();
            find(actions, JCheckBox.class).setSelected(true);
            Entity below = player(1, "Below", "");
            Entity meets = player(2, "Meets", "");
            stat(meets, StatType.SKIN_ID, SecurityFilter.exaltedSkinIds[0], "");
            ParsePanelGUI.addPlayer(1, below);
            ParsePanelGUI.addPlayer(2, meets);
            pressSpace(button(actions, "Export JSON…"));
            assertEquals(1, actions.exported.size());
            assertEquals("Below", actions.exported.get(0).playerEntity.name());
            // The disabled below-requirements option must not restrict an unfiltered export.
            find(actions, JComboBox.class).setSelectedItem("Default");
            pressSpace(button(actions, "Export JSON…"));
            assertEquals(2, actions.exported.size());
        });
    }

    @Test public void visibleUpdatesCoalesceAndKeepSelectionAndTextualModes() throws Exception {
        Entity player = player(7, "Selected", "Guild");
        CountDownLatch refreshed = new CountDownLatch(1);
        AtomicInteger events = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            panel = new ParsePanelGUI();
            frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(800, 600);
            ParsePanelGUI.addPlayer(7, player);
            frame.setVisible(true);
            JTable table = find(panel, JTable.class);
            table.setRowSelectionInterval(0, 0);
            table.getModel().addTableModelListener(e -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                events.incrementAndGet();
                refreshed.countDown();
            });
            Thread capture = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    player.stat.get(StatType.LEVEL_STAT).statValue = i;
                    ParsePanelGUI.update(player);
                }
                stat(player, StatType.SEASONAL, 1, "");
                stat(player, StatType.CRUCIBLE_STAT, 0, "active");
                ParsePanelGUI.update(player);
            });
            capture.start();
            try { capture.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(capture.isAlive());
            assertEquals(0, events.get());
        });
        assertTrue("Visible timer must flush the burst", refreshed.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(panel, JTable.class);
            assertEquals(1, events.get());
            assertEquals(0, table.getSelectedRow());
            assertEquals("Selected [499]", table.getValueAt(0, 0));
            assertEquals("Seasonal · Crucible", table.getValueAt(0, 8));
            assertTrue(button(panel, "Copy player").isEnabled());
            assertTrue(button(panel, "Open guild on RealmEye").isEnabled());
        });
    }

    @Test public void abilityMessagesBatchOnEdtWithoutLosingLines() throws Exception {
        AtomicReference<JTextArea> log = new AtomicReference<>();
        AtomicInteger appends = new AtomicInteger();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 300; i++) expected.append("Ability ").append(i).append('\n');
        SwingUtilities.invokeAndWait(() -> {
            SecurityGUI security = new SecurityGUI();
            log.set(find(security, JTextArea.class));
            log.get().getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    appends.incrementAndGet();
                }
                public void removeUpdate(DocumentEvent e) { }
                public void changedUpdate(DocumentEvent e) { }
            });
            Thread capture = new Thread(() -> {
                for (int i = 0; i < 300; i++) SecurityGUI.updateAbilityUsage("Ability " + i);
            });
            capture.start();
            try { capture.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(capture.isAlive());
            assertEquals(0, appends.get());
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(expected.toString(), log.get().getText());
            assertEquals(1, appends.get());
        });
    }

    @Test public void populatedSecurityInMinimumWorkspaceKeepsRowsAndActionsReachable() throws Exception {
        AtomicReference<WorkspaceShell> shell = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, 14));
            setLookAndFeel(new VioletTheme());
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
            pages[2] = new SecurityGUI();
            panel = find(pages[2], ParsePanelGUI.class);
            for (int i = 0; i < 30; i++) ParsePanelGUI.addPlayer(i, player(i, "Player" + i, "Guild"));
            shell.set(new WorkspaceShell(pages, () -> { }, false));
            shell.get().select(2);
            frame = new JFrame();
            frame.setContentPane(shell.get());
            frame.setSize(680, 520);
            frame.setVisible(true);
        });
        settleLayout();
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(panel, JTable.class);
            assertEquals(30, table.getRowCount());
            assertTrue("The populated in-shell viewport must show at least three full rows: " + table.getVisibleRect(),
                    table.getVisibleRect().height >= table.getRowHeight() * 3);
            assertLastRowReachable(table);
            AbstractButton actions = button(panel, "Actions…");
            pressSpace(actions);
            JPopupMenu popup = actions.getComponentPopupMenu();
            assertTrue(popup.isVisible());
            assertNotNull(button(panel, "Export names…"));
            assertNotNull(button(panel, "Export JSON…"));
            assertNotNull(((JMenuItem) button(panel, "Copy player")).getAccelerator());
            assertNotNull(((JMenuItem) button(panel, "Open guild on RealmEye")).getAccelerator());
            popup.setVisible(false);
            ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, 28));
            ContentStyle.refreshFonts(shell.get());
        });
        settleLayout();
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(panel, JTable.class);
            JScrollPane pageScroll = named(panel, "security-page-scroll", JScrollPane.class);
            JScrollPane rosterScroll = named(panel, "security-roster-scroll", JScrollPane.class);
            assertTrue("Large text must activate the page scroll fallback", pageScroll.getVerticalScrollBar().isVisible());
            assertTrue("Roster viewport must retain its minimum rows: viewport=" + rosterScroll.getViewport().getExtentSize()
                    + ", rowHeight=" + table.getRowHeight() + ", page=" + pageScroll.getViewport().getView().getSize()
                    + ", preferred=" + pageScroll.getViewport().getView().getPreferredSize()
                    + ", scroll=" + rosterScroll.getSize(), rosterScroll.getViewport().getExtentSize().height >= table.getRowHeight() * 3);
            Point start = SwingUtilities.convertPoint(rosterScroll.getViewport(), 0, 0, pageScroll.getViewport().getView());
            pageScroll.getVerticalScrollBar().setValue(start.y);
            assertTrue("Rows must be reachable by scrolling the page", table.getVisibleRect().height >= table.getRowHeight());
            assertLastRowReachable(table);
            pageScroll.getVerticalScrollBar().setValue(pageScroll.getVerticalScrollBar().getMaximum());
            assertTrue("Actions remain reachable at large text sizes", button(panel, "Actions…").getVisibleRect().height > 0);
        });
    }

    @Test public void themeChangesRegenerateCachedEquipmentIconsVisibleAndOnShow() throws Exception {
        AtomicReference<Icon> darkIcon = new AtomicReference<>();
        CountDownLatch themed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            setLookAndFeel(new VioletTheme());
            panel = new ParsePanelGUI();
            Entity enchanted = player(1, "Enchanted", "Guild");
            // Header, enchant record type 1026, one enchant ID (little endian).
            stat(enchanted, StatType.UNIQUE_DATA_STRING, 0, Base64.getUrlEncoder().encodeToString(new byte[]{0, 2, 4, 1, 0}));
            ParsePanelGUI.addPlayer(1, enchanted);
            frame = new JFrame();
            frame.setContentPane(panel);
            frame.setSize(800, 600);
            frame.setVisible(true);
            JTable table = find(panel, JTable.class);
            table.setRowSelectionInterval(0, 0);
            darkIcon.set(equipmentIcon(table));
            table.getModel().addTableModelListener(e -> themed.countDown());
            setLookAndFeel(new FlatLightLaf());
            SwingUtilities.updateComponentTreeUI(frame);
        });
        assertTrue("Visible theme changes must invalidate the row cache", themed.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(panel, JTable.class);
            Icon lightIcon = equipmentIcon(table);
            assertNotSame(darkIcon.get(), lightIcon);
            assertSame(ImageBuffer.getOutlinedIconWithGlow(-1, 20, ContentStyle.color("mint"), 3), lightIcon);
            assertEquals(0, table.getSelectedRow());
            frame.setVisible(false);
            setLookAndFeel(new VioletTheme());
            SwingUtilities.updateComponentTreeUI(frame);
            assertSame("Hidden tables wait for refresh-on-show", lightIcon, equipmentIcon(table));
            frame.setVisible(true);
            assertSame(darkIcon.get(), equipmentIcon(table));
        });
    }

    private static Icon equipmentIcon(JTable table) {
        return ((JLabel) table.prepareRenderer(table.getCellRenderer(0, 3), 0, 3)).getIcon();
    }

    private void settleLayout() throws Exception {
        // Include queued shell resize adaptation and width-aware preferred-height passes.
        for (int i = 0; i < 4; i++) SwingUtilities.invokeAndWait(() -> frame.validate());
    }

    private static void assertLastRowReachable(JTable table) {
        int last = table.getRowCount() - 1;
        table.setRowSelectionInterval(last, last);
        Rectangle cell = table.getCellRect(last, 0, true);
        table.scrollRectToVisible(cell);
        assertTrue("The final populated row must remain reachable", table.getVisibleRect().intersects(cell));
    }

    private static void setLookAndFeel(LookAndFeel lookAndFeel) {
        try { UIManager.setLookAndFeel(lookAndFeel); }
        catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) return type.cast(component);
            if (component instanceof Container) { T found = named((Container) component, name, type); if (found != null) return found; }
        }
        return null;
    }

    private static Entity player(int id, String name, String guild) {
        Entity player = new Entity(null, id, 0);
        player.objectType = 782;
        player.baseStats = new int[8];
        stat(player, StatType.NAME_STAT, 0, name);
        stat(player, StatType.GUILD_NAME_STAT, 0, guild);
        stat(player, StatType.LEVEL_STAT, 20, "");
        return player;
    }

    private static void stat(Entity player, StatType type, int number, String text) {
        StatData value = new StatData();
        value.statValue = number; value.stringStatValue = text;
        player.stat.set(type, value);
    }

    private static class ActionPanel extends ParsePanelGUI {
        String last;
        List<Player> exported;
        @Override protected void clicked(boolean full) { last = full ? "json" : "names"; }
        @Override protected void saveNamesAsText() { last = "export-names"; }
        @Override protected void saveAsJson(List<Player> players) { last = "export-json"; exported = players; }
    }

    private static void pressSpace(AbstractButton button) {
        assertNotNull(button);
        if (button instanceof JMenuItem) {
            JMenuItem item = (JMenuItem) button;
            Object binding = item.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(item.getAccelerator());
            assertNotNull("Menu accelerator must have an action", binding);
            item.getActionMap().get(binding).actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, "keyboard"));
            return;
        }
        assertTrue(button.isFocusable());
        for (boolean release : new boolean[]{false, true}) {
            Object binding = button.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, release));
            assertNotNull("Space key must have an action", binding);
            button.getActionMap().get(binding).actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "keyboard"));
        }
    }

    private static AbstractButton button(Container root, String text) {
        if (root instanceof JComponent && ((JComponent) root).getComponentPopupMenu() != null) {
            AbstractButton found = button(((JComponent) root).getComponentPopupMenu(), text);
            if (found != null) return found;
        }
        for (Component component : root.getComponents()) {
            if (component instanceof AbstractButton && text.equals(((AbstractButton) component).getText())) return (AbstractButton) component;
            if (component instanceof Container) { AbstractButton found = button((Container) component, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container) { T found = find((Container) component, type); if (found != null) return found; }
        }
        return null;
    }
}
