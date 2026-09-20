# Step 2 — responsiveness and service boundaries

Tracking: [issue #3](https://github.com/DaAwesomePwner/RealmShark/issues/3). Branch: `perf/step-2-responsiveness`, based on merge `7b36187` of [Step 1 PR #2](https://github.com/DaAwesomePwner/RealmShark/pull/2).

Implements Phase 2 of the [code review](CODE-REVIEW-2026-09-19.md): bridge and legacy transport blocking, coalesced fame presentation, view-specific history snapshots, and asynchronous preferences persistence.

## Implementation

### Bridge

- Construction returns while a dedicated worker loads settings/catalog data; loading and failure states are visible, and edits made during startup are preserved.
- Configuration is serialized separately from the short-lived capture/snapshot monitor. CSV reads, settings writes, folder locks, audit I/O, and cleanup execute outside that monitor.
- Failed configuration preserves the previous settings, catalog, and delivery generation. Late work cannot reactivate a closed service.
- Configuration capacity is eight waiting changes; delivery retains its 256-task bound. HTTP results retain existing accepted/logged/uncertain semantics and no-retry policy.
- Shutdown invalidates pending work immediately. An already-started write/request can finish; folder-lock cleanup is asynchronous after outstanding configuration work.

### Legacy loot sharing

- Replaced the unbounded LIFO/reflective-reset sender with a bounded FIFO and a single owned sender. Capture prepares detached payloads and never opens or waits for a socket.
- Capacity: 256 waiting payloads plus one active. New overflow is dropped and counted; opt-out clears unsent queued/pending merge data and invalidates old generations.
- Connection/write deadlines are three seconds. Known idle-closed connections are retired before the next drop. Only definite rejection before enqueue permits one reconnect attempt; ambiguous writes are not retried.
- Counts distinguish queued, sent-to-socket, dropped, and uncertain work. A local write is not application-level confirmation. The Loot panel exposes a compact summary and detailed status.
- The adapter bounds incoming handshake data at 16 KiB, frame/message data at 64 KiB, fragmented-message count at 128, and pending pong output at eight frames, including an output frame already dequeued into a blocked socket write. Idle control-output deadlines are checked by the sender's periodic poll.
- Valid legacy payload fields, enchant `sl` counting, tick/merge rules, preview protection, and independent local alerts are covered by regression tests. Missing class assets use an explicitly unknown exalt bonus.

### Preferences

- Reads and edits use the immediate memory view. One background writer retains at most one in-flight snapshot and one coalesced pending batch; changing Compact default submits its three settings together.
- Initial preferences load before GUI construction on the startup thread. Edits accepted during loading take precedence over loaded values.
- Same-directory temporary writes use atomic replacement. Failed/unsupported replacement preserves the previous file; malformed/unreadable input suspends saving for that store instance and reports why.
- The workspace footer displays loading/saving/saved/failure with generation details. Enchant Pings says Saved only after successful completion and ignores superseded callbacks.
- Orderly shutdown waits up to three seconds for accepted changes and reports failure/timeout. Power loss or forced termination is outside that guarantee.

### Fame

- Tracking state is independent of Swing presentation. The actual captured observation updates graph and table models together under the same boundary used by reset/save operations.
- All necessary timestamps and character/map transitions remain tracked, including unchanged fame, zero/negative gains, and open visits. Stale save completions cannot mark newer visit time as saved.
- Each view coalesces its presentation callbacks and catches up when shown. Hidden views avoid presentation copies/rebuilds. The unused fame-table sample history is removed; graph/session history is retained.
- Retained fame history is not given a new lifetime cap. Session reset and existing persistence semantics remain available.

### Activity and Logging

- Runs uses visit summaries, Timeline uses events/visit choices, and Combat uses only the selected visit's resources/conditions. Diagnostics snapshots exclude activity charts.
- Unchanged revisions avoid copying rows. Public payloads are detached; private displayed revisions remain stable for frozen views and export through copy-on-write state.
- One running view acquisition plus one replaceable pending request coalesces refreshes. Selection/generation checks reject stale completions, including freeze/resume and frozen visit changes.
- Activity exports its displayed revision. Logging exports current capture data even while its display is frozen. Full snapshot materialization and file serialization occur on workers.
- Activity checkpoints acquire snapshots on the persistence worker and acknowledge dirty generations only after successful writes. A failed paused checkpoint is retried on orderly close; older success cannot clear newer dirty state.

Full export/checkpoint copying still briefly holds the observer monitor on a worker and can contend with capture. Moving that work off the EDT is not a claim of zero capture-lock cost.

## Regression validation

Local JDK 17.0.20.1+1 / Gradle 7.6.4 results:

- **402 regular tests passed**, zero failed/ignored (**84 more than Step 1**).
- **31 UI checks passed at 150%** and **31 at 200%**.
- `shadowJar` and runnable JAR `--help` smoke check passed.
- Independent reviews covered storage/lifecycle, transport buffering and delivery, fame ordering, history revisions/persistence, compact status, and benchmark claims. Reported issues were corrected and re-reviewed.

New tests use controlled storage, network-adapter, and EDT barriers. They cover producer responsiveness during stalled startup/save/connect/audit, FIFO/overflow, cancellation/late completion, atomic replacement failures, session-reset races, frozen selection races, dirty checkpoint retries, bounded history, and unchanged-revision copy avoidance. WebSocket protocol tests use the real pinned engine with in-memory handshakes; they do not contact the legacy endpoint.

```powershell
.\gradlew.bat --offline --no-daemon --continue -I scripts/typography-validation.gradle -PrealmSharkBuildDir=build/step2-validation test testUi150 testUi200 shadowJar
java -jar build/step2-validation/libs/RealmShark-v1.2.3.jar --help
```

Reports: `build/step2-validation/reports/tests/{test,testUi150,testUi200}/index.html`.

Hosted Windows CI runs the entire regular suite and JAR/help checks. Scaled validation remains local because the hosted display is limited to 1920×1080, as documented in [Step 1](STEP-1-DATA-INTEGRITY.md).

## Reproducible synthetic benchmark

The opt-in task runs a fresh JVM with an isolated build working directory, preview mode, muted audio, `DiscoveryLog(null)`, and an in-memory fame save callback. It does not run application main, packet capture, real transport, or real history persistence.

```powershell
.\gradlew.bat --offline --no-daemon -I scripts/responsiveness-validation.gradle -PrealmSharkBuildDir=build/step2-validation responsivenessBenchmark
# Optional input size (2,000–200,000; default 100,000):
.\gradlew.bat --offline --no-daemon -I scripts/responsiveness-validation.gradle responsivenessBenchmark --args="--samples 22000"
```

Workload:

- Separate 2,000-update warmups; 100,000 measured updates per pipeline.
- Six hours of logical timestamps, four fame characters, and 240 visits; unchanged fame between gains is retained as observation time.
- Hidden then visible fame/discovery views, with at least 2.5 seconds per measured half and 15 ms EDT heartbeat probes.
- A separate deterministic 16-visit ActivityJournal replay exercises long-visit chart retention. Observer wall-clock timestamps are not presented as six hours of real capture.
- Terminal map/party markers verify that each Activity view catches up independently; Combat also verifies an explicitly selected older retained visit.

### Measured run

Windows 11 amd64, 24 logical processors, OpenJDK 17.0.20.1, 768 MiB maximum heap, UI scale 1. This is one synthetic run, without a pre-change application benchmark.

| Measured phase | Updates | Mean producer work / update | EDT median | EDT p95 | EDT maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Fame, hidden | 50,000 | 0.418 µs | 0.090 ms | 0.160 ms | 0.294 ms |
| Fame, visible | 50,000 | 0.521 µs | 0.370 ms | 0.965 ms | 8.863 ms |
| Discovery, hidden | 50,000 | 2.229 µs | 0.085 ms | 0.127 ms | 1.101 ms |
| Discovery, visible | 50,000 | 2.072 µs | 0.087 ms | 0.143 ms | 8.307 ms |
| Logical activity replay | 100,000 | 0.652 µs | 0.093 ms | 0.137 ms | 0.251 ms |

Each measured heartbeat phase contained 139–146 observations, with zero skipped probes. Warmups are excluded from this table. Producer time includes construction, model lock waits, and fame save preparation, but excludes pacing and real disk/network/decoder costs. This is decoded-observer/tracking throughput, not end-to-end capture throughput.

Functional assertions passed; total benchmark wall time was **16.27 seconds**. The report-only budgets were EDT p95 ≤100 ms, maximum ≤1,000 ms, and total wall time ≤30 seconds. All measured phases were within those budgets on this machine; they are not production latency guarantees.

Retention/copy results:

- Fame: four latest samples, 240 visits, and 10,000 retained graph samples. Hidden presentation snapshots/rendering stayed at zero.
- Discovery: 100,481 synthetic decoded frames, 200 retained visits, 1,500 diagnostic events, zero observer errors. Live views made **zero full-history snapshot reads**; one explicit full reference was taken afterward for verification.
- Long logical activity: 16 visits, 1,000 events, **12,000 resource points and 12,000 condition slices**, with a 1,000-per-visit cap and 95,984 omitted timeline records disclosed.
- On that saturated history, 12 full snapshots averaged **403.16 µs** each; Runs summaries averaged **9.33 µs**, Timeline **100.23 µs**, and selected-visit Combat **18.37 µs**. Runs/Timeline copied no chart samples. Unchanged-revision requests copied no rows.
- The same 12 full-history copies allocated approximately **18.34 MB** on the calling thread, versus **0.194 MB** for Runs summaries. These compare operation scopes on one fixture, not overall application speedups.

Limitations:

- Heartbeats permit one outstanding probe, so prolonged stalls produce coordinated omission; skipped counts are reported. Short warmup and one run do not establish steady-state behavior.
- Fame pending metrics are Boolean-flag samples, not independent queue-depth measurements. The one-callback guarantee is tested separately with blocked-EDT regression barriers. Discovery worker queue depth is not exposed to this benchmark.
- Allocation counters cover main/producer and EDT threads, including harness overhead, and exclude other workers/native allocations. Process-wide after-requested-GC observations were approximately 11.55 MB for the fame fixture, 15.27 MB for discovery, and 16.44 MB for long activity; these are not precise retained model costs.
- Fresh live gameplay, a real-time prolonged soak, network delivery acceptance, and audible notifications remain unmeasured. Existing regression doubles establish blocked-I/O behavior separately from these throughput/latency measurements.
