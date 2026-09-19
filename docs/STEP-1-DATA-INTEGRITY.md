# Step 1 — saved-data integrity and correctness

Tracking: [issue #1](https://github.com/DaAwesomePwner/RealmShark/issues/1). Branch: `fix/step-1-data-integrity`, based on the reviewed baseline `ad2b242` on `main`.

This implements Phase 1 of the [2026-09-19 code review](CODE-REVIEW-2026-09-19.md), findings 1, 4, 5, 6, 7, and 12.

## Completed behavior

- [x] **Preserve existing DPS exports.** Serialize before publishing; claim destinations exclusively and use numbered suffixes. Normal failed writes clean up their own partial files. Existing debug-rich recordings survive later exports with debug disabled.
- [x] **Keep player/pet identity coherent.** My Info receives one detached snapshot with account/character/world-player identity and a capture generation. Clear, HELLO, CREATE, and account transitions invalidate old/pending pet data; delayed metadata cannot attach it to a new owner.
- [x] **Show all saved fame history.** Zero/negative-gain, map-only, single-sample, and empty histories remain accessible. The optional gain filter affects map visits only. Session Info labels total and selected-view scopes, and sampleless selections clear the graph.
- [x] **Use stable historical DPS filters.** Meters, text, and icons share one immutable recorded class/guild context. New encounters preserve it even without local damage. Existing files infer it only from consistent local-player evidence; unavailable relative predicates have a visible explanation.
- [x] **Preserve missing enchant evidence.** Equipped-slot capture distinguishes missing, malformed, partial, and known-empty data. Incomplete enchant or pet evidence keeps combined mana recovery unavailable. Known-empty data contributes zero, and individual equipment details explain unavailable slots.
- [x] **Make enchant alerts independent of sharing.** Local slot-aligned checks happen before optional delivery. Item/enchant matches coalesce to one custom alert per item; malformed slots do not stop other local alerts.

## Compatibility and boundaries

- `DpsData` pins its prior computed `serialVersionUID` (`8052266513416820004L`) and supports streams missing the new optional context field. A fixture produced by the baseline JAR verifies old-file loading. Entity and Projectile serialization identifiers remain unchanged.
- Fame files are read without migration or rewriting. Existing graph/session retention behavior is preserved.
- Existing permissive enchant APIs remain available for other callers; the stricter equipped-capture API is used by My Info.
- Pet metadata distinguishes explicitly absent pets from unknown/incomplete pet information. Weapon-plus-observed-pet damage is explicitly partial while pet information is unknown.
- New export publication uses an exclusive copy. Abrupt termination can leave a new partial file; existing destinations are protected.
- Live capture, real network delivery, audio audibility, and prolonged gameplay performance were not exercised in this implementation pass.

## Regression coverage

Added 37 tests to the regular suite, including:

| Area | Coverage |
| --- | --- |
| Export integrity | Existing files/directories, repeated debug/nondebug exports, collisions in one batch, concurrent writers, serialization and final-copy failures |
| Historical DPS | Old serialized fixture, detached context, no-local-damage archiving, consistent meter/text/icon filtering and highlighting, unavailable context, explicit predicates |
| My Info identity | Actual map/account/character resets, blocked EDT with queued old snapshots, delayed metadata, foreign-owner rejection, explicit absent/unknown/present pets |
| Enchant evidence | All four equipped slots, truncated/malformed data, trailing empty slots, explicit empty stat, valid padded/unpadded codes, mana/health effects |
| Saved fame | Persist/reload zero/negative/map-only/single/flat/empty histories, numeric sorting, filter scope, stable selections, graph clearing, non-mutation |
| Local alerts | Both sharing states, callback ordering, slot alignment, malformed isolation, unknown items, overlapping rules and duplicate item copies |

The new health-regeneration test loads a small isolated XML fixture through the real definition parser and restores definition tables afterward; it does not require a game installation. The XML reader now closes its underlying stream.

## Validation — 2026-09-19

With JDK 17.0.20.1+1 and Gradle 7.6.4:

```powershell
.\gradlew.bat --offline --no-daemon -I scripts/typography-validation.gradle -PrealmSharkBuildDir=build/step1-validation test testUi150 testUi200 shadowJar
java -jar build/step1-validation/libs/RealmShark-v1.2.3.jar --help
```

- **318 regular tests passed**, zero failed or ignored.
- **31 UI tests passed at 150% scaling** and **31 at 200%**, zero failed or ignored.
- Runnable JAR built successfully; `--help` smoke check passed.
- Independent read-only reviews of DPS integrity, identity/enchant handling, saved fame/local alerts, and CI found no remaining blocking issues.
- Reports: `build/step1-validation/reports/tests/{test,testUi150,testUi200}/index.html`.

`.github/workflows/validate.yml` runs the same test/build tasks on Windows with JDK 17 for pull requests and pushes to `main`. GitHub Actions omits `--offline` so a fresh runner can obtain dependencies, uses `build/ci`, and uploads test reports and the runnable JAR. Hosted results are tracked on the pull request.

The hosted UI job prepares a supported primary-display mode with room for a 1240×800 logical window at 200% scaling. `scripts/Set-CiDisplay.ps1` is restricted to Windows GitHub Actions runners; it checks native mode-change results and runs `CheckUiDisplay.java` at 100/150/200% to verify the actual window size and AWT transform. An insufficient runner desktop fails explicitly instead of silently substituting compact layouts for desktop checks. The popup keyboard test also waits for real Swing focus transfer before activating Enter.
