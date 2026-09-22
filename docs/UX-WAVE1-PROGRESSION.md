# Wave 1B progression implementation

- Worker branch: `work/ux-w1-progression`
- Base: `835e178c56381a16a5414f3ca9791a9d1ea7f174` (merged baseline PR #10)
- Source/test head: `ce0a3f03af9cd80ba8730cc3e59cf23acc595b97`

Status: assigned implementation complete; selected model checks pass. Shared shell/lifecycle
integration, serialized GUI validation and independent final-head review remain coordinator gates.
This document does not close the execution ledger or assert a wave-wide verification pass.

## Implemented IDs

### QUEST-1 — account and freshness context

- `TomatoPacketCapture` sends quest fetches to its `TomatoData` source, without requiring a shell.
- `ProgressionData` publishes a volatile immutable scope and synchronized detached snapshots.
  Quest arrays are copied in both directions. Scope identity rejects previous-account/generation
  publications; notifications are coalesced and the EDT pulls the newest source snapshot.
- `QuestGUI(TomatoData)` shows captured-for account, capture generation, receipt time, age and
  stale/unverified state. An old list retains its original origin. Unidentified responses are not
  retroactively assigned to a subsequently observed account. Account actions require current scope.
- Account pins use `accounts/<hashed-account>/pin.<existing-quest-key>` preference subnodes.
  Existing `pin.<key>` entries remain labeled global interests, with an explicit removal action.
  Removing a global interest does not remove an account plan. Category preferences remain global.
- Redemption success does not modify a row's completion flag. Completion comes from fetched rows.
- Default constructor/raw-array updates remain for standalone previews and existing callers;
  bound live views do not accept that unscoped bypass.

### CHAR-1 — presence, freshness and manual life state

- `RealmCharacter.presence` records actual supplied XML elements/attributes, including explicit
  zero/false, in both parsers. Copying and captured-player overlays preserve field provenance.
  Omitted class, level, skin, fame, season and creation metadata do not overwrite journal values.
- `Entity` records local receipt times at ingestion, not when the journal/view reads its retained
  stat map. Unrelated packets cannot advance local-player observation time. Resupplied unchanged
  fields get a new receipt time. Derived base-stat freshness conservatively uses the older total/
  boost operand. Account-ID replacement clears the previous account's retained stat fields.
- Journal v2 adds optional per-field evidence, separate alive-observation and roster-receipt times,
  and observed-again notices. v1 values load without fabricated field timestamps. Corrupt and
  unsupported documents remain protected; asynchronous revision-aware atomic saving is retained.
- Manual death preserves the original snapshot. Subsequent observations produce a restore prompt
  and a separate pending projection; only explicit restoration accepts it. Observation revisions
  distinguish successive same-millisecond events. Notes survive restoration.
- Roster/detail views distinguish last observed alive, reported in roster, legacy life state and
  marked dead manually. Stats/equipment show field evidence; a Snapshot evidence tab covers scalar
  metadata. Snapshot age and known-field counts do not claim every field was recently captured.
- Entity's existing serialization UID is retained; ingestion timing and clock/revision bookkeeping
  are transient and do not manufacture provenance in old `.dps` files.

### CHAR-5 — trustworthy pet estimates

- Pet Yard UPDATE/NEWTICK and equipped metadata publish detached, source-scoped observations.
  Object-to-instance association is explicit; missing IDs do not collapse into a shared `-1` pet.
  Account/capture boundaries clear the live pet scope. Late older metadata cannot replace newer
  per-field observations. Synthetic metadata without object/instance identity is not joined.
- The UI shows identity, account/capture generation, equipped/absent/unknown context, field receipt
  evidence, next-level and maximum item/fame estimates, feed power and ability multipliers inline.
  Estimate explanations are focusable text rather than tooltip-only content.
- `PetFeeding` separates missing inputs, locked abilities, inconsistent inputs and unsupported
  cost tiers. Missing points cannot produce Fully fed. Unsupported costs stay unavailable while
  independently calculable item counts remain visible. Arithmetic uses nonnegative long counts.
- Item selection supports searchable local equipment and numeric/hex item-ID lookup, including
  food through the existing `ParseEquipment.getEquipmentById` API. Feed power remains manually
  overrideable. Catalog access is off the EDT; missing assets leave the manual scenario usable.
  A late ID lookup cannot overwrite a subsequently edited feed-power draft.
- Feeding points/cap are not prerequisites for My Info's otherwise complete captured ability
  type/power list. Explicit empty `<Pet/>`, incomplete abilities and absent metadata remain distinct.

## Required shared-owner integration

1. **TomatoGUI constructor binding (required for live quest display):**

   ```java
   questPanel = new QuestGUI(data);
   ```

   Replace the current no-argument construction. No new `TomatoGUI.updateQuests` overload is
   required. The packet controller already writes `data.progression()`; the bound view subscribes.
   Keep the existing raw-array method for unbound fixtures/previews if desired.

2. **Capture lifecycle hooks (thread-safe progression publication operations):**

   - Call `data.captureStarted()` immediately before starting a new capture worker.
   - Call `data.captureStopped()` when stop is requested, before asynchronously closing capture,
     and on an unexpected current-worker termination. This makes queued updates inert immediately.
   - Call `data.captureBoundary()` on a current worker's transport reset/new incoming connection,
     before dispatching decoded packets from that connection. HELLO, map/CREATE and verified
     account changes already invalidate through the owned model code.
   - Guard callbacks with the existing current-processor identity checks. A retired worker must
     not stop/reset the replacement worker's publication scope. Do not invoke these for view pause
     or diagnostic-collector toggles. A restarted capture waits for a new verified connection.

   These hooks only mutate the synchronized publication model, not live entity collections.
   They are not substitutes for the shared lane's My Info/capture-readiness lifecycle handling.

3. **MyInfoGUI:** no signature change required. Preserve `MyInfoIdentity`, `PetAvailability` and
   `updateSnapshot` producer/EDT checks. Synthetic pet stats now contain only supplied fields.
   Consumers must continue checking `Stat.get(...) != null`, not primitive value defaults.

4. **Assets:** no accessor is required to compile/use this implementation. The current API exposes
   searchable equipment plus arbitrary item-ID lookup. A future complete consumable name catalog
   can extend that selector through an owner-provided detached catalog accessor; no asset loader
   was edited here.

5. **Validation integration:** include `PetFeedingFormTest` and the new bound-publication test in
   the coordinator's serialized UI/scaling coverage. Existing Character/Quest consistency suites
   also need fresh compact/enlarged-font/keyboard evidence. Build/scaling files were not edited.

## Worker verification

JDK 17.0.20.1, Gradle 7.6.4, existing main `--release 8`. Used the coordinator checkout's `.tools`
JDK/Gradle/cache with isolated worker `build/w1-progression` and `.gradle-w1-progression` project cache.
The existing test PreferencesFactory and history-directory isolation were active. Only synthetic
fixtures were used. No GUI/native/focus suite, live capture, real delivery, packaging or screenshot
run was performed by this worker.

`compileJava compileTestJava` passed. The final selected test run passed **38 tests, zero failures,
errors or skips**, confirmed in `build/w1-progression/test-results/test/TEST-*.xml`:

| Suite/selection | Passed |
| --- | ---: |
| `CharacterFreshnessTest` | 7 |
| `CharacterJournalTest` | 9 |
| `ProgressionDataTest` | 6 |
| `PetFeedingTest` | 5 |
| `QuestPinsTest` (pure preferences model) | 1 |
| `PersistenceConcurrencyTest` journal-only methods | 3 |
| `AccountMetadataTest` non-GUI methods below | 7 |

Reproduction: use `test` with `-PrealmSharkBuildDir=build/w1-progression` and
`--project-cache-dir .gradle-w1-progression`, selecting the first five full suites plus:

```text
tomato.backend.data.PersistenceConcurrencyTest.*Journal*
tomato.backend.data.PersistenceConcurrencyTest.journalCopiesDetachEveryMutableArrayAndPreserveUnknownValues
tomato.backend.data.AccountMetadataTest.blockedHelloDoesNotBlockCaptureAndWorkerCannotPublishBeforeIdentity
tomato.backend.data.AccountMetadataTest.tokenSwitchDropsOldResponseAndCannotAttachOldRosterToNewJournalAccount
tomato.backend.data.AccountMetadataTest.sameTokenReconnectAndNewCharacterInvalidateOldRoster
tomato.backend.data.AccountMetadataTest.burstsCoalesceAndDelayedRosterPreservesCapturedStatsAndPacketExalts
tomato.backend.data.AccountMetadataTest.mismatchedAccountAndRejectedResponsesDoNotReplaceKnownRoster
tomato.backend.data.AccountMetadataTest.rosterDecoderRetainsEquipmentPetAndPartialStatsWithoutGlobalParserSideEffects
tomato.backend.data.AccountMetadataTest.observedAccountChangeDiscardsOldCredentialUntilNewHello
```

Each selector is supplied as a separate `--tests` argument. `git diff --check` passed. The new
blocked-EDT `QuestGuiTest.boundPublicationRejectsOldAccountWhileEdtIsBlockedAndShowsStoppedOrigin`
and updated GUI assertions compile but were intentionally not executed in this worker lane.

## Evidence boundaries / review focus

- Quest packets have no account identifier. Correct binding depends on serialized capture delivery
  and the integration lifecycle hooks; receipt-time snapshots cannot recover missing wire provenance.
  “Last captured” is not a promise that server state has remained unchanged. Expiration strings are
  not interpreted as a guessed refresh deadline.
- Per-field times are local receipt times. Retained values can have different ages; base-stat
  freshness does not imply simultaneous total/boost capture.
- The death mark and observed-again notice persist. The one pending restore projection per marked
  character is memory-only; after restart, restoration clears the mark and subsequent capture
  refreshes the preserved snapshot. This does not create a death/loadout history feature.
- Feeding uses the existing local formula and supported cost mapping, not a newly validated game
  pricing service. Unsupported tiers explicitly withhold fame costs. The catalog does not establish
  item ownership or consume/feed items.
- Independent review, full suite, native/scaled UI evidence and wave packaging remain pending.
  Shared-owned sources, alert matching, execution ledger, coordinator checkpoint and remote state
  were not changed by this worker.

## Independent review corrections on integrated Wave 1

Applied on the primary `feat/ux-wave-1-trust` checkout starting at
`25311fea31991f6f779640e0dbafc16f9e82416d`:

- Pet promotion now merges the existing instance record, the real object's anonymous record,
  and the incoming fields by each field's receipt timestamp. It retains the winning field's
  provenance and removes the anonymous row only after merging. Later ID-less object deltas
  continue to update the single identified record.
- Negative metadata sentinels, including `-1`, cannot promote or donate anonymous fields to an
  identified pet. Repeated unidentified metadata replaces its unverified snapshot without joining
  it to other unidentified or identified records.
- The journal's regular refresh now updates time-dependent evidence independently of journal
  revisions. It updates only the evidence text, preserving tables, sorting, selection, active tab,
  notes draft and caret. A package-private clock seam makes age checks deterministic.

Before the fixes, the new regression tests reproduced both pet failures and the frozen-age
failure (four failing assertions/tests across the three findings). After the fixes, the focused
headless run passed **10 tests, zero failures/errors/skips**:

- `tomato.backend.data.ProgressionDataTest`: 8 tests, including the exact anonymous-200/t200,
  existing-instance-10/t100 promotion sequence, conflicting per-field provenance, and negative
  sentinel isolation.
- `tomato.gui.character.CharacterJournalFreshnessRefreshTest`: 2 tests, using an injected clock
  instead of sleeps. They verify advancing age with an unchanged journal revision, zero table/
  selection/notes-document events, unchanged draft/caret, unknown timestamps and backward clocks.

Used JDK 17 / Gradle 7.6.4, `-PrealmSharkBuildDir=build/w1-progression-blockers`, and
`--project-cache-dir build/w1-progression-blockers/project-cache`. The local init script
`.omc/ux/progression-blockers-headless.gradle` sets every test JVM's `java.awt.headless=true`.
Reproduce with those options, `-I .omc/ux/progression-blockers-headless.gradle`, and
`test --tests tomato.backend.data.ProgressionDataTest
--tests tomato.gui.character.CharacterJournalFreshnessRefreshTest`. XML results are in
`build/w1-progression-blockers/test-results/test/`; main/test compilation also passed.
No native windows, rendering, focus/scaling validation or packaging was run for these corrections.
