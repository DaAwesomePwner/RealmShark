# P5b validation and coverage

Base: P5a PR #25 merged as `3ab077c` (with its Codex fix `21d896e`) and verified before P5b began. P5b is one PR from the
integration branch `claude/redesign-handoff-next-steps-edrr7w`; the plan is `docs/superpowers/plans/2026-09-28-p5b-runs-dps.md`
(committed as `7f1c8c6`). It finishes P5: page 10 becomes **Runs & DPS** with the tabs Feed · Dungeons · Live meter ·
Recordings, the single DPS meter moves into the Live meter tab, Statistics and DPS Logger leave the sidebar (S7), and S8 gets a
harness and is met.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass: keep it below as diagnostic
history and record the rerun that passed. The coordinator filled the cells it owns after the polish tasks merged.

## Implementation method

P5b followed a **contract plan**, as P3b, P4 and P5a did: the plan fixed the decisions, file ownership, names, signatures,
behavior and the tests each task had to add, and left the code to the implementers. It ran in five waves of implementer
subagents, one fresh subagent per task, each in its own isolated git worktree with its own Gradle build directory and project
cache (`build/p5b-tN`, `build/p5b-tN-cache`), working test-first. The coordinator (Claude) reviewed every task's diff against
the plan before merging it into the integration branch, and ran the union of the wave's focused tests on the integration branch
before the next wave started. Research notes R1 (shell and tabs), R2 (Live meter and Recordings), R3 (Dungeons and Statistics)
and R4 (the S8 baseline, harness design and hazards) gave the implementers the current code verbatim.

- **Wave A** (parallel, 5): Task 1 the Runs & DPS page container and its tab-aware route wrapper; Task 2 the sidebar changes
  and the DPS Logger pointer page; Task 3 the Live meter restyle and its render speed fix; Task 4 the Recordings source and a
  safe `.dps` reader; Task 5 the Dungeons model and source.
- **Wave B** (parallel, 3): Task 6 shell integration (page 10 composition, the meter moved, routes, Alt+8, search, closing);
  Task 7 customizable saved resource tabs and the recap's section order; Task 8 the S6 captures for Timeline, Resources and
  Party.
- **Wave C** (parallel, 3): Task 9 the Live meter filter bar and Analyst-only legacy modes; Task 10 the Recordings tab UI and
  its open actions; Task 11 the Dungeons tab UI and the Analyst analysis view.
- **Wave D** (parallel, 2): Task 12 tab wiring, drill-downs and search; Task 13 the S8 harness.
- **Wave E**: Task 14 evidence, docs and this record; then the coordinator's polish task for the evidence findings, the final
  full suite, `shadowJar` and the JAR smoke, and the PR.

**User decisions (2026-09-28):** P5 ships as two PRs (P5a merged; this is P5b); the Live meter moves into Runs & DPS (the DPS
Logger page becomes a pointer, Alt+8 opens the tab); P5b is one PR and the live scope-row merge moves to P6; Dungeons cards
count finished runs (completion = Completed ÷ (Completed + Left + App ended), labeled "observed"; average duration and best
personal DPS from completed runs only; In progress and Unknown runs counted in the tooltip, never as 0); session comparison and
A/B cohorts are embedded in Dungeons as an Analyst-only Analysis view built on first open; no per-recording delete.

Fix rounds and coordinator commits, each its own commit before its merge:

- Task 4: `d890764` — a coordinator-requested scan of all 205 classes in the admitted packet packages found no deserialization
  hook.
- Task 9: `bfa17a2` — the enemy list tracks its viewport width, so the Boss chip is never clipped (titles truncate with the
  full text in the tooltip).
- Coordinator: `ad2425c` — `HistoricalDpsFilterTest.assertEveryMode` left the static `DpsDisplayOptions.equipmentOption` at
  icons, which failed `DpsFilterBarTest.legacyIsAnAnalystOption…` in the combined Wave C run; it is restored and the Legacy test
  pins its default.

Task commits (merges in brackets): Task 1 `8ea43ba` (`0ae2383`), Task 2 `afc1dab` (`763c1d9`), Task 5 `467cbd3` (`689696c`),
Task 3 `e629bcd` (`e8bca46`), Task 4 `cf5245d` + `d890764` (`82395df`); Task 8 `0c72f5a` (`df1b2fe`), Task 7 `a4b2d3c`
(`e89bd9d`), Task 6 `1220fa7` (`614e9c0`); Task 11 `d43e887` (`0883f61`), Task 9 `fa8b332` + `bfa17a2` (`70842d3`), Task 10
`9a3ff08` (`93ac98b`), `ad2425c`; Task 13 `ecddceb` (`8960e1e`), Task 12 `466b36b` (`a04eccd`); Task 14 on `18ab0bd`. Each
task's own RED and GREEN runs are in its implementer report and the coordinator's review notes; the table below records the
wave totals measured on the integration branch.

## Coverage

- Task 1: `RunsDpsPage` (`runs-dps-page`; `CustomizableTabs("runs")` as `runs-tabs` with the tabs in `RunsTab` order; holders
  `runs-dungeons-slot` and `runs-recordings-slot`; one-row scroll tab layout; opens on the first visible tab), `RunsTab`,
  `RunsFocus` (a dungeon only on Feed), the composite `PageState` (front tab, that tab's owner state) on every page-10 target,
  `tabTarget` and `liveMeterTarget`, `close()` reaching hidden tabs; `CustomizableTabs.contents()` and its client property.
  Accepted: `owner()` refuses a page target; a Feed focus with a dungeon is refused while no hook is set; `setContent` never
  closes what it replaces.
- Task 2: Runs & DPS title and swords icon; Statistics and DPS Logger unlisted (6 core, Advanced (5)); saved layouts drop the
  unlisted IDs; the one-time un-hide of Runs (a hidden `runs` is saved with `dps-logger` beside it, so it happens once without a
  new preference key); `DpsMovedPanel` (`dps-moved`, **Open Live meter**, **Open Recordings**).
- Task 3: one-pass enemy refill (301 renderer calls for a 300-enemy refill, down from 45,451), cached renderer borders, fonts
  and number formats, the true rank prefix, your-row wash, class sprites, enemy cards with a Boss chip and "HP —", the details
  drawer (`dps-details-drawer`, Esc and ×) and the Explore footer.
- Task 4: the allow-list `ObjectInputFilter` on every `.dps` read (proven from real recording streams, plus package rules for
  RealmShark's packet classes; array-length limit), the 64 MB-stack reader thread (a 9 MB, 1,500-player file reads in about
  0.2–0.3 s; a 1 MB stack overflows), `EncounterCatalog.Kind` and `addSaved` (2 kept, the pinned one never evicted, an in-memory
  copy wins), `RecordingItem`, `RecordingsQuery`, `RecordingsSource`.
- Task 5: `DungeonCardModel` (a mergeable tally), `DungeonsModel`, `DungeonsQuery`, `DungeonsSource` with a per-session stamp
  cache, `SessionFacts` extracted from `RunFeedSource` without behavior change; a test checks each card equals the model over
  the feed's own cards.
- Task 6: page 10 is `RunsDpsPage(runsPage, dpsPanel)` with `DungeonListGUI` in Recordings; page 7 is `DpsMovedPanel`;
  `pageOf(ENCOUNTER|RESOURCES)` = 10; every page-10 target wrapped in the old registration order; Alt+8 via `page-7`;
  `browseSavedHistory` brings Feed forward; closing visits `CustomizableTabs.contents()`; search `dps.meter`, `dps.recordings`,
  `statistics.open`; `DpsGUI.onOpenLibrary` replaces the modal library dialog.
- Task 7: `CustomizableTabs("saved-resources")` per render under `saved-resource-tabs` (restore by ID never un-hides); the
  recap's sections reorder by header menu or Ctrl+Shift+Up/Down, persisted in `ui.order.run-recap`.
- Task 8: Timeline, Resources and Party in the S6 matrix (72 captures at the time).
- Task 9: `FilterBar("dps-meter")` in `DpsGUI` (search slot with Rank by; drawer Class, Enemies, Preset, Class colors, View in
  Analyst; chips; the encounter scope; ⋯ Saved resources…, Edit DPS filters…, Open Recordings, Load .dps…); `MeterDpsGUI`
  without its own filter row; Simple hides View and leaves Legacy; saved full detail labeled by `entry.imported()`.
- Task 10: the encounter library as the Recordings tab (Run and Saved columns, `FilterBar("encounter-library")`, Last 30 days
  | All sessions, reads on first show, stamp or revision change and Refresh only, explicit open per kind,
  `RecordingSummaryPanel` via public `RunRecapBuilder.damage`, `RUN_RECAP` with a recording ID, view state saved only when
  changed). Accepted: a fourth callback `onOpenLive`; a pruned row still opens its summary, never the full detail.
- Task 11: `DungeonsView` (FilterBar `dungeons`, one empty state per case, reads on first show and a 30 s stamp poll),
  `DungeonCardRenderer`, `DungeonAnalysis` (saved-only `ArchiveWorkspace` named `dungeon-analysis` over `LootArchiveClient`
  limited to RATES, SESSIONS, COHORTS, COUNTERS, ENEMIES, SOURCES), `ArchiveWorkspace.savedOnly`, `LootQuery.initial`.
- Task 12: the Dungeons tab holds `DungeonsView` with its analysis factory; the Recordings and Dungeons callbacks; the Feed's
  canonical dungeon filter (`RunFeedView.showDungeon`, `RunFeedQuery`); the Statistics banner with **Open Dungeons**; search
  `dungeons.open`; `DungeonCardModel.bestEntered`.
- Task 13: `ShellSwitchTimingTest` and `CombatFixtures.installLive` (S8 below).
- Task 14: `ui.RunsDpsEvidenceTest` (16 captures), the `dps-meter`, `encounter-library` and `dungeons` rows in
  `FilterBarEvidenceTest` (96 captures), `docs/DPS-METERS.md` (the Live meter in Runs & DPS, the restyle, the filter row, Legacy
  as an Analyst option, Recordings and how each kind opens, the safe reader, Alt+8, the pointer page), `docs/ACTIVITY.md` (the
  Runs & DPS tabs, the Dungeons cards and their counting rules, Show runs, the Analysis view, the recap's section order, the
  saved resource tabs), `docs/STATISTICS.md` (out of the sidebar, what remains until P6, where the dungeon views went),
  `docs/SESSION-HISTORY.md`, `docs/UI-REDESIGN.md` (the information architecture after P5), `README.md` and this record.

## Local validation

JDK 17 and Gradle 7.6.4 on Linux under Xvfb (`LC_ALL=C.UTF-8`), isolated build directories per task, synthetic fixtures only;
no live capture and no bridge deliveries. `GRADLE` abbreviates the plan's command. CI is manual-only (AGENTS.md, 2026-09-26);
these are the local checks.

| Check | Command | Record |
|---|---|---|
| Baseline (no new run) | P5a's final suite on `46cdcd3` and the Codex fix's focused run | **1752 / 4 / 0 / 5** (tests / failures / errors / skipped; the four known Linux/Xvfb failures `StatisticsArchiveNativeTest.actualLootFactory…`, `QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`) and **379 / 0** |
| Tasks 1–13 focused tests | each task's commands (plan) | passed when implemented and after each fix round, apart from the known Linux/Xvfb failures; per-task counts in the implementer reports and the coordinator's review notes |
| Wave A merge (Tasks 1, 2, 5, 3, 4) | the union of the wave's focused commands (runs, kit, modern, dps, Home, route, activity, stats, myinfo, history, the shell hook test, the wave-three evidence tests, `ui.WorkspaceShellNavigationTest`, `ui.WorkspaceUiTest`, `ui.ShellRedesignEvidenceTest`) | on Tasks 1–3 and 5: 155 test classes, 777 tests, 1 failure: the known `StatisticsArchiveNativeTest.actualLootFactory…`; dps, history, runs and myinfo again with Task 4: 61 classes, 337 tests, **0 failures** |
| Wave B merge (Tasks 8, 7, 6) | shell hook, route, runs, dps, myinfo, activity, modern, kit, glance, stats, history, `WaveThreeJourneyTest`, `ShellRouteRegistrationTest`, `SetupWorkspaceTest`, all `ui.*` | on `614e9c0`: 182 test classes, 972 tests, 1 failure: the known `StatisticsArchiveNativeTest.actualLootFactory…` |
| Wave C merge (Tasks 11, 9, 10) | the Wave B set with both history packages | on `93ac98b`: 199 test classes, 1070 tests, 2 failures: the known `StatisticsArchiveNativeTest.actualLootFactory…` and `DpsFilterBarTest.legacyIsAnAnalystOption…` (root cause: pre-existing test state, fixed by `ad2425c`); dps, stats and myinfo then 290 tests, only the known failure |
| Wave D merge (Tasks 13, 12) | the Wave C set | on `a04eccd`: 199 test classes, 1080 tests, 1 failure: the known `StatisticsArchiveNativeTest.actualLootFactory…` |
| S8 | `GRADLE test --tests "tomato.gui.ShellSwitchTimingTest"` (alone, twice) | on `a04eccd`: **both runs pass** (2 tests each, about 16 s); numbers under "S8 page switching" below |
| Reads | `GRADLE test --tests "tomato.gui.dps.RecordingsSourceTest" --tests "tomato.gui.runs.DungeonsSourceTest"` | Task 14 on `18ab0bd` plus the evidence commit: 7 + 10 tests, 0 failures; times under "Reads" below |
| S6 | `GRADLE test --tests "tomato.gui.history.FilterBarEvidenceTest"` (alone) | Task 14: **1 test, 0 failures** (three runs); 12 pages × 8 states = 96 captures in `build/p5b-t14/ui-test/screenshots/redesign-p1c/`; one filter row at 1240×800 font 13 with the drawer closed and active chips on every page |
| Evidence (Task 14) | `GRADLE test --tests "ui.RunsDpsEvidenceTest"` (alone) | **5 tests, 0 failures, 0 errors, 0 skipped** (runs 2 and 3); 16 screenshots in `build/p5b-t14/ui-test/screenshots/redesign-p5b-runs-dps/`. Diagnostic history: run 1 failed 3 tests, all test-side (the live row sorts last, so a lookup by row 0 was wrong; the summary panel's title starts with "Summary · "; the live snapshot's enemy copies take their names and the Boss label from the asset catalog, which the test has no entries for) and showed the meter's "Your character data has not arrived" warning, because the fixture had no resolved local character; the fixture now names its enemies and labels the boss through synthetic asset entries and sets the capture's own character, as capture does. A local mutation run (test-side, not committed) confirmed the guards: forcing a horizontal scroll bar on the meter's page failed the sideways check of all four Live meter captures, and expecting "Advanced (4)" failed the sidebar capture. Findings under "Evidence" below |
| Final full suite and JAR | `GRADLE test shadowJar` | on `a42f9fc` (Tasks 1–15 merged): 387 test classes, **1895 / 5 / 0 / 5**, 6 min 23 s. Four failures are exactly the baseline's (`StatisticsArchiveNativeTest.actualLootFactory…`, `QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`). The fifth, `QuestConsistencyTest.nativeCompactRequestRevealsKeyboardTargetsAcrossFontsAndThemes`, is **intermittent and not yet root-caused**: run alone it failed 3 of 15 times at `a42f9fc` and 2 of 11 at `614e9c0` (the Task 6 merge), and passed 17 of 17 on P5a `main` (`3ab077c`) and 11 of 11 at `e89bd9d`. Each failure hits a different geometry assertion after scrolling or focus at 680×520 (summary offscreen, a wrapped line reachable, a spinner field visible); the page's scroll pane is not yet validated at those points in P5a and P5b alike, and the test calls no code Task 6 changed functionally. The investigation continues (see the PR). `shadowJar` built `RealmShark-v1.2.3.jar` |
| JAR smoke | isolated `java -jar … --help` from an empty folder | exit 0 with the usage text, from an empty scratch folder with its own `user.home` and `java.io.tmpdir`; the folder held no files afterwards |

## S8 page switching

Spec §1 S8: switching between core destinations stays within 100 ms p95 on the large synthetic history. `ShellSwitchTimingTest`
(Task 13) builds the real shell over `HomeHistoryFixture.writeLarge(root, 30, 40)` and `CombatFixtures.writeLarge` (1,200
saved runs with combat records) in a visible 1240×800 frame with isolated preferences and history; Loot, Chat, the Runs table
and Recordings are in saved mode over all sessions and settled; a live fight of 1,200 s × 8 players × **300 enemies** (96,000
hits) is republished off the EDT before every Live meter entry, since the meter renders only a new snapshot. It switches among
the core destinations of `NavLayout.core()`, with Runs & DPS entered twice (Feed and Live meter), 3 warm-up rounds then 20 rounds
in a seeded order that never repeats the current page (140 samples); a second test switches among the four Runs & DPS tabs (80
samples). The frame metric is `select` + layout + dirty-region paint in one EDT turn; the longest follow-up EDT event is bounded
too, so work moved to `invokeLater` cannot pass; canaries check that each Live meter sample rendered the snapshot published just
before it (its summary names 300 enemies) and that Loot and Chat show a saved page. Mutations proven by Task 13: undoing Task
3's enemy refill fails it (Live meter p95 158–175 ms), moving the render to `invokeLater` fails the follow-up bound (214 ms), and
skipping the republish fails the canary.

**Before P5b** (R4 baseline on `3ab077c`, five probe runs with the same fight): switching into the DPS page cost **173–197 ms
p50** and **190–360 ms p95** (up to 500 ms max; overall p95 179–210 ms), about 75 % of it in the O(n²) enemy-list rebuild
(45,471 renderer calls for 301 rows). Without a live fight every destination stayed at or below 12.7 ms p95.

**After P5b** (on `a04eccd`, alone, twice; frame p50 / p95 / max in ms):

| Destination | Run 1 | Run 2 |
|---|---|---|
| Home | 4.7 / 6.9 / 7.2 | 4.7 / 9.9 / 10.0 |
| Characters | 6.0 / 10.2 / 12.7 | 5.8 / 9.2 / 19.0 |
| Runs & DPS › Feed | 6.1 / 9.0 / 11.7 | 6.0 / 11.2 / 12.2 |
| Runs & DPS › Live meter | 19.2 / 34.3 / 35.5 | 14.7 / 35.2 / 45.6 |
| Loot | 3.0 / 5.7 / 19.1 | 2.4 / 11.8 / 34.4 |
| Quests | 1.1 / 2.8 / 2.9 | 1.1 / 2.1 / 2.4 |
| Chat | 1.8 / 2.6 / 2.9 | 1.6 / 2.4 / 16.2 |
| All core switches (140) | 4.5 / 22.6 / 35.5 | 4.7 / 19.0 / 45.6 |

| Runs & DPS tab | Run 1 | Run 2 |
|---|---|---|
| Feed | 3.9 / 5.4 / 5.4 | 3.8 / 6.5 / 13.0 |
| Dungeons | 3.1 / 4.2 / 6.5 | 2.7 / 10.8 / 32.2 |
| Live meter | 14.7 / 18.4 / 33.8 | 14.1 / 17.0 / 51.6 |
| Recordings | 1.5 / 2.4 / 4.2 | 1.6 / 5.1 / 11.0 |
| All tab switches (80) | 3.6 / 15.7 / 33.8 | 3.5 / 15.6 / 51.6 |

**Met**: every destination, every tab and the overall p95 are far below 100 ms. The longest follow-up EDT event's p95 was at most
34.6 ms (run 1) and 39.6 ms (run 2), both on Loot, whose saved view arrives through a Swing timer and is rebuilt on every show (R4
H5/H6, not in P5b). Building `CombatMeterData` off the EDT (R4 H3) was not needed. Raw logs are in the coordinator's scratchpad
(`s8-run1.log`, `s8-run2.log`).

## Reads

Both tab sources read off the EDT on their own daemon workers, on first show and afterwards only when their inputs change (S8:
nothing reads saved history synchronously on a page or tab show). Large fixture: 30 sessions × 40 runs with a combat record each.

| Source | Test | Task run (Wave A) | Task 14 run |
|---|---|---|---|
| `RecordingsSource.read` (1,200 records, 30-day and all-session scopes, full-detail presence, dedupe) | `RecordingsSourceTest` | cold 73–100 ms, warm 12–28 ms | cold 68 ms, warm 11 ms (soft bound 250 ms warm) |
| `DungeonsSource.read` (every session's runs, loot and combat records; per-session stamp cache) | `DungeonsSourceTest` | cold about 100 ms, warm about 9 ms | cold 96 ms, warm 7 ms |

A `.dps` read runs on the recording reader thread: a 9 MB, 1,500-player file in about 0.2–0.3 s (Task 4).

## S6 filter rows

`FilterBarEvidenceTest` captures each adopted page with its filters collapsed and open at 1240×800 and 680×520, fonts 13 and 18,
and asserts the drawer's visibility, active chips, and one filter row at 1240×800 font 13 with the drawer closed. After P5b the
matrix holds 12 pages: the Runs table (`runs`), `loot`, `chat`, `keypops`, `characters`, `quests`, Timeline (`timeline`, Types
facet), Resources (`combat`, Outcome facet), Party (`inspect-roster`, a facet chip), and P5b's **`dps-meter`** (a synthetic
encounter with the chip "Player: alp"), **`encounter-library`** (Recordings with "Source: This app run") and **`dungeons`** (the
cards with a search chip). `ui.RunsDpsEvidenceTest` also asserts one row for `dps-meter`, `encounter-library` and `dungeons`
inside the real shell. The Runs feed's `run-feed` row is P5a's (`RunsEvidenceTest`); the Dungeons analysis uses the saved
workspace's own `dungeon-analysis` filter row (`DungeonAnalysisTest`).

**Exempt until P6** (their removal is planned there): the Statistics page's retiring sub-pages, Fame Table, Dungeon Stats, Fame
Graph and Loot › Live log, keep their current controls; `historical-statistics-tabs` (dead in production) is retired in P6.

## S7 core destinations

Six core entries: **Home, Characters, Runs & DPS, Loot, Quests, Chat**; Advanced (5): Party, Key-pops, Timeline, Logging, Bridge
Review; Settings below the list. Asserted by `NavLayoutTest` and `WorkspaceShellLayoutTest` (Task 2) and in the real shell by
`ui.RunsDpsEvidenceTest` (`p5b-sidebar-*`: the visible rows top to bottom are pages 14, 3, 10, 8, 5, 0, "Advanced (5)", and no
Statistics or DPS Logger row, also while the Statistics page shows). Statistics stays reachable by Alt+5, Settings search and the
Dungeons analysis banner; DPS Logger by Alt+8 (which opens the Live meter) and the pointer page.

## Evidence

Captured by `ui.RunsDpsEvidenceTest` in the real workspace (`TomatoGUI.createWorkspace` in preview mode, the app's history store
pointed at the test's own history folder and restored after) from synthetic saved history dated relative to the clock (the JVM's
zone is set for the test to a fixed offset at which it is mid-afternoon, so Today, Yesterday and Recordings' Last 30 days hold
the fixture whenever it runs):

- **Today** (ended 14:20, loot saved): Lost Halls left 11:45 (no recording), Ice Citadel completed 12:00 (3 items in two bags),
  Lost Halls completed 12:40 (22 m, 2 items) and 13:20 (26 m, party 6, 6 items in three bags), each completed run with a verified
  recording; a saved summary without a run link (Sprite World).
- **Yesterday** (no loot saved at all): Ice Citadel completed (its full detail kept) and Snake Pit completed (a recording without a
  verified local row; its full detail pruned).
- **This app run**: a Pirate Cave run without an entry time (Unknown) in the current session; a closed recording in memory (Mad
  Lab, unlinked) and an imported copy of it (`synthetic-copy.dps`, read through the safe reader); a live fight in Lost Halls:
  players Alpha…Foxtrot, the capture's own character Bravo, twelve minions of three types (Spitters without max HP) and the boss
  "Synthetic Colossus" (400,000 HP).

The test has no game assets: the enemies' names and the boss's BOSS label are synthetic asset entries (restored after), player
classes read "Unknown" and sprites are placeholders. Preferences (`ui.tabs.*`, `ui.nav.*`, `ui.filters.*`, `ui.runs.view`,
`ui.dungeons.view`, `ui.order.run-recap`, `combat.*`, the DPS preset name), the archive workspaces' keys, the display mode, the
zone, the format locale and the DPS statics are restored after each test. Every capture asserts that no page scrolls sideways (a
data table that scrolls its own columns, and the meter's unwrapped hit report, are listed on standard output instead; the
Recordings table's sideways scroll is finding 2 and is asserted as it is) and the content it is evidence of. Reviewer: the Task 14
implementer, by reading every PNG; the coordinator reviews the captures and these findings.

| Capture (`p5b-…`) | What it shows |
|---|---|
| `sidebar-1240-13-simple` | Home; the sidebar: Home, Characters, Runs & DPS, Loot, Quests, Chat, "› Advanced (5)" collapsed, Settings at the bottom; no Statistics or DPS Logger row |
| `sidebar-advanced-1240-13-simple` | Advanced expanded: Party, Key-pops, Timeline, Logging, Bridge Review |
| `dps-logger-pointer-1240-13-simple` | Page 7 "DPS Logger": "DPS Logger moved · The live meter and your recordings are now tabs of Runs & DPS." with **Open Live meter** (primary) and **Open Recordings**; no sidebar row selected. Open Live meter then opens Runs & DPS › Live meter and Back returns to the pointer |
| `statistics-banner-1240-13-simple` | Statistics (no sidebar row): the info banner "Dungeon stats, session comparison and cohorts are in Runs & DPS › Dungeons." with **Open Dungeons** (which opens the Dungeons tab), the Fame Graph, Fame Table, Loot and Dungeon Stats tabs below |
| `feed-1240-13-simple` | Runs & DPS with the strip Feed · Dungeons · Live meter · Recordings, Feed in front; "Saved runs · newest first · 6 runs · 1 run without an entry time or visit ID only in the Table view"; **Today** "4 runs · 3 completed · 1 h 23 m": Lost Halls 13:20 (Your DPS 436.2 · #2 of 6 · 20 %, six bag wells, 1 UT · 1 ST · 1 potion), Lost Halls 12:40 (#2 of 4, 2 potions), Ice Citadel 12:00 (#2 of 4), Lost Halls 11:45 Left ("No combat recording is linked to this run.", "No loot recorded in this run"); **Yesterday** "2 runs · 2 completed · 44 m": Snake Pit with the unverified-row reason, Ice Citadel #2 of 5 |
| `live-meter-1240-13-simple` | The Live meter with its nested tabs Damage meters · Resources & buffs; one filter row (Search players, Rank by Damage, Filters; ‹ Live › Go live, Pause this view, ⋯); "Live encounter · Lost Halls · Link: Live" with Open Run / Timeline / Resources / Details; "Lost Halls · LIVE · 13 enemies · 6/6 players · DMG: 194,727", "179.2s first-to-last hit window"; enemy cards All enemies · 13, Synthetic Colossus with the **Boss** chip ("400,000 HP · 89.4k …"), Wardens 6,000 HP, Crawlers 2,000 HP; the table #1 Alpha 51,885 (289.5 DPS, 26.6 %), #2 **Bravo (you)** washed in the accent, #3 Delta … #6 Charlie with class-hued bars; footer "Explore all events… · Select a player first" |
| `live-meter-drawer-1240-13-simple` | Bravo selected: the drawer "Details · Bravo · Unknown ×" with the hit report (Damage 41,556 · Hits 180 · Max hit 416; the share and DPS lines), Explore enabled above it; the table keeps five rows |
| `live-meter-680-18-analyst` | Compact Analyst, scrolled to the meter: the nested tabs, the summary (ellipsized), the enemy list at 130 px ("All ene…", "… Boss", "Synth…": finding 1), the table's #1–#4 in the Player / meter column (it scrolls its columns), the footer |
| `live-meter-drawer-680-18-analyst` | Compact drawer open: three table rows, Explore, "Details · Bravo · Unknown ×" and two lines of the report |
| `recordings-1240-13-simple` | Recordings: one filter row (Search recordings, Filters, Last 30 days · All sessions, ⋯), Open live meter / Load / Save checked / View imported encounter, "8 of 8 recordings shown · last 30 days · 0 checked for export · 0 checked outside filters"; Mad Lab (Captured), Mad Lab (`synthetic-copy.dps`), Sprite World, two Lost Halls, Ice Citadel (today), Snake Pit and Ice Citadel (yesterday) with "Not loaded" entries, and the live row last (finding 4); Run and Saved are right of the viewport (finding 2). The test reads them: "No saved summary (yet)" and "Unlinked" for Mad Lab, "Imported file · same recording as Captured · Mad Lab", "Summary saved" and "Linked · Lost Halls · …" for both Lost Halls rows, "Full detail · N KB" (its size) for yesterday's Ice Citadel, "Full detail pruned (kept 30 days)" for Snake Pit; no path anywhere |
| `recordings-680-18-analyst` | Compact: the filter row, the four buttons on two lines, the two-line summary and one table row in view (finding 6) |
| `recording-summary-1240-13-simple` | Sprite World opened with **Show summary**: "Summary · Sprite World · … 14:00:00 ×", "Summary only: hit detail was not kept (Settings › General › Keep full combat detail)", "Total 116k damage · 89.1 s first-to-last hit window · 4 players", the chart (You, Top contributors), the meter (Alpha 35.1k, Bravo (you) 31k washed, Charlie, Delta; others' Taken "—") and "Damage by source · Bravo (you)": Weapon 20.7k · 66.7 %, Ability 10.3k · 33.3 %; the tab stays Recordings |
| `recordings-empty-1240-13-simple` | Nothing recorded: "0 of 0 recordings shown · last 30 days · …", a blank Live row, "Live capture: Open shows the live meter. It is not an exportable saved encounter." (finding 5) |
| `dungeons-1240-13-simple` | "Saved runs · all sessions · 4 dungeons · 7 runs · most visits first"; **Lost Halls** (3 visits, Completion 67 % observed, Avg 24 m observed, Loot 4.0 per completed run · bags linked to the exact run, Best DPS 436.2 · 13:20, Show runs, Open best run); **Ice Citadel** (2 visits, 100 %, Avg 29 m, "Loot 3.0 per completed run ◐ 1 excluded", Best DPS 391.3 · Yesterday 20:00); **Pirate Cave** (1 visit, Completion — "No finished run yet (Completed, Left or App …", Avg, Loot and Best DPS — "No completed run yet.", Show runs only); **Snake Pit** (1 visit, 100 %, Avg 14 m, Loot — no loot bag saved, Best DPS — row not verified). Lost Halls' best DPS equals the 13:20 feed card's |
| `dungeons-680-18-analyst` | Compact Analyst: the Cards · Analysis switch, the filter row, the summary and the Lost Halls card's top |
| `dungeons-analysis-1240-13-analyst` | Analysis (after **Analyze** on Lost Halls): the banner "The Fame Table and the live loot log stay on the Statistics page." with Open Statistics; the saved workspace's row with the chip "Dungeons: Lost Halls" and All Sessions; Dungeon loot profile · **Session comparison** · Dungeon statistics · Enemy hit events · Loot by source · A/B cohorts; three sessions, today's with 8 items over 3 visits (00:55:00 observed) |

Findings from Task 14 (which changed no main code; the coordinator's review below says which the polish fixed):

1. **Enemy names collapse beside the Boss chip when compact (known, polish list).** At 680×520 font 18 the enemy list is 130 px
   wide in the real shell: the boss card's title gets 90 of its 175 px ("…"), and "All enemies · 13" reads "All ene…"
   (`p5b-live-meter-680-18-analyst`, `-drawer-680-18-analyst`, S6 `p1c-dps-meter-680-18-*`). The chip itself is whole and inside
   the list (asserted).
2. **The Recordings table scrolls its eleven columns at 1240 px (known, polish list).** 1,650 px of columns in a 1,012 px viewport:
   the two new columns, Run and Saved, are out of view at desktop width; at 680 px only Export to Recorded start show
   (`p5b-recordings-1240-13-simple`, `-680-18-analyst`, `-empty-1240-13-simple`). The evidence test asserts it as it is, so that
   assertion changes when the columns fit.
3. **The boss card's subtitle is cut at desktop width too (layout, minor).** At 1240×800 font 13 the enemy list is 230 px, so
   "400,000 HP · 89.4k dmg · 71.9 s" reads "400,000 HP · 89.4k …" beside the chip; the fight window is only in the tooltip
   (`p5b-live-meter-1240-13-simple`). Same family as 1.
4. **The live row sorts last in Recordings (inconsistency).** Under the default Recorded start ↓ order the live row, which has no
   start time, is the last row (view row 8 of 9) with blank cells, and it is the selected row, so the details below describe the
   live capture while the eye is on the top rows (`p5b-recordings-1240-13-simple`). `DungeonListGUI`'s own comment says "The
   first row is the live meter".
5. **An empty Recordings tab says nothing about what fills it (empty state).** A blank Live row and "0 of 0 recordings shown" in
   an otherwise empty table; the Feed and Dungeons tabs each have an empty state that says why nothing shows
   (`p5b-recordings-empty-1240-13-simple`).
6. **Compact Recordings shows one row before scrolling (compact, minor).** At 680×520 font 18 the filter row, the four buttons on
   two lines and the two-line summary leave one table row in view (`p5b-recordings-680-18-analyst`, S6
   `p1c-encounter-library-680-18-*`).
7. **The meter's encounter controls split when they wrap (compact, minor).** At 680×520 font 18 with a saved encounter shown ("1
   of 1"), "‹" stays at the end of the search row while "1 of 1 · › · Go live · Pause this view" wraps to the next line, separating
   the previous button from the position it steps (S6 `p1c-dps-meter-680-18-filters-closed`, `-open`). In the real shell with
   "Live" as the position, the whole group wrapped together (an earlier, unscrolled `p5b-live-meter-680-18-analyst`).
8. **Dungeon card reasons are cut to one line (wording, minor).** "No finished run yet (Completed, Left or App …", "From your best
   completed run; Open best r…", "No loot bag was saved in these completed r…", "The local player's row was not verified in
   the…"; the tooltip and the accessible name keep the full text (`p5b-dungeons-1240-13-simple`).
9. **The meter table scrolls sideways at desktop width (observation, pre-existing).** At 1240 px Player / meter, Class, Damage, DPS
   and Recorded share % show; Hits dealt, Avg hit, Max hit, Taken (est.) and Hits taken need the table's own scroll (1,197 px in a
   746 px viewport); the hit report scrolls its long lines (1,601 px in 735 px). Documented ("Scroll the table horizontally for
   additional columns"), listed for the polish decision.
10. **The Statistics page repeats its view-state row (observation, retiring page).** "Retry view save · Reset saved live view"
    appears twice under the Fame Graph (`p5b-statistics-banner-1240-13-simple`); the page is exempt from polish until P6 removes
    it.
11. **Observations and harness artifacts.** Player classes read "Unknown" and sprites are placeholders (no game assets); the
    window's title bar covers the page heading in every capture (as in P4 and P5a); at 680 px the shell header wraps its buttons
    onto two lines (pre-existing); Recordings keeps the library's Save view state / Reset saved view state buttons under the
    details; the analysis' Session comparison lists sessions with no visit of the chosen dungeon (the Statistics view's behavior,
    unchanged).

**Coordinator review.** The polish ran as two parallel tasks, reviewed on their regenerated captures:
- **Task 15a (Live meter; `43699a7`, `568001b`, merge `8831d4c`)** fixes 1, 3 and 7. The split places its divider at a fixed 0.32
  share until the reader moves it, never below a floor of ten average letters, "…" and card padding (149 px at font 13, 196 px at
  font 18), and gives the table its first column (rank, name, bar and amount) whenever the split allows; a card too narrow for ten
  characters of the name beside the chip folds the marker into its facts ("Boss · 400,000 HP · …", Boss in the warn tone), and
  wide cards keep the chip. At 1240×800 font 13 the list is about 318 px and the boss facts are whole beside the chip. The
  encounter controls (‹ · position · › · Go live · Pause) are one unit that wraps as a whole. Tests that failed first:
  `DpsFilterBarTest.enemyCardsKeepReadableNamesAndFactsBesideTheBossChip`, `…encounterControlsStayTogetherWhenTheyWrap`,
  `MeterRestyleTest.narrowBossCardMovesTheBossMarkerIntoItsFacts`, `DpsPresentationTest.narrowBossCardKeepsTheRendererShapeAndFocusBorder`.
  **Accepted residue:** at 680×520 font 18 the floor still wins, so the meter table's first column is 21–35 px short (the amount
  beside each bar is partly cut and the table scrolls sideways) and "All enemies · 13" reads "All enemie…"; closing it would need
  a smaller name floor, narrower card padding or a narrower Player / meter column.
- **Task 15b (Recordings and Dungeons; `13d46e2`, merge `2632c44`)** fixes 2, 4, 5, 6, 8 and the Save view state buttons of 11.
  Recordings orders its view Export, Dungeon, Recorded start, Run, Saved, Damage, … (model indices unchanged); Simple shows those
  six, which fit 1,012 px at 1240×800 font 13, Analyst all eleven. The live row stays first under every sort and is selected
  until a recording is chosen. An empty state replaces the table ("Reading recordings", "Recordings could not be read" with Try
  again, "No recordings yet", "No recordings match" with Clear filters), with Open live meter still in the header. Save view
  state and Reset saved view state moved into ⋯, Load and Save checked sit beside the status line, and at 680×520 font 18 three
  rows and part of a fourth show. Dungeon card reasons too long for their line show a shorter form that still says why; the
  tooltip and accessible name keep the full text. Saved Recordings widths from the old layout are ignored once (they are now
  tagged with their column layout).
- Coordinator follow-ups: `a5b5e47` (the compact Live meter evidence accepts the folded marker), `321dac1` (docs for the polished
  Recordings table and short reasons), `350d7e6` (`docs/LOGGING.md` points at Runs & DPS).
- **Accepted as they are:** 9 (the meter table's sideways scroll at desktop width is pre-existing and documented), 10 (the
  retiring Statistics page, exempt until P6), and the harness artifacts of 11. `ui.RunsDpsEvidenceTest` after the polish: 5 tests,
  0 failures.

## Deferred scope

- **P6:** the live scope-row merge on Runs, Timeline and Resources (with Loot, Chat, Key-pops and Party); Resources & buffs'
  final home; removing the Statistics and DPS Logger pages and `historical-statistics-tabs`; homes for the Fame Table, the live
  Fame Graph's interval comparison and Loot › Live log; the numeric shell API; drag-to-reorder tabs and sidebar rows.
- **Not in P5b unless S8 needs it** (it did not): building `CombatMeterData` off the EDT (R4 H3; about 17 ms at 96k hits, more in
  hour-long fights) and keeping unchanged saved views on show (R4 H5, the Loot follow-up event above).
- **Not in P5b:** per-recording delete; a curated class palette; marking dungeon loot partial for bags without a run link (needs
  the bag's own dungeon in `LootFacts`); the fight in progress at exit (P5a).
- Evidence findings 1–11 above, until the coordinator's review and polish task decide them.
