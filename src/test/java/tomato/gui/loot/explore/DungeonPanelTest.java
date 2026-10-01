package tomato.gui.loot.explore;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.DungeonStatsTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

public class DungeonPanelTest {
    @Test public void sameDungeonRefreshKeepsContentThenAppliesChangesAndEqualResultsKeepTheButtons() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        AtomicReference<List<LootFacts.Bag>> saved = new AtomicReference<>(List.of(drop(100, "s", "v", true, ut(1, 4))));
        DungeonPanel panel = edt(() -> new DungeonPanel(cancel -> {
            assertFalse("Catalog reads run off EDT", SwingUtilities.isEventDispatchThread());
            return saved.get();
        }, tasks::add));
        List<Integer> opened = new ArrayList<>();
        AtomicReference<Component> body = new AtomicReference<>();
        AtomicReference<AbstractButton> button = new AtomicReference<>();
        try {
            edt(() -> { panel.onOpenItem(opened::add); panel.showDungeon(DUNGEON); return null; });
            tasks.remove(0).run();
            edt(() -> {
                body.set(((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER));
                button.set(named(panel, "loot-dungeon-item-1", AbstractButton.class));
                panel.showDungeon(DUNGEON);
                assertSame("The shown content stays while refreshing", body.get(), ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER));
                assertSame(button.get(), named(panel, "loot-dungeon-item-1", AbstractButton.class));
                assertFalse("Loading text is not reattached", SwingUtilities.isDescendingFrom(panel.status(), panel));
                assertEquals(1, panel.model().uts());
                return null;
            });
            saved.set(List.of(drop(100, "s", "v", true, ut(1, 4)), drop(200, "s", "v", true, ut(1, 0))));
            tasks.remove(0).run();
            edt(() -> {
                assertEquals("The refreshed result applies", 2, panel.model().uts());
                assertEquals("×2", named(panel, "loot-dungeon-item-1", AbstractButton.class).getText());
                assertNotSame(body.get(), ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER));
                body.set(((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER));
                button.set(named(panel, "loot-dungeon-item-1", AbstractButton.class));
                panel.showDungeon(DUNGEON);
                return null;
            });
            tasks.remove(0).run();
            edt(() -> {
                assertSame("An equal result does not rebuild the content", body.get(), ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER));
                assertSame("The existing focusable button survives", button.get(), named(panel, "loot-dungeon-item-1", AbstractButton.class));
                press(button.get(), "ENTER");
                assertEquals(List.of(1), opened);
                return null;
            });
        } finally { edt(() -> { panel.close(); return null; }); }
    }

    @Test public void loadingEmptyFailureAndUnknownAreExplicitAndUnknownDoesNotRead() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        AtomicReference<IOException> fail = new AtomicReference<>();
        DungeonPanel panel = edt(() -> new DungeonPanel(cancel -> {
            assertFalse("Catalog reads run off EDT", SwingUtilities.isEventDispatchThread());
            if (fail.get() != null) throw fail.get();
            return List.of();
        }, tasks::add));
        try {
            edt(() -> {
                panel.showDungeon(null);
                assertFalse(panel.isVisible());
                assertEquals(0, panel.getComponentCount());
                assertTrue(tasks.isEmpty());
                panel.showDungeon(DUNGEON);
                assertEquals(DungeonPanel.LOADING, panel.status().getText());
                return null;
            });
            tasks.remove(0).run();
            edt(() -> {
                assertEquals(DungeonPanel.EMPTY, panel.status().getText());
                assertEquals(0, panel.model().bags());
                fail.set(new IOException("synthetic failure"));
                panel.showDungeon(DUNGEON);
                return null;
            });
            tasks.remove(0).run();
            edt(() -> {
                assertEquals("This dungeon's loot could not be read: synthetic failure", panel.status().getText());
                assertNull(panel.model());
                panel.showDungeon(LootFacts.UNRECOGNIZED);
                assertFalse(panel.isVisible());
                assertEquals(0, panel.getComponentCount());
                assertTrue(tasks.isEmpty());
                return null;
            });
        } finally { edt(() -> { panel.close(); return null; }); }
    }

    @Test public void newestReadWinsAndItemsOpenByClickEnterAndSpace() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        List<Integer> opened = new ArrayList<>();
        DungeonPanel panel = edt(() -> new DungeonPanel(cancel -> List.of(drop(100, "s", "v", true, ut(1, 4))), tasks::add));
        try {
            edt(() -> {
                panel.onOpenItem(opened::add);
                panel.showDungeon("Other dungeon");
                panel.showDungeon(DUNGEON);
                return null;
            });
            tasks.get(1).run();
            edt(() -> null);
            tasks.get(0).run(); // deliberately ignores cancellation
            edt(() -> {
                assertEquals(DUNGEON, panel.model().dungeon());
                assertEquals(1, panel.model().uts());
                AbstractButton button = named(panel, "loot-dungeon-item-1", AbstractButton.class);
                button.doClick();
                press(button, "ENTER");
                press(button, "SPACE");
                assertEquals(List.of(1, 1, 1), opened);
                panel.showDungeon("Other dungeon");
                panel.showDungeon(null);
                return null;
            });
            tasks.get(2).run();
            edt(() -> {
                assertFalse(panel.isVisible());
                assertNull(panel.model());
                assertEquals(0, panel.getComponentCount());
                return null;
            });
        } finally { edt(() -> { panel.close(); return null; }); }
    }

    private static void press(JComponent target, String key) {
        Object action = target.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
        assertNotNull(action);
        target.getActionMap().get(action).actionPerformed(new ActionEvent(target, ActionEvent.ACTION_PERFORMED, null));
    }
}
