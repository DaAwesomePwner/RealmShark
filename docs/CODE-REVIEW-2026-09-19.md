# RealmShark code review — 2026-09-19

## Assessment

RealmShark has a useful shared presentation layer, substantial feature coverage, and a passing regression suite. The highest-value next work is to protect exports, eliminate remaining blocking work in capture/UI paths, make historical and partial data trustworthy, and finish compact/large-font accessibility. These improvements can build on the existing Swing architecture.

This review covers the current working tree, including existing uncommitted changes. Source references below are relative to the repository root and describe that snapshot. Application source was not changed during the review.

**Priority:** P1 = fix first because saved data can be lost; P2 = significant user-facing correctness, responsiveness, or usability defect; P3 = narrower polish or structural improvement. Performance opportunities without timing measurements are identified explicitly.

## Verification

Fresh validation used the project-local JDK 17.0.20.1+1 and Gradle 7.6.4:

```powershell
$env:JAVA_HOME = "$PWD/.tools/jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-home"
.\gradlew.bat --offline --no-daemon -I scripts/typography-validation.gradle -PrealmSharkBuildDir=build/code-review-20260919 test testUi150 testUi200 shadowJar
& "$env:JAVA_HOME/bin/java.exe" -jar build/code-review-20260919/libs/RealmShark-v1.2.3.jar --help
```

- **281 tests passed**, zero failures or ignored tests.
- **30 UI tests passed at 150%**, and **30 at 200%** Java2D scaling.
- Runnable JAR built; `--help` smoke check passed.
- Fresh screenshots were generated; representative populated desktop/compact views were inspected.
- Independent temporary Java probes reproduced the export, bridge-monitor, stale-pet, missing-enchant, saved-fame, relative-DPS-filter, and selected UI defects below against the freshly built JAR.
- Probe files/fixtures are outside the project, under the local temporary workspace in `realmshark-review-verify` and `realmshark-ui-review-verify`.
- Reports: `build/code-review-20260919/reports/tests/{test,testUi150,testUi200}/index.html`.

The probes used synthetic data and isolated settings. There was no live capture, external-service exercise, prolonged gameplay benchmark, or screen-reader session. Headless layout measurements are identified below. Existing test success does not establish coverage of the reproduced edge cases.

## Findings to fix

### 1. P1 — DPS export silently replaces existing recordings

**Source:** `src/main/java/tomato/gui/dps/DungeonListGUI.java:133–165`.

Export selects a directory, generates deterministic filenames, and uses `FileOutputStream` without checking existing files. The collision set only protects encounters within the current batch.

**Reproduction:** Export an encounter with debug packets, then export it again without debug to the same directory. A probe confirmed that the original 1,063-byte recording became a 979-byte recording with `debugPackets == null`. An unrelated marker file at the same destination was also overwritten. The in-memory encounter retained its debug data; the loss is on disk.

**Fix:** Allocate names against existing destination files using exclusive creation. For intentional replacements, use an explicit replacement choice and write to a temporary file before moving it into place. An unsuccessful export must preserve an existing recording.

### 2. P2 — Bridge configuration can block the UI despite using a background worker

**Sources:** `src/main/java/tomato/bridge/BridgeService.java:39–80,94–109,168`; `src/main/java/tomato/gui/bridge/BridgeReviewGUI.java:86,131–137`; `src/main/java/tomato/gui/TomatoGUI.java:60–81`.

`configure()` holds the service monitor while loading the CSV, taking a filesystem lock, and saving settings. The visible Swing refresh timer calls `snapshot()`, which needs the same monitor. Capture submissions also use it. Separately, first construction of the bridge singleton loads settings and configures it synchronously during EDT window construction.

**Reproduction:** A controlled pause at CSV file access put the real Swing timer in `BLOCKED` state at `BridgeService.snapshot:168`, waiting for the configuration worker. First singleton construction on the EDT also reached `BridgeConfig.load` file reading.

**Fix:** Load and validate detached configuration/catalog data outside the snapshot/capture monitor. Serialize configuration operations separately, then briefly lock to publish a validated generation. Initialize optional bridge services asynchronously with a visible loading/error state.

This is a demonstrated conditional stall, not a measured normal-disk slowdown or inevitable deadlock. HTTP delivery itself already runs outside this configuration critical section.

### 3. P2 — Legacy loot sharing can pause capture and silently lose queued deliveries

**Sources:** `src/main/java/tomato/backend/data/TomatoData.java:357–399,494–504`; `src/main/java/tomato/gui/stats/LootGUI.java:109–139`; `src/main/java/tomato/realmshark/SendLoot.java:23,126–143,533–558`; `src/main/java/tomato/realmshark/WebSocket.java:35–49`.

The packet-processing path calls `SendLoot.sendLoot()`, which calls `connectBlocking(10, TimeUnit.SECONDS)` on the caller's thread. Sharing is enabled when the opt-out preference is absent (`TomatoMenuBar.java:488–494`). The existing send worker does not move connection establishment off capture.

The sender additionally uses an unbounded LIFO `Stack`, removes entries before checking delivery, and silently discards them when `sendBytes()` sees a disconnected socket. An exception escaping the send call terminates its only worker. Opt-out prevents new calls but does not close the sender or cancel previously pending bags.

**Fix:** Replace this transport implementation with a bounded FIFO worker that owns connection management, contains failures, defines pending-item/opt-out behavior, and exposes delivery/overflow status. Keep local loot processing independent of transport availability. Preserve the existing payload semantics when migrating; Guild Bridge is a separate integration.

**Evidence level:** Synchronous call chain and bundled WebSocket bytecode verified; no external endpoint was contacted. A connection attempt can block for the configured wait, but connected/backoff paths return promptly. Packet loss or a ten-second delay on every drop is not established.

### 4. P2 — Enchant notifications stop when loot sharing is disabled

**Sources:** `src/main/java/tomato/gui/stats/LootGUI.java:135–138,171–177`; `src/main/java/tomato/backend/data/TomatoData.java:1549–1552`; `src/main/java/tomato/realmshark/SendLoot.java:177–191`.

The local notification path calls `isEnchantPing("")`, which always returns false. Real decoded-enchant matching and sound playback occur only inside the sharing path.

**Trigger:** Configure an enchant rule and opt out of legacy loot sharing. A matching captured enchant no longer reaches the alert check. Bag and ordinary item sounds remain independent.

**Fix:** Decode and evaluate enchant rules in local loot processing, before transport submission. Remove the ineffective empty-string check and make sharing consume the resulting snapshot. Verify matching alerts with sharing both enabled and disabled, without opening a network connection.

### 5. P2 — My Info can combine a new character with an old pet

**Sources:** `src/main/java/tomato/gui/myinfo/MyInfoGUI.java:474–490,593–608,626–632`; `src/main/java/tomato/backend/data/TomatoData.java:899–950,1013–1020,1091–1126`.

Player and pet snapshots update independently. Account/map resets invalidate backend metadata but do not clear the displayed pet. A character with missing pet metadata produces no replacement pet publication.

**Reproduction:** After actual clear/account-reset operations, a probe displayed Account B with Wisdom 10 while retaining Account A's 45 mana/sec pet contribution: **46.2 mana/sec** estimated recovery. Explicitly clearing the pet reduced it to **1.2**. Both characters had explicitly empty enchant data, isolating this from finding 12.

**Fix:** Publish player and pet data with account/character identity and a generation. Invalidate mismatched snapshots on transitions. Distinguish an observed absence of a pet from unavailable pet metadata.

### 6. P2 — Saved fame sessions conceal zero-gain history

**Source:** `src/main/java/tomato/gui/stats/session/FameSessionViewer.java:159–181,245–268,287–297`.

Saved character rows, selector choices, and map rows require positive fame gain. Persistence and the live explorer retain zero-gain visits, while Session Info still counts them.

**Reproduction:** A saved/reloaded session containing one character, two equal-fame samples, and a 60-second zero-gain visit displayed **zero character rows, zero map rows, no character choice, and no graph samples**. Session Info reported one character, two fame entries, and one map entry. The data remained intact in the file.

**Fix:** Show all recorded visits/characters by default. Offer an explicit `With fame gain` filter, matching the live explorer. Build character choices from the union of saved fame and visit records.

### 7. P2 — Saved DPS relative filters disagree between display modes

**Sources:** `src/main/java/tomato/gui/dps/DpsGUI.java:209–215`; `src/main/java/tomato/gui/dps/Filter.java:60–73`; `src/main/java/tomato/gui/dps/IconDpsGUI.java:123,195–203`.

Saved Meters receives a null player context, Legacy text uses the latest player snapshot, and Legacy icons use the mutable current player. `My Class` and `My Guild` therefore do not have a stable historical meaning.

**Reproduction:** The same saved two-player encounter showed **0/2 players in Meters** under either relative filter. Legacy text showed a different contributor after switching the current character. Explicit-name filtering worked, and highlight mode retained rows.

**Fix:** Resolve one historical local-player context for the selected encounter and pass it to all renderers. Persist missing context in future recordings; for older files, infer it only when reliable or explicitly disable relative predicates with an explanation.

### 8. P2 — Characters' details become unusable at compact size with enlarged text

**Sources:** `src/main/java/tomato/gui/character/CharacterJournalGUI.java:48–102`; `src/main/java/tomato/gui/TomatoGUI.java:185–195`.

Filters and a vertically split roster/detail panel compete inside `BorderLayout`; the detail minimum is a fixed 170 pixels with no outer scrolling fallback.

**Reproduction:** Headless layout of the actual shell and character tab structure at **680×520 client size, font 24** produced a **zero-height detail pane and zero-height stat viewport** after ten EDT validation turns. Even moving the divider to give details nearly all available height left no content viewport: the detail chrome alone needed 159 pixels. Production's outer-frame minimum provides less client space than this probe.

**Fix:** Reuse `ContentStyle.page()` or an equivalent outer scrolling layout, calculate detail minimums from font metrics plus usable content rows, and preserve access to both roster and details in short windows.

### 9. P2 — Sidebar keyboard focus is visually invisible in the default theme

**Source:** `src/main/java/tomato/gui/modern/WorkspaceShell.java:83–100,176–179`.

Navigation replaces the LAF border with `EmptyBorder` and assigns explicit foreground/background colors. Under the pinned FlatLaf version, this eliminates the normal focus outline and focused-color fallback.

**Reproduction:** A focused/unfocused rendering probe of selected and unselected navigation buttons produced **zero changed pixels** with the production border. Restoring the LAF border produced **1,176 changed pixels**. Focus itself and navigation actions still work.

**Fix:** Retain the LAF focus border and express padding through margins/style properties, or draw an explicit focus-aware outline. Test focused-but-unselected navigation separately from the selected-page highlight.

### 10. P2 — Font-aware heights are undermined by fixed widths and single-line help

**Sources:** `src/main/java/tomato/gui/chat/ChatExplorer.java:152–156,434–441`; `src/main/java/tomato/gui/chat/ChatFilterPanel.java:11–27`.

At body font 24, `23:59:59` needs **98 pixels including renderer padding**, while Time has a hard maximum of **92**. The Channel value `System` needs 91 pixels: it clips at the initial 86-pixel width but fits if widened to the 96-pixel maximum. Neither preferred width adapts to font changes.

The Chat filters introduction needs **690 pixels** but receives at most **636** even when granted the entire 660-pixel dialog content width. Its explanation is truncated. The claim that every checkbox clips at that width was not supported; the longest checkbox becomes sensitive to native dialog insets.

**Fix:** Size semantic columns from representative text and current renderer metrics, rather than hard caps. Use wrapping descriptions and font-aware dialog sizing. Preserve useful horizontal scrolling/resizing for dense data.

### 11. P3 — Long notification filenames clip saved-status messages

**Sources:** `src/main/java/tomato/gui/notifications/NotificationsGUI.java:77,258–276`; `src/main/java/tomato/realmshark/Sound.java:142–151`.

`note()` estimates line count by splitting on spaces. A long filename is treated as a single word even when Swing breaks it across several lines.

**Reproduction:** At width 590 with a valid-length 184-character filename, the status allocated **56 pixels versus 88 required** at font 13; at font 24, **98 versus 278**. A short filename fit.

**Fix:** Measure the actual Swing text view at available width. Consolidate this helper with the view-based wrapping measurement in `ChatFilterPanel.java:66–85`.

### 12. P3 — Missing enchant capture is represented as zero recovery

**Sources:** `src/main/java/tomato/realmshark/ParseEnchants.java:332–347,410–425`; `src/main/java/tomato/gui/myinfo/MyInfoGUI.java:613–633`.

Absent `UNIQUE_DATA_STRING` becomes four empty strings, which decode as zero effects. The catch block intended to keep missing data unavailable does not run.

**Reproduction:** With Wisdom 75 and maximum HP/MP present but no enchant stat, the UI reports **0 enchant recovery and 9 mana/sec total**. Explicit empty enchant data correctly produces zero; malformed `!!!` instead produces unavailable values; a valid +2 MP/sec enchant produces total 11.

**Fix:** Preserve capture validity separately from decoded totals. Distinguish missing, malformed, and successfully decoded zero. Mark combined values unavailable or explicitly partial when required inputs are unknown. This affects a labeled advisory estimate, not captured combat damage.

### 13. P3 — Security equipment cells lack accessible names

**Source:** `src/main/java/tomato/gui/security/ParsePanelGUI.java:517–550`.

Equipment renderers replace the model's item name with an icon and empty text. The renderer and table cell both have null accessible names, as do the generated icons' descriptions.

**Probe:** A `Sword of Acclaim` cell had an empty name, but its tooltip was available as an HTML accessible description. The item is therefore not wholly absent from the accessibility API; actual screen-reader announcement depends on the reader.

**Fix:** Set/reset plain-text accessible names and descriptions on each reused renderer invocation, including unknown/empty states. Make complete equipment/enchant details reachable by keyboard.

## Refactoring and performance priorities

### A. Keep tracking separate from rendering; remove redundant fame samples

**Sources:** `src/main/java/tomato/backend/data/Entity.java:142–150,651–675`; `src/main/java/tomato/gui/stats/FameTablePanel.java:89–117,168–220`; `src/main/java/tomato/gui/stats/FameTrackerGUI.java:151–195`.

Each eligible nonempty local-player status update queues fame-table work, even if fame has not changed and Statistics is hidden. The table rebuilds character/map rows and retains timestamped samples. No production consumer of `FameTablePanel.getFameData(int)` was found; calculations use other state. Graph/session history is maintained separately in `FameTrackerGUI` and is used.

Remove the unused table sample collection/getter after checking API compatibility. Preserve all updates needed for observed time and map boundaries, but coalesce presentation and refresh hidden views on show. Do not drop unchanged-fame timestamps indiscriminately. The unnecessary work and retention are verified; long-session latency and heap impact remain unmeasured.

### B. Request view-specific, revisioned Activity/Logging snapshots

**Sources:** `src/main/java/tomato/gui/activity/ActivityPanel.java:105,118–128`; `src/main/java/tomato/gui/logging/LoggingGUI.java:113–123,193–203`; `src/main/java/packets/packetcapture/logger/DiscoveryLog.java:157–163`; `src/main/java/packets/packetcapture/logger/ActivityJournal.java:297–324,348–361`.

Visible refreshes copy complete activity history on the EDT under the capture observer's monitor, including resource/condition timelines that a Runs or diagnostic table does not use. Full diagnostic export also takes its snapshot before entering the worker. Retention permits 12,000 resource points and 12,000 condition slices across visits.

Use visit summaries for Runs, event rows for Timeline, selected-visit samples for charts, and a separate diagnostics snapshot. Reuse immutable data when revisions are unchanged, and prepare full exports on a worker. Preserve the existing detached-data and freeze semantics. These are allocation/lock-cost opportunities, not benchmarked freezes.

### C. Make preferences persistence batched, ordered, and observable

**Sources:** `src/main/java/util/PropertiesManager.java:15–20`; `src/main/java/tomato/gui/maingui/TomatoMenuBar.java:246–255`; `src/main/java/tomato/gui/maingui/EnchantPingGUI.java:102–118`.

Each setter synchronously truncates/rewrites the full preferences file while holding the same class monitor used by readers. Swing callers perform the I/O on the EDT; Compact default does so three times. Errors go only to stderr, and callers can subsequently say `Saved` despite failure.

Add grouped preference updates, serialize detached snapshots through an ordered/coalescing writer, replace files atomically where supported, and expose success/failure to the UI. Flush accepted updates during orderly shutdown. No frequent-lag or actual preferences-loss claim was established during this review.

### D. Consolidate formatting and shared presentation helpers

The existing `ContentStyle` and statistics `Formatters` are the right starting point, but policy remains inconsistent:

- `stats/Formatters.java:47–52` fixes decimals to `Locale.ROOT`; `stats/session/FameSessionViewer.java:173–175,263–265` uses the machine's default locale. Live and saved versions can therefore use different decimal separators on the same machine.
- Timestamps independently use `yyyy-MM-dd HH:mm:ss` (`Formatters.java:18`), `MM-dd HH:mm:ss` (`activity/ActivityPanel.java:189`), and localized short dates (`character/CharacterJournalGUI.java`, date renderer).
- `StatsUi` contains page, note, table, and metric helpers alongside related helpers in `ContentStyle`. Notifications has a separate manual wrapping algorithm.

Define one policy for user-visible numeric precision/grouping/locale, elapsed durations, and timestamp variants. Compact time-only labels are reasonable when full date/time/zone is available in details. Keep machine exports explicitly specified, for example ISO-8601 UTC. Store numbers as numbers and format in renderers. Share wrapping and table sizing primitives while retaining legitimate feature-specific layouts.

### E. Publish consistent information provenance

Extend the good existing unknown-value and scope labels into a common presentation contract:

- Value and unit.
- Account/character/encounter/session scope.
- Source: captured, saved, API-derived, local assets, or estimated.
- Last observation and live/frozen/stale state.
- Availability: observed zero, absent, not captured, invalid, or unsupported.
- Filtered rows versus retained/total observations, and any retention limit.

My Info already labels ability damage as unimplemented and excludes it (`MyInfoGUI.java:609`), and health recovery explicitly excludes base Vitality/pet Heal (`633`). Those are declared product limitations, not hidden successful calculations. Expand estimates only when mechanics and captured inputs can support them; otherwise keep the limitations prominent.

## Removal and cleanup candidates

1. **Remove repeated startup argument parsing.** `Tomato.java:64–68,80–89` parses `--path` three times. Parse once into startup options and apply once.
2. **Consolidate the duplicate Npcap dialog.** `tomato/gui/warnings/MissingNpcapGUI.java` and `packets/packetcapture/sniff/gui/MissingNpcapGUI.java` are duplicate implementations. Production capture imports the latter; the former appears in branding tests. Retain one implementation and update those references.
3. **Remove the unused fame-table sample history**, preserving the separate graph/session history as explained above.
4. **Retire legacy character panels after checking behavior parity.** `CharacterPanelGUI.java:18–24` mounts Journal/Exalts/Pets, while `84,91–95` still forwards to old singleton-based panels. The current production tree has no construction sites for several of those old panels. Audit vault/maxing/collection functionality before removing the panels and forwarding hooks.
5. **Replace legacy loot sender internals**, including reflective access to WebSocketClient's private reset method (`WebSocket.java:72–79`). Move executable debug/demo mains and large commented experiments out of production paths as that replacement is made.
6. **Remove unused build settings.** `build.gradle:16–26` defines LWJGL/JOML versions and native classifiers with no corresponding dependency use in this build; the Shadow plugin is applied both at `4` and `113`.
7. **Generate version code into a build source directory.** `build.gradle:83–100` writes generated Java into `src/main/java` on each compilation. Register a generated-source output under the build directory with declared inputs/outputs. Explicitly configure the JDK toolchain and intended runtime/API compatibility rather than relying only on source/target 1.8 while documenting JDK 17 development.

Avoid package-wide renaming as an early cleanup. Legacy `tomato.*` classes participate in Java-serialized DPS history; any migration needs an explicit saved-file compatibility strategy. Similarly, keep FlatLaf/Darklaf dependencies while their selectable themes remain supported.

## Recommended delivery order and acceptance criteria

### Phase 1 — Saved-data integrity and data correctness

Fix export collisions; stale pet identity; saved zero-gain fame visibility; historical DPS context; missing-enchant validity; and local enchant alerts.

Acceptance checks:
- Repeated exports preserve existing recordings and debug-rich content; failed writes leave old exports readable.
- Account/character changes never combine mismatched player/pet generations.
- Saved/live counts reconcile for zero-gain and partially observed visits.
- Meters/text/icons select the same players from the same saved encounter.
- Missing enchant evidence does not become an observed zero.
- Enchant alerts work independently of sharing state.

### Phase 2 — Responsiveness and service boundaries

Move connection/configuration/persistence I/O off capture and EDT paths without making UI readers wait for worker-held I/O locks. Replace legacy sender internals; coalesce fame rendering; narrow history snapshots.

Acceptance checks:
- Controlled slow-storage/network doubles do not block EDT heartbeats or unrelated capture updates.
- Queues have explicit capacities, failure behavior, and visible loss/overflow counters where relevant.
- Model tracking continues while hidden views avoid presentation rebuilds and catch up when shown.
- A long synthetic/live session measures EDT latency, allocations, retained history, queue depth, and capture throughput. Define a responsiveness budget on the intended target hardware rather than claiming performance from unit tests alone.

### Phase 3 — Compact layout, keyboard access, and formatting

Fix the Characters collapse, navigation focus outline, Chat widths/descriptions, notification wrapping, and equipment accessible names; consolidate formatters/helpers.

Acceptance checks:
- Test actual in-shell layouts at 1240×800 and 680×520, with fonts 13/16/24, at 100/150/200% scaling.
- Cover empty, populated, partial/stale, filtered-empty, long-name, and error states.
- Every actionable control and selected detail is visible or scroll-reachable after layout settles.
- Keyboard focus is visibly distinct from selection; icon-only content has a meaningful accessible name.
- Numeric/time formatting agrees across live and saved versions, including a non-English locale.

### Phase 4 — Cleanup and regression coverage

Remove redundant code only after the corresponding behavior is accounted for. Add targeted regression assertions for the defects above.

The fresh quest compact screenshot was usable, and a wrapping mismatch resolved after queued layout passes; no persistent quest-layout bug is claimed. However, `QuestGuiTest.java:118–125` only checks one combo's width. `CharacterJournalGuiTest.java` uses a tall standalone compact window and weak positioning assertions. Extend those tests to full control bounds, usable table/detail viewports, scroll reachability, and enlarged text inside the real shell.

## Existing strengths worth preserving

- Semantic typography and colors, font-aware table heights, responsive control rows/grids, and compact labeled navigation already exist.
- Multiple high-volume views use detached snapshots, bounded retention, coalescing, and hidden-view catch-up.
- Fame saves already use ordered background requests and stale-completion protection.
- Activity/Logging distinguish observed visits from clears, capture gaps from continuous coverage, and decoder definitions from live evidence.
- Tests cover capture recovery, account metadata races, persistence concurrency, themes/fonts, and populated UI rendering.

The next iteration should extend these established patterns to the remaining inconsistent paths and close the demonstrated edge cases.
