package tomato.realmshark;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import util.PreferencesStore;
import util.PropertiesManager;

/** Typed local rules. Evaluation is pure; only explicit saves change preference memory/disk. */
public final class AlertRules {
    public enum Domain {
        ITEM("itemPings"), ENTITY("entityIdPings"), CHAT("chatPingMessages");
        public final String legacyKey;
        Domain(String legacyKey) { this.legacyKey = legacyKey; }
        public String key() { return "alerts.rules." + name().toLowerCase(Locale.ROOT); }
    }
    public enum Mode {
        NAME_CONTAINS("Name contains", Domain.ITEM), ITEM_ID("Exact item ID", Domain.ITEM),
        ENTITY_TYPE("Exact entity type", Domain.ENTITY), TEXT_CONTAINS("Text contains", Domain.CHAT),
        SPACE_TOKEN("Space-delimited token", Domain.CHAT),
        LEGACY_ITEM("Legacy name / ID contains", Domain.ITEM),
        LEGACY_ENTITY("Legacy entity text equals", Domain.ENTITY),
        LEGACY_CHAT("Legacy chat rule (quotes = token)", Domain.CHAT);
        public final String label;
        public final Domain domain;
        Mode(String label, Domain domain) { this.label = label; this.domain = domain; }
        @Override public String toString() { return label; }
    }
    public static final class Rule {
        private final JsonElement raw;
        public final Mode mode;
        public final String value;
        private Rule(JsonElement raw, Mode mode, String value) {
            this.raw = raw.deepCopy(); this.mode = mode; this.value = value;
        }
        public static Rule of(Mode mode, String value) {
            Objects.requireNonNull(mode); Objects.requireNonNull(value);
            if (!mode.name().startsWith("LEGACY_")) {
                if (value.trim().isEmpty()) throw new IllegalArgumentException("Enter a non-empty rule value.");
                if (mode == Mode.ITEM_ID || mode == Mode.ENTITY_TYPE) {
                    int id;
                    try { id = Integer.parseInt(value.trim()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a decimal integer ID."); }
                    if (id < (mode == Mode.ITEM_ID ? 1 : 0) || (mode == Mode.ENTITY_TYPE && id > 65535))
                        throw new IllegalArgumentException(mode == Mode.ITEM_ID ? "Item ID must be positive." : "Entity type must be 0–65535.");
                    value = Integer.toString(id);
                }
                if (mode == Mode.SPACE_TOKEN && value.indexOf(' ') >= 0)
                    throw new IllegalArgumentException("A space-delimited token cannot contain spaces. Punctuation is literal.");
            }
            JsonObject raw = new JsonObject(); raw.addProperty("mode", mode.name()); raw.addProperty("value", value);
            return new Rule(raw, mode, value);
        }
        public boolean supported() { return mode != null; }
        public String label() { return supported() ? mode.label : "Unsupported rule — preserved"; }
        public Rule edited(Mode nextMode, String nextValue) {
            Rule next = of(nextMode, nextValue);
            JsonObject object = raw.isJsonObject() ? raw.getAsJsonObject().deepCopy() : new JsonObject();
            object.add("mode", next.raw.getAsJsonObject().get("mode")); object.add("value", next.raw.getAsJsonObject().get("value"));
            return new Rule(object, next.mode, next.value);
        }
    }
    /**
     * Detached proposal for a rule plus the observation it came from. Holds only strings and
     * numbers, never packets or live rows. Opening a draft evaluates the sample silently; a draft is
     * never saved, enabled or played unless the user explicitly chooses Save rules or Test sound.
     */
    public static final class Draft {
        public static final int MAX_VALUE = 160, MAX_SAMPLE = 2000;
        public final Domain domain;
        /** Proposed mode/value; {@code mode} may be null for a sample-only draft. */
        public final Mode mode;
        public final String value;
        /** Sample item ID or entity type; null for chat. */
        public final Integer sampleId;
        /** Sample message or item name; null when unavailable. */
        public final String sampleText;
        /** Human description of the source record, e.g. "Chat · 12:03:04 · Ann". */
        public final String source;
        private Draft(Domain domain, Mode mode, String value, Integer sampleId, String sampleText, String source) {
            this.domain = Objects.requireNonNull(domain, "domain");
            if (mode != null && mode.domain != domain) throw new IllegalArgumentException("Rule mode belongs to another alert category.");
            this.mode = mode; this.value = bound(value == null ? "" : value, MAX_VALUE);
            this.sampleId = sampleId; this.sampleText = sampleText == null ? null : bound(sampleText, MAX_SAMPLE);
            this.source = source == null || source.trim().isEmpty() ? "Selected record" : bound(source.trim(), 200);
        }
        /** Chat rule proposal; {@code mode} is TEXT_CONTAINS or SPACE_TOKEN (or null for sample only). */
        public static Draft chat(Mode mode, String value, String sampleMessage, String source) {
            return new Draft(Domain.CHAT, mode, value, null, sampleMessage, source);
        }
        /** Item rule proposal; {@code mode} is ITEM_ID or NAME_CONTAINS (or null). The sample name may be null. */
        public static Draft item(Mode mode, String value, int sampleItemId, String sampleName, String source) {
            return new Draft(Domain.ITEM, mode, value, sampleItemId, sampleName, source);
        }
        /** Exact entity-type rule proposal for an observed type. */
        public static Draft entity(int sampleType, String source) {
            return new Draft(Domain.ENTITY, Mode.ENTITY_TYPE, Integer.toString(sampleType), sampleType, null, source);
        }
        /** Validated proposed rule, or null for a sample-only draft. Throws IllegalArgumentException when invalid. */
        public Rule proposedRule() { return mode == null ? null : Rule.of(mode, value); }
        private static String bound(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
        @Override public String toString() { return "Draft{" + domain + " " + mode + " from " + source + "}"; }
    }
    public static final class Match {
        public final boolean matched;
        public final String explanation;
        public final int ruleIndex;
        private Match(boolean matched, String explanation, int ruleIndex) {
            this.matched = matched; this.explanation = explanation; this.ruleIndex = ruleIndex;
        }
    }
    public static final class Snapshot {
        public final Domain domain;
        public final List<Rule> rules;
        public final String problem;
        private final String source, legacySource;
        private final JsonObject envelope;
        private Snapshot(Domain domain, String source, String legacySource, JsonObject envelope, List<Rule> rules, String problem) {
            this.domain = domain; this.source = source; this.legacySource = legacySource;
            this.envelope = envelope; this.rules = Collections.unmodifiableList(new ArrayList<>(rules)); this.problem = problem;
        }
        public boolean editable() { return problem.isEmpty(); }
        /** Index of the first supported rule with this exact mode and value, or -1. Used to resolve recorded rules safely. */
        public int indexOf(Mode mode, String value) {
            if (mode == null || value == null) return -1;
            for (int i = 0; i < rules.size(); i++) if (rules.get(i).mode == mode && value.equals(rules.get(i).value)) return i;
            return -1;
        }
        public Match matchItem(int id, String name) { return match(Domain.ITEM, id, name); }
        public Match matchEntityType(int type) { return match(Domain.ENTITY, type, null); }
        public Match matchChat(String text) { return match(Domain.CHAT, -1, text); }
        public Snapshot withRules(List<Rule> draft) {
            for (Rule rule : draft) if (rule.mode != null && rule.mode.domain != domain)
                throw new IllegalArgumentException("Rule mode belongs to another alert category.");
            return new Snapshot(domain, source, legacySource, envelope, draft, problem);
        }
        private Match match(Domain requested, int id, String text) {
            if (requested != domain) throw new IllegalArgumentException("Wrong alert category.");
            if (!editable()) return new Match(false, problem, -1);
            String last = "No rules matched.";
            for (int i = 0; i < rules.size(); i++) {
                Rule rule = rules.get(i);
                if (!rule.supported()) { last = "Unsupported rule retained without evaluation."; continue; }
                boolean matched = false; String value = rule.value;
                switch (rule.mode) {
                    case ITEM_ID: case ENTITY_TYPE:
                        matched = id == Integer.parseInt(value);
                        last = "Observed ID " + id + (matched ? " equals " : " differs from ") + value + "."; break;
                    case NAME_CONTAINS: case TEXT_CONTAINS:
                        matched = text != null && text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
                        last = text == null ? "Name unavailable; name rule cannot match." : "Literal case-insensitive contains: " + value; break;
                    case SPACE_TOKEN:
                        matched = token(text, value, Locale.ROOT);
                        last = "Space-delimited token: " + value + ". Punctuation, tabs and line breaks stay in tokens."; break;
                    case LEGACY_ITEM:
                        matched = Integer.toString(id).toLowerCase().contains(value.toLowerCase())
                            || (text != null && text.toLowerCase().contains(value.toLowerCase()));
                        last = "Legacy substring in decimal ID or name: " + value; break;
                    case LEGACY_ENTITY:
                        matched = Integer.toString(id).equals(value); last = "Legacy decimal text equality: " + value; break;
                    case LEGACY_CHAT:
                        if (!value.trim().isEmpty() && text != null) {
                            matched = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                                ? token(text, value.substring(1, value.length() - 1), Locale.getDefault())
                                : text.toLowerCase().contains(value.toLowerCase());
                        }
                        last = "Legacy chat: quotes mean tokens split only at spaces; punctuation is literal."; break;
                }
                if (matched) return new Match(true, "Matched rule " + (i + 1) + ": " + last, i);
            }
            return new Match(false, "No match. " + last, -1);
        }
    }
    public static final class Submission {
        public final Snapshot active;
        public final CompletionStage<PreferencesStore.SaveResult> completion;
        private Submission(Snapshot active, CompletionStage<PreferencesStore.SaveResult> completion) {
            this.active = active; this.completion = completion;
        }
    }
    private static final AlertRules APPLICATION = new AlertRules(PropertiesManager::getProperty, PropertiesManager::setPropertiesAsync);
    public static AlertRules application() { return APPLICATION; }
    private final Function<String, String> read;
    private final BiFunction<String, String, CompletionStage<PreferencesStore.SaveResult>> write;
    private final Map<Domain, Snapshot> cache = new EnumMap<>(Domain.class);
    public AlertRules(Function<String, String> read, BiFunction<String, String, CompletionStage<PreferencesStore.SaveResult>> write) {
        this.read = read; this.write = write;
    }
    public synchronized Snapshot snapshot(Domain domain, Collection<String> legacy) {
        String source = read.apply(domain.key()), legacySource = read.apply(domain.legacyKey);
        if (source != null) {
            Snapshot cached = cache.get(domain);
            if (cached != null && Objects.equals(source, cached.source)) return cached;
            Snapshot parsed = decode(domain, source, legacySource); cache.put(domain, parsed); return parsed;
        }
        List<Rule> rules = new ArrayList<>();
        Mode mode = domain == Domain.ITEM ? Mode.LEGACY_ITEM : domain == Domain.ENTITY ? Mode.LEGACY_ENTITY : Mode.LEGACY_CHAT;
        if (legacy != null) for (String value : legacy) if (value != null) rules.add(Rule.of(mode, value));
        return new Snapshot(domain, null, legacySource, new JsonObject(), rules, "");
    }
    /** The stored pre-typed rule list for a domain (the same "§"-delimited preference TomatoData loads). Read only. */
    public List<String> storedLegacy(Domain domain) {
        String saved = read.apply(domain.legacyKey);
        List<String> result = new ArrayList<>();
        if (saved != null) for (String value : saved.split("§")) if (!value.isEmpty()) result.add(value);
        return result;
    }
    public Match matchItem(Collection<String> legacy, int id, String name) { return snapshot(Domain.ITEM, legacy).matchItem(id, name); }
    public Match matchEntityType(Collection<String> legacy, int type) { return snapshot(Domain.ENTITY, legacy).matchEntityType(type); }
    public Match matchChat(Collection<String> legacy, String text) { return snapshot(Domain.CHAT, legacy).matchChat(text); }
    public synchronized boolean isCurrent(Snapshot snapshot) {
        return Objects.equals(snapshot.source, read.apply(snapshot.domain.key()))
            && (snapshot.source != null || Objects.equals(snapshot.legacySource, read.apply(snapshot.domain.legacyKey)));
    }
    public synchronized Submission save(Snapshot base, List<Rule> draft) {
        if (!base.editable()) throw new IllegalStateException(base.problem);
        if (!isCurrent(base)) throw new IllegalStateException("Rules changed in another editor. Reopen to load current rules; your draft is retained here.");
        Snapshot next = base.withRules(draft);
        JsonObject envelope = base.envelope.deepCopy(); envelope.addProperty("version", 1);
        JsonArray rules = new JsonArray(); for (Rule rule : next.rules) rules.add(rule.raw.deepCopy()); envelope.add("rules", rules);
        String encoded = asciiJson(envelope);
        CompletionStage<PreferencesStore.SaveResult> completion = write.apply(base.domain.key(), encoded);
        if (!Objects.equals(encoded, read.apply(base.domain.key()))) throw new IllegalStateException("Preference store did not accept the change; draft retained.");
        return new Submission(snapshot(base.domain, Collections.emptyList()), completion);
    }
    private static Snapshot decode(Domain domain, String source, String legacySource) {
        try {
            JsonObject envelope = JsonParser.parseString(source).getAsJsonObject();
            if (!envelope.has("version") || !"1".equals(envelope.get("version").toString()) || !envelope.get("rules").isJsonArray())
                throw new IllegalArgumentException();
            List<Rule> rules = new ArrayList<>();
            for (JsonElement raw : envelope.getAsJsonArray("rules")) {
                Rule rule;
                try {
                    JsonObject object = raw.getAsJsonObject();
                    if (!object.get("mode").isJsonPrimitive() || !object.get("mode").getAsJsonPrimitive().isString()
                            || !object.get("value").isJsonPrimitive() || !object.get("value").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                    Mode mode = Mode.valueOf(object.get("mode").getAsString());
                    if (mode.domain != domain) throw new IllegalArgumentException();
                    Rule valid = Rule.of(mode, object.get("value").getAsString());
                    rule = new Rule(raw, mode, valid.value);
                } catch (RuntimeException invalid) { rule = new Rule(raw, null, raw.toString()); }
                rules.add(rule);
            }
            return new Snapshot(domain, source, legacySource, envelope, rules, "");
        } catch (RuntimeException invalid) {
            return new Snapshot(domain, source, legacySource, new JsonObject(), Collections.emptyList(),
                "Unreadable or future rule format — original data preserved. Editing and matching unavailable for this category.");
        }
    }
    private static boolean token(String text, String value, Locale locale) {
        if (text == null) return false;
        for (String part : text.toLowerCase(locale).split(" ")) if (part.equals(value.toLowerCase(locale))) return true;
        return false;
    }
    // PreferencesStore intentionally retains the legacy platform charset. JSON escapes keep
    // non-ASCII rule text and unknown fields lossless through that existing file format.
    private static String asciiJson(JsonObject object) {
        StringBuilder encoded = new StringBuilder();
        for (char c : object.toString().toCharArray()) {
            if (c <= 127) encoded.append(c);
            else { String hex = "0000" + Integer.toHexString(c); encoded.append("\\u").append(hex.substring(hex.length() - 4)); }
        }
        return encoded.toString();
    }
}
