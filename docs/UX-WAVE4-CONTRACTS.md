# Wave 4 planning and enrichment preparation

Status: PROVISIONAL DESIGN ONLY, prepared 2026-09-24 from the resumed Wave 2 checkout. Wave 4 must not branch or implement until Wave 3 has merged and main is verified. Wave 3 contracts in `UX-WAVE3-CONTRACTS.md` are also provisional; reconcile them with the actual merged APIs first. This portable preparation note changes no source, checkpoint, capture state or delivery configuration.

Scope: all remaining CHAR-3/4, QUEST-2/3, INS-4, LOOT-4, UX-08 and UX-07 final accessibility/integration adoption. This is a single fourth wave with bounded internal packages, not additional waves.

## Verified current structures and pitfalls

### Characters and progression

`src/main/java/tomato/backend/data/CharacterJournal.java`:
- Document version 2; accepts versions 1/2 and preserves unreadable/unsupported files by disabling saves.
- Character identity is `accountKey(rawAccountId) + ':' + characterId`; accountKey uses SHA-256. CharacterRecord holds nullable stats[8], equipment[28], field-level FieldCapture provenance, account/class identity, firstSeen/lastSeen, dead, diedAt, notes, lastObservedAlive/rosterReceivedAt/observedAgainAt.
- `diedAt` is currently the time the user MARKED the character dead, not the actual death occurrence. Never reinterpret old values as confirmed occurrence times.
- Live observation protects a dead record's frozen snapshot and holds subsequent observations in pendingAlive; `markDead(key,false)` can restore that data. Copy/save paths explicitly copy every field. New annotations must survive both copy and pendingAlive restoration.
- AccountRecord stores class-specific exalts and exaltSeen. `EXALT_ORDER` maps displayed stats to stored completion array indices. Existing thresholds are 5/15/30/50/75.
- `potions(value, cap, stat)` uses +5 Life/Mana and +1 others with ceil; a Life deficit of 16 already calculates four standard equivalents. Unknown stats/caps must not become zero.
- `save()` snapshots under saveLock after acquiring it, writes off normal view updates, and only clears dirty if its saved revision remains current. `startSaving` schedules every two seconds. Storage failure keeps dirty state/status. Preserve this ordering and do not block EDT.
- `TomatoData.characterJournal()` lazily uses app-folder `Characters/journal.json`.

`backend/data/RosterDefinitions.java` already publishes an immutable generation-derived cap/item snapshot: `cap(classId,stat)` returns nullable Integer. Caps derive from players.xml max fields. Reuse its missing-field behavior; add explicit version/generation metadata for planning calculations rather than loading an unrelated live global on every paint.

`gui/character/CharacterJournalGUI.java` already shows stat/potion tables, class exalts, notes, manual dead/restore and 28 equipment rows. Slot sections are 0-3 Equipped, 4-11 Inventory, 12-27 Backpack. Source values and per-slot provenance already distinguish null from captured values; retain that distinction in new grouping/filter controls.

### Quests

`backend/data/ProgressionData.java` is a detached mailbox: Scope has generation, account, accepting and reason; Quests has scope/capturedAt and defensive rows(); `Snapshot.currentQuests()` requires the current scope object, verified account and accepting state. `quests(origin,rows,at)` rejects obsolete generations. Reuse this boundary for selecting an account plan.

`packets/data/QuestData.java` contains stable id, name, description, raw expiration STRING, category, unknownInt, repeated-item-ID requirement/reward arrays, completed, repeatable, itemOfChoice. There are no decoded requirement quantities other than multiplicity in these arrays, nor a documented expiration unit/format in this class.

`gui/quest/QuestGUI.java` currently:
- converts null requirement/reward arrays into empty arrays in its Quest wrapper, which LOSES unknown-vs-empty information;
- aggregates duplicate IDs in `quantities`, and keys pins by nameUUID(stable id or name:category fallback);
- searches labels/item names but needs explicit stable-ID search/facets;
- labels categories through local custom names and retains raw IDs in details;
- treats repeatable/completed choices separately but lacks dedicated comparison facets.

`gui/quest/QuestPins.java` has Java Preferences global `pin.<key>` and separate `accounts/<hash>/pin.<key>`. Legacy global interests are not account plans. Preserve them; never silently migrate them to whichever account appears next. Account pins can seed proposed plans only through an explicit user action in that account.

### Ability inference

`backend/SecurityAbilityUseCheck.java` currently sends formatted strings to `SecurityGUI.updateAbilityUsage` from stasis/mana and decoy/mana heuristics. Stasis associates orb-duration candidates; decoy uses a short global counter and known decoy types. Both mana checks currently fire on `previousMana <= nextMana`, not a demonstrated mana cost. Preserve the actual evidence description; do not label it a successful cast or silently invert/change the heuristic while replacing presentation.

`backend/data/Entity.java` invokes these checks BEFORE `stat.setStats(status.stats)`, so previous stat and incoming stat are available together. This is the correct producer boundary for detached old/new MP, ability item and object identity. `TomatoData.clear()` resets the decoy heuristic.

`gui/security/SecurityGUI.java` currently batches a static string callback onto an Ability Use text area on EDT. It has no structured retained event model. New rows must not be reconstructed by parsing display text or current namesake entities.

### Loot context

`TomatoData.lootTick()` resolves attribution, then calls BridgeService.receive and LootGUI.update with map, bag, dropper/player and time before finishing the tick. Coordinator owns this shared producer.

`gui/stats/LootDashboard.receive(map,bag,dropper,time)` reads eight bag slots and per-slot UNIQUE_DATA_STRING. It writes a detached Drop to `AppHistory.append('loot',drop)` with visitId. Item retains ID/name/tier/classification plus ParseEnchants.Summary (slots/applied). Aggregate key is ID + slots + applied. No exact effects are currently persisted in this model.

`realmshark/ParseEnchants.summarize` is a bounded URL-Base64 decoder that distinguishes missing/malformed from empty. Its legacy `extractEnchantIds` returns an empty list for both none and invalid and uses a different decoder path; it is not a sufficient provenance API by itself. Add an explicit parse result rather than inferring valid-empty from that list.

`gui/stats/LootGUI.LootEntry` already detaches display-time enchant stats and map modifier fields; it also computes player.lootDropTime(time). This is not the persisted occurrence schema. A derived remaining time of zero is not by itself proof that the underlying boost stat was captured.

`packets/incoming/MapInfoPacket` provides four modifier strings, dungeonGrade and difficulty. `ParseDungeon.getModifiersString/getModIds` maps via the local DungeonCatalog. `DungeonCatalog` knows portal/modifier metadata; the inspected code does not expose a verified dungeon-to-exalt-stat mapping. Do not invent one from portal names or recollection.

### Settings

`gui/maingui/TomatoMenuBar.java` has existing Appearance/Font, Capture, Chat, Sound & Notifications, DPS Options, Filter Loot and original sharing actions. `TomatoGUI` exposes notification editor entry points; Wave 3 routing should provide contextual destinations.

Persistence differs by feature: `util/PropertiesManager` uses app-folder realmShark.properties with asynchronous PreferencesStore; character journal uses app-folder Characters/journal.json; QuestPins uses platform Java Preferences; `AppHistory.directory()` uses configured/user-level history directory; Bridge settings/journal have their own configured paths. Search results must explain the actual destination's location and preview behavior rather than promising a universal setting scope.

## Proposed shared model/persistence APIs

Coordinator owns shared backend lifecycles, producers and existing globals. A new planning store is separate from observations and history retention:

```java
interface PlanningStore {
    PlanSnapshot snapshot(String accountKey);
    CompletionStage<PlanSaveResult> update(String accountKey,
        long expectedRevision, PlanMutation mutation);
}
```

Proposed local path: sibling `plans.json` beside the character journal, with explicit injection/override for tests/preview. This is a proposed location, not an existing file. Give it its own versioned document, serialized async writer, atomic publish, detached immutable snapshots, per-account revisions and SaveResult. Failed saves retain draft/dirty state and expose retry. Unsupported versions/malformed existing data remain preserved and read-only. Use the existing account hash once, never raw account identifiers and never rehash an existing hash. A null/unverified account cannot create an actionable plan.

The coordinator defines/owns shared `planning` interfaces/models/store; workers may own distinct pure reducers/UI files that consume them. Suggested records:
- `CharacterGoal(accountKey, characterKey, statIndex, targetBaseValue, createdAt, updatedAt, metadataVersion)`; pinning Max records explicit intended target and its cap source. On metadata change show current cap versus saved target without silently rewriting the goal.
- `ExaltGoal(accountKey, classId, statIndex, targetTier, metadataVersion)`; account/class-scoped, not character-death-scoped. Resolve thresholds from a versioned local metadata object and expose missing mappings.
- `QuestPlanEntry(accountKey, entryId, stableQuestId, legacyPinKey, capturedAt, sourceGeneration, requirementsKnown, requirementCounts, rewardMode, rewards, repeatable, desiredRepeats, rawExpiration, metadataVersion)`; copied requirement snapshot remains usable after source account changes, labelled stale/unconfirmed until explicitly updated. Missing stable IDs remain provisional interests rather than auto-merged plans based on names.
- `ManualHeld(accountKey, itemId, quantity, confirmedAt, note)`; absence means not manually confirmed, not zero. Explicit zero is valid.
- `Reservation(accountKey, planEntryId, itemId, quantity)`; manual allocation only, validated against the same account's held quantity and known requirement demand.

Use checked long math with documented UI limits for repeats/quantities, no silent integer overflow. Required total for item i is sum(count(entry,i) * desiredRepeats(entry)) over entries with known requirements. A missing requirement snapshot makes overall readiness unknown even if known subtotals are shown. One-time quest repeats are at most one; completed one-time quests cannot become ready actionable turn-ins. Repeatable completion history does not imply another future completion is already done. Require explicit adjustment/reconfirmation when refreshed requirements change. Choose-one rewards remain alternatives, never summed as all guaranteed rewards or available inventory.

Reservation math:
- `reservedAll(i) = sum(reservation(entry,i))`; reject a new mutation exceeding confirmed held or known demand. Do not auto-rebalance another entry's reservations.
- For selected group P: `reservedOutside(P,i)`, `availableForGroup = max(0, held - reservedOutside)`; `remainingForGroup = max(0, requiredGroup - availableForGroup)` when held/requirements are known. Internal reservations partition that group's stock; they are not subtracted twice.
- For one entry: show its own reserved amount separately and remaining against its reservation; unreserved stock is available to allocate, not simultaneously counted as allocated for every quest.
- Group readiness using manual held is explicitly Manually confirmed, as of timestamp; observation never modifies held, reservations, repeats or readiness. No auto-consumption on a packet/loot event. A user can explicitly decrement held and release reservations together in one validated mutation.

Death API (coordinator-owned CharacterJournal changes):

```java
void annotateDeath(String characterKey, DeathAnnotation value);
// DeathAnnotation: nullable occurredAt, editedAt, notes,
// optional userSelectedVisitRef; provenance = MANUAL
```

Keep existing diedAt as markedAt semantics for compatibility, or explicitly migrate with markedAt=diedAt and occurredAt=null. Never populate occurrence time from lastSeen. The Wave 3 VisitRef is reused only for an explicit user-selected historical run; store it as a manual association, not verified causal death evidence. Broken/deleted links remain visible and removable. Preserve annotation across dead-snapshot observations and restore-alive; do not mutate frozen equipment. Coordinator bumps/validates journal schema and updates every copy/save path.

Ability producer API (coordinator-owned existing producer files):

```java
void publishAbilityObservation(AbilityObservation detached);
// id, appSession, optional exact VisitRef, observedAt, playerObjectId,
// captured display name/class, optional abilityItemId, heuristic kind,
// nullable previousMp/observedMp, evidence version, inference explanation
```

A worker-owned bounded AbilityObservationStore supplies immutable snapshots, filters and omitted/retention counts. Coordinator wires it before entity stat mutation; no arbitrary packet/string-stat payloads, credentials or chat. Retained player display identity is ordinary Inspect evidence, separate from the privacy-constrained diagnostic logger. If saved history is added, append only this allowlisted detached model through AppHistory; expose collection/retention limits. Do not promise exhaustive successful-cast coverage.

Loot proposed enrichment API (worker C owns new schema/decoder; coordinator wires producer):
- `EnchantmentEvidence(version, state, orderedSlotIds, slots, applied, observedAt, definitionVersion)`, where state distinguishes RECORDED_EMPTY, RECORDED, MISSING, INVALID and LEGACY_NOT_RECORDED. Preserve locked/empty/terminator semantics; unknown nonnegative IDs remain numeric effects, not a decoding failure.
- Optional `DropContext(version, capturedAt, VisitRef, modifierIds, modifierCoverage, dungeonGrade, difficulty, boostRemainingMillis, boostFieldCapturedAt, seasonal, crucible, definitionVersion)`. Include only fields actually read at observation with known source/presence. Do not dump a generic Stat map. Avoid arbitrary map strings by using existing locally mapped numeric modifiers plus explicit unresolved-count/coverage if necessary.
- Construct context synchronously once at bag observation, deep-copy it, then pass it to dashboard persistence. No later lookup from current player/map to enrich old events. Keep Wave 3 shared drop/occurrence identity if implemented; otherwise use exact existing archive ref + item child locator. Never invent a Bridge join from service-local review ID.
- Preserve aggregate ID/slots/applied grouping and show exact IDs/context in contributing occurrence details. Schema-absent old rows explicitly lack enrichment; no backfill from current definitions/build.

## Max three writer lanes and coordinator ownership

| Owner | Exclusive files / responsibilities | IDs |
| --- | --- | --- |
| Coordinator | PlanningStore/shared DTOs; CharacterJournal schema/lifecycle; TomatoData, TomatoPacketCapture, Entity, ProgressionData, SecurityAbilityUseCheck, AppHistory/SessionStore; shared route/shell/TomatoGUI/TomatoMenuBar integration, style helpers and validation allowlist | Shared contracts, producer wiring, final UX-07 audit/integration |
| A: character planning | gui/character/**; new pure CharacterGoal/ExaltGoal calculation models and versioned local goal metadata reader; character equipment/death editors | CHAR-3/4 |
| B: quest planning | gui/quest/** including QuestPins; new pure requirement/repeat/reservation reducer and quest facets; plan UI and pin migration action | QUEST-2/3 |
| C: enrichment and discovery | New structured-ability store/UI and gui/security/SecurityGUI ability surface; gui/stats loot occurrence schema/detail files; ParseEnchants decoder extension; new settings/action search package | INS-4, LOOT-4, UX-08 |

Explicitly reserve the ability surface in SecurityGUI to C; A must not edit Inspect files when reusing equipment details. Reuse a component via its API or ask coordinator for a hook. C owns all loot schema/UI edits; shared backend snapshots are requests to coordinator. The settings catalog/search UI is C-owned, while existing menu/shell actions are registered by coordinator; C does not edit TomatoMenuBar concurrently. Reconcile actual Wave 3 modifications before assigning filenames.

Avoid one overloaded C commit: ability, loot and search are three sequential bounded packages in the same lane. A/B continue independently. No additional worker is necessary. Reviewer/verifier can run read-only/headless work alongside three writers; native/focus checks serialize.

## Staged packages inside Wave 4

1. **4A.0 coordinator contracts:** reconcile Wave 3 APIs; new account-scoped store and DTOs with migration/failure/race tests; journal manual annotation schema; versioned metadata interfaces. No next-wave branch before main gate.
2. **4A.1 concurrent domain models:** A goal/potion/exalt reducer; B requirements/facets/repeat/reservation reducer retaining null presence; C structured ability model/store. Coordinator adds detached producer publication and tests the before-stat-update ordering.
3. **4A.2 planning surfaces:** A persistent goals and explicit death/equipment editors; B quest comparisons, selected plans, manual-held/reservation editor. C implements searchable retained ability table and honest inference/coverage labels.
4. **4C enrichment:** C extends bounded enchant decoder, optional context and occurrence detail; coordinator adds capture-boundary context hook. A/B finish restart/save-failure/account-switch scenarios and request any shared integration changes through coordinator.
5. **4D action search:** C creates typed ActionDescriptor registry/search with stable ID, label, keywords, location/persistence explanation, enabled predicate, focus/navigation action and preview semantics. Coordinator registers existing menu/editor actions and shell shortcut. Search locates controls; merely selecting a result must not toggle capture/sharing or send anything. Existing editor confirmation remains intact for actual actions. Keep direct navigation/shortcuts; optional navigation grouping is not required absent findability evidence.
6. **4D cross-module audit:** coordinator applies shared fixes; workers each audit only their owned surfaces for keyboard, full-value access, compact/enlarged/scaled layouts, state restoration, background reads and actionable unknown/error/paused states. Update committed coverage ledger only when all slices close.
7. **Final gates:** full test/shadowJar, applicable UI150/UI200 with independent fresh synthetic screenshot review, build-contract/help/runtime/package checks required for final delivery, independent final-head review, CI, normal merge and main verification. No live capture, real sharing or Bridge deliveries.

## Acceptance fixtures

- A known Life deficit 16 yields four standard potion equivalents; partial stat/cap data gives unknown, no invented zero. Pin/restart/reload preserves targets and metadata provenance; changed cap metadata flags a mismatch rather than rewriting user intent.
- Exalt goals belong to account/class and survive character death; missing local dungeon-to-stat metadata is explicitly unavailable. A verified versioned mapping can be added later without changing saved user targets. No external factual mapping was invented during this preparation.
- Duplicate character IDs on two accounts cannot share plans/notes/held quantities; stale async save/load results cannot overwrite the newly selected account. Null account cannot create actionable goals.
- Corrupt/future-version plans remain untouched/read-only. Failed save retains editable draft and prior durable state; concurrent updates reject stale revisions; close/restart preserves successful writes. Preview uses isolated non-writing/in-memory store.
- Quest arrays [i,i] and [i,i,i] total five; four manually confirmed items leave one remaining. Duplicate IDs count by multiplicity. Missing requirements remain unknown; empty observed arrays are separately labelled observed empty, without assuming a guaranteed free turn-in.
- Repeat two multiplies every requirement by two; one-time repeat greater than one is rejected. Overflow/negative quantities are rejected atomically. Choice rewards remain separate alternatives.
- Held four reserved two for plan A and two for B cannot be promised again to C. Group totals do not subtract their own reservations twice. Releasing A restores only its manual allocation. Unknown held remains unconfirmed. Observed drop/redemption/character inventory does not update manual held or readiness.
- Refresh changes an item's requirement or removes a quest: saved plan stays visible with stale/reconfirmation evidence, does not silently reroute by same-name quest. Legacy global pin remains global; migration is explicit and account-bound.
- Search stable quest ID, repeatability, reward mode, requirement item and quantity; unrecognized expiration remains raw/unsorted as an invented deadline; custom category labels retain numeric category IDs.
- Captured empty equipment sentinel differs from null Not captured; Equipped/Inventory/Backpack groups and keyboard details preserve 28-slot values. A manually dead historical loadout is unchanged by subsequent observations until explicit restore.
- Marked-at and optional occurred-at differ; old diedAt migrates only to marked-at. Ambiguous last runs require manual selection; deleted linked visit remains a broken manual link. Restore-alive preserves annotation history without claiming server-confirmed death.
- Ability inference fixture records actual previous/incoming MP and heuristic version before mutation; stasis/decoy rows remain inferred even with ambiguous candidates. Search one player across retained rows; omitted/retention counters visible; no fabricated successful-cast total.
- Equal item/count variants with different exact enchant IDs share aggregate count grouping but each occurrence shows its own effects. Unknown IDs remain inspectable numeric IDs. Missing, malformed, empty, locked, terminator, padded/unpadded encodings remain distinguishable.
- Drop context freezes on observation, survives map/player changes, and old rows show fields Not recorded. Missing boost source is unknown even when the derived timer is zero. No raw unique-data string, arbitrary packet dump or secret enters enrichment persistence.
- Settings search for font, history location and item alert reaches the real existing control by keyboard; disabled/preview states stay enforced. Result location/persistence copy matches the actual storage backend. Searching/opening settings never starts capture, toggles sharing, or makes a network delivery.
- Full-value copy/inspection, filter reset, Back/selection/scroll, compact 680x520 and enlarged/scaled text remain usable across all final surfaces. Generated screenshots are evidence only after independent inspection.
