package tomato.history.archive;

import com.google.gson.*;
import tomato.history.SessionStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class ArchiveContractRegressionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void allSessionPinIsNarrowedBeforeRecordsAdapterScanning()throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String a=session(root,1);session(root,2);
        try(SessionStore store=new SessionStore(root,false,"test")) {
            ReadSnapshot pin=store.capture(Collections.singletonList(new ReadSnapshot.Source(SessionStore.ALL,"chat")),scratch,new Cancellation());
            try(ArchiveResult<Event> result=ArchiveResult.open(pin,query(a),adapter(),scratch,new Cancellation())) {
                assertEquals(1,result.matches);assertEquals(a,result.page(0,100,new Cancellation()).rows.get(0).ref.session);
                assertEquals(1,result.manifest().getAsJsonArray("sessions").size());
            }
        }
    }
    @Test public void wrongSessionAndIncompleteAllSessionPinsCannotClaimSuccessfulQueries()throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String a=session(root,1),b=session(root,2);
        try(SessionStore store=new SessionStore(root,false,"test")) {
            for(String scope:Arrays.asList(a,SessionStore.ALL)) {
                ReadSnapshot pin=store.capture(Collections.singletonList(new ReadSnapshot.Source(b,"chat")),scratch,new Cancellation());
                try(ArchiveResult<Event> ignored=ArchiveResult.open(pin,query(scope),adapter(),scratch,new Cancellation())) { fail("Incompatible pin was accepted for "+scope); }
                catch(IOException expected){assertEquals(0,children(scratch));}
            }
        }
    }
    @Test public void currentScopeResolvesToTheSessionCapturedByThePin()throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();session(root,2);String current;ReadSnapshot pin;
        try(SessionStore store=new SessionStore(root,true,"test")) {
            current=store.currentId();store.append("chat",new Event(1,5,"a","current"));store.flush();
            pin=store.capture(Collections.singletonList(new ReadSnapshot.Source(SessionStore.ALL,"chat")),scratch,new Cancellation());
        }
        try(SessionStore next=new SessionStore(root,false,"next");ArchiveResult<Event> result=ArchiveResult.open(pin,query(ArchiveQuery.CURRENT),adapter(),scratch,new Cancellation())) {
            assertNotEquals(current,next.currentId());assertEquals(1,result.matches);
            assertEquals(current,result.page(0,10,new Cancellation()).rows.get(0).ref.session);
        }
    }
    @Test public void customAdapterGetsItsDeclaredScopeAndStillSeesGlobalAuxiliarySources()throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String a=session(root,1),b=session(root,2);
        for(String id:Arrays.asList(a,b))Files.write(root.resolve(id).resolve("chat-stars.jsonl"),(SessionStore.JSON.toJson(new Event(1,1,"star","x"))+"\n").getBytes(StandardCharsets.UTF_8));
        try(SessionStore store=new SessionStore(root,false,"test")) {
            ReadSnapshot pin=store.capture(Arrays.asList(new ReadSnapshot.Source(SessionStore.ALL,"chat"),new ReadSnapshot.Source(SessionStore.ALL,"chat-stars")),scratch,new Cancellation());
            try(ArchiveResult<Event> result=ArchiveResult.open(pin,query(a),new Custom(),scratch,new Cancellation())) {
                assertEquals(1,result.matches);assertEquals(2,result.page(0,100,new Cancellation()).counts.get("stars").value);
            }
            pin=store.capture(Collections.singletonList(new ReadSnapshot.Source(a,"chat")),scratch,new Cancellation());
            Custom ignoresMissingDependency=new Custom(){public void scan(ReadSnapshot p,ArchiveQuery<Facets,Sort> q,Sink<Event> out,Cancellation c)throws IOException{p.read("chat",Event.class,out,c);}};
            try(ArchiveResult<Event> ignored=ArchiveResult.open(pin,query(a),ignoresMissingDependency,scratch,new Cancellation())){fail("An undeclared/missing dependency was not validated");}
            catch(IOException expected){assertEquals(0,children(scratch));}
        }
    }
    @Test public void legacyReadersReportCorruptionInsteadOfEmptyOrUnmarkedPartialSuccess()throws Exception {
        Path root=temp.newFolder().toPath();String good=session(root,2),bad=session(root,3);
        Files.write(root.resolve(bad).resolve("session.json"),"{broken".getBytes(StandardCharsets.UTF_8));
        try(SessionStore store=new SessionStore(root,false,"test")) {
            assertEquals(1,store.catalog().stream().filter(e->!e.readable()).count());
            try{store.sessions();fail("Metadata enumeration silently omitted a session");}catch(IOException expected){ }
            for(String scope:Arrays.asList(bad,SessionStore.ALL)) {
                int[] received={0};
                try{store.read(scope,"chat",Event.class,(session,row)->received[0]++);fail("Legacy read silently succeeded for "+scope);}
                catch(IOException expected){assertEquals("Reject incomplete scope before callbacks",0,received[0]);}
            }
            assertEquals(2,store.read(good,"chat",Event.class).size());
        }
    }
    @Test public void csvPreservesNegativeNumbersAndEscapesFormulaShapedText()throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,1);
        List<ArchiveExport.Column<Event>> columns=Arrays.asList(new ArchiveExport.Column<>("Integer",r->-12),new ArchiveExport.Column<>("Decimal",r->new java.math.BigDecimal("-12.50")),
                new ArchiveExport.Column<>("Text number",r->"-12"),new ArchiveExport.Column<>("Formula",r->"=1+1"),new ArchiveExport.Column<>("Plus",r->"+SUM(A1)"),new ArchiveExport.Column<>("At",r->"@value"));
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation());ArchiveResult.Lease<Event> lease=result.lease()) {
            Path file=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"numeric",columns,new Cancellation());
            List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);
            assertTrue(lines.get(2),lines.get(2).endsWith(",\"-12\",\"-12.50\",\"'-12\",\"'=1+1\",\"'+SUM(A1)\",\"'@value\""));
        }
    }
    private static class Custom implements ArchiveAdapter<Event,Facets,Sort> {
        long stars;
        public Class<Event> rowType(){return Event.class;}public String unit(){return "messages";}
        public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Facets,Sort> q){return Arrays.asList(new ReadSnapshot.Source(q.resolvedScope(store),"chat"),new ReadSnapshot.Source(SessionStore.ALL,"chat-stars"));}
        public void scan(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Event> rows,Cancellation cancel)throws IOException{pin.read("chat-stars",Event.class,r->stars++,cancel);pin.read("chat",Event.class,rows,cancel);}
        public Long time(ArchiveRow<Event> row){return row.value.time;}public boolean matches(ArchiveRow<Event> row,ArchiveQuery<Facets,Sort> q){return true;}
        public Comparator<Event> comparator(Sort field){return Comparator.comparingInt(r->r.value);}
        public Map<String,Count> counts(){return Collections.singletonMap("stars",new Count(stars,"stars","all source sessions"));}
    }
}
