# Wave 3 core foundation (3A.1 capture identity, 3A.2 routing shell)

Branch `w3/core`, based on `a94240c`. Commits: `f6ded14` (3A.1), `49cc700` (3A.2), plus this
handoff. Not pushed; no GitHub or checkpoint changes. Coordinator-owned files only.

## Implemented behaviour by roadmap ID

### COMBAT-3 (producer slice): encounter-to-visit identity captured before reset
- `DiscoveryLog.visitForMap(MapInfoPacket)` returns a `VisitRef` only when that exact packet
  object (identity comparison) was observed clean, started a new `ActivityJournal` visit, that
  visit is still the active one, collection is enabled and a history session is attached. The
  provenance is ephemeral (object reference plus journal visit ID, never bytes or fields) and
  is forgotten on any later MAPINFO observation, failed MAPINFO (`decodeFailure`), trailing-byte
  MAPINFO, connection `boundary()`, pause/resume, `clear()` and `close()`.
- `VisitRef.sessionId` is the attached `SessionStore.currentId()` (recorded in
  `attachHistory`), `visitId` is `ActivityJournal.Visit.id`; the two stay separate.
- `TomatoData.setNewRealm` order: `clear()` finishes the outgoing encounter with its
  entry-frozen reference, then the incoming map's reference is captured via
  `visitForMap`. `clear()` computes the local object ID first, before identity fields reset,
  and only when `player.id == worldPlayerId`, `player.isUser()` and no second, different local
  object (CREATE) appeared during the encounter; otherwise it is null.
- `DpsData` keeps `serialVersionUID = 8052266513416820004L`. The optional context is stored
  as JDK-typed fields (`String`/`Integer`/`Long`), so older app versions can still read new
  files, and is copied by `getSaveFile(...)` for both debug settings. Old streams have no
  context: `getEncounterContext()` returns null (no manufactured visit or local identity).
  New recordings always have a context; its `visit` is null when unverified.
- `EncounterContext.capturedAt` is the producer wall-clock time at `setNewRealm` (encounter
  entry); for a map set without `setNewRealm` it falls back to the finish time.

### UX-02 (routing slice): typed routes, visible Back, atomic restore
- `ShellNavigator` implements `Navigator`: registry (newest registration first; a target that
  declines or throws in `accepts` is skipped), rejection with no state change when nothing
  accepts, origin captured (`page` + `captureState()` of the target showing on that page)
  before navigating, bounded Back stack (default 20, oldest dropped). The destination applies
  `open(route)` before its page is shown; a throwing `open` returns false and changes nothing
  in the navigator. Dialog destinations (`ALERT_DRAFT`, page `NO_PAGE`) open without a Back
  entry. Back restores the target state, then shows the origin page. Sidebar selection is
  untouched, so module scopes stay independent. All methods are EDT-only.
- `WorkspaceShell`: `pageOf(Destination)`, `createNavigator()`, and a `navigate-back` button
  labelled "Back to <page>" (same accessible name, tooltip naming Alt+Left) that is visible
  only while an origin exists; Alt+Left (`WHEN_IN_FOCUSED_WINDOW`) invokes it and focus moves
  to the restored page's navigation button.
- `TomatoGUI.createWorkspace` installs the navigator and registers generic query-only
  `ArchiveRouteTarget`s for the typed Runs, Statistics and Loot workspaces (when they are
  `ArchiveWorkspace`s). `closeWorkspace` uninstalls it only if it is still current.
- `ArchiveWorkspace.restore(ViewState)` (EDT only): validates that the state's query has this
  workspace's exact facet/sort types (`IllegalArgumentException`/`JsonParseException`
  otherwise, no change), applies scope/query/mode/page/selection/anchor/layouts in one step,
  persists, invalidates in-flight reads (`SnapshotRefresh.invalidate` + cancellation), and
  starts one load (reusing the pinned result when the query is unchanged). The show handler no
  longer restarts a load that is already running, so restoring before showing a page issues
  one read, not two.

### UX-04 / UX-05
No 3A.1/3A.2 change. Route-to-export scope agreement (UX-04) and recording-interval coverage
(UX-05, LOG-2 coverage persistence) remain for later coordinator packages.

## Public APIs for other lanes

```java
// Encounter identity (tomato.backend.data.DpsData)
public EncounterContext getEncounterContext();   // null = legacy; fresh detached value
// EncounterContext: VisitRef visit (nullable), Integer localPlayerObjectId (nullable), long capturedAt, boolean linked()

// Producer (packets.packetcapture.logger.DiscoveryLog) – coordinator-owned, rarely needed by lanes
public synchronized VisitRef visitForMap(MapInfoPacket packet);

// Navigation (tomato.gui.route)
Navigator.current().open(Route.to(Destination.RUNS).withVisit(ref));   // false = rejected, nothing changed
Navigator.current().canOpen(route);                                     // enable/explain an action
public static boolean NavigatorRegistry.register(RouteTarget target);   // false when no shell navigator installed
public static boolean NavigatorRegistry.unregister(RouteTarget target);
public final class ShellNavigator implements Navigator {
    public static final int DEFAULT_CAPACITY = 20, NO_PAGE = -1;
    public ShellNavigator(IntSupplier selected, IntConsumer select, ToIntFunction<Destination> pageOf, int capacity);
    public void register(RouteTarget target); public boolean unregister(RouteTarget target);
    public void addChangeListener(Runnable listener); public int backPage(); public int depth();
}
public final class ArchiveRouteTarget<R,F,S extends Enum<S>> implements RouteTarget {
    public ArchiveRouteTarget(Destination destination, ArchiveWorkspace<R,F,S> workspace);
}
// tomato.gui.history.ArchiveWorkspace
public void restore(ViewState<F,S> value);
// tomato.gui.modern.WorkspaceShell
public static int pageOf(Destination destination); public ShellNavigator createNavigator();
```

Destination pages: INSPECT 2, STATISTICS 4, MY_INFO 6, ENCOUNTER/RESOURCES 7 (DPS Logger),
LOOT 8, LOGGING 9, RUNS 10, TIMELINE 11, BRIDGE_REVIEW 12, NOTIFICATIONS 13, ALERT_DRAFT none.

Target contract reminders: `open`/`restoreState` run before the page is shown; `captureState`
must return a detached value; a target must reject (`accepts` false) references it cannot
resolve exactly, and `restoreState` must invalidate its own in-flight loads.

## Tests and results (serialized runner, JDK 17, isolated `build/w3-core`)

Added: `tomato.backend.data.EncounterIdentityTest` (6), `DpsDataTest` +1 test and legacy
assertions, `tomato.gui.route.ShellNavigatorTest` (6), `RouteBackRestoreTest` (1),
`ShellBackActionTest` (1).

| Run | Selectors | Result |
| --- | --- | --- |
| After 3A.1 | `EncounterIdentityTest`, `DpsDataTest`, `packets.packetcapture.logger.*` (ActivityJournal, DecoderDiagnostics, DiscoveryLog, InspectHistory, RunEvidence, SessionCapture), `CharacterArrivalTest`, `tomato.gui.dps.CombatMeterTest` | 80 tests, 0 failures/errors/skipped |
| After 3A.2 | `tomato.gui.route.*`, `tomato.gui.history.*`, `tomato.history.archive.*`, `tomato.SetupWorkspaceTest` | 53 tests, 0 failures/errors/skipped |
| After 3A.2 | `tomato.gui.activity.ActivityArchive*`, `tomato.gui.stats.*Archive*`, `SocialArchiveNativeTest`, `ChatArchiveClientTest`, `SocialScratchTest`, `tomato.gui.security.Inspect*`, `KeyPopArchiveClientTest`, `ui.WorkspaceShellNavigationTest`, `ShellHookIntegrationTest` | 78 tests, 0 failures/errors/skipped |

Acceptance fixtures covered: consecutive same-name visits get distinct refs and keep them
(including a reused MAPINFO object); pause, boundary, failed, trailing-byte and unobserved
MAPINFO yield no link; cleared collector and missing history yield none; old DpsData streams
stay unlinked; save-copy preserves identity; Back restores scope/query/page/selection/scroll
anchor; a stale in-flight destination read never renders; unknown/unsupported routes are
rejected with no change; the stack is bounded. Not run: the full suite, `shadowJar`,
UI150/UI200 scaled checks, screenshots. Those are coordinator gates.

## Requested hooks and unresolved items
- Lanes A/B/C: register module-specific targets (visit/record/recording/bounds/payload) via
  `NavigatorRegistry.register` after `TomatoGUI.createWorkspace`; newest registration wins,
  so they supersede the generic query-only targets. Coordinator wiring pending.
- Lane A (`gui/dps/**`): show `getEncounterContext()` state (linked / unlinked / legacy) and
  offer Open run / Timeline / Resources only when `context.linked()` and `canOpen(...)`.
- No breadcrumb yet (UX-02 "context breadcrumb"); the Back label names only the origin page.
- The new Back button is a new visible shell control: include the shell in the coordinator's
  UI150/UI200 and screenshot review. `scripts/typography-validation.gradle` was not changed.

## Known limitations
- Imported `.dps` files keep whatever context they carry; a VisitRef from another machine or a
  deleted session will not resolve and destinations must show it as unavailable.
- In preview (read-only history) refs name the current session although its runs are not
  persisted; they resolve only against the live journal.
- Pausing collection mid-encounter keeps the entry link (the visit is exact but ended early).
- If a target's `open` mutates its own state and then throws, the navigator does not undo that
  target's internal state (the page and Back stack are unchanged).
- Back returns to the recorded origin even if the user later moved elsewhere via the sidebar.

## Round 2 (base `9a56f1c`)

Commits: `f6051a5` (analytics integration, allowlist, STAT-2 fame hook), `d734a55` (LOG-2 and
UX-05 recording intervals; they share the same producer hunks, so they landed in one commit),
`cd8391c` (UX-04 export regression), plus this doc update. Ownership: `gui/logging/**` and
`packets/packetcapture/logger/**` are outside lanes A and C and were treated as coordinator
files for LOG-2. No lane A/C file was edited. `gui/stats/**` was not edited.

### Item 1: analytics integration
- `TomatoGUI` registers `LootRouteTarget.forWorkspace(Destination.STATISTICS|LOOT, typed, typed::restore)`
  after the generic `ArchiveRouteTarget`s, so the analytics targets are tried first. The
  factory signature was confirmed in `gui/stats/LootRouteTarget.java`.
- `scripts/typography-validation.gradle`: added the four analytics methods plus
  `tomato.gui.route.ShellBackActionTest`, `tomato.gui.route.RouteBackRestoreTest`,
  `tomato.ShellRouteRegistrationTest` and
  `tomato.gui.logging.CoverageExplanationTest.errorRouteFocusesAllowlistedPacketIssuesAndBackRestoresLogging`.
  I have not run these at 150% or 200%.
- STAT-2 hook: `AppHistory.FameSample` gains nullable `visitSession`, `visitId`, `map`,
  a 6-argument constructor `(int, long, long, String, VisitRef, String map)`, and `VisitRef visit()`.
  `AppHistory.fame(...)` reads `DiscoveryLog.INSTANCE.currentVisit()` before taking its own lock and
  stores the visit only when it is the exact active visit in the same history session. Otherwise all
  three fields are null, which Gson omits. Legacy JSON reads back with `visit() == null` (Not recorded).
  The fame field stays `long`. The analytics request suggested `double`, but the existing schema is
  `long`.
- New `DiscoveryLog.currentVisit()` returns a `CurrentVisit {VisitRef visit; String map}`, or null
  when collection is paused, no history is attached or no visit is active.

### Item 2: LOG-2
- `DiscoveryLog.Snapshot` adds these fields:
  - `retentionEvictions` counts retained samples removed at `EVENT_LIMIT`. It is separate from
    `cacheEvictions` and resets with the diagnostic clears.
  - `retainedFirst` and `retainedLast` are epoch-ms bounds, or null when nothing is retained.
  - `observedSince` is the start of the open observed interval.
  - `transitions` is a bounded list (`TRANSITION_LIMIT = 32`) of `Transition {time, collecting,
    reason}`. Reasons are pause, resume, connection boundary, clear and app close.
  - `coverageError` reports failures to save recording coverage.
- `DiagnosticCoverage` gives separate lines for the retained interval, retention evictions,
  collection state, recent transitions, sampling, decode failures, trailing bytes, the affected
  views, sampled-out events, omitted deltas, withheld stats, delta-cache evictions (explicitly "not an
  event-retention count"), observer errors and disk drops.
- `DiscoveryCatalog.AFFECTED_VIEWS` is a reviewed allowlist of {destination name, view label, packet
  names}, derived from `OPPORTUNITIES`. It exposes `affectedViews(packet)` and `packetsFor(destination)`
  and carries packet names only.
- `tomato.gui.logging.LoggingRouteTarget` handles `Destination.LOGGING`. The payload
  `LoggingRouteTarget.issuesFor(Destination view)` opens Packets with "issues only". The payload
  `packetFor(view, packet)` filters to one allowlisted packet. Other payloads or references are rejected.
  Capture and restore use the Logging view state (`applyViewState` is now package-private).
  Registered in `TomatoGUI`.

### Item 3: UX-05 recording intervals and UX-04 export scope
- `SessionStore.recordInterval(module, from, until, end)` runs asynchronously on the store worker.
  Intervals with the same start merge. The oldest are dropped beyond `ModuleAvailability.INTERVAL_LIMIT = 256`
  and the evidence is marked `truncated`.
- `SessionStore.availability(...)` no longer replaces prior intervals with a summary that has none.
- `ModuleAvailability` has optional `intervals` and `truncated`, still at `schemaVersion` 1.
  `Boolean recordedAt(long)` returns TRUE (recorded), FALSE (not recorded) or null (unknown: legacy,
  truncated, or no evidence).
- `DiscoveryLog` records an interval from the first to the last frame actually observed while
  collecting. It is persisted for `runs` and `timeline` (`RECORDED_MODULES`) when collection pauses, at a
  connection boundary, on clear or close, and every 60 s while it stays open. Collection that is on but
  sees no frames is not claimed as recorded.
- UX-04: `ArchiveWorkspace` already refused exports unless `!loading` and the displayed query equals the
  pinned result's query. The new regression test proves the export manifest's query and revision equal
  the displayed ones after an atomic restore, and that exports are refused while that restore is pending.
  No code change was needed.

### Round-2 tests (serialized runner, fresh results directory per run)

| Run | Selectors | Result |
| --- | --- | --- |
| Item 1 | `ShellRouteRegistrationTest`, `SetupWorkspaceTest`, `FameVisitHookTest`, `tomato.gui.stats.*`, `tomato.gui.route.*`, `EncounterIdentityTest`, `DiscoveryLogTest` | 145 tests, 0 failures/errors/skipped |
| Items 2–3 | `tomato.gui.logging.*` (includes new `CoverageExplanationTest` 3), `tomato.history.*` (includes new `RecordingIntervalTest` 3, `FameVisitHookTest` 1), `packets.packetcapture.logger.*`, `ShellRouteRegistrationTest`, `SetupWorkspaceTest`, `ShellHookIntegrationTest`, `tomato.gui.history.*`, `EncounterIdentityTest` | 152 tests, 0 failures/errors/skipped |
| UX-04 | `ExportScopeAgreementTest` (new, 1), `ArchiveExportHookTest` | 4 tests, 0 failures |

Not run: full suite, `shadowJar`, UI150/UI200, screenshots.

### Remaining requests
- Lane A (Runs/Timeline): register a RUNS target that accepts `Route.to(RUNS).withVisit(VisitRef)`. The
  loot drill-down emits it and no target accepts it yet. Views that detect missing evidence can offer
  `Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.issuesFor(Destination.RUNS|TIMELINE|RESOURCES|ENCOUNTER|INSPECT))`.
  Show `ModuleAvailability.recordedAt` for a run's saved session so "not recorded" is not shown as empty.
- Lane B (`gui/stats/**`): map `FameSample.visit()`/`map` in `LootArchiveClient.readFame`,
  `StatisticsArchiveAdapter.fame` and `FameSessionViewer.mapAssociationText`. Legacy samples keep
  "Not recorded".
- Lane C and other producers (chat, loot, key-pops, bridge): call `SessionStore.recordInterval` for
  their own modules when they actually record. Until then those modules stay "coverage unknown".
- Coordinator: `ArchiveResult`'s manifest `coverage` field is still generic. Adding per-session
  `ModuleAvailability` intervals there needs an edit in `tomato/history/archive`, which is outside my
  assigned files.

### Round-2 limitations
- An open interval is persisted at most every 60 s, so a crash can lose up to a minute of real
  coverage, which then reads as not recorded.
- Corrected after review: as first shipped, intervals could also over-claim. A capture stop and a
  later restart without a connection reset produced one interval spanning the unobserved time. The
  review fixes below close intervals on capture stop, start and transport reopen, and split them after
  more than 30 s without frames. Gaps shorter than that inside an interval still count as recorded.
- The fame visit is the active visit when the sample is produced. Samples taken between a boundary and
  the next MAPINFO carry none.
- Transitions and retention counters are in memory and cover the current launch only. Only the
  intervals are persisted.

## Round 3: final integration (base `3be61d4`)

Commits: `a5a8c76` (live DPS encounter link), `d64de4c` (export manifest coverage), `7bbd8a4`
(loot unavailable-state fix), `68468b5` (journey tests), plus this doc update. Lane files were
edited only where a test proved a defect (`gui/stats/LootRouteTarget`, `LootArchiveClient`,
`LootDrillDownTest`) or where the requested hook required it (`gui/dps/DpsGUI`, `EncounterLink`).

### Production-composition journeys (`tomato.WaveThreeJourneyTest`, 4 tests)
Each test builds the real `TomatoGUI.createWorkspace()` in preview mode with a synthetic writable
`SessionStore`. The tmpdir is isolated and every `ux.archive.*` workspace state is reset and restored
afterwards. Fixtures:
- two consecutive "Lost Halls" visits plus six others;
- per-visit timeline events, including a first-visit event inside the second visit's ±30 s window
  and a second-visit event at the exclusive upper bound;
- one loot bag per visit;
- a linked `DpsData` for visit 2 with a verified local object.

1. **Same-name visits.** The visit-2 routes for Runs, Timeline (±30 s), Inspect, Loot and Resources
   each show only visit 2's rows. Timeline shows `t2-a` and `t2-b` only. After every hop, Back returns
   to Runs with `ViewState` JSON equal to the origin and the same selected row.
2. **Absent session.** A `VisitRef` whose session is absent opens an explicit unavailable state:
   "Linked visit unavailable … same dungeon name" in Runs, 0 matches in Timeline, and 0 matches plus
   "Linked run unavailable here" in Loot.
3. **Route shapes.** `canOpen` is true for every route shape the lanes emit:
   - loot/fame Open recorded run;
   - the five workbench visit routes;
   - workbench ±30 s;
   - the Resources window to Timeline;
   - DPS Open run/Timeline/Resources;
   - My Info recorded local row;
   - Key pops to Notifications;
   - alert draft;
   - Logging issues.

   An unverified local object or an unknown recording is rejected.
4. **Stale loads.** For Runs, Loot and Timeline destinations, open immediately followed by Back in
   the same EDT turn leaves the Runs state, match count and page equal to the origin after any stale
   completion.

### Defect found and fixed
- **Loot route to an absent session never displayed a result.** `LootRouteTarget` scoped the query to
  `route.visit.sessionId`. For an imported, deleted or unsaved session the pinned read failed, and the
  workspace stayed without a result. It now uses All Sessions plus the exact session+visit facet
  (`Facets.visit` already checks both), matching `ActivityRoutes.visitQuery`. The drill-down summary
  adds "Linked run unavailable here … No other run is substituted." `LootDrillDownTest` had asserted
  the old scope; it now asserts `SessionStore.ALL`.
- No other integration defect was exposed: every other same-name, Back, route-shape and stale-load
  assertion passed on the first run.

### Live encounter link (lane A request 2)
- `TomatoData.currentEncounterContext()` runs on the producer thread and returns the in-progress
  encounter's entry-frozen visit, the local object ID verified so far, and the entry time. It returns
  null when no map was entered through `setNewRealm`.
- `DpsSnapshot.context` is copied in `capture`.
- `EncounterLink.live(EncounterContext)` gives LINKED or UNLINKED with `inProgress = true`:
  "Linked (live encounter)", or "Unlinked (live encounter)". A null context stays LIVE with the old
  "shown once saved" text. `DpsGUI.renderData` uses it, so live DPS offers Open run/Timeline/Resources
  under the same `canOpen` rules. The description notes that the run record is saved periodically and
  shows as unavailable until it is.
- Tests: `EncounterIdentityTest.liveSnapshotCarriesTheInProgressEncountersEntryFrozenContext` and
  `tomato.gui.dps.LiveEncounterLinkTest`.

### Export manifest coverage
- Archive manifests add `recordingCoverage`: `{session: {module: evidence}}` for every pinned session
  and every module read. The evidence is the session's declared `ModuleAvailability` (state, reason,
  intervals, truncated). When nothing is declared, including in legacy sessions, it is
  `{"state":"UNKNOWN","reason":"No recording evidence for this module in this session; coverage unknown"}`.
- `coverage` stays a string for older readers and now points to `recordingCoverage`.
  `ModuleAvailability.valid()` is public.
- Test: `tomato.history.archive.ExportCoverageManifestTest`.

### Round-3 test results (serialized runner, fresh results directory each run)

| Run | Selectors | Result |
| --- | --- | --- |
| Items 2–3 | `EncounterIdentityTest`, `tomato.gui.dps.*`, `tomato.history.*` (includes archive), `RecordedDpsHandoffTest` | 96 tests, 0 failures |
| First journey run | `WaveThreeJourneyTest` | 4 tests, 1 failure (the loot defect above) |
| After fix | `WaveThreeJourneyTest`, `tomato.gui.stats.*` | 118 tests, 0 failures |
| Consolidated | `WaveThreeJourneyTest`, `ShellRouteRegistrationTest`, `SetupWorkspaceTest`, `tomato.gui.route.*`, `tomato.gui.activity.*`, `tomato.gui.dps.*`, `tomato.gui.myinfo.*`, `tomato.gui.stats.*`, `tomato.history.*`, `EncounterIdentityTest`, `DpsDataTest`, `tomato.gui.logging.*` | 304 tests, 0 failures/errors/skipped |

Not run: the full suite, `shadowJar`, UI150/UI200, screenshots. `WaveThreeJourneyTest` was not added to
the scaling allowlist; it checks routing behaviour, not layout.

### Round-3 limitations
- The stale-load journey supersedes the destination load within one EDT turn. It shows that no stale
  completion is applied, but it cannot force the stale read to finish after the restored one;
  `RouteBackRestoreTest` covers that ordering with a blocked adapter.
- A live linked encounter's run record appears in saved history only after the 2 s run collector has
  checkpointed it. Before that, Runs shows its explicit unavailable state.
- Loot visit routes now scan All Sessions with the exact facet, the same cost profile as Runs.
- Loot fixtures in the journey test are written in `LootDashboard.Drop`'s JSON shape, because the
  class and its constructors are package-private.

## Review fixes (base `2ae070c`)

The independent review of the wave head approved it with two should-fix findings. Both are fixed,
along with the two optional items.

1. **UX-05 over-claimed coverage across a capture stop and restart.**
   - New `DiscoveryLog` hooks:
     - `captureStopped()` closes the interval with "Capture stopped" and forgets MAPINFO provenance.
     - `captureStarted()` closes any interval left open with "Capture restarted".
     - `captureInterrupted()` closes it with "Capture interrupted or reopened". This runs on the
       capture transport boundary and deliberately does not end the activity visit.
   - `CapturePublication` calls them from `started`, `stopRequested` and `boundary`. The collector is
     injectable through `CapturePublication(TomatoData, DiscoveryLog)` for tests.
   - Defence in depth: `record()` splits the interval when more than `DiscoveryLog.INTERVAL_GAP_MILLIS`
     (30 s) passes with no frames. A connected client receives several frames per second.
   - Tests: `tomato.CaptureCoverageTest` (stop, gap, restart gives two intervals and the gap reads not
     recorded) and `packets.packetcapture.logger.RecordingGapTest` (silence split plus transport hook).
2. **A stale Key-pops focus banner Back could pop an unrelated origin.**
   - `Navigator` has two default methods, `backToken()` and `nextBackToken()`, which return 0 when
     entries are not tracked. `ShellNavigator` gives every pushed Back entry a unique token.
   - `AlertRouteTargets.notifications` records the token its open pushes. The banner Back pops only
     while that entry is still on top; otherwise it only clears the focus. An untracked navigator (0)
     keeps the old behaviour.
   - `NotificationsGUI` clears the focus and restores the pre-focus search and Selected-only filter
     when the page stops showing.
   - Tests:
     - `WaveThreeJourneyTest.staleKeyPopFocusBackNeverPopsALaterNavigationsOrigin` runs the reported
       sequence in the production composition. It fails with the old banner Back and passes with the fix.
     - `WaveThreeJourneyTest.leavingTheNotificationsPageEndsTheHandoffFocusAndRestoresFilters` uses a
       real window and a card switch.
     - `ShellNavigatorTest.backTokensIdentifyEntriesSoStaleControlsCannotPopALaterOrigin`.
3. **Optional items (both done).**
   - `LootRouteTarget.forWorkspace` now opens through the atomic restore with a cleared selection and
     scroll anchor, like the Activity targets.
   - The menu toggle confirmation sounds in `TomatoMenuBar` use `Sound.preview(null)`. They still honour
     mute and volume, and no longer record "Alert sound" decisions.

### Review-fix test results
| Run | Result |
| --- | --- |
| `WaveThreeJourneyTest`, `CaptureCoverageTest`, `packets.packetcapture.logger.*`, `tomato.history.*`, `KeyPopNotificationHandoffTest`, `tomato.gui.notifications.*`, `tomato.gui.route.*`, `tomato.gui.stats.*`, `CaptureHookIntegrationTest`, `AssetReadinessRecoveryTest`, `tomato.gui.logging.*`, `ShellRouteRegistrationTest` | 267 tests, 0 failures/errors/skipped |
| Red check: `staleKeyPopFocusBackNeverPopsALaterNavigationsOrigin` with the old banner Back restored | 1 test, 1 failure, as expected; the fix was then restored |

Not run: the full suite, `shadowJar`, UI150/UI200.
