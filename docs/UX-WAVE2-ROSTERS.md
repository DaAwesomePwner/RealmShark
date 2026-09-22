# Wave 2C — task rosters, Inspect requirements and encounter library

- Branch: `work/ux-w2-rosters`
- Base: `64d58d0606a82a956e082fa0dcc07aa18d8fbbfa` (verified Wave 1 merge).

Local implementation milestones:

- `d45d50249c0eaf7cca71a0fc5ea52c2e1d659027` — CHAR-2 queries, numeric deficits, presence-aware definitions.
- `500aa151b8b1bcf05002d7646e6c1903520eeddc` — INS-1 evaluator, explanation UI and independent facets.
- `4313c67c4aa57351d29a346f74cb5e2d131d5ae6` — COMBAT-2 identity-safe encounter library.

This handoff also accompanies final decimal-cap parsing and displayed-timestamp search checks.
The three assigned local feature tasks are implemented. Independent review, native/focus/scaled
UI checks, integrated-wave validation and shared archive adapters are separate coordinator gates.

## CHAR-2: concrete behavior

`CharacterJournalGUI` applies an immutable `CharacterRosterQuery` to the complete retained journal
before Swing sorting. Controls compose account, class, life/season state, Needs Life, base-stat
coverage, missing cap definitions, maxed-count range/unknown and snapshot-update age. Unknown
season and unknown Life need are explicit choices. Reset clears display predicates.

- Account predicates use the full stored account key, not the displayed name or shortened hash.
- Equipment search includes decimal ID, hex ID and resolved name simultaneously. Search is literal.
- Maxed and Potions remaining are typed nullable numeric columns. Totals require all eight valid
  base stats and cap definitions. Zero is a known completed deficit; partial totals remain unknown.
  The retained convention is +5 Life/Mana and +1 other stats, not an inventory-ownership estimate.
- The age predicate explicitly concerns `lastSeen` (snapshot update). Existing alive-observation,
  per-field capture times and manual-death semantics remain visible separately.
- One injected clock instant is used per matching pass. Age-boundary membership changes occur
  without a journal revision. Unchanged age membership does not rebuild tables.
- Selection and note handling use account-qualified character keys; a filtered-out draft cannot
  be saved to another character. Empty journal and no matching characters have different messages.
- Existing `ContentStyle.page`, wrapping controls, nested focus reveal and detail panes are reused.

## Definition presence without modifying asset loaders

`RosterDefinitions` is a read-only, immutable projection of `players.xml` cap attributes and
`equip.xml` tier/slot/label elements from one `AssetCache.root()` generation. It does not replace
or write the shared catalogs. A single daemon reader coalesces generation changes; stale results
are discarded, and views use unknown results while relevant definitions are unavailable.

Presence is derived from actual XML elements/attributes. An omitted cap or tier is null; an
explicit numeric zero remains known. Decimal text with leading zeroes is not treated as octal.
Consumers can inject a fixed definition supplier; tests use synthetic XML rather than installed
assets, guessed positive-value presence, or reflective writes to the new definition model.

## INS-1: requirements and display policy

`SecurityFilter.evaluate(Player, RosterDefinitions)` returns a detached `RequirementResult`:

- **Not evaluated**: no active preset (view state).
- **Pass**: all applicable requirements are evaluable and satisfied.
- **Below requirements**: at least one confirmed failure. Unknown reasons are also retained.
- **Unknown**: no confirmed failure, but necessary capture/definition/preset evidence is missing.

Reasons have a kind, stable code and explanation. The result includes known points, the optional
class threshold and score completeness. Missing points inputs cannot produce a confirmed points
shortfall. Empty required equipment differs from uncaptured equipment. Missing definitions cannot
silently pass. Existing whitelist/blacklist scoring and UT/ST tier exemptions remain explicit.
Invalid presets produce Unknown, and editor load/paste/save validation prevents malformed rules
from silently becoming active drafts.

The roster adds literal search, exact class/guild, captured no-guild versus unknown guild,
seasonal/crucible/unknown, maxed-count range/unknown and verdict facets. A Requirements column and
expandable, keyboard-readable selected-row reasons expose the evaluation. Details are attached
to the displayed detached row and definition snapshot, not a newer producer entity.

Current-area rows carry their actual producer observation time. Recorded rows retain supplied
snapshot time and an explicit recorded/current-area flag. Selection restoration uses scoped
object IDs, not matching player names. Existing visit damage keys are not rewritten by this work.

Bulk copy/export still uses the latest complete current-area or selected-run roster, independently
of display facets. The conservative legacy restriction is now labeled **Only copy below or unknown
requirements**. The compatibility `isUnderReqs` adapter means non-Pass; it does not relabel Unknown
as a confirmed failure. JSON calculation availability uses the same presence-aware definitions.
Opening a historical panel does not replace the live capture owner or live selected preset.

## COMBAT-2: identity-safe local library

`DungeonListGUI` searches and sorts retained captured/imported encounters by dungeon, recorded
start, elapsed duration, contributor count, damage, source filename and local-context availability.
Text search covers dungeon/file/entry/recording IDs and ISO plus displayed local timestamps.
Source and local-context facets are independent of export checks. Live is explicitly non-exportable
regardless of where sorting places it. Counts expose checks hidden by filters.

- **Recording ID**: optional UUID string added to `DpsData`; generated once for new recordings and
  retained by debug-rich/debug-free save copies. The existing UID `8052266513416820004L` is unchanged.
  Old streams retain a null recording ID; no identity is invented from name, time or live context.
- **Entry ID**: separate catalog-local UUID. It owns selection/export checks for the lifetime of
  that DpsGUI source and survives sorting, filtering, captured-history refresh and dialog reopening.
- **Import evidence**: basename, SHA-256 of original file bytes and local import time. Identical
  bytes, including a renamed copy, select the existing imported entry. Different bytes claiming
  the same recording ID create separate entries and an explicit variant notice. Neither payload
  is overwritten or merged. Legacy files are eligible for exact-byte deduplication too.
- **Clear during import**: admission checks the catalog generation atomically; a completed old
  job cannot repopulate a cleared library.
- **View imported encounter** resolves the admitted entry ID. It does not guess a list index or
  implicitly select an unrelated last row. Duplicate import opens the existing matching entry.

Recorded start is labeled as the first captured tick, not a guaranteed map-entry timestamp.
Elapsed is the retained encounter duration, distinct from the meter's selected-hit window.
Summaries count retained outgoing damage on non-player targets, including unattributed damage;
contributors are represented owner object IDs, not a complete roster. Aggregate-only legacy
targets have an explicit fallback, never counted again on top of their raw hits. Local context
is available/partial/unavailable with supporting class/guild facts; current live data is not used
to fill missing historical context.

Catalog registration on existing DpsGUI producer callbacks is cheap; heavy summaries are computed
by the bounded background refresh worker. Imports remain in the library instead of appending on
the EDT to the capture-owned `TomatoData.dpsData` list. Export snapshots selected payloads/options
before background I/O and retains the existing collision-safe `DpsExport` writer.

## Data APIs and thread contracts

| API | Contract |
| --- | --- |
| `RosterDefinitions.parse(Reader, Reader)` | Presence-aware fixture/projection reader; no global catalog writes |
| `RosterDefinitions.current()` | Nonblocking cached generation request; immutable definition object is the calculation revision token |
| `CharacterRosterQuery.Row(record, definitions)` / `matches(...)` | Pure projection/predicate over detached journal records; caller supplies one resolved `now` |
| `SecurityFilter.snapshot()` / `evaluate(player, definitions)` | Detached rule settings and structured evaluation over captured player facts |
| `InspectRosterQuery.matches(...)` | Display predicate over a player, evaluation, nullable maxed count and class label |
| `DpsData.getRecordingId()` | Persisted recording claim; nullable for legacy streams |
| `EncounterImport.read(Path)` | Background file read/summary/fingerprint; no capture or network activity |
| `EncounterCatalog.captured(DpsData[])` | Producer-supplied closed-record array; registry never reads the mutable source list itself |
| `EncounterCatalog.add(import, generation)` | Synchronized duplicate/variant admission; null means obsolete generation |
| `entries`, `find`, `check`, `checkedEntries` | Source-local entry identities and stable checks |
| `EncounterQuery.matches(entry, summary)` | Local-library source/context/literal predicates, reusable before archive paging |
| `DpsGUI.encounters()` | Source-local catalog; shared between library dialog instances |
| `DpsGUI.currentEncounterId()` / `showEncounter(id)` | EDT view APIs; missing ID leaves the current view intact |

`getIndex`/`setIndex` remain compatibility methods over the combined library order. Callers must
not use that index to index `TomatoData.dpsData`: imports no longer belong to the capture-owned
list. Use entry identity for new navigation and selection.

## Remaining foundation adapters

The local features work with existing constructors; no shell/capture integration patch is required.
Shared whole-archive queries and persisted workspace state still need foundation-owner binding:

1. Adapt `CharacterRosterQuery`, `InspectRosterQuery` and `EncounterQuery` to the agreed typed
   query/origin envelope. Apply predicates/sort before archive paging and use matching-unit counts.
2. Preserve session + exact visit/record references and the read revision. Do not upgrade the
   existing page-limited `SecurityGUI.history` loader into an all-archive claim using these local
   roster predicates. The current Inspect facets are explicitly current-area/selected-run scope.
3. Provide detached `InspectSnapshot` rows, recorded times and session-qualified run references
   when binding retained view state. These modules do not infer identity from player names.
4. Retain the local `.dps` library as a distinct source until explicit archive origin metadata
   exists. Recording UUID/file fingerprint does not establish a gameplay visit/session link.

Activity/source capture, shared history/SessionPanel, ContentStyle, asset loaders, shell,
build/scaling configuration, execution ledger and coordinator checkpoint were not edited.

## Verification and evidence

JDK 17, Gradle 7.6.4; main sources retain `--release 8`. Main and all test sources compile.
The final selected run passed **41 tests, zero failures/errors/skips**, with test JVMs forced
headless and the existing in-memory PreferencesFactory/history isolation active.

| Full suite selector | Passed |
| --- | ---: |
| `tomato.backend.data.RosterDefinitionsTest` | 2 |
| `tomato.gui.character.CharacterRosterQueryTest` | 4 |
| `tomato.gui.character.CharacterRosterStateTest` | 2 |
| `tomato.gui.character.CharacterJournalFreshnessRefreshTest` | 2 |
| `tomato.gui.security.RequirementResultTest` | 4 |
| `tomato.gui.security.InspectFacetStateTest` | 2 |
| `tomato.gui.security.InspectEvidenceTest` | 2 |
| `tomato.gui.dps.EncounterCatalogTest` | 5 |
| `tomato.gui.dps.EncounterLibraryStateTest` | 2 |
| `tomato.gui.dps.DungeonListTest` | 3 |
| `tomato.backend.data.DpsDataTest` | 5 |
| `tomato.gui.dps.DpsExportTest` | 4 |
| `tomato.gui.dps.HistoricalDpsFilterTest` | 4 |

Use `test` with a separate `--tests` argument for each selector above, plus:

```text
-PrealmSharkBuildDir=build/w2-rosters
--project-cache-dir build/w2-rosters/project-cache
-I .omc/rosters-headless.gradle
```

The local ignored init script sets `tasks.withType(Test).configureEach { systemProperty
'java.awt.headless', 'true' }`. Toolchain and Gradle user-home paths were the coordinator root's
`.tools` JDK 17 / Gradle 7.6.4 / cached dependencies. Reports:
`build/w2-rosters/test-results/test/TEST-*.xml` and `build/w2-rosters/reports/tests/test/index.html`.

Coverage includes exact account identity, combined task predicates, typed/null deficits, decimal
and hex item search, literal punctuation, missing-versus-zero definitions, clock-only membership
changes, draft-key isolation, all requirement verdicts, same-name/different-object selection,
historical snapshot timestamps, copy-boundary independence, 20 identical-name/time encounters,
duplicate/variant imports, generation rejection, checked-state preservation, legacy serialized
fixtures, and off-EDT I/O with blocked-export selection changes and filename collisions.

Native GUI fixtures were adjusted for explicit synthetic definitions, the additional requirements
column and numeric maxed values; they compile but were not run. No native/focus/scaling tests,
screenshots, packaging, live capture, real deliveries or personal history/settings reads were
performed in this worker lane. Fresh native evidence and independent final-head review remain
required before the coordinator closes these IDs in the wave ledger.
