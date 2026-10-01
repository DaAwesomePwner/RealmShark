# Loot Explore pictures — handoff (2026-10-01)

Read this first when resuming the Loot › Explore redesign on another workspace or workstation. It records where the work stands,
what to do next, and the facts that live only in one machine's agent memory.

## 1. Where it stands

The goal is that Loot › Explore stops being text tables and becomes a picture of your loot that you can dive into, with sprites
throughout. The spec is `docs/superpowers/specs/2026-10-01-loot-explore-pictures-design.md`.

| Phase | What | PR | Merged as |
|---|---|---|---|
| P1 | The shared haul: `BagSprites`, `HaulModel`, `HaulView` (Full/Compact), shared `LootLine`; the run recap's Loot section uses the Compact haul | #46 | `a4f9ade` |
| P2 | Explore opens on **Pictures** (`LootExplorePage`: Pictures \| Table), `RunsLevel`, `RunHauls`, `RunFeedView` picker mode | #48 | `6c5ef77` |
| P3 | Collection (trophy cabinet), item history, kit `Breadcrumb`, Runs \| Collection switch (`ExplorePictures`), `ExploreItem` route, `LootCatalog` | #49 | `98af004` |
| P3b (user-requested layout, in #49) | A horizontal **run strip** on top (compact cards with a 32 px best drop); a **full-width haul with every bag open**; a **"This dungeon so far"** side panel (`DungeonStats`/`DungeonPanel`) | #49 | `98af004` |
| Hotfix | Three shell tests left on the old UI (`WaveThreeJourneyTest` compile error, `ShellSwitchTimingTest`, `RunsEvidenceTest` recap bag count) | #53 | `eefcd77` |
| **P4** | **Dungeons way in plus routed Pictures navigation.** Plan written; **not started** | — | — |

Related work merged by other sessions: mouse Back/Forward (#51: `Navigator.forward()`, `ShellNavigator` forward stack, mouse buttons
4/5, Alt+Right) and a workstation setup with a macOS Gradle wrapper (#52).

`main` is at `eefcd77` or later. The P4 branch **`claude/loot-explore-p4`** is pushed and holds the P4 plan and this handoff,
rebased on `eefcd77`.

## 2. Next step: P4

The plan is `docs/superpowers/plans/2026-10-01-loot-explore-pictures-p4.md`.

- **Dungeons**, the third way in (Runs | Dungeons | Collection), is a wall of portal tiles (`AtlasModel` + `DungeonsLevel`).
  - Each tile shows the dungeon's runs (from Runs › Dungeons' `DungeonsSource`; "—" when only loot knows it), white bags and UTs
    with rates per run with loot, and its top 3 drops.
  - **Sorts:** Most runs, Whites per run, UTs per run, Recent. A search box filters by name.
- **A tile** opens Runs **filtered to that dungeon** (`RunFeedView.showDungeon`). The dungeon panel gains **All items from <dungeon>**,
  which opens Collection filtered to it (new `CollectionLevel.filterDungeon`). The breadcrumb follows: "Dungeons › Lost Halls › run".
- **Routed navigation:** every Pictures move goes through the navigator (an `ExplorePictures.Focus` payload via
  `LootExplorePage.focusTarget()`), so mouse Back and Forward step through them: the entry switch, breadcrumb links, tiles and
  "All items from". Items and drops were already routed in P3.

**Status:** the plan was presented to the user, who settled its one open question: **run-card picks in the strip stay unrouted.**
They are master-detail selection, as PR #51 noted, so Back after picking several runs leaves Pictures rather than stepping through
each pick. **Get an explicit "go" before spawning the implementer.**

Tasks 2 and 3 of the plan specify UI behavior rather than full code, as the strip layout did. The implementer writes the code to
that specification, and the cross-family review checks it.

## 3. How this project is run (the user's workflow)

### Implement, review, merge

**Codex implements, Claude coordinates and reviews.** Use the Orchestra plugin (`orchestra:delegate` skill):

1. `orchestra.sh create <name>`.
2. Write the brief at the printed path. Keep the template's Role block, say "follow the plan's Tasks N–M", and list the files.
3. `spawn <name> --agent codex --tier hard` (hard for threading or routing work; standard for small changes), then start a
   background `wait`.
4. **The coordinator runs Gradle; Codex never does.** A sandboxed Gradle run locks `.tools/gradle-home`. Codex also never commits,
   because its sandbox makes `.git` read-only.
5. Delete build output in the worker before review: `build-*` folders, logs, `.gradle`.
6. `review <name> --tier <same>` (a Claude reviewer), then background `wait <name>:review`. Triage each finding against the code.
   Send valid ones back with `send <name> --file <fix.md>`. At most 2 fix rounds; trivial wording the coordinator may fix itself,
   then re-review.
7. Write `<name>.commit.md`, then `orch commit <name>` and `orch merge <name>` (`--no-ff` into the feature branch).
8. Push, then `gh pr create`. The user merges PRs or says "merge it".

**Removing a worker on Windows** often half-fails with "Permission denied" on `.git/worktrees/<name>`, because Codex's sandbox adds
DENY ACEs. With the sandbox disabled, `rm -rf` the worker folder and `.git/worktrees/<name>`, then `git worktree prune`, and delete
`<state>/<name>.*`. Delete `orch/<name>` with `-D` only after `git merge-base --is-ancestor orch/<name> origin/main`.

- The user approves this housekeeping per occasion.
- `git worktree prune` also tries to delete `.git/worktrees/mouse-back-forward`, a leftover of another session's worker. That fails
  harmlessly; the `claude/mouse-back-forward` worktree itself (`.claude/worktrees/deferred-redesign-items-ee9522`) is intact. Leave
  it for its owner.

### Building and testing

**Windows** (from a worktree root, Git Bash; `.tools` lives in the primary checkout):
```bash
export JAVA_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/gradle-home"
./gradlew.bat test --offline -PrealmSharkBuildDir=build-<unique> --tests '<pattern>'
```
On macOS, follow AGENTS.md: `./gradlew` with JDK 17, and UI tests through `scripts/mac/gui-gradle.sh` when over SSH.

- **Always also run `compileTestJava`, or the full suite, before review and before merge.** P3 was verified on focused suites only
  and broke `main`'s test compile (fixed in #53).
- **Another session's `gradlew --stop` can kill your build** ("Gradle build daemon has been stopped: stop command received"). Re-run.
- **Known environmental failures on the Windows workstation** (window renders 1224×761, not 1240×800; drag threshold; soft timing):
  - `LootHighlightsTest` (2), `ChatSectionTest`, `FilterBarEvidenceTest`, `ScopeChipTest` (3), `WorkspaceShellDragTest`;
  - `QuestBoardTest`, `QuestFilterBarTest`, `DungeonsSourceTest` (timing), `ui.LootEvidenceTest` (7), `ui.QuestsEvidenceTest`;
  - `ui.RunsDpsEvidenceTest` (2) and one `ui.RunsEvidenceTest`. `HomeRefreshTimingTest` and `ThemesTest` are flaky under load.
  
  Compare any failure against this list, and against `main`, before calling it a regression.
- **Failing on `main` from other PRs, not loot code** (2026-10-01, unowned):
  - `ui.RunsEvidenceTest.settingsGeneralShowsTheCombatHistorySettings`;
  - `ui.ShellRedesignEvidenceTest.sidebarHeaderBannerAndSettingsRenderInBothVariants` (Settings and theme layout);
  - `tomato.gui.history.HistoryLibraryNativeTest.malformedMetadataStaysVisibleWhileValidSelectionOpensAndSurvivesRefresh`.

### Launching for the user to test

The user tests every phase's build against their **real history**, which AGENTS.md allows when no RealmShark is running.

1. Check for a running instance first: `Get-CimInstance Win32_Process` for javaw/java with `-jar`, ignoring Gradle daemons.
2. Build with `gradlew.bat shadowJar --offline`.
3. Copy `build/libs/RealmShark-v1.2.3.jar` to a scratch folder, so the build output isn't locked.
4. Start it with `.tools\jdk-17.0.20.1+1\bin\javaw.exe -jar <copy>`, with the working directory set to the primary checkout
   `C:\Users\dap\Downloads\RealmShark-realmshark`.

Settings and extracted assets live in that launch folder; history is per user in `%LOCALAPPDATA%\RealmShark\history`.

- **To replace a running test build,** close it gracefully with `Process.CloseMainWindow()` and wait, so shutdown hooks save history.
- **Computer use cannot target the app.** It runs as javaw, and `request_access` matches no app name. The visual check is the user's;
  never claim it was done.

## 4. Decisions made along the way (all user-approved)

- **Land and shape:**
  - Explore lands on **Runs** ("what did that run give me").
  - **One shared haul component:** Full in Explore, Compact in the run recap.
  - Tables stay one click away (Pictures | Table, remembered in `ui.loot.explore.view`).
  - No "not collected yet" silhouettes, because there is no drop catalog.
- **Haul rules:**
  - Bag value order: White > Red > Orange > Blue > Teal > Gold > Egg Basket > Purple > Pink > Soulbound > Brown, with boosted bags
    just above their base.
  - Best drop: UT, then ST, then enchant rarity, then tier, then potion; the earliest drop wins ties.
  - Bags without a run are "Loot outside runs": no drop-time visit, the newest 10 sessions that have any.
- **P2 and P3 deviations:**
  - The breadcrumb and switch arrived in P3, not P2.
  - Item history is **per item, all enchant variants**.
  - Collection and item history read saved loot directly (`LootCatalog`, cached per closed session), not the archive projections.
  - Item clicks open item history (P2's Table route was removed).
- **P3b layout** (the user disliked the side-by-side split):
  - a horizontal run strip of compact cards on top, with the best drop readable at 32 px;
  - a full-width haul with **every bag open**;
  - "This dungeon so far" on the right (runs with loot, white bags, UTs, STs, stat potions, most-dropped items ignoring rarity),
    which moves below the bags under 720 px.
- **P4:**
  - a dungeon tile opens filtered Runs, and "All items from" opens filtered Collection;
  - tile rates are per run with loot;
  - Pictures moves are routed, but **run picks are not**.

## 5. Code map

| Area | Classes |
|---|---|
| Kit | `tomato.gui.kit.BagSprites`, `Breadcrumb`, `TileList.setSingleRow` |
| Haul | `tomato.gui.loot.haul.HaulModel`, `HaulView` (Full: header, best drop, every group open, side slot; Compact: shelf and one open group), `LootLine` |
| Explore | `tomato.gui.loot.explore`: `LootExplorePage` (Pictures \| Table, routes `routes()`, `itemTarget()`), `ExplorePictures` (levels, breadcrumb, entry switch, `State`), `RunsLevel` (strip and haul, dungeon panel), `RunHauls`, `LootCatalog`, `DungeonStats`/`DungeonPanel`, `CollectionModel`/`CollectionLevel`/`CollectionTileRenderer`, `ItemHistory`/`ItemLevel` |
| Runs | `tomato.gui.runs.RunFeedView` (`picker`, `strip`, `onSelect`/`onLoaded`/`select`/`isSelected`/`clearSelection`, `showDungeon`), `CompactRunCardRenderer`, `RunRecapView` (Compact haul), `RunRecapModel.Loot.Bag.dropper` |
| Shared Highlights | `HighlightsModel.kind` (public), `NotableDropRenderer` (null kind reads "Item"; host-specific open wording) |
| Wiring | `tomato.gui.TomatoGUI` (builds `LootExplorePage`, registers the Explore targets, routes item, drop and recap opens) |
| Docs | `docs/LOOT.md` (`## Explore`), `docs/ACTIVITY.md` (recap Loot bullet) |

## 6. Resume checklist

1. `git fetch`, then `git switch claude/loot-explore-p4` (or check it out in a new worktree). Confirm it's based on the latest
   `origin/main`; rebase if `main` moved, since the branch only holds docs.
2. Read the P4 plan and this handoff. Confirm with the user that P4 should start.
3. Run the verification: a whole-tree `compileTestJava` and the focused suites on `main`. Compare failures with §3.
4. Run P4 through the workflow in §3. Then launch the build for the user, open the PR, and merge on the user's word.
5. After P4, the redesign is complete. Possible follow-ups the user may raise:
   - routing other un-routed drill-downs that PR #51 listed (Dungeons › Analyze, Exalts class detail, Recordings → Live meter,
     Quest → Planner);
   - the three non-loot failing tests above.
