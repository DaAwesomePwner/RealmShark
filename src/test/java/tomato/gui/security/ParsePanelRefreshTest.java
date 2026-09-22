package tomato.gui.security;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import assets.IdToAsset;
import assets.ImageBuffer;
import com.formdev.flatlaf.FlatLightLaf;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.ParseEnchants;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
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
    private Map<Integer, String> classNames;
    private String oldClassName;
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
        Field namesField = CharacterClass.class.getDeclaredField("CLASS_NAME");
        namesField.setAccessible(true);
        classNames = (Map<Integer, String>) namesField.get(null);
        oldClassName = classNames.put(782, "Wizard");
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
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (frame != null) {
                for (Window owned : frame.getOwnedWindows()) owned.dispose();
                frame.dispose();
            }
            ContentStyle.setBodyFont(oldFont);
            try { UIManager.setLookAndFeel(oldLookAndFeel); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
        PropertiesManager.setProperties("securityFilters", oldFilters == null ? "" : oldFilters);
        PropertiesManager.setProperties("securityFilterName", oldSelected == null ? "" : oldSelected);
        PropertiesManager.setProperties("sortCheckBox", oldSort == null ? "" : oldSort);
        if (classes != null) { if (oldClass == null) classes.remove(782); else classes.put(782, oldClass); }
        if (classNames != null) { if (oldClassName == null) classNames.remove(782); else classNames.put(782, oldClassName); }
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
                    stat(first, StatType.SEASONAL, 0, "");
                    stat(first, StatType.CRUCIBLE_STAT, 0, "");
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
            assertEquals("Non-seasonal · Not Crucible", table.getValueAt(1, 8));
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

    @Test public void columnHeadersSortMaxedNumericallyAndPreserveSelectedPlayerThroughUpdates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionPanel actions = new ActionPanel(); panel = actions;
            Entity maxed = player(1, "Maxed", "Zulu"), fresh = player(2, "Fresh", "Alpha"), partial = player(3, "Partial", "Beta");
            maxed.baseStats = new int[]{670, 385, 75, 25, 50, 75, 40, 60};
            fresh.baseStats = new int[]{100, 100, 10, 10, 10, 10, 10, 10};
            partial.baseStats = maxed.baseStats.clone(); partial.baseStats[0] = 100;
            ParsePanelGUI.addPlayer(1, maxed); ParsePanelGUI.addPlayer(2, fresh); ParsePanelGUI.addPlayer(3, partial);
            frame = new JFrame(); frame.setContentPane(panel); frame.setSize(1000, 600); frame.setVisible(true);
            JTable table = find(panel, JTable.class);
            java.awt.Rectangle header = table.getTableHeader().getHeaderRect(7);
            java.awt.Point point = new java.awt.Point(header.x + header.width / 2, header.height / 2);
            table.getTableHeader().dispatchEvent(new java.awt.event.MouseEvent(table.getTableHeader(), java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, point.x, point.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals(8, table.getValueAt(0, 7)); assertEquals(7, table.getValueAt(1, 7)); assertEquals(0, table.getValueAt(2, 7));
            table.setRowSelectionInterval(0, 0);
            table.getRowSorter().toggleSortOrder(7);
            assertEquals("Fresh [20]", table.getValueAt(0, 0));
            assertEquals("Maxed [20]", table.getValueAt(table.getSelectedRow(), 0));
            frame.setVisible(false);
            maxed.baseStats[0] = 600; ParsePanelGUI.update(maxed);
            frame.setVisible(true);
            assertEquals(SortOrder.ASCENDING, table.getRowSorter().getSortKeys().get(0).getSortOrder());
            invokeEquipmentShortcut(actions, table);
            assertEquals("Maxed", actions.detailsPlayer); assertTrue(actions.details.contains("HP: 600"));
            for (int column = 0; column < table.getColumnCount(); column++) {
                table.getRowSorter().toggleSortOrder(column);
                assertEquals(column, table.getRowSorter().getSortKeys().get(0).getColumn());
            }
        });
    }

    @Test public void historicalExportsUseSelectedRunAndSwitchingBackRestoresLatestLiveRoster() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionPanel actions = new ActionPanel(); panel = actions;
            Entity old = player(1, "Old player", "Old guild");
            old.baseStats[0] = 500;
            tomato.backend.data.InspectSnapshot saved = new tomato.backend.data.InspectSnapshot(old);
            ParsePanelGUI.addPlayer(1, player(1, "Live player", ""));
            actions.showRun("old-run", java.util.Collections.singletonList(saved));
            old.baseStats[0] = 10;
            pressSpace(button(actions, "Export JSON…"));
            assertEquals("Old player", actions.exported.get(0).playerEntity.name());
            assertEquals(500, actions.exported.get(0).playerEntity.baseStats[0]);
            actions.showCurrentArea();
            pressSpace(button(actions, "Export JSON…"));
            assertEquals("Live player", actions.exported.get(0).playerEntity.name());
        });
    }

    @Test public void inspectTabsBrowseSortedRunsWithoutReplacingCurrentArea() throws Exception {
        packets.packetcapture.logger.DiscoveryLog log = new packets.packetcapture.logger.DiscoveryLog(null);
        try {
            packets.incoming.MapInfoPacket map = new packets.incoming.MapInfoPacket(); map.name = "Ice Citadel";
            log.observe(packets.PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0);
            Entity earlier = player(1, "Earlier", "Old guild"); earlier.baseStats[0] = 555;
            stat(earlier, StatType.INVENTORY_0_STAT, 12345, ""); log.inspectPlayer(earlier);
            map.name = "Ocean Trench"; log.observe(packets.PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0);
            log.inspectPlayer(player(1, "Later", "New guild"));
            map.name = "Nexus"; log.observe(packets.PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0);
            SwingUtilities.invokeAndWait(() -> {
                VioletTheme.install();
                SecurityGUI inspect = new SecurityGUI(log);
                panel = find(inspect, ParsePanelGUI.class);
                JTabbedPane tabs = find(inspect, JTabbedPane.class);
                assertEquals("Inspect", WorkspaceShell.TITLES[2]);
                assertEquals("Current Area", tabs.getTitleAt(tabs.getSelectedIndex()));
                ParsePanelGUI.addPlayer(1, player(1, "Here now", "Current guild"));
                frame = new JFrame(); frame.setContentPane(inspect); frame.setSize(1000, 750); frame.setVisible(true);
                JTable roster = find(panel, JTable.class);
                assertEquals("Here now [20]", roster.getValueAt(0, 0));
                assertEquals("Class", roster.getColumnName(2));
                assertEquals("Wizard", roster.getValueAt(0, 2));
                tabs.setSelectedIndex(1);
                JTable runs = named(inspect, "inspect-runs-table", JTable.class);
                tomato.gui.activity.SnapshotTestSupport.await(() -> runs.getRowCount() == 2 && roster.getRowCount() == 1);
                assertEquals("Later [20]", roster.getValueAt(0, 0));
                assertEquals("Observed minutes", runs.getColumnName(6));
                runs.moveColumn(6, 0);
                JComboBox<?> units = named(inspect, "inspect-run-duration-unit", JComboBox.class);
                units.setSelectedItem(tomato.gui.activity.RunDurationUnit.SECONDS);
                assertEquals("Observed seconds", runs.getColumnName(0));
                runs.moveColumn(0, 6);
                runs.getRowSorter().toggleSortOrder(1);
                assertEquals("Ice Citadel", runs.getValueAt(0, 1));
                runs.setRowSelectionInterval(0, 0);
                tomato.gui.activity.SnapshotTestSupport.await(() -> roster.getRowCount() == 1 && "Earlier [20]".equals(roster.getValueAt(0, 0)));
                assertEquals("Wizard", roster.getValueAt(0, 2));
                JLabel stats = (JLabel) roster.prepareRenderer(roster.getCellRenderer(0, 7), 0, 7);
                assertTrue(stats.getToolTipText().contains("HP: 555"));
                assertTrue(String.valueOf(roster.getValueAt(0, 3)).contains("12345"));
                for (int width : new int[]{1000, 680}) {
                    frame.setSize(width, width == 1000 ? 750 : 520); frame.validate();
                    assertTrue("Selected run roster remains visible", roster.getVisibleRect().height >= roster.getRowHeight());
                    BufferedImage image = new BufferedImage(inspect.getWidth(), inspect.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics(); inspect.printAll(graphics); graphics.dispose();
                    try {
                        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("screenshots"));
                        javax.imageio.ImageIO.write(image, "png", new java.io.File("screenshots/inspect-runs-" + width + ".png"));
                    } catch (java.io.IOException e) { throw new AssertionError(e); }
                }
                ParsePanelGUI.addPlayer(2, player(2, "Just arrived", ""));
                tabs.setSelectedIndex(0);
                assertEquals(2, roster.getRowCount());
                assertEquals("Here now [20]", roster.getValueAt(0, 0));
                tabs.setSelectedIndex(1);
                assertEquals("Earlier [20]", roster.getValueAt(0, 0));
                named(inspect, "inspect-runs-search", JTextField.class).setText("No such run");
                assertEquals(0, runs.getRowCount()); assertEquals(0, roster.getRowCount());
            });
        } finally { log.close(); }
    }

    @Test public void crucibleModesAreDistinctAndHistoricalDpsSortsWithTheCorrectBuild() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            ActionPanel actions = new ActionPanel(); panel = actions;
            Entity low = player(1, "Low", ""), high = player(2, "High", "");
            stat(low, StatType.SEASONAL, 0, "");
            stat(low, StatType.CRUCIBLE_STAT, 0, "active");
            stat(high, StatType.CRUCIBLE_STAT, 0, "active"); stat(high, StatType.SEASONAL, 1, "");
            stat(high, StatType.INVENTORY_0_STAT, 12345, "");
            tomato.backend.data.InspectSnapshot lowBuild = new tomato.backend.data.InspectSnapshot(low);
            tomato.backend.data.InspectSnapshot highBuild = new tomato.backend.data.InspectSnapshot(high);
            packets.packetcapture.logger.ActivityJournal.Visit run = new packets.packetcapture.logger.ActivityJournal.Visit();
            run.id = "damage-run"; run.map = "Ice Citadel"; run.damageTracked = true;
            run.firstDamageAt = 1000; run.lastDamageAt = 3000;
            run.inspectedPlayers.put(lowBuild.key(), lowBuild); run.inspectedPlayers.put(highBuild.key(), highBuild);
            run.playerDamage.put(lowBuild.key(), 400L); run.playerDamage.put(highBuild.key(), 2000L);
            actions.showRun(run);
            frame = new JFrame(); frame.setContentPane(actions); frame.setSize(1200, 600); frame.setVisible(true);
            JTable table = find(actions, JTable.class);
            assertEquals("Damage", table.getColumnName(9)); assertEquals("DPS", table.getColumnName(10));
            assertEquals(Long.class, table.getColumnClass(9)); assertEquals(Double.class, table.getColumnClass(10));
            assertEquals("Non-seasonal · Crucible", table.getValueAt(0, 8));
            assertEquals("Seasonal · Crucible", table.getValueAt(1, 8));
            for (LookAndFeel theme : new LookAndFeel[]{new VioletTheme(), new FlatLightLaf()}) {
                setLookAndFeel(theme); SwingUtilities.updateComponentTreeUI(frame);
                frame.setVisible(false); frame.setVisible(true);
                Color nonSeasonal = table.prepareRenderer(table.getCellRenderer(0, 8), 0, 8).getForeground();
                Color seasonal = table.prepareRenderer(table.getCellRenderer(1, 8), 1, 8).getForeground();
                assertEquals(ContentStyle.color("amber"), nonSeasonal);
                assertEquals(ContentStyle.color("violet"), seasonal);
                assertNotEquals(nonSeasonal, seasonal);
                table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, 8, table.getRowHeight() / 2, 0, false));
                Component hovered = table.prepareRenderer(table.getCellRenderer(0, 8), 0, 8);
                assertEquals("Inspect inherits the redesigned row hover", ContentStyle.color("hover"), hovered.getBackground());
                assertEquals("Hover retains the Crucible mode color", nonSeasonal, hovered.getForeground());
                assertEquals(ContentStyle.color("hover"), table.prepareRenderer(table.getCellRenderer(0, 10), 0, 10).getBackground());
                table.setRowSelectionInterval(0, 0);
                assertEquals("Selection still outranks hover", table.getSelectionBackground(),
                        table.prepareRenderer(table.getCellRenderer(0, 10), 0, 10).getBackground());
                table.clearSelection();
                table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_EXITED,
                        System.currentTimeMillis(), 0, -1, -1, 0, false));
            }
            table.getRowSorter().toggleSortOrder(10);
            assertEquals("High [20]", table.getValueAt(0, 0)); assertEquals(1000.0, table.getValueAt(0, 10));
            assertEquals(2000L, table.getValueAt(0, 9));
            table.setRowSelectionInterval(0, 0); invokeEquipmentShortcut(actions, table);
            assertEquals("High", actions.detailsPlayer); assertTrue(actions.details.contains("12345"));
            run.damageTracked = false; actions.showRun(run);
            assertNull(table.getValueAt(0, 9)); assertNull(table.getValueAt(0, 10));
            JLabel missing = (JLabel)table.prepareRenderer(table.getCellRenderer(0, 10), 0, 10);
            assertEquals("—", missing.getText());
            actions.showCurrentArea();
            assertEquals(9, table.getColumnCount());
            assertTrue(table.getRowSorter().getSortKeys().stream().allMatch(key -> key.getColumn() < 9));
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
            assertEquipmentText(table, 0, 3, "Weapon: Not captured", ParseEnchants.ENCHANTS.getOrDefault((short)1, "Unknown") + "(1)");
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
            assertEquipmentText(table, 0, 3, "Weapon: Not captured", ParseEnchants.ENCHANTS.getOrDefault((short)1, "Unknown") + "(1)");
            assertFocusDistinctFromSelection(table, 0, 3);
            frame.setVisible(false);
            setLookAndFeel(new VioletTheme());
            SwingUtilities.updateComponentTreeUI(frame);
            assertSame("Hidden tables wait for refresh-on-show", lightIcon, equipmentIcon(table));
            frame.setVisible(true);
            assertSame(darkIcon.get(), equipmentIcon(table));
            assertEquipmentText(table, 0, 3, "Weapon: Not captured", ParseEnchants.ENCHANTS.getOrDefault((short)1, "Unknown") + "(1)");
        });
    }

    @Test @SuppressWarnings("unchecked") public void equipmentRendererResetsKnownEmptyMissingAndUnrecognizedDescriptions() throws Exception {
        Field assets = IdToAsset.class.getDeclaredField("objectID"); assets.setAccessible(true);
        Map<Integer, IdToAsset> objects = (Map<Integer, IdToAsset>)assets.get(null);
        int itemId = 987654;
        IdToAsset previous = objects.put(itemId, new IdToAsset("", itemId, "Sword of Acclaim", "Sword of Acclaim", "", null, "", "", ""));
        String oldEnchant = ParseEnchants.ENCHANTS.put((short)1, "Test enchant");
        try {
            SwingUtilities.invokeAndWait(() -> {
                setLookAndFeel(new VioletTheme());
                panel = new ParsePanelGUI();
                Entity source = player(1, "Selected", "Guild");
                stat(source, StatType.INVENTORY_0_STAT, itemId, "");
                stat(source, StatType.UNIQUE_DATA_STRING, 0, Base64.getUrlEncoder().encodeToString(new byte[]{0, 2, 4, 1, 0}) + ",,,");
                ParsePanelGUI.addPlayer(1, source);
                frame = new JFrame(); frame.setContentPane(panel); frame.setSize(800, 600); frame.setVisible(true);
                JTable table = find(panel, JTable.class);
                JLabel reused = assertEquipmentText(table, 0, 3, "Weapon: Sword of Acclaim (ID " + itemId + ")", "Test enchant(1)");
                assertFocusDistinctFromSelection(table, 0, 3);

                stat(source, StatType.INVENTORY_0_STAT, -1, "");
                stat(source, StatType.UNIQUE_DATA_STRING, 0, "");
                publishOnShow(source);
                assertSame(reused, assertEquipmentText(table, 0, 3, "Weapon: Empty (ID -1)", "None (captured)"));
                assertFalse(reused.getAccessibleContext().getAccessibleDescription().contains("Test enchant"));

                source.stat.set(StatType.INVENTORY_0_STAT, null);
                source.stat.set(StatType.UNIQUE_DATA_STRING, null);
                publishOnShow(source);
                assertSame(reused, assertEquipmentText(table, 0, 3, "Weapon: Not captured", "Enchant data not captured."));

                stat(source, StatType.INVENTORY_0_STAT, Integer.MAX_VALUE, "");
                stat(source, StatType.UNIQUE_DATA_STRING, 0, "!!!,,,");
                publishOnShow(source);
                assertSame(reused, assertEquipmentText(table, 0, 3, "Weapon: Unrecognized item (ID 2147483647)", "Malformed enchant data"));

                // Reusing the icon renderer for text must clear its name, description, icon and alignment.
                JLabel text = (JLabel)table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1);
                assertSame(reused, text); assertNull(text.getIcon()); assertEquals(SwingConstants.LEFT, text.getHorizontalAlignment());
                assertEquals("Guild: Guild", text.getAccessibleContext().getAccessibleName());
                assertEquals("Guild: Guild", text.getAccessibleContext().getAccessibleDescription());
                assertSame(reused, assertEquipmentText(table, 0, 4, "Ability: Not captured", "None (captured)"));
            });
        } finally {
            if (previous == null) objects.remove(itemId); else objects.put(itemId, previous);
            if (oldEnchant == null) ParseEnchants.ENCHANTS.remove((short)1); else ParseEnchants.ENCHANTS.put((short)1, oldEnchant);
        }
    }

    @Test public void equipmentShortcutUsesDisplayedSnapshotThroughSortingFilteringAndPendingUpdates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ActionPanel actions = new ActionPanel(); panel = actions;
            Entity alpha = player(1, "Alpha", "Zulu"), zulu = player(2, "Zulu", "Alpha");
            stat(alpha, StatType.INVENTORY_0_STAT, Integer.MAX_VALUE, "");
            stat(alpha, StatType.UNIQUE_DATA_STRING, 0, "");
            ParsePanelGUI.addPlayer(1, alpha); ParsePanelGUI.addPlayer(2, zulu);
            frame = new JFrame(); frame.setContentPane(actions); frame.setSize(800, 600); frame.setVisible(true);
            JTable table = find(actions, JTable.class);
            table.setAutoCreateRowSorter(true);
            table.getRowSorter().toggleSortOrder(0);
            table.setRowSelectionInterval(0, 0);
            assertEquals("Alpha [20]", table.getValueAt(0, 0));
            assertTrue("Keep user column reordering available", table.getTableHeader().getReorderingAllowed());
            table.moveColumn(3, 1);
            assertEquipmentText(table, 0, 1, "Weapon: Unrecognized item (ID 2147483647)", "None (captured)");
            invokeEquipmentShortcut(actions, table);
            String shown = actions.details;
            assertEquals("Alpha", actions.detailsPlayer);
            assertTrue(shown.contains("Guild: Zulu"));
            assertTrue(shown.contains("ID 2147483647"));
            for (String slot : new String[]{"Weapon:", "Ability:", "Armor:", "Ring:"}) assertTrue(shown.contains(slot));
            assertEquals("copy-player", table.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
            assertEquals("open-player", table.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
            assertEquals("open-guild", table.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)));

            stat(alpha, StatType.INVENTORY_0_STAT, -1, "");
            alpha.stat.get(StatType.NAME_STAT).stringStatValue = "Renamed";
            ParsePanelGUI.update(alpha);
            SecurityFilter requirements = new SecurityFilter(); requirements.name = "Pending requirements";
            requirements.classPoint.put(782, 10);
            actions.getFilters().put(requirements.name, requirements);
            ParsePanelGUI.currentFilter = requirements; actions.filterUpdate();
            invokeEquipmentShortcut(actions, table);
            assertEquals("Pending capture and filter changes cannot leak into displayed equipment", shown, actions.details);
            alpha.stat.get(StatType.NAME_STAT).stringStatValue = "Never published";
            frame.setVisible(false); frame.setVisible(true);
            invokeEquipmentShortcut(actions, table);
            assertEquals("Renamed", actions.detailsPlayer);
            assertTrue(actions.details.contains("Weapon: Empty (ID -1)"));
            assertFalse(actions.details.contains("2147483647"));
            assertFalse(actions.details.contains("Never published"));
            // Model ordering is by guild, view ordering by name; hide another row as well.
            javax.swing.table.TableRowSorter<?> sorter = (javax.swing.table.TableRowSorter<?>)table.getRowSorter();
            sorter.setRowFilter(RowFilter.regexFilter("Renamed", 0));
            table.setRowSelectionInterval(0, 0);
            invokeEquipmentShortcut(actions, table);
            assertEquals("Renamed", actions.detailsPlayer);
            frame.setVisible(false); ParsePanelGUI.update(); frame.setVisible(true);
            assertEquals(0, table.getSelectedRow());
            invokeEquipmentShortcut(actions, table);
            assertEquals("Renamed", actions.detailsPlayer);
            table.clearSelection();
            assertFalse(button(actions, "Equipment details…").isEnabled());
        });
    }

    @Test public void equipmentPostedKeyEventOpensReadOnlyWrappingSnapshotDialog() throws Exception {
        for (LookAndFeel theme : new LookAndFeel[]{new VioletTheme(), new FlatLightLaf()}) postedKeyEquipmentDialog(theme);
    }

    private void postedKeyEquipmentDialog(LookAndFeel theme) throws Exception {
        AtomicReference<Entity> producer = new AtomicReference<>();
        AtomicReference<JTable> roster = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) frame.dispose();
            ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, 24));
            setLookAndFeel(theme);
            panel = new ParsePanelGUI();
            producer.set(player(1, "PlayerWithALongUnbrokenName_ABCDEFGHIJKLMNOPQRSTUVWXYZ", "Guild"));
            ParsePanelGUI.addPlayer(1, producer.get());
            frame = new JFrame(); frame.setContentPane(panel); frame.setSize(680, 520); frame.setVisible(true);
            JTable table = find(panel, JTable.class); table.setRowSelectionInterval(0, 0);
            roster.set(table);
            AbstractButton actions = button(panel, "Actions…");
            actions.scrollRectToVisible(new Rectangle(0, 0, actions.getWidth(), actions.getHeight()));
            pressSpace(actions);
            assertTrue("Details must be discoverable in the default theme's Actions menu", actions.getComponentPopupMenu().isVisible());
            JMenuItem details = (JMenuItem)button(panel, "Equipment details…");
            assertTrue(details.isEnabled());
            assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), details.getAccelerator());
            actions.getComponentPopupMenu().setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
        });
        JDialog dialog = openEquipmentWithPostedKey(roster.get());
        AtomicReference<String> snapshot = new AtomicReference<>();
        CountDownLatch refreshed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            assertNotNull(dialog); assertTrue(dialog.isVisible());
            JTextArea body = named(dialog, "security-equipment-details", JTextArea.class);
            assertNotNull(body); assertFalse(body.isEditable()); assertTrue(body.isFocusable());
            assertTrue(body.getLineWrap()); assertTrue(body.getWrapStyleWord());
            assertNotNull(body.getAccessibleContext().getAccessibleName());
            assertTrue(body.getText().contains("Weapon: Not captured"));
            assertTrue(body.getText().contains("Ring: Not captured"));
            snapshot.set(body.getText());
            roster.get().getModel().addTableModelListener(e -> refreshed.countDown());
            producer.get().stat.get(StatType.NAME_STAT).stringStatValue = "Published later";
            stat(producer.get(), StatType.INVENTORY_0_STAT, -1, "");
            ParsePanelGUI.update(producer.get());
            producer.get().stat.get(StatType.NAME_STAT).stringStatValue = "Unpublished mutation";
        });
        assertTrue("The live table must advance while the open dialog retains its snapshot", refreshed.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Published later [20]", roster.get().getValueAt(0, 0));
            JTextArea body = named(dialog, "security-equipment-details", JTextArea.class);
            assertEquals("An open detail body is a stable displayed-row snapshot", snapshot.get(), body.getText());
            body.setCaretPosition(body.getDocument().getLength());
            dialog.validate();
            JScrollPane scroll = (JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class, body);
            assertTrue(scroll.getViewport().getExtentSize().width > 0);
            assertTrue(scroll.getViewport().getExtentSize().height >= body.getFontMetrics(body.getFont()).getHeight() * 3);
            AbstractButton close = button(dialog, "Close");
            assertEquals(new Rectangle(0, 0, close.getWidth(), close.getHeight()), close.getVisibleRect());
            pressSpace(close);
            assertFalse(dialog.isDisplayable());
        });
    }

    private JDialog openEquipmentWithPostedKey(JTable table) throws Exception {
        CountDownLatch keyDelivered = new CountDownLatch(1), opened = new CountDownLatch(1);
        AtomicReference<JDialog> result = new AtomicReference<>();
        AtomicReference<String> wrongFocus = new AtomicReference<>();
        KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyEventDispatcher delivery = event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_E && event.isControlDown()) {
                if (event.getComponent() != table || focus.getFocusOwner() != table || focus.getFocusedWindow() != frame)
                    wrongFocus.set("Ctrl+E reached " + event.getComponent() + "; focus=" + focus.getFocusOwner());
                keyDelivered.countDown();
            }
            return false; // Observe KeyboardFocusManager dispatch; never invoke or consume the action here.
        };
        AWTEventListener windows = event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() instanceof JDialog) {
                JDialog dialog = (JDialog)event.getSource();
                if (dialog.getOwner() == frame && "security-equipment-dialog".equals(dialog.getName())) {
                    result.set(dialog); opened.countDown();
                }
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                focus.addKeyEventDispatcher(delivery);
                Toolkit.getDefaultToolkit().addAWTEventListener(windows, AWTEvent.WINDOW_EVENT_MASK);
            });
            awaitRosterFocus(table);
            // Robot injection never reached Java on this host despite confirmed window/component focus.
            // This is posted-AWT-key integration, not native hardware input or a direct Action invocation.
            EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
            long when = System.currentTimeMillis();
            queue.postEvent(new KeyEvent(table, KeyEvent.KEY_PRESSED, when, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_E, 'e'));
            queue.postEvent(new KeyEvent(table, KeyEvent.KEY_RELEASED, when, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_E, 'e'));
            assertTrue("Posted Ctrl+E must reach KeyboardFocusManager dispatch", keyDelivered.await(5, TimeUnit.SECONDS));
            assertNull("Ctrl+E must be delivered with the exact roster/frame focus", wrongFocus.get());
            assertTrue("Ctrl+E must open the owned equipment dialog", opened.await(5, TimeUnit.SECONDS));
            return result.get();
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                focus.removeKeyEventDispatcher(delivery);
                Toolkit.getDefaultToolkit().removeAWTEventListener(windows);
            });
        }
    }

    private void awaitRosterFocus(JTable table) throws Exception {
        CountDownLatch focused = new CountDownLatch(1);
        FocusAdapter listener = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                if (table.isFocusOwner() && frame.isFocused()) focused.countDown();
            }
        };
        WindowAdapter activation = new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent event) { table.requestFocusInWindow(); }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                table.addFocusListener(listener); frame.addWindowFocusListener(activation);
                table.scrollRectToVisible(table.getCellRect(table.getSelectedRow(), 0, true));
                frame.toFront(); frame.requestFocus(); table.requestFocusInWindow();
                if (table.isFocusOwner() && frame.isFocused()) focused.countDown();
            });
            assertTrue("Timed out waiting for native roster focus", focused.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertSame(table, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
                assertSame(frame, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                table.removeFocusListener(listener); frame.removeWindowFocusListener(activation);
            });
        }
    }

    private void publishOnShow(Entity player) {
        frame.setVisible(false); ParsePanelGUI.update(player); frame.setVisible(true);
    }

    private static JLabel assertEquipmentText(JTable table, int row, int column, String name, String detail) {
        JLabel cell = (JLabel)table.prepareRenderer(table.getCellRenderer(row, column), row, column);
        assertEquals("", cell.getText());
        assertEquals(name, cell.getAccessibleContext().getAccessibleName());
        String description = cell.getAccessibleContext().getAccessibleDescription();
        assertTrue(description, description.startsWith(name)); assertTrue(description, description.contains(detail));
        assertFalse("Description must be plain text", description.startsWith("<html>"));
        javax.accessibility.AccessibleContext accessible = table.getAccessibleContext().getAccessibleTable()
                .getAccessibleAt(row, column).getAccessibleContext();
        assertEquals(name, accessible.getAccessibleName());
        assertEquals(description, accessible.getAccessibleDescription());
        return cell;
    }

    private static void invokeEquipmentShortcut(ParsePanelGUI panel, JTable table) {
        KeyStroke stroke = KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK);
        Object key = panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);
        Action action = panel.getActionMap().get(key);
        assertNotNull(action);
        assertTrue("Invoke the actual key binding, not merely action presence", SwingUtilities.notifyAction(action, stroke,
                new KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_E, 'e'),
                table, InputEvent.CTRL_DOWN_MASK));
    }

    private static void assertFocusDistinctFromSelection(JTable table, int row, int column) {
        for (boolean selected : new boolean[]{false, true}) {
            int[] unfocused = renderCell(table, row, column, selected, false);
            int[] focused = renderCell(table, row, column, selected, true);
            assertFalse("Focus must be visible independently of selection=" + selected, Arrays.equals(unfocused, focused));
        }
    }

    private static int[] renderCell(JTable table, int row, int column, boolean selected, boolean focus) {
        Component cell = table.getCellRenderer(row, column).getTableCellRendererComponent(table, table.getValueAt(row, column), selected, focus, row, column);
        int width = 90, height = table.getRowHeight();
        cell.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics(); cell.paint(graphics); graphics.dispose();
        return image.getRGB(0, 0, width, height, null, 0, width);
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
        String detailsPlayer, details;
        List<Player> exported;
        @Override protected void clicked(boolean full) { last = full ? "json" : "names"; }
        @Override protected void saveNamesAsText() { last = "export-names"; }
        @Override protected void saveAsJson(List<Player> players) { last = "export-json"; exported = players; }
        @Override protected void showEquipmentDetails(String playerName, String details) { this.detailsPlayer = playerName; this.details = details; }
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
