# Loot Explore pictures — P2 (Explore Pictures + Runs level) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This repo's workflow:** the implementer (Codex via Orchestra) edits files only. The coordinator (Claude) runs every
> Gradle command, makes every commit, launches the app and opens the PR.

**Goal:** Loot › Explore opens on **Pictures**: saved runs on the left and the chosen run's full haul (P1's `HaulView` in Full mode) on the right. A **Pictures | Table** switch keeps today's tables one click away.

**Architecture:**
- `LootExplorePage` wraps the existing Loot workspace (Table, unchanged) and a new `RunsLevel` (Pictures) in a `CardLayout` with a remembered switch. It also wraps the workspace's route targets, so an exact-run route opens Pictures on that run and a query route opens Table.
- `RunsLevel` reuses the Runs tab's `RunFeedView` as a run picker (new picker mode) and reads each run's haul through the run recap's reader (`RunRecapBuilder`), via a small `RunHauls` adapter, on one worker thread with a generation guard.

**Tech Stack:** Java 17 (`--release 17`), Swing + FlatLaf, the project UI kit, JUnit 4, Gradle 7.6.4.

**Spec:** `docs/superpowers/specs/2026-10-01-loot-explore-pictures-design.md` (P2 = "Explore pictures frame + Runs level"). P1 (merged, PR #46) built `HaulModel`/`HaulView`/`BagSprites`.

### Deviations from the spec in this phase (approved with this plan)

- **Breadcrumb and the Runs | Dungeons | Collection switch move to P3.** A one-level breadcrumb and a one-option switch say nothing; both arrive with the second level.
- **Item clicks open Table** on Item occurrences of that exact item variant in every saved session, until P3 adds the Item level.
- **The view preference is `ui.loot.explore.view`** (as Runs uses `ui.runs.view`), not a key under `ux.archive.loot`. The switch shows in both Simple and Analyst, because Pictures is the main view.
- **"Loot outside runs"** shows bags that recorded no drop-time run (`LootFacts.Bag.visit() == null`, the same rule as Highlights' "Not linked to a run"), grouped by session, for the newest 10 sessions that have any.

## Global Constraints

- Java 17 language level only; no new dependencies.
- All Swing work on the EDT. Saved-history reads run off the EDT, on one daemon worker per component; only the newest request's result applies (a generation guard).
- Copy is sentence case, parts joined with `" · "`. Never present a guessed value: unknown facts are left out or explained.
- New component names use the `loot-explore-*` and `loot-runs-*` prefixes. P1's `loot-haul-*` names are unchanged.
- Fixtures are synthetic (`RunFixtures`, `HomeHistoryFixture`, `LootTestDrops`); no personal game history.
- Keyboard tests invoke bound actions or queued AWT events, never `java.awt.Robot`.
- Gradle (from the worktree root, Git Bash; the `.tools` folder lives in the primary checkout):
  ```bash
  export JAVA_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="C:/Users/dap/Downloads/RealmShark-realmshark/.tools/gradle-home"; ./gradlew.bat test --offline -PrealmSharkBuildDir=build-loot-p2 --tests <TEST>
  ```
  Written below as `GRADLE --tests <TEST>`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A route to a run the feed has not loaded** (older than the first 50 runs, another session, or no longer saved): its haul or its "unavailable" reason still shows, and nothing crashes. Covered by Task 4.
2. **Fast clicking or a slow read:** only the newest request's haul applies, and an older result never overwrites it. Covered by Task 4.
3. **Back after a route switched views:** Back restores Pictures or Table, the selected run, and the workspace's own state. A rejected route changes nothing. Covered by Task 5.
4. **A read failure:** shows one line saying why, never a blank pane, and Table still works. Covered by Task 4.
5. **No saved history open:** Explore stays the live dashboard alone, with no Pictures and no broken reads. Covered by Tasks 3 and 6.

---

### Task 1: `RunFeedView` picker mode and selection API

**Files:**
- Modify: `src/main/java/tomato/gui/runs/RunFeedView.java`
- Test: `src/test/java/tomato/gui/runs/RunFeedViewTest.java` (add one test)

**Interfaces:**
- Produces (all public, EDT only):
  - `static RunFeedView picker(Supplier<SessionStore> store)`
  - `void onSelect(Consumer<RunCardModel>)`
  - `void onLoaded(Consumer<List<RunCardModel>>)`
  - `boolean select(VisitRef)`
  - `void clearSelection()`
  - package-private constructor `RunFeedView(JComponent table, Supplier<Feed> feeds, ZoneId zone, DisplayModeModel mode, Function<String,String> read, BiConsumer<String,String> write, boolean picker)`

- [ ] **Step 1: Write the failing test.** Add it to `RunFeedViewTest`:

```java
    /** A picker (Loot › Explore's Runs) is the Cards view alone and reports what is loaded and what is selected. */
    @Test public void aPickerIsTheCardsAloneAndReportsLoadsAndSelections() throws Exception {
        Counting feed = scenario();
        RunFeedView view = edt(() -> new RunFeedView(new JPanel(), () -> feed, HomeHistoryFixture.ZONE, mode, prefs::get,
            (key, value) -> writes.add(key + "=" + value), true));
        views.add(view);
        List<List<VisitRef>> loads = new ArrayList<>();
        List<VisitRef> selected = new ArrayList<>();
        edt(() -> {
            view.onLoaded(cards -> loads.add(cards.stream().map(RunCardModel::ref).toList()));
            view.onSelect(card -> selected.add(card.ref()));
            return null;
        });
        load(view);
        await("the loaded runs", () -> !loads.isEmpty());
        edt(() -> {
            assertEquals(view.model().cards().stream().map(RunCardModel::ref).toList(), loads.get(0));
            assertTrue(view.select(RunFixtures.A1));
            assertEquals(List.of(RunFixtures.A1), selected);
            assertTrue("Selecting the selected card again reports nothing", view.select(RunFixtures.A1));
            assertEquals(1, selected.size());
            assertFalse("A run that is not loaded is not selected", view.select(new VisitRef(RunFixtures.A, "missing")));
            view.clearSelection();
            assertEquals("Clearing reports nothing", 1, selected.size());
            assertTrue("A cleared card can be selected again", view.select(RunFixtures.A1));
            assertEquals(2, selected.size());
            assertFalse("Simple: no Table view item", view.filterBar().overflow().item("Table view").isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertFalse("Analyst: no Cards/Table toggle", named(view, "run-feed-view-row", JComponent.class).isVisible());
            assertFalse(view.tableShown());
            assertTrue("A picker writes no view preference", writes.isEmpty());
            return null;
        });
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.runs.RunFeedViewTest`
Expected: compilation FAIL (no 7-argument constructor, `onLoaded`, `onSelect`, `select`, `clearSelection`).

- [ ] **Step 3: Implement.** Change `RunFeedView`:

1. Add fields next to `open`:

```java
    private final boolean picker;
    private Consumer<RunCardModel> select = card -> { };
    private Consumer<List<RunCardModel>> loaded = cards -> { };
```

2. The existing package-private six-argument constructor becomes a delegate. Its body moves unchanged into a new seven-argument constructor that assigns `this.picker = picker;` right after the `isEventDispatchThread` check:

```java
    /** As above with the reader, the zone cards are dated in, the display mode and the preference store (tests). */
    RunFeedView(JComponent table, Supplier<Feed> feeds, ZoneId zone, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write) {
        this(table, feeds, zone, mode, read, write, false);
    }

    /**
     * As above; a {@code picker} is the Cards view alone: no view toggle, no "Table view" item, and {@code read}/{@code write} are
     * not used for the view preference ({@link #picker(Supplier)}).
     */
    RunFeedView(JComponent table, Supplier<Feed> feeds, ZoneId zone, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write, boolean picker) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the run feed on the EDT");
        this.picker = picker;
        // ... the rest of the former constructor body, unchanged ...
    }
```

In the moved body, change the restore line `show(TABLE.equals(read.apply(VIEW_KEY)), false);` to:

```java
        show(!picker && TABLE.equals(read.apply(VIEW_KEY)), false);
```

3. Add the factory after the public constructor:

```java
    /**
     * A run picker over saved history from {@code store} (Loot › Explore's Runs): the Cards view alone, with no Table view, view
     * toggle or view preference. Selecting a card reports it ({@link #onSelect}).
     */
    public static RunFeedView picker(Supplier<SessionStore> store) {
        return new RunFeedView(new JPanel(), sources(store), ZoneId.systemDefault(), DisplayModeModel.application(),
            key -> null, (key, value) -> { }, true);
    }
```

4. In `modeChanged`, hide the picker's view controls:

```java
        viewRow.setVisible(analyst && !picker);
        viewItem.setVisible(!analyst && !picker);
```

5. Add the API after `onOpen`:

```java
    /** What selecting a card runs: a click, arrow keys, Tab into a day, or {@link #select}. A reload keeping the selection does not report it. */
    public void onSelect(Consumer<RunCardModel> action) { select = Objects.requireNonNull(action, "action"); }
    /** What runs after each read that applied new runs, with every loaded run, newest first. */
    public void onLoaded(Consumer<List<RunCardModel>> action) { loaded = Objects.requireNonNull(action, "action"); }

    /**
     * Selects {@code ref}'s card when it is loaded, scrolls it into view and reports it ({@link #onSelect}) unless it was already
     * selected; false when that run is not loaded. EDT.
     */
    public boolean select(VisitRef ref) {
        Objects.requireNonNull(ref, "ref");
        String key = ref.sessionId + "/" + ref.visitId;
        for (Section section : sections.values())
            for (RunCardModel card : section.list().items())
                if (key.equals(key(card))) { section.list().selectKey(key, true); return true; }
        return false;
    }

    /** Clears the selected card without reporting it. EDT. */
    public void clearSelection() {
        selecting = true;
        try { for (Section section : sections.values()) section.list().clearSelection(); } finally { selecting = false; }
    }
```

6. In `section(...)`, the list's selection listener reports the user's choice after clearing the other days:

```java
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || selecting || list.getSelectedValue() == null) return;
            selecting = true;   // one selected card in the whole feed
            try { for (Section other : sections.values()) if (other.list() != list) other.list().clearSelection(); } finally { selecting = false; }
            select.accept(list.getSelectedValue());
        });
```

7. In `apply(...)`, the success path ends by reporting the new runs. Replace the final `render();` of that method with:

```java
        render();
        loaded.accept(page.model().cards());
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.runs.RunFeedViewTest --tests tomato.gui.runs.RunsPageTest`
Expected: PASS (the existing feed tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/runs/RunFeedView.java src/test/java/tomato/gui/runs/RunFeedViewTest.java
git commit -m "Let the run feed act as a run picker that reports loads and selections"
```

---

### Task 2: `HaulView` says why a haul is empty

**Files:**
- Modify: `src/main/java/tomato/gui/loot/haul/HaulView.java`
- Test: `src/test/java/tomato/gui/loot/haul/HaulViewTest.java` (add one test)

**Interfaces:**
- Produces: `void show(HaulModel model, VisitRef run, String emptyReason)`. Null or blank keeps `EMPTY`. `show(model, run)` delegates with null.

- [ ] **Step 1: Write the failing test** in `HaulViewTest`:

```java
    @Test public void anEmptyHaulCanSayWhyItIsEmpty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HaulView view = new HaulView(HaulView.Mode.FULL);
            String why = "No loot bag was saved in this run's session, so its loot is unknown.";
            view.show(HaulModel.of(null, List.of()), null, why);
            assertEquals(why, text(view, "loot-haul-empty"));
            view.show(HaulModel.of(null, List.of()), null);
            assertEquals("The default comes back", HaulView.EMPTY, text(view, "loot-haul-empty"));
        });
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulViewTest`
Expected: compilation FAIL (no three-argument `show`).

- [ ] **Step 3: Implement.** In `HaulView`, rename the body of `show(HaulModel, VisitRef)` into a three-argument overload that first sets the empty text. The two-argument form delegates:

```java
    /** Draws {@code model} and opens its default group; {@code run} is the exact run it belongs to (null: no "Open run"). */
    public void show(HaulModel model, VisitRef run) { show(model, run, null); }

    /** As {@link #show(HaulModel, VisitRef)}; {@code emptyReason} replaces {@link #EMPTY} when there are no bags (null or blank keeps it). */
    public void show(HaulModel model, VisitRef run, String emptyReason) {
        empty.setText(emptyReason == null || emptyReason.isBlank() ? EMPTY : emptyReason);
        // ... the former body of show(HaulModel, VisitRef), unchanged ...
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `GRADLE --tests tomato.gui.loot.haul.HaulViewTest --tests tomato.gui.runs.RunRecapViewTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/haul/HaulView.java src/test/java/tomato/gui/loot/haul/HaulViewTest.java
git commit -m "Let the haul say why it has no bags"
```

---

### Task 3: `RunHauls` — reading a run's haul and loot outside runs

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/RunHauls.java`
- Create: `src/test/java/tomato/gui/loot/explore/RunHaulsTest.java`

**Interfaces:**
- Consumes:
  - `RunRecapBuilder(SessionStore, ZoneId, LongSupplier).build(VisitRef, String, Cancellation)`
  - `RunRecapModel.{ref(), available(), unavailable(), header(), loot()}`
  - `RunRecapModel.Header.{mapName(), portalId(), outcome(), entered(), durationMs(), character()}`
  - `RunOutcome.{label(), tone()}`
  - `LootFacts.read(SessionStore, List<SessionEntry>, String, Consumer<Bag>)`
  - `HaulModel.of`, `HaulModel.Bag`, `HaulModel.Header`
- Produces:
  - `record RunHauls.RunHaul(VisitRef ref, HaulModel haul, String emptyReason, String unavailable)`
  - `record RunHauls.UnlinkedSession(String sessionId, long started, List<HaulModel.Bag> bags)` with `int items()`
  - `interface RunHauls.Loader { RunHaul run(VisitRef, Cancellation) throws IOException; List<UnlinkedSession> unlinked(Cancellation) throws IOException; }`
  - `static Loader over(Supplier<SessionStore>)`
  - `static RunHaul of(RunRecapModel)`
  - `static HaulModel haul(RunRecapModel.Header, RunRecapModel.Loot)`
  - `static List<UnlinkedSession> unlinked(SessionStore, Cancellation)`
  - `static final int UNLINKED_SESSIONS = 10`

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/RunHaulsTest.java`:

```java
package tomato.gui.loot.explore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.runs.RunFixtures;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** Explore's reads over the synthetic Runs fixture: one run's haul through the recap's reader, and loot outside runs. */
public class RunHaulsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private SessionStore store;

    @Before public void write() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        store = new SessionStore(root, false, "fixture");
    }

    @After public void close() throws Exception { store.close(); }

    @Test public void aRunsHaulIsItsRecapsHeaderAndBags() throws Exception {
        RunHauls.RunHaul run = RunHauls.over(() -> store).run(RunFixtures.A1, new Cancellation());
        assertNull(run.unavailable());
        assertEquals(RunFixtures.A1, run.ref());
        assertEquals("Lost Halls", run.haul().header().mapName());
        assertEquals("Completed", run.haul().header().outcome());
        assertEquals("6 items in 2 bags · 1 UT · 1 ST · 2 potions", run.haul().tally());
        assertEquals(List.of("White", "Orange"), run.haul().shelf().stream().map(HaulModel.Shelf::bag).toList());
        assertEquals(502, run.haul().hero().item().id());
        assertEquals("Synthetic boss", run.haul().hero().dropper());
    }

    @Test public void aRunNotInSavedHistoryIsUnavailable() throws Exception {
        RunHauls.RunHaul run = RunHauls.over(() -> store).run(new VisitRef(RunFixtures.A, "missing"), new Cancellation());
        assertNotNull(run.unavailable());
        assertTrue(run.haul().shelf().isEmpty());
    }

    @Test public void lootOutsideRunsIsGroupedBySessionNewestFirst() throws Exception {
        List<RunHauls.UnlinkedSession> sessions = RunHauls.over(() -> store).unlinked(new Cancellation());
        assertEquals("Only session A has a bag without a recorded run", 1, sessions.size());
        assertEquals(RunFixtures.A, sessions.get(0).sessionId());
        assertEquals(List.of("Brown"), sessions.get(0).bags().stream().map(HaulModel.Bag::bag).toList());
        assertEquals(1, sessions.get(0).items());
    }

    @Test public void withoutSavedHistoryEveryReadSaysSo() throws Exception {
        RunHauls.Loader loader = RunHauls.over(() -> null);
        try { loader.run(RunFixtures.A1, new Cancellation()); fail("A run read needs saved history"); }
        catch (IOException expected) { assertEquals("Saved history is not open in this app run", expected.getMessage()); }
        try { loader.unlinked(new Cancellation()); fail("An unlinked read needs saved history"); }
        catch (IOException expected) { assertEquals("Saved history is not open in this app run", expected.getMessage()); }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.RunHaulsTest`
Expected: compilation FAIL, "cannot find symbol: RunHauls".

- [ ] **Step 3: Implement** `src/main/java/tomato/gui/loot/explore/RunHauls.java`:

```java
package tomato.gui.loot.explore;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.runs.RunRecapBuilder;
import tomato.gui.runs.RunRecapModel;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;

/**
 * What Loot › Explore's Runs level reads, off the EDT: one run's haul, through the run recap's reader so a run's loot reads the
 * same in Explore and in its recap, and the loot saved outside any run.
 */
public final class RunHauls {
    /** At most this many sessions are shown under "Loot outside runs", newest first. */
    public static final int UNLINKED_SESSIONS = 10;
    static final String NOT_OPEN = "Saved history is not open in this app run";

    /** One run's haul, or why it cannot be shown: {@code unavailable} (not in saved history), {@code emptyReason} (no bags). */
    public record RunHaul(VisitRef ref, HaulModel haul, String emptyReason, String unavailable) {
        public RunHaul { Objects.requireNonNull(ref, "ref"); Objects.requireNonNull(haul, "haul"); }
    }

    /** One saved session's bags that recorded no run, oldest first. */
    public record UnlinkedSession(String sessionId, long started, List<HaulModel.Bag> bags) {
        public UnlinkedSession { Objects.requireNonNull(sessionId, "sessionId"); bags = List.copyOf(bags); }
        public int items() {
            int count = 0;
            for (HaulModel.Bag bag : bags) count += bag.items().size();
            return count;
        }
    }

    /** How the Runs level reads ({@link #over} in production). Off the EDT only. */
    public interface Loader {
        RunHaul run(VisitRef ref, Cancellation cancel) throws IOException;
        List<UnlinkedSession> unlinked(Cancellation cancel) throws IOException;
    }

    private RunHauls() {}

    /** Reads saved history from {@code store}; while none is open, every read fails saying so. */
    public static Loader over(Supplier<SessionStore> store) {
        Objects.requireNonNull(store, "store");
        return new Loader() {
            @Override public RunHaul run(VisitRef ref, Cancellation cancel) throws IOException {
                return of(new RunRecapBuilder(open(store), ZoneId.systemDefault(), System::currentTimeMillis).build(ref, null, cancel));
            }
            @Override public List<UnlinkedSession> unlinked(Cancellation cancel) throws IOException { return RunHauls.unlinked(open(store), cancel); }
        };
    }

    private static SessionStore open(Supplier<SessionStore> store) throws IOException {
        SessionStore open = store.get();
        if (open == null) throw new IOException(NOT_OPEN);
        return open;
    }

    /** A built run recap as a haul: its header facts and its bags, or its unavailable reason. */
    public static RunHaul of(RunRecapModel recap) {
        if (!recap.available()) return new RunHaul(recap.ref(), HaulModel.of(null, List.of()), null, recap.unavailable());
        return new RunHaul(recap.ref(), haul(recap.header(), recap.loot()), recap.loot().reason(), null);
    }

    /** {@code header} and {@code loot} as a Full haul. */
    public static HaulModel haul(RunRecapModel.Header header, RunRecapModel.Loot loot) {
        HaulModel.Header facts = header == null ? null : new HaulModel.Header(header.mapName(), header.portalId(), header.outcome().label(),
            header.outcome().tone(), header.entered(), header.durationMs(), header.character());
        List<HaulModel.Bag> bags = new ArrayList<>();
        for (RunRecapModel.Loot.Bag bag : loot.bags()) bags.add(new HaulModel.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
        return HaulModel.of(facts, bags);
    }

    /**
     * The newest {@value #UNLINKED_SESSIONS} readable saved sessions holding bags recorded without a run (no drop-time visit),
     * newest first, each session's bags oldest first. Unreadable sessions are skipped; a loot file that cannot be read fails the read.
     */
    public static List<UnlinkedSession> unlinked(SessionStore store, Cancellation cancel) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);
        record Dated(SessionStore.SessionEntry entry, long started) {}
        List<Dated> sessions = new ArrayList<>();
        for (SessionStore.SessionEntry entry : catalog) if (entry.readable()) sessions.add(new Dated(entry, entry.session().started));
        sessions.sort(Comparator.comparingLong(Dated::started).reversed());
        List<UnlinkedSession> result = new ArrayList<>();
        for (Dated dated : sessions) {
            if (result.size() == UNLINKED_SESSIONS) break;
            cancel.check();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, catalog, dated.entry().id, bag -> { if (bag.visit() == null) bags.add(bag); });
            if (bags.isEmpty()) continue;
            bags.sort(Comparator.comparingLong(LootFacts.Bag::time));
            List<HaulModel.Bag> haul = new ArrayList<>();
            for (LootFacts.Bag bag : bags) haul.add(new HaulModel.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
            result.add(new UnlinkedSession(dated.entry().id, dated.started(), haul));
        }
        return result;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.explore.RunHaulsTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/RunHauls.java src/test/java/tomato/gui/loot/explore/RunHaulsTest.java
git commit -m "Read a run's haul through the recap's reader, and loot outside runs"
```

---

### Task 4: `RunsLevel` — run picker beside the full haul

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/RunsLevel.java`
- Create: `src/test/java/tomato/gui/loot/explore/RunsLevelTest.java`

**Interfaces:**
- Consumes:
  - from Task 1: `RunFeedView.picker`, `onSelect`, `onLoaded`, `onOpen`, `select`, `clearSelection`, `refresh`, `close`
  - `HaulView(Mode.FULL/COMPACT)`, `show(model, run, emptyReason)`, `onOpenItem`, `onOpenRun`
  - from Task 3: `RunHauls.Loader`, `RunHaul`, `UnlinkedSession`
  - `LootLine.section`
- Produces:
  - `public final class RunsLevel extends JPanel implements AutoCloseable`
  - `static RunsLevel production(Supplier<SessionStore>)`
  - package-private `RunsLevel(RunFeedView, RunHauls.Loader, Executor)`
  - `void openRun(VisitRef)`, `void openUnlinked()`, `VisitRef selectedRun()`, `boolean showingUnlinked()`
  - `RunFeedView feed()`, `HaulView haul()`
  - `void onOpenRun(Consumer<VisitRef>)`, `void onOpenItem(Consumer<String>)`, `void close()`
  - package-private `JTextArea status()`, `JComponent detailShown()`, `void loaded(List<RunCardModel>)`
  - Component names: `loot-runs`, `loot-runs-split`, `loot-runs-unlinked` (button), `loot-runs-status`, `loot-runs-detail`, `loot-runs-unlinked-list`, `loot-runs-unlinked-title`, `loot-runs-unlinked-note`, `loot-runs-unlinked-session`

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/RunsLevelTest.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.kit.Tokens;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.runs.RunCardModel;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.runs.RunOutcome;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** The Runs level over the synthetic Runs fixture and a fake haul reader: newest first, picking, routes, stale reads, failures, loot outside runs. */
public class RunsLevelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final List<RunsLevel> levels = new ArrayList<>();
    private SessionStore store;

    /** A haul reader the test fills; unknown runs read as unavailable. */
    static final class FakeLoader implements RunHauls.Loader {
        final Map<VisitRef, RunHauls.RunHaul> runs = new HashMap<>();
        final List<VisitRef> reads = new CopyOnWriteArrayList<>();
        volatile List<RunHauls.UnlinkedSession> unlinked = List.of();
        volatile IOException fail;
        @Override public RunHauls.RunHaul run(VisitRef ref, Cancellation cancel) throws IOException {
            reads.add(ref);
            if (fail != null) throw fail;
            RunHauls.RunHaul run = runs.get(ref);
            return run != null ? run : new RunHauls.RunHaul(ref, HaulModel.of(null, List.of()), null, "Linked visit unavailable");
        }
        @Override public List<RunHauls.UnlinkedSession> unlinked(Cancellation cancel) throws IOException {
            if (fail != null) throw fail;
            return unlinked;
        }
    }

    static RunHauls.RunHaul haul(VisitRef ref, String map) {
        HaulModel model = HaulModel.of(new HaulModel.Header(map, 0, "Completed", Tokens.Tone.GOOD, 1L, 60_000L, null),
            List.of(new HaulModel.Bag("White", 1L, "Synthetic boss", List.of(new LootFacts.Item(101, true, false, false, false)))));
        return new RunHauls.RunHaul(ref, model, null, null);
    }

    @Before public void write() throws Exception {
        Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        store = new SessionStore(root, false, "fixture");
    }

    @After public void release() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (RunsLevel level : levels) level.close(); });
        store.close();
    }

    private RunsLevel level(RunFeedView feed, RunHauls.Loader loader, java.util.concurrent.Executor worker) throws Exception {
        RunsLevel level = edt(() -> new RunsLevel(feed, loader, worker));
        levels.add(level);
        return level;
    }

    @Test public void theNewestRunOpensFirstAndPickingACardOpensItsHaul() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.B4, haul(RunFixtures.B4, "Synthetic B4"));
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "Synthetic A1"));
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> store)), loader, Runnable::run);
        edt(() -> { level.feed().refresh(); return null; });
        await("the newest run's haul", () -> RunFixtures.B4.equals(level.selectedRun()) && level.detailShown() == level.haul());
        assertEquals(List.of(RunFixtures.B4), loader.reads);
        assertEquals("Synthetic B4", edt(() -> level.haul().model().header().mapName()));
        edt(() -> { assertTrue(level.feed().select(RunFixtures.A1)); return null; });
        edt(() -> null);   // the read's result applies on a later EDT turn
        assertEquals(RunFixtures.A1, edt(level::selectedRun));
        assertEquals("Synthetic A1", edt(() -> level.haul().model().header().mapName()));
        assertEquals(List.of(RunFixtures.B4, RunFixtures.A1), loader.reads);
    }

    @Test public void aRouteToARunTheFeedHasNotLoadedStillShowsWhatIsKnown() throws Exception {
        FakeLoader loader = new FakeLoader();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> store)), loader, Runnable::run);
        VisitRef gone = new VisitRef(RunFixtures.A, "gone");
        edt(() -> { level.openRun(gone); return null; });
        edt(() -> null);
        assertEquals(gone, edt(level::selectedRun));
        assertSame(edt(level::status), edt(level::detailShown));
        assertEquals("Linked visit unavailable", edt(() -> level.status().getText()));
    }

    @Test public void onlyTheNewestRequestsHaulApplies() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.runs.put(RunFixtures.A1, haul(RunFixtures.A1, "First"));
        loader.runs.put(RunFixtures.A2, haul(RunFixtures.A2, "Second"));
        List<Runnable> queued = new ArrayList<>();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, queued::add);
        edt(() -> {
            level.openRun(RunFixtures.A1);
            level.openRun(RunFixtures.A2);
            assertEquals(RunsLevel.LOADING, level.status().getText());
            for (Runnable task : List.copyOf(queued)) task.run();   // the older read finishes too, in order
            return null;
        });
        edt(() -> null);
        assertEquals("Second", edt(() -> level.haul().model().header().mapName()));
    }

    @Test public void aFailedReadSaysWhy() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.fail = new IOException("synthetic read failure");
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        edt(() -> { level.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        assertSame(edt(level::status), edt(level::detailShown));
        assertEquals("This run's loot could not be read: synthetic read failure", edt(() -> level.status().getText()));
    }

    @Test public void aRunInProgressIsReadAgainWhenNewRunsLoad() throws Exception {
        FakeLoader loader = new FakeLoader();
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        RunCardModel live = new RunCardModel(RunFixtures.B4, "Lost Halls", "Lost Halls", 0, RunOutcome.IN_PROGRESS, 1L, null, null, null,
            "No combat recording", List.of(), 0, "", null, null, null);
        edt(() -> { level.openRun(RunFixtures.B4); level.loaded(List.of(live)); return null; });
        edt(() -> null);
        assertEquals("Opened, then read again", List.of(RunFixtures.B4, RunFixtures.B4), loader.reads);
    }

    @Test public void lootOutsideRunsIsShownBySession() throws Exception {
        FakeLoader loader = new FakeLoader();
        loader.unlinked = List.of(new RunHauls.UnlinkedSession(RunFixtures.A, RunFixtures.NOW - 3_600_000L,
            List.of(new HaulModel.Bag("Brown", RunFixtures.NOW - 60_000L, null, List.of(new LootFacts.Item(701, false, false, false, false))))));
        RunsLevel level = level(edt(() -> RunFeedView.picker(() -> null)), loader, Runnable::run);
        edt(() -> { named(level, "loot-runs-unlinked", AbstractButton.class).doClick(); return null; });
        edt(() -> null);
        assertTrue(edt(level::showingUnlinked));
        assertNull(edt(level::selectedRun));
        List<JLabel> sessions = edt(() -> all(level, "loot-runs-unlinked-session", JLabel.class));
        assertEquals(1, sessions.size());
        assertTrue(sessions.get(0).getText().startsWith("Session started "));
        assertTrue(sessions.get(0).getText().endsWith(" · 1 item in 1 bag"));

        loader.unlinked = List.of();
        edt(() -> { level.openUnlinked(); return null; });
        edt(() -> null);
        assertEquals(RunsLevel.NO_UNLINKED, edt(() -> level.status().getText()));
    }

    // ---- helpers ----

    @FunctionalInterface interface Checked<T> { T get() throws Exception; }

    static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    static void await(String what, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for " + what);
    }

    static <T extends Component> List<T> all(Container root, String name, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) found.addAll(all(container, name, type));
        }
        return found;
    }

    static <T extends Component> T named(Container root, String name, Class<T> type) {
        List<T> found = all(root, name, type);
        assertFalse("No " + name, found.isEmpty());
        return found.get(0);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.RunsLevelTest`
Expected: compilation FAIL, "cannot find symbol: RunsLevel".

- [ ] **Step 3: Implement** `src/main/java/tomato/gui/loot/explore/RunsLevel.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.loot.haul.HaulView;
import tomato.gui.loot.haul.LootLine;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.runs.RunCardModel;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunOutcome;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;

/**
 * Loot › Explore's Runs level: saved runs on the left (the run feed as a picker, newest first, with "Loot outside runs" above it)
 * and the chosen run's haul on the right, drawn Full. The newest run opens when the first runs load; picking a card, Enter on
 * one, or {@link #openRun} (a route) opens another, including a run the feed has not loaded. A run still in progress is read again
 * whenever the feed reads new runs (about every 30 s while it shows). Reads run on one worker; only the newest request's result
 * applies. EDT only, except the reads.
 */
public final class RunsLevel extends JPanel implements AutoCloseable {
    static final String CHOOSE = "Choose a run to see what it dropped.", LOADING = "Loading this run's loot…",
        UNLINKED_LOADING = "Loading loot outside runs…", NO_UNLINKED = "Every saved bag was recorded inside a run.";

    private final RunFeedView feed;
    private final RunHauls.Loader loader;
    private final Executor worker;
    private final HaulView haul = new HaulView(HaulView.Mode.FULL);
    private final JTextArea status = ContentStyle.wrappingText(CHOOSE);
    private final JPanel unlinked = new JPanel();
    private final JPanel detail = new JPanel(new BorderLayout());
    private final KitButton outside = KitButton.ghost("Loot outside runs");
    private Consumer<String> openItem = key -> { };
    /** The run asked for (shown or loading); null while none, or while "Loot outside runs" shows. */
    private VisitRef selected;
    /** The run whose haul is drawn now; null while the status or "Loot outside runs" shows. */
    private VisitRef shown;
    private boolean showingUnlinked, closed;
    private long generation;
    private Cancellation cancel = new Cancellation();

    /** The production level over saved history from {@code store}, reading on its own daemon worker. */
    public static RunsLevel production(Supplier<SessionStore> store) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "RealmShark loot explore");
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);
        return new RunsLevel(RunFeedView.picker(store), RunHauls.over(store), worker);
    }

    /** The level around {@code feed} (a picker), reading hauls through {@code loader} on {@code worker} (tests pass their own). */
    RunsLevel(RunFeedView feed, RunHauls.Loader loader, Executor worker) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Runs level on the EDT");
        this.feed = Objects.requireNonNull(feed, "feed");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.worker = Objects.requireNonNull(worker, "worker");
        setName("loot-runs");
        setOpaque(false);
        status.setName("loot-runs-status");
        status.setFocusable(false);
        unlinked.setName("loot-runs-unlinked-list");
        unlinked.setOpaque(false);
        unlinked.setLayout(new BoxLayout(unlinked, BoxLayout.Y_AXIS));
        detail.setName("loot-runs-detail");
        detail.setOpaque(false);
        detail.setBorder(BorderFactory.createEmptyBorder(0, Tokens.M, 0, 0));
        showDetail(status);

        outside.setName("loot-runs-unlinked");
        outside.setToolTipText("Bags that recorded no run, by session");
        outside.addActionListener(e -> openUnlinked());
        JPanel left = new JPanel(new BorderLayout(0, Tokens.S));
        left.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        top.setOpaque(false);
        top.add(outside);
        left.add(top, BorderLayout.NORTH);
        left.add(feed, BorderLayout.CENTER);
        JScrollPane right = new JScrollPane(detail);
        right.setName("loot-runs-detail-scroll");
        right.setBorder(BorderFactory.createEmptyBorder());
        right.setOpaque(false);
        right.getViewport().setOpaque(false);
        right.getVerticalScrollBar().setUnitIncrement(32);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setName("loot-runs-split");
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        feed.onSelect(card -> openRun(card.ref()));
        feed.onOpen(this::openRun);
        feed.onLoaded(this::loaded);
    }

    public RunFeedView feed() { return feed; }
    public HaulView haul() { return haul; }
    /** The run whose haul shows or is loading; null while none does, or while "Loot outside runs" shows. */
    public VisitRef selectedRun() { return showingUnlinked ? null : selected; }
    public boolean showingUnlinked() { return showingUnlinked; }
    /** What the haul's "Open run" runs (the run's recap in production). */
    public void onOpenRun(Consumer<VisitRef> action) { haul.onOpenRun(action); }
    /** What clicking an item runs, with its exact variant key, in the run's haul and under "Loot outside runs". */
    public void onOpenItem(Consumer<String> action) {
        openItem = Objects.requireNonNull(action, "action");
        haul.onOpenItem(action);
    }

    /** Shows {@code ref}'s haul and selects its card when it is loaded; the haul shows even when it is not. Asking again for the shown run does nothing. */
    public void openRun(VisitRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (closed) return;
        boolean same = ref.equals(selected) && !showingUnlinked;
        selected = ref;
        showingUnlinked = false;
        feed.select(ref);   // reports back through onSelect with the same run, which returns here as "same"
        if (!same) load(ref);
    }

    /** Shows the loot saved outside any run, by session; the run cards lose their selection. */
    public void openUnlinked() {
        if (closed) return;
        showingUnlinked = true;
        selected = null;
        shown = null;
        feed.clearSelection();
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        status.setText(UNLINKED_LOADING);
        showDetail(status);
        submit(() -> {
            List<RunHauls.UnlinkedSession> sessions = null;
            String failure = null;
            try { sessions = loader.unlinked(token); }
            catch (CancellationException cancelled) { return; }
            catch (Exception | Error failed) { failure = message(failed); }
            List<RunHauls.UnlinkedSession> done = sessions;
            String why = failure;
            SwingUtilities.invokeLater(() -> applyUnlinked(ticket, done, why));
        });
    }

    /** The feed applied new runs: opens the newest when nothing is chosen, and reads the chosen run again while it is in progress. */
    void loaded(List<RunCardModel> cards) {
        if (closed || showingUnlinked) return;
        if (selected == null) {
            if (!cards.isEmpty()) openRun(cards.get(0).ref());
            return;
        }
        feed.select(selected);   // a route opened before its card loaded selects it now
        for (RunCardModel card : cards)
            if (card.ref().equals(selected) && card.outcome() == RunOutcome.IN_PROGRESS) { load(selected); return; }
    }

    JTextArea status() { return status; }
    /** What the right side shows now: the status, the haul or the "Loot outside runs" list. */
    JComponent detailShown() { return detail.getComponentCount() == 0 ? null : (JComponent) detail.getComponent(0); }

    private void load(VisitRef ref) {
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        if (!ref.equals(shown)) {   // a refresh of the drawn run keeps it in place
            status.setText(LOADING);
            showDetail(status);
        }
        submit(() -> {
            RunHauls.RunHaul run = null;
            String failure = null;
            try { run = loader.run(ref, token); }
            catch (CancellationException cancelled) { return; }
            catch (Exception | Error failed) { failure = message(failed); }
            RunHauls.RunHaul done = run;
            String why = failure;
            SwingUtilities.invokeLater(() -> applyRun(ticket, ref, done, why));
        });
    }

    private void applyRun(long ticket, VisitRef ref, RunHauls.RunHaul run, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null || run.unavailable() != null) {
            status.setText(failure != null ? "This run's loot could not be read: " + failure : run.unavailable());
            shown = null;
            showDetail(status);
            return;
        }
        haul.show(run.haul(), ref, run.emptyReason());
        shown = ref;
        showDetail(haul);
    }

    private void applyUnlinked(long ticket, List<RunHauls.UnlinkedSession> sessions, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null || sessions.isEmpty()) {
            status.setText(failure != null ? "Loot outside runs could not be read: " + failure : NO_UNLINKED);
            showDetail(status);
            return;
        }
        unlinked.removeAll();
        KitText title = KitText.emphasis("Loot outside runs");
        title.setName("loot-runs-unlinked-title");
        KitText note = KitText.caption("Bags that recorded no run, by session, newest first"
            + (sessions.size() == RunHauls.UNLINKED_SESSIONS ? " (the newest " + RunHauls.UNLINKED_SESSIONS + " sessions)" : ""));
        note.setName("loot-runs-unlinked-note");
        stack(unlinked, title);
        stack(unlinked, note);
        for (RunHauls.UnlinkedSession session : sessions) {
            KitText header = KitText.body("Session started "
                + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(session.started()), DisplayFormat.TimestampMode.FULL)
                + " · " + LootLine.section(session.items(), session.bags().size(), ""));
            header.setName("loot-runs-unlinked-session");
            HaulView bags = new HaulView(HaulView.Mode.COMPACT);
            bags.onOpenItem(key -> openItem.accept(key));
            bags.show(HaulModel.of(null, session.bags()), null);
            header.setBorder(BorderFactory.createEmptyBorder(Tokens.M, 0, Tokens.XS, 0));
            stack(unlinked, header);
            stack(unlinked, bags);
        }
        showDetail(unlinked);
    }

    private static void stack(JPanel column, JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        column.add(part);
    }

    private void showDetail(JComponent part) {
        if (detail.getComponentCount() == 1 && detail.getComponent(0) == part) return;
        detail.removeAll();
        detail.add(part, BorderLayout.NORTH);
        detail.revalidate();
        detail.repaint();
    }

    private void submit(Runnable task) {
        try { worker.execute(task); } catch (RejectedExecutionException shutDown) { /* closed: nothing applies */ }
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** Stops the reads and the feed's worker. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        feed.close();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.explore.RunsLevelTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/RunsLevel.java src/test/java/tomato/gui/loot/explore/RunsLevelTest.java
git commit -m "Add Explore's Runs level: the run picker beside the full haul"
```

---

### Task 5: `LootExplorePage` — Pictures | Table and routes

**Files:**
- Create: `src/main/java/tomato/gui/loot/explore/LootExplorePage.java`
- Create: `src/test/java/tomato/gui/loot/explore/LootExplorePageTest.java`

**Interfaces:**
- Consumes: from Task 4, `RunsLevel` (`openRun`, `selectedRun`, `onOpenRun`, `onOpenItem`, `close`); `SegmentedControl(String, String...)` (its buttons are named `<name>-0` and `<name>-1`, and `setSelected` does not fire `onChange`); `RouteTarget`; `Route`; `LootQuery.initial`; `SessionStore.ALL`
- Produces:
  - `public final class LootExplorePage extends JPanel implements AutoCloseable`
  - `LootExplorePage(JComponent table, Supplier<SessionStore> store)`
  - package-private `LootExplorePage(JComponent, RunsLevel, Function<String,String>, BiConsumer<String,String>)`
  - `boolean tableShown()`, `void showPictures()`, `void showTable()`
  - `RunsLevel pictures()`, `JComponent table()`
  - `void onOpenRun(Consumer<VisitRef>)`, `void onOpenItem(Consumer<String>)`
  - `RouteTarget routes(RouteTarget)`
  - `static ArchiveQuery<LootQuery.Facets, LootQuery.Sort> variantQuery(String variantKey)`
  - `static final String VIEW_KEY = "ui.loot.explore.view"`
  - Component names: `loot-explore`, `loot-explore-view-row`, `loot-explore-view` (buttons `loot-explore-view-0` and `loot-explore-view-1`)

- [ ] **Step 1: Write the failing test** at `src/test/java/tomato/gui/loot/explore/LootExplorePageTest.java`:

```java
package tomato.gui.loot.explore;

import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.RunsLevelTest.edt;
import static tomato.gui.loot.explore.RunsLevelTest.named;

/** Explore's Pictures | Table switch, its remembered choice, the routes it wraps and closing. */
public class LootExplorePageTest {
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<LootExplorePage> pages = new ArrayList<>();

    @After public void release() throws Exception { edt(() -> { for (LootExplorePage page : pages) page.close(); return null; }); }

    /** A workspace stand-in that counts closes. */
    static final class Table extends JPanel implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }

    /** A Loot target that records what it opened and restored, and can reject routes. */
    static final class Target implements RouteTarget {
        final List<Route> opened = new ArrayList<>();
        Object state = "inner-1", restored;
        RuntimeException fail;
        @Override public Destination destination() { return Destination.LOOT; }
        @Override public Object captureState() { return state; }
        @Override public void open(Route route) { if (fail != null) throw fail; opened.add(route); }
        @Override public void restoreState(Object value) { restored = value; }
    }

    private LootExplorePage page(JComponent table) throws Exception {
        LootExplorePage page = edt(() -> new LootExplorePage(table, new RunsLevel(RunFeedView.picker(() -> null), new RunsLevelTest.FakeLoader(), Runnable::run),
            prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        pages.add(page);
        return page;
    }

    @Test public void picturesShowFirstAndTheChoiceIsRemembered() throws Exception {
        LootExplorePage page = page(new Table());
        edt(() -> {
            assertFalse("Explore opens on Pictures", page.tableShown());
            assertTrue("Restoring the default writes nothing", writes.isEmpty());
            named(page, "loot-explore-view-1", AbstractButton.class).doClick();
            assertTrue(page.tableShown());
            assertEquals(List.of(LootExplorePage.VIEW_KEY + "=table"), writes);
            return null;
        });
        LootExplorePage again = page(new Table());
        edt(() -> {
            assertTrue("The choice is remembered", again.tableShown());
            assertEquals("Restoring writes nothing", 1, writes.size());
            return null;
        });
    }

    @Test public void routesBringTheViewTheyNeedAndBackRestoresIt() throws Exception {
        LootExplorePage page = page(new Table());
        Target target = new Target();
        edt(() -> {
            RouteTarget routes = page.routes(target);
            page.showTable();
            routes.open(Route.to(Destination.LOOT).withVisit(RunFixtures.A1));
            assertFalse("An exact run opens Pictures", page.tableShown());
            assertEquals(RunFixtures.A1, page.pictures().selectedRun());
            assertTrue("The workspace is left as it was", target.opened.isEmpty());
            Object back = routes.captureState();

            Route query = Route.to(Destination.LOOT).withQuery(LootExplorePage.variantQuery("7/2/1"));
            routes.open(query);
            assertTrue("A query opens Table", page.tableShown());
            assertEquals(List.of(query), target.opened);

            routes.restoreState(back);
            assertFalse("Back returns to Pictures", page.tableShown());
            assertEquals(RunFixtures.A1, page.pictures().selectedRun());
            assertEquals("inner-1", target.restored);

            target.fail = new IllegalArgumentException("rejected");
            try { routes.open(query); fail("The target rejected the route"); } catch (IllegalArgumentException expected) { }
            assertFalse("A rejected route leaves Pictures showing", page.tableShown());
            return null;
        });
    }

    @Test public void anItemOpensItsOccurrencesInEverySession() {
        ArchiveQuery<LootQuery.Facets, LootQuery.Sort> query = LootExplorePage.variantQuery("7/2/1");
        assertEquals(LootQuery.View.OCCURRENCES, query.facets().view);
        assertEquals("7/2/1", query.facets().variant);
        assertEquals(SessionStore.ALL, query.scope());
    }

    @Test public void closingReleasesThePicturesAndTheWorkspaceOnce() throws Exception {
        Table table = new Table();
        LootExplorePage page = page(table);
        edt(() -> { page.close(); page.close(); return null; });
        assertEquals(1, table.closes);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE --tests tomato.gui.loot.explore.LootExplorePageTest`
Expected: compilation FAIL, "cannot find symbol: LootExplorePage".

- [ ] **Step 3: Implement** `src/main/java/tomato/gui/loot/explore/LootExplorePage.java`:

```java
package tomato.gui.loot.explore;

import java.awt.*;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * Loot › Explore with saved history: Pictures (the Runs level, {@link RunsLevel}) and Table (the Loot workspace, unchanged: its
 * views, filters, saved views and exports), behind a Pictures | Table switch above both, remembered in {@link #VIEW_KEY}
 * (Pictures by default; restoring the preference only selects). Routes reach the view they need through {@link #routes}: an
 * exact run opens Pictures on that run, a query opens Table, and Back restores the view, the run and the workspace's state.
 * Without saved history Explore stays the live dashboard alone (the coordinator does not build this page). EDT only.
 */
public final class LootExplorePage extends JPanel implements AutoCloseable {
    public static final String VIEW_KEY = "ui.loot.explore.view", PICTURES = "pictures", TABLE = "table";

    private final JComponent table;
    private final RunsLevel pictures;
    private final BiConsumer<String, String> write;
    private final CardLayout layout = new CardLayout();
    private final JPanel views = new JPanel(layout);
    private final SegmentedControl viewSwitch = new SegmentedControl("loot-explore-view", "Pictures", "Table");
    private boolean tableShown, closed;

    /** The production page around {@code table} (the Loot workspace), with Pictures over saved history from {@code store}. */
    public LootExplorePage(JComponent table, Supplier<SessionStore> store) {
        this(table, RunsLevel.production(store), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As above with the built Pictures and the preference store (tests). */
    LootExplorePage(JComponent table, RunsLevel pictures, Function<String, String> read, BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Loot › Explore on the EDT");
        this.table = Objects.requireNonNull(table, "table");
        this.pictures = Objects.requireNonNull(pictures, "pictures");
        Objects.requireNonNull(read, "read");
        this.write = Objects.requireNonNull(write, "write");
        setName("loot-explore");
        setOpaque(false);
        views.setOpaque(false);
        views.add(pictures, PICTURES);
        views.add(table, TABLE);
        viewSwitch.getAccessibleContext().setAccessibleName("Explore view");
        viewSwitch.setToolTipText("Pictures shows your runs' loot as the game draws it; Table holds every loot view and filter");
        viewSwitch.onChange(index -> show(index == 1, true));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        row.setName("loot-explore-view-row");
        row.setOpaque(false);
        row.add(viewSwitch);
        add(row, BorderLayout.NORTH);
        add(views, BorderLayout.CENTER);
        show(TABLE.equals(read.apply(VIEW_KEY)), false);
    }

    public boolean tableShown() { return tableShown; }
    /** Explicit navigation to Pictures: brings it forward and remembers it. */
    public void showPictures() { show(false, true); }
    /** Explicit navigation to Table: brings it forward and remembers it. */
    public void showTable() { show(true, true); }
    public RunsLevel pictures() { return pictures; }
    public JComponent table() { return table; }
    /** What a haul's "Open run" runs (the run's recap in production). */
    public void onOpenRun(Consumer<VisitRef> action) { pictures.onOpenRun(action); }
    /** What clicking an item runs, with its exact variant key (Table on {@link #variantQuery} in production). */
    public void onOpenItem(Consumer<String> action) { pictures.onOpenItem(action); }

    /** Item occurrences of exactly {@code variantKey} ("id/slots/applied") in every saved session, newest first. */
    public static ArchiveQuery<LootQuery.Facets, LootQuery.Sort> variantQuery(String variantKey) {
        ArchiveQuery<LootQuery.Facets, LootQuery.Sort> query = LootQuery.initial(LootQuery.View.OCCURRENCES, SessionStore.ALL);
        LootQuery.Facets facets = query.facets();
        facets.variant = Objects.requireNonNull(variantKey, "variantKey");
        facets.validate();
        return query.withFacets(facets);
    }

    /**
     * {@code target} (a Loot workspace target) with the view each route needs: an exact run (a visit and no query) opens Pictures on
     * that run and leaves the workspace as it is; a query brings Table forward first; any other route keeps the view. Its captured
     * state also holds the view and the run, and Back restores them with the workspace's state. A rejected route changes nothing.
     */
    public RouteTarget routes(RouteTarget target) {
        Objects.requireNonNull(target, "target");
        return new RouteTarget() {
            @Override public Destination destination() { return target.destination(); }
            @Override public boolean accepts(Route route) { return target.accepts(route); }
            @Override public Route redirect(Route route) { return target.redirect(route); }
            @Override public Object captureState() { return new RouteState(tableShown, pictures.selectedRun(), target.captureState()); }
            @Override public void open(Route route) {
                if (route.visit != null && route.query == null) {
                    showPictures();
                    pictures.openRun(route.visit);
                    return;
                }
                boolean table = tableShown;
                if (route.query != null) showTable();
                try { target.open(route); }
                catch (RuntimeException failed) {
                    if (!table) showPictures();
                    throw failed;
                }
            }
            @Override public void restoreState(Object state) {
                if (!(state instanceof RouteState saved)) throw new IllegalArgumentException("Not a Loot › Explore state");
                if (saved.table()) showTable(); else showPictures();
                if (saved.run() != null) pictures.openRun(saved.run());
                target.restoreState(saved.inner());
            }
        };
    }

    /** Which view showed, the run Pictures showed (null: none) and the workspace target's own state. */
    record RouteState(boolean table, VisitRef run, Object inner) {}

    private void show(boolean table, boolean remember) {
        tableShown = table;
        if (remember) write.accept(VIEW_KEY, table ? TABLE : PICTURES);
        layout.show(views, table ? TABLE : PICTURES);
        viewSwitch.setSelected(table ? 1 : 0);
        views.revalidate();
        views.repaint();
    }

    /** Releases Pictures' reads and the workspace (the Loot page closes this page as its Explore content). EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        pictures.close();
        if (table instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (RuntimeException failed) { throw failed; }
            catch (Exception failed) { throw new IllegalStateException(failed); }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE --tests tomato.gui.loot.explore.LootExplorePageTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/loot/explore/LootExplorePage.java src/test/java/tomato/gui/loot/explore/LootExplorePageTest.java
git commit -m "Add Explore's Pictures | Table switch and its route wrapper"
```

---

### Task 6: Wire Explore Pictures into the app, and document it

**Files:**
- Modify: `src/main/java/tomato/gui/TomatoGUI.java`:
  - lines 151-160: build the page;
  - lines 236-243: wrap the Loot targets;
  - near line 271: open actions.
- Modify: `docs/LOOT.md` (the start of the `## Explore` section)

**Interfaces:**
- Consumes: from Task 5, `LootExplorePage(JComponent, Supplier<SessionStore>)`, `routes`, `onOpenRun`, `onOpenItem`, `variantQuery`

- [ ] **Step 1: Build the page.** In `TomatoGUI`, after the `lootWorkspace` line, add:

```java
        // Loot › Explore with saved history: Pictures (saved runs and each run's haul) beside the workspace as its Table view.
        tomato.gui.loot.explore.LootExplorePage explorePage = lootWorkspace instanceof ArchiveWorkspace
            ? new tomato.gui.loot.explore.LootExplorePage(lootWorkspace, AppHistory::store) : null;
```

Then change `new LootPage(highlights, lootWorkspace)` to:

```java
        LootPage lootPage = new LootPage(highlights, explorePage == null ? lootWorkspace : explorePage);
```

- [ ] **Step 2: Wrap the Loot targets.** Replace the `lootArchive` and `lootVisits` registration block with:

```java
        RouteTarget lootArchive = lootWorkspace instanceof ArchiveWorkspace ? archiveTarget(Destination.LOOT, (ArchiveWorkspace<?, ?, ?>) lootWorkspace) : null;
        if (lootArchive != null) {
            // Explore's view follows the route (an exact run: Pictures; a query: Table), and Back restores it.
            RouteTarget explore = explorePage == null ? lootArchive : explorePage.routes(lootArchive);
            navigator.register(lootPage.routes(LootTab.EXPLORE, explore));
            lootPage.owner(LootTab.EXPLORE, explore);
        }
        // The exact visit/variant target, registered later, so it is tried first.
        RouteTarget lootVisits = lootTarget(Destination.LOOT, lootWorkspace);
        if (lootVisits != null) navigator.register(lootPage.routes(LootTab.EXPLORE, explorePage == null ? lootVisits : explorePage.routes(lootVisits)));
```

- [ ] **Step 3: Wire the open actions.** Next to `runsPage.feed().onOpen(...)` (around line 271), add:

```java
        if (explorePage != null) {
            // A haul's "Open run" opens the run's recap; an item opens Table on its occurrences in every saved session.
            explorePage.onOpenRun(visit -> navigator.open(tomato.gui.route.Route.to(Destination.RUN_RECAP).withVisit(visit)));
            explorePage.onOpenItem(key -> navigator.open(tomato.gui.route.Route.to(Destination.LOOT)
                .withQuery(tomato.gui.loot.explore.LootExplorePage.variantQuery(key))));
        }
```

`explorePage` is a local of the same method as `lootWorkspace` and `navigator`. If the compiler reports it out of scope at this point, promote it to a private field next to `runsPage`, assigned where Step 1 builds it.

- [ ] **Step 4: Document.** In `docs/LOOT.md`, change the first sentence under `## Explore` from "Explore holds every loot view, live and saved, behind **one view selector**." to "**Table** holds every loot view, live and saved, behind **one view selector**." Then insert this before it:

```markdown
With saved history, Explore opens on **Pictures**: your saved runs on the left, newest first, with the Runs feed's search and filters, and the chosen run's loot on the right as the game draws it. The haul shows the dungeon's portal, outcome and time, the run's best drop, one bag sprite per bag type with its count (white first), and the open bag as an 8-slot grid. The newest run opens first; pick another card, or press Enter on one. **Loot outside runs** above the list shows bags that recorded no run, by session (the newest 10 sessions that have any). **Open run** opens the run's recap. Clicking an item opens **Table** on that item's occurrences in every saved session. A run still in progress is read again as its loot is saved (about every 30 s while Pictures shows).

The **Pictures | Table** switch above Explore chooses the view and is remembered (`ui.loot.explore.view`). A route to an exact run, such as the run recap's **Open in Loot**, opens Pictures on that run; a route with a query opens Table; Back restores the view and the run. Without saved history, Explore is the live dashboard alone.
```

- [ ] **Step 5: Compile and run the focused tests**

Run: `GRADLE --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*'`
Expected: PASS, apart from failures already on `main` in this environment (`LootHighlightsTest` ×2, `DungeonsSourceTest` timing).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/TomatoGUI.java docs/LOOT.md
git commit -m "Open Loot › Explore on Pictures: saved runs and each run's haul"
```

---

### Task 7: Verification, launch and PR (coordinator)

- [ ] **Step 1: Run the focused suite**

Run: `GRADLE --tests 'tomato.gui.loot.*' --tests 'tomato.gui.runs.*' --tests 'tomato.gui.kit.*' --tests 'tomato.gui.stats.*'`
Expected: the only failures are the known environmental ones on `main` (`LootHighlightsTest` ×2, `DungeonsSourceTest` timing).

- [ ] **Step 2: Build and launch.** Run `GRADLE shadowJar`, but replace `test --tests <TEST>` with `shadowJar` in that command.

Check that RealmShark is not already running. Copy the jar to the scratchpad. Launch it with `javaw -jar` from `C:\Users\dap\Downloads\RealmShark-realmshark`, which holds the user's settings and assets; history is per-user.

Open **Loot › Explore** and check:
- Pictures shows;
- the newest run's haul shows on the right;
- picking another card swaps the haul;
- **Loot outside runs** lists sessions;
- **Open run** opens the recap;
- clicking an item opens Table on its occurrences;
- the recap's **Open in Loot** lands on Pictures at that run;
- Back returns.

Leave the app running for the user.

- [ ] **Step 3: Open the PR** titled "Loot Explore pictures P2: Pictures view and Runs level". Link the spec and this plan, list the deviations above, and end the body with the Claude Code attribution line.
