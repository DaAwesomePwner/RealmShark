# Redesign handoff: state, decisions and working notes (2026-09-27)

This document hands the RealmShark presentation redesign to a new agent session, for example a cloud instance that checks out `claude/realmshark-ui-ux-redesign-cb0914`. It collects what was previously only in the local session's memory and git-ignored scratch files.

**Read this first.** Then read AGENTS.md, the [spec](../specs/2026-09-26-ui-ux-redesign-design.md) and the [roadmap](2026-09-26-redesign-roadmap.md). Reconcile everything below with GitHub before acting, because PR state changes.

---

## 1. Where things stand

| Phase | PR | State | Merge commit |
|---|---|---|---|
| P0 Platform (Java 17, Violet themes) | #17 | Merged | `9b6844a` |
| P1a Design kit | #18 | Merged | `7b3e0c5` |
| P1b Shell and navigation | #19 | Merged | `0abafe4` |
| P1c Filters, tabs, columns | #20 | Merged | `e973f10` |
| P2 Home | #21 | Merged | `94db6f6` |
| P3a Characters: gallery, sheet, journal v5 | #22 | Merged (Codex threads fixed in `508d1d1`) | `04a61d4` |
| P3b Characters: Exalts grid, Pets, Pet/Fame tabs | #23 | Merged (Codex fix `9c0da67`) | `b559bca` |
| P4 Quests: Board and Planner | #24 | Merged (Codex: no findings) | `e541874` |
| P5a Runs: combat auto-save, feed, recap | #25 | Merged (Codex fix `21d896e`) | `3ab077c` |
| P5b Runs & DPS, P6 Loot & cleanup | none | Outlines only (roadmap) | |

All phases use the one branch `claude/realmshark-ui-ux-redesign-cb0914`, with one PR per phase against `main`. Merge PRs with a merge commit, not a squash, so the branch can continue. P3b continues from merged `main` (`04a61d4`) on the cloud session branch `claude/redesign-handoff-next-steps-edrr7w`.

### Immediate next steps

Done on 2026-09-27/28 (cloud session):
- PR #22's Codex threads fixed in `508d1d1`; PR #22 merged as `04a61d4` and `main` verified.
- P3b planned as a contract plan ([2026-09-27-p3b-characters.md](2026-09-27-p3b-characters.md)) and implemented with subagent-driven development: four waves of parallel implementer subagents in isolated worktrees, the coordinator reviewing and merging every task, then an independent whole-branch review and a UI polish round from the evidence screenshots. PR #23 merged as `b559bca` (Codex review fix `9c0da67`); `main` verified. Record: [2026-09-27-p3b-validation.md](2026-09-27-p3b-validation.md).
- P4 Quests planned ([2026-09-28-p4-quests.md](2026-09-28-p4-quests.md)) and implemented with subagent-driven development: Wave A (Board models, Planner cards with bars, Home's "Rewards not captured"), Wave B (the Board UI), Wave C (Quests routes and the S3 test), Wave D (evidence, docs, validation record) and a polish round from the evidence review, each task reviewed and merged by the coordinator. Record: [2026-09-28-p4-validation.md](2026-09-28-p4-validation.md).
- PR #24 (P4) merged as `e541874` after a Codex review with no findings; `main` verified (tree equals the reviewed head, `shadowJar` and an isolated `--help` pass).

- P5a planned (user decisions: split P5 into P5a/P5b; the Live meter moves into Runs & DPS in P5b; full combat detail included, off by default; summaries kept forever by default) and implemented with subagent-driven development in five waves plus a polish round, each task reviewed and merged by the coordinator; a pre-existing test-harness leak (`VisualEvidence` frames pinned by Swing's `KeyboardManager`) was fixed so the full suite fits its heap. Record: [2026-09-28-p5a-validation.md](2026-09-28-p5a-validation.md).
- PR #25 (P5a) merged as `3ab077c` after fixing its one Codex finding in `21d896e`: closing the combat autosave waits for queued fights before the history store closes, then cancels the rest with no partial files. `main` verified (tree equals the reviewed head, `shadowJar` and an isolated `--help` pass).
- **Standing user permission (2026-09-28):** when a phase's plan is done and everything is pushed, open its PR so Codex can review it; review and fix any Codex comment; merge once it is fixed, or when Codex leaves no comment. Then verify `main`.

1. **Plan P5b when the user asks** (Runs & DPS tabs, Live meter, Recordings, Dungeons, sidebar changes, S8; see the roadmap outline and the P5a plan's deferred scope).
2. **The expiry countdown phase** (open item O1, deferred by user decision): a read-only "Copy expiration samples…" diagnostic with the list's receipt time, then `QuestExpiry.parse` for the confirmed formats, the Board chip, "expiring today", Home's countdown and S3's expiry half. See the P4 plan's "Deferred scope". It needs one live Daily Quest Room visit by the user to collect the samples.
The P5 research notes (encounters storage, runs feed and recap, shell) live in the session scratchpad; the plan quotes their conclusions.

---

## 2. How this work has been run

### Roles over time

- **P0 to P2.** Claude wrote the plans and reviewed each PR. A separate Codex-style agent implemented them, working in PowerShell and writing a per-phase validation record.
- **P3a.** Claude implemented it with subagent-driven development, at the user's request.
- **Planning and handoffs.** When the user said "write the next plan" or "check the latest PR", Claude did it without further prompting.

### PR review routine (when asked to "check the latest PR")

**Standing permissions from the user:**
- Reply to and resolve bot review threads (Codex), with a short note on the fix or deferral.
- For small, clear-cut regressions of a few lines: push the fix to the branch, verify it with a focused test, resolve the thread and merge.

Always report both kinds of action in the summary. Anything larger: ask the user, offering "I fix it", "send back to implementer" or "defer".

**Scope:**
- Check conformance to the design and plan, and anything left behind: bot threads, restrictions, deferrals, validation notes.
- **Do not run the full test suite** for reviews. Read the diff and the implementer's evidence; run focused tests only for a specific doubt.
- Look for replaced assertions. The implementer twice replaced an existing assertion instead of adding beside it.

**Useful checks:**
- An independent "code vs plan" diff. Apply the plan to a clean export and diff it against the PR: it lists deviations mechanically.
- A separate risk review covering threading, honesty, persistence and tests.

**Mechanics:**
- `main` requires resolved conversations and 0 approvals; CI is manual-only.
- Codex reviews each PR when it is opened, not on later pushes, so a pushed fix is not re-reviewed by Codex.
- Before pushing: `git fetch`, then confirm the remote head is the head you reviewed.
- Merge: `gh pr merge N --merge --match-head-commit <sha>`, then verify `main`'s tree.
- **Hook quirk:** a local `git-blast-radius` hook blocked a command that combined `git push` with `gh api -f ...`, reading `-f` as `--force`. Run `git push` as its own command. Never bypass hooks.

### How plans were written

Claude wrote each plan in stages:
1. A shared contract with exact names, signatures and decisions.
2. Four writer subagents in parallel, each owning a group of tasks with complete code.
3. Assembly of their sections into one plan.
4. A verification agent applies the plan to a clean `git archive` export with a small Python applier. It compiles after every task, runs every new or modified test, and corrects the plan text.
5. An independent review against the spec.
6. A revision pass on the review findings, followed by re-verification.
7. Commit and push.

Conventions every plan follows (see the "Global Constraints" sections):
- PowerShell commands, with `GRADLE <tasks>` shorthand. Commit blocks use one `git add` line with explicit paths and the `Co-Authored-By:` trailer, never `\` continuations.
- Each existing assertion touched is marked "add beside" or "replace" with a reason.
- **Tab restore rule:** startup and live-state restore only `select()` a tab; only explicit navigation may `show()` a hidden one.
- Tests restore every `ui.*` preference and `DisplayModeModel.application()`.
- **Honesty invariants** (spec §1): unknown values are never shown as 0 ("—"), estimates show "≈", stale values are labeled, and links between modules are exact (`VisitRef` or journal key).
- Page indices stay stable until P6. Keep existing component names. Canonical stat order is life, mana, atk, def, spd, dex, vit, wis; exalt arrays are ordered dex, spd, vit, wis, def, atk, mana, life.

---

## 3. Build and test notes

- **Toolchain.** JDK 17 and the Gradle 7.6.4 wrapper. Main sources target `--release 17`. Persisted types stay plain classes, because Gson 2.9.1 cannot read records.
- **Machine-specific paths.** The plans hard-code `RS_TOOLS`, `JAVA_HOME` and `GRADLE_USER_HOME` paths from the original Windows workstation, whose `.tools` folder is not in the repo. On another machine, point `JAVA_HOME` at any JDK 17. Run the first Gradle build without `--offline` so dependencies download, and drop `--offline` if there is no warm Gradle home. Everything else in the plans is machine-independent.
- **Linux cloud sessions.** Install both `openjdk-17-jdk-headless` and `openjdk-17-jre`: the headless package has no `libawt_xawt.so`, so every Swing test fails with `HeadlessException` even under Xvfb. `gradlew` is not executable in a fresh clone; run `sh ./gradlew`. Point the toolchain at JDK 17 with `-Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64` when a newer default JDK is installed. Maven Central can answer 429 for a while on a cold Gradle home; retrying resolves it.
- **Separate build directory.** Use a unique build dir per run: `-PrealmSharkBuildDir=build/<name>`, optionally with `--project-cache-dir build/<name>-cache`.
- **UI tests need a display.** Many tests open real Swing windows, and `ui.*` evidence tests write PNG screenshots under `build/<dir>/ui-test/screenshots/`.
  - On headless Linux, run Gradle under `xvfb-run -a`.
  - Run one Gradle invocation at a time.
  - Never run `scripts/Set-CiDisplay.ps1` on the user's workstation. It is CI-only.
- **Stale build outputs cause phantom failures.**
  - `build/classes/java/test` can keep classes for deleted tests. Before trusting a failing baseline, check that the failing class still has a source file.
  - Alternating build dirs can produce `Failed to clean up output files for task ':compileTestJava'`. Deleting `build/classes/java/test`, `build/generated/sources/annotationProcessor/java/test` and `build/tmp/compileTestJava` clears it.
  - A `--tests`-filtered run refreshes only some screenshots. Check timestamps before comparing.
- **Known flake.** A `ShellHookIntegrationTest` chat-archive preview/read-only assertion failed once in a combined run and passed on rerun.
- **Evidence artifact.** The empty-gallery capture once showed a false "View state save failed" banner. The whole suite shares one `realmShark.properties` in the test working directory; real installs are unaffected.
- **Validation policy (AGENTS.md).** Use focused checks and relevant build or launch smoke checks. Do not routinely repeat full suites; each phase runs one baseline and one final full run. Record results in `docs/superpowers/plans/<date>-<phase>-validation.md`.
- **Safety (AGENTS.md).**
  - No live packet capture and no bridge deliveries.
  - Use synthetic fixtures only.
  - Never publish personal game history, settings, secrets or absolute user-data paths.
  - No force pushes, hook bypasses or direct commits to main.

---

## 4. Implementing a phase with subagent-driven development (how P3a was done)

The skill is `superpowers:subagent-driven-development`. Keep a progress ledger file, for example `.superpowers/sdd/progress.md`. It is git-ignored, so it does not survive a machine switch; this document replaces it for P3a.

1. **Baseline first.** Task 1 runs the full suite on the starting head and saves the totals.
2. **Per task:**
   - Extract the task brief with the skill's `task-brief` script, and dispatch a fresh implementer. It works TDD, commits, does not push, and writes a report file.
   - Generate a review package with `review-package BASE HEAD`, using the base recorded before dispatch.
   - Dispatch a reviewer that returns a spec-compliance verdict and a code-quality verdict. Use the most capable model for risky tasks: persistence, concurrency, routing, large refactors.
   - Send Critical and Important findings back to the same implementer, then re-review. Record Minor findings in the ledger.
3. **Anchor adaptations.** A later brief can be written against code that an earlier task's review fix changed. Tell implementers to apply the intended edit to the current code while preserving the fix, to list every adaptation, and to stop on any other anchor mismatch.
4. **Plan-mandated defects.** When a review finding comes from the plan's own code and the fix does not contradict the plan's intent, fix it and record the decision. When the plan's text genuinely conflicts with its Global Constraints, the Global Constraints win: in P3a the honesty rule overrode the brief line "while a new character loads, the tab keeps its card". Ask the user only for real design choices.
5. **Whole-branch review.** Use the most capable model. The reviewer reads the whole branch diff and the ledger's Minor list, and triages each Minor into fix before merge, defer to a named phase, or drop. Send the fix-before-merge items to one fix subagent, re-review, then open the PR.
6. **Closing checks.** Run a final `shadowJar` and an isolated `java -jar … --help` smoke on the final head.

---

## 5. User decisions (all phases)

- **Direction and delivery:**
  - Glance layer over the existing analyst layer.
  - Keep the Violet palette, with full freedom on layout.
  - Motion is "tiny" (≤100 ms, Reduce motion honored).
  - Phased PRs; a Simple/Analyst mode; evidence text tucked away.
- **Navigation:**
  - Core sidebar plus a collapsed Advanced group.
  - Users can reorder and hide tabs and sidebar rows.
  - Home absorbs My Info; Statistics dissolves into its owning pages.
- **P0 source changes:** retire Darklaf, save more character data, auto-save combat per run (P5), target Java 17.
- **Quests (P4):** badges, an expiry countdown and reward-first cards; no inventory "have it" check. The countdown waits for sanitized samples of the `QuestData.expiration` format (spec O1).
- **Runs (P5):** run cards use exact links only.
- **P2:**
  - One PR.
  - My Info became the unlisted "Build" page 6 (`NavEntry.Group.UNLISTED`); P3a then moved Build into the character sheet.
  - S9 is "strict on our code": apply plus layout ≤16 ms on every live tick, a Now-only tick including repaint at p95 ≤16 ms, and paint maxima logged. Java2D paint on the desktop is noisy.
- **P3:**
  - **Two PRs:** P3a (gallery, sheet, journal v5, Build move, Home hero opens the sheet) and P3b.
  - **Table rows open the full-page sheet.** The side detail pane is retired.
  - **Stat bars keep the "+N" boost text,** with no painted overlay.
  - **The journal moves to version 5.** P2 builds open v5 read-only, so a rollback cannot drop fields.
- **P3b process (2026-09-27):** use subagents for parallel work and for implementation, with Claude as the reviewer (subagent-driven development).
- **P3a implementation:** subagent-driven development by Claude.
  - After the final review, the user chose to push and open a PR.
  - Two scope moves to P3b were Claude's call and flagged to the user: the Overview pet card, and restyling Goals as cards.

---

## 6. P3b plan inputs

### Scope

From the roadmap and spec §6.2:
- **Account Exalts grid** on the Characters page's Exalts tab:
  - one tile per observed class, showing its sprite, total completions, lowest tier and a heat strip of eight tiers;
  - header tiles for the loot boost (`RealmCharacter.exaltLootBonus`) and fully exalted classes;
  - clicking a class opens its Exalts detail.
- **Pets gallery** on the Pets tab: rarity, family and ability bars, with the feeding calculator in a drawer.
- **Sheet Pet tab:** sprite, rarity, family, three abilities with level bars, and a feeding estimate.
- **Sheet Fame tab:** fame over time with fame/hour tiles.
- **Home hero's pet rarity chip.**
- **Deferred from P3a:**
  - the Overview **pet card**;
  - **Goals restyled as cards with progress**.

### Known facts and constraints

- **Pet names.** Pet family and rarity names exist only in `<assetRoot>/xml/pets.xml` (`<Family>`, `<Rarity>`), and nothing parses that file yet. Rarity is numeric (`PET_RARITY_STAT` 84); family is `PET_FAMILY_STAT` 86. Ability names are hard-coded for ids 402–411 in `CharacterPetsGUI`.
- **Explicit "no pet".** The journal stores it as `PetRecord.absent = TRUE`, from an empty `<Pet/>` and the presence key `pet.none`. The UI must show absent as "No pet" and a missing record as unknown.
- **Fame history ids.** Fame history (`AppHistory.FameSample`, the `fame`/`fame-latest` modules) is keyed by the bare character id, which two accounts can share. Disambiguate by the session's account.
- **Vault potions.** `vaultPotions` covers the regular (non-seasonal) vault only. Seasonal characters show no vault count.
- **Existing helpers.** Kit helpers exist: `Banner`, `KitLayouts`, `ItemTiers`, `KitText`, `Card`, `PipMeter`, `StatBar`, `ItemSlot`, `Sparkline`. `CustomizableTabs.addWhen(id, …, visible)` supports conditional tabs. The sheet's tabs are the group `character`; add `pet` and `fame` there.
- **Sheet model.** `SheetModel` and `SheetModelBuilder` are built off the EDT by `SheetPresenter`. Add sections there rather than reading on the EDT.

### Deferred findings to fold into P3b

These come from the per-task and final reviews of P3a:
- **Journal state and parsing:**
  - `CharacterJournal.storageProblem()` detects failure from the "Save failed" message prefix; replace it with an explicit flag.
  - Say "unavailable" only while the journal is unreadable. A failed save with no visible cards should read "No characters match".
  - The `validPet` contradiction check is partial: `absent` together with skin, max ability or ability values is still accepted.
  - Use strict primitive parsing for v5 fields. Currently `"hasBackpack":"yes"` loads as FALSE and `"exp":"123"` is accepted.
  - The `"pet.none"` literal appears in three places; make it a shared constant.
- **Navigation and focus:**
  - `CharactersRouteTarget.restoreState` does not bring the Roster tab forward on Back from another Characters tab.
  - Add a `CharacterRosterViewTest` for a remembered "death" tab through `showSheet(key, null)`.
  - `backFocuses…` proves little: it checks `focusTarget()` only.
- **Presenter and model:**
  - `SheetPresenter.failed()` logs no stack trace and catches only `RuntimeException`. Log the trace, and consider catching `Throwable`.
  - The late-result test cannot fail with a FIFO worker. Unit-test the generation guard instead.
  - While playing, the live identity's "now" timestamps make every rebuild unequal, which resets the Analyst stat table. Skip unchanged sections.
  - `OverviewTab`'s vault staleness uses the system clock; use `SheetContext.clock()`.
  - Tier labels are read on the EDT from the global `RosterDefinitions.current()`. Compute them in the builder, which also fixes blank labels after definitions load.
- **Build tab:**
  - After a character switch, the Build card updates up to about 1 s late (polling).
  - A failed build reads "Loading…".
- **Gallery and evidence:**
  - The empty gallery shows three overlapping "nothing here" messages.
  - `CharactersEvidenceTest` should use an isolated preference store and assert the view-state banner is absent.
  - The two P3a `WorkspaceUiTest` focus tests don't restore `ux.archive.characters-live-roster` or `ui.tabs.character`. Only scratch test preferences are affected.
- **Copy:**
  - Journal backup failure is now named in the status. Consider also explaining recovery in the UI.

### Deferred to P5 and P6

- **P5:** the `LiveHomeSources` reprojection test checks map identity only.
- **P6 de-duplication:**
  - the `HomeArchive` folder-listing helper;
  - splitting `CharacterJournal`'s v5 load helpers (the file grew by 239 lines);
  - stat labels and exalt thresholds are duplicated three times;
  - the journal key is spelled three ways (`BuildRoute.key`, `SheetModelBuilder.liveRef`, `Snapshot.journalKey`), and `liveRef` lacks the 64-hex check.
- **P6 hardening:**
  - `mark()` should force a presenter open;
  - the cached `CharacterRecord` handed to the EDT is mutable;
  - the Build Open button uses `Navigator.current()` with an unchecked key and ignores a false return;
  - `BuildMovedPanel` refreshes only when shown (the page is deleted in P6 anyway);
  - `RouteTarget.redirect` has no documented fallback for a throwing redirect or a redirected open that fails;
  - `BuildTab.host()` has a latent path to the build card.
- **Already in the roadmap:** the P1c deferrals (P5 item 9, P6 item 5) and the numeric page API replacement (P6).

---

## 7. Useful file map (P3a)

**Sheet (`src/main/java/tomato/gui/glance/character/`):**
- Frame and state: `CharacterSheet`, `SheetFocus`, `SheetContext`, `SheetHeader`.
- Model: `SheetModel`, `SheetModelBuilder`, `SheetPresenter` (worker thread `character-sheet`).
- Tabs: `OverviewTab`, `GearTab`, `EnchantDots`, `ExaltsTab`, `BuildTab`.
- Gallery: `CharacterGallery`, `CharacterCardModel`, `CharacterCardRenderer`.

**Roster and routing (`src/main/java/tomato/gui/character/`):**
- `CharactersRouteTarget`, `CharacterRosterView`, `RosterViews`, `CharacterJournalGUI` (list, filters, saved view state), `CharacterPanelGUI`.

**Build:** `src/main/java/tomato/gui/myinfo/BuildRoute.java`, `BuildMovedPanel.java`; `RouteTarget.redirect` in `src/main/java/tomato/gui/route/`.

**Journal v5:** `src/main/java/tomato/backend/data/CharacterJournal.java`, fed from `TomatoData`.

**Docs and evidence:**
- User docs: `docs/CHARACTERS.md`.
- Validation: `docs/superpowers/plans/2026-09-27-p3a-validation.md`.
- Evidence test: `ui.CharactersEvidenceTest`, 21 captures.
