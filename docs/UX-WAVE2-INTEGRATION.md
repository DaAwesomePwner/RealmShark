# Wave 2 shell integration

## Resumed integration status (2026-09-24)

The original bounded handoff below records its earlier integration base. The later
Wave 2 commits now adopt the shared selected-visit export preview/writer hooks and
persist Activity, Inspect, Character, encounter-library, and Logging view state.
`ArchiveClient.previewExport` and `writeExport` use the same pinned export lease;
the common toolbar therefore includes a selected visit's linked Timeline population.
The actual production factories retain independent live and saved state.

Inactive Inspect Runs no longer writes into the shared Current Area roster, and
cached archive compound controls restore parent-first. Independent production
source review through `c1ccedb` found no blocking issue in the reviewed paths.
Native validation is still in progress: fresh queried-screen fixtures exposed
compact/enlarged layout defects, so no full/scaled/native pass is claimed here.

Build-contract checks on the resumed production head passed output isolation,
source cleanliness, generation/version invalidation, and fresh Java 8 JAR contracts
under JDK 17 / Gradle 7.6.4. Evidence:
`build/build-maintenance-0fd8d9295ccd4cac9a66847a44ad6327/`.
An earlier sandboxed attempt could not read a cached dependency and is superseded
by this successful run. Final PR-head validation and CI remain required.

The availability/recording-interval producer hook below is still outstanding for
Wave 3. Missing recording metadata remains UNKNOWN, including for empty modules.

## Original bounded integration handoff

Integration base: `feat/ux-wave-2-evidence` at `f604e78`, primary checkout.
This bounded package owns `TomatoGUI`, shell integration tests and the scaling
allowlist. Foundation and roster fixes are concurrent, separately owned work.

## Registered production paths

`TomatoGUI.createWorkspace()` now uses the existing live instances and these APIs:

- Chat: `chatPanel.workspace()`.
- Key-pops: `keypopPanel.workspace()`.
- Inspect: `SecurityGUI.workspace(securityPanel)`.
- Runs / Timeline: `ActivityPanel.workspace(DiscoveryLog.INSTANCE, RUNS/TIMELINE)`.
- Statistics: `HistoricalStatistics.statisticsWorkspace(store, statistics, scratch, states)`.
- Loot: `HistoricalStatistics.lootWorkspace(store, statistics.getLootDashboard(), scratch, states)`.

All seven are queried workspaces when `AppHistory.store()` exists; the existing
live components remain the fallback when it is null. Logging stays on its
diagnostic-specific workflow. DPS Resources registration belongs to the roster
owner's `DpsGUI` change, not this package.

Fresh preferences keep Current Session / live mode. Factories load existing
per-module state; shell construction and ordinary sidebar selection do not reset
saved scopes, predicates or named views. The explicit **Browse saved history**
action selects Runs / All Sessions through `ArchiveWorkspace.selectSession`.
Each queried wrapper retains its History library action in the common toolbar.

Statistics and Loot use separate `statistics` / `loot` directories beneath the
temporary `realmshark-archive` namespace. The existing social factories use
`history/.query-scratch/chat` and `history/.query-scratch/keypops`, outside session
journals. Existing Activity and Inspect factories use their temporary namespaces.
The foundation creates exclusive `archive-pin-*` / `archive-result-*` directories
for each query; workspaces never share a result or a source copy. Scratch creation
and cleanup are background operations. No absolute user-data path is committed.
Social archive browsing still requires its history-root scratch namespace to be
writable; supporting filesystem-read-only history media needs a module-factory
scratch injection/fallback hook. Application preview's read-only captured-data
contract is preserved; this is separate from filesystem permissions on scratch.

`TomatoGUI.closeWorkspace()` recursively closes all `ArchiveWorkspace` descendants,
including nested Resources. Both window-closing and window-closed callbacks use
it on the EDT; the method also supports headless owners. It cancels archive work
and releases result ownership, while existing held leases retain their revisions.
It does not close producers or the history writer. Normal exit retains the
existing `AppHistory` shutdown sequence: `DiscoveryLog.close()` (final boundary /
checkpoint), then `SessionStore.close()` (collect / drain / end metadata).

## Exact remaining adoption hooks

- **Availability:** at the integration base there is no `recordingLifecycle` API
  or connected module-interval producer. `SessionStore.availability(module,
  ModuleAvailability)` is available, but startup, module presence and a displayed
  empty table do not establish recording coverage. No startup metadata is emitted
  here. Missing evidence remains UNKNOWN in the catalog/UI/export contract. Wave 3
  interval-dependent analysis still needs actual recording start/stop/collection
  transitions and observed module facts, with gaps preserved; do not infer intervals
  from session lifetime or initialization.
- **Shared selected-visit export:** `ActivityArchiveClient.writeExport(...)` and
  `SelectedRunExport.preview(...)` exist. At this base `ArchiveClient` has no shared
  writer/preview hook and `ArchiveWorkspace` calls `ArchiveExport.write` directly.
  The module's **Export selected visit + Timeline** action works; generic toolbar
  **Export selected** still means the summary projection. Foundation adoption of
  both the writer and truthful expanded-population preview remains required.
- **Live state:** registering the social and Statistics/Loot factories activates
  their existing separate live-state persistence. Activity's factory persists saved
  Runs/Timeline/Resources query/view state, not its live filters, pause/selection or
  resource-tab state. Inspect's saved visit query is persisted; its current-area /
  selected-roster facets and preset/tab persistence are separate roster adoption.
  Logging retains diagnostic facet state within the instance, without adopting the
  shared persistent/named workspace store. Character and `.dps` library persistent
  query-state adoption remains with their owners.
- **Other exports:** Logging deliberately exports all retained diagnostics from
  the chosen fresh/displayed snapshot, with display filters as context only. Live
  Activity's full-history export is explicitly unfiltered. Inspect bulk copy/export
  remains complete current-area / selected-run roster scope. Registration does not
  turn these into matching-row exports. Live Chat export/state parity beyond its
  existing retained transcript actions is not added by the shell.

## Bounded validation

JDK 17 / Gradle 7.6.4, main `--release 8`, primary `.tools/gradle-home`, isolated
build `build/w2-integration` and cache `.gradle/w2-integration`:

```text
-I scripts/archive-headless.gradle test
  --tests tomato.gui.chat.ShellHookIntegrationTest
  --tests tomato.SetupWorkspaceTest
```

Final bounded run: **8 tests passed, zero failures/errors/skips**. All main and
test sources compiled. Reports: `build/w2-integration/reports/tests/test/` and
`build/w2-integration/test-results/test/`. Concurrent foundation edits are outside
this commit; this evidence does not approve their complete final patch.

The eight headless tests cover actual factory registration/live fallback,
independent sidebar/named/past-state restoration, shared Chat policy, saved Runs
navigation without assets, preview captured-data immutability with persistent
view preferences, unknown availability, nested reader disposal and final collector
checkpoint persistence. Histories, temporary scratch and preference state are
isolated; no native windows, capture or deliveries are exercised.

The scaling allowlist preserves existing selectors and adds applicable Wave 2
component suites. Explicitly headless-only stress methods remain in the ordinary
headless validation lane. Native/focus/scaling, full-wave test/JAR gates and
independent final-head review remain coordinator work; allowlisting is not a pass.
