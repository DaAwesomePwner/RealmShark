# RealmShark presentation redesign — design spec

- **Date:** 2026-09-26
- **Status:** Draft for user review
- **Branch:** `claude/realmshark-ui-ux-redesign-cb0914` (spec only; implementation happens in phase branches)
- **Builds on:** the four merged UX waves (`docs/UX-ROADMAP-2026-09-21.md`, `docs/UX-EXECUTION.md`). Those waves made the data trustworthy, findable and connected. This program is the presentation pass on top: turn a statistics dump into a polished view of what happened and what is happening.

## 1. Goals, non-goals, success criteria

### Goals

1. A player sees the important things immediately: current character, what's happening now, what just happened, what to do next.
2. Detail is always one or two clicks away (drill-down), never on the surface by default.
3. Fewer top-level destinations; advanced and diagnostic tools stay available but out of the way.
4. One consistent visual system: cards, tiles, chips, sprites, buttons, inputs, tables and filters look and behave the same everywhere.
5. Keep the violet-on-dark palette and the app's speed.

### Non-goals

- No new analytics beyond what the listed source changes enable (no drop-chance predictions, no name/time identity joins, no quest-readiness inference from loot).
- No change to capture, decoding or damage-calculation semantics.
- No web/JavaFX rewrite; this stays a Swing + FlatLaf desktop app.

### Honesty invariants (unchanged from Waves 1–4)

These are presentation rules, not features to remove. The redesign moves the explanatory text, not the distinctions:

- Unknown is never shown as zero; estimates are never shown as measurements; manual values are never shown as captured.
- Cross-module links are exact (VisitRef / EncounterContext). No joins by name or timestamp. (User decision: run cards show exact links only.)
- Stale data is labeled as stale.

### Success criteria (task-based, measured on synthetic fixtures)

| # | Task | Target |
|---|---|---|
| S1 | From launch, see current character's maxed state, today's fame, and the last run's loot | 0 clicks |
| S2 | Find which stat on the current character still needs potions and how many | ≤ 1 click |
| S3 | See which pinned quests expire today and what they reward | ≤ 1 click |
| S4 | Open the damage breakdown of the last completed run | ≤ 2 clicks |
| S5 | See all exalt tiers for one class and how far each stat is from the next tier | ≤ 2 clicks |
| S6 | With filters collapsed, no page shows more than one row of filter controls | all pages |
| S7 | Sidebar shows ≤ 7 core destinations by default | yes |
| S8 | Switching between core destinations | ≤ 100 ms p95 on the large synthetic history |
| S9 | Home live refresh never blocks the EDT for more than 16 ms | yes |

## 2. Decisions captured from the brainstorming session

| Topic | Decision |
|---|---|
| Architecture | Two layers: a new **glance layer** (summary screens) over the existing **analyst layer** (today's detailed views, restyled) |
| Navigation | ~6 core sections + a collapsed **Advanced** group; any advanced item can be pinned up top |
| Adjustability | Users can **reorder and hide** sub-tabs and sidebar items; persisted |
| Evidence text | Tucked away (dim "—" + tooltip, per-card Evidence disclosure) **and** a global **Simple / Analyst** mode |
| Landing | New **Home** that absorbs **My Info** |
| Statistics section | **Dissolved** into Home, Characters, Runs and Loot |
| Quests quick glance | Repeatable / one-time / done badges, expiry countdown (when the format is verified), reward-first cards. No inventory "have it" check |
| Run cards | **Exact links only** (no key-popper guess) |
| Delivery | **Phased PRs**, each usable on its own |
| Visual direction | Approved as mocked (cards, tiles, chips, sprite-first) |
| Source changes | Retire the six legacy Darklaf themes; save more character data; auto-save combat per run; raise main sources to Java 17 |
| Motion | None by default; tiny (≤ 100 ms) only where it clearly helps, with a Reduce motion setting |

## 3. Architecture

### 3.1 Two layers

```text
┌──────────── Glance layer (new) ────────────┐   click / Enter    ┌──────── Analyst layer (existing, restyled) ────────┐
│ Home · Character sheet · Run feed + recap  │ ─────────────────▶ │ Archive tables · Stat explorer · Timeline · Logging │
│ Quest board · Loot highlights · Exalt grid │ ◀───── Back ────── │ Occurrences · cohorts · provenance · exports         │
└───────────────┬────────────────────────────┘                    └──────────────────────┬─────────────────────────────┘
                │ reads (read-only projections)                                          │
                ▼                                                                        ▼
   Existing sources: TomatoData / MyInfo snapshot · CharacterJournal · DiscoveryLog visits · DpsSnapshot
   LootDashboard.State · KeyPopHistory · ProgressionData quests · SessionStore archives (runs, loot, fame, encounters*)
                                                      (* new, see §8.4)
```

- Glance screens own **no** data. Each reads through a small, immutable **view model** built off the EDT from existing revisioned snapshots or `ArchiveQuery` reads, then handed to the EDT (`SnapshotRefresh` pattern).
- Drill-downs use the existing `Route` / `Navigator`. New `Destination` values: `HOME`, `RUN_RECAP`, `CHARACTER_SHEET`, `QUEST`, `SETTINGS`, plus the missing `CHAT` and `KEYPOPS`.
- No global event bus is introduced. Home uses one scheduled refresher (1 Hz while visible, paused when hidden) that polls revision counters and rebuilds only changed view models.

### 3.2 Simple / Analyst mode

A global `DisplayMode { SIMPLE, ANALYST }` held by a small `DisplayModeModel` (listener-based, persisted as `ui.mode` in `realmShark.properties`, default `SIMPLE`). A segmented switch sits in the page header, and `Ctrl+Shift+A` toggles it.

| Surface | Simple | Analyst |
|---|---|---|
| Values | Clean values, "—" for unknown, "≈" for estimates | Same, plus inline provenance lines |
| Card Evidence disclosure | Collapsed | Expanded by default |
| IDs / UUIDs / revisions | Hidden | Shown |
| Table views of glance screens (Runs table, Quests table…) | "Table view" is an item in the ⋯ menu | Feed/Table toggle shown in the toolbar; choice remembered per view |
| Diagnostic sub-tabs (Snapshot evidence, Ability use, packet issues…) | Hidden | Visible |
| Exports, saved views, column presets | In the ⋯ overflow menu | In the ⋯ overflow menu |

The mode never changes query semantics, only what is displayed.

### 3.3 Module structure

New package `tomato.gui.kit` (design-system components, no domain knowledge) and `tomato.gui.glance` (glance screens and view models). Each glance screen gets a focused file set:

```text
tomato/gui/kit/        Tokens, Type, Space, Card, StatTile, Chip, Badge, SectionHeader, Collapsible,
                       FilterBar, OverflowMenu, KitButton, KitTextField, SegmentedControl, CustomizableTabs,
                       Sprites, ItemSlot, PipMeter, StatBar, Sparkline, EmptyState, DisplayValue, EvidenceNote,
                       ColumnKind, KitTables, Motion
tomato/gui/glance/home/        HomePage, HomeModel(+Builder), HeroCard, NowCard, TodayTiles, RecentRunsCard, QuestsCard
tomato/gui/glance/character/   CharacterGallery, CharacterCard, CharacterSheet, SheetTabs…, ExaltGrid, PetGallery
tomato/gui/glance/runs/        RunFeed, RunCard(renderer), RunRecapPage, DungeonCards, LiveMeterPage
tomato/gui/glance/quests/      QuestBoard, QuestCard(renderer), QuestExpiry
tomato/gui/glance/loot/        LootHighlights
```

Existing analyst panels stay where they are and adopt kit components.

## 4. Information architecture

### 4.1 Sidebar

```text
⌂ Home                 (new; absorbs My Info)
☺ Characters           Roster gallery · Exalts · Pets
⚔ Runs & DPS           Feed · Dungeons · Live meter · Recordings
◆ Loot                 Highlights · Explore
✓ Quests               Board · Planner
✉ Chat
──────────
▸ Advanced (5)         Party · Timeline · Key-pops · Logging · Bridge review
──────────
⚙ Settings             Notifications (sounds) · General · Appearance · Loot filters · Chat · About
```

- **Advanced** is collapsed by default; its open state persists. Right-click any Advanced item → **Pin to top** (moves it into the core list). Core items can be hidden or reordered (right-click → Move up / Move down / Hide; drag-reorder with a drag handle). Persisted as `ui.nav.order`, `ui.nav.hidden`, `ui.nav.pinned`.
- Compact mode (< 1000 px) keeps today's icon rail and hamburger popup, now grouped the same way.
- Shortcuts stay bound to destinations, not positions, so muscle memory survives reordering: the existing `Alt+1…9`, `Alt+0`, `Alt+R`, `Alt+T`, `Alt+B` keep their pages, `Alt+N` and `Alt+,` open Settings, `Alt+H` opens Home (P2), `Alt+M` opens the compact menu and `Alt+Left` goes Back.
- Internally, pages keep their current numeric indices through P5 (`select(int)`, `pageOf`, `ShellNavigator` are unchanged); a `NavEntry` gives each page a stable string ID, title, group and icon, and the sidebar renders entries in the user's order. New pages are appended (Home becomes page 14). P6, which removes the retired pages, replaces the numeric API with the IDs. This keeps the ~40 tests that address pages by number valid until then.

### 4.2 Old → new mapping

| Today | New home | Notes |
|---|---|---|
| My Info | Home hero + Character sheet **Build** tab | Weapon DPS / MP-sec estimates as tiles; full per-projectile table in Analyst |
| Characters › Roster | Characters › gallery + sheet | Table available as Table view |
| Characters › Exalts | Characters › Exalts grid | |
| Characters › Pets | Characters › Pets gallery | Feeding calculator in a drawer |
| Statistics › Fame graph / table | Home "Today" tile + Character sheet **Fame** tab | Session table in Analyst |
| Statistics › Loot | Loot | |
| Statistics › Dungeon stats, session comparison | Runs & DPS › Dungeons | Session comparison / cohorts in Analyst |
| DPS Logger | Runs & DPS › Live meter, Recordings, Run recap › Damage | Legacy text/icon modes in Analyst |
| Runs | Runs & DPS › Feed + Run recap | Archive table = Table view |
| Inspect › Current area | Advanced › Party | |
| Inspect › Runs | Run recap › Players | |
| Inspect › Ability use | Party › Ability use (Analyst tab) | |
| Timeline | Advanced › Timeline; Run recap › Timeline section | |
| Key-pops | Advanced › Key-pops; recent pops line on Home Now card | |
| Logging | Advanced › Logging | |
| Bridge Review | Advanced › Bridge review | |
| Notifications | Settings › Notifications | Recent decisions stays there |
| Edit / File / Info menus | Unchanged entries; each also reachable from Settings | Settings search (UX-08) moves into Settings |

### 4.3 Page chrome

- Header: title (18 px/500), optional breadcrumb (`Characters › Sharkbait`), right-aligned context actions, capture pill (`● Capturing · Lost Halls` / `○ Capture off`), mode switch. No subtitle line on desktop widths (moves to the title tooltip).
- The setup row ("Choose assets… / Retry assets / Browse saved history") appears only when action is needed, as a single-line banner with one primary action. Preview mode shows a slim "Preview" chip in the header instead of a full banner.
- Footer keeps capture status, packet counters (Analyst) and the persistent capture-failure message.

### 4.4 Customizable sub-tabs

`CustomizableTabs` wraps `JTabbedPane` and gives every tab a stable string ID (the title can change, the ID cannot).

- Right-click a tab: Move left, Move right, Hide, Show hidden ▸ (list), Reset order. Drag a tab to reorder.
- The last visible tab cannot be hidden. Tabs added in later versions are appended at the end, visible.
- Analyst-only tabs carry a flag and are skipped in Simple mode without changing the saved order.
- Persisted as `ui.tabs.<groupId>` = ordered IDs + hidden set. Selected tab persistence already exists per module and keeps working through the ID.

## 5. Design system ("Violet 2")

### 5.1 Tokens

Palette values stay as in `VioletTheme` / `ContentStyle` today. `Tokens` exposes them by role so components never hard-code hex:

| Role | Dark | Use |
|---|---|---|
| `canvas` | `#131120` | Page background |
| `nav` | `#0E0C18` | Sidebar, title bar |
| `surface` | `#181627` | Cards, tables |
| `surfaceAlt` | `#1C1A2D` | Striped rows, inventory slots |
| `raised` | `#201D33` | Inputs, sprite wells, popups |
| `control` / `controlHover` / `controlPressed` | `#252139` / `#322C4C` / `#1C1930` | Secondary buttons |
| `borderSubtle` / `border` | `#252236` / `#38334F` | Hairlines / control borders |
| `accent` / `accentBright` / `accentWash` / `selection` | `#AD8CFF` / `#C4ADFF` / `#2A2142` / `#3B2E5E` | Focus, links, selected |
| `text` / `textMuted` / `textHeader` / `textDisabled` | `#E9E6F7` / `#A9A4C2` / `#B7B1D0` / `#626071` | |
| `good` / `warn` / `bad` / `info` | mint `#86DBBA` / amber `#EBC384` / rose `#EEA6BA` / blue `#90C8F8` | Status only |
| `primary` / `primaryHover` / `primaryPressed` | `#7041BD` / `#8052CD` / `#6036A5` | The one primary button per view |

A **Violet Light** variant defines the same roles (existing light values in `ContentStyle.color`). An **Increase contrast** setting strengthens `border`, `textMuted` and focus ring for users of the retired high-contrast themes.

Game-meaning colors are a separate small map (`Tokens.bag(LootBags)`, `Tokens.tier(ItemTier)`) derived from bag names. They are only used on sprite borders, bag chips and loot strips, never for status.

### 5.2 Type and spacing

- Font stays Segoe UI (body 13 px default, user-scalable via Edit › Font). The scale is in **em of the body size** so everything grows with the font setting: caption (the existing metadata role, 12/13), body 1.0, emphasis 1.0/500, title 1.15/500, page title 1.4/500, metric 1.35/500. Only two weights: regular and bold.
- Spacing on a 4 px grid: 4, 8, 12, 16, 24. Card padding 12; gap between cards 10; section gap 16.
- Radius: controls 6, cards 10, chips 4.

### 5.3 Components

| Component | Behavior |
|---|---|
| `Card` | Surface panel, 1 px subtle border, radius 10. Optional header (`SectionHeader`), optional ⓘ Evidence disclosure, optional footer link. Whole-card click target when it drills down (keyboard: focusable, Enter). |
| `StatTile` | Label (caption, muted) · metric value · optional sub-line · optional sparkline. Accepts a `DisplayValue`. Replaces `StatsUi.metrics`, `KeyPopDashboard` and `MyInfoGUI` ad-hoc tiles. |
| `Chip` / `Badge` | Tinted pill for statuses and filters. Filter chips have an × remove affordance and an accessible name ("Remove filter: Completed"). |
| `SectionHeader` | Title + optional count + right-aligned actions. |
| `Collapsible` | Header row with chevron; content shown/hidden; state persisted by ID. ≤ 100 ms height transition when motion is enabled (§5.8). |
| `FilterBar` | See §5.6. |
| `OverflowMenu` | ⋯ icon button that holds secondary actions (exports, saved views, columns, copy). Replaces walls of buttons. |
| `KitButton` | Variants: **Primary** (filled, one per view), **Secondary** (default), **Ghost** (text-only, accent), **Danger** (rose outline), **Icon** (square, `LineIcon`, tooltip required). Height 28, radius 6, 2 px accent focus ring, instant hover/press colors. |
| `KitTextField` | Optional leading icon, placeholder, clear (×) button, error state (rose border + helper line below), 28 px high. Search fields submit on Enter and show "Searching…" in their helper line while an archive query runs. |
| `SegmentedControl` | For small exclusive choices (Simple/Analyst, Feed/Table, group-by). |
| `Sprites` / `ItemSlot` | Facade over `ImageBuffer` + `IdToAsset`: `sprite(id, size)`, name, tier. Fallback glyph (slot icon or class initial) when assets are missing, never a blank. `ItemSlot` = sprite well with tier-colored border, tooltip with name/tier/enchant rarity, empty and unknown states distinct. |
| `PipMeter` | N pips (exalt tiers, maxed stats). |
| `StatBar` | Value / cap bar with maxed state (good color + check) and remaining label. |
| `Sparkline` | Tiny line/area chart for fame and damage-over-time; custom-painted, no library. |
| `EmptyState` | Title + one line + optional action. Used for zero-data and filtered-empty states. |
| `DisplayValue` | See §5.7. |

All components take their colors from `Tokens` roles and reapply them in `updateUI()`, so they follow Violet Dark/Light and Increase contrast. After P0 every supported theme is FlatLaf-based, so kit components may use FlatLaf style properties.

### 5.4 Icons

- UI icons: extend `LineIcon` with the new glyphs (home, swords, diamond, checklist, settings, filter, dots, chevron, pin, star, hourglass, refresh, skull, paw, flask, gift).
- Game art everywhere a game object appears: class/skin sprites, item icons, dungeon portal icons (`ParseDungeon.getPortalId`), bag icons, pet sprites. Cached per (id, size) in `Sprites`.

### 5.5 Tables and column kinds

A `ColumnKind` registry gives every table the same widths, alignment and formatting for the same kind of data. Widths are in em of the body font so they scale:

| Kind | Width | Alignment / format |
|---|---|---|
| `TIME_RELATIVE` | 7 em | "12 min ago"; absolute time in tooltip. Default for Simple |
| `DATE_TIME` | 11 em | `yyyy-MM-dd HH:mm:ss`; default for Analyst |
| `DURATION` | 6 em | `9m 51s` |
| `DUNGEON` | 13 em | 16 px portal sprite + name |
| `PLAYER` | 11 em | Name, ellipsis |
| `CLASS` | 8 em | 16 px class sprite + name |
| `ITEM` | 15 em | 16 px sprite + name, tier chip |
| `COUNT` | 5 em | Right, thousands separators |
| `NUMBER` | 6.5 em | Right, compact (`41.2k`) with exact value in tooltip |
| `PERCENT` | 5 em | Right, one decimal |
| `STATUS` | 9 em | Badge renderer |
| `ID` | 8 em | Monospace, Analyst only |
| `TEXT` | 20 em | Preferred width; fills the remainder only where the table already auto-resizes |

- `KitTables.apply(table, kinds…)` sets widths, renderers, header alignment, and hides Analyst-only columns in Simple mode.
- `HistoryTables` defaults switch to column kinds; saved user layouts (`ViewState.Table`) still win when present. **Reset columns** returns to kind defaults.
- Ad-hoc tables (Logging, Key-pops, Character, Quest, Bridge, Party, Meter, Fame session) migrate to `KitTables.apply` so time/dungeon/player columns match app-wide.

### 5.6 Filter bar (replaces filter walls)

```text
[🔍 Search runs        ] [Filters · 2 ▾]  (Completed ×) (Last 7 days ×)  Clear        Scope: all sessions ▾  [⋯]
└─ Filters drawer (collapsed by default; opens inline below the bar) ────────────────────────────────────────┘
```

- One row: whole-scope search, **Filters** toggle with the active-filter count, removable chips summarizing active facets, Clear, a **Scope** chip (session picker, live vs saved), and the ⋯ overflow (saved views, exports, columns, copy).
- The drawer hosts each module's **existing** facet controls unchanged. They keep publishing `binding.queryChanged(state.query.withFacets(f))`, so query semantics don't change.
- Each module supplies a `FacetDescriber` that turns its facets into chips (label + remove function). Drawer open state is remembered per view but defaults to closed.
- `ArchiveWorkspace` is refactored to expose its session, search, paging, views and export tools as slots that the `FilterBar` and ⋯ menu host. Paging moves to a table footer ("1–50 of 1,050 · ‹ ›").
- Live (non-archive) panels (`ActivityPanel` run filters, `StatisticsLiveState`, Chat, Party display filters) use the same `FilterBar`.

### 5.7 Showing values honestly without clutter

`DisplayValue` is an immutable value + state used by tiles, cards and table renderers:

| State | Shown as | Tooltip / Evidence |
|---|---|---|
| `KNOWN` | `1,240` | Source and time |
| `ZERO` | `0` | Source and time |
| `ESTIMATE` | `≈ 1,240` | How it was estimated |
| `MANUAL` | `1,240 ✎` | Who/when entered |
| `PARTIAL` | `1,240 ◐` | What is missing |
| `STALE` | `1,240` dimmed + clock glyph | Age and why stale |
| `UNKNOWN` | `—` dimmed | Why unknown (e.g., "Pet data arrives when you visit the Pet Yard") |

- Unknown reasons are rewritten as one short, actionable sentence (e.g., "Visit the Pet Yard with capture on to load pets") instead of "Not captured / unavailable".
- Each card's ⓘ Evidence disclosure holds today's full provenance text. Analyst mode expands it by default.

### 5.8 Motion

- Default: instant state changes (hover, press, selection, tab switch).
- Allowed only where it clearly helps orientation, capped at 100 ms, ease-out: filter drawer open/close, `Collapsible` sections, Advanced group expand. Implemented with one `Motion` helper (Swing `Timer`, no library).
- **Settings › Appearance › Reduce motion** turns it off. It is also off automatically when Windows "Animate controls and elements" is disabled (read via JNA, already a dependency).
- No animated loaders. Loading states use static placeholders.

## 6. Screens

### 6.1 Home (absorbs My Info)

Layout (desktop ≥ 1000 px): hero row; then Now | Today (2 columns); then Recent runs | Quests (1.5 : 1). Below 1000 px everything stacks in the same order.

- **Hero card (current character):** skin sprite (54 px), name, class, level, character fame; chips for maxed state (`7/8 maxed` amber, `8/8` mint), exalt completions for this class, pet rarity; 4 equipped `ItemSlot`s with tier labels; 8 compact `StatBar`s (base vs cap, boosted overlay when live) with one "DEF needs 5 potions" line; small tiles for Weapon DPS ≈ and MP/sec ≈ (estimates). Click → Character sheet. When not in game: last known character with a "Last seen 2 h ago" stale state; before any capture, an `EmptyState` ("Start capture and enter the game to see your character").
- **Now card:** portal sprite + area + elapsed; top-3 live meter rows with class colors, your row highlighted, "You're #2 of 8"; last key pop line ("Ann popped Lost Halls · 1 min ago", from `KeyPopHistory`, as its own fact, not tied to a run). Click → Live meter. Outside dungeons: current area and capture state only.
- **Today tiles:** Runs today (completed count), Fame (+gain and fame/hour with sparkline), Notable loot (UT/ST counts, white bags), Potions. A segmented toggle switches between **Today** (local calendar day) and **This session**. Built from `ArchiveQuery.Bounds` over `runs`, `loot`, `fame` + the live session.
- **Recent runs:** last 5 dungeon runs as compact rows (portal, name, outcome badge, time ago, your DPS when a linked recording exists, loot icons). Click → Run recap.
- **Quests card:** pinned quests expiring soonest (countdown when available), counts (pinned / repeatable / done), list age with stale state. Click → Quest board.

My Info's remaining content (per-projectile damage, recovery, dust, estimate scenarios, recorded-DPS picker) moves to Character sheet › **Build** (§6.2).

P2 delivery notes (user decisions, 2026-09-26):
- **Build page.** Until the character sheet exists (P3), the My Info page stays as page 6, retitled **Build**. It sits in an unlisted navigation group: reachable from the hero's Build action, Settings search, Alt+7 and its route, but never listed in the sidebar or compact menu.
- **Pet rarity chip.** Arrives with pet data in P3.
- **Quest countdown.** Arrives once the expiration format is confirmed (P4, O1).
- **Recent runs DPS.** Appears only for recordings exactly linked during the current app run, until the `encounters` module (P5).
- **Account line.** The hero shows the saved account fame, gold and rank stars as one muted line.

### 6.2 Characters

Top segmented control: **Roster · Exalts · Pets**.

**Roster gallery**
- Responsive grid of `CharacterCard`s (skin sprite 34 px, class, level, fame, 8-pip maxed meter, seasonal chip, last seen, "Playing now" marker). Dead characters are grouped in a collapsed **Graveyard** section. Rendered as a `JList` with `HORIZONTAL_WRAP` and a painted renderer so hundreds of characters stay fast.
- Filter bar: search, Filters drawer (existing roster filters: life need, stat coverage, maxed range, snapshot age, account, season), sort (last played, fame, class, maxed).
- Table view (existing roster table) via the Feed/Table toggle.

**Character sheet** (header: large sprite, name, class, level, fame, maxed, last seen; breadcrumb back to gallery). Customizable tabs:

| Tab | Content |
|---|---|
| Overview | Stat bars (base / cap, potions remaining), equipped gear, class exalt summary (top 3 stats + "N more"), pet card, death summary if dead |
| Gear | Equipped (4 large slots with enchant rarity dots), inventory (8), backpack (16) as a sprite grid; unknown vs empty distinct; item tooltip |
| Exalts | This class's 8 stats as `PipMeter` (5 tiers) + completions + "N to next tier"; live stat bonus when this class is being played (§8.3); where to earn each stat (from `exaltationConfig.xml`) |
| Pet | Pet sprite, rarity, family, three abilities with level bars and max; feeding estimate in a drawer |
| Fame | Fame over time for this character (existing `GraphPanel`, restyled) + fame/hour tiles |
| Build | Weapon DPS / MP-sec estimate tiles with scenario selector; per-projectile damage and recovery table (Analyst shows full rows); recorded DPS picker; dust counts |
| Goals | Existing goals, restyled as cards with progress |
| Notes | Existing notes |
| Snapshot evidence | Analyst only |
| Death annotation | Shown when the character is marked dead |

**Exalts grid:** one tile per observed class (sprite, total completions, lowest tier as a pip meter, eight small tier cells as a heat strip). Header tiles: account loot boost (`RealmCharacter.exaltLootBonus`), classes fully exalted. Click a class → its Exalts detail (same as the sheet tab, account-scoped).

**Pets gallery:** cards for known pets (sprite, name, rarity chip, three ability bars). Unknown → one actionable empty state.

### 6.3 Runs & DPS

Sub-tabs: **Feed · Dungeons · Live meter · Recordings** (customizable).

**Feed**
- Day-grouped (`Today · 5 runs · 4 completed · 1 h 12 m`) `RunCard`s rendered by a painted `JList` renderer, loaded 50 at a time with "Load more".
- Card: portal sprite (40 px), dungeon, outcome badge (Completed mint / Left neutral / In progress accent), time, duration, party size; your damage share bar + rank + DPS (only with a linked recording); loot strip (bag-colored item sprites + "1 UT · 2 potions"); fame gained (from `FameSample` grouped by exact visit); deaths (only from the linked recording); exalt increase when recorded. The left edge color follows the outcome.
- Filter bar with the existing run facets (outcome, evidence, capture issues, timing gaps, duration, dates) in the drawer. Table view = today's archive table.

**Run recap** (replaces `RunWorkbench` text; route `RUN_RECAP` with `VisitRef`)
- Header: portal sprite, dungeon, outcome, entered, duration, party size, character used (from fame samples, exact visit).
- Tiles: your DPS + rank, damage share, deaths, fame, loot count, exalt increase.
- Sections (collapsible, order persisted): **Damage** (meter table with class sprites and bars; damage-over-time chart; damage by source per player in an expandable row), **Loot** (bags and items as sprites), **Players** (inspected builds grid, from today's Inspect runs), **Resources** (HP/MP chart, collapsed), **Timeline** (events list, collapsed), **Evidence** (Analyst).
- Missing link → section shows a one-line reason ("No combat recording is linked to this run") instead of hiding silently.

**Dungeons:** one card per dungeon (portal sprite, visits, completion rate, average duration, loot per completed run, best personal DPS) from dungeon totals + runs archive. Session comparison and A/B cohorts under Analyst.

**Live meter:** today's `MeterDpsGUI`, restyled: class-colored bars, rank numbers, your row highlighted, enemy list on the left as cards (boss badge, HP), details in a drawer. Legacy text and icon modes are Analyst options.

**Recordings:** the encounter library (auto-saved and imported `.dps`), each linked to its run when linked.

### 6.4 Loot

Sub-tabs: **Highlights · Explore**.

- **Highlights:** notable drops grid (UT, ST, potions, enchanted) with bag-colored sprites and dungeon + time; tiles for today/session (UT, ST, potions by type, white bags); per-dungeon mini strip. Replaces the Statistics › Loot "Live log".
- **Explore:** one view selector (dropdown or segmented control) replaces the 9–12 tabs: All items, Potions, Whites, By bag, By dungeon, UTs, STs, Tiered, Recent. Analyst adds Occurrences, Dungeon loot profile, Session comparison, A/B cohorts, Enemy hit events, Loot by source. Filter bar with the existing loot facets in the drawer.

### 6.5 Quests

Sub-tabs: **Board · Planner**.

**Board**
- Summary line: `14 quests · 3 pinned · 2 expiring today · captured 14 min ago` (stale state when the list no longer matches the capture).
- Group by: chest tier (Mighty / Epic / Standard / Beginner, from the existing name classification), user type label, or none. Pinned first toggle.
- `QuestCard`: name, pin star, badges (**↻ Repeatable**, **One-time**, **✓ Done**), the user's category label chip (existing "Name types…"), expiry countdown chip; **You get** (large reward sprites; **Pick 1 of N** when `itemOfChoice`); **Bring** (small requirement sprites with × counts). Click → detail drawer (description, full lists with names, raw expiration in Analyst).
- Category labeling ("Name types…") lives in the Filters drawer.

**Expiry countdown:** `QuestExpiry.parse(raw)` supports formats confirmed from real captures (see open item O1). If the raw value doesn't parse, the chip is omitted (Simple) and the raw value is shown in Analyst. Countdown text updates once a minute; under 6 hours it uses the warn color.

**Planner:** today's saved plans, restyled as cards with requirement progress bars (reserved / available / missing) and the manual stock editor in a drawer. Semantics unchanged.

### 6.6 Chat

Restyle only: channel tabs become segmented pills with counts; FilterBar (search, Filters drawer with player/starred/ignored/dates, ⋯ with history library and exports). Message rendering unchanged.

### 6.7 Advanced pages and Settings

- **Party** (Inspect current area): roster rows get class sprites, gear `ItemSlot`s and requirement badges; the display filters move into a FilterBar drawer.
- **Timeline, Key-pops, Logging, Bridge review:** adopt FilterBar, ⋯ overflow, column kinds, kit buttons and cards. No structural redesign.
- **Settings:** left list of sections (Notifications, General, Appearance, Loot filters, Chat, About) + existing settings search. Appearance holds theme (Violet Dark / Light), Increase contrast, font size, Reduce motion, default mode. Notifications keeps today's content restyled.

## 7. States, errors and empty data

- Every glance card has explicit loading (static placeholder), empty (`EmptyState` with the next action) and unavailable (one-line reason) states. No blank panels.
- Errors keep today's persistent, non-silent behavior (capture failures, save failures with Retry). They render as a warn/bad banner inside the affected card or page, not modal dialogs.
- Preview mode renders all glance screens from empty sources with correct empty states.

## 8. Source changes

### 8.1 Java 17 target

- `compileJava` → `options.release = 17`. Update `scripts/Test-WindowsBundle.ps1` (class-header check), `docs/WINDOWS-BUNDLE.md`, README, `AGENTS.md` and memory notes that say Java 8.
- Allowed afterwards: switch expressions, `var`, text blocks, `List.of`, and records for new in-memory view models. The project uses Gson 2.9.1, which cannot deserialize records (support arrived in 2.10), so persisted types (journal, encounter summaries, preferences) stay plain classes.

### 8.2 Retire the Darklaf themes

- Remove `darklaf-core`. Edit › Theme offers **Violet Dark** and **Violet Light**, plus the Increase contrast toggle.
- Migration of the saved `theme` property: Darcula, HighContrast Dark, Solarized Dark → Violet Dark; IntelliJ, Solarized Light → Violet Light; HighContrast Light → Violet Light + Increase contrast. HighContrast Dark also enables Increase contrast.
- Only `TomatoMenuBar` and `TomatoGUI` reference Darklaf today; theme-switch tests are updated accordingly.

### 8.3 Save more character data (journal version 4)

All new fields are optional. Old records load unchanged and render as partial. No backfill guesses. Version 4 is introduced in P2 with the `AccountRecord` live fields; P3 adds the remaining optional fields to the same version (Gson leaves absent fields null, so no further bump is needed).

| Record | New field | Source |
|---|---|---|
| `CharacterRecord` | `pet` (name, type, rarity, family, skin, 3 abilities {type, level, points}, observedAt) | char/list `<Pet>` and Pet Yard updates for the equipped pet |
| `CharacterRecord` | `dungeonCompletions` (map + observedAt) | char/list `PCStats` (already parsed) |
| `CharacterRecord` | `exp`, `hasBackpack` | char/list / live stats |
| `AccountRecord` | `exaltSeenByClass` (per-class timestamps) | exalt updates |
| `AccountRecord` | `liveExaltBonus` (classId → int[8] + observedAt) | live stats 105–112 (`EXALTED_*`) while that class is played |
| `AccountRecord` | `accountFame`, `gold`, `rankStars` (+ observedAt) | live stats 39 / 35 / 30 |
| `AccountRecord` | `vaultPotions` (+ observedAt) | `VaultData` from `VaultContentPacket` |

Exalt stat bonuses are displayed only from `liveExaltBonus` (observed), never computed from a tier rule.

### 8.4 Auto-save combat per run

- When a DPS encounter closes (map change or capture stop), persist an **encounter summary** to a new SessionStore module `encounters`, checkpointed by `recordingId`, carrying its `EncounterContext` (VisitRef + local object id) when present.
- Summary contents: map, start, elapsed, per-enemy (type, max HP, boss flag), per-player metrics (class, damage, hits, max hit, taken, damage by source), death notifications, and **damage-over-time buckets** (1 s buckets for the local player and the top 12 contributors).
- Full hit lists stay in memory as today and are still exportable as `.dps`. **Settings › General › Keep full combat detail** (default off) additionally stores the full encounter file per run.
- Retention setting (default: keep all summaries; full detail pruned after 30 days). Measure storage on the large synthetic history before choosing final defaults.
- Run cards, the recap and Home use `encounters` joined to `runs` by exact VisitRef only. Unlinked encounters appear in Recordings.

### 8.5 Queries without schema changes

- Fame gained per run: group `FameSample` by exact visit, last − first.
- Loot per run: existing `LootQuery.Facets.visitSession/visitId` + `HistoricalStatistics.Profile` counting.
- Today/session aggregates: `ArchiveQuery.Bounds` over `runs`, `loot`, `fame`, `encounters`.

## 9. Performance

- Glance lists (run feed, character gallery, quest board, loot highlights) are `JList`s with painted renderers. No per-row component trees.
- View models are built off the EDT; EDT work is limited to swapping models and repainting changed cards.
- `Sprites` caches scaled images per (id, size). Asset reloads invalidate through the existing `liveOutlinedIcon` path.
- Archive reads stay asynchronous and cancelable (existing `Cancel read` semantics, now in ⋯).
- Budgets S8/S9 (§1) are measured on the existing large synthetic history fixtures.

## 10. Accessibility

- Every card, chip remove button and icon button has an accessible name. Cards that drill down are focusable and activate on Enter/Space.
- Keyboard: arrow keys move within galleries and feeds, Tab moves between regions, Esc closes drawers.
- Contrast meets WCAG AA in both variants; Increase contrast raises borders and muted text further.
- Layout works at 680 × 520 and fonts 13–24, as today.

## 11. Testing and validation

Per `AGENTS.md` (2026-09-26 policy): focused local checks, relevant build and launch smoke checks, no mandatory CI or full UI matrix on every change.

- **Unit:** `DisplayValue` formatting per state; `ColumnKind` widths scale with font; `CustomizableTabs` order/hide persistence and version-append rules; `FacetDescriber` chips round-trip; `QuestExpiry` parsing; journal v3 → v4 load; encounter summary serialization and exact-link joins; theme migration mapping.
- **UI:** new synthetic-fixture evidence tests per glance screen at 1240 × 800 and 680 × 520, fonts 13 and 18, Simple and Analyst: populated, empty, unavailable, stale.
- **Honesty regressions:** keep existing unknown-vs-zero, estimate, manual and exact-link tests passing through the new renderers.
- **Brittle test migration:** page-number tests stay valid through P5 (see §4.1). P1b updates the tests that assert sidebar/popup order and the archive toolbar layout (`ArchiveNativeSupport` and the few tests that click exports, saved views or History library, which move into the ⋯ menu). P6 migrates the remaining page-number tests to destination IDs when pages are removed.
- **Performance:** a small timing harness for S8/S9 on the large synthetic history.

## 12. Delivery phases

Each phase is its own branch and PR from verified `main`, usable on its own. Old views stay reachable until their replacement lands.

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0 Platform** | Java 17 target; retire Darklaf; Violet Light + Increase contrast; theme migration | Build, tests and bundle scripts pass on Java 17; saved legacy themes migrate |
| **P1 Kit + shell** (three PRs: P1a kit; P1b sidebar, header, Settings and shell tests; P1c filter drawers, customizable tabs and column kinds across pages) | `tomato.gui.kit` components; tokens; `DisplayValue`; `ColumnKind` + `KitTables`; `FilterBar` + `ArchiveWorkspace` slot refactor; `OverflowMenu`; `CustomizableTabs`; Simple/Analyst mode; new sidebar with Advanced group and Settings page; header/setup banner; brittle-test migration. Existing pages adopt FilterBar, overflow, column kinds and kit buttons mechanically | S6 met on Runs, Timeline, Resources, Party, Loot, Chat, Key-pops, Characters and Quests (Logging, Bridge review, the encounter library, the DPS meter and the Statistics sub-pages follow in P5/P6); S7 met; every existing page reachable; filters collapsed by default; tab reorder/hide persists |
| **P2 Home** | Home page + view models; hero, Now, Today, Recent runs, Quests cards; My Info content moved into a temporary Build panel reachable from the hero; journal v4 live fields (live exalt bonus, account fame/gold/stars) | S1, S9 met; My Info removed from sidebar |
| **P3 Characters** | Gallery, sheet with tabs, Exalts grid, Pets gallery; journal v4 persisted fields (pet, completions, vault potions) | S2, S5 met |
| **P4 Quests** | Board, cards, expiry countdown (after O1), Planner restyle | S3 met |
| **P5 Runs & DPS** | `encounters` auto-save; feed, recap, Dungeons, Live meter, Recordings; Statistics and DPS Logger removed from the sidebar | S4, S8 met; storage measured |
| **P6 Loot + cleanup** | Loot Highlights/Explore; Advanced pages restyle; remove dead panels (Statistics shell, old My Info) and unused styles; docs update | No orphaned pages or styles; docs describe the new IA |

## 13. Open items and risks

| ID | Item | Plan |
|---|---|---|
| O1 | Real format of `QuestData.expiration` is unknown (tests use placeholders) | In P4, record sanitized expiration samples from a live Daily Quest Room visit; implement only confirmed formats |
| O2 | Exalt bonus per tier isn't a known rule in code | Show only observed live bonuses (§8.3) |
| O3 | Auto-save storage growth | Measure in P5 on the large fixture; adjust bucket size, top-N and retention defaults |
| O4 | Test churn from renaming/reordering the shell | Migrate to stable IDs once in P1 before any page redesign |
| O5 | Removing Darklaf removes the legacy high-contrast themes | Increase contrast setting; migration maps high-contrast users to it |
| O6 | Tab customization vs Simple-mode hidden tabs | Saved order includes Analyst tabs; Simple mode skips them without rewriting the order |

## 14. Out of scope

Automatic quest readiness from inventory or loot; key-popper attribution on runs; universal player profiles by name; drop-chance predictions; any change to capture, decoding or damage math beyond persisting encounter summaries.
