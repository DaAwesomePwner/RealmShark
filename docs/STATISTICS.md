# Where Statistics went

The **Statistics** page is gone. P5b took it out of the sidebar, and P6a (2026-09-29) removed it once every part worth keeping had another home. The user decided to re-home the essentials and drop the rest. This page lists where each part went. The loot rules it used to describe are now in [Loot](LOOT.md), and the dungeon views are in [Runs & DPS › Dungeons](ACTIVITY.md#dungeons).

| Old part of Statistics | Now |
| --- | --- |
| **Fame Graph** (live: follow or pin a character, ranges, total or gain, interval comparison) | **Removed** (user decision: re-home only the essentials). Saved fame per character and session is in [Characters › Fame history](CHARACTERS.md#fame-history-analyst), whose **Open selected session's full fame graph** opens a saved session's whole graph |
| **Fame Table** (live characters, session gain, observed time, fame per hour) | **Removed** as a live table (same decision). The saved first and last reading, gain and observed time per character and session are in **Characters › Fame history** (Analyst) |
| Fame Table's **map breakdown** (fame per map and per visit) | **Removed** (user decision: the per-map fame breakdown is dropped) |
| Fame Table's **Sessions** menu (New Session, View Saved Sessions, Show Map Fame) and the automatic `.fame` save | **Removed** (user decision). **Open fame session file…** stays: in Characters › Fame history and in Settings search, it opens a saved `.fame` file read-only |
| **Loot › Live log** (this app run's bags, item tooltips, Filter Loot visibility) | **Loot › Highlights**: notable drops, tiles and bags by dungeon, with Filter Loot applied to its grid ([Loot](LOOT.md#highlights)). Facts only the Live log showed are **removed** (user decision): the player's skin, the exalt loot bonus, players left at a kill and the Moonlight flame counter |
| Loot › legacy **loot sharing status** | Highlights' ⋯ › **Loot sharing status…** ([Loot](LOOT.md#loot-sharing-status)); the opt-out stays under File |
| **Loot explorer** (live and saved, view tabs) | **Loot › Explore**, one view selector over every live and saved loot view ([Loot](LOOT.md#explore)) |
| **Dungeon Stats** (live counters of this app run, with search, Enemies and Loot by source) | **Removed** (user decision): the **Dungeon statistics**, **Enemy hit events** and **Loot by source** views over saved history in **Runs & DPS › Dungeons › Analysis** cover it |
| Saved Statistics views: Dungeon loot profile, Session comparison, Dungeon statistics, Enemy hit events, Loot by source, A/B cohorts | **Runs & DPS › Dungeons › Analysis** (Analyst). All except Dungeon statistics are also Analyst views of **Loot › Explore** |
| Saved **Character fame** view | **Characters › Fame history**, an Analyst tab ([Characters](CHARACTERS.md#fame-history-analyst)) |
| **Alt+5** | Opens **Runs & DPS › Dungeons**, with a Back entry |
| Settings search "Statistics (fame table, live loot log)" | **Removed**. Searching "statistics", "dungeon stats" or "alt+5" finds Dungeons, and "fame" finds Character fame history |
| Dungeons › Analysis banner and its **Open Statistics** link | **Removed** |

Nothing about capture changed. The same fame readings (`fame`), loot bags (`loot`) and dungeon counters (`dungeon-totals`) are still saved in session history, in the same format.

## Preferences left behind

The removed page's saved preferences are left where they are, never deleted. They are no longer read:

- `ui.tabs.statistics`: the Statistics tab strip.
- `ux.archive.statistics`: the saved Statistics workspace, **including any named saved Statistics views**. They are no longer offered anywhere, because the views they named now live on other pages with their own saved states (Loot's in `ux.archive.loot`, the Dungeons analysis's in `ux.archive.dungeon-analysis`, Fame history's in `ux.archive.character-fame`).
- `ux.archive.statistics-live`, `ux.archive.statistics-live-loot`, `ux.archive.statistics-live-fame-graph`, `ux.archive.statistics-live-fame-table` and `ux.archive.statistics-loot`: the live Statistics views and the old Loot explorer inside Statistics.
- `statistics`, `my-info` and `dps-logger` in the sidebar layout (`ui.nav.order`, `ui.nav.hidden`, `ui.nav.pinned`): still read, and ignored because no such page exists. `dps-logger` is still written beside a hidden `runs`, so an older (P5b) build does not show a hidden Runs & DPS again.

## Fame sessions

The automatic `.fame` save (the `FameSessions` folder in the app folder) stopped with the Fame Table. Files already saved there stay and are never deleted. **Open fame session file…** opens one read-only in the fame session viewer, as the Fame Table's Sessions menu did ([Characters › Fame history](CHARACTERS.md#fame-history-analyst)). Fame readings are still saved to session history as before, so Home, the character sheet's Fame tab and Characters › Fame history keep working.

## Validation

`ShellHookIntegrationTest` checks that no page, sidebar row, menu item, page action, destination or search entry for Statistics remains and that its words lead to Dungeons. `NavLayoutTest` checks that a saved `statistics` in the sidebar layout is read and ignored, and `ui.RunsDpsEvidenceTest` checks that Alt+5 opens Dungeons with a Back entry. `FameProvenanceTest` keeps the fame-saving checks, `LootCaptureTest` the loot capture checks, and `ReportingStatisticsTest` the saved loot coverage and rate rules. The P6a record is [P6a validation](superpowers/plans/2026-09-29-p6a-validation.md).
