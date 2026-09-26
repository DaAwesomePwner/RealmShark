# P0 platform validation — 2026-09-26

P0 is implemented on `claude/realmshark-ui-ux-redesign-cb0914`, continuing the
user-requested branch from planning head `33fb56f` and main `894fa29`. Production
changes and tests are committed as `1780923`. P1 is not started.

## Delivered behavior

- Main sources now target Java 17, matching the existing toolchain and bundled
  runtime. The class-header probe expects version 61. The source launcher rejects
  runtimes older than 17 before launching the GUI.
- Violet Dark and Violet Light share palette application, control metrics and
  typography. Increase contrast strengthens outlines, dividers, secondary text
  and focus rings in either variant.
- Darklaf is removed. Saved Darcula/Solarized Dark choices become Violet Dark;
  IntelliJ/Solarized Light become Violet Light. Both old high-contrast choices
  retain their light/dark variant and enable Increase contrast. Unknown values
  resolve to dark. Migration writes both preference values as one asynchronous
  update and does not repeat after normalization.
- Startup, preview and the menu use the same choice resolution. Preview reads
  saved preferences without migrating them. Persisted data schemas are unchanged.

## Evidence

Toolchain: Temurin 17.0.20.1, checked-in Gradle 7.6.4 wrapper, native Windows
desktop, offline dependencies. All checks use isolated `build/p0` output and
`build/p0-cache`; test history and Java preferences are isolated.

- Initial focused run: 51 tests, no failures, plus successful `shadowJar`.
  Selectors: `tomato.gui.modern.*`, `ui.ContentStyleTest`, `*DpsPresentationTest`,
  `ui.WorkspaceUiTest`, `NotificationsConsistencyTest`, `QuestConsistencyTest`.
- Windows PowerShell 5.1 launcher guard: real JDK 17 accepted under the launcher's
  `Stop` error preference; synthetic Java 8 and missing-version outputs rejected;
  synthetic Java 17 and 27 accepted. The guard was executed without launching
  capture or the application.
- Full test/JAR run at `1780923`: 974 tests, zero failures/errors/skips;
  `shadowJar` passed. Full XML and HTML reports are retained in
  `build/p0/evidence/full-1780923` before the focused rerun replaces Gradle reports.
- Independent source review found one light-theme issue: plain menu-item shortcut
  text inherited white on a pale selection background. The palette now sets
  `MenuItem.acceleratorSelectionForeground`; a regression assertion checks it.
  Fresh affected tests after this correction: 53, zero failures/errors/skips,
  plus successful `shadowJar`. Final-head review is recorded on the PR.
- Final JAR contract probe passed: generated/inlined product version, upstream
  and cache compatibility versions, Java 17 class headers and UnityPy notice.
  Isolated JAR `--help` exited 0; archive inspection found no Darklaf classes.
- Eight synthetic appearance captures were inspected: both variants, contrast
  on/off, 1240x800 and 680x520. Palette changes repaint the existing shell;
  stronger outlines and secondary text are visible. Compact pages retain their
  existing scrollable content; reducing their filter height belongs to P1c.

Local logs: `.tools/p0-focused.log`, `.tools/p0-final.log`,
`.tools/p0-review-fix.log`. Reports:
`build/p0/test-results/test` and `build/p0/reports/tests/test`. Appearance captures
use synthetic chat data under `build/p0/ui-test/screenshots/p0-*.png`.

No live capture, Bridge delivery, display-mode changes, repeated scaled matrix
or Windows bundle publication is part of this validation. CI is manual-only and
was not triggered. The full test run is the one end-of-P0 run requested by the
phase plan, not a repeated full suite after each edit.

## Workflow adaptations

The original plan's Git Bash commands and workstation-specific tool path were
adapted to the prepared PowerShell environment. Claude-specific skill and
attribution boilerplate is not an implementation dependency. Independent review
uses an isolated checkout; the coordinator owns checkpoints and GitHub actions.
