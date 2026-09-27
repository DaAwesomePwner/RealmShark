package tomato.gui.glance.home;

/**
 * Everything Home reads. HomeRefresher calls revisions(), hero(), now() and quests() only on its "home-refresh" thread and
 * archive() only on its "home-archive" thread, never on the EDT.
 */
public interface HomeSources {
    /**
     * Cheap change tokens, one per live section. HomeRefresher rebuilds a section only when its token moves (Now and Quests
     * also every 10 s, for their age rules). A token may move without a visible change: the rebuilt section is then equal
     * to the previous one and is dropped.
     */
    record Revisions(long hero, long now, long quests) {}

    Revisions revisions();
    HomeModel.Hero hero(long now);
    HomeModel.Now now(long now);
    HomeModel.Quests quests(long now);
    HomeArchive.Result archive(HomeArchive.Window window, long now) throws Exception;
}
