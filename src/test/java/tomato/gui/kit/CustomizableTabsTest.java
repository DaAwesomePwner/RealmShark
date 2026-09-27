package tomato.gui.kit;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CustomizableTabsTest {
    @Test public void analystModeCannotHideTheLastSimpleTabAndSavedHiddenStateRecovers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            mode.set(DisplayModeModel.Mode.ANALYST);
            CustomizableTabs tabs = tabs();
            assertTrue(tabs.hide("overview")); assertTrue(tabs.hide("gear"));
            assertFalse(tabs.hide("exalts"));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(Collections.singletonList("exalts"), tabs.visibleIds());
            store.put("ui.tabs.character", "overview,gear,exalts,evidence|overview,gear,exalts");
            CustomizableTabs recovered = tabs();
            assertFalse(recovered.visibleIds().isEmpty());
            assertTrue("Recovery does not rewrite saved preferences", store.get("ui.tabs.character").endsWith("|overview,gear,exalts"));
        });
    }

    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);

    private CustomizableTabs tabs() {
        return new CustomizableTabs("character", mode, store::get, store::put)
            .add("overview", "Overview", new JPanel())
            .add("gear", "Gear", new JPanel())
            .add("exalts", "Exalts", new JPanel())
            .addAnalyst("evidence", "Snapshot evidence", new JPanel());
    }

    @Test public void analystOnlyTabsAppearOnlyInAnalystMode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals(3, tabs.component().getTabCount());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(Arrays.asList("overview", "gear", "exalts", "evidence"), tabs.visibleIds());
            assertEquals("Snapshot evidence", tabs.component().getTitleAt(3));
        });
    }

    @Test public void orderAndHiddenTabsPersistAndSelectionFollowsTheTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            tabs.select("exalts");
            tabs.move("exalts", -2);
            assertEquals(Arrays.asList("exalts", "overview", "gear"), tabs.visibleIds());
            assertEquals("exalts", tabs.selectedId());
            assertTrue(tabs.hide("gear"));
            assertEquals("exalts,overview,gear,evidence|gear", store.get("ui.tabs.character"));
            CustomizableTabs reopened = tabs();
            assertEquals(Arrays.asList("exalts", "overview"), reopened.visibleIds());
            assertEquals(Collections.singleton("gear"), reopened.hiddenIds());
            reopened.show("gear");
            assertEquals("gear", reopened.selectedId());
            reopened.reset();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), reopened.visibleIds());
        });
    }

    @Test public void theLastVisibleTabCannotBeHiddenAndNewTabsAppend() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            store.put("ui.tabs.character", "gear,overview|");
            CustomizableTabs tabs = tabs();
            assertEquals(Arrays.asList("gear", "overview", "exalts"), tabs.visibleIds());
            assertTrue(tabs.hide("gear"));
            assertTrue(tabs.hide("overview"));
            assertFalse(tabs.hide("exalts"));
            assertEquals(Collections.singletonList("exalts"), tabs.visibleIds());
        });
    }

    @Test public void selectionIsReportedOnceAfterHidingTheSelectedTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            List<String> seen = new ArrayList<>();
            tabs.onSelect(seen::add);
            tabs.select("gear");
            tabs.hide("gear");
            assertEquals(Arrays.asList("gear", "overview"), seen);
        });
    }

    @Test public void invalidIdsAreRejected() {
        try { new CustomizableTabs("Bad Group", mode, store::get, store::put); fail(); } catch (IllegalArgumentException expected) { }
        try { tabs().add("gear", "Duplicate", new JPanel()); fail(); } catch (IllegalArgumentException expected) { }
    }
}
