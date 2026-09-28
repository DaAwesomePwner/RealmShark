# P3b validation and coverage

Base: P3a PR #22 merged as `04a61d4` (including the Codex pet-merge fixes `508d1d1`) and verified before P3b began.
P3b is one PR from the integration branch `claude/redesign-handoff-next-steps-edrr7w`; the plan is
`docs/superpowers/plans/2026-09-27-p3b-characters.md`.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass:
keep it below as diagnostic history and record the rerun that passed.

## Implementation method

P3b followed a **contract plan**: the plan fixed the decisions, file ownership, names, signatures, behavior and the tests
each task had to add, and left the code to the implementers. It ran in four sequential waves of parallel implementer
subagents, one fresh subagent per task, each in its own isolated git worktree with its own Gradle build directory and
project cache, working test-first (failing tests recorded before the code). The coordinator (Claude) reviewed every task's
diff against the plan before merging it into the integration branch, and ran the union of the wave's focused tests on the
integration branch before the next wave started.

- **Wave A** (parallel): Task 1 journal and history hardening, fame provenance and the loot boost over saved counts;
  Task 2 pet names and pet summaries; Task 3 the shared painted tile list and the gallery, roster and navigation fixes;
  Task 4 sheet presenter and tab hardening.
- **Wave B** (parallel, after A): Task 5 the sheet's Pet tab, the Overview pet card and Home's pet chip; Task 6 the account
  Exalts grid; Task 7 the Pets gallery.
- **Wave C** (parallel, after B): Task 8 the Fame tab; Task 9 Goals as cards.
- **Wave D**: Task 10 evidence, docs and this record; the coordinator runs the final full suite and the JAR smoke.

The review sent fix rounds for three tasks; each landed as its own commit before the merge:

- Task 1: `0a9d755` — a `RuntimeException` while saving is a failed save that retries, not a silently cancelled saver; and
  the live loot boost again returns 35% for a fully exalted account when the assets give no weapon groups.
- Task 3: `29a50c4` — Back returns to the Characters tab the user left from (the Roster tab comes forward only when it was
  in front when the state was captured).
- Task 6: `9fdddb8` — 35% for every class of a fully exalted account, and a warn banner (`character-exalts-failed`) instead
  of a silent failure when an Exalts build fails.

Each task's own focused runs (RED and GREEN) are in its implementer report and the coordinator's review notes; the table
below records the wave totals measured on the integration branch.

## Coverage

- Task 1: explicit save-failure flag; the complete "no pet" contradiction check; strict parsing of journal v5 fields; one
  `PET_NONE` constant; `journal.v4.bak` recovery text; `RealmCharacter.exaltLootBonus(Map, int[])`; fame samples carry
  the hashed account key (`fame-latest` keyed by account and character); Home's Today series by account.
- Task 2: `PetDefinitions` (families from `xml/pets.xml`, rarity and ability names) and the `PetSummary` view model.
- Task 3: `TileList`; one honest empty state in the gallery; Back brings the Roster tab forward; isolated Characters
  evidence (the view-state store seam).
- Task 4: failed sheet builds logged and retried; a testable generation guard; stable Analyst tables; tier labels in the
  model; the sheet clock; "Build unavailable".
- Task 5: Sheet › Pet, the Overview pet card, the Pet and Fame tab slots, and Home's hero pet chip.
- Task 6: Characters › Exalts as an account grid with per-class loot boost, account selector and class drill-down.
- Task 7: Characters › Pets as a gallery of equipped and Pet Yard pets, with the feeding calculator in a drawer.
- Task 8: Sheet › Fame, exact by account and character, on its own "character-fame" thread.
- Task 9: Sheet › Goals as cards with progress, over Manage goals (expanded in Analyst, collapsed in Simple).
- Task 10: evidence, `docs/CHARACTERS.md`, `docs/SESSION-HISTORY.md`, `README.md` and this record.

## Local validation

JDK 17 and Gradle 7.6.4 on Linux under Xvfb (`LC_ALL=C.UTF-8`), isolated build directories per task
(`build/p3b-tN`, `build/p3b-tN-cache`), synthetic fixtures only; no live capture and no bridge deliveries.

| Check | Command | Record |
|---|---|---|
| Baseline full suite on `04a61d4` plus the tracking docs (`31d5c77`; coordinator, before Wave A) | `GRADLE test` in `build/p3b-baseline` | tests / failures / errors / skipped: 1319 / 5 / 0 / 5. The five failures are pre-existing Linux/Xvfb environment failures, not P3b: `StatisticsArchiveNativeTest.actualLootFactoryFiltersBeforePagingAndRestoresNamedOccurrenceSelectionThroughExportFailure` (unreachable details rectangle), `QuestConsistencyTest.nameTypesDialogScrollsEditorsAndKeyboardCancelThenOkPreserveMeaning` (window focus without a window manager), `ChatFiltersTest.editorSavesRulesAndRendersAlongsideIgnoredDesktopAndCompactViews` and `ChatConsistencyTest.nativeFilterDialogAt460By360OuterMinimumKeepsEveryEditorAndActionReachable` (dialog reachability), `PreferencesStoreTest.groupedUpdateIsOneGenerationAndPreservesFileReaderWriterRoundTripAndUnknownKeys` (run without `LC_ALL=C.UTF-8`: POSIX default charset) |
| Tasks 1–9 focused tests | each task's commands (plan) | passed when implemented and after each fix round; per-task counts in the implementer reports and the coordinator's review notes |
| Wave A merge (Tasks 1–4, integration `86b2699`) | the union of the wave's focused commands | 101 test classes, 590 tests, 0 failures |
| Wave B merge (Tasks 5–7, integration `5db70c9`) | the union of the wave's focused commands | 107 test classes, 642 tests, 0 failures |
| Wave C merge (Tasks 8–9, integration `1b3e19a`) | the union of the wave's focused commands | 115 test classes, 687 tests, 0 failures |
| Evidence (Task 10) | `GRADLE test --tests "ui.CharactersEvidenceTest" --tests "ui.HomeEvidenceTest"` | 12 tests (9 + 3), 0 failures, 0 errors, 0 skipped; 23 P3b screenshots in `build/p3b-t10/ui-test/screenshots/redesign-p3b-characters/` (the 21 P3a captures regenerate in `redesign-p3a-characters/`). Classes that share `CharacterFixtures` (`RosterViewsTest`, `CharacterCardRendererTest`, `CharacterSheetTest`, `ShellHookIntegrationTest`, `tomato.gui.glance.home.*`): 15 classes, 129 tests, 0 failures. Findings under "Evidence" below |
| Final full suite and JAR (Task 10, coordinator) | `GRADLE test shadowJar` | _to record_ (tests / failures / errors / skipped; new failures versus the baseline) |
| JAR smoke (Task 10, coordinator) | isolated `java -jar … --help` from an empty folder | _to record_ (exit code) |

## Evidence

Captured by `ui.CharactersEvidenceTest` (the real workspace with an in-memory view-state store) and `ui.HomeEvidenceTest`
(Home from live sources) from synthetic fixtures: the `CharacterFixtures` evidence journal (a second account with saved
exalts, and two equipped pets, one of them also recorded earlier on a dead character), two Pet Yard pets, synthetic weapon groups and dungeon mapping, pet names from a
synthetic `pets.xml`, two saved fame sessions with three readings that have no account, and goals pinned in the shared goals
store (restored after the test). Every capture asserts that the `character-view-state` banner is not showing and that no
scroll pane shows a horizontal bar or cuts its content sideways; a data table that scrolls its own columns (Manage goals'
table in Analyst) is listed in the test output instead. A deliberate mutation run confirmed both guards fail when they
should. Reviewer: the Task 10 implementer, by reading every PNG; the coordinator reviews the captures and these findings.

| Capture (`p3b-…`) | What it shows |
|---|---|
| `exalts-grid-1240-13-simple` | Eight class tiles in class order; the **Account** selector (two accounts); header "+0%, Wizard · in game" (the Wizard has counts below 5) and "2 of 8 observed classes"; per-class boosts +25%, +20%, +15%, +0%, and "Loot —" for the Archer (no weapon group) |
| `exalts-grid-680-18-analyst` | One tile per row; selector and header tiles whole; the page scrolls vertically only |
| `exalts-class-1240-13-simple` | The Priest's detail after Enter: "‹ Exalts" focused, total 515 and lowest tier 4/5, eight rows with pips, "Earn in: Fixture Vault · Second Vault" for Life and "—" for unmapped stats |
| `exalts-grid-empty-1240-13-simple` | "No exalt progress yet"; no header tiles |
| `pets-equipped-1240-13-simple` | Two cards: the Wizard's Legendary Canine (also carried earlier by the dead Necromancer) and the Archer's Rare Feline; locked slot reads "Locked" |
| `pets-yard-1240-13-simple` | Three cards, Pet Yard pets first; the Archer's pet merged with its yard reading (Heal 51); context "2 in the Pet Yard now" |
| `pets-yard-680-18-analyst` | One card per row, whole |
| `pets-feeding-1240-13-simple` | The drawer open, estimating for the first card's pet, with the calculator's existing wording, evidence lines and the locked slot labeled |
| `pets-empty-1240-13-simple` | "No pets yet" with the context line "No account yet" |
| `sheet-pet-1240-13-simple` | Sample pet, Legendary, "Family: Canine", "Max level 90", three ability bars, "Observed just now · Character list", the Feeding estimate drawer collapsed |
| `sheet-pet-680-18-analyst` | The tab strip wraps to two rows; the Pet content is whole |
| `sheet-pet-none-1240-13-simple` | "No pet" (the Priest's list entry reported none) |
| `sheet-pet-unknown-1240-13-simple` | "Pet not captured yet" with where pet details arrive |
| `sheet-overview-pet-1240-13-simple` | The Pet card third in the Overview row: name, Legendary chip, "Heal 90 · Magic heal 72 · Electric 40" |
| `sheet-fame-1240-13-simple` | Fame 1,234 "Live"; "≈ 130" per hour for the newest qualifying session; "≈ +234" over 2 sessions; the chart with ≈ axis labels and dates; "3 older readings have no recorded account and are not shown" |
| `sheet-fame-680-18-analyst` | Three tiles across and the chart; see finding 1 |
| `sheet-fame-empty-1240-13-simple` | Saved fame "5,400 (stale), as of 1 h ago", "—" rate and gain, "No fame history for this character yet" |
| `sheet-goals-1240-13-simple` | "Goals for Wizard #101": three stat and three exalt cards with bars, remaining text, state chips and "Earn in"; Manage goals collapsed |
| `sheet-goals-1240-13-analyst` | The same cards over the expanded Manage goals panel |
| `sheet-goals-680-18-analyst` | Two cards per row; the goal table scrolls its own columns (1215 px in a 542 px viewport) |
| `sheet-goals-empty-1240-13-simple` | "Goals for Warrior #102", "No goals for this character", Manage goals collapsed |
| `home-hero-pet-1240-13-simple` | The live hero's chips "7/8 maxed", "Exalts 19/40", "Legendary pet" (from its journal record) |
| `home-hero-pet-680-18-analyst` | The same chips whole at font 18 |

Findings (none fixed in Task 10, which changes no main code):

1. **Truncated subline (minor defect).** At 680×520, font 18, the Fame tab's "Fame / hour" tile cuts its subline to
   "session of 2026-…" (`p3b-sheet-fame-680-18-analyst.png`): `FameTab` keeps three tiles across (`responsiveGrid(3, 170)`)
   at about 180 px each.
2. **One-line pet card footer.** A pet card paints one footer line and elides the rest, so a card that is both in the Pet
   Yard and equipped always loses "Equipped by …", and a pet carried by two characters loses the second name
   (`p3b-pets-yard-1240-13-simple.png`, `p3b-pets-equipped-1240-13-simple.png`). The tooltip and the accessible name keep
   the full text; this is the renderer's intended ellipsis, as on character cards.
3. **Home hero font after a late first model (pre-existing, P2).** `HeroCard` puts its content into the tree only when the
   first model arrives, so a font change made while the hero is still loading or empty misses that content until the next
   font refresh. The evidence test re-applies the font after the model arrives; the first run showed the meta line and
   chips at the old size.
4. **Observations.** In Analyst mode the Goals tab scrolls inside the sheet page, so two vertical scroll bars show side by
   side. A completed goal card says "Complete" twice (the remaining line and the chip). The empty Pets tab shows two
   messages (the context line and the empty state). Sheet › Pet colors ability bars below the maximum amber (the stat bar's
   "needs" color), while the gallery cards use the accent color. In every capture (P3a too) the test window's title bar
   covers the top of the page heading; this comes from printing the decorated test frame, not from the app.

## Deferred scope

- Pets not equipped by any character are not remembered after leaving the Pet Yard (user decision); a later journal version
  could persist them.
- Fame readings recorded before P3b have no account and are never attributed to a character.
- The Build card follows a character switch within the sheet's 1 s refresh (no live-character listener).
- While playing, the Analyst "Full slot details" dialog's "Snapshot updated" line can lag until the slots, the character or
  the definitions change (Task 4 skips re-rendering an unchanged slot table); P6 builds the details when the dialog opens
  (`CharacterEquipmentPanel`).
- Fame readers other than Home and the Fame tab (`LootArchiveClient.readFame`, `StatisticsArchiveAdapter`,
  `HistoricalStatistics`) still group by the bare character id; P5/P6 moves them to the account key.
- P5: the `LiveHomeSources` reprojection test checks map identity only (handoff §6).
- P6: every handoff §6 "P6 de-duplication" and "P6 hardening" item (including the current-account rule that exists twice,
  in `AccountExaltsBuilder.currentAccount` and `PetGalleryModel.account`), and the numeric page API replacement.
