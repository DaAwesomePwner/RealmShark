# History index core (D1a)

Implemented in `tomato.history.index`; no startup, Settings, SessionStore feed, or lifecycle wiring is installed by this package.
JSONL/checkpoint files remain authoritative. All mutations run on a single low-priority daemon writer. Query methods are
synchronous read-only operations for background callers, with independent WAL connections. They must not run on the EDT.

## Integration API

- Construct `HistoryIndex(SessionStore, boolean includeChat)` and call `start()`. Its future completes after initial backfill.
  The overload accepting an index path is for isolated testing and benchmarking.
- `state()` reports BUILDING progress, READY, or UNAVAILABLE. READY means the initial pass completed; check `ready(session)`
  or `sessionState(session)` for each scope before replacing its file fallback. A session can be failed, claimed, or stale.
  Skipped records and unknown/unindexed modules are reported per session.
- `listen(Consumer<State>)` returns a removable subscription. Callbacks run on the writer, so UI consumers dispatch to the EDT.
- After a successful source write, call `offer(session, module, byteOffset, null, detachedRecord)` for journals or
  `offer(session, module, -1, originalCheckpointKey, detachedRecord)` for checkpoints. Offers are bounded (8,192), processed
  in transactions of at most 2,000 operations on the one-second timer, and wake the writer at 2,000 pending operations.
  Overflow marks the session stale and reconstructs it from files. Objects must already be detached and must not be mutated.
- `markSessionChanged`, `removeSession`, `setIncludeChat`, and `rebuild` enqueue intent without doing I/O on the caller.
  `flush()` is an asynchronous completion barrier, mainly for tests. `closeAsync()` permits orderly background shutdown;
  ordinary `close()` initiates it without waiting. Pending offers are recoverable from source files on next startup.
- `search`, `runsPage`, `visitFacts`, and `rowCounts` use read-only connections. Query failures throw `SQLException` so a
  background client can keep its existing file fallback. Initial/native failures are contained in UNAVAILABLE.

## Source and privacy contracts

Schema version **3** rebuilds older versions automatically. `sessions.sid`, `visits.vid`, each source table's `id`, and
`players.pid` are integer primary keys. A session UUID is stored once in `sessions.id`; a visit ID is stored once per session
in `visits`. Source rows reference `sid`/`vid`, and modules use stable small integer codes. The public query maps and `Locator`
still expose the original string session, module and visit IDs. Source-row IDs returned in query maps retain the previous
locator-based form; SQLite rowids are private implementation details, not durable file identities.

Source uniqueness uses disjoint journal and checkpoint indexes: `(sid, module, byte_offset, item_position)` and
`(sid, module, checkpoint_key, item_position)`. Module distinguishes the three fame inputs; item position distinguishes
items/samples inside array checkpoints. Apart from these identities, secondary indexes cover exact `(sid,vid)` recaps and
the Runs time ordering and future Loot item time ordering. Unused time indexes on chat, timeline, key pops, fame, combat,
and loot bags were removed in version 3. Original message/event/recording IDs remain only where they cannot be derived from a file locator
(checkpoint filenames are one-way hashes), or are needed for message bookmarks and recording identity.

`player_occurrences` contains only integers: its rowid, `pid`, `sid`, `vid`, table code, source rowid, and time. Its two
secondary indexes are `(pid,time)` and `(sid)`. Names/normalized keys are interned in `players`; each source row retains its
own saved sender/player names. Only players affected by a transaction are recounted and reprojected. When a representative
source disappears, the remaining newest occurrence supplies its exact locator and saved spelling.

There is no `doc_ref`. Both FTS5 tables use `content=''` and `contentless_delete=1`; their rowids encode
`(tableCode << 48) | sourceRowid`. This removes duplicated source locators, FTS content tables, and text-key B-trees.
Tokens and positions remain indexed for prefix, phrase and trigram matching. Search decodes an address, joins the source
row to its session/visit, and renders the title and a bounded, approximately 240-character snippet from source columns.
Literal query matches are bracketed when present in that excerpt. A match on an indexed auxiliary value may show the
source's summary instead; snippets no longer depend on FTS storing a second copy of the document text.

Source deletion removes encoded FTS rowids **before** deleting the source rows. Session replacement, checkpoint re-offers,
player refresh, and chat exclusion use the same contentless deletion path inside their transaction. Pending FTS inserts
are flushed before replacing a source that may already have a queued document.

`Locator.checkpointKey` is the **on-disk filename stem**, not the preimage passed to `SessionStore.put`. Live offers convert
the original key with `SessionStore.checkpointName`; backfill takes the existing stem, including legacy import checkpoints
whose original keys cannot be recovered. Resolve it directly as `<session>/<module>/<checkpointKey>.json`; do not hash it
again. Module `session` is the special metadata address `<session>/session.json`. Journal offsets are UTF-8 byte offsets;
loot items, legacy fame samples and dungeon summary entries also carry their zero-based position in the source record.

The only retained account hash is `fame.account`, validated as exactly 64 lowercase hexadecimal characters. It supports
exact account/character joins, never player names. All other projected text redacts 64-hex substrings, and player discovery
rejects those names. No source JSON, equipment blobs, raw account IDs, or encounter-detail series are stored. Chat timestamps
remain their saved local wall-clock string; the numeric sort key encodes that wall clock without inferring a timezone.

Run outcomes share the extracted `RunOutcomeRule` with the Runs UI. Run durations/unknown damage follow
`ActivityQueries.visit`; timeline summaries reuse the Swing-free `ActivitySummaries`. `SessionFacts` and the ActivityQueries
adapter are UI-coupled, and LootFacts' bag conversion depends on package-private dashboard records. The projector therefore
reads their saved fields explicitly, preserving white/boosted-white, optional tier/enchant counts and exact visit rules.
Saved area names are preserved; no asset/dictionary lookup or canonical-name enrichment is performed in D1a.

Only closed sessions (`ended > 0`) enter startup backfill, newest first; `store.currentId()` is excluded. An explicit
`markSessionChanged` can reconstruct an open session for later live integration. Unknown modules and intentionally excluded
encounter detail/full binary folders are exposed in unindexed coverage. Unreadable records increment the skipped count;
a source or database failure aborts a whole replacement and keeps its previous committed rows. Claims use a 60-second
lease, periodic heartbeat updates, and SQLite's transaction write lock while replacing a session.
For open sessions (`ended <= 0` or the current session), a replacement commits its prefix snapshots even when the source
stamp changes during reading; offers arriving during replacement remain queued for idempotent upserts. Closed sessions
still require a stable post-read stamp. Failed sessions and SQLITE_BUSY/LOCKED work retry with exponential delays of
1, 2, 4 seconds up to 60 seconds; incoming offers do not reset that delay. Contention does not disable the index.
Matching schema versions do not perform schema writes. Removal holds a tombstone only until the deletion commits and
pre-removal queued offers are discarded, so the same UUID can subsequently be re-imported.
Change marks made after removal is requested are kept and processed after deletion commits, allowing an immediate re-import
of the same UUID to be indexed, while offers are still rejected as long as the tombstone stands. Turning chat off deletes its
rows/postings and refreshes affected players without invalidating stamps; turning it on invalidates and backfills.

Prepared statements and intern caches are reused throughout each writer transaction, then closed/discarded on either commit
or rollback. Initial backfill temporarily uses a 64 MiB SQLite cache and in-memory temporary storage; ordinary writer work
uses the normal cache/temp settings. FTS inserts are batched per session. Fresh builds, schema migrations and explicit
rebuilds optimize each FTS table once, then `VACUUM` returns freed pages to the filesystem. An existing index is compacted
at startup only if replacements exceed both 10 sessions and 25% of its indexed sessions; smaller incremental passes use
FTS automerge. Compaction is skipped when closing. Backfill timing includes any final compaction.

`realmshark.indexNativeDir` is the dedicated extraction override for isolated tests. Normally the native directory is a
sibling of `SessionStore.directory()`. It is created and `org.sqlite.tmpdir` is set before the driver's first use. Like other
process-wide native libraries, an already loaded SQLite DLL cannot be relocated later in the same JVM.

## Validation handoff

The coordinator validated version 2: **58 tests passed**, backfill **8.3 s**, database **55.3 MB**. Query medians were
**8.2 ms** (`oryx`), **5.4 ms** (frequent player), **2.7 ms** (`runsPage`), and **1.8 ms** (`visitFacts`). These measurements
use 52 closed sessions / 103 MB of history. Version 3 removes six unused time indexes, so its size will change; the coordinator
will update the measurement. No build, test, benchmark, or Git command was run by this worker for the final fix round.
The coordinator should rerun these JUnit 4 classes on Windows with JDK 17 / Gradle 7.6.4:

```
tomato.history.index.ProjectionsTest
tomato.history.index.JournalLinesTest
tomato.history.index.HistoryIndexTest
tomato.history.index.IndexUnavailableTest
tomato.gui.runs.RunOutcomeTest
```

The database tests create synthetic history and native extraction directories under temporary folders. Their privacy scan
checks every text-valued column, including FTS shadow tables, exempting only `fame.account`, for the fixture account hash.
The database tests require Windows natives; pure projection/prefix-reader and simulated-unavailability tests are portable.
Contentless tests check MATCH results and decoded FTS rowids after replacement, removal, and chat exclusion, rather than
testing FTS text columns (which now return null). The account privacy test also queries both token indexes for the fixture hash.

Run `tomato.history.index.IndexBenchmark <historyDir> <indexFile>` on the runtime classpath. It refuses existing database
files/sidecars and any output path inside the history tree, opens `SessionStore` read-only, and puts DLL extraction beside the
benchmark output. It prints table counts, skipped/failed-session counts, backfill time, final DB/sidecar bytes, and five-run
median query timings without printing player names or history contents.

The version-1 baseline was 189.3 MB and 87.9 s. Integer keys, contentless FTS and statement/batch reuse produced the version-2
improvements above. The remaining size target is at most 52 MB; removal of unused time indexes should reduce the 55.3 MB
result further. Version-3 regression coverage adds open-session append/upsert recovery, exponential retry, write-lock recovery,
incremental/closing compaction suppression, UUID re-import after removal, and deletion-only chat exclusion.
