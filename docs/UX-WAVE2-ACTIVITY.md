# Wave 2D Activity, Inspect archive and resource history

Base: `c5d9381`; isolated branch `work/ux-w2-activity`.

## Query and evidence package

`tomato.gui.activity.ActivityQueries` implements the real archive adapter for
Runs, Timeline and Resources & buffs. Runs and Inspect share the dungeon visit
contract; Resources includes all areas. Predicates and raw numeric/date ordering
run before paging (100 visits / 1,000 events). The origin envelope retains the
source session and storage reference. Visit rows omit resource samples, conditions
and roster payloads; `readVisit(lease, row, cancellation)` retrieves the exact full
record from the original pin, matching both source Ref and recorded visit ID.

Visit facets: multi-outcome, multi-evidence-source, inclusive duration limits in
milliseconds, capture issues and timing gaps. Left/unconfirmed remains distinct
from Completed; legacy Completed without evidence is **Not observed**, not a
fabricated victory. Unknown duration never satisfies a numeric range. Bounds are
half-open; entry is the default, observed overlap is explicit. Missing/zero legacy
timestamps are unknown; interval bounds never invent an end.

Timeline facets: exact multi-type selection, Assigned / Unassigned and optional
exact session + visit ID. Assignment means a recorded nonempty visit ID, not a
verified cross-module identity. Matching assigned/unassigned counts cover the
whole query. Human summaries explain entry, equipment, requests, progression,
capture problems and unknown fields before raw JSON. Unknown event kinds remain
searchable and retain their details. No timestamp/map-name joins are performed.

`SelectedRunExport.preview(lease, ref, cancellation)` verifies the selection in the
matching result and counts independent Timeline records from the same pin.
`SelectedRunExport.write(...)` streams one full visit and only its exact
session/visit-linked events to JSON or CSV, preserving each origin. Event dates
and types are not clipped to the visit query. The manifest declares this policy,
query/revision/source cuts, visit/event counts and linkage availability. An absent
visit ID exports no linked events. A lease survives owner closure and source edits.
Outputs exclusively claim collision-safe names; cancellation/failure cleans staging.

The foundation's page/all-match exports contain lightweight visit summaries or
complete event projections. This keeps the table and export population identical
without materializing all rosters/resource timelines in Swing.

## Workspace API and required coordinator registrations

`ActivityArchiveClient(mode, scratch[, visitRenderer])` implements
`ArchiveClient<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort>`.
The optional renderer receives the complete pinned visit and its exact origin Ref,
on the EDT, after a generation-checked background read. It does not own capture.
Each client keeps at most one running detail read and one replaceable pending
selection. Replacing a renderer cancels old work even without native peers.

The following factories are ready to register on the EDT:

```java
// TomatoGUI: replace the corresponding legacy SessionPanel.wrap calls.
runsWorkspace = ActivityPanel.workspace(DiscoveryLog.INSTANCE, ActivityPanel.Mode.RUNS);
JComponent timelineWorkspace = ActivityPanel.workspace(DiscoveryLog.INSTANCE, ActivityPanel.Mode.TIMELINE);
JComponent inspectWorkspace = SecurityGUI.workspace(securityPanel);

// DpsGUI: Resources & buffs only; keep the existing Damage meters component.
JComponent resourcesWorkspace = ActivityPanel.workspace(history, ActivityPanel.Mode.COMBAT);
```

Use these components in the existing slots. The factories return the live component
if `AppHistory.store()` is unavailable. With a store they return an
`ArchiveWorkspace`; Runs, Timeline, Inspect and Resources use independent state
keys `runs`, `timeline`, `inspect`, `combat`. Update the existing Runs history-open
callback's `instanceof SessionPanel` branch to also accept `ArchiveWorkspace` and
call `selectSession(SessionStore.ALL)`. This is compatibility wiring for the
existing action, not a new navigation route. Dispose workspaces with `close()` on
the EDT. Removing their native components also closes the foundation's result.

Injection overloads for synthetic or independently configured callers:

```java
ActivityPanel.workspace(store, liveComponent, mode, scratchDirectory, viewStateStore);
SecurityGUI.workspace(store, liveComponent, scratchDirectory, viewStateStore);
```

Queries, order, page, exact source selection, scroll anchor/offset and column
layouts use `ViewState` / `HistoryTables`. Resources also persists its chart /
uptime / coverage tab and never changes the damage encounter. Header and keyboard
sorting request a global query; no page-local sorter is installed on visit/event
tables. Compact visit columns start with area, entered, seconds, outcome, coverage.
Evidence presets expose technical fields. Date input uses explicit ISO offset
date-times, inclusive from / exclusive until, a display zone, Entry / Overlap and
unknown-time inclusion. Duration input is decimal seconds resolved to exact ms.
Named views and Reset filters use the foundation toolbar.

Inspect's archive wrapper creates `new ParsePanelGUI(false)` and reuses its local
roster facets only for the selected visit. Crossing source sessions explicitly
clears its visit-local row selection, including when visit IDs are equal. Its
capture/live owner is never registered by this saved renderer. `ParsePanelGUI`,
Player, SecurityFilter, TomatoGUI and DpsGUI are not modified by this package.

### Foundation hook request: shared selected export

The module's **Export selected visit + Timeline** action is functional at this
base, including asynchronous count preview, JSON/CSV, cancellation and Open folder.
It acquires the displayed page's lease before scheduling any work. The preview and
write keep that lease through modal dialogs; source changes cannot alter the report.

For the foundation owner, the ready shared-toolbar entry point is:

```java
Path ActivityArchiveClient.writeExport(
    ArchiveResult.Lease<ActivityQueries.Row> held,
    ExportSelection selection, ArchiveExport.Format format,
    Path directory, String base, Cancellation cancellation) throws IOException;
```

Add the equivalent default method to `ArchiveClient`, delegating to
`ArchiveExport.write` with `exportColumns()` for ordinary clients. Have the shared
workspace writer call this hook instead of calling `ArchiveExport.write` directly.
For selected visits the override calls `SelectedRunExport`; page/all matches and
Timeline still use the foundation exporter. Its selected population is exactly one
visit (the table uses single selection), with arbitrary linked-event count.
The shared preview should use `SelectedRunExport.preview(held, ref, cancel)` off
the EDT to show its actual event count and expanded scope, or expose an equivalent
client preview hook. The preview's `description()`, `events` and `revision` are
public; it freezes private selected/full-visit payloads. Both paths use the same
manifest and pinned sources. The caller owns the lease.

**At base `c5d9381` the shared toolbar has no override/preview hook:** its generic
Export selected still exports a summary row. The module-local linked action and
`writeExport` API provide the full report now; routing the shared selected action
through the hook remains required integration work. No shared foundation file was
edited to bypass its owner.

## Actual scope and limits

- Whole saved scope is filtered/sorted before paging. Saved means persisted source
  cuts, not pending asynchronous producer writes. Refresh captures a new revision.
- Source pinning includes Runs and Timeline for visit clients, so linked exports
  never reopen mutable history. Full details read only the selected source session;
  reads can scan that session's source files. No new index or database is introduced.
- Foundation bounds remain: raw records/checkpoints ≤16 MiB, projected rows ≤1 MiB,
  page bytes ≤8 MiB; scratch scales with pinned sources and matching projections.
  One full selected visit is decoded at a time; linked events are counted/streamed
  without building an event list. Export order is visit first, then pinned Timeline
  source order; each event keeps its original timestamp and origin.
- Buff percentages use observed coverage, not visit duration. Old aggregate-only
  visits do not gain reconstructed samples. No selected-window/new-window analysis,
  cross-module router, encounter matching or Wave 3 navigation is added.
- Existing live panels retain their observer callbacks, collection controls,
  pause/frozen behavior and retained-data filters. Their unfiltered displayed-history
  export keeps the legacy State shape and adds a manifest declaring revision,
  captured time, frozen state, filters-not-applied and visit/event counts.
- Legacy four-argument history loaders remain source-compatible. The coordinator
  must register the factories above to adopt global queries; the old loaders are
  not the new archive contract. Roster facet persistence beyond the selected-run
  widget remains with the roster owner; this package persists the visit query/view.

## Bounded validation

Final bounded run: **29 tests passed, zero failures/errors/skips; shadowJar passed**.
JDK 17 / Gradle 7.6.4, main `--release 8`, isolated build
`build/w2-activity`, project cache `build/w2-activity-cache`, primary tools' absolute
JDK/Gradle/user-cache paths. Test preferences use the in-memory factory and injected
temporary files; history and exports are synthetic. No personal history was read.

Selectors under `-I scripts/archive-headless.gradle test`:

- `tomato.gui.activity.ActivityArchiveTest` (8)
- `tomato.gui.activity.ActivityArchiveUiTest` (4)
- `tomato.gui.security.InspectArchiveClientTest` (1)
- `tomato.gui.activity.ActivityFormattingTest` (4)
- `tomato.gui.security.InspectFacetStateTest` (2)
- `tomato.gui.activity.SnapshotRefreshTest` (2)
- `tomato.gui.logging.CollectionEvidenceTest` (2)
- Six headless methods in `ActivityModulesTest`: `runsFilterSavedAndLiveVisitsWhileTimelineKeepsOtherAreas`,
  `hubAndUnresolvedOnlyHistoryShowsDungeonEmptyState`,
  `freezeLatchesDisplayAndExportWhileCaptureContinuesAndResumeCatchesUp`,
  `frozenCombatCanSelectAnotherVisitWithoutReadingNewCapture`,
  `latestCombatSelectionRejectsTheOldVisitWhenItsReadCompletesLate`,
  `frozenVisitNavigationCannotReuseTheOriginalVisitsTokenAfterResume`.

Fixtures cover 240 visits, 2,305 events, global predicates/order and page/all-match
export parity, a 1,007-event linked report, duplicate visit IDs across sessions,
post-pin source mutations, entry/overlap/unknown bounds, lightweight rows with full
leased details, unknown summaries, cancellation and collision-safe outputs.
Component checks prove persisted page/selection/tab/columns/scroll/named views,
independent module state, numeric global keyboard sorting, visible compact outcome,
old-pin resource samples until Refresh, exact roster/session identity and retained
live owner/collection/frozen callbacks. Tests use no native window or focus.

Reports: `build/w2-activity/reports/tests/test` and `test-results/test`.
JAR: `build/w2-activity/libs/RealmShark-v1.2.3.jar`.
Native/scaled/full-wave validation and independent final-head review remain
coordinator gates; these bounded checks do not claim those passes.
