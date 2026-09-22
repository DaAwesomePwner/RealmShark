# Key pops

The Key-pops workspace tracks observed key, rune, vial and inc notifications during the current app session. Portal callouts still trigger configured sounds but never add to pop totals. Unknown portal assets keep their numeric ID so a missing asset cannot masquerade as a known dungeon.

- **Events** shows sortable timestamps, players, types and dungeons/items, newest first.
- **By player** ranks contributors with total pops, key/rune/vial/inc counts, share of filtered pops and the last pop time.
- **By dungeon / item** shows pop counts, distinct players, shares and last activity.
- Search is literal, case insensitive and matches every space-separated word. Combine it with event type, dungeon/item and time range (all retained, 15 minutes, one hour or today in local time). All cards and tables use the same filters. Double-click a summary row, or select it and press Enter, to filter events.
- A **By player** drill-down adds a removable **Player equals … · Clear** chip. It matches the exact contributor name, ignoring case: Ann does not include Anna. General search stays independent and combines with the chip. Events, summaries, cards and CSV use the same filtered events. Activate the chip to remove only that name filter, or use **Reset filters** to clear it together with search, type, dungeon/item and time filters. In saved views, these dashboard filters narrow the loaded page.
- **Export CSV** saves the filtered event history in UTF-8, with UTC timestamps and spreadsheet-safe text. The view displays local time.
- **Notifications** opens the Key pops section of the shared Sound & Notifications module, with dungeon search, **Selected only**, **Select shown / Clear shown**, and missing-completion alerts. Choices save immediately; counts show selections inside and outside the current filter. See [Sound & Notifications](NOTIFICATIONS.md).
- **Log to file** retains the existing `keypops.log` preference and records incoming events independently of view filters.
- **Clear history** resets the entire in-memory history and its statistics after confirmation, preserving the log file.

The live view retains the latest 10,000 events, while the shared session archive keeps every observed pop until its session is deleted. The module defaults to Current Session; use its session picker or Browse saved for historical pages. Saved-page time filters follow that page's latest captured pop. Counts represent received pop notifications, not dungeon completions or a complete server-wide history. Repeated notifications are retained because there is no event ID with which to distinguish separate legitimate pops from duplicates. Legacy text integrations without an explicit event type appear under Other rather than inflating key totals. See [Session history](SESSION-HISTORY.md).

Validation: `gradle.bat -I scripts/keypop-validation.gradle test shadowJar --offline --no-daemon --console=plain` using the project-local JDK 17 and Gradle cache. Tests cover notification parsing, combined filters, aggregation, retention, CSV escaping, and rendered Swing layouts. Live packet capture is a separate gameplay check.
