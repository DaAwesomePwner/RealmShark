package tomato.realmshark.audio;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.util.UUID;

/** Minimal WASAPI shared-mode renderer. Every COM call stays on the audio worker. */
final class WasapiBackend implements DefaultAudioEngine.Backend {
    private interface Ole32 extends StdCallLibrary {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);
        int CoInitializeEx(Pointer reserved, int flags);
        void CoUninitialize();
        int CoCreateInstance(Pointer clsid, Pointer outer, int context, Pointer iid, PointerByReference object);
        void CoTaskMemFree(Pointer memory);
        int PropVariantClear(Pointer variant);
    }
    private static final Memory ENUMERATOR_CLASS = guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
    private static final Memory ENUMERATOR_IID = guid("A95664D2-9614-4F35-A746-DE8DB63617E6");
    private static final Memory CLIENT_IID = guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static final Memory RENDER_IID = guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
    private static final int CLSCTX_ALL = 23;
    // Let Windows convert our PCM to the endpoint's current format without changing device settings.
    private static final int AUTOCONVERT_PCM_AND_SRC_QUALITY = 0x88000000;
    private Pointer enumerator;
    private boolean initialized;

    private void initialize() throws IOException {
        if (enumerator != null) return;
        if (!initialized) {
            check(Ole32.INSTANCE.CoInitializeEx(null, 2), "Initialize Windows audio"); // STA, including Windows 8 support
            initialized = true;
        }
        PointerByReference result = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(ENUMERATOR_CLASS, null, CLSCTX_ALL, ENUMERATOR_IID, result),
                "Find Windows audio devices");
        enumerator = result.getValue();
    }

    @Override public DefaultAudioEngine.Device defaultDevice() throws Exception {
        initialize();
        PointerByReference result = new PointerByReference();
        check(call(enumerator, 4, 0, 1, result), "Find the default Windows output"); // eRender, eMultimedia
        Pointer device = result.getValue();
        PointerByReference id = new PointerByReference();
        try {
            check(call(device, 5, id), "Read the Windows output ID");
            return new DefaultAudioEngine.Device(id.getValue().getWideString(0), friendlyName(device));
        } finally {
            if (id.getValue() != null) Ole32.INSTANCE.CoTaskMemFree(id.getValue());
            release(device);
        }
    }

    private String friendlyName(Pointer device) {
        Pointer store = null;
        try (Memory key = new Memory(20); Memory value = new Memory(Native.POINTER_SIZE == 8 ? 24 : 16)) {
            PointerByReference result = new PointerByReference();
            check(call(device, 4, 0, result), "Read output properties"); // STGM_READ
            store = result.getValue();
            try (Memory format = guid("a45c254e-df1c-4efd-8020-67d146a850e0")) {
                key.write(0, format.getByteArray(0, 16), 0, 16);
            }
            key.setInt(16, 14); // PKEY_Device_FriendlyName
            value.clear();
            try {
                check(call(store, 5, key, value), "Read output name");
                if (value.getShort(0) == 31 && value.getPointer(8) != null)
                    return value.getPointer(8).getWideString(0); // VT_LPWSTR
            } finally { Ole32.INSTANCE.PropVariantClear(value); }
        } catch (Exception ignored) { /* Naming is optional; routing by endpoint ID is not. */ }
        finally { release(store); }
        return "the current Windows default output";
    }

    @Override public DefaultAudioEngine.Stream open(DefaultAudioEngine.Device selected, AudioFormat format) throws Exception {
        initialize();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleSizeInBits() != 16
                || format.isBigEndian() || format.getChannels() < 1 || format.getChannels() > 2)
            throw new IOException("Windows notifications require 16-bit mono or stereo PCM");
        Pointer device = null, client = null, render = null;
        try (Memory wave = new Memory(18)) {
            PointerByReference result = new PointerByReference();
            check(call(enumerator, 5, new WString(selected.id), result), "Open the selected Windows output");
            device = result.getValue();
            result.setValue(null);
            check(call(device, 3, CLIENT_IID, CLSCTX_ALL, null, result), "Activate the selected Windows output");
            client = result.getValue();
            wave.clear();
            wave.setShort(0, (short)1); // WAVE_FORMAT_PCM
            wave.setShort(2, (short)format.getChannels());
            wave.setInt(4, (int)format.getSampleRate());
            wave.setInt(8, (int)format.getSampleRate() * format.getFrameSize());
            wave.setShort(12, (short)format.getFrameSize());
            wave.setShort(14, (short)16);
            check(call(client, 3, 0, AUTOCONVERT_PCM_AND_SRC_QUALITY, 1_000_000L, 0L, wave, null),
                    "Initialize " + selected.name); // shared mode, 100 ms buffer
            IntByReference frames = new IntByReference();
            check(call(client, 4, frames), "Read the audio buffer size");
            result.setValue(null);
            check(call(client, 14, RENDER_IID, result), "Open the audio renderer");
            render = result.getValue();
            NativeStream stream = new NativeStream(client, render, frames.getValue(), format.getFrameSize());
            client = null; render = null; // Ownership transfers only after all initialization succeeds.
            return stream;
        } finally { release(render); release(client); release(device); }
    }

    private static final class NativeStream implements DefaultAudioEngine.Stream {
        private Pointer client, render;
        private final int frames, frameSize;
        private boolean started;
        NativeStream(Pointer client, Pointer render, int frames, int frameSize) {
            this.client = client; this.render = render; this.frames = frames; this.frameSize = frameSize;
        }
        @Override public int capacity() { return frames; }
        @Override public int padding() throws IOException {
            IntByReference padding = new IntByReference();
            check(call(client, 6, padding), "Read audio playback progress");
            return padding.getValue();
        }
        @Override public void write(byte[] pcm, int offset, int count) throws IOException {
            PointerByReference buffer = new PointerByReference();
            check(call(render, 3, count, buffer), "Get the audio buffer");
            try { buffer.getValue().write(0, pcm, offset, count * frameSize); }
            finally { check(call(render, 4, count, 0), "Submit the audio buffer"); }
        }
        @Override public void start() throws IOException {
            check(call(client, 10), "Start Windows audio playback");
            started = true;
        }
        @Override public void close() {
            if (client == null) return;
            if (started) call(client, 11);
            release(render); release(client);
            render = null; client = null;
        }
    }

    private static int call(Pointer object, int slot, Object... args) {
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = object;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        Pointer function = object.getPointer(0).getPointer((long)slot * Native.POINTER_SIZE);
        return Function.getFunction(function, Function.ALT_CONVENTION).invokeInt(callArgs);
    }
    private static void release(Pointer object) { if (object != null) call(object, 2); }
    private static void check(int hr, String operation) throws IOException {
        if (hr < 0) throw new IOException(operation + " failed (Windows audio " + String.format("0x%08X", hr) + ")");
    }
    private static Memory guid(String value) {
        UUID uuid = UUID.fromString(value);
        long high = uuid.getMostSignificantBits(), low = uuid.getLeastSignificantBits();
        Memory bytes = new Memory(16);
        bytes.setInt(0, (int)(high >>> 32));
        bytes.setShort(4, (short)(high >>> 16));
        bytes.setShort(6, (short)high);
        for (int i = 0; i < 8; i++) bytes.setByte(8 + i, (byte)(low >>> (56 - 8 * i)));
        return bytes;
    }
    @Override public void close() {
        release(enumerator); enumerator = null;
        if (initialized) { Ole32.INSTANCE.CoUninitialize(); initialized = false; }
    }
}
