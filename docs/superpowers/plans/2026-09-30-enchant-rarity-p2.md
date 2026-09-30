# Enchant Rarity Phase 2 (Loot) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every loot surface that shows a rolled item shows its enchant rarity gem and, on hover, its rolled enchants: Loot Highlights, run-recap loot bags, run cards, the Loot Dashboard tables and the Loot Archive detail. Past drops gain names from the enchant IDs they already saved; older records that saved only a slot count show their rarity.

**Architecture:**
- `EnchantInfo` gains a `COUNT_ONLY` state for records that know the rarity but not the enchant IDs.
- `LootDashboard.Item` gets a derived `enchantInfo()`, which is never persisted.
- `LootFacts.Item` carries that `EnchantInfo` to Highlights, run cards and the run recap.
- The Loot Dashboard wraps its cached icons in a new kit `ItemIcon`, and `StatsUi` tables read that icon's tooltip on hover.
- The Loot Archive detail lists enchant names.

**Tech Stack:** Java 17 (`--release 17`), Swing/FlatLaf, JUnit 4, Gradle 7.6.4 wrapper, Gson (`SessionStore.JSON`).

**Spec:** `docs/superpowers/specs/2026-09-30-equipment-enchant-rarity-design.md`. This plan covers the Phase 2 section plus Architecture, Compatibility rules, Error handling and Testing. Phase 1 (PR #32, `eaa4050`) delivered `EnchantInfo`, `EnchantGem`, `EnchantTooltip`, `EnchantIconLabel` and `ItemSlot.setItem(int, String, EnchantInfo)`.

**Deliberate refinements of the spec (same intent, smaller or more honest):**
- **Rarity-only records.** Old records that kept only a slot count read "`<Rarity> · N enchant slots`" plus **"Enchant names not available"**. The spec said "not recorded". The same wording also serves merged dashboard rows, where names exist but differ from drop to drop.
- **Run cards get the gem only.** A run card is one painted list cell, and per-item hover would need hit-testing inside it. The card keeps its tooltip, and each item's enchant tooltip is one click away in the run recap.
- **Merged dashboard rows.** A Loot Dashboard row can merge several drops with the same item, slot count and applied count. Those rows (count > 1) show the rarity gem and a rarity-only tooltip headed "`<name> · N drops`". Only single-drop rows list enchant names.
- **Table cells use a kit `ItemIcon`.** It is a decorated `Icon` that carries its tooltip, and `StatsUi` tables ask the icon for it on hover. This avoids building HTML for every cell during rendering.

## How to run things

All commands run from the repository root in Git Bash. Gradle needs the project-local JDK and Gradle home exported in the same command, plus `--offline`:

```bash
export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests <pattern>
```

If Gradle reports failures from classes that no longer exist, or cannot delete build outputs, run `clean` with the same environment and retry. If builds fail with `AccessDeniedException` on `.tools/gradle-home`, first kill any `java.exe` owned by `CodexSandboxOffline`.

Work on branch `feat/enchant-rarity-p2`, which was created from `main` after Phase 1 merged (`eaa4050`) and whose first commit is this plan:

```bash
git switch feat/enchant-rarity-p2
```

## Global Constraints

- Java 17, `--release 17`; no new dependencies.
- **Rarity is the unlocked slot count**: 0 Unenchanted, 1 Uncommon, 2 Rare, 3 Legendary, 4 Divine.
- **Summary strings verbatim:**
  - `"Unenchanted"`
  - `"<Rarity> · 1 enchant slot"` / `"<Rarity> · N enchant slots"`
  - `"Enchants not recorded"`
  - `"Enchant data unreadable"`
- **Slot lines:** `"<Display name> — <Description>"`, `"(empty slot)"`, and for rarity-only data `"Enchant names not available"`.
- **Gems:** colors come from `Tokens.rarity`. The gem sits top-right inside the well edge and the bag/tier border keeps its meaning.
  - After `Sprites.paintWell(owner, g, sprite, bag, x, y, S)`, paint the gem with `EnchantGem.paint(g, info, x, y, S - 1)`, because `paintWell`'s `S` covers an S px square while `EnchantGem.paint` expects `side + 1` px.
- **No history format changes.**
  - Do not add non-transient fields to `LootDashboard.Item`, `LootDashboard.Drop` or `ParseEnchants.Evidence`; they are Gson-serialized as-is.
  - New behavior is derived at read time from `enchantEvidence` / `enchants`.
  - Gson allocates these classes without running constructors, so any of their fields can be null.
- **Unchanged meanings:**
  - The "Enchanted" highlight kind still means Rare or better (`LootFacts.Item.enchanted()`, 2+ slots).
  - `LootQuery` rarity columns and facets stay as they are.
  - CSV export columns stay as they are.
- Do not add fields or methods to `Damage`, `StatData` or `Equipment`.
- Swing work stays on the EDT. Decoding never throws into UI code. Tooltip HTML is built on hover, never per paint.
- Never start live capture or send bridge deliveries. No force pushes, hook bypasses or commits to `main`.
- End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU
  ```

## Review Focus

1. **Partial or hand-edited evidence in saved loot.** Evidence can arrive with a null `state` or null `orderedSlotIds`. It must never throw; it falls back to the saved slot count or "not recorded". Pinned in Tasks 1 and 2.
2. **Legacy record `{"slots":3,"applied":-1}` with no evidence.** It must show a Legendary gem and "Enchant names not available", not "Enchants not recorded". Pinned in Task 2.
3. **Merged dashboard rows.** Several drops can share id, slots and applied but have different enchant IDs. They must not show one drop's names as if every drop had them. Pinned in Task 5.
4. **StatsUi tooltip escaping.** `ItemIcon` HTML must reach the user unescaped, while every other cell stays escaped. Pinned in Task 5.
5. **Icon cache identity.** `LootDashboard.iconForItem` must keep returning the same cached icon (`LootIconGenerationTest`) while rows show gems. Pinned in Task 5.

---

### Task 1: `EnchantInfo` rarity-only state and hardening

**Files:**
- Modify: `src/main/java/tomato/realmshark/EnchantInfo.java`
- Modify: `src/main/java/tomato/gui/kit/EnchantTooltip.java`
- Test: `src/test/java/tomato/realmshark/EnchantInfoTest.java`, `src/test/java/tomato/gui/kit/EnchantTooltipTest.java`

**Interfaces:**
- Produces: `EnchantInfo.State.COUNT_ONLY`; `public static EnchantInfo ofSlotCount(Integer unlocked)`; `public static final String NAMES_NOT_AVAILABLE = "Enchant names not available"`. `fromEvidence` never throws on partial evidence. `enchanted()` is true for `COUNT_ONLY` Uncommon–Divine, so `EnchantGem` paints its gem with no kit change.

- [ ] **Step 1: Write the failing tests**

Append to `EnchantInfoTest` (it already has the `encode(int...)` helper):

```java
    @Test public void aSlotCountAloneGivesTheRarityWithoutNames() {
        EnchantInfo legendary = EnchantInfo.ofSlotCount(3);
        assertEquals(State.COUNT_ONLY, legendary.state());
        assertEquals(Rarity.LEGENDARY, legendary.rarity());
        assertEquals(List.of(), legendary.slots());
        assertTrue("A known rarity shows its gem", legendary.enchanted());
        assertEquals("Legendary · 3 enchant slots", legendary.summary());
        assertEquals(List.of(EnchantInfo.NAMES_NOT_AVAILABLE), legendary.slotLines());
        assertEquals("Legendary · 3 enchant slots\n  Enchant names not available", legendary.text());
        assertEquals("Uncommon · 1 enchant slot", EnchantInfo.ofSlotCount(1).summary());
        EnchantInfo none = EnchantInfo.ofSlotCount(0);
        assertEquals("Unenchanted", none.summary());
        assertEquals("Nothing to name on an unenchanted item", List.of(), none.slotLines());
        assertFalse(none.enchanted());
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofSlotCount(null));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofSlotCount(-1));
        assertSame(EnchantInfo.unreadable(), EnchantInfo.ofSlotCount(5));
    }

    @Test public void partialEvidenceNeverThrows() throws Exception {
        ParseEnchants.Evidence noState = tomato.history.SessionStore.JSON.fromJson("{\"slots\":2,\"applied\":1}", ParseEnchants.Evidence.class);
        assertSame("No state: not recorded", EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(noState));
        ParseEnchants.Evidence noIds = tomato.history.SessionStore.JSON.fromJson("{\"state\":\"RECORDED\",\"slots\":2,\"applied\":1}", ParseEnchants.Evidence.class);
        EnchantInfo fallback = EnchantInfo.fromEvidence(noIds);
        assertEquals("No ordered ids: the saved slot count still gives the rarity", State.COUNT_ONLY, fallback.state());
        assertEquals(Rarity.RARE, fallback.rarity());
    }
```

Append to `EnchantTooltipTest`:

```java
    @Test public void aRarityOnlyTooltipSaysTheNamesAreNotAvailable() {
        String html = EnchantTooltip.html("Old Bow", EnchantInfo.ofSlotCount(2));
        assertTrue(html, html.contains("Rare · 2 enchant slots"));
        assertTrue(html, html.contains("Enchant names not available"));
        assertFalse("Unenchanted needs no names line", EnchantTooltip.html("Old Bow", EnchantInfo.ofSlotCount(0)).contains("not available"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.EnchantInfoTest --tests tomato.gui.kit.EnchantTooltipTest`
Expected: FAIL at compile time: `cannot find symbol ... ofSlotCount / COUNT_ONLY / NAMES_NOT_AVAILABLE`.

- [ ] **Step 3: Implement**

In `EnchantInfo.java`:
- Change `public enum State { RECORDED, NOT_RECORDED, UNREADABLE }` to:

```java
    /** COUNT_ONLY: the unlocked slot count (so the rarity) is known but not which enchantments were rolled. */
    public enum State { RECORDED, COUNT_ONLY, NOT_RECORDED, UNREADABLE }
```

- Below the `UNREADABLE` constant add:

```java
    /** The one line a rarity-only record shows in place of its slots. */
    public static final String NAMES_NOT_AVAILABLE = "Enchant names not available";

    /**
     * A record that saved only its unlocked slot count (older loot history, or a merged row of several drops): the rarity without
     * enchant names. Null or negative is not recorded; more than four is unreadable.
     */
    public static EnchantInfo ofSlotCount(Integer unlocked) {
        if (unlocked == null || unlocked < 0) return NOT_RECORDED;
        if (unlocked > 4) return UNREADABLE;
        return new EnchantInfo(State.COUNT_ONLY, Rarity.ofSlots(unlocked), List.of());
    }
```

- In `fromEvidence`, replace the first line `if (evidence == null) return NOT_RECORDED;` with:

```java
        // Saved evidence is Gson-read without constructors, so a partial or hand-edited record may lack any field.
        if (evidence == null || evidence.state == null) return NOT_RECORDED;
```

  and directly after the `switch` (before building `unlocked`) add:

```java
        if (evidence.orderedSlotIds == null) return ofSlotCount(evidence.slots);
```

- Replace `enchanted()` with:

```java
    /** True when there is a rarity to show: recorded or counted, with at least one unlocked slot. */
    public boolean enchanted() {
        return (state == State.RECORDED || state == State.COUNT_ONLY) && rarity != Rarity.UNENCHANTED && rarity != Rarity.UNKNOWN;
    }
```

- Replace the `default:` branch of `summary()` with:

```java
            default: {
                // Rarity constants are declared in slot-count order, so a count-only record's count is its rarity's ordinal.
                int count = state == State.COUNT_ONLY ? rarity.ordinal() : slots.size();
                return rarity == Rarity.UNENCHANTED ? "Unenchanted"
                    : rarity.label + " · " + count + (count == 1 ? " enchant slot" : " enchant slots");
            }
```

- At the top of `slotLines()` add:

```java
        if (state == State.COUNT_ONLY) return rarity == Rarity.UNENCHANTED ? List.of() : List.of(NAMES_NOT_AVAILABLE);
```

In `EnchantTooltip.html`, directly after the `for (EnchantInfo.Slot slot : info.slots()) { ... }` loop, add:

```java
        if (info.state() == EnchantInfo.State.COUNT_ONLY && info.rarity() != EnchantInfo.Rarity.UNENCHANTED)
            html.append("<br><span style='color:").append(muted).append("'>").append(EnchantInfo.NAMES_NOT_AVAILABLE).append("</span>");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.gui.kit.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/realmshark/EnchantInfo.java src/main/java/tomato/gui/kit/EnchantTooltip.java src/test/java/tomato/realmshark/EnchantInfoTest.java src/test/java/tomato/gui/kit/EnchantTooltipTest.java
git commit -m "Let EnchantInfo show a rarity from a saved slot count alone" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 2: Loot items carry their `EnchantInfo`

**Files:**
- Modify: `src/main/java/tomato/gui/stats/LootDashboard.java` (`Item`, lines ~486-516)
- Modify: `src/main/java/tomato/gui/stats/LootFacts.java` (`Item` record lines 25-37; `bag(...)` line 94)
- Test: `src/test/java/tomato/gui/stats/LootFactsTest.java`

**Interfaces:**
- Consumes: `EnchantInfo.fromEvidence`, `EnchantInfo.ofSlotCount` (Task 1).
- Produces:
  - Package-private `EnchantInfo LootDashboard.Item.enchantInfo()`. It is derived and never persisted.
  - `LootFacts.Item` becomes `Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied, EnchantInfo enchant)`. A null `enchant` defaults to `EnchantInfo.ofSlotCount(slots)`.
  - The existing 7- and 5-argument constructors keep working and default `enchant` the same way.

- [ ] **Step 1: Write the failing test**

Add to `LootFactsTest` (it has `enchants(int...)` and `temp`), plus `import tomato.realmshark.EnchantInfo;`:

```java
    @Test public void itemsCarryExactEnchantsWhenCapturedAndTheRarityForOlderRecords() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId();
            String rare = enchants(-1, 42);
            store.append("loot", new LootDashboard.Drop("White", "Lost Halls", "Boss", 1_000, List.of(
                new LootDashboard.Item(10, "Item #10", "EQUIPMENT,WEAPON,T12", ParseEnchants.evidence(rare)))));
            String item = "{\"id\":%d,\"name\":\"Legacy\",\"tier\":\"T4\",\"potion\":false,\"ut\":false,\"st\":false,\"highTier\":false%s}";
            store.append("loot", SessionStore.JSON.fromJson("{\"bag\":\"Brown\",\"dungeon\":\"Lost Halls\",\"dropper\":\"Boss\",\"time\":2000,\"items\":["
                + String.format(item, 11, ",\"enchants\":{\"slots\":3,\"applied\":-1}") + ","
                + String.format(item, 12, "") + ","
                + String.format(item, 13, ",\"enchants\":{\"slots\":2,\"applied\":1},\"enchantEvidence\":{\"slots\":2,\"applied\":1}") + "]}",
                LootDashboard.Drop.class));
            store.flush();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, id, bags::add);
            assertEquals("Captured evidence: the exact slots", EnchantInfo.of(rare), bags.get(0).items().get(0).enchant());
            EnchantInfo legacy = bags.get(1).items().get(0).enchant();
            assertEquals("A saved slot count alone: the rarity", EnchantInfo.State.COUNT_ONLY, legacy.state());
            assertEquals(EnchantInfo.Rarity.LEGENDARY, legacy.rarity());
            assertSame("Nothing saved: not recorded", EnchantInfo.notRecorded(), bags.get(1).items().get(1).enchant());
            EnchantInfo partial = bags.get(1).items().get(2).enchant();
            assertEquals("Evidence without a state falls back to the saved count, never throws", EnchantInfo.Rarity.RARE, partial.rarity());
        }
    }
```

In `theLiveProjectionFillsTheSameFacts` (line ~142), the expected item was built from evidence, so pass it explicitly:

```java
        assertEquals(new LootFacts.Item(20, false, true, false, false, 4, 3, EnchantInfo.of(enchants(-1, 42, 7, 0))), bag.items().get(0));
```

The other `LootFactsTest` expectations stay unchanged. `LootTestDrops` saves `ParseEnchants.summarize("")` without evidence, and potions save no enchant data, so those items still equal their 7-argument forms.

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.stats.LootFactsTest`
Expected: FAIL at compile time: `cannot find symbol ... enchant()` / no 8-argument constructor.

- [ ] **Step 3: Implement**

In `LootDashboard.Item` (after `description()`), add `import tomato.realmshark.EnchantInfo;` at the top of the file, then:

```java
        /**
         * This item's enchantments for display: exact when its evidence was captured, the rarity alone for older records that
         * saved only a slot count. Derived on every call from saved fields (Gson-read items may lack any of them); never persisted.
         */
        EnchantInfo enchantInfo() {
            if (enchantEvidence != null && enchantEvidence.state != null
                    && enchantEvidence.state != ParseEnchants.EvidenceState.LEGACY_NOT_RECORDED)
                return EnchantInfo.fromEvidence(enchantEvidence);
            return EnchantInfo.ofSlotCount(enchants == null ? null : enchants.slots);
        }
```

In `LootFacts.java`, add `import tomato.realmshark.EnchantInfo;` and change the `Item` record header and its constructors:

```java
    public record Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied,
                       EnchantInfo enchant) {
        public Item {
            if (slots != null && slots < 0) slots = null;   // the legacy -1: unknown
            if (applied != null && applied < 0) applied = null;
            // No enchantments given: the rarity the slot count implies (not recorded when it is unknown).
            if (enchant == null) enchant = EnchantInfo.ofSlotCount(slots);
        }

        public Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion, Integer slots, Integer applied) {
            this(id, untiered, setTiered, highTier, potion, slots, applied, null);
        }
```

Keep the existing 5-argument constructor and its comment unchanged, and merge in any existing comment or normalization lines of the compact constructor. Keep `enchantKnown()` and `enchanted()` unchanged.

In `bag(...)`, change line 94 to pass the derived enchantments:

```java
            items.add(new Item(item.id, item.ut, item.st, item.highTier, item.potion, LootQuery.slots(item), LootQuery.applied(item), item.enchantInfo()));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.stats.*' --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*'`
Expected: PASS. If an existing test compares a `LootFacts.Item` built from captured evidence against a 7-argument expectation, give the expectation `EnchantInfo.of(<the same blob>)` as its 8th argument. Record each such change in the report.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/stats/LootDashboard.java src/main/java/tomato/gui/stats/LootFacts.java src/test/java/tomato/gui/stats/LootFactsTest.java
git commit -m "Carry each loot item's enchantments from its saved evidence or slot count" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 3: Loot Highlights show the gem and enchant tooltip

**Files:**
- Modify: `src/main/java/tomato/gui/loot/HighlightsModel.java` (`Notable` record line 75; construction line 151)
- Modify: `src/main/java/tomato/gui/loot/NotableDropRenderer.java` (`accessibleName` lines 84-94; tooltip line 145; paint line 163)
- Test: `src/test/java/tomato/gui/loot/NotableDropRendererTest.java`, `src/test/java/tomato/gui/loot/HighlightsModelTest.java`

**Interfaces:**
- Consumes: `LootFacts.Item.enchant()` (Task 2); `EnchantGem.paint`, `EnchantTooltip.html` (Phase 1).
- Produces: `HighlightsModel.Notable(int itemId, String bag, String dungeon, long time, VisitRef visit, Kind kind, EnchantInfo enchant)`, plus the old 6-argument constructor, which gives `EnchantInfo.notRecorded()`. `key()` is unchanged.

- [ ] **Step 1: Write the failing tests**

In `NotableDropRendererTest` add (reuse its `ZONE_NY`, `NOON`, `RUN` constants; import `tomato.realmshark.EnchantInfo`, `tomato.gui.kit.Tokens`, `java.awt.image.BufferedImage`):

```java
    private static HighlightsModel.Notable enchanted(EnchantInfo enchant) {
        return new HighlightsModel.Notable(4242, "White", "Lost Halls", NOON, RUN, HighlightsModel.Kind.ENCHANTED, enchant);
    }

    @Test public void anEnchantedDropSaysItsRarityAndShowsTheEnchantTooltipOnHover() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnchantInfo legendary = EnchantInfo.ofSlotCount(3);
            HighlightsModel.Notable drop = enchanted(legendary);
            String name = NotableDropRenderer.accessibleName(drop, ZONE_NY, NOON);
            assertTrue(name, name.contains("enchanted, rare or better (Legendary · 3 enchant slots)"));
            NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
            renderer.getListCellRendererComponent(new JList<>(), drop, 0, false, false);
            String tip = renderer.getToolTipText();
            assertTrue(tip, tip.startsWith("<html>") && tip.contains("Legendary · 3 enchant slots") && tip.contains("Enchant names not available"));
            String plain = NotableDropRenderer.accessibleName(enchanted(EnchantInfo.notRecorded()), ZONE_NY, NOON);
            assertFalse("Without enchant data the name adds no rarity: " + plain, plain.contains("enchant slot"));
            renderer.getListCellRendererComponent(new JList<>(), enchanted(EnchantInfo.notRecorded()), 0, false, false);
            assertEquals("…and the tooltip stays the plain facts", plain + " · " + HighlightsModel.OBSERVED, renderer.getToolTipText());
        });
    }

    @Test public void theGemIsPaintedInsideTheWellsTopRightCorner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
            renderer.getListCellRendererComponent(new JList<>(), enchanted(EnchantInfo.notRecorded()), 0, false, false);
            Dimension size = renderer.getPreferredSize();
            BufferedImage plain = image(renderer, size);
            renderer.getListCellRendererComponent(new JList<>(), enchanted(EnchantInfo.ofSlotCount(3)), 0, false, false);
            BufferedImage gem = image(renderer, size);
            Rectangle well = NotableDropRenderer.well(size.width, size.height);
            int ink = Tokens.rarity(EnchantInfo.Rarity.LEGENDARY).getRGB(), changed = 0; boolean inked = false;
            for (int y = 0; y < size.height; y++) for (int x = 0; x < size.width; x++) {
                if (plain.getRGB(x, y) == gem.getRGB(x, y)) continue;
                changed++; inked |= gem.getRGB(x, y) == ink;
                assertTrue("Changes stay inside the well's top-right quarter at " + x + "," + y,
                    x > well.x + well.width / 2 && x < well.x + well.width - 1 && y > well.y && y < well.y + well.height / 2);
            }
            assertTrue("The gem is painted", changed > 8);
            assertTrue("…in the Legendary ink", inked);
        });
    }

    private static BufferedImage image(JComponent c, Dimension size) {
        c.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); c.paint(g); g.dispose();
        return image;
    }
```

Use the constructor and `getListCellRendererComponent` call the existing tests in this file use. If the renderer's constructor differs, copy the existing test's construction line. `well(...)` is package-private; make it `static` if it is not.

In `HighlightsModelTest`, add an assertion to the test that checks ENCHANTED kinds (line ~58). A model built from `HighlightsFixture.item(id, …, 2)` must yield a notable whose `enchant()` is `EnchantInfo.ofSlotCount(2)`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.loot.NotableDropRendererTest --tests tomato.gui.loot.HighlightsModelTest`
Expected: FAIL at compile time: no 7-argument `Notable` constructor / `enchant()`.

- [ ] **Step 3: Implement**

`HighlightsModel.java`: add `import tomato.realmshark.EnchantInfo;`. Replace the `Notable` record with:

```java
    public record Notable(int itemId, String bag, String dungeon, long time, VisitRef visit, Kind kind, EnchantInfo enchant) {
        public Notable {
            if (enchant == null) enchant = EnchantInfo.notRecorded();
        }
        public Notable(int itemId, String bag, String dungeon, long time, VisitRef visit, Kind kind) {
            this(itemId, bag, dungeon, time, visit, kind, null);
        }
        public String key() { return time + "/" + itemId + "/" + bag + "/" + visit + "/" + kind; }
    }
```

Keep the existing Javadoc on the record. At line 151, pass the item's enchantments:

```java
            if (kind != null) listed.add(new Notable(item.id(), bag.bag(), area, bag.time(), bag.visit(), kind, item.enchant()));
```

`NotableDropRenderer.java`: add imports `tomato.gui.kit.EnchantGem`, `tomato.gui.kit.EnchantTooltip`, `tomato.realmshark.EnchantInfo`.
- In `accessibleName`, after the `kind` switch, add the rarity to the kind words:

```java
        if (drop.enchant().state() != EnchantInfo.State.NOT_RECORDED) kind += " (" + drop.enchant().summary() + ")";
```

  and update its Javadoc example to mention "(Rare · 2 enchant slots)" for enchanted drops.
- In `paintComponent`, directly after the `Sprites.paintWell(...)` call:

```java
            // paintWell's side covers side px; the gem painter takes ItemSlot's side + 1 convention.
            EnchantGem.paint(g, drop.enchant(), well.x, well.y, well.width - 1);
```

- Add the lazy tooltip override:

```java
    /** Built when the list asks (on hover): the drop's facts, then its enchant lines when it has enchant data. */
    @Override public String getToolTipText() {
        String facts = super.getToolTipText();
        if (facts == null || drop == null || drop.enchant().state() == EnchantInfo.State.NOT_RECORDED) return facts;
        return EnchantTooltip.html(facts, drop.enchant());
    }
```

Existing tests that build `Notable`s with the 6-argument constructor keep their exact tooltip and name expectations, because those notables are not recorded. Tests that go through `HighlightsFixture.item(..., slots)` now carry `ofSlotCount(slots)`. Where they assert exact accessible names of enchanted drops, append the rarity words as the renderer now produces them, and note each change in the report.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.loot.*'`
Expected: PASS (compare any window-size/evidence failure against `main`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/HighlightsModel.java src/main/java/tomato/gui/loot/NotableDropRenderer.java src/test/java/tomato/gui/loot
git commit -m "Show the rarity gem and enchant tooltip on Loot Highlights" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 4: Run cards and run-recap loot bags

**Files:**
- Modify: `src/main/java/tomato/gui/runs/RunCardModel.java` (`LootItem` line 79; `loot(...)` line 130)
- Modify: `src/main/java/tomato/gui/runs/RunCardRenderer.java` (loot wells lines 266-271)
- Modify: `src/main/java/tomato/gui/runs/RunRecapView.java` (loot slots lines 596-600)
- Test: `src/test/java/tomato/gui/runs/RunFeedModelTest.java`, `src/test/java/tomato/gui/runs/RunCardRendererTest.java`, `src/test/java/tomato/gui/runs/RunRecapViewTest.java`

**Interfaces:**
- Consumes: `LootFacts.Item.enchant()` (Task 2); `EnchantGem.paint`; `ItemSlot.setItem(int, String, EnchantInfo)`.
- Produces: `RunCardModel.LootItem(int id, String bag, String tier, EnchantInfo enchant)`, plus a 3-argument constructor that gives `EnchantInfo.notRecorded()`.

- [ ] **Step 1: Write the failing tests**

`RunFeedModelTest` (it has `SESSION`, `NOW`, `MINUTE`, `bag(...)`, `potion(...)`), plus `import tomato.realmshark.EnchantInfo;`:

```java
    @Test public void runCardLootCarriesEachItemsEnchantments() {
        VisitRef ref = new VisitRef(SESSION, "v1");
        LootFacts.Item rare = new LootFacts.Item(7, false, false, false, false, 2, 1);
        RunCardModel card = RunCardModel.of(ref, "Lost Halls", RunOutcome.COMPLETED, NOW, MINUTE, null, 0, List.of(),
            List.of(bag("White", ref, rare, potion(8))), 1_240L);
        java.util.Map<Integer, EnchantInfo> byId = card.loot().stream()
            .collect(Collectors.toMap(RunCardModel.LootItem::id, RunCardModel.LootItem::enchant));
        assertEquals(EnchantInfo.ofSlotCount(2), byId.get(7));
        assertSame("A potion has no enchant data", EnchantInfo.notRecorded(), byId.get(8));
    }
```

`RunCardRendererTest` (it has `REF`, `ZONE`, `NOW`, `MINUTE`, `at(...)` and `linked()`), plus imports `tomato.realmshark.EnchantInfo`, `tomato.gui.kit.Tokens`, `java.util.ArrayList`:

```java
    @Test public void enchantedLootPaintsItsRarityGemAndKeepsTheCardsWords() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunCardModel plain = linked();
            List<RunCardModel.LootItem> loot = new ArrayList<>(plain.loot());
            RunCardModel.LootItem first = loot.get(0);
            loot.set(0, new RunCardModel.LootItem(first.id(), first.bag(), first.tier(), EnchantInfo.ofSlotCount(3)));
            // linked()'s arguments with the enchanted loot list.
            RunCardModel gem = new RunCardModel(REF, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, at(0, 8, 5), 25 * MINUTE, 6,
                new RunCardModel.Combat("r-v1-long", 2, 6_000L, 20d, 2, 6, 30d, 1, null), null, loot, 6, "1 UT · 1 ST · 2 potions", null, 240L, 2);
            assertEquals("The card's words are unchanged", RunCardRenderer.accessibleName(plain, ZONE, NOW), RunCardRenderer.accessibleName(gem, ZONE, NOW));
            BufferedImage before = card(plain), after = card(gem);
            int ink = Tokens.rarity(EnchantInfo.Rarity.LEGENDARY).getRGB(), changed = 0; boolean inked = false;
            for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++)
                if (before.getRGB(x, y) != after.getRGB(x, y)) { changed++; inked |= after.getRGB(x, y) == ink; }
            assertTrue("The gem is painted", changed > 8);
            assertTrue("…in the Legendary ink", inked);
        });
    }

    private static BufferedImage card(RunCardModel model) {
        RunCardRenderer renderer = new RunCardRenderer(ZONE, () -> NOW);
        Component painted = renderer.getListCellRendererComponent(new JList<>(), model, 0, false, false);
        Dimension size = renderer.getPreferredSize();
        painted.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { painted.paint(g); } finally { g.dispose(); }
        return image;
    }
```

`RunRecapViewTest`: in the model fixture at line ~82, change the White bag's item `ut(101)` to `new LootFacts.Item(101, true, false, false, false, 2, 0)`. It is still a UT, so the "1 UT" kinds text is unchanged. In the loot assertions (after line ~158, where it collects `ItemSlot`s), add:

```java
            List<ItemSlot> slots = new ArrayList<>();
            collect(named(view, "run-recap-loot-bags", JComponent.class), c -> { if (c instanceof ItemSlot) slots.add((ItemSlot) c); });
            assertEquals(tomato.realmshark.EnchantInfo.ofSlotCount(2), slots.get(0).enchant());
            assertTrue(slots.get(0).getAccessibleContext().getAccessibleName().endsWith("Rare · 2 enchant slots"));
            assertNull("A potion shows no enchant line", slots.get(1).enchant());
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.runs.RunFeedModelTest --tests tomato.gui.runs.RunCardRendererTest --tests tomato.gui.runs.RunRecapViewTest`
Expected: FAIL at compile time: no 4-argument `LootItem` / `enchant()`. The run recap assertion fails at runtime (no enchant on the slot).

- [ ] **Step 3: Implement**

`RunCardModel.java`: add `import tomato.realmshark.EnchantInfo;` and replace line 79 with:

```java
    public record LootItem(int id, String bag, String tier, EnchantInfo enchant) {
        public LootItem {
            if (enchant == null) enchant = EnchantInfo.notRecorded();
        }
        public LootItem(int id, String bag, String tier) { this(id, bag, tier, null); }
    }
```

At line 130: `items.add(new LootItem(item.id(), bag.bag(), tier, item.enchant()));`

`RunCardRenderer.java`: add `import tomato.gui.kit.EnchantGem;`. Inside the loot loop, directly after `Sprites.paintWell(this, g, Sprites.sprite(item.id(), LOOT), item.bag(), slotX, row + strip.wellTop(), well);`:

```java
                    EnchantGem.paint(g, item.enchant(), slotX, row + strip.wellTop(), well - 1);
```

`RunRecapView.java`, in the loot loop at lines 596-600, pass the item's enchantments. Pass none when nothing was recorded, so potions and old items read as before rather than "Enchants not recorded":

```java
                EnchantInfo enchant = item.enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : item.enchant();
                slot.setItem(item.id(), item.untiered() ? "UT" : item.setTiered() ? "ST" : ItemTiers.label(item.id()), enchant);
```

(add `import tomato.realmshark.EnchantInfo;`).

The run-recap row's accessible name (lines 608-609) is unchanged. The `loot.equals(shownLoot)` skip still works, because `LootFacts.Item` is a record whose `EnchantInfo` is a record.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.runs.*' --tests 'tomato.gui.glance.home.*'`
Expected: PASS (compare any window-size/evidence failure against `main`). If an existing run-recap test asserts an exact `ItemSlot` tooltip or accessible name for an item with known slots, append the enchant summary as `ItemSlot` now produces it, and note the change.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/runs/RunCardModel.java src/main/java/tomato/gui/runs/RunCardRenderer.java src/main/java/tomato/gui/runs/RunRecapView.java src/test/java/tomato/gui/runs
git commit -m "Show rarity gems on run cards and enchant tooltips in run-recap loot bags" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 5: Loot Dashboard icon column (kit `ItemIcon`, `StatsUi` tooltip)

**Files:**
- Create: `src/main/java/tomato/gui/kit/ItemIcon.java`
- Modify: `src/main/java/tomato/gui/stats/StatsUi.java` (`table(...)` `getToolTipText` lines 86-96)
- Modify: `src/main/java/tomato/gui/stats/LootDashboard.java` (rows lines 421-429)
- Test: create `src/test/java/tomato/gui/kit/ItemIconTest.java`; create `src/test/java/tomato/gui/stats/LootDashboardEnchantTest.java`

**Interfaces:**
- Consumes: `LootDashboard.Item.enchantInfo()` (Task 2); `EnchantInfo.ofSlotCount` (Task 1); `EnchantGem.paint`, `EnchantTooltip.html`.
- Produces:
  - `public final class ItemIcon implements Icon`, constructed as `ItemIcon(Icon base, String heading, EnchantInfo enchant)`, with methods `base()`, `enchant()` and `tooltip()`.
  - Package-private `static ItemIcon LootDashboard.rowIcon(Icon base, String name, int count, LootDashboard.Item item)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/tomato/gui/kit/ItemIconTest.java`:

```java
package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

/** A table-cell item icon: the base sprite, the rarity gem over its corner, and the shared tooltip built when asked for. */
public class ItemIconTest {
    private static final EnchantInfo RARE = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE,
        List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void paintsTheBaseThenTheGemAndKeepsTheBasesSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = new Solid(24);
            ItemIcon icon = new ItemIcon(base, "Doom Bow", RARE);
            assertSame(base, icon.base());
            assertEquals(24, icon.getIconWidth()); assertEquals(24, icon.getIconHeight());
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics(); icon.paintIcon(new JLabel(), g, 0, 0); g.dispose();
            int side = 23, gem = EnchantGem.size(side);
            assertEquals(Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB(), image.getRGB(side - 2 - gem + gem / 2, 2 + gem / 2));
            assertEquals("The base shows elsewhere", Solid.INK.getRGB(), image.getRGB(4, 20));
        });
    }

    @Test public void theTooltipIsBuiltWhenAskedAndFollowsTheTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemIcon icon = new ItemIcon(new Solid(24), "Doom Bow", RARE);
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                String hex = String.format("#%06x", Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB() & 0xFFFFFF);
                assertTrue(variant.toString(), icon.tooltip().startsWith("<html><b>Doom Bow</b>") && icon.tooltip().contains(hex));
            }
            assertNull("No enchant data, no tooltip", new ItemIcon(new Solid(24), "Potion", EnchantInfo.notRecorded()).tooltip());
        });
    }

    static final class Solid implements Icon {
        static final Color INK = new Color(200, 40, 90);
        private final int size;
        Solid(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { g.setColor(INK); g.fillRect(x, y, size, size); }
    }
}
```

Create `src/test/java/tomato/gui/stats/LootDashboardEnchantTest.java`:

```java
package tomato.gui.stats;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.Test;
import tomato.gui.kit.ItemIcon;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

/** Dashboard rows: single drops show their exact enchants, merged rows only the shared rarity; icon tooltips are not escaped. */
public class LootDashboardEnchantTest {
    private static final Icon BASE = new ImageIcon(new java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB));

    @Test public void aSingleDropShowsItsExactEnchantsAndAMergedRowOnlyItsRarity() {
        LootDashboard.Item item = new LootDashboard.Item(10, "Doom Bow", "EQUIPMENT,WEAPON,UT", ParseEnchants.evidence(enchants(-1, 42)));
        ItemIcon single = LootDashboard.rowIcon(BASE, "Doom Bow", 1, item);
        assertSame("The cached base icon is reused, never replaced", BASE, single.base());
        assertEquals(item.enchantInfo(), single.enchant());
        ItemIcon merged = LootDashboard.rowIcon(BASE, "Doom Bow", 3, item);
        assertEquals(EnchantInfo.State.COUNT_ONLY, merged.enchant().state());
        assertEquals(EnchantInfo.Rarity.RARE, merged.enchant().rarity());
        assertTrue(merged.tooltip(), merged.tooltip().startsWith("<html><b>Doom Bow · 3 drops</b>"));
        assertFalse("One drop's names are never shown for all three", merged.tooltip().contains("(empty slot)"));
    }

    @Test public void iconTooltipsReachTheUserUnescapedWhileTextStaysEscaped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[][] {
                {new ItemIcon(BASE, "Doom Bow", EnchantInfo.ofSlotCount(2)), "<b>not markup</b>", BASE}}, new Object[] {"Icon", "Item", "Plain"}) {
                @Override public Class<?> getColumnClass(int column) { return column == 1 ? String.class : Icon.class; }
            };
            JTable table = StatsUi.table(model, "loot-test");
            table.setSize(600, 200); table.doLayout();
            assertTrue(tip(table, 0).startsWith("<html><b>Doom Bow</b>"));
            assertTrue(tip(table, 0).contains("Rare · 2 enchant slots"));
            assertTrue("Text cells stay escaped", tip(table, 1).contains("&lt;b&gt;"));
            assertNull("Plain icons still have no tooltip", tip(table, 2));
        });
    }

    private static String tip(JTable table, int column) {
        Rectangle cell = table.getCellRect(0, column, true);
        Point at = new Point(cell.x + cell.width / 2, cell.y + cell.height / 2);
        return table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, at.x, at.y, 0, false));
    }

    private static String enchants(int... slots) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + slots.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0); buffer.putShort((short) 1026);
        for (int slot : slots) buffer.putShort((short) slot);
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
```

`StatsUi.table` is package-private static in `tomato.gui.stats`, so this test lives in the same package.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.kit.ItemIconTest --tests tomato.gui.stats.LootDashboardEnchantTest`
Expected: FAIL at compile time: `cannot find symbol ... ItemIcon / rowIcon`.

- [ ] **Step 3: Implement**

Create `src/main/java/tomato/gui/kit/ItemIcon.java`:

```java
package tomato.gui.kit;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Objects;
import javax.swing.Icon;
import tomato.realmshark.EnchantInfo;

/**
 * An item sprite for table cells: the base icon, the rarity gem over its top-right corner, and the shared enchant tooltip, which
 * tables built by the stats pages ask the cell value for on hover. Colors resolve while painting and on hover, so it follows the
 * theme. The base is kept as given (callers may cache and reuse it).
 */
public final class ItemIcon implements Icon {
    private final Icon base;
    private final String heading;
    private final EnchantInfo enchant;

    public ItemIcon(Icon base, String heading, EnchantInfo enchant) {
        this.base = Objects.requireNonNull(base, "base");
        this.heading = heading;
        this.enchant = enchant == null ? EnchantInfo.notRecorded() : enchant;
    }

    public Icon base() { return base; }
    public EnchantInfo enchant() { return enchant; }

    /** The shared enchant tooltip, built now (call it on hover); null when there is no enchant data (e.g. a potion). */
    public String tooltip() { return enchant.state() == EnchantInfo.State.NOT_RECORDED ? null : EnchantTooltip.html(heading, enchant); }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        base.paintIcon(c, g, x, y);
        EnchantGem.paint((Graphics2D) g, enchant, x, y, Math.min(getIconWidth(), getIconHeight()) - 1);
    }

    @Override public int getIconWidth() { return base.getIconWidth(); }
    @Override public int getIconHeight() { return base.getIconHeight(); }
}
```

`StatsUi.java`: add `import tomato.gui.kit.ItemIcon;`. In `table(...)`'s `getToolTipText`, replace `if (getValueAt(row, column) instanceof Icon) return null;` with:

```java
                Object value = getValueAt(row, column);
                // Item icons carry their own (already escaped) enchant tooltip, built here on hover.
                if (value instanceof ItemIcon) return ((ItemIcon) value).tooltip();
                if (value instanceof Icon) return null;
```

Rename the method's later local `value` to `text` so it doesn't clash.

`LootDashboard.java`: add `import tomato.gui.kit.ItemIcon;` and a helper near `iconForItem`:

```java
    /**
     * The icon cell of one dashboard row: the cached sprite with the row's rarity gem. A row that merges several drops (same item,
     * slots and applied count) shares their rarity but not necessarily their enchantments, so it names none.
     */
    static ItemIcon rowIcon(Icon base, String name, int count, Item item) {
        EnchantInfo enchant = count == 1 ? item.enchantInfo() : EnchantInfo.ofSlotCount(item.enchants == null ? null : item.enchants.slots);
        return new ItemIcon(base, count == 1 ? name : name + " · " + count + " drops", enchant);
    }
```

In the row loop (lines 421-429), keep `Icon icon = iconForItem(item.item.id);` and put `rowIcon(icon, item.item.name, item.count, item.item)` in the row's first cell instead of `icon`. `iconForItem`'s cache is untouched, so `LootIconGenerationTest`'s `assertSame` still holds. If `Aggregate.count` is not an `int`, convert it the way the row's Count column does.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.kit.*' --tests 'tomato.gui.stats.*'`
Expected: PASS, including `LootIconGenerationTest` (compare any window-size/evidence failure against `main`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/ItemIcon.java src/main/java/tomato/gui/stats/StatsUi.java src/main/java/tomato/gui/stats/LootDashboard.java src/test/java/tomato/gui/kit/ItemIconTest.java src/test/java/tomato/gui/stats/LootDashboardEnchantTest.java
git commit -m "Show rarity gems and enchant tooltips in the Loot Dashboard icon column" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 6: Loot Archive detail lists enchant names

**Files:**
- Modify: `src/main/java/tomato/gui/stats/LootQuery.java` (`Row` fields line ~105; `Row.item(...)` line ~124)
- Modify: `src/main/java/tomato/gui/stats/LootArchiveClient.java` (detail text line ~297)
- Modify: `src/main/java/tomato/gui/stats/LootArchiveAdapter.java` (`loot-projection` value line 41)
- Test: `src/test/java/tomato/gui/stats/LootEnrichmentTest.java`

**Interfaces:**
- Consumes: `LootDashboard.Item.enchantInfo()` (Task 2); `EnchantInfo.text()`.
- Produces: `public String LootQuery.Row.enchantments`, the plain enchant text for occurrence rows. It is null on variant and summary rows. It is not a CSV column.

- [ ] **Step 1: Write the failing test**

Add to `LootEnrichmentTest`. Use the file's existing way of building an occurrence `Row` for a saved drop; `Row.item(session, drop, item, dungeon)` is package-private static. Save and restore `ParseEnchants.ENCHANT_DEFINITIONS` around the test:

```java
    @Test public void occurrenceRowsNameTheirEnchantmentsForTheArchiveDetail() {
        java.util.HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            java.util.HashMap<Short, ParseEnchants.Definition> definitions = new java.util.HashMap<>();
            definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
            ParseEnchants.ENCHANT_DEFINITIONS = definitions;
            LootDashboard.Item item = new LootDashboard.Item(10, "Doom Bow", "EQUIPMENT,WEAPON,UT", ParseEnchants.evidence(enchants(-1, 42)));
            LootDashboard.Drop drop = new LootDashboard.Drop("White", "Lost Halls", "Boss", 1_000, java.util.List.of(item));
            LootQuery.Row row = LootQuery.Row.item("s", drop, item, "Lost Halls");
            assertEquals("Rare · 2 enchant slots\n  (empty slot)\n  Attack Bonus I — Increases Attack by 1.4", row.enchantments);
            assertTrue("The exact-evidence export column is unchanged", row.enchantEvidence.contains("RECORDED"));
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }
```

If `LootEnrichmentTest` has no `enchants(int...)` helper, copy the one from `LootFactsTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.stats.LootEnrichmentTest`
Expected: FAIL at compile time: `cannot find symbol ... enchantments`.

- [ ] **Step 3: Implement**

`LootQuery.Row`:
- Add the field next to `enchantEvidence`:

```java
        /** Occurrence rows: the drop's enchantments as the archive detail shows them (display names; rarity only for older records). */
        public String enchantments;
```

- In `Row.item(...)`, after the `r.enchantEvidence=...` line: `r.enchantments=i.enchantInfo().text();`

`LootArchiveClient.java` (line ~297): after the `"Exact enchantment evidence: "` segment of the occurrence detail text, append:

```java
text.append("\nEnchantments: ").append(row.value.enchantments==null?"Not recorded":row.value.enchantments.replace("\n","\n  "));
```

Match the surrounding code's line layout: the existing statement is a single chained `text.append(...)`, so add this as its own statement directly after it.

`LootArchiveAdapter.java` line 41: the projection now carries display text, so bump the dependency string. This invalidates any cached projection built without `enchantments`:

```java
values.put("loot-projection","v4; exact captured enchant IDs, enchant display text and optional map context; …");
```

Keep the remainder of the existing value text after "optional map context" unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.stats.*'`
Expected: PASS. If a test pins the exact `loot-projection` string or the exact detail text, update it to the new value, and note it.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/stats/LootQuery.java src/main/java/tomato/gui/stats/LootArchiveClient.java src/main/java/tomato/gui/stats/LootArchiveAdapter.java src/test/java/tomato/gui/stats
git commit -m "Name each drop's enchantments in the Loot Archive detail" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 7: Phase verification, review and PR

**Files:** none changed unless a check fails.

- [ ] **Step 1: Leftover scan**

Search the branch diff (`git diff main...HEAD -- src`) for `TODO`, `@Ignore` and `.skip(`: expect none.

Search `src/main` for new non-transient fields on `LootDashboard.Item`, `LootDashboard.Drop` and `ParseEnchants.Evidence`: expect none. `git diff main...HEAD -- src/main/java/tomato/gui/stats/LootDashboard.java` should add only the `enchantInfo()` method and `rowIcon(...)`.

- [ ] **Step 2: Focused suite plus build**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.stats.*' --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.glance.*' --tests 'tomato.history.*' shadowJar`
Expected: BUILD SUCCESSFUL. Before calling a window-size/evidence failure a regression, run that test class in a separate worktree of `main`.

- [ ] **Step 3: Visual check**

The `tomato.gui.loot.*` and `tomato.gui.runs.*` evidence tests write screenshots under `build/ui-test/screenshots/`. Open the Loot Highlights and run-card captures in both themes and check that:
- gems appear on enchanted drops;
- gems sit inside the bag-colored well;
- gems don't hide the bag color.

If no capture shows an enchanted item, say so in the report rather than claiming a visual check.

- [ ] **Step 4: Launch smoke**

Launch the built jar from an isolated folder with its own copy of `assets/`. Use in-memory preferences (`util.InMemoryPreferencesFactory` from `build/classes/java/test` on the classpath) and `-Drealmshark.historyDir=<isolated folder>`. Do **not** start capture. Confirm that the app reaches Home, the console has no exception, and the app closes cleanly.

- [ ] **Step 5: Independent review**

Dispatch a fresh reviewer on the branch diff against `main`. Give it the spec path, this plan's path and the Review Focus list. Fix confirmed findings test-first, then rerun Step 2 for the touched packages.

- [ ] **Step 6: Push and open the PR (after the user confirms)**

```bash
git push -u origin feat/enchant-rarity-p2
gh pr create --base main --title "Show enchant rarity and rolled enchants on loot surfaces (phase 2)" --body-file pr-body.md
```

Write `pr-body.md` in the plan workspace (not the repo) before running this. Its sections:
- **What changes:** gems and hover enchants on Loot Highlights, run-recap loot bags and the Loot Dashboard; gems on run cards; enchant names in the Loot Archive detail; rarity for old rarity-only records.
- **How:** `EnchantInfo.COUNT_ONLY` / `ofSlotCount`; derived `LootDashboard.Item.enchantInfo()`; `LootFacts.Item.enchant`; kit `ItemIcon` and the `StatsUi` tooltip.
- **Compatibility:** no history format change (derived accessors only); `loot-projection` bumped to v4; CSV columns unchanged.
- **Validation:** Step 2 counts, Step 3 and Step 4 results.
- **Deferred items.**

End it with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU
```
