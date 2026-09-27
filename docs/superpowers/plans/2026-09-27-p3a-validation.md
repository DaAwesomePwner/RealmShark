# P3a validation and coverage

Base: P2 PR #21 merged as `94db6f6` and verified before P3a began.
P3a is one PR on `claude/realmshark-ui-ux-redesign-cb0914`; P3b follows as its own plan and PR.

Fill every "Record" cell while executing the plan. An interrupted or failed run is not a pass:
keep it below as diagnostic history and record the rerun that passed.

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
| Baseline full suite before any code change: merged main `94db6f6` plus the plan (Task 1) | `GRADLE test shadowJar` | tests / failures / errors / skipped: _to record_; pre-existing failures by name: _to record_ (`build/p3a/evidence/baseline.txt`) |
| P2 follow-ups (Task 1) | Task 1 focused tests | _to record_ |
| Journal v5 (Task 2) | Task 2 focused tests | _to record_ |
| Kit promotions (Task 3) | Task 3 focused tests | _to record_ |
| Sheet frame, route and migrated character tests (Task 4) | Task 4 focused tests | _to record_ |
| Header, Overview, Gear, Exalts, Build (Tasks 5–8) | Task 5–8 focused tests | _to record_ |
| Gallery (Task 9) | `tomato.gui.glance.character.*`, `tomato.gui.character.*`, `LiveCharacterTest`, `FilterBarEvidenceTest`, `ShellHookIntegrationTest` | _to record_ |
| 500 characters (Task 9) | `RosterViewsTest` output lines | refresh (sort, map, apply) median / p95 / max µs, asserted every sample ≤ 50 000 µs: _to record_; one-viewport paint µs and visible cards (logged): _to record_ |
| Home hero → sheet (Task 10) | `tomato.gui.glance.home.*` | _to record_ |
| S2 (≤ 1 click) | `ShellHookIntegrationTest.homeHeroOpensItsSheetForS2AndS5AndBackReturnsHome` | Home "Needs WIS 3 potions" with 0 clicks; the sheet Overview "WIS needs 3" after 1 click: _to record_ |
| S5 (≤ 2 clicks) | same test | hero, then the Exalts tab (2 clicks); eight tier meters (19 tiers filled) and "to next tier": _to record_ |
| Evidence (Task 10) | `GRADLE test --tests "ui.CharactersEvidenceTest"` | 21 screenshots in `build/p3a/ui-test/screenshots/redesign-p3a-characters/`; reviewer and findings: _to record_ |
| Final full suite and JAR (Task 10) | `GRADLE test shadowJar` | tests / failures / errors / skipped: _to record_; new failures versus baseline: none required |
| JAR smoke (Task 10) | isolated `java -jar … --help` | exit code: _to record_ |

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
