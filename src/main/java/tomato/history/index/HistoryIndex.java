package tomato.history.index;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;

/** Optional derived cache. Mutations enqueue work; queries use independent read-only connections off the UI thread. */
public final class HistoryIndex implements AutoCloseable {
    public enum Phase { UNAVAILABLE, BUILDING, READY }
    public record State(Phase phase, String reason, int done, int total) {}
    public enum Readiness { READY, STALE, FAILED, CLAIMED, MISSING }
    public record SessionState(Readiness readiness, long skipped, String reason, Set<String> unindexed) {}
    public record Hit(Kind kind, Locator locator, String title, String snippet, String session, String visitId, Long time, double rank) {}
    public enum RunSort { NEWEST, OLDEST, MAP, DURATION, DAMAGE, PLAYERS, ISSUES, OUTCOME }
    public record RunFilter(String session, String map, Set<String> outcomes, Long since, Long until,
                            Long minimumDuration, Long maximumDuration, Boolean issues) {
        public RunFilter { outcomes = outcomes == null ? Set.of() : Set.copyOf(outcomes); }
        public static RunFilter all() { return new RunFilter(null,null,Set.of(),null,null,null,null,null); }
    }
    public record Facts(List<Map<String,Object>> timeline, List<Map<String,Object>> lootBags,
                        List<Map<String,Object>> lootItems, List<Map<String,Object>> fame, List<Map<String,Object>> combat) {}
    @FunctionalInterface interface NativeLoader { void load(Path nativeDirectory) throws Exception; }
    @FunctionalInterface interface ReplacementHook { void afterDelete(String session) throws Exception; }
    private record Offer(String session,String module,long offset,String key,Object value) {}
    private static final int BATCH = 2000;
    private final SessionStore store;
    private final Path file, nativeDirectory;
    private final String instance = UUID.randomUUID().toString();
    private final NativeLoader loader;
    private final ReplacementHook replacementHook;
    private final LongSupplier nanoTime;
    private final ScheduledExecutorService writer;
    private final ArrayBlockingQueue<Offer> offers;
    private final ConcurrentMap<String,Long> stale = new ConcurrentHashMap<>();
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final Object ingestionLock = new Object();
    private record Retry(int attempts,long after) {}
    private final Map<String,Retry> retries = new HashMap<>(); // writer only
    private Retry maintenanceRetry;
    private final ConcurrentMap<String,SessionState> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong revision = new AtomicLong(), replacements = new AtomicLong(), overflows = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean(), closing = new AtomicBoolean(), rebuild = new AtomicBoolean(), drainQueued = new AtomicBoolean();
    private final CompletableFuture<State> initial = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile State state = new State(Phase.BUILDING,"",0,0);
    private volatile boolean includeChat;
    private volatile boolean readable;
    private boolean appliedChat;
    private boolean compactNeeded;
    private boolean initialPending=true;
    private long compactions;
    private long lastHeartbeat;
    private Connection db; // writer thread only
    private IndexStorage storage; // scoped to one writer transaction

    public HistoryIndex(SessionStore store, boolean includeChat) {
        this(store,store.directory().resolve("index/search-v1.db"),includeChat);
    }
    public HistoryIndex(SessionStore store, Path indexFile, boolean includeChat) {
        this(store,indexFile,includeChat,8192,HistoryIndex::loadNative,session -> {});
    }
    HistoryIndex(SessionStore store, Path indexFile, boolean includeChat, int capacity, NativeLoader loader, ReplacementHook hook) {
        this(store,indexFile,includeChat,capacity,loader,hook,System::nanoTime);
    }
    HistoryIndex(SessionStore store, Path indexFile, boolean includeChat, int capacity, NativeLoader loader, ReplacementHook hook,LongSupplier nanoTime) {
        this.store=Objects.requireNonNull(store); file=indexFile.toAbsolutePath().normalize(); this.includeChat=includeChat;
        this.loader=loader; replacementHook=hook; this.nanoTime=nanoTime; offers=new ArrayBlockingQueue<>(capacity);
        String override=System.getProperty("realmshark.indexNativeDir");
        nativeDirectory=override==null?store.directory().resolveSibling("native"):Path.of(override).toAbsolutePath().normalize();
        writer=Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t=new Thread(r,"RealmShark search index"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
        });
    }
    private static synchronized void loadNative(Path directory) throws Exception {
        if (!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))
            throw new IOException("Search requires the bundled Windows SQLite library");
        Files.createDirectories(directory);
        System.setProperty("org.sqlite.tmpdir",directory.toString());
        Class.forName("org.sqlite.JDBC");
        // Force native initialization before any database recovery handling.
        try (Connection ignored=DriverManager.getConnection("jdbc:sqlite::memory:")) { }
    }
    public State state() { return state; }
    public SessionState sessionState(String session) {
        SessionState known=sessions.get(session);
        if (known!=null && known.readiness==Readiness.FAILED) return known;
        if (stale.containsKey(session)) return new SessionState(Readiness.STALE,0,"Waiting for file refresh",Set.of());
        return sessions.getOrDefault(session,new SessionState(Readiness.MISSING,0,"Not indexed",Set.of()));
    }
    public boolean ready(String session) { return readable && state.phase!=Phase.UNAVAILABLE && sessionState(session).readiness==Readiness.READY; }
    /** Listener runs on the writer; UI clients must dispatch to their own UI thread. */
    public AutoCloseable listen(Consumer<State> listener) { listeners.add(listener); return () -> listeners.remove(listener); }
    public long replacementCount() { return replacements.get(); }
    public long overflowCount() { return overflows.get(); }
    long compactionCount() { return compactions; }
    public CompletableFuture<State> start() {
        if (closing.get()) return CompletableFuture.completedFuture(state);
        if (started.compareAndSet(false,true)) writer.execute(() -> {
            boolean opening=true;
            try { open(); opening=false; initialBackfill(); initialPending=false; }
            catch (Exception | LinkageError failure) { backgroundFailure(failure,opening); }
            finally { initial.complete(state); }
            if (!closing.get()) writer.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
        });
        return initial;
    }
    public void rebuild() { rebuild.set(true); }
    public void setIncludeChat(boolean value) { includeChat=value; }
    public boolean includeChat() { return includeChat; }
    /** record must be a detached saved value, as in SessionStore's producer contract. No serialization or I/O here. */
    public void offer(String session,String module,long offsetOrMinusOne,String keyOrNull,Object record) {
        if (closing.get() || state.phase==Phase.UNAVAILABLE || !validSession(session) || !Projections.modules().contains(module)) return;
        synchronized (ingestionLock) {
            if (state.phase==Phase.UNAVAILABLE || removed.contains(session)) return;
            if (keyOrNull==null && offsetOrMinusOne<0) { markSessionChanged(session); return; }
            if (!offers.offer(new Offer(session,module,offsetOrMinusOne,keyOrNull,record))) {
                overflows.incrementAndGet(); markSessionChanged(session);
            } else if (offers.size()>=BATCH && started.get() && drainQueued.compareAndSet(false,true)) {
                try { writer.execute(() -> { drainQueued.set(false); tick(); }); }
                catch (RejectedExecutionException ignored) { drainQueued.set(false); }
            }
        }
    }
    public void markSessionChanged(String session) {
        synchronized (ingestionLock) {
            if (!closing.get() && state.phase!=Phase.UNAVAILABLE && validSession(session)) stale.put(session,revision.incrementAndGet());
        }
    }
    public void removeSession(String session) {
        synchronized (ingestionLock) { if (state.phase!=Phase.UNAVAILABLE && validSession(session)) { removed.add(session); stale.remove(session); } }
    }
    /** A completion barrier for background callers/tests. Never waits on the calling thread. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> done=new CompletableFuture<>();
        if (closing.get()) return closed;
        start();
        try { writer.execute(() -> { tick(); done.complete(null); }); }
        catch (RejectedExecutionException failure) { done.completeExceptionally(failure); }
        return done;
    }
    private void publish(State next) {
        state=next;
        for (Consumer<State> listener:listeners) try { listener.accept(next); } catch (RuntimeException ignored) { }
    }
    private void unavailable(Throwable failure) {
        rebuild.set(false);
        closeConnection();
        String reason=!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "Only Windows SQLite native libraries are bundled"
                : failure instanceof LinkageError ? "SQLite native library could not be loaded" : "SQLite or index storage could not be accessed";
        State next=new State(Phase.UNAVAILABLE,reason+" ("+failure.getClass().getSimpleName()+")",0,0);
        synchronized (ingestionLock) {
            state=next;
            offers.clear(); stale.clear(); removed.clear();
        }
        sessions.clear(); retries.clear();
        publish(next);
    }
    private void open() throws Exception {
        loader.load(nativeDirectory);
        Files.createDirectories(file.getParent());
        try { openChecked(); }
        catch (SQLException failure) {
            closeConnection();
            // Contention and filesystem permissions do not establish corruption; never move another instance's busy database.
            if (!corruption(failure)) throw failure;
            quarantine(); openChecked();
        }
    }
    private static boolean corruption(SQLException failure) {
        return failure.getErrorCode()==11 || failure.getErrorCode()==26 || failure.getErrorCode()==14;
    }
    private static boolean busy(Throwable failure) {
        for (Throwable cause=failure;cause!=null;cause=cause.getCause())
            if (cause instanceof SQLException sql && ((sql.getErrorCode() & 255)==5 || (sql.getErrorCode() & 255)==6)) return true;
        return false;
    }
    private Retry later(Retry previous) {
        int attempts=previous==null?1:Math.min(7,previous.attempts+1);
        return new Retry(attempts,nanoTime.getAsLong()+TimeUnit.SECONDS.toNanos(Math.min(60,1L<<(attempts-1))));
    }
    private boolean due(Retry retry) { return retry==null || nanoTime.getAsLong()-retry.after>=0; }
    private void backgroundFailure(Throwable failure,boolean opening) {
        if (!busy(failure) && (opening || failure instanceof LinkageError || failure instanceof SQLException sql && corruption(sql))) {
            unavailable(failure); return;
        }
        maintenanceRetry=later(maintenanceRetry);
        publish(new State(state.phase,"Index work will retry ("+failure.getClass().getSimpleName()+")",state.done,state.total));
    }
    private void retrySession(String session,Exception failure) {
        retries.put(session,later(retries.get(session)));
        synchronized (ingestionLock) {
            if (!removed.contains(session)) stale.putIfAbsent(session,revision.incrementAndGet());
        }
        boolean contention=busy(failure);
        sessions.put(session,new SessionState(contention?Readiness.STALE:Readiness.FAILED,0,
                contention?"Index busy; waiting to retry":"Session could not be indexed ("+failure.getClass().getSimpleName()+")",Set.of()));
    }
    private void openChecked() throws SQLException {
        db=DriverManager.getConnection("jdbc:sqlite:"+file);
        IndexSchema.configure(db);
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("PRAGMA quick_check")) {
            if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("Index integrity check failed","",11);
        }
        IndexSchema.exec(db,"PRAGMA journal_mode=WAL"); IndexSchema.exec(db,"PRAGMA synchronous=NORMAL");
        String version=null;
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            if (r.next()) version=r.getString(1);
        } catch (SQLException missing) {
            if (!missing.getMessage().contains("no such table")) throw missing;
        }
        if (!Integer.toString(IndexSchema.VERSION).equals(version)) {
            db.setAutoCommit(false);
            try { IndexSchema.drop(db); IndexSchema.create(db); db.commit(); compactNeeded=true; }
            catch (SQLException failure) { db.rollback(); throw failure; }
            finally { db.setAutoCommit(true); }
        }
        appliedChat="true".equals(meta("include_chat"));
        readable=true;
    }
    private void quarantine() throws IOException {
        String name="search-corrupt-"+System.currentTimeMillis()+".db";
        Path target=file.resolveSibling(name);
        for (String suffix:List.of("","-wal","-shm")) {
            Path source=Path.of(file+suffix);
            if (Files.exists(source)) Files.move(source,Path.of(target+suffix));
        }
    }
    private String meta(String key) throws SQLException {
        try (PreparedStatement p=db.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            p.setString(1,key); try (ResultSet r=p.executeQuery()) { return r.next()?r.getString(1):null; }
        }
    }
    private void meta(String key,String value) throws SQLException { execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",key,value); }
    private void tick() {
        if (closing.get() || !due(maintenanceRetry)) return;
        boolean changed=rebuild.get() || appliedChat!=includeChat || !offers.isEmpty() || !stale.isEmpty() || !removed.isEmpty();
        try {
            if (initialPending && state.phase!=Phase.UNAVAILABLE) {
                if (db==null) open();
                initialBackfill(); initialPending=false;
            }
            if (rebuild.getAndSet(false)) {
                sessions.clear(); retries.clear(); publish(new State(Phase.BUILDING,"",0,0));
                try {
                    if (db==null) open();
                    transaction(() -> { IndexSchema.drop(db); IndexSchema.create(db); });
                }
                catch (Exception failure) { rebuild.set(true); throw failure; }
                compactNeeded=true; appliedChat=false; initialPending=true; initialBackfill(); initialPending=false;
            }
            if (db==null) return;
            if (appliedChat!=includeChat) { if (includeChat) backfill(); else applyChatPolicy(); }
            for (String session:List.copyOf(removed)) {
                transaction(() -> { deleteSession(session); refreshPlayers(); });
                synchronized (ingestionLock) {
                    // The tombstone lasts through commit. Purge all pre-removal offers before accepting a re-import.
                    offers.removeIf(offer -> offer.session.equals(session));
                    // Preserve changes after removal was requested, including an immediate re-import of the same UUID.
                    sessions.remove(session); retries.remove(session); removed.remove(session);
                }
            }
            drainOffers();
            for (Map.Entry<String,Long> entry:List.copyOf(stale.entrySet())) {
                if (removed.contains(entry.getKey())) continue;
                if (due(retries.get(entry.getKey())) && indexSession(entry.getKey(),true)) stale.remove(entry.getKey(),entry.getValue());
            }
            for (Map.Entry<String,SessionState> entry:List.copyOf(sessions.entrySet()))
                if (entry.getValue().readiness==Readiness.CLAIMED && !stale.containsKey(entry.getKey()) && !removed.contains(entry.getKey())) {
                    indexSession(entry.getKey(),false); changed=true;
                }
            if (maintenanceRetry!=null) publish(new State(state.phase,"",state.done,state.total));
            else if (changed) publish(state);
            maintenanceRetry=null;
        } catch (Exception | LinkageError failure) { backgroundFailure(failure,db==null); }
    }
    private void applyChatPolicy() throws Exception {
        boolean chat=includeChat;
        if (appliedChat!=chat) {
            transaction(() -> {
                if (!chat) {
                    storage.deleteChat(); refreshPlayers();
                }
                if (chat) execute("UPDATE sessions SET stamp=NULL,index_state='STALE'");
                meta("include_chat",Boolean.toString(chat));
            }); appliedChat=chat;
            // Open sessions are omitted by startup backfill, but an already indexed live session also needs the new policy.
            if (chat) for (String session:sessions.keySet()) markSessionChanged(session);
        }
    }
    private void backfill() throws Exception {
        applyChatPolicy();
        List<SessionStore.SessionEntry> catalog=new ArrayList<>(store.catalog());
        Set<String> present=new HashSet<>(); for (SessionStore.SessionEntry entry:catalog) present.add(entry.id);
        List<String> gone=new ArrayList<>();
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT id FROM sessions")) {
            while (r.next()) if (!present.contains(r.getString(1)) && !store.currentId().equals(r.getString(1))) gone.add(r.getString(1));
        }
        if (!gone.isEmpty()) transaction(() -> { for (String session:gone) { deleteSession(session); sessions.remove(session); } refreshPlayers(); });
        catalog.removeIf(e -> e.id.equals(store.currentId()) || e.readable() && e.session().ended<=0 || removed.contains(e.id));
        catalog.sort(Comparator.comparingLong((SessionStore.SessionEntry e) -> e.readable()?e.session().started:0).reversed());
        int done=0; publish(new State(Phase.BUILDING,"",0,catalog.size()));
        for (SessionStore.SessionEntry entry:catalog) {
            if (closing.get()) return;
            Long pending=stale.get(entry.id);
            if (due(retries.get(entry.id)) && indexSession(entry.id,false) && pending!=null) stale.remove(entry.id,pending);
            publish(new State(Phase.BUILDING,"",++done,catalog.size()));
        }
        publish(new State(Phase.READY,"",done,catalog.size()));
    }
    private void initialBackfill() throws Exception {
        IndexSchema.exec(db,"PRAGMA cache_size=-65536"); IndexSchema.exec(db,"PRAGMA temp_store=MEMORY");
        long before=replacements.get();
        long indexed;
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT count(*) FROM sessions")) { r.next(); indexed=r.getLong(1); }
        try {
            backfill();
            // A small incremental startup is left to FTS automerge. Require substantial churn to compact an existing index.
            compactNeeded |= replacements.get()-before>Math.max(10,indexed/4);
            if (compactNeeded && !closing.get()) {
                IndexSchema.exec(db,"INSERT INTO docs(docs) VALUES('optimize')");
                if (closing.get()) return;
                IndexSchema.exec(db,"INSERT INTO names(names) VALUES('optimize')");
                if (closing.get()) return;
                // Segment merges and a requested rebuild may have freed many pages; return those bytes to the filesystem.
                IndexSchema.exec(db,"VACUUM"); compactNeeded=false; compactions++;
            }
        } finally { IndexSchema.exec(db,"PRAGMA cache_size=-2000"); IndexSchema.exec(db,"PRAGMA temp_store=DEFAULT"); }
    }
    private boolean indexSession(String session,boolean force) throws Exception {
        Path folder=store.directory().resolve(session);
        boolean claimed=false;
        try {
            if (!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)) {
                transaction(() -> { deleteSession(session); refreshPlayers(); }); sessions.remove(session); retries.remove(session); return true;
            }
            if (!claim(session)) { sessions.put(session,new SessionState(Readiness.CLAIMED,0,"Another instance is indexing this session",Set.of())); return false; }
            claimed=true;
            SessionStore.Session metadata=metadata(folder,session);
            String stamp=stamp(folder);
            if (!force && sameStamp(session,stamp)) { loadSessionState(session); retries.remove(session); return true; }
            // A full file snapshot supersedes queued older checkpoint values, including batches left after overflow.
            // Drop them before reading: writes offered during the snapshot remain queued and cannot be lost here.
            offers.removeIf(offer -> offer.session.equals(session));
            Set<String> unindexed=unindexed(folder); long[] skipped={0};
            transaction(() -> {
                storage.clearSession(session,false); replacementHook.afterDelete(session);
                Locator source=new Locator(session,"session",-1,"session",-1);
                insert(Projections.project(new Projections.Context(source,metadata.ended,session.equals(store.currentId()),metadata.version),metadata));
                for (String module:Projections.modules()) {
                    if (module.equals("session") || !appliedChat && (module.equals("chat")||module.equals("chat-stars"))) continue;
                    Path journal=folder.resolve(module+".jsonl");
                    if (Files.isRegularFile(journal,LinkOption.NOFOLLOW_LINKS)) JournalLines.read(journal,(offset,text) -> {
                        if (text.isBlank()) return;
                        projectSaved(metadata,new Locator(session,module,offset,null,-1),text,skipped);
                    });
                    Path checkpoints=folder.resolve(module);
                    if (Files.isDirectory(checkpoints,LinkOption.NOFOLLOW_LINKS)) try (DirectoryStream<Path> files=Files.newDirectoryStream(checkpoints,"*.json")) {
                        for (Path checkpoint:files) {
                            if (!Files.isRegularFile(checkpoint,LinkOption.NOFOLLOW_LINKS)) continue;
                            String name=checkpoint.getFileName().toString();
                            // SessionStore checkpoints and imported checkpoint filenames are UUID keys.
                            String key=name.substring(0,name.length()-5);
                            if (!validSession(key)) { skipped[0]++; continue; }
                            try { projectSaved(metadata,new Locator(session,module,-1,key,-1),Files.readString(checkpoint,StandardCharsets.UTF_8),skipped); }
                            catch (IOException unreadable) { skipped[0]++; }
                        }
                    }
                }
                if (!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Session removed");
                // Open files are prefix snapshots; later producer offers remain queued as exact source upserts.
                if (metadata.ended>0 && !session.equals(store.currentId()) && !stamp.equals(stamp(folder)))
                    throw new IOException("Session changed during indexing");
                execute("UPDATE sessions SET stamp=?,index_state='READY',skipped=?,reason='',unindexed=? WHERE id=?",
                        stamp,skipped[0],String.join(",",unindexed),session);
                refreshPlayers();
            });
            sessions.put(session,new SessionState(Readiness.READY,skipped[0],"",unindexed)); retries.remove(session);
            replacements.incrementAndGet(); return true;
        } catch (Exception failure) {
            if (failure instanceof SQLException sql && corruption(sql)) throw failure;
            if (busy(failure)) { retrySession(session,failure); return false; }
            if (!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)) {
                transaction(() -> { deleteSession(session); refreshPlayers(); }); sessions.remove(session); retries.remove(session); return true;
            }
            retrySession(session,failure);
            try { execute("UPDATE sessions SET index_state='FAILED',reason=? WHERE id=?",sessions.get(session).reason,session); }
            catch (SQLException writeFailure) { if (!busy(writeFailure)) throw writeFailure; }
            return false;
        } finally {
            if (claimed) try { execute("DELETE FROM claims WHERE session=? AND instance=?",session,instance); }
            catch (SQLException releaseFailure) { if (!busy(releaseFailure)) throw releaseFailure; } // lease can expire if another writer wins
        }
    }
    private void projectSaved(SessionStore.Session session,Locator locator,String text,long[] skipped) throws SQLException {
        long now=System.currentTimeMillis();
        if (now-lastHeartbeat>=10000) {
            execute("UPDATE claims SET heartbeat=? WHERE session=? AND instance=?",now,session.id,instance); lastHeartbeat=now;
        }
        List<Projections.Row> rows;
        try {
            JsonElement json=SessionStore.JSON.fromJson(text,JsonElement.class);
            rows=Projections.project(new Projections.Context(locator,session.ended,session.id.equals(store.currentId()),session.version),json);
        } catch (RuntimeException malformed) { skipped[0]++; return; }
        insert(rows); // database failures abort the whole replacement; they are never counted as malformed records
    }
    private SessionStore.Session metadata(Path folder,String id) throws IOException {
        Path source=folder.resolve("session.json");
        if (!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || Files.size(source)>1024*1024) throw new IOException("Invalid session metadata");
        SessionStore.Session session=SessionStore.JSON.fromJson(Files.readString(source,StandardCharsets.UTF_8),SessionStore.Session.class);
        if (session==null || session.schemaVersion!=1 || !id.equals(session.id) || session.label==null || session.version==null)
            throw new IOException("Invalid session metadata");
        return session;
    }
    private String stamp(Path folder) throws Exception {
        List<String> modules=new ArrayList<>();
        try (DirectoryStream<Path> entries=Files.newDirectoryStream(folder)) {
            for (Path entry:entries) if (Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)) modules.add(entry.getFileName().toString());
        }
        List<SessionStamps.Stamp> stamps=SessionStamps.stamp(folder,modules.toArray(String[]::new));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SessionStore.JSON.toJson(stamps).getBytes(StandardCharsets.UTF_8)));
    }
    private Set<String> unindexed(Path folder) throws IOException {
        Set<String> modules=new TreeSet<>();
        try (DirectoryStream<Path> entries=Files.newDirectoryStream(folder)) {
            for (Path entry:entries) {
                String name=entry.getFileName().toString();
                if (name.endsWith(".jsonl")) name=name.substring(0,name.length()-6);
                else if (!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Projections.modules().contains(name)) modules.add(Projections.clean(name));
            }
        }
        return Set.copyOf(modules);
    }
    private boolean sameStamp(String session,String stamp) throws SQLException {
        try (PreparedStatement p=db.prepareStatement("SELECT stamp,index_state FROM sessions WHERE id=?")) {
            p.setString(1,session); try (ResultSet r=p.executeQuery()) { return r.next()&&stamp.equals(r.getString(1))&&"READY".equals(r.getString(2)); }
        }
    }
    private boolean exists(String session) throws SQLException {
        if (storage!=null) return storage.hasSession(session);
        try (PreparedStatement p=db.prepareStatement("SELECT 1 FROM sessions WHERE id=?")) {
            p.setString(1,session); try (ResultSet r=p.executeQuery()) { return r.next(); }
        }
    }
    private void loadSessionState(String session) throws SQLException {
        try (PreparedStatement p=db.prepareStatement("SELECT skipped,reason,unindexed FROM sessions WHERE id=?")) {
            p.setString(1,session); try (ResultSet r=p.executeQuery()) {
                if (r.next()) sessions.put(session,new SessionState(Readiness.READY,r.getLong(1),r.getString(2),
                        r.getString(3)==null||r.getString(3).isEmpty()?Set.of():Set.copyOf(Arrays.asList(r.getString(3).split(",")))));
            }
        }
    }
    private boolean claim(String session) throws SQLException {
        long now=System.currentTimeMillis();
        return execute("INSERT INTO claims(session,instance,heartbeat) VALUES(?,?,?) ON CONFLICT(session) DO UPDATE SET instance=excluded.instance,heartbeat=excluded.heartbeat WHERE claims.instance=excluded.instance OR claims.heartbeat<?",
                session,instance,now,now-60000)>0;
    }
    private void drainOffers() throws Exception {
        List<Offer> batch=new ArrayList<>(BATCH); offers.drainTo(batch,BATCH);
        // A failed batch is recovered from the authoritative files after its backoff, even if producers keep offering.
        batch.removeIf(offer -> removed.contains(offer.session) || retries.containsKey(offer.session));
        if (batch.isEmpty()) return;
        Set<String> changed=new HashSet<>();
        Map<String,SessionStore.Session> metadata=new HashMap<>();
        for (String id:batch.stream().map(Offer::session).collect(java.util.stream.Collectors.toSet())) {
            try { metadata.put(id,metadata(store.directory().resolve(id),id)); }
            catch (IOException | RuntimeException failure) { retrySession(id,failure); }
        }
        batch.removeIf(offer -> !metadata.containsKey(offer.session));
        Map<String,Long> skipped=new HashMap<>();
        try {
            transaction(() -> {
                for (Offer offer:batch) {
                    if (removed.contains(offer.session) || !appliedChat&&(offer.module.equals("chat")||offer.module.equals("chat-stars"))) continue;
                    changed.add(offer.session);
                    SessionStore.Session session=metadata.get(offer.session);
                    Locator locator=Locator.offered(offer.session,offer.module,offer.offset,offer.key);
                    if (!exists(offer.session)) insert(Projections.project(new Projections.Context(new Locator(offer.session,"session",-1,"session",-1),session.ended,true,session.version),session));
                    deleteSource(locator);
                    List<Projections.Row> rows;
                    try {
                        rows=Projections.project(new Projections.Context(locator,session.ended,offer.session.equals(store.currentId()),session.version),offer.value);
                    } catch (RuntimeException malformed) {
                        skipped.merge(offer.session,1L,Long::sum);
                        execute("UPDATE sessions SET skipped=skipped+1,stamp=NULL WHERE id=?",offer.session);
                        continue;
                    }
                    insert(rows);
                    execute("UPDATE sessions SET stamp=NULL,index_state='STALE' WHERE id=?",offer.session);
                }
                refreshPlayers();
            });
            for (Map.Entry<String,Long> entry:skipped.entrySet()) sessions.computeIfPresent(entry.getKey(),(id,known) ->
                    new SessionState(known.readiness,known.skipped+entry.getValue(),known.reason,known.unindexed));
            // A complete baseline remains usable after exact source upserts. New live sessions need one full baseline.
            for (String session:changed) if (!sessions.containsKey(session)) markSessionChanged(session);
        } catch (Exception failure) {
            if (failure instanceof SQLException sql && corruption(sql)) throw failure;
            for (String session:batch.stream().map(Offer::session).collect(java.util.stream.Collectors.toSet())) retrySession(session,failure);
        }
    }
    private void insert(List<Projections.Row> rows) throws SQLException { for (Projections.Row row:rows) insert(row); }
    private void insert(Projections.Row row) throws SQLException {
        storage.insert(row);
    }
    private void refreshPlayers() throws SQLException { storage.refreshPlayers(); }
    private void deleteSession(String session) throws SQLException { storage.clearSession(session,true); }
    private void deleteSource(Locator l) throws SQLException { storage.deleteSource(l); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void transaction(Work action) throws Exception {
        db.setAutoCommit(false);
        storage=new IndexStorage(db);
        try { action.run(); storage.flushDocuments(); db.commit(); }
        catch (Exception | LinkageError failure) { db.rollback(); throw failure; }
        finally { storage.close(); storage=null; db.setAutoCommit(true); }
    }
    private int execute(String sql,Object... values) throws SQLException {
        if (storage!=null) return storage.execute(sql,values);
        try (PreparedStatement p=db.prepareStatement(sql)) { bind(p,Arrays.asList(values)); return p.executeUpdate(); }
    }
    static void bind(PreparedStatement p,List<?> values) throws SQLException { for (int i=0;i<values.size();i++) p.setObject(i+1,values.get(i)); }
    static String marks(int count) { return String.join(",",Collections.nCopies(count,"?")); }
    private static boolean validSession(String session) { return session!=null && session.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"); }

    /** Queries throw SQLException to background clients, which can then use their existing file fallback. */
    public Connection readConnection() throws SQLException {
        if (!readable || state.phase==Phase.UNAVAILABLE || !Files.isRegularFile(file)) throw new SQLException("Search index is unavailable");
        Connection read=DriverManager.getConnection("jdbc:sqlite:"+file.toUri()+"?mode=ro");
        try { IndexSchema.configure(read); IndexSchema.exec(read,"PRAGMA query_only=ON"); return read; }
        catch (SQLException failure) { read.close(); throw failure; }
    }
    public List<Hit> search(String text,Set<Kind> kinds,int limit) throws SQLException {
        if (text==null || text.isBlank() || limit<=0) return List.of();
        Set<Kind> selected=kinds==null||kinds.isEmpty()?EnumSet.of(Kind.RUN,Kind.PLAYER):EnumSet.copyOf(kinds);
        int bounded=Math.min(limit,10000); Map<Long,Match> matches=new HashMap<>(); List<Hit> hits=new ArrayList<>();
        try (Connection read=readConnection()) {
            read.setAutoCommit(false); // both FTS paths observe the same WAL snapshot
            String query=text.trim();
            try { searchInto(read,"docs",query,selected,bounded,matches); }
            catch (SQLException malformed) {
                if (!ftsSyntax(malformed)) throw malformed;
                searchInto(read,"docs",quote(query),selected,bounded,matches);
            }
            if (query.codePointCount(0,query.length())>=3 && !query.contains("*") && !query.contains("\""))
                searchInto(read,"names",quote(query),selected,bounded,matches);
            Comparator<Match> order=Comparator.comparingDouble(Match::score)
                    .thenComparingInt(m -> priority(IndexSchema.kind(IndexSchema.table((int)(m.doc>>>48)))))
                    .thenComparing(Match::time,Comparator.nullsLast(Comparator.reverseOrder())).thenComparingLong(Match::doc);
            // Hydrate only the requested page, not every per-type candidate, and keep the same WAL read snapshot.
            for (Match match:matches.values().stream().sorted(order).limit(bounded).toList()) {
                int code=(int)(match.doc>>>48); String table=IndexSchema.table(code);
                Map<String,Object> row=IndexStorage.source(read,code,match.doc & IndexSchema.ROW_MASK);
                if (row.isEmpty()) continue;
                Locator l=IndexStorage.locator(row); String title=IndexStorage.title(table,row);
                hits.add(new Hit(IndexSchema.kind(table),l,title,snippet(IndexStorage.body(table,row),query),l.session(),
                        (String)row.get("visit_id"),longValue(row.get("time")),match.score));
            }
        }
        return List.copyOf(hits);
    }
    private static boolean ftsSyntax(SQLException failure) {
        String message=Objects.toString(failure.getMessage(),"");
        return message.contains("fts5: syntax")||message.contains("unterminated string")||message.contains("no such column");
    }
    private static String quote(String query) { return "\""+query.replace("\"","\"\"")+"\""; }
    private static int priority(Kind kind) { return kind==Kind.RUN?0:kind==Kind.PLAYER?1:2; }
    private record Match(long doc,double score,Long time) {}
    private void searchInto(Connection read,String fts,String query,Set<Kind> kinds,int limit,Map<Long,Match> matches) throws SQLException {
        for (String table:IndexSchema.TABLES.keySet()) {
            Kind kind=IndexSchema.kind(table); if (kind==null || !kinds.contains(kind)) continue;
            long base=(long)IndexSchema.code(table)<<48;
            String pk=table.equals("sessions")?"sid":table.equals("players")?"pid":"id";
            String time=table.equals("sessions")?"t.started":table.equals("players")
                    ? "(SELECT time FROM player_occurrences o WHERE o.pid=t.pid ORDER BY time DESC,id LIMIT 1)":"t.time";
            String sql="SELECT "+fts+".rowid AS doc,bm25("+fts+") AS score,"+time+" AS matched_time FROM "+fts+" JOIN "+table+" t ON t."+pk+"=("+fts+".rowid & "+IndexSchema.ROW_MASK+")"
                    +" WHERE "+fts+" MATCH ? AND "+fts+".rowid>? AND "+fts+".rowid<=? ORDER BY score,matched_time DESC,t."+pk+" LIMIT ?";
            try (PreparedStatement p=read.prepareStatement(sql)) {
                bind(p,List.of(query,base,base+IndexSchema.ROW_MASK,limit));
                try (ResultSet r=p.executeQuery()) {
                    while (r.next()) {
                        long captured=r.getLong(3); Long at=r.wasNull()?null:captured;
                        Match match=new Match(r.getLong(1),r.getDouble(2),at);
                        matches.merge(match.doc,match,(a,b)->a.score<=b.score?a:b);
                    }
                }
            }
        }
    }
    /** Contentless FTS stores tokens only. A bounded excerpt comes from the source row, never a second stored document. */
    private static String snippet(String body,String query) {
        String term=query.replace("\"","").replace("*","").trim();
        java.util.regex.Matcher match=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(term),
                java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(body);
        int found=-1, matchedEnd=-1;
        if (!term.isEmpty() && match.find()) { found=match.start(); matchedEnd=match.end(); }
        int start=Math.max(0,found-50), end=Math.min(body.length(),start+240);
        if (start>0 && Character.isLowSurrogate(body.charAt(start))) start--;
        if (end>0 && end<body.length() && Character.isLowSurrogate(body.charAt(end))) end--;
        String excerpt=body.substring(start,end);
        if (found>=start && matchedEnd<=end) {
            int at=found-start, through=matchedEnd-start; excerpt=excerpt.substring(0,at)+"["+excerpt.substring(at,through)+"]"+excerpt.substring(through);
        }
        return (start>0?"… ":"")+excerpt+(end<body.length()?" …":"");
    }
    public List<Map<String,Object>> runsPage(RunFilter filter,RunSort sort,int offset,int limit) throws SQLException {
        RunFilter f=filter==null?RunFilter.all():filter; StringBuilder sql=new StringBuilder(IndexStorage.select("runs")+" WHERE 1=1"); List<Object> args=new ArrayList<>();
        condition(sql,args,"t.sid = (SELECT sid FROM sessions WHERE id =",f.session); if (f.session!=null) sql.append(")");
        condition(sql,args,"t.map =",f.map);
        condition(sql,args,"t.started >=",f.since); condition(sql,args,"t.started <=",f.until);
        condition(sql,args,"t.duration >=",f.minimumDuration); condition(sql,args,"t.duration <=",f.maximumDuration);
        if (!f.outcomes.isEmpty()) { sql.append(" AND t.outcome IN (").append(marks(f.outcomes.size())).append(")"); args.addAll(f.outcomes); }
        if (f.issues!=null) sql.append(f.issues?" AND t.issues>0":" AND t.issues=0");
        String order=switch(sort==null?RunSort.NEWEST:sort) {
            case NEWEST -> "t.started DESC"; case OLDEST -> "t.started ASC"; case MAP -> "t.map COLLATE NOCASE ASC";
            case DURATION -> "t.duration DESC"; case DAMAGE -> "t.damage DESC"; case PLAYERS -> "t.players DESC";
            case ISSUES -> "t.issues DESC"; case OUTCOME -> "t.outcome ASC";
        };
        sql.append(" ORDER BY ").append(order).append(" NULLS LAST,t.id LIMIT ? OFFSET ?"); args.add(Math.max(0,Math.min(limit,10000))); args.add(Math.max(0,offset));
        try (Connection read=readConnection()) { return rows(read,sql.toString(),args).stream().map(IndexStorage::external).toList(); }
    }
    private static void condition(StringBuilder sql,List<Object> args,String expression,Object value) {
        if (value!=null) { sql.append(" AND ").append(expression).append(" ?"); args.add(value); }
    }
    public Facts visitFacts(String session,String visitId) throws SQLException {
        try (Connection read=readConnection()) {
            read.setAutoCommit(false);
            List<Map<String,Object>> visits=rows(read,"SELECT v.sid,v.vid FROM visits v JOIN sessions s ON s.sid=v.sid WHERE s.id=? AND v.visit_id=?",Arrays.asList(session,visitId));
            if (visits.isEmpty()) return new Facts(List.of(),List.of(),List.of(),List.of(),List.of());
            List<Object> args=List.of(visits.get(0).get("sid"),visits.get(0).get("vid")); List<List<Map<String,Object>>> result=new ArrayList<>();
            for (String table:List.of("timeline","loot_bags","loot_items","fame","combat"))
                result.add(rows(read,IndexStorage.select(table)+" WHERE t.sid=? AND t.vid=? ORDER BY t.time,t.id",args)
                        .stream().map(IndexStorage::external).toList());
            return new Facts(result.get(0),result.get(1),result.get(2),result.get(3),result.get(4));
        }
    }
    public Map<String,Long> rowCounts() throws SQLException {
        Map<String,Long> counts=new LinkedHashMap<>();
        try (Connection read=readConnection(); Statement s=read.createStatement()) {
            for (String table:IndexSchema.TABLES.keySet()) try (ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)) { r.next(); counts.put(table,r.getLong(1)); }
        }
        return Collections.unmodifiableMap(counts);
    }
    static List<Map<String,Object>> rows(Connection read,String sql,List<Object> parameters) throws SQLException {
        List<Map<String,Object>> rows=new ArrayList<>();
        try (PreparedStatement p=read.prepareStatement(sql)) {
            bind(p,parameters); try (ResultSet r=p.executeQuery()) {
                ResultSetMetaData meta=r.getMetaData();
                while (r.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for (int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),r.getObject(i));
                    rows.add(Collections.unmodifiableMap(row));
                }
            }
        }
        return List.copyOf(rows);
    }
    private static Long longValue(Object value) { return value==null?null:((Number)value).longValue(); }
    public CompletableFuture<Void> closeAsync() { close(); return closed; }
    @Override public void close() {
        if (!closing.compareAndSet(false,true)) return;
        writer.execute(() -> {
            try {
                if (db!=null) { execute("DELETE FROM claims WHERE instance=?",instance); IndexSchema.exec(db,"PRAGMA wal_checkpoint(TRUNCATE)"); }
            } catch (SQLException ignored) { }
            finally { closeConnection(); closed.complete(null); }
        }); writer.shutdown();
    }
    private void closeConnection() {
        readable=false;
        if (db!=null) { try { db.close(); } catch (SQLException ignored) { } db=null; }
    }
}
