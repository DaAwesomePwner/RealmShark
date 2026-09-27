# P1a design kit validation — 2026-09-26

P1a continues on `claude/realmshark-ui-ux-redesign-cb0914` from P0 merge
`9b6844a` (PR #17). That merge tree matches reviewed P0 head `3f2e997`.
P1b and later phases have not started.

## Delivered behavior

- New `tomato.gui.kit` components provide semantic colors and typography,
  honest value states, table column roles, controls, cards, tiles, collapsibles,
  filter drawers, customizable tabs, game widgets, mode state and reduced motion.
  Existing pages are unchanged; adoption starts in P1b/P1c.
- Sprite wrappers refresh after asset generation changes. Filter replacement
  preserves loading-disabled state. Tabs retain a reachable Simple-mode page,
  including recovery from saved visibility state. Card input controls keep their
  own interaction, and replaced content releases the card's event handlers.
- The source launcher probes every local `.tools/jdk-*` candidate and selects
  the newest compatible Java 17+ runtime by numeric version, release status and
  build. A compatible local runtime takes precedence over PATH. Broken/old local
  candidates are skipped; PATH is validated only when no local candidate qualifies.
- The roadmap records P0 as merged and the same user-requested branch for every
  phase. Historical per-phase branch commands are superseded.

## Evidence

Toolchain: Temurin 17.0.20.1 and Gradle 7.6.4 on the native Windows desktop,
using offline dependencies, isolated `build/p1a` and `build/p1a-cache`, synthetic
history and isolated preferences.

- Focused kit run: 49 tests passed before the final Card regression was added;
  `shadowJar` passed. Two intermediate gallery runs failed because the test
  searched descendants for a root scroll pane. The corrected gallery run passed;
  those failed attempts are not counted as successful validation.
- Windows PowerShell 5.1 `scripts/Test-JavaRuntime.ps1` passed with synthetic
  candidates and the actual local JDK 17. Coverage includes all-folder probing,
  numeric patch/build ordering, release versus early-access versions, invalid and
  Java 8 candidates, local precedence, and compatible/incompatible PATH fallback.
- The synthetic gallery covers Violet Dark/Light at 1240x800 and 680x520 with
  fonts 13/18. Lower viewport captures cover content below the fold. Coordinator
  inspected representative captures; the independent reviewer inspected all 14
  images across both themes, sizes and fonts. Compact content is scrollable; page-level filter density and
  adoption acceptance belong to P1c.
- Independent source review found stale Card slot occupants and retained click
  listeners after removal. Both were corrected and reviewed again. The new
  regression checks detachment, slot contents, reparenting and later-added children.

- Final source head `266428c`: **1,024 tests, zero failures/errors/skips**, including
  50 kit tests; `shadowJar` passed. This is the single end-of-phase full run.
- Final JAR contract probe passed for product/upstream/cache identity, Java 17
  class headers and the UnityPy notice. Isolated JAR `--help` exited 0.
- Independent final-head review is recorded on the PR after the documentation
  commit. No unresolved source findings remain after the Card correction.

Local logs and reports remain in `.tools/p1a-*.log`,
`build/p1a/test-results/test`, `build/p1a/reports/tests/test`, and gallery captures
in `build/p1a/ui-test/screenshots/redesign-kit`.

No live capture, bridge delivery, display-mode change, scaled suite or Windows
bundle cycle was performed. CI is manual-only. The one final full suite follows
the phase plan; focused checks were used while implementing the kit.
