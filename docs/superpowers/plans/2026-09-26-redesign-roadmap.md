# Presentation redesign roadmap

This is the execution index for the redesign in `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md`. Phases run in order, each as its own branch and PR from verified `main`, each independently reviewed before merge (AGENTS.md). Detailed task-by-task plans exist for P0–P1c. P2–P6 get their own detailed plan (superpowers:writing-plans) at phase start, because they build on the kit and shell APIs as merged.

## Status

| Phase | Plan | Branch | State |
|---|---|---|---|
| P0 Platform | [2026-09-26-p0-platform.md](2026-09-26-p0-platform.md) | `claude/realmshark-ui-ux-redesign-cb0914` | Implemented and locally verified; PR not merged |
| P1a Design kit | [2026-09-26-p1a-design-kit.md](2026-09-26-p1a-design-kit.md) | `feat/redesign-p1a-kit` | Planned |
| P1b Shell and navigation | [2026-09-26-p1b-shell.md](2026-09-26-p1b-shell.md) | `feat/redesign-p1b-shell` | Planned |
| P1c Filters, tabs and columns | [2026-09-26-p1c-filters-tabs-columns.md](2026-09-26-p1c-filters-tabs-columns.md) | `feat/redesign-p1c-adoption` | Planned |
| P2 Home | written at phase start | `feat/redesign-p2-home` | Outline below |
| P3 Characters | written at phase start | `feat/redesign-p3-characters` | Outline below |
| P4 Quests | written at phase start | `feat/redesign-p4-quests` | Outline below |
| P5 Runs and DPS | written at phase start | `feat/redesign-p5-runs` | Outline below |
| P6 Loot and cleanup | written at phase start | `feat/redesign-p6-cleanup` | Outline below |

Update the State column when a phase's PR merges and `main` is verified.

P0 is implemented on the existing redesign branch at the user's request. The
branch includes the planning documents as well as P0; no separate docs merge is
needed for this delivery. Fresh evidence is recorded in
[P0 validation](2026-09-26-p0-validation.md). P1 has not started.

## Resume

Read AGENTS.md, the spec, this roadmap and the plan for the earliest phase not marked merged. Reconcile branches, PRs and `main` with GitHub before continuing. Never overwrite unfamiliar work.

**Where the documents live.** The spec, the plans and this roadmap were written on branch `claude/realmshark-ui-ux-redesign-cb0914` (pushed to `origin`). Until that branch is merged into `main` as a docs-only PR, fetch it (`git fetch origin claude/realmshark-ui-ux-redesign-cb0914`) to read them. Merging it first is simplest: every phase plan says "branch from verified `main`", and the plans are then present on every phase branch.

**On another workstation.** The plans set `RS_TOOLS="C:/Users/dap/Downloads/RealmShark-realmshark/.tools"`, the tools folder of the original machine (it contains `jdk-17.0.20.1+1` and a pre-populated `gradle-home` for `--offline` builds). Point `RS_TOOLS`, `JAVA_HOME` and `GRADLE_USER_HOME` at that machine's JDK 17 and Gradle home instead. If its Gradle home has no cached dependencies yet, run the first build without `--offline` so Gradle can download them. Everything else in the plans (build directories, commands, test names) is machine-independent.

## P2 Home (absorbs My Info)

**Entry:** P1c merged. **Exit:** spec S1 and S9; My Info removed from the sidebar.

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

Outline:
1. Roster gallery: `JList` with `HORIZONTAL_WRAP` and a painted `CharacterCard` renderer. Graveyard collapses; the FilterBar drawer reuses the P1c roster filters; the existing table is the Table view.
2. Character sheet (`Destination.CHARACTER_SHEET`), with header and `CustomizableTabs("character")`: Overview, Gear, Exalts, Pet, Fame, Build (from P2), Goals, Notes, Snapshot evidence (Analyst), Death annotation (dead characters only).
3. Exalts grid: one tile per observed class, header tiles for loot boost and fully exalted classes, class drill-down with `PipMeter`s, "N to next tier" and "where to earn it" (`exaltationConfig.xml`).
4. Pets gallery with rarity, family and ability bars; the feeding calculator goes in a drawer.
5. Journal v4, remaining fields: per-character `pet`, `dungeonCompletions`, `exp`, `hasBackpack`; account `exaltSeenByClass`, `vaultPotions`. No backfill.
6. Evidence and journal migration tests (v3 → v4 load, partial rendering).

## P4 Quests

**Entry:** P3 merged. **Exit:** S3.

Outline:
1. O1 first: record sanitized `QuestData.expiration` samples from a live Daily Quest Room visit using a read-only diagnostic that prints only the expiration strings and their count. Implement `QuestExpiry.parse` only for the confirmed formats.
2. Quest board: a painted `QuestCard` list grouped by chest tier, user type label or none. It shows badges (Repeatable / One-time / Done), the category chip, the expiry chip (warn color under 6 h), reward sprites first ("Pick 1 of N" for `itemOfChoice`) and requirement sprites with counts. A detail drawer shows description, full lists and the raw expiration in Analyst mode.
3. Summary line with the stale state, "Pinned first", and group-by control; "Name types…" in the Filters drawer.
4. Planner restyle: plan cards with reserved / available / missing bars; the manual stock editor in a drawer. Semantics unchanged.

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

## P6 Loot and cleanup

**Entry:** P5 merged. **Exit:** no orphaned pages or styles; docs describe the new IA.

Outline:
1. Loot: Highlights (notable drops grid, tiles, per-dungeon strip) and Explore (a view selector replacing the 9–12 tabs; analytical views in Analyst).
2. Restyle the Advanced pages (Party, Timeline, Key-pops, Logging, Bridge review) with kit components; condense `HistoryTables.controls` into the overflow menu. Add drag-to-reorder for sidebar rows (deferred from P1b, where the context menu and Ctrl+Shift+Up/Down cover reordering). FilterBar for Logging and Bridge review, `NotificationsGUI` tabs and the per-render tab sets (loot archive views, key-pop archive modes) as customizable or view-selector controls, relative-time columns and Analyst-only column hiding (all deferred from P1c).
3. Replace the numeric shell API (`TITLES`, `select(int)`, `pageOf`, page-keyed `ShellNavigator`) with destination IDs, and migrate the ~40 page-number tests in one pass; remove retired pages (My Info, Statistics shell, DPS Logger shell) and unused styles (`StatsUi.metrics`, ad-hoc KPI cards).
4. Update README, `docs/UI-REDESIGN.md` and the module docs; take a final screenshot set of every page in both variants.
