# Wave 2D — queried Loot and Statistics

Worker: `work/ux-w2-loot`, base `c5d9381`. Scope: LOOT-1 deep search, LOOT-2,
and the Loot/Statistics adoption of UX-01, UX-02 state, UX-04 and UX-07.
The implementation uses the actual [Wave 2 foundation](UX-WAVE2-FOUNDATION.md).
Worker code and focused headless validation are complete; shell registration,
independent final-head review and serialized desktop/scaling gates remain.

## Coordinator factory hooks

Replace the two legacy `SessionPanel.wrap` registrations in `TomatoGUI` with
these calls, on the EDT, retaining the already-created live components:

```java
HistoricalStatistics.statisticsWorkspace(
    store, statistics, scratch.resolve("statistics"), ViewStateStore.application());

HistoricalStatistics.lootWorkspace(
    store, statistics.getLootDashboard(), scratch.resolve("loot"),
    ViewStateStore.application());
```

Both return `ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort>`.
`store` is the application's initialized `SessionStore`; `scratch` is an
application-owned scratch directory. The workspace keys are **statistics** and
**loot**, so scopes, queries and named views stay independent. Close permanently
disposed workspaces on the EDT. Ordinary hiding/paging follows foundation
lease/cancellation behavior.

Factories also bind live presentation state. Separate keys cover overall
Statistics tabs, live fame graph/table controls, the Statistics loot mirror and
the independent Loot workspace. This package does not edit shell registrations,
foundation code, Activity, capture producers, scaling configuration or ledgers.
The legacy loader methods remain available for compatible callers; those methods
do not expose the new archive query surface.

## Saved Loot behavior

- **Item occurrences** flattens every saved bag before predicate evaluation.
  `source.child("item-" + ordinal, row)` preserves session/module/storage locator
  and position within the saved item list. Identical items in one bag are distinct.
  The ordinal is not a reconstructed inventory slot.
- All Items, Stat Potions, Whites, By Bag, Recent Drops, By Dungeon, UTs, STs and
  Tiered remain available. Saved Recent Drops is a globally paged bag query, with
  no 1,000-bag recency cutoff. Live Recent Drops retains its explicit 1,000-bag cap.
- Multi-select bag, dungeon, rarity and tier sets compose with item category and
  numeric minimum/maximum unlocked-slot/applied-enchant counts. Each numeric
  range has Include / Exclude / Only unknown policies. Unknown is never zero.
  UT equipment category and UTs tab retain the existing wearable-only semantics;
  raw tier labels remain separately searchable/selectable.
- Literal text searches item ID/name, bag, dungeon, dropper, tier and rarity across
  all occurrences, before grouping. Bag summaries count each qualifying bag once;
  duplicate matching items increase occurrence counts independently.
- Whole-query counts separately identify matching occurrences, variants and bags,
  plus broader saved-scope counts. Explicit empty bags can match a bag-compatible
  query without creating an item occurrence. Per-variant bag counts may overlap.
- Variant groups use item ID + unlocked slots + applied count. Their time/dungeon
  describe the latest matching observation. Summary references use explicit group
  locators in the selected scope, not invented occurrence origins. Multi-session
  groups leave single-session/visit fields empty; provenance is in the manifest.

`LootQuery` owns the DTOs, modes and comparators; `LootArchiveAdapter` streams
sources and performs bounded associative reductions. The foundation then sorts
the complete projection externally and pages it. No saved table has a local row
sorter. Header and keyboard sorting submit new typed query intent.

## Statistics populations and compatibility

`StatisticsArchiveAdapter` pins the necessary Runs, Loot, fame and counter sources.

| View | Text semantics | Population / time semantics |
| --- | --- | --- |
| Dungeon loot profile | Dungeon name | Dungeon selection and visit entry/observed-overlap bounds; whole captured visit loot |
| Session comparison | Session label, build, ID | Observed visits, items and fame in the stated analytical scope |
| Character fame | Session, character ID, class | First/last captured samples inside resolved date bounds; optional exact character |
| Dungeon statistics | Dungeon name | Undated cumulative activity counters |
| Enemy hit events | Dungeon, enemy name/ID | Undated enemy summaries; exact enemy/dungeon filters |
| Loot by source | Dungeon, enemy/item name and ID | Undated item/source summaries |

Analytical views visibly state that item/bag/enchant facets do **not** filter their
cohort. This prevents an occurrence filter from silently changing a loot-rate
denominator. Session visit totals remain observed visits, while rate views use
eligible visits. Fame has no invented map association: dungeon/item facets do not
filter its samples. Session comparisons do not expose misleading loot rates.

Wave 1 rules are retained:

- Eligibility requires saved bag evidence in the **same pinned session**, checked
  before time/item filtering; even an empty bag is evidence of partial observation.
- Unknown-coverage sessions and run-only imports remain separately excluded.
  Eligible zero-loot visits remain in the denominator; missing positive duration
  invalidates hourly rates and unassigned drops invalidate rates.
- Visit bounds select whole visits. A linked bag outside the date bounds still
  belongs to its selected visit; bags with no agreeing session/visit/dungeon join
  use their own timestamps and remain unassigned. No name/time-only join is made.
- Undated counters reject custom periods explicitly. Hit events remain hit events;
  activity-recorded exits/finalized time, average time per exit and optional ongoing contribution are
  preserved, with old missing ongoing metadata shown as Not captured.

**Open selected session's full fame graph** is retained for session/character
rows. It reads the same pin through a lease on a worker, then opens the existing
viewer. It includes available legacy map data, without inventing missing joins.
Legacy snapshots, fame journals and latest checkpoints are combined; equal-time
dated samples prefer latest checkpoint, then journal, then legacy snapshot. Undated
observations remain separate and are never plotted at epoch zero. A single pinned
snapshot retains its original name, creation/modification dates and description;
synthesized graphs label projection generation separately from historical dates. The graph
is explicitly the full pinned session, rather than the summary's filtered period.
Saved fame control parity remains Wave 3 work.

## Dates, state and exports

Date controls accept ISO offset timestamps or unambiguous local timestamps in the
specified `ZoneId`. Ambiguous/nonexistent local times require an explicit offset.
Bounds are resolved once and persisted as epoch milliseconds with zone and
`[from, until)` semantics. Nonpositive legacy timestamps are treated as unknown;
the query's unknown-time policy is explicit. Paging never re-anchors time.

`ArchiveClient.Binding` handles every saved inner filter/tab/sort change.
`ViewState` retains active view, query, selection refs, scroll anchor/offset and
per-view column layouts. Compact/all-column presets, visibility, reset, copy and
details use `HistoryTables`. Foundation named views persist the complete state.
Numeric values remain typed; comparators order nulls last ascending and first
descending. Full values and scope explanations are available in the detail pane.

CSV exports have explicit analytical columns (IDs, timestamps, counts, rates,
fame, provenance and coverage). JSON retains structured rows and origin refs.
Selected/page/all export choices use the displayed result's lease and manifest;
appending loot or replacing fame checkpoints cannot change an in-progress export.
Published asset-generation changes invalidate a scan before a mixed result can
be applied; normalized labels and generation description are frozen in the result.

Live Loot uses the same item predicates, with exact matching bag counts from
composition aggregates. Literal search uses explicit item ID/name, dungeon, bag,
dropper, tier and rarity fields rather than decorated item descriptions. Tabs,
multi-facets, search, ordering, selection, scroll
and columns persist separately from archive intent. Existing fame graph range,
measure and delayed character selection, fame table filters/layout and overall
Statistics tab state also persist. These live selection keys are local UI keys,
not cross-module visit identities. Save failures retain active controls, show a
failure and offer Retry view save; unreadable state requires explicit reset.

## Concrete memory limits

- Occurrence rows use the foundation's external sort/chunk/page/lease pipeline;
  all matching occurrence objects are never retained in a Swing model or reducer.
- Variant counters, facet catalog, summary/character maps: at most **25,000 keys
  per map**. The broader facet catalog covers the selected scope before filters.
  Exceeding a bound fails the entire query with an actionable message, not a
  successful truncated count. Narrow scope; date/facets also reduce matching groups.
- Visit linkage: at most **100,000 compact session-qualified visit keys**.
- Labels retained by archive reducers: at most **512 characters**; a saved bag
  supports at most **1,024 item positions**. Oversized/malformed records fail
  explicitly. Foundation 16 MiB raw-record and 1 MiB projected-row limits also apply.
- Opening one full fame graph: at most **100,000 retained observations** (distinct
  positive character/time samples plus separately retained undated observations).
  An oversized graph reports unavailable; summary queries remain available.
- Live filtered bag accounting: **25,000 distinct bag compositions**. If exceeded,
  affected filtered bag counts show unavailable with an explanation; complete
  item totals and unfiltered bag totals remain intact. Saved queries provide exact
  bag accounting without retaining compositions. No capture records are deleted.

## Primary review corrections — starting head `7f50b99`

Three independently confirmed module blockers were corrected in the primary
`feat/ux-wave-2-evidence` checkout:

1. **Literal live search.** The generated ` [rarity, slots, enchants]` display suffix
   is no longer searchable. Bag-composition counts, item totals and recent rows use
   one predicate over the same explicit fields as the archive. Live accumulators
   retain raw name/classification/dropper context so two observations of the same
   item variant cannot borrow each other's matching text. A real bracket in a name
   or dropper still matches literally.
2. **Fame chronology.** `FameSession.Chronology` separates positive-timestamp samples
   from undated observations. Character rows retain known endpoints when available,
   but included undated observations make gain and elapsed interval unavailable.
   Any such character also makes the combined session gain unavailable. Undated
   counts appear in row evidence, whole-query counts and exports. One dated timestamp
   alone has a labelled same-sample zero delta, not measured session growth; its
   elapsed interval is unavailable. Two dated equal values still establish zero
   change over their measured sample span. Finite date queries may explicitly
   exclude unknown times before this calculation.
3. **Graph provenance.** Graphs plot only dated samples and disclose undated counts.
   A single snapshot is returned from its pin with stored dates/description/name
   preserved; missing dates stay unknown rather than inheriting constructor time.
   Multi-source graphs have unknown historical created/modified dates and separate
   `ArchiveProvenance` containing projection generation time, revision, source session
   and source-record count. The viewer distinguishes these labels. Ordinary manual
   sessions retain their existing constructors, metadata and raw sample round-trip.

### Correction validation

**50 focused tests passed** using JDK 17/JUnit 4.13.2, forced headless, isolated
history/preferences and worker-owned output. Main sources compiled successfully
through Gradle 7.6.4 with the existing Java 8 target. At this validation point the
normal Gradle test compile was blocked by concurrent social work:
`ChatVisibilityStateTest` attempted to subclass final `ChatExplorer`.
That failed compile is not a test pass. The reporting test closure was instead
compiled directly with JDK 17 `javac --release 17` against the successful main
output and cached project dependencies, then executed with `JUnitCore`:

| Test class (`tomato.gui.stats.` prefix) | Count |
| --- | ---: |
| `ReportingReviewFixTest` | 7 |
| `LootArchiveQueryTest` | 11 |
| `LootArchiveStateTest` | 6 |
| `ReportingStatisticsTest` | 14 |
| `session.FameSessionPersistenceTest` | 4 |
| `session.FameFormatExportTest` | 1 |
| `FameAutosaveStateTest` | 7 |

New source cases verify:
- `literalBracketAndRawDropperSearchAgreeAcrossLiveBagsItemsAndArchive`: PlainSword
  does not match `[`, genuine raw brackets do, and bag/item counts agree with archive.
- `undatedHundredAndDatedOneFiftyNeverProduceEpochEndpointsOrFiftyGain`: known
  endpoint 150 at its recorded timestamp, null gain/span in rows and CSV/JSON,
  null session gain, and only the dated point sent to the graph.
- `undatedOnlyFlatDatedAndSingleDatedObservationsRemainDistinct` and
  `finiteBoundsCanExcludeUndatedSamplesWithoutInventingTheirOrder`: unknown versus
  confirmed zero, multiple undated values retained, and explicit bounds behavior.
- `singleImportedSnapshotRetainsMetadataAndUndatedEvidenceFromItsPin`: stored
  creation/modification/description survive a post-pin source-file replacement.
- `synthesizedGraphHasGenerationProvenanceAndNoInventedHistoricalDates` and
  `absentSnapshotDatesAreUnknownAndOrdinaryManualSessionsKeepTheirMetadataContract`:
  generation versus historical metadata, missing dates, and manual compatibility.

Output: `build/ux-w2-report-review/`; project cache:
`build/ux-w2-report-review-cache/`; directly compiled test closure:
`build/ux-w2-report-review/focused-tests/`. The direct run used the in-memory
PreferencesFactory and `focused-history/` under the same output. Console evidence:
`OK (50 tests)`. No native viewer, GUI/focus/scaling run, capture or real bridge
delivery was performed. Integrated Gradle/native gates remain coordinator-owned.

## Original worker focused validation

Original worker source: **43 tests passed, 0 failures/errors/skips**, forced headless,
JDK 17.0.20.1 / Gradle 7.6.4, main Java 8 API/bytecode targeting. Test history,
preferences, output and scratch are synthetic and isolated. No capture, bridge
delivery, native window, focus, screenshot or scaled validation was run.

| Selector (`tomato.gui.stats.` prefix) | Count |
| --- | ---: |
| `LootArchiveQueryTest` | 11 |
| `LootArchiveStateTest` | 6 |
| `ReportingStatisticsTest` | 14 |
| `LootEquipmentTest.categoriesUseExactLabelsAndInclusiveTierThresholds` | 1 |
| `FameAutosaveStateTest` | 7 |
| `FameTrackingStateTest.hiddenLongBurstRetainsHistoryButNoTableSampleSeriesOrPresentationCopies` | 1 |
| `FameTrackingStateTest.orderedCharactersAndMapBoundariesKeepZeroNegativeAndReturnVisits` | 1 |
| `FameTrackingStateTest.mutableMapInputsAndPublicSnapshotsNeverAliasTrackingState` | 1 |
| `StatisticsExplorerTest.dungeonFiltersPreserveLootOnlySourcesAndNumericSorting` | 1 |

Key cases cover 2,210 multisession bags / 2,211 occurrences, a rare duplicate pair
outside the newest 1,000, global sorted paging, stable child refs after appends,
CSV/JSON pinned counts, whole-query grouping, unknown versus zero, UT consumable
exclusions, DST bounds, explicit memory failures, imported/unknown/zero-loot rate
cohorts, pinned mixed legacy/current fame, independent named workspace state,
live multi-facets, bounded bag accounting, arrival-safe unapplied facet drafts and failed-save retry. A final source
inspection found nullable-time unboxing in grouped unknown-time bags; its new
regression also checks counter average time, non-applicable metrics staying null,
and actual eligible-cohort damage rather than an invented zero.

Worker-relative output: `build/loot/test-results/test/` and
`build/loot/reports/tests/test/`; unique project cache: `build/project-cache/`.
The invocation supplies absolute `realmSharkBuildDir`/project-cache paths, with
absolute JDK and Gradle user-home paths into the primary checkout's `.tools`.
The first compile caught a Swing `Component.bounds()` name collision; it was
renamed before successful validation. Earlier passes are superseded by the final
43-test run.

Coordinator remaining hooks: register the two factories above, consider the new
component suites in the shared scaling policy, independently review the integrated
head, then serialize native/compact/keyboard, full/scaled tests and packaging.
