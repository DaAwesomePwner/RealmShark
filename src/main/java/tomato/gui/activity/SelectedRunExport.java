package tomato.gui.activity;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import packets.packetcapture.logger.ActivityJournal;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Module-owned linked export. Independent Timeline records are streamed from the displayed visit pin. */
public final class SelectedRunExport {
    private SelectedRunExport() { }
    public static final class Preview {
        private final ArchiveRow<ActivityQueries.Row> selected;
        private final ActivityJournal.Visit visit;
        public final String revision;
        public final long events;
        private Preview(ArchiveRow<ActivityQueries.Row> selected,ActivityJournal.Visit visit,String revision,long events) {
            this.selected=selected;this.visit=visit;this.revision=revision;this.events=events;
        }
        public String description() {
            return "1 selected visit + "+events+" linked Timeline events\nSession "+selected.ref.session
                    +"\nVisit ID "+Objects.toString(visit.id,"Not recorded")+"\nRevision "+revision
                    +"\nAll recorded event types for this exact session/visit; event dates are not clipped to visit query bounds."
                    +"\nUnassigned events and other visits are excluded. Missing coverage remains unknown.";
        }
    }
    public static Preview preview(ArchiveResult.Lease<ActivityQueries.Row> lease,ArchiveRow.Ref ref,Cancellation cancel)throws IOException {
        offEdt();List<ArchiveRow<ActivityQueries.Row>> selected=new ArrayList<>(1);
        lease.stream(ExportSelection.selected(Collections.singleton(ref)),selected::add,cancel);
        ArchiveRow<ActivityQueries.Row> row=selected.get(0);
        ActivityJournal.Visit visit=ActivityQueries.readVisit(lease,row,cancel);
        long[] count={0};events(lease,row,event->count[0]++,cancel);
        return new Preview(row,visit,lease.manifest().get("revision").getAsString(),count[0]);
    }
    private static void events(ArchiveResult.Lease<ActivityQueries.Row> lease,ArchiveRow<ActivityQueries.Row> selected,
                               ArchiveAdapter.Sink<ActivityJournal.Entry> sink,Cancellation cancel)throws IOException {
        String id=selected.value.visitId;
        if(id==null||id.isEmpty())return;
        lease.readSource(selected.ref.session,"timeline",ActivityJournal.Entry.class,row->{
            if(row.ref.session.equals(selected.ref.session)&&id.equals(row.value.visitId))sink.accept(row);
        },cancel);
    }
    public static Path write(ArchiveResult.Lease<ActivityQueries.Row> lease,ArchiveRow.Ref ref,ArchiveExport.Format format,
                             Path folder,String base,Cancellation cancel)throws IOException {
        return write(lease,preview(lease,ref,cancel),format,folder,base,cancel);
    }
    public static Path write(ArchiveResult.Lease<ActivityQueries.Row> lease,Preview preview,ArchiveExport.Format format,
                             Path folder,String base,Cancellation cancel)throws IOException {
        offEdt();cancel.check();
        JsonObject manifest=lease.manifest();
        if(!preview.revision.equals(manifest.get("revision").getAsString()))throw new IOException("Linked preview belongs to another revision");
        if(base==null||!base.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,100}"))throw new IllegalArgumentException("Invalid export filename");
        manifest.addProperty("exportScope","SELECTED_VISIT_AND_LINKED_TIMELINE");
        manifest.addProperty("exportCount",1+preview.events);manifest.addProperty("exportUnit","records (visit + events)");
        manifest.addProperty("selectedVisitCount",1);manifest.addProperty("linkedEventCount",preview.events);
        manifest.add("selected",SessionStore.JSON.toJsonTree(Collections.singletonList(preview.selected.ref)));
        manifest.addProperty("linkedEventPolicy","Exact source session + nonempty recorded visit ID; all linked events from the same pin; visit query filters do not clip linked evidence");
        manifest.addProperty("linkage",preview.visit.id==null||preview.visit.id.isEmpty()?"Visit ID missing; linked evidence unavailable":"Recorded session + visit ID");
        Files.createDirectories(folder);Path stage=Files.createTempFile(folder,".activity-export-",".tmp");
        try {
            long[] written={0};
            try(Writer output=Files.newBufferedWriter(stage,StandardCharsets.UTF_8)) {
                if(format==ArchiveExport.Format.JSON) {
                    JsonWriter json=new JsonWriter(output);json.beginObject();json.name("manifest");SessionStore.JSON.toJson(manifest,json);
                    json.name("rows").beginArray();jsonRow(json,preview.selected.ref,preview.visit);
                    events(lease,preview.selected,row->{jsonRow(json,row.ref,row.value);written[0]++;},cancel);
                    json.endArray().endObject();json.flush();
                } else {
                    output.write("# RealmShark archive manifest,"+csv(manifest.toString())+"\r\nSession,Module,Locator,Child,Summary,Record JSON\r\n");
                    csvRow(output,preview.selected.ref,preview.selected.value.summary,preview.visit);
                    events(lease,preview.selected,row->{csvRow(output,row.ref,ActivitySummaries.event(row.value),row.value);written[0]++;},cancel);
                }
            }
            if(written[0]!=preview.events)throw new IOException("Pinned linked event count changed");
            cancel.check();
            for(long suffix=1;;suffix++) {
                cancel.check();Path target=folder.resolve(base+(suffix==1?"":" ("+suffix+")")+"."+format.name().toLowerCase(Locale.ROOT));
                OutputStream claim;
                try{claim=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
                catch(FileAlreadyExistsException exists){continue;}
                try(OutputStream output=claim;InputStream input=Files.newInputStream(stage)) {
                    byte[] buffer=new byte[65536];int n;
                    while((n=input.read(buffer))>=0){cancel.check();output.write(buffer,0,n);}cancel.check();
                }catch(IOException|RuntimeException|Error failure){try{Files.deleteIfExists(target);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
                return target;
            }
        } finally { Files.deleteIfExists(stage); }
    }
    private static void jsonRow(JsonWriter json,ArchiveRow.Ref ref,Object value)throws IOException {
        json.beginObject();json.name("origin");SessionStore.JSON.toJson(ref,ArchiveRow.Ref.class,json);
        json.name("value");SessionStore.JSON.toJson(value,value.getClass(),json);json.endObject();
    }
    private static void csvRow(Writer output,ArchiveRow.Ref ref,String summary,Object value)throws IOException {
        output.write(csv(ref.session)+","+csv(ref.module)+","+csv(ref.locator)+","+csv(ref.child)+","+csv(summary)+","+csv(SessionStore.JSON.toJson(value))+"\r\n");
    }
    private static String csv(String value) {
        value=Objects.toString(value,"");String trimmed=value.trim();
        if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0)value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private static void offEdt() { if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Read/export must run off the EDT"); }
}
