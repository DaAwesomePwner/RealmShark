package tomato.gui.glance.character;

import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.PetFeeding;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Sheet › Pet on synthetic pets: names, bars and locked slots; No pet and unknown kept apart; the local feeding estimate. */
public class PetTabTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long MINUTE = 60_000L;

    private static String text(JComponent root, String name) { return named(root, name, JLabel.class).getText(); }
    private static boolean shows(JComponent root, String name) { return named(root, name, JComponent.class).isVisible(); }
    /** Estimates are wrapping text areas (long lines must not be cut at 680 px or font 18). */
    private static String area(JComponent root, String name) { return named(root, name, JTextArea.class).getText(); }

    @Test public void aKnownPetShowsNamesBarsLockedSlotsAndWhenItWasObserved() throws Exception {
        // KitFormat.relative reads the real clock.
        CharacterJournal.PetRecord record = pet(System.currentTimeMillis() - 2 * HOUR);
        PetSummary pet = PetSummary.of(record, petNames(temp.getRoot().toPath()));
        SwingUtilities.invokeAndWait(() -> {
            PetTab tab = new PetTab();
            assertEquals("character-pet", tab.getName());
            tab.apply(pet);
            assertTrue(shows(tab, "character-pet-content"));
            assertFalse(shows(tab, "character-pet-none")); assertFalse(shows(tab, "character-pet-empty"));
            assertEquals("Sample pet", text(tab, "character-pet-name"));
            Chip rarity = named(tab, "character-pet-rarity", Chip.class);
            assertTrue(rarity.isVisible()); assertEquals("Rare", rarity.getText());
            assertEquals("Game meaning never signals status: the rarity chip is neutral", Tokens.Tone.NEUTRAL, rarity.tone());
            assertEquals("Family: Canine", text(tab, "character-pet-family"));
            assertEquals("Max level 70", text(tab, "character-pet-max"));
            assertTrue("Skin 0 has no sprite: the placeholder", Sprites.isPlaceholder(named(tab, "character-pet-sprite", JLabel.class).getIcon()));
            String[] names = {"Heal", "Magic heal", "Electric"};
            for (int i = 0; i < 3; i++) assertEquals(names[i], text(tab, "character-pet-ability-name-" + i));
            assertEquals("Level 45 / 70", text(tab, "character-pet-ability-level-0"));
            assertEquals("Level 30 / 70", text(tab, "character-pet-ability-level-1"));
            assertEquals("The third slot unlocks at max level 90", "Locked", text(tab, "character-pet-ability-level-2"));
            StatBar heal = named(tab, "character-pet-ability-bar-0", StatBar.class);
            assertEquals("StatBar.set(level, maxLevel)", "45 of 70", heal.getToolTipText());
            assertFalse(heal.maxed());
            assertEquals("30 of 70", named(tab, "character-pet-ability-bar-1", StatBar.class).getToolTipText());
            StatBar locked = named(tab, "character-pet-ability-bar-2", StatBar.class);
            assertTrue("A locked slot's bar says so, never a level", locked.getToolTipText().startsWith("Locked"));
            assertEquals("Observed 2 h ago · Character list", text(tab, "character-pet-observed"));
        });
    }

    @Test public void unknownValuesShowADashNeverZero() throws Exception {
        CharacterJournal.PetRecord partial = pet(0);
        partial.name = null; partial.maxAbilityPower = null; partial.abilityType = new int[]{407, -1, -1}; partial.abilityLevel = new int[]{-1, 3, -1};
        partial.source = null;
        PetSummary pet = PetSummary.of(partial, PetDefinitions.unavailable());
        // The family's tooltip is the pet names' status (PetDefinitions.current(), which never blocks): pinned here.
        try (AutoCloseable pinned = PetDefinitions.install(PetDefinitions.unavailable())) {
            unknownValues(pet);
        }
    }

    private static void unknownValues(PetSummary pet) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PetTab tab = new PetTab();
            tab.apply(pet);
            assertEquals("No name: the title falls back", "Pet", text(tab, "character-pet-name"));
            assertEquals("Max level —", text(tab, "character-pet-max"));
            JLabel family = named(tab, "character-pet-family", JLabel.class);
            assertEquals("Family: —", family.getText());
            assertEquals("The reason is the pet names' status", PetDefinitions.unavailable().status, family.getToolTipText());
            assertEquals("Heal", text(tab, "character-pet-ability-name-0"));
            assertEquals("Level —", text(tab, "character-pet-ability-level-0"));
            assertEquals("An unknown ability type is a dash", DisplayFormat.UNAVAILABLE, text(tab, "character-pet-ability-name-1"));
            assertEquals("A known level without a known max", "Level 3 / —", text(tab, "character-pet-ability-level-1"));
            assertEquals("Not captured", named(tab, "character-pet-ability-bar-0", StatBar.class).getToolTipText());
            assertEquals("An unknown max level locks nothing", "Level —", text(tab, "character-pet-ability-level-2"));
            assertEquals("Observed at an unknown time", text(tab, "character-pet-observed"));
        });
    }

    @Test public void noPetAndAnUnknownPetEachHaveTheirOwnEmptyState() throws Exception {
        PetSummary none = PetSummary.of(noPet(NOW), null);
        SwingUtilities.invokeAndWait(() -> {
            PetTab tab = new PetTab();
            assertFalse("Loading shows nothing", shows(tab, "character-pet-content"));
            assertFalse(shows(tab, "character-pet-none")); assertFalse(shows(tab, "character-pet-empty"));
            tab.apply(none);
            EmptyState noPet = named(tab, "character-pet-none", EmptyState.class);
            assertTrue(noPet.isVisible()); assertFalse(shows(tab, "character-pet-content")); assertFalse(shows(tab, "character-pet-empty"));
            assertEquals("No pet", noPet.getAccessibleContext().getAccessibleName());
            assertEquals("This character had no pet equipped when capture last read the character list.",
                noPet.getAccessibleContext().getAccessibleDescription());
            tab.apply(PetSummary.UNKNOWN);
            EmptyState unknown = named(tab, "character-pet-empty", EmptyState.class);
            assertTrue(unknown.isVisible()); assertFalse(shows(tab, "character-pet-content")); assertFalse(shows(tab, "character-pet-none"));
            assertEquals("Pet not captured yet", unknown.getAccessibleContext().getAccessibleName());
            // TomatoData requests the character list only on entering the Pet Yard or the Daily Quest Room (webRequest).
            assertEquals("Pet details arrive when capture reads your character list in the Pet Yard or the Daily Quest Room.",
                unknown.getAccessibleContext().getAccessibleDescription());
            tab.apply(null);
            assertFalse("null (loading) shows neither empty state", shows(tab, "character-pet-empty"));
            assertFalse(shows(tab, "character-pet-none")); assertFalse(shows(tab, "character-pet-content"));
        });
    }

    @Test public void theFeedingEstimateMatchesPetFeedingAndRejectsAnInvalidFeedPower() throws Exception {
        CharacterJournal.PetRecord record = pet(NOW);
        PetSummary pet = PetSummary.of(record, null);
        SwingUtilities.invokeAndWait(() -> {
            PetTab tab = new PetTab();
            tab.apply(pet);
            Collapsible drawer = named(tab, "character-pet-feeding", Collapsible.class);
            assertEquals("Feeding estimate", drawer.toggle().getText());
            assertEquals("collapsible-character-pet-feeding", drawer.toggle().getName());
            JTextField power = named(tab, "character-pet-feed-power", JTextField.class);
            assertEquals("500", power.getText());
            for (int i = 0; i < 2; i++) assertEquals(expected(i, record, 500), area(tab, "character-pet-estimate-" + i));
            assertEquals("Next level ≈ 2 items · ≈ 350 fame · To max ≈ 87 items · ≈ " + DisplayFormat.formatInteger(15_225) + " fame",
                area(tab, "character-pet-estimate-0"));
            assertEquals("A locked slot has no estimate", "Locked: no estimate", area(tab, "character-pet-estimate-2"));
            assertFalse(shows(tab, "character-pet-feed-error"));
            assertTrue(named(tab, "character-pet-feeding-caption", JTextArea.class).getText().contains("change nothing in game"));

            power.setText("1000");
            for (int i = 0; i < 2; i++) assertEquals(expected(i, record, 1000), area(tab, "character-pet-estimate-" + i));
            for (String invalid : new String[]{"0", "abc", "-5", ""}) {
                power.setText(invalid);
                KitText error = named(tab, "character-pet-feed-error", KitText.class);
                assertTrue("Rejected: '" + invalid + "'", error.isVisible());
                assertEquals("Enter a positive whole number (1–2,147,483,647).", error.getText());
                assertEquals("The helper line is rose", Tokens.Role.BAD, error.role());
                for (int i = 0; i < 3; i++) assertFalse("No estimate for invalid input", shows(tab, "character-pet-estimate-" + i));
            }
            power.setText("500");
            assertFalse(shows(tab, "character-pet-feed-error"));
            assertEquals(expected(0, record, 500), area(tab, "character-pet-estimate-0"));
        });
    }

    @Test public void theEstimateNamesWhatIsMissing() throws Exception {
        CharacterJournal.PetRecord record = pet(NOW);
        record.abilityPoints = new int[]{-1, 2_100, 0};
        CharacterJournal.PetRecord fed = pet(NOW); fed.abilityLevel = new int[]{70, 30, 1};
        SwingUtilities.invokeAndWait(() -> {
            PetTab tab = new PetTab();
            tab.apply(PetSummary.of(record, null));
            assertEquals("No estimate: Ability points not captured", area(tab, "character-pet-estimate-0"));
            tab.apply(PetSummary.of(fed, null));
            assertEquals("At max level: nothing to feed", area(tab, "character-pet-estimate-0"));
        });
    }

    /** The presenter re-applies the same summary once a second (SheetPresenter.times): only the relative age moves. */
    @Test public void theObservedAgeAdvancesOnAnUnchangedPet() throws Exception {
        java.lang.reflect.Field clock = KitFormat.class.getDeclaredField("clock");
        clock.setAccessible(true);
        Object previousClock = clock.get(null);
        PetSummary pet = PetSummary.of(pet(NOW), null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    PetTab tab = new PetTab();
                    clock.set(null, (java.util.function.LongSupplier) () -> NOW + 5 * MINUTE);
                    tab.apply(pet);
                    assertEquals("Observed 5 min ago · Character list", text(tab, "character-pet-observed"));
                    clock.set(null, (java.util.function.LongSupplier) () -> NOW + 65 * MINUTE);
                    tab.apply(pet); // the very same summary instance
                    assertEquals("Observed 1 h ago · Character list", text(tab, "character-pet-observed"));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
        } finally { clock.set(null, previousClock); }
    }

    /** The text the tab must show for slot {@code i}: straight from PetFeeding.estimate, with "≈" (it is an estimate). */
    private static String expected(int i, CharacterJournal.PetRecord pet, int power) {
        PetFeeding.Estimate e = PetFeeding.estimate(i, pet.abilityLevel[i], pet.abilityPoints[i], pet.maxAbilityPower, power);
        assertNotNull(e.reason, e.nextItems);
        return "Next level ≈ " + items(e.nextItems) + " · ≈ " + DisplayFormat.formatInteger(e.nextFame) + " fame · To max ≈ "
            + items(e.maxItems) + " · ≈ " + DisplayFormat.formatInteger(e.maxFame) + " fame";
    }
    private static String items(long n) { return DisplayFormat.formatInteger(n) + (n == 1 ? " item" : " items"); }
}
