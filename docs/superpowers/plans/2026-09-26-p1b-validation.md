# P1b shell and navigation validation — 2026-09-26

P1b continues on `claude/realmshark-ui-ux-redesign-cb0914`. Before any new push,
the planning agent's brand-icon fix `ee8d085` was pulled. PR #18 is merged as
`7b3e0c5`; its merge tree matches that fixed head. The branch was fast-forwarded
to merged main before implementation. P1c has not started.

## Delivered behavior and coverage

- Seven core destinations, collapsed Advanced group, and fixed Settings footer.
  Stable IDs persist order, hidden items, pinned items and Advanced open state.
  Context menus, Shift+F10, the context-menu key and Ctrl+Shift+Up/Down provide
  customization. The current page stays visible, even if hidden or collapsed.
- The compact menu follows the same order and grouping. Numeric page indices,
  routes, named actions and existing shortcuts remain stable; Alt+, opens Settings.
  Party, Quests and Settings replace the old sidebar titles. The app lands on its
  first visible core destination; direct test-shell constructors still select page 0.
- Header actions wrap at narrow widths. Capture state uses a pill, preview uses a
  chip, and the setup banner appears only while assets need attention. The mode
  switch and Ctrl+Shift+A share persisted Simple/Analyst state with Appearance.
- Settings hosts the existing Notifications panel and a new Appearance section:
  Violet Dark/Light, Increase contrast, Reduce motion and display mode. Settings,
  Edit > Theme, search and notification routes remain synchronized. Back restores
  both the Settings section and the nested notification view.
- Brand icon type 8 remains the fin. Existing capture/data/query semantics stay
  intact. Page restyling and filter adoption are later phases. Drag reordering is
  deferred to P6 as in the roadmap; keyboard/context-menu reordering is available.

## Local evidence

Prepared Temurin 17.0.20.1 and Gradle 7.6.4, offline dependencies, native Windows
desktop, isolated `build/p1b` output and `build/p1b-cache`, synthetic fixtures and
isolated preferences/history.

- Merged-main baseline: shell navigation, Back actions, kit and brand-icon checks
  passed. `.tools/p1b-baseline.log`.
- Initial implementation: **73 focused tests**, zero failures/errors/skips, plus
  successful `shadowJar`. `.tools/p1b-focused.log`.
- Review regressions: Settings Back initially failed because it restored only
  notification state. A constrained 240px-high window also reproduced the focused
  row scrolling out of view after reordering. The first 360px fixture was too tall
  to reproduce it; no production pass was inferred from that fixture.
- Both corrections passed **34 affected tests**, including shell integration,
  keyboard navigation and notification Back journeys. The reviewer inspected both
  fixes. `.tools/p1b-review-fixes.log`.
- Gallery test passed with **30 captures**: both variants, 1240x800 and 680x520,
  fonts 13/18, plus compact ready-state and lower Appearance viewports. Geometry
  checks cover control text and scroll reachability. At compact size with large
  fonts and missing assets, Settings uses a short scrollable viewport; the initial
  screenshot does not show every control at once. Coordinator inspected actual
  app preview My Info/Chat/Settings and representative gallery/scroll captures;
  reviewer inspected representative shell and Settings captures independently.

- Full suite at `60df570`: **1,056 tests, zero failures/errors/skips**, plus
  successful `shadowJar`. This includes the planned shell-dependent sweep.
  Preserved XML/HTML evidence: `build/p1b/evidence/full-60df570`.
- A final Appearance spacing correction keeps each control near its help text
  instead of stretching groups down tall windows. Dynamic preferred heights retain
  wrapping and font scaling. **18 focused Settings/gallery/application tests**
  and rebuilt `shadowJar` passed afterward; refreshed wide and compact captures
  were inspected. `.tools/p1b-appearance-final.log`.
- Final JAR contract probe passed for product/upstream/cache identity, Java 17
  class headers and UnityPy notice. Isolated JAR `--help` exited 0.
- Independent final-head review is recorded on the PR. Both navigation findings
  were corrected and reviewed again; the final spacing correction was also reviewed.

Logs: `.tools/p1b-*.log`; XML/HTML results: `build/p1b/test-results/test` and
`build/p1b/reports/tests/test`; gallery: `build/p1b/ui-test/screenshots/redesign-shell`.
Earlier failed regressions are not counted as passing validation.

The phase's shell-dependent sweep is included in its single final full run.
No scaled matrix, packaging cycle, live capture, bridge delivery or display-mode
change was performed. CI remains manual-only and was not triggered.
