package tomato.history.index;

import java.util.function.Function;
import util.PropertiesManager;

/** Search is a derived cache; this preference never changes the saved chat journal. */
public final class SearchSettings {
    public static final String INCLUDE_CHAT = "history.search.includeChat";
    private SearchSettings() { }
    public static boolean includeChat() { return includeChat(PropertiesManager::getProperty); }
    public static boolean includeChat(Function<String, String> read) {
        return !"false".equals(read.apply(INCLUDE_CHAT));
    }
}
