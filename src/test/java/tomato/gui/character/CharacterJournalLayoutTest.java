package tomato.gui.character;

import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.enums.StatType;
import tomato.backend.data.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.stats.Formatters;
import static org.junit.Assert.*;

/** Exact client-size fixtures complement the native-window checks on scaled/capped desktops. */
public class CharacterJournalLayoutTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private CharacterJournal journal;
    private CharacterJournalGUI panel;
    private WorkspaceShell shell;
    private JFrame frame;
    private Font oldFont;
    private LookAndFeel oldLookAndFeel;
    private static final String LONG_NAME = "AccountWithAnUnbrokenVeryLongName_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789";
    private static final String LONG_NOTES = String.join("\n", java.util.Collections.nCopies(30,
            "Finish Life and Wisdom; keep the captured equipment and this unsaved note."));

    @Before public void setup() throws Exception {
        journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            oldFont = ContentStyle.body(); oldLookAndFeel = UIManager.getLookAndFeel();
            VioletTheme.install();
        });
    }

    @After public void cleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) frame.dispose();
            else if (shell != null && shell.isDisplayable()) shell.removeNotify();
            ContentStyle.setBodyFont(oldFont);
            try { UIManager.setLookAndFeel(oldLookAndFeel); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
        journal.close();
    }

    private void populate() {
        Entity source = CharacterJournalTest.player("layout-account", 782);
        source.stat.get(StatType.NAME_STAT).stringStatValue = LONG_NAME;
        CharacterJournalTest.put(source, StatType.ATTACK_STAT, 70);
        CharacterJournalTest.put(source, StatType.ATTACK_BOOST_STAT, 0);
        CharacterJournalTest.put(source, StatType.INVENTORY_0_STAT, 12345);
        CharacterJournalTest.put(source, StatType.INVENTORY_1_STAT, -1);
        for (int i = 1; i <= 20; i++) {
            String account = journal.observe(source, i);
            journal.notes(account + ":" + i, LONG_NOTES);
        }
    }

    private void createShell(int font) {
        if (frame == null && shell != null && shell.isDisplayable()) shell.removeNotify();
        ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, font));
        ContentStyle.applyFontDefaults();
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
        for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
        // Production Roster / Exalts / Pets tabs, not just the journal as a frame's content.
        pages[3] = new CharacterPanelGUI(new TomatoData() {
            @Override public CharacterJournal characterJournal() { return journal; }
        });
        panel = find(pages[3], CharacterJournalGUI.class);
        shell = new WorkspaceShell(pages, () -> {}, false);
        shell.select(3);
        ContentStyle.refreshFonts(shell);
    }

    @Test public void exactClientMatrixRetainsRowsAndScrollReachableControls() throws Exception {
        for (boolean populated : new boolean[]{false, true}) {
            if (populated) populate();
            SwingUtilities.invokeAndWait(() -> createShell(13));
            for (int font : new int[]{13, 16, 24}) for (Dimension size : new Dimension[]{new Dimension(1240, 800), new Dimension(680, 520)}) {
                SwingUtilities.invokeAndWait(() -> {
                    ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, font));
                    ContentStyle.refreshFonts(shell);
                    shell.setSize(size);
                    assertNull("Exact geometry fixture must have no native window", SwingUtilities.getWindowAncestor(shell));
                    shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
                });
                settle();
                exerciseTabs(populated);
                SwingUtilities.invokeAndWait(() -> {
                    JSplitPane split = named(panel, "character-roster-detail-split", JSplitPane.class);
                    int before = split.getDividerLocation();
                    int minimum = split.getMinimumDividerLocation(), maximum = split.getMaximumDividerLocation();
                    assertTrue("Divider must have a valid range", maximum >= minimum);
                    split.setDividerLocation(0);
                    layout(shell);
                    assertRows(find((Container)split.getTopComponent(), JTable.class));
                    split.setDividerLocation(split.getHeight());
                    layout(shell);
                    assertRows(find((Container)named(panel, "character-detail-tabs", JTabbedPane.class).getComponentAt(0), JTable.class));
                    split.setDividerLocation(before);
                    assertEquals("No huge-frame workaround", size, shell.getSize());
                });
            }
        }
    }

    @Test public void nativeCompactRequestChecksRealizedGeometryAtAllFonts() throws Exception {
        nativeMatrix(new Dimension(680, 520));
    }

    @Test public void nativeDesktopRequestChecksRealizedGeometryAtAllFonts() throws Exception {
        nativeMatrix(new Dimension(1240, 800));
    }

    private void nativeMatrix(Dimension client) throws Exception {
        populate();
        SwingUtilities.invokeAndWait(() -> {
            createShell(13);
            frame = new JFrame("Characters layout validation"); frame.setContentPane(shell);
            frame.pack();
            Insets insets = frame.getInsets();
            frame.setSize(client.width + insets.left + insets.right, client.height + insets.top + insets.bottom);
            frame.setVisible(true);
        });
        settle();
        for (int font : new int[]{13, 16, 24}) {
            SwingUtilities.invokeAndWait(() -> {
                ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, font));
                ContentStyle.refreshFonts(shell);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Dimension actual = shell.getSize();
                System.out.println("Characters native usability: requested client=" + client + ", realized client=" + actual
                        + ", font=" + font + (client.equals(actual) ? "; request realized" : "; HOST CLAMP — not an exact-size pass"));
                assertTrue("Realized native client must have usable area", actual.width > 0 && actual.height > 0);
            });
            exerciseTabs(true);
            assertKeyboardSearchReachable();
        }
    }

    private void exerciseTabs(boolean populated) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable roster = named(panel, "character-roster", JTable.class);
            assertEquals(populated ? 20 : 0, roster.getRowCount());
            assertRows(roster);
            if (populated) {
                assertTrue(roster.getValueAt(0, 1).toString().contains(LONG_NAME));
                assertEquals(roster.getValueAt(0, 1), ((JLabel)roster.prepareRenderer(roster.getCellRenderer(0, 1), 0, 1)).getToolTipText());
                assertEquals(Integer.class, roster.getColumnClass(5));
                assertNull(roster.getValueAt(0, 5));
                assertEquals("Unknown", ((JLabel)roster.prepareRenderer(roster.getCellRenderer(0, 5), 0, 5)).getText());
                assertEquals(Formatters.formatTimestamp((Long)roster.getValueAt(0, 7)),
                        ((JLabel)roster.prepareRenderer(roster.getCellRenderer(0, 7), 0, 7)).getText());
                roster.getRowSorter().toggleSortOrder(0);
                roster.setRowSelectionInterval(0, 0);
                reachableRow(roster, roster.getRowCount() - 1);
            }
            reachable(named(panel, "character-search", JTextField.class));
            assertControlsReachable(panel);
            reachable(button(panel, "Mark dead"));
            assertEquals(populated, button(panel, "Mark dead").isEnabled());
        });
        for (int tab = 0; tab < 4; tab++) {
            final int index = tab;
            SwingUtilities.invokeAndWait(() -> named(panel, "character-detail-tabs", JTabbedPane.class).setSelectedIndex(index));
            settle();
            SwingUtilities.invokeAndWait(() -> {
                JTabbedPane tabs = named(panel, "character-detail-tabs", JTabbedPane.class);
                assertEquals("Detail navigation must wrap instead of clipping a scrolling tab strip", JTabbedPane.WRAP_TAB_LAYOUT, tabs.getTabLayoutPolicy());
                Rectangle tabBounds = tabs.getBoundsAt(index);
                tabs.scrollRectToVisible(tabBounds);
                assertTrue("Selected detail tab must remain reachable: tab=" + index + ", bounds=" + tabBounds
                        + ", visible=" + tabs.getVisibleRect() + ", shell=" + shell.getSize() + ", font=" + tabs.getFont(),
                        tabs.getVisibleRect().contains(tabBounds));
                for (Component child : tabs.getComponents()) if (child instanceof JViewport) {
                    JViewport strip = (JViewport)child;
                    Rectangle tabInStrip = SwingUtilities.convertRectangle(tabs, tabBounds, strip.getView());
                    assertTrue("The internal horizontal tab viewport must contain the whole selected tab: " + tabInStrip
                            + " within " + strip.getViewRect(), strip.getViewRect().contains(tabInStrip));
                }
                if (index < 3) {
                    JTable table = find((Container)tabs.getSelectedComponent(), JTable.class);
                    assertRows(table);
                    if (populated) {
                        reachableRow(table, 0); reachableRow(table, table.getRowCount() - 1);
                        if (index == 0) {
                            assertEquals("Unknown", table.getValueAt(0, 1));
                            assertEquals(70, table.getValueAt(2, 1));
                        }
                    }
                } else {
                    JTextArea notes = named(panel, "character-notes", JTextArea.class);
                    assertTrue("Three editable lines must survive detail chrome", ((JViewport)notes.getParent()).getExtentSize().height
                            >= notes.getFontMetrics(notes.getFont()).getHeight() * 3);
                    assertEquals(populated ? LONG_NOTES : "", notes.getText());
                    if (populated) {
                        try {
                            Rectangle lastLine = notes.modelToView(notes.getDocument().getLength());
                            revealRegion(notes, lastLine);
                            assertTrue("The end of long notes must be scroll reachable", notes.getVisibleRect().contains(lastLine));
                        } catch (BadLocationException e) { throw new AssertionError(e); }
                    }
                    reachable(button(panel, "Save notes"));
                    assertEquals(populated, button(panel, "Save notes").isEnabled());
                }
                assertWrappingTextFits(panel);
            });
        }
    }

    @Test public void backgroundSnapshotRefreshPreservesDraftAndSelectionSavesIt() throws Exception {
        populate();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> draftKey = new AtomicReference<>(), savedKey = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            createShell(24);
            JTextArea notes = named(panel, "character-notes", JTextArea.class);
            JTable roster = named(panel, "character-roster", JTable.class);
            String character = roster.getValueAt(roster.getSelectedRow(), 0).toString();
            draftKey.set(keyForSelectedCharacter(roster));
            notes.setText("Draft survives capture and background save");
            Thread capture = new Thread(() -> {
                try { journal.observe(CharacterJournalTest.player("layout-account", 782), 1); journal.save(); }
                catch (Throwable t) { failure.set(t); }
            }, "character-ui-background-save-test");
            capture.start();
            try { capture.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse("Journal work must not wait for EDT", capture.isAlive()); assertNull(failure.get());
            panel.refresh();
            assertEquals(character, roster.getValueAt(roster.getSelectedRow(), 0));
            assertEquals("Draft survives capture and background save", notes.getText());
            assertEquals(draftKey.get(), keyForSelectedCharacter(roster));
            int differentRow = (roster.getSelectedRow() + 1) % roster.getRowCount();
            roster.setRowSelectionInterval(differentRow, differentRow);
            savedKey.set(keyForSelectedCharacter(roster));
            assertNotEquals("Exercise an actual identity change after last-seen reordering", draftKey.get(), savedKey.get());
            assertEquals("Draft survives capture and background save", notesFor(journal, draftKey.get()));
            notes.setText("Explicitly saved notes"); button(panel, "Save notes").doClick();
            assertEquals("Explicitly saved notes", notesFor(journal, savedKey.get()));
        });
        journal.save();
        try (CharacterJournal reopened = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"))) {
            assertEquals("Draft survives capture and background save", notesFor(reopened, draftKey.get()));
            assertEquals("Explicitly saved notes", notesFor(reopened, savedKey.get()));
        }
    }

    private static String keyForSelectedCharacter(JTable roster) {
        String label = roster.getValueAt(roster.getSelectedRow(), 0).toString();
        return CharacterJournal.accountKey("layout-account") + ":" + label.substring(label.lastIndexOf('#') + 1);
    }

    private static String notesFor(CharacterJournal journal, String key) {
        return journal.characters().stream().filter(r -> r.key.equals(key)).findFirst().get().notes;
    }

    @Test public void fameAndSummaryUseDisplayLocaleWhileModelsIdsAndDatesStayStable() throws Exception {
        Entity source = CharacterJournalTest.player("layout-account", 782);
        CharacterJournalTest.put(source, StatType.CURR_FAME_STAT, 1_234_567);
        journal.observe(source, 12345);
        CharacterJournalTest.put(source, StatType.CURR_FAME_STAT, 2345);
        journal.observe(source, 67890);
        SwingUtilities.invokeAndWait(() -> {
            Locale original = Locale.getDefault(Locale.Category.FORMAT);
            try {
                for (Locale locale : new Locale[]{Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG")}) {
                    Locale.setDefault(Locale.Category.FORMAT, locale);
                    createShell(13);
                    JTable roster = named(panel, "character-roster", JTable.class);
                    roster.getRowSorter().toggleSortOrder(6);
                    assertEquals("Sorting still uses raw fame numbers", 2345L, roster.getValueAt(0, 6));
                    assertEquals(1_234_567L, roster.getValueAt(1, 6));
                    assertTrue(roster.getValueAt(1, 0).toString().endsWith("#12345"));
                    String fame = ((JLabel)roster.prepareRenderer(roster.getCellRenderer(1, 6), 1, 6)).getText();
                    assertEquals(DisplayFormat.formatInteger(1_234_567), fame);
                    if (locale.equals(Locale.US)) assertEquals("1,234,567", fame);
                    if (locale.equals(Locale.GERMANY)) assertEquals("1.234.567", fame);
                    JTextArea summary = named(panel, "character-summary", JTextArea.class);
                    assertEquals(DisplayFormat.formatInteger(2) + " not marked dead  •  " + DisplayFormat.formatInteger(0)
                            + " marked dead manually  •  " + DisplayFormat.formatInteger(0) + " at 8/8  •  " + DisplayFormat.formatInteger(2) + " shown", summary.getText());
                    String timestamp = ((JLabel)roster.prepareRenderer(roster.getCellRenderer(1, 7), 1, 7)).getText();
                    assertEquals(Formatters.formatTimestamp((Long)roster.getValueAt(1, 7)), timestamp);
                    assertTrue("Full, unambiguous timestamp", timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"));
                }
            } finally { Locale.setDefault(Locale.Category.FORMAT, original); }
        });
        assertEquals(Long.valueOf(1_234_567), journal.characters().stream().filter(r -> r.characterId == 12345).findFirst().get().fame);
    }

    private void settle() throws Exception {
        for (int i = 0; i < 10; i++) SwingUtilities.invokeAndWait(() -> {
            if (frame != null) frame.validate(); else layout(shell);
        });
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layout((Container)child);
    }

    private static void assertRows(JTable table) {
        JViewport viewport = (JViewport)table.getParent();
        assertTrue("At least three allocated content rows: " + viewport.getExtentSize() + ", row=" + table.getRowHeight(),
                viewport.getExtentSize().height >= table.getRowHeight() * 3);
    }

    private static void reachableRow(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, 0, true);
        revealRegion(table, cell);
        assertTrue("The whole row cell must be scroll reachable: " + cell + " within " + table.getVisibleRect(), table.getVisibleRect().contains(cell));
    }

    private static void reachable(JComponent control) {
        assertNotNull(control);
        assertTrue("Control height fits text: " + control, control.getHeight() >= control.getPreferredSize().height);
        revealRegion(control, new Rectangle(0, 0, control.getWidth(), control.getHeight()));
        if (control.isShowing() && !control.getVisibleRect().equals(new Rectangle(0, 0, control.getWidth(), control.getHeight())))
            new ui.VisualEvidence("wave2").capture(SwingUtilities.getWindowAncestor(control),
                    "character-clipped-" + control.getName() + "-" + control.getFont().getSize());
        assertEquals("Whole control must be contained or scroll reachable: " + control.getName()
                + " / " + control.getAccessibleContext().getAccessibleName(),
                new Rectangle(0, 0, control.getWidth(), control.getHeight()), control.getVisibleRect());
    }

    /** Exercise the real viewport scroll routes, from the inner table/notes to the outer page. */
    private static void revealRegion(JComponent component, Rectangle region) {
        int viewports = 0;
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (!(parent instanceof JViewport)) continue;
            JComponent view = (JComponent)((JViewport)parent).getView();
            view.scrollRectToVisible(SwingUtilities.convertRectangle(component, region, view));
            viewports++;
        }
        assertTrue("Expected a real scrolling viewport", viewports > 0);
    }

    private void assertKeyboardSearchReachable() throws Exception {
        JTable roster = named(panel, "character-roster", JTable.class);
        JTextField search = named(panel, "character-search", JTextField.class);
        awaitFocus(roster);
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane page = named(panel, "character-page-scroll", JScrollPane.class);
            page.getVerticalScrollBar().setValue(page.getVerticalScrollBar().getMaximum());
        });
        awaitFocus(search);
        SwingUtilities.invokeAndWait(() -> assertEquals("Keyboard focus must reveal the entire search control without fixture scrolling",
                new Rectangle(0, 0, search.getWidth(), search.getHeight()), search.getVisibleRect()));
    }

    private void awaitFocus(JComponent component) throws Exception {
        CountDownLatch focused = new CountDownLatch(1);
        FocusAdapter listener = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { focused.countDown(); }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                component.addFocusListener(listener);
                frame.toFront(); component.requestFocusInWindow();
                if (component.isFocusOwner()) focused.countDown();
            });
            assertTrue("Timed out waiting for focus on " + component.getName(), focused.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertSame(component, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        } finally { SwingUtilities.invokeAndWait(() -> component.removeFocusListener(listener)); }
    }

    private static void assertWrappingTextFits(Container root) {
        for (Component component : root.getComponents()) {
            if (!component.isVisible()) continue;
            if (component instanceof JTextArea && !((JTextArea)component).isEditable()) {
                JTextArea area = (JTextArea)component;
                try {
                    Rectangle end = area.modelToView(area.getDocument().getLength());
                    assertNotNull(end);
                    assertTrue("Wrapped metadata must allocate its final line: " + area.getText(), end.y + end.height <= area.getHeight());
                } catch (BadLocationException e) { throw new AssertionError(e); }
            } else if (component instanceof Container) assertWrappingTextFits((Container)component);
        }
    }

    private static void assertControlsReachable(Container root) {
        for (Component c : root.getComponents()) {
            if (!c.isVisible()) continue;
            if (c instanceof JComboBox) reachable((JComponent)c);
            else if (c instanceof Container) assertControlsReachable((Container)c);
        }
    }

    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton)c).getText())) return (JButton)c;
            if (c instanceof Container) { JButton found = button((Container)c, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type); if (found != null) return found; }
        }
        return null;
    }
}
