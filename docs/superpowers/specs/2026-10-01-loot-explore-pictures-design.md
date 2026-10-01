# Loot › Explore pictures — design

Date: 2026-10-01 · Status: approved design, awaiting spec review

## Goal

Loot › Explore is a set of text tables today. Replace its default presentation with a **picture of your loot you can
dive into**: game sprites for items, bags and dungeons, and a run-first landing that makes it obvious at a glance what a
run gave you. The tables stay one click away for filtering, export and the analyst views.

Success: opening Explore shows recent runs as cards; selecting one shows its haul — best drop, bags as bag sprites,
each bag opening into the game's 8-slot grid — without reading a table. Every item, run and dungeon on screen is
clickable and leads one level deeper. The same haul appears (compact) in the Runs tab's run recap.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| What Explore lands on | **Runs** — "what did that run give me" — inside a zoomable frame |
| Other entry points | **Dungeons** (atlas) and **Collection** (trophy cabinet) as alternate entries into the same drill-down |
| Relation to the Runs recap's Loot section | **One shared haul component**: full size in Explore, compact in the recap |
| Tables | Kept, behind a **Pictures / Table** toggle sharing one filter state; Pictures is the default |
| "Not collected yet" silhouettes | Out of scope — no per-dungeon drop catalog exists |

## What exists today (research)

- **Explore** = `LootExploreModel` (one `loot-views` `ViewSelector`) routing between `LootDashboard` (live, `JTable`s in
  a `CardLayout`) and an `ArchiveWorkspace` with `LootArchiveClient` (saved, `HistoryTables`, 100-row pages). Wired in
  `HistoricalStatistics.lootWorkspace()` and shown by `LootPage` (`LootTab.HIGHLIGHTS` / `EXPLORE`).
- **Queries** (`LootQuery`) already provide everything the pictures need:
  `ITEMS` (rows per item variant with counts), `RATES` (per-dungeon runs, whites/UTs/STs/potions per run),
  `OCCURRENCES` (one row per drop; exact `variant` facet = item history), and the exact-visit facet
  (`visitSession`/`visitId`). Rows carry `runLinked` and `visitRef()`.
- **Runs**: `RunFeedSource` pages `RunCardModel`s across sessions (portal, outcome, time, duration, loot strip).
  `RunRecapBuilder` builds `RunRecapModel.Loot` (bags of `LootFacts.Item`) from the run's linked `LootFacts.Bag`s;
  it currently drops each bag's `dropper`.
- **Kit**: `Sprites.sprite(id,size)` and `Sprites.paintWell(...)` (bag-tinted well), `Portals.spriteId(map)`,
  `ItemSlot` / `ItemSlot.icon(...)` (slot with tier label and `EnchantInfo` pips), `EnchantPips` (corner pips,
  `glow`), `Tokens.bag(name)`, `Tokens.rarity(Rarity)`, `TileList<T>` (wrapping painted tiles, keyed, `onOpen`),
  `NotableDropRenderer`, `DungeonStripRenderer`, `RunCardRenderer`, `EmptyState`, `SegmentedControl`, `Chip`.
- **Gaps**: no bag sprites (only colored wells; bag object IDs exist in `LootBags`, which maps ID → name only);
  class per run is known only from fame readings and may be missing.

## Architecture

### `BagSprites` (new, `tomato.gui.kit`)

- `int objectId(String bagName)` — reverse lookup over `LootBags` (`"White"` → 1292, `"B.White"` → 1296, …);
  0 when unknown.
- `Icon sprite(String bagName, int size)` — `Sprites.sprite(objectId, size)`; when the ID is 0 or the sprite is a
  placeholder, a well painted in `Tokens.bag(bagName)` (today's look), so unknown bags never render blank.
- `int rank(String bagName)` — value order used everywhere bags are sorted, highest first:
  **White > Red > Orange > Blue > Teal > Gold > Egg Basket > Purple > Pink > Soulbound > Brown > unknown.**
  A boosted bag ranks with its base color, immediately above it.

### `HaulModel` (new, pure, `tomato.gui.loot.haul`)

Input: a header (map, map name, portal ID, outcome, entered, duration, class label or null) and a list of
`HaulModel.Bag(String bag, long time, String dropper, List<LootFacts.Item> items)`. Output (immutable record):

- `hero` — the best single item, or null when there are no items. Order: UT, then ST, then higher enchant rarity
  (Divine > Legendary > Rare > Uncommon > unenchanted > unknown), then higher tier (T14 > T13 > …), then potions,
  then earliest drop. Ties keep the earliest drop. The hero records its bag and dropper.
- `shelf` — bag groups `(bagName, count, bags)` sorted by `BagSprites.rank`, then by earliest drop.
- `openByDefault` — the bag containing the hero (else the highest-ranked bag).
- `tally` — `RunRecapView.LootLine` wording ("14 items in 5 bags · 1 UT · 3 potions"), moved to a shared place so the
  recap, run cards and haul cannot drift.

No Swing, no I/O; fully unit-tested.

### `HaulView` (new, `tomato.gui.loot.haul`)

A `JPanel` rendering one `HaulModel`, in two modes:

- **Full** (Explore): header (portal sprite, dungeon, class, time, duration, outcome, tally) → hero card (large sprite
  with rarity glow, name, tier and rarity chips, "bag · dropper") → bag shelf (bag sprite tiles with "×N", sorted by
  rank) → the open bag as an 8-slot grid of `ItemSlot`s with tier labels, enchant pips and rarity border.
- **Compact** (Runs recap): bag shelf + open bag grid only; header and hero omitted (the recap has its own header).

Interaction: clicking a bag tile opens that bag (one open at a time); several bags of the same type are shown as a
row of grids, newest first. Clicking an item fires `onOpenItem(variantKey)`; an "Open run" link in Full mode fires
`onOpenRun(VisitRef)`. Keyboard: bag shelf and slots are focusable and arrow-navigable; Enter = click. Every slot keeps
the existing `EnchantTooltip` hover. Component names follow the `loot-haul-*` pattern for tests.

### `LootPictures` (new, `tomato.gui.loot.explore`)

The Explore picture host:

- **Header**: breadcrumb (`Explore › Runs › Lost Halls · 21:40`), entry switch **Runs | Dungeons | Collection**
  (`SegmentedControl`), and the **Pictures | Table** toggle.
- **Levels** (`CardLayout`): `RunsLevel`, `DungeonsLevel`, `CollectionLevel`, `ItemLevel`. Each level is a set of
  pre-filled `LootQuery.Facets`, so breadcrumb, Back, the filter drawer and persisted state behave the same at every
  level.
- **Pictures / Table**: Table shows today's Explore unchanged (`LootDashboard` / `ArchiveWorkspace` with all current
  views, including Analyst). Switching carries the current facets across (e.g. a run haul → Table opens
  Item occurrences filtered to that exact visit; an item history → occurrences filtered to that variant).

#### Levels

| Level | Shows | Data |
|---|---|---|
| **Runs** (landing) | Left: run feed grouped by day — `RunCardRenderer` cards (portal, time, duration, class when known, top 3 drop sprites + "+N"); an **Unlinked loot** pseudo-run per session for bags with no run link. Right: `HaulView` (Full) for the selected run; the newest run is selected on open. | `RunFeedSource` (paged); haul from the run's linked bags (exact visit), unlinked from bags with `runLinked=false` |
| **Dungeons** | Grid of portal tiles: runs, whites/run, UTs/run, top 3 drop sprites. Sort: runs (default) or whites/run. Click → that dungeon's runs (left) and its collection (right). | `RATES`; `ITEMS` filtered to the dungeon for top drops |
| **Collection** | Shelves — UT, ST, Tiered, Potions, Other — of item sprites with count badges; enchant variants of one item stack, best rarity in front. Search filters shelves. Each shelf shows up to 60 then "Show all N". | `ITEMS` (variant rows) |
| **Item** | Big sprite, name, tier, rarity, total count; then one mini card per drop: portal, time, bag sprite, enchant pips. Click a card → that run's haul. | `OCCURRENCES` with the `variant` facet |

Any item sprite at any level opens **Item**; any run or drop card opens **Runs** at that exact run.

### Scope and live data

All levels use the filter bar's session scope (current session or all saved history), as Explore does today. The live
session merges in as it does now; the selected haul updates in place when a new bag for the current run arrives.

### Run recap adoption

`RunRecapModel.Loot.Bag` gains `dropper` (nullable; `RunRecapBuilder` passes `LootFacts.Bag.dropper()`). The recap's
Loot section replaces its bag rows with `HaulView` (Compact). Its summary line, reason text and "Open in Loot" link are
unchanged; "Open in Loot" now lands on Explore › Runs at that exact run.

### Persisted state

New keys under the existing `ux.archive.loot` state: `pictures.mode` (`pictures` | `table`, default `pictures`),
`pictures.entry` (`runs` | `dungeons` | `collection`), and the current level's facets. Unknown or missing values fall
back to the defaults. The existing `loot-views` / `facets.view` keys and the `LIVE` view index order are untouched.

## Rollout (four sequential PRs)

1. **P1 — haul foundation.** `BagSprites`, `HaulModel`, shared tally wording, `HaulView` (Full and Compact),
   `RunRecapModel.Loot.Bag.dropper`, and the Runs recap switched to Compact. Visible immediately in Runs.
2. **P2 — Explore pictures frame + Runs level.** `LootPictures`, breadcrumb, entry switch, Pictures / Table toggle with
   facet carry-over, `RunsLevel` (feed + Full haul + Unlinked loot), persisted state, "Open in Loot" routing.
3. **P3 — Item and Collection levels.**
4. **P4 — Dungeons level.**

## Error handling and empty states

- Reads run off the EDT through the existing readers with `Cancellation`; switching level or run cancels the
  in-flight read. Results apply on the EDT only if still current.
- No runs in scope: `EmptyState` — "Runs appear here once capture records a dungeon." Table remains available.
- Run with no linked bags: haul shows "No bags recorded in this run" (the recap keeps its existing reason text).
- Missing item sprite: placeholder sprite plus the item name in the tooltip. Unknown bag: tinted well.
- Unknown enchant data: no pips (existing behavior), never a guessed rarity.
- Unknown class: header omits it rather than showing "Unknown".
- Read failure: one-line reason in place of the level's content; the Table toggle still works.

## Testing

Focused local checks only (per the repo validation policy):

- `HaulModelTest` — hero ordering (UT > ST > rarity > tier > potion, ties → earliest), shelf ranking including boosted
  and unknown bags, default open bag, tally wording, empty input.
- `BagSpritesTest` — every `LootBags` name maps to its ID; boosted names; unknown → 0 and tinted fallback; rank order.
- `HaulViewTest` (synthetic fixtures, headless where possible) — Full vs Compact composition, bag switching,
  `onOpenItem` / `onOpenRun` firing, keyboard Enter on a slot (queued AWT `KeyEvent`s, not `Robot`).
- `LootPicturesTest` — level routing from breadcrumb, entry switch and item/run clicks; Pictures ↔ Table facet
  carry-over; persisted-state round trip with unknown values.
- Existing recap tests updated for the Compact haul.

## Out of scope

- "Not collected yet" silhouettes (needs a drop catalog).
- New capture fields (soulbound per item, character per drop).
- Changes to Highlights, the analyst views, or export.
