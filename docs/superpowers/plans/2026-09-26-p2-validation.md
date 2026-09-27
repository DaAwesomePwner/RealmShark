# P2 validation and coverage

Base: P1c PR #20 merged as `e973f10` (including `4ca1657`) and verified before P2 began.
P2 is one PR on `claude/realmshark-ui-ux-redesign-cb0914`.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass:
keep it below as diagnostic history and record the rerun that passed.

## Coverage

- Tasks 1–2: kit follow-ups (ColumnKind widths follow font changes, ItemSlot names after asset reloads); Home is page 14 and
  the first core entry (Alt+H); My Info is the unlisted Build page 6 (route, Alt+7, Settings search, hero action).
- Tasks 3–7: journal v4 account live fields (v1–v3 load unchanged); LiveCharacter, BuildEstimates and live accessors;
  HomeArchive totals and recent runs with exact VisitRef links; HomeModel builder, sources and refresher.
- Tasks 8–11: five Home cards with loading, empty, unavailable and stale states, Evidence in Analyst, keyboard and accessible
  names; spec §6.1 layout; click-throughs through the Navigator with Back to Home; S9 timing; evidence screenshots.

## Local validation

JDK 17 and Gradle 7.6.4, offline, isolated `build/p2` and `build/p2-cache`, synthetic fixtures only.

| Check | Command | Record |
|---|---|---|
| Baseline full suite on merged main `e973f10` (Task 1) | `GRADLE test` | tests / failures / errors / skipped: _to record_; pre-existing failures by name: _to record_ |
| Kit follow-ups (Task 1) | Task 1 focused tests | _to record_ |
| Navigation and Build (Task 2) | Task 2 focused tests | _to record_ |
| Journal v4, live snapshot, accessors (Tasks 3–5) | Task 3–5 focused tests | _to record_ |
| HomeArchive, model, sources, refresher (Tasks 6–7) | Task 6–7 focused tests | _to record_ |
| Home cards (Tasks 8–10) | `GRADLE test --tests "tomato.gui.glance.home.*"` | expected 22 card tests plus the Tasks 6–7 home tests: _to record_ |
| Layout, S9 and shell click-throughs (Task 11) | `HomePageLayoutTest`, `HomeRefreshTimingTest`, `ShellHookIntegrationTest` | _to record_ |
| S9 timing (Task 11) | `HomeRefreshTimingTest` output lines | A, apply + layout on every Now-and-hero tick (asserted ≤ 16 ms per sample) median / p95 / max µs: _to record_; B, typical Now-only tick with dirty-region paint (asserted p95 ≤ 16 ms) median / p95 / max µs and whether the re-run applied: _to record_; logged: Now-and-hero tick with its repaint and full-page paint (S8) median / p95 / max µs: _to record_; source calls on the EDT: 0; live sources only on `home-refresh`, archive reads only on `home-archive` |
| Evidence (Task 11) | `GRADLE test --tests "ui.HomeEvidenceTest"` | 16 screenshots in `build/p2/ui-test/screenshots/redesign-p2-home/`; reviewer and findings: _to record_ |
| S1 (0 clicks) | `HomePageLayoutTest` real-window check and `p2-home-shell-1240-13.png` | viewport, hero height and the y range of the maxed chip, Fame tile and last run's first loot sprite at fonts 13 (asserted visible) and 18 (reported): _to record_ |
| Final full suite and JAR (Task 11) | `GRADLE test shadowJar` | tests / failures / errors / skipped: _to record_; new failures versus baseline: none required |
| JAR smoke (Task 11) | isolated `java -jar … --help` | exit code: _to record_ |

## Deferred scope

- Hero pet rarity chip (pet data arrives with P3's journal fields); Build moves into the P3 character sheet.
- Quests card expiry countdown (P4, after O1 confirms the expiration format).
- The live boost shows as "+N" beside each stat bar; a painted overlay needs a kit `StatBar` API (P3 if wanted).
- The Now card shows the meter rows and the last key pop line only while the meter has rows, and the meter has rows only when its encounter was entered in exactly the current visit; outside a run it shows area and capture state only (spec §6.1). The key pop stays its own fact, never tied to a run.
- S9 asserts Home's own EDT work (apply + layout ≤ 16 ms on every live tick) and a typical Now-only tick with its repaint at p95 ≤ 16 ms; the worst-case repaint and the full-page paint (first show or page switch, S8) are logged, because Java2D paint time on a shared desktop is noisy (see "Decisions recorded for this phase").
