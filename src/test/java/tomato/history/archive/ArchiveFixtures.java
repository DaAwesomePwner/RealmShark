package tomato.history.archive;

import tomato.history.SessionStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ArchiveFixtures {
    private ArchiveFixtures(){ }
    public enum Sort { VALUE, TIME }
    public static final class Facets {
        public final int minimum;
        public final Set<String> groups;
        public Facets(int minimum,String... groups){this.minimum=minimum;this.groups=new LinkedHashSet<>(Arrays.asList(groups));}
    }
    public static final class Event {
        public long time;public int value;public String group,text;
        public Event(long time,int value,String group,String text){this.time=time;this.value=value;this.group=group;this.text=text;}
    }
    public static String session(Path root,int records)throws IOException {
        String id=UUID.randomUUID().toString();Path directory=Files.createDirectories(root.resolve(id));
        metadata(directory,id,"Synthetic session",1000);
        try(BufferedWriter writer=Files.newBufferedWriter(directory.resolve("chat.jsonl"),StandardCharsets.UTF_8)){
            for(int i=0;i<records;i++){writer.write(SessionStore.JSON.toJson(new Event(i,i,i%2==0?"even":"odd","Message "+i)));writer.newLine();}
        }
        return id;
    }
    public static void metadata(Path directory,String id,String label,long started)throws IOException {
        com.google.gson.JsonObject json=new com.google.gson.JsonObject();json.addProperty("schemaVersion",1);json.addProperty("id",id);
        json.addProperty("started",started);json.addProperty("ended",started+1);json.addProperty("label",label);json.addProperty("version","synthetic");
        Files.write(directory.resolve("session.json"),json.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static ArchiveQuery<Facets,Sort> query(String scope){return ArchiveQuery.of(scope,new Facets(0),Facets.class,Sort.VALUE);}
    public static ArchiveAdapter<Event,Facets,Sort> adapter(){
        return ArchiveAdapter.records("chat",Event.class,"messages",event->event.time,(event,q)->{
            Facets f=q.facets();return event.value>=f.minimum&&(f.groups.isEmpty()||f.groups.contains(event.group))&&event.text.contains(q.text());
        },field->field==Sort.TIME?Comparator.comparingLong(event->event.time):Comparator.comparingInt(event->event.value));
    }
    public static long children(Path path)throws IOException {try(java.util.stream.Stream<Path> files=Files.list(path)){return files.count();}}
}
