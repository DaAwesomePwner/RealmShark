# UX implementation execution and recovery

## Authority and decisions

User approved autonomous execution on 2026-09-21 for **DaAwesomePwner/RealmShark**.
Source backlog: [UX roadmap](UX-ROADMAP-2026-09-21.md), 55 recommendation IDs.

Approved decisions:
- Publish existing Chat changes/tests and planning through a baseline PR before Wave 1.
- Delete the untracked, unused `tomato/gui/warnings/MissingNpcapGUI.java` duplicate;
  production uses `packets/packetcapture/sniff/gui/MissingNpcapGUI.java`.
- Independent AI review plus passing CI authorizes autonomous PR merges. This is not
  represented as a separate human GitHub approval.
- Protect main: PRs, required Windows tests and JAR check, administrator enforcement
  where supported, blocked force-push and deletion. Never bypass these gates.
- One major wave branch/PR at a time; merge commits retain reviewed milestone history.
- At most five active subagents: up to three writers, independent reviewer, verifier.

Product defaults: whole-scope queries; explicit half-open date bounds; run entry-time
inclusion by default with explicit overlap alternative; current manual `.dps` retention;
account-scoped actionable plans with legacy global pins preserved; optional manual
quantities separate from observations; versioned metadata; no guessed historical joins.

## Current milestone

Wave 1 is merged. Wave 2 implementation is integrated and explicitly resumed on
2026-09-24 from the clean, published `6f7d007` handoff. Live GitHub reconciliation
confirmed the same Wave 1 main/CI and no Wave 2 PR. The new workstation's full
800-test and 174-test 150% runs passed; its 200% run failed a native window-width
assertion. That failure and the earlier focus timeout are under investigation in
an isolated validation worktree, alongside independent source review and user-guide
updates. Waves 3 and 4 implementation has not started; their tracked
contract notes are provisional preparation only. Read [the handoff](UX-HANDOFF.md),
[portable checkpoint](UX-CHECKPOINT.json) and [validation record](UX-WAVE2-VALIDATION.md)
for the tested code, completed checks, remaining gates and exact next action.
Live GitHub reconciliation on 2026-09-24 confirmed Wave 1 PR #11 merged as
`64d58d0606a82a956e082fa0dcc07aa18d8fbbfa`, with successful main CI `35696825491`.
Wave 2 resumed from local `c1ccedb` and remote `18e3c4f`. All recovered production,
native test and fixture changes are now integrated through `ad21a1f` on
`feat/ux-wave-2-evidence`; no Wave 2 PR exists at handoff. Source review through the
final test delta found no blockers. Earlier failed/interrupted checks remain
diagnostic history and are not validation passes.
Baseline PR #10 merged as `835e178c56381a16a5414f3ca9791a9d1ea7f174`; final-head AI
review and PR CI passed, followed by successful main CI run `35681691854`.
Initial main/live remote SHA:
`d57456d955f5c942ab0a2eb5d5a586752d2cff9b`.
Pre-existing work: Chat ignored-player visibility feature, associated tests and docs,
untracked UX roadmap, and unused Npcap duplicate. No other pre-existing source changes.
See the tracked checkpoint and tracking issue for published status, and the local
checkpoint when available. These are milestone records; reconcile subsequent
checks and merges with actual Git/GitHub state before resuming.
Tracking issue: https://github.com/DaAwesomePwner/RealmShark/issues/9.

Baseline local evidence: 519 tests, 76 UI checks at 150%, and 76 at 200%, all with
zero failures/errors/skips; shadow JAR, isolated `--help`, and build-maintenance checks
passed. Reports and reviewed synthetic Chat screenshots: `build/ux-baseline/`.
Build-contract evidence: `build/build-maintenance-c9d30bbc49d848a696c410b384fb4279/`.
Test JVMs now use an in-memory PreferencesFactory to isolate Windows user preferences.
Native Character/Quest windows were host-clamped; passing exact offscreen geometry
checks are distinct from native exact-size coverage. Main protection is configured.

Wave 1 package handoffs: [shared](UX-WAVE1-SHARED.md),
[progression](UX-WAVE1-PROGRESSION.md), [social](UX-WAVE1-SOCIAL.md),
[reporting](UX-WAVE1-REPORTING.md), [producer integration](UX-WAVE1-INTEGRATION.md).
Their focused checks passed. Integrated validation at `ec39ac6` passed 624 tests,
122 UI checks at 150%, and 122 at 200%, with zero failures/errors/skips; shadow JAR,
isolated `--help`, and build-maintenance passed. Fresh synthetic views were independently
reviewed, including typed-rule save failures, known/unknown pets and recovery states.
Reports: `build/ux-wave1-final-pass/`; build-contract evidence:
`build/build-maintenance-2c9de56d51c643acaa4d780d3c03e121/`.
Review findings on pet identity/freshness, mixed-session coverage, capture preferences,
asset generation replacement/publication, and compact layouts were fixed and rechecked.
Final Windows package and bundled-Java help checks passed at source `ec39ac6` with
guides `7305510`. Package evidence:
`build/share/20260922-013854-a8a5295ea685419e9a9b1cf827291f1a/`.
ZIP SHA-256: `AF4F8FD3D02690DB4585F8FA01AC55F5C1777EADDF43961036D2377CA42489B3`.
The package builder's fresh 624 tests passed; packaged guides match their sources.
Immutable-runtime staging checks also passed in
`build/runtime-test-6c6f3b26a6bc471e83af6ac2de43c7a1/`.

Wave 2 package handoffs: [foundation](UX-WAVE2-FOUNDATION.md),
[social](UX-WAVE2-SOCIAL.md), [rosters](UX-WAVE2-ROSTERS.md),
[activity](UX-WAVE2-ACTIVITY.md), [loot/statistics](UX-WAVE2-LOOT.md),
[Logging](UX-WAVE2-LOGGING.md), and [shell integration](UX-WAVE2-INTEGRATION.md).
Their bounded checks do not replace the integrated native/scaled/final-head gates.
Recording-interval availability remains unknown until actual producer transitions
are instrumented in Wave 3; an empty archive is not proof of collection coverage.

## Sequence and work packages

### Baseline — `chore/ux-baseline`
Persist plan/state; inspect and preserve approved work; remove authorized duplicate;
focused Chat validation and full required checks; independent review; publish PR;
merge; verify main CI and synchronize local main before branching Wave 1.

### Wave 1 — `feat/ux-wave-1-trust`
1A: shared evidence vocabulary, recoverable setup, actionable estimates, early
Inspect/Logging unknown-state and collector clarity.
1B: account/generation-safe quests, character presence/freshness, pet estimates.
1C: shared Chat policy, exact contributor filtering, silent/reliable typed alert editors.
1D: global loot recency, statistical/combat denominators, Bridge outcomes/reasons.
Gate: zero/unknown/partial/stale differ; missing setup recovers; failed saves retain
drafts; account-switch updates rejected; archive recency and response buckets correct.

### Wave 2 — `feat/ux-wave-2-evidence`
2A: typed queries, origin envelopes, matching counts/global ordering, pinned read
revisions, saved view state, history manager, export contracts and shared table behavior.
2B: Chat/Key-pop reference query clients, unseen arrivals, saved views, diagnostics.
2C: task-oriented character/Inspect filters and searchable encounter library.
2D: deep loot occurrences/facets, run filters and readable Timeline.
Gate: predicates/sort before paging, rows/counts/exports agree, malformed metadata
isolated, independent view state retained, compact and keyboard actions accessible.

### Wave 3 — `feat/ux-wave-3-connected-analysis`
3A: typed routes and Back; exact-visit run workbench; diagnostic mapping and coverage.
3B: encounter/visit/local-row identity captured before reset, event explorer,
selected-window resource analysis, build comparison and My Info navigation.
3C: loot occurrence/rate drill-down, saved fame parity, A/B cohort comparisons.
3D: contextual alert drafts/decisions, Bridge active-draft feedback and saved review.
Gate: consecutive same-name visits never cross-link; old/unlinked records are honest;
Back restores state; unknown victim equipment is not inferred; comparison denominators
remain explicit; saved review browsing never sends network deliveries.

### Wave 4 — `feat/ux-wave-4-planning`
4A: persistent maxing/exalt goals and quest requirement/repeat/optional quantity plans.
4B: character equipment/death annotations and structured inferred ability evidence.
4C: optional future drop-time enchant/context enrichment, compatible with old records.
4D: settings/action search and final accessibility/integration audit.
Gate: plans survive restart; quantity/reservation math correct; observation/manual
provenance stays separate; old records remain usable; ambiguous run links are explicit.

## Shared contracts and ownership

- Queries carry scope, resolved bounds, typed predicates and deterministic ordering.
  Results carry origin session, stable record reference, matching unit/count, revision
  and coverage. Legacy record locators are not invented cross-module identity.
- Exports use the promised revision/query. Stream archives rather than materializing
  all records into Swing tables. Preserve existing `.dps` collision-safe publishing.
- Routes carry destination, scope, query, exact optional references, time bounds and
  return state. Early local details do not depend on the Wave 3 router.
- Reuse detached snapshots, SnapshotRefresh, ContentStyle and generation checks.
- Shared files have one writer. Assign `TomatoData`, `SessionPanel`, `ActivityPanel`,
  recording models and style helpers explicitly; owners integrate requested hooks.
- Workers use isolated worktrees/branches and bounded commits. Coordinator integrates,
  records progress and owns remote protection/PR/merge operations. No later-wave branch
  is created from an unmerged predecessor.

## Coverage ledger

States: pending, implementing, implemented, verified, reviewed, merged, blocked.
At the 2026-09-24 handoff, Wave 2 full tests (800), JAR and 200% checks (174)
passed; 150% checks had one keyboard-focus failure out of 174. Its rows remain
implemented with gates pending. No Wave 2 merge or Wave 3/4 implementation is claimed.
An ID with multiple slices closes only when all slices meet their acceptance criteria.

| ID | Package(s) | State |
| --- | --- | --- |
| UX-01 | 2A | implemented; Wave 2 gates pending |
| UX-02 | 2A state; 3A routing | state implemented; routing pending |
| UX-03 | 1A; adopted throughout | 1A merged; later adoption tracked |
| UX-04 | 2A and module adapters; 3 linked extensions | Wave 2 implemented; linked extensions pending |
| UX-05 | 2A library; 3A recording intervals | library implemented; recording intervals pending |
| UX-06 | 1A | merged |
| UX-07 | 2A onward; 4D audit | Wave 2 implemented; final audit pending |
| UX-08 | 4D | pending |
| CHAT-1 | 1C | merged |
| CHAT-2 | 2B | implemented; Wave 2 gates pending |
| CHAT-3 | 2B saved views; 3D drafts | saved views implemented; drafts pending |
| KEY-1 | 1C | merged |
| KEY-2 | 2B | implemented; Wave 2 gates pending |
| KEY-3 | 3D | pending |
| INS-1 | 2C | implemented; Wave 2 gates pending |
| INS-2 | 1A honesty; 3B metadata | 1A merged; 3B pending |
| INS-3 | 3B | pending |
| INS-4 | 4B | pending |
| CHAR-1 | 1B | merged |
| CHAR-2 | 2C | implemented; Wave 2 gates pending |
| CHAR-3 | 4A | pending |
| CHAR-4 | 4B | pending |
| CHAR-5 | 1B | merged |
| STAT-1 | 1D | merged |
| STAT-2 | 3C | pending |
| STAT-3 | 3C | pending |
| QUEST-1 | 1B | merged |
| QUEST-2 | 4A | pending |
| QUEST-3 | 4A | pending |
| INFO-1 | 1A | merged |
| INFO-2 | 3B | pending |
| COMBAT-1 | 1D | merged |
| COMBAT-2 | 2C | implemented; Wave 2 gates pending |
| COMBAT-3 | 3B | pending |
| COMBAT-4 | 3B | pending |
| COMBAT-5 | 3B | pending |
| LOOT-1 | 1D recency; 2D deep search | recency merged; deep search implemented; Wave 2 gates pending |
| LOOT-2 | 2D | implemented; Wave 2 gates pending |
| LOOT-3 | 3C | pending |
| LOOT-4 | 4C | pending |
| LOG-1 | 2B | implemented; Wave 2 gates pending |
| LOG-2 | 1A honesty; 3A metadata | 1A merged; 3A pending |
| LOG-3 | 2B | implemented; Wave 2 gates pending |
| RUN-1 | 2D | implemented; Wave 2 gates pending |
| RUN-2 | 3A | pending |
| TIME-1 | 2D | implemented; Wave 2 gates pending |
| TIME-2 | 3A | pending |
| BRIDGE-1 | 1D | merged |
| BRIDGE-2 | 1D | merged |
| BRIDGE-3 | 3D | pending |
| BRIDGE-4 | 3D | pending |
| ALERT-1 | 1C | merged |
| ALERT-2 | 1C | merged |
| ALERT-3 | 1C | merged |
| ALERT-4 | 3D | pending |

## Validation and merge gates

For bounded packages: meaningful behavioral tests for changed semantics; preserve
compatibility and EDT/background boundaries. Record selectors and results. GUI changes
need fresh populated/empty/error evidence and keyboard/scaling checks as applicable.
Add new applicable UI suites to the scaling allowlist; generated screenshots alone do
not constitute a visual pass.

For each baseline/wave final head:
1. Test and build with JDK 17/Gradle 7.6.4; retain main Java 8 compatibility.
2. Run applicable 150%/200% checks and independently review affected synthetic screens.
3. Pass CI build-contract checks and runnable-JAR `--help` smoke test.
4. Review the final head independently; resolve material findings and refresh evidence
   after relevant changes. Do not use superseded checks/reviews as approval.
5. Create/publish PR evidence; require green Windows tests and JAR; merge normally.
6. Verify actual merge SHA and successful main CI; synchronize local main; checkpoint.
   Repeat local suites only when integration differs or unresolved concerns justify it.
7. Additional package/runtime checks for startup changes and final delivery.

Use unique build/project-cache directories and isolated synthetic history/preferences.
Serialize GUI tests and packaging. CI's Set-CiDisplay is forbidden on a workstation.
Do not perform live capture or real bridge deliveries. No secrets/personal history in
commits, public issues, screenshots or reports.

## Durable recovery protocol

Coordinator writes `.omc/ux/checkpoint.json` after every package, review/check, commit,
push, PR and merge transition and before long-running operations. It records wave,
base/head, branch/worktrees/owners, PR/CI URLs and SHAs, evidence, blockers and next task.
Publish sanitized milestone state in a GitHub tracking issue and wave PRs; local-only
state is not sufficient for recovery on another machine. Commit ledger milestones.

On resume:
1. Read AGENTS, this plan, roadmap, UX-HANDOFF.md, UX-CHECKPOINT.json and any local checkpoint.
2. Verify cwd, status/branch, worktrees, local commits and live remote main.
3. Reconcile PR/head/review/CI/merge state with GitHub; actual state wins.
4. Inspect unfamiliar/uncommitted work; never reset it automatically.
5. Invalidate checks for changed source. Incomplete/interrupted checks are not passes.
6. Resume the earliest incomplete gate; do not start the next wave early.

Resume prompt: "Resume RealmShark UX execution; read AGENTS.md, docs/UX-HANDOFF.md,
docs/UX-CHECKPOINT.json, docs/UX-EXECUTION.md and any .omc/ux/checkpoint.json;
reconcile GitHub, then continue the earliest incomplete gate."
