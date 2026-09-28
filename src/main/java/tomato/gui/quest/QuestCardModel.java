package tomato.gui.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * One quest card: everything the painted card and the detail drawer show, built off the EDT from the page's detached quest copy.
 * Lists are immutable (List.copyOf). Rewards and requirements aggregate repeated ids into counts in first-seen order (a repeated id
 * in the packet means quantity, as QuestGUI.quantities). A {@code null} list in the packet gives {@code rewardsKnown} /
 * {@code requirementsKnown} false and an empty item list: the card says "not captured", never "none"; a captured empty list stays
 * known. {@code category} is the server's category number (with id and name it is the pin key, QuestPins.key); {@code typeLabel} is
 * the user's own label for it, trimmed, "" when unlabeled (no chip). {@code rawExpiration} is the server's value verbatim, "" when not
 * supplied: it is never parsed (the countdown is deferred) and only the Analyst detail shows it.
 */
record QuestCardModel(String id, String name, boolean pinned, boolean repeatable, boolean completed, boolean choice, int category,
                      String typeLabel, QuestTier tier, List<Item> rewards, boolean rewardsKnown,
                      List<Item> requirements, boolean requirementsKnown, String description, String rawExpiration) {
    static final String REPEATABLE = "↻ Repeatable", ONE_TIME = "One-time", DONE = "✓ Done";

    QuestCardModel {
        id = text(id); name = text(name); description = text(description); rawExpiration = text(rawExpiration);
        typeLabel = typeLabel == null ? "" : typeLabel.trim();
        Objects.requireNonNull(tier, "tier");
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /**
     * An item id with its count and its display name ("Unknown item #id" without assets). The sprite border's tier label is resolved
     * at paint time (ItemTiers.label, constant time on the EDT), so definitions that load after the model was built still show.
     */
    record Item(int id, int count, String name) {
        Item { name = text(name); }
    }

    /** "↻ Repeatable" or "One-time", plus "✓ Done" when completed. */
    List<String> badges() {
        String kind = repeatable ? REPEATABLE : ONE_TIME;
        return completed ? List.of(kind, DONE) : List.of(kind);
    }

    /** Card header for rewards: "You get", or "Pick 1 of N" when choice (N distinct reward items); "Pick 1" when rewards are unknown. */
    String rewardsTitle() {
        if (!choice) return "You get";
        return rewardsKnown ? "Pick 1 of " + rewards.stream().mapToInt(Item::id).distinct().count() : "Pick 1";
    }

    /** The card for {@code quest}; {@code pinned} and {@code typeLabel} are the page's (pins and "Name types…" labels). */
    static QuestCardModel of(QuestGUI.Quest quest, boolean pinned, String typeLabel, IntFunction<String> names) {
        return new QuestCardModel(quest.id, quest.name, pinned, quest.repeatable, quest.completed, quest.choice, quest.category, typeLabel,
            QuestTier.of(quest, names), items(quest.rewards, names), quest.rewardsKnown, items(quest.requirements, names),
            quest.requirementsKnown, quest.description, quest.expiration);
    }

    /** The display name QuestGUI shows: the lookup's name, or "Unknown item #id" when it has none or fails. */
    static String itemName(IntFunction<String> names, int id) {
        try { String name = names.apply(id); return name == null || name.isEmpty() ? "Unknown item #" + id : name; }
        catch (RuntimeException e) { return "Unknown item #" + id; }
    }

    private static List<Item> items(int[] ids, IntFunction<String> names) {
        List<Item> items = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : QuestGUI.quantities(ids).entrySet()) items.add(new Item(e.getKey(), e.getValue(), itemName(names, e.getKey())));
        return items;
    }

    private static String text(String value) { return value == null ? "" : value; }
}
