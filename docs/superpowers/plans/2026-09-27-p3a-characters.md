# P3a Characters: Gallery, Sheet and Journal v5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Claude-specific skill boilerplate is optional for non-Claude agents; the task steps are self-contained.

**Goal:** Turn the Characters page into a gallery of character cards that opens a full-page character sheet. The sheet has Overview, Gear, Exalts, Build, Goals, Notes, Snapshot evidence and Death annotation tabs. The journal saves the remaining character data (pet, dungeon completions, experience, backpack, per-class exalt times, vault potions) as version 5, and the P2 Home follow-ups are fixed.

**Architecture:**
- **Sheet as a page view.** The sheet is a second view of the Characters page (page 3), reached by `Destination.CHARACTER_SHEET` with a typed `SheetFocus` payload. `CharactersRouteTarget` captures and restores list-vs-sheet state, so Back works.
- **Detail pane retired.** The roster's side-by-side detail pane is removed. Its tabs move into the sheet first, then Overview, Gear and Exalts are rebuilt on kit components from a `SheetModel` built off the EDT.
- **Build.** The single `MyInfoGUI` moves into the sheet's Build tab, and every Build entry point opens it.
- **Gallery.** The gallery is a painted `JList`. It shows exactly the rows the existing roster filters select, so one filter bar and one saved view state serve the Gallery and Table views.

**Tech Stack:** Java 17, Swing, FlatLaf 3.5.4, Gson 2.9.1 (persisted types stay plain classes), JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md`:
- §1: S2, S5 and the honesty invariants
- §3.2: Simple/Analyst
- §4.4: customizable tabs
- §5.3, §5.7: components and `DisplayValue`
- §6.2: Characters
- §7: states
- §8.3: journal version 5
- §9: painted lists
- §10: accessibility
- §12: P3 row

**Depends on:** P2 merged (PR #21, `94db6f6`).

## Global Constraints

- **Branch and PR.** Continue on `claude/realmshark-ui-ux-redesign-cb0914`. Pull first; it must contain `94db6f6` and this plan. No direct commits to main, force pushes or hook bypasses. P3a is one PR; P3b follows as a separate plan and PR.
- **Commands are PowerShell, run from the worktree root.** On another workstation, point `RS_TOOLS` at that machine's tools folder (roadmap, "On another workstation"):
  ```powershell
  $env:RS_TOOLS = 'C:\Users\dap\Downloads\RealmShark-realmshark\.tools'
  $env:JAVA_HOME = "$env:RS_TOOLS\jdk-17.0.20.1+1"; $env:GRADLE_USER_HOME = "$env:RS_TOOLS\gradle-home"
  .\gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p3a-cache "-PrealmSharkBuildDir=build/p3a" <tasks>
  ```
  Steps abbreviate the last line as `GRADLE <tasks>`.
- **Validation limits.**
  - Run one Gradle invocation at a time, because UI tests open real windows.
  - Never run `scripts/Set-CiDisplay.ps1`, start live capture or send bridge deliveries.
  - Use synthetic fixtures and isolated preferences only.
- **Commits.**
  - Each commit block is PowerShell: one `git add` line with explicit paths, then `git commit -m "<subject>" -m "<body>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.
  - Never `git add -u` and never `\` line continuations.
- **Existing assertions.** Every step that touches an existing assertion says **add beside** or **replace**. Replace only when the behavior it checks intentionally changes, and state why in the step.
- **Tab restore.** Startup and live-state restore only `select()` a tab; only explicit navigation (Back, a route, "Open goals") may `show()` a hidden tab.
- **Preference hygiene.** Tests restore every preference they change: `ui.nav.*`, `ui.tabs.*`, `ui.filters.*`, `ui.home.*`, `ui.characters.*`, `ui.collapse.*`, `ui.mode`, and `DisplayModeModel.application()`. Use `@After` or `finally`.
- **Honesty (spec §1).** Unknown is never shown as zero ("—"). Estimates show "≈". Stale values are labeled. Links between modules are exact (journal key or `VisitRef` equality), never by name or time.
- **Threading.**
  - Sources are read off the EDT; the EDT only swaps immutable models and repaints.
  - `CharacterJournal` accessors are synchronized deep copies.
  - `RosterDefinitions.current()` and `PlanningMetadata.current()` load asynchronously and return an empty or unavailable value first. Render "—" or a loading state for them, never a guess.
- **Journal v5.**
  - Every new field is optional and null-tolerant. A malformed new field is normalized to null, never thrown: one throw makes the whole journal read-only.
  - Every new field is copied in `copy(CharacterRecord)`/`copy(AccountRecord)`, because `save()` persists only what those copy.
  - `sameObservation` compares every field `observe` writes.
  - No backfill guesses.
- **MyInfoGUI.** Construct exactly one `MyInfoGUI`, ever: its static `INSTANCE` is the one updated by capture.
- **Styling.** Colors come from `tomato.gui.kit.Tokens`, fonts from `ContentStyle.font(component, Type.x())` or `KitText`, and motion only from `Motion.run`. New in-memory view models may be `record`s; persisted journal types stay plain classes.
- **Stable names and indices.** No new shell page: the sheet lives on page 3 and indices stay stable until P6. Keep existing component names unless a step says otherwise: `characters-filter-bar`, `character-facet-N`, `character-search`, `character-roster`, `characters-tabs`, `nav-<page>`, `myinfo-*`.
- **Canonical stat order: life, mana, atk, def, spd, dex, vit, wis.** Exalt arrays are ordered dex, spd, vit, wis, def, atk, mana, life (`CharacterJournal.EXALT_ORDER` converts). Vault potions use canonical order.
- **Task order.** Tasks run in order, each compiles on its own, and later tasks anchor edits on code added by earlier ones.

## Decisions recorded for this phase

- **P3 ships as two PRs** (user decision, 2026-09-27).
  - P3a (this plan): P2 follow-ups, journal v5, roster gallery, character sheet, Build move, Home hero → sheet.
  - P3b: account Exalts grid, Pets gallery, the sheet's Pet and Fame tabs, Home's pet rarity chip.
- **The Table view opens the sheet** (user decision). The roster's side-by-side detail pane is retired, and its tabs move into the sheet. Gallery cards and table rows both open the full-page sheet; Back returns to the list with its scroll and selection.
- **Stat bars keep the "+N" live boost text** (user decision); no painted overlay.
- **The journal moves to version 5** (spec §8.3, from the P2 review). P2 builds open v5 read-only, so the new fields survive a rollback.
- **The sheet lives on page 3.** `Destination.CHARACTER_SHEET` maps to the Characters page. No new shell page is added, and the page indices stay stable until P6.
- **Sheet tab group.** The sheet's tabs are the new group `character` (`ui.tabs.character`).
  - Tab ids: `overview`, `gear`, `exalts`, `build`, `goals`, `notes`, `evidence`, `death`.
  - A saved order for the retired `character-detail` group is not migrated; it existed for one day (P1c).
  - The roster's saved `tab` index maps to the matching sheet tab and is only selected.
- **Death annotation tab** (spec §6.2: "shown when the character is marked dead"). It is a conditional tab (`CustomizableTabs.addWhen`), skipped like an Analyst-only tab without rewriting the saved order (spec §4.4); Mark dead makes it appear and Restore alive hides it again.
- **The Characters page keeps its Roster / Exalts / Pets tabs.** They stay `CustomizableTabs("characters")` rather than the spec's segmented control: they are already customizable and saved, and P3b rebuilds Exalts and Pets inside them.
- **Build.**
  - The single `MyInfoGUI` is hosted in the sheet's Build tab.
  - Page 6 (unlisted since P2) becomes a "Build moved" pointer until P6 deletes it.
  - Alt+7, the `build.open` search entry, the `MY_INFO` route and Home's Build action all open the sheet's Build tab for the character being played, else the most recent one.
- **Vault potions** are summed over the regular vault's chest, potion storage and gift chest as normal-potion equivalents (a greater potion counts 2). They compare directly with "potions needed". The seasonal vault is not recorded, so a seasonal character's sheet shows no vault count.

**Sheet and Build decisions (Tasks 4–8):**

- **Back link.** "‹ Characters" always leads to the list (spec §6.2).
  - When the Back entry its open pushed is still on top and returns to the list, the link calls `navigator.back()`, which restores the list's selection and scroll and pops that entry.
  - Otherwise (for example, opened from Home) it switches to the list in place, and shell Back still returns to where the sheet was opened from.
  - Back does not restore the outer Roster / Exalts / Pets tab (as in P2); routes into Characters bring the Roster tab forward.
- **Remembered tab.** The roster's view state keeps `tab`, the legacy index 0–6, so a rollback to P2 reads a valid tab. It gains `sheetTab`, the id, so Build and later tabs are remembered. Restore prefers `sheetTab`; either is only selected, when the sheet opens without an explicit tab.
- **Notes drafts** are saved when another character's sheet opens and whenever the sheet hides: another card, Back, "‹ Characters", the page's other tabs, and closing the workspace. Refreshes and background saves never replace a draft.
- **Titles.** "Gear" and "Exalts" replace "Equipment & inventory" and "Class exalts".
- **Sheet presenter.** `SheetPresenter` feeds the header and the Overview, Gear, Exalts and Build tabs, and the sheet's own tabs (notes, Goals, death, evidence) through `CharacterSheet.loaded`.
  - It rebuilds when the sheet opens a key. While the sheet shows, it checks once a second whether any cheap token changed: the key, `journal.revision()`, `LiveCharacter.revision()`, and the identity of `RosterDefinitions.current()` and `PlanningMetadata.current()`.
  - Builds run on one daemon thread, `character-sheet`, over deep copies (reused while the journal revision is unchanged); the sheet copies nothing on the EDT.
  - Opening another character clears the sheet and shows "Loading…" until that key's result applies. Results carry their key; the EDT applies only the newest one for the key still shown, and each tab skips a section equal to the one it shows. A failed build shows a warn banner (spec §7) and is retried.
  - Mark dead, Restore alive and Save notes act only once the header shows the opened character (`CharacterSheet.ready()`).
- **Build tab after capture stops.** Build (`MyInfoGUI`) describes the character in game, else the last one (`LiveCharacter.lastKnown()`). It shows only on that character's sheet; other sheets say "Build shows the character you're playing", with an Open button only while someone is in game.
- **Simple hides provenance** (spec §3.2): the sheet's snapshot evidence and tab hint are Analyst-only, so nothing in Simple ticks every second.
- **States (spec §7).** An unreadable journal or a failed save shows a warn banner in the sheet and the gallery; with no character to show, the gallery says the characters are unavailable (with the journal's status), never "No characters yet".
- **Saved views in the ⋯ menu** (spec §3.2, both modes): "Save view state" and "Reset saved view state"; the Characters page shows their status only as a warn banner when saving fails or the saved state cannot be read.
- **Times (spec §5.7).** Cards and the sheet header say "Played <ago>" from the last time in game ("Seen <ago>" only when never played), matching the Last played sort; vault counts show their age and dim after 24 h; the Exalts tab says "Changed <ago>".
- **Explicit "no pet".** An empty `<Pet/>` in the character list is saved as a known absence (`PetRecord.absent`), a missing one stays unknown.
- **Journal backup.** The first save that writes version 5 over an older file copies it once to `journal.v4.bak` (never over an existing one).
- **Deferred to P3b** (user decision after review, 2026-09-27): the Overview "pet card" (it needs P3b's pet names and rarity) and Goals "restyled as cards with progress" (P3a moves the Goals tab unchanged).
- **Shared arithmetic with Home.** Caps come from `RosterDefinitions`, as the old stat table's did. The potions, maxed and "still current" rules are Home's (`HomeModelBuilder`, now public).
  - Live values apply only when the snapshot's account and character ID match the sheet's key.
  - A map change's brief clear does not flicker "Playing now".
- **Labels.** A character not in game has stale fame, as on Home. The maxed chip always reads "N/8 maxed": WARN below 8, GOOD at 8.
  - Exalt summary: the top 3 stats by tier (ties keep stat order), then "+N more", where N counts the other stats that have at least one tier.
- **Build moves by a one-hop route redirect.**
  - `RouteTarget.redirect` sends `MY_INFO` to `CHARACTER_SHEET(SheetFocus(key, "build"))`: the live character when the journal has it, else the most recent one.
  - With no character, `MY_INFO` stays on page 6, now `BuildMovedPanel`. `pageOf(MY_INFO)` stays 6.
  - The search entry and `HomeActions.build` already open `MY_INFO`; only Alt+7 is rebound.
  - `TomatoGUI` still constructs the only `MyInfoGUI` and hands it to the sheet, which keeps it parented in a `CardLayout` card.

---

## File map

| File | Change | Task | Responsibility |
|---|---|---|---|
| `src/main/java/tomato/gui/glance/home/HomeArchive.java`, `LiveHomeSources.java`, `HomeModelBuilder.java`, `TodayTiles.java`, `RecentRunsCard.java` | Modify | 1, 5, 10 | Unreadable sessions skipped and reported (Today and Recent runs); per-session cache; recordings cache; shared maxed/potions helpers; hero key |
| `src/main/java/tomato/backend/data/TomatoData.java`, `LiveCharacter.java` | Modify | 1, 2, 8 | Publish de-duplication; content-based revision; v5 feeding (and an explicit no-pet); `Snapshot.journalKey()` |
| `src/main/java/tomato/gui/modern/WorkspaceShell.java` | Modify | 1, 4 | Hidden-row focus fallback; `pageOf(CHARACTER_SHEET)` |
| `src/main/java/tomato/backend/data/CharacterJournal.java` | Modify | 2 | Version 5 fields, `PetRecord` (with `absent`), load/copy/normalize, `characterCopy`, `storageProblem`, the one-time `journal.v4.bak` |
| `src/main/java/tomato/gui/kit/CustomizableTabs.java` | Modify | 4 | Conditional tabs (`addWhen`, `refreshConditions`) for Death annotation |
| `src/main/java/tomato/gui/roster/RosterViewState.java` | Modify | 9 | Status for hosts that keep its actions in the ⋯ menu |
| `src/main/java/tomato/gui/kit/Banner.java`, `KitLayouts.java`, `ItemTiers.java`, `KitText.java` | Create | 3 | Kit pieces promoted from Home |
| `src/main/java/tomato/gui/glance/home/HomeViews.java`, `HeroCard.java`, `NowCard.java`, `QuestsCard.java`, `RecentRunsCard.java` | Modify | 3, 10 | Delegate to the kit; hero opens the sheet |
| `src/main/java/tomato/gui/route/Destination.java`, `RouteTarget.java`, `ShellNavigator.java` | Modify | 4, 8 | `CHARACTER_SHEET`; one-hop redirect for `MY_INFO` |
| `src/main/java/tomato/gui/glance/character/SheetFocus.java`, `CharacterSheet.java`, `SheetContext.java` | Create; `CharacterSheet` modified | 4; 5–8 | Sheet route payload, sheet frame and context; presenter hooks, identity and the Build slot |
| `src/main/java/tomato/gui/character/CharactersRouteTarget.java`, `CharacterRosterView.java` | Create | 4 | Characters list/sheet routing with Back; list/sheet cards |
| `src/main/java/tomato/gui/character/CharacterJournalGUI.java`, `CharacterPanelGUI.java` | Modify | 4, 8, 9 | Side pane retired; rows exposed; `hostBuild`; gallery/table views |
| `src/main/java/tomato/gui/glance/character/SheetModel.java`, `SheetModelBuilder.java`, `SheetViews.java`, `SheetHeader.java`, `OverviewTab.java`, `SheetPresenter.java` | Create | 5 | Header and Overview built off the EDT |
| `src/main/java/tomato/gui/dps/DpsGUI.java` | Modify | 1 | Each recording projected once per catalog entry |
| `src/main/java/tomato/gui/glance/character/GearTab.java`, `EnchantDots.java`; `src/main/java/tomato/realmshark/ParseEnchants.java`, `src/main/java/tomato/gui/myinfo/BuildEstimates.java` | Create/Modify | 6 (`BuildEstimates` also 1) | Gear tab; enchant dots for the live character; `Inputs.sameSource` |
| `src/main/java/tomato/gui/glance/character/ExaltsTab.java` | Create | 7 | Class-scoped exalts with next tier, live bonus, where to earn |
| `src/main/java/tomato/gui/glance/character/BuildTab.java`, `src/main/java/tomato/gui/myinfo/BuildRoute.java`, `BuildMovedPanel.java`; `src/main/java/tomato/gui/modern/NavEntry.java` | Create/Modify | 8 | Build hosted in the sheet; Build entry points; page 6 pointer |
| `src/main/java/tomato/gui/glance/character/CharacterCardModel.java`, `CharacterCardRenderer.java`, `CharacterGallery.java`; `src/main/java/tomato/gui/character/RosterViews.java` | Create | 9 | Painted gallery, Graveyard, sort, view switch |
| `src/main/java/tomato/gui/glance/home/HomeModel.java`, `HomeActions.java` | Modify | 10 | Hero key; hero opens the sheet |
| `src/main/java/tomato/gui/TomatoGUI.java` | Modify | 4, 8, 10 | Characters route targets and goals; Build hosting, Alt+7 and page 6; the hero's sheet route |
| Tests under `src/test/java/tomato/{backend/data,gui/kit,gui/glance/home,gui/glance/character,gui/character,gui/history,gui/chat,gui/route,gui/modern,gui/myinfo}`, `src/test/java/tomato/ShellRouteRegistrationTest.java`, `src/test/java/ui/` | Create/Modify | 1–10 | See each task's **Files** list |
| `README.md`, `docs/CHARACTERS.md`, roadmap, `docs/UX-CHECKPOINT.json`, `docs/UX-EXECUTION.md`, `docs/UX-HANDOFF.md`, `docs/superpowers/plans/2026-09-27-p3a-validation.md` | Modify/Create | 1 (README), 10 | Docs, status and the validation record |

---

### Task 1: Baseline and P2 Home follow-ups

Home's archive read no longer fails because one old session's metadata is unreadable: it skips that session, and Today and Recent runs say how many such sessions may hold their records. It keeps what it learned about closed sessions between its 30-second reads. The DPS page's recordings are projected once each. The capture thread detaches Home's copy of the local character once per observation instead of at least twice per tick. Hiding a sidebar row never leaves focus or the scroll anchor on a hidden row. The README describes the Now card correctly.

**Files:**
- Modify (`src/main/java/tomato/gui/glance/home/`): `HomeArchive.java`, `LiveHomeSources.java`, `HomeModelBuilder.java`, `TodayTiles.java`, `RecentRunsCard.java`
- Modify:
  - `src/main/java/tomato/gui/dps/DpsGUI.java`
  - `src/main/java/tomato/backend/data/TomatoData.java` (`publishMyInfoPlayer`)
  - `src/main/java/tomato/backend/data/LiveCharacter.java`
  - `src/main/java/tomato/gui/myinfo/BuildEstimates.java`
  - `src/main/java/tomato/gui/modern/WorkspaceShell.java`
  - `README.md`
- Modify tests:
  - `src/test/java/tomato/gui/glance/home/`:
    - `HomeArchiveTest.java`: one replace, one add beside.
    - `TodayTilesTest.java`, `HomeModelBuilderTest.java`, `LiveHomeSourcesTest.java`, `RecentRunsCardTest.java`: add beside.
  - `src/test/java/tomato/gui/myinfo/RecordedDpsHandoffTest.java`: add beside.
  - `src/test/java/tomato/backend/data/LiveCharacterTest.java`: one replace, one add beside.
  - `src/test/java/tomato/gui/modern/WorkspaceShellLayoutTest.java`: add beside.
- Evidence (not committed): `build/p3a/evidence/baseline.txt`

**Interfaces:**
- Consumes:
  - `SessionStore.catalog()`, which throws on the EDT.
  - `SessionStore.read(List<SessionEntry>, scope, module, Class<T>, BiConsumer)`. A single-session scope never throws for another entry.
  - `SessionStore.directory()/currentId()/started()` and `LootFacts.read(store, catalog, scope, sink)`.
  - `EncounterCatalog.Entry.id`, a UUID. An entry's `DpsData` is frozen.
  - `EncounterCatalog.captured(DpsData[])` and `DpsGUI.encounters()`.
  - `Entity.observationRevision()`, which is 0 until the first `updateStats`.
  - `WorkspaceShell.contextMenu(int)`, package-private.
- Produces:
  - `HomeArchive.Totals` gets:
    - a 15th component, `int unreadableSessions`, included in equality;
    - a public 14-argument constructor in the P2 shape, which sets it to 0.
  - `HomeArchive.Result` gets a third component, `int unreadableRecent`, and a public two-argument constructor in the P2 shape, which sets it to 0.
  - `public static final class HomeArchive.Cache` with `public Cache()` and a package-private `int size()`.
  - `public static Result HomeArchive.read(SessionStore, Window, long now, ZoneId, List<RecordedEncounter>, Cache)`:
    - The five-argument `read` delegates to it with a new `Cache`.
    - Unreadable entries are skipped. TODAY counts in `unreadableSessions` those with a file (in the session folder or its module folders) modified at or after the day's start; only they can hold today's records.
    - SESSION reports 0. It still throws `IOException("Unreadable session <id>: …")` when the current session itself is unreadable.
    - `unreadableRecent` counts the unreadable sessions that may hold a run newer than the oldest one listed: every one while fewer than five runs are listed, else those with a file modified at or after that run's start.
  - `public static List<RecordedEncounter> DpsGUI.recordedEncounters(Map<String, RecordedEncounter> known)`, keyed by catalog entry id.
  - A package-private six-argument `LiveHomeSources(..., Function<Map<String, RecordedEncounter>, List<RecordedEncounter>> recordings, HomeArchive.Cache cache)`. The P2 five-argument constructor delegates to it.
  - `TodayTiles` gets a warn `HomeViews.Reason` named `home-today-unreadable`:
    - The text is "1 saved session could not be read" or "N saved sessions could not be read".
    - Known counts become `DisplayValue.partial`, with that text as the detail. A stale value still wins.
  - `HomeModelBuilder.today` is never EMPTY while `unreadableSessions > 0`. `HomeModelBuilder.runs` is LIVE with the reason "N saved sessions could not be read" while `unreadableRecent > 0`, even with no row.
  - `RecentRunsCard` shows a LIVE reason as a warn line above the rows (or the empty state); a STALE reason as before.
  - `public boolean LiveCharacter.Snapshot.sameContent(Snapshot)`. `publish` bumps `revision` only when there is no current snapshot or its content differs.
  - `public boolean BuildEstimates.Inputs.sameSource(Inputs)` compares the observation revisions (a revision of 0 never matches), the presence of each entity and the pet state.
  - `WorkspaceShell` gets package-private `Component focusTarget(int page)` and `JToggleButton scrollAnchor()`.

- [ ] **Step 1: Full-suite baseline before any code change**

Run in PowerShell from the worktree root, after the three `$env:` lines from the Global rules. `GRADLE` uses `build/p3a` and `build/p3a-cache`. The suite opens real windows: start it and leave the machine alone until it finishes.
```powershell
git status --short --branch
git pull --ff-only
git log --oneline -1
git merge-base --is-ancestor 94db6f6 HEAD; "contains 94db6f6: $($LASTEXITCODE -eq 0)"
git log --oneline -1 -- docs/superpowers/plans/2026-09-27-p3a-characters.md
New-Item -ItemType Directory -Force build/p3a/evidence | Out-Null
GRADLE --continue test shadowJar
$gradleExit = $LASTEXITCODE
$suites = Get-ChildItem build/p3a/test-results/test -Filter *.xml | ForEach-Object { ([xml](Get-Content $_.FullName -Raw)).testsuite }
function Total($name) { ($suites | ForEach-Object { [int]$_.GetAttribute($name) } | Measure-Object -Sum).Sum }
$failed = foreach ($s in $suites) { foreach ($c in $s.testcase) { if ($c.failure -or $c.error) { "FAILED $($c.classname).$($c.name)" } } }
$jar = if (Test-Path build/p3a/libs) { (Get-ChildItem build/p3a/libs -Filter *.jar).Name -join ', ' } else { 'not built' }
@("P3a baseline at $(git rev-parse --short HEAD) (merged main 94db6f6 plus the plan), $(Get-Date -Format s)",
  "tests=$(Total 'tests') failures=$(Total 'failures') errors=$(Total 'errors') skipped=$(Total 'skipped') gradleExit=$gradleExit",
  "shadowJar: $jar") + $failed | Set-Content build/p3a/evidence/baseline.txt
Get-Content build/p3a/evidence/baseline.txt
```
Expected:
- The branch is `claude/realmshark-ui-ux-redesign-cb0914`. HEAD contains P2's merge and the commit that added this plan: the `merge-base` line prints `contains 94db6f6: True`, and the last `git log` line names the plan's commit (`8bf37d0` or a later revision of it). HEAD itself is not pinned; a later merged main is fine.
- `git status --short` prints nothing: the P3 spec, the roadmap and this plan are committed.
- `baseline.txt` holds the totals line and `shadowJar: RealmShark-<version>.jar`.

If `failures` or `errors` is non-zero, **do not fix them in this task.** Report every `FAILED …` line to the coordinator and keep the file. Task 10 copies these numbers, and later tasks count only failures that are not in this list as regressions.

- [ ] **Step 2: Write the failing archive, recordings and Progress tests**

`src/test/java/tomato/gui/glance/home/HomeArchiveTest.java` — **replace** the whole method `unreadableSessionMetadataNeverBecomesCompleteTotals` (lines 40–54, which expect both windows to throw). The behavior changes on purpose: unreadable entries are skipped as `ReadSnapshot` skips them under ALL scope, and SESSION never fails because of another session. The replacement, plus a new cache test:
```java
    @Test public void unreadableSessionsAreSkippedCountedAndNeverFailThisSession() throws Exception {
        Path root = fixture(), broken = root.resolve(MORNING);
        Files.writeString(broken.resolve("session.json"), "{broken");
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            assertTrue(store.catalog().stream().anyMatch(entry -> !entry.readable()));
            HomeArchive.Result today = HomeArchive.read(store, TODAY, NOW, ZONE, List.of());
            assertEquals("The broken session's files changed today: counted, not read", 1, today.totals().unreadableSessions());
            assertEquals("Only the after-midnight Lost Halls remains", 1, today.totals().runsEntered());
            assertEquals(Long.valueOf(100), today.totals().fameGained());
            assertEquals("Recent runs skip it too", List.of(ref(ACROSS, "b2"), ref(ACROSS, "b1"), ref(YESTERDAY, "y2"), ref(YESTERDAY, "y1")),
                today.recent().stream().map(HomeArchive.RecentRun::visit).collect(Collectors.toList()));
            assertEquals("…and say it may hold a newer run: fewer than five are listed", 1, today.unreadableRecent());
            assertEquals("This session reads only the current session", 0,
                HomeArchive.read(store, SESSION, store.started() + MINUTE, ZONE, List.of()).totals().unreadableSessions());
            try (Stream<Path> files = Files.walk(broken)) {
                for (Path file : files.collect(Collectors.toList())) Files.setLastModifiedTime(file, FileTime.fromMillis(MIDNIGHT - HOUR));
            }
            assertEquals("Unchanged since before today: it cannot hold today's records", 0,
                HomeArchive.read(store, TODAY, NOW, ZONE, List.of()).totals().unreadableSessions());
            store.append("fame", new AppHistory.FameSample(4, 500, store.started() + 1_000, "Knight"));
            store.flush();   // publishes the current session's folder and metadata
            Files.writeString(root.resolve(store.currentId()).resolve("session.json"), "{broken");
            try { HomeArchive.read(store, SESSION, store.started() + MINUTE, ZONE, List.of()); fail("The current session's metadata must be readable"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Unreadable session " + store.currentId())); }
        }
    }

    @Test public void aCacheReusesClosedSessionsUntilTheirFilesChange() throws Exception {
        Path root = fixture(), fame = root.resolve(MORNING).resolve("fame.jsonl");
        HomeArchive.Cache cache = new HomeArchive.Cache();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            assertEquals(Long.valueOf(310), HomeArchive.read(store, TODAY, NOW, ZONE, List.of(), cache).totals().fameGained());
            assertTrue("Closed sessions' facts are kept", cache.size() > 0);
            FileTime written = Files.getLastModifiedTime(fame);
            String text = Files.readString(fame);
            assertTrue(text.contains("\"fame\":1600"));
            Files.writeString(fame, text.replace("\"fame\":1600", "\"fame\":1700"));   // same size
            Files.setLastModifiedTime(fame, written);
            assertEquals("Same name, size and time: the kept facts are used", Long.valueOf(310),
                HomeArchive.read(store, TODAY, NOW, ZONE, List.of(), cache).totals().fameGained());
            assertEquals("A new cache reads the file", Long.valueOf(410), HomeArchive.read(store, TODAY, NOW, ZONE, List.of()).totals().fameGained());
            Files.setLastModifiedTime(fame, FileTime.fromMillis(written.toMillis() + 2_000));
            assertEquals("A changed stamp reads it again", Long.valueOf(410), HomeArchive.read(store, TODAY, NOW, ZONE, List.of(), cache).totals().fameGained());
        }
    }
```
All imports already exist (`Stream`, `FileTime` and `Collectors` among them). Why the fame numbers come out as they do:
- Today's gain is 310: +100 across midnight, then +150 and +60 in the morning.
- The edited reading adds 100, giving 410.
- The current session starts in 2026, so it is outside the 2025 fixture day.

`src/test/java/tomato/gui/glance/home/TodayTilesTest.java` — add beside. Insert before `    @Test public void runsAreUnknownWhenNoVisitWasSaved() throws Exception {`:
```java
    @Test public void unreadableSessionsWarnAndMarkTheTotalsPartial() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TodayTiles tiles = new TodayTiles(window -> {}, TODAY, mode);
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 2, 3, true, 120L, null, null, 1, 0, 1, 2, true, 2), null));
            HomeViews.Reason banner = named(tiles, "home-today-unreadable", HomeViews.Reason.class);
            assertTrue(banner.isVisible()); assertTrue(banner.warns()); assertEquals("2 saved sessions could not be read", banner.text());
            for (String tile : new String[] {"home-tile-runs", "home-tile-fame", "home-tile-loot", "home-tile-potions"}) {
                DisplayValue value = named(tiles, tile, StatTile.class).value();
                assertEquals(tile, DisplayValue.State.PARTIAL, value.state); assertEquals(tile, "2 saved sessions could not be read", value.detail);
            }
            assertFalse("Not stale", named(tiles, "home-today-note", HomeViews.Reason.class).isVisible());
            tiles.apply(new HomeModel.Today(HomeModel.State.LIVE, TODAY, new HomeArchive.Totals(TODAY, NOW - 3_600_000L, NOW, 2, 3, true, 120L, null, null, 1, 0, 1, 2, true, 1), null));
            assertEquals("1 saved session could not be read", banner.text());
            tiles.apply(HomeModels.today(TODAY, NOW));
            assertFalse("Every session readable: no banner", banner.isVisible());
            assertEquals(DisplayValue.State.KNOWN, named(tiles, "home-tile-runs", StatTile.class).value().state);
        });
    }
```

`src/test/java/tomato/gui/glance/home/HomeModelBuilderTest.java` — add beside. Insert before `    @Test public void questsShowPinnedQuestsOnlyWithCountsAndStaleness() {`:
```java
    @Test public void aPeriodWithUnreadableSessionsIsNeverEmpty() {
        HomeArchive.Totals nothing = new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false, 1);
        assertEquals("Its runs may be in the unreadable session", State.LIVE, HomeModelBuilder.today(TODAY, new HomeArchive.Result(nothing, List.of()), null).state());
        assertEquals("The P2 constructor: every session readable", 0, new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false).unreadableSessions());
        assertNotEquals(nothing, new HomeArchive.Totals(TODAY, 0, 1, 0, 0, false, null, null, null, 0, 0, 0, 0, false, 2));
        HomeModel.Runs skipped = HomeModelBuilder.runs(new HomeArchive.Result(nothing, List.of(), 2), null);
        assertEquals("No readable run, but two sessions may hold newer ones: not empty", State.LIVE, skipped.state());
        assertEquals("2 saved sessions could not be read", skipped.reason());
        assertEquals("The P2 constructor: nothing skipped", State.EMPTY, HomeModelBuilder.runs(new HomeArchive.Result(nothing, List.of()), null).state());
    }
```

`src/test/java/tomato/gui/glance/home/LiveHomeSourcesTest.java` — add beside. Replace `import java.util.List;` with `import java.util.List;` and a new line `import java.util.Map;`, then insert before `    @Test public void unreadableJournalIsUnavailableUntilALiveCharacterCanBeShown() throws Exception {`:
```java
    @Test public void reprojectionsGetEarlierProjectionsAndArchiveReadsKeepTheirCache() throws Exception {
        List<Map<String, RecordedEncounter>> maps = new ArrayList<>();
        String[] revision = {"catalog#1"};
        java.nio.file.Path root = temp.newFolder().toPath(); HomeHistoryFixture.write(root);
        HomeArchive.Cache cache = new HomeArchive.Cache();
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            LiveHomeSources sources = new LiveHomeSources(isolated(), () -> store, (account, quest) -> false, () -> revision[0],
                known -> { maps.add(known); return List.of(); }, cache);
            sources.archive(HomeArchive.Window.TODAY, HomeHistoryFixture.NOW);
            revision[0] = "catalog#2";
            sources.archive(HomeArchive.Window.TODAY, HomeHistoryFixture.NOW);
            assertEquals(2, maps.size()); assertSame("Each re-projection gets the projections kept so far", maps.get(0), maps.get(1));
            assertTrue("The archive read through the sources' cache", cache.size() > 0);
        }
    }

```

`src/test/java/tomato/gui/glance/home/RecentRunsCardTest.java` — add beside. Insert before `    @Test public void inProgressFewerRowsStatesAndEvidence() throws Exception {`:
```java
    @Test public void aLiveReadNamesTheSessionsItCouldNotRead() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecentRunsCard card = new RecentRunsCard(opened::add, mode);
            card.apply(new HomeModel.Runs(HomeModel.State.LIVE, HomeModels.runs(NOW), "2 saved sessions could not be read"), NOW);
            HomeViews.Reason note = named(card, "home-runs-note", HomeViews.Reason.class);
            assertTrue(note.isVisible()); assertTrue(note.warns()); assertEquals("2 saved sessions could not be read", note.text());
            assertTrue("The readable rows stay", named(card, "home-run-0", JPanel.class).isVisible());
            assertEquals("2 saved sessions could not be read", card.getAccessibleContext().getAccessibleDescription());
            card.apply(new HomeModel.Runs(HomeModel.State.LIVE, List.of(), "1 saved session could not be read"), NOW);
            assertTrue("No readable run: the empty state keeps the warning", note.isVisible());
            assertNotNull(named(card, "home-runs-empty", EmptyState.class));
            card.apply(HomeModels.empty().runs(), NOW);
            assertFalse("An empty history's reason is the empty state, never a warning", note.isVisible());
        });
    }

```

`src/test/java/tomato/gui/myinfo/RecordedDpsHandoffTest.java` — add beside. Insert before `    static void awaitLoaded(RecordedDpsPanel panel) throws Exception {`:
```java
    @Test public void recordedEncountersProjectOnlyCatalogEntriesTheyHaveNotSeen() throws Exception {
        TomatoData data = new TomatoData();
        DpsData first = encounter(data, "Lost Halls", new EncounterContext(new VisitRef(SESSION, "journal:1"), 7, 1));
        data.dpsData.add(first);
        DpsGUI dps = edt(() -> new DpsGUI(data, DiscoveryLog.historyView(new ActivityJournal.State())));
        Map<String, RecordedEncounter> known = new HashMap<>();
        List<RecordedEncounter> one = DpsGUI.recordedEncounters(known);
        assertEquals(1, one.size()); assertEquals(1, known.size());
        data.dpsData.add(encounter(data, "Ice Citadel", new EncounterContext(new VisitRef(SESSION, "journal:2"), 7, 2)));
        dps.encounters().captured(data.dpsData.toArray(new DpsData[0]));
        List<RecordedEncounter> two = DpsGUI.recordedEncounters(known);
        assertEquals(2, two.size()); assertSame("A projected recording is reused, not recomputed", one.get(0), two.get(0));
        assertEquals("Ice Citadel", two.get(1).map);
        data.dpsData.remove(first);
        dps.encounters().captured(data.dpsData.toArray(new DpsData[0]));
        assertEquals(1, DpsGUI.recordedEncounters(known).size()); assertEquals("Entries that left the catalog are forgotten", 1, known.size());
        assertEquals("The one-shot projection is unchanged", 1, DpsGUI.recordedEncounters().size());
    }

```

- [ ] **Step 3: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.glance.home.*" --tests "tomato.gui.myinfo.RecordedDpsHandoffTest"`
Expected: FAIL with compilation errors, because none of these exist yet:
- `unreadableSessions()`
- `HomeArchive.Cache`
- the 15-argument `Totals` and the three-argument `Result`
- the six-argument `LiveHomeSources`
- `recordedEncounters(Map)`

- [ ] **Step 4: `HomeArchive` — skip unreadable sessions, keep per-session facts between reads**

All edits are in `src/main/java/tomato/gui/glance/home/HomeArchive.java`.

Replace `import java.nio.file.Files;` with:
```java
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
```

Replace:
```java
     * are then unknown, not zero.
     */
    public record Totals(Window window, long from, long until, int runsCompleted, int runsEntered, boolean runsRecorded,
                         Long fameGained, Double famePerHour, double[] fameSeries,
                         int untiered, int setTiered, int whiteBags, int potions, boolean lootRecorded) {
        public Totals {
            Objects.requireNonNull(window, "window");
            fameSeries = fameSeries == null ? new double[0] : fameSeries.clone();
        }
```
with:
```java
     * are then unknown, not zero. {@code unreadableSessions} counts saved sessions whose metadata could not be read and whose
     * files changed during the period, so they may hold records missing from these totals (Today only; This session reads only
     * the current session and is always 0).
     */
    public record Totals(Window window, long from, long until, int runsCompleted, int runsEntered, boolean runsRecorded,
                         Long fameGained, Double famePerHour, double[] fameSeries,
                         int untiered, int setTiered, int whiteBags, int potions, boolean lootRecorded, int unreadableSessions) {
        public Totals {
            Objects.requireNonNull(window, "window");
            fameSeries = fameSeries == null ? new double[0] : fameSeries.clone();
            if (unreadableSessions < 0) throw new IllegalArgumentException("unreadableSessions must not be negative");
        }
        /** Totals of a period whose saved sessions were all readable. */
        public Totals(Window window, long from, long until, int runsCompleted, int runsEntered, boolean runsRecorded, Long fameGained,
                      Double famePerHour, double[] fameSeries, int untiered, int setTiered, int whiteBags, int potions, boolean lootRecorded) {
            this(window, from, until, runsCompleted, runsEntered, runsRecorded, fameGained, famePerHour, fameSeries,
                untiered, setTiered, whiteBags, potions, lootRecorded, 0);
        }
```

Replace `potions == t.potions && lootRecorded == t.lootRecorded;` with `potions == t.potions && lootRecorded == t.lootRecorded` followed by a new line `                && unreadableSessions == t.unreadableSessions;`.

Replace `                Arrays.hashCode(fameSeries), untiered, setTiered, whiteBags, potions, lootRecorded);` with `                Arrays.hashCode(fameSeries), untiered, setTiered, whiteBags, potions, lootRecorded, unreadableSessions);`.

Replace:
```java
    public record Result(Totals totals, List<RecentRun> recent) {
        public Result {
            Objects.requireNonNull(totals, "totals");
            recent = recent == null ? List.of() : List.copyOf(recent);
        }
    }
```
with:
```java
    /**
     * {@code unreadableRecent} counts saved sessions whose metadata could not be read and that may hold a run newer than the
     * oldest one listed; Recent runs says so instead of implying the list is complete.
     */
    public record Result(Totals totals, List<RecentRun> recent, int unreadableRecent) {
        public Result {
            Objects.requireNonNull(totals, "totals");
            recent = recent == null ? List.of() : List.copyOf(recent);
            if (unreadableRecent < 0) throw new IllegalArgumentException("unreadableRecent must not be negative");
        }
        /** A read whose saved sessions were all readable. */
        public Result(Totals totals, List<RecentRun> recent) { this(totals, recent, 0); }
    }
```

Replace:
```java
    public static Result read(SessionStore store, Window window, long now, ZoneId zone, List<RecordedEncounter> recordings) throws IOException {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(window, "window"); Objects.requireNonNull(zone, "zone");
        List<SessionStore.SessionEntry> catalog = store.catalog();   // listed once; every module read below reuses it
        List<SessionStore.Session> sessions = new ArrayList<>();   // newest start first, as catalog() sorts them
        for (SessionStore.SessionEntry entry : catalog) {
            // Missing metadata cannot establish whether this session overlaps Today or contains a newer run.
            // Reject the combined read so the refresher retains prior results as stale, rather than showing partial totals.
            if (!entry.readable()) throw new IOException("Unreadable session " + entry.id + ": " + entry.error);
            sessions.add(entry.session());
        }
        Cache cache = new Cache(store, catalog);
```
with:
```java
    public static Result read(SessionStore store, Window window, long now, ZoneId zone, List<RecordedEncounter> recordings) throws IOException {
        return read(store, window, now, zone, recordings, new Cache());
    }

    /**
     * As above, keeping {@code kept}'s per-session facts between reads ({@link Cache}). Unreadable catalog entries are skipped,
     * as the archive queries skip them for all sessions. Today counts in {@link Totals#unreadableSessions()} those changed since
     * the day began; This session reads only the current session, so another session never fails it, while its own unreadable
     * metadata still does. Recent runs come from the readable sessions, and {@link Result#unreadableRecent()} counts the
     * unreadable ones that may hold a newer run.
     */
    public static Result read(SessionStore store, Window window, long now, ZoneId zone, List<RecordedEncounter> recordings,
                              Cache kept) throws IOException {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(window, "window"); Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(kept, "cache");
        List<SessionStore.SessionEntry> catalog = store.catalog();   // listed once; every module read below reuses it
        List<SessionStore.Session> sessions = new ArrayList<>();   // newest start first, as catalog() sorts them
        List<SessionStore.SessionEntry> unreadable = new ArrayList<>();
        for (SessionStore.SessionEntry entry : catalog) if (entry.readable()) sessions.add(entry.session()); else unreadable.add(entry);
        kept.prepare(store, catalog);
        Reader cache = new Reader(store, catalog, kept);   // this read's session facts
```

Then make five replacements:
- `Math.min(now, until)));` becomes `Math.min(now, until)), changedSince(store, unreadable, from));`
- `Long.MIN_VALUE, Long.MAX_VALUE, now));` becomes `Long.MIN_VALUE, Long.MAX_VALUE, now), 0);`
- `    private static Totals totals(Cache cache, List<SessionStore.Session> sessions, Span span) throws IOException {` becomes `    private static Totals totals(Reader cache, List<SessionStore.Session> sessions, Span span, int unreadable) throws IOException {`
- `            untiered, setTiered, whites, potions, lootRecorded);` becomes `            untiered, setTiered, whites, potions, lootRecorded, unreadable);`
- `        return new Result(totals, recent(cache, sessions, recordings == null ? List.of() : recordings));` becomes the lines below.
```java
        List<RecentRun> recent = recent(cache, sessions, recordings == null ? List.of() : recordings);
        // An unreadable session can hold a newer run only if its files changed after the oldest listed run began.
        long since = recent.size() < RECENT ? Long.MIN_VALUE : recent.get(recent.size() - 1).started();
        return new Result(totals, recent, changedSince(store, unreadable, since));
```

In `recent`'s signature, replace `recent(Cache cache,` with `recent(Reader cache,`.

Finally, replace the private class from `    /** One read per session and module per call, all over one catalog listing; recent runs reuse what the totals read. */` through its closing `    }`, which is the line before the file's final `}`, with:
```java
    /**
     * How many unreadable sessions have a file (in the session folder, or in its runs, loot, fame and fame-latest folders)
     * modified at or after {@code since}: only those can hold records from then on. A folder that cannot be listed counts,
     * because nothing rules it out; {@code since} Long.MIN_VALUE counts every unreadable session.
     */
    private static int changedSince(SessionStore store, List<SessionStore.SessionEntry> unreadable, long since) {
        int count = 0;
        for (SessionStore.SessionEntry entry : unreadable) {
            if (since == Long.MIN_VALUE) { count++; continue; }
            List<Stamp> stamp = new ArrayList<>();
            Path folder = store.directory().resolve(entry.id);
            try {
                Reader.list(stamp, folder, "");
                for (String module : Reader.FOLDERS) Reader.list(stamp, folder.resolve(module), module + "/");
            } catch (IOException unlisted) { count++; continue; }
            for (Stamp file : stamp) if (file.modified() >= since) { count++; break; }
        }
        return count;
    }

    /**
     * Per-session facts kept between reads: a crashed session's end, runs, loot bags and fame readings. One reader thread owns
     * it (Home's "home-archive"). A closed or imported session's facts are reused while its stamp is unchanged: the name, size
     * and modification time of every entry in the session folder and of the files in its runs, loot, fame and fame-latest
     * folders. The current session is read again every time and never kept.
     */
    public static final class Cache {
        private Path root;
        private final Map<String, Facts> sessions = new HashMap<>();

        public Cache() {}

        /** Sessions whose facts are kept (tests). */
        int size() { return sessions.size(); }

        /** Forgets everything when the store's folder changed, and the sessions that left the catalog. */
        private void prepare(SessionStore store, List<SessionStore.SessionEntry> catalog) {
            if (!store.directory().equals(root)) { sessions.clear(); root = store.directory(); }
            Set<String> listed = new HashSet<>();
            for (SessionStore.SessionEntry entry : catalog) listed.add(entry.id);
            sessions.keySet().retainAll(listed);
        }
    }

    /** One session's facts, each read on first use; {@code stamp} is null for the current session. */
    private static final class Facts {
        final List<Stamp> stamp;
        Long end;
        List<ActivityJournal.Visit> runs;
        List<LootFacts.Bag> loot;
        List<AppHistory.FameSample> fame;
        Facts(List<Stamp> stamp) { this.stamp = stamp; }
    }

    /** One entry as last seen: its path inside the session folder, size and modification time (epoch ms). */
    private record Stamp(String name, long size, long modified) {}

    /** One read over one catalog listing: each session is stamped at most once; its facts come from the Cache while unchanged. */
    private static final class Reader {
        private static final String[] FOLDERS = {"runs", "loot", "fame", "fame-latest"};
        private final SessionStore store;
        private final List<SessionStore.SessionEntry> catalog;
        private final Cache kept;
        private final Map<String, Facts> checked = new HashMap<>();

        Reader(SessionStore store, List<SessionStore.SessionEntry> catalog, Cache kept) { this.store = store; this.catalog = catalog; this.kept = kept; }

        private Facts facts(SessionStore.Session session) throws IOException {
            Facts facts = checked.get(session.id);
            if (facts != null) return facts;
            if (session.id.equals(store.currentId())) facts = new Facts(null);   // still being written: never kept
            else {
                List<Stamp> stamp = new ArrayList<>();
                Path folder = store.directory().resolve(session.id);
                list(stamp, folder, "");
                for (String module : FOLDERS) list(stamp, folder.resolve(module), module + "/");
                stamp.sort(Comparator.comparing(Stamp::name));
                facts = kept.sessions.get(session.id);
                if (facts == null || !facts.stamp.equals(stamp)) { facts = new Facts(stamp); kept.sessions.put(session.id, facts); }
            }
            checked.put(session.id, facts);
            return facts;
        }

        private static void list(List<Stamp> stamp, Path folder, String prefix) throws IOException {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                for (Path file : files) {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    stamp.add(new Stamp(prefix + file.getFileName(), attributes.size(), attributes.lastModifiedTime().toMillis()));
                }
            }
        }

        /** When the session ended; 0 while it is open. A crashed session (no end saved) ended when its files were last written. */
        long end(SessionStore.Session session) throws IOException {
            if (!crashed(session)) return session.ended;
            Facts facts = facts(session);
            if (facts.end == null) {
                long last = session.started;   // the folder's own entries, as before the cache
                for (Stamp file : facts.stamp) if (file.name().indexOf('/') < 0) last = Math.max(last, file.modified());
                facts.end = last;
            }
            return facts.end;
        }
        /** An earlier launch that never saved its end (a crash); the current session is still open. */
        private boolean crashed(SessionStore.Session session) {
            return session.ended <= 0 && !session.id.equals(store.currentId()) && !"Imported".equals(session.version);
        }
        List<ActivityJournal.Visit> runs(SessionStore.Session session) throws IOException {
            Facts facts = facts(session);
            if (facts.runs == null) {
                List<ActivityJournal.Visit> read = new ArrayList<>();
                store.read(catalog, session.id, "runs", ActivityJournal.Visit.class, (s, visit) -> read.add(visit));
                // The store closes unfinished visits of sessions that saved their end; a crashed session's close the same way.
                if (crashed(session)) for (ActivityJournal.Visit visit : read)
                    if (visit.ended == 0) { visit.ended = Math.max(visit.started, visit.lastSeen); visit.endReason = "App ended"; }
                facts.runs = read;
            }
            return facts.runs;
        }
        List<LootFacts.Bag> loot(SessionStore.Session session) throws IOException {
            Facts facts = facts(session);
            if (facts.loot == null) { List<LootFacts.Bag> bags = new ArrayList<>(); LootFacts.read(store, catalog, session.id, bags::add); facts.loot = bags; }
            return facts.loot;
        }
        List<AppHistory.FameSample> fame(SessionStore.Session session) throws IOException {
            Facts facts = facts(session);
            if (facts.fame == null) {
                List<AppHistory.FameSample> samples = new ArrayList<>();
                store.read(catalog, session.id, "fame", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
                store.read(catalog, session.id, "fame-latest", AppHistory.FameSample.class, (s, sample) -> samples.add(sample));
                facts.fame = samples;
            }
            return facts.fame;
        }
    }
```
Notes on this design:
- Kept lists are only read. `totals` sorts its own per-character copies. A crashed session's visits are closed once, when first read.
- A module read that throws leaves that fact unset, so the next read tries again.
- `read` is not synchronized: the cache belongs to the one `home-archive` thread, and the five-argument form uses a fresh cache.

- [ ] **Step 5: Project each recording once (`DpsGUI`, `LiveHomeSources`)**

`src/main/java/tomato/gui/dps/DpsGUI.java` (`java.util.*` is imported). Two edits in the one-shot projection keep its body in place.

Replace:
```java
    public static List<RecordedEncounter> recordedEncounters() {
        DpsGUI view = INSTANCE;
        List<RecordedEncounter> result = new ArrayList<>();
        if (view == null) return result;
        for (EncounterCatalog.Entry entry : view.encounterCatalog.entries()) {
            DpsData data = entry.data;
```
with:
```java
    public static List<RecordedEncounter> recordedEncounters() { return recordedEncounters(new HashMap<>()); }

    /**
     * As {@link #recordedEncounters()}, reusing {@code known}'s projections by catalog entry id: an entry's graph is frozen, so
     * only entries missing from {@code known} are projected. {@code known} is then replaced with this call's projections
     * (entries that left the catalog are forgotten). One caller per map, off the EDT.
     */
    public static List<RecordedEncounter> recordedEncounters(Map<String, RecordedEncounter> known) {
        DpsGUI view = INSTANCE;
        List<RecordedEncounter> result = new ArrayList<>();
        Map<String, RecordedEncounter> next = new HashMap<>();
        if (view != null) for (EncounterCatalog.Entry entry : view.encounterCatalog.entries()) {
            RecordedEncounter kept = known.get(entry.id);
            if (kept != null) { next.put(entry.id, kept); result.add(kept); continue; }
            DpsData data = entry.data;
```
Replace:
```java
            result.add(new RecordedEncounter(data.getRecordingId(), map == null || map.isEmpty() ? "Unknown encounter" : map,
                data.dungeonStartTime > 0 ? data.dungeonStartTime : null, data.totalDungeonPcTime > 0 ? data.totalDungeonPcTime : null,
                link, localDamage, window));
        }
        return result;
    }
```
with:
```java
            RecordedEncounter projected = new RecordedEncounter(data.getRecordingId(), map == null || map.isEmpty() ? "Unknown encounter" : map,
                data.dungeonStartTime > 0 ? data.dungeonStartTime : null, data.totalDungeonPcTime > 0 ? data.totalDungeonPcTime : null,
                link, localDamage, window);
            next.put(entry.id, projected);
            result.add(projected);
        }
        known.clear();
        known.putAll(next);
        return result;
    }
```

`src/main/java/tomato/gui/glance/home/LiveHomeSources.java`:
1. Replace `import java.util.function.BiPredicate;` with that line plus `import java.util.function.Function;`.
2. Replace `    private final Supplier<List<RecordedEncounter>> recordings;` with `    private final Function<Map<String, RecordedEncounter>, List<RecordedEncounter>> recordings;`.
3. Replace the block from `    // "home-archive": the recorded meters, recomputed only when the DPS page's recordings change.` through the closing `    }` of the five-argument constructor (lines 41–58 of the original file) with:
```java
    // "home-archive": per-session archive facts kept between reads, and the recorded meters, each recording projected once.
    private final HomeArchive.Cache archiveCache;
    private final Map<String, RecordedEncounter> projected = new HashMap<>();
    private String recordedRevision;
    private List<RecordedEncounter> recorded = List.of();

    public LiveHomeSources(TomatoData data, Supplier<SessionStore> store) {
        this(data, store, QuestPinning::pinned, DpsGUI::recordingsRevision, known -> DpsGUI.recordedEncounters(known), new HomeArchive.Cache());
    }

    /** Tests replace the Preferences pin lookup and the DPS page's recordings (recomputed as a whole). */
    LiveHomeSources(TomatoData data, Supplier<SessionStore> store, BiPredicate<String, QuestData> pins,
                    Supplier<String> recordingsRevision, Supplier<List<RecordedEncounter>> recordings) {
        this(data, store, pins, recordingsRevision, known -> recordings.get(), new HomeArchive.Cache());
    }

    /** {@code recordings} receives the projections kept so far (by catalog entry) and replaces them, as DpsGUI.recordedEncounters does. */
    LiveHomeSources(TomatoData data, Supplier<SessionStore> store, BiPredicate<String, QuestData> pins, Supplier<String> recordingsRevision,
                    Function<Map<String, RecordedEncounter>, List<RecordedEncounter>> recordings, HomeArchive.Cache cache) {
        this.data = Objects.requireNonNull(data, "data");
        this.store = Objects.requireNonNull(store, "store");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.recordingsRevision = Objects.requireNonNull(recordingsRevision, "recordingsRevision");
        this.recordings = Objects.requireNonNull(recordings, "recordings");
        this.archiveCache = Objects.requireNonNull(cache, "cache");
        data.progression().addListener(progressionChanges::incrementAndGet);   // Home lives as long as the app
    }
```
The new constructor takes six arguments, not five. A five-argument `Function` overload would make P2's `List::of` argument ambiguous.
4. Replace `        return HomeArchive.read(history, window, now, ZoneId.systemDefault(), recorded());` with `        return HomeArchive.read(history, window, now, ZoneId.systemDefault(), recorded(), archiveCache);`.
5. Replace `recorded = recordings.get();` with `recorded = recordings.apply(projected);`.
6. Replace the comment `    /** DpsGUI.recordedEncounters() recomputes every recording's meter: only when the recordings changed. */` with `    /** Re-projected only when the recordings changed, and then only the recordings not projected before. */`.

- [ ] **Step 6: The Progress card's unreadable-sessions line**

`src/main/java/tomato/gui/glance/home/HomeModelBuilder.java`, replace:
```java
        boolean empty = t.runsEntered() == 0 && t.fameGained() == null && t.untiered() + t.setTiered() + t.whiteBags() + t.potions() == 0;
```
with:
```java
        // Unreadable sessions may hold this period's records: never "nothing recorded" while any exist.
        boolean empty = t.unreadableSessions() == 0 && t.runsEntered() == 0 && t.fameGained() == null
            && t.untiered() + t.setTiered() + t.whiteBags() + t.potions() == 0;
```

Replace:
```java
        if (result.recent().isEmpty()) return HomeModel.Runs.placeholder(State.EMPTY, "No dungeon runs recorded yet. Enter a dungeon with capture on.");
        return new HomeModel.Runs(State.LIVE, result.recent(), "");
```
with:
```java
        // Saved sessions that could not be read may hold newer runs: say so above the rows instead of implying the list is complete.
        String skipped = result.unreadableRecent() == 0 ? "" : TodayTiles.unreadableText(result.unreadableRecent());
        if (result.recent().isEmpty() && skipped.isEmpty())
            return HomeModel.Runs.placeholder(State.EMPTY, "No dungeon runs recorded yet. Enter a dungeon with capture on.");
        return new HomeModel.Runs(State.LIVE, result.recent(), skipped);
```

`src/main/java/tomato/gui/glance/home/RecentRunsCard.java`:
1. In the class comment, replace ` * re-read keeps the last rows under a warn banner saying when they were read and why the new read failed.` with:
```java
 * re-read keeps the last rows under a warn banner saying when they were read and why the new read failed; a read that skipped
 * unreadable saved sessions says how many, because they may hold newer runs.
```
2. Replace:
```java
        String reason = stale ? text(runs.reason(), "Showing the last successful read of saved history.") : "";
        note.setText(reason, true);
        note.setVisible(stale);
```
with:
```java
        // A stale read explains itself; a live read names the saved sessions it could not read. An EMPTY reason is the empty state.
        String reason = stale ? text(runs.reason(), "Showing the last successful read of saved history.")
            : runs.state() == HomeModel.State.LIVE ? runs.reason() : "";
        note.setText(reason, true);
        note.setVisible(!reason.isEmpty());
```
3. In both places, replace `setAccessibleDescription(stale ? reason : null);` with `setAccessibleDescription(reason.isEmpty() ? null : reason);`: the no-rows branch and the line after the rows.

`src/main/java/tomato/gui/glance/home/TodayTiles.java`:

Replace `    private final HomeViews.Reason note = new HomeViews.Reason("home-today-note");` with:
```java
    private final HomeViews.Reason note = new HomeViews.Reason("home-today-note");
    private final HomeViews.Reason unreadable = new HomeViews.Reason("home-today-unreadable");
    private final JPanel banners = HomeViews.stack(Tokens.XS, note, unreadable);
```

Replace:
```java
        note.setVisible(false);
        // The stale banner sits above the tiles and takes no space (nor gap) while hidden.
        content = HomeViews.named(HomeViews.stack(0, HomeViews.beside(grid, note, BorderLayout.NORTH, Tokens.S)), "home-today-content");
```
with:
```java
        note.setVisible(false);
        unreadable.setVisible(false);
        banners.setVisible(false);
        // The stale and unreadable-sessions banners sit above the tiles and take no space (nor gap) while hidden.
        content = HomeViews.named(HomeViews.stack(0, HomeViews.beside(grid, banners, BorderLayout.NORTH, Tokens.S)), "home-today-content");
```

Replace `        boolean stale = today.state() == HomeModel.State.STALE;` with:
```java
        boolean stale = today.state() == HomeModel.State.STALE;
        // Sessions that could not be read may hold part of this period: the totals that exist are partial, with a warn line.
        String partial = totals.unreadableSessions() == 0 ? null : unreadableText(totals.unreadableSessions());
```

Add the `partial` argument in four places:
- `"Completed dungeon runs, " + source, stale)` becomes `"Completed dungeon runs, " + source, stale, partial)`.
- `gained(gain, source, stale)` becomes `gained(gain, source, stale, partial)`.
- `"UT and ST drops, " + source, stale)` becomes `"UT and ST drops, " + source, stale, partial)`.
- `"Potion drops, " + source, stale)` becomes `"Potion drops, " + source, stale, partial)`.

Replace `        note.setVisible(!reason.isEmpty());` with:
```java
        note.setVisible(!reason.isEmpty());
        if (partial != null) unreadable.setText(partial, true);
        unreadable.setVisible(partial != null);
        banners.setVisible(note.isVisible() || unreadable.isVisible());
```

Replace:
```java
            + " use the item potion flag. Run counts are unknown when no visit was saved in this period, and loot counts when no loot was.");
        getAccessibleContext().setAccessibleName("Progress: " + label + (stale ? ", last successful read" : ""));
        getAccessibleContext().setAccessibleDescription(reason.isEmpty() ? null : reason);
```
with:
```java
            + " use the item potion flag. Run counts are unknown when no visit was saved in this period, and loot counts when no loot was."
            + " Saved sessions whose details cannot be read are left out and counted in a warning; the totals are then partial.");
        getAccessibleContext().setAccessibleName("Progress: " + label + (stale ? ", last successful read" : "") + (partial != null ? ", partial" : ""));
        String described = (reason + " " + (partial == null ? "" : partial)).trim();
        getAccessibleContext().setAccessibleDescription(described.isEmpty() ? null : described);
```

Replace the two formatters `count(long value, String source, boolean stale)` and `gained(long gain, String source, boolean stale)`. That is everything from `    private static DisplayValue count(` through the closing `    }` of `gained`. The replacement:
```java
    /** Stale wins; then partial (unreadable sessions) with the missing part as its detail; else a plain count. */
    private static DisplayValue count(long value, String source, boolean stale, String partial) {
        if (stale) return DisplayValue.stale(DisplayFormat.formatInteger(value), source);
        if (partial != null) return DisplayValue.partial(DisplayFormat.formatInteger(value), partial);
        return DisplayValue.count(value, source, null);
    }

    private static DisplayValue gained(long gain, String source, boolean stale, String partial) {
        String text = (gain > 0 ? "+" : "") + DisplayFormat.formatInteger(gain);
        if (stale) return DisplayValue.stale(text, "Fame gained, " + source);
        if (partial != null) return DisplayValue.partial(text, partial);
        return gain == 0 ? DisplayValue.zero("Fame gained, " + source) : DisplayValue.known(text, "Fame gained, " + source);
    }

    /** "1 saved session could not be read", "3 saved sessions could not be read". */
    static String unreadableText(int sessions) { return sessions + (sessions == 1 ? " saved session" : " saved sessions") + " could not be read"; }
```

- [ ] **Step 7: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.glance.home.*" --tests "tomato.gui.myinfo.*" --tests "ui.HomeEvidenceTest"`
Expected: PASS. The P2 Home tests still pass without edits (the EDT refusal, the crashed-session end, the large-history read, Recent runs' stale banners), as does RecordedDpsHandoffTest's existing handoff test.

- [ ] **Step 8: Commit**
```powershell
git add src/main/java/tomato/gui/glance/home/HomeArchive.java src/main/java/tomato/gui/glance/home/LiveHomeSources.java src/main/java/tomato/gui/glance/home/HomeModelBuilder.java src/main/java/tomato/gui/glance/home/TodayTiles.java src/main/java/tomato/gui/glance/home/RecentRunsCard.java src/main/java/tomato/gui/dps/DpsGUI.java src/test/java/tomato/gui/glance/home/HomeArchiveTest.java src/test/java/tomato/gui/glance/home/TodayTilesTest.java src/test/java/tomato/gui/glance/home/HomeModelBuilderTest.java src/test/java/tomato/gui/glance/home/LiveHomeSourcesTest.java src/test/java/tomato/gui/glance/home/RecentRunsCardTest.java src/test/java/tomato/gui/myinfo/RecordedDpsHandoffTest.java
git commit -m "Skip unreadable saved sessions on Home and cache closed-session reads" -m "Home's archive read skips unreadable saved sessions instead of failing. Today counts those changed during the day and marks its totals partial with a warn line; Recent runs says how many may hold a newer run. Per-session facts are kept between reads until a session's files change, and each DPS recording is projected once." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 9: Write the failing publish de-duplication tests**

`src/test/java/tomato/backend/data/LiveCharacterTest.java`:
1. Replace `import tomato.gui.myinfo.WeaponFixture;` with `import tomato.gui.myinfo.BuildEstimates;` and a new line `import tomato.gui.myinfo.WeaponFixture;`.
2. **Replace** a snapshot in `publishClearAndLastKnownFollowTheCharacterInGame`. It published two snapshots that differ only in `observedAt` and expected a revision bump; that is no change now. Replace `        LiveCharacter.Snapshot first = snapshot(1000), second = snapshot(2000);` with:
```java
        LiveCharacter.Snapshot first = snapshot(1000);
        // A new level, so a change (a snapshot differing only in observedAt is none; see the next test).
        LiveCharacter.Snapshot second = new LiveCharacter.Snapshot("account", 7, 782, "Sample", 912, 21, 1500L, TOTALS,
            new int[]{670, 385, 75, 25, 50, 75, 40, -1}, new int[]{2711, -1, -1, -1}, 12345, 1200, 70, BONUS, null, 2000);
```
3. Add beside. Insert before `    private static void put(Entity entity, StatType type, int value) {`:
```java
    @Test public void theSameObservationIsDetachedOnceAndAnEqualSnapshotKeepsTheRevision() throws Exception {
        LiveCharacter live = new LiveCharacter();
        live.publish(snapshot(1000));
        long revision = live.revision();
        LiveCharacter.Snapshot again = snapshot(2000);
        live.publish(again);
        assertEquals("Only the publish time differs", revision, live.revision());
        assertSame("Readers still get the newest publication", again, live.current()); assertSame(again, live.lastKnown());
        live.publish(new LiveCharacter.Snapshot("account", 7, 782, "Sample", 912, 21, 1500L, TOTALS,
            new int[]{670, 385, 75, 25, 50, 75, 40, -1}, new int[]{2711, -1, -1, -1}, 12345, 1200, 70, BONUS, null, 3000));
        assertEquals("A new level is a change", revision + 1, live.revision());

        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); player.captureObjectType(782); data.player = player;
        text(player, StatType.ACCOUNT_ID_STAT, "dedupe-account");
        player.updateStats(status(StatType.LEVEL_STAT, 20), 0);
        data.publishMyInfoPlayer(player);
        LiveCharacter.Snapshot first = data.liveCharacter.current();
        long published = data.liveCharacter.revision();
        data.publishMyInfoPlayer(player);   // the tick publishes the same observation again
        assertSame("Nothing new was observed: no second detach", first, data.liveCharacter.current());
        assertEquals(published, data.liveCharacter.revision());
        player.updateStats(status(StatType.LEVEL_STAT, 21), 0);
        data.publishMyInfoPlayer(player);
        assertEquals(Integer.valueOf(21), data.liveCharacter.current().level()); assertTrue(data.liveCharacter.revision() > published);
        data.captureBoundary();   // clears the character without a new observation
        data.publishMyInfoPlayer(player);
        assertNotNull("After a clear the same observation is published again", data.liveCharacter.current());

        BuildEstimates.Inputs a = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.ABSENT);
        assertTrue("Same observations", a.sameSource(BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.ABSENT)));
        assertFalse("Another pet state", a.sameSource(BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN)));
        player.updateStats(status(StatType.LEVEL_STAT, 22), 0);
        assertFalse("A newer observation", a.sameSource(BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.ABSENT)));
        Entity untracked = new Entity(null, 2, 0);
        assertFalse("Stats set without an observation are never assumed equal",
            BuildEstimates.Inputs.detach(untracked, null, null).sameSource(BuildEstimates.Inputs.detach(untracked, null, null)));
    }

    private static packets.data.ObjectStatusData status(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value;
        packets.data.ObjectStatusData status = new packets.data.ObjectStatusData();
        status.objectId = 1; status.pos = new packets.data.WorldPosData(); status.stats = new StatData[]{stat};
        return status;
    }
```

- [ ] **Step 10: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.backend.data.LiveCharacterTest"`
Expected: FAIL with a compilation error: `cannot find symbol: method sameSource(BuildEstimates.Inputs)`.

- [ ] **Step 11: Implement `sameSource`, `sameContent` and the publish key**

`src/main/java/tomato/gui/myinfo/BuildEstimates.java`, replace:
```java
        private Inputs(Entity player, Entity pet, TomatoData.PetAvailability pets) { this.player = player; this.pet = pet; this.pets = pets; }

        /** Capture thread: copies the stats now; later packets change the live entities, never these copies. */
        public static Inputs detach(Entity player, Entity pet, TomatoData.PetAvailability pets) {
            return new Inputs(copyStats(player), copyStats(pet), pets == null ? TomatoData.PetAvailability.UNKNOWN : pets);
        }
```
with:
```java
        /** The sources' Entity.observationRevision() when copied; -1 without that entity. */
        private final long playerRevision, petRevision;

        private Inputs(Entity player, Entity pet, TomatoData.PetAvailability pets, long playerRevision, long petRevision) {
            this.player = player; this.pet = pet; this.pets = pets; this.playerRevision = playerRevision; this.petRevision = petRevision;
        }

        /** Capture thread: copies the stats now; later packets change the live entities, never these copies. */
        public static Inputs detach(Entity player, Entity pet, TomatoData.PetAvailability pets) {
            return new Inputs(copyStats(player), copyStats(pet), pets == null ? TomatoData.PetAvailability.UNKNOWN : pets,
                player == null ? -1 : player.observationRevision(), pet == null ? -1 : pet.observationRevision());
        }

        /** Copied from the same player and pet observations with the same pet state; revision 0 (no observation yet) never matches. */
        public boolean sameSource(Inputs other) {
            return other != null && pets == other.pets && (player == null) == (other.player == null) && (pet == null) == (other.pet == null)
                && playerRevision != 0 && petRevision != 0 && playerRevision == other.playerRevision && petRevision == other.petRevision;
        }
```

`src/main/java/tomato/backend/data/LiveCharacter.java`. First replace `import java.util.Objects;` with `import java.util.Arrays;` and a new line `import java.util.Objects;`. Then replace `        private static int[] copy(int[] values) { return values == null ? null : values.clone(); }` with:
```java
        private static int[] copy(int[] values) { return values == null ? null : values.clone(); }
        /** The same character state: every value and array by content, Build inputs from the same observations; observedAt ignored. */
        public boolean sameContent(Snapshot other) {
            return other != null && characterId == other.characterId && classId == other.classId && Objects.equals(account, other.account)
                && Objects.equals(name, other.name) && Objects.equals(skin, other.skin) && Objects.equals(level, other.level)
                && Objects.equals(characterFame, other.characterFame) && Arrays.equals(totals, other.totals) && Arrays.equals(base, other.base)
                && Arrays.equals(equipment, other.equipment) && Objects.equals(accountFame, other.accountFame) && Objects.equals(gold, other.gold)
                && Objects.equals(rankStars, other.rankStars) && Arrays.equals(exaltBonus, other.exaltBonus)
                && (build == null ? other.build == null : build.sameSource(other.build));
        }
```
Replace:
```java
    /** Capture thread: the local character's latest state. */
    public synchronized void publish(Snapshot value) {
        if (value == null) throw new IllegalArgumentException("Use clear() when no character is in game");
        if (!accepting) return; // A producer may finish detaching after capture stop was requested.
        current = value; lastKnown = value; revision++;
    }
```
with:
```java
    /**
     * Capture thread: the local character's latest state. The revision moves only when no character was current or the content
     * differs ({@link Snapshot#sameContent}); an equal snapshot still replaces it, so readers see its observedAt.
     */
    public synchronized void publish(Snapshot value) {
        if (value == null) throw new IllegalArgumentException("Use clear() when no character is in game");
        if (!accepting) return; // A producer may finish detaching after capture stop was requested.
        boolean changed = current == null || !current.sameContent(value);
        current = value; lastKnown = value;
        if (changed) revision++;
    }
```

`src/main/java/tomato/backend/data/TomatoData.java`, replace `    public final LiveCharacter liveCharacter = new LiveCharacter();` with:
```java
    public final LiveCharacter liveCharacter = new LiveCharacter();
    /** What Home's last detach was made from (capture thread); an unchanged key while its snapshot is current needs no new detach. */
    private record LiveKey(MyInfoIdentity identity, Entity player, long playerRevision, Entity pet, long petRevision, PetAvailability availability) {}
    private LiveKey liveKey;
    private LiveCharacter.Snapshot livePublished;
```
and replace:
```java
        // Home: detached copies of My Info's inputs; Home estimates from them on its own thread, never here.
        liveCharacter.publish(LiveCharacter.read(identity.account, identity.characterId, value,
            BuildEstimates.Inputs.detach(value, companion, availability), System.currentTimeMillis()));
```
with:
```java
        // Home: detached copies of My Info's inputs; Home estimates from them on its own thread, never here. A tick publishes at
        // least twice (the entity update, then the tick): the same observation of the same pair is detached once. My Info's own
        // snapshot above still runs every time, because its callers may set stats without a new observation.
        LiveKey key = new LiveKey(identity, value, value.observationRevision(), companion,
            companion == null ? -1 : companion.observationRevision(), availability);
        if (key.equals(liveKey) && livePublished != null && liveCharacter.current() == livePublished) return;
        liveCharacter.publish(LiveCharacter.read(identity.account, identity.characterId, value,
            BuildEstimates.Inputs.detach(value, companion, availability), System.currentTimeMillis()));
        liveKey = key;
        livePublished = liveCharacter.current();   // null when a stopped capture rejected it, so the next call publishes again
```
`MyInfoIdentity` and `Entity` compare by identity, so the record compares generations and entity instances.

- [ ] **Step 12: Run the focused tests**

Run: `GRADLE test --tests "tomato.backend.data.*" --tests "tomato.gui.myinfo.*" --tests "tomato.gui.glance.home.*"`
Expected: PASS.

- [ ] **Step 13: Commit**
```powershell
git add src/main/java/tomato/gui/myinfo/BuildEstimates.java src/main/java/tomato/backend/data/LiveCharacter.java src/main/java/tomato/backend/data/TomatoData.java src/test/java/tomato/backend/data/LiveCharacterTest.java
git commit -m "Detach Home's live character once per observation" -m "publishMyInfoPlayer skips Home's detach while the identity, player and pet observations and pet state are unchanged, and LiveCharacter moves its revision only when a snapshot's content changes." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 14: Write the failing sidebar focus test**

`src/test/java/tomato/gui/modern/WorkspaceShellLayoutTest.java` — add beside. Insert before `    private final Map<String, String> store = new HashMap<>();`:
```java
    @Test public void hidingARowNeverAnchorsOnAHiddenRowAndFocusFallsToThePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = new JPanel();
            JButton build = new JButton("Estimate");
            pages[6].add(build);
            WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, true, null, null, null, new NavLayout(store::get, store::put));
            JFrame frame = new JFrame("Hidden row focus");
            frame.setContentPane(shell); frame.setSize(1240, 800); frame.setVisible(true);   // the focus traversal policy orders only a showing window
            try {
                shell.select(6);   // Build: unlisted, no row
                click(shell.contextMenu(8), "nav-menu-hide");
                assertFalse(named(shell, "nav-8", AbstractButton.class).isVisible());
                assertNull("No visible row to anchor on while Build is current", shell.scrollAnchor());
                assertSame("Focus goes into Build, which has no row", build, shell.focusTarget(6));
                click(shell.contextMenu(3), "nav-menu-show-8");
                shell.select(8);
                click(shell.contextMenu(8), "nav-menu-hide");   // the current page's own row
                AbstractButton lootRow = named(shell, "nav-8", AbstractButton.class);
                assertTrue("The current page's row stays listed while it is current", lootRow.isVisible());
                assertSame("…so it still anchors and takes focus", lootRow, shell.scrollAnchor());
                assertSame(lootRow, shell.focusTarget(8));
                shell.select(3);
                assertFalse("Once another page is current, the hidden row leaves the sidebar", lootRow.isVisible());
                click(shell.contextMenu(10), "nav-menu-hide");
                JToggleButton characters = named(shell, "nav-3", JToggleButton.class);
                assertSame("A visible current row anchors and takes focus", characters, shell.scrollAnchor());
                assertSame(characters, shell.focusTarget(3));
            } finally { frame.dispose(); }
        });
    }

```

- [ ] **Step 15: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.modern.WorkspaceShellLayoutTest"`
Expected: FAIL with compilation errors: `cannot find symbol: method scrollAnchor()` and `focusTarget(int)`.

- [ ] **Step 16: Fall back to the page's focus target, never anchor on a hidden row**

`src/main/java/tomato/gui/modern/WorkspaceShell.java`. Replace the `change` method, from `    /** Applies a saved-layout change, then re-lays the rows and keeps focus on the row the user acted on. */` through its closing `    }` (lines 444–452), with:
```java
    /**
     * Applies a saved-layout change, then re-lays the rows and keeps focus on the row the user acted on. When that row is
     * hidden now (Hide), focus goes where page navigation puts it ({@link #focusPage}); a hidden row never anchors scrolling.
     */
    private void change(BooleanSupplier operation, int focus) {
        if (!operation.getAsBoolean()) return;
        applyLayout();
        JToggleButton row = navigation[focus];
        if (row.isVisible()) { scrollAnchor = row; row.requestFocusInWindow(); }
        else { scrollAnchor = navigation[selected].isVisible() ? navigation[selected] : null; focusPage(selected); }
        scrollSelectedLater();
    }

    /** The row the sidebar keeps in view after a change, or null (tests). */
    JToggleButton scrollAnchor() { return scrollAnchor; }
```
Then replace `focusPage` with its doc comment, from `    /**` + `     * Keyboard focus for the page just shown: its sidebar row, or for an unlisted page (Build)` through the method's closing `    }` (lines 766–777 of the original file), with:
```java
    /** Keyboard focus for the page just shown ({@link #focusTarget}). */
    private void focusPage(int page) { focusTarget(page).requestFocusInWindow(); }

    /**
     * Where page navigation puts keyboard focus: the page's sidebar row while it is visible; otherwise (an unlisted page such
     * as Build, or a hidden row) the page's first focusable component in traversal order, else the page container.
     */
    Component focusTarget(int page) {
        if (NavEntry.forPage(page).group() != NavEntry.Group.UNLISTED && navigation[page].isVisible()) return navigation[page];
        Container root = cards.getFocusCycleRootAncestor();
        FocusTraversalPolicy policy = root == null ? null : root.getFocusTraversalPolicy();
        Component first = policy == null ? null : policy.getFirstComponent(pages[page]);
        return first != null ? first : cards;
    }
```

- [ ] **Step 17: Run the shell tests**

Run: `GRADLE test --tests "tomato.gui.modern.*" --tests "ui.WorkspaceShellNavigationTest"`
Expected: PASS. `openingBuildMovesKeyboardFocusIntoThePageBecauseItHasNoRow` still passes; Build's target is unchanged.

- [ ] **Step 18: README wording and commit**

`README.md`: replace `what is happening now (area, the live meter's top three and the last key pop)` with `what is happening now (the area, and during a run the live meter's top three and the last key pop)`.
```powershell
git add src/main/java/tomato/gui/modern/WorkspaceShell.java src/test/java/tomato/gui/modern/WorkspaceShellLayoutTest.java README.md
git commit -m "Keep focus off hidden sidebar rows" -m "Hiding a row falls back to the selected page's focus target and never leaves the scroll anchor on a hidden row. The README says the Now card shows meter rows and the last key pop only during a run." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Journal v5 — pet, completions, experience, backpack, exalt times, vault potions

The journal saves version 5. It loads versions 1–4 unchanged; any other version stays read-only. Every v5 field is optional:
- A field that does not parse as its type is dropped from the JSON tree before binding.
- A value that makes no sense is normalized to unknown (null, or 0 for a time).
- Neither ever makes the journal read-only.

The capture thread feeds the new fields from five sources: the character list, CREATE's PCStats, exaltation updates, the regular vault and Pet Yard pets. An explicitly empty `<Pet/>` in the character list is saved as a known "no pet"; a list that says nothing about pets leaves the pet unknown. `copy(...)` copies every new field, because `save()` persists copies. `observe` writes no v5 field, so `sameObservation` is unchanged.

The first save that writes version 5 over an older file first copies that file once to `journal.v4.bak` beside it (never over an existing backup), so a rollback keeps the pre-v5 journal. `storageProblem()` reports an unreadable journal or a failed save for the sheet and gallery banners (Tasks 4 and 9).

**Files:**
- Modify: `src/main/java/tomato/backend/data/CharacterJournal.java`
- Modify: `src/main/java/tomato/backend/data/TomatoData.java`: `parseCharacter`, `setUserId`, `rememberCharacter`, `vaultPacketUpdate` and `publishYardPet`.
- Create test: `src/test/java/tomato/backend/data/CharacterJournalV5Test.java`
- Modify tests:
  - `CharacterJournalV4Test.java` and `CharacterFreshnessTest.java`: replace two version literals in each.
  - `CharacterPublicationTest.java`: add beside.

  All three are in `src/test/java/tomato/backend/data/`.

**Interfaces:**
- Consumes:
  - `RealmCharacter.presence` (explicitly supplied fields):
    - Pet: `pet.81` instance, `pet.82` name, `pet.83` type, `pet.84` rarity, `pet.85` max ability power, `pet.25` skin.
    - Abilities: `pet.87+i` points, `pet.90+i` power, `pet.93+i` type.
  - `RealmCharacter` values:
    - `petAbilitys` (`int[9]`, `[points, power, type]` × 3)
    - `petInstanceId/petName/petType/petRarity/petSkin/petMaxAbilityPower`
    - `exp`, `backpack`, `receivedAt`
    - `pcStats`, the list's PCStats string. The journal decodes it again, because capture overlays the decoded `charStats` of the character in game with CREATE's.
  - `RealmCharacterStats.completionCounts()`: null unless the decode was complete.
  - `VaultData.getVaultChestPots/getPotStoragePots/getGiftChestPots(int[8])`: each adds normal-potion equivalents in canonical order (greater = 2).
  - `StatType.PET_*` 81–95, `SKIN_ID`, `Entity.observedAt()`.
  - `CharacterJournalV4Test.v3Document(String)`, package-private static.
- Produces (in `tomato.backend.data.CharacterJournal`):
  - `CharacterRecord` fields:
    - `PetRecord pet`
    - `Map<String, Integer> dungeonCompletions` (positive counts; null = unknown)
    - `long dungeonCompletionsObservedAt`
    - `Long exp`, `Boolean hasBackpack`
  - `public static final class PetRecord`:
    - `Boolean absent`: `TRUE` when the character list reported an explicitly empty pet (no pet equipped); every other value is then unknown. A missing `pet` stays unknown.
    - `Long instanceId; String name;`
    - `Integer type, rarity, family, skin, maxAbilityPower;`
    - `int[] abilityType, abilityLevel, abilityPoints`, each initialized to `{-1, -1, -1}`
    - `long observedAt; String source;`
  - `AccountRecord` fields:
    - `Map<Integer, Long> exaltSeenByClass = new TreeMap<>()`
    - `int[] vaultPotions`
    - `long vaultPotionsObservedAt`
  - New methods, all `public synchronized`:
    - `CharacterRecord characterCopy(String key)`
    - `void dungeonCompletions(String accountKey, int characterId, int[] counts, long observedAt)`
    - `void vaultPotions(String accountKey, int[] potions, long observedAt)`
    - `void yardPet(String accountKey, PetRecord seen)`
    - `String storageProblem()`: null while the journal is readable and its last save succeeded, else `storageStatus()`.
  - `exalts(...)` stamps `exaltSeenByClass` for each class whose counts changed.
  - `parseCharacter` supplies the presence keys `exp`, `backpack` and `dungeons` (the last only after a complete decode), and `pet.none` for an explicitly empty `<Pet/>`.
  - Saved documents carry `"version": 5`. The first save over a version 1–4 file copies it once to `<name>.v4.bak` (`journal.json` → `journal.v4.bak`); a failed copy fails that save, so the older file is never replaced without its backup.

- [ ] **Step 1: Write the failing journal tests**

Create `src/test/java/tomato/backend/data/CharacterJournalV5Test.java`:
```java
package tomato.backend.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.RealmCharacterStats;
import tomato.realmshark.enums.CharacterStatistics;
import static org.junit.Assert.*;

/** Journal v5: pet, dungeon completions, experience, backpack, per-class exalt times and vault potions. */
public class CharacterJournalV5Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String ACCOUNT = CharacterJournal.accountKey("v5-fixture"), KEY = ACCOUNT + ":7";
    private Path file() { return temp.getRoot().toPath().resolve("Characters/journal.json"); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void write(Path path, String json) throws Exception { Files.createDirectories(path.getParent()); Files.write(path, json.getBytes(StandardCharsets.UTF_8)); }
    private static String v3() { return CharacterJournalV4Test.v3Document(ACCOUNT); }

    /** PCStats with one Pirate Cave count (the encoding CharacterPublicationTest uses). */
    static String completions(int count) {
        byte[] bytes = new byte[21]; bytes[0] = 1;
        int bit = CharacterStatistics.PIRATE_CAVE.getPcStatId();
        bytes[4 + bit / 8] = (byte) (1 << (bit % 8)); bytes[20] = (byte) count;
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
    private static int[] counts(int pirateCave) {
        int[] counts = new int[CharacterStatistics.DUNGEON_NAMES.size()];
        counts[CharacterStatistics.getDungeonIndex("Pirate Cave")] = pirateCave;
        return counts;
    }
    /** A character-list entry with everything v5 reads, marked supplied as the metadata parser marks it. */
    private static RealmCharacter listed(long at, int pirateCave) {
        RealmCharacter c = new RealmCharacter(); c.charId = 7; c.receivedAt = at;
        c.exp = 30_000; c.supplied("exp", at, "Character list"); c.backpack = true; c.supplied("backpack", at, "Character list");
        c.pcStats = completions(pirateCave); c.charStats = new RealmCharacterStats(); c.charStats.decode(c.pcStats); c.supplied("dungeons", at, "Character list");
        c.petName = "Pup"; c.petInstanceId = 42; c.petType = 3; c.petRarity = 2; c.petSkin = 100; c.petMaxAbilityPower = 70;
        for (int field : new int[]{81, 82, 83, 84, 85, 25, 87, 88, 89, 90, 91, 92, 93, 94, 95}) c.supplied("pet." + field, at, "Character list");
        c.petAbilitys = new int[]{1000, 50, 407, 800, 40, 408, 600, 30, 406};
        return c;
    }

    @Test public void versionFourLoadsWithTheNewFieldsUnknownSavesAsFiveAndSixStaysReadOnly() throws Exception {
        Path path = file(); write(path, v3().replace("\"version\":3", "\"version\":4"));
        CharacterJournal j = new CharacterJournal(path);
        assertTrue(j.readable());
        CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
        assertEquals("Sample", r.name);
        assertNull(r.pet); assertNull(r.dungeonCompletions); assertEquals(0, r.dungeonCompletionsObservedAt); assertNull(r.exp); assertNull(r.hasBackpack);
        CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
        assertTrue(a.exaltSeenByClass.isEmpty()); assertNull(a.vaultPotions); assertEquals(0, a.vaultPotionsObservedAt);
        assertNull("An unknown key", j.characterCopy(ACCOUNT + ":99")); assertNull(j.characterCopy(null));
        j.vaultPotions(ACCOUNT, new int[]{3, 1, 2, 1, 0, 0, 0, 0}, 5_000); j.save();
        assertTrue(read(path).contains("\"version\": 5"));
        CharacterJournal reopened = new CharacterJournal(path);
        assertArrayEquals(new int[]{3, 1, 2, 1, 0, 0, 0, 0}, reopened.accountCopy(ACCOUNT).vaultPotions);
        assertEquals("v4 values survive", 5, reopened.accountCopy(ACCOUNT).exalts.get(782)[0]);
        Path newerPath = temp.newFolder().toPath().resolve("journal.json"); String newer = v3().replace("\"version\":3", "\"version\":6");
        write(newerPath, newer);
        CharacterJournal later = new CharacterJournal(newerPath);
        assertFalse(later.readable()); assertTrue(later.storageStatus().contains("Original preserved"));
        later.vaultPotions(ACCOUNT, new int[8], 1_000); later.save();
        assertEquals(newer, read(newerPath));
    }

    @Test public void malformedNewFieldsAreUnknownAndTheJournalStaysWritable() throws Exception {
        Path path = file();
        write(path, v3().replace("\"version\":3", "\"version\":5")
            .replace("\"source\":\"Captured character\"}", "\"source\":\"Captured character\",\"pet\":{\"instanceId\":42,\"abilityType\":[1,2]},"
                + "\"dungeonCompletions\":{\"Not a dungeon\":3},\"dungeonCompletionsObservedAt\":\"soon\",\"exp\":-5,\"hasBackpack\":true}")
            .replace("\"exaltSeen\":300", "\"exaltSeen\":300,\"exaltSeenByClass\":{\"wizard\":5},\"vaultPotions\":[1,2,3]"));
        CharacterJournal j = new CharacterJournal(path);
        assertTrue("One malformed optional field never makes the journal read-only", j.readable());
        CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
        assertNull(r.pet); assertNull(r.dungeonCompletions); assertEquals(0, r.dungeonCompletionsObservedAt); assertNull(r.exp);
        assertEquals("A well-formed field beside them is kept", Boolean.TRUE, r.hasBackpack);
        CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
        assertTrue(a.exaltSeenByClass.isEmpty()); assertNull(a.vaultPotions); assertEquals(5, a.exalts.get(782)[0]);
        j.notes(KEY, "still writable"); j.save();
        assertTrue(read(path).contains("still writable")); assertTrue(read(path).contains("\"version\": 5"));
    }

    @Test public void theCharacterListFillsPetCompletionsExperienceAndBackpack() {
        CharacterJournal j = new CharacterJournal(file());
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        CharacterJournal.CharacterRecord r = j.characterCopy(KEY);
        assertEquals(Long.valueOf(30_000), r.exp); assertEquals(Boolean.TRUE, r.hasBackpack);
        assertEquals(Map.of("Pirate Cave", 3), r.dungeonCompletions); assertEquals(5_000, r.dungeonCompletionsObservedAt);
        assertNotNull("Supplied fields keep their provenance", r.fields.get("dungeons"));
        CharacterJournal.PetRecord pet = r.pet;
        assertEquals(Long.valueOf(42), pet.instanceId); assertEquals("Pup", pet.name); assertEquals(Integer.valueOf(3), pet.type);
        assertEquals(Integer.valueOf(2), pet.rarity); assertEquals(Integer.valueOf(100), pet.skin); assertEquals(Integer.valueOf(70), pet.maxAbilityPower);
        assertNull("The character list has no family", pet.family);
        assertArrayEquals(new int[]{407, 408, 406}, pet.abilityType); assertArrayEquals(new int[]{50, 40, 30}, pet.abilityLevel);
        assertArrayEquals(new int[]{1000, 800, 600}, pet.abilityPoints); assertEquals(5_000, pet.observedAt); assertEquals("Character list", pet.source);
        RealmCharacter bare = new RealmCharacter(); bare.charId = 7; bare.receivedAt = 6_000;
        j.mergeRoster(ACCOUNT, List.of(bare));
        r = j.characterCopy(KEY);
        assertEquals("Fields a list does not report keep their values", Long.valueOf(30_000), r.exp);
        assertNotNull(r.pet); assertEquals(Map.of("Pirate Cave", 3), r.dungeonCompletions);
        j.mergeRoster(ACCOUNT, List.of(listed(4_000, 1)));
        assertEquals("An older list never replaces newer completions", Map.of("Pirate Cave", 3), j.characterCopy(KEY).dungeonCompletions);
        assertEquals("nor a newer pet", 5_000, j.characterCopy(KEY).pet.observedAt);
    }

    @Test public void completionsExaltTimesVaultPotionsAndYardPetsAreRecordedOnlyWhenValidAndNew() {
        CharacterJournal j = new CharacterJournal(file());
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        j.dungeonCompletions(ACCOUNT, 7, counts(4), 6_000);
        assertEquals(Map.of("Pirate Cave", 4), j.characterCopy(KEY).dungeonCompletions);
        long revision = j.revision();
        j.dungeonCompletions(ACCOUNT, 7, counts(9), 5_500);   // older than what is known
        j.dungeonCompletions(ACCOUNT, 7, new int[3], 7_000);   // not one count per dungeon
        j.dungeonCompletions(ACCOUNT, 8, counts(9), 7_000);    // no such character
        j.dungeonCompletions(ACCOUNT, 7, counts(4), 6_000);    // nothing new
        assertEquals(revision, j.revision());
        long before = System.currentTimeMillis();
        j.exalts(ACCOUNT, Map.of(782, new int[]{0, 0, 0, 0, 0, 0, 0, 30}, 784, new int[]{1, 1, 1, 1, 1, 1, 1, 1}));
        Map<Integer, Long> seen = j.accountCopy(ACCOUNT).exaltSeenByClass;
        assertEquals(Set.of(782, 784), seen.keySet()); assertTrue(seen.get(782) >= before);
        revision = j.revision();
        j.exalts(ACCOUNT, Map.of(782, new int[]{0, 0, 0, 0, 0, 0, 0, 30}));
        assertEquals("Unchanged counts are not stamped again", revision, j.revision());
        j.vaultPotions(ACCOUNT, new int[]{3, 1, 2, 1, 0, 0, 0, 0}, 8_000);
        j.vaultPotions(ACCOUNT, new int[]{9, 9, 9, 9, 9, 9, 9, 9}, 7_000);    // older
        j.vaultPotions(ACCOUNT, new int[]{1, 2, 3}, 9_000);                  // not 8 stats
        j.vaultPotions(ACCOUNT, new int[]{-1, 0, 0, 0, 0, 0, 0, 0}, 9_000);  // negative
        assertArrayEquals(new int[]{3, 1, 2, 1, 0, 0, 0, 0}, j.accountCopy(ACCOUNT).vaultPotions);
        assertEquals(8_000, j.accountCopy(ACCOUNT).vaultPotionsObservedAt);

        CharacterJournal.PetRecord other = new CharacterJournal.PetRecord(); other.instanceId = 43L; other.family = 1; other.observedAt = 9_000;
        j.yardPet(ACCOUNT, other);
        assertNull("Another pet changes nothing", j.characterCopy(KEY).pet.family);
        CharacterJournal.PetRecord yard = new CharacterJournal.PetRecord();
        yard.instanceId = 42L; yard.family = 4; yard.rarity = 3; yard.abilityLevel = new int[]{55, -1, -1}; yard.observedAt = 9_000; yard.source = "Pet Yard capture";
        j.yardPet(CharacterJournal.accountKey("someone-else"), yard);
        assertNull("Another account's characters are not matched", j.characterCopy(KEY).pet.family);
        j.yardPet(ACCOUNT, yard);
        CharacterJournal.PetRecord pet = j.characterCopy(KEY).pet;
        assertEquals(Integer.valueOf(4), pet.family); assertEquals(Integer.valueOf(3), pet.rarity); assertEquals("Unreported values stay", "Pup", pet.name);
        assertArrayEquals(new int[]{55, 40, 30}, pet.abilityLevel); assertEquals(9_000, pet.observedAt); assertEquals("Pet Yard capture", pet.source);
        revision = j.revision();
        yard.observedAt = 10_000; j.yardPet(ACCOUNT, yard);
        assertEquals("The same values seen again do not dirty the journal", revision, j.revision());
        j.mergeRoster(ACCOUNT, List.of(listed(11_000, 3)));
        assertEquals("A newer list keeps the family of the same pet", Integer.valueOf(4), j.characterCopy(KEY).pet.family);
    }

    @Test public void anExplicitlyEmptyPetIsSavedAsNoPetAndAMissingOneStaysUnknown() throws Exception {
        Path path = file();
        CharacterJournal j = new CharacterJournal(path);
        RealmCharacter bare = new RealmCharacter(); bare.charId = 7; bare.receivedAt = 4_000;
        j.mergeRoster(ACCOUNT, List.of(bare));
        assertNull("A list that says nothing about a pet leaves it unknown", j.characterCopy(KEY).pet);
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        RealmCharacter none = new RealmCharacter(); none.charId = 7; none.receivedAt = 6_000; none.supplied("pet.none", 6_000, "Character list");
        j.mergeRoster(ACCOUNT, List.of(none));
        CharacterJournal.PetRecord pet = j.characterCopy(KEY).pet;
        assertEquals("An explicitly empty pet is known: no pet", Boolean.TRUE, pet.absent);
        assertNull(pet.instanceId); assertNull(pet.name); assertArrayEquals(new int[]{-1, -1, -1}, pet.abilityType);
        assertEquals(6_000, pet.observedAt); assertEquals("Character list", pet.source);
        CharacterJournal.PetRecord yard = new CharacterJournal.PetRecord(); yard.instanceId = 42L; yard.family = 4; yard.observedAt = 7_000;
        j.yardPet(ACCOUNT, yard);
        assertEquals("The Pet Yard never fills in a character without a pet", Boolean.TRUE, j.characterCopy(KEY).pet.absent);
        j.save();
        assertTrue(read(path).contains("\"absent\": true"));
        assertEquals(Boolean.TRUE, new CharacterJournal(path).characterCopy(KEY).pet.absent);
        j.mergeRoster(ACCOUNT, List.of(listed(8_000, 3)));
        assertNull("A newer list with a pet replaces the absence", j.characterCopy(KEY).pet.absent);
        assertEquals("Pup", j.characterCopy(KEY).pet.name);
        Path contradictory = temp.newFolder().toPath().resolve("journal.json");
        write(contradictory, v3().replace("\"version\":3", "\"version\":5")
            .replace("\"source\":\"Captured character\"}", "\"source\":\"Captured character\",\"pet\":{\"absent\":true,\"instanceId\":42}}"));
        CharacterJournal odd = new CharacterJournal(contradictory);
        assertTrue(odd.readable()); assertNull("No pet and a pet at once is unknown, never a load failure", odd.characterCopy(KEY).pet);
    }

    @Test public void theFirstVersionFiveSaveKeepsOneBackupOfTheOlderFile() throws Exception {
        Path path = file(), backup = path.resolveSibling("journal.v4.bak");
        String v4 = v3().replace("\"version\":3", "\"version\":4");
        write(path, v4);
        CharacterJournal j = new CharacterJournal(path);
        assertFalse("Loading alone writes nothing", Files.exists(backup));
        j.notes(KEY, "first"); j.save();
        assertEquals("The older file is kept once, byte for byte", v4, read(backup));
        assertTrue(read(path).contains("\"version\": 5"));
        j.notes(KEY, "second"); j.save();
        assertEquals("Later saves never replace the backup", v4, read(backup));
        Files.delete(backup);
        CharacterJournal five = new CharacterJournal(path); five.notes(KEY, "third"); five.save();
        assertFalse("A version 5 file needs no backup", Files.exists(backup));
        Path other = temp.newFolder().toPath().resolve("journal.json"), kept = other.resolveSibling("journal.v4.bak");
        write(other, v4); write(kept, "an earlier backup");
        CharacterJournal again = new CharacterJournal(other); again.notes(KEY, "fourth"); again.save();
        assertEquals("An existing backup is never overwritten", "an earlier backup", read(kept));
        assertTrue(read(other).contains("fourth"));
    }

    @Test public void storageProblemsNameAnUnreadableFileAndAFailedSave() throws Exception {
        Path path = file(); write(path, "{broken");
        CharacterJournal broken = new CharacterJournal(path);
        assertFalse(broken.readable()); assertTrue(broken.storageProblem().contains("Cannot read"));
        CharacterJournal failing = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"),
            (target, json) -> { throw new java.io.IOException("Synthetic save failure"); });
        assertNull("Readable and never failed: no problem", failing.storageProblem());
        failing.mergeRoster(ACCOUNT, List.of(listed(5_000, 3))); failing.save();
        assertTrue(failing.storageProblem(), failing.storageProblem().startsWith("Save failed"));
    }

    @Test public void copiesAreDeepAndEveryNewFieldSurvivesSaveAndReload() throws Exception {
        Path path = file();
        CharacterJournal j = new CharacterJournal(path);
        j.mergeRoster(ACCOUNT, List.of(listed(5_000, 3)));
        j.exalts(ACCOUNT, Map.of(782, new int[]{0, 0, 0, 0, 0, 0, 0, 30}));
        j.vaultPotions(ACCOUNT, new int[]{3, 1, 2, 1, 0, 0, 0, 0}, 8_000);
        CharacterJournal.CharacterRecord copy = j.characterCopy(KEY);
        copy.pet.abilityType[0] = 1; copy.pet.name = "Changed"; copy.dungeonCompletions.put("Lost Halls", 9);
        CharacterJournal.AccountRecord account = j.accountCopy(ACCOUNT);
        account.vaultPotions[0] = 99; account.exaltSeenByClass.put(1, 1L);
        assertEquals(407, j.characterCopy(KEY).pet.abilityType[0]); assertEquals("Pup", j.characterCopy(KEY).pet.name);
        assertEquals(Map.of("Pirate Cave", 3), j.characterCopy(KEY).dungeonCompletions);
        assertEquals(3, j.accountCopy(ACCOUNT).vaultPotions[0]); assertFalse(j.accountCopy(ACCOUNT).exaltSeenByClass.containsKey(1));
        j.save();
        CharacterJournal.CharacterRecord r = new CharacterJournal(path).characterCopy(KEY);
        CharacterJournal.AccountRecord a = new CharacterJournal(path).accountCopy(ACCOUNT);
        assertEquals(Long.valueOf(30_000), r.exp); assertEquals(Boolean.TRUE, r.hasBackpack);
        assertEquals(Map.of("Pirate Cave", 3), r.dungeonCompletions); assertEquals(5_000, r.dungeonCompletionsObservedAt);
        assertEquals("Pup", r.pet.name); assertEquals(Long.valueOf(42), r.pet.instanceId); assertArrayEquals(new int[]{50, 40, 30}, r.pet.abilityLevel);
        assertEquals(5_000, r.pet.observedAt); assertEquals("Character list", r.pet.source);
        assertArrayEquals(new int[]{3, 1, 2, 1, 0, 0, 0, 0}, a.vaultPotions); assertEquals(8_000, a.vaultPotionsObservedAt);
        assertTrue(a.exaltSeenByClass.containsKey(782));
    }
}
```

Version literals (**replace**: the journal now writes and accepts version 5, so 6 is the first newer version):
- `CharacterJournalV4Test.java`:
  - `saved.contains("\"version\": 4")` becomes `saved.contains("\"version\": 5")`.
  - `replace("\"version\":3", "\"version\":5");` becomes `replace("\"version\":3", "\"version\":6");`. This is in `newerOrMalformedJournalsStayReadOnly`.
- `CharacterFreshnessTest.java`:
  - `.replace("\"version\": 4", "\"version\": 1");` becomes `.replace("\"version\": 5", "\"version\": 1");`.
  - `.contains("\"version\": 4")` becomes `.contains("\"version\": 5")`.

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.backend.data.CharacterJournal*" --tests "tomato.backend.data.CharacterFreshnessTest"`
Expected: FAIL with compilation errors: `cannot find symbol: method characterCopy(String)`, `class PetRecord`, `method vaultPotions(...)`, `method storageProblem()`.

- [ ] **Step 3: Implement v5 in `CharacterJournal`**

All edits are in `src/main/java/tomato/backend/data/CharacterJournal.java`.

1. Replace `import com.google.gson.GsonBuilder;` with that line followed by:
```java
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
```
2. Replace `import tomato.realmshark.RealmCharacter;` with that line plus `import tomato.realmshark.RealmCharacterStats;`, and `import tomato.realmshark.enums.CharacterClass;` with that line plus `import tomato.realmshark.enums.CharacterStatistics;`.

3. Replace:
```java
        public DeathAnnotation deathAnnotation;
    }
```
with:
```java
        public DeathAnnotation deathAnnotation;
        /** v5: the pet as the character list last reported it (absent = TRUE: no pet), refreshed (with its family) by the Pet Yard; null = unknown. */
        public PetRecord pet;
        /** v5: dungeon name → completions from a complete PCStats decode, positive counts only. Null = unknown; absent in a map = 0. */
        public Map<String, Integer> dungeonCompletions;
        public long dungeonCompletionsObservedAt;
        /** v5: from the character list; null = unknown. */
        public Long exp;
        public Boolean hasBackpack;
    }
    /**
     * v5: a character's pet. A null value was not reported; ability arrays are in slot order with -1 = unknown. {@code absent}
     * TRUE means the character list said the character has no pet (an explicitly empty element): a known "No pet", with every
     * other value unknown. A character whose record has no PetRecord at all is unknown, never "No pet".
     */
    public static final class PetRecord {
        public Boolean absent;
        public Long instanceId;
        public String name;
        public Integer type, rarity, family, skin, maxAbilityPower;
        public int[] abilityType = {-1, -1, -1}, abilityLevel = {-1, -1, -1}, abilityPoints = {-1, -1, -1};
        public long observedAt;
        public String source;
    }
```
4. Replace:
```java
        public long accountStatsObservedAt;
    }
```
with:
```java
        public long accountStatsObservedAt;
        /** v5: class id → when that class's exalt counts last changed in this journal (epoch ms). */
        public Map<Integer, Long> exaltSeenByClass = new TreeMap<>();
        /** v5: stat potions in the regular vault chest, potion storage and gift chest, canonical order, greater = 2; null = unknown. */
        public int[] vaultPotions;
        public long vaultPotionsObservedAt;
    }
```
5. Replace `        int version = 4;` with `        int version = 5;`, and `    private final Map<String, CharacterRecord> pendingAlive = new HashMap<>();` with that line followed by:
```java
    /** A loaded version 1-4 file not yet backed up: the first save copies it once to <name>.v4.bak (saver thread, saveLock held). */
    private volatile boolean backupPending;
```
6. Replace:
```java
                Document loaded = JSON.fromJson(reader, Document.class);
                if (loaded == null || (loaded.version < 1 || loaded.version > 4) || loaded.characters == null || loaded.accounts == null)
```
with:
```java
                JsonElement tree = JsonParser.parseReader(reader);
                dropMalformedV5Fields(tree);
                Document loaded = JSON.fromJson(tree, Document.class);
                if (loaded == null || (loaded.version < 1 || loaded.version > 5) || loaded.characters == null || loaded.accounts == null)
```
7. Replace `                    if (r.deathAnnotation != null) validateAnnotation(r.deathAnnotation);` with that line followed by `                    normalizeV5(r);`.
8. Replace `                    if (a.accountStatsObservedAt < 0) throw new IOException("Invalid account observation time");` with that line followed by `                    normalizeV5(a);`.
9. Replace `                loaded.version = 4;` with:
```java
                backupPending = loaded.version < 5; // a pre-version-5 file is copied once before version 5 first replaces it
                loaded.version = 5;
```

10. In `mergeRoster`, replace `            if (c.presence.containsKey("created")) r.created = c.date;` with:
```java
            if (c.presence.containsKey("created")) r.created = c.date;
            if (c.presence.containsKey("exp")) r.exp = c.exp;
            if (c.presence.containsKey("backpack")) r.hasBackpack = c.backpack;
            // A delayed list never replaces newer completions (CREATE's PCStats) or a newer pet (the Pet Yard). The list's own
            // PCStats string is decoded again: capture overlays the decoded stats of the character in game with CREATE's.
            if (c.presence.containsKey("dungeons") && c.receivedAt >= r.dungeonCompletionsObservedAt) {
                Map<String, Integer> completions = completions(listedCompletions(c.pcStats));
                if (completions != null) { r.dungeonCompletions = completions; r.dungeonCompletionsObservedAt = c.receivedAt; }
            }
            PetRecord pet = rosterPet(c, r.pet);
            if (pet != null) r.pet = pet;
```
11. In `exalts`, replace `                a.exalts.put(entry.getKey(), entry.getValue().clone()); a.exaltSeen = System.currentTimeMillis(); changed();` with:
```java
                long now = System.currentTimeMillis();
                a.exalts.put(entry.getKey(), entry.getValue().clone()); a.exaltSeen = now; a.exaltSeenByClass.put(entry.getKey(), now); changed();
```

12. Insert before `    /** The captured EXALTED_* bonuses in canonical stat order, or null unless all eight were captured. */`:
```java
    /** A complete PCStats decode of one character: counts in CharacterStatistics.DUNGEON_NAMES order. Anything else is ignored. */
    public synchronized void dungeonCompletions(String accountKey, int characterId, int[] counts, long observedAt) {
        CharacterRecord r = observable(accountKey, characterId);
        Map<String, Integer> next = completions(counts);
        if (r == null || next == null || observedAt < r.dungeonCompletionsObservedAt) return;
        if (next.equals(r.dungeonCompletions) && observedAt == r.dungeonCompletionsObservedAt) return;
        r.dungeonCompletions = next; r.dungeonCompletionsObservedAt = observedAt; changed();
    }
    /** The regular vault's stat potions (8 entries, canonical order, normal-potion equivalents) seen at {@code observedAt}. */
    public synchronized void vaultPotions(String accountKey, int[] potions, long observedAt) {
        if (accountKey == null || potions == null || potions.length != 8 || Arrays.stream(potions).anyMatch(n -> n < 0)) return;
        AccountRecord a = account(accountKey);
        if (observedAt < a.vaultPotionsObservedAt || Arrays.equals(a.vaultPotions, potions) && observedAt == a.vaultPotionsObservedAt) return;
        a.vaultPotions = potions.clone(); a.vaultPotionsObservedAt = observedAt; changed();
    }
    /**
     * A Pet Yard pet: each of the account's characters whose pet has this instance id takes its family and other reported values
     * (null values and -1 abilities keep what is known). Values seen again unchanged do not dirty the journal.
     */
    public synchronized void yardPet(String accountKey, PetRecord seen) {
        if (accountKey == null || seen == null || seen.instanceId == null || !validPet(seen)) return;
        for (CharacterRecord record : document.characters) {
            if (!accountKey.equals(record.account)) continue;
            CharacterRecord r = record.dead ? pendingAlive.get(record.key) : record;
            if (r == null || r.pet == null || !seen.instanceId.equals(r.pet.instanceId)) continue;
            PetRecord next = copy(r.pet);
            if (seen.name != null) next.name = seen.name;
            if (seen.type != null) next.type = seen.type;
            if (seen.rarity != null) next.rarity = seen.rarity;
            if (seen.family != null) next.family = seen.family;
            if (seen.skin != null) next.skin = seen.skin;
            if (seen.maxAbilityPower != null) next.maxAbilityPower = seen.maxAbilityPower;
            for (int i = 0; i < 3; i++) {
                if (seen.abilityType[i] >= 0) next.abilityType[i] = seen.abilityType[i];
                if (seen.abilityLevel[i] >= 0) next.abilityLevel[i] = seen.abilityLevel[i];
                if (seen.abilityPoints[i] >= 0) next.abilityPoints[i] = seen.abilityPoints[i];
            }
            if (samePetValues(next, r.pet)) continue;
            next.observedAt = Math.max(next.observedAt, seen.observedAt);
            next.source = seen.source == null ? "Pet Yard capture" : seen.source;
            r.pet = next; changed();
        }
    }
    /** Where new observations of a character go: its record, or while it is marked dead its pending alive copy (null if none). */
    private CharacterRecord observable(String accountKey, int characterId) {
        if (accountKey == null) return null;
        String key = accountKey + ":" + characterId;
        CharacterRecord r = find(key);
        return r == null || !r.dead ? r : pendingAlive.get(key);
    }
    /** Dungeon name → count (positive counts only) from one count per CharacterStatistics.DUNGEON_NAMES entry; else null. */
    private static Map<String, Integer> completions(int[] counts) {
        List<String> names = CharacterStatistics.DUNGEON_NAMES;
        if (counts == null || counts.length != names.size()) return null;
        Map<String, Integer> result = new TreeMap<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < 0) return null;
            if (counts[i] > 0) result.put(names.get(i), counts[i]);
        }
        return result;
    }
    /** A complete decode of a character-list PCStats string: counts in CharacterStatistics.DUNGEON_NAMES order, else null. */
    private static int[] listedCompletions(String pcStats) {
        if (pcStats == null) return null;
        try { RealmCharacterStats stats = new RealmCharacterStats(); stats.decode(pcStats); return stats.completionCounts(); }
        catch (RuntimeException malformed) { return null; }
    }
    /** The list's pet, or null when it reported none or is older than the known pet; the same pet keeps its Pet Yard family. */
    private static PetRecord rosterPet(RealmCharacter c, PetRecord known) {
        boolean reported = false;
        for (String field : c.presence.keySet()) if (field.startsWith("pet.")) { reported = true; break; }
        if (!reported || known != null && c.receivedAt < known.observedAt) return null;
        PetRecord pet = new PetRecord();
        // An explicitly empty pet element: no pet is equipped (known), unlike a list that says nothing about pets (unknown).
        if (c.presence.containsKey("pet.none")) { pet.absent = Boolean.TRUE; pet.observedAt = c.receivedAt; pet.source = "Character list"; return pet; }
        if (c.presence.containsKey("pet.81")) pet.instanceId = (long) c.petInstanceId;
        if (c.presence.containsKey("pet.82")) pet.name = c.petName;
        if (c.presence.containsKey("pet.83")) pet.type = c.petType;
        if (c.presence.containsKey("pet.84")) pet.rarity = c.petRarity;
        if (c.presence.containsKey("pet.85")) pet.maxAbilityPower = c.petMaxAbilityPower;
        if (c.presence.containsKey("pet." + StatType.SKIN_ID.get())) pet.skin = c.petSkin;
        if (c.petAbilitys != null && c.petAbilitys.length == 9) for (int i = 0; i < 3; i++) {
            if (c.presence.containsKey("pet." + (87 + i))) pet.abilityPoints[i] = c.petAbilitys[i * 3];
            if (c.presence.containsKey("pet." + (90 + i))) pet.abilityLevel[i] = c.petAbilitys[i * 3 + 1];
            if (c.presence.containsKey("pet." + (93 + i))) pet.abilityType[i] = c.petAbilitys[i * 3 + 2];
        }
        if (known != null && known.family != null && Objects.equals(known.instanceId, pet.instanceId)) pet.family = known.family;
        pet.observedAt = c.receivedAt; pet.source = "Character list";
        return pet;
    }
    private static boolean samePetValues(PetRecord a, PetRecord b) {
        return Objects.equals(a.absent, b.absent) && Objects.equals(a.instanceId, b.instanceId) && Objects.equals(a.name, b.name) && Objects.equals(a.type, b.type)
            && Objects.equals(a.rarity, b.rarity) && Objects.equals(a.family, b.family) && Objects.equals(a.skin, b.skin)
            && Objects.equals(a.maxAbilityPower, b.maxAbilityPower) && Arrays.equals(a.abilityType, b.abilityType)
            && Arrays.equals(a.abilityLevel, b.abilityLevel) && Arrays.equals(a.abilityPoints, b.abilityPoints);
    }
    private static boolean validPet(PetRecord p) {
        for (int[] values : new int[][]{p.abilityType, p.abilityLevel, p.abilityPoints})
            if (values == null || values.length != 3 || Arrays.stream(values).anyMatch(n -> n < -1)) return false;
        // "No pet" carries no pet values: a record that says both is contradictory, so it reads as unknown.
        if (Boolean.TRUE.equals(p.absent) && (p.instanceId != null || p.name != null || p.type != null || p.rarity != null || p.family != null)) return false;
        return p.observedAt >= 0;
    }
    /** v5 values that parsed but make no sense are unknown, never a load failure. */
    private static void normalizeV5(CharacterRecord r) {
        if (r.pet != null && Boolean.FALSE.equals(r.pet.absent)) r.pet.absent = null; // only TRUE is saved
        if (r.pet != null && !validPet(r.pet)) r.pet = null;
        if (r.dungeonCompletions != null) for (Map.Entry<String, Integer> entry : r.dungeonCompletions.entrySet())
            if (!CharacterStatistics.DUNGEON_NAMES.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0) { r.dungeonCompletions = null; break; }
        if (r.dungeonCompletions == null || r.dungeonCompletionsObservedAt < 0) r.dungeonCompletionsObservedAt = 0;
        if (r.exp != null && r.exp < 0) r.exp = null;
    }
    private static void normalizeV5(AccountRecord a) {
        Map<Integer, Long> seen = new TreeMap<>();
        if (a.exaltSeenByClass != null) a.exaltSeenByClass.forEach((id, at) -> { if (id != null && id > 0 && at != null && at >= 0) seen.put(id, at); });
        a.exaltSeenByClass = seen;
        if (a.vaultPotions != null && (a.vaultPotions.length != 8 || Arrays.stream(a.vaultPotions).anyMatch(n -> n < 0))) a.vaultPotions = null;
        if (a.vaultPotions == null || a.vaultPotionsObservedAt < 0) a.vaultPotionsObservedAt = 0;
    }
    private static final java.lang.reflect.Type COMPLETIONS = new TypeToken<Map<String, Integer>>() {}.getType(),
        SEEN_BY_CLASS = new TypeToken<Map<Integer, Long>>() {}.getType();
    /** v5 fields are optional: one that does not parse as its type is removed before binding (read as unknown), never a failure. */
    private static void dropMalformedV5Fields(JsonElement tree) {
        if (tree == null || !tree.isJsonObject()) return;
        JsonElement characters = tree.getAsJsonObject().get("characters"), accounts = tree.getAsJsonObject().get("accounts");
        if (characters != null && characters.isJsonArray()) for (JsonElement row : characters.getAsJsonArray()) if (row.isJsonObject()) {
            JsonObject r = row.getAsJsonObject();
            drop(r, "pet", PetRecord.class); drop(r, "dungeonCompletions", COMPLETIONS); drop(r, "dungeonCompletionsObservedAt", long.class);
            drop(r, "exp", Long.class); drop(r, "hasBackpack", Boolean.class);
        }
        if (accounts != null && accounts.isJsonObject()) for (Map.Entry<String, JsonElement> row : accounts.getAsJsonObject().entrySet())
            if (row.getValue().isJsonObject()) {
                JsonObject a = row.getValue().getAsJsonObject();
                drop(a, "exaltSeenByClass", SEEN_BY_CLASS); drop(a, "vaultPotions", int[].class); drop(a, "vaultPotionsObservedAt", long.class);
            }
    }
    private static void drop(JsonObject owner, String field, java.lang.reflect.Type type) {
        JsonElement value = owner.get(field);
        if (value == null || value.isJsonNull()) return;
        try { JSON.fromJson(value, type); } catch (RuntimeException malformed) { owner.remove(field); }
    }
```
13. Insert after the `accountCopy` method, which ends with `        return a == null ? null : copy(a);` and `    }`:
```java
    /** Deep copy of one character's record by journal key ("<account>:<characterId>"), or null when unknown. */
    public synchronized CharacterRecord characterCopy(String key) {
        CharacterRecord r = key == null ? null : find(key);
        return r == null ? null : copy(r);
    }
    /** Null while the journal is readable and its last save (if any) succeeded; otherwise storageStatus(), for a warn banner. */
    public synchronized String storageProblem() {
        return readOnly || storageStatus.startsWith("Save failed") ? storageStatus : null;
    }
```
14. In `copy(CharacterRecord)`, replace `        c.stats = r.stats.clone(); c.equipment = r.equipment.clone();` with:
```java
        c.stats = r.stats.clone(); c.equipment = r.equipment.clone();
        c.pet = copy(r.pet);
        c.dungeonCompletions = r.dungeonCompletions == null ? null : new TreeMap<>(r.dungeonCompletions);
        c.dungeonCompletionsObservedAt = r.dungeonCompletionsObservedAt; c.exp = r.exp; c.hasBackpack = r.hasBackpack;
```
15. In `copy(AccountRecord)`, replace `        c.accountFame = a.accountFame; c.gold = a.gold; c.rankStars = a.rankStars; c.accountStatsObservedAt = a.accountStatsObservedAt;` with:
```java
        c.accountFame = a.accountFame; c.gold = a.gold; c.rankStars = a.rankStars; c.accountStatsObservedAt = a.accountStatsObservedAt;
        c.exaltSeenByClass = new TreeMap<>(a.exaltSeenByClass);
        c.vaultPotions = a.vaultPotions == null ? null : a.vaultPotions.clone(); c.vaultPotionsObservedAt = a.vaultPotionsObservedAt;
```
16. Insert before `    private static boolean sameObservation(CharacterRecord a, CharacterRecord b) {`:
```java
    private static PetRecord copy(PetRecord p) {
        if (p == null) return null;
        PetRecord c = new PetRecord();
        c.absent = p.absent; c.instanceId = p.instanceId; c.name = p.name; c.type = p.type; c.rarity = p.rarity; c.family = p.family; c.skin = p.skin;
        c.maxAbilityPower = p.maxAbilityPower; c.abilityType = p.abilityType.clone(); c.abilityLevel = p.abilityLevel.clone();
        c.abilityPoints = p.abilityPoints.clone(); c.observedAt = p.observedAt; c.source = p.source;
        return c;
    }

```
17. In `save()`, replace `                store.write(path, JSON.toJson(snapshot));` with:
```java
                backupOnce(); // before version 5 first replaces an older file
                store.write(path, JSON.toJson(snapshot));
```
18. Insert before `    private static void writeFile(Path path, String json) throws IOException {`:
```java
    /** The one-time copy of a pre-version-5 journal beside it: journal.json becomes journal.v4.bak. */
    static Path backupPath(Path path) {
        String name = path.getFileName().toString();
        return path.resolveSibling((name.endsWith(".json") ? name.substring(0, name.length() - 5) : name) + ".v4.bak");
    }
    /** Copies the loaded pre-version-5 file once, never over an existing backup (saveLock held). A failed copy fails the save. */
    private void backupOnce() throws IOException {
        if (!backupPending) return;
        Path backup = backupPath(path);
        if (Files.exists(path) && !Files.exists(backup)) Files.copy(path, backup);
        backupPending = false;
    }

```
How loading and saving treat the new fields:
- The drop pass removes type errors: `"exp":"abc"`, a string `pet`, a non-numeric map key. `normalizeV5` then nulls wrong array lengths, unknown dungeon names and negative values.
- v1–v4 documents have none of these fields. Gson keeps the initializers (`exaltSeenByClass` is empty; the ability arrays are `{-1, -1, -1}`).
- Gson omits nulls when saving, so unknown v5 values stay absent from the file; `absent` is saved only as `true`.
- The backup copy runs on the saver thread before the write; if it fails, the save reports "Save failed" and retries later, so the older file is never replaced without its backup.

- [ ] **Step 4: Run the journal tests**

Run: `GRADLE test --tests "tomato.backend.data.CharacterJournal*" --tests "tomato.backend.data.CharacterFreshnessTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing capture-feeding test**

`src/test/java/tomato/backend/data/CharacterPublicationTest.java` — add beside. First replace `import packets.data.StatData;` with:
```java
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.WorldPosData;
```
Then replace `import packets.incoming.CreateSuccessPacket;` with:
```java
import packets.incoming.CreateSuccessPacket;
import packets.incoming.ExaltationUpdatePacket;
import packets.incoming.NewTickPacket;
```
Finally, insert before `    private void identify() throws Exception {`:
```java
    @Test public void rosterCreateVaultExaltsAndPetYardFeedJournalVersionFive() throws Exception {
        identify();
        roster = roster("<Char id='7'><ObjectType>782</ObjectType><Level>20</Level><Exp>30000</Exp><HasBackpack>1</HasBackpack>"
            + "<Seasonal>False</Seasonal><PCStats>" + completions(3) + "</PCStats>"
            + "<Pet name='Pup' instanceId='42' maxAbilityPower='70' rarity='2' skin='100' type='3'><Abilities>"
            + "<Ability type='407' power='50' points='1000'/><Ability type='408' power='40' points='800'/>"
            + "<Ability type='406' power='30' points='600'/></Abilities></Pet></Char>");
        acceptRoster();
        String account = CharacterJournal.accountKey("fixture-account"), key = account + ":7";
        CharacterJournal.CharacterRecord listed = journal.characterCopy(key);
        assertEquals(Long.valueOf(30_000), listed.exp); assertEquals(Boolean.TRUE, listed.hasBackpack);
        assertEquals(Map.of("Pirate Cave", 3), listed.dungeonCompletions);
        assertEquals(Long.valueOf(42), listed.pet.instanceId); assertEquals("Pup", listed.pet.name); assertNull(listed.pet.family);
        assertArrayEquals(new int[]{407, 408, 406}, listed.pet.abilityType); assertEquals("Character list", listed.pet.source);

        data.setUserId(1, 7, completions(5));   // CREATE carries the character's current PCStats
        data.player = new Entity(data, 1, 0); data.player.objectType = 782;
        StatData identity = new StatData(); identity.stringStatValue = "fixture-account"; data.player.stat.set(StatType.ACCOUNT_ID_STAT, identity);
        data.rememberCharacter();
        assertEquals("Live completions replace the list's", Map.of("Pirate Cave", 5), journal.characterCopy(key).dungeonCompletions);

        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        seasonal(false);
        capture.packetCapture(vault(true, new int[]{2793, 9064}, new int[]{5466}, new int[]{2794, 9070}));
        assertArrayEquals("Chest, gifts and storage in normal potions", new int[]{3, 1, 2, 1, 0, 0, 0, 0}, journal.accountCopy(account).vaultPotions);
        seasonal(true);
        capture.packetCapture(vault(true, new int[]{2613}, new int[0], new int[0]));
        data.player.stat.set(StatType.SEASONAL, null);
        capture.packetCapture(vault(true, new int[]{2613}, new int[0], new int[0]));   // season unknown: not attributed, no failure
        assertArrayEquals("Only the regular vault is recorded", new int[]{3, 1, 2, 1, 0, 0, 0, 0}, journal.accountCopy(account).vaultPotions);

        ExaltationUpdatePacket exalt = new ExaltationUpdatePacket(); exalt.objType = 782; exalt.healthProgress = 30;
        data.exaltUpdate(exalt);
        assertTrue(journal.accountCopy(account).exaltSeenByClass.containsKey(782));

        data.petYardCheck("Pet Yard");
        NewTickPacket tick = new NewTickPacket();
        tick.status = new ObjectStatusData[]{yardStatus(500, yardStat(StatType.PET_INSTANCE_ID_STAT, 42), yardStat(StatType.PET_FAMILY_STAT, 4),
            yardStat(StatType.PET_RARITY_STAT, 3))};
        data.updateNewTick(tick);
        CharacterJournal.PetRecord pet = journal.characterCopy(key).pet;
        assertEquals(Integer.valueOf(4), pet.family); assertEquals(Integer.valueOf(3), pet.rarity);
        assertEquals("Pup", pet.name); assertEquals("Pet Yard capture", pet.source);

        roster = roster("<Char id='7'><ObjectType>782</ObjectType><Level>20</Level><Pet/></Char>");
        acceptRoster();
        assertEquals("An explicitly empty Pet element is saved as no pet", Boolean.TRUE, journal.characterCopy(key).pet.absent);
    }

    private static StatData yardStat(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; return stat;
    }
    private static ObjectStatusData yardStatus(int objectId, StatData... stats) {
        ObjectStatusData status = new ObjectStatusData(); status.objectId = objectId; status.pos = new WorldPosData(); status.stats = stats; return status;
    }

```

- [ ] **Step 6: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.backend.data.CharacterPublicationTest"`
Expected: FAIL in the new test: `listed.exp` is null because the parser does not supply `exp` yet. The other tests pass.

- [ ] **Step 7: Feed the journal from capture (`TomatoData`)**

All edits are in `src/main/java/tomato/backend/data/TomatoData.java`.

1. Replace `    private RealmCharacterStats currentCharacterStats;` with:
```java
    private RealmCharacterStats currentCharacterStats;
    // A complete PCStats decode from CREATE, kept until rememberCharacter has verified the account it belongs to.
    private int[] journalCompletions;
    private int journalCompletionsCharacter = -1;
    private long journalCompletionsAt;
```
2. Replace `        packets.packetcapture.logger.DiscoveryLog.INSTANCE.completionStats(charId, currentCharacterStats.completionCounts());` with that line followed by:
```java
        journalCompletions = currentCharacterStats.completionCounts();   // null unless the payload decoded completely
        journalCompletionsCharacter = charId; journalCompletionsAt = System.currentTimeMillis();
```
3. Replace:
```java
        if (account == null) return;
        journalAccount = account;
```
with:
```java
        if (account == null) return;
        journalAccount = account;
        if (journalCompletions != null && journalCompletionsCharacter == charId) {
            characterJournal().dungeonCompletions(account, charId, journalCompletions, journalCompletionsAt);
            journalCompletions = null;
        }
```
4. Replace `        progression.pet(progression.scope(), value.id, new Stat(delta), value.observedAt(), "Pet Yard capture", fields);` with that line followed by:
```java
        CharacterJournal.PetRecord seen = yardPetRecord(value);   // the entity's accumulated pet fields, not only this delta
        if (seen != null && journalAccount != null) characterJournal().yardPet(journalAccount, seen);
```
5. Replace the whole `vaultPacketUpdate` method:
```java
    public void vaultPacketUpdate(VaultContentPacket p) {
        if (player != null) {
            if (player.stat.get(StatType.SEASONAL).statValue == 1) {
                vaultDataRecievedSeasonal = true;
                seasonalVault.vaultPacketUpdate(p);
            } else {
                vaultDataRecievedRegular = true;
                regularVault.vaultPacketUpdate(p);
            }
        }
    }
```
with:
```java
    public void vaultPacketUpdate(VaultContentPacket p) {
        if (player == null) return;
        // Without the SEASONAL stat the contents cannot be attributed to either vault (reading it used to throw).
        StatData season = player.stat.get(StatType.SEASONAL);
        if (season == null) return;
        if (season.statValue == 1) {
            vaultDataRecievedSeasonal = true;
            seasonalVault.vaultPacketUpdate(p);
        } else {
            vaultDataRecievedRegular = true;
            regularVault.vaultPacketUpdate(p);
            if (p.lastVaultPacket) {   // the regular vault's totals are complete now
                int[] potions = new int[8];
                regularVault.getVaultChestPots(potions); regularVault.getPotStoragePots(potions); regularVault.getGiftChestPots(potions);
                characterJournal().vaultPotions(journalAccount, potions, System.currentTimeMillis());
            }
        }
    }
```
6. In `parseCharacter`:
   - Replace `case "HasBackpack": c.backpack="1".equals(value); break;` with `case "HasBackpack": c.backpack="1".equals(value); c.supplied("backpack"); break;`.
   - Replace `case "Exp": c.exp=Long.parseLong(value); break;` with `case "Exp": c.exp=Long.parseLong(value); c.supplied("exp"); break;`.
   - Replace `                case "Pet":` with:
```java
                case "Pet":
                    // An explicitly empty <Pet/> is a known absence (the journal saves "no pet"); omitted or partial metadata stays unknown.
                    if (!field.hasAttributes() && children(field).isEmpty() && value.isEmpty()) c.supplied("pet.none");
```
   - Replace `                    catch (RuntimeException e) { c.charStats=null; }` with that line followed by:
```java
                    // Presence marks a complete decode; the journal decodes the list's PCStats string again (capture may overlay charStats).
                    if (c.charStats != null && c.charStats.completionCounts() != null) c.supplied("dungeons");
```
7. Insert before `    private static int attributeInt(Element node, String name) {`:
```java
    /** The journal's view of a Pet Yard pet entity (every pet field captured so far), or null without an instance id. */
    private static CharacterJournal.PetRecord yardPetRecord(Entity value) {
        StatData id = value.stat.get(StatType.PET_INSTANCE_ID_STAT), name = value.stat.get(StatType.PET_NAME_STAT);
        if (id == null) return null;
        CharacterJournal.PetRecord pet = new CharacterJournal.PetRecord();
        pet.instanceId = (long) id.statValue; pet.name = name == null ? null : name.stringStatValue;
        pet.type = statInt(value, StatType.PET_TYPE_STAT); pet.rarity = statInt(value, StatType.PET_RARITY_STAT);
        pet.family = statInt(value, StatType.PET_FAMILY_STAT); pet.maxAbilityPower = statInt(value, StatType.PET_MAX_ABILITY_POWER_STAT);
        pet.skin = statInt(value, StatType.SKIN_ID);
        StatType[][] abilities = {{StatType.PET_FIRST_ABILITY_POINT_STAT, StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_FIRST_ABILITY_TYPE_STAT},
            {StatType.PET_SECOND_ABILITY_POINT_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT},
            {StatType.PET_THIRD_ABILITY_POINT_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT}};
        for (int i = 0; i < 3; i++) {
            Integer points = statInt(value, abilities[i][0]), level = statInt(value, abilities[i][1]), type = statInt(value, abilities[i][2]);
            pet.abilityPoints[i] = points == null || points < 0 ? -1 : points;
            pet.abilityLevel[i] = level == null || level < 0 ? -1 : level;
            pet.abilityType[i] = type == null || type < 0 ? -1 : type;
        }
        pet.observedAt = Math.max(0, value.observedAt()); pet.source = "Pet Yard capture";
        return pet;
    }
    private static Integer statInt(Entity entity, StatType type) { StatData s = entity.stat.get(type); return s == null ? null : s.statValue; }
```
CREATE does not carry the account, so live completions wait for the first verified UPDATE of the same character. A decode that was not complete never reaches the journal.

- [ ] **Step 8: Run the backend data tests**

Run: `GRADLE test --tests "tomato.backend.data.*" --tests "tomato.gui.character.*" --tests "packets.packetcapture.logger.RunEvidenceTest"`
Expected: PASS. `AccountMetadataTest` passes unchanged: only presence keys were added, and they also reach `CharacterRecord.fields` as provenance.

- [ ] **Step 9: Commit**
```powershell
git add src/main/java/tomato/backend/data/CharacterJournal.java src/main/java/tomato/backend/data/TomatoData.java src/test/java/tomato/backend/data/CharacterJournalV5Test.java src/test/java/tomato/backend/data/CharacterJournalV4Test.java src/test/java/tomato/backend/data/CharacterFreshnessTest.java src/test/java/tomato/backend/data/CharacterPublicationTest.java
git commit -m "Save journal version 5 with pet, completions, vault potions and exalt times" -m "The journal saves version 5 and still loads 1-4, keeping one journal.v4.bak copy of an older file before the first version 5 save; malformed optional fields read as unknown instead of making the journal read-only. The character list (including an explicit no-pet), CREATE's PCStats, exaltation updates, the regular vault and Pet Yard pets feed the new fields, and a vault packet without the SEASONAL stat no longer throws. storageProblem() reports an unreadable journal or a failed save." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Kit promotions — `Banner`, `KitLayouts`, `ItemTiers`, `KitText`

The sheet (Tasks 5–7) and the gallery (Task 9) need Home's banner, row layouts, tier labels and role labels, so they move into `tomato.gui.kit` and Home delegates to them:
- `HomeViews.Reason` stays: P2 tests look it up by class and name. It becomes a named panel around a `Banner`.
- `HomeViews.Text` goes. No test names it, and its four callers switch to `KitText`.

P2 tests pass unchanged.

**Files:**
- Create: `Banner.java`, `KitLayouts.java`, `ItemTiers.java` and `KitText.java` in `src/main/java/tomato/gui/kit/`.
- Modify: `HomeViews.java`, `HeroCard.java`, `NowCard.java`, `QuestsCard.java` and `RecentRunsCard.java` in `src/main/java/tomato/gui/glance/home/`.
- Create tests: `BannerTest.java`, `KitLayoutsTest.java` and `ItemTiersTest.java` in `src/test/java/tomato/gui/kit/`.

**Interfaces:**
- Consumes:
  - `ContentStyle.wrappingText(String)` and `ContentStyle.font(JComponent, Font)`
  - `Tokens.color/tone/tint` and `Type.caption/body/emphasis()`
  - `RosterDefinitions.current().item(int)`, `RosterDefinitions.parse(Reader, Reader)` and `Item.labels/tier`
- Produces (public, in `tomato.gui.kit`):
  - `final class Banner extends JPanel`:
    - `Banner(String name)`; its text area is named `<name>-text`.
    - `setText(String)` (null reads as "") and `text()`.
    - `setTone(Tokens.Tone)` (null = NEUTRAL) and `tone()`.
    - `warns()`: true for WARN.
    - NEUTRAL is plain muted text. Other tones draw a tinted row with a left stripe.
    - The accessible name is the text.
  - `final class KitLayouts`:
    - `stack(int gap, JComponent... rows)`
    - `spread(int gap, JComponent... parts)`: `parts[0]` is the lead; no parts is an error.
    - `spreadWhenWide(int minimumRootWidth, int gap, JComponent... parts)`
    - `rootWidth(Component)`
  - `final class ItemTiers`: `label(int itemId)` ("" when the id is ≤ 0 or unknown) and `label(RosterDefinitions.Item)`.
  - `final class KitText extends JLabel`:
    - `KitText(String, Font, Tokens.Role)`
    - static `caption/body/emphasis(String)`
    - `role()` and `role(Tokens.Role)`
    - HTML is off, and the role color is re-applied in `updateUI`.
- Home after this task:
  - `HomeViews.caption/body/emphasis` return `KitText`.
  - `HomeViews.spread(boolean wideOnly, int gap, JComponent lead, JComponent... trailing)` takes `JComponent`, which every caller already passes.
  - `HomeViews.tier` delegates to `ItemTiers`.
  - `HomeViews.Reason(name)` wraps a `Banner` named `<name>-banner`.

- [ ] **Step 1: Write the failing kit tests**

`src/test/java/tomato/gui/kit/BannerTest.java`:
```java
package tomato.gui.kit;

import java.awt.Color;
import java.awt.Insets;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Banner: tones, wrapping text and accessible name. KitText: role colors that follow the theme. */
public class BannerTest {
    @Test public void neutralIsPlainMutedTextAndOtherTonesAreTintedRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Banner banner = new Banner("sample-banner");
            JTextArea text = (JTextArea) banner.getComponent(0);
            assertEquals("sample-banner-text", text.getName()); assertTrue("Text wraps instead of clipping", text.getLineWrap());
            assertEquals(Tokens.Tone.NEUTRAL, banner.tone()); assertFalse(banner.warns()); assertNull("Plain text has no inset", banner.getBorder());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), text.getForeground());
            banner.setText("Loading…");
            assertEquals("Loading…", banner.text()); assertEquals("Loading…", banner.getAccessibleContext().getAccessibleName());
            banner.setTone(Tokens.Tone.WARN);
            Insets inset = banner.getInsets();
            assertTrue(banner.warns()); assertTrue("The stripe gets room at the left", inset.left > inset.right);
            assertEquals(Tokens.color(Tokens.Role.TEXT), text.getForeground());
            banner.setTone(Tokens.Tone.INFO);
            assertFalse("Only WARN warns", banner.warns()); assertNotNull(banner.getBorder());
            banner.setTone(null); banner.setText(null);
            assertEquals(Tokens.Tone.NEUTRAL, banner.tone()); assertNull(banner.getBorder()); assertEquals("", banner.text());
        });
    }

    @Test public void colorsFollowTheThemeAfterUpdateUi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Banner banner = new Banner("theme-banner"); banner.setTone(Tokens.Tone.WARN);
            JTextArea text = (JTextArea) banner.getComponent(0);
            KitText muted = KitText.caption("Muted"), accent = new KitText("Accent", Type.body(), Tokens.Role.ACCENT_TEXT);
            for (JComponent part : new JComponent[] {text, muted, accent}) part.setForeground(Color.MAGENTA);
            banner.updateUI(); muted.updateUI(); accent.updateUI();
            assertEquals(Tokens.color(Tokens.Role.TEXT), text.getForeground());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), muted.getForeground());
            assertEquals(Tokens.color(Tokens.Role.ACCENT_TEXT), accent.getForeground());
            accent.role(Tokens.Role.TEXT);
            assertEquals(Tokens.Role.TEXT, accent.role()); assertEquals(Tokens.color(Tokens.Role.TEXT), accent.getForeground());
            assertEquals(Boolean.TRUE, muted.getClientProperty("html.disable"));
            assertEquals(Tokens.Role.TEXT, KitText.body("Body").role()); assertEquals(Tokens.Role.TEXT, KitText.emphasis("Title").role());
        });
    }
}
```

`src/test/java/tomato/gui/kit/KitLayoutsTest.java`:
```java
package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** KitLayouts: full-width stacks that skip hidden rows; spread rows that keep one line when they fit and wrap below the lead otherwise. */
public class KitLayoutsTest {
    private static JComponent box(int width, int height) { JPanel box = new JPanel(); box.setPreferredSize(new Dimension(width, height)); return box; }
    /** Sizes a panel to its preferred height at this width, twice (spread rows measure against their width). */
    private static void lay(JComponent panel, int width) { for (int pass = 0; pass < 2; pass++) { panel.setSize(width, panel.getPreferredSize().height); panel.doLayout(); } }

    @Test public void stackFillsTheWidthInOrderAndHiddenRowsTakeNoSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent first = box(50, 20), hidden = box(50, 30), last = box(80, 10);
            hidden.setVisible(false);
            JPanel stack = KitLayouts.stack(6, first, hidden, last);
            assertFalse(stack.isOpaque());
            assertEquals("20 + gap + 10: the hidden row and its gap are skipped", 36, stack.getPreferredSize().height);
            lay(stack, 300);
            assertEquals(new Rectangle(0, 0, 300, 20), first.getBounds()); assertEquals(new Rectangle(0, 26, 300, 10), last.getBounds());
        });
    }

    @Test public void spreadKeepsOneRowWhenItFitsAndWrapsBelowTheLeadOtherwise() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent lead = box(100, 20), a = box(60, 30), b = box(60, 10);
            JPanel row = KitLayouts.spread(8, lead, a, b);
            lay(row, 400);
            assertEquals("One row, as tall as its tallest part", 30, row.getPreferredSize().height);
            assertEquals("The lead stretches to the trailing parts", new Rectangle(0, 5, 264, 20), lead.getBounds());
            assertEquals(new Rectangle(272, 0, 60, 30), a.getBounds()); assertEquals(new Rectangle(340, 10, 60, 10), b.getBounds());
            lay(row, 200);
            assertEquals("The lead's line, then the trailing parts", 58, row.getPreferredSize().height);
            assertEquals(new Rectangle(0, 0, 200, 20), lead.getBounds());
            assertEquals(new Rectangle(0, 28, 60, 30), a.getBounds()); assertEquals(new Rectangle(68, 38, 60, 10), b.getBounds());
            a.setVisible(false); b.setVisible(false);
            lay(row, 200);
            assertEquals("Hidden parts take no space", 20, row.getPreferredSize().height);
        });
    }

    @Test public void spreadWhenWideWrapsWhileTheWindowIsNarrow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel narrow = KitLayouts.spreadWhenWide(1000, 8, box(100, 20), box(60, 20));
            lay(narrow, 400);
            assertEquals("Below 1000 px the row wraps although it would fit", 48, narrow.getPreferredSize().height);
            JPanel wide = KitLayouts.spreadWhenWide(300, 8, box(100, 20), box(60, 20));
            lay(wide, 400);
            assertEquals(20, wide.getPreferredSize().height);
            assertEquals("Outside a window, the component's own width", 400, KitLayouts.rootWidth(wide));
            try { KitLayouts.spread(8); fail("A spread row needs a lead"); } catch (IllegalArgumentException expected) {}
        });
    }
}
```

`src/test/java/tomato/gui/kit/ItemTiersTest.java`:
```java
package tomato.gui.kit;

import java.io.StringReader;
import org.junit.Test;
import tomato.backend.data.RosterDefinitions;
import static org.junit.Assert.*;

/** ItemTiers: UT and ST first, then an explicit T label, then the numeric tier; "" while unknown. */
public class ItemTiersTest {
    @Test public void labelsComeFromTheDefinitionOrStayEmpty() throws Exception {
        RosterDefinitions defs = RosterDefinitions.parse(null, new StringReader("<Objects>"
            + "<Object type='10'><Labels>UT,WEAPON</Labels><Tier>12</Tier></Object><Object type='11'><Labels>ST,ARMOR</Labels></Object>"
            + "<Object type='12'><Labels>T13,ARMOR</Labels><Tier>12</Tier></Object><Object type='13'><Tier>7</Tier></Object>"
            + "<Object type='14'><Labels>CONSUMABLE</Labels></Object></Objects>"));
        assertEquals("UT", ItemTiers.label(defs.item(10))); assertEquals("ST", ItemTiers.label(defs.item(11)));
        assertEquals("An explicit tier label wins over the numeric tier", "T13", ItemTiers.label(defs.item(12)));
        assertEquals("T7", ItemTiers.label(defs.item(13))); assertEquals("No tier at all", "", ItemTiers.label(defs.item(14)));
        assertEquals("Unknown definition", "", ItemTiers.label((RosterDefinitions.Item) null));
        assertEquals("Empty and unknown slots have no tier", "", ItemTiers.label(0)); assertEquals("", ItemTiers.label(-1));
    }
}
```
Expected spread geometry at 400 px:
- The trailing parts are 60 + 8 + 60 = 128 px wide.
- The lead is 400 − 128 − 8 = 264 px wide, centered in the 30 px row.

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.kit.BannerTest" --tests "tomato.gui.kit.KitLayoutsTest" --tests "tomato.gui.kit.ItemTiersTest"`
Expected: FAIL. The compiler reports `cannot find symbol` for `Banner`, `KitText`, `KitLayouts` and `ItemTiers`.

- [ ] **Step 3: Create the four kit classes**

`src/main/java/tomato/gui/kit/Banner.java`:
```java
package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;

/**
 * One line of status text that wraps instead of clipping. NEUTRAL is plain muted text (loading); any other tone draws a
 * tinted row with a stripe in that tone and reads in the body color: WARN for errors, stale or incomplete data (spec §7:
 * errors render as a warn banner inside the card), INFO for notes.
 */
public final class Banner extends JPanel {
    private final JTextArea text;
    private Tokens.Tone tone = Tokens.Tone.NEUTRAL;

    public Banner(String name) {
        super(new BorderLayout());
        setOpaque(false);
        setName(name);
        text = ContentStyle.wrappingText("");
        text.setName(name + "-text");
        add(text);
        refreshColors();
    }

    public void setText(String value) {
        String next = value == null ? "" : value;
        if (!next.equals(text.getText())) text.setText(next);
        getAccessibleContext().setAccessibleName(next);
    }

    public String text() { return text.getText(); }

    public Tokens.Tone tone() { return tone; }

    public void setTone(Tokens.Tone value) {
        Tokens.Tone next = value == null ? Tokens.Tone.NEUTRAL : value;
        if (tone == next) return;
        tone = next;
        setBorder(next == Tokens.Tone.NEUTRAL ? null : new EmptyBorder(Tokens.XS, Tokens.S + 3, Tokens.XS, Tokens.S));
        refreshColors();
        revalidate();
        repaint();
    }

    /** True for a warn banner. */
    public boolean warns() { return tone == Tokens.Tone.WARN; }

    @Override public void updateUI() { super.updateUI(); if (text != null) refreshColors(); }   // text is null while JPanel's constructor runs

    private void refreshColors() { text.setForeground(Tokens.color(tone == Tokens.Tone.NEUTRAL ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT)); }

    @Override protected void paintComponent(Graphics graphics) {
        if (tone == Tokens.Tone.NEUTRAL) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color ink = Tokens.tone(tone);
        g.setColor(Tokens.tint(ink));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(ink); // the stripe keeps the tone visible without relying on the tint alone
        g.fillRect(0, 2, 3, Math.max(0, getHeight() - 4));
        g.dispose();
    }
}
```

`src/main/java/tomato/gui/kit/KitText.java`:
```java
package tomato.gui.kit;

import java.awt.Font;
import java.util.Objects;
import javax.swing.JLabel;
import tomato.gui.modern.ContentStyle;

/** A label in one kit text role and font. HTML is off, because names and maps come from capture. */
public final class KitText extends JLabel {
    private Tokens.Role role;

    public KitText(String text, Font font, Tokens.Role role) {
        super(text);
        this.role = Objects.requireNonNull(role, "role");
        putClientProperty("html.disable", Boolean.TRUE);
        ContentStyle.font(this, font);
        setForeground(Tokens.color(role));
    }

    public static KitText caption(String text) { return new KitText(text, Type.caption(), Tokens.Role.TEXT_MUTED); }
    public static KitText body(String text) { return new KitText(text, Type.body(), Tokens.Role.TEXT); }
    public static KitText emphasis(String text) { return new KitText(text, Type.emphasis(), Tokens.Role.TEXT); }

    public Tokens.Role role() { return role; }

    /** Switches the text role; the color follows the theme from then on. */
    public void role(Tokens.Role value) {
        if (value == null || role == value) return;
        role = value;
        setForeground(Tokens.color(value));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (role != null) setForeground(Tokens.color(role)); // null while JLabel's constructor runs
    }
}
```

`src/main/java/tomato/gui/kit/ItemTiers.java`:
```java
package tomato.gui.kit;

import java.util.regex.Pattern;
import tomato.backend.data.RosterDefinitions;

/** Short tier labels for items: "UT", "ST" or "T12", from the loaded item definitions. */
public final class ItemTiers {
    private static final Pattern TIER_LABEL = Pattern.compile("T\\d{1,2}");

    private ItemTiers() {}

    /** The label for an item id; "" for an empty or unknown slot, or while the definitions load. Constant time on the EDT. */
    public static String label(int itemId) { return itemId > 0 ? label(RosterDefinitions.current().item(itemId)) : ""; }

    /** UT, then ST, then an explicit "T<n>" label, then the numeric tier; "" when the definition has none. */
    public static String label(RosterDefinitions.Item item) {
        if (item == null) return "";
        if (item.labels != null) {
            if (item.labels.contains("UT")) return "UT";
            if (item.labels.contains("ST")) return "ST";
            for (String label : item.labels) if (TIER_LABEL.matcher(label).matches()) return label;
        }
        return item.tier == null ? "" : "T" + item.tier;
    }
}
```

`src/main/java/tomato/gui/kit/KitLayouts.java`:
```java
package tomato.gui.kit;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** Row and column layouts shared by glance cards and pages. EDT only. */
public final class KitLayouts {
    private KitLayouts() {}

    /** Rows top to bottom at full width, {@code gap} apart; hidden rows take no space, and wrapping rows grow with their width. */
    public static JPanel stack(int gap, JComponent... rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : gap, 0, 0, 0);
            panel.add(rows[i], c);
        }
        // Rows stay at the top when a grid stretches this panel to its row partner's height.
        c.gridy = rows.length;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(Box.createVerticalGlue(), c);
        return panel;
    }

    /**
     * One row: the first part stretches at the left and the rest sit at the right edge, all vertically centered, when
     * everything fits. Otherwise the first part takes its own line and the rest flow below it from the left, wrapping at the
     * width. Hidden parts take no space. Rows re-measure when their width changes.
     */
    public static JPanel spread(int gap, JComponent... parts) { return spreadWhenWide(0, gap, parts); }

    /** As {@link #spread}, with the one-row form only while {@link #rootWidth} is at least {@code minimumRootWidth}. */
    public static JPanel spreadWhenWide(int minimumRootWidth, int gap, JComponent... parts) {
        if (parts.length == 0) throw new IllegalArgumentException("A spread row needs a leading part");
        JPanel panel = new JPanel() {
            @Override public void setBounds(int x, int y, int width, int height) {
                boolean changed = width != getWidth();
                super.setBounds(x, y, width, height);
                if (changed) SwingUtilities.invokeLater(this::revalidate); // the row count depends on this width
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new Spread(minimumRootWidth, gap));
        for (JComponent part : parts) panel.add(part);
        return panel;
    }

    /** The width of the component's root pane, or outside a window (or before layout) the component's own width. */
    public static int rootWidth(Component component) {
        JRootPane root = SwingUtilities.getRootPane(component);
        return root != null && root.getWidth() > 0 ? root.getWidth() : component.getWidth();
    }

    private static final class Spread implements LayoutManager {
        private final int minimumRootWidth, gap;

        Spread(int minimumRootWidth, int gap) { this.minimumRootWidth = minimumRootWidth; this.gap = gap; }

        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension preferredLayoutSize(Container target) { return size(target); }
        /** Height as preferred and no width floor, so enclosing GridBag stacks never switch to minimum sizes. */
        @Override public Dimension minimumLayoutSize(Container target) { return new Dimension(0, size(target).height); }
        @Override public void layoutContainer(Container target) { place(target, true); }

        private Dimension size(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int available = available(target), height = place(target, false);
                return new Dimension(available > 0 ? available : natural(target) + insets.left + insets.right, height);
            }
        }

        /** The one-row width of the visible parts. */
        private int natural(Container target) {
            int width = 0;
            for (Component part : target.getComponents()) if (part.isVisible()) width += (width == 0 ? 0 : gap) + part.getPreferredSize().width;
            return width;
        }

        private int place(Container target, boolean apply) {
            Insets insets = target.getInsets();
            int width = Math.max(1, available(target) - insets.left - insets.right), left = insets.left, y = insets.top;
            Component[] parts = target.getComponents();
            Component lead = parts[0];
            List<Component> rest = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) if (parts[i].isVisible()) rest.add(parts[i]);
            int restWidth = 0, restHeight = 0;
            for (Component part : rest) {
                Dimension size = part.getPreferredSize();
                restWidth += (restWidth == 0 ? 0 : gap) + size.width;
                restHeight = Math.max(restHeight, size.height);
            }
            Dimension leadSize = lead.getPreferredSize();
            if (lead.isVisible() && !rest.isEmpty() && (minimumRootWidth <= 0 || rootWidth(target) >= minimumRootWidth) && leadSize.width + gap + restWidth <= width) {
                int height = Math.max(leadSize.height, restHeight);
                if (apply) {
                    lead.setBounds(left, y + (height - leadSize.height) / 2, width - restWidth - gap, leadSize.height);
                    int x = left + width - restWidth;
                    for (Component part : rest) {
                        Dimension size = part.getPreferredSize();
                        part.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                        x += size.width + gap;
                    }
                }
                return y + height + insets.bottom;
            }
            if (lead.isVisible()) {
                if (apply) lead.setBounds(left, y, width, leadSize.height);
                y += leadSize.height + (rest.isEmpty() ? 0 : gap);
            }
            List<Component> row = new ArrayList<>();
            int rowWidth = 0;
            for (Component part : rest) {
                int partWidth = part.getPreferredSize().width;
                if (!row.isEmpty() && rowWidth + gap + partWidth > width) { y = row(row, left, y, apply) + gap; row.clear(); rowWidth = 0; }
                rowWidth += (row.isEmpty() ? 0 : gap) + partWidth;
                row.add(part);
            }
            if (!row.isEmpty()) y = row(row, left, y, apply);
            return y + insets.bottom;
        }

        /** One wrapped row of trailing parts from the left, vertically centered; returns its bottom. */
        private int row(List<Component> row, int left, int y, boolean apply) {
            int height = 0;
            for (Component part : row) height = Math.max(height, part.getPreferredSize().height);
            int x = left;
            for (Component part : row) {
                Dimension size = part.getPreferredSize();
                if (apply) part.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                x += size.width + gap;
            }
            return y + height;
        }

        private static int available(Container target) {
            if (target.getWidth() > 0) return target.getWidth();
            Container parent = target.getParent();
            if (parent == null) return 0;
            Insets insets = parent.getInsets();
            return parent.getWidth() - insets.left - insets.right;
        }
    }
}
```
`Spread` is Home's layout manager (`HomeViews.java` lines 184–275) with `wideOnly` replaced by `minimumRootWidth`. Home's copy is removed in Step 5.

- [ ] **Step 4: Run the kit tests**

Run: `GRADLE test --tests "tomato.gui.kit.*"`
Expected: PASS.

- [ ] **Step 5: Home delegates to the kit**

`src/main/java/tomato/gui/glance/home/HomeViews.java` needs three replacements.

(a) Replace everything from `package tomato.gui.glance.home;` down to, but not including, `    static <T extends Component> T named(T component, String name) {` (lines 1–112). That span holds the imports, `LOADING`, `WIDE`, `TIER_LABEL`, `wide`, `Text`, `caption/body/emphasis` and `Reason`. The replacement:
```java
package tomato.gui.glance.home;

import java.awt.*;
import javax.swing.*;
import tomato.gui.kit.Banner;
import tomato.gui.kit.ItemTiers;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.KitText;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterStatistics;

/** Small shared pieces of the Home cards; the general ones live in the kit (Banner, KitLayouts, KitText, ItemTiers). EDT only. */
final class HomeViews {
    /** Loading is static text: the spec allows no animated loaders (§5.8). */
    static final String LOADING = "Loading…";
    /** Home's desktop layout starts at this root-pane width, where the shell leaves compact mode (spec §6.1). */
    static final int WIDE = 1000;

    private HomeViews() {}

    /** True when the window's root pane (or, outside a window, the component) is at least WIDE pixels wide. */
    static boolean wide(Component component) { return KitLayouts.rootWidth(component) >= WIDE; }

    static KitText caption(String text) { return KitText.caption(text); }
    static KitText body(String text) { return KitText.body(text); }
    static KitText emphasis(String text) { return KitText.emphasis(text); }

    /**
     * The muted loading text, or (warn) an error or stale-read reason, as a kit {@link Banner} (spec §7: errors render as a
     * warn banner inside the card). Home's tests find it by class and name, so it stays a named panel around the banner.
     */
    static final class Reason extends JPanel {
        private final Banner banner;

        Reason(String name) {
            super(new BorderLayout());
            setOpaque(false);
            setName(name);
            banner = new Banner(name + "-banner");
            add(banner);
        }

        void setText(String value) { setText(value, false); }

        void setText(String value, boolean warning) {
            banner.setText(value);
            banner.setTone(warning ? Tokens.Tone.WARN : Tokens.Tone.NEUTRAL);
            getAccessibleContext().setAccessibleName(value);
        }

        String text() { return banner.text(); }

        boolean warns() { return banner.warns(); }
    }

```
(b) Replace everything from `    /** Rows top to bottom at full width; hidden rows take no space, and wrapping rows grow with their width. */` down to, but not including, `    /** Portal sprite for a map:`. That span holds `stack`, `spread` and the private `Spread`. The replacement:
```java
    /** Rows top to bottom at full width ({@link KitLayouts#stack}). */
    static JPanel stack(int gap, JComponent... rows) { return KitLayouts.stack(gap, rows); }

    /** One row that wraps below {@code lead} when it does not fit; with {@code wideOnly}, one row only while the page is {@link #wide}. */
    static JPanel spread(boolean wideOnly, int gap, JComponent lead, JComponent... trailing) {
        JComponent[] parts = new JComponent[trailing.length + 1];
        parts[0] = lead;
        System.arraycopy(trailing, 0, parts, 1, trailing.length);
        return KitLayouts.spreadWhenWide(wideOnly ? WIDE : 0, gap, parts);
    }

```
(c) Replace the `tier` method with its doc comment, from `    /** "UT", "ST" or "T12" from the loaded item definitions;` through the method's closing `    }` (11 lines). The replacement:
```java
    /** "UT", "ST" or "T12" from the loaded item definitions; empty while unknown ({@link ItemTiers#label(int)}). */
    static String tier(int itemId) { return ItemTiers.label(itemId); }
```

Then update the callers of `HomeViews.Text`:
- In `HeroCard.java`, `NowCard.java`, `QuestsCard.java` and `RecentRunsCard.java`, replace every `HomeViews.Text` with `KitText` (Edit, `replace_all`). HeroCard's `new HomeViews.Text("", Type.caption(), Tokens.Role.ACCENT_TEXT)` becomes the public `KitText` constructor.
- Add `import tomato.gui.kit.KitText;` to each file:
  - HeroCard: after `import tomato.gui.kit.KitButton;`
  - NowCard and RecentRunsCard: after `import tomato.gui.kit.KitFormat;`
  - QuestsCard: after `import tomato.gui.kit.ItemSlot;`

Check that nothing else used the removed pieces: `git grep -n "HomeViews.Text\|TIER_LABEL\|class Spread" -- src/main/java/tomato/gui/glance` prints nothing.

- [ ] **Step 6: Run the Home and kit tests unchanged**

Run: `GRADLE test --tests "tomato.gui.kit.*" --tests "tomato.gui.glance.home.*" --tests "ui.HomeEvidenceTest" --tests "tomato.gui.chat.ShellHookIntegrationTest"`
Expected: PASS, with no P2 test edited in this task. `HomeViews.Reason` lookups, `warns()`, `text()`, `statusText()` and `statusWarns()` behave as before, and the hero's top row still wraps below 1000 px.

- [ ] **Step 7: Commit**
```powershell
git add src/main/java/tomato/gui/kit/Banner.java src/main/java/tomato/gui/kit/KitLayouts.java src/main/java/tomato/gui/kit/ItemTiers.java src/main/java/tomato/gui/kit/KitText.java src/main/java/tomato/gui/glance/home/HomeViews.java src/main/java/tomato/gui/glance/home/HeroCard.java src/main/java/tomato/gui/glance/home/NowCard.java src/main/java/tomato/gui/glance/home/QuestsCard.java src/main/java/tomato/gui/glance/home/RecentRunsCard.java src/test/java/tomato/gui/kit/BannerTest.java src/test/java/tomato/gui/kit/KitLayoutsTest.java src/test/java/tomato/gui/kit/ItemTiersTest.java
git commit -m "Promote Home's banner, layouts, tier labels and text roles to the kit" -m "Banner, KitLayouts, ItemTiers and KitText move from Home's private helpers into the kit for the character sheet and gallery; Home delegates to them and behaves as before." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Character sheet frame, `CHARACTER_SHEET` route with Back, roster side pane retired

The roster's side-by-side detail pane goes away. Its seven tabs move into a full-page `CharacterSheet` on the Roster tab, with the changes below. Enter or a double-click on a table row opens the sheet through the navigator. Shell Back and the sheet's "‹ Characters" link both return to the list with its filters, selection and scroll.
- **Death annotation** shows only while the character is marked dead (spec §6.2). `CustomizableTabs` gains conditional tabs for it, skipped like Analyst-only tabs without rewriting the saved order (spec §4.4).
- **Notes drafts** are saved when another character opens and whenever the sheet hides: another card, Back, "‹ Characters", the page's other tabs, and closing the workspace.
- **Provenance** (the snapshot evidence and the tab hint) shows in Analyst mode only (spec §3.2).
- **Storage problems** (an unreadable journal, a failed save) show as a warn banner in the sheet (spec §7).
- **Actions.** Mark dead is a danger button and Restore alive a secondary one in its place; Save notes is secondary. They act only on the character the sheet has loaded.

Line numbers below refer to `94db6f6`. Tasks 1–3 do not touch `CharacterJournalGUI`, `CharacterPanelGUI`, `Destination`, `WorkspaceShell.pageOf` or the character tests.

**Files:**
- Create:
  - `src/main/java/tomato/gui/glance/character/SheetFocus.java`
  - `src/main/java/tomato/gui/glance/character/SheetContext.java`
  - `src/main/java/tomato/gui/glance/character/CharacterSheet.java`
  - `src/main/java/tomato/gui/character/CharacterRosterView.java`
  - `src/main/java/tomato/gui/character/CharactersRouteTarget.java`
- Modify:
  - `src/main/java/tomato/gui/route/Destination.java`
  - `src/main/java/tomato/gui/modern/WorkspaceShell.java`
  - `src/main/java/tomato/gui/kit/CustomizableTabs.java` (conditional tabs)
  - `src/main/java/tomato/gui/character/CharacterJournalGUI.java`
  - `src/main/java/tomato/gui/character/CharacterPanelGUI.java` (replace)
  - `src/main/java/tomato/gui/TomatoGUI.java`
- Create tests:
  - `src/test/java/tomato/gui/character/RosterFixtures.java` (fixture helper)
  - `src/test/java/tomato/gui/glance/character/CharacterSheetTest.java`
  - `src/test/java/tomato/gui/character/CharacterRosterViewTest.java`
  - `src/test/java/tomato/gui/character/CharactersRouteTargetTest.java`
- Modify tests:
  - `src/test/java/tomato/gui/kit/CustomizableTabsTest.java` (add beside)
  - `src/test/java/tomato/gui/character/CharacterTabsTest.java` (replace)
  - `src/test/java/tomato/gui/character/CharacterJournalGuiTest.java` (replace)
  - `src/test/java/tomato/gui/character/CharacterJournalFreshnessRefreshTest.java` (replace)
  - `src/test/java/tomato/gui/character/CharacterViewStateTest.java`
  - `src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`
  - `src/test/java/tomato/gui/character/CharacterWaveFourEvidenceTest.java`
  - `src/test/java/tomato/gui/character/CharacterRosterStateTest.java`
  - `src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`
  - `src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`
  - `src/test/java/tomato/ShellRouteRegistrationTest.java`
- Verified unchanged:
  - `CharacterFilterBarTest` and `CharacterTableKindsTest`: list only; the names and the first table are unchanged.
  - `CharacterPlanningPanelTest`, `CharacterRosterQueryTest`, `PetFeedingFormTest` and `PetLayoutEvidenceTest`: they build their panels directly.
  - `ShellBackActionTest`: `pageOf(CHARACTER_SHEET) == 3` is in range.
  - `ShellNavigatorTest`.
  - `ui.WorkspaceUiTest`: page 3 is only selected.

**Interfaces:**
- Consumes:
  - From Task 2: `CharacterJournal.characterCopy(String key)` (a deep copy, or null) and `storageProblem()`.
  - From Task 3: `Banner` (WARN tone) and `KitLayouts.stack`.
  - `CharacterJournal.characters()`, `accounts()`, `revision()`, `markDead`, `notes` and `mostRecentCharacter()`.
  - `CustomizableTabs`:
    - `(String)`, `add`, `addAnalyst` and `component()`
    - `select`, `show` and `selectedId`
    - `order`, `hiddenIds` and `onSelect`
  - Kit and styling: `EmptyState(String, String, KitButton)`, `KitButton.ghost/secondary/danger`, `Sprites.sprite(int, int)`, `Type.emphasis()`, `DisplayModeModel.bind`.
  - `ContentStyle`: `page`, `tableScroll`, `wrappingText`, `reveal`, `font` and `Cell`.
  - Routing: `ShellNavigator`, `Navigator.nextBackToken()/backToken()/back()`, `Route.withPayload`.
  - The character panels: `CharacterEquipmentPanel`, `CharacterPlanningPanel(PlanningStore)` and `CharacterDeathPanel(CharacterJournal)`, all public.
  - `RosterViewState`.
- Produces:
  - `Destination.CHARACTER_SHEET` (appended), and `WorkspaceShell.pageOf(CHARACTER_SHEET) == 3`.
  - `CustomizableTabs.addWhen(String id, String title, Component, BooleanSupplier visible)` and `refreshConditions()`: a conditional tab is offered only while its condition holds, skipped like an Analyst-only tab without rewriting the saved order or hidden set; conditional and Analyst-only tabs never count as the last tab a view keeps.
  - `tomato.gui.glance.character.SheetFocus`:
    - `public record SheetFocus(String key, String tab)`. It rejects a key that is not `[0-9a-f]{64}:[0-9]+` and a tab that is not `[a-z0-9][a-z0-9-]*`.
    - `public static boolean validKey(String)`.
  - `tomato.gui.glance.character.SheetContext`:
    - `public record SheetContext(TomatoData data, CharacterJournal journal, Supplier<RosterDefinitions> definitions, DisplayModeModel mode, LongSupplier clock, PlanningStore plans)`. Every component is non-null.
    - A public 4-argument constructor `(data, journal, definitions, mode)` that uses `System::currentTimeMillis` and `PlanningStore.shared()`.
  - `tomato.gui.glance.character.CharacterSheet extends JPanel`, named `character-sheet`:
    - `public static final String UNAVAILABLE = "This character is not in the journal"`
    - `public CharacterSheet(SheetContext)`
    - `public void open(String key, String tab)`: a non-null tab is explicit, so it runs `show` then `select`.
    - `public String key()`, `public String selectedTab()`, `public CustomizableTabs tabs()`
    - `public boolean ready()`: the sheet shows the journal's read of its current key (the character or its unavailable state). Mark dead, Restore alive and Save notes act only while it is true; Mark dead and Restore alive clear it until the re-read shows the new state.
    - `public void saveDraft()`: saves a changed notes draft. The sheet calls it before another character opens, whenever it hides (`SHOWING_CHANGED`) and in `removeNotify`; `CharacterRosterView.showList`, the page's tab switch and `TomatoGUI.closeWorkspace` call it too.
    - `public void selectTab(String id)`: select only.
    - `public void onBack(Runnable)`, `public void bindNavigator(Navigator)`, `public void focusBackLink()`
    - `public void refresh()`: EDT; it runs only while showing or detached.
    - Package-private `void setTab(String id, JComponent content)`. The slots are `overview`, `gear` and `exalts`. Any other id throws `IllegalArgumentException`.
    - Package-private accessors for the moved components: `JComponent statTable()`, `CharacterEquipmentPanel equipmentPanel()` and `JComponent exaltTable()`.
    - Tabs: `CustomizableTabs("character")`, whose component is named `character-tabs` and saved as `ui.tabs.character`. Default order:
      - `overview` "Overview" (the moved "Stat maxing" table)
      - `gear` "Gear" (the moved `CharacterEquipmentPanel`)
      - `exalts` "Exalts" (the moved class-exalts table)
      - `goals` "Goals"
      - `notes` "Notes"
      - `evidence` "Snapshot evidence" (Analyst)
      - `death` "Death annotation", conditional: shown only while the character is marked dead (`addWhen`)
    - Component names:
      - Header: `character-sheet-back` ("‹ Characters"), `character-sheet-title`, `character-sheet-sprite`, `character-sheet-death` (Mark dead, danger), `character-sheet-restore` (Restore alive, secondary; one of the two shows), `character-sheet-storage` (warn banner), `character-snapshot-evidence` (Analyst), `character-sheet-hint` (Analyst)
      - Notes: `character-notes`, `character-notes-save`
      - Unavailable state: `character-sheet-unavailable` (EmptyState), `character-sheet-unavailable-back`
      - The page scroll: `character-sheet-scroll`
  - `tomato.gui.character.CharacterRosterView extends JPanel`, named `character-roster-view`:
    - A CardLayout with the cards `LIST = "list"` and `SHEET = "sheet"`.
    - `public CharacterRosterView(CharacterJournalGUI list, CharacterSheet sheet)`
    - `public void showSheet(String key, String tab, Runnable back)`, `public void showList()`, `public boolean showingSheet()`
    - `public CharacterJournalGUI listPanel()` (not `list()`, which would clash with `Component.list()`), `public CharacterSheet sheet()`, `public void bindNavigator(Navigator)`
    - Package-private: `openCharacter(String key)`, `navigator()`, `onReveal(Runnable)`, `reveal()`, `state()` and `currentKey()`.
  - `tomato.gui.character.CharactersRouteTarget implements RouteTarget`:
    - `public record CharactersState(boolean sheet, String key, String tab)`
    - `public static List<RouteTarget> of(CharacterRosterView view)`: one CHARACTERS target and one CHARACTER_SHEET target that share one view and one origin.
  - `CharacterJournalGUI`:
    - It loses the side pane and the `(journal, clock, definitions, plans)` constructor.
    - New public methods: `onOpenSheet(Consumer<String>)`, `List<CharacterRosterQuery.Row> visibleRows()` and `addRowsListener(Runnable)`.
    - New package-private members: `SHEET_TABS`, `selectedKey()`, `sheetTab()`, `sheetTabSelected(String)` and `focusRoster()`.
    - The table binds Enter to the action `open-character`, and a double-click also opens the row.
    - The view state gains the key `sheetTab` and keeps `tab`, the legacy index.
  - `CharacterPanelGUI`:
    - `public CharacterPanelGUI(TomatoData)` builds the default `SheetContext`.
    - `public CharacterPanelGUI(TomatoData, SheetContext)`.
    - `public List<RouteTarget> routeTargets()` (built once, so the two targets always share one Back origin), `public CharacterRosterView roster()`, `public CharacterSheet sheet()`.
    - `openGoals()` routes to the sheet's `goals` tab.
  - `TomatoGUI`:
    - It registers `characterPanel.routeTargets()` instead of `registerRetainedPage(Destination.CHARACTERS)`.
    - `plans.characters` calls `characterPanel.openGoals()`.
    - `closeWorkspace()` saves the sheet's notes draft.

- [ ] **Step 1: Write the failing tests and the fixture helper**

`src/test/java/tomato/gui/character/RosterFixtures.java`:
```java
package tomato.gui.character;

import java.awt.Component;
import java.awt.Container;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Navigator;
import tomato.planning.PlanningStore;

/**
 * Builds the Characters Roster tab (the list and the sheet) over a fixture journal, clock and definitions. The view is bound to
 * Navigator.NONE, so opening a character switches cards in place even if another test left a navigator installed.
 */
final class RosterFixtures {
    private RosterFixtures() { }

    static CharacterSheet sheet(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions, PlanningStore plans) {
        return new CharacterSheet(new SheetContext(new TomatoData(), journal, definitions, DisplayModeModel.application(), clock, plans));
    }
    static CharacterSheet sheet(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions) {
        return sheet(journal, clock, definitions, PlanningStore.shared());
    }
    static CharacterRosterView view(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions, PlanningStore plans) {
        CharacterRosterView view = new CharacterRosterView(new CharacterJournalGUI(journal, clock, definitions), sheet(journal, clock, definitions, plans));
        view.bindNavigator(Navigator.NONE);
        return view;
    }
    static CharacterRosterView view(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions) {
        return view(journal, clock, definitions, PlanningStore.shared());
    }
    /**
     * Enter on the list's selected row, through the table's own key binding. Returns once the sheet shows that character
     * (at once here; the sheet builds off the EDT from Task 5 on, and SnapshotTestSupport.await runs the EDT while it waits).
     */
    static void enter(CharacterRosterView view) {
        JTable roster = named(view.listPanel(), "character-roster", JTable.class);
        roster.getActionMap().get(roster.getInputMap().get(KeyStroke.getKeyStroke("ENTER"))).actionPerformed(null);
        if (view.showingSheet()) tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
    }
    static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/glance/character/CharacterSheetTest.java`:
```java
package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class CharacterSheetTest {
    private static final String ORDER = "ui.tabs.character";
    private static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;

    @Before public void remember() throws Exception {
        savedOrder = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, "");
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
    }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    private CharacterJournal journal(String file, int... ids) { return journal(new CharacterJournal(temp.getRoot().toPath().resolve(file)), ids); }
    private static CharacterJournal journal(CharacterJournal journal, int... ids) {
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id : ids) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.level = 20; c.receivedAt = 1000;
            c.supplied("class"); c.supplied("level"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static CharacterSheet sheet(CharacterJournal journal) {
        return new CharacterSheet(new SheetContext(new TomatoData(), journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()));
    }
    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    @Test public void tabsKeepTheirIdsAndOrderSnapshotEvidenceIsAnalystOnlyAndSlotsAreReplaceable() throws Exception {
        try (CharacterJournal journal = journal("tabs.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                CharacterSheet sheet = sheet(journal);
                assertEquals("character-sheet", sheet.getName());
                List<String> order = Arrays.asList("overview", "gear", "exalts", "goals", "notes", "evidence", "death");
                assertEquals(order, sheet.tabs().order());
                JTabbedPane tabs = sheet.tabs().component();
                assertEquals("character-tabs", tabs.getName());
                assertEquals("Death annotation shows only for a character marked dead", Arrays.asList("Overview", "Gear", "Exalts", "Goals", "Notes", "Snapshot evidence"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
                sheet.open(ACCOUNT + ":1", "notes"); assertEquals("notes", sheet.selectedTab());
                sheet.open(ACCOUNT + ":1", "goals"); assertEquals("An explicit tab is selected", "goals", sheet.selectedTab());
                JPanel replacement = new JPanel();
                sheet.setTab("overview", replacement);
                assertEquals("A replaced slot keeps its id and place", order, sheet.tabs().order());
                assertTrue(SwingUtilities.isDescendingFrom(replacement, tabs.getComponentAt(0)));
                try { sheet.setTab("notes", new JPanel()); fail("Only overview, gear and exalts are slots"); } catch (IllegalArgumentException expected) { }
            });
        }
    }

    @Test public void headerHasTheBackLinkIdentityAndMarkDeadShowsTheDeathTab() throws Exception {
        try (CharacterJournal journal = journal("header.json", 7)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                AtomicInteger backs = new AtomicInteger();
                sheet.onBack(backs::incrementAndGet);
                sheet.open(ACCOUNT + ":7", null);
                assertEquals(ACCOUNT + ":7", sheet.key()); assertTrue(sheet.ready());
                AbstractButton back = named(sheet, "character-sheet-back", AbstractButton.class);
                assertEquals("‹ Characters", back.getText());
                back.doClick(); assertEquals(1, backs.get());
                JTextArea title = named(sheet, "character-sheet-title", JTextArea.class);
                assertTrue(title.getText(), title.getText().contains("#7") && title.getText().contains("Level 20"));
                AbstractButton death = named(sheet, "character-sheet-death", AbstractButton.class), restore = named(sheet, "character-sheet-restore", AbstractButton.class);
                assertEquals("Mark dead", death.getText()); assertTrue(death.isVisible()); assertFalse(restore.isVisible());
                assertFalse("An alive character has no Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                death.doClick();
                assertTrue(journal.characterCopy(ACCOUNT + ":7").dead);
                assertFalse(death.isVisible()); assertTrue(restore.isVisible()); assertEquals("Restore alive", restore.getText());
                assertTrue(title.getText().contains("Marked dead manually"));
                assertTrue("Marking dead shows the Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                assertEquals("…without rewriting the saved order", "", PropertiesManager.getProperty(ORDER));
                restore.doClick();
                assertFalse(journal.characterCopy(ACCOUNT + ":7").dead);
                assertTrue(death.isVisible()); assertEquals("Mark dead", death.getText());
                assertFalse(sheet.tabs().visibleIds().contains("death"));
            });
        }
    }

    @Test public void anUnknownKeyShowsTheUnavailableStateAndAKnownOneTheTabs() throws Exception {
        try (CharacterJournal journal = journal("unknown.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":404", null);
                EmptyState missing = named(sheet, "character-sheet-unavailable", EmptyState.class);
                assertTrue(missing.isVisible());
                assertEquals(CharacterSheet.UNAVAILABLE, missing.getAccessibleContext().getAccessibleName());
                assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                assertEquals(" ", named(sheet, "character-snapshot-evidence", JTextArea.class).getText());
                sheet.open(ACCOUNT + ":1", null);
                assertFalse(missing.isVisible());
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void aNotesDraftSurvivesRefreshAndIsSavedWhenAnotherCharacterOpens() throws Exception {
        try (CharacterJournal journal = journal("notes.json", 1, 2)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", "notes");
                JTextArea notes = named(sheet, "character-notes", JTextArea.class);
                notes.setText("Draft for one"); sheet.refresh();
                assertEquals("A refresh keeps the draft", "Draft for one", notes.getText());
                assertEquals("", journal.characterCopy(ACCOUNT + ":1").notes);
                sheet.open(ACCOUNT + ":2", null);
                assertEquals("Opening another character saves the draft", "Draft for one", journal.characterCopy(ACCOUNT + ":1").notes);
                assertEquals("", notes.getText());
                notes.setText("Saved for two"); named(sheet, "character-notes-save", AbstractButton.class).doClick();
                assertEquals("Saved for two", journal.characterCopy(ACCOUNT + ":2").notes);
            });
        }
    }

    @Test public void hidingTheSheetSavesItsNotesDraft() throws Exception {
        try (CharacterJournal journal = journal("hide.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                JFrame frame = new JFrame("Sheet hide"); frame.setContentPane(sheet); frame.setSize(900, 600); frame.setVisible(true);
                try {
                    sheet.open(ACCOUNT + ":1", "notes");
                    named(sheet, "character-notes", JTextArea.class).setText("Kept when the sheet hides");
                    sheet.setVisible(false); // what another card, Back or another Characters tab does
                    assertEquals("Kept when the sheet hides", journal.characterCopy(ACCOUNT + ":1").notes);
                } finally { frame.dispose(); }
            });
        }
    }

    @Test public void snapshotEvidenceAndTheTabHintAreAnalystOnly() throws Exception {
        try (CharacterJournal journal = journal("provenance.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", null);
                JTextArea evidence = named(sheet, "character-snapshot-evidence", JTextArea.class), hint = named(sheet, "character-sheet-hint", JTextArea.class);
                assertFalse("Simple hides provenance (spec §3.2)", evidence.isVisible()); assertFalse(hint.isVisible());
                assertTrue("The text is still kept current", evidence.getText().contains("Snapshot update age"));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertTrue(evidence.isVisible()); assertTrue(hint.isVisible());
            });
        }
    }

    @Test public void anUnreadableJournalAndAFailedNotesSaveShowAWarnBanner() throws Exception {
        Path broken = temp.getRoot().toPath().resolve("broken.json");
        Files.writeString(broken, "{broken");
        Path blocker = temp.newFile("blocker").toPath(); // a file where the journal's folder should be: every save fails
        CharacterSheet[] shown = new CharacterSheet[1];
        try (CharacterJournal unreadable = new CharacterJournal(broken); CharacterJournal failing = journal(new CharacterJournal(blocker.resolve("journal.json")), 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(unreadable);
                sheet.open(ACCOUNT + ":1", null);
                Banner storage = named(sheet, "character-sheet-storage", Banner.class);
                assertTrue(storage.isVisible()); assertTrue(storage.warns()); assertTrue(storage.text(), storage.text().startsWith("Cannot read"));
                shown[0] = sheet(failing);
                shown[0].open(ACCOUNT + ":1", "notes");
                assertFalse("Nothing has failed yet", named(shown[0], "character-sheet-storage", Banner.class).isVisible());
                named(shown[0], "character-notes", JTextArea.class).setText("Never reaches the disk");
                named(shown[0], "character-notes-save", AbstractButton.class).doClick();
            });
            failing.save(); // the saver thread's write fails
            SwingUtilities.invokeAndWait(() -> {
                shown[0].refresh();
                Banner storage = named(shown[0], "character-sheet-storage", Banner.class);
                assertTrue("A failed save warns inside the sheet", storage.isVisible()); assertTrue(storage.warns());
                assertTrue(storage.text(), storage.text().startsWith("Save failed"));
            });
        }
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/kit/CustomizableTabsTest.java` — **add beside**. Insert before `    private final Map<String, String> store = new HashMap<>();`:
```java
    @Test public void aConditionalTabIsSkippedWithoutRewritingTheSavedOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            boolean[] dead = {false};
            store.put("ui.tabs.character", "death,overview,gear,exalts,evidence|");
            CustomizableTabs tabs = tabs().addWhen("death", "Death annotation", new JPanel(), () -> dead[0]);
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals("Every known tab keeps its saved place", Arrays.asList("death", "overview", "gear", "exalts", "evidence"), tabs.order());
            dead[0] = true;
            assertEquals("Nothing changes until the conditions are re-checked", 3, tabs.component().getTabCount());
            tabs.refreshConditions();
            assertEquals(Arrays.asList("death", "overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals("Death annotation", tabs.component().getTitleAt(0));
            tabs.select("death"); assertEquals("death", tabs.selectedId());
            dead[0] = false; tabs.refreshConditions();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertNotEquals("death", tabs.selectedId());
            assertEquals("A condition never rewrites the saved order", "death,overview,gear,exalts,evidence|", store.get("ui.tabs.character"));
            assertTrue(tabs.hide("overview")); assertTrue(tabs.hide("gear"));
            dead[0] = true; tabs.refreshConditions();
            assertFalse("A tab that may disappear never counts as the view's last tab", tabs.hide("exalts"));
            assertTrue(tabs.hide("death"));
        });
    }

```

`src/test/java/tomato/gui/character/CharacterRosterViewTest.java`:
```java
package tomato.gui.character;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

/** The Roster tab's two cards: Enter and double-click open a row's sheet, the back link returns, and the gallery's row feed. */
public class CharacterRosterViewTest {
    private static final String ACCOUNT = CharacterJournal.accountKey("roster-view-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private CharacterJournal journal() {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.receivedAt = 1000L * id; c.supplied("class"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static String keyAt(JTable roster, int row) {
        String label = roster.getValueAt(row, 0).toString();
        return ACCOUNT + ":" + label.substring(label.lastIndexOf('#') + 1);
    }

    @Test public void enterAndDoubleClickOpenARowsSheetAndTheBackLinkReturnsToTheList() throws Exception {
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                assertEquals("character-roster-view", view.getName());
                JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
                assertEquals(3, roster.getRowCount()); assertFalse(view.showingSheet());
                assertTrue(view.listPanel().isVisible()); assertFalse(view.sheet().isVisible());
                roster.setRowSelectionInterval(1, 1);
                RosterFixtures.enter(view);
                assertTrue("Enter opens the selected row", view.showingSheet());
                assertEquals(keyAt(roster, 1), view.sheet().key());
                assertFalse(view.listPanel().isVisible()); assertTrue(view.sheet().isVisible());
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse("Without a navigator the back link switches cards in place", view.showingSheet());
                assertTrue(view.listPanel().isVisible());
                assertEquals("The list keeps its selection", 1, roster.getSelectedRow());
                Rectangle cell = roster.getCellRect(2, 0, true);
                roster.dispatchEvent(new MouseEvent(roster, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 1, false, MouseEvent.BUTTON1));
                assertFalse("A single click only selects", view.showingSheet());
                roster.dispatchEvent(new MouseEvent(roster, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 2, false, MouseEvent.BUTTON1));
                assertTrue("A double-click opens the row under the pointer", view.showingSheet());
                assertEquals(keyAt(roster, 2), view.sheet().key());
            });
        }
    }

    @Test public void theBackLinkKeepsAnUnsavedNotesDraft() throws Exception {
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
                roster.setRowSelectionInterval(0, 0); String key = keyAt(roster, 0);
                RosterFixtures.enter(view);
                JTextArea notes = RosterFixtures.named(view.sheet(), "character-notes", JTextArea.class);
                notes.setText("Typed, never saved");
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse(view.showingSheet());
                assertEquals("Back without Save keeps the note", "Typed, never saved", journal.characterCopy(key).notes);
                RosterFixtures.enter(view);
                assertEquals("…and the sheet shows it again", "Typed, never saved", notes.getText());
            });
        }
    }

    @Test public void visibleRowsFollowSearchAndSortAndNotifyListeners() throws Exception {
        try (CharacterJournal journal = journal()) {
            journal.notes(ACCOUNT + ":2", "needle");
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI list = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
                AtomicInteger changes = new AtomicInteger();
                list.addRowsListener(changes::incrementAndGet);
                assertEquals(3, list.visibleRows().size());
                JTextField search = RosterFixtures.named(list, "character-search", JTextField.class);
                search.setText("needle");
                assertTrue("Search notifies", changes.get() > 0);
                assertEquals(1, list.visibleRows().size());
                assertEquals(ACCOUNT + ":2", list.visibleRows().get(0).record.key);
                search.setText("");
                JTable roster = RosterFixtures.named(list, "character-roster", JTable.class);
                int before = changes.get();
                roster.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                assertTrue("A new sort notifies", changes.get() > before);
                List<CharacterRosterQuery.Row> rows = list.visibleRows();
                assertEquals(3, rows.size());
                for (int i = 0; i < rows.size(); i++) assertEquals("Rows follow the table's order", keyAt(roster, i), rows.get(i).record.key);
            });
        }
    }
}
```

`src/test/java/tomato/gui/character/CharactersRouteTargetTest.java`:
```java
package tomato.gui.character;

import java.awt.Point;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.roster.RosterStateTestSupport;
import tomato.gui.route.*;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;

/** Characters routes on a real ShellNavigator: the list, a sheet and its tab, Back, the sheet's back link and search's goals. */
public class CharactersRouteTargetTest {
    private static final String TABS = "ui.tabs.character";
    private static final String[] KEYS = {TABS, "ui.tabs.characters", "ux.archive.characters-live-roster", "ui.filters.characters.open"};
    private static final String ACCOUNT = CharacterJournal.accountKey("route-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> saved = new LinkedHashMap<>();
    private final int[] page = {0};
    private CharacterJournal journal;

    @Before public void isolate() {
        for (String key : KEYS) { saved.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.receivedAt = 1000L * id; c.supplied("class"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
    }
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> { }); // Queued view-state saves run before the preferences are restored.
        journal.close();
        saved.forEach((key, value) -> PropertiesManager.setProperties(key, value == null ? "" : value));
    }

    private static String key(int id) { return ACCOUNT + ":" + id; }
    private static Route sheet(String key, String tab) { return Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, tab)); }
    private static String keyAt(JTable roster, int row) {
        String label = roster.getValueAt(row, 0).toString();
        return ACCOUNT + ":" + label.substring(label.lastIndexOf('#') + 1);
    }
    private ShellNavigator navigator(CharacterRosterView view, List<RouteTarget> targets) {
        ShellNavigator navigator = new ShellNavigator(() -> page[0], selected -> page[0] = selected, WorkspaceShell::pageOf, ShellNavigator.DEFAULT_CAPACITY);
        for (RouteTarget target : targets) navigator.register(target);
        view.bindNavigator(navigator);
        return navigator;
    }
    private ShellNavigator navigator(CharacterRosterView view) { return navigator(view, CharactersRouteTarget.of(view)); }

    @Test public void aPlainRouteOpensTheListAndASheetRouteOpensItsTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            ShellNavigator navigator = navigator(view);
            view.showSheet(key(1), null, view::showList);
            assertTrue(navigator.open(Route.to(Destination.CHARACTERS)));
            assertEquals(3, page[0]); assertFalse("A plain route shows the list", view.showingSheet());
            assertTrue(navigator.open(sheet(key(2), "notes")));
            assertEquals(3, page[0]); assertTrue(view.showingSheet());
            assertEquals(key(2), view.sheet().key()); assertEquals("notes", view.sheet().selectedTab());
            assertFalse("The list takes no payload", navigator.canOpen(Route.to(Destination.CHARACTERS).withPayload(new SheetFocus(key(2), null))));
            assertFalse("A sheet needs a character", navigator.canOpen(Route.to(Destination.CHARACTER_SHEET)));
            assertFalse(navigator.canOpen(Route.to(Destination.CHARACTER_SHEET).withPayload(key(2))));
            for (String[] bad : new String[][]{{"not-a-key", null}, {key(1).toUpperCase(Locale.ROOT), null}, {key(1), "Not A Tab"}}) {
                try { new SheetFocus(bad[0], bad[1]); fail("Rejected: " + Arrays.toString(bad)); } catch (IllegalArgumentException expected) { }
            }
            assertTrue(navigator.back()); assertFalse("Back restores the list", view.showingSheet()); assertEquals(3, page[0]);
            assertTrue(navigator.back()); assertEquals(0, page[0]);
        });
    }

    @Test public void theBackLinkReturnsToTheListAsItWasAndPopsOnlyItsOwnEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            ShellNavigator navigator = navigator(view);
            page[0] = 3;
            JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
            JViewport viewport = (JViewport) roster.getParent();
            roster.setRowSelectionInterval(2, 2); String selected = keyAt(roster, 2);
            viewport.setViewPosition(new Point(0, 20));
            RosterFixtures.enter(view);
            assertTrue(view.showingSheet()); assertEquals(selected, view.sheet().key()); assertTrue(navigator.canGoBack());
            RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
            assertFalse(view.showingSheet());
            assertFalse("The link returned through the Back entry its open pushed", navigator.canGoBack());
            assertEquals(selected, keyAt(roster, roster.getSelectedRow()));
            assertEquals(new Point(0, 20), viewport.getViewPosition());
            page[0] = 14;
            assertTrue(navigator.open(sheet(key(1), null)));
            assertEquals(3, page[0]);
            RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
            assertFalse("Opened from elsewhere, the link still goes to the list", view.showingSheet());
            assertEquals(3, page[0]);
            assertTrue("Shell Back still returns to the page the sheet was opened from", navigator.back());
            assertEquals(14, page[0]);
        });
    }

    @Test public void anUnknownKeyShowsTheUnavailableState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            ShellNavigator navigator = navigator(view);
            String missing = ACCOUNT + ":404";
            assertTrue("A well-formed key is accepted; the sheet says what it cannot show", navigator.open(sheet(missing, null)));
            assertTrue(view.showingSheet()); assertEquals(missing, view.sheet().key());
            tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready); // at once here; from Task 5 the sheet reads off the EDT
            EmptyState state = RosterFixtures.named(view.sheet(), "character-sheet-unavailable", EmptyState.class);
            assertTrue(state.isVisible());
            assertEquals("This character is not in the journal", CharacterSheet.UNAVAILABLE);
            assertEquals(CharacterSheet.UNAVAILABLE, state.getAccessibleContext().getAccessibleName());
            RosterFixtures.named(view.sheet(), "character-sheet-unavailable-back", AbstractButton.class).doClick();
            assertFalse(view.showingSheet());
        });
    }

    @Test public void onlyExplicitNavigationShowsAHiddenTab() throws Exception {
        RosterStateTestSupport.Memory memory = new RosterStateTestSupport.Memory();
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView first = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            first.listPanel().bindViewState(memory.store);
            first.showSheet(key(1), "goals", first::showList);
            assertEquals("goals", first.listPanel().sheetTab());
            first.listPanel().saveViewState();
            PropertiesManager.setProperties(TABS, "overview,gear,exalts,goals,notes,evidence,death|goals");
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            view.listPanel().bindViewState(memory.store);
            ShellNavigator navigator = navigator(view);
            assertNull("Startup restore leaves the sheet closed", view.sheet().key());
            assertEquals("goals", view.listPanel().sheetTab());
            assertTrue(navigator.open(sheet(key(1), null)));
            assertTrue("A route without a tab only selects the saved one", view.sheet().tabs().hiddenIds().contains("goals"));
            assertEquals("overview", view.sheet().selectedTab());
            assertEquals("overview,gear,exalts,goals,notes,evidence,death|goals", PropertiesManager.getProperty(TABS));
            assertTrue(navigator.open(sheet(key(1), "goals")));
            assertFalse("A route's tab is shown", view.sheet().tabs().hiddenIds().contains("goals"));
            assertEquals("goals", view.sheet().selectedTab());
        });
    }

    @Test public void planningSearchOpensTheSelectedCharactersGoalsElseTheMostRecent() throws Exception {
        TomatoData data = new TomatoData() { @Override public synchronized CharacterJournal characterJournal() { return journal; } };
        SwingUtilities.invokeAndWait(() -> {
            CharacterPanelGUI panel = new CharacterPanelGUI(data,
                new SheetContext(data, journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()));
            CharacterRosterView roster = panel.roster();
            ShellNavigator navigator = navigator(roster, panel.routeTargets());
            panel.bindNavigator(navigator);
            JTable table = RosterFixtures.named(roster.listPanel(), "character-roster", JTable.class);
            table.setRowSelectionInterval(1, 1); String selected = keyAt(table, 1);
            page[0] = 5;
            panel.openGoals();
            assertEquals(3, page[0]); assertTrue(roster.showingSheet());
            assertEquals(selected, roster.sheet().key()); assertEquals("goals", roster.sheet().selectedTab());
            assertTrue(navigator.back()); assertEquals(5, page[0]);
            roster.showList(); table.clearSelection();
            panel.openGoals();
            assertEquals("Without a selection, the journal's most recent character", journal.mostRecentCharacter().key, roster.sheet().key());
        });
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.glance.character.CharacterSheetTest" --tests "tomato.gui.character.CharacterRosterViewTest" --tests "tomato.gui.character.CharactersRouteTargetTest" --tests "tomato.gui.kit.CustomizableTabsTest"`
Expected: FAIL. The test sources do not compile: `package tomato.gui.glance.character does not exist` and `cannot find symbol: method addWhen`.

- [ ] **Step 3: Add the destination and its page**

`src/main/java/tomato/gui/route/Destination.java`, replace:
```java
    ENCOUNTER, NOTIFICATIONS, ALERT_DRAFT, BRIDGE_REVIEW, LOGGING, MY_INFO, CHARACTERS, QUESTS, HOME
```
with:
```java
    ENCOUNTER, NOTIFICATIONS, ALERT_DRAFT, BRIDGE_REVIEW, LOGGING, MY_INFO, CHARACTERS, QUESTS, HOME, CHARACTER_SHEET
```

`src/main/java/tomato/gui/modern/WorkspaceShell.java` (`pageOf`, line 724), replace:
```java
            case CHARACTERS: return 3;
```
with:
```java
            case CHARACTERS: case CHARACTER_SHEET: return 3; // The sheet is a card on the Characters Roster tab.
```

- [ ] **Step 4: Create `SheetFocus` and `SheetContext`; add conditional tabs to `CustomizableTabs`**

`src/main/java/tomato/gui/glance/character/SheetFocus.java`:
```java
package tomato.gui.glance.character;

import java.util.regex.Pattern;

/**
 * Route payload for {@code Destination.CHARACTER_SHEET}. The key is the journal key "<64 hex>:<characterId>". The tab is
 * a sheet tab id to show and select, or null to select the remembered tab. The tab is not checked against the tabs that exist.
 */
public record SheetFocus(String key, String tab) {
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}:[0-9]+"), TAB = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public SheetFocus {
        if (!validKey(key)) throw new IllegalArgumentException("Not a journal character key");
        if (tab != null && !TAB.matcher(tab).matches()) throw new IllegalArgumentException("Not a sheet tab id");
    }

    public static boolean validKey(String key) { return key != null && KEY.matcher(key).matches(); }
}
```

`src/main/java/tomato/gui/glance/character/SheetContext.java`:
```java
package tomato.gui.glance.character;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayModeModel;
import tomato.planning.PlanningStore;

/**
 * What a character sheet reads:
 * - live data, for the character being played
 * - the journal, item and cap definitions, and the display mode
 * - the clock for snapshot ages, and the goals store
 * The four-argument form uses the system clock and the shared goals store.
 */
public record SheetContext(TomatoData data, CharacterJournal journal, Supplier<RosterDefinitions> definitions, DisplayModeModel mode,
                           LongSupplier clock, PlanningStore plans) {
    public SheetContext {
        Objects.requireNonNull(data, "data"); Objects.requireNonNull(journal, "journal"); Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(clock, "clock"); Objects.requireNonNull(plans, "plans");
    }

    public SheetContext(TomatoData data, CharacterJournal journal, Supplier<RosterDefinitions> definitions, DisplayModeModel mode) {
        this(data, journal, definitions, mode, System::currentTimeMillis, PlanningStore.shared());
    }
}
```

`src/main/java/tomato/gui/kit/CustomizableTabs.java` (the sheet's Death annotation tab shows only for a character marked dead):
1. Replace `import java.util.function.BiConsumer;` with that line followed by `import java.util.function.BooleanSupplier;`.
2. In the class comment, replace ` * and hidden. Analyst-only tabs are skipped in Simple mode without changing the saved order.` with ` * and hidden. Analyst-only tabs are skipped in Simple mode, and conditional tabs while their condition is false, without changing the saved order.`
3. Replace:
```java
        final boolean analystOnly;
        Entry(String id, String title, Component component, boolean analystOnly) {
            this.id = id; this.title = title; this.component = component; this.analystOnly = analystOnly;
        }
```
with:
```java
        final boolean analystOnly;
        /** Null: always offered. Otherwise offered only while it is true; {@link #refreshConditions} re-checks it. */
        final BooleanSupplier condition;
        Entry(String id, String title, Component component, boolean analystOnly, BooleanSupplier condition) {
            this.id = id; this.title = title; this.component = component; this.analystOnly = analystOnly; this.condition = condition;
        }
        /** Neither Analyst-only nor conditional: only such a tab may be the one a view keeps when the others are hidden. */
        boolean steady() { return !analystOnly && condition == null; }
```
4. Replace:
```java
    public CustomizableTabs add(String id, String title, Component component) { return add(id, title, component, false); }
    public CustomizableTabs addAnalyst(String id, String title, Component component) { return add(id, title, component, true); }

    private CustomizableTabs add(String id, String title, Component component, boolean analystOnly) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Tab IDs use lowercase letters, digits and hyphens: " + id);
        if (entries.containsKey(id)) throw new IllegalArgumentException("Duplicate tab ID " + id);
        entries.put(id, new Entry(id, title, component, analystOnly));
```
with:
```java
    public CustomizableTabs add(String id, String title, Component component) { return add(id, title, component, false, null); }
    public CustomizableTabs addAnalyst(String id, String title, Component component) { return add(id, title, component, true, null); }
    /**
     * A tab offered only while {@code visible} is true (the sheet's Death annotation for a character marked dead). Like an
     * Analyst-only tab it is skipped without changing the saved order or hidden set (spec §4.4); call {@link #refreshConditions}
     * after the condition may have changed.
     */
    public CustomizableTabs addWhen(String id, String title, Component component, BooleanSupplier visible) {
        return add(id, title, component, false, Objects.requireNonNull(visible, "visible"));
    }

    /** Re-checks the conditional tabs and shows or skips each; the saved order and hidden set are never rewritten. EDT. */
    public void refreshConditions() { rebuild(); }

    private CustomizableTabs add(String id, String title, Component component, boolean analystOnly, BooleanSupplier condition) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Tab IDs use lowercase letters, digits and hyphens: " + id);
        if (entries.containsKey(id)) throw new IllegalArgumentException("Duplicate tab ID " + id);
        entries.put(id, new Entry(id, title, component, analystOnly, condition));
```
5. In `visibleIds`, replace `            if (!entries.get(id).analystOnly || mode.analyst()) { result.add(id); break; }` with `            if (offered(entries.get(id))) { result.add(id); break; }`.
6. In `hide`, replace `        if (!entries.get(id).analystOnly && visible.stream().filter(key -> !entries.get(key).analystOnly).count() <= 1) return false;` with `        if (entries.get(id).steady() && visible.stream().filter(key -> entries.get(key).steady()).count() <= 1) return false;`. Unchanged for tabs without a condition; a tab that may disappear never counts as the one that keeps a view from losing its last tab.
7. Replace `    private boolean shown(Entry entry) { return !hidden.contains(entry.id) && (!entry.analystOnly || mode.analyst()); }` with:
```java
    private boolean shown(Entry entry) { return !hidden.contains(entry.id) && offered(entry); }
    /** Offered by the display mode and, for a conditional tab, by its condition. */
    private boolean offered(Entry entry) { return (!entry.analystOnly || mode.analyst()) && (entry.condition == null || entry.condition.getAsBoolean()); }
```

- [ ] **Step 5: Create `CharacterSheet` with the moved detail tabs**

The stat, class-exalt and evidence fills, the time evidence and the notes and death actions are moved from `CharacterJournalGUI.select`/`refreshTimeEvidence` (lines 342–378, 440–451). What changes on the way:
- The draft rule moves from "the selection changed" to "another character opened or the sheet hid".
- Death annotation is conditional (`addWhen`), and the tabs re-check it after every journal read.
- Mark dead becomes a danger button with a secondary Restore alive beside it (one shows); both, and Save notes, act only while `ready()`.
- The snapshot evidence and the tab hint follow the display mode; a storage problem shows the warn banner.
- The timer runs only while the sheet shows. The journal read (`loaded`) is separate from the refresh of what is shown (`shown`), so Task 5's presenter can feed it from its build thread.

`src/main/java/tomato/gui/glance/character/CharacterSheet.java`:
```java
package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterDeathPanel;
import tomato.gui.character.CharacterEquipmentPanel;
import tomato.gui.character.CharacterPlanningPanel;
import tomato.gui.kit.Banner;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.Navigator;
import tomato.gui.stats.Formatters;
import tomato.realmshark.enums.CharacterClass;

/**
 * One character's full page on the Characters Roster tab: a header (back link, identity, Mark dead or Restore alive, a storage
 * warning and the snapshot evidence) over {@code CustomizableTabs("character")}. Overview, Gear and Exalts are slots whose
 * content later tasks replace with {@link #setTab}; Death annotation shows only while the character is marked dead (spec §6.2).
 * - Snapshot evidence and the tab hint are provenance: Analyst mode only (spec §3.2).
 * - Mark dead, Restore alive and Save notes act only on the character this sheet has loaded ({@link #ready}).
 * - A notes draft is saved when another character opens and whenever the sheet hides (another card, Back, another Characters
 *   tab, closing the workspace); refreshes never replace it.
 * - An unreadable journal or a failed save shows a warn banner (spec §7).
 * While it shows, the sheet re-reads the journal on the EDT when its revision moves (checked once a second, by a timer that
 * runs only while the sheet shows), as the roster's side pane did. EDT only.
 */
public final class CharacterSheet extends JPanel {
    /** Title of the unavailable state, for a key the journal does not hold. */
    public static final String UNAVAILABLE = "This character is not in the journal";
    private static final String TABS_CARD = "tabs", UNAVAILABLE_CARD = "unavailable";

    private final SheetContext context;
    private final CustomizableTabs tabs = new CustomizableTabs("character");
    private final Map<String, JPanel> slots = new HashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final KitButton back = KitButton.ghost("‹ Characters");
    private final JLabel sprite = new JLabel();
    private final JTextArea title = ContentStyle.wrappingText(" "), seen = ContentStyle.wrappingText(" ");
    private final JTextArea hint = ContentStyle.wrappingText("Base stats exclude captured boosts. Caps use local game assets; missing values stay unknown.");
    /** Marking a character dead is destructive; Restore alive takes its place while the character is marked dead. */
    private final KitButton death = KitButton.danger("Mark dead"), restore = KitButton.secondary("Restore alive");
    private final KitButton saveNotes = KitButton.secondary("Save notes");
    /** The journal cannot be read, or its last save failed. */
    private final Banner storage = new Banner("character-sheet-storage");
    private final JTextArea notes = new JTextArea(3, 30);
    private final DefaultTableModel statModel = model("Stat", "Base", "Cap", "Potions to max", "Field evidence");
    private final DefaultTableModel exaltModel = model("Stat", "Level", "Completions", "Next tier");
    private final DefaultTableModel metadataModel = model("Field", "Value", "Field evidence");
    private final JScrollPane statTable, exaltTable;
    private final CharacterEquipmentPanel equipment = new CharacterEquipmentPanel();
    private final CharacterPlanningPanel planning;
    private final CharacterDeathPanel deathPanel;
    private final javax.swing.Timer timer;
    private Runnable backAction = () -> { };
    /** {@code loadedKey}: the key whose journal read this sheet shows; the actions wait until it equals {@code key}. */
    private String key, filledKey, loadedKey;
    private CharacterRecord record;
    private List<CharacterRecord> records = Collections.emptyList();
    private List<AccountRecord> accounts = Collections.emptyList();
    private RosterDefinitions definitions = RosterDefinitions.empty();
    private long revision = -1;

    public CharacterSheet(SheetContext context) {
        super(new BorderLayout());
        this.context = Objects.requireNonNull(context, "context");
        setName("character-sheet");
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        planning = new CharacterPlanningPanel(context.plans());
        deathPanel = new CharacterDeathPanel(context.journal());
        back.setName("character-sheet-back"); back.setToolTipText("Back to the character list");
        back.getAccessibleContext().setAccessibleName("Back to Characters");
        back.addActionListener(e -> backAction.run());
        sprite.setName("character-sheet-sprite");
        title.setName("character-sheet-title"); ContentStyle.font(title, Type.emphasis());
        death.setName("character-sheet-death"); restore.setName("character-sheet-restore"); saveNotes.setName("character-notes-save");
        restore.setVisible(false);
        seen.setName("character-snapshot-evidence"); hint.setName("character-sheet-hint");
        storage.setTone(Tokens.Tone.WARN); storage.setVisible(false);
        JPanel backRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); backRow.add(back);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, Tokens.XS, 0)); actions.add(death); actions.add(restore);
        JPanel identity = new JPanel(new BorderLayout(8, 0));
        identity.add(sprite, BorderLayout.WEST); identity.add(title, BorderLayout.CENTER); identity.add(actions, BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout(0, 4)); header.setName("character-sheet-header");
        header.add(backRow, BorderLayout.NORTH); header.add(identity, BorderLayout.CENTER);
        header.add(KitLayouts.stack(Tokens.XS, storage, seen), BorderLayout.SOUTH);

        JTable stats = table(statModel);
        stats.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, s, f, row, col);
                setHorizontalAlignment(RIGHT);
                if (!s) setForeground(t.getForeground());
                if (v instanceof Integer) {
                    if ((Integer)v == 0) { setText("Maxed"); if (!s) setForeground(ContentStyle.color("mint")); }
                    else if (!s) setForeground(ContentStyle.color("violet"));
                }
                return this;
            }
        });
        statTable = ContentStyle.tableScroll(stats, 3);
        exaltTable = ContentStyle.tableScroll(table(exaltModel), 3);
        JPanel notePanel = new JPanel(new BorderLayout(8, 8)); notes.setLineWrap(true); notes.setWrapStyleWord(true);
        notes.setName("character-notes"); notes.setFont(ContentStyle.body()); notes.getAccessibleContext().setAccessibleName("Character notes");
        JScrollPane noteScroll = new JScrollPane(notes) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, notes.getFontMetrics(notes.getFont()).getHeight() * 3 + insets.top + insets.bottom);
            }
        };
        notePanel.add(noteScroll, BorderLayout.CENTER); notePanel.add(saveNotes, BorderLayout.SOUTH);
        tabs.add("overview", "Overview", slot("overview", statTable))
            .add("gear", "Gear", slot("gear", equipment))
            .add("exalts", "Exalts", slot("exalts", exaltTable))
            .add("goals", "Goals", planning)
            .add("notes", "Notes", notePanel)
            // Raw field provenance is diagnostic: Analyst mode only (spec §3.2); the saved order still includes it.
            .addAnalyst("evidence", "Snapshot evidence", ContentStyle.tableScroll(table(metadataModel), 3))
            // Only while the character is marked dead (spec §6.2); skipping it never rewrites the saved order (spec §4.4).
            .addWhen("death", "Death annotation", deathPanel, () -> record != null && record.dead);
        JTabbedPane strip = tabs.component(); strip.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        hint.setToolTipText("Potion estimates use +5 Life/Mana and +1 other stats. Exalts are account/class progress shared across characters.");
        JPanel content = new JPanel(new BorderLayout(0, 8)) {
            // Tab chrome and usable rows must fit after font and width changes; the page scrolls instead of squeezing them.
            @Override public Dimension getMinimumSize() { return new Dimension(0, strip.getMinimumSize().height + (hint.isVisible() ? hint.getPreferredSize().height + 8 : 0)); }
        };
        content.add(strip, BorderLayout.CENTER); content.add(hint, BorderLayout.SOUTH);
        KitButton showAll = KitButton.secondary("Show all characters"); showAll.setName("character-sheet-unavailable-back");
        showAll.addActionListener(e -> backAction.run());
        EmptyState missing = new EmptyState(UNAVAILABLE, "The journal has no saved character with this reference. Choose one from the character list.", showAll);
        missing.setName("character-sheet-unavailable");
        body.add(content, TABS_CARD); body.add(missing, UNAVAILABLE_CARD);
        JScrollPane page = ContentStyle.page(header, body, null); page.setName("character-sheet-scroll");
        page.getAccessibleContext().setAccessibleName("Character sheet; scroll for tabs and actions at large text sizes");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{back, death, restore, saveNotes}) control.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); }
        });
        death.addActionListener(e -> mark(true));
        restore.addActionListener(e -> mark(false));
        saveNotes.addActionListener(e -> {
            if (!ready() || record == null) return;
            context.journal().notes(record.key, notes.getText()); record.notes = notes.getText(); refresh();
        });
        // Provenance is diagnostic (spec §3.2): Simple mode shows neither the snapshot evidence nor the tab hint.
        context.mode().bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            seen.setVisible(analyst); hint.setVisible(analyst); revalidate(); repaint();
        });
        timer = new javax.swing.Timer(1000, e -> refresh());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            // The timer runs only while the sheet shows. Hiding it (another card, Back, another Characters tab, closing) keeps the draft.
            if (isShowing()) { timer.start(); refresh(); } else { timer.stop(); saveDraft(); }
        });
        fill();
    }

    @Override public void removeNotify() { saveDraft(); timer.stop(); super.removeNotify(); }

    /**
     * Shows one character. If the key differs, the previous character's changed notes are saved first. A non-null tab is
     * explicit navigation: it is shown if hidden, then selected. A key the journal does not hold shows the unavailable state.
     */
    public void open(String key, String tab) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Open the character sheet on the EDT");
        if (!Objects.equals(this.key, key)) saveDraft();
        this.key = key;
        reload(true);
        if (tab != null) { tabs.show(tab); tabs.select(tab); }
    }
    public String key() { return key; }
    /** True once the sheet shows the journal's read of its current key: that character, or its unavailable state. */
    public boolean ready() { return key != null && key.equals(loadedKey); }
    public String selectedTab() { return tabs.selectedId(); }
    public CustomizableTabs tabs() { return tabs; }
    /** Selects a tab without showing it: startup and saved-state restore keep a hidden tab hidden. */
    public void selectTab(String id) { tabs.select(id); }
    /** What the "‹ Characters" link and the unavailable state's button do; the Roster tab sets it on every open. */
    public void onBack(Runnable action) { backAction = Objects.requireNonNull(action); }
    public void bindNavigator(Navigator navigator) { deathPanel.bindNavigator(navigator); }
    /** Moves keyboard focus to the back link, the sheet's first control. */
    public void focusBackLink() { back.requestFocusInWindow(); }

    /**
     * Saves the shown character's changed notes to the journal: before another character opens, whenever the sheet hides
     * (another card, Back, the Characters page's other tabs) and when the workspace closes. A draft equal to the saved notes,
     * or one typed for another character, saves nothing. EDT.
     */
    public void saveDraft() {
        if (filledKey == null || record == null || !filledKey.equals(record.key) || Objects.equals(record.notes, notes.getText())) return;
        context.journal().notes(filledKey, notes.getText()); record.notes = notes.getText();
    }

    /** Replaces a slot tab's content (overview, gear, exalts); its id, title, order and hidden state are unchanged. */
    void setTab(String id, JComponent content) {
        JPanel slot = slots.get(id);
        if (slot == null) throw new IllegalArgumentException("Not a replaceable sheet tab: " + id);
        slot.removeAll(); slot.add(content, BorderLayout.CENTER); slot.revalidate(); slot.repaint();
    }
    /** The moved stat-maxing table (Task 5 keeps it in Analyst). */
    JComponent statTable() { return statTable; }
    /** The moved 28-slot equipment table (Task 6 keeps it in Analyst). */
    CharacterEquipmentPanel equipmentPanel() { return equipment; }
    /** The moved class-exalts table (Task 7 replaces it). */
    JComponent exaltTable() { return exaltTable; }

    /** Re-reads the journal when its revision or the definitions moved, then advances the snapshot age. EDT; skipped while hidden. */
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        if (key != null && (isShowing() || !isDisplayable())) reload(false);
    }

    private JPanel slot(String id, JComponent content) {
        JPanel slot = new JPanel(new BorderLayout()); slot.setName("character-tab-" + id);
        slot.add(content, BorderLayout.CENTER); slots.put(id, slot);
        return slot;
    }

    /** Reads the journal (deep copies) when forced or when its revision or the definitions moved; the shown state refreshes always. */
    private void reload(boolean force) {
        RosterDefinitions next = context.definitions().get();
        CharacterJournal journal = context.journal();
        CharacterRecord read = null;
        List<CharacterRecord> all = null;
        List<AccountRecord> known = null;
        long at;
        synchronized (journal) {
            at = journal.revision();
            if (force || at != revision || next != definitions) {
                read = key == null ? null : journal.characterCopy(key); all = journal.characters(); known = journal.accounts();
            }
        }
        if (all != null) loaded(key, read, all, known, next, at); else shown();
    }

    /**
     * Shows one journal read made for {@code forKey}; a read for any other key is ignored. The tables, notes and actions refill
     * only when the character, the journal revision or the definitions changed. The Death annotation tab follows the dead flag.
     */
    void loaded(String forKey, CharacterRecord read, List<CharacterRecord> all, List<AccountRecord> known, RosterDefinitions defs, long at) {
        if (!Objects.equals(forKey, key)) return;
        boolean changed = !Objects.equals(forKey, loadedKey) || at != revision || defs != definitions;
        record = read; records = all; accounts = known; definitions = defs; revision = at; loadedKey = forKey;
        if (changed) fill();
        tabs.refreshConditions();
        shown();
    }

    /** On every refresh: the snapshot age (time advances after capture stops), the storage warning, the death panel and Goals. */
    private void shown() {
        refreshTimeEvidence();
        String problem = context.journal().storageProblem();
        storage.setText(problem == null ? "" : problem);
        storage.setVisible(problem != null);
        deathPanel.showRecord(record);
        planning.refresh(records, accounts, definitions);
    }

    /** Mark dead or Restore alive, then wait for the re-read that shows the new state: a second click never acts on the old one. */
    private void mark(boolean dead) {
        if (!ready() || record == null) return;
        context.journal().markDead(record.key, dead);
        loadedKey = null;
        actions();
        refresh();
    }

    /** Mark dead or Restore alive (whichever applies) and Save notes act only on the character this sheet has loaded. */
    private void actions() {
        boolean dead = record != null && record.dead, acts = ready() && record != null;
        death.setVisible(!dead); restore.setVisible(dead);
        death.setEnabled(acts); restore.setEnabled(acts); saveNotes.setEnabled(acts); notes.setEnabled(acts);
    }

    private void fill() {
        CharacterRecord r = record;
        statModel.setRowCount(0); exaltModel.setRowCount(0); metadataModel.setRowCount(0);
        equipment.showRecord(r, definitions);
        actions();
        cards.show(body, ready() && r == null ? UNAVAILABLE_CARD : TABS_CARD);
        // A refresh never replaces an unsaved draft; only a different character does.
        if (r == null) { notes.setText(""); filledKey = null; }
        else if (!r.key.equals(filledKey)) { notes.setText(r.notes); filledKey = r.key; }
        if (r == null) {
            title.setText(key == null ? "Select a character" : "Character unavailable"); sprite.setIcon(null);
            seen.setToolTipText(null);
            return;
        }
        title.setText((r.name == null || r.name.isEmpty() ? "" : r.name + " · ") + className(r.classId) + " #" + r.characterId
            + " · " + (r.level == null ? "Level unknown" : "Level " + r.level) + (r.dead ? " • Marked dead manually" : ""));
        sprite.setIcon(Sprites.sprite(r.skin == null || r.skin == 0 ? r.classId : r.skin, 28));
        sprite.getAccessibleContext().setAccessibleName(className(r.classId));
        restore.setText(r.observedAgainAt > 0 ? "Observed again—restore?" : "Restore alive");
        seen.setToolTipText(r.source);
        String[] fields = {"class", "level", "skin", "fame", "seasonal", "created"};
        Object[] values = {r.className, r.level, r.skin, r.fame, r.seasonal == null ? null : r.seasonal ? "Seasonal" : "Regular", r.created};
        for (int i = 0; i < fields.length; i++) metadataModel.addRow(new Object[]{fields[i], unknown(values[i]), evidence(r, fields[i], values[i] != null)});
        for (int i = 0; i < 8; i++) {
            Integer cap = definitions.cap(r.classId, i);
            statModel.addRow(new Object[]{CharacterJournal.STATS[i], unknown(r.stats[i]), unknown(cap),
                cap == null || r.stats[i] == null ? "Unknown" : CharacterJournal.potions(r.stats[i], cap, i), evidence(r, "stat." + i, r.stats[i] != null)});
        }
        int[] exalt = null;
        for (AccountRecord a : accounts) if (a.key.equals(r.account)) exalt = a.exalts.get(r.classId);
        for (int i = 0; i < 8; i++) {
            Integer count = exalt == null ? null : exalt[CharacterJournal.EXALT_ORDER[i]];
            exaltModel.addRow(new Object[]{CharacterJournal.STATS[i], count == null ? "Unknown" : CharacterJournal.exaltLevel(count) + "/5", unknown(count), count == null ? "Unknown" : next(count)});
        }
    }

    /** Time advances even after capture stops; refresh just this text, not tables or editable drafts. */
    private void refreshTimeEvidence() {
        CharacterRecord r = record;
        String text = " ";
        if (r != null) {
            long age = r.lastSeen <= 0 ? -1 : Math.max(0, (context.clock().getAsLong() - r.lastSeen) / 1000);
            text = "Last observed alive " + date(r.lastObservedAlive) + "  •  Roster received " + date(r.rosterReceivedAt)
                + "\nSnapshot update age: " + (age < 0 ? "Unknown" : age + "s") + " · "
                + Arrays.stream(r.stats).filter(Objects::nonNull).count() + "/8 known stats · "
                + Arrays.stream(r.equipment).filter(Objects::nonNull).count() + "/28 known slots (may be retained)"
                + (r.dead ? "\nMarked dead manually " + date(r.diedAt) + "; preserved snapshot."
                    + (r.observedAgainAt > 0 ? " Reported again " + date(r.observedAgainAt) + ". Restore explicitly to accept updates." : "") : "");
        }
        if (!seen.getText().equals(text)) seen.setText(text);
    }

    private static String evidence(CharacterRecord r, String key, boolean known) {
        if (!known) return "Not captured";
        FieldCapture field = r.fields.get(key);
        if (field == null) return "Legacy / provenance unknown";
        return field.source + " · " + date(field.at) + (field.at > 0 && field.at < r.lastSeen ? " · Retained from earlier observation" : "");
    }
    private static String next(int count) { for (int goal : new int[]{5, 15, 30, 50, 75}) if (count < goal) return (goal - count) + " to " + goal; return "Complete"; }
    private static Object unknown(Object value) { return value == null ? "Unknown" : value; }
    private static String className(int id) { String name = CharacterClass.getName(id); return name == null ? "Class " + id : name; }
    private static String date(long time) { return time <= 0 ? "Unknown" : Formatters.formatTimestamp(time); }
    private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int col) { return false; }
        @Override public Class<?> getColumnClass(int col) { for (int i = 0; i < getRowCount(); i++) { Object v = getValueAt(i, col); if (v != null) return v instanceof Number ? v.getClass() : String.class; } return String.class; }
    }; }
    private static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model); ContentStyle.table(t, ContentStyle.Density.DENSE);
        t.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                int row = Math.max(0, t.getSelectedRow()), column = Math.max(0, t.getSelectedColumn());
                ContentStyle.reveal(t, t.getCellRect(row, column, true));
            }
        });
        t.getTableHeader().setReorderingAllowed(false); return t;
    }
}
```

- [ ] **Step 6: Retire the side pane in `CharacterJournalGUI`**

Make these edits in `src/main/java/tomato/gui/character/CharacterJournalGUI.java`:

1. Remove the three imports the side pane used: line 4 `import assets.ImageBuffer;`, line 14 `import tomato.backend.data.FieldCapture;` and line 26 `import tomato.gui.kit.CustomizableTabs;`.
2. Replace line 29:
   ```java
   /** Searchable persistent roster, with explicit unknowns and reversible life-state annotations. */
   ```
   with:
   ```java
   /** Searchable persistent roster with explicit unknowns. Enter or a double-click opens a character's sheet (CharacterRosterView). */
   ```
3. Replace lines 47–50:
   ```java
       private final JLabel heading = new JLabel("Select a character");
       private final JTextArea summary = ContentStyle.wrappingText(""), status = ContentStyle.wrappingText(""), seen = ContentStyle.wrappingText(" ");
       private final JButton death = new JButton("Mark dead"), saveNotes = new JButton("Save notes");
       private final JTextArea notes = new JTextArea(3, 30);
   ```
   with:
   ```java
       private final JTextArea summary = ContentStyle.wrappingText(""), status = ContentStyle.wrappingText("");
   ```
4. Replace lines 56–62 (from `    private final DefaultTableModel statModel = model(` through `    private final DefaultTableModel charExaltModel = model("Stat", "Level", "Completions", "Next tier");`) with:
   ```java
       private final DefaultTableModel exaltModel = model("Account", "Class", "Stat", "Level", "Completions", "Next tier", "Observed");
   ```
5. Replace lines 76–79:
   ```java
       private final CustomizableTabs detailTabs = new CustomizableTabs("character-detail");
       private final JTabbedPane tabs = detailTabs.component();
       /** Default tab order. The saved "tab" value is an index into it, so saved views stay valid when tabs move or hide. */
       private static final String[] DETAIL_TABS = {"stats", "equipment", "exalts", "notes", "evidence", "goals", "death"};
   ```
   with:
   ```java
       /**
        * Sheet tab ids by the saved "tab" index. Index i names the sheet tab that replaced the old detail pane's tab i
        * (stats, equipment, exalts, notes, evidence, goals, death), so views saved before the sheet keep their tab.
        */
       static final String[] SHEET_TABS = {"overview", "gear", "exalts", "notes", "evidence", "goals", "death"};
       private static final java.util.regex.Pattern TAB_ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");
       /** The sheet tab selected when the sheet opens without an explicit tab: restored at startup, then the last one chosen. */
       private String sheetTab;
       private java.util.function.Consumer<String> openSheet = key -> { };
       private final List<Runnable> rowsListeners = new ArrayList<>();
   ```
6. Replace lines 95–101:
   ```java
       CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock, java.util.function.Supplier<RosterDefinitions> definitionsSource) {
           this(journal, clock, definitionsSource, tomato.planning.PlanningStore.shared());
       }
       CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock, java.util.function.Supplier<RosterDefinitions> definitionsSource, tomato.planning.PlanningStore plans) {
           super(new BorderLayout(0, 8)); this.journal = journal; this.clock = Objects.requireNonNull(clock); this.definitionsSource = definitionsSource;
           planningPanel = new CharacterPlanningPanel(plans);
           deathPanel = new CharacterDeathPanel(journal);
   ```
   with:
   ```java
       CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock, java.util.function.Supplier<RosterDefinitions> definitionsSource) {
           super(new BorderLayout(0, 8)); this.journal = journal; this.clock = Objects.requireNonNull(clock); this.definitionsSource = definitionsSource;
   ```
7. Replace lines 104–107:
   ```java
           summary.setName("character-summary");
           seen.setName("character-snapshot-evidence");
           ContentStyle.font(summary, ContentStyle.body()); top.add(summary, BorderLayout.NORTH);
           seen.setFont(ContentStyle.metadata(ContentStyle.body())); status.setFont(ContentStyle.metadata(ContentStyle.body()));
   ```
   with:
   ```java
           summary.setName("character-summary");
           ContentStyle.font(summary, ContentStyle.body()); top.add(summary, BorderLayout.NORTH);
           status.setFont(ContentStyle.metadata(ContentStyle.body()));
   ```
8. Replace the side-pane block. It runs from line 155, `        JPanel detail = new JPanel(new BorderLayout(0, 8)) {`, through line 220, `        add(page, BorderLayout.CENTER);`: 66 lines covering the detail panel, its title, its seven tabs, the hint and the `character-roster-detail-split` split pane. Replace it with:
   ```java
           roster.getAccessibleContext().setAccessibleDescription("Enter or double-click opens the character's sheet");
           roster.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-character");
           roster.getActionMap().put("open-character", new AbstractAction() {
               @Override public void actionPerformed(java.awt.event.ActionEvent e) { openRow(roster.getSelectedRow()); }
           });
           roster.addMouseListener(new java.awt.event.MouseAdapter() {
               @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                   if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) openRow(roster.rowAtPoint(e.getPoint()));
               }
           });
           // A user sort reorders the visible rows; filter() reports its own rebuild once.
           roster.getRowSorter().addRowSorterListener(e -> { if (e.getType() == RowSorterEvent.Type.SORTED && !refreshing) rowsChanged(); });
           JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(status, BorderLayout.NORTH); footer.add(stateHost, BorderLayout.CENTER); stateHost.setVisible(false);
           JScrollPane page = ContentStyle.page(top, ContentStyle.tableScroll(roster, 3), footer); pageScroll = page;
           page.setName("character-page-scroll");
           page.getAccessibleContext().setAccessibleName("Characters; scroll for the roster and its actions at large text sizes");
           add(page, BorderLayout.CENTER);
   ```
9. Replace line 221:
   ```java
           for (JComponent control : new JComponent[]{search, life, season, death, saveNotes, accountFilter, classFilter, needsLife, missing, maxedFilter, minMaxed, maxMaxed, ageFilter, ageHours, reset}) {
   ```
   with:
   ```java
           for (JComponent control : new JComponent[]{search, life, season, accountFilter, classFilter, needsLife, missing, maxedFilter, minMaxed, maxMaxed, ageFilter, ageHours, reset}) {
   ```
10. Delete lines 244–245. The sheet header now owns both actions:
   ```java
           death.addActionListener(e -> { CharacterRecord r = selected(); if (r != null) { journal.markDead(r.key, !r.dead); refresh(); } });
           saveNotes.addActionListener(e -> { if (selectedKey != null) { journal.notes(selectedKey, notes.getText()); refresh(); } });
   ```
11. Replace lines 255–256:
   ```java
       public void bindNavigator(tomato.gui.route.Navigator navigator) { deathPanel.bindNavigator(navigator); }
       public void openGoals() { detailTabs.show("goals"); detailTabs.select("goals"); tabs.requestFocusInWindow(); }
   ```
   with:
   ```java
       /** Enter or a double-click on a row passes that character's journal key here; the Roster tab opens its sheet. */
       public void onOpenSheet(java.util.function.Consumer<String> open) { openSheet = Objects.requireNonNull(open); }
       /** The rows the search and filters keep, in the table's current sort order (the gallery shows exactly these). EDT only. */
       public List<CharacterRosterQuery.Row> visibleRows() {
           List<CharacterRosterQuery.Row> rows = new ArrayList<>();
           for (int view = 0; view < roster.getRowCount(); view++) rows.add(projected.get(filtered.get(roster.convertRowIndexToModel(view)).key));
           return rows;
       }
       /** Runs after the visible rows or their order change: filters, search, a journal change or a new sort. EDT only. */
       public void addRowsListener(Runnable listener) { rowsListeners.add(Objects.requireNonNull(listener)); }
       private void rowsChanged() { for (Runnable listener : new ArrayList<>(rowsListeners)) listener.run(); }
       private void openRow(int view) { if (view >= 0 && view < roster.getRowCount()) openSheet.accept(filtered.get(roster.convertRowIndexToModel(view)).key); }
       /** The selected row's character, or null. */
       String selectedKey() { return selectedKey; }
       /** The sheet tab to select when the sheet opens without an explicit tab; null when none was saved or chosen. */
       String sheetTab() { return sheetTab; }
       /** The sheet's settled tab changed; remember it with the list's view state. */
       void sheetTabSelected(String id) { if (id != null && !id.equals(sheetTab)) { sheetTab = id; rememberViewState(); } }
       void focusRoster() { roster.requestFocusInWindow(); }
   ```
12. In `refresh()`, delete lines 278–279:
   ```java
           if (isShowing() || detached) { refreshTimeEvidence(selected()); deathPanel.showRecord(selected()); }
           if (isShowing() || detached) planningPanel.refresh(records, accounts, definitions);
   ```
13. At the end of `filter()`, replace lines 338–339:
   ```java
           refreshing = false; select(false);
           rememberViewState();
   ```
   with:
   ```java
           refreshing = false; select(false);
           rememberViewState();
           rowsChanged();
   ```
14. Replace `select(boolean)`, lines 342–378 (37 lines, from `    private void select(boolean explicitSelection) {` through the closing `    }` after `        rememberViewState();`), with:
   ```java
       private void select(boolean explicitSelection) {
           CharacterRecord r = selected(); String newKey = r == null ? null : r.key;
           if (explicitSelection && r != null) pendingSelectionKey = null;
           selectedKey = newKey;
           if (Objects.equals(selectedKey, pendingSelectionKey)) pendingSelectionKey = null;
           if (r != null) rememberViewState();
       }
   ```
15. In `bindViewState`, delete line 384:
   ```java
           tabs.addChangeListener(e -> { if (!detailTabs.isRebuilding()) rememberViewState(); });
   ```
16. In `captureViewState`, replace line 399:
   ```java
           values.put("tab", Integer.toString(detailTabIndex())); values.put("pageY", Integer.toString(pageScroll.getViewport().getViewPosition().y));
   ```
   with:
   ```java
           // "tab" keeps its pre-sheet meaning (an index) for the tabs that existed then; "sheetTab" names any tab, including later ones.
           int tab = Arrays.asList(SHEET_TABS).indexOf(sheetTab);
           if (tab >= 0) values.put("tab", Integer.toString(tab));
           values.put("sheetTab", Objects.toString(sheetTab, "")); values.put("pageY", Integer.toString(pageScroll.getViewport().getViewPosition().y));
   ```
17. In `prepareViewState`:
   - Replace line 410:
     ```java
             int hours = RosterViewState.number(values, "hours", (Integer)ageHours.getValue(), 0, 1000000), tab = RosterViewState.number(values, "tab", detailTabIndex(), 0, DETAIL_TABS.length - 1);
     ```
     with:
     ```java
             int hours = RosterViewState.number(values, "hours", (Integer)ageHours.getValue(), 0, 1000000), tab = RosterViewState.number(values, "tab", 0, 0, SHEET_TABS.length - 1);
     ```
   - After line 416 (`        if (!selected.isEmpty() && !selected.matches("[0-9a-f]{64}:[0-9]+")) throw new IllegalArgumentException("Invalid character reference");`), insert:
     ```java
             String savedSheetTab = values.getOrDefault("sheetTab", "");
             if (!savedSheetTab.isEmpty() && !TAB_ID.matcher(savedSheetTab).matches()) throw new IllegalArgumentException("Invalid sheet tab");
     ```
   - Replace line 426:
     ```java
                     if(values.containsKey("tab"))detailTabs.select(DETAIL_TABS[tab]); // A hidden tab stays hidden at startup.
     ```
     with:
     ```java
                     // Remembered only: the sheet selects (never shows) it when it next opens without an explicit tab, so a hidden tab stays hidden.
                     if (!savedSheetTab.isEmpty()) sheetTab = savedSheetTab; else if (values.containsKey("tab")) sheetTab = SHEET_TABS[tab];
     ```
18. Delete lines 434–435:
   ```java
       /** The selected detail tab as an index in the default order (0 while a rebuild has nothing selected). */
       private int detailTabIndex() { return Math.max(0, Arrays.asList(DETAIL_TABS).indexOf(detailTabs.selectedId())); }
   ```
19. Delete lines 440–451, the time-evidence method, which now lives in `CharacterSheet`:
   ```java
       /** Time advances even after capture stops; refresh just this text, not selection or editable drafts. */
       private void refreshTimeEvidence(CharacterRecord r) {
           if (r == null) return;
           long age = r.lastSeen <= 0 ? -1 : Math.max(0, (clock.getAsLong() - r.lastSeen) / 1000);
           String text = "Last observed alive " + date(r.lastObservedAlive) + "  •  Roster received " + date(r.rosterReceivedAt)
               + "\nSnapshot update age: " + (age < 0 ? "Unknown" : age + "s") + " · "
               + Arrays.stream(r.stats).filter(Objects::nonNull).count() + "/8 known stats · "
               + Arrays.stream(r.equipment).filter(Objects::nonNull).count() + "/28 known slots (may be retained)"
               + (r.dead ? "\nMarked dead manually " + date(r.diedAt) + "; preserved snapshot."
                   + (r.observedAgainAt > 0 ? " Reported again " + date(r.observedAgainAt) + ". Restore explicitly to accept updates." : "") : "");
           if (!seen.getText().equals(text)) seen.setText(text);
       }
   ```
20. Delete lines 503–508 and line 510. `next`, `className`, `itemName`, `date`, `dateRenderer`, `model`, `table`, `reveal` and `note` stay, because the list and the account Exalts page use them.
   ```java
       private static String evidence(CharacterRecord r, String key, boolean known) {
           if (!known) return "Not captured";
           FieldCapture field = r.fields.get(key);
           if (field == null) return "Legacy / provenance unknown";
           return field.source + " · " + date(field.at) + (field.at > 0 && field.at < r.lastSeen ? " · Retained from earlier observation" : "");
       }
   ```
   ```java
       private static Object unknown(Object value) { return value == null ? "Unknown" : value; }
   ```

- [ ] **Step 7: Create `CharacterRosterView` and `CharactersRouteTarget`**

`src/main/java/tomato/gui/character/CharacterRosterView.java`:
```java
package tomato.gui.character;

import java.awt.CardLayout;
import java.util.Objects;
import javax.swing.JPanel;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;

/**
 * The Characters page's Roster tab: the character list or one character's sheet (CardLayout cards "list" and "sheet").
 * Enter or a double-click in the list opens the sheet through the navigator, so Back and the sheet's "‹ Characters" link
 * return to the list as it was. EDT only.
 */
public final class CharacterRosterView extends JPanel {
    public static final String LIST = "list", SHEET = "sheet";
    private final CardLayout cards = new CardLayout();
    private final CharacterJournalGUI list;
    private final CharacterSheet sheet;
    private Navigator navigator;
    private Runnable reveal = () -> { };
    private boolean sheetShowing;

    public CharacterRosterView(CharacterJournalGUI list, CharacterSheet sheet) {
        this.list = Objects.requireNonNull(list, "list"); this.sheet = Objects.requireNonNull(sheet, "sheet");
        setLayout(cards); setName("character-roster-view");
        add(list, LIST); add(sheet, SHEET);
        list.onOpenSheet(this::openCharacter);
        // The sheet's settled tab is saved with the list's view state and selected the next time the sheet opens.
        sheet.tabs().onSelect(id -> { if (id != null && sheet.key() != null) list.sheetTabSelected(id); });
        cards.show(this, LIST);
    }

    public void bindNavigator(Navigator navigator) { this.navigator = navigator; }
    Navigator navigator() { return navigator != null ? navigator : Navigator.current(); }
    /** Brings the Roster tab forward on the Characters page; routes use it because they are explicit navigation. */
    void onReveal(Runnable action) { reveal = Objects.requireNonNull(action); }
    void reveal() { reveal.run(); }

    /**
     * Shows one character's sheet.
     * - A null tab selects the remembered tab without showing it.
     * - A tab id is explicit navigation.
     * - {@code back} is what the sheet's "‹ Characters" link does.
     */
    public void showSheet(String key, String tab, Runnable back) {
        sheet.onBack(Objects.requireNonNull(back, "back"));
        sheet.open(key, tab);
        if (tab == null && list.sheetTab() != null) sheet.selectTab(list.sheetTab());
        sheetShowing = true; cards.show(this, SHEET);
    }
    /** Shows the list, refreshed with changes made on the sheet (Mark dead, notes); Back and "‹ Characters" keep a notes draft. */
    public void showList() {
        boolean fromSheet = sheetShowing;
        if (fromSheet) sheet.saveDraft();
        sheetShowing = false; cards.show(this, LIST);
        list.refresh();
        if (fromSheet) list.focusRoster();
    }
    public boolean showingSheet() { return sheetShowing; }
    public CharacterJournalGUI listPanel() { return list; }
    public CharacterSheet sheet() { return sheet; }
    CharactersRouteTarget.CharactersState state() { return new CharactersRouteTarget.CharactersState(sheetShowing, sheet.key(), sheet.selectedTab()); }
    /** The sheet's character while it shows, else the list's selected character; null when neither. */
    String currentKey() { return sheetShowing && sheet.key() != null ? sheet.key() : list.selectedKey(); }

    /** Opens a character from the list (and, in Task 9, the gallery) through the navigator; without one, switches in place. */
    void openCharacter(String key) {
        boolean routed = SheetFocus.validKey(key) && navigator().open(Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, null)));
        if (!routed) showSheet(key, null, this::showList);
        sheet.focusBackLink();
    }
}
```

`src/main/java/tomato/gui/character/CharactersRouteTarget.java`:
```java
package tomato.gui.character;

import java.util.List;
import java.util.Objects;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * Routes into the Characters Roster tab:
 * - {@link Destination#CHARACTERS} without a payload shows the character list.
 * - {@link Destination#CHARACTER_SHEET} with a {@link SheetFocus} shows that character's sheet; its tab, when named, is shown and selected.
 * Both targets share one view and capture {@link CharactersState}, so Back from a sheet returns to the list with its filters,
 * selection and scroll. EDT only.
 */
public final class CharactersRouteTarget implements RouteTarget {
    /** Detached Back state: whether the sheet was showing, and its character and tab. */
    public record CharactersState(boolean sheet, String key, String tab) { }

    /** The Back entry the next open pushes, and the Characters view it returns to, as last captured. */
    private static final class Origin { long token; CharactersState state; }

    private final Destination destination;
    private final CharacterRosterView view;
    private final Origin origin;

    private CharactersRouteTarget(Destination destination, CharacterRosterView view, Origin origin) {
        this.destination = destination; this.view = Objects.requireNonNull(view, "view"); this.origin = origin;
    }

    /** The list target and the sheet target over one Roster view; a RouteTarget has one destination, so register both. */
    public static List<RouteTarget> of(CharacterRosterView view) {
        Origin origin = new Origin();
        return List.of(new CharactersRouteTarget(Destination.CHARACTERS, view, origin), new CharactersRouteTarget(Destination.CHARACTER_SHEET, view, origin));
    }

    @Override public Destination destination() { return destination; }
    @Override public boolean accepts(Route route) {
        if (route.destination != destination || route.query != null || route.visit != null || route.record != null
            || route.recordingId != null || route.localObjectId != null || route.from != null || route.until != null) return false;
        return destination == Destination.CHARACTERS ? route.payload == null : route.payload instanceof SheetFocus;
    }
    @Override public Object captureState() {
        CharactersState state = view.state();
        origin.state = state; origin.token = view.navigator().nextBackToken();
        return state;
    }
    @Override public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Unsupported Characters route: " + route);
        long entry = view.navigator().nextBackToken();
        // Spec §6.2: the link leads back to the list. It pops this open's Back entry only when that entry returns to the list.
        boolean fromList = entry != 0 && origin.token == entry && origin.state != null && !origin.state.sheet();
        origin.token = 0; origin.state = null;
        view.reveal();
        if (destination == Destination.CHARACTERS) { view.showList(); return; }
        SheetFocus focus = (SheetFocus) route.payload;
        view.showSheet(focus.key(), focus.tab(), () -> {
            Navigator navigator = view.navigator();
            if (fromList && navigator.backToken() == entry) navigator.back(); else view.showList();
        });
    }
    @Override public void restoreState(Object state) {
        if (!(state instanceof CharactersState)) throw new IllegalArgumentException("Not a Characters view state");
        CharactersState saved = (CharactersState) state;
        if (saved.sheet() && saved.key() != null) view.showSheet(saved.key(), saved.tab(), view::showList);
        else view.showList();
    }
}
```

- [ ] **Step 8: Rebuild `CharacterPanelGUI` and wire `TomatoGUI`**

Replace `src/main/java/tomato/gui/character/CharacterPanelGUI.java` with:
```java
package tomato.gui.character;

import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JPanel;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/** Characters (shell page 3): the Roster tab (the character list or one character's sheet), Exalts and Pets. */
public class CharacterPanelGUI extends JPanel {
    private final CharacterJournal characters;
    private final CharacterJournalGUI journal;
    private final CharacterSheet sheet;
    private final CharacterRosterView roster;
    private final List<RouteTarget> routeTargets;
    private final CustomizableTabs tabs = new CustomizableTabs("characters");

    public CharacterPanelGUI(TomatoData data) {
        this(data, new SheetContext(data, data.characterJournal(), RosterDefinitions::current, DisplayModeModel.application()));
    }

    public CharacterPanelGUI(TomatoData data, SheetContext context) {
        setLayout(new BorderLayout());
        characters = context.journal();
        journal = new CharacterJournalGUI(context.journal(), context.clock(), context.definitions());
        journal.bindViewState(ViewStateStore.application());
        sheet = new CharacterSheet(context);
        roster = new CharacterRosterView(journal, sheet);
        routeTargets = CharactersRouteTarget.of(roster); // built once: both targets share one Back origin
        // Routes are explicit navigation, so they may bring the Roster tab forward even when it is hidden.
        roster.onReveal(() -> { tabs.show("roster"); tabs.select("roster"); });
        tabs.add("roster", "Roster", roster).add("exalts", "Exalts", journal.exaltPanel()).add("pets", "Pets", new CharacterPetsGUI(data));
        // Another Characters tab refreshes the list and keeps the sheet's notes draft.
        tabs.component().addChangeListener(e -> { journal.refresh(); sheet.saveDraft(); });
        add(tabs.component(), BorderLayout.CENTER);
    }

    public void bindNavigator(Navigator navigator) { roster.bindNavigator(navigator); sheet.bindNavigator(navigator); }
    /** The CHARACTERS and CHARACTER_SHEET targets; TomatoGUI registers both with the shell navigator. */
    public List<RouteTarget> routeTargets() { return routeTargets; }
    public CharacterRosterView roster() { return roster; }
    public CharacterSheet sheet() { return sheet; }

    /**
     * Search's "Character and exalt goals": the Goals tab of the selected character's sheet, else the journal's most recent
     * character. It goes through the navigator so Back returns, and shows the list when the journal holds no character.
     */
    public void openGoals() {
        String key = roster.currentKey();
        if (key == null) { CharacterJournal.CharacterRecord recent = characters.mostRecentCharacter(); key = recent == null ? null : recent.key; }
        boolean sheetRoute = SheetFocus.validKey(key);
        Route route = sheetRoute ? Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, "goals")) : Route.to(Destination.CHARACTERS);
        if (roster.navigator().open(route)) return;
        roster.reveal();
        if (sheetRoute) roster.showSheet(key, "goals", roster::showList); else roster.showList();
    }
}
```

In `src/main/java/tomato/gui/TomatoGUI.java`:

1. Replace lines 139–140:
   ```java
           registerRetainedPage(Destination.CHARACTERS);
           registerRetainedPage(Destination.QUESTS);
   ```
   with:
   ```java
           // The Roster tab's list (CHARACTERS) and one character's sheet (CHARACTER_SHEET) share one view and one Back state.
           for (RouteTarget target : characterPanel.routeTargets()) navigator.register(target);
           registerRetainedPage(Destination.QUESTS);
   ```
2. Replace lines 458–459:
   ```java
           registerSearch("plans.characters", "Character and exalt goals", "maxing potions character goals equipment death", "Characters",
               "Characters/plans.json; death notes in Characters/journal.json", () -> { navigator.open(tomato.gui.route.Route.to(Destination.CHARACTERS)); characterPanel.openGoals(); });
   ```
   with:
   ```java
           registerSearch("plans.characters", "Character and exalt goals", "maxing potions character goals equipment death", "Characters",
               "Characters/plans.json; death notes in Characters/journal.json", () -> characterPanel.openGoals());
   ```
3. In `closeWorkspace`, replace `            if (home != null) home.close();` with:
   ```java
               if (home != null) home.close();
               if (characterPanel != null) characterPanel.sheet().saveDraft(); // a notes draft survives closing the workspace
   ```

- [ ] **Step 9: Migrate `CharacterTabsTest` (replace the file)**

Assertion changes:
- **Replace** because the sheet's tab group and ids replace the detail pane's: `ui.tabs.character-detail` → `ui.tabs.character`; `character-detail-tabs` → `character-tabs`; the saved order `goals,stats,equipment,…` → `goals,overview,gear,…`; the default order in test 2 uses the new ids; the first visible title "Stat maxing" → "Overview".
- **Replace** because the side pane's `openGoals()` is gone: `panel.openGoals()` → `sheet.open(key, "goals")`. The explicit-tab route is covered in `CharactersRouteTargetTest`.
- **Add beside**: "Restoring the list does not open the sheet". The tab is selected only when the sheet opens, through Enter.
- **Replace** the tab counts, 6 Simple and 7 Analyst, with 5 and 6: the fixture character is alive, so Death annotation is not offered (the sheet shows it only for a character marked dead).
- The evidence index 5, "stays hidden", the "first visible selected" message and "does not rewrite the hidden set" are kept.

```java
package tomato.gui.character;

import java.awt.*;
import java.util.Collections;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.kit.DisplayModeModel;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class CharacterTabsTest {
    private static final String ORDER = "ui.tabs.character";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;
    @Before public void remember() throws Exception { savedOrder = PropertiesManager.getProperty(ORDER); SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode()); }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    private static String seed(CharacterJournal journal) {
        String account = CharacterJournal.accountKey("tabs-fixture");
        RealmCharacter c = new RealmCharacter(); c.charId = 1; c.classNum = 782; c.receivedAt = 1000; c.supplied("class");
        journal.mergeRoster(account, Collections.singletonList(c));
        return account + ":1";
    }

    @Test public void snapshotEvidenceIsAnalystOnlyAndTabsKeepTheirSavedOrder() throws Exception {
        PropertiesManager.setProperties(ORDER, "goals,overview,gear,exalts,notes,evidence,death|");
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String key = seed(journal);
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            CharacterSheet sheet = RosterFixtures.sheet(journal, () -> 5000, RosterDefinitions::empty);
            JTabbedPane tabs = find(sheet, JTabbedPane.class, "character-tabs");
            assertEquals("Goals", tabs.getTitleAt(0)); assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertEquals(6, tabs.getTabCount()); assertEquals(5, tabs.indexOfTab("Snapshot evidence"));
            sheet.open(key, null); tabs.setSelectedIndex(tabs.indexOfTab("Notes")); sheet.open(key, "goals");
            assertEquals("Goals", tabs.getTitleAt(tabs.getSelectedIndex()));
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }

    /** Tab hiding persists (spec §12 P1 exit): restoring the saved view must not un-hide the last selected tab. */
    @Test public void savedViewKeepsAHiddenNotesTabHidden() throws Exception {
        tomato.gui.history.ArchiveNativeSupport.Memory memory = new tomato.gui.history.ArchiveNativeSupport.Memory();
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("tabs.json"));
        seed(journal);
        try {
            SwingUtilities.invokeAndWait(() -> {
                PropertiesManager.setProperties(ORDER, "");
                CharacterRosterView first = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                first.listPanel().bindViewState(memory.states);
                RosterFixtures.enter(first); first.sheet().tabs().select("notes"); first.listPanel().saveViewState();
                PropertiesManager.setProperties(ORDER, "overview,gear,exalts,goals,notes,evidence,death|notes");
                CharacterRosterView restored = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                restored.listPanel().bindViewState(memory.states);
                assertNull("Restoring the list does not open the sheet", restored.sheet().key());
                RosterFixtures.enter(restored);
                JTabbedPane shown = find(restored.sheet(), JTabbedPane.class, "character-tabs");
                assertEquals("The hidden Notes tab stays hidden", -1, shown.indexOfTab("Notes"));
                assertEquals("The first visible tab stays selected", "Overview", shown.getTitleAt(shown.getSelectedIndex()));
                assertEquals("Restoring does not rewrite the hidden set", "overview,gear,exalts,goals,notes,evidence,death|notes",
                    PropertiesManager.getProperty(ORDER));
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally { journal.close(); }
    }
}
```

- [ ] **Step 10: Migrate `CharacterJournalGuiTest` (replace the file)**

Assertion changes:
- **Replace** because Mark dead, Restore alive and Save notes moved to the sheet header and Notes tab (Mark dead and Restore alive are two buttons, one shown at a time, found by their text). Each action now opens the row with Enter, acts on the sheet, then returns with `showList()`, which refreshes the list. The roster values asserted are unchanged: "Marked dead manually", "Last observed alive" and the saved notes.
- **Replace** because the first `JTabbedPane` is no longer the detail pane: `find(panel, JTabbedPane.class)` with `setSelectedIndex(1|2)` becomes `sheet.tabs().select("gear"|"exalts")`.
- Every other assertion and screenshot is kept, with `frame.setContentPane(view)`.

```java
package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;
import tomato.backend.data.*;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.modern.VioletTheme;
import tomato.realmshark.RealmCharacter;

public class CharacterJournalGuiTest {
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) { if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type); if (found != null) return found; } }
        return null;
    }
    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) { if (c instanceof JButton && text.equals(((JButton)c).getText())) return (JButton)c;
            if (c instanceof Container) { JButton found = button((Container)c,text); if (found != null) return found; } } return null;
    }
    private static JTextArea notes(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextArea && "character-notes".equals(c.getName())) return (JTextArea)c;
            if (c instanceof Container) { JTextArea found = notes((Container)c); if (found != null) return found; }
        }
        return null;
    }
    private static void render(JFrame frame, String filename, int width) {
        frame.setSize(width, 780); frame.setVisible(true); frame.validate();
        BufferedImage img = new BufferedImage(width, 780, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); frame.paint(g); g.dispose();
        try { ImageIO.write(img,"png",new File(filename)); } catch (Exception e) { throw new RuntimeException(e); }
    }
    @Test public void rosterSearchSortDeathNotesAndResponsiveScreens() throws Exception {
        CharacterJournal j = new CharacterJournal(Files.createTempDirectory("character-ui-").resolve("journal.json"));
        tomato.backend.data.RosterDefinitions definitions = CharacterRosterQueryTest.definitions();
        String account = j.observe(CharacterJournalTest.player("sample-account",782), 101);
        ArrayList<RealmCharacter> chars = new ArrayList<>();
        int[] classes = {782,768,775,784,800,801};
        for (int i = 0; i < classes.length; i++) {
            RealmCharacter c = new RealmCharacter(); c.classNum = (short)classes[i]; c.charId = 101+i;
            c.level = 20; c.seasonal = i % 2 == 0; c.fame = i == 0 ? 900 : 10000 + i;
            for (String field : new String[]{"class", "level", "seasonal", "fame"}) c.supplied(field);
            c.receivedAt = System.currentTimeMillis();
            int[] cap = new int[]{670,385,75,25,50,75,40,60};
            c.hp = cap[0]; c.mp = cap[1]; c.atk = cap[2]; c.def = cap[3]; c.spd = cap[4]; c.dex = cap[5]; c.vit = cap[6]; c.wis = cap[7];
            if (i == 0) { c.hp -= 20; c.wis -= 10; }
            c.capturedStatMask = 255; c.equipment = new int[]{12345, -1, 9999, -1, -1}; chars.add(c);
        }
        j.mergeRoster(account, chars); j.markDead(account+":104",true);
        Map<Integer,int[]> ex = new HashMap<>(); ex.put(782,new int[]{75,50,30,15,5,1,0,74}); j.exalts(account,ex);
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            CharacterRosterView view = RosterFixtures.view(j, System::currentTimeMillis, () -> definitions);
            CharacterJournalGUI panel = view.listPanel(); CharacterSheet sheet = view.sheet();
            JTable roster = find(panel,JTable.class); assertEquals(6,roster.getRowCount());
            JTextField search = find(panel,JTextField.class);
            search.setText("["); assertEquals(0,roster.getRowCount());
            search.setText("12345"); assertEquals(6,roster.getRowCount()); search.setText("101"); assertEquals(1,roster.getRowCount());
            assertEquals(Integer.valueOf(6), roster.getValueAt(0,5));
            RosterFixtures.enter(view); button(sheet,"Mark dead").doClick(); view.showList(); assertEquals("Marked dead manually",roster.getValueAt(0,2));
            RosterFixtures.enter(view); button(sheet,"Restore alive").doClick(); view.showList(); assertEquals("Last observed alive",roster.getValueAt(0,2));
            RosterFixtures.enter(view); JTextArea notes = notes(sheet); notes.setText("Finish Life and Wisdom"); button(sheet,"Save notes").doClick();
            assertEquals("Finish Life and Wisdom",j.characters().stream().filter(r -> r.characterId == 101).findFirst().get().notes);
            view.showList(); search.setText(""); roster.getRowSorter().toggleSortOrder(6);
            assertEquals(900L, roster.getValueAt(0,6));
            JComboBox<?> filter = find(panel,JComboBox.class); filter.setSelectedItem("Marked dead manually"); assertEquals(1,roster.getRowCount());
            filter.setSelectedItem("Not marked dead"); assertEquals(5,roster.getRowCount()); filter.setSelectedItem("All characters");
            search.setText("");
            JFrame frame = new JFrame("Characters — sample data"); frame.setContentPane(view);
            try {
                render(frame,"characters-desktop.png",1080);
                render(frame,"characters-compact.png",680);
                RosterFixtures.enter(view);
                assertTrue(button(sheet,"Mark dead").getX() >= 0);
                sheet.tabs().select("gear"); render(frame,"characters-equipment.png",1080);
                sheet.tabs().select("exalts"); render(frame,"characters-class-exalts.png",1080);
                frame.setContentPane(panel.exaltPanel()); render(frame,"characters-account-exalts.png",1080);
            } finally { frame.dispose(); }
        });
        j.close();
    }
}
```

- [ ] **Step 11: Migrate `CharacterJournalFreshnessRefreshTest` (replace the file)**

Assertion changes:
- Test 1: **replace**. Notes, snapshot evidence and the tabs now come from the sheet opened with Enter. The selected tab is asserted by id (`"notes"`) instead of index 3, because Notes is index 4 in the sheet's default order. Both `panel.refresh()` and `sheet.refresh()` run. The model-, selection- and note-change counters now cover the list's tables and the sheet's tables. All zero-change, age, draft, caret and journal assertions are kept.
- Test 2: **replace**. The sheet follows the opened character, not the list selection, so "search selects #8" becomes "search, then Enter opens #8". "No matching character → `" "`" becomes "an unknown key → `" "`", because an empty list no longer blanks a sheet. All age texts are kept.

```java
package tomato.gui.character;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.gui.glance.character.CharacterSheet;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

/** Headless Swing state checks: no frames, rendering, native focus or sleeping timers. */
public class CharacterJournalFreshnessRefreshTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void unchangedJournalRefreshAdvancesAgeWithoutTouchingTablesSelectionOrDraft() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        String account = CharacterJournal.accountKey("age-fixture");
        journal.mergeRoster(account, Arrays.asList(character(7, 100_000), character(8, 90_000)));
        journal.notes(account + ":7", "Saved notes");
        AtomicLong clock = new AtomicLong(100_000);
        tomato.backend.data.RosterDefinitions definitions = tomato.backend.data.RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, clock::get, () -> definitions);
            CharacterJournalGUI panel = view.listPanel(); CharacterSheet sheet = view.sheet();
            JTable roster = named(panel, "character-roster", JTable.class);
            roster.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
            assertTrue(roster.getValueAt(roster.getSelectedRow(), 0).toString().endsWith("#7"));
            RosterFixtures.enter(view);
            assertEquals(account + ":7", sheet.key());
            JTextArea notes = named(sheet, "character-notes", JTextArea.class);
            JTextArea evidence = named(sheet, "character-snapshot-evidence", JTextArea.class);
            sheet.tabs().select("notes"); notes.setText("Unsaved draft remains editable"); notes.setCaretPosition(5);
            assertTrue(evidence.getText().contains("Snapshot update age: 0s"));
            int selected = roster.getSelectedRow(); long revision = journal.revision();
            List<JTable> tables = new ArrayList<>(); collectTables(view, tables);
            AtomicInteger modelChanges = new AtomicInteger(), selectionChanges = new AtomicInteger(), noteChanges = new AtomicInteger();
            for (JTable table : tables) table.getModel().addTableModelListener(e -> modelChanges.incrementAndGet());
            roster.getSelectionModel().addListSelectionListener(e -> selectionChanges.incrementAndGet());
            notes.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { noteChanges.incrementAndGet(); }
                public void removeUpdate(DocumentEvent e) { noteChanges.incrementAndGet(); }
                public void changedUpdate(DocumentEvent e) { noteChanges.incrementAndGet(); }
            });
            clock.set(105_000); panel.refresh(); sheet.refresh();
            assertTrue(evidence.getText(), evidence.getText().contains("Snapshot update age: 5s"));
            clock.set(160_000); panel.refresh(); sheet.refresh();
            assertTrue(evidence.getText(), evidence.getText().contains("Snapshot update age: 60s"));
            assertEquals(revision, journal.revision());
            assertEquals(0, modelChanges.get()); assertEquals(0, selectionChanges.get()); assertEquals(0, noteChanges.get());
            assertEquals(selected, roster.getSelectedRow()); assertEquals("notes", sheet.selectedTab());
            assertEquals("Unsaved draft remains editable", notes.getText()); assertEquals(5, notes.getCaretPosition());
            assertEquals("Saved notes", journal.characters().stream().filter(r -> r.characterId == 7).findFirst().get().notes);
        });
    }

    @Test public void ageRefreshRetainsUnknownAndEmptyStatesAndClampsBackwardClock() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("unknown.json"));
        String account = CharacterJournal.accountKey("unknown-age-fixture");
        journal.mergeRoster(account, Arrays.asList(character(7, 100_000), character(8, 0)));
        journal.notes(account + ":8", "unknown-timestamp-only");
        AtomicLong clock = new AtomicLong(120_000);
        tomato.backend.data.RosterDefinitions definitions = tomato.backend.data.RosterDefinitions.empty();
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, clock::get, () -> definitions);
            CharacterSheet sheet = view.sheet();
            RosterFixtures.enter(view);
            JTextArea evidence = named(sheet, "character-snapshot-evidence", JTextArea.class);
            assertTrue(evidence.getText().contains("Snapshot update age: 20s"));
            clock.set(90_000); sheet.refresh();
            assertTrue(evidence.getText().contains("Snapshot update age: 0s"));
            view.showList();
            JTextField search = named(view.listPanel(), "character-search", JTextField.class);
            search.setText("unknown-timestamp-only"); RosterFixtures.enter(view);
            assertEquals(account + ":8", sheet.key());
            assertTrue(evidence.getText().contains("Snapshot update age: Unknown"));
            clock.set(200_000); sheet.refresh();
            assertTrue(evidence.getText().contains("Snapshot update age: Unknown"));
            view.showSheet(account + ":404", null, view::showList);
            assertEquals(" ", evidence.getText());
        });
    }

    private static RealmCharacter character(int id, long at) {
        RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 1; c.classString = "Fixture";
        c.receivedAt = at; c.supplied("class", at, "Synthetic roster"); return c;
    }
    private static void collectTables(Container root, List<JTable> tables) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable) tables.add((JTable) child);
            if (child instanceof Container) collectTables((Container) child, tables);
        }
    }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 12: Migrate `CharacterViewStateTest`**

In `src/test/java/tomato/gui/character/CharacterViewStateTest.java`:

1. Add beside `import com.google.gson.JsonParser;`:
   ```java
   import com.google.gson.JsonObject;
   ```
2. In test 1, replace lines 42–45:
   ```java
               CharacterJournalGUI reopened = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); reopened.bindViewState(memory.store);
               JTable table = named(reopened, "character-roster", JTable.class);
               assertEquals(0, table.getSelectedRow()); assertTrue(table.getValueAt(0, 1).toString().contains(b.substring(0, 6)));
               assertEquals("needle", named(reopened, "character-notes", JTextArea.class).getText());
   ```
   with the following. This is a **replace**: the notes now live on the sheet, so the reopened B row is opened with Enter first. The selection assertions are kept.
   ```java
               CharacterRosterView reopenedRoster = RosterFixtures.view(journal, () -> 5000, () -> definitions);
               CharacterJournalGUI reopened = reopenedRoster.listPanel(); reopened.bindViewState(memory.store);
               JTable table = named(reopened, "character-roster", JTable.class);
               assertEquals(0, table.getSelectedRow()); assertTrue(table.getValueAt(0, 1).toString().contains(b.substring(0, 6)));
               RosterFixtures.enter(reopenedRoster);
               assertEquals("needle", named(reopenedRoster.sheet(), "character-notes", JTextArea.class).getText());
   ```
3. In test 2, replace line 62:
   ```java
               CharacterJournalGUI view = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); view.bindViewState(memory.store);
   ```
   with:
   ```java
               CharacterRosterView roster = RosterFixtures.view(journal, () -> 5000, () -> definitions);
               CharacterJournalGUI view = roster.listPanel(); view.bindViewState(memory.store);
   ```
4. Replace lines 72–74:
   ```java
               named(view, "character-detail-tabs", JTabbedPane.class).setSelectedIndex(3);
               named(view, "character-notes", JTextArea.class).setText("B unsaved draft"); view.saveViewState();
               CharacterJournalGUI reopened = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); reopened.bindViewState(memory.store);
   ```
   with the following. This is a **replace**: the tab is chosen on the sheet, by id. The saved `tab` value is added beside and must still be the old index 3 (Notes), so a rollback to P2 reads the same tab.
   ```java
               RosterFixtures.enter(roster); roster.sheet().tabs().select("notes");
               named(roster.sheet(), "character-notes", JTextArea.class).setText("B unsaved draft"); view.saveViewState();
               JsonObject values = savedValues(memory);
               assertEquals("The saved index keeps its pre-sheet meaning (3 = Notes)", "3", values.get("tab").getAsString());
               assertEquals("notes", values.get("sheetTab").getAsString());
               CharacterRosterView reopenedRoster = RosterFixtures.view(journal, () -> 5000, () -> definitions);
               CharacterJournalGUI reopened = reopenedRoster.listPanel(); reopened.bindViewState(memory.store);
   ```
5. Replace lines 81–83:
   ```java
               assertEquals(3, named(reopened, "character-detail-tabs", JTabbedPane.class).getSelectedIndex());
               assertEquals("Account B notes", named(reopened, "character-notes", JTextArea.class).getText());
               assertEquals("B unsaved draft", named(view, "character-notes", JTextArea.class).getText());
   ```
   with the following. **Replace**: index 3 on `character-detail-tabs` becomes the `notes` id on the reopened sheet. **Add beside**: the sheet stays closed until it is opened. The notes values are kept.
   ```java
               assertNull("Restoring the list leaves the sheet closed", reopenedRoster.sheet().key());
               RosterFixtures.enter(reopenedRoster);
               assertEquals("The restored tab is selected when the sheet opens", "notes", reopenedRoster.sheet().selectedTab());
               assertEquals("Account B notes", named(reopenedRoster.sheet(), "character-notes", JTextArea.class).getText());
               assertEquals("B unsaved draft", named(roster.sheet(), "character-notes", JTextArea.class).getText());
   ```
6. **Add beside**: insert after the `savedSelection` method (after line 52):
   ```java
       private static JsonObject savedValues(Memory memory) {
           return JsonParser.parseString(memory.values.get("ux.archive.characters-live-roster")).getAsJsonObject()
               .getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets").getAsJsonObject("values");
       }
       @Test public void aViewSavedBeforeTheSheetOpensTheSheetAtTheSameTab() throws Exception {
           CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("legacy.json"));
           RealmCharacter character = new RealmCharacter(); character.charId = 1; character.classNum = 782; character.receivedAt = 1000; character.supplied("class");
           journal.mergeRoster(CharacterJournal.accountKey("legacy-tab"), Collections.singletonList(character));
           Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
           SwingUtilities.invokeAndWait(() -> {
               CharacterJournalGUI first = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); first.bindViewState(memory.store); first.saveViewState();
               JsonObject document = JsonParser.parseString(memory.values.get("ux.archive.characters-live-roster")).getAsJsonObject();
               JsonObject values = document.getAsJsonObject("last").getAsJsonObject("query").getAsJsonObject("facets").getAsJsonObject("values");
               values.addProperty("tab", "5"); values.remove("sheetTab");
               memory.values.put("ux.archive.characters-live-roster", document.toString());
               CharacterRosterView roster = RosterFixtures.view(journal, () -> 5000, () -> definitions); roster.listPanel().bindViewState(memory.store);
               RosterFixtures.enter(roster);
               assertEquals("Index 5 was the side pane's Goals tab", "goals", roster.sheet().selectedTab());
           });
       }
   ```

- [ ] **Step 13: Migrate `CharacterJournalLayoutTest`**

In `src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`:

1. Add beside `import tomato.backend.data.*;`:
   ```java
   import tomato.gui.glance.character.CharacterSheet;
   ```
2. Add beside `    private CharacterJournalGUI panel;`:
   ```java
       private CharacterRosterView view;
       private CharacterSheet sheet;
   ```
3. In `setup()`, line 42, replace `"ui.tabs.character-detail"` with `"ui.tabs.character"`. This is a **replace**: the sheet's tab group replaced the detail group.
4. In `createShell`, add beside `        panel = find(pages[3], CharacterJournalGUI.class);`:
   ```java
           view = find(pages[3], CharacterRosterView.class); sheet = view.sheet();
           view.bindNavigator(tomato.gui.route.Navigator.NONE); // Enter switches cards in place even if another test left a navigator installed.
   ```
5. In `exactClientMatrixRetainsRowsAndScrollReachableControls`, replace lines 107–117, from `                    JSplitPane split = named(panel, "character-roster-detail-split", JSplitPane.class);` through `                    split.setDividerLocation(before);`, with the line below. This is a **replace**: the split is retired. The list's three-row floor is still asserted in `exerciseTabs`, and each sheet tab's floor is asserted per tab. The following `assertEquals("No huge-frame workaround", …)` line is kept.
   ```java
                       assertNull("The roster has no side-by-side detail pane", named(panel, "character-roster-detail-split", JSplitPane.class));
   ```
6. Replace the whole `exerciseTabs` method, lines 160–228 (69 lines, from `    private void exerciseTabs(boolean populated) throws Exception {` through its closing `    }`), with the version below. This is a **replace**: the detail assertions move from the list's tabs to the opened sheet's tabs.
   - The list keeps its assertions: row count, the long-name renderer, "Unknown" maxed, the timestamp, reachable rows, search and controls.
   - The sheet is opened with Enter.
   - The back link, "Mark dead" and "Save notes" reachability move to the sheet.
   - The tabs are addressed by id instead of index 0–3: overview, gear, exalts and notes. Their per-tab checks are kept: the wrap policy, tab bounds, the three-row floor, reachable first and last rows, Overview's values "Unknown" and 70, and the three note lines, their text and reachable end.
   - The empty-roster case asserts only the list, because there is no row to open.
   - **Add beside**: the back link returns to the list.
   ```java
       private void exerciseTabs(boolean populated) throws Exception {
           SwingUtilities.invokeAndWait(() -> view.showList());
           settle();
           SwingUtilities.invokeAndWait(() -> {
               JTable roster = named(panel, "character-roster", JTable.class);
               assertEquals(populated ? 20 : 0, roster.getRowCount());
               assertRows(roster);
               if (populated) {
                   assertTrue(roster.getValueAt(0, 1).toString().contains(LONG_NAME));
                   assertEquals(roster.getValueAt(0, 1), ((JLabel)roster.prepareRenderer(roster.getCellRenderer(0, 1), 0, 1)).getToolTipText());
                   assertEquals(Integer.class, roster.getColumnClass(5));
                   assertNull(roster.getValueAt(0, 5));
                   assertEquals("Unknown", ((JLabel)roster.prepareRenderer(roster.getCellRenderer(0, 5), 0, 5)).getText());
                   assertEquals(Formatters.formatTimestamp((Long)roster.getValueAt(0, 7)),
                           ((JLabel)roster.prepareRenderer(roster.getCellRenderer(0, 7), 0, 7)).getText());
                   roster.getRowSorter().toggleSortOrder(0);
                   roster.setRowSelectionInterval(0, 0);
                   reachableRow(roster, roster.getRowCount() - 1);
               }
               reachable(named(panel, "character-search", JTextField.class));
               assertControlsReachable(panel);
               assertWrappingTextFits(panel);
           });
           if (!populated) return; // No row to open: the empty roster shows its invitation instead of a sheet.
           SwingUtilities.invokeAndWait(() -> {
               named(panel, "character-roster", JTable.class).getActionMap().get("open-character").actionPerformed(null);
               assertTrue("Enter opens the selected character's sheet", view.showingSheet());
           });
           settle();
           SwingUtilities.invokeAndWait(() -> {
               reachable(named(sheet, "character-sheet-back", JButton.class));
               reachable(button(sheet, "Mark dead"));
               assertTrue(button(sheet, "Mark dead").isEnabled());
           });
           for (String id : new String[]{"overview", "gear", "exalts", "notes"}) {
               SwingUtilities.invokeAndWait(() -> sheet.tabs().select(id));
               settle();
               SwingUtilities.invokeAndWait(() -> {
                   JTabbedPane tabs = named(sheet, "character-tabs", JTabbedPane.class);
                   assertEquals(id, sheet.selectedTab());
                   int index = tabs.getSelectedIndex();
                   assertEquals("Sheet navigation must wrap instead of clipping a scrolling tab strip", JTabbedPane.WRAP_TAB_LAYOUT, tabs.getTabLayoutPolicy());
                   Rectangle tabBounds = tabs.getBoundsAt(index);
                   tabs.scrollRectToVisible(tabBounds);
                   assertTrue("Selected sheet tab must remain reachable: tab=" + id + ", bounds=" + tabBounds
                           + ", visible=" + tabs.getVisibleRect() + ", shell=" + shell.getSize() + ", font=" + tabs.getFont(),
                           tabs.getVisibleRect().contains(tabBounds));
                   for (Component child : tabs.getComponents()) if (child instanceof JViewport) {
                       JViewport strip = (JViewport)child;
                       Rectangle tabInStrip = SwingUtilities.convertRectangle(tabs, tabBounds, strip.getView());
                       assertTrue("The internal horizontal tab viewport must contain the whole selected tab: " + tabInStrip
                               + " within " + strip.getViewRect(), strip.getViewRect().contains(tabInStrip));
                   }
                   if (!"notes".equals(id)) {
                       JTable table = find((Container)tabs.getSelectedComponent(), JTable.class);
                       assertRows(table);
                       reachableRow(table, 0); reachableRow(table, table.getRowCount() - 1);
                       if ("overview".equals(id)) {
                           assertEquals("Unknown", table.getValueAt(0, 1));
                           assertEquals(70, table.getValueAt(2, 1));
                       }
                   } else {
                       JTextArea notes = named(sheet, "character-notes", JTextArea.class);
                       assertTrue("Three editable lines must survive sheet chrome", ((JViewport)notes.getParent()).getExtentSize().height
                               >= notes.getFontMetrics(notes.getFont()).getHeight() * 3);
                       assertEquals(LONG_NOTES, notes.getText());
                       try {
                           Rectangle lastLine = notes.modelToView(notes.getDocument().getLength());
                           revealRegion(notes, lastLine);
                           assertTrue("The end of long notes must be scroll reachable", notes.getVisibleRect().contains(lastLine));
                       } catch (BadLocationException e) { throw new AssertionError(e); }
                       reachable(button(sheet, "Save notes"));
                       assertTrue(button(sheet, "Save notes").isEnabled());
                   }
                   assertWrappingTextFits(sheet);
               });
           }
           SwingUtilities.invokeAndWait(() -> named(sheet, "character-sheet-back", JButton.class).doClick());
           settle();
           SwingUtilities.invokeAndWait(() -> assertFalse("The back link returns to the list", view.showingSheet()));
       }
   ```
7. Replace the whole `backgroundSnapshotRefreshPreservesDraftAndSelectionSavesIt` method, lines 230–265 (from its `@Test` line through its closing `    }`). This is a **replace**: the draft now lives on the sheet and is saved when the sheet hides (Back here) or another character opens, not when the selection changes.
   - Kept: the background capture and save off the EDT, "Journal work must not wait for EDT", the preserved draft and selection, the actual identity change, the explicit save and the reopened-journal persistence.
   - **Add beside**: a refresh saves nothing; Back saves the draft to its own character; opening another character never moves it. Each open waits for `sheet.ready()` (at once here; from Task 5 the sheet builds off the EDT).
   ```java
       @Test public void backgroundSnapshotRefreshPreservesDraftAndLeavingTheSheetSavesIt() throws Exception {
           populate();
           AtomicReference<Throwable> failure = new AtomicReference<>();
           AtomicReference<String> draftKey = new AtomicReference<>(), savedKey = new AtomicReference<>();
           SwingUtilities.invokeAndWait(() -> {
               createShell(24);
               JTable roster = named(panel, "character-roster", JTable.class);
               draftKey.set(keyForSelectedCharacter(roster));
               roster.getActionMap().get("open-character").actionPerformed(null);
               assertEquals(draftKey.get(), sheet.key());
               tomato.gui.activity.SnapshotTestSupport.await(sheet::ready);
               JTextArea notes = named(sheet, "character-notes", JTextArea.class);
               notes.setText("Draft survives capture and background save");
               Thread capture = new Thread(() -> {
                   try { journal.observe(CharacterJournalTest.player("layout-account", 782), 1); journal.save(); }
                   catch (Throwable t) { failure.set(t); }
               }, "character-ui-background-save-test");
               capture.start();
               try { capture.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
               assertFalse("Journal work must not wait for EDT", capture.isAlive()); assertNull(failure.get());
               panel.refresh(); sheet.refresh();
               assertEquals("A background save does not move the sheet", draftKey.get(), sheet.key());
               assertEquals("Draft survives capture and background save", notes.getText());
               assertEquals("A refresh saves nothing", LONG_NOTES, notesFor(journal, draftKey.get()));
               view.showList();
               assertEquals("Back saves the draft to its own character", "Draft survives capture and background save", notesFor(journal, draftKey.get()));
               assertEquals("The list keeps its selection", draftKey.get(), keyForSelectedCharacter(roster));
               int differentRow = (roster.getSelectedRow() + 1) % roster.getRowCount();
               roster.setRowSelectionInterval(differentRow, differentRow);
               savedKey.set(keyForSelectedCharacter(roster));
               assertNotEquals("Exercise an actual identity change after last-seen reordering", draftKey.get(), savedKey.get());
               roster.getActionMap().get("open-character").actionPerformed(null);
               tomato.gui.activity.SnapshotTestSupport.await(sheet::ready);
               assertEquals("Opening another character never moves the draft", "Draft survives capture and background save", notesFor(journal, draftKey.get()));
               assertEquals(LONG_NOTES, notes.getText());
               notes.setText("Explicitly saved notes"); button(sheet, "Save notes").doClick();
               assertEquals("Explicitly saved notes", notesFor(journal, savedKey.get()));
           });
           journal.save();
           try (CharacterJournal reopened = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"))) {
               assertEquals("Draft survives capture and background save", notesFor(reopened, draftKey.get()));
               assertEquals("Explicitly saved notes", notesFor(reopened, savedKey.get()));
           }
       }
   ```

- [ ] **Step 14: Migrate the evidence, roster-state, filter-bar and shell tests**

`src/test/java/tomato/gui/character/CharacterWaveFourEvidenceTest.java`:
- Replace lines 38–40:
  ```java
                      CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 1700000001000L, () -> definitions, plans);
                      frame.setContentPane(panel); frame.setSize(1080, 800); frame.setVisible(true); frame.validate();
                      JTabbedPane tabs = named(panel, "character-detail-tabs", JTabbedPane.class);
  ```
  with the following. This is a **replace**: the goals, equipment and death surfaces are now on the sheet of the one saved character. Every surface assertion and screenshot name is kept.
  ```java
                      CharacterRosterView panel = RosterFixtures.view(journal, () -> 1700000001000L, () -> definitions, plans);
                      frame.setContentPane(panel); frame.setSize(1080, 800); frame.setVisible(true); frame.validate();
                      RosterFixtures.enter(panel); frame.validate();
                      JTabbedPane tabs = named(panel, "character-tabs", JTabbedPane.class);
  ```
- On line 46, replace `tabs.indexOfTab("Equipment & inventory")` with `tabs.indexOfTab("Gear")`. **Replace**: the sheet titles the tab Gear. The line's 28-row assertion is kept.

`src/test/java/tomato/gui/character/CharacterRosterStateTest.java`, replace lines 60–69 (the body of `ageBoundaryChangesMembershipWithoutWritingDraftToAnotherCharacter` inside `invokeAndWait`):
```java
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI view = new CharacterJournalGUI(journal, clock::get, () -> definitions);
            JTable table = named(view, "character-roster", JTable.class);
            for (int i = 0; i < table.getRowCount(); i++) if (table.getValueAt(i,0).toString().endsWith("#1")) table.setRowSelectionInterval(i,i);
            named(view, "character-notes", JTextArea.class).setText("Draft for character one");
            named(view, "character-facet-8", JSpinner.class).setValue(1); named(view, "character-facet-7", JComboBox.class).setSelectedIndex(1);
            clock.set(3601000); view.refresh(); assertEquals(1, table.getRowCount()); assertTrue(table.getValueAt(0,0).toString().endsWith("#2"));
            assertEquals("Draft for character one", journal.characters().stream().filter(r -> r.characterId == 1).findFirst().get().notes);
            assertEquals("", journal.characters().stream().filter(r -> r.characterId == 2).findFirst().get().notes);
        });
```
with the following. This is a **replace**: the draft is typed on #1's sheet, and Back saves it to #1 (**added beside**). The membership change and opening #2 never write it to #2, which keeps the two final assertions.
```java
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView roster = RosterFixtures.view(journal, clock::get, () -> definitions);
            CharacterJournalGUI view = roster.listPanel();
            JTable table = named(view, "character-roster", JTable.class);
            for (int i = 0; i < table.getRowCount(); i++) if (table.getValueAt(i,0).toString().endsWith("#1")) table.setRowSelectionInterval(i,i);
            RosterFixtures.enter(roster);
            named(roster.sheet(), "character-notes", JTextArea.class).setText("Draft for character one");
            roster.showList();
            assertEquals("Back saves the draft to its own character", "Draft for character one", journal.characters().stream().filter(r -> r.characterId == 1).findFirst().get().notes);
            named(view, "character-facet-8", JSpinner.class).setValue(1); named(view, "character-facet-7", JComboBox.class).setSelectedIndex(1);
            clock.set(3601000); view.refresh(); assertEquals(1, table.getRowCount()); assertTrue(table.getValueAt(0,0).toString().endsWith("#2"));
            RosterFixtures.enter(roster);
            assertEquals("Draft for character one", journal.characters().stream().filter(r -> r.characterId == 1).findFirst().get().notes);
            assertEquals("", journal.characters().stream().filter(r -> r.characterId == 2).findFirst().get().notes);
        });
```

`src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`, line 39: replace `"ui.tabs.character-detail"` with `"ui.tabs.character"`. **Replace**: the detail tab group no longer exists, and the sheet's group is isolated instead. The page under test stays the list (`CharacterJournalGUI`), and its assertions are unchanged.

`src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`, **add beside**: insert before `    @Test public void homeIsPageFourteenAndBuildOpensFromSearchUnderItsNewTitle() throws Exception {`. The existing `plans.characters` assertions (page 3, then Back to 0) stay unchanged. `openGoals` still lands on page 3 whether the working-directory journal holds a character or not.
```java
    @Test public void charactersRoutesOpenTheListOrOneSheetAndBackRestoresEach() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.character.CharacterRosterView roster = find(shell, tomato.gui.character.CharacterRosterView.class);
            assertNotNull("The Characters Roster tab hosts the list and the sheet", roster);
            shell.select(0);
            String unknown = "0".repeat(64) + ":404";
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.CHARACTER_SHEET)
                .withPayload(new tomato.gui.glance.character.SheetFocus(unknown, null))));
            assertEquals(3, shell.getSelectedPage());
            assertTrue(roster.showingSheet());
            tomato.gui.activity.SnapshotTestSupport.await(roster.sheet()::ready); // at once here; from Task 5 the sheet reads off the EDT
            assertTrue("An unknown key shows the unavailable state",
                named(roster, "character-sheet-unavailable", tomato.gui.kit.EmptyState.class).isVisible());
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.CHARACTERS)));
            assertFalse("A plain Characters route shows the list", roster.showingSheet());
            assertTrue(navigator.back());
            assertTrue("Back restores the sheet", roster.showingSheet()); assertEquals(unknown, roster.sheet().key());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
        });
    }
```

`src/test/java/tomato/ShellRouteRegistrationTest.java`, **add beside**: insert after the two-line `assertFalse("Only allowlisted packets of the affected view are routable", …);` (lines 52–53):
```java
                String character = "0".repeat(64) + ":7";
                assertTrue("A character's sheet has its own route",
                    navigator.canOpen(Route.to(Destination.CHARACTER_SHEET).withPayload(new tomato.gui.glance.character.SheetFocus(character, "goals"))));
                assertFalse("A sheet route needs a character", navigator.canOpen(Route.to(Destination.CHARACTER_SHEET)));
                assertTrue(navigator.canOpen(Route.to(Destination.CHARACTERS)));
                assertFalse("The list takes no payload",
                    navigator.canOpen(Route.to(Destination.CHARACTERS).withPayload(new tomato.gui.glance.character.SheetFocus(character, null))));
```

- [ ] **Step 15: Run the new tests**

Run: `GRADLE test --tests "tomato.gui.glance.character.CharacterSheetTest" --tests "tomato.gui.character.CharacterRosterViewTest" --tests "tomato.gui.character.CharactersRouteTargetTest" --tests "tomato.gui.kit.CustomizableTabsTest"`
Expected: PASS.

- [ ] **Step 16: Sweep for detail-pane leftovers, then run the Characters and navigation neighbourhood**

Run: `git grep -n "character-detail\|DETAIL_TABS\|detailTabs\|detailTabIndex\|roster-detail-split\|Equipment & inventory\|Stat maxing\|registerRetainedPage(Destination.CHARACTERS)" -- src`
Expected: only these two matches. Any other match is a missed migration.
- `src/test/java/tomato/gui/dps/DpsInspectMenuTest.java` ("character-details dialog", unrelated).
- The `assertNull(… "character-roster-detail-split" …)` in `CharacterJournalLayoutTest`.

Run: `GRADLE test --tests "tomato.gui.character.*" --tests "tomato.gui.glance.character.*" --tests "tomato.gui.kit.*" --tests "tomato.gui.history.FilterBarEvidenceTest" --tests "tomato.gui.route.*" --tests "tomato.ShellRouteRegistrationTest" --tests "tomato.gui.chat.ShellHookIntegrationTest" --tests "ui.WorkspaceUiTest" --tests "ui.WorkspaceShellNavigationTest"`
Expected: PASS. Check these screenshots under `build/p3a/ui-test`:
- `screenshots/wave4/characters/goals-populated.png` shows the sheet: "‹ Characters", the title line and Restore alive above the tabs, with Death annotation among them (the fixture character is marked dead).
- `characters-desktop.png` shows the list without a side pane.

- [ ] **Step 17: Commit**

```powershell
git add src/main/java/tomato/gui/route/Destination.java src/main/java/tomato/gui/modern/WorkspaceShell.java src/main/java/tomato/gui/kit/CustomizableTabs.java src/test/java/tomato/gui/kit/CustomizableTabsTest.java src/main/java/tomato/gui/glance/character/SheetFocus.java src/main/java/tomato/gui/glance/character/SheetContext.java src/main/java/tomato/gui/glance/character/CharacterSheet.java src/main/java/tomato/gui/character/CharacterJournalGUI.java src/main/java/tomato/gui/character/CharacterRosterView.java src/main/java/tomato/gui/character/CharactersRouteTarget.java src/main/java/tomato/gui/character/CharacterPanelGUI.java src/main/java/tomato/gui/TomatoGUI.java src/test/java/tomato/gui/character/RosterFixtures.java src/test/java/tomato/gui/glance/character/CharacterSheetTest.java src/test/java/tomato/gui/character/CharacterRosterViewTest.java src/test/java/tomato/gui/character/CharactersRouteTargetTest.java src/test/java/tomato/gui/character/CharacterTabsTest.java src/test/java/tomato/gui/character/CharacterJournalGuiTest.java src/test/java/tomato/gui/character/CharacterJournalFreshnessRefreshTest.java src/test/java/tomato/gui/character/CharacterViewStateTest.java src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java src/test/java/tomato/gui/character/CharacterWaveFourEvidenceTest.java src/test/java/tomato/gui/character/CharacterRosterStateTest.java src/test/java/tomato/gui/history/FilterBarEvidenceTest.java src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java src/test/java/tomato/ShellRouteRegistrationTest.java
git commit -m "Open characters on a full-page sheet and retire the roster side pane" -m "Enter or a double-click on a roster row opens CHARACTER_SHEET through the navigator; Back and the sheet's back link return to the list as it was. The side pane's tabs move into CustomizableTabs(character), where Death annotation is a conditional tab shown only for a character marked dead. Mark dead moves to the sheet header; notes drafts are saved whenever the sheet hides; provenance is Analyst-only and storage problems show a warn banner." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Sheet header, Overview tab, `SheetModel`/`SheetModelBuilder`, `SheetPresenter`

This task replaces the moved "Stat maxing" table (Task 4) with the Overview tab and fills the header.
- **Header.** A 54 px skin sprite, the name, "class · level · fame", and chips for maxed, "Playing now", seasonal, marked dead and "Played <ago>" (the last time in game; "Seen <ago>" only for a character never played).
- **Overview.** Eight base-versus-cap bars with a "+N" boost only while playing, then a needs line per stat with vault counts only when known. Below those, four gear slots with tier labels, the class exalt summary, and the death annotation when dead.
- **Stat table.** It stays in Analyst mode as `Collapsible("character-stat-table", …)`.
- **Model.** Built off the EDT by a pure builder and applied by `SheetPresenter`, which also reads the journal for the sheet's own tabs (notes, Goals, death, evidence), so the sheet copies nothing on the EDT. Opening a character clears what is shown and says "Loading…" until that character's result applies; a result for a key the sheet no longer shows is dropped, and a failed build shows a warn banner (spec §7). Mark dead, Restore alive and Save notes act only once the header shows the opened character.
- **Times (spec §5.7).** A vault count shows its age ("3 in vault (2 h ago)") and is dimmed once a day old.

**Files:**
- Create: `src/main/java/tomato/gui/glance/character/SheetModel.java`, `SheetModelBuilder.java`, `SheetViews.java`, `SheetHeader.java`, `OverviewTab.java`, `SheetPresenter.java`
- Modify: `src/main/java/tomato/gui/glance/home/HomeModelBuilder.java` (four helpers become public), `src/main/java/tomato/gui/glance/character/CharacterSheet.java` (Task 4's file, replaced: the presenter reads the journal and fills the header; the moved stat table is removed)
- Create test: `src/test/java/tomato/gui/glance/character/SheetFixtures.java` (used by Tasks 5–8), `SheetModelBuilderTest.java`, `OverviewTabTest.java`
- Modify tests (replace, Step 14): Task 4's `src/test/java/tomato/gui/glance/character/CharacterSheetTest.java` (the whole file) and `src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`

**Interfaces:**
- Consumes:
  - **Task 4:** `CharacterSheet(SheetContext)`, `open(String key, String tab)`, `key()`, `selectedTab()`, and `SheetContext`'s `data()`, `journal()`, `definitions()` and `mode()`.
  - **Task 4, package-private:** `CharacterSheet.setTab(String id, JComponent content)` replaces a slot tab's content (overview, gear, exalts) and keeps its title, order and hidden state.
  - **Task 2:** `CharacterJournal.characterCopy(String)`, `CharacterRecord.hasBackpack`, `AccountRecord.vaultPotions` (`int[8]`, canonical, null = unknown), `AccountRecord.exaltSeenByClass` (`Map<Integer, Long>`).
  - **Task 3:** `KitText.caption/body/emphasis(String)`, `new KitText(String, Font, Tokens.Role)` and `role(Tokens.Role)` (mirroring `HomeViews.Text`), `KitLayouts.stack(int, JComponent...)`, `ItemTiers.label(int)` ("" when unknown).
  - **Existing:**
    - `CharacterJournal.potions/exaltLevel/EXALT_ORDER/STATS/accountCopy`; `RosterDefinitions.cap/empty/parse`.
    - `LiveCharacter` (`current/lastKnown/lastSeenAt/lastBoundary/revision`, `Snapshot`); `PlanningMetadata.current()/available/dungeons(int)`.
    - Kit: `StatBar`, `ItemSlot`, `Chip`, `Card`, `Collapsible`, `DisplayValue`, `DisplayModeModel.bind`, `Sprites`, `KitFormat.relative`, `Tokens`, `Type`.
    - `ContentStyle.controls/responsiveGrid/table/tableScroll`, `DisplayFormat`.
- Produces:
  - **`public record SheetModel(String key, Identity identity, Stats stats, Gear gear, Exalts exalts, Death death, Live live)`** with nested `Identity` (with `lastPlayed`), `Stats` (with `vaultObservedAt`), `Gear`, `Exalts` (`known()`), `Death` and `Live` (fields as in Step 4). −1 means unknown; a slot is an item ID > 0, 0 for empty, −1 for not captured.
  - **`SheetModelBuilder`:** `public static SheetModel build(CharacterRecord, AccountRecord, LiveCharacter.Snapshot, RosterDefinitions, long now)` (null for a null record); a package-private overload with `IntFunction<List<String>> dungeons`; `toNext(int)` and `summary(List<Integer>)`; `public static LiveCharacter.Snapshot inGame(LiveCharacter, long now)`, the character in game with Home's map-change grace (the gallery's "Playing now" uses it too).
  - **`SheetHeader`** (`character-sheet-identity`): `apply(SheetModel.Identity)`; names `character-sheet-{sprite,name,meta,maxed,playing,seasonal,dead,seen}`; accessible name "Sharkbait, Wizard level 20, 5 of 8 maxed[, playing now]", description "Played …" (or "Seen …").
  - **`OverviewTab`** (`character-overview`): `apply(SheetModel)`; names `character-overview-{bar,value,boost}-i`, `-needs` (the row of need labels), `-need-N`, `-needs-unknown`, `-maxed`, `-slot-i`, `-tier-i`, `-exalts`, `-death`, `-death-text`, plus `character-stat-table`/`character-stat-rows`.
  - **`CharacterSheet`** (Step 13 replaces Task 4's file; public API unchanged): package-private `setIdentity(JComponent)` (the identity block beside Mark dead / Restore alive), `loaded(String key, CharacterRecord, List<CharacterRecord>, List<AccountRecord>, RosterDefinitions, long revision)` (a read for another key is ignored) and `failed(RuntimeException)`; the banner `character-sheet-status` ("Loading…", or warn after a failed build). Task 4's title line, sprite and moved stat table are removed.
  - **Helpers:** `SheetViews` (`STATS`, `SLOTS`, `named`, `clear`, `row`, `beside`, `card`, `at`, `scroll`); `SheetPresenter(CharacterSheet, SheetContext)` with `open(String)`, `refresh()` and `model()`; public `HomeModelBuilder.potionsNeeded/maxed/stillCurrent/statLabel`.
  - **Test fixture `SheetFixtures`:** `WIZARD`, `PRIEST`, `ACCOUNT`, `KEY`, `NOW`, `HOUR`, `defs()`, `record()`, `account(int...)`, `bonus(...)`, `live(...)`, `model(...)`, `seed(journal)`, `inject(data, journal)`, `find/count/named`.

- [ ] **Step 1: Write the fixture and the failing builder test**

`src/test/java/tomato/gui/glance/character/SheetFixtures.java`:
```java
package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import tomato.backend.data.*;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.gui.myinfo.BuildEstimates;

/** Synthetic character-sheet inputs: one Wizard of a hashed synthetic account. No capture and no personal data. */
public final class SheetFixtures {
    public static final int WIZARD = 782, PRIEST = 784;
    public static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture"), KEY = ACCOUNT + ":7";
    public static final long NOW = 1_700_000_000_000L, HOUR = 3_600_000L;

    private SheetFixtures() {}

    /** Wizard caps (life, mana, atk, def, spd, dex, vit, wis) = 720, 252, 75, 25, 50, 75, 40, 60, from a players.xml fragment. */
    public static RosterDefinitions defs() {
        String xml = "<Objects><Object type=\"782\"><MaxHitPoints max=\"720\"/><MaxMagicPoints max=\"252\"/><Attack max=\"75\"/>"
            + "<Defense max=\"25\"/><Speed max=\"50\"/><Dexterity max=\"75\"/><HpRegen max=\"40\"/><MpRegen max=\"60\"/></Object></Objects>";
        try { return RosterDefinitions.parse(new StringReader(xml), null); } catch (IOException e) { throw new AssertionError(e); }
    }

    /** Wizard #7, 5 of 8 maxed (DEF needs 5, VIT 3, WIS 12); weapon and ring equipped, ability empty, armor not captured. */
    public static CharacterRecord record() {
        CharacterRecord r = new CharacterRecord();
        r.key = KEY; r.account = ACCOUNT; r.characterId = 7; r.classId = WIZARD; r.className = "Wizard"; r.name = "Sharkbait";
        r.level = 20; r.skin = 0; r.fame = 1_234L; r.seasonal = Boolean.TRUE; r.lastSeen = NOW - 2 * HOUR;
        int[] base = {720, 252, 75, 20, 50, 75, 37, 48};
        for (int i = 0; i < 8; i++) { r.stats[i] = base[i]; r.fields.put("stat." + i, new FieldCapture(r.lastSeen, "Captured total minus boost")); }
        r.equipment[0] = 2_001; r.equipment[1] = -1; r.equipment[3] = 2_004; r.equipment[4] = 2_010;
        for (int i = 5; i < 12; i++) r.equipment[i] = -1;
        return r;
    }

    /** An account whose Wizard exalt counts are given in canonical stat order (stored in RealmCharacter order); none when not 8. */
    public static AccountRecord account(int... canonical) {
        AccountRecord a = new AccountRecord(); a.key = ACCOUNT;
        if (canonical.length == 8) {
            int[] counts = new int[8];
            for (int i = 0; i < 8; i++) counts[CharacterJournal.EXALT_ORDER[i]] = canonical[i];
            a.exalts.put(WIZARD, counts);
        }
        return a;
    }

    public static CharacterJournal.ExaltBonus bonus(long observedAt, int... canonical) {
        CharacterJournal.ExaltBonus bonus = new CharacterJournal.ExaltBonus(); bonus.bonus = canonical.clone(); bonus.observedAt = observedAt;
        return bonus;
    }

    /** A live Wizard: same base as record(), totals 800/300/90/30/60/80/50/70, gear weapon/empty/armor/ring, live exalt bonus 9s. */
    public static LiveCharacter.Snapshot live(String account, int characterId, String name, BuildEstimates.Inputs build) {
        return new LiveCharacter.Snapshot(account, characterId, WIZARD, name, 0, 20, 1_500L, new int[]{800, 300, 90, 30, 60, 80, 50, 70},
            new int[]{720, 252, 75, 20, 50, 75, 37, 48}, new int[]{2_001, 0, 2_003, 2_004}, 10_000, 500, 40,
            new int[]{9, 9, 9, 9, 9, 9, 9, 9}, build, NOW - 500);
    }

    /** The builder with the fixture caps and no dungeon mapping (as while PlanningMetadata loads). */
    public static SheetModel model(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live) {
        return SheetModelBuilder.build(record, account, live, defs(), null, NOW);
    }

    /** Observes one Wizard #7 of the synthetic account (name "Sample") and returns its journal key. */
    public static String seed(CharacterJournal journal) {
        journal.observe(CharacterJournalTest.player("sheet-fixture", WIZARD), 7);
        return KEY;
    }

    /** Points TomatoData at a test journal; its lazy default reads Characters/journal.json in the working directory. */
    public static void inject(TomatoData data, CharacterJournal journal) {
        try { Field field = TomatoData.class.getDeclaredField("characterJournal"); field.setAccessible(true); field.set(data, journal); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** The first descendant of {@code type} (depth first), or null. */
    public static <T> T find(Container root, Class<T> type) { return search(root, null, type); }

    public static int count(Container root, Class<?> type) {
        int count = 0;
        for (Component child : root.getComponents()) count += (type.isInstance(child) ? 1 : 0) + (child instanceof Container ? count((Container) child, type) : 0);
        return count;
    }

    public static <T extends Component> T named(Container root, String name, Class<T> type) {
        T found = search(root, name, type);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + " named " + name);
        return found;
    }

    private static <T> T search(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (name == null || name.equals(child.getName()))) return type.cast(child);
            if (child instanceof Container) { T found = search((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/glance/character/SheetModelBuilderTest.java`:
```java
package tomato.gui.glance.character;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Sheet rules on synthetic records: Home's potion arithmetic, vault counts only when known, unknown never 0, live only while playing. */
public class SheetModelBuilderTest {
    @Test public void potionsMaxedAndNeedsUseCapsAndVaultCounts() {
        CharacterJournal.AccountRecord account = account();
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7}; account.vaultPotionsObservedAt = NOW - 3 * HOUR;
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE;
        SheetModel m = model(regular, account, null);
        assertEquals("The vault count keeps its time", NOW - 3 * HOUR, m.stats().vaultObservedAt());
        assertNull("Only the regular vault is recorded: a seasonal character shows no vault count", model(record(), account, null).stats().vault());
        assertEquals(KEY, m.key()); assertEquals(5, m.identity().maxed()); assertEquals(0, m.stats().unknown());
        assertEquals(List.of(720, 252, 75, 25, 50, 75, 40, 60), m.stats().caps());
        assertEquals(List.of(0, 0, 0, 5, 0, 0, 3, 12), m.stats().needed());
        assertEquals("A real zero in the vault stays zero", List.of("DEF needs 5 · 3 in vault", "VIT needs 3 · 0 in vault",
            "WIS needs 12 · 7 in vault"), m.stats().needs());
        assertEquals("Captured total minus boost · " + DisplayFormat.formatTimestamp(NOW - 2 * HOUR), m.stats().evidence().get(3));
        assertFalse(m.identity().playing()); assertEquals(Collections.nCopies(8, 0), m.stats().boosts());
        assertEquals(DisplayValue.State.STALE, m.identity().fame().state); assertEquals(NOW - 2 * HOUR, m.identity().lastSeen());
        assertEquals("Never observed in game", 0, m.identity().lastPlayed());
    }

    @Test public void unknownIsNeverShownAsZero() {
        CharacterJournal.CharacterRecord partial = record(); partial.stats[3] = null;
        SheetModel m = model(partial, account(), null);
        assertEquals(Integer.valueOf(-1), m.stats().needed().get(3));
        assertEquals(-1, m.identity().maxed()); assertEquals(1, m.stats().unknown()); assertNull(m.stats().vault());
        assertEquals("Vault counts appear only when known", List.of("VIT needs 3", "WIS needs 12"), m.stats().needs());
        assertEquals("Not captured", m.stats().evidence().get(3));
        SheetModel noCaps = SheetModelBuilder.build(record(), account(), null, RosterDefinitions.empty(), null, NOW);
        assertEquals(-1, noCaps.identity().maxed()); assertEquals(8, noCaps.stats().unknown()); assertTrue(noCaps.stats().needs().isEmpty());
        SheetModel noExalts = model(record(), null, null);
        assertFalse(noExalts.exalts().known()); assertEquals("", noExalts.exalts().summary());
        assertEquals(Collections.nCopies(8, -1), noExalts.exalts().completions());
        CharacterJournal.CharacterRecord noFame = record(); noFame.fame = null;
        assertEquals(DisplayValue.State.UNKNOWN, model(noFame, account(), null).identity().fame().state);
        assertEquals("Not captured stays -1, captured empty is 0", List.of(2_001, 0, -1, 2_004), m.gear().slots().subList(0, 4));
        assertNull("Enchants belong to the live character only", m.gear().enchants());
    }

    @Test public void exaltSummaryListsTheTopThreeByTierThenHowManyMore() {
        SheetModel.Exalts e = model(record(), account(80, 15, 30, 50, 0, 0, 0, 5), null).exalts();
        assertEquals(List.of(5, 2, 3, 4, 0, 0, 0, 1), e.tiers());
        assertEquals("LIFE 5/5 · DEF 4/5 · ATT 3/5 · +2 more", e.summary());
        assertEquals(180, e.total()); assertEquals(0, e.lowest());
        assertEquals("Ties keep stat order", "ATT 1/5 · DEF 1/5 · SPD 1/5", model(record(), account(0, 0, 5, 5, 5, 0, 0, 0), null).exalts().summary());
        assertEquals("No exalt tiers yet", model(record(), account(0, 4, 0, 0, 0, 0, 0, 0), null).exalts().summary());
    }

    @Test public void liveValuesApplyOnlyWhileThisCharacterIsPlaying() {
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null));
        assertTrue(playing.identity().playing()); assertEquals(NOW, playing.identity().lastSeen()); assertEquals(NOW, playing.identity().lastPlayed());
        assertEquals("Boost is total minus base", List.of(80, 48, 15, 10, 10, 5, 13, 22), playing.stats().boosts());
        assertEquals("Live equipped slots are the freshest", List.of(2_001, 0, 2_003, 2_004), playing.gear().slots().subList(0, 4));
        assertEquals(new SheetModel.Live(KEY, "Sharkbait"), playing.live());
        assertEquals(DisplayValue.State.KNOWN, playing.identity().fame().state);
        assertEquals("Equal inputs build equal models", playing, model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null)));
        SheetModel other = model(record(), account(), live(ACCOUNT, 8, "Ann", null));
        assertFalse(other.identity().playing()); assertEquals(Collections.nCopies(8, 0), other.stats().boosts());
        assertEquals(List.of(2_001, 0, -1, 2_004), other.gear().slots().subList(0, 4));
        assertEquals(new SheetModel.Live(ACCOUNT + ":8", "Ann"), other.live());
        assertNull(model(record(), account(), null).live());
    }

    @Test public void deathAndIdentityComeFromTheSavedRecord() {
        CharacterJournal.CharacterRecord dead = record(); dead.dead = true; dead.diedAt = NOW - 24 * HOUR;
        dead.deathAnnotation = new CharacterJournal.DeathAnnotation(); dead.deathAnnotation.markedAt = NOW - 24 * HOUR; dead.deathAnnotation.notes = "Synthetic note";
        SheetModel m = model(dead, account(), null);
        assertEquals(new SheetModel.Death(NOW - 24 * HOUR, null, "Synthetic note"), m.death());
        assertTrue(m.identity().dead()); assertEquals(Boolean.TRUE, m.identity().seasonal());
        assertEquals("Sharkbait", m.identity().name()); assertEquals("Wizard", m.identity().className());
        assertNull(model(record(), account(), null).death());
        CharacterJournal.CharacterRecord unnamed = record(); unnamed.name = null;
        assertEquals("Wizard #7", model(unnamed, account(), null).identity().name());
        assertNull("A key the journal does not have builds nothing", model(null, account(), null));
    }

    @Test public void aMapChangesBriefClearStillCountsAsInGame() {
        LiveCharacter live = new LiveCharacter();
        LiveCharacter.Snapshot wizard = live(ACCOUNT, 7, "Sharkbait", null);
        live.publish(wizard);
        assertSame(wizard, SheetModelBuilder.inGame(live, NOW));
        live.clear(NOW, LiveCharacter.Boundary.TRANSIENT);
        assertSame("Within Home's grace a map change is not leaving the game", wizard, SheetModelBuilder.inGame(live, NOW + 1_000));
        assertNull("After it, nobody is in game", SheetModelBuilder.inGame(live, NOW + HOUR));
        live.publish(wizard); live.stop(NOW + 2_000);
        assertNull("A capture stop ends it at once", SheetModelBuilder.inGame(live, NOW + 2_001));
        assertNull(SheetModelBuilder.inGame(null, NOW));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.glance.character.SheetModelBuilderTest"`
Expected: FAIL (compile error: `SheetModel` and `SheetModelBuilder` do not exist).

- [ ] **Step 3: Make Home's potion helpers public**

In `src/main/java/tomato/gui/glance/home/HomeModelBuilder.java`, **replace**:
```java
    private HomeModelBuilder() {}
```
with:
```java
    private HomeModelBuilder() {}

    /** Short canonical stat label: LIFE, MANA, ATT, DEF, SPD, DEX, VIT, WIS (the character sheet uses Home's wording). */
    public static String statLabel(int index) { return STAT_LABELS[index]; }
```
**Replace** `    static boolean stillCurrent(long lastSeenAt, LiveCharacter.Boundary boundary, long now) {` with `    public static boolean stillCurrent(long lastSeenAt, LiveCharacter.Boundary boundary, long now) {`.
**Replace** `    static int[] potionsNeeded(int[] base, int[] caps) {` with `    public static int[] potionsNeeded(int[] base, int[] caps) {`.
**Replace** `    static int maxed(int[] need) { int count = 0; for (int n : need) { if (n < 0) return -1; if (n == 0) count++; } return count; }` with the same line starting `    public static int maxed(`.

- [ ] **Step 4: Create `SheetModel.java`**

```java
package tomato.gui.glance.character;

import java.util.List;
import java.util.Objects;
import tomato.gui.kit.DisplayValue;

/**
 * Immutable character sheet view model (spec §6.2), built off the EDT by SheetModelBuilder and applied on the EDT. Per-stat
 * lists have 8 entries in canonical order (life, mana, atk, def, spd, dex, vit, wis) with -1 for unknown. Lists are immutable
 * and compare by content, so the presenter and tabs can skip a section that did not change.
 */
public record SheetModel(String key, Identity identity, Stats stats, Gear gear, Exalts exalts, Death death, Live live) {
    public SheetModel {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(gear, "gear"); Objects.requireNonNull(exalts, "exalts");
    }

    /**
     * {@code maxed} 0-8 or -1 unknown; {@code lastSeen} epoch ms of the last capture (0 unknown, the build time while playing);
     * {@code lastPlayed} the last time in game (the journal's lastObservedAlive, 0 never, the build time while playing);
     * {@code playing}: in game now.
     */
    public record Identity(String name, int classId, String className, Integer skin, Integer level, DisplayValue fame,
                           Boolean seasonal, boolean dead, long lastSeen, long lastPlayed, boolean playing, int maxed) {}

    /**
     * {@code boosts} gear and effect bonuses (total - base), 0 unless playing; {@code needed} potions to max per stat; {@code vault}
     * stored potions per stat, null while unknown or for a seasonal character (only the regular vault is recorded), counted at
     * {@code vaultObservedAt} (0 unknown or no count); {@code needs} one line per stat that needs potions; {@code unknown} stats
     * whose base or cap is not captured; {@code evidence} the stat table's Field evidence column.
     */
    public record Stats(List<Integer> base, List<Integer> caps, List<Integer> boosts, List<Integer> needed, List<Integer> vault,
                        long vaultObservedAt, List<String> needs, int unknown, int maxed, List<String> evidence) {}

    /**
     * 28 slots (0-3 equipped, 4-11 inventory, 12-27 backpack): item id > 0, 0 empty, -1 not captured. {@code hasBackpack} null
     * unknown. {@code enchants}: unlocked enchant slots of the 4 equipped items (0 Common … 4 Divine), -1 unknown; null unless playing.
     */
    public record Gear(List<Integer> slots, Boolean hasBackpack, List<Integer> enchants) {}

    /**
     * This class's exaltations: {@code completions}, {@code tiers} (0-5) and {@code toNext} (0 once maxed) per stat, -1 unknown;
     * {@code total} and {@code lowest} -1 unknown; {@code liveBonus} the saved live stat bonus for this class (null = never observed)
     * from {@code liveObservedAt}; {@code seenAt} when this class's counts were last observed (0 unknown); {@code earnIn} dungeon
     * names per stat, null while the mapping loads or is unavailable ("" = not mapped); {@code summary} top 3 stats plus "+N more".
     */
    public record Exalts(List<Integer> completions, List<Integer> tiers, List<Integer> toNext, int total, int lowest,
                         List<Integer> liveBonus, long liveObservedAt, long seenAt, List<String> earnIn, String summary) {
        public boolean known() { return total >= 0; }
    }

    /** Manual death annotation; {@code occurredAt} null unless the user entered it. */
    public record Death(long markedAt, Long occurredAt, String notes) {}

    /** The character in game now (possibly another one): its journal key and display name. */
    public record Live(String key, String name) {}
}
```

- [ ] **Step 5: Create `SheetModelBuilder.java`**

```java
package tomato.gui.glance.character;

import java.util.*;
import java.util.function.IntFunction;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.home.HomeModelBuilder;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.enums.CharacterClass;

/**
 * Pure, static builder of the character sheet model (spec §6.2). No Swing (DisplayValue only): SheetPresenter calls it on the
 * "character-sheet" thread with detached journal copies. Potions and maxed counts use Home's arithmetic, so the hero and the sheet agree.
 */
public final class SheetModelBuilder {
    /** Exaltation tier thresholds, as CharacterJournal.exaltLevel counts them. */
    static final int[] THRESHOLDS = {5, 15, 30, 50, 75};
    static final int SUMMARY_STATS = 3;
    static final String FAME_UNKNOWN = "Enter the game with capture on to read character fame";

    private SheetModelBuilder() {}

    /**
     * The sheet of {@code record}, or null when the journal has no such character. {@code live} is the character in game now,
     * whoever it is (null = nobody); its values apply only when it is this character. Dungeons stay unknown while the mapping loads.
     */
    public static SheetModel build(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live, RosterDefinitions defs, long now) {
        PlanningMetadata planning = PlanningMetadata.current();
        return build(record, account, live, defs, planning.available ? planning::dungeons : null, now);
    }

    /**
     * The character in game now, or null: the current snapshot, or while a map change's brief clear lasts (Home's grace,
     * {@link HomeModelBuilder#stillCurrent}) the last known one, so "Playing now" does not flicker. Any thread.
     */
    public static LiveCharacter.Snapshot inGame(LiveCharacter live, long now) {
        if (live == null) return null;
        LiveCharacter.Snapshot current = live.current();
        if (current != null) return current;
        return HomeModelBuilder.stillCurrent(live.lastSeenAt(), live.lastBoundary(), now) ? live.lastKnown() : null;
    }

    /** {@code dungeons}: canonical stat index to dungeon names, or null while the mapping is loading or unavailable. */
    static SheetModel build(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live, RosterDefinitions defs,
                            IntFunction<List<String>> dungeons, long now) {
        if (record == null) return null;
        RosterDefinitions definitions = defs == null ? RosterDefinitions.empty() : defs;
        boolean playing = live != null && Objects.equals(live.account(), record.account) && live.characterId() == record.characterId;
        int[] liveBase = playing ? live.base() : null, totals = playing ? live.totals() : null;
        int[] base = new int[8], caps = new int[8], boosts = new int[8];
        for (int i = 0; i < 8; i++) {
            Integer saved = record.stats != null && i < record.stats.length ? record.stats[i] : null, cap = definitions.cap(record.classId, i);
            base[i] = liveBase != null && liveBase[i] >= 0 ? liveBase[i] : saved == null || saved < 0 ? -1 : saved;
            caps[i] = cap == null ? -1 : cap;
            boosts[i] = totals != null && totals[i] >= 0 && base[i] >= 0 ? Math.max(0, totals[i] - base[i]) : 0;
        }
        int[] needed = HomeModelBuilder.potionsNeeded(base, caps);
        int maxed = HomeModelBuilder.maxed(needed);
        return new SheetModel(record.key, identity(record, live, playing, maxed, now), stats(record, account, base, caps, boosts, needed, maxed, liveBase),
            gear(record, live, playing), exalts(record.classId, account, dungeons), death(record), liveRef(live));
    }

    private static SheetModel.Identity identity(CharacterRecord r, LiveCharacter.Snapshot live, boolean playing, int maxed, long now) {
        String kind = r.className != null && !r.className.isBlank() ? r.className : className(r.classId);
        String name = r.name != null && !r.name.isBlank() ? r.name : kind + " #" + r.characterId;
        Integer level = playing && live.level() != null ? live.level() : r.level, skin = playing && live.skin() != null ? live.skin() : r.skin;
        DisplayValue fame = playing && live.characterFame() != null ? DisplayValue.count(live.characterFame(), "Live character stats", FAME_UNKNOWN)
            : r.fame == null ? DisplayValue.unknown(FAME_UNKNOWN)
            : DisplayValue.stale(DisplayFormat.formatInteger(r.fame.longValue()), "Saved in the character journal, last seen " + when(r.lastSeen));
        return new SheetModel.Identity(name, r.classId, kind, skin, level, fame, r.seasonal, r.dead, playing ? now : r.lastSeen,
            playing ? now : r.lastObservedAlive, playing, maxed);
    }

    private static SheetModel.Stats stats(CharacterRecord r, AccountRecord account, int[] base, int[] caps, int[] boosts, int[] needed,
                                          int maxed, int[] liveBase) {
        // Journal v5 records the regular vault only; a seasonal character cannot use it, so its vault count stays unknown.
        int[] vault = Boolean.TRUE.equals(r.seasonal) || account == null || account.vaultPotions == null || account.vaultPotions.length != 8
            ? null : account.vaultPotions;
        List<String> needs = new ArrayList<>(), evidence = new ArrayList<>();
        int unknown = 0;
        for (int i = 0; i < 8; i++) {
            if (needed[i] < 0) unknown++;
            else if (needed[i] > 0) needs.add(HomeModelBuilder.statLabel(i) + " needs " + needed[i] + (vault != null && vault[i] >= 0 ? " · " + vault[i] + " in vault" : ""));
            evidence.add(liveBase != null && liveBase[i] >= 0 ? "Live character stats" : evidence(r, "stat." + i, base[i] >= 0));
        }
        return new SheetModel.Stats(list(base), list(caps), list(boosts), list(needed), vault == null ? null : list(vault),
            vault == null ? 0 : account.vaultPotionsObservedAt, List.copyOf(needs), unknown, maxed, List.copyOf(evidence));
    }

    /** Saved slots, with the live equipped four while this character plays. Enchant rarity arrives in Task 6. */
    private static SheetModel.Gear gear(CharacterRecord r, LiveCharacter.Snapshot live, boolean playing) {
        int[] equipped = playing ? live.equipment() : null, slots = new int[28];
        for (int i = 0; i < 28; i++) {
            Integer item = i < 4 && equipped != null ? Integer.valueOf(equipped[i]) : r.equipment != null && i < r.equipment.length ? r.equipment[i] : null;
            slots[i] = item == null ? -1 : item > 0 ? item : 0;
        }
        return new SheetModel.Gear(list(slots), r.hasBackpack, null);
    }

    /** Exalt arrays are in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life); EXALT_ORDER maps canonical stats into them. */
    static SheetModel.Exalts exalts(int classId, AccountRecord account, IntFunction<List<String>> dungeons) {
        int[] counts = account == null || account.exalts == null ? null : account.exalts.get(classId);
        boolean known = counts != null && counts.length == 8 && Arrays.stream(counts).allMatch(count -> count >= 0);
        int[] completions = new int[8], tiers = new int[8], toNext = new int[8];
        int total = known ? 0 : -1, lowest = known ? 5 : -1;
        for (int i = 0; i < 8; i++) {
            int count = known ? counts[CharacterJournal.EXALT_ORDER[i]] : -1;
            completions[i] = count; tiers[i] = known ? CharacterJournal.exaltLevel(count) : -1; toNext[i] = known ? toNext(count) : -1;
            if (known) { total += count; lowest = Math.min(lowest, tiers[i]); }
        }
        CharacterJournal.ExaltBonus bonus = account == null || account.liveExaltBonus == null ? null : account.liveExaltBonus.get(classId);
        boolean bonusKnown = bonus != null && bonus.bonus != null && bonus.bonus.length == 8;
        Long seen = account == null || account.exaltSeenByClass == null ? null : account.exaltSeenByClass.get(classId);
        List<String> earnIn = null;
        if (dungeons != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 8; i++) { List<String> where = dungeons.apply(i); names.add(where == null ? "" : String.join(" · ", where)); }
            earnIn = List.copyOf(names);
        }
        List<Integer> tierList = list(tiers);
        return new SheetModel.Exalts(list(completions), tierList, list(toNext), total, lowest, bonusKnown ? list(bonus.bonus) : null,
            bonusKnown ? bonus.observedAt : 0, seen == null ? 0 : seen, earnIn, known ? summary(tierList) : "");
    }

    /** Completions still needed for the next tier; 0 once the last tier (75 completions) is reached. */
    static int toNext(int count) { for (int goal : THRESHOLDS) if (count < goal) return goal - count; return 0; }

    /** "LIFE 5/5 · DEF 4/5 · ATT 3/5 · +2 more": highest tiers first (ties keep stat order); N counts other stats with a tier. */
    static String summary(List<Integer> tiers) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 8; i++) if (tiers.get(i) > 0) order.add(i);
        if (order.isEmpty()) return "No exalt tiers yet";
        order.sort(Comparator.comparingInt(i -> -tiers.get(i)));
        List<String> parts = new ArrayList<>();
        for (int i : order.subList(0, Math.min(SUMMARY_STATS, order.size()))) parts.add(HomeModelBuilder.statLabel(i) + " " + tiers.get(i) + "/5");
        int more = order.size() - SUMMARY_STATS;
        return String.join(" · ", parts) + (more > 0 ? " · +" + more + " more" : "");
    }

    private static SheetModel.Death death(CharacterRecord r) {
        if (!r.dead) return null;
        CharacterJournal.DeathAnnotation a = r.deathAnnotation;
        return new SheetModel.Death(a != null && a.markedAt > 0 ? a.markedAt : r.diedAt, a == null ? null : a.occurredAt, a == null || a.notes == null ? "" : a.notes);
    }

    private static SheetModel.Live liveRef(LiveCharacter.Snapshot live) {
        if (live == null || live.account() == null || live.characterId() < 0) return null;
        String name = live.name() != null && !live.name().isBlank() ? live.name() : className(live.classId()) + " #" + live.characterId();
        return new SheetModel.Live(live.account() + ":" + live.characterId(), name);
    }

    private static String evidence(CharacterRecord r, String field, boolean known) {
        if (!known) return "Not captured";
        FieldCapture capture = r.fields == null ? null : r.fields.get(field);
        if (capture == null) return "Legacy / provenance unknown";
        return capture.source + " · " + DisplayFormat.formatTimestamp(capture.at) + (capture.at > 0 && capture.at < r.lastSeen ? " · Retained from earlier observation" : "");
    }

    private static String className(int classId) { String name = CharacterClass.getName(classId); return name == null || name.isEmpty() ? "Class #" + classId : name; }
    private static String when(long at) { return at > 0 ? DisplayFormat.formatTimestamp(at) : "at an unknown time"; }
    private static List<Integer> list(int[] values) { List<Integer> out = new ArrayList<>(values.length); for (int value : values) out.add(value); return List.copyOf(out); }
}
```

- [ ] **Step 6: Run the builder test**

Run: `GRADLE test --tests "tomato.gui.glance.character.SheetModelBuilderTest" --tests "tomato.gui.glance.home.HomeModelBuilderTest"`
Expected: PASS (6 new tests; Home's builder tests unchanged).

- [ ] **Step 7: Write the failing Overview and header test**

`src/test/java/tomato/gui/glance/character/OverviewTabTest.java`:
```java
package tomato.gui.glance.character;

import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Overview and header on synthetic models: live boost only while playing, vault counts only when known, unknown never 0. */
public class OverviewTabTest {
    private final DisplayModeModel mode = new DisplayModeModel(key -> null, (key, value) -> {});
    private static String text(JComponent root, String name) { return named(root, name, JLabel.class).getText(); }
    private static ItemSlot.State slot(JComponent root, int i) { return named(root, "character-overview-slot-" + i, ItemSlot.class).state(); }

    @Test public void playingShowsBarsBoostsVaultNeedsGearAndTheExaltSummary() throws Exception {
        CharacterJournal.AccountRecord account = account(80, 15, 30, 50, 0, 0, 0, 5);
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7};
        account.vaultPotionsObservedAt = System.currentTimeMillis() - 2 * HOUR; // KitFormat.relative reads the real clock
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE; // seasonal characters show no vault count
        SheetModel model = model(regular, account, live(ACCOUNT, 7, "Sharkbait", null));
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, id -> id == 2_001 ? "UT" : "");
            tab.apply(model);
            assertEquals("20/25", text(tab, "character-overview-value-3"));
            assertTrue(named(tab, "character-overview-bar-0", StatBar.class).maxed());
            assertFalse(named(tab, "character-overview-bar-3", StatBar.class).maxed());
            JLabel boost = named(tab, "character-overview-boost-3", JLabel.class);
            assertTrue(boost.isVisible()); assertEquals("+10", boost.getText());
            assertEquals("A vault count shows its age", "DEF needs 5 · 3 in vault (2 h ago)", text(tab, "character-overview-need-0"));
            assertEquals("WIS needs 12 · 7 in vault (2 h ago)", text(tab, "character-overview-need-2"));
            assertEquals("A fresh count reads as current", Tokens.Role.TEXT, named(tab, "character-overview-need-0", KitText.class).role());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 0));
            assertEquals("The live ability slot is empty", ItemSlot.State.EMPTY, slot(tab, 1));
            assertEquals("UT", text(tab, "character-overview-tier-0"));
            assertEquals("Without a tier the slot name shows", "Armor", text(tab, "character-overview-tier-2"));
            assertEquals("LIFE 5/5 · DEF 4/5 · ATT 3/5 · +2 more", text(tab, "character-overview-exalts"));
            assertFalse(named(tab, "character-overview-death", Card.class).isVisible());
        });
    }

    @Test public void notPlayingHidesBoostsAndKeepsUnknownUnknown() throws Exception {
        CharacterJournal.CharacterRecord record = record(); record.stats[3] = null;
        SheetModel model = model(record, account(), live(ACCOUNT, 8, "Ann", null));
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, id -> "");
            tab.apply(model);
            for (int i = 0; i < 8; i++) assertFalse("No boost for a character not in game", named(tab, "character-overview-boost-" + i, JLabel.class).isVisible());
            assertEquals("—", text(tab, "character-overview-value-3"));
            assertEquals("No vault data: no vault count", "VIT needs 3", text(tab, "character-overview-need-0"));
            assertEquals("Potions unknown for 1 stat (base stat or cap not captured)", text(tab, "character-overview-needs-unknown"));
            assertEquals(ItemSlot.State.UNKNOWN, slot(tab, 2)); assertEquals(ItemSlot.State.EMPTY, slot(tab, 1));
            assertEquals("No saved exalts: unknown, not zero", "—", text(tab, "character-overview-exalts"));
        });
    }

    @Test public void aVaultCountOlderThanADayIsDimmedAsStale() throws Exception {
        CharacterJournal.AccountRecord account = account();
        account.vaultPotions = new int[]{0, 0, 0, 3, 0, 0, 0, 7};
        account.vaultPotionsObservedAt = System.currentTimeMillis() - 30 * HOUR;
        CharacterJournal.CharacterRecord regular = record(); regular.seasonal = Boolean.FALSE;
        SheetModel model = model(regular, account, null);
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, id -> "");
            tab.apply(model);
            KitText need = named(tab, "character-overview-need-0", KitText.class);
            assertEquals("DEF needs 5 · 3 in vault (yesterday)", need.getText());
            assertEquals("Stale after a day: dimmed (spec §5.7)", Tokens.Role.TEXT_MUTED, need.role());
            assertTrue(need.getToolTipText(), need.getToolTipText().startsWith("Vault counted "));
        });
    }

    @Test public void statTableIsAnalystOnlyAndDeathShowsForADeadCharacter() throws Exception {
        CharacterJournal.CharacterRecord record = record(); record.dead = true; record.diedAt = NOW - 24 * HOUR;
        SheetModel model = model(record, account(), null);
        SwingUtilities.invokeAndWait(() -> {
            OverviewTab tab = new OverviewTab(mode, id -> "");
            tab.apply(model);
            Collapsible table = named(tab, "character-stat-table", Collapsible.class);
            assertFalse("Simple mode hides the stat table", table.isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(table.isVisible());
            JTable rows = named(tab, "character-stat-rows", JTable.class);
            assertEquals(8, rows.getRowCount()); assertEquals(5, rows.getValueAt(3, 3)); assertEquals("Maxed", rows.getValueAt(0, 3));
            assertTrue(String.valueOf(rows.getValueAt(3, 4)).startsWith("Captured total minus boost · "));
            assertTrue(named(tab, "character-overview-death", Card.class).isVisible());
            assertTrue(text(tab, "character-overview-death-text").startsWith("Marked dead "));
        });
    }

    @Test public void headerShowsIdentityPlayingAndSeasonalAndHidesAnUnknownMaxedCount() throws Exception {
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null));
        CharacterJournal.CharacterRecord partial = record(); partial.stats[0] = null;
        SheetModel away = model(partial, account(), null);
        CharacterJournal.CharacterRecord played = record(); played.lastObservedAlive = System.currentTimeMillis() - 3 * HOUR;
        SheetModel wasPlayed = model(played, account(), null);
        SwingUtilities.invokeAndWait(() -> {
            SheetHeader header = new SheetHeader();
            header.apply(playing.identity());
            assertEquals("Sharkbait", text(header, "character-sheet-name"));
            assertTrue(text(header, "character-sheet-meta").startsWith("Wizard · Level 20 · Fame "));
            Chip maxed = named(header, "character-sheet-maxed", Chip.class);
            assertTrue(maxed.isVisible()); assertEquals("5/8 maxed", maxed.getText()); assertEquals(Tokens.Tone.WARN, maxed.tone());
            assertTrue(named(header, "character-sheet-playing", Chip.class).isVisible());
            assertTrue(named(header, "character-sheet-seasonal", Chip.class).isVisible());
            assertFalse(named(header, "character-sheet-seen", Chip.class).isVisible());
            header.apply(away.identity());
            assertFalse("Unknown is never shown as 0/8", named(header, "character-sheet-maxed", Chip.class).isVisible());
            assertFalse(named(header, "character-sheet-playing", Chip.class).isVisible());
            Chip seen = named(header, "character-sheet-seen", Chip.class);
            assertTrue("Never in game: seen from the character list", seen.getText().startsWith("Seen "));
            assertEquals("The time is the description, never the name", seen.getText(), header.getAccessibleContext().getAccessibleDescription());
            header.apply(wasPlayed.identity());
            assertEquals("The last time in game, as the gallery's Last played sort", "Played 3 h ago", seen.getText());
        });
    }
}
```

- [ ] **Step 8: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.glance.character.OverviewTabTest"`
Expected: FAIL (compile error: `OverviewTab`, `SheetHeader` and `SheetViews` do not exist).

- [ ] **Step 9: Create `SheetViews.java`**

```java
package tomato.gui.glance.character;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import tomato.gui.kit.Card;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;

/** Small shared pieces of the character sheet tabs. EDT only. */
final class SheetViews {
    /** Canonical stat order: life, mana, atk, def, spd, dex, vit, wis. */
    static final String[] STATS = {"Life", "Mana", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    static final String[] SLOTS = {"Weapon", "Ability", "Armor", "Ring"};

    private SheetViews() {}

    static <T extends Component> T named(T component, String name) { component.setName(name); return component; }

    static JPanel clear(LayoutManager layout, Component... children) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        for (Component child : children) panel.add(child);
        return panel;
    }

    /** A transparent row that wraps at its width (ContentStyle.controls), so nothing is cut at 680 px or font 18. */
    static JPanel row(Component... children) {
        JPanel panel = ContentStyle.controls();
        panel.setOpaque(false);
        for (Component child : children) panel.add(child);
        return panel;
    }

    static JPanel beside(Component center, Component side, String edge, int gap) {
        JPanel panel = clear(new BorderLayout(gap, gap));
        panel.add(center, BorderLayout.CENTER);
        panel.add(side, edge);
        return panel;
    }

    static Card card(DisplayModeModel mode, String title, JComponent body, String name) { return named(new Card(mode).title(title).body(body), name); }

    /** The value at {@code index}, or null when it is unknown (-1) or missing. */
    static Integer at(List<Integer> values, int index) {
        return values == null || index >= values.size() || values.get(index) < 0 ? null : values.get(index);
    }

    /** A tab body that scrolls vertically, follows the viewport width (never sideways) and keeps its content at the top. */
    static JScrollPane scroll(JComponent body) {
        JScrollPane scroll = new JScrollPane(new Page(body));
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    private static final class Page extends JPanel implements Scrollable {
        Page(JComponent body) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(Tokens.S, 0, Tokens.S, 0));
            add(body, BorderLayout.NORTH);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height; }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
    }
}
```

- [ ] **Step 10: Create `SheetHeader.java`**

```java
package tomato.gui.glance.character;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.gui.kit.*;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * The sheet header's identity (spec §6.2): 54 px skin sprite, name, class · level · fame, and chips for maxed (hidden while
 * unknown), playing now, seasonal, marked dead and "Played <ago>" (the last time in game; "Seen <ago>" only for a character never
 * played, as the gallery's cards). That time is the accessible description, not part of the name, so assistive technology is
 * not re-announced as time passes. EDT only.
 */
final class SheetHeader extends JPanel {
    private final JLabel sprite = named(new JLabel(), "character-sheet-sprite");
    private final KitText name = named(new KitText("", Type.title(), Tokens.Role.TEXT), "character-sheet-name");
    private final KitText meta = named(KitText.caption(""), "character-sheet-meta");
    private final Chip maxed = named(new Chip("", Tokens.Tone.WARN), "character-sheet-maxed");
    private final Chip playing = named(new Chip("Playing now", Tokens.Tone.GOOD), "character-sheet-playing");
    private final Chip seasonal = named(new Chip("Seasonal", Tokens.Tone.INFO), "character-sheet-seasonal");
    private final Chip dead = named(new Chip("Marked dead", Tokens.Tone.BAD), "character-sheet-dead");
    private final Chip seen = named(new Chip("", Tokens.Tone.NEUTRAL), "character-sheet-seen");
    private SheetModel.Identity shown;
    private String shownSeen = "";

    SheetHeader() {
        super(new BorderLayout(Tokens.M, 0));
        setOpaque(false);
        setName("character-sheet-identity");
        add(sprite, BorderLayout.WEST);
        add(KitLayouts.stack(Tokens.XS, name, meta, row(maxed, playing, seasonal, dead, seen)), BorderLayout.CENTER);
        clearIdentity();
    }

    /** Called for every model and once a second; an unchanged identity re-reads only its relative "Played" text. */
    void apply(SheetModel.Identity identity) {
        String seenText = seen(identity);
        if (identity != null && identity.equals(shown) && seenText.equals(shownSeen)) return;
        shown = identity;
        shownSeen = seenText;
        if (identity == null) { clearIdentity(); return; }
        String kind = identity.className();
        sprite.setIcon(Sprites.sprite(identity.skin() != null && identity.skin() > 0 ? identity.skin() : identity.classId(), 54));
        sprite.setToolTipText(kind);
        name.setText(identity.name());
        meta.setText(kind + (identity.level() == null ? "" : " · Level " + identity.level()) + " · Fame " + identity.fame().display());
        meta.setToolTipText(identity.fame().tooltip());
        maxed.setVisible(identity.maxed() >= 0); // -1 is unknown: never shown as 0/8
        if (identity.maxed() >= 0) {
            maxed.setText(identity.maxed() + "/8 maxed");
            maxed.setTone(identity.maxed() >= 8 ? Tokens.Tone.GOOD : Tokens.Tone.WARN);
        }
        playing.setVisible(identity.playing());
        seasonal.setVisible(Boolean.TRUE.equals(identity.seasonal()));
        dead.setVisible(identity.dead());
        seen.setText(seenText);
        seen.setVisible(!seenText.isEmpty());
        getAccessibleContext().setAccessibleName(identity.name() + ", " + kind + (identity.level() == null ? "" : " level " + identity.level())
            + (identity.maxed() >= 0 ? ", " + identity.maxed() + " of 8 maxed" : "") + (identity.playing() ? ", playing now" : ""));
        getAccessibleContext().setAccessibleDescription(seenText.isEmpty() ? null : seenText);
        revalidate();
        repaint();
    }

    /** "Played 2 h ago" from the last time in game, "Seen …" from the last capture only when never played; "" while in game. */
    private static String seen(SheetModel.Identity identity) {
        if (identity == null || identity.playing()) return "";
        if (identity.lastPlayed() > 0) return "Played " + KitFormat.relative(identity.lastPlayed());
        return identity.lastSeen() > 0 ? "Seen " + KitFormat.relative(identity.lastSeen()) : "";
    }

    private void clearIdentity() {
        sprite.setIcon(null);
        name.setText("");
        meta.setText("");
        for (Chip chip : new Chip[]{maxed, playing, seasonal, dead, seen}) chip.setVisible(false);
    }
}
```

- [ ] **Step 11: Create `OverviewTab.java`**

```java
package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Overview (spec §6.2). It shows base-versus-cap bars with the live "+N" boost, then the potions each stat still needs
 * (with vault counts only when known). Below those come the four equipped slots, this class's exalt summary and, for a dead
 * character, its death annotation. The full stat table, with field evidence, is an Analyst-only Collapsible. EDT only.
 */
final class OverviewTab extends JPanel {
    private static final String NEEDS_RULE = "Life and Mana take one potion per 5 points, other stats one per point. "
        + "Vault counts add the regular vault, potion storage and gift chest (a greater potion counts as two); seasonal characters show none.";
    /** A vault count older than this is dimmed as stale (spec §5.7). */
    static final long VAULT_STALE_MILLIS = 24 * 3_600_000L;
    private final StatBar[] bars = new StatBar[8];
    private final KitText[] values = new KitText[8], boosts = new KitText[8], tiers = new KitText[4];
    private final ItemSlot[] gear = new ItemSlot[4];
    private final JPanel needs = named(row(), "character-overview-needs");
    private final KitText exalts = named(KitText.body(""), "character-overview-exalts");
    private final KitText deathText = named(KitText.body(""), "character-overview-death-text");
    private final Card death;
    private final DefaultTableModel table = new DefaultTableModel(new String[]{"Stat", "Base", "Cap", "Potions to max", "Field evidence"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final Collapsible statTable;
    private final IntFunction<String> tierOf;
    private SheetModel shown;
    private String shownAge = "";

    OverviewTab(DisplayModeModel mode) { this(mode, ItemTiers::label); }

    /** {@code tierOf}: "UT", "ST", "T12" or "" for an item id (ItemTiers.label in the app). */
    OverviewTab(DisplayModeModel mode, IntFunction<String> tierOf) {
        super(new BorderLayout());
        this.tierOf = tierOf;
        setOpaque(false);
        setName("character-overview");
        JPanel grid = ContentStyle.responsiveGrid(4, 150, Tokens.S);
        grid.setOpaque(false);
        for (int i = 0; i < 8; i++) {
            bars[i] = named(new StatBar(), "character-overview-bar-" + i);
            bars[i].getAccessibleContext().setAccessibleName(CharacterJournal.STATS[i]);
            values[i] = named(KitText.caption(""), "character-overview-value-" + i);
            boosts[i] = named(new KitText("", Type.caption(), Tokens.Role.ACCENT_TEXT), "character-overview-boost-" + i);
            JPanel numbers = clear(new FlowLayout(FlowLayout.TRAILING, Tokens.XS, 0), values[i], boosts[i]);
            grid.add(beside(bars[i], beside(numbers, KitText.caption(STATS[i]), BorderLayout.WEST, Tokens.XS), BorderLayout.NORTH, 2));
        }
        needs.setToolTipText(NEEDS_RULE);
        JPanel slots = clear(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
        for (int i = 0; i < 4; i++) {
            gear[i] = named(new ItemSlot(32), "character-overview-slot-" + i);
            tiers[i] = named(KitText.caption(SLOTS[i]), "character-overview-tier-" + i);
            tiers[i].setHorizontalAlignment(SwingConstants.CENTER);
            slots.add(beside(gear[i], tiers[i], BorderLayout.SOUTH, 2));
        }
        JPanel pair = ContentStyle.responsiveGrid(2, 260, Tokens.M);
        pair.setOpaque(false);
        pair.add(card(mode, "Gear", slots, "character-overview-gear"));
        pair.add(card(mode, "Class exalts", exalts, "character-overview-class-exalts"));
        death = card(mode, "Death", deathText, "character-overview-death");
        JTable rows = named(new JTable(table), "character-stat-rows");
        ContentStyle.table(rows, ContentStyle.Density.DENSE);
        rows.getAccessibleContext().setAccessibleName("Stat table: base, cap, potions to max and field evidence");
        statTable = named(new Collapsible("character-stat-table", "Stat table", ContentStyle.tableScroll(rows, 8), false), "character-stat-table");
        add(KitLayouts.stack(Tokens.M, card(mode, "Stats", KitLayouts.stack(Tokens.S, grid, needs), "character-overview-stats"), pair, death, statTable),
            BorderLayout.NORTH);
        // Raw provenance is diagnostic (spec §3.2): Analyst mode only.
        mode.bind(this, value -> statTable.setVisible(value == DisplayModeModel.Mode.ANALYST));
        apply(null);
    }

    /**
     * EDT. A null model (loading, or the character is not in the journal) clears everything. A model equal to the shown one is
     * skipped while the vault count's relative age reads the same (the presenter re-applies once a second).
     */
    void apply(SheetModel model) {
        String age = vaultAge(model);
        if (model != null && model.equals(shown) && age.equals(shownAge)) return;
        shown = model;
        shownAge = age;
        SheetModel.Stats stats = model == null ? null : model.stats();
        boolean playing = model != null && model.identity().playing();
        for (int i = 0; i < 8; i++) {
            Integer base = stats == null ? null : at(stats.base(), i), cap = stats == null ? null : at(stats.caps(), i);
            int boost = playing && base != null ? stats.boosts().get(i) : 0; // the boost describes the character in game right now
            bars[i].set(base, cap);
            values[i].setText(base == null ? DisplayFormat.UNAVAILABLE : cap == null ? String.valueOf(base) : base + "/" + cap);
            boosts[i].setText(boost > 0 ? "+" + boost : "");
            boosts[i].setVisible(boost > 0);
            boosts[i].setToolTipText(boost > 0 ? CharacterJournal.STATS[i] + " is " + (base + boost) + " right now with gear and effects" : null);
        }
        needs(stats);
        for (int i = 0; i < 4; i++) {
            int id = model == null ? -1 : model.gear().slots().get(i);
            String tier = id > 0 ? Objects.toString(tierOf.apply(id), "") : "";
            if (id > 0) gear[i].setItem(id, tier); else if (id == 0) gear[i].setEmpty(); else gear[i].setUnknown();
            tiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);
        }
        SheetModel.Exalts classExalts = model == null ? null : model.exalts();
        boolean known = classExalts != null && classExalts.known();
        exalts.setText(known ? classExalts.summary() : DisplayFormat.UNAVAILABLE);
        exalts.setToolTipText(known ? "Exaltation tiers for this class; the Exalts tab lists every stat"
            : "Exalt progress arrives when capture reads your character list");
        SheetModel.Death dead = model == null ? null : model.death();
        death.setVisible(dead != null);
        if (dead != null) deathText.setText("Marked dead " + (dead.markedAt() > 0 ? KitFormat.relative(dead.markedAt()) : "at an unknown time")
            + (dead.occurredAt() == null ? "" : " · occurred " + DisplayFormat.formatTimestamp(dead.occurredAt()))
            + (dead.notes().isBlank() ? "" : " · " + dead.notes().strip().split("\\R", 2)[0]));
        table.setRowCount(0);
        if (stats != null) for (int i = 0; i < 8; i++) {
            Integer base = at(stats.base(), i), cap = at(stats.caps(), i);
            int need = stats.needed().get(i);
            table.addRow(new Object[]{CharacterJournal.STATS[i], base == null ? "Unknown" : base, cap == null ? "Unknown" : cap,
                need < 0 ? "Unknown" : need == 0 ? "Maxed" : need, stats.evidence().get(i)});
        }
        revalidate();
        repaint();
    }

    private void needs(SheetModel.Stats stats) {
        needs.removeAll();
        if (stats != null) {
            if (stats.maxed() == 8) needs.add(named(new KitText("All 8 stats maxed", Type.body(), Tokens.Role.GOOD), "character-overview-maxed"));
            for (int i = 0; i < stats.needs().size(); i++) needs.add(named(need(stats, i), "character-overview-need-" + i));
            if (stats.unknown() > 0) needs.add(named(KitText.caption("Potions unknown for " + stats.unknown()
                + (stats.unknown() == 1 ? " stat" : " stats") + " (base stat or cap not captured)"), "character-overview-needs-unknown"));
        }
        needs.setVisible(needs.getComponentCount() > 0);
        needs.revalidate();
        needs.repaint();
    }

    /** The vault count's relative age as the needs show it; "" without a vault count. */
    private static String vaultAge(SheetModel model) {
        if (model == null || model.stats().vault() == null) return "";
        long at = model.stats().vaultObservedAt();
        return at > 0 ? KitFormat.relative(at) : "time unknown";
    }

    /** "DEF needs 5 · 3 in vault (2 h ago)": a vault count shows its age and is dimmed as stale once a day old (spec §5.7). */
    private static KitText need(SheetModel.Stats stats, int index) {
        String line = stats.needs().get(index);
        if (stats.vault() == null) return KitText.body(line);
        long at = stats.vaultObservedAt();
        boolean stale = at <= 0 || System.currentTimeMillis() - at > VAULT_STALE_MILLIS;
        KitText text = new KitText(line + " (" + (at > 0 ? KitFormat.relative(at) : "time unknown") + ")", Type.body(),
            stale ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT);
        text.setToolTipText((at > 0 ? "Vault counted " + DisplayFormat.formatTimestamp(at) : "When the vault was counted is unknown")
            + (stale ? "; open the vault with capture on to update it" : ""));
        return text;
    }
}
```

- [ ] **Step 12: Create `SheetPresenter.java`**

```java
package tomato.gui.glance.character;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.planning.PlanningMetadata;

/**
 * Feeds the character sheet (spec §3.1: glance screens own no data). It rebuilds when the sheet opens a key and, while the sheet
 * shows, whenever a cheap token moved (CharacterSheet.refresh checks once a second): the key, the journal and live-character
 * revisions, the loaded definitions and the dungeon mapping. The "character-sheet" thread reads the journal's deep copies (reused
 * while the journal's revision is unchanged) and runs SheetModelBuilder. The EDT applies a result only while it is the newest
 * request and its key is still the sheet's, so a late result for another character is dropped. A failed build is reported in the
 * sheet (spec §7), never swallowed, and tried again on the next refresh.
 */
final class SheetPresenter {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "character-sheet"); thread.setDaemon(true); return thread;
    });
    private final CharacterSheet sheet;
    private final SheetContext context;
    private final SheetHeader header = new SheetHeader();
    private final OverviewTab overview;
    private String key;
    private Token token;
    private long generation;
    private SheetModel model;
    /** The build thread's last journal read, reused while the key and the journal revision are unchanged (build thread only). */
    private Read lastRead;

    private record Token(String key, long journal, long live, RosterDefinitions definitions, PlanningMetadata planning) {}
    /** One journal read at one revision: the character's record (null when the journal lacks it) and the lists Goals shows. */
    private record Read(String key, long revision, CharacterRecord record, List<CharacterRecord> records, List<AccountRecord> accounts) {}
    /** One build for {@code key}. */
    private record Built(String key, SheetModel model, Read read, RosterDefinitions definitions) {}

    SheetPresenter(CharacterSheet sheet, SheetContext context) {
        this.sheet = sheet;
        this.context = context;
        overview = new OverviewTab(context.mode());
        sheet.setIdentity(header);
        sheet.setTab("overview", SheetViews.scroll(overview));
    }

    /** EDT: the sheet now shows {@code key}; rebuild at once. A new key clears what is shown until its own result applies. */
    void open(String key) {
        if (!Objects.equals(key, this.key)) show(null); // nothing of the previous character stays on screen while this one loads
        this.key = key;
        request();
    }

    /** EDT: the model last applied, or null (loading, failed, or a key the journal lacks). */
    SheetModel model() { return model; }

    /** EDT, once a second while the sheet shows: rebuild when a token moved, else re-read only the relative times. */
    void refresh() {
        if (key == null) return;
        if (!token().equals(token)) request();
        else times();
    }

    /** Relative times ("Played …", the vault's age) change without a new model. */
    private void times() {
        if (model == null) return;
        header.apply(model.identity());
        overview.apply(model); // re-reads only the vault age
    }

    private Token token() {
        LiveCharacter live = live();
        return new Token(key, context.journal().revision(), live.revision(), context.definitions().get(), PlanningMetadata.current());
    }

    private void request() {
        token = token();
        long requested = ++generation;
        String target = key;
        CharacterJournal journal = context.journal();
        LiveCharacter live = live();
        WORKER.execute(() -> {
            Built built = null;
            RuntimeException failure = null;
            try { built = build(target, journal, live); }
            catch (RuntimeException e) { failure = e; }
            Built result = built;
            RuntimeException failed = failure;
            SwingUtilities.invokeLater(() -> {
                // Only the newest request for the key the sheet still shows applies; a late result for another character is dropped.
                if (requested != generation || !Objects.equals(target, key)) return;
                if (failed != null) { token = null; sheet.failed(failed); return; } // the next refresh tries again
                apply(result);
            });
        });
    }

    /** The build thread: one journal read (reused while the revision is unchanged) and the model, over deep copies. */
    private Built build(String target, CharacterJournal journal, LiveCharacter live) {
        long now = System.currentTimeMillis();
        RosterDefinitions definitions = context.definitions().get();
        Read read = lastRead;
        synchronized (journal) {
            long revision = journal.revision();
            if (read == null || !Objects.equals(read.key(), target) || read.revision() != revision)
                read = new Read(target, revision, target == null ? null : journal.characterCopy(target), journal.characters(), journal.accounts());
        }
        lastRead = read;
        AccountRecord account = null;
        if (read.record() != null) for (AccountRecord a : read.accounts()) if (a.key.equals(read.record().account)) account = a;
        return new Built(target, SheetModelBuilder.build(read.record(), account, SheetModelBuilder.inGame(live, now), definitions, now), read, definitions);
    }

    private LiveCharacter live() { return context.data().liveCharacter; }

    /** EDT: shows a build for the key the sheet still shows. */
    private void apply(Built built) {
        Read read = built.read();
        sheet.loaded(built.key(), read.record(), read.records(), read.accounts(), built.definitions(), read.revision());
        show(built.model());
    }

    /** EDT: the header and the tabs this presenter owns show {@code value}; null (loading, or not in the journal) clears them. */
    private void show(SheetModel value) {
        model = value;
        header.apply(value == null ? null : value.identity());
        overview.apply(value);
    }
}
```

- [ ] **Step 13: Hook the presenter into `CharacterSheet` and remove the moved stat table**

**Replace** Task 4's `src/main/java/tomato/gui/glance/character/CharacterSheet.java` with the file below. What changes, and why:
- `SheetHeader`, which the presenter installs through `setIdentity`, replaces the title line and the sprite. The Overview tab replaces the moved stat table; its Analyst stat table keeps the rows.
- The journal is read on the presenter's build thread instead of the EDT: `loaded(...)` shows each read and ignores one made for another key.
  - `open` of another key clears what is shown and says "Loading…" until that key's read applies.
  - `refresh` asks the presenter for a new read when a token moved, then refreshes the snapshot age.
  - `failed` shows a warn banner (spec §7).
- Kept from Task 4: the back link, Mark dead / Restore alive (acting only while `ready()`), the notes draft rules, the conditional Death annotation tab, the Analyst-only provenance, the storage banner, the moved Gear and Exalts tables (Tasks 6 and 7 replace them), every public method and every component name.
```java
package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterDeathPanel;
import tomato.gui.character.CharacterEquipmentPanel;
import tomato.gui.character.CharacterPlanningPanel;
import tomato.gui.kit.Banner;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.Navigator;
import tomato.gui.stats.Formatters;

/**
 * One character's full page on the Characters Roster tab: a header (back link, identity, Mark dead or Restore alive, status
 * banners and the snapshot evidence) over {@code CustomizableTabs("character")}. Overview, Gear and Exalts are slots whose
 * content SheetPresenter sets ({@link #setTab}); Death annotation shows only while the character is marked dead (spec §6.2).
 * - SheetPresenter reads the journal and builds the model off the EDT; {@link #loaded} shows each read. Until the read of the
 *   opened key arrives the sheet says "Loading…" and nothing acts ({@link #ready}); a failed build shows a warn banner (spec §7).
 * - Snapshot evidence and the tab hint are provenance: Analyst mode only (spec §3.2).
 * - A notes draft is saved when another character opens and whenever the sheet hides (another card, Back, another Characters
 *   tab, closing the workspace); refreshes never replace it.
 * - An unreadable journal or a failed save shows a warn banner.
 * The refresh timer runs only while the sheet shows. EDT only.
 */
public final class CharacterSheet extends JPanel {
    /** Title of the unavailable state, for a key the journal does not hold. */
    public static final String UNAVAILABLE = "This character is not in the journal";
    /** The static loading line (spec §5.8: no animated loaders). */
    static final String LOADING = "Loading…";
    private static final String TABS_CARD = "tabs", UNAVAILABLE_CARD = "unavailable";

    private final SheetContext context;
    private final CustomizableTabs tabs = new CustomizableTabs("character");
    private final Map<String, JPanel> slots = new HashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final KitButton back = KitButton.ghost("‹ Characters");
    private final JTextArea seen = ContentStyle.wrappingText(" ");
    private final JTextArea hint = ContentStyle.wrappingText("Base stats exclude captured boosts. Caps use local game assets; missing values stay unknown.");
    /** The header row: SheetHeader's identity block (setIdentity) and Mark dead / Restore alive. */
    private final JPanel identity = new JPanel(new BorderLayout(8, 0));
    /** Marking a character dead is destructive; Restore alive takes its place while the character is marked dead. */
    private final KitButton death = KitButton.danger("Mark dead"), restore = KitButton.secondary("Restore alive");
    private final KitButton saveNotes = KitButton.secondary("Save notes");
    /** "Loading…" until the opened character's read arrives; a failed build in the warn tone. */
    private final Banner status = new Banner("character-sheet-status");
    /** The journal cannot be read, or its last save failed. */
    private final Banner storage = new Banner("character-sheet-storage");
    private final JTextArea notes = new JTextArea(3, 30);
    private final DefaultTableModel exaltModel = model("Stat", "Level", "Completions", "Next tier");
    private final DefaultTableModel metadataModel = model("Field", "Value", "Field evidence");
    private final JScrollPane exaltTable;
    private final CharacterEquipmentPanel equipment = new CharacterEquipmentPanel();
    private final CharacterPlanningPanel planning;
    private final CharacterDeathPanel deathPanel;
    private final javax.swing.Timer timer;
    /** Header identity and the Overview, Gear, Exalts and Build tabs (Tasks 5–8), built off the EDT. */
    private final SheetPresenter presenter;
    private Runnable backAction = () -> { };
    /** {@code loadedKey}: the key whose journal read this sheet shows; the actions wait until it equals {@code key}. */
    private String key, filledKey, loadedKey;
    private CharacterRecord record;
    private List<CharacterRecord> records = Collections.emptyList();
    private List<AccountRecord> accounts = Collections.emptyList();
    private RosterDefinitions definitions = RosterDefinitions.empty();
    private long revision = -1;

    public CharacterSheet(SheetContext context) {
        super(new BorderLayout());
        this.context = Objects.requireNonNull(context, "context");
        setName("character-sheet");
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        planning = new CharacterPlanningPanel(context.plans());
        deathPanel = new CharacterDeathPanel(context.journal());
        back.setName("character-sheet-back"); back.setToolTipText("Back to the character list");
        back.getAccessibleContext().setAccessibleName("Back to Characters");
        back.addActionListener(e -> backAction.run());
        death.setName("character-sheet-death"); restore.setName("character-sheet-restore"); saveNotes.setName("character-notes-save");
        restore.setVisible(false);
        seen.setName("character-snapshot-evidence"); hint.setName("character-sheet-hint");
        status.setVisible(false);
        storage.setTone(Tokens.Tone.WARN); storage.setVisible(false);
        JPanel backRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); backRow.add(back);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, Tokens.XS, 0)); actions.add(death); actions.add(restore);
        identity.add(actions, BorderLayout.EAST); // SheetHeader takes the center (setIdentity)
        JPanel header = new JPanel(new BorderLayout(0, 4)); header.setName("character-sheet-header");
        header.add(backRow, BorderLayout.NORTH); header.add(identity, BorderLayout.CENTER);
        header.add(KitLayouts.stack(Tokens.XS, status, storage, seen), BorderLayout.SOUTH);

        exaltTable = ContentStyle.tableScroll(table(exaltModel), 3);
        JPanel notePanel = new JPanel(new BorderLayout(8, 8)); notes.setLineWrap(true); notes.setWrapStyleWord(true);
        notes.setName("character-notes"); notes.setFont(ContentStyle.body()); notes.getAccessibleContext().setAccessibleName("Character notes");
        JScrollPane noteScroll = new JScrollPane(notes) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, notes.getFontMetrics(notes.getFont()).getHeight() * 3 + insets.top + insets.bottom);
            }
        };
        notePanel.add(noteScroll, BorderLayout.CENTER); notePanel.add(saveNotes, BorderLayout.SOUTH);
        tabs.add("overview", "Overview", slot("overview", new JPanel())) // SheetPresenter sets the Overview tab
            .add("gear", "Gear", slot("gear", equipment))
            .add("exalts", "Exalts", slot("exalts", exaltTable))
            .add("goals", "Goals", planning)
            .add("notes", "Notes", notePanel)
            // Raw field provenance is diagnostic: Analyst mode only (spec §3.2); the saved order still includes it.
            .addAnalyst("evidence", "Snapshot evidence", ContentStyle.tableScroll(table(metadataModel), 3))
            // Only while the character is marked dead (spec §6.2); skipping it never rewrites the saved order (spec §4.4).
            .addWhen("death", "Death annotation", deathPanel, () -> record != null && record.dead);
        JTabbedPane strip = tabs.component(); strip.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        hint.setToolTipText("Potion estimates use +5 Life/Mana and +1 other stats. Exalts are account/class progress shared across characters.");
        JPanel content = new JPanel(new BorderLayout(0, 8)) {
            // Tab chrome and usable rows must fit after font and width changes; the page scrolls instead of squeezing them.
            @Override public Dimension getMinimumSize() { return new Dimension(0, strip.getMinimumSize().height + (hint.isVisible() ? hint.getPreferredSize().height + 8 : 0)); }
        };
        content.add(strip, BorderLayout.CENTER); content.add(hint, BorderLayout.SOUTH);
        KitButton showAll = KitButton.secondary("Show all characters"); showAll.setName("character-sheet-unavailable-back");
        showAll.addActionListener(e -> backAction.run());
        EmptyState missing = new EmptyState(UNAVAILABLE, "The journal has no saved character with this reference. Choose one from the character list.", showAll);
        missing.setName("character-sheet-unavailable");
        body.add(content, TABS_CARD); body.add(missing, UNAVAILABLE_CARD);
        JScrollPane page = ContentStyle.page(header, body, null); page.setName("character-sheet-scroll");
        page.getAccessibleContext().setAccessibleName("Character sheet; scroll for tabs and actions at large text sizes");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{back, death, restore, saveNotes}) control.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); }
        });
        death.addActionListener(e -> mark(true));
        restore.addActionListener(e -> mark(false));
        saveNotes.addActionListener(e -> {
            if (!ready() || record == null) return;
            context.journal().notes(record.key, notes.getText()); record.notes = notes.getText(); refresh();
        });
        // Provenance is diagnostic (spec §3.2): Simple mode shows neither the snapshot evidence nor the tab hint.
        context.mode().bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            seen.setVisible(analyst); hint.setVisible(analyst); revalidate(); repaint();
        });
        timer = new javax.swing.Timer(1000, e -> refresh());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            // The timer runs only while the sheet shows. Hiding it (another card, Back, another Characters tab, closing) keeps the draft.
            if (isShowing()) { timer.start(); refresh(); } else { timer.stop(); saveDraft(); }
        });
        fill();
        presenter = new SheetPresenter(this, context); // after every tab exists: it sets the identity and the tabs it owns
    }

    @Override public void removeNotify() { saveDraft(); timer.stop(); super.removeNotify(); }

    /**
     * Shows one character. A different key first saves the previous character's changed notes and clears everything shown, so
     * nothing of that character stays on screen or acts while this one loads. A non-null tab is explicit navigation: it is shown
     * if hidden, then selected. A key the journal does not hold shows the unavailable state once its read arrives.
     */
    public void open(String key, String tab) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Open the character sheet on the EDT");
        if (!Objects.equals(this.key, key)) {
            saveDraft();
            this.key = key;
            record = null; loadedKey = null;
            fill();
            tabs.refreshConditions();
            shown();
            status.setTone(Tokens.Tone.NEUTRAL); status.setText(LOADING); status.setVisible(true);
        }
        presenter.open(key);
        if (tab != null) { tabs.show(tab); tabs.select(tab); }
    }
    public String key() { return key; }
    /** True once the sheet shows the journal's read of its current key: that character, or its unavailable state. */
    public boolean ready() { return key != null && key.equals(loadedKey); }
    public String selectedTab() { return tabs.selectedId(); }
    public CustomizableTabs tabs() { return tabs; }
    /** Selects a tab without showing it: startup and saved-state restore keep a hidden tab hidden. */
    public void selectTab(String id) { tabs.select(id); }
    /** What the "‹ Characters" link and the unavailable state's button do; the Roster tab sets it on every open. */
    public void onBack(Runnable action) { backAction = Objects.requireNonNull(action); }
    public void bindNavigator(Navigator navigator) { deathPanel.bindNavigator(navigator); }
    /** Moves keyboard focus to the back link, the sheet's first control. */
    public void focusBackLink() { back.requestFocusInWindow(); }

    /**
     * Saves the shown character's changed notes to the journal: before another character opens, whenever the sheet hides
     * (another card, Back, the Characters page's other tabs) and when the workspace closes. A draft equal to the saved notes,
     * or one typed for another character, saves nothing. EDT.
     */
    public void saveDraft() {
        if (filledKey == null || record == null || !filledKey.equals(record.key) || Objects.equals(record.notes, notes.getText())) return;
        context.journal().notes(filledKey, notes.getText()); record.notes = notes.getText();
    }

    /** Replaces a slot tab's content (overview, gear, exalts); its id, title, order and hidden state are unchanged. */
    void setTab(String id, JComponent content) {
        JPanel slot = slots.get(id);
        if (slot == null) throw new IllegalArgumentException("Not a replaceable sheet tab: " + id);
        slot.removeAll(); slot.add(content, BorderLayout.CENTER); slot.revalidate(); slot.repaint();
    }
    /** Puts the header's identity block (SheetHeader) beside Mark dead; the back link, banners and snapshot evidence stay. */
    void setIdentity(JComponent value) { identity.add(value, BorderLayout.CENTER); identity.revalidate(); identity.repaint(); }
    /** The moved 28-slot equipment table (Task 6 keeps it in Analyst). */
    CharacterEquipmentPanel equipmentPanel() { return equipment; }
    /** The moved class-exalts table (Task 7 replaces it). */
    JComponent exaltTable() { return exaltTable; }

    /** Asks the presenter for a new read when a token moved, then advances the snapshot age. EDT; skipped while hidden. */
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        if (key != null && (isShowing() || !isDisplayable())) { presenter.refresh(); shown(); }
    }

    private JPanel slot(String id, JComponent content) {
        JPanel slot = new JPanel(new BorderLayout()); slot.setName("character-tab-" + id);
        slot.add(content, BorderLayout.CENTER); slots.put(id, slot);
        return slot;
    }

    /**
     * Shows one journal read the presenter made for {@code forKey} off the EDT; a read for any other key is ignored. The tables,
     * notes and actions refill only when the character, the journal revision or the definitions changed. The Death annotation
     * tab follows the dead flag.
     */
    void loaded(String forKey, CharacterRecord read, List<CharacterRecord> all, List<AccountRecord> known, RosterDefinitions defs, long at) {
        if (!Objects.equals(forKey, key)) return;
        boolean changed = !Objects.equals(forKey, loadedKey) || at != revision || defs != definitions;
        record = read; records = all; accounts = known; definitions = defs; revision = at; loadedKey = forKey;
        status.setVisible(false);
        if (changed) fill();
        tabs.refreshConditions();
        shown();
    }

    /** A build failed (spec §7: never silent): a warn banner; nothing acts until a later build shows this character. */
    void failed(RuntimeException failure) {
        String reason = failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
        status.setTone(Tokens.Tone.WARN); status.setText("This character could not be shown: " + reason); status.setVisible(true);
    }

    /** On every refresh: the snapshot age (time advances after capture stops), the storage warning, the death panel and Goals. */
    private void shown() {
        refreshTimeEvidence();
        String problem = context.journal().storageProblem();
        storage.setText(problem == null ? "" : problem);
        storage.setVisible(problem != null);
        deathPanel.showRecord(record);
        planning.refresh(records, accounts, definitions);
    }

    /** Mark dead or Restore alive, then wait for the re-read that shows the new state: a second click never acts on the old one. */
    private void mark(boolean dead) {
        if (!ready() || record == null) return;
        context.journal().markDead(record.key, dead);
        loadedKey = null;
        actions();
        refresh();
    }

    /** Mark dead or Restore alive (whichever applies) and Save notes act only on the character this sheet has loaded. */
    private void actions() {
        boolean dead = record != null && record.dead, acts = ready() && record != null;
        death.setVisible(!dead); restore.setVisible(dead);
        death.setEnabled(acts); restore.setEnabled(acts); saveNotes.setEnabled(acts); notes.setEnabled(acts);
    }

    private void fill() {
        CharacterRecord r = record;
        exaltModel.setRowCount(0); metadataModel.setRowCount(0);
        equipment.showRecord(r, definitions);
        actions();
        cards.show(body, ready() && r == null ? UNAVAILABLE_CARD : TABS_CARD);
        // A refresh never replaces an unsaved draft; only a different character does.
        if (r == null) { notes.setText(""); filledKey = null; }
        else if (!r.key.equals(filledKey)) { notes.setText(r.notes); filledKey = r.key; }
        if (r == null) {
            seen.setToolTipText(null);
            return;
        }
        restore.setText(r.observedAgainAt > 0 ? "Observed again—restore?" : "Restore alive");
        seen.setToolTipText(r.source);
        String[] fields = {"class", "level", "skin", "fame", "seasonal", "created"};
        Object[] values = {r.className, r.level, r.skin, r.fame, r.seasonal == null ? null : r.seasonal ? "Seasonal" : "Regular", r.created};
        for (int i = 0; i < fields.length; i++) metadataModel.addRow(new Object[]{fields[i], unknown(values[i]), evidence(r, fields[i], values[i] != null)});
        int[] exalt = null;
        for (AccountRecord a : accounts) if (a.key.equals(r.account)) exalt = a.exalts.get(r.classId);
        for (int i = 0; i < 8; i++) {
            Integer count = exalt == null ? null : exalt[CharacterJournal.EXALT_ORDER[i]];
            exaltModel.addRow(new Object[]{CharacterJournal.STATS[i], count == null ? "Unknown" : CharacterJournal.exaltLevel(count) + "/5", unknown(count), count == null ? "Unknown" : next(count)});
        }
    }

    /** Time advances even after capture stops; refresh just this text, not tables or editable drafts. */
    private void refreshTimeEvidence() {
        CharacterRecord r = record;
        String text = " ";
        if (r != null) {
            long age = r.lastSeen <= 0 ? -1 : Math.max(0, (context.clock().getAsLong() - r.lastSeen) / 1000);
            text = "Last observed alive " + date(r.lastObservedAlive) + "  •  Roster received " + date(r.rosterReceivedAt)
                + "\nSnapshot update age: " + (age < 0 ? "Unknown" : age + "s") + " · "
                + Arrays.stream(r.stats).filter(Objects::nonNull).count() + "/8 known stats · "
                + Arrays.stream(r.equipment).filter(Objects::nonNull).count() + "/28 known slots (may be retained)"
                + (r.dead ? "\nMarked dead manually " + date(r.diedAt) + "; preserved snapshot."
                    + (r.observedAgainAt > 0 ? " Reported again " + date(r.observedAgainAt) + ". Restore explicitly to accept updates." : "") : "");
        }
        if (!seen.getText().equals(text)) seen.setText(text);
    }

    private static String evidence(CharacterRecord r, String key, boolean known) {
        if (!known) return "Not captured";
        FieldCapture field = r.fields.get(key);
        if (field == null) return "Legacy / provenance unknown";
        return field.source + " · " + date(field.at) + (field.at > 0 && field.at < r.lastSeen ? " · Retained from earlier observation" : "");
    }
    private static String next(int count) { for (int goal : new int[]{5, 15, 30, 50, 75}) if (count < goal) return (goal - count) + " to " + goal; return "Complete"; }
    private static Object unknown(Object value) { return value == null ? "Unknown" : value; }
    private static String date(long time) { return time <= 0 ? "Unknown" : Formatters.formatTimestamp(time); }
    private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int col) { return false; }
        @Override public Class<?> getColumnClass(int col) { for (int i = 0; i < getRowCount(); i++) { Object v = getValueAt(i, col); if (v != null) return v instanceof Number ? v.getClass() : String.class; } return String.class; }
    }; }
    private static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model); ContentStyle.table(t, ContentStyle.Density.DENSE);
        t.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                int row = Math.max(0, t.getSelectedRow()), column = Math.max(0, t.getSelectedColumn());
                ContentStyle.reveal(t, t.getCellRect(row, column, true));
            }
        });
        t.getTableHeader().setReorderingAllowed(false); return t;
    }
}
```

- [ ] **Step 14: Migrate Task 4's sheet tests to the header, the Overview tab and the asynchronous sheet**

**Replace** `src/test/java/tomato/gui/glance/character/CharacterSheetTest.java` with the file below. Reasons: the sheet now reads the journal off the EDT, so each test waits (`SnapshotTestSupport.await`, which runs the EDT while it waits) before it acts on a character; the header replaces the title line. Kept: every assertion of Task 4's seven tests, with the title checks now reading `character-sheet-name` ("… #7") and `character-sheet-meta` ("Level 20"), and the dead state checked on `character-sheet-dead`. **Added beside:** Loading until the opened key's read applies, a late result for another key dropped, Mark dead waiting for the re-read, and a failed build's warn banner.
```java
package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class CharacterSheetTest {
    private static final String ORDER = "ui.tabs.character";
    private static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;

    @Before public void remember() throws Exception {
        savedOrder = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, "");
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
    }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    private CharacterJournal journal(String file, int... ids) { return journal(new CharacterJournal(temp.getRoot().toPath().resolve(file)), ids); }
    private static CharacterJournal journal(CharacterJournal journal, int... ids) {
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id : ids) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.level = 20; c.receivedAt = 1000;
            c.supplied("class"); c.supplied("level"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static CharacterSheet sheet(CharacterJournal journal) {
        return new CharacterSheet(new SheetContext(new TomatoData(), journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()));
    }
    /** Opens {@code key} and waits (running the EDT) until the sheet shows its read: the model is built off the EDT. */
    private static void open(CharacterSheet sheet, String key, String tab) { sheet.open(key, tab); await(sheet::ready); }
    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    @Test public void tabsKeepTheirIdsAndOrderSnapshotEvidenceIsAnalystOnlyAndSlotsAreReplaceable() throws Exception {
        try (CharacterJournal journal = journal("tabs.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                CharacterSheet sheet = sheet(journal);
                assertEquals("character-sheet", sheet.getName());
                List<String> order = Arrays.asList("overview", "gear", "exalts", "goals", "notes", "evidence", "death");
                assertEquals(order, sheet.tabs().order());
                JTabbedPane tabs = sheet.tabs().component();
                assertEquals("character-tabs", tabs.getName());
                assertEquals("Death annotation shows only for a character marked dead", Arrays.asList("Overview", "Gear", "Exalts", "Goals", "Notes", "Snapshot evidence"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
                open(sheet, ACCOUNT + ":1", "notes"); assertEquals("notes", sheet.selectedTab());
                open(sheet, ACCOUNT + ":1", "goals"); assertEquals("An explicit tab is selected", "goals", sheet.selectedTab());
                JPanel replacement = new JPanel();
                sheet.setTab("overview", replacement);
                assertEquals("A replaced slot keeps its id and place", order, sheet.tabs().order());
                assertTrue(SwingUtilities.isDescendingFrom(replacement, tabs.getComponentAt(0)));
                try { sheet.setTab("notes", new JPanel()); fail("Only overview, gear and exalts are slots"); } catch (IllegalArgumentException expected) { }
            });
        }
    }

    @Test public void headerHasTheBackLinkIdentityAndMarkDeadShowsTheDeathTab() throws Exception {
        try (CharacterJournal journal = journal("header.json", 7)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                AtomicInteger backs = new AtomicInteger();
                sheet.onBack(backs::incrementAndGet);
                open(sheet, ACCOUNT + ":7", null);
                assertEquals(ACCOUNT + ":7", sheet.key());
                AbstractButton back = named(sheet, "character-sheet-back", AbstractButton.class);
                assertEquals("‹ Characters", back.getText());
                back.doClick(); assertEquals(1, backs.get());
                assertTrue("The fixture has no name: the header reads \"<class> #7\"", named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#7"));
                assertTrue(named(sheet, "character-sheet-meta", JLabel.class).getText().contains("Level 20"));
                AbstractButton death = named(sheet, "character-sheet-death", AbstractButton.class), restore = named(sheet, "character-sheet-restore", AbstractButton.class);
                assertEquals("Mark dead", death.getText()); assertTrue(death.isVisible()); assertFalse(restore.isVisible());
                assertFalse("An alive character has no Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                death.doClick();
                assertTrue(journal.characterCopy(ACCOUNT + ":7").dead);
                assertFalse("Nothing acts again until the re-read shows the new state", sheet.ready());
                await(sheet::ready);
                assertFalse(death.isVisible()); assertTrue(restore.isVisible()); assertEquals("Restore alive", restore.getText());
                assertTrue(named(sheet, "character-sheet-dead", JComponent.class).isVisible());
                assertTrue("Marking dead shows the Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                assertEquals("…without rewriting the saved order", "", PropertiesManager.getProperty(ORDER));
                restore.doClick();
                assertFalse(journal.characterCopy(ACCOUNT + ":7").dead);
                await(sheet::ready);
                assertTrue(death.isVisible()); assertEquals("Mark dead", death.getText());
                assertFalse(sheet.tabs().visibleIds().contains("death"));
            });
        }
    }

    @Test public void anUnknownKeyShowsTheUnavailableStateAndAKnownOneTheTabs() throws Exception {
        try (CharacterJournal journal = journal("unknown.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":404", null);
                EmptyState missing = named(sheet, "character-sheet-unavailable", EmptyState.class);
                assertTrue(missing.isVisible());
                assertEquals(CharacterSheet.UNAVAILABLE, missing.getAccessibleContext().getAccessibleName());
                assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                assertEquals(" ", named(sheet, "character-snapshot-evidence", JTextArea.class).getText());
                open(sheet, ACCOUNT + ":1", null);
                assertFalse(missing.isVisible());
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void aNotesDraftSurvivesRefreshAndIsSavedWhenAnotherCharacterOpens() throws Exception {
        try (CharacterJournal journal = journal("notes.json", 1, 2)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", "notes");
                JTextArea notes = named(sheet, "character-notes", JTextArea.class);
                notes.setText("Draft for one"); sheet.refresh();
                assertEquals("A refresh keeps the draft", "Draft for one", notes.getText());
                assertEquals("", journal.characterCopy(ACCOUNT + ":1").notes);
                sheet.open(ACCOUNT + ":2", null);
                assertEquals("Opening another character saves the draft", "Draft for one", journal.characterCopy(ACCOUNT + ":1").notes);
                assertEquals("", notes.getText());
                await(sheet::ready);
                notes.setText("Saved for two"); named(sheet, "character-notes-save", AbstractButton.class).doClick();
                assertEquals("Saved for two", journal.characterCopy(ACCOUNT + ":2").notes);
            });
        }
    }

    @Test public void openingShowsLoadingUntilItsOwnReadAndDropsAnEarlierCharactersResult() throws Exception {
        try (CharacterJournal journal = journal("loading.json", 1, 2)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", null);
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                assertFalse(status.isVisible());
                sheet.open(ACCOUNT + ":2", null);
                assertTrue("Loading shows until the new character's read applies", status.isVisible());
                assertEquals("Loading…", status.text()); assertFalse(status.warns());
                assertFalse(sheet.ready());
                assertEquals("Nothing of the previous character stays", "", named(sheet, "character-sheet-name", JLabel.class).getText());
                assertFalse("Nothing acts while loading", named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                sheet.open(ACCOUNT + ":1", null); // #2's build may still arrive: it is for another key now, so it is dropped
                await(sheet::ready);
                assertFalse(status.isVisible());
                assertEquals(ACCOUNT + ":1", sheet.key());
                assertTrue(named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#1"));
                long settled = System.currentTimeMillis() + 200;
                await(() -> System.currentTimeMillis() >= settled); // runs the EDT, so a late result would arrive now
                assertTrue("A late result for #2 never replaces #1", named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#1"));
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void aFailedBuildShowsAWarnBannerAndNothingActs() throws Exception {
        RosterDefinitions none = RosterDefinitions.empty();
        try (CharacterJournal journal = journal("failure.json", 1)) {
            SheetContext failing = new SheetContext(new TomatoData(), journal, () -> {
                if ("character-sheet".equals(Thread.currentThread().getName())) throw new IllegalStateException("Synthetic build failure");
                return none;
            }, DisplayModeModel.application(), () -> 5000, PlanningStore.shared());
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = new CharacterSheet(failing);
                sheet.open(ACCOUNT + ":1", null);
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                await(status::warns);
                assertTrue(status.isVisible()); assertTrue(status.text(), status.text().contains("Synthetic build failure"));
                assertFalse(sheet.ready()); assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void hidingTheSheetSavesItsNotesDraft() throws Exception {
        try (CharacterJournal journal = journal("hide.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                JFrame frame = new JFrame("Sheet hide"); frame.setContentPane(sheet); frame.setSize(900, 600); frame.setVisible(true);
                try {
                    open(sheet, ACCOUNT + ":1", "notes");
                    named(sheet, "character-notes", JTextArea.class).setText("Kept when the sheet hides");
                    sheet.setVisible(false); // what another card, Back or another Characters tab does
                    assertEquals("Kept when the sheet hides", journal.characterCopy(ACCOUNT + ":1").notes);
                } finally { frame.dispose(); }
            });
        }
    }

    @Test public void snapshotEvidenceAndTheTabHintAreAnalystOnly() throws Exception {
        try (CharacterJournal journal = journal("provenance.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", null);
                JTextArea evidence = named(sheet, "character-snapshot-evidence", JTextArea.class), hint = named(sheet, "character-sheet-hint", JTextArea.class);
                assertFalse("Simple hides provenance (spec §3.2)", evidence.isVisible()); assertFalse(hint.isVisible());
                assertTrue("The text is still kept current", evidence.getText().contains("Snapshot update age"));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertTrue(evidence.isVisible()); assertTrue(hint.isVisible());
            });
        }
    }

    @Test public void anUnreadableJournalAndAFailedNotesSaveShowAWarnBanner() throws Exception {
        Path broken = temp.getRoot().toPath().resolve("broken.json");
        Files.writeString(broken, "{broken");
        Path blocker = temp.newFile("blocker").toPath(); // a file where the journal's folder should be: every save fails
        CharacterSheet[] shown = new CharacterSheet[1];
        try (CharacterJournal unreadable = new CharacterJournal(broken); CharacterJournal failing = journal(new CharacterJournal(blocker.resolve("journal.json")), 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(unreadable);
                open(sheet, ACCOUNT + ":1", null);
                Banner storage = named(sheet, "character-sheet-storage", Banner.class);
                assertTrue(storage.isVisible()); assertTrue(storage.warns()); assertTrue(storage.text(), storage.text().startsWith("Cannot read"));
                shown[0] = sheet(failing);
                open(shown[0], ACCOUNT + ":1", "notes");
                assertFalse("Nothing has failed yet", named(shown[0], "character-sheet-storage", Banner.class).isVisible());
                named(shown[0], "character-notes", JTextArea.class).setText("Never reaches the disk");
                named(shown[0], "character-notes-save", AbstractButton.class).doClick();
            });
            failing.save(); // the saver thread's write fails
            SwingUtilities.invokeAndWait(() -> {
                shown[0].refresh();
                Banner storage = named(shown[0], "character-sheet-storage", Banner.class);
                assertTrue("A failed save warns inside the sheet", storage.isVisible()); assertTrue(storage.warns());
                assertTrue(storage.text(), storage.text().startsWith("Save failed"));
            });
        }
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`, in `exerciseTabs`. This is a **replace**: the Overview tab replaced the moved stat table, so the Overview checks read its value labels (still "Unknown"/"—" for Life and 70 for ATT) and its reachability; the Gear and Exalts table checks are kept.
- Replace:
  ```java
          SwingUtilities.invokeAndWait(() -> {
              named(panel, "character-roster", JTable.class).getActionMap().get("open-character").actionPerformed(null);
              assertTrue("Enter opens the selected character's sheet", view.showingSheet());
          });
  ```
  with:
  ```java
          SwingUtilities.invokeAndWait(() -> {
              named(panel, "character-roster", JTable.class).getActionMap().get("open-character").actionPerformed(null);
              assertTrue("Enter opens the selected character's sheet", view.showingSheet());
          });
          // The sheet's model is built off the EDT.
          tomato.gui.activity.SnapshotTestSupport.await(() -> named(sheet, "character-overview-value-2", JLabel.class).getText().startsWith("70"));
  ```
- Replace:
  ```java
                  if (!"notes".equals(id)) {
                      JTable table = find((Container)tabs.getSelectedComponent(), JTable.class);
                      assertRows(table);
                      reachableRow(table, 0); reachableRow(table, table.getRowCount() - 1);
                      if ("overview".equals(id)) {
                          assertEquals("Unknown", table.getValueAt(0, 1));
                          assertEquals(70, table.getValueAt(2, 1));
                      }
                  } else {
  ```
  with:
  ```java
                  if ("overview".equals(id)) {
                      assertEquals("Life is not captured: unknown, never 0", DisplayFormat.UNAVAILABLE, named(sheet, "character-overview-value-0", JLabel.class).getText());
                      assertTrue(named(sheet, "character-overview-value-2", JLabel.class).getText().startsWith("70"));
                      reachable(named(sheet, "character-overview-value-7", JLabel.class));
                      reachable(named(sheet, "character-overview-exalts", JLabel.class));
                  } else if (!"notes".equals(id)) {
                      JTable table = find((Container)tabs.getSelectedComponent(), JTable.class);
                      assertRows(table);
                      reachableRow(table, 0); reachableRow(table, table.getRowCount() - 1);
                  } else {
  ```

- [ ] **Step 15: Run the tests**

Run: `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.glance.home.HomeModelBuilderTest" --tests "tomato.gui.glance.home.HeroCardTest" --tests "tomato.gui.character.*"`
Expected: PASS.

- [ ] **Step 16: Commit**

```powershell
git add src/main/java/tomato/gui/glance/character/SheetModel.java src/main/java/tomato/gui/glance/character/SheetModelBuilder.java src/main/java/tomato/gui/glance/character/SheetViews.java src/main/java/tomato/gui/glance/character/SheetHeader.java src/main/java/tomato/gui/glance/character/OverviewTab.java src/main/java/tomato/gui/glance/character/SheetPresenter.java src/main/java/tomato/gui/glance/character/CharacterSheet.java src/main/java/tomato/gui/glance/home/HomeModelBuilder.java src/test/java/tomato/gui/glance/character/SheetFixtures.java src/test/java/tomato/gui/glance/character/SheetModelBuilderTest.java src/test/java/tomato/gui/glance/character/OverviewTabTest.java src/test/java/tomato/gui/glance/character/CharacterSheetTest.java src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java
git commit -m "Add the character sheet header and Overview tab" -m "Sprite, identity and maxed/playing/seasonal/last-played chips; base-versus-cap bars with the live boost only while playing; potions needed with vault counts (and their age) only when known; gear, class exalt summary and death annotation. The stat table stays in Analyst mode. SheetPresenter reads the journal and builds SheetModel off the EDT with Home's potion arithmetic; the sheet shows Loading until the opened character's result applies, drops late results for another character and reports a failed build." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Gear tab (`gear`)

This task replaces the moved equipment table with a sprite grid:
- Four 48 px equipped slots with tier labels, and enchant rarity dots for the live character only.
- Inventory (8) and backpack (16) at 32 px. The backpack shows "No backpack" when there is none, and unknown slots when that is unknown and nothing was captured.
- Unknown and empty stay distinct, with item tooltips. The 28-row slot table (`CharacterEquipmentPanel`) stays in Analyst mode inside a `Collapsible`.

**Files:**
- Create: `src/main/java/tomato/gui/glance/character/GearTab.java`, `EnchantDots.java`
- Modify: `src/main/java/tomato/realmshark/ParseEnchants.java`, `src/main/java/tomato/gui/myinfo/BuildEstimates.java`, `SheetModelBuilder.java`, `SheetPresenter.java`, `CharacterSheet.java` (Task 4's moved equipment panel is removed)
- Create test: `src/test/java/tomato/gui/glance/character/GearTabTest.java`
- Modify tests (replace, Step 8): `CharacterJournalLayoutTest.java` and `CharacterWaveFourEvidenceTest.java` in `src/test/java/tomato/gui/character/`

**Interfaces:**
- Consumes: Task 5's `SheetModel.Gear`, `SheetViews` and `SheetPresenter.Built(key, model, read, definitions)` (`read.record()` is the sheet's record copy); Task 4's `CharacterSheet.setTab`; `ParseEnchants.EquippedCapture` (per-slot `CaptureState`; `summarize(code).slots` 0–4 = Common–Divine); `BuildEstimates.Inputs` (a detached player copy); `CharacterEquipmentPanel` (`showRecord(record, defs)`, table `character-equipment`); `ItemTiers.label`.
- Produces:
  - `public int ParseEnchants.EquippedCapture.unlockedSlots(int slot)` (−1 unless KNOWN), `public ParseEnchants.EquippedCapture BuildEstimates.Inputs.enchants()`, `static List<Integer> SheetModelBuilder.enchants(LiveCharacter.Snapshot)`.
  - **`GearTab`** (`character-gear`): `apply(SheetModel.Gear)` and `analyst(CharacterRecord, RosterDefinitions)`; names `character-gear-slot-0..27`, `-tier-i`, `-enchant-i`, `-backpack` (the grid), `-no-backpack`, `-backpack-unknown`, and `character-gear-slot-table` (Collapsible).
  - **`EnchantDots`:** `set(int unlocked)` (−1 hides it) and `slots()`; tooltip and accessible name "Uncommon · 1 enchant slot".

- [ ] **Step 1: Write the failing test**

`src/test/java/tomato/gui/glance/character/GearTabTest.java`:
```java
package tomato.gui.glance.character;

import java.util.List;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.ItemSlot;
import tomato.gui.myinfo.BuildEstimates;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Gear: unknown vs empty vs no backpack, tier labels, and enchant rarity dots only for the live character. */
public class GearTabTest {
    private final DisplayModeModel mode = new DisplayModeModel(key -> null, (key, value) -> {});
    private static ItemSlot slot(JComponent tab, int index) { return named(tab, "character-gear-slot-" + index, ItemSlot.class); }

    @Test public void emptyAndNotCapturedSlotsStayDistinctAndEquippedItemsShowTiers() throws Exception {
        SheetModel model = model(record(), account(), null);
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> id == 2_001 ? "UT" : id == 2_004 ? "T6" : "");
            tab.apply(model.gear());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 0).state());
            assertEquals(ItemSlot.State.EMPTY, slot(tab, 1).state());
            assertEquals(ItemSlot.State.UNKNOWN, slot(tab, 2).state());
            assertEquals("Empty slot", slot(tab, 1).getToolTipText());
            assertEquals("Slot not captured", slot(tab, 2).getToolTipText());
            assertEquals("UT", named(tab, "character-gear-tier-0", JLabel.class).getText());
            assertEquals("T6", named(tab, "character-gear-tier-3", JLabel.class).getText());
            assertEquals("Armor", named(tab, "character-gear-tier-2", JLabel.class).getText());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 4).state());
            assertEquals(ItemSlot.State.EMPTY, slot(tab, 5).state());
        });
    }

    @Test public void backpackIsAbsentUnknownOrShownAsCaptured() throws Exception {
        CharacterJournal.CharacterRecord none = record(); none.hasBackpack = Boolean.FALSE;
        CharacterJournal.CharacterRecord partial = record(); partial.equipment[12] = 3_000;
        CharacterJournal.CharacterRecord owned = record(); owned.hasBackpack = Boolean.TRUE;
        for (int i = 12; i < 28; i++) owned.equipment[i] = -1;
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> "");
            tab.apply(model(none, account(), null).gear());
            assertTrue(named(tab, "character-gear-no-backpack", JLabel.class).isVisible());
            assertFalse(named(tab, "character-gear-backpack", JPanel.class).isVisible());
            tab.apply(model(record(), account(), null).gear());
            assertFalse(named(tab, "character-gear-no-backpack", JLabel.class).isVisible());
            assertTrue("Unknown and nothing captured", named(tab, "character-gear-backpack-unknown", JLabel.class).isVisible());
            for (int i = 12; i < 28; i++) assertEquals(ItemSlot.State.UNKNOWN, slot(tab, i).state());
            tab.apply(model(partial, account(), null).gear());
            assertFalse("Captured slots show as captured", named(tab, "character-gear-backpack-unknown", JLabel.class).isVisible());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 12).state());
            assertEquals(ItemSlot.State.UNKNOWN, slot(tab, 13).state());
            tab.apply(model(owned, account(), null).gear());
            assertEquals(ItemSlot.State.EMPTY, slot(tab, 20).state());
        });
    }

    @Test public void enchantDotsShowOnlyForTheLiveCharacter() throws Exception {
        Entity player = new Entity(null, 1, 0);
        StatData enchants = new StatData();
        enchants.stringStatValue = "AAIE_wU,AAIE,,!!!"; // weapon 1 unlocked slot, ability 0, armor 0, ring malformed
        player.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        BuildEstimates.Inputs inputs = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN);
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", inputs));
        SheetModel other = model(record(), account(), live(ACCOUNT, 8, "Ann", inputs));
        assertEquals(List.of(1, 0, 0, -1), playing.gear().enchants());
        assertNull("Another character's enchants never describe this one", other.gear().enchants());
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> "");
            tab.apply(playing.gear());
            EnchantDots weapon = named(tab, "character-gear-enchant-0", EnchantDots.class);
            assertTrue(weapon.isVisible());
            assertEquals(1, weapon.slots());
            assertEquals("Uncommon · 1 enchant slot", weapon.getToolTipText());
            assertFalse("Empty slot: no dots", named(tab, "character-gear-enchant-1", EnchantDots.class).isVisible());
            assertEquals("Common · 0 enchant slots", named(tab, "character-gear-enchant-2", EnchantDots.class).getToolTipText());
            assertFalse("Malformed data: unknown, no dots", named(tab, "character-gear-enchant-3", EnchantDots.class).isVisible());
            tab.apply(other.gear());
            for (int i = 0; i < 4; i++) assertFalse(named(tab, "character-gear-enchant-" + i, EnchantDots.class).isVisible());
        });
    }

    @Test public void theSlotTableIsAnalystOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> "");
            tab.analyst(record(), defs());
            Collapsible table = named(tab, "character-gear-slot-table", Collapsible.class);
            assertFalse(table.isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(table.isVisible());
            assertEquals(28, named(tab, "character-equipment", JTable.class).getRowCount());
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.glance.character.GearTabTest"`
Expected: FAIL (compile error: `GearTab`, `EnchantDots` and `Inputs.enchants()` do not exist).

- [ ] **Step 3: Expose per-slot enchant evidence**

In `src/main/java/tomato/realmshark/ParseEnchants.java`, **add beside** (after `        public CaptureState state(int slot) { return states[slot]; }`):
```java
        /** Unlocked enchant slots of one equipped item (0 Common … 4 Divine, as Summary.rarity() names them); -1 unless KNOWN. */
        public int unlockedSlots(int slot) { return states[slot] == CaptureState.KNOWN ? summarize(codes[slot]).slots : -1; }
```
In `src/main/java/tomato/gui/myinfo/BuildEstimates.java`, **add beside** (after `        public Estimates estimate() { return of(player, pet, pets, false); }`):
```java
        /** The detached player's equipped enchant evidence; decoding the copy is safe on any thread. */
        public ParseEnchants.EquippedCapture enchants() { return ParseEnchants.equippedCapture(player); }
```

- [ ] **Step 4: Fill the live character's enchants in `SheetModelBuilder`**

**Replace**:
```java
    /** Saved slots, with the live equipped four while this character plays. Enchant rarity arrives in Task 6. */
```
with:
```java
    /** Saved slots, with the live equipped four and their enchant rarity while this character plays. */
```
**Replace**:
```java
        return new SheetModel.Gear(list(slots), r.hasBackpack, null);
    }
```
with:
```java
        return new SheetModel.Gear(list(slots), r.hasBackpack, playing ? enchants(live) : null);
    }

    /** Unlocked enchant slots of the 4 equipped items from the live snapshot's detached inputs; -1 where not decodable. */
    static List<Integer> enchants(LiveCharacter.Snapshot live) {
        if (live.build() == null) return null;
        ParseEnchants.EquippedCapture capture = live.build().enchants();
        int[] slots = new int[4];
        for (int i = 0; i < 4; i++) slots[i] = capture.unlockedSlots(i);
        return list(slots);
    }
```
and **add beside** the imports: insert `import tomato.realmshark.ParseEnchants;` before `import tomato.realmshark.enums.CharacterClass;`.

- [ ] **Step 5: Create `EnchantDots.java`**

```java
package tomato.gui.glance.character;

import java.awt.*;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;

/** Four dots, the first N filled: an equipped item's unlocked enchant slots (its rarity). Hidden while unknown. */
final class EnchantDots extends JComponent {
    /** ParseEnchants.Summary.rarity() names by unlocked slot count. */
    private static final String[] RARITY = {"Common", "Uncommon", "Rare", "Legendary", "Divine"};
    private static final int GAP = 3;
    private int slots = -1;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    EnchantDots() { setOpaque(false); set(-1); }

    /** 0-4 unlocked enchant slots; -1 hides the dots (not decodable, or not the character in game). */
    void set(int unlocked) {
        slots = unlocked < 0 || unlocked > 4 ? -1 : unlocked;
        setVisible(slots >= 0);
        String text = slots < 0 ? null : RARITY[slots] + " · " + slots + (slots == 1 ? " enchant slot" : " enchant slots");
        setToolTipText(text);
        getAccessibleContext().setAccessibleName(text);
        repaint();
    }

    int slots() { return slots; }

    private int dot() { return Math.max(6, Math.round(ContentStyle.body().getSize2D() * 0.45f)); }

    @Override public Dimension getPreferredSize() { int dot = dot(); return new Dimension(4 * dot + 3 * GAP, dot); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int dot = dot(), y = (getHeight() - dot) / 2;
        for (int i = 0; i < 4; i++) {
            g.setColor(Tokens.color(i < slots ? Tokens.Role.ACCENT : Tokens.Role.CONTROL));
            g.fillOval(i * (dot + GAP), y, dot, dot);
        }
        g.dispose();
    }
}
```

- [ ] **Step 6: Create `GearTab.java`**

```java
package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterEquipmentPanel;
import tomato.gui.kit.*;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Gear (spec §6.2): four large equipped slots with tier labels and, for the character in game only, enchant rarity dots;
 * then the inventory (8) and backpack (16) as a sprite grid. Unknown and empty slots stay distinct; each slot's tooltip names its
 * item. The 28-row slot table with field evidence is an Analyst-only Collapsible. EDT only.
 */
final class GearTab extends JPanel {
    private final ItemSlot[] slots = new ItemSlot[28];
    private final KitText[] tiers = new KitText[4];
    private final EnchantDots[] dots = new EnchantDots[4];
    private final JPanel backpack = named(row(), "character-gear-backpack");
    private final KitText noBackpack = named(KitText.caption("No backpack"), "character-gear-no-backpack");
    private final KitText backpackUnknown = named(KitText.caption("Backpack contents not captured yet"), "character-gear-backpack-unknown");
    private final CharacterEquipmentPanel table = new CharacterEquipmentPanel();
    private final Collapsible slotTable;
    private final IntFunction<String> tierOf;
    private SheetModel.Gear shown;

    GearTab(DisplayModeModel mode) { this(mode, ItemTiers::label); }

    GearTab(DisplayModeModel mode, IntFunction<String> tierOf) {
        super(new BorderLayout());
        this.tierOf = tierOf;
        setOpaque(false);
        setName("character-gear");
        JPanel equipped = clear(new FlowLayout(FlowLayout.LEADING, Tokens.M, 0));
        for (int i = 0; i < 4; i++) {
            slots[i] = named(new ItemSlot(48), "character-gear-slot-" + i);
            tiers[i] = named(KitText.caption(SLOTS[i]), "character-gear-tier-" + i);
            dots[i] = named(new EnchantDots(), "character-gear-enchant-" + i);
            equipped.add(beside(slots[i], clear(new FlowLayout(FlowLayout.CENTER, Tokens.XS, 0), tiers[i], dots[i]), BorderLayout.SOUTH, 2));
        }
        JPanel inventory = row();
        for (int i = 4; i < 12; i++) inventory.add(slots[i] = named(new ItemSlot(32), "character-gear-slot-" + i));
        for (int i = 12; i < 28; i++) backpack.add(slots[i] = named(new ItemSlot(32), "character-gear-slot-" + i));
        slotTable = named(new Collapsible("character-equipment-table", "Slot table", table, false), "character-gear-slot-table");
        add(KitLayouts.stack(Tokens.M, card(mode, "Equipped", equipped, "character-gear-equipped"),
            card(mode, "Inventory", inventory, "character-gear-inventory"),
            card(mode, "Backpack", KitLayouts.stack(Tokens.XS, noBackpack, backpackUnknown, backpack), "character-gear-backpack-card"), slotTable),
            BorderLayout.NORTH);
        mode.bind(this, value -> slotTable.setVisible(value == DisplayModeModel.Mode.ANALYST));
        apply(null);
    }

    /** EDT. A gear section equal to the shown one is skipped. */
    void apply(SheetModel.Gear gear) {
        if (gear != null && gear.equals(shown)) return;
        shown = gear;
        boolean backpackCaptured = false;
        for (int i = 0; i < 28; i++) {
            int id = gear == null ? -1 : gear.slots().get(i);
            String tier = id > 0 ? Objects.toString(tierOf.apply(id), "") : "";
            if (id > 0) slots[i].setItem(id, tier); else if (id == 0) slots[i].setEmpty(); else slots[i].setUnknown();
            if (i >= 12 && id >= 0) backpackCaptured = true;
            if (i < 4) {
                tiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);
                // Enchant rarity is decoded from the character in game only; saved records never show dots.
                dots[i].set(gear == null || gear.enchants() == null || id <= 0 ? -1 : gear.enchants().get(i));
            }
        }
        Boolean has = gear == null ? null : gear.hasBackpack();
        noBackpack.setVisible(Boolean.FALSE.equals(has));
        backpack.setVisible(!Boolean.FALSE.equals(has));
        backpackUnknown.setVisible(has == null && !backpackCaptured);
        revalidate();
        repaint();
    }

    /** EDT: the Analyst slot table (per-slot field evidence) for the sheet's detached record copy. */
    void analyst(CharacterJournal.CharacterRecord record, RosterDefinitions definitions) { table.showRecord(record, definitions); }
}
```

- [ ] **Step 7: Show the Gear tab from the presenter and remove the moved equipment panel**

In `SheetPresenter.java`, **replace**:
```java
    private final OverviewTab overview;
```
with:
```java
    private final OverviewTab overview;
    private final GearTab gear;
```
**Replace**:
```java
        sheet.setTab("overview", SheetViews.scroll(overview));
```
with:
```java
        sheet.setTab("overview", SheetViews.scroll(overview));
        gear = new GearTab(context.mode());
        sheet.setTab("gear", SheetViews.scroll(gear));
```
**Replace** (the end of `show`):
```java
        overview.apply(value);
    }
```
with:
```java
        overview.apply(value);
        gear.apply(value == null ? null : value.gear());
    }
```
**Replace** (the end of `apply(Built)`; the Analyst slot table shows the sheet's record copy):
```java
        show(built.model());
    }
```
with:
```java
        show(built.model());
        gear.analyst(read.record(), built.definitions());
    }
```
In Task 4's `src/main/java/tomato/gui/glance/character/CharacterSheet.java`, **replace** (reason: the Gear tab owns its own Analyst slot table, and two panels would both be named `character-equipment`):
1. Remove `import tomato.gui.character.CharacterEquipmentPanel;` and the field line `    private final CharacterEquipmentPanel equipment = new CharacterEquipmentPanel();`.
2. Replace `            .add("gear", "Gear", slot("gear", equipment))` with `            .add("gear", "Gear", slot("gear", new JPanel())) // SheetPresenter sets the Gear tab`.
3. Delete:
   ```java
       /** The moved 28-slot equipment table (Task 6 keeps it in Analyst). */
       CharacterEquipmentPanel equipmentPanel() { return equipment; }
   ```
4. In `fill()`, delete the line `        equipment.showRecord(r, definitions);`.

- [ ] **Step 8: Migrate Task 4's Gear checks**

`src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`, in `exerciseTabs`, **replace** `                } else if (!"notes".equals(id)) {` with the lines below (reason: the Gear tab is a sprite grid now; its slot table is Analyst-only). The Exalts table check that follows is kept.
```java
                } else if ("gear".equals(id)) {
                    // The Gear tab: the equipped slots and the last inventory slot scroll into view.
                    reachable(named(sheet, "character-gear-slot-0", JComponent.class));
                    reachable(named(sheet, "character-gear-slot-11", JComponent.class));
                } else if (!"notes".equals(id)) {
```

`src/test/java/tomato/gui/character/CharacterWaveFourEvidenceTest.java`, **replace** (reason: the slot table is filled off the EDT, by the presenter; the 28-row assertion and the screenshot are kept):
```java
                    tabs.setSelectedIndex(tabs.indexOfTab("Gear")); JTable gear = named(panel, "character-equipment", JTable.class); assertEquals(28, gear.getRowCount()); ContentStyle.reveal(gear, gear.getCellRect(0, 0, true)); capture(frame, "equipment-compact");
```
with:
```java
                    tabs.setSelectedIndex(tabs.indexOfTab("Gear")); JTable gear = named(panel, "character-equipment", JTable.class);
                    tomato.gui.activity.SnapshotTestSupport.await(() -> gear.getRowCount() == 28); // the Gear tab's slot table fills off the EDT
                    ContentStyle.reveal(gear, gear.getCellRect(0, 0, true)); capture(frame, "equipment-compact");
```

- [ ] **Step 9: Run the tests**

Run: `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.realmshark.EquippedEnchantCaptureTest" --tests "tomato.gui.myinfo.BuildEstimatesTest" --tests "tomato.gui.character.*"`
Expected: PASS.

- [ ] **Step 10: Commit**

```powershell
git add src/main/java/tomato/gui/glance/character/GearTab.java src/main/java/tomato/gui/glance/character/EnchantDots.java src/main/java/tomato/gui/glance/character/SheetModelBuilder.java src/main/java/tomato/gui/glance/character/SheetPresenter.java src/main/java/tomato/gui/glance/character/CharacterSheet.java src/main/java/tomato/realmshark/ParseEnchants.java src/main/java/tomato/gui/myinfo/BuildEstimates.java src/test/java/tomato/gui/glance/character/GearTabTest.java src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java src/test/java/tomato/gui/character/CharacterWaveFourEvidenceTest.java
git commit -m "Add the character sheet Gear tab" -m "Four large equipped slots with tier labels and enchant rarity dots for the character in game only; inventory and backpack as a sprite grid with unknown, empty and no-backpack kept distinct; the 28-row slot table stays in Analyst mode." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Exalts tab (`exalts`, class-scoped)

This task replaces the moved "Class exalts" table with one row per stat, in canonical order:
- Tier as a `PipMeter(5)`, the completions, and "N to next tier" (5/15/30/50/75), or "Maxed" at 75.
- A "+N" live bonus only from this class's saved `AccountRecord.liveExaltBonus`, with "Live bonus observed <ago>".
- "Changed <ago>": when this class's completion counts last changed (`exaltSeenByClass`, spec §5.7).
- "Earn in: <dungeon>" from the selected assets' mapping, or "—" while it loads or is unavailable.

The header shows total completions and the lowest tier; with no saved counts, an empty state replaces the rows; while the sheet loads, neither shows (never "no progress" before the read). S5 (Task 10): Home hero, then the Exalts tab, is two clicks.

**Files:**
- Create: `src/main/java/tomato/gui/glance/character/ExaltsTab.java`
- Modify: `SheetPresenter.java`, `CharacterSheet.java` (Task 4's moved class-exalts table is removed)
- Create test: `src/test/java/tomato/gui/glance/character/ExaltsTabTest.java`
- Modify test (replace, Step 4): `src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`

**Interfaces:**
- Consumes: Task 5's `SheetModel.Exalts` and `SheetModelBuilder.build(..., dungeons, now)`; `PipMeter` (`setFilled/filled/setColor`), `EmptyState`, `KitText.role`, `KitFormat.relative`.
- Produces: **`ExaltsTab`** (`character-exalts`) with `apply(SheetModel.Exalts)`; names `character-exalts-{totals,observed,bonus-observed,content,empty}` and `character-exalt-{pips,count,next,bonus,earn}-i`.

- [ ] **Step 1: Write the failing test**

`src/test/java/tomato/gui/glance/character/ExaltsTabTest.java`:
```java
package tomato.gui.glance.character;

import java.util.Collections;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.PipMeter;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Class-scoped exalts: tier pips, next-tier math at every threshold, this class's saved live bonus, and where to earn each stat. */
public class ExaltsTabTest {
    private static String text(JComponent root, String name) { return named(root, name, JLabel.class).getText(); }

    @Test public void pipsCompletionsAndNextTierAtEveryThreshold() throws Exception {
        SheetModel.Exalts climbing = model(record(), account(0, 4, 5, 14, 15, 30, 50, 74), null).exalts();
        assertEquals(List.of(0, 0, 1, 1, 2, 3, 4, 4), climbing.tiers());
        assertEquals(List.of(5, 1, 10, 1, 15, 20, 25, 1), climbing.toNext());
        assertEquals(192, climbing.total());
        assertEquals(0, climbing.lowest());
        SheetModel.Exalts maxed = model(record(), account(75, 80, 75, 75, 75, 75, 75, 75), null).exalts();
        assertEquals(Collections.nCopies(8, 5), maxed.tiers());
        assertEquals("75 is the last tier", Collections.nCopies(8, 0), maxed.toNext());
        assertEquals(5, maxed.lowest());
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(climbing);
            assertEquals("Total completions 192 · Lowest tier 0/5", text(tab, "character-exalts-totals"));
            for (int i = 0; i < 8; i++) {
                assertEquals(climbing.tiers().get(i).intValue(), named(tab, "character-exalt-pips-" + i, PipMeter.class).filled());
                assertEquals(climbing.toNext().get(i) + " to next tier", text(tab, "character-exalt-next-" + i));
            }
            assertEquals("0 completions", text(tab, "character-exalt-count-0"));
            assertEquals("74 completions", text(tab, "character-exalt-count-7"));
            tab.apply(maxed);
            assertEquals("Maxed", text(tab, "character-exalt-next-0"));
            assertEquals(5, named(tab, "character-exalt-pips-1", PipMeter.class).filled());
            assertEquals("80 completions", text(tab, "character-exalt-count-1"));
        });
    }

    @Test public void liveBonusComesOnlyFromThisClassesSavedBonus() throws Exception {
        CharacterJournal.AccountRecord account = account(5, 5, 5, 5, 5, 5, 5, 5);
        account.exaltSeenByClass.put(WIZARD, System.currentTimeMillis() - HOUR); // KitFormat.relative reads the real clock
        account.liveExaltBonus.put(WIZARD, bonus(NOW - 3 * HOUR, 1, 2, 3, 4, 5, 6, 7, 8));
        account.liveExaltBonus.put(PRIEST, bonus(NOW, 9, 9, 9, 9, 9, 9, 9, 9));
        SheetModel.Exalts wizard = model(record(), account, live(ACCOUNT, 7, "Sharkbait", null)).exalts();
        assertEquals("This class's saved bonus, not the snapshot's or another class's", List.of(1, 2, 3, 4, 5, 6, 7, 8), wizard.liveBonus());
        assertEquals(NOW - 3 * HOUR, wizard.liveObservedAt());
        CharacterJournal.AccountRecord priestOnly = account(5, 5, 5, 5, 5, 5, 5, 5);
        priestOnly.liveExaltBonus.put(PRIEST, bonus(NOW, 9, 9, 9, 9, 9, 9, 9, 9));
        SheetModel.Exalts none = model(record(), priestOnly, live(ACCOUNT, 7, "Sharkbait", null)).exalts();
        assertNull(none.liveBonus());
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(wizard);
            assertEquals("+1", text(tab, "character-exalt-bonus-0"));
            assertEquals("+8", text(tab, "character-exalt-bonus-7"));
            assertTrue(text(tab, "character-exalts-bonus-observed").startsWith("Live bonus observed "));
            assertEquals("When this class's counts last changed", "Changed 1 h ago", text(tab, "character-exalts-observed"));
            tab.apply(none);
            assertFalse(named(tab, "character-exalt-bonus-0", JLabel.class).isVisible());
            assertFalse(named(tab, "character-exalts-bonus-observed", JLabel.class).isVisible());
        });
    }

    @Test public void earnInNamesDungeonsOrShowsADashWhileTheMappingIsUnavailable() throws Exception {
        SheetModel.Exalts mapped = SheetModelBuilder.build(record(), account(0, 0, 0, 0, 0, 0, 0, 0), null, defs(),
            stat -> stat == 3 ? List.of("Lost Halls") : List.<String>of(), NOW).exalts();
        SheetModel.Exalts loading = model(record(), account(0, 0, 0, 0, 0, 0, 0, 0), null).exalts();
        assertNull(loading.earnIn());
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(mapped);
            assertEquals("Earn in: Lost Halls", text(tab, "character-exalt-earn-3"));
            assertEquals("Earn in: —", text(tab, "character-exalt-earn-0"));
            assertEquals("Not mapped in the selected game assets", named(tab, "character-exalt-earn-0", JLabel.class).getToolTipText());
            tab.apply(loading);
            assertEquals("Earn in: —", text(tab, "character-exalt-earn-3"));
            assertEquals("Dungeon mapping loads with the selected game assets", named(tab, "character-exalt-earn-3", JLabel.class).getToolTipText());
        });
    }

    @Test public void aClassWithoutSavedProgressShowsTheEmptyState() throws Exception {
        SheetModel.Exalts unknown = model(record(), account(), null).exalts();
        SwingUtilities.invokeAndWait(() -> {
            ExaltsTab tab = new ExaltsTab();
            tab.apply(unknown);
            assertTrue(named(tab, "character-exalts-empty", EmptyState.class).isVisible());
            assertFalse("Unknown is never shown as zero completions", named(tab, "character-exalts-content", JPanel.class).isVisible());
            tab.apply(null);
            assertFalse("Loading shows no \"no progress\" either", named(tab, "character-exalts-empty", EmptyState.class).isVisible());
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.glance.character.ExaltsTabTest"`
Expected: FAIL (compile error: `ExaltsTab` does not exist).

- [ ] **Step 3: Create `ExaltsTab.java`**

```java
package tomato.gui.glance.character;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Exalts (spec §6.2), for this character's class. Each of the 8 stats shows its tier pips (5), its completions and how
 * many more reach the next tier, the saved live stat bonus for this class, and the dungeon that grants it. The header shows the
 * total completions and the lowest tier. With no saved counts for this class, an empty state replaces the rows; a null section (the
 * sheet is loading) shows neither. EDT only.
 */
final class ExaltsTab extends JPanel {
    private final KitText totals = named(KitText.emphasis(""), "character-exalts-totals");
    private final KitText observed = named(KitText.caption(""), "character-exalts-observed");
    private final KitText bonusSeen = named(KitText.caption(""), "character-exalts-bonus-observed");
    private final PipMeter[] pips = new PipMeter[8];
    private final KitText[] counts = new KitText[8], next = new KitText[8], bonus = new KitText[8], earn = new KitText[8];
    private final JPanel content;
    private final EmptyState empty = named(new EmptyState("No exalt progress for this class yet",
        "Exalt progress arrives when capture reads your character list.", null), "character-exalts-empty");
    private SheetModel.Exalts shown;

    ExaltsTab() {
        super(new BorderLayout());
        setOpaque(false);
        setName("character-exalts");
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(Tokens.XS, 0, Tokens.XS, Tokens.M);
        for (int i = 0; i < 8; i++) {
            pips[i] = named(new PipMeter(5), "character-exalt-pips-" + i);
            pips[i].getAccessibleContext().setAccessibleName(CharacterJournal.STATS[i] + " exalt tier");
            counts[i] = named(KitText.body(""), "character-exalt-count-" + i);
            next[i] = named(KitText.caption(""), "character-exalt-next-" + i);
            bonus[i] = named(new KitText("", Type.caption(), Tokens.Role.ACCENT_TEXT), "character-exalt-bonus-" + i);
            earn[i] = named(KitText.caption(""), "character-exalt-earn-" + i);
            c.gridy = i;
            c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            rows.add(KitText.body(CharacterJournal.STATS[i]), c);
            c.gridx = 1;
            rows.add(pips[i], c);
            c.gridx = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            rows.add(row(counts[i], next[i], bonus[i], earn[i]), c); // wraps at 680 px and font 18
        }
        content = named(KitLayouts.stack(Tokens.S, totals, row(observed, bonusSeen), rows), "character-exalts-content");
        add(KitLayouts.stack(Tokens.M, content, empty), BorderLayout.NORTH);
        apply(null);
    }

    /** EDT. Called for every model and once a second; an unchanged section re-reads only its relative "Changed" and "observed" texts. */
    void apply(SheetModel.Exalts exalts) {
        // seenAt is when this class's counts last changed in the journal (exaltSeenByClass), not when they were last read.
        String seen = exalts == null || exalts.seenAt() <= 0 ? "" : "Changed " + KitFormat.relative(exalts.seenAt());
        String bonusText = exalts == null || exalts.liveBonus() == null ? "" : "Live bonus observed "
            + (exalts.liveObservedAt() > 0 ? KitFormat.relative(exalts.liveObservedAt()) : "at an unknown time");
        if (exalts != null && exalts.equals(shown) && seen.equals(observed.getText()) && bonusText.equals(bonusSeen.getText())) return;
        shown = exalts;
        boolean known = exalts != null && exalts.known();
        content.setVisible(known);
        empty.setVisible(exalts != null && !known); // null: nothing to show yet (loading), never "no progress"
        if (!known) return;
        totals.setText("Total completions " + DisplayFormat.formatInteger(exalts.total()) + " · Lowest tier " + exalts.lowest() + "/5");
        observed.setText(seen);
        observed.setVisible(!seen.isEmpty());
        bonusSeen.setText(bonusText);
        bonusSeen.setVisible(!bonusText.isEmpty());
        List<Integer> live = exalts.liveBonus();
        for (int i = 0; i < 8; i++) {
            int count = exalts.completions().get(i), tier = exalts.tiers().get(i), toNext = exalts.toNext().get(i);
            pips[i].setFilled(tier);
            pips[i].setColor(tier >= 5 ? Tokens.Role.GOOD : Tokens.Role.ACCENT);
            counts[i].setText(DisplayFormat.formatInteger(count) + (count == 1 ? " completion" : " completions"));
            next[i].setText(toNext == 0 ? "Maxed" : toNext + " to next tier");
            next[i].role(toNext == 0 ? Tokens.Role.GOOD : Tokens.Role.TEXT_MUTED);
            // Only this class's saved bonus: never another class's, and never a live snapshot of another character.
            bonus[i].setVisible(live != null);
            bonus[i].setText(live == null ? "" : "+" + live.get(i));
            bonus[i].setToolTipText(live == null ? null : "Live " + CharacterJournal.STATS[i] + " bonus from exaltation, saved for this class");
            String where = exalts.earnIn() == null ? "" : exalts.earnIn().get(i);
            earn[i].setText("Earn in: " + (where.isEmpty() ? DisplayFormat.UNAVAILABLE : where));
            earn[i].setToolTipText(exalts.earnIn() == null ? "Dungeon mapping loads with the selected game assets"
                : where.isEmpty() ? "Not mapped in the selected game assets" : null);
        }
        revalidate();
        repaint();
    }
}
```

- [ ] **Step 4: Show the Exalts tab from the presenter and remove the moved class-exalts table**

In `SheetPresenter.java`, **replace**:
```java
    private final GearTab gear;
```
with:
```java
    private final GearTab gear;
    private final ExaltsTab exalts = new ExaltsTab();
```
**Replace**:
```java
        sheet.setTab("gear", SheetViews.scroll(gear));
```
with:
```java
        sheet.setTab("gear", SheetViews.scroll(gear));
        sheet.setTab("exalts", SheetViews.scroll(exalts));
```
**Replace** (in `times()`):
```java
        overview.apply(model); // re-reads only the vault age
```
with:
```java
        overview.apply(model); // re-reads only the vault age
        exalts.apply(model.exalts()); // and when this class's counts last changed
```
**Replace** (the end of `show`):
```java
        gear.apply(value == null ? null : value.gear());
    }
```
with:
```java
        gear.apply(value == null ? null : value.gear());
        exalts.apply(value == null ? null : value.exalts());
    }
```
In Task 4's `src/main/java/tomato/gui/glance/character/CharacterSheet.java`, **replace** (reason: the Exalts tab replaces the moved class-exalts table):
1. Delete the field lines `    private final DefaultTableModel exaltModel = model("Stat", "Level", "Completions", "Next tier");` and `    private final JScrollPane exaltTable;`, and the constructor line `        exaltTable = ContentStyle.tableScroll(table(exaltModel), 3);`.
2. Replace `            .add("exalts", "Exalts", slot("exalts", exaltTable))` with `            .add("exalts", "Exalts", slot("exalts", new JPanel())) // SheetPresenter sets the Exalts tab`.
3. Delete:
   ```java
       /** The moved class-exalts table (Task 7 replaces it). */
       JComponent exaltTable() { return exaltTable; }
   ```
4. In `fill()`, replace `        exaltModel.setRowCount(0); metadataModel.setRowCount(0);` with `        metadataModel.setRowCount(0);`, and delete the class-exalt rows:
   ```java
           int[] exalt = null;
           for (AccountRecord a : accounts) if (a.key.equals(r.account)) exalt = a.exalts.get(r.classId);
           for (int i = 0; i < 8; i++) {
               Integer count = exalt == null ? null : exalt[CharacterJournal.EXALT_ORDER[i]];
               exaltModel.addRow(new Object[]{CharacterJournal.STATS[i], count == null ? "Unknown" : CharacterJournal.exaltLevel(count) + "/5", unknown(count), count == null ? "Unknown" : next(count)});
           }
   ```
5. Delete the helper `    private static String next(int count) { for (int goal : new int[]{5, 15, 30, 50, 75}) if (count < goal) return (goal - count) + " to " + goal; return "Complete"; }`.

`src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`, in `exerciseTabs`, **replace** (reason: the Exalts tab has no table; this fixture saved no exalts, so its empty state must stay reachable):
```java
                } else if (!"notes".equals(id)) {
                    JTable table = find((Container)tabs.getSelectedComponent(), JTable.class);
                    assertRows(table);
                    reachableRow(table, 0); reachableRow(table, table.getRowCount() - 1);
                } else {
```
with:
```java
                } else if ("exalts".equals(id)) {
                    reachable(named(sheet, "character-exalts-empty", JComponent.class));
                } else {
```

- [ ] **Step 5: Run the tests**

Run: `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/tomato/gui/glance/character/ExaltsTab.java src/main/java/tomato/gui/glance/character/SheetPresenter.java src/main/java/tomato/gui/glance/character/CharacterSheet.java src/test/java/tomato/gui/glance/character/ExaltsTabTest.java src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java
git commit -m "Add the class-scoped Exalts tab to the character sheet" -m "Tier pips, completions and completions to the next tier (Maxed at 75) for this class; the live bonus comes only from this class's saved bonus; where to earn each stat, shown as a dash while the dungeon mapping is unavailable." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Build tab — `MyInfoGUI` moves into the sheet; `MY_INFO`, Alt+7, search and Home's Build open it; page 6 says "Build moved"

The single `MyInfoGUI` (still constructed only by `TomatoGUI`) moves into the sheet's `build` tab, inserted after `exalts`. Build describes the character in game, or after capture stops (or during a map change) the last one that was: `LiveCharacter.current()`, else `lastKnown()`, by `Snapshot.journalKey()` (added here).
- On that character's sheet the tab shows `MyInfoGUI`.
- On any other sheet it shows "Build shows the character you're playing". While someone is in game it offers a button to that character's Build; while nobody is, it says to start capture and enter the game, with no button. `MyInfoGUI` stays parented in its hidden card.

`Route.to(MY_INFO)` redirects once to `CHARACTER_SHEET(SheetFocus(key, "build"))`: the live character when the journal has it, else the journal's most recent. With no character at all, page 6 (`BuildMovedPanel`) opens. The search entry and `HomeActions.build` already open `MY_INFO`; Alt+7 is rebound to it.

**Files:**
- Create: `src/main/java/tomato/gui/glance/character/BuildTab.java`, `src/main/java/tomato/gui/myinfo/BuildRoute.java`, `src/main/java/tomato/gui/myinfo/BuildMovedPanel.java`
- Modify:
  - `src/main/java/tomato/backend/data/LiveCharacter.java` (`Snapshot.journalKey()`)
  - `src/main/java/tomato/gui/route/RouteTarget.java`, `ShellNavigator.java` (one-hop redirect)
  - `SheetPresenter.java`, `CharacterSheet.java`, `src/main/java/tomato/gui/character/CharacterPanelGUI.java` (`hostBuild`)
  - `src/main/java/tomato/gui/TomatoGUI.java`, `src/main/java/tomato/gui/modern/NavEntry.java`
- Create test: `src/test/java/tomato/gui/route/ShellNavigatorRedirectTest.java`, `src/test/java/tomato/gui/glance/character/BuildTabTest.java`
- Modify tests:
  - Add beside: `src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`, `src/test/java/tomato/backend/data/LiveCharacterTest.java`.
  - Replace: `src/test/java/tomato/ShellRouteRegistrationTest.java` (setup only) and `src/test/java/ui/WorkspaceUiTest.java`.
  - Replace (Step 9, the Build tab): Task 4's `src/test/java/tomato/gui/glance/character/CharacterSheetTest.java` and `src/test/java/tomato/gui/character/CharacterTabsTest.java`; add beside (Step 9): `src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java` (the tab loop covers Build, Goals and Death annotation).
  - Step 13 lists the tests that stay unchanged.

**Interfaces:**
- Consumes:
  - Task 4: `Destination.CHARACTER_SHEET` (page 3), `SheetFocus(String key, String tab)`, the `CHARACTER_SHEET` target (`sheet.open(key, tab)`, may `show()`), `CharacterSheet.key()/selectedTab()`.
  - Task 4: `CharacterSheet.setTab(id, content)` (package-private; this task adds the `build` slot to the tab chain) and `CharacterPanelGUI`'s `sheet` field.
  - Task 5: `SheetModelBuilder.inGame` (behind `SheetModel.live()`) and the presenter's `apply`.
  - Existing: `CharacterJournal.characterCopy/mostRecentCharacter`, `LiveCharacter.current()/lastKnown()/stop()`, `MyInfoGUI` (unchanged), the `WorkspaceShell` action `page-6`, `HomeActions.build`, search `build.open`.
- Produces:
  - `public String LiveCharacter.Snapshot.journalKey()`: `"<account>:<characterId>"` when `account` is 64 lowercase hex and `characterId >= 0`, else null.
  - `RouteTarget.redirect(Route)`: a default method. `ShellNavigator.open` follows it once, only to an accepted route, with one Back entry.
  - **`BuildRoute`** (`MY_INFO`): `BuildRoute(Supplier<String> key)`, `static String key(TomatoData)`, `static Route sheet(String key)`.
  - **`BuildMovedPanel`** (`build-moved`): `BuildMovedPanel(Runnable, BooleanSupplier)` and `refresh()`; its button is `build-moved-open`.
  - **`BuildTab`** (`character-build`): `BuildTab(Consumer<String> openLive)`, `host(JComponent)`, `hosted()`, `apply(SheetModel, String buildKey)`, `card()`, `static String shownKey(LiveCharacter)` (the character Build describes: current, else last known), `POINTER` ("Build shows the character you're playing"); names `character-build-{host,other,other-state,open-live,unhosted}`.
  - `hostBuild(JComponent)` on `CharacterSheet`, `CharacterPanelGUI` and `SheetPresenter`.

- [ ] **Step 1: Write the failing redirect test**

`src/test/java/tomato/gui/route/ShellNavigatorRedirectTest.java`:
```java
package tomato.gui.route;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** One-hop redirects (Build now opens on the character sheet): one Back entry, and the original route when nobody accepts. */
public class ShellNavigatorRedirectTest {
    @Test public void aTargetMayRedirectOnceToARouteAnotherTargetAccepts() throws Exception {
        ShellNavigatorTest.edt(() -> {
            ShellNavigatorTest.Pages pages = new ShellNavigatorTest.Pages();
            ShellNavigator navigator = pages.navigator(5);
            Route[] next = {Route.to(Destination.TIMELINE)};
            ShellNavigatorTest.Fake runs = new ShellNavigatorTest.Fake(Destination.RUNS, pages.log) {
                @Override public Route redirect(Route route) { return next[0]; }
            };
            ShellNavigatorTest.Fake timeline = new ShellNavigatorTest.Fake(Destination.TIMELINE, pages.log) {
                @Override public Route redirect(Route route) { return Route.to(Destination.RUNS); } // never followed: one hop only
            };
            navigator.register(runs); navigator.register(timeline);
            pages.selected = 8;
            assertTrue(navigator.open(Route.to(Destination.RUNS)));
            assertEquals(Arrays.asList("open TIMELINE", "select 11"), pages.log);
            assertNull("The redirecting target does not open", runs.opened);
            assertEquals("One Back entry, to the true origin", 1, navigator.depth());
            assertTrue(navigator.back());
            assertEquals(8, pages.selected);
            pages.log.clear();
            next[0] = Route.to(Destination.LOOT);
            assertTrue("A redirect nobody accepts keeps the original route", navigator.open(Route.to(Destination.RUNS)));
            assertEquals(Arrays.asList("open RUNS", "select 10"), pages.log);
            return null;
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.route.ShellNavigatorRedirectTest"`
Expected: FAIL (compile error: `redirect` overrides nothing).

- [ ] **Step 3: Add the one-hop redirect**

In `src/main/java/tomato/gui/route/RouteTarget.java`, **replace**:
```java
    void restoreState(Object state);
}
```
with:
```java
    void restoreState(Object state);
    /**
     * Another route to open instead of an accepted one, or null (the default). The navigator follows it once, only when some
     * target accepts it, and keeps a single Back entry; otherwise this target opens the original route.
     */
    default Route redirect(Route route) { return null; }
}
```
In `src/main/java/tomato/gui/route/ShellNavigator.java`, **replace**:
```java
        RouteTarget target = route == null ? null : target(route);
        if (target == null) return false;
        int destinationPage = pageOf.applyAsInt(route.destination);
```
with:
```java
        RouteTarget target = route == null ? null : target(route);
        if (target == null) return false;
        // One hop: a target may send its route to another destination (Build now opens on the character sheet).
        Route redirected = redirect(target, route);
        RouteTarget next = redirected == null ? null : target(redirected);
        if (next != null) { route = redirected; target = next; }
        int destinationPage = pageOf.applyAsInt(route.destination);
```
and **add beside** (after the `target(Route)` method):
```java
    private static Route redirect(RouteTarget target, Route route) {
        try { return target.redirect(route); }
        catch (RuntimeException failed) { return null; } // A failing redirect keeps the original route.
    }
```

- [ ] **Step 4: Run the route tests**

Run: `GRADLE test --tests "tomato.gui.route.*"`
Expected: PASS (the new test and the existing navigator tests).

- [ ] **Step 5: Write the failing Build tests**

`src/test/java/tomato/gui/glance/character/BuildTabTest.java`:
```java
package tomato.gui.glance.character;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.glance.home.HomeModels;
import tomato.gui.glance.home.HomePage;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.myinfo.BuildMovedPanel;
import tomato.gui.myinfo.BuildRoute;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.route.*;
import tomato.gui.search.ActionRegistry;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Build on the character sheet: one MyInfoGUI, hosted in the sheet; every Build entry opens the sheet's Build tab; page 6 points there. */
public class BuildTabTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void buildTabHostsOneMyInfoAndPointsElsewhereWhileAnotherCharacterPlays() throws Exception {
        TomatoData data = new TomatoData();
        SwingUtilities.invokeAndWait(() -> {
            List<String> opened = new ArrayList<>();
            BuildTab tab = new BuildTab(opened::add);
            assertEquals("unhosted", tab.card());
            MyInfoGUI build = new MyInfoGUI(data);
            tab.host(build);
            tab.apply(model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null)), KEY);
            assertEquals("This character is in game: Build itself", "build", tab.card());
            assertSame(tab, SwingUtilities.getAncestorOfClass(BuildTab.class, build));
            tab.apply(model(record(), account(), live(ACCOUNT, 8, "Ann", null)), ACCOUNT + ":8");
            assertEquals("other", tab.card());
            EmptyState other = named(tab, "character-build-other-state", EmptyState.class);
            assertEquals(BuildTab.POINTER, "Build shows the character you're playing");
            assertEquals(BuildTab.POINTER, other.getAccessibleContext().getAccessibleName());
            assertEquals("Ann is in game now.", other.getAccessibleContext().getAccessibleDescription());
            AbstractButton open = named(tab, "character-build-open-live", AbstractButton.class);
            assertEquals("Open Ann's Build", open.getText());
            open.doClick();
            assertEquals(List.of(ACCOUNT + ":8"), opened);
            assertSame("MyInfoGUI stays parented in its card", tab, SwingUtilities.getAncestorOfClass(BuildTab.class, build));
            assertFalse("…but that card is not shown", named(tab, "character-build-host", JPanel.class).isVisible());
            tab.apply(model(record(), account(), null), null);
            assertEquals("Build describes nobody: the pointer, not an empty Build", "other", tab.card());
            EmptyState nobody = named(tab, "character-build-other-state", EmptyState.class);
            assertEquals("Start capture and enter the game with this character.", nobody.getAccessibleContext().getAccessibleDescription());
            assertEquals("Nobody is in game: nothing to open", 0, count(named(tab, "character-build-other", JPanel.class), KitButton.class));
        });
    }

    @Test public void afterCaptureStopsBuildStaysOnTheLastCharactersSheetOnly() throws Exception {
        TomatoData data = new TomatoData();
        LiveCharacter live = new LiveCharacter();
        live.publish(live(ACCOUNT, 7, "Sharkbait", null));
        live.stop(NOW);
        assertNull("Capture stopped: nobody is in game", SheetModelBuilder.inGame(live, NOW + 1));
        assertEquals("…while Build still describes the last character", KEY, BuildTab.shownKey(live));
        CharacterJournal.CharacterRecord other = record(); other.key = ACCOUNT + ":8"; other.characterId = 8;
        SheetModel mine = model(record(), account(), SheetModelBuilder.inGame(live, NOW + 1));
        SheetModel theirs = model(other, account(), SheetModelBuilder.inGame(live, NOW + 1));
        SwingUtilities.invokeAndWait(() -> {
            BuildTab tab = new BuildTab(key -> fail("Nothing to open while nobody is in game"));
            tab.host(new MyInfoGUI(data));
            tab.apply(mine, BuildTab.shownKey(live));
            assertEquals("The last character's sheet still shows its Build", "build", tab.card());
            tab.apply(theirs, BuildTab.shownKey(live));
            assertEquals("Another sheet never shows the last character's Build", "other", tab.card());
            EmptyState state = named(tab, "character-build-other-state", EmptyState.class);
            assertEquals(BuildTab.POINTER, state.getAccessibleContext().getAccessibleName());
            assertEquals("Start capture and enter the game with this character.", state.getAccessibleContext().getAccessibleDescription());
            assertEquals(0, count(named(tab, "character-build-other", JPanel.class), KitButton.class));
        });
    }

    @Test public void buildRoutePrefersTheLiveCharacterInTheJournalThenTheMostRecent() throws Exception {
        TomatoData data = new TomatoData();
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
        inject(data, journal);
        assertNull("No character at all: no key (page 6)", BuildRoute.key(data));
        String recent = seed(journal);
        assertEquals(recent, BuildRoute.key(data));
        data.liveCharacter.publish(live(ACCOUNT, 8, "Ann", null));
        assertEquals("A live character missing from the journal is skipped", recent, BuildRoute.key(data));
        journal.observe(CharacterJournalTest.player("sheet-fixture", WIZARD), 8);
        assertEquals(ACCOUNT + ":8", BuildRoute.key(data));
        assertEquals(Destination.CHARACTER_SHEET, BuildRoute.sheet(recent).destination);
        assertEquals(new SheetFocus(recent, "build"), BuildRoute.sheet(recent).payload);
    }

    @Test public void theWorkspaceHostsOneBuildAndEveryBuildEntryOpensTheSheetsBuildTab() throws Exception {
        try (Workspace w = new Workspace(temp, true)) {
            CharacterSheet[] sheet = new CharacterSheet[1];
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Exactly one Build page exists", 1, count(w.shell, MyInfoGUI.class));
                sheet[0] = find(w.shell, CharacterSheet.class);
                assertTrue("It lives in the sheet's Build tab", SwingUtilities.isDescendingFrom(find(w.shell, MyInfoGUI.class),
                    named(sheet[0], "character-build", BuildTab.class)));
                BuildMovedPanel moved = find(w.shell, BuildMovedPanel.class);
                assertNotNull("Page 6 only says that Build moved", moved);
                moved.refresh();
                HomePage home = find(w.shell, HomePage.class);
                home.apply(HomeModels.populated(System.currentTimeMillis()));
                Navigator navigator = Navigator.current();
                for (String entry : new String[]{"MY_INFO route", "Alt+7", "Settings search", "Home Build", "Build moved button"}) {
                    w.shell.select(14);
                    switch (entry) {
                        case "MY_INFO route": assertTrue(navigator.open(Route.to(Destination.MY_INFO))); break;
                        case "Alt+7": w.shell.getActionMap().get("page-6").actionPerformed(null); break;
                        case "Settings search": assertTrue(ActionRegistry.application().search("build.open").get(0).open()); break;
                        case "Home Build": named(home, "home-build", AbstractButton.class).doClick(); break;
                        default: named(moved, "build-moved-open", AbstractButton.class).doClick();
                    }
                    assertEquals(entry + " opens Characters", 3, w.shell.getSelectedPage());
                    assertEquals(entry + " opens this character's sheet", w.key, sheet[0].key());
                    assertEquals(entry + " selects Build", "build", sheet[0].selectedTab());
                    assertTrue(entry + ": Back is available", navigator.back());
                    assertEquals(entry + ": Back returns to Home", 14, w.shell.getSelectedPage());
                }
            });
            await(() -> "Sample".equals(named(sheet[0], "character-sheet-name", JLabel.class).getText())); // built off the EDT
        }
    }

    @Test public void withoutAnyCharacterBuildLandsOnTheBuildMovedPage() throws Exception {
        try (Workspace w = new Workspace(temp, false)) {
            SwingUtilities.invokeAndWait(() -> {
                w.shell.select(14);
                assertTrue(Navigator.current().open(Route.to(Destination.MY_INFO)));
                assertEquals(6, w.shell.getSelectedPage());
                BuildMovedPanel moved = find(w.shell, BuildMovedPanel.class);
                moved.refresh();
                assertFalse("Nothing to open yet", named(moved, "build-moved-open", AbstractButton.class).isEnabled());
                assertTrue(Navigator.current().back());
                assertEquals(14, w.shell.getSelectedPage());
            });
        }
    }

    /**
     * The real workspace (TomatoGUI.createWorkspace) over a temporary journal, preview mode and a temporary history store. It puts
     * back what it changes: TomatoGUI's and ChatGUI's static fields, the display mode, the Characters and sheet preferences
     * (ui.tabs.*, ui.characters.*, ui.collapse.*, ui.filters.characters.open) and every ux.archive.* saved view.
     */
    private static final class Workspace implements AutoCloseable {
        private static final List<String> PREFERENCES = List.of("ui.tabs.character", "ui.tabs.characters", "ui.characters.view",
            "ui.characters.sort", "ui.collapse.characters-graveyard", "ui.filters.characters.open");
        final TomatoData data = new TomatoData();
        final TomatoGUI gui;
        final String key;
        WorkspaceShell shell;
        private final Field store = AppHistory.class.getDeclaredField("store"), preview = Tomato.class.getDeclaredField("preview");
        private final Object previousStore, previousPreview;
        private final String tmp = System.getProperty("java.io.tmpdir");
        private final Map<String, String> preferences = new HashMap<>();
        private final Map<Field, Object> statics = new LinkedHashMap<>();
        private final DisplayModeModel.Mode mode = DisplayModeModel.application().mode();
        private final SessionStore history;

        Workspace(TemporaryFolder temp, boolean seeded) throws Exception {
            for (String name : preferenceKeys()) preferences.put(name, PropertiesManager.getProperty(name));
            for (Class<?> type : new Class<?>[]{TomatoGUI.class, ChatGUI.class})
                for (Field field : type.getDeclaredFields())
                    if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) { field.setAccessible(true); statics.put(field, field.get(null)); }
            store.setAccessible(true); preview.setAccessible(true);
            previousStore = store.get(null); previousPreview = preview.get(null);
            preview.set(null, true);
            System.setProperty("java.io.tmpdir", temp.newFolder().getAbsolutePath());
            history = new SessionStore(temp.newFolder().toPath(), false, "synthetic");
            store.set(null, history);
            CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
            key = seeded ? seed(journal) : null;
            inject(data, journal);
            gui = new TomatoGUI(data);
            SwingUtilities.invokeAndWait(() -> shell = (WorkspaceShell) gui.createWorkspace());
        }

        @Override public void close() throws Exception {
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> {
                if (shell != null) shell.removeNotify();
                DisplayModeModel.application().set(mode);
                try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            for (String name : preferenceKeys()) { String value = preferences.get(name); PropertiesManager.setProperties(name, value == null ? "" : value); }
            System.setProperty("java.io.tmpdir", tmp);
            store.set(null, previousStore); preview.set(null, previousPreview);
            history.close();
        }

        /** The preferences above and every ux.archive.* key present now (a key the workspace added is cleared on close). */
        private static Set<String> preferenceKeys() throws ReflectiveOperationException {
            Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
            Set<String> keys = new HashSet<>(PREFERENCES);
            for (String name : ((Properties) field.get(null)).stringPropertyNames()) if (name.startsWith("ux.archive.")) keys.add(name);
            return keys;
        }
    }
}
```

In `src/test/java/tomato/backend/data/LiveCharacterTest.java`, **add beside** the existing tests, immediately before `    private static void put(Entity entity, StatType type, int value) {`:
```java
    @Test public void journalKeyIsTheJournalsExactKeyOrNull() {
        String account = CharacterJournal.accountKey("sample-account");
        assertEquals(account + ":7", new LiveCharacter.Snapshot(account, 7, 782, null, null, null, null, TOTALS, null, null, null, null, null,
            null, null, 0).journalKey());
        assertNull("Not a hashed account key", snapshot(0).journalKey());
        assertNull("No character id", new LiveCharacter.Snapshot(account, -1, 782, null, null, null, null, TOTALS, null, null, null, null, null,
            null, null, 0).journalKey());
    }
```

What the Workspace helper puts back, and why: `createWorkspace` sets `TomatoGUI`'s and `ChatGUI`'s static fields, and the Characters page saves its tabs, drawer, gallery view and sort, Graveyard state and view state as it builds. Restoring them keeps later tests independent of this one.

- [ ] **Step 6: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.glance.character.BuildTabTest" --tests "tomato.backend.data.LiveCharacterTest"`
Expected: FAIL (compile error: `BuildTab`, `BuildRoute`, `BuildMovedPanel` and `journalKey()` do not exist).

- [ ] **Step 7: Add `LiveCharacter.Snapshot.journalKey()` and create `BuildTab.java`**

In `src/main/java/tomato/backend/data/LiveCharacter.java` — before:
```java
        @Override public int[] exaltBonus() { return copy(exaltBonus); }
```
after:
```java
        @Override public int[] exaltBonus() { return copy(exaltBonus); }
        /** The character journal's key, "<account>:<characterId>", or null when the account is not a journal account key. */
        public String journalKey() {
            return account != null && account.matches("[0-9a-f]{64}") && characterId >= 0 ? account + ":" + characterId : null;
        }
```

`src/main/java/tomato/gui/glance/character/BuildTab.java`:

```java
package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.backend.data.LiveCharacter;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import static tomato.gui.glance.character.SheetViews.named;

/**
 * Sheet › Build (spec §6.1–6.2): the app's single Build page (MyInfoGUI). Build describes the character in game or, after capture
 * stops or while a map change clears it, the last one that was. So the tab shows Build only on that character's sheet; on any
 * other sheet an empty state says "Build shows the character you're playing" and, while someone is in game, offers that
 * character's Build. MyInfoGUI stays parented in its hidden card (never a second instance). EDT only.
 */
final class BuildTab extends JPanel {
    static final String POINTER = "Build shows the character you're playing";
    private final CardLayout cards = new CardLayout();
    private final JPanel host = named(new JPanel(new BorderLayout()), "character-build-host");
    private final JPanel other = named(new JPanel(new BorderLayout()), "character-build-other");
    private final Consumer<String> openLive;
    private JComponent build;
    private String card = "", liveKey, pointerFor;

    /** {@code openLive} opens the sheet's Build tab for a journal key (the character in game). */
    BuildTab(Consumer<String> openLive) {
        this.openLive = Objects.requireNonNull(openLive, "openLive");
        setLayout(cards);
        setOpaque(false);
        setName("character-build");
        host.setOpaque(false);
        other.setOpaque(false);
        add(host, "build");
        add(other, "other");
        add(named(new EmptyState("Build is not available here", "Build opens in the RealmShark window.", null), "character-build-unhosted"), "unhosted");
        show("unhosted");
    }

    /** The journal key of the character Build describes: the one in game, else the last one (capture stopped); null when none. */
    static String shownKey(LiveCharacter live) {
        if (live == null) return null;
        LiveCharacter.Snapshot shown = live.current() != null ? live.current() : live.lastKnown();
        return shown == null ? null : shown.journalKey();
    }

    /** Parents the app's single MyInfoGUI here (TomatoGUI calls this once). */
    void host(JComponent value) {
        if (build == value) return;
        if (build != null) host.remove(build);
        build = value;
        host.add(value, BorderLayout.CENTER);
        host.revalidate();
        if ("unhosted".equals(card)) show("build");
    }

    JComponent hosted() { return build; }

    /** "build", "other" or "unhosted". */
    String card() { return card; }

    /**
     * {@code buildKey}: the character Build describes ({@link #shownKey}). This sheet's own character shows Build; any other shows
     * the pointer, with an Open button only while someone is in game. A null model (loading, or not in the journal) changes nothing.
     */
    void apply(SheetModel model, String buildKey) {
        if (build == null) { show("unhosted"); return; }
        if (model == null) return;
        if (model.key().equals(buildKey)) { show("build"); return; }
        pointer(model.live());
        show("other");
    }

    /** The pointer for another sheet; {@code live} null: nobody is in game, so there is nothing to open. */
    private void pointer(SheetModel.Live live) {
        liveKey = live == null ? null : live.key(); // read at click time: the same name may belong to another character later
        String shownFor = live == null ? "" : live.name();
        if (shownFor.equals(pointerFor)) return;
        pointerFor = shownFor;
        EmptyState state;
        if (live == null) state = new EmptyState(POINTER, "Start capture and enter the game with this character.", null);
        else {
            KitButton open = named(KitButton.primary("Open " + live.name() + "'s Build"), "character-build-open-live");
            open.addActionListener(e -> { if (liveKey != null) openLive.accept(liveKey); });
            state = new EmptyState(POINTER, live.name() + " is in game now.", open);
        }
        other.removeAll();
        other.add(named(state, "character-build-other-state"));
        other.revalidate();
        other.repaint();
    }

    private void show(String name) {
        if (name.equals(card)) return;
        card = name;
        cards.show(this, name);
    }
}
```

- [ ] **Step 8: Create `BuildRoute.java` and `BuildMovedPanel.java`**

`src/main/java/tomato/gui/myinfo/BuildRoute.java`:
```java
package tomato.gui.myinfo;

import java.util.Objects;
import java.util.function.Supplier;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * The Build route (Destination.MY_INFO). Build is a tab on the character sheet (spec §6.2), so a plain MY_INFO route redirects
 * to the sheet's Build tab. The character is the one in game when the journal has it, else the journal's most recent. With no
 * character at all it stays on page 6, which says that Build moved. EDT only.
 */
public final class BuildRoute implements RouteTarget {
    private final Supplier<String> key;

    public BuildRoute(Supplier<String> key) { this.key = Objects.requireNonNull(key, "key"); }

    /** The journal key whose Build to open, or null when there is no character at all. Any thread. */
    public static String key(TomatoData data) {
        if (data == null) return null;
        CharacterJournal journal = data.characterJournal();
        LiveCharacter.Snapshot live = data.liveCharacter.current();
        if (live != null && live.account() != null && live.characterId() >= 0) {
            String liveKey = live.account() + ":" + live.characterId();
            if (journal.characterCopy(liveKey) != null) return liveKey;
        }
        CharacterJournal.CharacterRecord recent = journal.mostRecentCharacter();
        return recent == null ? null : recent.key;
    }

    /** The character sheet of {@code key}, on its Build tab. */
    public static Route sheet(String key) { return Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, "build")); }

    @Override public Destination destination() { return Destination.MY_INFO; }

    @Override public boolean accepts(Route route) {
        return route.destination == Destination.MY_INFO && route.query == null && route.visit == null && route.record == null
            && route.recordingId == null && route.payload == null && route.from == null && route.until == null;
    }

    @Override public Route redirect(Route route) {
        String target = key.get();
        return target == null ? null : sheet(target);
    }

    // Page 6 is a static pointer: nothing to capture, open or restore.
    @Override public Object captureState() { return null; }
    @Override public void open(Route route) { }
    @Override public void restoreState(Object state) { }
}
```

`src/main/java/tomato/gui/myinfo/BuildMovedPanel.java`:
```java
package tomato.gui.myinfo;

import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;

/** Page 6 after P3a: Build is a tab on the character sheet. "Open Build" follows the Build route once a character exists. */
public final class BuildMovedPanel extends JPanel {
    private final KitButton open = KitButton.primary("Open Build");
    private final BooleanSupplier available;

    /** {@code available}: whether some character exists to open (BuildRoute.key != null). */
    public BuildMovedPanel(Runnable openBuild, BooleanSupplier available) {
        super(new BorderLayout());
        this.available = Objects.requireNonNull(available, "available");
        setName("build-moved");
        open.setName("build-moved-open");
        open.addActionListener(e -> openBuild.run());
        add(new EmptyState("Build moved", "Build is now a tab on the character sheet.", open), BorderLayout.CENTER);
        addHierarchyListener(e -> { if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh(); });
        refresh();
    }

    /** EDT: Open Build is enabled only while a character exists, so it never routes back to this page. */
    public void refresh() {
        boolean any = available.getAsBoolean();
        open.setEnabled(any);
        open.setToolTipText(any ? "Opens the Build tab of the character you're playing, or of your most recent character"
            : "Enter the game with capture on to add a character");
    }
}
```

- [ ] **Step 9: Add the Build tab to the sheet**

In `SheetPresenter.java`, **replace**:
```java
    private final ExaltsTab exalts = new ExaltsTab();
```
with:
```java
    private final ExaltsTab exalts = new ExaltsTab();
    private final BuildTab build = new BuildTab(key -> tomato.gui.route.Navigator.current().open(tomato.gui.myinfo.BuildRoute.sheet(key)));
```
**Replace**:
```java
        sheet.setTab("exalts", SheetViews.scroll(exalts));
```
with:
```java
        sheet.setTab("exalts", SheetViews.scroll(exalts));
        sheet.setTab("build", build); // the sheet's build slot, added below right after exalts
```
**Replace** (the end of `apply(Built)`; while a new character loads, the tab keeps its card until that character's result applies):
```java
        gear.analyst(read.record(), built.definitions());
    }
```
with:
```java
        gear.analyst(read.record(), built.definitions());
        build.apply(built.model(), BuildTab.shownKey(live())); // Build shows only on the sheet of the character it describes
    }

    /** EDT: parents the app's single MyInfoGUI in the Build tab. */
    void hostBuild(javax.swing.JComponent value) { build.host(value); }
```
The live revision is one of the presenter's tokens, so the tab follows a character entering or leaving the game while the sheet shows.
In Task 4's `src/main/java/tomato/gui/glance/character/CharacterSheet.java`:
1. **Add beside**: after `            .add("exalts", "Exalts", slot("exalts", new JPanel())) // SheetPresenter sets the Exalts tab`, add the line below. The default order becomes overview, gear, exalts, build, goals, notes, evidence, death. A saved order without `build` gets it appended (`CustomizableTabs.order`).
   ```java
               .add("build", "Build", slot("build", new JPanel())) // SheetPresenter hosts Build (MyInfoGUI) here
   ```
2. **Replace** (the slots now include Build) `Overview, Gear and Exalts are slots whose` in the class comment with `Overview, Gear, Exalts and Build are slots whose`, and `    /** Replaces a slot tab's content (overview, gear, exalts); its id, title, order and hidden state are unchanged. */` with `    /** Replaces a slot tab's content (overview, gear, exalts, build); its id, title, order and hidden state are unchanged. */`.
3. **Add beside**, before `    public String key() { return key; }`:
   ```java
       /** Hosts the app's single Build page (MyInfoGUI) in this sheet's Build tab; TomatoGUI calls this once. */
       public void hostBuild(JComponent build) { presenter.hostBuild(build); }
   ```

In Task 4's `src/main/java/tomato/gui/character/CharacterPanelGUI.java`, **add beside** after `    public void bindNavigator(Navigator navigator) { roster.bindNavigator(navigator); sheet.bindNavigator(navigator); }`:
```java
    /** Hosts the app's single Build page (MyInfoGUI) in the character sheet's Build tab. */
    public void hostBuild(javax.swing.JComponent build) { sheet.hostBuild(build); }
```

Task 4's tab tests now see the Build tab. **Replace** (reason: the sheet gains its Build tab; every other assertion is kept):
- `src/test/java/tomato/gui/glance/character/CharacterSheetTest.java`, in `tabsKeepTheirIdsAndOrderSnapshotEvidenceIsAnalystOnlyAndSlotsAreReplaceable`:
  - `List<String> order = Arrays.asList("overview", "gear", "exalts", "goals", "notes", "evidence", "death");` becomes `List<String> order = Arrays.asList("overview", "gear", "exalts", "build", "goals", "notes", "evidence", "death");`.
  - `Arrays.asList("Overview", "Gear", "Exalts", "Goals", "Notes", "Snapshot evidence"), titles(tabs));` becomes `Arrays.asList("Overview", "Gear", "Exalts", "Build", "Goals", "Notes", "Snapshot evidence"), titles(tabs));`.
  - `assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));` becomes `assertEquals(6, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));`.
  - `fail("Only overview, gear and exalts are slots")` becomes `fail("Only overview, gear, exalts and build are slots")`.
- `src/test/java/tomato/gui/character/CharacterTabsTest.java`, in `snapshotEvidenceIsAnalystOnlyAndTabsKeepTheirSavedOrder` (its saved order predates Build, so Build is appended last and Snapshot evidence keeps index 5):
  - `assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));` becomes `assertEquals(6, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));`.
  - `assertEquals(6, tabs.getTabCount()); assertEquals(5, tabs.indexOfTab("Snapshot evidence"));` becomes `assertEquals(7, tabs.getTabCount()); assertEquals(5, tabs.indexOfTab("Snapshot evidence"));`.

`src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java`, in `exerciseTabs`, **add beside**: the tab loop also covers Build, Goals and Death annotation at every size and font. Death annotation shows only for a character marked dead, so the loop marks the character dead before it and restores it after. The checks of the other tabs are kept.
- Replace:
  ```java
          for (String id : new String[]{"overview", "gear", "exalts", "notes"}) {
              SwingUtilities.invokeAndWait(() -> sheet.tabs().select(id));
  ```
  with:
  ```java
          for (String id : new String[]{"overview", "gear", "exalts", "build", "goals", "notes", "death"}) {
              if ("death".equals(id)) { // shown only for a character marked dead: mark it here, restore it after the loop
                  SwingUtilities.invokeAndWait(() -> button(sheet, "Mark dead").doClick());
                  tomato.gui.activity.SnapshotTestSupport.await(sheet::ready);
              }
              SwingUtilities.invokeAndWait(() -> sheet.tabs().select(id));
  ```
- Replace:
  ```java
                  } else {
                      JTextArea notes = named(sheet, "character-notes", JTextArea.class);
  ```
  with:
  ```java
                  } else if ("build".equals(id)) {
                      // Build fills its tab and scrolls itself, so it is checked for its place and width rather than for fitting whole.
                      JComponent build = named(sheet, "character-build", JComponent.class);
                      assertTrue("Build is the selected tab's content", SwingUtilities.isDescendingFrom(build, tabs.getSelectedComponent()));
                      assertTrue("Build never overflows the sheet sideways: " + build.getWidth() + " > " + tabs.getWidth(), build.getWidth() <= tabs.getWidth());
                  } else if ("goals".equals(id)) {
                      reachable(named(sheet, "planning-0", JComponent.class));
                  } else if ("death".equals(id)) {
                      reachable(named(sheet, "death-occurred", JComponent.class)); // the annotation's first field
                  } else {
                      JTextArea notes = named(sheet, "character-notes", JTextArea.class);
  ```
- Replace:
  ```java
          SwingUtilities.invokeAndWait(() -> named(sheet, "character-sheet-back", JButton.class).doClick());
          settle();
          SwingUtilities.invokeAndWait(() -> assertFalse("The back link returns to the list", view.showingSheet()));
  ```
  with:
  ```java
          SwingUtilities.invokeAndWait(() -> button(sheet, "Restore alive").doClick());
          tomato.gui.activity.SnapshotTestSupport.await(sheet::ready);
          SwingUtilities.invokeAndWait(() -> named(sheet, "character-sheet-back", JButton.class).doClick());
          settle();
          SwingUtilities.invokeAndWait(() -> assertFalse("The back link returns to the list", view.showingSheet()));
  ```

- [ ] **Step 10: Wire `TomatoGUI`, Alt+7 and page 6**

In `src/main/java/tomato/gui/TomatoGUI.java`, **replace**:
```java
        myDmg = new MyInfoGUI(data);
        dpsPanel = new DpsGUI(data);
```
with:
```java
        myDmg = new MyInfoGUI(data); // the only instance: it lives in the character sheet's Build tab
        characterPanel.hostBuild(myDmg);
        dpsPanel = new DpsGUI(data);
```
**Replace**:
```java
            questPanel, myDmg, dpsPanel,
```
with:
```java
            questPanel, new tomato.gui.myinfo.BuildMovedPanel(TomatoGUI::openBuild, () -> tomato.gui.myinfo.BuildRoute.key(data) != null), dpsPanel,
```
**Replace**:
```java
        // Build (page 6) has no sidebar row; routes, Settings search, the Home hero and Alt+7 reach it.
        registerRetainedPage(Destination.MY_INFO);
```
with:
```java
        // Build is a tab on the character sheet (spec §6.2). The Build route, Settings search, the Home hero and Alt+7 open it for
        // the character in game, else the most recent one. Page 6 only says that Build moved, for when no character exists yet.
        navigator.register(new tomato.gui.myinfo.BuildRoute(() -> tomato.gui.myinfo.BuildRoute.key(data)));
        shell.getActionMap().put("page-6", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { openBuild(); }
        });
```
**Replace**:
```java
            "Build", "Nothing is saved; values come from the live capture", () -> navigator.open(tomato.gui.route.Route.to(Destination.MY_INFO)));
```
with:
```java
            "Characters › Build", "Nothing is saved; values come from the live capture", () -> navigator.open(tomato.gui.route.Route.to(Destination.MY_INFO)));
```
**Add beside** (before `    /** Opens the first route a registered target accepts. */`):
```java
    /** Alt+7 and page 6's button: the Build route (the sheet's Build tab, or page 6 while no character exists). */
    private static void openBuild() {
        if (navigator != null) navigator.open(tomato.gui.route.Route.to(Destination.MY_INFO));
    }

```
`homeActions()` is unchanged: its Build action already opens `Route.to(Destination.MY_INFO)`, which now redirects.

In `src/main/java/tomato/gui/modern/NavEntry.java`, **replace**:
```java
        new NavEntry("my-info", 6, "Build", "Weapon damage, recovery and estimates for your current character.", LineIcon.INFO, Group.UNLISTED));
```
with:
```java
        new NavEntry("my-info", 6, "Build", "Build moved to the character sheet: open a character and choose Build.", LineIcon.INFO, Group.UNLISTED));
```

- [ ] **Step 11: Make the shell tests independent of the working directory's journal**

`TomatoData.characterJournal()` lazily reads `Characters/journal.json` in the test working directory (`build/p3a/ui-test`). Tests that save journals can leave characters there, so the Build route's landing would depend on test order.

`src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`, **add beside** in `open()`: after `        data = new TomatoData();` add:
```java
        // Build now follows the journal (live or most recent character): an empty temporary journal keeps Build on page 6 here.
        tomato.gui.glance.character.SheetFixtures.inject(data, new tomato.backend.data.CharacterJournal(temp.newFolder().toPath().resolve("journal.json")));
```
In `homeIsPageFourteenAndBuildOpensFromSearchUnderItsNewTitle`, **add beside** after `            assertEquals("Build", named(shell, "page-title", JLabel.class).getText());`:
```java
            assertNotNull("With no character, page 6 says that Build moved", find(shell, tomato.gui.myinfo.BuildMovedPanel.class));
```
In `homeCardsOpenTheirPagesThroughTheNavigatorAndBackReturnsHome`, **add beside** after `            assertEquals("The hero's Build action opens page 6", 6, shell.getSelectedPage());`:
```java
            assertNotNull("With no character yet, that is the Build moved page", find(shell, tomato.gui.myinfo.BuildMovedPanel.class));
```

`src/test/java/tomato/ShellRouteRegistrationTest.java`: **replace** (reason: the `MY_INFO` landing now depends on the journal, so the test pins an empty one; its assertions stay):
```java
        TomatoGUI gui = new TomatoGUI(new TomatoData());
```
with:
```java
        TomatoData data = new TomatoData();
        // An empty temporary journal: with no character the Build route stays on page 6 (with one it opens the sheet's Build tab).
        tomato.gui.glance.character.SheetFixtures.inject(data, new tomato.backend.data.CharacterJournal(temp.newFolder().toPath().resolve("journal.json")));
        TomatoGUI gui = new TomatoGUI(data);
```

`src/test/java/ui/WorkspaceUiTest.java`: **replace** (reason: page 6 is Build's landing only while no character exists, and the preview app reads the working directory's journal, so the test pins an empty journal and puts the app's back; the exact page-6 and "Build" checks are kept) in `buildIsUnlistedButOpensByRouteUnderItsNewTitle`:
```java
                assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.MY_INFO)));
                assertEquals(6, shell.getSelectedPage());
                assertEquals("Build", pageTitle(shell).getText());
```
with:
```java
                // Build lands on page 6 only while no character exists: pin an empty journal, then put the app's own back.
                tomato.backend.data.TomatoData app = appData();
                tomato.backend.data.CharacterJournal previous = app.characterJournal();
                tomato.gui.glance.character.SheetFixtures.inject(app, emptyJournal());
                try {
                    assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.MY_INFO)));
                    assertEquals(6, shell.getSelectedPage());
                    assertEquals("Build", pageTitle(shell).getText());
                } finally { tomato.gui.glance.character.SheetFixtures.inject(app, previous); }
```
and **add beside** (after `pageTitle(Container)`):
```java
    private static tomato.backend.data.TomatoData appData() {
        try {
            java.lang.reflect.Field field = TomatoGUI.class.getDeclaredField("data"); field.setAccessible(true);
            return (tomato.backend.data.TomatoData) field.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    /** A journal with no character, in a new temporary folder. */
    private static tomato.backend.data.CharacterJournal emptyJournal() {
        try { return new tomato.backend.data.CharacterJournal(java.nio.file.Files.createTempDirectory("workspace-build-").resolve("journal.json")); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }
```

- [ ] **Step 12: Run the Build tests**

Run: `GRADLE test --tests "tomato.gui.glance.character.BuildTabTest" --tests "tomato.backend.data.LiveCharacterTest" --tests "tomato.gui.route.*" --tests "tomato.gui.chat.ShellHookIntegrationTest" --tests "tomato.ShellRouteRegistrationTest"`
Expected: PASS (5 Build tests, the journal-key test, the redirect test, and the shell tests with their added assertions).

- [ ] **Step 13: Check the tests that pin page 6 or Build but need no change, then sweep**

These tests pin page 6 or Build but stay valid:
- **`ui.WorkspaceShellNavigationTest` 438–469.** It builds a bare `WorkspaceShell` with plain panels. There, page 6 is still an unlisted page and the shell's own `page-6` action still selects it; only `TomatoGUI` rebinds Alt+7.
- **`tomato.gui.modern.WorkspaceShellLayoutTest` 206.** `pageOf(MY_INFO)` is still 6.
- **`ui.HomeEvidenceTest` 108, `tomato.gui.glance.home.HomePageLayoutTest` 73 and `HeroCardTest` 138.** They check the hero's `home-build` button (its layout, accessible name, and that it runs its `Runnable`), not the page it opens.
- **`tomato.gui.modern.NavLayoutTest`.** It checks `my-info`'s ID, page 6, title "Build" and `UNLISTED`, all unchanged; the description is not asserted.
- **`MyInfoLayoutEvidenceTest` and `WaveThreeEvidenceTest`.** They build their own page arrays with `pages[6] = view`.
- **`AccountMetadataTest`.** `MyInfoGUI` and its static update paths are unchanged.

Run: `git grep -n "registerRetainedPage(Destination.MY_INFO)\|myDmg, dpsPanel\|new MyInfoGUI(" -- src/main/java`
Expected: only `myDmg = new MyInfoGUI(data);` in `TomatoGUI`.

Run: `GRADLE test --tests "tomato.gui.modern.*" --tests "tomato.gui.glance.home.*" --tests "tomato.gui.myinfo.*" --tests "tomato.gui.character.*" --tests "tomato.backend.data.AccountMetadataTest" --tests "ui.WorkspaceShellNavigationTest" --tests "ui.WorkspaceUiTest" --tests "ui.HomeEvidenceTest" --tests "tomato.WaveThreeJourneyTest" --tests "tomato.SetupWorkspaceTest"`
Expected: PASS. This run opens real windows, so leave the machine alone until it finishes.

- [ ] **Step 14: Commit**

```powershell
git add src/main/java/tomato/backend/data/LiveCharacter.java src/test/java/tomato/backend/data/LiveCharacterTest.java src/main/java/tomato/gui/glance/character/BuildTab.java src/main/java/tomato/gui/myinfo/BuildRoute.java src/main/java/tomato/gui/myinfo/BuildMovedPanel.java src/main/java/tomato/gui/route/RouteTarget.java src/main/java/tomato/gui/route/ShellNavigator.java src/main/java/tomato/gui/glance/character/SheetPresenter.java src/main/java/tomato/gui/glance/character/CharacterSheet.java src/main/java/tomato/gui/character/CharacterPanelGUI.java src/main/java/tomato/gui/TomatoGUI.java src/main/java/tomato/gui/modern/NavEntry.java src/test/java/tomato/gui/route/ShellNavigatorRedirectTest.java src/test/java/tomato/gui/glance/character/BuildTabTest.java src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java src/test/java/tomato/ShellRouteRegistrationTest.java src/test/java/ui/WorkspaceUiTest.java src/test/java/tomato/gui/glance/character/CharacterSheetTest.java src/test/java/tomato/gui/character/CharacterTabsTest.java src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java
git commit -m "Move Build into the character sheet" -m "The single MyInfoGUI now lives in the sheet's Build tab, shown only on the sheet of the character it describes (the one in game, or after capture stops the last one). Other sheets say that Build shows the character you're playing and, while someone plays, offer that character's Build. The Build route redirects once to the sheet's Build tab for the live character, else the most recent one, so the route, Alt+7, Settings search and Home's Build action all land there. Page 6 only says that Build moved." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Roster gallery — painted cards, Graveyard, sort, Gallery/Table views over the one roster filter

- **Layout.** The Graveyard sits right below the living cards at any page height; the Sort combo and the Gallery/Table toggle share the filter row's wrapping row, so at 680 px or font 18 they wrap below the search and "Reset filters" instead of squeezing them.
- **Selection and focus.** A card selection is the roster's selection (`currentKey()` and the saved `selected` follow it); Back from the sheet focuses the selected card in the gallery, the row in the Table view.
- **Times (spec §5.7).** Cards say "Played <ago>" from the last time in game, "Seen <ago>" only for a character never played, as the Last played sort orders them; an unknown maxed count reads "—".
- **Playing now** uses Home's map-change grace (`SheetModelBuilder.inGame`).
- **States (spec §7).** An unreadable journal shows an unavailable state with the journal's status, not "No characters yet"; a failed save shows a warn banner above the cards.
- **Saved views (spec §3.2).** "Save view state" and "Reset saved view state" move into the ⋯ menu in both modes; the page shows their status only as a warn banner when saving fails or the saved state cannot be read.

**Files:**
- Create: `src/main/java/tomato/gui/glance/character/CharacterCardModel.java`, `CharacterCardRenderer.java`, `CharacterGallery.java`
- Create: `src/main/java/tomato/gui/character/RosterViews.java`
- Modify: `src/main/java/tomato/gui/roster/RosterViewState.java` (status for hosts that keep its actions in a menu), `src/main/java/tomato/gui/character/CharacterJournalGUI.java`, `src/main/java/tomato/gui/character/CharacterPanelGUI.java`
- Create test: `src/test/java/tomato/gui/glance/character/CharacterFixtures.java`, `CharacterCardRendererTest.java`, `CharacterGalleryTest.java`; `src/test/java/tomato/gui/character/RosterViewsTest.java`, `TableViewRule.java`
- Modify tests (add beside): `CharacterRosterViewTest` (Back focus) and `CharacterViewStateTest` (the ⋯ menu and its warning) in `src/test/java/tomato/gui/character/`; the table rule in `CharacterJournalGuiTest`, `CharacterJournalLayoutTest`, `CharacterFilterBarTest`, `CharacterRosterStateTest`, `CharacterTableKindsTest`, `CharacterViewStateTest`, `CharacterJournalFreshnessRefreshTest`, `CharacterWaveFourEvidenceTest` (`src/test/java/tomato/gui/character/`) and `src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`
- Modify test (replace one lookup): `CharacterJournalGuiTest` (the type-only `JComboBox` lookup)

**Interfaces:**
- Consumes:
  - Task 4: `CharacterJournalGUI.visibleRows()` (public, EDT, the filtered rows), `addRowsListener(Runnable)` (listeners run at the end of every `filter()`, after `filtered` and the table model are rebuilt), the `Consumer<String> openSheet` field (table Enter/double-click), the page statement `JScrollPane page = ContentStyle.page(top, ContentStyle.tableScroll(roster, 3), footer); pageScroll = page;` (named `character-page-scroll`), which runs before the constructor's first `refresh()`, the package-private constructor `CharacterJournalGUI(CharacterJournal, LongSupplier, Supplier<RosterDefinitions>)`, and `CharacterPanelGUI(TomatoData, SheetContext)`, where `data` and the `journal` field (the list) are in scope.
  - Task 2: `CharacterJournal.storageProblem()`. Task 5: `SheetModelBuilder.inGame(LiveCharacter, long)`. Task 8: `LiveCharacter.Snapshot.journalKey()`.
  - Existing: `CharacterRosterQuery.Row` (plain class: `record`, `maxed` (Integer, null = unknown), `potions`, `capturedStats`, `completeCaps`, `needsLife`; public constructor `Row(CharacterRecord, RosterDefinitions)`); `CharacterJournal.CharacterRecord` (`key, account, name, className, characterId, classId, level, skin, fame, seasonal, lastSeen, lastObservedAlive, dead, stats`); `WrapRow` (`tomato.gui.history`); `RosterViewState` (`tomato.gui.roster`); kit `Collapsible(id, title, content, expanded)` (`PREFIX = "ui.collapse."`, `toggle()`, `expanded()`), `SegmentedControl(name, options…)` (`selected()`, silent `setSelected`, `onChange`), `FilterBar.scope(JComponent)`/`overflow()`, `OverflowMenu.add(label, Runnable)`/`menu()`/`item(label)`, `EmptyState(title, body, action)`, `Sprites.sprite(id, size)`, `KitFormat.relative`, `DisplayFormat.formatInteger(Long)` ("—" for null)/`formatTimestamp(long)`, `Tokens`, `Type`, `DisplayModeModel.bind(owner, listener)`.
- Produces:
  - `RosterViewState` (add beside; its own controls are unchanged for every other roster): `statusText()`, `statusProblem()` (the last save failed or the saved state could not be read), `onStatus(Runnable)` and `resetSaved()`.
  - `public record CharacterCardModel(String key, String name, int classId, String className, Integer skin, Integer level, Long fame, Integer maxed, Boolean seasonal, long lastPlayed, long lastSeen, boolean playingNow, boolean dead)` with `static of(CharacterRosterQuery.Row, String liveKey)`, `static String className(int classId, String saved)`, `characterId()`, `accessibleName()` ("<name>, <class> level N, 7 of 8 maxed" plus ", playing now" / ", marked dead"; "maxed stats unknown" when unknown).
  - `public final class CharacterCardRenderer extends JComponent implements ListCellRenderer<CharacterCardModel>, Accessible`: one painted component for every cell; `public Dimension cellSize()` (card plus the 10 px gap, from the body font); package-private `record Lines(title, meta, identity, maxed, pips, seen, status, chip)`, `static Lines lines(card, analyst)`, `Lines shown()`.
  - `public final class CharacterGallery extends JPanel` named `character-gallery`: `new CharacterGallery(Consumer<String> open, DisplayModeModel mode)`, `apply(alive, dead)`, `apply(alive, dead, boolean filtered)`, `apply(alive, dead, boolean filtered, String problem)`, `alive()`, `dead()`, `cards()`, `onSelect(Consumer<String>)`, `select(String key)`, `focusTarget()`; lists `character-cards` and `character-graveyard-cards` (`HORIZONTAL_WRAP`, fixed cells, action `open-character` on Enter/Space, double-click) stacked at their preferred heights with the Graveyard right below; `Collapsible("characters-graveyard", "Graveyard (N)", …, false)`; banner `character-gallery-storage`; empty states `character-gallery-empty` / `character-gallery-no-match` / `character-gallery-unavailable`.
  - `final class RosterViews` (package `tomato.gui.character`): `RosterViews(JComponent table, FilterBar, WrapRow row, Source, DisplayModeModel, read, write)` with `interface Source` (`rows`, `saved`, `problem`, `liveKey`, `selectedKey`, `select`, `open`); `VIEW_KEY = "ui.characters.view"` (`gallery`|`table`, default gallery), `SORT_KEY = "ui.characters.sort"` (`last-played`|`fame`|`class`|`maxed`); combo `character-sort` (in the filter row's wrapping row, gallery only; panel `character-view-controls`); `SegmentedControl("character-view", "Gallery", "Table")` (Analyst); ⋯ item `character-view-item` "Table view"/"Gallery view" (Simple); body `character-roster-body` holding the table and the gallery (only the shown one is visible and measured); `refresh()`, `showGallery(boolean, boolean remember)`, `body()`, `gallery()`, `galleryShown()`, `sort()`.
  - `CharacterJournalGUI.setLiveKey(Supplier<String>)`; package-private `views()`, `selectKey(String)` and `focusTarget()`; the ⋯ items `character-save-view` "Save view state" and `character-reset-view` "Reset saved view state"; the warn banner `character-view-state`; names `character-life`, `character-season` on the life and season facets.
  - Test helpers: `CharacterFixtures` (synthetic roster, journal, definitions, live snapshot, `installDefinitions()`), `TableViewRule` (pins `ui.characters.view=table`).

- [ ] **Step 1: Write the fixtures and the failing tests**

`src/test/java/tomato/gui/glance/character/CharacterFixtures.java`:
```java
package tomato.gui.glance.character;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterRosterQuery;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.enums.CharacterClass;

/** Synthetic characters for the gallery, sheet, shell and evidence tests. No capture and no personal data. */
public final class CharacterFixtures {
    public static final String ACCOUNT = CharacterJournal.accountKey("synthetic-account");
    /** The synthetic Wizard: the Home fixture's hero (HomeModels.KEY) opens this sheet. */
    public static final String KEY = ACCOUNT + ":101";
    public static final int WIZARD = 782;
    /** Caps every fixture class shares (canonical order: life, mana, atk, def, spd, dex, vit, wis). */
    public static final int[] CAPS = {670, 385, 75, 25, 50, 75, 40, 60};
    /** The Wizard's exalt completions in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life): tiers 5+4+3+2+1+0+0+4 = 19. */
    public static final int[] WIZARD_EXALTS = {75, 50, 30, 15, 5, 1, 0, 74};
    private static final String[] CAP_TAGS = {"MaxHitPoints", "MaxMagicPoints", "Attack", "Defense", "Speed", "Dexterity", "HpRegen", "MpRegen"};

    /** One roster entry; {@code statMask} marks the captured base stats (fewer than eight leave "maxed" unknown). */
    private record Spec(int id, int classId, String className, int level, Long fame, boolean seasonal, int[] stats, int statMask, boolean dead) {}
    private static final List<Spec> ROSTER = List.of(
        new Spec(101, WIZARD, "Wizard", 20, 1_234L, false, new int[]{670, 385, 75, 25, 50, 75, 40, 57}, 255, false), // 7/8: WIS needs 3
        new Spec(102, 797, "Warrior", 20, 5_400L, true, CAPS, 255, false),                                        // 8/8, seasonal
        new Spec(103, 784, "Priest", 20, 880L, false, new int[]{620, 385, 70, 25, 45, 75, 40, 60}, 255, false),    // 5/8, needs Life
        new Spec(104, 798, "Knight", 14, null, false, new int[]{540, 200, 50, 10, 40, 0, 0, 0}, 0b11111, false),   // fame, maxed unknown
        new Spec(105, 775, "Archer", 20, 15_020L, false, new int[]{670, 385, 75, 20, 50, 70, 40, 60}, 255, false), // 6/8
        new Spec(106, 768, "Rogue", 20, 2_210L, true, CAPS, 255, false),                                          // 8/8, seasonal
        new Spec(107, 801, "Necromancer", 20, 640L, false, new int[]{600, 300, 70, 20, 50, 75, 40, 60}, 255, true), // 4/8, dead
        new Spec(108, 799, "Paladin", 18, 95L, false, new int[]{500, 300, 60, 20, 40, 60, 30, 50}, 255, true));    // 0/8, dead

    private CharacterFixtures() {}

    /** A journal at {@code file} with the eight characters above (107 and 108 marked dead) and the Wizard's exalts. */
    public static CharacterJournal journal(Path file, long now) {
        CharacterJournal journal = new CharacterJournal(file);
        String account = journal.observe(CharacterJournalTest.player("synthetic-account", WIZARD), 101);
        List<RealmCharacter> roster = new ArrayList<>();
        for (int i = 0; i < ROSTER.size(); i++) roster.add(character(ROSTER.get(i), now - i * 3_600_000L));
        journal.mergeRoster(account, roster);
        journal.exalts(account, Map.of(WIZARD, WIZARD_EXALTS.clone()));
        for (Spec spec : ROSTER) if (spec.dead()) journal.markDead(ACCOUNT + ":" + spec.id(), true);
        return journal;
    }

    private static RealmCharacter character(Spec spec, long receivedAt) {
        RealmCharacter c = new RealmCharacter();
        c.charId = spec.id(); c.classNum = (short) spec.classId(); c.classString = spec.className(); c.level = spec.level();
        c.seasonal = spec.seasonal();
        for (String field : new String[]{"class", "level", "seasonal"}) c.supplied(field);
        if (spec.fame() != null) { c.fame = spec.fame(); c.supplied("fame"); }
        int[] s = spec.stats();
        c.hp = s[0]; c.mp = s[1]; c.atk = s[2]; c.def = s[3]; c.spd = s[4]; c.dex = s[5]; c.vit = s[6]; c.wis = s[7];
        c.capturedStatMask = spec.statMask();
        // The Wizard's weapon, ability and armor are known, its ring and inventory empty, its backpack not captured.
        if (spec.id() == 101) c.equipment = new int[]{2593, 2856, 3113, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        c.receivedAt = receivedAt;
        return c;
    }

    /** Class caps for every fixture class, as RosterDefinitions would read them from players.xml. */
    public static RosterDefinitions definitions() {
        StringBuilder xml = new StringBuilder("<Objects>");
        for (Spec spec : ROSTER) {
            if (xml.indexOf("type='" + spec.classId() + "'") >= 0) continue;
            xml.append("<Object type='").append(spec.classId()).append("'>");
            for (int i = 0; i < 8; i++) xml.append('<').append(CAP_TAGS[i]).append(" max='").append(CAPS[i]).append("'/>");
            xml.append("</Object>");
        }
        try { return RosterDefinitions.parse(new StringReader(xml.append("</Objects>").toString()), null); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    /** A saved record: {@code below} stats one short of CAPS counting down from WIS, or -1 for an uncaptured WIS (maxed unknown). */
    public static CharacterJournal.CharacterRecord record(int id, int classId, String className, Integer level, Long fame, int below,
                                                          long played, long seen, boolean dead) {
        CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord();
        r.key = ACCOUNT + ":" + id; r.account = ACCOUNT; r.characterId = id; r.classId = classId; r.className = className;
        r.name = "Sample"; r.level = level; r.fame = fame; r.lastObservedAlive = played; r.lastSeen = seen; r.dead = dead;
        for (int i = 0; i < 8; i++) r.stats[i] = CAPS[i] - (7 - i < below ? 1 : 0);
        if (below < 0) r.stats[7] = null;
        return r;
    }

    public static CharacterRosterQuery.Row row(RosterDefinitions definitions, int id, int classId, String className, Integer level, Long fame,
                                               int below, long played, long seen, boolean dead) {
        return new CharacterRosterQuery.Row(record(id, classId, className, level, fame, below, played, seen, dead), definitions);
    }

    /** {@code count} rows across the fixture classes: every tenth dead, every seventh with maxed unknown, every eleventh fame unknown. */
    public static List<CharacterRosterQuery.Row> manyRows(RosterDefinitions definitions, int count, long now) {
        List<CharacterRosterQuery.Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Spec spec = ROSTER.get(i % ROSTER.size());
            rows.add(row(definitions, 1_000 + i, spec.classId(), spec.className(), 1 + i % 20, i % 11 == 0 ? null : 100L * i,
                i % 7 == 0 ? -1 : i % 9, now - i * 60_000L, now - i * 30_000L, i % 10 == 0));
        }
        return rows;
    }

    /** A card last played (and last seen) at {@code lastPlayed}. */
    public static CharacterCardModel card(int id, String className, Integer level, Long fame, Integer maxed, Boolean seasonal,
                                          boolean playingNow, boolean dead, long lastPlayed) {
        return new CharacterCardModel(ACCOUNT + ":" + id, "Sample", WIZARD, className, null, level, fame, maxed, seasonal, lastPlayed, lastPlayed, playingNow, dead);
    }

    /** The Wizard (KEY) in game: the roster's base stats with +50 Life and +12 on the others. */
    public static LiveCharacter.Snapshot live(long now) {
        int[] base = ROSTER.get(0).stats().clone(), totals = new int[8];
        for (int i = 0; i < 8; i++) totals[i] = base[i] + (i == 0 ? 50 : 12);
        return new LiveCharacter.Snapshot(ACCOUNT, 101, WIZARD, "Sample", null, 20, 1_234L, totals, base, new int[]{2593, 2856, 3113, -1},
            null, null, null, null, null, now);
    }

    /**
     * Makes shell tests deterministic until the returned handle is closed: RosterDefinitions.current() returns definitions(), and
     * CharacterClass knows the fixture classes' names and CAPS (other classes keep theirs). Waits for a running asset read first.
     */
    public static AutoCloseable installDefinitions() throws Exception {
        List<Runnable> undo = new ArrayList<>();
        Map<Integer, int[]> knownCaps = get(CharacterClass.class, "CLASS_MAX_STATS");
        Map<Integer, String> knownNames = get(CharacterClass.class, "CLASS_NAME");
        TreeMap<Integer, int[]> caps = new TreeMap<>(knownCaps);
        TreeMap<Integer, String> names = new TreeMap<>(knownNames);
        for (Spec spec : ROSTER) { caps.put(spec.classId(), CAPS.clone()); names.put(spec.classId(), spec.className()); }
        undo.add(swap(CharacterClass.class, "CLASS_MAX_STATS", caps));
        undo.add(swap(CharacterClass.class, "CLASS_NAME", names));
        RosterDefinitions.current(); // starts the asset read for this root when none ran yet
        Field lock = field(RosterDefinitions.class, "LOCK"), running = field(RosterDefinitions.class, "running");
        Field current = field(RosterDefinitions.class, "current");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            synchronized (lock.get(null)) {
                if (!running.getBoolean(null)) {
                    Object previous = current.get(null);
                    current.set(null, definitions());
                    undo.add(() -> {
                        try { synchronized (lock.get(null)) { current.set(null, previous); } }
                        catch (IllegalAccessException e) { throw new AssertionError(e); }
                    });
                    break;
                }
            }
            if (System.nanoTime() > end) throw new AssertionError("The roster definitions reader did not finish");
            Thread.sleep(20);
        }
        return () -> { for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run(); };
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Class<?> type, String name) throws ReflectiveOperationException { return (T) field(type, name).get(null); }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Runnable swap(Class<?> type, String name, Object value) throws ReflectiveOperationException {
        Field field = field(type, name);
        Object previous = field.get(null);
        field.set(null, value);
        return () -> { try { field.set(null, previous); } catch (IllegalAccessException e) { throw new AssertionError(e); } };
    }
}
```

`src/test/java/tomato/gui/glance/character/CharacterCardRendererTest.java`:
```java
package tomato.gui.glance.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;

/** The painted card: its text, unknown never shown as zero, the Playing now marker and the Seasonal chip; one component for every cell. */
public class CharacterCardRendererTest {
    private static final long NOW = System.currentTimeMillis(), HOUR = 3_600_000L; // KitFormat.relative reads the real clock
    private Locale format;
    private Font font;

    @Before public void usFormatAndFont13() throws Exception {
        format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        SwingUtilities.invokeAndWait(() -> { font = ContentStyle.body(); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13)); });
    }
    @After public void restore() throws Exception {
        Locale.setDefault(Locale.Category.FORMAT, format);
        SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(font));
    }

    @Test public void aLiveCardShowsClassLevelFameMaxedSeasonAndPlayingNow() {
        CharacterCardModel card = CharacterFixtures.card(101, "Wizard", 20, 1_234L, 7, true, true, false, NOW - 2 * HOUR);
        CharacterCardRenderer.Lines simple = CharacterCardRenderer.lines(card, false);
        assertEquals("Wizard", simple.title());
        assertEquals("Level 20 · Fame 1,234", simple.meta());
        assertEquals("Simple hides the character ID (spec §3.2)", "Sample", simple.identity());
        assertEquals("7/8", simple.maxed());
        assertEquals(7, simple.pips());
        assertEquals("The last time in game, as the Last played sort", "Played 2 h ago", simple.seen());
        assertEquals("Playing now", simple.status());
        assertEquals("Seasonal", simple.chip());
        assertEquals("Analyst shows it", "Sample · #101", CharacterCardRenderer.lines(card, true).identity());
        assertEquals("Sample, Wizard level 20, 7 of 8 maxed, playing now", card.accessibleName());
    }

    @Test public void unknownValuesShowADashNeverZero() {
        CharacterCardModel card = CharacterFixtures.card(104, "Knight", null, null, null, null, false, false, 0);
        CharacterCardRenderer.Lines lines = CharacterCardRenderer.lines(card, false);
        assertEquals("Level — · Fame —", lines.meta());
        assertEquals("An unknown maxed count is \"—\", never 0/8", "—", lines.maxed());
        assertEquals("No pip counts as filled", -1, lines.pips());
        assertEquals("Last played unknown", lines.seen());
        assertEquals("No chip for an unknown season", "", lines.chip());
        assertEquals("", lines.status());
        assertEquals("Sample, Knight, maxed stats unknown", card.accessibleName());
    }

    @Test public void aCharacterNeverPlayedShowsWhenItWasLastSeen() {
        CharacterCardModel listed = new CharacterCardModel(CharacterFixtures.ACCOUNT + ":109", "Sample", CharacterFixtures.WIZARD, "Wizard", null,
            20, 10L, 8, false, 0, NOW - 10 * 60_000L, false, false);
        assertEquals("Only a character list saw it: Seen, not Played", "Seen 10 min ago", CharacterCardRenderer.lines(listed, false).seen());
    }

    @Test public void aDeadCardSaysSoAndARegularCardHasNoSeasonChip() {
        CharacterCardModel card = CharacterFixtures.card(107, "Necromancer", 20, 640L, 4, false, false, true, NOW - HOUR);
        CharacterCardRenderer.Lines lines = CharacterCardRenderer.lines(card, false);
        assertEquals("Dead", lines.status());
        assertEquals("", lines.chip());
        assertEquals(4, lines.pips());
        assertEquals("Sample, Necromancer level 20, 4 of 8 maxed, marked dead", card.accessibleName());
    }

    @Test public void oneComponentServesEveryCellPaintsTheCardAndGrowsWithTheFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterCardRenderer renderer = new CharacterCardRenderer();
            JList<CharacterCardModel> list = new JList<>();
            CharacterCardModel live = CharacterFixtures.card(101, "Wizard", 20, 1_234L, 7, true, true, false, NOW - HOUR);
            CharacterCardModel unknown = CharacterFixtures.card(104, "Knight", 14, null, null, null, false, false, NOW - HOUR);
            Component first = renderer.getListCellRendererComponent(list, live, 0, true, true);
            Component second = renderer.getListCellRendererComponent(list, unknown, 1, false, false);
            assertSame("One reusable component, no per-card trees (spec §9)", first, second);
            assertEquals(unknown.accessibleName(), renderer.getAccessibleContext().getAccessibleName());
            assertTrue(renderer.getToolTipText(), renderer.getToolTipText().contains("Maxed stats unknown"));
            Dimension small = renderer.cellSize();
            renderer.setSize(small);
            BufferedImage image = new BufferedImage(small.width, small.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            renderer.paint(g);
            g.dispose();
            assertTrue("The card is painted", painted(image));
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18));
            Dimension large = renderer.cellSize();
            assertTrue("Cards grow with the body font: " + small + " -> " + large, large.width > small.width && large.height > small.height);
        });
    }

    private static boolean painted(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }
}
```

`src/test/java/tomato/gui/glance/character/CharacterGalleryTest.java`:
```java
package tomato.gui.glance.character;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.*;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.*;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.Tokens;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** The gallery: living cards and a collapsed Graveyard, accessible names, Enter/Space/double-click, selection and empty states. */
public class CharacterGalleryTest {
    private static final long NOW = System.currentTimeMillis(), HOUR = 3_600_000L;
    private static final String GRAVEYARD_KEY = Collapsible.PREFIX + CharacterGallery.GRAVEYARD;
    private static final List<CharacterCardModel> ALIVE = List.of(
        CharacterFixtures.card(101, "Wizard", 20, 1_234L, 7, false, true, false, NOW - HOUR),
        CharacterFixtures.card(102, "Warrior", 20, 5_400L, 8, true, false, false, NOW - 2 * HOUR),
        CharacterFixtures.card(104, "Knight", 14, null, null, null, false, false, NOW - 3 * HOUR));
    private static final List<CharacterCardModel> DEAD = List.of(
        CharacterFixtures.card(107, "Necromancer", 20, 640L, 4, false, false, true, NOW - 5 * HOUR),
        CharacterFixtures.card(108, "Paladin", 18, 95L, 0, false, false, true, NOW - 6 * HOUR));
    private final Map<String, String> prefs = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put); // application() is never changed
    private final List<String> opened = new ArrayList<>();
    private String graveyard;
    private Locale format;

    @Before public void isolate() {
        graveyard = PropertiesManager.getProperty(GRAVEYARD_KEY);
        PropertiesManager.setProperties(GRAVEYARD_KEY, "");
        format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }
    @After public void restore() {
        PropertiesManager.setProperties(GRAVEYARD_KEY, graveyard == null ? "" : graveyard);
        Locale.setDefault(Locale.Category.FORMAT, format);
    }

    @Test public void livingCardsFillTheGalleryAndTheDeadACollapsedGraveyard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards"), graves = list(gallery, "character-graveyard-cards");
            assertEquals(ALIVE, gallery.alive());
            assertEquals(DEAD, gallery.dead());
            assertEquals(3, cards.getModel().getSize());
            assertEquals(2, graves.getModel().getSize());
            assertEquals("A wrapping grid of cards", JList.HORIZONTAL_WRAP, cards.getLayoutOrientation());
            CharacterCardRenderer renderer = (CharacterCardRenderer) cards.getCellRenderer();
            assertEquals("Fixed cells: no per-card measuring", renderer.cellSize(), new java.awt.Dimension(cards.getFixedCellWidth(), cards.getFixedCellHeight()));
            Collapsible section = named(gallery, "character-graveyard", Collapsible.class);
            assertTrue(section.isVisible());
            assertEquals("Graveyard (2)", section.toggle().getText());
            assertFalse("The Graveyard starts collapsed", section.expanded());
            gallery.apply(ALIVE, List.of());
            assertFalse("No Graveyard without dead characters", section.isVisible());
        });
    }

    @Test public void eachCardHasAnAccessibleNameAndUnknownIsNeverZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards"), graves = list(gallery, "character-graveyard-cards");
            assertEquals("Characters", cards.getAccessibleContext().getAccessibleName());
            assertEquals("Sample, Wizard level 20, 7 of 8 maxed, playing now", child(cards, 0));
            assertEquals("Sample, Knight level 14, maxed stats unknown", child(cards, 2));
            assertEquals("Sample, Necromancer level 20, 4 of 8 maxed, marked dead", child(graves, 0));
        });
    }

    @Test public void enterSpaceAndDoubleClickOpenTheSelectedCharacter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            for (String key : new String[] {"ENTER", "SPACE"})
                assertEquals(key + " opens the card", "open-character", cards.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key)));
            cards.setSelectedIndex(1);
            cards.getActionMap().get("open-character").actionPerformed(null);
            assertEquals(List.of(ALIVE.get(1).key()), opened);
            Rectangle cell = cards.getCellBounds(2, 2);
            cards.dispatchEvent(click(cards, cell, 1));
            assertEquals("A single click only selects", 1, opened.size());
            cards.dispatchEvent(click(cards, cell, 2));
            assertEquals(List.of(ALIVE.get(1).key(), ALIVE.get(2).key()), opened);
            JList<CharacterCardModel> graves = list(gallery, "character-graveyard-cards");
            graves.setSelectedIndex(0);
            graves.getActionMap().get("open-character").actionPerformed(null);
            assertEquals("Graveyard cards open their sheet too", DEAD.get(0).key(), opened.get(2));
        });
    }

    @Test public void aRefreshKeepsTheSelectedCharacterAndAnEqualOneChangesNothing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            cards.setSelectedIndex(1);
            List<CharacterCardModel> reordered = List.of(ALIVE.get(2), ALIVE.get(0), ALIVE.get(1));
            gallery.apply(reordered, DEAD);
            assertEquals("The selection follows the character, not the index", ALIVE.get(1).key(), cards.getSelectedValue().key());
            int[] events = new int[1];
            cards.getModel().addListDataListener(new ListDataListener() {
                @Override public void intervalAdded(ListDataEvent e) { events[0]++; }
                @Override public void intervalRemoved(ListDataEvent e) { events[0]++; }
                @Override public void contentsChanged(ListDataEvent e) { events[0]++; }
            });
            gallery.apply(new ArrayList<>(reordered), DEAD);
            assertEquals("Equal cards are not applied again", 0, events[0]);
        });
    }

    @Test public void emptyStatesSayWhetherFiltersHideSavedCharacters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(List.of(), List.of(), false);
            assertNotNull("Nothing saved yet", named(gallery, "character-gallery-empty", EmptyState.class));
            assertNull(named(gallery, "character-cards", JList.class));
            gallery.apply(List.of(), List.of(), true);
            assertNotNull("Saved characters hidden by filters", named(gallery, "character-gallery-no-match", EmptyState.class));
            gallery.apply(ALIVE, List.of());
            assertNotNull(named(gallery, "character-cards", JList.class));
            assertNull(named(gallery, "character-gallery-no-match", EmptyState.class));
        });
    }

    @Test public void theGraveyardSitsRightBelowTheLivingCardsInATallPage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            gallery.setSize(900, 2_000); // the page's viewport is far taller than three cards
            for (int pass = 0; pass < 3; pass++) layout(gallery);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            Collapsible graveyard = named(gallery, "character-graveyard", Collapsible.class);
            java.awt.Rectangle above = SwingUtilities.convertRectangle(cards.getParent(), cards.getBounds(), gallery);
            java.awt.Rectangle below = SwingUtilities.convertRectangle(graveyard.getParent(), graveyard.getBounds(), gallery);
            assertEquals("The cards keep their wrapped height", cards.getPreferredSize().height, above.height);
            assertTrue("The Graveyard follows the cards, not the page's bottom: " + above + " then " + below,
                below.y - (above.y + above.height) <= Tokens.L + 1);
        });
    }

    @Test public void anUnreadableJournalIsUnavailableAndAFailedSaveWarnsAboveTheCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            String unreadable = "Cannot read Characters/journal.json. Original preserved; saving disabled.";
            gallery.apply(List.of(), List.of(), false, unreadable);
            EmptyState unavailable = named(gallery, "character-gallery-unavailable", EmptyState.class);
            assertNotNull("Not \"No characters yet\"", unavailable);
            assertNull(named(gallery, "character-gallery-empty", EmptyState.class));
            assertEquals(unreadable, unavailable.getAccessibleContext().getAccessibleDescription());
            gallery.apply(ALIVE, DEAD, false, "Save failed • check access to Characters/journal.json");
            Banner storage = named(gallery, "character-gallery-storage", Banner.class);
            assertTrue(storage.isVisible()); assertTrue(storage.warns());
            assertEquals("Save failed • check access to Characters/journal.json", storage.text());
            gallery.apply(ALIVE, DEAD, false, null);
            assertFalse(storage.isVisible());
        });
    }

    @Test public void aUserSelectionIsReportedOnceAcrossBothListsAndTheRosterCanSelectACard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> selected = new ArrayList<>();
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.onSelect(selected::add);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards"), graves = list(gallery, "character-graveyard-cards");
            cards.setSelectedIndex(2);
            assertEquals(List.of(ALIVE.get(2).key()), selected);
            graves.setSelectedIndex(0);
            assertEquals("One selection across the cards and the Graveyard", -1, cards.getSelectedIndex());
            assertEquals(List.of(ALIVE.get(2).key(), DEAD.get(0).key()), selected);
            assertSame("The Graveyard is collapsed: focus returns to the cards", cards, gallery.focusTarget());
            gallery.select(ALIVE.get(1).key());
            assertEquals(1, cards.getSelectedIndex()); assertEquals(-1, graves.getSelectedIndex());
            assertEquals("A selection made by the roster is not reported back", 2, selected.size());
        });
    }

    @Test public void analystModeShowsCharacterIdsOnTheCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            CharacterCardRenderer renderer = (CharacterCardRenderer) cards.getCellRenderer();
            renderer.getListCellRendererComponent(cards, ALIVE.get(0), 0, false, false);
            assertEquals("Sample", renderer.shown().identity());
            mode.set(DisplayModeModel.Mode.ANALYST);
            renderer.getListCellRendererComponent(cards, ALIVE.get(0), 0, false, false);
            assertEquals("Sample · #101", renderer.shown().identity());
        });
    }

    @SuppressWarnings("unchecked")
    private static JList<CharacterCardModel> list(CharacterGallery gallery, String name) { return named(gallery, name, JList.class); }
    private static void layout(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) if (child instanceof java.awt.Container) layout((java.awt.Container) child);
    }
    private static String child(JList<?> list, int index) {
        return list.getAccessibleContext().getAccessibleChild(index).getAccessibleContext().getAccessibleName();
    }
    private static MouseEvent click(JList<?> list, Rectangle cell, int count) {
        return new MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + cell.width / 2, cell.y + cell.height / 2,
            count, false, MouseEvent.BUTTON1);
    }
}
```

`src/test/java/tomato/gui/character/TableViewRule.java`:
```java
package tomato.gui.character;

import org.junit.rules.ExternalResource;
import util.PropertiesManager;

/** Pins the roster's Table view for tests of the roster table (the page opens on the gallery) and restores the saved choice. */
public final class TableViewRule extends ExternalResource {
    private static final String KEY = "ui.characters.view";
    private String saved;

    @Override protected void before() { saved = PropertiesManager.getProperty(KEY); PropertiesManager.setProperties(KEY, "table"); }
    @Override protected void after() { PropertiesManager.setProperties(KEY, saved == null ? "" : saved); }
}
```

`src/test/java/tomato/gui/character/RosterViewsTest.java`:
```java
package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.character.CharacterCardModel;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.modern.ContentStyle;
import ui.UiTestLayout;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Gallery | Table: sort orders and memory, the view switch per mode, exactly the table's visible rows, and 500 characters on the EDT. */
public class RosterViewsTest {
    private static final long NOW = 1_790_000_000_000L, HOUR = 3_600_000L;
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    private final List<String> opened = new ArrayList<>();
    private final String[] live = {null};
    private FilterBar bar;
    private WrapRow row;
    private JComponent table;

    /**
     * Alive: 1 Wizard 7/8 fame 1,234 played 1 h ago; 2 Warrior 8/8 fame 5,400 played 3 h ago; 3 Priest (fame and maxed unknown,
     * never played, seen 10 min ago); 4 Archer 6/8 fame 15,020 played 2 h ago. Dead: 5 Archer.
     */
    private static List<CharacterRosterQuery.Row> rows(RosterDefinitions d) {
        return List.of(
            CharacterFixtures.row(d, 1, 782, "Wizard", 20, 1_234L, 1, NOW - HOUR, NOW - HOUR, false),
            CharacterFixtures.row(d, 2, 797, "Warrior", 20, 5_400L, 0, NOW - 3 * HOUR, NOW - 3 * HOUR, false),
            CharacterFixtures.row(d, 3, 784, "Priest", 20, null, -1, 0, NOW - 600_000L, false),
            CharacterFixtures.row(d, 4, 775, "Archer", 20, 15_020L, 2, NOW - 2 * HOUR, NOW - 2 * HOUR, false),
            CharacterFixtures.row(d, 5, 775, "Archer", 20, 640L, 4, NOW - 5 * HOUR, NOW - 5 * HOUR, true));
    }

    /** A new roster view over {@code rows} with fresh stand-ins for the table and the filter bar, refreshed once. EDT. */
    private RosterViews views(List<CharacterRosterQuery.Row> rows) {
        table = new JPanel();
        table.setName("table-stand-in");
        bar = new FilterBar("characters-test");
        row = new WrapRow();
        bar.search(row);
        RosterViews views = new RosterViews(table, bar, row, new RosterViews.Source() {
            @Override public List<CharacterRosterQuery.Row> rows() { return rows; }
            @Override public boolean saved() { return !rows.isEmpty(); }
            @Override public String problem() { return null; }
            @Override public String liveKey() { return live[0]; }
            @Override public String selectedKey() { return null; }
            @Override public void select(String key) { }
            @Override public void open(String key) { opened.add(key); }
        }, mode, prefs::get, prefs::put);
        views.refresh(); // CharacterJournalGUI's first filter() does this through its rows listener
        return views;
    }

    @Test public void eachSortOrdersTheCardsWithUnknownsLastAndIsRemembered() throws Exception {
        List<CharacterRosterQuery.Row> rows = rows(CharacterFixtures.definitions());
        SwingUtilities.invokeAndWait(() -> {
            RosterViews views = views(rows);
            assertEquals("Last played is the default", RosterViews.Sort.LAST_PLAYED, views.sort());
            assertEquals("Played 1 h, 2 h, 3 h ago, then never", List.of("1", "4", "2", "3"), ids(views.gallery().alive()));
            assertEquals("The dead sit in the Graveyard", List.of("5"), ids(views.gallery().dead()));
            assertEquals("Wizard", views.gallery().alive().get(0).className());
            JComboBox<?> sort = named(bar, "character-sort", JComboBox.class);
            sort.setSelectedItem(RosterViews.Sort.FAME);
            assertEquals("fame", prefs.get(RosterViews.SORT_KEY));
            assertEquals("Unknown fame sorts last, never as 0", List.of("4", "2", "1", "3"), ids(views.gallery().alive()));
            sort.setSelectedItem(RosterViews.Sort.CLASS);
            assertEquals(List.of("4", "3", "2", "1"), ids(views.gallery().alive()));
            sort.setSelectedItem(RosterViews.Sort.MAXED);
            assertEquals("Unknown maxed sorts last, never as 0/8", List.of("2", "1", "4", "3"), ids(views.gallery().alive()));
            assertEquals("maxed", prefs.get(RosterViews.SORT_KEY));
            RosterViews again = views(rows);
            assertEquals("A new roster view restores the sort", RosterViews.Sort.MAXED, again.sort());
            assertEquals(List.of("2", "1", "4", "3"), ids(again.gallery().alive()));
        });
    }

    @Test public void simpleOffersTheOtherViewInTheOverflowMenuAndAnalystAToggle() throws Exception {
        List<CharacterRosterQuery.Row> rows = rows(CharacterFixtures.definitions());
        SwingUtilities.invokeAndWait(() -> {
            RosterViews views = views(rows);
            assertTrue("The gallery is the default view", views.galleryShown());
            assertTrue(views.gallery().isVisible());
            assertFalse(table.isVisible());
            assertSame("The table stays in the tree, hidden", views.body(), table.getParent());
            SegmentedControl toggle = named(bar, "character-view", SegmentedControl.class);
            assertFalse("Simple: no toggle in the filter row", toggle.isVisible());
            JMenuItem item = bar.overflow().item("Table view");
            assertNotNull("Simple: the Table view is in the ⋯ menu", item);
            assertTrue(item.isVisible());
            assertTrue(bar.overflow().isVisible());
            assertTrue("The gallery sorts in the filter row", shown(named(bar, "character-sort", JComboBox.class), bar));
            assertSame("…in the search's wrapping row, so it wraps below the search at narrow widths", row,
                named(bar, "character-view-controls", JPanel.class).getParent());
            item.doClick();
            assertFalse(views.galleryShown());
            assertTrue(table.isVisible());
            assertFalse(views.gallery().isVisible());
            assertEquals("table", prefs.get(RosterViews.VIEW_KEY));
            assertEquals("Gallery view", item.getText());
            assertFalse("The table sorts by its headers", shown(named(bar, "character-sort", JComboBox.class), bar));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst: a Gallery/Table toggle in the filter row", toggle.isVisible());
            assertEquals(1, toggle.selected());
            assertFalse(item.isVisible());
            assertFalse("A ⋯ menu with nothing to show is hidden", bar.overflow().isVisible());
            named(toggle, "character-view-0", JToggleButton.class).doClick();
            assertTrue(views.galleryShown());
            assertEquals("gallery", prefs.get(RosterViews.VIEW_KEY));
            prefs.put(RosterViews.VIEW_KEY, "table");
            assertFalse("A new roster view restores the saved view", views(rows).galleryShown());
        });
    }

    @Test public void theGalleryShowsExactlyTheRosterTablesVisibleRows() throws Exception {
        String view = PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        CharacterJournal journal = CharacterFixtures.journal(temp.getRoot().toPath().resolve("Characters").resolve("journal.json"), NOW);
        journal.notes(CharacterFixtures.ACCOUNT + ":105", "bow practice");
        RosterDefinitions definitions = CharacterFixtures.definitions();
        try {
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> NOW, () -> definitions);
                CharacterGallery gallery = named(panel, "character-gallery", CharacterGallery.class);
                assertEquals(8, panel.visibleRows().size());
                assertEquals(rowKeys(panel.visibleRows()), cardKeys(gallery));
                assertEquals("Marked-dead characters are in the Graveyard", List.of("107", "108"), ids(gallery.dead()));
                JTextField search = named(panel, "character-search", JTextField.class);
                search.setText("bow practice");
                assertEquals(List.of(CharacterFixtures.ACCOUNT + ":105"), rowKeys(panel.visibleRows()));
                assertEquals("One search serves both views", rowKeys(panel.visibleRows()), cardKeys(gallery));
                search.setText("");
                named(panel, "character-facet-2", JComboBox.class).setSelectedIndex(1); // Needs Life
                assertEquals(List.of("103", "104", "107", "108"), panel.visibleRows().stream().map(r -> String.valueOf(r.record.characterId)).sorted().collect(Collectors.toList()));
                assertEquals("One filter drawer serves both views", rowKeys(panel.visibleRows()), cardKeys(gallery));
                named(panel, "character-facet-2", JComboBox.class).setSelectedIndex(0);
                assertTrue(gallery.alive().stream().noneMatch(CharacterCardModel::playingNow));
                panel.setLiveKey(() -> CharacterFixtures.KEY);
                assertEquals("Playing now marks exactly the live character's journal key", List.of(CharacterFixtures.KEY),
                    gallery.alive().stream().filter(CharacterCardModel::playingNow).map(CharacterCardModel::key).collect(Collectors.toList()));
            });
        } finally {
            PropertiesManager.setProperties(RosterViews.VIEW_KEY, view == null ? "" : view);
            journal.close();
        }
    }

    /** Spec §9: 500 characters sort, map and apply within 50 ms on the EDT (every sample); one viewport paint is logged. */
    @Test public void fiveHundredCharactersRefreshWithinFiftyMillisecondsAndPaintOnePass() throws Exception {
        List<CharacterRosterQuery.Row> rows = CharacterFixtures.manyRows(CharacterFixtures.definitions(), 500, NOW);
        RosterViews[] views = new RosterViews[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            views[0] = views(rows);
            frame[0] = new JFrame("Gallery timing - synthetic validation");
            frame[0].setContentPane(ContentStyle.page(null, views[0].body(), null));
            frame[0].setSize(1240, 800);
            frame[0].setVisible(true);
        });
        try {
            long[] micros = new long[30];
            for (int i = 0; i < 50; i++) {
                int sample = i - 20;
                String key = rows.get(1 + i % 2).record.key; // a different card plays each turn, so no refresh is skipped as equal
                SwingUtilities.invokeAndWait(() -> {
                    live[0] = key;
                    long start = System.nanoTime();
                    views[0].refresh();
                    if (sample >= 0) micros[sample] = (System.nanoTime() - start) / 1_000;
                });
            }
            Arrays.sort(micros);
            System.out.println("Gallery refresh of 500 characters on the EDT over 30 samples: median " + micros[15] + " us, p95 "
                + micros[28] + " us, max " + micros[29] + " us");
            assertTrue("Sort, map and apply take at most 50 ms on every sample; max " + micros[29] + " us", micros[29] <= 50_000);
            SwingUtilities.invokeAndWait(() -> {
                UiTestLayout.settle(frame[0]);
                BufferedImage image = new BufferedImage(frame[0].getWidth(), frame[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                long start = System.nanoTime();
                frame[0].getContentPane().paint(g);
                long painted = (System.nanoTime() - start) / 1_000;
                g.dispose();
                JList<?> cards = named(views[0].body(), "character-cards", JList.class);
                Rectangle visible = cards.getVisibleRect();
                int first = cards.locationToIndex(visible.getLocation());
                int last = cards.locationToIndex(new Point(visible.x + visible.width - 1, visible.y + visible.height - 1));
                System.out.println("Gallery paint of one 1240x800 viewport (cards " + first + ".." + last + " of " + cards.getModel().getSize()
                    + " visible): " + painted + " us");
                assertTrue("The cards wrap into rows taller than the window, so only the visible ones paint", cards.getHeight() > 800);
                assertTrue("More than one card per row at 1240 px", cards.getCellBounds(1, 1).y == cards.getCellBounds(0, 0).y);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    private static List<String> ids(List<CharacterCardModel> cards) { return cards.stream().map(CharacterCardModel::characterId).collect(Collectors.toList()); }
    private static List<String> rowKeys(List<CharacterRosterQuery.Row> rows) { return rows.stream().map(r -> r.record.key).sorted().collect(Collectors.toList()); }
    private static List<String> cardKeys(CharacterGallery gallery) { return gallery.cards().stream().map(CharacterCardModel::key).sorted().collect(Collectors.toList()); }
    /** Visible up to {@code root}: there is no window, so isShowing is false everywhere. */
    private static boolean shown(Component component, Container root) {
        for (Component c = component; c != null; c = c.getParent()) { if (!c.isVisible()) return false; if (c == root) return true; }
        return false;
    }
}
```

`src/test/java/tomato/gui/character/CharacterRosterViewTest.java` — **add beside**. Add `import tomato.gui.glance.character.CharacterCardModel;` after `import tomato.backend.data.RosterDefinitions;`, then insert before `    @Test public void visibleRowsFollowSearchAndSortAndNotifyListeners() throws Exception {`:
```java
    @Test public void backFocusesTheOpenedCardInTheGalleryAndTheRowInTheTable() throws Exception {
        String saved = util.PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        try (CharacterJournal journal = journal()) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
                JList<?> cards = RosterFixtures.named(view.listPanel(), "character-cards", JList.class);
                cards.setSelectedIndex(1);
                String key = ((CharacterCardModel) cards.getSelectedValue()).key();
                assertEquals("A card selection is the list's selection", key, view.currentKey());
                cards.getActionMap().get("open-character").actionPerformed(null);
                assertTrue(view.showingSheet()); assertEquals(key, view.sheet().key());
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertFalse(view.showingSheet());
                assertSame("Back focuses the gallery", cards, view.listPanel().focusTarget());
                assertEquals("…on the card that was open", key, ((CharacterCardModel) cards.getSelectedValue()).key());
                view.listPanel().views().showGallery(false, false);
                RosterFixtures.enter(view);
                RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
                assertSame("In the Table view, Back focuses the table", RosterFixtures.named(view.listPanel(), "character-roster", JTable.class),
                    view.listPanel().focusTarget());
            });
        } finally { util.PropertiesManager.setProperties(RosterViews.VIEW_KEY, saved == null ? "" : saved); }
    }

```

`src/test/java/tomato/gui/character/CharacterViewStateTest.java` — **add beside**. Add `import tomato.gui.kit.Banner;`, `import tomato.gui.kit.DisplayModeModel;` and `import tomato.gui.kit.FilterBar;` after `import tomato.realmshark.RealmCharacter;`, then insert before `    private static String savedSelection(Memory memory) {`:
```java
    @Test public void savedViewActionsAreInTheOverflowMenuAndOnlyAFailureShows() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("menu.json"));
        Memory memory = new Memory(); RosterDefinitions definitions = RosterDefinitions.empty();
        CharacterJournalGUI[] view = new CharacterJournalGUI[1];
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.Mode before = DisplayModeModel.application().mode();
            view[0] = new CharacterJournalGUI(journal, () -> 5000, () -> definitions); view[0].bindViewState(memory.store);
            FilterBar bar = named(view[0], "characters-filter-bar", FilterBar.class);
            try {
                for (DisplayModeModel.Mode mode : DisplayModeModel.Mode.values()) {
                    DisplayModeModel.application().set(mode);
                    assertNotNull(mode + ": Save view state is in the ⋯ menu (spec §3.2)", bar.overflow().item("Save view state"));
                    assertNotNull(mode + ": so is Reset saved view state", bar.overflow().item("Reset saved view state"));
                    assertTrue(mode + ": the ⋯ menu shows", bar.overflow().isVisible());
                }
            } finally { DisplayModeModel.application().set(before); }
            assertNull("No view-state buttons in the page", named(view[0], "characters-live-roster-save-state", JButton.class));
            assertFalse("A good state says nothing", named(view[0], "character-view-state", Banner.class).isVisible());
            memory.fail = true;
            bar.overflow().item("Save view state").doClick();
        });
        SwingUtilities.invokeAndWait(() -> { }); // the save's status arrives on the EDT
        SwingUtilities.invokeAndWait(() -> {
            Banner banner = named(view[0], "character-view-state", Banner.class);
            assertTrue("A failed save warns in the page", banner.isVisible()); assertTrue(banner.warns());
            assertTrue(banner.text(), banner.text().startsWith("View state save failed"));
            memory.fail = false;
            named(view[0], "characters-filter-bar", FilterBar.class).overflow().item("Save view state").doClick();
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> assertFalse("A later good save clears the warning", named(view[0], "character-view-state", Banner.class).isVisible()));
        journal.close();
    }

```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.RosterViewsTest" --tests "tomato.gui.character.CharacterRosterViewTest" --tests "tomato.gui.character.CharacterViewStateTest"`
Expected: FAIL — compilation errors `cannot find symbol: class CharacterCardModel`, `class CharacterGallery`, `class RosterViews` and `method focusTarget()`.

- [ ] **Step 3: `RosterViewState` reports its status**

The Characters page keeps "Save view state" and "Reset saved view state" in its ⋯ menu (spec §3.2) and shows their status only when it is a failure, so `RosterViewState` tells its host about status changes. **Add beside** in `src/main/java/tomato/gui/roster/RosterViewState.java`; its own controls and texts are unchanged, and every other roster keeps them:
1. Replace `    private long changeGeneration;` with that line followed by:
```java
    private final List<Runnable> statusListeners = new ArrayList<>();
    /** The latest status is a failure: a save failed, or the saved state could not be read. */
    private boolean problem;
```
2. Replace `            blocked = true; status.setText("Saved view state unavailable. Current controls remain usable; Reset saved view state to replace it.");` with `            blocked = true; status("Saved view state unavailable. Current controls remain usable; Reset saved view state to replace it.", true);`.
3. Replace `        status.setText("Saved state reset. Current controls and notes are retained; Save view state remembers them.");` with `        status("Saved state reset. Current controls and notes are retained; Save view state remembers them.", false);`.
4. In `watch`, replace:
```java
            if (request == saveGeneration) status.setText(failure == null && result != null && result.isSuccess()
                ? "View state saved." : "View state save failed; current controls remain active. Save view state retries.");
```
with:
```java
            if (request != saveGeneration) return;
            boolean saved = failure == null && result != null && result.isSuccess();
            status(saved ? "View state saved." : "View state save failed; current controls remain active. Save view state retries.", !saved);
```
5. Insert before `    public static int number(Map<String, String> values, String key, int fallback, int minimum, int maximum) {`:
```java
    private void status(String text, boolean failed) {
        problem = failed;
        status.setText(text);
        for (Runnable listener : new ArrayList<>(statusListeners)) listener.run();
    }
    /** The latest status line: saved, save failed, reset, or saved state unavailable ("" before any). */
    public String statusText() { return status.getText(); }
    /** True while the latest status is a failure: the last save failed or the saved state could not be read. */
    public boolean statusProblem() { return problem; }
    /** Runs on the EDT after every status change, for hosts that offer the actions in a menu and show only failures. */
    public void onStatus(Runnable listener) { statusListeners.add(Objects.requireNonNull(listener)); }
    /** What the Reset saved view state button does, for hosts that offer it in a menu. */
    public void resetSaved() { reset(); }

```

- [ ] **Step 4: Create `CharacterCardModel`**

`src/main/java/tomato/gui/glance/character/CharacterCardModel.java`:
```java
package tomato.gui.glance.character;

import java.util.Objects;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.gui.character.CharacterRosterQuery;
import tomato.realmshark.enums.CharacterClass;

/**
 * One gallery card (spec §6.2), built on the EDT from a roster row CharacterJournalGUI already projected. {@code key} is the
 * journal key the sheet opens; {@code maxed} 0–8 or null when unknown (never shown as 0); {@code seasonal} null when unknown;
 * {@code lastPlayed} the last time in game (the journal's lastObservedAlive) and {@code lastSeen} the last capture of any kind,
 * epoch ms, 0 = unknown; {@code playingNow} when {@code key} equals the in-game character's journal key (exact).
 */
public record CharacterCardModel(String key, String name, int classId, String className, Integer skin, Integer level, Long fame,
                                 Integer maxed, Boolean seasonal, long lastPlayed, long lastSeen, boolean playingNow, boolean dead) {
    public CharacterCardModel {
        Objects.requireNonNull(key, "key");
        name = name == null || name.isBlank() ? null : name;
        className = className(classId, className);
        if (maxed != null && (maxed < 0 || maxed > 8)) maxed = null;
    }

    public static CharacterCardModel of(CharacterRosterQuery.Row row, String liveKey) {
        CharacterRecord r = row.record;
        return new CharacterCardModel(r.key, r.name, r.classId, r.className, r.skin, r.level, r.fame, row.maxed, r.seasonal,
            r.lastObservedAlive, r.lastSeen, r.key.equals(liveKey), r.dead);
    }

    /** The saved class name, else the loaded class definitions' name, else "Class <id>". */
    public static String className(int classId, String saved) {
        if (saved != null && !saved.isBlank()) return saved;
        String known = CharacterClass.getName(classId);
        return known == null || known.isBlank() ? "Class " + classId : known;
    }

    public String characterId() { return key.substring(key.lastIndexOf(':') + 1); }

    /** "<name>, <class> level N, 7 of 8 maxed" (spec §10); an unknown count says so and is never read as zero. */
    public String accessibleName() {
        StringBuilder text = new StringBuilder();
        if (name != null) text.append(name).append(", ");
        text.append(className);
        if (level != null) text.append(" level ").append(level);
        text.append(maxed == null ? ", maxed stats unknown" : ", " + maxed + " of 8 maxed");
        if (playingNow) text.append(", playing now");
        if (dead) text.append(", marked dead");
        return text.toString();
    }
}
```

- [ ] **Step 5: Create `CharacterCardRenderer`**

`src/main/java/tomato/gui/glance/character/CharacterCardRenderer.java`:
```java
package tomato.gui.glance.character;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Paints one character card (spec §6.2, §9): one component reused for every cell of a gallery list, no per-card component tree.
 * A 34 px skin sprite, class, level and fame, the name (and, in Analyst, the character ID), an 8-pip maxed meter ("—" with
 * outlined pips when unknown, never 0/8), a Seasonal chip, when the character last played and a "Playing now" marker; dead
 * characters are dimmed and say "Dead". Sizes follow the body font; colors come from Tokens at paint time.
 */
public final class CharacterCardRenderer extends JComponent implements ListCellRenderer<CharacterCardModel>, Accessible {
    static final int SPRITE = 34, PIPS = 8, PIP_GAP = 3, GAP = 10; // GAP: space between cards (spec §5.2)
    private CharacterCardModel card;
    private Lines lines;
    private boolean selected, focused, analyst;

    /** The text one card paints (painted text is not in the component tree, so tests read it here); {@code pips} -1 = unknown. */
    record Lines(String title, String meta, String identity, String maxed, int pips, String seen, String status, String chip) {}

    public CharacterCardRenderer() { setOpaque(false); }

    void setAnalyst(boolean value) { analyst = value; }

    static Lines lines(CharacterCardModel card, boolean analyst) {
        String name = card.name() == null ? "" : card.name();
        String identity = analyst ? (name.isEmpty() ? "" : name + " · ") + "#" + card.characterId() : name;
        Integer maxed = card.maxed();
        return new Lines(card.className(),
            "Level " + (card.level() == null ? DisplayFormat.UNAVAILABLE : card.level()) + " · Fame " + DisplayFormat.formatInteger(card.fame()),
            identity, maxed == null ? DisplayFormat.UNAVAILABLE : maxed + "/8", maxed == null ? -1 : maxed,
            seen(card),
            card.playingNow() ? "Playing now" : card.dead() ? "Dead" : "",
            Boolean.TRUE.equals(card.seasonal()) ? "Seasonal" : "");
    }

    Lines shown() { return lines; }

    /** "Played 2 h ago" from the last time in game, as the Last played sort; "Seen …" only for a character never played. */
    static String seen(CharacterCardModel card) {
        if (card.lastPlayed() > 0) return "Played " + KitFormat.relative(card.lastPlayed());
        return card.lastSeen() > 0 ? "Seen " + KitFormat.relative(card.lastSeen()) : "Last played unknown";
    }

    /** The list cell: the card plus half the gap between cards on each side, at the current body font. */
    public Dimension cellSize() {
        FontMetrics title = getFontMetrics(Type.emphasis()), caption = getFontMetrics(Type.caption());
        int height = Tokens.M + Math.max(SPRITE, title.getHeight() + 2 * caption.getHeight()) + Tokens.S
            + Math.max(pip(), caption.getHeight()) + Tokens.XS + caption.getHeight() + Tokens.M;
        int width = Math.max(200, Math.round(ContentStyle.body().getSize2D() * 18f));
        return new Dimension(width + GAP, height + GAP);
    }

    @Override public Component getListCellRendererComponent(JList<? extends CharacterCardModel> list, CharacterCardModel value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        card = value;
        selected = isSelected;
        focused = cellHasFocus;
        lines = value == null ? null : lines(value, analyst);
        // The name stays stable while time passes; "Played N ago" is the description.
        getAccessibleContext().setAccessibleName(value == null ? null : value.accessibleName());
        getAccessibleContext().setAccessibleDescription(value == null ? null : lines.seen() + (lines.status().isEmpty() ? "" : " · " + lines.status()));
        setToolTipText(value == null ? null : tooltip(value));
        return this;
    }

    private static String tooltip(CharacterCardModel card) {
        return card.className() + " #" + card.characterId() + " · Level " + (card.level() == null ? DisplayFormat.UNAVAILABLE : card.level())
            + " · Fame " + DisplayFormat.formatInteger(card.fame()) + " · "
            + (card.maxed() == null ? "Maxed stats unknown (needs all 8 base stats and class caps)" : card.maxed() + " of 8 stats maxed")
            + " · Season " + (card.seasonal() == null ? "unknown" : card.seasonal() ? "seasonal" : "regular") + " · "
            + (card.lastPlayed() > 0 ? "Last played " + DisplayFormat.formatTimestamp(card.lastPlayed())
                : card.lastSeen() > 0 ? "Last seen " + DisplayFormat.formatTimestamp(card.lastSeen()) : "Last played unknown");
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (card == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
            int x = GAP / 2, y = GAP / 2, w = getWidth() - GAP, h = getHeight() - GAP;
            RoundRectangle2D shape = new RoundRectangle2D.Float(x + .5f, y + .5f, w - 1, h - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
            g.setColor(Tokens.color(selected ? Tokens.Role.ACCENT_WASH : Tokens.Role.RAISED));
            g.fill(shape);
            g.setColor(Tokens.color(selected || focused ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
            g.setStroke(new BasicStroke(focused ? 2f : 1f));
            g.draw(shape);
            Color ink = Tokens.color(card.dead() ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT), muted = Tokens.color(Tokens.Role.TEXT_MUTED);
            int left = x + Tokens.M, top = y + Tokens.M, right = x + w - Tokens.M;
            Graphics2D sprite = (Graphics2D) g.create();
            if (card.dead()) sprite.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f));
            Sprites.sprite(card.skin() != null && card.skin() > 0 ? card.skin() : card.classId(), SPRITE).paintIcon(this, sprite, left, top);
            sprite.dispose();
            Font titleFont = Type.emphasis(), captionFont = Type.caption();
            FontMetrics titleMetrics = g.getFontMetrics(titleFont), caption = g.getFontMetrics(captionFont);
            int textLeft = left + SPRITE + Tokens.S, titleRight = right;
            if (!lines.chip().isEmpty()) titleRight = chip(g, lines.chip(), Tokens.Tone.INFO, right, top, caption) - Tokens.XS;
            int baseline = top + titleMetrics.getAscent();
            text(g, lines.title(), titleFont, titleMetrics, ink, textLeft, baseline, titleRight - textLeft);
            baseline += titleMetrics.getDescent() + caption.getAscent();
            text(g, lines.meta(), captionFont, caption, ink, textLeft, baseline, right - textLeft);
            text(g, lines.identity(), captionFont, caption, muted, textLeft, baseline + caption.getHeight(), right - textLeft);
            int row = top + Math.max(SPRITE, titleMetrics.getHeight() + 2 * caption.getHeight()) + Tokens.S;
            int pip = pip(), rowHeight = Math.max(pip, caption.getHeight()), pipY = row + (rowHeight - pip) / 2;
            for (int i = 0; i < PIPS; i++) {
                int px = left + i * (pip + PIP_GAP);
                if (lines.pips() < 0) {
                    g.setStroke(new BasicStroke(1f));
                    g.setColor(Tokens.color(Tokens.Role.BORDER));
                    g.drawRoundRect(px, pipY, pip - 1, pip - 1, 3, 3);
                } else {
                    g.setColor(Tokens.color(i >= lines.pips() ? Tokens.Role.CONTROL : lines.pips() >= PIPS ? Tokens.Role.GOOD : Tokens.Role.ACCENT));
                    g.fillRoundRect(px, pipY, pip, pip, 3, 3);
                }
            }
            int after = left + PIPS * (pip + PIP_GAP) + Tokens.XS;
            text(g, lines.maxed(), captionFont, caption, lines.pips() < 0 ? muted : ink, after,
                row + (rowHeight - caption.getHeight()) / 2 + caption.getAscent(), right - after);
            int bottom = row + rowHeight + Tokens.XS + caption.getAscent(), statusLeft = right;
            if (!lines.status().isEmpty()) {
                statusLeft = right - caption.stringWidth(lines.status());
                g.setFont(captionFont);
                g.setColor(Tokens.tone(card.playingNow() ? Tokens.Tone.GOOD : Tokens.Tone.BAD));
                g.drawString(lines.status(), statusLeft, bottom);
                if (card.playingNow()) {
                    int dot = Math.max(6, caption.getAscent() / 2);
                    statusLeft -= dot + Tokens.XS;
                    g.fillOval(statusLeft, bottom - caption.getAscent() / 2 - dot / 2, dot, dot);
                }
            }
            text(g, lines.seen(), captionFont, caption, muted, left, bottom, statusLeft - Tokens.S - left);
        } finally {
            g.dispose();
        }
    }

    /** PipMeter's pip size: follows the body font. */
    private static int pip() { return Math.max(7, Math.round(ContentStyle.body().getSize2D() * .7f)); }

    private static void text(Graphics2D g, String value, Font font, FontMetrics metrics, Color color, int x, int baseline, int width) {
        if (value.isEmpty() || width <= 0) return;
        g.setFont(font);
        g.setColor(color);
        g.drawString(fit(value, metrics, width), x, baseline);
    }

    /** The text, or its longest prefix plus "…" that fits the width. */
    static String fit(String value, FontMetrics metrics, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : value.substring(0, end) + "…";
    }

    /** A tinted chip right-aligned at {@code right}; returns its left edge. */
    private static int chip(Graphics2D g, String label, Tokens.Tone tone, int right, int top, FontMetrics metrics) {
        int width = metrics.stringWidth(label) + 14, left = right - width;
        g.setColor(Tokens.tint(Tokens.tone(tone)));
        g.fillRoundRect(left, top, width, metrics.getHeight() + 2, Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.setFont(metrics.getFont());
        g.setColor(Tokens.tone(tone));
        g.drawString(label, left + 7, top + 1 + metrics.getAscent());
        return left;
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
```

- [ ] **Step 6: Create `CharacterGallery`**

`src/main/java/tomato/gui/glance/character/CharacterGallery.java`:
```java
package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.Tokens;

/**
 * The roster gallery (spec §6.2, §9, §10): living characters as painted cards in a wrapping JList, dead ones in a collapsed
 * Graveyard right below them. Arrow keys move between cards; Enter, Space or a double-click opens the selected character's sheet
 * through {@code open} (its journal key). One painted renderer per list and one model event per apply, so hundreds of characters
 * stay fast. Analyst shows character IDs on the cards (spec §3.2). A storage problem is a warn banner above the cards; an
 * unreadable journal with nothing to show is an unavailable state, never "No characters yet" (spec §7). EDT only.
 */
public final class CharacterGallery extends JPanel {
    static final String GRAVEYARD = "characters-graveyard";
    private final Consumer<String> open;
    private final CharacterCardRenderer aliveRenderer = new CharacterCardRenderer(), deadRenderer = new CharacterCardRenderer();
    private final Cards alive = new Cards("character-cards", "Characters", aliveRenderer);
    private final Cards dead = new Cards("character-graveyard-cards", "Graveyard", deadRenderer);
    private final Collapsible graveyard = new Collapsible(GRAVEYARD, "Graveyard (0)", dead, false);
    private final Banner storage = new Banner("character-gallery-storage");
    /** The storage warning, the living cards and the Graveyard, each at its preferred height from the top (no stretched gap). */
    private final JPanel content = KitLayouts.stack(Tokens.L, storage, alive, graveyard);
    private final EmptyState none = new EmptyState("No characters yet", "Start capture and enter the game on a character to save it here.", null);
    private final EmptyState noMatch = new EmptyState("No characters match", "Reset filters to show every saved character.", null);
    private EmptyState unavailable;
    private String unavailableReason;
    private Consumer<String> selected = key -> { };
    private boolean selecting;

    public CharacterGallery(Consumer<String> open, DisplayModeModel mode) {
        super(new BorderLayout());
        this.open = Objects.requireNonNull(open, "open");
        setName("character-gallery");
        setOpaque(false);
        none.setName("character-gallery-empty");
        noMatch.setName("character-gallery-no-match");
        graveyard.setName("character-graveyard");
        graveyard.setVisible(false);
        storage.setTone(Tokens.Tone.WARN);
        storage.setVisible(false);
        bindOpen(alive);
        bindOpen(dead);
        bindSelection(alive, dead);
        bindSelection(dead, alive);
        mode.bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            aliveRenderer.setAnalyst(analyst);
            deadRenderer.setAnalyst(analyst);
            alive.repaint();
            dead.repaint();
        });
        showBody(none);
    }

    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen) { apply(living, fallen, false, null); }

    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen, boolean filtered) { apply(living, fallen, filtered, null); }

    /**
     * {@code filtered}: nothing is shown although characters are saved, so the empty state points at the filters. {@code problem}:
     * the journal's storage problem (null when none), a warn banner above the cards; with no card at all, the unavailable state.
     */
    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen, boolean filtered, String problem) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Apply the gallery on the EDT");
        alive.setCards(living);
        dead.setCards(fallen);
        graveyard.toggle().setText("Graveyard (" + fallen.size() + ")");
        graveyard.setVisible(!fallen.isEmpty());
        storage.setText(problem == null ? "" : problem);
        storage.setVisible(problem != null);
        boolean empty = living.isEmpty() && fallen.isEmpty();
        showBody(!empty ? content : problem != null ? unavailable(problem) : filtered ? noMatch : none);
    }

    public List<CharacterCardModel> alive() { return alive.cards.cards; }
    public List<CharacterCardModel> dead() { return dead.cards.cards; }
    /** Every shown card: the living, then the Graveyard. */
    public List<CharacterCardModel> cards() { List<CharacterCardModel> all = new ArrayList<>(alive()); all.addAll(dead()); return all; }

    /** Where the user's card selection goes (the roster list selects the same character, so views and saved state follow it). */
    public void onSelect(Consumer<String> listener) { selected = Objects.requireNonNull(listener, "listener"); }

    /** Selects {@code key}'s card, living or in the Graveyard, and scrolls it into view; null or an unknown key clears both lists. */
    public void select(String key) {
        selecting = true;
        try { alive.selectKey(key); dead.selectKey(key); } finally { selecting = false; }
    }

    /** The list keyboard focus returns to from the sheet: the Graveyard's while it is open and holds the selection, else the cards. */
    public JComponent focusTarget() { return dead.getSelectedIndex() >= 0 && graveyard.isVisible() && graveyard.expanded() ? dead : alive; }

    /** The page scrolls the gallery instead of squeezing it. */
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    private EmptyState unavailable(String reason) {
        if (!reason.equals(unavailableReason)) {
            unavailable = new EmptyState("Characters unavailable", reason, null);
            unavailable.setName("character-gallery-unavailable");
            unavailableReason = reason;
        }
        return unavailable;
    }

    private void showBody(JComponent next) {
        if (getComponentCount() == 1 && getComponent(0) == next) return;
        removeAll();
        add(next, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void bindOpen(Cards list) {
        for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE})
            list.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "open-character");
        list.getActionMap().put("open-character", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                CharacterCardModel card = list.getSelectedValue();
                if (card != null) open.accept(card.key());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int index = list.locationToIndex(e.getPoint());
                Rectangle cell = index < 0 ? null : list.getCellBounds(index, index);
                if (cell != null && cell.contains(e.getPoint())) open.accept(list.getModel().getElementAt(index).key());
            }
        });
    }

    /** A card the user selects in one list clears the other list's selection and is reported once. */
    private void bindSelection(Cards list, Cards other) {
        list.addListSelectionListener(e -> {
            CharacterCardModel card = list.getSelectedValue();
            if (e.getValueIsAdjusting() || selecting || card == null) return;
            selecting = true;
            try { other.clearSelection(); } finally { selecting = false; }
            selected.accept(card.key());
        });
    }

    /** A wrapping list of fixed-size cards whose height follows its width, so the page scrolls the cards and the Graveyard together. */
    private static final class Cards extends JList<CharacterCardModel> {
        private final CardListModel cards;
        private final CharacterCardRenderer renderer;
        private int measuredWidth = -1;

        Cards(String name, String title, CharacterCardRenderer renderer) {
            super(new CardListModel());
            this.cards = (CardListModel) getModel();
            this.renderer = renderer;
            setName(name);
            getAccessibleContext().setAccessibleName(title);
            setLayoutOrientation(HORIZONTAL_WRAP);
            setVisibleRowCount(0);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setOpaque(false);
            setCellRenderer(renderer);
            resize();
            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) {
                    if (getWidth() != measuredWidth) { measuredWidth = getWidth(); revalidate(); } // the row count depends on the width
                }
            });
        }

        void setCards(List<CharacterCardModel> next) {
            CharacterCardModel selected = getSelectedValue();
            if (!cards.set(next)) return;
            resize();
            if (selected != null) for (int i = 0; i < cards.getSize(); i++)
                if (cards.getElementAt(i).key().equals(selected.key())) { setSelectedIndex(i); break; }
        }

        void selectKey(String key) {
            for (int i = 0; key != null && i < cards.getSize(); i++)
                if (cards.getElementAt(i).key().equals(key)) { if (getSelectedIndex() != i) setSelectedIndex(i); ensureIndexIsVisible(i); return; }
            if (!isSelectionEmpty()) clearSelection();
        }

        private void resize() {
            Dimension cell = renderer.cellSize();
            if (getFixedCellWidth() != cell.width) super.setFixedCellWidth(cell.width);
            if (getFixedCellHeight() != cell.height) super.setFixedCellHeight(cell.height);
        }

        /** ContentStyle.refreshFonts sizes text lists by line height; a card keeps the height its own layout needs. */
        @Override public void setFixedCellHeight(int height) { super.setFixedCellHeight(renderer == null ? height : renderer.cellSize().height); }
        @Override public void setFont(Font font) { super.setFont(font); if (renderer != null) resize(); }
        @Override public void addNotify() { super.addNotify(); resize(); }

        /** Rows of as many cards as fit the list's width (before its first layout, its nearest sized ancestor's). */
        @Override public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int count = cards.getSize(), cellWidth = Math.max(1, getFixedCellWidth()), width = getWidth();
            for (Container parent = getParent(); width <= 0 && parent != null; parent = parent.getParent()) width = parent.getWidth();
            int columns = Math.max(1, (width - insets.left - insets.right) / cellWidth), rows = (count + columns - 1) / columns;
            return new Dimension(Math.min(count, columns) * cellWidth + insets.left + insets.right,
                rows * getFixedCellHeight() + insets.top + insets.bottom);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    }

    /** The cards shown; a change fires one removal and one insertion, never one event per card, and an equal list fires nothing. */
    private static final class CardListModel extends AbstractListModel<CharacterCardModel> {
        private List<CharacterCardModel> cards = List.of();
        @Override public int getSize() { return cards.size(); }
        @Override public CharacterCardModel getElementAt(int index) { return cards.get(index); }

        boolean set(List<CharacterCardModel> next) {
            List<CharacterCardModel> copy = List.copyOf(next);
            if (copy.equals(cards)) return false;
            int before = cards.size();
            cards = List.of();
            if (before > 0) fireIntervalRemoved(this, 0, before - 1);
            cards = copy;
            if (!copy.isEmpty()) fireIntervalAdded(this, 0, copy.size() - 1);
            return true;
        }
    }
}
```

- [ ] **Step 7: Create `RosterViews`**

`src/main/java/tomato/gui/character/RosterViews.java`:
```java
package tomato.gui.character;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.*;
import javax.swing.*;
import tomato.gui.glance.character.CharacterCardModel;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * The roster's Gallery | Table views (spec §6.2, §3.2) below the one characters FilterBar, so both share its search, facets and
 * saved view. The gallery shows exactly the roster's visible rows ({@link Source#rows}) in the Sort order; the table keeps its
 * header sorting. The Sort combo and, in Analyst, the Gallery/Table toggle sit in the filter row's own wrapping row, so at 680 px
 * or font 18 they wrap below the search instead of squeezing it. Simple offers the other view in the ⋯ menu. The view and the sort
 * persist ({@link #VIEW_KEY}, {@link #SORT_KEY}). While the gallery shows, a 1 s check re-marks "Playing now" when the character in
 * game changes without a journal change. The gallery's selection is the roster's selection. EDT only.
 */
final class RosterViews {
    static final String VIEW_KEY = "ui.characters.view", SORT_KEY = "ui.characters.sort";
    /** Most recently played first: last observed in game, then last seen in any capture, then the key, so ties stay stable. */
    private static final Comparator<CharacterRosterQuery.Row> RECENT =
        Comparator.comparingLong((CharacterRosterQuery.Row row) -> row.record.lastObservedAlive).reversed()
            .thenComparing(Comparator.comparingLong((CharacterRosterQuery.Row row) -> row.record.lastSeen).reversed())
            .thenComparing(row -> row.record.key);

    /** What the roster list gives the views. EDT. */
    interface Source {
        /** The rows the search and filters keep (CharacterJournalGUI.visibleRows()). */
        List<CharacterRosterQuery.Row> rows();
        /** Whether the journal holds any character. */
        boolean saved();
        /** The journal's storage problem (CharacterJournal.storageProblem()), or null. */
        String problem();
        /** The in-game character's journal key (with Home's map-change grace), or null. */
        String liveKey();
        /** The list's selected character, or null. */
        String selectedKey();
        /** Selects a character in the list (the gallery's selection). */
        void select(String key);
        /** Opens a character's sheet. */
        void open(String key);
    }

    /** Gallery orders; unknown values sort last and are never read as zero. */
    enum Sort {
        LAST_PLAYED("Last played", "last-played"), FAME("Fame", "fame"), CLASS("Class", "class"), MAXED("Maxed", "maxed");
        final String label, id;
        Sort(String label, String id) { this.label = label; this.id = id; }
        @Override public String toString() { return label; }
        static Sort of(String id) { for (Sort sort : values()) if (sort.id.equals(id)) return sort; return LAST_PLAYED; }
        Comparator<CharacterRosterQuery.Row> order() {
            switch (this) {
                case FAME: return Comparator.comparing((CharacterRosterQuery.Row row) -> row.record.fame,
                    Comparator.nullsLast(Comparator.<Long>reverseOrder())).thenComparing(RECENT);
                case CLASS: return Comparator.comparing((CharacterRosterQuery.Row row) ->
                        CharacterCardModel.className(row.record.classId, row.record.className), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing((CharacterRosterQuery.Row row) -> row.record.level, Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
                    .thenComparing(RECENT);
                case MAXED: return Comparator.comparing((CharacterRosterQuery.Row row) -> row.maxed,
                    Comparator.nullsLast(Comparator.<Integer>reverseOrder())).thenComparing(RECENT);
                default: return RECENT;
            }
        }
    }

    private final JComponent table;
    private final CharacterGallery gallery;
    private final Body body = new Body();
    private final JComboBox<Sort> sort = new JComboBox<>(Sort.values());
    private final JPanel sortRow = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
    private final SegmentedControl view = new SegmentedControl("character-view", "Gallery", "Table");
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
    private final OverflowMenu overflow;
    private final JMenuItem viewItem;
    private final Source source;
    private final BiConsumer<String, String> write;
    private final Timer live;
    private boolean galleryShown, ready;
    private String shownLive;

    /**
     * {@code row}: the filter row's wrapping row (FilterBar.search's content), which gets the Sort combo and the view toggle;
     * {@code bar}: the same FilterBar, whose ⋯ menu gets the other view in Simple mode.
     */
    RosterViews(JComponent table, FilterBar bar, WrapRow row, Source source, DisplayModeModel mode, Function<String, String> read,
                BiConsumer<String, String> write) {
        this.table = table;
        this.source = Objects.requireNonNull(source, "source");
        this.write = write;
        gallery = new CharacterGallery(source::open, mode);
        gallery.onSelect(source::select);
        body.add(table);
        body.add(gallery);
        sort.setName("character-sort");
        sort.getAccessibleContext().setAccessibleName("Sort characters");
        sort.setToolTipText("Order of the character cards; the table sorts by its column headers");
        sort.setSelectedItem(Sort.of(read.apply(SORT_KEY)));
        sort.addActionListener(e -> { write.accept(SORT_KEY, sort().id); refresh(); });
        JLabel label = new JLabel("Sort");
        label.setLabelFor(sort);
        ContentStyle.font(label, Type.caption());
        sortRow.setName("character-sort-row");
        sortRow.setOpaque(false);
        sortRow.add(label);
        sortRow.add(sort);
        view.getAccessibleContext().setAccessibleName("Roster view");
        view.onChange(index -> showGallery(index == 0, true));
        controls.setName("character-view-controls");
        controls.setOpaque(false);
        controls.add(sortRow);
        controls.add(view);
        row.add(controls); // wraps below the search and Reset filters when the row is narrow
        overflow = bar.overflow();
        viewItem = overflow.add("Table view", () -> showGallery(!galleryShown, true));
        viewItem.setName("character-view-item");
        mode.bind(controls, this::modeChanged);
        live = new Timer(1000, e -> { if (galleryShown && !Objects.equals(source.liveKey(), shownLive)) refresh(); });
        gallery.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (gallery.isShowing()) live.start(); else live.stop();
        });
        showGallery(!"table".equals(read.apply(VIEW_KEY)), false);
        ready = true;
    }

    JComponent body() { return body; }
    CharacterGallery gallery() { return gallery; }
    boolean galleryShown() { return galleryShown; }
    Sort sort() { return (Sort) sort.getSelectedItem(); }

    /** Re-reads the visible rows into the gallery; while the table shows, the next switch to the gallery does it instead. EDT. */
    void refresh() {
        if (!galleryShown) return;
        List<CharacterRosterQuery.Row> visible = new ArrayList<>(source.rows());
        visible.sort(sort().order());
        String key = source.liveKey();
        List<CharacterCardModel> alive = new ArrayList<>(), dead = new ArrayList<>();
        for (CharacterRosterQuery.Row row : visible) {
            CharacterCardModel card = CharacterCardModel.of(row, key);
            (card.dead() ? dead : alive).add(card);
        }
        shownLive = key;
        gallery.apply(alive, dead, visible.isEmpty() && source.saved(), source.problem());
        gallery.select(source.selectedKey());
    }

    /** Shows the gallery or the table; the other stays in the tree, hidden and unmeasured. A user's choice is remembered. */
    void showGallery(boolean show, boolean remember) {
        galleryShown = show;
        if (remember) write.accept(VIEW_KEY, show ? "gallery" : "table");
        table.setVisible(!show);
        gallery.setVisible(show);
        sortRow.setVisible(show);
        view.setSelected(show ? 0 : 1);
        viewItem.setText(show ? "Table view" : "Gallery view");
        body.revalidate();
        body.repaint();
        if (show && ready) refresh();
    }

    /** Analyst: the Gallery/Table toggle in the filter row; Simple: the other view in the ⋯ menu, hidden when nothing else is there. */
    private void modeChanged(DisplayModeModel.Mode mode) {
        boolean analyst = mode == DisplayModeModel.Mode.ANALYST;
        view.setVisible(analyst);
        viewItem.setVisible(!analyst);
        boolean items = false;
        for (Component item : overflow.menu().getComponents()) items |= item instanceof JMenuItem && item.isVisible();
        overflow.setVisible(items);
        controls.revalidate();
        controls.repaint();
    }

    /** Holds the table and the gallery; only the visible one is laid out and measured, so a long hidden gallery never sets the page height. */
    private static final class Body extends JPanel {
        Body() {
            super(null);
            setOpaque(false);
            setName("character-roster-body");
        }
        private Component shown() { for (Component child : getComponents()) if (child.isVisible()) return child; return null; }
        @Override public Dimension getPreferredSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getPreferredSize(); }
        @Override public Dimension getMinimumSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getMinimumSize(); }
        @Override public void doLayout() { for (Component child : getComponents()) if (child.isVisible()) child.setBounds(0, 0, getWidth(), getHeight()); }
    }
}
```

- [ ] **Step 8: Wire the views into `CharacterJournalGUI` and the live key into `CharacterPanelGUI`**

All edits in this step are in `src/main/java/tomato/gui/character/CharacterJournalGUI.java` unless noted.

Imports — replace `import tomato.gui.kit.FilterBar;` with that line followed by `import tomato.gui.kit.DisplayModeModel;`, `import tomato.gui.kit.Banner;`, `import tomato.gui.kit.Tokens;` and `import util.PropertiesManager;`.

Fields — before:
```java
    private Runnable clearFilters = () -> {};
```
after:
```java
    private Runnable clearFilters = () -> {};
    /** Gallery | Table below the one filter row: both show visibleRows(), and either opens the sheet (spec §6.2). */
    private final RosterViews views;
    /** The live character's journal key for the gallery's "Playing now"; CharacterPanelGUI supplies it (exact key, never a name). */
    private java.util.function.Supplier<String> liveKey = () -> null;
    /** The saved view's status, shown only while it is a failure (the actions themselves are in the ⋯ menu). */
    private final Banner stateBanner = new Banner("character-view-state");
```

The filter row — replace `        filterBar.search(new WrapRow(search, reset)).drawer(filters); clearFilters = reset::doClick; top.add(filterBar);` with the lines below. RosterViews adds the Sort combo and the view toggle to `searchRow`, so they wrap below the search and "Reset filters" at narrow widths.
```java
        WrapRow searchRow = new WrapRow(search, reset);
        filterBar.search(searchRow).drawer(filters); clearFilters = reset::doClick; top.add(filterBar);
```

The saved view's warning — replace `        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(status, BorderLayout.NORTH); footer.add(stateHost, BorderLayout.CENTER); stateHost.setVisible(false);` with:
```java
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(status, BorderLayout.NORTH);
        // Saved views live in the ⋯ menu (spec §3.2); a failure shows as a warn banner under the filter row, nothing else does.
        stateBanner.setTone(Tokens.Tone.WARN); stateBanner.setVisible(false); stateHost.add(stateBanner); stateHost.setVisible(false);
        top.add(stateHost, BorderLayout.SOUTH);
```

Facet names — before:
```java
        life.getAccessibleContext().setAccessibleName("Character life state"); season.getAccessibleContext().setAccessibleName("Character season");
```
after:
```java
        life.getAccessibleContext().setAccessibleName("Character life state"); season.getAccessibleContext().setAccessibleName("Character season");
        life.setName("character-life"); season.setName("character-season");
```

The page — replace Task 4's statement `        JScrollPane page = ContentStyle.page(top, ContentStyle.tableScroll(roster, 3), footer); pageScroll = page;` with the lines below. The constructor's first `refresh()` runs after them, so its `filter()` already feeds the gallery through the rows listener.
```java
        // Gallery | Table below the one filter row (spec §6.2): both views show visibleRows(), and either opens the sheet.
        JComponent rosterArea = ContentStyle.tableScroll(roster, 3);
        views = new RosterViews(rosterArea, filterBar, searchRow, new RosterViews.Source() {
            @Override public List<CharacterRosterQuery.Row> rows() { return visibleRows(); }
            @Override public boolean saved() { return !records.isEmpty(); }
            @Override public String problem() { return journal.storageProblem(); }
            @Override public String liveKey() { return liveKey.get(); }
            @Override public String selectedKey() { return selectedKey; }
            @Override public void select(String key) { selectKey(key); }
            @Override public void open(String key) { openSheet.accept(key); }
        }, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
        addRowsListener(views::refresh);
        JScrollPane page = ContentStyle.page(top, views.body(), footer); pageScroll = page;
```

Live key — before:
```java
    public JPanel exaltPanel() { return exalts; }
```
after:
```java
    public JPanel exaltPanel() { return exalts; }
    /** Where the gallery reads the live character's journal key (null when no character is in game). EDT. */
    public void setLiveKey(java.util.function.Supplier<String> source) { liveKey = Objects.requireNonNull(source); views.refresh(); }
    RosterViews views() { return views; }
    /** Selects {@code key}'s row, as a card selection in the gallery does, so currentKey() and the saved selection follow it. EDT. */
    void selectKey(String key) {
        for (int i = 0; key != null && i < filtered.size(); i++) if (filtered.get(i).key.equals(key)) {
            int view = roster.convertRowIndexToView(i);
            if (view >= 0 && roster.getSelectedRow() != view) roster.setRowSelectionInterval(view, view);
            return;
        }
    }
    /** Where Back from the sheet puts keyboard focus: the gallery's selected card while the gallery shows, else the table. */
    JComponent focusTarget() { return views.galleryShown() ? views.gallery().focusTarget() : roster; }
```

Back focus — replace Task 4's `    void focusRoster() { roster.requestFocusInWindow(); }` with:
```java
    /** Back from the sheet: the selected card again (in view) while the gallery shows, else the roster table (spec §10). */
    void focusRoster() {
        if (views.galleryShown()) views.gallery().select(selectedKey);
        focusTarget().requestFocusInWindow();
    }
```

Saved views in the ⋯ menu — in `bindViewState`, replace `        stateHost.add(viewState.controls()); stateHost.setVisible(true);` with the lines below, and add `viewStateChanged` beside `saveViewState`:
```java
        // Saved views live in the ⋯ menu in both modes (spec §3.2); the page shows their status only when it is a failure.
        filterBar.overflow().add("Save view state", () -> viewState.save()).setName("character-save-view");
        filterBar.overflow().add("Reset saved view state", viewState::resetSaved).setName("character-reset-view");
        viewState.onStatus(this::viewStateChanged);
        viewStateChanged();
```
```java
    /** The saved view's status shows only while it is a failure: a save failed, or the saved state could not be read. */
    private void viewStateChanged() {
        boolean problem = viewState.statusProblem();
        stateBanner.setText(problem ? viewState.statusText() : "");
        stateBanner.setVisible(problem);
        stateHost.setVisible(problem);
        stateHost.revalidate();
    }
```

`src/main/java/tomato/gui/character/CharacterPanelGUI.java` — after Task 4's `        journal = new CharacterJournalGUI(context.journal(), context.clock(), context.definitions());` add:
```java
        // The gallery's "Playing now": the exact journal key of the character in game, with Home's map-change grace (no flicker).
        journal.setLiveKey(() -> {
            tomato.backend.data.LiveCharacter.Snapshot live = tomato.gui.glance.character.SheetModelBuilder.inGame(data.liveCharacter, System.currentTimeMillis());
            return live == null ? null : live.journalKey();
        });
```

- [ ] **Step 9: Pin the Table view in the roster-table tests**

The Characters page now opens on the gallery; these tests cover the table, so each **adds beside** its existing fields (no assertion changes): `@Rule public final TableViewRule tableView = new TableViewRule();` as the first member of the class, in `CharacterJournalGuiTest`, `CharacterJournalLayoutTest`, `CharacterFilterBarTest`, `CharacterRosterStateTest`, `CharacterTableKindsTest`, `CharacterViewStateTest`, `CharacterJournalFreshnessRefreshTest`, `CharacterWaveFourEvidenceTest` (all in `src/test/java/tomato/gui/character/`) and `src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`. The rule goes on the line after `public class <Name> {`. In `FilterBarEvidenceTest` also add `import tomato.gui.character.TableViewRule;` after `import tomato.gui.character.CharacterJournalGUI;`. `CharacterJournalGuiTest` imports only `org.junit.Test`, so add `import org.junit.Rule;` after it; the others import `org.junit.*` or `org.junit.Rule`.

In `CharacterJournalGuiTest`, **replace** `JComboBox<?> filter = find(panel,JComboBox.class);` with `JComboBox<?> filter = lifeFilter(panel);`. Reason: the filter row now holds the gallery's Sort combo, which comes before the drawer's facets in the component tree, so the first `JComboBox` is no longer the life-state facet; the assertions that follow are unchanged. Add beside the other helpers, before `    private static JButton button(Container root, String text) {`:
```java
    /** The life-state facet by name: the filter row's Sort combo precedes the drawer's facets in the tree. */
    private static JComboBox<?> lifeFilter(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JComboBox && "character-life".equals(c.getName())) return (JComboBox<?>) c;
            if (c instanceof Container) { JComboBox<?> found = lifeFilter((Container) c); if (found != null) return found; }
        }
        return null;
    }
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `GRADLE test --tests "tomato.gui.glance.character.*" --tests "tomato.gui.character.*" --tests "tomato.gui.roster.*" --tests "tomato.gui.history.FilterBarEvidenceTest" --tests "tomato.gui.chat.ShellHookIntegrationTest"`
Expected: PASS — CharacterCardRendererTest 5, CharacterGalleryTest 9, RosterViewsTest 4, CharacterRosterViewTest and CharacterViewStateTest (one new each), every `tomato.gui.character` test (the table tests on the pinned Table view), the roster view-state tests unchanged, and ShellHookIntegrationTest. Copy the `Gallery refresh of 500 characters …` and `Gallery paint of one 1240x800 viewport …` lines from `build/p3a/test-results/test/TEST-tomato.gui.character.RosterViewsTest.xml` into the validation record (Task 10).

- [ ] **Step 11: Commit**

```powershell
git add src/main/java/tomato/gui/roster/RosterViewState.java src/main/java/tomato/gui/glance/character/CharacterCardModel.java src/main/java/tomato/gui/glance/character/CharacterCardRenderer.java src/main/java/tomato/gui/glance/character/CharacterGallery.java src/main/java/tomato/gui/character/RosterViews.java src/main/java/tomato/gui/character/CharacterJournalGUI.java src/main/java/tomato/gui/character/CharacterPanelGUI.java src/test/java/tomato/gui/glance/character/CharacterFixtures.java src/test/java/tomato/gui/glance/character/CharacterCardRendererTest.java src/test/java/tomato/gui/glance/character/CharacterGalleryTest.java src/test/java/tomato/gui/character/RosterViewsTest.java src/test/java/tomato/gui/character/TableViewRule.java src/test/java/tomato/gui/character/CharacterRosterViewTest.java src/test/java/tomato/gui/character/CharacterJournalGuiTest.java src/test/java/tomato/gui/character/CharacterJournalLayoutTest.java src/test/java/tomato/gui/character/CharacterFilterBarTest.java src/test/java/tomato/gui/character/CharacterRosterStateTest.java src/test/java/tomato/gui/character/CharacterTableKindsTest.java src/test/java/tomato/gui/character/CharacterViewStateTest.java src/test/java/tomato/gui/character/CharacterJournalFreshnessRefreshTest.java src/test/java/tomato/gui/character/CharacterWaveFourEvidenceTest.java src/test/java/tomato/gui/history/FilterBarEvidenceTest.java
git commit -m "Add the roster gallery with Graveyard, sort and Gallery/Table views" -m "Painted wrapping character cards show exactly the roster table's visible rows, so one search and one filter drawer serve both views. Dead characters sit in a collapsed Graveyard right below the cards; cards say when they were last played and open the sheet on Enter, Space or double-click, and Back returns focus to the card. Sort by last played, fame, class or maxed with unknown values last. The Sort and view controls wrap in the filter row; Simple offers the other view in the overflow menu, Analyst a Gallery/Table toggle; both choices persist, and saved views move into the overflow menu. Playing now uses the in-game character's exact journal key with Home's map-change grace; an unreadable journal or failed save is stated, never shown as an empty roster. Roster-table tests pin the Table view." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Home hero → sheet, S2/S5 click paths, evidence, docs, validation record, final suite and JAR smoke

**Files:**
- Modify: `src/main/java/tomato/gui/glance/home/HomeModel.java`, `HomeModelBuilder.java`, `HeroCard.java`, `HomeActions.java`; `src/main/java/tomato/gui/TomatoGUI.java`
- Modify tests: `src/test/java/tomato/gui/glance/home/HomeModels.java` (fixture), `HeroCardTest.java` (replace, add beside), `HomeModelBuilderTest.java` (add beside), `HomeRefreshTimingTest.java` (constructor call only), `src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java` (add beside)
- Create test: `src/test/java/ui/CharactersEvidenceTest.java`
- Modify docs: `README.md`, `docs/CHARACTERS.md`, `docs/superpowers/plans/2026-09-26-redesign-roadmap.md`, `docs/UX-CHECKPOINT.json`, `docs/UX-EXECUTION.md`, `docs/UX-HANDOFF.md`; create `docs/superpowers/plans/2026-09-27-p3a-validation.md`
- Evidence (not committed): `build/p3a/ui-test/screenshots/redesign-p3a-characters/` (21 PNGs)

**Interfaces:**
- Consumes: Task 8 `LiveCharacter.Snapshot.journalKey()`, `CharacterFixtures` (`KEY`, `ACCOUNT`, `journal`, `live`, `installDefinitions`); Task 4 `Destination.CHARACTER_SHEET`, `SheetFocus(key, tab)`, `CharacterSheet` (`key()`, `selectedTab()`, name `character-sheet`, back link `character-sheet-back`, tabs `character-tabs`), `CharactersRouteTarget` (plain CHARACTERS = list; unknown key = "This character is not in the journal"); Task 5 Overview needs row `character-overview-needs` (its labels include "WIS needs 3" for the fixture Wizard); Task 7 Exalts tab (one `PipMeter(5)` per stat, "N to next tier"); Task 8 Build tab (`MyInfoGUI` for the live character, an `EmptyState` for another one); P2 `HomePage`, `HomeModels`, `ShellHookIntegrationTest` helpers (`find`, `named`, `buildShell`, `remember`, `temp`), `SnapshotTestSupport.await`, `ui.VisualEvidence`.
- Produces:
  - `HomeModel.Hero` gains a last component `String key` (the sheet's journal key, null when unknown); `equals`/`hashCode` include it.
  - `HomeModelBuilder`: the live hero's key is `Snapshot.journalKey()`, the saved hero's `sheetKey(record.key)`; `static String sheetKey(String)` (null unless `"<64 hex>:<id>"`).
  - `public record HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests)`: `characters` receives the hero's key or null.
  - `HeroCard(Consumer<String> openCharacter, Runnable openBuild, DisplayModeModel mode)`: the whole card passes its hero's key; accessible name ends "Open character sheet" (keyed) or "Open Characters".
  - `TomatoGUI.openCharacterFromHome(String)`: `CHARACTER_SHEET(SheetFocus(key, "overview"))`, else plain `CHARACTERS`.
  - `HomeModels.KEY` (= `CharacterFixtures.KEY`), `HomeModels.withKey(Hero, String)`.
  - 21 evidence screenshots, with layout guards (no sideways scroll; Sort, the search and "Reset filters" whole; the Graveyard right below the cards); the P3a validation record; the P3a pull request.

- [ ] **Step 1: Update the Home fixture and write the failing tests**

`src/test/java/tomato/gui/glance/home/HomeModels.java` — fixture edits (they change no assertion):
- Before: `    public static final HomeActions NO_ACTIONS = new HomeActions(() -> {}, () -> {}, () -> {}, visit -> {}, () -> {});`
  After:
  ```java
      /** The synthetic Wizard's journal key; CharacterFixtures' roster holds it, so the hero opens a real sheet in shell tests. */
      public static final String KEY = tomato.gui.glance.character.CharacterFixtures.KEY;
      public static final HomeActions NO_ACTIONS = new HomeActions(key -> {}, () -> {}, () -> {}, visit -> {}, () -> {});
  ```
- In `hero(...)`, before: `            "Live stats from the current map; caps from the class definition; estimates use the Build page's default scenario.");` after: `            "Live stats from the current map; caps from the class definition; estimates use the Build page's default scenario.", KEY);`
- In `empty()`, before: `                "", -1, null, DisplayValue.unknown(none), DisplayValue.unknown(none), "", 0L, "No character has been captured on this computer yet."),` after: `                "", -1, null, DisplayValue.unknown(none), DisplayValue.unknown(none), "", 0L, "No character has been captured on this computer yet.", null),`
- In `unavailable()`, before: `                null, "", -1, null, DisplayValue.unknown(reason), DisplayValue.unknown(reason), "", 0L, "The character journal could not be read."),` after: `                null, "", -1, null, DisplayValue.unknown(reason), DisplayValue.unknown(reason), "", 0L, "The character journal could not be read.", null),`
- Add beside, before `    public static HomeModel populated(long now) {`:
  ```java
      /** The same hero with another journal key (null: the hero opens the Characters list). */
      public static HomeModel.Hero withKey(HomeModel.Hero h, String key) {
          return new HomeModel.Hero(h.state(), h.name(), h.classId(), h.className(), h.skin(), h.level(), h.fame(), h.maxed(), h.base(), h.caps(),
              h.totals(), h.potionsNeeded(), h.needsLine(), h.exaltTiers(), h.equipment(), h.weaponDps(), h.mpPerSecond(), h.accountLine(),
              h.lastSeenAt(), h.evidence(), key);
      }
  ```

`src/test/java/tomato/gui/glance/home/HomeRefreshTimingTest.java`, in `wisdom(...)` — the record gained a component, so the copy passes it (no assertion changes). Before: `            h.lastSeenAt(), h.evidence());` after: `            h.lastSeenAt(), h.evidence(), h.key());`

`src/test/java/tomato/gui/glance/home/HeroCardTest.java`:
- Imports: replace `import java.util.HashMap;` with `import java.util.ArrayList;`, `import java.util.Arrays;`, `import java.util.HashMap;` and `import java.util.List;`, one per line.
- **Replace** the click recorder (the constructor now takes `Consumer<String>`). Before:
  ```java
      private final int[] opened = new int[2]; // [0] Characters, [1] Build
  ```
  after:
  ```java
      private final int[] opened = new int[2]; // [0] Characters (the hero's sheet or the list), [1] Build
      /** The journal key each whole-card click passed (null: the Characters list). */
      private final List<String> keys = new ArrayList<>();
  ```
  and before: `    private HeroCard card() { return new HeroCard(() -> opened[0]++, () -> opened[1]++, mode); }` after: `    private HeroCard card() { return new HeroCard(key -> { opened[0]++; keys.add(key); }, () -> opened[1]++, mode); }`
- **Replace** two accessible-name assertions, because the keyed fixture hero now opens its sheet (intended change): `"Sharkbait, Wizard level 20, 7 of 8 maxed. Open Characters"` becomes `"Sharkbait, Wizard level 20, 7 of 8 maxed. Open character sheet"`, and `"Sharkbait, Wizard level 20, 7 of 8 maxed, not in game. Open Characters"` becomes `"Sharkbait, Wizard level 20, 7 of 8 maxed, not in game. Open character sheet"`.
- In `uncapturedSlotsAreUnknownAndEmptySlotsAreEmpty`, the copy passes the new component. Before: `                h.weaponDps(), h.mpPerSecond(), h.accountLine(), h.lastSeenAt(), h.evidence()), NOW);` after: `                h.weaponDps(), h.mpPerSecond(), h.accountLine(), h.lastSeenAt(), h.evidence(), h.key()), NOW);`
- **Add beside** (after `wholeCardOpensCharactersAndBuildOpensBuild`):
  ```java
      @Test public void theHeroOpensItsOwnSheetByJournalKeyAndTheListWithoutOne() throws Exception {
          SwingUtilities.invokeAndWait(() -> {
              HeroCard card = card();
              card.apply(HomeModels.hero(HomeModel.State.LIVE, NOW), NOW);
              card.getActionMap().get("open-card").actionPerformed(null);
              assertEquals("The hero passes its journal key", List.of(HomeModels.KEY), keys);
              card.apply(HomeModels.withKey(HomeModels.hero(HomeModel.State.STALE, NOW), null), NOW);
              assertEquals("Sharkbait, Wizard level 20, 7 of 8 maxed, not in game. Open Characters", card.getAccessibleContext().getAccessibleName());
              card.getActionMap().get("open-card").actionPerformed(null);
              assertEquals("Without a key it asks for the Characters list", Arrays.asList(HomeModels.KEY, null), keys);
              card.apply(HomeModels.empty().hero(), NOW);
              card.getActionMap().get("open-card").actionPerformed(null);
              assertNull("No character, no key", keys.get(2));
          });
      }
  ```

`src/test/java/tomato/gui/glance/home/HomeModelBuilderTest.java`, **add beside** (after `anUnchangedLiveCharacterBuildsAnEqualHeroSoHomeDoesNotRedrawIt`):
```java
    @Test public void theHeroCarriesTheJournalKeyOfTheSheetItOpens() {
        String account = CharacterJournal.accountKey("sample-account");
        LiveCharacter.Snapshot keyed = new LiveCharacter.Snapshot(account, 7, CLASS, "Tester", 900, 20, 100L, new int[]{800, 300, 90, 30, 60, 80, 50, 70},
            CAPS.clone(), new int[]{1001, 1002, 0, 1004}, null, null, null, null, null, NOW - 500);
        HomeModel.Hero inGame = hero(keyed, null, null, account(), 0);
        assertEquals("A live hero opens its own sheet", account + ":7", inGame.key());
        assertNull("An account that is not a journal key opens the Characters list", hero(live(CAPS.clone(), 100L), null, null, account(), 0).key());
        CharacterJournal.CharacterRecord saved = new CharacterJournal.CharacterRecord();
        saved.key = account + ":9"; saved.account = account; saved.characterId = 9; saved.classId = CLASS; saved.lastSeen = NOW - 60_000L;
        assertEquals("A saved hero opens that record's sheet", account + ":9", hero(null, null, saved, account(), 0).key());
        saved.key = "account-A:9";
        assertNull("A malformed saved key is never routed", hero(null, null, saved, account(), 0).key());
        assertNull("No character, no key", hero(null, null, null, null, 0).key());
        assertNotEquals("The key is part of the hero's content", inGame, HomeModels.withKey(inGame, null));
    }
```

`src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`, **add beside** (after `homeCardsOpenTheirPagesThroughTheNavigatorAndBackReturnsHome`; no existing assertion changes — that test's hero now opens the sheet for a key this shell's journal lacks, which is still page 3):
```java
    /**
     * Spec S2 and S5 from Home over a synthetic roster (CharacterFixtures). S2: Home names the stat that needs potions and how
     * many with no click, and the hero's sheet Overview repeats it after one. S5: the hero, then the Exalts tab (two clicks) show
     * every exalt tier and the distance to the next. The hero opens its own sheet at Overview; Back returns Home.
     */
    @Test public void homeHeroOpensItsSheetForS2AndS5AndBackReturnsHome() throws Exception {
        try (AutoCloseable definitions = tomato.gui.glance.character.CharacterFixtures.installDefinitions()) {
            tomato.backend.data.CharacterJournal journal = rebuildWithSyntheticRoster();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
                    home.apply(tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis()));
                    shell.select(14);
                    JLabel needs = named(home, "home-hero-needs", JLabel.class);
                    assertEquals("S2, 0 clicks: Home names the stat and the count", "Needs WIS 3 potions", needs.getText());
                    assertTrue(shown(needs));
                    named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null); // click 1
                    assertEquals("The hero opens the Characters page", 3, shell.getSelectedPage());
                    tomato.gui.glance.character.CharacterSheet sheet = find(shell, tomato.gui.glance.character.CharacterSheet.class);
                    assertEquals("…on its own character's sheet", tomato.gui.glance.home.HomeModels.KEY, sheet.key());
                    assertEquals("…at Overview", "overview", sheet.selectedTab());
                    assertTrue(shown(sheet));
                });
                // Built off the EDT; the needs row holds one label per stat that needs potions.
                await(() -> texts(named(shell, "character-overview-needs", JComponent.class)).stream().anyMatch(t -> t.contains("WIS needs 3")));
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue("S2, 1 click: the Overview names the stat and the count", shown(named(shell, "character-overview-needs", JComponent.class)));
                    tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
                    assertTrue(navigator.back());
                    assertEquals("Back returns Home", 14, shell.getSelectedPage());
                    tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
                    int clicks = 0;
                    named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null); clicks++;
                    JTabbedPane tabs = named(shell, "character-tabs", JTabbedPane.class);
                    tabs.setSelectedIndex(tabs.indexOfTab("Exalts")); clicks++;
                    assertEquals("S5 takes two clicks from Home", 2, clicks);
                    assertEquals("exalts", find(shell, tomato.gui.glance.character.CharacterSheet.class).selectedTab());
                });
                await(() -> exaltTiers() == 19); // tiers 5+4+3+2+1+0+0+4 of the fixture Wizard
                SwingUtilities.invokeAndWait(() -> {
                    Container exalts = (Container) named(shell, "character-tabs", JTabbedPane.class).getSelectedComponent();
                    assertTrue("S5: all eight stats' tiers show", all(exalts, tomato.gui.kit.PipMeter.class).size() >= 8);
                    assertTrue("S5: with the distance to the next tier", texts(exalts).stream().anyMatch(t -> t.contains("to next tier")));
                    assertTrue(tomato.gui.route.Navigator.current().back());
                    assertEquals(14, shell.getSelectedPage());
                });
            } finally {
                journal.close();
            }
        }
    }

    @Test public void aHeroWithoutAJournalKeyOpensTheCharactersList() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            tomato.gui.glance.home.HomeModel model = tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis());
            home.apply(model.withHero(tomato.gui.glance.home.HomeModels.withKey(model.hero(), null)));
            shell.select(14);
            named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null);
            assertEquals(3, shell.getSelectedPage());
            assertFalse("The list shows, not a sheet", shown(find(shell, tomato.gui.glance.character.CharacterSheet.class)));
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals(14, shell.getSelectedPage());
        });
    }

    /** Rebuilds the workspace over a TomatoData whose journal is a temporary file holding the synthetic roster. */
    private tomato.backend.data.CharacterJournal rebuildWithSyntheticRoster() throws Exception {
        tomato.backend.data.CharacterJournal journal = tomato.gui.glance.character.CharacterFixtures.journal(
            temp.newFolder("roster").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis());
        SwingUtilities.invokeAndWait(() -> {
            gui.closeWorkspace(); shell.removeNotify();
            data = new TomatoData() { @Override public tomato.backend.data.CharacterJournal characterJournal() { return journal; } };
            buildShell();
        });
        return journal;
    }
    /** Visible up to the shell: there is no window here, so isShowing is false everywhere. */
    private boolean shown(Component component) {
        for (Component c = component; c != null; c = c.getParent()) { if (!c.isVisible()) return false; if (c == shell) return true; }
        return false;
    }
    /** The filled tiers of every pip meter on the sheet's selected tab (0 while it is still loading). */
    private int exaltTiers() {
        JTabbedPane tabs = named(shell, "character-tabs", JTabbedPane.class);
        if (tabs == null || !(tabs.getSelectedComponent() instanceof Container)) return 0;
        int sum = 0;
        for (tomato.gui.kit.PipMeter meter : all((Container) tabs.getSelectedComponent(), tomato.gui.kit.PipMeter.class)) sum += meter.filled();
        return sum;
    }
    private static <T> java.util.List<T> all(Container root, Class<T> type) {
        java.util.List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) found.add(type.cast(c));
            if (c instanceof Container) found.addAll(all((Container) c, type));
        }
        return found;
    }
    private static java.util.List<String> texts(Container root) {
        java.util.List<String> found = new ArrayList<>();
        for (Component c : all(root, Component.class)) { String t = text(c); if (!t.isEmpty()) found.add(t); }
        return found;
    }
    private static String text(Component component) {
        if (component instanceof JLabel) return Objects.toString(((JLabel) component).getText(), "");
        if (component instanceof javax.swing.text.JTextComponent) return ((javax.swing.text.JTextComponent) component).getText();
        return "";
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.glance.home.*" --tests "tomato.gui.chat.ShellHookIntegrationTest"`
Expected: FAIL — compilation errors: `constructor Hero in record Hero cannot be applied to given types` (21 arguments), `cannot find symbol: method key()`, and `incompatible types: bad return type in lambda expression` for `HomeActions` (`key -> {}` where a `Runnable` is expected).

- [ ] **Step 3: `HomeModel.Hero` carries its journal key**

In `src/main/java/tomato/gui/glance/home/HomeModel.java`:
- Before: `    /** {@code maxed} 0-8 or -1; {@code exaltTiers} sum over 8 stats or -1; {@code lastSeenAt} when a STALE character was last seen (epoch ms), 0 while LIVE. */` after: `    /** {@code maxed} 0-8 or -1; {@code exaltTiers} sum over 8 stats or -1; {@code lastSeenAt} when a STALE character was last seen (epoch ms), 0 while LIVE; {@code key} the journal key of the character's sheet ("<64 hex>:<characterId>"), null when unknown. */`
- Before: `                       String accountLine, long lastSeenAt, String evidence) {` after: `                       String accountLine, long lastSeenAt, String evidence, String key) {`
- Before: `                && mpPerSecond.equals(h.mpPerSecond) && accountLine.equals(h.accountLine) && evidence.equals(h.evidence);` after: `                && mpPerSecond.equals(h.mpPerSecond) && accountLine.equals(h.accountLine) && evidence.equals(h.evidence) && Objects.equals(key, h.key);`
- Before: `            return Objects.hash(state, name, classId, level, fame, maxed, Arrays.hashCode(base), Arrays.hashCode(totals), Arrays.hashCode(equipment), lastSeenAt);` after: `            return Objects.hash(state, name, classId, level, fame, maxed, Arrays.hashCode(base), Arrays.hashCode(totals), Arrays.hashCode(equipment), lastSeenAt, key);`
- Before: `            return new Hero(state, null, -1, null, null, null, null, -1, null, null, null, null, "", -1, null, null, null, "", 0, evidence);` after: `            return new Hero(state, null, -1, null, null, null, null, -1, null, null, null, null, "", -1, null, null, null, "", 0, evidence, null);`

- [ ] **Step 4: `HomeModelBuilder` fills the key**

In `src/main/java/tomato/gui/glance/home/HomeModelBuilder.java`:
- Imports: replace `import java.util.function.*;` with that line followed by `import java.util.regex.Pattern;`.
- `fromLive`, before: `            accountLine(live.rankStars(), live.accountFame(), live.gold(), current ? null : account), seen, evidence);` after: `            accountLine(live.rankStars(), live.accountFame(), live.gold(), current ? null : account), seen, evidence, live.journalKey());`
- `fromJournal`, before: `            DisplayValue.unknown(MP_UNKNOWN), accountLine(null, null, null, account), last.lastSeen, evidence);` after: `            DisplayValue.unknown(MP_UNKNOWN), accountLine(null, null, null, account), last.lastSeen, evidence, sheetKey(last.key));`
- Add beside, before `    public static int[] potionsNeeded(int[] base, int[] caps) {` (Task 5 made it public):
  ```java
      private static final Pattern SHEET_KEY = Pattern.compile("[0-9a-f]{64}:[0-9]+");
      /** A journal key the character sheet accepts ("<64 hex>:<characterId>"), else null: the hero then opens the Characters list. */
      static String sheetKey(String key) { return key != null && SHEET_KEY.matcher(key).matches() ? key : null; }
  ```

- [ ] **Step 5: The hero and `HomeActions` pass the key**

`src/main/java/tomato/gui/glance/home/HomeActions.java` — before:
```java
/** Home's drill-downs. TomatoGUI routes each one through the Navigator, so Back returns to Home. */
public record HomeActions(Runnable characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests) {}
```
after:
```java
/**
 * Home's drill-downs. TomatoGUI routes each one through the Navigator, so Back returns to Home. {@code characters} receives the
 * hero's journal key: its sheet opens at Overview, or the Characters list when the key is null.
 */
public record HomeActions(Consumer<String> characters, Runnable build, Runnable meter, Consumer<VisitRef> run, Runnable quests) {}
```

`src/main/java/tomato/gui/glance/home/HeroCard.java`:
- Imports: replace `import java.awt.*;` with that line followed by `import java.util.function.Consumer;`.
- Class comment: replace `The whole card opens Characters; Build opens the` with `The whole card opens its sheet (the Characters list without a journal key); Build opens the`.
- Before: `    HeroCard(Runnable openCharacters, Runnable openBuild, DisplayModeModel mode) {` after: `    HeroCard(Consumer<String> openCharacter, Runnable openBuild, DisplayModeModel mode) {`
- Before: `        onOpen("Open Characters", openCharacters);` after:
  ```java
          // The shown hero's own sheet at Overview (TomatoGUI routes by its journal key); without a key, the Characters list.
          onOpen("Open Characters", () -> openCharacter.accept(shown == null ? null : shown.key()));
  ```
- Before: `            + (hero.maxed() >= 0 ? ", " + hero.maxed() + " of 8 maxed" : "") + (live ? "" : ", not in game") + ". Open Characters");` after:
  ```java
              + (hero.maxed() >= 0 ? ", " + hero.maxed() + " of 8 maxed" : "") + (live ? "" : ", not in game")
              + (hero.key() == null ? ". Open Characters" : ". Open character sheet"));
  ```

`HomePage` needs no edit: it passes `actions.characters()` to `HeroCard`, and both types changed together.

- [ ] **Step 6: `TomatoGUI` routes the hero to the sheet**

In `src/main/java/tomato/gui/TomatoGUI.java`, `homeActions()` — before:
```java
            () -> openFromHome(tomato.gui.route.Route.to(Destination.CHARACTERS)),
```
after:
```java
            TomatoGUI::openCharacterFromHome,
```
Add immediately after the `openFromHome(tomato.gui.route.Route... routes)` method:
```java
    /** The Home hero opens its own character's sheet at Overview; without a journal key (no character yet), the Characters list. */
    private static void openCharacterFromHome(String key) {
        tomato.gui.route.Route list = tomato.gui.route.Route.to(Destination.CHARACTERS);
        if (key == null) openFromHome(list);
        else openFromHome(tomato.gui.route.Route.to(Destination.CHARACTER_SHEET)
            .withPayload(new tomato.gui.glance.character.SheetFocus(key, "overview")), list);
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `GRADLE test --tests "tomato.gui.glance.home.*" --tests "tomato.gui.glance.character.*" --tests "tomato.gui.chat.ShellHookIntegrationTest"`
Expected: PASS — HeroCardTest (one new test, two replaced names), HomeModelBuilderTest (one new), every Home test, the gallery tests, and ShellHookIntegrationTest including `homeHeroOpensItsSheetForS2AndS5AndBackReturnsHome` and `aHeroWithoutAJournalKeyOpensTheCharactersList`. Record the S2 and S5 rows of the validation record from this run.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/tomato/gui/glance/home/HomeModel.java src/main/java/tomato/gui/glance/home/HomeModelBuilder.java src/main/java/tomato/gui/glance/home/HeroCard.java src/main/java/tomato/gui/glance/home/HomeActions.java src/main/java/tomato/gui/TomatoGUI.java src/test/java/tomato/gui/glance/home/HomeModels.java src/test/java/tomato/gui/glance/home/HeroCardTest.java src/test/java/tomato/gui/glance/home/HomeModelBuilderTest.java src/test/java/tomato/gui/glance/home/HomeRefreshTimingTest.java src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java
git commit -m "Open the character sheet from the Home hero" -m "The hero carries its character's exact journal key and opens that sheet at Overview through the Navigator; without a key it opens the Characters list, and Back returns Home. Shell tests check S2 (the needs line on Home with no click, on the sheet Overview after one) and S5 (hero, then Exalts: two clicks)." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 9: Write the evidence test**

This step adds no behaviour: the test is evidence plus layout guards and must pass on its first run.

`src/test/java/ui/CharactersEvidenceTest.java`:
```java
package ui;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P3a Characters evidence (spec §6.2, §11) in the real workspace: the gallery (populated, Graveyard open, no match, empty, the
 * Table view) and the sheet (Overview, Gear, Exalts, Build for the live and another character, a key not in the journal) at
 * 1240×800 and 680×520, fonts 13 and 18, Simple and Analyst: 21 screenshots. Synthetic journal, history and definitions;
 * preview mode; no capture.
 */
public class CharactersEvidenceTest {
    private static final Map<String, String> DEFAULTS = Map.of("chat.filters", "{}", "chat.showIgnoredPlayers", "false",
        "ui.characters.view", "", "ui.characters.sort", "", "ui.collapse.characters-graveyard", "", "ui.filters.characters.open", "");
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p3a-characters");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> saved = new HashMap<>(), archive = new HashMap<>();
    private DisplayModeModel.Mode savedMode;
    private String temporaryDirectory;
    private SessionStore store;
    private AutoCloseable definitions;
    private CharacterJournal journal;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;

    @Before public void open() throws Exception {
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            saved.put(entry.getKey(), PropertiesManager.getProperty(entry.getKey()));
            PropertiesManager.setProperties(entry.getKey(), entry.getValue());
        }
        for (String key : archiveKeys()) { archive.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        store = new SessionStore(temp.newFolder("history").toPath(), true, "p3a-characters");
        remember(AppHistory.class, "store", store);
        remember(Tomato.class, "preview", true);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !statics.containsKey(field)) {
                    field.setAccessible(true); statics.put(field, field.get(null));
                }
        definitions = CharacterFixtures.installDefinitions();
    }

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            evidence.closeWindow();
            if (gui != null) gui.closeWorkspace();
            DisplayModeModel.application().set(savedMode);
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        if (definitions != null) definitions.close();
        if (journal != null) journal.close();
        for (Map.Entry<String, String> entry : saved.entrySet())
            PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? DEFAULTS.get(entry.getKey()) : entry.getValue());
        for (String key : archiveKeys()) { String value = archive.get(key); PropertiesManager.setProperties(key, value == null ? "" : value); }
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (store != null) store.close();
    }

    /** 8 captures: populated at both sizes, fonts and modes, the Graveyard open, no match, and the Analyst Table view. */
    @Test public void galleryRendersPopulatedGraveyardNoMatchAndTableViews() throws Exception {
        build(CharacterFixtures.journal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        gallery("gallery", 1240, 800, 13, SIMPLE, () -> {});
        gallery("gallery", 1240, 800, 13, ANALYST, () -> {});
        gallery("gallery", 1240, 800, 18, SIMPLE, () -> {});
        gallery("gallery", 680, 520, 13, SIMPLE, () -> {});
        gallery("gallery", 680, 520, 18, ANALYST, () -> {});
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = VisualEvidence.find(shell, CharacterGallery.class, g -> true);
            errors.checkThat("Six living cards, one playing now", gallery.alive().stream().filter(c -> c.playingNow()).count(), org.hamcrest.CoreMatchers.is(1L));
            errors.checkThat(gallery.dead().size(), org.hamcrest.CoreMatchers.is(2));
        });
        gallery("gallery-graveyard", 1240, 800, 13, SIMPLE, () -> VisualEvidence.named(shell, "collapsible-characters-graveyard", AbstractButton.class).doClick());
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "collapsible-characters-graveyard", AbstractButton.class).doClick());
        gallery("gallery-no-match", 1240, 800, 13, SIMPLE, () -> VisualEvidence.named(shell, "character-search", JTextField.class).setText("no such character"));
        SwingUtilities.invokeAndWait(() -> {
            errors.checkSucceeds(() -> VisualEvidence.named(shell, "character-gallery-no-match", EmptyState.class));
            VisualEvidence.named(shell, "character-search", JTextField.class).setText("");
            DisplayModeModel.application().set(ANALYST);
            evidence.show(shell, "Characters table", 1240, 800, 13);
            VisualEvidence.named(shell, "character-view-1", AbstractButton.class).doClick();
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("p3a-table-1240-13-analyst");
            errors.checkSucceeds(() -> { assertTrue("The Table view shows the roster table", VisualEvidence.named(shell, "character-roster", JTable.class).isShowing()); return null; });
            VisualEvidence.named(shell, "character-view-0", AbstractButton.class).doClick();
        });
    }

    /** 12 captures: Overview, Gear and Exalts across sizes, fonts and modes; Build live (also 680×520 at font 18, Analyst) and for another character; an unknown key. */
    @Test public void sheetTabsRenderForTheLiveAndAnotherCharacter() throws Exception {
        build(CharacterFixtures.journal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        String key = CharacterFixtures.KEY, other = CharacterFixtures.ACCOUNT + ":102", missing = CharacterFixtures.ACCOUNT + ":999";
        sheet("overview", key, 1240, 800, 13, SIMPLE);
        sheet("overview", key, 1240, 800, 13, ANALYST);
        sheet("overview", key, 680, 520, 13, SIMPLE);
        sheet("overview", key, 1240, 800, 18, SIMPLE);
        sheet("gear", key, 1240, 800, 13, SIMPLE);
        sheet("gear", key, 680, 520, 18, SIMPLE);
        sheet("exalts", key, 1240, 800, 13, SIMPLE);
        sheet("exalts", key, 680, 520, 13, ANALYST);
        sheet("build", key, 1240, 800, 13, SIMPLE);
        sheet("build", key, 680, 520, 18, ANALYST);
        sheet("build", other, 1240, 800, 13, SIMPLE);
        sheet("overview", missing, 1240, 800, 13, SIMPLE);
    }

    /** 1 capture: no saved character at all. */
    @Test public void anEmptyJournalShowsTheGalleryEmptyState() throws Exception {
        build(new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json")), false);
        gallery("gallery-empty", 1240, 800, 13, SIMPLE, () -> {});
        SwingUtilities.invokeAndWait(() -> errors.checkSucceeds(() -> {
            assertTrue(VisualEvidence.named(shell, "character-gallery-empty", EmptyState.class).isShowing()); return null;
        }));
    }

    private void build(CharacterJournal characters, boolean live) throws Exception {
        journal = characters;
        SwingUtilities.invokeAndWait(() -> {
            data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
            if (live) data.liveCharacter.publish(CharacterFixtures.live(System.currentTimeMillis()));
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
        });
    }

    private void gallery(String state, int width, int height, int font, DisplayModeModel.Mode mode, Runnable arrange) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Characters " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.CHARACTERS)));
            arrange.run();
        });
        pause();
        if ("gallery-graveyard".equals(state)) {
            SwingUtilities.invokeAndWait(() -> {
                JComponent graveyard = VisualEvidence.named(shell, "character-graveyard", JComponent.class);
                graveyard.scrollRectToVisible(new Rectangle(0, 0, graveyard.getWidth(), graveyard.getHeight()));
            });
            pause();
        }
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture(name(state, width, font, mode));
            errors.checkSucceeds(() -> { assertGalleryWhole(width); return null; });
        });
    }

    private void sheet(String tab, String key, int width, int height, int font, DisplayModeModel.Mode mode) throws Exception {
        String variant = key.equals(CharacterFixtures.KEY) ? "" : key.endsWith(":999") ? "-unavailable" : "-other";
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Character sheet " + tab + variant, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, tab))));
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture(name("sheet-" + tab + variant, width, font, mode));
            CharacterSheet sheet = VisualEvidence.find(shell, CharacterSheet.class, s -> true);
            boolean unavailable = variant.equals("-unavailable");
            errors.checkSucceeds(() -> { assertSheetWhole(sheet, unavailable ? null : key, unavailable ? null : tab, width); return null; });
            if (variant.equals("-unavailable"))
                errors.checkSucceeds(() -> { assertTrue("An unknown key says so", showsText(sheet, "This character is not in the journal")); return null; });
            else if ("build".equals(tab)) errors.checkSucceeds(() -> {
                if (variant.isEmpty()) assertNotNull("The live character's Build", VisualEvidence.find(sheet, MyInfoGUI.class, Component::isShowing));
                else assertNotNull("Another character's Build points to the live one", VisualEvidence.find(sheet, EmptyState.class, Component::isShowing));
                return null;
            });
        });
    }

    /**
     * The gallery page never scrolls sideways; the Sort combo, the search and "Reset filters" are whole (inside their row and the
     * window, never narrower than they want); the Graveyard sits right below the living cards, not at the bottom of the page.
     */
    private void assertGalleryWhole(int width) {
        JScrollPane page = VisualEvidence.named(shell, "character-page-scroll", JScrollPane.class);
        assertEquals("No sideways scrolling at " + width + " px", page.getViewport().getWidth(), page.getViewport().getView().getWidth());
        JComponent sort = VisualEvidence.named(shell, "character-sort", JComponent.class);
        if (sort.isShowing()) assertWhole(sort, "Sort", width);
        assertWhole(VisualEvidence.named(shell, "character-search", JComponent.class), "The search", width);
        assertWhole(VisualEvidence.find(shell, JButton.class, button -> "Reset filters".equals(button.getText())), "Reset filters", width);
        CharacterGallery gallery = VisualEvidence.find(shell, CharacterGallery.class, g -> true);
        if (gallery.isShowing() && !gallery.alive().isEmpty() && !gallery.dead().isEmpty()) { // the cards and the Graveyard are both on the page
            JComponent cards = VisualEvidence.named(gallery, "character-cards", JComponent.class), graveyard = VisualEvidence.named(gallery, "character-graveyard", JComponent.class);
            Rectangle above = SwingUtilities.convertRectangle(cards.getParent(), cards.getBounds(), shell);
            Rectangle below = SwingUtilities.convertRectangle(graveyard.getParent(), graveyard.getBounds(), shell);
            assertTrue("The Graveyard follows the cards at " + width + " px: " + above + " then " + below, below.y - (above.y + above.height) <= 24);
        }
    }

    /** Sideways only, since the page may be scrolled: inside its row and the window, and at least its preferred width (not squeezed). */
    private void assertWhole(JComponent part, String what, int width) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), shell);
        assertTrue(what + " is whole at " + width + " px: " + part.getBounds() + " in a row " + part.getParent().getWidth() + " wide, preferred "
                + part.getPreferredSize().width + ", in the window " + placed,
            part.getX() >= 0 && part.getX() + part.getWidth() <= part.getParent().getWidth() && placed.x >= 0
                && placed.x + placed.width <= shell.getWidth() && part.getWidth() >= part.getPreferredSize().width);
    }

    /** {@code key} and {@code tab} null: an unknown key, whose sheet shows its unavailable state instead of tabs. */
    private void assertSheetWhole(CharacterSheet sheet, String key, String tab, int width) {
        assertTrue("The sheet shows", sheet.isShowing());
        if (key != null) assertEquals(key, sheet.key());
        if (tab != null) assertEquals(tab, sheet.selectedTab());
        JComponent back = VisualEvidence.named(sheet, "character-sheet-back", JComponent.class);
        Rectangle placed = SwingUtilities.convertRectangle(back.getParent(), back.getBounds(), shell);
        assertTrue("‹ Characters fits at " + width + " px: " + placed, back.isShowing() && placed.x >= 0 && placed.x + placed.width <= shell.getWidth());
    }

    private static boolean showsText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c.isShowing() && (c instanceof JLabel && String.valueOf(((JLabel) c).getText()).contains(text)
                || c instanceof javax.swing.text.JTextComponent && ((javax.swing.text.JTextComponent) c).getText().contains(text))) return true;
            if (c instanceof Container && showsText((Container) c, text)) return true;
        }
        return false;
    }

    private static String name(String state, int width, int font, DisplayModeModel.Mode mode) {
        return "p3a-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT);
    }

    /** The sheet builds its model off the EDT and Collapsible motion takes at most 100 ms: settle, wait, settle. */
    private void pause() throws Exception { evidence.settle(); Thread.sleep(400); evidence.settle(); }

    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); statics.put(field, field.get(null)); field.set(null, next);
    }

    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties) field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive."));
        return keys;
    }
}
```

- [ ] **Step 10: Run the evidence test and review the screenshots**

Run: `GRADLE test --tests "ui.CharactersEvidenceTest"`
Expected: PASS; 21 PNGs in `build/p3a/ui-test/screenshots/redesign-p3a-characters/`. Open every image and check:
- Gallery: six living cards in a grid (four or five per row at 1240, two at 680, fewer at font 18), each with a sprite (or the kit's dashed placeholder, never a blank), class, "Level N · Fame N", the 8-pip meter with "7/8" (the Knight: outlined pips and "—", never 0/8), a Seasonal chip on the Warrior and Rogue, "Played N h ago", and "Playing now" in mint on the Wizard only. "Graveyard (2)" is collapsed right below the last row of cards, not at the bottom of the window; Analyst adds "#101"-style IDs and the Gallery/Table toggle; the ⋯ menu shows in both modes (it holds the saved-view actions; Simple also offers the other view there). No "Save view state" button or status text on the page. Nothing is cut at the right edge; at 680 × 520 and font 18 the Sort control wraps below the search and "Reset filters", and both stay whole.
- Graveyard open: two dimmed cards marked "Dead". No match: "No characters match" with the reset hint. Empty: "No characters yet". Table view: the roster table below the same filter row.
- Sheet: "‹ Characters" and the header on every capture; in Simple no snapshot evidence or tab hint under the header (Analyst shows both); Overview names "WIS needs 3" with no vault count; Gear shows three items, an empty ring, empty inventory and unknown backpack slots, each distinct; Exalts shows eight tier meters with "N to next tier"; Build shows the Build page for the Wizard (also at 680 × 520, font 18, where it scrolls instead of clipping) and the "Build shows the character you're playing" pointer for the Warrior; an unknown key reads "This character is not in the journal"; no capture shows "Loading…".
- Unknowns read "—", estimates "≈"; no text is clipped.

- [ ] **Step 11: Commit**

```powershell
git add src/test/java/ui/CharactersEvidenceTest.java
git commit -m "Add P3a Characters evidence screenshots" -m "Gallery (populated, Graveyard open, no match, empty, Table view) and sheet (Overview, Gear, Exalts, Build live and for another character, unknown key) in the real workspace at 1240x800 and 680x520, fonts 13 and 18, Simple and Analyst: 21 captures, with guards for sideways fit, the filter row and the Graveyard's place." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 12: Update the docs and create the validation record**

`README.md`:
- Replace `| Saved roster, death marks, equipment, stat maxing and exalts | [Characters](docs/CHARACTERS.md) |` with `| Character gallery and sheet: gear, stat maxing, exalts, Build, goals, notes and death marks | [Characters](docs/CHARACTERS.md) |`.
- Replace the sentence `The former My Info page is now **Build**, opened from the Home hero, Alt+7 or Settings search.` with: `**Characters** opens on a gallery of character cards, with dead characters in a collapsed Graveyard and a sort by last played, fame, class or maxed; the roster table is the Table view, and one search and filter drawer serve both. A card, a table row or the Home hero opens the full-page character sheet (Overview, Gear, Exalts, Build, Goals, Notes and Death annotation), and Back returns to where you were. **Build** (weapon damage and recovery, formerly My Info) is the sheet's Build tab: Alt+7, Settings search and the hero's Build action open it for the character you are playing.`

`docs/CHARACTERS.md`:
- Replace the paragraph that begins `The roster supports literal-text search` together with the five bullets after it (through `- Field source/receipt evidence in stats and equipment, plus a **Snapshot evidence** tab for character metadata.`) with:
  ```markdown
  The roster opens as a **gallery** of character cards: skin sprite, class, level and fame, an 8-pip maxed meter (**—** when unknown, never 0/8), a **Seasonal** chip, when the character last played, and a **Playing now** marker for the character in game. Characters marked dead are grouped in a collapsed **Graveyard** below. **Sort** orders the cards by last played, fame, class or maxed; unknown values sort last. The existing roster table is the **Table view**: in Simple mode choose **Table view** or **Gallery view** in the ⋯ menu; in Analyst mode use the Gallery/Table switch in the filter row, where cards also show character IDs. Both views show exactly the characters the one search and filter drawer select, and the view and sort are remembered. **Save view state** and **Reset saved view state** are in the ⋯ menu; the page warns only when saving fails.

  The roster supports literal-text search (class, account, character ID, item name/ID, or notes), task filters and sortable table columns. Equipment search accepts decimal and hexadecimal IDs as well as names. Open a character (Enter or double-click on a card or table row, or click the Home hero) for its full-page **character sheet**; **‹ Characters** or Back returns to the list where you were. The sheet's tabs can be reordered and hidden:

  - **Overview**: base stats against class caps (with the live boost while you play), what each stat still needs (with vault potions and how long ago they were counted, when known), equipped gear and a class exalt summary.
  - **Gear**: equipped items, inventory and backpack; unknown and empty slots stay distinct.
  - **Exalts**: this class's eight stats with tier, completions, the distance to the next tier and where to earn it.
  - **Build**: weapon damage and recovery estimates for the character you are playing, or after capture stops the last one you played (formerly My Info). Other characters' sheets point to it.
  - **Goals** and **Notes**, **Death annotation** for a character marked dead, and in Analyst mode **Snapshot evidence** (field source and receipt times, also shown under the header).
  ```
- Replace `At compact sizes or enlarged fonts, scroll the page to move between the roster and details. Their minimum sizes reserve usable data rows; detail tabs wrap instead of hiding part of the selected label.` with `At compact sizes or enlarged fonts the gallery wraps to fewer cards per row and the page scrolls; the table view keeps usable data rows, and sheet tabs wrap instead of hiding part of a label.`
- Replace `Draft notes survive background roster refreshes and are saved against the selected character identity when selection changes.` with `Draft notes survive background refreshes and are saved to their character when you leave the sheet or another character's sheet opens.`

`docs/superpowers/plans/2026-09-26-redesign-roadmap.md`:
- Status row: replace `` | P3a Characters: gallery, sheet, journal v5 | [2026-09-27-p3a-characters.md](2026-09-27-p3a-characters.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Planned | `` with `` | P3a Characters: gallery, sheet, journal v5 | [2026-09-27-p3a-characters.md](2026-09-27-p3a-characters.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Implemented; PR merge pending | ``.
- Replace `P3a is planned; later phases have not started.` with `P3a is implemented on the branch; its PR merge is pending. Later phases have not started.`
- Replace `[P1c validation](2026-09-26-p1c-validation.md), [P2 validation](2026-09-26-p2-validation.md).` with `[P1c validation](2026-09-26-p1c-validation.md), [P2 validation](2026-09-26-p2-validation.md), [P3a validation](2026-09-27-p3a-validation.md).`
- In "P3 Characters", after the line starting `7. Deferred from P2:` add:
  ```markdown
  8. Status (2026-09-27): P3a implements items 1, 2 (without the Pet and Fame tabs), 5 (as journal version 5) and 7 (except the pet rarity chip). P3b covers items 3 and 4, the Pet and Fame tabs, the pet rarity chip, the Overview pet card and Goals restyled as cards with progress.
  ```

`docs/UX-EXECUTION.md` — replace lines 3–10 (from `Current redesign (2026-09-26): P0 merged…` through `final-head review and GitHub state before P3.`) with:
```markdown
Current redesign (2026-09-27): P0 merged in PR #17 (`9b6844a`); P1a design kit
merged in PR #18 (`7b3e0c5`), including fix `ee8d085`. P1b merged in PR #19 as `0abafe4`, including fix `a011bfd`.
P1c merged in PR #20 as `e973f10`, including fix `4ca1657`. P2 merged in PR #21 as `94db6f6`; main was verified
before P3a began. Continue from the [presentation roadmap](superpowers/plans/2026-09-26-redesign-roadmap.md) and
[P3a validation](superpowers/plans/2026-09-27-p3a-validation.md). Every redesign
phase uses `claude/realmshark-ui-ux-redesign-cb0914`. The wave history below is
completed background. P3a (Characters: gallery, sheet, journal v5) is implemented, pending PR merge. Reconcile its
final-head review and GitHub state before P3b.
```

`docs/UX-HANDOFF.md` — replace lines 3–12 (from `Current redesign handoff (2026-09-26)…` through `The older resumption instructions below are historical.`) with:
```markdown
Current redesign handoff (2026-09-27): the four waves are complete. P0 merged
in PR #17 (`9b6844a`); P1a design kit merged in PR #18 (`7b3e0c5`), including fix `ee8d085`.
Use the [presentation roadmap](superpowers/plans/2026-09-26-redesign-roadmap.md)
and [P3a validation](superpowers/plans/2026-09-27-p3a-validation.md) for current
scope and evidence. Every phase uses `claude/realmshark-ui-ux-redesign-cb0914`.
P1b merged in PR #19 as `0abafe4`, including `a011bfd`. P1c merged in PR #20 as
`e973f10`, including `4ca1657`. P2 merged in PR #21 as `94db6f6`. P3a (Characters) is
implemented, pending PR merge. Focused checks, S2/S5, the final full suite and JAR smoke
are recorded in the P3a validation; see the P3a PR for independent final-head review.
Verify merged main before starting P3b. The older resumption instructions below are historical.
```

Create `docs/superpowers/plans/2026-09-27-p3a-validation.md`:
```markdown
# P3a validation and coverage

Base: P2 PR #21 merged as `94db6f6` and verified before P3a began.
P3a is one PR on `claude/realmshark-ui-ux-redesign-cb0914`; P3b follows as its own plan and PR.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass:
keep it below as diagnostic history and record the rerun that passed.

## Coverage

- Task 1: P2 follow-ups (unreadable saved sessions, the per-session archive cache, recording and publish de-duplication,
  the focus fallback) and the full-suite baseline.
- Task 2: journal version 5 (pet, dungeon completions, experience, backpack, per-class exalt times, vault potions);
  v1–v4 load unchanged; a malformed new field is normalized to null.
- Task 3: kit promotions (`Banner`, `KitLayouts`, `ItemTiers`, `KitText`); Home delegates to them.
- Tasks 4–8: the character sheet on page 3 (`CHARACTER_SHEET`, Back to the list), header, Overview, Gear, Exalts and
  Build tabs; the side pane retired; Build moved from page 6.
- Task 9: the roster gallery (painted wrapping cards, Graveyard, sort, Gallery/Table per mode) over the one roster filter.
- Task 10: the Home hero opens its sheet; S2 and S5 click paths; evidence; docs.

## Local validation

JDK 17 and Gradle 7.6.4, offline, isolated `build/p3a` and `build/p3a-cache`, synthetic fixtures only.

| Check | Command | Record |
|---|---|---|
| Baseline full suite before any code change: merged main `94db6f6` plus the plan (Task 1) | `GRADLE test shadowJar` | tests / failures / errors / skipped: _to record_; pre-existing failures by name: _to record_ (`build/p3a/evidence/baseline.txt`) |
| P2 follow-ups (Task 1) | Task 1 focused tests | _to record_ |
| Journal v5 (Task 2) | Task 2 focused tests | _to record_ |
| Kit promotions (Task 3) | Task 3 focused tests | _to record_ |
| Sheet frame, route and migrated character tests (Task 4) | Task 4 focused tests | _to record_ |
| Header, Overview, Gear, Exalts, Build (Tasks 5–8) | Task 5–8 focused tests | _to record_ |
| Gallery (Task 9) | `tomato.gui.glance.character.*`, `tomato.gui.character.*`, `LiveCharacterTest`, `FilterBarEvidenceTest`, `ShellHookIntegrationTest` | _to record_ |
| 500 characters (Task 9) | `RosterViewsTest` output lines | refresh (sort, map, apply) median / p95 / max µs, asserted every sample ≤ 50 000 µs: _to record_; one-viewport paint µs and visible cards (logged): _to record_ |
| Home hero → sheet (Task 10) | `tomato.gui.glance.home.*` | _to record_ |
| S2 (≤ 1 click) | `ShellHookIntegrationTest.homeHeroOpensItsSheetForS2AndS5AndBackReturnsHome` | Home "Needs WIS 3 potions" with 0 clicks; the sheet Overview "WIS needs 3" after 1 click: _to record_ |
| S5 (≤ 2 clicks) | same test | hero, then the Exalts tab (2 clicks); eight tier meters (19 tiers filled) and "to next tier": _to record_ |
| Evidence (Task 10) | `GRADLE test --tests "ui.CharactersEvidenceTest"` | 21 screenshots in `build/p3a/ui-test/screenshots/redesign-p3a-characters/`; reviewer and findings: _to record_ |
| Final full suite and JAR (Task 10) | `GRADLE test shadowJar` | tests / failures / errors / skipped: _to record_; new failures versus baseline: none required |
| JAR smoke (Task 10) | isolated `java -jar … --help` | exit code: _to record_ |

## Deferred scope

- P3b: the account Exalts grid (a tile per observed class, loot boost, fully exalted classes), the Pets gallery, the sheet's
  Pet and Fame tabs, and Home's pet rarity chip (journal v5 already saves the equipped pet, and an explicit "no pet").
- P3b: the sheet Overview's pet card (spec §6.2), which needs P3b's pet names and rarity, and Goals restyled as cards with
  progress; P3a moves the Goals tab unchanged.
- The Characters page keeps its Roster / Exalts / Pets tabs (`CustomizableTabs("characters")`) rather than the spec's
  segmented control; P3b rebuilds Exalts and Pets inside them.
- The gallery's sort and view are preferences (`ui.characters.sort`, `ui.characters.view`), not part of the roster's saved views.
- Stat bars keep the "+N" live boost text; no painted overlay (user decision 2026-09-27).
- Page 6 stays a "Build moved" pointer until P6 deletes it.
```

- [ ] **Step 13: Commit the docs**

```powershell
git add README.md docs/CHARACTERS.md docs/superpowers/plans/2026-09-26-redesign-roadmap.md docs/UX-EXECUTION.md docs/UX-HANDOFF.md docs/superpowers/plans/2026-09-27-p3a-validation.md
git commit -m "Document P3a Characters and add its validation record" -m "README, the Characters guide and the roadmap describe the gallery, the character sheet and Build on the sheet; the execution and handoff notes point at the P3a validation record, whose results are filled after the final suite." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 14: Final full suite, JAR build and smoke**

Record the source head first: `git rev-parse --short HEAD`.
Run (one Gradle invocation; UI tests open real windows, so leave the desktop alone): `GRADLE test shadowJar`
Expected: BUILD SUCCESSFUL. A failure not in the Task 1 baseline must be fixed and rerun before continuing; a baseline failure that still fails is recorded by name as pre-existing.
Totals:
```powershell
$suites = Get-ChildItem build/p3a/test-results/test -Filter *.xml | ForEach-Object { [xml](Get-Content $_.FullName) }
"tests=$(($suites.testsuite.tests | Measure-Object -Sum).Sum) failures=$(($suites.testsuite.failures | Measure-Object -Sum).Sum) errors=$(($suites.testsuite.errors | Measure-Object -Sum).Sum) skipped=$(($suites.testsuite.skipped | Measure-Object -Sum).Sum)"
```
JAR smoke from an isolated folder:
```powershell
$jar = Get-ChildItem build/p3a/libs -Filter 'RealmShark-*.jar' | Select-Object -First 1
New-Item -ItemType Directory -Force build/p3a/smoke | Out-Null
Push-Location build/p3a/smoke; & "$env:JAVA_HOME\bin\java.exe" -jar $jar.FullName --help; $code = $LASTEXITCODE; Pop-Location; "exit=$code"
```
Expected: the help text and `exit=0`.

- [ ] **Step 15: Fill the validation record and checkpoint, then commit**

Replace every `_to record_` in `docs/superpowers/plans/2026-09-27-p3a-validation.md` with the recorded values. In `docs/UX-CHECKPOINT.json` replace `"recordedDate": "2026-09-26",` with `"recordedDate": "2026-09-27",` and replace the `redesign` block with the one below, writing the hash printed in Step 14 as `sourceHead` and a one-line summary of the record as `localChecks` (for example "N focused tests pass; full suite A tests, 0 new failures; 21 captures reviewed; S2 0/1 clicks, S5 2 clicks; 500-character refresh max X us; shadowJar and isolated help pass"):
```json
  "redesign": {
    "phase": "P3a",
    "status": "P2 merged in PR #21 as 94db6f6; P3a Characters implemented, pending PR merge",
    "p1aMerge": "7b3e0c5",
    "p1aFix": "ee8d085",
    "branch": "claude/realmshark-ui-ux-redesign-cb0914",
    "base": "94db6f6",
    "sourceHead": "<short hash from Step 14>",
    "localChecks": "<one-line summary from the validation record>",
    "roadmap": "docs/superpowers/plans/2026-09-26-redesign-roadmap.md",
    "validation": "docs/superpowers/plans/2026-09-27-p3a-validation.md",
    "nextAction": "Reconcile P3a final-head review and PR; verify main after merge before starting P3b",
    "historicalFieldsBelow": "All four prior waves are complete; their pending gate text below is superseded",
    "p1bMerge": "0abafe4",
    "p1bFix": "a011bfd",
    "p1bPullRequest": 19,
    "p1cMerge": "e973f10",
    "p1cFix": "4ca1657",
    "p1cPullRequest": 20,
    "p2Merge": "94db6f6",
    "p2PullRequest": 21
  },
```
Check the JSON parses: `Get-Content docs/UX-CHECKPOINT.json -Raw | ConvertFrom-Json | Select-Object -ExpandProperty redesign`.
```powershell
git add docs/superpowers/plans/2026-09-27-p3a-validation.md docs/UX-CHECKPOINT.json
git commit -m "Record P3a validation results" -m "The validation record holds the focused, S2/S5, timing, evidence, final-suite and JAR smoke results, and the checkpoint names the P3a source head." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 16: Push and open the P3a pull request**

```powershell
git push -u origin claude/realmshark-ui-ux-redesign-cb0914
gh pr create --title "Redesign P3a: Characters gallery, sheet and journal v5" --body @'
Implements P3a of docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md (§6.2, §7, §8.3, §9, §10, S2, S5) as one PR:

- Characters opens on a gallery of painted character cards (sprite, class, level, fame, 8-pip maxed meter with "—" when unknown, Seasonal chip, last played, Playing now) with dead characters in a collapsed Graveyard and a sort by last played, fame, class or maxed. The roster table is the Table view (Simple: ⋯ menu; Analyst: toggle); one search and filter drawer serve both, and saved views live in the ⋯ menu.
- Cards, table rows and the Home hero open a full-page character sheet on page 3 (CHARACTER_SHEET): Overview, Gear, Exalts, Build, Goals, Notes, Snapshot evidence (Analyst) and Death annotation (characters marked dead); Back returns to where you were. The sheet is built off the EDT, says Loading until the opened character's result applies and reports failures. The side detail pane is retired.
- Build (formerly My Info) is the sheet's Build tab; Alt+7, search and Home's Build action open it; page 6 points there.
- Journal version 5 saves pet (including an explicit "no pet"), dungeon completions, experience, backpack, per-class exalt times and vault potions; v1-v4 load unchanged, the first v5 save keeps journal.v4.bak, and P2 builds open v5 read-only.
- P2 follow-ups: unreadable saved sessions no longer fail Home's totals, per-session archive caching, publish de-duplication, focus fallback.
- S2: Home names the stat that needs potions with no click, the sheet Overview after one. S5: hero, then Exalts: two clicks.

Validation: see docs/superpowers/plans/2026-09-27-p3a-validation.md (baseline and final full suite, focused tests, S2/S5, 500-character gallery timing, 21 evidence screenshots, shadowJar and isolated JAR smoke). P3b (Exalts grid, Pets, Pet and Fame tabs, the Overview pet card, Goals as cards) follows separately.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
'@
```
Request an independent review of the final PR head before merging; the coordinator owns the merge and the machine-local checkpoint.

---

## Cross-task notes

These facts span tasks. Each task's steps already apply them; they are collected here for reviewers and for anyone resuming mid-phase.

- **Journal keys.**
  - A key is `accountKey + ":" + characterId`; `CharacterJournal.accountKey(...)` is a 64-hex SHA-256. `SheetFocus` accepts only `[0-9a-f]{64}:[0-9]+` and a tab id `[a-z0-9][a-z0-9-]*`.
  - Three rules produce a key. `LiveCharacter.Snapshot.journalKey()` (Task 8) is null unless the account is a hashed key. `BuildRoute.key(TomatoData)` (Task 8) takes the live character when the journal has it, else the most recent one. `HomeModelBuilder.sheetKey(record.key)` (Task 10) keeps only a well-formed saved key.
  - The hero's key names the character the hero shows; Build entry points use `BuildRoute`'s rule. A key the journal lacks opens the sheet's unavailable state ("This character is not in the journal").
  - The Build tab shows `MyInfoGUI` only on the sheet whose key is `BuildTab.shownKey(live)`: the current snapshot's key, else (capture stopped, map change) the last known one's. Other sheets point to it.
- **Routes.**
  - `Destination.CHARACTER_SHEET` maps to page 3. `CharactersRouteTarget.of(view)` returns one target per destination (a `RouteTarget` has one destination), sharing one Back origin. A plain `CHARACTERS` route shows the list.
  - `MY_INFO` redirects once, through the default `RouteTarget.redirect`, to `CHARACTER_SHEET(SheetFocus(key, "build"))`; with no character it stays on page 6 (`BuildMovedPanel`). `pageOf(MY_INFO)` stays 6.
  - A route's tab, "Open goals" and every Build entry are explicit: the tab is shown, then selected. The remembered tab (`sheetTab`, else the legacy index `tab`) is only selected.
- **Sheet API.**
  - `SheetContext(data, journal, definitions, mode, clock, plans)`, all non-null; the four-argument form uses the system clock and `PlanningStore.shared()`. Tests pass `new TomatoData()` and a fixture clock.
  - `CustomizableTabs("character")`: component `character-tabs`, preference `ui.tabs.character`. Default order overview, gear, exalts, build (Task 8), goals, notes, evidence (Analyst only), death (conditional: only while the character is marked dead; `addWhen`/`refreshConditions`, Task 4). A saved order without `build` gets it appended; a condition never rewrites the saved order.
  - `CharacterSheet.ready()`: the sheet shows the read of its current key. Mark dead, Restore alive and Save notes act only then; tests wait for it (`RosterFixtures.enter` does). `saveDraft()` runs before another character opens, whenever the sheet hides, from `CharacterRosterView.showList`, on the page's tab switch and on `TomatoGUI.closeWorkspace`.
  - Package-private `setTab` replaces a slot's content (overview, gear, exalts; build from Task 8); `setIdentity` (Task 5) takes `SheetHeader`. `SheetPresenter` owns those slots from Task 5 on.
  - `CharacterPanelGUI` exposes `roster()`, `sheet()`, `routeTargets()` (built once), `openGoals()` and (Task 8) `hostBuild(JComponent)`. `TomatoGUI` constructs the only `MyInfoGUI` and hands it over.
- **Component names.**
  - List: `character-roster` (Enter action `open-character`), `character-search`, `character-facet-N`, `character-life`, `character-season` (Task 9), `character-page-scroll`.
  - Roster tab and sheet: `character-roster-view` (cards `list`/`sheet`), `character-sheet`, `character-sheet-scroll`, `character-sheet-back`, `character-sheet-death` (Mark dead), `character-sheet-restore` (Restore alive), `character-sheet-status` (Loading… / a failed build, Task 5), `character-sheet-storage`, `character-snapshot-evidence` and `character-sheet-hint` (Analyst), `character-notes`, `character-notes-save`, `character-sheet-unavailable`, `character-sheet-unavailable-back`.
  - Header (Task 5): `character-sheet-{identity,sprite,name,meta,maxed,playing,seasonal,dead,seen}`.
  - Tabs: `character-overview-*` (the needs row `character-overview-needs` holds `character-overview-need-N` labels), `character-stat-table`/`character-stat-rows` (Analyst), `character-gear-*`, `character-exalt(s)-*`, `character-build-*`; page 6 `build-moved`, `build-moved-open`.
  - Gallery (Task 9): `character-gallery`, `character-cards`, `character-graveyard`, `character-graveyard-cards`, `character-gallery-storage`, `character-gallery-empty`, `character-gallery-no-match`, `character-gallery-unavailable`, `character-sort`, `character-view-controls`, `character-view`, `character-view-item`, `character-roster-body`; the ⋯ items `character-save-view`, `character-reset-view` and the banner `character-view-state`.
- **Threading.**
  - `SheetPresenter` reads the journal (the record, the character list and the accounts, reused while the journal revision is unchanged) and builds `SheetModel` on the daemon thread `character-sheet`. It rebuilds on `open(key)` and, while the sheet shows, when a token moves (checked once a second): the key, the journal and live revisions, and the definitions and planning objects. The EDT applies only the newest result for the key still shown, and each tab skips an equal section; a failed build shows a warn banner and is retried on the next refresh.
  - From Task 5 `CharacterSheet` copies nothing on the EDT: `loaded(...)` shows the presenter's read (notes, Goals, the death panel, the evidence), and its timer runs only while the sheet shows. (Task 4's interim sheet reads on the EDT.)
  - `CharacterJournalGUI.addRowsListener` listeners run at the end of every `filter()`, including the constructor's first `refresh()`, and after a user sort; `RosterViews` is built before that first refresh.
- **Units and honesty.**
  - Per-stat lists use canonical order (life, mana, atk, def, spd, dex, vit, wis) with −1 for unknown; exalt arrays use RealmCharacter order, and `CharacterJournal.EXALT_ORDER` converts. Gear slots are an item id > 0, 0 for empty, −1 for not captured.
  - An unknown maxed count is hidden (the sheet's chip) or "—" (cards and the Overview), never 0/8. Potions and maxed use Home's arithmetic (`HomeModelBuilder.potionsNeeded/maxed`, public since Task 5) with caps from `RosterDefinitions`.
  - Live values apply only when the snapshot's account and character ID equal the record's. A snapshot cleared by a map change still counts during Home's grace (`SheetModelBuilder.inGame`, over `HomeModelBuilder.stillCurrent`), on the sheet and the gallery cards, so "Playing now" does not flicker. Enchant dots come only from the live character.
  - Times (spec §5.7): cards and the header say "Played <ago>" from `lastObservedAlive` ("Seen <ago>" from `lastSeen` only when never played), as the Last played sort orders them; a vault count shows its age and is dimmed after 24 h; the Exalts tab says when this class's counts last "Changed".
  - A pet with `absent = TRUE` is a known "No pet"; a missing `pet` is unknown. P3b's pet card and Pets gallery read it that way.
  - Journal v5 fields are optional and null-tolerant; `copy(...)` copies each of them. Vault potions cover the regular vault only, so a seasonal character's sheet shows no vault count.
  - The character list's completions are decoded from the entry's own PCStats string, because capture overlays the decoded stats of the character in game with CREATE's; CREATE's counts reach the journal separately, with their own time.
- **Fixtures.**
  - `SheetFixtures` (Task 5, `tomato.gui.glance.character`): one seasonal Wizard #7 of `accountKey("sheet-fixture")`, model inputs (`record`, `account`, `bonus`, `live`, `model`, `defs`), `seed` (a journal Wizard named "Sample") and `inject` (a test journal on a `TomatoData`). Tasks 5–8 and the shell tests' empty journals use it.
  - `CharacterFixtures` (Task 9, same package): an eight-character roster of `accountKey("synthetic-account")` (two marked dead) whose Wizard `KEY` (`…:101`) is 7/8 maxed with "WIS needs 3" and 19 exalt tiers, its live snapshot, and `installDefinitions()`, which pins `RosterDefinitions.current()` and the fixture classes' names and caps until closed. `HomeModels.KEY` equals `CharacterFixtures.KEY`, so the Home hero opens that sheet in S2/S5 and the evidence.
  - `RosterFixtures` (Task 4, `tomato.gui.character`) builds the Roster tab bound to `Navigator.NONE`; `TableViewRule` (Task 9) pins the Table view for the roster-table tests.
- **Test hygiene.**
  - Tests restore `ui.tabs.character`, `ui.tabs.characters`, `ux.archive.characters-live-roster`, `ui.filters.characters.open`, `ui.characters.view`, `ui.characters.sort`, `ui.collapse.characters-graveyard`, `ui.mode` and `DisplayModeModel.application()`; tests that build the real workspace also restore every `ux.archive.*` key and `TomatoGUI`'s and `ChatGUI`'s static fields.
  - Shell tests inject a temporary journal (`SheetFixtures.inject` or a `TomatoData` override); otherwise the Build route follows `Characters/journal.json` in the test working directory.
  - Sheet values arrive off the EDT: await one (`SnapshotTestSupport.await`, which also works on the EDT) before asserting or capturing, for example `character-sheet-name`, `character-overview-value-2` or the Gear slot table's 28 rows.
  - The focus traversal policy orders only a showing window, so focus-target tests show their frame.
- **Evidence.** The baseline is `build/p3a/evidence/baseline.txt` (Task 1). Task 10's 21 captures are in `build/p3a/ui-test/screenshots/redesign-p3a-characters/`; `RosterViewsTest` logs the 500-character timing lines for the validation record.
- **Encoding.** Java snippets contain literal `·`, `…`, `‹`, `—` and `≈`; the build compiles UTF-8.
