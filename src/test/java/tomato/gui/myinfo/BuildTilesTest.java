package tomato.gui.myinfo;

import java.awt.Rectangle;
import java.util.Locale;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.StatTile;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.named;

/** Build's four metric tiles (P6b Task 10): known, partial, estimate and unknown values, their Details buttons and the recorded-DPS line. */
public class BuildTilesTest {
    private static final int WEAPON = 999_002;
    private static final String NO_BUILD = "Enter the game during capture to see your build.";
    private Locale previous;

    private static final class View extends MyInfoGUI {
        String title, opened;
        View(TomatoData data) { super(data); }
        @Override protected void showMetricDetails(String title, String text) { this.title = title; opened = text; }
    }

    @Before public void usFormat() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restoreFormat() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void withoutACapturedCharacterEveryTileIsUnknownWithTheEnterTheGameReason() throws Exception {
        View view = edt(() -> new View(new TomatoData()));
        SwingUtilities.invokeAndWait(() -> {
            StatTile[] tiles = tiles(view);
            String[] captions = {"Health", "Mana", "Weapon DPS", "MP/sec"};
            for (int i = 0; i < 4; i++) {
                assertEquals("Tile " + i, "tile-" + captions[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("-$", ""), tiles[i].getName());
                assertEquals(captions[i] + ": —", tiles[i].getAccessibleContext().getAccessibleName());
                assertEquals(DisplayValue.State.UNKNOWN, tiles[i].value().state);
                assertEquals("Unknown is never 0", "—", tiles[i].valueText());
                assertEquals(NO_BUILD, tiles[i].value().tooltip());
            }
        });
    }

    @Test public void healthAndManaAreKnownPairsPartialWhenOneSideIsMissingAndUnknownWhenBoth() throws Exception {
        TomatoData data = data();
        Entity player = new Entity(data, 1, 0); data.player = player;
        put(player, StatType.HP_STAT, 820); put(player, StatType.MAX_HP_STAT, 900); put(player, StatType.MP_STAT, 0);
        View view = edt(() -> new View(data));
        publish(player);
        SwingUtilities.invokeAndWait(() -> {
            StatTile[] tiles = tiles(view);
            assertEquals(DisplayValue.State.KNOWN, tiles[0].value().state);
            assertEquals("820 / 900", tiles[0].valueText());
            assertEquals(DisplayValue.State.PARTIAL, tiles[1].value().state);
            assertEquals("A captured 0 stays 0; the missing side stays —", "0 / — (partial)", tiles[1].valueText());
            assertEquals("Maximum mana not captured yet.", tiles[1].value().tooltip());
        });
        player.stat.set(StatType.HP_STAT, null); player.stat.set(StatType.MAX_HP_STAT, null);
        publish(player);
        SwingUtilities.invokeAndWait(() -> {
            StatTile health = tiles(view)[0];
            assertEquals(DisplayValue.State.UNKNOWN, health.value().state);
            assertEquals("—", health.valueText());
            assertEquals("Health and maximum health not captured yet.", health.value().tooltip());
        });
    }

    @Test public void estimatesShowApproximatelyWithTheirAssumptionsAndMissingInputsWhenUnknown() throws Exception {
        TomatoData data = data();
        Entity player = build(new Entity(data, 1, 0)); data.player = player;
        View view = edt(() -> new View(data));
        try (AutoCloseable weapon = WeaponFixture.install(WEAPON, 100, 200, 2, 1.5f)) {
            SwingUtilities.invokeAndWait(() -> { });
            MyInfoGuiTest.equipPet(data, BuildEstimates.MAGIC_HEAL); // publishes the player with a known Magic Heal pet
            SwingUtilities.invokeAndWait(() -> {
                StatTile[] tiles = tiles(view);
                assertEquals(DisplayValue.State.ESTIMATE, tiles[2].value().state);
                assertEquals(DisplayValue.estimate("7,200", "-").text(), tiles[2].valueText());
                assertTrue(tiles[2].valueText(), tiles[2].valueText().matches("[≈~] 7,200"));
                assertTrue("The assumptions are the tooltip", tiles[2].value().tooltip().contains("0 defense"));
                assertEquals(DisplayValue.State.ESTIMATE, tiles[3].value().state);
                assertEquals(DisplayValue.estimate("54", "-").text(), tiles[3].valueText());
                assertTrue(tiles[3].value().tooltip(), tiles[3].value().tooltip().startsWith("Estimate scenario: in combat"));
                assertTrue(tiles[3].value().tooltip().contains("Magic Heal"));
                named(view, null, JCheckBox.class).doClick(); // "Estimate scenario: out of combat"
                assertTrue(tiles[3].value().tooltip().startsWith("Estimate scenario: out of combat"));
            });
            put(player, StatType.INVENTORY_0_STAT, -1); player.stat.set(StatType.WISDOM_STAT, null);
            publish(player);
            SwingUtilities.invokeAndWait(() -> {
                StatTile[] tiles = tiles(view);
                for (int i : new int[] {2, 3}) {
                    assertEquals(DisplayValue.State.UNKNOWN, tiles[i].value().state);
                    assertEquals("—", tiles[i].valueText());
                }
                assertEquals("Missing inputs: weapon/projectile definitions. Details lists what the estimate needs.", tiles[2].value().tooltip());
                assertEquals("Missing inputs: wisdom not captured. Details lists what the estimate needs.", tiles[3].value().tooltip());
            });
        }
    }

    @Test public void theFourDetailsButtonsAreGhostButtonsUnderTheirTiles() throws Exception {
        TomatoData data = data();
        Entity player = new Entity(data, 1, 0); data.player = player;
        put(player, StatType.HP_STAT, 820); put(player, StatType.MAX_HP_STAT, 900);
        View view = edt(() -> new View(data));
        publish(player);
        SwingUtilities.invokeAndWait(() -> {
            view.setSize(1100, 900); layout(view);
            StatTile[] tiles = tiles(view);
            String[] captions = {"Health", "Mana", "Weapon DPS", "MP/sec"};
            for (int i = 0; i < 4; i++) {
                JButton button = named(view, "myinfo-metric-" + i, JButton.class);
                assertNotNull("myinfo-metric-" + i, button);
                assertTrue(button instanceof KitButton);
                assertEquals(KitButton.Variant.GHOST, ((KitButton) button).variant());
                assertEquals("Details…", button.getText());
                assertEquals(captions[i] + " details", button.getAccessibleContext().getAccessibleName());
                Rectangle tile = SwingUtilities.convertRectangle(tiles[i].getParent(), tiles[i].getBounds(), view);
                Rectangle under = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), view);
                assertTrue("Details " + i + " sits under its tile: " + under + " vs " + tile,
                    under.y >= tile.y + tile.height && under.x >= tile.x && under.x < tile.x + tile.width);
            }
            named(view, "myinfo-metric-0", JButton.class).doClick();
            assertEquals("Health", view.title);
            assertTrue(view.opened, view.opened.contains("Health: 820 points") && view.opened.contains("Maximum health: 900 points"));
            named(view, "myinfo-metric-2", JButton.class).doClick();
            assertEquals("Weapon DPS", view.title);
            assertTrue(view.opened.startsWith("Estimated from the displayed build"));
        });
    }

    @Test public void recordedDpsExplanationReadsTheRawEstimateAfterEveryUpdate() throws Exception {
        TomatoData data = data();
        Entity player = build(new Entity(data, 1, 0)); data.player = player;
        put(player, StatType.INVENTORY_0_STAT, -1);
        View view = edt(() -> new View(data));
        try (AutoCloseable weapon = WeaponFixture.install(WEAPON, 100, 200, 2, 1.5f)) {
            publish(player);
            SwingUtilities.invokeAndWait(() -> assertTrue(explanation(view),
                explanation(view).startsWith("Current estimate: weapon DPS (est.) unavailable · estimate scenario: in combat")));
            put(player, StatType.INVENTORY_0_STAT, WEAPON);
            publish(player);
            SwingUtilities.invokeAndWait(() -> {
                String shown = DisplayFormat.formatNumber(7200d, 0, 2);
                assertTrue(explanation(view), explanation(view).startsWith("Current estimate: " + shown + " weapon DPS (est.) · estimate scenario: in combat"));
                named(view, null, JCheckBox.class).doClick();
                assertTrue(explanation(view), explanation(view).startsWith("Current estimate: " + shown + " weapon DPS (est.) · estimate scenario: out of combat"));
            });
            put(player, StatType.ATTACK_STAT, 25);   // 0.5 + 25 / 50 = 1.0 × instead of 2.0 ×: half the damage
            publish(player);
            SwingUtilities.invokeAndWait(() -> assertTrue(explanation(view),
                explanation(view).startsWith("Current estimate: " + DisplayFormat.formatNumber(3600d, 0, 2) + " weapon DPS (est.)")));
        }
    }

    /** Lays out a component tree that has no window (validate() needs a peer). */
    private static void layout(java.awt.Component component) {
        if (!(component instanceof java.awt.Container)) return;
        ((java.awt.Container) component).doLayout();
        for (java.awt.Component child : ((java.awt.Container) component).getComponents()) layout(child);
    }

    private static StatTile[] tiles(MyInfoGUI view) { return declared(view, "summary", StatTile[].class); }

    /** A MyInfoGUI field of {@code view} (a View subclass, so FormattingTestSupport.field would not see it). */
    private static <T> T declared(MyInfoGUI view, String name, Class<T> type) {
        try { java.lang.reflect.Field field = MyInfoGUI.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(view)); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static String explanation(MyInfoGUI view) { return declared(view, "recordedDps", RecordedDpsPanel.class).explanationText(); }

    private static TomatoData data() { TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA=="); return data; }

    /** Publishes {@code player} as capture does and waits for Build to apply it. */
    private static void publish(Entity player) throws Exception {
        SwingUtilities.invokeAndWait(() -> MyInfoGUI.updatePlayer(player));
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** Attack and dexterity 75, exaltation ×1.0, wisdom 75, known-empty enchants, the fixture weapon equipped. */
    private static Entity build(Entity player) {
        put(player, StatType.ATTACK_STAT, 75); put(player, StatType.DEXTERITY_STAT, 75); put(player, StatType.EXALTATION_BONUS_DAMAGE, 1000);
        put(player, StatType.INVENTORY_0_STAT, WEAPON); put(player, StatType.WISDOM_STAT, 75); put(player, StatType.MAX_MP_STAT, 400);
        StatData enchants = new StatData(); enchants.stringStatValue = ""; player.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        StatData account = new StatData(); account.stringStatValue = "A"; player.stat.set(StatType.ACCOUNT_ID_STAT, account);
        return player;
    }

    private static void put(Entity entity, StatType type, int value) { StatData stat = new StatData(); stat.statValue = value; entity.stat.set(type, stat); }

    private static <T> T edt(java.util.concurrent.Callable<T> task) throws Exception {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> { try { result[0] = task.call(); } catch (Exception e) { throw new AssertionError(e); } });
        @SuppressWarnings("unchecked") T value = (T) result[0];
        return value;
    }
}
