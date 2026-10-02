# Loot Explore pictures — P4 (Dungeons view, routed Pictures navigation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This repo's workflow:** the implementer (Codex via Orchestra) edits files only. The coordinator (Claude) runs every Gradle
> command, makes every commit, launches the app and opens the PR.

**Goal:** Pictures gets its third way in, **Dungeons**, and every move between Pictures' levels goes through the navigator, so the mouse Back/Forward buttons (PR #51) step through them one at a time.
- **Dungeons** is a wall of portal tiles. Each shows runs, white bags and UTs with their per-run rates, and the dungeon's top 3 drops. Tiles can be sorted and searched.
- **Clicking a tile** opens Runs filtered to that dungeon: the strip shows only its runs, and the newest opens with "This dungeon so far" beside it.
- **The dungeon panel** gains **All items from <dungeon>**, which opens Collection filtered to that dungeon.

**Architecture:**
- **`AtlasModel` (pure)** joins Runs › Dungeons' run counts (`DungeonsSource` → `DungeonCardModel`) with the saved-loot statistics P3b built (`DungeonStats` over `LootCatalog` bags).
- **`DungeonsLevel`** draws it as a `TileList` of painted tiles.
- **`ExplorePictures`:**
  - gains `Level.DUNGEONS`, a three-way entry switch, and a dungeon filter shared by Runs (`RunFeedView.showDungeon`) and Collection (new `CollectionLevel.filterDungeon`);
  - gets one navigation hook, `onNavigate(Focus)`, which the entry switch, breadcrumb links, dungeon tiles and the "All items from" link all call.
- **`LootExplorePage`** routes that hook as a LOOT route with an `ExplorePictures.Focus` payload, so each move is one Back/Forward step. Run-card picks in the strip stay unrouted: they're master-detail selection, as PR #51 noted.

**Tech Stack:** Java 17 (`--release 17`), Swing + FlatLaf, the project UI kit, JUnit 4, Gradle 7.6.4.

**Spec:** `docs/superpowers/specs/2026-10-01-loot-explore-pictures-design.md` (P4 = "Dungeons level"). P1 #46, P2 #48 and P3 (+ the strip layout) #49 are merged; mouse Back/Forward #51 is merged.

### Decisions and deviations (approved with this plan)

- **A dungeon tile opens Runs filtered to that dungeon,** not a separate split view. The strip lists only that dungeon's runs, newest first. Its "Dungeon: X" chip clears the filter, and "This dungeon so far" is beside the haul. Its collection is one click away, through **All items from X** in that panel. This keeps the spec's "that dungeon's runs and its collection" in the strip layout the user chose.
- **Tile rates are per run with loot:** distinct drop-time runs among the dungeon's bags, the same denominator the dungeon panel shows. A tile also shows the dungeon's total runs from Runs › Dungeons (all saved visits), and reads "—" when only loot knows the dungeon.
- **Routed moves** (run picks unrouted: user-confirmed 2026-10-01): Pictures' level and dungeon moves go through the navigator. That covers the entry switch, breadcrumb links, tiles and "All items from". Run-card picks don't, so Back after picking three runs leaves Pictures rather than stepping through each pick.
- **Sorts:** Most runs (default), Whites per run, UTs per run, Recent. A search box filters by dungeon name.

## Global Constraints

- Java 17; no new dependencies. Swing on the EDT; reads off the EDT on one daemon worker per level with a generation guard, as `CollectionLevel` does.
- Copy is sentence case, `" · "` separators, `"×N"` counts, and singular/plural via a count helper ("1 run", "2 runs"). Unknown values read "—" with a tooltip saying why; nothing is guessed.
- New names use the `loot-dungeons-*` prefix (the level) or `loot-dungeon-*` (the side panel, as today).
- Fixtures are synthetic: `RunFixtures`, `LootTestDrops`, hand-built `LootFacts.Bag`s and `DungeonCardModel`s.
- Keyboard tests invoke bound actions or queued AWT events, never `java.awt.Robot`.
- Gradle (from the worktree root, Git Bash):
  ```bash
  export JAVA_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/gradle-home"; ./gradlew.bat test --offline -PrealmSharkBuildDir=build-loot-p4 --tests <TEST>
  ```
  Written below as `GRADLE --tests <TEST>`. **The coordinator also compiles every test source** (`compileTestJava`) before review. The P3 merge broke `WaveThreeJourneyTest` because only focused suites were compiled.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A dungeon that only loot knows** (no saved run record): its tile reads "—" runs and still opens its filtered runs and collection without errors. Unrecognized or unknown areas are never tiles. Covered by Tasks 1 and 2.
2. **Back/Forward across Pictures moves:**
   1. Runs → Dungeons → a tile → All items from → an item.
   2. Back walks back one step at a time: the item, then Collection filtered, then the filtered Runs, then Dungeons, then Runs.
   3. Forward walks forward again, restoring the dungeon filter on Runs and Collection each time.
   Covered by Tasks 4 and 5.
3. **Leaving a dungeon:** the Runs entry, the "Runs" crumb, or clearing the "Dungeon: X" chip shows all runs again, and the breadcrumb and state follow. Covered by Task 4.
4. **The filtered strip after a dungeon switch:** the newest run of the new dungeon opens. It never keeps a run from another dungeon that the filtered strip doesn't list. Covered by Tasks 3 and 4.
5. **Every test source compiles,** including shell journey tests that reach into `LootExplorePage.pictures()`. Covered by Task 6.

---

### Task 1: `AtlasModel` — the dungeon wall's data (pure)

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/AtlasModel.java`
- Create: `src/test/java/tomato/gui/loot/explore/AtlasModelTest.java`

**Interfaces:**
- Consumes:
  - `DungeonCardModel`: public record (`canonical()`, `portalId()`, `visits()`, `lastVisit()`)
  - `DungeonStats.of(List<LootFacts.Bag>, String)` (`runs()`, `whites()`, `uts()`, `mostDropped()`)
  - `LootFacts.Bag.dungeon()` (canonical), `LootFacts.UNKNOWN_AREA`, `LootFacts.UNRECOGNIZED`, `Portals.spriteId(String)`
- Produces:
  - `record AtlasModel(List<Tile> tiles)`
  - `enum AtlasModel.Sort { RUNS, WHITES, UTS, RECENT }`, each with a `label`
  - `record AtlasModel.Tile(String dungeon, int portalId, Integer runs, int lootRuns, int whites, int uts, List<DungeonStats.Item> top, long last)`, with `Double whitesPerRun()` and `Double utsPerRun()`
  - `static AtlasModel of(List<DungeonCardModel> cards, List<LootFacts.Bag> bags, Sort sort, String search)`

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/AtlasModelTest.java`:

```java
package tomato.gui.loot.explore;

import java.util.List;
import org.junit.Test;
import tomato.gui.runs.DungeonCardModel;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;

/** The dungeon wall: runs from Runs › Dungeons joined with saved loot, rates per run with loot, sorts, search, no unknown areas. */
public class AtlasModelTest {
    static DungeonCardModel card(String canonical, int visits, long last) {
        return new DungeonCardModel(canonical, canonical, 0, visits, 0, 0, 0, 0, 0, null, null, 0, null, 0, 0, null, null, null, null,
            null, null, null, null, last);
    }
    static LootFacts.Bag bag(String dungeon, String visit, long time, boolean white, LootFacts.Item... items) {
        return new LootFacts.Bag("s", time, white, white ? "White" : "Brown",
            visit == null ? null : new VisitRef("00000000-0000-4000-8000-00000000000" + visit.length(), visit), List.of(items), dungeon, "Synthetic boss");
    }

    @Test public void cardsAndLootJoinByCanonicalDungeon() {
        AtlasModel atlas = AtlasModel.of(List.of(card("Lost Halls", 10, 500), card("Snake Pit", 3, 100)),
            List.of(bag("Lost Halls", "a", 400, true, ut(1, null)), bag("Lost Halls", "b", 450, false, ut(1, null), potion(4)),
                bag("Sprite World", "c", 600, true, st(2)), bag(LootFacts.UNRECOGNIZED, "d", 700, true, ut(9, null)), bag(null, null, 800, true)),
            AtlasModel.Sort.RUNS, "");
        assertEquals(List.of("Lost Halls", "Snake Pit", "Sprite World"), atlas.tiles().stream().map(AtlasModel.Tile::dungeon).toList());
        AtlasModel.Tile halls = atlas.tiles().get(0);
        assertEquals(Integer.valueOf(10), halls.runs());
        assertEquals(2, halls.lootRuns());
        assertEquals(1, halls.whites());
        assertEquals(2, halls.uts());
        assertEquals(0.5, halls.whitesPerRun(), 1e-9);
        assertEquals(1.0, halls.utsPerRun(), 1e-9);
        assertEquals(List.of(1), halls.top().stream().map(DungeonStats.Item::id).toList());
        assertEquals(500, halls.last());
        AtlasModel.Tile sprite = atlas.tiles().get(2);
        assertNull("Only loot knows Sprite World", sprite.runs());
        assertNull("Snake Pit has no loot", atlas.tiles().get(1).whitesPerRun());
    }

    @Test public void sortsAndSearch() {
        List<DungeonCardModel> cards = List.of(card("Lost Halls", 10, 500), card("Snake Pit", 3, 900));
        List<LootFacts.Bag> bags = List.of(bag("Lost Halls", "a", 400, false, ut(1, null)), bag("Snake Pit", "b", 300, true));
        assertEquals("Snake Pit", AtlasModel.of(cards, bags, AtlasModel.Sort.WHITES, "").tiles().get(0).dungeon());
        assertEquals("Lost Halls", AtlasModel.of(cards, bags, AtlasModel.Sort.UTS, "").tiles().get(0).dungeon());
        assertEquals("Snake Pit", AtlasModel.of(cards, bags, AtlasModel.Sort.RECENT, "").tiles().get(0).dungeon());
        assertEquals(List.of("Snake Pit"), AtlasModel.of(cards, bags, AtlasModel.Sort.RUNS, " snake ").tiles().stream().map(AtlasModel.Tile::dungeon).toList());
    }
}
```

If the `DungeonCardModel` constructor's arity differs from the 24 components above, match the record exactly. `canonical`,
`displayName`, `portalId`, `visits` and `lastVisit` are the ones this test cares about.

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.AtlasModelTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement** `src/main/java/tomato/gui/loot/explore/AtlasModel.java`:

```java
package tomato.gui.loot.explore;

import java.util.*;
import tomato.gui.kit.Portals;
import tomato.gui.runs.DungeonCardModel;
import tomato.gui.stats.LootFacts;

/**
 * Explore's Dungeons wall: one tile per dungeon that a saved run (Runs › Dungeons) or a saved bag knows, by canonical name. A
 * tile has the dungeon's total runs (null when only loot knows it), its runs with loot (distinct drop-time runs among its bags),
 * white bags and UTs with their rates per run with loot (null without one), its top 3 drops ignoring rarity, and its latest
 * activity. Unknown and unrecognized areas are never tiles. Pure.
 */
public record AtlasModel(List<Tile> tiles) {
    public AtlasModel { tiles = List.copyOf(tiles); }

    public enum Sort {
        RUNS("Most runs"), WHITES("Whites per run"), UTS("UTs per run"), RECENT("Recent");
        public final String label;
        Sort(String label) { this.label = label; }
    }

    public record Tile(String dungeon, int portalId, Integer runs, int lootRuns, int whites, int uts, List<DungeonStats.Item> top, long last) {
        public Tile { top = List.copyOf(top); }
        public Double whitesPerRun() { return lootRuns == 0 ? null : (double) whites / lootRuns; }
        public Double utsPerRun() { return lootRuns == 0 ? null : (double) uts / lootRuns; }
    }

    public static AtlasModel of(List<DungeonCardModel> cards, List<LootFacts.Bag> bags, Sort sort, String search) {
        Map<String, DungeonCardModel> byName = new LinkedHashMap<>();
        for (DungeonCardModel card : cards) if (known(card.canonical())) byName.put(card.canonical(), card);
        Map<String, List<LootFacts.Bag>> loot = new LinkedHashMap<>();
        for (LootFacts.Bag bag : bags) if (known(bag.dungeon())) loot.computeIfAbsent(bag.dungeon(), name -> new ArrayList<>()).add(bag);
        Set<String> dungeons = new LinkedHashSet<>(byName.keySet());
        dungeons.addAll(loot.keySet());
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Tile> tiles = new ArrayList<>();
        for (String dungeon : dungeons) {
            if (!query.isEmpty() && !dungeon.toLowerCase(Locale.ROOT).contains(query)) continue;
            List<LootFacts.Bag> own = loot.getOrDefault(dungeon, List.of());
            DungeonStats stats = DungeonStats.of(own, dungeon);
            DungeonCardModel card = byName.get(dungeon);
            long last = card == null ? 0 : card.lastVisit();
            for (LootFacts.Bag bag : own) last = Math.max(last, bag.time());
            List<DungeonStats.Item> top = stats.mostDropped().size() > 3 ? stats.mostDropped().subList(0, 3) : stats.mostDropped();
            tiles.add(new Tile(dungeon, card != null ? card.portalId() : Portals.spriteId(dungeon), card == null ? null : card.visits(),
                stats.runs(), stats.whites(), stats.uts(), top, last));
        }
        Comparator<Tile> byName = Comparator.comparing(tile -> tile.dungeon().toLowerCase(Locale.ROOT));
        Comparator<Tile> order = switch (sort) {
            case RUNS -> Comparator.comparingInt((Tile tile) -> tile.runs() == null ? -1 : tile.runs()).reversed()
                .thenComparing(Comparator.comparingInt(Tile::lootRuns).reversed());
            case WHITES -> Comparator.comparing(Tile::whitesPerRun, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingInt(Tile::lootRuns).reversed());
            case UTS -> Comparator.comparing(Tile::utsPerRun, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingInt(Tile::lootRuns).reversed());
            case RECENT -> Comparator.comparingLong(Tile::last).reversed();
        };
        tiles.sort(order.thenComparing(byName));
        return new AtlasModel(tiles);
    }

    private static boolean known(String dungeon) {
        return dungeon != null && !dungeon.isBlank() && !LootFacts.UNKNOWN_AREA.equals(dungeon) && !LootFacts.UNRECOGNIZED.equals(dungeon)
            && !LootFacts.UNKNOWN.equals(dungeon);
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.explore.AtlasModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**: `git commit -m "Join dungeon runs and saved loot into Explore's dungeon wall"`

---

### Task 2: `DungeonsLevel` — the dungeon wall

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/DungeonTileRenderer.java`
- Create: `src/main/java/tomato/gui/loot/explore/DungeonsLevel.java`
- Create: `src/test/java/tomato/gui/loot/explore/DungeonsLevelTest.java`

**Interfaces:**
- Consumes:
  - from Task 1: `AtlasModel`
  - `DungeonsSource(SessionStore, ZoneId, LongSupplier).read(DungeonsQuery.all(), Cancellation).cards()`
  - `LootCatalog.Reader`, `TileList`, `SegmentedControl`, `Sprites`, `Portals`, `ContentStyle.page`, `CollectionLevel.daemon`, `RunsLevelMessages.message`
- Produces:
  - `public final class DungeonsLevel extends JPanel implements AutoCloseable`
  - `interface DungeonsLevel.Cards { List<DungeonCardModel> cards(Cancellation) throws IOException; }`
  - `static DungeonsLevel production(Supplier<SessionStore>, LootCatalog.Reader)`
  - package-private `DungeonsLevel(Cards, LootCatalog.Reader, Executor)`
  - `void reload()`, `void onOpenDungeon(Consumer<String> canonical)`, `AtlasModel model()`, `AtlasModel.Sort sort()`, `void close()`
  - package-private `JTextField search()`, `JTextArea status()`, `SegmentedControl sorter()`
  - Names: `loot-dungeons`, `loot-dungeons-search`, `loot-dungeons-sort` (buttons `-0`…`-3`), `loot-dungeons-summary`, `loot-dungeons-status`, `loot-dungeons-tiles` (a `TileList<AtlasModel.Tile>`)

**Behavior** (the implementer writes the code to this specification; follow `CollectionLevel` for structure, threading and states):
- **Layout:** a controls row (search "Search dungeons", sort switch Most runs | Whites per run | UTs per run | Recent, and the summary "N dungeons"), then the tiles in a `ContentStyle.page` scroll page.
- **Reload:** `reload()` reads the cards and the bags on the worker. The cards read is `DungeonsSource` in production, and without saved history it fails with `RunHauls.NOT_OPEN`. Results apply on the EDT only for the newest ticket.
- **Sort and search:** they re-render on the EDT without reading again.
- **States:**
  - while loading: "Loading dungeons…";
  - after a failure: "Dungeons could not be read: <message>";
  - empty: "Dungeons appear here once capture saves a run or a loot bag.";
  - no search match: "No dungeon name matches the search.".
- **`DungeonTileRenderer`** paints one tile, about 230 × 120 px, reusing tokens like `RunCardRenderer`:
  - the portal sprite (40 px) and the dungeon name;
  - "37 runs · 12 white bags · 8 UTs", using "—" runs when unknown and singular/plural counts;
  - "0.32 whites/run · 0.22 UTs/run", or "No runs with loot yet";
  - up to 3 top-drop sprites (24 px) with "×N";
  - a selection fill and a focus outline.
  
  Its accessible name says all of that in words.
- **Opening a tile:** Enter, Space or a double-click calls `onOpenDungeon(tile.dungeon())`.

- [ ] **Step 1: Write the failing test.** `DungeonsLevelTest` uses a fake `Cards`, a fake `LootCatalog.Reader` and `Runnable::run`. It covers:
  1. tiles in Most-runs order;
  2. clicking the "Whites per run" sort button reorders without another read (count reads in the fakes);
  3. search filters, and a no-match search shows the no-match status;
  4. Enter on a selected tile (through its bound action) reports its canonical name;
  5. an empty read and a failed read show their statuses;
  6. a slower older read never replaces a newer one (queued executor);
  7. the renderer's accessible name for a tile with unknown runs contains "—" and "No runs with loot yet".
- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.DungeonsLevelTest`
Expected: compilation FAIL.
- [ ] **Step 3: Implement** to the behavior above.
- [ ] **Step 4: Run it to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.explore.DungeonsLevelTest`
Expected: PASS.
- [ ] **Step 5: Commit**: `git commit -m "Add Explore's dungeon wall: portal tiles with runs, white bags, UTs and top drops"`

---

### Task 3: Dungeon filters for Collection and Runs, and "All items from"

**Files:**
- Modify: `src/main/java/tomato/gui/loot/explore/CollectionLevel.java`, which gains `filterDungeon(String)`, `dungeonFilter()`, `onFilterCleared(Runnable)`, and a removable chip
- Modify: `src/main/java/tomato/gui/loot/explore/DungeonPanel.java`, which gains the **All items from <dungeon>** link and `onOpenCollection(Consumer<String>)`
- Modify: `src/main/java/tomato/gui/loot/explore/RunsLevel.java`, which gains `clearRun()` so the next applied page opens its newest run
- Tests: `CollectionLevelTest`, `DungeonPanelTest`, `RunsLevelTest` (one test each)

**Behavior:**
- **`CollectionLevel.filterDungeon(String canonical)`:**
  - keeps only bags whose `dungeon()` equals `canonical`, then builds `CollectionModel.of` as before; null clears the filter;
  - shows a removable chip "Dungeon: <name>" (`Chip.removable`) named `loot-collection-dungeon` in the controls row;
  - removing the chip clears the filter and runs `onFilterCleared`;
  - the summary and the empty status follow the filtered bags ("No saved items from <name>.").
- **`DungeonPanel` link:** while a dungeon shows, a ghost link `loot-dungeon-collection`, "All items from <name>", calls `onOpenCollection(canonical)`. It is hidden while loading, unknown or empty.
- **`RunsLevel.clearRun()`:** forgets the selected and shown run (`selected = shown = null`, `pendingSelect = false`) and clears the strip selection, without reading. The next `loaded(...)` then opens the newest listed run.
  - It also must not keep a run that the filtered strip doesn't list: if the selected run isn't among the newly loaded cards after a dungeon filter, `loaded` treats it as cleared.
  - Implement this by having `ExplorePictures` call `clearRun()` before `feed().showDungeon(...)`.
- **Tests:**
  - `CollectionLevelTest`: the filter, the chip removal callback, and the filtered empty status.
  - `DungeonPanelTest`: the link reports the canonical name, and is hidden when unknown.
  - `RunsLevelTest`: after `clearRun()`, `loaded(cards)` opens `cards.get(0)`.
- **Commit:** `git commit -m "Filter Explore's Collection and Runs to one dungeon"`

---

### Task 4: `ExplorePictures` — Dungeons level, three-way entry, the dungeon filter, one navigation hook

**Files:**
- Modify: `src/main/java/tomato/gui/loot/explore/ExplorePictures.java`
- Test: `src/test/java/tomato/gui/loot/explore/ExplorePicturesTest.java`

**Interfaces (produced):**
- `enum Level { RUNS, DUNGEONS, COLLECTION, ITEM }`
- `record Focus(Level level, String dungeon)`: a navigation target. `dungeon` is non-null only for RUNS or COLLECTION.
- `record State(Level level, VisitRef run, boolean unlinked, int itemId, Level itemFrom, String dungeon)`
- `void onNavigate(Consumer<Focus>)` (default: `go`); `void go(Focus)`
- `void showDungeons()`, `void openDungeon(String canonical)`, `void openDungeonCollection(String canonical)`, `String dungeon()`, `DungeonsLevel dungeons()`
- The package-private constructor gains a `DungeonsLevel` parameter after `collection`. `production(store)` builds it from `DungeonsLevel.production(store, catalog)`.
- `ENTRY_KEY` values: `"runs"`, `"dungeons"` and `"collection"`. The switch is `SegmentedControl("loot-explore-entry", "Runs", "Dungeons", "Collection")`.

**Behavior:**
- **The entry switch, breadcrumb links, a dungeon tile (`dungeons.onOpenDungeon`) and the panel's "All items from"** (`runs.dungeon().onOpenCollection`) each call `navigate.accept(new Focus(...))`. Production routes these, so Back and Forward step through them. The default is `go`, applied in place.
- **The entry switch still writes `ENTRY_KEY` on a user choice.**
- **`go(focus)`:**

  | Focus | What it shows |
  |---|---|
  | RUNS with null dungeon | `showRuns()`: clears any dungeon filter on the strip (`feed().showDungeon(null)`) and shows Runs |
  | RUNS with a dungeon | `openDungeon(d)`: `clearRun()`, then `feed().showDungeon(d)`, then shows Runs with `dungeon = d` |
  | DUNGEONS | `showDungeons()`: shows the wall and reloads it |
  | COLLECTION with null dungeon | `showCollection()`: clears Collection's filter |
  | COLLECTION with a dungeon | `openDungeonCollection(d)`: `collection.filterDungeon(d)` and shows Collection with `dungeon = d` |

  Clearing the Collection chip or the strip's "Dungeon: X" chip clears `dungeon`. Hook the feed through a `RunFeedView` query
  listener if one exists; otherwise compare `feed().query().map()` in `runs.onShown`.
- **Breadcrumb:**

  | Where | Crumbs |
  |---|---|
  | Runs | "Runs" › run label |
  | Runs filtered to a dungeon | "Dungeons" › "<dungeon>" › run label |
  | Dungeons | "Dungeons" |
  | Collection | "Collection" |
  | Collection filtered to a dungeon | "Dungeons" › "<dungeon>" › "All items" |
  | Item | the crumbs of the level it came from (with its dungeon) › item name |

  Every crumb but the last navigates through `navigate`:
  - "Runs" → RUNS with null dungeon;
  - "Dungeons" → DUNGEONS;
  - the dungeon crumb → RUNS with that dungeon;
  - "Collection" → COLLECTION with null dungeon.
- **Entry highlight:** Runs for RUNS with no dungeon, Dungeons for DUNGEONS or any level filtered to a dungeon, Collection for an unfiltered COLLECTION.
- **`state()` and `restore(State)`:** these carry `dungeon`. Restore reapplies the dungeon filter for its level before showing it, and keeps P2/P3 restores: the run, "Loot outside runs", and the item.
- **Tests** (extend `ExplorePicturesTest`; build `DungeonsLevel` with fakes):
  - the three-way entry and its preference;
  - a tile opening filtered Runs, with the breadcrumb "Dungeons › Lost Halls" and the newest run opening;
  - "All items from" opening filtered Collection with the "All items" crumb;
  - the "Runs" crumb clearing the filter;
  - the `onNavigate` hook receiving each Focus instead of applying it, when set;
  - a state round trip through Dungeons → filtered Runs → filtered Collection → item.
- **Commit:** `git commit -m "Give Explore's Pictures a Dungeons way in and one navigation hook"`

---

### Task 5: Route Pictures navigation, so Back and Forward step through it

**Files:**
- Modify: `src/main/java/tomato/gui/loot/explore/LootExplorePage.java`, which gains `focusTarget()` and `onNavigate(Consumer<ExplorePictures.Focus>)`; `RouteState` now carries the new `State`
- Modify: `src/main/java/tomato/gui/TomatoGUI.java` (register `focusTarget()` with `lootPage.routes(LootTab.EXPLORE, …)`, and wire `onNavigate` to `navigator.open(Route.to(LOOT).withPayload(focus))`)
- Test: `src/test/java/tomato/gui/loot/explore/LootExplorePageTest.java`

**Behavior:**
- `focusTarget()` accepts only LOOT routes whose payload is an `ExplorePictures.Focus`. To open, it shows Pictures and then `pictures.go(focus)`. It captures and restores the same `RouteState` as `itemTarget()`: the view, then `pictures.restore(state)`.
- It must not accept `LootFocus` payloads (`LootPage.tabTarget`) or `ExploreItem`, and the workspace targets keep rejecting payload routes.
- **Test:** a navigator-like sequence with captured states:
  1. Runs, Dungeons, a tile (filtered Runs), All items from, an item.
  2. Restoring each captured state in reverse returns the exact level, dungeon, run and item.
  3. Re-opening them in order (Forward) works too.
  4. `focusTarget().accepts` rejects LootFocus and ExploreItem routes.
- **Commit:** `git commit -m "Route Explore's level moves so mouse Back and Forward step through them"`

---

### Task 6: Docs and a whole-tree compile (coordinator checks)

- **`docs/LOOT.md`** (`## Explore`):
  - add the **Dungeons** bullet (tiles, sorts, search, a tile → filtered runs, "All items from");
  - update the breadcrumb sentence and the Runs | Dungeons | Collection switch (`ui.loot.explore.entry` values);
  - say that every Pictures move is a Back/Forward step except picking a run card.
- **`docs/UI-REDESIGN.md`:** if it lists un-routed drill-downs from PR #51, remove Explore's entry switch and breadcrumb from that list.
- **The coordinator runs:**
  - `GRADLE compileTestJava`, but replace `test --tests <TEST>` with `compileTestJava`;
  - `GRADLE --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.kit.*' --tests 'tomato.WaveThreeJourneyTest' --tests 'tomato.gui.route.*'`.
- **Commit:** `git commit -m "Document Explore's Dungeons and routed Pictures navigation"`

### Task 7: Verification, launch and PR (coordinator)

- **Suite:** the full focused suite plus a whole-tree `compileTestJava`. Expect only the known environmental failures.
- **Launch:** `shadowJar`, then launch from the user's folder once no RealmShark is running. The user checks:
  - Dungeons tiles and sorts;
  - a tile opening filtered Runs and the dungeon panel's "All items from";
  - mouse Back/Forward through every move.
- **PR:** "Loot Explore pictures P4: Dungeons and routed Pictures navigation", ending the body with the Claude Code attribution line.
