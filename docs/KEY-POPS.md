# Key pops

The Key-pops workspace tracks observed key, rune, vial and inc notifications during the current app session. Portal callouts still trigger configured sounds but never add to pop totals. Unknown portal assets keep their numeric ID so a missing asset cannot masquerade as a known dungeon.

- **Events** shows sortable timestamps, players, types and dungeons/items, newest first.
- **By player** ranks contributors with total pops, key/rune/vial/inc counts, share of filtered pops and the last pop time.
- **By dungeon / item** shows pop counts, distinct players, shares and last activity.
- Search is literal, case insensitive and matches every space-separated word. Combine it with multiple event types, dungeon/items and dates. In the live view, relative periods such as 15 minutes, one hour or today resolve when selected; the displayed bounds stay fixed until changed. Double-click a live summary row, or select it and press Enter, to filter events. In saved summaries, use **Show this player's events** or **Show this item's events**.
- A **By player** drill-down adds a removable **Player equals … · Clear** chip. It matches the exact contributor name, ignoring case: Ann does not include Anna. General search stays independent and combines with the chip. Events, summaries, cards and exports use the same filtered events. Activate the chip to remove only that name filter, or use **Reset filters** to reset the query.
- Live **Export events (retained CSV)** exports matching retained events; **Export current tab (retained CSV)** exports the active event or summary view. CSV uses UTF-8, UTC event timestamps and spreadsheet-safe text. The table uses the application's display time zone.
- **Notifications** opens the Key pops section of the shared Sound & Notifications module, with dungeon search, **Selected only**, **Select shown / Clear shown**, and missing-completion alerts. Choices save immediately; counts show selections inside and outside the current filter. See [Sound & Notifications](NOTIFICATIONS.md).
- **Log to file** retains the existing `keypops.log` preference and records incoming events independently of view filters.
- **Clear history** resets the entire in-memory history and its statistics after confirmation, preserving the log file.

## Saved contributions

The live view retains the latest 10,000 events, while observed pops are queued to the shared session archive before that limit. Use the session picker or **Browse saved** for persisted history. Search, exact player, multiple types/items, dates and sorting apply to the whole selected scope before paging. Enter exact dungeon/item names one per line and choose **Apply contribution filters**. **Apply dates** uses inclusive From / exclusive Until bounds and an explicit zone; paging does not move the interval.

**By player** and **By dungeon / item** summarize all matching events, including matches beyond the loaded page. Shares divide contribution counts by matching observed pop events, excluding portal callouts. Contributors are recorded names grouped without case, not verified account identities. Distinct-player counts use that same whole-query scope.

Choose **Export events (all matches)…** from Events or **Export current summary (all matches)…** from a summary tab. The shared toolbar also offers selected/page/all-match CSV or JSON exports. Named saved views retain the query and table state; live views have separate named controls. See [Session history](SESSION-HISTORY.md).

Counts represent received pop notifications, not dungeon completions or a complete server-wide history. Repeated notifications are retained because capture cannot reliably distinguish legitimate repeated pops from duplicate notifications. Legacy text integrations without an explicit event type appear under Other rather than inflating key totals.

Validation: `gradle.bat -I scripts/keypop-validation.gradle test shadowJar --offline --no-daemon --console=plain` using the project-local JDK 17 and Gradle cache. Tests cover notification parsing, combined filters, aggregation, retention, CSV escaping, and rendered Swing layouts. Live packet capture is a separate gameplay check.
