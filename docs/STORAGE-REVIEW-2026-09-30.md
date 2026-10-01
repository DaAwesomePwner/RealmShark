# Storage and retrieval review (2026-09-30)

A backend review of how RealmShark saves and reads history: runs, timeline, loot, fame, chat, key pops, DPS recordings and the smaller stores. It covers size, write cost (ingestion), read speed and durability.

The review is based on the code at `4653fbd` plus measurements of one real history folder.

- **Real history measured:** 52 sessions, 103 MB.
- **Measurement method:** a benchmark that called the real `SessionStore` and `ReadSnapshot` classes against that folder in read-only mode, with all writes going to a scratch directory.
- **Machine:** fast local SSD with the OS cache warm, so treat the numbers as best case.

## Verdict

The write path is sound. Capture never waits for disk, checkpoints are temp-file plus atomic rename, journals tolerate a torn last line, and sessions are locked across processes. Keep that design.

The problems sit on both sides of it:

- **Size.** Most of the bytes are low-value diagnostic rows, and nothing is ever compressed or pruned.
  - 60% of `timeline.jsonl` is "Ownership check" and "Resources" diagnostics.
  - Journals compress 6–22× but are never compressed.
  - Only combat data has a retention policy.
- **Read layout does not match the read pattern.**
  - History is split by app launch. A single launch can last 100+ hours and hold 765 runs.
  - Readers ask for one run, or for "all runs".
  - So opening one run recap scans the whole launch's timeline, loot, fame and combat files.
  - Opening the Runs or Dungeons view after a restart parses all 1,506 run files, each opened twice.
- **No persistent derived index.** Five readers each keep their own in-memory cache, and nothing survives a restart:
  - Runs feed
  - Dungeons
  - Home
  - Loot Highlights
  - Fame history

  The Table/Explore views are worse: on every filter change they copy every journal to temp and SHA-256 it.
- **A few hot-path write bugs.**
  - Fame is rewritten about 4 times a second even when it hasn't changed.
  - The open run is rewritten every 2 s with no change check, deep-copied under the capture lock.
  - Each journal record opens and closes the file.
- **One real memory leak.** Every closed fight, including its full packet log, stays in memory for the whole app run.

None of this is a rewrite. The recommended plan is quick fixes first, then three structural additions:

- a per-session index written when a session closes;
- compression of closed sessions;
- byte-offset lookup by run.

## How it works today

| Data | Where | Format | Written | Read |
|---|---|---|---|---|
| Session metadata | `%LOCALAPPDATA%\RealmShark\history\<launch-uuid>\session.json` | JSON, `schemaVersion 1` | atomic, on start, close and availability change | `catalog()`: every session, on every read |
| Timeline, loot, chat, key pops, fame, boss observations | `<session>/<module>.jsonl` | JSONL, uncompressed | append; one open/close per record; 250 ms drain thread | full stream of the file; "pin" copies it to temp first |
| Runs (visits) | `<session>/runs/<uuid(visitId)>.json` | one JSON file per run | atomic; active run every 2 s; final copy at close | every file read twice (once to sort by `started`, once to parse) |
| Fame latest, dungeon totals, chat stars | `<session>/<module>/<uuid(key)>.json` | JSON | atomic | full read |
| Combat summary and detail | `<session>/encounters/`, `encounter-detail/` | JSON, a few KB each | at fight close, combat worker → store | Recordings tab parses all records per session (30-day default scope) |
| Combat full detail (opt-in) | `<session>/combat-full/*.dps` | Java serialization, uncompressed | temp + move, combat worker | only when the recording is opened |
| Character journal, plans, dungeon stats, preferences, bridge | launch folder (`Characters/`, `realmShark.properties`, …) | JSON / properties | each has its own copy of the atomic-write code | full load at startup |
| Quest pins | Windows registry (`java.util.prefs`) | registry | direct | startup |

Retention:

- Combat full detail is kept for 30 days by default.
- Combat summaries are kept forever by default.
- **Everything else is kept forever**, with no compression. That includes chat, which is the most personal data stored.

## What was measured

Data shape of the measured folder (aggregates only):

| Module | Rows | Raw | gzip | Ratio |
|---|---|---|---|---|
| `timeline.jsonl` | 153,463 | 48.5 MB | 2.2 MB | 22× |
| `runs/*.json` (1,506 files) | — | 24.2 MB | 3.0 MB | 8× |
| `chat.jsonl` | 35,820 | 14.0 MB | 2.3 MB | 6× |
| `boss-observations.jsonl` | 18,861 | 5.0 MB | 0.3 MB | 15× |
| `loot.jsonl` | 3,288 | 2.1 MB | 0.13 MB | 16× |

Notes on the data:

- **Timeline by kind:**

  | Kind | Size |
  |---|---|
  | Ownership check | 17.7 MB |
  | Resources (HP/MP samples) | 11.4 MB |
  | Inventory result | 9.4 MB |
  | Item request | 6.7 MB |
  | Everything else | about 3 MB |
- **Run file sizes:** median 2 KB, p90 64 KB, max 115 KB.
- **One launch dominates:** 107 hours, 765 runs, a 29 MB timeline.
- **Tiny files:** about 3,200 files are under 4 KB, including 1,200 import marker files. Each one costs a full disk cluster plus a per-file open on Windows.

Timings (warm-cache figures; the first pass was 20–40% slower):

| Operation | Time |
|---|---|
| `catalog()`, 53 sessions | 41 ms (cold: 170 ms) |
| Read all runs, 1,506 visits (Runs feed and Dungeons cold start, each) | **705 ms** (cold: 947 ms) |
| Run recap: scan the largest session's timeline to keep 4 events | **260 ms**, before loot, fame and combat |
| Pin all timeline: copy + SHA-256 of 47 MB, then parse | **1.15 s + 0.87 s** |
| Same 28 MB timeline as gzip, gunzip + parse | 227 ms (faster than parsing the raw file) |
| Same file, line scan only, no JSON parse | 43 ms |
| Append 20,000 records, open/close per record (current) | **4,167 ms** |
| Append 20,000 records, one channel, 64 KB batches | 4 ms |
| One atomic checkpoint rewrite (temp + rename) | about 1.2 ms |

These costs scale linearly with history. At 10× the history, the Runs feed and Dungeons each take about 7 s to cold-load, and Timeline Table filters take about 20 s.

## Findings

Severity:

- **P0:** user-visible or unbounded, so fix soon.
- **P1:** measurable cost, or a durability gap.
- **P2:** hygiene.

### P0-1: closed fights and their full packet logs never leave memory

**Evidence:**
- `TomatoData.dpsData` is an unbounded `ArrayList<DpsData>` (`TomatoData.java:231`).
- Every closed fight is added to it (`:1260`, `:363`).
- Each fight keeps `debugPackets = dpsPacketLog` (`DpsData.java:63`).
- That log holds every `NewTick` and `Update` packet of the dungeon, unconditionally (`TomatoPacketCapture.java:34,44,…`).
- Only "Clear DPS Logs" empties it (`DpsGUI.java:851`).

**Impact:**
- An app left open for days keeps every logged dungeon's packets and hit graphs.
- The measured history contains a 765-run launch.
- The summary and optional `.dps` file are already on disk, so the in-memory copy is redundant once saved.

**Fix:**
- Drop `debugPackets` at close unless "Save Debug Data" is on.
- Keep only the last N closed fights (for example 20) in memory. Older ones open from disk like any saved recording.

**Verification still needed:** a heap measurement on a long session.

### P0-2: one run recap scans the whole launch

**Evidence:** `RunRecapBuilder.java:162-176` reads all of the session's combat records, loot, fame and the **entire** `timeline.jsonl`, then filters by `visitId`.

**Impact:**
- Recap cost grows with the length of the launch, not the run: over 260 ms for the timeline alone in the 107-hour session.
- Nothing is cached, so it is paid on every open.

**Fix:** see S3 (byte-offset lookup by run). A cheaper interim step is to cache the parsed session facts for closed sessions, the way `SessionStamps` already does for the feed.

### P0-3: diagnostic rows are 60% of the timeline and are written forever

**Evidence:**
- `ActivityJournal.java:243` writes "Resources" rows.
- `ActivityJournal.java:300` writes "Ownership check" rows.
- In memory, `trim()` evicts these first (`:404`), which shows they are treated as low value.
- But `archiveEvents` has already journaled every one of them.

**Impact:**
- 29 of the 48 MB of timeline.
- Every timeline read, pin and recap parses them.

**Fix (needs a product call):** either stop journaling Ownership checks (diagnostic evidence for the summon-owner heuristic), or route both kinds to a separate `timeline-diagnostics` module. The Timeline view would read that module only when its Resources or Ownership filter is selected. Resources is already summarized in `Visit.resourceTimeline` for charts.

### P1-1: fame checkpoint rewritten about 4 times a second

**Evidence:**
- `Entity.fame()` runs on every status update of the player (`Entity.java:193`).
- It also computes a SHA-256 account key every time (`CharacterJournal.accountKeyOf`).
- `AppHistory.record` always calls `put("fame-latest", …)` (`AppHistory.java:64`). Only the journal append is change-gated (`:63`).

**Impact:** an atomic temp-file write and rename every 250 ms drain for the whole play session, about 1.2 ms of I/O each, plus Defender scanning on Windows.

**Fix:**
- Skip the `put` when `previous == fame`. This is a one-line change.
- Cache the account key per entity.

### P1-2: the open run is rewritten every 2 s, deep-copied under the capture lock

**Evidence:**
- The collector at `DiscoveryLog.java:82-86` runs `activity.activeVisit()` inside `synchronized (DiscoveryLog.this)`. That is a deep copy including timelines and rosters (`ActivityJournal.java:63`).
- It then calls `put` unconditionally. There is no revision check, although `Visit.revision` exists.
- `docs/STEP-2-RESPONSIVENESS.md` already notes that full copies "can contend with capture".

**Impact:**
- For a 64 KB run, that is 30 full rewrites a minute even while idle in a dungeon.
- The copy competes with packet processing.

**Fix:**
- Gate on the visit's revision.
- Lower the cadence to 5–10 s. The final copy at close is already exact, so only crash recovery depends on the interval.

### P1-3: each journal record opens and closes the file

**Evidence:** `SessionStore.persist` opens a `FileChannel` per record (`SessionStore.java:109-120`).

**Impact:**
- 208 µs per record against 0.2 µs batched: 1,000× slower.
- A 2,000-record drain (the budget) takes about 0.4 s of writer time, so the writer can fall behind during chat or inventory bursts.

**Fix:** in each drain, group records by `(session, module)`, then write once per group: one open, one buffered write, one close.

**Keep the torn-write rollback.** Truncate to the pre-batch size on failure, as the code does today.

### P1-4: a single bad write blocks all history writes, silently

**Evidence:**
- `drain()` retries the head of the queue forever (`SessionStore.java:88-108`).
- One deterministic failure stops every later event and checkpoint. Examples: a Gson exception on one object, or a session folder deleted underneath.
- The only signal is the `error` string, and nothing is logged.

**Fix:**
- Count failures per `Write`.
- After N attempts, move it to `<session>/rejected.jsonl` (or drop it with a counted, visible error) and continue.
- Bound the `events` queue too. It is unbounded during a persistent disk failure.

### P1-5: Runs feed and Dungeons cold-parse all history on every launch, twice

**Evidence:**
- `SessionStamps` caches are in memory only.
- `RunFeedSource` and `DungeonsSource` parse the same 1,506 run files independently.
- `SessionStore.read("runs")` opens every file twice: once for `started`, once to parse (`SessionStore.java:225-237`).

**Impact:** about 0.7–0.95 s per view today, growing linearly with history.

**Fix:**
- Short term: parse once and sort the parsed visits by `started`.
- Real fix: S1 (per-session index).

### P1-6: Table, Explore and legacy panels re-copy and re-hash all journals on every query change

**Evidence:**
- `ArchiveWorkspace.read` calls `ReadSnapshot.capture`, which copies every in-scope journal and checkpoint to `%TEMP%` and SHA-256s each copy (`ReadSnapshot.java:95-129`).
- It then parses byte by byte (`:179-192`).
- Any filter, sort, search or scope change re-pins (`ArchiveWorkspace.java:304`).

**Impact:**
- About 2 s for the Timeline table across all sessions at today's size.
- Four full passes over the bytes: read, write, re-read to hash, re-read to parse.

**Fix:**
- Closed sessions are immutable, so pin them by reference, validated by a (name, size, mtime) stamp, instead of copying.
- Copy only the current session's journal prefix, which `JournalPrefix` already handles.
- Keep the parsed row set across filter changes, and re-filter in memory.

### P1-7: Loot Highlights re-parses the whole current loot journal on each drop

**Evidence:** `LootHighlights.java:55` triggers a read on every bag-count change. `HighlightsSource` re-reads the full current-session `loot.jsonl` each time, which is O(B²) per session.

**Fix:** keep a byte offset for the current session and parse only the newly appended tail. The journal is append-only, so this is safe.

### P1-8: durability gaps on power loss

**Evidence:** there is no `FileChannel.force` or fsync anywhere. Checkpoints are written then renamed, and journals are appended without sync.

**Impact:**
- An app crash is safe.
- An OS crash or power loss can leave a zero-length or stale checkpoint, or lose the end of a journal.

**Fix:** sync only at meaningful points, so the cost stays negligible:
- `session.json` at close;
- the final checkpoint of a finished run;
- each journal at session close.

### P1-9: open fight lost on crash, and a slow queue drops fights at exit

**Evidence:**
- A fight is saved only at map change or capture stop (`docs/DPS-METERS.md:108-111`).
- On exit, `CombatAutosave.close` waits 15 s, then cancels the remaining queue (`CombatAutosave.java:141-160`).

**Fix:**
- Checkpoint a summary of the open fight every 30–60 s on the combat worker. It is small, so this is cheap.
- At exit, save summaries before full `.dps` files, so a slow full write never costs a summary.

### P2 findings

| Finding | Evidence | Fix |
|---|---|---|
| Dead `ActivityStore` thread polls every 200 ms for the live instance | `DiscoveryLog.java:72,303` | Don't construct it when `AppHistory` is attached |
| `DungeonStatData.save()` is synchronous on the capture thread and turns an IOException into a `RuntimeException`; platform charset; fixed `.tmp` name | `DungeonStatData.java:105-116` | Move it to a writer and use UTF-8 |
| The temp-file + atomic-move pattern is hand-copied about 12 times, and the copies differ (fallback or not, fixed or unique temp name, charset) | e.g. `SessionStore.java:126`, `CharacterJournal.java:726`, `PreferencesStore.java:77` | One shared `AtomicFiles.write(path, bytes, sync)` helper |
| Four corrupt-file policies (refuse, move aside, skip session, fail whole view) | `CharacterJournal`, `ActivityStore`, `SessionStore`, `RunFeedSource:306` | Per record: skip and count. Per file: preserve and report. Never fail a whole view for one session |
| One bad runs record fails the whole Runs feed | `RunFeedSource.java:306` | Skip and count, as Dungeons does |
| Recording summary row parses every record in the session to find one | `RecordingSummaryPanel.java:126` | `readCheckpoint(session, "encounters", recordingId)`: the key is known |
| Combat retention opens every record file at each startup and settings change | `CombatRetention.java:74-124` | Read ages from S1's index |
| `.dps` is uncompressed Java serialization that needs a 64 MB stack; a `StackOverflowError` silently drops full detail | `CombatAutosave.java:44,185` | Wrap in gzip (read side detects the magic bytes). Longer term, a flat damage-event format |
| No startup sweep of `.history-*.tmp` / `archive-pin-*` after a crash | `SessionStore.atomic`, `ReadSnapshot` | Sweep at startup, deleting anything older than 1 h |
| Asset cache generations are never garbage-collected | `AssetCache.java` | Keep the current and previous generations |
| 1,200 import marker files | `SessionStore.importSnapshot:267-280` | One marker per import source, or a manifest |
| `SessionEntry` copies metadata by JSON round-trip on construction and on every `session()` call | `SessionStore.java:476,480` | Make `Session` immutable, or copy once |

## Structural recommendations

These are ordered. Each one stands alone and keeps the current files as the source of truth, so every addition is a **derived, rebuildable** artifact. If one is missing or stale it is rebuilt, and the data itself is never at risk.

### S1: per-session index, written when a session closes

When a session ends (or on the first launch after a crash ends it), write `<session>/index.v1.json`. It holds the session's run projections, which are what the Runs feed and Dungeons already compute:

- visit id, map, start and end times, outcome;
- loot count and notable drops;
- fame delta;
- linked recordings;
- combat record ages.

It also stores the stamp of the files it was built from.

- **Readers:**
  - Runs feed, Dungeons, Home, Highlights, `FameHistory` and combat retention all read the index for closed sessions.
  - Only the current session is parsed live.
- **Effect:** cold start reads 52 small files instead of 1,506 run files plus loot, fame and encounters. The five independent caches collapse into one shared `HistoryIndex` service.
- **Invalidation:**
  - Closed sessions don't change, except through delete, rename, import or combat pruning. Those code paths already exist in `SessionStore`, so each one rewrites or deletes the index.
  - An index whose stamp doesn't match is rebuilt.

### S2: compress closed sessions

At the same point as S1, gzip each closed session's `*.jsonl` to `*.jsonl.gz`:

1. Write to a temp file.
2. Verify it.
3. Rename it into place.
4. Delete the original.

Readers accept either form.

- **Size:** 103 MB → about 10 MB at today's ratios.
- **Speed:** parsing is the same or faster (227 ms gzip against 260 ms raw).
- **Pins:** copies shrink 6–22×, if pins still copy at all after P1-6.
- The current session stays plain JSONL, so appends and torn-tail tolerance are unchanged.

### S3: byte-offset lookup by run

The writer knows each record's byte offset when it appends. Record visit id → offset ranges for each of `timeline`, `loot`, `fame` and `boss-observations`:

- in memory, for the current session;
- in the S1 index, for closed sessions.

A recap then reads only its run's ranges, so its cost no longer depends on how long the launch was. With S2, either keep the offsets for the uncompressed form and decompress the full file (still fast, 227 ms worst case), or gzip in independent blocks per run if recap latency matters more.

### S4 (optional, later): embedded SQLite as the query index

If cross-session search and analytics become first-class, build a SQLite (WAL) database next to the history from the JSONL, as a rebuildable cache. Examples: full-text search over all chat, or "every drop of item X across 2 years".

- **Gain:** indexed queries and FTS5.
- **Cost:**
  - a native-library dependency (`sqlite-jdbc`, about 13 MB);
  - schema migrations;
  - a second consistency story.

**Not recommended yet.** S1–S3 deliver most of the speed with no new dependencies at the current data volume.

### Retention policy (product decision)

Today, combat data is the only thing that ever expires. Suggested Settings › History controls, with defaults that change nothing until the user opts in:

- **Chat** (personal content, 14 MB here): forever / 1 year / 90 days.
- **Diagnostic timeline** (if P0-3 splits it out): 30 days by default, since it is debug evidence.
- **Everything else:** forever, compressed.

## Suggested sequencing

1. **Small PR, low risk.** P1-1, P1-2, P1-3, P1-5 (single parse), P2 dead thread, P2 recording summary lookup, P2 runs-feed skip-and-count.
2. **P0-1 memory cap.** Needs a heap measurement before and after.
3. **P1-4 poison-pill and queue bound, P1-8 targeted sync, shared atomic-write helper.**
4. **S1 index plus shared `HistoryIndex` service.** This is the largest read-speed win.
5. **S2 compression** and **P1-6 pin-by-reference.**
6. **S3 offsets**, then **P0-3** and retention, once the product calls are made.

## Decisions

On 2026-09-30 the user answered the open questions below; the delivery plan is in [STORAGE-PLAN.md](STORAGE-PLAN.md). In summary:

- **Ownership checks:** all 62,602 saved checks agreed, so only conflicts will be journaled.
- **DPS memory:** keep the last 20 closed fights.
- **Chat retention:** becomes a setting, defaulting to Forever.
- **Cross-history search:** this is the goal, so a search index replaces S1 and S4 becomes the destination.

Since this review, PR #41 has started showing the store's save error in the app shell. P1-4 is therefore about the stuck queue, not about the error being invisible.

## Open questions (answered above)

1. Is "Ownership check" evidence still needed after summon-owner attribution shipped? Can it move to a diagnostics module or be dropped?
2. Should the in-memory DPS list keep only the last N fights, and if so how many? "Save checked" already reads fights from that list.
3. Are retention controls for chat wanted at all, or is "keep everything" a product requirement?
4. Is cross-session search or analytics planned? That decides whether S4 is ever worth it.
