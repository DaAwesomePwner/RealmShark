# Presentation redesign roadmap

This is the execution index for the redesign in `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md`. Phases run in order on `claude/realmshark-ui-ux-redesign-cb0914`, with one PR per phase/sub-phase. Synchronize the branch with verified `main` after each merge, and independently review each final PR head before merging (AGENTS.md). Detailed task-by-task plans exist for P0–P1c. P2–P6 get their own detailed plan (superpowers:writing-plans) at phase start, because they build on the kit and shell APIs as merged.

## Status

| Phase | Plan | Branch | State |
|---|---|---|---|
| P0 Platform | [2026-09-26-p0-platform.md](2026-09-26-p0-platform.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Merged: PR #17 (`9b6844a`) |
| P1a Design kit | [2026-09-26-p1a-design-kit.md](2026-09-26-p1a-design-kit.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Merged: PR #18 (`7b3e0c5`) |
| P1b Shell and navigation | [2026-09-26-p1b-shell.md](2026-09-26-p1b-shell.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Merged: PR #19 (`0abafe4`) |
| P1c Filters, tabs and columns | [2026-09-26-p1c-filters-tabs-columns.md](2026-09-26-p1c-filters-tabs-columns.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Merged: PR #20 (`e973f10`) |
| P2 Home | [2026-09-26-p2-home.md](2026-09-26-p2-home.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Merged: PR #21 (`94db6f6`) |
| P3a Characters: gallery, sheet, journal v5 | [2026-09-27-p3a-characters.md](2026-09-27-p3a-characters.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Merged: PR #22 (`04a61d4`) |
| P3b Characters: Exalts grid, Pets, Pet and Fame tabs | [2026-09-27-p3b-characters.md](2026-09-27-p3b-characters.md) | `claude/redesign-handoff-next-steps-edrr7w` | Merged: PR #23 (`b559bca`) |
| P4 Quests | [2026-09-28-p4-quests.md](2026-09-28-p4-quests.md) | `claude/redesign-handoff-next-steps-edrr7w` | Merged: PR #24 (`e541874`) |
| P5a Runs: combat auto-save, feed, recap | [2026-09-28-p5a-runs.md](2026-09-28-p5a-runs.md) | `claude/redesign-handoff-next-steps-edrr7w` | Merged: PR #25 (`3ab077c`) |
| P5b Runs & DPS: tabs, Live meter, Recordings, Dungeons, sidebar, S8 | [2026-09-28-p5b-runs-dps.md](2026-09-28-p5b-runs-dps.md) | `claude/redesign-handoff-next-steps-edrr7w` | Merged: PR #26 (`73400af`) |
| P6a Structure: destination IDs, retired pages, Loot Highlights and Explore, Statistics removal | [2026-09-29-p6a-structure.md](2026-09-29-p6a-structure.md) | `claude/redesign-handoff-next-steps-edrr7w` | Merged: PR #27 (`82983c2`) |
| P6b Consistency: Scope ▾ merge, Advanced restyles, relative times, sidebar drag, final screenshots | written after P6a merges | `claude/redesign-handoff-next-steps-edrr7w` | Outline below and in the P6a plan's "Deferred scope" |

Update the State column when a phase's PR merges and `main` is verified.

P0 merged in PR #17 as `9b6844a`; its merge tree matches the reviewed P0 source.
P1a merged in PR #18 as `7b3e0c5`, including the brand-icon correction `ee8d085`.
P1b merged in PR #19 as `0abafe4`, including fix `a011bfd` (pulled before P1c).
P1c merged in PR #20 as `e973f10`, including fix `4ca1657` (startup restore no longer un-hides tabs).
P2 merged in PR #21 as `94db6f6`, including seven review fixes (`f10bd7d`..`2367bc7`).
P3a merged in PR #22 as `04a61d4`, including fix `508d1d1` for the two Codex pet-merge threads; its merge tree matches the reviewed head, and `shadowJar` plus an isolated `--help` pass on it. P3b merged in PR #23 as `b559bca`, including the Codex review fix `9c0da67`; its merge tree matches the reviewed head, and `shadowJar` plus an isolated `--help` pass on it. P4 merged in PR #24 as `e541874` (Codex review: no findings); its merge tree matches the reviewed head, and `shadowJar` plus an isolated `--help` pass on it.
P5a merged in PR #25 as `3ab077c`, including the Codex review fix `21d896e` (queued fights are saved before the history store closes); its merge tree matches the reviewed head, and `shadowJar` plus an isolated `--help` pass on it. P5b merged in PR #26 as `73400af`, including the Codex review fixes `a7b873e` and the quest native-matrix harness fix `1498102`; its merge tree matches the reviewed head, and `shadowJar` plus an isolated `--help` pass on it. P6 is split into P6a and P6b (user decision, 2026-09-29). P6a merged in PR #27 as `82983c2`, including the Codex review fix `4a22ce4` (Filter Loot applies before Highlights' notable limit); its merge tree matches the reviewed head, and `shadowJar` plus an isolated `--help` pass on it. P6b is planned next.
Evidence: [P0 validation](2026-09-26-p0-validation.md),
[P1a validation](2026-09-26-p1a-validation.md), [P1b validation](2026-09-26-p1b-validation.md),
[P1c validation](2026-09-26-p1c-validation.md), [P2 validation](2026-09-26-p2-validation.md), [P3a validation](2026-09-27-p3a-validation.md), [P3b validation](2026-09-27-p3b-validation.md), [P4 validation](2026-09-28-p4-validation.md), [P5a validation](2026-09-28-p5a-validation.md), [P5b validation](2026-09-28-p5b-validation.md).

## Resume

**Latest handoff: [2026-09-27-redesign-handoff.md](2026-09-27-redesign-handoff.md).**
- It covers the current state, next steps, decisions, process and build notes, and the P3b inputs.
- Read it before anything else when resuming on a new machine or in a cloud session.

Read AGENTS.md, the spec, this roadmap and the plan for the earliest phase not marked merged. Reconcile branches, PRs and `main` with GitHub before continuing. Never overwrite unfamiliar work.

**Where the documents live.** The spec and plans landed on `main` with P0. All
phases continue on `claude/realmshark-ui-ux-redesign-cb0914` per the user update.
Historical commands naming separate phase branches are superseded by this
workflow; keep separate PRs and independent review for each phase.

**On another workstation.** The plans set `RS_TOOLS="C:/Users/dap/Downloads/RealmShark-realmshark/.tools"`, the tools folder of the original machine (it contains `jdk-17.0.20.1+1` and a pre-populated `gradle-home` for `--offline` builds). Point `RS_TOOLS`, `JAVA_HOME` and `GRADLE_USER_HOME` at that machine's JDK 17 and Gradle home instead. If its Gradle home has no cached dependencies yet, run the first build without `--offline` so Gradle can download them. Everything else in the plans (build directories, commands, test names) is machine-independent.

## P2 Home (absorbs My Info)

**Entry:** P1c merged. **Exit:** spec S1 and S9; My Info removed from the sidebar.

Detailed plan: [2026-09-26-p2-home.md](2026-09-26-p2-home.md). User decisions (2026-09-26):
- **One PR** for all of P2.
- **My Info becomes an unlisted Build page.** Page 6 is retitled Build and joins a new `NavEntry.Group.UNLISTED`, reachable by route, search, Alt+7 and the hero's Build action.
- **P1 carry-overs come first:** column widths follow font changes, and item-slot names refresh after asset reloads.

Outline:
1. Home page as shell page 14 (appended; indices stay stable), `Destination.HOME`, `Alt+H`, landing entry at the top of Core. `NavLayout` appends unknown entries to a saved `ui.nav.order`, so P2 performs a one-time prepend of `home` to existing saved orders.
2. `HomeModel` view models built off the EDT by one 1 Hz refresher that polls revision counters while Home is showing (`DiscoveryLog.activityView`, `KeyPopHistory.revision`, `LootDashboard` state version, the MyInfo snapshot, the journal). No event bus.
3. Hero card from the live MyInfo snapshot and journal: skin sprite, level, fame, maxed (base = total − boost vs `CharacterClass.getStats`), four `ItemSlot`s, eight `StatBar`s, and weapon DPS / MP-sec estimate tiles (`DisplayValue.estimate`). A stale state appears when out of game; an `EmptyState` appears before any capture.
4. Now card: area and elapsed time (`DiscoveryLog.currentVisit`), the top three live meter rows (add a read-only `DpsGUI.latestSnapshot()` accessor), and the last key pop as its own fact.
5. Today tiles (Today / This session): runs and completions, fame gain plus a fame/hour sparkline, notable loot, potions (`ArchiveQuery.Bounds` over `runs`, `loot`, `fame`).
6. Recent runs card (last five dungeon visits; DPS only through an exact encounter link) and Quests card (pinned, counts, list age and stale state).
7. Journal v4, first part: `AccountRecord.liveExaltBonus` (stats 105–112 while that class is played), `accountFame`, `gold`, `rankStars`, with observed timestamps. v3 records load unchanged.
8. Move the rest of My Info (per-projectile damage, recovery, dust, estimate scenario, recorded-DPS picker) into a Build panel opened from the hero. P3 rehomes it in the character sheet.
9. Evidence screenshots (populated, empty, stale, preview) at 1240 × 800 and 680 × 520, fonts 13/18, Simple and Analyst; timing harness for S9.

## P3 Characters

**Entry:** P2 merged. **Exit:** S2 and S5.

User decisions (2026-09-27):
- **Two PRs.** P3a covers the P2 follow-ups, journal v5, the roster gallery, the character sheet (Overview, Gear, Exalts, Build, Goals, Notes, Snapshot evidence, Death annotation) and the Build move. P3b covers the account Exalts grid, the Pets gallery, the sheet's Pet and Fame tabs, Home's pet rarity chip, the sheet Overview's pet card and Goals restyled as cards with progress (the last two deferred from P3a at its plan review).
- **Stat bars keep the "+N" boost text;** there is no painted overlay.
- **The journal moves to version 5, not 4 as first planned** (spec §8.3).

Outline:
1. Roster gallery: `JList` with `HORIZONTAL_WRAP` and a painted `CharacterCard` renderer. Graveyard collapses; the FilterBar drawer reuses the P1c roster filters; the existing table is the Table view.
2. Character sheet (`Destination.CHARACTER_SHEET`), with header and `CustomizableTabs("character")`: Overview, Gear, Exalts, Pet, Fame, Build (from P2), Goals, Notes, Snapshot evidence (Analyst), Death annotation (dead characters only).
3. Exalts grid: one tile per observed class, header tiles for loot boost and fully exalted classes, class drill-down with `PipMeter`s, "N to next tier" and "where to earn it" (`exaltationConfig.xml`).
4. Pets gallery with rarity, family and ability bars; the feeding calculator goes in a drawer.
5. Journal v5, remaining fields: per-character `pet`, `dungeonCompletions`, `exp`, `hasBackpack`; account `exaltSeenByClass`, `vaultPotions`. No backfill.
6. Evidence and journal migration tests (v4 → v5 load, partial rendering).
7. Deferred from P2: the Home hero's pet rarity chip (needs the pet journal field); Build moves from the unlisted page into the sheet's Build tab, and the hero's Build action follows it; a painted boost overlay on StatBar if wanted (P2 shows the live boost as +N beside each bar). Deferred from P3a's plan review (2026-09-27) to P3b: the sheet Overview's pet card (it needs P3b's pet names and rarity) and Goals restyled as cards with progress (P3a moves the Goals tab unchanged).
8. Status (2026-09-27): P3a implements items 1, 2 (without the Pet and Fame tabs), 5 (as journal version 5) and 7 (except the pet rarity chip). P3b covers items 3 and 4, the Pet and Fame tabs, the pet rarity chip, the Overview pet card and Goals restyled as cards with progress.
9. Status (2026-09-28): P3b implements items 3 and 4, the Pet and Fame tabs, the pet rarity chip, the Overview pet card and Goals as cards, plus the P3a review findings deferred to it. User decisions: the Pets gallery shows equipped pets plus the live Pet Yard (no journal change); fame history is exact by account (new fame samples carry the hashed account key; older ones are excluded and counted); the sheet's Goals tab shows this character's cards over the account-wide panel; the loot boost is per class (header: the class in game, else last played).

## P4 Quests

**Entry:** P3 merged. **Exit:** S3.

Outline:
1. O1 first: record sanitized `QuestData.expiration` samples from a live Daily Quest Room visit using a read-only diagnostic that prints only the expiration strings and their count. Implement `QuestExpiry.parse` only for the confirmed formats.
2. Quest board: a painted `QuestCard` list grouped by chest tier, user type label or none. It shows badges (Repeatable / One-time / Done), the category chip, the expiry chip (warn color under 6 h), reward sprites first ("Pick 1 of N" for `itemOfChoice`) and requirement sprites with counts. A detail drawer shows description, full lists and the raw expiration in Analyst mode.
3. Summary line with the stale state, "Pinned first", and group-by control; "Name types…" in the Filters drawer.
4. Planner restyle: plan cards with reserved / available / missing bars; the manual stock editor in a drawer. Semantics unchanged.
5. Deferred from P2: the Home Quests card's expiry countdown, from the same confirmed QuestExpiry formats.
6. Status (2026-09-28, user decisions): P4 is planned without the expiry countdown. Items 1 and 5, the Board's expiry chip, "expiring today" and S3's expiry half move to a later countdown phase (its samples diagnostic will include the list's receipt time, UTC rounded to the minute). The Board shows cards with the existing table kept as a Table view; the tabs are titled Board and Planner.
7. Status (2026-09-28): implemented items 2–4 and the S3 click path (pinned quests and their rewards: 0 clicks on Home, 1 click to the Board, Back returns Home), plus Home's "Rewards not captured" line and the Quests routes (a plain route opens the Board, search opens the Planner with Back). Record: [P4 validation](2026-09-28-p4-validation.md).

## P5 Runs and DPS

**Entry:** P4 merged. **Exit:** S4 and S8; storage measured.

Outline:
1. `encounters` SessionStore module: an `EncounterSummary` plain class (map, start, elapsed, enemies, per-player metrics and damage by source, deaths, 1 s damage buckets for the local player and the top 12), checkpointed by `recordingId` with its `EncounterContext`. "Keep full combat detail" setting (default off) and a retention setting; measure storage on the large synthetic history before choosing defaults.
2. Runs & DPS page with `CustomizableTabs("runs")`: Feed, Dungeons, Live meter, Recordings.
3. Run feed: a painted `RunCard` `JList` grouped by day, loaded 50 at a time. Cards show outcome, time, duration, party, damage share and rank, loot strip, fame gained (fame samples by exact visit), deaths and exalt increase, each only from exact links.
4. Run recap (`Destination.RUN_RECAP`, replaces `RunWorkbench` text). Tiles plus collapsible sections: Damage (meter, damage-over-time chart, damage by source), Loot, Players, Resources, Timeline, Evidence. Missing links state a one-line reason.
5. Dungeons cards (dungeon totals plus runs); session comparison and cohorts in Analyst.
6. Live meter restyle; Recordings list (auto-saved and imported `.dps`).
7. Statistics and DPS Logger leave the sidebar. Their pages stay reachable by route until P6.
8. FilterBar for the DPS meter, the encounter library and the remaining Statistics sub-pages (deferred from P1c).
9. Deferred from P1c:
   - Merge the live scope row (Browse saved · session · Refresh · ⋯) into the page FilterBar on Runs, Timeline and Resources.
   - Make `saved-resource-tabs` customizable.
   - Retire the Statistics view tabs (`historical-statistics-tabs`, Fame Table, Dungeon Stats) with the Statistics page.
   - Add the S6 screenshots missing for Timeline, Resources and Party.
10. Status (2026-09-28, user decisions): P5 is split into two PRs. **P5a** ([plan](2026-09-28-p5a-runs.md)) covers items 1, 3 and 4 plus Settings › General › Combat history and S4: saved combat records (`encounters`, `encounter-detail`) and optional full detail (`combat-full`, default off, pruned after 30 days), summaries kept forever by default, the run feed and the run recap on the existing Runs page. **P5b** covers items 2 and 5–9 and S8; the single DPS meter moves into the Runs & DPS › Live meter tab and the DPS Logger page becomes a pointer (Alt+8 opens the tab). Storage measured in research: a card record is about 2 KB, a full summary 20–80 KB, the large test history about 24 MB of full summaries.
11. Status (2026-09-28, user decisions for P5b, [plan](2026-09-28-p5b-runs-dps.md)): one PR; the live scope-row merge (item 9, first bullet) moves to P6; Dungeons cards count finished runs (completion over Completed + Left + App ended, labeled observed; averages and best DPS from completed runs); session comparison and A/B cohorts are embedded in Dungeons as an Analyst Analysis view; no per-recording delete. Research measured S8 on current code: 9–12 ms p95 overall without a live fight, but 190–360 ms p95 when switching into the DPS meter during a 300-enemy fight (an O(n²) enemy-list refill, fixed in P5b Task 3).

## P6 Loot and cleanup

**Entry:** P5 merged. **Exit:** no orphaned pages or styles; docs describe the new IA.

Outline:
1. Loot: Highlights (notable drops grid, tiles, per-dungeon strip) and Explore (a view selector replacing the 9–12 tabs; analytical views in Analyst).
2. Restyle the Advanced pages (Party, Timeline, Key-pops, Logging, Bridge review) with kit components; condense `HistoryTables.controls` into the overflow menu. Add drag-to-reorder for sidebar rows (deferred from P1b, where the context menu and Ctrl+Shift+Up/Down cover reordering). FilterBar for Logging and Bridge review, `NotificationsGUI` tabs and the per-render tab sets (loot archive views, key-pop archive modes) as customizable or view-selector controls, relative-time columns and Analyst-only column hiding (all deferred from P1c).
3. Replace the numeric shell API (`TITLES`, `select(int)`, `pageOf`, page-keyed `ShellNavigator`) with destination IDs, and migrate the ~40 page-number tests in one pass; remove retired pages (My Info, Statistics shell, DPS Logger shell) and unused styles (`StatsUi.metrics`, ad-hoc KPI cards).
4. Update README, `docs/UI-REDESIGN.md` and the module docs; take a final screenshot set of every page in both variants.
5. Deferred from P1c: merge the live scope row into the page FilterBar on Loot, Chat, Key-pops and, moved from P5b by user decision (2026-09-28), Runs, Timeline, Resources and Party. Also from P5b: Resources & buffs' final home, removing `historical-statistics-tabs`, and homes for Fame Table, the live Fame Graph's interval comparison and Loot › Live log.
6. Status (2026-09-29, user decisions for P6, [P6a plan](2026-09-29-p6a-structure.md)): two PRs. **P6a (structure)** covers items 1 and 3, the P5b deferrals in item 5 except the scope-row merge, and Settings' missing Loot filters, Chat and About sections (spec §6.7): destination IDs first as a pure refactor, the Build and DPS Logger pointer pages removed (Alt+7 and Alt+8 kept), loot capture moved into a non-UI `LootCapture`, Loot Highlights and Explore, Character fame moved to an Analyst tab of Characters, then Statistics removed (Alt+5 opens Runs & DPS › Dungeons) with the `UNLISTED` group and the unused `StatsUi` styles. Dropped with Statistics by user decision: the legacy `.fame` autosave and Sessions popup ("Open fame session file…" stays), the per-map fame breakdown, the live Dungeon Stats view and the Live log's live-only facts. Resources & buffs stays in the Live meter. **P6b (consistency)** covers items 2, 4 and the scope-row merge of item 5, using the spec's single "Scope ▾" chip on all seven archive pages.
