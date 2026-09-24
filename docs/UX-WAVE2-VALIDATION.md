# Wave 2 validation and review

Status on 2026-09-24: stopped at the user's request after completing the current
validation task. One scaled failure remains; this is not a merge authorization.

## Final stopping-task results

Integrated code commit: `ad21a1fe3091324a7d773b69a326b2566071e0eb`.
Verifier worker `0b6fe5c` has identical `src`, `scripts` and `build.gradle` content.
The complete sequential command used JDK 17 / Gradle 7.6.4, isolated build/cache
directories, synthetic fixtures and isolated preferences; it continued through
all tasks and exited **1** after 8 minutes 4 seconds.

| Task | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| `test` | 800 | 0 | 0 | 0 | Passed |
| `shadowJar` | — | — | — | — | Passed |
| `testUi150` | 174 | 1 | 0 | 0 | Failed |
| `testUi200` | 174 | 0 | 0 | 0 | Passed |

The sole failure is
`ui.WorkspaceShellNavigationTest.realFrameFocusPaintDiffersFromSelectionAndReproducesTheOldEmptyBorderDefect`:
`awaitFocus` at line 329, called from line 186, timed out after five seconds waiting
for keyboard focus following `requestFocusInWindow`. It failed before the focus
paint comparison. XML does not identify the failing theme/font loop variant.
The cause is unestablished; do not label it infrastructure-only or waive the gate.

Local evidence in the verifier worktree: `build/ux-wave2-gate/validation.log`,
`validation.exit`, `test-results/`, `reports/`, `ui-test/`, `ui-Ui150/` and `ui-Ui200/`.
The coordinator checked the XML totals and exit marker against the verifier report.
No retry or repair cycle was started after this result, in accordance with the
user's stop instruction. No owned Gradle/test JVM remained after completion.
Raw reports/screenshots are not part of the portable checkout; regenerate needed
artifacts on resumption. An earlier interrupted attempt produced no results.

## Recovered baseline

Wave 1 PR #11 and main CI `35696825491` were verified live before resuming Wave 2.
The original resumed full preflight ran 799 tests with 16 failures, zero errors
and zero skips. It exposed real native layout defects as well as stale selectors
and diagnostic fixture expectations. Its JAR build succeeded, but the test run
is a failed diagnostic baseline, not final evidence.

## Repairs and focused evidence

- Cached compound controls restore parent-first, and inactive Inspect Runs cannot
  overwrite the Current Area roster.
- Wrapped controls and nested archive pages retain their minimum content height;
  table and detail viewports scale with rows and font metrics.
- Long captured Character account/class labels no longer stretch selectors. Full
  literal values remain in tooltips and accessible descriptions, including labels
  beginning with HTML-looking text.
- Logging's full page scrolls in compact windows. Inspect advanced facets have an
  explicit keyboard-accessible disclosure, active count and Reset.
- Passive metadata carets cannot scroll an unfocused read-only workspace. Explicit
  Find/programmatic scrolling and focused caret behavior remain available.
- Inspect's container-level saved-view controls scroll with the page instead of
  consuming a fixed footer below the roster.

The verifier uses isolated synthetic histories and the in-memory PreferencesFactory.
Fixture repairs select the actual archive controls instead of hidden live controls,
assert typed unknown values plus their rendered text, and inject an actual decode
failure when testing diagnostic errors. No capture or bridge delivery is exercised.

Character layout/roster/literal-label and History checks passed a focused 9-test run.
The passive-caret change passed 17 ContentStyle checks and archived Inspect workflow
checks. These bounded results do not replace final full/scaled checks.

History keyboard evidence establishes an active native window, actual focus, posted
Swing key dispatch, and the intended callback. An earlier Robot attempt did not
produce the callback; its cause was not established. OS input delivery is therefore
not claimed by the replacement check.

## Independent review

Independent source review through `904ece4` found no remaining blocking issue. A P2
tooltip literal-safety finding was repaired and re-reviewed at `6d0e94f`.
The final test-only delta through `ad21a1f` and portable handoff/contracts were
also independently reviewed with no blockers. This is not final PR-head approval.
The reviewer inspected actual archive query/revision/export and state code, recent
layout changes, passive caret behavior, Inspect ownership and regression validity.

The reviewer independently inspected 22 native screenshots from
`build/native-final/ui-test/screenshots/wave2/` in the isolated verifier worktree:
Activity, Chat, Key-pops, Loot/Statistics, Logging, History, encounter library,
Timeline and Resources, including desktop and compact 24-point text targets.
No blocking visual issue was found in that sample. Table-target screenshots were
captured immediately after scrolling to the actual rows; action/detail screenshots
were captured separately. Ellipsized cells retain full detail paths.

These images support unchanged archive surfaces, not final-head live Inspect or
scaled validation. Some initial files named "error" showed surrounding controls
rather than the error message. The reviewer subsequently inspected three explicit
Chat save/export and Loot export failure-message captures from `native-wrapper`;
the complete messages, synthetic paths and recovery controls were visible/readable.

Final focused wrapper checks passed 4/4 at worker `0b6fe5c`: initial live Inspect
rows and enlarged actions, archived Inspect, and actual Chat/Loot failure messages.
That worker's source, scripts and build configuration exactly match integrated
commit `ad21a1fe3091324a7d773b69a326b2566071e0eb`.

## Build contract

JDK 17 / Gradle 7.6.4 with main Java 8 targeting passed output isolation, clean
source-tree generation, repeat/version invalidation, fresh JAR identity, unique
classes and license checks. Evidence:
`build/build-maintenance-0fd8d9295ccd4cac9a66847a44ad6327/`.
An isolated JAR `--help` smoke test exited 0. An earlier sandboxed attempt could
not read a cached dependency and is superseded by the successful run.

## Remaining gates

Resolve the 150% keyboard-focus failure and refresh affected validation; independently
review final Inspect/scaled images and the final PR head; create the Wave 2 PR,
pass required Windows CI, merge normally, and verify main CI. Waves 3 and 4 remain
unimplemented. See [the handoff](UX-HANDOFF.md) and [checkpoint](UX-CHECKPOINT.json).
