package tomato.gui.chat;

import java.time.LocalDateTime;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.archive.*;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class ChatArrivalStateTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static ChatMessage message(String sender,String text,ChatMessage.Channel channel){return new ChatMessage(LocalDateTime.now(),channel,sender,"Me",sender,text,"From");}
    @Test public void unseenBadgeUsesAllCurrentFiltersAndJumpPreservesSelectedMessage()throws Exception{
        String previous = util.PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS);
        try { edt(()->{
            util.PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,"false");
            ChatFilters policy=new ChatFilters();ChatExplorer view=new ChatExplorer(()->{},policy,()->"");
            view.accept(message("Ann","needle earlier",ChatMessage.Channel.PM));
            JTable table=named(view,"chat-messages",JTable.class);table.setRowSelectionInterval(0,0);
            button(view,"Follow latest").doClick();named(view,"chat-search",JTextField.class).setText("needle");named(view,"chat-player",JTextField.class).setText("Ann");
            named(view,"chat-channel-PM",JToggleButton.class).doClick();view.refresh(false);
            view.accept(message("Ann","needle new",ChatMessage.Channel.PM));view.accept(message("Bea","needle unrelated",ChatMessage.Channel.PM));
            view.accept(message("Ann","other text",ChatMessage.Channel.PM));view.accept(message("Ann","needle guild",ChatMessage.Channel.GUILD));
            assertEquals(1,view.unseenMatchingCount());assertTrue(named(view,"chat-new-messages",JButton.class).isVisible());
            assertEquals("needle earlier",table.getValueAt(table.getSelectedRow(),4));
            ChatFilters.Settings rules=policy.settings();rules.ignoredPlayers.add("Ann");policy.apply(rules,false);view.refresh(false);assertEquals(0,view.unseenMatchingCount());
            named(view,"chat-show-ignored-players",JCheckBox.class).doClick();assertEquals(1,view.unseenMatchingCount());
            table.setRowSelectionInterval(0,0);named(view,"chat-new-messages",JButton.class).doClick();assertEquals(0,view.unseenMatchingCount());
            assertEquals("needle earlier",table.getValueAt(table.getSelectedRow(),4));assertTrue(button(view,"Follow latest").isSelected());
            return null;
        }); } finally { util.PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS,previous==null?"":previous); }
    }
    @Test public void liveSnapshotRestoresFiltersColumnsSelectionAndUnseenStateWithoutMixingArchiveScopes()throws Exception{
        edt(()->{
            ChatMessage a=message("Ann","one",ChatMessage.Channel.PM),b=message("Ann","two",ChatMessage.Channel.PM);
            ChatExplorer first=new ChatExplorer(()->{});first.accept(a);button(first,"Follow latest").doClick();first.accept(b);
            named(first,"chat-player",JTextField.class).setText("Ann");first.refresh(false);named(first,"chat-messages",JTable.class).setRowSelectionInterval(0,0);
            ViewState<ChatExplorer.LiveFacets,ChatArchiveClient.Sort> saved=first.captureLiveState();
            ChatExplorer second=new ChatExplorer(()->{});second.accept(a);second.accept(b);second.applyLiveState(saved);
            assertEquals("Ann",named(second,"chat-player",JTextField.class).getText());assertFalse(button(second,"Follow latest").isSelected());
            assertEquals(1,second.unseenMatchingCount());assertEquals(0,named(second,"chat-messages",JTable.class).getSelectedRow());
            assertEquals(saved.query,second.captureLiveState().query);return null;
        });
    }
    @Test public void factoryLivePersistenceRoundTripsDiskWithoutOverwritingArchiveScope()throws Exception{
        java.nio.file.Path path=temp.getRoot().toPath().resolve("live.properties");PreferencesStore preferences=new PreferencesStore(path);preferences.preload();ViewStateStore states=ViewStateStore.preferences(preferences);
        ViewState<ChatArchiveClient.Facets,ChatArchiveClient.Sort> archived=ViewState.initial(ChatArchiveClient.query().withScope(tomato.history.SessionStore.ALL)).withArchive(true);
        states.save("chat",archived).toCompletableFuture().get();final ViewState<ChatArchiveClient.Facets,ChatArchiveClient.Sort> expectedArchive=archived;
        ChatExplorer view=edt(()->{ChatExplorer v=new ChatExplorer(()->{});v.enableLiveState(states);named(v,"chat-player",JTextField.class).setText("Ann");button(v,"Follow latest").doClick();v.refresh(false);return v;});
        await(()->preferences.getProperty("ux.archive.chat-live")!=null&&preferences.getProperty("ux.archive.chat-live").contains("Ann"));
        preferences.flush().toCompletableFuture().get();edt(()->{view.removeNotify();return null;});preferences.shutdown(5,java.util.concurrent.TimeUnit.SECONDS,m->{});
        PreferencesStore reopened=new PreferencesStore(path);reopened.preload();ViewStateStore restored=ViewStateStore.preferences(reopened);
        ChatExplorer second=edt(()->{ChatExplorer v=new ChatExplorer(()->{});v.enableLiveState(restored);return v;});
        try{assertEquals("Ann",edt(()->named(second,"chat-player",JTextField.class).getText()));assertFalse(edt(()->button(second,"Follow latest").isSelected()));
            assertEquals(expectedArchive.toJson(),restored.load("chat",expectedArchive).toJson());
        }finally{edt(()->{second.removeNotify();return null;});reopened.shutdown(5,java.util.concurrent.TimeUnit.SECONDS,m->{});}
    }
}
