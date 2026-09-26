# Wave 3 lane C: alerts and Bridge

Branch `w3/alerts`, based on `a94240c`. This covers CHAT-3 (the remaining draft slice), KEY-3,
ALERT-4, BRIDGE-3 and BRIDGE-4. These are worker-lane results: coordinator integration, scaled
validation, review and merge gates are still pending.

## Commits

| Commit | Package |
| --- | --- |
| `27028ea` | Draft contract, Chat and Key-pop entry points, route targets (CHAT-3, KEY-3) |
| `89a0113` | Bounded decision records, Sound/chat/realm/key-pop instrumentation, Recent decisions tab (ALERT-4) |
| `5ecd5d7` | Bridge draft versus active settings, confirmation result, Bridge alert draft (BRIDGE-3) |
| `1e942af` | Versioned review journal, saved-review reader and tab (BRIDGE-4) |
| `9e17f3d` | Coalesced decision refresh; hidden decision table does not rebuild |
| `1d25d00` | Bridge settings status layout fix |

## Draft contract for the Loot, Bridge and other lanes

A draft is a detached value object that holds only strings and numbers:

```java
// tomato.realmshark.AlertRules.Draft (immutable; value capped at 160 chars, sample at 2,000)
public static AlertRules.Draft chat(AlertRules.Mode mode, String value, String sampleMessage, String source)      // TEXT_CONTAINS | SPACE_TOKEN | null
public static AlertRules.Draft item(AlertRules.Mode mode, String value, int sampleItemId, String sampleName, String source) // ITEM_ID | NAME_CONTAINS | null
public static AlertRules.Draft entity(int sampleType, String source)                                              // ENTITY_TYPE
public AlertRules.Rule proposedRule()   // null for a sample-only draft; IllegalArgumentException when invalid

// tomato.gui.maingui.AlertRuleEditor
public static void openDraft(AlertRules.Draft draft, Runnable onReturn)   // EDT-safe; modal; onReturn runs once after the dialog closes
public static AlertRuleEditor draft(AlertRules.Draft draft)               // same editor without a window (tests, embedding)
public AlertRuleEditor(AlertRules service, AlertRules.Domain domain, Collection<String> legacy, String title, Runnable testSound, AlertRules.Draft draft)
public void open(Runnable onReturn)
public boolean focusRule(AlertRules.Mode mode, String value)             // select a saved rule by exact mode and value
```

To open a draft, the editor adds the proposed rule as an unsaved row. When an identical rule
already exists, it selects that rule and adds nothing. It then checks the sample silently and
shows where the draft came from, plus whether the category's sound is currently on. Opening a
draft never saves, enables a sound or plays audio. Only the explicit **Save rules** and
**Test sound** actions do anything. Invalid proposals, such as a token that contains a space, are
not added and the editor says why.

Loot-side use (worker B), for example from a selected occurrence row:

```java
tomato.gui.maingui.AlertRuleEditor.openDraft(
    tomato.realmshark.AlertRules.Draft.item(tomato.realmshark.AlertRules.Mode.ITEM_ID,
        Integer.toString(itemId), itemId, itemName, "Loot · " + timeLabel + " · " + dungeon),
    () -> { /* reselect the same occurrence and focus its table */ });
```

Through the router, the equivalent is `Route.to(Destination.ALERT_DRAFT).withPayload(draft)`.
The payload never causes a save.

## Behaviour by ID

**CHAT-3 (draft slice).** The Chat detail actions have two new buttons:

- **Alert from message…** drafts `TEXT_CONTAINS` from the words selected in the message, or from
  the whole message when nothing is selected.
- **Alert on mentions of <player>…** drafts `SPACE_TOKEN`. Chat rules match message text, not
  senders, and the tooltip says so.

The draft carries the message as a detached sample. When the editor closes, the same message is
reselected and focused. If filters or history retention have removed it, a status line says so
instead of selecting a different row. Your own messages cannot trigger chat alerts, so the
message draft is disabled for them. Starred-view discoverability was already done in Wave 2.

**KEY-3.** Key pops has a new **Dungeon alert…** button. It takes the dungeon from the selected
event, the selected By-dungeon row or the Dungeon filter.

- **Unknown names:** `NotificationFocus.resolveDungeon` accepts exact known names only. Runes,
  vials, incs, unknown portals and near-miss spellings are reported explicitly, and nothing opens.
- **With the router:** the button routes `NOTIFICATIONS` with `NotificationFocus.dungeon(name)`.
- **Without the router:** it selects the page directly. It finds the shell through
  `WorkspaceShell` and offers **Back**.

Notifications opens Key pops and filters to the dungeon, which stays visible even when
Selected-only was on. It focuses the checkbox and states the dungeon's current choice. No choice
changes. **Back** and **Done** restore the previous search and Selected-only filter. The
contributor report (tab, exact player, selection) is left untouched.

**ALERT-4.** `tomato.realmshark.AlertDecisions` holds detached, in-memory records: the latest 200
decisions, plus the latest 100 no-match results in a separate ring so chatter cannot push out
matches. Records are never persisted or replayed. Decisions are recorded at these points:

- **Chat, ignored:** recorded only when the message would otherwise have alerted.
- **Chat, precedence:** a channel sound takes precedence. When a keyword also matched, the
  explanation says so.
- **Chat, keywords:** a keyword match, a no-match, or rules that are unavailable.
- **Realm events:** a phrase match with the alert off, cooldown with elapsed seconds, a skipped
  defeat message, or no phrase matched.
- **Key pops:** selected, missing-completes, or not selected (with the reason).
- **Sound:** `Sound.play(long id)` completes the decision at the enable, mute and volume-0 gates.
  The asynchronous playback result (played on a device, unavailable, audio busy) is correlated by
  decision ID. Plain `play()` now records a direct decision instead of returning silently, so bag
  and trade sounds are covered for mute and playback outcomes.

Notifications has a new **Recent decisions** tab:

- Its outcome filter covers Played, Suppressed, Unavailable, Pending and No match, and it can
  include no-match results.
- **Details** show the rule as recorded, the explanation and the playback detail.
- **Edit rule** looks up the recorded rule by mode and value in the current rules. It reports
  "now rule N (it was rule M)", or says the rule is no longer active and selects nothing. For
  other decision types it opens the realm-event phrase (or says the rule was removed), the
  dungeon, the sound row or the enchantment editor.
- **Draft rule from sample** seeds a draft from a no-match chat, item or entity sample.
- The table only rebuilds while it is shown.

**BRIDGE-3.** Settings now show:

- **Active now:** mode, CSV item count, endpoint host and review-log state, taken from
  `Snapshot.config`.
- **Unsaved changes:** the fields that differ, by label only; the token value is never shown.
- **Inline validation**, a **Revert to active** button, and the actual confirmation result
  (`Snapshot.confirmation`).

The confirmation result has these states: queued, received, rejected, uncertain, not requested,
not queued, and cancelled. Each is tracked for one accepted configuration generation and is
never inferred from the save.

The service already validates, loads the CSV and saves before it switches (`configureOnWorker`).
A failed save therefore leaves the previous settings active. The GUI now reports this inline
("Not saved: … The previous settings remain active (mode). Your draft is still in the form")
instead of a modal dialog, and the draft stays editable. Review rows also offer **Item alert from
this drop…**, a detached `ITEM_ID` draft. Closing it reselects the same review by ID.

**BRIDGE-4.** Journal lines are now written in format 1:

```
{"journal":"realmshark.bridge.review","version":1,"service":"<uuid>","appSession":"<id, optional>","review":{…unchanged redacted review…}}
```

`BridgeJournal.read(List<Path>)` depends on no service, settings or transport:

- It reads the configured log's `.1` backup and then the current file, or journals the user
  chooses explicitly.
- Legacy bare-review lines are split into "legacy run N" wherever the service-local counter
  restarts.
- Every record is qualified by journal file name and session.
- A repeated identity is replaced by the later line.
- Malformed, incomplete, unknown-type and future-version lines are reported with their line
  numbers (capped at 200 reported, 100,000 records).

The **Saved review** tab opens the configured log or chosen files. It shows the summary, the
skipped lines, details marked as historical, and a CSV export that includes the identity
columns. It explains that observations never written to a journal cannot be recovered.

## Tests (JDK 17 serialized runner, fresh results directory each run)

New tests:

| Test | Covers |
| --- | --- |
| `tomato.gui.maingui.AlertDraftContractTest` | The draft never saves, enables or plays; handling of identical, invalid and entity drafts; bounds |
| `tomato.gui.chat.ChatAlertDraftTest` | Message and player drafts; returning restores the source row or explains its absence |
| `tomato.gui.keypop.KeyPopNotificationHandoffTest` | Fake Navigator installed and `Navigator.NONE` restored; exact focus with no choice change; Back restores both views; unresolved names |
| `tomato.realmshark.AlertDecisionsTest` | Off, muted, volume 0, played and unavailable via the fake device; realm cooldown, disabled, defeat and no-match; loot and entity hooks; bounds and eviction |
| `tomato.gui.chat.ChatAlertDecisionTest` | No match, ignored, channel precedence, muted and unavailable from real chat delivery |
| `tomato.gui.notifications.RecentDecisionsTest` | Distinct outcomes; viewing never plays or records; Edit rule when the rule moved or was removed; draft from sample |
| `tomato.bridge.BridgeDraftActiveTest` | Failed save keeps the previous active config and the editable draft; Revert; validation; rejected and received confirmation via a fake transport; Bridge review draft |
| `tomato.bridge.BridgeSavedReviewTest` | Real versioned writer with rotation, legacy and malformed lines; a fake transport that fails if invoked; no configure, CSV reload or receive |

Updated tests: `BridgeUiTest` (four tabs), plus the `SoundSeam` test helper.

Final results on `1d25d00`:

- `test --tests` over `tomato.gui.chat.*`, `tomato.gui.keypop.*`, `tomato.gui.notifications.*`,
  `tomato.gui.maingui.*`, `tomato.realmshark.*`, `tomato.bridge.*`, `tomato.gui.bridge.*`,
  `tomato.backend.data.CharacterPublicationTest`, `tomato.gui.quest.QuestConsistencyTest` and
  `tomato.gui.stats.*`: **343 tests, 0 failures, 0 errors, 0 skipped**.
- Bridge-only rerun after the layout fix: 34/0/0/0.

Not run by this lane: the full suite, `shadowJar`, `testUi150`/`testUi200`, and native
packaging.

Screenshots that tests produced under `build/w3-alerts/ui-test/screenshots` were spot-checked:
Bridge Settings and Saved review at 1240 and 680 px, Notifications Recent decisions at 960 px,
and Chat at 500 px. This was not an independent review.

## Coordinator requests

1. **TomatoData.customSoundAlert** (coordinator-owned). Replace the body so entity matches record
   rule identity:
   ```java
   long decision = tomato.realmshark.AlertDecisions.entityAlert(getEntityIdPings(), idType);
   if (decision != 0) Sound.custom.play(decision);
   ```
   Entity no-matches are deliberately not recorded; every spawn would flood the history.
2. **LootGUI.notifyItems** (worker B). Change the parameter to `java.util.function.LongConsumer alert`
   and replace `boolean itemMatch = data.isItemPing(item.statValue, name);` and
   `if (itemMatch || enchantMatch) alert.run();` with:
   ```java
   long decision = tomato.realmshark.AlertDecisions.lootItem(data.getItemPings(), item.statValue, name, enchantText, enchantMatch);
   if (decision != 0) alert.accept(decision);
   ```
   The call site `notifyItems(bag, Sound.custom::play, …)` then binds to `play(long)`. Tests that
   pass a `Runnable` lambda become `id -> …`. Until then, item and entity sounds record a direct
   "Alert sound" decision (mute and playback outcomes are covered, but there is no rule identity
   and no no-match).
3. **Route targets.** Register these with the shell navigator (adapt to the registry API):
   ```java
   tomato.gui.notifications.AlertRouteTargets.notifications(notifications, () -> shell.select(13));
   tomato.gui.notifications.AlertRouteTargets.alertDraft();
   ```
   `NOTIFICATIONS` accepts no payload, or a `NotificationFocus` for a section, a dungeon or a
   decision ID. No `BRIDGE_REVIEW` target was needed.
4. **Scaled validation allowlist** (`scripts/typography-validation.gradle`). Add:
   - `tomato.gui.maingui.AlertDraftContractTest`
   - `tomato.gui.chat.ChatAlertDraftTest`
   - `tomato.gui.keypop.KeyPopNotificationHandoffTest`
   - `tomato.gui.notifications.RecentDecisionsTest`
   - `tomato.bridge.BridgeDraftActiveTest`
   - `tomato.bridge.BridgeSavedReviewTest`
   - `tomato.gui.bridge.BridgeUiTest`

   `NotificationsConsistencyTest` is already listed and now exercises the new tab.
5. **Coverage ledger.** Move CHAT-3, KEY-3, ALERT-4, BRIDGE-3 and BRIDGE-4 to "implemented; lane
   tests pass". ALERT-4 closes only after hooks 1 and 2 land.

## Known gaps

- Hooks 1 and 2 above are outside this lane. Loot-side draft buttons belong to worker B.
- `ALERT_DRAFT` opens a modal editor over the origin. A Back entry pushed by the router is
  harmless but unnecessary; direct `openDraft` is preferred.
- `TomatoMenuBar` calls `Sound.play()` when a user switches some sounds on. These now appear as
  direct decisions.
- A pending decision stays "playback pending" if a caller submits without calling
  `Sound.play(long)`, for example the legacy `Consumer<Sound>` test constructor of `ChatGUI`.
- In legacy journals, a restart is not detected if the new run's first journaled ID is larger
  than the previous run's last one (for example, when earlier reviews in that run were never
  written).
- Decision records are session-only by design. Saved-review export covers journaled records only.
