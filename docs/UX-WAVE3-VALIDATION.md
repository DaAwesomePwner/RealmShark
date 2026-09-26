# Wave 3 validation record

Branch `feat/ux-wave-3-connected-analysis`, based on main `6d71a56`. Toolchain: JDK 17
(Temurin 17.0.20.1) and Gradle 7.6.4; main and test sources remain Java 8 API/bytecode.
All runs used unique build/project-cache directories and a machine-wide lock that
serialized desktop/focus-sensitive runs. Synthetic fixtures only; no live capture,
network Bridge deliveries or real audio.

## Final-head evidence

Tested code head: `ab6f613 (later commits change docs only)`.

| Check | Result |
| --- | --- |
| `test` (full suite) | 913 tests, 0 failures/errors/skips |
| `testUi150` (allowlisted UI at 150%) | 232 tests, 0 failures/errors/skips |
| `testUi200` (allowlisted UI at 200%) | 232 tests, 0 failures/errors/skips |
| `shadowJar` | built (RealmShark-v1.2.3.jar) |
| Isolated runnable-JAR `--help` | exit 0; wrote nothing to its isolated working directory |
| `scripts/Test-BuildMaintenance.ps1` build contract | PASS: output isolation, source-tree cleanliness, generation/invalidation and fresh-build JAR contracts |

Earlier failed or interrupted runs are diagnostic history, not passes. Notable ones:

- `f3d8161`: `BridgeUiTest` failed only at 200% because it asserted compact mode from
  the requested frame width. The test now follows the realized shell width
  (`2ae070c`) and requires the 680 px pass to be compact (`6798533`).
- `7975942`: two failures in the full suite, both from combining independently
  tested fix branches. The run-workbench evidence expected "Back to Runs" after the
  same-page Back label changed. `NotificationsConsistencyTest` recursed into the new
  Recent decisions scroll bar, whose FlatLaf arrow buttons are 0 px tall, once earlier
  tests had recorded decisions. It was reproduced deterministically by seeding
  decisions. Both were fixed in `ab6f613`.

## Reviews

- Independent source review: APPROVE `f3d8161`. Two should-fix findings were fixed and
  re-reviewed (APPROVE `cce9fb0`): recording intervals over-claiming coverage across a
  capture stop/restart, and a stale Key-pops focus Back popping an unrelated origin.
  The re-review's timing-fragile `RecordingGapTest` was hardened.
- Final-head delta review `cce9fb0..7975942`: APPROVE, no blockers. The later
  commits are test-expectation reconciliations only (`ab6f613`).
- Independent visual review, round 1 (91 images x 3 scales): FIX REQUIRED, with two
  blockers. The DPS meter was squeezed out by the new encounter-link header; Bridge
  saved review was unreachable at compact width. Nine should-fix items and notes were
  also raised. All were addressed in three disjoint fix lanes, each reproduced before
  fixing and asserted with realized sizes at 100/150/200%.
- Visual review, round 2 on fresh final-head captures (105 images x 3 scales): PASS. Both blockers and all nine should-fix findings confirmed fixed; no new blocker or should-fix defect. Remaining cosmetic notes are listed under known limitations.

## Acceptance fixtures covered by tests

- Consecutive same-name visits never cross-link across Runs, Timeline, Inspect, Loot
  and Resources (`WaveThreeJourneyTest`, `EncounterIdentityTest`).
- Collection pause, connection boundary, failed and trailing-byte MAPINFO yield no
  link. Old `DpsData` streams stay unlinked; save-copy preserves new identity.
- Back restores origin scope/query/page/selection/anchor; stale asynchronous
  completions cannot replace restored state (`RouteBackRestoreTest`, journeys).
- Event 1 of 1,200 is reachable. Weapon-swap gear matches its event; incoming victim
  gear is "Not captured".
- 2 active s in 4 observed s of a 10 s window give 50% observed uptime and 6 s
  unknown; zero-active lanes stay listed.
- The same ±30 s Timeline query yields identical displayed and exported events;
  late completion stays "observed later".
- 3 items over two eligible one-minute runs, one with zero loot, give 1.5/run and
  90/hour with exclusions. 10-run vs 2-run cohorts show totals and normalized rates;
  a zero baseline shows no percentage.
- Fame timestamp pins survive pointer movement and new samples; legacy samples say
  "Not recorded".
- Alert drafts from Chat, Loot and Bridge never save, enable or play. Decisions
  distinguish no match, ignored, cooldown, muted and playback unavailable.
- Bridge failed save keeps the previous active config and the draft. Saved journal
  review never calls transport, configure or receive (fake transports fail if it does).
- Collection pause, decode failure, retention eviction and disk drops have separate
  counters. Recording intervals end at capture stop/restart and after silence, so
  gaps read "not recorded".

## Known limitations (accepted, documented)

- A linked live encounter's run shows as unavailable in Runs until the periodic run
  save writes it. A crash can lose up to 60 s of claimed coverage (undercount only).
- Imported `.dps` files from another machine keep a VisitRef that cannot resolve and
  show as unavailable. The INS-3 baseline pin is not persisted across restarts.
- My Info recomputes recorded DPS for each library encounter on reload (EDT); a very
  large library could pause briefly.
- Cohort Export still exports the last applied comparison while the view shows the
  cleared/stale notice; its manifest carries that query.
- The DPS Damage meters tab needs a vertical scroll at 680x520 to reach the lower
  meter; nothing is cut off.
- Terminology outside Wave 3 files: the Activity page's `CollectionControl` still says
  "collection: off".
- `KeyPopEvent` formatters capture the default zone at class load (pre-existing
  order-dependence shared with older formatting tests).
- Cosmetic visual notes accepted from review round 2: the last footer line is cut in
  some wide pages at 100% (Runs workbench, Timeline unavailable, Loot occurrences;
  the pages scroll). Bridge saved review (not opened) and the Loot rate table at
  compact open scrolled past their first content. The selected row highlight is faint
  in Recent decisions and Loot occurrences. The legacy DPS status line truncates at
  680 px (100%), and Details shows the full text. The Logging coverage dialog repeats
  "Collection: paused". The saved-fame "Not recorded · no saved map visits" wording
  sits beside a recorded-visit count. An audio-error footer appears in several
  Notifications fixtures; it is expected to be shared test sound state, to be
  confirmed not to be a sticky app status.