# P1c validation and coverage

Base: P1b PR #19 merged as `0abafe4`, including planning correction `a011bfd`,
pulled before implementation. P1c continues on `claude/realmshark-ui-ux-redesign-cb0914`.

## Coverage

- Archive workspaces: filter drawer, removable facet/date chips, overflow saved views
  and exports, paging/status footer; unchanged query and export contracts.
- Live Runs/Timeline/Resources, Chat, Key-pops, Loot, party roster, Characters and
  Quests: FilterBar adoption with existing facet behavior.
- Characters/detail, Inspect, Quests, Bridge, DPS/Resources, Logging, Key-pops and
  Statistics: customizable tabs. Snapshot evidence and Ability Use require Analyst.
- Archive and ad-hoc tables: shared ColumnKind widths, header floors and saved-layout
  precedence. Statistics selection remains canonical across reordering.
- Tests that change the application display mode restore it in `@After`.

## Local validation

JDK 17 and Gradle 7.6.4, offline dependencies, isolated `build/p1c` and `build/p1c-cache`,
synthetic history and in-memory Java preferences. App preferences are under the test
working directory; evidence fixtures explicitly restore saved view/drawer preferences.

- Archive-focused checks passed, including query retirement, cancellation, saved
  views, export leases and drawer/chip semantics (`.tools/p1c-archive.log`).
- The affected GUI package sweep ran **475 tests**. Eight failures were resolved:
  five Character fixtures inherited a saved evidence filter, two tests assumed old
  always-visible filter controls/labels, and a real 24pt compact Quest search field
  clipped by seven pixels. Preferences are now isolated/restored, tests open drawers
  or scroll deliberately, and WrapRow bounds oversized controls to available width.
- The **23-test layout follow-up passed**, resolving those eight failures
  (`.tools/p1c-layout-fixes.log`). Earlier Chat compact checks also passed after
  separating expanded-filter access from message viewport checks.
- Independent review found hidden-tab restoration and text-only archive Clear
  omissions. Both are fixed: saved views/Back reveal hidden tabs without bypassing
  Analyst gating; a short removable Search active chip exposes Clear. No saved
  selection means the current tab is retained. Statistics numeric state maps to
  canonical IDs even after reorder/hide.
- **27 review-focused tests passed** (`.tools/p1c-review-fixes.log`). A subsequent
  17-test run had one incorrect fixture assumption about initial tab selection;
  that fixture now explicitly selects its starting tab. Final **3 affected tests
  passed** (`.tools/p1c-final-corrections.log`).
- Consolidating the newest result per test across the affected sweep and follow-ups
  gives **476 unique tests, zero outstanding failures/errors/skips**. This is not a
  claim that the full suite ran on the final head. The preserved XML sets and
  `build/p1c/evidence/latest-case-results.json` retain the evidence lineage.
- FilterBar evidence passed with **48 screenshots** (six pages, 1240x800/680x520,
  fonts 13/18, open/closed). Coordinator inspected representative captures; independent
  reviewer inspected all 48. Compact/open views use outer scrolling. Populated history
  and live semantics are also covered by the existing affected native/functional tests.
- `shadowJar` passed after final source corrections. BuildContractProbe verified
  isolated version identity, Java 17 class headers and UnityPy notice. Isolated JAR
  `--help` exited 0. A first smoke command had an incorrectly quoted PowerShell JVM
  property; corrected invocation passed (`.tools/p1c-jar-contract.log`, smoke/help.log).

No full-suite rerun, scaled UI task, packaging cycle, display reconfiguration or CI
dispatch was performed. Failed runs above are diagnostic history, not passes.
Final-head independent approval is recorded on the PR; merge remains pending.

## Deferred scope

The P1c plan retains dynamic/per-render tabs, Notifications tabs, column tools in
overflow, relative timestamps and automatic Analyst column visibility for later
phases. Logging/Bridge/DPS meter and remaining Statistics filter rows are P5/P6 work.
Live archive-backed pages retain a separate workspace scope row above their own
filter bar. No capture, real personal history or bridge delivery is used for validation.
