# DPS meters

The DPS Logger opens in **Meters** mode for live and saved encounters. Choose **Legacy** to use the original text/equipment view and its existing menu options.

These controls are in the **Damage meters** tab. The adjacent **Resources & buffs** tab charts local HP/MP and buff intervals and provides an observed-uptime summary. Its visit selection is independent of the damage encounter; see [Activity modules](ACTIVITY.md) for coverage and recording details.

- The enemy list defaults to highest recorded maximum HP. Select an enemy to inspect that fight, or All enemies for the displayed encounter. Latest hit, longest fight, and asset-tagged bosses-only sorting are available above the list. HP ordering is a useful boss-first approximation, not a boss classifier.
- Rank by damage, DPS, outgoing hit count, estimated incoming damage, or incoming event count. Bars scale to the highest visible value and use consistent class colors; disable Class colors for violet bars. All table columns can be sorted numerically by clicking their headers.
- Filter by class, search player names, or apply the existing filter/highlight presets. Damage shares retain the full selected enemy scope as their denominator when players are filtered out.
- Select a player to inspect individual hits. Incoming ranking modes switch the detail pane to incoming events. Details display the latest 500 events; totals include every recorded event. Unknown item/source data is labeled explicitly. Equipment information remains available in Legacy mode.
- Resize either divider to prioritize the enemy list, rankings, or details. Scroll the table horizontally for additional columns at smaller widths. The selected ranking value also appears inside the player bar.
- Pause view freezes presentation while capture continues. Unpause refreshes immediately. Switching encounters clears the pause. Enemy/player selection and column sorting survive ordinary live refreshes.

## Interpretation

DPS is recorded outgoing damage divided by the first-to-last selected enemy hit interval. The all-enemy interval includes gaps between fights. A zero-duration interval displays unavailable DPS. It is not a rolling or active-time DPS estimate.

Incoming damage uses the same retained player damage events as the original hover tooltip, including events recorded for other players. **All enemies** shows full dungeon incoming totals, including events outside the outgoing DPS window. Selecting an enemy limits incoming totals to that fight's inclusive time window; the detail pane also shows the full dungeon total. Bosses-only overview uses the first-to-last boss hit window. Incoming totals include every source in the chosen interval, not just attacks from the selected enemy, and overlapping windows do not double-count events. Incoming values can include existing local AoE/ground estimates; counts describe recorded events. An em dash means no incoming records are available for that remote player, not confirmed zero damage. Missing capture cannot be reconstructed.

No packet decoding, damage reconstruction, or saved serialization formats were changed.

If capture misses the local player's spawn data, outgoing hit packets alone cannot supply the player's missing damage inputs. Live meters display a warning while the local character is unresolved. Saved encounters with retained combat diagnostics display an incomplete-personal-damage warning when local shots precede the captured spawn record. Recordings without those diagnostics cannot be checked for this particular gap. Changing areas or reconnecting provides a new opportunity to capture full character data; it does not repair an incomplete earlier encounter. The warning update passed all 44 Java tests.

## Validation and launch

Live display updates use detached combat snapshots published by the packet processor at most once per second, with immediate publication on map transitions. Swing renders only the latest snapshot when Damage meters is visible. Capture never waits for a DPS table redraw, and hidden tabs do not accumulate queued redraws. Completed encounters are still archived by the model on the map transition, independently of viewing them. Enemy selection is retained by object ID across snapshot replacements.

The September 9 delay investigation found the previous per-tick `invokeAndWait` call blocking the packet processor behind Swing rendering. Recent retained discovery samples showed about 226 seconds of variation between processing time and the advancing server clock within one area; those older samples omit map names, so the precise Nest visit cannot be identified from them alone. A later thread snapshot showed both threads caught up and idle. The regression suite deliberately blocks Swing while hits and dungeon archiving continue, and verifies detached stats/hits/projectiles, retained selection, and existing serialization identifiers. A fresh live dungeon capture is still needed to verify the reported symptom is resolved in play.

The updated build passed all 118 tests. A separate compatibility check loaded all 14 recovered encounters (3,926 enemies and 180,309 hits): display snapshots matched both recorded hit totals and legacy aggregate totals. The slowest snapshot copy in that local check took 22 ms; this is a sample measurement, not a worst-case guarantee for future encounters.

Validated with 43 passing Java tests, wide/compact Swing renders, and successful offline meter rendering of all 14 recovered `.dps` encounters. Incoming totals and hit counts exactly matched the legacy tooltip for all 415 rows with incoming records, including 406 remote-player rows, across those recordings. This does not establish new live-game damage accuracy. Screenshots are under `build/ui-test/screenshots/dps-meters-*.png`.

The updated package is `build/libs/RealmShark-v1.2.3.jar`. Save any wanted current logs before closing the running app, then reopen through `Launch-RealmShark.cmd` to load the new build using its protected runtime copy.
