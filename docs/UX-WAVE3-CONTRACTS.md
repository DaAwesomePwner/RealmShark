# Wave 3 connected-analysis preparation

Status: PROVISIONAL READ-ONLY DESIGN, pending Wave 2 merge and successful main verification.
Prepared 2026-09-24 against the resumed Wave 2 checkout. No Wave 3 implementation or branch is authorized to start before its predecessor gate. Reconcile current code before implementing these proposed APIs. This portable preparation note is not completion evidence or a checkpoint replacement.

## Critical capture ordering

Source pointers:
- `src/main/java/packets/packetcapture/PacketProcessor.java`, `processPackets`: `DiscoveryLog.observe` precedes `Register.emitPacketLogs`.
- `src/main/java/packets/packetcapture/logger/ActivityJournal.java`, `observePacket`: clean MAPINFO finishes the old visit and creates the new visit.
- `src/main/java/tomato/backend/TomatoPacketCapture.java`, MAPINFO branch: calls `TomatoData.setNewRealm`.
- `src/main/java/tomato/backend/data/TomatoData.java`, `setNewRealm`/`clear`: clears the outgoing encounter; `clear` currently resets `worldPlayerId` and `charId` before constructing `DpsData`, although the `player` reference remains available until afterward.

Therefore reading `DiscoveryLog.currentVisitId()` inside `clear()` would attach the outgoing encounter to the incoming visit. Consecutive identical dungeon names must never be reconciled using names or nearest timestamps.

Proposed coordinator-owned serializable detached values:

```java
final class VisitRef implements Serializable {
    final String sessionId; // SessionStore.currentId(), not the journal UUID
    final String visitId;   // ActivityJournal.Visit.id
}
final class EncounterContext implements Serializable {
    final VisitRef visit;                // optional, verified at entry
    final Integer localPlayerObjectId;   // optional, within this encounter only
    final long capturedAt;
}
```

`SessionStore.currentId()` and the UUID prefix of `ActivityJournal.Visit.id` are separate identities. Preserve both explicitly.

Propose `DiscoveryLog.visitForMap(MapInfoPacket packet)` returning a reference only when the exact clean MAPINFO object established the current active visit. Keep the packet identity ephemeral, clear it on boundary/pause/clear, and return no link for disabled collection or partial MAPINFO. No packet bytes or strings are persisted for this check. In `setNewRealm`, finish the old encounter using its entry-frozen reference, call `clear`, and only then capture the incoming map reference. At the start of `clear`, capture the local player object ID before identity fields reset; require its agreement with the capture-owned player. Capture time is the producer time, not dialog-open time.

Extend `DpsData` with optional context, retain its existing serialVersionUID, and preserve context in `getSaveFile`. Existing `LocalPlayerContext` only stores class/guild; it cannot identify a verified local row. `CombatMeterData` aggregates outgoing `Damage.owner.id` and stores `Row.player`; use the saved object ID only inside the matching encounter. Legacy or ambiguous recordings remain unlinked.

## Minimal navigation contract

Coordinator owns a typed route package and adapters, not a second query engine:

```java
enum Destination {
    RUNS, INSPECT, TIMELINE, RESOURCES, LOOT,
    ENCOUNTER, NOTIFICATIONS, ALERT_DRAFT, LOGGING
}
final class Route<F, S extends Enum<S>> {
    final Destination destination;
    final ArchiveQuery<F, S> query;
    final VisitRef visit;                 // optional exact visit
    final ArchiveRow.Ref record;          // optional exact archive locator
    final String recordingId;             // optional
    final Integer localObjectId;          // optional
    final Long from, until;               // half-open epoch-millisecond bounds
}
```

Destination adapters expose `captureState`, `open(route)`, and `restoreState`. Capture the origin before navigation and place it on a bounded Back stack. Validate destination/query compatibility in registration/adapters. A route payload for alert samples must remain detached and must not cause a save.

Existing hooks:
- `gui/modern/WorkspaceShell.java`: `select(int)` and `getSelectedPage()`.
- `gui/history/ViewState.java`: typed query, archive/live mode, page, tab, selected refs, anchor/offset and table layouts.
- `gui/history/ArchiveWorkspace.java`: `state`, `changeQuery`, `selectSession`, `selectPage`; missing atomic restore API.
- `gui/activity/ActivityQueries.java`: existing `Filters.visitSession` + `visitId`, validated together and applied before paging.

Add EDT-only `ArchiveWorkspace.restore(ViewState)` to restore once, invalidate stale requests, and initiate one load. Keep live state independent; hidden Inspect state must not overwrite the shared live roster. Never build routes around guessed `ArchiveRow.Ref` locators or turn them into cross-module identity.

## Exclusive ownership

At most three implementation workers run concurrently. The coordinator integrates shared files and is the only checkpoint/GitHub merge writer.

| Owner | Files/modules | Coverage |
| --- | --- | --- |
| Coordinator | New route package; `TomatoGUI`, `WorkspaceShell`, `ArchiveWorkspace`, `SessionPanel`; `TomatoData`, `TomatoPacketCapture`, `DpsData`, `DiscoveryLog`, `ActivityJournal`, `AppHistory`, `SessionStore`; Logging coverage integration | UX-02/04 linked extensions; producer contracts; LOG-2; integration |
| A: investigation | `gui/activity/**`, `gui/dps/**`, Inspect UI/roster files, `gui/myinfo/**`; new detached comparison/resource models | RUN-2, TIME-2, COMBAT-3/4/5, INS-2/3, INFO-2 |
| B: analytics | `gui/stats/**`, including loot, graphs and saved fame | LOOT-3, STAT-2/3 |
| C: alerts and Bridge | `gui/chat/**`, `gui/keypop/**`, `gui/notifications/**`, `gui/maingui/*Ping*`, `AlertRuleEditor`; `AlertRules`, `Sound`, `RealmEventAlerts`; `bridge/**`, `gui/bridge/**` | CHAT-3 remaining draft slice, KEY-3, ALERT-4, BRIDGE-3/4 |

Resolve actual Inspect/roster filenames explicitly in the worker assignment. No worker writes coordinator-owned producer, shell, or archive files. All ActivityPanel/resource/run-workbench edits stay with A. Stats-side alert-draft buttons stay with B using C's published draft contract. Bridge files stay with C even when adding review-derived alert drafts. Style helpers and validation allowlist remain coordinator-owned unless explicitly reassigned.

## Source-grounded implementation details

### A: investigation

`ActivityJournal.Visit` already retains outcome evidence, completionObservedAt, start/end/lastSeen, issues/gaps, progression, inspectedPlayers, playerDamage, resourceTimeline and conditionTimeline. Group the workbench as Outcome, Timing/coverage, Progression and Related evidence. Fetch exact evidence using origin session plus visit ID.

`InspectSnapshot.observedAt()` is producer observation/change time. Its no-time constructor uses the current clock; never use it to invent historical capture time. Carry source VisitRef into provenance/comparison UI. Compare two detached builds, including missing stats, class changes, outcomes and both DPS windows; do not attribute a DPS difference to gear.

`Damage` stores time, damage, ownerObjectType/name, ownerInvntory, ownerEnchants, projectile and supported flags. Paginate retained events beyond the latest-500 text output. Owner equipment belongs to the outgoing damage source, not the incoming victim. Without an explicit victim snapshot, incoming gear remains Not captured.

`Visit.resourceTimeline` stores nullable raw HP/MP; no per-sample maxima exist. `conditionTimeline` has start/end and nullable primary/secondary flags. Clip and union intervals inside selected half-open bounds, calculate each flag family's observed coverage, and allow zero-active lanes. Show active, observed and unknown durations separately. `CombatTimelineChart` already offers keyboard sample selection and an inspectionSummary property; extend it with timestamp endpoints and separate HP/MP plots.

TIME-2 route and export must use identical query bounds. Delayed counter confirmation stays at completionObservedAt, labelled observed later.

### B: analytics

`LootArchiveAdapter` already provides occurrence origin refs, session, visitId, item ID, slots and applied count. Aggregate variants intentionally erase single-visit identity. Add exact item-variant and exact visit facets to `LootQuery.Facets`; drill from a variant to occurrences and from one occurrence to its verified run.

`StatisticsArchiveAdapter` already pins runs/loot and implements exact session/visit/dungeon joins and coverage-aware rates. Reuse its denominator semantics for eligible runs, zero-loot runs, duration, exclusions and unassigned drops. Do not calculate rates from visible occurrence rows. A/B needs explicit baseline/candidate cohorts with the same dungeon/outcome/coverage predicates, totals, per-run/per-hour values and per-run distributions. Zero baseline produces no percentage change.

`FameSessionViewer` uses minimal `GraphPanel` without range controls. GraphPanel currently selects indices. Replace pinned selection with timestamps that survive new samples and pointer movement; expose range/character/gain-total controls, keyboard endpoints and a text delta. `AppHistory.FameSample` currently only has character/fame/time/class. Legacy map association must say Not recorded. New optional association requires a coordinator-owned producer hook.

### C: alerts and Bridge

`AlertRules.Snapshot` has pure matchChat/matchItem/matchEntityType, Match.ruleIndex and withRules. Add a draft API on AlertRuleEditor accepting a proposed rule and sample; open and silently evaluate without saving or enabling playback. Key-pop handoff focuses a known exact dungeon in Notifications while preserving checkbox state; unresolved names remain explicit.

Instrument actual decisions at ignored Chat gating, channel/keyword precedence, Realm-event cooldown, item/entity/enchant match, and Sound mute/volume/enable/playback completion. `Sound.play()` currently silently returns on suppression; UI-only instrumentation is insufficient. Use bounded detached decisions and correlate asynchronous playback results by decision ID. Edit rule must resolve the recorded rule safely if active rules have since changed.

`BridgeService.Snapshot.config` is the active state. Compare the draft form to it, show active mode/catalog, add Revert/inline validation and actual confirmation result. `configureOnWorker` saves before switching configuration: preserve prior active settings on save failure and retain the draft.

`BridgeStorage.audit` writes raw review JSONL with a 5 MB rotated backup; review IDs are service-local counters. Add a versioned reader independent of delivery, support explicitly selected legacy journals, qualify identities by journal/session, and report malformed lines. Future records may include app-session metadata. Opening/exporting saved review must never call receive, configure or transport, and must explain that observations never journaled cannot be recovered.

### Coordinator: LOG-2

DiscoveryLog.Snapshot already exposes sampleMillis, packet failures/trailing counts, delta omission, cache evictions, observer errors, diskDropped, enabled/saving and writer errors. `cacheEvictions` is prior-stat-cache eviction, not retained-event removal. The `events.removeFirst()` retention path has no separate counter.

Add retained-event first/last time, count and eviction count plus bounded collection transitions. Distinguish collection pause, sampling, decode failure, retention loss, omitted fields and disk loss. Persist coverage through asynchronous `SessionStore.availability`, preserving richer prior interval evidence instead of replacing it with the latest enabled flag. `DiscoveryCatalog` already maps diagnostics to affected gameplay views; reuse that allowlisted mapping for error routes. Never add chat/credential/raw-packet fields.

## Bounded delivery packages within this single wave

The original 3A-3D product groupings are too large for one commit per writer. Keep one Wave 3 branch/PR and use bounded checkpoints/commits within it:

1. **3A.1 contracts and capture identity (coordinator):** VisitRef/EncounterContext, exact MAPINFO provenance, legacy-compatible DpsData context, producer-order tests. Publish the API before workers depend on it.
2. **3A.2 routing shell (coordinator):** typed registry, Back stack, atomic ArchiveWorkspace restoration, stub destination registration and stale-request tests. Workers can implement pure models while this lands.
3. **First concurrent slices:** A builds run workbench and exact visit routes; B builds occurrence-to-run drill-down and rate calculation details; C builds contextual drafts and exact dungeon handoff. Integrate each independently with headless semantic tests.
4. **Second concurrent slices:** A builds paged event explorer and build provenance/comparison; B builds saved-fame parity and timestamp pinning; C builds bounded alert-decision instrumentation and UI. Coordinator implements LOG-2 concurrently without touching worker files.
5. **Third concurrent slices:** A builds selected-window resources and My Info verified-local navigation; B builds A/B cohort comparison and distributions; C builds Bridge draft/active feedback and saved journal reading. Bridge work can be split into two commits with separate fake-transport tests.
6. **3A.3 coordinator integration:** destination adapters and producer hooks, full cross-module journeys, export scope agreement, committed coverage ledger. Missing UI entry points are incomplete slices even if model tests pass.
7. **Final wave gate:** integrated test/shadowJar, applicable UI150/UI200, fresh synthetic populated/empty/error screenshots independently inspected, help/build-contract checks, independent final-head review, green PR CI, normal merge, main verification. Review fixes refresh relevant evidence.

Use unique build/cache directories per worker, isolated histories/preferences, at most three concurrent writers, and serialize desktop/focus validation. No live capture or Bridge deliveries.

## Required acceptance fixtures

- Two consecutive visits with the same dungeon name keep separate encounter/roster/loot/timeline links.
- Collection pause, connection boundary, failed MAPINFO and trailing-byte MAPINFO yield unlinked/unknown evidence appropriately.
- Old DpsData streams remain readable without manufactured visit or local-row identity; save-copy preserves new identity.
- Back restores origin scope/query/page/selection/scroll; stale asynchronous completions cannot replace the destination.
- Event 1 of 1,200 is reachable; outgoing weapon-swap gear matches its event; incoming victim gear is unavailable without a snapshot.
- Two active seconds, four observed seconds and ten selected seconds yield 50% observed uptime and six unknown seconds; zero-active lanes remain available.
- The same plus/minus 30-second Timeline query yields identical displayed/exported events; late completion remains late.
- Three items across two eligible one-minute runs, including one zero-loot run, yield 1.5/run and 90/hour with exclusions shown.
- Ten-run versus two-run cohorts expose both totals and normalized rates; zero baseline has no invented percent change.
- Fame timestamp pins survive pointer movement and refreshed samples; legacy map association says Not recorded.
- Opening Chat/Loot/Bridge alert drafts does not save, enable or play; returning restores the source record.
- Decisions distinguish no match, ignored, cooldown, muted and playback unavailable without replaying the source event.
- Bridge failed save retains previous active config and editable draft; confirmation reflects its actual outcome.
- Reopening synthetic legacy/versioned journals restores outcomes/export; fake transport fails the test if any delivery is invoked.
- Collection pause, decode failure, retention eviction and disk drops have separate counters/explanations; cache eviction is not mislabeled as history retention.
