package tomato.gui.chat;

import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.ViewState;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.OverflowMenu;
import tomato.history.archive.ArchiveQuery;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** The named live views as a host ⋯ section (for Chat's and Key-pops' live bars); in-memory view-state storage only. */
public class SocialQueryControlsTest {
    @Test public void liveViewItemsListLoadDeleteAndResetNamedLiveViewsInAHostSection() throws Exception {
        Map<String, String> values = new ConcurrentHashMap<>();
        ViewStateStore store = new ViewStateStore(new ViewStateStore.Storage() {
            public String get(String key) { return values.get(key); }
            public CompletionStage<PreferencesStore.SaveResult> put(String key, String value) {
                values.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(values.size()));
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            ViewState<Facets, Sort> defaults = ViewState.initial(query(ArchiveQuery.CURRENT));
            AtomicReference<ViewState<Facets, Sort>> current = new AtomicReference<>(defaults.withQuery(defaults.query.withText("needle")));
            List<String> statuses = new ArrayList<>();
            store.saveNamed("keypops-live-views", "Needle", current.get());
            OverflowMenu more = new OverflowMenu("keypops-live-more");
            more.add("Export events…", () -> {});
            OverflowMenu.Section section = SocialQueryControls.liveViewItems(more, "keypops-live-views", store, current::get, current::set, defaults, statuses::add);
            assertEquals(SocialQueryControls.LIVE_VIEWS, section.id());
            assertSame(section, more.section(SocialQueryControls.LIVE_VIEWS));
            JMenu views = (JMenu) more.item("Saved views");
            assertNotNull("⋯ Saved views ▸", views);
            assertEquals("keypops-live-views-saved-views", views.getName());
            assertEquals(1, section.items().size());
            assertNotNull(more.item("Save current view…"));
            assertNotNull(more.item("Load: Needle"));
            assertTrue(more.item("Delete view…").isEnabled());
            current.set(defaults);
            more.item("Load: Needle").doClick();
            assertEquals("Loading applies the named view", "needle", current.get().query.text());
            store.saveNamed("keypops-live-views", "Second", defaults);
            views.setSelected(true);   // opening the submenu relists the names
            views.setSelected(false);
            assertNotNull(more.item("Load: Second"));
            more.item("Reset saved state").doClick();
            assertEquals("Reset restores the defaults", "", current.get().query.text());
            assertTrue(store.names("keypops-live-views").isEmpty());
            assertNull("…and relists", more.item("Load: Needle"));
            assertFalse(more.item("Delete view…").isEnabled());
            // A second call replaces the section instead of adding another submenu.
            SocialQueryControls.liveViewItems(more, "keypops-live-views", store, current::get, current::set, defaults, statuses::add);
            int submenus = 0;
            for (java.awt.Component child : more.menu().getComponents()) if (child instanceof JMenu && "Saved views".equals(((JMenu) child).getText())) submenus++;
            assertEquals(1, submenus);
            // An unreadable document is reported, never thrown at the menu.
            values.put("ux.archive.keypops-live-views", "{not json");
            views = (JMenu) more.item("Saved views");
            views.setSelected(true);
            views.setSelected(false);
            assertFalse(statuses.isEmpty());
            assertTrue(statuses.get(statuses.size() - 1), statuses.get(statuses.size() - 1).contains("Reset saved state"));
        });
    }
}
