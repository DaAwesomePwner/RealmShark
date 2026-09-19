package tomato.realmshark;

import util.PropertiesManager;
import tomato.realmshark.audio.AudioOutput;
import javax.sound.sampled.*;
import java.io.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Saved alert profiles with bounded, asynchronous audio playback. No capture thread opens audio devices. */
public class Sound {
    private static final List<Sound> instances = new CopyOnWriteArrayList<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final ThreadPoolExecutor audio = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "notification-audio"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private static volatile int masterVolume = readPercent("soundVolume", 100);
    private static volatile boolean muted = Boolean.parseBoolean(PropertiesManager.getProperty("soundMuted"));
    private static volatile String lastStatus = "Ready. Test an alert to check your current system audio output.";

    public static final Sound pm = new Sound("pm", "Whispers / DMs", "Messages", "chatPing", false);
    public static final Sound party = new Sound("party", "Party chat", "Messages", "chatPingParty", false);
    public static final Sound guild = new Sound("guild", "Guild chat", "Messages", "chatPingGuild", false);
    public static final Sound trade = new Sound("trade", "Trade requests", "Messages", "tradePing", false);
    public static final Sound keywords = new Sound("keywords", "Chat keyword matches", "Other alerts", "sound.keywords.enabled", true, "pm");
    public static final Sound keypop = new Sound("keypop", "Selected dungeon openings", "Key pops", "sound.keypop.enabled", true);
    public static final Sound whitebag = new Sound("whitebag", "White bag", "Bags", "whiteBagSound", true);
    public static final Sound orangebag = new Sound("orangebag", "Orange bag", "Bags", "orangeBagSound", false);
    public static final Sound redbag = new Sound("redbag", "Red bag", "Bags", "redBagSound", false);
    public static final Sound goldbag = new Sound("goldbag", "Gold bag", "Bags", "goldBagSound", false);
    public static final Sound eggbag = new Sound("eggbag", "Egg basket", "Bags", "eggBagSound", false);
    public static final Sound bluebag = new Sound("bluebag", "Blue bag", "Bags", "blueBagSound", false);
    public static final Sound custom = new Sound("custom", "Item, entity & enchant matches", "Other alerts", "sound.custom.enabled", true);
    public static final List<Sound> ALERTS = Collections.unmodifiableList(Arrays.asList(
            pm, party, guild, trade, keypop, whitebag, orangebag, redbag, goldbag, eggbag, bluebag, keywords, custom));
    public static final String[] BUILT_INS = {"pm", "party", "guild", "trade", "keypop", "whitebag", "orangebag", "redbag", "goldbag", "eggbag", "bluebag", "custom"};

    public final String id, label, group;
    private final String enabledKey, defaultTone;
    private volatile boolean enabled;
    private volatile int volume;
    private volatile String tone;
    // Decoding stays on the audio worker; playback cancellation is thread safe.
    private volatile AudioOutput.Playback playback;
    private String cachedTone;
    private byte[] cachedPcm;
    private AudioFormat cachedFormat;

    private Sound(String id, String label, String group, String enabledKey, boolean defaultEnabled) {
        this(id, label, group, enabledKey, defaultEnabled, id);
    }
    private Sound(String id, String label, String group, String enabledKey, boolean defaultEnabled, String defaultTone) {
        this.id = id; this.label = label; this.group = group; this.enabledKey = enabledKey; this.defaultTone = defaultTone;
        String saved = PropertiesManager.getProperty(enabledKey);
        enabled = saved == null ? defaultEnabled : Boolean.parseBoolean(saved);
        volume = readPercent("sound." + id + ".volume", 100);
        saved = PropertiesManager.getProperty("sound." + id + ".tone");
        tone = saved == null || saved.isEmpty() ? defaultTone : saved;
        instances.add(this);
    }
    public static Sound realmEvent(String id, String label) {
        return new Sound("realm." + id, label, "Realm events", "sound.realm." + id + ".enabled", false, "keypop");
    }
    static int readPercent(String key, int fallback) {
        try { return Math.max(0, Math.min(100, Integer.parseInt(PropertiesManager.getProperty(key)))); }
        catch (RuntimeException e) { return fallback; }
    }
    public boolean isEnabled() { return enabled; }
    public int getVolume() { return volume; }
    public String getTone() { return tone; }
    public String getDefaultTone() { return defaultTone; }
    public static int getMasterVolume() { return masterVolume; }
    public static boolean isMuted() { return muted; }
    public static String getLastStatus() { return lastStatus; }
    public void setEnabled(boolean value) { enabled = value; save(enabledKey, Boolean.toString(value)); }
    public void setAlertVolume(int value) { volume = percent(value); save("sound." + id + ".volume", Integer.toString(volume)); }
    public void setTone(String value) {
        if (value == null || value.trim().isEmpty()) value = defaultTone;
        tone = value; save("sound." + id + ".tone", value);
    }
    public static void setVolume(int value) { masterVolume = percent(value); save("soundVolume", Integer.toString(masterVolume)); }
    public static void setMuted(boolean value) { muted = value; if (value) for (Sound sound : instances) sound.stop(); save("soundMuted", Boolean.toString(value)); }
    private static int percent(int value) { return Math.max(0, Math.min(100, value)); }
    private static void save(String key, String value) { PropertiesManager.setProperties(key, value); changed(); }
    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }
    private static void changed() { for (Runnable listener : listeners) listener.run(); }
    private static void status(String message) { lastStatus = message; changed(); }

    /** Event playback respects both this profile and the master mute. */
    public void play() { if (isEnabled() && !muted && masterVolume > 0 && volume > 0) submit(false, null); }
    /** Test ignores this alert's enable switch, but always honors master mute and volumes. */
    public void preview(Consumer<String> result) { submit(true, result); }
    private void submit(boolean test, Consumer<String> result) {
        try {
            audio.execute(() -> {
                if (muted || masterVolume == 0 || volume == 0)
                    report("Muted: raise the master and alert volumes and turn off Mute all.", result);
                else if (test || enabled) {
                    try { playNow(result); }
                    catch (Exception | LinkageError e) { failed(e.getMessage(), result); }
                }
            });
        } catch (RejectedExecutionException e) {
            report("Audio is busy; this alert was skipped. Try again.", result);
        }
    }
    private static void report(String message, Consumer<String> result) {
        status(message);
        if (result != null) result.accept(message);
    }
    private void failed(String reason, Consumer<String> result) {
        report("Could not play " + label + ": " + reason + ". Check the audio output or choose another WAV.", result);
    }
    private void playNow(Consumer<String> result) throws Exception {
        String selected = tone;
        if (!selected.equals(cachedTone)) {
            Decoded decoded = decode(selected);
            cachedPcm = decoded.pcm; cachedFormat = decoded.format; cachedTone = selected;
        }
        stop();
        byte[] pcm = scalePcm(cachedPcm, masterVolume, volume);
        playback = AudioOutput.play(cachedFormat, pcm,
                device -> report("Playing " + label + " on " + device + ".", result),
                reason -> failed(reason, result));
        if (muted) playback.stop();
    }
    static byte[] scalePcm(byte[] original, int master, int alert) {
        byte[] pcm = original.clone(); double gain = percent(master) * percent(alert) / 10000.0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short)((pcm[i] & 255) | (pcm[i + 1] << 8));
            int scaled = (int)Math.round(sample * gain);
            pcm[i] = (byte)scaled; pcm[i + 1] = (byte)(scaled >> 8);
        }
        return pcm;
    }
    /** Validates a local WAV off the UI thread before replacing a saved choice. */
    public void chooseFile(File file, Consumer<String> result) {
        try {
            audio.execute(() -> {
                String message;
                try {
                    String path = file.getAbsolutePath(); Decoded validated = decode(path);
                    cachedPcm = validated.pcm; cachedFormat = validated.format; cachedTone = path; setTone(path);
                    message = "Saved " + file.getName() + ". Use Test to hear it.";
                } catch (Exception e) { message = "Sound unchanged: " + e.getMessage(); }
                status(message); if (result != null) result.accept(message);
            });
        } catch (RejectedExecutionException e) { if (result != null) result.accept("Audio is busy; try again."); }
    }
    static Decoded decode(String source) throws Exception {
        boolean builtIn = Arrays.asList(BUILT_INS).contains(source);
        String path = builtIn ? "sound/" + source + ".wav" : source;
        File file = new File(path);
        if (!builtIn && (!file.isFile() || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".wav")))
            throw new IOException("Choose an existing WAV file");
        if (file.isFile() && file.length() > 10 * 1024 * 1024) throw new IOException("WAV files must be 10 MB or smaller");
        InputStream raw = file.isFile() ? new FileInputStream(file) : Sound.class.getResourceAsStream("/" + path);
        if (raw == null) throw new IOException("Sound file is missing");
        try (InputStream in = new BufferedInputStream(raw); AudioInputStream stream = AudioSystem.getAudioInputStream(in)) {
            AudioFormat format = new AudioFormat(44100, 16, Math.max(1, Math.min(2, stream.getFormat().getChannels())), true, false);
            if (!AudioSystem.isConversionSupported(format, stream.getFormat())) throw new IOException("Unsupported WAV encoding");
            try (AudioInputStream decoded = AudioSystem.getAudioInputStream(format, stream); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = decoded.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (out.size() > format.getFrameRate() * format.getFrameSize() * 30) throw new IOException("Sounds must be 30 seconds or shorter");
                }
                if (out.size() == 0) throw new IOException("The WAV contains no audio");
                return new Decoded(format, out.toByteArray());
            }
        }
    }
    static final class Decoded {
        final AudioFormat format; final byte[] pcm;
        Decoded(AudioFormat format, byte[] pcm) { this.format = format; this.pcm = pcm; }
    }
    public void dispose() { instances.remove(this); stop(); }
    public void stop() {
        AudioOutput.Playback current = playback;
        if (current != null) current.stop();
    }
}