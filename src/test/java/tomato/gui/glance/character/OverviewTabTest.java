package tomato.gui.glance.character;

import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Overview and header on synthetic models: live boost only while playing, vault counts only when known, unknown never 0. */
public class OverviewTabTest {
    private final DisplayModeModel mode = new DisplayModeModel(key -> null, (key, value) -> {});
    private static String text(JComponent root, String name) { return named(root, name, JLabel.class).getText(); }
    private static ItemSlot.State slot(JComponent root, int i) { return named(root, "character-overview-slot-" + i, ItemSlot.class).state(); }

    @Test public void playingShowsBarsBoostsVaultNeedsGearAndTheExaltSummary() throws Exception {
        CharacterJournal.AccountRecord account = account(80, 15, 30, 50, 0, 0, 0, 5);
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7};
        account.vaultPotionsObservedAt = System.currentTimeMillis() - 2 * HOUR; // KitFormat.relative reads the real clock
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE; // seasonal characters show no vault count
        SheetModel model = model(regular, account, live(ACCOUNT, 7, "Sharkbait", null));
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, id -> id == 2_001 ? "UT" : "");
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
            OverviewTab tab = new OverviewTab(mode, id -> "");
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
            OverviewTab tab = new OverviewTab(mode, id -> "");
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
                    OverviewTab tab = new OverviewTab(mode, id -> "");
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
            OverviewTab tab = new OverviewTab(mode, id -> "");
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
}
