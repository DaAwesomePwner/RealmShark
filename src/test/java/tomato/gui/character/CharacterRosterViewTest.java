package tomato.gui.character;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.character.CharacterCardModel;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

/** The Roster tab's two cards: Enter and double-click open a row's sheet, the back link returns, and the gallery's row feed. */
public class CharacterRosterViewTest {
    private static final String ACCOUNT = CharacterJournal.accountKey("roster-view-fixture");
    private static final String TABS = "ui.tabs.character";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedTabs;

    // showSheet(key, tab, ...) with an explicit tab may show() a previously hidden one, which persists the saved tab order
    // (CustomizableTabs.show -> save()); isolate it like CharacterSheetTest does.
    @Before public void rememberTabs() { savedTabs = util.PropertiesManager.getProperty(TABS); util.PropertiesManager.setProperties(TABS, ""); }
    @After public void restoreTabs() { util.PropertiesManager.setProperties(TABS, savedTabs == null ? "" : savedTabs); }

    private CharacterJournal journal() {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.receivedAt = 1000L * id; c.supplied("class"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static String keyAt(JTable roster, int row) {
        String label = roster.getValueAt(row, 0).toString();
        return ACCOUNT + ":" + label.substring(label.lastIndexOf('#') + 1);
    }

    @Test public void enterAndDoubleClickOpenARowsSheetAndTheBackLinkReturnsToTheList() throws Exception {
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                assertEquals("character-roster-view", view.getName());
                JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
                assertEquals(3, roster.getRowCount()); assertFalse(view.showingSheet());
                assertTrue(view.listPanel().isVisible()); assertFalse(view.sheet().isVisible());
                roster.setRowSelectionInterval(1, 1);
                RosterFixtures.enter(view);
                assertTrue("Enter opens the selected row", view.showingSheet());
                assertEquals(keyAt(roster, 1), view.sheet().key());
                assertFalse(view.listPanel().isVisible()); assertTrue(view.sheet().isVisible());
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse("Without a navigator the back link switches cards in place", view.showingSheet());
                assertTrue(view.listPanel().isVisible());
                assertEquals("The list keeps its selection", 1, roster.getSelectedRow());
                Rectangle cell = roster.getCellRect(2, 0, true);
                roster.dispatchEvent(new MouseEvent(roster, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 1, false, MouseEvent.BUTTON1));
                assertFalse("A single click only selects", view.showingSheet());
                roster.dispatchEvent(new MouseEvent(roster, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 2, false, MouseEvent.BUTTON1));
                assertTrue("A double-click opens the row under the pointer", view.showingSheet());
                assertEquals(keyAt(roster, 2), view.sheet().key());
            });
        }
    }

    @Test public void theBackLinkKeepsAnUnsavedNotesDraft() throws Exception {
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
                roster.setRowSelectionInterval(0, 0); String key = keyAt(roster, 0);
                RosterFixtures.enter(view);
                JTextArea notes = RosterFixtures.named(view.sheet(), "character-notes", JTextArea.class);
                notes.setText("Typed, never saved");
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse(view.showingSheet());
                assertEquals("Back without Save keeps the note", "Typed, never saved", journal.characterCopy(key).notes);
                RosterFixtures.enter(view);
                assertEquals("…and the sheet shows it again", "Typed, never saved", notes.getText());
            });
        }
    }

    @Test public void loadingAnotherDeadCharactersSheetDoesNotOverwriteTheSavedTabWithOverview() throws Exception {
        try (CharacterJournal journal = journal()) {
            journal.markDead(ACCOUNT + ":1", true);
            journal.markDead(ACCOUNT + ":2", true);
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                view.showSheet(ACCOUNT + ":1", "death", view::showList);
                tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
                assertEquals("death", view.listPanel().sheetTab());
                view.showSheet(ACCOUNT + ":2", "death", view::showList);
                // Death briefly disappears while character 2's record loads, forcing a transient fallback to Overview; that
                // is not the character's actual tab and must never be saved (checked synchronously, before anything settles).
                assertEquals("The transient Overview fallback while loading is never saved", "death", view.listPanel().sheetTab());
                tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
                assertEquals("death", view.sheet().selectedTab());
                assertEquals("death", view.listPanel().sheetTab());
            });
        }
    }

    /**
     * P3a deferred finding 7: the remembered Death tab through showSheet(key, null), the select-only path a plain open takes.
     * Death is not offered until the record loads, so the request waits for it; an alive character's Overview fallback is never
     * saved over the remembered tab.
     */
    @Test public void aRememberedDeathTabOpensThroughShowSheetWithoutAnExplicitTab() throws Exception {
        try (CharacterJournal journal = journal()) {
            journal.markDead(ACCOUNT + ":1", true);
            journal.markDead(ACCOUNT + ":2", true);
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                view.listPanel().sheetTabSelected("death"); // as a restored saved view leaves it
                view.showSheet(ACCOUNT + ":1", null, view::showList);
                assertEquals("Not forgotten while the record loads", "death", view.listPanel().sheetTab());
                tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
                assertEquals("A dead character opens on the remembered Death tab", "death", view.sheet().selectedTab());
                view.showSheet(ACCOUNT + ":2", null, view::showList);
                assertEquals("Switching dead characters keeps it", "death", view.listPanel().sheetTab());
                tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
                assertEquals("death", view.sheet().selectedTab());
                view.showSheet(ACCOUNT + ":3", null, view::showList); // alive: no Death tab
                tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
                assertEquals("An alive character falls back to Overview", "overview", view.sheet().selectedTab());
                assertEquals("…which is never saved over the remembered tab", "death", view.listPanel().sheetTab());
                view.showSheet(ACCOUNT + ":1", null, view::showList);
                tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
                assertEquals("The next dead character opens on Death again", "death", view.sheet().selectedTab());
            });
        }
    }

    /**
     * P3a deferred finding 8: Back from the sheet really moves keyboard focus. A shown, focused frame, and the actual focus owner
     * (the focus traversal policy orders only a showing window), not what focusTarget() would return. The table variant too.
     */
    @Test public void backFromTheSheetMovesRealKeyboardFocus() throws Exception {
        String saved = util.PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        JFrame frame = new JFrame("Roster Back focus - synthetic validation");
        try (CharacterJournal journal = journal()) {
            CharacterRosterView[] view = new CharacterRosterView[1];
            JList<?>[] cards = new JList<?>[1];
            JTable[] table = new JTable[1];
            AbstractButton[] back = new AbstractButton[1];
            String[] opened = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                view[0] = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                cards[0] = RosterFixtures.named(view[0].listPanel(), "character-cards", JList.class);
                table[0] = RosterFixtures.named(view[0].listPanel(), "character-roster", JTable.class);
                back[0] = RosterFixtures.named(view[0].sheet(), "character-sheet-back", AbstractButton.class);
                frame.setContentPane(view[0]);
                frame.setSize(900, 600);
                frame.setVisible(true);
                frame.toFront();
            });
            tomato.gui.activity.SnapshotTestSupport.await(frame::isFocused);
            SwingUtilities.invokeAndWait(() -> {
                cards[0].setSelectedIndex(1);
                opened[0] = ((CharacterCardModel) cards[0].getSelectedValue()).key();
                cards[0].getActionMap().get("open-character").actionPerformed(null);
            });
            tomato.gui.activity.SnapshotTestSupport.await(() -> focusOwner() == back[0]); // opening moved focus into the sheet
            SwingUtilities.invokeAndWait(back[0]::doClick);
            tomato.gui.activity.SnapshotTestSupport.await(() -> focusOwner() == cards[0]);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(view[0].showingSheet());
                assertEquals("Back focuses the card that was open", opened[0], ((CharacterCardModel) cards[0].getSelectedValue()).key());
                view[0].listPanel().views().showGallery(false, false);
                RosterFixtures.enter(view[0]);
            });
            tomato.gui.activity.SnapshotTestSupport.await(() -> focusOwner() == back[0]);
            SwingUtilities.invokeAndWait(back[0]::doClick);
            tomato.gui.activity.SnapshotTestSupport.await(() -> focusOwner() == table[0]);
            SwingUtilities.invokeAndWait(() -> assertSame("In the Table view, Back focuses the table", table[0], focusOwner()));
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
            util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, saved == null ? "" : saved);
        }
    }

    private static java.awt.Component focusOwner() { return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }

    @Test public void backFocusesTheOpenedCardInTheGalleryAndTheRowInTheTable() throws Exception {
        String saved = util.PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                JList<?> cards = RosterFixtures.named(view.listPanel(), "character-cards", JList.class);
                cards.setSelectedIndex(1);
                String key = ((CharacterCardModel) cards.getSelectedValue()).key();
                assertEquals("A card selection is the list's selection", key, view.currentKey());
                cards.getActionMap().get("open-character").actionPerformed(null);
                assertTrue(view.showingSheet()); assertEquals(key, view.sheet().key());
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse(view.showingSheet());
                assertSame("Back focuses the gallery", cards, view.listPanel().focusTarget());
                assertEquals("…on the card that was open", key, ((CharacterCardModel) cards.getSelectedValue()).key());
                view.listPanel().views().showGallery(false, false);
                RosterFixtures.enter(view);
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertSame("In the Table view, Back focuses the table", RosterFixtures.named(view.listPanel(), "character-roster", JTable.class),
                    view.listPanel().focusTarget());
            });
        } finally { util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, saved == null ? "" : saved); }
    }

    /**
     * Regression for the P3a final review: an EmptyState (no match) takes the card list's place in the tree, so the old
     * focusTarget() (always the card list while the gallery shows) pointed at a component Back could never actually focus.
     */
    @Test public void backFallsBackToSearchWhenTheGalleryShowsAnEmptyStateInsteadOfTheCards() throws Exception {
        String saved = util.PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                JTextField search = RosterFixtures.named(view.listPanel(), "character-search", JTextField.class);
                search.setText("no character matches this");
                assertSame("The card list is no longer in the tree: fall back to the search field", search, view.listPanel().focusTarget());
            });
        } finally { util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, saved == null ? "" : saved); }
    }

    @Test public void visibleRowsFollowSearchAndSortAndNotifyListeners() throws Exception {
        try (CharacterJournal journal = journal()) {
            journal.notes(ACCOUNT + ":2", "needle");
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI list = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
                AtomicInteger changes = new AtomicInteger();
                list.addRowsListener(changes::incrementAndGet);
                assertEquals(3, list.visibleRows().size());
                JTextField search = RosterFixtures.named(list, "character-search", JTextField.class);
                search.setText("needle");
                assertTrue("Search notifies", changes.get() > 0);
                assertEquals(1, list.visibleRows().size());
                assertEquals(ACCOUNT + ":2", list.visibleRows().get(0).record.key);
                search.setText("");
                JTable roster = RosterFixtures.named(list, "character-roster", JTable.class);
                int before = changes.get();
                roster.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                assertTrue("A new sort notifies", changes.get() > before);
                List<CharacterRosterQuery.Row> rows = list.visibleRows();
                assertEquals(3, rows.size());
                for (int i = 0; i < rows.size(); i++) assertEquals("Rows follow the table's order", keyAt(roster, i), rows.get(i).record.key);
            });
        }
    }
}
