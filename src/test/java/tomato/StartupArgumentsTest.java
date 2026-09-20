package tomato;

import assets.AssetExtractor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StartupArgumentsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void firstValidPathIsAppliedAndAnnouncedOnce() throws Exception {
        String first = temp.newFile("first resources.assets").getAbsolutePath();
        String second = temp.newFile("second.assets").getAbsolutePath();
        assertParse(first, "Using custom resources.assets path: " + first + System.lineSeparator(), "",
            "--unknown", "--path", first, "--path", second);
    }

    @Test public void invalidFirstPathDoesNotFallThroughToALaterValidPath() throws Exception {
        String valid = temp.newFile("valid.assets").getAbsolutePath();
        for (String invalid : new String[]{new File(temp.getRoot(), "missing.assets").getAbsolutePath(),
                temp.newFolder("directory.assets").getAbsolutePath()}) {
            assertParse("unchanged", "", "Invalid path provided: " + invalid + System.lineSeparator()
                + "Falling back to default paths." + System.lineSeparator(), "--path", invalid, "--path", valid);
        }
    }

    @Test public void absentAndTrailingPathArgumentsLeaveTheConfiguredPathAlone() throws Exception {
        assertParse("unchanged", "", "");
        assertParse("unchanged", "", "", "--unknown", "--path");
    }

    @Test public void helpAndShortHelpExitBeforeApplyingAPath() throws Exception {
        String path = temp.newFile("help.assets").getAbsolutePath();
        for (String help : new String[]{"--help", "-h"}) {
            String output = probe("help", "--path", path, help);
            assertTrue(output, output.contains("Usage: java -jar RealmShark-"));
            assertFalse(output, output.contains("Using custom resources.assets path:"));
            assertFalse(output, output.contains("STARTUP_RETURNED"));
        }
    }

    @Test public void previewReturnsBeforeHelpOrPathHandling() throws Exception {
        String output = probe("preview", "--help", "--path", temp.newFile("preview.assets").getAbsolutePath(), "--preview");
        assertTrue(output, output.contains("STARTUP_RETURNED preview=true path=unchanged"));
        assertFalse(output, output.contains("Usage: java -jar"));
        assertFalse(output, output.contains("Using custom resources.assets path:"));
        assertFalse(output, output.contains("Java Version:"));
    }

    private static void assertParse(String expectedPath, String expectedOut, String expectedError, String... args) throws Exception {
        Field path = assetPath();
        Object originalPath = path.get(null);
        PrintStream originalOut = System.out, originalError = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream(), error = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(out, true, "UTF-8");
             PrintStream stderr = new PrintStream(error, true, "UTF-8")) {
            path.set(null, "unchanged");
            System.setOut(stdout); System.setErr(stderr);
            Method parse = Tomato.class.getDeclaredMethod("parseArgs", String[].class);
            parse.setAccessible(true);
            parse.invoke(null, (Object)args);
            assertEquals(expectedPath, path.get(null));
        } finally {
            System.setOut(originalOut); System.setErr(originalError); path.set(null, originalPath);
        }
        assertEquals(expectedOut, out.toString("UTF-8"));
        assertEquals(expectedError, error.toString("UTF-8"));
    }

    private String probe(String mode, String... args) throws Exception {
        File directory = temp.newFolder();
        File output = new File(directory, "startup.txt");
        List<String> command = new ArrayList<>(Arrays.asList(
            new File(System.getProperty("java.home"), "bin/" + (File.separatorChar == '\\' ? "java.exe" : "java")).getAbsolutePath(),
            "-Djava.awt.headless=true", "-cp", testClasspath(), StartupProbe.class.getName(), mode));
        command.addAll(Arrays.asList(args));
        Process child = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(output).start();
        try {
            boolean exited = child.waitFor(15, TimeUnit.SECONDS);
            if (!exited) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            String transcript = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertTrue("Startup probe did not exit\n" + transcript, exited);
            assertEquals(transcript, 0, child.exitValue());
            return transcript;
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private static String testClasspath() throws Exception {
        // Gradle's java.class.path can contain only its bootstrap JAR; include the test loader URLs.
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            entries.add(new File(entry).getAbsolutePath());
        for (ClassLoader loader = StartupArgumentsTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader) for (URL url : ((URLClassLoader)loader).getURLs())
                if ("file".equals(url.getProtocol())) entries.add(new File(url.toURI()).getAbsolutePath());
        }
        for (Class<?> type : new Class<?>[]{StartupArgumentsTest.class, Tomato.class})
            entries.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath());
        return String.join(File.pathSeparator, entries);
    }

    private static Field assetPath() throws Exception {
        Field path = AssetExtractor.class.getDeclaredField("REALM_RES_PATH");
        path.setAccessible(true);
        return path;
    }

    public static final class StartupProbe {
        public static void main(String[] args) throws Exception {
            assetPath().set(null, "unchanged");
            if ("preview".equals(args[0])) {
                CountDownLatch entered = new CountDownLatch(1), hold = new CountDownLatch(1);
                SwingUtilities.invokeLater(() -> {
                    entered.countDown();
                    try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("EDT blocker did not start");
            }
            // The preview UI stays queued behind the blocker. No window, capture, or asset extraction runs.
            Tomato.main(Arrays.copyOfRange(args, 1, args.length));
            System.out.println("STARTUP_RETURNED preview=" + Tomato.isPreview() + " path=" + assetPath().get(null));
            System.exit(0);
        }
    }
}
