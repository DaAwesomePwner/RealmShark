# Enchant Rarity Phase 3 (Runs and Characters) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the rarity gem and hover enchants in four places, and start recording your own equipped items' enchant data on character records so that saved characters keep it:
- the gear of every player in a run recap, yourself included;
- the Character sheet (Gear tab, Overview, Gear Analyst table), including characters that are not in game;
- the Home hero card.

**Architecture:**
- **Run recaps** read the enchant stat that the saved inspect snapshots already contain.
- **Character records** gain one optional field, `equipmentEnchants`: the four equipped items' raw enchant entries, written whenever the character is observed live. It persists in character-journal version 5, with no version bump.
- **Decoding** goes through `CharacterRecord.enchantInfos()` and `ParseEnchants.EquippedCapture.infos()`, both returning `List<EnchantInfo>`. The sheet and Home builders use them, and Swing only paints.

**Tech Stack:** Java 17 (`--release 17`), Swing/FlatLaf, JUnit 4, Gradle 7.6.4 wrapper, Gson.

**Spec:** `docs/superpowers/specs/2026-09-30-equipment-enchant-rarity-design.md`. This plan covers the Phase 3 section plus Compatibility rules, Error handling and Testing. Earlier phases are on `main`:
- Phase 1 (PR #32): `EnchantInfo`, `EnchantGem`, `EnchantTooltip`, `EnchantIconLabel`, `ItemSlot.setItem(int, String, EnchantInfo)`.
- Phase 2 (PR #34): `COUNT_ONLY`, `ItemIcon`, loot surfaces.

**Deliberate refinements of the spec:**
- **No `ActivityJournal.Visit.equipmentEnchants`.** Run recaps exist only for dungeon visits. There, `TomatoData` already records the local player through `DiscoveryLog.inspectPlayer`, and `InspectSnapshot` whitelists and persists `UNIQUE_DATA_STRING` (`InspectHistoryTest:110`). Your own gear's enchants in a run are therefore already saved. A second copy on the visit would have no reader, and `activity-history.json` stays exactly as it is.
- **Character enchants are kept when an observation lacks the stat.** This is the same rule the record already uses for equipment (a slot absent from an update keeps its saved item). Records written before this change show "Enchants not recorded" until the character is next played.
- **The Home hero record gains a 23rd component.** A 22-argument constructor stays so that `placeholder(...)` and existing callers keep compiling.

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
  - `HomeRefreshTimingTest.liveTicksKeepHomesEdtWorkWithinOneFrame`
  - the window-size and evidence classes listed in the project's desktop-failure note

Work on branch `feat/enchant-rarity-p3`, created from `main` after PR #34 merged, whose first commit is this plan:

```bash
git switch feat/enchant-rarity-p3
```

## Global Constraints

- Java 17, `--release 17`; no new dependencies.
- **Rarity is the unlocked slot count.** Summary strings are verbatim from `EnchantInfo`:
  - `"Unenchanted"`
  - `"<Rarity> · N enchant slots"`
  - `"Enchants not recorded"`
  - `"Enchant data unreadable"`
- **Gem colors come from `Tokens.rarity`.** The gem sits top-right inside the well, and the tier border keeps its meaning.
- **Surfaces that have no enchant data pass `null` to `ItemSlot`,** so they read exactly as before. This is Phase 2's rule for not-recorded data.
- **Persistence:**
  - `CharacterJournal` stays at `Document.version = 5`, and `activity-history.json` stays at `schemaVersion` 1 and unchanged.
  - The new record field is optional and null-tolerant. It is validated only through `dropMalformedV5Fields` / `normalizeV5`, never through the hard reject at `CharacterJournal` load (lines ~142-143).
  - Gson allocates `InspectSnapshot` without a constructor. `CharacterRecord` has a no-arg constructor, but any saved field may be null.
- **Equality-based refresh skips must see enchant changes:**
  - `CharacterJournal.sameObservation` (without this, a change to enchants alone is never saved)
  - `GearTab.SlotRows`
  - `HomeModel.Hero.equals/hashCode` (hand-written)
  - `RunRecapView`'s `players.equals(shownPlayers)`
  - `OverviewTab`'s `gearShown`
  - `HeroCard`'s `hero.equals(shown)`
- **Threads:**
  - Journals are written on the capture thread under their own locks.
  - Models are built off the EDT: the "character-sheet" and "home-refresh" threads, and the run-recap builder.
  - Decode `EnchantInfo` in builders, never in Swing paint code. Tooltip HTML is built on hover only.
- Do not add fields or methods to `Damage`, `StatData` or `Equipment`.
- Never start live capture or send bridge deliveries. No force pushes, hook bypasses or commits to `main`.
- End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU
  ```

## Review Focus

1. **Saved character files from before this change** (no `equipmentEnchants`), and malformed values: a number, the wrong length, or null elements. Expected: the journal loads, stays writable, and the sheet and Home read "Enchants not recorded" or no gem. It must never reject the file. Pinned in Task 2.
2. **Enchants-only changes.** When the same items are observed with new enchants, the change is saved and every surface repaints; nothing skips it as equal. Pinned in Tasks 2, 3 and 4.
3. **Inspect snapshots without the enchant stat,** which is the case for older run history. The recap shows the gear exactly as before, with no gem and no enchant tooltip. Pinned in Task 1.
4. **The protocol's empty shorthand.** A captured stat 80 of `""` means all four slots are known-unenchanted; a short list means the missing slots are not recorded. This matches Phase 1's `EquippedCapture`. Pinned in Task 2.
5. **Another character's record.** Only that character's saved enchants may describe it, never the live character's. Pinned in Task 3.

---

### Task 1: Run recap player gear shows enchants

**Files:**
- Modify: `src/main/java/tomato/gui/runs/RunRecapModel.java` (`Players.Player` record, lines ~159-161)
- Modify: `src/main/java/tomato/gui/runs/RunRecapBuilder.java` (`players(...)`, lines ~352-363)
- Modify: `src/main/java/tomato/gui/runs/RunRecapView.java` (player gear, lines ~643-646)
- Test: `src/test/java/tomato/gui/runs/RunRecapBuilderTest.java`, `src/test/java/tomato/gui/runs/RunRecapViewTest.java`

**Interfaces:**
- Consumes: `ParseEnchants.equippedCapture(Entity)`, `EquippedCapture.info(int)`, `EnchantInfo`, and `ItemSlot.setItem(int, String, EnchantInfo)`.
- Produces: `RunRecapModel.Players.Player(int objectId, String name, String className, int classType, List<Integer> equipment, Long inspectDamage, long observedAt, List<EnchantInfo> enchants)`.
  - The existing 7-argument constructor keeps working. It gives one `EnchantInfo.notRecorded()` per equipment slot.

- [ ] **Step 1: Write the failing tests**

In `RunRecapBuilderTest`, add an overload of the `snapshot(...)` helper (lines ~142-148) that also sets the enchant stat:

```java
    /** As {@link #snapshot(int, int, String, int...)}, with the player's UNIQUE_DATA_STRING (one entry per equipped slot). */
    private static InspectSnapshot snapshot(int id, int classType, String name, String enchants, int... equipment) {
        InspectSnapshot plain = snapshot(id, classType, name, equipment);
        Entity player = plain.toEntity();
        StatData stat = new StatData(); stat.stringStatValue = enchants; player.stat.set(StatType.UNIQUE_DATA_STRING, stat);
        return new InspectSnapshot(player, T0 + 1_000);
    }
```

In the test that asserts the recap's players (lines ~446-462):
- Give one player's snapshot the enchant stat with this overload: `"AAIE_wU,,,"`, which is weapon Uncommon (Flat Mana Regeneration I) followed by three known-unenchanted slots.
- Leave another player's snapshot without the stat.
- Then assert:

```java
        assertEquals(EnchantInfo.Rarity.UNCOMMON, withEnchants.enchants().get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, withEnchants.enchants().get(1).rarity());
        assertEquals("A snapshot without the enchant stat records none", java.util.Collections.nCopies(4, EnchantInfo.notRecorded()), without.enchants());
```

Here `withEnchants` and `without` are the two players as the test already finds them in the model. Use its existing lookup. Add `import tomato.realmshark.EnchantInfo;`.

In `RunRecapViewTest`, the fixture builds `Player`s with the 7-argument constructor (lines ~85, ~86, ~222). Make one of them use the 8-argument form with `List.of(EnchantInfo.ofSlotCount(2), EnchantInfo.notRecorded(), EnchantInfo.notRecorded(), EnchantInfo.notRecorded())`. In the players assertions, collect that player's gear `ItemSlot`s the way the loot test does (`collect(named(view, …), c -> …)`, using the players section's container name). Then assert that the first slot's `enchant()` equals `EnchantInfo.ofSlotCount(2)` and the second slot's `enchant()` is null.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.runs.RunRecapBuilderTest --tests tomato.gui.runs.RunRecapViewTest`
Expected: FAIL at compile time: `cannot find symbol ... enchants()` / no 8-argument `Player`.

- [ ] **Step 3: Implement**

`RunRecapModel.java`: add `import tomato.realmshark.EnchantInfo;` and replace the `Player` record with:

```java
        /** {@code enchants}: each equipped slot's enchantments from the snapshot (not recorded when it lacked the stat). */
        public record Player(int objectId, String name, String className, int classType, List<Integer> equipment, Long inspectDamage,
                             long observedAt, List<EnchantInfo> enchants) {
            public Player {
                equipment = Collections.unmodifiableList(new ArrayList<>(equipment));
                enchants = enchants == null ? Collections.nCopies(equipment.size(), EnchantInfo.notRecorded()) : List.copyOf(enchants);
            }
            public Player(int objectId, String name, String className, int classType, List<Integer> equipment, Long inspectDamage, long observedAt) {
                this(objectId, name, className, classType, equipment, inspectDamage, observedAt, null);
            }
        }
```

Keep any existing Javadoc and extra members of the record.

`RunRecapBuilder.java`: in `players(...)`, after `Entity player = snapshot.toEntity();`, decode the saved enchant stat and pass it:

```java
            ParseEnchants.EquippedCapture capture = ParseEnchants.equippedCapture(player);
            List<EnchantInfo> enchants = new ArrayList<>(SLOTS.length);
            for (int i = 0; i < SLOTS.length; i++) enchants.add(capture.info(i));
```

Pass `enchants` as the new last argument of the `new RunRecapModel.Players.Player(...)` call. Add the `tomato.realmshark.ParseEnchants` and `tomato.realmshark.EnchantInfo` imports.

`RunRecapView.java`: iterate the player's gear with an index and pass each slot's enchantments. Pass `null` when not recorded, so older runs read as before:

```java
            List<Integer> equipment = player.equipment();
            for (int i = 0; i < equipment.size(); i++) {
                Integer item = equipment.get(i);
                ItemSlot slot = new ItemSlot(24);
                EnchantInfo enchant = player.enchants().get(i).state() == EnchantInfo.State.NOT_RECORDED ? null : player.enchants().get(i);
                if (item == null) slot.setUnknown(); else slot.setItem(item, ItemTiers.label(item), enchant);
```

Keep the loop's remaining body (adding the slot and its names) unchanged. `import tomato.realmshark.EnchantInfo;` may already exist from Phase 2.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.runs.*'`
Expected: PASS, apart from the known `DungeonsSourceTest` timing baseline.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/runs src/test/java/tomato/gui/runs
git commit -m "Show each player's gear enchantments in the run recap" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 2: Character records keep the equipped items' enchant data

**Files:**
- Modify: `src/main/java/tomato/backend/data/CharacterJournal.java`:
  - `CharacterRecord` fields (~37-62)
  - `observe(...)` (~231-235)
  - `normalizeV5(CharacterRecord)` (~452)
  - `dropMalformedV5Fields` (~502)
  - `copy(...)` (~701)
  - `sameObservation(...)` (~741)
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java` (`EquippedCapture`: add `infos()`)
- Test: `src/test/java/tomato/backend/data/CharacterJournalTest.java`, `src/test/java/tomato/backend/data/CharacterJournalV5Test.java`, `src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java`

**Interfaces:**
- Produces:
  - `public String[] CharacterRecord.equipmentEnchants`: weapon, ability, armor and ring entries as last observed live. A null array means not recorded, and a null element means that slot was not recorded.
  - `public List<EnchantInfo> CharacterRecord.enchantInfos()`: null when not recorded.
  - Package-private `static String[] CharacterJournal.equippedEnchants(String captured)`.
  - `public List<EnchantInfo> ParseEnchants.EquippedCapture.infos()`.

- [ ] **Step 1: Write the failing tests**

In `CharacterJournalTest` (it has `temp`, `put(Entity, StatType, int)`, `player(account, classId)` and `file()`), add the tests below, plus `import tomato.realmshark.EnchantInfo;`:

```java
    @Test public void observedEquipmentEnchantsAreSavedKeptAndReloaded() throws Exception {
        Path file = file(); CharacterJournal j = new CharacterJournal(file);
        Entity e = player("account-a", 782);
        put(e, StatType.INVENTORY_0_STAT, 12345);
        StatData enchants = new StatData(); enchants.stringStatValue = "AAIE_wU,,,"; e.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        j.observe(e, 10);
        assertArrayEquals(new String[] {"AAIE_wU", "", "", ""}, j.characters().get(0).equipmentEnchants);
        assertEquals(EnchantInfo.Rarity.UNCOMMON, j.characters().get(0).enchantInfos().get(0).rarity());
        e.stat.set(StatType.UNIQUE_DATA_STRING, null);
        j.observe(e, 10);
        assertArrayEquals("An observation without the stat keeps the saved enchants", new String[] {"AAIE_wU", "", "", ""},
            j.characters().get(0).equipmentEnchants);
        StatData changed = new StatData(); changed.stringStatValue = "AAIE,,,"; e.stat.set(StatType.UNIQUE_DATA_STRING, changed);
        long before = j.revision();
        j.observe(e, 10);
        assertNotEquals("An enchants-only change is a change (so it is saved)", before, j.revision());
        j.save();
        assertArrayEquals(new String[] {"AAIE", "", "", ""}, new CharacterJournal(file).characters().get(0).equipmentEnchants);
    }

    @Test public void theEmptyShorthandIsFourUnenchantedSlotsAndMissingSlotsAreNotRecorded() {
        assertArrayEquals(new String[] {"", "", "", ""}, CharacterJournal.equippedEnchants(""));
        assertArrayEquals(new String[] {"AAIE", "", null, null}, CharacterJournal.equippedEnchants("AAIE,"));
    }
```

In `src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java` (it has `capture(String)`), add:

```java
    @Test public void infosListsTheFourEquippedSlots() {
        java.util.List<EnchantInfo> infos = capture("AAIE_wU,,!!!").infos();
        assertEquals(4, infos.size());
        assertEquals(EnchantInfo.Rarity.UNCOMMON, infos.get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, infos.get(1).rarity());
        assertSame(EnchantInfo.unreadable(), infos.get(2));
        assertSame(EnchantInfo.notRecorded(), infos.get(3));
    }
```

In `CharacterJournalV5Test`, follow the file's existing patterns:
- **Legacy file** (template at line ~45): a v4 or v5 file without `equipmentEnchants` loads, with `equipmentEnchants == null` and `enchantInfos() == null`.
- **Malformed field** (template at line ~68): write `"equipmentEnchants": 12`, then `["a","b"]` (wrong length). Each loads with `equipmentEnchants == null`, and the journal stays writable.
- **Deep copy** (template at line ~431): set `equipmentEnchants` and check that a copy's array is not the same instance and survives save and reload.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.backend.data.CharacterJournalTest --tests tomato.backend.data.CharacterJournalV5Test`
Expected: FAIL at compile time: `cannot find symbol ... equipmentEnchants / enchantInfos / equippedEnchants`.

- [ ] **Step 3: Implement**

`ParseEnchants.EquippedCapture`: add, below `info(int)`:

```java
        /** The four equipped slots' enchantments (weapon, ability, armor, ring). */
        public List<EnchantInfo> infos() {
            List<EnchantInfo> slots = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) slots.add(info(i));
            return List.copyOf(slots);
        }
```

In `CharacterJournal.CharacterRecord`, add after `equipment`:

```java
        /**
         * v5: the four equipped items' UNIQUE_DATA_STRING entries (weapon, ability, armor, ring) as last observed live; null = not
         * recorded (older records, or never observed since); a null element is a slot the capture did not include.
         */
        public String[] equipmentEnchants;

        /** The equipped items' enchantments for display; null when none were recorded. */
        public List<EnchantInfo> enchantInfos() {
            if (equipmentEnchants == null) return null;
            List<EnchantInfo> slots = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) slots.add(EnchantInfo.of(i < equipmentEnchants.length ? equipmentEnchants[i] : null));
            return List.copyOf(slots);
        }
```

In `observe(...)`, directly after the 28-slot equipment loop:

```java
        StatData enchants = player.stat.get(StatType.UNIQUE_DATA_STRING);
        // Like equipment, an observation without the stat keeps what was saved.
        if (enchants != null && enchants.stringStatValue != null) record.equipmentEnchants = equippedEnchants(enchants.stringStatValue);
```

Add the helper to `CharacterJournal`:

```java
    /** The first four comma-separated entries (the equipped slots); "" is the protocol's known-empty shorthand for all four. */
    static String[] equippedEnchants(String captured) {
        String[] parts = captured.isEmpty() ? new String[] {"", "", "", ""} : captured.split(",", -1);
        String[] slots = new String[4];
        for (int i = 0; i < 4; i++) slots[i] = i < parts.length ? parts[i] : null;
        return slots;
    }
```

Also make these changes in `CharacterJournal`:
- `copy(CharacterRecord)`: add `c.equipmentEnchants = r.equipmentEnchants == null ? null : r.equipmentEnchants.clone();`.
- `sameObservation(...)`: append `&& Arrays.equals(a.equipmentEnchants, b.equipmentEnchants)`.
- `dropMalformedV5Fields`: add `drop(r, "equipmentEnchants", String[].class);` beside the other v5 `drop(...)` calls.
- `normalizeV5(CharacterRecord)`: add `if (r.equipmentEnchants != null && r.equipmentEnchants.length != 4) r.equipmentEnchants = null;`.

Add imports as needed: `packets.data.enums.StatType`, `tomato.realmshark.EnchantInfo`, and `java.util.List`/`ArrayList`. `Document.version` stays 5.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.backend.data.*' --tests 'tomato.realmshark.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/backend/data/CharacterJournal.java src/main/java/tomato/realmshark/ParseEnchants.java src/test/java/tomato/backend/data src/test/java/tomato/realmshark/EquippedEnchantCaptureTest.java
git commit -m "Save each character's equipped enchant data when it is observed live" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 3: Character sheet shows saved enchants (Gear tab, Overview, Gear Analyst)

**Files:**
- Modify: `src/main/java/tomato/gui/glance/character/SheetModel.java` (the `Gear` record's doc comment only)
- Modify: `src/main/java/tomato/gui/glance/character/SheetModelBuilder.java` (`gear(...)` line ~129)
- Modify: `src/main/java/tomato/gui/glance/character/GearTab.java` (the `apply` comment; `SlotRows.of`, lines ~44-51)
- Modify: `src/main/java/tomato/gui/glance/character/OverviewTab.java` (gear fill, lines ~131-139)
- Modify: `src/main/java/tomato/gui/character/CharacterEquipmentPanel.java` (`Slot`, `project`, Item column renderer)
- Test: `src/test/java/tomato/gui/glance/character/SheetModelBuilderTest.java`, `GearTabTest.java`, `OverviewTabTest.java`, `src/test/java/tomato/gui/character/CharacterPlanningPanelTest.java`

**Interfaces:**
- Consumes: `CharacterRecord.enchantInfos()`, `EquippedCapture.infos()` (Task 2); `EnchantGem.decorate`, `EnchantTooltip.html`.
- Produces: `CharacterEquipmentPanel.Slot.enchant` (a `public final EnchantInfo`), which is null for inventory and backpack slots and when nothing was recorded.

- [ ] **Step 1: Write the failing tests**

`SheetModelBuilderTest`: add the test below. Keep the existing line ~56, a record without saved enchants, which still asserts `null`.

```java
    @Test public void aCharacterNotInGameShowsTheEnchantsLastObservedLive() {
        CharacterJournal.CharacterRecord r = SheetFixtures.record();
        r.equipmentEnchants = new String[] {"AAIE_wU", "", null, "!!!"};
        List<EnchantInfo> saved = SheetFixtures.model(r, SheetFixtures.account(), null).gear().enchants();
        assertEquals(EnchantInfo.Rarity.UNCOMMON, saved.get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, saved.get(1).rarity());
        assertSame(EnchantInfo.notRecorded(), saved.get(2));
        assertSame(EnchantInfo.unreadable(), saved.get(3));
    }
```

`GearTabTest`: add a test that applies `model(record-with-equipmentEnchants, account(), null).gear()` and asserts `slot(tab, 0).enchant().rarity() == UNCOMMON`. The existing live-only test keeps passing, because its `other` character's record has no saved enchants.

`OverviewTabTest`: add a test that the Overview's equipped slot `character-overview-slot-0` shows `enchant()` for a record with saved enchants. Follow the file's existing way of applying a model.

`CharacterPlanningPanelTest` (it has `record(String account)`). The existing `equipmentHasAll28DetachedStatesAndNoLiveEnchantClaim` must keep passing unchanged. Add the test below, plus `import tomato.realmshark.EnchantInfo;`:

```java
    @Test public void equippedSlotsShowTheEnchantsLastObservedLive() {
        CharacterJournal.CharacterRecord r = record("A"); r.equipment[0] = 123; r.equipmentEnchants = new String[] {"AAIE_wU", "", "", ""};
        java.util.List<CharacterEquipmentPanel.Slot> rows = CharacterEquipmentPanel.project(r, RosterDefinitions.empty());
        assertEquals(EnchantInfo.Rarity.UNCOMMON, rows.get(0).enchant.rarity());
        assertTrue(rows.get(0).detail, rows.get(0).detail.contains("Enchantment effects: Uncommon · 1 enchant slot"));
        assertNull("Inventory slots carry no enchant data", rows.get(4).enchant);
        assertTrue(rows.get(4).detail.contains("Enchantment effects: Not recorded"));
        assertNull("A record without saved enchants carries none", CharacterEquipmentPanel.project(record("B"), RosterDefinitions.empty()).get(0).enchant);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.glance.character.*' --tests tomato.gui.character.CharacterPlanningPanelTest`
Expected:
- Compile failure on `Slot.enchant`.
- `SheetModelBuilderTest` fails on the null `enchants()`.
- The Overview assertion fails.

- [ ] **Step 3: Implement**

`SheetModelBuilder.gear(...)` (line ~129): use the saved enchants when the character is not in game:

```java
        return new SheetModel.Gear(list(slots), List.copyOf(tiers), r.hasBackpack, playing ? enchants(live) : r.enchantInfos());
```

`enchants(live)` may now be written as `live.build() == null ? null : live.build().enchants().infos()`; keep its behavior. Update the `gear(...)` Javadoc:
- Live characters show their live enchantments.
- Others show the enchantments last observed live, or none when never recorded.

`SheetModel.Gear` doc: replace "null unless playing" with "live while playing, else as last observed live; null when never recorded".

`GearTab`:
- Update the `apply` comment "saved records show no gem" to say saved records show the enchantments last observed live.
- In `SlotRows.of(...)`, add the slot's enchantments to each row, so a change repaints the Analyst table. `EnchantInfo` is a record, so equality works:

```java
                rows.add(Arrays.asList(slot.state, slot.item, slot.item == null || slot.item < 0 ? null : IdToAsset.objectName(slot.item), slot.evidence, slot.enchant));
```

`OverviewTab` (lines ~131-139): pass the equipped slot's enchantments.

```java
                EnchantInfo enchant = equipped == null || equipped.enchants() == null ? null : equipped.enchants().get(i);
                if (id > 0) gear[i].setItem(id, tier, enchant); else if (id == 0) gear[i].setEmpty(); else gear[i].setUnknown();
```

Add `import tomato.realmshark.EnchantInfo;`. The `gearShown` equality already covers `enchants`, because `Gear` is a record.

`CharacterEquipmentPanel`:
- `Slot`: add `public final EnchantInfo enchant;` as the last field, plus a constructor parameter that assigns it. Only `project` constructs `Slot`.
- `project(...)`: compute the enchant for equipped slots, and use it in the detail line.

```java
            EnchantInfo enchant = i < 4 && record.equipmentEnchants != null ? record.enchantInfos().get(i) : null;
            ...
                + "\nEnchantment effects: " + (enchant == null ? "Not recorded in this character snapshot" : enchant.text().replace("\n", "\n  "));
            result.add(new Slot(i, item, group, name, state, evidence, detail, enchant));
```

- Item column renderer: decorate the icon, and build the enchant tooltip lazily. `JTable` asks the renderer's `getToolTipText()` on hover, and painting never calls it.

```java
        table.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell() {
            private Slot current;
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, s, f, row, col); current = shown.get(t.convertRowIndexToModel(row));
                Icon icon = current.item == null || current.item < 0 ? null : ImageBuffer.getOutlinedIcon(current.item, 24);
                setIcon(EnchantGem.decorate(icon, current.enchant)); return this;
            }
            /** Built when the table asks (on hover): the slot's enchant lines, when its enchantments were recorded. */
            @Override public String getToolTipText() {
                return current == null || current.enchant == null ? super.getToolTipText() : EnchantTooltip.html(itemName(current.item), current.enchant);
            }
        });
```

Imports: `tomato.gui.kit.EnchantGem`, `tomato.gui.kit.EnchantTooltip`, `tomato.realmshark.EnchantInfo` and `javax.swing.Icon`, unless already covered by wildcards. `itemName(Integer)` is the file's existing helper.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.glance.character.*' --tests 'tomato.gui.character.*'`
Expected: PASS (compare any window-size/evidence failure against the known list).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/glance/character src/main/java/tomato/gui/character/CharacterEquipmentPanel.java src/test/java/tomato/gui/glance/character src/test/java/tomato/gui/character
git commit -m "Show saved enchantments on the Character sheet's Gear, Overview and Analyst slots" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 4: Home hero card shows enchants

**Files:**
- Modify: `src/main/java/tomato/gui/glance/home/HomeModel.java` (`Hero` record, lines ~33-63)
- Modify: `src/main/java/tomato/gui/glance/home/HomeModelBuilder.java` (`fromLive` ~112, `fromJournal` ~135)
- Modify: `src/main/java/tomato/gui/glance/home/HeroCard.java` (`gear(...)`, lines ~141 and ~200-208)
- Test: `src/test/java/tomato/gui/glance/home/HomeModelBuilderTest.java`, `src/test/java/tomato/gui/glance/home/HeroCardTest.java`

**Interfaces:**
- Consumes: `EquippedCapture.infos()` and `CharacterRecord.enchantInfos()` (Task 2).
- Produces: `HomeModel.Hero` gains a 23rd component, `List<EnchantInfo> enchants`: four entries, or null when none were recorded. A 22-argument constructor stays.

- [ ] **Step 1: Write the failing tests**

`HomeModelBuilderTest` (it has `hero(…, last, account, now)`, `account()`, `CLASS` and `NOW`), plus `import tomato.realmshark.EnchantInfo;`:
- In `journalRecordIsStaleAndNothingIsEmpty`, add `assertNull("No saved enchants", h.enchants());`.
- Add this test:

```java
    @Test public void aJournalHeroShowsTheEnchantsLastObservedLive() {
        CharacterJournal.CharacterRecord last = new CharacterJournal.CharacterRecord();
        last.name = "Saved"; last.classId = CLASS; last.className = "Wizard"; last.characterId = 3; last.lastSeen = NOW - 7_200_000;
        last.equipment[0] = 2001; last.equipmentEnchants = new String[] {"AAIE_wU", "", "", ""};
        HomeModel.Hero h = hero(null, null, last, account(), 0);
        assertEquals(EnchantInfo.Rarity.UNCOMMON, h.enchants().get(0).rarity());
        HomeModel.Hero without = new HomeModel.Hero(h.state(), h.name(), h.classId(), h.className(), h.skin(), h.level(), h.fame(), h.maxed(),
            h.base(), h.caps(), h.totals(), h.potionsNeeded(), h.needsLine(), h.exaltTiers(), h.equipment(), h.weaponDps(), h.mpPerSecond(),
            h.accountLine(), h.lastSeenAt(), h.evidence(), h.key(), h.petChip());
        assertNotEquals("Enchants alone make two heroes differ (so the card repaints)", without, h);
    }
```

`HeroCardTest` (it has `card()`, `HomeModels.hero(...)`, `named(...)` and `NOW`), plus imports `tomato.realmshark.EnchantInfo` and `java.util.List`:

```java
    @Test public void heroGearShowsEachSlotsEnchants() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeroCard card = card();
            HomeModel.Hero h = HomeModels.hero(HomeModel.State.STALE, NOW);
            EnchantInfo uncommon = EnchantInfo.ofSlotCount(1);
            card.apply(new HomeModel.Hero(h.state(), h.name(), h.classId(), h.className(), h.skin(), h.level(), h.fame(), h.maxed(),
                h.base(), h.caps(), h.totals(), h.potionsNeeded(), h.needsLine(), h.exaltTiers(), new int[]{2593, 2594, -1, -1},
                h.weaponDps(), h.mpPerSecond(), h.accountLine(), h.lastSeenAt(), h.evidence(), h.key(), h.petChip(),
                List.of(uncommon, EnchantInfo.notRecorded(), EnchantInfo.notRecorded(), EnchantInfo.notRecorded())), NOW);
            assertEquals(uncommon, named(card, "home-hero-slot-0", ItemSlot.class).enchant());
            assertNull("Not recorded reads as before", named(card, "home-hero-slot-1", ItemSlot.class).enchant());
        });
    }
```

The live hero's enchants come from `EquippedCapture.infos()`, which Task 2's `infosListsTheFourEquippedSlots` pins. `fromLive` only passes that value through.

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests tomato.gui.glance.home.HomeModelBuilderTest --tests tomato.gui.glance.home.HeroCardTest`
Expected: FAIL at compile time: `cannot find symbol ... enchants()`.

- [ ] **Step 3: Implement**

`HomeModel.Hero`:
- Append the component `List<EnchantInfo> enchants` after `petChip`.
- In the compact constructor: `enchants = enchants == null ? null : List.copyOf(enchants);`.
- Add the old 22-argument constructor, delegating with `null`.
- Include `enchants` in the hand-written `equals`, as `&& Objects.equals(enchants, h.enchants)`, and in `hashCode`.
- Leave `placeholder(...)` using the 22-argument constructor.
- Document the component: the four equipped items' enchantments; live when in game, else as last observed live; null when none were recorded.

`HomeModelBuilder`:
- `fromLive(...)`: pass `live.build() == null ? null : live.build().enchants().infos()` as the new last argument.
- `fromJournal(...)`: pass `last.enchantInfos()`.

`HeroCard`:
- Change `gear(int[] equipment)` to `gear(int[] equipment, List<EnchantInfo> enchants)`, and call it with `gear(hero.equipment(), hero.enchants())`.
- Inside, pass each slot's enchantments. Not recorded reads as before:

```java
            EnchantInfo enchant = enchants == null || enchants.get(i).state() == EnchantInfo.State.NOT_RECORDED ? null : enchants.get(i);
            if (id > 0) gear[i].setItem(id, tier, enchant);
```

Keep the rest of the method. `HeroCard`'s `hero.equals(shown)` skip now sees enchant changes, because `Hero.equals` includes them.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.gui.glance.home.*'`
Expected: PASS, apart from the known `HomeRefreshTimingTest` timing flake.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/glance/home src/test/java/tomato/gui/glance/home
git commit -m "Show the Home hero's equipped enchantments" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU"
```

---

### Task 5: Phase verification, review and PR

**Files:** none changed unless a check fails.

- [ ] **Step 1: Leftover and compatibility scan**

Search the branch diff (`git diff main...HEAD -- src`) for `TODO`, `@Ignore` and `.skip(`: expect none.

Confirm that `ActivityJournal.java` and `ActivityStore.java` are unchanged, and that `CharacterJournal`'s `Document.version` is still 5.

- [ ] **Step 2: Focused suite plus build**

Run: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests 'tomato.realmshark.*' --tests 'tomato.backend.data.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.glance.*' --tests 'tomato.gui.character.*' --tests 'packets.packetcapture.logger.*' shadowJar`
Expected: BUILD SUCCESSFUL, or only the known environmental failures. Confirm anything else against `main` in a temporary worktree before calling it a regression.

- [ ] **Step 3: Visual check**

Open the character-sheet and Home evidence captures written under `build/ui-test/screenshots/` in both themes. Check that equipped slots with saved enchants show gems.

If no capture carries enchant data, say so, and rely on the component tests.

- [ ] **Step 4: Launch smoke**

Launch the built jar from an isolated folder:
- its own copy of `assets/`;
- `util.InMemoryPreferencesFactory` from `build/classes/java/test` on the classpath;
- `-Drealmshark.historyDir=<isolated folder>`.

Do **not** start capture. Confirm the app reaches Home, has no console exception, and closes cleanly.

- [ ] **Step 5: Independent review**

Dispatch a fresh reviewer on the branch diff against `main`. Give it the spec path, this plan's path and the Review Focus list. Fix confirmed findings test-first, then rerun Step 2 for the touched packages.

- [ ] **Step 6: Push and open the PR (after the user confirms)**

```bash
git push -u origin feat/enchant-rarity-p3
gh pr create --base main --title "Show enchant rarity on run recap players, characters and Home (phase 3)" --body-file <body file outside the repo index>
```

The PR body's sections:
- **What changes:** run-recap players, character records recording enchants, Gear tab, Overview, Gear Analyst and the Home hero.
- **How:** the record field, `enchantInfos()`, `EquippedCapture.infos()`, and the model components.
- **Compatibility:** journal version 5 unchanged, the field optional and dropped when malformed, `activity-history.json` untouched.
- **Validation:** the Step 2–4 results.
- **Deferred items.**

End the body with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01KtVqdQbZmiqLKY7RecsUpU
```
