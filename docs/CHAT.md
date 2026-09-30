# Chat history

The live Chat workspace groups captured conversations into All, PM, Party, Guild, World, System and Ignored. By default, ignored messages are excluded from All and their original channel views; the original channel and PM direction remain visible in Ignored. Live channel counts show messages available in each view before search filters. World includes public player chat; System includes NPCs (names starting with `#`), unnamed server messages, and application notices.

**Chat** is a core sidebar destination (**Alt+1**). Its filter row holds the search, **Reset**, **Actions**, **Follow latest**, **Filters** and the **Scope ▾** chip, with the channel buttons below it. The **Filters** drawer holds the player, **Starred**, **Show ignored players**, the dates and the sort; active filters show as chips. ⋯ holds the named live views (**Saved views ▸**: **Save current view…**, **Load:** and a name, **Delete view…**, **Reset saved state**) and the live table's column tools (**Columns ▸**, **Column preset ▸**, **Reset columns**, **Copy selected rows**, **Row details…**). A line under the row reports the result of a Saved views action, and warns when the live view state could not be applied or saved.

- **Search:** Type a literal phrase to filter messages, players, channels or local dates. Search ignores case. Ctrl+F focuses search; Enter selects the first result; Escape clears the focused search field.
- **Player:** Narrow results by sender or recipient (in the Filters drawer). Incoming and outgoing PMs both appear when searching for the other player. Select a message and choose **This player** to fill the filter.
- **Show ignored players:** Off by default. Enable this checkbox in the Filters drawer to include captured messages from locally ignored players and positively identified in-game ignores in All and their original channels. Ignored rows use red/rose text and an explicit **Ignored** channel badge; details and exports state the reason. Selected rows retain readable selection colors and the Ignored label. Turn it off to hide those messages again. Spam-only matches remain in Ignored. Live and saved views remember their own visibility choice, separate from the shared ignore rules.
- **Details:** Select a row to read the full wrapped message. Search matches are highlighted in the detail pane. Arrow keys navigate rows; long messages remain complete in details and exports.
- **Stars:** Select a message and press **Star** or Space. Enable **Starred** in the Filters drawer to filter your saved picks. Stars are stored with message IDs and restored in saved-history views.
- **Follow latest:** Follows new matching messages without changing the selected message. Clicking a row, scrolling up, dragging the scrollbar upward or using Up/Home/Page Up releases follow so you can read history. The **new matching messages · Jump to latest** badge counts retained unseen arrivals matching your current filters. Activate it to resume following and clear the badge. Capture continues either way.
- **Copy:** Copy selected messages with Ctrl+C or **Copy**. Shift/Ctrl selection supports multiple rows. **Actions › Copy matching retained messages** copies the entire current result list.
- **Export:** **Actions › Export matching retained messages…** saves a snapshot of the filtered list as UTF-8 text, including dates, channel names and both sides of PMs. This is a local file export.
- **Reset:** Clears the search, player, channel, starred, date and sort choices without deleting messages or changing the Show ignored players preference.
- **Clear:** **Actions › Clear session history…** clears the live messages and stars in every channel after confirmation; saved session history stays available through **Scope ▾ › Saved history**. The original **Edit › Chat › Clear Chat** command also clears all channels. Neither deletes saved log files.

The live workspace retains the latest **10,000 messages** per application session; pending UI batches use the same bound. Every captured message is also queued to shared session history before those limits apply. **Edit › Chat › Save Chat** (also **Settings › Chat › Save chat**) separately controls the existing plain-text log.

## Saved Chat

Choose **Scope ▾ › This session** for older records from the current launch, or **All sessions** or a past launch from the same menu ([Session history](SESSION-HISTORY.md#browsing)); **Live · this app run** returns to the live view. Search, channel/player filters, stars, ignored-player visibility, dates and sorting apply across the entire saved scope before paging. The saved row's **Filters** drawer holds **Channel**, **Sender or recipient contains**, **Starred only**, **Show ignored players** and the dates; the channel and the checkboxes apply at once, and a typed player applies on Enter or with **Apply Chat filters**. **Copy selected transcripts** and **Copy loaded page transcripts** have explicit local scopes; use ⋯ › **Export all matches…** for the full matching saved query in CSV or JSON.

**Apply dates** uses an inclusive From and exclusive Until. Chat recorded local receipt times without an offset, so **Assumed receipt zone** interprets those dates rather than establishing a captured UTC timestamp. The displayed date explanation describes the legacy daylight-saving interpretation.

Saved queries remember independent state and support named views (the saved row's ⋯ › **Saved views**); the live row's ⋯ › **Saved views** keeps separate named live views. Stars and shared-policy edits refresh saved classifications after saving, while an export already in progress retains its original revision. See [Session history](SESSION-HISTORY.md) for the library, dates, saved views and export scopes.

Time and Channel columns resize from their renderer/header metrics when fonts change. You can widen them further; narrow windows scroll horizontally instead of imposing a timestamp-clipping maximum. Selected-message headings and ignore reasons wrap while full messages remain in the detail pane.

Existing PM, party and guild sound settings, custom phrase alerts, fonts, and Umi response hints remain available. **Actions › Chat alert rules…** opens the typed-rule editor silently, with text/token modes, preserved legacy rules, **Check sample** and explicit **Test sound**. See [editing match rules](NOTIFICATIONS.md#editing-match-rules). Local spam rules load from `block.txt`; empty rules are ignored. The existing remote spam-rule request has timeouts and falls back to local rules on failure. New search, starring and export functions make no network requests.

## Spam filters and ignored players

Open **Chat › Actions › Chat filters…** (or **Settings › Chat**) to edit saved rules. Short checkbox captions are followed by wrapping explanations. Settings and list editors scroll in short/enlarged-font dialogs, while Save filters and Cancel remain accessible:

- **Detect advertisements** (advertisements with links) is enabled by default for whispers and World chat. A message needs both a link/domain and sales wording (such as buy, cheap, delivery, or coupon). Matching ignores case, zero-width formatting characters and common domain obfuscation such as `shop [dot] com`. This is a heuristic, not a guarantee that every advertisement is detected.
- **Ignore whisper links** (every whisper containing a link) is optional and off by default. Enable it for stricter filtering; it also catches ordinary guide and Discord links.
- **Ignored players** uses exact, case-insensitive sender names. Select a message and choose **Ignore player** for a shortcut. **Unignore player** removes that local rule; another matching spam rule can still keep the message in Ignored.
- **Blocked phrases** accepts literal phrases or domains, one per line. Matching is case-insensitive; blank and formatting-only entries are skipped. These rules apply to PM, Party, Guild and World.
- **Allowed players** bypasses advertisement, link, phrase and inherited spam rules. Explicit local player ignores and observed game ignores still take precedence.
- **Use existing spam rules** retains the local `block.txt` and existing downloaded keyword list, with an option to disable matching against that list. The pre-existing download stays asynchronous and sends no chat contents.

Your own messages and System/NPC notices are exempt. Filters classify matching messages as **Ignored before any chat alert runs**, including PM, party, guild, custom keyword and realm announcement checks. Showing ignored players does not unignore them or enable their alerts. Opening Ignored, searching, starring or exporting does not play sounds. Each ignored message has a reason in its details and exported transcript. Automatic session history retains received messages while the toggle is off; **Edit › Chat › Save Chat** additionally logs them to text with their ignore reason. Changing visibility does not change capture or logging.

Live and saved Chat views share the current local filter policy. Saving filters or ignoring a player from a saved page reclassifies retained views and affects future live alerts. Saved chat uses today's local rules while its in-game-ignore evidence still describes receipt time. Browsing or reclassification never replays alerts.

**Save filters** applies changes immediately and reports disk saving separately. If saving fails, the rules remain active, the draft stays editable and **Retry save** is available; Cancel does not roll back applied settings. Rules persist locally in `realmShark.properties` when saving succeeds. Reset clears view filters, not spam protection. Ignored messages share the live buffer limit and are also retained in the structured session archive.

### Settings › Chat

**Settings › Chat** holds the same two settings in one place. Open it from Settings, from **Edit › Chat › Chat settings…** (the last entry of that menu) or from Settings search ("Chat settings (filters and saving)"). The section is one scrolling page: **Saving**, then the **Chat filters** heading and the embedded editor's options and lists. The editor's save status, **Cancel** and **Save filters** are pinned below the page, in view however far it scrolls.

- **Save chat** is **Edit › Chat › Save Chat**: the same preference with the same effect (a plain-text log of each message, ignored messages with their reason, in the app folder's chat folder). The checkbox and the menu item always agree. Saving chat does not change saved session history.
- **Chat filters** is the chat filter editor itself, embedded, not a copy. It edits the live chat's rules, and **Save filters** applies and saves them exactly as the **Chat › Actions › Chat filters…** dialog does, with the same save status and **Retry save**. **Cancel** discards unsaved edits by reloading the saved rules.

Clearing chat stays in the Chat menu and on the Chat page.

### In-game ignore observation

RealmShark can only show and log chat `TEXT` packets delivered to the captured connection. Its own hiding is local and happens after message retention. The account-list protocol documents ignore-list updates, not whether the current official server suppresses ignored senders' messages for every chat channel. No live delivery guarantee is established here: messages filtered by the server before delivery cannot be recovered by this toggle.

The optional **Use in-game ignores** setting (use observed in-game ignores when identity is available) is on by default. RealmShark passively consumes `ACCOUNTLIST` for list 1 and applies snapshots (`lockAction = -1`), removals (`0`) and additions (`1`). Lock lists and unknown actions are ignored. These semantics follow the [RealmLib account-list protocol audit](https://git.him.is/code/realmlib/src/commit/6bccefb1efb2b55416078dde70a897a56c85bb70/PACKET_FINDINGS.md?display=rendered); this integration has synthetic regression coverage, not a fresh live-client validation.

A chat packet contains a name and object ID, not an account ID. Matching requires an observed entity with both the same sender name and an account ID. A name/account correlation learned from a message can then be reused within that connection. Consequently, previously ignored players whispering remotely may not be identifiable. The Filters panel reports whether a full list was captured; this is partial observation, not complete synchronization. Local ignored names and phrase rules cover missing identities.

Observed account IDs and name correlations stay in memory and are cleared on a new HELLO or capture restart. No account lookup requests, ignore commands, credential reads, or game mutations are added. Historical messages retain whether the sender was observed ignored at receipt; a later in-game unignore affects future arrivals. The local ignore controls only affect RealmShark.

## Validation

`scripts/chat-filters-validation.gradle` directs builds and test files to `build/chat-filters-validation`, away from running JARs. With the project-local JDK and Gradle configured:

```powershell
.\.tools\gradle-7.6.4\bin\gradle.bat -I scripts/chat-filters-validation.gradle test shadowJar --offline --no-daemon --console=plain
```

Chat tests cover channel/PM routing, packet snapshots, combined literal filters, stars, transcript scope, UTF-8 text preservation, safe literal rendering, bounded incoming bursts, EDT model updates, clearing pending messages, reading position, auto-follow, Umi hints and desktop/compact screenshots. Additional tests cover advertisement false positives, obfuscated domains, exact sender matching, rule precedence/persistence, default-off ignored-player visibility, red/rose styling and labels, saved visibility preferences, silent alert gating while visible, retained-message re-filtering, optional logging, account-list snapshots/deltas, connection reset and filter-editor layout. Screenshots use synthetic messages; they do not establish live packet-capture compatibility.
