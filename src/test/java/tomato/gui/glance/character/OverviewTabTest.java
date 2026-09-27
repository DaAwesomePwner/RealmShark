package tomato.gui.glance.character;

import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Overview and header on synthetic models: live boost only while playing, vault counts only when known, unknown never 0. */
public class OverviewTabTest {
    private final DisplayModeModel mode = new DisplayModeModel(key -> null, (key, value) -> {});
    /** Tests that do not open the Pet tab from the Overview's pet card. */
    private static final Runnable NO_OPEN = () -> {};
    private static String text(JComponent root, String name) { return named(root, name, JLabel.class).getText(); }
    private static ItemSlot.State slot(JComponent root, int i) { return named(root, "character-overview-slot-" + i, ItemSlot.class).state(); }

    @Test public void playingShowsBarsBoostsVaultNeedsGearAndTheExaltSummary() throws Exception {
        CharacterJournal.AccountRecord account = account(80, 15, 30, 50, 0, 0, 0, 5);
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7};
        account.vaultPotionsObservedAt = System.currentTimeMillis() - 2 * HOUR; // KitFormat.relative reads the real clock
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE; // seasonal characters show no vault count
        SheetModel model = model(regular, account, live(ACCOUNT, 7, "Sharkbait", null));
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, NO_OPEN);
            tab.apply(model);
            assertEquals("20/25", text(tab, "character-overview-value-3"));
            assertTrue(named(tab, "character-overview-bar-0", StatBar.class).maxed());
            assertFalse(named(tab, "character-overview-bar-3", StatBar.class).maxed());
            JLabel boost = named(tab, "character-overview-boost-3", JLabel.class);
            assertTrue(boost.isVisible()); assertEquals("+10", boost.getText());
            assertEquals("A vault count shows its age", "DEF needs 5 · 3 in vault (2 h ago)", text(tab, "character-overview-need-0"));
            assertEquals("WIS needs 12 · 7 in vault (2 h ago)", text(tab, "character-overview-need-2"));
            assertEquals("A fresh count reads as current", Tokens.Role.TEXT, named(tab, "character-overview-need-0", KitText.class).role());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 0));
            assertEquals("The live ability slot is empty", ItemSlot.State.EMPTY, slot(tab, 1));
            assertEquals("UT", text(tab, "character-overview-tier-0"));
            assertEquals("Without a tier the slot name shows", "Armor", text(tab, "character-overview-tier-2"));
            assertEquals("LIFE 5/5 · DEF 4/5 · ATT 3/5 · +2 more", text(tab, "character-overview-exalts"));
            assertFalse(named(tab, "character-overview-death", Card.class).isVisible());
        });
    }

    @Test public void notPlayingHidesBoostsAndKeepsUnknownUnknown() throws Exception {
        CharacterJournal.CharacterRecord record = record(); record.stats[3] = null;
        SheetModel model = model(record, account(), live(ACCOUNT, 8, "Ann", null));
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, NO_OPEN);
            tab.apply(model);
            for (int i = 0; i < 8; i++) assertFalse("No boost for a character not in game", named(tab, "character-overview-boost-" + i, JLabel.class).isVisible());
            assertEquals("—", text(tab, "character-overview-value-3"));
            assertEquals("No vault data: no vault count", "VIT needs 3", text(tab, "character-overview-need-0"));
            assertEquals("Potions unknown for 1 stat (base stat or cap not captured)", text(tab, "character-overview-needs-unknown"));
            assertEquals(ItemSlot.State.UNKNOWN, slot(tab, 2)); assertEquals(ItemSlot.State.EMPTY, slot(tab, 1));
            assertEquals("No saved exalts: unknown, not zero", "—", text(tab, "character-overview-exalts"));
        });
    }

    @Test public void aVaultCountOlderThanADayIsDimmedAsStale() throws Exception {
        CharacterJournal.AccountRecord account = account();
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7};
        account.vaultPotionsObservedAt = System.currentTimeMillis() - 30 * HOUR;
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE;
        SheetModel model = model(regular, account, null);
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, NO_OPEN);
            tab.apply(model);
            KitText need = named(tab, "character-overview-need-0", KitText.class);
            assertEquals("DEF needs 5 · 3 in vault (yesterday)", need.getText());
            assertEquals("Stale after a day: dimmed (spec §5.7)", Tokens.Role.TEXT_MUTED, need.role());
            assertTrue(need.getToolTipText(), need.getToolTipText().startsWith("Vault counted "));
        });
    }

    /**
     * Regression for the P3a final review: apply() skipped an unchanged model whenever the vault age also matched, so once
     * capture stopped "Marked dead N ago" never aged past its first paint. The death age must be part of that equality check too.
     */
    @Test public void aDeadCharactersMarkedAgeKeepsAdvancingOnAnUnchangedModel() throws Exception {
        java.lang.reflect.Field clock = KitFormat.class.getDeclaredField("clock");
        clock.setAccessible(true);
        Object previousClock = clock.get(null);
        try {
            CharacterJournal.CharacterRecord record = record(); record.dead = true;
            record.deathAnnotation = new CharacterJournal.DeathAnnotation(); record.deathAnnotation.markedAt = NOW;
            SheetModel model = model(record, account(), null); // no vault data: vaultAge() is always "", so it never masks this bug
            SwingUtilities.invokeAndWait(() -> {
                try {
                    OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, NO_OPEN);
                    clock.set(null, (java.util.function.LongSupplier) () -> NOW + 30_000); // 30 s later: "just now"
                    tab.apply(model);
                    assertEquals("Marked dead just now", text(tab, "character-overview-death-text"));
                    clock.set(null, (java.util.function.LongSupplier) () -> NOW + 90_000); // 90 s later: "1 min ago"
                    tab.apply(model); // the very same model instance: only the relative age moved
                    assertEquals("The death age keeps advancing on an unchanged model, as the vault age already did",
                        "Marked dead 1 min ago", text(tab, "character-overview-death-text"));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
        } finally { clock.set(null, previousClock); }
    }

    @Test public void statTableIsAnalystOnlyAndDeathShowsForADeadCharacter() throws Exception {
        CharacterJournal.CharacterRecord record = record(); record.dead = true; record.diedAt = NOW - 24 * HOUR;
        SheetModel model = model(record, account(), null);
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, NO_OPEN);
            tab.apply(model);
            Collapsible table = named(tab, "character-stat-table", Collapsible.class);
            assertFalse("Simple mode hides the stat table", table.isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(table.isVisible());
            JTable rows = named(tab, "character-stat-rows", JTable.class);
            assertEquals(8, rows.getRowCount()); assertEquals(5, rows.getValueAt(3, 3)); assertEquals("Maxed", rows.getValueAt(0, 3));
            assertTrue(String.valueOf(rows.getValueAt(3, 4)).startsWith("Captured total minus boost · "));
            assertTrue(named(tab, "character-overview-death", Card.class).isVisible());
            assertTrue(text(tab, "character-overview-death-text").startsWith("Marked dead "));
        });
    }

    /**
     * While playing, every rebuild stamps the build time into the identity, so the whole model is unequal each time. The
     * Analyst stat table (and the needs row) refill only when the stats changed: selection and scroll survive an identity-only
     * rebuild, and a real stat change still shows at once.
     */
    @Test public void theAnalystStatTableIsNotRefilledWhenOnlyTheIdentityChanged() throws Exception {
        SheetModel first = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null));
        SheetModel later = seenAgain(first, first.identity().lastSeen() + 1_000);
        CharacterJournal.CharacterRecord raised = record(); raised.stats[3] = 22; // DEF 20 -> 22: two potions fewer to max
        SheetModel changed = model(raised, account(), null);
        assertNotEquals("The two rebuilds are unequal models", first, later);
        assertEquals(first.stats(), later.stats());
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, () -> NOW, NO_OPEN);
            mode.set(DisplayModeModel.Mode.ANALYST);
            tab.apply(first);
            JTable rows = named(tab, "character-stat-rows", JTable.class);
            rows.setRowSelectionInterval(3, 3);
            java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
            rows.getModel().addTableModelListener(e -> events.incrementAndGet());
            KitText need = named(tab, "character-overview-need-0", KitText.class);
            tab.apply(later);
            assertEquals("An identity-only rebuild fires no row events", 0, events.get());
            assertEquals("…so the selected row stays selected", 3, rows.getSelectedRow());
            assertSame("…and the needs row is not rebuilt either", need, named(tab, "character-overview-need-0", KitText.class));
            tab.apply(changed);
            assertTrue("A real stat change refills the table at once", events.get() > 0);
            assertEquals(3, rows.getValueAt(3, 3));
            assertEquals("DEF needs 3", text(tab, "character-overview-need-0"));
        });
    }

    /** Staleness is judged by the sheet's clock (SheetContext.clock), so it is testable and consistent; the system clock is irrelevant. */
    @Test public void vaultStalenessUsesTheSheetClock() throws Exception {
        CharacterJournal.AccountRecord account = account();
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7};
        account.vaultPotionsObservedAt = NOW; // years before the system clock
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE;
        SheetModel model = model(regular, account, null);
        long[] clock = {NOW + HOUR};
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, () -> clock[0], NO_OPEN);
            tab.apply(model);
            KitText need = named(tab, "character-overview-need-0", KitText.class);
            assertEquals("An hour old by the sheet's clock: current, whatever the system clock says", Tokens.Role.TEXT, need.role());
            assertFalse(need.getToolTipText(), need.getToolTipText().contains("open the vault"));
            clock[0] = NOW + 25 * HOUR;
            tab.apply(model); // the very same model: only the sheet's clock moved past a day
            need = named(tab, "character-overview-need-0", KitText.class);
            assertEquals("25 h old by the sheet's clock: dimmed as stale", Tokens.Role.TEXT_MUTED, need.role());
            assertTrue(need.getToolTipText(), need.getToolTipText().endsWith("; open the vault with capture on to update it"));
        });
    }

    @Test public void headerShowsIdentityPlayingAndSeasonalAndHidesAnUnknownMaxedCount() throws Exception {
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null));
        CharacterJournal.CharacterRecord partial = record(); partial.stats[0] = null;
        SheetModel away = model(partial, account(), null);
        CharacterJournal.CharacterRecord played = record(); played.lastObservedAlive = System.currentTimeMillis() - 3 * HOUR;
        SheetModel wasPlayed = model(played, account(), null);
        SwingUtilities.invokeAndWait(() -> {
            SheetHeader header = new SheetHeader();
            header.apply(playing.identity());
            assertEquals("Sharkbait", text(header, "character-sheet-name"));
            assertTrue(text(header, "character-sheet-meta").startsWith("Wizard · Level 20 · Fame "));
            Chip maxed = named(header, "character-sheet-maxed", Chip.class);
            assertTrue(maxed.isVisible()); assertEquals("5/8 maxed", maxed.getText()); assertEquals(Tokens.Tone.WARN, maxed.tone());
            assertTrue(named(header, "character-sheet-playing", Chip.class).isVisible());
            assertTrue(named(header, "character-sheet-seasonal", Chip.class).isVisible());
            assertFalse(named(header, "character-sheet-seen", Chip.class).isVisible());
            header.apply(away.identity());
            assertFalse("Unknown is never shown as 0/8", named(header, "character-sheet-maxed", Chip.class).isVisible());
            assertFalse(named(header, "character-sheet-playing", Chip.class).isVisible());
            Chip seen = named(header, "character-sheet-seen", Chip.class);
            assertTrue("Never in game: seen from the character list", seen.getText().startsWith("Seen "));
            assertEquals("The time is the description, never the name", seen.getText(), header.getAccessibleContext().getAccessibleDescription());
            header.apply(wasPlayed.identity());
            assertEquals("The last time in game, as the gallery's Last played sort", "Played 3 h ago", seen.getText());
        });
    }

    @Test public void clearingTheIdentityAlsoClearsTheAccessibleNameAndDescription() throws Exception {
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null));
        SwingUtilities.invokeAndWait(() -> {
            SheetHeader header = new SheetHeader();
            header.apply(playing.identity());
            assertNotNull(header.getAccessibleContext().getAccessibleName());
            header.apply(null); // the next character's read has not arrived yet
            assertNull("Sharkbait must not still be announced while the next character loads",
                header.getAccessibleContext().getAccessibleName());
            assertNull(header.getAccessibleContext().getAccessibleDescription());
        });
    }
    /** The Overview's pet card: the pet, its rarity and its three abilities; "No pet" and unknown ("—" with a reason) kept apart. */
    @Test public void thePetCardShowsKnownNoneAndUnknown() throws Exception {
        CharacterJournal.CharacterRecord withPet = record(); withPet.pet = pet(NOW);
        CharacterJournal.CharacterRecord none = record(); none.pet = noPet(NOW);
        SheetModel known = model(withPet, account(), null), noPet = model(none, account(), null), unknown = model(record(), account(), null);
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, NO_OPEN);
            tab.apply(known);
            Card card = named(tab, "character-overview-pet", Card.class);
            assertTrue(card.isVisible()); assertEquals("Pet", card.header().title());
            assertEquals("Sample pet", text(tab, "character-overview-pet-name"));
            Chip rarity = named(tab, "character-overview-pet-rarity", Chip.class);
            assertTrue(rarity.isVisible()); assertEquals("Rare", rarity.getText()); assertEquals(Tokens.Tone.NEUTRAL, rarity.tone());
            JTextArea abilities = named(tab, "character-overview-pet-abilities", JTextArea.class);
            assertTrue(abilities.isVisible());
            assertEquals("Levels of the three slots; the third unlocks at max level 90", "Heal 45 · Magic heal 30 · Electric locked", abilities.getText());
            assertTrue("Skin 0: the placeholder sprite", Sprites.isPlaceholder(named(tab, "character-overview-pet-sprite", JLabel.class).getIcon()));

            tab.apply(noPet);
            assertEquals("No pet", text(tab, "character-overview-pet-name"));
            assertFalse(rarity.isVisible()); assertFalse(abilities.isVisible());
            assertFalse(named(tab, "character-overview-pet-sprite", JLabel.class).isVisible());

            tab.apply(unknown);
            JLabel name = named(tab, "character-overview-pet-name", JLabel.class);
            assertEquals("Unknown is never \"No pet\"", DisplayFormat.UNAVAILABLE, name.getText());
            assertEquals("Pet details arrive when capture reads your character list in the Pet Yard or the Daily Quest Room", name.getToolTipText());
            assertFalse(rarity.isVisible()); assertFalse(abilities.isVisible());

            CharacterJournal.CharacterRecord partial = record(); partial.pet = pet(NOW);
            partial.pet.rarity = null; partial.pet.abilityType = new int[]{407, -1, 406}; partial.pet.abilityLevel = new int[]{-1, 3, 1};
            partial.pet.maxAbilityPower = 90;
            tab.apply(model(partial, account(), null));
            assertFalse("An unknown rarity hides the chip", rarity.isVisible());
            assertEquals("Unknown levels and types are dashes, never 0", "Heal — · — · Electric 1", abilities.getText());
        });
    }

    /** Opening the card (click, Enter or Space) opens the sheet's Pet tab: CharacterSheet passes openTab("pet"). */
    @Test public void thePetCardOpensThePetTab() throws Exception {
        CharacterJournal.CharacterRecord withPet = record(); withPet.pet = pet(NOW);
        SheetModel known = model(withPet, account(), null);
        SwingUtilities.invokeAndWait(() -> {
            int[] opened = {0};
            OverviewTab tab = new OverviewTab(mode, System::currentTimeMillis, () -> opened[0]++);
            tab.apply(known);
            Card card = named(tab, "character-overview-pet", Card.class);
            assertEquals("Open pet", card.getAccessibleContext().getAccessibleName());
            assertTrue("Keyboard reachable", card.isFocusable());
            card.getActionMap().get("open-card").actionPerformed(null);
            assertEquals(1, opened[0]);
            JLabel name = named(tab, "character-overview-pet-name", JLabel.class);
            for (java.awt.event.MouseListener listener : name.getMouseListeners())
                listener.mouseClicked(new java.awt.event.MouseEvent(name, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals("A click on the card's text opens it too", 2, opened[0]);
            assertNull("The other cards stay plain", named(tab, "character-overview-gear", Card.class).getActionMap().get("open-card"));
        });
    }
}
