# Runs, Timeline, and Resources & buffs

Start capture and enable **Record** in any activity module. Record is the same shared collection switch as Collect in Logging. Runs and timeline events now save automatically in the shared app-session archive, independently of diagnostic Save logs. Freeze pauses the selected view; Export history saves a local JSON snapshot. Session pickers expose previous launches; see [Session history](SESSION-HISTORY.md).

Activity export uses the last displayed history revision, including while frozen. Changing the Resources & buffs visit while frozen reads that frozen history; unfreezing catches up with current capture. Full export materialization and file writing run on a background worker. Runs/Timeline refreshes omit chart samples, and Resources & buffs reads only the selected visit's chart data. Hidden views defer automatic refreshes.

## Runs

Runs has its own sidebar entry and Alt+R shortcut. It lists observed **dungeon runs** (for example, Ice Citadel and Ocean Trench) with duration, progression increases, item/ability requests, capture issues, status, captured damage, and DPS. Durations default to minutes (90 seconds displays as 1.5); the **Time** selector switches between minutes and seconds without changing saved timestamps or numeric sorting. Select a run for completion evidence, HP/MP ranges, condition coverage, party context, realm score, and retention information. The count and search apply to dungeon runs; Export history includes all retained dungeon runs and their linked events regardless of search.

Nexus, Vault, Guild Halls, Pet Yard, Bazaar, daily rooms, the Realm overworld, Court of Oryx, tutorials, and known test maps are excluded from Runs. Their visits remain available in Timeline and Resources & buffs. Classification uses exact catalogued names, so content such as Battle for the Nexus still counts as a dungeon.

Area recognition includes a bundled catalog for startup without extracted assets and portal definitions across the extracted game XML, including Lost Halls, Cultist Hideout, The Void, Fungal/Crystal Cavern, Oryx's Sanctuary, Kogbold Steamworks and Moonlight Village. Known internal labels such as `mgm2 Dungeon` resolve to display names (The Trials of Cronus). If the map name is unknown, an exact, unambiguous catalogued display name can identify it. Shared display names such as Mysterious Arena cannot identify a specific dungeon without its internal name. Arbitrary server text is not retained.

Unresolved areas are excluded from Runs and remain labeled **Unrecognized area** in Timeline. Previously saved entries with that label cannot be renamed reliably: the original map name was not retained. Expanded recognition applies to new observations.

Both Runs and Inspect > Runs show **Completed** when a clean server victory notification, recognized final-boss dialogue (Moonlight Village, The Void, or The Shatters), or a matching server dungeon-completion counter confirms the clear. Counter confirmation waits for the same account and character on the immediately following area entry, within 30 seconds of the last observation; pauses, missing counts, character/account changes and ambiguous multi-clear increases are excluded. The completion result survives connection boundaries and application restarts. The original exit reason remains in details (or the Inspect summary tooltip). Without completion evidence, an ended visit shows **Left · completion unconfirmed**; leaving or despawning alone does not establish a kill. Previously saved unknown results cannot be reconstructed. Exalt progress received between visits remains unassigned.

## Inspect: Current Area and Runs

**Inspect** (formerly Security, Alt+3) opens on **Current Area**, the live player roster. Click any roster column header to sort and click again to reverse. **Maxed** sorts numerically and starts with 8/8 at the top. Options > Sort by guild restores the default guild ordering.

The **Runs** section lists the same dungeon visits as the standalone Runs module, newest first, with its own minutes/seconds selector. Search or sort the run list, then select a visit to see the players observed there and their last captured class, equipment, enchants, level, character mode, and base stats. Seasonal Crucible characters are violet; non-seasonal Crucible characters are amber, with explicit text for both modes. Hover Maxed for stats, or use **Actions > Equipment details…** / **Ctrl+E** for a read-only equipment and stats snapshot. Copy/export actions apply to the selected run; Current Area continues collecting live updates while you browse history.

Run rosters add sortable **Damage** and **DPS** columns; the first click ranks highest first. Damage comes from the existing damage recorder, including resolved summon ownership, and excludes incoming player damage. DPS divides each player's captured damage by the same first-to-last attributed hit interval for the dungeon; it is not divided by minutes spent in the area or by each player's individual hit interval. Gear remains the last captured loadout, so it may differ from equipment worn earlier in the run. Older recordings without damage tracking show **—**, and a single hit timestamp has no measurable DPS. Run-list Damage/DPS columns summarize the tracked players in that visit.

Snapshots update while a player is observed and remain after they leave the area. Repeat visits to the same dungeon stay separate. Record controls collection; the session archive automatically retains captured visits across launches and builds. Existing runs without player snapshots display an empty-state explanation; previous gear and stats cannot be reconstructed. Up to 300 player loadouts are retained per visit. The live journal shows up to 200 visits; older runs remain accessible in saved-history pages. These are observed world players, not inferred party membership. Normalized player names collapse metadata/case/class changes and returning object IDs into the last captured build.

## Timeline

Timeline has its own sidebar entry and Alt+T shortcut. Filter by visit and activity type, or search literal text. Select an event for its values and interpretation. All visits includes unassigned events.

The timeline includes observed party changes, progression, equipment, inventory activity, resources, and capture events. Item/ability requests do not prove that the action succeeded.

## DPS Logger: Resources & buffs

The Damage meters tab retains the existing live/saved encounter controls. Resources & buffs has a separate visit selector. Selecting a gameplay visit does not change the damage encounter.

The chart aligns local HP/MP samples and condition lanes on an elapsed-time axis. Hover for values; Ctrl+mouse wheel zooms the horizontal axis. Scroll to see additional lanes. Search filters condition lanes. Drag the divider to give the chart or visit details more room.

Gray condition lanes show observed coverage; violet intervals show active effects. Blank intervals mean unknown coverage. Inactive effects are meaningful only where their corresponding coverage lane is present. HP and MP share a raw-value scale and are sampled at most once per second. Lines do not bridge gaps longer than two seconds or extend beyond the latest sample.

Uptime summary divides active time by observed time, not total visit duration. Primary and additional condition flags have separate coverage denominators. Data gaps and decoder boundaries invalidate ongoing observations until fresh values arrive.

This version charts the local character. Numeric party roster context is retained, but party-wide buff uptime requires verified links between roster members and observed player objects. It is not inferred from the local character's effects.

## History and bounds

Existing aggregate-only visits remain readable, including uptime summaries. They cannot be reconstructed into time-series charts; new captures supply those samples.

The live journal retains up to 200 visits and 1,000 timeline events. Durable session history receives closed runs and events before live-buffer eviction. Resource points and condition intervals are each capped at 1,000 per visit and 12,000 across the live journal. Adjacent identical condition intervals are merged. The oldest live plot records are removed first; visit details disclose omissions, and aggregate statistics remain available.

Gameplay session history is stored under `%LOCALAPPDATA%\RealmShark\history`. Existing `logs/discovery/activity-history.json` files can be imported without modifying the originals. Clearing Logging diagnostics preserves saved sessions. Reports remain local and follow the field restrictions described in [Discovery logging](LOGGING.md).

Checkpoint snapshots are acquired by the persistence worker. Dirty history is acknowledged only after a successful write, so a failed final checkpoint remains eligible for retry on orderly close. See [Step 2 validation](STEP-2-RESPONSIVENESS.md) for concurrency coverage and synthetic measurements.
