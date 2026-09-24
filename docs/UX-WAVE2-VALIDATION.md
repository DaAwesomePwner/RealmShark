# Wave 2 validation and review

Status on 2026-09-24: final validation in progress; not a merge authorization.

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
rather than the error message; explicit error-message captures are still required.

## Build contract

JDK 17 / Gradle 7.6.4 with main Java 8 targeting passed output isolation, clean
source-tree generation, repeat/version invalidation, fresh JAR identity, unique
classes and license checks. Evidence:
`build/build-maintenance-0fd8d9295ccd4cac9a66847a44ad6327/`.
An isolated JAR `--help` smoke test exited 0. An earlier sandboxed attempt could
not read a cached dependency and is superseded by the successful run.

## Remaining gates

Final live Inspect focus/layout checks; full tests and shadow JAR; applicable 150%
and 200% suites; explicit error-message and final Inspect/scaled image review;
independent final PR-head review; Windows CI, normal merge, and main CI verification.
