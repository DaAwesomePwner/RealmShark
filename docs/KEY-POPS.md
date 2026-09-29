# Key pops

**Key-pops** is under **Advanced** in the sidebar (**Alt+2**). It tracks observed key, rune, vial and inc notifications during the current app session. Portal callouts still trigger configured sounds but never add to pop totals. Unknown portal assets keep their numeric ID so a missing asset cannot masquerade as a known dungeon.

The page leads with its one filter row: the search, **Reset filters**, **Filters**, the **Scope ▾** chip ([Session history](SESSION-HISTORY.md#browsing)) and ⋯. Four tiles follow: **Observed pops**, **Keys**, **Players** and **Dungeons/items**, counted over the events that match your filters. A tile is marked partial when some matching pops have no recorded type, player or dungeon, or when the live list has dropped its oldest pops; its tooltip says which. Then three tabs, customizable like the app's other tab strips (drag, the tab menu or **Ctrl+Shift+Left/Right**; saved as `ui.tabs.keypops-live`):

- **Events** lists times, players, types and dungeons/items, newest first.
- **By player** ranks contributors with total pops, key/rune/vial/inc counts, share of filtered pops and the last pop time.
- **By dungeon / item** shows pop counts, distinct players, shares and last activity.

Around the tables:

- **Times.** In Simple, **Time** and **Last pop** read relatively ("just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", then the date), with the absolute time and display zone in the tooltip. Analyst shows the absolute time in the application's display time zone. Sorting, search and every export keep the absolute time.
- Search is literal, case insensitive and matches every space-separated word. The **Filters** drawer holds **Event type**, **Time range** and **Dungeon / item**, then the multi-select lists (**Additional types**, **Additional exact dungeons/items**, **Apply multiple choices**) and the dates; active filters show as chips. In the live view, relative periods such as 15 minutes, one hour or today resolve when selected; the displayed bounds stay fixed until changed. Double-click a live summary row, or select it and press Enter, to filter events. In saved summaries, use **Show this player's events** or **Show this item's events**.
- A **By player** drill-down adds a removable **Player equals … · Clear** chip. It matches the exact contributor name, ignoring case: Ann does not include Anna. General search stays independent and combines with the chip. Events, summaries, tiles and exports use the same filtered events. Activate the chip to remove only that name filter, or use **Reset filters** to reset the query.
- **⋯** holds the page's actions:
  - **Saved views ▸**: named live views (**Save current view…**, **Load:** and a name, **Delete view…**, **Reset saved state**).
  - **Export events…** exports the matching retained events; **Export current tab…** exports the shown tab's retained rows (on Events, the filtered events). CSV uses UTF-8, UTC event timestamps and spreadsheet-safe text.
  - **Log to file** keeps the existing `keypops.log` preference and records incoming events independently of view filters.
  - **Notification settings…** opens **Settings › Notifications** on its **Key-pops** tab, with dungeon search, **Selected only**, **Select shown / Clear shown**, and missing-completion alerts. Choices save immediately; counts show selections inside and outside the current filter. See [Sound & Notifications](NOTIFICATIONS.md).
  - **Clear history…** resets the live in-memory history and its statistics after confirmation. Saved session history and the log file are kept.
  - The shown tab's column tools: **Columns ▸**, **Reset columns** and **Copy selected rows**.
- **Dungeon alert…**, under the tables, shows the selected row's dungeon in Settings › Notifications with its current choice; nothing changes until you toggle it there.

## Saved contributions

The live view retains the latest 10,000 events, while observed pops are queued to the shared session archive before that limit. Choose **Scope ▾ › This session**, **All sessions** or a past session for persisted history; **Live · this app run** returns to the live view. Search, exact player, multiple types/items, dates and sorting apply to the whole selected scope before paging. In the saved row's **Filters** drawer, enter an exact player or exact dungeon/item names (one per line), tick the types, and choose **Apply contribution filters**. **Apply dates** uses inclusive From / exclusive Until bounds and an explicit zone; paging does not move the interval.

The saved modes are the tabs **Events**, **By player** and **By dungeon / item**. They are customizable like the live tabs and keep their own order (`ui.tabs.keypops-saved`); choosing a tab queries that mode, and the tab of the mode being shown stays visible even if you hid it. **By player** and **By dungeon / item** summarize all matching events, including matches beyond the loaded page. Shares divide contribution counts by matching observed pop events, excluding portal callouts. Contributors are recorded names grouped without case, not verified account identities. Distinct-player counts use that same whole-query scope. The **Time** and **Last pop** columns read relatively in Simple and absolutely in Analyst, as live.

The saved row's ⋯ offers **Export selected…**, **Export page…** and **Export all matches…** as CSV or JSON, for the shown tab's rows (pop events on Events, summaries on a summary tab), and **Saved views** for named saved queries with their table state; live views are kept separately. See [Session history](SESSION-HISTORY.md).

Counts represent received pop notifications, not dungeon completions or a complete server-wide history. Repeated notifications are retained because capture cannot reliably distinguish legitimate repeated pops from duplicate notifications. Legacy text integrations without an explicit event type appear under Other rather than inflating key totals.

Validation: `gradle.bat -I scripts/keypop-validation.gradle test shadowJar --offline --no-daemon --console=plain` using the project-local JDK 17 and Gradle cache. Tests cover notification parsing, combined filters, aggregation, retention, CSV escaping, and rendered Swing layouts. Live packet capture is a separate gameplay check.
