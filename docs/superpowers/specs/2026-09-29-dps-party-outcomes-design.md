# DPS party outcomes: completed, died, nexused — design

Date: 2026-09-29 · Status: approved design, awaiting spec review

## Goal

For every player on a DPS recording, show whether they **completed** the dungeon, **died**, **nexused**, or whether
that is **unknown**, with the time it happened. Every player keeps their full DPS row; players who did not complete
are marked, never hidden. Surfaces: the DPS Meter (live and saved recordings) and the exports (`.dps` file and the
text view). Runs pages and combat history (`CombatRecord`) are out of scope.

## What the protocol gives us (research)

| Signal | Packet | Who | Notes |
|---|---|---|---|
| Someone died | `NotificationPacket`, effect `PlayerDeath` | everyone in the instance | Name inside `message` (`DeathParser`), gravestone in `pictureType`. No object ID, no timestamp: arrival must be stamped. |
| You died | `DeathPacket` (46) | local only | `killedBy`, fame, stats. Currently unused by DPS. |
| You pressed nexus | `EscapePacket` (105, outgoing) | local only | Definitive local nexus. |
| Someone left your view | `UpdatePacket.drops` | anyone | Same drop for nexus, disconnect, death, exit portal, **and walking out of view range** (they later reappear via `newObjects` with the same object ID). |
| Dungeon cleared | `NotificationPacket`, effect `Victory`; final-boss dialogue (Void, Shatters, Moonlight Village) | instance | Already used by `ActivityJournal` for run completion. |

There is no "X nexused" packet. A nexus is therefore **inferred**: the player's last drop happened before the
dungeon ended, they never reappeared, and there is no death for them.

Existing behaviour replaced: the legacy text view tags a player "Nexus"/"Died" per enemy when they dropped while
that enemy was alive (`DpsToString`, `IconDpsGUI`). It never clears on reappearance, so out-of-view players are
falsely tagged, and it is not shown on the Meter.

## Outcome rules

**End of dungeon** (`endAt`), first available of:
1. Time of the `Victory` notification.
2. Time of recognised final-boss dialogue (shared helper extracted from `ActivityJournal.completionDialogue`).
3. Time of the last drop of a boss enemy (`Entity.isBossMob()`) present in the hit list.

Outcome per player, evaluated in order:
1. **Died**: a death for this player (local `DeathPacket`, or a `PlayerDeath` notice whose name uniquely matches the
   row) arrived at or before `endAt`, or at any time when there is no `endAt`. Shows time and gravestone; the local
   player also shows `killedBy`.
2. **Nexused (local, confirmed)**: the local player sent `EscapePacket` and no `endAt` was observed before it.
   Tooltip says it is confirmed.
3. **Unknown**: there is no `endAt` (the dungeon was not seen to end — typically because you left first; `endAt`
   can only be observed while you are present), or the recording has no timeline (legacy file; Died from
   name-matched notices still applies). A live recording without `endAt` shows these as *pending*.
4. **Nexused (inferred)**: the player's last drop is before `endAt` and was not followed by a reappearance. Shows
   time left and HP% at the drop. Tooltip: "nexus, disconnect, or out of view at the boss kill".
5. **Completed**: present (not dropped, or reappeared) and alive at `endAt`. Leaving or dying after `endAt` stays
   Completed. The local player is always present at `endAt` when it was observed.

Times are shown relative to the encounter start (`dungeonStartTime`), as `m:ss`.

Matching: timeline entries are keyed by object ID. Death notices match by name only when the name is unique among
the recording's rows (the rule `CombatSummaries` already uses); an unmatched notice still counts in the summary's
"died" total but is attached to no row.

## Components

### `PresenceTimeline` (new, `tomato.backend.data`, `Serializable`)
Plain data with defensive copy (`copy()`):
- `players`: object ID → `{ name, classType, firstSeenAt, List<Leave{at, hp, maxHp}>, List<Long> returnedAt }`
- `deaths`: `List<{ at, name, graveIcon }>`
- `localDeath`: `{ at, killedBy }` or null; `localEscapeAt`: Long or null
- `endAt`: Long or null; `endSource`: `VICTORY | DIALOGUE | BOSS_DROP` or null

### Capture (`TomatoData`, packet thread only; times are `timePc`)
- `update()`: player `newObjects` → first seen / returned; player drops → leave with HP from the entity; boss drops
  from the hit list → candidate `endAt` (only when no Victory/dialogue end is set).
- `notification()`: `PlayerDeath` → death entry (in addition to the existing list); `Victory` → `endAt`.
- `text()`: final-boss dialogue → `endAt` if not set by Victory.
- `TomatoPacketCapture`: new cases for `DeathPacket` → `localDeath`, `EscapePacket` → `localEscapeAt`.
- Encounter close (`clear()` and `captureTerminated()`): pass the timeline into
  the new `DpsData`, and start a fresh one — at the same points `deathNotifications` resets today.

### Data (`DpsData`, `DpsSnapshot`)
- `DpsData.presence` — new optional field; `serialVersionUID` unchanged. Old streams read null.
- `getSaveFile()` and `DpsSnapshot` carry `presence.copy()`, so no live objects cross threads.

### `EncounterOutcomes` (new, `tomato.gui.dps`, pure)
`EncounterOutcomes.of(PresenceTimeline presence, List<Entity> players, List<NotificationPacket> legacyDeaths, long start)`
→ per-player `Outcome{ kind, at, hpPercent, graveIcon, killedBy, confirmed, reason }` plus counts
`{ players, completed, nexused, died, unknown, pending }`. Never throws; malformed input yields Unknown with a reason.
`pending` is used for a live recording that has no `endAt` yet.

### Meter (`MeterDpsGUI`)
- New sortable **Outcome** column (hideable via the column chooser): `✓ Completed`, `💀 Died 3:12`,
  `⏏ Nexused 2:40 · 18% HP`, `— Unknown`.
- Non-completers: name muted, status glyph after "(you)"; all numbers unchanged.
- Tooltip with the reason text; Died shows the gravestone (and `killedBy` for you).
- Summary line: `8 players · 5 completed · 2 nexused · 1 died`; live without `endAt`: `… · outcome pending`;
  legacy: `outcomes unavailable (recorded before this feature)`.

### Exports
- `.dps`: carries the timeline through serialization.
- Text view (`DpsToString`): replace the per-enemy "Nexus/Died" tag with the dungeon-level outcome, and add a
  "Party outcome" block (player, class, outcome, time, HP%).
- No CSV export (none exists today; not added).

## Error handling

- Missing name, class, or HP → the outcome still resolves; the missing parts are omitted from the label.
- Duplicate names → no name-based death match for those rows (their outcome falls to the presence rules).
- Legacy recordings → Died via unique-name notices, all others Unknown, with the "outcomes unavailable" summary.

## Testing

JUnit with synthetic packet sequences driven through `TomatoData`, plus pure `EncounterOutcomes` tests:
walk out of view and return → Completed; drop before Victory → Nexused; death notice → Died; drop after Victory →
Completed; no end signal → Unknown/pending; local left early (no end seen) → Unknown; local Escape → confirmed
Nexused; duplicate names; boss-drop fallback when there is no Victory; legacy `.dps` without presence; `.dps`
save/load round trip with presence. Focused meter model and `DpsToString` checks. Locally: `gradlew.bat test
shadowJar` and one launch smoke check of the meter (per the repo's local-validation policy).
