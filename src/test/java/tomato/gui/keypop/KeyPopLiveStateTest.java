package tomato.gui.keypop;

import java.time.Instant;
import javax.swing.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.ViewState;
import tomato.gui.history.ViewStateStore;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class KeyPopLiveStateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void liveTabExactPlayerSortAndResolvedPeriodRestoreTogether()throws Exception{
        edt(()->{
            KeyPopHistory history=new KeyPopHistory();Instant now=Instant.now().minusSeconds(10);
            history.add(new KeyPopEvent(now,"Ann","Halls",KeyPopEvent.Kind.KEY));history.add(new KeyPopEvent(now,"Anna","Halls",KeyPopEvent.Kind.KEY));
            history.add(new KeyPopEvent(now.plusSeconds(1),"ANN","Vial",KeyPopEvent.Kind.VIAL));
            KeyPopDashboard view=new KeyPopDashboard(history);view.selectPlayer("Ann");view.period.setSelectedItem("Last hour");view.tabs.setSelectedIndex(1);view.players.setRowSelectionInterval(0,0);
            ViewState<KeyPopDashboard.LiveFacets,KeyPopArchiveClient.Sort> saved=view.captureLiveState();Long until=saved.query.bounds().until;
            view.refresh();assertEquals(until,view.captureLiveState().query.bounds().until);
            KeyPopDashboard second=new KeyPopDashboard(history);second.applyLiveState(saved);
            assertEquals(1,second.tabs.getSelectedIndex());assertEquals(2,second.events.getRowCount());assertEquals(1,second.players.getRowCount());
            assertEquals(0,second.players.getSelectedRow());assertEquals("Ann",second.captureLiveState().query.facets().exactPlayer);
            assertEquals(until,second.captureLiveState().query.bounds().until);return null;
        });
    }
    @Test public void attachedLiveStateSurvivesPreferenceStoreRestart()throws Exception{
        java.nio.file.Path path=temp.getRoot().toPath().resolve("keypop-live.properties");
        PreferencesStore prefs=new PreferencesStore(path);prefs.preload();KeyPopHistory history=new KeyPopHistory();history.add(new KeyPopEvent(Instant.now(),"Ann","Halls",KeyPopEvent.Kind.KEY));
        KeyPopDashboard first=edt(()->{KeyPopDashboard v=new KeyPopDashboard(history);v.enableLiveState(ViewStateStore.preferences(prefs));v.selectPlayer("Ann");v.tabs.setSelectedIndex(2);return v;});
        await(()->prefs.getProperty("ux.archive.keypops-live")!=null&&prefs.getProperty("ux.archive.keypops-live").contains("BY_ITEM"));
        prefs.flush().toCompletableFuture().get();edt(()->{first.removeNotify();return null;});prefs.shutdown(5,java.util.concurrent.TimeUnit.SECONDS,m->{});
        PreferencesStore restarted=new PreferencesStore(path);restarted.preload();KeyPopDashboard second=edt(()->{KeyPopDashboard v=new KeyPopDashboard(history);v.enableLiveState(ViewStateStore.preferences(restarted));return v;});
        try{assertEquals(2,edt(()->second.tabs.getSelectedIndex()).intValue());assertEquals("Ann",edt(()->second.captureLiveState().query.facets().exactPlayer));assertEquals(1,edt(()->second.events.getRowCount()).intValue());}
        finally{edt(()->{second.removeNotify();return null;});restarted.shutdown(5,java.util.concurrent.TimeUnit.SECONDS,m->{});}
    }
}
