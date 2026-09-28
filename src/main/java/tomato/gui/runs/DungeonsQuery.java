package tomato.gui.runs;

import java.util.Comparator;
import java.util.Locale;

/**
 * What the Dungeons tab shows: a text filter over the dungeon's name and the cards' order. Immutable; null text is "" and a
 * null sort is {@link Sort#MOST_VISITS}.
 *
 * @param text words the card's name (display or canonical) contains, ignoring case and surrounding spaces; "" is every card
 */
public record DungeonsQuery(String text, Sort sort) {
    /** The cards' order; ties go by name, then canonical name. */
    public enum Sort {
        MOST_VISITS("Most visits"), RECENT("Most recent"), NAME("Name");

        private final String label;

        Sort(String label) { this.label = label; }

        /** The sort control's text. */
        public String label() { return label; }
    }

    public DungeonsQuery {
        text = text == null ? "" : text.trim();
        sort = sort == null ? Sort.MOST_VISITS : sort;
    }

    /** Every card, most visited first. */
    public static DungeonsQuery all() { return new DungeonsQuery("", Sort.MOST_VISITS); }

    /** Whether {@code card}'s name contains the text (ignoring case). */
    public boolean matches(DungeonCardModel card) {
        if (text.isEmpty()) return true;
        String words = text.toLowerCase(Locale.ROOT);
        return card.displayName().toLowerCase(Locale.ROOT).contains(words) || card.canonical().toLowerCase(Locale.ROOT).contains(words);
    }

    /** The cards' order for {@link #sort}. */
    public Comparator<DungeonCardModel> order() {
        Comparator<DungeonCardModel> byName = Comparator.comparing((DungeonCardModel card) -> card.displayName().toLowerCase(Locale.ROOT))
            .thenComparing(DungeonCardModel::canonical);
        return switch (sort) {
            case MOST_VISITS -> Comparator.comparingInt(DungeonCardModel::visits).reversed().thenComparing(byName);
            case RECENT -> Comparator.comparingLong(DungeonCardModel::lastVisit).reversed().thenComparing(byName);
            case NAME -> byName;
        };
    }
}
