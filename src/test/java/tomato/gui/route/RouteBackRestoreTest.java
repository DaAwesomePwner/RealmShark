package tomato.gui.route;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.history.link.VisitRef;
import util.PreferencesStore;

import javax.swing.*;
import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** Routed open and Back over a real typed workspace: atomic restore, one load, stale reads inert. */
public class RouteBackRestoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void backRestoresOriginScopeQueryPageSelectionAndScrollDespiteAStaleRead() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
        String id = session(root, 23);
        PreferencesStore preferences = new PreferencesStore(temp.getRoot().toPath().resolve("route.properties")); preferences.preload();
        ViewStateStore states = ViewStateStore.preferences(preferences);
        SessionStore store = new SessionStore(root, false, "test");
        Client client = new Client(id, scratch);
        ArchiveWorkspace<Event, Facets, Sort> workspace = edt(() -> SessionPanel.queried(store, "route", new JLabel("live"), client, states));
        try {
            ShellNavigatorTest.Pages pages = new ShellNavigatorTest.Pages();
            ShellNavigator navigator = edt(() -> pages.navigator(5));
            ArchiveRouteTarget<Event, Facets, Sort> target = new ArchiveRouteTarget<>(Destination.RUNS, workspace);
            edt(() -> { navigator.register(target); pages.selected = 10; workspace.changeQuery(query(id).withFacets(new Facets(3))); return null; });
            await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.displayedPage().matches == 20);
            edt(() -> { workspace.selectPage(2); return null; });
            await(() -> !workspace.loading() && workspace.displayedPage().page == 2);
            ArchiveRow.Ref ref = edt(() -> workspace.displayedPage().rows.get(1).ref);
            edt(() -> { client.binding.viewChanged(workspace.state().withPosition("events", Collections.singletonList(ref), ref, 4)); return null; });
            ViewState<Facets, Sort> origin = edt(workspace::state);
            assertEquals(2, origin.page); assertEquals(ref, origin.anchor);

            // Unsupported references and foreign query types are rejected by the generic target.
            assertFalse(edt(() -> navigator.canOpen(Route.to(Destination.RUNS).withVisit(new VisitRef(id, "journal:1")))));
            assertFalse(edt(() -> navigator.canOpen(Route.to(Destination.RUNS).withQuery(ArchiveQuery.of(id, "text", String.class, Destination.RUNS)))));

            client.block = new CountDownLatch(1);
            int adaptersBefore = client.adapters;
            assertTrue(edt(() -> navigator.open(Route.to(Destination.RUNS).withQuery(query(id).withFacets(new Facets(10))))));
            assertTrue("The destination read is in flight", client.entered.await(10, TimeUnit.SECONDS));
            assertEquals(adaptersBefore + 1, client.adapters);
            assertEquals(10, edt(() -> workspace.state().query.facets().minimum).intValue());
            int rendersBefore = edt(() -> client.renders.size());

            assertTrue(edt(navigator::back));
            client.block.countDown();
            await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.displayedPage().page == 2
                && workspace.state().query.facets().minimum == 3);
            Thread.sleep(200); // Give any stale completion a chance to (incorrectly) apply.
            edt(() -> {
                assertEquals("Back applies the origin exactly once", Collections.singletonList("3@2"), client.renders.subList(rendersBefore, client.renders.size()));
                assertFalse("The stale destination read never rendered", client.renders.contains("10@0"));
                assertEquals(origin.toJson(), workspace.state().toJson());
                assertEquals(ref, workspace.state().anchor); assertEquals(4, workspace.state().anchorOffset);
                assertEquals(Arrays.asList(13, 14, 15, 16, 17), values(workspace.displayedPage()));
                assertEquals(1, client.table.getSelectedRow());
                assertEquals(10, pages.selected);
                assertFalse(navigator.canGoBack());
                return null;
            });
            assertEquals("Restoring the origin reused its pinned result: no extra query revision", adaptersBefore + 1, client.adapters);

            // A state from a different workspace type is rejected without changing anything.
            @SuppressWarnings({"unchecked", "rawtypes"}) ViewState<Facets, Sort> foreign = (ViewState) ViewState.initial(ArchiveQuery.of(id, "text", String.class, Destination.RUNS));
            edt(() -> {
                try { workspace.restore(foreign); fail(); } catch (IllegalArgumentException | com.google.gson.JsonParseException expected) {}
                assertEquals(origin.toJson(), workspace.state().toJson());
                return null;
            });
        } finally {
            if (client.block != null) client.block.countDown();
            edt(() -> { workspace.close(); return null; });
            store.close(); preferences.shutdown(5, TimeUnit.SECONDS, message -> {});
        }
    }

    private static List<Integer> values(ArchivePage<Event> page) {
        List<Integer> values = new ArrayList<>(); for (ArchiveRow<Event> row : page.rows) values.add(row.value.value); return values;
    }

    private static final class Client implements ArchiveClient<Event, Facets, Sort> {
        final String scope; final Path scratch;
        final List<String> renders = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch block;
        volatile int adapters;
        JTable table; Binding<Facets, Sort> binding;
        Client(String scope, Path scratch) { this.scope = scope; this.scratch = scratch; }
        public ArchiveQuery<Facets, Sort> initialQuery() { return query(scope); }
        public Path scratchDirectory() { return scratch; }
        public int pageSize() { return 5; }
        public ArchiveAdapter<Event, Facets, Sort> adapter(ArchiveQuery<Facets, Sort> query) {
            adapters++;
            ArchiveAdapter<Event, Facets, Sort> delegate = ArchiveFixtures.adapter();
            return new ArchiveAdapter<Event, Facets, Sort>() {
                public Class<Event> rowType() { return delegate.rowType(); }
                public String unit() { return delegate.unit(); }
                public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<Facets, Sort> q) { return delegate.sources(store, q); }
                public void scan(ReadSnapshot pin, ArchiveQuery<Facets, Sort> q, Sink<Event> rows, Cancellation cancel) throws IOException {
                    CountDownLatch gate = block;
                    if (gate != null && q.facets().minimum == 10) {
                        entered.countDown();
                        try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    delegate.scan(pin, q, rows, cancel);
                }
                public boolean matches(ArchiveRow<Event> row, ArchiveQuery<Facets, Sort> q) { return delegate.matches(row, q); }
                public Long time(ArchiveRow<Event> row) { return delegate.time(row); }
                public Comparator<Event> comparator(Sort field) { return delegate.comparator(field); }
            };
        }
        public JComponent render(ArchivePage<Event> page, ViewState<Facets, Sort> state, Binding<Facets, Sort> binding) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            this.binding = binding; renders.add(state.query.facets().minimum + "@" + page.page);
            List<HistoryTables.Column<Event, ?>> columns = Collections.singletonList(new HistoryTables.Column<>("value", "Value", Integer.class, e -> e.value, null));
            table = HistoryTables.queried("route-events", columns, page, Collections.singletonMap("value", Sort.VALUE), state.query, binding::queryChanged, row -> {});
            JScrollPane scroll = new JScrollPane(table); JPanel panel = new JPanel(new BorderLayout()); panel.add(scroll);
            HistoryTables.restorePosition(table, scroll, page, state);
            return panel;
        }
    }

    private static <T> T edt(ShellNavigatorTest.Checked<T> body) throws Exception { return ShellNavigatorTest.edt(body); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for EDT state");
    }
}
