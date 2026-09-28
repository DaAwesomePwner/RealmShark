package tomato.gui.character;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.TileList;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class PetLayoutEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence();
    @Rule public ErrorCollector layouts = new ErrorCollector();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    /** The feeding calculator's drawer remembers whether it is open; each test sets it and restores the user's value. */
    private static final String FEEDING = Collapsible.PREFIX + "pets-feeding";

    @Test public void knownAndMissingFeedingInputsRemainReadableWithManualScenarioAtCompactAndLargeFonts() throws Exception {
        String feeding = PropertiesManager.getProperty(FEEDING);
        // The feeding calculator is in a drawer that starts collapsed: open it first. A saved open state opens it without the
        // opening motion, so nothing is measured mid-animation.
        PropertiesManager.setProperties(FEEDING, "true");
        CharacterPetsGUI[] panel = new CharacterPetsGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new CharacterPetsGUI(null);
                assertTrue(named(panel[0], "pets-feeding", Collapsible.class).expanded());
                CharacterPetsGUI.addPet(pet(101, stat(StatType.PET_INSTANCE_ID_STAT, 10), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                    stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 100), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 407)));
                CharacterPetsGUI.addPet(pet(102, stat(StatType.PET_INSTANCE_ID_STAT, 20), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                    stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 408)));
                named(panel[0], "pet-feed-power", JTextField.class).postActionEvent();
            });
            await(() -> find(panel[0], JComboBox.class,
                c -> "Feed item from local assets".equals(c.getAccessibleContext().getAccessibleName())).getItemCount() > 0);
            for (int font : new int[]{13, 24}) for (int width : new int[]{1080, 680}) {
                String name = "pets-" + width + "-" + font;
                SwingUtilities.invokeAndWait(() -> evidence.show(panel[0], "Pet feeding evidence", width, width == 680 ? 520 : 780, font));
                evidence.settle();
                // The calculator computes for the selected card: the known estimate is pet 10's, the missing points pet 20's.
                SwingUtilities.invokeAndWait(() -> { evidence.capture(name); select(panel[0], "pet:10"); });
                evidence.settle();
                SwingUtilities.invokeAndWait(() -> {
                    JTextArea known = find(panel[0], JTextArea.class, a -> a.getText().contains("Captured points: 100"));
                    // Spec §1: estimates show ≈ (captured inputs and unknowns never do).
                    assertTrue(known.getText().contains("Items to max: ≈ 4")); assertTrue(known.getText().contains("Fame to max: ≈ 60"));
                    layouts.checkSucceeds(() -> { completeText(known); return null; });
                    evidence.capture(name + "-known-estimate");
                    select(panel[0], "pet:20");
                });
                evidence.settle();
                SwingUtilities.invokeAndWait(() -> {
                    JTextArea unknown = find(panel[0], JTextArea.class, a -> a.getText().contains("Ability points not captured"));
                    assertTrue(unknown.getText().contains("Items to max: Not captured / unavailable"));
                    assertFalse(unknown.getText().contains("Fully fed"));
                    assertFalse("Unknown is never an estimate", unknown.getText().contains("≈"));
                    layouts.checkSucceeds(() -> { completeText(unknown); return null; });
                    evidence.capture(name + "-unknown-estimate");
                    layouts.checkSucceeds(() -> {
                        completeText(named(panel[0], "pet-capture-context", JTextArea.class));
                        reachable(named(panel[0], "pet-feed-power", JTextField.class));
                        completeButton(button(panel[0], "Use item ID"));
                        completeButton(button(panel[0], "Recalculate feeding costs"));
                        return null;
                    });
                    evidence.capture(name + "-scenario");
                });
            }
            SwingUtilities.invokeAndWait(() -> {
                JTextField feed = named(panel[0], "pet-feed-power", JTextField.class); feed.setText("1000"); feed.postActionEvent();
                select(panel[0], "pet:10");
                JTextArea known = find(panel[0], JTextArea.class, a -> a.getText().contains("Captured points: 100"));
                assertTrue(known.getText().contains("Items to max: ≈ 2")); assertTrue(known.getText().contains("Fame to max: ≈ 30")); // spec §1: estimates show ≈
                select(panel[0], "pet:20");
                assertTrue(find(panel[0], JTextArea.class, a -> a.getText().contains("Ability points not captured"))
                    .getText().contains("Items to max: Not captured / unavailable"));
            });
        } finally {
            SwingUtilities.invokeAndWait(CharacterPetsGUI::clearPets);
            PropertiesManager.setProperties(FEEDING, feeding == null ? "" : feeding);
        }
    }

    /**
     * A live Pets tab: the current account's equipped pets from the journal (one card for a pet two characters carry) and the Pet
     * Yard pets seen this visit, with another account's pet left out; the journal is read only off the EDT. Leaving the yard
     * leaves the equipped pets.
     */
    @Test public void theGalleryShowsTheAccountsEquippedPetsAndThePetYardAtCompactAndLargeFonts() throws Exception {
        String feeding = PropertiesManager.getProperty(FEEDING);
        PropertiesManager.setProperties(FEEDING, "");
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
        List<String> readers = new CopyOnWriteArrayList<>();
        TomatoData data = new TomatoData() {
            @Override public CharacterJournal characterJournal() {
                readers.add(SwingUtilities.isEventDispatchThread() ? "EDT" : Thread.currentThread().getName());
                return journal;
            }
        };
        long at = System.currentTimeMillis();
        int[] rex = {1000, 45, PetFixtures.HEAL, 200, 30, PetFixtures.MAGIC_HEAL, 0, 1, PetFixtures.ELECTRIC};
        journal.mergeRoster(PetFixtures.ACCOUNT, List.of(
            PetFixtures.listed(7, PetFixtures.WIZARD, "Wizard", at, 9001, "Rex", PetFixtures.HOUND, 2, 70, rex),
            PetFixtures.listed(8, PetFixtures.KNIGHT, "Knight", at, 9001, "Rex", PetFixtures.HOUND, 2, 70, rex)));
        journal.mergeRoster(PetFixtures.OTHER, List.of(
            PetFixtures.listed(1, PetFixtures.PRIEST, "Priest", at - 60_000, 4242, "Theirs", PetFixtures.CAT, 4, 100, rex)));
        data.progression().reset(PetFixtures.ACCOUNT, "Pet Yard visit");
        PetFixtures.publish(data.progression(), 5, at, "Pet Yard capture", PetFixtures.stat(StatType.PET_INSTANCE_ID_STAT, 555),
            PetFixtures.text(StatType.PET_NAME_STAT, "Tom"), PetFixtures.stat(StatType.PET_TYPE_STAT, PetFixtures.CAT),
            PetFixtures.stat(StatType.PET_RARITY_STAT, 1), PetFixtures.stat(StatType.PET_MAX_ABILITY_POWER_STAT, 50),
            PetFixtures.stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, PetFixtures.HEAL), PetFixtures.stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 20));
        CharacterPetsGUI[] panel = new CharacterPetsGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> panel[0] = new CharacterPetsGUI(data));
            await(() -> cards(panel[0]).items().size() == 2);
            SwingUtilities.invokeAndWait(() -> {
                List<PetGalleryModel.PetCard> items = cards(panel[0]).items();
                assertEquals("Yard pets first, then equipped; one card per instance id; another account's pet left out",
                    List.of("pet:555", "pet:9001"), keys(items));
                assertEquals(List.of("In the Pet Yard now"), PetCardRenderer.lines(items.get(0)).footer());
                assertEquals(List.of("Equipped by Wizard #7, Knight #8"), PetCardRenderer.lines(items.get(1)).footer());
                assertFalse(named(panel[0], "pet-empty", EmptyState.class).isVisible());
                assertFalse("The feeding calculator starts collapsed", named(panel[0], "pets-feeding", Collapsible.class).expanded());
                String context = named(panel[0], "pet-capture-context", JTextArea.class).getText();
                assertTrue(context, context.startsWith("Account · " + PetFixtures.ACCOUNT.substring(0, 6) + " · Capture stopped"));
                assertTrue(context, context.contains("1 in the Pet Yard now"));
                assertFalse("The journal is never read on the EDT: " + readers, readers.contains("EDT"));
                assertTrue("…but on the gallery's own thread: " + readers, readers.contains("character-pets"));
            });
            for (int font : new int[]{13, 24}) for (int width : new int[]{1080, 680}) {
                String name = "pets-gallery-" + width + "-" + font;
                SwingUtilities.invokeAndWait(() -> evidence.show(panel[0], "Pets gallery evidence", width, width == 680 ? 520 : 780, font));
                evidence.settle();
                SwingUtilities.invokeAndWait(() -> {
                    evidence.capture(name);
                    TileList<?> cards = cards(panel[0]);
                    layouts.checkSucceeds(() -> {
                        for (int i = 0; i < cards.getModel().getSize(); i++) reachable(cards, cards.getCellBounds(i, i));
                        completeText(named(panel[0], "pet-capture-context", JTextArea.class));
                        completeButton(named(panel[0], "collapsible-pets-feeding", AbstractButton.class));
                        return null;
                    });
                });
            }
            data.progression().reset(PetFixtures.ACCOUNT, "Left the Pet Yard");
            await(() -> cards(panel[0]).items().size() == 1);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("After the yard only the equipped pets remain", List.of("pet:9001"), keys(cards(panel[0]).items()));
                assertFalse(cards(panel[0]).items().get(0).inYard());
            });
        } finally {
            PropertiesManager.setProperties(FEEDING, feeding == null ? "" : feeding);
            journal.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static TileList<PetGalleryModel.PetCard> cards(JComponent panel) { return named(panel, "pet-cards", TileList.class); }
    private static List<String> keys(List<PetGalleryModel.PetCard> cards) {
        List<String> keys = new ArrayList<>(); for (PetGalleryModel.PetCard card : cards) keys.add(card.key()); return keys;
    }
    /** Selects a pet card as the user does; the feeding calculator follows the selection. EDT. */
    private static void select(JComponent panel, String key) {
        cards(panel).selectKey(key, true);
        assertEquals(key, cards(panel).getSelectedValue().key());
    }
    private static ObjectData pet(int objectId, StatData... stats) {
        ObjectData object = new ObjectData(); object.status = new ObjectStatusData();
        object.status.objectId = objectId; object.status.stats = stats; return object;
    }
    private static StatData stat(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; return stat;
    }
}
