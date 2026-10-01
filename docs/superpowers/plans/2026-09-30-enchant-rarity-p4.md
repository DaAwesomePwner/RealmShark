# Enchant Rarity Phase 4 (Bridge and Cleanup) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the enchant-rarity feature:
- Bridge rarity follows the unlocked slot count, and saved Bridge entries that used the old rule say "legacy count".
- The DPS equipment summary shows each item's most-used enchant variant.
- The last `parse()` line-count caller is gone.
- The cleanup items deferred from Phases 1–3 are fixed.

**Architecture:**
- **Bridge.** `BridgePayload.Item` is built from the item's raw enchant entry through `EnchantInfo`. It keeps that entry (`enchantData`) in the review journal, so Bridge Review can draw the gem and the enchant tooltip.
  - The wire field names and their value vocabulary do not change.
  - Saved entries are never recomputed. `Item.rarityLabel()` marks the ones whose rarity came from upstream's line count.
- **DPS hits.** They keep a missing stat as `null` and a known-unenchanted slot as a canonical unenchanted entry, so new recordings can tell the two apart.
- **DPS summary.** `EquipmentUsageAggregator` sums usage per item as before and records damage per enchant variant, so the summary shows the variant that dealt the most damage.
- **Tooltip headings.** One shared helper, `EnchantTooltip.heading(name, tier)`, heads every item tooltip "name · tier".

**Tech Stack:** Java 17 (`--release 17`), Swing/FlatLaf, JUnit 4, Gradle 7.6.4 wrapper, Gson.

**Spec:** `docs/superpowers/specs/2026-09-30-equipment-enchant-rarity-design.md`. This plan covers the spec's Phase 4 section, plus Compatibility rules, Error handling and Testing, and the deferred items listed in `docs/superpowers/plans/2026-09-30-enchant-rarity-handoff.md`. Earlier phases are on `main`:
- Phase 1 (PR #32): `EnchantInfo`, `EnchantGem`, `EnchantTooltip`, `EnchantIconLabel`, `ItemSlot.setItem(int, String, EnchantInfo)`.
- Phase 2 (PR #34): `COUNT_ONLY`/`ofSlotCount`, kit `ItemIcon`, loot surfaces.
- Phase 3 (PR #35): run-recap players, `CharacterRecord.equipmentEnchants`, Character sheet and the Home hero.

**Deliberate refinements of the spec:**
- **The wire vocabulary stays upstream's.**
  - Unenchanted is still sent as `"common"`, the bot's word. Only the rule that picks the value changes: 1 unlocked slot is `"uncommon"` … 4 is `"divine"`, and locked slots do not count.
  - A bag slot with no enchant entry (potions, a short stat list) is still sent as `"common"`, as upstream did. Bridge Review labels it "no enchant data".
- **`enchantCount` now counts applied enchantments**, where it used to count decoded lines; unreadable data is still `-1`. Three things read it, and each keeps its meaning of "has applied enchantments":
  - the Enchanted send category;
  - "Other CSV items";
  - Review's Applied / No applied / Unknown filter.

  The count is now also correct for an enchant after an empty or locked slot, which `parse()` dropped.
- **Journal entries keep the raw entry.** The new optional `enchantData` field lets Review show the gem and tooltip. The journal `VERSION` stays 1. Entries without it (older entries, and bag slots with no entry) show no gem.
- **"Legacy count" labels:**
  - It covers saved entries whose `raritySource` is `"enchant_count"`, and saved entries with no `raritySource` at all. Both came from the old line count.
  - It appears in the live Review table, the live and saved details, and both CSV exports.
  - The saved table has no Rarity column, so it shows the label in its details and CSV.
- **Known-unenchanted DPS slots are saved as `ParseEnchants.UNENCHANTED_ENTRY` (`"AAIE"`: header and type, no slots),** not as `""`.
  - Older `.dps` files saved a missing stat as `""` too, so `""` stays "not recorded" forever.
  - Storing `null` for a missing stat alone would not let DPS show "Unenchanted".
  - This changes values, not fields.
- **The DPS "most used item" is still chosen by the item's total damage,** and the per-slot breakdown is unchanged. Only the enchant shown with it is now the variant that dealt the most damage (the first seen on a tie). Variants do not compete as separate items, so swapping between two copies of one item does not change which item wins.
- **`ParseEnchants.parse` is kept only for `SendLoot`'s legacy `sl` wire field.** The unused `extractEnchants` is deleted.
- **Tooltip headings.**
  - An item's tooltip is headed "name · tier" on:
    - Party roster
    - DPS icon view
    - My Info
    - Gear Analyst
    - Bridge Review
  - Empty and uncaptured Party slots keep their slot label.
  - Loot Dashboard's merged-row heading ("name · N drops", Phase 2) and the Highlights facts heading stay as designed.
- **Double indent.** Both text surfaces that re-indent `EnchantInfo.text()` stop doing so: Loot Archive detail and the Gear Analyst detail.
- **Not addressed:**
  - `CharacterRecord` enchants can be one observation stale after a server swap that lacks stat 80. This follows from Phase 3's rule that a missing stat keeps what was saved.
  - Import order is tidied only in files this plan touches.
  - The open question of whether the Divine gem should keep the UT border's amber is for the user.

## How to run things

All commands run from the repository root in Git Bash, with the project-local JDK and Gradle home exported in the same command:

```bash
export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests <pattern>
```

- If Gradle reports failures from classes that no longer exist, run `clean` and retry.
- If builds fail with `AccessDeniedException` on `.tools/gradle-home`, kill any `java.exe` owned by `CodexSandboxOffline` first.
- **Known environmental failures** on this workstation, which also fail on `main`:
  - `LootHighlightsTest` (2 window-size tests)
  - `DungeonsSourceTest.theLargeHistoryIsReadOffTheEventDispatchThreadAndKeptPerClosedSession`
  - `HomeRefreshTimingTest.liveTicksKeepHomesEdtWorkWithinOneFrame` (flaky)
  - the window-size and evidence classes listed in the project's desktop-failure note

Work on branch `feat/enchant-rarity-p4`. It was created from `main` at `ccb25b1`, after PR #35 merged, and its first commit is this plan:

```bash
git switch feat/enchant-rarity-p4
```

**Synthetic enchant entries used in tests.** Each is URL Base64 of `00 | 02 04 | LE shorts`:

| Entry | Slots | Rarity | Applied |
|---|---|---|---|
| `"AAIE"` | none | Unenchanted | 0 |
| `"AAIE__8="` | empty | Uncommon | 0 |
| `"AAIE_wU="` | type 1535 | Uncommon | 1 |
| `"AAIEAQD__w=="` | type 1, empty | Rare | 1 |
| `"AAIE__8FAA=="` | empty, type 5 | Rare | 1 (`parse()` read 0) |
| `"AAIEAQACAA=="` | types 1, 2 | Rare | 2 |
| `"AAIEAQACAP__"` | types 1, 2, empty | Legendary | 2 |
| `"AAIEAQACAAMABAA="` | types 1–4 | Divine | 4 |
| `"AAIE_v_-_w=="` | locked, locked | Unenchanted | 0 |
| `"!!!"` | — | unreadable | — |

## Global Constraints

- Java 17, `--release 17`; no new dependencies.
- **Rarity is the unlocked slot count.** Summary strings are verbatim from `EnchantInfo`:
  - `"Unenchanted"`
  - `"<Rarity> · N enchant slots"`
  - `"Enchants not recorded"`
  - `"Enchant data unreadable"`
- **Gem colors come from `Tokens.rarity`.** The gem sits top-right inside the well, and the tier border keeps its meaning.
- **Wire formats are unchanged:**
  - The HTTP bridge payload keeps the same keys, and `item_rarity` keeps the vocabulary `common`/`uncommon`/`rare`/`legendary`/`divine`/`unknown`.
  - `SendLoot`'s JSON, including `sl`, is unchanged.
- **Persistence:**
  - `BridgeJournal.VERSION` stays 1.
  - The new `BridgePayload.Item.enchantData` is optional. Gson restores older entries with it null, and they must load, list, export and show exactly their stored rarity.
  - Never recompute a saved entry's rarity.
- **Do not add fields or methods to `Damage`, `StatData` or `Equipment`**; they have no pinned `serialVersionUID`. Changing which value `Damage.ownerEnchants` holds is allowed. Old `.dps` files, where `ownerEnchants` entries may be `""` or the array may be null, must keep loading and reading "not recorded".
- **Threads:**
  - Bridge items are built on the capture thread.
  - Bridge Review decodes `EnchantInfo` when it rebuilds its rows (on the EDT, once per revision), never while painting a cell.
  - Tooltip HTML is built on hover only.
- New imports go in sorted position in the file's existing import block.
- Never start live capture or send bridge deliveries. No force pushes, hook bypasses or commits to `main`.
- End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG
  ```

## Review Focus

1. **Bridge journals saved before this change,** with no `enchantData` and a `raritySource` of `"enchant_count"` or none.
   - Expected: they reopen, list and export with their stored rarity plus "(legacy count)", and show no gem.
   - Nothing re-derives them from `enchants` text.
   - Pinned in Task 4 (`savedEntriesFromBeforeSlotRarityKeepTheirValueAndSayLegacyCount` and `savedEntriesFromBeforeSlotRaritySayLegacyCountAndShowNoGem`).
2. **A bag slot with no enchant entry** (a potion, or a stat list shorter than the bag).
   - Expected: the wire still says `"common"`; Review says "common (no enchant data)" and shows no gem.
   - The Enchanted category does not match it, and "Other CSV items" still does.
   - Pinned in Task 4.
3. **Old `.dps` recordings.**
   - A hit whose `ownerEnchants` holds `""`, or whose `ownerEnchants` array is null, loads without an exception.
   - The DPS view reads it as not recorded, never as the text `"null"` or as an unreadable gem.
   - Pinned in Task 3.
4. **One item used with two enchant variants in a fight.**
   - The summary names the item with the higher total damage, shown with its most-damaging variant.
   - The breakdown still lists each item once with unchanged percentages.
   - Pinned in Task 3.
5. **An applied enchant after an empty or locked slot**, which is what `parse()` got wrong.
   - Bridge rarity counts every unlocked slot.
   - `enchantCount`, and so the Enchanted category and Review's Applied filter, counts the applied enchantment.
   - Pinned in Task 4.

---

### Task 1: Shared kit fixes: heading helper, non-square gems, unknown-rarity guard

**Files:**
- Modify: `src/main/java/tomato/realmshark/EnchantInfo.java` (`summary()`, lines ~82-93)
- Modify: `src/main/java/tomato/gui/kit/EnchantTooltip.java` (add `heading`)
- Modify: `src/main/java/tomato/gui/kit/EnchantGem.java` (`decorate`, lines ~46-57)
- Modify: `src/main/java/tomato/gui/kit/ItemIcon.java` (`paintIcon`)
- Modify: `src/main/java/tomato/gui/kit/ItemSlot.java` (`itemText()`, line ~118)
- Test: `src/test/java/tomato/realmshark/EnchantInfoTest.java`, `src/test/java/tomato/gui/kit/EnchantGemTest.java`, `src/test/java/tomato/gui/kit/EnchantTooltipTest.java`

**Interfaces:**
- Produces: `public static String EnchantTooltip.heading(String name, String tier)`:
  - returns `"name · tier"`, or `name` alone when `tier` is null or empty;
  - a null `name` reads as `""`.

  Tasks 2 and 4 use it.

- [ ] **Step 1: Write the failing tests**

`EnchantInfoTest`: add the test below (plus `import java.util.List;` if missing):

```java
    @Test public void aRecordWithTheUnknownRarityClaimsNoCount() {
        assertEquals("Enchant data unreadable", new EnchantInfo(EnchantInfo.State.COUNT_ONLY, EnchantInfo.Rarity.UNKNOWN, List.of()).summary());
        assertEquals("Enchant data unreadable",
            new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of(new EnchantInfo.Slot(-1))).summary());
        assertFalse(new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNKNOWN, List.of()).enchanted());
    }
```

`EnchantGemTest`: add these two tests. The file's `paint(Icon)` helper returns the ARGB pixels, and untouched pixels are `0`.

```java
    @Test public void aRecordedUnknownRarityPaintsNoGem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = ItemSlot.icon(null, "UT", ItemSlot.State.ITEM, 20);
            assertSame(base, EnchantGem.decorate(base, new EnchantInfo(EnchantInfo.State.RECORDED, Rarity.UNKNOWN, java.util.List.of())));
        });
    }

    @Test public void aWideIconsGemSitsInItsTopRightCorner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon wide = new Icon() {
                @Override public void paintIcon(Component c, Graphics g, int x, int y) { }
                @Override public int getIconWidth() { return 60; }
                @Override public int getIconHeight() { return 21; }
            };
            for (Icon decorated : new Icon[] {EnchantGem.decorate(wide, recorded(Rarity.RARE)), new ItemIcon(wide, "Bow", recorded(Rarity.RARE))}) {
                int[] pixels = paint(decorated);
                int minX = Integer.MAX_VALUE, maxY = -1;
                for (int i = 0; i < pixels.length; i++) if (pixels[i] != 0) { minX = Math.min(minX, i % 60); maxY = Math.max(maxY, i / 60); }
                assertTrue("The gem is painted", maxY >= 0);
                assertTrue("The gem is in the right-hand square, not over the icon's left: " + minX, minX >= 60 - 21);
                assertTrue("The gem is in the top half: " + maxY, maxY < 21 / 2 + 1);
            }
        });
    }
```

`EnchantTooltipTest`: add:

```java
    @Test public void theHeadingIsTheNameThenTheTier() {
        assertEquals("Doom Bow · UT", EnchantTooltip.heading("Doom Bow", "UT"));
        assertEquals("Doom Bow", EnchantTooltip.heading("Doom Bow", ""));
        assertEquals("Doom Bow", EnchantTooltip.heading("Doom Bow", null));
        assertEquals("", EnchantTooltip.heading(null, null));
    }

    @Test public void escapesEnchantNamesAndEffects() {
        saved = ParseEnchants.ENCHANT_DEFINITIONS;
        HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
        definitions.put((short) 9, new ParseEnchants.Definition("<b>Bold</b> & Co", "Adds <i>1</i>"));
        ParseEnchants.ENCHANT_DEFINITIONS = definitions;
        String html = EnchantTooltip.html("Bow", new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNCOMMON, List.of(new EnchantInfo.Slot(9))));
        assertTrue(html, html.contains("&lt;b&gt;Bold&lt;/b&gt; &amp; Co"));
        assertTrue(html, html.contains("Adds &lt;i&gt;1&lt;/i&gt;"));
        assertFalse(html, html.contains("<i>"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.EnchantInfoTest --tests tomato.gui.kit.EnchantGemTest --tests tomato.gui.kit.EnchantTooltipTest`
Expected:
- Compile failure on `EnchantTooltip.heading`. Once a stub exists:
  - `aRecordWithTheUnknownRarityClaimsNoCount` fails with `"Unknown · 5 enchant slots"`.
  - `aWideIconsGemSitsInItsTopRightCorner` fails with a `minX` below 39.

- [ ] **Step 3: Implement**

`EnchantInfo.summary()`: the `default` branch starts with a guard:

```java
            default: {
                // No slot count maps to UNKNOWN, so a record built with it has no honest count to show.
                if (rarity == Rarity.UNKNOWN) return "Enchant data unreadable";
                // Rarity constants are declared in slot-count order, so a count-only record's count is its rarity's ordinal.
```

The rest of the branch is unchanged.

`EnchantTooltip`: add above `html`:

```java
    /** The heading every item tooltip uses: "Doom Bow · UT", or the name alone when the item has no tier label. */
    public static String heading(String name, String tier) {
        String shown = name == null ? "" : name;
        return tier == null || tier.isEmpty() ? shown : shown + " · " + tier;
    }
```

`ItemSlot.itemText()`:

```java
    private String itemText() { return EnchantTooltip.heading(Sprites.name(itemId), tier); }
```

`EnchantGem.decorate`: place the gem in the icon's top-right square:

```java
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                // Sized to the shorter side and placed at the right edge, so a wide icon's gem is still in its top-right corner.
                int side = Math.min(getIconWidth(), getIconHeight());
                paint((Graphics2D) g, info, x + getIconWidth() - side, y, side - 1);
            }
```

Update the `decorate` Javadoc: "…with the gem over its top-right corner, sized to its shorter side…".

`ItemIcon.paintIcon`: the same placement.

```java
    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        base.paintIcon(c, g, x, y);
        int side = Math.min(getIconWidth(), getIconHeight());
        EnchantGem.paint((Graphics2D) g, enchant, x + getIconWidth() - side, y, side - 1);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.gui.kit.*'`
Expected: PASS. `ItemSlotEnchantTest` still sees "name · tier" headings.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/realmshark/EnchantInfo.java src/main/java/tomato/gui/kit src/test/java/tomato/realmshark/EnchantInfoTest.java src/test/java/tomato/gui/kit
git commit -m "Share the item tooltip heading, place gems on wide icons and guard the unknown rarity" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG"
```

---

### Task 2: Item tooltips are headed "name · tier" on Party, DPS, My Info and Gear Analyst

**Files:**
- Modify: `src/main/java/tomato/gui/security/ParsePanelGUI.java` (equipment loop, lines ~1045-1062)
- Modify: `src/main/java/tomato/gui/dps/IconDpsGUI.java` (line ~419)
- Modify: `src/main/java/tomato/gui/myinfo/MyInfoGUI.java` (line ~627)
- Modify: `src/main/java/tomato/gui/character/CharacterEquipmentPanel.java` (tooltip, line ~51)
- Test: `src/test/java/tomato/gui/security/ParsePanelRefreshTest.java`

**Interfaces:**
- Consumes: `EnchantTooltip.heading(String, String)` (Task 1) and `ItemTiers.label(int)`.

- [ ] **Step 1: Write the failing test**

In `ParsePanelRefreshTest.equipmentRendererResetsKnownEmptyMissingAndUnrecognizedDescriptions`, directly after:

```java
                JLabel reused = assertEquipmentText(table, 0, 3, "Weapon: Sword of Acclaim (ID " + itemId + ")", "Test enchant — Does a test thing");
```

add:

```java
                assertTrue(reused.getToolTipText(), reused.getToolTipText().startsWith("<html><b>Sword of Acclaim"));
                assertFalse("An item's tooltip is headed by the item, not its slot", reused.getToolTipText().contains("Weapon:"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.security.ParsePanelRefreshTest`
Expected: FAIL. The tooltip starts `<html><b>Weapon: Sword of Acclaim (ID 987654)`.

- [ ] **Step 3: Implement**

`ParsePanelGUI`, in the per-slot loop: compute the well's state and tier before the tooltip, then head an item's tooltip with the item. The loop body from `EnchantInfo enchant = capture.info(i);` to the end becomes:

```java
                EnchantInfo enchant = capture.info(i);
                equipmentDetails[i] = equipmentLabels[i] + "\nEnchants: " + enchant.text();
                // The well says what the slot holds (as its label does): not captured, empty (a negative ID) or an item with its tier.
                ItemSlot.State state = !player.equipmentCaptured[i] ? ItemSlot.State.UNKNOWN : player.inv[i] < 0 ? ItemSlot.State.EMPTY : ItemSlot.State.ITEM;
                String tier = state == ItemSlot.State.ITEM && player.inv[i] > 0 && definitions != null ? ItemTiers.label(definitions.item(player.inv[i])) : "";
                // Built with the row, which is rebuilt on a theme change, so the tooltip's colors follow the theme. An item is headed by its
                // name and tier, as every item tooltip is; an empty or uncaptured slot keeps its slot label.
                String heading = state == ItemSlot.State.ITEM
                        ? EnchantTooltip.heading(player.itemName[i] == null ? "Unrecognized item" : player.itemName[i], tier) : equipmentLabels[i];
                equipmentTooltips[i] = EnchantTooltip.html(heading, enchant);
                Icon well = ItemSlot.icon(state == ItemSlot.State.ITEM ? Sprites.sprite(player.inv[i], 20) : null, tier, state, 20);
                // Only a captured item carries a rarity gem; a well that shows no item claims none.
                icons[i] = state == ItemSlot.State.ITEM ? EnchantGem.decorate(well, enchant) : well;
```

`IconDpsGUI` (line ~419):

```java
            icon.setItem(ImageBuffer.getOutlinedIcon(eq, s), EnchantTooltip.heading(IdToAsset.objectName(eq), ItemTiers.label(eq)), enchant);
```

`MyInfoGUI` (line ~627):

```java
                if (id != null && id >= 0) icons[i].setItem(loadIcon(id.intValue()), EnchantTooltip.heading(item, ItemTiers.label(id.intValue())), enchant);
```

`CharacterEquipmentPanel` (the Item column renderer's `getToolTipText`):

```java
                return current == null || current.enchant == null ? super.getToolTipText()
                    : EnchantTooltip.html(EnchantTooltip.heading(itemName(current.item), current.item == null ? "" : ItemTiers.label(current.item)), current.enchant);
```

Add the imports `tomato.gui.kit.EnchantTooltip` and `tomato.gui.kit.ItemTiers` wherever a file lacks them.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.security.*' --tests 'tomato.gui.dps.*' --tests 'tomato.gui.myinfo.*' --tests 'tomato.gui.character.*' --tests 'tomato.gui.kit.*'`
Expected: PASS. Compare any window-size or evidence failure against the known list.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/security/ParsePanelGUI.java src/main/java/tomato/gui/dps/IconDpsGUI.java src/main/java/tomato/gui/myinfo/MyInfoGUI.java src/main/java/tomato/gui/character/CharacterEquipmentPanel.java src/test/java/tomato/gui/security/ParsePanelRefreshTest.java
git commit -m "Head item tooltips with the item name and tier on Party, DPS, My Info and Gear Analyst" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG"
```

---

### Task 3: DPS keeps unenchanted slots apart from missing data and shows the most-used enchant variant

**Files:**
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (add `UNENCHANTED_ENTRY`, `EquippedCapture.retained()`)
- Modify: `src/main/java/tomato/realmshark/EnchantInfo.java` (`ofRetained` Javadoc only)
- Modify: `src/main/java/tomato/backend/data/Damage.java` (line ~225, a value only)
- Modify: `src/main/java/tomato/gui/dps/shared/EquipmentUsageAggregator.java`
- Test: `src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java`
- Create: `src/test/java/tomato/backend/data/DamageEnchantRetentionTest.java`, `src/test/java/tomato/gui/dps/shared/EquipmentUsageAggregatorTest.java`

**Interfaces:**
- Produces:
  - `public static final String ParseEnchants.UNENCHANTED_ENTRY = "AAIE"`;
  - `public String[] ParseEnchants.EquippedCapture.retained()`, which returns four entries: null when not recorded, `UNENCHANTED_ENTRY` for a known-unenchanted slot, otherwise the entry as captured.
- `EquipmentUsageAggregator`'s public API is unchanged. `getMostUsedItem(...).enchant` is now the most-damaging variant's entry, and `null` when not recorded (it used to be the text `"null"`).

- [ ] **Step 1: Write the failing tests**

`EquippedEnchantCaptureTest`: add:

```java
    @Test public void retainedEntriesTellAMissingSlotFromAKnownUnenchantedOne() {
        assertArrayEquals(new String[] {"AAIE_wU=", ParseEnchants.UNENCHANTED_ENTRY, "!!!", null}, capture("AAIE_wU,,!!!").retained());
        assertArrayEquals(new String[] {"AAIE", "AAIE", "AAIE", "AAIE"}, capture("").retained());
        assertArrayEquals(new String[] {null, null, null, null}, ParseEnchants.equippedCapture(null).retained());
        EnchantInfo unenchanted = EnchantInfo.ofRetained(ParseEnchants.UNENCHANTED_ENTRY);
        assertEquals(EnchantInfo.State.RECORDED, unenchanted.state());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, unenchanted.rarity());
    }
```

(Add `import static org.junit.Assert.assertArrayEquals;` if the file's static imports do not cover it.)

Create `src/test/java/tomato/backend/data/DamageEnchantRetentionTest.java`:

```java
package tomato.backend.data;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

/** New DPS hits keep a missing enchant stat apart from a known-unenchanted slot (older hits saved "" for both). */
public class DamageEnchantRetentionTest {
    private static Entity player(String enchants) {
        Entity p = new Entity(null, 1, 0);
        StatType[] slots = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
        for (int i = 0; i < 4; i++) { StatData s = new StatData(); s.statValue = 100 + i; p.stat.set(slots[i], s); }
        if (enchants != null) { StatData s = new StatData(); s.stringStatValue = enchants; p.stat.set(StatType.UNIQUE_DATA_STRING, s); }
        return p;
    }

    @Test public void aHitKeepsAMissingStatAsNotRecordedAndAnEmptyEntryAsUnenchanted() {
        assertArrayEquals(new String[] {null, null, null, null}, new Damage(player(null)).ownerEnchants);
        Damage hit = new Damage(player("AAIE_wU,,"));
        assertArrayEquals(new String[] {"AAIE_wU=", "AAIE", "AAIE", null}, hit.ownerEnchants);
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, EnchantInfo.ofRetained(hit.ownerEnchants[1]).rarity());
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(hit.ownerEnchants[3]));
        assertSame("Older hits' \"\" still reads as not recorded", EnchantInfo.notRecorded(), EnchantInfo.ofRetained(""));
    }
}
```

Create `src/test/java/tomato/gui/dps/shared/EquipmentUsageAggregatorTest.java`:

```java
package tomato.gui.dps.shared;

import org.junit.Test;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.Equipment;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

/** Usage is summed per item; the item is shown with the enchant variant that dealt the most damage. */
public class EquipmentUsageAggregatorTest {
    private static final String RARE = "AAIEAQD__w==", UNCOMMON = "AAIE_wU=";

    private static Damage hit(Entity owner, int damage, int weapon, String weaponEnchant) {
        Damage d = new Damage(owner, 0, damage);
        d.ownerInvntory = new int[] {weapon, 200, 300, 400};
        d.ownerEnchants = new String[] {weaponEnchant, null, "", ParseEnchants.UNENCHANTED_ENTRY};
        return d;
    }

    @Test public void theMostUsedItemShowsItsMostUsedEnchantVariant() {
        Entity mob = new Entity(null, 1, 0), owner = new Entity(null, 7, 0);
        mob.getDamageList().add(hit(owner, 10, 100, UNCOMMON)); // seen first
        mob.getDamageList().add(hit(owner, 50, 100, RARE));
        mob.getDamageList().add(hit(owner, 30, 101, RARE));
        EquipmentUsageAggregator usage = EquipmentUsageAggregator.of(mob);
        Equipment weapon = usage.getMostUsedItem(7, 0);
        assertEquals(100, weapon.id);
        assertEquals("The variant that dealt the most damage, not the first one seen", RARE, weapon.enchant);
        assertEquals("Usage is still summed per item across its variants", 60, weapon.dmg);
        assertEquals("The breakdown lists each item once", 2, usage.getSlotBreakdown(7, 0).size());
        assertEquals(90, usage.getSlotTotalDamage(7, 0));
    }

    @Test public void aTieKeepsTheFirstVariantSeen() {
        Entity mob = new Entity(null, 1, 0), owner = new Entity(null, 7, 0);
        mob.getDamageList().add(hit(owner, 20, 100, UNCOMMON));
        mob.getDamageList().add(hit(owner, 20, 100, RARE));
        assertEquals(UNCOMMON, EquipmentUsageAggregator.of(mob).getMostUsedItem(7, 0).enchant);
    }

    @Test public void oldHitsWithoutEnchantsReadAsNotRecordedNotAsTheTextNull() {
        Entity mob = new Entity(null, 1, 0), owner = new Entity(null, 7, 0);
        mob.getDamageList().add(hit(owner, 10, 100, UNCOMMON));
        Damage old = new Damage(owner, 0, 5);
        old.ownerInvntory = new int[] {100, 200, 300, 400};
        old.ownerEnchants = null; // a hit recorded without enchant strings
        mob.getDamageList().add(old);
        EquipmentUsageAggregator usage = EquipmentUsageAggregator.of(mob);
        assertNull(usage.getMostUsedItem(7, 1).enchant);
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(usage.getMostUsedItem(7, 1).enchant));
        assertEquals("An older file's \"\" is kept as it was", "", usage.getMostUsedItem(7, 2).enchant);
        assertEquals(UNCOMMON, usage.getMostUsedItem(7, 0).enchant);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.EquippedEnchantCaptureTest --tests tomato.backend.data.DamageEnchantRetentionTest --tests tomato.gui.dps.shared.EquipmentUsageAggregatorTest`
Expected: FAIL at compile time: `cannot find symbol ... UNENCHANTED_ENTRY / retained()`.

- [ ] **Step 3: Implement**

`ParseEnchants`: add a public constant near the top of the class body:

```java
    /**
     * A known-unenchanted equipped entry (header and type, no slots). DPS hits save it for an empty entry, because older hits
     * saved a missing stat as "" and "" therefore stays "not recorded".
     */
    public static final String UNENCHANTED_ENTRY = "AAIE";
```

In `EquippedCapture`, below `infos()`:

```java
        /** The four slots' entries as DPS hits keep them: null when not recorded, {@link #UNENCHANTED_ENTRY} when known empty, else as captured. */
        public String[] retained() {
            String[] entries = new String[4];
            for (int i = 0; i < 4; i++)
                entries[i] = states[i] == CaptureState.MISSING ? null : codes[i].isEmpty() ? UNENCHANTED_ENTRY : codes[i];
            return entries;
        }
```

`Damage.setInv` (line ~225). This changes a value only; add no field or method to `Damage`:

```java
            ownerEnchants = ParseEnchants.equippedCapture(o).retained();
```

`EnchantInfo.ofRetained`: replace its Javadoc (the method body is unchanged):

```java
    /**
     * A blob a DPS hit retained. Hits saved before enchant rarity stored a missing stat as "", so "" (like null) reads as not
     * recorded; newer hits store a known-unenchanted slot as {@link ParseEnchants#UNENCHANTED_ENTRY}.
     */
```

`EquipmentUsageAggregator`:
- Add `import java.util.LinkedHashMap;`.
- In the class Javadoc's Notes, replace the "enchant" bullet with: `The "enchant" of each item is its most-used variant's entry (by damage; the first seen on a tie); null when not recorded.`
- `SlotUsage` becomes:

```java
    public static final class SlotUsage {

        /** itemId -> the item's usage across all its enchant variants; its {@code enchant} is the most-used variant's entry. */
        public final Map<Integer, Equipment> items = new HashMap<>();
        public final AtomicInteger total = new AtomicInteger(0);
        /** itemId -> enchant entry (null = not recorded) -> damage dealt with that variant, in first-seen order. */
        private final Map<Integer, Map<String, int[]>> variants = new HashMap<>();
    }
```

- In `aggregate`, replace the per-slot body (from `int itemId = …` through `eq.add(d.damage);`) with:

```java
                // Use inventory snapshot stored on the Damage
                int itemId = d.ownerInvntory[fi];
                String enchant = d.ownerEnchants == null || fi >= d.ownerEnchants.length ? null : d.ownerEnchants[fi];

                // Ensure Equipment.totalDmg points to the slot total accumulator.
                Equipment eq = su.items.computeIfAbsent(itemId, id -> new Equipment(id, enchant, su.total));
                eq.add(d.damage);
                su.variants.computeIfAbsent(itemId, id -> new LinkedHashMap<>()).computeIfAbsent(enchant, e -> new int[1])[0] += d.damage;
```

- At the end of `aggregate`, after the loop:

```java
        // Each item shows the enchant variant that dealt it the most damage (the first seen on a tie).
        for (OwnerUsage ou : byOwner.values()) for (SlotUsage su : ou.slots)
            for (Map.Entry<Integer, Equipment> item : su.items.entrySet()) {
                int best = Integer.MIN_VALUE;
                for (Map.Entry<String, int[]> variant : su.variants.get(item.getKey()).entrySet())
                    if (variant.getValue()[0] > best) { best = variant.getValue()[0]; item.getValue().enchant = variant.getKey(); }
            }
```

`IconDpsGUI` and `DpsToString` need no change. `ofRetained` already reads `null` as not recorded.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.backend.data.*' --tests 'tomato.gui.dps.*'`
Expected: PASS. `DamageEventsEnchantTest` still reads a retained `""` as "Enchants not recorded".

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/realmshark/ParseEnchants.java src/main/java/tomato/realmshark/EnchantInfo.java src/main/java/tomato/backend/data/Damage.java src/main/java/tomato/gui/dps/shared/EquipmentUsageAggregator.java src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java src/test/java/tomato/backend/data/DamageEnchantRetentionTest.java src/test/java/tomato/gui/dps/shared/EquipmentUsageAggregatorTest.java
git commit -m "Keep unenchanted DPS slots apart from missing data and show each item's most-used enchant variant" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG"
```

---

### Task 4: Bridge rarity from the slot count, legacy-count labels, and the gem in Bridge Review

**Files:**
- Modify: `src/main/java/tomato/bridge/BridgePayload.java` (`Item`, `snapshot`)
- Modify: `src/main/java/tomato/bridge/BridgeJournal.java` (`csv`, line ~159)
- Modify: `src/main/java/tomato/gui/bridge/BridgeReviewGUI.java`:
  - item renderers (lines ~106, ~178)
  - rows (~349)
  - saved rows (~213-215)
  - saved details (~231)
  - `reviewCsv` (~431)
  - `ItemCell` (~546)
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (delete `extractEnchants`; `parse` Javadoc)
- Modify: `docs/BRIDGE.md` (lines ~24 and ~71)
- Test:
  - `src/test/java/tomato/bridge/BridgeTest.java`
  - `src/test/java/tomato/gui/bridge/BridgeTableKindsTest.java`
- Migrate fixtures, which call the removed 6-argument `Item` constructor:
  - `src/test/java/tomato/bridge/BridgeDraftActiveTest.java`
  - `src/test/java/tomato/bridge/BridgeResponsivenessTest.java`
  - `src/test/java/tomato/bridge/BridgeSavedReviewTest.java`
  - `src/test/java/tomato/bridge/WaveThreeEvidenceTest.java`
  - `src/test/java/tomato/gui/bridge/BridgeFilterBarTest.java`
  - `src/test/java/tomato/gui/bridge/BridgeReportingTest.java`
  - `src/test/java/tomato/gui/bridge/BridgeUiTest.java`
  - `src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`
  - `src/test/java/ui/FinalScreensFixture.java`

**Interfaces:**
- Consumes: `EnchantInfo.of`, `EnchantGem.decorate`, `EnchantTooltip.html`, `EnchantTooltip.heading` (Task 1) and `ItemTiers.label(int)`.
- Produces:
  - `public BridgePayload.Item(int id, String name, String group, String label, String enchantData)`, replacing the 6-argument constructor. `enchantData` is the item's raw `UNIQUE_DATA_STRING` entry; null means the bag had none for the slot.
  - `public final String Item.enchantData`.
  - Constants `Item.METADATA_TOKEN`, `ENCHANT_SLOTS`, `ENCHANT_DATA_ABSENT`, `UNKNOWN_DEFAULT` and `LEGACY_ENCHANT_COUNT`.
  - `public String Item.rarityLabel()`.
  - `public EnchantInfo Item.enchantInfo()`, which is null when no entry was kept.

- [ ] **Step 1: Write the failing tests**

`BridgeTest`:
- Change the `drop` helper to take an enchant entry:

```java
    private BridgePayload.Drop drop(String name,String enchantData){return new BridgePayload.Drop(new BridgePayload.Item(42,name,"EQUIPMENT","UT",enchantData),7,"Example","Wizard","Test Dungeon",true,false,9,0);}
```

- In `goldenWireFormatMatchesPublicBridgeFieldsAndTypes`, use `drop("Test Sword (Shiny)","AAIEAQACAAMABAA=")`. The expected JSON is unchanged, including `"item_rarity":"divine"`.
- Replace `rarityAndShinyRulesMatchUpstreamIncludingMetadataPrecedence` with the two tests below, and add `import tomato.realmshark.EnchantInfo;`:

```java
    @Test public void rarityIsTheUnlockedSlotCountAndMetadataTokensStillTakePrecedence(){
        String[][] cases={{"AAIE","common","0"},{"AAIE__8=","uncommon","0"},{"AAIEAQD__w==","rare","1"},{"AAIEAQACAP__","legendary","2"},{"AAIEAQACAAMABAA=","divine","4"}};
        for(String[] c:cases){BridgePayload.Item item=new BridgePayload.Item(1,"Sword","","",c[0]);
            assertEquals(c[0],c[1],item.rarity);assertEquals(c[0],Integer.parseInt(c[2]),item.enchantCount);assertEquals(BridgePayload.Item.ENCHANT_SLOTS,item.raritySource);assertEquals(c[1],item.rarityLabel());}
        assertEquals("Locked slots do not count","common",new BridgePayload.Item(1,"Sword","","","AAIE_v_-_w==").rarity);
        BridgePayload.Item afterEmpty=new BridgePayload.Item(1,"Sword","","","AAIE__8FAA==");
        assertEquals("An enchant after an empty slot still counts","rare",afterEmpty.rarity);assertEquals(1,afterEmpty.enchantCount);
        BridgePayload.Item absent=new BridgePayload.Item(1,"Sword","","",null);
        assertEquals("No entry sends upstream's value","common",absent.rarity);assertEquals(BridgePayload.Item.ENCHANT_DATA_ABSENT,absent.raritySource);
        assertEquals(0,absent.enchantCount);assertEquals("common (no enchant data)",absent.rarityLabel());assertNull(absent.enchantInfo());
        BridgePayload.Item metadata=new BridgePayload.Item(1,"Sword","SHINY EQUIPMENT","UT RARE","AAIEAQACAAMABAA=");
        assertEquals("rare",metadata.rarity);assertEquals(BridgePayload.Item.METADATA_TOKEN,metadata.raritySource);assertTrue(metadata.shiny);assertFalse(metadata.divine);
        BridgePayload.Item malformed=new BridgePayload.Item(1,"Sword","","","!!!");
        assertEquals("unknown",malformed.rarity);assertEquals(-1,malformed.enchantCount);assertEquals("Unable to decode enchant data",malformed.enchants);
        assertEquals(EnchantInfo.State.UNREADABLE,malformed.enchantInfo().state());
        assertTrue(new BridgePayload.Item(1,"Divine Sword","","","").divine);
        assertEquals(EnchantInfo.Rarity.UNCOMMON,new BridgePayload.Item(1,"Sword","","","AAIE_wU=").enchantInfo().rarity());
    }
    @Test public void savedEntriesFromBeforeSlotRarityKeepTheirValueAndSayLegacyCount(){
        Gson gson=new Gson();JsonObject json=gson.toJsonTree(new BridgePayload.Item(1,"Sword","","","AAIE")).getAsJsonObject();
        json.addProperty("rarity","rare");json.addProperty("raritySource","enchant_count");json.remove("enchantData");
        BridgePayload.Item old=gson.fromJson(json,BridgePayload.Item.class);
        assertEquals("The stored value is kept","rare",old.rarity);assertEquals("rare (legacy count)",old.rarityLabel());assertNull("No gem",old.enchantInfo());
        json.remove("raritySource");
        assertEquals("An entry without a source also came from the line count","rare (legacy count)",gson.fromJson(json,BridgePayload.Item.class).rarityLabel());
    }
```

- In `snapshotDetachesBagAndPreservesEnchantSlotAlignment`, after its existing asserts, add `assertEquals(BridgePayload.Item.ENCHANT_SLOTS,d.get(0).item.raritySource);`.
- The test that exports `"First\nSecond"` (line ~121) now names enchant types 1 and 2. Wrap its body so the enchant text is built from those names:

```java
        java.util.HashMap<Short,tomato.realmshark.ParseEnchants.Definition> savedDefinitions=tomato.realmshark.ParseEnchants.ENCHANT_DEFINITIONS;
        java.util.HashMap<Short,tomato.realmshark.ParseEnchants.Definition> definitions=new java.util.HashMap<>();
        definitions.put((short)1,new tomato.realmshark.ParseEnchants.Definition("First",""));definitions.put((short)2,new tomato.realmshark.ParseEnchants.Definition("Second",""));
        tomato.realmshark.ParseEnchants.ENCHANT_DEFINITIONS=definitions;
        try { /* the existing body, with drop("Test Sword","First\nSecond") changed to drop("Test Sword","AAIEAQACAA==") */ }
        finally { tomato.realmshark.ParseEnchants.ENCHANT_DEFINITIONS=savedDefinitions; }
```

- Every other `drop(...)` call in `BridgeTest` already passes `""`, and keeps it.

`BridgeTableKindsTest`:
- `journal(...)` (line ~285): `new BridgePayload.Item(100 + i, "Saved Sword #" + i, "EQUIPMENT", "UT", "")`.
- In `columnsUseTheirKindsAndTheItemCellShowsTheItemSprite`, the review drops come from `BridgeFilterBarTest.service`, where "Test Sword" now carries `"AAIE_wU="` (see the migration below). Replace the loop's sprite assertion with:

```java
                    Icon sprite = Sprites.sprite(id, 16);
                    if (name.equals("Test Sword")) {
                        assertNotSame("An enchanted drop's sprite carries its gem", sprite, cell.getIcon());
                        assertTrue(cell.getToolTipText(), cell.getToolTipText().contains("Uncommon · 1 enchant slot"));
                    } else {
                        assertSame("The row's item sprite", sprite, cell.getIcon());
                        assertTrue(cell.getToolTipText(), cell.getToolTipText().contains("Unenchanted"));
                    }
```

- Add this test and helper (the imports `Icon`, `Gson` and `JsonObject` may already be present):

```java
    @Test public void savedEntriesFromBeforeSlotRaritySayLegacyCountAndShowNoGem() throws Exception {
        try (BridgeService service = service(temp)) {
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service, mode(DisplayModeModel.Mode.ANALYST)));
            openSaved(panel, legacyJournal());
            edt(() -> {
                JTable saved = named(panel, "bridge-saved-table", JTable.class);
                assertEquals(2, saved.getRowCount());
                for (int row = 0; row < saved.getRowCount(); row++) {
                    JLabel item = render(saved, row, 1);
                    if ("Legacy Bow".equals(saved.getValueAt(row, 1))) assertSame("A legacy entry kept no enchant data, so no gem", Sprites.sprite(7001, 16), item.getIcon());
                    else assertNotSame("A new entry shows its gem", Sprites.sprite(7002, 16), item.getIcon());
                }
                String csv = panel.savedCsv();
                assertTrue(csv, csv.contains("\"rare (legacy count)\""));
                assertTrue(csv, csv.contains("\"uncommon\""));
                return null;
            });
        }
    }
    /** A journal with one entry saved before slot rarity (rarity from upstream's line count, no enchant data) and one after. */
    private Path legacyJournal() throws Exception {
        Path file = temp.getRoot().toPath().resolve("saved").resolve("legacy-review.jsonl"); Files.createDirectories(file.getParent());
        Gson gson = new Gson(); StringBuilder lines = new StringBuilder();
        BridgePayload.Item[] items = {new BridgePayload.Item(7001, "Legacy Bow", "EQUIPMENT", "UT", "AAIEAQD__w=="), new BridgePayload.Item(7002, "New Bow", "EQUIPMENT", "UT", "AAIE_wU=")};
        for (int i = 0; i < items.length; i++) {
            JsonObject drop = gson.toJsonTree(new BridgePayload.Drop(items[i], 7, "Fixture", "Wizard", "Synthetic Dungeon", false, false, 1, 0)).getAsJsonObject();
            if (i == 0) { JsonObject item = drop.getAsJsonObject("item"); item.remove("enchantData"); item.addProperty("raritySource", "enchant_count"); }
            JsonObject review = new JsonObject();
            review.addProperty("id", i + 1); review.addProperty("time", TIMES[i]); review.add("drop", drop);
            review.addProperty("status", "Local only"); review.addProperty("detail", "Sending was off."); review.addProperty("payload", "");
            JsonObject line = new JsonObject();
            line.addProperty("journal", BridgeJournal.FORMAT); line.addProperty("version", 1); line.addProperty("service", "fixture"); line.add("review", review);
            lines.append(gson.toJson(line)).append('\n');
        }
        Files.write(file, lines.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }
```

**Fixture migration.** The 6-argument constructor is removed. Every `new BridgePayload.Item(a, b, c, d, X, false/true)` becomes `new BridgePayload.Item(a, b, c, d, entry)`, using these rules:
- `X` is `""` → entry `""`.
- `X` is any enchant text (`"Damage(3)"`, `"Damage Boost(1)"`, `"Frost +5"`) → `"AAIE_wU="`, which has one applied enchant, so it stays in the "Applied enchants" filter.
- Malformed (`true`) → `"!!!"`.

Where a helper takes the text as a parameter (`BridgeFilterBarTest.drop`, `BridgeReportingTest.drop`, `BridgeUiTest.drop`, `FinalScreensFixture.bridgeDrop`), rename the parameter to `enchantData` and change its callers by the same rules. Two tests match on the enchant *name*, so they also name the enchant type:
- **`BridgeReportingTest`** (the test that searches `"Frost +5"`): wrap its body as in the `BridgeTest` example, with `definitions.put((short)1535,new ParseEnchants.Definition("Frost +5",""))`. The search still finds `"Frost +5"`, and `"Enchant count: unknown"` still comes from the `"!!!"` item.
- **`BridgeUiTest`**: use `drop("Test Sword (Shiny)","AAIEAQACAA==")`, and wrap the body with definitions `1 → "Damage Boost"`, `2 → "Loot Bonus"`. The details still contain `"Damage Boost"`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.bridge.*' --tests 'tomato.gui.bridge.*'`
Expected: FAIL at compile time: no 5-argument `Item` constructor, and `cannot find symbol ... rarityLabel / enchantInfo / ENCHANT_SLOTS`.

- [ ] **Step 3: Implement**

`BridgePayload.Item`: replace the class body's fields and constructor with the code below. Add `import tomato.realmshark.EnchantInfo;`, and remove `import tomato.realmshark.ParseEnchants;` once `snapshot` no longer needs it.

```java
    public static final class Item {
        /** Rarity sources. Saved entries may also hold LEGACY_ENCHANT_COUNT (upstream's decoded line count) or none; they are never recomputed. */
        public static final String METADATA_TOKEN="metadata_token", ENCHANT_SLOTS="enchant_slots", ENCHANT_DATA_ABSENT="enchant_data_absent",
            UNKNOWN_DEFAULT="fallback_unknown_default", LEGACY_ENCHANT_COUNT="enchant_count";
        /** The wire's rarity words by unlocked slot count (upstream's vocabulary: an unenchanted item is "common"). */
        private static final String[] WIRE_RARITIES={"common","uncommon","rare","legendary","divine"};
        /** enchantCount: applied enchantments, -1 when the entry is unreadable (saved legacy entries hold upstream's line count). */
        public final int id, enchantCount;
        public final String rawName, baseName, group, label, rarity, enchants, raritySource;
        /** The item's UNIQUE_DATA_STRING entry as captured; null when the bag had none for this slot, and in entries saved before it was kept. */
        public final String enchantData;
        public final boolean shiny, divine, ut, st;
        public Item(int id, String name, String group, String label, String enchantData) {
            this.id=id; this.rawName=name.trim(); this.group=group==null?"":group; this.label=label==null?"":label;
            boolean suffix=rawName.toLowerCase(Locale.ROOT).endsWith("(shiny)");
            baseName=suffix?rawName.substring(0,rawName.length()-7).trim():rawName;
            List<String> tokens=Arrays.asList((this.label+" "+this.group).toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"));
            shiny=suffix||tokens.contains("SHINY"); ut=tokens.contains("UT"); st=tokens.contains("ST");
            this.enchantData=enchantData;
            EnchantInfo info=EnchantInfo.of(enchantData);
            boolean unreadable=info.state()==EnchantInfo.State.UNREADABLE, absent=info.state()==EnchantInfo.State.NOT_RECORDED;
            int applied=0; for(EnchantInfo.Slot slot:info.slots()) if(!slot.empty()) applied++;
            enchantCount=unreadable?-1:applied;
            enchants=unreadable?"Unable to decode enchant data":String.join("\n",info.slotLines());
            String resolved=null;
            for(String token:tokens) if(Arrays.asList("COMMON","UNCOMMON","RARE","LEGENDARY","DIVINE").contains(token)) {resolved=token.toLowerCase(Locale.ROOT);break;}
            if(resolved!=null) {rarity=resolved; raritySource=METADATA_TOKEN;}
            else if(unreadable) {rarity="unknown"; raritySource=UNKNOWN_DEFAULT;}
            // No entry for this slot: upstream sent "common" here, so the wire keeps it; Review says why.
            else if(absent) {rarity="common"; raritySource=ENCHANT_DATA_ABSENT;}
            else {rarity=WIRE_RARITIES[info.rarity().ordinal()]; raritySource=ENCHANT_SLOTS;}
            divine=tokens.contains("DIVINE")||Arrays.asList(baseName.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")).contains("DIVINE")||rarity.equals("divine");
        }
        /** The rarity as Review and its exports show it: saved entries whose rarity came from upstream's line count say so. */
        public String rarityLabel() {
            String shown=rarity==null?"unknown":rarity;
            if(raritySource==null||LEGACY_ENCHANT_COUNT.equals(raritySource)) return shown+" (legacy count)";
            return ENCHANT_DATA_ABSENT.equals(raritySource)?shown+" (no enchant data)":shown;
        }
        /** The enchantments for the gem and tooltip; null when no entry was kept (older entries, or none in the bag). Decode once per row build. */
        public EnchantInfo enchantInfo() { return enchantData==null?null:EnchantInfo.of(enchantData); }
    }
```

In `snapshot`, replace the per-item enchant decoding (`String text=""; …` through the `new Item(...)` line) with:

```java
            Item item=new Item(s.statValue,name,IdToAsset.getIdGroup(s.statValue),IdToAsset.getIdLabel(s.statValue),i<encoded.length?encoded[i]:null);
```

Update the class comment's second sentence to: "Deliberately no new fields in HTTP loot payloads: enchant descriptions and the raw entry stay local."

`BridgeJournal.csv`: change the `d.item.rarity` cell to `d.item.rarityLabel()`.

`BridgeReviewGUI`:
- **Imports:** `java.util.function.IntFunction`, `tomato.gui.kit.EnchantGem`, `tomato.gui.kit.EnchantTooltip`, `tomato.gui.kit.ItemTiers` and `tomato.realmshark.EnchantInfo`. Remove `IntUnaryOperator` if it becomes unused.
- **Fields**, next to `rows`/`savedEntries`:

```java
    // Each row's enchantments, decoded when the rows are rebuilt (never while painting); null where no entry was kept.
    private List<EnchantInfo> reviewEnchants=Collections.emptyList(), savedEnchants=Collections.emptyList();
```

- **Renderers:**
  - Line ~106: `review.getColumnModel().getColumn(1).setCellRenderer(new ItemCell(model->model<rows.size()?rows.get(model).drop.item:null,model->model<reviewEnchants.size()?reviewEnchants.get(model):null));`
  - Line ~178: `saved.getColumnModel().getColumn(1).setCellRenderer(new ItemCell(model->model<savedEntries.size()?savedEntries.get(model).review.drop.item:null,model->model<savedEnchants.size()?savedEnchants.get(model):null));`
- **In `refresh()`**, after `rows=…;Collections.reverse(rows);`, add:

```java
        List<EnchantInfo> enchants=new ArrayList<>(rows.size());for(BridgeService.Review r:rows)enchants.add(r.drop.item.enchantInfo());reviewEnchants=enchants;
```

  and in the row data, change `d.item.rarity` to `d.item.rarityLabel()`.
- **In `showSaved`**, after `Collections.reverse(savedEntries);`, add:

```java
        List<EnchantInfo> enchants=new ArrayList<>(savedEntries.size());for(BridgeJournal.Entry e:savedEntries)enchants.add(e.review.drop.item.enchantInfo());savedEnchants=enchants;
```

- **Saved details** (line ~231): `"\nRarity: "+i.rarityLabel()+…` in place of `i.rarity`.
- **`reviewCsv`**: `d.item.rarityLabel()` in place of `d.item.rarity`.
- **Live details** (`showDetails`): keep `"Rarity: "+i.rarity+" ("+i.raritySource+")"`. It already names the source.
- **`ItemCell`** becomes:

```java
    /** The item's sprite with its rarity gem, and the shared enchant tooltip (built on hover) for rows that kept enchant data. */
    private static final class ItemCell extends ContentStyle.Cell {
        private static final int SPRITE=16;
        private final IntFunction<BridgePayload.Item> item; // model row → item; null shows the kit placeholder
        private final IntFunction<EnchantInfo> enchant; // model row → its enchantments as decoded with the rows; null when none were kept
        private BridgePayload.Item current;
        private EnchantInfo currentEnchant;
        ItemCell(IntFunction<BridgePayload.Item> item,IntFunction<EnchantInfo> enchant){this.item=item;this.enchant=enchant;}
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){
            super.getTableCellRendererComponent(table,value,selected,focus,row,column);
            int model=row<0||row>=table.getRowCount()?-1:table.convertRowIndexToModel(row);
            current=model<0?null:item.apply(model);currentEnchant=model<0?null:enchant.apply(model);
            if(model>=0){setIcon(EnchantGem.decorate(Sprites.sprite(current==null?0:current.id,SPRITE),currentEnchant));setIconTextGap(Tokens.XS);}
            return this;
        }
        @Override public String getToolTipText(){
            return current==null||currentEnchant==null?super.getToolTipText()
                :EnchantTooltip.html(EnchantTooltip.heading(current.rawName,ItemTiers.label(current.id)),currentEnchant);
        }
    }
```

`ParseEnchants`:
- Delete `extractEnchants(Entity)`, which has no callers.
- Replace `parse`'s Javadoc with:

```java
    /**
     * Legacy text decoder, kept only for SendLoot's legacy "sl" wire field (its decoded line count). It stops at the first empty or
     * locked slot, so never use it for rarity or display: use {@link EnchantInfo}.
     */
```

`docs/BRIDGE.md`:
- **Line ~71:** replace the paragraph with:

  > Rarity: metadata tokens take precedence; otherwise the item's unlocked enchant slot count maps 0–4 to common/uncommon/rare/legendary/divine (locked slots do not count), the rule every RealmShark item surface uses. A bag slot without an enchant entry is sent as common, as before, and Review labels it "no enchant data". Malformed enchant data is isolated per item and reported as unknown when metadata cannot resolve rarity. The Enchanted category and Review's enchant filter count applied enchantments. Entries saved before this rule kept upstream's decoded line count; saved journals and both CSV exports label those "legacy count" and never recompute them.

- **Line ~24:** after "The **Item** column shows the item's sprite beside its name.", add:

  > Drops observed since enchant data was kept also show the rarity gem, and hovering the item lists the rolled enchantments.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.bridge.*' --tests 'tomato.gui.bridge.*' --tests 'tomato.gui.history.*' --tests 'tomato.realmshark.*'`
Expected: PASS, apart from the known window-size and evidence baseline.

Then confirm that `grep -rn "ParseEnchants.parse(" src/main` lists only `SendLoot.java`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/bridge src/main/java/tomato/gui/bridge/BridgeReviewGUI.java src/main/java/tomato/realmshark/ParseEnchants.java docs/BRIDGE.md src/test/java/tomato/bridge src/test/java/tomato/gui/bridge src/test/java/tomato/gui/history/FilterBarEvidenceTest.java src/test/java/ui/FinalScreensFixture.java
git commit -m "Take Bridge rarity from the enchant slot count and label legacy-count entries" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG"
```

---

### Task 5: Deferred loot, run and home fixes

**Files:**
- Modify: `src/main/java/tomato/gui/stats/LootArchiveClient.java` (line ~299; add `enchantmentsLine`)
- Modify: `src/main/java/tomato/gui/character/CharacterEquipmentPanel.java` (line ~81)
- Modify: `src/main/java/tomato/gui/loot/NotableDropRenderer.java` (`accessibleName`, `getListCellRendererComponent`, `getToolTipText`)
- Modify: `src/main/java/tomato/gui/runs/RunRecapModel.java` (`Players.Player` compact constructor)
- Modify: `src/main/java/tomato/gui/glance/home/HeroCard.java` (`gear`, line ~206)
- Test:
  - `src/test/java/tomato/gui/character/CharacterPlanningPanelTest.java`
  - `src/test/java/tomato/gui/loot/NotableDropRendererTest.java`
  - `src/test/java/tomato/gui/runs/RunCardRendererTest.java`
  - `src/test/java/tomato/gui/runs/RunRecapBuilderTest.java`
  - `src/test/java/tomato/gui/glance/home/HeroCardTest.java`
  - `src/test/java/tomato/gui/glance/character/GearTabTest.java` (rename only)
- Create: `src/test/java/tomato/gui/stats/LootArchiveDetailTest.java`

**Interfaces:**
- Produces: package-private `static String LootArchiveClient.enchantmentsLine(String enchantments)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/tomato/gui/stats/LootArchiveDetailTest.java`:

```java
package tomato.gui.stats;

import org.junit.Test;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.assertEquals;

/** The archive detail lists enchant lines indented once under their rarity line. */
public class LootArchiveDetailTest {
    @Test public void enchantLinesAreIndentedOnceUnderTheRarity() {
        assertEquals("\nEnchantments: Rare · 2 enchant slots\n  Enchant names not available", LootArchiveClient.enchantmentsLine(EnchantInfo.ofSlotCount(2).text()));
        assertEquals("\nEnchantments: Not recorded", LootArchiveClient.enchantmentsLine(null));
    }
}
```

`CharacterPlanningPanelTest`, in `equippedSlotsShowTheEnchantsLastObservedLive`: set `r.equipmentEnchants = new String[] {"AAIE__8=", "", "", ""}` (one unlocked, empty slot), and replace the detail assertion with:

```java
        assertTrue(rows.get(0).detail, rows.get(0).detail.contains("Enchantment effects: Uncommon · 1 enchant slot\n  (empty slot)"));
        assertFalse("Enchant lines are indented once", rows.get(0).detail.contains("\n    (empty slot)"));
```

`NotableDropRendererTest.anEnchantedDropSaysItsRarityAndShowsTheEnchantTooltipOnHover`: after the existing `assertTrue(tip, …)` on `tip`, add:

```java
            assertEquals("The tooltip says the rarity once: " + tip, 1, tip.split("Legendary · 3 enchant slots", -1).length - 1);
            assertTrue("Its heading still gives the facts: " + tip, tip.contains("enchanted, rare or better;"));
```

`RunCardRendererTest`: add the test below and its helpers, plus `import java.awt.Rectangle;` if not covered. It reuses `linked()`, `card(...)`, `REF`, `at`, `MINUTE`, `ZONE` and `NOW`.

```java
    @Test public void theGemSitsInTheTopRightCornerOfItsItemsWell() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunCardModel plain = linked();
            RunCardModel.LootItem first = plain.loot().get(0);
            String otherBag = "White".equals(first.bag()) ? "Orange" : "White";
            Rectangle well = changed(card(plain), card(withFirst(new RunCardModel.LootItem(first.id(), otherBag, first.tier()))));
            Rectangle gem = changed(card(plain), card(withFirst(new RunCardModel.LootItem(first.id(), first.bag(), first.tier(), EnchantInfo.ofSlotCount(3)))));
            assertNotNull("The bag colour tints the first well", well);
            assertNotNull("The gem is painted", gem);
            assertTrue("Gem " + gem + " in the right half of well " + well, gem.x >= well.x + well.width / 2 && gem.x + gem.width <= well.x + well.width);
            assertTrue("Gem " + gem + " in the top half of well " + well, gem.y >= well.y && gem.y + gem.height <= well.y + well.height / 2 + 1);
        });
    }

    /** linked() with its first loot item replaced. */
    private static RunCardModel withFirst(RunCardModel.LootItem item) {
        List<RunCardModel.LootItem> loot = new ArrayList<>(linked().loot());
        loot.set(0, item);
        return new RunCardModel(REF, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, at(0, 8, 5), 25 * MINUTE, 6,
            new RunCardModel.Combat("r-v1-long", 2, 6_000L, 20d, 2, 6, 30d, 1, null), null, loot, 6, "1 UT · 1 ST · 2 potions", null, 240L, 2);
    }

    /** The bounds of the pixels that differ between two same-size images; null when none do. */
    private static Rectangle changed(BufferedImage a, BufferedImage b) {
        Rectangle bounds = null;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (a.getRGB(x, y) != b.getRGB(x, y)) { Rectangle p = new Rectangle(x, y, 1, 1); bounds = bounds == null ? p : bounds.union(p); }
        return bounds;
    }
```

If `linked()`'s arguments differ from those in `withFirst`, copy them from `linked()` exactly. The existing `enchantedLootPaintsItsRarityGemAndKeepsTheCardsWords` constructs the same model and shows them.

`RunRecapBuilderTest`: add (imports `java.util.Arrays`, `java.util.List` if missing):

```java
    @Test public void aPlayersEnchantsAlwaysMatchTheirEquipment() {
        RunRecapModel.Players.Player shortList = new RunRecapModel.Players.Player(1, "A", "Wizard", 782, Arrays.asList(1, 2, 3, 4), null, 0L,
            List.of(EnchantInfo.ofSlotCount(2)));
        assertEquals(4, shortList.enchants().size());
        assertEquals(EnchantInfo.ofSlotCount(2), shortList.enchants().get(0));
        assertSame(EnchantInfo.notRecorded(), shortList.enchants().get(3));
        RunRecapModel.Players.Player nullEntry = new RunRecapModel.Players.Player(1, "A", "Wizard", 782, Arrays.asList(1, 2), null, 0L,
            Arrays.asList(null, EnchantInfo.unreadable(), EnchantInfo.unreadable()));
        assertEquals(Arrays.asList(EnchantInfo.notRecorded(), EnchantInfo.unreadable()), nullEntry.enchants());
    }
```

`HeroCardTest`: add:

```java
    @Test public void aShortEnchantListLeavesTheOtherSlotsWithoutAGem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            HomeModel.Hero h = HomeModels.hero(HomeModel.State.STALE, NOW);
            EnchantInfo uncommon = EnchantInfo.ofSlotCount(1);
            card.apply(new HomeModel.Hero(h.state(), h.name(), h.classId(), h.className(), h.skin(), h.level(), h.fame(), h.maxed(),
                h.base(), h.caps(), h.totals(), h.potionsNeeded(), h.needsLine(), h.exaltTiers(), new int[]{2593, 2594, -1, -1},
                h.weaponDps(), h.mpPerSecond(), h.accountLine(), h.lastSeenAt(), h.evidence(), h.key(), h.petChip(), List.of(uncommon)), NOW);
            assertEquals(uncommon, named(card, "home-hero-slot-0", ItemSlot.class).enchant());
            assertNull(named(card, "home-hero-slot-1", ItemSlot.class).enchant());
        });
    }
```

`GearTabTest`: rename `enchantGemsShowOnlyForTheLiveCharacter` to `liveEnchantGemsShowAndARecordWithoutSavedEnchantsShowsNone`. Its body is unchanged.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.stats.LootArchiveDetailTest --tests tomato.gui.character.CharacterPlanningPanelTest --tests tomato.gui.loot.NotableDropRendererTest --tests tomato.gui.runs.RunCardRendererTest --tests tomato.gui.runs.RunRecapBuilderTest --tests tomato.gui.glance.home.HeroCardTest`
Expected:
- Compile failure on `enchantmentsLine`. With a stub:
  - the detail and tooltip tests fail (a 4-space indent, and the rarity said twice);
  - `aPlayersEnchantsAlwaysMatchTheirEquipment` fails on size 1 or a `NullPointerException`;
  - the HeroCard test fails with `IndexOutOfBoundsException`.
- `theGemSitsInTheTopRightCornerOfItsItemsWell` passes, because it pins existing behaviour.

- [ ] **Step 3: Implement**

`LootArchiveClient`: add to the outer class:

```java
    /** The detail's enchantment lines; the stored text already indents each enchant under its rarity line. */
    static String enchantmentsLine(String enchantments){return "\nEnchantments: "+(enchantments==null?"Not recorded":enchantments);}
```

Replace line ~299 with `text.append(enchantmentsLine(row.value.enchantments));`.

`CharacterEquipmentPanel` (line ~81): `+ "\nEnchantment effects: " + (enchant == null ? "Not recorded in this character snapshot" : enchant.text());`

`NotableDropRenderer`:
- Split the facts so the enchant tooltip's heading can leave the rarity out:

```java
    static String accessibleName(HighlightsModel.Notable drop, ZoneId zone, long now) { return facts(drop, zone, now, true); }

    /** The drop's facts in words; {@code withEnchant} adds the enchant summary after the kind (the enchant tooltip has its own line for it). */
    private static String facts(HighlightsModel.Notable drop, ZoneId zone, long now, boolean withEnchant) {
```

  The body is the old `accessibleName` body, with the enchant line guarded by `withEnchant &&`.
- Add a field `private String enchantHeading;`.
- In `getListCellRendererComponent`, after `lines = …`:

```java
        enchantHeading = value == null ? null : facts(value, zone, at, false) + " · " + HighlightsModel.OBSERVED;
```

- In `getToolTipText`:

```java
        // The enchant tooltip says the rarity on its own line, so its heading leaves it out.
        return EnchantTooltip.html(enchantHeading, drop.enchant());
```

`RunRecapModel.Players.Player`, compact constructor:

```java
            public Player {
                equipment = Collections.unmodifiableList(new ArrayList<>(equipment));
                // One entry per equipped slot, so views can index them together; a missing or null entry is not recorded.
                List<EnchantInfo> sized = new ArrayList<>(equipment.size());
                for (int i = 0; i < equipment.size(); i++)
                    sized.add(enchants != null && i < enchants.size() && enchants.get(i) != null ? enchants.get(i) : EnchantInfo.notRecorded());
                enchants = List.copyOf(sized);
            }
```

`HeroCard.gear` (line ~206):

```java
            EnchantInfo enchant = enchants == null || i >= enchants.size() || enchants.get(i).state() == EnchantInfo.State.NOT_RECORDED ? null : enchants.get(i);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.stats.*' --tests 'tomato.gui.character.*' --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.glance.*'`
Expected: PASS, apart from the known `LootHighlightsTest`, `DungeonsSourceTest` and `HomeRefreshTimingTest` baselines.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/stats/LootArchiveClient.java src/main/java/tomato/gui/character/CharacterEquipmentPanel.java src/main/java/tomato/gui/loot/NotableDropRenderer.java src/main/java/tomato/gui/runs/RunRecapModel.java src/main/java/tomato/gui/glance/home/HeroCard.java src/test/java/tomato/gui
git commit -m "Indent enchant lines once, say the Highlights rarity once and guard enchant list sizes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG"
```

---

### Task 6: Phase verification, review and PR

**Files:** none changed unless a check fails.

- [ ] **Step 1: Leftover and compatibility scan**
  - Search the branch diff (`git diff main...HEAD -- src`) for `TODO`, `@Ignore` and `.skip(`: expect none.
  - `git diff main...HEAD -- src/main/java/tomato/backend/data/Damage.java` changes only the `ownerEnchants = …` line.
  - `git diff main...HEAD -- src/main/java/tomato/backend/data/Equipment.java src/main/java/packets/data/StatData.java src/main/java/tomato/realmshark/SendLoot.java` is empty.
  - `BridgeJournal.VERSION` is still 1.
  - `BridgePayload.loot(...)` adds the same keys as on `main`.
  - `grep -rn "ParseEnchants.parse(" src/main` lists only `SendLoot.java`.

- [ ] **Step 2: Focused suite plus build**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.backend.data.*' --tests 'tomato.bridge.*' --tests 'tomato.gui.bridge.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.dps.*' --tests 'tomato.gui.security.*' --tests 'tomato.gui.myinfo.*' --tests 'tomato.gui.character.*' --tests 'tomato.gui.stats.*' --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.glance.*' --tests 'tomato.gui.history.*' shadowJar`
Expected: BUILD SUCCESSFUL, or only the known environmental failures. Confirm anything else against `main` in a temporary worktree before calling it a regression.

- [ ] **Step 3: Visual check**

Open the Bridge evidence captures that `BridgeUiTest` writes under `screenshots/` (`bridge-1240-0.png`, `bridge-680-0.png`). Check that the enchanted "Test Sword (Shiny)" row's sprite carries a gem. If a capture does not show that row, say so and rely on the renderer tests.

- [ ] **Step 4: Launch smoke**

Launch the built jar from an isolated folder:
- its own copy of `assets/`;
- `util.InMemoryPreferencesFactory` from `build/classes/java/test` on the classpath;
- `-Drealmshark.historyDir=<isolated folder>`.

Do **not** start capture or enable the bridge. Confirm the app reaches Home and opens Bridge Review, has no console exception, and closes cleanly.

- [ ] **Step 5: Independent review**

Dispatch a fresh reviewer on the branch diff against `main`. Give it the spec path, this plan's path and the Review Focus list. Fix confirmed findings test-first, then rerun Step 2 for the touched packages.

- [ ] **Step 6: Push and open the PR (after the user confirms)**

```bash
git push -u origin feat/enchant-rarity-p4
gh pr create --base main --title "Bridge enchant rarity from slot count, DPS most-used variant and enchant cleanup (phase 4)" --body-file <body file outside the repo index>
```

The PR body's sections:
- **What changes:** Bridge rarity rule and Review gem, legacy-count labels, DPS variant and unenchanted retention, headings, and the deferred fixes.
- **Compatibility:**
  - Wire keys and vocabulary are unchanged, and `SendLoot` is untouched.
  - The journal stays at version 1 with an optional `enchantData`.
  - `Damage`, `StatData` and `Equipment` gain no fields.
  - Old `.dps` files keep reading "not recorded".
- **Validation:** the Step 2–4 results.
- **Not addressed:** the refinements' "Not addressed" list.

End the body with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_015pGRDr87HCz1xus7EdojbG
```
