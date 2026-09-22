package tomato.gui.chat;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.history.*;
import util.PreferencesStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class ChatVisibilityStateTest {
    private static ViewStateStore memory() {
        return new ViewStateStore(new ViewStateStore.Storage() {
            final Map<String,String> values = new HashMap<>();
            public String get(String key) { return values.get(key); }
            public CompletionStage<PreferencesStore.SaveResult> put(String key,String value) { values.put(key,value);return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1)); }
        });
    }
    @Test public void namedLiveVisibilityAndMatchingBadgeSurviveLeavingAndReturning() throws Exception {
        String previous=PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS);ViewStateStore states=memory();
        ChatExplorer[] view={null};
        try { edt(() -> {
            PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,"false");
            ChatFilters filters=new ChatFilters();ChatFilters.Settings policy=filters.settings();policy.ignoredPlayers.add("Ann");filters.apply(policy,false);
            view[0]=new ChatExplorer(()->{},filters,()->"");
            button(view[0],"Follow latest").doClick();
            view[0].accept(new ChatMessage(LocalDateTime.of(2026,9,22,12,0),ChatMessage.Channel.WORLD,"Ann","","Ann","Synthetic unread", ""));
            ViewState<ChatExplorer.LiveFacets,ChatArchiveClient.Sort> initial=view[0].captureLiveState();
            ChatExplorer.LiveFacets facets=initial.query.facets();facets.showIgnoredPlayers=true;
            states.saveNamed("chat-live","Ignored conversation",initial.withQuery(initial.query.withFacets(facets)));
            view[0].enableLiveState(states);assertFalse(view[0].showsIgnoredPlayers());
            namedViews(view[0]).setSelectedItem("Ignored conversation");button(view[0],"Load live view").doClick();
            assertTrue(view[0].showsIgnoredPlayers());assertEquals(1,view[0].unseenMatchingCount());
            assertTrue(named(view[0],"chat-new-messages",JButton.class).isVisible());
            assertEquals("false",PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS));
            view[0].setVisible(false);view[0].setVisible(true);view[0].refreshShownState();
            assertTrue(view[0].showsIgnoredPlayers());assertEquals(1,view[0].unseenMatchingCount());
            assertTrue(named(view[0],"chat-new-messages",JButton.class).isVisible());
            assertFalse(button(view[0],"Follow latest").isSelected());
            return null;
        }); } finally {
            edt(() -> { if(view[0]!=null)view[0].removeNotify();return null; });
            PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,previous==null?"":previous);
        }
    }
    @Test public void legacyVisibilitySeedsOnlyTheFirstCanonicalLiveState() throws Exception {
        String previous=PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS);ViewStateStore states=memory();
        ChatExplorer[] views=new ChatExplorer[2];
        try {
            edt(() -> { PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,"true");views[0]=new ChatExplorer(()->{});views[0].enableLiveState(states);
                assertTrue(views[0].showsIgnoredPlayers());states.save("chat-live",views[0].captureLiveState());
                PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,"false");views[1]=new ChatExplorer(()->{});views[1].enableLiveState(states);
                assertTrue("Canonical state wins over the legacy key on restart",views[1].showsIgnoredPlayers());
                named(views[1],"chat-show-ignored-players",JCheckBox.class).doClick();assertFalse(views[1].showsIgnoredPlayers());
                states.save("chat-live",views[1].captureLiveState());assertFalse(states.load("chat-live",views[0].captureLiveState()).query.facets().showIgnoredPlayers);
                assertEquals("false",PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS));return null;
            });
        } finally { edt(() -> { for(ChatExplorer view:views)if(view!=null)view.removeNotify();return null; });PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,previous==null?"":previous); }
    }
    private static JComboBox<?> namedViews(Container root) {
        for(Component child:root.getComponents()) {
            if(child instanceof JComboBox && "Named live views".equals(((JComboBox<?>)child).getAccessibleContext().getAccessibleName()))return (JComboBox<?>)child;
            if(child instanceof Container){JComboBox<?> found=namedViews((Container)child);if(found!=null)return found;}
        }return null;
    }
}
