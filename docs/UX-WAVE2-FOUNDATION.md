# Wave 2A archive foundation

Base: verified main `64d58d0` (Wave 1 PR #11). This package supplies the shared
foundation for UX-01, UX-02 state, UX-04, UX-05 and UX-07. **These roadmap IDs are
not complete until module adapters, native/scaled validation and independent
final-head review are integrated.** Existing production module registrations
still use the compatible legacy loader. No capture producers were changed here.

## Actual API and ownership

`tomato.history.archive` contains the storage-independent query/result contracts:

```java
ArchiveQuery<F, S extends Enum<S>>
ArchiveAdapter<R, F, S extends Enum<S>>
ArchiveRow<R>                         // value + session/module/locator/child Ref
ReadSnapshot                        // AutoCloseable, private source copies
ArchiveResult<R>                     // AutoCloseable, ordered disk-backed rows
ArchivePage<R>                       // immutable list, matches/unit/revision/issues
ExportSelection                     // selected(refs), page(number,size), all()
ArchiveExport                       // JSON or CSV; exclusive output claim
Cancellation                        // cancel(), check()
```

The normal worker entry point is:

```java
try (ArchiveResult<Row> result = HistoryPage.open(
        store, query, adapter, scratchDirectory, cancellation)) {
    ArchivePage<Row> page = result.page(0, 1000, cancellation);
    long pageNumber = result.pageOf(recordRef, 1000, cancellation); // -1 if absent
    result.stream(ExportSelection.all(), row -> consume(row), cancellation);
}
```

`ArchiveResult.open(store, ...)` is equivalent. Advanced multi-source clients can
call `store.capture(sources, scratch, cancel)` and then
`ArchiveResult.open(pin, query, adapter, scratch, cancel)`; that overload takes
ownership of the pin on success **and failure**. Close unused pins yourself.

The pin retains its capturing store's current-session ID. `pin.resolveScope(...)`
resolves `@current` against that frozen ID. Opening a supplied pin validates the
adapter's required `sources(...)` and binds its reads to those scopes before the
scan, including custom adapters' unscoped `pin.read(module, ...)` calls. An
ALL-session pin can serve session A, but a B-only pin cannot serve A or claim an
ALL-session result. Required modules and global dependencies must have actually
been captured. Declare the primary selected session explicitly; global auxiliary
sources (such as Chat stars) retain their ALL-session scope. Source declaration
methods must be pure: the origin store is provided for identity resolution, not
for fresh reads during replay. A pin is transferred to one result, not rebound.

The foundation owns `SessionStore`, these archive types, `HistoryPage`,
`SessionPanel.queried`, `ArchiveWorkspace`, `ArchiveClient`, `HistoryTables`,
`HistoryLibrary`, `ViewState`, `ViewStateStore`, and the optional
`SnapshotRefresh.request(..., discard)` overload. Module workers own their facet
DTOs, sort enums, immutable projections, predicate semantics, renderers and
summary reducers. Give `ActivityPanel` one writer for Runs/Timeline changes.
Shell registration and availability producer hooks require explicit integration
ownership. No universal field registry, route bus, database or reflection-based
GUI discovery is required.

## Query semantics

```java
ArchiveQuery<Filters, Sort> q = ArchiveQuery.of(
    ArchiveQuery.CURRENT, new Filters(), Filters.class, Sort.TIME);
q = q.withScope(SessionStore.ALL)
     .withText("Ann")
     .withBounds(new ArchiveQuery.Bounds(
         fromMillis, untilMillis, ZoneId.of("UTC"),
         ArchiveQuery.TimeMode.ENTRY, false))
     .withOrder(Collections.singletonList(
         new ArchiveQuery.Order<>(Sort.TIME, ArchiveQuery.Direction.DESCENDING)));
```

Facets are detached through their declared class, including on `facets()` reads.
Use stable enum names and explicit unknown-value policies. Query/order lists are
immutable. Restoration rejects unknown versions, sort names and facet structures
instead of silently broadening a saved query. A module schema change therefore
needs an explicit migration; it must not be hidden by Gson ignoring new fields.

Bounds are resolved once, half-open `[from, until)`. `ENTRY` tests the start.
`OVERLAP` tests `[start,end)`; a point/unknown end uses the known start instead of
inventing an observation interval. `includeUnknown` applies to missing starts.
Unbounded queries include unknown times. Relative-period controls resolve an
anchor when activated, persist the actual bounds, and show that anchor. They do
not re-anchor to each loaded page.

The engine applies `adapter.inBounds()` and `adapter.matches()` before sorting.
It appends `ArchiveRow.Ref` as the deterministic final tie-breaker. Numeric/date
comparators receive raw typed values, not formatted cell strings. An empty order
still has deterministic reference order. Module code must define null ordering.

`ArchiveAdapter.records(module, type, unit, time, matches, comparator)` is the
small one-record/one-row convenience factory. A custom adapter implements:

```java
Class<R> rowType();
String unit();
List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<F,S> query);
void scan(ReadSnapshot pin, ArchiveQuery<F,S> query,
          ArchiveAdapter.Sink<R> rows, Cancellation cancel) throws IOException;
boolean matches(ArchiveRow<R> row, ArchiveQuery<F,S> query);
Long time(ArchiveRow<R> row);           // nullable if not captured
Comparator<R> comparator(S field);
```

Optional hooks are `endTime`, `inBounds`, `validate`, `dependencies`, and `counts`.
`validate` rejects unsupported operations (e.g. custom periods on undated
counters). Summary adapters filter original events/time bounds **before**
reducing, emit summary rows, and override `inBounds` to return true. Counts then
use the summary row unit; include event numerator/denominator in the summary
projection. `scannedCount` counts candidate **projection rows**, not packets.
`dependencies()` records frozen policy/definition versions in the manifest.
`counts()` returns a map of `new ArchiveAdapter.Count(value, unit, population)`
after the complete scan. These counters appear unchanged in `page.counts` and
the export manifest; use them for whole-query cards/denominators. Omit unknown
counts. A count's population label distinguishes matching events from broader
scope totals. Do not use arbitrary row-size collections as a counter store.

## Pinning, consistency and limits

A snapshot copies the selected metadata, fixed newline-terminated JSONL prefixes,
and checkpoint images into the caller's scratch directory. It records source
lengths/digests and capture start/end. Capture is a **vector of source cuts**, not
an atomic transaction across applications or modules. Checkpoints are copied,
not reopened by path during export. Source replacement/shortening during capture
fails explicitly and can be retried. Append-only prefixes permit capture to
continue. An unfinished tail is excluded with a visible source issue; malformed
complete records fail the query rather than silently changing its count.

No automatic `flush()` occurs on paging or capture. The scope is **persisted saved
data**; pending producer writes may arrive in the next explicit Refresh. No
producer locks are held during query parsing, sorting or export.

Matches are encoded into sorted chunks, merged with bounded fan-in, then stored
with a disk offset index. Paging never scans live history. Export streams the
same ordered projection; a later policy/star/checkpoint edit cannot change it.
Projection and export functions must be pure functions of their frozen inputs.
For classification-dependent output, include the classification in the row.

Concrete bounds:

- Sort chunks: at most 1,024 rows / 4 MiB encoded data, with a 1 MiB per-row limit.
- Merge fan-in: 16 readers; run-path bookkeeping grows logarithmically.
- Raw journal record/checkpoint: 16 MiB maximum; oversized data fails explicitly.
- Page: at most 1,000 rows / 8 MiB encoded data. Reduce page size or project lighter
  rows when the byte limit is exceeded; the engine never silently truncates it.
- Heap also includes decoded objects and source manifests (one entry per source
  file), not all matching records. Metadata enumeration is O(session/file count).
- Scratch disk is O(selected source bytes + matched projection); a merge can
  temporarily retain both its inputs and output. Storage failures are errors.
- A workspace has one reader and one replaceable pending request. There is no
  cross-workspace cache or process-wide query scheduler. Budget simultaneous
  programmatic callers accordingly.

Results own their pins and scratch. Close results when replacing/discarding them;
leases allow exports to outlive the visible result. EDT closure schedules scratch
cleanup on a daemon worker. Non-EDT closure cleans synchronously. Failed cleanup
is reported to stderr; automatic scavenging after a process crash is not included.
Only delete known archive scratch directories, never arbitrary history folders.

Use compact visit projections: retaining full resource timelines/rosters in every
table row defeats the page budget. `pageOf` resolves an exact reference beyond
page one. Renderers can acquire `page.lease()` and then, off the EDT, call
`lease.readSource(scope, module, type, sink, cancel)` for details from a source
declared in the original pin. That raw read intentionally does not apply the
display query: match the selected source Ref/session/domain key explicitly.
`lease.stream(selection, sink, cancel)` reads matching projected rows instead.
Close the lease when the background detail task finishes; reject stale detail
completion with the module's SnapshotRefresh generation.
Projected item occurrences use `source.child("item-" + position, row)`;
same-item duplicates in one bag remain distinct. Storage locators do not establish
Chat-to-Inspect identity. Visit links always require **session + recorded visit ID**.

## Opt-in SessionPanel wiring

Existing `Loader.load(store, scope, page, text)`, the `SessionPanel` constructor,
`wrap`, `Loaded(Supplier, boolean, String)`, `Loaded.createView()`, and legacy
`HistoryPage.read` overloads remain source compatible.

```java
ArchiveWorkspace<Row, Filters, Sort> workspace = SessionPanel.queried(
    store, "module-name", existingLiveComponent,
    new ArchiveClient<Row, Filters, Sort>() {
        public ArchiveQuery<Filters, Sort> initialQuery() { return initial; }
        public Path scratchDirectory() { return isolatedScratch; }
        public ArchiveAdapter<Row, Filters, Sort> adapter(ArchiveQuery<Filters, Sort> q) {
            return buildAdapterWithFrozenDependencies(q);
        }
        public int pageSize() { return 100; }
        public JComponent render(ArchivePage<Row> page, ViewState<Filters, Sort> state,
                                 Binding<Filters, Sort> binding) {
            return createView(page, state, binding);
        }
    }, ViewStateStore.application());
```

The adapter factory runs on the EDT and must only detach already-available
policy/configuration; no I/O. Scan/filter/sort/page work runs off the EDT. The
renderer runs on the EDT. Inner controls call
`binding.queryChanged(state.query.withFacets(nextFacets))`; they must **not**
refilter `page.rows`. Header sorting uses the same callback. Programmatic restore
callbacks are suppressed. Pending query changes disable ambiguous exports.
Renderer callbacks are invalidated when intent changes, before replacement work
starts, and on hide/removal/disposal. Obsolete controls are disabled and callbacks
also check their original query against the current intent. Saved references are
validated and reconstructed before state installation or persistence: malformed
anchors, selected refs and null entries cannot replace a valid active view.
Paging keeps the revision; Refresh captures a new one and can re-anchor by Ref.
After a shared policy change use `binding.refresh()` even if the query is equal.
For a saved annotation, wait for its save to finish off the EDT before requesting
the replacement pin; keep save failures visible rather than claiming it persisted.
Hide cancels pending work; removal closes the result; disposal should call
`workspace.close()` on the EDT. Cancellation is cooperative inside reads/merges,
and discarded generations close newly built results.

`ViewState` carries live/saved mode, symbolic scope, query/page/tab, selected
references, scroll anchor/offset, and per-table column layouts. Keep a renderer-
local current `ViewState` when merging several control callbacks, then send
`binding.viewChanged(nextState)`; do not overwrite newer layout changes with an
old captured state. `HistoryTables.position` / `restorePosition` help retain
selection/scroll. `HistoryTables.controls` supplies column presets, visibility,
Reset columns, Copy selected and Details. `HistoryTables.queried` supplies typed
columns, an accessible name, Enter, Ctrl+C and Ctrl+Shift+Up/Down global sorting.
It deliberately has no page-local row sorter.

The toolbar provides text search, Reset filters, named view save/load/delete,
explicit export populations, cancellation and a searchable library. Facet chips
and task-specific controls belong to module renderers. Saved state uses versioned
`ux.archive.<module>` keys and the existing asynchronous PreferencesStore writer.
`ViewStateStore.preferences(injectedStore)` isolates tests. Each module key is
independent. Failed saves keep memory state and expose retry status; unreadable
saved state requires the explicit Reset saved state action.

## Concrete module adapter examples

These are adoption examples **inside the corresponding module package**, where
the existing package-private models are accessible. The facet DTOs/sort enums
below are module-owned declarations, not a foundation field-registration API.
The complete executable synthetic reference client is
`ArchiveWorkspaceTest.Reference`; it exercises this same factory and binding.

### Chat

For a simple channel/player query (all constructor arguments are actual API):

```java
enum ChatOrder { TIME, PLAYER }
final class ChatFacets {
    Set<ChatMessage.Channel> channels = new LinkedHashSet<>();
    String player = "";
}

ArchiveAdapter<ChatMessage, ChatFacets, ChatOrder> chatAdapter(
        ArchiveQuery<ChatFacets, ChatOrder> q) {
    ChatFacets f = q.facets();
    ZoneId assumedZone = ZoneId.of(q.bounds().zone);
    return ArchiveAdapter.records("chat", ChatMessage.class, "messages",
        message -> message.received == null ? null
            : message.received.atZone(assumedZone).toInstant().toEpochMilli(),
        (message, query) -> (f.channels.isEmpty() || f.channels.contains(message.channel))
            && message.matches(query.text(), f.player),
        field -> field == ChatOrder.TIME
            ? Comparator.comparing(message -> message.received,
                  Comparator.nullsLast(Comparator.naturalOrder()))
            : Comparator.comparing(message -> message.player,
                  String.CASE_INSENSITIVE_ORDER));
}
```

This minimal example is **not full Chat migration**: shared ignore classification,
starred-only and receipt-time ignore evidence must be included. The concrete
multi-source hook is:

```java
public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<F,S> q) {
    return Arrays.asList(new ReadSnapshot.Source(q.resolvedScope(store), "chat"),
                        new ReadSnapshot.Source(SessionStore.ALL, "chat-stars"));
}
```

In `scan`, read `ChatExplorer.Bookmark` from `chat-stars` before reading messages;
select latest `changed` per existing bookmark ID, with Ref as the equal-time tie
break. Emit a compact DTO containing message, starred state and ignore reason
computed with the **frozen** `ChatFilters.Classification` from the adapter factory.
The predicate tests this DTO, so all pages, counts and exports agree. New policy
or star changes request a new revision, while already leased exports finish on
their original projection. Legacy missing IDs cannot acquire invented stars.

Chat has historical `LocalDateTime`, not a stored UTC offset. Label the selected
zone as assumed for old records. The example uses Java's `atZone` resolution
(earlier offset in an overlap, forward adjustment in a gap); a different policy
must be explicit and persisted, not silently taken from a later machine default.

### Key-pops

```java
enum PopOrder { TIME, PLAYER, ITEM }
final class PopFacets {
    Set<KeyPopEvent.Kind> kinds = new LinkedHashSet<>();
    Set<String> items = new LinkedHashSet<>();
    String exactPlayer = "";
}

ArchiveAdapter<KeyPopEvent, PopFacets, PopOrder> popAdapter(
        ArchiveQuery<PopFacets, PopOrder> q) {
    PopFacets f = q.facets();
    return ArchiveAdapter.records("keypops", KeyPopEvent.class, "pop events",
        event -> event.time == null ? null : event.time.toEpochMilli(),
        (event, query) -> (f.kinds.isEmpty() || f.kinds.contains(event.kind))
            && (f.items.isEmpty() || f.items.contains(event.item))
            && (f.exactPlayer.isEmpty() || f.exactPlayer.equalsIgnoreCase(event.player))
            && event.matches(query.text(), "All types", "All dungeons / items", null),
        field -> field == PopOrder.TIME
            ? Comparator.comparing(event -> event.time,
                  Comparator.nullsLast(Comparator.naturalOrder()))
            : Comparator.comparing(event -> field == PopOrder.PLAYER ? event.player : event.item,
                  String.CASE_INSENSITIVE_ORDER));
}
```

By-player/by-item summaries reduce **all matching events**, not the loaded page.
Use `result.stream(ExportSelection.all(), ...)` off the EDT or a grouped adapter.
Retain counters/last timestamps instead of lists of every contributor's events.
High-cardinality grouping needs its own bounded/spill strategy. Counts and CSV
must state whether the unit is pop events, players or item-summary rows.

### Runs / Timeline

```java
enum ActivityOrder { TIME, MAP }
final class ActivityFacets {
    Set<String> outcomes = new LinkedHashSet<>();
    Set<String> kinds = new LinkedHashSet<>();
    String visitId = "";
    String visitSession = "";
}

ArchiveAdapter<ActivityJournal.Entry, ActivityFacets, ActivityOrder> timelineAdapter(
        ArchiveQuery<ActivityFacets, ActivityOrder> q) {
    ActivityFacets f = q.facets();
    return new ArchiveAdapter<ActivityJournal.Entry, ActivityFacets, ActivityOrder>() {
        public Class<ActivityJournal.Entry> rowType() { return ActivityJournal.Entry.class; }
        public String unit() { return "events"; }
        public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<ActivityFacets, ActivityOrder> query) {
            return Collections.singletonList(new ReadSnapshot.Source(query.resolvedScope(store), "timeline"));
        }
        public void scan(ReadSnapshot pin, ArchiveQuery<ActivityFacets, ActivityOrder> query,
                         Sink<ActivityJournal.Entry> rows, Cancellation cancel) throws IOException {
            pin.read("timeline", ActivityJournal.Entry.class, rows, cancel);
        }
        public Long time(ArchiveRow<ActivityJournal.Entry> row) { return row.value.time; }
        public boolean matches(ArchiveRow<ActivityJournal.Entry> row, ArchiveQuery<ActivityFacets, ActivityOrder> query) {
            ActivityJournal.Entry e = row.value;
            return (f.kinds.isEmpty() || f.kinds.contains(e.kind))
                && (f.visitId.isEmpty() || (f.visitId.equals(e.visitId) && f.visitSession.equals(row.ref.session)))
                && (e.map + " " + e.kind + " " + e.detail).toLowerCase(Locale.ROOT)
                    .contains(query.text().toLowerCase(Locale.ROOT));
        }
        public Comparator<ActivityJournal.Entry> comparator(ActivityOrder field) {
            return field == ActivityOrder.TIME ? Comparator.comparingLong(e -> e.time)
                : Comparator.comparing(e -> e.map, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        }
    };
}
```

Runs use the same interface with `"runs"`, `ActivityJournal.Visit.class`, time
`started`, and `endTime` returning `lastSeen` for observed overlap. Test
`ParseDungeon.isDungeon`, `runStatus`, completion evidence, gaps and numeric
ranges in `matches`; preserve Left/unconfirmed. Prefer a summary DTO for the
table. Inspect facets must inspect the full source visit before projecting it,
not merely the roster from 100 already-selected visits. For selected-run linked
exports, include Timeline in the original source pin and assemble a projection
with exact session/visit linkage. The generic exporter does not fabricate links.

### Loot occurrences

Flatten before filtering/paging; each duplicate item position is distinct:

```java
final class Occurrence {
    final long time;
    final String dungeon, bag, visitId;
    final LootDashboard.Item item;
    Occurrence(LootDashboard.Drop drop, LootDashboard.Item item) {
        time = drop.time; dungeon = drop.dungeon; bag = drop.bag;
        visitId = drop.visitId; this.item = item;
    }
}

public void scan(ReadSnapshot pin, ArchiveQuery<LootFacets,LootOrder> q,
                 Sink<Occurrence> rows, Cancellation cancel) throws IOException {
    pin.read("loot", LootDashboard.Drop.class, source -> {
        for (int i = 0; i < source.value.items.size(); i++) {
            cancel.check();
            rows.accept(source.child("item-" + i,
                new Occurrence(source.value, source.value.items.get(i))));
        }
    }, cancel);
}
```

Declare `sources` as the selected scope's `loot`, `rowType` as `Occurrence.class`,
`unit` as `"item occurrences"`, and `time` as `row.value.time`. `LootFacets` owns
typed bag/dungeon sets, rarity/tier, slot/applied ranges and an explicit unknown
policy. Negative existing enchant counts remain unknown, not zero. Variant
summaries count occurrences separately from displayed variant rows. Statistics
must reject period filters on undated aggregate counters via `validate`; rates
need pinned Runs + Loot sources and explicit eligible denominators.

## Export API and metadata library

The shared toolbar dispatches two module-owned hooks on a worker:

```java
default String previewExport(ArchiveResult.Lease<R> lease,
    ExportSelection selection, Cancellation cancel) throws IOException;
default Path writeExport(ArchiveResult.Lease<R> lease,
    ExportSelection selection, ArchiveExport.Format format,
    Path directory, String base, Cancellation cancel) throws IOException;
```

The defaults preview/export matching projection rows. `exportColumns()` and both
hooks must use frozen data and avoid reading Swing controls. Preview runs **before
confirmation**, and the same lease is transferred to the writer even if the
display refreshes while the user chooses a destination. Cancellation is tied to
the SwingWorker's cancellation state, including `cancel(false)`. The toolbar stays
busy until the background reader/writer and output cleanup actually finish.

`ActivityArchiveClient.writeExport` already has the exact signature and is now
dispatched automatically, so selected visits use its linked-evidence exporter.
The integration owner must add this companion preview override in that client
(the foundation owner does not edit module clients):

```java
@Override public String previewExport(ArchiveResult.Lease<Row> lease,
        ExportSelection selection, Cancellation cancel) throws IOException {
    if (mode != ActivityPanel.Mode.TIMELINE
            && selection.kind == ExportSelection.Kind.SELECTED) {
        if (selection.refs.size() != 1)
            throw new IllegalArgumentException("Select exactly one visit for linked evidence export");
        return SelectedRunExport.preview(lease,
            selection.refs.iterator().next(), cancel).description();
    }
    return ArchiveClient.super.previewExport(lease, selection, cancel);
}
```

This supplies the linked-event count before confirmation. The shared hook tests
exercise that implementation through a headless confirmation callback and verify
old-pin ownership through a display refresh; the production module override is
an integration change.

Capture an export lease on the EDT before scheduling the job; close it after the
worker finishes, including failure/cancellation. The provided workspace does
this, including cancellation of a SwingWorker before its reader starts.

```java
try (ArchiveResult.Lease<Row> held = result.lease()) {
    Path output = ArchiveExport.write(held, ExportSelection.all(),
        ArchiveExport.Format.CSV, outputDirectory, "report",
        Arrays.asList(new ArchiveExport.Column<Row>("Amount", row -> row.amount)),
        cancellation);
}
```

JSON embeds `manifest` and ordered `rows` (each with `origin` and `value`). CSV
begins with a quoted `# RealmShark archive manifest` record, followed by column
headers/data; spreadsheet formula-leading text is escaped. Numeric values retain
their type when formatted: numeric `-12` stays `"-12"`, while textual `"-12"`
is emitted as `"'-12"`. With no CSV columns,
the last column is record JSON. Both include count/unit, selected scope,
query/bounds/order, source-session metadata, source cuts/digests, dependency
versions and revision. Selected references missing from the result fail the
export. Same-name exports exclusively claim suffixed names with `CREATE_NEW`.
Staging/failed claims are cleaned; an existing destination is never overwritten.
Batch callers retain per-file success/failure results; no all-or-nothing batch
transaction is promised. The workspace offers Open export folder after success.

`SessionStore.catalog(cancel)` returns healthy and unreadable `SessionEntry`s;
one malformed metadata file cannot hide other sessions. An unreadable selected
session fails; All Sessions continues healthy entries with explicit issues.
`sessions()` is a strict legacy enumeration: it throws if any session metadata
cannot be read. Legacy `read(scope, ...)` validates the selected metadata before
calling consumers, throws for a corrupt selected session or incomplete ALL scope,
and permits a healthy specific session despite unrelated bad metadata. These APIs
have no channel for partial coverage; callers needing isolated entries/issues use
`catalog` or pinned results. The library
loads metadata in the background, supports search/open/import/rename/delete,
retains selection by ID, and labels open/interrupted sessions honestly. Existing
active locks and import markers continue protecting deletion/idempotency.

`SessionStore.availability(module, evidence)` asynchronously writes optional,
versioned metadata for the current session. Supported states are UNKNOWN,
PARTIAL and NOT_CAPTURED with a reason and optional observed bounds. Module
presence, import labels and absent metadata never imply complete recording or
recorded zero. Unknown availability versions fall back to UNKNOWN. Later producer
owners must supply positive evidence; no producers were instrumented in 2A.

Integration-owner call shape (schema version 1; the map is optional on old sessions):

```java
CompletionStage<Void> saved = store.availability("loot",
    new SessionStore.ModuleAvailability(
        SessionStore.ModuleAvailability.State.PARTIAL,
        "Observed collection interval; gaps remain possible", fromMillis, untilMillis));
// Handle saved.whenComplete(...) without waiting on the EDT.
```

Read with `entry.availability("loot")` after `store.catalog(cancel)` on a worker.
The value exposes `schemaVersion`, `state`, `reason`, nullable `from`/`until`.
UNKNOWN is the fallback for absent/unsupported metadata. NOT_CAPTURED needs
positive evidence for the declared interval; do not infer it from an empty file,
an import label, or a currently paused collector. Producer integration belongs
to the capture/AppHistory owner, who must preserve earlier observed coverage.

## Validation and handoff boundaries

Headless validation uses JDK 17 / Gradle 7.6.4, preserving Java 8 main APIs/bytecode:

```powershell
$env:JAVA_HOME = '<primary repo>\.tools\jdk-17.0.20.1+1'
$env:GRADLE_USER_HOME = '<primary repo>\.tools\gradle-home'
& '<primary repo>\.tools\gradle-7.6.4\bin\gradle.bat' --no-daemon `
  --project-cache-dir '.gradle/w2-foundation' `
  '-PrealmSharkBuildDir=build/w2-foundation' `
  -I 'scripts/archive-headless.gradle' test `
  --tests 'tomato.history.archive.*Test' `
  --tests 'tomato.gui.history.ArchiveWorkspaceTest' `
  --tests 'tomato.gui.history.ViewStateStoreTest' `
  --tests 'tomato.history.SessionStoreTest' `
  --tests 'tomato.gui.activity.SnapshotRefreshTest'
```

The init script only enables headless mode; always supply bounded test selectors.
Reports are under `build/w2-foundation/reports/tests/test` and `test-results/test`.
Final package validation on 2026-09-22: **35 tests passed, 0 failures/errors/skips**.
The same invocation compiled all main/test sources, with main `--release 8`.
Breakdown: archive pipeline 12, catalog 4, headless workspace 6, persistent state
3, existing SessionStore 8, and existing SnapshotRefresh 2. This is bounded
headless evidence, not the full wave or native/scaled gate.

Review-fix validation on the integrated `f604e78` baseline: 12 new regression
tests first failed, covering all six reported blockers. After the fixes, **87
headless tests passed with zero failures/errors/skips**, including three export
hook/lifecycle tests and the integrated Activity, Chat, Key-pop, Loot and Statistics
client suites. Red evidence: `build/w2-foundation-review-fixes-red/`; passing
evidence: `build/w2-foundation-review-fixes/`; isolated project cache:
`.gradle/w2-foundation-review-fixes`. Native/scaled validation remains separate.
Fixtures use isolated history/preferences/scratch. Tests cover 12,005 records,
24,010-row global ties/external merge, explicit page byte limits, 500 sessions with
one corrupt metadata file, checkpoint/star/journal changes, leased exports under
owner closure, output failure/collision/cancellation, real preference restart,
independent workspaces, named views, keyboard/model actions, projection child
identity, outside-page reference lookup and the original Loader constructor.

Module integration must still freeze actual Chat policy/stars, bind every inner
filter/sort, adopt date/unknown semantics, project module summaries, and verify
their population/denominator/export parity. Existing legacy views remain clearly
page-local until migrated. Native/focus, screenshots, scaled/full wave suites,
packaging and independent review remain coordinator gates; headless component
tests do not claim those passes.
