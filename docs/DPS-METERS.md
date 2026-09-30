# DPS meters

The damage meter is the **Live meter** tab of **Runs & DPS** (sidebar, Alt+R). **Alt+8** opens that tab directly and adds a Back entry, as do Home's **Now** card and Settings search ("Live DPS meter"); routes to an exact recording from other pages open it on that recording. There is one meter in the app, and there is no DPS Logger page any more: P5b moved the meter into Runs & DPS, and P6a removed the pointer page that remained. **Alt+8**, the DPS Logger's old shortcut, opens the Live meter, and a saved sidebar layout that still names the DPS Logger simply no longer lists it. Every recording the meter can show is listed in the **Recordings** tab beside it ([below](#recordings)).

The Live meter keeps two nested tabs. **Damage meters** holds the meter described here. **Resources & buffs** charts local HP/MP and buff intervals and provides an observed-uptime summary; its visit selection is independent of the damage encounter (see [Activity modules](ACTIVITY.md) for coverage and recording details), and its routes also bring the Live meter tab forward.

The meter opens in **Meters** mode for live and saved encounters. In Analyst mode the **View** choice in the Filters drawer also offers **Legacy**, the original text/equipment view with its existing menu options; switching to Simple while Legacy shows returns to Meters.

### One filter row

The meter's controls form one filter row, as on the other redesigned pages (one line at 1240×800 with the drawer closed; at narrower widths the encounter controls wrap below the search):

- **Search players** and **Rank by** (damage, DPS, outgoing hit count, estimated incoming damage or incoming event count).
- **Filters** opens the drawer: **Class**, **Enemies** (the enemy list's order: highest recorded maximum HP by default, latest hit, longest fight, or asset-tagged bosses only), **Preset** (the existing filter/highlight presets; **+** edits them), **Class colors** and, in Analyst, **View** (Meters or Legacy).
- Active filters show as chips ("Class: …", "Player: …", "Preset: …", "Bosses only"); a chip's × removes its own filter and **Clear** resets them all.
- The encounter controls: **‹** and **›** step through this app run's recordings, the position (**Live**, or "3 of 12") opens Recordings, **Go live** returns to the current fight and **Pause this view** freezes the display.
- **⋯** holds **Saved resources…**, **Edit DPS filters…**, **Open Recordings** and **Load .dps…** (adds a file to Recordings through the safe reader and shows it; the notice names the file only).

The meter summary and the encounter's link and status line stay below the row.

### Reading the meter

- Each row shows the player's **true rank** by the chosen metric among all players of the selected enemies ("#3"), painted before the name. Re-sorting a column or filtering by name or class never renumbers it. Unknown values (for example, damage taken with no incoming records) have no rank.
- Bars use the player's class hue (the same hue as Home's Now card) with the class sprite in the Class column, so color is never the only cue; turn off Class colors for violet bars. Your own row (the capture's character) is washed in the accent color and reads "(you)" in every column. All columns sort numerically from their headers.
- The enemy list shows one card per enemy under **All enemies · N**: the name, a **Boss** chip for asset-tagged bosses, and "900,000 HP · 41.2k dmg · 12.3 s" (maximum HP, damage recorded on it and its fight window). An enemy without a captured maximum HP reads "HP —", never 0. Analyst adds the object ID. Long names end in "…"; the tooltip keeps the full text. HP ordering is a useful boss-first approximation, not a boss classifier.
- Damage shares retain the full selected enemy scope as their denominator when players are filtered out.
- Select a player to open the **details drawer** under the table ("Details · Bravo · Wizard ×"): the individual hits, or incoming events in the incoming ranking modes. It shows the latest 500 events; totals include every recorded event, and unknown item/source data is labeled explicitly. **Escape** or **×** clears the selection and closes the drawer. **Explore all events…** and, when it is unavailable, the reason ("Select a player first") stay in a footer under the table whether or not the drawer is open. At least three table rows stay visible with the drawer open. Equipment information remains available in Legacy mode.
- **Right-click a player row → Inspect** opens the same read-only equipment/stat details as Party's **Equipment details…**: class, level, guild, character mode, base stats, equipped items and enchants. The action works in Meters and Legacy equipment rows. In Meters, the keyboard context-menu key or Shift+F10 opens the menu for the selected player. The clicked build is pinned when the menu opens, so live sorting/refreshes cannot change the target. Saved encounters use their recorded player data; missing fields remain labeled as not captured.
- Resize either divider to prioritize the enemy list, rankings, or details. Scroll the table horizontally for additional columns at smaller widths. The selected ranking value also appears inside the player bar.
- **Pause this view** freezes one displayed snapshot for both Meters and Legacy while capture continues. Switching display modes or receiving a new map preserves that snapshot; unpause refreshes immediately, and choosing another encounter clears the pause. The source label gives the snapshot's display time, not a capture timestamp. Resources & buffs has separate controls. Enemy/player selection and column sorting survive ordinary live refreshes.
- Switching to the Live meter during a heavy fight stays within the app's 100 ms page-switch goal: the enemy list is refilled in one pass. With a synthetic 300-enemy, 96,000-hit fight the switch measured 14–19 ms median and at most 35 ms p95 (before P5b: 173–197 ms median); see the [P5b validation record](superpowers/plans/2026-09-28-p5b-validation.md).

### Party outcomes

Every player on the meter keeps their damage and DPS; the **Outcome** column and the line under the window say whether they finished the dungeon. The text view lists the same outcomes in a "Party outcome" block (it follows the player filter: in filter mode it names only the players the filter shows), and the legacy icon view tags players who died or nexused. Outcomes are shown in dungeons only: in the Realm and in hubs the line reads "Outcomes are tracked in dungeons only". An area the built-in catalog does not know counts as a dungeon.

- **Completed**: in the dungeon when it ended. The end is the server's victory notice; for The Void, The Shatters and Moonlight Village also the final boss's closing line. When neither arrives, the fallback is the last enemy labelled `BOSS` (never a miniboss) leaving the hit list. That fallback is provisional while the dungeon is live (everyone stays In progress) and inferred on a saved recording: the reason says so and even your own completion is not marked confirmed. Leaving or dying afterwards still counts as completed.
- **Died**: a death notice for the player (or your own death packet, which names the killer) arrived before the end. Shows the time and the gravestone.
- **Nexused**: the player left view before the end and never came back, with the time and the HP they had. The game sends no nexus message for other players, so this is inferred: it can also be a disconnect or someone standing out of view when the dungeon ended. Your own nexus is confirmed by the nexus key press itself.
- **In progress**: a live encounter that has not ended yet.
- **Unknown**: the end was never seen (usually because you left first), the player was first seen after the end, or capture started mid-dungeon.

Players who walk out of view and come back are unaffected, and a reconnect under a new object ID counts as the same player. Recordings saved before outcome tracking show only deaths matched by a unique name ("Outcomes unavailable"). `.dps` files carry the outcome data, so imported recordings show the same outcomes. A recording made with outcome tracking can only be opened by this version or a newer one; older builds reject the file.

## Interpretation

**Recorded share %** in Meters is player damage divided by all recorded damage on the selected enemies, including hidden players and unattributed damage. A zero total leaves the percentage unavailable. Legacy text/icons instead show **% enemy max HP**: player damage divided by that enemy's captured maximum HP. For example, 200 damage out of 400 recorded against a 1,000-HP enemy is 50% recorded share but 20% enemy max HP.

DPS is recorded outgoing damage divided by the first-to-last selected enemy hit interval. The all-enemy interval includes gaps between fights. Player filters change neither this time denominator nor recorded-share percentages. Legacy uses the same interval definition for each enemy section. A zero-duration interval displays unavailable DPS. It is not a rolling or active-time DPS estimate.

Incoming damage uses the same retained player damage events as the original hover tooltip, including events recorded for other players. **All enemies** shows full dungeon incoming totals, including events outside the outgoing DPS window. Selecting an enemy limits incoming totals to that fight's inclusive time window; the detail pane also shows the full dungeon total. Bosses-only overview uses the first-to-last boss hit window. Incoming totals include every source in the chosen interval, not just attacks from the selected enemy, and overlapping windows do not double-count events. Incoming values can include existing local AoE/ground estimates; counts describe recorded events. An em dash means no incoming records are available for that remote player, not confirmed zero damage. Missing capture cannot be reconstructed.

Incoming rankings include represented outgoing contributors plus the captured local player when available, not a complete player roster. A captured-local zero and an unavailable remote value remain distinct. Legacy equipment tooltips use the same inclusive fight boundaries as Meters.

Packet decoding and damage reconstruction retain their existing behavior. Step 1 adds an optional historical local-player context to saved encounters while preserving the previous Java serialization identifier; existing recordings remain readable.

## Recordings

**Runs & DPS › Recordings** is the encounter library, now a tab instead of a dialog. It lists every combat recording, one row each: this app run's recordings held in memory, the combat summaries saved in history ([below](#saved-combat-history)), saved full-detail files and the `.dps` files you import. Open it from its tab, from the meter's position button (**Live** or "3 of 12") or ⋯ **Open Recordings**, or from Settings search ("Recordings (encounter library)").

Rows are merged by recording ID: a recording in memory and its saved summary are one row. An imported copy of a recording that is already listed stays a row of its own and says so ("Imported file · same recording as Captured · Mad Lab"); older imports without a recording ID are told apart by their file. Besides the library's columns (the export check, Entry, Dungeon, Recorded start, Elapsed, Contributors, Damage, Source file and Local context), two columns say where each recording stands:

| Column | Values |
| --- | --- |
| Run | The recording's own link to its run, fixed when it began: "Linked · Lost Halls · 2026-09-29 13:20:00" (the run's dungeon and entry time), "Unlinked" or "Legacy · unlinked". Nothing is matched by dungeon name or time. |
| Saved | "Summary saved"; "No saved summary (yet)" (nothing is saved in preview mode or outside a logged dungeon, or a save is still in progress); "Full detail · 12.3 MB"; "Full detail pruned (kept 30 days)"; "Imported file" (imports stay in memory for this app run). |

Unknown values are "—" or a reason, never 0. File names are shown, never folders or paths. Simple shows Export, Dungeon, Recorded start, Run, Saved and Damage, which fit a desktop-width window; Analyst adds Elapsed, Contributors, Source file, Local context and Entry and may scroll sideways. The live row ("The fight in progress") stays first under every sort and is selected until you choose a recording. With nothing to list, the tab says so ("No recordings yet", or "No recordings match" with **Clear filters**) and **Open live meter** stays in reach. **Save view state** and **Reset saved view state** are in the ⋯ menu.

The filter row holds **Search recordings** (dungeon, recording ID, file name, entry ID or date) and **Filters**: **Source** (All sources, This app run, Saved summary, Full detail, Imported), **Run link** (Any, Linked, Unlinked, Legacy) and **Local context** (Any, Available, Partial, Unavailable). Active filters show as chips; **Clear** resets them. The scope is **Last 30 days** (the default: saved recordings entered in the last 30 days) or **All sessions**; this app run's recordings and imports are always listed. ⋯ **Refresh** reads again.

The tab reads saved history off the Swing thread when it first shows. Afterwards it reads again only when something changed: saved history (checked when the tab shows and every 30 s while it shows), this app run's recordings (a fight closed, an import, Clear DPS Logs), the scope, or **Refresh**. Showing the tab again reads nothing new.

Selecting a row only shows its details; it never switches the meter. **Open** (named for what it does), Enter or a double-click opens the row where it lives, and Back returns to Recordings:

- **The live row** ("Open live meter") shows the live fight in the Live meter.
- **A recording in memory** ("Open in Live meter") shows it in the Live meter.
- **Saved full detail** ("Load full detail (12.3 MB)…") asks first, naming its size ("… · loads into memory"), then reads the file through the safe reader ([below](#safe-dps-reading)) and shows it in the Live meter. At most two loaded saved recordings stay in memory; the one the meter shows is never unloaded. If the same recording is already in memory, that copy opens and nothing is read.
- **A summary-only recording linked to its run** ("Open run recap") opens that run's recap on this recording.
- **A summary-only recording without a run link** (unlinked or legacy) ("Show summary") shows its saved summary read-only under the table: the totals line, the damage chart and the meter with damage by source, captioned "Summary only: hit detail was not kept (Settings › General › Keep full combat detail)".
- **Pruned full detail** is never offered. The row opens like a summary-only recording; its summary is captioned "Summary only: full detail was pruned (kept 30 days)".

Selection and export checks are independent. Use Space on an exportable row to toggle its check, then **Save checked**. Checks survive sorting and filtering; the count reports checks hidden by the current filters. Only recordings in memory can be exported; Live is not exportable. **Load** imports a file and **View imported encounter** opens that exact entry. Importing identical bytes again reuses the existing entry, even under a renamed file; different files claiming the same recording ID stay separate and show a variant notice. There is no per-recording delete: the Combat history retention settings and deleting a whole session in **History library…** remove saved combat data.

Recorded start is the first captured tick, not guaranteed map entry. Elapsed is the retained encounter duration, not the DPS hit window. Contributors are represented damage-owner objects, not a complete roster. Historical local context is available, partial or unavailable for recordings in memory (a saved summary does not keep it); current live values do not fill missing history. The tab remembers its filters and exact selection/check references (saved only when they change) but does not reopen imported files after a restart. A summary is not the hit-level recording: save wanted encounters as `.dps` files before closing, or turn on **Keep full combat detail**.

### Safe .dps reading

Every `.dps` read, an import (**Load** or ⋯ **Load .dps…**) or saved full detail, goes through an allow-list: only the classes of RealmShark's recording graph, and RealmShark's own packet data classes (which files saved with **Save Debug Data** hold), are read. Anything else stops the read before any of its code runs, with "This file contains data RealmShark does not read: <class name>". Recordings made with party-outcome tracking add the class `PresenceTimeline` and its parts to the allow-list, so they can only be opened by a build that has it (this version or newer); older builds stop at the first such class with the message above. Reads run on a dedicated background thread ("RealmShark recording reader") with a 64 MB stack, the stack the automatic save writes with, so long Realm recordings read without overflowing it; the window never waits on a read. Notices name the file, never its folder.

## Saved combat history

Every closed fight is saved automatically, on its own background worker, as a **combat summary** in the [app-session archive](SESSION-HISTORY.md#combat-history). A fight closes when the map changes and when capture stops: the fight open at that moment is saved as it stands. Capture itself only hands the closed recording over; building and writing the summary never delays packet processing or the window. Preview mode saves nothing.

A summary has two parts, both keyed by the recording's ID:

- **The record** (card-sized, a few KB): the dungeon, entry time, first tick and elapsed time, the first-to-last hit window, the run it belongs to (its exact session and visit), your verified local row, the total and unattributed damage, and every contributor's damage, hits, biggest hit, damage taken, deaths and rank, with the enemy count, enemy types and bosses. Player names are saved as the meter shows them.
- **The detail** (read only by the run recap): damage over time in 1 s buckets (fights longer than 30 minutes use wider buckets, at most 1,800 values), for your row and the top 12 contributors; each player's damage by source and item; enemies grouped by type with their count, largest known maximum HP, damage and hits; and the death notifications.

The totals, window, share and ranks are exactly the meter's. Your row is the recording's verified local player only: without one, your DPS, rank, share and deaths stay "—" with the reason, and another player's row is never used instead. A recording counts as a run's when its entry context carries that run's exact session and visit ID; nothing is linked by dungeon name or time. Hits recorded before the first tick count in the totals and are reported, not charted.

The Runs & DPS **Feed** shows your DPS, rank and share from these records on each card, the run recap shows the full Damage section, **Dungeons** takes each dungeon's best DPS from them, Recordings lists them, and Home's **Recent runs** takes each run's DPS from them, so all of these survive a restart. When a run has several recordings, cards, tiles and Home use the one with the longest hit window; the recap's picker offers the others. While a recording is still in this app run's memory, Home prefers it over its saved copy (they share the recording ID).

**Keep full combat detail** (Settings › General › Combat history, off by default) also saves each fight's complete recording, every hit, in the Recordings `.dps` format without the debug packet log (the log holds chat and account-list packets, so it never leaves memory). These files take about 14 MB per 100,000 hits and are kept for 30 days by default (7 days, 30 days, 90 days or 1 year). [Recordings](#recordings) lists them ("Full detail · 12.3 MB") and loads one into the Live meter when you open it; a file removed by retention reads "Full detail pruned (kept 30 days)". They are ordinary `.dps` files in the session's `combat-full` folder, in the format **Save checked** writes. Summaries are always saved, and kept forever unless **Keep combat summaries** chooses 1 year or 90 days. See [Session history](SESSION-HISTORY.md#combat-history) for where the files live and how pruning works.

**Clear DPS Logs** clears only this app run's encounter list in the Live meter (the recordings held in memory). It does not delete saved combat history; only the Combat history retention settings remove saved summaries and full detail.

Not saved:

- **The fight in progress when the app exits or crashes.** Only closed fights are saved, and nothing is checkpointed while a fight runs. Change area or stop capture first to keep it.
- **The rest of a fight after capture restarts in the same area.** Stopping capture saves the fight so far, linked to its run. What is recorded after capture starts again in the same area is a separate recording that is not linked to that run; its own local row can still be verified.

## Saved context and safe exports

- **My Class / My Guild** use the selected encounter's recorded local context consistently in Meters and Legacy text/icon views. Switching the live character does not change those historical matches.
- Older files infer context only from consistent, recorded local-player markers. If a relative predicate cannot be evaluated, a visible notice explains why. Explicit names/classes/guilds still apply; a relative-only preset with no usable context leaves all players visible.
- Recordings exports (**Save checked**) preserve existing files and use numbered suffixes for collisions, including repeated exports. Turning **Save Debug Data** off creates a separate recording rather than replacing a richer file.
- Serialization completes in a temporary file before a destination is exclusively created. Normal write failures remove the new partial file; an abrupt process termination can leave a new partial export, but cannot overwrite an existing recording.

If capture misses the local player's spawn data, outgoing hit packets alone cannot supply the player's missing damage inputs. Live meters display a warning while the local character is unresolved. Saved encounters with retained combat diagnostics display an incomplete-personal-damage warning when local shots precede the captured spawn record. Recordings without those diagnostics cannot be checked for this particular gap. Changing areas or reconnecting provides a new opportunity to capture full character data; it does not repair an incomplete earlier encounter. The warning update passed all 44 Java tests.

## Validation and launch

Live display updates use detached combat snapshots published by the packet processor at most once per second, with immediate publication on map transitions. Swing renders only the latest snapshot when Damage meters is visible. Capture never waits for a DPS table redraw, and hidden tabs do not accumulate queued redraws. Completed encounters are still archived by the model on the map transition, independently of viewing them. Enemy selection is retained by object ID across snapshot replacements.

The September 9 delay investigation found the previous per-tick `invokeAndWait` call blocking the packet processor behind Swing rendering. Recent retained discovery samples showed about 226 seconds of variation between processing time and the advancing server clock within one area; those older samples omit map names, so the precise Nest visit cannot be identified from them alone. A later thread snapshot showed both threads caught up and idle. The regression suite deliberately blocks Swing while hits and dungeon archiving continue, and verifies detached stats/hits/projectiles, retained selection, and existing serialization identifiers. A fresh live dungeon capture is still needed to verify the reported symptom is resolved in play.

The updated build passed all 118 tests. A separate compatibility check loaded all 14 recovered encounters (3,926 enemies and 180,309 hits): display snapshots matched both recorded hit totals and legacy aggregate totals. The slowest snapshot copy in that local check took 22 ms; this is a sample measurement, not a worst-case guarantee for future encounters.

Validated with 43 passing Java tests, wide/compact Swing renders, and successful offline meter rendering of all 14 recovered `.dps` encounters. Incoming totals and hit counts exactly matched the legacy tooltip for all 415 rows with incoming records, including 406 remote-player rows, across those recordings. This does not establish new live-game damage accuracy. Screenshots are under `build/ui-test/screenshots/dps-meters-*.png`.

The updated package is `build/libs/RealmShark-v1.2.3.jar`. Save any wanted current logs before closing the running app, then reopen through `Launch-RealmShark.cmd` to load the new build using its protected runtime copy.
