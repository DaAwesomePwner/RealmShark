# Step 4 — cleanup and final review closure

Tracking: [issue #7](https://github.com/DaAwesomePwner/RealmShark/issues/7). Branch: `chore/step-4-cleanup`, based on merge `0d77570` of [Phase 3 PR #6](https://github.com/DaAwesomePwner/RealmShark/pull/6).

The [post-merge main CI run](https://github.com/DaAwesomePwner/RealmShark/actions/runs/35487249823) passed before this phase. This is the fourth and final implementation phase of the [original code review](CODE-REVIEW-2026-09-19.md).

## Cleanup completed

| Candidate | Disposition |
| --- | --- |
| Repeated startup path parsing | Removed the two duplicate calls. The single existing parser and help/preview/path precedence remain. |
| Duplicate Npcap dialog | Retained the packet-capture implementation and its branding regression; removed the unused Tomato duplicate. |
| Redundant fame-table sample history | Already removed in Step 2; graph/session history and its regressions remain. |
| Unmounted legacy character panels | Audited and retired as detailed below; relocated the live Pets/Fame updates and preserved model ingestion. |
| Legacy loot sender internals/reflection | Replaced in Step 2; bounded transport, wire/merge semantics, opt-out, and local-alert regressions remain. |
| Unused build declarations | Removed LWJGL/JOML/native-selector settings and the redundant Shadow application, without dependency upgrades. |
| Generated version source in `src` | Replaced with a declared build-directory generator; explicit compiler/API policy and multi-build verification are now part of CI. |

### Legacy character retirement

Removed these unused GUI implementations:

- `CharacterListGUI`
- `CharacterStatsGUI`
- `CharacterExaltGUI`
- `CharacterStatMaxingGUI`
- `CharacterCollectionGUI`

Their constructors had no production, test, reflective, or script callers. Their singleton refresh methods returned immediately because no instance was created. `DungeonCollection`, used only by the retired collection view, was removed too. `CharacterPanelGUI` remains the mounted Journal/Roster, Exalts, and Pets container; its dead layout helpers and forwarding methods were removed.

The old forwarding path also contained **live** work. Accepted roster publication still schedules equipped-pet and Fame baseline updates together on the EDT. The cleanup preserves:

- Current-character completion decoding and the missing-completion Key-pop decision path.
- Regular/seasonal vault packet routing, multipart totals, and character-inventory counter rebuilding.
- Account/character metadata generation checks, captured-value precedence, and pet ownership.
- Global and journal exalt updates, including the callbacks that enable local loot processing.
- Journal persistence and current Roster/Exalts/Pets behavior.

New `CharacterPublicationTest` cases exercise those publication boundaries rather than merely checking class names. The published JAR was also inspected to confirm the retired classes are absent.

**Retirement is not a full feature-parity claim.** Quickslot/belt display, the old per-character dungeon matrix, collection checklist, aggregate exalt Total/Missing rows, and multi-character/vault potion planning were already inaccessible in the mounted application. This cleanup removes their dead implementations without introducing replacement features. Underlying vault and completion models remain even where no current screen displays their full contents.

No persisted class/package was renamed. Existing old-JAR deserialization and Entity/Projectile UID regressions still pass.

## Build and package maintenance

- JDK **17** is the explicit Gradle compiler/test/build-tool toolchain.
- Main source compilation uses **`--release 8`**, enforcing the existing Java 8 language/API/bytecode boundary. Tests and branding tools target 17; the tested and packaged runtime remains 17. This does not establish Java 8 runtime support for every shaded dependency.
- Product `realmshark.version.Version` is generated under `<buildDir>/generated/sources/version/main/`. The task declares its version input and output directory; the source set carries its producer dependency. No generated product source is tracked under `src`.
- Product version stays `v1.2.3`; the separate Tomato upstream/cache values stay `v1.9.2` and `v1.9.1`.
- Nondefault build-contract versions require an isolated canonical output directory. Normal-output aliases, source locations, unsafe ancestors, and roots are rejected. Dedicated build subdirectories and external temporary output locations remain available.
- UnityPy's existing MIT notice is included in `META-INF/licenses/UNITYPY-LICENSE.txt` inside the application JAR and at the Windows bundle root. The verifier compares both to the source notice.
- The build-contract probe reads the explicitly built JAR with an isolated classloader, checking the generated constant, inlined AppIdentity version/title, entrypoint, unique selected classes, Java 8 class headers, separate compatibility versions, and notice bytes.

`Test-BuildMaintenance.ps1` verifies:

1. Unsafe output overrides fail during configuration, before tasks run, without changing normal outputs.
2. A fresh default JAR generates the expected version.
3. An unchanged repeat is `UP-TO-DATE` and does not rewrite the generated file.
4. Changing only the version in the same isolated output invalidates generation and updates inlined consumers.
5. Repeating that override is again up to date.
6. A second fresh output builds the default version correctly.
7. Source paths, hashes, lengths, and write times are unchanged across the entire sequence.

This checks incremental behavior with build-cache restoration disabled. It does not claim shared build-cache coverage or byte-for-byte reproducible ZIP output.

## Quest regression closure

Replaced the weak standalone screenshot-width check with observable usability and data-state tests. Small presentation corrections use the shared wrapping/page/focus helpers:

- Three usable roster rows and three detail text lines, font-aware filters/columns, divider constraints, horizontal scrolling, and outer page scrolling.
- Complete long/unbroken/multiline descriptions, expiration, requirements, and choice/all-reward explanations, including null or failing asset lookups.
- Stable-ID selection and pinning after reordered/updated packets, filtered-empty states, reset, and obsolete-detail clearing.
- A scrolling Name Types form with keyboard OK/Cancel and unchanged Java Preferences storage semantics.
- Static labels skipped by keyboard traversal; controls/editors/details reveal themselves through nested viewports.
- A mixed-page publication test through the real `TomatoGUI.updateQuests` method with an injected Quest target, Notifications page, font/theme changes, and preview capture guard.

Exact **1240×800 / 680×520 logical client** matrices run offscreen with fonts **13/16/24**. Native-window cases test realized dimensions and report host clamps; they do not skip or claim exact-size coverage when clamped. Keyboard tests post AWT events through actual dispatch/bindings. The publication test is not a full application-startup or live-capture test. No hardware-key or screen-reader validation is claimed.

## Original finding disposition

All 13 original findings have implementation and retained regression evidence:

| Finding | Implemented in | Principal regression evidence |
| --- | --- | --- |
| 1. Export overwrite | Step 1 | `DpsExportTest`, `DungeonListTest` |
| 2. Bridge I/O lock stalls | Step 2 | `BridgeResponsivenessTest` |
| 3. Blocking/unreliable legacy transport | Step 2 | `LootDeliveryTest`, `SendLootTest`, `WebSocketTest`, `LootLogTest` |
| 4. Sharing-dependent enchant alerts | Step 1, retained in Step 2 | `LootNotificationTest` |
| 5. Stale pet identity | Step 1 | `AccountMetadataTest`, `MyInfoGuiTest` |
| 6. Hidden zero-gain saved history | Step 1 | `FameSessionViewerTest` |
| 7. Inconsistent historical relative filters | Step 1 | `HistoricalDpsFilterTest`, `DpsDataTest` |
| 8. Collapsed Characters details | Step 3 | `CharacterJournalLayoutTest` |
| 9. Invisible navigation focus | Step 3 | `WorkspaceShellNavigationTest` |
| 10. Font-clipped Chat columns/help | Step 3 | `ChatConsistencyTest` |
| 11. Clipped notification messages | Step 3 | `NotificationsConsistencyTest`, `ContentStyleTest` |
| 12. Missing enchant evidence becoming zero | Step 1 | `EquippedEnchantCaptureTest`, `MyInfoGuiTest` |
| 13. Equipment accessibility names | Step 3 | `ParsePanelRefreshTest` |

The original review remains a historical record. Declared product limits—such as incomplete capture, unimplemented ability estimates, uncertain external delivery, and already-unmounted legacy features—are not promoted into supported functionality by closing this implementation plan.

## Validation evidence

Local JDK 17.0.20.1+1 / Gradle 7.6.4:

- **464 regular tests passed**, zero failures/ignored (**19 more than Step 3**).
- **60 UI checks passed at 150%** and **60 at 200%**, zero failures/ignored, including all seven Quest consistency cases.
- Fresh main compilation passed `--release 8`; tests compile/run with JDK 17.
- Build-maintenance positive/invalidation and five output-rejection checks passed. Source-tree snapshots remained equal.
- `Test-RuntimeJar.ps1` passed immutable running-JAR isolation, identical-build reuse, distinct-build paths, and corruption detection.
- The Windows bundle builder ran the full regular suite again in a fresh output directory, then verified the ZIP checksum, staged/source hashes, manifest, product/compatibility versions, eight EXE icon sizes, public-only layout, runtime commands, and notices.
- The bundled runtime successfully ran the packaged application JAR's `--help` entry point.
- Independent code/build/coverage reviews completed; reported focus and isolation issues were corrected and re-reviewed.

The combined local terminal operation was interrupted after the passing regular and 150% reports were written; the full 200% task was then completed separately. These totals come from the completed task reports, not the interrupted process status.

### Reproduce

With JDK 17 and a populated Gradle cache:

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --continue --project-cache-dir build/step4-validation-cache -I scripts/typography-validation.gradle -PrealmSharkBuildDir=build/step4-validation test testUi150 testUi200 shadowJar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-BuildMaintenance.ps1 -JavaHome "$env:JAVA_HOME"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-RuntimeJar.ps1 -JavaHome "$env:JAVA_HOME"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build-WindowsBundle.ps1 -JavaHome "$env:JAVA_HOME" -GradleHome ".tools/gradle-7.6.4"
```

Reports: `build/step4-validation/reports/tests/{test,testUi150,testUi200}/index.html`. Build-contract logs and source fingerprints are retained under `build/build-maintenance-*` and uploaded by CI.

Verified local package: `build/share/RealmShark-Windows-x64.zip`, with `.sha256` sidecar. The recorded package checksum is `F0E8DA011B558FA13B10C1B3525BCCC107B8078B757E1C6CE2519FAF3E1A74F9`. A timestamped archive is retained separately; future builds can replace the latest download. The builder also refreshed the normal launch JAR through its existing verified publication mechanism.

Hosted Windows CI runs the full regular suite, JAR/help checks, and the new multi-build contract verifier. The scaled suites and complete Windows ZIP verification above are local evidence. No new live gameplay soak, real remote-delivery acceptance, or screen-reader session was performed in this final phase.
