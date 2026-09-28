# App sessions and durable history

Each normal RealmShark launch creates a new session. Stopping capture, changing areas, or browsing an older session does not create a new app session. Workspaces start with **Current Session** unless a saved view state restores another scope.

## Storage across builds

History is saved automatically under **`%LOCALAPPDATA%\RealmShark\history`** on Windows, independently of the application/build folder. Extracting a newer portable build into another folder on the same Windows account uses this same history. Settings, extracted assets, optional plain-text logs and manually exported files retain their existing locations.

History is kept until explicitly deleted; saved combat history also follows its own retention settings ([below](#combat-history)). Each session has metadata, append journals for messages/pops/loot/fame/timeline events, replaceable run/statistics checkpoints, and a combat summary for each closed fight. Writes happen on a background worker; failures remain visible in the session toolbar and pending records retry. Normal shutdown checkpoints the final run before closing the session. Preview can browse saved history but does not record or delete it.

New fame readings also record the player's hashed account key: the same pseudonymous key the character journal (`Characters/journal.json`) uses, never a raw account ID, name or credential. Character IDs are only unique within an account, so the key lets the character sheet's **Fame** tab and Home's **Progress** card tell two accounts' characters with the same ID apart. The latest fame checkpoint (`fame-latest`) is kept per account and character. Readings recorded before this version, or before capture saw the account, have no account: Home counts them as before, and the Fame tab leaves them out and says how many. Statistics' fame views do not use the account yet.

## Browsing

**Chat, Key-pops, Loot, Statistics and Inspect** have independent session pickers. Runs, Timeline and DPS Logger's Resources & buffs also expose the saved visits they use. **Runs** opens on a feed of saved runs from every session, grouped by day; its Table view keeps the session picker (see [Runs](ACTIVITY.md#runs)).

- **Current Session** returns to the original live view. Capture continues while another scope is selected.
- Pick a timestamped session to inspect that launch, or **All Sessions** to aggregate history.
- **Browse saved** reads the durable archive for the current session, including records older than its live memory buffers. **Current live view** returns to live capture.
- **Refresh** loads the latest saved state. In an archive, use the saved-history search field and press Enter to search the entire selected scope.
- Saved search, module filters and column sorting apply to the **entire selected scope before paging**. **Previous page / Next page** browse those ordered matches; the status identifies the matching unit, count and saved revision. A summary row can represent many events, so its row count is not an event count.
- Live Chat/Key-pops clear actions clear their display buffers. Saved history remains available. Chat stars are saved and restored with their message IDs.

The live journal still limits chart/event buffers for responsiveness. Closed visits and timeline events are archived before those buffers evict them, so a long app session does not lose its earlier runs. Player loadouts still have the existing per-visit bound and chart samples retain their existing per-visit coverage limits.

### Dates, saved views and tables

- Date bounds include **From** and exclude **Until**: midnight to the next midnight selects one day. Blank endpoints are unbounded. Use the displayed zone and an ISO date-time such as `2026-09-24T00:00:00Z`; ambiguous local times need an explicit offset. **Include unknown times** controls undated records when bounds are set. Unbounded queries include unknown times.
- Visit queries default to entry time (**ENTRY**). **OVERLAP** instead includes visits whose observed interval overlaps the bounds. It cannot reconstruct an unobserved start or end. Relative periods resolve when selected; paging does not move their dates.
- Enter a name and choose **Save view**, then use **Load view** to restore its scope, filters, ordering and supported table/selection state. Each workspace keeps its own last-used and named views. Live controls have separate state where supported. A saved view stores presentation choices, not a permanent copy of its result data.
- **Reset filters** restores the query defaults within the selected scope. **Delete view** removes a named view; **Reset saved state** clears that workspace's saved state and names. Neither deletes history records. Check save feedback: active settings can remain in memory after a disk-save failure.
- Use column presets, visibility controls and **Reset columns** to arrange saved tables. **Enter** opens details, **Ctrl+C** copies selected rows, and **Ctrl+Shift+Up/Down** sorts the focused column across the saved query.

Paging keeps one saved revision. **Refresh** reads a new revision, including writes that have since reached disk; queued producer writes are not guaranteed to appear immediately. Read errors and partial-source issues are reported rather than treated as a complete empty result.

### Export the intended population

Saved workspaces offer **Export selected…**, **Export page…** and **Export all matches…**, with CSV or JSON. Read the preview's scope, count and unit before confirming. Exports retain the acquired revision even if capture advances; existing files are preserved with a new filename on collision. **Open export folder** is enabled after success.

For Runs, Inspect and Resources, selecting exactly one visit exports its full saved visit plus Timeline events with the same recorded session and visit ID. **Export selected visit + Timeline…** exposes that workflow directly. Missing links stay missing. Page/all-match visit exports contain the displayed visit summaries; Timeline exports contain event rows. Key-pop summaries export summaries, while its Events tab exports pop events.

JSON includes a manifest and row origins; CSV starts with a manifest record before the column headers. The manifest records query, resolved dates, source scope, revision and count/unit. Live local exports and [Logging reports](LOGGING.md#export-a-diagnostic-report) have their own labeled populations.

### History library

Choose **History library…** to search sessions by label, build or available modules, including sessions beyond the short picker list. Select a row and use **Open session** (or Enter) to open it in the requesting workspace. **Rename…**, **Delete…** and **Import old folder…** manage the archive.

The library exposes unreadable metadata separately so healthy sessions remain discoverable. **All Sessions** can continue with healthy entries while reporting excluded unreadable sources; a selected unreadable session cannot be opened. Open/interrupted sessions and unknown coverage are labeled. Module presence or an empty query is not proof of continuous recording, nor proof that no gameplay occurred; complete recording intervals are not available.

## Combat history

Every closed fight is saved as a combat summary in the session it was recorded in ([what a summary holds](DPS-METERS.md#saved-combat-history)). Three module folders hold it, each file named by the store's stable checkpoint name for the recording's ID:

- **`encounters`**: one card-sized record per recording (JSON). The Runs feed and Home read only these.
- **`encounter-detail`**: each recording's damage over time, damage by source, enemies and deaths (JSON). Only the run recap reads one, for the run and recording it shows.
- **`combat-full`**: the complete recording as a `.dps` file, only while **Keep full combat detail** is on. It never contains the debug packet log.

A dedicated background worker builds each summary and hands its record and detail to the history writer, and writes full detail files itself; capture and the window never do this work. Preview mode writes nothing.

**Settings › General › Combat history** chooses what is kept:

- **Keep full combat detail**: off by default. **Keep full detail for** 7 days, 30 days (default), 90 days or 1 year applies while it is on.
- **Keep combat summaries**: **Forever** (default), 1 year or 90 days.

Pruning runs on the same worker when history opens at startup and again after any Combat history change. It deletes full detail files older than their day count and, when summaries have a period, a recording older than that loses its record, detail and full detail together. A recording's age is its record's entry time, else the record file's time; a file without a record is dated by its own time. Only closed sessions this instance can lock are touched: the current session and sessions open in another RealmShark are skipped and tried again next time. Pruning touches only these three folders, never runs, loot, fame or other modules; deleting a session in **History library…** removes its combat history with the rest of the session. **Clear DPS Logs** deletes nothing saved.

Storage, measured with synthetic fights (`CombatStorageMeasurementTest`):

| Fight | Record | Detail |
| --- | --- | --- |
| 150 s dungeon, 8 players, 60 enemies | 1,781 B | 10,651 B |
| 600 s dungeon, 8 players, 150 enemies | 1,799 B | 28,115 B |
| 3,600 s Realm-like fight, 30 players, 2,000 enemies | 4,924 B | 152,029 B |

A large synthetic history of 30 sessions × 40 runs, with one record and one detail per run, takes 17.0 MB on disk, of which `encounters` is 2.1 MB and `encounter-detail` 12.8 MB. Reading all 1,200 records took 19–27 ms and Home's read 6–7 ms. Full detail takes about 14 MB per 100,000 hits.

Not saved: the fight in progress when the app exits or crashes (there are no in-progress checkpoints), and the link between a run and what is recorded after capture restarts in the same area (that remainder is a separate recording).

## Loot profiles and comparisons

Choose **All Sessions** (or a particular session) in Loot and open **Dungeon loot profile**. It shows observed visits, items per run, items per captured hour, UTs per hour, observed duration, and separate totals/rates for white bags, UT equipment, ST equipment and stat potions. Columns sort numerically; use the column controls to reveal additional analytical measures.

Visits with no linked bags remain in the denominator only within sessions containing at least one saved bag, including an empty or unassigned bag. This is partial observation, not proof of continuous coverage. Sessions without saved loot evidence remain unknown and are excluded, as are run-only imported visits. Loot is joined to the visit ID captured with the bag, using the shared canonical dungeon name. Unassigned drops make rates unavailable; an eligible visit without positive observed duration makes hourly rates unavailable but does not by itself invalidate per-run rates. These metrics describe captured drops, not pickups, guaranteed drop chances, or a server-wide total. Selected-dungeon details explain the eligible and excluded cohorts.

**Session comparison** lists session totals side by side. Statistics adds first/last fame changes, per-character summaries, saved dungeon/enemy/item counters, and a button to open a selected session's fame graph. Character IDs are scoped by app session so they do not merge across launches. Current Statistics dungeon counters exclude the preloaded cumulative baseline.

## Inspect and DPS identity

Inspect's historical run rows use a normalized player name, excluding comma-suffixed metadata, casing and surrounding whitespace. Returning with a different object ID or class updates the last captured build instead of adding another named-player row. Older duplicate buckets are consolidated on read and their recorded damage is preserved.

DPS targets exclude identified players in both live snapshots and saved-encounter rendering. Their incoming damage remains available on player rows. Saved Inspect builds can still display their captured name, class, equipment and base stats when game assets are unavailable; unknown max-stat definitions display **—**.

## Import and deletion

On normal startup, existing structured history in the current app folder is imported: `logs/discovery/activity-history.json` (runs and timeline) and `FameSessions/*.fame`. **Import old folder…** imports those files from a previous installation folder. Originals remain untouched, and import markers prevent repeated builds from duplicating records or automatically resurrecting an imported session you deleted.

Old plain-text chat/key-pop logs remain in their existing files. Structured chat/key-pop/loot history starts with captures made by this version; missing historical fields are not fabricated.

In **History library…**, **Delete…** deletes the selected past session across modules after confirmation. The current session and sessions locked by another running RealmShark instance are protected. The shared history folder is personal data and is not included in the shareable Windows ZIP.

## Validation and developer isolation

Gradle tests set `realmshark.historyDir` under their own working directory, keeping both normal and scaled UI tests away from the real Windows history folder. Combat history tests build summaries from synthetic fights in temporary folders; `CombatStorageMeasurementTest` prints the storage figures above. Storage tests cover separate launches/build versions, UTF-8/time round trips, checkpoint replacement, records beyond live limits, pagination/search, interrupted final records, write failures/retry, active-session deletion, and repeatable legacy imports. Module tests cover independent browsing while capture continues, restored chat stars, key-pop timestamps/counts, historical Inspect ownership, zero-loot rate denominators, and separate character IDs across sessions.
