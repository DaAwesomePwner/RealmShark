# Wave 4 evidence and discovery lane

Implemented INS-4, LOOT-4 and the UX-08 registry/search surface. Coordinator owns
producer wiring and actual menu/editor registrations. This lane does not merge PRs.

## Interfaces

- `AbilityObservation` is detached heuristic evidence with actual old/incoming MP,
  captured names/IDs, session and optional exact VisitRef. Producers publish to
  `AbilityObservationStore.application().add(...)` before changing entity stats.
- The session-only store retains 5,000 rows, separately counts eviction and omitted
  unstructured legacy calls, and supports immutable filtered snapshots. Inspect
  searches/sorts all retained matches before rendering 100 rows. Reset starts a new
  window. Empty results never claim that no abilities were used.
- `DropContext.capture(map, player, observedAt, exactVisit)` freezes numeric mapped
  modifiers and unresolved count, allowlisted grade, finite difficulty, field-present
  loot timer seconds/receipt, seasonal value/receipt and raw crucible value/receipt.
  Derived timer remaining is unknown when receipt is unknown. No raw modifier or
  unique-data strings persist. Definition generation is frozen under asset publication
  lock; legacy assets are explicitly labelled. `LootGUI.update(..., time, context)`
  forwards the detached context to normal loot history.
- `ParseEnchants.evidence` provides ordered exact IDs and explicit recorded/empty,
  missing/invalid/legacy states. Empty, locked and terminator IDs remain inspectable.
  Summary and ID extraction share the bounded decoder. Aggregate grouping remains
  item ID/slots/applied; aggregate rows do not inherit a single occurrence's context.
  Saved occurrence details and CSV expose enrichment; old records are not backfilled.
- `ActionRegistry.application().register(ActionDescriptor)` accepts audited
  navigation/focus callbacks. `clear()` releases callbacks on workspace rebuild.
  `ActionSearchPanel.show(owner)` is modeless. Selecting/searching never invokes a
  callback; Enter/Open rechecks enabled state. Actual registered controls and shortcut
  integration belong to the coordinator.

## Validation

JDK 17.0.20.1 / Gradle 7.6.4; main sources compile with existing Java 8 targeting.
Focused model/query suites passed: ability store, enchant summary, loot archive query,
enrichment, registry and offscreen panel behaviors. Synthetic fixtures test absent
versus captured-zero boost, detached context, unknown numeric IDs, legacy records,
exact variants sharing aggregate keys and non-executing search selection.

`tomato.gui.stats.WaveFourEvidenceTest` passed 3 native tests at each 100/150/200%
scale (zero failures/errors/skips), with 1240x800, 680x520 and compact 18-point text.
Screenshots: `build/w4-native/ui-{test,Ui150,Ui200}/screenshots/wave4/` in the lane
checkout. XML: `build/w4-native/test-results/{test,testUi150,testUi200}/`.
The fixture uses the production archive scroll fallback; an earlier naked-render
geometry failure is superseded, not a pass. Actual inspected compact screenshots
prompted minimum detail-height fixes. Ability details and long loot values scroll;
search disabled explanations remain visible. No live capture or delivery occurred.

Coordinator must include the new native suite in scaling validation and independently
review fresh final-head evidence. These bounded checks do not replace full-wave gates.
