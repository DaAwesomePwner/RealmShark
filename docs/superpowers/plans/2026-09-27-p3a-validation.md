# P3a validation and coverage

Base: P2 PR #21 merged as `94db6f6` and verified before P3a began.
P3a is one PR on `claude/realmshark-ui-ux-redesign-cb0914`; P3b follows as its own plan and PR.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass:
keep it below as diagnostic history and record the rerun that passed.

## Implementation method

P3a was implemented task by task by subagents, each following a written plan step and TDD (failing tests first, then the
minimal change to pass them), with an independent spec/quality review after every task before the next one started. Review
findings that needed a code change landed as their own commits on top of the plan, not folded into the task's commit:

- `cbf9c2b` — no phantom pet card for an empty `<Pet/>` (Task 2 review).
- `1d7a163` and `55e4e09` — sheet grace expiry, Death-tab navigation (including list opens), Unknown capture times, and a
  real notes-draft test (Task 5 review).
- `4dc6a90` — a neutral Build card while a new sheet loads or fails, instead of showing another character's data (Task 8
  review, overriding a plan line per the global honesty constraint).
- `0ba31de` — no gallery scroll-jump on selection, a live-refreshing stale-storage banner, and in-place card updates
  instead of full rebuilds (Task 9 review).

Every task's minor, non-blocking review findings are catalogued in the progress ledger
(`.superpowers/sdd/progress.md`) rather than repeated here.

## Coverage

- Task 1: P2 follow-ups (unreadable saved sessions, the per-session archive cache, recording and publish de-duplication,
  the focus fallback) and the full-suite baseline.
- Task 2: journal version 5 (pet, dungeon completions, experience, backpack, per-class exalt times, vault potions);
  v1–v4 load unchanged; a malformed new field is normalized to null.
- Task 3: kit promotions (`Banner`, `KitLayouts`, `ItemTiers`, `KitText`); Home delegates to them.
- Tasks 4–8: the character sheet on page 3 (`CHARACTER_SHEET`, Back to the list), header, Overview, Gear, Exalts and
  Build tabs; the side pane retired; Build moved from page 6.
- Task 9: the roster gallery (painted wrapping cards, Graveyard, sort, Gallery/Table per mode) over the one roster filter.
- Task 10: the Home hero opens its sheet; S2 and S5 click paths; evidence; docs.

## Local validation

JDK 17 and Gradle 7.6.4, offline, isolated `build/p3a` and `build/p3a-cache`, synthetic fixtures only.

| Check | Command | Record |
|---|---|---|
| Baseline full suite before any code change: merged main `94db6f6` plus the plan (Task 1) | `GRADLE test shadowJar` | tests / failures / errors / skipped: 1198 / 0 / 0 / 0; pre-existing failures by name: none (`build/p3a/evidence/baseline.txt`) |
| P2 follow-ups (Task 1) | Task 1 focused tests | Not re-run in isolation during Task 10; passed when implemented (commits 2a2e0b1..9c22b21), review clean per the progress ledger, and every Task 1 test is included and passing in the final full suite below |
| Journal v5 (Task 2) | Task 2 focused tests | Not re-run in isolation during Task 10; passed when implemented (commits 9c22b21..cbf9c2b, review clean after fix `cbf9c2b`) per the progress ledger, and every Task 2 test is included and passing in the final full suite below |
| Kit promotions (Task 3) | Task 3 focused tests | Not re-run in isolation during Task 10; passed when implemented (commits cbf9c2b..ddd3c32, review clean) per the progress ledger, and every Task 3 test is included and passing in the final full suite below |
| Sheet frame, route and migrated character tests (Task 4) | Task 4 focused tests | Not re-run in isolation during Task 10; passed when implemented (commits ddd3c32..fc88495, review clean) per the progress ledger, and every Task 4 test is included and passing in the final full suite below |
| Header, Overview, Gear, Exalts, Build (Tasks 5–8) | Task 5–8 focused tests | Not re-run in isolation during Task 10; passed when implemented (commits fc88495..4dc6a90, review clean after fixes `1d7a163`, `55e4e09`, `4dc6a90`) per the progress ledger, and every Task 5–8 test is included and passing in the final full suite below |
| Gallery (Task 9) | `tomato.gui.glance.character.*`, `tomato.gui.character.*`, `LiveCharacterTest`, `FilterBarEvidenceTest`, `ShellHookIntegrationTest` | 128 tests, 0 failures, 0 errors, 0 skipped, across 27 test classes (measured from the Task 10 final full-suite run below; also passed when implemented, commits 4dc6a90..0ba31de, review clean after fix `0ba31de`) |
| 500 characters (Task 9) | `RosterViewsTest` output lines | refresh (sort, map, apply) median 247 µs / p95 345 µs / max 365 µs, every sample ≤ 50 000 µs (asserted, passed); one-viewport paint 10 051 µs, cards 0..23 of 450 visible (logged) — captured from the Task 10 final full-suite run |
| Home hero → sheet (Task 10) | `tomato.gui.glance.home.*` | 77 tests, 0 failures, 0 errors, 0 skipped, across 11 test classes (HeroCardTest 9, HomeArchiveTest 12, HomeModelBuilderTest 14, HomePageLayoutTest 2, HomeRefreshTimingTest 4, HomeRefresherTest 9, LiveHomeSourcesTest 8, NowCardTest 4, QuestsCardTest 3, RecentRunsCardTest 6, TodayTilesTest 6) |
| S2 (≤ 1 click) | `ShellHookIntegrationTest.homeHeroOpensItsSheetForS2AndS5AndBackReturnsHome` | PASSED. Home shows "Needs WIS 3 potions" with 0 clicks; after 1 click (the hero) the sheet opens on `CharacterFixtures.KEY` at Overview and its needs row later shows "WIS needs 3" |
| S5 (≤ 2 clicks) | same test | PASSED. After the hero (click 1) and selecting the Exalts tab (click 2), the sheet's Exalts tab shows at least 8 `PipMeter`s totalling 19 filled tiers (5+4+3+2+1+0+0+4 of the fixture Wizard) and text containing "to next tier" |
| Evidence (Task 10) | `GRADLE test --tests "ui.CharactersEvidenceTest"` | 21 screenshots in `build/p3a/ui-test/screenshots/redesign-p3a-characters/`; all 3 tests (`galleryRendersPopulatedGraveyardNoMatchAndTableViews`, `sheetTabsRenderForTheLiveAndAnotherCharacter`, `anEmptyJournalShowsTheGalleryEmptyState`) PASSED on first run, including every layout guard (no sideways gallery scroll; Sort/search/"Reset filters" whole, wrapping correctly at 680×520 font 18; the Graveyard directly below the cards; the sheet's Back link always whole). Reviewer: the implementing agent, by reading every PNG. Findings: gallery, Table view, sheet Overview/Gear/Exalts/Build tabs and the empty/no-match/unavailable states render correctly at 1240×800 and 680×520, fonts 13 and 18, in both Simple and Analyst, with no clipped text; the Knight card (unknown maxed) shows outlined pips and "—", never 0/8; Build shows `MyInfoGUI` for the live Wizard and an "Open Sample's Build" pointer for the other character; the unavailable key reads "This character is not in the journal". One unrelated observation: the isolated empty-journal capture shows a "View state save failed" banner from the Task 9 view-state preference save reacting to this test's isolated temp-directory harness; no Task 10 code touches view-state saving, no assertion is affected, and it is not a layout defect |
| Final full suite and JAR (Task 10) | `GRADLE test shadowJar` | tests / failures / errors / skipped: 1308 / 0 / 0 / 0 (110 more tests than the 1198 baseline, from Tasks 2–10); new failures versus baseline: none; `BUILD SUCCESSFUL` in 5 m 35 s; source head `a6b8fa8` |
| JAR smoke (Task 10) | isolated `java -jar … --help` | exit code: 0 (help text printed: Java Version 17.0.20.1, `--help`/`--preview`/`--path` options listed) |

## Deferred scope

- P3b: the account Exalts grid (a tile per observed class, loot boost, fully exalted classes), the Pets gallery, the sheet's
  Pet and Fame tabs, and Home's pet rarity chip (journal v5 already saves the equipped pet, and an explicit "no pet").
- P3b: the sheet Overview's pet card (spec §6.2), which needs P3b's pet names and rarity, and Goals restyled as cards with
  progress; P3a moves the Goals tab unchanged.
- The Characters page keeps its Roster / Exalts / Pets tabs (`CustomizableTabs("characters")`) rather than the spec's
  segmented control; P3b rebuilds Exalts and Pets inside them.
- The gallery's sort and view are preferences (`ui.characters.sort`, `ui.characters.view`), not part of the roster's saved views.
- Stat bars keep the "+N" live boost text; no painted overlay (user decision 2026-09-27).
- Page 6 stays a "Build moved" pointer until P6 deletes it.
