# P4 validation and coverage

Base: P3b PR #23 merged as `b559bca` and verified before P4 began. P4 is one PR from the integration branch
`claude/redesign-handoff-next-steps-edrr7w`; the plan is `docs/superpowers/plans/2026-09-28-p4-quests.md` (committed as
`2a13d8a`).

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass: keep it below as diagnostic
history and record the rerun that passed. Cells marked **[Coordinator]** are placeholders the coordinator fills after Wave C
and the final run.

## Implementation method

P4 followed a **contract plan**, as P3b did: the plan fixed the decisions, file ownership, names, signatures, behavior and the
tests each task had to add, and left the code to the implementers. It ran in four sequential waves of implementer subagents,
one fresh subagent per task, each in its own isolated git worktree with its own Gradle build directory and project cache
(`build/p4-tN`, `build/p4-tN-cache`), working test-first (failing tests recorded before the code). The coordinator (Claude)
reviewed every task's diff against the plan before merging it into the integration branch, and ran the union of the wave's
focused tests on the integration branch before the next wave started.

- **Wave A** (parallel): Task 1 the Board's pure models (chest tier, card, groups and summary); Task 2 the Planner restyle with
  the kit `SegmentBar`; Task 3 Home's Quests card telling uncaptured rewards apart from none.
- **Wave B** (after A): Task 4 the Board UI: painted cards, groups, summary line, detail drawer, Cards/Table views, the tab
  titles Board and Planner.
- **Wave C** (after B): Task 5 the Quests routes (Home's Quests card and a plain Quests route open the Board; the
  `plans.quests` search entry opens the Planner through the navigator, so Back works) and the S3 click-path test.
- **Wave D**: Task 6 evidence, docs and this record; the coordinator runs the final full suite and the JAR smoke.

**User decision (2026-09-28):** the expiry countdown is not in P4 (see "Deferred scope"); the Board keeps the raw server
expiration visible in its Analyst details only.

The review sent fix rounds for two tasks, and one test-only fix; each landed as its own commit before its merge:

- Task 1: `f8654eb` — an untiered quest chest reward groups as **Other quest chests** (`OTHER_CHEST`, key `other-chest`), not
  with quests that have no chest; `QuestCardModel` carries the quest's category so type groups key on `type-<category id>`.
- Task 2: `ba241ff` — the release checkbox `quest-plan-release-affected` stays visible beside the plan edits it governs,
  outside the Manual stock drawer.
- Test fix: `e9e9467` (merge `322697b`) — `HomeRefresherTest` moves its clock only after Home leaves Today and waits for the
  session read at the moved clock; the old order raced the refresher.

Merges on the integration branch: Task 3 `3bb2090`, Task 1 `654adbe`, Task 2 `3b0517e`, the test fix `322697b`, Task 4
`e47916b`, Task 5 `362f00f`, Task 6 `ad0ae85`. Each task's own focused runs (RED and
GREEN) are in its implementer report and the coordinator's review notes; the table below records the wave totals measured on
the integration branch.

## Coverage

- Task 1: `QuestTier` (the highest chest tier among a quest's reward names: Mighty, Epic, Standard, Beginner, Other quest
  chests, No quest chest, Rewards not captured); `QuestCardModel` (counted rewards and requirements in first-seen order,
  "not captured" kept apart from empty, badges, "You get" / "Pick 1 of N", the raw expiration verbatim); `QuestBoardModel`
  (groups by chest tier, type label or none in a fixed order, pinned first within a group, the summary text with a stale
  suffix and the no-list text); `QuestFixtures`.
- Task 2: kit `SegmentBar` (reserved accent, covered good, missing warn; an unknown total draws no track); `PlanCardModel` and
  `PlanCardRenderer`; the Planner's Cards view (the **All plans** summary over `QuestPlanning.totals` of every plan, one card
  per plan with up to four requirement rows, "Stock unconfirmed" without a bar), the Table view unchanged, the view switch
  (`ui.quests.plan-view`), the **Manual stock** drawer (`ui.collapse.quest-plan-stock`) with the held values in manual
  wording.
- Task 3: `HomeModel.QuestLine.rewardsKnown`; Home's Quests card reads "Rewards not captured" (muted) for a pinned quest whose
  rewards the list did not include, and keeps an empty strip for a known none.
- Task 4: the Board's Cards view (one `SectionHeader` and `TileList` per group, painted `QuestCardRenderer` cards, the
  `quest-summary` line updated each minute and stale in the warn tone), `quest-group-by` and `quest-pinned-first`, the
  `quest-detail` drawer (full lists, pin, legacy interest, "Add to account plan", and in Analyst the stable ID, server
  category and raw expiration), the Cards/Table switch (`ui.quests.view`), "Name types…" in the Filters drawer, the tab titles
  Board and Planner with their ids unchanged, `QuestGUI.openBoard()`.
- Task 5: `QuestsFocus` and `QuestsRouteTarget` (a plain Quests route and `QuestsFocus.BOARD` open the Board, `PLANNER` the
  Planner; Back restores the tab), the `plans.quests` search entry through the navigator, Home's Quests card landing on the
  Board, and the S3 click-path test (confirmed against the merged Task 5: `QuestsRouteTargetTest`, 7 tests, and the S3 method
  plus the planning search's Back assertions in `ShellHookIntegrationTest`).
- Task 6: `ui.QuestsEvidenceTest`, the Quests capture in `ui.HomeEvidenceTest`, `docs/DAILY-QUESTS.md`, `README.md` and this
  record.

## Local validation

JDK 17 and Gradle 7.6.4 on Linux under Xvfb (`LC_ALL=C.UTF-8`), isolated build directories per task, synthetic fixtures only;
no live capture and no bridge deliveries. `GRADLE` abbreviates the plan's command.

| Check | Command | Record |
|---|---|---|
| Baseline full suite on `2a13d8a` (`b559bca` plus the plan; coordinator, before Wave A) | `GRADLE test` | tests / failures / errors / skipped: **1483 / 5 / 0 / 5**. The failures are pre-existing, not P4: `StatisticsArchiveNativeTest.actualLootFactoryFiltersBeforePagingAndRestoresNamedOccurrenceSelectionThroughExportFailure` (unreachable details rectangle), `QuestConsistencyTest.nameTypesDialogScrollsEditorsAndKeyboardCancelThenOkPreserveMeaning` (window focus without a window manager), `ContentStyleTest.wrappingTextRelayoutSettlesAfterWidthFontAndDocumentChanges` (load-sensitive under the full suite; passes alone), `ChatFiltersTest.editorSavesRulesAndRendersAlongsideIgnoredDesktopAndCompactViews` and `ChatConsistencyTest.nativeFilterDialogAt460By360OuterMinimumKeepsEveryEditorAndActionReachable` (dialog reachability) |
| Tasks 1–4 focused tests | each task's commands (plan) | passed when implemented and after each fix round, except the known `QuestConsistencyTest.nameTypesDialog…` focus failure; per-task counts in the implementer reports and the coordinator's review notes |
| Task 5 focused tests | `GRADLE test --tests "tomato.gui.quest.*" --tests "tomato.gui.chat.*" --tests "tomato.gui.route.*" --tests "tomato.ShellRouteRegistrationTest" --tests "tomato.gui.glance.home.*"` | `tomato.gui.quest.*` + `ShellHookIntegrationTest`: 104 tests, 1 failure (the known `QuestConsistencyTest.nameTypesDialog…`); the wider command: 251 tests, 3 failures, all known (`QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`) |
| Wave A merge (Tasks 3, 1, 2: `3bb2090`, `654adbe`, `3b0517e`) | the union of the wave's focused commands | 51 test classes, 265 tests, 2 failures: the known `QuestConsistencyTest.nameTypesDialog…` focus failure, and a `HomeRefresherTest` window race (the test moved its clock before Home left Today), fixed by the test-only commit `e9e9467` (merge `322697b`) |
| Wave B merge (Task 4, `e47916b`) | the union of the wave's focused commands (quest, kit, planning, Home, shell hook, workspace, modern, filter-bar evidence) | 60 test classes, 317 tests, 1 failure: the known `QuestConsistencyTest.nameTypesDialog…` focus failure without a window manager (the method's behavior is unchanged) |
| Wave C merge (Task 5) | the union of the wave's focused commands | on `362f00f` (quest, chat, route, `ShellRouteRegistrationTest`, Home, kit, planning, `WorkspaceUiTest`): 76 test classes, 347 tests, 3 failures, all known (`QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`) |
| S3 (partial) | `ShellHookIntegrationTest.homeQuestsCardAndBoardShowPinnedQuestsAndRewardsForS3AndBackReturnsHome` | passed in the Wave C merge run and in the full suite — pinned quests and their rewards: 0 clicks on Home, 1 click to the Board (pinned first, reward sprites), Back returns to Home. The "expire today" half of S3 moves with the countdown phase |
| Evidence (Task 6, on `fd26db4` plus the Task 6 commits) | `GRADLE test --tests "ui.QuestsEvidenceTest" --tests "ui.HomeEvidenceTest"` | **7 tests (3 + 4), 0 failures, 0 errors, 0 skipped**; 17 P4 screenshots in `build/p4-t6/ui-test/screenshots/redesign-p4-quests/` (15 from `QuestsEvidenceTest`, 2 from `HomeEvidenceTest`). A local mutation run (not committed) confirmed the guards: forcing the Board page's horizontal scroll bar failed every Board capture's sideways check, and showing the raw expiration in Simple failed the Simple details check. Task 5 was not on this base: the evidence opens the Board and the Planner with `QuestGUI.openBoard()` / `openPlans()` after the Quests route. Findings under "Evidence" below |
| Final full suite and JAR (coordinator) | `GRADLE test shadowJar` | **[Coordinator: source head; tests / failures / errors / skipped; new failures versus the baseline, each investigated; the JAR built]** |
| JAR smoke (coordinator) | isolated `java -jar … --help` from an empty folder | **[Coordinator: exit code; the folder's contents afterwards]** |

## Evidence

Captured by `ui.QuestsEvidenceTest` (the real workspace in preview mode) and `ui.HomeEvidenceTest` (Home from a synthetic model)
from synthetic fixtures: nine quests over `QuestFixtures`' item names (installed as the asset names, plus one synthetic
"Spirit Shard", and one id without a name), categories 5 and 8 labeled Daily and Event in the quest preferences node and 9 left
unlabeled, two quests pinned for a synthetic account from the detail drawer, one raw expiration string, and saved plans of two
synthetic accounts (the account in game with confirmed stock and two reservations; an offline account with stock confirmed
for one item). Asset names, labels, pins, the planning store's plans, the `ui.quests.*` / `ui.collapse.quest-plan-stock` /
`ui.filters.quests.open` / `ui.tabs.quests` keys and the display mode are restored after each test. Every capture asserts
that no scroll pane shows a horizontal bar or cuts its content sideways (a data table that scrolls its own columns is listed
in the test output instead, as in P3b) and the content it is evidence of. Reviewer: the Task 6 implementer, by reading every
PNG; the coordinator reviews the captures and these findings.

| Capture (`p4-…`) | What it shows |
|---|---|
| `board-tier-1240-13-simple` | Tabs **Board** / **Planner**; summary "9 quests · 2 pinned · captured 14 min ago"; groups Mighty, Epic, Standard, Beginner, Other quest chests, No quest chest, Rewards not captured, each with its count; pinned Festival exchange (★, Repeatable, Event chip, "Pick 1 of 5", four slots and "+1") before Mighty haul (Repeatable, ✓ Done); pinned Royal tribute first among the Epic cards |
| `board-tier-680-18-analyst` | One card per row from the first group (scrolled past the header and the filter row, as the user would) |
| `board-type-1240-13-simple` | Groups Daily 4, Event 4, No type label 1; Standard delivery's "+1" requirement overflow and its reward count; Token swap's "None listed by the server" |
| `board-detail-1240-13-simple` | Royal tribute's details above the cards: badges, Daily, Pinned, Unpin quest, Add to account plan, description, "1 × Royal Epic Quest Chest", "10 × Mark of the Forgotten King"; no expiration |
| `board-detail-1240-13-analyst` | The same with "Stable quest ID", "Server category: 5" and "Expiration (raw server value): synthetic-raw-expiration-86399"; the Filters button wraps to its own row (the Wave B note) |
| `board-detail-680-18-analyst` | The lower half of the details at font 18: the lists side by side and the raw expiration wrapped, whole |
| `board-table-1240-13-simple` | The Table view (chosen from the ⋯ menu): 9 rows, the split details, Unpin quest and Add to account plan in the footer; the table scrolls its columns sideways (1,051 px in a 985 px viewport) |
| `board-stale-1240-13-simple` | After capture stops: the summary ends "· stale" in the warn tone; the context reads "Stale / unverified for the current capture"; the cards stay |
| `board-empty-1240-13-simple` | "No quest list captured yet" and the empty state "Plan your next turn-in" |
| `planner-cards-1240-13-simple` | **All plans** (4 plans, "More items needed"): Forgotten King need 10 · reserved 4 · covered 6 · missing 0, Malus need 3 · covered 1 · missing 2, Festival Token need 9 · reserved 2 · covered 2 · missing 5, each with its bar; four plan cards (Royal tribute covered, Festival exchange "Repeats: 3" missing 5, Cultist tribute missing 1, Beginner errand covered); "Verified snapshot available for import/reconfirmation." |
| `planner-cards-680-18-analyst` | The All plans summary at font 18, its rows wrapped over their bars |
| `planner-top-680-18-analyst` | The compact Planner as it opens: the account list, the Cards/Table switch below it, the status; see finding 1 |
| `planner-stock-1240-13-simple` | The Manual stock drawer open: Item ID, Quantity, Manual note, the four actions and the held values "12 (manual) · unallocated 8 · confirmed … · Synthetic count; vault checked manually", "4 (manual) · unallocated 2", "1 (manual) · unallocated 1" |
| `planner-unknown-stock-1240-13-simple` | The offline account: "Unknown — requirements or manual stock unconfirmed"; "need 1 · Stock unconfirmed" rows without a bar beside Malus' bar; Standard delivery's card (three "Stock unconfirmed" rows, one covered, "+1 more"), Token swap "No items required (observed empty)", Unknown loot "Requirements unknown" / "Requirements not captured"; "Offline manual editing; …" |
| `planner-table-1240-13-simple` | The Planner's Table view: four rows, the selected plan's saved details (raw expiration, metadata version, rewards) and totals, the Manual stock drawer collapsed |
| `home-quests-rewards-unknown-1240-13-simple` | Home's Quests card: "3 pinned · 2 repeatable · 0 done"; Oryx's Castle "Rewards not captured"; Pirate Cave (a known none) with no sprites; Epic Shatters with its reward sprite; "Captured 14 min ago" |
| `home-quests-rewards-unknown-680-18-analyst` | The same card at font 18, whole, with its Analyst evidence note ("Expiry countdowns are not shown until the expiry format is confirmed.") |

Findings (none fixed in Task 6, which changes no main code):

1. **Planning account list cut off at 680 px, font 18 (defect).** The account list is as wide as the 64-character hashed
   account key (723 px wanted, 556 px visible), so its right end and its drop-down arrow fall outside the page
   (`p4-planner-top-680-18-analyst`). The list predates P4; the Board's context line already shortens the same key to six
   characters.
2. **Focus falls to the sidebar when the cards hide (to confirm).** After Close handed focus back to a card and the Table view
   was chosen, the focus ring shows on the sidebar's **Chat** item (`p4-board-table-1240-13-simple`, and still in
   `p4-board-stale-1240-13-simple` after switching back): hiding the focused card list moves focus out of the page instead of
   to the table. Seen under Xvfb without a window manager, with the menu item clicked programmatically; worth checking on
   Windows.
3. **The empty Board says it three times (minor).** With nothing captured, the Cards view shows the summary "No quest list
   captured yet", the empty state and, at the page bottom, the Table view's footer line "No quests captured"
   (`p4-board-empty-1240-13-simple`). The Cards view already hides the footer's pin actions.
4. **Compact Board: no card on the first screen (observation).** At 680×520, font 18 the shell header (two button rows), the
   tab strip, the capture context, the summary and the four-row filter area fill the first screen; the first card starts
   below it (the capture scrolls to the first group). In Analyst at 1240×800, font 13 the Filters button wraps to its own row
   (the known Wave B note).
5. **Plan cards cut their readiness text (renderer's ellipsis).** At 1240×800, font 13 a plan card elides QuestPlanning's
   readiness to one line ("Requirements covered by manual s…", "Unknown — requirements or manu…") and long item names ("Mark of
   the Forgotte…"); the leading word survives and the tooltip and accessible name keep the full text. Cards reserve four
   requirement rows, so one-item plans leave most of the card empty (fixed cells by design).
6. **Unknown item names repeat the id (minor).** An item without an asset name reads "Unknown item #9999 (#9999)" in the All
   plans summary (`p4-planner-unknown-stock-1240-13-simple`): the page's name lookup already ends in the id and the Planner
   appends it again.
7. **Type chips repeat the group title (minor).** Grouped by type label, every card in "Daily" carries a "Daily" chip
   (`p4-board-type-1240-13-simple`).
8. **Raw expiration in the Table view's details in Simple (by design).** As recorded in the Wave B outcomes, the Table view
   keeps its split details unchanged, so Simple shows "Expiration (raw server value): …" there
   (`p4-board-table-1240-13-simple`); the Board's cards and drawer show it only in Analyst. Listed for the countdown phase.
9. **Observations.** The Planner table elides its Status column ("Saved requirements; …") while the table has spare width to
   its right (fixed column widths, pre-existing). Home shows nothing for a known-empty reward list (only its accessible name
   says "no rewards listed"), as Task 3 specified. In every capture the test window's title bar covers the top of the page
   heading, and sprites are the kit placeholder (no game assets in the test environment); both are harness artifacts. The
   card selected last keeps its accent border.

**[Coordinator: review of the captures; which findings are fixed, deferred or accepted.]**

## Deferred scope

- **The expiry countdown phase** (user decision, 2026-09-28): the "Copy expiration samples…" diagnostic in the Quests ⋯ menu
  (the distinct raw strings with their counts and the list's receipt time, UTC rounded to the minute), `QuestExpiry.parse`
  for the confirmed formats only, the Board's expiry chip (warn under 6 h), the summary's "N expiring today", Home's
  "expiring soonest" order and countdown, and S3's "expire today" half.
- **Risk for that phase:** `QuestPlanning.sameDefinition` compares `rawExpiration`; if the server value is a countdown, every
  new quest list would mark saved plans stale. Decide the comparison when the format is known. The same phase decides whether
  the Table view's details keep the raw expiration in Simple (finding 8).
- The spec's ✎ manual glyph (P4 keeps `DisplayValue.manual`'s "(manual)").
- Noted in the Wave A review, not fixed in P4: a `Collapsible` can paint one frame expanded before its saved state applies;
  Enter on a focused plan card does not open its editor (double-click and the Table do).
- Evidence findings 1–7 unless the coordinator schedules a polish round.
- P5/P6 items unchanged (roadmap).
