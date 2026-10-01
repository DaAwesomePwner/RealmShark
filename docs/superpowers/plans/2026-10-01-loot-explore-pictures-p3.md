# Loot Explore pictures — P3 (Collection, item history, breadcrumb) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This repo's workflow:** the implementer (Codex via Orchestra) edits files only. The coordinator (Claude) runs every
> Gradle command, makes every commit, launches the app and opens the PR.

**Goal:** Explore's Pictures get a second way in and a level to dive into.
- **Collection** is the trophy cabinet: every item you have looted, as sprites with counts on shelves.
- **Item history** shows every drop of one item, as cards that open their run.
- A **breadcrumb** says where you are.
- A **Runs | Collection** switch chooses the way in.

**Architecture:**
- `ExplorePictures` replaces P2's bare `RunsLevel` inside `LootExplorePage`. It holds a breadcrumb, the entry switch, and three levels: Runs (P2's `RunsLevel`), `CollectionLevel` and `ItemLevel`.
- Collection and Item read every saved loot bag through a small cached reader (`LootCatalog`, closed sessions kept by `SessionStamps`), then build pure models (`CollectionModel`, `ItemHistory`).
- Item drops reuse Highlights' `NotableDropRenderer` cards, so a drop looks the same in Highlights and Explore.
- Opening an item or a drop goes through the navigator (a new `ExploreItem` route payload and the existing exact-run route), so **Back** walks back through levels.

**Tech Stack:** Java 17 (`--release 17`), Swing + FlatLaf, the project UI kit, JUnit 4, Gradle 7.6.4.

**Spec:** `docs/superpowers/specs/2026-10-01-loot-explore-pictures-design.md` (P3 = "Item and Collection levels"). P1 (#46) and P2 (#48) are merged.

### Deviations from the spec in this phase (approved with this plan)

- **Item history is per item**, all enchant variants together, and each drop card shows its own rarity. A haul's item click passes its variant key; the Item level opens that key's item ID.
- **Collection and Item read every saved session directly** (`LootFacts.read`, cached per closed session), not the archive's `ITEMS`/`OCCURRENCES` projections. The counts are the same, without the archive's temp files and 100-row pages. Both cover all saved sessions; there is no session-scope selector yet.
- **The entry switch is Runs | Collection.** Dungeons joins it in P4. The entry is remembered in `ui.loot.explore.entry`.
- **Item clicks no longer open Table.** P2's `LootExplorePage.variantQuery` is removed.

## Global Constraints

- Java 17 language level only; no new dependencies.
- All Swing work on the EDT. Saved-history reads run off the EDT on one daemon worker per level, and only the newest request's result applies (a generation guard), as `RunsLevel` does.
- Copy is sentence case, parts joined with `" · "`, and counts read `"×N"`. Never present a guessed rarity: `NOT_RECORDED` enchant data shows no pips.
- New component names use the `loot-collection-*`, `loot-item-*` and `loot-explore-*` prefixes; the kit breadcrumb uses its given name.
- Fixtures are synthetic (`RunFixtures`, `LootTestDrops`, hand-built `LootFacts.Bag`s); no personal history.
- Keyboard tests invoke bound actions or queued AWT events, never `java.awt.Robot`.
- Gradle (from the worktree root, Git Bash):
  ```bash
  export JAVA_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/gradle-home"; ./gradlew.bat test --offline -PrealmSharkBuildDir=build-loot-p3 --tests <TEST>
  ```
  Written below as `GRADLE --tests <TEST>`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Items with no tier, no notable kind and no enchant data**, such as a plain consumable that isn't a stat potion. Their drop cards and tiles must render without exceptions, reading "Item" rather than a guessed type. Covered by Tasks 1 and 3.
2. **A large collection:** a shelf with more than 60 items shows 60 and "Show all N", and search filters every shelf. Covered by Task 4.
3. **Fast switching between levels or items:** only the newest item's history applies, and the breadcrumb always names what is shown. Covered by Tasks 5 and 6.
4. **Back across levels:** a run, Collection, an item, then Back returns through those levels, restoring the run and "Loot outside runs". Covered by Task 7.
5. **No saved history or a failed read:** one line saying why, never a blank pane. Covered by Tasks 3–5.

---

### Task 1: Open small hooks in shared loot code

**Files:**
- Modify: `src/main/java/tomato/gui/loot/HighlightsModel.java` (`kind(LootFacts.Item)` becomes `public static`)
- Modify: `src/main/java/tomato/gui/loot/NotableDropRenderer.java`:
  - `itemType` reads "Item" when `kind()` is null;
  - `accessibleName` becomes `public static`.
- Modify: `src/main/java/tomato/gui/loot/haul/HaulModel.java` (`rarity(LootFacts.Item)` becomes `public static`)
- Modify: `src/main/java/tomato/gui/loot/haul/HaulView.java` (`tierLabel(LootFacts.Item)` becomes `public static`)
- Test: `src/test/java/tomato/gui/loot/NotableDropRendererTest.java` (add one test)

**Interfaces:**
- Produces: `HighlightsModel.kind(LootFacts.Item)`, `NotableDropRenderer.accessibleName(Notable, ZoneId, long)`, `HaulModel.rarity(LootFacts.Item)` and `HaulView.tierLabel(LootFacts.Item)`, all public. A `Notable` with a null `kind` is valid.

- [ ] **Step 1: Write the failing test.** Add it to `NotableDropRendererTest`, reusing its `ZONE_NY` and `NOON` constants:

```java
    /** A drop that is neither UT, ST, a potion nor enchanted (Explore's item history lists every drop) reads "Item", never throws. */
    @Test public void aDropWithoutANotableKindReadsItem() {
        HighlightsModel.Notable plain = new HighlightsModel.Notable(987_654_321, "Brown", "Pirate Cave", NOON, null, null);
        assertEquals("Item", NotableDropRenderer.itemType(plain));
        assertTrue(NotableDropRenderer.accessibleName(plain, ZONE_NY, NOON).contains("Item"));
        assertNull("A plain item is not notable", HighlightsModel.kind(new tomato.gui.stats.LootFacts.Item(5, false, false, false, false)));
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.NotableDropRendererTest`
Expected: FAIL with a `NullPointerException` in `itemType`.

- [ ] **Step 3: Implement**
  - In `NotableDropRenderer.itemType`, change the last line to
    `return !tier.isEmpty() ? tier : drop.kind() == null ? "Item" : drop.kind() == HighlightsModel.Kind.ENCHANTED ? "Gear" : drop.kind().label();`.
  - Make `NotableDropRenderer.accessibleName` public.
  - Make `HighlightsModel.kind` public.
  - Make `HaulModel.rarity` and `HaulView.tierLabel` public.
  - Add "(public: Explore reuses it)" to each changed Javadoc.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.loot.NotableDropRendererTest --tests tomato.gui.loot.HighlightsModelTest --tests 'tomato.gui.loot.haul.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/HighlightsModel.java src/main/java/tomato/gui/loot/NotableDropRenderer.java src/main/java/tomato/gui/loot/haul/HaulModel.java src/main/java/tomato/gui/loot/haul/HaulView.java src/test/java/tomato/gui/loot/NotableDropRendererTest.java
git commit -m "Let drop cards show items of no notable kind, and open shared loot helpers"
```

---

### Task 2: Kit `Breadcrumb`

**Files:**
- Create: `src/main/java/tomato/gui/kit/Breadcrumb.java`
- Create: `src/test/java/tomato/gui/kit/BreadcrumbTest.java`

**Interfaces:**
- Produces:
  - `public final class Breadcrumb extends JPanel` with `Breadcrumb(String name)`, `void setPath(List<Crumb>)` and `List<String> labels()`
  - `public record Breadcrumb.Crumb(String label, Runnable open)`. A null `open`, or the last crumb, is plain text.
  - Parts are named `<name>-<index>`. A link is a `KitButton`; text is a `KitText`.

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/kit/BreadcrumbTest.java`:

```java
package tomato.gui.kit;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The breadcrumb: its labels in order, links for every crumb but the last, and the "›" separators. */
public class BreadcrumbTest {
    @Test public void everyCrumbButTheLastIsALink() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> opened = new ArrayList<>();
            Breadcrumb path = new Breadcrumb("path");
            path.setPath(List.of(new Breadcrumb.Crumb("Collection", () -> opened.add("collection")),
                new Breadcrumb.Crumb("Synthetic Seal", () -> opened.add("never"))));
            assertEquals(List.of("Collection", "Synthetic Seal"), path.labels());
            Component first = named(path, "path-0"), last = named(path, "path-1");
            assertTrue(first instanceof AbstractButton);
            assertFalse("The last crumb is where you are", last instanceof AbstractButton);
            ((AbstractButton) first).doClick();
            assertEquals(List.of("collection"), opened);

            path.setPath(List.of(new Breadcrumb.Crumb("Runs", null)));
            assertEquals(List.of("Runs"), path.labels());
            assertFalse(named(path, "path-0") instanceof AbstractButton);
        });
    }

    private static Component named(JComponent root, String name) {
        for (Component child : root.getComponents()) if (name.equals(child.getName())) return child;
        throw new AssertionError("No " + name);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.kit.BreadcrumbTest`
Expected: compilation FAIL, "cannot find symbol: Breadcrumb".

- [ ] **Step 3: Implement** `src/main/java/tomato/gui/kit/Breadcrumb.java`:

```java
package tomato.gui.kit;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Where you are, left to right ("Collection › Synthetic Seal"): every crumb but the last is a link back to that place; the last
 * names the place shown. Separators are "›" and are not announced. Parts are named {@code <name>-<index>}. EDT only.
 */
public final class Breadcrumb extends JPanel {
    /** One place: its label and what opening it runs (null: plain text). */
    public record Crumb(String label, Runnable open) {
        public Crumb { Objects.requireNonNull(label, "label"); }
    }

    private final List<String> labels = new ArrayList<>();

    public Breadcrumb(String name) {
        super(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
        setName(Objects.requireNonNull(name, "name"));
        setOpaque(false);
        getAccessibleContext().setAccessibleName("You are here");
    }

    /** Shows {@code path}, replacing the previous one. */
    public void setPath(List<Crumb> path) {
        removeAll();
        labels.clear();
        for (int i = 0; i < path.size(); i++) {
            Crumb crumb = path.get(i);
            boolean last = i == path.size() - 1;
            if (i > 0) {
                KitText separator = KitText.caption("›");
                separator.getAccessibleContext().setAccessibleName("");
                add(separator);
            }
            JComponent part;
            if (last || crumb.open() == null) part = last ? KitText.emphasis(crumb.label()) : KitText.body(crumb.label());
            else {
                KitButton link = KitButton.ghost(crumb.label());
                link.addActionListener(e -> crumb.open().run());
                part = link;
            }
            part.setName(getName() + "-" + i);
            add(part);
            labels.add(crumb.label());
        }
        revalidate();
        repaint();
    }

    /** The shown labels, in order. */
    public List<String> labels() { return List.copyOf(labels); }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.kit.BreadcrumbTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/Breadcrumb.java src/test/java/tomato/gui/kit/BreadcrumbTest.java
git commit -m "Add a breadcrumb to the UI kit"
```

---

### Task 3: `LootCatalog`, `CollectionModel` and `ItemHistory`

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/LootCatalog.java`
- Create: `src/main/java/tomato/gui/loot/explore/CollectionModel.java`
- Create: `src/main/java/tomato/gui/loot/explore/ItemHistory.java`
- Create: `src/test/java/tomato/gui/loot/explore/CollectionModelTest.java`
- Create: `src/test/java/tomato/gui/loot/explore/ItemHistoryTest.java`
- Create: `src/test/java/tomato/gui/loot/explore/LootCatalogTest.java`

**Interfaces:**
- Consumes: `LootFacts.read(store, catalog, session, sink)`, `SessionStamps<V>(String...)` with `get` and `forgetGone`, `HaulModel.rarity`, `HighlightsModel.kind`, `HighlightsModel.Notable`, and `RunHauls.NOT_OPEN` (package-private in this package)
- Produces:
  - `interface LootCatalog.Reader { List<LootFacts.Bag> bags(Cancellation) throws IOException; }`
  - `static LootCatalog.Reader LootCatalog.over(Supplier<SessionStore>)`
  - `record CollectionModel(List<Shelf> shelves, int drops, int kinds)`
    - `enum CollectionModel.Kind { UT, ST, TIERED, POTIONS, OTHER }`, each with a `label`
    - `record CollectionModel.Entry(int itemId, int count, LootFacts.Item front, long last)`
    - `record CollectionModel.Shelf(Kind kind, List<Entry> entries)`
    - `static CollectionModel of(List<LootFacts.Bag>, String search, IntFunction<String> names)`
    - `static Kind kind(LootFacts.Item)`
  - `record ItemHistory(int itemId, List<HighlightsModel.Notable> drops, LootFacts.Item front)`
    - `static ItemHistory of(int itemId, List<LootFacts.Bag>)`
    - `int count()` and `String summary()`

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/loot/explore/CollectionModelTest.java`:

```java
package tomato.gui.loot.explore;

import java.util.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;

/** The trophy cabinet's rules: shelves by kind, one entry per item with its count, the best variant in front, search by name. */
public class CollectionModelTest {
    static LootFacts.Item ut(int id, Integer slots) { return new LootFacts.Item(id, true, false, false, false, slots, slots == null ? null : 0); }
    static LootFacts.Item st(int id) { return new LootFacts.Item(id, false, true, false, false); }
    static LootFacts.Item tiered(int id) { return new LootFacts.Item(id, false, false, true, false, null, null, null, "T13"); }
    static LootFacts.Item potion(int id) { return new LootFacts.Item(id, false, false, false, true); }
    static LootFacts.Item plain(int id) { return new LootFacts.Item(id, false, false, false, false); }
    static LootFacts.Bag bag(long time, LootFacts.Item... items) { return new LootFacts.Bag("s", time, false, "White", null, List.of(items), "Lost Halls", "Synthetic boss"); }
    static final Map<Integer, String> NAMES = Map.of(1, "Synthetic Seal", 2, "Synthetic Robe", 3, "Synthetic Staff", 4, "Potion of Life", 5, "Synthetic Rock");

    @Test public void itemsLandOnTheirShelfWithCountsAndTheBestVariantInFront() {
        CollectionModel model = CollectionModel.of(List.of(
            bag(100, ut(1, 0), potion(4), potion(4)),
            bag(200, ut(1, 3), st(2), tiered(3), plain(5)),
            bag(300, ut(1, 1), potion(4))), "", NAMES::get);
        assertEquals(List.of(CollectionModel.Kind.UT, CollectionModel.Kind.ST, CollectionModel.Kind.TIERED, CollectionModel.Kind.POTIONS, CollectionModel.Kind.OTHER),
            model.shelves().stream().map(CollectionModel.Shelf::kind).toList());
        CollectionModel.Entry seal = model.shelves().get(0).entries().get(0);
        assertEquals(1, seal.itemId());
        assertEquals(3, seal.count());
        assertEquals("The Legendary (3 slots) variant is in front", Integer.valueOf(3), seal.front().slots());
        assertEquals(300, seal.last());
        assertEquals(3, model.shelves().get(3).entries().get(0).count());
        assertEquals(9, model.drops());
        assertEquals(5, model.kinds());
    }

    @Test public void entriesSortByCountThenNewest() {
        CollectionModel model = CollectionModel.of(List.of(bag(100, potion(4)), bag(200, potion(6)), bag(300, potion(6))), "",
            id -> "Potion " + id);
        assertEquals(List.of(6, 4), model.shelves().get(0).entries().stream().map(CollectionModel.Entry::itemId).toList());
    }

    @Test public void searchKeepsItemsWhoseNameMatchesAndEmptyShelvesLeave() {
        CollectionModel model = CollectionModel.of(List.of(bag(100, ut(1, null), st(2), potion(4))), "  ROBE ", NAMES::get);
        assertEquals(1, model.shelves().size());
        assertEquals(CollectionModel.Kind.ST, model.shelves().get(0).kind());
        assertTrue(CollectionModel.of(List.of(), "", NAMES::get).shelves().isEmpty());
    }
}
```

`src/test/java/tomato/gui/loot/explore/ItemHistoryTest.java`:

```java
package tomato.gui.loot.explore;

import java.util.List;
import org.junit.Test;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;

/** One item's drops, newest first, with where each came from, and the rarity summary line. */
public class ItemHistoryTest {
    @Test public void dropsOfOneItemNewestFirstWithTheirRunsAndRarities() {
        VisitRef run = new VisitRef("00000000-0000-4000-8000-000000000001", "v1");
        ItemHistory history = ItemHistory.of(1, List.of(
            new LootFacts.Bag("s", 100, false, "White", run, List.of(ut(1, 2), potion(4)), "Lost Halls", "Synthetic boss"),
            bag(300, ut(1, 3)),
            bag(200, ut(1, 0), ut(1, null))));
        assertEquals(4, history.count());
        assertEquals(List.of(300L, 200L, 200L, 100L), history.drops().stream().map(HighlightsModel.Notable::time).toList());
        assertEquals(run, history.drops().get(3).visit());
        assertEquals(HighlightsModel.Kind.UT, history.drops().get(0).kind());
        assertEquals(Integer.valueOf(3), history.front().slots());
        assertEquals("4 drops · 1 Rare · 1 Legendary", history.summary());
    }

    @Test public void anItemNeverLootedHasNoDrops() {
        ItemHistory history = ItemHistory.of(42, List.of(bag(100, potion(4))));
        assertEquals(0, history.count());
        assertNull(history.front());
        assertEquals("0 drops", history.summary());
    }
}
```

`src/test/java/tomato/gui/loot/explore/LootCatalogTest.java`:

```java
package tomato.gui.loot.explore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.runs.RunFixtures;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import static org.junit.Assert.*;

/** Every saved bag of every readable session, read off the EDT, and a clear failure without saved history. */
public class LootCatalogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void readsEveryBagOfEverySession() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            LootCatalog.Reader reader = LootCatalog.over(() -> store);
            List<LootFacts.Bag> bags = reader.bags(new Cancellation());
            assertEquals("Session A's four bags and B's one", 5, bags.size());
            assertEquals("A second read gives the same bags", bags, reader.bags(new Cancellation()));
        }
    }

    @Test public void withoutSavedHistoryTheReadSaysSo() {
        try { LootCatalog.over(() -> null).bags(new Cancellation()); fail("Needs saved history"); }
        catch (IOException expected) { assertEquals(RunHauls.NOT_OPEN, expected.getMessage()); }
    }
}
```

If `SessionStore` is not `AutoCloseable`, use a `try`/`finally` with `store.close()` instead.

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE --tests tomato.gui.loot.explore.CollectionModelTest --tests tomato.gui.loot.explore.ItemHistoryTest --tests tomato.gui.loot.explore.LootCatalogTest`
Expected: compilation FAIL (the classes don't exist).

- [ ] **Step 3: Implement**

`src/main/java/tomato/gui/loot/explore/LootCatalog.java`:

```java
package tomato.gui.loot.explore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;

/**
 * Every saved loot bag of every readable session, for Explore's Collection and Item levels. Closed sessions' bags are kept
 * while their loot files are unchanged ({@link SessionStamps}); the current session is read each time. Off the EDT only.
 */
public final class LootCatalog {
    /** How the levels read bags ({@link #over} in production). */
    public interface Reader { List<LootFacts.Bag> bags(Cancellation cancel) throws IOException; }

    private LootCatalog() {}

    /** Reads saved history from {@code stores}; while none is open, every read fails saying so. One reader keeps its own cache. */
    public static Reader over(Supplier<SessionStore> stores) {
        Objects.requireNonNull(stores, "stores");
        SessionStamps<List<LootFacts.Bag>> kept = new SessionStamps<>("loot");
        return cancel -> {
            SessionStore store = stores.get();
            if (store == null) throw new IOException(RunHauls.NOT_OPEN);
            return read(store, kept, cancel);
        };
    }

    static List<LootFacts.Bag> read(SessionStore store, SessionStamps<List<LootFacts.Bag>> kept, Cancellation cancel) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);
        kept.forgetGone(store, catalog);
        List<LootFacts.Bag> all = new ArrayList<>();
        for (SessionStore.SessionEntry entry : catalog) {
            if (!entry.readable()) continue;
            cancel.check();
            all.addAll(kept.get(store, entry.id, stamp -> {
                List<LootFacts.Bag> bags = new ArrayList<>();
                LootFacts.read(store, catalog, entry.id, bags::add);
                return List.copyOf(bags);
            }));
        }
        return all;
    }
}
```

`src/main/java/tomato/gui/loot/explore/CollectionModel.java`:

```java
package tomato.gui.loot.explore;

import java.util.*;
import java.util.function.IntFunction;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.stats.LootFacts;

/**
 * Explore's Collection, the trophy cabinet: every item ever looted, one entry per item ID with its drop count, on shelves by kind
 * (UTs, STs, Tiered, Potions, Other), most-dropped first. An entry's {@code front} is the drop drawn for it: the best enchant rarity,
 * then the newest. Pure.
 */
public record CollectionModel(List<Shelf> shelves, int drops, int kinds) {
    public CollectionModel { shelves = List.copyOf(shelves); }

    /** The shelves, in this order. */
    public enum Kind {
        UT("UTs"), ST("STs"), TIERED("Tiered"), POTIONS("Potions"), OTHER("Other");
        public final String label;
        Kind(String label) { this.label = label; }
    }

    /** One item: {@code count} drops, {@code front} the one drawn, {@code last} its newest drop's time. */
    public record Entry(int itemId, int count, LootFacts.Item front, long last) {}

    /** One shelf's entries, most-dropped first. */
    public record Shelf(Kind kind, List<Entry> entries) {
        public Shelf { entries = List.copyOf(entries); }
    }

    /** UT, then ST, then tiered (T13+), then stat potion; everything else is Other. */
    public static Kind kind(LootFacts.Item item) {
        if (item.untiered()) return Kind.UT;
        if (item.setTiered()) return Kind.ST;
        if (item.highTier()) return Kind.TIERED;
        if (item.potion()) return Kind.POTIONS;
        return Kind.OTHER;
    }

    /** The cabinet of {@code bags}, keeping only items whose name ({@code names}) contains {@code search}, ignoring case. */
    public static CollectionModel of(List<LootFacts.Bag> bags, String search, IntFunction<String> names) {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final class Tally { int count; LootFacts.Item front; long frontTime, last; }
        Map<Integer, Tally> byId = new LinkedHashMap<>();
        Map<Integer, Boolean> matches = new HashMap<>();
        int drops = 0;
        for (LootFacts.Bag bag : bags) for (LootFacts.Item item : bag.items()) {
            if (!query.isEmpty() && !matches.computeIfAbsent(item.id(),
                id -> Objects.toString(names.apply(id), "").toLowerCase(Locale.ROOT).contains(query))) continue;
            Tally tally = byId.computeIfAbsent(item.id(), id -> new Tally());
            tally.count++;
            drops++;
            tally.last = Math.max(tally.last, bag.time());
            if (tally.front == null || better(item, bag.time(), tally.front, tally.frontTime)) { tally.front = item; tally.frontTime = bag.time(); }
        }
        Map<Kind, List<Entry>> shelves = new EnumMap<>(Kind.class);
        for (Map.Entry<Integer, Tally> entry : byId.entrySet()) {
            Tally tally = entry.getValue();
            shelves.computeIfAbsent(kind(tally.front), kind -> new ArrayList<>()).add(new Entry(entry.getKey(), tally.count, tally.front, tally.last));
        }
        List<Shelf> result = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            List<Entry> entries = shelves.get(kind);
            if (entries == null) continue;
            entries.sort(Comparator.comparingInt(Entry::count).reversed().thenComparing(Comparator.comparingLong(Entry::last).reversed())
                .thenComparingInt(Entry::itemId));
            result.add(new Shelf(kind, entries));
        }
        return new CollectionModel(result, drops, byId.size());
    }

    /** A higher enchant rarity wins; at equal rarity, the newer drop. */
    private static boolean better(LootFacts.Item item, long time, LootFacts.Item front, long frontTime) {
        int a = HaulModel.rarity(item), b = HaulModel.rarity(front);
        return a != b ? a > b : time > frontTime;
    }
}
```

`src/main/java/tomato/gui/loot/explore/ItemHistory.java`:

```java
package tomato.gui.loot.explore;

import java.util.*;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.stats.LootFacts;
import tomato.realmshark.EnchantInfo;

/**
 * Every saved drop of one item, newest first, as Highlights' drop cards ({@link HighlightsModel.Notable}: bag, area, time, run,
 * enchantments, tier); {@code front} is the drop drawn in the header (the best enchant rarity, then the newest), null without drops.
 * Pure.
 */
public record ItemHistory(int itemId, List<HighlightsModel.Notable> drops, LootFacts.Item front) {
    public ItemHistory { drops = List.copyOf(drops); }

    public static ItemHistory of(int itemId, List<LootFacts.Bag> bags) {
        List<HighlightsModel.Notable> drops = new ArrayList<>();
        LootFacts.Item front = null;
        long frontTime = 0;
        for (LootFacts.Bag bag : bags) for (LootFacts.Item item : bag.items()) {
            if (item.id() != itemId) continue;
            drops.add(new HighlightsModel.Notable(itemId, bag.bag(), bag.dungeon(), bag.time(), bag.visit(), HighlightsModel.kind(item),
                item.enchant(), item.tier()));
            int rarity = HaulModel.rarity(item), best = front == null ? Integer.MIN_VALUE : HaulModel.rarity(front);
            if (front == null || rarity > best || rarity == best && bag.time() > frontTime) { front = item; frontTime = bag.time(); }
        }
        drops.sort(Comparator.comparingLong(HighlightsModel.Notable::time).reversed());
        return new ItemHistory(itemId, drops, front);
    }

    public int count() { return drops.size(); }

    /** "12 drops · 3 Rare · 1 Legendary": the enchanted drops by rarity, rarest last; drops without enchant data count only as drops. */
    public String summary() {
        Map<EnchantInfo.Rarity, Integer> byRarity = new EnumMap<>(EnchantInfo.Rarity.class);
        for (HighlightsModel.Notable drop : drops) if (drop.enchant().enchanted()) byRarity.merge(drop.enchant().rarity(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        parts.add(drops.size() + (drops.size() == 1 ? " drop" : " drops"));
        for (EnchantInfo.Rarity rarity : List.of(EnchantInfo.Rarity.UNCOMMON, EnchantInfo.Rarity.RARE, EnchantInfo.Rarity.LEGENDARY, EnchantInfo.Rarity.DIVINE)) {
            Integer count = byRarity.get(rarity);
            if (count != null) parts.add(count + " " + rarity.label);
        }
        return String.join(" · ", parts);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.loot.explore.CollectionModelTest --tests tomato.gui.loot.explore.ItemHistoryTest --tests tomato.gui.loot.explore.LootCatalogTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/LootCatalog.java src/main/java/tomato/gui/loot/explore/CollectionModel.java src/main/java/tomato/gui/loot/explore/ItemHistory.java src/test/java/tomato/gui/loot/explore/CollectionModelTest.java src/test/java/tomato/gui/loot/explore/ItemHistoryTest.java src/test/java/tomato/gui/loot/explore/LootCatalogTest.java
git commit -m "Read every saved bag for Explore and build the collection and item history"
```

---

### Task 4: `CollectionLevel` — the trophy cabinet

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/CollectionTileRenderer.java`
- Create: `src/main/java/tomato/gui/loot/explore/CollectionLevel.java`
- Create: `src/test/java/tomato/gui/loot/explore/CollectionLevelTest.java`

**Interfaces:**
- Consumes:
  - from Task 3: `LootCatalog.Reader`, `CollectionModel`
  - `TileList<T>(String, ListCellRenderer, Function<T,String>, Function<T,String>)` with `setItems`, `onOpen` and `items()`
  - `ItemSlot.icon(Icon, String, ItemSlot.State, int, EnchantInfo)`, `HaulView.tierLabel`, `SectionHeader(String)`
  - `ContentStyle.page(header, body, footer)` and `ContentStyle.wrappingText`
- Produces:
  - `public final class CollectionLevel extends JPanel implements AutoCloseable`
  - `static CollectionLevel production(LootCatalog.Reader)`
  - package-private `CollectionLevel(LootCatalog.Reader, Executor, IntFunction<String> names)`
  - `void reload()`, `void onOpenItem(IntConsumer)`, `CollectionModel model()`, `void close()`
  - `static final int SHELF_LIMIT = 60`
  - package-private `JTextField search()` and `JTextArea status()`
  - Names: `loot-collection`, `loot-collection-search`, `loot-collection-summary`, `loot-collection-status`, `loot-collection-shelf-<kind>` (a `TileList`), `loot-collection-more-<kind>` (button), where `<kind>` is the lowercase enum name

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/CollectionLevelTest.java`:

```java
package tomato.gui.loot.explore;

import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.TileList;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** The cabinet over a fake catalog: shelves of tiles, the 60-item cap with "Show all", search, opening an item, empty and failed reads. */
public class CollectionLevelTest {
    private static CollectionLevel level(LootCatalog.Reader catalog) throws Exception {
        return edt(() -> new CollectionLevel(catalog, Runnable::run, id -> NAMES.getOrDefault(id, "Synthetic item " + id)));
    }

    @Test public void shelvesShowTheirTilesAndATileOpensItsItem() throws Exception {
        CollectionLevel level = level(cancel -> List.of(bag(100, ut(1, 2), potion(4), potion(4)), bag(200, st(2))));
        List<Integer> opened = new ArrayList<>();
        edt(() -> { level.onOpenItem(opened::add); level.reload(); return null; });
        edt(() -> null);   // the read applies on a later EDT turn
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<CollectionModel.Entry> uts = named(level, "loot-collection-shelf-ut", TileList.class);
            assertEquals(List.of(1), uts.items().stream().map(CollectionModel.Entry::itemId).toList());
            assertEquals("3 items · 4 drops", named(level, "loot-collection-summary", JLabel.class).getText());
            uts.setSelectedIndex(0);
            Object key = uts.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
            uts.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(uts, 0, null));
            assertEquals(List.of(1), opened);
            return null;
        });
        level.close();
    }

    @Test public void aBigShelfShowsSixtyThenShowAll() throws Exception {
        List<LootFacts.Item> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add(potion(1000 + i));
        CollectionLevel level = level(cancel -> List.of(new LootFacts.Bag("s", 1, false, "Purple", null, many, null, null)));
        edt(() -> { level.reload(); return null; });
        edt(() -> null);
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<CollectionModel.Entry> potions = named(level, "loot-collection-shelf-potions", TileList.class);
            assertEquals(CollectionLevel.SHELF_LIMIT, potions.items().size());
            AbstractButton more = named(level, "loot-collection-more-potions", AbstractButton.class);
            assertEquals("Show all 70", more.getText());
            more.doClick();
            @SuppressWarnings("unchecked") TileList<CollectionModel.Entry> all = named(level, "loot-collection-shelf-potions", TileList.class);
            assertEquals(70, all.items().size());
            return null;
        });
        level.close();
    }

    @Test public void searchFiltersEveryShelf() throws Exception {
        CollectionLevel level = level(cancel -> List.of(bag(100, ut(1, null), st(2), potion(4))));
        edt(() -> { level.reload(); return null; });
        edt(() -> null);
        edt(() -> {
            level.search().setText("robe");
            assertEquals(1, level.model().shelves().size());
            assertTrue(all(level, "loot-collection-shelf-ut", TileList.class).isEmpty());
            level.search().setText("nothing like this");
            assertEquals(CollectionLevel.NO_MATCH, level.status().getText());
            return null;
        });
        level.close();
    }

    @Test public void anEmptyOrFailedReadSaysWhy() throws Exception {
        CollectionLevel empty = level(cancel -> List.of());
        edt(() -> { empty.reload(); return null; });
        edt(() -> null);
        assertEquals(CollectionLevel.EMPTY, edt(() -> empty.status().getText()));
        empty.close();
        CollectionLevel failed = level(cancel -> { throw new IOException("synthetic read failure"); });
        edt(() -> { failed.reload(); return null; });
        edt(() -> null);
        assertEquals("Your collection could not be read: synthetic read failure", edt(() -> failed.status().getText()));
        failed.close();
    }
}
```

`close()` is called on the test thread. If `CollectionLevel.close()` requires the EDT, wrap those calls in `edt(...)`.

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.CollectionLevelTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`src/main/java/tomato/gui/loot/explore/CollectionTileRenderer.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.haul.HaulView;
import tomato.realmshark.EnchantInfo;

/**
 * Paints one Collection entry: the item's sprite in its slot (tier label, enchant pips and rarity glow of the front drop) with its
 * drop count ("×12") under it; selection and focus as a tinted rounded tile. One component reused for every cell; colors resolve at
 * paint time. The tooltip and accessible name say the same in words ({@link #accessibleName}).
 */
final class CollectionTileRenderer extends JComponent implements ListCellRenderer<CollectionModel.Entry> {
    static final int SPRITE = 40, CELL_WIDTH = 72, PAD = Tokens.XS;
    private CollectionModel.Entry entry;
    private boolean selected, focused;

    CollectionTileRenderer() { setOpaque(false); }

    @Override public Component getListCellRendererComponent(JList<? extends CollectionModel.Entry> list, CollectionModel.Entry value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        entry = value;
        selected = isSelected;
        focused = cellHasFocus;
        setToolTipText(value == null ? null : accessibleName(value));
        return this;
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics caption = getFontMetrics(Type.caption());
        return new Dimension(CELL_WIDTH, PAD + SPRITE + Sprites.WELL + Tokens.XS + caption.getHeight() + PAD);
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (entry == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g.setColor(Tokens.color(Tokens.Role.SELECTION));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
            EnchantInfo enchant = entry.front().enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : entry.front().enchant();
            Icon icon = ItemSlot.icon(Sprites.sprite(entry.itemId(), SPRITE), HaulView.tierLabel(entry.front()), ItemSlot.State.ITEM, SPRITE, enchant);
            icon.paintIcon(this, g, (getWidth() - icon.getIconWidth()) / 2, PAD);
            g.setFont(Type.caption());
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            FontMetrics metrics = g.getFontMetrics();
            String count = "×" + entry.count();
            g.drawString(count, (getWidth() - metrics.stringWidth(count)) / 2, PAD + icon.getIconHeight() + Tokens.XS + metrics.getAscent());
            if (focused) {
                g.setColor(Tokens.color(Tokens.Role.ACCENT));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
        } finally {
            g.dispose();
        }
    }

    /** "Synthetic Seal, 3 drops, best Legendary": the name, the count, and the front drop's rarity when enchanted. */
    static String accessibleName(CollectionModel.Entry entry) {
        EnchantInfo enchant = entry.front().enchant();
        return Sprites.name(entry.itemId()) + ", " + entry.count() + (entry.count() == 1 ? " drop" : " drops")
            + (enchant.enchanted() ? ", best " + enchant.rarity().label : "");
    }
}
```

`src/main/java/tomato/gui/loot/explore/CollectionLevel.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFacts;
import tomato.history.archive.Cancellation;

/**
 * Explore's Collection, the trophy cabinet: every item ever looted, as sprites with drop counts on shelves (UTs, STs, Tiered,
 * Potions, Other), most-dropped first. A shelf shows {@value #SHELF_LIMIT} items, then "Show all N". The search keeps the items whose
 * name contains it. Enter, Space or a double-click on a tile opens that item ({@link #onOpenItem}). Bags are read off the EDT on
 * {@link #reload} (each time Collection is shown; closed sessions come from the catalog's cache); only the newest read applies.
 * EDT only, except the reads.
 */
public final class CollectionLevel extends JPanel implements AutoCloseable {
    static final int SHELF_LIMIT = 60;
    static final String LOADING = "Loading your collection…", EMPTY = "Items appear here once capture saves loot bags.",
        NO_MATCH = "No item name matches the search.";

    private final LootCatalog.Reader catalog;
    private final Executor worker;
    private final IntFunction<String> names;
    private final JTextField search = new JTextField(18);
    private final KitText summary = KitText.caption(" ");
    private final JTextArea status = ContentStyle.wrappingText(LOADING);
    private final JPanel shelves = new JPanel() {
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }   // the page scrolls the shelves, never squeezes them
    };
    private final Set<CollectionModel.Kind> expanded = EnumSet.noneOf(CollectionModel.Kind.class);
    private List<LootFacts.Bag> bags;
    private CollectionModel model;
    private IntConsumer openItem = id -> { };
    private long generation;
    private Cancellation cancel = new Cancellation();
    private boolean closed;

    /** The production cabinet over {@code catalog}, reading on its own daemon worker; names come from the game's assets. */
    public static CollectionLevel production(LootCatalog.Reader catalog) {
        return new CollectionLevel(catalog, daemon("RealmShark loot collection"), Sprites::name);
    }

    CollectionLevel(LootCatalog.Reader catalog, Executor worker, IntFunction<String> names) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Collection on the EDT");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.names = Objects.requireNonNull(names, "names");
        setName("loot-collection");
        setOpaque(false);
        search.setName("loot-collection-search");
        search.putClientProperty("JTextField.placeholderText", "Search items");
        search.getAccessibleContext().setAccessibleName("Search items");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { render(); }
            public void removeUpdate(DocumentEvent e) { render(); }
            public void changedUpdate(DocumentEvent e) { render(); }
        });
        summary.setName("loot-collection-summary");
        status.setName("loot-collection-status");
        status.setFocusable(false);
        shelves.setName("loot-collection-shelves");
        shelves.setOpaque(false);
        shelves.setLayout(new BoxLayout(shelves, BoxLayout.Y_AXIS));
        JPanel controls = new WrapRow(search, summary);
        JScrollPane page = ContentStyle.page(KitLayouts.stack(Tokens.S, controls, status), shelves, null);
        page.setName("loot-collection-scroll");
        page.getVerticalScrollBar().setUnitIncrement(32);
        add(page, BorderLayout.CENTER);
        render();
    }

    /** What opening a tile runs, with its item ID. */
    public void onOpenItem(IntConsumer action) { openItem = Objects.requireNonNull(action, "action"); }
    /** The cabinet shown now (null before the first read). */
    public CollectionModel model() { return model; }
    JTextField search() { return search; }
    JTextArea status() { return status; }

    /** Reads every saved bag again, off the EDT; the newest read's bags replace the shown ones. EDT. */
    public void reload() {
        if (closed) return;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        if (bags == null) { status.setText(LOADING); status.setVisible(true); }
        try {
            worker.execute(() -> {
                List<LootFacts.Bag> read = null;
                String failure = null;
                try { read = catalog.bags(token); }
                catch (CancellationException cancelled) { return; }
                catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
                List<LootFacts.Bag> done = read;
                String why = failure;
                SwingUtilities.invokeLater(() -> apply(ticket, done, why));
            });
        } catch (RejectedExecutionException shutDown) { /* closed */ }
    }

    private void apply(long ticket, List<LootFacts.Bag> read, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null) {
            status.setText("Your collection could not be read: " + failure);
            status.setVisible(true);
            return;
        }
        bags = read;
        render();
    }

    /** EDT: the shelves for the read bags and the search. */
    private void render() {
        if (bags == null) return;
        model = CollectionModel.of(bags, search.getText(), names);
        shelves.removeAll();
        for (CollectionModel.Shelf shelf : model.shelves()) {
            String id = shelf.kind().name().toLowerCase(Locale.ROOT);
            SectionHeader header = new SectionHeader(shelf.kind().label + " · " + shelf.entries().size());
            header.setName("loot-collection-header-" + id);
            TileList<CollectionModel.Entry> tiles = new TileList<>("loot-collection-shelf-" + id, new CollectionTileRenderer(),
                entry -> String.valueOf(entry.itemId()), CollectionTileRenderer::accessibleName);
            boolean all = expanded.contains(shelf.kind()) || shelf.entries().size() <= SHELF_LIMIT;
            tiles.setItems(all ? shelf.entries() : shelf.entries().subList(0, SHELF_LIMIT));
            tiles.onOpen(entry -> openItem.accept(entry.itemId()));
            tiles.getAccessibleContext().setAccessibleDescription("Enter or Space opens the item's drops");
            stack(header);
            stack(tiles);
            if (!all) {
                KitButton more = KitButton.ghost("Show all " + shelf.entries().size());
                more.setName("loot-collection-more-" + id);
                more.addActionListener(e -> { expanded.add(shelf.kind()); render(); });
                stack(more);
            }
        }
        summary.setText(model.kinds() + (model.kinds() == 1 ? " item · " : " items · ") + model.drops() + (model.drops() == 1 ? " drop" : " drops"));
        boolean none = model.shelves().isEmpty();
        status.setText(none ? (search.getText().isBlank() ? EMPTY : NO_MATCH) : " ");
        status.setVisible(none);
        shelves.revalidate();
        shelves.repaint();
    }

    private void stack(JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        shelves.add(part);
    }

    static ThreadPoolExecutor daemon(String name) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);
        return worker;
    }

    /** Stops the reads. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
```

`CollectionLevel` and `ItemLevel` both need `RunsLevel`'s private `message(Throwable)`. Move it into a small package-private helper, `src/main/java/tomato/gui/loot/explore/RunsLevelMessages.java`:

```java
package tomato.gui.loot.explore;

/** The words a failed read shows: the root cause's message, else its class name. */
final class RunsLevelMessages {
    private RunsLevelMessages() {}
    static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
```

`RunsLevel` then calls `RunsLevelMessages.message(...)` and drops its own copy. `WrapRow` is `tomato.gui.history.WrapRow`, which `RunFeedView` already uses for its search row.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.loot.explore.CollectionLevelTest --tests tomato.gui.loot.explore.RunsLevelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/CollectionTileRenderer.java src/main/java/tomato/gui/loot/explore/CollectionLevel.java src/main/java/tomato/gui/loot/explore/RunsLevelMessages.java src/main/java/tomato/gui/loot/explore/RunsLevel.java src/test/java/tomato/gui/loot/explore/CollectionLevelTest.java
git commit -m "Add Explore's Collection: every looted item as sprites on shelves"
```

---

### Task 5: `ItemLevel` — one item's drops

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/ItemLevel.java`
- Create: `src/test/java/tomato/gui/loot/explore/ItemLevelTest.java`

**Interfaces:**
- Consumes:
  - from Task 3: `LootCatalog.Reader`, `ItemHistory`
  - `NotableDropRenderer(ZoneId, LongSupplier)` and `NotableDropRenderer.accessibleName` (Task 1)
  - `TileList`, `ItemSlot`, `HaulView.tierLabel`, `CollectionLevel.daemon`, `RunsLevelMessages.message`
- Produces:
  - `public final class ItemLevel extends JPanel implements AutoCloseable`
  - `static ItemLevel production(LootCatalog.Reader)`
  - package-private `ItemLevel(LootCatalog.Reader, Executor, ZoneId)`
  - `void open(int itemId)`, `int itemId()`, `ItemHistory model()`, `void onOpenRun(Consumer<VisitRef>)`, `void close()`
  - `static final int DROP_LIMIT = 200`
  - package-private `JTextArea status()`
  - Names: `loot-item`, `loot-item-slot`, `loot-item-name`, `loot-item-summary`, `loot-item-note`, `loot-item-drops` (a `TileList<Notable>`), `loot-item-status`

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/ItemLevelTest.java`:

```java
package tomato.gui.loot.explore;

import java.time.ZoneId;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.TileList;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** One item's drops over a fake catalog: the header, the cards newest first, opening a drop's run, the newest read winning. */
public class ItemLevelTest {
    static final VisitRef RUN = new VisitRef("00000000-0000-4000-8000-000000000001", "v1");

    @Test public void theHeaderAndTheDropsAndADropOpensItsRun() throws Exception {
        List<LootFacts.Bag> bags = List.of(new LootFacts.Bag("s", 100, false, "White", RUN, List.of(ut(1, 2)), "Lost Halls", "Synthetic boss"),
            bag(200, ut(1, 3), potion(4)));
        ItemLevel level = edt(() -> new ItemLevel(cancel -> bags, Runnable::run, ZoneId.of("UTC")));
        List<VisitRef> opened = new ArrayList<>();
        edt(() -> { level.onOpenRun(opened::add); level.open(1); return null; });
        edt(() -> null);
        edt(() -> {
            assertEquals(1, level.itemId());
            assertEquals("2 drops · 1 Rare · 1 Legendary", named(level, "loot-item-summary", JLabel.class).getText());
            @SuppressWarnings("unchecked") TileList<HighlightsModel.Notable> drops = named(level, "loot-item-drops", TileList.class);
            assertEquals(List.of(200L, 100L), drops.items().stream().map(HighlightsModel.Notable::time).toList());
            drops.setSelectedIndex(1);
            Object key = drops.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
            drops.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(drops, 0, null));
            assertEquals(List.of(RUN), opened);
            drops.setSelectedIndex(0);
            drops.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(drops, 0, null));
            assertEquals("A drop without a run opens nothing", 1, opened.size());
            return null;
        });
        edt(() -> { level.close(); return null; });
    }

    @Test public void onlyTheNewestItemApplies() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ItemLevel level = edt(() -> new ItemLevel(cancel -> List.of(bag(100, ut(1, null), st(2))), queued::add, ZoneId.of("UTC")));
        edt(() -> { level.open(1); level.open(2); for (Runnable task : List.copyOf(queued)) task.run(); return null; });
        edt(() -> null);
        assertEquals(2, (int) edt(() -> level.model().itemId()));
        edt(() -> { level.close(); return null; });
    }

    @Test public void aFailedReadSaysWhy() throws Exception {
        ItemLevel level = edt(() -> new ItemLevel(cancel -> { throw new java.io.IOException("synthetic read failure"); }, Runnable::run, ZoneId.of("UTC")));
        edt(() -> { level.open(1); return null; });
        edt(() -> null);
        assertEquals("This item's drops could not be read: synthetic read failure", edt(() -> level.status().getText()));
        edt(() -> { level.close(); return null; });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.ItemLevelTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement** `src/main/java/tomato/gui/loot/explore/ItemLevel.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.loot.NotableDropRenderer;
import tomato.gui.loot.haul.HaulView;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFacts;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;

/**
 * Explore's item history: one item's every saved drop, newest first, as Highlights' drop cards (sprite in its bag's well, type
 * and rarity chips, time · area, "Not linked to a run"). The header shows the item's best drop, its name and "12 drops · 3 Rare".
 * At most {@value #DROP_LIMIT} cards show, with a note when there are more. Enter, Space or a double-click on a card with a run opens
 * that run ({@link #onOpenRun}). Reads off the EDT; only the newest {@link #open} applies. EDT only, except the reads.
 */
public final class ItemLevel extends JPanel implements AutoCloseable {
    static final int DROP_LIMIT = 200;
    static final String LOADING = "Loading this item's drops…", NONE = "No saved drop of this item.";

    private final LootCatalog.Reader catalog;
    private final Executor worker;
    private final ZoneId zone;
    private final ItemSlot slot = new ItemSlot(48);
    private final KitText name = KitText.emphasis(" "), summary = KitText.caption(" "), note = KitText.caption(" ");
    private final JTextArea status = ContentStyle.wrappingText(LOADING);
    private final TileList<HighlightsModel.Notable> drops;
    private ItemHistory model;
    private long capturedAt = System.currentTimeMillis();
    private int itemId;
    private Consumer<VisitRef> openRun = ref -> { };
    private long generation;
    private Cancellation cancel = new Cancellation();
    private boolean closed;

    public static ItemLevel production(LootCatalog.Reader catalog) {
        return new ItemLevel(catalog, CollectionLevel.daemon("RealmShark loot item"), ZoneId.systemDefault());
    }

    ItemLevel(LootCatalog.Reader catalog, Executor worker, ZoneId zone) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the item history on the EDT");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.zone = Objects.requireNonNull(zone, "zone");
        setName("loot-item");
        setOpaque(false);
        slot.setName("loot-item-slot");
        name.setName("loot-item-name");
        summary.setName("loot-item-summary");
        note.setName("loot-item-note");
        note.setVisible(false);
        status.setName("loot-item-status");
        status.setFocusable(false);
        drops = new TileList<>("loot-item-drops", new NotableDropRenderer(zone, () -> capturedAt), HighlightsModel.Notable::key,
            drop -> NotableDropRenderer.accessibleName(drop, zone, capturedAt));
        drops.getAccessibleContext().setAccessibleDescription("Enter or Space opens the drop's run");
        drops.onOpen(drop -> { if (drop.visit() != null) openRun.accept(drop.visit()); });
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.M, 0));
        header.setOpaque(false);
        header.add(slot);
        header.add(KitLayouts.stack(0, name, summary));
        JScrollPane page = ContentStyle.page(KitLayouts.stack(Tokens.S, header, note, status), drops, null);
        page.setName("loot-item-scroll");
        page.getVerticalScrollBar().setUnitIncrement(32);
        add(page, BorderLayout.CENTER);
    }

    public int itemId() { return itemId; }
    /** The shown history (null before the first read applies). */
    public ItemHistory model() { return model; }
    /** What opening a drop that recorded a run runs, with that exact run. */
    public void onOpenRun(Consumer<VisitRef> action) { openRun = Objects.requireNonNull(action, "action"); }
    JTextArea status() { return status; }

    /** Shows {@code id}'s drops: its name at once, the drops once read. EDT. */
    public void open(int id) {
        if (closed) return;
        itemId = id;
        name.setText(Sprites.name(id));
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        if (model == null || model.itemId() != id) {
            status.setText(LOADING);
            status.setVisible(true);
            drops.setItems(List.of());
            summary.setText(" ");
            note.setVisible(false);
            slot.setItem(id, "");
        }
        try {
            worker.execute(() -> {
                ItemHistory read = null;
                String failure = null;
                try { read = ItemHistory.of(id, catalog.bags(token)); }
                catch (CancellationException cancelled) { return; }
                catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
                ItemHistory done = read;
                String why = failure;
                SwingUtilities.invokeLater(() -> apply(ticket, done, why));
            });
        } catch (RejectedExecutionException shutDown) { /* closed */ }
    }

    private void apply(long ticket, ItemHistory read, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null) {
            status.setText("This item's drops could not be read: " + failure);
            status.setVisible(true);
            return;
        }
        model = read;
        capturedAt = System.currentTimeMillis();
        LootFacts.Item front = read.front();
        if (front != null) {
            EnchantInfo enchant = front.enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : front.enchant();
            slot.setItem(read.itemId(), HaulView.tierLabel(front), enchant);
        }
        summary.setText(read.summary());
        List<HighlightsModel.Notable> shown = read.count() > DROP_LIMIT ? read.drops().subList(0, DROP_LIMIT) : read.drops();
        drops.setItems(shown);
        note.setText("Showing the newest " + DROP_LIMIT + " of " + read.count() + " drops");
        note.setVisible(read.count() > DROP_LIMIT);
        status.setText(read.count() == 0 ? NONE : " ");
        status.setVisible(read.count() == 0);
        revalidate();
        repaint();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.explore.ItemLevelTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/ItemLevel.java src/test/java/tomato/gui/loot/explore/ItemLevelTest.java
git commit -m "Add Explore's item history: every drop of one item as cards"
```

---

### Task 6: `ExplorePictures` — breadcrumb, entry switch and levels

**Files:**
- Modify: `src/main/java/tomato/gui/loot/explore/RunsLevel.java`:
  - add `onShown(Runnable)`, which runs at the end of `openRun`, `openUnlinked`, `applyRun` and `applyUnlinked`;
  - add `VisitRef shownRun()`, which returns the drawn run.
- Create: `src/main/java/tomato/gui/loot/explore/ExplorePictures.java`
- Create: `src/test/java/tomato/gui/loot/explore/ExplorePicturesTest.java`

**Interfaces:**
- Consumes: from Tasks 2, 4 and 5, `RunsLevel`, `CollectionLevel`, `ItemLevel` and `Breadcrumb`; `SegmentedControl` (its buttons are `<name>-0` and `<name>-1`)
- Produces:
  - `public final class ExplorePictures extends JPanel implements AutoCloseable`
  - `enum Level { RUNS, COLLECTION, ITEM }`
  - `record State(Level level, VisitRef run, boolean unlinked, int itemId, Level itemFrom)`
  - `static ExplorePictures production(Supplier<SessionStore>)`
  - package-private `ExplorePictures(RunsLevel, CollectionLevel, ItemLevel, IntFunction<String> names, Function<String,String>, BiConsumer<String,String>)`
  - Navigation: `showRuns()`, `showCollection()`, `openItem(int)`, `openRun(VisitRef)`, `openUnlinked()`
  - Queries: `Level level()`, `int itemId()`, `VisitRef selectedRun()`, `boolean showingUnlinked()`, `State state()`, `void restore(State)`, `List<String> path()`
  - Callbacks: `onOpenItem(IntConsumer)`, `onOpenRun(Consumer<VisitRef>)`, `onOpenRecap(Consumer<VisitRef>)`
  - Parts: `runs()`, `collection()`, `item()`, `close()`
  - `static int itemOf(String variantKey)`, `static final String ENTRY_KEY = "ui.loot.explore.entry"`
  - Names: `loot-explore-pictures`, `loot-explore-path` (the `Breadcrumb`), `loot-explore-entry` (the `SegmentedControl`)

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/ExplorePicturesTest.java`:

```java
package tomato.gui.loot.explore;

import java.time.ZoneId;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** The Pictures frame: the remembered entry, the breadcrumb at every level, items and runs opening across levels, state round trips. */
public class ExplorePicturesTest {
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<ExplorePictures> made = new ArrayList<>();

    @After public void release() throws Exception { edt(() -> { for (ExplorePictures pictures : made) pictures.close(); return null; }); }

    ExplorePictures pictures() throws Exception {
        RunsLevelTest.FakeLoader loader = new RunsLevelTest.FakeLoader();
        loader.runs.put(RunFixtures.A1, RunsLevelTest.haul(RunFixtures.A1, "Synthetic Halls"));
        LootCatalog.Reader catalog = cancel -> List.of(bag(100, ut(1, 2), potion(4)));
        ExplorePictures pictures = edt(() -> new ExplorePictures(new RunsLevel(RunFeedView.picker(() -> null), loader, Runnable::run),
            new CollectionLevel(catalog, Runnable::run, id -> NAMES.getOrDefault(id, "Synthetic item " + id)),
            new ItemLevel(catalog, Runnable::run, ZoneId.of("UTC")), id -> NAMES.getOrDefault(id, "Synthetic item " + id), prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        made.add(pictures);
        return pictures;
    }

    @Test public void theEntryIsRememberedAndThePathFollowsTheLevel() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals(List.of("Runs"), pictures.path());
            named(pictures, "loot-explore-entry-1", AbstractButton.class).doClick();
            assertEquals(ExplorePictures.Level.COLLECTION, pictures.level());
            assertEquals(List.of(ExplorePictures.ENTRY_KEY + "=collection"), writes);
            assertEquals(List.of("Collection"), pictures.path());
            return null;
        });
        ExplorePictures again = pictures();
        assertEquals("The entry is remembered", ExplorePictures.Level.COLLECTION, edt(again::level));
    }

    @Test public void anItemOpensFromEitherWayInAndThePathLeadsBack() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> { pictures.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        edt(() -> {
            assertEquals(List.of("Runs", "Synthetic Halls · " + timeOf(1L)), pictures.path());
            pictures.openItem(1);
            return null;
        });
        edt(() -> null);
        edt(() -> {
            assertEquals(ExplorePictures.Level.ITEM, pictures.level());
            assertEquals(3, pictures.path().size());
            assertEquals("Synthetic Seal", pictures.path().get(2));
            named(pictures, "loot-explore-path-1", AbstractButton.class).doClick();   // the run crumb
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals(RunFixtures.A1, pictures.selectedRun());

            pictures.showCollection();
            pictures.openItem(4);
            assertEquals(List.of("Collection", "Potion of Life"), pictures.path());
            return null;
        });
    }

    @Test public void aHaulsItemClickOpensItsItem() throws Exception {
        ExplorePictures pictures = pictures();
        List<Integer> opened = new ArrayList<>();
        edt(() -> { pictures.onOpenItem(opened::add); return null; });
        assertEquals(101, ExplorePictures.itemOf("101/2/0"));
        assertEquals(8, ExplorePictures.itemOf("8/null/null"));
        edt(() -> { pictures.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        edt(() -> {
            tomato.gui.kit.ItemSlot slot = (tomato.gui.kit.ItemSlot) named(pictures, "loot-haul-grid", JPanel.class).getComponent(0);
            slot.dispatchEvent(new java.awt.event.MouseEvent(slot, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            assertEquals(List.of(101), opened);
            return null;
        });
    }

    @Test public void stateRoundTripsThroughEveryLevel() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            pictures.openUnlinked();
            ExplorePictures.State unlinked = pictures.state();
            pictures.showCollection();
            pictures.openItem(1);
            ExplorePictures.State item = pictures.state();
            pictures.restore(unlinked);
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertTrue(pictures.showingUnlinked());
            pictures.restore(item);
            assertEquals(ExplorePictures.Level.ITEM, pictures.level());
            assertEquals(1, pictures.itemId());
            assertEquals(List.of("Collection", "Synthetic Seal"), pictures.path());
            return null;
        });
    }

    private static String timeOf(long epochMillis) {
        return tomato.gui.modern.DisplayFormat.formatTimestamp(java.time.Instant.ofEpochMilli(epochMillis), tomato.gui.modern.DisplayFormat.TimestampMode.TIME);
    }
}
```

`RunsLevelTest.haul(ref, map)` builds a header with `entered = 1L`, so the run crumb reads `"<map> · <time of 1L>"`.

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.ExplorePicturesTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

In `RunsLevel`, add:

```java
    private Runnable shownListener = () -> { };
    /** What runs whenever what the level shows changes: a run asked for or drawn, or "Loot outside runs". */
    public void onShown(Runnable listener) { shownListener = Objects.requireNonNull(listener, "listener"); }
    /** The run whose haul is drawn now; null while the status or "Loot outside runs" shows. */
    public VisitRef shownRun() { return shown; }
```

Then call `shownListener.run();` as the last statement of `openRun`, of `openUnlinked`, and of `applyRun` and `applyUnlinked` on every path that passes the ticket check. That includes the failure and unavailable paths.

`src/main/java/tomato/gui/loot/explore/ExplorePictures.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.*;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.modern.DisplayFormat;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * Explore's Pictures: a breadcrumb ({@code Runs › Lost Halls · 21:40 › Synthetic Seal}) and the way in (Runs | Collection,
 * remembered in {@link #ENTRY_KEY}) above three levels: Runs ({@link RunsLevel}), Collection ({@link CollectionLevel}) and one
 * item's drops ({@link ItemLevel}). An item opens from a haul or a Collection tile and a run from a drop card; in production those
 * go through the navigator ({@link #onOpenItem}, {@link #onOpenRun}), so Back walks back through levels. {@link #state} and
 * {@link #restore} carry the level, the run (or "Loot outside runs") and the item. EDT only.
 */
public final class ExplorePictures extends JPanel implements AutoCloseable {
    public static final String ENTRY_KEY = "ui.loot.explore.entry", RUNS_ENTRY = "runs", COLLECTION_ENTRY = "collection";

    public enum Level { RUNS, COLLECTION, ITEM }

    /** Where Pictures is: the level, the run or "Loot outside runs" of Runs, and the item with the level it was opened from. */
    public record State(Level level, VisitRef run, boolean unlinked, int itemId, Level itemFrom) {}

    private final RunsLevel runs;
    private final CollectionLevel collection;
    private final ItemLevel item;
    private final IntFunction<String> names;
    private final BiConsumer<String, String> write;
    private final Breadcrumb path = new Breadcrumb("loot-explore-path");
    private final SegmentedControl entry = new SegmentedControl("loot-explore-entry", "Runs", "Collection");
    private final CardLayout layout = new CardLayout();
    private final JPanel body = new JPanel(layout);
    private Level level = Level.RUNS, itemFrom = Level.RUNS;
    private int itemId;
    private IntConsumer openItem = this::openItem;
    private Consumer<VisitRef> openRun = this::openRun;

    /** The production Pictures over saved history from {@code store}: one catalog shared by Collection and the item history. */
    public static ExplorePictures production(Supplier<SessionStore> store) {
        LootCatalog.Reader catalog = LootCatalog.over(store);
        return new ExplorePictures(RunsLevel.production(store), CollectionLevel.production(catalog), ItemLevel.production(catalog),
            Sprites::name, PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As {@link #production} with built levels, the item names the breadcrumb shows, and the preference store (tests). */
    ExplorePictures(RunsLevel runs, CollectionLevel collection, ItemLevel item, IntFunction<String> names, Function<String, String> read,
                    BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Explore's Pictures on the EDT");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.item = Objects.requireNonNull(item, "item");
        this.names = Objects.requireNonNull(names, "names");
        Objects.requireNonNull(read, "read");
        this.write = Objects.requireNonNull(write, "write");
        setName("loot-explore-pictures");
        setOpaque(false);
        body.setOpaque(false);
        body.add(runs, Level.RUNS.name());
        body.add(collection, Level.COLLECTION.name());
        body.add(item, Level.ITEM.name());
        entry.getAccessibleContext().setAccessibleName("Explore by");
        entry.onChange(index -> {
            write.accept(ENTRY_KEY, index == 1 ? COLLECTION_ENTRY : RUNS_ENTRY);
            if (index == 1) showCollection(); else showRuns();
        });
        JPanel header = new JPanel(new BorderLayout(Tokens.M, 0));
        header.setOpaque(false);
        header.add(path, BorderLayout.CENTER);
        header.add(entry, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        runs.onShown(this::refreshPath);
        runs.onOpenItem(key -> openItem.accept(itemOf(key)));
        collection.onOpenItem(id -> openItem.accept(id));
        item.onOpenRun(ref -> openRun.accept(ref));
        if (COLLECTION_ENTRY.equals(read.apply(ENTRY_KEY))) showCollection(); else show(Level.RUNS);
    }

    public Level level() { return level; }
    public int itemId() { return itemId; }
    public VisitRef selectedRun() { return runs.selectedRun(); }
    public boolean showingUnlinked() { return runs.showingUnlinked(); }
    public RunsLevel runs() { return runs; }
    public CollectionLevel collection() { return collection; }
    public ItemLevel item() { return item; }
    /** The breadcrumb's labels. */
    public List<String> path() { return path.labels(); }

    /** What opening an item runs, with its ID (default: {@link #openItem} here; production routes it, so Back returns). */
    public void onOpenItem(IntConsumer action) { openItem = Objects.requireNonNull(action, "action"); }
    /** What opening a drop's run runs (default: {@link #openRun} here; production routes it). */
    public void onOpenRun(Consumer<VisitRef> action) { openRun = Objects.requireNonNull(action, "action"); }
    /** What a haul's "Open run" runs (the run's recap in production). */
    public void onOpenRecap(Consumer<VisitRef> action) { runs.onOpenRun(action); }

    public void showRuns() { show(Level.RUNS); }

    /** Shows Collection, reading saved bags again (closed sessions come from the cache). */
    public void showCollection() {
        show(Level.COLLECTION);
        collection.reload();
    }

    /** Shows item {@code id}'s drops; the breadcrumb leads back to the level it was opened from. */
    public void openItem(int id) {
        if (level != Level.ITEM) itemFrom = level;
        itemId = id;
        item.open(id);
        show(Level.ITEM);
    }

    /** Shows Runs on {@code ref}'s haul. */
    public void openRun(VisitRef ref) {
        show(Level.RUNS);
        runs.openRun(ref);
    }

    /** Shows Runs on "Loot outside runs". */
    public void openUnlinked() {
        show(Level.RUNS);
        runs.openUnlinked();
    }

    public State state() { return new State(level, runs.selectedRun(), runs.showingUnlinked(), itemId, itemFrom); }

    /** Goes back to {@code state}: Runs' run or "Loot outside runs" first, then the level (an item reads its drops again). */
    public void restore(State state) {
        Objects.requireNonNull(state, "state");
        if (state.unlinked()) runs.openUnlinked(); else if (state.run() != null) runs.openRun(state.run());
        switch (state.level()) {
            case RUNS -> show(Level.RUNS);
            case COLLECTION -> showCollection();
            case ITEM -> {
                itemFrom = state.itemFrom();
                itemId = state.itemId();
                item.open(itemId);
                show(Level.ITEM);
            }
        }
    }

    /** The item ID of a haul's exact variant key ("id/slots/applied"). */
    static int itemOf(String variantKey) {
        int slash = variantKey.indexOf('/');
        return Integer.parseInt(slash < 0 ? variantKey : variantKey.substring(0, slash));
    }

    private void show(Level next) {
        level = next;
        layout.show(body, next.name());
        Level root = next == Level.ITEM ? itemFrom : next;
        entry.setSelected(root == Level.COLLECTION ? 1 : 0);
        refreshPath();
    }

    /** The breadcrumb for what shows: the way in, Runs' run or "Loot outside runs", and the item. */
    private void refreshPath() {
        List<Breadcrumb.Crumb> crumbs = new ArrayList<>();
        Level root = level == Level.ITEM ? itemFrom : level;
        if (root == Level.COLLECTION) crumbs.add(new Breadcrumb.Crumb("Collection", this::showCollection));
        else {
            crumbs.add(new Breadcrumb.Crumb("Runs", this::showRuns));
            String run = runLabel();
            if (run != null) crumbs.add(new Breadcrumb.Crumb(run, this::showRuns));
        }
        if (level == Level.ITEM) crumbs.add(new Breadcrumb.Crumb(names.apply(itemId), null));
        path.setPath(crumbs);
    }

    /** "Lost Halls · 21:40" for the drawn run, "Loot outside runs", "Run" while one loads, or null when none is chosen. */
    private String runLabel() {
        if (runs.showingUnlinked()) return "Loot outside runs";
        VisitRef selected = runs.selectedRun();
        if (selected == null) return null;
        HaulModel.Header header = selected.equals(runs.shownRun()) && runs.haul().model() != null ? runs.haul().model().header() : null;
        if (header == null) return "Run";
        return header.entered() == null ? header.mapName()
            : header.mapName() + " · " + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(header.entered()), DisplayFormat.TimestampMode.TIME);
    }

    /** Releases every level's reads. EDT. */
    @Override public void close() {
        runs.close();
        collection.close();
        item.close();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.loot.explore.ExplorePicturesTest --tests tomato.gui.loot.explore.RunsLevelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/RunsLevel.java src/main/java/tomato/gui/loot/explore/ExplorePictures.java src/test/java/tomato/gui/loot/explore/ExplorePicturesTest.java
git commit -m "Frame Explore's Pictures with a breadcrumb, a Runs | Collection switch and the item level"
```

---

### Task 7: `LootExplorePage` hosts `ExplorePictures`, and adds an item route

**Files:**
- Modify: `src/main/java/tomato/gui/loot/explore/LootExplorePage.java`
- Modify: `src/test/java/tomato/gui/loot/explore/LootExplorePageTest.java`

**Interfaces:**
- Consumes: from Task 6, `ExplorePictures` (`state`, `restore`, `openRun`, `openItem`, `selectedRun`, `showingUnlinked`, `onOpenItem`, `onOpenRun`, `onOpenRecap`, `close`)
- Produces:
  - `record LootExplorePage.ExploreItem(int itemId)`, a route payload
  - `RouteTarget itemTarget()`
  - `ExplorePictures pictures()`
  - `onOpenRun(Consumer<VisitRef>)`: the haul's "Open run", i.e. the recap
  - `onOpenItem(IntConsumer)`
  - `onOpenDrop(Consumer<VisitRef>)`: a drop card's run
  - `variantQuery` is removed

- [ ] **Step 1: Update the tests first.** In `LootExplorePageTest`:

1. `page(JComponent table)` builds `ExplorePictures`, not `RunsLevel`:

```java
    private LootExplorePage page(JComponent table) throws Exception {
        LootCatalog.Reader catalog = cancel -> java.util.List.of(CollectionModelTest.bag(100, CollectionModelTest.ut(1, 2)));
        LootExplorePage page = edt(() -> new LootExplorePage(table, new ExplorePictures(
                new RunsLevel(RunFeedView.picker(() -> null), new RunsLevelTest.FakeLoader(), Runnable::run),
                new CollectionLevel(catalog, Runnable::run, id -> "Synthetic item " + id),
                new ItemLevel(catalog, Runnable::run, java.time.ZoneId.of("UTC")), id -> "Synthetic item " + id, key -> null, (key, value) -> { }),
            prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        pages.add(page);
        return page;
    }
```

2. In `routesBringTheViewTheyNeedAndBackRestoresIt` and the "Loot outside runs" Back test, `page.pictures().selectedRun()`, `.openUnlinked()` and `.showingUnlinked()` keep working, because `ExplorePictures` has them. Replace the query route's `LootExplorePage.variantQuery("7/2/1")` with:

```java
            ArchiveQuery<LootQuery.Facets, LootQuery.Sort> occurrences = LootQuery.initial(LootQuery.View.OCCURRENCES, SessionStore.ALL);
            Route query = Route.to(Destination.LOOT).withQuery(occurrences);
```

3. Delete `anItemOpensItsOccurrencesInEverySession` and add:

```java
    @Test public void anItemRouteOpensTheItemLevelAndBackReturns() throws Exception {
        LootExplorePage page = page(new Table());
        edt(() -> {
            RouteTarget items = page.itemTarget();
            page.showTable();
            Object back = items.captureState();
            Route route = Route.to(Destination.LOOT).withPayload(new LootExplorePage.ExploreItem(1));
            assertTrue(items.accepts(route));
            assertFalse("Other Loot routes are not the item target's", items.accepts(Route.to(Destination.LOOT)));
            items.open(route);
            assertFalse(page.tableShown());
            assertEquals(ExplorePictures.Level.ITEM, page.pictures().level());
            assertEquals(1, page.pictures().itemId());
            items.restoreState(back);
            assertTrue("Back returns to Table", page.tableShown());
            assertEquals(ExplorePictures.Level.RUNS, page.pictures().level());
            return null;
        });
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE --tests tomato.gui.loot.explore.LootExplorePageTest`
Expected: compilation FAIL (no `ExplorePictures` constructor argument, no `itemTarget`, no `ExploreItem`).

- [ ] **Step 3: Implement.** In `LootExplorePage`:
  - Replace the `RunsLevel pictures` field and constructor parameter with `ExplorePictures pictures`. The public constructor builds `ExplorePictures.production(store)`.
  - `RouteState` becomes `record RouteState(boolean table, ExplorePictures.State pictures, Object inner) {}`.
  - The `routes(target)` wrapper changes:
    - capture: `new RouteState(tableShown, pictures.state(), target.captureState())`;
    - the visit route opens `showPictures(); pictures.openRun(route.visit);`;
    - restore calls the shared helper below with `target`.
  - Add the shared restore helper, the item payload and the item target:

```java
    /** A Loot route payload: open Explore's Pictures on this item's drops. */
    public record ExploreItem(int itemId) {}

    /** LOOT routes carrying {@link ExploreItem}: Pictures on that item; Back restores the view and Pictures' level. */
    public RouteTarget itemTarget() {
        return new RouteTarget() {
            @Override public Destination destination() { return Destination.LOOT; }
            @Override public boolean accepts(Route route) { return route.destination == Destination.LOOT && route.payload instanceof ExploreItem; }
            @Override public Object captureState() { return new RouteState(tableShown, pictures.state(), null); }
            @Override public void open(Route route) {
                if (!accepts(route)) throw new IllegalArgumentException("Unsupported Explore item route: " + route);
                showPictures();
                pictures.openItem(((ExploreItem) route.payload).itemId());
            }
            @Override public void restoreState(Object state) { restore(state, null); }
        };
    }

    /** Shows the saved view, puts Pictures back, then restores {@code target}'s own state when it has one. */
    private void restore(Object state, RouteTarget target) {
        if (!(state instanceof RouteState saved)) throw new IllegalArgumentException("Not a Loot › Explore state");
        if (saved.table()) showTable(); else showPictures();
        pictures.restore(saved.pictures());
        if (target != null && saved.inner() != null) target.restoreState(saved.inner());
    }
```

  - The open actions:

```java
    /** What a haul's "Open run" runs (the run's recap in production). */
    public void onOpenRun(Consumer<VisitRef> action) { pictures.onOpenRecap(action); }
    /** What opening an item runs (an {@link ExploreItem} route in production, so Back returns). */
    public void onOpenItem(java.util.function.IntConsumer action) { pictures.onOpenItem(action); }
    /** What opening a drop's run runs (an exact-run Loot route in production). */
    public void onOpenDrop(Consumer<VisitRef> action) { pictures.onOpenRun(action); }
```

  - Delete `variantQuery`. Remove the imports that become unused (`LootQuery`, `ArchiveQuery`, `SessionStore` if unused).
  - Update the class Javadoc: Pictures is Runs | Collection with item history, and an `ExploreItem` route opens an item.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests 'tomato.gui.loot.explore.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/LootExplorePage.java src/test/java/tomato/gui/loot/explore/LootExplorePageTest.java
git commit -m "Host Explore's Pictures in the page and route items so Back walks back"
```

---

### Task 8: Wire it into the app, and document it

**Files:**
- Modify: `src/main/java/tomato/gui/TomatoGUI.java` (the `explorePage` registration and its open actions, added in P2)
- Modify: `docs/LOOT.md` (the Pictures paragraphs at the start of `## Explore`)

- [ ] **Step 1: Register the item target.** In the `if (lootArchive != null)` block, after registering `explore`, add:

```java
            if (explorePage != null) navigator.register(lootPage.routes(LootTab.EXPLORE, explorePage.itemTarget()));
```

- [ ] **Step 2: Replace the open actions.** In the `if (explorePage != null)` block next to `runsPage.feed().onOpen(...)`, replace P2's two lines with:

```java
            // A haul's "Open run" opens the run's recap; an item opens its drops and a drop its run, through the navigator so Back returns.
            explorePage.onOpenRun(visit -> navigator.open(tomato.gui.route.Route.to(Destination.RUN_RECAP).withVisit(visit)));
            explorePage.onOpenItem(id -> navigator.open(tomato.gui.route.Route.to(Destination.LOOT)
                .withPayload(new tomato.gui.loot.explore.LootExplorePage.ExploreItem(id))));
            explorePage.onOpenDrop(visit -> navigator.open(tomato.gui.route.Route.to(Destination.LOOT).withVisit(visit)));
```

- [ ] **Step 3: Document.** In `docs/LOOT.md`, replace the first Pictures paragraph (it starts "With saved history, Explore opens on **Pictures**") with:

```markdown
With saved history, Explore opens on **Pictures**. A breadcrumb above it says where you are (for example **Runs › Lost Halls · 21:40 › Marble Seal**); every part but the last leads back. The **Runs | Collection** switch beside it chooses the way in and is remembered (`ui.loot.explore.entry`).

- **Runs:** your saved runs on the left, newest first, with the Runs feed's search and filters, and the chosen run's loot on the right as the game draws it. The haul shows the portal, outcome and time, the best drop, one bag sprite per bag type with its count (white first), and the open bag as an 8-slot grid. **Loot outside runs** above the list shows bags that recorded no run, by session (the newest 10 sessions that have any). **Open run** opens the run's recap. A run still in progress is read again as its loot is saved (about every 30 s while Pictures shows).
- **Collection:** every item you have looted, as sprites with drop counts on shelves (**UTs**, **STs**, **Tiered**, **Potions**, **Other**), most-dropped first. Each item shows its best enchanted drop. A shelf shows 60 items, then **Show all N**. **Search items** keeps the items whose name matches.
- **An item** (a tile in Collection, or an item in a run's haul) opens its drops: its best drop, its name and "12 drops · 3 Rare · 1 Legendary", then every drop newest first, as Highlights' drop cards (at most 200). A drop that recorded a run opens that run. **Back** returns through the levels you came from.
```

Leave the second paragraph (the Pictures | Table switch and routes) as it is.

- [ ] **Step 4: Compile and run the focused tests**

Run: `GRADLE --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.kit.*'`
Expected: PASS, apart from the failures already on `main` in this environment (`LootHighlightsTest` ×2, `DungeonsSourceTest` timing).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/TomatoGUI.java docs/LOOT.md
git commit -m "Open items and drops from Explore's Pictures through the navigator"
```

---

### Task 9: Verification, launch and PR (coordinator)

- [ ] **Step 1: Run the focused suite**

Run: `GRADLE --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.stats.*'`
Expected: the only failures are the known environmental ones on `main`.

- [ ] **Step 2: Build and launch.** Run `GRADLE shadowJar`, but replace `test --tests <TEST>` with `shadowJar` in that command.

Check that RealmShark is not running. Copy the jar to the scratchpad. Launch it with `javaw -jar` from `C:\Users\dap\Downloads\RealmShark-realmshark`. Leave it running for the user.

The user checks Loot › Explore:
- the breadcrumb;
- Runs | Collection;
- Collection's shelves, "Show all" and search;
- an item from a tile and from a haul;
- a drop card opening its run;
- Back through the levels.

- [ ] **Step 3: Open the PR** titled "Loot Explore pictures P3: Collection, item history and breadcrumb". Link the spec and this plan, list the deviations above, and end the body with the Claude Code attribution line.
