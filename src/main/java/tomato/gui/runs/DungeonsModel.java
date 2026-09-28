package tomato.gui.runs;

import java.util.List;

/**
 * The Dungeons tab's cards as last read ({@link DungeonsSource#read}). Immutable; the EDT applies it.
 *
 * @param cards           one card per canonical dungeon matching the query, in its order
 * @param runs            every counted saved dungeon run of every readable session, before the text filter: 0 is a history
 *                        without dungeon runs, while no cards with runs above 0 is a filter without a match
 * @param sessionsSkipped saved sessions left out whole: their metadata or their saved runs could not be read
 * @param issues          why the cards may be partial, one line each: a skipped session, or a session's loot or combat records
 *                        that could not be read ("&lt;session&gt;: loot could not be read (IOException)"), or saved runs
 *                        without a visit ID; never a path. Empty when everything was read
 * @param capturedAt      the source's clock when the read finished (epoch ms)
 */
public record DungeonsModel(List<DungeonCardModel> cards, int runs, int sessionsSkipped, List<String> issues, long capturedAt) {
    public DungeonsModel {
        cards = List.copyOf(cards);
        issues = List.copyOf(issues);
    }
}
