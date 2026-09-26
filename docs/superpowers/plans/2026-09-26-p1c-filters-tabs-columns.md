# P1c Filters, Tabs and Columns Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Existing pages adopt the P1a kit mechanically: every filter wall becomes one `FilterBar` row with a collapsed drawer and removable chips, the archive toolbar moves into that row, a ⋯ menu and a table footer, page sub-tab groups become `CustomizableTabs`, and tables take `ColumnKind` widths, without changing any query, export or persisted format.

**Architecture:** `ArchiveWorkspace` hosts a `FilterBar` (search + scope slots, ⋯ overflow) and a footer; each `ArchiveClient` contributes its existing facet controls and chip describers through a new optional `filters(page, state, binding)` hook that the workspace calls right after `render(...)` with the same `Binding`. Live panels build their own `FilterBar` around their existing controls; `FilterChips` and `WrapRow` (new, `tomato.gui.history`) keep chip updates focus-safe and filter rows wrapping at 680 px / 24 pt. Tab groups keep exposing a plain `JTabbedPane` (`CustomizableTabs.component()`), and every index-based lookup becomes an ID lookup so reordering and hiding stay correct.

**Tech Stack:** Java 17, Swing, FlatLaf 3.5.4, JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md` §3.2 (Simple/Analyst), §4.4 (customizable sub-tabs), §5.5 (column kinds), §5.6 (filter bar), §5.7 (honest values), §11 (testing), §12 row P1 (P1c), success criterion S6.

**Depends on:** P0, P1a and P1b merged.

## Global Constraints

- Branch from verified main as feat/redesign-p1c-adoption in its own worktree; no direct commits to main; no force pushes; no hook bypasses.
- Gradle, from the worktree root in Git Bash:
  RS_TOOLS="C:/Users/dap/Downloads/RealmShark-realmshark/.tools"
  export JAVA_HOME="$RS_TOOLS/jdk-17.0.20.1+1" GRADLE_USER_HOME="$RS_TOOLS/gradle-home"
  ./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1c-cache -PrealmSharkBuildDir=build/p1c <tasks>
  (only build/p1c; UI tests open real windows — one Gradle invocation at a time; never run scripts/Set-CiDisplay.ps1; never start live capture)
- Query semantics, exports, persistence formats (ViewState, saved views) and honesty wording are unchanged; only placement/presentation changes.
- Colors via tomato.gui.kit.Tokens; fonts via ContentStyle.font; motion only via Motion.run (FilterBar handles it).
- Commit messages end with: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
- Every command below is `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1c-cache -PrealmSharkBuildDir=build/p1c …` run after the two `export` lines above; it is abbreviated as `GRADLE …`.
- Use only public P1a APIs (`FilterBar`, `FilterBar.ActiveFilter`, `OverflowMenu`, `KitButton`, `Chip`, `CustomizableTabs`, `ColumnKind`, `KitTables`, `DisplayModeModel`); do not edit `tomato.gui.kit`, `WorkspaceShell`, `TomatoGUI` or the sidebar (P1b owns those). No P1b API is used.
- Keep component names that tests use (`*-session-picker`, `*-history-search`, `activity-search`, `chat-player`, `inspect-facet-N`, `character-facet-N`, `quest-*`, `loot-*`, `keypop-*`, tab names such as `inspect-tabs`, `character-detail-tabs`, `dps-tabs`, `statistics-tabs`, `activity-resource-tabs`).
- FilterBar drawer state (`ui.filters.<name>.open`), tab order (`ui.tabs.<group>`) and `ui.mode` persist through `PropertiesManager` into `build/p1c/ui-test/realmShark.properties`; every test that opens a drawer, writes a tab order or changes `DisplayModeModel.application()` restores it in `finally`/`@After`.
- Tasks run in order: later tasks anchor import edits on lines added by earlier tasks.
- Match surrounding style: the history/activity/stats archive code is dense one-liners; keep new code there in the same compact style.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `src/main/java/tomato/gui/history/ArchiveFilters.java` | Create | Drawer + chips value returned by `ArchiveClient.filters`; shared date/summary chip text |
| `src/main/java/tomato/gui/history/FilterChips.java` | Create | Focus-safe `FilterBar.setActive` (skip unchanged labels, restore focus after row rebuilds) |
| `src/main/java/tomato/gui/history/WrapRow.java` | Create | FilterBar slot whose controls wrap to the hosting row width |
| `src/main/java/tomato/gui/history/ArchiveClient.java` | Modify | Optional `filters(...)` hook |
| `src/main/java/tomato/gui/history/ArchiveWorkspace.java` | Replace | FilterBar row (search, scope, ⋯), footer paging/status, drawer lifecycle |
| `src/main/java/tomato/gui/history/HistoryTables.java` | Modify | `Column.kind`, kind widths/renderers in `queried`, `kinds(JTable, Map)` for ad-hoc tables |
| `src/main/java/tomato/gui/activity/ActivityArchiveClient.java` | Modify | Facets → drawer, chips, window/exact-link actions beside the view, column kinds |
| `src/main/java/tomato/gui/stats/LootFacetChips.java` | Create | Loot facet chip describer (saved and live) |
| `src/main/java/tomato/gui/stats/LootFacetControls.java` | Modify | `kind(Kind)` package-private for chip text |
| `src/main/java/tomato/gui/stats/LootArchiveClient.java` | Modify | Facets/dates → drawer, chips, column kinds |
| `src/main/java/tomato/gui/keypop/KeyPopArchiveClient.java` | Modify | Facets/dates → drawer, chips, column kinds |
| `src/main/java/tomato/gui/chat/ChatArchiveClient.java` | Modify | Facets/dates → drawer, chips, column kinds |
| `src/main/java/tomato/gui/activity/ActivityPanel.java` | Modify | Live FilterBar (run facets drawer), combat CustomizableTabs, column kinds |
| `src/main/java/tomato/gui/chat/ChatExplorer.java` | Modify | Live FilterBar (player/starred/ignored/dates/view state in drawer) |
| `src/main/java/tomato/gui/keypop/KeyPopDashboard.java` | Modify | Live FilterBar, CustomizableTabs, column kinds |
| `src/main/java/tomato/gui/stats/LootDashboard.java` | Modify | Live FilterBar |
| `src/main/java/tomato/gui/security/ParsePanelGUI.java` | Modify | Roster FilterBar (options + display facets in drawer), column kinds |
| `src/main/java/tomato/gui/character/CharacterJournalGUI.java` | Modify | Roster FilterBar, detail CustomizableTabs, column kinds |
| `src/main/java/tomato/gui/quest/QuestGUI.java` | Modify | FilterBar, CustomizableTabs, column kinds |
| `src/main/java/tomato/gui/character/CharacterPanelGUI.java`, `security/SecurityGUI.java`, `bridge/BridgeReviewGUI.java`, `dps/DpsGUI.java` | Modify | CustomizableTabs |
| `src/main/java/tomato/gui/logging/LoggingGUI.java`, `stats/StatisticsGUI.java` | Modify | CustomizableTabs (+ Logging column kinds) |
| `src/main/java/tomato/gui/dps/MeterDpsGUI.java`, `security/InspectRunsPanel.java` | Modify | Column kinds |
| `src/test/java/tomato/gui/history/ArchiveNativeSupport.java` | Modify | Overflow/footer/chip helpers; rewritten `archiveControls`, `preview`, `failExport` |
| `src/test/java/tomato/gui/history/ArchiveLifecycleRegressionTest.java`, `ArchiveExportHookTest.java`, `src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java` | Modify | Menu-item lookups |
| `src/test/java/tomato/gui/stats/StatisticsArchiveNativeTest.java`, `security/InspectArchiveNativeTest.java`, `chat/ChatConsistencyTest.java`, `chat/ChatExplorerTest.java`, `chat/ChatFiltersTest.java`, `quest/QuestConsistencyTest.java`, `security/InspectRosterOwnershipTest.java` | Modify | Open drawers / Analyst mode where tests need hidden controls |
| New tests (see tasks) | Create | TDD coverage per task plus `FilterBarEvidenceTest` screenshots |

---

### Task 1: Archive filter hook, workspace filter row, ⋯ menu and footer

**Files:**
- Create: `src/main/java/tomato/gui/history/ArchiveFilters.java`, `FilterChips.java`, `WrapRow.java`
- Modify: `src/main/java/tomato/gui/history/ArchiveClient.java`
- Replace: `src/main/java/tomato/gui/history/ArchiveWorkspace.java`
- Modify tests: `src/test/java/tomato/gui/history/ArchiveNativeSupport.java`, `ArchiveLifecycleRegressionTest.java`, `ArchiveExportHookTest.java`, `src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`
- Create test: `src/test/java/tomato/gui/history/ArchiveFilterBarTest.java`

**Interfaces:**
- Consumes (P1a): `public FilterBar(String name)`; `FilterBar search(JComponent)`, `FilterBar scope(JComponent)`, `FilterBar drawer(JComponent)`, `OverflowMenu overflow()`, `void setActive(List<ActiveFilter>, Runnable)`, `int activeCount()`, `boolean drawerOpen()`, `void setDrawerOpen(boolean)`, `void setDrawerEnabled(boolean)`, `JComponent drawerContent()`; `FilterBar.ActiveFilter(String label, Runnable remove)` (public final `label`, `remove`); component names `<name>-filter-bar`, `<name>-filters`, `<name>-clear-filters`, `<name>-filter-drawer`, `<name>-more`; `OverflowMenu`: `JMenuItem add(String, Runnable)`, `JMenu submenu(String)`, `void addSeparator()`, `JMenuItem item(String)`; `KitButton.icon(Icon, String)`, `KitButton.variant()`; `LineIcon.REFRESH`; chip remove buttons are named `remove-filter` with accessible name `Remove filter: <label>`.
- Produces:
  - `public final class ArchiveFilters { public final JComponent drawer; public final List<FilterBar.ActiveFilter> active; public ArchiveFilters(JComponent drawer, List<FilterBar.ActiveFilter> active); public static <F,S extends Enum<S>> void dates(List<FilterBar.ActiveFilter> chips, ArchiveQuery<F,S> query, Consumer<ArchiveQuery<F,S>> changed); public static String dateLabel(ArchiveQuery.Bounds b); public static String summary(Collection<?> values); }`
  - `public final class FilterChips { public static void update(FilterBar bar, List<FilterBar.ActiveFilter> active, Runnable clear, boolean force); public static void keepingFocus(Runnable change); }`
  - `public final class WrapRow extends JPanel { public WrapRow(Component... children); }`
  - `ArchiveClient`: `default ArchiveFilters filters(ArchivePage<R> page, ViewState<F,S> state, Binding<F,S> binding) { return null; }`
  - `ArchiveWorkspace`: `public FilterBar filterBar()`; component names `<name>-archive-footer`; overflow items `History library…`, submenu `Saved views` (`Save current view…`, `Load: <name>` per view, `Delete view…`, `Reset saved state`), `Export selected…`, `Export page…`, `Export all matches…`, `Open export folder`; footer buttons `Previous page`, `Next page`, `Cancel read`, `Cancel export`; scope `Browse saved`/`Current live view`, `<name>-session-picker`, icon button accessible name `Refresh`. `saveNamed(String)` now also refreshes the Saved views menu.
  - `ArchiveNativeSupport`: `public static OverflowMenu more(ArchiveWorkspace<?,?,?>)`, `public static JMenuItem action(ArchiveWorkspace<?,?,?>, String)`, `public static List<String> chipLabels(FilterBar)`, `public static void removeChip(FilterBar, String)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/tomato/gui/history/ArchiveFilterBarTest.java`:
```java
package tomato.gui.history;

// Explicit AWT imports: java.awt.* would make ArchiveFixtures.Event ambiguous with java.awt.Event.
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** The archive toolbar as one filter row, a ⋯ menu and a footer; synthetic history, no native window. */
public class ArchiveFilterBarTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void toolbarMovesIntoTheFilterRowOverflowAndFooter()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,120);ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(root,false,"test")){
            Faceted client=new Faceted(id,scratch);
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"facet-bar",new JLabel("Live"),client,memory.states));
            try{
                edt(()->{
                    assertEquals("facet-bar-filter-bar",workspace.filterBar().getName());
                    assertFalse("Saved-history search is hidden in the live view",named(workspace,"facet-bar-history-search").isVisible());
                    assertFalse("Paging and status belong to saved history",named(workspace,"facet-bar-archive-footer").isVisible());
                    assertNotNull(button(workspace,"Browse saved"));assertNotNull(named(workspace,"facet-bar-session-picker"));
                    KitButton refresh=find(workspace,KitButton.class,b->"Refresh".equals(b.getAccessibleContext().getAccessibleName()));
                    assertEquals(KitButton.Variant.ICON,refresh.variant());
                    OverflowMenu more=ArchiveNativeSupport.more(workspace);
                    for(String label:new String[]{"History library…","Saved views","Save current view…","Delete view…","Reset saved state",
                            "Export selected…","Export page…","Export all matches…","Open export folder"})assertNotNull(label,more.item(label));
                    assertFalse(more.item("Export all matches…").isEnabled());assertFalse(more.item("Open export folder").isEnabled());
                    assertFalse("Saved views follow saved-history mode",more.item("Saved views").isEnabled());
                    workspace.showSaved();return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    OverflowMenu more=ArchiveNativeSupport.more(workspace);FilterBar bar=workspace.filterBar();
                    assertTrue(named(workspace,"facet-bar-archive-footer").isVisible());assertTrue(named(workspace,"facet-bar-history-search").isVisible());
                    assertNotNull(button(workspace,"Current live view"));
                    assertTrue(more.item("Export all matches…").isEnabled());assertTrue(more.item("Export page…").isEnabled());
                    assertFalse("Nothing selected yet",more.item("Export selected…").isEnabled());assertTrue(more.item("Saved views").isEnabled());
                    assertFalse(button(workspace,"Cancel read").isVisible());assertFalse(button(workspace,"Cancel export").isVisible());
                    assertFalse("Filters start collapsed",bar.drawerOpen());assertSame(client.drawer,bar.drawerContent());
                    assertSame("filters() receives render()'s binding",client.renderBinding,client.filtersBinding);
                    assertEquals(0,bar.activeCount());
                    client.minimum.setValue(100);
                    assertFalse("The drawer is disabled while its query loads",client.minimum.isEnabled());return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==20);
                edt(()->{
                    assertTrue("The replacement drawer is enabled",client.minimum.isEnabled());
                    assertEquals(Collections.singletonList("Minimum 100"),ArchiveNativeSupport.chipLabels(workspace.filterBar()));
                    assertEquals("Filters · 1",((AbstractButton)named(workspace,"facet-bar-filters")).getText());
                    ((AbstractButton)named(workspace,"facet-bar-clear-filters")).doClick();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
                edt(()->{assertEquals(0,workspace.filterBar().activeCount());assertEquals("Clear keeps the scope",id,workspace.state().query.scope());
                    client.minimum.setValue(110);return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==10);
                edt(()->{ArchiveNativeSupport.removeChip(workspace.filterBar(),"Minimum 110");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
                edt(()->workspace.saveNamed("Everything")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                edt(()->{assertTrue(ArchiveNativeSupport.action(workspace,"Delete view…").isEnabled());
                    workspace.changeQuery(workspace.state().query.withText("Message 1"));return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==31);
                edt(()->{ArchiveNativeSupport.action(workspace,"Load: Everything").doClick();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
            }finally{edt(()->{workspace.close();return null;});}
        }
    }

    private static final class Faceted implements ArchiveClient<Event,Facets,Sort> {
        final String scope;final Path scratch;JSpinner minimum;JPanel drawer;Binding<Facets,Sort> renderBinding,filtersBinding;
        Faceted(String scope,Path scratch){this.scope=scope;this.scratch=scratch;}
        public ArchiveQuery<Facets,Sort> initialQuery(){return query(scope);}
        public Path scratchDirectory(){return scratch;}
        public ArchiveAdapter<Event,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> query){return ArchiveFixtures.adapter();}
        static List<HistoryTables.Column<Event,?>> columns(){return Arrays.asList(new HistoryTables.Column<>("value","Value",Integer.class,e->e.value,null));}
        public JComponent render(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            renderBinding=binding;
            return new JScrollPane(HistoryTables.queried("facet-bar-rows",columns(),page,Collections.singletonMap("value",Sort.VALUE),state.query,binding::queryChanged,row->{}));
        }
        @Override public ArchiveFilters filters(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            filtersBinding=binding;int value=state.query.facets().minimum;
            minimum=new JSpinner(new SpinnerNumberModel(value,0,20000,1));minimum.addChangeListener(e->binding.queryChanged(state.query.withFacets(new Facets((Integer)minimum.getValue()))));
            drawer=new JPanel();drawer.add(minimum);List<FilterBar.ActiveFilter> chips=new ArrayList<>();
            if(value>0)chips.add(new FilterBar.ActiveFilter("Minimum "+value,()->binding.queryChanged(state.query.withFacets(new Facets(0)))));
            return new ArchiveFilters(drawer,chips);
        }
    }
    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T> T edt(Checked<T> value)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{result.set(value.get());}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());return result.get();
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Timed out waiting for EDT state");
    }
    private static Component named(Container root,String name){return find(root,Component.class,c->name.equals(c.getName()));}
    private static AbstractButton button(Container root,String text){return find(root,AbstractButton.class,b->text.equals(b.getText()));}
    private static <T extends Component> T find(Container root,Class<T> type,Predicate<T> test){
        for(Component child:root.getComponents()){
            if(type.isInstance(child)&&test.test(type.cast(child)))return type.cast(child);
            if(child instanceof Container){T found=find((Container)child,type,test);if(found!=null)return found;}
        }return null;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.history.ArchiveFilterBarTest"`
Expected: FAIL — compilation errors `cannot find symbol: class ArchiveFilters`, `cannot find symbol: method filterBar()`, `cannot find symbol: method more(ArchiveWorkspace<Event,Facets,Sort>)`.

- [ ] **Step 3: Create `WrapRow.java`**

```java
package tomato.gui.history;

import java.awt.*;
import javax.swing.JPanel;

/**
 * A FilterBar slot whose controls wrap to the width of the row that hosts it. FilterBar lays its slots out at their
 * preferred size, so a plain panel with several controls would push past the page edge at 680 px or 24 pt.
 */
public final class WrapRow extends JPanel {
    public WrapRow(Component... children) {
        super(new FlowLayout(FlowLayout.LEADING, 6, 2));
        setOpaque(false);
        for (Component child : children) add(child);
    }

    @Override public Dimension getPreferredSize() {
        Container host = getParent();
        if (host == null || host.getWidth() <= 0) return super.getPreferredSize();
        Insets hostInsets = host.getInsets();
        // The host is FilterBar's wrapping row (FlowLayout hgap 6): a child may use its width minus both gaps.
        int limit = host.getWidth() - hostInsets.left - hostInsets.right - 12;
        return limit > 0 ? size(limit) : super.getPreferredSize();
    }

    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    /** Mirrors FlowLayout.layoutContainer's line breaking for a row of the given width. */
    private Dimension size(int width) {
        FlowLayout flow = (FlowLayout) getLayout();
        Insets insets = getInsets();
        int available = width - insets.left - insets.right - flow.getHgap() * 2, x = 0, rowHeight = 0, widest = 0;
        int height = insets.top + flow.getVgap();
        for (Component child : getComponents()) {
            if (!child.isVisible()) continue;
            Dimension size = child.getPreferredSize();
            if (x > 0 && x + size.width > available) { height += rowHeight + flow.getVgap(); widest = Math.max(widest, x); x = 0; rowHeight = 0; }
            x += (x > 0 ? flow.getHgap() : 0) + size.width;
            rowHeight = Math.max(rowHeight, size.height);
        }
        widest = Math.max(widest, x);
        return new Dimension(Math.min(width, widest + insets.left + insets.right + flow.getHgap() * 2),
            height + rowHeight + flow.getVgap() + insets.bottom);
    }
}
```

- [ ] **Step 4: Create `FilterChips.java`**

```java
package tomato.gui.history;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;
import tomato.gui.kit.FilterBar;

/** FilterBar rebuilds its whole row when chips or slots change; these helpers keep that invisible to the keyboard. */
public final class FilterChips {
    private static final String LABELS = "filterChips.labels";
    private FilterChips() {}

    /**
     * Replaces the chips when their labels change (always when force is true, e.g. a new archive render whose remove
     * actions carry a new binding). Live panels pass force=false; their remove actions read current state when clicked.
     */
    public static void update(FilterBar bar, List<FilterBar.ActiveFilter> active, Runnable clear, boolean force) {
        List<String> labels = new ArrayList<>();
        for (FilterBar.ActiveFilter filter : active) labels.add(filter.label);
        if (!force && labels.equals(bar.getClientProperty(LABELS))) return;
        bar.putClientProperty(LABELS, labels);
        keepingFocus(() -> bar.setActive(active, clear));
    }

    /** Runs a change that removes and re-adds controls, then returns keyboard focus to the control that had it. */
    public static void keepingFocus(Runnable change) {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        change.run();
        if (focused != null && focused.isShowing() && focused.isEnabled() && focused.isFocusable()) focused.requestFocusInWindow();
    }
}
```

- [ ] **Step 5: Create `ArchiveFilters.java`**

```java
package tomato.gui.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.JComponent;
import tomato.gui.kit.FilterBar;
import tomato.history.archive.ArchiveQuery;

/** A module's facet controls for the workspace Filters drawer, plus chips describing the facets that narrow the query. */
public final class ArchiveFilters {
    private static final DateTimeFormatter CHIP_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    /** The module's existing facet controls; null keeps the Filters toggle hidden. */
    public final JComponent drawer;
    public final List<FilterBar.ActiveFilter> active;

    public ArchiveFilters(JComponent drawer, List<FilterBar.ActiveFilter> active) {
        this.drawer = drawer;
        this.active = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(active, "active")));
    }

    /** Adds a chip for bounded dates; removing it keeps the zone, time mode and unknown-time choice. */
    public static <F, S extends Enum<S>> void dates(List<FilterBar.ActiveFilter> chips, ArchiveQuery<F, S> query, Consumer<ArchiveQuery<F, S>> changed) {
        ArchiveQuery.Bounds b = query.bounds();
        if (b.from == null && b.until == null) return;
        chips.add(new FilterBar.ActiveFilter(dateLabel(b), () -> changed.accept(query.withBounds(
            new ArchiveQuery.Bounds(null, null, ZoneId.of(b.zone), b.mode, b.includeUnknown)))));
    }

    /** "2026-09-21 00:00 – 2026-09-22 00:00", "From …" or "Until …" in the bounds' own zone (until stays exclusive). */
    public static String dateLabel(ArchiveQuery.Bounds b) {
        ZoneId zone = ZoneId.of(b.zone);
        String from = b.from == null ? null : CHIP_TIME.format(Instant.ofEpochMilli(b.from).atZone(zone));
        String until = b.until == null ? null : CHIP_TIME.format(Instant.ofEpochMilli(b.until).atZone(zone));
        if (from != null && until != null) return from + " – " + until;
        return from != null ? "From " + from : "Until " + until;
    }

    /** "A", "A, B" or "A, B +3": short enough for a chip; the drawer shows the full selection. */
    public static String summary(Collection<?> values) {
        List<String> text = new ArrayList<>();
        for (Object value : values) text.add(String.valueOf(value));
        return text.size() <= 2 ? String.join(", ", text) : text.get(0) + ", " + text.get(1) + " +" + (text.size() - 2);
    }
}
```

- [ ] **Step 6: Add the hook to `ArchiveClient.java`**

Replace:
```java
    default int pageSize(){return 1000;}
```
with:
```java
    default int pageSize(){return 1000;}
    /**
     * Called on the EDT right after render(...) with the same page, state and Binding. The drawer holds the module's
     * existing facet controls (they keep publishing binding.queryChanged); chips describe active facets and dates.
     * Null: no Filters drawer and no chips.
     */
    default ArchiveFilters filters(ArchivePage<R> page,ViewState<F,S> state,Binding<F,S> binding){return null;}
```

- [ ] **Step 7: Replace `ArchiveWorkspace.java`**

Full new file (everything from `publishCompletion` in `ExportTask` down to the nested `Update` class is unchanged apart from menu items replacing buttons):
```java
package tomato.gui.history;

import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** EDT-owned typed SessionPanel path. Capture/live components remain independent of saved data. */
public final class ArchiveWorkspace<R,F,S extends Enum<S>> extends JPanel implements AutoCloseable {
    private final SessionStore store;private final String name;private final ArchiveClient<R,F,S> client;private final ViewStateStore states;
    private final SnapshotRefresh<Update<R>> refresh=new SnapshotRefresh<>();
    private final SnapshotRefresh<List<SessionStore.SessionEntry>> catalog=new SnapshotRefresh<>();
    private final JComboBox<Choice> sessions=new JComboBox<>();private final JTextField search=new JTextField(18);
    private final JPanel cards=new JPanel(new CardLayout()),saved=new JPanel(new BorderLayout()),footer=new JPanel(new BorderLayout(0,4));
    private final JTextArea status=ContentStyle.wrappingText("Current live view"),saveStatus=ContentStyle.wrappingText("");
    private final JButton previous=new JButton("Previous page"),next=new JButton("Next page"),browse=new JButton("Browse saved");
    private final JButton stop=new JButton("Cancel read"),cancelExport=new JButton("Cancel export");
    private final KitButton reload=KitButton.icon(new LineIcon(LineIcon.REFRESH,16),"Refresh");
    private final FilterBar filterBar;private final WrapRow searchRow=new WrapRow();private final JPanel scopeRow=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
    private final JMenuItem exportAll,exportPage,exportSelected,openFolder;private final JMenu views;
    private boolean narrowScope=true;
    private Path exportFolder;
    private String librarySelection;
    private ViewState<F,S> state;private ArchiveResult<R> result;private ArchiveQuery<F,S> resultQuery;
    private ArchivePage<R> displayed;
    private Cancellation cancel=new Cancellation(),catalogCancel=new Cancellation(),exportCancel=new Cancellation();
    private boolean restoring,loading,exporting,closed,stateLoadFailed;
    private long viewGeneration,saveGeneration;
    // Weak keys retain cached controls' state without retaining every retired page.
    private final Map<Component,Boolean> disabledStates=new WeakHashMap<>();
    private JComponent activeView;
    private final HierarchyListener reuseListener=event->{
        Component component=event.getComponent();
        if((event.getChangeFlags()&HierarchyEvent.PARENT_CHANGED)!=0&&!closed&&!loading&&!restoring&&state.archive
                &&activeView!=null&&SwingUtilities.isDescendingFrom(activeView,saved)
                &&SwingUtilities.isDescendingFrom(component,activeView)&&disabledStates.containsKey(component))
            restoreAdoptedSubtree(component);
    };

    ArchiveWorkspace(SessionStore store,String name,JComponent live,ArchiveClient<R,F,S> client,ViewStateStore states){
        super(new BorderLayout(0,6));requireEdt();this.store=store;this.name=name;this.client=client;this.states=states;
        if(client.pageSize()<1||client.pageSize()>1000)throw new IllegalArgumentException("Page size must be 1–1000");
        state=ViewState.initial(client.initialQuery());
        try{state=states.load(name,state);}catch(RuntimeException failure){stateLoadFailed=true;saveStatus.setText(failure.getMessage());}
        setName(name+"-session-view");sessions.setName(name+"-session-picker");sessions.getAccessibleContext().setAccessibleName(name+" session");
        sessions.setToolTipText("Session scope");sessions.setPrototypeDisplayValue(new Choice("","Session · 2026-09-20 12:00:00"));
        DefaultListCellRenderer literal=new DefaultListCellRenderer();literal.putClientProperty("html.disable",true);sessions.setRenderer(literal);
        cards.add(live,"live");cards.add(saved,"saved");
        search.setName(name+"-history-search");search.getAccessibleContext().setAccessibleName("Search all saved "+name+" records");
        search.putClientProperty("JTextField.placeholderText","Search entire scope; press Enter");
        filterBar=new FilterBar(name);scopeRow.setOpaque(false);OverflowMenu more=filterBar.overflow();
        more.add("History library…",this::openLibrary);more.addSeparator();views=more.submenu("Saved views");more.addSeparator();
        exportSelected=more.add("Export selected…",()->chooseExport(ExportSelection.selected(state.selected)));
        exportPage=more.add("Export page…",()->{if(displayed!=null)chooseExport(ExportSelection.page(displayed.page,displayed.size));});
        exportAll=more.add("Export all matches…",()->chooseExport(ExportSelection.all()));
        openFolder=more.add("Open export folder",()->{
            if(exportFolder==null||!Desktop.isDesktopSupported())return;
            try{Desktop.getDesktop().open(exportFolder.toFile());}catch(Exception failure){status.setText("Could not open the export folder: "+failure.getMessage());}
        });openFolder.setEnabled(false);
        placeScope(false);
        JPanel paging=ContentStyle.controls();paging.add(previous);paging.add(next);paging.add(stop);paging.add(cancelExport);stop.setVisible(false);cancelExport.setVisible(false);
        JPanel texts=new JPanel();texts.setLayout(new BoxLayout(texts,BoxLayout.Y_AXIS));texts.add(status);texts.add(saveStatus);
        footer.setName(name+"-archive-footer");footer.add(paging,BorderLayout.NORTH);footer.add(texts,BorderLayout.CENTER);
        add(ContentStyle.page(filterBar,cards,footer));
        sessions.addActionListener(e->{if(!restoring){Choice selected=(Choice)sessions.getSelectedItem();if(selected!=null)selectSession(selected.id);}});
        browse.addActionListener(e->{state=state.withArchive(!state.archive);persist();request(false);});
        reload.addActionListener(e->{reloadCatalog();request(true);});
        stop.addActionListener(e->{invalidateView();cancel.cancel();refresh.invalidate();loading=false;status.setText("Read cancelled. Refresh to retry.");updateActions();});
        search.addActionListener(e->changeQuery(state.query.withText(search.getText())));
        previous.addActionListener(e->selectPage(Math.max(0,state.page-1)));next.addActionListener(e->selectPage(state.page+1));
        cancelExport.addActionListener(e->exportCancel.cancel());
        filterBar.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){fitScope();}});
        search.addPropertyChangeListener("font",e->fitScope());
        addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0){
            // A load started while hidden (for example an atomic restore before its page is shown) continues.
            if(isShowing()){if(!closed&&!loading)request(false);}else{invalidateView();cancel.cancel();refresh.invalidate();loading=false;updateActions();}
        }});
        reloadNames();syncControls();reloadCatalog();request(false);
    }
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Workspace changes require the EDT");}
    public ViewState<F,S> state(){requireEdt();return state;}
    public ArchivePage<R> displayedPage(){requireEdt();return displayed;}
    public boolean loading(){requireEdt();return loading;}
    /** The workspace's filter row: search, the module's Filters drawer and chips, scope and the ⋯ menu. */
    public FilterBar filterBar(){requireEdt();return filterBar;}
    public void selectSession(String id){
        requireEdt();String scope=store.currentId().equals(id)?ArchiveQuery.CURRENT:id;
        state=state.withQuery(state.query.withScope(scope)).withArchive(!ArchiveQuery.CURRENT.equals(scope));persist();syncControls();request(false);
    }
    public void showSaved(){requireEdt();state=state.withArchive(true);persist();request(false);}
    public void changeQuery(ArchiveQuery<F,S> query){
        requireEdt();if(restoring||closed)return;state=state.withQuery(query).withArchive(true);persist();syncControls();request(false);
    }
    /**
     * Atomically applies a complete detached state once (Back and routed opens): scope, query, mode, page,
     * selection, scroll anchor and layouts. In-flight reads are invalidated so their completions are inert,
     * and exactly one load starts for the restored state. A query of other facet/sort types is rejected.
     */
    public void restore(ViewState<F,S> value){
        requireEdt();Objects.requireNonNull(value,"state");if(closed)return;
        ArchiveQuery<F,S> typed=client.initialQuery().restore(value.query.toJson());
        if(!typed.equals(value.query))throw new IllegalArgumentException("State belongs to a different workspace");
        state=value;persist();syncControls();request(false);
    }
    public void selectPage(long page){requireEdt();if(page<0)throw new IllegalArgumentException("Negative page");state=state.withPage(page);persist();request(false);}
    public void refresh(){requireEdt();request(true);}
    public CompletionStage<PreferencesStore.SaveResult> saveNamed(String label){requireEdt();CompletionStage<PreferencesStore.SaveResult> save=states.saveNamed(name,label,state);watchSave(save);reloadNames();return save;}
    public void loadNamed(String label){
        requireEdt();try{state=states.loadNamed(name,label,ViewState.initial(client.initialQuery()));persist();syncControls();request(false);}
        catch(RuntimeException failure){saveStatus.setText("Saved view was not applied: "+failure.getMessage());}
    }
    /** Scope controls use the row's right slot when it has room; on narrow rows they wrap after the search field. */
    private void fitScope(){if(!closed)SwingUtilities.invokeLater(()->{if(!closed&&narrowFit()!=narrowScope)placeScope(!narrowScope);});}
    private boolean narrowFit(){
        int needed=search.getPreferredSize().width+browse.getPreferredSize().width+sessions.getPreferredSize().width+reload.getPreferredSize().width
            +filterBar.overflow().getPreferredSize().width+14*search.getFontMetrics(search.getFont()).charWidth('m');
        return filterBar.getWidth()>0&&filterBar.getWidth()<needed;
    }
    private void placeScope(boolean narrow){
        narrowScope=narrow;FilterChips.keepingFocus(()->{
            searchRow.removeAll();scopeRow.removeAll();searchRow.add(search);
            JPanel target=narrow?searchRow:scopeRow;target.add(browse);target.add(sessions);target.add(reload);
            filterBar.search(searchRow).scope(narrow?null:scopeRow);
        });
    }
    private void reloadNames(){
        List<String> names;
        try{names=states.names(name);}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());names=Collections.emptyList();}
        views.removeAll();JMenuItem save=new JMenuItem("Save current view…");save.addActionListener(e->promptSave());views.add(save);
        if(!names.isEmpty())views.addSeparator();
        for(String label:names){JMenuItem load=new JMenuItem("Load: "+label);load.addActionListener(e->loadNamed(label));views.add(load);}
        views.addSeparator();
        JMenuItem delete=new JMenuItem("Delete view…");delete.setEnabled(!names.isEmpty());delete.addActionListener(e->promptDelete());views.add(delete);
        JMenuItem reset=new JMenuItem("Reset saved state");reset.addActionListener(e->resetSavedState());views.add(reset);
    }
    private void promptSave(){
        String label=JOptionPane.showInputDialog(this,"Name for this view","Save view",JOptionPane.PLAIN_MESSAGE);
        if(label==null||closed)return;
        try{saveNamed(label);}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}
    }
    private void promptDelete(){
        List<String> names;try{names=states.names(name);}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());return;}
        if(names.isEmpty())return;
        Object label=JOptionPane.showInputDialog(this,"Delete which saved view?","Delete view",JOptionPane.PLAIN_MESSAGE,null,names.toArray(),names.get(0));
        if(label==null||closed)return;watchSave(states.deleteNamed(name,label.toString()));reloadNames();
    }
    private void resetSavedState(){watchSave(states.reset(name));stateLoadFailed=false;state=ViewState.initial(client.initialQuery());reloadNames();syncControls();request(true);}
    private void syncControls(){
        restoring=true;try{
            if(sessions.getItemCount()==0){sessions.addItem(new Choice(ArchiveQuery.CURRENT,"Current Session"));sessions.addItem(new Choice(SessionStore.ALL,"All Sessions"));}
            boolean found=false;for(int i=0;i<sessions.getItemCount();i++)if(sessions.getItemAt(i).id.equals(state.query.scope())){sessions.setSelectedIndex(i);found=true;break;}
            if(!found){Choice pending=new Choice(state.query.scope(),"Selected session · "+state.query.scope());sessions.addItem(pending);sessions.setSelectedItem(pending);}
            search.setText(state.query.text());
        }finally{restoring=false;}
    }
    private void reloadCatalog(){
        catalogCancel.cancel();catalogCancel=new Cancellation();Cancellation token=catalogCancel;
        catalog.request(new Object(),()->{try{return store.catalog(token);}catch(Exception failure){throw new IllegalStateException(failure);}},entries->{
            restoring=true;try{sessions.removeAllItems();sessions.addItem(new Choice(ArchiveQuery.CURRENT,"Current Session"));sessions.addItem(new Choice(SessionStore.ALL,"All Sessions"));
                int recent=0;for(SessionStore.SessionEntry entry:entries)if(entry.readable()&&!entry.id.equals(store.currentId())&&(recent++<20||entry.id.equals(state.query.scope())))sessions.addItem(new Choice(entry.id,entry.session().toString()));
            }finally{restoring=false;}syncControls();
        },failure->status.setText("Session list could not be read. Open History library or Refresh."));
    }
    private void request(boolean fresh){
        requireEdt();if(closed)return;invalidateView();cancel.cancel();refresh.invalidate();cancel=new Cancellation();
        ((CardLayout)cards.getLayout()).show(cards,state.archive?"saved":"live");
        footer.setVisible(state.archive);search.setVisible(state.archive);browse.setText(state.archive?"Current live view":"Browse saved");
        if(!state.archive){
            FilterChips.keepingFocus(()->{filterBar.drawer(null);FilterChips.update(filterBar,Collections.<FilterBar.ActiveFilter>emptyList(),null,true);});
            loading=false;updateActions();return;
        }
        final ViewState<F,S> requested=state;final Cancellation token=cancel;
        final ArchiveResult<R> existing=!fresh&&result!=null&&state.query.equals(resultQuery)?result:null;
        final ArchiveAdapter<R,F,S> adapter;
        try{adapter=existing==null?client.adapter(requested.query):null;}catch(RuntimeException failure){status.setText("Query is unavailable: "+failure.getMessage());loading=false;updateActions();return;}
        loading=true;status.setText("Loading saved history… Previous rows, if shown, are not the pending query.");updateActions();
        refresh.request(new Object(),()->{
            ArchiveResult<R> next=existing;
            try {
                token.check();if(next==null)next=HistoryPage.open(store,requested.query,adapter,client.scratchDirectory(),token);
                long last=next.matches==0?0:(next.matches-1)/client.pageSize();long page=Math.min(requested.page,last);
                if(fresh&&requested.anchor!=null){long anchored=next.pageOf(requested.anchor,client.pageSize(),token);if(anchored>=0)page=anchored;}
                ArchivePage<R> values=next.page(page,client.pageSize(),token);token.check();return new Update<>(next,values,existing==null);
            }catch(Exception|Error failure){if(existing==null&&next!=null)next.close();throw failure instanceof RuntimeException?(RuntimeException)failure:new IllegalStateException(failure);}
        },update->apply(update,requested.query),failure->{loading=false;Throwable cause=failure;
            while(cause.getCause()!=null)cause=cause.getCause();
            status.setText("History read failed: "+cause.getMessage()+". Refresh to retry; no new revision was applied.");updateActions();},Update::discard);
    }
    private void apply(Update<R> update,ArchiveQuery<F,S> query){
        if(closed||!state.archive||!query.equals(state.query)){update.discard();return;}long ticket=++viewGeneration;
        ViewState<F,S> nextState=state.withPage(update.page.page);JComponent view;ArchiveFilters filters;
        ArchiveClient.Binding<F,S> binding=new ArchiveClient.Binding<F,S>(){
            private boolean current(){requireEdt();return !restoring&&!closed&&!loading&&isVisible()&&state.archive&&ticket==viewGeneration&&query.equals(state.query);}
            public void queryChanged(ArchiveQuery<F,S> value){if(current())changeQuery(value);}
            public void refresh(){if(current())request(true);}
            public void viewChanged(ViewState<F,S> value){if(current()&&value.query.equals(state.query)){
                state=new ViewState<>(state.query,state.archive,state.page,value.tab,value.selected,value.anchor,value.anchorOffset,value.tables);persist();updateActions();}}
        };
        restoring=true;
        try{view=client.render(update.page,nextState,binding);filters=client.filters(update.page,nextState,binding);}catch(RuntimeException failure){update.discard();throw failure;}finally{restoring=false;}
        ArchiveResult<R> old=result;result=update.result;resultQuery=query;displayed=update.page;state=nextState;
        saved.removeAll();activeView=view;saved.add(view);restoreEnabled(saved);saved.revalidate();saved.repaint();loading=false;
        FilterChips.keepingFocus(()->{
            filterBar.drawer(filters==null?null:filters.drawer);
            FilterChips.update(filterBar,filters==null?Collections.<FilterBar.ActiveFilter>emptyList():filters.active,()->changeQuery(client.initialQuery().withScope(state.query.scope())),true);
            filterBar.setDrawerEnabled(true);
        });
        List<String> ordering=new ArrayList<>();for(ArchiveQuery.Order<S> item:query.order())ordering.add(sortLabel(item));
        String empty=displayed.matches==0?(result.scanned==0?"No rows available in this saved query. ":"No matches; use Clear to reset filters. "):"";
        status.setText(empty+displayed.description()+" · sorted by "+String.join(", ",ordering)+" · missing recording metadata means coverage unknown");
        if(old!=null&&old!=result)old.close();persist();updateActions();
    }
    /** Readable sort wording, e.g. "time (descending)", instead of raw enum names. */
    static String sortLabel(ArchiveQuery.Order<?> item){
        String field=item.field.name().toLowerCase(java.util.Locale.ROOT).replace('_',' ');
        return field+" ("+(item.direction==ArchiveQuery.Direction.ASCENDING?"ascending":"descending")+")";
    }
    private void persist(){if(stateLoadFailed)return;try{watchSave(states.save(name,state));}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}}
    private void watchSave(CompletionStage<PreferencesStore.SaveResult> save){
        long generation=++saveGeneration;save.whenComplete((value,failure)->SwingUtilities.invokeLater(()->{
            if(closed||generation!=saveGeneration)return;
            saveStatus.setText(failure==null&&value!=null&&value.isSuccess()?"View state saved":"View state is active; save failed. Change or save the view again to retry.");
        }));
    }
    private boolean canExport(){return !closed&&!loading&&!exporting&&state.archive&&result!=null&&state.query.equals(resultQuery);}
    private void invalidateView(){viewGeneration++;activeView=null;disable(saved);filterBar.setDrawerEnabled(false);}
    private void disable(Component component){
        if(!disabledStates.containsKey(component)){
            disabledStates.put(component,component.isEnabled());component.addHierarchyListener(reuseListener);
        }
        if(component instanceof Container)for(Component child:((Container)component).getComponents())disable(child);
        // UI delegates may disable children when their parent is disabled (e.g. combo arrows).
        component.setEnabled(false);
    }
    private void restoreAdoptedSubtree(Component component){
        // Container delivers child events first. Let the highest tracked ancestor's own
        // callback restore the subtree; consuming/removing child listeners earlier also
        // changes listener counts while the JDK is still traversing those descendants.
        for(Component ancestor=component;ancestor!=activeView;){
            ancestor=ancestor.getParent();
            if(ancestor==null||disabledStates.containsKey(ancestor))return;
        }
        restoreEnabled(component);
    }
    /** Only the accepted tree (including asynchronously reparented cached details) is reactivated. */
    private void restoreEnabled(Component component){
        boolean wasRestoring=restoring;restoring=true;
        try{
            Boolean enabled=disabledStates.remove(component);
            if(enabled!=null){component.removeHierarchyListener(reuseListener);component.setEnabled(enabled);}
            if(component instanceof Container)for(Component child:((Container)component).getComponents())restoreEnabled(child);
        }finally{restoring=wasRestoring;}
    }
    private void forgetDisabledStates(){
        for(Component component:new ArrayList<>(disabledStates.keySet()))component.removeHierarchyListener(reuseListener);
        disabledStates.clear();
    }
    private void updateActions(){boolean ready=canExport();exportAll.setEnabled(ready);exportPage.setEnabled(ready);exportSelected.setEnabled(ready&&!state.selected.isEmpty());
        previous.setEnabled(!loading&&state.page>0);next.setEnabled(!loading&&displayed!=null&&displayed.more());
        stop.setVisible(loading);cancelExport.setVisible(exporting);views.setEnabled(state.archive);}
    public SwingWorker<Path,Void> exportTo(Path directory,String base,ExportSelection selection,ArchiveExport.Format format)throws java.io.IOException {
        requireEdt();if(!canExport())throw new IllegalStateException("Wait for the displayed query revision before exporting");
        return startExport(result.lease(),directory,base,selection,format);
    }
    SwingWorker<Path,Void> startExport(ArchiveResult.Lease<R> lease,Path directory,String base,ExportSelection selection,
            ArchiveExport.Format format){
        requireEdt();
        String revision=lease.manifest().get("revision").getAsString();
        return launch(new ExportTask<Path>(lease,false){
            protected Path runExport()throws Exception{return client.writeExport(lease,selection,format,directory,base,token);}
            protected void discard(Path output)throws Exception{if(output!=null)java.nio.file.Files.deleteIfExists(output);}
            protected boolean completed(Path path){
                exportFolder=path.toAbsolutePath().getParent();openFolder.setEnabled(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
                status.setText("Export complete · revision "+revision.substring(0,8)+" · "+path.getFileName());return false;
            }
        });
    }
    /** The ⋯ export items and headless tests use the same asynchronous pre-confirmation path. */
    SwingWorker<String,Void> prepareExport(ExportSelection selection,java.util.function.BiPredicate<ArchiveResult.Lease<R>,String> confirm)throws java.io.IOException {
        requireEdt();if(!canExport())throw new IllegalStateException("Wait for the displayed query revision before exporting");
        ArchiveResult.Lease<R> lease=result.lease();
        return launch(new ExportTask<String>(lease,true){
            protected String runExport()throws Exception{return client.previewExport(lease,selection,token);}
            protected boolean completed(String text){return confirm.test(lease,text);}
        });
    }
    private <T> SwingWorker<T,Void> launch(ExportTask<T> worker){
        exporting=true;exportCancel=worker.token;updateActions();worker.execute();return worker;
    }
    /** Future completion and writer exit are separate when cancel(false) is used. */
    private abstract class ExportTask<T> extends SwingWorker<T,Void> {
        final ArchiveResult.Lease<R> lease;
        final Cancellation token=new Cancellation(this::isCancelled);
        private final boolean transferLease;
        private final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean(),exited=new java.util.concurrent.atomic.AtomicBoolean(),published=new java.util.concurrent.atomic.AtomicBoolean();
        private T value;private Throwable failure;
        ExportTask(ArchiveResult.Lease<R> lease,boolean transferLease){this.lease=lease;this.transferLease=transferLease;}
        protected abstract T runExport()throws Exception;
        protected abstract boolean completed(T value);
        protected void discard(T value)throws Exception { }
        protected T doInBackground()throws Exception {
            if(!claimed.compareAndSet(false,true))throw new java.util.concurrent.CancellationException();
            try{token.check();value=runExport();token.check();return value;}
            catch(Exception|Error error){failure=error;throw error;}
            finally{
                if(!transferLease||failure!=null||token.isCancelled())lease.close();
                exited.set(true);SwingUtilities.invokeLater(this::publishCompletion);
            }
        }
        protected void done(){
            if(isCancelled())token.cancel();
            if(claimed.compareAndSet(false,true))exited.set(true);
            publishCompletion();
        }
        private void publishCompletion(){
            if(!isDone()||!exited.get()||!published.compareAndSet(false,true))return;
            if(closed||token.isCancelled()){
                // A final cancellation can race with a writer returning its owned output path.
                java.util.concurrent.CompletableFuture.runAsync(()->{
                    try{discard(value);}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}finally{lease.close();}
                }).whenComplete((ignored,error)->SwingUtilities.invokeLater(()->{
                    exporting=false;status.setText(error==null?"Export cancelled; unfinished output cleaned":"Export cancelled; cleanup failed: "+error.getMessage());updateActions();
                }));return;
            }
            boolean transferred=false;
            try{
                if(failure!=null)status.setText("Export failed: "+failure.getMessage());
                else transferred=completed(value);
            }catch(RuntimeException error){status.setText("Export could not finish: "+error.getMessage());}
            finally{if(!transferred){lease.close();exporting=false;}updateActions();}
        }
    }
    private void chooseExport(ExportSelection selection){
        if(!canExport())return;
        try{prepareExport(selection,(held,message)->{
            JTextArea preview=ContentStyle.wrappingText(message);preview.getAccessibleContext().setAccessibleName("Export population and revision");
            JScrollPane previewScroll=new JScrollPane(preview);previewScroll.setPreferredSize(new Dimension(600,240));
            Object format=JOptionPane.showInputDialog(this,previewScroll,"Export pinned revision",JOptionPane.PLAIN_MESSAGE,null,ArchiveExport.Format.values(),ArchiveExport.Format.JSON);
            if(!(format instanceof ArchiveExport.Format)||closed||exportCancel.isCancelled())return false;JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION||closed||exportCancel.isCancelled())return false;
            // Modal dialogs run a nested EDT loop: the displayed result may have changed since preview.
            startExport(held,chooser.getSelectedFile().toPath(),name+"-"+System.currentTimeMillis(),selection,(ArchiveExport.Format)format);return true;
        });}catch(java.io.IOException|RuntimeException failure){status.setText("Export could not start: "+failure.getMessage());}
    }
    private void openLibrary(){
        Window parent=SwingUtilities.getWindowAncestor(this);JDialog dialog=new JDialog(parent,"History library",Dialog.ModalityType.MODELESS);
        HistoryLibrary library=new HistoryLibrary(store,id->{selectSession(id);showSaved();dialog.dispose();},librarySelection==null?state.query.resolvedScope(store):librarySelection);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){librarySelection=library.rememberedSelection();library.close();if(!closed)reloadCatalog();}});
        dialog.setContentPane(library);dialog.setSize(900,550);dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    @Override public void removeNotify(){if(!closed){invalidateView();cancel.cancel();refresh.invalidate();if(result!=null){result.close();result=null;displayed=null;}}super.removeNotify();}
    @Override public void close(){requireEdt();if(closed)return;closed=true;invalidateView();forgetDisabledStates();cancel.cancel();catalogCancel.cancel();exportCancel.cancel();refresh.invalidate();catalog.invalidate();if(result!=null){result.close();result=null;}}
    private static final class Choice{final String id,label;Choice(String id,String label){this.id=id;this.label=label;}public String toString(){return label;}}
    private static final class Update<R>{final ArchiveResult<R> result;final ArchivePage<R> page;final boolean owns;
        Update(ArchiveResult<R> result,ArchivePage<R> page,boolean owns){this.result=result;this.page=page;this.owns=owns;}
        void discard(){if(owns)result.close();}}
}
```

- [ ] **Step 8: Rewrite the archive helpers in `ArchiveNativeSupport.java`**

Replace the import block lines:
```java
import java.util.Arrays;
import java.util.Map;
```
with:
```java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
```
Replace:
```java
import tomato.gui.modern.WorkspaceShell;
```
with:
```java
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.Motion;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.WorkspaceShell;
```
Replace:
```java
import util.PreferencesStore;
```
with:
```java
import util.PreferencesStore;
import util.PropertiesManager;
```
Replace the whole `archiveControls` method:
```java
    public static void archiveControls(ArchiveWorkspace<?,?,?> workspace, String module, String table, String detail) {
        assertTrue(workspace.state().archive); assertTrue(ready(workspace));
        JTable rows = named(workspace, table, JTable.class); assertNull("Global order must not become page-local", rows.getRowSorter());
        tableRows(rows);
        reachable(named(workspace, module + "-session-picker", JComboBox.class));
        reachable(named(workspace, module + "-history-search", JTextField.class));
        for (String label : new String[]{"Current live view", "Previous page", "Next page",
                "Export selected…", "Export page…", "Export all matches…"}) completeButton(button(workspace, label));
        completeButton(find(workspace, AbstractButton.class, b -> b.isShowing() && "Columns…".equals(b.getText())));
        if (detail != null) completeText(named(workspace, detail, JTextArea.class));
    }
```
with:
```java
    public static void archiveControls(ArchiveWorkspace<?,?,?> workspace, String module, String table, String detail) {
        assertTrue(workspace.state().archive); assertTrue(ready(workspace));
        JTable rows = named(workspace, table, JTable.class); assertNull("Global order must not become page-local", rows.getRowSorter());
        tableRows(rows);
        reachable(named(workspace, module + "-session-picker", JComboBox.class));
        reachable(named(workspace, module + "-history-search", JTextField.class));
        for (String label : new String[]{"Current live view", "Previous page", "Next page"}) completeButton(button(workspace, label));
        OverflowMenu more = more(workspace); reachable(more);
        for (String label : new String[]{"History library…", "Saved views", "Save current view…", "Reset saved state",
                "Export selected…", "Export page…", "Export all matches…", "Open export folder"}) assertNotNull("Overflow action " + label, more.item(label));
        completeButton(find(workspace, AbstractButton.class, b -> b.isShowing() && "Columns…".equals(b.getText())));
        if (detail != null) completeText(named(workspace, detail, JTextArea.class));
    }

    /** The workspace's ⋯ menu: exports, saved views and History library live there since P1c. */
    public static OverflowMenu more(ArchiveWorkspace<?,?,?> workspace) {
        String module = workspace.getName().substring(0, workspace.getName().length() - "-session-view".length());
        return named(workspace, module + "-more", OverflowMenu.class);
    }

    public static JMenuItem action(ArchiveWorkspace<?,?,?> workspace, String label) {
        JMenuItem item = more(workspace).item(label); assertNotNull("Overflow action " + label, item); return item;
    }

    /** Chip labels in display order, read from each chip's remove button ("Remove filter: <label>"). */
    public static List<String> chipLabels(FilterBar bar) { List<String> labels = new ArrayList<>(); collectChips(bar, labels); return labels; }

    private static void collectChips(Container root, List<String> labels) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && "remove-filter".equals(child.getName()))
                labels.add(((AbstractButton)child).getAccessibleContext().getAccessibleName().substring("Remove filter: ".length()));
            else if (child instanceof Container) collectChips((Container)child, labels);
        }
    }

    public static void removeChip(FilterBar bar, String label) {
        find(bar, AbstractButton.class, b -> "remove-filter".equals(b.getName())
            && ("Remove filter: " + label).equals(b.getAccessibleContext().getAccessibleName())).doClick();
    }

    /**
     * Opens or closes a filter drawer without its 100 ms animation (Reduce motion for this call only), so geometry
     * assertions see the final layout. Tests that open a drawer close it again in finally: the state persists.
     */
    public static void drawer(FilterBar bar, boolean open) {
        String motion = PropertiesManager.getProperty(Motion.REDUCE_KEY);
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "true");
        try { bar.setDrawerOpen(open); } finally { PropertiesManager.setProperties(Motion.REDUCE_KEY, motion == null ? "" : motion); }
    }
```
In `failExport`, replace:
```java
            await(() -> textPresent(workspace, "Export failed:") && button(workspace, "Export all matches…").isEnabled());
```
with:
```java
            await(() -> textPresent(workspace, "Export failed:") && action(workspace, "Export all matches…").isEnabled());
```
In `preview`, replace:
```java
        await(() -> button(workspace, "Export selected…").isEnabled());
        SwingUtilities.invokeLater(() -> button(workspace, "Export selected…").doClick());
```
with:
```java
        await(() -> action(workspace, "Export selected…").isEnabled());
        SwingUtilities.invokeLater(() -> action(workspace, "Export selected…").doClick());
```
and replace:
```java
            await(() -> !preview.isShowing() && button(workspace, "Export all matches…").isEnabled());
```
with:
```java
            await(() -> !preview.isShowing() && action(workspace, "Export all matches…").isEnabled());
```

- [ ] **Step 9: Update the other tests that looked up toolbar buttons**

`src/test/java/tomato/gui/history/ArchiveLifecycleRegressionTest.java`, replace:
```java
                assertFalse("Export must remain busy while its writer is blocked",edt(()->button(f.workspace,"Export all matches…").isEnabled()));
                release.countDown();await(()->button(f.workspace,"Export all matches…").isEnabled());assertEquals(0,children(f.output));
```
with:
```java
                assertFalse("Export must remain busy while its writer is blocked",edt(()->ArchiveNativeSupport.action(f.workspace,"Export all matches…").isEnabled()));
                release.countDown();await(()->ArchiveNativeSupport.action(f.workspace,"Export all matches…").isEnabled());assertEquals(0,children(f.output));
```
(`button(f.workspace,"Current live view")` on line 41 keeps working: the toggle is still a `JButton` in the scope slot.)

`src/test/java/tomato/gui/history/ArchiveExportHookTest.java`, replace:
```java
    private static void awaitExportIdle(ArchiveWorkspace<?,?,?> workspace)throws Exception{await(()->enabledExport(workspace));}
    private static boolean enabledExport(java.awt.Container root){for(java.awt.Component component:root.getComponents()){if(component instanceof JButton&&"Export all matches…".equals(((JButton)component).getText()))return component.isEnabled();if(component instanceof java.awt.Container&&enabledExport((java.awt.Container)component))return true;}return false;}
```
with:
```java
    private static void awaitExportIdle(ArchiveWorkspace<?,?,?> workspace)throws Exception{await(()->ArchiveNativeSupport.action(workspace,"Export all matches…").isEnabled());}
```

`src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java`, replace:
```java
                AbstractButton library = button(workspace, "History library…");
```
with:
```java
                JMenuItem library = tomato.gui.history.ArchiveNativeSupport.action(workspace, "History library…");
```
(The following `assertNotNull`, `isEnabled`, `isVisible` and `getActionListeners().length == 1` assertions stay as they are.)

No other test changes are needed for this task: `ChatBookmarkIntentTest` (~58, ~78), `InspectArchiveClientTest` (~80), `InspectArchiveNativeTest` (~60), `ArchiveCompoundControlTest` (~41) and `ArchiveLifecycleRegressionTest` (~41) click `Current live view`/`Browse saved`, which remain `JButton`s in the workspace tree; `SocialArchiveNativeTest` (~49–60) finds `Column preset` inside the rendered view, which is unchanged. Search the test tree once more to prove nothing else looks the old controls up:

Run: `grep -rn '"Save view"\|"Load view"\|"Delete view"\|"Reset saved state"\|"Open export folder"\|"Cancel export"\|"Cancel read"\|-named-views\|"History library…"\|"Export all matches…"\|"Export page…"\|"Export selected…"' src/test/java`
Expected: matches only in `ArchiveNativeSupport.java`, `ArchiveFilterBarTest.java`, `ArchiveLifecycleRegressionTest.java`, `ArchiveExportHookTest.java` and `ShellHookIntegrationTest.java`; every overflow label is looked up through `action(...)`/`more(...)`/`OverflowMenu.item(...)`, and only the footer buttons (`Cancel read`, `Cancel export`) through `button(...)`. `EncounterLibraryNativeTest`, `QuestGuiTest` and `LootLayoutEvidenceTest` also mention `"Reset filters"`, but those are the encounter library, Quests and live Loot buttons, which keep that text (Tasks 6–7).

- [ ] **Step 10: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.history.*" --tests "tomato.gui.chat.ShellHookIntegrationTest" --tests "tomato.gui.chat.ChatBookmarkIntentTest" --tests "tomato.gui.security.InspectArchiveClientTest"`
Expected: PASS (`BUILD SUCCESSFUL`; every `build/p1c/test-results/test/*.xml` touched has `failures="0" errors="0"`).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/tomato/gui/history/ArchiveFilters.java src/main/java/tomato/gui/history/FilterChips.java src/main/java/tomato/gui/history/WrapRow.java \
  src/main/java/tomato/gui/history/ArchiveClient.java src/main/java/tomato/gui/history/ArchiveWorkspace.java \
  src/test/java/tomato/gui/history/ArchiveFilterBarTest.java src/test/java/tomato/gui/history/ArchiveNativeSupport.java \
  src/test/java/tomato/gui/history/ArchiveLifecycleRegressionTest.java src/test/java/tomato/gui/history/ArchiveExportHookTest.java \
  src/test/java/tomato/gui/chat/ShellHookIntegrationTest.java
git commit -m "Move the archive toolbar into a filter row, overflow menu and footer

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Runs, Timeline, Resources and Inspect archives — facets in the drawer

`ActivityArchiveClient` serves the `runs`, `timeline`, `combat` and (with a visit renderer) `inspect` workspaces.

**Files:**
- Modify: `src/main/java/tomato/gui/activity/ActivityArchiveClient.java`
- Create test: `src/test/java/tomato/gui/activity/ActivityArchiveFiltersTest.java`

**Interfaces:**
- Consumes: Task 1 `ArchiveClient.filters`, `ArchiveFilters(JComponent, List<ActiveFilter>)`, `ArchiveFilters.dates(...)`, `ArchiveFilters.summary(Collection<?>)`, `ArchiveWorkspace.filterBar()`, `ArchiveNativeSupport.chipLabels/removeChip/ready/Memory`; existing `ActivityQueries.Filters` (`outcomes`, `evidence`, `kinds`, `minimumDurationMillis`, `maximumDurationMillis`, `captureIssues`, `timingGaps`, `assignment`, `visitSession`, `visitId`), `Presence {ANY, PRESENT, ABSENT}`, `Assignment {ALL, ASSIGNED, UNASSIGNED}`.
- Produces: `ActivityArchiveClient.filters(page, state, binding)` override; package-private `static String ActivityArchiveClient.durationLabel(ActivityQueries.Filters)` ("≥ 30 s", "≤ 600 s", "30–600 s"), reused by `ActivityPanel` in Task 5. Chip labels: `Outcome: …`, `Evidence: …`, `With capture issues`/`No capture issues`, `With timing gaps`/`No timing gaps`, `Duration …`, `Types: …`, `Assigned events`/`Unassigned events`, `Exact visit`, and the date label from `ArchiveFilters.dateLabel`.

- [ ] **Step 1: Write the failing test**

`src/test/java/tomato/gui/activity/ActivityArchiveFiltersTest.java`:
```java
package tomato.gui.activity;

import java.awt.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import static org.junit.Assert.*;

/** Saved Runs facets live in the workspace Filters drawer and read back as removable chips. */
public class ActivityArchiveFiltersTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void runFacetsMoveToTheDrawerAndBecomeRemovableChips()throws Exception{
        Path scratch=temp.newFolder().toPath();ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            for(int i=0;i<3;i++){ActivityJournal.Visit v=new ActivityJournal.Visit();v.id="visit-"+i;v.map="Lost Halls";v.started=1000+i*100_000L;v.lastSeen=v.ended=v.started+60_000;store.put("runs",v.id,v);}
            store.flush();
            ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace=edt(()->ActivityPanel.workspace(store,new JLabel("Live runs"),ActivityPanel.Mode.RUNS,scratch,memory.states));
            try{
                edt(()->{workspace.showSaved();return null;});await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    FilterBar bar=workspace.filterBar();JComponent drawer=bar.drawerContent();Container view=(Container)named(workspace,"activity-archive-runs");
                    assertNotNull("Run facets live in the Filters drawer",drawer);assertFalse(bar.drawerOpen());
                    assertNotNull(find(drawer,AbstractButton.class,b->b.getText()!=null&&b.getText().startsWith("Outcome: ")));
                    assertNotNull(find(drawer,AbstractButton.class,b->"Date bounds…".equals(b.getText())));
                    assertNull("The view no longer repeats the facet row",find(view,AbstractButton.class,b->"Date bounds…".equals(b.getText())));
                    assertEquals(0,bar.activeCount());
                    ActivityQueries.Filters f=new ActivityQueries.Filters();f.outcomes.add(ActivityQueries.Outcome.COMPLETED);f.timingGaps=ActivityQueries.Presence.PRESENT;
                    f.minimumDurationMillis=30_000L;f.visitSession=store.currentId();f.visitId="visit-1";
                    workspace.changeQuery(workspace.state().query.withFacets(f).withBounds(new ArchiveQuery.Bounds(0L,10_000_000L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false)));return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    assertEquals(Arrays.asList("Outcome: Completed","With timing gaps","Duration ≥ 30 s","Exact visit","1970-01-01 00:00 – 1970-01-01 02:46"),
                        ArchiveNativeSupport.chipLabels(workspace.filterBar()));
                    Container view=(Container)named(workspace,"activity-archive-runs");
                    assertNotNull("The exact-link action stays beside the view",find(view,AbstractButton.class,b->"Clear exact visit link".equals(b.getText())));
                    ArchiveNativeSupport.removeChip(workspace.filterBar(),"Exact visit");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.state().query.facets().visitId.isEmpty());
                edt(()->{
                    assertEquals(Collections.singleton(ActivityQueries.Outcome.COMPLETED),workspace.state().query.facets().outcomes);
                    assertEquals("",workspace.state().query.facets().visitSession);
                    ArchiveNativeSupport.removeChip(workspace.filterBar(),"1970-01-01 00:00 – 1970-01-01 02:46");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.state().query.bounds().from==null);
                edt(()->{ArchiveQuery.Bounds b=workspace.state().query.bounds();assertNull(b.until);assertEquals("UTC",b.zone);assertFalse(b.includeUnknown);return null;});
            }finally{edt(()->{workspace.close();return null;});}
        }
    }

    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T> T edt(Checked<T> value)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{result.set(value.get());}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());return result.get();
    }
    private static void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Timed out waiting for EDT state");
    }
    private static Component named(Container root,String name){return find(root,Component.class,c->name.equals(c.getName()));}
    private static <T extends Component> T find(Container root,Class<T> type,Predicate<T> test){
        for(Component child:root.getComponents()){
            if(type.isInstance(child)&&test.test(type.cast(child)))return type.cast(child);
            if(child instanceof Container){T found=find((Container)child,type,test);if(found!=null)return found;}
        }return null;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.activity.ActivityArchiveFiltersTest"`
Expected: FAIL — `java.lang.AssertionError: Run facets live in the Filters drawer` (the client still returns the default `null` drawer).

- [ ] **Step 3: Implement in `ActivityArchiveClient.java`**

Replace:
```java
import tomato.gui.modern.ContentStyle;
```
with:
```java
import tomato.gui.kit.FilterBar;
import tomato.gui.modern.ContentStyle;
```
Replace:
```java
        if(currentView!=null)currentView.retire();
        currentView=new View(page,state,binding);return currentView;
    }
```
with:
```java
        if(currentView!=null)currentView.retire();
        currentView=new View(page,state,binding);return currentView;
    }
    /** The facets of the view just rendered, for the workspace drawer; exact-link and window actions stay beside the view. */
    @Override public ArchiveFilters filters(ArchivePage<Row> page,ViewState<Filters,Sort> state,Binding<Filters,Sort> binding) {
        return currentView!=null&&currentView.binding==binding?currentView.filters():null;
    }
```
In the `View` constructor replace:
```java
            JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
            top.add(queryControls());
            top.add(HistoryTables.controls(table,defaults,presets,layout->{this.state=this.state.withTable("activity",layout);remember();}));
```
with:
```java
            JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
            top.add(HistoryTables.controls(table,defaults,presets,layout->{this.state=this.state.withTable("activity",layout);remember();}));
```
and replace:
```java
                window.setText(windowText()+(exact?"\nLinked outcome: reading from this revision…":""));top.add(window);
            }
            add(top,BorderLayout.NORTH);
```
with:
```java
                window.setText(windowText()+(exact?"\nLinked outcome: reading from this revision…":""));top.add(window);
            }
            JPanel links=linkActions();if(links.getComponentCount()>0)top.add(links);
            add(top,BorderLayout.NORTH);
```
Replace the tail of `queryControls()`:
```java
            JButton dates=new JButton("Date bounds…");dates.addActionListener(e->dates());controls.add(dates);
            ArchiveQuery.Bounds span=state.query.bounds();
            if(mode==ActivityPanel.Mode.TIMELINE&&span.from!=null&&span.until!=null) {
                JButton widen=new JButton("Widen window ±30 s");widen.setName("timeline-widen-window");
                widen.setToolTipText("Adjust both half-open bounds by 30 seconds; displayed rows and exports keep using one query");
                widen.addActionListener(e->binding.queryChanged(state.query.withBounds(new ArchiveQuery.Bounds(span.from-ActivityRoutes.AROUND_MILLIS,
                        span.until+ActivityRoutes.AROUND_MILLIS,ZoneId.of(span.zone),span.mode,span.includeUnknown))));controls.add(widen);
            }
            if(!f.visitId.isEmpty()) {
                JButton clear=new JButton("Clear exact visit link");clear.setToolTipText(f.visitSession+" / "+f.visitId);
                clear.addActionListener(e->{Filters next=this.state.query.facets();next.visitId=next.visitSession="";change(next);});controls.add(clear);
            }
            return controls;
        }
```
with:
```java
            JButton dates=new JButton("Date bounds…");dates.addActionListener(e->dates());controls.add(dates);
            return controls;
        }
        /** Window and exact-link actions act on the displayed window, so they stay beside it instead of in the Filters drawer. */
        private JPanel linkActions() {
            JPanel actions=ContentStyle.controls();Filters f=state.query.facets();ArchiveQuery.Bounds span=state.query.bounds();
            if(mode==ActivityPanel.Mode.TIMELINE&&span.from!=null&&span.until!=null) {
                JButton widen=new JButton("Widen window ±30 s");widen.setName("timeline-widen-window");
                widen.setToolTipText("Adjust both half-open bounds by 30 seconds; displayed rows and exports keep using one query");
                widen.addActionListener(e->binding.queryChanged(state.query.withBounds(new ArchiveQuery.Bounds(span.from-ActivityRoutes.AROUND_MILLIS,
                        span.until+ActivityRoutes.AROUND_MILLIS,ZoneId.of(span.zone),span.mode,span.includeUnknown))));actions.add(widen);
            }
            if(!f.visitId.isEmpty()) {
                JButton clear=new JButton("Clear exact visit link");clear.setToolTipText(f.visitSession+" / "+f.visitId);
                clear.addActionListener(e->{Filters next=this.state.query.facets();next.visitId=next.visitSession="";change(next);});actions.add(clear);
            }
            return actions;
        }
        /** This view's existing facet controls for the Filters drawer, and one chip per narrowing facet. */
        ArchiveFilters filters() {
            List<FilterBar.ActiveFilter> chips=new ArrayList<>();Filters f=state.query.facets();
            if(mode==ActivityPanel.Mode.TIMELINE) {
                if(!f.kinds.isEmpty())chips.add(chip("Types: "+ArchiveFilters.summary(f.kinds),next->next.kinds=new LinkedHashSet<>()));
                if(f.assignment!=Assignment.ALL)chips.add(chip(f.assignment==Assignment.ASSIGNED?"Assigned events":"Unassigned events",next->next.assignment=Assignment.ALL));
            } else {
                if(!f.outcomes.isEmpty())chips.add(chip("Outcome: "+ArchiveFilters.summary(f.outcomes),next->next.outcomes=new LinkedHashSet<>()));
                if(!f.evidence.isEmpty())chips.add(chip("Evidence: "+ArchiveFilters.summary(f.evidence),next->next.evidence=new LinkedHashSet<>()));
                if(f.captureIssues!=Presence.ANY)chips.add(chip(f.captureIssues==Presence.PRESENT?"With capture issues":"No capture issues",next->next.captureIssues=Presence.ANY));
                if(f.timingGaps!=Presence.ANY)chips.add(chip(f.timingGaps==Presence.PRESENT?"With timing gaps":"No timing gaps",next->next.timingGaps=Presence.ANY));
                if(f.minimumDurationMillis!=null||f.maximumDurationMillis!=null)chips.add(chip("Duration "+durationLabel(f),next->{next.minimumDurationMillis=null;next.maximumDurationMillis=null;}));
            }
            if(!f.visitId.isEmpty())chips.add(chip("Exact visit",next->next.visitId=next.visitSession=""));
            ArchiveFilters.dates(chips,state.query,binding::queryChanged);
            return new ArchiveFilters(queryControls(),chips);
        }
        private FilterBar.ActiveFilter chip(String label,Consumer<Filters> reset) {
            return new FilterBar.ActiveFilter(label,()->{Filters next=state.query.facets();reset.accept(next);change(next);});
        }
```
In `select(...)`, replace the empty-result wording:
```java
                    :"No saved matches in this query. Reset filters or change scope; recording coverage is unknown."):"Select a saved visit or event for full details.");return;}
```
with:
```java
                    :"No saved matches in this query. Clear filters or change scope; recording coverage is unknown."):"Select a saved visit or event for full details.");return;}
```
Replace:
```java
    private static String seconds(Long millis) { return millis==null?"":java.math.BigDecimal.valueOf(millis,3).stripTrailingZeros().toPlainString(); }
```
with:
```java
    private static String seconds(Long millis) { return millis==null?"":java.math.BigDecimal.valueOf(millis,3).stripTrailingZeros().toPlainString(); }
    /** "30–600 s", "≥ 30 s" or "≤ 600 s": chip text for a duration facet (saved and live Runs). */
    static String durationLabel(Filters f) {
        String min=seconds(f.minimumDurationMillis),max=seconds(f.maximumDurationMillis);
        return f.minimumDurationMillis!=null&&f.maximumDurationMillis!=null?min+"–"+max+" s":f.minimumDurationMillis!=null?"≥ "+min+" s":"≤ "+max+" s";
    }
```

- [ ] **Step 4: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.activity.*" --tests "tomato.gui.security.InspectArchiveClientTest" --tests "tomato.gui.history.*"`
Expected: PASS. `ActivityRouteTest` still clicks `timeline-widen-window` and `WaveThreeEvidenceTest` still sees "Widen window ±30 s" in the view.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/activity/ActivityArchiveClient.java src/test/java/tomato/gui/activity/ActivityArchiveFiltersTest.java
git commit -m "Put saved Runs and Timeline facets in the filter drawer with chips

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Loot and Statistics archives — facets in the drawer

**Files:**
- Create: `src/main/java/tomato/gui/stats/LootFacetChips.java`
- Modify: `src/main/java/tomato/gui/stats/LootFacetControls.java`, `LootArchiveClient.java`
- Modify test: `src/test/java/tomato/gui/stats/StatisticsArchiveNativeTest.java`
- Create tests: `src/test/java/tomato/gui/stats/LootFacetChipsTest.java`, `LootArchiveFiltersTest.java`

**Interfaces:**
- Consumes: Task 1 API; existing `LootQuery.Facets` (`view`, `bags`, `dungeons`, `rarities`, `tiers`, `kind`, `slots`, `applied`, `character`, `enemy`, `variant`, `visitSession`, `visitId`, `drilled()`), `LootQuery.Range` (`min`, `max`, `unknown`), `LootQuery.Kind`, `LootQuery.Unknown`, `LootFacetControls`, `CohortControls`, `HistoricalStatistics.lootWorkspace(SessionStore, JComponent, Path, ViewStateStore)`.
- Produces: `static List<FilterBar.ActiveFilter> LootFacetChips.chips(Supplier<LootQuery.Facets> current, Consumer<LootQuery.Facets> changed)` (also used by the live `LootDashboard` in Task 6); `static String LootFacetChips.range(LootQuery.Range)`; package-private `static String LootFacetControls.kind(LootQuery.Kind)`; `LootArchiveClient.filters(...)` override. Cohort inputs (`CohortControls`) stay in the Cohorts view because they define the comparison itself; that view's date controls move to the drawer.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/stats/LootFacetChipsTest.java`:
```java
package tomato.gui.stats;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tomato.gui.kit.FilterBar;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

public class LootFacetChipsTest {
    @Test public void narrowingFacetsBecomeChipsAndRemovalResetsOnlyThatFacet() {
        LootQuery.Facets f = new LootQuery.Facets(); f.view = LootQuery.View.OCCURRENCES;
        f.bags.addAll(Arrays.asList("White", "Orange", "Blue")); f.kind = LootQuery.Kind.UT_EQUIPMENT;
        f.slots.min = 2; f.applied.unknown = LootQuery.Unknown.EXCLUDE; f.variant = "910000/2/1";
        AtomicReference<LootQuery.Facets> applied = new AtomicReference<>();
        List<FilterBar.ActiveFilter> chips = LootFacetChips.chips(() -> copy(f), applied::set);
        List<String> labels = new ArrayList<>(); for (FilterBar.ActiveFilter chip : chips) labels.add(chip.label);
        assertEquals(Arrays.asList("Bags: White, Orange +1", "UT equipment", "Slots ≥ 2", "Applied enchants any, unknown excluded", "Exact variant"), labels);
        chips.get(0).remove.run();
        assertTrue(applied.get().bags.isEmpty()); assertEquals(LootQuery.Kind.UT_EQUIPMENT, applied.get().kind);
        assertEquals("910000/2/1", applied.get().variant); assertEquals(LootQuery.View.OCCURRENCES, applied.get().view);
        chips.get(4).remove.run();
        assertNull(applied.get().variant); assertEquals(3, applied.get().bags.size());
        assertTrue(LootFacetChips.chips(LootQuery.Facets::new, applied::set).isEmpty());
    }

    private static LootQuery.Facets copy(LootQuery.Facets f) { return SessionStore.JSON.fromJson(SessionStore.JSON.toJson(f), LootQuery.Facets.class); }
}
```

`src/test/java/tomato/gui/stats/LootArchiveFiltersTest.java`:
```java
package tomato.gui.stats;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
import tomato.history.SessionStore;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/** Saved loot facets and dates live in the drawer; drill-down and cohort inputs stay in the view. */
public class LootArchiveFiltersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void lootFacetsMoveToTheDrawerAndBecomeChips() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore source = new SessionStore(root, true, "loot-filters")) {
            for (int row = 0; row < 4; row++) source.append("loot", new LootDashboard.Drop(row < 3 ? "White" : "Orange", "Lost Halls", "Synthetic boss", 2000 + row * 1000L,
                Collections.singletonList(new LootDashboard.Item(910000 + row, "Needle blade " + row, "EQUIPMENT,WEAPON,UT", ParseEnchants.summarize(""))), "visit-1"));
            source.flush();
        }
        try (SessionStore store = new SessionStore(root, true, "reader")) {
            ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> workspace = edt(() -> HistoricalStatistics.lootWorkspace(store, new LootDashboard(), scratch, memory.states));
            try {
                edt(() -> { workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 4);
                edt(() -> {
                    FilterBar bar = workspace.filterBar(); JComponent drawer = bar.drawerContent();
                    assertNotNull(drawer); assertNotNull(named(drawer, "loot-date-from", JTextField.class));
                    assertTrue(SwingUtilities.isDescendingFrom(named(workspace, "loot-apply-facets", JButton.class), drawer));
                    assertNull("Dates are no longer repeated in the view", named(named(workspace, "loot-archive-tabs", JTabbedPane.class), "loot-date-from", JTextField.class));
                    LootQuery.Facets f = workspace.state().query.facets(); f.bags.add("White"); f.kind = LootQuery.Kind.UT_EQUIPMENT;
                    workspace.changeQuery(workspace.state().query.withFacets(f)); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 3);
                edt(() -> { assertEquals(Arrays.asList("Bags: White", "UT equipment"), ArchiveNativeSupport.chipLabels(workspace.filterBar()));
                    ArchiveNativeSupport.removeChip(workspace.filterBar(), "Bags: White"); return null; });
                await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 4);
                edt(() -> { LootQuery.Facets f = workspace.state().query.facets(); assertTrue(f.bags.isEmpty()); assertEquals(LootQuery.Kind.UT_EQUIPMENT, f.kind);
                    assertEquals(LootQuery.View.OCCURRENCES, f.view); return null; });
            } finally { edt(() -> { workspace.close(); return null; }); }
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.stats.LootFacetChipsTest" --tests "tomato.gui.stats.LootArchiveFiltersTest"`
Expected: FAIL — compilation error `cannot find symbol: variable LootFacetChips`.

- [ ] **Step 3: Make the kind label reusable in `LootFacetControls.java`**

Replace:
```java
    private static String kind(Kind kind){switch(kind){case UT_EQUIPMENT:return "UT equipment";case ST:return "ST items";case STAT_POTION:return "Stat potions";case HIGH_TIER:return "High tier";default:return kind.toString();}}
```
with:
```java
    static String kind(Kind kind){switch(kind){case UT_EQUIPMENT:return "UT equipment";case ST:return "ST items";case STAT_POTION:return "Stat potions";case HIGH_TIER:return "High tier";default:return kind.toString();}}
```

- [ ] **Step 4: Create `LootFacetChips.java`**

```java
package tomato.gui.stats;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tomato.gui.history.ArchiveFilters;
import tomato.gui.kit.FilterBar;
import tomato.gui.stats.LootQuery.*;

/** Removable chips for loot facets, shared by the saved Loot/Statistics drawer and the live Loot explorer. */
final class LootFacetChips {
    private LootFacetChips() {}

    /**
     * One chip per narrowing facet. current must return a fresh copy; removing a chip resets only that facet on the
     * facets current at click time, so the view, cohorts and every other facet are kept.
     */
    static List<FilterBar.ActiveFilter> chips(Supplier<Facets> current, Consumer<Facets> changed) {
        Facets f = current.get(); List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        if (!f.bags.isEmpty()) chips.add(chip("Bags: " + ArchiveFilters.summary(f.bags), current, changed, n -> n.bags = new LinkedHashSet<>()));
        if (!f.dungeons.isEmpty()) chips.add(chip("Dungeons: " + ArchiveFilters.summary(f.dungeons), current, changed, n -> n.dungeons = new LinkedHashSet<>()));
        if (f.kind != null && f.kind != Kind.ANY) chips.add(chip(LootFacetControls.kind(f.kind), current, changed, n -> n.kind = Kind.ANY));
        if (!f.rarities.isEmpty()) chips.add(chip("Rarity: " + ArchiveFilters.summary(f.rarities), current, changed, n -> n.rarities = new LinkedHashSet<>()));
        if (!f.tiers.isEmpty()) chips.add(chip("Tier: " + ArchiveFilters.summary(f.tiers), current, changed, n -> n.tiers = new LinkedHashSet<>()));
        if (narrows(f.slots)) chips.add(chip("Slots " + range(f.slots), current, changed, n -> n.slots = new Range()));
        if (narrows(f.applied)) chips.add(chip("Applied enchants " + range(f.applied), current, changed, n -> n.applied = new Range()));
        if (!f.character.isEmpty()) chips.add(chip("Character ID " + f.character, current, changed, n -> n.character = ""));
        if (!f.enemy.isEmpty()) chips.add(chip("Enemy ID " + f.enemy, current, changed, n -> n.enemy = ""));
        if (f.drilled()) chips.add(chip(f.variant != null && f.visitSession != null ? "Exact variant and run" : f.variant != null ? "Exact variant" : "Exact run",
            current, changed, n -> { n.variant = null; n.visitSession = null; n.visitId = null; }));
        return chips;
    }

    private static FilterBar.ActiveFilter chip(String label, Supplier<Facets> current, Consumer<Facets> changed, Consumer<Facets> reset) {
        return new FilterBar.ActiveFilter(label, () -> { Facets next = current.get(); reset.accept(next); changed.accept(next); });
    }

    private static boolean narrows(Range r) { return r != null && (r.min != null || r.max != null || r.unknown != Unknown.INCLUDE); }

    /** "2–4", "≥ 2", "≤ 4" or "any", plus the unknown-value policy when it is not the default. */
    static String range(Range r) {
        String bounds = r.min != null && r.max != null ? r.min + "–" + r.max : r.min != null ? "≥ " + r.min : r.max != null ? "≤ " + r.max : "any";
        return bounds + (r.unknown == Unknown.EXCLUDE ? ", unknown excluded" : r.unknown == Unknown.ONLY ? " (unknown only)" : "");
    }
}
```

- [ ] **Step 5: Move facets and dates into the drawer in `LootArchiveClient.java`**

Replace:
```java
import tomato.gui.history.*;
```
with:
```java
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){return new Render(page,state,binding);}
```
with:
```java
    private Render rendered;
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){rendered=new Render(page,state,binding);return rendered;}
    /** The rendered view's facet and date controls for the drawer; drill-down actions and cohort inputs stay in the view. */
    @Override public ArchiveFilters filters(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){return rendered!=null&&rendered.binding==binding?rendered.filters():null;}
```
In the `Render` constructor replace:
```java
            JPanel top=new JPanel(new BorderLayout(0,4));
            if(view.loot())top.add(new LootFacetControls(state.query.facets(),choices("facet.bag."),choices("facet.dungeon."),f->query(current.query.withFacets(f))),BorderLayout.NORTH);
            else top.add(analyticalFilters(view),BorderLayout.NORTH);
            top.add(dateControls(),BorderLayout.CENTER);countText=ContentStyle.wrappingText(description(view)+"\n"+countDescription(page));countText.setName("loot-archive-counts");top.add(countText,BorderLayout.SOUTH);body.add(top,BorderLayout.NORTH);
```
with:
```java
            JPanel top=new JPanel(new BorderLayout(0,4));
            // Cohort inputs define the comparison itself, so they stay in the view; every other facet lives in the Filters drawer.
            if(view==View.COHORTS)top.add(analyticalFilters(view),BorderLayout.NORTH);
            countText=ContentStyle.wrappingText(description(view)+"\n"+countDescription(page));countText.setName("loot-archive-counts");top.add(countText,BorderLayout.SOUTH);body.add(top,BorderLayout.NORTH);
```
Add this method to `Render`, directly after `private void query(ArchiveQuery<Facets,Sort> q){binding.queryChanged(q);}`:
```java
        ArchiveFilters filters(){
            View view=current.query.facets().view;JPanel drawer=new JPanel(new BorderLayout(0,4));
            if(view.loot())drawer.add(new LootFacetControls(current.query.facets(),choices("facet.bag."),choices("facet.dungeon."),f->query(current.query.withFacets(f))),BorderLayout.NORTH);
            else if(view!=View.COHORTS)drawer.add(analyticalFilters(view),BorderLayout.NORTH);
            drawer.add(dateControls(),BorderLayout.CENTER);
            List<FilterBar.ActiveFilter> chips=LootFacetChips.chips(current.query::facets,f->query(current.query.withFacets(f)));
            ArchiveFilters.dates(chips,current.query,this::query);
            return new ArchiveFilters(drawer,chips);
        }
```

- [ ] **Step 6: Open the drawer where the native Loot test uses facet controls**

`src/test/java/tomato/gui/stats/StatisticsArchiveNativeTest.java`, replace:
```java
                edt(() -> {
                    find(workspace,AbstractButton.class,c -> c.isShowing() && "Multi-select loot facets…".equals(c.getText())).doClick();
                    find(workspace,JComboBox.class,c -> c.isShowing() && "loot-kind".equals(c.getName())).setSelectedItem(Kind.UT_EQUIPMENT);
                    find(workspace,JButton.class,c -> c.isShowing() && "loot-apply-facets".equals(c.getName())).doClick(); return null;
                });
```
with:
```java
                edt(() -> {
                    drawer(workspace.filterBar(), true);
                    find(workspace,AbstractButton.class,c -> c.isShowing() && "Multi-select loot facets…".equals(c.getText())).doClick();
                    find(workspace,JComboBox.class,c -> c.isShowing() && "loot-kind".equals(c.getName())).setSelectedItem(Kind.UT_EQUIPMENT);
                    find(workspace,JButton.class,c -> c.isShowing() && "loot-apply-facets".equals(c.getName())).doClick();
                    drawer(workspace.filterBar(), false); return null;
                });
```
(`drawer` comes from the existing `import static tomato.gui.history.ArchiveNativeSupport.*;`.)
(Closing again keeps the matrix screenshots and `ui.filters.loot.open` as before.) `LootDrillDownTest`, `CohortComparisonTest`, `WaveThreeJourneyTest` and `stats/WaveThreeEvidenceTest` use drill-down buttons, drill summaries and cohort inputs, which stay in the view.

- [ ] **Step 7: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.stats.*" --tests "tomato.WaveThreeJourneyTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/tomato/gui/stats/LootFacetChips.java src/main/java/tomato/gui/stats/LootFacetControls.java src/main/java/tomato/gui/stats/LootArchiveClient.java \
  src/test/java/tomato/gui/stats/LootFacetChipsTest.java src/test/java/tomato/gui/stats/LootArchiveFiltersTest.java src/test/java/tomato/gui/stats/StatisticsArchiveNativeTest.java
git commit -m "Put saved loot facets and dates in the filter drawer with chips

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Chat and Key-pops archives — facets in the drawer

**Files:**
- Modify: `src/main/java/tomato/gui/chat/ChatArchiveClient.java`, `src/main/java/tomato/gui/keypop/KeyPopArchiveClient.java`
- Create tests: `src/test/java/tomato/gui/chat/ChatArchiveFiltersTest.java`, `src/test/java/tomato/gui/keypop/KeyPopArchiveFiltersTest.java`

**Interfaces:**
- Consumes: Task 1 API; existing `SocialQueryControls.State<R,F,S>` (`value`, `query(ArchiveQuery)`, `owner(JComponent)`), `SocialQueryControls.labeled/dates/lines`; package-private `ChatMessage.Channel.label`, `KeyPopEvent.Kind.label`.
- Produces: `ChatArchiveClient.filters(...)` and `KeyPopArchiveClient.filters(...)` overrides (drawer controls share the rendered view's `State`, so a drawer query retires the view exactly as before); package-private `static String KeyPopArchiveClient.kinds(Set<String>)`. Chip labels: Chat `Channel: Guild`, `Player: …`, `Starred only`, `Ignored players shown`/`Ignored players hidden` (only when different from the default `initialQuery().facets().showIgnoredPlayers`), dates; Key-pops `Player: …`, `Types: Key, Vial`, `Dungeons/items: …`, dates. Mode tabs, table controls, message and export actions stay in the rendered view.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/chat/ChatArchiveFiltersTest.java`:
```java
package tomato.gui.chat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/** Saved Chat facets and dates live in the drawer; the message table and actions stay in the view. */
public class ChatArchiveFiltersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String ignored;
    @Before public void defaultIgnoredPlayers() { ignored = PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS); PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, "false"); }
    @After public void restoreIgnoredPlayers() { PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, ignored == null ? "" : ignored); }

    @Test public void chatFacetsMoveToTheDrawerAndBecomeChips() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory(); String session;
        try (SessionStore source = new SessionStore(root, true, "chat-filters")) {
            session = source.currentId();
            source.append("chat", new ChatMessage(LocalDateTime.of(2026, 9, 22, 12, 0), ChatMessage.Channel.GUILD, "Ann", "", "Ann", "guild hello", ""));
            source.append("chat", new ChatMessage(LocalDateTime.of(2026, 9, 22, 12, 1), ChatMessage.Channel.WORLD, "Bo", "", "Bo", "world hello", ""));
            source.flush();
        }
        try (SessionStore store = new SessionStore(root, false, "reader")) {
            ChatArchiveClient client = new ChatArchiveClient(store, new ChatFilters(), null, scratch);
            ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> ws = edt(() -> SessionPanel.queried(store, "chat", new JPanel(), client, memory.states));
            try {
                edt(() -> { ws.changeQuery(ChatArchiveClient.query().withScope(session)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 2);
                edt(() -> {
                    JComponent drawer = ws.filterBar().drawerContent();
                    assertTrue(SwingUtilities.isDescendingFrom(named(ws, "chat-archive-channel", JComboBox.class), drawer));
                    assertTrue(SwingUtilities.isDescendingFrom(named(ws, "social-date-from", JTextField.class), drawer));
                    assertFalse("Message actions stay in the view", SwingUtilities.isDescendingFrom(button(ws, "Toggle star"), drawer));
                    named(ws, "chat-archive-channel", JComboBox.class).setSelectedItem(ChatMessage.Channel.GUILD); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 1);
                edt(() -> { assertEquals(Collections.singletonList("Channel: Guild"), ArchiveNativeSupport.chipLabels(ws.filterBar()));
                    ArchiveNativeSupport.removeChip(ws.filterBar(), "Channel: Guild"); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 2);
                edt(() -> { assertEquals("ALL", ws.state().query.facets().channel); assertEquals(0, ws.filterBar().activeCount()); return null; });
            } finally { edt(() -> { ws.close(); return null; }); }
        }
    }
}
```

`src/test/java/tomato/gui/keypop/KeyPopArchiveFiltersTest.java`:
```java
package tomato.gui.keypop;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/** Saved contribution facets live in the drawer; mode tabs and exports stay in the view. */
public class KeyPopArchiveFiltersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void contributionFacetsMoveToTheDrawerAndBecomeChips() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory(); String session;
        try (SessionStore source = new SessionStore(root, true, "keypop-filters")) {
            session = source.currentId(); Instant base = Instant.parse("2026-09-22T12:00:00Z");
            source.append("keypops", new KeyPopEvent(base, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY));
            source.append("keypops", new KeyPopEvent(base.plusSeconds(1), "Bo", "Ice Citadel", KeyPopEvent.Kind.VIAL));
            source.flush();
        }
        try (SessionStore store = new SessionStore(root, false, "reader")) {
            ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> ws =
                edt(() -> SessionPanel.queried(store, "keypops", new JPanel(), new KeyPopArchiveClient(scratch), memory.states));
            try {
                edt(() -> { ws.changeQuery(KeyPopArchiveClient.query().withScope(session)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 2);
                edt(() -> {
                    JComponent drawer = ws.filterBar().drawerContent();
                    assertTrue(SwingUtilities.isDescendingFrom(named(ws, "keypop-archive-player", JTextField.class), drawer));
                    assertFalse("Mode tabs stay in the view", SwingUtilities.isDescendingFrom(named(ws, "keypop-archive-tabs", JTabbedPane.class), drawer));
                    KeyPopArchiveClient.Facets f = ws.state().query.facets(); f.exactPlayer = "Ann"; f.kinds.add("KEY");
                    ws.changeQuery(ws.state().query.withFacets(f)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 1);
                edt(() -> { assertEquals(Arrays.asList("Player: Ann", "Types: Key"), ArchiveNativeSupport.chipLabels(ws.filterBar()));
                    ArchiveNativeSupport.removeChip(ws.filterBar(), "Player: Ann"); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.state().query.facets().exactPlayer.isEmpty());
                edt(() -> { assertEquals(Collections.singleton("KEY"), ws.state().query.facets().kinds); return null; });
            } finally { edt(() -> { ws.close(); return null; }); }
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.chat.ChatArchiveFiltersTest" --tests "tomato.gui.keypop.KeyPopArchiveFiltersTest"`
Expected: FAIL — `java.lang.AssertionError` at the first `assertTrue(SwingUtilities.isDescendingFrom(... drawer))`: both clients still return the default `null` drawer, so `drawerContent()` is `null`.

- [ ] **Step 3: Move Chat facets into `filters()` in `ChatArchiveClient.java`**

Replace:
```java
import tomato.gui.history.*;
```
with:
```java
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private long generation;
    private final Map<String,String> policies = new ConcurrentHashMap<>();
```
with:
```java
    private long generation;
    private final Map<String,String> policies = new ConcurrentHashMap<>();
    private SocialQueryControls.State<Row,Facets,Sort> rendered;
    private Binding<Facets,Sort> renderedBinding;
```
In `render(...)` replace:
```java
        Facets f = initial.query.facets(); JPanel controls = ContentStyle.controls();
        JComboBox<ChatMessage.Channel> channel = new JComboBox<>(ChatMessage.Channel.values()); channel.setSelectedItem(ChatMessage.Channel.valueOf(f.channel));
        JTextField player = new JTextField(f.player, 12); JCheckBox stars = new JCheckBox("Starred only", f.starredOnly), ignored = new JCheckBox("Show ignored players", f.showIgnoredPlayers);
        controls.add(SocialQueryControls.labeled("Channel", channel, "chat-archive-channel"));
        controls.add(SocialQueryControls.labeled("Sender or recipient contains", player, "chat-archive-player")); controls.add(stars); controls.add(ignored);
        JButton apply = new JButton("Apply Chat filters"); controls.add(apply);
        Runnable change = () -> { Facets next = new Facets(); next.channel = ((ChatMessage.Channel)channel.getSelectedItem()).name();
            next.player = player.getText(); next.starredOnly = stars.isSelected(); next.showIgnoredPlayers = ignored.isSelected(); state.query(state.value.query.withFacets(next)); };
        apply.addActionListener(e -> change.run()); player.addActionListener(e -> change.run()); channel.addActionListener(e -> change.run());
        stars.addActionListener(e -> change.run()); ignored.addActionListener(e -> change.run());
        JPanel header = new JPanel(new BorderLayout(0, 6)); header.add(controls, BorderLayout.NORTH);
        header.add(SocialQueryControls.dates(initial.query.bounds(), true, b -> state.query(state.value.query.withBounds(b))));
        JTextArea detail
```
with:
```java
        rendered = state; renderedBinding = binding;
        JTextArea detail
```
and replace:
```java
        view.add(ContentStyle.page(header, body, lower)); state.owner(view); policyChanged.run(); return view;
    }
```
with:
```java
        view.add(ContentStyle.page(null, body, lower)); state.owner(view); policyChanged.run(); return view;
    }
    /** Channel, player, star and ignore facets plus dates of the view just rendered; they share its State. */
    @Override public ArchiveFilters filters(ArchivePage<Row> page, ViewState<Facets,Sort> initial, Binding<Facets,Sort> binding) {
        if (rendered == null || renderedBinding != binding) return null;
        SocialQueryControls.State<Row,Facets,Sort> state = rendered; Facets f = initial.query.facets(); JPanel controls = ContentStyle.controls();
        JComboBox<ChatMessage.Channel> channel = new JComboBox<>(ChatMessage.Channel.values()); channel.setSelectedItem(ChatMessage.Channel.valueOf(f.channel));
        JTextField player = new JTextField(f.player, 12); JCheckBox stars = new JCheckBox("Starred only", f.starredOnly), ignored = new JCheckBox("Show ignored players", f.showIgnoredPlayers);
        controls.add(SocialQueryControls.labeled("Channel", channel, "chat-archive-channel"));
        controls.add(SocialQueryControls.labeled("Sender or recipient contains", player, "chat-archive-player")); controls.add(stars); controls.add(ignored);
        JButton apply = new JButton("Apply Chat filters"); controls.add(apply);
        Runnable change = () -> { Facets next = new Facets(); next.channel = ((ChatMessage.Channel)channel.getSelectedItem()).name();
            next.player = player.getText(); next.starredOnly = stars.isSelected(); next.showIgnoredPlayers = ignored.isSelected(); state.query(state.value.query.withFacets(next)); };
        apply.addActionListener(e -> change.run()); player.addActionListener(e -> change.run()); channel.addActionListener(e -> change.run());
        stars.addActionListener(e -> change.run()); ignored.addActionListener(e -> change.run());
        JPanel drawer = new JPanel(new BorderLayout(0, 6)); drawer.add(controls, BorderLayout.NORTH);
        drawer.add(SocialQueryControls.dates(initial.query.bounds(), true, b -> state.query(state.value.query.withBounds(b))));
        List<FilterBar.ActiveFilter> chips = new ArrayList<>(); boolean ignoredDefault = initialQuery().facets().showIgnoredPlayers;
        if (!"ALL".equals(f.channel)) chips.add(chip(state, "Channel: " + ChatMessage.Channel.valueOf(f.channel).label, next -> next.channel = "ALL"));
        if (!f.player.isEmpty()) chips.add(chip(state, "Player: " + f.player, next -> next.player = ""));
        if (f.starredOnly) chips.add(chip(state, "Starred only", next -> next.starredOnly = false));
        if (f.showIgnoredPlayers != ignoredDefault) chips.add(chip(state, f.showIgnoredPlayers ? "Ignored players shown" : "Ignored players hidden", next -> next.showIgnoredPlayers = ignoredDefault));
        ArchiveFilters.dates(chips, initial.query, state::query);
        return new ArchiveFilters(drawer, chips);
    }
    private static FilterBar.ActiveFilter chip(SocialQueryControls.State<Row,Facets,Sort> state, String label, java.util.function.Consumer<Facets> reset) {
        return new FilterBar.ActiveFilter(label, () -> { Facets next = state.value.query.facets(); reset.accept(next); state.query(state.value.query.withFacets(next)); });
    }
```

- [ ] **Step 4: Move contribution facets into `filters()` in `KeyPopArchiveClient.java`**

Replace:
```java
import tomato.gui.history.*;
```
with:
```java
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> initial,Binding<Facets,Sort> binding){
        long ticket=++generation;SocialQueryControls.State<Row,Facets,Sort> state=new SocialQueryControls.State<>(initial,binding,()->generation==ticket);
        Facets f=initial.query.facets();JPanel header=new JPanel(new BorderLayout(0,6)),filters=ContentStyle.responsiveGrid(2,220,8);
        JTextField player=new JTextField(f.exactPlayer);JTextArea items=new JTextArea(String.join("\n",f.items),3,18);
        filters.add(SocialQueryControls.labeled("Player equals (case-insensitive)",player,"keypop-archive-player"));filters.add(SocialQueryControls.labeled("Exact dungeons/items — one per line; empty = all",new JScrollPane(items),"keypop-archive-items"));
        JPanel types=ContentStyle.controls();Map<String,JCheckBox> choices=new LinkedHashMap<>();
        for(String kind:Arrays.asList("KEY","RUNE","VIAL","INC","OTHER","UNKNOWN")){JCheckBox box=new JCheckBox(kind,f.kinds.contains(kind));choices.put(kind,box);types.add(box);}
        JButton apply=new JButton("Apply contribution filters"),clearPlayer=new JButton("Clear exact player");types.add(apply);types.add(clearPlayer);
        clearPlayer.setName("keypop-archive-exact-player"); clearPlayer.setVisible(!f.exactPlayer.isEmpty());
        clearPlayer.setText("Player equals " + f.exactPlayer + " · Clear");
        Runnable change=()->{Facets next=state.value.query.facets();next.exactPlayer=player.getText().trim();next.items=SocialQueryControls.lines(items.getText());next.kinds.clear();choices.forEach((kind,box)->{if(box.isSelected())next.kinds.add(kind);});state.query(state.value.query.withFacets(next));};
        apply.addActionListener(e->change.run());player.addActionListener(e->change.run());clearPlayer.addActionListener(e->{player.setText("");change.run();});
        JPanel facets=new JPanel(new BorderLayout());facets.add(filters);facets.add(types,BorderLayout.SOUTH);header.add(facets,BorderLayout.NORTH);
        header.add(SocialQueryControls.dates(initial.query.bounds(),false,b->state.query(state.value.query.withBounds(b))));
        JTabbedPane tabs=new JTabbedPane();
```
with:
```java
    private SocialQueryControls.State<Row,Facets,Sort> rendered;private Binding<Facets,Sort> renderedBinding;
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> initial,Binding<Facets,Sort> binding){
        long ticket=++generation;SocialQueryControls.State<Row,Facets,Sort> state=new SocialQueryControls.State<>(initial,binding,()->generation==ticket);
        rendered=state;renderedBinding=binding;Facets f=initial.query.facets();
        JTabbedPane tabs=new JTabbedPane();
```
Replace:
```java
        JComponent view = ContentStyle.page(header,tabs,footer); state.owner(view); return view;
    }
```
with:
```java
        JComponent view = ContentStyle.page(null,tabs,footer); state.owner(view); return view;
    }
    /** Contribution facets and dates of the view just rendered, for the workspace drawer; they share its State. */
    @Override public ArchiveFilters filters(ArchivePage<Row> page,ViewState<Facets,Sort> initial,Binding<Facets,Sort> binding){
        if(rendered==null||renderedBinding!=binding)return null;SocialQueryControls.State<Row,Facets,Sort> state=rendered;Facets f=initial.query.facets();
        JPanel drawer=new JPanel(new BorderLayout(0,6)),filters=ContentStyle.responsiveGrid(2,220,8);
        JTextField player=new JTextField(f.exactPlayer);JTextArea items=new JTextArea(String.join("\n",f.items),3,18);
        filters.add(SocialQueryControls.labeled("Player equals (case-insensitive)",player,"keypop-archive-player"));filters.add(SocialQueryControls.labeled("Exact dungeons/items — one per line; empty = all",new JScrollPane(items),"keypop-archive-items"));
        JPanel types=ContentStyle.controls();Map<String,JCheckBox> choices=new LinkedHashMap<>();
        for(String kind:Arrays.asList("KEY","RUNE","VIAL","INC","OTHER","UNKNOWN")){JCheckBox box=new JCheckBox(kind,f.kinds.contains(kind));choices.put(kind,box);types.add(box);}
        JButton apply=new JButton("Apply contribution filters"),clearPlayer=new JButton("Clear exact player");types.add(apply);types.add(clearPlayer);
        clearPlayer.setName("keypop-archive-exact-player"); clearPlayer.setVisible(!f.exactPlayer.isEmpty());
        clearPlayer.setText("Player equals " + f.exactPlayer + " · Clear");
        Runnable change=()->{Facets next=state.value.query.facets();next.exactPlayer=player.getText().trim();next.items=SocialQueryControls.lines(items.getText());next.kinds.clear();choices.forEach((kind,box)->{if(box.isSelected())next.kinds.add(kind);});state.query(state.value.query.withFacets(next));};
        apply.addActionListener(e->change.run());player.addActionListener(e->change.run());clearPlayer.addActionListener(e->{player.setText("");change.run();});
        JPanel facets=new JPanel(new BorderLayout());facets.add(filters);facets.add(types,BorderLayout.SOUTH);drawer.add(facets,BorderLayout.NORTH);
        drawer.add(SocialQueryControls.dates(initial.query.bounds(),false,b->state.query(state.value.query.withBounds(b))));
        List<FilterBar.ActiveFilter> chips=new ArrayList<>();
        if(!f.exactPlayer.isEmpty())chips.add(chip(state,"Player: "+f.exactPlayer,next->next.exactPlayer=""));
        if(!f.kinds.isEmpty())chips.add(chip(state,"Types: "+kinds(f.kinds),next->next.kinds=new LinkedHashSet<>()));
        if(!f.items.isEmpty())chips.add(chip(state,"Dungeons/items: "+ArchiveFilters.summary(f.items),next->next.items=new LinkedHashSet<>()));
        ArchiveFilters.dates(chips,initial.query,state::query);
        return new ArchiveFilters(drawer,chips);
    }
    private static FilterBar.ActiveFilter chip(SocialQueryControls.State<Row,Facets,Sort> state,String label,java.util.function.Consumer<Facets> reset){
        return new FilterBar.ActiveFilter(label,()->{Facets next=state.value.query.facets();reset.accept(next);state.query(state.value.query.withFacets(next));});
    }
    /** Kind codes as the labels players see; unrecorded kinds stay "Unknown". */
    static String kinds(Set<String> kinds){List<String> labels=new ArrayList<>();for(String kind:kinds)labels.add("UNKNOWN".equals(kind)?"Unknown":KeyPopEvent.Kind.valueOf(kind).label);return ArchiveFilters.summary(labels);}
```

- [ ] **Step 5: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.chat.*" --tests "tomato.gui.keypop.*"`
Expected: PASS. `ChatArchiveClientTest` still finds `chat-archive-player`/`chat-archive-channel` (now in the drawer, same names) and the retired player field stays inert; `SocialArchiveNativeTest` still sets `chat-archive-channel`/`keypop-archive-player` without needing them visible.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/chat/ChatArchiveClient.java src/main/java/tomato/gui/keypop/KeyPopArchiveClient.java \
  src/test/java/tomato/gui/chat/ChatArchiveFiltersTest.java src/test/java/tomato/gui/keypop/KeyPopArchiveFiltersTest.java
git commit -m "Put saved Chat and Key-pops facets in the filter drawer with chips

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Live Runs/Timeline/Resources and live Chat filter bars

**Files:**
- Modify: `src/main/java/tomato/gui/activity/ActivityPanel.java`, `src/main/java/tomato/gui/chat/ChatExplorer.java`
- Modify tests: `src/test/java/tomato/gui/chat/ChatConsistencyTest.java`, `ChatExplorerTest.java`, `ChatFiltersTest.java`
- Create tests: `src/test/java/tomato/gui/activity/ActivityLiveFilterBarTest.java`, `src/test/java/tomato/gui/chat/ChatLiveFilterBarTest.java`

**Interfaces:**
- Consumes: Task 1 `FilterChips.update`, `WrapRow`, `ArchiveFilters.summary/dateLabel`, `ArchiveNativeSupport.chipLabels/removeChip/drawer`; Task 2 `ActivityArchiveClient.durationLabel`.
- Produces: FilterBars named `activity-runs`, `activity-timeline`, `activity-combat` (search slot: Search [+ Visit, + Activity type]; drawer: live run facets for Runs only; Clear = `setRunFilters(new Filters())`) and `chat-live` (search slot: search, Reset, Actions, Follow latest, new-message jump; drawer: player, Starred, Show ignored players, sort, dates, live views; Clear = the existing Reset). The capture/pause/export row of `ActivityPanel` stays as a separate non-filter row; the Chat channel pills stay visible below the bar. The "Dates / view state" toggle is removed (its content is in the drawer).

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/activity/ActivityLiveFilterBarTest.java`:
```java
package tomato.gui.activity;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class ActivityLiveFilterBarTest {
    @Test public void liveRunFacetsLiveInTheDrawerAndTimelineKeepsVisitBesideSearch() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                ActivityPanel runs = new ActivityPanel(log, ActivityPanel.Mode.RUNS);
                FilterBar bar = find(runs, FilterBar.class, "activity-runs-filter-bar");
                assertNotNull(bar); assertFalse(bar.drawerOpen());
                assertTrue(SwingUtilities.isDescendingFrom(find(runs, JComboBox.class, "live-run-issues"), bar.drawerContent()));
                JTextField search = find(runs, JTextField.class, "activity-search");
                assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, bar.drawerContent()));
                ActivityQueries.Filters f = new ActivityQueries.Filters(); f.outcomes.add(ActivityQueries.Outcome.COMPLETED);
                f.captureIssues = ActivityQueries.Presence.ABSENT; f.maximumDurationMillis = 600_000L; runs.setRunFilters(f);
                assertEquals(Arrays.asList("Outcome: Completed", "No capture issues", "Duration ≤ 600 s"), ArchiveNativeSupport.chipLabels(bar));
                ArchiveNativeSupport.removeChip(bar, "No capture issues");
                assertEquals(ActivityQueries.Presence.ANY, runs.runFilters().captureIssues); assertEquals(1, runs.runFilters().outcomes.size());
                find(runs, AbstractButton.class, "activity-runs-clear-filters").doClick();
                assertTrue(runs.runFilters().outcomes.isEmpty()); assertEquals(0, bar.activeCount());
                ActivityPanel timeline = new ActivityPanel(log, ActivityPanel.Mode.TIMELINE);
                FilterBar timelineBar = find(timeline, FilterBar.class, "activity-timeline-filter-bar");
                assertNull("Timeline keeps visit and type beside search", timelineBar.drawerContent());
                assertTrue(SwingUtilities.isDescendingFrom(find(timeline, JComboBox.class, "activity-visit"), timelineBar));
                assertTrue(SwingUtilities.isDescendingFrom(find(timeline, JComboBox.class, "activity-kind"), timelineBar));
            });
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/chat/ChatLiveFilterBarTest.java`:
```java
package tomato.gui.chat;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class ChatLiveFilterBarTest {
    @Test public void playerStarsAndDatesMoveToTheDrawerWhileSearchChannelsAndFollowStayVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer ui = new ChatExplorer(() -> {});
            FilterBar bar = named(ui, "chat-live-filter-bar", FilterBar.class);
            assertNotNull(bar); assertFalse(bar.drawerOpen()); JComponent drawer = bar.drawerContent();
            for (String name : new String[]{"chat-player", "chat-show-ignored-players", "chat-live-sort"})
                assertTrue(name, SwingUtilities.isDescendingFrom(named(ui, name, JComponent.class), drawer));
            assertFalse(SwingUtilities.isDescendingFrom(named(ui, "chat-search", JTextField.class), drawer));
            assertTrue(SwingUtilities.isDescendingFrom(button(ui, "Follow latest"), bar));
            assertFalse(SwingUtilities.isDescendingFrom(button(ui, "Follow latest"), drawer));
            assertFalse("Channel pills stay outside the bar", SwingUtilities.isDescendingFrom(named(ui, "chat-channel-ALL", JComponent.class), bar));
            assertNull("The drawer replaces the old toggle", button(ui, "Dates / view state"));
            named(ui, "chat-player", JTextField.class).setText("Wren"); button(ui, "Starred").doClick(); ui.refresh(false);
            List<String> labels = ArchiveNativeSupport.chipLabels(bar); labels.remove("Ignored players shown");
            assertEquals(Arrays.asList("Player: Wren", "Starred"), labels);
            ArchiveNativeSupport.removeChip(bar, "Player: Wren");
            assertEquals("", named(ui, "chat-player", JTextField.class).getText());
            named(ui, "chat-live-clear-filters", AbstractButton.class).doClick();
            assertFalse(((AbstractButton) button(ui, "Starred")).isSelected());
        });
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.activity.ActivityLiveFilterBarTest" --tests "tomato.gui.chat.ChatLiveFilterBarTest"`
Expected: FAIL — `java.lang.AssertionError` at `assertNotNull(bar)` (no FilterBar named `activity-runs-filter-bar` / `chat-live-filter-bar`).

- [ ] **Step 3: Build the live Runs/Timeline/Resources bar in `ActivityPanel.java`**

Replace:
```java
import tomato.gui.history.ViewStateStore;
```
with:
```java
import tomato.gui.history.ViewStateStore;
import tomato.gui.history.ArchiveFilters;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final JTextField minimumDuration=new JTextField(6), maximumDuration=new JTextField(6);
```
with:
```java
    private final JTextField minimumDuration=new JTextField(6), maximumDuration=new JTextField(6);
    private final FilterBar filterBar;
```
Replace:
```java
        search.setToolTipText("Search this module; text is matched literally"); controls.add(labeled("Search",search));
```
with:
```java
        search.setToolTipText("Search this module; text is matched literally");
```
Replace:
```java
        top.add(controls);
        if(mode==Mode.RUNS)top.add(runFilterControls());
        if(mode!=Mode.RUNS){
            JPanel filters=ContentStyle.controls();filters.setAlignmentX(LEFT_ALIGNMENT);
            visitPicker.setName("activity-visit"); visitPicker.setPrototypeDisplayValue(new VisitChoice("","09-09 22:00 · Recorded visit"));
            visitPicker.getAccessibleContext().setAccessibleName("Recorded visit");kind.setName("activity-kind");kind.getAccessibleContext().setAccessibleName("Activity type");
            filters.add(labeled("Visit",visitPicker)); if(mode==Mode.TIMELINE)filters.add(kind); top.add(filters);
        }
```
with:
```java
        // One filter row: search (and the visit/type selectors for Timeline and Resources); Runs keeps its facets in the drawer.
        filterBar=new FilterBar("activity-"+mode.name().toLowerCase(Locale.ROOT));filterBar.setAlignmentX(LEFT_ALIGNMENT);
        // Not "scope": the constructor already declares a JTextArea scope further down.
        WrapRow searchRow=new WrapRow(labeled("Search",search));
        if(mode!=Mode.RUNS){
            visitPicker.setName("activity-visit"); visitPicker.setPrototypeDisplayValue(new VisitChoice("","09-09 22:00 · Recorded visit"));
            visitPicker.getAccessibleContext().setAccessibleName("Recorded visit");kind.setName("activity-kind");kind.getAccessibleContext().setAccessibleName("Activity type");
            searchRow.add(labeled("Visit",visitPicker)); if(mode==Mode.TIMELINE)searchRow.add(kind);
        }
        filterBar.search(searchRow);if(mode==Mode.RUNS)filterBar.drawer(runFilterControls());
        top.add(filterBar);top.add(controls);
```
Replace:
```java
        minimumDuration.setText(durationSeconds(runFilters.minimumDurationMillis));maximumDuration.setText(durationSeconds(runFilters.maximumDurationMillis));
    }finally{restoringState=previous;}}
```
with:
```java
        minimumDuration.setText(durationSeconds(runFilters.minimumDurationMillis));maximumDuration.setText(durationSeconds(runFilters.maximumDurationMillis));
    }finally{restoringState=previous;}updateRunChips();}
    /** Live run facets as removable chips; Clear resets them like "Reset run filters". */
    private void updateRunChips(){
        if(filterBar==null||mode!=Mode.RUNS)return;List<FilterBar.ActiveFilter> chips=new ArrayList<>();ActivityQueries.Filters f=runFilters;
        if(!f.outcomes.isEmpty())chips.add(runChip("Outcome: "+ArchiveFilters.summary(f.outcomes),next->next.outcomes=new LinkedHashSet<>()));
        if(!f.evidence.isEmpty())chips.add(runChip("Evidence: "+ArchiveFilters.summary(f.evidence),next->next.evidence=new LinkedHashSet<>()));
        if(f.captureIssues!=ActivityQueries.Presence.ANY)chips.add(runChip(f.captureIssues==ActivityQueries.Presence.PRESENT?"With capture issues":"No capture issues",next->next.captureIssues=ActivityQueries.Presence.ANY));
        if(f.timingGaps!=ActivityQueries.Presence.ANY)chips.add(runChip(f.timingGaps==ActivityQueries.Presence.PRESENT?"With timing gaps":"No timing gaps",next->next.timingGaps=ActivityQueries.Presence.ANY));
        if(f.minimumDurationMillis!=null||f.maximumDurationMillis!=null)chips.add(runChip("Duration "+ActivityArchiveClient.durationLabel(f),next->{next.minimumDurationMillis=null;next.maximumDurationMillis=null;}));
        FilterChips.update(filterBar,chips,()->setRunFilters(new ActivityQueries.Filters()),false);
    }
    private FilterBar.ActiveFilter runChip(String label,java.util.function.Consumer<ActivityQueries.Filters> reset){
        return new FilterBar.ActiveFilter(label,()->{ActivityQueries.Filters next=runFilters();reset.accept(next);setRunFilters(next);});
    }
```

- [ ] **Step 4: Build the live Chat bar in `ChatExplorer.java`**

Replace:
```java
import tomato.gui.history.*;
```
with:
```java
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final JTextArea liveStateStatus = ContentStyle.wrappingText("");
```
with:
```java
    private final JTextArea liveStateStatus = ContentStyle.wrappingText("");
    private final FilterBar filterBar = new FilterBar("chat-live");
```
Replace:
```java
        JPanel filters = new JPanel(new BorderLayout(0, 6));
        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        search.setName("chat-search"); search.putClientProperty("JTextField.placeholderText", "Search messages, players, or dates…");
        search.getAccessibleContext().setAccessibleName("Search chat history");
        search.setToolTipText("Literal, case-insensitive search within the selected channel. Ctrl+F to focus; Esc to clear.");
        searchRow.add(search, BorderLayout.CENTER);
        JButton reset = new JButton("Reset"); reset.setToolTipText("Reset all search and channel filters");
        reset.addActionListener(e -> resetFilters());
        JPanel searchActions = new JPanel(new GridLayout(1, 2, 6, 0));
        searchActions.add(reset); searchActions.add(actions); searchRow.add(searchActions, BorderLayout.EAST);
        filters.add(searchRow, BorderLayout.NORTH);
        JPanel filterRow = ContentStyle.controls();
```
with:
```java
        JPanel filters = new JPanel(new BorderLayout(0, 6));
        search.setName("chat-search"); search.putClientProperty("JTextField.placeholderText", "Search messages, players, or dates…");
        search.getAccessibleContext().setAccessibleName("Search chat history"); search.setColumns(22);
        search.setToolTipText("Literal, case-insensitive search within the selected channel. Ctrl+F to focus; Esc to clear.");
        JButton reset = new JButton("Reset"); reset.setToolTipText("Reset all search and channel filters");
        reset.addActionListener(e -> resetFilters());
        JPanel filterRow = ContentStyle.controls();
```
Replace:
```java
        filterRow.add(playerRow); filterRow.add(starredOnly); filterRow.add(follow); filterRow.add(showIgnoredPlayers);
        JButton advanced = new JButton("Dates / view state"); filterRow.add(advanced);
        extra.setVisible(false); advanced.addActionListener(e -> { extra.setVisible(!extra.isVisible()); revalidate(); });
        arrivals.setName("chat-new-messages"); arrivals.setVisible(false);
        arrivals.addActionListener(e -> { follow.setSelected(true); unseen.clear(); updateArrivals(); scrollToLatest(); rememberState(); });
        filterRow.add(arrivals);
```
with:
```java
        filterRow.add(playerRow); filterRow.add(starredOnly); filterRow.add(showIgnoredPlayers);
        arrivals.setName("chat-new-messages"); arrivals.setVisible(false);
        arrivals.addActionListener(e -> { follow.setSelected(true); unseen.clear(); updateArrivals(); scrollToLatest(); rememberState(); });
        // Search, reset, actions, follow and the new-message jump stay visible; player, star, ignore, sort, dates and views live in the drawer.
        JPanel drawer = new JPanel(new BorderLayout(0, 6)); drawer.add(filterRow, BorderLayout.NORTH); drawer.add(extra, BorderLayout.CENTER);
        filterBar.search(new WrapRow(search, reset, actions, follow, arrivals)).drawer(drawer);
        filters.add(filterBar, BorderLayout.NORTH);
```
Replace:
```java
        filters.add(filterRow, BorderLayout.CENTER);
        ((FlowLayout) channelRow.getLayout()).setHgap(4);
```
with:
```java
        ((FlowLayout) channelRow.getLayout()).setHgap(4);
```
Replace:
```java
        JPanel context = new JPanel(new BorderLayout()); context.add(filterStatus, BorderLayout.NORTH); context.add(extra);
```
with:
```java
        JPanel context = new JPanel(new BorderLayout()); context.add(filterStatus, BorderLayout.NORTH);
```
Replace:
```java
        updateArrivals(); rebuilding = false; rememberState();
    }
```
with:
```java
        updateArrivals(); updateChips(); rebuilding = false; rememberState();
    }

    /** Player, star, ignored-player and date filters as removable chips; the channel pills and search show themselves. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        if (!player.getText().trim().isEmpty()) chips.add(new FilterBar.ActiveFilter("Player: " + player.getText().trim(), () -> { player.setText(""); refresh(false); }));
        if (starredOnly.isSelected()) chips.add(new FilterBar.ActiveFilter("Starred", () -> { starredOnly.setSelected(false); refresh(false); }));
        if (showIgnoredPlayers.isSelected()) chips.add(new FilterBar.ActiveFilter("Ignored players shown", showIgnoredPlayers::doClick));
        if (timeBounds.from != null || timeBounds.until != null) chips.add(new FilterBar.ActiveFilter(ArchiveFilters.dateLabel(timeBounds), () -> {
            timeBounds = new ArchiveQuery.Bounds(null, null, java.time.ZoneId.of(timeBounds.zone), timeBounds.mode, timeBounds.includeUnknown); rebuildDates(); refresh(false); }));
        FilterChips.update(filterBar, chips, this::resetFilters, false);
    }
```

- [ ] **Step 5: Open the Chat drawer in tests that measure moved controls**

`src/test/java/tomato/gui/chat/ChatConsistencyTest.java`, replace:
```java
            chat[0] = new ChatExplorer(() -> {}, filters, () -> "Remote whisper identities may be unavailable.");
```
with:
```java
            chat[0] = new ChatExplorer(() -> {}, filters, () -> "Remote whisper identities may be unavailable.");
            tomato.gui.history.ArchiveNativeSupport.drawer(find(chat[0], "chat-live-filter-bar", tomato.gui.kit.FilterBar.class), true);
```
and replace:
```java
        SwingUtilities.invokeAndWait(() -> { ContentStyle.setBodyFont(previousFont); setLaf(previousLaf); });
    }
```
with:
```java
        SwingUtilities.invokeAndWait(() -> { ContentStyle.setBodyFont(previousFont); setLaf(previousLaf); });
        PropertiesManager.setProperties("ui.filters.chat-live.open", "false");
    }
```

`src/test/java/tomato/gui/chat/ChatExplorerTest.java`, replace:
```java
ChatExplorer ui = new ChatExplorer(() -> {}); JFrame frame = new JFrame("Chat · sample data");
```
with:
```java
ChatExplorer ui = new ChatExplorer(() -> {}); JFrame frame = new JFrame("Chat · sample data");
            tomato.gui.kit.FilterBar filterBar = find(ui, tomato.gui.kit.FilterBar.class, "chat-live-filter-bar"); tomato.gui.history.ArchiveNativeSupport.drawer(filterBar, true);
```
and replace:
```java
            } finally { frame.dispose(); }
        });
    }

    private static void sample(ChatExplorer ui) {
```
with:
```java
            } finally { frame.dispose(); tomato.gui.history.ArchiveNativeSupport.drawer(filterBar, false); }
        });
    }

    private static void sample(ChatExplorer ui) {
```

`src/test/java/tomato/gui/chat/ChatFiltersTest.java`, replace:
```java
                ChatExplorer ui = new ChatExplorer(() -> {}, filters, () -> "");
                ui.accept(pm("Buy at shop.example - fast delivery!"));
```
with:
```java
                ChatExplorer ui = new ChatExplorer(() -> {}, filters, () -> "");
                tomato.gui.history.ArchiveNativeSupport.drawer(find(ui, tomato.gui.kit.FilterBar.class, "chat-live-filter-bar"), true);
                ui.accept(pm("Buy at shop.example - fast delivery!"));
```
and replace:
```java
            } finally { frame.dispose(); }
        }); } finally { PropertiesManager.setProperties("chat.filters", old == null ? "{}" : old); }
    }
    private static void snapshot(JFrame frame, String name) {
```
with:
```java
            } finally { frame.dispose(); PropertiesManager.setProperties("ui.filters.chat-live.open", "false"); }
        }); } finally { PropertiesManager.setProperties("chat.filters", old == null ? "{}" : old); }
    }
    private static void snapshot(JFrame frame, String name) {
```
Other Chat tests (`ChatArrivalStateTest`, `ChatVisibilityStateTest`, `IgnoredPlayerVisibilityTest`, `ChatExplorerTest` lines 74–104, `ChatHistoryTest`) only call `doClick`/`setText`/`isSelected`/`isVisible` on the moved controls, which do not require them to be showing; `ActivityLiveStateTest` likewise drives `live-run-*` controls without showing them.

- [ ] **Step 6: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.activity.*" --tests "tomato.gui.chat.*"`
Expected: PASS (includes `ActivityLiveStateTest` "Outcome fits at compact width", `ChatConsistencyTest`, `ChatExplorerTest`, `ChatFiltersTest`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tomato/gui/activity/ActivityPanel.java src/main/java/tomato/gui/chat/ChatExplorer.java \
  src/test/java/tomato/gui/activity/ActivityLiveFilterBarTest.java src/test/java/tomato/gui/chat/ChatLiveFilterBarTest.java \
  src/test/java/tomato/gui/chat/ChatConsistencyTest.java src/test/java/tomato/gui/chat/ChatExplorerTest.java src/test/java/tomato/gui/chat/ChatFiltersTest.java
git commit -m "Give live Runs, Timeline, Resources and Chat one filter row

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Live Key-pops and live Loot filter bars

**Files:**
- Modify: `src/main/java/tomato/gui/keypop/KeyPopDashboard.java`, `src/main/java/tomato/gui/stats/LootDashboard.java`
- Create tests: `src/test/java/tomato/gui/keypop/KeyPopLiveFilterBarTest.java`, `src/test/java/tomato/gui/stats/LootLiveFilterBarTest.java`

**Interfaces:**
- Consumes: Task 1 `FilterChips`, `WrapRow`, `ArchiveFilters.summary/dateLabel`; Task 3 `LootFacetChips.chips(Supplier, Consumer)`; existing `KeyPopDashboard.selectPlayer/resetFilters/refresh`, `LootDashboard.applyFacets`.
- Produces: FilterBar `keypops-live` (search slot: search + "Reset filters"; drawer: exact-player button `keypop-exact-player`, type/period/item grid, "Multi-select / absolute dates / view state" toggle and its panel; Clear = `resetFilters()`), chips `Player: …`, `Type: …`, period label or date label, `Dungeon / item: …`, `More types: …`, `More dungeons/items: …`. FilterBar `loot-live` (search slot: search + "Reset filters"; drawer: bag and dungeon choices + `LootFacetControls`; Clear = the Reset filters button), chips from `LootFacetChips`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/keypop/KeyPopLiveFilterBarTest.java`:
```java
package tomato.gui.keypop;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class KeyPopLiveFilterBarTest {
    @Test public void keyPopFiltersLiveInTheDrawerWithChips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopHistory history = new KeyPopHistory(); Instant now = Instant.now();
            history.add(new KeyPopEvent(now, "Ann", "Halls", KeyPopEvent.Kind.KEY)); history.add(new KeyPopEvent(now.plusSeconds(1), "Bo", "Shatters", KeyPopEvent.Kind.VIAL));
            KeyPopDashboard ui = new KeyPopDashboard(history);
            FilterBar bar = find(ui, FilterBar.class, "keypops-live-filter-bar"); JComponent drawer = bar.drawerContent();
            assertTrue(SwingUtilities.isDescendingFrom(ui.type, drawer)); assertTrue(SwingUtilities.isDescendingFrom(ui.playerChip, drawer));
            assertTrue(SwingUtilities.isDescendingFrom(ui.search, bar)); assertFalse(SwingUtilities.isDescendingFrom(ui.search, drawer));
            ui.type.setSelectedItem("Key"); ui.selectPlayer("Ann");
            assertEquals(Arrays.asList("Player: Ann", "Type: Key"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Type: Key");
            assertEquals(0, ui.type.getSelectedIndex()); assertTrue(ui.playerChip.isVisible());
            find(ui, AbstractButton.class, "keypops-live-clear-filters").doClick();
            assertFalse(ui.playerChip.isVisible()); assertEquals(0, bar.activeCount());
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/stats/LootLiveFilterBarTest.java`:
```java
package tomato.gui.stats;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class LootLiveFilterBarTest {
    @Test public void liveLootFacetsLiveInTheDrawerWithChips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootDashboard dashboard = new LootDashboard();
            FilterBar bar = find(dashboard, FilterBar.class, "loot-live-filter-bar");
            assertTrue(SwingUtilities.isDescendingFrom(find(dashboard, JComboBox.class, "loot-bag-filter"), bar.drawerContent()));
            JTextField search = find(dashboard, JTextField.class, "loot-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, bar.drawerContent()));
            LootQuery.Facets f = new LootQuery.Facets(); f.kind = LootQuery.Kind.STAT_POTION; f.tiers.add("UT"); dashboard.applyFacets(f);
            assertEquals(Arrays.asList("Stat potions", "Tier: UT"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Stat potions");
            assertEquals(Collections.singletonList("Tier: UT"), ArchiveNativeSupport.chipLabels(bar));
            find(dashboard, AbstractButton.class, "loot-live-clear-filters").doClick();
            assertEquals(0, bar.activeCount());
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.keypop.KeyPopLiveFilterBarTest" --tests "tomato.gui.stats.LootLiveFilterBarTest"`
Expected: FAIL — `java.lang.NullPointerException` at `bar.drawerContent()` (no FilterBar named `keypops-live-filter-bar` / `loot-live-filter-bar` yet).

- [ ] **Step 3: Build the live Key-pops bar in `KeyPopDashboard.java`**

Replace:
```java
import tomato.gui.history.*;
```
with:
```java
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final javax.swing.Timer refreshTimer;
    private List<KeyPopEvent> filtered = Collections.emptyList();
```
with:
```java
    private final FilterBar filterBar = new FilterBar("keypops-live");
    private final javax.swing.Timer refreshTimer;
    private List<KeyPopEvent> filtered = Collections.emptyList();
```
Replace:
```java
        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        search.setName("keypop-search"); search.putClientProperty("JTextField.placeholderText", "Search player, dungeon or item…");
        search.getAccessibleContext().setAccessibleName("Search key-pops");
        search.setToolTipText("Case-insensitive search; every word must match the event.");
        searchRow.add(search); searchRow.add(button("Reset filters", this::resetFilters), BorderLayout.EAST);
        constraints.gridy++; top.add(searchRow, constraints);
        playerChip.setName("keypop-exact-player"); playerChip.setVisible(false);
        playerChip.addActionListener(e -> selectPlayer(null));
        constraints.gridy++; top.add(playerChip, constraints);
        JPanel filters = ContentStyle.responsiveGrid(3, 140, 8);
        filters.add(labeled("Event type", type, "keypop-type")); filters.add(labeled("Time range", period, "keypop-period"));
        item.setPrototypeDisplayValue(ALL_ITEMS); filters.add(labeled("Dungeon / item", item, "keypop-item"));
        constraints.gridy++; constraints.insets = new Insets(0, 0, 0, 0); top.add(filters, constraints);
        JButton more = new JButton("Multi-select / absolute dates / view state");
        more.addActionListener(e -> { advanced.setVisible(!advanced.isVisible()); revalidate(); });
        constraints.gridy++; top.add(more, constraints); constraints.gridy++; top.add(advanced, constraints); advanced.setVisible(false);
```
with:
```java
        search.setName("keypop-search"); search.putClientProperty("JTextField.placeholderText", "Search player, dungeon or item…");
        search.getAccessibleContext().setAccessibleName("Search key-pops"); search.setColumns(22);
        search.setToolTipText("Case-insensitive search; every word must match the event.");
        playerChip.setName("keypop-exact-player"); playerChip.setVisible(false);
        playerChip.addActionListener(e -> selectPlayer(null));
        JPanel filters = ContentStyle.responsiveGrid(3, 140, 8);
        filters.add(labeled("Event type", type, "keypop-type")); filters.add(labeled("Time range", period, "keypop-period"));
        item.setPrototypeDisplayValue(ALL_ITEMS); filters.add(labeled("Dungeon / item", item, "keypop-item"));
        JButton more = new JButton("Multi-select / absolute dates / view state");
        more.addActionListener(e -> { advanced.setVisible(!advanced.isVisible()); revalidate(); });
        // One filter row (search + reset); the exact player, type/period/item and multi-select/date/view controls live in the drawer.
        JPanel drawer = new JPanel(new GridBagLayout()); GridBagConstraints row = new GridBagConstraints(); row.gridx = 0; row.weightx = 1;
        row.fill = GridBagConstraints.HORIZONTAL; row.insets = new Insets(0, 0, 6, 0);
        for (JComponent part : new JComponent[]{playerChip, filters, more, advanced}) { row.gridy++; drawer.add(part, row); } advanced.setVisible(false);
        filterBar.search(new WrapRow(search, button("Reset filters", this::resetFilters))).drawer(drawer);
        constraints.gridy++; constraints.insets = new Insets(0, 0, 0, 0); top.add(filterBar, constraints);
```
Replace:
```java
        rebuilding = false; rememberState();
    }

    private KeyPopArchiveClient.Facets liveFacets() {
```
with:
```java
        updateChips(); rebuilding = false; rememberState();
    }

    /** Exact player, type, period or dates, dungeon/item and multi-select choices as removable chips. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        if (exactPlayer != null) chips.add(new FilterBar.ActiveFilter("Player: " + exactPlayer, () -> selectPlayer(null)));
        if (type.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Type: " + type.getSelectedItem(), () -> type.setSelectedIndex(0)));
        if (period.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(period.getSelectedItem()), () -> period.setSelectedIndex(0)));
        else if (bounds.from != null || bounds.until != null) chips.add(new FilterBar.ActiveFilter(ArchiveFilters.dateLabel(bounds), () -> {
            bounds = new ArchiveQuery.Bounds(null, null, ZoneId.of(bounds.zone), bounds.mode, bounds.includeUnknown); rebuildDates(); refresh(); }));
        if (item.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Dungeon / item: " + item.getSelectedItem(), () -> item.setSelectedIndex(0)));
        if (!extraKinds.isEmpty()) chips.add(new FilterBar.ActiveFilter("More types: " + ArchiveFilters.summary(extraKinds), () -> { extraKinds.clear(); kindsInput.setText(""); refresh(); }));
        if (!extraItems.isEmpty()) chips.add(new FilterBar.ActiveFilter("More dungeons/items: " + ArchiveFilters.summary(extraItems), () -> { extraItems.clear(); itemsInput.setText(""); refresh(); }));
        FilterChips.update(filterBar, chips, this::resetFilters, false);
    }

    private KeyPopArchiveClient.Facets liveFacets() {
```

- [ ] **Step 4: Build the live Loot bar in `LootDashboard.java`**

Replace:
```java
import tomato.gui.history.ViewStateStore;
```
with:
```java
import tomato.gui.history.ViewStateStore;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private LootFacetControls facetEditor;
```
with:
```java
    private LootFacetControls facetEditor;
    private final FilterBar filterBar = new FilterBar("loot-live");
    private Runnable clearFilters = () -> {};
```
Replace:
```java
        JPanel controls = StatsUi.controls(); controls.add(search); controls.add(bagFilter); controls.add(dungeonFilter);
        JButton reset = new JButton("Reset filters"); controls.add(reset);
        add(StatsUi.stack(StatsUi.heading("Loot explorer", (historical ? "Saved drops in the selected session scope. " : "Observed drops this app session. ") + "Facets/search filter full aggregates; tab categories narrow the displayed rows."),
            StatsUi.metrics(metrics, "Matching bags", "Matching items", "Matching stat potions", "Matching white bags"), controls,facetControls), BorderLayout.NORTH);
```
with:
```java
        // One filter row (search + reset); bag/dungeon choices and the multi-select facets live in the drawer.
        JButton reset = new JButton("Reset filters");
        JPanel locations = StatsUi.controls(); locations.add(bagFilter); locations.add(dungeonFilter);
        JPanel drawer = new JPanel(new BorderLayout(0, 4)); drawer.add(locations, BorderLayout.NORTH); drawer.add(facetControls, BorderLayout.CENTER);
        filterBar.search(new WrapRow(search, reset)).drawer(drawer); clearFilters = reset::doClick;
        add(StatsUi.stack(StatsUi.heading("Loot explorer", (historical ? "Saved drops in the selected session scope. " : "Observed drops this app session. ") + "Facets/search filter full aggregates; tab categories narrow the displayed rows."),
            StatsUi.metrics(metrics, "Matching bags", "Matching items", "Matching stat potions", "Matching white bags"), filterBar), BorderLayout.NORTH);
```
Replace:
```java
        facetControls.removeAll();facetEditor=new LootFacetControls(facets,bags,dungeons,this::applyFacets);facetControls.add(facetEditor);facetControls.revalidate();
    }
```
with:
```java
        facetControls.removeAll();facetEditor=new LootFacetControls(facets,bags,dungeons,this::applyFacets);facetControls.add(facetEditor);facetControls.revalidate();
        FilterChips.update(filterBar,LootFacetChips.chips(()->tomato.history.SessionStore.JSON.fromJson(tomato.history.SessionStore.JSON.toJson(facets),LootQuery.Facets.class),this::applyFacets),clearFilters,false);
    }
```
(`rebuildFacetControls()` runs after every facet, bag or dungeon change and after `bindViewState`, so chips always follow `facets`.)

- [ ] **Step 5: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.keypop.*" --tests "tomato.gui.stats.*"`
Expected: PASS. `ExactContributorTest`, `KeyPopNotificationHandoffTest` and `KeyPopLiveStateTest` keep using `ui.playerChip`, `ui.type`, `ui.period` and `ui.tabs`; `KeyPopTest` still sees `ui.search` showing and wider than 150 px; `LootLayoutEvidenceTest` still finds a complete, reachable "Reset filters" and `loot-search`; `LootEquipmentTest`/`StatisticsExplorerTest` drive the bag/dungeon/recent-range combos by name.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/keypop/KeyPopDashboard.java src/main/java/tomato/gui/stats/LootDashboard.java \
  src/test/java/tomato/gui/keypop/KeyPopLiveFilterBarTest.java src/test/java/tomato/gui/stats/LootLiveFilterBarTest.java
git commit -m "Give live Key-pops and Loot one filter row with a drawer

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Party roster, Characters and Quests filter bars

**Files:**
- Modify: `src/main/java/tomato/gui/security/ParsePanelGUI.java`, `src/main/java/tomato/gui/character/CharacterJournalGUI.java`, `src/main/java/tomato/gui/quest/QuestGUI.java`
- Modify tests: `src/test/java/tomato/gui/security/InspectArchiveNativeTest.java`, `src/test/java/tomato/gui/quest/QuestConsistencyTest.java`
- Create tests: `src/test/java/tomato/gui/security/InspectRosterFilterBarTest.java`, `src/test/java/tomato/gui/character/CharacterFilterBarTest.java`, `src/test/java/tomato/gui/quest/QuestFilterBarTest.java`

**Interfaces:**
- Consumes: Task 1 `FilterChips`, `WrapRow`, `ArchiveNativeSupport.chipLabels/removeChip/drawer`; P1a `Motion.REDUCE_KEY`.
- Produces:
  - FilterBar `inspect-roster` (search slot: requirement "Filter" chooser, `inspect-roster-search`, "Reset display filters"; drawer: `inspect-facet-0…7` and the copy/sort options; Clear = Reset display filters). The "Options" and "Display filters (N)" toggles are removed; the Filters toggle (`inspect-roster-filters`, text `Filters · N`) replaces them. Chips: `Class: …`, `No guild (captured)`/`Guild not captured`/`Guild: …`, season and crucible labels, `Requirements: …`, `Maxed a–b`/`Unknown maxed count`.
  - FilterBar `characters` (search slot: `character-search`, "Reset filters"; drawer: life, season, `character-facet-0…8`). Chips use the choice labels plus `Account: …`, `Class: …`, `Maxed a–b`, `Newer than N h`/`At least N h`/`Unknown snapshot age`.
  - FilterBar `quests` (search slot: `quest-search`, `quest-reset`, "Sort by" `quest-sort`, `quest-name-types`; drawer: type, reward, repeatability, reward mode, expiration, required item/quantity, `quest-pinned-only`, `quest-completed`). Chips: the selected choice labels, `Needs <item> ≥ N`, `Pinned only`, `Completed shown`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/security/InspectRosterFilterBarTest.java`:
```java
package tomato.gui.security;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class InspectRosterFilterBarTest {
    @Test public void displayFacetsAndCopyOptionsLiveInTheDrawer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ParsePanelGUI roster = new ParsePanelGUI(false);
            FilterBar bar = find(roster, FilterBar.class, "inspect-roster-filter-bar"); JComponent drawer = bar.drawerContent();
            assertNull("The Filters toggle replaces Display filters (N)", find(roster, JToggleButton.class, "inspect-display-filters"));
            JComboBox<?> season = find(roster, JComboBox.class, "inspect-facet-2");
            assertTrue(SwingUtilities.isDescendingFrom(season, drawer));
            assertTrue(SwingUtilities.isDescendingFrom(button(roster, "Sort by guild"), drawer));
            JTextField search = find(roster, JTextField.class, "inspect-roster-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, drawer));
            assertFalse(SwingUtilities.isDescendingFrom(button(roster, "Reset display filters"), drawer));
            season.setSelectedIndex(1); find(roster, JComboBox.class, "inspect-facet-4").setSelectedIndex(3);
            assertEquals(Arrays.asList("Seasonal", "Requirements: Below requirements"), ArchiveNativeSupport.chipLabels(bar));
            assertEquals("Filters · 2", find(roster, AbstractButton.class, "inspect-roster-filters").getText());
            ArchiveNativeSupport.removeChip(bar, "Seasonal"); assertEquals(0, season.getSelectedIndex());
            button(roster, "Reset display filters").doClick(); assertEquals(0, bar.activeCount());
        });
    }

    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton) child).getText())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = button((Container) child, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/character/CharacterFilterBarTest.java`:
```java
package tomato.gui.character;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class CharacterFilterBarTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void rosterFiltersLiveInTheDrawerWithChips() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
            FilterBar bar = find(panel, FilterBar.class, "characters-filter-bar"); JComponent drawer = bar.drawerContent();
            JComboBox<?> need = find(panel, JComboBox.class, "character-facet-2");
            assertTrue(SwingUtilities.isDescendingFrom(need, drawer));
            JTextField search = find(panel, JTextField.class, "character-search");
            assertTrue(SwingUtilities.isDescendingFrom(search, bar)); assertFalse(SwingUtilities.isDescendingFrom(search, drawer));
            need.setSelectedIndex(1); find(panel, JComboBox.class, "character-facet-7").setSelectedIndex(3);
            assertEquals(Arrays.asList("Needs Life", "Unknown snapshot age"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Needs Life"); assertEquals(0, need.getSelectedIndex());
            find(panel, AbstractButton.class, "characters-clear-filters").doClick(); assertEquals(0, bar.activeCount());
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/quest/QuestFilterBarTest.java`:
```java
package tomato.gui.quest;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
import static org.junit.Assert.*;

public class QuestFilterBarTest {
    @Test public void questFiltersLiveInTheDrawerWhileSortAndNameTypesStayVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences());
            FilterBar bar = find(ui, FilterBar.class, "quests-filter-bar"); JComponent drawer = bar.drawerContent();
            for (String name : new String[]{"quest-type", "quest-reward", "quest-repeat-mode", "quest-requirement-item", "quest-pinned-only", "quest-completed"})
                assertTrue(name, SwingUtilities.isDescendingFrom(find(ui, JComponent.class, name), drawer));
            for (String name : new String[]{"quest-search", "quest-reset", "quest-sort", "quest-name-types"}) {
                JComponent control = find(ui, JComponent.class, name);
                assertTrue(name, SwingUtilities.isDescendingFrom(control, bar)); assertFalse(name, SwingUtilities.isDescendingFrom(control, drawer));
            }
            find(ui, JComboBox.class, "quest-repeat-mode").setSelectedIndex(1); find(ui, AbstractButton.class, "quest-pinned-only").doClick();
            assertEquals(Arrays.asList("Repeatable", "Pinned only"), ArchiveNativeSupport.chipLabels(bar));
            ArchiveNativeSupport.removeChip(bar, "Pinned only"); assertFalse(find(ui, AbstractButton.class, "quest-pinned-only").isSelected());
            find(ui, AbstractButton.class, "quest-reset").doClick(); assertEquals(0, bar.activeCount());
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.security.InspectRosterFilterBarTest" --tests "tomato.gui.character.CharacterFilterBarTest" --tests "tomato.gui.quest.QuestFilterBarTest"`
Expected: FAIL — `java.lang.NullPointerException` at `bar.drawerContent()` (no FilterBar with those names yet).

- [ ] **Step 3: Build the roster bar in `ParsePanelGUI.java`**

Replace:
```java
import tomato.gui.history.ViewStateStore;
```
with:
```java
import tomato.gui.history.ViewStateStore;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final JToggleButton displayFilters = new JToggleButton("Display filters (0)");
```
with:
```java
    private final FilterBar filterBar = new FilterBar("inspect-roster");
    private Runnable clearFilters = () -> {};
```
Replace:
```java
        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
```
with:
```java
        JPanel filterRow = new JPanel(new BorderLayout(8, 0)); filterRow.setOpaque(false);
```
Replace:
```java
        JPanel options = ContentStyle.controls();
        options.setVisible(false);
        JToggleButton optionsButton = new JToggleButton("Options");
        optionsButton.getAccessibleContext().setAccessibleDescription("Show copy and sorting options");
        optionsButton.addActionListener(e -> {
            options.setVisible(optionsButton.isSelected());
            optionsButton.getAccessibleContext().setAccessibleDescription(optionsButton.isSelected()
                    ? "Copy and sorting options expanded" : "Copy and sorting options collapsed");
            page.revalidate();
        });
        filterRow.add(optionsButton, BorderLayout.EAST);
```
with:
```java
        JPanel options = ContentStyle.controls();
```
Replace:
```java
        top.add(filterRow, BorderLayout.NORTH);
        top.add(options, BorderLayout.CENTER);
        JPanel facets = ContentStyle.controls();
        JPanel searchActions = ContentStyle.controls();
        rosterSearch.setName("inspect-roster-search"); rosterSearch.getAccessibleContext().setAccessibleName("Search displayed roster");
        searchActions.add(rosterSearch);
        displayFilters.setName("inspect-display-filters");
        displayFilters.setToolTipText("Expand display filters; the count shows active advanced filters even while collapsed.");
        searchActions.add(displayFilters);
        facets.setVisible(false);
        displayFilters.addActionListener(e -> { facets.setVisible(displayFilters.isSelected()); updateDisplayFilterSummary(); page.revalidate(); });
```
with:
```java
        JPanel facets = ContentStyle.controls();
        rosterSearch.setName("inspect-roster-search"); rosterSearch.getAccessibleContext().setAccessibleName("Search displayed roster");
```
Replace:
```java
        JButton reset = new JButton("Reset display filters"); searchActions.add(reset);
```
with:
```java
        JButton reset = new JButton("Reset display filters");
```
Replace:
```java
        JPanel displayControls = new JPanel(new BorderLayout(0, 2));
        displayControls.add(searchActions, BorderLayout.NORTH); displayControls.add(facets);
        top.add(displayControls, BorderLayout.SOUTH);
```
with:
```java
        // One filter row (requirement rules, roster search, reset); display facets and copy/sort options live in the drawer.
        JPanel drawer = new JPanel(new BorderLayout(0, 4)); drawer.setOpaque(false); drawer.add(facets, BorderLayout.NORTH); drawer.add(options, BorderLayout.SOUTH);
        filterBar.search(new WrapRow(filterRow, rosterSearch, reset)).drawer(drawer); clearFilters = reset::doClick;
        top.add(filterBar, BorderLayout.NORTH);
```
Replace:
```java
        for (JComponent control : new JComponent[]{rosterSearch, displayFilters, classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet, minMaxed, maxMaxed, reset, explain}) {
```
with:
```java
        for (JComponent control : new JComponent[]{rosterSearch, classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet, minMaxed, maxMaxed, reset, explain}) {
```
Replace:
```java
    private void requestRefresh() {
        synchronized (rosterLock) { revision++; }
        rememberViewState();
    }
```
with:
```java
    private void requestRefresh() {
        synchronized (rosterLock) { revision++; }
        rememberViewState(); updateChips();
    }
```
Replace:
```java
        rebuildDisplayChoices(players);
        updateDisplayFilterSummary();
```
with:
```java
        rebuildDisplayChoices(players);
        updateChips();
```
Replace the whole method:
```java
    private void updateDisplayFilterSummary() {
        int activeFilters = 0;
        for (JComboBox<?> facet : new JComboBox<?>[]{classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet})
            if (facet.getSelectedIndex() > 0) activeFilters++;
        displayFilters.setText("Display filters (" + activeFilters + ")");
        displayFilters.getAccessibleContext().setAccessibleDescription(activeFilters + " active advanced display filters; "
                + (displayFilters.isSelected() ? "expanded" : "collapsed") + ". Search and Reset remain available.");
    }
```
with:
```java
    /** Active display facets as removable chips; bulk copy and export keep using the full roster. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        if (classFacet.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Class: " + classFacet.getSelectedItem(), () -> classFacet.setSelectedIndex(0)));
        int guild = guildFacet.getSelectedIndex();
        if (guild > 0) chips.add(new FilterBar.ActiveFilter((guild > 2 ? "Guild: " : "") + guildFacet.getSelectedItem(), () -> guildFacet.setSelectedIndex(0)));
        for (JComboBox<String> facet : Arrays.asList(seasonalFacet, crucibleFacet))
            if (facet.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(facet.getSelectedItem()), () -> facet.setSelectedIndex(0)));
        if (verdictFacet.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Requirements: " + verdictFacet.getSelectedItem(), () -> verdictFacet.setSelectedIndex(0)));
        if (maxedFacet.getSelectedIndex() == 1) chips.add(new FilterBar.ActiveFilter("Maxed " + minMaxed.getValue() + "–" + maxMaxed.getValue(), () -> maxedFacet.setSelectedIndex(0)));
        else if (maxedFacet.getSelectedIndex() == 2) chips.add(new FilterBar.ActiveFilter("Unknown maxed count", () -> maxedFacet.setSelectedIndex(0)));
        FilterChips.update(filterBar, chips, clearFilters, false);
    }
```

- [ ] **Step 4: Build the roster bar in `CharacterJournalGUI.java`**

Replace:
```java
import tomato.gui.roster.RosterViewState;
```
with:
```java
import tomato.gui.roster.RosterViewState;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private String pendingSelectionKey;
    private boolean restoringState;
```
with:
```java
    private String pendingSelectionKey;
    private boolean restoringState;
    private final FilterBar filterBar = new FilterBar("characters");
    private Runnable clearFilters = () -> {};
```
Replace:
```java
        filters.add(search); filters.add(life); filters.add(season);
```
with:
```java
        filters.add(life); filters.add(season);
```
Replace:
```java
        JButton reset = new JButton("Reset filters"); filters.add(reset); top.add(filters);
```
with:
```java
        // One filter row (search + reset); life, season and every roster facet live in the drawer.
        JButton reset = new JButton("Reset filters");
        filterBar.search(new WrapRow(search, reset)).drawer(filters); clearFilters = reset::doClick; top.add(filterBar);
```
Replace:
```java
    private void filter() {
        if (refreshing) return;
```
with:
```java
    /** Active roster facets as removable chips. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        for (JComboBox<?> box : new JComboBox<?>[]{life, season, needsLife, missing})
            if (box.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(box.getSelectedItem()), () -> box.setSelectedIndex(0)));
        if (accountFilter.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Account: " + accountFilter.getSelectedItem(), () -> accountFilter.setSelectedIndex(0)));
        if (classFilter.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Class: " + classFilter.getSelectedItem(), () -> classFilter.setSelectedIndex(0)));
        if (maxedFilter.getSelectedIndex() == 1) chips.add(new FilterBar.ActiveFilter("Maxed " + minMaxed.getValue() + "–" + maxMaxed.getValue(), () -> maxedFilter.setSelectedIndex(0)));
        else if (maxedFilter.getSelectedIndex() == 2) chips.add(new FilterBar.ActiveFilter("Unknown maxed count", () -> maxedFilter.setSelectedIndex(0)));
        int age = ageFilter.getSelectedIndex();
        if (age == 1 || age == 2) chips.add(new FilterBar.ActiveFilter((age == 1 ? "Newer than " : "At least ") + ageHours.getValue() + " h", () -> ageFilter.setSelectedIndex(0)));
        else if (age == 3) chips.add(new FilterBar.ActiveFilter("Unknown snapshot age", () -> ageFilter.setSelectedIndex(0)));
        FilterChips.update(filterBar, chips, clearFilters, false);
    }
    private void filter() {
        if (refreshing) return;
        updateChips();
```

- [ ] **Step 5: Build the Quests bar in `QuestGUI.java`**

Replace:
```java
import tomato.gui.modern.ContentStyle;
```
with:
```java
import tomato.gui.modern.ContentStyle;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private boolean columnSizingPending;
```
with:
```java
    private boolean columnSizingPending;
    private final FilterBar filterBar = new FilterBar("quests");
    private Runnable clearFilters = () -> {};
```
Replace:
```java
        search.getAccessibleContext().setAccessibleName("Search quests");
        header.add(search, BorderLayout.CENTER);
```
with:
```java
        search.getAccessibleContext().setAccessibleName("Search quests"); search.setColumns(22);
```
Replace:
```java
        selects.add(field("Quest type", type)); selects.add(field("Reward", reward)); selects.add(field("Sort by", sort));
```
with:
```java
        selects.add(field("Quest type", type)); selects.add(field("Reward", reward));
```
Replace:
```java
        options.add(onlyPinned); options.add(completed); options.add(labels); options.add(reset);
        filters.add(options, BorderLayout.CENTER); header.add(filters, BorderLayout.SOUTH);
```
with:
```java
        options.add(onlyPinned); options.add(completed);
        filters.add(options, BorderLayout.CENTER);
        // One filter row: search, reset, sort and type labels stay visible; every narrowing filter lives in the drawer.
        filterBar.search(new WrapRow(search, reset, field("Sort by", sort), labels)).drawer(filters); clearFilters = reset::doClick;
        header.add(filterBar, BorderLayout.CENTER);
```
Replace:
```java
    private void refresh() {
        if (refreshing) return;
```
with:
```java
    /** Active quest filters as removable chips; sort order is not a filter. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        for (JComboBox<String> box : Arrays.asList(type, reward, repeatMode, rewardMode, expirationMode))
            if (box.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(box.getSelectedItem()), () -> box.setSelectedIndex(0)));
        String item = requirementItem.getText().trim(); int quantity = (Integer) requirementCount.getValue();
        if (!item.isEmpty() || quantity > 0) chips.add(new FilterBar.ActiveFilter("Needs " + (item.isEmpty() ? "any item" : item) + (quantity > 0 ? " ≥ " + quantity : ""),
            () -> { requirementItem.setText(""); requirementCount.setValue(0); }));
        if (onlyPinned.isSelected()) chips.add(new FilterBar.ActiveFilter("Pinned only", onlyPinned::doClick));
        if (completed.isSelected()) chips.add(new FilterBar.ActiveFilter("Completed shown", completed::doClick));
        FilterChips.update(filterBar, chips, clearFilters, false);
    }
    private void refresh() {
        if (refreshing) return;
        updateChips();
```

- [ ] **Step 6: Update the tests that need the moved controls visible**

`src/test/java/tomato/gui/security/InspectArchiveNativeTest.java` — add imports:
```java
import java.util.Collections;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.Motion;
import util.PropertiesManager;
```
and replace:
```java
                JToggleButton filters = edt(() -> named(cached,"inspect-display-filters",JToggleButton.class));
                key(filters, java.awt.event.KeyEvent.VK_SPACE);
                await(filters::isSelected);
                edt(() -> {
                    JComboBox<?> season = named(cached,"inspect-facet-2",JComboBox.class);
                    reachable(season); season.setSelectedIndex(1); return null;
                });
                await(() -> "Display filters (1)".equals(filters.getText()));
                edt(() -> { filters.doClick(); assertFalse(filters.isSelected()); assertEquals("Display filters (1)",filters.getText());
                    assertTrue(filters.getAccessibleContext().getAccessibleDescription().contains("1 active"));
                    button(cached,"Reset display filters").doClick(); return null; });
```
with:
```java
                FilterBar rosterFilters = edt(() -> named(cached,"inspect-roster-filter-bar",FilterBar.class));
                JButton filters = edt(() -> named(cached,"inspect-roster-filters",JButton.class));
                String motion = PropertiesManager.getProperty(Motion.REDUCE_KEY); PropertiesManager.setProperties(Motion.REDUCE_KEY, "true");
                try {
                    key(filters, java.awt.event.KeyEvent.VK_SPACE);
                    await(() -> rosterFilters.drawerOpen());
                    edt(() -> {
                        JComboBox<?> season = named(cached,"inspect-facet-2",JComboBox.class);
                        reachable(season); season.setSelectedIndex(1); return null;
                    });
                    await(() -> "Filters · 1".equals(filters.getText()));
                    edt(() -> { filters.doClick(); assertFalse(rosterFilters.drawerOpen()); assertEquals("Filters · 1",filters.getText());
                        assertEquals(Collections.singletonList("Seasonal"),chipLabels(rosterFilters));
                        button(cached,"Reset display filters").doClick(); return null; });
                } finally { PropertiesManager.setProperties(Motion.REDUCE_KEY, motion == null ? "" : motion); }
```
(`InspectArchiveClientTest` lines 89–107 keep working: `inspect-roster-search`, `inspect-facet-0` and "Reset display filters" keep their names and enabled state.)

`src/test/java/tomato/gui/quest/QuestConsistencyTest.java` — add imports:
```java
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
        }, preferences);
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
```
with:
```java
        }, preferences);
        // The shell matrix measures every filter control, so it runs with the Quests filter drawer open.
        ArchiveNativeSupport.drawer(named(quest, "quests-filter-bar", FilterBar.class), true);
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
```
Replace:
```java
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (frame != null) {
```
with:
```java
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (quest != null) ArchiveNativeSupport.drawer(named(quest, "quests-filter-bar", FilterBar.class), false);
            if (frame != null) {
```
Replace:
```java
                SwingUtilities.invokeAndWait(() -> {
                    JComboBox<?> type = named(quest, "quest-type", JComboBox.class);
                    assertSame("Posted Tab must traverse into the first filter", type, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
                    fullyVisible(type);
                });
```
with:
```java
                SwingUtilities.invokeAndWait(() -> {
                    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    assertNotNull("Posted Tab must keep a focus owner", owner); assertNotSame("Posted Tab must leave the search field", search(), owner);
                    assertTrue("Posted Tab must reach the next filter-row control, not a static label: " + owner,
                        SwingUtilities.isDescendingFrom(owner, named(quest, "quests-filter-bar", FilterBar.class)) && !staticLabel(owner));
                });
                awaitFocus(named(quest, "quest-type", JComboBox.class));
                SwingUtilities.invokeAndWait(() -> fullyVisible(named(quest, "quest-type", JComboBox.class)));
```
(`QuestGuiTest` keeps clicking "Reset filters", "Pinned only" and "Show completed" by text; `CharacterJournalLayoutTest.assertControlsReachable` skips invisible drawer content and still reaches `character-search`; `CharacterRosterStateTest`/`CharacterViewStateTest` drive `character-facet-N` without needing them visible; `InspectViewStateTest`/`InspectFacetStateTest` drive `inspect-facet-N` likewise.)

- [ ] **Step 7: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.security.*" --tests "tomato.gui.character.*" --tests "tomato.gui.quest.*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/tomato/gui/security/ParsePanelGUI.java src/main/java/tomato/gui/character/CharacterJournalGUI.java src/main/java/tomato/gui/quest/QuestGUI.java \
  src/test/java/tomato/gui/security/InspectRosterFilterBarTest.java src/test/java/tomato/gui/character/CharacterFilterBarTest.java src/test/java/tomato/gui/quest/QuestFilterBarTest.java \
  src/test/java/tomato/gui/security/InspectArchiveNativeTest.java src/test/java/tomato/gui/quest/QuestConsistencyTest.java
git commit -m "Give the party roster, Characters and Quests one filter row

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Customizable tabs — Characters, Inspect, Quests, Bridge review, DPS and Resources

Every converted group keeps a plain `JTabbedPane` for existing code and tests (`tabs = custom.component()`), keeps its titles, default order and component name, and replaces every index-based lookup with an ID lookup so reordering/hiding cannot select the wrong view. Change listeners ignore `isRebuilding()` transients; where a listener has side effects (Inspect), it runs once after the rebuild settles.

| Group ID (pane name) | Tab IDs in default order | Analyst-only |
|---|---|---|
| `characters` (`characters-tabs`) | `roster`, `exalts`, `pets` | — |
| `character-detail` (`character-detail-tabs`) | `stats`, `equipment`, `exalts`, `notes`, `evidence`, `goals`, `death` | `evidence` "Snapshot evidence" (spec §3.2, §6.2) |
| `inspect` (`inspect-tabs`) | `area`, `runs`, `ability` | `ability` "Ability Use" (spec §3.2, §4.2 "Party › Ability use (Analyst tab)") |
| `quests` (`quests-tabs`) | `captured`, `plans` | — |
| `bridge` (`bridge-tabs`) | `review`, `settings`, `logs`, `saved-review` | — |
| `dps` (`dps-tabs`) | `meters`, `resources` | — |
| `activity-combat` (renamed back to `activity-resource-tabs`) | `timeline`, `uptime`, `window` | — |

**Files:**
- Modify: `src/main/java/tomato/gui/character/CharacterPanelGUI.java`, `character/CharacterJournalGUI.java`, `security/SecurityGUI.java`, `quest/QuestGUI.java`, `bridge/BridgeReviewGUI.java`, `dps/DpsGUI.java`, `activity/ActivityPanel.java`
- Modify test: `src/test/java/tomato/gui/security/InspectRosterOwnershipTest.java`
- Create tests: `src/test/java/tomato/gui/character/CharacterTabsTest.java`, `src/test/java/tomato/gui/security/InspectTabsTest.java`, `src/test/java/tomato/gui/quest/QuestTabsTest.java`, `src/test/java/tomato/gui/activity/ActivityCombatTabsTest.java`

**Interfaces:**
- Consumes (P1a): `public CustomizableTabs(String group)`; `JTabbedPane component()` (named `<group>-tabs`); `CustomizableTabs add(String id, String title, Component)`, `addAnalyst(...)`; `String selectedId()`, `void select(String id)` (no-op for hidden/Analyst-only-in-Simple tabs), `void show(String id)` (no-op unless hidden), `boolean isRebuilding()`; preference `ui.tabs.<group>` = `id,id,…|hiddenId,…`; `DisplayModeModel.application()`, `Mode`, `set(Mode)`, `mode()`.
- Produces: the groups above. Persisted per-module tab state keeps its format: `characters-live-roster` "tab" stays an integer, now the index in `DETAIL_TABS` (the default order); `inspect-live-container` "tab" stays `area|runs|ability`; the live Resources "tab" stays `primary|uptime`; DPS Back state (in memory only) stores the tab ID.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/character/CharacterTabsTest.java`:
```java
package tomato.gui.character;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.kit.DisplayModeModel;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class CharacterTabsTest {
    private static final String ORDER = "ui.tabs.character-detail";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;
    @Before public void remember() throws Exception { savedOrder = PropertiesManager.getProperty(ORDER); SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode()); }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    @Test public void snapshotEvidenceIsAnalystOnlyAndTabsKeepTheirSavedOrder() throws Exception {
        PropertiesManager.setProperties(ORDER, "goals,stats,equipment,exalts,notes,evidence,death|");
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty);
            JTabbedPane tabs = find(panel, JTabbedPane.class, "character-detail-tabs");
            assertEquals("Goals", tabs.getTitleAt(0)); assertEquals(6, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertEquals(7, tabs.getTabCount()); assertEquals(5, tabs.indexOfTab("Snapshot evidence"));
            tabs.setSelectedIndex(tabs.indexOfTab("Notes")); panel.openGoals();
            assertEquals("Goals", tabs.getTitleAt(tabs.getSelectedIndex()));
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/security/InspectTabsTest.java`:
```java
package tomato.gui.security;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.DisplayModeModel;
import static org.junit.Assert.*;

public class InspectTabsTest {
    private DisplayModeModel.Mode savedMode;
    @Before public void remember() throws Exception { SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode()); }
    @After public void restore() throws Exception { SwingUtilities.invokeAndWait(() -> { DisplayModeModel.application().set(savedMode); ParsePanelGUI.clear(); }); }

    @Test public void abilityUseIsAnalystOnly() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                SecurityGUI panel = new SecurityGUI(log); JTabbedPane tabs = find(panel, JTabbedPane.class, "inspect-tabs");
                assertEquals(Arrays.asList("Current Area", "Runs"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertEquals(Arrays.asList("Current Area", "Runs", "Ability Use"), titles(tabs));
                tabs.setSelectedIndex(2); DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals("Hiding the selected Analyst tab falls back to the first tab", "Current Area", tabs.getTitleAt(tabs.getSelectedIndex()));
            });
            SwingUtilities.invokeAndWait(() -> { }); // lets the post-rebuild tab sync run before the log closes
        }
    }

    private static List<String> titles(JTabbedPane tabs) { List<String> titles = new ArrayList<>(); for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i)); return titles; }
    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/quest/QuestTabsTest.java`:
```java
package tomato.gui.quest;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import org.junit.*;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class QuestTabsTest {
    private static final String ORDER = "ui.tabs.quests";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void savedPlansOpenByIdEvenWhenMovedAndHidden() throws Exception {
        PropertiesManager.setProperties(ORDER, "plans,captured|plans");
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences());
            JTabbedPane tabs = find(ui, JTabbedPane.class, "quests-tabs");
            assertEquals(1, tabs.getTabCount()); assertEquals("Captured quests", tabs.getTitleAt(0));
            ui.openPlans();
            assertEquals(Arrays.asList("Saved plans", "Captured quests"), Arrays.asList(tabs.getTitleAt(0), tabs.getTitleAt(1)));
            assertEquals("Saved plans", tabs.getTitleAt(tabs.getSelectedIndex()));
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/activity/ActivityCombatTabsTest.java`:
```java
package tomato.gui.activity;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class ActivityCombatTabsTest {
    private static final String ORDER = "ui.tabs.activity-combat";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void resourceViewsFollowTheSavedOrderUnderTheirExistingName() throws Exception {
        PropertiesManager.setProperties(ORDER, "window,timeline,uptime|");
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                ActivityPanel combat = new ActivityPanel(log, ActivityPanel.Mode.COMBAT);
                JTabbedPane tabs = find(combat, JTabbedPane.class, "activity-resource-tabs");
                List<String> titles = new ArrayList<>(); for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
                assertEquals(Arrays.asList("Selected window", "Buff timeline & resources", "Uptime summary"), titles);
                assertNull("Runs has no resource views", find(new ActivityPanel(log, ActivityPanel.Mode.RUNS), JTabbedPane.class, "activity-resource-tabs"));
            });
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.character.CharacterTabsTest" --tests "tomato.gui.security.InspectTabsTest" --tests "tomato.gui.quest.QuestTabsTest" --tests "tomato.gui.activity.ActivityCombatTabsTest"`
Expected: FAIL — `expected:<Goals> but was:<Stat maxing>`, `expected:<[Current Area, Runs]> but was:<[Current Area, Runs, Ability Use]>`, `NullPointerException` (no `quests-tabs`), `expected:<[Selected window, …]> but was:<[Buff timeline & resources, …]>`.

- [ ] **Step 3: Convert `CharacterPanelGUI.java`**

Replace the whole file with:
```java
package tomato.gui.character;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.CustomizableTabs;

/**
 * Character GUI class to display character data in the character tab.
 */
public class CharacterPanelGUI extends JPanel {
    private final CharacterJournalGUI journal;
    private final CustomizableTabs tabs = new CustomizableTabs("characters");

    public CharacterPanelGUI(TomatoData data) {
        setLayout(new BorderLayout());

        journal = new CharacterJournalGUI(data.characterJournal());
        tabs.add("roster", "Roster", journal).add("exalts", "Exalts", journal.exaltPanel()).add("pets", "Pets", new CharacterPetsGUI(data));
        tabs.component().addChangeListener(e -> journal.refresh());
        add(tabs.component(), BorderLayout.CENTER);
    }
    public void bindNavigator(tomato.gui.route.Navigator navigator) { journal.bindNavigator(navigator); }
    public void openGoals() { tabs.show("roster"); tabs.select("roster"); journal.openGoals(); }
}
```

- [ ] **Step 4: Convert the character detail tabs in `CharacterJournalGUI.java`**

Replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final JTabbedPane tabs = new JTabbedPane();
```
with:
```java
    private final CustomizableTabs detailTabs = new CustomizableTabs("character-detail");
    private final JTabbedPane tabs = detailTabs.component();
    /** Default tab order. The saved "tab" value is an index into it, so saved views stay valid when tabs move or hide. */
    private static final String[] DETAIL_TABS = {"stats", "equipment", "exalts", "notes", "evidence", "goals", "death"};
```
Replace each `addTab` call:
```java
        tabs.addTab("Stat maxing", ContentStyle.tableScroll(stats, 3));
        tabs.addTab("Equipment & inventory", equipmentPanel);
        tabs.addTab("Class exalts", ContentStyle.tableScroll(table(charExaltModel), 3));
```
with:
```java
        detailTabs.add("stats", "Stat maxing", ContentStyle.tableScroll(stats, 3));
        detailTabs.add("equipment", "Equipment & inventory", equipmentPanel);
        detailTabs.add("exalts", "Class exalts", ContentStyle.tableScroll(table(charExaltModel), 3));
```
and:
```java
        notePanel.add(noteScroll, BorderLayout.CENTER); notePanel.add(saveNotes, BorderLayout.SOUTH); tabs.addTab("Notes", notePanel);
        tabs.addTab("Snapshot evidence", ContentStyle.tableScroll(table(metadataModel), 3));
        tabs.addTab("Goals", planningPanel);
        tabs.addTab("Death annotation", deathPanel);
```
with:
```java
        notePanel.add(noteScroll, BorderLayout.CENTER); notePanel.add(saveNotes, BorderLayout.SOUTH); detailTabs.add("notes", "Notes", notePanel);
        // Raw field provenance is diagnostic: Analyst mode only (spec §3.2); the saved order still includes it.
        detailTabs.addAnalyst("evidence", "Snapshot evidence", ContentStyle.tableScroll(table(metadataModel), 3));
        detailTabs.add("goals", "Goals", planningPanel);
        detailTabs.add("death", "Death annotation", deathPanel);
```
Replace:
```java
    public void openGoals() { tabs.setSelectedComponent(planningPanel); tabs.requestFocusInWindow(); }
```
with:
```java
    public void openGoals() { detailTabs.show("goals"); detailTabs.select("goals"); tabs.requestFocusInWindow(); }
```
Replace:
```java
        tabs.addChangeListener(e -> rememberViewState());
```
with:
```java
        tabs.addChangeListener(e -> { if (!detailTabs.isRebuilding()) rememberViewState(); });
```
Replace:
```java
        values.put("tab", Integer.toString(tabs.getSelectedIndex())); values.put("pageY", Integer.toString(pageScroll.getViewport().getViewPosition().y));
```
with:
```java
        values.put("tab", Integer.toString(detailTabIndex())); values.put("pageY", Integer.toString(pageScroll.getViewport().getViewPosition().y));
```
Replace:
```java
        int hours = RosterViewState.number(values, "hours", (Integer)ageHours.getValue(), 0, 1000000), tab = RosterViewState.number(values, "tab", tabs.getSelectedIndex(), 0, tabs.getTabCount() - 1);
```
with:
```java
        int hours = RosterViewState.number(values, "hours", (Integer)ageHours.getValue(), 0, 1000000), tab = RosterViewState.number(values, "tab", detailTabIndex(), 0, DETAIL_TABS.length - 1);
```
Replace:
```java
                minMaxed.setValue(minimum); maxMaxed.setValue(maximum); ageHours.setValue(hours); tabs.setSelectedIndex(tab);
```
with:
```java
                minMaxed.setValue(minimum); maxMaxed.setValue(maximum); ageHours.setValue(hours); detailTabs.select(DETAIL_TABS[tab]);
```
Replace:
```java
    private static <T> void selectChoice(JComboBox<Choice<T>> box, T value, String label) {
```
with:
```java
    /** The selected detail tab as an index in the default order (0 while a rebuild has nothing selected). */
    private int detailTabIndex() { return Math.max(0, Arrays.asList(DETAIL_TABS).indexOf(detailTabs.selectedId())); }
    private static <T> void selectChoice(JComboBox<Choice<T>> box, T value, String label) {
```

- [ ] **Step 5: Convert the Inspect tabs in `SecurityGUI.java`**

Replace:
```java
import tomato.gui.roster.RosterViewState;
```
with:
```java
import tomato.gui.roster.RosterViewState;
import tomato.gui.kit.CustomizableTabs;
```
Replace:
```java
    private final JTabbedPane tabbedPane=new JTabbedPane();
```
with:
```java
    private final CustomizableTabs tabs=new CustomizableTabs("inspect");
    private final JTabbedPane tabbedPane=tabs.component();
    private static final String[] TAB_IDS={"area","runs","ability"};
    private boolean tabSyncPending;
```
Replace:
```java
        tabbedPane.addTab("Current Area", currentArea);
        tabbedPane.addTab("Runs", runs);
        tabbedPane.addTab("Ability Use", abilityUse);
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedComponent() != runs) runs.releaseRoster();
            if (tabbedPane.getSelectedComponent() == currentArea) {
                parsePanel.showCurrentArea();
                currentArea.add(parsePanel);
                currentArea.revalidate();
            } else if (tabbedPane.getSelectedComponent() == runs) runs.showRoster();
            if(liveState!=null)liveState.changed();
        });
```
with:
```java
        // Ability Use is diagnostic evidence: Analyst mode only (spec §3.2, §4.2).
        tabs.add("area", "Current Area", currentArea).add("runs", "Runs", runs).addAnalyst("ability", "Ability Use", abilityUse);
        Runnable tabChanged = () -> {
            if (tabbedPane.getSelectedComponent() != runs) runs.releaseRoster();
            if (tabbedPane.getSelectedComponent() == currentArea) {
                parsePanel.showCurrentArea();
                currentArea.add(parsePanel);
                currentArea.revalidate();
            } else if (tabbedPane.getSelectedComponent() == runs) runs.showRoster();
            if(liveState!=null)liveState.changed();
        };
        tabbedPane.addChangeListener(e -> {
            // A rebuild (mode switch, reorder, hide) passes through transient selections; act once on the final one.
            if (!tabs.isRebuilding()) { tabChanged.run(); return; }
            if (tabSyncPending) return;
            tabSyncPending = true; SwingUtilities.invokeLater(() -> { tabSyncPending = false; tabChanged.run(); });
        });
```
Replace:
```java
            java.util.Map<String,String> values=new java.util.LinkedHashMap<>();values.put("tab",new String[]{"area","runs","ability"}[tabbedPane.getSelectedIndex()]);return values;
        },values->{int tab=RosterViewState.option(values,"tab",0,"area","runs","ability");return ()->tabbedPane.setSelectedIndex(tab);});
```
with:
```java
            java.util.Map<String,String> values=new java.util.LinkedHashMap<>();values.put("tab",tabs.selectedId()==null?"area":tabs.selectedId());return values;
        },values->{int tab=RosterViewState.option(values,"tab",0,"area","runs","ability");return ()->tabs.select(TAB_IDS[tab]);});
```

- [ ] **Step 6: Convert `QuestGUI.java`, `BridgeReviewGUI.java` and `DpsGUI.java`**

`QuestGUI.java` — replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final JTabbedPane tabs = new JTabbedPane();
```
with:
```java
    private final CustomizableTabs views = new CustomizableTabs("quests");
    private final JTabbedPane tabs = views.component();
```
Replace:
```java
    public void openPlans() { tabs.setSelectedComponent(plans); tabs.requestFocusInWindow(); }
```
with:
```java
    public void openPlans() { views.show("plans"); views.select("plans"); tabs.requestFocusInWindow(); }
```
Replace:
```java
        plan.addActionListener(e -> { Quest q = selected(); tabs.setSelectedIndex(1); if (q != null) plans.importQuest(q, globalPinned.contains(key(q)) ? key(q) : null); });
```
with:
```java
        plan.addActionListener(e -> { Quest q = selected(); views.show("plans"); views.select("plans"); if (q != null) plans.importQuest(q, globalPinned.contains(key(q)) ? key(q) : null); });
```
Replace:
```java
        tabs.addTab("Captured quests", page); tabs.addTab("Saved plans", plans); add(tabs, BorderLayout.CENTER);
```
with:
```java
        views.add("captured", "Captured quests", page).add("plans", "Saved plans", plans); add(tabs, BorderLayout.CENTER);
```

`BridgeReviewGUI.java` — replace:
```java
import tomato.gui.modern.ContentStyle;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.modern.ContentStyle;
```
Replace:
```java
    private final JTabbedPane tabs=new JTabbedPane();
```
with:
```java
    private final CustomizableTabs views=new CustomizableTabs("bridge");private final JTabbedPane tabs=views.component();
```
Replace:
```java
        tabs.addTab("Review",reviewPage);tabs.addTab("Settings",settings());
```
with:
```java
        views.add("review","Review",reviewPage).add("settings","Settings",settings());
```
Replace:
```java
        tabs.addTab("Logs",logPage);tabs.addTab("Saved review",savedReview());tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
```
with:
```java
        views.add("logs","Logs",logPage).add("saved-review","Saved review",savedReview());tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
```
Replace:
```java
tabs.setSelectedIndex(0);review.setRowSelectionInterval(view,view);
```
with:
```java
views.show("review");views.select("review");review.setRowSelectionInterval(view,view);
```

`DpsGUI.java` — replace:
```java
import tomato.gui.modern.ContentStyle;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.modern.ContentStyle;
```
Replace:
```java
    private final JTabbedPane combatTabs = new JTabbedPane();
```
with:
```java
    private final CustomizableTabs combatViews = new CustomizableTabs("dps");
    private final JTabbedPane combatTabs = combatViews.component();
```
Replace:
```java
        combatTabs.setName("dps-tabs");
        combatTabs.addTab("Damage meters", damageScroll);
        combatTabs.addTab("Resources & buffs", resourcesWorkspace);
```
with:
```java
        combatViews.add("meters", "Damage meters", damageScroll).add("resources", "Resources & buffs", resourcesWorkspace);
```
Replace:
```java
        combatTabs.addChangeListener(e -> refreshLiveView());
```
with:
```java
        combatTabs.addChangeListener(e -> { if (!combatViews.isRebuilding()) refreshLiveView(); });
```
Replace:
```java
        combatTabs.setSelectedComponent(resourcesWorkspace); return true;
```
with:
```java
        showCombat("resources"); return true;
```
Replace:
```java
        final int tab; final boolean live; final String entry; final Object resources;
        RouteState(int tab, boolean live, String entry, Object resources) { this.tab = tab; this.live = live; this.entry = entry; this.resources = resources; }
```
with:
```java
        final String tab; final boolean live; final String entry; final Object resources;
        RouteState(String tab, boolean live, String entry, Object resources) { this.tab = tab; this.live = live; this.entry = entry; this.resources = resources; }
```
Replace:
```java
        return new RouteState(combatTabs.getSelectedIndex(), liveUpdates, selectedEncounter == null ? null : selectedEncounter.id,
```
with:
```java
        return new RouteState(combatViews.selectedId(), liveUpdates, selectedEncounter == null ? null : selectedEncounter.id,
```
Replace:
```java
        if (state.tab >= 0 && state.tab < combatTabs.getTabCount()) combatTabs.setSelectedIndex(state.tab);
```
with:
```java
        if (state.tab != null) combatViews.select(state.tab);
```
Replace:
```java
                combatTabs.setSelectedIndex(0);
```
with:
```java
                showCombat("meters");
```
Replace:
```java
            public void open(Route route) { delegate.open(route); combatTabs.setSelectedComponent(resourcesWorkspace); }
```
with:
```java
            public void open(Route route) { delegate.open(route); showCombat("resources"); }
```
Replace:
```java
    JTabbedPane combatTabs() { return combatTabs; }
```
with:
```java
    JTabbedPane combatTabs() { return combatTabs; }
    private void showCombat(String id) { combatViews.show(id); combatViews.select(id); }
```

- [ ] **Step 7: Convert the live Resources views in `ActivityPanel.java`**

Replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    private final JTabbedPane combatViews=new JTabbedPane();
```
with:
```java
    private final CustomizableTabs combatTabs;
    private final JTabbedPane combatViews;
```
Replace:
```java
        record=new CollectionControl(log,this::refresh);
```
with:
```java
        combatTabs=mode==Mode.COMBAT?new CustomizableTabs("activity-combat"):null;combatViews=combatTabs==null?new JTabbedPane():combatTabs.component();
        record=new CollectionControl(log,this::refresh);
```
Replace:
```java
            combatViews.addTab("Buff timeline & resources",plot); combatViews.addTab("Uptime summary",ContentStyle.tableScroll(table,3));
            // Live visits are not yet saved-session references: window analysis works, Timeline handoffs explain why not.
            combatViews.addTab("Selected window",ResourceWindowPanel.scroll(new ResourceWindowPanel(chart,()->null)));
            combatViews.setName("activity-resource-tabs");
            combatViews.addChangeListener(e->{if(!refreshing&&!restoringState){fill(false);rememberLiveState();}});
```
with:
```java
            // Live visits are not yet saved-session references: window analysis works, Timeline handoffs explain why not.
            combatTabs.add("timeline","Buff timeline & resources",plot).add("uptime","Uptime summary",ContentStyle.tableScroll(table,3))
                .add("window","Selected window",ResourceWindowPanel.scroll(new ResourceWindowPanel(chart,()->null)));
            combatViews.setName("activity-resource-tabs");
            combatViews.addChangeListener(e->{if(!refreshing&&!restoringState&&!combatTabs.isRebuilding()){fill(false);rememberLiveState();}});
```
Replace:
```java
        values.put("tab",mode==Mode.COMBAT&&combatViews.getSelectedIndex()==1?"uptime":"primary");
```
with:
```java
        values.put("tab",uptimeShown()?"uptime":"primary");
```
Replace:
```java
            if(mode==Mode.COMBAT)combatViews.setSelectedIndex(tab);columns.run();
```
with:
```java
            if(mode==Mode.COMBAT)combatTabs.select(tab==1?"uptime":"timeline");columns.run();
```
Replace:
```java
        boolean updateTable=mode!=Mode.COMBAT||explicit||!tableInitialized||combatViews.getSelectedIndex()==1;
```
with:
```java
        boolean updateTable=mode!=Mode.COMBAT||explicit||!tableInitialized||uptimeShown();
```
Replace:
```java
            if(explicit||combatViews.getSelectedIndex()!=1)chart.setVisit(v);
```
with:
```java
            if(explicit||!uptimeShown())chart.setVisit(v);
```
Replace:
```java
    private RunDurationUnit unit(){return (RunDurationUnit)durationUnit.getSelectedItem();}
```
with:
```java
    private RunDurationUnit unit(){return (RunDurationUnit)durationUnit.getSelectedItem();}
    private boolean uptimeShown(){return combatTabs!=null&&"uptime".equals(combatTabs.selectedId());}
```

- [ ] **Step 8: Keep the ownership test on the Analyst tab it needs**

`src/test/java/tomato/gui/security/InspectRosterOwnershipTest.java` — add `import tomato.gui.kit.DisplayModeModel;` and replace:
```java
    @After public void clearLivePlayers()throws Exception{edt(()->{ParsePanelGUI.clear();return null;});}
```
with:
```java
    @After public void clearLivePlayers()throws Exception{edt(()->{ParsePanelGUI.clear();return null;});}
    private DisplayModeModel.Mode previousMode;
    /** These tests switch to Ability Use (index 2), which is Analyst-only since P1c. */
    @Before public void analystTabs()throws Exception{edt(()->{previousMode=DisplayModeModel.application().mode();DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);return null;});}
    @After public void restoreMode()throws Exception{edt(()->{DisplayModeModel.application().set(previousMode);return null;});}
```
Tests that keep working unchanged (default order, same names): `InspectContainerStateTest` (index 1), `ParsePanelRefreshTest` (selected title "Current Area"), `CharacterJournalLayoutTest` (indices 0–3), `CharacterViewStateTest` (index 3 "Notes"), `CharacterJournalFreshnessRefreshTest`, `CharacterWaveFourEvidenceTest` (by title), `ActivityLiveStateTest` (resource tab index 1), `DpsInvestigationTest`/`DpsResourcesWorkspaceTest` (`combatTabs()`, "dps-tabs"), `ActivityModulesTest` ("dps-tabs" title at 1), `ShellHookIntegrationTest` (adds a foreign tab to "dps-tabs"; no rebuild happens in that test), bridge `WaveThreeEvidenceTest` (`selectTab("Saved review")`).

- [ ] **Step 9: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.character.*" --tests "tomato.gui.security.*" --tests "tomato.gui.quest.*" --tests "tomato.gui.dps.*" --tests "tomato.gui.activity.*" --tests "tomato.bridge.*" --tests "tomato.gui.chat.ShellHookIntegrationTest"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/tomato/gui/character/CharacterPanelGUI.java src/main/java/tomato/gui/character/CharacterJournalGUI.java src/main/java/tomato/gui/security/SecurityGUI.java \
  src/main/java/tomato/gui/quest/QuestGUI.java src/main/java/tomato/gui/bridge/BridgeReviewGUI.java src/main/java/tomato/gui/dps/DpsGUI.java src/main/java/tomato/gui/activity/ActivityPanel.java \
  src/test/java/tomato/gui/character/CharacterTabsTest.java src/test/java/tomato/gui/security/InspectTabsTest.java src/test/java/tomato/gui/quest/QuestTabsTest.java \
  src/test/java/tomato/gui/activity/ActivityCombatTabsTest.java src/test/java/tomato/gui/security/InspectRosterOwnershipTest.java
git commit -m "Make Characters, Inspect, Quests, Bridge, DPS and Resources tabs customizable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Customizable tabs — Logging, live Key-pops and Statistics

Logging has no Analyst-only tabs: the whole page already sits in Advanced, and its tabs are cross-linked (Stat explorer → Event samples → Field catalog via "View retained samples"/"Open field definition"), so hiding the packet-level tabs in Simple mode would break in-page navigation that Logging users rely on.

| Group ID (pane name) | Tab IDs in default order |
|---|---|
| `logging` (`logging-tabs`) | `discovery`, `reentry`, `packets`, `stats`, `events`, `fields` (the existing `TAB_KEYS`, so saved Logging views keep working) |
| `keypops-live` (`keypops-live-tabs`) | `events`, `by-player`, `by-item` (Mode order) |
| `statistics` (`statistics-tabs`) | `fame-graph`, `fame-table`, `loot`, `dungeon-stats` |

**Files:**
- Modify: `src/main/java/tomato/gui/logging/LoggingGUI.java`, `src/main/java/tomato/gui/keypop/KeyPopDashboard.java`
- Replace: `src/main/java/tomato/gui/stats/StatisticsGUI.java`
- Create tests: `src/test/java/tomato/gui/logging/LoggingTabsTest.java`, `src/test/java/tomato/gui/keypop/KeyPopTabsTest.java`, `src/test/java/tomato/gui/stats/StatisticsTabsTest.java`

**Interfaces:**
- Consumes: P1a `CustomizableTabs`; existing `LoggingGUI(DiscoveryLog, ViewStateStore)`, `captureViewState()`, `LoggingStateTestSupport.memoryStore()`, `KeyPopDashboard.captureLiveState()/selectedDungeon()`, `StatisticsLiveState.tabs(JTabbedPane)` (unchanged: it persists the visible index under the pane name, and the saved order is restored before it, so the same position means the same tab).
- Produces: groups above; `StatisticsGUI.tabTips(JTabbedPane, Map<Component,String>)` (private) keeps tab tooltips attached to their pages across rebuilds.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/logging/LoggingTabsTest.java`:
```java
package tomato.gui.logging;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class LoggingTabsTest {
    private static final String ORDER = "ui.tabs.logging";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void movedTabsKeepTheirTablesAndSavedStateKeys() throws Exception {
        PropertiesManager.setProperties(ORDER, "fields,discovery,reentry,packets,stats,events|");
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                LoggingGUI panel = new LoggingGUI(log, LoggingStateTestSupport.memoryStore());
                JTabbedPane tabs = find(panel, JTabbedPane.class, "logging-tabs");
                assertEquals(6, tabs.getTabCount()); assertEquals("Field catalog", tabs.getTitleAt(0));
                assertEquals("The restored default state selects Discovery by ID, wherever it sits", "Discovery", tabs.getTitleAt(tabs.getSelectedIndex()));
                assertEquals("discovery", panel.captureViewState().tab);
                tabs.setSelectedIndex(0); assertEquals("fields", panel.captureViewState().tab);
                tabs.setSelectedIndex(tabs.indexOfTab("Packets")); assertEquals("packets", panel.captureViewState().tab);
            });
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/keypop/KeyPopTabsTest.java`:
```java
package tomato.gui.keypop;

import java.time.Instant;
import javax.swing.*;
import org.junit.*;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class KeyPopTabsTest {
    private static final String ORDER = "ui.tabs.keypops-live";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void movedViewsKeepTheirModesAndTables() throws Exception {
        PropertiesManager.setProperties(ORDER, "by-item,events,by-player|");
        SwingUtilities.invokeAndWait(() -> {
            KeyPopHistory history = new KeyPopHistory(); history.add(new KeyPopEvent(Instant.now(), "Ann", "Halls", KeyPopEvent.Kind.KEY));
            KeyPopDashboard ui = new KeyPopDashboard(history);
            assertEquals("By dungeon / item", ui.tabs.getTitleAt(0));
            ui.tabs.setSelectedIndex(1); assertEquals(KeyPopArchiveClient.Mode.EVENTS, ui.captureLiveState().query.facets().mode);
            ui.tabs.setSelectedIndex(0); assertEquals(KeyPopArchiveClient.Mode.BY_ITEM, ui.captureLiveState().query.facets().mode);
            ui.items.setRowSelectionInterval(0, 0); assertEquals("Halls", ui.selectedDungeon());
        });
    }
}
```

`src/test/java/tomato/gui/stats/StatisticsTabsTest.java`:
```java
package tomato.gui.stats;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import tomato.backend.data.TomatoData;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class StatisticsTabsTest {
    private static final String ORDER = "ui.tabs.statistics";
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(ORDER); }
    @After public void restore() { PropertiesManager.setProperties(ORDER, saved == null ? "" : saved); }

    @Test public void tooltipsFollowTheirTabsWhenReordered() throws Exception {
        PropertiesManager.setProperties(ORDER, "loot,fame-graph,fame-table,dungeon-stats|");
        SwingUtilities.invokeAndWait(() -> {
            StatisticsGUI panel = new StatisticsGUI(new TomatoData());
            JTabbedPane tabs = find(panel, JTabbedPane.class, "statistics-tabs");
            assertEquals("Loot", tabs.getTitleAt(0));
            assertEquals("Shared session loot summaries and original drop log", tabs.getToolTipTextAt(0));
            assertEquals("Character and time-range filters with interval comparison", tabs.getToolTipTextAt(tabs.indexOfTab("Fame Graph")));
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.logging.LoggingTabsTest" --tests "tomato.gui.keypop.KeyPopTabsTest" --tests "tomato.gui.stats.StatisticsTabsTest"`
Expected: FAIL — `NullPointerException` (no `logging-tabs`), `expected:<By dungeon / item> but was:<Events>`, `expected:<Loot> but was:<Fame Graph>`.

- [ ] **Step 3: Convert `LoggingGUI.java`**

Replace:
```java
import tomato.gui.history.ViewStateStore;
```
with:
```java
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.CustomizableTabs;
```
Replace:
```java
    private final JTabbedPane tabs = new JTabbedPane();
```
with:
```java
    private final CustomizableTabs tabGroup = new CustomizableTabs("logging");
    private final JTabbedPane tabs = tabGroup.component();
    private boolean tabSyncPending;
```
Replace:
```java
        tabs.addTab("Discovery", discoveries.scroll()); tabs.addTab("Re-entry trace", reentry.scroll()); tabs.addTab("Packets", packets.scroll());
        tabs.addTab("Stat explorer", stats.scroll()); tabs.addTab("Event samples", events.scroll()); tabs.addTab("Field catalog", fields.scroll());
```
with:
```java
        // Tab IDs are the saved-state keys (TAB_KEYS), so saved Logging views survive reordering and hiding.
        tabGroup.add("discovery", "Discovery", discoveries.scroll()).add("reentry", "Re-entry trace", reentry.scroll()).add("packets", "Packets", packets.scroll())
            .add("stats", "Stat explorer", stats.scroll()).add("events", "Event samples", events.scroll()).add("fields", "Field catalog", fields.scroll());
        activeTab = activeIndex();
```
Replace:
```java
        tabs.addChangeListener(e -> {
            if (applyingState) return;
            activeTab=tabs.getSelectedIndex(); applyingState=true;
            try { search.setText(activeTable().filters.text); } finally { applyingState=false; }
            refreshTables(); updateFacets(); filter(); stateChanged();
        });
```
with:
```java
        tabs.addChangeListener(e -> {
            if (applyingState) return;
            if (tabGroup.isRebuilding()) { syncTabLater(); return; }
            tabChanged();
        });
```
Replace:
```java
    private DataTable activeTable() { return new DataTable[] {discoveries, reentry, packets, stats, events, fields}[tabs.getSelectedIndex()]; }
```
with:
```java
    private DataTable activeTable() { return tabTables()[activeIndex()]; }
    /** The selected tab as an index into TAB_KEYS/tabTables(), whatever its visible position. */
    private int activeIndex() { return Math.max(0, Arrays.asList(TAB_KEYS).indexOf(tabGroup.selectedId())); }
    private void showTab(String id) { tabGroup.show(id); tabGroup.select(id); }
    private void tabChanged() {
        activeTab=activeIndex(); applyingState=true;
        try { search.setText(activeTable().filters.text); } finally { applyingState=false; }
        refreshTables(); updateFacets(); filter(); stateChanged();
    }
    /** A rebuild (reorder, hide) passes through transient selections; sync once on the final one. */
    private void syncTabLater() {
        if (tabSyncPending) return;
        tabSyncPending = true; SwingUtilities.invokeLater(() -> { tabSyncPending = false; if (!applyingState) tabChanged(); });
    }
```
Replace:
```java
        events.filters=target; tabs.setSelectedIndex(4); updateFacets(); filter();
```
with:
```java
        events.filters=target; showTab("events"); updateFacets(); filter();
```
Replace:
```java
        tabs.setSelectedIndex(5); updateFacets(); filter(); fields.restore(event.packet + "|" + path); showDetails();
```
with:
```java
        showTab("fields"); updateFacets(); filter(); fields.restore(event.packet + "|" + path); showDetails();
```
Replace:
```java
            activeTab=Arrays.asList(TAB_KEYS).indexOf(state.tab); tabs.setSelectedIndex(activeTab);
```
with:
```java
            activeTab=Arrays.asList(TAB_KEYS).indexOf(state.tab); showTab(TAB_KEYS[activeTab]);
```

- [ ] **Step 4: Convert the live Key-pops views in `KeyPopDashboard.java`**

Replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
    final JTabbedPane tabs = new JTabbedPane();
```
with:
```java
    private final CustomizableTabs views = new CustomizableTabs("keypops-live");
    final JTabbedPane tabs = views.component();
    /** Tab IDs in Mode order; the live view state stores the mode, never a tab position. */
    private static final String[] VIEW_IDS = {"events", "by-player", "by-item"};
    private final JScrollPane eventsScroll = ContentStyle.tableScroll(events, 3), playersScroll = ContentStyle.tableScroll(players, 3), itemsScroll = ContentStyle.tableScroll(items, 3);
```
Replace:
```java
        tabs.addTab("Events", ContentStyle.tableScroll(events, 3)); tabs.addTab("By player", ContentStyle.tableScroll(players, 3)); tabs.addTab("By dungeon / item", ContentStyle.tableScroll(items, 3));
```
with:
```java
        views.add("events", "Events", eventsScroll).add("by-player", "By player", playersScroll).add("by-item", "By dungeon / item", itemsScroll);
```
Replace:
```java
        tabs.addChangeListener(e -> rememberState());
```
with:
```java
        tabs.addChangeListener(e -> { if (!views.isRebuilding()) rememberState(); });
```
Replace:
```java
            ((JScrollPane)tabs.getComponentAt(table == events ? 0 : table == players ? 1 : 2)).getViewport().addChangeListener(e -> rememberState());
```
with:
```java
            scroll(table).getViewport().addChangeListener(e -> rememberState());
```
Replace:
```java
f.mode = KeyPopArchiveClient.Mode.values()[tabs.getSelectedIndex()];
```
with:
```java
f.mode = mode();
```
Replace:
```java
        JTable table = activeTable(); JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent(); List<ArchiveRow.Ref> selection = new ArrayList<>();
```
with:
```java
        JTable table = activeTable(); JScrollPane scroll = scroll(table); List<ArchiveRow.Ref> selection = new ArrayList<>();
```
Replace:
```java
            bounds = state.query.bounds(); rebuildDates(); tabs.setSelectedIndex(f.mode.ordinal());
```
with:
```java
            bounds = state.query.bounds(); rebuildDates(); showView(VIEW_IDS[f.mode.ordinal()]);
```
Replace:
```java
if(ref.equals(state.anchor))((JScrollPane)tabs.getSelectedComponent()).getViewport()
```
with:
```java
if(ref.equals(state.anchor))scroll(table).getViewport()
```
Replace:
```java
    private JTable activeTable(){return tabs.getSelectedIndex()==0?events:tabs.getSelectedIndex()==1?players:items;}
```
with:
```java
    private JTable activeTable(){KeyPopArchiveClient.Mode mode=mode();return mode==KeyPopArchiveClient.Mode.EVENTS?events:mode==KeyPopArchiveClient.Mode.BY_PLAYER?players:items;}
    private KeyPopArchiveClient.Mode mode(){return KeyPopArchiveClient.Mode.values()[Math.max(0,Arrays.asList(VIEW_IDS).indexOf(views.selectedId()))];}
    private JScrollPane scroll(JTable table){return table==events?eventsScroll:table==players?playersScroll:itemsScroll;}
    private void showView(String id){views.show(id);views.select(id);}
```
Replace:
```java
            if (player) selectPlayer(value); else item.setSelectedItem(value);
            tabs.setSelectedIndex(0);
```
with:
```java
            if (player) selectPlayer(value); else item.setSelectedItem(value);
            showView("events");
```
Replace:
```java
        int tab = tabs.getSelectedIndex();
```
with:
```java
        int tab = mode().ordinal();
```
Replace:
```java
        if (tabs.getSelectedIndex() == 0) { exportCsv(); return; }
```
with:
```java
        if (mode() == KeyPopArchiveClient.Mode.EVENTS) { exportCsv(); return; }
```

- [ ] **Step 5: Replace `StatisticsGUI.java`**

```java
package tomato.gui.stats;

import java.awt.*;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.CustomizableTabs;

public class StatisticsGUI extends JPanel {
    private final LootGUI loot;
    private final CustomizableTabs tabs = new CustomizableTabs("statistics");
    private final JTabbedPane tabbedPane = tabs.component();
    private final FameTrackerGUI fameTracker;
    private final FameTablePanel fameTable;

    public LootDashboard getLootDashboard() { return loot.getDashboard(); }

    public StatisticsGUI(TomatoData data) {
        setLayout(new BorderLayout(0, 10));
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.setName("statistics-tabs");
        add(tabbedPane);

        fameTracker = new FameTrackerGUI();
        JComponent fameGraph = StatsUi.page(fameTracker, 510);

        fameTable = new FameTablePanel(data);
        JComponent famePage = StatsUi.page(fameTable, 510);

        // Initialize and connect the fame table bridge
        FameTableBridge.initialize();
        FameTableBridge bridge = FameTableBridge.getInstance();
        bridge.setFameTablePanel(fameTable);
        bridge.setFameTrackerGUI(fameTracker);

        loot = new LootGUI(data);
        JComponent lootPage = StatsUi.page(loot, 570);
        DungeonStats dungeonStats = new DungeonStats(true);
        JComponent dungeonPage = StatsUi.page(dungeonStats, 510);
        tabs.add("fame-graph", "Fame Graph", fameGraph).add("fame-table", "Fame Table", famePage)
            .add("loot", "Loot", lootPage).add("dungeon-stats", "Dungeon Stats", dungeonPage);
        Map<Component, String> tips = new HashMap<>();
        tips.put(fameGraph, "Character and time-range filters with interval comparison");
        tips.put(famePage, "Session totals and map visit breakdowns");
        tips.put(lootPage, "Shared session loot summaries and original drop log");
        tips.put(dungeonPage, "Current app session's dungeon, enemy and item counters");
        tabTips(tabbedPane, tips);
    }
    public void bindViewState(tomato.gui.history.ViewStateStore store){
        new StatisticsLiveState(store,"statistics-live").attach(this).tabs(tabbedPane);
        fameTracker.bindViewState(store);fameTable.bindViewState(store);
        loot.getDashboard().bindSiblingViewState(store,"statistics-live-loot");
    }

    /** CustomizableTabs re-adds pages when they move, hide or the display mode changes; each tooltip follows its page. */
    private static void tabTips(JTabbedPane pane, Map<Component, String> tips) {
        Runnable apply = () -> {
            for (int i = 0; i < pane.getTabCount(); i++) { String tip = tips.get(pane.getComponentAt(i)); if (tip != null) pane.setToolTipTextAt(i, tip); }
        };
        pane.addContainerListener(new ContainerAdapter() { @Override public void componentAdded(ContainerEvent e) { apply.run(); } });
        apply.run();
    }
}
```

- [ ] **Step 6: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.logging.*" --tests "tomato.gui.keypop.*" --tests "tomato.gui.stats.*"`
Expected: PASS. `LoggingGuiTest` still sees six tabs with "Re-entry trace" at index 1; `LoggingViewStateTest`/`CoverageExplanationTest` still read `captureViewState().tabs.get("packets"/"events")`; `ExactContributorTest`, `KeyPopLiveStateTest`, `KeyPopNotificationHandoffTest` and `KeyPopTest` use `ui.tabs` indices in the default order; `StatisticsExplorerTest` iterates `statistics-tabs` by index.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tomato/gui/logging/LoggingGUI.java src/main/java/tomato/gui/keypop/KeyPopDashboard.java src/main/java/tomato/gui/stats/StatisticsGUI.java \
  src/test/java/tomato/gui/logging/LoggingTabsTest.java src/test/java/tomato/gui/keypop/KeyPopTabsTest.java src/test/java/tomato/gui/stats/StatisticsTabsTest.java
git commit -m "Make Logging, live Key-pops and Statistics tabs customizable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Column kinds in archive tables

**Files:**
- Modify: `src/main/java/tomato/gui/history/HistoryTables.java`, `src/main/java/tomato/gui/activity/ActivityArchiveClient.java`, `src/main/java/tomato/gui/stats/LootArchiveClient.java`, `src/main/java/tomato/gui/keypop/KeyPopArchiveClient.java`, `src/main/java/tomato/gui/chat/ChatArchiveClient.java`
- Create test: `src/test/java/tomato/gui/history/HistoryTablesKindTest.java`

**Interfaces:**
- Consumes (P1a): `enum ColumnKind` (`int width(Font)`), `static TableCellRenderer KitTables.renderer(ColumnKind)`.
- Produces:
  - `HistoryTables.Column<R,V>`: new `public final ColumnKind kind` and constructor `Column(String id, String label, Class<V> type, Function<R,V> value, TableCellRenderer renderer, ColumnKind kind)`; the existing five-argument constructor stays (kind `null`).
  - `HistoryTables.queried(...)`: a column with a kind gets `max(kind width, header width, min width)` as its default width; a `String` column whose kind only lays out text (`TEXT`, `PLAYER`, `DUNGEON`, `CLASS`, `ITEM`, `STATUS`, `ID`) and has no explicit renderer gets `KitTables.renderer(kind)` (same text, kind alignment, "—" for missing). Numeric, time and boolean columns keep their typed renderers (zone-aware times, `h:mm:ss`, "Undated"), so every sort, copy and export is unchanged. Saved layouts (`ViewState.Table`) are applied afterwards and still win; "Reset columns" returns to these defaults because clients compute their defaults from the freshly built table.
  - `public static void HistoryTables.kinds(JTable table, Map<String, ColumnKind> kinds)` — the same widths for ad-hoc tables (by identifier, or header text when none is set); renderers untouched.
- Kinds per archive column:

| Client | Column id → kind |
|---|---|
| Activity (Runs/Resources/Inspect) | map DUNGEON, time DATE_TIME, duration DURATION, outcome STATUS, coverage TEXT, evidence TEXT, issues COUNT, gaps COUNT, players COUNT, damage NUMBER |
| Activity (Timeline) | time DATE_TIME, map DUNGEON, kind STATUS, summary TEXT, assignment STATUS |
| Loot/Statistics | type STATUS, time DATE_TIME, name ITEM, session ID, visit ID, dungeon DUNGEON, bag STATUS, dropper TEXT, item ID, tier STATUS, rarity STATUS, slots/applied/count/bags/items/runs COUNT, millis/average DURATION, whites/uts/sts/potions/completed/unknown/imports COUNT, rate/perRun/utHour/whiteRun/utRun/stRun/potionRun NUMBER, character ID, class CLASS, first/last/gain NUMBER, enemy ID, hits COUNT, damage NUMBER, build TEXT, ongoing STATUS, runLink STATUS, zeroLoot/unassigned/minRun/maxRun COUNT, median NUMBER, runChange/hourChange PERCENT, evidence TEXT |
| Key-pops | player PLAYER, item DUNGEON, time DATE_TIME, kind STATUS, pops/keys/runes/vials/incs/players COUNT, share PERCENT |
| Chat | star STATUS, time DATE_TIME, channel STATUS, player PLAYER, message TEXT |

Loot keeps its existing content fit: a column grows to its page's values (capped at 320 px) and never below its header, with the kind width as the floor.

- [ ] **Step 1: Write the failing test**

`src/test/java/tomato/gui/history/HistoryTablesKindTest.java`:
```java
package tomato.gui.history;

// Explicit AWT imports: java.awt.* would make ArchiveFixtures.Event ambiguous with java.awt.Event.
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.kit.ColumnKind;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class HistoryTablesKindTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void kindsSetDefaultWidthsTextRenderersAndResetTargets() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); String id = session(root, 5);
        try (SessionStore store = new SessionStore(root, false, "test"); ArchiveResult<Event> result = ArchiveResult.open(store, query(id), adapter(), scratch, new Cancellation())) {
            ArchivePage<Event> page = result.page(0, 5, new Cancellation());
            SwingUtilities.invokeAndWait(() -> {
                JTable table = HistoryTables.queried("kinds", columns(), page, Collections.emptyMap(), query(id), q -> {}, row -> {});
                Font font = table.getFont();
                assertEquals(ColumnKind.COUNT.width(font), table.getColumnModel().getColumn(0).getPreferredWidth());
                assertEquals(ColumnKind.TEXT.width(font), table.getColumnModel().getColumn(1).getPreferredWidth());
                assertEquals("Columns without a kind keep the legacy width", 145, table.getColumnModel().getColumn(2).getPreferredWidth());
                assertNull("Numbers keep their typed renderer", table.getColumnModel().getColumn(0).getCellRenderer());
                assertNotNull("Text columns use the kit renderer", table.getColumnModel().getColumn(1).getCellRenderer());
                assertEquals("Model values are unchanged", 0, table.getValueAt(0, 0));
                ViewState.Table defaults = HistoryTables.columnState(table, "Default");
                HistoryTables.applyColumns(table, new ViewState.Table("Custom", Arrays.asList(new ViewState.Column("value", 300, true),
                    new ViewState.Column("text", 40, true), new ViewState.Column("group", 90, true))));
                assertEquals(300, table.getColumnModel().getColumn(0).getWidth());
                JComponent controls = HistoryTables.controls(table, defaults, Collections.emptyMap(), layout -> {});
                for (Component c : controls.getComponents()) if (c instanceof JButton && "Reset columns".equals(((JButton) c).getText())) ((JButton) c).doClick();
                assertEquals(ColumnKind.COUNT.width(font), table.getColumnModel().getColumn(0).getWidth());
                assertEquals(ColumnKind.TEXT.width(font), table.getColumnModel().getColumn(1).getWidth());
                JTable adhoc = new JTable(new DefaultTableModel(new Object[]{"When", "Who"}, 0));
                adhoc.getColumnModel().getColumn(1).setMinWidth(400);
                Map<String, ColumnKind> kinds = new HashMap<>(); kinds.put("When", ColumnKind.DATE_TIME); kinds.put("Who", ColumnKind.PLAYER);
                HistoryTables.kinds(adhoc, kinds);
                assertEquals(ColumnKind.DATE_TIME.width(adhoc.getFont()), adhoc.getColumnModel().getColumn(0).getPreferredWidth());
                assertEquals("Kind widths never shrink below an existing minimum", 400, adhoc.getColumnModel().getColumn(1).getPreferredWidth());
                assertNull("Ad-hoc renderers are untouched", adhoc.getColumnModel().getColumn(0).getCellRenderer());
            });
        }
    }

    @Test public void savedRunsColumnsDefaultToTheirKinds() throws Exception {
        Path scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "kinds")) {
            ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = "visit"; v.map = "Lost Halls"; v.started = 1000; v.lastSeen = v.ended = 61_000; store.put("runs", v.id, v); store.flush();
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace =
                edt(() -> ActivityPanel.workspace(store, new JLabel("Live"), ActivityPanel.Mode.RUNS, scratch, memory.states));
            try {
                edt(() -> { workspace.showSaved(); return null; }); await(() -> ArchiveNativeSupport.ready(workspace));
                edt(() -> {
                    JTable table = find(workspace, "saved-activity-table"); Font font = table.getFont();
                    assertEquals(ColumnKind.DUNGEON.width(font), table.getColumn("map").getPreferredWidth());
                    assertEquals(ColumnKind.DATE_TIME.width(font), table.getColumn("time").getPreferredWidth());
                    assertEquals(ColumnKind.STATUS.width(font), table.getColumn("outcome").getPreferredWidth());
                    return null; });
            } finally { edt(() -> { workspace.close(); return null; }); }
        }
    }

    private static List<HistoryTables.Column<Event, ?>> columns() {
        return Arrays.asList(new HistoryTables.Column<>("value", "Value", Integer.class, e -> e.value, null, ColumnKind.COUNT),
            new HistoryTables.Column<>("text", "Message", String.class, e -> e.text, null, ColumnKind.TEXT),
            new HistoryTables.Column<>("group", "Group", String.class, e -> e.group, null));
    }
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return result.get();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for EDT state");
    }
    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `GRADLE test --tests "tomato.gui.history.HistoryTablesKindTest"`
Expected: FAIL — compilation errors `constructor Column in class Column<R,V> cannot be applied to given types` and `cannot find symbol: method kinds(JTable,Map<String,ColumnKind>)`.

- [ ] **Step 3: Add kinds to `HistoryTables.java`**

Replace:
```java
import tomato.gui.modern.DisplayFormat;
```
with:
```java
import tomato.gui.modern.DisplayFormat;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.KitTables;
```
Replace:
```java
    public static final class Column<R,V> {
        public final String id,label;public final Class<V> type;public final Function<R,V> value;
        public final TableCellRenderer renderer;
        public Column(String id,String label,Class<V> type,Function<R,V> value,TableCellRenderer renderer){
            this.id=Objects.requireNonNull(id);this.label=Objects.requireNonNull(label);this.type=Objects.requireNonNull(type);
            this.value=Objects.requireNonNull(value);this.renderer=renderer;
        }
    }
```
with:
```java
    public static final class Column<R,V> {
        public final String id,label;public final Class<V> type;public final Function<R,V> value;
        public final TableCellRenderer renderer;
        /** Shared width/alignment family (spec §5.5); null keeps the legacy 240/145 px default. */
        public final ColumnKind kind;
        public Column(String id,String label,Class<V> type,Function<R,V> value,TableCellRenderer renderer){this(id,label,type,value,renderer,null);}
        public Column(String id,String label,Class<V> type,Function<R,V> value,TableCellRenderer renderer,ColumnKind kind){
            this.id=Objects.requireNonNull(id);this.label=Objects.requireNonNull(label);this.type=Objects.requireNonNull(type);
            this.value=Objects.requireNonNull(value);this.renderer=renderer;this.kind=kind;
        }
    }
    /** Kinds whose kit renderer only lays text out (alignment, "—" for missing); numbers and times keep typed renderers. */
    private static final Set<ColumnKind> TEXT_KINDS=EnumSet.of(ColumnKind.TEXT,ColumnKind.PLAYER,ColumnKind.DUNGEON,ColumnKind.CLASS,ColumnKind.ITEM,ColumnKind.STATUS,ColumnKind.ID);
```
In `queried(...)` replace:
```java
        for(int c=0;c<columns.size();c++){TableColumn column=table.getColumnModel().getColumn(c);column.setIdentifier(columns.get(c).id);if(columns.get(c).renderer!=null)column.setCellRenderer(columns.get(c).renderer);}
```
with:
```java
        for(int c=0;c<columns.size();c++){TableColumn column=table.getColumnModel().getColumn(c);Column<R,?> spec=columns.get(c);column.setIdentifier(spec.id);
            if(spec.renderer!=null)column.setCellRenderer(spec.renderer);
            else if(spec.kind!=null&&spec.type==String.class&&TEXT_KINDS.contains(spec.kind))column.setCellRenderer(KitTables.renderer(spec.kind));
            if(spec.kind!=null){int width=kindWidth(table,column,spec.kind);column.setPreferredWidth(width);column.setWidth(width);}}
```
Replace:
```java
    private static void action(JTable table,String stroke,String name,Runnable run){
```
with:
```java
    /**
     * Ad-hoc tables: ColumnKind widths by column identifier (the header text when none was set). Renderers are left
     * alone; they carry each table's units, zones and "Unknown" wording, so displayed text, sorting and exports stay identical.
     */
    public static void kinds(JTable table,Map<String,ColumnKind> kinds){
        for(TableColumn column:allColumns(table)){ColumnKind kind=kinds.get(String.valueOf(column.getIdentifier()));if(kind==null)continue;
            int width=kindWidth(table,column,kind);column.setPreferredWidth(width);column.setWidth(width);}
    }
    /** The kind's width, never narrower than the column's minimum or its header text. */
    private static int kindWidth(JTable table,TableColumn column,ColumnKind kind){
        TableCellRenderer header=column.getHeaderRenderer()!=null?column.getHeaderRenderer():table.getTableHeader()==null?null:table.getTableHeader().getDefaultRenderer();
        int title=header==null?0:header.getTableCellRendererComponent(table,column.getHeaderValue(),false,false,-1,0).getPreferredSize().width;
        return Math.max(column.getMinWidth(),Math.max(title,kind.width(table.getFont())));
    }
    private static void action(JTable table,String stroke,String name,Runnable run){
```

- [ ] **Step 4: Give the archive clients' columns their kinds**

`ActivityArchiveClient.java` — replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
            ViewState.Table defaults=defaults(columns);
```
with:
```java
            ViewState.Table defaults=defaults(columns,table);
```
Replace the body of `columns(Map<String,Sort> sorts)` up to the `for(Sort sort…)` line:
```java
        if(mode!=ActivityPanel.Mode.TIMELINE)columns.add(new HistoryTables.Column<>("map","Dungeon / area",String.class,r->r.map,null));
        columns.add(new HistoryTables.Column<>("time",mode==ActivityPanel.Mode.TIMELINE?"Time":"Entered",Instant.class,r->r.time==null?null:Instant.ofEpochMilli(r.time),null));
        if(mode==ActivityPanel.Mode.TIMELINE) {
            columns.add(new HistoryTables.Column<>("map","Area",String.class,r->r.map,null));
            columns.add(new HistoryTables.Column<>("kind","Activity",String.class,r->r.kind,null));
            columns.add(new HistoryTables.Column<>("summary","Summary",String.class,r->r.summary,null));
            columns.add(new HistoryTables.Column<>("assignment","Assignment",String.class,r->r.assigned?"Assigned":"Unassigned",null));
        } else {
            columns.add(new HistoryTables.Column<>("duration","Seconds",Double.class,r->r.durationMillis==null?null:r.durationMillis/1000.0,null));
            columns.add(new HistoryTables.Column<>("outcome","Outcome",String.class,r->r.outcome.toString(),null));
            columns.add(new HistoryTables.Column<>("coverage","Coverage",String.class,Row::coverage,null));
            columns.add(new HistoryTables.Column<>("evidence","Evidence source",String.class,r->r.evidence.toString(),null));
            columns.add(new HistoryTables.Column<>("issues","Capture issues",Long.class,r->r.issues,null));
            columns.add(new HistoryTables.Column<>("gaps","Timing gaps",Long.class,r->r.gaps,null));
            columns.add(new HistoryTables.Column<>("players","Players",Integer.class,r->r.players,null));
            columns.add(new HistoryTables.Column<>("damage","Damage",Long.class,r->r.damage,null));
        }
```
with:
```java
        if(mode!=ActivityPanel.Mode.TIMELINE)columns.add(new HistoryTables.Column<>("map","Dungeon / area",String.class,r->r.map,null,ColumnKind.DUNGEON));
        columns.add(new HistoryTables.Column<>("time",mode==ActivityPanel.Mode.TIMELINE?"Time":"Entered",Instant.class,r->r.time==null?null:Instant.ofEpochMilli(r.time),null,ColumnKind.DATE_TIME));
        if(mode==ActivityPanel.Mode.TIMELINE) {
            columns.add(new HistoryTables.Column<>("map","Area",String.class,r->r.map,null,ColumnKind.DUNGEON));
            columns.add(new HistoryTables.Column<>("kind","Activity",String.class,r->r.kind,null,ColumnKind.STATUS));
            columns.add(new HistoryTables.Column<>("summary","Summary",String.class,r->r.summary,null,ColumnKind.TEXT));
            columns.add(new HistoryTables.Column<>("assignment","Assignment",String.class,r->r.assigned?"Assigned":"Unassigned",null,ColumnKind.STATUS));
        } else {
            columns.add(new HistoryTables.Column<>("duration","Seconds",Double.class,r->r.durationMillis==null?null:r.durationMillis/1000.0,null,ColumnKind.DURATION));
            columns.add(new HistoryTables.Column<>("outcome","Outcome",String.class,r->r.outcome.toString(),null,ColumnKind.STATUS));
            columns.add(new HistoryTables.Column<>("coverage","Coverage",String.class,Row::coverage,null,ColumnKind.TEXT));
            columns.add(new HistoryTables.Column<>("evidence","Evidence source",String.class,r->r.evidence.toString(),null,ColumnKind.TEXT));
            columns.add(new HistoryTables.Column<>("issues","Capture issues",Long.class,r->r.issues,null,ColumnKind.COUNT));
            columns.add(new HistoryTables.Column<>("gaps","Timing gaps",Long.class,r->r.gaps,null,ColumnKind.COUNT));
            columns.add(new HistoryTables.Column<>("players","Players",Integer.class,r->r.players,null,ColumnKind.COUNT));
            columns.add(new HistoryTables.Column<>("damage","Damage",Long.class,r->r.damage,null,ColumnKind.NUMBER));
        }
```
Replace the whole `defaults` method:
```java
    private ViewState.Table defaults(List<HistoryTables.Column<Row,?>> columns) {
        List<ViewState.Column> layout=new ArrayList<>();
        for(int i=0;i<columns.size();i++) {
            String id=columns.get(i).id;int width="map".equals(id)?145:"time".equals(id)?150:"duration".equals(id)?80:"summary".equals(id)?360:180;
            layout.add(new ViewState.Column(id,width,i<5));
        }
        return new ViewState.Table("Compact",layout);
    }
```
with:
```java
    /** Compact defaults: the first five columns, each at its column-kind width (what Reset columns returns to). */
    private ViewState.Table defaults(List<HistoryTables.Column<Row,?>> columns,JTable table) {
        List<ViewState.Column> layout=new ArrayList<>();
        for(int i=0;i<columns.size();i++)layout.add(new ViewState.Column(columns.get(i).id,table.getColumnModel().getColumn(i).getPreferredWidth(),i<5));
        return new ViewState.Table("Compact",layout);
    }
```

`LootArchiveClient.java` — replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
```
Replace the `col` helper and the whole `columns()` method (from `    private static <V> HistoryTables.Column<Row,V> col(` through `        col("evidence","Calculation / coverage",String.class,r->r.evidence));}`) with:
```java
    private static <V> HistoryTables.Column<Row,V> col(String id,String label,Class<V> type,java.util.function.Function<Row,V> value,ColumnKind kind){return new HistoryTables.Column<>(id,label,type,value,null,kind);}
    static List<HistoryTables.Column<Row,?>> columns(){return Arrays.asList(
        col("type","Row unit/type",String.class,r->r.type,ColumnKind.STATUS),new HistoryTables.Column<>("time","Timestamp (epoch ms)",Long.class,r->r.time,timeRenderer(),ColumnKind.DATE_TIME),col("name","Name",String.class,r->r.name,ColumnKind.ITEM),
        col("session","Source session",String.class,r->r.session,ColumnKind.ID),col("visit","Recorded visit ID (within session)",String.class,r->r.visitId,ColumnKind.ID),col("dungeon","Dungeon",String.class,r->r.dungeon,ColumnKind.DUNGEON),col("bag","Bag",String.class,r->r.bag,ColumnKind.STATUS),col("dropper","Dropper",String.class,r->r.dropper,ColumnKind.TEXT),
        col("item","Item ID",Integer.class,r->r.itemId,ColumnKind.ID),col("tier","Tier",String.class,r->r.tier,ColumnKind.STATUS),col("rarity","Rarity",String.class,r->r.rarity,ColumnKind.STATUS),col("slots","Slots",Integer.class,r->r.slots,ColumnKind.COUNT),col("applied","Applied enchants",Integer.class,r->r.applied,ColumnKind.COUNT),
        col("count","Occurrences / sample observations / count",Long.class,r->r.count,ColumnKind.COUNT),col("bags","Bags",Long.class,r->r.bags,ColumnKind.COUNT),col("items","Items",Long.class,r->r.items,ColumnKind.COUNT),col("runs","Visits / activity-recorded exits",Long.class,r->r.runs,ColumnKind.COUNT),new HistoryTables.Column<>("millis","Observed / finalized milliseconds",Long.class,r->r.millis,durationRenderer(),ColumnKind.DURATION),new HistoryTables.Column<>("average","Average finalized milliseconds / exit",Long.class,r->r.averageMillis,durationRenderer(),ColumnKind.DURATION),
        col("whites","White bags",Long.class,r->r.whites,ColumnKind.COUNT),col("uts","UT gear",Long.class,r->r.uts,ColumnKind.COUNT),col("sts","ST gear",Long.class,r->r.sts,ColumnKind.COUNT),col("potions","Stat potions",Long.class,r->r.potions,ColumnKind.COUNT),col("completed","Completed",Long.class,r->r.completed,ColumnKind.COUNT),col("unknown","Excluded unknown visits",Long.class,r->r.unknownRuns,ColumnKind.COUNT),col("imports","Excluded imported visits",Long.class,r->r.importedRuns,ColumnKind.COUNT),
        col("rate","Items / hour",Double.class,r->r.perHour,ColumnKind.NUMBER),col("perRun","Items / run",Double.class,r->r.perRun,ColumnKind.NUMBER),col("utHour","UT / hour",Double.class,r->r.utPerHour,ColumnKind.NUMBER),col("whiteRun","Whites / run",Double.class,r->r.whitesPerRun,ColumnKind.NUMBER),col("utRun","UT / run",Double.class,r->r.utPerRun,ColumnKind.NUMBER),col("stRun","ST / run",Double.class,r->r.stPerRun,ColumnKind.NUMBER),col("potionRun","Potions / run",Double.class,r->r.potionsPerRun,ColumnKind.NUMBER),
        col("character","Character ID (within session)",Integer.class,r->r.character,ColumnKind.ID),col("class","Class",String.class,r->r.className,ColumnKind.CLASS),col("first","First fame",Double.class,r->r.firstFame,ColumnKind.NUMBER),col("last","Last fame",Double.class,r->r.lastFame,ColumnKind.NUMBER),col("gain","Fame change",Double.class,r->r.gain,ColumnKind.NUMBER),col("enemy","Enemy ID",Integer.class,r->r.enemyId,ColumnKind.ID),col("hits","Hit events",Long.class,r->r.hits,ColumnKind.COUNT),col("damage","Damage",Long.class,r->r.damage,ColumnKind.NUMBER),col("build","Build",String.class,r->r.build,ColumnKind.TEXT),col("ongoing","Ongoing contribution at counter snapshot",String.class,r->"COUNTERS".equals(r.type)?r.ongoingActivity==null?"Not captured":r.ongoingActivity?"Included; time not finalized":"None at snapshot":null,ColumnKind.STATUS),col("runLink","Recorded run link",String.class,r->r.runLinked==null?null:r.runLinked?"Verified":"Unavailable",ColumnKind.STATUS),col("zeroLoot","Eligible runs with no linked bags",Long.class,r->r.zeroLootRuns,ColumnKind.COUNT),col("unassigned","Unassigned bags",Long.class,r->r.unassignedBags,ColumnKind.COUNT),
        col("minRun","Minimum items / run",Long.class,r->r.minPerRun,ColumnKind.COUNT),col("median","Median items / run",Double.class,r->r.medianPerRun,ColumnKind.NUMBER),col("maxRun","Maximum items / run",Long.class,r->r.maxPerRun,ColumnKind.COUNT),col("runChange","Items / run change (% of baseline)",Double.class,r->r.perRunChange,ColumnKind.PERCENT),col("hourChange","Items / hour change (% of baseline)",Double.class,r->r.perHourChange,ColumnKind.PERCENT),
        col("evidence","Calculation / coverage",String.class,r->r.evidence,ColumnKind.TEXT));}
```
In `sizeColumns`, replace:
```java
        javax.swing.table.TableCellRenderer base=table.getTableHeader().getDefaultRenderer();Map<String,String> labels=new HashMap<>();for(HistoryTables.Column<Row,?> c:columns())labels.put(c.id,c.label);
```
with:
```java
        javax.swing.table.TableCellRenderer base=table.getTableHeader().getDefaultRenderer();Map<String,String> labels=new HashMap<>();Map<String,ColumnKind> kinds=new HashMap<>();
        for(HistoryTables.Column<Row,?> c:columns()){labels.put(c.id,c.label);kinds.put(c.id,c.kind);}
```
and replace:
```java
            int width=Math.max(header,Math.min(content+table.getIntercellSpacing().width+2,320));
```
with:
```java
            // The column kind is the floor; Loot still grows a column to its page's values (capped at 320 px) so no value is cut.
            ColumnKind kind=kinds.get(id);int width=Math.max(header,Math.max(kind==null?0:kind.width(table.getFont()),Math.min(content+table.getIntercellSpacing().width+2,320)));
```

`KeyPopArchiveClient.java` — replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
        if(f.mode!=Mode.BY_ITEM){columns.add(new HistoryTables.Column<>("player","Player",String.class,r->r.player,null));sorts.put("player",Sort.PLAYER);}
        if(f.mode!=Mode.BY_PLAYER){columns.add(new HistoryTables.Column<>("item","Dungeon / item",String.class,r->r.item,null));sorts.put("item",Sort.ITEM);}
        columns.add(new HistoryTables.Column<>("time",f.mode==Mode.EVENTS?"Time":"Last pop",Instant.class,r->r.time,null));sorts.put("time",Sort.TIME);
        if(f.mode==Mode.EVENTS){columns.add(new HistoryTables.Column<>("kind","Type",String.class,r->r.kind,null));sorts.put("kind",Sort.KIND);}
        else{
            columns.add(new HistoryTables.Column<>("pops","Pops",Long.class,r->r.pops,null));sorts.put("pops",Sort.POPS);
            columns.add(new HistoryTables.Column<>("keys","Keys",Long.class,r->r.keys,null));sorts.put("keys",Sort.KEYS);
            columns.add(new HistoryTables.Column<>("runes","Runes",Long.class,r->r.runes,null));sorts.put("runes",Sort.RUNES);
            columns.add(new HistoryTables.Column<>("vials","Vials",Long.class,r->r.vials,null));sorts.put("vials",Sort.VIALS);
            columns.add(new HistoryTables.Column<>("incs","Incs",Long.class,r->r.incs,null));sorts.put("incs",Sort.INCS);
            if(f.mode==Mode.BY_ITEM){columns.add(new HistoryTables.Column<>("players","Players",Long.class,r->r.players,null));sorts.put("players",Sort.PLAYERS);}
            columns.add(new HistoryTables.Column<>("share","Share %",Double.class,r->r.share,null));sorts.put("share",Sort.SHARE);
        }
```
with:
```java
        if(f.mode!=Mode.BY_ITEM){columns.add(new HistoryTables.Column<>("player","Player",String.class,r->r.player,null,ColumnKind.PLAYER));sorts.put("player",Sort.PLAYER);}
        if(f.mode!=Mode.BY_PLAYER){columns.add(new HistoryTables.Column<>("item","Dungeon / item",String.class,r->r.item,null,ColumnKind.DUNGEON));sorts.put("item",Sort.ITEM);}
        columns.add(new HistoryTables.Column<>("time",f.mode==Mode.EVENTS?"Time":"Last pop",Instant.class,r->r.time,null,ColumnKind.DATE_TIME));sorts.put("time",Sort.TIME);
        if(f.mode==Mode.EVENTS){columns.add(new HistoryTables.Column<>("kind","Type",String.class,r->r.kind,null,ColumnKind.STATUS));sorts.put("kind",Sort.KIND);}
        else{
            columns.add(new HistoryTables.Column<>("pops","Pops",Long.class,r->r.pops,null,ColumnKind.COUNT));sorts.put("pops",Sort.POPS);
            columns.add(new HistoryTables.Column<>("keys","Keys",Long.class,r->r.keys,null,ColumnKind.COUNT));sorts.put("keys",Sort.KEYS);
            columns.add(new HistoryTables.Column<>("runes","Runes",Long.class,r->r.runes,null,ColumnKind.COUNT));sorts.put("runes",Sort.RUNES);
            columns.add(new HistoryTables.Column<>("vials","Vials",Long.class,r->r.vials,null,ColumnKind.COUNT));sorts.put("vials",Sort.VIALS);
            columns.add(new HistoryTables.Column<>("incs","Incs",Long.class,r->r.incs,null,ColumnKind.COUNT));sorts.put("incs",Sort.INCS);
            if(f.mode==Mode.BY_ITEM){columns.add(new HistoryTables.Column<>("players","Players",Long.class,r->r.players,null,ColumnKind.COUNT));sorts.put("players",Sort.PLAYERS);}
            columns.add(new HistoryTables.Column<>("share","Share %",Double.class,r->r.share,null,ColumnKind.PERCENT));sorts.put("share",Sort.SHARE);
        }
```

`ChatArchiveClient.java` — replace:
```java
import tomato.gui.kit.FilterBar;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
```
Replace:
```java
            new HistoryTables.Column<>("star", "Starred", Boolean.class, r -> r.starred, null),
            new HistoryTables.Column<>("time", "Local receipt", LocalDateTime.class, r -> r.message.received, null),
            new HistoryTables.Column<>("channel", "Channel", String.class, r -> r.message.channel.label + (r.reason.isEmpty() ? "" : " · Ignored"), null),
            new HistoryTables.Column<>("player", "Player", String.class, r -> r.message.playerLabel(), null),
            new HistoryTables.Column<>("message", "Message", String.class, r -> r.message.text, null));
```
with:
```java
            new HistoryTables.Column<>("star", "Starred", Boolean.class, r -> r.starred, null, ColumnKind.STATUS),
            new HistoryTables.Column<>("time", "Local receipt", LocalDateTime.class, r -> r.message.received, null, ColumnKind.DATE_TIME),
            new HistoryTables.Column<>("channel", "Channel", String.class, r -> r.message.channel.label + (r.reason.isEmpty() ? "" : " · Ignored"), null, ColumnKind.STATUS),
            new HistoryTables.Column<>("player", "Player", String.class, r -> r.message.playerLabel(), null, ColumnKind.PLAYER),
            new HistoryTables.Column<>("message", "Message", String.class, r -> r.message.text, null, ColumnKind.TEXT));
```

- [ ] **Step 5: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.history.*" --tests "tomato.gui.activity.*" --tests "tomato.gui.stats.*" --tests "tomato.gui.chat.*" --tests "tomato.gui.keypop.*" --tests "tomato.gui.security.*"`
Expected: PASS. `ActivityArchiveUiTest` still finds Outcome left of 500 px in the compact Runs view (185 + 159 + header-floored duration ≈ 94 px at 13 pt); Loot tests that require columns at least as wide as their headers still pass (header remains the floor).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/history/HistoryTables.java src/main/java/tomato/gui/activity/ActivityArchiveClient.java src/main/java/tomato/gui/stats/LootArchiveClient.java \
  src/main/java/tomato/gui/keypop/KeyPopArchiveClient.java src/main/java/tomato/gui/chat/ChatArchiveClient.java src/test/java/tomato/gui/history/HistoryTablesKindTest.java
git commit -m "Default archive table columns to shared column kinds

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Column kinds in ad-hoc tables

Widths only (`HistoryTables.kinds`), applied where each table already set fixed widths; every existing renderer, sort key, string converter and export path is left as is.

| Table | Header (or identifier) → kind |
|---|---|
| Logging `DataTable` (all six) | Time DATE_TIME; Area DUNGEON in Discovery (4 columns), else ID; ID ID; Direction, Evidence, Outcome, Java type STATUS; Count, Bytes, Observations, Changes, Withheld, Decode errors, Trailing, Type listeners COUNT; Latest, Secondary, Min, Max NUMBER; Since prior DURATION; Packet, Stat, Step, Field path, Retention, Selected values / stat samples, Retained evidence, Fields to explore, Sources TEXT |
| Key-pops dashboard (events, players, items) | Time, Last pop DATE_TIME; Player PLAYER; Type STATUS; Dungeon / item DUNGEON; Pops, Keys, Runes, Vials, Incs, Players COUNT; Share % PERCENT |
| `ActivityPanel` Runs (`column-0…11`) | Dungeon DUNGEON, Entered DATE_TIME, Observed minutes/seconds DURATION, Outcome STATUS, Coverage TEXT, Progress increase COUNT, Use requests COUNT, Capture issues COUNT, Timing gaps COUNT, Evidence source TEXT, Damage NUMBER, DPS NUMBER |
| `ActivityPanel` Timeline (`column-0…4`) | Time DATE_TIME, Area DUNGEON, Activity STATUS, Summary TEXT, Meaning TEXT |
| `ActivityPanel` Resources (`column-0…3`) | Condition TEXT, Active seconds DURATION, Observed seconds DURATION, Uptime % PERCENT |
| Characters roster | Character PLAYER, Account PLAYER, State STATUS, Season STATUS, Level COUNT, Maxed COUNT, Fame COUNT, Last snapshot update DATE_TIME, Potions remaining COUNT |
| Quests table (in `sizeColumns`, still floored by header/example fit) | Pin STATUS, Quest TEXT, Type STATUS, Rewards ITEM, Needed COUNT, Availability STATUS |
| DPS meter (`dps-player-table`) | Player / meter TEXT (bar + name), Class CLASS, Damage NUMBER, DPS NUMBER, Recorded share % PERCENT, Hits dealt COUNT, Avg hit NUMBER, Max hit NUMBER, Taken (est.) NUMBER, Hits taken COUNT |
| Inspect runs (`column-0…6`) | Entered DATE_TIME, Dungeon DUNGEON, Players COUNT, Status TEXT (status sentence with evidence), Damage NUMBER, DPS NUMBER, Observed minutes DURATION |
| Party roster (`ParsePanelGUI`) | Player / level PLAYER, Guild PLAYER, Class CLASS, Maxed COUNT, Character mode STATUS, Damage NUMBER, DPS NUMBER, Requirements STATUS; Weapon/Ability/Armor/Ring keep 75 px (icon-only sprite cells have no kind) |

**Files:**
- Modify: `src/main/java/tomato/gui/logging/LoggingGUI.java`, `keypop/KeyPopDashboard.java`, `activity/ActivityPanel.java`, `character/CharacterJournalGUI.java`, `quest/QuestGUI.java`, `dps/MeterDpsGUI.java`, `security/InspectRunsPanel.java`, `security/ParsePanelGUI.java`
- Create tests: `src/test/java/tomato/gui/dps/TableKindsTest.java`, `src/test/java/tomato/gui/logging/LoggingTableKindsTest.java`, `src/test/java/tomato/gui/keypop/KeyPopTableKindsTest.java`, `src/test/java/tomato/gui/character/CharacterTableKindsTest.java`, `src/test/java/tomato/gui/quest/QuestTableKindsTest.java`, `src/test/java/tomato/gui/security/InspectTableKindsTest.java`

**Interfaces:**
- Consumes: Task 10 `HistoryTables.kinds(JTable, Map<String, ColumnKind>)`; P1a `ColumnKind.width(Font)`.
- Produces: the widths above. Saved layouts still win: Logging views, `RosterViewState` column states and chat/key-pop live layouts are applied after construction as before.

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/dps/TableKindsTest.java`:
```java
package tomato.gui.dps;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class TableKindsTest {
    @Test public void meterAndLiveRunTablesUseColumnKindWidths() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                JTable meter = find(new MeterDpsGUI(), "dps-player-table");
                assertEquals(ColumnKind.CLASS.width(meter.getFont()), meter.getColumn("Class").getPreferredWidth());
                assertEquals(ColumnKind.NUMBER.width(meter.getFont()), meter.getColumn("Damage").getPreferredWidth());
                JTable runs = find(new ActivityPanel(log, ActivityPanel.Mode.RUNS), "activity-table");
                assertEquals(ColumnKind.DUNGEON.width(runs.getFont()), runs.getColumnModel().getColumn(0).getPreferredWidth());
                assertEquals(ColumnKind.DATE_TIME.width(runs.getFont()), runs.getColumnModel().getColumn(1).getPreferredWidth());
            });
        }
    }

    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/logging/LoggingTableKindsTest.java`:
```java
package tomato.gui.logging;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class LoggingTableKindsTest {
    @Test public void diagnosticTablesUseColumnKindWidthsAsTheirDefaults() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                LoggingGUI panel = new LoggingGUI(log, LoggingStateTestSupport.memoryStore());
                JTable packets = find(panel, "logging-table-0"), events = find(panel, "logging-table-2");
                assertEquals(ColumnKind.ID.width(packets.getFont()), packets.getColumn("ID").getPreferredWidth());
                assertEquals(ColumnKind.COUNT.width(packets.getFont()), packets.getColumn("Count").getPreferredWidth());
                assertEquals(ColumnKind.DATE_TIME.width(events.getFont()), events.getColumn("Time").getPreferredWidth());
            });
        }
    }

    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/keypop/KeyPopTableKindsTest.java`:
```java
package tomato.gui.keypop;

import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class KeyPopTableKindsTest {
    @Test public void liveKeyPopTablesUseColumnKindWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopDashboard ui = new KeyPopDashboard(new KeyPopHistory());
            assertEquals(ColumnKind.DATE_TIME.width(ui.events.getFont()), ui.events.getColumnModel().getColumn(0).getPreferredWidth());
            assertEquals(ColumnKind.PLAYER.width(ui.events.getFont()), ui.events.getColumnModel().getColumn(1).getPreferredWidth());
            assertEquals(ColumnKind.COUNT.width(ui.players.getFont()), ui.players.getColumnModel().getColumn(1).getPreferredWidth());
        });
    }
}
```

`src/test/java/tomato/gui/character/CharacterTableKindsTest.java`:
```java
package tomato.gui.character;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class CharacterTableKindsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void rosterUsesColumnKindWidths() throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        SwingUtilities.invokeAndWait(() -> {
            JTable roster = find(new CharacterJournalGUI(journal, () -> 5000, RosterDefinitions::empty), "character-roster");
            assertEquals(ColumnKind.PLAYER.width(roster.getFont()), roster.getColumn("Character").getPreferredWidth());
            assertEquals(ColumnKind.COUNT.width(roster.getFont()), roster.getColumn("Level").getPreferredWidth());
            assertTrue(roster.getColumn("Last snapshot update").getPreferredWidth() >= ColumnKind.DATE_TIME.width(roster.getFont()));
        });
    }

    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/quest/QuestTableKindsTest.java`:
```java
package tomato.gui.quest;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class QuestTableKindsTest {
    @Test public void questColumnsStartFromTheirKindsAndStillFitTheirExamples() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(new QuestGUI(id -> "Item " + id, id -> null, new QuestGuiTest.MemoryPreferences()), "quest-table");
            assertTrue(table.getColumnModel().getColumn(1).getPreferredWidth() >= ColumnKind.TEXT.width(table.getFont()));
            assertTrue(table.getColumnModel().getColumn(3).getPreferredWidth() >= ColumnKind.ITEM.width(table.getFont()));
            assertEquals(Math.max(table.getColumnModel().getColumn(4).getMinWidth(), ColumnKind.COUNT.width(table.getFont())),
                table.getColumnModel().getColumn(4).getPreferredWidth());
        });
    }

    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

`src/test/java/tomato/gui/security/InspectTableKindsTest.java`:
```java
package tomato.gui.security;

import java.awt.*;
import javax.swing.*;
import org.junit.*;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.kit.ColumnKind;
import static org.junit.Assert.*;

public class InspectTableKindsTest {
    @After public void clearLivePlayers() throws Exception { SwingUtilities.invokeAndWait(ParsePanelGUI::clear); }

    @Test public void rosterAndRunTablesUseColumnKindWidths() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            SwingUtilities.invokeAndWait(() -> {
                ParsePanelGUI roster = new ParsePanelGUI(false);
                JTable players = first(roster);
                assertEquals(ColumnKind.CLASS.width(players.getFont()), players.getColumn("Class").getPreferredWidth());
                assertEquals("Equipment sprite cells keep their width", 75, players.getColumn("Weapon").getPreferredWidth());
                JTable runs = find(new InspectRunsPanel(log, roster), "inspect-runs-table");
                assertEquals(ColumnKind.DATE_TIME.width(runs.getFont()), runs.getColumnModel().getColumn(0).getPreferredWidth());
            });
        }
    }

    private static JTable first(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable) return (JTable) child;
            if (child instanceof Container) { JTable found = first((Container) child); if (found != null) return found; }
        }
        return null;
    }
    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `GRADLE test --tests "tomato.gui.dps.TableKindsTest" --tests "tomato.gui.logging.LoggingTableKindsTest" --tests "tomato.gui.keypop.KeyPopTableKindsTest" --tests "tomato.gui.character.CharacterTableKindsTest" --tests "tomato.gui.quest.QuestTableKindsTest" --tests "tomato.gui.security.InspectTableKindsTest"`
Expected: FAIL — width mismatches such as `expected:<120> but was:<110>` (meter Class), `expected:<120> but was:<65>` (Logging ID), `expected:<159> but was:<175>` (key-pop Time), `expected:<159> but was:<150>` (roster Character), a failed `>=` for the Quest column, and `expected:<120> but was:<160>` (party Class).

- [ ] **Step 3: Logging `DataTable` in `LoggingGUI.java`**

Replace:
```java
import tomato.gui.kit.CustomizableTabs;
```
with:
```java
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.ColumnKind;
import tomato.gui.history.HistoryTables;
```
Replace:
```java
            for(int i=0;i<columns.length;i++) {
                String c=columns[i];
                int width=c.equals("Time") ? 175 : c.equals("Area") && columns.length==4 ? 215 : c.equals("ID") || c.equals("Area") ? 65
                    : c.equals("Field path") || c.equals("Retention") || c.contains("values") || c.contains("evidence") ? 300
                    : c.equals("Packet") || c.equals("Stat") || c.equals("Step") ? 215 : 130;
                table.getColumnModel().getColumn(i).setPreferredWidth(width);
                defaultColumns.add(new ViewState.Column(c,width,true));
            }
        }
```
with:
```java
            Map<String,ColumnKind> kinds=new HashMap<>();for(String c:columns)kinds.put(c,kind(c,columns.length));
            HistoryTables.kinds(table,kinds);
            for(int i=0;i<columns.length;i++)defaultColumns.add(new ViewState.Column(columns[i],table.getColumnModel().getColumn(i).getPreferredWidth(),true));
        }
        /** Column kind by header; Area is a map name in Discovery (four columns) and a numeric area ID elsewhere. */
        private static ColumnKind kind(String column,int count) {
            switch (column) {
                case "Time": return ColumnKind.DATE_TIME;
                case "Area": return count==4 ? ColumnKind.DUNGEON : ColumnKind.ID;
                case "ID": return ColumnKind.ID;
                case "Direction": case "Evidence": case "Outcome": case "Java type": return ColumnKind.STATUS;
                case "Count": case "Bytes": case "Observations": case "Changes": case "Withheld": case "Decode errors": case "Trailing": case "Type listeners": return ColumnKind.COUNT;
                case "Latest": case "Secondary": case "Min": case "Max": return ColumnKind.NUMBER;
                case "Since prior": return ColumnKind.DURATION;
                default: return ColumnKind.TEXT;
            }
        }
```

- [ ] **Step 4: Key-pops dashboard tables in `KeyPopDashboard.java`**

Replace:
```java
import tomato.gui.kit.CustomizableTabs;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.CustomizableTabs;
```
Replace:
```java
    private static final String ALL_ITEMS = "All dungeons / items";
```
with:
```java
    private static final String ALL_ITEMS = "All dungeons / items";
    /** Header → column kind for the three live tables (widths only; the Player emphasis and Type badge renderers stay). */
    private static final Map<String, ColumnKind> COLUMN_KINDS = new HashMap<>();
    static {
        for (String time : new String[]{"Time", "Last pop"}) COLUMN_KINDS.put(time, ColumnKind.DATE_TIME);
        COLUMN_KINDS.put("Player", ColumnKind.PLAYER); COLUMN_KINDS.put("Type", ColumnKind.STATUS); COLUMN_KINDS.put("Dungeon / item", ColumnKind.DUNGEON);
        for (String count : new String[]{"Pops", "Keys", "Runes", "Vials", "Incs", "Players"}) COLUMN_KINDS.put(count, ColumnKind.COUNT);
        COLUMN_KINDS.put("Share %", ColumnKind.PERCENT);
    }
```
Replace:
```java
        for (int i = 0; i < model.getColumnCount(); i++) {
            int width = model.getColumnClass(i) == Instant.class ? 175 : model.getColumnClass(i) == String.class ? (model.getColumnName(i).equals("Dungeon / item") ? 270 : model.getColumnName(i).equals("Type") ? 86 : 160) : 86;
            table.getColumnModel().getColumn(i).setPreferredWidth(width);
        }
```
with:
```java
        HistoryTables.kinds(table, COLUMN_KINDS);
```

- [ ] **Step 5: Live activity tables in `ActivityPanel.java`**

Replace:
```java
import tomato.gui.kit.CustomizableTabs;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.CustomizableTabs;
```
Replace:
```java
        for(int c=0;c<columns.length;c++)table.getColumnModel().getColumn(c).setPreferredWidth(c==1?190:c==0?150:160);
        for(int c=0;c<columns.length;c++)table.getColumnModel().getColumn(c).setIdentifier("column-"+c);
        if(mode==Mode.RUNS){int[] widths={145,150,85,175,150};for(int c=0;c<widths.length;c++){
            table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);table.getColumnModel().getColumn(c).setWidth(widths[c]);}}
        if(mode==Mode.TIMELINE){table.getColumnModel().getColumn(3).setPreferredWidth(320);table.getColumnModel().getColumn(4).setPreferredWidth(370);}
```
with:
```java
        ColumnKind[] kinds=mode==Mode.RUNS?new ColumnKind[]{ColumnKind.DUNGEON,ColumnKind.DATE_TIME,ColumnKind.DURATION,ColumnKind.STATUS,ColumnKind.TEXT,ColumnKind.COUNT,
                ColumnKind.COUNT,ColumnKind.COUNT,ColumnKind.COUNT,ColumnKind.TEXT,ColumnKind.NUMBER,ColumnKind.NUMBER}
            :mode==Mode.TIMELINE?new ColumnKind[]{ColumnKind.DATE_TIME,ColumnKind.DUNGEON,ColumnKind.STATUS,ColumnKind.TEXT,ColumnKind.TEXT}
            :new ColumnKind[]{ColumnKind.TEXT,ColumnKind.DURATION,ColumnKind.DURATION,ColumnKind.PERCENT};
        Map<String,ColumnKind> byId=new HashMap<>();
        for(int c=0;c<columns.length;c++){table.getColumnModel().getColumn(c).setIdentifier("column-"+c);byId.put("column-"+c,kinds[c]);}
        HistoryTables.kinds(table,byId);
```

- [ ] **Step 6: Characters roster and Quests table**

`CharacterJournalGUI.java` — replace:
```java
import tomato.gui.history.FilterChips;
```
with:
```java
import tomato.gui.history.FilterChips;
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.ColumnKind;
```
Replace:
```java
        int[] widths = {150, 130, 165, 85, 55, 70, 80, 150, 140};
        for (int i = 0; i < widths.length; i++) roster.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
```
with:
```java
        Map<String, ColumnKind> kinds = new HashMap<>();
        kinds.put("Character", ColumnKind.PLAYER); kinds.put("Account", ColumnKind.PLAYER); kinds.put("State", ColumnKind.STATUS); kinds.put("Season", ColumnKind.STATUS);
        kinds.put("Level", ColumnKind.COUNT); kinds.put("Maxed", ColumnKind.COUNT); kinds.put("Fame", ColumnKind.COUNT);
        kinds.put("Last snapshot update", ColumnKind.DATE_TIME); kinds.put("Potions remaining", ColumnKind.COUNT);
        HistoryTables.kinds(roster, kinds);
```

`QuestGUI.java` — replace:
```java
import tomato.gui.kit.CustomizableTabs;
```
with:
```java
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.CustomizableTabs;
```
Replace:
```java
        int[] preferred = {40, 210, 105, 210, 75, 110};
```
with:
```java
        ColumnKind[] kinds = {ColumnKind.STATUS, ColumnKind.TEXT, ColumnKind.STATUS, ColumnKind.ITEM, ColumnKind.COUNT, ColumnKind.STATUS};
```
and replace:
```java
            value.setPreferredWidth(Math.max(minimum, Math.round(preferred[column] * table.getFont().getSize2D() / ContentStyle.FONT_SIZE)));
```
with:
```java
            value.setPreferredWidth(Math.max(minimum, kinds[column].width(table.getFont())));
```

- [ ] **Step 7: DPS meter, Inspect runs and party roster**

`MeterDpsGUI.java` — replace:
```java
import tomato.gui.modern.ContentStyle;
```
with:
```java
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.ColumnKind;
import tomato.gui.modern.ContentStyle;
```
Replace:
```java
        for (int i = 0; i < model.getColumnCount(); i++) table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 230 : 110);
```
with:
```java
        Map<String, ColumnKind> kinds = new HashMap<>();
        kinds.put("Player / meter", ColumnKind.TEXT); kinds.put("Class", ColumnKind.CLASS); kinds.put("Recorded share %", ColumnKind.PERCENT);
        for (String number : new String[]{"Damage", "DPS", "Avg hit", "Max hit", "Taken (est.)"}) kinds.put(number, ColumnKind.NUMBER);
        for (String count : new String[]{"Hits dealt", "Hits taken"}) kinds.put(count, ColumnKind.COUNT);
        HistoryTables.kinds(table, kinds);
```

`InspectRunsPanel.java` — replace:
```java
import tomato.gui.history.HistoryTables;
```
with:
```java
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.ColumnKind;
```
Replace:
```java
        int[] widths = {160, 190, 70, 220, 110, 100, 130};
        for (int i = 0; i < widths.length; i++){table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);table.getColumnModel().getColumn(i).setIdentifier("column-"+i);}
```
with:
```java
        ColumnKind[] kinds = {ColumnKind.DATE_TIME, ColumnKind.DUNGEON, ColumnKind.COUNT, ColumnKind.TEXT, ColumnKind.NUMBER, ColumnKind.NUMBER, ColumnKind.DURATION};
        Map<String, ColumnKind> byId = new HashMap<>();
        for (int i = 0; i < kinds.length; i++){table.getColumnModel().getColumn(i).setIdentifier("column-"+i);byId.put("column-"+i,kinds[i]);}
        HistoryTables.kinds(table, byId);
```

`ParsePanelGUI.java` — replace:
```java
import tomato.gui.history.FilterChips;
```
with:
```java
import tomato.gui.history.FilterChips;
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.ColumnKind;
```
Replace:
```java
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        for (int i = 9; i <= 10; i++) runColumns.add(table.getColumnModel().getColumn(i));
```
with:
```java
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        // Weapon, Ability, Armor and Ring keep their 75 px sprite cells: no column kind describes an icon-only slot.
        Map<String, ColumnKind> kinds = new HashMap<>();
        kinds.put("Player / level", ColumnKind.PLAYER); kinds.put("Guild", ColumnKind.PLAYER); kinds.put("Class", ColumnKind.CLASS); kinds.put("Maxed", ColumnKind.COUNT);
        kinds.put("Character mode", ColumnKind.STATUS); kinds.put("Damage", ColumnKind.NUMBER); kinds.put("DPS", ColumnKind.NUMBER); kinds.put("Requirements", ColumnKind.STATUS);
        HistoryTables.kinds(table, kinds);
        for (int i = 9; i <= 10; i++) runColumns.add(table.getColumnModel().getColumn(i));
```

- [ ] **Step 8: Run the focused tests**

Run: `GRADLE test --tests "tomato.gui.dps.*" --tests "tomato.gui.logging.*" --tests "tomato.gui.keypop.*" --tests "tomato.gui.character.*" --tests "tomato.gui.quest.*" --tests "tomato.gui.security.*" --tests "tomato.gui.activity.*"`
Expected: PASS. `ActivityLiveStateTest` "Outcome fits at compact width" stays true (185 + 159 + ~110 header-floored duration + 133 ≈ 587 px < 680); `QuestConsistencyTest.assertTableGeometry` still sees headers and semantic cells fit (minimum widths are unchanged); `LoggingViewStateTest`, `CharacterViewStateTest` and `EncounterViewStateTest` restore saved widths over the new defaults.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/tomato/gui/logging/LoggingGUI.java src/main/java/tomato/gui/keypop/KeyPopDashboard.java src/main/java/tomato/gui/activity/ActivityPanel.java \
  src/main/java/tomato/gui/character/CharacterJournalGUI.java src/main/java/tomato/gui/quest/QuestGUI.java src/main/java/tomato/gui/dps/MeterDpsGUI.java \
  src/main/java/tomato/gui/security/InspectRunsPanel.java src/main/java/tomato/gui/security/ParsePanelGUI.java \
  src/test/java/tomato/gui/dps/TableKindsTest.java src/test/java/tomato/gui/logging/LoggingTableKindsTest.java src/test/java/tomato/gui/keypop/KeyPopTableKindsTest.java \
  src/test/java/tomato/gui/character/CharacterTableKindsTest.java src/test/java/tomato/gui/quest/QuestTableKindsTest.java src/test/java/tomato/gui/security/InspectTableKindsTest.java
git commit -m "Size ad-hoc tables by shared column kinds

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Visual evidence, full build and pull request

**Files:**
- Create test: `src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`

**Interfaces:**
- Consumes: `ui.VisualEvidence` (`show(JComponent, String, int, int, int)`, `settle()`, `capture(String)`, `closeWindow()`, static `named(...)`), `ArchiveNativeSupport.drawer/ready/await/Memory`, public factories `ActivityPanel.workspace(...)`, `HistoricalStatistics.lootWorkspace(...)`, `ChatGUI(TomatoData).workspace(SessionStore, Path, ViewStateStore)`, `KeypopGUI().workspace(...)`, `CharacterJournalGUI(CharacterJournal)`, `QuestGUI()`.
- Produces: 48 screenshots under `build/p1c/ui-test/screenshots/redesign-p1c/` (Runs, Loot, Chat, Key-pops, Characters, Quests × 1240×800 / 680×520 × fonts 13 / 18 × drawer closed / open) and the S6 assertion at desktop width; the P1c pull request.

- [ ] **Step 1: Write the evidence test**

This task adds no behaviour, so the test is evidence plus the S6 guard rather than a red-green step; it must pass on the first run after Tasks 1–11.

`src/test/java/tomato/gui/history/FilterBarEvidenceTest.java`:
```java
package tomato.gui.history;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.TomatoData;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.character.CharacterJournalGUI;
import tomato.gui.chat.ChatArchiveClient;
import tomato.gui.chat.ChatGUI;
import tomato.gui.keypop.KeyPopArchiveClient;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.FilterBar;
import tomato.gui.quest.QuestGUI;
import tomato.gui.stats.HistoricalStatistics;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;

/** S6 evidence: adopted pages with filters collapsed and open, 1240×800 and 680×520, fonts 13 and 18. Synthetic data only; no capture. */
public class FilterBarEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("redesign-p1c");

    private static final class Page {
        final String name; final JComponent root; final FilterBar bar; final BooleanSupplier ready;
        Page(String name, JComponent root, FilterBar bar, BooleanSupplier ready) { this.name = name; this.root = root; this.bar = bar; this.ready = ready; }
    }

    @Test @SuppressWarnings("unchecked") public void adoptedPagesShowOneFilterRowUntilTheDrawerOpens() throws Exception {
        Path root = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        Path runsScratch = temp.newFolder().toPath(), lootScratch = temp.newFolder().toPath(), chatScratch = temp.newFolder().toPath(), popsScratch = temp.newFolder().toPath();
        try (SessionStore store = new SessionStore(root, true, "p1c-evidence"); DiscoveryLog log = new DiscoveryLog(null)) {
            for (int i = 0; i < 6; i++) {
                ActivityJournal.Visit visit = new ActivityJournal.Visit(); visit.id = "visit-" + i; visit.map = i % 2 == 0 ? "Lost Halls" : "Ice Citadel";
                visit.started = 1_790_000_000_000L + i * 600_000L; visit.lastSeen = visit.ended = visit.started + 420_000L; store.put("runs", visit.id, visit);
            }
            store.flush();
            List<Page> pages = edt(() -> {
                List<Page> built = new ArrayList<>();
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs =
                    ActivityPanel.workspace(store, new ActivityPanel(log, ActivityPanel.Mode.RUNS), ActivityPanel.Mode.RUNS, runsScratch, memory.states);
                ActivityQueries.Filters run = runs.state().query.facets(); run.outcomes.add(ActivityQueries.Outcome.LEFT); run.minimumDurationMillis = 60_000L;
                runs.changeQuery(runs.state().query.withFacets(run)); built.add(archive("runs", runs));
                ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> loot = HistoricalStatistics.lootWorkspace(store, new LootDashboard(), lootScratch, memory.states);
                LootQuery.Facets items = loot.state().query.facets(); items.bags.add("White"); items.kind = LootQuery.Kind.UT_EQUIPMENT;
                loot.changeQuery(loot.state().query.withFacets(items)); built.add(archive("loot", loot));
                ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> chat =
                    (ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort>) new ChatGUI(new TomatoData()).workspace(store, chatScratch, memory.states);
                ChatArchiveClient.Facets channel = chat.state().query.facets(); channel.channel = "GUILD"; channel.starredOnly = true;
                chat.changeQuery(chat.state().query.withFacets(channel)); built.add(archive("chat", chat));
                ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> keypops =
                    (ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort>) new KeypopGUI().workspace(store, popsScratch, memory.states);
                KeyPopArchiveClient.Facets pops = keypops.state().query.facets(); pops.exactPlayer = "Ann"; pops.kinds.add("KEY");
                keypops.changeQuery(keypops.state().query.withFacets(pops)); built.add(archive("keypops", keypops));
                CharacterJournalGUI characters = new CharacterJournalGUI(new TomatoData().characterJournal());
                VisualEvidence.named(characters, "character-facet-2", JComboBox.class).setSelectedIndex(1);
                VisualEvidence.named(characters, "character-facet-4", JComboBox.class).setSelectedIndex(2);
                built.add(new Page("characters", characters, VisualEvidence.named(characters, "characters-filter-bar", FilterBar.class), () -> true));
                QuestGUI quests = new QuestGUI();
                VisualEvidence.named(quests, "quest-repeat-mode", JComboBox.class).setSelectedIndex(1);
                VisualEvidence.named(quests, "quest-pinned-only", AbstractButton.class).doClick();
                built.add(new Page("quests", quests, VisualEvidence.named(quests, "quests-filter-bar", FilterBar.class), () -> true));
                return built;
            });
            try {
                for (Page page : pages) for (int font : new int[]{13, 18}) for (int[] size : new int[][]{{1240, 800}, {680, 520}}) for (boolean open : new boolean[]{false, true}) {
                    edt(() -> { ArchiveNativeSupport.drawer(page.bar, open); evidence.show(page.root, page.name, size[0], size[1], font); return null; });
                    ArchiveNativeSupport.await(page.ready); evidence.settle(); ArchiveNativeSupport.await(page.ready);
                    edt(() -> {
                        evidence.capture("p1c-" + page.name + "-" + size[0] + "-" + font + (open ? "-filters-open" : "-filters-closed"));
                        assertEquals(open, page.bar.drawerOpen());
                        assertEquals(page.name + " drawer visibility", open, page.bar.drawerContent().isShowing());
                        assertTrue(page.name + " shows its active filters as chips", page.bar.activeCount() > 0);
                        if (!open && size[0] == 1240 && font == 13) assertOneFilterRow(page.name, page.bar);
                        return null;
                    });
                }
            } finally {
                edt(() -> {
                    for (Page page : pages) { ArchiveNativeSupport.drawer(page.bar, false); if (page.root instanceof ArchiveWorkspace) ((ArchiveWorkspace<?, ?, ?>) page.root).close(); }
                    evidence.closeWindow(); return null;
                });
            }
        }
    }

    private static Page archive(String name, ArchiveWorkspace<?, ?, ?> workspace) {
        return new Page(name, workspace, workspace.filterBar(), () -> ArchiveNativeSupport.ready(workspace) && workspace.state().archive);
    }

    /** S6 at desktop width: with the drawer closed, the search slot and the Filters toggle share one row. */
    private static void assertOneFilterRow(String name, FilterBar bar) {
        AbstractButton filters = VisualEvidence.named(bar, bar.getName().replace("-filter-bar", "-filters"), AbstractButton.class);
        Component slot = filters.getParent().getComponent(0);
        int slotY = SwingUtilities.convertPoint(slot, 0, 0, bar).y, filtersY = SwingUtilities.convertPoint(filters, 0, 0, bar).y;
        assertTrue(name + ": the search slot and Filters share one row", Math.abs(slotY - filtersY) < filters.getHeight());
    }
}
```

- [ ] **Step 2: Run the evidence test and review the screenshots**

Run: `GRADLE test --tests "tomato.gui.history.FilterBarEvidenceTest"`
Expected: PASS. Open the 48 images in `build/p1c/ui-test/screenshots/redesign-p1c/` and check: with filters closed each page shows exactly one row of filter controls (search, Filters · N, chips, Clear, scope, ⋯) at 1240×800; at 680×520 the same controls wrap without being cut; opened drawers show the module's existing facet controls unchanged; chips are readable at font 18; the archive footer shows paging and status only in saved views.

- [ ] **Step 3: Full build**

Run: `GRADLE test shadowJar`
Expected: `BUILD SUCCESSFUL`; every `build/p1c/test-results/test/*.xml` has `failures="0" errors="0"`.

- [ ] **Step 4: Commit and open the PR**

```bash
git add src/test/java/tomato/gui/history/FilterBarEvidenceTest.java
git commit -m "Add P1c filter bar evidence screenshots

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin feat/redesign-p1c-adoption
gh pr create --title "Redesign P1c: filter bars, customizable tabs and column kinds" --body "Implements P1c of docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md (§4.4, §5.5, §5.6, S6):

- Archive workspaces (Runs, Timeline, Resources, Inspect, Loot, Statistics, Chat, Key-pops) show one filter row: whole-scope search, a collapsed Filters drawer holding each module's existing facet controls, removable chips, Clear, the scope controls (Browse saved / Current live view, session, Refresh) and a ⋯ menu with History library, Saved views and exports. Paging, Cancel read/export and status move to a footer.
- Live Runs/Timeline/Resources, Chat, Key-pops, Loot, the party roster, Characters and Quests use the same FilterBar.
- Page sub-tab groups become CustomizableTabs (reorder/hide persisted by ID); Snapshot evidence and Ability Use are Analyst-only.
- Archive and ad-hoc tables default to shared ColumnKind widths; saved layouts still win and Reset columns returns to the kind defaults.

Query semantics, exports, saved views and ViewState formats are unchanged. Validation: focused tests per task, FilterBarEvidenceTest screenshots (1240×800 / 680×520, fonts 13 / 18, drawer closed / open) and a full test shadowJar.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
Request an independent review of the final PR head before merging.

---

## Self-review notes

**Spec coverage**

| Spec | Where |
|---|---|
| §5.6 one row: search, Filters · N, chips, Clear, Scope, ⋯ | Task 1 (archive workspaces), Tasks 5–7 (live panels) |
| §5.6 drawer hosts the existing facet controls, publishing `queryChanged` unchanged | Tasks 2–4 (`ArchiveClient.filters`, same `Binding`/`State` as the view) |
| §5.6 FacetDescriber → chips with remove | `ArchiveFilters.dates/summary`, per-client `chip(...)`, `LootFacetChips`, live `updateChips()` methods |
| §5.6 drawer closed by default, remembered per view | P1a `FilterBar` (`ui.filters.<name>.open`); names per workspace/panel |
| §5.6 `ArchiveWorkspace` slots; paging in a footer | Task 1 |
| §5.6 live panels (ActivityPanel run filters, Chat, Party display filters) | Tasks 5, 7 (plus Key-pops, Loot, Characters, Quests in Tasks 6–7) |
| §9 archive reads cancelable ("Cancel read", now shown only while loading) | Task 1 footer |
| §4.4 stable tab IDs, reorder/hide persisted, Analyst-only tabs skipped in Simple | Tasks 8–9 |
| §3.2 diagnostic sub-tabs hidden in Simple | Snapshot evidence, Ability Use (Task 8) |
| §3.2 exports/saved views/column presets in ⋯ | Task 1 (exports, saved views; column tools deferred, see below) |
| §5.5 `HistoryTables` defaults switch to kinds; saved layouts win; Reset columns → kind defaults | Task 10 |
| §5.5 ad-hoc tables adopt kinds | Task 11 |
| §5.7 honest values | No value/wording changes; KitTables text renderers show missing strings as "—"; "Unknown", units and zone text renderers untouched |
| §11 tests, brittle archive-toolbar test migration | Task 1 (`ArchiveNativeSupport`, lifecycle/export/shell tests), Tasks 3–9 (drawer/Analyst updates) |
| §12 P1c exit: S6, filters collapsed by default, tab reorder/hide persists | Tasks 1–9, evidence and S6 assertion in Task 12 |

**Decisions**

- `ArchiveClient.filters(page, state, binding)` is called inside the same `restoring` window as `render(...)`; clients build the drawer from the view they just rendered (Activity `currentView`, Loot `rendered`, Chat/Key-pops `SocialQueryControls.State`), so a drawer query retires that view exactly like the old in-view controls did. While a query loads, `invalidateView()` calls `setDrawerEnabled(false)`; `apply()` re-enables the replacement drawer.
- FilterBar Clear on archives = the old "Reset filters" (`client.initialQuery().withScope(current scope)`). Live panels keep their visible Reset buttons (tests and muscle memory) and Clear runs the same action.
- Scope controls sit in the FilterBar scope slot when the row has room and wrap into the search slot on narrow rows (`fitScope`), so the row stays one line at desktop widths and every control stays reachable at 680 px / 24 pt (the P1a trailing slot cannot wrap).
- In the live (not saved) view of an archive-backed page, the workspace row carries only scope and ⋯ (search, drawer and chips belong to saved history, as the old toolbar hid them); the live panel's own FilterBar is that page's single row of filter controls. Merging both into one physical row would need a live-panel → workspace slot API and is left for P5/P6 page redesigns.
- `WrapRow` and `FilterChips` live in `tomato.gui.history` (shared GUI infrastructure) so the P1a kit stays untouched; `FilterChips` skips unchanged chip sets and restores keyboard focus after FilterBar rebuilds its row.
- Saved views: "Save current view…" prompts for a name, one "Load: <name>" item per view, "Delete view…" prompts with the list; the submenu is disabled in the live view, mirroring the old hidden toolbar. `saveNamed` refreshes the menu.
- Window and exact-link actions (Timeline "Widen window ±30 s", "Clear exact visit link"), Loot drill-down actions and Cohort inputs stay in the view: they act on or define what is displayed. The exact visit and drill-downs also appear as chips ("Exact visit", "Exact variant/run").
- Logging has no Analyst-only tabs (cross-linked tabs on an Advanced page; see Task 9).
- Persisted tab state keeps its formats: indices are canonical (default order) or existing string IDs; `StatisticsLiveState.tabs` stays index-based because the saved order is restored before it.
- Column kinds set widths everywhere; KitTables renderers only on archive `String` columns of text kinds. Headers and existing minimums are floors; Loot keeps its content fit (≤ 320 px) above the kind floor.
- Guidance text "try Reset filters"/"Reset filters or change scope" became "use Clear to reset filters"/"Clear filters or change scope" because the button no longer exists in archives; no honesty wording changed.

**Deferred (with reasons)**

- Per-render tab sets: `loot-archive-tabs` (LootArchiveClient, rebuilt per query; P6 replaces it with a view selector), `keypop-archive-tabs` (one tab per mode, rebuilt per query), `saved-resource-tabs` (ActivityArchiveClient view, rebuilt per render; its tab is stored by name in `ViewState.tab`), `historical-statistics-tabs` (legacy loaded page).
- `LootDashboard` `loot-views` (9 index-coupled tabs; spec §6.4/P6 replaces them with one view selector), `LootGUI` views, `FameTablePanel` views and `DungeonStats` `dungeon-views` (nested inside Statistics, which P5 dissolves).
- `NotificationsGUI` tabs (P1b moves Notifications into Settings in parallel; titles come from sound groups), `ChatFilterPanel` lists (dialog internals), `FameSessionViewer` (standalone per-session window).
- Column tools (Copy selected, Details…, Column preset, Columns…, Reset columns) moving into ⋯: needs a view-to-overflow contribution API; they are table tools, not filter controls, so S6 is unaffected.
- `TIME_RELATIVE` as the Simple-mode default and hiding Analyst-only (`ID`) columns: P1a `KitTables` has no mode-aware API; archive time columns keep exact, zone-labelled timestamps.
- Remaining filter walls not in P1c's scope list, so S6 is not yet met on these pages: Logging (controls/search/facet/chip rows), Bridge review (search + five choices + Reset filters), Encounter library and DPS meter filters, Statistics › Fame table and Dungeon stats search rows. Proposed for P6 ("Advanced pages restyle") or a follow-up P1c.1 using the same `FilterBar` + `FilterChips` pattern.

**Risks**

- Tests that interact with drawer controls need the drawer open (done with `ArchiveNativeSupport.drawer`, which also disables the 100 ms animation for that call); new tests restore every persisted drawer/tab/mode preference.
- `QuestConsistencyTest` and `ShellHookIntegrationTest` may also be edited by P0/P1b; the edits here anchor on lines those plans do not change, but a rebase may still need a manual merge.
- Kind widths are narrower than a few old fixed widths (e.g. Inspect party "Character mode", Loot IDs): full values stay in tooltips/details and header text is never cut; the Task 12 screenshots are the check.
- `CustomizableTabs` binds a `DisplayModeModel` listener per group; panels that are never displayed keep theirs (P1a behaviour), which only costs a rebuild on mode switches.
- `FilterBar.setActive` rebuilds its row; `FilterChips` restores focus, but a chip's own remove button necessarily disappears when clicked.

