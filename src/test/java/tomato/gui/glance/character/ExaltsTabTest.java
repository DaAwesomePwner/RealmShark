package tomato.gui.glance.character;

import java.util.Collections;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.PipMeter;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Class-scoped exalts: tier pips, next-tier math at every threshold, this class's saved live bonus, and where to earn each stat. */
public class ExaltsTabTest {
    private static String text(JComponent root, String name) { return named(root, name, JLabel.class).getText(); }

    @Test public void pipsCompletionsAndNextTierAtEveryThreshold() throws Exception {
        SheetModel.Exalts climbing = model(record(), account(0, 4, 5, 14, 15, 30, 50, 74), null).exalts();
        assertEquals(List.of(0, 0, 1, 1, 2, 3, 4, 4), climbing.tiers());
        assertEquals(List.of(5, 1, 10, 1, 15, 20, 25, 1), climbing.toNext());
        assertEquals(192, climbing.total());
        assertEquals(0, climbing.lowest());
        SheetModel.Exalts maxed = model(record(), account(75, 80, 75, 75, 75, 75, 75, 75), null).exalts();
        assertEquals(Collections.nCopies(8, 5), maxed.tiers());
        assertEquals("75 is the last tier", Collections.nCopies(8, 0), maxed.toNext());
        assertEquals(5, maxed.lowest());
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(climbing);
            assertEquals("Total completions 192 · Lowest tier 0/5", text(tab, "character-exalts-totals"));
            for (int i = 0; i < 8; i++) {
                assertEquals(climbing.tiers().get(i).intValue(), named(tab, "character-exalt-pips-" + i, PipMeter.class).filled());
                assertEquals(climbing.toNext().get(i) + " to next tier", text(tab, "character-exalt-next-" + i));
            }
            assertEquals("0 completions", text(tab, "character-exalt-count-0"));
            assertEquals("74 completions", text(tab, "character-exalt-count-7"));
            tab.apply(maxed);
            assertEquals("Maxed", text(tab, "character-exalt-next-0"));
            assertEquals(5, named(tab, "character-exalt-pips-1", PipMeter.class).filled());
            assertEquals("80 completions", text(tab, "character-exalt-count-1"));
        });
    }

    @Test public void liveBonusComesOnlyFromThisClassesSavedBonus() throws Exception {
        CharacterJournal.AccountRecord account = account(5, 5, 5, 5, 5, 5, 5, 5);
        account.exaltSeenByClass.put(WIZARD, System.currentTimeMillis() - HOUR); // KitFormat.relative reads the real clock
        account.liveExaltBonus.put(WIZARD, bonus(NOW - 3 * HOUR, 1, 2, 3, 4, 5, 6, 7, 8));
        account.liveExaltBonus.put(PRIEST, bonus(NOW, 9, 9, 9, 9, 9, 9, 9, 9));
        SheetModel.Exalts wizard = model(record(), account, live(ACCOUNT, 7, "Sharkbait", null)).exalts();
        assertEquals("This class's saved bonus, not the snapshot's or another class's", List.of(1, 2, 3, 4, 5, 6, 7, 8), wizard.liveBonus());
        assertEquals(NOW - 3 * HOUR, wizard.liveObservedAt());
        CharacterJournal.AccountRecord priestOnly = account(5, 5, 5, 5, 5, 5, 5, 5);
        priestOnly.liveExaltBonus.put(PRIEST, bonus(NOW, 9, 9, 9, 9, 9, 9, 9, 9));
        SheetModel.Exalts none = model(record(), priestOnly, live(ACCOUNT, 7, "Sharkbait", null)).exalts();
        assertNull(none.liveBonus());
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(wizard);
            assertEquals("+1", text(tab, "character-exalt-bonus-0"));
            assertEquals("+8", text(tab, "character-exalt-bonus-7"));
            assertTrue(text(tab, "character-exalts-bonus-observed").startsWith("Live bonus observed "));
            assertEquals("When this class's counts last changed", "Changed 1 h ago", text(tab, "character-exalts-observed"));
            tab.apply(none);
            assertFalse(named(tab, "character-exalt-bonus-0", JLabel.class).isVisible());
            assertFalse(named(tab, "character-exalts-bonus-observed", JLabel.class).isVisible());
        });
    }

    @Test public void earnInNamesDungeonsOrShowsADashWhileTheMappingIsUnavailable() throws Exception {
        SheetModel.Exalts mapped = SheetModelBuilder.build(record(), account(0, 0, 0, 0, 0, 0, 0, 0), null, defs(),
            stat -> stat == 3 ? List.of("Lost Halls") : List.<String>of(), NOW).exalts();
        SheetModel.Exalts loading = model(record(), account(0, 0, 0, 0, 0, 0, 0, 0), null).exalts();
        assertNull(loading.earnIn());
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(mapped);
            assertEquals("Earn in: Lost Halls", text(tab, "character-exalt-earn-3"));
            assertEquals("Earn in: —", text(tab, "character-exalt-earn-0"));
            assertEquals("Not mapped in the selected game assets", named(tab, "character-exalt-earn-0", JLabel.class).getToolTipText());
            tab.apply(loading);
            assertEquals("Earn in: —", text(tab, "character-exalt-earn-3"));
            assertEquals("Dungeon mapping loads with the selected game assets", named(tab, "character-exalt-earn-3", JLabel.class).getToolTipText());
        });
    }

    @Test public void aClassWithoutSavedProgressShowsTheEmptyState() throws Exception {
        SheetModel.Exalts unknown = model(record(), account(), null).exalts();
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(unknown);
            assertTrue(named(tab, "character-exalts-empty", EmptyState.class).isVisible());
            assertFalse("Unknown is never shown as zero completions", named(tab, "character-exalts-content", JPanel.class).isVisible());
            tab.apply(null);
            assertFalse("Loading shows no \"no progress\" either", named(tab, "character-exalts-empty", EmptyState.class).isVisible());
        });
    }

    @Test public void aPrefixRenamesEveryComponent() throws Exception {
        SheetModel.Exalts exalts = model(record(), account(0, 4, 5, 14, 15, 30, 50, 74), null).exalts();
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab sheet = new ExaltsTab(), account = new ExaltsTab("account");
            List<String> sheetNames = names(sheet, new java.util.ArrayList<>()), accountNames = names(account, new java.util.ArrayList<>());
            assertTrue("The sheet keeps its names", sheetNames.containsAll(List.of("character-exalts", "character-exalts-totals",
                "character-exalts-content", "character-exalts-empty", "character-exalt-pips-0", "character-exalt-earn-7")));
            List<String> expected = new java.util.ArrayList<>();
            for (String name : sheetNames) expected.add(name != null && name.startsWith("character") ? "account" + name.substring("character".length()) : name);
            assertEquals("Every name, in the same place, with the prefix instead of \"character\"", expected, accountNames);
            for (String name : accountNames) assertFalse(name, name != null && name.startsWith("character"));
            account.apply(exalts);
            assertEquals("Total completions 192 · Lowest tier 0/5", text(account, "account-exalts-totals"));
            assertEquals(3, named(account, "account-exalt-pips-5", PipMeter.class).filled());
            assertEquals("74 completions", text(account, "account-exalt-count-7"));
        });
    }

    /** Every component's name, depth first from {@code root} (null for an unnamed one), so two trees compare position by position. */
    private static List<String> names(java.awt.Component root, List<String> into) {
        into.add(root.getName());
        if (root instanceof java.awt.Container) for (java.awt.Component child : ((java.awt.Container) root).getComponents()) names(child, into);
        return into;
    }
}
