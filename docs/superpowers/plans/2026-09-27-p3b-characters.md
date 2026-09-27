# P3b Characters: Exalts Grid, Pets, Pet and Fame Tabs Implementation Plan

> **For agentic workers:** implement with subagent-driven development: one fresh implementer subagent per task, in an isolated worktree, working test-first; the coordinator (Claude) reviews every task's diff and merges it. Steps use checkbox (`- [ ]`) syntax. This plan is a **contract plan**: it fixes the decisions, file ownership, names, signatures, behavior and the tests each task must add, and leaves the code to the implementer. Research notes with verbatim current code are listed under "Sources" and are the implementer's reference for existing APIs.

**Goal:** Finish the Characters redesign (spec §6.2). The Characters page's Exalts tab becomes an account Exalts grid with class drill-down; the Pets tab becomes a Pets gallery with the feeding calculator in a drawer; the character sheet gains Pet and Fame tabs, an Overview pet card and Goals shown as cards with progress; Home's hero gains a pet rarity chip. The P3a review findings deferred to P3b are fixed first.

**Architecture:**
- **Wave A (parallel):** journal/history hardening and fame provenance (Task 1), pet names and pet summaries (Task 2), a shared painted tile list plus gallery/roster/navigation fixes (Task 3), sheet presenter and tab hardening (Task 4).
- **Wave B (parallel, after A is merged):** the sheet's Pet tab, Overview pet card and Home pet chip (Task 5), the account Exalts grid (Task 6), the Pets gallery (Task 7).
- **Wave C (parallel, after B is merged):** the Fame tab (Task 8), Goals as cards (Task 9).
- **Wave D:** evidence, docs, validation record, final suite and JAR smoke (Task 10).
- Every view model is built off the EDT and applied on the EDT, as in P3a. No journal version change: journal stays at version 5.

**Tech Stack:** Java 17, Swing, FlatLaf 3.5.4, Gson 2.9.1 (persisted types stay plain classes), JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md`: §1 (honesty invariants), §3.2 (Simple/Analyst), §4.4 (customizable tabs), §5.3 and §5.7 (components, `DisplayValue`), §6.1 (hero chips), §6.2 (Characters), §7 (states), §9 (painted lists), §10 (accessibility).

**Depends on:** P3a merged (PR #22, merge `04a61d4`, which includes the Codex pet-merge fixes `508d1d1`).

**Sources (read before coding; verbatim current code with file:line):**
- `docs/superpowers/plans/2026-09-27-redesign-handoff.md` §6 (P3b inputs and deferred findings).
- Research notes, kept in the coordinator's scratchpad and handed to each implementer: R1 sheet architecture, R2 pets, R3 exalts and kit APIs, R4 fame history and Home, R5 deferred findings and Goals. Where a note and the code disagree, the code wins.
- The P3a plan `docs/superpowers/plans/2026-09-27-p3a-characters.md`, "Cross-task notes", for names, fixtures and test hygiene that still hold.

---

## Global Constraints

- **Branch and PR.** Integration branch: `claude/redesign-handoff-next-steps-edrr7w` (cloud session branch, from merged `main` `04a61d4`). Implementers commit on their own worktree branch and never push; the coordinator merges each reviewed task into the integration branch, pushes, and opens one P3b PR against `main`. No direct commits to `main`, force pushes or hook bypasses.
- **Commands (Linux cloud session).** JDK 17 must be the full JRE (`openjdk-17-jdk-headless` plus `openjdk-17-jre`; the headless package alone makes every Swing test throw `HeadlessException`). From the worktree root:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 LC_ALL=C.UTF-8
  xvfb-run -a -s "-screen 0 1920x1200x24" sh ./gradlew --console=plain -PrealmSharkBuildDir=build/p3b-tN --project-cache-dir build/p3b-tN-cache -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64 <tasks>
  ```
  Steps abbreviate this as `GRADLE <tasks>`; `tN` is the task number, so parallel workers never share a build directory. On Windows use the P3a plan's PowerShell form with the same tasks.
- **Validation limits (AGENTS.md).** Focused tests per task; one baseline full suite (coordinator, before Wave A) and one final full suite (Task 10). Never run `scripts/Set-CiDisplay.ps1`, start live capture or send bridge deliveries. Synthetic fixtures and isolated preferences only. A failing test is never "a flake" without a root cause: rerun once alone to check for load-induced timing, then fix or report it.
- **Commits.** One commit per task step group as written; explicit paths in `git add` (never `-A`, `-u` or `.`); message subject in the imperative, a body saying why, and the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.
- **Existing assertions.** Every change to an existing assertion is marked in the task report as **add beside** or **replace** with the reason. Replace only when the behavior intentionally changes. Never weaken an assertion to make a test pass.
- **File ownership.** Within a wave, a file listed under one task's **Owns** is edited by that task only. A task that needs a change in a file it does not own stops and reports it. Test fixtures are owned too (see each task).
- **Tab restore.** Startup and live-state restore only `select()` a tab; only explicit navigation (Back, a route, "Open goals", clicking the Overview pet card) may `show()` a hidden tab.
- **Preference hygiene.** Tests restore every preference they change: `ui.nav.*`, `ui.tabs.*` (notably `ui.tabs.character`, `ui.tabs.characters`), `ui.filters.*`, `ui.home.*`, `ui.characters.*`, `ui.collapse.*`, `ux.archive.*` (notably `ux.archive.characters-live-roster`), `ui.mode`, and `DisplayModeModel.application()`. Use `@After` or `finally`.
- **Honesty (spec §1).** Unknown is never shown as zero ("—" with a one-line reason in the tooltip). Estimates show "≈". Stale values are labeled. Links between modules are exact: journal key, `VisitRef`, or (new in Task 1) the fame sample's account key. Never join by name or time.
- **Pet semantics.** `PetRecord.absent == TRUE` is a known "No pet"; `pet == null` is unknown. A pet's identity is its instance id; a record without one is never merged with another.
- **Threading.** Sources are read off the EDT; the EDT only swaps immutable models and repaints. `CharacterJournal` accessors are synchronized deep copies. `RosterDefinitions.current()`, `PlanningMetadata.current()` and (Task 2) `PetDefinitions.current()` load asynchronously and return an unavailable/loading value first: render "—" or a loading state for them and rebuild when their identity changes.
- **Journal.** Stays version 5. Every v5 field stays optional and null-tolerant; a malformed field is dropped to unknown, never thrown.
- **Styling.** Colors from `tomato.gui.kit.Tokens`, fonts from `Type`/`KitText`/`ContentStyle.font`, motion only through `Motion.run`. New in-memory view models may be `record`s; persisted types stay plain classes. Game-meaning colors never signal status.
- **Stable names and indices.** No new shell page; page indices stay stable until P6. Keep existing component names unless a task says otherwise. New names are listed per task and must be used exactly.
- **Canonical stat order: life, mana, atk, def, spd, dex, vit, wis.** Exalt arrays are ordered dex, spd, vit, wis, def, atk, mana, life (`CharacterJournal.EXALT_ORDER` converts).
- **Encoding.** Sources are UTF-8; literal `·`, `…`, `‹`, `—`, `≈` are fine.

## Decisions recorded for this phase

User decisions (2026-09-27):
- **Process.** Subagents do the parallel work and the implementation; Claude reviews every task (subagent-driven development).
- **Pets gallery data: equipped pets plus the live Pet Yard.** No journal format change. The gallery always shows the current account's equipped pets from the journal (one per character, de-duplicated by instance id) and, while the player is in the Pet Yard this connection, every yard pet from `ProgressionData`, labeled "In the Pet Yard now". Pets not equipped are not remembered after leaving the yard.
- **Fame history is exact by account.** New fame samples also store the journal's hashed account key (the same pseudonymous 64-hex key `Characters/journal.json` already holds). The Fame tab shows only samples whose account and character id both match the sheet's key; older samples without an account are excluded and counted ("N older readings have no recorded account and are not shown"). This adds a hashed account key to the history folder, which so far held no account identifier: `docs/SESSION-HISTORY.md` and `docs/CHARACTERS.md` state it (Task 10).
- **Goals show this character's cards.** The sheet's Goals tab shows cards with progress for this character's stat goals and this class's exalt goals, scoped to the sheet's own account (exact, from the journal key). The existing account-wide goals panel (explicit account picker, table and editors) stays below the cards: expanded in Analyst mode; in Simple mode inside a collapsed "Manage goals" section, so goals stay editable in both modes (coordinator's reading of "the table stays below in Analyst mode").
- **Loot boost is per class.** Each class tile shows its own loot boost; the header tile shows the boost for the class in game, else the class last played, and names that class. Unknown ("—") when the weapon groups cannot be read from the selected game assets or the account has no saved exalt counts.

Coordinator decisions:
- **Rarity names** use the game's 0-based order: 0 Common, 1 Uncommon, 2 Rare, 3 Legendary, 4 Divine (the same five names `EnchantDots` uses; consistent with the max-ability tiers 30/50/70/90/100 that `PetFeeding` uses). Any other value is unknown ("—"), never guessed.
- **Family names** come only from `<assetRoot>/xml/pets.xml`: the `<Family>` text of the pet's type object (`PetRecord.type`, `PET_TYPE_STAT` 83). The file's structure is not verifiable in the repo, so the parser is defensive (every tag optional, unknown → "—"). The numeric `PET_FAMILY_STAT` (86) is never decoded to a name.
- **Ability names** move from `CharacterPetsGUI` into `PetDefinitions.ability(int)` (ids 402–411 as today; 403 and any other id are unknown).
- **Exalts drill-down stays inside the Exalts tab:** the grid and one class's detail swap in place with a "‹ Exalts" back link; no new destination or route.
- **Exalts grid account:** Home's "current account" rule (the live account, else the last known live account, else the account of the journal's most recent character). When the journal has more than one account with saved exalt counts, an account selector appears; its choice is not persisted.
- **Tab ids and order.** The sheet's group `character` gains `pet` and `fame`, registered right after `exalts`: overview, gear, exalts, pet, fame, build, goals, notes, evidence (Analyst), death (conditional). New users get this order; a saved order gets the new ids appended (as `build` was in P3a).
- **Fame tab reads history on its own thread** ("character-fame"), never on each sheet rebuild: once when the sheet opens a key and then at most every 30 s while the Fame tab is showing. Finished sessions are cached by session id.
- **Build tab lag is kept.** After a character switch the Build card follows within the sheet's 1 s refresh; P3b does not add a live-character listener. Only the failed-build wording changes (Task 4).
- **Deferred (not in P3b):** the handoff's P5 and P6 lists stay deferred, unchanged.

---

## File map and ownership

| Wave | Task | Owns (main) | Owns (test) |
|---|---|---|---|
| A | 1 Journal/history hardening, fame provenance, loot overload | `backend/data/CharacterJournal.java`, `realmshark/RealmCharacter.java`, `backend/data/TomatoData.java`, `backend/data/Entity.java`, `history/AppHistory.java`, the fame bridge (`FameTableBridge`), `gui/glance/home/HomeArchive.java` | `CharacterJournalV5Test`, `FameVisitHookTest`, new `RealmCharacterLootBonusTest`, new `FameProvenanceTest`, `HomeArchive*Test` |
| A | 2 Pet names and summaries | new `backend/data/PetDefinitions.java`, new `gui/glance/character/PetSummary.java` | new `PetDefinitionsTest`, new `PetSummaryTest` |
| A | 3 Tile list, gallery/roster/navigation fixes | new `gui/kit/TileList.java`; `CharacterGallery`, `CharacterCardRenderer`, `RosterViews`, `CharacterJournalGUI`, `CharactersRouteTarget`, `CharacterRosterView`, `CharacterPanelGUI`, `TomatoGUI` | new `TileListTest`; `CharacterGalleryTest`, `CharacterCardRendererTest`, `RosterViewsTest`, `CharacterRosterViewTest`, `CharactersRouteTargetTest`, `ui.WorkspaceUiTest`, `ui.CharactersEvidenceTest`, `CharacterFixtures`, `RosterFixtures` |
| A | 4 Sheet presenter and tab hardening | `SheetPresenter`, `SheetModel`, `SheetModelBuilder`, `OverviewTab`, `GearTab`, `BuildTab`, `CharacterSheet`, `SheetContext` | `SheetModelBuilderTest`, `OverviewTabTest`, `GearTabTest`, `BuildTabTest`, `CharacterSheetTest`, `SheetFixtures` |
| B | 5 Pet tab, Overview pet card, Home pet chip | new `PetTab`; `SheetModel`, `SheetModelBuilder`, `SheetPresenter`, `CharacterSheet`, `OverviewTab`; `HomeModel`, `HomeModelBuilder`, `HeroCard`, `LiveHomeSources` | new `PetTabTest`; `SheetModelBuilderTest`, `OverviewTabTest`, `CharacterSheetTest`, `CharacterTabsTest`, `CharacterJournalLayoutTest`, `SheetFixtures`, `CharacterFixtures`, `HeroCardTest`, `HomeModelBuilderTest`, `HomeModels`, `HomeRefreshTimingTest` |
| B | 6 Account Exalts grid | new `ExaltsGrid`, `AccountExalts`, `AccountExaltsBuilder`, `ExaltTileRenderer`; `ExaltsTab`, `CharacterPanelGUI`, `CharacterJournalGUI` | new `AccountExaltsBuilderTest`, `ExaltsGridTest`, `ExaltTileRendererTest`, `ExaltFixtures`; `ExaltsTabTest`, `CharacterJournalGuiTest` |
| B | 7 Pets gallery | `CharacterPetsGUI`; new `PetGalleryModel`, `PetCardRenderer` (package `tomato.gui.character`) | new `PetGalleryModelTest`, `PetCardRendererTest`, `PetFixtures`; `PetFeedingFormTest`, `PetLayoutEvidenceTest` |
| C | 8 Fame tab | new `FameHistory`, `FameModel`, `FameChart`, `FameTab`, `FamePresenter` (package `tomato.gui.glance.character`); `SheetPresenter` | new `FameHistoryTest`, `FameModelTest`, `FameTabTest`, `FameFixtures` |
| C | 9 Goals as cards | new `GoalCardsModel`, `GoalCards` (package `tomato.gui.glance.character`); `CharacterSheet`, `CharacterPlanningPanel` | new `GoalCardsModelTest`, `GoalCardsTest`; `CharacterPlanningPanelTest`, `CharacterWaveFourEvidenceTest`, `CharacterJournalLayoutTest`, `CharacterSheetTest`, `SheetFixtures` |
| D | 10 Evidence, docs, validation | `README.md`, `docs/CHARACTERS.md`, `docs/SESSION-HISTORY.md`, roadmap, handoff, `docs/UX-CHECKPOINT.json`, new `docs/superpowers/plans/2026-09-27-p3b-validation.md` | `ui.CharactersEvidenceTest`, `ui.HomeEvidenceTest`, `CharacterFixtures` |

Paths are under `src/main/java/tomato/` and `src/test/java/tomato/` (package `ui` tests under `src/test/java/ui/`) unless shown in full. Short class names are in their P3a packages: `gui.glance.character` (sheet, gallery), `gui.character` (roster, panels), `gui.glance.home` (Home).

---

## Wave A

### Task 1: Journal and history hardening, fame provenance, loot boost over saved counts

**Owns:** see the file map. **Depends on:** nothing in P3b.

**Scope (deferred findings 1, 3, 4, 5, 12 of the handoff, plus the Task 6 and Task 8 prerequisites):**

1. **Explicit save-failure flag.** `CharacterJournal` gains a private `volatile boolean saveFailed`, set in both save failure paths and cleared by a successful write. `storageProblem()` and `storageStatus()` decide from this flag and `readable()`, never from the "Save failed" message prefix. Messages keep their current text.
2. **Complete "no pet" contradiction check.** `validPet` also rejects `absent == TRUE` together with `skin`, `maxAbilityPower`, or any ability type/level/points other than -1. Such a record loads as unknown (`pet == null`), never as a failure.
3. **Strict primitive parsing for v5 fields.** A v5 field whose JSON value is not exactly its type is dropped to unknown before binding: booleans must be JSON booleans (`"yes"`, `1` are malformed); `long`/`Long`/`int` fields and `int[]` elements must be JSON numbers with an exact integral value inside the Java type's range (`"123"`, `1.5`, `4294967297` for an int are malformed). Map keys of `exaltSeenByClass` keep today's lenient string-to-int key parsing; its values are strict. The journal stays writable after dropping a malformed field. Pre-v5 fields are unchanged.
4. **One constant for "pet.none".** `public static final String PET_NONE = "pet.none";` on `tomato.realmshark.RealmCharacter`; every use in `src/main` (`CharacterJournal`, `TomatoData` ×2) and `CharacterJournalV5Test` uses it.
5. **Backup-failure recovery text.** When the one-time `journal.v4.bak` cannot be written, the status keeps naming `journal.v4.bak` and adds recovery guidance: `journal.json` is unchanged; move or delete whatever is at `journal.v4.bak`, or free disk space; saving retries automatically. No absolute paths in the text.
6. **Loot boost over saved counts.** On `RealmCharacter`:
   - `public static int exaltLootBonus(Map<Integer, int[]> exalts, int[] weaponGroup)`: the existing formula over an explicit counts map (class id → 8 counts in `RealmCharacter` order) and weapon group; `weaponGroup` must be non-null (`NullPointerException` otherwise). 35 when `fullyExalted(exalts)`; otherwise the existing 0/5/10/15/20/25 rule.
   - `public static boolean fullyExalted(Map<Integer, int[]> exalts)`: the existing rule (at least 19 classes, every count ≥ 75).
   - The existing `exaltLootBonus(int classId)` and `fullyExalted()` delegate to these with the static live map and `CharacterClass.weaponClasses(classId)`; behavior of `LootGUI` and `SendLoot` is unchanged.
7. **Fame provenance.** `AppHistory.FameSample` gains `public final String account` (the journal account key, 64 hex; null = not recorded) and a constructor taking it; both existing constructors stay (account null). New `AppHistory.fame(int character, String account, long fame, long time, String className)`; the existing 4-argument form delegates with `null`. In `record(...)`: the change-detection map is keyed by `account + ":" + character` (the literal `"?:" + character` when account is null), and the `fame-latest` checkpoint key is `account + ":" + character` when the account is known, else `Integer.toString(character)`. `Entity.fame(long)` keeps its signature and passes the player's account key, computed exactly as `TomatoData` computes `journalAccount` from `ACCOUNT_ID_STAT` (via `CharacterJournal.accountKey`), through a new overload of the fame bridge's `observeFame`. When the account stat is not yet known the sample's account is null.
8. **Home Today series by account.** `HomeArchive`'s per-series key (`session.id + "/" + sample.character + "/" + sample.className`) also includes `sample.account` (empty when null), so an in-session account switch no longer merges two series. Legacy samples behave exactly as before.

**Tests (all new methods; add beside):**
- `CharacterJournalV5Test`: `aFailedSaveIsFlaggedUntilTheNextSuccessfulWrite` (a store that fails once then succeeds: `storageProblem()` non-null after the failure, null after the retry; independent of the message text); `aNoPetRecordWithAnyPetValueLoadsAsUnknown` (absent + skin; absent + maxAbilityPower; absent + one ability level); `v5FieldsParseStrictly` (`"hasBackpack":"yes"`, `"exp":"123"`, `"exp":1.5`, `"dungeonCompletionsObservedAt":"5"`, `"vaultPotions":[1,2,3,4,5,6,7,4294967297]`, `"exaltSeenByClass":{"782":"7"}` each load as unknown for that field only; a valid neighbour field still loads; `readable()` stays true; the next `save()` writes version 5); `theBackupFailureStatusExplainsRecovery` (status contains `journal.v4.bak` and "unchanged" and "retries"; after the obstacle is removed the next save succeeds and the status clears).
- New `tomato.realmshark.RealmCharacterLootBonusTest`: every threshold (a class below 5 → 0; lowest tier 1..5 → 5..25), a group member without counts → 0, 19 fully exalted classes → 35, 18 → not fully exalted, the static live form still delegates (set and restore `RealmCharacter.exalts`).
- New `tomato.history.FameProvenanceTest` (temporary `SessionStore`): a sample with an account round-trips with its account; a legacy JSON line without `account` loads with `account == null`; two accounts with the same character id produce two `fame-latest` entries and two change-detection streams; the 4-argument `AppHistory.fame` still records with a null account.
- `FameVisitHookTest`: add beside: the existing legacy-shape assertion still holds for a null account.
- A `HomeArchive` test (the existing `HomeArchive*Test` class that covers Today, else a new `HomeArchiveAccountTest`): two accounts' samples with the same character id and class in one session make two series; legacy samples (null account) produce the same totals as before.

**Steps:**
- [ ] Read the Task 1 sources (R5 items 1, 3, 4, 5, 12; R3 §2.4; R4 §1.1–1.2, §1.7, §2.2).
- [ ] Write the failing tests above; run them (`GRADLE test --tests "tomato.backend.data.CharacterJournalV5Test" --tests "tomato.realmshark.RealmCharacterLootBonusTest" --tests "tomato.history.*" --tests "tomato.gui.glance.home.*"`) and confirm each fails for the expected reason.
- [ ] Implement items 1–8.
- [ ] Run the focused tests plus `tomato.backend.data.*`, `tomato.history.*`, `tomato.gui.glance.home.*`, `SendLootTest` and the loot GUI tests that call `exaltLootBonus`; all pass.
- [ ] Commit (suggested subjects: "Harden journal v5 loading and save-failure reporting", "Compute the exalt loot boost from saved counts", "Record the account on new fame samples").

### Task 2: Pet names and pet summaries

**Owns:** new `src/main/java/tomato/backend/data/PetDefinitions.java`, new `src/main/java/tomato/gui/glance/character/PetSummary.java` and their tests. **Depends on:** nothing in P3b. Does **not** edit `CharacterPetsGUI` (Task 7 switches it over).

**`PetDefinitions`** (public, immutable, follows `PlanningMetadata`'s loader pattern: secure XML parsing, size limit, lazy background read keyed on `AssetCache.root()`, re-read when the root changes):
```java
public final class PetDefinitions {
    public final boolean available;
    public final String status;
    /** The selected assets' pet names; loading() until the background read finishes, unavailable() without the file. Never blocks. */
    public static PetDefinitions current();
    public static PetDefinitions loading();      // available=false, status "Pet names load with the selected game assets"
    public static PetDefinitions unavailable();  // available=false, status "Pet names are unavailable in the selected game assets"
    public static PetDefinitions read(java.nio.file.Path root);   // <root>/xml/pets.xml; unavailable() when missing, too large or malformed
    static PetDefinitions parse(byte[] xml);                       // test seam; tolerant of any missing tag
    /** The <Family> text of the pet type object with this id; null when unknown or not loaded. */
    public String family(Integer petType);
    /** 0 Common, 1 Uncommon, 2 Rare, 3 Legendary, 4 Divine; null for null or any other value. */
    public static String rarity(Integer rarity);
    /** Ability names for ids 402–411 exactly as CharacterPetsGUI names them today; null for any other id or a negative id. */
    public static String ability(int abilityType);
    /** Tests: pins current() to value until the returned handle is closed. */
    static AutoCloseable install(PetDefinitions value);
}
```
- Parsing: every `<Object>` element whose `type` attribute parses (hex `0x…` or decimal) and that has a non-blank `<Family>` child contributes `type → family text` (trimmed). Duplicates keep the first. Objects without `<Family>` are ignored. A document with no family at all is still `available` (names are then unknown per pet).
- `install` must be visible to tests in other packages: make it `public static` with a javadoc "tests only", or expose a public test hook as `PlanningMetadata`/`RosterDefinitions` do; follow whichever pattern those classes use.

**`PetSummary`** (public record, pure, no Swing; the one pet view model for the sheet, the Overview card, the gallery and Home):
```java
public record PetSummary(State state, Long instanceId, String name, Integer skin, Integer type, Integer rarityValue,
                         String rarity, String family, Integer maxLevel, List<Ability> abilities, long observedAt, String source) {
    public enum State { UNKNOWN, NONE, KNOWN }
    /** slot 0–2; null values unknown; locked when the pet's max level is known and below the slot's unlock (slot 1: 50, slot 2: 90). */
    public record Ability(int slot, Integer type, String name, Integer level, Integer points, boolean locked) {}
    public static final PetSummary UNKNOWN;   // state UNKNOWN, abilities = three unknown slots
    /** null → UNKNOWN; absent TRUE → NONE (observedAt/source kept); otherwise KNOWN with names from defs (defs may be loading). */
    public static PetSummary of(CharacterJournal.PetRecord pet, PetDefinitions defs);
    /** "Legendary pet", "No pet", or null (unknown pet, or a known pet whose rarity is unknown). */
    public String chip();
    /** The pet's name, else "Pet". */
    public String title();
}
```
- Ability name: `PetDefinitions.ability(type)`, else `"Ability #" + type` for a known type, else null. Level/points: null when the record holds -1. Lists are `List.copyOf` (content equality). `abilities` always has 3 entries.
- Locked rule: use `PetFeeding`'s existing unlock rule if it is accessible; otherwise the thresholds above, with a comment naming `PetFeeding` as the source.

**Tests:**
- New `tomato.backend.data.PetDefinitionsTest`: inline XML fixture with two pet objects (hex and decimal types), one object without `<Family>`, one with a blank family → `family()` for each; malformed XML → `unavailable()`; missing file under a temporary root → `unavailable()`; `rarity(0..4)` names and `rarity(5)`, `rarity(-1)`, `rarity(null)` → null; `ability(402)` equals the text `CharacterPetsGUI` uses today (copy the string), `ability(403)` → null; `install` pins and restores `current()`.
- New `tomato.gui.glance.character.PetSummaryTest`: null → `UNKNOWN`; absent → `NONE` with `chip()` "No pet"; a full record → KNOWN with rarity "Rare", family from an installed `PetDefinitions`, three abilities with names, levels and points; -1 values → nulls; max level 30 locks slots 1 and 2, 70 locks slot 2 only, 90 locks none, null locks none; `chip()` null for unknown rarity; `title()` falls back to "Pet"; equal inputs → equal summaries.

**Steps:**
- [ ] Read R2 §1.5, §3, §5 and `PlanningMetadata`.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.backend.data.PetDefinitionsTest" --tests "tomato.gui.glance.character.PetSummaryTest"`; confirm they fail (missing classes).
- [ ] Implement both classes; run the tests; pass.
- [ ] Commit ("Add pet names and a pet summary model").

### Task 3: Shared tile list; gallery, roster and navigation fixes

**Owns:** see the file map. **Depends on:** nothing in P3b.

**Scope:**
1. **`tomato.gui.kit.TileList<T>`** (public), extracted from `CharacterGallery`'s private wrapping list and list model, so the Exalts grid (Task 6) and the Pets gallery (Task 7) reuse it:
   ```java
   public class TileList<T> extends JList<T> {
       public TileList(String name, ListCellRenderer<? super T> renderer, Function<T, String> key, Function<T, String> accessibleName);
       /** EDT: shows items; an item whose key equals the one at the same position updates in place (one change event, no scroll jump). */
       public void setItems(List<T> items);
       public List<T> items();
       /** Enter, Space or double-click on an item runs the action; a single click only selects. */
       public void onOpen(Consumer<T> action);
   }
   ```
   `HORIZONTAL_WRAP`, fixed cell size from the renderer's preferred size, preferred size driven by the available width (wraps to fewer columns when narrow; the page scrolls, the list does not), focus ring and accessible names per item, and keyboard behavior exactly as the P3a gallery. `CharacterGallery` uses it for both the cards and the Graveyard with no visible change; all existing gallery tests keep passing unchanged.
2. **Unavailable vs no match (finding 2).** The gallery says the characters are unavailable only while the journal is unreadable (`journal.readable() == false`); a failed save with no visible cards reads "No characters match" (filtered) or "No characters yet" (nothing saved). Pass an explicit `unreadable` flag through `RosterViews.Source` instead of inferring it from `storageProblem()`. The storage warning banner moves out of the card area (above it) so it stays visible when an empty state replaces the cards.
3. **One empty message (finding 9).** With nothing to show, exactly one "nothing here" message is visible: hide the `character-summary` text when nothing is saved, and show the footer guidance (`status`) only in the Table view.
4. **Back brings the Roster tab forward (finding 6).** `CharactersRouteTarget.restoreState` reveals the Roster tab (`view.reveal()`, the same path routes use) before restoring the list or sheet.
5. **Remembered death tab test (finding 7)** and **real focus test (finding 8)** in `CharacterRosterViewTest` (see Tests).
6. **Preference restore (finding 11).** The two P3a focus tests in `ui.WorkspaceUiTest` save and restore `ux.archive.characters-live-roster` and `ui.tabs.character` (restore after draining the event queue).
7. **Isolated evidence (finding 10).** A seam lets tests give the Characters page its own view-state store: `CharacterPanelGUI(TomatoData data, SheetContext context, ViewStateStore viewState)` (the existing constructors delegate with `ViewStateStore.application()`), and a package-private or test-visible static in `TomatoGUI` selects the store the workspace uses (default `ViewStateStore.application()`). `ui.CharactersEvidenceTest` uses an in-memory store and asserts the `character-view-state` banner is not showing in every capture.

**Tests:**
- New `tomato.gui.kit.TileListTest`: wraps to 3/2/1 columns as the width shrinks; in-place update keeps selection and scroll when keys are equal and fires one change; Enter/Space/double-click open, a single click only selects; accessible name per item.
- `CharacterGalleryTest`: add beside: a failed save with no cards shows "No characters match"/"No characters yet" and not "unavailable"; an unreadable journal shows "unavailable"; the storage banner stays visible over an empty state. Update the 4-argument call if the `Source` shape changes (**replace**, reason: the unreadable flag is explicit).
- `RosterViewsTest`: add beside: the blocked-save fixture reports no-match/empty, not unavailable.
- `CharacterRosterViewTest`: add `aRememberedDeathTabOpensThroughShowSheetWithoutAnExplicitTab` (a dead character's saved `death` tab is selected when `showSheet(key, null)` opens it; opening an alive character does not overwrite the remembered tab) and `backFromTheSheetMovesRealKeyboardFocus` (a shown frame; waits until the actual focus owner is the expected component, not only `focusTarget()`). Keep the existing `backFocuses…` test (add beside).
- `CharactersRouteTargetTest`: add `backFromAnotherCharactersTabBringsTheRosterForward` (open a sheet, select the Exalts tab, Back → Roster selected, sheet or list restored).
- `ui.CharactersEvidenceTest`: add beside the banner assertion described in item 7.
- The empty-message rule: add a test in the gallery or `CharacterJournalGuiTest`-style class you own that counts visible "nothing here" components (exactly 1) for an empty journal in Gallery and Table views.

**Steps:**
- [ ] Read R3 §6 (gallery pattern) and R5 items 2, 6–11.
- [ ] Extract `TileList` test-first (`GRADLE test --tests "tomato.gui.kit.TileListTest"` fails, then passes); switch `CharacterGallery` over; run `GRADLE test --tests "tomato.gui.glance.character.*"` (all existing gallery tests pass unchanged).
- [ ] Write the failing tests for items 2–7; implement; run `GRADLE test --tests "tomato.gui.kit.*" --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.*" --tests "ui.WorkspaceUiTest" --tests "ui.CharactersEvidenceTest" --tests "tomato.gui.chat.ShellHookIntegrationTest"`; all pass.
- [ ] Commit ("Extract a shared painted tile list from the gallery", "Show one honest empty state in the Characters gallery", "Bring the Roster tab forward on Back and isolate Characters evidence").

### Task 4: Sheet presenter and tab hardening

**Owns:** see the file map. **Depends on:** nothing in P3b.

**Scope (deferred presenter/model/Build findings):**
1. **Failures are logged and never lost.** `SheetPresenter` catches `Throwable` from a build (rethrowing nothing on the EDT), logs it once with its stack trace through the application's existing error log helper (find the one other presenters or `TomatoData` use; do not print to stdout), and reports it in the sheet: `CharacterSheet.failed(Throwable)` replaces `failed(RuntimeException)`. After a failure the next refresh retries, as today.
2. **Testable generation guard.** A package-private constructor `SheetPresenter(CharacterSheet sheet, SheetContext context, java.util.concurrent.Executor worker)`; the existing constructor delegates with the shared single-thread "character-sheet" executor. A new unit test drives a manual executor that runs two queued builds in reverse order and proves only the newest result for the key still shown applies. The P3a late-result test in `CharacterSheetTest` is **replaced** by this test (reason: it could not fail with a FIFO worker).
3. **Skip unchanged sections while playing.** `OverviewTab` refills the Analyst stat table only when `SheetModel.stats` (or the display mode) changed, not on every model; `GearTab.analyst(...)` re-renders the Analyst slot table only when the record's slots or the definitions changed. The header still updates every rebuild.
4. **The sheet's clock.** `OverviewTab`'s vault-staleness check uses `SheetContext.clock()` (pass a `LongSupplier` in), not `System.currentTimeMillis()`.
5. **Tier labels in the model.** `SheetModel.Gear` gains `List<String> tiers` (28 entries, `""` for none/unknown) computed in `SheetModelBuilder` from the `RosterDefinitions` passed to the build (`ItemTiers.label(RosterDefinitions.Item)` or the equivalent pure overload). `OverviewTab` and `GearTab` read labels from the model and never call the global `RosterDefinitions.current()` on the EDT. Because the presenter's token includes the definitions' identity, labels appear when definitions finish loading.
6. **Failed-build wording.** A failed build no longer shows the Build card as "Loading…": `BuildTab` gets `failed()` with the title "Build unavailable" and one line "The character sheet could not be built. It retries automatically.", used by the presenter's failure path.

**Tests:**
- `SheetModelBuilderTest`: add `tierLabelsComeFromTheDefinitionsPassedToTheBuild` (labels for equipped items with a fixture `RosterDefinitions`; all `""` with empty definitions).
- `OverviewTabTest`: add `theAnalystStatTableIsNotRefilledWhenOnlyTheIdentityChanged` (two models differing only in `identity.lastSeen`: the table model fires no row events on the second apply) and `vaultStalenessUsesTheSheetClock` (a fixture clock 25 h after the count dims it; the system clock is irrelevant).
- `GearTabTest`: add `tierLabelsFollowTheModel` and `theAnalystSlotTableIsNotRerenderedForAnUnchangedRecord`.
- `BuildTabTest`: add `aFailedBuildSaysUnavailableNotLoading`.
- `CharacterSheetTest`: **replace** the late-result test with `onlyTheNewestBuildForTheShownKeyApplies` (manual executor); add `anErrorInABuildIsReportedAndRetried` (a definitions supplier that throws an `Error` once: the sheet shows the failure banner, the log receives the stack trace, and the next refresh applies a model).

**Steps:**
- [ ] Read R1 §2–4, §6, §9 (a)–(f), §10.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.gui.glance.character.*"`; confirm the new tests fail for the expected reasons.
- [ ] Implement items 1–6. Update every `SheetModel.Gear` construction site.
- [ ] Run `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.*" --tests "tomato.gui.chat.ShellHookIntegrationTest" --tests "ui.CharactersEvidenceTest"`; all pass.
- [ ] Commit ("Log and retry failed sheet builds; test the generation guard", "Keep Analyst sheet tables stable and compute tier labels off the EDT").

---

## Wave B

Starts from the integration branch after all Wave A tasks are merged and the coordinator's focused run passes.

### Task 5: Sheet Pet tab, Overview pet card, Home pet chip

**Owns:** see the file map. **Depends on:** Task 2 (`PetDefinitions`, `PetSummary`), Task 4 (sheet hardening).

**Model and presenter:**
- `SheetModel` gains a non-null component `PetSummary pet`, placed after `exalts`: `SheetModel(String key, Identity identity, Stats stats, Gear gear, Exalts exalts, PetSummary pet, Death death, Live live)`.
- `SheetModelBuilder.build(...)` gains a `PetDefinitions pets` parameter (the public overload used by the presenter and the package-private test overload); `pet = PetSummary.of(record.pet, pets)`.
- `SheetPresenter`: the token includes `PetDefinitions.current()` (identity, like `PlanningMetadata`), the build passes it, `show()` feeds the Pet tab and the Overview card; `times()` re-reads the Pet tab's "Observed …" age.
- `CharacterSheet`: registers `.add("pet", "Pet", slot("pet", …))` and `.add("fame", "Fame", slot("fame", …))` right after `exalts` (the Fame slot is an empty panel until Task 8 sets it). A package-private `openTab(String id)` shows (explicit navigation) and selects a tab; the Overview pet card uses it. A package-private `selectedTab()` returns the selected tab id (Task 8 uses it to read fame only while the Fame tab shows).

**`PetTab`** (`character-pet`), `apply(PetSummary)`; `null` (loading) shows nothing:
- KNOWN: sprite (`Sprites.sprite(skin, 40)`, the placeholder when skin is unknown) `character-pet-sprite`; name `character-pet-name` (`title()`); rarity chip `character-pet-rarity` (NEUTRAL tone; hidden when rarity unknown); family `character-pet-family` ("Family: Canine", "Family: —" with tooltip `PetDefinitions` status when unknown); max level `character-pet-max` ("Max level 70", or "Max level —"); for each slot i: `character-pet-ability-name-i` (name or "—"), `character-pet-ability-bar-i` (`StatBar.set(level, maxLevel)`), `character-pet-ability-level-i` ("Level 45 / 70", "Level —", or "Locked" when locked); observed line `character-pet-observed` ("Observed <ago> · <source>").
- Feeding estimate drawer: `Collapsible("character-pet-feeding", "Feeding estimate", …, false)` with a feed-power field `character-pet-feed-power` (default 500, positive integers only; invalid input shows a rose helper line and no estimates) and per slot `character-pet-estimate-i`: "Next level ≈ N items · ≈ F fame · To max ≈ M items · ≈ G fame" from `PetFeeding.estimate(...)`; unknown inputs and locked slots say so; a caption says estimates use local formulas and change nothing in game.
- NONE: `EmptyState("No pet", "This character had no pet equipped when capture last read the character list.")` named `character-pet-none`.
- UNKNOWN: `EmptyState("Pet not captured yet", "Pet details arrive when capture reads your character list in the Pet Yard or the Daily Quest Room.")` named `character-pet-empty`. (Check the wording against R2 §2.2/§2.3; if the character list is read elsewhere too, name the real places.)
- KNOWN content `character-pet-content` is hidden in the NONE/UNKNOWN states and vice versa.

**Overview pet card:** a third card in the Overview's card grid, `character-overview-pet`, titled "Pet": sprite, title, rarity chip, and one line of the three abilities ("Heal 45 · Magic Heal 30 · Electric —"); NONE → "No pet"; UNKNOWN → "—" with the tooltip "Pet details arrive when capture reads your character list". Whole-card open (`Card.onOpen("Open pet", …)`) calls `openTab("pet")`. Its age text (if any) joins the Overview's unchanged-section skip.

**Home pet chip:** `HomeModel.Hero` gains `String petChip` (null = hidden; update the hand-written `equals`/`hashCode` and every constructor site: R4 §7 lists them). `LiveHomeSources.hero` reads the hero's journal record (for a live or last-known hero `journal.characterCopy(snapshot.journalKey())`; for the saved fallback the record already in hand) on the refresh thread and sets `petChip = PetSummary.of(record.pet, PetDefinitions.current()).chip()`. `HeroCard` shows a `Chip` named `home-hero-pet` (NEUTRAL tone) between the exalts chip and the last-seen chip; hidden when `petChip` is null. The hero's rebuild token already includes the journal revision; add `PetDefinitions.current()` identity if the token otherwise would not move when names load.

**Tests:**
- New `tomato.gui.glance.character.PetTabTest`: KNOWN full pet (names, bars `StatBar` values, locked slot text, observed line); NONE; UNKNOWN; null shows neither empty state; the feeding drawer computes the same numbers as `PetFeeding.estimate` for a fixture pet and rejects feed power 0 and "abc".
- `SheetModelBuilderTest`: add `petComesFromTheRecordWithNames` (installed `PetDefinitions`), `noPetAndUnknownPetStayDistinct`.
- `OverviewTabTest`: add `thePetCardShowsKnownNoneAndUnknown` and `thePetCardOpensThePetTab` (a callback or a real sheet).
- `CharacterSheetTest`: **replace** the tab count/order assertions (reason: the sheet gains its Pet and Fame tabs; Simple 8→10, Analyst 9→11, and Snapshot evidence's index moves by two). Adjust indices exactly; do not drop any tab id from the checks.
- `CharacterTabsTest`: **replace** the saved-order assertions the same way (a pre-P3b saved order gets `pet` and `fame` appended at the end).
- `CharacterJournalLayoutTest`: if its tab loop fails on the new tabs, **add** cases for `pet` (the unknown empty state reachable) and `fame` (the placeholder panel) — add beside.
- `HeroCardTest` / `HomeModelBuilderTest`: add `thePetChipShowsRarityNoPetOrNothing` (Legendary → "Legendary pet"; absent → "No pet"; unknown or no rarity → hidden) and a live-hero case reading the journal record by the snapshot's key.

**Steps:**
- [ ] Read R1 §1–6 and §12, R2 §8, R4 §5 and §7.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.glance.home.*" --tests "tomato.gui.character.*"`; confirm failures.
- [ ] Implement; run the same tests plus `tomato.gui.chat.ShellHookIntegrationTest`, `ui.HomeEvidenceTest`, `ui.CharactersEvidenceTest`; all pass.
- [ ] Commit ("Add the Pet tab and the Overview pet card to the character sheet", "Show the hero's pet rarity on Home").

### Task 6: Account Exalts grid

**Owns:** see the file map. **Depends on:** Task 1 (loot boost overload), Task 3 (`TileList`).

**Model (pure, off the EDT):**
```java
/** The Characters › Exalts grid for one account. */
record AccountExalts(String account, List<Choice> accounts, List<Tile> tiles, Integer headerBoost, String headerClass,
                     String headerBasis, int fullyExalted, int observed) {
    /** An account the selector offers: key and label (the account's saved name, else "Account " + the first 6 key characters). */
    record Choice(String key, String label) {}
    /** One observed class: tiers per stat in canonical order (0–5); lootBoost null = unknown. */
    record Tile(int classId, String className, int total, int lowest, List<Integer> tiers, Integer lootBoost, long seenAt) {}
}
final class AccountExaltsBuilder {
    static AccountExalts build(List<CharacterJournal.AccountRecord> accounts, List<CharacterJournal.CharacterRecord> records,
                               String preferredAccount, LiveCharacter.Snapshot live,
                               IntFunction<int[]> weaponClasses, IntFunction<String> className);
}
```
- `accounts` offered: every account with at least one saved exalt count; the shown account is `preferredAccount` when it has counts, else the first offered. `tiles`: one per class id in the shown account's `exalts`, ordered by class id; `tiers`, `total`, `lowest` as `SheetModelBuilder.exalts` computes them (reuse it). `lootBoost` = `RealmCharacter.exaltLootBonus(account.exalts, group)` when `weaponClasses.apply(classId)` is non-null, else null. `fullyExalted` = tiles with `lowest == 5`; `observed` = tiles.
- Header boost: the class in game when `live` is on the shown account (`headerBasis` "in game"), else the class of the shown account's most recently played character (`headerBasis` "last played"); `headerClass` its name; `headerBoost` its loot boost (null when unknown or no such class). No account → an empty model.

**View `ExaltsGrid`** (public, package `tomato.gui.glance.character`, constructor `public ExaltsGrid(SheetContext context)`, named `character-exalts-grid`), hosted by `CharacterPanelGUI` as the `exalts` tab in place of `journal.exaltPanel()`; `CharacterJournalGUI`'s old exalt table, `exaltPanel()` and its notes are removed.
- Header: `StatTile("Loot boost")` (`tile-loot-boost`): "+15%" with subline "Wizard · in game" / "Wizard · last played", "—" with the tooltip "Needs saved exalts for every class that shares this class's weapon, and class data from the selected game assets"; `StatTile("Fully exalted")` (`tile-fully-exalted`): count with subline "of N observed classes".
- Account selector `character-exalts-account` (`JComboBox`), visible only with more than one offered account.
- Tiles: `TileList<AccountExalts.Tile>` named `character-exalt-tiles` with `ExaltTileRenderer`: class sprite (34 px), class name, "Total 312", a 5-pip lowest-tier meter, an 8-cell heat strip (tier 0 `CONTROL`, 1–4 blended toward `ACCENT`, 5 `GOOD`), and "+15% loot" or "Loot —". Accessible name: "<Class>: 312 completions, lowest tier 3 of 5, loot boost 15%" (or "loot boost unknown").
- Empty: `EmptyState("No exalt progress yet", "Exalt progress arrives when capture reads your character list.")` named `character-exalts-grid-empty`. While the first build is pending, show neither tiles nor the empty state.
- Drill-down: opening a tile swaps (in place, `CardLayout`) to a detail with `KitButton.ghost("‹ Exalts")` `character-exalts-back`, a class title `character-exalts-class`, and an `ExaltsTab` constructed with the name prefix `account` (so its names are `account-exalts-*` and `account-exalt-*-i`), fed `SheetModelBuilder.exalts(classId, account, dungeons)`. Back (button, or Escape inside the detail) returns to the grid with the same tile selected and focused.
- Refresh: while showing, once a second compare a token (journal revision, `RosterDefinitions.current()` and `PlanningMetadata.current()` identity, the live revision); on change rebuild on a daemon thread "character-exalts" and apply on the EDT only the newest result.
- `ExaltsTab`: add a constructor `ExaltsTab(String prefix)` whose component names replace the leading `character` with `prefix`; `ExaltsTab()` keeps today's names.

**Tests:**
- New `AccountExaltsBuilderTest`: tiles ordered by class, tiers/total/lowest per tile, loot boost from explicit weapon groups, a null group → null boost, header in game vs last played, the preferred account vs the first offered, one account → a single choice, no counts → empty model, fully exalted count.
- New `ExaltTileRendererTest`: the painted lines/record for a tile (as `CharacterCardRendererTest` does), unknown boost text, accessible name.
- New `ExaltsGridTest` (fixture `ExaltFixtures`): header tiles, selector visibility with one and two accounts, empty state, drill-down and Back restore selection and focus, the detail's `account-exalt-pips-i` values, and that the sheet's `character-exalt-*` names are not duplicated by the grid.
- `ExaltsTabTest`: add beside: `aPrefixRenamesEveryComponent`.
- `CharacterJournalGuiTest`: **replace** the `exaltPanel()` screenshot step (reason: the table moves to the Exalts grid; its evidence capture moves to Task 10).

**Steps:**
- [ ] Read R3 §1–4, §6, §7.4.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.*"`; confirm failures.
- [ ] Implement; run the same plus `tomato.gui.chat.ShellHookIntegrationTest`, `ui.CharactersEvidenceTest`; all pass.
- [ ] Commit ("Replace the Exalts table with an account Exalts grid and class drill-down").

### Task 7: Pets gallery

**Owns:** see the file map. **Depends on:** Task 2, Task 3.

**Keep:** the class `CharacterPetsGUI`, its constructor `CharacterPetsGUI(TomatoData)`, its static `INSTANCE`, `addPet`, `clearPets`; component names `pet-page-scroll`, `pet-list-scroll`, `pet-capture-context`, `pet-feed-power`; the feeding calculator's controls, texts and accessible names (R2 §6.1–6.2); `PetFeeding`'s formulas. Switch its ability names to `PetDefinitions.ability`.

**Model (pure, `tomato.gui.character.PetGalleryModel`):**
```java
record PetCard(String key, PetSummary pet, List<String> equippedBy, boolean inYard, long seenAt) {}
static List<PetCard> build(List<CharacterJournal.CharacterRecord> records, String account,
                           List<CharacterJournal.PetRecord> yardPets, PetDefinitions defs);
```
- Equipped: the account's records with a known pet (`pet != null`, not absent, instance id non-null), grouped by instance id; the newest `observedAt` supplies the values; `equippedBy` lists "Wizard #7" labels (class name + character id) of every record carrying it, dead characters included with " (dead)".
- Yard: pets from `ProgressionData` for the same account while in the Pet Yard, converted to `PetRecord` the way `TomatoData.yardPetRecord` does; a yard pet with an equipped pet's instance id merges into that card (newer values win, `inYard` true); a yard pet without an instance id gets its own card keyed by its object id.
- Order: yard pets first, then equipped; within each by max level descending, then name. Card key: `"pet:" + instanceId` or `"object:" + objectId`.
- Account: Home's current-account rule (as Task 6).

**View:** `CharacterPetsGUI` shows
- the context line (`pet-capture-context`, kept) naming the account and whether capture is running;
- a `TileList<PetCard>` named `pet-cards` with `PetCardRenderer`: sprite (skin, 34 px), title, rarity chip, family, three thin ability bars (level/max, "—" track when unknown), and one footer line: "In the Pet Yard now", "Equipped by Wizard #7", or both;
- `EmptyState("No pets yet", "Enter the Pet Yard to see your pets.")` named `pet-empty` (keeps today's wording);
- the feeding calculator inside `Collapsible("pets-feeding", "Feeding calculator", …, false)`; it computes for the selected card's pet (the first card when none is selected), with today's per-ability estimate text.
- Refresh: the existing 500 ms `ProgressionData` poll stays for the yard; journal reads happen off the EDT (a daemon thread "character-pets") when the journal revision changes, and the EDT applies the newest model.

**Tests:**
- New `PetGalleryModelTest` (fixture `PetFixtures`): de-duplication by instance id across characters (newest values), dead character label, yard merge, yard-only pet, yard pet without instance id, another account's pets excluded, absent and null pets excluded, ordering.
- New `PetCardRendererTest`: painted lines for known/unknown values and the footer.
- `PetFeedingFormTest` and `PetLayoutEvidenceTest`: **replace** where needed, reason "the feeding calculator is now in a collapsed drawer": expand `pets-feeding` first, then keep every existing assertion on names, texts and estimates. The empty-state text assertion keeps passing unchanged.
- `AccountMetadataTest` and `CharacterPublicationTest` keep passing unchanged.

**Steps:**
- [ ] Read R2 in full.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.gui.character.*" --tests "tomato.backend.data.AccountMetadataTest" --tests "tomato.backend.data.CharacterPublicationTest"`; confirm failures.
- [ ] Implement; run the same; all pass.
- [ ] Commit ("Turn the Pets tab into a pets gallery with the feeding calculator in a drawer").

---

## Wave C

Starts from the integration branch after all Wave B tasks are merged and the coordinator's focused run passes.

### Task 8: Fame tab

**Owns:** see the file map. **Depends on:** Task 1 (fame sample account), Task 5 (the `fame` slot).

**Reader `FameHistory`** (package-private; all methods off the EDT):
```java
final class FameHistory {
    FameHistory(java.util.function.Supplier<tomato.history.SessionStore> store);   // production: AppHistory::store
    /** Samples of exactly (account, characterId), oldest first, grouped by session; finished sessions are cached by session id. */
    Series read(String account, int characterId);
    record Point(long time, long fame) {}
    record Session(String id, List<Point> points) {}
    /** untagged: this character id's samples that have no account (not shown); unreadable: sessions skipped. */
    record Series(List<Session> sessions, int untagged, int unreadable) {}
}
```
Reads the `fame` module through `SessionStore.catalog()` and `store.read(...)` as `HomeArchive` does; skips and counts unreadable sessions; the current session (`store.currentId()`) is never cached. A null store → empty series.

**Model `FameModel`** (record, pure) built from a `Series`, the sheet's record and the in-game snapshot:
- `fame` tile: live `characterFame()` when this character is in game (known, "Live"); else the record's saved fame as stale ("as of <ago>"); unknown otherwise.
- `perHour` tile: Home's rule (only increases count; divided by that session's own reading time; shown only with ≥ 10 minutes of readings) for the newest session that qualifies; an estimate ("≈ 1,240") with subline "this session" or "session of <date>"; unknown with the reason "Needs 10 minutes of fame readings in one session".
- `gained` tile: total increases across the shown sessions, subline "over N sessions"; unknown when there are no samples.
- chart points per session; `untagged` and `unreadable` counts. If sample fame is derived from experience (check `Entity.fame`), label the chart and tiles as estimates where they come from samples.

**View:** `FameTab` (`character-fame`): `StatTile`s "Fame" (`tile-fame`), "Fame / hour" (`tile-fame-hour`), "Recorded gain" (`tile-recorded-gain`); `FameChart` (`character-fame-chart`, painted with `Tokens`: one polyline per session with gaps between sessions, first/last date and min/max fame labels, an accessible description "Fame from A to B over N sessions"); caption `character-fame-untagged` ("N older readings have no recorded account and are not shown", hidden at 0) and `character-fame-unreadable` ("M saved sessions could not be read", hidden at 0); `EmptyState("No fame history for this character yet", "Fame history records while you play this character with capture on.")` `character-fame-empty`; nothing while loading.

**`FamePresenter`**: owns a daemon thread "character-fame"; `open(String key)` parses the key (`[0-9a-f]{64}:[0-9]+`) and reads; `refresh(boolean showing)` re-reads at most every 30 s while the Fame tab is showing; results carry their key and apply on the EDT only for the key still shown. `SheetPresenter` creates it (with `AppHistory::store`), calls `sheet.setTab("fame", …)`, forwards `open` and `refresh`, and passes the in-game snapshot for the live tile.

**Tests:**
- New `FameHistoryTest` (fixture `FameFixtures` writing a temporary `SessionStore`): exact account match only; another account's same character id excluded; untagged counted; an unreadable session skipped and counted; finished sessions read once (count reads through a spy store or file access), the current session re-read.
- New `FameModelTest`: live vs stale vs unknown fame; per-hour rule at 9 and 10 minutes and with decreases; gained across sessions; empty series.
- New `FameTabTest`: tiles, captions visibility, empty state, chart accessible description, and that a result for another key is dropped.

**Steps:**
- [ ] Read R4 in full.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.gui.glance.character.*"`; confirm failures.
- [ ] Implement; run `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.history.*" --tests "tomato.gui.character.*"`; all pass.
- [ ] Commit ("Add the Fame tab with exact per-character fame history").

### Task 9: Goals as cards

**Owns:** see the file map. **Depends on:** Task 4 (sheet), Task 5 (tab registration already in place).

**Model `GoalCardsModel`** (pure):
```java
record GoalCard(String key, Kind kind, String title, String target, Integer current, Integer goal, Integer remaining,
                String remainingText, String state, boolean reconfirm, String earnIn, long updatedAt) {
    enum Kind { STAT, EXALT }
}
static List<GoalCard> build(CharacterJournal.CharacterRecord record, PlanData.AccountPlan plan, CharacterJournal.AccountRecord account,
                            RosterDefinitions defs, PlanningMetadata metadata);
```
- Stat goals: `plan.characterGoals` whose `characterKey` equals the record's key; title "<Stat> → <target> base"; `current` = base, `goal` = target; `remaining`/`state`/`reconfirm` from `CharacterGoals.character(...)`; `remainingText` "N potions to go" / "Complete" / the unknown state text.
- Exalt goals: `plan.exaltGoals` whose `classId` equals the record's class; title "<Stat> exalt tier <n>"; `current` = the account's completions for that stat (null unknown), `goal` = `PlanningMetadata.threshold(tier)`; from `CharacterGoals.exalt(...)`; `earnIn` from `metadata.dungeons(stat)` ("" when unmapped).
- Order: stat goals in canonical stat order, then exalt goals; no other goals.

**View:** the Goals tab becomes a panel `character-goals`: a header `character-goals-title` ("Goals for Wizard #7"); cards in a wrapping grid (`Card`s named `character-goal-<key>`): title, a progress bar (`StatBar.set(current, goal)`, not drawn when `remaining` is null), `remainingText`, a state chip (GOOD for "Complete", NEUTRAL otherwise), a WARN "Reconfirm target" chip when `reconfirm`, and "Earn in: …" for exalt goals; `EmptyState("No goals for this character", "Add goals under Manage goals.")` `character-goals-empty`; then the existing `CharacterPlanningPanel` (unchanged behavior, names `planning-0`…`planning-9`): in Analyst mode shown expanded below the cards; in Simple mode inside `Collapsible("character-goals-manage", "Manage goals", …, false)`. The cards refresh from `CharacterSheet.shown()` when the journal revision, the plan store's revision or the definitions change, reading `context.plans().snapshot(record.account)` for the sheet's account only.

**Tests:**
- New `GoalCardsModelTest`: only this character's stat goals and this class's exalt goals; unknown base or cap; target above cap; complete; exalt remaining and earn-in; reconfirm on metadata change; order.
- New `GoalCardsTest`: cards and chips, no bar for unknown remaining, empty state, Simple (collapsed manage section) vs Analyst (panel shown).
- `CharacterPlanningPanelTest`, `CharacterWaveFourEvidenceTest`, `CharacterJournalLayoutTest`: where they reach `planning-*` through the sheet, **add** a step that makes the manage section visible (Analyst mode or expanding `collapsible-character-goals-manage`), then keep every existing assertion (reason: the panel moved under the cards). Direct `CharacterPlanningPanel` tests stay unchanged.
- `CharacterSheetTest`: add beside: the Goals tab shows cards for its own character only.

**Steps:**
- [ ] Read R5 "Goals" sections and R1 §4.2.
- [ ] Write the failing tests; run `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.*"`; confirm failures.
- [ ] Implement; run the same plus `ui.CharactersEvidenceTest`; all pass.
- [ ] Commit ("Show this character's goals as cards with progress").

---

## Wave D

### Task 10: Evidence, docs, validation record, final suite and JAR smoke

**Owns:** see the file map. **Depends on:** Tasks 1–9 merged.

- [ ] **Evidence.** Extend `ui.CharactersEvidenceTest` (isolated view-state store from Task 3) with captures in `build/p3b-t10/ui-test/screenshots/redesign-p3b-characters/`: Exalts grid (populated with two accounts and the selector; empty), a class detail, Pets gallery (equipped only; with yard pets; empty), the feeding drawer open, the sheet Pet tab (known; no pet; unknown), the Overview with the pet card, the Fame tab (populated with an untagged caption; empty), the Goals tab (cards in Simple; cards plus panel in Analyst; empty). Each at 1240×800 font 13 Simple; the grid, gallery, Pet, Fame and Goals also at 680×520 font 18 Analyst. Extend `ui.HomeEvidenceTest` with the hero showing a pet chip. Assert no `character-view-state` banner and no horizontal scrollbar in each capture. Extend `CharacterFixtures` with pets, exalts for two accounts and fame fixtures as needed.
- [ ] **Docs.** `docs/CHARACTERS.md`: the Exalts grid (per-class loot boost, drill-down), the Pets gallery (equipped pets always, yard pets only while in the yard), the Pet and Fame tabs (exact per-account fame; older readings excluded and counted), Goals cards and "Manage goals"; `docs/SESSION-HISTORY.md`: fame samples now carry the hashed account key (the same key the character journal uses; no raw account id); README feature list if it names Characters features; the roadmap (P3b row and status), the handoff (next steps), `docs/UX-CHECKPOINT.json` (the `redesign` block).
- [ ] **Validation record** `docs/superpowers/plans/2026-09-27-p3b-validation.md`: implementation method, per-task focused results (from the coordinator's review notes), baseline and final full-suite totals, evidence list and reviewer, JAR smoke, deferred scope.
- [ ] **Final full suite and JAR** (coordinator): `GRADLE test shadowJar`; totals from the XML results; new failures versus the baseline must be fixed. Isolated `java -jar … --help` from an empty folder exits 0.
- [ ] Commit ("Document P3b Characters and add its validation record", "Record P3b validation results").

---

## Local validation

| Check | Command | Record |
|---|---|---|
| Baseline full suite on `04a61d4` plus tracking docs (`31d5c77`; coordinator, before Wave A) | `GRADLE test` in `build/p3b-baseline` | **Recorded:** 1319 tests, 5 failures, 0 errors, 5 skipped. Pre-existing on Linux/Xvfb (environment, not P3b): `StatisticsArchiveNativeTest.actualLootFactoryFiltersBeforePagingAndRestoresNamedOccurrenceSelectionThroughExportFailure` (unreachable details rectangle), `QuestConsistencyTest.nameTypesDialogScrollsEditorsAndKeyboardCancelThenOkPreserveMeaning` (window focus without a window manager), `ChatFiltersTest.editorSavesRulesAndRendersAlongsideIgnoredDesktopAndCompactViews`, `ChatConsistencyTest.nativeFilterDialogAt460By360OuterMinimumKeepsEveryEditorAndActionReachable` (dialog reachability), `PreferencesStoreTest.groupedUpdateIsOneGenerationAndPreservesFileReaderWriterRoundTripAndUnknownKeys` (run without `LC_ALL=C.UTF-8`: POSIX default charset). |
| Task 1–9 focused tests | each task's commands | pass counts per task (coordinator review notes) |
| Wave merges | the union of the wave's focused commands on the integration branch | pass counts |
| Evidence (Task 10) | `GRADLE test --tests "ui.CharactersEvidenceTest" --tests "ui.HomeEvidenceTest"` | captures reviewed by the coordinator; findings |
| Final full suite and JAR (Task 10) | `GRADLE test shadowJar` | totals; no new failures versus baseline |
| JAR smoke | isolated `java -jar … --help` | exit 0 |

## Deferred scope

- Pets not equipped by any character are not remembered after leaving the Pet Yard (user decision); a later journal version could persist them.
- Fame readings recorded before P3b have no account and are never attributed to a character.
- The Build card follows a character switch within the sheet's 1 s refresh (no live-character listener).
- While playing, the Analyst "Full slot details" dialog's "Snapshot updated" line can lag until the slots, the character or the definitions change (Task 4 skips re-rendering an unchanged slot table); P6 builds the details when the dialog opens (`CharacterEquipmentPanel`).
- Fame readers other than Home and the Fame tab (`LootArchiveClient.readFame`, `StatisticsArchiveAdapter`, `HistoricalStatistics`) still group by the bare character id; P5/P6 moves them to the account key.
- P5: the `LiveHomeSources` reprojection test checks map identity only (handoff §6).
- P6: every handoff §6 "P6 de-duplication" and "P6 hardening" item, and the numeric page API replacement.

## Cross-task notes

- **Journal keys** are `accountKey + ":" + characterId` with a 64-hex SHA-256 account key; a fame sample's `account` uses the same key.
- **Sheet tab ids:** overview, gear, exalts, pet, fame, build, goals, notes, evidence (Analyst), death (conditional). `CharacterJournalGUI.SHEET_TABS` (the legacy saved index 0–6) is not extended.
- **Workers:** "character-sheet" (sheet models, Task 4 makes it injectable), "character-exalts" (Task 6), "character-pets" (Task 7), "character-fame" (Task 8), "home-refresh" (Home). Each applies only its newest result on the EDT.
- **Current account** (Tasks 6, 7): the live snapshot's account, else the last known snapshot's, else `journal.mostRecentCharacter().account`; null → the empty state.
- **Component names added in P3b:** `character-pet*`, `character-overview-pet`, `home-hero-pet`, `character-exalts-grid`, `character-exalt-tiles`, `character-exalts-{account,back,class,grid-empty}`, `account-exalts-*`, `account-exalt-*-i`, `tile-loot-boost`, `tile-fully-exalted`, `pet-cards`, `pet-empty`, `pets-feeding` (toggle `collapsible-pets-feeding`), `character-fame*`, `tile-fame`, `tile-fame-hour`, `tile-recorded-gain`, `character-goals*`, `character-goal-<key>`, `character-goals-manage` (toggle `collapsible-character-goals-manage`), `character-pet-feeding` (toggle `collapsible-character-pet-feeding`).
- **Preferences added in P3b:** `ui.collapse.pets-feeding`, `ui.collapse.character-pet-feeding`, `ui.collapse.character-goals-manage`. Tests that toggle them restore them.
