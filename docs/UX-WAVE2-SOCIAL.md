# Wave 2B — queried Chat and Key-pop workspaces

Branch `work/ux-w2-social`, base `c5d9381`. This package adopts the committed
[Wave 2 foundation](UX-WAVE2-FOUNDATION.md) for CHAT-2, the CHAT-3 saved-view slice,
KEY-2, and the social portions of UX-01/02 state/04/07. It uses the real
`ArchiveClient`, `SessionPanel.queried`, `ArchiveQuery`, `ArchiveAdapter`,
`ArchivePage`, `ViewStateStore`, `HistoryTables` and shared export implementation.

**The module factories are implemented and tested through the shared workspace;
production shell registrations still require the coordinator changes below.**
CHAT-3's contextual alert-draft handoff remains the separately scheduled Wave 3
slice. Full-wave/native/scaled validation and independent review are not claimed.

## Exact shell integration

Keep the existing live instances and replace only these two registrations in
`TomatoGUI.create()`:

```java
// Previously SessionPanel.wrap("chat", chatPanel, chatPanel::historyWithPolicy)
chatPanel.workspace(),

// Previously SessionPanel.wrap("keypops", keypopPanel, KeypopGUI::history)
keypopPanel.workspace(),
```

Both public instance methods return `JComponent`, use `AppHistory.store()`, return
the existing live panel when the store is null, and otherwise memoize a fully
wired `SessionPanel.queried` workspace. Key-pop's factory also binds its explicit
current-tab export action to `ArchiveWorkspace.exportTo`. They construct no second
capture consumer and require no additional producer hooks or reflection.

The compatible legacy history loaders remain usable and page-local. Their local
exports/views are labeled accordingly. Use the new factories to claim whole-scope
query behavior. This worker did not edit the foundation, SessionPanel, TomatoGUI,
build configuration, scaling allowlist or shared ledger.

## Chat query and frozen evidence

`ChatArchiveClient` owns `Facets`, `Sort`, and the immutable `Row` projection.
Channel, sender/recipient text, starred-only, ignored-player visibility, literal
search, resolved date bounds and sorting apply before paging. Header/keyboard
sorting sends a new query through `HistoryTables.queried`; there is no page-local
sorter on the queried table.

Each new adapter detaches `ChatFilters.Classification` on the EDT. A SHA-256
fingerprint of its immutable local/inherited rules goes into the dependency
manifest. The worker reads pinned `chat-stars` across **all sessions**, resolving
latest `changed` per existing message ID, then source Ref as the equal-time tie
break. Missing legacy message IDs stay unstarred; no replacement ID is invented.
It then projects each message with its frozen star, ignore reason, player-ignore
classification and time interpretation. CSV/JSON and transcript copying consume
these projected values, never the current live policy.

The saved table keeps full details, selected/page transcript copying, Ctrl+C,
This player, star editing, local sender ignores and the existing Chat-filter
editor. Star writes flush off the EDT before requesting a new pin. Failures keep
an actionable status. Policy changes and completed live star changes trigger a
fresh displayed revision; a cached saved view also checks its dependency stamp
on return from Live. Existing export leases continue reading their original rows.
History rendering and reclassification do not invoke alert delivery.

Chat's timestamps are recorded `LocalDateTime`, without captured offsets. The UI
and export explain the **assumed zone**, with Java's earlier-offset overlap and
forward-adjusted gap interpretation for filtering. The original local timestamp
is preserved and used for receipt-time sorting. `assumedTime` is an explicitly
derived filter coordinate, not asserted captured UTC or a cross-zone chronology.
Changing the assumed zone creates a new query. New date input rejects ambiguous
or nonexistent local times unless an explicit ISO offset is supplied.

## Matching arrivals and live Chat state

When Follow is released, retained arrivals enter an unseen set. The badge counts
the intersection of that set and the **current visible predicate**, including
channel, player, literal text, stars, ignore visibility and dates. Filter/policy
changes re-evaluate the badge without counting unrelated messages. Retention
eviction removes corresponding unseen entries. Jump to latest clears the unseen
set, resumes Follow and retains the selected message. Arrival refresh preserves
the table's reading anchor where the record remains available.

The live view exposes package-local `captureLiveState` / `applyLiveState` hooks;
the factory installs their persistence automatically. It retains search, channel,
player, star/ignore filters, bounds, sort, Follow, unseen IDs, selected IDs, scroll
anchor/offset and column layout. Live columns have presets/reset/copy controls.

## Key-pop events and whole-query summaries

`KeyPopArchiveClient` owns modes `EVENTS`, `BY_PLAYER`, and `BY_ITEM`. Exact player
matching excludes Anna from Ann; its removable chip is independent of general
word search. Multiple types and exact dungeon/item names combine with explicit
date bounds. Summary drill-down changes the same query to Events while retaining
scope and filters.

Each adapter filters source events before reducing. Events and summaries carry
the same whole-query matching-pop denominator. Shares are contribution counts /
matching observed pop events, **excluding portal callouts**. Contributor grouping
is by case-insensitive recorded name, not verified account identity. By-item rows
count distinct recorded contributor names across the full query. Counts identify
pop events, contributor summaries or dungeon/item summaries; page row counts are
never substituted for event denominators.

Aggregation keeps at most 512 group/pair keys and approximately 4 MiB of key
characters per batch (one larger key can make progress, subject to the existing
source/output row limits). It rescans the **same immutable pin** for successive
ordered batches. It stores counters/last times, not lists of events. By-item
aggregation batches `(item, normalized contributor)` pairs, so distinct-player
counts remain exact across batch boundaries. Heap is bounded independently of
event count; high cardinality trades additional scans for memory. This introduces
no competing storage/sort engine. Cancellation remains cooperative during scans.
Chat's annotation index separately scales with unique bookmark IDs.

Summary references use `@query/keypops/summary:<mode>/<group>`: stable aggregate
selection keys, explicitly **not** session/visit/player identity links. Real
source-session provenance remains in the shared pin/export manifest. Event rows
retain their original storage Refs.

The archive's explicit **Export events (all matches)** or **Export current summary
(all matches)** action delegates to `ArchiveWorkspace.exportTo`; generic selected,
page and all-match CSV/JSON actions also remain available. CSV uses a stable schema
with mode, event/group values, counts and denominator so a tab transition cannot
substitute incompatible columns during export. Modal export selection rechecks
the displayed revision. The exported population is the selected tab's projection.

Live Key-pops retain the bounded session buffer. Multiple choices and resolved
absolute periods apply there too. Relative periods resolve once when selected,
not on each refresh. The resolved half-open interval and pop denominator are
shown. Explicit retained-event/current-tab CSV actions describe that local scope;
whole-history CSV/JSON is provided by the queried workspace.

Live state hooks persist exact player, text, multiple facets, resolved bounds,
period control, active tab, each table's sort/column layout, and the active
selection/scroll anchor. New pop observations carry an optional local UUID for
live selection; old records remain readable without one. Archive identity still
uses storage Refs. Missing live records after restart are not guessed from names.

## State, dates and asynchronous ownership

The existing `ViewStateStore` owns all persistence and named views. Independent
keys are `ux.archive.chat`, `ux.archive.keypops`, `ux.archive.chat-live`, and
`ux.archive.keypops-live`. Archive scope changes do not replace live intent or
another module's scope. Live-state updates are coalesced, with pending/failure
feedback and named live views/reset available in the expandable controls.

`SocialQueryControls` only composes module controls and renderer-local state from
the foundation. It is not a second query, state-storage or export framework.
Renderer callbacks merge against the latest local `ViewState`. Replaced/pending
renderers are retired and their controls disabled; deferred column/position and
annotation callbacks cannot overwrite a newer renderer's state. `HistoryTables`
supplies position restoration and column controls.

All bounds are `[from, until)`, with the resolved IANA zone/offset shown. Empty
endpoints mean unbounded. Unknown timestamps follow the foundation contract:
unbounded queries include them; bounded queries use the explicit checkbox. Key-pop
table formatting retains the application's existing display-zone preference;
the date controls label their separate input/bounds zone. CSV event times remain
UTC Instants; legacy Chat does not acquire captured UTC offsets.

No blocking foundation API change was required. The foundation has no live-state
binding hook; the two factories install their own module capture/apply hooks using
the existing store. No coordinator live-state hook is needed beyond registration.

## Fresh validation

Final focused headless run: **28 tests passed, zero failures/errors/skips**.
`compileJava` and `compileTestJava` passed using JDK 17 / Gradle 7.6.4, with main
Java 8 API/bytecode targeting retained. Tests use synthetic histories/preferences,
isolated scratch and fake alert channels. No native windows/focus checks, live
capture, actual sound playback or bridge deliveries were used.

New suites:

- `ChatArchiveClientTest` (5): 12,005 messages; a star on the unfiltered last page
  found on filtered page one; cross-session bookmarks and stable ties; global sort;
  pinned CSV/JSON reasons/stars; real workspace/named-state/columns/selection;
  independent Key-pop scope; stale callbacks; shared-policy refresh; legacy DST.
- `ChatArrivalStateTest` (3): filter-aware unseen arrivals and stable selection;
  live snapshot/seen-state restoration; real preferences restart preserving the
  separate archive scope.
- `KeyPopArchiveClientTest` (3): 12,005 events, 12,002 contributor groups; exact Ann;
  global summary ordering/counts/denominators; by-item distinct contributors across
  batches; pinned exports; real tab/workspace restart; large keys crossing the
  grouping byte budget without truncation.
- `KeyPopLiveStateTest` (2): exact contributor/tab/sort/bounds restoration and real
  attached live-state persistence across preference-store restart.

Fifteen existing behavioral checks also passed: five non-window ChatExplorer
methods, four SharedChatPolicy tests, TypedChatAlertTest, ExactContributorTest,
KeyPopFormattingTest, and three non-window KeyPopTest methods. Initial compilation
and fixture-startup failures were corrected before the final successful run.

Reports: `build/w2-social/reports/tests/test/index.html` and
`build/w2-social/test-results/test/`. Isolated cache: `.gradle/w2-social`.
Reproduction from this worktree:

```powershell
$tools = (Resolve-Path '..\..\..\.tools').Path
$env:JAVA_HOME = "$tools\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "$tools\gradle-home"
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
$selectors = @(
  'tomato.gui.chat.ChatArchiveClientTest', 'tomato.gui.chat.ChatArrivalStateTest',
  'tomato.gui.keypop.KeyPopArchiveClientTest', 'tomato.gui.keypop.KeyPopLiveStateTest',
  'tomato.gui.chat.SharedChatPolicyTest', 'tomato.gui.chat.TypedChatAlertTest',
  'tomato.gui.chat.ChatExplorerTest.routingPreservesBothSidesOfPrivateConversationsAndCopiesPackets',
  'tomato.gui.chat.ChatExplorerTest.whisperAlertsRequireIncomingDirectionWithNormalizedPlayerNames',
  'tomato.gui.chat.ChatExplorerTest.combinedFiltersStarsCopyAndClearOperateOnOneHistory',
  'tomato.gui.chat.ChatExplorerTest.burstsAreBoundedAndDeliveredOnEdtAndClearDropsQueuedMessages',
  'tomato.gui.chat.ChatExplorerTest.packetMarkupIsLiteralAndFullMultilineUnicodeMessagesSurvive',
  'tomato.gui.keypop.ExactContributorTest', 'tomato.gui.keypop.KeyPopFormattingTest',
  'tomato.gui.keypop.KeyPopTest.liveMetricsSummarySortingAndCombinedFiltersStayConsistent',
  'tomato.gui.keypop.KeyPopTest.filtersAreLiteralCombinedAndIncludeTimeBoundary',
  'tomato.gui.keypop.KeyPopTest.exportEscapesCapturedContentAndWritesUtc'
)
$filters = @(); foreach ($selector in $selectors) { $filters += @('--tests', $selector) }
& "$tools\gradle-7.6.4\bin\gradle.bat" --offline --no-daemon --console=plain `
  --project-cache-dir '.gradle/w2-social' '-PrealmSharkBuildDir=build/w2-social' test @filters
```

Coordinator gates: replace both shell registrations, run the integrated full
test/JAR gate, add appropriate native/scaled evidence through the shared allowlist
owner, and independently review the final wave head. Native/scaled keyboard,
compact layouts and visual evidence have not been run in this worker.
