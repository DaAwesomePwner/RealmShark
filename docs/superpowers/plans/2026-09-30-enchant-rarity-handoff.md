# Enchant rarity — session handoff (after Phase 3)

Date: 2026-09-30.

**Update: the Phase 4 plan is written.** It is `docs/superpowers/plans/2026-09-30-enchant-rarity-p4.md`, the first commit on branch `feat/enchant-rarity-p4`.

**Next step: execute it task by task,** the same way the earlier phases were implemented (see Workflow below). Start at Task 1.

The "Phase 4 scope" and deferred-item lists below are what the plan covers. Its "Deliberate refinements of the spec" section records the decisions made while planning.

## Where things stand

The feature shows each equipped item's enchant rarity as a corner gem, with the rolled enchants on hover, everywhere equipment is shown.

- Spec (binding): `docs/superpowers/specs/2026-09-30-equipment-enchant-rarity-design.md`. Read its "Phase 4" section, plus Compatibility rules, Error handling and Testing.
- Phase plans, done and merged to `main` via squash:
  - `docs/superpowers/plans/2026-09-30-enchant-rarity-p1.md`, PR #32: shared foundation (`EnchantInfo`, `EnchantGem`, `EnchantTooltip`, `EnchantIconLabel`, `ItemSlot.setItem(int, String, EnchantInfo)`) and Party, DPS, Gear tab and My Info.
  - `docs/superpowers/plans/2026-09-30-enchant-rarity-p2.md`, PR #34: loot surfaces, `EnchantInfo.COUNT_ONLY`/`ofSlotCount`, kit `ItemIcon` plus the `StatsUi` tooltip, and stat potions carrying no enchant data.
  - `docs/superpowers/plans/2026-09-30-enchant-rarity-p3.md`, PR #35: run-recap players; `CharacterRecord.equipmentEnchants` (journal v5, optional); Character sheet, Overview, Gear Analyst and Home hero.
- Each plan opens with its "Deliberate refinements of the spec" section. Read them before planning, because Phase 4 builds on those decisions.

## Phase 4 scope (from the spec)

- **Bridge (`tomato.bridge.BridgePayload`, `BridgeJournal`, `gui/bridge/BridgeReviewGUI`):**
  - New entries compute rarity from the slot count, and `raritySource` marks the new source.
  - Old journal entries keep their stored value (it came from `parse()` line counts and cannot be recomputed). They are labelled "legacy count" in Bridge Review and the CSV export.
  - The Bridge Review item cell gains the gem where a new-style rarity exists.
- **DPS equipment summary (`tomato.gui.dps.shared.EquipmentUsageAggregator`):** it currently keys usage on item id only (`computeIfAbsent`, so the first blob seen wins). Track usage per item id **and** enchant blob, and show the most-used variant. Never add fields or methods to `Damage`, `StatData` or `Equipment`, because they have no pinned `serialVersionUID` and old `.dps` files must keep loading.
- **Unchanged:** the HTTP bridge payload and the `SendLoot` wire fields.
- **Cleanup:** remove the remaining `parse()`-line-count callers. Keep `ParseEnchants.parse` only if something still needs its text (today that is `BridgePayload` and `SendLoot`).

### Deferred cleanup items collected from Phases 1–3 (fold into Phase 4)

- **Tooltip heading.** It differs per surface: `ItemSlot` uses "name · tier", the Party roster uses "Weapon: Name (ID n)", and DPS and My Info use the bare name. Align on "name · tier".
- **Empty enchant strings.** DPS reads a retained empty enchant string as "Enchants not recorded", while Party reads it as "Unenchanted" (`Damage.ownerEnchants` cannot tell a missing stat from an empty one). For new captures, consider retaining `null` for a missing stat. That changes a value, not a field.
- **`EnchantGem.decorate` on non-square icons.** It places the gem from `min(width, height)`. Doc note or fix.
- **Unreachable state.** Guard `EnchantInfo.summary()` for `COUNT_ONLY` + `Rarity.UNKNOWN`.
- **Run-card gem test.** `RunCardRendererTest`'s gem test does not constrain the gem's position.
- **Archive detail indentation.** `LootArchiveClient` double-indents enchant lines: `text().replace("\n","\n  ")` on text that `text()` already indents.
- **Highlights tooltip** repeats the rarity, once in the facts heading and once in the coloured line.
- **Stale test name.** `GearTabTest.enchantGemsShowOnlyForTheLiveCharacter` still says "live only"; its assertion is correct.
- **Size guards.** `RunRecapModel.Players.Player` and `HeroCard` index `enchants.get(i)` without a size guard. The builders always pass 4.
- **Tidy-ups.** Import order in several files. `EnchantGemTest` / `EnchantTooltipTest` coverage gaps: slot-name escaping, and RECORDED + UNKNOWN showing no gem.

## Workflow the user wants (default)

**Codex implements, Claude reviews, and the controller (you) runs Gradle and commits.** The user has spare Codex tokens and wants to conserve Claude tokens.

- **Skills:** superpowers `writing-plans` for the plan, then `subagent-driven-development` to execute it.
- **Workspace:** `bash <skill>/scripts/sdd-workspace <plan>` gives `.superpowers/sdd/<plan>/`, which is git-ignored. It holds `progress.md` (the ledger), an `implementer-contract.md`, and per-task `task-N-brief.md`, `task-N-dispatch.md` and `task-N-report.md`.
- **Dispatch a Codex task** from a `run_in_background` Bash:
  `node "C:/Users/dap/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs" task --write --cwd "C:/Users/dap/Downloads/RealmShark-realmshark" --prompt-file <dispatch.md>`
  Add `--resume-last` for a fix round. The model is `gpt-6-astra` at medium effort, from `~/.codex/config.toml`.
- **Implementer contract**, copied from a previous phase:
  - Codex **must not run Gradle/javac/java** and **must not stage or commit**. The sandbox makes `.git` read-only, and a sandboxed Gradle daemon locks `.tools/gradle-home`.
  - If the built-in `apply_patch` fails with "Failed to write file", Codex falls back to the shell apply_patch: its installed codex executable with `--codex-run-as-apply-patch`, called via Python.
- **Controller loop, per task:**
  1. Run the brief's tests outside the sandbox: `export JAVA_HOME="$(pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(pwd)/.tools/gradle-home" && ./gradlew.bat --offline test --tests <pattern>`.
  2. Commit Codex's files with the brief's message and trailers.
  3. Build the review package: `<skill>/scripts/review-package <plan> BASE HEAD`.
  4. Dispatch a Claude Sonnet task reviewer.
  5. Record every step, ruling and deferred minor in the ledger.
- **Wrap-up:** run the final whole-branch review on the most capable model, then do one fix wave and one scoped re-review.
- **Known environmental test failures** on this workstation, which also fail on `main` and are not regressions:
  - `LootHighlightsTest` (2 window-size tests)
  - `DungeonsSourceTest.theLargeHistoryIsReadOffTheEventDispatchThreadAndKeptPerClosedSession`
  - `HomeRefreshTimingTest.liveTicksKeepHomesEdtWorkWithinOneFrame` (flaky)
  - the window-size and evidence classes in the desktop-failures memory note
- **Build breaks with `AccessDeniedException` on `.tools/gradle-home`:** kill any `java.exe` owned by `CodexSandboxOffline`, run `clean`, and retry.
- **Codex sandbox broken** (`helper_unknown_error: setup refresh had errors`): check `~/.codex/.sandbox/sandbox.<date>.log` for `runtime read/execute validation failed`. It names the stale runtime folder to move aside; one was moved to `%USERPROFILE%\codex-runtime-quarantine\` on 2026-09-30.
- **Launch smoke:** copy the jar and `assets/` into an isolated folder, then run `java -Djava.util.prefs.PreferencesFactory=util.InMemoryPreferencesFactory -Drealmshark.historyDir=<dir> -cp "RealmShark.jar;<repo>/build/classes/java/test" realmshark.RealmShark`. Never start capture.

## PR / merge flow

1. After the user confirms, push the branch and create the PR (`gh pr create --body-file`). Keep the body file outside the git index, and delete it with plain `rm`.
2. The **Codex GitHub reviewer** reviews automatically. It reacts 👀 while running, then 👍 for no findings or leaves inline comments. Every phase so far got one real finding; fix it test-first and reply on the thread.
3. `main` requires resolved review conversations. Resolve the thread with the GraphQL `resolveReviewThread` mutation, using `--raw-field`.
4. Squash-merge with `gh pr merge <n> --squash --delete-branch --subject "<title> (#n)"`, then verify that `git diff <tested tip> main` is empty.
5. **pro-workflow `git-blast-radius` hook:** it blocks commands containing ` -f ` or `rm -f` (it reads them as force push) and `git branch -D`. Use `--raw-field`/`--field`, plain `rm`, and `-d`. Never override the hook.
