package tomato.gui.stats;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DungeonStatData;
import tomato.backend.data.Entity;
import tomato.gui.history.SessionPanel;
import tomato.history.SessionStore;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

/** Synthetic projections only: no windows, desktop, assets or capture required. */
public class ReportingStatisticsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static LootDashboard.Drop drop(long time, String source) {
        return new LootDashboard.Drop("White", "Ice Citadel", source, time,
            Collections.singletonList(new LootDashboard.Item(42, "Synthetic item", false)), "run");
    }
    @Test public void globalNewestThousandSurviveNewestSessionFirstAndOutOfOrderRecords() throws Exception {
        LootDashboard.Archive archive=new LootDashboard.Archive();
        for(int i=1099;i>=0;i--)archive.accept("new",drop(10000+i,"new"));
        for(int i=0;i<1100;i++)archive.accept("old",drop(i,"old"));
        SwingUtilities.invokeAndWait(()->{
            LootDashboard view=archive.view();List<LootDashboard.Drop> recent=view.recentDrops();
            assertEquals(1000,recent.size());assertEquals(11099,recent.get(0).time);assertEquals(10100,recent.get(999).time);
            assertArrayEquals(new int[]{2200,2200},view.sessionTotals());
        });
    }
    @Test public void equalTimestampsUseSessionAndLocalOrdinalWithoutCollapsingDuplicates() throws Exception {
        LootDashboard.Archive first=new LootDashboard.Archive(),second=new LootDashboard.Archive();
        for(int i=0;i<600;i++)first.accept("z",drop(1000,"z"+i));
        for(int i=0;i<600;i++)first.accept("a",drop(1000,"a"+i));
        for(int i=0;i<600;i++)second.accept("a",drop(1000,"a"+i));
        for(int i=0;i<600;i++)second.accept("z",drop(1000,"z"+i));
        SwingUtilities.invokeAndWait(()->{
            List<LootDashboard.Drop> a=first.view().recentDrops(),b=second.view().recentDrops();
            assertEquals(1000,a.size());for(int i=0;i<a.size();i++)assertEquals(a.get(i).dropper,b.get(i).dropper);
            assertEquals("z599",a.get(0).dropper);assertEquals("a200",a.get(999).dropper);
        });
    }
    private static ActivityJournal.Visit visit(String id,String map,long duration){
        ActivityJournal.Visit v=new ActivityJournal.Visit();v.id=id;v.map=map;v.started=1000;v.lastSeen=v.ended=1000+duration;return v;
    }
    private static Object value(JTable table,String name){return table.getValueAt(0,table.getColumnModel().getColumnIndex(name));}
    @Test public void ninetyNineUnknownVisitsCannotDiluteOneEvidencedVisit() throws Exception {
        Path root=temp.newFolder().toPath();String unknownId;
        try(SessionStore unknown=new SessionStore(root,true,"synthetic A")){
            unknownId=unknown.currentId();
            for(int i=0;i<99;i++){
                ActivityJournal.Visit v=visit("unknown-"+i,"Ice Citadel",60000);
                v.started+=60000L*i;v.lastSeen+=60000L*i;v.ended=v.lastSeen;
                unknown.put("runs",v.id,v);
            }
            unknown.flush();assertFalse(Files.exists(root.resolve(unknownId).resolve("loot.jsonl")));
        }
        try(SessionStore known=new SessionStore(root,true,"synthetic B")){
            known.put("runs","run",visit("run","Ice Citadel",60000));known.append("loot",drop(2000,"Boss"));known.flush();
            SessionPanel.Loaded loaded=HistoricalStatistics.statistics(known,SessionStore.ALL,0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();JTable table=named(panel,"history-dungeon-loot",JTable.class);
                assertEquals(1L,value(table,"Observed runs"));assertEquals(1.0,value(table,"Captured minutes"));
                assertEquals(1L,value(table,"Items"));assertEquals(1.0,value(table,"Items / run"));assertEquals(60.0,value(table,"Items / hour"));
                assertEquals(99L,value(table,"Excluded unknown-coverage runs"));assertEquals(0L,value(table,"Excluded imported runs"));
                String details=named(panel,"history-loot-rate-details",JTextArea.class).getText();
                assertTrue(details.contains("0 with no linked bags"));assertTrue(details.contains("99 unknown-coverage visits (5940000 observed milliseconds)"));
                JTable sessions=named(panel,"history-session-comparison",JTable.class);assertEquals(2,sessions.getRowCount());
                for(int row=0;row<sessions.getRowCount();row++){
                    boolean unknownSession=Long.valueOf(99).equals(sessions.getValueAt(row,2));
                    Object items=sessions.getValueAt(row,sessions.getColumnModel().getColumnIndex("Items"));
                    if(unknownSession){assertNull(items);assertTrue(sessions.getValueAt(row,sessions.getColumnModel().getColumnIndex("Loot coverage")).toString().contains("coverage unknown"));}
                    else assertEquals(1L,items);
                }
            });
            known.importSnapshot("synthetic run-only import","Imported fixture",1000,"runs","imported",visit("imported","Ice Citadel",60000));
            SessionPanel.Loaded withImport=HistoricalStatistics.loot(known,SessionStore.ALL,0,"");
            SwingUtilities.invokeAndWait(()->{
                JTable table=named(withImport.createView(),"history-dungeon-loot",JTable.class);
                assertEquals(1L,value(table,"Observed runs"));assertEquals(1L,value(table,"Excluded imported runs"));assertEquals(99L,value(table,"Excluded unknown-coverage runs"));
                assertEquals(1.0,value(table,"Items / run"));assertEquals(60.0,value(table,"Items / hour"));
            });
        }
    }
    @Test public void evidencedSessionKeepsItsVisitWithoutLootInBothDenominators() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","run",visit("run","Ice Citadel",60000));store.put("runs","empty",visit("empty","Ice Citadel",60000));
            store.append("loot",drop(2000,"Boss"));store.flush();
            SessionPanel.Loaded loaded=HistoricalStatistics.loot(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();JTable table=named(panel,"history-dungeon-loot",JTable.class);
                assertEquals(2L,value(table,"Observed runs"));assertEquals(2.0,value(table,"Captured minutes"));
                assertEquals(.5,value(table,"Items / run"));assertEquals(30.0,value(table,"Items / hour"));assertEquals(0L,value(table,"Excluded unknown-coverage runs"));
                assertTrue(named(panel,"history-loot-rate-details",JTextArea.class).getText().contains("1 with no linked bags"));
            });
        }
    }
    @Test public void emptyBagEvidenceIsSessionScopedAndSurvivesDungeonFiltering() throws Exception {
        Path root=temp.newFolder().toPath();String knownId;
        try(SessionStore known=new SessionStore(root,true,"synthetic known")){
            knownId=known.currentId();
            known.put("runs","run",visit("run","Ice Citadel",60000));known.put("runs","empty",visit("empty","Lost Halls",60000));
            known.append("loot",new LootDashboard.Drop("Blue","Ice Citadel","Boss",2000,Collections.emptyList(),"run"));known.flush();
        }
        try(SessionStore unknown=new SessionStore(root,true,"synthetic unknown")){
            unknown.put("runs","empty",visit("empty","Lost Halls",60000));unknown.flush();
            SessionPanel.Loaded known=HistoricalStatistics.loot(unknown,knownId,0,"Lost Halls");
            SessionPanel.Loaded combined=HistoricalStatistics.loot(unknown,SessionStore.ALL,0,"Lost Halls");
            SessionPanel.Loaded alone=HistoricalStatistics.loot(unknown,unknown.currentId(),0,"Lost Halls");
            SwingUtilities.invokeAndWait(()->{
                for(SessionPanel.Loaded loaded:Arrays.asList(known,combined)){
                    JTable table=named(loaded.createView(),"history-dungeon-loot",JTable.class);
                    assertEquals(1,table.getRowCount());assertEquals("Lost Halls",value(table,"Dungeon"));assertEquals(1L,value(table,"Observed runs"));
                    assertEquals(0L,value(table,"Items"));assertEquals(0.0,value(table,"Items / run"));assertEquals(0.0,value(table,"Items / hour"));
                    assertTrue(value(table,"Loot coverage").toString().contains("Partial"));
                    assertEquals(loaded==combined?1L:0L,value(table,"Excluded unknown-coverage runs"));
                }
                JTable table=named(alone.createView(),"history-dungeon-loot",JTable.class);
                assertEquals(0L,value(table,"Observed runs"));assertEquals(1L,value(table,"Excluded unknown-coverage runs"));
                assertNull(value(table,"Items"));assertNull(value(table,"Items / run"));assertNull(value(table,"Items / hour"));
                assertTrue(value(table,"Loot coverage").toString().contains("coverage unknown"));
            });
        }
    }
    @Test public void onlyEligibleSessionDurationsCanInvalidateTheHourlyRate() throws Exception {
        for(boolean knownMissingDuration:new boolean[]{false,true}){
            Path root=temp.newFolder().toPath();
            try(SessionStore unknown=new SessionStore(root,true,"synthetic unknown")){
                unknown.put("runs","gap",visit("gap","Ice Citadel",0));unknown.flush();
            }
            try(SessionStore known=new SessionStore(root,true,"synthetic known")){
                known.put("runs","run",visit("run","Ice Citadel",60000));known.append("loot",drop(2000,"Boss"));
                if(knownMissingDuration)known.put("runs","gap",visit("gap","Ice Citadel",0));known.flush();
                SessionPanel.Loaded loaded=HistoricalStatistics.loot(known,SessionStore.ALL,0,"");
                SwingUtilities.invokeAndWait(()->{
                    JComponent panel=loaded.createView();JTable table=named(panel,"history-dungeon-loot",JTable.class);
                    assertEquals(1L,value(table,"Excluded unknown-coverage runs"));assertEquals(knownMissingDuration?2L:1L,value(table,"Observed runs"));
                    assertEquals(knownMissingDuration?.5:1.0,value(table,"Items / run"));
                    if(knownMissingDuration){assertNull(value(table,"Items / hour"));assertTrue(named(panel,"history-loot-rate-details",JTextArea.class).getText().contains("at least one visit has no positive observed duration"));}
                    else assertEquals(60.0,value(table,"Items / hour"));
                });
            }
        }
    }
    @Test public void unassignedBagEstablishesSessionEvidenceButCannotCreateARate() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","a",visit("a","Ice Citadel",60000));store.put("runs","b",visit("b","Ice Citadel",60000));
            store.append("loot",drop(2000,"Boss"));store.flush(); // Bag refers to absent visit "run".
            SessionPanel.Loaded loaded=HistoricalStatistics.loot(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();JTable table=named(panel,"history-dungeon-loot",JTable.class);
                assertEquals(2L,value(table,"Observed runs"));assertEquals(0L,value(table,"Excluded unknown-coverage runs"));assertEquals(1L,value(table,"Items"));
                assertNull(value(table,"Items / run"));assertNull(value(table,"Items / hour"));
                assertTrue(named(panel,"history-loot-rate-details",JTextArea.class).getText().contains("Unassigned bags: 1"));
            });
        }
    }
    @Test public void unknownAndImportedOnlyCohortsDoNotBecomeRecordedZero() throws Exception {
        Path root=temp.newFolder().toPath();
        try(SessionStore imported=new SessionStore(root,true,"Imported")){
            imported.put("runs","run",visit("run","Ice Citadel",60000));imported.flush();
        }
        try(SessionStore unknown=new SessionStore(root,true,"synthetic")){
            unknown.put("runs","run",visit("run","Ice Citadel",60000));unknown.flush();
            SessionPanel.Loaded loaded=HistoricalStatistics.loot(unknown,SessionStore.ALL,0,"");
            SwingUtilities.invokeAndWait(()->{
                JTable table=named(loaded.createView(),"history-dungeon-loot",JTable.class);
                assertEquals(0L,value(table,"Observed runs"));assertEquals(1L,value(table,"Excluded imported runs"));assertEquals(1L,value(table,"Excluded unknown-coverage runs"));
                assertNull(value(table,"Items"));assertNull(value(table,"Items / run"));assertNull(value(table,"Items / hour"));
                assertTrue(value(table,"Loot coverage").toString().contains("coverage unknown"));
            });
        }
    }
    @Test public void runOnlyImportsExposeUnavailableLootInBothReports() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"Imported")){
            store.put("runs","run",visit("run","Ice Citadel",60000));store.flush();
            SessionPanel.Loaded loaded=HistoricalStatistics.statistics(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();JTable sessions=named(panel,"history-session-comparison",JTable.class);
                assertEquals(1L,value(sessions,"Dungeon visits"));assertNull(value(sessions,"Items"));assertTrue(value(sessions,"Loot coverage").toString().contains("Not captured"));
                JTable dungeons=named(panel,"history-dungeon-loot",JTable.class);
                assertEquals(0L,value(dungeons,"Observed runs"));assertEquals(1L,value(dungeons,"Excluded imported runs"));assertNull(value(dungeons,"Items / run"));
                assertTrue(named(panel,"history-loot-rate-details",JTextArea.class).getText().contains("run-only imported visits"));
            });
        }
    }
    @Test public void missingLootJournalIsUnknownButAnObservedEmptyBagEstablishesZero() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","run",visit("run","Ice Citadel",60000));store.flush();
            SessionPanel.Loaded unknown=HistoricalStatistics.statistics(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JTable table=named(unknown.createView(),"history-session-comparison",JTable.class);
                assertNull(value(table,"Items"));assertTrue(value(table,"Loot coverage").toString().contains("coverage unknown"));
            });
            store.append("loot",new LootDashboard.Drop("White","Ice Citadel","Boss",2000,Collections.emptyList(),"run"));store.flush();
            SessionPanel.Loaded zero=HistoricalStatistics.loot(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JTable table=named(zero.createView(),"history-dungeon-loot",JTable.class);
                assertEquals(0L,value(table,"Items"));assertEquals(0.0,value(table,"Items / run"));
                assertEquals(1L,value(table,"White bags"));
            });
        }
    }
    @Test public void aliasJoinIncludesZeroLootVisitsAndRateDetailsAndRejectsMissingDurations() throws Exception {
        String alias="Cave of A Thousand Treasures",canonical="Cave of a Thousand Treasures";
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","run",visit("run",alias,60000));store.put("runs","empty",visit("empty",canonical,60000));
            List<LootDashboard.Item> items=Arrays.asList(new LootDashboard.Item(1,"A",false),new LootDashboard.Item(2,"B",false),new LootDashboard.Item(3,"C",false));
            store.append("loot",new LootDashboard.Drop("White",canonical,"Boss",2000,items,"run"));store.flush();
            SessionPanel.Loaded loaded=HistoricalStatistics.loot(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();JTable table=named(panel,"history-dungeon-loot",JTable.class);
                assertEquals(1,table.getRowCount());assertEquals(canonical,value(table,"Dungeon"));
                assertEquals(1.5,value(table,"Items / run"));assertEquals(90.0,value(table,"Items / hour"));
                String details=named(panel,"history-loot-rate-details",JTextArea.class).getText();
                assertTrue(details.contains("1 with no linked bags"));assertTrue(details.contains("120000 observed milliseconds"));
            });
            store.put("runs","gap",visit("gap",canonical,0));store.flush();
            SessionPanel.Loaded gap=HistoricalStatistics.loot(store,store.currentId(),0,"");
            SwingUtilities.invokeAndWait(()->{
                JTable table=named(gap.createView(),"history-dungeon-loot",JTable.class);
                assertEquals(1.0,value(table,"Items / run"));assertNull(value(table,"Items / hour"));
            });
        }
    }
    @Test public void unassignedBagMakesRateUnavailableEvenWithAnEligibleVisit() {
        HistoricalStatistics.Profile profile=new HistoricalStatistics.Profile();profile.runs=2;profile.millis=120000;
        profile.add(drop(2000,"test"),false);
        assertNull(profile.perRun(profile.items));assertNull(profile.perHour(profile.items));
        assertTrue(profile.explanation().contains("Unassigned bags: 1"));
    }
    @Test public void ongoingActivityIsSeparateFromFinalizedExitsAndOldMetadataStaysUnknown() {
        DungeonStatData data=new DungeonStatData(temp.getRoot().toPath().resolve("synthetic.stats"));
        data.updateDungeon("Ice Citadel",1000);assertTrue(data.sessionSnapshot().isEmpty());
        Entity enemy=new Entity(null,1,42);enemy.objectType=42;
        data.updateEntityDamage("Ice Citadel",enemy);
        DungeonStatData.Snapshot ongoing=data.sessionSnapshot().get(0);
        assertEquals(0,ongoing.visits);assertEquals(0,ongoing.time);assertEquals(1,ongoing.hitCount());assertEquals(Boolean.TRUE,ongoing.ongoingActivity);
        data.updateDungeon("Ice Citadel",1000);DungeonStatData.Snapshot ended=data.sessionSnapshot().get(0);
        assertEquals(1,ended.visits);assertEquals(1000,ended.time);assertEquals(Boolean.FALSE,ended.ongoingActivity);
        DungeonStatData.Snapshot legacy=SessionStore.JSON.fromJson("{\"name\":\"Ice Citadel\",\"visits\":1,\"time\":1000}",DungeonStatData.Snapshot.class);
        assertNull(legacy.ongoingActivity);
    }
    @Test public void canonicalCountersMergeVerifiedAliasesButPreserveUnrecognizedAreas() {
        DungeonStatData.Snapshot a=new DungeonStatData.Snapshot("Cave of A Thousand Treasures",1,1000);
        DungeonStatData.Snapshot b=new DungeonStatData.Snapshot("Cave of a Thousand Treasures",2,2000);
        a.hits.put(42,1);b.hits.put(42,2);b.ongoingActivity=true;
        List<DungeonStatData.Snapshot> rows=DungeonStatData.Snapshot.canonicalize(Arrays.asList(a,b));
        assertEquals(1,rows.size());assertEquals(3,rows.get(0).visits);assertEquals(3,rows.get(0).hitCount());assertEquals(Boolean.TRUE,rows.get(0).ongoingActivity);
        assertEquals("Cave of A Thousand Treasures",a.name);assertEquals(1,a.visits);
        assertEquals(2,DungeonStatData.Snapshot.canonicalize(Arrays.asList(new DungeonStatData.Snapshot("Unknown A",0,0),new DungeonStatData.Snapshot("Unknown B",0,0))).size());
    }
}
