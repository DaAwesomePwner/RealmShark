package realmshark.branding;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Windows 7+ Shell identity, using only JNA core and public Win32 APIs. */
final class WindowsAppIdentity {
    private static final String PROPERTY_FORMAT = "9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3";
    private static final String PROPERTY_STORE_IID = "886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99";
    private static final int RELAUNCH_COMMAND = 2, RELAUNCH_ICON = 3, RELAUNCH_NAME = 4, APP_ID = 5;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;
    // No Window references or AWT locks: System.exit may leave the EDT waiting for shutdown hooks.
    private static final Set<Pointer> LIVE_WINDOWS = new HashSet<>();
    private static boolean shutdownHookRegistered, shuttingDown;

    private WindowsAppIdentity() { }

    private interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);
        int SetCurrentProcessExplicitAppUserModelID(WString appId);
        int SHGetPropertyStoreForWindow(Pointer hwnd, Pointer iid, PointerByReference store);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        boolean IsWindow(Pointer hwnd);
        int GetWindowThreadProcessId(Pointer hwnd, IntByReference processId);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        int GetCurrentProcessId();
    }

    private interface Ole32 extends StdCallLibrary {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);
        int CoInitializeEx(Pointer reserved, int flags);
        void CoUninitialize();
    }

    static void initializeProcess() {
        check(Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(AppIdentity.APP_USER_MODEL_ID)));
    }

    static void attach(Window window) {
        synchronized (window.getTreeLock()) {
            for (HierarchyListener listener : window.getHierarchyListeners()) {
                if (listener instanceof Binding) {
                    ((Binding) listener).refresh(window);
                    return;
                }
            }
            Binding binding = new Binding();
            window.addHierarchyListener(binding);
            binding.refresh(window);
        }
    }

    private static final class Binding implements HierarchyListener {
        private Pointer applied;

        @Override public void hierarchyChanged(HierarchyEvent event) {
            if ((event.getChangeFlags() & (HierarchyEvent.SHOWING_CHANGED | HierarchyEvent.DISPLAYABILITY_CHANGED)) == 0) return;
            try { refresh((Window) event.getComponent()); }
            catch (RuntimeException | LinkageError ex) { AppIdentity.nativeFailure(ex); }
        }

        private void refresh(Window window) {
            if (!window.isShowing()) {
                // AWT hide() emits SHOWING_CHANGED synchronously, before dispose() destroys the HWND.
                // Do not set properties on a merely packed, never-shown window: it has no hide event.
                if (applied != null) {
                    clearWindowProperties(applied);
                    applied = null;
                }
                return;
            }
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd.equals(applied)) return;
            if (setWindowProperties(hwnd, RelaunchInfo.fromProperties())) applied = hwnd;
        }
    }

    /** HWNDs are opaque handles; never dereference them or pass stale/foreign handles to the Shell. */
    static boolean isOwnWindow(Pointer hwnd) {
        if (hwnd == null || Pointer.nativeValue(hwnd) == 0 || Pointer.nativeValue(hwnd) == -1) return false;
        if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
        if (!User32.INSTANCE.IsWindow(hwnd)) return false;
        IntByReference processId = new IntByReference();
        return User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId) != 0
                && processId.getValue() == Kernel32.INSTANCE.GetCurrentProcessId();
    }

    static boolean setWindowProperties(Pointer hwnd, RelaunchInfo relaunch) {
        synchronized (LIVE_WINDOWS) {
            if (shuttingDown || !isOwnWindow(hwnd)) return false;
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(WindowsAppIdentity::shutdown, "RealmShark-taskbar-cleanup"));
                shutdownHookRegistered = true;
            }
            LIVE_WINDOWS.add(hwnd);
            try (PropertyStore store = new PropertyStore(hwnd)) {
                boolean complete = false;
                try {
                    if (relaunch != null) {
                        store.set(RELAUNCH_COMMAND, relaunch.command);
                        store.set(RELAUNCH_NAME, AppIdentity.NAME);
                        store.set(RELAUNCH_ICON, relaunch.iconResource);
                    }
                    // Publish the ID last, with its relaunch metadata already in place.
                    store.set(APP_ID, AppIdentity.APP_USER_MODEL_ID);
                    complete = true;
                } finally {
                    if (!complete) {
                        store.clear();
                        LIVE_WINDOWS.remove(hwnd);
                    }
                }
            }
            return true;
        }
    }

    static boolean clearWindowProperties(Pointer hwnd) {
        synchronized (LIVE_WINDOWS) {
            if (!isOwnWindow(hwnd)) {
                LIVE_WINDOWS.remove(hwnd);
                return false;
            }
            try (PropertyStore store = new PropertyStore(hwnd)) { store.clear(); }
            LIVE_WINDOWS.remove(hwnd);
            return true;
        }
    }

    /** Called before System.exit; the hook is only a fallback because ToolkitShutdown runs concurrently. */
    static void shutdown() {
        synchronized (LIVE_WINDOWS) {
            shuttingDown = true; // Serialize with writers and prevent rebranding during shutdown.
            for (Pointer hwnd : new ArrayList<>(LIVE_WINDOWS)) {
                try { clearWindowProperties(hwnd); }
                catch (RuntimeException | LinkageError ex) { AppIdentity.nativeFailure(ex); }
            }
        }
    }

    static final class RelaunchInfo {
        final String command;
        final String iconResource;

        private RelaunchInfo(String command, String iconResource) {
            this.command = command;
            this.iconResource = iconResource;
        }

        static RelaunchInfo fromProperties() {
            return create(System.getProperty("realmshark.launcher"), System.getProperty("realmshark.icon"));
        }

        static RelaunchInfo create(String launcherPath, String iconPath) {
            File launcher = absoluteFile(launcherPath);
            if (launcher == null) return null;
            String extension = launcher.getName().toLowerCase(Locale.ROOT);
            boolean executable = extension.endsWith(".exe");
            if (!executable && !extension.endsWith(".cmd")) return null;
            File icon = absoluteFile(iconPath);
            if (icon == null) {
                if (!executable) return null;
                icon = launcher;
            }
            // ShellExecute accepts a quoted document (.cmd) as well as a quoted executable.
            return new RelaunchInfo('"' + launcher.getAbsolutePath() + '"', icon.getAbsolutePath() + ",0");
        }

        private static File absoluteFile(String path) {
            if (path == null || path.isEmpty() || path.indexOf('"') >= 0) return null;
            for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return null;
            File file = new File(path);
            return file.isAbsolute() && file.isFile() ? file : null;
        }
    }

    /*
     * SHGetPropertyStoreForWindow: SetValue stores immediately; Commit is unnecessary.
     * https://learn.microsoft.com/windows/win32/api/shellapi/nf-shellapi-shgetpropertystoreforwindow
     * IPropertyStore vtable: IUnknown(0..2), GetCount(3), GetAt(4), GetValue(5), SetValue(6), Commit(7).
     */
    private static final class PropertyStore implements AutoCloseable {
        private final Pointer store;
        private final boolean uninitialize;

        private PropertyStore(Pointer hwnd) {
            int hr = Ole32.INSTANCE.CoInitializeEx(null, 2); // COINIT_APARTMENTTHREADED
            if (hr != RPC_E_CHANGED_MODE) check(hr);
            uninitialize = hr >= 0; // Balance S_OK and S_FALSE, never RPC_E_CHANGED_MODE.
            Pointer acquired = null;
            boolean complete = false;
            try (Memory iid = guid(PROPERTY_STORE_IID)) {
                PointerByReference result = new PointerByReference();
                check(Shell32.INSTANCE.SHGetPropertyStoreForWindow(hwnd, iid, result));
                acquired = result.getValue();
                if (acquired == null) throw new IllegalStateException("Shell returned no window property store");
                complete = true;
            } finally {
                if (!complete && uninitialize) Ole32.INSTANCE.CoUninitialize();
            }
            store = acquired;
        }

        private void set(int id, String text) {
            try (Memory key = new Memory(20);
                 Memory format = guid(PROPERTY_FORMAT);
                 Memory variant = new Memory(Native.POINTER_SIZE == 8 ? 24 : 16);
                 Memory value = text == null ? null : new Memory((long) (text.length() + 1) * Native.WCHAR_SIZE)) {
                key.write(0, format.getByteArray(0, 16), 0, 16);
                key.setInt(16, id);
                variant.clear(); // VT_EMPTY removes a property.
                if (value != null) {
                    value.setWideString(0, text);
                    variant.setShort(0, (short) 31); // VT_LPWSTR
                    variant.setPointer(8, value);
                }
                // SetValue copies the string. These input buffers belong to JNA, not PropVariantClear.
                check(call(store, 6, key, variant));
            }
        }

        private void clear() {
            set(APP_ID, null);
            set(RELAUNCH_COMMAND, null);
            set(RELAUNCH_NAME, null);
            set(RELAUNCH_ICON, null);
        }

        @Override public void close() {
            try { call(store, 2); }
            finally { if (uninitialize) Ole32.INSTANCE.CoUninitialize(); }
        }
    }

    private static int call(Pointer object, int slot, Object... args) {
        Object[] values = new Object[args.length + 1];
        values[0] = object;
        System.arraycopy(args, 0, values, 1, args.length);
        Pointer address = object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
        return Function.getFunction(address, Function.ALT_CONVENTION).invokeInt(values);
    }

    private static void check(int hr) {
        if (hr < 0) throw new IllegalStateException(String.format("Windows Shell HRESULT 0x%08X", hr));
    }

    private static Memory guid(String value) {
        UUID uuid = UUID.fromString(value);
        long high = uuid.getMostSignificantBits(), low = uuid.getLeastSignificantBits();
        Memory bytes = new Memory(16);
        bytes.setInt(0, (int) (high >>> 32));
        bytes.setShort(4, (short) (high >>> 16));
        bytes.setShort(6, (short) high);
        for (int i = 0; i < 8; i++) bytes.setByte(8 + i, (byte) (low >>> (56 - 8 * i)));
        return bytes;
    }
}
