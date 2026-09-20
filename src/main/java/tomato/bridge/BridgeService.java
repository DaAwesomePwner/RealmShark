package tomato.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
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
        public final boolean loading,closed;
        public final BridgeConfig config;
        Snapshot(List<Review> r,List<Log> l,int q,int c,long o,long a,long s,long f,long revision,String state,boolean loading,boolean closed,BridgeConfig config){reviews=r;logs=l;queued=q;catalogSize=c;observed=o;accepted=a;skipped=s;failed=f;this.revision=revision;this.state=state;this.loading=loading;this.closed=closed;this.config=config;}
    }
    private static final class Holder { static final BridgeService INSTANCE = create(); }
    private static BridgeService create(){return new BridgeService(Paths.get("bridge.properties"),Tomato.isPreview(),new BridgeHttp(),256);}
    public static BridgeService getInstance(){return Holder.INSTANCE;}
    private final Path settings;
    private final boolean preview;
    private final Transport transport;
    private final BridgeStorage storage;
    private final ThreadPoolExecutor worker;
    // One FIFO configuration lane: startup, validation, persistence and lock ownership never overtake each other.
    private final ThreadPoolExecutor configurationWorker;
    private final CountDownLatch ready=new CountDownLatch(1);
    private static final int CONFIGURATION_CAPACITY=8;
    private final ArrayDeque<Log> logs=new ArrayDeque<>();
    private final LinkedHashMap<Long,Review> reviews=new LinkedHashMap<>();
    private volatile BridgeConfig config=new BridgeConfig(new Properties());
    private volatile BridgeCatalog catalog=BridgeCatalog.empty();
    private volatile boolean active,closed;
    private boolean loading=true;
    private long generation,sequence,observed,accepted,skipped,failed,revision;
    private String state="Loading bridge settings…";
    private Closeable folderLock; // Owned exclusively by configurationWorker, including cleanup.

    /** Loads settings in the background without waiting on the caller, including the EDT. Observe Snapshot.loading/state. */
    public BridgeService(Path settings,boolean preview,Transport transport,int queueCapacity) {
        this(settings,preview,transport,queueCapacity,new BridgeStorage());
    }
    BridgeService(Path settings,boolean preview,Transport transport,int queueCapacity,BridgeStorage storage) {
        this.settings=settings;this.preview=preview;this.transport=transport;this.storage=storage;
        worker=worker("realmshark-guild-bridge",queueCapacity);
        configurationWorker=worker("realmshark-bridge-configuration",CONFIGURATION_CAPACITY);
        configurationWorker.execute(this::initialize);
    }
    private static ThreadPoolExecutor worker(String name,int capacity) {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;});
    }
    private void initialize() {
        try {
            checkOpen();
            BridgeConfig loaded=storage.loadSettings(settings);
            // Preserve the saved form even if its CSV is invalid, so the user can correct it.
            synchronized(this){checkOpen();config=loaded;if(preview)state="Preview - sending and saving disabled";}
            if(!preview)configureOnWorker(loaded,false,false);
        } catch(Exception e){synchronized(this){if(!closed){log("ERROR","Bridge startup needs attention: "+safeError(e));state="Settings need attention";}}}
        finally {synchronized(this){loading=false;revision++;}ready.countDown();}
    }
    public BridgeConfig config(){return config;}
    public boolean isPreview(){return preview;}
    /** Synchronous compatibility API; callers must use a background thread (the GUI uses SwingWorker). */
    public void configure(BridgeConfig next,boolean persist,boolean ping) throws IOException {
        if(SwingUtilities.isEventDispatchThread())throw new IOException("Configure the bridge on a background worker.");
        Future<?> update;
        synchronized(this) {
            if(preview)throw new IOException("Preview cannot save settings or send events.");
            checkOpen();
            try {update=configurationWorker.submit(()->{configureOnWorker(next,persist,ping);return null;});}
            catch(RejectedExecutionException e){log("ERROR","Bridge settings queue is full; this change was not accepted.");throw new IOException("Bridge settings queue is full. Wait for the pending change to finish.",e);}
        }
        try {update.get();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for settings; an accepted change may still finish.",e);}
        catch(CancellationException e){throw new IOException("Bridge closed before this settings change started.",e);}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof IOException)throw (IOException)cause;if(cause instanceof RuntimeException)throw (RuntimeException)cause;throw new IOException("Could not configure bridge.",cause);}
    }
    private void checkOpen() throws IOException {if(closed)throw new IOException("Bridge is closed.");}
    private void configureOnWorker(BridgeConfig next,boolean persist,boolean ping) throws IOException {
        checkOpen();next.validate();
        BridgeCatalog loaded=next.enabled?storage.loadCatalog(next):BridgeCatalog.empty();
        Closeable acquired=null;
        try {
            checkOpen();
            if(next.enabled&&folderLock==null)acquired=storage.acquireLock(settings);
            checkOpen();
            if(persist)storage.save(next,settings);
            synchronized(this) {
                checkOpen();
                generation++;config=next;catalog=loaded;active=next.enabled;
                state=active?(next.send?"Ready to send detected drops":"Local review only"):"Disabled";
                log("INFO",state+". CSV items: "+loaded.size()+". Waiting requests from previous settings will be cancelled.");
                if(active&&next.send&&ping)enqueuePing(next,generation);
            }
            if(acquired!=null){folderLock=acquired;acquired=null;}
            if(!next.enabled)releaseLock();
        } finally {if(acquired!=null)release(acquired);}
    }
    private void releaseLock(){Closeable previous=folderLock;folderLock=null;if(previous!=null)release(previous);}
    private void release(Closeable lock){try{lock.close();}catch(IOException|RuntimeException e){log("ERROR","Could not release the bridge folder lock ("+e.getClass().getSimpleName()+").");}}
    public void receive(TomatoData data,MapInfoPacket map,Entity bag,Entity player,long time) {
        if(!active||preview)return;
        try {receive(BridgePayload.snapshot(data,map,bag,player,time));}
        catch(RuntimeException e){log("ERROR","Could not snapshot this loot bag ("+e.getClass().getSimpleName()+"). Other capture modules remain active.");}
    }
    public void receive(List<BridgePayload.Drop> drops) {
        for(BridgePayload.Drop drop:drops) {
            for(;;) {
                BridgeConfig target;BridgeCatalog items;long version;
                synchronized(this){if(!active||preview||closed)return;target=config;items=catalog;version=generation;}
                boolean tracked=items.contains(drop.item), included=target.includes(drop.item);
                String status=!tracked?"Not in CSV":!included?"Filtered":!target.send?"Local only":"Queued";
                String detail=!tracked?"Item is absent from the CSV allowlist; nothing sent.":!included?"Excluded by category selection.":!target.send?"Sending is turned off.":"Awaiting HTTP submission.";
                if(drop.item.enchantCount<0)detail+=" Enchant data could not be decoded; rarity may be unknown.";
                JsonObject payload=target.send&&tracked&&included?BridgePayload.loot(target,drop):null;
                String redacted=payload==null?"":BridgePayload.redacted(payload).toString();
                synchronized(this) {
                    if(!active||preview||closed)return;
                    if(version!=generation)continue;
                    observed++;
                    Review entry=new Review(++sequence,Instant.now().toString(),drop,status,detail,redacted);
                    reviews.put(entry.id,entry);while(reviews.size()>1000)reviews.remove(reviews.keySet().iterator().next());revision++;
                    if(!status.equals("Queued"))skipped++;
                    try {worker.execute(()->process(entry,payload,target,version));}
                    catch(RejectedExecutionException e){finish(entry,"Queue full","Worker queue is full; this item was not sent.",target,false);failed++;log("ERROR","Bridge queue is full; an event could not be processed. Review its status.");}
                }
                break;
            }
        }
    }
    private void process(Review entry,JsonObject payload,BridgeConfig target,long version) {
        if(payload==null){audit(entry,target);if(target.debug)log("DEBUG",entry.status+": "+entry.drop.item.rawName);return;}
        boolean cancelled;
        synchronized(this){cancelled=!active||version!=generation||closed;if(cancelled)skipped++;}
        if(cancelled){finish(entry,"Cancelled","Settings changed before this request started; nothing sent.",target,true);return;}
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
        try {storage.audit(entry,target);}
        catch(IOException|RuntimeException e){log("ERROR","Could not write the local review log ("+e.getClass().getSimpleName()+"). Check its path and folder permissions.");}
    }
    private static String safeError(Exception e){return e instanceof IllegalArgumentException?e.getMessage():"Check settings, CSV path and whether another instance is running ("+e.getClass().getSimpleName()+").";}
    private synchronized void log(String level,String message) {
        if(!config.token.isEmpty())message=message.replace(config.token,"[redacted]");
        logs.addLast(new Log(level,message));while(logs.size()>500)logs.removeFirst();revision++;
    }
    public synchronized Snapshot snapshot(){return new Snapshot(new ArrayList<>(reviews.values()),new ArrayList<>(logs),worker.getQueue().size(),catalog.size(),observed,accepted,skipped,failed,revision,state,loading,closed,config);}
    public synchronized void clearLogs(){logs.clear();revision++;}
    private static void requireBackgroundWait(){if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Do not wait for bridge workers on the EDT.");}
    /** Startup has settled (possibly with an error in snapshot().state), or close has invalidated it. */
    public void awaitReady(long millis)throws InterruptedException,TimeoutException {requireBackgroundWait();if(!ready.await(millis,TimeUnit.MILLISECONDS))throw new TimeoutException("Bridge settings are still loading.");}
    public void awaitIdle(long millis)throws Exception {requireBackgroundWait();worker.submit(()->{}).get(millis,TimeUnit.MILLISECONDS);}
    /** Optional off-EDT shutdown barrier; close itself never waits for storage or an in-flight send. */
    public void awaitClosed(long millis)throws InterruptedException,TimeoutException {
        requireBackgroundWait();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        if(!configurationWorker.awaitTermination(millis,TimeUnit.MILLISECONDS)||!worker.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))throw new TimeoutException("Bridge workers are still closing.");
    }
    /** Invalidates pending work immediately; an already-started save/send may finish, but cannot reactivate the service. */
    @Override public synchronized void close(){
        if(closed)return;
        active=false;closed=true;loading=false;generation++;state="Closed";revision++;ready.countDown();
        // Admission and close share only this short model lock. Reserve cleanup space by cancelling queued configs.
        List<Runnable> pending=new ArrayList<>();configurationWorker.getQueue().drainTo(pending);
        for(Runnable task:pending)if(task instanceof Future<?>)((Future<?>)task).cancel(false);
        configurationWorker.execute(this::releaseLock);configurationWorker.shutdown();
        worker.shutdown(); // Drain reviews as cancelled; requests already in flight retain their original target.
    }
}
