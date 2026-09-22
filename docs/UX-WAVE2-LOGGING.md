# Wave 2 — Logging evidence workflows

Base: verified main `64d58d0606a82a956e082fa0dcc07aa18d8fbbfa`.
Worker branch: `work/ux-w2-logging`. Scope: LOG-1, LOG-3 and Logging's UX-04 slice.

## Implemented behavior

- **Packets → View retained samples → Open field definition.** The sample action
  opens Event samples with an exact packet facet. A named field-path selector offers
  only retained values with a verified matching decoder catalog path.
- **Stat explorer → View retained samples.** An exact stat-ID facet opens events with
  retained per-object stat evidence. Full details put named primary/secondary changes
  before the raw JSON, alongside the complete UTC timestamp, packet and diagnostic area.
  Initial observations explicitly lack a prior value and do not satisfy Changed values.
- Literal search includes nested stat names/IDs, object IDs and previous/current
  values, plus formatted and raw table values. Users can find stat samples without
  searching JSON. Searches identify **events**, not an exhaustive protocol history.
- Per-tab facet and literal-search state survives tab switches. A contextual drill-down
  clears the destination search and replaces only the destination's facets.
  Removable chips and Reset filters expose all effective predicates. Missing/evicted
  facet values remain selected until explicitly cleared, rather than broadening silently.
- Matching/retained counts identify their unit. Defined packet rows, schema fields
  and discovery topics use **available**, not **retained samples**, as the denominator.
- All six tables have accessible names, stable typed sorting (including empty tables),
  content-specific widths, and Enter-to-full-detail behavior. Copy full detail copies
  complete selected evidence. Packet-plus-field-path keys preserve the third field
  through search, sorting and refresh; event/trace keys include capture identity and
  observed packet count. Refresh preserves unchanged chip and field-selector components.

### Effective facets

| Tab | Facets |
| --- | --- |
| Discovery | Literal search |
| Re-entry trace | Packet, diagnostic area, event outcome |
| Packets | Packet, **latest** outcome, observed only, cumulative issues only |
| Stat explorer | Stat ID (aggregate counters across observed objects) |
| Event samples | Packet, stat ID, retained stat object's ID, diagnostic area, event outcome, changed values |
| Field catalog | Packet; exact field path supplied by sample drill-down |

Stat, object and changed-value predicates must match the **same retained delta**.
The object facet describes `Delta.objectId`, not arbitrary numeric fields in packet
values. Area IDs are diagnostic collector areas, not application session/visit links.
Packet-only controls appear only on Packets. Controls unavailable for a tab do not
silently filter it.

## Export contract

The visible source selector offers **Current fresh diagnostics** and **Displayed
diagnostic revision**. Both export **all retained diagnostics in that one snapshot**.
Display filters are explicitly recorded as context, **not applied to the export**.
Neither option advertises matching-row, archive, loaded-page, or saved-activity export.

1. Capture the chosen source and displayed query/count context on the EDT.
2. Acquire fresh diagnostics on the existing background worker, or reuse the pinned
   displayed snapshot without accessing the live observer at all.
3. Preview the acquired snapshot reference, retained counts/interval, scope and
   displayed filter context. Confirmation exports this already-acquired snapshot,
   even if capture or the visible view subsequently advances.
4. Serialize/write on the worker to a collision-safe `discovery-report-*.json` file.
   Errors leave a retryable status, and successful reports enable Open report folder.

The JSON keeps `observations`, `reentryTrace`, `fieldCatalog` and `opportunities`, and
adds a version-1 `manifest`. Observation counters, retained events, trace and manifest
counts are derived from the same snapshot. `observations.activity` is explicitly null:
the old fresh-only export's activity checkpoint is no longer bundled or described as
full saved history. Saved activity export belongs to the separate history workstream.

Manifest fields include source, snapshot revision/time, capture run ID, scope/coverage,
packet/stat counter row counts, event/stat sample counts, observed frames, retained
UTC endpoints and display context. Display-context counts identify their **own**
snapshot reference, which can differ from the fresh export's reference. The Logging
snapshot reference is `captureRunId@exportedAt` (a detached snapshot acquisition
identity); it is not a fabricated archive revision or application session identifier.

## Verified data availability and limits

- `DiscoveryLog.status` and `Delta` retain object ID, spawn object type (or -1), stat
  ID, prior/current primary and secondary values. `Event` supplies packet, area,
  timestamp, outcome, sampling metadata and observed packet count.
- `DiscoveryLog.diagnosticsSnapshot` is already detached and excludes activity.
  It is used for both refresh and fresh export, so no backend hook was needed.
- `DiscoveryCatalog.fields()` actually contains
  `NEWTICK/status[].stats[].{statTypeNum,statValue,statValueTwo}` and
  `UPDATE/newObjects[].status.stats[].{statTypeNum,statValue,statValueTwo}`.
  Tests verify these paths against the current decoder catalog. Direct retained
  value keys must also match an actual packet/path pair before offering a link.
- Unmapped or combined derived keys without an exact catalog path do not get an
  invented definition link. The UI explains when a sample has no eligible path.
- Event retention, sampling, safe-stat withholding and per-event delta limits still
  apply. Aggregate stat changes need not all have retained event samples. Initial
  observations, missing prior values and unverified stat 114 keep their distinct labels.
  Neither clean samples nor field definitions establish exhaustive protocol facts.

## Integration hooks and ownership

- `LoggingReport.Source`, `LoggingReport.revision(snapshot)` and
  `LoggingReport.manifest(source, snapshot, displayContext)` are public, dependency-free
  adapter points. The manifest is a plain map suitable for a later generic export wrapper.
  The wrapper must preserve the distinction between exported scope and display context.
- `LoggingQuery` is a Logging-local predicate helper; there is no dependency on a
  future full-history query API or `SessionPanel` wrapper.
- Shared `HistoryTables`, `ContentStyle`, `DiscoveryLog`, history models, execution
  ledger, scaling configuration and coordinator checkpoint are not part of this package.

## Validation

Only synthetic, isolated, headless checks are used in this lane. JDK 17 / Gradle 7.6.4,
main `--release 8`, root `.tools` JDK/dependency cache, worker-local
`build/w2-logging` output and `build/w2-logging/cache` project cache. Tests use the
existing in-memory preferences factory and isolated test working/history directories.

Selectors:

```text
tomato.gui.logging.LoggingQueryTest
tomato.gui.logging.LoggingWorkflowTest
tomato.gui.logging.LoggingFormattingTest
tomato.gui.logging.LoggingGuiTest.currentExportAndRefreshBlockedOnObserverDoNotBlockEdtAndFreezeStaysStable
tomato.gui.logging.CollectionEvidenceTest
packets.packetcapture.logger.DiscoveryLogTest
```

Evidence is in `build/w2-logging/reports/tests/test/` and
`build/w2-logging/test-results/test/`. The workflow suite covers correlated nested
stat filters, real Swing actions/visibility/reset, stable third-field selection,
retained keyboard targets, empty/error states, non-native 680×520 table/detail
geometry, frozen A versus fresh B after a diagnostic clear, manifest/source/count
agreement, collision-safe files, retry after export failure, and an EDT heartbeat
while the live observer is locked. Formatting checks retain locale/time-zone and
frozen-refresh coverage. Native/focus/clipboard interaction, scaled UI evidence,
independent final-head review and integrated wave checks remain coordinator gates.

Final bounded validation on 2026-09-22: **31 tests passed, zero failures/errors/skips**
(17 discovery-model, 2 collection/coverage, 3 formatting, 1 existing asynchronous
export, 2 query-model and 6 workflow tests). `shadowJar` passed, and the isolated
`RealmShark-v1.2.3.jar --help` smoke test exited successfully on JDK 17.0.20.1.
The successful test command applies the selectors above to `test` before the
`shadowJar` task, with `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`.

## Bounded UX-02 live-state adoption

Implemented in the primary `feat/ux-wave-2-evidence` checkout, starting at `48f5304`.
The concurrent roster-owner commit `2594929` is retained. This slice uses the real
`ViewStateStore.application()` adapter and the existing asynchronous
`PropertiesManager`/`PreferencesStore` writer; it adds no shared storage framework.

### Actual state contract

- **Independent key:** `ux.archive.logging-live`. Shared envelope version 1; Logging
  payload version 1 (`LoggingViewState.Fields`). The store retains both last-used
  intent and named views under this one module key. Other module keys are untouched.
- **Saved:** active tab; each tab's literal text and applicable facets; sort keys by
  stable column name; complete visible-column order and widths; detail-divider
  location; optional per-tab exact diagnostic selection references. Column reordering
  is available through the table header. Layout values and all queries are validated
  before any restored controls are applied.
- **Not saved:** diagnostic payloads/snapshots, revisions as data sources, frozen
  samples, or runtime collection, disk-saving and sampling controls. Pause is
  deliberately **temporary**. Recreating, loading a named view, or Reset saved resumes
  fresh diagnostics, unpaused. The named-view and pause controls explain this policy.
  Existing explicit frozen-versus-fresh export behavior remains intact within the
  current view's lifetime.
- **Capture-bound facets:** object/area predicates retain `captureRun`. The capture
  chip makes this scope removable together with object/area facets. A saved object ID
  from capture A cannot match a same-numbered object in capture B. Selecting an
  object/area explicitly binds it to the currently displayed capture.
- **Selection:** persisted references contain diagnostic run ID, area and stable row
  key. Event/trace keys also include timestamp, packet identity and observed packet
  count. Restoration is attempted only against the matching populated tab and is
  consumed once; absent, filtered-out, wrong-run or wrong-area references do not
  select replacement rows. Explicit selection cancels any pending reference. A live
  capture change also clears old selection even if rendered values happen to match.
  These references are not world-player, app-session or gameplay-visit identity.
- **Actions:** editable named-view selector plus accessible Save, Load, Delete,
  Reset saved and Retry save buttons. Reset saved clears Logging's saved document
  and names and applies default live controls; Reset filters only resets the active
  query. Short windows scroll the header so table and full-detail areas stay usable.
- **Persistence:** edits coalesce to an EDT capture and the existing background
  preferences writer. Equal intent does not cause repeated disk writes on diagnostic
  refresh. Memory updates survive disk failure; failure status exposes Retry save.
  Retry after recreation explicitly writes the loaded memory state rather than
  treating it as proof of durability. Completion generations and queued-edit checks
  reject obsolete success/failure callbacks; detachment invalidates UI callbacks.
- **Forward compatibility:** unsupported/malformed last state, envelope, or named
  payload blocks automatic and named writes. Working controls stay usable; original
  saved bytes remain until explicit Reset saved. Validation includes all named
  payloads, not just whichever one was last opened.

### State validation evidence

`LoggingViewStateTest` adds six headless cases: in-process recreation with independent
tab text/facets, sort/order/width/divider and exact selection; real preferences-file
restart and named views; pause/runtime-control exclusions; wrong-capture/wrong-area
rejection; future envelope/named-payload preservation and explicit reset; out-of-order
durability completions/detachment; and real disk failure with memory recreation and
successful Retry. Test fixtures inject isolated stores, including legacy Logging
tests, so view-state persistence does not leak between cases.

The state slice's final targeted run passes **37 tests with zero failures/errors/skips**:
the preceding 31 selectors plus `tomato.gui.logging.LoggingViewStateTest`. This includes
unchanged frozen-A/fresh-B export assertions and the headless 680×520 layout check.
JDK 17, Gradle 7.6.4 and main Java 8 targeting were used with
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. Primary-checkout output/cache are isolated
under `build/w2-logging-state` and `build/w2-logging-state/cache`; reports are in
`build/w2-logging-state/reports/tests/test/` and XML in
`build/w2-logging-state/test-results/test/`. Native/focus/scaled and the full integrated
suite are deferred to the coordinator's normal validation pass.
