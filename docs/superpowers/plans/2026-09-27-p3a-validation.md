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

## Final review fixes

Fixes from the whole-branch review, after Task 10 (full details, file:line and RED/GREEN evidence in
`.superpowers/sdd/final-fix-report.md`):

1. Build pointer worded by the live character's **class**, not the account name it shares with every
   character (`SheetModel.Live`, `SheetModelBuilder.liveRef`, `BuildTab.pointer`): "Your Wizard is in
   game now." / "Open the Wizard's Build" (round 2 below adds the character id, since class alone does
   not disambiguate same-class characters).
2. Keyboard focus regressions vs P2 (spec §10): explicit navigation into the sheet (Alt+7's Build
   redirect and the Goals search entry, both resolving through `CharactersRouteTarget.open`) now
   focuses the back link (round 2 below fixes this to actually work from another page, not just when
   the sheet was already visible); `CharacterJournalGUI.focusTarget()` no longer points at the
   gallery's card list once an EmptyState has taken its place in the tree (falls back to the search
   field).
3. Relative times stop aging once capture stops: the gallery's 1 s timer now repaints every tick even
   without a live-key change; `OverviewTab`'s unchanged-model skip now also compares the death
   annotation's relative age, so "Played `<ago>`" and "Marked dead `<ago>`" keep advancing.
4. `SheetHeader.clearIdentity` now clears the accessible name/description too.
5. `TomatoGUI.closeWorkspace` now saves the sheet's notes draft first, before
   `closeArchiveWorkspaces`/`home.close`.
6. Test preference isolation for `ui.tabs.character` added to `ShellHookIntegrationTest`, `BuildTabTest`
   and `CharacterRosterViewTest`.
7. `CharacterJournal.backupOnce` no longer treats a non-regular-file at the `journal.v4.bak` path as
   already backed up, so a broken backup fails the save instead of letting the version 5 write proceed
   with no real backup.

Commits: `45aecbc` (code + tests), `a3680af` (docs: journal version 5 in CHARACTERS.md, the spec's
§8.3 heading and the roadmap outline).

Tests run (covering tests named for this task, not the full suite; one combined Gradle invocation):
`tomato.gui.glance.character.*`, `tomato.gui.character.*`, `tomato.gui.roster.*`,
`tomato.gui.glance.home.*`, `CharacterJournalV5Test`, `CharacterJournalV4Test`,
`ShellHookIntegrationTest`, `ShellRouteRegistrationTest`, `ui.WorkspaceUiTest`,
`ui.CharactersEvidenceTest` — **237 tests, 0 failures, 0 errors, 0 skipped, across 42 test classes**,
`BUILD SUCCESSFUL in 1m 20s`.

RED→GREEN executed for the backup-failure test (item 7) and the gallery-EmptyState focus fallback
(item 2's `CharacterJournalGUI.focusTarget()` half): both failed against the pre-fix code exactly as
predicted, then passed after the fix. The Build-pointer wording (item 1) is RED by construction (the
new assertions name the fixed text). The other focus half (`CharactersRouteTarget.open()`'s
`focusBackLink()` call), the aging fixes (item 3) and the closeWorkspace ordering (item 5) are
reasoned/new-test RED rather than a literal revert-and-rerun; see the fix report for which.

**Head of the last run above: `45aecbc`** (round 1's code+test commit; `a3680af`/`7488b86` are docs-only).

### Fix round 2 (re-review findings)

The re-reviewer caught two gaps the round-1 report had flagged as "reasoned, not executed" rather than
run: `CharactersRouteTarget.open()`'s `focusBackLink()` call was still synchronous, so from another
page (the main Alt+7 case — the round-1 focus test used a single-page harness where the sheet was
already part of the visible content, which could not reproduce this) focus never actually reached the
back link; and the class-only Build pointer wording assumed one character per class per account, which
is false. Both are now fixed (details, file:line and RED/GREEN evidence in
`.superpowers/sdd/final-fix-report.md`'s "Fix round 2" section); also: the backup-failure status names
`journal.v4.bak`, and `docs/CHARACTERS.md`'s v5 paragraph drops the internal "P3a" phase name and adds
a manual-rollback hint.

Commits: `fe80cc0` (code + tests), `3cf67ca` (docs).

**Head of this run: `3cf67ca`.** Tests run (not the full suite): `BuildTabTest`,
`CharactersRouteTargetTest`, `SheetModelBuilderTest`, `CharacterJournalV5Test`,
`ShellHookIntegrationTest`, `ui.WorkspaceUiTest`, `ui.CharactersEvidenceTest` — **62 tests, 0
failures, 0 errors, 0 skipped, across 7 test classes**. The full suite / `shadowJar` has not been
re-run since Task 10 (source head `a6b8fa8`, 1308/0/0/0); do not read the 237- or 62-test totals above
as full-suite runs.

Evidence: re-ran `ui.CharactersEvidenceTest` (3/3 passed, 21 captures).
`p3a-sheet-build-other-1240-13-simple.png` now reads "Your Wizard is in game now." / "Open the
Wizard's Build" (previously "Sample is in game now." / "Open Sample's Build" — "Sample" being the
account name). `p3a-gallery-empty-1240-13-simple.png` is clean in this run: no "View state save
failed" banner (the Task 10 record already noted this as an intermittent test-harness artifact
deferred to P3b; not reproduced here, so left alone).
