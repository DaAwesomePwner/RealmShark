# RealmShark development and resumption

## Current validation policy

User update (2026-09-26): CI is manual-only and is not required for PR merges.
Prefer focused local checks and relevant build/launch smoke checks on the user's
computer. Do not routinely repeat full suites, scaled UI matrices or packaging
cycles. Docs/workflow-only changes need diff/config inspection, not application
tests. This supersedes mandatory CI and broad validation requirements below and
in historical wave documents. Keep normal PR merges and other branch protections.

## Active UX implementation

The UI redesign P0–P6 is merged. Continue from `docs/superpowers/plans/2026-09-26-redesign-roadmap.md`
and `docs/superpowers/plans/2026-09-27-redesign-handoff.md`.
The rest of this section describes the completed earlier UX waves and is history.

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

Use JDK 17 and Gradle 7.6.4; main sources target Java 17 (`--release 17`).
Main checks: `gradlew.bat test shadowJar`; scaled checks use
`-I scripts/typography-validation.gradle test testUi150 testUi200`.
Use unique `realmSharkBuildDir` and project-cache directories per worker. Serialize
desktop/focus-sensitive validation and packaging. Never run `Set-CiDisplay.ps1` on this
workstation; it is CI-only. Automated tests use synthetic fixtures, isolated working
directories/history, and explicit preference isolation. Launching the app (including from
the user's usual folder with their real history) and starting live capture are allowed
when no other RealmShark instance is running; check the running processes first. Do not
send bridge deliveries.

On macOS, use `./gradlew` with JDK 17 in `JAVA_HOME`. Sessions that reach the Mac over SSH
are headless, so run UI tests and launches through `scripts/mac/gui-gradle.sh <gradle args>`
(outside the command sandbox). It runs the build in the desktop session, keeps those Gradle
daemons apart from headless ones, and serializes desktop runs across worktrees. Builds without
UI work over plain SSH.

Use `apply_patch` for source edits. Keep changes compatible with existing data, unknown
states, asynchronous persistence and EDT rules. Reviewers inspect actual code and fresh
evidence; tests and screenshots are not successful merely because they were generated.

## Checkpoints

Update the local checkpoint after each bounded package, test/review, commit/push, PR and
merge transition and before long-running operations. Publish sanitized milestone state
in the tracking issue/PRs and commit the coverage ledger with each wave. Never publish
personal game history, settings, secrets or absolute user-data paths.
