# Four-wave UX implementation handoff

Wave 2 was explicitly resumed on 2026-09-24 from the clean, published `6f7d007`
handoff. The earlier stop is superseded. Native geometry/focus test repairs and
user guides are integrated at `fefdaa6`. PR #12 required Windows CI passed 802
tests plus JAR/build-contract/help. Independent source/test/guide reviews and a
46-image native/scaled visual review found no blockers. Final local full/scaled
validation, exact-head approval and merge/main verification remain pending.
Main and its successful Wave 1 CI were reconciled live.

## Start here on another computer

```powershell
git clone https://github.com/DaAwesomePwner/RealmShark.git
Set-Location RealmShark
git switch feat/ux-wave-2-evidence
git pull --ff-only
git status --short
```

For an existing checkout, inspect its status and worktrees first; never overwrite
unfamiliar changes to match this handoff. Fetch and compare with the live remote.

Read `AGENTS.md`, `docs/UX-EXECUTION.md`, `docs/UX-CHECKPOINT.json`, and
`docs/UX-WAVE2-VALIDATION.md`. The checkpoint records the exact tested code commit,
results and next action. Reconcile it with GitHub issue #9, PRs and CI before work.
`main` was verified at Wave 1 merge `64d58d0606a82a956e082fa0dcc07aa18d8fbbfa`;
its successful CI run was `35696825491`. Wave 2 remains on its feature branch.

The ignored `.omc/ux/checkpoint.json` and old isolated worktrees are machine-local
conveniences. They are not required to resume, and their stale owners/processes
must not be treated as active workers on a new computer. Create fresh isolated
worktrees for new work; at most five active subagents and one writer per shared file.
All resumed production/test work is integrated into the Wave 2 feature branch.

## Scope and remaining sequence

1. Finish the Wave 2 gates recorded in the tracked checkpoint. Resolve any failing
   checks without treating an interrupted or partly completed run as a pass.
2. Independently review the final PR head and fresh native/scaled evidence. Publish
   the Wave 2 PR with exact evidence, pass required Windows CI, merge normally,
   verify the actual main merge and main CI, then synchronize local main.
3. Only then create `feat/ux-wave-3-connected-analysis` from verified main and use
   [Wave 3 contracts](UX-WAVE3-CONTRACTS.md) as provisional preparation. Reconcile
   those proposed APIs with the actual checkout. The MAPINFO/reset ordering hazard
   in that note is particularly important for exact encounter/visit identity.
4. After Wave 3 review/CI/merge/main verification, create `feat/ux-wave-4-planning`
   and use [Wave 4 contracts](UX-WAVE4-CONTRACTS.md). It is the fourth and final
   wave, with bounded internal packages rather than additional wave branches.

The 55-ID product backlog is `docs/UX-ROADMAP-2026-09-21.md`; completion states are
in `docs/UX-EXECUTION.md`. The two files with ` (1)` in their names are unchanged
early planning copies preserved during this handoff, not authoritative current
status. Do not resume from their obsolete baseline/pending-state statements.

## Reproduce validation

Install/configure JDK 17, set `JAVA_HOME` to it, and use the checked-in Gradle 7.6.4
wrapper. Main production sources retain Java 8 API/bytecode targeting. A fresh
machine may need network access for the wrapper and dependencies. Use a native
Windows desktop for the UI/focus checks; serialize those checks and packaging.

```powershell
.\gradlew.bat --no-daemon --continue --console=plain --project-cache-dir build/ux-handoff-cache -PrealmSharkBuildDir=build/ux-handoff -I scripts/typography-validation.gradle test shadowJar testUi150 testUi200
.\scripts\Test-BuildMaintenance.ps1 -JavaHome $env:JAVA_HOME
```

Use a new output/cache directory if another process owns one. Inspect every test
task's XML and the command exit code, not just whether a report/JAR exists. Run
the resulting JAR's `--help` from an isolated working directory. Review generated
populated/empty/error, compact/enlarged and scaled screenshots independently.
Never run `Set-CiDisplay.ps1` on a workstation; it is CI-only. Do not start live
capture or send Bridge deliveries for this work. Tests use synthetic history and
isolated preferences; do not substitute personal saved data.

Raw local build reports and screenshots are not committed. The tracked validation
record and checkpoint retain the results and their limits; rerun on the next
machine when those artifacts are needed or the code changes. In particular,
History keyboard evidence distinguishes native focus plus posted Swing key
dispatch from OS keystroke delivery.

## Resume prompt

> Resume the four-wave RealmShark UX implementation. Read AGENTS.md,
> docs/UX-HANDOFF.md, docs/UX-CHECKPOINT.json and docs/UX-EXECUTION.md; reconcile
> actual Git/GitHub state; continue the earliest incomplete Wave 2 gate using
> independent subagents where appropriate. Do not start the next wave before the
> current wave's final-head review, CI, merge and main verification are complete.
