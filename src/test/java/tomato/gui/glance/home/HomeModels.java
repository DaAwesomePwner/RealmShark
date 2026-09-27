package tomato.gui.glance.home;

import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.List;
import tomato.gui.dps.MeterSummary;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.kit.DisplayValue;
import tomato.history.link.VisitRef;

/** Synthetic Home view models for card, layout, timing, shell and evidence tests. No capture and no personal data. */
public final class HomeModels {
    public static final String SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e";
    public static final HomeActions NO_ACTIONS = new HomeActions(() -> {}, () -> {}, () -> {}, visit -> {}, () -> {});
    static final int WIZARD = 782, PRIEST = 784, WARRIOR = 797;
    private static final int[] CAPS = {720, 385, 75, 25, 50, 75, 40, 75};
    private HomeModels() {}
    public static HomeModel.Hero hero(HomeModel.State state, long now) { return hero(state, now, 7, 12); }
    public static HomeModel.Hero hero(HomeModel.State state, long now, int maxed, int exaltTiers) {
        boolean live = state == HomeModel.State.LIVE;
        int[] base = maxed >= 8 ? CAPS.clone() : new int[]{720, 385, 75, 25, 50, 75, 40, 60};
        int[] totals = new int[8];
        for (int i = 0; i < 8; i++) totals[i] = base[i] + (i == 0 ? 50 : 12);
        return new HomeModel.Hero(state, "Sharkbait", WIZARD, "Wizard", 0, 20,
            live ? DisplayValue.known("1,234", "Character fame from live stats") : DisplayValue.stale("1,234", "Last seen 2 h ago"),
            maxed, base, CAPS.clone(), totals, new int[]{0, 0, 0, 0, 0, 0, 0, maxed >= 8 ? 0 : 3},
            maxed >= 8 ? "" : "Needs WIS 3 potions", exaltTiers, new int[]{2593, 2856, 3113, 0},
            DisplayValue.estimate("1,480", "Average weapon damage × rate of fire against 0 DEF"),
            DisplayValue.estimate("18.4", "MP regeneration from WIS at the base rate"),
            "70 stars · 12,345 account fame · 1,200 gold", live ? 0L : now - 2 * 3_600_000L,
            "Live stats from the current map; caps from the class definition; estimates use the Build page's default scenario.");
    }
    public static HomeModel.Now now(long now) {
        return new HomeModel.Now(HomeModel.State.LIVE, true, "Lost Halls", now - 750_000L, List.of(
            new MeterSummary.Row("Ann", WARRIOR, "Warrior", 1_204_000L, 3_120.5, false),
            new MeterSummary.Row("Sharkbait", WIZARD, "Wizard", 980_000L, 2_540.2, true),
            new MeterSummary.Row("Bo", PRIEST, "Priest", 610_000L, 1_580.9, false)), 2, 8,
            new KeypopGUI.LastPop("Ann", "Lost Halls", Instant.ofEpochMilli(now - 60_000L)));
    }
    public static HomeArchive.Totals totals(HomeArchive.Window window, long now) {
        return new HomeArchive.Totals(window, now - (window == HomeArchive.Window.TODAY ? 6 : 2) * 3_600_000L, now, 4, 5, 1_234L, 540.0,
            new double[]{0, 40, 95, 180, 260, 340, 455, 610, 720, 860, 1_020, 1_234}, 2, 1, 3, 6, true);
    }
    public static HomeModel.Today today(HomeArchive.Window window, long now) {
        return new HomeModel.Today(HomeModel.State.LIVE, window, totals(window, now), null);
    }
    /** Newest first; the first run has nine loot ids (the card shows eight) and a linked DPS. */
    public static List<HomeArchive.RecentRun> runs(long now) {
        return List.of(
            run(5, "Lost Halls", "Completed", now - 1_620_000L, now - 720_000L, List.of(3180, 3181, 2591, 2592, 2593, 2594, 2595, 2596, 2597), 3_120.5),
            run(4, "The Shatters", "Left · completion unconfirmed", now - 5_400_000L, now - 4_200_000L, List.of(2591), null),
            run(3, "Ice Cave", "Completed", now - 9_000_000L, now - 8_100_000L, List.of(), 1_980.0),
            run(2, "Lost Halls", "Completed", now - 14_400_000L, now - 13_200_000L, List.of(3180, 2592), null),
            run(1, "Sprite World", "Left · completion unconfirmed", now - 90_000_000L, now - 89_700_000L, List.of(), null));
    }
    private static HomeArchive.RecentRun run(int id, String map, String outcome, long started, Long ended, List<Integer> loot, Double dps) {
        return new HomeArchive.RecentRun(new VisitRef(SESSION, "visit-" + id), map, outcome, started, ended, loot, dps);
    }
    public static HomeArchive.Result result(HomeArchive.Window window, long now) { return new HomeArchive.Result(totals(window, now), runs(now)); }
    public static HomeModel.Quests quests(long now, boolean stale) {
        return new HomeModel.Quests(stale ? HomeModel.State.STALE : HomeModel.State.LIVE, 3, 5, 2, List.of(
            new HomeModel.QuestLine("Mighty Lost Halls", new int[]{3180, 2591, 2592, 2593, 2594, 2595}, true, false),
            new HomeModel.QuestLine("Epic Shatters", new int[]{2596}, false, false),
            new HomeModel.QuestLine("Standard Snake Pit", new int[]{2597, 2598}, true, true)),
            now - (stale ? 3 * 3_600_000L : 840_000L), stale);
    }
    public static HomeModel populated(long now) {
        return new HomeModel(hero(HomeModel.State.LIVE, now), now(now), today(HomeArchive.Window.TODAY, now),
            new HomeModel.Runs(HomeModel.State.LIVE, runs(now), null), quests(now, false));
    }
    /** Nothing ever captured: every card shows its EmptyState. */
    public static HomeModel empty() {
        String none = "No character captured yet";
        return new HomeModel(
            new HomeModel.Hero(HomeModel.State.EMPTY, null, 0, null, null, null, DisplayValue.unknown(none), -1, null, null, null, null,
                "", -1, null, DisplayValue.unknown(none), DisplayValue.unknown(none), "", 0L, "No character has been captured on this computer yet."),
            new HomeModel.Now(HomeModel.State.EMPTY, false, null, null, List.of(), 0, 0, null),
            new HomeModel.Today(HomeModel.State.EMPTY, HomeArchive.Window.TODAY, null, null),
            new HomeModel.Runs(HomeModel.State.EMPTY, List.of(), null),
            new HomeModel.Quests(HomeModel.State.EMPTY, 0, 0, 0, List.of(), 0L, false));
    }
    /** Out of game: last known character, capture off (Now is EMPTY, as HomeModelBuilder.now builds it then), a stale quest list. */
    public static HomeModel stale(long now) {
        return new HomeModel(hero(HomeModel.State.STALE, now), new HomeModel.Now(HomeModel.State.EMPTY, false, null, null, List.of(), 0, 0, null),
            today(HomeArchive.Window.TODAY, now), new HomeModel.Runs(HomeModel.State.LIVE, runs(now), null), quests(now, true));
    }
    public static HomeModel unavailable() {
        String reason = "Saved history is not available: the history folder could not be opened.";
        return new HomeModel(
            new HomeModel.Hero(HomeModel.State.UNAVAILABLE, null, 0, null, null, null, DisplayValue.unknown(reason), -1, null, null, null,
                null, "", -1, null, DisplayValue.unknown(reason), DisplayValue.unknown(reason), "", 0L, "The character journal could not be read."),
            new HomeModel.Now(HomeModel.State.UNAVAILABLE, false, null, null, List.of(), 0, 0, null),
            new HomeModel.Today(HomeModel.State.UNAVAILABLE, HomeArchive.Window.TODAY, null, reason),
            new HomeModel.Runs(HomeModel.State.UNAVAILABLE, List.of(), reason),
            new HomeModel.Quests(HomeModel.State.UNAVAILABLE, 0, 0, 0, List.of(), 0L, false));
    }
    /** Depth-first lookup by name; null when absent (a card body that is not shown is not in the tree). */
    public static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) {
                T found = named((Container) child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
