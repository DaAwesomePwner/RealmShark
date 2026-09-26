package tomato.realmshark;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ALERT-4: bounded, detached, in-memory history of alert decisions made at the actual decision
 * points (chat gating and precedence, realm-event cooldown, item/entity/enchant/key-pop matching and
 * Sound enable/mute/volume/playback). Records hold only strings and numbers, are never persisted and
 * are never replayed. Asynchronous playback results are correlated by decision ID.
 */
public final class AlertDecisions {
    public enum Source {
        CHAT("Chat"), REALM_EVENT("Realm event"), ITEM("Item drop"), ENTITY("Entity"), KEY_POP("Key pop"), DIRECT("Alert sound");
        public final String label;
        Source(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public enum Result {
        NO_MATCH("No match"), RULES_UNAVAILABLE("Rules unavailable"), IGNORED("Matched but ignored"),
        COOLDOWN("Matched · cooldown"), SOUND_OFF("Matched · alert off"), MUTED("Matched but muted"),
        SILENT_VOLUME("Matched · volume 0"), SUBMITTED("Matched · playback pending"), PLAYED("Played"),
        UNAVAILABLE("Playback unavailable"), BUSY("Skipped · audio busy");
        public final String label;
        Result(String label) { this.label = label; }
        @Override public String toString() { return label; }
        /** A rule or channel matched but a local setting deliberately kept it silent. */
        public boolean suppressed() { return this == IGNORED || this == COOLDOWN || this == SOUND_OFF || this == MUTED || this == SILENT_VOLUME; }
        public boolean failed() { return this == UNAVAILABLE || this == BUSY; }
    }
    /** Immutable record. Rule fields describe the rule as it was when the decision was made. */
    public static final class Decision {
        public final long id, time;
        public final Source source;
        public final Result result;
        /** Sound profile ID/label, or null when no sound was selected. */
        public final String sound, soundLabel;
        /** Typed rule identity at decision time; domain/mode null for non-AlertRules sources. */
        public final AlertRules.Domain domain;
        public final AlertRules.Mode ruleMode;
        public final String ruleValue;
        public final int ruleIndex;
        /** Other exact reference: realm-event rule ID or dungeon name. */
        public final String ruleRef;
        /** Bounded description of the observation (e.g. "World · Ann"). */
        public final String subject;
        /** Detached sample for drafting (message text or item name) and optional sample ID. */
        public final String sample;
        public final Integer sampleId;
        public final String explanation, playback;
        private Decision(long id, long time, Source source, Result result, String sound, String soundLabel, AlertRules.Domain domain,
                         AlertRules.Mode ruleMode, String ruleValue, int ruleIndex, String ruleRef, String subject, String sample,
                         Integer sampleId, String explanation, String playback) {
            this.id = id; this.time = time; this.source = source; this.result = result; this.sound = sound; this.soundLabel = soundLabel;
            this.domain = domain; this.ruleMode = ruleMode; this.ruleValue = ruleValue; this.ruleIndex = ruleIndex; this.ruleRef = ruleRef;
            this.subject = bound(subject, 160); this.sample = sample == null ? null : bound(sample, 500); this.sampleId = sampleId;
            this.explanation = bound(explanation, 500); this.playback = bound(playback, 300);
        }
        Decision with(Result next, String detail) {
            return new Decision(id, time, source, next, sound, soundLabel, domain, ruleMode, ruleValue, ruleIndex, ruleRef, subject, sample, sampleId, explanation, detail);
        }
        public boolean hasRule() { return ruleMode != null || ruleRef != null; }
        public String ruleLabel() {
            if (ruleMode != null) return "Rule " + (ruleIndex + 1) + " · " + ruleMode.label + " “" + ruleValue + "”";
            if (ENCHANT_REF.equals(ruleRef)) return "Selected enchantment";
            if (ruleRef != null) return source == Source.KEY_POP ? "Dungeon · " + ruleRef : "Realm event rule";
            return soundLabel == null ? "" : soundLabel;
        }
    }
    /** Detached description of a match to record before playback. */
    public static final class Entry {
        final Source source; Result result = Result.SUBMITTED; Sound sound; AlertRules.Domain domain; AlertRules.Mode mode; String value;
        int index = -1; String ref, subject = "", sample, explanation = ""; Integer sampleId;
        public Entry(Source source) { this.source = Objects.requireNonNull(source); }
        public Entry result(Result value) { result = value; return this; }
        public Entry sound(Sound value) { sound = value; return this; }
        public Entry rule(AlertRules.Domain domain, AlertRules.Rule rule, int index) {
            this.domain = domain; if (rule != null && rule.supported()) { mode = rule.mode; value = rule.value; this.index = index; } return this;
        }
        public Entry ref(String value) { ref = value; return this; }
        public Entry subject(String value) { subject = value == null ? "" : value; return this; }
        public Entry sample(String text, Integer id) { sample = text; sampleId = id; return this; }
        public Entry explain(String value) { explanation = value == null ? "" : value; return this; }
    }

    public static final int CAPACITY = 200, NO_MATCH_CAPACITY = 100;
    /** {@link Decision#ruleRef} of an item decision made by the selected-enchantment list. */
    public static final String ENCHANT_REF = "enchantments";
    public static final AlertDecisions INSTANCE = new AlertDecisions();
    private final ArrayDeque<Decision> decisions = new ArrayDeque<>(), noMatches = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long sequence, evicted;

    AlertDecisions() {}

    /** Records a decision and returns its ID (never 0). No-match and unavailable-rule results use their own ring. */
    public long record(Entry entry) {
        Decision decision;
        synchronized (this) {
            decision = new Decision(++sequence, System.currentTimeMillis(), entry.source, entry.result,
                entry.sound == null ? null : entry.sound.id, entry.sound == null ? null : entry.sound.label, entry.domain,
                entry.mode, entry.value, entry.index, entry.ref, entry.subject, entry.sample, entry.sampleId, entry.explanation, "");
            ArrayDeque<Decision> ring = quiet(entry.result) ? noMatches : decisions;
            ring.addLast(decision);
            while (ring.size() > (ring == noMatches ? NO_MATCH_CAPACITY : CAPACITY)) { ring.removeFirst(); evicted++; }
        }
        changed(); return decision.id;
    }
    /** Correlates an asynchronous outcome with its decision; ignores ID 0 (Test/preview) and evicted IDs. */
    public void complete(long id, Result result, String detail) {
        if (id == 0) return;
        boolean found = false;
        synchronized (this) {
            for (ArrayDeque<Decision> ring : Arrays.asList(decisions, noMatches)) {
                List<Decision> copy = new ArrayList<>(ring);
                for (int i = 0; i < copy.size(); i++) if (copy.get(i).id == id) {
                    copy.set(i, copy.get(i).with(result, detail)); ring.clear(); ring.addAll(copy); found = true; break;
                }
                if (found) break;
            }
        }
        if (found) changed();
    }
    /** Newest first. */
    public synchronized List<Decision> snapshot(boolean includeNoMatch) {
        List<Decision> result = new ArrayList<>(decisions);
        if (includeNoMatch) result.addAll(noMatches);
        result.sort((a, b) -> Long.compare(b.id, a.id));
        return result;
    }
    public synchronized Decision find(long id) {
        for (Decision d : decisions) if (d.id == id) return d;
        for (Decision d : noMatches) if (d.id == id) return d;
        return null;
    }
    public synchronized long evicted() { return evicted; }
    public synchronized void clear() { decisions.clear(); noMatches.clear(); changed(); }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : listeners) listener.run(); }
    private static boolean quiet(Result result) { return result == Result.NO_MATCH || result == Result.RULES_UNAVAILABLE; }
    private static String bound(String text, int max) { if (text == null) return ""; return text.length() <= max ? text : text.substring(0, max - 1) + "…"; }

    /**
     * Decision point for entity spawns (hook for TomatoData.customSoundAlert). Only matches are
     * recorded: every spawn would otherwise flood the no-match history. Returns the ID or 0.
     */
    public static long entityAlert(Collection<String> legacyEntityRules, int entityType) {
        return entityAlert(AlertRules.application(), legacyEntityRules, entityType);
    }
    public static long entityAlert(AlertRules service, Collection<String> legacyEntityRules, int entityType) {
        AlertRules.Snapshot rules = service.snapshot(AlertRules.Domain.ENTITY, legacyEntityRules);
        AlertRules.Match match = rules.matchEntityType(entityType);
        if (!match.matched) return 0;
        return INSTANCE.record(new Entry(Source.ENTITY).sound(Sound.custom).rule(AlertRules.Domain.ENTITY, rules.rules.get(match.ruleIndex), match.ruleIndex)
            .subject("Entity type " + entityType).sample(null, entityType).explain(match.explanation));
    }
    /**
     * Decision point for one dropped item (hook for LootGUI.notifyItems): item rules first, then the
     * selected-enchantment result the caller computed. Records a no-match too. Returns the ID or 0.
     */
    public static long lootItem(Collection<String> legacyItemRules, int itemId, String itemName, String enchantText, boolean enchantMatched) {
        return lootItem(AlertRules.application(), legacyItemRules, itemId, itemName, enchantText, enchantMatched);
    }
    public static long lootItem(AlertRules service, Collection<String> legacyItemRules, int itemId, String itemName, String enchantText, boolean enchantMatched) {
        AlertRules.Snapshot rules = service.snapshot(AlertRules.Domain.ITEM, legacyItemRules);
        AlertRules.Match match = rules.matchItem(itemId, itemName);
        String subject = (itemName == null ? "Item" : itemName) + " (#" + itemId + ")";
        Entry entry = new Entry(Source.ITEM).subject(subject).sample(itemName, itemId);
        if (match.matched) {
            entry.sound(Sound.custom).rule(AlertRules.Domain.ITEM, rules.rules.get(match.ruleIndex), match.ruleIndex)
                .explain(match.explanation + (enchantMatched ? " A selected enchantment also matched." : ""));
        } else if (enchantMatched) {
            entry.sound(Sound.custom).rule(AlertRules.Domain.ITEM, null, -1).ref(ENCHANT_REF)
                .explain("A selected enchantment matched: " + firstLine(enchantText) + ". " + match.explanation);
        } else {
            entry.result(rules.editable() ? Result.NO_MATCH : Result.RULES_UNAVAILABLE).rule(AlertRules.Domain.ITEM, null, -1)
                .explain(match.explanation + (enchantText == null || enchantText.isEmpty() ? "" : " No selected enchantment matched."));
        }
        long id = INSTANCE.record(entry);
        return match.matched || enchantMatched ? id : 0;
    }
    private static String firstLine(String text) {
        if (text == null) return "";
        String[] lines = text.trim().split("\\R");
        return lines.length == 1 ? lines[0] : lines[0] + " (+" + (lines.length - 1) + " more)";
    }
}
