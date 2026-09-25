# RealmShark development and resumption

## Active UX implementation

Before editing, read `docs/UX-EXECUTION.md` and, if present, `.omc/ux/checkpoint.json`.
For cross-machine recovery also read `docs/UX-HANDOFF.md` and
`docs/UX-CHECKPOINT.json`; these tracked files preserve the last published handoff
when the machine-local checkpoint and worktrees are absent.
Reconcile their recorded branch, worktrees, commits, PRs and checks with actual Git/GitHub
state. Never reset or overwrite unfamiliar work. An interrupted check is not a pass.
`docs/UX-ROADMAP-2026-09-21.md` is the product backlog and evidence source.

The user approved autonomous implementation in four sequential wave branches. Each wave
must receive independent review of its final PR head and pass required validation before
merging to main. Verify main after merge before creating the next wave branch. No force
pushes, hook bypasses, or direct-to-main implementation commits. Use at most five active
subagents, isolated worktrees, and one writer per shared file. The coordinator owns GitHub
merges and `.omc/ux/checkpoint.json`; workers must not alter either.

## Build and validation

Use JDK 17, Gradle 7.6.4, and existing Java 8 API/bytecode targeting for main sources.
Main checks: `gradlew.bat test shadowJar`; scaled checks use
`-I scripts/typography-validation.gradle test testUi150 testUi200`.
Use unique `realmSharkBuildDir` and project-cache directories per worker. Serialize
desktop/focus-sensitive validation and packaging. Never run `Set-CiDisplay.ps1` on this
workstation; it is CI-only. Use synthetic fixtures, isolated working directories/history,
and explicit preference isolation. Do not start live capture or send bridge deliveries.

Use `apply_patch` for source edits. Keep changes compatible with existing data, unknown
states, asynchronous persistence and EDT rules. Reviewers inspect actual code and fresh
evidence; tests and screenshots are not successful merely because they were generated.

## Checkpoints

Update the local checkpoint after each bounded package, test/review, commit/push, PR and
merge transition and before long-running operations. Publish sanitized milestone state
in the tracking issue/PRs and commit the coverage ledger with each wave. Never publish
personal game history, settings, secrets or absolute user-data paths.
