# RealmShark interface

How the RealmShark desktop app is organized after the redesign (P0 to P6): its pages and tabs, Simple and Analyst, the keyboard shortcuts, what you can customize, the filter row, how values are shown, where the older pages went and which preferences the interface saves. The dated history of the original 2026-09-06 refresh and the capture fixes that followed it is kept under [History](#history).

- Design: the [UI/UX redesign spec](superpowers/specs/2026-09-26-ui-ux-redesign-design.md) and the [redesign roadmap](superpowers/plans/2026-09-26-redesign-roadmap.md).
- Validation: [P5a](superpowers/plans/2026-09-28-p5a-validation.md), [P5b](superpowers/plans/2026-09-28-p5b-validation.md), [P6a](superpowers/plans/2026-09-29-p6a-validation.md) and [P6b](superpowers/plans/2026-09-29-p6b-validation.md). The P6b record also reviews the final Simple and Analyst screenshot set of every page. The screenshots are build output and are not part of these docs.
- Typography, spacing and responsiveness history (2026-09-18 to 2026-09-20): [UI consistency](UI-CONSISTENCY.md).
- What each page does in detail: the guides linked from the [README](../README.md#pages).

<a id="information-architecture-after-p6a-2026-09-29"></a>

## Information architecture

This is the interface after P6 (P6a and P6b, 2026-09-29). The sidebar lists six core pages and a collapsed **Advanced** group of five, with **Settings** below the list. Every page of the app is one of these twelve. Pages are addressed by stable IDs (`home`, `characters`, `runs`, `loot`, `quests`, `chat`, `party`, `key-pops`, `timeline`, `logging`, `bridge-review` and `settings`), never by their position. The saved sidebar layout uses the same IDs.

| Sidebar | Pages |
| --- | --- |
| Core | **Home** (Alt+H), **Characters** (Alt+4), **Runs & DPS** (Alt+R), **Loot** (Alt+9), **Quests** (Alt+6), **Chat** (Alt+1) |
| **Advanced (5)**, collapsed by default | **Party** (Alt+3), **Key-pops** (Alt+2), **Timeline** (Alt+T), **Logging** (Alt+0), **Bridge Review** (Alt+B) |
| Below the list | **Settings** (Alt+, or Alt+N) with six fixed sections: **Notifications**, **General** (Combat history), **Appearance**, **Loot filters**, **Chat** and **About** |

The app opens on the first visible core page in your saved order, which is Home by default. Below 1000 pixels the sidebar becomes an icon rail; the menu icon above it, or **Alt+M**, opens the labeled page list. A page's Alt key opens it even when its row is hidden or the Advanced group is collapsed, and the row shows while that page is in front.

The menu entries that have a Settings section open it: **Edit › Sound › Sound & Notifications...** (Notifications), **Edit › Filter Loot › Loot filter settings…**, **Edit › Chat › Chat settings…** and **Info › About**.

### Tabs on each page

Tabs have stable IDs. Each group saves its order and its hidden tabs in `ui.tabs.<group>`. A page opens on its first visible tab: the tab in front is not saved, and startup and saved-state restores only select. Only explicit navigation (a route, Back, a search entry, a card or tile action) brings a hidden tab forward. **(A)** marks a tab shown only in Analyst.

| Page | Tabs | Group |
| --- | --- | --- |
| Characters | **Roster · Exalts · Pets · Fame history** (A, with saved history) | `characters` |
| Characters › character sheet | **Overview · Gear · Exalts · Pet · Fame · Build · Goals · Notes · Snapshot evidence** (A) · **Death annotation** (only for a character marked dead) | `character` |
| Runs & DPS | **Feed · Dungeons · Live meter · Resources & buffs · Recordings** | `runs` |
| Runs & DPS › Resources & buffs | Live: **Buff timeline & resources · Uptime summary · Selected window**. Saved: **Resources & buffs · Uptime summary · Coverage · Selected window** | `activity-combat`, `saved-resources` |
| Loot | **Highlights · Explore** | `loot` |
| Quests | **Board · Planner** | `quests` |
| Chat | No tabs: channel pills (**All · PM · Party · Guild · World · System · Ignored**) over one message table | — |
| Party | **Current Area · Runs · Ability Use** (A) | `inspect` |
| Key-pops | Live: **Events · By player · By dungeon / item**. Saved: the same three modes | `keypops-live`, `keypops-saved` |
| Timeline | No tabs: one event table | — |
| Logging | **Discovery · Re-entry trace · Packets · Stat explorer · Event samples · Field catalog** | `logging` |
| Bridge Review | **Review · Settings · Logs · Saved review** | `bridge` |
| Settings › Notifications | **Messages · Bags · Key-pops · Realm events · Other alerts · Recent decisions** | `notifications` |

Key-pops' saved mode is part of the saved query, so the tab of the mode a query shows is always revealed. The Settings sections themselves are fixed, so every setting stays reachable.

Within a tab, Characters › Roster (Gallery or Table), Runs & DPS › Feed, Quests › Board and Quests › Planner (Cards or Table) switch views from ⋯ › **Table view** (and back) in Simple, and from a switch in the filter row in Analyst. A feed card or a Home **Recent runs** row opens the **run recap**: a header, a row of tiles, then the sections **Damage · Loot · Players · Resources · Timeline · Evidence** (Evidence in Analyst only).

## Simple and Analyst

The header's **Simple | Analyst** switch, **Ctrl+Shift+A** and **Settings › Appearance** set one display mode (`ui.mode`), and the last choice is used at the next start. The mode changes what is shown, never a query, a saved view or an export.

Analyst adds:

- **Evidence:** each card's ⓘ provenance note starts expanded, and a saved-history result's caption shows its rows, page, pinned revision, sort and coverage notes. Simple keeps only what asks for action or qualifies the rows (no matches, a partial read).
- **IDs and revisions**, for example character IDs on roster cards.
- **Tabs and views:** Characters › Fame history, the sheet's Snapshot evidence, Party › Ability Use, Runs & DPS › Dungeons › Analysis (and each card's Analyze), Loot › Explore's six saved-only views (Item occurrences, Dungeon loot profile, Session comparison, A/B cohorts, Enemy hit events and Loot by source), the run recap's Evidence section and the Live meter's Legacy view.
- **Analyst-only columns** (below), and the view switches described above.

### Relative times

In Simple, the time columns of Runs & DPS › Feed's Table view, Timeline, Resources & buffs' saved table, Party › Runs and Party's saved history, Key-pops (live and saved) and Bridge Review (Review, Logs and Saved review) read **just now**, **12 min ago**, **3 h ago**, **yesterday** or **4 days ago**, and the date from 7 days on. The tooltip gives the absolute time and its zone, and the cells refresh once a minute. An unknown instant keeps its own wording ("Unknown time"), dimmed.

Analyst and every export show absolute times. **Logging** always shows absolute times. The change is display only: sorting, search (it matches the absolute text, so "12 min ago" finds nothing), Copy and exports keep the recorded instant.

### Analyst-only columns

| Page | Column | In Simple |
| --- | --- | --- |
| Timeline | **Meaning** | Hidden |
| Bridge Review › Saved review | **Session** and **Journal** | Hidden; the selected record's details and the export keep them |
| Loot › Explore, saved | **Items**, when no row on the page has a value | Hidden |

Column widths never change on a mode switch. A saved column layout never records a column the mode hides as hidden by you: it keeps your own choice and width for Analyst.

## Keyboard shortcuts

| Keys | Action |
| --- | --- |
| **Alt+H**, **Alt+4**, **Alt+R**, **Alt+9**, **Alt+6**, **Alt+1** | Home, Characters, Runs & DPS, Loot, Quests, Chat |
| **Alt+3**, **Alt+2**, **Alt+T**, **Alt+0**, **Alt+B** | Party, Key-pops, Timeline, Logging, Bridge Review |
| **Alt+,** or **Alt+N** | Settings |
| **Alt+5** | Runs & DPS › Dungeons (the Statistics page before P6a) |
| **Alt+7** | The character sheet's Build tab for the character in game, else the last one played; the Characters list ("No characters yet") when there is none (the Build pointer page, earlier My Info, before P6a) |
| **Alt+8** | Runs & DPS › Live meter (the DPS Logger page before P6a) |
| **Alt+M** | The labeled page list (navigation menu) |
| **Alt+Left** | Back (also the mouse Back button) |
| **Alt+Right** | Forward (also the mouse Forward button) |
| **Ctrl+K** | Edit › Find settings and actions… |
| **Ctrl+Shift+A** | Switch Simple and Analyst |
| **Ctrl+Shift+S** | Start or stop capture (File › Start capture connection) |
| **Ctrl+F** | Chat: focus search |
| **Ctrl+Shift+Up** / **Down** | Move the focused sidebar row (core rows), or the focused run recap section |
| **Ctrl+Shift+Left** / **Right** | Move the selected tab while the tab strip has focus |
| **Shift+F10** or the context-menu key | The menu of the focused sidebar row, tab strip, run recap section, Dungeons card or Highlights drop |
| **Space**, **Enter**, **F4** or **Alt+Down** on the Scope chip | Open the Scope menu |
| **Esc** | Close an open filter drawer (focus inside its row) and focus **Filters**; cancel a sidebar or tab drag; close the Scope menu; close Quests' details drawer; leave an Exalts class for the grid. A focused control's own Esc comes first: Chat's search, for example, clears its text. |

Alt+5, Alt+7 and Alt+8 go through navigation, so Back returns to where you were. Page-specific keys are in each page's guide.
Forward restores views left by Back, one step at a time. Opening a new route clears Forward history.

## Customizing

### Sidebar

- **Menu:** right-click a row, or focus it and press Shift+F10 or the context-menu key: **Move up**, **Move down**, **Pin to top** (Advanced pages), **Unpin from top** (pinned pages), **Hide** or **Show in sidebar**, **Show hidden ▸** and **Reset navigation**. Items that cannot apply stay visible but disabled.
- **Keyboard:** **Ctrl+Shift+Up/Down** moves the focused core row.
- **Drag:** press a core row (pinned Advanced rows included) and move it past the system drag threshold (5 px by default). The whole row drags; there is no grip. A violet line marks where it will land, and the release drops it there and saves the order once. A drop below the core rows puts the row last, so a drop never pins or unpins. **Esc**, the row losing focus or a release beside the sidebar cancels and saves nothing. Near the list's top or bottom edge the list scrolls. The compact icon rail drags the same way. Unpinned Advanced rows and Settings do not drag; use their menu.
- The keyboard and the menu are the alternatives to dragging (WCAG 2.2 SC 2.5.7). Each row's accessible description gives its Alt key and the keys that move it or open its menu.
- Settings is never hidden, and at least one core page always stays visible. A hidden page stays reachable through its Alt key, routes and **Show hidden ▸**.

### Tabs

- **Drag** a tab with the left button past the drag threshold. Tabs swap live once the pointer crosses a neighbour's midpoint, and the order is saved once, on release. **Esc** during a drag puts every tab back and saves nothing.
- **Menu:** right-click the tab strip, or focus it and press Shift+F10 or the context-menu key: **Move left**, **Move right**, **Hide tab**, **Show hidden ▸** and **Reset order**. Each item is enabled only when it would change something.
- **Keyboard:** **Ctrl+Shift+Left/Right** moves the selected tab.
- A view always keeps one tab that is neither Analyst-only nor conditional, so its last such tab cannot be hidden. Analyst-only and conditional tabs are skipped without changing the saved order.

### Other orders and states

- The run recap's sections reorder from each header's menu (**Move up**, **Move down**, **Reset order**) or with Ctrl+Shift+Up/Down.
- Collapsible sections, the Cards/Table choices and each filter drawer remember whether they were open or which view was shown.

## The filter row

Each page that filters has one filter row, one line high at 1240×800 with font 13 while its drawer is closed (success criterion S6, checked for every page, live and saved, in the P6b validation record):

- **Search** on the left. In saved history it searches the whole scope when you press Enter.
- **Filters** opens a drawer under the row with the page's filter controls, closed by default. It reads **Filters · N** while N filters are active, and each bar remembers whether its drawer was open (`ui.filters.<bar>.open`). Each active filter is a removable chip beside it. **Clear** removes them all and shows only while a filter is active.
- **Esc** closes an open drawer while the focus is inside the row or the drawer, as the Filters button would, and moves the focus to Filters. While the drawer is closed, Esc is left to the page.
- **Scope ▾** on the archive pages ([below](#scope)).
- **⋯** (More actions) on the right holds the secondary actions: views (Table view, Cards view), saved views and view state (Save view state, Reset saved view state), exports and the shown table's column tools (**Columns ▸**, **Column preset ▸**, **Reset columns**, **Copy selected rows**, **Row details…**). It hides while it is empty.

Controls that must stay in view sit in the row too, for example Logging's collection switch and Pause or the Live meter's encounter position.

### Scope

Seven archive pages choose between the live view and saved history with one **Scope ▾** chip in the filter row: Runs & DPS › Feed's Table view, Timeline, Runs & DPS › Resources & buffs, Party (all three tabs), Loot › Explore, Chat and Key-pops.

| Chip | Shows |
| --- | --- |
| **Scope: Live** | The live view of this app run, not saved history |
| **Scope: Saved · this session** | Saved history of this app run; the first saved choice |
| **Scope: Saved · all sessions** | Saved history of every session |
| **Scope: Saved ·** *session label* | One saved session; a long label is cut at about 18 characters with "…" |
| **Scope: Saved · selected session** | A session that is not in the list |

The tooltip and the accessible description give the full wording, and the accessible name is "Scope". Click the chip, or focus it and press Space, Enter, F4 or Alt+Down, for its menu: **Live · this app run**; then, under **Saved history**, **This session**, **All sessions** and each readable saved session; then **History library…** and **Refresh session list**. Esc closes the menu and returns the focus to the chip.

- While live, the chip sits in the page's own live row. In saved history it sits in the saved row, whose ⋯ starts with **Refresh** (reload the results) and holds **Saved views ▸**, **Export selected…**, **Export page…**, **Export all matches…**, **Open export folder** and the column tools. At narrow widths the chip moves into the row's search area instead of wrapping.
- Loot › Explore's one view selector leads the row, live and saved.
- Runs & DPS › Feed's cards and Dungeons' cards read every saved session and have no chip. Dungeons › Analysis and Characters › Fame history read saved history only, so their chip has no Live item.
- The header's **Browse saved history** opens Runs & DPS › Feed's Table view on **Scope: Saved · all sessions**, without starting capture.

## Values

- **—** is unknown, never zero, and its tooltip says why; a confirmed zero shows **0**. A missing count or ID in a table is "—".
- **≈** marks an estimate, for example Build's **Weapon DPS** and **MP/sec** tiles, pet feeding costs or fame gained from experience. The tooltip says how it was estimated.
- **(manual)** follows a value you entered, such as Planner stock (the spec draws it as ✎).
- **(partial)**, or **◐** on Loot › Highlights, marks a count with a missing part: some sessions could not be read, or a Key-pops tile leaves out pops without a recorded field. The tooltip or a note says what is missing.
- **Stale** values are dimmed and say how old they are ("Last updated 12 min ago", "Last seen 2 h ago"): the last good result stays after a failed read.
- A bag without a known map is in **Unknown area**, a bag without a saved name is **Unknown bag (name not saved)**, and an unknown time reads **Unknown time**, dimmed.
- Loot counts are **observed drops, not pickups**.

## Where the old pages went

| Before | Now |
| --- | --- |
| Runs (sidebar page) | Runs & DPS › **Feed** (saved-run cards, the run recap, the archive table as its Table view) |
| DPS Logger › Damage meters | Runs & DPS › **Live meter**, with one filter row ([DPS meters](DPS-METERS.md)) |
| DPS Logger › Resources & buffs | Runs & DPS › **Resources & buffs**, in both Simple and Analyst |
| The Encounter library dialog | Runs & DPS › **Recordings**, which also lists saved summaries and kept full detail |
| My Info, then the Build pointer page | The character sheet's **Build** tab; its four metric cards are tiles (**Health**, **Mana**, **Weapon DPS**, **MP/sec**) with **Details…** links |
| Statistics › Loot (Live log, explorer, legacy sharing status) | **Loot › Highlights**, **Loot › Explore** and Highlights' ⋯ › **Loot sharing status…** |
| Statistics' dungeon, session comparison and cohort views, and Dungeon Stats | Runs & DPS › **Dungeons** (cards) and its Analyst **Analysis** view; most are also Analyst views of Loot › Explore |
| Statistics' Character fame view | **Characters › Fame history** (Analyst) |
| Statistics' Fame Graph, Fame Table, map breakdown and `.fame` autosave | Removed (user decision); **Open fame session file…** stays ([where Statistics went](STATISTICS.md)) |
| Security, then Inspect | **Party** (Advanced) |
| The About dialog, the Chat filters dialog, Edit › Filter Loot | Still there, and also **Settings › About**, **Chat** and **Loot filters** |
| **Browse saved** / **Current live view**, the session picker and the scope ⟳ on every archive page | The **Scope ▾** chip. ⟳ is ⋯ › **Refresh** in saved history, and **History library…** moved from ⋯ into the Scope menu, beside **Refresh session list** |
| Loot › Explore's two synced view selectors (live and saved) | One selector at the front of the filter row |
| Live view-state rows and controls (Save view state, Reset saved view state, named live views) on Party, Runs, Timeline, Resources, Chat, Key-pops and Loot | ⋯ items |
| Column controls under the saved tables | ⋯ › **Columns ▸**, **Column preset ▸**, **Reset columns** |
| Logging's Views row, Save diagnostic samples, sampling and Clear data | Logging's ⋯ (**Saved views ▸**, **Save diagnostic samples**, **Sampling ▸**, **Clear data…**); the collection switch and Pause stay in the row ([Logging](LOGGING.md)) |
| Key-pops' metric cards, footer buttons and the "Multi-select / absolute dates / view state" panel | Four tiles under the filter row; exports, **Log to file**, **Notification settings…** and **Clear history…** in ⋯ (**Dungeon alert…** stays in view); multi-select and dates in the Filters drawer; times follow Simple and Analyst ([Key-pops](KEY-POPS.md)) |
| Timeline's Collection and Pause row, visit and kind choices and export button | A status line under the filter row, the Filters drawer with chips, and ⋯ › **Export displayed history…** |
| Party's separate "Search runs" row and Ability Use's two-line row | One filter row on each of Party's tabs |
| Bridge Review's two rows of search, filters and actions | One filter row each on Review and Logs; Saved review's Session and Journal are Analyst-only ([Bridge Review](BRIDGE.md)) |
| Settings › Notifications' fixed sections | Customizable tabs ([Notifications](NOTIFICATIONS.md)) |

## Saved preferences

The interface keeps its choices as plain preferences in the app folder's `realmShark.properties`:

- `ui.mode`: Simple or Analyst.
- `ui.nav.order`, `ui.nav.hidden`, `ui.nav.pinned` and `ui.nav.advanced`: the sidebar order, hidden pages, pinned Advanced pages and whether the Advanced group is open.
- `ui.tabs.<group>`: each tab group's order and hidden tabs (the groups are in the [tabs table](#tabs-on-each-page)).
- `ui.filters.<bar>.open`: whether a filter row's drawer is open.
- `ux.archive.<name>`: one document per saved workspace or live view (for example `ux.archive.loot` and `ux.archive.loot-live`) with its last state (live or saved, the scope, the query and the table layouts) and its named views.
- Smaller choices such as `ui.collapse.*` (collapsible sections), `ui.order.run-recap`, `ui.runs.view`, `ui.characters.view`, `ui.quests.view`, `ui.dungeons.view`, `ui.home.window` and `ui.loot.highlights`.

**Added in P6b:** `ui.tabs.notifications`, `ui.tabs.keypops-saved`, and `ui.filters.<bar>.open` for the new `logging`, `bridge-review`, `bridge-logs`, `inspect-runs` and `ability` rows. P6b adds no saved type and changes no history format. Every `ux.archive.*` key, `ui.nav.*` and the live roster view states keep their formats; a saved table layout may now carry the width of a column the mode hides.

**Left behind** (read by nothing, never deleted): from P6a, `ui.tabs.statistics`, `ux.archive.statistics` (including named saved Statistics views, no longer offered) and the `ux.archive.statistics-*` keys ([where Statistics went](STATISTICS.md#preferences-left-behind)). A saved sidebar layout that names `statistics`, `my-info` or `dps-logger` is read and those names are ignored. The one exception is `dps-logger`: if a layout hid Runs while DPS Logger stayed visible, Runs & DPS is shown once, and `dps-logger` is still written beside a hidden Runs so that an older build does not show Runs again. P6b leaves no preference behind.

## History

### Redesign phases (2026-09-26 to 2026-09-29)

Each phase has its plan and validation record in `docs/superpowers/plans/`:

| Phase | What it did | Validation |
| --- | --- | --- |
| P0 | Platform: Java 17, Darklaf removed | [P0](superpowers/plans/2026-09-26-p0-validation.md) |
| P1a, P1b, P1c | Design kit; shell and navigation; filters, tabs and columns | [P1a](superpowers/plans/2026-09-26-p1a-validation.md), [P1b](superpowers/plans/2026-09-26-p1b-validation.md), [P1c](superpowers/plans/2026-09-26-p1c-validation.md) |
| P2 | Home | [P2](superpowers/plans/2026-09-26-p2-validation.md) |
| P3a, P3b | Characters: gallery, sheet and journal; Exalts grid, Pets, Pet and Fame tabs | [P3a](superpowers/plans/2026-09-27-p3a-validation.md), [P3b](superpowers/plans/2026-09-27-p3b-validation.md) |
| P4 | Quests: Board and Planner | [P4](superpowers/plans/2026-09-28-p4-validation.md) |
| P5a, P5b | Runs: combat auto-save, run feed and recap; Runs & DPS tabs, Live meter, Recordings and Dungeons | [P5a](superpowers/plans/2026-09-28-p5a-validation.md), [P5b](superpowers/plans/2026-09-28-p5b-validation.md) |
| P6a | Structure: page IDs, the Build, DPS Logger and Statistics pages retired, Loot Highlights and Explore, Settings sections | [P6a](superpowers/plans/2026-09-29-p6a-validation.md) |
| P6b | Consistency: the Scope ▾ chip and one filter row, the Advanced page restyles, relative times and Analyst-only columns, sidebar drag and tab drag fixes, Esc for filter drawers, light-theme outlines, the final screenshot set | [P6b](superpowers/plans/2026-09-29-p6b-validation.md) |

### Original refresh and capture fixes (2026-09-06 and 2026-09-07)

The notes below are kept as they were written. They record the 2026-09-06 refresh, which imported the Tomato application and restyled its eight pages, and the capture fixes that followed. "Run", "Design and controls", "Feature preservation" and "Build and validation" describe that 2026-09-06 baseline, not the current interface.

This folder now builds a complete Swing desktop application. The supplied download contained the RealmShark library branch, without the Tomato application described in its README. The Tomato GUI and its backend/resources were imported from `X-com/RealmShark`, branch `tomato`, commit `257a1c5` (2026-02-28). The supplied library sources remain in place. The upstream MIT license and credits are preserved.

The imported Tomato baseline is v1.9.2. Its checked-in version constant still said v1.9.1 because upstream generated it during builds; this combined build generates the separate RealmShark artifact version. The Tomato constant now reflects v1.9.2, avoiding a false update popup. The asset-cache revision stays v1.9.1 because this metadata correction does not change asset extraction. Future upstream release notices explain that changes must be merged and rebuilt to preserve the customizations, rather than instructing users to replace the application with the stock JAR.

#### Run

- Double-click `Launch-RealmShark.cmd` for the application.
- Double-click `Preview-RealmShark.cmd` to inspect the UI without starting capture, extracting game assets or making the startup API requests. The capture controls are disabled in preview. It uses empty real feature panels rather than invented account data.
- Or run `java -jar build/libs/RealmShark-v1.2.3.jar` (add `--preview` for preview mode). Existing `--path` and `--help` arguments remain supported.

The launchers use the downloaded project-local JDK when present, otherwise Java from PATH. The normal application retains the upstream game-asset extraction, Npcap requirement, API integrations, saved settings and capture auto-start preference. Preview mode bypasses that startup path, but settings changed through its menus are still saved in the current working directory.

#### Design and controls

The default RealmShark Violet theme adds dark surfaces, violet accents, modern fonts for controls, larger click targets and clearer focus states. **Edit > Theme** offers Violet Dark, Violet Light and Increase contrast. Saved Darklaf theme choices migrate to the nearest variant; high-contrast choices also enable Increase contrast. Saved log-font preferences remain supported; changes now apply to all four chat channels.

P0 (2026-09-26) targets Java 17 and removes Darklaf. Current platform and theme
validation is recorded in [P0 validation](superpowers/plans/2026-09-26-p0-validation.md);
the dated legacy-theme checks below describe earlier releases.

The sidebar automatically collapses to labeled-by-tooltip icons below 1000 pixels. At smaller widths the descriptive subtitle and footer hint yield space to the actual controls. The window supports sizes down to 680 by 520 logical pixels (or the available screen size). Legacy panels retain their scrolling and sub-tabs.

- **Alt+1** through **Alt+8**: navigate to a section (in the original refresh; the current shortcuts are listed under [Keyboard shortcuts](#keyboard-shortcuts)).
- **Ctrl+Shift+S**: start or stop capture, using the same action as File > Start Sniffer (now **File › Start capture connection**).
- **Ctrl+F** in Chat: focus search in the active channel.
- **Enter / Shift+Enter** in search: next / previous match, wrapping at the end.
- **Escape** in search: clear the query.

Search is literal and case-insensitive. It highlights a match without filtering, deleting, or inserting log messages. Empty-state text is painted separately and is never part of copied/saved logs.

#### Feature preservation

| Original section | Retained capabilities |
| --- | --- |
| Chat | All, PM, Party and Guild channels; spam filtering; chat saving/clearing; keyword pings; Umi responses |
| Key-pops | Event log, clear, dungeon notification choices and log-to-file setting |
| Security | Player parsing, filter controls, player/guild links and ability-use log |
| Characters | Existing Exalts and Pets tabs, pet controls and existing data hooks |
| Statistics | Fame graph, fame table, session management, loot and dungeon statistics |
| Daily Quests | Quest requirements/rewards and completion filtering |
| My Info | Existing player equipment, stats and damage display |
| DPS Logger | Live/saved encounters, previous/next navigation, dungeon list, filter editor/presets, freeze, icon/text modes and equipment/sort options |
| Menus | Capture/auto-start, loot sharing opt-out, chat settings, volume and all sound pings, item/entity/enchantment pings, loot filters, themes, fonts, borders, about, Java information and bandwidth |

Some character functions were already disabled upstream (`CharacterPanelGUI.ENABLED = false`, with additional tabs commented out). They were preserved as received, not silently enabled or represented as repaired. Current game-protocol compatibility and live data population require an in-game session; UI validation does not establish them.

#### Build and validation

Use **JDK 17** with the included Gradle 7.6.4 wrapper:

```powershell
.\gradlew.bat test shadowJar
```

The combined build resolves the original dependencies from Maven rather than requiring a manually copied RealmShark JAR in `libs`. Output: `build/libs/RealmShark-v1.2.3.jar`. UI tests run in `build/ui-test`, keeping their settings separate from the user's preferences. They require a graphical desktop session.

Tests open the actual application in preview mode, exercise all eight navigation destinations at 1240, 760 and 680 pixel widths, inspect preserved menus, verify the capture guard and search behavior, and render all eight pages. Screenshots are written to `build/ui-test/screenshots`; HTML test results are in `build/reports/tests/test/index.html`.

Validated on 2026-09-06: the build and all five UI/search tests passed, including switching to the legacy high-contrast light theme and back. The packaged JAR's `--help` entry point also passed. All main views and their existing sub-tabs were rendered for review. Preview logs include an expected missing `assets/xml/players.xml` diagnostic because no game assets were extracted. Darklaf also logs a Windows cleanup warning when trying to delete its loaded native DLL; the theme-switch assertions passed.

No live packet-capture/gameplay validation has been performed. Packet parsing, damage calculations and the original library implementation were retained; this refresh is focused on presentation and the combined build.

#### ProtonVPN capture fix (2026-09-06)

The later VPN fix supersedes the capture-validation limitation above for the tested capture path. On this machine ProtonVPN Smart mode selected a WireGuard adapter, which Npcap 1.88 opened successfully with `DLT_RAW` (12). The original sniffer interpreted every packet as Ethernet and could therefore open the tunnel without decoding its packets.

Capture now carries the actual adapter link type into packet decoding, supporting Ethernet (including the existing VLAN path), raw IPv4, and NULL/LOOP loopback headers. Original Ethernet packet factories remain compatible. IPv6, unsupported formats and truncated frames are ignored rather than selecting an unusable adapter. This follows [libpcap's per-handle link-type contract](https://www.tcpdump.org/manpages/pcap_datalink.3pcap.html).

Adapter selection and packet consumption now share a condition-guarded queue. This retains a first packet received before the consumer starts, prevents another adapter from overwriting the winner, and wakes waiting consumers on stop. Unused adapters are stopped with `breakLoop`; each native reader closes its own handle after the loop exits. The capture buffer timeout is 250 ms instead of 60 seconds. Packet timestamps use the handle's actual precision. The footer displays the selected adapter; its tooltip remains available in compact layouts.

Validation: all **13 tests passed** (8 capture/packet regressions and the existing 5 UI tests). An eight-second passive check of the existing game connection selected **WireGuard Tunnel (link type 12)** and delivered **105 incoming / 37 outgoing payload segments**, with **0 stream errors**. Shutdown left **0 capture reader threads**. The diagnostic printed only status and counts; packet/account contents were not saved or displayed. This validates adapter discovery, decoding and stream delivery, not every downstream gameplay feature.

Close and reopen RealmShark to load the rebuilt JAR. Connect ProtonVPN before starting capture. The subsequent recovery fix below adds automatic adapter rescanning. No VPN settings or network drivers were changed.

#### Live capture recovery (2026-09-06)

Read-only diagnostics of the stalled running app confirmed `processorPresent=true`, `processorAlive=false`, `queueStopped=true`, and zero native capture readers. The window's event thread was responsive. Both Chat and DPS stopped because their shared capture worker had exited while the app retained its processor reference. The old build did not retain the triggering exception, so the specific historical trigger could not be recovered.

The capture worker now supervises individual capture attempts. A reader exit or processing exception closes the attempt and reopens adapters after one second. Fifteen seconds without game traffic refreshes adapter discovery, including VPN interfaces recreated by a reconnect. Explicit Stop cancels retries. Terminal failures reset the UI control through the event thread. The footer reports captured TCP packets, successfully decoded packets and game ticks; these counts restart on each capture attempt.

TCP reassembly now handles overlapping retransmissions, out-of-order first data after SYN, 32-bit sequence wrap, FIN payloads, and changed connection endpoints. ACK-only packets do not accumulate. The old gap handler could skip missing encrypted bytes and had an unreachable stop branch. Gaps exceeding the bounded reorder buffer now trigger recovery instead. Invalid game-frame lengths fail promptly rather than poisoning the framing buffer. Fresh handshakes clear stale framing/alignment history and preserve the initial game packets; capture begun mid-connection still uses the existing alignment mechanism.

Lifecycle failures are recorded in `logs/capture-health.log`, with a single rotated previous file (approximately 256 KiB each). These new logs contain event descriptions, exception classes and stack frames, not packet bodies, addresses or credentials. They are ignored by Git. The legacy optional packet/error logging features are unchanged.

Validation: **28 tests passed**, including the original UI and VPN tests plus recovery, cancellation, TCP, framing and handshake regressions. A passive 20-second check through WireGuard observed **3,686 decoded packets and 73 NEWTICK packets** by the last status update; shutdown left no capture readers. This verifies the real decoder path, not a prolonged gameplay soak or every DPS calculation. No packet contents were printed or saved by the diagnostic.

Restart RealmShark using `Launch-RealmShark.cmd` to load the update. If capture must recover mid-connection and ticks remain at zero, change areas or reconnect the game to provide a fresh handshake. Recovery cannot reconstruct packets already missed during an outage.

#### Loot-triggered capture crash (2026-09-06)

The next user's run produced a concrete cause in `capture-health.log`: `LootGUI.displayDungeonIcon` initialized `ParseDungeon`, whose mandatory read of missing `assets/xml/mods2.xml` threw `FileNotFoundException` and then `ExceptionInInitializerError`. Later attempts in the same app failed with `NoClassDefFoundError` because Java retained the failed class initialization. The installed assets contain `mods.xml` and `portals.xml`; the second modifier file is absent. This explains why a decoder-only probe passed while gameplay with a loot entry stopped capture.

Dungeon metadata now loads files independently, treats `mods2.xml` as optional, closes input streams, preserves valid modifier and portal lookups, and tolerates missing/malformed metadata. Unknown modifier names remain in the map data but do not produce invented numeric IDs or null-unboxing exceptions. Dungeon grades and fallback portal icons remain available. The old public `ParseDungeon` methods remain compatible.

Terminal capture failures now carry the exception type and code location to a persistent, wrapping error message. It remains visible at compact widths where the ordinary footer hint is hidden. Restarting capture clears the message.

Validation: **35 tests passed**, including absent/optional/malformed metadata, known modifier and portal lookups, repeated loot dungeon rendering, and the compact error display. A standalone reproduction against the user's extracted assets failed with `FileNotFoundException: assets\\xml\\mods2.xml` using the previous JAR, then rendered three consecutive dungeon icons successfully using the rebuilt JAR and returned modifier IDs `[98, -15]` for `FEEBLEMINIONS_1;|D`. This reproduction did not capture traffic or submit loot. A full app restart is necessary to replace the previously failed Java class.

#### Saved DPS encounters showing only elapsed time (2026-09-06)

Inspection of the running app confirmed all three encounters were retained: Tomb of the Ancients had 98 hit entities / 388 damage rows, Ice Citadel 108 / 246, and Deadwater Docks 64 / 76. Replaying the saved icon renderer reproduced a null-pointer exception for the latter two: `ImageBuffer.getOutlinedIcon` called `getScaledInstance` on the null returned for a nonpositive/unknown entity sprite ID. The renderer had already drawn the dungeon/time heading when it failed.

Both normal and glowing icons now use the existing empty placeholder when sprite lookup returns null, preserving damage rows rather than aborting the encounter. Font measurement also has a default when a custom font has not been initialized. Switching display panels revalidates their layout, and returning to Live renders immediately rather than leaving the previous saved encounter visible until another packet arrives.

Validation: **37 tests passed**, including unknown/empty sprite IDs and repeated navigation through three synthetic saved encounters with damage. All three actual encounters rendered successfully both offline and in the running app after reloading the three changed renderer classes. No restart was needed for this session. Local backups were saved with the existing `.dps` exporter format and debug packets excluded under `DPS-Recovered/2026-09-06`; this directory is ignored by Git. The rebuilt JAR includes the same changes for future launches.

#### Delayed class-loading failures and protected launches (2026-09-07)

The session started at 22:15 on September 6 kept running while its `build/libs` JAR was rebuilt. Later trade, stasis and failure packets triggered `NoClassDefFoundError` caused by `ClassNotFoundException`. A read-only class-availability probe confirmed `FailureCode`, `TradeItemData` and `StasisOrbs` could not load in that running JVM, while all three loaded successfully from the same JAR in a fresh JVM. Replacing an open JAR invalidated lazy class loading; this was not another ProtonVPN adapter failure.

`Launch-RealmShark.cmd` now calls a PowerShell launcher that copies the finished build to `.runtime/RealmShark-<SHA256>.jar` and launches that immutable copy. Identical builds reuse their copy; changed builds use different paths. Staging excludes concurrent writers, published runtime contents are verified before reuse, and only the launcher's own temporary staging file is deleted. Published runtimes are retained because a JVM may still be using them. Working directory, local JDK preference and application arguments (including preview and custom asset paths) are preserved. Use the launcher rather than launching Gradle's output directly.

Capture initialization failures now enter the same retry loop as adapter/read/processing failures. Repeated exceptions back off from 1 to 32 seconds; ordinary idle rescanning still waits one second. Cleanup and status-listener runtime exceptions cannot bypass recovery, and explicit Stop wakes the retry immediately. Class-loading errors are intentionally terminal: retrying inside a damaged JVM cannot repair it. The persistent error now explicitly requests an application restart through the launcher. Diagnostics include validated missing-class identifiers but never arbitrary exception messages.

Validation: **39 Java tests passed**, including initialization, processing, reader exit, cleanup, broken status listeners, explicit cancellation, and actionable class-loading failures. `scripts/Test-RuntimeJar.ps1` also launches a real Java fixture, replaces its build JAR before a late class load, and verifies the active runtime still loads the original class. The same test checks build reuse, different build paths and damaged-copy detection. These checks do not establish an indefinite gameplay soak.

The damaged session's 11 retained encounters and four visible chat channels were backed up locally under `DPS-Recovered/2026-09-07-restart` (debug packets excluded) and restored into a fresh protected runtime. Class availability succeeded for all three previously failing classes. The restarted capture was verified alive on WireGuard Tunnel with 26,404 decoded packets and 326 decoded ticks, all 11 restored encounters present, and each restored chat view preserving its original text.
