# P6a validation and coverage

Base: P5b PR #26 merged as `73400af`, verified before P6a began. P6a is one PR from the integration branch
`claude/redesign-handoff-next-steps-edrr7w`. The plan is `docs/superpowers/plans/2026-09-29-p6a-structure.md` (committed as
`b189257`). P6a is the structural half of P6. Shell pages are addressed by destination ID instead of page number. The Build and DPS
Logger pointer pages are removed. Loot capture moves out of the Statistics UI into `LootCapture`. **Loot** becomes a page with the
tabs **Highlights · Explore**. Saved character fame moves to Characters as an Analyst tab, and Settings gains **Loot filters**,
**Chat** and **About**. Last, the **Statistics** page is removed, along with everything only it used and the `UNLISTED` sidebar group.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass: keep it below as diagnostic history
and record the rerun that passed. The cells the coordinator owns are marked as such.

## Implementation method

P6a followed a **contract plan**, as P3b, P4, P5a and P5b did. The plan fixed the decisions, file ownership, names, signatures,
behavior and the tests each task had to add, and left the code to the implementers. It ran in four waves of implementer subagents,
one fresh subagent per task. Each worked test-first in its own isolated git worktree, with its own Gradle build directory and
project cache (`build/p6a-tN`, `build/p6a-tN-cache`). The coordinator (Claude) reviewed every task's diff against the plan before
merging it into the integration branch, and ran the union of the wave's focused tests on the integration branch before the next
wave started. Research notes R1 (Loot and the content only Statistics had), R2 (Advanced pages; P6b), R3 (destination IDs and the
retired pages) and R4 (scope-row merge and docs; P6b) gave the implementers the current code verbatim.

- **Wave A** (parallel, 5): Task 1, the destination-ID pass (a pure refactor that merged first); Task 2, `LootCapture` and
  `LootFilters`; Task 3, the `LootFacts` extension and `SessionStamps`; Task 4, the kit's `ViewSelector`, bag sprite well and
  `StatTile.valueText()`; Task 5, deleting the dead half of `HistoricalStatistics`.
- **Wave B** (parallel, 5): Task 6, retiring the Build and DPS Logger pointer pages; Task 7, Loot Highlights; Task 8, Loot Explore's
  view selector; Task 9, Characters › Fame history; Task 10, the Settings sections Loot filters, Chat and About.
- **Wave C** (sequential): Task 11, the Loot page and all shell wiring; Polish A (screenshot fixes from Tasks 7 and 10); Task 12,
  removing Statistics, the `UNLISTED` group and the unused `StatsUi` styles.
- **Wave D**: Task 13, evidence, S6, S8, the docs and this record. Then the coordinator runs the final full suite, `shadowJar` and
  the JAR smoke, and opens the PR.

**User decisions (2026-09-29):** P6 ships as two PRs: this one, P6a (structure), then P6b (consistency). For the Statistics extras,
**re-home the essentials**. Character fame moves to Characters as an Analyst tab. Dropped with the page: the legacy `.fame`
autosave and the Fame Table's Sessions popup ("Open fame session file…" stays), the per-map fame breakdown, the live Dungeon Stats
view (Dungeons › Analysis covers it), and the facts only the Live log showed (player skin, exalt loot bonus, players left at a kill,
Moonlight flames). The Scope row merge is P6b. Settings gains Loot filters, Chat and About sections, and their menu entries stay and
open them.

Task commits (merges in brackets):
- Wave A: Task 1 `b435c05` (`1c71f6d`), Task 2 `c728cc8` (`5565e20`), Task 3 `dc0a1c9` (`a068315`), Task 4 `6e9ea2e` (`a5fa866`),
  Task 5 `db45309` (`0780c52`); coordinator `6c252c7` (Wave A outcomes; "shell page N" wording dropped from comments).
- Wave B: Task 6 `b49db0e` (`627b67c`), Task 7 `3deec66` (`99e98d7`), Task 9 `832dbb5` (`56bf003`), Task 10 `e30803d` (`51e9591`),
  Task 8 `c1b227b` + `ce27ef2` (`aa12e87`); coordinator `600668c`, `19389ee`.
- Wave C: Task 11 `b74e2ff` (`ff2a857`), Polish A `9a2c03f` (`a747b3b`), Task 12 `b9003bf` (`3271259`); coordinator `e2bbc27`
  (present-tense Statistics wording dropped from comments), `f51b452` (Wave C outcomes).
- Wave D: Task 13 on `f51b452`: this commit.

Each task's RED and GREEN runs are in its implementer report and in the coordinator's review notes. The table below records the
focused and wave totals.

## Coverage

- Task 1: `NavEntry(id, title, description, icon, group, shortcut)`, `WorkspaceShell(Map<String, …>)`, `select(String)`,
  `selectedPage()`, `pageOf` → ID, `bindShortcut`, `ShellNavigator` with `String` pages, `TestPages` and `NavEntryTest`. Accepted:
  Chat is added to the `CardLayout` first; loops follow `NavEntry` order; evidence file names use IDs.
- Task 2: `LootDashboard.Feed` (public; one `loot` append per drop; weakly held views; `revision()`, `snapshot(session)`,
  `capped()`), `LootCapture` (`get()`, `bind(TomatoData)`, `update`, `updateExaltStats`, sinks, sounds, pings, sharing and a daemon
  flame-reset executor; no Swing) and `LootFilters` (keys unchanged; listeners on the EDT; the menu follows it).
- Task 3: `Bag.dungeon` and `dropper`, `Item.slots` and `applied` (null = unknown), `potionStat`, `Item.enchanted()` and
  `enchantKnown()`; `SessionStamps<V>`, used by `HomeArchive` and `DungeonsSource` without behavior change.
- Task 4: `ViewSelector<V>` (header rows never selectable; silent `select` and `setItems`; `onChange` for user choices only),
  `Sprites.paintWell` (pixel-identical to the old well) and `StatTile.valueText()`.
- Task 5: `HistoricalStatistics` keeps only its workspace factories; `Profile` became `LootProfile`; `ReportingStatisticsTest`
  asserts every rule on `StatisticsArchiveAdapter` rows.
- Task 6: the `my-info` and `dps-logger` rows, slots, pointer panels and `DpsMovedPanelTest` are gone. Alt+7 and Alt+8 go through
  `bindShortcut` and the navigator, and a Build route with no character opens Characters. `DiscoveryCatalog` says "Live meter" and
  "Party". New `BuildRouteTest`.
- Task 7: `tomato.gui.loot` Highlights (model, source, view and two renderers). Home parity is checked on three fixtures. Accepted:
  four extra model fields for honest captions, and the nudge reads 750 ms after a revision change.
- Task 8: the `loot-views` (live, in the filter row) and `loot-archive-view` (saved) selectors, `LootExploreModel`, `StatTile`s and
  Recent Drops' range in the drawer. Review change `ce27ef2`: a fresh saved Loot opens on All Items.
- Task 9: `CharacterFameHistory` (saved-only, FAME only, header, "Open fame session file…"); `CharacterPanelGUI.hostFame` builds it
  on first selection, once.
- Task 10: six Settings sections; `LootFiltersSection`, `ChatSection` (Save chat through `TomatoMenuBar.setSaveChat`, the embedded
  editor from `ChatGUI.filtersEditor()`), `AboutPanel` and `AboutSection`; `TomatoMenuBar.onOpenSettings`.
- Task 11: `LootPage` (`loot-page`, `loot-tabs`, `ui.tabs.loot`; composite Back state; `tabTarget`; `dungeonRoute`), `LootTab`,
  `LootFocus` and `LootSharingStatus`. `TomatoGUI` binds `LootCapture` and wires Highlights' hooks and ⋯, the six-section Settings
  and the menu hook, Characters › Fame history, seven search entries, and Home's Notable loot tile opening Highlights.
- Polish A: About's content shares the Diagnostics header's left edge; the Loot filters pack into two natural columns; notable cards
  give the area its own line.
- Task 12: deleted `StatisticsGUI`, `LootGUI`, `FameTablePanel`, `FameTrackerGUI`, `FameRefresh`, `FameTrackingModel`,
  `FameTableBridge`, `DungeonStats` and `FameTracker` (and the tests `StatisticsTabsTest`, `FameAutosaveStateTest` and
  `FameTrackingStateTest`); removed the `statistics` row, `Group.UNLISTED`, `Destination.STATISTICS`, `statistics.open`, the Dungeons
  banner and link, `statisticsWorkspace`, `LootDashboard.Archive`, the boolean `LootArchiveClient` and unused `StatsUi` members.
  Alt+5 opens Runs & DPS › Dungeons with a Back entry. `Entity` saves fame through `AppHistory.fame(...)` exactly as before.
- Task 13: `ui.LootEvidenceTest` (7 tests, 27 captures); `FilterBarEvidenceTest` gains the Loot › Explore live row
  (`loot-explore-live`), extends the Loot view-selector check to it, and pads each page clear of the capture harness's title band;
  `ShellSwitchTimingTest` enters Loot as Highlights and as Explore and gains a Loot tab test. Docs: new `docs/LOOT.md`,
  `docs/STATISTICS.md` rewritten as "Where Statistics went", `docs/CHARACTERS.md` (Fame history), `docs/CHAT.md` (Settings › Chat),
  `docs/DPS-METERS.md`, `docs/UI-REDESIGN.md` (the information architecture after P6a), `README.md`, and present-tense fixes in
  `docs/ACTIVITY.md`, `docs/SESSION-HISTORY.md` and `docs/UI-CONSISTENCY.md`.

Existing assertions Task 13 touched: `ShellSwitchTimingTest.switchingCoreDestinationsStaysWithinOneHundredMsAtP95` now expects 8
targets instead of 7 (**replace**; reason: Loot became a page with two tabs, so it is entered twice, like Runs & DPS; stricter).
Its Loot canary now also requires the workspace to be showing when Explore is the target (**add beside**). `FilterBarEvidenceTest`'s
Loot view-selector check applies its live branch to every `*-live` Loot page instead of only `loot-live` (**replace (form only)**;
`loot-live` is checked exactly as before). Each capture also asserts that the page keeps the matrix size (**add beside**).

## Local validation

JDK 17 and Gradle 7.6.4 on Linux under Xvfb (`LC_ALL=C.UTF-8`), isolated build directories per task, synthetic fixtures only; no
live capture and no bridge deliveries. `GRADLE` abbreviates the plan's command. CI is manual-only (AGENTS.md, 2026-09-26), so these
are the local checks.

| Check | Command | Record |
|---|---|---|
| Baseline (no new run) | P5b's final suite on `a42f9fc` and the P5b Codex fix's focused run | **1895 / 5 / 0 / 5** (tests / failures / errors / skipped): the four known Linux/Xvfb failures (`StatisticsArchiveNativeTest.actualLootFactory…`, `QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`) plus the quest harness race fixed in `1498102`; and **487 / 0** |
| Task 1 refactor proof | the migrated test classes on the base commit and on Task 1's head | the 51 migrated classes (R3's 52 counted the helper `ArchiveNativeSupport`) have **identical per-class counts and results** before and after: 259 tests, the same 3 known failures; plus `RouteBackRestoreTest` and `ShellNavigatorRedirectTest`, which R3 missed. Every changed assertion was a **replace (form only)** page-number → ID rewrite |
| Task 2 | `tomato.gui.stats.*`, `CharacterPublicationTest`, `ui.WorkspaceUiTest` | 188 tests, 1 failure: the known `actualLootFactory…` |
| Task 3 | `LootFactsTest`, `tomato.history.*`, `tomato.gui.glance.home.*`, `tomato.gui.runs.*` | 333 tests, 0 failures |
| Task 4 | `tomato.gui.kit.*`, `RunCardRendererTest` | 257 tests, 0 failures |
| Task 5 | `tomato.gui.stats.*` | 141 tests, 1 failure: the known `actualLootFactory…` (reproduced on the base); `ReportingStatisticsTest` 16 / 0 |
| Task 6 | modern, route, myinfo, glance.character, dps, activity, `ui.*`, the shell hook and route registration tests | 655 tests; the one failure it met (`CoverageExplanationTest` expecting the old "Inspect" label) was fixed in the task ("Party"), otherwise only known failures |
| Task 7 | `tomato.gui.loot.*`, `tomato.gui.glance.home.*` | 131 tests, 0 failures; Home parity on three fixtures |
| Task 8 | `tomato.gui.stats.*`, `tomato.gui.runs.*`, `FilterBarEvidenceTest` | 334 tests, 1 failure: the known `actualLootFactory…` |
| Task 9 | character, glance.character, stats | 397 tests, 1 failure: the known `actualLootFactory…` |
| Task 10 | settings, chat, maingui, `ui.WorkspaceUiTest` | 133 tests, 2 failures: the known `ChatFiltersTest.editorSavesRules…` and `ChatConsistencyTest.nativeFilterDialog…` |
| Task 11 | loot, stats, settings, glance.home, character, the shell hook, route registration, journey and timing tests | 643 tests, 3 known failures; `WorkspaceUiTest`'s About test was updated to Settings › About; S8's Loot switch about 20 ms p95 |
| Polish A | the touched Highlights and Settings tests | 82 tests, 0 failures |
| Task 12 | stats, loot, modern, route, runs, backend, `ui.*`, the shell hook, route registration, journey and timing tests | 1,091 tests, 1 failure: the known `actualLootFactory…` |
| Wave A merge (Tasks 1–5) | the 66 changed test classes plus stats, kit, runs, glance.home, modern, route, maingui, `tomato.history` and `ui` | 163 test classes, **887 tests, 4 failures**: the three known Linux/Xvfb ones and the load-sensitive `ContentStyleTest`, which passes alone (17 / 0) |
| Wave B merge (Tasks 6–10) | the 42 changed test classes plus the stats, loot, kit, runs, Home, character, Characters, settings, chat, maingui, modern, route, myinfo, dps, activity, logging, history and `ui` packages, the shell route, journey, timing and S6 tests | 258 test classes, **1,353 tests, 3 failures**, all known (`actualLootFactory…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`) |
| Wave C merge (Tasks 11, Polish A, 12) | the 40 surviving changed test classes plus stats, loot, kit, runs, glance, character, settings, chat, maingui, modern, route, myinfo, dps, activity, logging, history, backend, `tomato.history` and `ui`, the shell route, journey and timing tests | 289 test classes, **1,510 tests, 4 failures**: the three known ones and `ContentStyleTest` (see the note below) |
| S8 | `GRADLE test --tests "tomato.gui.ShellSwitchTimingTest"` (alone, twice) | Task 13 on `f51b452` plus this commit: **both runs pass**, 3 tests each (core destinations, Runs & DPS tabs, Loot tabs), 20.1 s and 21.2 s. Numbers under "S8 page switching" below |
| Reads | `HighlightsSourceTest` | Task 7: large fixture (30 sessions × 40 bags) **91 ms cold / 34 ms warm** alone, **15 / 3 ms** with a warm JVM; Task 13 (`tomato.gui.loot.*`): cold 26 ms, warm 8 ms |
| S6 | `GRADLE test --tests "tomato.gui.history.FilterBarEvidenceTest"` (alone) | Task 13: **1 test, 0 failures**; 14 pages × 8 states = **112 captures** in `build/p6a-t13/ui-test/screenshots/redesign-p1c/`; one filter row at 1240×800 font 13 with the drawer closed and active chips on every page, Loot's view selector live and saved. Diagnostic history: the first run of the new `loot-explore-live` page failed a Task 13 expectation, not the app (it expected the Loot workspace's own row to be hidden while live; it shows only the scope controls, Browse saved, the session, Refresh and ⋯, and P6b merges it). The check now asserts that the workspace's saved search and Filters toggle are hidden while live. Two passing runs followed, the second with the padded frame |
| Evidence (Task 13) | `GRADLE test --tests "ui.LootEvidenceTest"` (alone) | **7 tests, 0 failures, 0 errors, 0 skipped** (run 2); 27 captures in `build/p6a-t13/ui-test/screenshots/redesign-p6a-loot/`. Diagnostic history: run 1 failed 5 of 7 tests. All but one cause were test-side: the empty fixture saved this app run's bag, so Today had a bag and showed real zeros; tile text was compared with the label's "2 (partial)"; header rows were read through `toString()`; compact captures did not scroll to the selector or the fame table; and "four tiles in one row" is not true in the real shell (finding 1, now asserted as whole tiles in at most two rows). The remaining failure was real: a saved-only view failed to read a legacy bag without a name (finding 2). The Explore fixture now holds no such bag, and the failing capture is kept in the coordinator's scratchpad (`p6/t13-evidence/`) |
| `tomato.gui.loot.*` (Task 13) | `GRADLE test --tests "tomato.gui.loot.*"` | 6 classes, **46 tests, 0 failures**: nothing else moved |
| Final full suite and JAR | `GRADLE test shadowJar` on `0625e34` (alone) | **2,020 tests in 401 classes: 4 failures, 0 errors, 5 skipped.** The four failures are exactly the Linux/Xvfb baseline ones (`StatisticsArchiveNativeTest.actualLootFactory…`, `QuestConsistencyTest.nameTypesDialog…`, `ChatFiltersTest.editorSavesRules…`, `ChatConsistencyTest.nativeFilterDialog…`); `ContentStyleTest` passed in this run. Baseline: P5b's 1,895 / 5 / 0 / 5 on `a42f9fc` (the fifth, the quest harness race, was fixed in `1498102`). `shadowJar` built `RealmShark-v1.2.3.jar` (9.9 MB) |
| JAR smoke | isolated `java -jar … --help` from an empty folder | exit 0 with the usage text (`--help`, `--preview`, `--path`), from an empty scratch folder with its own `user.home` and `java.io.tmpdir`; the folders held no files afterwards |

**`ContentStyleTest` (Waves A and C).** It loads only `ContentStyle` and `VioletTheme`, both byte-identical to P5b's merge. The
assertions that fail (an extra relayout after the width settles, and a grid height after a window resize) belong to the known
window-resize timing family on Xvfb without a window manager: the same late X11 resize echo root-caused for the quest test in P5b
(`1498102`). Alone it failed 2 of 7 runs on the Wave C head and 0 of 5 on `73400af`. The inputs are identical, so it is not a P6a
regression. Hardening it (resizing once, as `1498102` did) is a separate follow-up.

## S8 page switching

Spec §1 S8: switching between core destinations stays within 100 ms p95 on the large synthetic history. `ShellSwitchTimingTest`
(P5b Task 13) builds the real shell in a visible 1240×800 frame, with isolated preferences and history, over
`HomeHistoryFixture.writeLarge(root, 30, 40)` and `CombatFixtures.writeLarge` (1,200 saved runs with combat records). Loot › Explore,
Chat, the Runs table and Recordings are in saved mode over all sessions and settled. A live fight of 1,200 s × 8 players × 300 enemies
(96,000 hits) is republished off the EDT before every Live meter entry. The frame metric is `select`, layout and dirty-region paint in
one EDT turn. The longest follow-up EDT event is bounded too, so work moved to `invokeLater` cannot pass.

**P6a additions (Task 13).** Loot is entered twice, as **Highlights** and as **Explore** (each tab selected while the page is hidden,
as Runs & DPS' Feed and Live meter are): 8 targets, 160 samples. A third test switches the two Loot tabs through the tab strip while
the page shows (40 samples). The large fixture is dated in 2025, so this app run's session also saves 400 bags dated today (a UT every
fifth bag, STs and stat potions). Highlights' Today then shows full tiles, the 200 newest notable drops and four dungeons. It reads them
on its first show during setup, and a Highlights read in flight counts as loading. Canaries: Highlights shows its read (80 UTs, a
non-empty grid), Explore shows a saved page, and the Loot tab in front is the target's.

On `f51b452` with Task 13's changes, alone, twice. Frame p50 / p95 / max in ms:

| Destination | Run 1 | Run 2 |
|---|---|---|
| Home | 5.8 / 14.8 / 15.3 | 7.3 / 22.1 / 27.4 |
| Characters | 7.1 / 9.0 / 10.3 | 7.4 / 15.9 / 24.6 |
| Runs & DPS › Feed | 5.1 / 14.3 / 26.2 | 6.5 / 9.2 / 11.4 |
| Runs & DPS › Live meter | 19.0 / 32.1 / 55.5 | 19.5 / 32.7 / 36.6 |
| **Loot › Highlights** | 7.0 / 11.8 / 22.2 | 7.1 / 8.6 / 32.1 |
| **Loot › Explore** | 2.4 / 4.7 / 7.1 | 3.0 / 6.6 / 21.3 |
| Quests | 1.0 / 1.6 / 3.5 | 1.2 / 1.7 / 1.9 |
| Chat | 1.7 / 3.9 / 4.6 | 2.2 / 5.2 / 10.6 |
| All core switches (160) | 4.7 / 22.2 / 55.5 | 6.0 / 22.8 / 36.6 |

| Loot tab | Run 1 | Run 2 |
|---|---|---|
| Highlights | 4.4 / 5.2 / 20.7 | 5.6 / 8.6 / 25.6 |
| Explore | 1.2 / 1.3 / 2.5 | 1.5 / 3.3 / 6.3 |
| All Loot tab switches (40) | 4.1 / 4.8 / 20.7 | 4.4 / 6.8 / 25.6 |

| Runs & DPS tab | Run 1 | Run 2 |
|---|---|---|
| Feed | 3.6 / 4.6 / 5.3 | 3.8 / 5.6 / 5.9 |
| Dungeons | 2.5 / 3.4 / 4.9 | 2.8 / 3.5 / 3.7 |
| Live meter | 14.1 / 35.8 / 39.0 | 14.9 / 34.0 / 36.8 |
| Recordings | 1.2 / 1.9 / 2.3 | 1.4 / 2.1 / 3.1 |
| All tab switches (80) | 3.4 / 27.9 / 39.0 | 3.5 / 18.5 / 36.8 |

**Met.** Every destination, every tab and the overall p95 are far below 100 ms. The longest follow-up EDT event's p95 was at most
33.4 ms (run 1) and 34.2 ms (run 2), on Loot › Explore in both runs. The event is a Swing timer on every Explore entry: the saved view
arrives through a Swing timer and is rebuilt on every show (R4 H5/H6, unchanged since P5b, whose Loot showed the same 34.6 / 39.6 ms).
Entering Highlights costs no read, because it reads only on first show, on a period change and on a nudge. Its longest follow-up p95
was 0.3 ms and 6.9 ms as a destination, and 5.2 ms and 13.5 ms as a tab. Before P6a (P5b's record), Loot was 3.0 / 5.7 and
2.4 / 11.8 ms p50 / p95. Raw logs are in the coordinator's scratchpad (`p6/t13-s8-run1.log`, `p6/t13-s8-run2.log`).

## S6 filter rows

`FilterBarEvidenceTest` captures each adopted page with its filters collapsed and open, at 1240×800 and 680×520, fonts 13 and 18. It
asserts the drawer's visibility, the active chips, and one filter row at 1240×800 font 13 with the drawer closed. After P6a the
matrix holds 14 pages: the Runs table (`runs`), saved Loot (`loot`), Loot's live row (`loot-live`, the live dashboard alone, as Explore
shows it without saved history), **Loot › Explore live beside saved history (`loot-explore-live`, new in Task 13)**, `chat`,
`keypops`, `characters`, `quests`, Timeline (`timeline`), Resources (`combat`), Party (`inspect-roster`), the Live meter
(`dps-meter`), Recordings (`encounter-library`) and `dungeons`. On the Loot pages the view selector sits in the live row's search slot.
In saved history it leads the saved view directly under the filter row, and P6b's Scope merge makes that one row. In live Explore,
the Loot workspace's row above keeps only the scope controls; its saved search and Filters toggle are hidden. Since Task 13 each page
is shown padded clear of the harness's 23 px title band, in a frame enlarged by as much, so the filter rows are visible in the
captures and each page keeps the matrix size (asserted).

**No exemptions remain.** P5b exempted the Statistics page's retiring sub-pages (Fame Table, Dungeon Stats, Fame Graph, Loot › Live
log) and the dead `historical-statistics-tabs`. All of them are gone (Tasks 5 and 12), and every page with a filter row is in the
matrix.

## S7 core destinations

Six core entries: **Home, Characters, Runs & DPS, Loot, Quests, Chat**. Advanced (5): Party, Key-pops, Timeline, Logging, Bridge
Review. Settings sits below the list, with six sections. No page exists outside the sidebar any more: there are no Build, DPS Logger or
Statistics pages and no `UNLISTED` group. Alt+5 opens Runs & DPS › Dungeons, Alt+7 the Build tab and Alt+8 the Live meter, each through
the navigator. Asserted by `NavEntryTest`, `NavLayoutTest`, `WorkspaceShellLayoutTest` and `ShellHookIntegrationTest`, and in the real
shell by `ui.RunsDpsEvidenceTest` (sidebar and shortcuts) and `ui.LootEvidenceTest` (the sidebar in every 1240 capture: six core rows
and no `nav-statistics`, `nav-dps-logger` or `nav-my-info`).

## Evidence

`ui.LootEvidenceTest` captures the real workspace (`TomatoGUI.createWorkspace` in preview mode; the app's history store points at the
test's own history folder, or at none, and is restored after). The data is synthetic saved history dated relative to the clock. For
the test, the JVM's zone is set to a fixed offset at which it is mid-afternoon, so the bags fall on the local day whenever it runs.

- **Today** (a closed session, 11:40–14:20, four runs): eight bags. A White bag in Lost Halls holds the UT Voidblade (2 slots) and a
  Life potion. An Orange bag holds the ST Aegis Robe (0 slots) and the Tier Sword (3 slots). A Cyan bag in Ice Citadel holds the Frost
  Staff (2 slots), a Defense and a greater Life potion. A boosted White bag in Pirate Cave holds the UT Tidal Dagger (1 slot). A Purple
  bag in Snake Pit holds the Viper Bow (no enchant data) and a Mana potion, with no run. A Brown bag with no recorded area holds a
  tonic (an other potion). A Blue bag holds an Attack and a Wisdom potion. A legacy bag has no saved bag name. There is fame for two
  characters.
- **This app run's session**: an Orange bag with the ST Crystal Mail (1 slot), ten minutes ago.
- **Yesterday**: a White bag with a UT (not in Today's counts) and fame.
- **Live** (the no-history test): the same named bags, handed to a fresh loot capture's feed.

The test has no game assets. Item names are synthetic asset entries (restored after), sprites are placeholders, and the stat potions
use the game's IDs so Highlights knows their stats. Preferences (`ui.tabs.*`, `ui.nav.*`, `ui.filters.*`, `ui.loot.*`, the Filter
Loot keys, Save chat, `ui.mode`), the archive keys, the loot capture, the zone, the format locale and the DPS statics are restored
after each test. The harness's frame paints a 23 px title band over its content, so every capture pads the shell and enlarges the
frame by as much. The shell itself is exactly 1240×800 or 680×520 (asserted), and nothing of it is hidden. Every capture asserts that
no page scrolls sideways (data tables that scroll their own columns are listed on standard output) and the content it is evidence of.
Each test takes its light-theme capture last, switching the theme live as Settings › Appearance does. Reviewer: the Task 13
implementer read every PNG; the coordinator reviews the captures and these findings.

| Capture (`p6a-…`) | What it shows |
|---|---|
| `highlights-1240-13-simple` | Loot with the strip **Highlights · Explore**; "Saved history · Today", **Today** selected. Tiles: UT drops 2, ST drops 2, Potions 7 ("2 Life · 1 Mana · 1 Att · 1 Def · +2 more"), White bags 2 ("of 9 bags · 1 without a bag name"), in **two rows of two** (finding 1). Notable drops 12, "1 item without recorded enchant slots is not listed as enchanted", newest first: Crystal Mail (ST, "Not linked to a run"), Attack, Wisdom and Mana potions, Tidal Dagger (UT), Defense and greater Life potions, Frost Staff and Tier Sword (**Enchanted**), Aegis Robe (ST), Voidblade (UT), Life potion. By dungeon, 9 bags: Lost Halls 4 ("1 UT · 1 ST · 3 p…", finding 4), Ice Citadel 2, Pirate Cave 1, Snake Pit 1, **Unknown area** 1. The sidebar: six core rows, Loot selected, no Statistics row |
| `highlights-680-18-analyst` | Compact: the header wraps its buttons (pre-existing), the strip, the caption, the period choice and ⋯. The tiles stack one per row, so UT, ST and part of Potions show (finding 1) |
| `highlights-notable-680-18-analyst` | Compact, scrolled to the notable drops: one card per row, whole names, "Not linked to a run" |
| `highlights-1240-13-simple-light` | The light theme: as the dark capture. The sidebar's destination list is boxed (finding 3), and the tiles have low contrast (finding 11) |
| `highlights-empty-1240-13-simple` | Nothing saved today: every tile is **—**, "No notable drops yet" with "No loot was saved for this period. Start capture and run a dungeon: …", "No bags in this period." The four tiles share one row (short sub-lines) |
| `highlights-empty-680-18-analyst`, `-1240-13-simple-light` | The same, compact (tiles two by two) and in the light theme |
| `highlights-live-1240-13-simple` | No history store: "**This app run · not saved**"; UT 2, ST 2, Potions 7, White bags 2 "of 8 bags"; the same 12 notable drops; 8 bags by dungeon. Explore is the live dashboard alone (asserted) |
| `highlights-live-680-18-analyst`, `-1240-13-simple-light` | The same, compact and in the light theme |
| `highlights-partial-1240-13-simple` | One of today's sessions has a damaged loot journal: "◐ 1 saved session could not be read; their loot is missing from these counts." and every tile "2 (partial)" or "7 (partial)"; the readable sessions still list their 12 drops |
| `highlights-partial-680-18-analyst`, `-1240-13-simple-light` | The same, compact (the warning wraps) and in the light theme |
| `explore-live-1240-13-simple` | Explore, live: Browse saved, Current Session, Refresh and ⋯; "Loot explorer" with four tiles (8 bags, 14 items, 7 stat potions, 2 white bags); one filter row (**All Items** selector in the search slot, search, Reset filters, Filters); the All Items table with rarity, slots and enchants ("—" for potions); the rarity summary ("Unknown: 8", finding 6); Retry view save / Reset saved live view (finding 8) |
| `explore-live-680-18-analyst` | Compact Analyst, scrolled to the filter row: the selector, search and Reset filters, then Filters on its own line, and three table rows (the table scrolls its columns). The selector lists the nine Simple views, then "Analyst" and the six saved-only views (asserted) |
| `explore-saved-680-18-analyst` | Saved, all sessions, compact, scrolled to the saved selector: All Items, then the long query caption, which fills most of the viewport (finding 7) |
| `explore-saved-1240-13-simple` | Saved, all sessions: the filter row (search, Filters, Current live view, All Sessions, Refresh, ⋯), the **All Items** selector right under it, the caption ("matching bags: 9 bags …"), 15 item variants (blank Slots and Applied for potions, finding 6; Items "—"), the drill buttons, the column controls and "pinned …" (finding 7) |
| `explore-saved-only-1240-13-analyst` | Back to live in Analyst, **Dungeon loot profile** chosen from the live selector: Explore switches to saved history with that view, captioned **Saved history only**. Five areas (Ice Citadel, Lost Halls, Pirate Cave, Snake Pit and "Unknown", finding 5) with items, visits, observed time and rates ("—" where unavailable) |
| `explore-saved-only-680-18-analyst` | The same, compact, scrolled to the selector and caption |
| `settings-loot-filters-1240-13-simple` | Settings with six sections, **Loot filters** selected: "Which bag colors show in Loot › Highlights' notable drops. Tiles and counts always include every observed drop. …", ten checkboxes in two columns, **Show all** disabled (all shown) |
| `settings-chat-1240-13-simple` | **Chat**: Saving (Save chat, off), Chat filters with the embedded editor (its content inset about 10 px further than the section headings, and its Save and Cancel below the fold, finding 9) |
| `settings-about-1240-13-simple` | **About**: the logo, "RealmShark v1.2.3 · Custom build", the description, credits, license, then Diagnostics with **Java version** and **Net traffic**; no path shown (asserted) |
| `settings-*-680-18-analyst` | Compact: the sections wrap above the content, all six reachable; each section's top in view |
| `characters-fame-history-1240-13-analyst` | Characters with **Roster · Exalts · Pets · Fame history** (Analyst; hidden in Simple, asserted). The header line and **Open fame session file…**, the filter row, a one-item **Character fame** selector (finding 10), the caption, and three rows (Knight #2 +120 and Wizard … +450 today, Wizard … +500 yesterday) with **Open selected session's full fame graph** |
| `characters-fame-history-680-18-analyst` | Compact: the header wraps beside the link; the table is below the fold, reachable by scrolling (finding 10) |

Findings from Task 13, which changed no main code:

1. **Highlights' tiles wrap into two rows at desktop width (layout).** At 1240×800 font 13 in the real shell (content about 1,020 px
   wide), the tiles need more than a quarter of the width each, because of the potions sub-line ("2 Life · 1 Mana · 1 Att · 1 Def · +2
   more") and the white-bag sub-line ("of 9 bags · 1 without a bag name"). The grid then goes two by two, each tile spanning half the
   page with its right half empty (`p6a-highlights-1240-13-simple`, `-partial-…`, `-live-…`). With short sub-lines the tiles share one
   row (`p6a-highlights-empty-1240-13-simple`); `LootHighlightsTest` checks one row for the panel alone at a full 1240 px. At 680×520
   font 18 the tiles stack one per row, so only two and a half tiles show before the notable drops
   (`p6a-highlights-680-18-analyst`).
2. **Bug (pre-existing): the saved loot-profile views fail on a legacy bag without a name.** A saved bag with no `bag` name makes
   `LootProfile.add` throw at `LootProfile.java:20` (`drop.bag.equals("White")`). Explore's **Dungeon loot profile** then shows
   "History read failed: Cannot invoke "String.equals(Object)" because "drop.bag" is null. Refresh to retry; no new revision was
   applied." The selector falls back to All Items and the previous table stays. **Session comparison** (the same adapter) and **A/B
   cohorts** (`CohortArchiveAdapter`, also on `LootProfile`) take the same path, in Explore and in Dungeons › Analysis, but were not
   captured. The line is verbatim from P5b's `HistoricalStatistics.Profile` (present at `73400af`; Task 5 moved it), so it is not a
   P6a regression. Highlights counts such bags ("without a bag name"). Capture from run 1: coordinator scratchpad
   `p6/t13-evidence/p6a-explore-saved-only-legacy-bag-read-failure-1240-13-analyst.png`. The evidence fixture for Explore holds no such
   bag.
3. **A live theme switch boxes the sidebar list (pre-existing).** After Settings › Appearance (or `Themes.install` plus the tree
   update) switches the theme while the window is open, the sidebar's destination list, and the compact rail, get a bordered box.
   `WorkspaceShell` clears the nav scroll pane's border only in its constructor, and `refreshTheme()` does not clear it again
   (`p6a-highlights*-1240-13-simple-light`; the dark captures are taken first and are clean).
4. **Notable cards and dungeon cells truncate (minor).** At 1240×800 font 13 with four columns of about 220 px, long item names end
   in "…" ("Synthetic Crystal M…", "Synthetic Tidal Dag…"). A dungeon cell with UT, ST and potions cuts its summary ("1 UT · 1 ST · 3
   p…", Lost Halls). The tooltips and accessible names keep the full text (`p6a-highlights-1240-13-simple`).
5. **Unknown area wording differs (wording).** Explore's Dungeon loot profile names bags without an area "Unknown", while Highlights
   says "Unknown area" (`p6a-explore-saved-only-1240-13-analyst`).
6. **Unknown enchant values are blank in saved Explore (consistency).** Saved All Items leaves Slots and Applied empty for potions and
   for the item without enchant data, where the live table shows "—". The live rarity summary counts the seven potions under
   "Unknown: 8", although potions cannot have enchant slots (`p6a-explore-saved-1240-13-simple`, `p6a-explore-live-1240-13-simple`).
7. **Saved Explore shows Analyst-level detail in Simple (P6b).** Simple mode shows the query caption with bracketed scopes ("matching
   bags: 9 bags [whole query; each qualifying bag once] · …"), "pinned 69305e6a", the drill buttons and column presets. The Items
   column is "—" in every row, and the table uses about two thirds of the width. At 680×520 font 18 the caption fills most of the
   viewport (`p6a-explore-saved-1240-13-simple`, `-680-18-analyst`). P6b's Advanced restyles and Analyst-only columns cover this.
8. **View-state buttons and compact wraps (P6b, minor).** Live Explore shows **Retry view save** and **Reset saved live view** beside
   "Live view state saved" (P6b folds view-state rows into ⋯). At 680×520 font 18 the saved filter row wraps into three lines with
   Filters apart from the search (S6 `p1c-loot-680-18-*`). In the real shell at that size, the live session row also wrapped Refresh
   alone onto a second line; that was seen in run 1, before the compact capture was scrolled to the filter row.
9. **Settings › Chat: the embedded editor is inset and its buttons are below the fold (minor).** The editor's text starts about 10 px
   right of the section headings, and **Save filters** and **Cancel** are below the fold at 1240×800, so the page scrolls
   (`p6a-settings-chat-1240-13-simple`).
10. **Characters › Fame history (minor).** The view selector offers one view ("Character fame"). The Filters drawer holds the loot
    facets, which (as the caption says) do not filter fame. The Name column ends in "…" while the table uses about 60 % of the width.
    At 680×520 font 18 the table is below the fold (`p6a-characters-fame-history-*`).
11. **Light theme contrast (observation).** Highlights' tiles and cards are barely distinguishable from the page background in the
    light theme (`p6a-highlights*-1240-13-simple-light`).
12. **Stale page descriptions (wording, main code).** The page title's tooltip and accessible description still read "Explore live
    loot by item, stat potion and bag type." for Loot and "Notifications, sounds and appearance." for Settings (`NavEntry`); neither
    mentions Highlights or the three new sections.
13. **Observations and harness artifacts.** Sprites are placeholders and the Icon column is blank (no game assets). The fame rows'
    "Home fixture" label is the fixture's session label. The shell header still offers "Browse saved history" when no history store
    is open (pre-existing). The capture harness's title band is now padded in `LootEvidenceTest` and `FilterBarEvidenceTest`; older
    evidence tests still show it over the page heading.

## Coordinator review of the evidence

The coordinator looked at the captures and decided each finding. Two polish tasks ran in parallel on separate files, each test-first
and reviewed like the plan's tasks:

- **Polish B1** (`9e6842a`, `052e939`; merge `3a162b2`):
  - **Finding 2 (fixed, real bug):** `LootProfile` counts a bag without a saved name as a bag (its items count) but never as a white
    bag, and its explanation says how many such bags it saw. Dungeon loot profile, Session comparison and A/B cohorts now read such a
    history. The same review found that saved **By Bag** also failed on such a bag and that the bag facet listed a bag named "null":
    `LootArchiveAdapter` now groups them on one "Unknown bag (name not saved)" row, leaves them out of the bag facet, and search
    finds them by that label, never by "null". `docs/LOOT.md` explains it.
  - **Finding 3 (fixed):** the sidebar's scroll pane clears its border after each of its own UI updates, so a live theme switch no
    longer boxes the list or the compact rail.
  - **Finding 12 (fixed):** new Loot and Settings descriptions.
  - **Finding 10, part (fixed):** a workspace offering one view hides its view selector row (Characters › Fame history).
- **Polish B2** (`07ab232`; merge `0625e34`):
  - **Finding 1 (fixed):** Highlights' tiles wrap their sub-lines between their " · " parts, so the four tiles share one row at
    1240×800 font 13 in the real shell and go two by two at 680×520 font 18 (one per row before).
  - **Finding 9, part (fixed):** Settings › Chat's editor fills the section and its own lists scroll, so Save filters, Cancel and the
    save status are in view without scrolling at both reference sizes. At 680×520 font 18 this leaves two small stacked scroll areas.

Focused runs: Polish B1 265 tests (1 failure, the known `StatisticsArchiveNativeTest.actualLootFactory…`), then 202 tests with the By
Bag fix (the same one failure); Polish B2 83 tests, 0 failures, twice.

**Deferred to P6b** (pre-existing or consistency work that P6b's restyles cover): findings 4 (long names and dungeon-cell summaries
ellipsize; tooltips keep the text), 5 ("Unknown" versus "Unknown area"), 6 (blank Slots and Applied in saved Explore; potions counted
under Unknown rarity), 7 (Analyst-level captions in Simple saved Explore), 8 (view-state buttons; compact wraps), 9's remaining inset
and a single pinned-footer scroll for Settings › Chat at 680×520, 10's loot facets and Name column width, 11 (light-theme tile
contrast), and By Bag's explicit Bag and Name sorts not placing the Unknown bag row last.

## PR review

PR #27 opened on `87a18aa`. Codex reviewed it and left one finding.

- **P2, notable drops truncated before Filter Loot** (`HighlightsModel.java:135`): with more than 200 notable drops in the period,
  the model kept the newest 200 before `LootHighlights` applied the bag-color filters, so when many of them were a hidden color the
  grid could be sparse, or say every drop was hidden, although older drops of visible colors existed. **Fixed:** each bag name (null:
  none saved) keeps its own newest 200, and `HighlightsModel.shown(filter)` returns the newest 200 visible drops with the visible
  and hidden totals; this is exact for any filter, because the newest 200 visible drops always lie within the visible bag names'
  newest 200. The grid's "Filter Loot hides N of M" and "Showing the newest 200 of N" now count the whole period. Memory stays
  bounded (200 per bag name). Tests first: `HighlightsModelTest.hiddenBagColorsNeverCrowdOutOlderVisibleNotableDrops` and
  `LootHighlightsTest.filterLootShowsOlderVisibleDropsBehindNewerHiddenOnes` failed before the fix ("Every white drop shows
  expected:<50> but was:<0>"); `HighlightsSourceTest`'s large-fixture assertion is replaced (the model keeps 200 per bag name; the
  grid shows exactly 200). Focused run after the fix: `tomato.gui.loot.*`, `ui.LootEvidenceTest`, `tomato.gui.ShellSwitchTimingTest`
  and `tomato.gui.glance.home.*`, 152 tests, 0 failures.
- **Merged** as `82983c2` (merge commit) after the fix; `main`'s tree equals the reviewed head `4a22ce4`, `shadowJar` builds on
  `main` and the isolated `--help` smoke exits 0 with no files left.

## Deferred scope

- **P6b (consistency), after P6a merges:** the Scope ▾ chip merged into the page filter row on Runs, Timeline, Resources, Party,
  Loot, Chat and Key-pops (Loot's two synced selectors become one); the Advanced page restyles; a shared helper for Analyst-only
  columns and relative times; sidebar drag; the final Simple/Analyst screenshot set and docs pass; a shared base for `RunsDpsPage` and
  `LootPage`.
- **Not in P6:** Resources & buffs stays nested in the Live meter; building `CombatMeterData` off the EDT; a per-map fame breakdown and
  live Dungeon Stats (dropped by user decision).
- **Left for later:** `RunFeedSource`'s own stamp cache (Task 3); Home's Notable loot tile does not pass its Today / This session
  choice to Highlights (Task 11; the Highlights caption names its period); hardening `ContentStyleTest`; the evidence findings deferred to P6b in "Coordinator review of the evidence".
