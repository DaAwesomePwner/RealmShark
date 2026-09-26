package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import tomato.backend.data.*;
import tomato.gui.modern.*;
import tomato.planning.*;
import tomato.realmshark.RealmCharacter;

/** Synthetic native fixtures. Screenshot production alone is not a visual/a11y pass. */
public class CharacterWaveFourEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public ui.WaveThreeEvidence.FixtureZone zone = new ui.WaveThreeEvidence.FixtureZone();
    @Test public void populatedEmptyAndUnavailablePlanningEquipmentAndDeathSurfaces() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        try (CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json")); PlanningStore plans = PlanningStore.memory()) {
            String account = CharacterJournal.accountKey("synthetic-wave4");
            RealmCharacter source = new RealmCharacter(); source.charId = 7; source.classNum = 782; source.level = 20;
            source.supplied("class"); source.supplied("level"); source.capturedStatMask = 255; source.hp = 654; source.mp = 385; source.atk = 75; source.def = 25; source.spd = 50; source.dex = 75; source.vit = 40; source.wis = 60;
            source.equipment = new int[]{12345, -1, 23456}; source.receivedAt = 1700000000000L; journal.mergeRoster(account, Collections.singletonList(source));
            RosterDefinitions definitions = CharacterRosterQueryTest.definitions();
            PlanData.AccountPlan plan = new PlanData.AccountPlan(); CharacterGoals.pinCharacter(plan, journal.characters().get(0), 0, 670, definitions, 1700000000000L);
            CharacterGoals.pinExalt(plan, 782, 0, 2, PlanningMetadata.unavailable(), 1700000000000L);
            assertTrue(plans.update(account, 0, plan).get().saved);
            CharacterJournal.DeathAnnotation death = new CharacterJournal.DeathAnnotation(); death.notes = "Synthetic manual note; no server-confirmed death."; death.occurredAt = 1700000000000L;
            death.visit = new tomato.history.link.VisitRef("00000000-0000-0000-0000-000000000001", "unavailable-synthetic-visit"); journal.markDead(account + ":7", true); journal.annotateDeath(account + ":7", death);
            SwingUtilities.invokeAndWait(() -> {
                Font previous = ContentStyle.body(); LookAndFeel look = UIManager.getLookAndFeel(); JFrame frame = null;
                try {
                    VioletTheme.install(); frame = new JFrame("Synthetic Wave 4 character evidence");
                    CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 1700000001000L, () -> definitions, plans);
                    frame.setContentPane(panel); frame.setSize(1080, 800); frame.setVisible(true); frame.validate();
                    JTabbedPane tabs = named(panel, "character-detail-tabs", JTabbedPane.class);
                    tabs.setSelectedIndex(tabs.indexOfTab("Goals")); named(panel, "planning-0", JComboBox.class).setSelectedIndex(1);
                    JTable goals = named(panel, "planning-8", JTable.class); assertEquals(2, goals.getRowCount());
                    ContentStyle.reveal(goals, goals.getCellRect(0, 0, true)); capture(frame, "goals-populated");
                    frame.setSize(680, 520); frame.validate(); ContentStyle.reveal(goals, goals.getCellRect(0, 0, true)); capture(frame, "goals-compact");
                    named(panel, "planning-7", JTextField.class).setText("no-matching-fixture"); assertEquals(0, goals.getRowCount()); capture(frame, "goals-empty-search"); named(panel, "planning-7", JTextField.class).setText("");
                    tabs.setSelectedIndex(tabs.indexOfTab("Equipment & inventory")); JTable gear = named(panel, "character-equipment", JTable.class); assertEquals(28, gear.getRowCount()); ContentStyle.reveal(gear, gear.getCellRect(0, 0, true)); capture(frame, "equipment-compact");
                    tabs.setSelectedIndex(tabs.indexOfTab("Death annotation")); JTextField occurred = named(panel, "death-occurred", JTextField.class); ContentStyle.reveal(occurred, new Rectangle(0, 0, occurred.getWidth(), occurred.getHeight())); capture(frame, "death-unavailable-link");
                    ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, 22)); ContentStyle.applyFontDefaults(); ContentStyle.refreshFonts(frame); ui.UiTestLayout.settle(frame);
                    float metadataSize = ContentStyle.metadata(ContentStyle.body()).getSize2D();
                    assertEquals("Snapshot evidence follows enlarged metadata role", metadataSize, named(panel, "character-snapshot-evidence", JTextArea.class).getFont().getSize2D(), .05f);
                    assertEquals("Read-only run evidence uses wrappingText's metadata role (12/13 of body)", metadataSize, named(panel, "death-run-link", JTextArea.class).getFont().getSize2D(), .05f);
                    ContentStyle.reveal(occurred, new Rectangle(0, 0, occurred.getWidth(), occurred.getHeight())); capture(frame, "death-enlarged-text");
                } catch (Exception failure) { throw new RuntimeException(failure); }
                finally { if (frame != null) frame.dispose(); ContentStyle.setBodyFont(previous); try { UIManager.setLookAndFeel(look); } catch (Exception failure) { throw new RuntimeException(failure); } }
            });
        }
    }
    @Test public void enlargedDeathActionsAreRevealedByNativeFocusAndKeyboardTab() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        try (CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("focus-journal.json")); PlanningStore plans = PlanningStore.memory()) {
            String account = CharacterJournal.accountKey("synthetic-death-focus");
            RealmCharacter source = new RealmCharacter(); source.charId = 8; source.classNum = 782; source.supplied("class");
            journal.mergeRoster(account, Collections.singletonList(source)); journal.markDead(account + ":8", true);
            CharacterJournal.DeathAnnotation annotation = new CharacterJournal.DeathAnnotation(); annotation.notes = "Synthetic manual annotation for keyboard evidence.";
            annotation.visit = new tomato.history.link.VisitRef("00000000-0000-0000-0000-000000000001", "unavailable-synthetic-visit"); journal.annotateDeath(account + ":8", annotation);
            JFrame[] frame = new JFrame[1]; JButton[] actions = new JButton[2]; Font[] oldFont = new Font[1]; LookAndFeel[] oldLook = new LookAndFeel[1];
            try {
                ui.WaveThreeEvidence.run(() -> {
                    oldFont[0] = ContentStyle.body(); oldLook[0] = UIManager.getLookAndFeel(); VioletTheme.install();
                    ContentStyle.setBodyFont(new Font("Segoe UI", Font.PLAIN, 22)); ContentStyle.applyFontDefaults();
                    CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 1700000001000L, RosterDefinitions::empty, plans);
                    frame[0] = new JFrame("Synthetic enlarged death keyboard evidence"); frame[0].setContentPane(panel); frame[0].setSize(680, 520); frame[0].setVisible(true);
                    JTabbedPane tabs = named(panel, "character-detail-tabs", JTabbedPane.class); tabs.setSelectedIndex(tabs.indexOfTab("Death annotation"));
                    ContentStyle.refreshFonts(frame[0]); ui.UiTestLayout.settle(frame[0]);
                    actions[0] = button(panel, "Open exact run"); actions[1] = button(panel, "Save death annotation");
                    assertNotNull(actions[0]); assertNotNull(actions[1]); assertTrue(actions[0].isEnabled()); assertTrue(actions[1].isEnabled());
                    frame[0].toFront(); frame[0].requestFocus();
                });
                ui.WaveThreeEvidence.await(() -> frame[0].isFocused());
                ui.WaveThreeEvidence.run(() -> assertTrue("Native focus request accepted", actions[0].requestFocusInWindow()));
                ui.WaveThreeEvidence.await(() -> actions[0].isFocusOwner());
                ui.WaveThreeEvidence.run(() -> {
                    ui.UiTestLayout.settle(frame[0]); assertFullyVisible(actions[0]); capture(frame[0], "death-open-run-keyboard-enlarged");
                });
                // Wrapped Swing controls and scroll bars can add traversal stops at enlarged/scaled sizes.
                // Verify real Tab reachability, without assuming Save is exactly one platform-specific stop away.
                Robot keyboard = new Robot(); keyboard.setAutoDelay(50); java.util.List<String> visited = new ArrayList<>();
                for (int step = 0; step < 40 && !ui.WaveThreeEvidence.edt(() -> actions[1].isFocusOwner()); step++) {
                    keyboard.keyPress(java.awt.event.KeyEvent.VK_TAB); keyboard.keyRelease(java.awt.event.KeyEvent.VK_TAB); keyboard.waitForIdle();
                    visited.add(ui.WaveThreeEvidence.edt(() -> {
                        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                        return owner instanceof JButton ? ((JButton)owner).getText() : owner == null ? "No focus" : owner.getClass().getSimpleName() + ":" + owner.getName();
                    }));
                }
                assertTrue("Save death annotation must be reachable by keyboard Tab; visited " + visited, ui.WaveThreeEvidence.edt(() -> actions[1].isFocusOwner()));
                ui.WaveThreeEvidence.run(() -> {
                    ui.UiTestLayout.settle(frame[0]); assertFullyVisible(actions[1]); capture(frame[0], "death-save-keyboard-enlarged");
                });
            } finally {
                ui.WaveThreeEvidence.run(() -> {
                    if (frame[0] != null) frame[0].dispose();
                    if (oldFont[0] != null) ContentStyle.setBodyFont(oldFont[0]);
                    if (oldLook[0] != null) UIManager.setLookAndFeel(oldLook[0]); ContentStyle.applyFontDefaults();
                });
            }
        }
    }
    private static void assertFullyVisible(JComponent control) {
        assertTrue("Native keyboard focus must remain on action", control.isFocusOwner());
        assertTrue("Focused action must be completely inside all viewports: " + control.getName() + " " + control.getVisibleRect(),
            control.isShowing() && control.getWidth() > 0 && control.getHeight() > 0 && control.getVisibleRect().contains(new Rectangle(0, 0, control.getWidth(), control.getHeight())));
    }
    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton)child).getText())) return (JButton)child;
            if (child instanceof Container) { JButton found = button((Container)child, text); if (found != null) return found; }
        }
        return null;
    }
    private static void capture(JFrame frame, String name) throws Exception {
        ui.UiTestLayout.settle(frame); assertWrappedControlsFit(frame);
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics(); frame.paint(g); g.dispose();
        Path path = Paths.get("screenshots/wave4/characters"); Files.createDirectories(path); ImageIO.write(image, "png", path.resolve(name + ".png").toFile());
    }
    private static void assertWrappedControlsFit(Container root) {
        if (root.isShowing() && root.getLayout() instanceof FlowLayout) for (Component child : root.getComponents()) if (child.isVisible())
            assertTrue("Wrapped control clipped: " + (child instanceof JButton ? ((JButton)child).getText() : child.getName()) + " " + child.getBounds() + " in " + root.getSize(),
                child.getX() >= 0 && child.getY() >= 0 && child.getX() + child.getWidth() <= root.getWidth() && child.getY() + child.getHeight() <= root.getHeight());
        for (Component child : root.getComponents()) if (child instanceof Container) assertWrappedControlsFit((Container)child);
    }
    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) { if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c); if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; } } return null;
    }
}
