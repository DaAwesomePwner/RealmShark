package realmshark.branding;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Permission;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class WindowsAppIdentityExitTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void exitOnCloseClearsAllLiveShellPropertiesWithoutWaitingForTheEdt() throws Exception {
        assertExitCleanup("window-close");
    }

    @Test public void systemExitFromAButtonClearsAllLiveShellPropertiesWithoutDisposing() throws Exception {
        assertExitCleanup("button-exit");
    }

    @Test public void workerExitClearsPropertiesWhileTheEdtIsBlockedHoldingTheTreeLock() throws Exception {
        assertExitCleanup("worker-exit");
    }

    private void assertExitCleanup(String mode) throws Exception {
        Assume.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        File launcher = temporary.newFile("RealmShark.exe");
        File icon = temporary.newFile("RealmShark.ico");
        Files.write(icon.toPath(), BrandingAssetsTest.resource("realmshark.ico"));
        File output = temporary.newFile("exit-probe.txt");
        // Gradle's test worker java.class.path may contain only its bootstrap JAR.
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> type : new Class<?>[]{WindowsAppIdentityExitTest.class, AppIdentity.class,
                Native.class, org.junit.Assert.class, org.hamcrest.Matcher.class}) {
            entries.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath());
        }
        Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath(),
                "-Djava.awt.headless=false", "-Djava.security.manager=allow", "-cp", String.join(File.pathSeparator, entries),
                ExitProbe.class.getName(), mode, launcher.getAbsolutePath(), icon.getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(output).start();
        try {
            boolean exited = child.waitFor(15, TimeUnit.SECONDS);
            if (!exited) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            String transcript = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertTrue("Shutdown must complete even with System.exit blocking the EDT\n" + transcript, exited);
            assertEquals(transcript, 0, child.exitValue());
            assertTrue(transcript, transcript.contains("CLEARED_LIVE_WINDOWS=2 " + mode));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** A real JVM shutdown; the exit observer verifies cleanup without invoking it. */
    public static final class ExitProbe {
        public static void main(String[] args) throws Exception {
            System.setProperty("realmshark.launcher", args[1]);
            System.setProperty("realmshark.icon", args[2]);
            AppIdentity.initialize();
            JFrame[] frames = new JFrame[2];
            Pointer[] handles = new Pointer[frames.length];
            JButton exit = new JButton("Exit");
            exit.addActionListener(event -> AppIdentity.exit(0));
            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < frames.length; i++) {
                    JFrame frame = new JFrame("RealmShark exit probe");
                    frames[i] = frame;
                    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    if (i == 0) frame.add(exit);
                    AppIdentity.apply(frame);
                    frame.setSize(100, 80);
                    frame.setLocation(-10000, -10000);
                    frame.setVisible(true);
                    handles[i] = Native.getWindowPointer(frame);
                    assertEquals("RealmShark.Desktop", WindowsAppIdentityTest.readProperty(handles[i], 5));
                    for (int id = 2; id <= 4; id++) assertNotNull(WindowsAppIdentityTest.readProperty(handles[i], id));
                }
            });
            // JDK 17 packaging-test fixture only: checkExit observes the actual System.exit boundary
            // before ToolkitShutdown can destroy HWNDs. It allows exit and imposes no permissions.
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkExit(int status) {
                    for (Pointer hwnd : handles) {
                        assertTrue("Cleanup must precede native destruction", WindowsAppIdentity.isOwnWindow(hwnd));
                        assertFalse("Shutdown must prevent late property writes", WindowsAppIdentity.setWindowProperties(hwnd, null));
                        for (int id = 2; id <= 5; id++) assertNull(WindowsAppIdentityTest.readProperty(hwnd, id));
                    }
                    System.out.println("CLEARED_LIVE_WINDOWS=2 " + args[0]);
                }
            });
            SwingUtilities.invokeLater(() -> {
                // Also prove the native cleanup does not need the AWT tree lock held by an exiting UI.
                synchronized (frames[0].getTreeLock()) {
                    if ("window-close".equals(args[0])) {
                        frames[0].dispatchEvent(new WindowEvent(frames[0], WindowEvent.WINDOW_CLOSING));
                    } else if ("worker-exit".equals(args[0])) {
                        Thread worker = new Thread(() -> AppIdentity.exit(0), "RealmShark-exiting-worker");
                        worker.start();
                        try { worker.join(); }
                        catch (InterruptedException ex) { throw new AssertionError(ex); }
                    } else {
                        exit.doClick();
                    }
                }
            });
        }
    }
}
