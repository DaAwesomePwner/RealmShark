# Storage and search plan

This plan puts [STORAGE-REVIEW-2026-09-30.md](STORAGE-REVIEW-2026-09-30.md) into practice.

## End goal

Fast, thorough search across all saved history:

- runs
- loot
- timeline
- chat
- combat

The saved session files stay the source of truth. Everything added on top of them (indexes, caches, compressed copies) is derived: it can be rebuilt from those files at any time.

## Decisions (user, 2026-09-30)

| Question | Decision |
|---|---|
| Ownership check timeline rows | Keep only conflicts. Across 62,602 saved checks there were 0 conflicts. The agree/conflict counters on each run stay. |
| Closed fights kept in memory | The last 20. Older fights are opened from saved history. |
| Chat retention | Add a setting. The default stays Forever. |
| Cross-history search | This is the end goal. A search index is planned, so there is no interim per-session JSON index. |

## Delivery

Each item below is one PR to `main`.

- **Who does what:** Codex implements in an orchestra worktree. Claude verifies the work, runs the focused tests and obtains a cross-family review, then the PR is opened. The user smoke-tests on their own machine. CI is manual only.
- **On-disk format changes:** "read the new format" merges before "write the new format". Nothing rewrites existing history until a reader for the new form has shipped.

| PR | Scope | Depends on | Status |
|---|---|---|---|
| A | Write-path fixes | — | Merged (#42) |
| B | DPS memory cap | — (in parallel with A) | Merged (#42) |
| C | Read fixes and safety | A | Merged (#43) |
| — | Design checkpoint: search index ([design](STORAGE-SEARCH-DESIGN.md)) | A, C | Approved 2026-10-01 |
| D | Index foundation: D1 merged (#44); D2 lifecycle hooks + name enrichment in the D2 PR | design | D2 in PR |
| E | Move screens to the index (probably 2 PRs) | D | |
| F | Search page | E | |
| G | Compress closed sessions | D | |
| H | Retention settings | A | |

### A: write-path fixes

- **Fame:** the `fame-latest` checkpoint is written only when its content changes, or at most every 30 s while it stays unchanged. Today it is written on every player stat update.
- **Active run:** the open-run checkpoint is written only when the run's revision has changed since the last write.
- **Journal batching:** journal appends are batched per file per drain, instead of one open and close per record. A failed batch rolls back to the file's size before the batch.
- **Unwritable records:** a record that cannot be serialized is skipped and counted, instead of blocking the queue forever. Disk errors still retry.
- **Pending-event cap:** pending events are capped. Records dropped by the cap are counted and reported.
- **Legacy writer:** the `ActivityStore` writer thread starts only when something is actually offered to it.
- **Ownership check rows:** a timeline row is written only when the check finds a conflict. Agreements only increment the run's counters.

### B: DPS memory cap

- **Fight list:** `TomatoData.dpsData` and the encounter catalog keep the 20 most recent closed fights. The fight on screen is never evicted.
- **Packet logs:** only the 3 most recent closed fights keep their debug packet log. Exporting an older fight with "Save Debug Data" says that its packets were not kept.
- **Evicted fights:** an evicted fight still appears in Recordings from its saved summary, when history is being saved.
- **Recording summary panel:** reads one record by its key instead of parsing every record in the session.

### C: read fixes and safety

- **Run checkpoints:** each runs checkpoint is read once (parse, then sort), not twice.
- **Runs feed:** one unreadable run record is skipped and counted. It no longer fails the whole feed.
- **Loot Highlights:** reads only the new tail of the current session's loot journal.
- **Startup sweep:** at startup, stale `.history-*.tmp` files and `realmshark-archive` scratch folders older than 1 hour are deleted.
- **Atomic writes:** one shared atomic-write helper replaces the hand-copied variants (UTF-8, unique temp name, fallback move).
- **Durability:** the final checkpoints, the journals and `session.json` are synced to disk when a session closes.
- **Dungeon stats:** `DungeonStatData` saves off the capture thread and no longer throws there.

### Design checkpoint: search index

Short design document, to be approved by the user before D starts.

- **Engine:** SQLite FTS5 (`sqlite-jdbc`), a pure-Java search library such as Lucene, or H2. Compare them on:
  - dependency size
  - native code
  - Windows portability
  - full-text search quality
  - query flexibility
- **Data model:** the schema for runs, loot, timeline, chat, combat records and fame.
- **Ingestion:** live from the current session, and backfill from closed sessions.
- **Upkeep:** rebuild, versioning, and corruption recovery (deleting the index must always be safe).
- **Consistency:** keeping the index in step with deletes, imports, renames and combat pruning.
- **Migration:** which screens move to the index, and in what order.

### D–H

- **D:** build the index next to the history and feed it from the session store's writer. Include a rebuild command. No screen changes.
- **E:** move the Runs feed, Dungeons, Home, Highlights, the run recap and Table/Explore to the index. Retire the copy-to-temp pins.
- **F:** a global search page.
- **G:** gzip closed sessions' journals into `*.jsonl.gz`. Readers accept both forms first, then writing switches over.
- **H:** add Settings › History retention, with chat defaulting to Forever. Diagnostic timeline kinds get their own retention.
