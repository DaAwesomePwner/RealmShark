package tomato.gui.chat;

import com.google.gson.Gson;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import util.PropertiesManager;

/** Shared by capture and display. Each published rule set is immutable. */
final class ChatFilters {
    private static final String KEY = "chat.filters";
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");
    private static final Pattern DOT = Pattern.compile("\\s*(?:\\[dot\\]|\\(dot\\)|\\[\\.\\]|\\(\\.\\))\\s*|\\s+\\.\\s*|\\.\\s+(?=(?:com|net|org|gg|io|co|shop|xyz|ru|me|biz|info|store|online|site|cc|top)(?![a-z]))");
    private static final Pattern LINK = Pattern.compile("(?:https?://|www\\.|(?<![\\p{L}\\p{N}-])(?:[a-z0-9-]{1,63}\\.){1,12}[a-z]{2,24}(?![a-z]))");
    private static final Pattern SALES = Pattern.compile("\\b(?:buy|sell|selling|cheap|cheapest|discount|coupon|promo|shop|store|stock|delivery|boosting|powerlevelling|powerleveling|services?)\\b|\\b(?:real[- ]?money|realm gold)\\b|[$€£]\\s*\\d");
    private volatile Rules rules = new Rules(new Settings());
    private volatile List<String> inherited = Collections.emptyList();
    private volatile long revision;

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
    ChatFilters() { }
    static ChatFilters load() {
        ChatFilters filters = new ChatFilters();
        try {
            Settings saved = new Gson().fromJson(PropertiesManager.getProperty(KEY), Settings.class);
            if (saved != null) filters.rules = new Rules(saved);
        } catch (RuntimeException e) { System.err.println("Could not read chat filters; using defaults."); }
        return filters;
    }
    Settings settings() { return new Gson().fromJson(new Gson().toJson(rules.settings), Settings.class); }
    synchronized void apply(Settings settings, boolean persist) {
        Rules next = new Rules(settings);
        if (persist) PropertiesManager.setProperties(KEY, new Gson().toJson(next.settings));
        rules = next; revision++;
    }
    synchronized void inherited(Collection<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String rule : clean(values)) normalized.add(normalize(rule));
        inherited = Collections.unmodifiableList(normalized); revision++;
    }
    long revision() { return revision; }
    int inheritedCount() { return inherited.size(); }
    boolean ignoresPlayer(String name) { return rules.ignored.contains(playerKey(name)); }
    boolean ignoresPlayer(ChatMessage message) { return !playerIgnoreReason(rules, message).isEmpty(); }
    void togglePlayer(String name) {
        Settings settings = settings(); String key = playerKey(name);
        if (key.isEmpty() || key.startsWith("#")) return;
        if (!settings.ignoredPlayers.removeIf(value -> playerKey(value).equals(key))) settings.ignoredPlayers.add(name);
        apply(settings, true);
    }
    String reason(ChatMessage message) {
        Rules r = rules;
        if (message.ownMessage || message.channel == ChatMessage.Channel.SYSTEM) return "";
        String playerIgnore = playerIgnoreReason(r, message);
        if (!playerIgnore.isEmpty()) return playerIgnore;
        if (r.allowed.contains(playerKey(message.sender))) return "";
        String text = normalize(message.text);
        for (String phrase : r.phrases) if (text.contains(phrase)) return "Blocked phrase: " + phrase;
        if (r.settings.inheritedRules) for (String phrase : inherited)
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
