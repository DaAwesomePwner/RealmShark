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

## Validation in progress

Focused archive regressions passed. Adoption tests initially exposed two compact
Chat fixtures assuming simultaneous expanded-filter and message visibility; updated
to exercise drawer collapse and scrolling. No failed or interrupted run is a pass.
Final focused evidence, build/launch check and independent review are pending.

## Deferred scope

The P1c plan retains dynamic/per-render tabs, Notifications tabs, column tools in
overflow, relative timestamps and automatic Analyst column visibility for later
phases. Logging/Bridge/DPS meter and remaining Statistics filter rows are P5/P6 work.
Live archive-backed pages retain a separate workspace scope row above their own
filter bar. No capture, real personal history or bridge delivery is used for validation.
