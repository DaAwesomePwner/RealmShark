# Wave 2D Activity, Inspect archive and resource history

Base: `c5d9381`; isolated branch `work/ux-w2-activity`.

## Query and evidence package

`tomato.gui.activity.ActivityQueries` implements the real archive adapter for
Runs, Timeline and Resources & buffs. Runs and Inspect share the dungeon visit
contract; Resources includes all areas. Predicates and raw numeric/date ordering
run before paging (100 visits / 1,000 events). The origin envelope retains the
source session and storage reference. Visit rows omit resource samples, conditions
and roster payloads; `readVisit(lease, row, cancellation)` retrieves the exact full
record from the original pin, matching both source Ref and recorded visit ID.

Visit facets: multi-outcome, multi-evidence-source, inclusive duration limits in
milliseconds, capture issues and timing gaps. Left/unconfirmed remains distinct
from Completed; legacy Completed without evidence is **Not observed**, not a
fabricated victory. Unknown duration never satisfies a numeric range. Bounds are
half-open; entry is the default, observed overlap is explicit. Missing/zero legacy
timestamps are unknown; interval bounds never invent an end.

Timeline facets: exact multi-type selection, Assigned / Unassigned and optional
exact session + visit ID. Assignment means a recorded nonempty visit ID, not a
verified cross-module identity. Matching assigned/unassigned counts cover the
whole query. Human summaries explain entry, equipment, requests, progression,
capture problems and unknown fields before raw JSON. Unknown event kinds remain
searchable and retain their details. No timestamp/map-name joins are performed.

`SelectedRunExport.preview(lease, ref, cancellation)` verifies the selection in the
matching result and counts independent Timeline records from the same pin.
`SelectedRunExport.write(...)` streams one full visit and only its exact
session/visit-linked events to JSON or CSV, preserving each origin. Event dates
and types are not clipped to the visit query. The manifest declares this policy,
query/revision/source cuts, visit/event counts and linkage availability. An absent
visit ID exports no linked events. A lease survives owner closure and source edits.
Outputs exclusively claim collision-safe names; cancellation/failure cleans staging.

The foundation's page/all-match exports contain lightweight visit summaries or
complete event projections. This keeps the table and export population identical
without materializing all rosters/resource timelines in Swing.

## Bounded evidence

Initial adapter package: **8 headless tests passed**, no failures/errors/skips,
using JDK 17 / Gradle 7.6.4 with main `--release 8`. Synthetic fixtures cover 240
visits, 2,305 events, global filters/order/page parity, exact linked export of
1,007 events, duplicate visit IDs across sessions, post-pin source mutations,
entry/overlap/unknown bounds, lightweight rows with full leased details, unknown
summaries, cancellation and filename collisions. Evidence directory:
`build/w2-activity/reports/tests/test`.

Workspace wiring and state-validation handoff follows in the next bounded package.
