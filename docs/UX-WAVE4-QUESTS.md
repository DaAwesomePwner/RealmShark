# Wave 4 quest planning

Implemented QUEST-2/3 in the quest package. Captured quest comparison now searches
stable IDs and filters repeatability, reward mode/presence, raw expiration availability,
and required item name/ID with a minimum per-item quantity. Null packet arrays retain
their unknown presence, separately from an observed empty array. Raw categories and
expiration values are displayed without inferring dates or category meanings.

The Saved plans tab uses the shared PlanningStore and an explicitly selected account.
Saved accounts and the verified captured account are offered. The shell can supply
additional journal account keys with `QuestGUI.knownPlanningAccounts(Collection)` on
the EDT. No account is implicitly chosen. Offline edits remain possible after selecting
a known account; imports and reconfirmation require the current verified capture scope,
checked at action time as well as mailbox delivery. Missing/duplicate stable IDs cannot
be imported. Legacy pins remain independent; copying an interest is explicit.

Select multiple plan rows for combined demand and stock availability. Reservations
inside that selection are not subtracted twice; allocations outside it are excluded.
Manual held values carry a timestamp and note, explicit zero is distinct from unknown,
and no packet changes them. Stock and demand reductions can release affected allocations
atomically through the explicit checkbox. Zero reservation releases that item. Completed
one-time quests are unavailable; covered requirements never assert server eligibility.

Changed or absent quests retain saved snapshots and show reconfirmation status. Current
and saved requirements are shown before the explicit Refresh / reconfirm action. Missing
quests remain removable, not silently deleted. Stale reservations require release or
reconfirmation before publishing edits. Each account keeps its own draft, revision and
pending-save state. Save means durable publication; failure preserves the draft for retry.
Reload/discard requires confirmation when dirty. Switching accounts during save cannot
publish another account's draft or change that account's displayed selection.

Validation: JDK 17 / Gradle 7.6.4 compilation passed. Focused non-native test selectors:
`tomato.gui.quest.QuestPlanningTest`, `QuestPlanPanelTest`, `QuestGuiTest`, `QuestPinsTest`.
Coverage includes quantity multiplicity and repeats, internal versus external reservations,
unknown/empty/zero, stale snapshots, checked overflow, release validation, completed one-time
quests, explicit account selection, scope changes before mailbox delivery, failed save/retry,
and delayed save completion during account switching. Native geometry/focus, scaled new
surface evidence and independent screenshot review remain integration gates.

`QuestPlanEvidenceTest` adds exact offscreen 680/1100x520 pages at fonts 13/18 with
populated, unknown, stale, validation error, empty and manual-control screenshots under
`screenshots/wave4/quests`. Its native method exercises focused keyboard actions for
manual stock validation, explicit atomic release and durable Save at compact size; run
only in the serialized desktop lane. Planning controls reveal on focus and long detail
text reveals the keyboard caret. The offscreen method passed with the 16 behavioral
checks; native/scaled execution is still pending at this package handoff.

## Native and scaled follow-up

Standard, 150% and 200% runs each passed 9 checks: seven existing
`QuestConsistencyTest` cases and both `QuestPlanEvidenceTest` cases. All XML reports
show zero failures, errors and skips; both standard and scaled Gradle runs exited 0.
The first native run found and fixed a partial button-height focus reveal and updated
the older missing-array assertion to the new unknown-state contract. Final fresh
native stock/error and compact populated/stale screenshots were inspected at all
three scales. Controls and text are reachable by scrolling and keyboard; table status
uses horizontal scrolling, with full selected values in the wrapping details.
Unfocused row selection remains subtle in the shared theme and is recorded for the
coordinator's final cross-module audit, rather than claimed as a contrast pass.

Reports: `build/w4-quests-native/test-results/test` and
`build/w4-quests-scaled/test-results/testUi150`, `testUi200`. Screenshots live below
the corresponding `ui-test`, `ui-Ui150`, `ui-Ui200` directories in
`screenshots/wave4/quests`. The native runs were serialized with the other lanes.

No shared store, checkpoint, shell routing or producer files were modified in this lane.
