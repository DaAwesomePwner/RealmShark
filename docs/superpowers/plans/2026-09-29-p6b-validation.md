# P6b validation and coverage

Base: P6a PR #27, merged as `82983c2` and verified before P6b began. The plan is `docs/superpowers/plans/2026-09-29-p6b-consistency.md`,
committed as `2125b0b`. P6b is one PR from the integration branch `claude/redesign-handoff-next-steps-edrr7w`. It is the consistency
half of P6. The sections below cover what it changed and how that was checked:
- the one filter row with a **Scope ▾** chip on the seven archive pages;
- the Advanced page restyles;
- mode-aware tables;
- sidebar drag and the tab drag fixes;
- Esc for filter drawers and light-theme outlines;
- the evidence findings P6a left open;
- the final Simple/Analyst screenshot set and the docs pass.

An interrupted or failed run is not a pass. Failed runs are kept below as diagnostic history, next to the rerun that passed.

## Implementation method

P6b followed a **contract plan**, like P3b to P6a:
- **The plan** fixed the decisions, file ownership, names, signatures, behavior and the tests each task had to add. It left the code
  to the implementers.
- **Implementers:** one fresh implementer subagent per task, at most five at once, each working test-first in its own git worktree
  with its own Gradle build directory and project cache (`build/p6b-tN`).
- **Review:** the coordinator (Claude) reviewed every diff and every before/after capture against the plan, then merged it with
  `--no-ff`. It sent back what fell short, and ran the union of each wave's focused tests on the integration branch before the next
  wave.
- **Research:** four notes gave the implementers the current code verbatim: R1 (the Scope chip), R2 (Advanced restyles and the
  mode-aware table helper), R3 (sidebar and tab drag, and the P6a leftovers) and R4 (the final screenshots, docs and success criteria).

The waves:
- **Wave A** (5 in parallel):
  - Task 1: the Scope chip core and Esc for filter drawers.
  - Task 2: kit relative-time and Analyst-only columns, item wells and light outlines.
  - Task 3: sidebar drag.
  - Task 4: tab drag fixes and customizable Notifications tabs.
  - Task 5: Logging's one filter row.
- **Wave B** (5):
  - Task 6: column tools into ⋯ and mode-hidden layout rules.
  - Task 7: Party.
  - Task 8: Bridge review.
  - Task 9: loot values, wording and Highlights polish.
  - Task 10: Build tiles and cleanups.
- **Wave C** (4): each page's Scope host paired with its restyle under one owner.
  - Task 11: Runs, Timeline and Resources.
  - Task 12: Chat and Settings › Chat.
  - Task 13: Key-pops.
  - Task 14: Loot Explore.
- **Wave D** (4):
  - Task 15: strict S6.
  - Task 16: the interface docs.
  - Task 17: the module guides.
  - Task 18: the final screenshot set.
- **Wave E** (coordinator):
  - review of all 155 final captures, and five polish tasks A–E from it;
  - the recovery-doc refresh;
  - S8, the final set and S6 again;
  - the final suite and the JAR smoke.

Sent back after review:
- **Task 3:** the rows' accessible description had lost their Alt key.
- **Task 5:** the real-shell row with one chip wrapped by about 11 px, and cut chip labels lost their full text.
- **Task 8:** search did not match the local time Analyst shows.

Each plan wave's "outcomes" section lists every merge, deviation and accepted choice.

## Coverage

| Plan item | Where | Status |
|---|---|---|
| Scope ▾ chip on Runs, Timeline, Resources, Party, Loot, Chat, Key-pops | Tasks 1, 7, 11, 12, 13, 14 | Done. Live: the page's own row hosts the chip. Saved: the workspace row. Loot's two selectors are now one. |
| ⋯ Refresh, and History library and Refresh session list in the Scope menu | Task 1 | Done |
| Esc closes an open filter drawer | Task 1 | Done (focused components keep their own Esc) |
| Relative times in Simple, absolute in Analyst and exports | Tasks 2, 7, 8, 11, 13 | Done on Timeline, Key-pops, Party runs, Bridge and saved activity. Logging stays absolute. |
| Analyst-only columns | Tasks 2, 8, 11, 14 | Done: Timeline Meaning, Bridge saved Session/Journal, Explore's empty Items column |
| Widths never change on a mode switch; saved layouts ignore mode-hidden columns | Tasks 2, 6, Polish D | Done |
| Column tools and "Save view state" rows into ⋯ | Tasks 6, 7, 11, 12, 13, 14 | Done. `HistoryTables.controls` deleted. |
| Party, Timeline, Key-pops, Logging, Bridge, Notifications restyles; Build tiles | Tasks 4, 5, 7, 8, 10, 11, 13 | Done |
| Sidebar drag (whole row, 5 px, violet line, Esc, core rows only, never pins) | Task 3 | Done |
| Tab drag fixes and tests | Task 4 | Done |
| Light-theme outlines on tiles and cards | Tasks 2, 9 | Done. Polish E also removed nested frames after a live theme switch. |
| P6a leftovers B1–B14 | Tasks 6, 9, 10, 12, 14 | Done. The RunsDps/Loot shared base stays deferred by decision. |
| Final Simple/Analyst screenshot set | Task 18, Polish A–E | Done: 155 captures (see "Final screenshot set") |
| Docs pass | Tasks 16, 17, coordinator | Done |

## Local validation

All runs were on Linux (JDK 17, Xvfb), with each worker's own build directory.

| Check | Record |
|---|---|
| Baseline | P6a final suite on `0625e34`: 2,020 tests / 4 failures / 5 skipped. No new baseline run. |
| Per-task focused runs | In each task's report and the coordinator's review notes. Every task ran its RED tests first and reported each existing assertion as "add beside", "replace (form only)" or "replace". |
| Wave A combined, on `f8acc19` | 1,326 tests, 4 failures: the four known Linux/Xvfb ones |
| Wave B combined, on `86436b9` | 1,479 tests, 3 failures: `QuestConsistencyTest.nameTypesDialog…` and the two Chat dialog tests. `StatisticsArchiveNativeTest.actualLootFactory…` now passes: with the column tools in ⋯, the details pane is reachable. |
| Wave C combined, on `33b8521` | 1,682 tests, 3 failures: the same three |
| `ContentStyleTest` (Task 10) | 10 of 10 alone after the settle change. It did not fail here beforehand, so this is not proof of the fix. |
| S8 (alone, twice), on `5dbae2c` | 3 tests / 0 failures, twice. See "S8 page switching". |
| S6 (alone), on `5dbae2c` | 1 test / 0 failures: 31 pages, strict. See "S6 filter rows". |
| Final set (alone), on `5dbae2c` | 1 test / 0 failures: 155 captures, 0 with failed checks, 206 s |
| Final full suite and `shadowJar`, on `5dbae2c` | 2,216 tests / 3 failures / 0 errors / 6 skipped. The three failures are the known Linux/Xvfb ones: `QuestConsistencyTest.nameTypesDialog…` (owning-window focus without a window manager), `ChatFiltersTest.editorSavesRules…` and `ChatConsistencyTest.nativeFilterDialog…`. The P6a baseline's fourth, `StatisticsArchiveNativeTest.actualLootFactory…`, now passes. The skips are the P6a baseline's five plus the opt-in final set. `shadowJar` builds `RealmShark-v1.2.3.jar`. |
| JAR smoke | `java -jar RealmShark-v1.2.3.jar --help` with an empty working directory and an empty home (headless): exit 0, usage printed, both folders still empty afterwards |

## S8 page switching

`ShellSwitchTimingTest` is unchanged since P6a, and so is its fixture:
- the real shell in a visible 1240×800 frame;
- 1,200 saved runs with combat records;
- Loot › Explore, Chat, the Runs table and Recordings saved over all sessions;
- a live fight of 300 enemies republished before every Live meter entry;
- 400 bags saved today.

The frame metric is `select`, layout and dirty-region paint in one EDT turn; the longest follow-up EDT event is bounded too.

Run alone, twice, on `5dbae2c`: both passed. Frame p50 / p95 / max in ms:

| Destination | Run 1 | Run 2 |
|---|---|---|
| Home | 6.2 / 13.2 / 27.2 | 6.4 / 26.7 / 32.0 |
| Characters | 5.5 / 7.1 / 8.7 | 6.3 / 13.5 / 14.1 |
| Runs & DPS › Feed | 5.1 / 7.6 / 8.7 | 5.9 / 13.2 / 14.0 |
| Runs & DPS › Live meter | 17.3 / 28.2 / 30.9 | 18.5 / 34.6 / 37.1 |
| Loot › Highlights | 6.6 / 8.9 / 23.0 | 7.1 / 13.9 / 15.6 |
| Loot › Explore | 3.0 / 7.7 / 23.7 | 3.1 / 8.3 / 19.3 |
| Quests | 1.0 / 1.7 / 2.0 | 1.2 / 6.0 / 7.8 |
| Chat | 1.5 / 1.9 / 4.8 | 1.6 / 3.6 / 6.7 |
| All core switches (160) | 4.9 / 20.8 / 30.9 | 5.6 / 20.8 / 37.1 |

| Tab | Run 1 | Run 2 |
|---|---|---|
| Loot › Highlights | 4.4 / 6.5 / 10.7 | 4.3 / 4.9 / 11.3 |
| Loot › Explore | 1.6 / 2.8 / 3.0 | 1.6 / 4.0 / 6.5 |
| Runs & DPS › Feed | 3.6 / 7.6 / 8.4 | 3.6 / 7.0 / 7.3 |
| Runs & DPS › Dungeons | 2.5 / 3.8 / 3.8 | 2.6 / 10.2 / 11.3 |
| Runs & DPS › Live meter | 14.5 / 37.7 / 42.1 | 14.3 / 28.4 / 31.9 |
| Runs & DPS › Recordings | 1.2 / 1.6 / 2.2 | 1.4 / 2.6 / 2.9 |

**Met.** Every destination and tab stays far below 100 ms p95, in line with P6a's record.

The longest follow-up EDT event's p95 was 37.8 ms (run 1) and 41.8 ms (run 2), both on Loot › Explore; P6a recorded 33–34 ms. Its
largest part is the Swing timer that rebuilds the saved Explore view on every entry, unchanged since P5b (38–43 ms max). Polish D's
column fill adds one follow-up event: 10–25 ms on Explore, about 10–13 ms elsewhere. Raw logs are in the coordinator's scratchpad
(`p6b/s8-run1.log`, `p6b/s8-run2.log`).

## S6 filter rows

- **Scope:** `FilterBarEvidenceTest` now covers 31 pages and 236 captures, up from 14 and 112:
  - the saved archive pages;
  - the live Scope hosts: Runs table, Timeline, Resources, Chat, Key-pops, Loot Explore, and Party's three tabs;
  - Party saved;
  - Logging's six tabs;
  - Bridge review's Review and Logs.
- **The check** is strict: every visible filter row at 1240×800 font 13 with the drawer closed must pass
  `FilterBarAssert.assertOneRow`. That means every showing child within one control height of the first, and the row under 1.6× a
  control height. On archive pages it also checks that the chip sits in the row the page shows, and that the workspace row is hidden
  while live. Failures are collected, so one run names every page that wraps.
- **What it found:** one row wrapped, Quests. Its "Sort by" and "Group by" were label-over-combo panels, a P4 layout the old weak
  check missed.
- **The fix (Polish A):** Group by stays visible with an inline label, Sort by and Pinned first move into ⋯, and Analyst's
  Cards|Table sits on the right. The Quests row is 35 px tall in every state.
- **Wave E result:** on `5dbae2c`, alone, 1 test / 0 failures. Every row is one row, live and saved, on all 31 pages.

## S7 core destinations

Six core rows, and Advanced (5), are unchanged by sidebar drag. A drop never pins, and a drop over Advanced places the row last in
the core list (`WorkspaceShellDragTest`). The final set's sidebar captures show the default order.

## Final screenshot set

`ui.FinalScreensEvidenceTest` (Task 18) runs only with `REALMSHARK_FINAL_SCREENS=1`, and needs `cleanTest` after an ungated run. It
builds the real shell (`TomatoGUI.createWorkspace`, preview) over one synthetic history built from public fixtures.

**Captures:** 155 in all.
- **Primary (130):** 1240×800 font 13, dark: every page and tab in Simple and Analyst, and each Analyst-only view once. Live and
  saved on all seven archive pages. Also the Scope menu open, a filter drawer open, the sidebar with Advanced, the Alt+M menu, a
  sidebar drag with its drop line, and the compact rail.
- **Bridge Review (1):** populated over a fake service in a `TestPages` shell, labeled as such.
- **Compact (12):** 680×520 font 18, Analyst.
- **Light (12):** 1240×800 font 13, Simple.

**Checks per capture:**
- the shell is exactly W×H;
- the selected page and tab IDs;
- the mode, with Analyst-only tabs absent in Simple;
- nothing scrolls sideways;
- no view-state warning;
- no absolute path in any shown text;
- the strict one-row check on every visible filter row, live and saved.

Captures are root-pane paints, so there is no title band. Since Polish E they use `paintAll`, because `printAll` never draws a
table's selection.

**Runs:**
- **First run** (on `c3d4d4c`): 7 failed checks, all the Quests row.
- **After the polish** (on `5dbae2c`, alone): 155 captures, 0 with failed checks, 206 s.
  - The coordinator re-checked saved Runs: a selected row now shows its highlight, there is no pinned hash in Simple, Cancel is
    disabled, and the table reaches the right edge.
  - Light Party after a live theme switch shows no nested frames.
  - The Scope menu stays inside the window.

The captures are not committed. The index and contact sheet were sent to the user, and a copy stays in the coordinator's scratchpad
(`p6b/final2/`).

## Coordinator review of the final set

The coordinator looked at the captures (Home, Runs, Loot, Chat, Key-pops, Party, Timeline, Quests, the menus and the light set) and at
the implementer's page-by-page list, and decided each finding.

- **Polish A (Quests), `a2dd467`:**
  - one filter row in both modes (see S6);
  - Simple no longer shows the capture-context line (account, capture generation, provenance), raw quest IDs or server categories;
  - the Planner shows a short account key, with the full key only in Analyst's tooltip.
- **Polish B (kit), `0a26d35`:**
  - The Scope menu opens right-aligned under its chip, above it when only that fits, and stays inside the window.
  - The Filters toggle is selected and drawn pressed while its drawer is open (a FlatLaf style class, so the Simple/Analyst segments
    are unaffected).
  - An unfocused table's selection was already visible in the app. The capture helper's `printAll` hid it, and Polish E fixed that.
- **Polish C (Simple detail and copy), `3d1daaa` + `e25b045`:**
  - The pinned revision hash leaves the Simple count lines of saved Runs, Party, Resources, Timeline, Chat and Key-pops.
  - Key-pops' live period lines are Analyst-only.
  - "Cancel linked export" is disabled while idle.
  - Chat's receipt times read uniformly as local `yyyy-MM-dd HH:mm:ss`.
  - The live summary no longer repeats the collection state under its checkbox.
  - The roster table reads "—" for unknown values.
  - Saved Key-pops uses live's column order and type badges.
  - Two stale strings are fixed: "File › Start capture connection" and "Party builds".
  - Two scroll panes keep an empty border.
- **Polish D (tables), `e9c9364`:**
  - Saved tables built by `HistoryTables` widen cut columns first, then one text column, up to the viewport.
  - The fitted widths are never saved and never change on a mode switch, and the space goes back when the window narrows.
  - Fame history uses the shared fill.
- **Polish E (borders and captures), `ec9d03e`:**
  - Five page scroll panes keep an empty border. FlatLaf reinstalled its outline on a `null` border at a live theme switch, which drew
    2–4 nested frames in the light set.
  - `captureRoot` paints instead of printing.

**Accepted as they are:**
- **Timeline's empty band in Simple.** The Analyst-only Meaning column is hidden and widths never change on a mode switch, by user
  decision.
- **Header wrap at 680×520 font 18.** The header wraps to two or three lines, as before P6b.
- **Home's Now card in preview.** It stays empty ("Nothing live yet") because capture is not running; this is a harness limit.
- **Runs' duration unit** stays a combo on the status line, while Party's is a ⋯ submenu.

**Left for later:**
- the saved Runs detail pane's session and revision IDs in Simple;
- the pinned hash in the saved tables' accessible description;
- the Planner's Table view Stable ID column in Simple;
- the live Rarity column's "Unknown" for potions;
- Chat saved's row of six action buttons;
- Logging's short table, Settings › Notifications' empty area, the position of Pets' feeding calculator, and the height of quest plan
  cards.

## PR review

_Filled in after the PR._

## Deferred scope

- **Not in P6** (user decision, 2026-09-29):
  - a font-size control in Settings › Appearance;
  - a search box inside the Settings page;
  - the `AGENTS.md` pointer line.
- **Kept deferred:**
  - the quest expiry countdown (S3's expiry half);
  - typed `Destination`s for Settings, Chat and Key-pops;
  - a shared base for `RunsDpsPage` and `LootPage`;
  - Resources & buffs stays nested in the Live meter;
  - building `CombatMeterData` off the EDT.
- **Follow-up suggested:** saved-history write failures (`SessionStore.error()`) are shown only by the unmounted legacy
  `SessionPanel` and the Logging summary. This predates P6b.
