# Loot

**Loot** is a core sidebar destination (**Alt+9**). It has two tabs, **Highlights** and **Explore**. They are customizable like the app's other tab strips: reorder them by dragging, from the tab menu or with **Ctrl+Shift+Left/Right**, and hide or show them from the tab menu (saved as `ui.tabs.loot`). The page opens on its first visible tab (Highlights by default). The tab in front is not saved between launches. Only explicit navigation brings a hidden tab forward: a route from another page, Back, a Settings search entry, Home's **Notable loot** tile or a Highlights dungeon cell. Back returns to the tab you left, as you left it.

Every count on this page is an **observed drop, not a pickup**: what capture saw in loot bags, not what you or anyone else picked up.

Loot capture does not depend on this page. The app's loot capture records each accepted bag to saved history, plays bag sounds, runs item and enchant pings and offers the bag to legacy loot sharing whether or not Loot has been opened.

## Highlights

Highlights shows what dropped **Today** or **This session**.

- **Header.** At the left, where the numbers come from: "Saved history · Today", "Saved history · This session", or "This app run · not saved" ([below](#without-saved-history)). At the right, the **Today · This session** choice and ⋯. Today is the local calendar day and This session is the time since RealmShark started. The choice is remembered (`ui.loot.highlights`, Today by default). ⋯ offers **Refresh**, **Loot sharing status…** ([below](#loot-sharing-status)) and **Loot filter settings…** (Settings › Loot filters).
- **Tiles.** **UT drops**, **ST drops**, **Potions** and **White bags**. The Potions sub-line lists up to four stats ("2 Life · 1 Mana · 1 Att · 1 Def · +2 more"), and its tooltip lists them all. Potions without a stat are counted as **other potions**. The White bags sub-line gives the number of bags in the period and how many had no saved bag name ("of 9 bags · 1 without a bag name"). A bag without a saved name comes from an older save and is never counted as white. Each tile's tooltip says "Observed drops, not pickups". For the same period, Home's Today card and these tiles give the same numbers.
- **Notable drops.** A grid of cards, newest first, holding at most the newest 200 visible drops ("Showing the newest 200 of N notable drops"). Filter Loot applies before that limit, so hiding a bag color never leaves older drops of the colors you show out of the grid. A drop is notable when it is a **UT**, an **ST**, a **stat potion** or **enchanted**. An item that fits more than one of these is listed once, with the first that applies in that order. Each card shows the item in a sprite well tinted with its bag's color, the item's name (wrapping to a second line when it is long), a kind chip (UT, ST, Potion, Enchanted), the time, the area ("Unknown area" when none was recorded) and "Not linked to a run" when the bag recorded no exact run. Enter, Space, a double-click or the card menu's **Open run recap** (Shift+F10 or a right-click) opens the recap of that exact run. A drop without one opens nothing.
- **By dungeon.** One cell per area: its portal, the number of bags, then "1 UT · 2 potions". Cells are ordered by bags, most first, with **Unknown area** last. Opening a cell (Enter, Space or a double-click) shows Explore on saved loot, All Items filtered to that dungeon, over the same period the tiles counted. For Today that is every saved session kept to the local day; for This session it is the current session. Unknown area opens Explore unfiltered, because no dungeon filter can select bags without an area. Back returns to Highlights.

### What "enchanted" means

**Enchanted means rare or better: an item with 2 or more recorded enchant slots** (Rare 2, Legendary 3, Divine 4). A common (0 slots) or uncommon (1 slot) item is not enchanted. An item without recorded enchant slots is never counted as enchanted or as not enchanted. A line under the heading says how many such items there are ("1 item without recorded enchant slots is not listed as enchanted").

### Honest numbers

- **Unknown is never 0.** When no bag was saved in the period, every tile shows **—** with "No loot was saved for this period", and the grid shows "No notable drops yet" with how to fill it. A period with saved bags but no notable drop shows real zeros and "UT, ST, stat potion and enchanted (rare or better) drops appear here as they are observed."
- **Partial.** If a saved session of the period cannot be read, the others are still counted. Every tile is then marked "(partial)", and a warning line gives the count: "◐ 1 saved session could not be read; their loot is missing from these counts."
- **Stale.** If a later read fails, the last successful read stays on screen, labeled with its time and the reason.
- **Unavailable.** If the history folder cannot be listed at all, the tab gives the reason in words (never a path) and offers **Try again**.

### Filter Loot applies to the grid only

**Edit › Filter Loot** and **Settings › Loot filters** are one setting (the keys `filterWhiteBag` … `filterBrownBag`). They decide which bag colors appear in the notable grid, and the grid updates immediately without reading history again. When the filter hides drops, a line says how many ("Filter Loot hides 3 of 12 notable drops; the tiles still count them"). If every drop is hidden, the grid says so instead of looking empty. The tiles and the dungeon cells always count every observed drop. Bag sounds are set separately, under Notifications.

### When Highlights reads

Highlights reads saved history in the background, never on the UI thread. It reads when it first shows, when you change the period, and on ⋯ **Refresh**. It reads again soon after capture observes a new bag: while it shows, it checks every 2 seconds and reads 750 ms after a change, once the bag has reached disk. While it shows it also reads every 30 seconds, which picks up a new day at midnight. Showing the tab again does not start a read. Closed sessions are cached and read again only when their files change; the current session is always read. Until the first read finishes, a loading message shows.

### Without saved history

When no history store is open, Highlights shows this app run's live capture instead, labeled **This app run · not saved**. The live list keeps the newest 1,000 bags. Once more bags than that have been seen, the counts cover only those, and the caption and tiles say so ("latest 1,000 bags", partial). In this case Explore is the live dashboard alone.

## Explore

With saved history, Explore opens on **Pictures**. A breadcrumb above it says where you are (for example **Runs › Lost Halls · 21:40 › Marble Seal**, or **Dungeons › Lost Halls › All items › Marble Seal**); every part but the last leads back. The **Runs | Dungeons | Collection** switch beside it chooses the way in and is remembered (`ui.loot.explore.entry`: `runs`, `dungeons`, or `collection`).

- **Runs:** a horizontal strip of compact run cards, newest on the left, below search and filters. Each card shows its best drop; scrolling to the end loads older runs. The chosen run's full-width haul below shows its portal, outcome and time, best drop, and every bag group open at once (highest-ranked bags first), with an 8-slot grid per bag, newest first. **This dungeon so far** beside the bags counts runs with loot, white bags, UTs, STs and stat potions across that dungeon's saved bags. Its six most-dropped notable items combine enchant variants; click one to open its history. In narrow windows this panel moves below the bags. **Loot outside runs** beside the filters shows bags that recorded no run, by session (the newest 10 sessions that have any). **Open run** opens the run's recap, whose compact bag shelf is unchanged. A run still in progress is read again as its loot is saved (about every 30 s while Pictures shows).
- **Dungeons:** a wall of portal tiles with total saved runs, white bags, UTs, rates per run with loot, and the three most-dropped notable items. Sort by **Most runs**, **Whites per run**, **UTs per run**, or **Recent**, and search by dungeon name. Total runs read **—** when only saved loot knows a dungeon; rates use distinct runs recorded with its bags. Opening a tile shows only that dungeon's runs, newest first, with the newest haul selected. **All items from <dungeon>** in **This dungeon so far** opens Collection filtered to that dungeon. The removable **Dungeon: <name>** chip clears the filter; the **Runs** entry shows all runs again.
- **Collection:** every item you have looted, as sprites with drop counts on shelves (**UTs**, **STs**, **Tiered**, **Potions**, **Other**), most-dropped first. Each item shows its best enchanted drop. A shelf shows 60 items, then **Show all N**. **Search items** keeps the items whose name matches. A dungeon filter restricts these shelves and counts to that dungeon's saved bags; remove its chip to see every dungeon.
- **An item** (a tile in Collection, or an item in a run's haul) opens its drops: its best drop, its name and "12 drops · 3 Rare · 1 Legendary", then every drop newest first, as Highlights' drop cards (at most 200). A drop that recorded a run opens that run. **Back** returns through the levels you came from.

Every Pictures level or dungeon move—including the entry switch, breadcrumb links, dungeon tiles, **All items from**, items and drops—is a **Back/Forward** step (also available on the mouse). Picking a run card in the strip stays a local selection. Back and Forward restore the level, dungeon filter, run and item.

The **Pictures | Table** switch above Explore chooses the view and is remembered (`ui.loot.explore.view`). A route to an exact run, such as the run recap's **Open in Loot**, opens Pictures on that run; a route with a query opens Table; Back restores the view and the run. Without saved history, Explore is the live dashboard alone.

**Table** holds every loot view, live and saved, behind **one view selector**. The selector leads the page's one filter row in both modes, and the **Scope ▾** chip in the same row switches between live and saved history ([Session history](SESSION-HISTORY.md#browsing)).

- **Live** (the default) shows this app run's bags. The filter row holds the view selector, the literal search, **Reset filters**, **Filters**, the Scope ▾ chip and ⋯. The Filters drawer holds the bag and dungeon choices, **Multi-select loot facets…** and the Recent Drops range (every retained bag, or 5 minutes, 15 minutes or 1 hour before the newest one; it applies to Recent Drops only). A note and four tiles follow: Matching bags, items, stat potions and white bags; Matching bags shows **—** when the bags are not known. ⋯ offers **Retry view save** and **Reset saved live view** only after the live view state failed to save or could not be read, and a status line then says why.
- **Saved** (**Scope ▾ › This session**, **All sessions** or a past session) queries saved history. The selector moves into the saved filter row, followed by a search over the whole scope (press Enter), **Filters** with the same facets, active filters as chips, the Scope ▾ chip and ⋯ (**Refresh**, saved views, exports, the table's column tools and the drill-downs). **Scope ▾ › Live · this app run** returns to live.

**Views.** Simple lists the nine item views, in this order: **All Items**, **Stat Potions**, **Whites**, **By Bag**, **By Dungeon**, **UTs**, **STs**, **Tiered** and **Recent Drops**. Analyst adds six views, below an "Analyst" header, that read saved history only: **Item occurrences**, **Dungeon loot profile**, **Session comparison**, **A/B cohorts**, **Enemy hit events** and **Loot by source**. Dungeon statistics is in [Runs & DPS › Dungeons › Analysis](ACTIVITY.md#dungeons), and saved character fame is in [Characters › Fame history](CHARACTERS.md#fame-history-analyst).

- **A saved-only view chosen while live** switches Explore to saved history with that view, and the caption beside the selector says **Saved history only**. Each saved-only view's tooltip says so too.
- **Switching between live and saved** keeps the chosen view where both have it. From saved to live, a view only saved history has shows All Items live.
- **The Simple/Analyst switch never changes the query.** If an Analyst view is open when you switch to Simple, it stays in the selector as its **Current view**.
- **Saved history in Simple reads plainly.** One count line, such as "9 bags · 15 item variants · 22 items", replaces the view's description and counts, which stay in its tooltip (Analyst shows them in full). The saved revision's status line is Analyst-only, and an Items column that would show only **—** is left out of the table in Simple. The mode never changes the query.
- A fresh saved Loot opens on All Items of the current session. Routes and drill-downs from other pages open the view they ask for (a run's loot opens Item occurrences of that run).
- View states are compatible with older saves: the live view is still written as its numeric index under `loot-views` in `ux.archive.loot-live`, and the saved view as `facets.view` in `ux.archive.loot`.

**Unknown areas and bags.** An area capture could not name (none recorded, or one the catalog did not recognize) reads **Unknown area** in the live Last dungeon, By Dungeon and Recent Drops tables, in the saved Dungeon column and in the rate rows. This is display only: the dungeon filter lists it as Unknown area but stores it as "Unknown", and the tables keep the recorded name for sorting and search. A blank bag reads **—** in the saved Bag column; exports keep it blank.

**Bags without a saved name.** Older saves can hold a bag whose name was not saved. Saved views still count it as a bag, and its items count as usual. By Bag lists all such bags on one **Unknown bag (name not saved)** row, never merged into a named bag and always last, whatever the sort. They are never counted as white bags. The bag filter lists named bags only, so choosing a bag leaves them out; with no bag chosen they are included. Search finds them as "Unknown bag". Dungeon loot profile, Session comparison and A/B cohorts say how many such bags they saw.

### Item views

Open **Multi-select loot facets…** to combine bag types, dungeons, rarity and tier choices with item category and unlocked-slot and applied-enchant ranges, then choose **Apply facets**. Each numeric range has its own policy for unknown values. Literal search covers item name and ID, bag, dungeon, dropper, tier and rarity. Filtered counts use the same item predicates, and labels distinguish matching items, variants and bags.

**UTs** requires the exact UT label plus a weapon, ability, armor or ring label, and excludes consumables and stat potions. Runes, tokens, skins and other non-tiered items do not count as UTs. **STs** uses the exact ST label, whatever the bag color. **Tiered** includes **T13+ weapons and armor** and **T6+ abilities**; UTs, STs, rings and consumables are not in that view. Tiers come from the item's asset labels. **Whites** means the contents of white or boosted white bags, not an inferred item rarity.

Every item table gives each variant its own row: item ID, unlocked enchant slots and applied enchant count. For example, an unenchanted UT and a two-slot Rare copy of it are separate rows. **Count** is the number of drops of that variant. **Tier**, **Rarity**, **Slots** and **Enchants** describe the variant. The breakdown below the table totals the visible drops by rarity and follows the bag and dungeon filters, the view and the search. Stat potions have no enchant slots, so the live breakdown counts them apart ("· 3 stat potions (no enchant slots)"), never as an unknown rarity.

Rarity comes from the number of unlocked slots: **Common / Unenchanted (0)**, **Uncommon (1)**, **Rare (2)**, **Legendary (3)** and **Divine (4)**. Empty unlocked slots count toward rarity but not toward applied enchants; locked and unused slots count toward neither. Missing or invalid enchant data stays **Unknown**, separate from a confirmed zero-slot drop. The values are snapshots taken when the drop was captured, not later rerolls or inventory changes.

Live **Recent Drops** shows each item's tier, rarity, slots and applied count. It searches the newest 1,000 bags, while the item totals cover the whole app run. Saved **Recent Drops** queries and pages every matching saved bag, with no 1,000-bag limit. In saved Loot, **Item occurrences** lists individual saved item observations, including identical copies in the same bag. Search and facets apply before grouping and paging in every saved view. Use **Apply dates** for From (inclusive) and Until (exclusive) bounds, and ⋯ › **Export all matches…** to export beyond the loaded page. The saved drill-downs are ⋯ items in both modes: **Occurrences of selected variant**, **Loot from selected run**, **Open recorded run** and **Dungeon rate calculation**. Each is enabled when the selected row supports it; the run and rate drill-downs say in their tooltips why they are unavailable. See [Session history](SESSION-HISTORY.md) for named views, the history library and export scopes.

### Saved loot coverage and rates

These rules apply to the Analyst views **Dungeon loot profile**, **Session comparison** and **A/B cohorts**, here and in Runs & DPS › Dungeons › Analysis. They describe a population of visits: they use visit scope, dungeon and entry or overlap bounds, and the item, bag and enchant facets do not narrow them. Undated dungeon, enemy and source counters reject custom periods.

Session comparisons distinguish three cases: **Not captured** loot in run-only imports, **coverage unknown** when a session has no saved bags, and **Partial** coverage when bags were saved. A captured empty bag can establish zero visible items; a missing journal cannot. Saved bags are observations, not proof of continuous recording or of item ownership.

Select a dungeon profile to read its numerator units, eligible visits, visits with no linked bags, ongoing visits, observed duration and exclusions. Per-run rates divide observed counts by eligible visits; per-hour rates divide by observed hours, gaps included. A visit is eligible only if at least one bag was saved in the **same session**, even an empty or unassigned one. Within such a session, visits without linked bags still count in the denominator. Run-only imports and sessions without loot evidence are left out, and their counts and unknown-coverage duration are shown. Ongoing eligible visits are included.

Rates are unavailable when drops cannot be linked to a matching session, visit and dungeon. Any eligible visit without a positive observed duration makes the hourly rates unavailable. Verified dungeon aliases are combined; unknown area names stay separate. These are sample rates, not drop probabilities.

## Loot sharing status

Highlights' ⋯ › **Loot sharing status…** opens the legacy loot-sharing status in a separate window; the app stays usable beside it. It shows the mode (On, Opted out, Preview or Stopped), the queued bags, socket writes, dropped and uncertain sends and the last error, with a Details section. It updates twice a second while open and not at all when closed. The opt-out stays where it was, under **File › Opt-out Loot Sharing**.

Connection and delivery run on a bounded first-in, first-out worker: up to 256 payloads wait and one is active. **Sent to socket** means the local write completed, not that the server accepted it. Definite unsent failures and overflow count as dropped. A failure after a payload was queued is uncertain and is not retried; a definite rejection before queueing allows one reconnect attempt. Opting out clears unsent queued and pending bags and stops a connection that completes late from sending them. Bytes already in flight cannot be recalled. Local alerts are independent of sharing, and this status is separate from Guild Bridge Review.

## Settings › Loot filters

**Settings › Loot filters** has one checkbox per bag color (White, Orange, Red, Gold, Egg, Blue, Teal, Purple, Pink and Brown bags) and **Show all**. It is the same setting as **Edit › Filter Loot**, whose menu ends with **Loot filter settings…**; changing either updates the other. See [Filter Loot](#filter-loot-applies-to-the-grid-only) for what it changes.

## Finding Loot

- The sidebar's **Loot** row, or **Alt+9**.
- Home's Today card: the **Notable loot** tile opens Highlights on the card's own period (Today or This session), and Highlights keeps that choice as a click would.
- Settings search (**Ctrl+K**): **Loot highlights**, **Explore loot** and **Loot filters**.
- A Highlights dungeon cell opens Explore filtered to that dungeon; routes to a run's loot from other pages open Explore on that run's saved loot.

## Validation

`ui.LootEvidenceTest` captures Highlights (populated, empty, without saved history, partial; dark and light), Explore live and saved in Simple and Analyst (including a saved-only view chosen while live), Settings › Loot filters, Chat and About, and Characters › Fame history in the real workspace. It uses synthetic history, and its captures are written to `redesign-p6a-loot`. `HighlightsSourceTest`, `HighlightsModelTest`, `LootHighlightsTest` and `NotableDropRendererTest` cover the Highlights rules (Home parity, enchanted only with 2 or more recorded slots, unknown never 0, partial, the live fallback, Filter Loot on the grid only, reads). `LootExploreTest` covers the view selector. `LootPageTest`, `LootSharingStatusTest`, `LootCaptureTest` and `LootFiltersTest` cover the page, the sharing status, capture without a page and the Filter Loot model. `LootEquipmentTest` and `ParseEnchantsSummaryTest` cover classification, variants and enchant parsing. The one-filter-row check (S6) is `FilterBarEvidenceTest`, and the tab-switch timing check (S8) is `ShellSwitchTimingTest`. The P6a record is [P6a validation](superpowers/plans/2026-09-29-p6a-validation.md). Synthetic captures and tests do not establish live gameplay accuracy.
