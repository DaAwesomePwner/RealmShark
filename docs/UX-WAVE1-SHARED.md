# Wave 1A — shared trust and recoverable setup

Worker branch: `work/ux-w1-shared`; base: `835e178` (merged baseline).
Status: implementation complete for this slice; focused checks passed. Independent
review, integrated full validation, desktop/scaling evidence and packaging remain
coordinator gates. This document does not close the later INS-2/LOG-2 slices.

## Implemented IDs

- **UX-03:** `Evidence.Source` / `Evidence.Coverage` separate provenance from
  completeness. `ContentStyle.detailsButton` and `showDetails` provide real
  accessible actions with pinned text. Touched views distinguish captured zero,
  unavailable inputs, local estimates, collection state, view pause and history.
- **INFO-1:** all four My Info cards open component/assumption/missing-input
  explanations independently of table filters. Captured/Estimated/Unavailable
  facets use row provenance and availability. The scenario checkbox explicitly
  changes estimates, not captured combat conditions. Existing generation-checked
  player/pet publication and calculation formulas are retained.
- **Early INS-2:** detached rows retain absent stats/mode/equipment. Missing mode
  does not become Non-seasonal; partial stats do not produce an exact maxed total
  or fabricated deficits. Inherited JSON export preserves nulls and escapes text.
  Required unknown stats cannot pass the existing boolean requirement gate.
  Recorded-run details show the retained build/change time, not last-seen time.
  Generic DPS inspection does not treat menu-open snapshot time as capture time.
- **Early LOG-2:** Logging, Activity and Inspect Runs share a current collection
  control. Paused data stays pinned while collector status remains current.
  Saved history has its own wording. Coverage details explain retained interval,
  sampling, decode/trailing errors, withheld observations, omitted deltas,
  delta-cache eviction, observer errors and writer drops/errors. Cache eviction
  is explicitly not event-retention loss. No new event-eviction counter is inferred.
- **UX-06:** the full shell/history opens before asset setup. Choose assets and
  Retry run recovery off the EDT; cancellation leaves the shell available.
  Successful extraction generates replacement object/tile lists and reloads
  catalogs before publishing the success marker. Missing/malformed loaders remain
  reloadable; failed replacements retain usable definitions. Cached assets with
  no source disclose unverified freshness. The saved capture preference is honored
  after initial asset readiness, with typed waiting/receiving/stopping/Npcap/error states. Missing Npcap no longer
  opens an application-exiting dialog; the retained dialog also disposes normally.

## Ownership and exact integration points

Wave1A owns the changed asset loaders/extractor, runtime `Tomato`/`PacketProcessor`,
shared `TomatoGUI`/menu/shell/style helpers, My Info, Inspect and Logging files.
`ActivityPanel` changes are confined to collector/pause/history-state presentation.
`SpriteFlatBuffer` and `UnityExtractor` were required recovery dependencies.

Integration hooks for the coordinator:

1. **Social:** `TomatoGUI.createWorkspace()` still passes `ChatGUI::history` to
   `SessionPanel.wrap("chat", ...)`. Integrate the social lane's `historyWithPolicy`
   factory here when available. The loader contract remains
   `load(SessionStore, String scope, int page, String query) -> SessionPanel.Loaded`.
2. **Progression/quests:** `TomatoGUI.updateQuests(QuestData[])` retains its existing
   signature and delegation. Integrate the progression lane's scoped publication
   API at this forwarding point; no guessed future API is wired.
3. **Progression/presence:** My Info still consumes `MyInfoIdentity` and
   `PetAvailability` via `updateSnapshot`. Preserve those checks and distinguish
   incomplete metadata from an explicitly absent pet. Inspect's
   `Player.seasonal(Entity)` / `crucible(Entity)` return boxed booleans (null means
   unknown); they read raw stat presence without changing `Entity`.
4. **Capture generation:** progression should supply its invalidation hook for a
   stopped/replaced capture. Integration points are the final stopped-worker
   callback and fresh-worker start in `Tomato.startPacketSniffer()`. The data owner
   must define producer-thread serialization; no capture-owned model is mutated
   from the EDT by this package.
5. **Pet feed picker:** `ParseEquipment.feedPowerCatalog()` returns an immutable
   `Map<Integer,Integer>` of item ID to positive feed power. Existing
   `getEquipmentById(int)` supplies the catalog name/details.

## Validation evidence

JDK 17 / Gradle 7.6.4; main remains `--release 8`. Final source compilation and
test compilation passed. **45 tests passed, zero failures/errors/skips**, using
the following `--tests` selectors:

```text
assets.AssetRecoveryTest
tomato.SetupWorkspaceTest
tomato.StartupInitializationTest
tomato.gui.myinfo.MyInfoEvidenceTest
tomato.gui.myinfo.MyInfoFormattingTest
tomato.gui.myinfo.MyInfoGuiTest.recoveryRequiresAllFourEnchantSlotsAndPreservesKnownEmptyAndValidEffects
tomato.gui.security.InspectEvidenceTest
tomato.gui.logging.CollectionEvidenceTest
packets.packetcapture.CaptureLifecycleTest
packets.packetcapture.logger.InspectHistoryTest
packets.packetcapture.logger.DiscoveryLogTest
```

Invocation: Gradle `--no-daemon --max-workers=2 --project-cache-dir
.gradle-w1-shared -PrealmSharkBuildDir=build-w1-shared test`, with each selector
above. The coordinator's `.tools` JDK, Gradle and `gradle-home` were used by
absolute path. Local reports: `build-w1-shared/test-results/test/` and
`build-w1-shared/reports/tests/test/`. Test preferences/history/working directories
are isolated by the existing build configuration.

These are model and non-window component checks. The complete workspace test
opens an actual synthetic saved Runs view without a native window or capture.
Recovery tests inject synthetic extracted XML at the Unity-decoder boundary, then
exercise real list generation, loader replacement and success-marker publication.
They do not validate a real game resource binary or installed Npcap.

## Remaining validation and boundaries

- Run native keyboard/dialog, compact/enlarged-font, 150%/200%, theme and populated
  screenshot checks serially. Existing window-test label selectors were updated,
  but those suites were not executed here. Add applicable Logging/Activity and
  setup coverage to the coordinator-owned scaling allowlist.
- Run integrated full tests, shadow JAR/build-contract checks and isolated `--help`.
- Review final integrated head independently, especially asset reload readers,
  capture lifecycle/generation hooks and shared-file merge points.
- Full source-session envelopes, per-field historical timestamps, diagnostic
  visit links and additional loss instrumentation remain later-wave work.
- Optional sprite failures use neutral images; core readiness validates definition
  catalogs. Saved auto-start behavior was restored by the review corrections below.

No `TomatoData`, `Entity`, `RealmCharacter`, character/quest, social/alert or
reporting/Bridge implementation files, shared execution ledger, build configuration
or checkpoint were edited. No live
capture, real deliveries, personal data, push, PR or merge was used for validation.

## Independent-review setup corrections

Implemented on primary `feat/ux-wave-1-trust` following review of `efe80ae`:

1. `sniffer=T` auto-starts only after a successful **initial** readiness result.
   Explicit Start/Stop persists T/F through `PropertiesManager`; setup failures,
   retries and unexpected worker termination do not erase that choice. A manual
   setup retry does not itself start capture. Tests use a fake worker factory and
   verify both memory and the isolated preference file after its save completes.
2. Recovery extracts into a new private `assets/.realmshark-cache/generation-*`
   directory. It builds lists and prepares every core catalog there before
   atomically replacing `assets/realmshark-cache.current`. Loaders and sprite readers
   resolve the selected generation; legacy `assets` files and previous generations
   remain untouched. Failed preparation or pointer replacement retains the previous
   files, catalog objects and successful marker. Only the private failed staging
   directory is discarded. Existing legacy caches still load without a pointer.
3. `ParseDungeon` prepares a new catalog and swaps its volatile reference before
   readiness. Missing-assets fallback is no longer permanent after recovery.
4. `AbilityScalingManager` parses fresh rule/projectile maps and publishes them as
   one immutable-map snapshot. Removed rules disappear; failed parsing retains the
   prior usable snapshot. The asset transaction prepares these rules before commit.

Final correction checks: **50 passed, zero failures/errors/skips**, with JDK 17,
Gradle 7.6.4 and `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. Exact selectors:

```text
assets.AssetRecoveryTest
tomato.backend.data.AbilityScalingReloadTest
tomato.CaptureHookIntegrationTest
packets.packetcapture.CaptureLifecycleTest
packets.packetcapture.pconstructor.PacketConstructorRecoveryTest
tomato.realmshark.DungeonCatalogTest
tomato.realmshark.ParseDungeonCatalogTest
```

Build output: `build/ux-w1-setup-review`; project cache:
`.gradle/ux-w1-setup-review`. Main/test compilation passed; main retains Java 8
targeting. Tests cover obsolete malformed XML plus repeated fresh recovery, a late
semantic validation failure, denied pointer publication, selection after simulated
process restart, new dungeon portal/modifier metadata, and removal of an existing
50-point scaling bonus and projectile association.

Remaining gates: independent review of these corrections, real resource-binary
conversion, serialized desktop/scaling checks and integrated packaging/CI. No GUI
test or native capture was run for these corrections. Prior successful generations
are retained intentionally; automated cache cleanup is not part of this fix. The
pointer requires atomic replacement support and fails safely if unavailable.

## Active-cache readiness and image-coherence follow-up

Following review of `75011ce`, replacement-attempt success is separate from active
cache readiness. A failed replacement retains the previously validated active
cache and manual capture availability. Retry validates a usable cache even if the
original resource file is gone; it repairs from a source only when necessary and
does not auto-start capture. Failure to validate the active cache itself still
withholds readiness. Feedback identifies when the old cache remains usable.

Image publication now shares `ImageBuffer`'s existing read monitor. Coordinate
decoding, catalog parsing and disk-pointer commit happen outside that monitor;
the in-memory root, texture/catalog maps, prepared coordinates and image-cache
clear are installed together. Writers remain serialized by `AssetExtractor` and
do not invoke Swing or wait for the EDT while holding the image monitor. Old
generation files remain available to readers. Loot retains logical item icons
that resolve through the current image cache when painted, rather than retaining
an old bitmap indefinitely.

Deterministic tests pause a reader after its old coordinates are known, and pause
another reader with an old atlas cached. They wait until the publisher is blocked
on that reader's image monitor, then verify a red old tile followed by a blue new
tile whose coordinates and texture mapping moved. Fixtures use real XML,
FlatBuffers and PNG data. A separate test retains a Loot icon across publication
without new loot rows. The runtime test deletes the original resource, rejects
two invalid replacements, retries the retained cache, and starts only an explicit
fake capture worker. The first run exposed leaked decoder file handles; header
and serialized-data readers now close on success/failure, and unterminated
resource strings fail at EOF instead of looping.

Focused headless validation: **25 tests passed, zero failures/errors/skips**.
Build/cache: `build/ux-w1-cache-coherence` and `.gradle/ux-w1-cache-coherence`.
JDK 17 / Gradle 7.6.4, existing Java 8 main target, with
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. Exact selectors:

```text
assets.ImageGenerationTest
tomato.AssetReadinessRecoveryTest
tomato.gui.stats.LootIconGenerationTest
assets.AssetRecoveryTest
tomato.CaptureHookIntegrationTest
tomato.backend.data.AbilityScalingReloadTest
packets.packetcapture.CaptureLifecycleTest
```

Native/scaled GUI validation and settled My Info/Loot clipping checks belong to
the coordinated fixture/full-test pass. No layout pass is claimed here. The
fixture writer's tests and the shared execution ledger were not edited by this
package. Independent review of the final head remains required.
