package tomato.gui.quest;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import tomato.gui.modern.DisplayFormat;

/**
 * The Board: groups of cards and the summary line. Pure and immutable: built off the EDT from card models, applied on the EDT.
 * Groups keep a fixed order and omit empty ones: TIER in QuestTier order; TYPE by the user's labels alphabetically ignoring case,
 * then "No type label"; NONE one "All quests" group. Within a group, pinned cards come first when {@code pinnedFirst}, otherwise
 * (and among equals) the input order, which is the Board sort's.
 */
record QuestBoardModel(List<Group> groups, Summary summary) {
    enum GroupBy { TIER, TYPE, NONE }

    /**
     * One group. Keys: the tier's key (mighty, epic, standard, beginner, other-chest, no-chest, not-captured); "type-" + category id,
     * or "no-type";
     * "all". Categories the user gave the same label share one group (as the type filter matches by label), keyed by the lowest id.
     */
    record Group(String key, String title, List<QuestCardModel> cards) {
        Group { Objects.requireNonNull(key, "key"); Objects.requireNonNull(title, "title"); cards = List.copyOf(cards); }
    }

    /** Total quests, pinned, repeatable, done over the given cards; capturedAt epoch ms (0 unknown); stale per the page's existing rule. */
    record Summary(int total, int pinned, int repeatable, int done, long capturedAt, boolean stale) {
        /** "14 quests · 3 pinned · captured 14 min ago" (+ " · stale" when stale); "No quest list captured yet" when none. */
        String text(LongSupplier clock) {
            if (capturedAt <= 0) return "No quest list captured yet";
            return total + (total == 1 ? " quest" : " quests") + " · " + pinned + " pinned · captured " + relative(capturedAt, clock.getAsLong())
                + (stale ? " · stale" : "");
        }
    }

    static final String NO_TYPE_KEY = "no-type", NO_TYPE_TITLE = "No type label", ALL_KEY = "all", ALL_TITLE = "All quests";
    /** Labels alphabetically ignoring case; labels differing only in case stay apart (the type filter compares exactly). */
    private static final Comparator<String> LABELS = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    QuestBoardModel { groups = List.copyOf(groups); Objects.requireNonNull(summary, "summary"); }

    static QuestBoardModel build(List<QuestCardModel> cards, GroupBy by, boolean pinnedFirst, long capturedAt, boolean stale) {
        Objects.requireNonNull(by, "by");
        List<QuestCardModel> all = cards == null ? List.of() : List.copyOf(cards);
        List<Group> groups = new ArrayList<>();
        switch (by) {
            case TIER:
                for (QuestTier tier : QuestTier.values()) {
                    List<QuestCardModel> members = new ArrayList<>();
                    for (QuestCardModel card : all) if (card.tier() == tier) members.add(card);
                    add(groups, tier.key, tier.label, members, pinnedFirst);
                }
                break;
            case TYPE:
                TreeMap<String, List<QuestCardModel>> labeled = new TreeMap<>(LABELS);
                Map<String, Integer> lowest = new HashMap<>();
                List<QuestCardModel> unlabeled = new ArrayList<>();
                for (QuestCardModel card : all) {
                    if (card.typeLabel().isEmpty()) { unlabeled.add(card); continue; }
                    labeled.computeIfAbsent(card.typeLabel(), label -> new ArrayList<>()).add(card);
                    lowest.merge(card.typeLabel(), card.category(), Math::min);
                }
                for (Map.Entry<String, List<QuestCardModel>> e : labeled.entrySet())
                    add(groups, "type-" + lowest.get(e.getKey()), e.getKey(), e.getValue(), pinnedFirst);
                add(groups, NO_TYPE_KEY, NO_TYPE_TITLE, unlabeled, pinnedFirst);
                break;
            default:
                add(groups, ALL_KEY, ALL_TITLE, all, pinnedFirst);
        }
        int pinned = 0, repeatable = 0, done = 0;
        for (QuestCardModel card : all) { if (card.pinned()) pinned++; if (card.repeatable()) repeatable++; if (card.completed()) done++; }
        return new QuestBoardModel(groups, new Summary(all.size(), pinned, repeatable, done, Math.max(0, capturedAt), stale));
    }

    private static void add(List<Group> groups, String key, String title, List<QuestCardModel> cards, boolean pinnedFirst) {
        if (cards.isEmpty()) return;
        List<QuestCardModel> ordered = new ArrayList<>(cards);
        if (pinnedFirst) ordered.sort(Comparator.comparingInt(card -> card.pinned() ? 0 : 1)); // stable: input order among equals
        groups.add(new Group(key, title, ordered));
    }

    /** KitFormat.relative's wording against an injected clock (KitFormat keeps its own static clock); keep the two in step. */
    static String relative(long epochMillis, long now) {
        long minutes = Math.max(0, now - epochMillis) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        return DisplayFormat.DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
