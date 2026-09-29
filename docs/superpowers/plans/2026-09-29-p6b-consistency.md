# P6b Consistency: one Scope ▾ filter row, Advanced page restyles, mode-aware columns, sidebar drag, final screenshots — Implementation Plan

> **For agentic workers:** implement with subagent-driven development: one fresh implementer subagent per task, in an isolated worktree, working test-first; the coordinator (Claude) reviews every task's diff and merges it. This is a **contract plan** (as P3b to P6a): it fixes the decisions, file ownership, names, signatures, behavior and the tests each task must add, and leaves the code to the implementer. Research notes with verbatim current code are the implementer's reference for existing APIs (see "Sources").

**Goal:** The consistency half of P6 (spec §3.2, §4.1, §4.4, §5.5, §5.6, §6.7, §10; roadmap P6 items 2, 4 and 6):
- **One filter row with a Scope ▾ chip** (live vs saved plus the session picker) on the seven archive pages: Runs, Timeline, Resources, Party, Loot, Chat and Key-pops. Loot's two synced view selectors become one.
- **Advanced page restyles:** Party, Timeline, Key-pops, Logging, Bridge review and Settings › Notifications. "Save view state" rows and column tools fold into ⋯, and Build's metric cards become StatTiles.
- **Mode-aware tables:** relative times in Simple and Analyst-only columns, through one kit helper.
- **Sidebar drag** for core rows, and fixes and tests for the existing tab drag.
- **Small fixes:** Esc closes an open filter drawer, and light-theme tiles and cards get a subtle outline.
- **The evidence findings** P6a left open.
- **The final Simple/Analyst screenshot set of every page, and the docs pass.**

**Scope (user decisions, 2026-09-29):** P6b is **one PR**. It follows P6a (PR #27, merge `82983c2`).

**Architecture:**
- **Wave A (parallel, 5):**
  - the Scope chip core in `ArchiveWorkspace`, plus Esc for filter drawers (Task 1);
  - kit: mode-aware time columns, Analyst-only columns, painted item wells and light-theme outlines (Task 2);
  - sidebar drag (Task 3);
  - tab drag fixes, plus Notifications as customizable tabs (Task 4);
  - the Logging restyle (Task 5).
- **Wave B (parallel, 5):**
  - column tools into ⋯, and mode-hidden layout rules (Task 6);
  - Party: Scope host and restyle (Task 7);
  - the Bridge review restyle (Task 8);
  - loot values, wording and Highlights polish (Task 9);
  - Build tiles and cleanups (Task 10).
- **Wave C (parallel, 4):** each page's Scope host is paired with its restyle under one owner:
  - Runs, Timeline and Resources (Task 11);
  - Chat, with Settings › Chat (Task 12);
  - Key-pops (Task 13);
  - Loot Explore (Task 14).
- **Wave D (parallel, 4):**
  - strict S6 and removing the last `HistoryTables.controls` (Task 15);
  - the information-architecture docs (Task 16);
  - the module docs (Task 17);
  - the final screenshot set (Task 18).
- **Wave E (coordinator):**
  - review every final capture and run polish tasks for the findings;
  - S8 alone, twice;
  - the validation record and the recovery-doc refresh;
  - the final suite, `shadowJar` and the JAR smoke, then the PR.
- **Performance and threading:** no task adds a synchronous history read on a page or tab show (S8). Scope changes go through the existing `ArchiveWorkspace.request()` path, and moving the chip costs one revalidate.

**Tech Stack:** Java 17, Swing, FlatLaf 3.5.4, Gson 2.9.1, JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md`:
- §1: honesty invariants; S3, S6, S7, S8.
- §3.2: Simple/Analyst.
- §4.1: sidebar.
- §4.4: customizable sub-tabs.
- §5.1–§5.7: tokens, components, tables, filter bar, Scope ▾, `DisplayValue`.
- §6.7: Advanced pages, Settings.
- §7: states.
- §9: performance.
- §10: accessibility, including WCAG 2.2 SC 2.5.7 (dragging alternatives).

**Depends on:** P6a merged (PR #27, merge `82983c2`). The plan's base is `b5dcd24` (`82983c2` plus the P6a record commit).

**Sources (read before coding; verbatim current code with file:line):** four research notes, kept in the coordinator's scratchpad and handed to each implementer:
- **R1:** the Scope ▾ chip.
- **R2:** the Advanced restyles and the Analyst-only/relative-time kit helper.
- **R3:** sidebar drag, tab drag and the P6a leftovers B1–B14.
- **R4:** the final screenshot set, the docs pass and the success criteria.

Where a note and the code disagree, the code wins. Also read the P6a plan's "Global Constraints", "Decisions" and "Wave A–D outcomes" (`docs/superpowers/plans/2026-09-29-p6a-structure.md`) and the P6a validation record's "Coordinator review of the evidence" (`2026-09-29-p6a-validation.md`).

---

## Global Constraints

- **Branch and PR.**
  - Integration branch: `claude/redesign-handoff-next-steps-edrr7w`, at `b5dcd24` on top of merged `main` `82983c2`.
  - Implementers commit on their own worktree branch (`p6b/tN-<slug>`) and never push.
  - The coordinator merges each reviewed task with `--no-ff` and pushes. Once the plan is done and pushed, it opens the P6b PR against `main`. This is the user's standing permission: open the PR so Codex can review, fix its comments, and merge once they are fixed or there are none.
  - No direct commits to `main`, no force pushes, no hook bypasses.
- **Commands (Linux cloud session).** JDK 17 full JRE. Run from the worktree root, with `tN` the task number:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 LC_ALL=C.UTF-8
  xvfb-run -a -s "-screen 0 1920x1200x24" sh ./gradlew --console=plain -PrealmSharkBuildDir=build/p6b-tN --project-cache-dir build/p6b-tN-cache -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64 <tasks>
  ```
  Steps abbreviate this as `GRADLE <tasks>`.
- **Pre-existing Linux/Xvfb failures.** These are not P6b's to chase unless a task touches them:
  - `StatisticsArchiveNativeTest.actualLootFactory…`;
  - `QuestConsistencyTest.nameTypesDialog…`;
  - `ChatFiltersTest.editorSavesRules…` and `ChatConsistencyTest.nativeFilterDialog…` (window focus or dialog reachability without a window manager). Task 12 must keep the dialog mode of `ChatFilterPanel` unchanged.
  - the load-sensitive `HomeRefreshTimingTest.liveTicks…`;
  - the `ContentStyleTest` resize race, which Task 10 fixes.

  A failing test is never "a flake" without a root cause: rerun it once alone, then fix it or report it.
- **Validation limits (AGENTS.md, 2026-09-26 policy).**
  - Focused tests per task, combined focused runs at each wave merge, and one final full suite (coordinator, Wave E).
  - The baseline is P6a's final suite on `0625e34` (2020 tests / 4 failures / 0 errors / 5 skipped: the four known failures) plus the Codex fix's focused run (152 / 0). No new baseline run.
  - Never run `scripts/Set-CiDisplay.ps1`, live capture or bridge deliveries. Use synthetic fixtures, isolated history folders and isolated preferences only.
- **Commits.** Explicit paths in `git add`; an imperative subject and a body saying why; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.
- **Existing assertions.** Report every change to an existing assertion as one of:
  - **add beside**;
  - **replace (form only)**: the same behavior reached through a new form, for example a `JLabel` metric read as `StatTile.valueText()`, or a button reached as a ⋯ item;
  - **replace**, with the reason. Use it only where behavior intentionally changes, for example a time cell in Simple now reads relatively.

  Never weaken an assertion. When a Simple-mode assertion on a time cell must stay absolute, either run it in Analyst or use a timestamp at least 7 days old (rendered as a date). Say which.
- **File ownership.** Within a wave, a file listed under one task's **Owns** is edited by that task only. A task that needs another file stops and reports. Files owned in an earlier wave are free again in a later wave. Test files are owned the same way as main files.
- **Scope row semantics.** Keep these unchanged for existing callers:
  - `ArchiveWorkspace.selectSession(CURRENT)` goes **live**. `DpsGUI`, `TomatoGUI.browseSavedHistory` and `HistoryLibrary` rely on it.
  - Saved-current (archive + CURRENT) is the default first saved state.
  - Routes, Back and restores all pass through `request()`.
  - The persisted state is unchanged: `ux.archive.<module>` (`archive`, `query.scope`), `ux.archive.loot-live` (the numeric live index under `loot-views`), `ux.archive.loot` (`facets.view`, tab, tables) and `ui.filters.<bar>.open`.
- **Mode-aware tables.**
  - **Renderer only.** Model values, sorting, search string converters, Copy (`HistoryTables.selectedText`) and every export keep raw absolute values.
  - **No refitting.** Column widths are never refitted on a mode switch, and no saved layout records a mode-driven width or a mode-hidden column as user-hidden.
  - **Search.** Typing "12 min ago" matches nothing; search keeps matching the absolute text.
- **Tab and view restore.** Startup and saved-state restore only select. Only explicit navigation (a route, Back, a search entry, a card or tile action) may bring a hidden tab forward. This includes Notifications' sections and Key-pops' saved mode tab.
- **Preference hygiene.** Tests restore every preference they change. Use `@After` or `finally`. The keys are:
  - tabs: `ui.tabs.*` (including the new `ui.tabs.notifications` and `ui.tabs.keypops-saved`);
  - navigation: `ui.nav.order`, `ui.nav.hidden`, `ui.nav.pinned`;
  - filters: `ui.filters.*` (including the new `logging`, `bridge-review`, `bridge-logs`, `inspect-runs` and `ability` bars);
  - saved state: `ux.archive.*` and the roster view-state keys (`activity-live-*`, `inspect-live-*`);
  - the Logging views keys;
  - Filter Loot: `filterWhiteBag` … `filterBrownBag`;
  - Highlights: `ui.loot.highlights`;
  - theme: `Themes` (theme and contrast);
  - mode: `ui.mode` and `DisplayModeModel.application()`.
- **Honesty (spec §1).** Unknown is never shown as zero. A null count or ID is "—". A bag without a known area is "Unknown area", and a bag without a saved name is "Unknown bag (name not saved)". A relative time for an unknown instant keeps the renderer's own "Unknown time" wording, dimmed. Counts stay **observed drops, not pickups**. Links are exact.
- **Privacy (AGENTS.md).** Show file names, never absolute paths. Evidence and docs use synthetic names only; never publish personal history, settings or user-data paths.
- **Persisted types.** P6b adds no persisted type and changes no history format. New state is plain preferences: tab orders, drawer-open keys and the Key-pops saved tab group.
- **Threading.** The EDT only applies immutable models. Sources read off the EDT on daemon workers with a newest-result generation guard. The relative-time repaint is one shared `javax.swing.Timer` and never reads data.
- **Styling.** Colors come from `Tokens`, fonts from `Type`/`KitText`/`ContentStyle.font`, and motion only through `Motion.run`. Buttons are `KitButton`s (Primary, Secondary, Ghost or Danger), and KPI numbers use `StatTile`. Filter rows use `FilterBar`: one row at 1240×800 font 13 with the drawer closed. Danger actions confirm.
- **Stable names (kept).**
  - Workspaces and Loot:
    - `<module>-session-view` (workspaces);
    - `loot-views` (the one Loot selector);
    - `loot-archive-view` (Dungeons › Analysis and Characters › Fame history only);
    - `loot-archive-view-caption` (moves into the lead panel).
  - Key-pops, Build and Logging:
    - `keypop-metric-<i>` (now a `StatTile`);
    - `keypop-archive-tabs` (still a `JTabbedPane`, now the component of a `CustomizableTabs`);
    - `myinfo-metric-<i>` (Details buttons);
    - `logging-reset` (the same instance);
    - `logging-facet-*`.
  - Bridge: `bridge-search`, `bridge-status-filter`, `bridge-totals`, `bridge-save`, `bridge-send`, `bridge-alert-draft`, `bridge-saved-*`.
  - Party: `inspect-tabs`, `inspect-roster`, `inspect-live-roster-save-state` and `-reset-state` (now ⋯ items), `inspect-live-runs`, `inspect-live-container`.
  - Activity bars: `activity-runs`, `activity-timeline`, `activity-combat`.
  - Live bars: `chat-live`, `keypops-live`, `loot-live`.
  - Sidebar and shell: the `nav-`, `compact-nav-` and `nav-menu-show-` prefixes, and `settings-section-<id>`.
  - Visible labels: "Pause this view", "Copy names", "Copy all (JSON)", "Actions…", "Requirement details", "Reset display filters", "Diagnostic coverage".
- **Encoding.** Sources are UTF-8. The literals `·`, `…`, `‹`, `›`, `—`, `×`, `≈`, `◐`, `▾`, `▸` and `⟳` are fine.

## Decisions recorded for this phase

User decisions (2026-09-29, P6b):
- **One PR** for all of P6b.
- **Relative times and Analyst-only columns, as proposed.**
  - Timeline, Key-pops, Party runs, Bridge and saved activity (the shared saved table behind Runs' Table view, Resources and Party saved) show relative times such as "12 min ago" in Simple, with the absolute time in the tooltip.
  - Analyst and every export stay absolute. Logging stays absolute.
  - Bridge saved **Session/Journal** and Timeline **Meaning** are Analyst-only.
  - Column widths never change on a mode switch.
- **Sidebar drag: whole row, no grip.**
  - The drag starts after the 5 px system threshold and shows a violet drop line. Esc cancels.
  - Core rows only, including pinned Advanced rows. A drop never pins.
  - The keyboard (Ctrl+Shift+Up/Down) and the right-click menu keep working.
- **Extras: Esc and light contrast only.**
  - Esc closes an open filter drawer.
  - Light-theme tiles and cards get a subtle outline.
  - **Not chosen, so deferred:** a font-size control in Settings › Appearance, a search box inside the Settings page, and the `AGENTS.md` pointer line. **`AGENTS.md` is not edited.**

Earlier P6 decisions still in force (2026-09-29):
- **Scope row:** the spec's single "Scope: … ▾" chip.
- **Resources & buffs** stays nested in the Live meter.
- **Logging** keeps absolute times.
- **Customizable tabs:** Key-pops' saved modes and Notifications' sections become customizable tabs.
- **"Save view state" rows** fold into ⋯.
- **Drag:** sidebar drag covers core rows only; the existing tab drag gets tests.
- **"Both variants"** means Simple and Analyst.

Coordinator decisions (the research's recommended options):
- **Chip labels (R1 D1):**
  - `Scope: Live`;
  - `Scope: Saved · this session`;
  - `Scope: Saved · all sessions`;
  - `Scope: Saved · <session label>`, cut at about 18 characters with "…";
  - `Scope: Saved · selected session`, for a scope not in the list.

  The tooltip and accessible description carry the full wording (R1 §2.2). The accessible name is "Scope".
- **The chip moves between two bars; both bars stay (R1 §0.3, §2.6).**
  - While live, the chip is lent to the live panel's own `FilterBar` (its scope slot), and the workspace bar is hidden.
  - While saved, it sits in the workspace bar's scope slot.
  - At narrow widths it falls back into the visible bar's search slot.
  - No `OverflowMenu.borrow`: host pages keep their own ⋯ items (R1 D2).
- **⋯ Refresh and the Scope menu (R1 D3, D7).**
  - The saved-mode ⟳ becomes a ⋯ item "Refresh", first in the workspace ⋯ and shown in saved mode only.
  - The Scope menu gains "Refresh session list" and takes "History library…", which leaves the workspace ⋯.
- **Live bars move to the first row (R1 D4, D5).**
  - Loot's and Key-pops' live bars move above their tiles.
  - Party › Runs gets an `inspect-runs` FilterBar and Ability Use a `FilterBar("ability")`. Both host the chip.
  - Runs' Cards view has no chip, because the feed reads every saved session (R1 D6). The legacy `SessionPanel` stays (R1 D8).
- **Loot's one selector** is the dashboard's `loot-views`. `ArchiveWorkspace.lead(...)` places it at the front of the visible bar's search slot in both modes, and `LootExploreModel` is its controller (R1 §3).
- **Time columns and analystOnly (R2 decision 3).** Timeline "Assignment" stays in both modes. There is no automatic hiding of `ColumnKind.ID` columns: `analystOnly` is opt-in per table. Loot's archive table keeps its current columns in P6b.
- **Party (R2 decisions 5 and 6).**
  - Gear shows tier-bordered painted wells with the enchant glow kept inside the well.
  - The skin sprite moves from Player to Class.
  - The three roster view-state rows fold into ⋯.
- **Logging (R2 decision 7).**
  - The collection toggle and Pause sit in the bar's trailing slot. View states, sampling, diagnostic samples and Clear data move into ⋯.
  - The export row below the table stays as it is, with its privacy note. It is not a filter row.
  - `LoggingViewStateTest` calls the view-state methods directly.
- **Timeline (R2 decision 8).**
  - Collection and Pause become one status line under the filter row.
  - Visit and kind move into a Filters drawer with chips.
  - The export moves into ⋯.
- **Build tiles (R2 decision 9)** read "Weapon DPS" and "MP/sec" with "≈" values. The assumptions go in the tooltip.
- **Key-pops' saved modes (R2 decision 4)** are a per-render `CustomizableTabs` group `keypops-saved`, and the query's mode tab is revealed as explicit content.
- **Sidebar drag (R3 decisions 1 and 3).**
  - The compact rail drags too.
  - Leaving the sidebar horizontally cancels. Vertical overshoot clamps, and autoscroll handles the edges.
- **Tab drag (R3 decision 4)** keeps live swapping, made stable with midpoint hysteresis. It saves once on release, and Escape restores the order.
- **Unknown bag row (R3 decision 5):** always last under any explicit sort, through an `ArchiveAdapter.sortsLast` hook.
- **Home's Notable loot tile (R3 decision 6)** passes its Today / This session choice to Highlights, which applies and saves it as a click would.
- **"Unknown area" (R3 decision 7)** is a display-only relabel. The saved "Unknown" keys and facets are unchanged.
- **Cleanups (R3 decisions 8 and 9):**
  - The `RunsDpsPage`/`LootPage` base stays deferred, because no third composite-Back page appears.
  - `ContentStyleTest` is hardened in P6b (Task 10).
- **Light outline:** a 1 px `BORDER_SUBTLE` outline (`BORDER` under Increase contrast), drawn **in the light theme only**, on StatTiles and on painted cards that have no outline today. Dark captures do not move.
- **Esc for filter drawers.**
  - While a bar's drawer is open and focus is inside the bar, Esc closes the drawer, as a toggle click would, and focuses the Filters toggle.
  - While the drawer is closed, the key is not consumed.
  - A focused component's own Esc binding keeps precedence.
- **Final screenshot set (R4 D1–D4, D9).**
  - About 145 captures: 121 primary (every page and tab, 1240×800 font 13, Simple and Analyst), 12 compact (680×520 font 18 Analyst, each destination's landing tab) and 12 light (1240×800 font 13 Simple).
  - Opt-in with `REALMSHARK_FINAL_SCREENS=1`.
  - Root-pane captures, so no title band and no padding.
  - Bridge Review is shown as the real shell's unconfigured state, plus one populated capture over a fake service in a `TestPages` shell, labeled as such.
  - The screenshots are not committed. The coordinator sends the contact sheet to the user and summarizes it in the PR.
- **Docs (R4 D5, D6).** Delete the two unreferenced "(1)" duplicate docs. `UI-CONSISTENCY.md` gets a history banner plus line fixes.

---

## File map and ownership

Paths are under `src/main/java/tomato/` and `src/test/java/tomato/` (package `ui` tests under `src/test/java/ui/`). The coordinator owns the roadmap, handoff, checkpoint, `docs/UX-HANDOFF.md`, `docs/UX-EXECUTION.md`, `docs/UX-CHECKPOINT.json` and the validation record.

### Wave A

| Task | Owns (main) | Owns (test) |
|---|---|---|
| 1 Scope chip core, Esc | new `gui/history/ScopeChip.java`, new `gui/history/LiveFilterHost.java`; `gui/history/ArchiveWorkspace.java`; `gui/kit/FilterBar.java` (`searchSlot()`, Esc) | new `history/ScopeChipTest`, new `kit/FilterBarAssert` (test helper), `kit/FilterBarTest` (add beside), `history/ArchiveNativeSupport`, `history/ArchiveFilterBarTest` (add beside), `chat/ChatBookmarkIntentTest`, `security/InspectArchiveClientTest`, `security/InspectArchiveNativeTest`, `history/ArchiveLifecycleRegressionTest`, `history/ArchiveCompoundControlTest`, `stats/DungeonAnalysisTest`, `stats/CharacterFameHistoryTest`, `stats/LootExploreTest` (the 4 button lines), `ui/LootEvidenceTest` (1 line), and any other test that looks up the old strip (R1 §5.1) |
| 2 Kit tables, wells, outlines | `gui/kit/KitTables.java`, `gui/kit/ItemSlot.java`, `gui/kit/StatTile.java`, `gui/kit/Tokens.java` (`outline`); the outline call in `gui/quest/PlanCardRenderer.java`, `gui/glance/character/ExaltTileRenderer.java`, `gui/runs/RunCardRenderer.java`, `gui/glance/home/NowCard.java` | new `kit/KitTablesModeTest`, new `kit/ItemSlotIconTest`, new `kit/LightOutlineTest`; `kit/ContainersTest` (add beside) |
| 3 Sidebar drag | `gui/modern/NavLayout.java`, `gui/modern/WorkspaceShell.java` | `modern/NavLayoutTest` (add beside), new `modern/WorkspaceShellDragTest` |
| 4 Tab drag, Notifications tabs | `gui/kit/CustomizableTabs.java`, `gui/notifications/NotificationsGUI.java` | `kit/CustomizableTabsTest` (add beside), new `notifications/NotificationsTabsTest` |
| 5 Logging restyle | `gui/logging/LoggingGUI.java`, `gui/logging/LoggingViewState.java` | `logging/LoggingViewStateTest`, `logging/LoggingWorkflowTest`, new `logging/LoggingFilterBarTest`; `logging/LoggingGuiTest`, `logging/LoggingNativeTest`, `logging/WaveThreeEvidenceTest`, `logging/CollectionEvidenceTest` (only if a lookup moves) |

### Wave B

| Task | Owns (main) | Owns (test) |
|---|---|---|
| 6 Column tools, mode-hidden layouts | `gui/history/HistoryTables.java`, `gui/history/ArchiveFilters.java`, `gui/history/ArchiveWorkspace.java` (tools section), `gui/kit/OverflowMenu.java` (a replaceable section), `gui/roster/RosterViewState.java` (mode-hidden widths), `gui/chat/SocialQueryControls.java`; the call sites in `gui/activity/ActivityArchiveClient.java`, `gui/stats/LootArchiveClient.java`, `gui/chat/ChatArchiveClient.java`, `gui/keypop/KeyPopArchiveClient.java` | new `history/HistoryTablesColumnToolsTest`, `history/HistoryTablesKindTest`, `history/ArchiveNativeSupport`, `chat/SocialArchiveNativeTest`, `roster/RosterViewStateTest` (add beside), `kit/ControlsTest` or a new `kit/OverflowMenuTest` |
| 7 Party | `gui/security/SecurityGUI.java`, `gui/security/ParsePanelGUI.java`, `gui/security/InspectRunsPanel.java`, `gui/security/AbilityEvidencePanel.java` | `security/InspectViewStateTest`, `security/ParsePanelRefreshTest`, `security/InspectTableKindsTest`, `security/InspectRosterFilterBarTest` (add beside), `security/WaveThreeEvidenceTest` (if a lookup moves), new `security/PartyRestyleTest`, new `security/SecurityScopeHostTest` |
| 8 Bridge review | `gui/bridge/BridgeReviewGUI.java` | new `gui/bridge/BridgeFilterBarTest`, new `gui/bridge/BridgeTableKindsTest`; `gui/bridge/BridgeUiTest`, `BridgeReportingTest` (only if a lookup moves) |
| 9 Loot values, Highlights polish | `gui/stats/LootFacts.java`, `gui/loot/HighlightsModel.java`, `gui/stats/LootFacetControls.java`, `gui/stats/StatisticsArchiveAdapter.java`, `gui/stats/LootArchiveAdapter.java`, `history/archive/ArchiveAdapter.java`, `history/archive/ArchiveResult.java`, `gui/loot/DungeonStripRenderer.java`, `gui/loot/NotableDropRenderer.java`, `gui/loot/LootFocus.java`, `gui/loot/LootPage.java`, `gui/loot/LootHighlights.java`, `gui/glance/home/TodayTiles.java`, `gui/glance/home/HomeActions.java`, `gui/TomatoGUI.java` (the Home loot action only) | `stats/LootFactsTest`, `loot/HighlightsModelTest`, `loot/LootHighlightsTest`, `loot/NotableDropRendererTest`, `loot/LootPageTest`, `stats/LootArchiveQueryTest` (add beside), `history/archive/ArchivePipelineTest` (add beside), `glance/home/TodayTilesTest` (add beside) |
| 10 Build tiles, cleanups | `gui/myinfo/MyInfoGUI.java`, `gui/runs/RunFeedSource.java`, `gui/runs/SessionFacts.java` | `myinfo/MyInfoFormattingTest`, `myinfo/BuildEstimatesTest`, new `myinfo/BuildTilesTest`, `myinfo/MyInfoLayoutEvidenceTest` and `myinfo/MyInfoEvidenceTest` (only if a lookup moves), `runs/RunFeedSourceTest`, `ui/ContentStyleTest` |

### Wave C

| Task | Owns (main) | Owns (test) |
|---|---|---|
| 11 Runs, Timeline, Resources | `gui/activity/ActivityPanel.java`, `gui/activity/ActivityArchiveClient.java`, `gui/runs/RunFeedView.java`, `gui/activity/ResourceWindowPanel.java` | `activity/ActivityLiveFilterBarTest` (add beside), `activity/ActivityFormattingTest`, `runs/RunFeedViewTest` (add beside), new `activity/ActivityScopeHostTest`, new `activity/TimelineRestyleTest`; `activity/ActivityModulesTest`, `activity/ActivityLiveStateTest`, `activity/ActivityArchiveUiTest` (only if a lookup moves) |
| 12 Chat, Settings › Chat | `gui/chat/ChatGUI.java`, `gui/chat/ChatExplorer.java`, `gui/chat/ChatFilterPanel.java`, `gui/settings/ChatSection.java`, `gui/TomatoGUI.java` (only if the chat editor supplier type changes) | `chat/ChatLiveFilterBarTest` (add beside), `settings/ChatSectionTest`, new `chat/ChatScopeHostTest` |
| 13 Key-pops | `gui/keypop/KeypopGUI.java`, `gui/keypop/KeyPopDashboard.java`, `gui/keypop/KeyPopArchiveClient.java` | `keypop/KeyPopTest`, `keypop/ExactContributorTest`, `keypop/KeyPopFormattingTest`, `keypop/KeyPopHistoryPersistenceTest`, `keypop/KeyPopLiveFilterBarTest` (add beside), `keypop/KeyPopNotificationHandoffTest` (only if a lookup moves), new `keypop/KeyPopRestyleTest` |
| 14 Loot Explore | `gui/stats/LootDashboard.java`, `gui/stats/LootExploreModel.java`, `gui/stats/LootArchiveClient.java`, `gui/stats/HistoricalStatistics.java`, `gui/stats/StatisticsLiveState.java`, `gui/stats/CharacterFameHistory.java`, `gui/history/ArchiveWorkspace.java` (the status caption only) | `stats/LootExploreTest`, `stats/LootArchiveStateTest`, `stats/StatisticsArchiveNativeTest` (the selector line), `stats/LootLiveFilterBarTest` (add beside), `stats/LootDashboardTest` (add beside), `stats/CharacterFameHistoryTest` (add beside), `ui/LootEvidenceTest`, `history/FilterBarEvidenceTest` (the loot lines only) |

### Wave D

| Task | Owns (main) | Owns (test) |
|---|---|---|
| 15 Strict S6, last `controls` | `gui/history/HistoryTables.java` (delete `controls`) | `history/FilterBarEvidenceTest`, `kit/FilterBarAssert`, `ui/LootEvidenceTest` (its one-row check), any test still calling `controls` |
| 16 IA docs | `docs/UI-REDESIGN.md`, `README.md`, `docs/UI-CONSISTENCY.md` | — |
| 17 Module docs | `docs/SESSION-HISTORY.md`, `docs/CHAT.md`, `docs/LOOT.md`, `docs/ACTIVITY.md`, `docs/DPS-METERS.md`, `docs/LOGGING.md`, `docs/KEY-POPS.md`, `docs/BRIDGE.md`, `docs/NOTIFICATIONS.md`, `docs/CHARACTERS.md` | — |
| 18 Final screenshot set | — | new `ui/FinalScreensEvidenceTest`, new `ui/EvidenceWorkspace`, new `ui/FinalScreensFixture`, `ui/VisualEvidence` (`captureRoot`, shared `nothingSideways`) |

---

## Wave A

### Task 1: The Scope chip core, and Esc for filter drawers

**Owns:** see the file map. **Reads:** R1 §0–§2, §5.1 and §6 (all of them).

- **`ScopeChip`** (`tomato.gui.history`, public final, EDT only). It extends `KitButton` (Secondary) with a trailing `LineIcon.CHEVRON_DOWN`.
  - **Constructor:** `ScopeChip(String module, boolean savedOnly, Actions actions)`. It is named `<module>-scope`, and its menu `<module>-scope-menu`.
  - **Methods:**
    - `void show(boolean archive, String scope, String currentId, List<SessionChoice> recent)` sets the label, tooltip, accessible description and the radio state, silently.
    - `JPopupMenu menu()` is built eagerly, so tests `doClick` items without showing it.
    - `JMenuItem item(String suffix)` looks up an item by suffix.
  - **Callbacks:** `interface Actions { void live(); void saved(String scope); void library(); void refreshList(); }`.
  - **Menu (R1 §2.3):** radio items in one group.
    - `Live · this app run` (`-scope-live`; absent when `savedOnly`).
    - A disabled header "Saved history".
    - `This session` (`-scope-current`) and `All sessions` (`-scope-all`).
    - Up to 20 recent readable sessions (`-scope-session`, client property `scope` = id, in `reloadCatalog`'s order).
    - A pending "Selected session · <id>" when the scope is not listed.
    - A separator, then `History library…` (`-scope-library`) and `Refresh session list` (`-scope-refresh`).

    Every item sets `html.disable`, because session labels are user-editable.
  - **Keyboard:** the chip is focusable. Enter, Space, Alt+Down and F4 open the menu with the selected radio highlighted. Up and Down move, Enter picks, and Esc closes the menu and returns focus to the chip.
  - **Theming:** `updateUI` updates the menu tree, as `OverflowMenu` does.
- **`LiveFilterHost`** (`tomato.gui.history`, public):
  - `String BAR = "liveFilterBar"`, a property the host fires when its bar changes.
  - `FilterBar liveFilterBar()`, which may return null for "no bar on this tab".
  - The Javadoc warns that re-calling `search()` on the host bar drops lent components.
- **`ArchiveWorkspace`:**
  - **Removed:** delete `browse`, `sessions`, `reload`, `scopeRow` and their listeners; add the chip. Keep no hidden copies for tests.
  - **Calls to the chip:** `syncControls`, `reloadCatalog` and `request()` call `chip.show(...)`.
  - **Actions:**
    - `live()` sets `state.withArchive(false)` and keeps the scope.
    - `saved(CURRENT)` calls the new `showSaved(String scope)`: `admit(state.withQuery(q.withScope(scope)).withArchive(true))`, then persist, sync and `request(false)`.
    - `saved(ALL | id)` calls `selectSession`.
    - `library()` calls `openLibrary()`, and `refreshList()` calls `reloadCatalog()`.
  - **Unchanged:** `selectSession(CURRENT)` still goes live, and `showSaved()` stays.
  - **⋯:** remove "History library…". Add "Refresh" (`<module>-refresh`), first, visible in saved mode only; it reloads the results. Saved views and exports stay. Runs' `run-feed-cards-item` stays first among the page items in saved mode.
  - **New public API:**
    - `lead(JComponent)` places a component first in the visible bar's search slot; null clears it.
    - `firePropertyChange(ARCHIVE = "archive", old, new)` fires from `request()` when `state.archive` flips.
    - `FilterBar liveFilterBar()` returns the host's bar or null.
  - **Placement (`place()`, R1 §2.5):**
    - Call it from `request()` after the card switch, from `fitScope()` and on the host's `BAR` change, wrapped in `FilterChips.keepingFocus`.
    - **While live with a host bar:** the chip goes in the host bar's scope slot, and the workspace bar is hidden.
    - **While saved, or live without a host:** the chip goes in the workspace bar's own scope slot. Without a host, the workspace row shows only chip + ⋯, as today.
    - **Narrow:** when the target bar is too narrow (R1's `narrowFit` formula on the target bar), the chip goes at the end of the target bar's search slot.
    - Resize listeners go on both bars.
    - After a keyboard pick, focus follows the chip into its new bar.
    - Placement must not depend on showing or size: routes, Back and restores while the page is hidden place correctly once it is shown.
- **`FilterBar`:**
  - **`searchSlot()`:** a public getter that returns the component passed to `search()`.
  - **Esc:** a `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` binding on the bar, enabled only while `drawerOpen()`.
    - It closes the drawer the way the Filters toggle does, including the persisted `ui.filters.<bar>.open`, then focuses the Filters toggle.
    - While the drawer is closed, the key falls through.
    - Components with their own `WHEN_FOCUSED` Esc keep it.
- **Tests:**
  - **`ScopeChipTest`:**
    - labels, tooltips and accessible text for each state;
    - item names and the radio state reflecting `show` silently, with no action fired;
    - each item calling its action;
    - `savedOnly` has no Live item;
    - `html.disable` on session items;
    - a long custom label is cut with "…" in the label and full in the tooltip;
    - the keyboard opens the menu;
    - the accessible name is "Scope".
  - **`ArchiveFilterBarTest`** (add beside, with a fake `LiveFilterHost` whose bar has a `WrapRow` search slot):
    - live: the chip is in the host bar's scope slot and the workspace bar is not showing;
    - saved: the chip is in the workspace bar;
    - narrow: the chip is in the search slot;
    - `lead` sits first in the visible bar in both modes;
    - `ARCHIVE` fires once per flip;
    - ⋯ Refresh appears in saved mode only, and the library is in the Scope menu;
    - `showSaved(CURRENT)` stays saved-current, while `selectSession(CURRENT)` goes live;
    - a restore while not showing places correctly once shown;
    - the chip is always a descendant of the workspace.
  - **`FilterBarTest`** (add beside):
    - Esc closes an open drawer, persists it and focuses the toggle;
    - Esc with the drawer closed is not consumed, so a parent binding still fires;
    - a focused field's own Esc binding wins.
  - **`FilterBarAssert`** (new test helper in `tomato.gui.kit`, used by later tasks and Task 15):
    - `assertOneRow(FilterBar)`: every showing child of the bar's top row is within one control height of the first, and the row is under 1.6× a control height tall;
    - `assertChipInVisibleBar(ArchiveWorkspace)`.
  - **The strip lookups (R1 §5.1):** move them to the chip through `ArchiveNativeSupport` (`scope(ws)`, `scopeItem(ws, suffix)`), and let its `action()` also search the Scope menu. These are "replace (form only)".

**Steps:** failing tests; implement; run `tomato.gui.history.*`, `tomato.gui.kit.*`, `tomato.gui.stats.*`, `tomato.gui.security.Inspect*`, `tomato.gui.chat.ChatBookmarkIntentTest`, `tomato.gui.chat.SocialArchiveNativeTest`, `tomato.gui.activity.ActivityArchive*`, `tomato.gui.chat.ShellHookIntegrationTest`, `ui.LootEvidenceTest` and `tomato.gui.ShellSwitchTimingTest`. Commit ("Replace the archive scope strip with one Scope chip and let Esc close filter drawers").

### Task 2: Kit — mode-aware time columns, Analyst-only columns, item wells, light outlines

**Owns:** see the file map. **Reads:** R2 §2 (all), §3; R3 B8.

- **`KitTables.relativeTime(JTable table, Object columnId, DisplayModeModel mode, Function<Object, Long> epoch, String zoneLabel)`:** `columnId` is the `TableColumn` identifier.
  - **Analyst:** the column's existing renderer, untouched.
  - **Simple:** the same renderer's component, with its text replaced by `KitFormat.relative(epoch)` and its tooltip set to the absolute text plus `zoneLabel`. An unknown value keeps the base renderer's wording, dimmed.
  - Model values, sorting, converters, Copy and exports are unchanged. No refit on a mode change.
- **`KitTables.epoch(Object)`:** public. It reads an `Instant`, a `Number` (epoch ms) or an ISO-8601 `String`, and returns null otherwise. It replaces the private helper.
- **`KitTables.analystOnly(JTable table, DisplayModeModel mode, Object... columnIds)`:**
  - It hides the columns in Simple and restores them at their previous view index and width in Analyst.
  - It records them in the table client property `KitTables.MODE_HIDDEN` and brackets each change with `KitTables.MODE_CHANGING = TRUE` so layout listeners can ignore it.
  - It drops `RowSorter` sort keys on hidden columns.
  - `Set<Object> modeHidden(JTable)` returns the currently mode-hidden identifiers.
- **Repaint:** one shared coalescing `javax.swing.Timer` (60 s) repaints registered tables that are showing in Simple. The registry is weak and the timer stops when it is empty. A package-private `tick()` exists for tests. `mode.bind(table, …)` repaints on a switch.
- **`ItemSlot.icon(Icon sprite, String tier, ItemSlot.State state, int size)`:** a painted `Icon` for renderers, generalizing `QuestCardRenderer.slot/slotIcon` (R2 risk 7).
  - `State` is `ITEM`, `EMPTY` or `NOT_CAPTURED`.
  - The tier border comes from `ItemTiers`.
  - `EMPTY` and `NOT_CAPTURED` look different from each other.
  - It fires no accessibility events.
- **`Tokens.outline(Graphics2D g, Shape shape)`:** in the light theme it draws 1 px `BORDER_SUBTLE`, or `BORDER` when `Themes.increaseContrast()`. In the dark theme it does nothing.
  - Callers: `StatTile.paintComponent`, and the card shapes in `PlanCardRenderer`, `ExaltTileRenderer`, `RunCardRenderer` and `NowCard`, the painted surfaces with no outline today.
  - Check each with a light capture. Skip a renderer whose surface already reads clearly, and say so.
  - `NotableDropRenderer` is Task 9's.
- **Tests:**
  - **`KitTablesModeTest`** (fixed `KitFormat` clock):
    - Simple text, tooltip and unknown wording;
    - Analyst matches the base renderer exactly;
    - model value, `getValueAt`, sort order and `HistoryTables.selectedText` are identical in both modes;
    - `tick` repaints only showing Simple tables;
    - `analystOnly` removes and re-adds at the same index and width and drops the sort key;
    - `MODE_CHANGING` is set only during the change;
    - the listener count stays bounded after repeated switches;
    - `epoch` handles `Instant`, `Long`, ISO strings with fractions of a second, and garbage.
  - **`ItemSlotIconTest`:** the tier border color, `EMPTY` versus `NOT_CAPTURED` pixels, the size.
  - **`LightOutlineTest`:** a StatTile painted in the light theme has outline pixels at its edge that differ from the canvas and the fill. In the dark theme the tile's pixels are identical to a tile painted without the outline call. Increase contrast uses `BORDER`.
  - **`ContainersTest`** (add beside): tiles keep `valueText()`.

**Steps:** failing tests; implement; run `tomato.gui.kit.*`, `tomato.gui.runs.RunCardRendererTest`, `tomato.gui.quest.*Renderer*`, `tomato.gui.glance.*` and `ui.KitGalleryEvidenceTest` (review the light gallery). Commit ("Add mode-aware time and Analyst-only columns, painted item wells and light-theme outlines to the kit").

### Task 3: Sidebar drag

**Owns:** see the file map. **Reads:** R3 Part A1 (all).

- **`NavLayout.moveTo(String id, int index)`:** `index` is the final position among the visible core rows (`core()`). It reuses `move`, so it has the same hidden-row semantics and makes one ORDER write. It returns `false` with no write for:
  - Settings;
  - unpinned Advanced rows;
  - hidden rows, including a hidden current page that `applyLayout` still shows;
  - unknown IDs;
  - out-of-range indexes;
  - a no-op.
- **The drag handler in `WorkspaceShell`:** one listener instance on every row, but only rows where `layout.inCore(id) && !layout.isHidden(id)` start a drag.
  - **Start:**
    - The left button only, not a popup trigger.
    - Movement of at least `DragSource.getDragThreshold()` (5 px by default).
    - On start: disarm the button model (`setArmed(false)`, `setPressed(false)`) so the release never selects; set the MOVE cursor.
  - **Gap:** gap *g* is the number of visible core rows whose vertical centre lies above the pointer, clamped to 0…n.
    - A pointer below the core list (over Advanced) clamps to *n*, so a drop there never pins.
    - The final index is `g > from ? g - 1 : g`.
    - No line shows when the final index equals `from`.
  - **Drop line:** `nav` paints a 2 px rounded line in `Tokens.color(Tokens.Role.ACCENT)`, resolved at paint time.
    - Dragging down, it sits at the bottom of row g-1; dragging up, at the top of row g.
    - Repaint only the old and new line rectangles.
  - **Autoscroll:** a 40 ms timer runs while the pointer is within 16 px of the viewport's top or bottom edge. A package-private `autoscrollStep()` exists for tests. `scrollSelected()` is a no-op while dragging.
  - **Cancel:** any of these cancels, writing nothing, clearing the line and ignoring the release:
    - Esc (a `WHEN_FOCUSED` row binding enabled only while dragging);
    - focus loss;
    - a release outside the sidebar horizontally.
  - **Drop:** `change(() -> layout.moveTo(id, index), id)`. The moved row takes focus and stays in view, the selected page does not change, and the compact popup is rebuilt.
  - **Compact rail:** it uses the same code path.
- **Accessibility:** each row's accessible description adds "Ctrl+Shift+Up or Down to move; Shift+F10 for options". The tooltips gain the same hint unless a test pins them; if one does, report it.
- **Tests (add beside; no existing assertion changes):**
  - **`NavLayoutTest`:**
    - `moveToPlacesACoreEntryAtAVisibleIndexPastHiddenRowsAndSavesOnce`;
    - `moveToRefusesSettingsUnpinnedAdvancedHiddenUnknownAndOutOfRange`.
  - **`WorkspaceShellDragTest`:** windowless, with synthetic mouse events and package-private `dropIndicator()` and `autoscrollStep()`. It covers R3's cases 1–8:
    1. A drag drops at the gap, writes ORDER once, leaves the selected page unchanged, and makes the moved row the scroll anchor.
    2. 2 px of jitter is still a click.
    3. Esc mid-drag writes nothing and selects nothing.
    4. Unpinned Advanced rows, Settings and a hidden current row do not drag, but a pinned Advanced row does.
    5. A drop over Advanced places the row last and leaves PINNED unchanged.
    6. Right-button, popup-trigger and middle-button drags do nothing, and the popup still opens.
    7. At 680×520 font 24 with Advanced open, autoscroll works and does not snap back.
    8. The compact rail drags.

    It also covers case 9: the line color in both themes.

**Steps:** failing tests; implement; run `tomato.gui.modern.*`, `ui.ShellRedesignEvidenceTest` and `tomato.gui.chat.ShellHookIntegrationTest`. Commit ("Let core sidebar rows be dragged into a new order").

### Task 4: Tab drag fixes and Notifications as customizable tabs

**Owns:** see the file map. **Reads:** R3 Part A2; R2 §1.6.

- **`CustomizableTabs`:**
  - **Start:** left button only, past `DragSource.getDragThreshold()`.
  - **Moves:**
    - Move only once the pointer crosses the target tab's midpoint in the direction of travel. The midpoint rule is skipped when the pointer is on a different tab run (WRAP layout with several rows).
    - Live moves call an internal `moveLive`, which rebuilds and selects without saving.
    - `save()` runs once on release, if the order changed.
  - **Esc** restores the snapshot order without saving.
  - **Menu:**
    - "Hide tab" is enabled exactly when `canHide(id)` (extracted from `hide`) is true.
    - Every menu item is named `<group>-tab-<action>`.
    - The labels follow spec §4.4: "Show hidden ▸" and "Reset order". If a test pins the old labels, report it.
    - A package-private `JPopupMenu menu(Point)` exists for tests.
- **`NotificationsGUI`:** `CustomizableTabs("notifications")` with the IDs `messages`, `bags`, `key-pops`, `realm-events`, `other-alerts` and `decisions`, in today's order.
  - Keep `final JTabbedPane tabs = group.component()`.
  - `selectSection` matches by ID and uses `show(id); select(id)`, since a route is explicit navigation.
  - Back state stores `selectedId()`.
- **Tests:**
  - **`CustomizableTabsTest`** (add beside) covers R3's cases 1–8:
    1. A drag across the midpoint moves the tab and saves once, on release.
    2. A narrow tab on a wide neighbour does not oscillate. This fails today.
    3. Only the left button drags.
    4. A press within the threshold is not a drag.
    5. Esc restores the order and writes nothing.
    6. Hidden and Analyst-only tabs keep their saved place.
    7. Ctrl+Shift+Left/Right move the selected tab.
    8. The menu enables exactly what applies, including a disabled "Hide tab" for the last steady tab.
  - **`NotificationsTabsTest`:**
    - the order persists under `ui.tabs.notifications`;
    - a route to a hidden section reveals and selects it;
    - Back restores by ID after a reorder;
    - the default order matches the old titles, so the existing index and title loops stay green unchanged.

**Steps:** failing tests; implement; run `tomato.gui.kit.*`, `tomato.gui.notifications.*`, `tomato.gui.keypop.KeyPopNotificationHandoffTest`, `tomato.gui.chat.ShellHookIntegrationTest` and every test class using `CustomizableTabs` (`grep -l CustomizableTabs src/test`). Commit ("Make tab drag stable and save once, and let Notifications' sections be customized").

### Task 5: The Logging restyle

**Owns:** see the file map. **Reads:** R2 §1.4, §1.8.

- **One `FilterBar("logging")`** replaces rows R0–R4:
  - **Search slot:** `[Search][Reset filters]`. It keeps the persistent `logging-reset` instance, and Clear runs the same action.
  - **Drawer:** the per-tab facets (today's visibility rules) and the Observed only / Issues only / Changed only checkboxes. The fields keep their names.
  - **Chips:** `FilterChips.update(...)` replaces the hand-made "label ×" buttons.
  - **Trailing slot:** the collection checkbox and "Pause this view", both showing.
  - **⋯:**
    - "Saved views ▸": Save current view…, Load ▸ with the names, Delete view…, Reset saved state, Retry save;
    - "Save diagnostic samples" (a check item);
    - "Sampling ▸" (Sampled / Detailed radios);
    - "Clear data…" (Danger, confirm).
- **Diagnostic coverage** stays a Ghost link by the summary, with the accessible name "Diagnostic coverage".
- **The export row below the table stays.** Absolute times are kept. On every Logging tab the filter area is one row at 1240×800 font 13.
- **`LoggingViewState`:** the named-view controls become ⋯ items over the same methods. `LoggingViewStateTest` calls those methods, and each such change is "replace (form only)". A status line shows only on failure.
- **Tests:**
  - **`LoggingFilterBarTest`:**
    - one row per tab at 1240×800 font 13, measured in the harness frame;
    - an active facet shows a chip, and removing it clears the facet;
    - Clear and Reset act the same and `logging-reset` is the same instance;
    - the ⋯ items run the view-state methods;
    - Pause and collection are showing;
    - times stay absolute in both modes.
  - The existing Logging tests stay green, with lookups moved only where a control moved.

**Steps:** failing tests; implement; run `tomato.gui.logging.*` and `tomato.gui.chat.ShellHookIntegrationTest`. Commit ("Give Logging one filter row with its view states and actions in ⋯").

### Wave A outcomes (coordinator review)

Merged: Task 3 `63c4de7` + `2f5fdd5` (`57bc14d`), Task 4 `d4d465c` (`ad3f45b`), Task 2 `20a64c4` (`45acf52`), Task 5 `8572452` +
`294ac99` (`0562e43`) and Task 1 `436ead8` (`f8acc19`). Combined focused run on `f8acc19` (history, kit, stats, security, chat,
activity, modern, notifications, logging, glance, runs, loot, character, quest, keypop, the shell timing, route and journey tests and
the Loot, shell, workspace and Runs evidence tests): 1,326 tests, 4 failures, exactly the four known Linux/Xvfb ones.

- **Task 1:**
  - The chip, `LiveFilterHost`, `showSaved(scope)`, `lead`, `liveFilterBar` and `ARCHIVE` are as planned. `FilterBarAssert` exists
    for later tasks.
  - Deviations accepted: the chip opens its menu from a key listener, because an InputMap entry for Enter would shadow the open
    menu's own Enter. Sessions without a custom label show "MM-dd HH:mm". ⋯ Refresh reloads results only; "Refresh session list"
    is in the Scope menu.
  - Once a page hosts the chip, the workspace ⋯ hides while live, so Task 11 must add Runs' live Cards item.
- **Task 2:** accepted as planned, with two differences.
  - `ItemSlot.State.UNKNOWN` keeps its name and means "not captured".
  - `PlanCardRenderer`, `ExaltTileRenderer`, `RunCardRenderer` and `NowCard` already draw an outline, so only `StatTile` calls
    `Tokens.outline`.
  - Note for Task 6: `MODE_CHANGING` is set only while the change runs, so layout listeners must test it in the listener callback,
    not in a deferred save.
- **Task 3:** sent back once. The row's accessible description had replaced the tooltip fallback and lost the Alt key; it now reads
  the shortcut and then the move or menu hint. Tooltips are unchanged, because tests pin them.
- **Task 4:** accepted. The submenu label is "Show hidden", because a `JMenu` paints its own ▸. A cross-run guard beyond the plan
  stops WRAP strips from bouncing. Notifications' `selectSection` accepts IDs and the old titles.
- **Task 5:** sent back once.
  - The real-shell row with one chip wrapped by about 11 px. The search field is now 10 columns ("Search this tab…"), and the
    one-row test measures at the shell's bar width.
  - Clear is explicitly absent at the defaults.
  - Cut chip labels keep their full text in the tooltip and accessible description.
- **Found in passing:** `ShellHookIntegrationTest.shellDisposalReleasesAllReaders…` failed once in Task 5's run: its
  `hasArchiveScratch` helper walks the temp folder while the archive deletes `archive-pin-*`. Task 10 hardens the helper.

---

## Wave B

### Task 6: Column tools into ⋯, and mode-hidden layout rules

**Owns:** see the file map. **Depends on:** Tasks 1 and 2. **Reads:** R2 §1.8, §2.3 (the HistoryTables rules), risks 1–4; R3 B1(a).

- **`HistoryTables`:**
  - **`rememberLayout(JTable, Consumer<TableLayout> save)`:** the layout-saving column-model listener, moved out of `controls()`. It ignores changes made while `MODE_CHANGING` is set.
  - **`ColumnTools columnTools(JTable, TableLayout defaults, Map<String, TableLayout> presets, Consumer<TableLayout> save)`:** one menu section holding:
    - `Columns ▸`: check items, with mode-hidden columns disabled and marked "(Analyst)";
    - `Column preset ▸`: radios;
    - `Reset columns`;
    - a separator;
    - `Copy selected rows` (Ctrl+C) and `Row details…` (Enter).

    `ColumnTools.addTo(OverflowMenu)` and `removeFrom(OverflowMenu)` manage the section. Items are named `<table>-columns`, `<table>-column-preset` and `<table>-reset-columns`.
  - **Keep `controls()`** as a thin wrapper until Task 15 deletes it.
  - **Mode-hidden rules:**
    - `columnState` saves a mode-hidden column with its **user** visibility (remembered per table), never `visible=false` because of the mode.
    - `applyColumns` and presets skip mode-hidden columns in Simple.
    - The last-visible guard counts mode-visible columns.
  - **Integer renderer:** `table()` registers a default `Integer` renderer: null shows `DisplayFormat.UNAVAILABLE` ("—"); otherwise `toString()`, right-aligned, with no grouping, since IDs must not become "2,591".
- **`OverflowMenu`:** a named, replaceable section (`section(String id)` with `replace(...)` and `clear()`). Hidden-while-empty is kept, and separators never double up.
- **`RosterViewState.captureTable`/`prepareTable`:** they remember the widths of mode-hidden columns, and never drop or hide a column because it was mode-hidden when captured.
- **`ArchiveFilters`** gains an optional `tools` (`ColumnTools`). `ArchiveWorkspace.apply` swaps the previous render's section in its ⋯, and `invalidateView` disables it.
- **Call sites:**
  - `ActivityArchiveClient` and `LootArchiveClient` pass their tools through `ArchiveFilters`. Loot's drill buttons are unchanged here; Task 14 owns them.
  - `SocialQueryControls.tableControls` becomes `tableTools(...)`. `ChatArchiveClient` and `KeyPopArchiveClient` use it.
  - A new `SocialQueryControls.liveViewItems(...)` puts the named live views ("Saved views ▸") in a host ⋯ section, for Tasks 12 and 13.
- **Tests:**
  - **`HistoryTablesColumnToolsTest`:**
    - mode → resize → save → mode keeps the column visible;
    - a preset and Reset in Simple leave mode-hidden columns alone;
    - a restored layout in Analyst;
    - "(Analyst)" items are disabled;
    - the last-visible guard;
    - the section is swapped on re-render, not duplicated;
    - the Integer renderer shows "—" for null and "2591" for 2591.
  - **`HistoryTablesKindTest`, `ArchiveNativeSupport` and `SocialArchiveNativeTest`:** the "Columns…" button and the "Column preset" combo become ⋯ items. These are "replace (form only)".
  - **The roster view-state test** (add beside): mode-hidden widths survive a Simple capture.

**Steps:** failing tests; implement; run `tomato.gui.history.*`, `tomato.gui.kit.*`, `tomato.gui.roster.*`, `tomato.gui.activity.*`, `tomato.gui.chat.*Archive*`, `tomato.gui.keypop.*Archive*`, `tomato.gui.stats.*` and `ui.LootEvidenceTest`. Commit ("Move column tools into ⋯ and keep mode-hidden columns out of saved layouts").

### Task 7: Party — Scope host and restyle

**Owns:** see the file map. **Depends on:** Tasks 1 and 2. **Reads:** R1 §1.2 (Party row), §2.5, §6 risk 5; R2 §1.1, §1.8.

- **Host:** `SecurityGUI` implements `LiveFilterHost`, and `parsePanel` becomes a field.
  - **Bar per tab:**
    - Current Area: `inspect-roster`.
    - Runs: a new `FilterBar("inspect-runs")` with the search field in its search slot and the duration unit as a "Duration unit ▸" radio submenu in its ⋯.
    - Ability Use: a new `FilterBar("ability")`.
  - `BAR` fires on the final tab selection, never during `tabs.isRebuilding()`.
  - On Runs, the collection toggle becomes a status line under the filter row, as on Timeline.
- **Ability Use (Analyst):**
  - Its search goes in the search slot. Kind, range and order go in the drawer, with chips.
  - Previous and Next become a footer. "Reset evidence window" moves to ⋯ (Danger).
  - "Observed" renders as an absolute `DATE_TIME`, not `Date.toString()`.
- **Roster restyle:**
  - **Class column:** the class sprite and name through the roster cell. The model value stays the class name. The skin sprite moves from Player to Class, falling back to `Sprites.sprite(objectType, 16)`, and Player becomes text.
  - **Gear:** `ItemSlot.icon(...)` tier wells, with the enchant glow drawn inside the well. The cells keep "" text and their "Weapon: Not captured" / "Empty (ID …)" accessible names.
  - **Requirements:** `KitTables.status` (PASS → GOOD, BELOW → BAD, UNKNOWN → WARN), keeping the tooltip and accessible name.
  - **Buttons:** `KitButton`s with the same labels. "Requirement details" stays a toggle.
- **View state:**
  - The three `RosterViewState` rows become ⋯ items "Save view state" and "Reset saved view state":
    - the roster's in `inspect-roster`'s ⋯, keeping the names `inspect-live-roster-save-state` and `-reset-state`;
    - the container's and runs' in the active host bar's ⋯.
  - Status shows only on failure.
  - The items are disabled while a recorded run owns the roster, as `updateViewStateOwnership` rules today.
- **Party › Runs time column:** `KitTables.relativeTime` in Simple. It has no Analyst-only columns, because its saved layout lists every column.
- **Tests:**
  - **`SecurityScopeHostTest`:**
    - each tab's bar hosts the chip while live;
    - the chip moves on a tab change;
    - `BAR` fires once per final selection;
    - one row per tab at 1240×800 font 13 (`FilterBarAssert`);
    - saved mode is unchanged.
  - **`PartyRestyleTest`:**
    - class cell sprite and text;
    - wells for item, empty and not captured, with the glow;
    - requirement tones;
    - view-state items and their disabled state;
    - Runs time relative in Simple and absolute in Analyst, with the export unchanged.
  - **`InspectViewStateTest`:** about 10 lines, buttons become items, "replace (form only)".
  - **`ParsePanelRefreshTest`:** the one icon-identity test is "replace", because the well now paints the glow.
  - **`InspectRosterFilterBarTest`** (add beside).

**Steps:** failing tests; implement; run `tomato.gui.security.*`, `tomato.gui.history.*`, `tomato.gui.chat.ShellHookIntegrationTest` and `tomato.gui.ShellSwitchTimingTest`. Commit ("Host the Scope chip on Party's tabs and restyle its roster, runs and ability views").

### Task 8: The Bridge review restyle

**Owns:** see the file map. **Depends on:** Task 2. **Reads:** R2 §1.5.

- **Review tab:**
  - `FilterBar("bridge-review")` with `[Search][Reset filters]` in the search slot.
  - The drawer holds the outcome, status, character, dungeon and enchant filters, with their names kept, plus chips.
  - ⋯ holds "Export review CSV…".
  - "Item alert from this drop…" (`bridge-alert-draft`) becomes a Secondary action above the details pane.
- **Logs tab:** `FilterBar("bridge-logs")` with search and level; ⋯ holds "Export logs…" and "Clear logs" (Danger, confirm).
- **Other tabs:** on Saved review, "Open configured" is Primary. In Settings, Save is Primary, Revert Secondary and "Use included CSV" Ghost.
- **Tables:**
  - Kinds through `HistoryTables.kinds`: review `{time, ITEM, STATUS, STATUS, PLAYER, DUNGEON, STATUS, STATUS}`, logs `{time, STATUS, TEXT}`, saved `{…, ID, ID}`.
  - The Item cell shows the sprite of the row's item id; the model stays the name.
  - Time columns: the header becomes "Time". `KitTables.relativeTime` uses `KitTables.epoch` on the ISO strings, with a tooltip giving the time in UTC and local time. A comparator parses `Instant`, so fractions of a second sort correctly.
  - Saved "Session" and "Journal" are `analystOnly`.
- **Tests:**
  - **`BridgeFilterBarTest`:**
    - one row at 1240×800 font 13 on Review and Logs;
    - the drawer filters and chips;
    - Reset;
    - the ⋯ items.
  - **`BridgeTableKindsTest`:**
    - kinds;
    - relative time in Simple, absolute in Analyst;
    - the Instant sort with fractions of a second;
    - CSV export identical in both modes;
    - Session and Journal hidden in Simple.
  - `BridgeUiTest`, `BridgeReportingTest`, `BridgeDraftActiveTest` and the `tomato.bridge` tests stay green.

**Steps:** failing tests; implement; run `tomato.gui.bridge.*` and `tomato.bridge.*`. Commit ("Give Bridge review one filter row, table kinds and mode-aware times").

### Task 9: Loot values, wording and Highlights polish

**Owns:** see the file map. **Depends on:** Task 2. **Reads:** R3 B2, B3(a), B9, B10.

- **`LootFacts.areaLabel(String)`:** returns "Unknown area" for null, blank, "Unknown" and "Unrecognized area", and the name otherwise. `LootFacts.known` and `HighlightsModel.area` merge into it.
  - Used for: Highlights, the RATES row names in `StatisticsArchiveAdapter` (`row.dungeon` stays the drill key), and the dungeon facet labels in `LootFacetControls`.
  - The saved keys and facets stay "Unknown".
  - Task 14 applies it to `LootDashboard`'s and `LootArchiveClient`'s renderers.
- **`ArchiveAdapter.sortsLast(R row)`:** a default method returning `false`. `ArchiveResult.open` applies it as the first sort key, ahead of the query order and outside the DESCENDING reversal. `LootArchiveAdapter` returns true for the `bag-type` row named "Unknown bag (name not saved)".
- **Highlights:**
  - The dungeon strip joins its summary into one " · " sequence and wraps it at " · " boundaries across the two caption lines, so the height is unchanged.
  - Notable card names wrap to two lines, with the cell one title line taller.
  - `NotableDropRenderer` calls `Tokens.outline`.
- **Home's Notable loot tile:**
  - `HomeActions.loot` becomes `Consumer<HomeArchive.Window>`; the old `Runnable` constructor stays as an adapter.
  - `TodayTiles` passes its current window.
  - `LootFocus` gains an optional `HighlightsModel.Window window` (null means keep). The `LootFocus(tab)` constructor stays.
  - `LootPage.tabTarget().open` calls a new `LootHighlights.showWindow(window)`, which selects and saves the window as a click does.
  - `TomatoGUI` changes only the Home loot action line.
- **Tests:**
  - `areaLabel` cases, with keys unchanged in facets and drills.
  - By Bag under BAG and NAME, ascending and descending: the unknown row is last.
  - The strip and card layouts at 1240×800 font 13: no ellipsis for the fixture names; tooltips and accessible names unchanged.
  - The Home tile opens Highlights on Today and on This session, and the choice is saved.
  - Light outline on the notable cards.

**Steps:** failing tests; implement; run `tomato.gui.loot.*`, `tomato.gui.stats.*`, `tomato.history.archive.*`, `tomato.gui.glance.home.*` and `ui.LootEvidenceTest`. Commit ("Say Unknown area, keep the unknown bag last, and polish Highlights and its Home entry").

### Task 10: Build tiles and cleanups

**Owns:** see the file map. **Depends on:** Task 2. **Reads:** R2 §1.7; R3 B11, B13.

- **Build's metric cards** become `StatTile[] summary`:
  - Health and Mana: `DisplayValue.known`, with "—" parts kept, or `partial` when one side is missing.
  - Weapon DPS and MP/sec: `estimate(text, assumptions)`, shown as "≈ 1,234" with the assumptions as the tooltip. Unknown shows the "Enter the game…" reason.
  - The four `myinfo-metric-<i>` Details buttons stay, as Ghost buttons under the tiles.
  - The recorded-DPS supplier reads the raw number and calls `explain()` after each update.
  - Existing lookups: `summary[i].getText()` becomes `valueText()`, "replace (form only)".
- **`RunFeedSource`** uses `SessionStamps<Facts>` (R3 B11). `Facts.stamp` becomes `List<SessionStamps.Stamp>`. Delete `SessionFacts.Stamp` and `stamp()`.
- **`ContentStyleTest`:** after each `setSize`, settle by polling `invokeAndWait` until the frame size equals the request and the layout counter is unchanged for three consecutive turns (bounded at about 2 s). Take the "no loop" baseline after the settle. No assertion weakens. The coordinator dismisses the separate suggestion `task_fabaed93` when this task starts.
- **Tests:**
  - **`BuildTilesTest`:** tile values for known, partial, estimate and unknown; the Details buttons; the recorded-DPS explanation updates.
  - `RunFeedSourceTest` stays green.
  - `ContentStyleTest` passes 10 of 10 runs alone.

**Steps:** failing tests; implement; run `tomato.gui.myinfo.*`, `tomato.gui.glance.character.*`, `tomato.gui.runs.*` and `ui.ContentStyleTest` (alone, 10 times). Commit ("Show Build's metrics as tiles, share session stamps in the run feed and settle ContentStyleTest").

### Wave B outcomes (coordinator review)

_To be filled in after the merges._

---

## Wave C

### Task 11: Runs, Timeline and Resources — Scope hosts and the Timeline restyle

**Owns:** see the file map. **Depends on:** Tasks 1, 2 and 6. **Reads:** R1 §1.2, §2.5, §2.7, §5.1; R2 §1.2, §2.2.

- **Host:** `ActivityPanel` implements `LiveFilterHost`. Its bars are `activity-runs`, `activity-timeline` and `activity-combat`.
- **`RunFeedView`** adds `run-feed-live-cards-item` ("Cards view") to the host bar's ⋯ through `ArchiveWorkspace.liveFilterBar()`, and `modeChanged` toggles both Cards items.
- **Rewording:** the "Browse saved" wording (`ActivityPanel` about `:485`, `ResourceWindowPanel` about `:158-159`) points to Scope ▾ › Saved history.
- **Timeline and Resources live:**
  - Visit and kind move into a Filters drawer, with chips.
  - Collection and Pause become one status line under the filter row.
  - "Export displayed history…" and the view state ("Save view state", "Reset saved view state") move into the host bar's ⋯. The same view-state fold applies to Runs' Table view live.
- **Time columns:** `KitTables.relativeTime` on:
  - Timeline live `column-0`;
  - the Runs table live time column;
  - Resources;
  - the saved activity `time` column (shared by Runs' Table view, Resources and Party saved), which keeps its zone tooltip in Analyst.
- **Timeline "Meaning"** is `analystOnly`; the detail pane keeps it.
- **Tests:**
  - **`ActivityScopeHostTest`:**
    - each mode's bar hosts the chip while live;
    - one row at 1240×800 font 13 in both modes (`FilterBarAssert`);
    - the live Cards item follows the mode.
  - **`TimelineRestyleTest`:**
    - drawer chips for Visit and kind;
    - the status line;
    - the ⋯ export and view-state items;
    - Meaning hidden in Simple and restored in Analyst with its width;
    - relative and absolute times;
    - exports identical in both modes.
  - **`ActivityFormattingTest`:** the four RUNS absolute-text lines run in Analyst. This is "replace", because Simple now reads relatively.
  - "Pause this view" lookups stay green.

**Steps:** failing tests; implement; run `tomato.gui.activity.*`, `tomato.gui.runs.*`, `tomato.gui.dps.*`, `tomato.gui.history.*`, `tomato.gui.chat.ShellHookIntegrationTest`, `ui.RunsEvidenceTest`, `ui.RunsDpsEvidenceTest` and `tomato.gui.ShellSwitchTimingTest`. Commit ("Host the Scope chip on Runs, Timeline and Resources and restyle Timeline").

### Task 12: Chat — Scope host and a single-scroll Settings › Chat

**Owns:** see the file map. **Depends on:** Tasks 1 and 6. **Reads:** R1 §1.2 (Chat row), §5.1; R2 §1.8; R3 B6.

- **Host:** `ChatExplorer` implements `LiveFilterHost` with the `chat-live` bar, and `ChatGUI.workspace` wires it.
- **⋯:** the live column tools (`HistoryTables.columnTools`) and the named live views (`SocialQueryControls.liveViewItems`) move into `chat-live`'s ⋯. The drawer keeps only filters.
- **Rewording:** the "Browse saved" wording in the clear dialog and the summary tooltip.
- **Settings › Chat:**
  - `ChatExplorer.filtersEditor()` returns `ChatGUI.FiltersEditor(JComponent body, JComponent footer)`, and every rebuild refills both holders.
  - `ChatFilterPanel` gains an embedded mode: no border, no page scroll of its own, and the footer exposed. Its dialog mode is unchanged.
  - `ChatSection` builds one `ContentStyle.page(null, top + body, null)` with the footer pinned below it.
- **Tests:**
  - **`ChatScopeHostTest`:** the chip in `chat-live` while live, one row, saved unchanged, the ⋯ items.
  - **`ChatSectionTest`** (add beside):
    - one scroll area at 680×520 font 18, with Save filters and Cancel in view;
    - no extra inset;
    - the footer survives Cancel and a revision rebuild.
  - **`ChatLiveFilterBarTest`** (add beside).

**Steps:** failing tests; implement; run `tomato.gui.chat.*`, `tomato.gui.settings.*` and `ui.LootEvidenceTest` (the Settings › Chat captures). Commit ("Host the Scope chip on Chat and give Settings › Chat one scroll with a pinned footer").

### Task 13: Key-pops — Scope host and restyle

**Owns:** see the file map. **Depends on:** Tasks 1, 2, 4 and 6. **Reads:** R1 §1.2 (Key-pops row), §6 risk 6; R2 §1.3.

- **Host:** `KeyPopDashboard` implements `LiveFilterHost` through the instance's own dashboard, never the static one.
- **The live bar** moves above the tiles. The metric cards become `StatTile`s named `keypop-metric-<i>`, with `DisplayValue.count`.
- **The page footer** moves to the live bar's ⋯:
  - "Export events…" and "Export current tab…";
  - "Log to file" (a check item);
  - "Notification settings…";
  - "Clear history…" (Danger, confirm).

  "Dungeon alert…" (`keypop-notify-dungeon`) stays visible as a selection action.
- **The drawer:** the live saved views (`liveViewItems`) and column tools move into ⋯. The "Multi-select / absolute dates / view state" toggle goes; multi-select and dates become plain drawer sections.
- **Saved mode:**
  - The archive modes become a per-render `CustomizableTabs("keypops-saved")` whose component is the `keypop-archive-tabs` `JTabbedPane`. The query's mode tab is revealed as explicit content.
  - The duplicate "(all matches)" export goes.
- **Time columns:** `KitTables.relativeTime` on the live Time and Last pop columns (3 tables) and on the saved `time` column.
- **Rewording:** the "Browse saved" wording in the status tooltip.
- **Tests:**
  - **`KeyPopRestyleTest`:**
    - the bar above the tiles and one row (`FilterBarAssert`);
    - tile values and unknown handling;
    - the ⋯ items, with Clear confirming;
    - the saved tabs persist, hide and reveal;
    - relative and absolute times;
    - exports identical in both modes.
  - **Metric lookups:** `KeyPopTest`, `ExactContributorTest`, `KeyPopFormattingTest` and `KeyPopHistoryPersistenceTest` move from `getText()` to `valueText()` and from `JLabel` to `StatTile`. These are "replace (form only)".
  - **The absolute live time assertions** run in Analyst. This is "replace", because Simple now reads relatively.

**Steps:** failing tests; implement; run `tomato.gui.keypop.*`, `tomato.gui.chat.SocialArchiveNativeTest`, `tomato.gui.notifications.*` and `tomato.gui.chat.ShellHookIntegrationTest`. Commit ("Host the Scope chip on Key-pops and restyle its tiles, actions and saved modes").

### Task 14: Loot Explore — one selector, saved presentation and Fame history

**Owns:** see the file map. **Depends on:** Tasks 1, 6 and 9. **Reads:** R1 §3, §4, §5.1 (the loot rows), §6 risk 8; R3 B1(b), B3(b), B4, B5, B7, B14.

- **One selector (R1 §3):**
  - `LootDashboard` implements `LiveFilterHost` (`loot-live`), and its live bar moves above the tiles.
  - `showView` splits into `showLive(View)` and a selector sync used only without Explore. A bare dashboard with no history behaves as today.
  - **`LootExploreModel`** becomes the selector's controller:
    - `attach(LootDashboard, ArchiveWorkspace)` adopts `loot-views` and puts it with the "Saved history only" caption in a lead panel (`workspace.lead(panel)`). It listens to `ARCHIVE` and to the mode.
    - `choose(View)` is the selector's only `onChange`, replacing `chosenLive` and `chosenSaved`.
    - `modeChanged(boolean archive)` re-lists and selects, adding a "Current view" extra when needed.
    - `rendered(ViewState)` is called by `Render` so restores, routes and drills re-select.
  - **`LootArchiveClient.Render`** skips its view row when Explore is attached. Dungeons › Analysis and Fame history keep theirs.
  - **State keys** are unchanged.
- **Saved presentation in Simple (R3 B4):**
  - One plain count line (for example "9 bags · 15 item variants · 22 items"), with the full description and counts in its tooltip.
  - The drill buttons move into ⋯.
  - The Items column leaves the compact preset when every row shows "—".
  - All of this rebinds on a mode change.
  - `ArchiveWorkspace`'s "pinned …" status caption is Analyst-only on every archive page.
- **Values:**
  - The Bag column renders a blank value as "—" (display only; CSV and the model keep "").
  - `LootFacts.areaLabel` is applied in the live Last dungeon, By Dungeon and Recent renderers and in the archive `dungeon` column.
  - The live rarity summary leaves potions out of the buckets and adds "· N potions (no enchant slots)".
- **Live view state (R3 B5):** `StatisticsLiveState`'s Retry and Reset become items in `loot-live`'s ⋯, shown only after a failed save or while saving is blocked. The status line shows only on failure.
- **Fame history (R3 B7):**
  - FAME's analytical filters are Character ID only: no dungeon field and no loot chips.
  - The Name column absorbs the slack when the columns fit the viewport.
- **Tests:**
  - **`LootExploreTest`:** rewrite about six tests for the one selector:
    - the selector in the host bar while live and in the workspace search slot while saved;
    - a saved-only view chosen while live;
    - view kept across modes;
    - "Current view" extra;
    - the caption.
  - **Selector lookups:** `LootEvidenceTest`'s selector parts and its stale "view row" comment; the `loot-archive-view` lookup in `LootArchiveStateTest` and `StatisticsArchiveNativeTest`; the loot lines in `FilterBarEvidenceTest`.
  - **New cases:**
    - Simple count line and tooltip;
    - drills in ⋯;
    - pinned caption hidden in Simple;
    - Bag "—";
    - Unknown area;
    - potions line;
    - Retry/Reset visibility;
    - Fame filters and Name width;
    - one row live and saved (`FilterBarAssert`).

**Steps:** failing tests; implement; run `tomato.gui.stats.*`, `tomato.gui.loot.*`, `tomato.gui.history.*`, `tomato.gui.character.*`, `ui.LootEvidenceTest`, `tomato.gui.history.FilterBarEvidenceTest` and `tomato.gui.ShellSwitchTimingTest`. Commit ("Give Loot Explore one view selector in the filter row and a plainer saved view in Simple").

### Wave C outcomes (coordinator review)

_To be filled in after the merges._

---

## Wave D

### Task 15: Strict S6 and the last `HistoryTables.controls`

**Owns:** see the file map. **Depends on:** Tasks 1–14. **Reads:** R1 §5.2; R4 §2.4, §4 (S6).

- **`FilterBarEvidenceTest`:**
  - Add the live host pages next to `loot-explore-live`: runs, timeline, resources, chat, keypops, and Party's three tabs (SecurityGUI in its workspace).
  - Add Logging (every tab) and Bridge review (Review and Logs).
  - At 1240×800 font 13 with the drawer closed, use `FilterBarAssert.assertOneRow` for every visible bar.
  - On archive pages: the chip is a descendant of the visible bar, the workspace bar is not showing while live, and saved Loot's selector is in the workspace search slot.
  - Keep the 680×520 font 18 captures.
- **`LootEvidenceTest`'s copy of the weak check** becomes `FilterBarAssert`.
- **Delete `HistoryTables.controls`** and every remaining caller (none in main code after Wave C).
- **Tests:** the new pages pass. The coordinator reviews the captures.

**Steps:** extend the test; run it alone twice; delete `controls`; run `tomato.gui.history.*` and `ui.LootEvidenceTest`. Commit ("Check one filter row strictly on every page, live and saved, and remove the old column controls").

### Task 16: The information-architecture docs

**Owns:** see the file map. **Depends on:** Tasks 1–14. **Reads:** R4 §3.1 (these files), §3.4.

- **`docs/UI-REDESIGN.md`** follows R4 §3.4's structure: current IA first, history last. The sections are:
  - the sidebar table;
  - per-page tabs;
  - Simple and Analyst, including the Analyst-only columns and relative times;
  - keyboard shortcuts, including drag alternatives and Esc for drawers;
  - Customizing: sidebar drag, tab drag, hide, pin, reset;
  - the filter row: search, Filters and chips, Scope ▾, ⋯;
  - values: —, ≈, ✎, ◐, stale, observed;
  - where the old pages went;
  - saved preferences, added and orphaned;
  - dated history.
- **`README.md`:** `## Workspaces` becomes `## Pages` (a 12-row table with short per-page notes), followed by a `## Navigation` paragraph. Fix the stale lines listed in R4 §3.1.
- **`docs/UI-CONSISTENCY.md`:** a history banner, plus the six line fixes.
- **Links:** to the P6b validation record (`docs/superpowers/plans/2026-09-29-p6b-validation.md`). No screenshots are embedded.

**Steps:** write; check every link and anchor, including README's anchor into `UI-REDESIGN.md`; commit ("Document the interface after P6").

### Task 17: The module docs

**Owns:** see the file map. **Depends on:** Tasks 1–14. **Reads:** R4 §3.1, §3.2.

- **Scope ▾ wording** replaces "Browse saved / session picker / Current live view" in `SESSION-HISTORY.md`, `CHAT.md`, `KEY-POPS.md`, `LOOT.md` (one selector in the row) and `ACTIVITY.md`.
- **The restyles:**
  - `LOGGING.md`: one filter row, with views in ⋯;
  - `KEY-POPS.md`: tiles, ⋯ actions, saved tabs, relative times;
  - `BRIDGE.md`: filter rows and Analyst-only columns;
  - `NOTIFICATIONS.md`: Settings › Notifications with customizable tabs;
  - `ACTIVITY.md`: Party as an Advanced page with its restyle, and Timeline's drawer, status line and Meaning;
  - `CHARACTERS.md`: Build tiles;
  - `DPS-METERS.md`: Party naming.
- **Other stale statements:** "Inspect" becomes Party, and the Advanced pages are "under Advanced in the sidebar". Fix the wrong menu paths in `CHAT.md`.

**Steps:** write; grep the docs for "Browse saved", "Current live view", "session picker", "Inspect >" and "in the sidebar (Alt+N)"; commit ("Update the module guides for the Scope chip and the Advanced restyles").

### Task 18: The final Simple/Analyst screenshot set

**Owns:** see the file map. **Depends on:** Tasks 1–14. **Reads:** R4 §0–§2 (all).

- **Harness:**
  - **`ui/EvidenceWorkspace`** is a JUnit rule that isolates:
    - preferences by prefix;
    - the display mode;
    - statics: `TomatoGUI`, `ChatGUI`, `CharacterPetsGUI`, `DpsGUI`, `Filter`, `DpsDisplayOptions`, the filter sets;
    - `Tomato.preview`, the character view states, `LootCapture`, `IdToAsset` names;
    - `java.io.tmpdir`, the zone and FORMAT locale, `AppHistory.store`, a temporary journal;
    - `DiscoveryLog.INSTANCE`, cleared in `close()`;
    - the `PlanningStore` snapshot, restored afterwards.

    It builds the real shell with `new TomatoGUI(data).createWorkspace()` in preview mode.
  - **`VisualEvidence.captureRoot(name)`** paints the frame's root pane, so there is no title band and no padding. The window is sized so the root pane is exactly W×H.
- **Fixture:** `ui/FinalScreensFixture` is one synthetic history built from public fixtures only (R4 §2.2): Today, Yesterday and This app run. Synthetic names only.
- **Matrix (R4 §2.3):**
  - **Primary:** 121 captures at 1240×800 font 13, dark: every page and tab in Simple and Analyst, and Analyst-only tabs once. This includes the Scope menu open once, the sidebar during a drag with its drop line, and a filter drawer open.
  - **Compact:** 12 captures at 680×520 font 18, Analyst, each destination's landing tab.
  - **Light:** 12 captures at 1240×800 font 13, Simple.
  - **Bridge Review:** the unconfigured real shell, plus one labeled populated capture over a fake service in a `TestPages` shell.
- **Navigation:** routes and IDs only (`Route.to(...)`, `select(id)`, `TomatoGUI.openSettings(id)`, tab IDs), never indices.
- **Checks per capture (R4 §2.4):**
  - the shell is exactly W×H;
  - the selected page and tab IDs;
  - the mode applied, with Analyst-only tabs absent in Simple;
  - nothing scrolls sideways;
  - no view-state warning;
  - no absolute path in any showing text;
  - `FilterBarAssert.assertOneRow` on every visible bar at 1240×800 font 13, live and saved on the seven archive pages.
- **Output and gating:**
  - `redesign-final/INDEX.md` has one row per capture with its checks. `redesign-final/index.html` is the contact sheet (Simple and Analyst side by side, relative links).
  - The test is skipped unless `REALMSHARK_FINAL_SCREENS=1`.
  - Nothing is committed from `build/`.

**Steps:** write the fixture and harness; run the test alone with the variable set; fix test-side issues; report every check that fails and every visual problem you see as findings for the coordinator (do not fix page code); commit ("Add the final Simple and Analyst screenshot set").

### Wave D outcomes (coordinator review)

_To be filled in after the merges._

---

## Wave E (coordinator)

- **Review:** look at every final capture (contact sheet and full size) and decide each finding. Run polish tasks, test-first, one writer per file, each reviewed like a plan task. Then re-run the final set alone.
- **S8:** `ShellSwitchTimingTest` alone, twice.
- **Recovery docs:**
  - `docs/UX-HANDOFF.md` and `docs/UX-EXECUTION.md`: replace the stale "current" paragraphs with a pointer ("Redesign P0–P6 merged; see the roadmap and handoff; everything below is Waves 1–4 history").
  - `docs/UX-CHECKPOINT.json`: move the Wave 4 top-level fields under `waves` and describe the redesign state.
  - Delete `docs/UX-EXECUTION (1).md` and `docs/UX-ROADMAP-2026-09-21 (1).md`.
  - `AGENTS.md` is not edited.
- **Validation record:** `docs/superpowers/plans/2026-09-29-p6b-validation.md`, with:
  - per-task and per-wave runs;
  - S6 and S8;
  - an S1–S9 table (S3 stays partial: the expiry half is deferred);
  - the final set's review and its findings;
  - the final suite and the JAR smoke.
- **Final checks:** the full suite and `shadowJar`, then an isolated `--help` smoke. Send the contact sheet to the user.
- **PR:** fill the plan's wave outcomes, update the roadmap, handoff and checkpoint, push, and open the PR.

## Local validation

| Check | Command | Record |
|---|---|---|
| Baseline | P6a final suite on `0625e34` (2020 / 4 / 0 / 5) and the Codex fix's focused run (152 / 0) | no new run |
| Task focused tests | each task's commands | pass counts (coordinator review notes) |
| Wave merges | the union of each wave's focused commands on the integration branch | pass counts |
| S6 | `GRADLE test --tests "tomato.gui.history.FilterBarEvidenceTest"` (alone, after Task 15) | one row on every page, live and saved, strict |
| S8 | `GRADLE test --tests "tomato.gui.ShellSwitchTimingTest"` (alone, twice, Wave E) | p50/p95 per destination and tab |
| Final set | `REALMSHARK_FINAL_SCREENS=1 GRADLE test --tests "ui.FinalScreensEvidenceTest"` (alone) | captures and INDEX reviewed by the coordinator; findings |
| `ContentStyleTest` | alone, 10 times (Task 10) | 10 / 10 |
| Final full suite and JAR (coordinator) | `GRADLE test shadowJar` | totals; new failures versus the baseline |
| JAR smoke | isolated `java -jar … --help` | exit 0; folder empty afterwards |

## Deferred scope

- **Not in P6 (user decision 2026-09-29):** a font-size control in Settings › Appearance (Edit › Font stays); a search box inside the Settings page (Ctrl+K "Find settings and actions…" stays); the `AGENTS.md` pointer line.
- **Kept deferred from earlier phases:**
  - the quest expiry countdown and "N expiring today" (S3's expiry half; it needs a live Daily Quest Room sample);
  - typed `Destination`s for Settings, Chat and Key-pops (IDs and `bindShortcut` cover them);
  - a shared base for `RunsDpsPage` and `LootPage`;
  - Resources & buffs stays nested in the Live meter;
  - building `CombatMeterData` off the EDT;
  - a per-map fame breakdown and live Dungeon Stats.
- **Left for later:**
  - renaming `HistoricalStatistics` and `StatisticsLiveState` after the removed page;
  - the saved rarity column still says "Unknown" for potions;
  - Analyst-only ID columns in Loot's archive table;
  - `KitTables` kinds on the Logging, meter and fame-session tables;
  - `FameHistory` and `RecordingsSource` keep their own stamp caches;
  - 680×520 font 24 spot checks.
- **Accepted as implemented differently:** the header breadcrumb (the sheet's "‹ Characters" link) and chat pill counts (in tooltips).

## Cross-task notes

- **Tabs added:** `notifications` (`messages`, `bags`, `key-pops`, `realm-events`, `other-alerts`, `decisions`) and `keypops-saved` (the saved modes).
- **Bars added:** `logging`, `bridge-review`, `bridge-logs`, `inspect-runs`, `ability`.
- **Preferences added:** `ui.tabs.notifications`, `ui.tabs.keypops-saved`, and `ui.filters.<bar>.open` for each new bar. **Unchanged:** every `ux.archive.*` key, `ui.nav.*` and the roster view-state keys (their layouts may now carry the widths of mode-hidden columns).
- **Component names added:**
  - the Scope chip: `<module>-scope`, `<module>-scope-menu`, `<module>-scope-live|current|all|session|library|refresh`, `<module>-refresh`;
  - `run-feed-live-cards-item`;
  - `<group>-tab-<action>` (tab menus);
  - `<table>-columns`, `<table>-column-preset`, `<table>-reset-columns` (⋯ column tools).
- **Component names removed:**
  - `<module>-session-picker`;
  - the "Browse saved" / "Current live view" buttons;
  - the scope ⟳ button;
  - `loot-archive-view-row` on Explore;
  - the `<table>-column-controls` rows;
  - Logging's "Views" row controls;
  - the Key-pops "Multi-select / absolute dates / view state" toggle.
- **Kit API added:**
  - `ScopeChip`, `LiveFilterHost` (history);
  - `FilterBar.searchSlot()`;
  - `KitTables.relativeTime`, `epoch`, `analystOnly`, `modeHidden`, `MODE_HIDDEN`, `MODE_CHANGING`;
  - `ItemSlot.icon`, `Tokens.outline`;
  - `OverflowMenu.section`;
  - `HistoryTables.columnTools` and `rememberLayout`;
  - `ArchiveWorkspace.showSaved(String)`, `lead`, `liveFilterBar`, `ARCHIVE`;
  - `ArchiveAdapter.sortsLast`, `LootFacts.areaLabel`, `LootHighlights.showWindow`;
  - `NavLayout.moveTo`.
- **Kit API removed:** `HistoryTables.controls` (Task 15), `SocialQueryControls.tableControls` (Task 6).
