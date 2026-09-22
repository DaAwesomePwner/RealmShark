package tomato.gui.activity;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityQueries.*;

public class ActivityArchiveTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static ActivityJournal.Visit visit(String id,int index) {
        ActivityJournal.Visit v=new ActivityJournal.Visit();v.id=id;v.map="Lost Halls";
        v.started=10000+index*1000L;v.lastSeen=v.ended=v.started+index*100L;
        v.completionEvidence=index%2==0?"Server victory notification":"";v.issues=index%3==0?1:0;v.timingGaps=index%5==0?2:0;
        v.conditionObservedMillis=1000;v.conditions.put("Damaging",500L);
        ActivityJournal.ResourcePoint point=new ActivityJournal.ResourcePoint();point.time=v.started;point.hp=700;v.resourceTimeline.add(point);
        return v;
    }
    static ActivityJournal.Entry event(String visit,int index) {
        ActivityJournal.Entry e=new ActivityJournal.Entry();e.id="event-"+index;e.visitId=visit;e.map="Lost Halls";
        e.time=10000+index;e.kind=index%2==0?"Capture issue":"Equipment changed";e.detail="Synthetic observation";
        e.values=new LinkedHashMap<>();e.values.put("slot",0);e.values.put("before",-1);e.values.put("after",123);return e;
    }
    private ArchiveResult<Row> open(SessionStore store,ArchiveQuery<Filters,Sort> query,ActivityPanel.Mode mode)throws Exception {
        return ArchiveResult.open(store,query,ActivityQueries.adapter(mode),temp.newFolder().toPath(),new Cancellation());
    }
    @Test public void visitsFilterAndSortGloballyBeyondFirstHundredWithLightPagesAndExportParity()throws Exception {
        Path root=temp.newFolder().toPath(),output=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(root,true,"synthetic")) {
            for(int i=1;i<=240;i++)store.put("runs","visit-"+i,visit("visit-"+i,i));store.flush();
            Filters filters=new Filters();filters.outcomes.add(Outcome.COMPLETED);filters.evidence.add(Evidence.VICTORY);
            filters.minimumDurationMillis=12000L;filters.maximumDurationMillis=24000L;filters.captureIssues=Presence.PRESENT;filters.timingGaps=Presence.PRESENT;
            ArchiveQuery<Filters,Sort> query=initial().withFacets(filters).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.DURATION,ArchiveQuery.Direction.DESCENDING)));
            try(ArchiveResult<Row> result=open(store,query,ActivityPanel.Mode.RUNS)) {
                assertEquals(5,result.matches);ArchivePage<Row> first=result.page(0,2,new Cancellation());
                assertEquals("visit-240",first.rows.get(0).value.visitId);assertEquals("visit-210",first.rows.get(1).value.visitId);
                assertEquals(store.currentId(),first.rows.get(0).ref.session);assertEquals(Long.valueOf(24000),first.rows.get(0).value.durationMillis);
                assertFalse(SessionStore.JSON.toJson(first.rows).contains("resourceTimeline"));
                ArchivePage<Row> third=result.page(2,2,new Cancellation());assertEquals("visit-120",third.rows.get(0).value.visitId);
                assertEquals(2,result.pageOf(third.rows.get(0).ref,2,new Cancellation()));
                try(ArchiveResult.Lease<Row> lease=result.lease()) {
                    ActivityJournal.Visit detail=ActivityQueries.readVisit(lease,first.rows.get(0),new Cancellation());assertEquals(1,detail.resourceTimeline.size());
                    Path all=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"all",Collections.emptyList(),new Cancellation());
                    assertEquals(5,json(all).getAsJsonArray("rows").size());
                    Path page=ArchiveExport.write(lease,ExportSelection.page(0,2),ArchiveExport.Format.JSON,output,"page",Collections.emptyList(),new Cancellation());assertEquals(2,json(page).getAsJsonArray("rows").size());
                }
            }
            try(ArchiveResult<Row> all=open(store,initial(),ActivityPanel.Mode.RUNS)) { assertEquals(240,all.matches);assertEquals(100,all.page(0,100,new Cancellation()).rows.size());assertEquals(40,all.page(2,100,new Cancellation()).rows.size()); }
        }
    }
    @Test public void timelineMultiTypeUnassignedAndDateFiltersPrecedeThousandEventPaging()throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            for(int i=0;i<2305;i++)store.append("timeline",event(i>=1000?"":"v",i));store.flush();
            Filters f=new Filters();f.kinds.add("Capture issue");f.kinds.add("Equipment changed");f.assignment=Assignment.UNASSIGNED;
            ArchiveQuery<Filters,Sort> query=initial().withFacets(f).withBounds(new ArchiveQuery.Bounds(11000L,12305L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false));
            try(ArchiveResult<Row> result=open(store,query,ActivityPanel.Mode.TIMELINE)) {
                assertEquals(1305,result.matches);ArchivePage<Row> first=result.page(0,1000,new Cancellation());
                assertEquals("event-2304",first.rows.get(0).value.recordId);assertEquals("event-1305",first.rows.get(999).value.recordId);
                assertEquals(0,first.counts.get("assigned").value);assertEquals(1305,first.counts.get("unassigned").value);
                assertEquals(305,result.page(1,1000,new Cancellation()).rows.size());
                assertTrue(first.rows.get(0).value.summary.contains("Capture issue"));assertFalse(first.rows.get(0).value.assigned);
            }
            f.kinds.remove("Equipment changed");
            try(ArchiveResult<Row> result=open(store,query.withFacets(f).withText("incomplete"),ActivityPanel.Mode.TIMELINE)){assertEquals(653,result.matches);}
        }
    }
    @Test public void linkedExportUsesIndependentTimelineExactSessionAndVisitAndSurvivesPinChanges()throws Exception {
        Path root=temp.newFolder().toPath(),output=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String first;
        try(SessionStore store=new SessionStore(root,true,"synthetic-a")) {
            first=store.currentId();store.put("runs","same",visit("same",2));
            for(int i=0;i<1007;i++)store.append("timeline",event("same",i));
            store.append("timeline",event("other",9999));store.append("timeline",event("",9998));store.flush();
        }
        try(SessionStore store=new SessionStore(root,true,"synthetic-b")) {
            store.put("runs","same",visit("same",2));store.append("timeline",event("same",9000));store.flush();
            ArchiveQuery<Filters,Sort> query=initial().withScope(SessionStore.ALL);
            ArchiveResult<Row> result=ArchiveResult.open(store,query,ActivityQueries.adapter(ActivityPanel.Mode.RUNS),scratch,new Cancellation());
            ArchivePage<Row> page=result.page(0,100,new Cancellation());assertEquals(2,page.matches);
            ArchiveRow<Row> a=page.rows.stream().filter(r->r.ref.session.equals(first)).findFirst().get();
            ArchiveRow<Row> b=page.rows.stream().filter(r->r.ref.session.equals(store.currentId())).findFirst().get();assertNotEquals(a.ref,b.ref);
            try(ArchiveResult.Lease<Row> held=result.lease()) {
                SelectedRunExport.Preview preview=SelectedRunExport.preview(held,a.ref,new Cancellation());assertEquals(1007,preview.events);
                result.close();
                store.put("runs","same",visit("same",40));store.append("timeline",event("same",9001));store.flush();
                Files.write(root.resolve(first).resolve("timeline.jsonl"),(SessionStore.JSON.toJson(event("same",10000))+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                Path file=SelectedRunExport.write(held,preview,ArchiveExport.Format.JSON,output,"same",new Cancellation());
                JsonObject report=json(file);assertEquals(1008,report.getAsJsonArray("rows").size());
                JsonObject manifest=report.getAsJsonObject("manifest");assertEquals(1007,manifest.get("linkedEventCount").getAsInt());assertEquals(1008,manifest.get("exportCount").getAsInt());
                assertEquals(page.revision,manifest.get("revision").getAsString());
                for(JsonElement row:report.getAsJsonArray("rows"))assertEquals(first,row.getAsJsonObject().getAsJsonObject("origin").get("session").getAsString());
                Path second=SelectedRunExport.write(held,b.ref,ArchiveExport.Format.JSON,output,"same",new Cancellation());assertNotEquals(file,second);
                assertEquals(2,json(second).getAsJsonArray("rows").size());
                assertEquals(200,json(second).getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("value").get("lastSeen").getAsLong()-12000);
                Path csv=SelectedRunExport.write(held,a.ref,ArchiveExport.Format.CSV,output,"same",new Cancellation());assertTrue(new String(Files.readAllBytes(csv),StandardCharsets.UTF_8).contains("SELECTED_VISIT_AND_LINKED_TIMELINE"));
            } finally { result.close(); }
            try(java.util.stream.Stream<Path> paths=Files.list(scratch)){assertEquals(0,paths.count());}
            Filters exact=new Filters();exact.visitSession=first;exact.visitId="same";
            try(ArchiveResult<Row> events=open(store,query.withFacets(exact),ActivityPanel.Mode.TIMELINE)){assertEquals(1008,events.matches);}
        }
    }
    @Test public void dateBoundsUseEntryOrObservedOverlapWithoutInventingUnknownDurations()throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            ActivityJournal.Visit earlier=visit("earlier",1);earlier.started=1000;earlier.lastSeen=earlier.ended=4000;store.put("runs","earlier",earlier);
            ActivityJournal.Visit boundary=visit("boundary",2);boundary.started=5000;boundary.lastSeen=6000;store.put("runs","boundary",boundary);
            ActivityJournal.Visit unknown=visit("unknown",3);unknown.started=unknown.lastSeen=unknown.ended=0;store.put("runs","unknown",unknown);store.flush();
            ArchiveQuery<Filters,Sort> q=initial().withBounds(new ArchiveQuery.Bounds(3000L,5000L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false));
            try(ArchiveResult<Row> result=open(store,q,ActivityPanel.Mode.RUNS)){assertEquals(0,result.matches);}
            q=q.withBounds(new ArchiveQuery.Bounds(3000L,5000L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.OVERLAP,false));
            try(ArchiveResult<Row> result=open(store,q,ActivityPanel.Mode.RUNS)){assertEquals(1,result.matches);assertEquals("earlier",result.page(0,100,new Cancellation()).rows.get(0).value.visitId);}
            q=q.withBounds(new ArchiveQuery.Bounds(3000L,5000L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.OVERLAP,true));
            try(ArchiveResult<Row> result=open(store,q,ActivityPanel.Mode.RUNS)){assertEquals(2,result.matches);}
            Filters f=new Filters();f.maximumDurationMillis=0L;
            try(ArchiveResult<Row> result=open(store,initial().withFacets(f),ActivityPanel.Mode.RUNS)){assertEquals("missing duration is not zero",0,result.matches);}
            assertEquals(Outcome.UNKNOWN,ActivityQueries.visit(unknown).outcome);
        }
    }
    @Test public void summariesExplainEntryEquipmentProgressionCaptureAndUnknownWithoutJson() {
        ActivityJournal.Entry e=event("",1);e.kind="Area entered";e.values.clear();assertTrue(ActivitySummaries.event(e).startsWith("Entered Lost Halls"));
        e.kind="Equipment changed";assertEquals("Unknown equipment slot: Unknown item → Unknown item",ActivitySummaries.event(e));
        e.values.put("slot",1);e.values.put("before",-1);e.values.put("after",42);assertEquals("Ability: Empty → Item 42",ActivitySummaries.event(e));
        e.kind="Exalt change";e.values.clear();assertTrue(ActivitySummaries.event(e).contains("Unknown → Unknown"));assertTrue(ActivitySummaries.event(e).contains("source unassigned"));
        e.kind="Exalt baseline";assertTrue(ActivitySummaries.event(e).contains("no earned progress inferred"));
        e.kind="Item / ability request";assertTrue(ActivitySummaries.event(e).contains("acceptance not established"));
        e.kind="Capture issue";e.values=null;assertTrue(ActivitySummaries.event(e).contains("incomplete"));
        e.kind="future-kind";e.detail="";assertTrue(ActivitySummaries.event(e).contains("raw fields available"));
        e.kind=null;assertTrue(ActivitySummaries.event(e).contains("Unknown activity"));
    }
    @Test public void runEvidenceFacetsKeepLegacyCompletedAndLeftUnconfirmedDistinct() {
        ActivityJournal.Visit v=visit("legacy",3);assertEquals(Outcome.LEFT,ActivityQueries.visit(v).outcome);
        v.status="Completed";Row row=ActivityQueries.visit(v);assertEquals(Outcome.COMPLETED,row.outcome);assertEquals(Evidence.NOT_OBSERVED,row.evidence);
        v.completionEvidence="Final boss dialogue: Void Entity";assertEquals(Evidence.DIALOGUE,ActivityQueries.visit(v).evidence);
        v.completionEvidence="Server dungeon-completion counter increased on the next area entry";assertEquals(Evidence.COUNTER,ActivityQueries.visit(v).evidence);
        v.completionEvidence="Future evidence";assertEquals(Evidence.OTHER,ActivityQueries.visit(v).evidence);
        v.completionEvidence=null;v.status=null;assertEquals(Outcome.LEFT,ActivityQueries.visit(v).outcome);
    }
    @Test public void resourcesIncludeNonDungeonVisitsAndExactLinksRequireBothKeys()throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            ActivityJournal.Visit hub=visit("hub",1);hub.map="Nexus";store.put("runs","hub",hub);store.flush();
            try(ArchiveResult<Row> runs=open(store,initial(),ActivityPanel.Mode.RUNS);ArchiveResult<Row> resources=open(store,initial(),ActivityPanel.Mode.COMBAT)){assertEquals(0,runs.matches);assertEquals(1,resources.matches);}
            Filters f=new Filters();f.visitId="hub";
            try{ActivityQueries.adapter(ActivityPanel.Mode.TIMELINE).validate(initial().withFacets(f));fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("both"));}
        }
    }
    @Test public void linkedExportRejectsForeignRefAndCancellationWithoutPublishing()throws Exception {
        Path output=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            store.put("runs","v",visit("v",1));store.flush();
            try(ArchiveResult<Row> result=open(store,initial(),ActivityPanel.Mode.RUNS);ArchiveResult.Lease<Row> held=result.lease()) {
                ArchiveRow.Ref ref=result.page(0,100,new Cancellation()).rows.get(0).ref;
                Cancellation stop=new Cancellation();stop.cancel();
                try{SelectedRunExport.write(held,ref,ArchiveExport.Format.JSON,output,"cancel",stop);fail();}catch(java.util.concurrent.CancellationException expected){ }
                try{SelectedRunExport.preview(held,new ArchiveRow.Ref(store.currentId(),"runs","missing",""),new Cancellation());fail();}catch(java.io.IOException expected){ }
                try(java.util.stream.Stream<Path> paths=Files.list(output)){assertEquals(0,paths.count());}
            }
        }
    }
    static JsonObject json(Path file)throws Exception { return JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject(); }
}
