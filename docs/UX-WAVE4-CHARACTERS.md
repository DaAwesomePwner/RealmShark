# Wave 4 characters and manual goals

CHAR-3/4 lane implements embedded Goals and Death annotation tabs plus detached
equipment review. Shared persistence and CharacterJournal schema remain coordinator-owned.

## Behavior

- Goals require explicit selection of a known account. Offline editing works for
  recorded accounts, including accounts retained only in the planning document.
  Stat targets bind to account-qualified character keys; exalt targets bind to
  account/class and survive death. Max and next-tier actions save a fixed target.
- Custom stat targets require a known cap; missing base/cap stays unknown. Life/Mana
  use the existing +5 standard-potion conversion; a deficit of 16 takes four.
  Saved cap fingerprints and exalt mapping/threshold versions are compared without
  rewriting user intent. Reapply a target to reconfirm changed metadata.
- Search covers saved goals, statuses and unknowns. Sort Remaining for near-complete
  work; stat rows count potions and exalt rows count completions. Complete targets
  remain visible. Full details expose saved version and copyable values.
- Account drafts stay in memory across selection changes. Save uses expected
  account revision and captures the selected account before async dispatch. Failures
  retain drafts; stale revisions require explicit reload/reapplication. Reload warns
  before discarding edits. Closing the application without Save loses local drafts.
- Optional dungeon mapping reads only the selected AssetCache generation, off EDT,
  from xml/exaltationConfig.xml. Missing/unsupported mapping stays unavailable;
  fingerprint is SHA-256. Thresholds explicitly version existing local 5/15/30/50/75
  values independently. Mapping names are exact; alias/current-game coverage is not
  claimed. No game facts were inferred from dungeon names.
- All 28 equipment slots retain Occupied / Empty / Not captured and group by
  Equipped, Inventory and Backpack. Enter opens full copyable detached details.
  Item definitions are labelled current local metadata; historical enchant effects
  are explicitly not recorded by CharacterJournal. No live entity is consulted.
- Death annotation keeps optional UTC occurrence time, notes and explicitly selected
  VisitRef. Marked time and edited time remain separate. Per-character drafts survive
  selection changes while open. The picker searches all saved visits before keeping
  its newest 500 matches, exposes the count and requires a selected row. Same-name
  visits remain distinct. Open uses the exact Runs route; unavailable links remain
  visible/removable. No nearest-timestamp or causal association is inferred.

## Integration

`CharacterPanelGUI.bindNavigator(Navigator)` delegates exact run routing. The fallback
uses `Navigator.current()`. `CharacterJournalGUI` has a package-private injectable
PlanningStore constructor for synthetic fixtures. No native capture is started.

## Validation

JDK 17 / Gradle 7.6.4 `compileJava` passed with Java 8 production targeting.
Focused final run: 16 tests, zero failures/errors/skips (goal models, account/save
failure UI semantics, slot states, time parsing, existing query/view-state, journal
annotation, exact same-map picker references and search-before-retention). XML totals
and exit status verified under the isolated worker build. Integrated final checks are
recorded by the coordinator; this document does not claim unrun checks pass.

`CharacterWaveFourEvidenceTest` provides native synthetic populated/empty/compact/
enlarged-text captures in `screenshots/wave4/characters`. Add this suite to the scaled
allowlist. Native checks/screenshots require serialized execution and independent
visual inspection; no screen-reader pass is claimed by accessible labels alone.

Targeted native character and affected My Info fixtures passed at 100/150/200%
(two tests per task; zero failures/errors/skips). Visual inspection identified an
unsettled screenshot resize artifact; the character fixture now uses the shared
recursive layout settler and asserts wrapping-control bounds. A targeted 100%
rerun passed and the compact Pin max control is confirmed visible after wrapping.
Updated scaled assertions are included in the coordinator's integrated final gate.

## P7 My Info follow-up

Recorded DPS library projection now runs in a SwingWorker rather than on EDT.
Refresh keeps selection by exact recording ID, disables opening while pending or
failed, explains loading/error state, retains prior choices on failure, and rejects
superseded/disposed completions. The synchronized catalog supplies detached archived
entries; recorded graphs remain frozen. Four focused async race/disposal/selection/
handoff tests passed. Existing native My Info evidence now awaits async loading.
