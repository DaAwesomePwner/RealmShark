# Wave 4 implementation plan

Prepared 2026-09-26 against main `dfceefb9ab87dd4d37a2429167d740b7ab0a33ee`.
Status: planning only; no production changes or new validation runs. Wave 3 PR #14
is merged; main CI `36245882529` passed. Its tracked checkpoint is a pre-merge
snapshot; the live milestone in issue #9 and local checkpoint supersede that status.

This plan reconciles `UX-WAVE4-CONTRACTS.md` with merged code. The roadmap remains
the acceptance source. Recommendations below are proposed defaults, not new user
decisions. Wave 4 remains one branch/PR, `feat/ux-wave-4-planning`, with bounded
internal packages. Do not begin feature implementation as part of this planning task.

## Outcome and coverage

Users can retain account-specific goals, compare quests and plan requirements,
review historical equipment and manual death notes, and find controls by search.
Captured observations, inferred evidence and manual quantities remain distinguishable.

| Backlog | Deliverable | Completion evidence |
| --- | --- | --- |
| CHAR-3 | Persistent maxing and account/class exalt goals | Restart, missing caps, metadata changes, account isolation |
| CHAR-4 | Grouped equipment and manual death annotations | Frozen snapshots, restore-alive, explicit exact run selection |
| QUEST-2 | Stable-ID search and comparison facets | Repeat/reward modes, item/quantity filters, unknown expiration |
| QUEST-3 | Saved requirements and repeats; optional held/reservations | Multiplicity, readiness, stale plans, no automatic stock updates |
| INS-4 | Searchable structured inferred ability observations | Detached producer evidence, bounded retention, no cast-total claim |
| LOOT-4 | Exact enchant effects and optional captured drop context | New occurrence round-trip, legacy unknowns, capture-time freezing |
| UX-08 | Search settings and existing actions | Keyboard navigation to real controls; accurate persistence labels |
| UX-07 | Final cross-module usability audit | Full values, focus, Back, compact/scaled and empty/error states |

## Decisions to settle

| Decision | Recommended default | Alternative and consequence |
| --- | --- | --- |
| Include manual stock and reservations now? | Include as optional controls in Wave 4; requirements totals work without them. Quantities are account-wide, timestamped manual entries. | Requirements-only is smaller, but explicitly defers that optional part of QUEST-3. Separate seasonal/mode stock pools would need an additional user-selected inventory scope. |
| Retain ability observations after restart? | Bounded current-session list first, with visible eviction/omission counts. Keep a persistence-ready detached model. | Saved history needs collection controls, archive scope, deletion/export and coverage contracts; it materially enlarges INS-4. |
| How much drop context? | Exact enchant IDs plus an allowlisted set of fields whose presence can be established at capture time; unavailable fields stay unknown. | Exact enchant IDs only is smaller but defers the context portion of LOOT-4. No unproven field should delay exact effects. |

Default presentation: put goals beside Characters and plans beside Daily Quests;
retain direct workspace access. Add global settings/action search without a new
top-level planning dashboard, navigation regrouping, or an optional exalt matrix in
the first pass. These are routine scope choices, adjustable without changing the
data contracts. Keep the existing dark/violet design and shared components.

Allow offline editing of an explicitly selected previously known account; a missing
active account must never select one implicitly. Importing/refreshing observed quests
still requires the verified current scope. Fixed goals become complete when reached;
they do not automatically advance. Keep prior manual death annotations after restore.
Record the allowlisted loot enrichment with ordinary local loot history rather than
introducing a separate collection toggle by default.

Already approved constraints do not need another decision: local-only persistence;
account-scoped actionable plans; legacy global pins preserved; manual stock separate
from observed loot; versioned metadata; no guessed historical joins.

## Reconciliation with current main

- `CharacterJournal` is version 2 and already has account-qualified keys, nullable
  stats/equipment, detached copies, asynchronous saving and a read-only failure mode.
  It has no persistent goals or structured death annotation. `markDead(false)` may
  replace a frozen record from `pendingAlive`, preserving only existing notes there;
  new annotation fields must be preserved explicitly in every replacement/copy path.
- `QuestGUI` still replaces missing requirement/reward arrays with empty arrays.
  Correct that loss of presence before building readiness or facets. Use
  `ProgressionData` scope/generation rejection and stable quest IDs; do not join by name.
- `VisitRef(sessionId, visitId)`, `Route`, `ShellNavigator` and activity adapters now
  exist. Reuse `Route.to(RUNS).withVisit(ref)` and unavailable-link behavior. Back is
  bounded and EDT-only. Never invent a second router or match a nearest visit.
- `Destination` has no Characters/Quests destinations. Add explicit targets only
  where navigation requires them, owned by the coordinator; include restore state.
- Historical equipment needs a detached detail model. Inspect's player-oriented
  details cannot safely be passed a current live entity for an old character record.
- The repository DOES contain dungeon-to-stat entries under `Dungeons/Dungeon` in
  `assets/xml/exaltationConfig.xml` (`Name` and `PowerUp`). No current Java consumer
  was found. The provisional document's absence assumption is too broad: implement
  a versioned loader and verify packaging/alias coverage. These entries establish
  local metadata availability, not that every current game variant is covered.
  Load the optional file from the selected `AssetCache.root()` generation and expose
  a fingerprint through the metadata contract. Extraction does not require this file;
  missing is valid. It supplies no threshold schedule: version the existing threshold
  source separately. Never fall back to unrelated checked-in data or guess aliases.
- Ability inference still originates in `SecurityAbilityUseCheck` before entity
  stat mutation. Preserve its actual heuristic and observed old/new MP; replacing
  the UI is not authorization to reinterpret every inference as a successful cast.
- Loot occurrence enrichment belongs in the persisted `LootDashboard` item/drop
  model and archive details, not only the transient `LootGUI.LootEntry` view.
  Preserve Wave 3 exact visit and occurrence references and aggregate grouping.
  Reuse `LootArchiveAdapter`'s `source.child("item-"+ordinal, row)`; update its
  projection/dependency version, `LootQuery.Row.item` and existing detail view.
  There is no common Bridge/drop identity to join.

## Sequence, ownership and exit gates

Dependencies: P0 -> P1 -> P2/P3/P4; P4 -> P5; P2/P3/P5 -> P6 -> P7.
P2, P3 and P4 may progress independently after shared interfaces are frozen.

### P0 — Freeze contracts and record choices

Coordinator reconciles this decision table, confirms Wave 3 main evidence, inventories
existing worktrees and creates/reuses isolated implementation worktrees. Define
interfaces and file ownership before writers start. Preserve old worktrees and
unfamiliar changes. Update the local checkpoint and sanitized coverage ledger.

Exit: chosen optional scope recorded; proposed schemas, save semantics and metadata
sources agreed. No unverifiable game mapping or assumed historical linkage.

### P1 — Durable planning foundation and metadata

Coordinator owns a new `tomato.planning` package, shared DTOs, store lifecycle and
backend integration. Proposed file: `Characters/plans.json`, independent of history
retention, with an injected path/in-memory implementation for preview and tests.

- Immutable snapshots, per-account revisions, serialized background writes and
  atomic replacement. Define draft/pending/durable state explicitly: success means
  published to disk; failure keeps the draft and prior durable revision, with retry.
  Reject stale revisions and stale account/generation completions.
- Use the existing account key once. Unknown account cannot create an actionable
  plan. Corrupt/future-version files are preserved read-only with recovery guidance.
  Define shutdown flushing and surface unsaved changes; never block normal EDT reads.
- DTOs: CharacterGoal, ExaltGoal, QuestPlanEntry, ManualHeld and Reservation.
  Pure reducer validation uses checked long arithmetic and explicit UI bounds.
  Mutations affecting held quantities and reservations are atomic within this store.
- Version local cap/exalt metadata, expose source/version/completeness. Saved target
  values retain user intent when definitions change; show mismatch/reconfirmation.
  Exalt goals belong to account/class and survive a character's death.
- Keep death annotations in CharacterJournal with a compatible schema migration;
  avoid cross-file transactional promises between the journal and planning store.

Exit: failure/retry/restart, stale revision, account race, malformed/future file and
preview isolation tests pass before UI uses durable plans.

### P2 — Characters and historical review (writer A)

Own `gui/character/**` and new pure goal reducers; coordinator owns journal changes.
Show pinned targets, deficits in standard potion equivalents, next/custom attainable
exalt tier, near-complete sorting and source age. Keep unknowns searchable.

Group all 28 slots into Equipped/Inventory/Backpack with Occupied/Empty/Not captured
filters and keyboard full-value details. Extract a shared detached detail component
through its designated owner rather than changing Inspect concurrently.

Add optional manual occurrence time, notes and an explicitly selected saved visit.
Store marked-at separately; old diedAt means mark time, not actual death time.
Preserve previous manual mark times when current alive/dead status is restored;
the existing reset-to-zero behavior must not erase the annotation history.
Broken links remain visible/removable. Restore-alive preserves annotation history
and must not silently rewrite the frozen loadout while still marked dead.

Exit: deficit 16 Life -> four standard potions; null cap stays unknown; identical
character IDs on different accounts remain isolated; restart and death/restore
preserve goals/notes; ambiguous same-name runs require explicit selection.

### P3 — Quest comparison and requirements planning (writer B)

Own `gui/quest/**`, quest facets and pure requirement/reservation reducers.
Search stable IDs; filter repeatability, reward mode, requirement items/quantities
and expiration availability. Retain raw expiration/category values without guessed
dates. Missing requirements/rewards remain distinct from observed-empty arrays.

Persist selected quest snapshots and intended repeats. Changed/removed quests stay
visible as stale and require explicit refresh/reconfirmation. Preserve global pins;
copy into an account plan only through an explicit action. No stable ID means a
provisional interest, not an automatically merged actionable quest.

If manual quantities are included, absent held means unconfirmed; explicit zero is
valid. Validate reservations against held stock and known demand. For selected group
P: available = held - reservations outside P; internal reservations are not subtracted
again. Individual rows distinguish reserved from unallocated stock. Never allocate
the same stock automatically to multiple quests. Lowering held below reservations
requires a single explicit adjustment/release operation. Do not auto-consume on loot
or redemption packets, and do not treat choose-one rewards as all guaranteed.
Revalidate reservations on every repeat/requirement change too: reduced demand below
allocated stock requires explicit atomic release/reconfirmation. Completed one-time
quests cannot become actionable ready turn-ins; repeatable completion history alone
does not confirm a subsequent turn-in.

Exit: requirements [i,i] and [i,i,i] total five; held four leaves one; two repeats
double demand; one-time repeats >1 rejected; unknown requirement makes readiness
unknown; overflow rejects the whole mutation; observed drops change no manual values.

### P4 — Structured inferred ability evidence (writer C)

Own new ability observation model/store/UI and Inspect ability surface. Coordinator
wires `SecurityAbilityUseCheck`/`Entity`/`TomatoData` producers before stat mutation.
Capture detached timestamp, session, optional exact visit, object identity, captured
display/class/ability fields, old/new MP, heuristic version and explanation.

Provide searchable/filterable time/player/ability/evidence rows, full details,
retained/omitted counts and reset semantics. Bound storage and rendering; show the
retention limit. Object IDs require session/visit context and are not permanent player
identity. Do not collect arbitrary packet/string-stat payloads. Persistence, if chosen,
must use explicit archive and collection contracts rather than parsing UI strings.

Exit: player filtering works beyond the visible page; later entity changes cannot
alter past rows; ambiguous stasis/decoy evidence stays inferred; zero rows never
claims no abilities were used; eviction and collection gaps are distinguishable.

### P5 — Drop-time enrichment (writer C, after P4)

Own bounded enchant decoder/result, loot schema and occurrence details; coordinator
owns capture hooks in `TomatoData`. Unify exact-ID decoding with explicit missing,
invalid, recorded-empty and legacy-not-recorded states. Preserve ordered slot IDs,
locked/empty/terminator semantics and unknown numeric effects.

Freeze optional allowlisted context synchronously at bag observation, before deferred
publication: exact visit, mapped modifiers/coverage, grade/difficulty, boost fields,
seasonal/crucible only when presence and source are verified. Derived zero boost time
does not prove the boost stat was captured. No retroactive enrichment from current
player/map or new definitions. Preserve ID/slots/applied aggregate grouping while
occurrence details expose exact effects. Inventory candidate fields before promising
which ones ship; each needs a presence and capture-time fixture.

Exit: equal-count variants retain different exact effects; malformed/legacy data
still loads; map/player changes cannot alter saved context; bounded decoder cases
include padding, terminators, invalid data and unknown IDs.

### P6 — Settings/action search and cross-module integration

Writer C owns an ActionDescriptor registry/search UI; coordinator owns registrations
in existing menu/shell/router files. Descriptors carry stable ID, label, keywords,
location, persistence/preview explanation, enabled predicate and navigation/focus action.
Reuse the actual editor callbacks so search cannot drift from menu behavior.
Audit each callback first: do not invoke generic menu clicks that toggle capture or
sharing. History location currently comes from configuration/defaults, not a path
picker; search should show/open that location and explain its configuration.

Search/opening a result navigates or focuses; it never toggles capture/sharing, saves
an alert or sends a delivery. State-changing actions retain their existing explicit
controls. Preserve shortcuts; select a global search shortcut only after collision
inspection. Modeless search returns focus correctly and respects preview restrictions.

Exit: keyboard searches for font, history location and item alert reach real controls;
disabled actions explain why; Back restores scope, selection and scroll; path text
matches each real storage backend instead of claiming one universal settings folder.

### P7 — Final audit, review and delivery

Coordinator owns shared fixes/coverage ledger; each writer audits their own surfaces.
Use synthetic populated/empty/partial/error cases at compact 680x520, enlarged text,
100/150/200% scales, keyboard focus, full-value copy and accessible names/descriptions.
Screen-reader behavior requires actual evidence; labels alone are not a verified pass.

Reproduce Wave 3's accepted UI notes before deciding fixes: clipped/scrolled footers,
initial scroll position, faint row selection, duplicate/misleading status wording and
possibly sticky audio error state. Check My Info library work on the EDT and stale
cohort export clarity. Track persistent comparison pins, cross-machine unresolvable
visits and crash-loss windows as separate accepted limitations unless this work
changes their contract; do not silently expand Wave 4 into an unrelated rewrite.

Run JDK 17 / Gradle 7.6.4 with Java 8 main targeting:

1. Focused semantic tests per package, then integrated `test shadowJar`.
2. `-I scripts/typography-validation.gradle test testUi150 testUi200` with new suites
   added to the allowlist and actual XML/exit verification.
3. Build-maintenance, isolated runnable-JAR help, final runtime/package checks and
   independent inspection of fresh synthetic screenshots.
4. Independent review of the final PR head; resolve findings and refresh affected
   evidence. Green required Windows CI, normal merge, verify exact main merge and CI.

Use unique build/project-cache directories per worker; serialize desktop tests and
packaging. No workstation Set-CiDisplay, live capture or real Bridge deliveries.
The earlier no-tests request was honored for synchronization/planning; future
implementation still needs the repository's required gates.

## Coordination and resumption

At most three writers plus independent reviewer and verifier. Writers use isolated
worktrees, never edit the coordinator checkpoint or merge PRs, and must preserve
others' work. One owner per shared file; integration hooks are requests to that owner.
Coordinator exclusively owns TomatoData, Entity, CharacterJournal schema/lifecycle,
AppHistory/SessionStore shared changes, menu/shell/router registrations and validation
allowlist. Shared planning interfaces are frozen before workers add owned reducers.

Update `.omc/ux/checkpoint.json` after each package/check/review/commit/PR/merge and
before long operations. Publish sanitized milestones through the approved execution
workflow, commit the coverage ledger with the wave, and retain exact evidence SHAs.
Planning deliverable is this file; feature implementation and external publication
have not occurred in this task.
