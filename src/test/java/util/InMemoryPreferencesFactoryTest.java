package util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.quest.QuestGUI;
import static org.junit.Assert.*;

public class InMemoryPreferencesFactoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void gradleInstallsIsolatedUserAndSystemRootsIncludingProductionPackageLookups() {
        assertIsolatedRoots();
        assertTrue(Preferences.userRoot().isUserNode());
        assertFalse(Preferences.systemRoot().isUserNode());
        assertNotSame(Preferences.userRoot(), Preferences.systemRoot());
        assertEquals(InMemoryPreferencesFactory.MemoryPreferences.class,
            Preferences.userNodeForPackage(QuestGUI.class).getClass());
        assertEquals(InMemoryPreferencesFactory.MemoryPreferences.class,
            Preferences.systemNodeForPackage(QuestGUI.class).getClass());
    }

    @Test public void valuesAndSubtreesStaySeparateAndRemovalDoesNotResurrectOldData() throws Exception {
        assertIsolatedRoots();
        String path = "realmshark-fixture-" + UUID.randomUUID();
        Preferences user = Preferences.userRoot().node(path), system = Preferences.systemRoot().node(path);
        try {
            user.put("label", "Synthetic 東京"); system.put("label", "System fixture");
            Preferences child = user.node("quests/current"); child.putBoolean("pinned", true);
            user.flush(); user.sync();
            assertEquals("Synthetic 東京", Preferences.userRoot().node(path).get("label", null));
            assertEquals("System fixture", system.get("label", null));
            assertFalse(system.nodeExists("quests"));
            assertSame(child, user.node("quests/current"));
            assertTrue(child.getBoolean("pinned", false));
            assertArrayEquals(new String[]{"label"}, user.keys());
            assertArrayEquals(new String[]{"quests"}, user.childrenNames());
            assertFalse(new InMemoryPreferencesFactory().userRoot().nodeExists(path));

            user.clear(); assertNull(user.get("label", null));
            assertTrue(child.getBoolean("pinned", false));
            user.node("quests").removeNode();
            assertFalse(user.nodeExists("quests"));
            assertArrayEquals(new String[0], user.childrenNames());
            assertThrows(IllegalStateException.class, () -> child.get("pinned", null));
            Preferences replacement = user.node("quests/current");
            assertNull(replacement.get("pinned", null));
            replacement.put("temporary", "value"); replacement.remove("temporary");
            assertArrayEquals(new String[0], replacement.keys());
        } finally { user.removeNode(); system.removeNode(); }
    }

    @Test public void flushedPreferencesDoNotSurviveAFreshJvm() throws Exception {
        assertIsolatedRoots();
        for (int run = 0; run < 2; run++) {
            File directory = temp.newFolder();
            File output = new File(directory, "preferences.txt");
            String classes = new File(InMemoryPreferencesFactory.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();
            Process child = new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/" + (File.separatorChar == '\\' ? "java.exe" : "java")).getAbsolutePath(),
                "-Djava.util.prefs.PreferencesFactory=" + InMemoryPreferencesFactory.class.getName(),
                "-cp", classes, Probe.class.getName())
                .directory(directory).redirectErrorStream(true).redirectOutput(output).start();
            try {
                assertTrue("Preference probe timed out", child.waitFor(15, TimeUnit.SECONDS));
                String transcript = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
                assertEquals(transcript, 0, child.exitValue());
                assertTrue(transcript, transcript.contains("Fresh in-memory user and system preferences"));
            } finally {
                if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }

    private static void assertIsolatedRoots() {
        // Check the launch property before any access that could select the platform factory.
        assertEquals(InMemoryPreferencesFactory.class.getName(), System.getProperty("java.util.prefs.PreferencesFactory"));
        assertEquals(InMemoryPreferencesFactory.MemoryPreferences.class, Preferences.userRoot().getClass());
        assertEquals(InMemoryPreferencesFactory.MemoryPreferences.class, Preferences.systemRoot().getClass());
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            for (Preferences root : new Preferences[]{Preferences.userRoot(), Preferences.systemRoot()}) {
                if (root.getClass() != InMemoryPreferencesFactory.MemoryPreferences.class)
                    throw new AssertionError("Platform preferences must not be used");
                Preferences node = root.node("realmshark-test-process-isolation");
                if (node.get("sentinel", null) != null) throw new AssertionError("Persisted data leaked into a fresh JVM");
                node.put("sentinel", "synthetic fixture"); node.flush(); node.sync();
                if (!"synthetic fixture".equals(node.get("sentinel", null))) throw new AssertionError("Lost in-memory value");
            }
            System.out.println("Fresh in-memory user and system preferences");
        }
    }
}
