package realmshark.branding;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class WindowsAppIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void relaunchPathsAreAbsoluteQuotedAndSupportSpacesAndUnicode() throws Exception {
        File directory = temporary.newFolder("RealmShark space \u03a9 & fin");
        File exe = new File(directory, "RealmShark.exe");
        File cmd = new File(directory, "Launch-RealmShark.cmd");
        File icon = new File(directory, "RealmShark.ico");
        assertTrue(exe.createNewFile());
        assertTrue(cmd.createNewFile());
        assertTrue(icon.createNewFile());
        for (File launcher : new File[]{exe, cmd}) {
            WindowsAppIdentity.RelaunchInfo info = WindowsAppIdentity.RelaunchInfo.create(launcher.getAbsolutePath(), icon.getAbsolutePath());
            assertNotNull(info);
            assertEquals('"' + launcher.getAbsolutePath() + '"', info.command);
            assertEquals(icon.getAbsolutePath() + ",0", info.iconResource);
        }
        assertEquals(exe.getAbsolutePath() + ",0", WindowsAppIdentity.RelaunchInfo.create(exe.getAbsolutePath(), null).iconResource);
        assertNull(WindowsAppIdentity.RelaunchInfo.create(cmd.getAbsolutePath(), null));
    }

    @Test public void invalidRelaunchTargetsDoNotPublishBrokenCommands() throws Exception {
        File unsupported = temporary.newFile("RealmShark.txt");
        File exe = temporary.newFile("RealmShark.exe");
        for (String path : new String[]{null, "", "RealmShark.exe", temporary.getRoot().getAbsolutePath(),
                new File(temporary.getRoot(), "missing.exe").getAbsolutePath(), unsupported.getAbsolutePath(),
                '"' + exe.getAbsolutePath() + '"', exe.getAbsolutePath() + " --preview", exe.getAbsolutePath() + "\n"}) {
            assertNull(WindowsAppIdentity.RelaunchInfo.create(path, null));
        }
    }

    @Test public void invalidAndForeignNativeHandlesAreRejected() {
        for (Pointer handle : new Pointer[]{null, Pointer.createConstant(0), Pointer.createConstant(-1), Pointer.createConstant(1)}) {
            assertFalse(WindowsAppIdentity.isOwnWindow(handle));
            assertFalse(WindowsAppIdentity.setWindowProperties(handle, null));
            assertFalse(WindowsAppIdentity.clearWindowProperties(handle));
        }
        Assume.assumeTrue(isWindows());
        Pointer desktop = NativeLibrary.getInstance("user32").getFunction("GetDesktopWindow").invokePointer(new Object[0]);
        assertFalse(WindowsAppIdentity.isOwnWindow(desktop));
        assertFalse(WindowsAppIdentity.setWindowProperties(desktop, null));
    }

    @Test public void nativeShellIdentityRoundTripsAndIsClearedBeforeDisposal() throws Exception {
        Assume.assumeTrue(isWindows());
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        AppIdentity.initialize();
        PointerByReference process = new PointerByReference();
        assertEquals(0, Shell32.INSTANCE.GetCurrentProcessExplicitAppUserModelID(process));
        try { assertEquals("RealmShark.Desktop", process.getValue().getWideString(0)); }
        finally { Ole32.INSTANCE.CoTaskMemFree(process.getValue()); }

        File exe = temporary.newFile("RealmShark.exe");
        File icon = temporary.newFile("RealmShark.ico");
        Files.write(icon.toPath(), BrandingAssetsTest.resource("realmshark.ico"));
        String oldLauncher = System.getProperty("realmshark.launcher"), oldIcon = System.getProperty("realmshark.icon");
        System.setProperty("realmshark.launcher", exe.getAbsolutePath());
        System.setProperty("realmshark.icon", icon.getAbsolutePath());
        Pointer[] disposed = new Pointer[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame();
                AtomicInteger clearedWhileAlive = new AtomicInteger();
                try {
                    AppIdentity.apply(frame);
                    assertFalse(frame.isDisplayable());
                    frame.setSize(80, 80);
                    frame.setLocation(-10000, -10000);
                    frame.pack();
                    Pointer hwnd = Native.getWindowPointer(frame);
                    assertTrue(WindowsAppIdentity.isOwnWindow(hwnd));
                    assertNull(readProperty(hwnd, 5)); // Packed but never shown: no Shell allocation to leak.
                    frame.addHierarchyListener(event -> {
                        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !frame.isShowing()) {
                            assertTrue("Cleanup precedes native destruction", frame.isDisplayable());
                            for (int id = 2; id <= 5; id++) assertNull(readProperty(hwnd, id));
                            clearedWhileAlive.incrementAndGet();
                        }
                    });
                    for (int show = 0; show < 2; show++) {
                        frame.setVisible(true);
                        assertEquals("RealmShark.Desktop", readProperty(hwnd, 5));
                        assertEquals('"' + exe.getAbsolutePath() + '"', readProperty(hwnd, 2));
                        assertEquals(icon.getAbsolutePath() + ",0", readProperty(hwnd, 3));
                        assertEquals("RealmShark", readProperty(hwnd, 4));
                        if (show == 0) frame.setVisible(false);
                    }
                    frame.dispose();
                    assertEquals(2, clearedWhileAlive.get());
                    assertFalse(frame.isDisplayable());
                    disposed[0] = hwnd;
                } finally { frame.dispose(); }
            });
            // AWT's native thread may finish destroying the HWND after the EDT's dispose() returns.
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (WindowsAppIdentity.isOwnWindow(disposed[0]) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(WindowsAppIdentity.isOwnWindow(disposed[0]));
            assertFalse(WindowsAppIdentity.setWindowProperties(disposed[0], null));
        } finally {
            restore("realmshark.launcher", oldLauncher);
            restore("realmshark.icon", oldIcon);
        }
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").startsWith("Windows"); }
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    private interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);
        int GetCurrentProcessExplicitAppUserModelID(PointerByReference value);
        int SHGetPropertyStoreForWindow(Pointer hwnd, Pointer iid, PointerByReference store);
    }
    private interface Ole32 extends StdCallLibrary {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);
        int CoInitializeEx(Pointer reserved, int flags);
        void CoUninitialize();
        void CoTaskMemFree(Pointer value);
        int PropVariantClear(Pointer value);
    }

    /** Independent Shell readback: use the SDK's byte layout and GetValue, not the production setter. */
    static String readProperty(Pointer hwnd, int id) {
        int initialized = Ole32.INSTANCE.CoInitializeEx(null, 2);
        assertTrue(initialized >= 0 || initialized == 0x80010106);
        Pointer store = null;
        try (Memory iid = new Memory(16); Memory key = new Memory(20);
             Memory value = new Memory(Native.POINTER_SIZE == 8 ? 24 : 16)) {
            iid.write(0, new byte[]{(byte) 0xeb, (byte) 0x8e, 0x6d, (byte) 0x88, (byte) 0xf2, (byte) 0x8c, 0x46, 0x44,
                    (byte) 0x8d, 0x02, (byte) 0xcd, (byte) 0xba, 0x1d, (byte) 0xbd, (byte) 0xcf, (byte) 0x99}, 0, 16);
            key.write(0, new byte[]{0x55, 0x28, 0x4c, (byte) 0x9f, 0x79, (byte) 0x9f, 0x39, 0x4b,
                    (byte) 0xa8, (byte) 0xd0, (byte) 0xe1, (byte) 0xd4, 0x2d, (byte) 0xe1, (byte) 0xd5, (byte) 0xf3}, 0, 16);
            key.setInt(16, id);
            PointerByReference result = new PointerByReference();
            assertEquals(0, Shell32.INSTANCE.SHGetPropertyStoreForWindow(hwnd, iid, result));
            store = result.getValue();
            value.clear();
            try {
                assertEquals(0, invoke(store, 5, key, value));
                if (value.getShort(0) == 0) return null;
                assertEquals(31, value.getShort(0));
                return value.getPointer(8).getWideString(0);
            } finally { Ole32.INSTANCE.PropVariantClear(value); }
        } finally {
            if (store != null) invoke(store, 2);
            if (initialized >= 0) Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static int invoke(Pointer object, int slot, Object... args) {
        Object[] values = new Object[args.length + 1];
        values[0] = object;
        System.arraycopy(args, 0, values, 1, args.length);
        return Function.getFunction(object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION).invokeInt(values);
    }
}
