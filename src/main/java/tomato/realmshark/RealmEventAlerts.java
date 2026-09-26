package tomato.realmshark;

import com.google.gson.Gson;
import packets.incoming.TextPacket;
import util.PropertiesManager;
import java.util.*;
import java.util.regex.Pattern;

/** Local, opt-in matching of public realm announcements. No message bodies are retained. */
public final class RealmEventAlerts {
    public static final RealmEventAlerts INSTANCE = new RealmEventAlerts();
    private static final long COOLDOWN_NANOS = 30_000_000_000L;
    private static final Pattern DEFEAT = Pattern.compile("\\b(slain|defeated|killed|destroyed|vanquished|dead|fallen)\\b");
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, Long> lastMatch = new HashMap<>();
    private volatile String lastMatchLabel = "No selected realm announcement matched this session.";

    public static final class Rule {
        public final String id, name;
        public final Sound sound;
        private volatile String phrase;
        Rule(String id, String name, String phrase) {
            this.id = id; this.name = name; this.phrase = phrase; sound = Sound.realmEvent(id, name);
        }
        public String getPhrase() { return phrase; }
    }
    private static final class SavedRule { String id, name, phrase; }
    RealmEventAlerts() {
        String saved = PropertiesManager.getProperty("sound.realm.rules");
        if (saved != null) {
            try {
                SavedRule[] loaded = new Gson().fromJson(saved, SavedRule[].class);
                if (loaded != null) for (SavedRule r : loaded) {
                    if (r != null && r.id != null && r.id.matches("[a-zA-Z0-9-]{1,64}") && valid(r.name, 80) && valid(r.phrase, 160)
                            && rules.size() < 32 && rules.stream().noneMatch(existing -> existing.id.equals(r.id)))
                        rules.add(new Rule(r.id, r.name.trim(), r.phrase.trim()));
                }
                if (loaded != null) return;
            } catch (RuntimeException ignored) { }
        }
        rules.add(new Rule("cube-god", "Cube God", "cube god"));
        rules.add(new Rule("legion-general", "Legion General", "legion general"));
    }
    private static boolean valid(String value, int max) { return value != null && !value.trim().isEmpty() && value.length() <= max; }
    public synchronized List<Rule> getRules() { return new ArrayList<>(rules); }
    public synchronized Rule add(String name, String phrase) {
        if (!valid(name, 80) || !valid(phrase, 160)) throw new IllegalArgumentException("Enter a name (up to 80 characters) and a phrase (up to 160).");
        if (rules.size() >= 32) throw new IllegalArgumentException("Up to 32 realm event rules are supported.");
        Rule rule = new Rule(UUID.randomUUID().toString(), name.trim(), phrase.trim());
        rules.add(rule); save(); return rule;
    }
    public synchronized void setPhrase(Rule rule, String phrase) {
        if (!valid(phrase, 160)) throw new IllegalArgumentException("Enter a non-empty announcement phrase, up to 160 characters.");
        rule.phrase = phrase.trim(); lastMatch.remove(rule.id); save();
    }
    public synchronized void remove(Rule rule) { rules.remove(rule); rule.sound.dispose(); lastMatch.remove(rule.id); save(); }
    private void save() {
        List<SavedRule> saved = new ArrayList<>();
        for (Rule rule : rules) {
            SavedRule r = new SavedRule(); r.id = rule.id; r.name = rule.name; r.phrase = rule.phrase; saved.add(r);
        }
        PropertiesManager.setProperties("sound.realm.rules", new Gson().toJson(saved));
    }
    public synchronized void resetCooldowns() { lastMatch.clear(); }
    public String getLastMatchLabel() { return lastMatchLabel; }
    public void accept(TextPacket packet, String mapName) {
        for (Hit hit : evaluate(packet, mapName, System.nanoTime())) hit.rule.sound.play(hit.decision);
    }
    synchronized List<Rule> matching(TextPacket packet, String mapName, long now) {
        List<Rule> result = new ArrayList<>();
        for (Hit hit : evaluate(packet, mapName, now)) result.add(hit.rule);
        return result;
    }
    private static final class Hit {
        final Rule rule; final long decision;
        Hit(Rule rule, long decision) { this.rule = rule; this.decision = decision; }
    }
    /** Decision point: records phrase matches, disabled rules, cooldown and defeat-message suppression. */
    private synchronized List<Hit> evaluate(TextPacket packet, String mapName, long now) {
        if (!isRealmAnnouncement(packet, mapName)) return Collections.emptyList();
        String message = normalize(packet.text);
        boolean defeat = DEFEAT.matcher(message).find();
        List<Hit> matches = new ArrayList<>();
        boolean phraseMatched = false;
        for (Rule rule : rules) {
            if (!containsPhrase(message, normalize(rule.phrase))) continue;
            phraseMatched = true;
            AlertDecisions.Entry entry = new AlertDecisions.Entry(AlertDecisions.Source.REALM_EVENT).sound(rule.sound).ref(rule.id)
                .subject(rule.name + " · announcement").sample(packet.text, null);
            if (defeat) {
                AlertDecisions.INSTANCE.record(entry.result(AlertDecisions.Result.NO_MATCH)
                    .explain("Phrase “" + rule.phrase + "” matched, but defeat announcements are skipped."));
                continue;
            }
            if (!rule.sound.isEnabled()) {
                AlertDecisions.INSTANCE.record(entry.result(AlertDecisions.Result.SOUND_OFF)
                    .explain("Phrase “" + rule.phrase + "” matched; this realm event alert is turned off."));
                continue;
            }
            Long last = lastMatch.get(rule.id);
            if (last != null && now - last < COOLDOWN_NANOS) {
                AlertDecisions.INSTANCE.record(entry.result(AlertDecisions.Result.COOLDOWN).explain("Phrase “" + rule.phrase + "” matched "
                    + Math.max(0, (now - last) / 1_000_000_000L) + " s after the previous match; repeats pause for 30 s."));
                continue;
            }
            lastMatch.put(rule.id, now);
            matches.add(new Hit(rule, AlertDecisions.INSTANCE.record(entry.explain("Phrase “" + rule.phrase + "” matched a public announcement."))));
            lastMatchLabel = "Last match: " + rule.name + " at " + java.time.LocalTime.now().withNano(0) + " (announcement).";
        }
        if (!phraseMatched) AlertDecisions.INSTANCE.record(new AlertDecisions.Entry(AlertDecisions.Source.REALM_EVENT).result(AlertDecisions.Result.NO_MATCH)
            .subject("Realm announcement").sample(packet.text, null).explain("No realm event phrase matched this announcement."));
        return matches;
    }
    static boolean isRealmAnnouncement(TextPacket packet, String mapName) {
        if (packet == null || packet.text == null || packet.name == null || packet.recipient == null) return false;
        if (!"Realm of the Mad God".equals(mapName) || !packet.recipient.trim().isEmpty()) return false;
        String sender = packet.name.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
        return sender.isEmpty() || sender.equals("#oryx") || sender.equals("#oryx the mad god") || sender.equals("#system");
    }
    static String normalize(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim(); }
    static boolean containsPhrase(String text, String phrase) {
        return !phrase.isEmpty() && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }
}
