# Runs, Timeline, and Resources & buffs

Start capture and enable **Gameplay & diagnostics collection** in any activity module. This is the same shared collection switch as in Logging. Runs and timeline events save automatically in the shared app-session archive, independently of **Save diagnostic samples**. **Pause this view** holds the live display; **Export displayed history (unfiltered)** saves a local JSON snapshot. Session pickers and **Browse saved** expose persisted history; see [Session history](SESSION-HISTORY.md).

Activity export uses the last displayed history revision, including while frozen. Changing the Resources & buffs visit while frozen reads that frozen history; unfreezing catches up with current capture. Full export materialization and file writing run on a background worker. Runs/Timeline refreshes omit chart samples, and Resources & buffs reads only the selected visit's chart data. Hidden views defer automatic refreshes.

## Runs

Runs has its own sidebar entry and Alt+R shortcut. It opens on the **feed**: every saved dungeon run of every session as a card, grouped by the day it was entered. The saved-runs archive table is the **Table view**, unchanged. In Simple, the feed's ⋯ menu offers **Table view** and the table's ⋯ menu **Cards view**; Analyst has a **Cards / Table** toggle above both. The choice is remembered (`ui.runs.view`). A run opens in the **run recap**, the page for reading one run: its facts, the damage meter and chart, loot, players, resources, timeline and evidence.

### Run feed

The feed reads saved history of all sessions, newest first, 50 runs at a time; **Load more** reads the next 50. The run in progress appears once its checkpoint is saved (about every 10 s while capture is on). Each day has a header: **Today**, **Yesterday**, or the weekday and date, then the loaded runs' count, how many completed and their summed observed time, for example "5 runs · 3 completed · 1 h 35 m". "More below" means the day continues past the loaded runs. The summary line says how many runs are loaded out of how many match; runs without an entry time or visit ID are counted there and listed only in the Table view. A warning line appears when a session's facts could not be read fully; the affected cards say which fact is missing.

**Search saved runs** searches every saved run's text as the Table view does (Enter applies it at once). **Filters** holds one checkbox per outcome and the dungeon (the dungeons among the runs read so far); active filters show as chips with **Clear filters**. ⋯ **Refresh** reads again. The feed reads off the Swing thread when it first shows; afterwards it reads again only when saved history changed, checked when it shows and every 30 s while it shows. Arrow keys move within a day and Tab moves between days; Enter, Space or a double-click opens the run's recap.

Each card shows the outcome as a chip and as the card's left edge, the portal, the dungeon, the entry time ("14:32" today, "Yesterday 22:10", else the date), the observed duration and the party. Below that come your damage line, the loot strip and a line of fame, deaths and exalt progress. Every fact comes from the run's own saved visit or from records linked to exactly that run (its session and visit ID); nothing is matched by dungeon name or time. A missing link is said in words (and in the card's accessible name), never shown as 0:

| Card fact | Needs | Without it |
| --- | --- | --- |
| Outcome | The saved visit, its completion evidence and its session's state | **Unknown** |
| Time and duration | The visit's entry time and last saved observation (the observed span, not a verified clear time) | "duration —" |
| Party | The observed RotMG party | "Party —" (party not observed) |
| Your DPS · rank · share | A combat recording linked to exactly this run with your verified local row. With several recordings, the one with the longest hit window | The reason, muted: "No combat recording is linked to this run." or "The local player's row was not verified for this encounter; another player's row is never substituted." |
| Deaths | Your verified row, with a known name that no other player in that recording shares | Not shown |
| Loot strip | Bags whose drop-time visit is exactly this run: up to eight sprites in bag-colored wells, most notable first, "+N" and a summary such as "1 UT · 1 ST · 2 potions" | "No loot recorded in this run" when the session saved bags but none in this run; when the session saved no bag at all, loot is unknown and the card says so |
| Fame | Fame readings tagged with this run, each counted against the previous reading of the same account and character within one saved capture interval | Not shown (unknown, not 0) |
| Exalt progress | An increase observed inside this visit | Not shown |

Outcomes are shared by the feed, the recap and Home: **Completed** (completion evidence, as described below), **Left** (ended without it), **In progress** (this app run's open visit), **App ended** (a visit its session left open because the app closed or crashed) and **Unknown**. The Table view keeps its own outcome wording: a visit that a crashed launch left open still reads **In progress** there.

Empty states say why nothing shows: no saved runs yet, no runs match the search and filters (**Clear filters**), saved runs could not be read (**Try again**), or saved history is not open in this app run (**Show the table**).

### Run recap

**Home › Recent runs** opens a run's recap with its Damage section expanded in one click; from the sidebar, **Runs** and then a card reach the same recap in two. **‹ Runs** returns to the feed and Back returns to where you came from. Routes that select a run's row, such as those from Timeline, Loot and Inspect or the recap's **Open in Runs table**, open the Table view on that row; **Browse saved history** also shows the Table view.

The header shows the portal, dungeon, outcome, entry time, observed span, party and character (from fame readings tagged with this run) and, for a run in progress, when it was read. **Open in Runs table**, **Open in Loot** and **Open in Timeline** open the same exact run in those pages. Six tiles follow: **Your DPS** with your rank, **Damage share**, **Deaths** (with all players' deaths), **Fame**, **Loot** (item count and summary) and **Exalt progress**. Tiles use the run's longest linked recording and your verified row in it. An unknown tile shows "—", and its tooltip gives the reason.

Sections remember whether they are open (`ui.collapse.run-recap-<section>`). Damage and Loot start open; Players, Resources and Timeline start closed; Evidence is in Analyst only. A section without content shows a one-line reason instead of disappearing.

- **Damage** shows one recording as the meter recorded it, with every contributor. With several recordings a **Recording** picker lists them longest first ("Recording 1 of 2 · longest · 240 s window · 6 players"); the tiles keep the longest. The totals line gives the total damage, the first-to-last hit window and the player count. The damage-per-second chart shows **You** and **Top contributors** (the other saved players summed; **Others (top 12)** when some contributors were not saved) in Simple, a single summed line when no row is verified as yours, and one line per saved series in Analyst (your row and the top 12). Deaths are not placed on the chart: a death notification carries no time or object ID. The meter table lists rank, player (your row is highlighted and named "(you)"), damage with a bar, DPS, share, hits, biggest hit, damage taken and deaths. Select a row to see that player's damage by source (weapon, ability, summon, item effect) with its top items. DPS divides damage by the recording's first-to-last hit window, and share is of all recorded damage, unattributed hits included. At narrow widths the meter scrolls its own columns sideways.
- **Loot** lists this run's bags with their color, drop time, items and kinds.
- **Players** lists the players seen in the run with their class, four equipped items and **Inspect damage (inspected players only)**. That is the run's own Inspect tracking, not the recording's damage.
- **Resources** charts your HP, MP and conditions as Resources & buffs does. Aggregate-only visits say why there is no chart.
- **Timeline** lists this exact run's saved events, up to 500.
- **Evidence** (Analyst) is the Table view's run details text: outcome, timing and coverage, party and recording.

Reasons used when something is missing:

- "No combat recording is linked to this run."
- "The local player's row was not verified for this encounter; another player's row is never substituted."
- "The damage over time and by source of this recording were not saved; only its totals are."
- Your deaths are known only when your name is known and unique inside that recording.
- Fame gained is unknown when no reading was tagged with the run, or when a reading has no earlier reading of the same character within one unbroken capture.
- "No loot bag was saved in this run's session, so its loot is unknown."
- "No saved Timeline events for this exact visit."

A run that is not in this saved history shows **This run is not in saved history** with the archive's wording; no other run is substituted.

### Table view

The Table view lists observed **dungeon runs** (for example, Ice Citadel and Ocean Trench) with duration, progression increases, item/ability requests, capture issues, status, captured damage, and DPS. Live durations default to minutes (90 seconds displays as 1.5); the **Time** selector switches between minutes and seconds without changing saved timestamps or numeric sorting. Select a run for completion evidence, HP/MP ranges, condition coverage, party context, realm score, and retention information. The count and search apply to dungeon runs; the live **Export displayed history (unfiltered)** includes all retained dungeon runs and their linked events regardless of search.

Combine outcomes and completion-evidence sources with minimum/maximum duration in seconds, capture issues and timing gaps. Live filters cover the retained displayed snapshot, including while paused. Saved filters and sorting cover the full selected session scope before paging. **Date bounds…** uses entry time by default; choose **OVERLAP** for observed-interval overlap. Missing duration does not satisfy a numeric range. Named views remember these queries.

In saved Runs, Inspect and Resources, select one visit and choose **Export selected visit + Timeline…** for its full saved record and exactly session/visit-linked Timeline events. The preview states the linked count; events are not clipped to the visit query's dates. Shared page/all-match exports contain visit summaries. See [export populations](SESSION-HISTORY.md#export-the-intended-population).

### Which areas are runs, and when a run is completed

Nexus, Vault, Guild Halls, Pet Yard, Bazaar, daily rooms, the Realm overworld, Court of Oryx, tutorials, and known test maps are excluded from Runs. Their visits remain available in Timeline and Resources & buffs. Classification uses exact catalogued names, so content such as Battle for the Nexus still counts as a dungeon.

Area recognition includes a bundled catalog for startup without extracted assets and portal definitions across the extracted game XML, including Lost Halls, Cultist Hideout, The Void, Fungal/Crystal Cavern, Oryx's Sanctuary, Kogbold Steamworks and Moonlight Village. Known internal labels such as `mgm2 Dungeon` resolve to display names (The Trials of Cronus). If the map name is unknown, an exact, unambiguous catalogued display name can identify it. Shared display names such as Mysterious Arena cannot identify a specific dungeon without its internal name. Arbitrary server text is not retained.

Unresolved areas are excluded from Runs and remain labeled **Unrecognized area** in Timeline. Previously saved entries with that label cannot be renamed reliably: the original map name was not retained. Expanded recognition applies to new observations.

Both Runs and Inspect > Runs show **Completed** when a clean server victory notification, recognized final-boss dialogue (Moonlight Village, The Void, or The Shatters), or a matching server dungeon-completion counter confirms the clear. Counter confirmation waits for the same account and character on the immediately following area entry, within 30 seconds of the last observation; pauses, missing counts, character/account changes and ambiguous multi-clear increases are excluded. The completion result survives connection boundaries and application restarts. The original exit reason remains in details (or the Inspect summary tooltip). Without completion evidence, an ended visit shows **Left · completion unconfirmed**; leaving or despawning alone does not establish a kill. Previously saved unknown results cannot be reconstructed. Exalt progress received between visits remains unassigned.

## Inspect: Current Area and Runs

**Inspect** (formerly Security, Alt+3) opens on **Current Area**, the live player roster. Click any roster column header to sort and click again to reverse. **Maxed** sorts numerically and starts with 8/8 at the top. Options > Sort by guild restores the default guild ordering.

The **Runs** section lists the same dungeon visits as the standalone Runs module, newest first, with its own minutes/seconds selector. Search or sort the run list, then select a visit to see the players observed there and their last captured class, equipment, enchants, level, character mode, and base stats. Seasonal Crucible characters are violet; non-seasonal Crucible characters are amber, with explicit text for both modes. Hover Maxed for stats, or use **Actions > Equipment details…** / **Ctrl+E** for a read-only equipment and stats snapshot. Copy/export actions apply to the selected run; Current Area continues collecting live updates while you browse history.

Run rosters add sortable **Damage** and **DPS** columns; the first click ranks highest first. Damage comes from the existing damage recorder, including resolved summon ownership, and excludes incoming player damage. DPS divides each player's captured damage by the same first-to-last attributed hit interval for the dungeon; it is not divided by minutes spent in the area or by each player's individual hit interval. Gear remains the last captured loadout, so it may differ from equipment worn earlier in the run. Older recordings without damage tracking show **—**, and a single hit timestamp has no measurable DPS. Run-list Damage/DPS columns summarize the tracked players in that visit.

Roster search and class, exact guild, seasonal/crucible, maxed-count and requirements-result filters apply to the **current area or selected visit's roster**. They do not search every historical player. Guild and character-mode unknowns remain separate from observed no-guild/non-seasonal values. With a requirements preset active, **Pass**, **Below requirements** and **Unknown** distinguish satisfied rules, confirmed failures and missing evidence; without a preset the result is **Not evaluated**. Select a row for its reasons.

Roster display filters do not narrow bulk copy/export, which uses the complete current-area or selected-run roster. **Only copy below or unknown requirements** is the separate restriction for that operation. Current-area filters are remembered independently when you browse recorded rosters.

Snapshots update while a player is observed and remain after they leave the area. Repeat visits to the same dungeon stay separate. Record controls collection; the session archive automatically retains captured visits across launches and builds. Existing runs without player snapshots display an empty-state explanation; previous gear and stats cannot be reconstructed. Up to 300 player loadouts are retained per visit. The live journal shows up to 200 visits; older runs remain accessible in saved-history pages. These are observed world players, not inferred party membership. Normalized player names collapse metadata/case/class changes and returning object IDs into the last captured build.

## Timeline

Timeline has its own sidebar entry and Alt+T shortcut. Filter by visit and activity type, or search literal text. Select an event for its values and interpretation. All visits includes unassigned events.

Saved Timeline supports multiple event types and Assigned / Unassigned filters across the full query. Readable summaries precede raw details; literal search also includes retained value keys and values. Assigned means a recorded visit ID is present, not an inferred link from a matching map name or timestamp. Unknown event kinds remain visible and searchable.

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
