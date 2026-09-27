# P2 validation and coverage

Base: P1c PR #20 merged as `e973f10` (including `4ca1657`) and verified before P2 began.
P2 is one PR on `claude/realmshark-ui-ux-redesign-cb0914`.

Results are from local execution of the plan. Timings below are the initial focused integration run; the final run is recorded separately below. An interrupted or failed run is not a pass:
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

| Check | Command | Result |
|---|---|---|
| Baseline full suite on merged main e973f10 | test shadowJar | 1088 tests / 0 failures / 0 errors / 0 skipped; no pre-existing failures; shadowJar passed. Saved before code edits in build/p2/evidence/baseline.txt |
| Kit follow-ups (Task 1) | Task 1 focused tests | 42 table/history checks and 14 kit checks passed (overlapping runs) |
| Navigation and Build (Task 2) | Task 2 focused tests | 8 navigation, 49 shell, 36 application/Build and 36 additional neighborhood checks passed (overlapping runs) |
| Journal, snapshot, accessors (Tasks 3–5) | Task 3–5 focused tests | 93 journal, 16 Build, 112 live/backend/Build and 124 accessor/neighborhood checks passed (overlapping runs) |
| Archive, model, sources, refresher (Tasks 6–7) | Task 6–7 focused tests | 11 loot/store, 11 archive/loot, 10 builder, 8 refresher and 34 integrated Home/loot checks passed (overlapping runs). 30 sessions × 40 runs: cold 42 ms / warm 40 ms; target < 2000 ms |
| Home cards (Tasks 8–10) | Focused card tests | 22 passed: Hero 8, Now 3, Today 4, Recent runs 4, Quests 3 |
| Layout, S9 and shell actions (Task 11) | Home package and ShellHookIntegrationTest | 70 integration tests passed, including 2 layout, 3 timing and all shell hook tests |
| S9 A: apply + layout | HomeRefreshTimingTest | median / p95 / max: 1539 / 2230 / 2519 µs; every sample ≤ 16000 µs |
| S9 B: typical tick with dirty-region paint | HomeRefreshTimingTest | 5098 / 6422 / 7076 µs; p95 ≤ 16000 µs; no re-run |
| Logged paint distributions | HomeRefreshTimingTest | Now-and-hero with repaint: 9642 / 12788 / 14145 µs; full-page paint: 3957 / 4897 / 5983 µs (median / p95 / max) |
| Threading | HomeRefresherTest | 0 source calls on EDT; live sources only on home-refresh, archive reads only on home-archive |
| Evidence | HomeEvidenceTest | 16 PNGs in build/p2/ui-test/screenshots/redesign-p2-home plus 2 real-shell captures; all inspected by coordinator. No horizontal clipping or missing-state findings; compact/large-font layouts scroll vertically |
| S1 at font 13 | HomePageLayoutTest, real 1240×800 frame | Viewport 989×648; hero 208 px; maxed y=89–107, fame y=282–376, loot y=574–600: all above fold |
| S1 at font 18 (reported) | HomePageLayoutTest, real 1240×800 frame | Viewport 967×612; hero 259 px; maxed y=116–141 and fame y=346–464 visible; loot y=699–725 below fold |
| Final full suite and JAR | test shadowJar at ac14a3c | 1182 / 0 / 0 / 0 (tests / failures / errors / skipped); shadowJar passed; no regressions versus baseline |
| JAR smoke | isolated java -jar … --help after review fixes | Exit 0; rebuilt at source head 5416b4c |

## Deferred scope

- Hero pet rarity chip (pet data arrives with P3's journal fields); Build moves into the P3 character sheet.
- Quests card expiry countdown (P4, after O1 confirms the expiration format).
- The live boost shows as "+N" beside each stat bar; a painted overlay needs a kit `StatBar` API (P3 if wanted).
- The Now card shows the meter rows and the last key pop line only while the meter has rows, and the meter has rows only when its encounter was entered in exactly the current visit; outside a run it shows area and capture state only (spec §6.1). The key pop stays its own fact, never tied to a run.
- S9 asserts Home's own EDT work (apply + layout ≤ 16 ms on every live tick) and a typical Now-only tick with its repaint at p95 ≤ 16 ms; the worst-case repaint and the full-page paint (first show or page switch, S8) are logged, because Java2D paint time on a shared desktop is noisy (see "Decisions recorded for this phase").


## Diagnostic history and evidence

- Each task's planned red test failed before its implementation, then the focused green run passed; logs are under build/p2/evidence/task*.log.
- One navigation neighborhood attempt overlapped the prior GUI test process and failed to acquire its result-file directory; no test assertions ran. The serialized retry passed all 36 checks (task2-neighborhood-retry.log).
- Preserved baseline XML/HTML: build/p2/evidence/baseline/. Initial S9/S1 XML: task11-timing.xml and task11-layout.xml; archive benchmark: task6-archive.xml.
- Synthetic assets intentionally use dashed placeholders; no live capture or user history was used. Tests restore mode, font, window and navigation preferences.


## Independent review follow-up

The review found four error/lifecycle cases beyond the plan examples. All four were reproduced by regression tests and fixed in 5416b4c: stale empty recent runs retain their warning; unreadable session metadata fails the combined archive read instead of yielding partial totals; stop rejects late live-character publications and transport boundaries clear current state; unreadable character-journal fallback is unavailable rather than empty (live data still displays).

After the full suite, 172 affected tests passed with zero failures/errors/skips, including four added regressions, all Home tests, backend data, CapturePublication, shell hooks and Home evidence. The JAR was rebuilt and its isolated help smoke passed. The full suite was not redundantly repeated after these focused fixes, following the current validation policy. Latest results across the full suite and follow-up cover 1186 unique tests.

Final-source S9 measurements (50 samples, median / p95 / max µs):

| Work | Median | p95 | Max | Result |
|---|---:|---:|---:|---|
| A: Now-and-hero apply + layout | 1598 | 2577 | 4015 | Every sample below 16000 |
| B: Now-only with dirty-region paint | 5725 | 7699 | 7819 | p95 below 16000; no re-run |
| Now-and-hero with paint | 10386 | 14723 | 15341 | Logged |
| Full-page paint | 3998 | 5115 | 6440 | Logged |

Final-source archive benchmark: cold 34 ms, warm 32 ms. S1 geometry remains as recorded above. All 16 Home captures and both shell captures were regenerated; the changed empty/populated layouts were inspected again. Preserved follow-up XML: build/p2/evidence/review-fixes/test-results/test/. Full-suite XML/HTML: build/p2/evidence/final-full/.

The first review-fix runner invocation put --tests after shadowJar, which Gradle rejected during configuration; no checks ran. The corrected invocation passed (review-fixes-retry.log).

## Post-review fixes (Claude, before merge)

A review of PR head 0a11930 found seven issues. Each one was fixed test-first: the new or extended test failed on 0a11930, either on an assertion or at compile time where the fix adds API (NowCard.tick, LiveCharacter.Boundary, Totals.runsRecorded). The fix was then made and the test passed. Fix 2 was also checked by reverting its grace rule, and both new tests for it failed.

1. The Now card's elapsed time moved in 10 s steps while idle in a run. HomePage now runs a 1 s EDT timer that calls NowCard.tick, which updates only the elapsed label and only when its text changes. The pop age and the spoken description stay on the 10 s tick. The timer runs only while Home is showing, not minimized and not closed, and while the applied Now is LIVE with a start time (a53a861).
2. The previous character stayed LIVE for 5 s after a capture stop or an identity change. Each clear now records a LiveCharacter.Boundary:
   - TRANSIENT: a map change, a HELLO with the same credential, a transport reset, or a CREATE or publication for the same character. Only these get the 5 s grace.
   - IDENTITY: a different known account or character, a new HELLO credential, or an account change seen without HELLO.
   - STOPPED: capture stop.
   A later IDENTITY or STOPPED replaces a TRANSIENT reason. The boundary reaches HomeModelBuilder.hero through LiveHomeSources (be3d961).
3. When a re-read failed, the last good archive read was kept only for the window read most recently. HomeRefresher now keeps the last good read and its time for each window. A failed re-read shows that window's own totals as STALE, and UNAVAILABLE appears only if that window never had a good read (f10bd7d).
4. Runs showed "0" when no runs had been recorded. HomeArchive.Totals.runsRecorded is true when any runs-module visit, dungeon or not, was saved in the window's sessions. Without one, TodayTiles shows Runs as unknown with the reason "No runs were saved for this period" (5180541).
5. A LIVE hero showed saved account values without a label. A LIVE hero's account line now uses live values only. Saved values fill gaps only on a STALE hero, which is already labeled (816c95f).
6. After a restart, Home could show the wrong last-known character. CharacterJournal.mostRecentCharacter now ranks by lastObservedAlive, then lastSeen, then file order. A character-list merge sets lastSeen for every character, so lastSeen alone is not reliable (a9d738c).
7. Opening Build with Alt+7, the compact menu's destination listener or Back sent focus to the hidden nav-6 row. For an unlisted page, focus now moves to the page's first focusable component in traversal order, and falls back to the page container, workspace-cards (a57a88d).

Tests run: one serialized Gradle invocation with build dir build/p2fix, covering 28 classes. Totals from build/p2fix/test-results/test are 188 tests, 0 failures, 0 errors and 0 skipped.

- tomato.gui.glance.home.*: 11 classes, 70 tests.
- tomato.gui.modern.*: 7 classes, 38 tests.
- tomato.backend.data: LiveCharacterTest, CharacterJournalV4Test, CharacterJournalTest, CharacterFreshnessTest, and AccountMetadataTest (added because the HELLO and account-reset path changed).
- ui: WorkspaceShellNavigationTest, WorkspaceUiTest and HomeEvidenceTest.
- tomato.gui.chat.ShellHookIntegrationTest and tomato.ShellRouteRegistrationTest.

The full suite and the JAR smoke were not re-run for these focused fixes, following the current review and validation policy.
