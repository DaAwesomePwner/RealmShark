package tomato.gui.chat;

import com.google.gson.Gson;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import util.PreferencesStore;
import util.PropertiesManager;

/** Shared by capture and display. Each published rule set is immutable. */
final class ChatFilters {
    private static final String KEY = "chat.filters";
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");
    private static final Pattern DOT = Pattern.compile("\\s*(?:\\[dot\\]|\\(dot\\)|\\[\\.\\]|\\(\\.\\))\\s*|\\s+\\.\\s*|\\.\\s+(?=(?:com|net|org|gg|io|co|shop|xyz|ru|me|biz|info|store|online|site|cc|top)(?![a-z]))");
    private static final Pattern LINK = Pattern.compile("(?:https?://|www\\.|(?<![\\p{L}\\p{N}-])(?:[a-z0-9-]{1,63}\\.){1,12}[a-z]{2,24}(?![a-z]))");
    private static final Pattern SALES = Pattern.compile("\\b(?:buy|sell|selling|cheap|cheapest|discount|coupon|promo|shop|store|stock|delivery|boosting|powerlevelling|powerleveling|services?)\\b|\\b(?:real[- ]?money|realm gold)\\b|[$€£]\\s*\\d");
    private volatile Policy policy = new Policy(new Rules(new Settings()), Collections.emptyList(), 0, 0);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String saveStatus = "";
    private final java.util.function.Function<String, CompletionStage<PreferencesStore.SaveResult>> persist;

    private static final class Policy {
        final Rules rules; final List<String> inherited; final long revision, edits;
        Policy(Rules rules, List<String> inherited, long revision, long edits) {
            this.rules = rules; this.inherited = inherited; this.revision = revision; this.edits = edits;
        }
    }

    static final class Settings {
        boolean advertisements = true, whisperLinks, gameIgnores = true, inheritedRules = true;
        List<String> ignoredPlayers = new ArrayList<>(), phrases = new ArrayList<>(), allowedPlayers = new ArrayList<>();
    }
    private static final class Rules {
        final Settings settings;
        final Set<String> ignored, allowed;
        final List<String> phrases;
        Rules(Settings input) {
            Settings s = new Settings();
            s.advertisements = input.advertisements; s.whisperLinks = input.whisperLinks;
            s.gameIgnores = input.gameIgnores; s.inheritedRules = input.inheritedRules;
            s.ignoredPlayers = clean(input.ignoredPlayers); s.phrases = clean(input.phrases); s.allowedPlayers = clean(input.allowedPlayers);
            settings = s; ignored = names(s.ignoredPlayers); allowed = names(s.allowedPlayers);
            List<String> normalized = new ArrayList<>();
            for (String phrase : s.phrases) normalized.add(normalize(phrase));
            phrases = Collections.unmodifiableList(normalized);
        }
    }
    ChatFilters() { this(value -> PropertiesManager.setPropertiesAsync(KEY, value)); }
    ChatFilters(java.util.function.Function<String, CompletionStage<PreferencesStore.SaveResult>> persist) { this.persist = persist; }
    static ChatFilters load() {
        ChatFilters filters = new ChatFilters();
        try {
            Settings saved = new Gson().fromJson(PropertiesManager.getProperty(KEY), Settings.class);
            if (saved != null) filters.policy = new Policy(new Rules(saved), Collections.emptyList(), 0, 0);
        } catch (RuntimeException e) { System.err.println("Could not read chat filters; using defaults."); }
        return filters;
    }
    Settings settings() { return new Gson().fromJson(new Gson().toJson(policy.rules.settings), Settings.class); }
    synchronized void apply(Settings settings, boolean persist) {
        apply(settings, persist, policy.edits);
    }
    synchronized CompletionStage<PreferencesStore.SaveResult> apply(Settings settings, boolean persist, long expectedEdits) {
        if (expectedEdits != policy.edits) throw new IllegalStateException("Filters changed in another view. Reopen the editor to load current rules; this draft is retained.");
        Rules next = new Rules(settings);
        CompletionStage<PreferencesStore.SaveResult> saving = persist ? this.persist.apply(new Gson().toJson(next.settings))
            : CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(0));
        policy = new Policy(next, policy.inherited, policy.revision + 1, policy.edits + 1);
        long edits = policy.edits;
        saveStatus = persist ? "Filter changes active; saving…" : "";
        changed();
        if (persist) saving.whenComplete((result, failure) -> {
            synchronized (ChatFilters.this) {
                if (edits != policy.edits) return;
                saveStatus = failure == null && result != null && result.isSuccess() ? "Filters saved." : "Filters active; disk save failed. Open Chat filters to retry.";
            }
            changed();
        });
        return saving;
    }
    synchronized void inherited(Collection<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String rule : clean(values)) normalized.add(normalize(rule));
        policy = new Policy(policy.rules, Collections.unmodifiableList(normalized), policy.revision + 1, policy.edits); changed();
    }
    long revision() { return policy.revision; }
    long edits() { return policy.edits; }
    String saveStatus() { return saveStatus; }
    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : listeners) listener.run(); }
    int inheritedCount() { return policy.inherited.size(); }
    boolean ignoresPlayer(String name) { return policy.rules.ignored.contains(playerKey(name)); }
    boolean ignoresPlayer(ChatMessage message) { return !playerIgnoreReason(policy.rules, message).isEmpty(); }
    synchronized void togglePlayer(String name) {
        Settings settings = settings(); String key = playerKey(name);
        if (key.isEmpty() || key.startsWith("#")) return;
        if (!settings.ignoredPlayers.removeIf(value -> playerKey(value).equals(key))) settings.ignoredPlayers.add(name);
        apply(settings, true);
    }
    String reason(ChatMessage message) {
        return reason(policy, message);
    }
    Classification snapshot() { return new Classification(policy); }
    static final class Classification {
        private final Policy policy;
        final long revision;
        private Classification(Policy policy) { this.policy = policy; revision = policy.revision; }
        String reason(ChatMessage message) { return ChatFilters.reason(policy, message); }
        boolean ignoresPlayer(ChatMessage message) { return !playerIgnoreReason(policy.rules, message).isEmpty(); }
        String fingerprint() {
            try {
                byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((new Gson().toJson(policy.rules.settings) + "\n" + new Gson().toJson(policy.inherited)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 255)); return hex.toString();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    private static String reason(Policy current, ChatMessage message) {
        Rules r = current.rules;
        if (message.ownMessage || message.channel == ChatMessage.Channel.SYSTEM) return "";
        String playerIgnore = playerIgnoreReason(r, message);
        if (!playerIgnore.isEmpty()) return playerIgnore;
        if (r.allowed.contains(playerKey(message.sender))) return "";
        String text = normalize(message.text);
        for (String phrase : r.phrases) if (text.contains(phrase)) return "Blocked phrase: " + phrase;
        if (r.settings.inheritedRules) for (String phrase : current.inherited)
            if (text.contains(phrase)) return "Existing spam rule: " + phrase;
        boolean privateMessage = message.channel == ChatMessage.Channel.PM && !"To".equals(message.direction);
        if (!privateMessage && message.channel != ChatMessage.Channel.WORLD) return "";
        boolean link = LINK.matcher(DOT.matcher(text).replaceAll(".")).find();
        if (r.settings.whisperLinks && privateMessage && link) return "Link in whisper";
        if (r.settings.advertisements && link && SALES.matcher(text).find()) return "Possible advertisement: link and sales wording";
        return "";
    }
    private static String playerIgnoreReason(Rules rules, ChatMessage message) {
        if (message.ownMessage || message.channel == ChatMessage.Channel.SYSTEM) return "";
        if (rules.ignored.contains(playerKey(message.sender))) return "Ignored player: " + message.sender;
        return rules.settings.gameIgnores && message.gameIgnored ? "In-game ignore observed at receipt" : "";
    }
    static String normalize(String text) {
        String value = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SPACE.matcher(INVISIBLE.matcher(value).replaceAll("")).replaceAll(" ").trim();
    }
    static String playerKey(String value) { return normalize(value == null ? "" : value.split(",", 2)[0]); }
    private static Set<String> names(List<String> values) {
        Set<String> names = new HashSet<>(); for (String value : values) names.add(playerKey(value)); return names;
    }
    private static List<String> clean(Collection<String> values) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (values != null) for (String value : values) {
            String key = normalize(value); if (!key.isEmpty()) unique.putIfAbsent(key, value.trim());
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }
}
