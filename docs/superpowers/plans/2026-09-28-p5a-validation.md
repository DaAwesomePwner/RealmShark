# P5a validation and coverage

Base: P4 PR #24 merged as `e541874` and verified before P5a began. P5a is one PR from the integration branch
`claude/redesign-handoff-next-steps-edrr7w`; the plan is `docs/superpowers/plans/2026-09-28-p5a-runs.md` (committed as
`f03668b`). P5b (the Runs & DPS page with its tabs, the Live meter move, Recordings, Dungeons) is planned after P5a merges.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass: keep it below as diagnostic
history and record the rerun that passed. Task 9 was implemented in parallel with Task 10 and was not on Task 10's base (`d038127`); the coordinator filled the cells left
for it, the final suite and the JAR smoke after both merged.

## Implementation method

P5a followed a **contract plan**, as P3b and P4 did: the plan fixed the decisions, file ownership, names, signatures, behavior
and the tests each task had to add, and left the code to the implementers. It ran in five waves of implementer subagents, one
fresh subagent per task, each in its own isolated git worktree with its own Gradle build directory and project cache
(`build/p5a-tN`, `build/p5a-tN-cache`), working test-first (failing tests recorded before the code). The coordinator (Claude)
reviewed every task's diff against the plan before merging it into the integration branch, and ran the union of the wave's
focused tests on the integration branch before the next wave started.

- **Wave A** (parallel): Task 1 the combat summary model, builder and reader; Task 2 run facts for the feed (bag names, fame
  gained per run, the shared run-outcome rule, portal sprites in the kit); Task 3 Settings › General with the Combat history
  settings.
- **Wave B** (parallel, after A): Task 4 auto-save on close and on capture stop, full detail, retention pruning, Home's DPS from
  saved records and the storage measurement; Task 5 the feed's view models and off-EDT source; Task 6 the recap's view model
  builder.
- **Wave C** (parallel, after B): Task 7 the Runs page feed UI with the Cards/Table views; Task 8 the recap UI and the kit
  damage chart.
- **Wave D**: Task 9 the `RUN_RECAP` route, Home's Recent runs and the feed's cards opening the recap, Back, and the S4
  click-path test.
- **Wave E**: Task 10 evidence, docs and this record (run in parallel with Task 9 on the Wave C base); then Task 11, a polish
  round for the defects the coordinator took from the evidence review; then the coordinator's final full suite, `shadowJar` and
  the JAR smoke.

**User decisions (2026-09-28):** P5 ships as two PRs (P5a, P5b); the Live meter moves into Runs & DPS in P5b; **Keep full
combat detail** is included and off by default, without the debug packet log, pruned after 30 days by default; combat summaries
are kept forever by default, with 1 year and 90 days offered.

The review sent fix rounds for four tasks, and the coordinator made one wording fix; each landed as its own commit before its
merge:

- Task 1: `d8ed14e` — bosses are grouped by type in the combat record (`Boss.count`, at most 8 types, `bossTypesOmitted`).
- Task 2: `7ba607b` — a fame step is credited to a run only when the reading before it and the run's reading lie in one saved
  capture interval of the session's `runs` coverage; a capture gap, missing or truncated coverage leaves the run's fame unknown.
- Task 5: `3705d84` — one session's unreadable loot, fame or combat records degrade only that session's cards (`LOOT_UNREADABLE`,
  fame unknown, `COMBAT_UNREADABLE`) and are listed in `Page.issues()`; `be27990` — runs of a session without any saved loot
  read `LOOT_NOT_SAVED` (the recap's rule).
- Task 4: `3e4f9ce` — capture stop saves a deep copy of the fight (`Entity.copyForDisplay` through one identity map) and only
  clears recorded damage on the live objects, so the local player, objects, drops and loot bags survive a capture restart in
  the same area; the remainder is a separate recording without a visit link.
- Coordinator: `52c41fb` — the recap uses the feed card's loot summary wording (`RunCardModel.summary`); `f9bd3d9` — the route
  target builds the recap's failed-read reason from `RunRecapView.FAILED_TITLE`, which the recap uses to tell a failed read from
  a run missing from saved history.

Merges on the integration branch: Task 3 `3d38105`, Task 2 `a7c67ba`, Task 1 `625d6a5`, Task 6 `32c5fb4`, Task 5 `303a136`,
the wording fix `52c41fb`, Task 4 `754d1a4`, Task 8 `b77920c`, Task 7 `2812dc6`, Task 9 `e6a7ff5`, Task 10
`cd3e07d`, Task 11 `46666f6` (commit `db19eb3`), the reason fix `f9bd3d9`. Each task's own focused runs (RED and GREEN) are in its implementer report and the coordinator's
review notes; the table below records the wave totals measured on the integration branch.

## Coverage

- Task 1: `CombatRecord` and `CombatDetail` (Gson, schema 1; modules `encounters` and `encounter-detail`, keyed by recording
  ID), `CombatSummaries.build` (the meter's exact totals, window, share and rank; the verified local row only; deaths per row
  only for unique known names; enemies and bosses by type; series of the local row and the top 12; 1 s buckets widened beyond
  30 minutes to at most 1,800 values; hits before the first tick counted and reported), `CombatFacts` (`read` skips bad files
  and newer schemas, `detail`, `longest`, `byVisit`), `SessionStore.readCheckpoint`, `CombatFixtures`. Contributors are those
  with damage above 0 (a verified local player without damage has no row); `windowSeconds` is null for a zero-length window;
  `enteredAt` is nullable (legacy).
- Task 2: `LootFacts.Bag.bag` (bag names), `FameGains` (fame gained per run inside one saved capture interval; the series key
  includes the class, as Home's totals), `RunOutcome` (Completed, Left, In progress, App ended, Unknown; Home uses it and a
  store-closed visit reads App ended), `Portals.spriteId` (Home delegates).
- Task 3: `CombatSettings` (strict keys `combat.keepFullDetail`, `combat.fullDetailDays`, `combat.summaryRetention`; `current()`;
  `onChange` listeners), `GeneralSection` (Combat history group and help text), `SettingsPage.GENERAL` between Notifications and
  Appearance, the search entry `combat.settings`.
- Task 4: `CombatAutosave` (worker "RealmShark combat history": detail, then record; full detail
  `combat-full/<checkpointName(id)>.dps` from `getSaveFile(false)`; started by `AppHistory.start`, closed before the store at
  shutdown; prunes at start and after a Combat history change; preview saves nothing), `TomatoData.captureTerminated` and
  `CapturePublication.terminated` (capture stop closes and saves the open fight), `CombatRetention`, `SessionStore.currentDirectory`,
  `deleteFiles`, public `checkpointName`, Home's DPS from saved records merged with in-memory recordings by ID (the longest
  recording; "—" when its local row is unverified), the Clear DPS Logs tooltip `DpsGUI.CLEAR_LOGS_HELP`,
  `CombatStorageMeasurementTest`.
- Task 5: `RunCardModel`, `RunFeedModel` (day groups and headers), `RunFeedQuery`, `RunFeedSource` (its own adapter over `runs`
  only; exact outcome and map filters; pages of 50 sharing one reference-counted lease; `Page.issues()`, `Page.unplaced()`),
  `RunFixtures`.
- Task 6: `RunRecapModel`, `RunRecapBuilder` (closes a visit its session left open as the readers do; tiles use the longest
  recording and the Damage section the chosen one; the existing missing-link sentences; Timeline keeps at most 500 events).
- Task 7: `RunsPage` (`runs-page`: feed and recap cards; `tableRoutes` brings the Table view forward for RUNS routes with a visit
  or a query and restores the view on Back), `RunFeedView` (Cards/Table with `ui.runs.view`; Simple ⋯ items, Analyst toggle;
  the `run-feed` filter bar with search, outcome and dungeon filters and Refresh; summary and issues lines; one list per day;
  **Load more** is `run-feed-load-more` because `run-feed-more` is the filter bar's ⋯; reads on the "RealmShark run feed"
  worker when first shown and afterwards only when a store stamp changed, checked on show and every 30 s while shown),
  `RunCardRenderer`, test rule `RunsViewRule`. Browse saved history shows the Table view; `closeArchiveWorkspaces` also closes
  `RunsPage`.
- Task 8: `RunRecapView` (header, tiles, six collapsible sections with remembered state, reasons, Analyst-only Evidence),
  `RunDamagePanel` (recording picker only with more than one recording; meter with the local row's accent wash and "(you)";
  damage by source follows the selection), kit `DamageChart` (Simple: You and Top contributors, "Others (top 12)" when fewer
  series were saved than contributors; Analyst: one line per series with a chart-local six-hue palette).
- Task 9 (confirmed against the merge `e6a7ff5`): `Destination.RUN_RECAP` (page 10), `RunsRouteTarget.of` (a plain `RUNS` route
  shows the feed; `RUN_RECAP` accepts only an exact saved visit and is rejected without saved history), `RunsState` (recap,
  visit, recording, view and the Table view's own state), the recap built on the "RealmShark run recap" worker, Home's Recent
  runs and the feed's cards opening the recap with Damage expanded, "‹ Runs" following the Characters precedent, and the S4
  method in `ShellHookIntegrationTest`; `RunsRouteTargetTest` (10 tests). Task 9's own runs: its three test classes 26 tests,
  0 failures; its wider set 367 tests, 2 failures (the known `ChatConsistencyTest.nativeFilterDialog…` and
  `ChatFiltersTest.editorSavesRules…`).
- Task 10: `ui.RunsEvidenceTest` (19 captures), `docs/ACTIVITY.md` (Runs feed, card facts, recap, Table view),
  `docs/DPS-METERS.md` (saved combat history, capture stop, full detail, Clear DPS Logs, what is not saved),
  `docs/SESSION-HISTORY.md` (the three combat modules, retention and pruning, storage figures), `README.md` (Runs row and
  summary sentence; Settings sections) and this record.
- Task 11 (polish, merge `46666f6`): the card's loot summary takes its own line when it does not fit beside the sprites; the
  meter's rank column is as wide as its digits; the recap header uses the card's time and duration formats and never ends a
  wrapped line with a separator; the Loot section adds only notable kinds after "N items in M bags"; slot columns align in Loot
  and Players; one empty state per feed situation; the recording list's selection uses the `SELECTION` roles (4.03:1 before in
  the dark theme, now ≥ 4.5:1 in both); a failed recap read has its own "This run could not be read" state; Settings' size
  wording follows the measurement. Tests in `RunCardRendererTest`, `RunDamagePanelTest`, `RunFeedViewTest`,
  `RunRecapViewTest`, `GeneralSectionTest`; `RunsEvidenceTest` assertions follow the intended changes. Its run: 38 classes,
  224 tests, 0 failures.

## Local validation

JDK 17 and Gradle 7.6.4 on Linux under Xvfb (`LC_ALL=C.UTF-8`), isolated build directories per task, synthetic fixtures only;
no live capture and no bridge deliveries. `GRADLE` abbreviates the plan's command. CI is manual-only (AGENTS.md, 2026-09-26);
these are the local checks.

| Check | Command | Record |
|---|---|---|
| Baseline full suite on `f03668b` (`e541874` plus the plan; coordinator, before Wave A) | `GRADLE test` | 350 test classes, tests / failures / errors / skipped: **1562 / 4 / 0 / 5**. The failures are pre-existing Linux/Xvfb failures, not P5a: `StatisticsArchiveNativeTest.actualLootFactory…` (unreachable details rectangle), `QuestConsistencyTest.nameTypesDialog…` (window focus without a window manager), `ChatFiltersTest.editorSavesRules…` and `ChatConsistencyTest.nativeFilterDialog…` (dialog reachability) |
| Tasks 1–9 focused tests | each task's commands (plan) | passed when implemented and after each fix round, apart from the known Linux/Xvfb failures; per-task counts in the implementer reports and the coordinator's review notes |
| Wave A merge (Tasks 3, 2, 1: `3d38105`, `a7c67ba`, `625d6a5`) | the union of the wave's focused commands (dps, history, runs, stats, Home, kit, settings, shell hook, workspace, shell evidence) | on `625d6a5`: 104 test classes, 483 tests, 1 failure: the known `StatisticsArchiveNativeTest.actualLootFactory…` |
| Wave B merge (Tasks 6, 5, the wording fix, Task 4: `32c5fb4`, `303a136`, `52c41fb`, `754d1a4`) | the union of the wave's focused commands (backend data, history, dps, runs, activity, Home, settings, main menu, stats, kit, capture, shell hook, workspace) | on `754d1a4`: 151 test classes, 742 tests, 1 failure: the known `StatisticsArchiveNativeTest.actualLootFactory…` |
| Wave C merge (Tasks 8, 7: `b77920c`, `2812dc6`) | the union of the wave's focused commands (runs, kit, activity, shell hook, workspace, modern, history, Home, `WaveThreeJourneyTest`, `SetupWorkspaceTest`, `ShellRouteRegistrationTest`) | on `2812dc6`: 80 test classes, 421 tests, **0 failures** |
| Wave D merge (Task 9) | the union of the wave's focused commands (plan: runs, route, chat, `ShellRouteRegistrationTest`, Home, activity) | on `e6a7ff5` (runs, route, chat, `ShellRouteRegistrationTest`, Home, activity, `WaveThreeJourneyTest`, `SetupWorkspaceTest`, modern, kit): 90 test classes, 449 tests, 2 failures: the known `ChatFiltersTest.editorSavesRules…` and `ChatConsistencyTest.nativeFilterDialog…` |
| Storage (Task 4) | `GRADLE test --tests "*CombatStorageMeasurementTest"` | see "Storage measurement" below; all three soft bounds met |
| S4 | `ShellHookIntegrationTest.homeRecentRunAndFeedOpenTheDamageBreakdownOfTheLastCompletedRunForS4` | **met**: 1 click from Home's Recent runs row to the recap with Damage expanded and the meter's local row "Player1 (you)" (the row already shows the run's DPS without a click); Back returns Home; 2 clicks from the sidebar (Runs, then the first completed card, which opens on Enter or double-click, counted as one open like the S2/S3 card actions). The recap applied 19–36 ms after the click in the test |
| Evidence (Task 10, on `d038127` plus the Task 10 commits) | `GRADLE test --tests "ui.RunsEvidenceTest"` | **4 tests, 0 failures, 0 errors, 0 skipped**; 19 screenshots in `build/p5a-t10/ui-test/screenshots/redesign-p5a-runs/`. A local mutation run (test-side, not committed) confirmed the guards: forcing the feed page's horizontal scroll bar failed the sideways check of all four feed captures that show the cards (`p5a-feed-1240-13-simple`, `-680-18-analyst`, `-yesterday-`, `-empty-`), and expecting "Party 7" failed both recap top captures. Task 9 was not on this base: the test opens the Runs page (page 10) for the feed, builds the recap with `RunRecapBuilder` off the EDT and shows it with `RunsPage.setRecap`, `RunRecapView.show` and `RunsPage.showRecap`, expanding Damage as the route will. Findings under "Evidence" below |
| Final full suite and JAR (coordinator) | `GRADLE test shadowJar` | on `46cdcd3` (Tasks 1–11 merged): 373 test classes, **1752 / 4 / 0 / 5**, 5 min 39 s. The four failures are exactly the baseline's (`StatisticsArchiveNativeTest.actualLootFactory…`, `QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`); no new failures. `shadowJar` built `RealmShark-v1.2.3.jar`. Diagnostic history: the first final run on `f9bd3d9` failed 17 tests, 13 of them with `OutOfMemoryError` in the `ui.*` tests (512 MB test heap, 14 min 27 s). The cause was test-only and pre-existing on `main`: disposed `VisualEvidence` frames stayed in `Window.getWindows()`, the next test's theme install re-registered the Party roster's Alt+A mnemonic in Swing's static `KeyboardManager` under the disposed frame, and each such frame kept its workspace and 16 MB tile map alive (about 17 by the end of the `ui.*` tests; found with JFR old-object samples and a throwaway `KeyboardManager` probe). `46cdcd3` detaches the content when `VisualEvidence` releases its frame; the probe then found no frame left. A run of `f9bd3d9` before that also failed `ContentStyleTest.wrappingTextRelayout…` once under load (it passed in the final run) |
| JAR smoke (coordinator) | isolated `java -jar … --help` from an empty folder | exit 0 with the usage text, run from an empty scratch folder with its own `user.home` and `java.io.tmpdir`; the folder held no files afterwards |

## Storage measurement

`CombatStorageMeasurementTest` (Task 4, merge `754d1a4`) builds real summaries with `CombatSummaries.build` from synthetic
`DpsData` (two hits per player per second, incoming hits every 5 s, one death) and writes `CombatFixtures.writeLarge` beside
`HomeHistoryFixture.writeLarge`. Spec §13 O3.

| Fight | Hits | Record | Detail | Bound |
|---|---|---|---|---|
| 150 s dungeon, 8 players, 60 enemies | 2,400 | 1,781 B | 10,651 B | — |
| 600 s dungeon, 8 players, 150 enemies | 9,600 | 1,799 B | 28,115 B | record ≤ 16 KB, detail ≤ 64 KB: met |
| 3,600 s Realm-like fight, 30 players, 2,000 enemies | 216,000 | 4,924 B | 152,029 B | — |

- Large synthetic history (30 sessions × 40 runs, one record and one detail per run): **17.0 MB** on disk, of which `encounters`
  2.1 MB and `encounter-detail` 12.8 MB (runs, loot and fame make up the rest).
- `CombatFacts.read` of all 1,200 records: **19–27 ms** (bound: 250 ms warm, met). `HomeArchive.read` with saved records: 6–7 ms.
- The Wave A review measured a Realm-like 600 s fight with 200 boss objects at 3,627 B per record once bosses were grouped by
  type (at most 8 types).
- The feed's `RunFeedSource.first()` on the large fixture takes about 0.5–0.9 s, almost all of it the archive pin that the Table
  view also pays; the feed reads off the EDT and only when its inputs change.
- Full detail (Settings text, from the P5 storage research): about 14 MB per 100,000 hits.
- Settings › General's help says a summary is "about 20–80 KB"; the measured record plus detail is about 12 KB for a 150 s
  dungeon, 30 KB for 600 s and 157 KB for the one-hour Realm-like fight (evidence finding 4). The docs quote the measured figures.

## Evidence

Captured by `ui.RunsEvidenceTest` in the real workspace (`TomatoGUI.createWorkspace` in preview mode, the app's history store
pointed at the test's own history folder and restored after) from synthetic saved history:

- **Today** (a session that saved its end): Ice Citadel completed (party 4; your row #1 of 4; 3 items; +310 fame), Pirate Cave
  completed (party not observed; no recording; 3 items; +120), Snake Pit left (party 3; a recording without a verified local
  row; 1 item; no fame reading), Lost Halls completed (party 6; exalt progress 1; two recordings, 240 s with your row #2 of 6
  and one death, and 45 s; 10 items in three bags; +510 fame; six inspected players; HP/MP and condition samples; five timeline
  events).
- **A crashed launch today** (never saved its end): Lost Halls left open (App ended), 2 items, +110 fame, no recording.
- **Yesterday**: Lost Halls (#3 of 8, exalt progress 2, +900), Snake Pit (#1 of 3, +150), Pirate Cave left (no loot in a session
  with loot: a known none), Ice Citadel (#2 of 5, +420). **Three days ago**: Snake Pit (#1 of 2), Pirate Cave left.

Combat summaries are real `CombatSummaries` output of synthetic fights (players Alpha…Hotel; the capture's own character is
Bravo, a Wizard; enemies "Synthetic Crawler/Spitter/Warden" and the boss "Synthetic Colossus"); fame readings belong to a
synthetic account key. The JVM's default time zone is set for the test to a fixed offset at which it is mid-afternoon, so
Today and Yesterday hold the fixture's runs whenever it runs; the zone, the format locale, the `ui.runs.view`,
`ui.filters.run-feed.open`, `ui.collapse.run-recap-*` and `combat.*` keys, the archive workspaces' keys and the display mode are
restored after each test. Every capture asserts that no scroll pane shows a horizontal bar or cuts its content sideways (a data
table that scrolls its own columns is listed in the test output instead: the recap's meter at 680 px, 1,170 px in a 552 px
viewport) and the content it is evidence of. Reviewer: the Task 10 implementer, by reading every PNG; the coordinator reviews
the captures and these findings.

| Capture (`p5a-…`) | What it shows |
|---|---|
| `feed-1240-13-simple` | "Saved runs · newest first · 11 runs"; **Today** "5 runs · 3 completed · 1 h 35 m": Lost Halls **App ended** (no recording, 2 items, +110 fame), Lost Halls Completed "Your DPS 477.9 · #2 of 6 · 22%" with its share bar, eight bag-colored wells "+2", "+510 fame · Deaths 1 · Exalt progress +1", Snake Pit Left with the unverified-local reason, Pirate Cave "Party —" with "No combat recording is linked to this run." and +120 fame, Ice Citadel "#1 of 4 · 37%"; the Yesterday header below |
| `feed-680-18-analyst` | The Cards/Table toggle; Today's header and the App ended card at font 18 |
| `feed-yesterday-1240-13-simple` | **Yesterday** "4 runs · 3 completed · 1 h 25 m" (Ice Citadel #2 of 5, Pirate Cave "No loot recorded in this run", Snake Pit #1 of 3, Lost Halls #3 of 8 with "Exalt progress +2") and the older day titled "Friday 25 September" |
| `feed-table-1240-13-simple` | The Table view (chosen from the ⋯ menu) over All Sessions: 11 rows, the details pane and exports, the table 847 px in a 1,001 px viewport |
| `feed-empty-1240-13-simple` | "Saved runs · newest first · 0 runs" and the empty state "No saved runs yet" |
| `recap-1240-13-simple` | "‹ Runs"; Lost Halls, Completed, "Entered … · Observed 26m 0s · Party 6 · Wizard #1", the three links; tiles Your DPS 477.9 (#2 of 6), Damage share 22%, Deaths 1 (All players: 2), Fame +510, Loot 10 (1 UT · 1 ST · 3 potions), Exalt progress +1; Damage open: the picker, the totals line, the chart (You, Top contributors) and the meter's first rows |
| `recap-damage-1240-13-simple` | The chart, the meter (six rows; "Bravo (you)" washed in the accent) and "Damage by source · Bravo (you)": Weapon, Ability, Item effect / proc |
| `recap-picker-1240-13-simple` | The Recording list open: "Recording 1 of 2 · longest · 239.7 s window · 6 players", "Recording 2 of 2 · 44.6 s window · 4 players" (the list is a popup of its own and is drawn over the window capture) |
| `recap-recording-2-1240-13-simple` | The second recording chosen: four rows, "Total 71.1k damage · 44.6 s first-to-last hit window · 4 players"; the tiles keep the longest (#2 of 6) |
| `recap-loot-players-1240-13-simple` | Loot · 10 items in three bags (White, Orange, Purple, with times and kinds) and Players · 6 with four slots each and "Inspect damage …" |
| `recap-resources-timeline-1240-13-simple` | The Resources chart (HP, MP, observed conditions and five condition lanes) and Timeline · 5 events of this run only |
| `recap-680-18-analyst` | The recap's top at font 18: the header facts wrapped, two tiles per row |
| `recap-damage-680-18-analyst` | Analyst's chart: one line per player (Alpha, Bravo (you), Delta, Echo, Foxtrot, Charlie) with a legend |
| `recap-meter-680-18-analyst` | The meter scrolling its own columns (rank, player, damage, part of DPS) and the sources title |
| `recap-evidence-680-18-analyst` | Evidence open: OUTCOME (Completed · Server victory …) and TIMING / COVERAGE |
| `recap-no-recording-1240-13-simple` | Pirate Cave: Your DPS, Damage share and Deaths "—" (reason in the tooltip), Fame +120, Loot 3; Damage "No combat recording is linked to this run."; Players, Resources and Timeline with their one-line reasons |
| `recap-unverified-1240-13-simple` | Snake Pit: "No row is marked as yours. The local player's row was not verified …", one summed chart line, three meter rows without "(you)" |
| `settings-general-1240-13-simple` | Settings › General › Combat history: Keep full combat detail off, "Keep full detail for 30 days" disabled, Keep combat summaries Forever, both help notes |
| `settings-general-680-18-analyst` | The same at font 18, the sections list above the page |

Findings (none fixed in Task 10, which changes no main code):

1. **Loot summary cut on cards with more than eight items (defect, minor).** The card cell reserves room for eight wells and the
   widest summary ("1 UT · 1 ST · 2 potions") but not for the "+N" beside them, so the Lost Halls card reads
   "+2 1 UT · 1 ST · 3 p…" (`p5a-feed-1240-13-simple`). The accessible name keeps the whole summary.
2. **The Table view calls a crashed launch's open run "In progress" (inconsistency).** The card and the recap say **App ended** for
   the crashed session's Lost Halls run; the Table view's Outcome column says "In progress" (`p5a-feed-table-1240-13-simple`).
   The plan kept the archive's wording; "In progress" hours after the app ended is misleading beside the shared outcome rule.
3. **"Top contributors" labels a sum (wording).** In Simple the muted chart line sums every other saved player but is labeled
   "Top contributors", also when every contributor was saved (`p5a-recap-1240-13-simple`); without a verified row the single
   line summing all players is captioned "Top contributors · damage per second" (`p5a-recap-unverified-1240-13-simple`). The plan
   named them "Everyone else" and "All players"; "Top contributors" reads like a ranking.
4. **Settings' summary size does not match the measurement (wording).** "About 20–80 KB each" (`p5a-settings-general-*`) against
   the measured record plus detail of about 12 KB (150 s dungeon), 30 KB (600 s) and 157 KB (one-hour Realm-like fight).
5. **Compact meter: a wide rank column (layout, minor).** At 680×520 font 18 the meter scrolls its columns (known from Wave C), and
   its "#" column takes about 110 px, so only rank, player, damage and part of DPS show (`p5a-recap-meter-680-18-analyst`).
6. **Item slots do not line up between rows (visual, minor).** In Loot and Players each row's slots sit left of a right-aligned
   text of varying width ("1 UT · 1 potion" and "1 potion"; "Inspect damage 186,000" and "83,000"), so the slot columns shift
   from row to row (`p5a-recap-loot-players-1240-13-simple`, `p5a-recap-no-recording-1240-13-simple`).
7. **Time formats differ between card and recap (wording, minor).** Cards say "13:20" and "Yesterday 22:10"; the recap header says
   "Entered 2026-09-28 13:20:00" and bag rows "at 13:30:00" (`p5a-recap-1240-13-simple`).
8. **A wrapped header leaves a dangling separator (compact, minor).** At 680 px font 18 the facts wrap after "Party 6 ·", leaving
   "Wizard #1" alone on the next line (`p5a-recap-680-18-analyst`).
9. **Plain loot repeats itself (wording, minor).** A run whose only item is plain reads "1 item in 1 bag · 1 item" in the Loot
   section (`p5a-recap-unverified-1240-13-simple`).
10. **The empty feed says it twice (minor).** "Saved runs · newest first · 0 runs" above "No saved runs yet"; the empty state's
    title is centered and its body left-aligned (`p5a-feed-empty-1240-13-simple`).
11. **Selected row contrast in the recording list (to confirm).** The open list's selected row is light text on a light violet
    highlight (`p5a-recap-picker-1240-13-simple`): the theme's list selection, pre-existing; worth checking against the spec's
    contrast rule.
12. **Observations.** Two fixed-width cards per row leave about 170 px empty at 1240 px, and one card about 100 px at 680 px (fixed
    cells by design). "Deaths 0" shows on every card with a verified row and no death: a known zero, but it adds a fact to most
    cards. The Resources axis reads "1,560.0s" and the Evidence text's wrapped lines lose their indent (both pre-existing views).
    "Class unknown" in Players and the placeholder sprites come from the test environment having no game assets; the window's
    title bar covers the page heading in every capture (harness artifacts, as in P4).

**Coordinator review.** The coordinator read the captures (the feed at both sizes, the recap top and meter at 680, the recap at
1240) and agrees with the findings. Fixed in Task 11 (merge `46666f6`), each with a test that failed first, and rechecked in
its regenerated captures (`build/p5a-t11/ui-test/screenshots/redesign-p5a-runs/`): **1** (the loot summary is whole on its own
line), **4** (measured sizes in Settings), **5** (a narrow rank column; #, Player, Damage and DPS show at 680 font 18), **6**
(slot columns align), **7** (the card's time and duration formats in the recap), **8** (no dangling separator), **9** (no
repeated item count), **10** (one empty state, no summary line) and **11** (the selection passes 4.5:1 in both themes).
Accepted: **2** (the Table view keeps the archive's own wording by plan; the feed, recap and Home share `RunOutcome`) and **3**
("Top contributors" is honest because only the local row and the top 12 are saved as series). **12** stays as observations
(fixed cells by design; "Deaths 0" is a known zero for a verified row; pre-existing views and harness artifacts). Also fixed
in Task 11 from the Wave D review: a failed recap read has its own title.

## Deferred scope

- **P5b** (planned after P5a merges): the Runs & DPS title, icon and `CustomizableTabs("runs")` (Feed · Dungeons · Live meter ·
  Recordings); the Live meter moved into its tab (the DPS Logger page as a pointer, Alt+8 opens the tab); Recordings (saved
  summaries, this run's recordings, imported and full-detail files, deduplicated by recording ID; opening full detail in the
  meter with an `ObjectInputFilter`); Dungeons cards and the Analyst session comparison and cohorts; Statistics and DPS Logger
  leaving the sidebar (Statistics stays constructed until P6); FilterBars for the DPS meter, encounter library and remaining
  Statistics sub-pages; the live scope row merge, customizable `saved-resource-tabs` and the missing S6 screenshots; the S8
  timing harness; recap section reordering.
- **Not saved:** the fight in progress when the app exits or crashes (no in-progress checkpoints).
- **Deaths** have no time or object ID in the packets, so they are not placed on the chart.
- Evidence findings 1–12 above, until the coordinator's review decides them.
- P6 items unchanged (roadmap).
