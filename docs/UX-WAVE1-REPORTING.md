# Wave 1D — reporting evidence and integration handoff

Worker branch: `work/ux-w1-reporting`, based on `d1bfb63` (integrated 1A/1B/1C).
Reporting core: `1c2c62f`. The typed-hook follow-up uses primary-owner commit
`83c0c3c`, copied unchanged into this worktree as `3588b2e`.
Scope: Wave 1 portions of LOOT-1, STAT-1, COMBAT-1, BRIDGE-1 and BRIDGE-2 from
[the approved roadmap](UX-ROADMAP-2026-09-21.md). Worker implementation and focused
headless validation are complete. Coordinator integration, independent review and
serialized desktop/scaling/packaging validation remain required.

## Implemented behavior

### LOOT-1: global recency

`LootDashboard` retains the globally newest 1,000 bags in a bounded ordered set.
Ordering is descending timestamp, session ID, then record ordinal within that
session. Archive traversal order cannot evict newer records; equal timestamps and
identical drop contents remain distinct observations. Aggregate counts still include
every accepted bag/item. Recent filters operate on the retained global window.

The archive loader supplies the session ID. These tie-breakers are local projection
locators, not new persisted occurrence IDs or invented cross-module links.
All-history occurrence search beyond the retained window remains Wave 2 work.

### STAT-1: coverage, denominators and counters

- Run-only imported sessions expose **Not captured** loot and null numeric loot
  metrics. Imported runs appear as excluded runs in dungeon profiles.
- An older session with no saved loot records exposes **coverage unknown**, not
  an observed zero. An actual saved empty bag establishes zero visible items.
- Saved bags establish partial observation evidence, not continuous recording.
- Selecting a dungeon shows numerator units, eligible visits, visits with no linked
  bags, ongoing visits, observed milliseconds, imported exclusions and unassigned
  bags. Rates retain eligible zero-loot visits. Missing positive duration on any
  eligible visit makes hourly rates unavailable; unassigned drops make rates
  unavailable rather than mixing unmatched numerators and denominators.
- Verified aliases are normalized before the existing session/visit/dungeon join
  and in aggregate presentations. Unknown area names remain separate. Raw stored
  keys and observations are preserved.
- Dungeon counters explicitly report **activity-recorded exits** and **finalized
  time**, distinct from Runs' observed visits. A new optional snapshot field,
  `ongoingActivity`, exposes contributions before finalization; old JSON without
  it remains unknown. Hit events stay hit events, not kills. Counters without dates
  do not acquire period-filtering claims.

Sources: `gui/stats/HistoricalStatistics.java`, `LootDashboard.java`,
`DungeonStats.java`, and `backend/data/DungeonStatData.java` under
`src/main/java/tomato/`. Shared `Evidence.Coverage` and wrapping-text helpers are reused.

### COMBAT-1: metric definitions and shared pause

- Meters label **Recorded share %** and disclose player damage / all recorded
  target damage, including unattributed damage. Hiding players changes neither
  share nor DPS denominator. A zero total has no percentage denominator.
- Legacy text and icons explicitly say **% enemy max HP**. The same first-to-last
  target-hit duration definition is used across presentations; each Legacy section
  describes its own enemy while Meters may select multiple enemies.
- Legacy icon incoming metrics now use the meter projection's inclusive fight
  boundaries rather than the older exclusive `Entity.damageTaken` helper.
  Full-dungeon incoming totals and selected-window totals remain distinct.
- Incoming rankings describe represented outgoing contributors plus the captured
  local player when available, not an exhaustive roster. Remote unavailable versus
  captured-local zero behavior is preserved.
- One parent **Pause this view** control freezes both Meters and Legacy on the same
  already-detached live snapshot. It shows source and the time that snapshot was
  displayed (not an invented capture timestamp). Mode changes preserve the paused
  source across incoming map changes; resume renders the latest published snapshot
  immediately. Explicit encounter navigation resumes the view. Resources retains
  its own existing controls.

Sources: `src/main/java/tomato/gui/dps/CombatMeterData.java`, `DpsGUI.java`,
`MeterDpsGUI.java`, `DpsToString.java`, `IconDpsGUI.java`, `StringDpsGUI.java`.
No recording producer or `.dps` format changed.

### BRIDGE-1/2: outcomes and actionable review

- Each observation occupies one lifetime outcome bucket: Confirmed logged,
  Received—unconfirmed, Not logged, Local/excluded, Failed/uncertain, or Pending.
  Transitions update those buckets even after the bounded review list evicts a row.
- `logged` must be a JSON boolean to confirm either logging or non-logging.
  Successful HTTP responses with absent, null, malformed or unrecognized logging
  confirmation remain received/unconfirmed. HTTP errors and explicit `ok=false`
  cannot become confirmed logging. Announcement results remain separate.
- Outcome is visible separately from the compatible raw delivery status. Only
  confirmed logging receives positive styling. Lifetime counts and filtered shown
  counts use the same bucket definitions and name their scope.
- Local audit queue failures retain the local/excluded delivery outcome and expose
  the audit failure in details/logs; they do not count the item as both skipped and
  a failed delivery. A sending queue overflow remains failed/uncertain.
- Review search includes reason codes, item IDs, enchant descriptions/counts,
  character and dungeon. Exact character/dungeon, outcome, raw-status and applied /
  none / unknown enchant facets compose with search. Reset clears review filters.
  Export continues to consume the visible rows.
- Details preserve the original local choice and show observation → local choice →
  delivery/bot result → next step. `unmapped_character` points to Discord
  `/mysniffer → Configure Character`; catalog, category, local-only, queue,
  cancellation and uncertain-delivery cases have appropriate instructions.

Sources: `src/main/java/tomato/bridge/BridgeService.java` and
`src/main/java/tomato/gui/bridge/BridgeReviewGUI.java`. No endpoint, wire payload,
automatic retry or saved-journal reader was added.

## Validation evidence

Final focused run including the typed loot hook: **62 tests, 0 failures, 0 errors,
0 skipped**, using JDK
17.0.20.1 and Gradle wrapper 7.6.4. Main compilation retains `--release 8`.
Test JVMs used `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`, the existing in-memory
PreferencesFactory, isolated working/history directories and synthetic fixtures.
Transport tests use in-process fakes; the existing HTTP contract test uses loopback.
The reporting core was separately validated with 52 passing tests before importing
the typed-overload dependency; the final 62-test run rechecked those same selectors.

Outputs are relative to this worker checkout:

- Build output: `build/reporting/`
- Project cache: `build/project-cache/`
- XML evidence: `build/reporting/test-results/test/TEST-*.xml`
- HTML report: `build/reporting/reports/tests/test/index.html`

`JAVA_HOME` and `GRADLE_USER_HOME` used absolute paths to the coordinator checkout's
`.tools/jdk-17.0.20.1+1` and `.tools/gradle-home`. Both Gradle project-cache and
`realmSharkBuildDir` arguments used absolute paths into this worker's own build.

| Selector (package prefix `tomato.`) | Tests |
| --- | ---: |
| `gui.stats.ReportingStatisticsTest` | 8 |
| `gui.dps.CombatReportingTest` | 4 |
| `gui.bridge.BridgeReportingTest` | 3 |
| `bridge.BridgeTest` | 11 |
| `bridge.BridgeResponsivenessTest` | 14 |
| `backend.data.DungeonSessionScopeTest` | 1 |
| `gui.dps.DpsRefreshTest` | 4 |
| `gui.dps.DpsFormattingTest` | 2 |
| `gui.stats.LootNotificationTest` | 8 |
| `realmshark.TypedProducerIntegrationTest` | 2 |
| `gui.dps.CombatMeterTest.incomingUsesInclusiveWindowWithoutDoubleCountingOverlaps` | 1 |
| `gui.dps.CombatMeterTest.zeroDurationDoesNotInventDpsAndTotalsUseLongs` | 1 |
| `gui.dps.CombatMeterTest.remotePlayerIncomingMatchesLegacyDungeonTotalAndKeepsFightScope` | 1 |
| `gui.dps.CombatMeterTest.playerFiltersReuseHitAggregateAndMetersRescaleWithoutChangingShares` | 1 |
| `gui.stats.StatisticsExplorerTest.dungeonFiltersPreserveLootOnlySourcesAndNumericSorting` | 1 |

### Source-case references

- `ReportingStatisticsTest.globalNewestThousandSurviveNewestSessionFirstAndOutOfOrderRecords`:
  two 1,100-drop sessions retain the newest 1,000 while totals remain 2,200.
- `equalTimestampsUseSessionAndLocalOrdinalWithoutCollapsingDuplicates`: stable
  results after reversing session traversal order.
- `runOnlyImportsExposeUnavailableLootInBothReports` and
  `missingLootJournalIsUnknownButAnObservedEmptyBagEstablishesZero`: imported,
  unknown and observed-zero states are distinct.
- `aliasJoinIncludesZeroLootVisitsAndRateDetailsAndRejectsMissingDurations`: three
  items across two one-minute visits yield 1.5/run and 90/hour; a missing-duration
  visit invalidates hourly rates. `unassignedBagMakesRateUnavailableEvenWithAnEligibleVisit`
  checks the unmatched numerator case.
- `ongoingActivityIsSeparateFromFinalizedExitsAndOldMetadataStaysUnknown` and
  `canonicalCountersMergeVerifiedAliasesButPreserveUnrecognizedAreas`: zero-activity
  exit, ongoing contribution, finalized counters, old JSON and raw-key preservation.
- `CombatReportingTest.shareAndMaxHpAreDistinctAndFilteringCannotChangeDenominators`:
  200 / 400 recorded damage is 50% share while 200 / 1,000 HP is 20% max HP.
  `damageWithoutAnOwnerRemainsInTheRecordedShareDenominator` covers unattributed damage.
- `sharedPauseSurvivesModeAndMapChangesAndResumeRendersImmediately`: paused source
  survives mode/map changes and resume reflects 400 → 1,000 damage immediately.
- `BridgeReportingTest.strictResponsesSplitAllOutcomesAndLifetimeCountsSurviveEviction`:
  typed booleans, ambiguous successful responses and 1,016 observations / 1,000
  retained rows preserve mutually exclusive lifetime counts.
- `queueOverflowMovesOneBucketAndCancellationIsLocal` and the updated
  `BridgeResponsivenessTest.cancelledAuditDoesNotHoldModelMonitorAndDeliveryQueueRemainsBounded`:
  bounded queue, cancellation, local audit failure and responsive model lock.
- `retainedSearchFacetsDetailsAndShownCountsUseTheSameRows`: reason, ID and literal
  enchant search; composing facets; unknown versus zero enchants; mapping guidance;
  shown counts; no additional transport calls from browsing.

## Typed loot hook and coordinator integration

At handoff preparation, coordinator head `8f316db` became available; its parent
`83c0c3c` supplies `TomatoData.isItemPing(int, String)`. Only that integration commit
was cherry-picked as `3588b2e`. The typed-hook follow-up changes
`LootGUI.notifyItems` to:

```java
boolean itemMatch = data.isItemPing(item.statValue, name);
```

The occupied-slot guard, `itemMatch || enchantMatch`, malformed-slot isolation,
one alert per occupied matching slot, and local alerts before optional sharing are
preserved. The final headless run includes `LootNotificationTest`, especially
`alertsOncePerMatchingItemBeforeSharingWithEitherOptOutState`,
`malformedSlotsDoNotAbortOrdinaryAlertsLaterEnchantMatchesOrSharing` and
`nameIdAndEnchantRulesCoalesceToOneAlertForTheSameItem`. New production-caller cases
`typedExactIdAtTheLootProducerCoalescesEnchantsAndRejects142` and
`unsupportedTypedRulesDoNotReactivateTheLegacyLootProbe` prove exact ID 42 does not
match 142, matching item/enchant rules coalesce, malformed and empty slots remain
isolated, later enchant matches survive, opt-out state is honored, and unsupported
typed settings cannot fall back to the old string probes. All callbacks are
synthetic; no audio or sharing delivery occurs. Typed preference state is isolated
and restored along with the existing enchant fixture.

Coordinator integration: cherry-pick worker core `1c2c62f` and the typed-hook
follow-up commit. The coordinator already has original `83c0c3c`, so it should not
duplicate this worktree's copy `3588b2e`. That unchanged primary-owner commit is the
only imported dependency and owns its shared shell/data/scaling-policy edits.

## Remaining coordinator gates

This worker did not run native/focus/desktop screenshots, scaled suites, full-suite
packaging or `shadowJar`. Those checks and independent review belong to the
coordinator's serialized integration gate. New test classes need consideration in
that gate's scaling policy; this worker authored no shared build configuration edits.
No shared checkpoint or coverage ledger was changed.
