# Wave 3C — analytics (LOOT-3, STAT-2, STAT-3)

Worker B, branch `w3/analytics`, base `a94240c`. Scope: `src/main/java/tomato/gui/stats/**`
and new tests under `src/test/java/tomato/gui/stats/**`. No coordinator-owned file
(shell, producers, history/archive, routes, style helpers, scaling allowlist) was edited.
This note is a worker handoff. It does not replace the integrated wave gates.

| ID | Commit | State |
| --- | --- | --- |
| LOOT-3 | `8554a7e` | implemented; focused tests pass; route target needs coordinator registration |
| STAT-2 | `8257020` | implemented; saved map association remains "Not recorded" until the producer hook below exists |
| STAT-3 | `55115e2` | implemented; focused tests pass |

## LOOT-3: occurrences and calculation details

**Done when:** an unavailable rate explains why; three items across two eligible
one-minute runs, one with no loot, yield 1.5/run and 90/hour.

- `LootQuery.Facets` gains `variant` (item ID/slots/applied, the existing variant
  key) and `visitSession` + `visitId` (validated together; the session must be a
  session UUID). All three are `null` by default. Gson omits nulls, so saved query
  JSON without them still passes `ArchiveQuery.restore`'s equality check. A test
  covers this. `LootArchiveAdapter.scan` applies both facets before external sort
  and paging.
- For Item occurrences and Recent Drops, the loot pin also includes `runs`. A row's
  `runLinked` is true only when the same session holds a saved run with the same
  visit ID and the same canonical dungeon. Row evidence explains legacy (no visit
  ID) and non-agreeing links. Whole-query counts separate run-linked and unlinked
  occurrences. `Row.visitRef()` returns a `VisitRef` only for verified rows. No
  names or timestamps are used for this.
- In the saved Loot workspace:
  - **Occurrences of selected variant** drills a variant row to its occurrences.
  - **Loot from selected run** sets the exact visit facet.
  - **Open recorded run** calls
    `Navigator.current().open(Route.to(Destination.RUNS).withVisit(ref))`. It is
    enabled only when the row is verified and `canOpen` accepts the route. The
    `loot-run-link-status` label always says why it is unavailable: no visit ID,
    no agreeing run, or Runs navigation not registered.
  - **Dungeon rate calculation** switches to the Dungeon loot profile for that
    exact dungeon.
  - An active drill-down is summarised, with a **Clear drill-down** button.
- `RateCalculation.describe` formats values that `StatisticsArchiveAdapter` already
  computed for the eligible cohort: numerator, eligible runs, runs with no linked
  bags, observed duration, both formulas, imported and unknown-coverage exclusions,
  and unassigned drops. It never reads visible occurrence rows. If a rate is
  unavailable it states the reason: no loot evidence, no eligible runs, unassigned
  bags, or a missing duration. Rate rows now carry `zeroLootRuns` and
  `unassignedBags`. Rate details appear first in the selected-row detail pane.
- `LootRouteTarget` (EDT) accepts:
  - LOOT routes with an exact visit, opening that visit's occurrences scoped to its
    origin session;
  - LOOT or STATISTICS routes carrying a Loot/Statistics query.

  It rejects record, recording and local-object references, and visits whose
  session ID is not a UUID.

## STAT-2: live and saved fame parity

**Done when:** pointer movement and new samples do not clear a pinned interval;
saved histories without map association say Not recorded.

- `GraphPanel` stores the pinned interval as two sample timestamps. Pointer movement
  only moves the hover marker. `setScores`, range changes and gain/total changes
  keep the pin. A pin outside the shown samples is reported ("is not in the shown
  samples"), not shifted. A click without a drag clears the pin, and so does
  `clearData`.
- The graph is focusable:
  - Left, Right, Home and End move the sample cursor.
  - `[` sets the start endpoint and `]` sets the end.
  - Enter sets the start, then the end.
  - Escape clears the pin.
- `inspectionSummary()` holds the text delta: timestamps, signed fame change,
  duration and fame/h, or "rate unavailable". It is also the accessible
  description and fires `GraphPanel.SUMMARY_PROPERTY`.
- `FameSessionViewer` adds range (`saved-fame-range`) and Total/Gain
  (`saved-fame-measure`) controls, a text delta (`saved-fame-delta`) and a map
  association line (`saved-fame-map-association`). With no saved map visits, the
  association line, the Map Fame status and the session info all say
  `Map association: Not recorded`. Fame samples never carry a map. Pins reset only
  when the character changes.
- `FameTrackerGUI` (live) shows the same delta (`fame-graph-delta`). Pins reset only
  when the graphed character changes.

## STAT-3: controlled A/B cohorts

**Done when:** ten runs versus two show both totals and per-run rates; a zero
baseline has no invented percentage change.

- The new `View.COHORTS` ("A/B cohorts") appears in the saved Statistics workspace
  only. `Facets.baseline` and `Facets.candidate` are `Cohort` values: chosen
  sessions (empty means all sessions in scope) plus optional half-open visit-entry
  bounds. `Facets.outcome` is Any, Completed or Not completed. All three default to
  `null`.
- `CohortArchiveAdapter` applies the same shared predicates to both cohorts:
  - exact dungeons;
  - outcome;
  - query date bounds;
  - coverage, using `StatisticsArchiveAdapter` eligibility through the reused
    `HistoricalStatistics.Profile` and `addVisit`/`profile`. Imports and sessions
    without loot evidence are excluded, zero-loot runs stay in the denominator, and
    unassigned bags invalidate rates.
- For each cohort it emits totals, per-run and per-hour rates, and min/median/max
  items per run. It also emits per-run distribution rows (0–9 and 10+ items).
  Distributions are unavailable, with a reason, when rates are.
- A delta row gives absolute differences in items, runs, duration and both rates.
  It gives percentage changes only from a positive, available baseline. Otherwise
  the value is `null` and the evidence says, for example, "baseline is zero (the
  change is not 0% and not infinite)". Runs that match both cohorts are disclosed.
- `CohortControls` has two keyboard-accessible session lists (html disabled), ISO
  entry bounds, shared dungeon and outcome fields, **Compare cohorts**, and an
  inline error. Apply submits query intent; it never filters a loaded page.

## Validation

Every run used the serialized runner (JDK 17, Gradle 7.6.4, `build/w3-analytics`,
machine lock), with the Gradle test task's in-memory preferences and synthetic temp
stores. Nothing used live capture or bridge delivery.

| Selector | Result |
| --- | --- |
| Baseline before changes: `tomato.gui.stats.*` | 98 tests, 0 failures/errors/skips |
| `tomato.gui.stats.LootDrillDownTest` | 5 passed |
| `tomato.gui.stats.FameGraphPinTest` | 4 passed |
| `tomato.gui.stats.CohortComparisonTest` | 4 passed |
| Final `tomato.gui.stats.*` (includes `session.*` and the 13 new tests) | **111 tests, 0 failures/errors/skips** |
| Adjacent: `tomato.BrandPresentationTest`, `tomato.gui.chat.ShellHookIntegrationTest`, `tomato.gui.history.*` | 33 tests, 0 failures/errors/skips |

Invocation (PowerShell, from the worktree root): `& <scratchpad>\w3-gradle.ps1
-Name analytics -GradleArgs @('test','--tests','tomato.gui.stats.*')`. The
documented `pwsh -File … -- test …` form fails to bind parameters (`--` is parsed as
an empty parameter name); that failure ran no tests.

Not run: the full suite, `testUi150`/`testUi200`, `shadowJar`, native/compact
screenshots and visual review. No screenshots were produced. Those gates belong to
the coordinator.

## Requests for the coordinator

1. **Route registration** (EDT, after the workspaces are created in `TomatoGUI`):
   ```java
   navigator.register(LootRouteTarget.forWorkspace(Destination.LOOT, lootWorkspace, lootWorkspace::restore));
   navigator.register(LootRouteTarget.forWorkspace(Destination.STATISTICS, statisticsWorkspace, statisticsWorkspace::restore));
   ```
   The third argument is the planned atomic `ArchiveWorkspace.restore(ViewState)`.
   Opening a route uses `ArchiveWorkspace.changeQuery`.
2. **RUNS target:** the loot drill-down emits `Route.to(Destination.RUNS).withVisit(new VisitRef(originSession, visitId))`,
   so the Runs target must accept exactly that route.
3. **Scaling allowlist** (`scripts/typography-validation.gradle`) for the new UI
   component checks:
   - `tomato.gui.stats.LootDrillDownTest.workspaceDrillsVariantToOccurrenceToVerifiedRunAndExplainsUnavailableNavigation`
   - `tomato.gui.stats.CohortComparisonTest.statisticsWorkspaceEditsCohortsThroughQueryIntent`
   - `tomato.gui.stats.FameGraphPinTest.savedViewerHasRangeGainControlsKeepsPinsAndSaysMapNotRecorded`
   - `tomato.gui.stats.FameGraphPinTest.liveTrackerShowsTheSameTextDelta`
4. **Optional fame map association (producer hook in `AppHistory`, not implemented here).**
   Add nullable fields to `AppHistory.FameSample`:
   `public String visitSession, visitId, map;`, with a constructor overload
   `FameSample(int character, double fame, long time, String className, VisitRef visit, String map)`.
   Populate them only from the exact current visit reference captured at the
   sample's producer time (the Wave 3 `VisitRef`/`EncounterContext`), and leave
   them `null` whenever no exact visit is active. Gson omits nulls, so legacy
   journals are unchanged. Once this lands, worker B can map these fields in
   `LootArchiveClient.readFame` and `StatisticsArchiveAdapter.fame`. Until then,
   every archive fame graph says "Not recorded".

## Known gaps

- Rate drill-down is per dungeon. There is no item-variant numerator over the
  dungeon cohort; analytical cohorts still ignore item facets by design.
- A/B cohorts with no dungeon predicate combine dungeons into one denominator. The
  view text recommends an exact dungeon. Unlinked drops count as unassigned only on
  dungeon maps.
- The distribution uses fixed integer buckets (0–9, 10+), not a chart.
- Headless component tests only. Compact 680×520, keyboard-focus traversal at
  150%/200%, and screen-reader inspection are unverified.

## Round 2: fame sample visit association (STAT-2)

Base: wave head `e8657c7`, which contains the core lane's `AppHistory.FameSample`
fields `visitSession`, `visitId` and `map` and the `visit()` accessor.

- `LootArchiveClient.readFame` copies each journal sample's exact visit into
  `FameSession.SampleVisit`. It does this only for samples that carry a `visit()`
  and whose timestamp survives as a graph point. `FameSession.sampleVisits` is
  `null` unless at least one sample has a visit, so legacy `.fame` JSON is
  unchanged.
- `StatisticsArchiveAdapter.fame` counts associated samples and their maps (up to
  10 listed). Row evidence reads either "Map association: N sample(s) with a
  recorded visit (…); M sample(s) Not recorded", or "Map association: Not
  recorded" when no sample has a visit. Legacy snapshot and journal samples count
  as Not recorded.
- `FameSessionViewer` reports "N of M samples with a recorded visit (maps); K Not
  recorded". Tracker map visits are listed separately.
  - It lists the distinct recorded runs (`saved-fame-recorded-runs`).
  - **Open recorded run** (`saved-fame-open-run`) calls
    `Navigator.current().open(Route.to(RUNS).withVisit(visit))` and is enabled only
    when `canOpen` accepts the route.
  - `saved-fame-run-status` explains when it is unavailable. The RUNS target is
    still pending in lane A.
  - A legacy-only character still shows `Map association: Not recorded`.
- Tests: `tomato.gui.stats.FameVisitAssociationTest` passed 2/2. It uses mixed
  legacy and new samples, legacy JSON without the fields, and both an absent and a
  fake navigator. Final run of `tomato.gui.stats.*` plus
  `tomato.BrandPresentationTest`: **117 tests, 0 failures/errors/skips**. The full
  suite and the scaled runs were not run.
- Scaling allowlist candidate:
  `tomato.gui.stats.FameVisitAssociationTest.viewerShowsRecordedRunsAndGatesOpeningOnTheNavigator`.
