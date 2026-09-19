# Runs, Timeline, and Resources & buffs

Start capture and enable **Record** in any activity module. Record is the same shared collection switch as Collect in Logging. The existing Save logs setting controls automatic persistence across launches. Freeze pauses the selected view; Export history saves a local JSON snapshot.

## Runs

Runs has its own sidebar entry and Alt+R shortcut. It lists observed **dungeon runs** (for example, Ice Citadel and Ocean Trench) with duration, progression increases, item/ability requests, capture issues, and status. Select a run for HP/MP ranges, condition coverage, party context, realm score, and retention information. The count and search apply to dungeon runs; Export history includes all retained dungeon runs and their linked events regardless of search.

Nexus, Vault, Guild Halls, Pet Yard, Bazaar, daily rooms, the Realm overworld, Court of Oryx, tutorials, and known test maps are excluded from Runs. Their visits remain available in Timeline and Resources & buffs. Classification uses exact catalogued names, so content such as Battle for the Nexus still counts as a dungeon.

Area recognition includes a bundled catalog for startup without extracted assets and portal definitions across the extracted game XML, including Lost Halls, Cultist Hideout, The Void, Fungal/Crystal Cavern, Oryx's Sanctuary, Kogbold Steamworks and Moonlight Village. Known internal labels such as `mgm2 Dungeon` resolve to display names (The Trials of Cronus). If the map name is unknown, an exact, unambiguous catalogued display name can identify it. Shared display names such as Mysterious Arena cannot identify a specific dungeon without its internal name. Arbitrary server text is not retained.

Unresolved areas are excluded from Runs and remain labeled **Unrecognized area** in Timeline. Previously saved entries with that label cannot be renamed reliably: the original map name was not retained. Expanded recognition applies to new observations.

An area visit is not a confirmed dungeon clear. Leaving an area does not prove completion. Progress received between visits remains unassigned instead of being credited to an arbitrary dungeon.

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

History retains up to 200 visits and 1,000 timeline events. Resource points and condition intervals are each capped at 1,000 per visit and 12,000 across retained visits. Adjacent identical condition intervals are merged. The oldest plot records are removed first; the visit details disclose omissions, and aggregate statistics remain available.

Gameplay history is stored in `logs/discovery/activity-history.json`. Clearing Logging diagnostics preserves it. Reports remain local and follow the field restrictions described in [Discovery logging](LOGGING.md).
