package tomato.gui.character;

import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.enums.StatType;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class PetLayoutEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence();
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void knownAndMissingFeedingInputsRemainReadableWithManualScenarioAtCompactAndLargeFonts() throws Exception {
        CharacterPetsGUI[] panel = new CharacterPetsGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new CharacterPetsGUI(null);
            CharacterPetsGUI.addPet(pet(101, stat(StatType.PET_INSTANCE_ID_STAT, 10), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 100), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 407)));
            CharacterPetsGUI.addPet(pet(102, stat(StatType.PET_INSTANCE_ID_STAT, 20), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30),
                stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1), stat(StatType.PET_FIRST_ABILITY_TYPE_STAT, 408)));
            named(panel[0], "pet-feed-power", JTextField.class).postActionEvent();
        });
        try {
            await(() -> find(panel[0], JComboBox.class,
                c -> "Feed item from local assets".equals(c.getAccessibleContext().getAccessibleName())).getItemCount() > 0);
            for (int font : new int[]{13, 24}) for (int width : new int[]{1080, 680}) {
                SwingUtilities.invokeAndWait(() -> evidence.show(panel[0], "Pet feeding evidence", width, width == 680 ? 520 : 780, font));
                evidence.settle();
                SwingUtilities.invokeAndWait(() -> {
                    String name = "pets-" + width + "-" + font; evidence.capture(name);
                    JTextArea known = find(panel[0], JTextArea.class, a -> a.getText().contains("Captured points: 100"));
                    JTextArea unknown = find(panel[0], JTextArea.class, a -> a.getText().contains("Ability points not captured"));
                    assertTrue(known.getText().contains("Items to max: 4")); assertTrue(known.getText().contains("Fame to max: 60"));
                    assertTrue(unknown.getText().contains("Items to max: Not captured / unavailable"));
                    assertFalse(unknown.getText().contains("Fully fed"));
                    layouts.checkSucceeds(() -> { completeText(known); return null; });
                    evidence.capture(name + "-known-estimate");
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
                JTextArea known = find(panel[0], JTextArea.class, a -> a.getText().contains("Captured points: 100"));
                assertTrue(known.getText().contains("Items to max: 2")); assertTrue(known.getText().contains("Fame to max: 30"));
                assertTrue(find(panel[0], JTextArea.class, a -> a.getText().contains("Ability points not captured"))
                    .getText().contains("Items to max: Not captured / unavailable"));
            });
        } finally { SwingUtilities.invokeAndWait(CharacterPetsGUI::clearPets); }
    }

    private static ObjectData pet(int objectId, StatData... stats) {
        ObjectData object = new ObjectData(); object.status = new ObjectStatusData();
        object.status.objectId = objectId; object.status.stats = stats; return object;
    }
    private static StatData stat(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; return stat;
    }
}
