package tomato.gui.history;

import com.google.gson.JsonObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** UX-04: an export uses exactly the query and pinned revision the view displays, never a pending one. */
public class ExportScopeAgreementTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void exportsAreRefusedWhileARestoreIsPendingAndThenMatchTheDisplayedRevision() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
        String id = session(root, 23);
        PreferencesStore preferences = new PreferencesStore(temp.getRoot().toPath().resolve("export.properties")); preferences.preload();
        SessionStore store = new SessionStore(root, false, "test");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Client client = new Client(id, scratch, entered, release);
        ArchiveWorkspace<Event, Facets, Sort> workspace = edt(() -> SessionPanel.queried(store, "export-scope", new JLabel("live"), client, ViewStateStore.preferences(preferences)));
        try {
            edt(() -> { workspace.showSaved(); return null; });
            await(() -> !workspace.loading() && workspace.displayedPage() != null);
            ViewState<Facets, Sort> pending = edt(() -> workspace.state().withQuery(query(id).withFacets(new Facets(10))));
            edt(() -> { workspace.restore(pending); return null; });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            edt(() -> {
                try { workspace.exportTo(temp.getRoot().toPath(), "pending", ExportSelection.all(), ArchiveExport.Format.JSON); fail(); }
                catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("displayed query revision")); }
                try { workspace.prepareExport(ExportSelection.all(), (lease, text) -> false); fail(); }
                catch (IllegalStateException expected) { }
                return null;
            });
            release.countDown();
            await(() -> !workspace.loading() && workspace.displayedPage().matches == 13);
            AtomicReference<JsonObject> manifest = new AtomicReference<>();
            CountDownLatch confirmed = new CountDownLatch(1);
            edt(() -> workspace.prepareExport(ExportSelection.all(), (lease, text) -> { manifest.set(lease.manifest()); confirmed.countDown(); return false; }));
            assertTrue(confirmed.await(10, TimeUnit.SECONDS));
            edt(() -> {
                assertEquals("The export revision is the displayed pin", workspace.displayedPage().revision, manifest.get().get("revision").getAsString());
                assertEquals("The export query is the displayed query", workspace.state().query.toJson(), manifest.get().getAsJsonObject("query"));
                assertEquals(13, manifest.get().get("matchingCount").getAsLong());
                return null;
            });
        } finally {
            release.countDown();
            edt(() -> { workspace.close(); return null; });
            store.close(); preferences.shutdown(5, TimeUnit.SECONDS, message -> {});
        }
    }

    private static final class Client implements ArchiveClient<Event, Facets, Sort> {
        final String scope; final Path scratch; final CountDownLatch entered, release;
        Client(String scope, Path scratch, CountDownLatch entered, CountDownLatch release) { this.scope = scope; this.scratch = scratch; this.entered = entered; this.release = release; }
        public ArchiveQuery<Facets, Sort> initialQuery() { return query(scope); }
        public Path scratchDirectory() { return scratch; }
        public int pageSize() { return 5; }
        public ArchiveAdapter<Event, Facets, Sort> adapter(ArchiveQuery<Facets, Sort> query) {
            ArchiveAdapter<Event, Facets, Sort> delegate = ArchiveFixtures.adapter();
            return new ArchiveAdapter<Event, Facets, Sort>() {
                public Class<Event> rowType() { return delegate.rowType(); }
                public String unit() { return delegate.unit(); }
                public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<Facets, Sort> q) { return delegate.sources(store, q); }
                public void scan(ReadSnapshot pin, ArchiveQuery<Facets, Sort> q, Sink<Event> rows, Cancellation cancel) throws IOException {
                    if (q.facets().minimum == 10) {
                        entered.countDown();
                        try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    delegate.scan(pin, q, rows, cancel);
                }
                public boolean matches(ArchiveRow<Event> row, ArchiveQuery<Facets, Sort> q) { return delegate.matches(row, q); }
                public Long time(ArchiveRow<Event> row) { return delegate.time(row); }
                public Comparator<Event> comparator(Sort field) { return delegate.comparator(field); }
            };
        }
        public JComponent render(ArchivePage<Event> page, ViewState<Facets, Sort> state, Binding<Facets, Sort> binding) {
            return new JLabel(page.matches + " matches");
        }
    }

    private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(body.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for EDT state");
    }
}
