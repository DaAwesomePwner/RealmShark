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
