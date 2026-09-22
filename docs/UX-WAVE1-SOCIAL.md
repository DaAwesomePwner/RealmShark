# Wave 1C — social policy and alert editing

Worker branch: `work/ux-w1-social`, based on `835e178` (baseline PR #10 merge).
Baseline merge and successful main CI were checked before implementation.

## Implemented worker scope

| ID | Implementation | Integration / validation boundary |
| --- | --- | --- |
| CHAT-1 | One observable immutable Chat policy, pinned classification per refresh, coalesced EDT refreshes, lifecycle subscriptions, current local rules distinguished from game-ignore receipt evidence. Historical edits suppress future live alerts through the shared loader. | `TomatoGUI` must select the new instance loader below. The legacy static loader remains available for existing callers. |
| KEY-1 | Independent removable `Player equals …` predicate, case-insensitive exact contributor matching, keyboard/mouse drill-down, unchanged independent general search. Events, summaries, metrics and CSV use the same filtered list. | Implemented in the owned producer/view. Native geometry and focus checks remain for coordinated validation. |
| ALERT-1 | Chat/item/entity/enchant editor opening paths are silent; playback is explicit Test sound. Save retains the editor, editable drafts, visible failures and Retry. Shared save feedback distinguishes memory application from disk durability and rejects superseded acknowledgements. Chat filters and the retained Key-pop dialog use the same feedback. Realm phrase/create forms keep failure feedback and retry without duplicating created events. | Headless behavioral evidence only; actual dialog opening/closing and scaled layouts await serialized UI validation. |
| ALERT-2 | Shared pure typed matcher, versioned per-category persistence, legacy migration, silent draft sample checker, stale-editor protection, unsupported-data preservation. Chat keyword production uses it with existing suppression/precedence and one keyword submission per message. | Entity and item producer hooks below are required before claiming complete production wiring. |
| ALERT-3 | Enchant Select shown / Clear shown, selected-only view, selected-shown/outside-filter counts, explicit all-catalog operations, filtered group operations. Unknown catalog IDs and unrecognized saved entries survive save and catalog rebuild. Notifications dungeon choices show the same selected-only/count concepts. | Headless selection behavior verified. Compact/scaled visual verification pending. |

`TomatoData`, `LootGUI`, `TomatoGUI`, the shared execution ledger, build configuration,
scaling allowlist and coordinator checkpoint were not edited by this worker.

## Integration contracts — required owner changes

The service is self-contained and already compiles against existing APIs. It is
`tomato.realmshark.AlertRules.application()`, backed by the existing preloaded
`PropertiesManager` memory and asynchronous writer. **Do not introduce a second
rule store or duplicate matching logic.** No new `TomatoData` field is necessary.

### TomatoData owner: new-entity producer

Replace the legacy loop inside `customSoundAlert(int idType)` with:

```java
if (AlertRules.application().matchEntityType(getEntityIdPings(), idType).matched) {
    Sound.custom.play();
}
```

Keep the existing call under `if (newObject)` in `addEntity`. `idType` is the
unsigned entity **type**, not the instance/object ID. This evaluates cached
immutable rules; it does no disk I/O, Swing work or networking.

### TomatoData owner: typed item overload

Add this overload while retaining the existing string method and legacy list APIs:

```java
public boolean isItemPing(int itemType, String resolvedName) {
    return AlertRules.application()
        .matchItem(getItemPings(), itemType, resolvedName).matched;
}
```

No change to `loadPropList`, `savePropList`, or legacy setters is needed for these
hooks. Until a category has typed settings, each evaluation consumes its supplied
legacy list. After an explicit typed save, that category's typed envelope takes
precedence. Legacy keys are preserved, not dual-written or automatically reimported.
An old string-only caller therefore remains a legacy caller; all new production
item matching must use the typed overload. Other legacy list consumers/tests retain
their existing APIs and behavior.

### LootGUI owner: item observation

Inside `notifyItems`, after the existing positive inventory-ID guard and name
resolution, replace the two string-based probes with:

```java
boolean itemMatch = data.isItemPing(item.statValue, name);
```

Keep `if (itemMatch || enchantMatch) alert.run()` exactly once per occupied item
slot, the malformed-enchant isolation, and local alerts before optional sharing.
Exact ID matching works without a resolved asset name. Add integration regressions
for 42 versus 142 alongside existing `LootNotificationTest` cases. Use fake alert
and share callbacks; no deliveries are needed.

### TomatoGUI owner: shared history policy

Change the Chat wrapper's loader from `ChatGUI::history` to:

```java
SessionPanel.wrap("chat", chatPanel, chatPanel::historyWithPolicy)
```

Implemented public signature:

```java
public SessionPanel.Loaded historyWithPolicy(
    SessionStore store, String scope, int page, String query) throws IOException
```

Archive reading remains on the history worker. The loaded view is constructed on
the EDT with the live panel's policy, including inherited spam rules. History
loading/reclassification never goes through live delivery. The old static loader
is compatibility-only and still loads a separate policy, so the shell change is
necessary to close CHAT-1 production integration.

## Rule schema and compatibility

Canonical keys: `alerts.rules.item`, `alerts.rules.entity`, `alerts.rules.chat`.
Each stores `{"version":1,"rules":[{"mode":"ITEM_ID","value":"42"}]}` with modes
restricted to their category. IDs are decimal integers. Item IDs must be positive;
entity types support 0–65535 without signed-short narrowing.

- New modes: `NAME_CONTAINS`, `ITEM_ID`, `ENTITY_TYPE`, `TEXT_CONTAINS`, `SPACE_TOKEN`.
- `LEGACY_ITEM` retains substring matching against both decimal ID and resolved name.
  A legacy `42` can still match 142; conversion to exact ID is an explicit edit.
- `LEGACY_CHAT` preserves quoted token rules using literal-space splitting, including
  punctuation, tabs/newlines, multiword quoted strings and empty quoted tokens.
  Legacy case folding retains its old default-locale behavior; new text modes use
  `Locale.ROOT`.
- `LEGACY_ENTITY` preserves decimal-string equality. Leading zero/plus spellings
  are not silently activated by migration.
- Missing canonical settings use the caller's legacy list. Present empty typed
  rules intentionally match nothing. Malformed/future envelopes are preserved and
  reported as unavailable for editing/matching; legacy rules are not resurrected.
- Unknown/invalid rows in version 1 remain inactive, visible and losslessly
  round-trippable alongside known rows. Unknown envelope/row fields survive edits.
  Explicit Remove can delete an unsupported row.
- Canonical JSON escapes non-ASCII code units, preserving rule values and unknown
  fields through the existing platform-charset preferences file.
- Migration does not write on opening. Save compares the category's base settings
  with current memory before writing; another category cannot create a conflict.
  Sample checking evaluates a detached draft through the same pure matcher.

`AlertRules.Submission` returns the new active `Snapshot` and a
`CompletionStage<PreferencesStore.SaveResult>`. Preference writes are memory-immediate:
a failed disk write leaves submitted rules active. Retrying writes again; Cancel
discards only unsubmitted edits. A later unrelated successful preference write can
also persist currently active values. Feedback never promises rollback or durability
before completion. Edits made during saving remain visibly unsaved; overwritten or
coalesced submissions cannot falsely acknowledge the current draft.

Realm event creation fixes its name on first application and creates a disabled
profile. The phrase remains editable; retry updates that event instead of adding a
duplicate. Existing realm announcement source filters and cooldown matching remain
in `RealmEventAlerts`.

## Validation evidence

Final focused run: **36 tests, zero failures/errors/skips** across 12 suites.
`compileJava` and `compileTestJava` passed; main compilation retains `--release 8`.
JDK 17 and Gradle 7.6.4 were used. Every test JVM ran with
`-Djava.awt.headless=true`; no native windows, desktop/focus validation, capture,
real sound playback or network deliveries were used.

| Suite / selected methods | Count | Meaningful assertions |
| --- | ---: | --- |
| `AlertRulesTest` | 7 | Legacy/exact boundaries; token edge cases; malformed/future/unknown preservation; stale drafts; active-memory failure; Unicode/locale compatibility |
| `SharedChatPolicyTest` | 4 | Saved-view ignore changes live suppression and other pages; receipt evidence; subscriptions; pinned classification; failed-save draft retention |
| `TypedChatAlertTest` | 1 | Fake audio submissions verify keyword coalescing, channel precedence, ignores, own messages and unknown PM direction |
| `ExactContributorTest` | 1 | Sorted mouse/Enter drill-down excludes Anna from Ann; independent search, filters, metrics, CSV rows and reset |
| `SocialRuleEditorTest` | 5 | Silent sample/Test separation; slow/failed/exceptional/retried saves; in-flight edits; coalesced completion; unknown enchant selection; future schema UI |
| `RuleEditorActionsTest` | 3 | Existing keyboard row actions, expansion preservation, superseded save completion |
| `NotificationDraftTest` | 2 | Failed realm creation/retry retains one disabled event; selected-only dungeon clear preserves hidden choices |
| `SilentRuleEditorTest` | 1 | Construction/selection produce zero audio submissions; explicit tests use a recording channel |
| Selected `ChatFiltersTest` methods | 3 | Advertisement rules, ignore/allow/inherited precedence, persisted local rules |
| Selected `KeyPopTest` method | 1 | Existing live metrics, summaries, sorting and combined filters |
| Selected `NotificationsGuiTest` method | 1 | Existing hidden dungeon choices and preference behavior |
| `NotificationSoundTest` | 7 | Decode without hardware, scaling/mute, saved profiles, invalid WAV rejection and realm match/cooldown behavior |

Reports: `build/social-behavior/reports/tests/test/index.html` and
`build/social-behavior/test-results/test/`. These contain synthetic test evidence.
An initial compile found a wrong `ContentStyle.page` arity and an initial test run
found a headless fixture trying to emulate a native host; both were corrected before
the final passing run. No interrupted/failed run is counted as a pass.

Reproduction from this worker worktree (set tool paths to the coordinator repository's
absolute `.tools` directory; the worker itself has no copied toolchain):

```powershell
$tools = (Resolve-Path '..\..\..\.tools').Path
$env:JAVA_HOME = "$tools\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "$tools\gradle-home"
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
$selectors = @(
  'tomato.realmshark.AlertRulesTest',
  'tomato.realmshark.SilentRuleEditorTest',
  'tomato.realmshark.NotificationSoundTest',
  'tomato.gui.chat.SharedChatPolicyTest',
  'tomato.gui.chat.TypedChatAlertTest',
  'tomato.gui.keypop.ExactContributorTest',
  'tomato.gui.maingui.SocialRuleEditorTest',
  'tomato.gui.maingui.RuleEditorActionsTest',
  'tomato.gui.notifications.NotificationDraftTest',
  'tomato.gui.chat.ChatFiltersTest.advertisementsNeedBothSalesWordingAndLinkAndPreserveOrdinaryConversations',
  'tomato.gui.chat.ChatFiltersTest.exactSenderIgnoresLiteralPhrasesAllowListsAndInheritedRulesHaveExplicitPrecedence',
  'tomato.gui.chat.ChatFiltersTest.savedSettingsSurviveReloadAndCanBeRemovedWithoutAliasing',
  'tomato.gui.keypop.KeyPopTest.liveMetricsSummarySortingAndCombinedFiltersStayConsistent',
  'tomato.gui.notifications.NotificationsGuiTest.dungeonSelectionPreservesHiddenChoicesAndExistingPreferences'
)
$filters = @(); foreach ($selector in $selectors) { $filters += @('--tests', $selector) }
& "$tools\gradle-7.6.4\bin\gradle.bat" --offline --no-daemon --console=plain `
  --project-cache-dir '.gradle-social' '-PrealmSharkBuildDir=build/social-behavior' test @filters
```

The existing build isolates preferences/history in the test working directory and
uses the in-memory Java Preferences factory. Worker cache and output are isolated.

## Remaining coordinated gates

Integrate the three producer/shell hooks, then run producer-level regressions and
the full required test/JAR checks. Add applicable new editor suites to the scaling
allowlist through its owner; serialize real dialog, keyboard/focus, compact 680×520,
enlarged-font, 150%/200% and populated/empty/save-failure visual checks. Source opening
paths are silent, but native opening/closing has not been exercised in this worker.
Independent final-head review and wave CI/merge gates remain coordinator-owned.

## Primary-wave compact sample-field correction — 2026-09-22

Follow-up on primary `feat/ux-wave-1-trust` at `7aa8593`. The independent settled
`AlertRuleLayoutEvidenceTest` reported that at font 24 and a 680×520 outer window
(664×451 client), `rule-sample-text` was allocated 536×52 but only 466×52 was
reachable in both populated and save-error states.

The sample controls used a wrapping FlowLayout. Wrapping moved each label/field
pair to another row but could not shrink a pair whose preferred width exceeded the
page viewport; the inline label consumed additional width beside the 24-column
text field. The page intentionally has no horizontal scrollbar.

`AlertRuleEditor` now uses the shared `ContentStyle.responsiveGrid(2, 260, 8)` for
sample inputs. Labels sit above their fields with their `labelFor` associations
and accessible names retained. The grid allocates actual available cell width and
drops to one column before two cells would fall below the 260-pixel sizing target.
Each field fills its cell horizontally, and Check sample has a separate shared
wrapping action row. Long input remains fully editable through normal JTextField
caret scrolling; labels, text content and font sizes are retained. The existing
page scrolling accommodates the resulting vertical layout.

Validation: **`compileJava compileTestJava` passed** with JDK 17 / Gradle 7.6.4,
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`, main `--release 8`, and isolated
`-PrealmSharkBuildDir=build/social-sample-layout-7aa8593` /
`--project-cache-dir build/social-sample-layout-cache`.
No test execution or native/focus validation was performed for this narrow fix.
The coordinator's unchanged `AlertRuleLayoutEvidenceTest` and `VisualEvidence`
must be rerun for settled native proof; compilation is not a visual pass.
