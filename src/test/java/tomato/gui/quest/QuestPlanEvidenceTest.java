package tomato.gui.quest;

import org.junit.*;
import static org.junit.Assert.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.planning.PlanData;
import tomato.planning.PlanningStore;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Synthetic full planning page evidence. Native method must run in the serialized desktop lane. */
public class QuestPlanEvidenceTest {
    private Font oldFont;
    private LookAndFeel oldLaf;
    private PlanningStore store;
    private QuestPlanPanel panel;
    private JFrame frame;
    @Before public void setup() throws Exception {
        SwingUtilities.invokeAndWait(() -> { oldFont = ContentStyle.body(); oldLaf = UIManager.getLookAndFeel(); VioletTheme.install(); });
        store = PlanningStore.memory();
        long end = System.currentTimeMillis() + 5000;
        while (!store.snapshot("synthetic-account").ready && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(store.snapshot("synthetic-account").ready);
        PlanData.AccountPlan p = new PlanData.AccountPlan();
        p.quests.put("Royal tribute", QuestPlanningTest.entry("Royal tribute", 1, 1));
        PlanData.QuestPlanEntry unknown = QuestPlanningTest.entry("Uncaptured requirements"); unknown.requirementsKnown = false; p.quests.put(unknown.entryId, unknown);
        QuestPlanning.held(p, 1, 5, "Synthetic count; vault and inventory checked manually", false, 1750000000000L);
        QuestPlanning.reserve(p, "Royal tribute", 1, 2);
        assertTrue(store.update("synthetic-account", 0, p).get(5, TimeUnit.SECONDS).saved);
    }
    @After public void cleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) frame.dispose(); ContentStyle.setBodyFont(oldFont);
            try { UIManager.setLookAndFeel(oldLaf); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            ContentStyle.applyFontDefaults();
        });
        store.close();
    }
    private void create(int font) {
        ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font)); ContentStyle.applyFontDefaults();
        panel = new QuestPlanPanel(store, id -> id == 1 ? "Mark of the Forgotten King" : "Quest reward #" + id);
        panel.knownAccounts(Arrays.asList("synthetic-account", "synthetic-empty"));
        named("quest-plan-account", JComboBox.class).setSelectedItem("synthetic-account"); ContentStyle.refreshFonts(panel);
    }
    @Test public void exactCompactAndDesktopPagesExposePopulatedUnknownStaleErrorAndEmptyStates() throws Exception {
        for (int font : new int[]{13, 18}) for (int width : new int[]{680, 1100}) {
            SwingUtilities.invokeAndWait(() -> { create(font); panel.setSize(width, 520); }); settle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(width, panel.getWidth()); assertEquals(520, panel.getHeight());
                assertTrue(named("quest-plan-table", JTable.class).getRowHeight() >= panel.getFontMetrics(ContentStyle.body()).getHeight());
                capture("plans-populated-" + width + "-font" + font, panel);
                named("quest-plan-table", JTable.class).setRowSelectionInterval(1, 1);
            }); settle();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(named("quest-plan-totals", JTextArea.class).getText().startsWith("Unknown"));
                ContentStyle.reveal(named("quest-plan-totals", JTextArea.class), new Rectangle(0, 0, 50, 30));
                capture("plans-unknown-" + width + "-font" + font, panel);
                named("quest-plan-table", JTable.class).setRowSelectionInterval(0, 0);
                panel.observations("synthetic-account", true, Arrays.asList(QuestPlanningTest.quest("Royal tribute", 1, 1, 1, 1)), 1750000001000L, 2);
            }); settle();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(named("quest-plan-detail", JTextArea.class).getText().contains("Current requirements per repeat"));
                ContentStyle.reveal(named("quest-plan-detail", JTextArea.class), new Rectangle(0, 0, 50, 30));
                capture("plans-stale-" + width + "-font" + font, panel);
                named("quest-plan-held", JButton.class).doClick();
                assertTrue(named("quest-plan-status", JTextArea.class).getText().contains("Release reservations"));
                named("quest-plan-scroll", JScrollPane.class).getVerticalScrollBar().setValue(0);
                capture("plans-error-" + width + "-font" + font, panel);
                named("quest-plan-account", JComboBox.class).setSelectedItem("synthetic-empty");
            }); settle();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, named("quest-plan-table", JTable.class).getRowCount()); capture("plans-empty-" + width + "-font" + font, panel);
                ContentStyle.reveal(named("quest-plan-held", JButton.class), new Rectangle(0, 0, named("quest-plan-held", JButton.class).getWidth(), named("quest-plan-held", JButton.class).getHeight()));
                capture("plans-stock-controls-" + width + "-font" + font, panel);
            });
        }
    }
    @Test public void nativeKeyboardReleasesReservationsAndSavesAtCompactSize() throws Exception {
        assertFalse("Native evidence requires a desktop", GraphicsEnvironment.isHeadless());
        for (int font : new int[]{13, 18}) {
            SwingUtilities.invokeAndWait(() -> {
                if (frame != null) frame.dispose(); create(font);
                frame = new JFrame("Synthetic Wave 4 quest plan validation"); frame.setContentPane(panel);
                panel.setPreferredSize(new Dimension(680, 520)); frame.pack(); frame.setVisible(true);
                named("quest-plan-item-id", JSpinner.class).setValue(1);
                named("quest-plan-quantity", JSpinner.class).setValue(0L);
            }); settle();
            JButton held = namedOnEdt("quest-plan-held", JButton.class); focus(held);
            SwingUtilities.invokeAndWait(() -> assertTrue("Focus reveals entire held action", held.getVisibleRect().contains(new Rectangle(0, 0, held.getWidth(), held.getHeight()))));
            key(held, KeyEvent.VK_SPACE); settle();
            if (font == 13) SwingUtilities.invokeAndWait(() -> {
                assertTrue(named("quest-plan-status", JTextArea.class).getText().contains("Reservation exceeds"));
                named("quest-plan-scroll", JScrollPane.class).getVerticalScrollBar().setValue(0); capture("plans-native-error-font" + font, panel);
            });
            JCheckBox release = namedOnEdt("quest-plan-release-affected", JCheckBox.class); focus(release); key(release, KeyEvent.VK_SPACE);
            focus(held); key(held, KeyEvent.VK_SPACE); settle();
            SwingUtilities.invokeAndWait(() -> { assertTrue(named("quest-plan-totals", JTextArea.class).getText().contains("unallocated 0")); capture("plans-native-stock-font" + font, panel); });
            JButton save = namedOnEdt("quest-plan-save", JButton.class); focus(save); key(save, KeyEvent.VK_SPACE);
            long revision = font == 13 ? 2 : 3, end = System.currentTimeMillis() + 5000;
            while (store.snapshot("synthetic-account").revision < revision && System.currentTimeMillis() < end) Thread.sleep(10);
            assertEquals(revision, store.snapshot("synthetic-account").revision);
            assertEquals(0, store.snapshot("synthetic-account").plan().held.get(1).quantity);
            assertTrue(store.snapshot("synthetic-account").plan().reservations.isEmpty());
            focus(namedOnEdt("quest-plan-note", JTextField.class)); key(namedOnEdt("quest-plan-note", JTextField.class), KeyEvent.VK_TAB);
            SwingUtilities.invokeAndWait(() -> assertNotNull(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        }
    }
    private void settle() throws Exception { for (int i = 0; i < 8; i++) SwingUtilities.invokeAndWait(() -> { layout(panel); if (frame != null) frame.validate(); }); }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container) layout((Container)child); }
    private void capture(String name, JComponent value) {
        try {
            File dir = new File("screenshots/wave4/quests"); assertTrue(dir.isDirectory() || dir.mkdirs());
            BufferedImage image = new BufferedImage(value.getWidth(), value.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); value.printAll(g); g.dispose(); assertTrue(ImageIO.write(image, "png", new File(dir, name + ".png")));
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
    private <T extends Component> T named(String name, Class<T> type) { T c = find(panel, name, type); assertNotNull(name, c); return c; }
    private <T extends Component> T namedOnEdt(String name, Class<T> type) throws Exception { java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>(); SwingUtilities.invokeAndWait(() -> result.set(named(name, type))); return result.get(); }
    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container) for (Component child : ((Container)root).getComponents()) { T c = find(child, name, type); if (c != null) return c; } return null;
    }
    private void focus(JComponent c) throws Exception {
        CountDownLatch active = new CountDownLatch(1), focused = new CountDownLatch(1);
        WindowFocusListener window = new WindowAdapter() { @Override public void windowGainedFocus(WindowEvent e) { active.countDown(); } };
        FocusListener listener = new FocusAdapter() { @Override public void focusGained(FocusEvent e) { focused.countDown(); } };
        try {
            SwingUtilities.invokeAndWait(() -> { frame.addWindowFocusListener(window); if (frame.isFocused()) active.countDown(); else frame.toFront(); });
            assertTrue("Window focus", active.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> { c.addFocusListener(listener); if (c.isFocusOwner()) focused.countDown(); else c.requestFocusInWindow(); });
            assertTrue("Focus " + c.getName(), focused.await(5, TimeUnit.SECONDS)); settle();
        } finally { SwingUtilities.invokeAndWait(() -> { c.removeFocusListener(listener); frame.removeWindowFocusListener(window); }); }
    }
    private static void key(JComponent c, int code) throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        KeyEventDispatcher observer = e -> { if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == code) { assertSame(c, e.getComponent()); delivered.countDown(); } return false; };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(observer);
        try {
            long time = System.currentTimeMillis(); EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
            q.postEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, time, 0, code, KeyEvent.CHAR_UNDEFINED)); q.postEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, time, 0, code, KeyEvent.CHAR_UNDEFINED));
            assertTrue("Posted key delivered", delivered.await(5, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> {});
        } finally { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(observer); }
    }
}
