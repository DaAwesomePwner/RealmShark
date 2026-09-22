# Wave 1 shared integration

Branch: `feat/ux-wave-1-trust`; integration base: `d1bfb63`.
Status: assigned hooks implemented; focused headless checks passed. Author review
is not independent approval, and full/desktop/wave gates remain pending.

## Hooks wired

- `TomatoGUI` constructs `QuestGUI(data)` and passes `chatPanel::historyWithPolicy`
  to the Chat history wrapper. The packet controller's scoped quest publication
  now reaches the live shell. The old raw-array API remains for compatible callers;
  the bound view rejects it as an unscoped bypass.
- `TomatoData.customSoundAlert` uses `AlertRules.application().matchEntityType`
  with the existing legacy list and unsigned entity type. Its existing new-object
  gate is retained. The new `isItemPing(int, String)` uses `matchItem`; the old
  string overload and preference-list APIs retain their behavior.
- `CapturePublication` serializes current-worker ownership with the progression
  mailbox. `captureStarted()` runs immediately before starting the new worker.
  Stop requests close a nonblocking packet-dispatch gate and call `captureStopped()`
  before asynchronous native cleanup. Unexpected termination invalidates on the
  producer before its EDT notification. Old boundary/termination callbacks cannot
  invalidate a replacement worker; deliberate stop is not published twice.
- `PacketProcessor` invokes the synchronous boundary listener before each capture
  attempt, when an attempt ends, and before incoming/outgoing transport resets.
  Existing `Sniffer`/`TcpStreamBuilder` callbacks run inline on the producer, so
  changed tuples and captured SYN/RST resets invalidate before payload dispatch.
  Attempt boundaries cover initial midstream capture and automatic adapter retry.
  Terminated processors reject late data/reset callbacks. View pause and collector
  toggles do not trigger these lifecycle hooks.

Only the existing synchronized progression-publication methods run across the EDT
and producer. Lifecycle integration does not clear/mutate live entity collections
from the EDT. Existing packet-owned identity and My Info mechanisms remain intact.

## Proof

JDK 17 / Gradle 7.6.4; main Java 8 API/bytecode target retained. Main and test
compilation passed. **46 tests passed, zero failures/errors/skips**, with every JVM
using `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true` and these exact selectors:

```text
tomato.CaptureHookIntegrationTest
tomato.gui.chat.ShellHookIntegrationTest
tomato.realmshark.TypedProducerIntegrationTest
packets.packetcapture.CaptureLifecycleTest
packets.packetcapture.sniff.assembly.TcpStreamBuilderTest
packets.packetcapture.pconstructor.PacketConstructorRecoveryTest
tomato.backend.data.ProgressionDataTest
tomato.realmshark.AlertRulesTest
tomato.gui.quest.QuestGuiTest.boundPublicationRejectsOldAccountWhileEdtIsBlockedAndShowsStoppedOrigin
tomato.gui.chat.SharedChatPolicyTest
```

Invocation: `test`, supplying each selector as a separate `--tests` argument, with
`--no-daemon --max-workers=2 --project-cache-dir .gradle/ux-w1-integration
-PrealmSharkBuildDir=build/ux-w1-integration`. Toolchain/cache came from the root
`.tools` directory. Existing preferences/history isolation was active. Reports:
`build/ux-w1-integration/test-results/test/` and `reports/tests/test/` beneath that
build directory.

`-I scripts/typography-validation.gradle help --task testUi150` also configured
successfully. This checked task registration only; no scaled test task ran.

New integration assertions cover the actual shell wrappers, scoped quest packets,
historical ignore changing the live Chat policy, exact 42 versus 142, unsigned
entity type versus instance ID, one alert per newly observed object, typed rules
overriding legacy settings, and unresolved item names. Audio is recorded through
the existing test override. Lifecycle checks use the real runtime start/stop path
with a synthetic adapter loop: blocked close, blocked EDT, retired callbacks,
changed TCP tuple, outgoing reset, automatic retry and queued restart ordering.
No native adapter, native window, real playback or bridge delivery was used.

The initial test compile exposed package-private Chat fixture types; the shell
integration test was moved into the Chat test package. That failed compile is not
counted as validation evidence.

## Remaining coordinated gates

- Reporting owns the `LootGUI` caller change to `data.isItemPing(itemType, name)`
  and its occupied-slot/enchant/once-only regression checks. No reporting feature
  code was edited here.
- The typography allowlist now includes the applicable setup, My Info, Logging,
  Activity/Inspect, scoped Quest, pet-feeding, shared Chat, exact contributor and
  social editor component suites. Pure matcher/model suites remain regular checks.
  Run full tests, native keyboard/dialog checks, 150%/200% suites and screenshot
  review serially after reporting integration. Additional reporting-specific GUI
  suites can then be included by the coordinator.
- Run shadow JAR/build-contract and isolated `--help` checks; obtain independent
  review of the final integrated head and passing CI before a PR merge.

No execution ledger, checkpoint, other feature implementation, remote publication
or merge was changed by this integration package.
