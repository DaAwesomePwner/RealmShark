package tomato.gui.character;

import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import tomato.backend.data.*;
import tomato.planning.*;

public class CharacterPlanningPanelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) { if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c); if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; } } return null;
    }
    private static JButton button(Container root, String text) { for (Component c : root.getComponents()) { if (c instanceof JButton && text.equals(((JButton)c).getText())) return (JButton)c; if (c instanceof Container) { JButton found = button((Container)c, text); if (found != null) return found; } } return null; }
    private static CharacterJournal.CharacterRecord record(String account) { CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord(); r.account = account; r.key = account + ":7"; r.characterId = 7; r.classId = 782; r.stats[0] = 654; return r; }
    private static void awaitReady(PlanningStore store) throws Exception { long end = System.nanoTime() + 5000000000L; while (!store.snapshot("A").ready && System.nanoTime() < end) Thread.sleep(5); assertTrue(store.snapshot("A").ready); }
    @Test public void explicitAccountAndFailedSaveDraftRemainIsolatedAcrossSwitches() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (PlanningStore store = new PlanningStore(temp.getRoot().toPath().resolve("plans.json"), (path, json) -> { if (fail.get()) throw new IOException("fixture"); Files.write(path, json.getBytes("UTF-8")); })) {
            awaitReady(store); RosterDefinitions definitions = CharacterRosterQueryTest.definitions(); CharacterPlanningPanel[] panel = new CharacterPlanningPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                CharacterPlanningPanel p = panel[0] = new CharacterPlanningPanel(store); p.refresh(Arrays.asList(record("A"), record("B")), Collections.emptyList(), definitions);
                assertFalse(button(p, "Pin max").isEnabled()); assertFalse(button(p, "Save goals").isEnabled());
                named(p, "planning-0", JComboBox.class).setSelectedIndex(1); button(p, "Pin max").doClick(); assertEquals(1, named(p, "planning-8", JTable.class).getRowCount()); button(p, "Save goals").doClick();
            });
            awaitButton(panel[0], "Save goals"); assertTrue(store.snapshot("A").plan().characterGoals.isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                JComboBox<?> account = named(panel[0], "planning-0", JComboBox.class); account.setSelectedIndex(2); assertEquals(0, named(panel[0], "planning-8", JTable.class).getRowCount());
                account.setSelectedIndex(1); assertEquals(1, named(panel[0], "planning-8", JTable.class).getRowCount()); fail.set(false); button(panel[0], "Save goals").doClick();
            });
            long end = System.nanoTime() + 5000000000L; while (store.snapshot("A").revision == 0 && System.nanoTime() < end) Thread.sleep(5);
            assertEquals(1, store.snapshot("A").plan().characterGoals.size()); assertTrue(store.snapshot("B").plan().characterGoals.isEmpty());
        }
    }
    @Test public void equipmentHasAll28DetachedStatesAndNoLiveEnchantClaim() {
        CharacterJournal.CharacterRecord r = record("A"); r.equipment[0] = 123; r.equipment[1] = -1;
        java.util.List<CharacterEquipmentPanel.Slot> rows = CharacterEquipmentPanel.project(r, RosterDefinitions.empty());
        assertEquals(28, rows.size()); assertEquals("Occupied", rows.get(0).state); assertEquals("Empty", rows.get(1).state); assertEquals("Not captured", rows.get(27).state);
        assertEquals("Equipped", rows.get(3).group); assertEquals("Inventory", rows.get(11).group); assertEquals("Backpack", rows.get(12).group);
        r.equipment[0] = 456; assertEquals(Integer.valueOf(123), rows.get(0).item); assertTrue(rows.get(0).detail.contains("Enchantment effects: Not recorded"));
    }
    @Test public void optionalDeathTimeDistinguishesUnknownFromManualInstant() {
        assertNull(CharacterDeathPanel.parseOccurred(" ")); assertEquals(Long.valueOf(1000), CharacterDeathPanel.parseOccurred("1970-01-01T00:00:01Z"));
        try { CharacterDeathPanel.parseOccurred("yesterday"); fail(); } catch (RuntimeException expected) { }
    }
    @Test public void sameMapRunsRemainDistinctAndSearchPrecedesBoundedRetention() throws Exception {
        try (tomato.history.SessionStore history = new tomato.history.SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            for (int i = 0; i < 4; i++) {
                packets.packetcapture.logger.ActivityJournal.Visit visit = new packets.packetcapture.logger.ActivityJournal.Visit();
                visit.id = "visit-" + i; visit.map = "Same named dungeon"; visit.started = 1000 + i;
                history.put("runs", visit.id, visit);
            }
            history.flush(); long[] matched = {0};
            java.util.List<CharacterDeathPanel.RunChoice> all = CharacterDeathPanel.findRuns(history, "", 2, matched);
            assertEquals(4, matched[0]); assertEquals(2, all.size()); assertNotEquals(all.get(0).reference, all.get(1).reference);
            matched[0] = 0; java.util.List<CharacterDeathPanel.RunChoice> older = CharacterDeathPanel.findRuns(history, "visit-0", 2, matched);
            assertEquals(1, matched[0]); assertEquals("visit-0", older.get(0).reference.visitId); assertEquals(history.currentId(), older.get(0).reference.sessionId);
        }
    }
    private static void awaitButton(CharacterPlanningPanel panel, String text) throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(); long end = System.nanoTime() + 5000000000L;
        while (System.nanoTime() < end) { SwingUtilities.invokeAndWait(() -> enabled.set(button(panel, text).isEnabled())); if (enabled.get()) return; Thread.sleep(5); }
        fail("Save did not complete");
    }
}
