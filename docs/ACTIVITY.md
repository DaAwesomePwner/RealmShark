# Runs & DPS, Timeline, and Resources & buffs

Start capture and enable **Gameplay & diagnostics collection** in any activity module. This is the same shared collection switch as in Logging. Runs and timeline events save automatically in the shared app-session archive, independently of **Save diagnostic samples**. **Pause this view** holds the live display; **Export displayed history (unfiltered)** saves a local JSON snapshot. Session pickers and **Browse saved** expose persisted history; see [Session history](SESSION-HISTORY.md).

Activity export uses the last displayed history revision, including while frozen. Changing the Resources & buffs visit while frozen reads that frozen history; unfreezing catches up with current capture. Full export materialization and file writing run on a background worker. Runs/Timeline refreshes omit chart samples, and Resources & buffs reads only the selected visit's chart data. Hidden views defer automatic refreshes.

## Runs & DPS

**Runs & DPS** has its own sidebar entry and Alt+R shortcut. It holds four tabs:

| Tab | What it shows |
| --- | --- |
| **Feed** | Every saved dungeon run of every session as a card, grouped by day, and each run's recap ([below](#run-feed)) |
| **Dungeons** | One card per dungeon over all saved runs, and in Analyst the session comparison and cohorts ([below](#dungeons)) |
| **Live meter** | The single damage meter and its Resources & buffs tab ([DPS meters](DPS-METERS.md)); **Alt+8** opens it |
| **Recordings** | Every combat recording: this app run's, saved summaries, kept full detail and imports ([DPS meters › Recordings](DPS-METERS.md#recordings)) |

The page starts on its first visible tab (Feed by default); the tab in front is not saved between launches. Tabs can be reordered (drag, the tab menu, or Ctrl+Shift+Left/Right) and hidden like the app's other customizable tabs (`ui.tabs.runs`). Only explicit navigation brings a hidden tab forward: a route from another page, Back, a Settings search entry, Alt+8, the DPS Logger pointer or a card's action. Back returns to the tab you left, as it was. The DPS Logger and Statistics pages left the sidebar in P5b; see [UI refresh history](UI-REDESIGN.md#information-architecture-after-p5-2026-09-29).

The Feed tab opens on the **feed**: every saved dungeon run of every session as a card, grouped by the day it was entered. The saved-runs archive table is the **Table view**, unchanged. In Simple, the feed's ⋯ menu offers **Table view** and the table's ⋯ menu **Cards view**; Analyst has a **Cards / Table** toggle above both. The choice is remembered (`ui.runs.view`). A run opens in the **run recap**, the page for reading one run: its facts, the damage meter and chart, loot, players, resources, timeline and evidence.

### Run feed

The feed reads saved history of all sessions, newest first, 50 runs at a time; **Load more** reads the next 50. The run in progress appears once its checkpoint is saved (about every 10 s while capture is on). Each day has a header: **Today**, **Yesterday**, or the weekday and date, then the loaded runs' count, how many completed and their summed observed time, for example "5 runs · 3 completed · 1 h 35 m". "More below" means the day continues past the loaded runs. The summary line says how many runs are loaded out of how many match; runs without an entry time or visit ID are counted there and listed only in the Table view. A warning line appears when a session's facts could not be read fully; the affected cards say which fact is missing.

**Search saved runs** searches every saved run's text as the Table view does (Enter applies it at once). **Filters** holds one checkbox per outcome and the dungeon (the dungeons among the runs read so far, by canonical name, so a dungeon's aliases such as `mgm2 Dungeon` and The Trials of Cronus are one choice); active filters show as chips with **Clear filters**. A Dungeons card's **Show runs** opens the feed with that dungeon's filter set. ⋯ **Refresh** reads again. The feed reads off the Swing thread when it first shows; afterwards it reads again only when saved history changed, checked when it shows and every 30 s while it shows. Arrow keys move within a day and Tab moves between days; Enter, Space or a double-click opens the run's recap.

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

**Home › Recent runs** opens a run's recap with its Damage section expanded in one click; from the sidebar, **Runs & DPS** and then a card reach the same recap in two. A Dungeons card's **Open best run** and a linked summary's **Open run recap** in Recordings open the recap on that recording. **‹ Runs** returns to the feed and Back returns to where you came from. Routes that select a run's row, such as those from Timeline, Loot and Inspect or the recap's **Open in Runs table**, open the Table view on that row; **Browse saved history** also shows the Table view.

The header shows the portal, dungeon, outcome, entry time, observed span, party and character (from fame readings tagged with this run) and, for a run in progress, when it was read. **Open in Runs table**, **Open in Loot** and **Open in Timeline** open the same exact run in those pages. Six tiles follow: **Your DPS** with your rank, **Damage share**, **Deaths** (with all players' deaths), **Fame**, **Loot** (item count and summary) and **Exalt progress**. Tiles use the run's longest linked recording and your verified row in it. An unknown tile shows "—", and its tooltip gives the reason.

Sections remember whether they are open (`ui.collapse.run-recap-<section>`). Damage and Loot start open; Players, Resources and Timeline start closed; Evidence is in Analyst only. A section without content shows a one-line reason instead of disappearing. The sections can be reordered: a section header's menu offers **Move up**, **Move down** and **Reset order**, and Ctrl+Shift+Up/Down moves a focused header, as the sidebar's rows move. The order is remembered (`ui.order.run-recap`); a moved section keeps its content, focus and open state.

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

### Dungeons

The **Dungeons** tab shows one card per dungeon over every saved session's dungeon runs. Each run is read with the feed's own rules (the shared outcome, the observed span, and loot and recordings linked to exactly that run), so a card agrees with the feed's cards for the same runs; a dungeon's aliases share one card. **Search dungeons** matches the name, **Filters › Sort** orders the cards by **Most visits** (the default), **Most recent** or **Name**, and ⋯ **Refresh** reads again. The summary line counts what was read, for example "Saved runs · all sessions · 4 dungeons · 7 runs · most visits first". The cards are read off the Swing thread when the tab first shows, and again only when saved history changed (checked when the tab shows and every 30 s while it shows). An empty state says why nothing shows: saved history is not open, no dungeon run is saved yet, nothing matches the search, or saved history could not be read.

Each card shows the portal, the dungeon, its number of visits and four facts. An unknown fact is "—" with a one-line reason under it, never 0:

| Card fact | How it is counted | Without it |
| --- | --- | --- |
| Completion 67% observed | Completed ÷ (Completed + Left + App ended): finished runs only, labeled "observed" because a Left or App ended run may include a clear the app did not see. In progress and Unknown runs are not counted and are named under the rate ("Not counted: 1 in progress."); the tooltip gives each outcome's count | "No finished run yet (Completed, Left or App ended)." |
| Avg 24 m observed | The observed span (entry to the last saved observation, not a verified clear time), averaged over the completed runs that have one | "No completed run yet." or "No completed run has an observed span." |
| Loot 4.0 per completed run | Items in bags linked to the exact run, per completed run, captioned "bags linked to the exact run". A completed run whose session saved bags, none in this run, counts as a known 0. Completed runs whose session saved no loot bag at all are left out and the value is marked partial: "◐ 1 excluded", with the reason in the tooltip | "No loot bag was saved in these completed runs' sessions, so their loot is unknown." |
| Best DPS 436.2 · 13:20 | Your best DPS among completed runs whose recording has your verified local row, with that run's entry time | "No combat recording is linked to these completed runs." or "The local player's row was not verified in these completed runs' recordings; another player's row is never substituted." |

**Show runs** (also Enter, Space or a double-click) opens the Feed with the dungeon filter set to that dungeon. **Open best run**, when a best run is known, opens its recap on that recording. **Analyze** (Analyst) opens the Analysis view filtered to the dungeon. A card's menu (Shift+F10, the context-menu key or a right-click) offers the same actions, and its accessible name reads every fact and reason in words. Back returns to Dungeons.

**Analysis** (Analyst only): a **Cards · Analysis** switch above the tab chooses the view (remembered as `ui.dungeons.view`; switching to Simple returns to the cards). The Analysis view is built the first time it shows and reads saved history only. It holds the Statistics workspace's dungeon views, unchanged: **Dungeon loot profile**, **Session comparison** (the default, over every saved session), **Dungeon statistics** (activity-recorded exits, never summed into visits), **Enemy hit events**, **Loot by source** and **A/B cohorts**, with the usual saved-history search, filters, named views and exports. A one-line banner above it says "The Fame Table and the live loot log stay on the Statistics page." with **Open Statistics**. Its loot numbers use the Statistics views' join (session and visit ID with dungeon agreement), which can differ from the cards' exact-run rule on older histories; neither is adjusted to match the other. See [Statistics](STATISTICS.md#saved-loot-coverage-and-rates) for how those views count.

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

## Live meter: Resources & buffs

**Resources & buffs** is the second tab inside **Runs & DPS › Live meter**, beside Damage meters; a route to it (such as the meter's **Resources** action for a linked run) brings the Live meter tab forward. Its final home is decided in P6. The Damage meters tab retains the existing live/saved encounter controls. Resources & buffs has a separate visit selector. Selecting a gameplay visit does not change the damage encounter. In its saved view, the detail tabs (**Resources & buffs**, **Uptime summary**, **Coverage** and **Selected window**) can be reordered and hidden like other customizable tabs (`ui.tabs.saved-resources`); a restored view never shows a tab you hid.

The chart aligns local HP/MP samples and condition lanes on an elapsed-time axis. Hover for values; Ctrl+mouse wheel zooms the horizontal axis. Scroll to see additional lanes. Search filters condition lanes. Drag the divider to give the chart or visit details more room.

Gray condition lanes show observed coverage; violet intervals show active effects. Blank intervals mean unknown coverage. Inactive effects are meaningful only where their corresponding coverage lane is present. HP and MP share a raw-value scale and are sampled at most once per second. Lines do not bridge gaps longer than two seconds or extend beyond the latest sample.

Uptime summary divides active time by observed time, not total visit duration. Primary and additional condition flags have separate coverage denominators. Data gaps and decoder boundaries invalidate ongoing observations until fresh values arrive.

This version charts the local character. Numeric party roster context is retained, but party-wide buff uptime requires verified links between roster members and observed player objects. It is not inferred from the local character's effects.

## History and bounds

Existing aggregate-only visits remain readable, including uptime summaries. They cannot be reconstructed into time-series charts; new captures supply those samples.

The live journal retains up to 200 visits and 1,000 timeline events. Durable session history receives closed runs and events before live-buffer eviction. Resource points and condition intervals are each capped at 1,000 per visit and 12,000 across the live journal. Adjacent identical condition intervals are merged. The oldest live plot records are removed first; visit details disclose omissions, and aggregate statistics remain available.

Gameplay session history is stored under `%LOCALAPPDATA%\RealmShark\history`. Existing `logs/discovery/activity-history.json` files can be imported without modifying the originals. Clearing Logging diagnostics preserves saved sessions. Reports remain local and follow the field restrictions described in [Discovery logging](LOGGING.md).

Checkpoint snapshots are acquired by the persistence worker. Dirty history is acknowledged only after a successful write, so a failed final checkpoint remains eligible for retry on orderly close. See [Step 2 validation](STEP-2-RESPONSIVENESS.md) for concurrency coverage and synthetic measurements.
