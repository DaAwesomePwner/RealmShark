# DPS meters

The DPS Logger opens in **Meters** mode for live and saved encounters. Choose **Legacy** to use the original text/equipment view and its existing menu options.

These controls are in the **Damage meters** tab. The adjacent **Resources & buffs** tab charts local HP/MP and buff intervals and provides an observed-uptime summary. Its visit selection is independent of the damage encounter; see [Activity modules](ACTIVITY.md) for coverage and recording details.

- The enemy list defaults to highest recorded maximum HP. Select an enemy to inspect that fight, or All enemies for the displayed encounter. Latest hit, longest fight, and asset-tagged bosses-only sorting are available above the list. HP ordering is a useful boss-first approximation, not a boss classifier.
- Rank by damage, DPS, outgoing hit count, estimated incoming damage, or incoming event count. Bars scale to the highest visible value and use consistent class colors; disable Class colors for violet bars. All table columns can be sorted numerically by clicking their headers.
- Filter by class, search player names, or apply the existing filter/highlight presets. Damage shares retain the full selected enemy scope as their denominator when players are filtered out.
- Select a player to inspect individual hits. Incoming ranking modes switch the detail pane to incoming events. Details display the latest 500 events; totals include every recorded event. Unknown item/source data is labeled explicitly. Equipment information remains available in Legacy mode.
- **Right-click a player row → Inspect** opens the same read-only equipment/stat details used by Inspect: class, level, guild, character mode, base stats, equipped items and enchants. The action works in Meters and Legacy equipment rows. In Meters, the keyboard context-menu key or Shift+F10 opens the menu for the selected player. The clicked build is pinned when the menu opens, so live sorting/refreshes cannot change the target. Saved encounters use their recorded player data; missing fields remain labeled as not captured.
- Resize either divider to prioritize the enemy list, rankings, or details. Scroll the table horizontally for additional columns at smaller widths. The selected ranking value also appears inside the player bar.
- **Pause this view** freezes one displayed snapshot for both Meters and Legacy while capture continues. Switching display modes or receiving a new map preserves that snapshot; unpause refreshes immediately, and choosing another encounter clears the pause. The source label gives the snapshot's display time, not a capture timestamp. Resources & buffs has separate controls. Enemy/player selection and column sorting survive ordinary live refreshes.

## Interpretation

**Recorded share %** in Meters is player damage divided by all recorded damage on the selected enemies, including hidden players and unattributed damage. A zero total leaves the percentage unavailable. Legacy text/icons instead show **% enemy max HP**: player damage divided by that enemy's captured maximum HP. For example, 200 damage out of 400 recorded against a 1,000-HP enemy is 50% recorded share but 20% enemy max HP.

DPS is recorded outgoing damage divided by the first-to-last selected enemy hit interval. The all-enemy interval includes gaps between fights. Player filters change neither this time denominator nor recorded-share percentages. Legacy uses the same interval definition for each enemy section. A zero-duration interval displays unavailable DPS. It is not a rolling or active-time DPS estimate.

Incoming damage uses the same retained player damage events as the original hover tooltip, including events recorded for other players. **All enemies** shows full dungeon incoming totals, including events outside the outgoing DPS window. Selecting an enemy limits incoming totals to that fight's inclusive time window; the detail pane also shows the full dungeon total. Bosses-only overview uses the first-to-last boss hit window. Incoming totals include every source in the chosen interval, not just attacks from the selected enemy, and overlapping windows do not double-count events. Incoming values can include existing local AoE/ground estimates; counts describe recorded events. An em dash means no incoming records are available for that remote player, not confirmed zero damage. Missing capture cannot be reconstructed.

Incoming rankings include represented outgoing contributors plus the captured local player when available, not a complete player roster. A captured-local zero and an unavailable remote value remain distinct. Legacy equipment tooltips use the same inclusive fight boundaries as Meters.

Packet decoding and damage reconstruction retain their existing behavior. Step 1 adds an optional historical local-player context to saved encounters while preserving the previous Java serialization identifier; existing recordings remain readable.

## Encounter library

Click the encounter button between **Previous** and **Next** (labeled **Live** while live) to open the **Encounter library** for retained captured encounters and imported `.dps` files. **Search encounters** finds dungeon names, source filenames, IDs and recorded dates; combine it with **Captured / Imported** source and local-context availability filters. Sort by recorded start, elapsed duration, contributors or damage, then select a row to view it.

Selection and export checks are independent. Use Space on an exportable row to toggle its check, then **Save checked**. Checks survive sorting and filtering; the count reports checks hidden by the current filters. Live is not exportable. **Load** imports a file and **View imported encounter** opens that exact entry. Importing identical bytes again reuses the existing entry, even under a renamed file; different files claiming the same recording ID stay separate and show a variant notice.

Recorded start is the first captured tick, not guaranteed map entry. Elapsed is the retained encounter duration, not the DPS hit window. Contributors are represented damage-owner objects, not a complete roster. Historical local context is available, partial or unavailable; current live values do not fill missing history. The library remembers filters and exact selection/check references, but does not automatically reopen files after restart: it lists this app run's recordings and the files you import. Every closed fight is also saved automatically as a combat summary (below), which the Runs feed, the run recap and Home read after a restart. A summary is not the hit-level recording: save wanted encounters as `.dps` files before closing, or turn on **Keep full combat detail**. This local library is separate from the automatic [app-session archive](SESSION-HISTORY.md).

## Saved combat history

Every closed fight is saved automatically, on its own background worker, as a **combat summary** in the [app-session archive](SESSION-HISTORY.md#combat-history). A fight closes when the map changes and when capture stops: the fight open at that moment is saved as it stands. Capture itself only hands the closed recording over; building and writing the summary never delays packet processing or the window. Preview mode saves nothing.

A summary has two parts, both keyed by the recording's ID:

- **The record** (card-sized, a few KB): the dungeon, entry time, first tick and elapsed time, the first-to-last hit window, the run it belongs to (its exact session and visit), your verified local row, the total and unattributed damage, and every contributor's damage, hits, biggest hit, damage taken, deaths and rank, with the enemy count, enemy types and bosses. Player names are saved as the meter shows them.
- **The detail** (read only by the run recap): damage over time in 1 s buckets (fights longer than 30 minutes use wider buckets, at most 1,800 values), for your row and the top 12 contributors; each player's damage by source and item; enemies grouped by type with their count, largest known maximum HP, damage and hits; and the death notifications.

The totals, window, share and ranks are exactly the meter's. Your row is the recording's verified local player only: without one, your DPS, rank, share and deaths stay "—" with the reason, and another player's row is never used instead. A recording counts as a run's when its entry context carries that run's exact session and visit ID; nothing is linked by dungeon name or time. Hits recorded before the first tick count in the totals and are reported, not charted.

The **Runs** feed shows your DPS, rank and share from these records on each card, the run recap shows the full Damage section, and Home's **Recent runs** takes each run's DPS from them, so all three survive a restart. When a run has several recordings, cards, tiles and Home use the one with the longest hit window; the recap's picker offers the others. While a recording is still in this app run's memory, Home prefers it over its saved copy (they share the recording ID).

**Keep full combat detail** (Settings › General › Combat history, off by default) also saves each fight's complete recording, every hit, in the encounter library's `.dps` format without the debug packet log (the log holds chat and account-list packets, so it never leaves memory). These files take about 14 MB per 100,000 hits and are kept for 30 days by default (7 days, 30 days, 90 days or 1 year). A view that lists and reopens them is planned; meanwhile they are ordinary `.dps` files in the session's `combat-full` folder, in the format the library's **Save checked** writes, so **Load** can import one. Summaries are always saved, and kept forever unless **Keep combat summaries** chooses 1 year or 90 days. See [Session history](SESSION-HISTORY.md#combat-history) for where the files live and how pruning works.

**Clear DPS Logs** clears only this app run's encounter list in the DPS Logger (the recordings held in memory). It does not delete saved combat history; only the Combat history retention settings remove saved summaries and full detail.

Not saved:

- **The fight in progress when the app exits or crashes.** Only closed fights are saved, and nothing is checkpointed while a fight runs. Change area or stop capture first to keep it.
- **The rest of a fight after capture restarts in the same area.** Stopping capture saves the fight so far, linked to its run. What is recorded after capture starts again in the same area is a separate recording that is not linked to that run; its own local row can still be verified.

## Saved context and safe exports

- **My Class / My Guild** use the selected encounter's recorded local context consistently in Meters and Legacy text/icon views. Switching the live character does not change those historical matches.
- Older files infer context only from consistent, recorded local-player markers. If a relative predicate cannot be evaluated, a visible notice explains why. Explicit names/classes/guilds still apply; a relative-only preset with no usable context leaves all players visible.
- Encounter library exports preserve existing files and use numbered suffixes for collisions, including repeated exports. Turning **Save Debug Data** off creates a separate recording rather than replacing a richer file.
- Serialization completes in a temporary file before a destination is exclusively created. Normal write failures remove the new partial file; an abrupt process termination can leave a new partial export, but cannot overwrite an existing recording.

If capture misses the local player's spawn data, outgoing hit packets alone cannot supply the player's missing damage inputs. Live meters display a warning while the local character is unresolved. Saved encounters with retained combat diagnostics display an incomplete-personal-damage warning when local shots precede the captured spawn record. Recordings without those diagnostics cannot be checked for this particular gap. Changing areas or reconnecting provides a new opportunity to capture full character data; it does not repair an incomplete earlier encounter. The warning update passed all 44 Java tests.

## Validation and launch

Live display updates use detached combat snapshots published by the packet processor at most once per second, with immediate publication on map transitions. Swing renders only the latest snapshot when Damage meters is visible. Capture never waits for a DPS table redraw, and hidden tabs do not accumulate queued redraws. Completed encounters are still archived by the model on the map transition, independently of viewing them. Enemy selection is retained by object ID across snapshot replacements.

The September 9 delay investigation found the previous per-tick `invokeAndWait` call blocking the packet processor behind Swing rendering. Recent retained discovery samples showed about 226 seconds of variation between processing time and the advancing server clock within one area; those older samples omit map names, so the precise Nest visit cannot be identified from them alone. A later thread snapshot showed both threads caught up and idle. The regression suite deliberately blocks Swing while hits and dungeon archiving continue, and verifies detached stats/hits/projectiles, retained selection, and existing serialization identifiers. A fresh live dungeon capture is still needed to verify the reported symptom is resolved in play.

The updated build passed all 118 tests. A separate compatibility check loaded all 14 recovered encounters (3,926 enemies and 180,309 hits): display snapshots matched both recorded hit totals and legacy aggregate totals. The slowest snapshot copy in that local check took 22 ms; this is a sample measurement, not a worst-case guarantee for future encounters.

Validated with 43 passing Java tests, wide/compact Swing renders, and successful offline meter rendering of all 14 recovered `.dps` encounters. Incoming totals and hit counts exactly matched the legacy tooltip for all 415 rows with incoming records, including 406 remote-player rows, across those recordings. This does not establish new live-game damage accuracy. Screenshots are under `build/ui-test/screenshots/dps-meters-*.png`.

The updated package is `build/libs/RealmShark-v1.2.3.jar`. Save any wanted current logs before closing the running app, then reopen through `Launch-RealmShark.cmd` to load the new build using its protected runtime copy.
