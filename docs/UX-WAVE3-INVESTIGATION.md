# Wave 3 investigation lane (A): RUN-2, TIME-2, COMBAT-3/4/5, INS-2/3, INFO-2

Branch `w3/investigation`, based on wave commit `2637c56` (core foundation 3A.1/3A.2 merged).
Commits: `6de3ccb` (RUN-2, TIME-2), `bd8fffd` (COMBAT-3, COMBAT-4), `129392c` (INS-2, INS-3),
`77d19dc` (COMBAT-5), `6247233` (INFO-2), plus this handoff. Not pushed; no GitHub or
checkpoint changes. Only lane-A files were edited (`gui/activity/**`, `gui/dps/**`,
`gui/myinfo/**`, `gui/security/**` Inspect/roster files and their tests). SecurityGUI and its
Ability Use surface are unchanged.

All seven IDs have an implemented, tested slice. None is claimed closed: each still needs the
coordinator's registrations below, integrated full/UI150/UI200 runs and visual review.

## Behaviour by roadmap ID

### RUN-2 run workbench and exact visit routes
Done when: one selected visit opens exact linked evidence and Back restores the review queue.
- Saved Runs (`ActivityArchiveClient`, mode RUNS without a roster renderer) replaces the
  flat detail with a workbench (`RunWorkbench` model, `RunWorkbenchPanel`) grouped as
  **Outcome**, **Timing / coverage**, **Progression** and **Related evidence**, read from the
  full visit of the displayed pin (origin session Ref + recorded visit ID). Unknowns stay
  unknown (`—`, "not recorded"); Left/unconfirmed is stated as not a failure; a missing visit
  ID is shown as Unlinked. Evidence and actions share one width-tracking scroll column so
  every line stays reachable at 680 px / 24 pt (ActivityArchiveNativeTest).
- Actions: Inspect players, Open Timeline, Timeline around completion evidence (±30 s),
  Open Resources, Open Loot. Each carries `VisitRef(row.ref.session, visit.id)` and is
  enabled only when `Navigator.current().canOpen(route)`; otherwise it is disabled with its
  reason in the tooltip and in a visible "Unavailable here: …" line.
- `ActivityRouteTarget` (RUNS, INSPECT, TIMELINE, RESOURCES) resolves a visit route through
  `ActivityRoutes.visitQuery`: scope All Sessions + the existing exact
  `Filters.visitSession` + `visitId` facet (applied before paging), with review-queue facets,
  text and date bounds cleared. The single match is auto-selected and its selection
  remembered. A reference to an imported/deleted/unsaved session yields zero matches and an
  explicit "Linked visit unavailable … No other visit (including one with the same dungeon
  name) is substituted" state. (Using the session as scope made a missing session a read
  failure; All Sessions + exact facet keeps it an honest unavailable state.) Routes carrying
  a query, record, recording, payload, a non-UUID session, or bounds (except Timeline) are
  rejected. Capture/restore use the workspace `ViewState`, so Back restores the review
  queue's query, page, selection and scroll.

### TIME-2 Timeline around a moment
Done when: a ±30-second route reproduces the same events in the table and export.
- `ActivityRoutes.timelineAround(ref, t, 30 s)` → Timeline route with half-open
  `[t − 30 s, t + 30 s)`; the target applies these as the workspace query bounds (ENTRY,
  unknown times excluded, time ascending, exact visit facet). Displayed pages and every
  toolbar export use this one query/revision.
- The Timeline view shows the window, zone and matching count, a "Widen window ±30 s"
  control (Date bounds… remains), and the linked outcome read from the same pin (exact
  visit-linked Timeline queries now also pin `runs`). Completion evidence stays at
  `completionObservedAt`; if that is after the visit ended it is labelled **observed later**
  with the delay, never moved to an invented completion time.
- Entry points: run workbench (completion evidence), Resources sample (COMBAT-5), and
  window selection (COMBAT-5).

### COMBAT-3 encounter link status and handoffs
Done when: consecutive same-dungeon visits never open each other's evidence.
- `EncounterLink` classifies the displayed encounter from `DpsData.getEncounterContext()`:
  Linked (visit verified at entry; imported files flagged "may not resolve here"),
  Unlinked (context without verified visit), Legacy (no context), Live (not yet saved).
  DPS Logger shows the status and explanation (`dps-encounter-link`) and Open run / Open
  Timeline / Open Resources, enabled only when `link.linked()` and `canOpen(...)`; otherwise
  each explains why. Routes use only the frozen `VisitRef`.
- `DpsGUI.resourcesRouteTarget()` (Resources workspace + tab) and
  `DpsGUI.encounterRouteTarget()` (exact recording ID, optional verified local object ID)
  share a page-7 route state (tab, live/selected encounter, Resources `ViewState`).
- Inspect from a DPS row now names the encounter and its link, and no longer passes the
  menu-open time into the detached snapshot (producer receipt time, 0 for saved files).

### COMBAT-4 event and loadout explorer
Done when: event 1 of 1,200 is findable, an outgoing event's retained weapon-swap snapshot is
correct, and absent incoming-hit victim gear remains unavailable.
- "Explore all events…" beside the meter details opens `DamageEventExplorer` over a detached
  copy of the selected row's retained hits in the current enemy scope (outgoing, or incoming
  when available), numbered chronologically, paged 200 per page (Ctrl+Page Up/Down,
  Go to event #). Filters: half-open time (seconds from first recorded hit), inclusive amount,
  item/source/attacker text, `DamageSource` and counter flags (Oryx guard, Chancellor Dammah,
  Walled Garden reflector). Filters apply before paging; an excluded event is explained.
- Detail: outgoing events show the owner's equipment/enchants retained on that event
  (`ownerInvntory`, `ownerEnchants`) separately from "Last recorded gear … may differ";
  incoming events show the attacker and "Victim … equipment at this hit: Not captured".
  The latest-500 text and damage-by-source breakdown are unchanged.

### COMBAT-5 selected-window resources
Done when: two active seconds in four observed seconds of a ten-second window show 50%
observed uptime and six seconds unknown.
- `CombatTimelineChart` draws raw HP and MP in separate plots with separate scales; a
  half-open timestamp window is selected by dragging, or `[` / `]` at the inspected sample,
  Ctrl+A (whole visit), Escape (clear); endpoints are the public `selection` property.
- `ResourceWindow` computes raw HP/MP extrema from samples in `[from, until)` and, per flag,
  active / observed / unknown time from condition slices clipped to the window and unioned
  per family (primary / extra). Observed uptime = active / observed (null when nothing was
  observed, never 0). Zero-active lanes (recorded flags, or all flags on request) stay listed.
  No percentage of maximum is shown (no per-sample maxima).
- "Selected window" tab in saved and live Resources (`ResourceWindowPanel`): summary, lane
  table, Whole visit / Clear, and Timeline ±30 s around the inspected sample or for the
  window (saved visits only; live views explain that a saved visit is needed).

### INS-2 build provenance
Done when: opening an old build cannot substitute a live namesake or present dialog-open time
as historical capture time.
- Saved Inspect rosters receive `VisitRef(origin.session, visit.id)`; each row's origin reads
  "Recorded run: session … · visit … · map · Last recorded loadout". The live Runs roster
  says "saved session not referenced". Build/change time stays `InspectSnapshot.observedAt()`;
  no new code uses the no-time constructor.

### INS-3 build comparison
Done when: a DPS difference includes both time windows and never attributes the difference to
gear automatically.
- Roster actions "Pin build as comparison baseline" (Ctrl+B) and "Compare with pinned
  baseline…" (Ctrl+Shift+B). `BuildComparison` copies both builds and reports source, build
  time, class (class change flagged: stats/equipment not like-for-like), level, eight base
  stats, four equipment slots (Not captured distinct from Empty), outcome, damage and DPS,
  each DPS over its own recorded window with both windows in the summary, plus the explicit
  statement that DPS differences are not attributed to equipment. Current-area rows have
  no outcome/DPS ("Not recorded"). The pin is EDT-local for the app session (not persisted).

### INFO-2 My Info navigation
Done when: users see the relevant scope/window and cannot mistake a current build estimate for
the historical build.
- My Info page footer: "Historical recorded DPS (not this estimate)" with a recording picker
  (`DpsGUI.recordedEncounters()`, detached), Open recorded local row, and an explanation that
  names the current estimate + scenario separately from the recording's scope, entry time,
  duration and link. Open routes `ENCOUNTER` with recording ID + verified local object ID;
  DPS Logger shows Meters, selects only that row and a notice "Historical recorded DPS …
  not your current-build estimate". Unverified, unlinked or legacy recordings explain why and
  never substitute another row.

## Tests (serialized runner, JDK 17 / Gradle 7.6.4, isolated `build/w3-investigation`)

New classes: `tomato.gui.activity.ActivityRouteTest` (3), `tomato.gui.activity.ResourceWindowTest`
(4), `tomato.gui.dps.DpsInvestigationTest` (4), `tomato.gui.security.InspectComparisonTest` (3),
`tomato.gui.myinfo.RecordedDpsHandoffTest` (1). Changed: `DpsInspectMenuTest` resets the global
DPS `Filter` (it failed when run alone because `Filter.filter` defaults to 1; order-dependent,
pre-existing).

Final run at `6247233` (fresh results directory):
`test --tests 'tomato.gui.activity.*' --tests 'tomato.gui.dps.*' --tests 'tomato.gui.myinfo.*'
--tests 'tomato.gui.security.*' --tests 'tomato.gui.route.*'` → **148 tests, 0 failures, 0
errors, 0 skipped** (includes ActivityArchiveNativeTest 3, InspectArchiveNativeTest 1,
EncounterLibraryNativeTest 1, ParsePanelRefreshTest 14, MyInfoLayoutEvidenceTest 1).

Acceptance fixtures covered: consecutive same-name visits (Runs, Inspect, DPS Open run,
Resources) open only their own visit; deleted-session reference shows unavailable; Back
restores the review queue state exactly (`ViewState` JSON equal); ±30 s window displayed ids
== all-match export ids with half-open boundaries (t−30 000 in, t+30 000 out) and revision
match; late counter completion labelled observed later; event 1 of 1,200 reachable; outgoing
weapon swap 100→200 matches each event with last-recorded gear separate; incoming victim gear
Not captured; 2 s active / 4 s observed / 10 s window → 50%, 6 s unknown; zero-active lane
listed; unobserved window uptime null (not 0).

Not run by this lane: the full suite, `shadowJar`, `testUi150` / `testUi200`, packaging,
native screenshots of the new surfaces. Coordinator gates.

## Coordinator requests

### RouteTarget registrations (TomatoGUI.createWorkspace, EDT, after the navigator is installed
and after the generic ArchiveRouteTargets; unregister in closeWorkspace)
```java
ActivityRouteTarget runs = ActivityRouteTarget.of(Destination.RUNS, runsWorkspace);          // null without saved history
ActivityRouteTarget timeline = ActivityRouteTarget.of(Destination.TIMELINE, timelineWorkspace);
ActivityRouteTarget inspect = ActivityRouteTarget.of(Destination.INSPECT, inspectWorkspace);  // SecurityGUI.workspace(...)
RouteTarget resources = dpsGui.resourcesRouteTarget();                                       // null without saved history
RouteTarget encounter = dpsGui.encounterRouteTarget();
for (RouteTarget t : new RouteTarget[]{runs, timeline, inspect, resources, encounter})
    if (t != null) NavigatorRegistry.register(t);
```
`ActivityRouteTarget` rejects query-only routes, so the generic targets keep handling those.
Constructor for already-typed workspaces: `new ActivityRouteTarget(Destination, ArchiveWorkspace<Row,Filters,Sort>)`.

### Producer hook (requested, not blocking)
- `tomato.backend.data.DpsSnapshot`: add `public final EncounterContext context` copied from
  TomatoData's entry-frozen encounter context in `capture(...)`. Then `DpsGUI.renderData` can
  show a live encounter's link (today live shows "Live … shown once the encounter is saved").

### Scaling allowlist (`scripts/typography-validation.gradle`, testUi150/testUi200)
These are headless component tests of new surfaces; add if the coordinator wants them scaled:
`tomato.gui.activity.ActivityRouteTest`, `tomato.gui.activity.ResourceWindowTest`,
`tomato.gui.dps.DpsInvestigationTest`, `tomato.gui.security.InspectComparisonTest`,
`tomato.gui.myinfo.RecordedDpsHandoffTest`. The workbench is already exercised by the
allowlisted `ActivityArchiveNativeTest` (runs, 680/1240 × 13/24).

## Known gaps and limits
- No native screenshot/visual-evidence tests were added for the event explorer, build
  comparison dialog, Selected window tab or My Info recorded-DPS footer; needs coordinator
  visual review. The Runs workbench appears in the existing Runs native screenshots.
- Live (unsaved) Runs keep the Wave 2 text detail; the workbench and routed actions are on
  saved history (Browse saved). Live Resources window analysis works but Timeline handoffs
  explain that a saved visit is required.
- Open Loot stays disabled until lane B registers a LOOT target accepting exact visit routes.
- Visit routes scan All Sessions with the exact facet (whole saved scope read, then filtered).
- The explorer covers the selected row's hits in the meter's current enemy scope; incoming
  follows the meter's fight-window/full-dungeon rule. Supported flags are the three counter
  flags recorded on `Damage`.
- My Info lists the DPS Logger's in-memory library (captured this run + imports); saved
  `.dps` files not loaded into the library are not listed.
- The INS-3 baseline pin is not persisted and is shared by Inspect rosters in one app run.
- Imported encounters keep their recorded link; destinations show unavailable if its session
  is absent.
