# Four-wave UX implementation handoff

## Current Wave 4 resumption

Latest: Wave 4 implementation is complete in PR #15. The user approved the shortest
finish with required PR CI and independent review, without another complete local
validation/package cycle. See UX-WAVE4-VALIDATION.md for failed-run history and the
explicitly deferred extra native fixture. Finish the PR/merge/main gates; do not restart
the earlier broad visual audit. This update supersedes the in-progress text below.

Wave 3 PR #14 merged as `dfceefb9ab87dd4d37a2429167d740b7ab0a33ee`; main CI
`36245882529` passed. The user approved the recommendations in
`UX-WAVE4-IMPLEMENTATION.md` and authorized implementation on 2026-09-26.
Wave 4 is in progress on `feat/ux-wave-4-planning`. Read that plan and the updated
portable checkpoint first. Shared persistence, manual annotations, quest planning,
ability evidence, loot enrichment and search are being integrated; final gates remain.
The Wave 3 sections below are historical recovery context, not an instruction to
repeat its completed merge. No current Wave 4 check is implied by old Wave 3 evidence.

Waves 1 and 2 are merged and main-verified (PR #11 `64d58d0`, PR #12 `00e2188`).
Standalone PR #13 (per-player damage-by-source breakdown) merged as `6d71a56`.
Wave 3 was implemented on `feat/ux-wave-3-connected-analysis` from `6d71a56` on
2026-09-26 in four lanes plus coordinator integration. Evidence, reviews and the
exact tested head are in [the Wave 3 validation record](UX-WAVE3-VALIDATION.md) and
[portable checkpoint](UX-CHECKPOINT.json). This is the pre-merge snapshot; reconcile
the Wave 3 PR, CI and main state with GitHub before resuming.

## Start here on another computer

```powershell
git clone https://github.com/DaAwesomePwner/RealmShark.git
Set-Location RealmShark
git switch feat/ux-wave-3-connected-analysis   # or main, once Wave 3 is merged
git pull --ff-only
git status --short
```

For an existing checkout, inspect its status and worktrees first; never overwrite
unfamiliar changes to match this handoff. Fetch and compare with the live remote.

Read `AGENTS.md`, `docs/UX-EXECUTION.md`, `docs/UX-CHECKPOINT.json` and
`docs/UX-WAVE3-VALIDATION.md`. The lane handoffs `docs/UX-WAVE3-CORE.md`,
`UX-WAVE3-ANALYTICS.md`, `UX-WAVE3-ALERTS.md` and `UX-WAVE3-INVESTIGATION.md`
document the public APIs (VisitRef/EncounterContext, Route/Navigator, archive
restore, alert drafts and decisions, Bridge journal reader) that Wave 4 builds on.
Reconcile the checkpoint with GitHub issue #9, PRs and CI before work.

The ignored `.omc/ux/checkpoint.json` and isolated `../RealmShark-w3-*` worktrees are
machine-local conveniences. They are not required to resume, and their owners must
not be treated as active workers on a new computer. Create fresh isolated worktrees
for new work; at most five active subagents and one writer per shared file.

## Scope and remaining sequence

1. If the Wave 3 PR is not merged: resume its earliest incomplete gate (final-head
   CI, merge, main verification). Refresh evidence after any code change; an
   interrupted run is not a pass.
2. After Wave 3 main verification, create `feat/ux-wave-4-planning` from verified main
   and use [Wave 4 contracts](UX-WAVE4-CONTRACTS.md). Reconcile its proposed APIs with
   the actual Wave 3 code; it is the fourth and final wave.

The 55-ID product backlog is `docs/UX-ROADMAP-2026-09-21.md`; completion states are
in `docs/UX-EXECUTION.md`. The two files with ` (1)` in their names are early
planning copies, not authoritative current status.

## Reproduce validation

Install/configure JDK 17, set `JAVA_HOME` to it, and use the checked-in Gradle 7.6.4
wrapper. Main production sources retain Java 8 API/bytecode targeting. Use a native
Windows desktop for UI/focus checks; serialize those checks and packaging.

```powershell
.\gradlew.bat --no-daemon --continue --console=plain --project-cache-dir build/ux-handoff-cache -PrealmSharkBuildDir=build/ux-handoff -I scripts/typography-validation.gradle test shadowJar testUi150 testUi200
.\scripts\Test-BuildMaintenance.ps1 -JavaHome $env:JAVA_HOME
```

Inspect every test task's XML and the exit code. Run the JAR's `--help` from an
isolated working directory. Wave 3 visual evidence (`*.WaveThreeEvidenceTest`)
writes populated/empty/unavailable captures under
`<build>/ui-{test,Ui150,Ui200}/screenshots/wave3/`; review them independently.
Never run `Set-CiDisplay.ps1` on a workstation; it is CI-only. Do not start live
capture or send Bridge deliveries. Tests use synthetic history and isolated
preferences; do not substitute personal saved data.

## Resume prompt

> Resume the four-wave RealmShark UX implementation. Read AGENTS.md,
> docs/UX-HANDOFF.md, docs/UX-CHECKPOINT.json and docs/UX-EXECUTION.md; reconcile
> actual Git/GitHub state; continue the earliest incomplete gate using independent
> subagents where appropriate. Do not start Wave 4 before Wave 3's final-head review,
> CI, merge and main verification are complete.
