package tomato.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import tomato.Tomato;
import tomato.backend.data.*;
import packets.incoming.MapInfoPacket;

/** Bounded background sender and local review journal, independent of the legacy sharing socket. */
public final class BridgeService implements AutoCloseable {
    public interface Transport { Response post(String endpoint,String json) throws IOException; }
    public static final class Response {
        public final int status; public final String body;
        public Response(int status,String body){this.status=status;this.body=body;}
    }
    public static final class Review {
        public final long id; public final String time;
        public final BridgePayload.Drop drop;
        public final String status, detail, payload;
        private Review(long id,String time,BridgePayload.Drop drop,String status,String detail,String payload){this.id=id;this.time=time;this.drop=drop;this.status=status;this.detail=detail;this.payload=payload;}
        Review with(String status,String detail){return new Review(id,time,drop,status,detail,payload);}
    }
    public static final class Log {
        public final String time,level,message;
        Log(String level,String message){time=Instant.now().toString();this.level=level;this.message=message;}
    }
    public static final class Snapshot {
        public final List<Review> reviews;public final List<Log> logs;
        public final int queued,catalogSize; public final long observed,accepted,skipped,failed,revision;
        public final String state;
        Snapshot(List<Review> r,List<Log> l,int q,int c,long o,long a,long s,long f,long revision,String state){reviews=r;logs=l;queued=q;catalogSize=c;observed=o;accepted=a;skipped=s;failed=f;this.revision=revision;this.state=state;}
    }
    private static final class Holder { static final BridgeService INSTANCE = create(); }
    private static BridgeService create(){return new BridgeService(Paths.get("bridge.properties"),Tomato.isPreview(),new BridgeHttp(),256);}
    public static BridgeService getInstance(){return Holder.INSTANCE;}
    private final Path settings;
    private final boolean preview;
    private final Transport transport;
    private final ThreadPoolExecutor worker;
    private final ArrayDeque<Log> logs=new ArrayDeque<>();
    private final LinkedHashMap<Long,Review> reviews=new LinkedHashMap<>();
    private volatile BridgeConfig config=new BridgeConfig(new Properties());
    private volatile BridgeCatalog catalog=BridgeCatalog.empty();
    private volatile boolean active,closed;
    private long generation,sequence,observed,accepted,skipped,failed,revision;
    private String state="Disabled";
    private FileChannel lockChannel; private FileLock lock;

    public BridgeService(Path settings,boolean preview,Transport transport,int queueCapacity) {
        this.settings=settings;this.preview=preview;this.transport=transport;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queueCapacity),r->{Thread t=new Thread(r,"realmshark-guild-bridge");t.setDaemon(true);return t;});
        try {
            BridgeConfig loaded=BridgeConfig.load(settings);
            config=loaded;
            if(preview){config=loaded;state="Preview - sending and saving disabled";}
            else configure(loaded,false,false);
        } catch(Exception e){log("ERROR","Bridge startup needs attention: "+safeError(e));state="Settings need attention";}
    }
    public BridgeConfig config(){return config;}
    public boolean isPreview(){return preview;}
    /** Called by SwingWorker, never on capture/EDT. Validation finishes before replacing active settings. */
    public synchronized void configure(BridgeConfig next,boolean persist,boolean ping) throws IOException {
        if(preview)throw new IOException("Preview cannot save settings or send events.");
        if(closed)throw new IOException("Bridge is closed.");
        next.validate();
        BridgeCatalog loaded=next.enabled?BridgeCatalog.load(Paths.get(next.csvPath)):BridgeCatalog.empty();
        boolean acquired=false;
        if(next.enabled && lock==null){acquireLock();acquired=true;}
        try {if(persist)next.save(settings);} catch(IOException e){if(acquired)releaseLock();throw e;}
        generation++;config=next;catalog=loaded;active=next.enabled;
        if(!active)releaseLock();
        state=active?(next.send?"Ready to send detected drops":"Local review only"):"Disabled";
        log("INFO",state+". CSV items: "+loaded.size()+". Waiting requests from previous settings will be cancelled.");
        if(active&&next.send&&ping)enqueuePing(next,generation);
    }
    private void acquireLock() throws IOException {
        Path path=settings.toAbsolutePath().resolveSibling(".runtime").resolve("bridge.lock");Files.createDirectories(path.getParent());
        lockChannel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {lock=lockChannel.tryLock();}catch(OverlappingFileLockException e){lock=null;}catch(IOException e){lockChannel.close();lockChannel=null;throw e;}
        if(lock==null){lockChannel.close();lockChannel=null;throw new IOException("Another instance in this folder already owns the bridge. Close it before enabling here.");}
    }
    private void releaseLock(){try{if(lock!=null)lock.release();}catch(IOException ignored){}finally{lock=null;}try{if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){}finally{lockChannel=null;}}
    public void receive(TomatoData data,MapInfoPacket map,Entity bag,Entity player,long time) {
        if(!active||preview)return;
        try {receive(BridgePayload.snapshot(data,map,bag,player,time));}
        catch(RuntimeException e){log("ERROR","Could not snapshot this loot bag ("+e.getClass().getSimpleName()+"). Other capture modules remain active.");}
    }
    public synchronized void receive(List<BridgePayload.Drop> drops) {
        if(!active||preview||closed)return;
        BridgeConfig target=config;long version=generation;
        for(BridgePayload.Drop drop:drops) {
            observed++;
            boolean tracked=catalog.contains(drop.item), included=target.includes(drop.item);
            String status=!tracked?"Not in CSV":!included?"Filtered":!target.send?"Local only":"Queued";
            String detail=!tracked?"Item is absent from the CSV allowlist; nothing sent.":!included?"Excluded by category selection.":!target.send?"Sending is turned off.":"Awaiting HTTP submission.";
            if(drop.item.enchantCount<0)detail+=" Enchant data could not be decoded; rarity may be unknown.";
            JsonObject payload=target.send&&tracked&&included?BridgePayload.loot(target,drop):null;
            Review entry=new Review(++sequence,Instant.now().toString(),drop,status,detail,payload==null?"":BridgePayload.redacted(payload).toString());
            reviews.put(entry.id,entry);while(reviews.size()>1000)reviews.remove(reviews.keySet().iterator().next());revision++;
            if(!status.equals("Queued"))skipped++;
            try {worker.execute(()->process(entry,payload,target,version));}
            catch(RejectedExecutionException e){finish(entry,"Queue full","Worker queue is full; this item was not sent.",target,false);failed++;log("ERROR","Bridge queue is full; an event could not be processed. Review its status.");}
        }
    }
    private void process(Review entry,JsonObject payload,BridgeConfig target,long version) {
        if(payload==null){audit(entry,target);if(target.debug)log("DEBUG",entry.status+": "+entry.drop.item.rawName);return;}
        synchronized(this){if(!active||version!=generation||closed){skipped++;finish(entry,"Cancelled","Settings changed before this request started; nothing sent.",target,true);return;}}
        send(entry,payload,target);
    }
    private void enqueuePing(BridgeConfig target,long version) {
        try {worker.execute(()->{synchronized(this){if(!active||generation!=version||closed)return;}send(null,BridgePayload.settingsPing(target),target);});log("INFO","Settings confirmation queued. The bot may announce the successful link in Discord.");}
        catch(RejectedExecutionException e){log("ERROR","Settings saved, but confirmation could not be queued. Queue is full.");}
    }
    private void send(Review entry,JsonObject payload,BridgeConfig target) {
        String label=entry==null?"Settings confirmation":entry.drop.item.rawName;
        if(target.debug)log("DEBUG","POST "+label+" | "+BridgePayload.redacted(payload));
        try {
            Response response=transport.post(target.endpoint,payload.toString());
            String detail="HTTP "+response.status;
            String status=response.status>=200&&response.status<300?"Accepted":"Rejected";
            try {
                JsonObject body=JsonParser.parseString(response.body).getAsJsonObject();
                if(body.has("ok")&&!body.get("ok").getAsBoolean())status="Rejected";
                JsonObject result=body.has("result")&&body.get("result").isJsonObject()?body.getAsJsonObject("result"):body;
                for(String key:new String[]{"reason","routing_reason","error"}) {
                    JsonElement value=result.get(key);if(value==null)value=body.get(key);
                    if(value!=null&&value.isJsonPrimitive()) {String code=value.getAsString();if(code.matches("[a-zA-Z0-9_]{1,96}"))detail+=" | "+key+"="+code;}
                }
                if(status.equals("Accepted")&&result.has("logged"))status=result.get("logged").getAsBoolean()?"Logged":entry==null?"Accepted":"Not logged";
                if(result.has("announced")&&result.get("announced").isJsonPrimitive())detail+=" | announced="+result.get("announced").getAsBoolean();
            }catch(RuntimeException ignored){detail+=" | No recognized bot result; HTTP receipt alone does not confirm loot logging.";}
            detail=detail.replace(target.token,"[redacted]");
            if(status.equals("Rejected"))detail+=". Check endpoint, token, guild and bot settings; no automatic retry.";
            synchronized(this){if(entry!=null){if(status.equals("Rejected"))failed++;else accepted++;}}
            log(status.equals("Rejected")?"ERROR":"INFO",label+": "+status+" | "+detail);
            if(entry!=null)finish(entry,status,detail,target,true);
        } catch(IOException|RuntimeException e) {
            synchronized(this){if(entry!=null)failed++;}
            String detail="Delivery is uncertain ("+e.getClass().getSimpleName()+"). Check the bot before resubmitting; automatic retries could duplicate loot.";
            log("ERROR",label+": "+detail);if(entry!=null)finish(entry,"Uncertain",detail,target,true);
        }
    }
    private void finish(Review entry,String status,String detail,BridgeConfig target,boolean save) {
        Review updated=entry.with(status,detail);
        synchronized(this){if(reviews.containsKey(entry.id))reviews.put(entry.id,updated);revision++;}
        if(save)audit(updated,target);
    }
    private void audit(Review entry,BridgeConfig target) {
        if(target.reviewLog.isEmpty())return;
        try {
            Path path=Paths.get(target.reviewLog).toAbsolutePath();Files.createDirectories(path.getParent());
            if(Files.exists(path)&&Files.size(path)>5*1024*1024)Files.move(path,path.resolveSibling(path.getFileName()+".1"),StandardCopyOption.REPLACE_EXISTING);
            String json=new Gson().toJson(entry)+System.lineSeparator();
            Files.write(path,json.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException|RuntimeException e){log("ERROR","Could not write the local review log ("+e.getClass().getSimpleName()+"). Check its path and folder permissions.");}
    }
    private static String safeError(Exception e){return e instanceof IllegalArgumentException?e.getMessage():"Check settings, CSV path and whether another instance is running ("+e.getClass().getSimpleName()+").";}
    private synchronized void log(String level,String message) {
        if(!config.token.isEmpty())message=message.replace(config.token,"[redacted]");
        logs.addLast(new Log(level,message));while(logs.size()>500)logs.removeFirst();revision++;
    }
    public synchronized Snapshot snapshot(){return new Snapshot(new ArrayList<>(reviews.values()),new ArrayList<>(logs),worker.getQueue().size(),catalog.size(),observed,accepted,skipped,failed,revision,state);}
    public synchronized void clearLogs(){logs.clear();revision++;}
    public void awaitIdle(long millis)throws Exception {worker.submit(()->{}).get(millis,TimeUnit.MILLISECONDS);}
    @Override public synchronized void close(){active=false;closed=true;generation++;worker.shutdown();releaseLock();state="Closed";}
}
