package tomato;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.DungeonStatData;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import static org.junit.Assert.*;

public class StartupInitializationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void malformedAndEmptyHistoryStillOpenUiAndKeepWritesSuspendedUntilRecovery() throws Exception {
        for (String contents : new String[]{"", "{broken"}) {
            Path file = temp.newFile().toPath();
            Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
            DungeonStatData history = new DungeonStatData(file);
            openThroughStartup(history, false);

            Entity mob = new Entity(null, 1, 0); mob.objectType=100;
            history.updateEntityDamage("Shared", mob);
            history.updateDungeon("Shared", 500);
            assertEquals(contents, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            assertEquals(1, history.data.get("Shared").getEnteredDungeon());

            Files.write(file, savedHistory().getBytes(StandardCharsets.UTF_8));
            history.load();
            history.updateEntityDamage("Shared", mob);
            history.updateDungeon("Shared", 250);
            DungeonStatData reopened = new DungeonStatData(file); reopened.load();
            assertEquals(5, reopened.data.get("Shared").getEnteredDungeon());
            assertEquals(1750, reopened.data.get("Shared").getTotalTime());
        }
    }

    @Test public void unreadableHistoryStillOpensUiAndSuccessfulHistoryIsLoadedBeforeUi() throws Exception {
        // A directory cannot be read as a history file on supported platforms.
        Path unreadable = temp.newFolder().toPath();
        DungeonStatData blocked = new DungeonStatData(unreadable);
        openThroughStartup(blocked, false);
        Entity mob = new Entity(null, 1, 0); mob.objectType=100;
        blocked.updateEntityDamage("Shared", mob); blocked.updateDungeon("Shared", 100);
        assertTrue(Files.isDirectory(unreadable));

        Path valid = temp.newFile().toPath();
        Files.write(valid, savedHistory().getBytes(StandardCharsets.UTF_8));
        openThroughStartup(new DungeonStatData(valid), true);
    }

    private static void openThroughStartup(DungeonStatData history, boolean expectHistory) throws Exception {
        List<String> stages = new ArrayList<>();
        TomatoData data = new TomatoData() {
            @Override public void bootload() {
                assertFalse(SwingUtilities.isEventDispatchThread());
                stages.add("history");
                history.load();
            }
            @Override public void loadPropList(String name) { stages.add(name); }
        };
        AtomicReference<JPanel> shell = new AtomicReference<>();
        // This is the production startup helper. Only the window factory is replaced,
        // so it exercises EDT UI creation without auto-starting capture or HTTP requests.
        Tomato.initializeAndOpen(data, () -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            assertEquals(Arrays.asList("history", "chatPingMessages", "entityIdPings", "itemPings"), stages);
            assertEquals(expectHistory ? 1 : 0, history.snapshot().size());
            if (expectHistory) assertEquals(3, history.data.get("Shared").getEnteredDungeon());
            JPanel panel = new JPanel(); panel.add(new JLabel("Shell ready")); shell.set(panel);
        });
        assertNotNull(shell.get());
        assertEquals("Shell ready", ((JLabel)shell.get().getComponent(0)).getText());
    }

    private static String savedHistory() {
        return "{\"data\":{\"Shared\":{\"name\":\"Shared\",\"enteredDungeon\":3,\"totalTime\":1000,"
            + "\"entityDamaged\":{},\"entityLoot\":{}}}}";
    }
}
