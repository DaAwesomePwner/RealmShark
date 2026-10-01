# Loot Explore pictures — P1 (haul foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This repo's workflow:** the implementer (Codex via Orchestra) edits files only. The coordinator (Claude) runs every
> Gradle command and makes every commit. "Run" and "Commit" steps are the coordinator's.

**Goal:** Draw one run's loot the way the game does — bag sprites, 8-slot bag grids, a best drop — as a shared `HaulView`, and switch the Runs tab's run recap Loot section to it.

**Architecture:** A pure `HaulModel` (best drop, bag groups in value order, tally) feeds a Swing `HaulView` with two modes: Full (header, best drop, shelf, open bag; used by Explore in P2) and Compact (shelf and open bag; used by the recap now). `BagSprites` maps saved bag names to game bag sprites and a value rank. The recap's loot wording moves to a shared `LootLine` so cards, recap and haul cannot drift.

**Tech Stack:** Java 17 (`--release 17`), Swing + FlatLaf, the project UI kit (`tomato.gui.kit`), JUnit 4, Gradle 7.6.4.

**Spec:** `docs/superpowers/specs/2026-10-01-loot-explore-pictures-design.md` (P1 = "haul foundation"). P2–P4 get their own plans after P1 merges.

## Global Constraints

- Java 17 language level only; no new dependencies.
- All Swing work on the EDT; tests wrap Swing in `SwingUtilities.invokeAndWait`.
- Copy is sentence case, parts joined with `" · "`; bag counts read `"×N"`; bag labels read `"<name> bag"`, or `"Bag"` when no name was saved.
- Enchant data that is `NOT_RECORDED` shows no pips and never a guessed rarity.
- New component names use the `loot-haul-*` prefix.
- Fixtures are synthetic (no personal game history, no absolute user paths).
- Keyboard tests invoke the bound `InputMap`/`ActionMap` action (or queued AWT events), never `java.awt.Robot`.
- Gradle (from the worktree root, Git Bash):
  ```bash
  export JAVA_HOME="$(pwd)/../../../.tools/jdk-17.0.20.1+1"; export GRADLE_USER_HOME="$(pwd)/../../../.tools/gradle-home"; ./gradlew.bat test --offline -PrealmSharkBuildDir=build-loot-p1 --tests <TEST>
  ```
  Written below as `GRADLE --tests <TEST>`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Older or unknown bag names** (null, `"Mystery"`, `"B.Egg"`): each still gets a labelled tile, ranks after every known bag, and paints a tinted well. Covered by Tasks 2, 3 and 4.
2. **No extracted assets** (a fresh launch folder): bag tiles are never blank, because `BagSprites` falls back to a tinted well. Covered by Task 2.
3. **Bags holding more than 8 items, or none:** a grid wraps to a second row of 8, and an empty bag shows 8 empty slots. Covered by Task 4.
4. **Legacy items** (no enchant slots, no tier, or odd tier labels such as `"T"` or `"Tx"`): there is still a sensible best drop, no exception, and no invented rarity. Covered by Task 3, plus Task 6's potion check.
5. **Recap at 680×520 with font 18:** the haul never scrolls sideways, and an equal model rebuilds nothing, so the live in-progress refresh keeps focus. Covered by Task 6.

---

### Task 1: Shared loot wording (`LootLine`)

**Files:**
- Create: `src/main/java/tomato/gui/loot/haul/LootLine.java`
- Create: `src/test/java/tomato/gui/loot/haul/LootLineTest.java`
- Modify: `src/main/java/tomato/gui/runs/RunCardModel.java:152-166` (`summary`)
- Modify: `src/main/java/tomato/gui/runs/RunRecapView.java` (delete nested `LootLine` at the end of the file; call sites change in Task 6. For this task only, replace `LootLine.` with `tomato.gui.loot.haul.LootLine.` at lines 574 and 604 so it compiles)
- Modify: `src/test/java/tomato/gui/runs/RunRecapViewTest.java:346-352` (delete `lootLinesUseOneWording`; it moves to `LootLineTest`)

**Interfaces:**
- Produces: `public final class LootLine { public static String kinds(List<LootFacts.Item>); public static String section(int items, int bags, String kinds); }`

- [ ] **Step 1: Write the failing test** — `src/test/java/tomato/gui/loot/haul/LootLineTest.java`

```java
package tomato.gui.loot.haul;

import java.util.List;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;

/** The one loot wording shared by run cards, the run recap and the haul. */
public class LootLineTest {
    private static LootFacts.Item ut(int id) { return new LootFacts.Item(id, true, false, false, false); }
    private static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }

    @Test public void kindsCountUntieredSetTieredAndPotionsOnly() {
        assertEquals("1 UT · 2 potions", LootLine.kinds(List.of(ut(1), potion(2), potion(3))));
        assertEquals("1 ST · 1 potion", LootLine.kinds(List.of(new LootFacts.Item(4, false, true, false, false), potion(5))));
        assertEquals("", LootLine.kinds(List.of(new LootFacts.Item(6, false, false, true, false))));
    }

    @Test public void sectionPutsTheCountsFirst() {
        assertEquals("3 items in 2 bags · 1 UT · 2 potions", LootLine.section(3, 2, "1 UT · 2 potions"));
        assertEquals("1 item in 1 bag", LootLine.section(1, 1, ""));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.haul.LootLineTest`
Expected: compilation FAIL, "cannot find symbol: LootLine".

- [ ] **Step 3: Implement** — `src/main/java/tomato/gui/loot/haul/LootLine.java`

```java
package tomato.gui.loot.haul;

import java.util.ArrayList;
import java.util.List;
import tomato.gui.stats.LootFacts;

/**
 * The loot wording shared by the run cards, the run recap and the haul ("1 UT · 2 potions", "3 items in 2 bags · …"): untiered,
 * then set-tiered, then potions; nothing else is counted by kind.
 */
public final class LootLine {
    private LootLine() {}

    /** "1 UT · 2 potions" for these items; "" when none is untiered, set-tiered or a potion. */
    public static String kinds(List<LootFacts.Item> items) {
        int untiered = 0, setTiered = 0, potions = 0;
        for (LootFacts.Item item : items) {
            if (item.untiered()) untiered++;
            if (item.setTiered()) setTiered++;
            if (item.potion()) potions++;
        }
        List<String> parts = new ArrayList<>();
        if (untiered > 0) parts.add(untiered + " UT");
        if (setTiered > 0) parts.add(setTiered + " ST");
        if (potions > 0) parts.add(potions + (potions == 1 ? " potion" : " potions"));
        return String.join(" · ", parts);
    }

    /** "3 items in 2 bags · 1 UT · 2 potions" ({@code kinds} as {@link #kinds} words it; "" leaves it out). */
    public static String section(int items, int bags, String kinds) {
        String line = items + (items == 1 ? " item" : " items") + " in " + bags + (bags == 1 ? " bag" : " bags");
        return kinds == null || kinds.isEmpty() ? line : line + " · " + kinds;
    }
}
```

Replace `RunCardModel.summary` (keeps its "N items" fallback):

```java
    static String summary(List<LootFacts.Bag> bags) {
        List<LootFacts.Item> items = new ArrayList<>();
        for (LootFacts.Bag bag : bags) items.addAll(bag.items());
        String kinds = tomato.gui.loot.haul.LootLine.kinds(items);
        if (!kinds.isEmpty() || items.isEmpty()) return kinds;
        return items.size() + (items.size() == 1 ? " item" : " items");
    }
```

Delete the nested `static final class LootLine { … }` (with its Javadoc) at the end of `RunRecapView.java`. Point its two call sites at `tomato.gui.loot.haul.LootLine`, and delete `lootLinesUseOneWording` from `RunRecapViewTest`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.loot.haul.LootLineTest --tests tomato.gui.runs.RunRecapBuilderTest --tests tomato.gui.runs.RunRecapViewTest --tests tomato.gui.runs.RunFeedModelTest`
Expected: PASS. `lootSummaryIsTheFeedCardsWording` pins the card fallback.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/haul/LootLine.java src/test/java/tomato/gui/loot/haul/LootLineTest.java src/main/java/tomato/gui/runs/RunCardModel.java src/main/java/tomato/gui/runs/RunRecapView.java src/test/java/tomato/gui/runs/RunRecapViewTest.java
git commit -m "Share the loot wording between run cards, the recap and the haul"
```

---

### Task 2: `BagSprites` — bag sprites and value rank

**Files:**
- Create: `src/main/java/tomato/gui/kit/BagSprites.java`
- Create: `src/test/java/tomato/gui/kit/BagSpritesTest.java`

**Interfaces:**
- Consumes: `LootBags.lootBagName` (`TreeMap<Integer,String>`), `Sprites.sprite(int,int)`, `Sprites.isPlaceholder(Icon)`, `Tokens.bag(String)`, `Tokens.tint(Color)`, `Tokens.ARC_CONTROL`
- Produces: `BagSprites.objectId(String) -> int`, `BagSprites.rank(String) -> int` (higher is more valuable; 0 = unknown), `BagSprites.sprite(String, int) -> Icon`, `BagSprites.isFallback(Icon) -> boolean`

- [ ] **Step 1: Write the failing test** — `src/test/java/tomato/gui/kit/BagSpritesTest.java`

```java
package tomato.gui.kit;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.JLabel;
import org.junit.Test;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

/** Bag sprites by saved bag name, the bag value order, and the tinted well when no sprite is available. */
public class BagSpritesTest {
    @Test public void everySavedBagNameMapsToItsObjectId() {
        for (Map.Entry<Integer, String> bag : LootBags.lootBagName.entrySet())
            assertEquals(bag.getValue(), (int) bag.getKey(), BagSprites.objectId(bag.getValue()));
        assertEquals(1292, BagSprites.objectId("white"));
        assertEquals(1296, BagSprites.objectId(" B.White "));
        assertEquals(0, BagSprites.objectId("Mystery"));
        assertEquals(0, BagSprites.objectId(null));
    }

    @Test public void bagsRankWhiteFirstWithBoostedJustAboveTheirBase() {
        List<String> order = List.of("White", "Red", "Orange", "Blue", "Teal", "Gold", "Egg Basket", "Purple", "Pink", "Soulbound", "Brown");
        for (int i = 1; i < order.size(); i++)
            assertTrue(order.get(i - 1) + " > " + order.get(i), BagSprites.rank(order.get(i - 1)) > BagSprites.rank(order.get(i)));
        assertTrue(BagSprites.rank("B.White") > BagSprites.rank("White"));
        assertTrue(BagSprites.rank("B.Red") < BagSprites.rank("White"));
        assertTrue(BagSprites.rank("B.Egg") > BagSprites.rank("Egg Basket"));
        assertTrue(BagSprites.rank("B.Egg") < BagSprites.rank("Gold"));
        assertTrue("Every known bag outranks an unknown one", BagSprites.rank("Brown") > 0);
        assertEquals(0, BagSprites.rank("Mystery"));
        assertEquals(0, BagSprites.rank(null));
    }

    @Test public void withoutASpriteABagPaintsATintedWellNeverABlank() {
        Sprites.clear();
        try {
            Icon unknown = BagSprites.sprite("Mystery", 24);
            assertEquals(24, unknown.getIconWidth());
            assertEquals(24, unknown.getIconHeight());
            assertTrue(BagSprites.isFallback(unknown));
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            unknown.paintIcon(new JLabel(), g, 0, 0);
            g.dispose();
            boolean painted = false;
            for (int y = 0; y < 24 && !painted; y++) for (int x = 0; x < 24; x++) if ((image.getRGB(x, y) >>> 24) != 0) { painted = true; break; }
            assertTrue("The well is painted", painted);
            assertEquals(24, BagSprites.sprite("White", 24).getIconWidth());
        } finally { Sprites.clear(); }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.kit.BagSpritesTest`
Expected: compilation FAIL, "cannot find symbol: BagSprites".

- [ ] **Step 3: Implement** — `src/main/java/tomato/gui/kit/BagSprites.java`

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Icon;
import tomato.realmshark.enums.LootBags;

/**
 * Loot bags as the game draws them: a bag's sprite by its saved name ("White", "B.White", "Egg Basket"), and the value order bags
 * are sorted by everywhere. A bag without a sprite (unknown name, assets not extracted yet) paints a well in its bag color.
 */
public final class BagSprites {
    /** Base bag names, most valuable first; a boosted bag ranks immediately above its base. */
    private static final List<String> ORDER = List.of("white", "red", "orange", "blue", "teal", "gold", "egg basket", "purple", "pink", "soulbound", "brown");

    private BagSprites() {}

    /** The game object ID of a saved bag name (case and surrounding spaces ignored); 0 when unknown. */
    public static int objectId(String bagName) {
        if (bagName == null) return 0;
        String key = bagName.trim();
        for (Map.Entry<Integer, String> bag : LootBags.lootBagName.entrySet()) if (bag.getValue().equalsIgnoreCase(key)) return bag.getKey();
        return 0;
    }

    /** Higher is more valuable: White highest, Brown lowest of the known bags, 0 for an unknown or missing name. */
    public static int rank(String bagName) {
        if (bagName == null) return 0;
        String key = bagName.trim().toLowerCase(Locale.ROOT);
        boolean boosted = key.startsWith("b.");
        if (boosted) key = key.substring(2).trim();
        if (key.equals("egg")) key = "egg basket";
        int index = ORDER.indexOf(key);
        return index < 0 ? 0 : (ORDER.size() - index) * 2 + (boosted ? 1 : 0);
    }

    /** The bag's game sprite at {@code size}, or a tinted well while none is available; resolves live after asset reloads. */
    public static Icon sprite(String bagName, int size) { return new BagIcon(bagName, size); }

    /** True when {@code icon} is a bag icon that currently paints the tinted well (no game sprite). */
    public static boolean isFallback(Icon icon) { return icon instanceof BagIcon bag && Sprites.isPlaceholder(bag.live); }

    private static final class BagIcon implements Icon {
        private final String bag;
        private final int size;
        private final Icon live;

        BagIcon(String bag, int size) {
            this.bag = bag;
            this.size = size;
            live = Sprites.sprite(objectId(bag), size);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            if (!Sprites.isPlaceholder(live)) { live.paintIcon(c, graphics, x, y); return; }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = Tokens.bag(bag);
                g.setColor(Tokens.tint(color));
                g.fillRoundRect(x, y, size - 1, size - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
                g.setColor(color);
                g.drawRoundRect(x, y, size - 1, size - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            } finally {
                g.dispose();
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.kit.BagSpritesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/BagSprites.java src/test/java/tomato/gui/kit/BagSpritesTest.java
git commit -m "Add bag sprites and the bag value order to the UI kit"
```

---

### Task 3: `HaulModel` — best drop, shelf and tally

**Files:**
- Create: `src/main/java/tomato/gui/loot/haul/HaulModel.java`
- Create: `src/test/java/tomato/gui/loot/haul/HaulModelTest.java`

**Interfaces:**
- Consumes: `LootLine.kinds/section` (Task 1), `BagSprites.rank` (Task 2), `LootFacts.Item`, `EnchantInfo.Rarity`
- Produces:
  - `record HaulModel(Header header, Hero hero, List<Shelf> shelf, int openByDefault, String tally, int items)`
  - `static HaulModel of(Header header, List<Bag> bags)`
  - `static String variantKey(LootFacts.Item)`
  - `record Header(String mapName, int portalId, String outcome, Tokens.Tone outcomeTone, Long entered, Long durationMs, String character)`
  - `record Bag(String bag, long time, String dropper, List<LootFacts.Item> items)`
  - `record Hero(LootFacts.Item item, String bag, String dropper, long time)`
  - `record Shelf(String bag, List<Bag> bags)` with `int items()`

- [ ] **Step 1: Write the failing test** — `src/test/java/tomato/gui/loot/haul/HaulModelTest.java`

```java
package tomato.gui.loot.haul;

import java.util.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import tomato.gui.stats.LootQuery;
import static org.junit.Assert.*;

/** The haul's pure rules: best drop order, bag shelf order, the default open bag, the tally and exact variant keys. */
public class HaulModelTest {
    private static LootFacts.Item item(int id, boolean ut, boolean st, boolean potion, Integer slots, String tier) {
        return new LootFacts.Item(id, ut, st, false, potion, slots, slots == null ? null : 0, null, tier);
    }
    private static HaulModel.Bag bag(String name, long time, LootFacts.Item... items) { return new HaulModel.Bag(name, time, null, List.of(items)); }

    @Test public void theBestDropIsUtThenStThenRarityThenTierThenPotion() {
        List<LootFacts.Item> ladder = List.of(
            item(1, false, false, false, null, null),   // nothing known
            item(2, false, false, true, null, null),    // potion
            item(3, false, false, false, null, "T12"),  // tiered
            item(4, false, false, false, null, "T13"),  // higher tier
            item(5, false, false, false, 0, "T4"),      // known unenchanted beats unknown rarity
            item(6, false, false, false, 2, "T10"),     // Rare
            item(7, false, false, false, 4, "T5"),      // Divine
            item(8, false, true, false, null, null),    // ST
            item(9, true, false, false, null, null));   // UT
        for (int n = 1; n <= ladder.size(); n++) {
            List<LootFacts.Item> reversed = new ArrayList<>(ladder.subList(0, n));
            Collections.reverse(reversed);
            HaulModel haul = HaulModel.of(null, List.of(new HaulModel.Bag("White", 1, null, reversed)));
            assertEquals("Best of the first " + n, n, haul.hero().item().id());
        }
    }

    @Test public void tiesKeepTheEarliestDrop() {
        HaulModel haul = HaulModel.of(null, List.of(bag("White", 200, item(1, true, false, false, null, null)), bag("Red", 100, item(2, true, false, false, null, null))));
        assertEquals(2, haul.hero().item().id());
        assertEquals("Red", haul.hero().bag());
        assertEquals(100, haul.hero().time());
    }

    @Test public void oddTierLabelsCountAsNoTier() {
        HaulModel haul = HaulModel.of(null, List.of(bag("Brown", 1, item(1, false, false, false, null, "T"), item(2, false, false, false, null, "Tx"), item(3, false, false, false, null, "T2"))));
        assertEquals(3, haul.hero().item().id());
    }

    @Test public void theShelfRanksBagsByValueWithBoostedAboveTheirBaseAndUnknownLast() {
        HaulModel haul = HaulModel.of(null, List.of(bag("Purple", 1), bag("White", 2), bag(null, 3), bag("B.White", 4), bag("Purple", 5), bag("Mystery", 6)));
        assertEquals(Arrays.asList("B.White", "White", "Purple", null, "Mystery"), haul.shelf().stream().map(HaulModel.Shelf::bag).toList());
        assertEquals("Newest bag first within a group", List.of(5L, 1L), haul.shelf().get(2).bags().stream().map(HaulModel.Bag::time).toList());
        assertEquals("No items: no best drop, the first group opens", 0, haul.openByDefault());
        assertNull(haul.hero());
    }

    @Test public void theBestDropsBagOpensByDefault() {
        HaulModel haul = HaulModel.of(null, List.of(bag("White", 1, item(1, false, false, true, null, null)), bag("Pink", 2, item(2, true, false, false, null, null))));
        assertEquals(List.of("White", "Pink"), haul.shelf().stream().map(HaulModel.Shelf::bag).toList());
        assertEquals(1, haul.openByDefault());
    }

    @Test public void theTallyCountsItemsBagsAndNotableKinds() {
        HaulModel haul = HaulModel.of(null, List.of(bag("White", 1, item(1, true, false, false, null, null)),
            bag("Purple", 2, item(2, false, false, true, null, null), item(3, false, false, true, null, null))));
        assertEquals("3 items in 2 bags · 1 UT · 2 potions", haul.tally());
        assertEquals(3, haul.items());
        assertEquals(1, haul.shelf().get(0).items());
        assertEquals(2, haul.shelf().get(1).items());
    }

    @Test public void anEmptyHaulHasNoBestDropNoShelfAndNoTally() {
        HaulModel haul = HaulModel.of(null, List.of());
        assertNull(haul.hero());
        assertTrue(haul.shelf().isEmpty());
        assertEquals(-1, haul.openByDefault());
        assertEquals("", haul.tally());
        assertEquals(0, haul.items());
    }

    @Test public void variantKeysAreLootQuerysExactVariant() {
        LootFacts.Item enchanted = new LootFacts.Item(7, false, false, false, false, 2, 1), legacy = new LootFacts.Item(8, false, false, false, false);
        assertEquals("7/2/1", HaulModel.variantKey(enchanted));
        assertEquals("8/null/null", HaulModel.variantKey(legacy));
        for (LootFacts.Item item : List.of(enchanted, legacy)) {
            LootQuery.Facets facets = new LootQuery.Facets();
            facets.variant = HaulModel.variantKey(item);
            facets.validate();   // throws when the key is not an exact variant
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulModelTest`
Expected: compilation FAIL, "cannot find symbol: HaulModel".

- [ ] **Step 3: Implement** — `src/main/java/tomato/gui/loot/haul/HaulModel.java`

```java
package tomato.gui.loot.haul;

import java.util.*;
import tomato.gui.kit.BagSprites;
import tomato.gui.kit.Tokens;
import tomato.gui.stats.LootFacts;
import tomato.realmshark.EnchantInfo;

/**
 * One run's haul, ready to draw ({@link HaulView}): the best drop, the bags grouped by type in value order, and the tally line.
 * Pure: no components, no I/O. {@code header} is null where the surface has its own (the run recap); {@code hero} is null without
 * items; {@code openByDefault} is the shelf index of the group holding the hero (0 when there is no hero), -1 without bags.
 */
public record HaulModel(Header header, Hero hero, List<Shelf> shelf, int openByDefault, String tally, int items) {
    public HaulModel { shelf = List.copyOf(shelf); Objects.requireNonNull(tally, "tally"); }

    /** The run's facts for the Full haul. {@code outcome} is its label ("Completed"); null fields are unknown and left out. */
    public record Header(String mapName, int portalId, String outcome, Tokens.Tone outcomeTone, Long entered, Long durationMs, String character) {
        public Header { Objects.requireNonNull(mapName, "mapName"); }
    }

    /** One saved bag: its name ("White", "B.White"; null when none was saved), drop time, dropper (null when unknown) and items. */
    public record Bag(String bag, long time, String dropper, List<LootFacts.Item> items) {
        public Bag { items = List.copyOf(items); }
    }

    /** The best single item and the bag it came from. */
    public record Hero(LootFacts.Item item, String bag, String dropper, long time) {}

    /** Every bag of one type ({@code bag} as saved; null groups the bags saved without a name), newest first. */
    public record Shelf(String bag, List<Bag> bags) {
        public Shelf { bags = List.copyOf(bags); }
        public int items() {
            int count = 0;
            for (Bag bag : bags) count += bag.items().size();
            return count;
        }
    }

    /** Best first: UT, ST, higher enchant rarity, higher tier, potion; ties keep the earliest drop. */
    static final Comparator<Hero> BEST = Comparator.comparing((Hero h) -> !h.item().untiered())
        .thenComparing(h -> !h.item().setTiered())
        .thenComparing(h -> -rarity(h.item()))
        .thenComparing(h -> -tier(h.item()))
        .thenComparing(h -> !h.item().potion())
        .thenComparingLong(Hero::time);

    public static HaulModel of(Header header, List<Bag> bags) {
        Map<String, List<Bag>> groups = new LinkedHashMap<>();
        List<LootFacts.Item> all = new ArrayList<>();
        Hero hero = null;
        for (Bag bag : bags) {
            groups.computeIfAbsent(bag.bag(), name -> new ArrayList<>()).add(bag);
            for (LootFacts.Item item : bag.items()) {
                all.add(item);
                Hero candidate = new Hero(item, bag.bag(), bag.dropper(), bag.time());
                if (hero == null || BEST.compare(candidate, hero) < 0) hero = candidate;
            }
        }
        List<Shelf> shelf = new ArrayList<>();
        for (Map.Entry<String, List<Bag>> group : groups.entrySet()) {
            List<Bag> newest = new ArrayList<>(group.getValue());
            newest.sort(Comparator.comparingLong(Bag::time).reversed());
            shelf.add(new Shelf(group.getKey(), newest));
        }
        shelf.sort(Comparator.comparingInt((Shelf s) -> -BagSprites.rank(s.bag())).thenComparingLong(HaulModel::earliest));
        int open = shelf.isEmpty() ? -1 : 0;
        if (hero != null) for (int i = 0; i < shelf.size(); i++) if (Objects.equals(shelf.get(i).bag(), hero.bag())) open = i;
        String tally = bags.isEmpty() ? "" : LootLine.section(all.size(), bags.size(), LootLine.kinds(all));
        return new HaulModel(header, hero, shelf, open, tally, all.size());
    }

    /** item ID/slots/applied: {@code LootQuery}'s exact variant key (unknown counts read "null"). */
    public static String variantKey(LootFacts.Item item) { return item.id() + "/" + item.slots() + "/" + item.applied(); }

    /** Rarity order 0 (Unenchanted) to 4 (Divine); -1 when not recorded, so a known rarity always wins. */
    static int rarity(LootFacts.Item item) {
        EnchantInfo.Rarity rarity = item.enchant().rarity();
        return rarity == EnchantInfo.Rarity.UNKNOWN ? -1 : rarity.ordinal();
    }

    /** The tier number of a saved "T13"-style label; -1 for none, "UT"/"ST" or anything unreadable. */
    static int tier(LootFacts.Item item) {
        String tier = item.tier();
        if (tier == null || tier.length() < 2 || Character.toUpperCase(tier.charAt(0)) != 'T') return -1;
        try { return Integer.parseInt(tier.substring(1)); } catch (NumberFormatException e) { return -1; }
    }

    private static long earliest(Shelf shelf) { return shelf.bags().get(shelf.bags().size() - 1).time(); }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/haul/HaulModel.java src/test/java/tomato/gui/loot/haul/HaulModelTest.java
git commit -m "Add the haul model: best drop, bag shelf and tally"
```

---

### Task 4: `HaulView` Compact — bag shelf, open bag grids, item clicks

**Files:**
- Create: `src/main/java/tomato/gui/loot/haul/HaulView.java`
- Create: `src/test/java/tomato/gui/loot/haul/HaulViewTest.java`

**Interfaces:**
- Consumes: `HaulModel` (Task 3), `BagSprites.sprite` (Task 2), `ItemSlot(int)` / `setItem(int,String,EnchantInfo)` / `setEmpty()` / `state()` / `itemId()`, `ItemTiers.label(int)`, `KitText.caption/body/emphasis`, `KitButton.ghost`, `Chip(String, Tokens.Tone)`, `KitLayouts.stack(int, JComponent...)`, `Sprites.sprite/name`, `Type.caption()`, `DisplayFormat.formatTimestamp(Instant, TimestampMode.TIME)`, `DisplayFormat.formatDurationHMS(long)`
- Produces:
  - `public final class HaulView extends JPanel` with `enum Mode { FULL, COMPACT }`
  - `HaulView(Mode)`, `void show(HaulModel, VisitRef)`, `HaulModel model()`, `int openGroup()`, `void openGroup(int)`
  - `void onOpenItem(Consumer<String> variantKey)`, `void onOpenRun(Consumer<VisitRef>)`
  - `static final String EMPTY = "No bags recorded in this run"`
  - Component names: `loot-haul`, `loot-haul-header`, `loot-haul-title`, `loot-haul-facts`, `loot-haul-outcome`, `loot-haul-tally`, `loot-haul-open-run`, `loot-haul-hero`, `loot-haul-hero-slot`, `loot-haul-hero-name`, `loot-haul-hero-from`, `loot-haul-shelf`, `loot-haul-bag` (one `JToggleButton` per group, tooltip = accessible name), `loot-haul-grids`, `loot-haul-grid-caption`, `loot-haul-grid` (`GridLayout` of `ItemSlot`s), `loot-haul-empty`

This task writes the whole class, Full parts included, because they share `show()`. Its tests cover Compact; Task 5 adds the Full tests.

- [ ] **Step 1: Write the failing test** — `src/test/java/tomato/gui/loot/haul/HaulViewTest.java`

```java
package tomato.gui.loot.haul;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.Tokens;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** The haul over synthetic bags (synthetic names only): Compact and Full composition, opening groups, item and run callbacks. */
public class HaulViewTest {
    private static final long T0 = 1_700_000_000_000L;
    static final VisitRef RUN = new VisitRef("00000000-0000-4000-8000-000000000001", "v1");
    private Locale previous;

    @Before public void usLocale() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    static LootFacts.Item ut(int id) { return new LootFacts.Item(id, true, false, false, false, 2, 0); }
    static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }

    /** White (one UT, from a named boss), then two Purple bags of potions. */
    static HaulModel model(HaulModel.Header header) {
        return HaulModel.of(header, List.of(
            new HaulModel.Bag("White", T0 + 300_000, "Synthetic Colossus", List.of(ut(101))),
            new HaulModel.Bag("Purple", T0 + 400_000, null, List.of(potion(102), potion(103))),
            new HaulModel.Bag("Purple", T0 + 500_000, null, List.of(potion(104)))));
    }

    @Test public void compactShowsTheShelfAndTheOpenBagOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            view.show(model(null), null);
            assertFalse(shows(view, "loot-haul-header"));
            assertFalse(shows(view, "loot-haul-hero"));
            assertFalse(shows(view, "loot-haul-empty"));
            assertEquals(List.of("White bag: 1 bag, 1 item", "Purple bag: 2 bags, 3 items"), tooltips(view, "loot-haul-bag"));
            assertEquals("×1", all(view, "loot-haul-bag", JToggleButton.class).get(0).getText());
            assertEquals("The best drop's bag opens first", 0, view.openGroup());
            assertEquals(List.of(List.of(101)), grids(view));
            assertEquals("A bag is 8 slots", 8, all(view, "loot-haul-grid", JPanel.class).get(0).getComponentCount());
            assertTrue(text(view, "loot-haul-grid-caption").startsWith("White bag · Synthetic Colossus · "));
        });
    }

    @Test public void openingAGroupShowsOneGridPerBagNewestFirst() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            view.show(model(null), null);
            List<JToggleButton> tiles = all(view, "loot-haul-bag", JToggleButton.class);
            tiles.get(1).doClick();
            assertEquals(1, view.openGroup());
            assertTrue(tiles.get(1).isSelected());
            assertFalse(tiles.get(0).isSelected());
            assertEquals(List.of(List.of(104), List.of(102, 103)), grids(view));
            assertEquals("Purple bag", text(view, "loot-haul-grid-caption").substring(0, "Purple bag".length()));
            press(tiles.get(0), "ENTER");
            assertEquals("Enter opens a bag", 0, view.openGroup());
        });
    }

    @Test public void itemsReportTheirExactVariantOnClickEnterAndSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            List<String> opened = new ArrayList<>();
            view.onOpenItem(opened::add);
            view.show(model(null), null);
            ItemSlot slot = (ItemSlot) all(view, "loot-haul-grid", JPanel.class).get(0).getComponent(0);
            assertTrue(slot.isFocusable());
            slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            press(slot, "ENTER");
            press(slot, "SPACE");
            assertEquals(List.of("101/2/0", "101/2/0", "101/2/0"), opened);
        });
    }

    @Test public void anEmptyHaulSaysSoAndBigOrEmptyBagsKeepWholeRowsOfEight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.COMPACT);
            view.show(HaulModel.of(null, List.of()), null);
            assertTrue(shows(view, "loot-haul-empty"));
            assertEquals(HaulView.EMPTY, text(view, "loot-haul-empty"));
            assertFalse(shows(view, "loot-haul-shelf"));
            assertEquals(-1, view.openGroup());

            List<LootFacts.Item> nine = new ArrayList<>();
            for (int i = 0; i < 9; i++) nine.add(potion(200 + i));
            view.show(HaulModel.of(null, List.of(new HaulModel.Bag(null, T0, null, nine), new HaulModel.Bag("Mystery", T0 + 1, null, List.of()))), null);
            assertFalse(shows(view, "loot-haul-empty"));
            assertEquals(List.of("Bag: 1 bag, 9 items", "Mystery bag: 1 bag, 0 items"), tooltips(view, "loot-haul-bag"));
            assertEquals("Nine items wrap to a second row of 8", 16, all(view, "loot-haul-grid", JPanel.class).get(0).getComponentCount());
            view.openGroup(1);
            assertEquals("An empty bag is 8 empty slots", 8, all(view, "loot-haul-grid", JPanel.class).get(0).getComponentCount());
            assertEquals(List.of(List.of()), grids(view));
        });
    }

    // ---- helpers ----

    static <T extends Component> List<T> all(Container root, String name, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) found.addAll(all(container, name, type));
        }
        return found;
    }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        List<T> found = all(root, name, type);
        assertFalse("No " + name, found.isEmpty());
        return found.get(0);
    }

    static boolean has(Container root, String name) { return !all(root, name, Component.class).isEmpty(); }
    static boolean shows(Container root, String name) { return named(root, name, Component.class).isVisible(); }
    static String text(Container root, String name) { return named(root, name, JLabel.class).getText(); }

    static List<String> tooltips(Container root, String name) {
        List<String> tips = new ArrayList<>();
        for (JComponent component : all(root, name, JComponent.class)) tips.add(component.getToolTipText());
        return tips;
    }

    /** The item IDs of each shown grid, in order (empty slots left out). */
    static List<List<Integer>> grids(Container root) {
        List<List<Integer>> result = new ArrayList<>();
        for (JPanel grid : all(root, "loot-haul-grid", JPanel.class)) {
            List<Integer> ids = new ArrayList<>();
            for (Component c : grid.getComponents()) if (c instanceof ItemSlot slot && slot.state() == ItemSlot.State.ITEM) ids.add(slot.itemId());
            result.add(ids);
        }
        return result;
    }

    static void press(JComponent target, String key) {
        Object action = target.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
        assertNotNull("Bound on the focused component: " + key, action);
        target.getActionMap().get(action).actionPerformed(new ActionEvent(target, ActionEvent.ACTION_PERFORMED, null));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulViewTest`
Expected: compilation FAIL, "cannot find symbol: HaulView".

- [ ] **Step 3: Implement** — `src/main/java/tomato/gui/loot/haul/HaulView.java`

```java
package tomato.gui.loot.haul;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;

/**
 * One run's haul drawn the way the game shows loot. FULL (Loot › Explore): header, best drop, the bag shelf and the open group's
 * 8-slot grids. COMPACT (the run recap's Loot section): the shelf and the open group only. One shelf group is open at a time; a
 * group of several bags shows one grid per bag, newest first. Clicking an item, or Enter/Space on a focused one, reports its exact
 * variant key ({@link HaulModel#variantKey}); Full's "Open run" reports the run.
 */
public final class HaulView extends JPanel {
    public enum Mode { FULL, COMPACT }

    public static final String EMPTY = "No bags recorded in this run";
    static final int SLOTS_PER_ROW = 8, SLOT = 32, BAG = 32, HERO = 48, PORTAL = 40;
    private static final String INDEX = "loot-haul-index", OPEN = "loot-haul-open";

    private final Mode mode;
    private final Line header = new Line("loot-haul-header"), hero = new Line("loot-haul-hero"), shelf = new Line("loot-haul-shelf");
    private final Column grids = new Column("loot-haul-grids");
    private final KitText empty = KitText.caption(EMPTY);
    private final KitButton openRun = KitButton.ghost("Open run");
    private HaulModel model;
    private VisitRef run;
    private int open = -1;
    private Consumer<String> onOpenItem = key -> {};
    private Consumer<VisitRef> onOpenRun = ref -> {};

    public HaulView(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        setName("loot-haul");
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        empty.setName("loot-haul-empty");
        openRun.setName("loot-haul-open-run");
        openRun.addActionListener(e -> { if (run != null) onOpenRun.accept(run); });
        for (JComponent part : new JComponent[] {header, hero, shelf, grids, empty}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            part.setBorder(new EmptyBorder(0, 0, Tokens.S, 0));
            add(part);
        }
        show(HaulModel.of(null, List.of()), null);
    }

    /** Draws {@code model} and opens its default group; {@code run} is the exact run it belongs to (null: no "Open run"). */
    public void show(HaulModel model, VisitRef run) {
        this.model = Objects.requireNonNull(model, "model");
        this.run = run;
        boolean full = mode == Mode.FULL, any = !model.shelf().isEmpty();
        fillHeader();
        fillHero();
        header.setVisible(full && model.header() != null);
        hero.setVisible(full && model.hero() != null);
        empty.setVisible(!any);
        shelf.setVisible(any);
        grids.setVisible(any);
        fillShelf();
        openGroup(model.openByDefault());
        revalidate();
        repaint();
    }

    public HaulModel model() { return model; }

    /** The open shelf group's index; -1 when none is open. */
    public int openGroup() { return open; }

    /** Opens shelf group {@code index}: one 8-slot grid per bag, newest first. An index out of range closes every group. */
    public void openGroup(int index) {
        List<HaulModel.Shelf> groups = model.shelf();
        open = index >= 0 && index < groups.size() ? index : -1;
        for (Component tile : shelf.getComponents())
            if (tile instanceof AbstractButton button) button.setSelected(Integer.valueOf(open).equals(button.getClientProperty(INDEX)));
        grids.removeAll();
        if (open >= 0) for (HaulModel.Bag bag : groups.get(open).bags()) grids.add(grid(bag));
        grids.revalidate();
        grids.repaint();
    }

    /** Called with an item's exact variant key ("id/slots/applied") when it is clicked or activated. */
    public void onOpenItem(Consumer<String> action) { onOpenItem = Objects.requireNonNull(action, "action"); }

    /** Called with the run when Full's "Open run" is clicked. */
    public void onOpenRun(Consumer<VisitRef> action) { onOpenRun = Objects.requireNonNull(action, "action"); }

    private void fillHeader() {
        header.removeAll();
        HaulModel.Header h = model.header();
        if (h == null) return;
        header.add(new JLabel(Sprites.sprite(h.portalId(), PORTAL)));
        KitText title = KitText.emphasis(h.mapName());
        title.setName("loot-haul-title");
        KitText facts = KitText.caption(facts(h));
        facts.setName("loot-haul-facts");
        facts.setVisible(!facts.getText().isEmpty());
        header.add(KitLayouts.stack(0, title, facts));
        if (h.outcome() != null) {
            Chip outcome = new Chip(h.outcome(), h.outcomeTone() == null ? Tokens.Tone.NEUTRAL : h.outcomeTone());
            outcome.setName("loot-haul-outcome");
            header.add(outcome);
        }
        KitText tally = KitText.caption(model.tally());
        tally.setName("loot-haul-tally");
        tally.setVisible(!model.tally().isEmpty());
        header.add(tally);
        openRun.setVisible(run != null);
        header.add(openRun);
    }

    private void fillHero() {
        hero.removeAll();
        HaulModel.Hero best = model.hero();
        if (best == null) return;
        ItemSlot slot = slot(best.item(), HERO);
        slot.setName("loot-haul-hero-slot");
        hero.add(slot);
        KitText caption = KitText.caption("Best drop");
        KitText name = KitText.body(Sprites.name(best.item().id()));
        name.setName("loot-haul-hero-name");
        Line chips = new Line("loot-haul-hero-chips");
        String tier = tierLabel(best.item());
        if (!tier.isEmpty()) chips.add(new Chip(tier, Tokens.Tone.NEUTRAL));
        if (best.item().enchant().enchanted()) chips.add(new Chip(best.item().enchant().rarity().label, Tokens.Tone.ACCENT));
        KitText from = KitText.caption(from(best.bag(), best.dropper(), null));
        from.setName("loot-haul-hero-from");
        hero.add(KitLayouts.stack(0, caption, name, chips, from));
    }

    private void fillShelf() {
        shelf.removeAll();
        List<HaulModel.Shelf> groups = model.shelf();
        for (int i = 0; i < groups.size(); i++) {
            HaulModel.Shelf group = groups.get(i);
            int index = i;
            JToggleButton tile = new JToggleButton("×" + group.bags().size(), BagSprites.sprite(group.bag(), BAG));
            tile.setName("loot-haul-bag");
            tile.putClientProperty(INDEX, index);
            tile.setVerticalTextPosition(SwingConstants.BOTTOM);
            tile.setHorizontalTextPosition(SwingConstants.CENTER);
            tile.setContentAreaFilled(false);
            tile.setFont(Type.caption());
            tile.setBorder(new OpenBorder());
            String label = bagLabel(group.bag()) + ": " + count(group.bags().size(), "bag") + ", " + count(group.items(), "item");
            tile.setToolTipText(label);
            tile.getAccessibleContext().setAccessibleName(label);
            tile.addActionListener(e -> openGroup(index));
            bind(tile, tile::doClick, "ENTER");
            shelf.add(tile);
        }
    }

    private JComponent grid(HaulModel.Bag bag) {
        KitText caption = KitText.caption(from(bag.bag(), bag.dropper(), bag.time()));
        caption.setName("loot-haul-grid-caption");
        JPanel slots = new JPanel(new GridLayout(0, SLOTS_PER_ROW, Tokens.XS, Tokens.XS));
        slots.setName("loot-haul-grid");
        slots.setOpaque(false);
        int cells = Math.max(1, (bag.items().size() + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW) * SLOTS_PER_ROW;
        for (int i = 0; i < cells; i++) {
            if (i < bag.items().size()) { slots.add(slot(bag.items().get(i), SLOT)); continue; }
            ItemSlot blank = new ItemSlot(SLOT);
            blank.setEmpty();
            slots.add(blank);
        }
        Line holder = new Line(null);   // keeps the grid at its preferred width instead of stretching its cells
        holder.add(slots);
        Column block = new Column(null);
        for (JComponent part : new JComponent[] {caption, holder}) { part.setAlignmentX(LEFT_ALIGNMENT); block.add(part); }
        block.setBorder(new EmptyBorder(0, 0, Tokens.S, 0));
        return block;
    }

    private ItemSlot slot(LootFacts.Item item, int size) {
        ItemSlot slot = new ItemSlot(size);
        EnchantInfo enchant = item.enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : item.enchant();
        slot.setItem(item.id(), tierLabel(item), enchant);
        String key = HaulModel.variantKey(item);
        slot.setFocusable(true);
        slot.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onOpenItem.accept(key); }
        });
        bind(slot, () -> onOpenItem.accept(key), "ENTER", "SPACE");
        return slot;
    }

    /** "UT", "ST", the saved tier ("T12"), else the current definitions' label; "" when none is known. */
    static String tierLabel(LootFacts.Item item) {
        if (item.untiered()) return "UT";
        if (item.setTiered()) return "ST";
        if (item.tier() != null) return item.tier();
        return Objects.toString(ItemTiers.label(item.id()), "");
    }

    static String bagLabel(String bag) { return bag == null ? "Bag" : bag + " bag"; }

    /** "White bag · Marble Colossus · 21:52": the bag, then the dropper and time when known. */
    static String from(String bag, String dropper, Long time) {
        List<String> parts = new ArrayList<>();
        parts.add(bagLabel(bag));
        if (dropper != null && !dropper.isBlank()) parts.add(dropper);
        if (time != null && time > 0) parts.add(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(time), DisplayFormat.TimestampMode.TIME));
        return String.join(" · ", parts);
    }

    /** "Wizard #3 · 9:40 PM · 00:14:00": the known facts only. */
    static String facts(HaulModel.Header h) {
        List<String> parts = new ArrayList<>();
        if (h.character() != null && !h.character().isBlank()) parts.add(h.character());
        if (h.entered() != null) parts.add(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(h.entered()), DisplayFormat.TimestampMode.TIME));
        if (h.durationMs() != null) parts.add(DisplayFormat.formatDurationHMS(h.durationMs()));
        return String.join(" · ", parts);
    }

    private static String count(int n, String noun) { return n + " " + noun + (n == 1 ? "" : "s"); }

    private static void bind(JComponent target, Runnable action, String... keys) {
        for (String key : keys) target.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), OPEN);
        target.getActionMap().put(OPEN, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /** A left-to-right row only as tall as its content inside the haul's vertical stack. */
    private static final class Line extends JPanel {
        Line(String name) {
            super(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
            setName(name);
            setOpaque(false);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** A top-to-bottom stack only as tall as its content. */
    private static final class Column extends JPanel {
        Column(String name) {
            setName(name);
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** A bag tile's outline: accent and 2 px when its group is open, a subtle hairline otherwise; colors resolve while painting. */
    private static final class OpenBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component c) { return new Insets(Tokens.XS, Tokens.XS, Tokens.XS, Tokens.XS); }
        @Override public Insets getBorderInsets(Component c, Insets insets) { insets.set(Tokens.XS, Tokens.XS, Tokens.XS, Tokens.XS); return insets; }
        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
            boolean open = c instanceof AbstractButton button && button.isSelected();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Tokens.color(open ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
                g.setStroke(new BasicStroke(open ? 2f : 1f));
                g.drawRoundRect(x + 1, y + 1, width - 3, height - 3, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            } finally {
                g.dispose();
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulViewTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/haul/HaulView.java src/test/java/tomato/gui/loot/haul/HaulViewTest.java
git commit -m "Add the haul view: bag shelf and 8-slot bag grids"
```

---

### Task 5: `HaulView` Full — header, best drop, Open run

**Files:**
- Modify: `src/test/java/tomato/gui/loot/haul/HaulViewTest.java` (add one test)
- Modify: `src/main/java/tomato/gui/loot/haul/HaulView.java` only if the test exposes a defect

**Interfaces:**
- Consumes: everything from Task 4.

- [ ] **Step 1: Add the test** to `HaulViewTest`

```java
    @Test public void fullAddsTheHeaderTheBestDropAndOpenRun() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.FULL);
            List<VisitRef> runs = new ArrayList<>();
            view.onOpenRun(runs::add);
            view.show(model(new HaulModel.Header("Synthetic Halls", 0, "Completed", Tokens.Tone.GOOD, T0, 840_000L, "Wizard #3")), RUN);
            assertTrue(shows(view, "loot-haul-header"));
            assertEquals("Synthetic Halls", text(view, "loot-haul-title"));
            assertTrue(text(view, "loot-haul-facts").startsWith("Wizard #3 · "));
            assertEquals("Completed", text(view, "loot-haul-outcome"));
            assertEquals("4 items in 3 bags · 1 UT · 3 potions", text(view, "loot-haul-tally"));
            assertTrue(shows(view, "loot-haul-hero"));
            assertEquals(101, named(view, "loot-haul-hero-slot", ItemSlot.class).itemId());
            assertEquals("White bag · Synthetic Colossus", text(view, "loot-haul-hero-from"));
            named(view, "loot-haul-open-run", AbstractButton.class).doClick();
            assertEquals(List.of(RUN), runs);

            view.show(model(new HaulModel.Header("Synthetic Halls", 0, null, null, null, null, null)), null);
            assertFalse("No run: no Open run", named(view, "loot-haul-open-run", JComponent.class).isVisible());
            assertFalse("No outcome: no chip", has(view, "loot-haul-outcome"));
            assertFalse("Unknown facts are left out", named(view, "loot-haul-facts", JComponent.class).isVisible());

            view.show(model(null), RUN);
            assertFalse("No header facts: no header", shows(view, "loot-haul-header"));
            assertTrue("The best drop still shows", shows(view, "loot-haul-hero"));
        });
    }
```

- [ ] **Step 2: Run the tests**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulViewTest`
Expected: PASS (5 tests). If one fails, fix `HaulView`, not the test, unless the test contradicts the spec.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/tomato/gui/loot/haul/HaulViewTest.java src/main/java/tomato/gui/loot/haul/HaulView.java
git commit -m "Pin the full haul: header, best drop and Open run"
```

---

### Task 6: Run recap Loot section switches to the Compact haul

**Files:**
- Modify: `src/main/java/tomato/gui/runs/RunRecapModel.java:137-144` (`Loot.Bag` gains `dropper`)
- Modify: `src/main/java/tomato/gui/runs/RunRecapBuilder.java:344`
- Modify: `src/main/java/tomato/gui/runs/RunRecapView.java` (field, constructor near line 206, `applyLoot` at 567-580; delete `bagRow` at 582-614 and `Dot` at 994-1009)
- Modify: `src/test/java/tomato/gui/runs/RunRecapViewTest.java` (lines 83-84, 157-167, 336-338, 397, 407-428)
- Modify: `src/test/java/tomato/gui/runs/RunRecapBuilderTest.java:398`
- Modify: `docs/ACTIVITY.md:57`

**Interfaces:**
- Consumes: `HaulModel.of`, `HaulModel.Bag`, `HaulView(Mode.COMPACT)`, `HaulView.show/model/openGroup` (Tasks 3-4)
- Produces: `RunRecapModel.Loot.Bag(String bag, long time, String dropper, List<LootFacts.Item> items)`

- [ ] **Step 1: Update the tests first**

In `RunRecapBuilderTest.lootAndFameAreJoinedByTheExactVisitOnly`, after line 398, add:

```java
        assertEquals("The bag keeps the enemy that dropped it", "Synthetic boss", loot.bags().get(0).dropper());
```

In `RunRecapViewTest.full(...)` (lines 83-84), give each `Loot.Bag` a dropper:

```java
        RunRecapModel.Loot loot = new RunRecapModel.Loot(List.of(new RunRecapModel.Loot.Bag("White", T0 + 300_000, "Synthetic boss", List.of(new LootFacts.Item(101, true, false, false, false, 2, 0))),
            new RunRecapModel.Loot.Bag("Purple", T0 + 400_000, null, List.of(potion(102), potion(103)))), 3, "1 UT · 2 potions", null);
```

In `theLootLineAddsOnlyNotableKinds` (line 397), change `new RunRecapModel.Loot.Bag("White", T0, List.of(` to `new RunRecapModel.Loot.Bag("White", T0, null, List.of(`.

Replace lines 157-167 (from the `run-recap-loot-summary` assertion through the `run-recap-loot-bag-kinds` assertion) with:

```java
            assertEquals("3 items in 2 bags · 1 UT · 2 potions", text(view, "run-recap-loot-summary"));
            tomato.gui.loot.haul.HaulView haul = named(view, "loot-haul", tomato.gui.loot.haul.HaulView.class);
            assertNull("The recap has its own header", haul.model().header());
            List<String> bags = new ArrayList<>();
            collect(haul, c -> { if ("loot-haul-bag".equals(c.getName())) bags.add(((JComponent) c).getToolTipText()); });
            assertEquals("Most valuable bag first", List.of("White bag: 1 bag, 1 item", "Purple bag: 1 bag, 2 items"), bags);
            assertEquals("The UT's bag opens first", 0, haul.openGroup());
            List<ItemSlot> slots = itemSlots(haul);
            assertEquals(List.of(101), slots.stream().map(ItemSlot::itemId).toList());
            assertEquals(tomato.realmshark.EnchantInfo.ofSlotCount(2), slots.get(0).enchant());
            assertTrue(slots.get(0).getAccessibleContext().getAccessibleName().endsWith("Rare · 2 enchant slots"));
            haul.openGroup(1);
            slots = itemSlots(haul);
            assertEquals(List.of(102, 103), slots.stream().map(ItemSlot::itemId).toList());
            assertNull("A potion shows no enchant line", slots.get(0).enchant());
```

Add this helper next to `texts(...)`:

```java
    /** The item slots holding an item under {@code root}, in order. */
    private static List<ItemSlot> itemSlots(JComponent root) {
        List<ItemSlot> slots = new ArrayList<>();
        collect(root, c -> { if (c instanceof ItemSlot slot && slot.state() == ItemSlot.State.ITEM) slots.add(slot); });
        return slots;
    }
```

Replace lines 336-338 (the "An equal model rebuilds nothing" check) with:

```java
            Component bag = named(view, "loot-haul-bag", JComponent.class);
            view.show(full(REF, RunOutcome.IN_PROGRESS, 1));
            assertSame("An equal model rebuilds nothing", bag, named(view, "loot-haul-bag", JComponent.class));
```

In `itemSlotsLineUpFromRowToRow`:
- Keep the loop that opens both sections, so `nothingSideways` still covers the haul at 680×520 with font 18.
- Change the row table to players only: `for (String[] rows : new String[][] {{"run-recap-player-rows", "run-recap-player"}}) {`
- Change its Javadoc to: `/** Players rows give their labels the section's widest label width, so the item slots line up from row to row; the open Loot haul never scrolls sideways. */`

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE --tests tomato.gui.runs.RunRecapViewTest --tests tomato.gui.runs.RunRecapBuilderTest`
Expected: compilation FAIL (`Loot.Bag` takes 3 arguments; `dropper()` not found).

- [ ] **Step 3: Implement**

`RunRecapModel.Loot.Bag`:

```java
        /** One bag; {@code bag} is its saved bag name ("White", "Orange", …) or null when none was saved; {@code dropper} the enemy that dropped it, or null when unknown. */
        public record Bag(String bag, long time, String dropper, List<LootFacts.Item> items) {
            public Bag { items = List.copyOf(items); }
        }
```

`RunRecapBuilder` line 344:

```java
            bags.add(new RunRecapModel.Loot.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
```

`RunRecapView`:
- Add imports `tomato.gui.loot.haul.HaulModel` and `tomato.gui.loot.haul.HaulView`.
- Add a field next to `lootBags`: `private final HaulView lootHaul = new HaulView(HaulView.Mode.COMPACT);`
- In the constructor, right after `lootSummary.setName("run-recap-loot-summary");`, add `rebuild(lootBags, rows -> rows.add(lootHaul));`
- Replace `applyLoot` with:

```java
    private void applyLoot(RunRecapModel.Loot loot) {
        if (loot.equals(shownLoot)) return;
        shownLoot = loot;
        boolean any = !loot.bags().isEmpty();
        List<HaulModel.Bag> bags = new ArrayList<>();
        for (RunRecapModel.Loot.Bag bag : loot.bags()) bags.add(new HaulModel.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
        HaulModel haul = HaulModel.of(null, bags);
        lootSummary.setText(any ? haul.tally() : " ");
        lootSummary.setVisible(any);
        text(lootReason, any ? null : loot.reason());
        lootHaul.show(haul, null);
        lootBags.setVisible(any);
        title(LOOT, any ? "Loot · " + loot.count() + (loot.count() == 1 ? " item" : " items") : "Loot");
    }
```

- Delete `bagRow(...)` and the nested `Dot` class; nothing else uses them. Leave `SlotColumns`/`SlotRow`, which the Players rows still use. Remove any import that becomes unused.

`docs/ACTIVITY.md` line 57: replace the Loot bullet with:

```markdown
- **Loot** shows this run's bags as the game draws them: one bag sprite per bag type with its count, most valuable first (white first). The bag holding the best drop starts open as an 8-slot grid; click a bag, or press Enter on it, to open it instead (one grid per bag, newest first). Each grid is headed by its bag, the enemy that dropped it when known, and the drop time.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.runs.RunRecapViewTest --tests tomato.gui.runs.RunRecapBuilderTest --tests 'tomato.gui.loot.haul.*' --tests tomato.gui.kit.BagSpritesTest`
Expected: PASS. If a recap failure looks unrelated to Loot, compare it with `main` before treating it as a regression; see the known desktop window-size failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/runs/RunRecapModel.java src/main/java/tomato/gui/runs/RunRecapBuilder.java src/main/java/tomato/gui/runs/RunRecapView.java src/test/java/tomato/gui/runs/RunRecapViewTest.java src/test/java/tomato/gui/runs/RunRecapBuilderTest.java docs/ACTIVITY.md
git commit -m "Show the run recap's loot as a compact haul of bag sprites and grids"
```

---

### Task 7: Focused verification and launch smoke check (coordinator)

**Files:** none.

- [ ] **Step 1: Run the focused suite**

Run: `GRADLE --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.stats.*'`
Expected: PASS, apart from failures that already fail on `main` (the known window-size and evidence tests). List any others and fix them before continuing.

- [ ] **Step 2: Build the jar and launch**

Run `GRADLE shadowJar`, but replace `test --tests <TEST>` with `shadowJar` in that command. Launch the jar from the usual folder that holds saved history (assets and history are relative to the launch folder). Do not start live capture.

Open **Runs**, then a run with loot, and check that the Loot section shows:
- bag sprites with "×N", white first;
- the open bag's 8-slot grid;
- that clicking another bag opens it;
- that nothing scrolls sideways at a narrow window width.

- [ ] **Step 3: Open the PR** titled "Loot Explore pictures P1: haul foundation and run recap". Link the spec and this plan in the body, and end it with the Claude Code attribution line.
