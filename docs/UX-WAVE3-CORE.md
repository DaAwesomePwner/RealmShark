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
