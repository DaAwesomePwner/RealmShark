# Enchant Rarity Phase 1 (Foundation and Live Surfaces) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every equipped item on the Party roster, DPS icon view, DPS event detail, Character Gear tab and My Info shows its enchant rarity as a corner gem and, on hover, the enchants it rolled, all from one shared decoder, catalog, color set, gem painter and tooltip.

**Architecture:** `EnchantInfo` (a record in `tomato.realmshark`) is decoded once per item from the strict `ParseEnchants.evidence()` decoder; `ParseEnchants` also keeps each enchantment's display name and description. The kit gains `Tokens.rarity`, `EnchantGem` (paint and `decorate(Icon, EnchantInfo)`) and `EnchantTooltip`, and `ItemSlot` accepts an `EnchantInfo`. The five live surfaces then switch from their own rules (`parse()` line counts, `EnchantDots`, raw blobs) to these pieces.

**Tech Stack:** Java 17 (`--release 17`), Swing/FlatLaf, JUnit 4, Gradle 7.6.4 wrapper.

**Spec:** `docs/superpowers/specs/2026-09-30-equipment-enchant-rarity-design.md` (Phase 1 section, plus Architecture, Compatibility rules, Error handling, Testing).

**Deliberate refinements of the spec (smaller API, same behavior):**
- The gem painter is its own kit class, `EnchantGem`, instead of `Sprites.paintGem`.
- `EnchantGem.decorate(Icon, EnchantInfo)` replaces the proposed `ItemSlot.icon(..., EnchantInfo)` overload, because table cells and the DPS labels need to decorate icons that are not wells.
- Tooltip HTML lives in the kit (`EnchantTooltip.html`) rather than on `EnchantInfo`, which stays free of presentation.
- `Definition` keeps only the display name and description. The `TIER1`–`TIER4` label is not stored, because display names already carry I–IV and nothing reads it.

## How to run things

All commands run from the repository root in Git Bash. Gradle needs the project-local JDK and Gradle home exported in the same command, plus `--offline`. Without them it picks up the Java 8 on PATH and tries to download Gradle. Shell state does not persist between tool calls, so each step below repeats the full command. If Gradle reports failures from test classes that no longer exist, or cannot delete build outputs, run `./gradlew.bat clean` with the same environment and retry.

Work on branch `feat/enchant-rarity-p1`, created from `docs/enchant-rarity-spec` so the spec and this plan travel with the PR:

```bash
git switch docs/enchant-rarity-spec && git switch -c feat/enchant-rarity-p1
```

## Global Constraints

- Java 17, `--release 17`; no new dependencies.
- **Rarity is the unlocked slot count:** 0 Unenchanted, 1 Uncommon, 2 Rare, 3 Legendary, 4 Divine.
- **Summary strings, used verbatim everywhere:**
  - `"Unenchanted"`, `"<Rarity> · 1 enchant slot"` or `"<Rarity> · N enchant slots"`;
  - `"Enchants not recorded"` and `"Enchant data unreadable"`;
  - slot lines `"<Display name> — <Description>"` and `"(empty slot)"`;
  - unknown types `"Unknown enchant (0x<hex>)"`.
- **Gem colors:** Uncommon = `Tokens.Role.GOOD` (mint), Rare = `INFO` (blue), Legendary = `ACCENT_TEXT` (violet), Divine = `WARN` (amber), unreadable = `TEXT_MUTED`. Unenchanted and not recorded have no gem.
- The gem sits in the top-right corner inside the well's edge, and the tier border keeps its meaning.
- Do not add fields or methods to `Damage`, `StatData` or `Equipment`: they have no pinned `serialVersionUID`, and old `.dps` files must keep loading.
- Wire formats (HTTP bridge payload, `SendLoot`) are unchanged. Bridge, loot, runs and characters beyond the Gear tab are later phases.
- Swing work stays on the EDT. Decoding never throws into UI code. Tooltip HTML is never built per paint.
- Never start live capture or send bridge deliveries during validation. No force pushes, hook bypasses or commits to `main`.
- End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU
  ```

## Review Focus

1. **Blob spelling from live packets.** Padded vs unpadded URL Base64 (`AAIE_wU` vs `AAIE_wU=`) and the common all-terminator blob `AAIE_f_9__3__f8=` must give the same rarity as the strict decoder, not a different one or an exception. Pinned in Task 2.
2. **Applied enchant after a locked or empty slot** (`-2, -1, 42`). It must be listed; the old `parse()` silently dropped it. Pinned in Task 2.
3. **Enchant type missing from the catalog, or catalog not loaded** (assets not extracted yet). The tooltip must name `Unknown enchant (0x…)` and must not throw. Pinned in Tasks 1 and 3.
4. **HTML-significant characters or null in item names and descriptions** (`<`, `&`, a null `IdToAsset.objectName`). The tooltip must escape them and never throw. Pinned in Task 3.
5. **Theme switch while gems are on screen.** Gem and tooltip colors must follow the new theme, never a cached old one. Pinned in Tasks 3 and 6.

---

### Task 1: Enchantment definitions (display name and description)

**Files:**
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (fields near line 35, `prepareReload` lines 61-193, helpers section near line 795)
- Modify: `src/test/java/assets/AssetGenerationFixture.java:34`
- Modify: `src/test/java/assets/AssetRecoveryTest.java:29`
- Create: `src/test/java/tomato/realmshark/ParseEnchantsDefinitionTest.java`

**Interfaces:**
- Produces: `public record ParseEnchants.Definition(String displayName, String description)`; `public static volatile HashMap<Short, Definition> ParseEnchants.ENCHANT_DEFINITIONS`; `public static Definition ParseEnchants.definition(int typeId)`; package-private `static Definition ParseEnchants.readDefinition(StringXML enchantment)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/tomato/realmshark/ParseEnchantsDefinitionTest.java`:

```java
package tomato.realmshark;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.Test;
import util.StringXML;
import static org.junit.Assert.assertEquals;

/** Display names and descriptions for tooltips, read from enchantments.xml alongside the existing maps. */
public class ParseEnchantsDefinitionTest {
    private static final String ATTACK = "<Enchantment id=\"Attack_Bonus_1\" type=\"0x107\">\n  <DisplayId>Attack Bonus I</DisplayId>\n"
        + "  <Description>Increases Attack by 1.4</Description>\n  <Weight>35000</Weight>\n</Enchantment>";

    private static StringXML enchantment(String xml) throws Exception {
        for (StringXML node : StringXML.getParsedXML("<Enchantments>" + xml + "</Enchantments>"))
            if ("Enchantment".equals(node.name)) return node;
        throw new AssertionError("No <Enchantment> in " + xml);
    }

    @Test public void readsTheDisplayNameAndDescription() throws Exception {
        assertEquals(new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"), ParseEnchants.readDefinition(enchantment(ATTACK)));
    }

    @Test public void fallsBackToTheInternalIdAndAnEmptyDescription() throws Exception {
        assertEquals(new ParseEnchants.Definition("UNIQUE_THING", ""),
            ParseEnchants.readDefinition(enchantment("<Enchantment id=\"UNIQUE_THING\" type=\"0x9\"><DisplayId/></Enchantment>")));
    }

    @Test public void unknownTypesNameTheRawTypeInHex() {
        HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            ParseEnchants.ENCHANT_DEFINITIONS = new HashMap<>();
            assertEquals(new ParseEnchants.Definition("Unknown enchant (0x5ff)", ""), ParseEnchants.definition(0x5ff));
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }

    @Test public void reloadPublishesDefinitionsWithTheOtherMaps() throws Exception {
        String[] maps = {"ENCHANTS", "ENCHANT_EFFECTS", "ENCHANT_REGEN", "ENCHANT_LOOT_BONUS", "ENCHANT_DEFINITIONS"};
        Object[] saved = new Object[maps.length];
        for (int i = 0; i < maps.length; i++) saved[i] = field(maps[i]).get(null);
        Path xml = Files.createTempFile("enchantments", ".xml");
        try {
            Files.write(xml, ("<Enchantments>" + ATTACK + "</Enchantments>").getBytes(StandardCharsets.UTF_8));
            ParseEnchants.prepareReload(xml).run();
            assertEquals("Attack Bonus I", ParseEnchants.definition(0x107).displayName());
            assertEquals("Attack_Bonus_1", ParseEnchants.ENCHANTS.get((short) 0x107));
        } finally {
            for (int i = 0; i < maps.length; i++) field(maps[i]).set(null, saved[i]);
            Files.deleteIfExists(xml);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = ParseEnchants.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.ParseEnchantsDefinitionTest`
Expected: FAIL at compile time: `cannot find symbol ... Definition` / `readDefinition` / `ENCHANT_DEFINITIONS`.

- [ ] **Step 3: Implement**

In `ParseEnchants.java`, directly below the `ENCHANT_LOOT_BONUS` field and **above** the `static { reload(); ... }` block. Static initializers run in textual order, so a field declared below the block would be reset to an empty map after `reload()` filled it.

```java
    // Maps enchant type ID -> what a tooltip shows: the game's display name and description
    public static volatile HashMap<Short, Definition> ENCHANT_DEFINITIONS = new HashMap<>();

    /** An enchantment as the game describes it; {@code description} is "" when the definition has none. */
    public record Definition(String displayName, String description) {}

    /** The definition of an enchantment type; unknown types, and all types before definitions load, name the raw type in hex. */
    public static Definition definition(int typeId) {
        Definition known = ENCHANT_DEFINITIONS.get((short) typeId);
        return known != null ? known : new Definition(String.format("Unknown enchant (0x%x)", typeId), "");
    }
```

In `prepareReload`, add beside the other local maps:

```java
        HashMap<Short, Definition> definitions = new HashMap<>();
```

inside `if (enchantType != null) { ... }` add:

```java
                    definitions.put(enchantType, readDefinition(xml));
```

and change the returned swap to:

```java
            return () -> { ENCHANTS = names; ENCHANT_EFFECTS = effects; ENCHANT_REGEN = regeneration; ENCHANT_LOOT_BONUS = loot; ENCHANT_DEFINITIONS = definitions; };
```

In the `// ===== Helpers =====` section add:

```java
    /** One {@code <Enchantment>}'s display name (its DisplayId, else its internal id) and description. */
    static Definition readDefinition(StringXML enchantment) {
        String id = null, display = null, description = null;
        for (StringXML node : enchantment) {
            if (Objects.equals(node.name, "id")) id = node.value;
            else if (Objects.equals(node.name, "DisplayId")) display = text(node);
            else if (Objects.equals(node.name, "Description")) description = text(node);
        }
        String name = display != null && !display.isEmpty() ? display : id != null ? id : "Unnamed enchant";
        return new Definition(name, description == null ? "" : description);
    }

    /** An element's text; StringXML keeps it as a "#text" child. Null when the element is empty. */
    private static String text(StringXML node) {
        for (StringXML child : node) if (Objects.equals(child.name, "#text") && child.value != null) return child.value.trim();
        return null;
    }
```

Add `"ENCHANT_DEFINITIONS"` to the field-name arrays in `src/test/java/assets/AssetGenerationFixture.java:34` and `src/test/java/assets/AssetRecoveryTest.java:29`, so those fixtures save and restore the new map like the other four.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.ParseEnchantsDefinitionTest --tests 'tomato.realmshark.ParseEnchants*' --tests assets.AssetRecoveryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/realmshark/ParseEnchants.java src/test/java/tomato/realmshark/ParseEnchantsDefinitionTest.java src/test/java/assets/AssetGenerationFixture.java src/test/java/assets/AssetRecoveryTest.java
git commit -m "Keep enchantment display names and descriptions for tooltips" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 2: `EnchantInfo`, one item's decoded enchantments

**Files:**
- Create: `src/main/java/tomato/realmshark/EnchantInfo.java`
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (`EquippedCapture`, lines 350-395)
- Modify: `src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java:60`
- Create: `src/test/java/tomato/realmshark/EnchantInfoTest.java`

**Interfaces:**
- Consumes: `ParseEnchants.evidence(String)`, `ParseEnchants.Evidence` (`state`, `orderedSlotIds`), `ParseEnchants.definition(int)` (Task 1).
- Produces:
  - `public record EnchantInfo(State state, Rarity rarity, List<Slot> slots)`.
  - Enums `EnchantInfo.State {RECORDED, NOT_RECORDED, UNREADABLE}` and `EnchantInfo.Rarity {UNENCHANTED, UNCOMMON, RARE, LEGENDARY, DIVINE, UNKNOWN}`, where each Rarity has a public `label`.
  - `public record EnchantInfo.Slot(int typeId)` with `boolean empty()`.
  - Factories `static EnchantInfo of(String)`, `ofRetained(String)`, `fromEvidence(ParseEnchants.Evidence)`, `notRecorded()`, `unreadable()`.
  - Instance methods `boolean enchanted()`, `String summary()`, `List<String> slotLines()`, `String text()`.
  - `public EnchantInfo ParseEnchants.EquippedCapture.info(int slot)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/tomato/realmshark/EnchantInfoTest.java`:

```java
package tomato.realmshark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.realmshark.EnchantInfo.Rarity;
import tomato.realmshark.EnchantInfo.Slot;
import tomato.realmshark.EnchantInfo.State;
import static org.junit.Assert.*;

/** The one decoded form every item surface shows; no enchantment definitions or assets needed except where stated. */
public class EnchantInfoTest {

    @Test public void rarityIsTheUnlockedSlotCount() {
        assertEquals(Rarity.UNENCHANTED, EnchantInfo.of(encode()).rarity());
        assertEquals(Rarity.UNCOMMON, EnchantInfo.of(encode(-1)).rarity());
        assertEquals(Rarity.RARE, EnchantInfo.of(encode(-1, 42)).rarity());
        assertEquals(Rarity.LEGENDARY, EnchantInfo.of(encode(-1, 42, 7)).rarity());
        EnchantInfo divine = EnchantInfo.of(encode(-1, -1, -1, -1));
        assertEquals(Rarity.DIVINE, divine.rarity());
        assertEquals(List.of(new Slot(-1), new Slot(-1), new Slot(-1), new Slot(-1)), divine.slots());
    }

    @Test public void anAppliedEnchantAfterALockedOrEmptySlotIsListed() {
        EnchantInfo info = EnchantInfo.of(encode(-2, -1, 42));
        assertEquals(State.RECORDED, info.state());
        assertEquals(Rarity.RARE, info.rarity());
        assertEquals(List.of(new Slot(-1), new Slot(42)), info.slots());
    }

    @Test public void theTerminatorEndsTheSlots() {
        assertEquals(List.of(new Slot(42)), EnchantInfo.of(encode(42, -3, 7)).slots());
    }

    @Test public void paddedAndUnpaddedSpellingsDecodeAlike() {
        assertEquals(EnchantInfo.of("AAIE_wU="), EnchantInfo.of("AAIE_wU"));
        assertEquals(List.of(new Slot(0x5ff)), EnchantInfo.of("AAIE_wU").slots());
    }

    @Test public void theCommonUnusedBlobAndTheEmptyStringAreUnenchanted() {
        for (String code : new String[] {"AAIE_f_9__3__f8=", "AAIE_f_9__3__f8", "", "AAIE"}) {
            EnchantInfo info = EnchantInfo.of(code);
            assertEquals(code, State.RECORDED, info.state());
            assertEquals(code, Rarity.UNENCHANTED, info.rarity());
            assertFalse(code, info.enchanted());
            assertEquals("Unenchanted", info.summary());
        }
    }

    @Test public void missingDataIsNotRecordedAndBadDataIsUnreadable() {
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.of((String) null));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(null));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.fromEvidence(ParseEnchants.legacyEvidence()));
        for (String bad : new String[] {"!!!", "AA==", "AAAE", encode(-4), "A".repeat(4097)}) {
            EnchantInfo info = EnchantInfo.of(bad);
            assertSame(bad, EnchantInfo.unreadable(), info);
            assertEquals(Rarity.UNKNOWN, info.rarity());
            assertFalse(info.enchanted());
        }
        assertEquals("Enchants not recorded", EnchantInfo.notRecorded().summary());
        assertEquals("Enchant data unreadable", EnchantInfo.unreadable().summary());
    }

    /** ParseEnchants.getEnchantStrings turns a missing stat into "", so a retained "" cannot claim unenchanted. */
    @Test public void aRetainedEmptyStringIsNotRecorded() {
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(""));
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(null));
        assertEquals(Rarity.UNCOMMON, EnchantInfo.ofRetained(encode(-1)).rarity());
    }

    @Test public void summaryAndSlotLinesNameEachSlot() {
        HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
            definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
            definitions.put((short) 9, new ParseEnchants.Definition("Plain", ""));
            ParseEnchants.ENCHANT_DEFINITIONS = definitions;
            EnchantInfo info = EnchantInfo.of(encode(42, -1, 7, 9));
            assertTrue(info.enchanted());
            assertEquals("Divine · 4 enchant slots", info.summary());
            assertEquals(List.of("Attack Bonus I — Increases Attack by 1.4", "(empty slot)", "Unknown enchant (0x7)", "Plain"), info.slotLines());
            assertEquals("Divine · 4 enchant slots\n  Attack Bonus I — Increases Attack by 1.4\n  (empty slot)\n  Unknown enchant (0x7)\n  Plain", info.text());
            assertEquals("Uncommon · 1 enchant slot", EnchantInfo.of(encode(-1)).summary());
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }

    @Test public void equippedCaptureInfoFollowsEachSlotsState() {
        Entity player = new Entity(null, 1, 0);
        StatData stat = new StatData();
        stat.stringStatValue = "AAIE_wU,,!!!"; // weapon one applied, ability "", armor malformed, ring missing
        player.stat.set(StatType.UNIQUE_DATA_STRING, stat);
        ParseEnchants.EquippedCapture capture = ParseEnchants.equippedCapture(player);
        assertEquals(Rarity.UNCOMMON, capture.info(0).rarity());
        assertEquals(Rarity.UNENCHANTED, capture.info(1).rarity());
        assertSame(EnchantInfo.unreadable(), capture.info(2));
        assertSame(EnchantInfo.notRecorded(), capture.info(3));
    }

    private static String encode(int... entries) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + entries.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0);
        buffer.putShort((short) 1026);
        for (int entry : entries) buffer.putShort((short) entry);
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.EnchantInfoTest`
Expected: FAIL at compile time: `cannot find symbol ... EnchantInfo`.

- [ ] **Step 3: Implement**

Create `src/main/java/tomato/realmshark/EnchantInfo.java`:

```java
package tomato.realmshark;

import java.util.ArrayList;
import java.util.List;

/**
 * One item's enchantments as every item surface shows them, decoded from its UNIQUE_DATA_STRING entry with the strict decoder
 * ({@link ParseEnchants#evidence}). The rarity is the unlocked slot count (0 Unenchanted … 4 Divine); {@code slots} are the
 * unlocked slots in order, locked ones omitted. Not-recorded and unreadable data never claim a rarity. Immutable.
 */
public record EnchantInfo(State state, Rarity rarity, List<Slot> slots) {
    public enum State { RECORDED, NOT_RECORDED, UNREADABLE }

    public enum Rarity {
        UNENCHANTED("Unenchanted"), UNCOMMON("Uncommon"), RARE("Rare"), LEGENDARY("Legendary"), DIVINE("Divine"), UNKNOWN("Unknown");
        public final String label;
        Rarity(String label) { this.label = label; }
        static Rarity ofSlots(int unlocked) { return unlocked >= 0 && unlocked <= 4 ? values()[unlocked] : UNKNOWN; }
    }

    /** One unlocked slot: an applied enchantment's type, or empty (-1). */
    public record Slot(int typeId) {
        public boolean empty() { return typeId < 0; }
    }

    private static final EnchantInfo NOT_RECORDED = new EnchantInfo(State.NOT_RECORDED, Rarity.UNKNOWN, List.of());
    private static final EnchantInfo UNREADABLE = new EnchantInfo(State.UNREADABLE, Rarity.UNKNOWN, List.of());

    public EnchantInfo { slots = List.copyOf(slots); }

    public static EnchantInfo notRecorded() { return NOT_RECORDED; }
    public static EnchantInfo unreadable() { return UNREADABLE; }

    /** One item's blob: null is not recorded, "" is the protocol's known-unenchanted shorthand. */
    public static EnchantInfo of(String blob) { return fromEvidence(ParseEnchants.evidence(blob)); }

    /**
     * A blob retained through {@link ParseEnchants#getEnchantStrings}, which turns a missing stat into "": there "" cannot be told
     * from unenchanted, so it reads as not recorded.
     */
    public static EnchantInfo ofRetained(String blob) { return blob == null || blob.isEmpty() ? NOT_RECORDED : of(blob); }

    public static EnchantInfo fromEvidence(ParseEnchants.Evidence evidence) {
        if (evidence == null) return NOT_RECORDED;
        switch (evidence.state) {
            case RECORDED: case RECORDED_EMPTY: break;
            case INVALID: return UNREADABLE;
            default: return NOT_RECORDED;
        }
        List<Slot> unlocked = new ArrayList<>();
        for (int id : evidence.orderedSlotIds) {
            if (id == -3) break;
            if (id >= -1) unlocked.add(new Slot(id));
        }
        return new EnchantInfo(State.RECORDED, Rarity.ofSlots(unlocked.size()), unlocked);
    }

    /** True when there is a rarity to show: recorded, with at least one unlocked slot. */
    public boolean enchanted() { return state == State.RECORDED && rarity != Rarity.UNENCHANTED && rarity != Rarity.UNKNOWN; }

    /** "Rare · 2 enchant slots", "Unenchanted", "Enchants not recorded" or "Enchant data unreadable". */
    public String summary() {
        switch (state) {
            case NOT_RECORDED: return "Enchants not recorded";
            case UNREADABLE: return "Enchant data unreadable";
            default: return rarity == Rarity.UNENCHANTED ? "Unenchanted"
                : rarity.label + " · " + slots.size() + (slots.size() == 1 ? " enchant slot" : " enchant slots");
        }
    }

    /** Each unlocked slot in order: "Attack Bonus I — Increases Attack by 1.4", the name alone without a description, or "(empty slot)". */
    public List<String> slotLines() {
        List<String> lines = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.empty()) { lines.add("(empty slot)"); continue; }
            ParseEnchants.Definition definition = ParseEnchants.definition(slot.typeId());
            lines.add(definition.description().isEmpty() ? definition.displayName() : definition.displayName() + " — " + definition.description());
        }
        return lines;
    }

    /** Plain text for dialogs and accessible descriptions: the summary, then one indented line per unlocked slot. */
    public String text() {
        StringBuilder text = new StringBuilder(summary());
        for (String line : slotLines()) text.append("\n  ").append(line);
        return text.toString();
    }
}
```

In `ParseEnchants.EquippedCapture`, add below `unlockedSlots`:

```java
        /** The slot's enchantments: not recorded while MISSING, unreadable while MALFORMED. */
        public EnchantInfo info(int slot) {
            return states[slot] == CaptureState.MISSING ? EnchantInfo.notRecorded()
                : states[slot] == CaptureState.MALFORMED ? EnchantInfo.unreadable() : EnchantInfo.of(codes[slot]);
        }
```

In `src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java:60`, replace `assertFalse(capture.description(0).isEmpty());` with a check that no longer depends on `description()`, which Task 8 deletes:

```java
        assertEquals(java.util.List.of(new EnchantInfo.Slot(0x5ff)), capture.info(0).slots());
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.realmshark.EnchantInfoTest --tests tomato.realmshark.EquippedEnchantCaptureTest --tests tomato.realmshark.ParseEnchantsSummaryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/realmshark/EnchantInfo.java src/main/java/tomato/realmshark/ParseEnchants.java src/test/java/tomato/realmshark/EnchantInfoTest.java src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java
git commit -m "Decode each item's enchant rarity and slots into EnchantInfo" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 3: Kit rarity colors, gem painter and tooltip

**Files:**
- Modify: `src/main/java/tomato/gui/kit/Tokens.java` (after `tier`, line 124)
- Create: `src/main/java/tomato/gui/kit/EnchantGem.java`
- Create: `src/main/java/tomato/gui/kit/EnchantTooltip.java`
- Create: `src/test/java/tomato/gui/kit/EnchantGemTest.java`
- Create: `src/test/java/tomato/gui/kit/EnchantTooltipTest.java`

**Interfaces:**
- Consumes: `EnchantInfo` (Task 2), `ParseEnchants.definition(int)` (Task 1).
- Produces:
  - `public static Color Tokens.rarity(EnchantInfo.Rarity)`, which returns null for UNENCHANTED and UNKNOWN.
  - `public static void EnchantGem.paint(Graphics2D g, EnchantInfo info, int x, int y, int side)`, using the same geometry as `ItemSlot.paint`: a well `side + 1` px square at x, y.
  - `public static Icon EnchantGem.decorate(Icon base, EnchantInfo info)`, which returns `base` itself when no gem is shown.
  - Package-private `static int EnchantGem.size(int side)` and `static Color EnchantGem.ink(EnchantInfo)`.
  - `public static String EnchantTooltip.html(String heading, EnchantInfo info)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/tomato/gui/kit/EnchantGemTest.java`:

```java
package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.EnchantInfo.Rarity;
import static org.junit.Assert.*;

/** The rarity gem: one color per rarity, in the well's top-right corner inside its edge, resolved from the theme at paint time. */
public class EnchantGemTest {
    private static final Rarity[] GEMS = {Rarity.UNCOMMON, Rarity.RARE, Rarity.LEGENDARY, Rarity.DIVINE};

    static EnchantInfo recorded(Rarity rarity) {
        return new EnchantInfo(EnchantInfo.State.RECORDED, rarity, java.util.Collections.nCopies(rarity.ordinal(), new EnchantInfo.Slot(-1)));
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void eachRarityHasItsOwnColorInEveryVariant() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                Set<Integer> inks = new HashSet<>();
                for (Rarity rarity : GEMS) inks.add(Tokens.rarity(rarity).getRGB());
                assertEquals(variant + ": four distinct gem colors", 4, inks.size());
                assertNull(Tokens.rarity(Rarity.UNENCHANTED));
                assertNull(Tokens.rarity(Rarity.UNKNOWN));
            }
        });
    }

    @Test public void noGemForUnenchantedNotRecordedOrNoData() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon base = ItemSlot.icon(null, "UT", ItemSlot.State.ITEM, 20);
            assertSame(base, EnchantGem.decorate(base, recorded(Rarity.UNENCHANTED)));
            assertSame(base, EnchantGem.decorate(base, EnchantInfo.notRecorded()));
            assertSame(base, EnchantGem.decorate(base, null));
            assertNull(EnchantGem.decorate(null, recorded(Rarity.RARE)));
        });
    }

    @Test public void theGemPaintsItsInkInTheTopRightCornerInsideTheEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                Icon plain = ItemSlot.icon(null, "UT", ItemSlot.State.ITEM, 20);
                int w = plain.getIconWidth(), side = w - 1, gem = EnchantGem.size(side);
                int cx = side - 2 - gem + gem / 2, cy = 2 + gem / 2;
                for (Rarity rarity : GEMS) {
                    int[] with = paint(EnchantGem.decorate(plain, recorded(rarity))), without = paint(plain);
                    assertEquals(variant + " " + rarity + ": the gem's ink", Tokens.rarity(rarity).getRGB(), with[cy * w + cx]);
                    for (int i = 0; i < with.length; i++) {
                        if (with[i] == without[i]) continue;
                        int x = i % w, y = i / w;
                        assertTrue("Inside the edge at " + x + "," + y, x > 0 && y > 0 && x < w - 1 && y < w - 1);
                        assertTrue("In the top-right quarter at " + x + "," + y, x >= w / 2 && y < w / 2);
                    }
                }
                int[] unreadable = paint(EnchantGem.decorate(plain, EnchantInfo.unreadable()));
                assertEquals(variant + ": unreadable data is a muted gem", Tokens.color(Tokens.Role.TEXT_MUTED).getRGB(), unreadable[cy * w + cx]);
            }
        });
    }

    @Test public void theGemStaysLegibleFromTwentyToFortyEightPixels() {
        assertTrue(EnchantGem.size(20 + Sprites.WELL - 1) >= 6);
        assertTrue(EnchantGem.size(48 + Sprites.WELL - 1) <= 16);
    }

    private static int[] paint(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(new JLabel(), g, 0, 0);
        g.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
```

Create `src/test/java/tomato/gui/kit/EnchantTooltipTest.java`:

```java
package tomato.gui.kit;

import java.util.HashMap;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

/** The shared item tooltip: heading, rarity line in the gem color, then each slot's name and effect, all escaped. */
public class EnchantTooltipTest {
    private HashMap<Short, ParseEnchants.Definition> saved;

    @After public void restore() throws Exception {
        if (saved != null) ParseEnchants.ENCHANT_DEFINITIONS = saved;
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void listsTheRarityThenEachSlotsNameAndEffect() throws Exception {
        saved = ParseEnchants.ENCHANT_DEFINITIONS;
        HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
        definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
        ParseEnchants.ENCHANT_DEFINITIONS = definitions;
        EnchantInfo info = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.LEGENDARY,
            List.of(new EnchantInfo.Slot(42), new EnchantInfo.Slot(7), new EnchantInfo.Slot(-1)));
        SwingUtilities.invokeAndWait(() -> {
            String html = EnchantTooltip.html("Doom Bow · UT", info);
            assertTrue(html, html.startsWith("<html><b>Doom Bow · UT</b><br>"));
            assertTrue(html, html.contains(hex(Tokens.rarity(EnchantInfo.Rarity.LEGENDARY)) + "'>Legendary · 3 enchant slots</span>"));
            assertTrue(html, html.contains("<br>Attack Bonus I<br>"));
            assertTrue(html, html.contains("Increases Attack by 1.4"));
            assertTrue(html, html.contains("<br>Unknown enchant (0x7)"));
            assertTrue(html, html.contains("(empty slot)"));
            assertTrue(html, html.endsWith("</html>"));
        });
    }

    @Test public void escapesMarkupAndToleratesAMissingHeading() {
        EnchantInfo unreadable = EnchantInfo.unreadable();
        assertTrue(EnchantTooltip.html("Sword <of> & Things", unreadable).contains("Sword &lt;of&gt; &amp; Things"));
        String none = EnchantTooltip.html(null, EnchantInfo.notRecorded());
        assertTrue(none, none.startsWith("<html><b></b><br>Enchants not recorded"));
    }

    @Test public void theRarityColorFollowsTheTheme() throws Exception {
        EnchantInfo rare = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE, List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                assertTrue(variant.toString(), EnchantTooltip.html("Item", rare).contains(hex(Tokens.rarity(EnchantInfo.Rarity.RARE))));
            }
        });
    }

    private static String hex(java.awt.Color color) { return String.format("#%06x", color.getRGB() & 0xFFFFFF); }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.kit.EnchantGemTest --tests tomato.gui.kit.EnchantTooltipTest`
Expected: FAIL at compile time: `cannot find symbol ... EnchantGem` / `EnchantTooltip` / `Tokens.rarity`.

- [ ] **Step 3: Implement**

In `Tokens.java`, add `import tomato.realmshark.EnchantInfo;` and after `tier(String)`:

```java
    /** Enchant rarity gem color: Uncommon mint, Rare blue, Legendary violet, Divine amber; null for Unenchanted and Unknown (no gem). */
    public static Color rarity(EnchantInfo.Rarity rarity) {
        switch (rarity) {
            case UNCOMMON: return color(Role.GOOD);
            case RARE: return color(Role.INFO);
            case LEGENDARY: return color(Role.ACCENT_TEXT);
            case DIVINE: return color(Role.WARN);
            default: return null;
        }
    }
```

Create `src/main/java/tomato/gui/kit/EnchantGem.java`:

```java
package tomato.gui.kit;

import java.awt.*;
import javax.swing.Icon;
import tomato.realmshark.EnchantInfo;

/**
 * The enchant rarity gem: a small diamond in an item well's top-right corner, inside its edge so the tier border still reads.
 * Recorded rarities paint their {@link Tokens#rarity} color, unreadable data a muted gem, unenchanted and not-recorded nothing.
 * Colors resolve while painting, so gems follow the theme.
 */
public final class EnchantGem {
    private EnchantGem() {}

    /** The gem's width and height for a well {@code side + 1} px square: 7 px on a 20 px sprite's well, 14 px on a 48 px one. */
    static int size(int side) { return Math.max(6, Math.round(side * 0.27f)); }

    /** The gem's color, or null when no gem is shown. */
    static Color ink(EnchantInfo info) {
        if (info == null) return null;
        if (info.state() == EnchantInfo.State.UNREADABLE) return Tokens.color(Tokens.Role.TEXT_MUTED);
        return info.enchanted() ? Tokens.rarity(info.rarity()) : null;
    }

    /** Paints the gem for a well drawn at x, y, {@code side + 1} px square (ItemSlot's geometry); nothing when none is shown. */
    public static void paint(Graphics2D graphics, EnchantInfo info, int x, int y, int side) {
        Color ink = ink(info);
        if (ink == null) return;
        int d = size(side), left = x + side - 2 - d, top = y + 2;
        Polygon diamond = new Polygon(new int[] {left + d / 2, left + d, left + d / 2, left}, new int[] {top, top + d / 2, top + d, top + d / 2}, 4);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ink);
            g.fillPolygon(diamond);
            // A canvas-colored outline keeps the gem readable over any sprite.
            g.setColor(Tokens.color(Tokens.Role.CANVAS));
            g.setStroke(new BasicStroke(1f));
            g.drawPolygon(diamond);
        } finally {
            g.dispose();
        }
    }

    /** {@code base} with the gem over its top-right corner, sized to it; {@code base} itself (even null) when no gem is shown. */
    public static Icon decorate(Icon base, EnchantInfo info) {
        if (base == null || ink(info) == null) return base;
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                paint((Graphics2D) g, info, x, y, Math.min(getIconWidth(), getIconHeight()) - 1);
            }
            @Override public int getIconWidth() { return base.getIconWidth(); }
            @Override public int getIconHeight() { return base.getIconHeight(); }
        };
    }
}
```

Create `src/main/java/tomato/gui/kit/EnchantTooltip.java`:

```java
package tomato.gui.kit;

import java.awt.Color;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;

/**
 * The shared item tooltip: the item heading, the rarity line in its gem color, then each unlocked slot's display name with its
 * effect beneath, and "(empty slot)" for empty ones. Colors come from the current theme, so build it when the tooltip is asked
 * for (or with a row that is rebuilt on theme changes), never while painting.
 */
public final class EnchantTooltip {
    private EnchantTooltip() {}

    public static String html(String heading, EnchantInfo info) {
        StringBuilder html = new StringBuilder("<html><b>").append(escape(heading)).append("</b><br>");
        Color ink = EnchantGem.ink(info);
        if (ink == null) html.append(escape(info.summary()));
        else html.append("<span style='color:").append(hex(ink)).append("'>").append(escape(info.summary())).append("</span>");
        String muted = hex(Tokens.color(Tokens.Role.TEXT_MUTED));
        for (EnchantInfo.Slot slot : info.slots()) {
            html.append("<br>");
            if (slot.empty()) {
                html.append("<span style='color:").append(muted).append("'>(empty slot)</span>");
                continue;
            }
            ParseEnchants.Definition definition = ParseEnchants.definition(slot.typeId());
            html.append(escape(definition.displayName()));
            if (!definition.description().isEmpty())
                html.append("<br>&nbsp;&nbsp;&nbsp;<span style='color:").append(muted).append("'>").append(escape(definition.description())).append("</span>");
        }
        return html.append("</html>").toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static String hex(Color color) { return String.format("#%06x", color.getRGB() & 0xFFFFFF); }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.kit.EnchantGemTest --tests tomato.gui.kit.EnchantTooltipTest --tests tomato.gui.kit.TokensTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/Tokens.java src/main/java/tomato/gui/kit/EnchantGem.java src/main/java/tomato/gui/kit/EnchantTooltip.java src/test/java/tomato/gui/kit/EnchantGemTest.java src/test/java/tomato/gui/kit/EnchantTooltipTest.java
git commit -m "Add rarity colors, the enchant gem and the shared enchant tooltip to the kit" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 4: `ItemSlot` shows the gem and the enchant tooltip

**Files:**
- Modify: `src/main/java/tomato/gui/kit/ItemSlot.java` (fields line 25-28, `setItem`/`setEmpty`/`setUnknown` lines 100-108, `text` lines 111-117, `show` lines 120-127, `getToolTipText` line 140, `paintComponent` lines 146-152)
- Modify: `src/test/java/tomato/gui/kit/KitGallery.java` (after the gear card, around line 62)
- Create: `src/test/java/tomato/gui/kit/ItemSlotEnchantTest.java`

**Interfaces:**
- Consumes: `EnchantGem.paint`, `EnchantGem.size`, `EnchantTooltip.html`, `Tokens.rarity` (Task 3), `EnchantInfo` (Task 2).
- Produces: `public void ItemSlot.setItem(int objectId, String tierLabel, EnchantInfo enchant)` (null enchant = this surface has no enchant data) and `public EnchantInfo ItemSlot.enchant()` (null unless an item with enchant data is shown). `setItem(int, String)` keeps working and means "no enchant data".

- [ ] **Step 1: Write the failing test**

Create `src/test/java/tomato/gui/kit/ItemSlotEnchantTest.java`:

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

/** ItemSlot with enchant data: rarity in its name, the shared enchant tooltip, the gem in the corner; slots without items drop it. */
public class ItemSlotEnchantTest {
    private static final EnchantInfo RARE = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE,
        List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void anEnchantedItemNamesItsRarityAndShowsTheEnchantTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(987_654_321, "UT", RARE);
            assertSame(RARE, slot.enchant());
            String name = slot.getAccessibleContext().getAccessibleName();
            assertTrue(name, name.endsWith(" · UT · Rare · 2 enchant slots"));
            String tip = slot.getToolTipText();
            assertTrue(tip, tip.startsWith("<html>") && tip.contains("Rare · 2 enchant slots") && tip.contains("(empty slot)"));
        });
    }

    @Test public void withoutEnchantDataTheSlotReadsAsBefore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(987_654_321, "UT");
            assertNull(slot.enchant());
            assertFalse(slot.getToolTipText().startsWith("<html>"));
            assertTrue(slot.getToolTipText().endsWith(" · UT"));
        });
    }

    @Test public void emptyAndNotCapturedSlotsDropTheEnchant() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            slot.setItem(5, "T3", RARE); slot.setEmpty();
            assertNull(slot.enchant()); assertEquals("Empty slot", slot.getToolTipText());
            slot.setItem(5, "T3", RARE); slot.setUnknown();
            assertNull(slot.enchant()); assertEquals("Slot not captured", slot.getToolTipText());
            slot.setItem(0, "T3", RARE);
            assertEquals(ItemSlot.State.EMPTY, slot.state()); assertNull(slot.enchant());
        });
    }

    @Test public void theGemIsPaintedInTheComponentsTopRightCorner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                ItemSlot slot = new ItemSlot(24);
                slot.setItem(987_654_321, "UT", RARE);
                Dimension size = slot.getPreferredSize();
                slot.setSize(size);
                BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics(); slot.paint(g); g.dispose();
                int side = Math.min(size.width, size.height) - 1, gem = EnchantGem.size(side);
                assertEquals(variant + ": the Rare ink", Tokens.rarity(EnchantInfo.Rarity.RARE).getRGB(), image.getRGB(side - 2 - gem + gem / 2, 2 + gem / 2));
            }
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.kit.ItemSlotEnchantTest`
Expected: FAIL at compile time: `no suitable method found for setItem(int,String,EnchantInfo)` / `cannot find symbol ... enchant()`.

- [ ] **Step 3: Implement**

In `ItemSlot.java`:
- Add `import tomato.realmshark.EnchantInfo;`.
- Change the class comment to end with "items show their sprite, tier edge and, where the surface knows it, an enchant rarity gem."
- Add the field below `tier`:

```java
    /** The item's enchantments where this surface knows them; null otherwise (and always null unless an item is shown). */
    private EnchantInfo enchant;
```

Replace the `setItem` / `setEmpty` / `setUnknown` / accessor block (lines 100-108) with:

```java
    public void setItem(int objectId, String tierLabel) { setItem(objectId, tierLabel, null); }

    /** As {@link #setItem(int, String)}, with the item's enchantments (null when this surface has none): a gem and the enchant tooltip. */
    public void setItem(int objectId, String tierLabel, EnchantInfo enchantments) {
        if (objectId <= 0) { setEmpty(); return; }
        show(State.ITEM, objectId, tierLabel == null ? "" : tierLabel, enchantments);
    }

    public void setEmpty() { show(State.EMPTY, -1, "", null); }
    public void setUnknown() { show(State.UNKNOWN, -1, "", null); }
    public State state() { return state; }
    public int itemId() { return itemId; }
    public EnchantInfo enchant() { return enchant; }
```

Replace `text()` (lines 110-117) with:

```java
    /** The item's name and tier; "Unknown item #id" only while assets cannot name it. */
    private String itemText() { String name = Sprites.name(itemId); return tier.isEmpty() ? name : name + " · " + tier; }

    /** The slot's description with the item's current name, and its enchant summary when known. */
    private String text() {
        switch (state) {
            case ITEM: return enchant == null ? itemText() : itemText() + " · " + enchant.summary();
            case EMPTY: return "Empty slot";
            default: return "Slot not captured";
        }
    }
```

Replace `show` (lines 119-127) with:

```java
    /** Refilling a slot with what it already shows repaints and announces nothing (pages refresh slots every second). */
    private void show(State next, int id, String label, EnchantInfo enchantments) {
        boolean redraw = state != next || itemId != id || !tier.equals(label) || !Objects.equals(enchant, enchantments);
        state = next;
        itemId = id;
        tier = label;
        enchant = enchantments;
        describe();
        if (redraw) repaint();
    }
```

Replace `getToolTipText()` (line 140) with:

```java
    /** Built when the tooltip is asked for, so its colors follow the theme and nothing is built per paint. */
    @Override public String getToolTipText() {
        return state == State.ITEM && enchant != null ? EnchantTooltip.html(itemText(), enchant) : text();
    }
```

In `paintComponent`, directly after the `paint(this, g, ...)` call and before `g.dispose()`:

```java
        if (state == State.ITEM) EnchantGem.paint(g, enchant, x, y, side);
```

In `src/test/java/tomato/gui/kit/KitGallery.java`, add `import java.util.Collections;` and `import tomato.realmshark.EnchantInfo;`, then add the block below after `page.add(card);`, the gear card's last line. This is the visual check sheet: every gem at every surface size.

```java
        JPanel gems = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.XS, 0));
        gems.setOpaque(false);
        EnchantInfo.Rarity[] rarities = {EnchantInfo.Rarity.UNCOMMON, EnchantInfo.Rarity.RARE, EnchantInfo.Rarity.LEGENDARY, EnchantInfo.Rarity.DIVINE};
        for (int size : new int[] {20, 24, 32, 48}) {
            for (EnchantInfo.Rarity rarity : rarities) {
                ItemSlot slot = new ItemSlot(size);
                slot.setItem(987_654_321, "UT", new EnchantInfo(EnchantInfo.State.RECORDED, rarity,
                    Collections.nCopies(rarity.ordinal(), new EnchantInfo.Slot(-1))));
                gems.add(slot);
            }
            ItemSlot unreadable = new ItemSlot(size);
            unreadable.setItem(987_654_321, "T12", EnchantInfo.unreadable());
            gems.add(unreadable);
        }
        page.add(section("Enchant gems", gems));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.kit.ItemSlotEnchantTest --tests tomato.gui.kit.ItemSlotIconTest --tests tomato.gui.kit.GameWidgetsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/ItemSlot.java src/test/java/tomato/gui/kit/ItemSlotEnchantTest.java src/test/java/tomato/gui/kit/KitGallery.java
git commit -m "Let ItemSlot show an item's enchant rarity gem and enchant tooltip" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 5: Character Gear tab uses the gem (retire `EnchantDots`)

**Files:**
- Modify: `src/main/java/tomato/gui/glance/character/SheetModel.java:36-47` (`Gear` record and its comment)
- Modify: `src/main/java/tomato/gui/glance/character/SheetModelBuilder.java:117-139`
- Modify: `src/main/java/tomato/gui/glance/character/GearTab.java`
- Delete: `src/main/java/tomato/gui/glance/character/EnchantDots.java`
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (delete `EquippedCapture.unlockedSlots`, its last caller goes away here)
- Modify: `src/test/java/tomato/gui/glance/character/GearTabTest.java:21, 67-90`

**Interfaces:**
- Consumes: `EquippedCapture.info(int)` (Task 2), `ItemSlot.setItem(int, String, EnchantInfo)` and `ItemSlot.enchant()` (Task 4).
- Produces: `SheetModel.Gear(List<Integer> slots, List<String> tiers, Boolean hasBackpack, List<EnchantInfo> enchants)`, where `enchants` holds the 4 equipped items' enchantments and is null unless the character is playing; `static List<EnchantInfo> SheetModelBuilder.enchants(LiveCharacter.Snapshot)`.

- [ ] **Step 1: Write the failing test**

In `GearTabTest.java`, change the class comment (line 21) to `/** Gear: unknown vs empty vs no backpack, tier labels, and enchant rarity gems only for the live character. */`, add `import tomato.realmshark.EnchantInfo;`, and replace the whole `enchantDotsShowOnlyForTheLiveCharacter` test (lines 67-90) with:

```java
    @Test public void enchantGemsShowOnlyForTheLiveCharacter() throws Exception {
        Entity player = new Entity(null, 1, 0);
        StatData enchants = new StatData();
        enchants.stringStatValue = "AAIE_wU,AAIE,,!!!"; // weapon 1 unlocked slot, ability 0, armor 0, ring malformed
        player.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        BuildEstimates.Inputs inputs = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN);
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", inputs));
        SheetModel other = model(record(), account(), live(ACCOUNT, 8, "Ann", inputs));
        List<EnchantInfo> equipped = playing.gear().enchants();
        assertEquals(EnchantInfo.Rarity.UNCOMMON, equipped.get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, equipped.get(1).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, equipped.get(2).rarity());
        assertSame(EnchantInfo.unreadable(), equipped.get(3));
        assertNull("Another character's enchants never describe this one", other.gear().enchants());
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
            tab.apply(playing.gear());
            // The live equipped four are 2001 (UT), empty, 2003 and 2004 (SheetFixtures.live).
            assertEquals(equipped.get(0), slot(tab, 0).enchant());
            String weapon = slot(tab, 0).getAccessibleContext().getAccessibleName();
            assertTrue(weapon, weapon.endsWith(" · UT · Uncommon · 1 enchant slot"));
            assertTrue(slot(tab, 0).getToolTipText().contains("Uncommon · 1 enchant slot"));
            assertNull("Empty slot: no enchant", slot(tab, 1).enchant());
            assertTrue(slot(tab, 2).getAccessibleContext().getAccessibleName().endsWith("Unenchanted"));
            assertTrue(slot(tab, 3).getAccessibleContext().getAccessibleName().endsWith("Enchant data unreadable"));
            tab.apply(other.gear());
            for (int i = 0; i < 4; i++) assertNull(slot(tab, i).enchant());
        });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.glance.character.GearTabTest`
Expected: FAIL at compile time: `enchants()` returns `List<Integer>`, so `live.get(0).rarity()` does not compile.

- [ ] **Step 3: Implement**

`SheetModel.java`: add `import tomato.realmshark.EnchantInfo;`. In the `Gear` comment, replace the `{@code enchants}` sentence with `{@code enchants}: the 4 equipped items' enchantments; null unless playing.` and change the record header to:

```java
    public record Gear(List<Integer> slots, List<String> tiers, Boolean hasBackpack, List<EnchantInfo> enchants) {
```

`SheetModelBuilder.java`: add `import tomato.realmshark.EnchantInfo;` and replace `enchants(LiveCharacter.Snapshot)` (lines 131-138) with:

```java
    /** The 4 equipped items' enchantments from the live snapshot's detached inputs. */
    static List<EnchantInfo> enchants(LiveCharacter.Snapshot live) {
        if (live.build() == null) return null;
        ParseEnchants.EquippedCapture capture = live.build().enchants();
        List<EnchantInfo> slots = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) slots.add(capture.info(i));
        return List.copyOf(slots);
    }
```

The `gear(...)` method (line 128) keeps passing `playing ? enchants(live) : null` unchanged. Update its comment "their enchant rarity" → "their enchantments".

`GearTab.java`:
- Delete the `dots` field (line 27).
- In the constructor, replace the equipped loop body with:

```java
            slots[i] = named(new ItemSlot(48), "character-gear-slot-" + i);
            tiers[i] = named(KitText.caption(SLOTS[i]), "character-gear-tier-" + i);
            equipped.add(beside(slots[i], clear(new FlowLayout(FlowLayout.CENTER, Tokens.XS, 0), tiers[i]), BorderLayout.SOUTH, 2));
```

- In `apply`, replace the fill line `if (id > 0) slots[i].setItem(id, tier); else ...` with:

```java
            // Enchantments are decoded from the character in game only; saved records show no gem.
            EnchantInfo enchant = i < 4 && gear != null && gear.enchants() != null ? gear.enchants().get(i) : null;
            if (id > 0) slots[i].setItem(id, tier, enchant); else if (id == 0) slots[i].setEmpty(); else slots[i].setUnknown();
```

  and reduce the `if (i < 4) { ... }` block to `if (i < 4) tiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);`, deleting the dots comment and `dots[i].set(...)`.
- Add `import tomato.realmshark.EnchantInfo;`.
- In the class comment, replace "enchant rarity dots" with "an enchant rarity gem and enchant tooltip".

Delete `src/main/java/tomato/gui/glance/character/EnchantDots.java`.

In `ParseEnchants.EquippedCapture`, delete `unlockedSlots(int)` and its comment (line 372-373), which no longer has a caller. Confirm with a search for `unlockedSlots(` in `src/`: expect no matches.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.glance.character.*'`
Expected: PASS. `SheetModelBuilderTest:56` (`assertNull(m.gear().enchants())`) still passes unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/tomato/gui/glance/character src/main/java/tomato/realmshark/ParseEnchants.java src/test/java/tomato/gui/glance/character/GearTabTest.java
git commit -m "Show the rarity gem and enchant tooltip on the Gear tab's equipped slots" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 6: Party roster and Inspect use slot-based rarity (retire `GlowWell`)

**Files:**
- Modify: `src/main/java/tomato/gui/security/ParsePanelGUI.java` (Row fields line 1022-1024, Row constructor lines 1039-1062, `GlowWell` lines 1065-1094, `RosterCell` equipment branch lines 1170-1175)
- Modify: `src/test/java/tomato/gui/security/PartyRestyleTest.java` (test lines 87-113, helper lines 352-367)
- Modify: `src/test/java/tomato/gui/security/ParsePanelRefreshTest.java` (lines 559-600 and 603-647)
- Modify: `src/test/java/tomato/gui/dps/DpsInspectMenuTest.java:58`

**Interfaces:**
- Consumes: `EquippedCapture.info(int)`, `EnchantInfo.text()` (Task 2), `EnchantGem.decorate`, `EnchantTooltip.html`, `Tokens.rarity` (Task 3).
- Produces: nothing new for later tasks.

- [ ] **Step 1: Update the tests to the new behavior (they fail first)**

`PartyRestyleTest.java`:
- Rename the test at line 87 to `gearWellsShowItemEmptyAndNotCapturedWithTheRarityGemInsideTheWell`.
- Replace its line 106 (`assertGlowInside(...)`) with:

```java
            assertGemInside(weapon, ItemSlot.icon(Sprites.sprite(42, 20), "T5", ItemSlot.State.ITEM, 20), Tokens.rarity(EnchantInfo.Rarity.UNCOMMON), table);
```

- Replace the `assertGlowInside` helper and its comment (lines 352-367) with:

```java
    /**
     * The gem well paints like the plain well except in its top-right quarter, inside the edge (the tier border still reads), and
     * the gem paints its rarity ink.
     */
    static void assertGemInside(Icon withGem, Icon plain, Color ink, Component owner) {
        int w = withGem.getIconWidth(), h = withGem.getIconHeight();
        assertEquals(plain.getIconWidth(), w);
        int[] a = paint(withGem, owner), b = paint(plain, owner); int changed = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x; if (a[i] == b[i]) continue;
            assertFalse("The gem leaves the well's edge alone at " + x + "," + y, x == 0 || y == 0 || x == w - 1 || y == h - 1);
            assertTrue("The gem stays in the top-right corner at " + x + "," + y, x >= w / 2 && y < h / 2);
            changed++;
        }
        assertTrue("The well paints a gem", changed > 8);
        assertPaintsInk(withGem, ink, owner);
    }

    static void assertPaintsInk(Icon icon, Color ink, Component owner) {
        for (int argb : paint(icon, owner)) if (argb == ink.getRGB()) return;
        fail("The icon paints its gem ink " + ink);
    }
```

- Add imports `tomato.gui.kit.Tokens` (if not already imported) and `tomato.realmshark.EnchantInfo`, plus `static org.junit.Assert.fail` if the file imports assertions individually. Remove the `distance` helper if nothing else uses it.

`ParsePanelRefreshTest.java`, test `themeChangesRegenerateCachedEquipmentIconsVisibleAndOnShow` (line 559). Only captured items carry a gem now, so give the player a weapon. After the `UNIQUE_DATA_STRING` stat line (566) add:

```java
            stat(enchanted, StatType.INVENTORY_0_STAT, 42, "");
```

In that test:
- Replace each of the three `assertEquipmentText(table, 0, 3, "Weapon: Not captured", ParseEnchants.ENCHANTS.getOrDefault((short)1, "Unknown") + "(1)")` calls with `assertEquipmentText(table, 0, 3, "Weapon: Unrecognized item (ID 42)", "Uncommon · 1 enchant slot")`.
- Replace line 586 (the `assertGlowInside` call) with:

```java
            // The well paints the Uncommon gem in the light theme's ink after the theme change.
            PartyRestyleTest.assertPaintsInk(lightIcon, tomato.gui.kit.Tokens.rarity(tomato.realmshark.EnchantInfo.Rarity.UNCOMMON), table);
```

Test `equipmentRendererResetsKnownEmptyMissingAndUnrecognizedDescriptions` (line 603):
- Replace `String oldEnchant = ParseEnchants.ENCHANTS.put((short)1, "Test enchant");` with `ParseEnchants.Definition oldEnchant = ParseEnchants.ENCHANT_DEFINITIONS.put((short)1, new ParseEnchants.Definition("Test enchant", "Does a test thing"));`.
- In `finally`, change the restore to `if (oldEnchant == null) ParseEnchants.ENCHANT_DEFINITIONS.remove((short)1); else ParseEnchants.ENCHANT_DEFINITIONS.put((short)1, oldEnchant);`.
- Change the expected details:
  - `"Test enchant(1)"` → `"Test enchant — Does a test thing"`;
  - both `"None (captured)"` → `"Enchants: Unenchanted"`;
  - `"Enchant data not captured."` → `"Enchants not recorded"`;
  - `"Malformed enchant data"` → `"Enchant data unreadable"`.

`DpsInspectMenuTest.java:58`: change `"Enchants: None (captured)"` to `"Enchants: Unenchanted"`.

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.security.PartyRestyleTest --tests tomato.gui.security.ParsePanelRefreshTest --tests tomato.gui.dps.DpsInspectMenuTest`
Expected: FAIL. The roster still paints the glow (no pixel equals the gem ink) and still writes "None (captured)" / "Test enchant(1)".

- [ ] **Step 2: Implement**

In `ParsePanelGUI.java`:
- Add imports `tomato.gui.kit.EnchantGem`, `tomato.gui.kit.EnchantTooltip` (skip if `tomato.gui.kit.*` is already imported) and `tomato.realmshark.EnchantInfo`.
- Row fields (line 1022-1024): change to

```java
        final String[] equipmentLabels = new String[4], equipmentDetails = new String[4], equipmentTooltips = new String[4];
        /** Painted gear wells (tier edge, rarity gem in the corner); rebuilt with the row on a theme change. */
        final Icon[] icons = new Icon[4];
```

- In the Row constructor loop, replace everything from `String enchant = capture.description(i).trim();` through `icons[i] = count == 0 ? well : new GlowWell(well, ContentStyle.color(role));` with:

```java
                EnchantInfo enchant = capture.info(i);
                equipmentDetails[i] = equipmentLabels[i] + "\nEnchants: " + enchant.text();
                // Built with the row, which is rebuilt on a theme change, so the tooltip's colors follow the theme.
                equipmentTooltips[i] = EnchantTooltip.html(equipmentLabels[i], enchant);
                // The well says what the slot holds (as its label does): not captured, empty (a negative ID) or an item with its tier.
                ItemSlot.State state = !player.equipmentCaptured[i] ? ItemSlot.State.UNKNOWN : player.inv[i] < 0 ? ItemSlot.State.EMPTY : ItemSlot.State.ITEM;
                String tier = state == ItemSlot.State.ITEM && player.inv[i] > 0 && definitions != null ? ItemTiers.label(definitions.item(player.inv[i])) : "";
                Icon well = ItemSlot.icon(state == ItemSlot.State.ITEM ? Sprites.sprite(player.inv[i], 20) : null, tier, state, 20);
                // Only a captured item carries a rarity gem; a well that shows no item claims none.
                icons[i] = state == ItemSlot.State.ITEM ? EnchantGem.decorate(well, enchant) : well;
```

- Delete the `GlowWell` class and its comment (lines 1065-1094).
- In `RosterCell`, replace `setToolTipText("<html>" + html(row.equipmentDetails[slot]) + "</html>");` in the equipment branch with `setToolTipText(row.equipmentTooltips[slot]);`.
- Remove imports that now have no use, such as `BasicStroke` if it was only used by `GlowWell`. The compiler won't flag them; search the file for each one.

- [ ] **Step 3: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.security.PartyRestyleTest --tests tomato.gui.security.ParsePanelRefreshTest --tests tomato.gui.dps.DpsInspectMenuTest`
Expected: PASS. If a failure is a window-size or evidence test, run the same test class on `main` before treating it as a regression (22 such tests already fail there on this workstation).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/tomato/gui/security/ParsePanelGUI.java src/test/java/tomato/gui/security/PartyRestyleTest.java src/test/java/tomato/gui/security/ParsePanelRefreshTest.java src/test/java/tomato/gui/dps/DpsInspectMenuTest.java
git commit -m "Show slot-based rarity gems and enchant names on the Party roster and Inspect" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 7: DPS icon view and event detail

**Files:**
- Modify: `src/main/java/tomato/gui/dps/IconDpsGUI.java:396-480` (`equipment(...)`)
- Modify: `src/main/java/tomato/gui/dps/DamageEvents.java:126-141`
- Create: `src/test/java/tomato/gui/dps/DamageEventsEnchantTest.java`

**Interfaces:**
- Consumes: `EnchantInfo.ofRetained(String)`, `EnchantInfo.text()` (Task 2), `EnchantGem.decorate`, `EnchantTooltip.html` (Task 3).
- Produces: package-private `static String DamageEvents.enchantLines(String[] enchants)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/tomato/gui/dps/DamageEventsEnchantTest.java`:

```java
package tomato.gui.dps;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.HashMap;
import org.junit.Test;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.assertEquals;

/** Event detail names each slot's enchantments instead of printing raw encoded strings. */
public class DamageEventsEnchantTest {
    @Test public void namesEachSlotsEnchantsAndReadsARetainedEmptyAsNotRecorded() {
        HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
            definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
            ParseEnchants.ENCHANT_DEFINITIONS = definitions;
            assertEquals("  Weapon enchants: Rare · 2 enchant slots\n    Attack Bonus I — Increases Attack by 1.4\n    (empty slot)\n"
                    + "  Ability enchants: Enchants not recorded\n"
                    + "  Armor enchants: Enchant data unreadable\n"
                    + "  Ring enchants: Unenchanted\n",
                DamageEvents.enchantLines(new String[] {encode(42, -1), "", "!!!", encode()}));
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }

    private static String encode(int... entries) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + entries.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0);
        buffer.putShort((short) 1026);
        for (int entry : entries) buffer.putShort((short) entry);
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.dps.DamageEventsEnchantTest`
Expected: FAIL at compile time: `cannot find symbol ... enchantLines`.

- [ ] **Step 3: Implement**

`DamageEvents.java`: add `import tomato.realmshark.EnchantInfo;`. Replace line 127 (the `"  Enchants: "` append) with:

```java
        if (slots != null && enchants != null && enchants.length > 0) text.append(enchantLines(enchants));
```

Add below `allEmpty`, and delete `nonEmpty` (its only caller was the line replaced above; confirm by searching the file for `nonEmpty(`):

```java
    /** One line per equipped slot naming its enchantments; a retained "" cannot be told from unenchanted, so it reads not recorded. */
    static String enchantLines(String[] enchants) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(4, enchants.length); i++)
            text.append("  ").append(SLOTS[i]).append(" enchants: ").append(EnchantInfo.ofRetained(enchants[i]).text().replace("\n", "\n  ")).append('\n');
        return text.toString();
    }
```

If `List` or `ArrayList` imports become unused after deleting `nonEmpty`, remove them.

`IconDpsGUI.java`: add imports `tomato.gui.kit.EnchantGem`, `tomato.gui.kit.EnchantTooltip`, `tomato.realmshark.EnchantInfo`. In `equipment(...)`, replace the loop body (from `Equipment max = ...` through `panel.add(icon);`) with:

```java
            Equipment max = eqAgg.getMostUsedItem(owner.id, i);
            int eq = (max != null) ? max.id : 0;
            // Retained per-hit strings turn a missing stat into "", so "" reads as not recorded rather than unenchanted.
            EnchantInfo enchant = EnchantInfo.ofRetained(max != null ? max.enchant : null);
            JLabel icon = new JLabel(EnchantGem.decorate(ImageBuffer.getOutlinedIcon(eq, s), enchant));
            icon.setToolTipText(EnchantTooltip.html(IdToAsset.objectName(eq), enchant));
            panel.add(icon);
```

If `ParseEnchants` is no longer referenced in `IconDpsGUI.java`, remove its import. `ImageBuffer.getOutlinedIconWithGlow` stays; it is used by `ImageBufferTest`, and Phase 4 cleans up the remaining legacy callers.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.dps.DamageEventsEnchantTest --tests 'tomato.gui.dps.Dps*' --tests tomato.gui.dps.IconOutcomeTest`
Expected: PASS (compare any window-size/evidence failure against `main`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/dps/IconDpsGUI.java src/main/java/tomato/gui/dps/DamageEvents.java src/test/java/tomato/gui/dps/DamageEventsEnchantTest.java
git commit -m "Show rarity gems in the DPS icon view and enchant names in event detail" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 8: My Info equipment icons

**Files:**
- Modify: `src/main/java/tomato/gui/myinfo/MyInfoGUI.java:601, 617-627, 768-771`
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (delete `EquippedCapture.description`, which has no callers left after this task)
- Modify: `src/test/java/tomato/gui/myinfo/MyInfoGuiTest.java:154`

**Interfaces:**
- Consumes: `EquippedCapture.info(int)`, `EnchantInfo.text()` (Task 2), `EnchantGem.decorate`, `EnchantTooltip.html` (Task 3).
- Produces: `public void MyInfoGUI.displayImg(JLabel label, int id, EnchantInfo enchant)` (replaces the two-argument form).

- [ ] **Step 1: Update the test (fails first)**

In `MyInfoGuiTest.java:154`, change `"Malformed enchant data"` to `"Enchant data unreadable"`.

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.myinfo.MyInfoGuiTest`
Expected: FAIL. The notes still say "Malformed enchant data; effects unavailable."

- [ ] **Step 2: Implement**

In `MyInfoGUI.java`, add imports `tomato.gui.kit.EnchantGem`, `tomato.gui.kit.EnchantTooltip` and `tomato.realmshark.EnchantInfo`. In the equipment loop (lines 620-627), replace the body with:

```java
                Double id = stat(player, slots[i]);
                String item = id == null ? "Not captured" : id < 0 ? "Empty slot" : itemName(id.intValue());
                EnchantInfo enchant = enchants.info(i);
                equipmentNames[i].setText(item); equipmentNames[i].setToolTipText(item);
                if (id != null && id >= 0) displayImg(icons[i], id.intValue(), enchant);
                icons[i].setToolTipText(EnchantTooltip.html(item, enchant));
                add("Equipment", SLOT_NAMES[i], id, "item ID", item + "\n" + enchant.text());
                if (i == 0) weapon = BuildEstimates.weapon(player);
```

Change the reset at line 601 to also clear the tooltip:

```java
        for (int i = 0; i < 4; i++) { icons[i].setIcon(null); icons[i].setToolTipText(null); equipmentNames[i].setText("Awaiting capture"); }
```

Replace `displayImg` (lines 768-771) with:

```java
    public void displayImg(JLabel label, int id, EnchantInfo enchant) {
        try { label.setIcon(EnchantGem.decorate(ImageBuffer.getOutlinedIcon(id, 28), enchant)); }
        catch (RuntimeException e) { label.setIcon(null); }
    }
```

In `ParseEnchants.EquippedCapture`, delete `description(int)`. Search `src/` for `.description(` on an `EquippedCapture`: expect no remaining callers. `ParseEnchants.parse` stays, because Bridge and `SendLoot` still use it (Phase 4).

- [ ] **Step 3: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.myinfo.*' --tests 'tomato.realmshark.*'`
Expected: PASS (compare any window-size/evidence failure against `main`).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/tomato/gui/myinfo/MyInfoGUI.java src/main/java/tomato/realmshark/ParseEnchants.java src/test/java/tomato/gui/myinfo/MyInfoGuiTest.java
git commit -m "Show rarity gems and enchant tooltips on My Info equipment" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 9: Phase verification, visual check and PR

**Files:** none changed unless a check fails.

- [ ] **Step 1: Leftover scan**

Search `src/` for `EnchantDots`, `GlowWell`, `unlockedSlots(`, `getOutlinedIconWithGlow(` and `ParseEnchants.parse(`.
Expected results:
- `getOutlinedIconWithGlow(` remains only in `ImageBuffer` and `ImageBufferTest`.
- `ParseEnchants.parse(` remains only in `BridgePayload` and `SendLoot` (Phase 4).
- Nothing else.

Also search the diff for `TODO`, `@Ignore`, `.skip` and placeholder text: expect none.

- [ ] **Step 2: Focused suite plus build**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.glance.character.*' --tests 'tomato.gui.security.*' --tests 'tomato.gui.dps.*' --tests 'tomato.gui.myinfo.*' --tests 'assets.*' shadowJar`
Expected: BUILD SUCCESSFUL, or only window-size/evidence failures that also fail on `main`. Before calling any failure a regression, run that test class in a separate worktree of `main` (`git worktree add ../realmshark-main main`). Fix real regressions and rerun.

- [ ] **Step 3: Visual gem check (both themes, 20/24/32/48 px)**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.kit.KitGalleryEvidenceTest`
Then open `screenshots/redesign-kit/kit-dark-1240-13.png`, `kit-light-1240-13.png` and their `-lower` variants if the "Enchant gems" section is below the fold. Check that:
- each rarity's gem is distinguishable at 20 px;
- the UT amber border still reads next to the amber Divine gem;
- the gem never covers the well edge;
- the muted unreadable gem is visible but quiet.

Delete the screenshots after viewing unless they are tracked (`git status` must show no new files under `screenshots/`).

- [ ] **Step 4: Launch smoke**

Launch the built jar from the repository root, where `assets/` already exists:

```bash
.tools/jdk-17.0.20.1+1/bin/java.exe -jar "$(ls build/libs/*-all.jar | head -1)"
```

Do **not** start capture. With no capture running:
- open Party, the DPS icon view, Character › Gear and My Info, and confirm each shows its empty state;
- confirm the console shows no exception;
- close the app.

If the jar name differs, use the `shadowJar` output under `build/libs/`.

- [ ] **Step 5: Independent review**

Dispatch a fresh reviewer (for example the `pr-review-toolkit:code-reviewer` or `oh-my-claudecode:code-reviewer` agent) on the branch diff against `main`. Give it the spec path, this plan's path, and the Review Focus list. Fix confirmed findings with a test first, then rerun Step 2 for the touched packages.

- [ ] **Step 6: Push and open the PR (after the user confirms)**

```bash
git push -u origin feat/enchant-rarity-p1
gh pr create --base main --title "Enchant rarity, phase 1: shared foundation and live surfaces" --body "$(cat <<'EOF'
Phase 1 of docs/superpowers/specs/2026-09-30-equipment-enchant-rarity-design.md (plan: docs/superpowers/plans/2026-09-30-enchant-rarity-p1.md).

- One decoder (EnchantInfo), enchant display names/descriptions, rarity colors, a corner gem and a shared enchant tooltip.
- Party roster, Inspect, DPS icon view, DPS event detail, Character Gear tab and My Info now show slot-based rarity (Unenchanted/Uncommon/Rare/Legendary/Divine) and the rolled enchants on hover.
- Replaces the parse()-line-count glows (which counted empty/locked as an enchant) and EnchantDots.
- No persisted format or wire payload changes.

Validation: focused tests for touched packages, shadowJar, kit gallery gem check in both themes, launch smoke without capture.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU
EOF
)"
```
