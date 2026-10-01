# Search index design

This is the design checkpoint between plan steps C and D in [STORAGE-PLAN.md](STORAGE-PLAN.md). It needs the user's approval before D starts.

Inputs:
- [STORAGE-REVIEW-2026-09-30.md](STORAGE-REVIEW-2026-09-30.md), with measurements of real history;
- a read-only inventory of every history module, record field, search surface and lifecycle event at `5e7a9d8`;
- current engine facts (sources at the end).

## Goal

Search everything saved, across all sessions, quickly. Make every history screen fast without re-reading every file.

**Search scope:** runs, loot, timeline, chat, key pops, fame and combat.

**Speed targets on today's history (52 sessions, 103 MB):**

| Operation | Target |
|---|---|
| Global search | under 200 ms |
| Runs feed / Dungeons cold open | under 150 ms |
| Run recap | under 100 ms |

Each target must still hold at 10× the history.

**Non-goals:**
- **Replacing the saved files.** JSONL journals and JSON checkpoints remain the source of truth.
- **Changing what is captured or saved.**
- **Network or cloud search.** Everything stays on the machine.

## Principles

1. **The index is a derived cache.**
   - Deleting it is always safe; it is rebuilt from the session files.
   - No feature depends on data that exists only in the index.
2. **Screens never wait for the index.** Until the sessions a view needs are indexed, that view keeps using today's file readers and shows "Indexing history… N%".
3. **Exact links only.**
   - Runs join on (session, visit id), combat on recording id, and fame on (account hash, character).
   - Nothing is matched by nearest time or dungeon name, which is the codebase's existing rule.
4. **Every row points back to its source.**
   - Each row carries (session, module, locator). The locator is a journal byte offset or a checkpoint key, plus the item position where needed.
   - Lists render from the index. Opening a record reads it from its file, seeking straight to the offset.
5. **Privacy follows the source.**
   - The index holds nothing the files don't already hold.
   - Deleting history removes it from the index, with secure-delete enabled so deleted text isn't left in free pages.

## Engine choice

| | **SQLite FTS5** (`sqlite-jdbc` 3.53.4.0, Aug 2026) | **Lucene 9.12** | **H2 2.x** | **No engine** (custom files) |
|---|---|---|---|---|
| Runs on the app's Java 17 | Yes | Only on the 9.x maintenance line, last release 9.12.3 in Sep 2025. Lucene 10 needs Java 21; 11 will need Java 25. | Yes | Yes |
| Added size | 248 KB jar + 2.3 MB Windows natives (+1.3 MB macOS). The all-platform jar is 12 MB. | About 4–6 MB (core, analysis, queryparser) | About 2.6 MB | 0 |
| Native code | Yes: one DLL, loaded like the existing JNA/pcap natives | No | No | No |
| Full-text quality | Good: unicode tokenizer, prefix queries, trigram substring, bm25 ranking, snippets | Best | Basic built-in; its Lucene option brings Lucene back | Whatever we write |
| Structured queries (filters, counts, group-by for Dungeons, Loot, Home) | Native SQL | Weak; needs a second store | SQL | Hand-written |
| Two app instances at once | WAL mode: many readers, serialized writers | Single writer lock per index | Weaker multi-process story | Hand-written |
| Crash safety | Transactional | Transactional commits | Transactional | Hand-written |
| Corruption recovery | `quick_check`, then delete and rebuild | Delete and rebuild | Delete and rebuild | Delete and rebuild |

**Recommendation: SQLite FTS5.** It is the only option that gives both real full-text search and the structured filters, counts and group-bys that the Runs, Dungeons, Loot and Home screens need, from one small file. Only the Windows natives ship.

The native library isn't extracted to `%TEMP%`. `org.sqlite.tmpdir` points at `%LOCALAPPDATA%\RealmShark\native\`. The jlink runtime already includes `java.sql` through `java.se`.

## What gets indexed

There is one database: `%LOCALAPPDATA%\RealmShark\history\index\search-v1.db`, with its `-wal` and `-shm` files.

| Table | Source | One row per | Key columns | Full-text |
|---|---|---|---|---|
| `sessions` | `session.json` | session | id, label, version, started, ended, source stamp, index state | label |
| `runs` | `runs/` checkpoints | visit | session, visit id, map, started, ended, outcome, duration, damage, players, issues | map, status, roster names and classes, equipment names |
| `loot_bags`, `loot_items` | `loot.jsonl` | bag / item in bag | session, offset (+ item position), visit, time, dungeon, bag, tier, rarity, enchant slots and applied | item name, dungeon, dropper, **enchant names** (today's search lacks these) |
| `timeline` | `timeline.jsonl` | event | session, offset, visit, time, kind | detail and enriched values, **except** the diagnostic kinds |
| `chat` | `chat.jsonl` | message (UUID) | session, offset, received (local time, as saved), channel, sender, recipient, own / ignored | text, sender, recipient |
| `chat_stars` | `chat-stars/` | star change | message id, session, changed, starred | — (the cross-session winner is computed by query, so deleting a session correctly restores an older state) |
| `keypops` | `keypops.jsonl` | pop (UUID) | session, offset, time, kind | player, item |
| `fame` | `fame.jsonl`, `fame-latest/`, legacy `fame-snapshots` | sample | session, account hash, character, time, fame, visit | class, map |
| `combat` | `encounters/` (+ `encounter-detail/` on demand) | recording | session, recording id, visit, map, times, damage, deaths | map, player names, boss names |
| `dungeon_totals` | `dungeon-totals/` | session × dungeon | counters | dungeon, enemy and item names |
| `meta` | — | — | schema version, dictionary versions, build | — |

**Not indexed:**
- `encounter-detail` series. They are read from their file when a recording is opened.
- The `combat-full/*.dps` files. These are Java-serialized object graphs that are not a safe search projection.
- Unknown modules, such as the stray `boss-observations` journals left on disk by an unmerged experiment; nothing in the code writes them.
- Unknown modules show as "not indexed" coverage. They are never silently dropped.

**Full-text design:**
- One FTS5 table `docs(kind, title, body)` with the `unicode61 remove_diacritics 2` tokenizer and `prefix='2 3'`. Its rowid joins to a `doc_ref(kind, row)` table.
  - Word and prefix queries hit it directly: `abyss`, `"oryx sanctuary"`, `cr*`.
- A second FTS5 `trigram` table covers only short name fields (items, players, dungeons, bosses). It keeps today's "contains" behavior there: `ancient` matches "Ancient Stone Sword" and `oryx` matches "Oryx the Mad God 2".
- Ranking uses bm25 within each result type, newest first as the tiebreak.

**Dictionary enrichment:**
- Loot already saves item names.
- Enchant names (`ParseEnchants`), class names, enemy names and equipment names (`IdToAsset`) are added at index time.
- `meta` records the asset generation and enchant-definition version. When the game assets update, affected text is re-derived in the background. The stored ids never change.

**Size (measured in D1a, schema v3, on a read-only copy of the real history: 52 closed sessions, 103 MiB):**

| Measure | Result | Target |
|---|---|---|
| Index size | **51.2 MiB** (49.7% of the raw history) | under 50% |
| Full backfill | **7.8 s** | |
| search "oryx" | 6.8 ms | under 200 ms |
| search a frequent player | 4.8 ms | under 200 ms |
| first Runs page | 2.2 ms | under 150 ms |
| one run's recap facts | 1.3 ms | under 100 ms |

With D2 name enrichment (36,408 object names, 1,016 enchants and 19 classes, schema v6), the same copy measured **55.5 MiB** and a **8.6 s** backfill, with queries of 1.4–9.3 ms.

The first layout used text keys and a separate locator table, and measured 189 MB. Integer keys and contentless FTS5 brought it to the size above. Compressing closed sessions (G) shrinks the raw history, but not the index.

## How data gets in

```
capture → SessionStore queue → drain (append / checkpoint written OK)
                                   └→ IndexFeed: (session, module, locator, record)
                                         └→ index writer thread: one transaction per ~1 s or 2,000 ops
```

- **Live ingestion.**
  - `SessionStore` passes each record to the index only **after** the file write succeeds. The batch writer already knows each record's byte offset.
  - Checkpoints such as the live run and `fame-latest` are upserts by key.
  - The index writer is a separate daemon thread with a bounded queue. If the index falls behind or fails, the session is marked "stale" and catches up from the files. Capture and history saving never wait on it.
- **Backfill.**
  - At startup, every closed session whose stamp (file names, sizes, modification times; the same rule as `SessionStamps`) differs from the index is re-indexed newest first, on a low-priority background thread.
  - Each session is replaced in one transaction: delete its rows, insert new ones. A half-indexed session is therefore never visible.
  - Expected time on today's history: well under a minute, measured in D1.
- **Lifecycle.**
  - **Session delete** removes the session's rows.
  - **Rename** updates the label.
  - **Legacy import** indexes the imported session.
  - **Combat retention pruning** removes the pruned recordings.
  - **Session close** re-projects run outcomes, because closing turns unfinished runs into "App ended".
  - Each of these is an explicit call from the code that makes the change. As a safety net, the startup stamp check catches anything missed, including another instance's changes.
- **Schema change or corruption.**
  - `meta.schema_version` mismatch → rebuild in the background.
  - Open failure or `PRAGMA quick_check` failure → move the file aside as `search-corrupt-<time>.db` and rebuild.
  - Settings › History gets a **Rebuild search index** button.
- **Two instances.** WAL mode with `busy_timeout`. Each instance writes only its own session's rows, and backfill takes a per-session claim row so two instances never index the same session.

## How screens use it

The order is from the most painful today to the least:

1. **Run recap (E1).** It fetches the run's timeline, loot, fame and combat rows by (session, visit) instead of scanning the whole launch. Today that takes 260 ms+ on the 107-hour launch.
2. **Runs feed and Dungeons (E1).** Paged SQL queries with filters and sort. Cold open no longer parses 1,506 files twice.
3. **Loot Explore, Loot Highlights and Home (E2).** Indexed filters and counts. Enchant names become searchable.
4. **Timeline, Chat, Key pops and Statistics tables (E3).** These replace the copy-and-hash "pin" reads, about 2 s per filter change today, with indexed queries. The temp-copy machinery is retired.
5. **Search page (F).**
   - One search box across everything, with type chips (Runs · Loot · Chat · Timeline · Key pops · Players), a date range and a session scope.
   - Results are grouped by run where linked, with highlighted snippets.
   - Every result opens its exact source: the run recap, the chat message in context, the loot row.
   - A shell-wide Ctrl+K shortcut opens it with the box focused.

Each screen keeps its current reader as the fallback until the index reports ready for its scope. The fallback is removed only after the index path has shipped and been verified.

**Interaction with compressing closed sessions (G).** A row needs a byte offset to open its exact record. When a closed session's journals are compressed, they are written as independent gzip members, one per ~64 KB block. The index stores (member offset, offset within member), so an exact record read decompresses one small block, not the whole file.

## Delivery

| PR | Contents | User-visible |
|---|---|---|
| D1 | Dependency and native loading; `HistoryIndex` (all module projections, including the `players` table) (open, schema, migrate, corruption recovery); writer thread; live feed from `SessionStore`; backfill with stamps; rebuild command; benchmark against a read-only copy of real history | Settings › History: index status + Rebuild |
| D2 | Lifecycle hooks (delete, rename, import, prune, close, stars); dictionary enrichment and versioning; secure-delete; trigram name index | None |
| E1 | Recap, Runs feed and Dungeons on the index, with fallback | Faster |
| E2 | Loot Explore, Loot Highlights and Home on the index; enchant-name search | Faster, better loot search |
| E3 | Timeline, Chat, Key pops and Statistics tables on the index; pins retired | Faster filters |
| F | Search page | New |
| G, H | Compression (block-gzip) and retention settings, unchanged from the plan, now aware of the index | Smaller history |

Every PR keeps the existing focused tests green and adds its own. D1 records measured sizes and timings in this document.

## Risks

| Risk | Mitigation |
|---|---|
| The native DLL is blocked by antivirus, or fails to load | The index is optional: every screen falls back to file readers and Settings shows "Search unavailable: …". Nothing is lost. |
| The index drifts from the files | Explicit lifecycle hooks, plus the startup stamp check, plus Rebuild. |
| The index grows large | Diagnostic timeline kinds are excluded; only projections are stored, never full records; size is measured in D1 against the target. |
| Two instances | WAL, busy timeout, per-session writers and backfill claims. |
| Chat text in a second file | It lives in the same folder as the chat journal, with the same deletion rules and secure-delete. A setting excludes chat from the index. |

## Decisions (user, 2026-10-01)

| Question | Decision |
|---|---|
| Engine | **SQLite FTS5**, with **Windows natives only**: `sqlite-jdbc` `without-natives` + `natives-windows` (+2.6 MB). On another OS the native library is missing, the index reports "Search unavailable", and screens use the file readers. |
| Chat | **Indexed**, local only. Settings › History › **Include chat in search** is on by default. Turning it off deletes chat rows from the index (secure-delete); turning it on backfills them. |
| Timeline diagnostics | "Resources", "Capture issue" and ownership-conflict rows are **not indexed**. They stay in the files and in the Timeline table's kind filter. |
| Search priorities | **Runs and players first.** They are the default result types and are ranked first. Players are a derived `players` table built from run rosters (inspected players), combat recordings, key pops and chat senders. Each entry has a name and its occurrences, keyed by name as saved, with no account identifiers. Loot, chat, timeline and key pops follow. |
| Placement | **Both:** a Search page in the sidebar, and a shell-wide **Ctrl+K** shortcut that opens it with the box focused. |

## Sources

- [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc): FTS5 compiled in; native extraction configurable through `org.sqlite.tmpdir` / `org.sqlite.lib.path`; Apache-2.0 / BSD-2.
- [Maven Central: sqlite-jdbc 3.53.4.0](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/): all-platform jar 12.0 MB; `without-natives` 248 KB; Windows natives 2.3 MB; macOS natives 1.3 MB.
- [Lucene 10 system requirements](https://lucene.apache.org/core/10_0_0/SYSTEM_REQUIREMENTS.html) (Java 21) and [lucene#14229](https://github.com/apache/lucene/issues/14229) (Lucene 11 → Java 25); [Maven Central: lucene-core](https://repo1.maven.org/maven2/org/apache/lucene/lucene-core/), last 9.x release 9.12.3 (2025-09-27).
