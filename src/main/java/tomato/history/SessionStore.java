package tomato.history;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Stream;

/** Per-launch append journals and atomic run checkpoints. Producers never wait for disk. */
public final class SessionStore implements AutoCloseable {
    public static final String ALL = "*";
    public static final Gson JSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>)(v,t,c) -> new JsonPrimitive(v.toString()))
        .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>)(v,t,c) -> Instant.parse(v.getAsString()))
        .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>)(v,t,c) -> new JsonPrimitive(v.toString()))
        .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>)(v,t,c) -> LocalDateTime.parse(v.getAsString()))
        .create();
    private final Path root;
    private final boolean writable;
    private final Session current;
    private final ScheduledExecutorService worker;
    private final Object pendingLock = new Object();
    private final ArrayDeque<Write> events = new ArrayDeque<>();
    private final Map<String, Write> checkpoints = new LinkedHashMap<>();
    private final Map<String, Runnable> collectors = new ConcurrentHashMap<>();
    private volatile String error = "";
    private volatile String importError = "";
    private volatile boolean closing;
    private volatile Thread ioThread;
    private FileChannel lockChannel;
    private FileLock fileLock;

    public SessionStore(Path root, boolean writable, String version) {
        this.root = root.toAbsolutePath().normalize(); this.writable = writable;
        current = new Session(UUID.randomUUID().toString(), System.currentTimeMillis(), "", version);
        worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "RealmShark session history"); t.setDaemon(true); ioThread=t;return t; });
        if (writable) {
            worker.scheduleWithFixedDelay(this::drain, 0, 250, TimeUnit.MILLISECONDS);
            worker.scheduleWithFixedDelay(() -> { collect(); drain(); }, 2, 2, TimeUnit.SECONDS);
        }
    }
    public Path directory() { return root; }
    public String currentId() { return current.id; }
    public long started() { return current.started; }
    public boolean writable() { return writable; }
    public String error() { return error.isEmpty() ? importError : error; }
    public void importError(String message) { importError=message; }
    public void collect(String key, Runnable collector) { collectors.put(key, collector); }
    private void collect() {
        for (Runnable collector : collectors.values()) try { collector.run(); }
        catch (RuntimeException e) { error = "A history snapshot could not be collected: " + e.getClass().getSimpleName(); }
    }
    public void append(String module, Object detached) { offer(new Write(current.id, module, null, detached)); }
    public void put(String module, String key, Object detached) { offer(new Write(current.id, module, key, detached)); }
    private void offer(Write write) {
        if (!writable || (closing && Thread.currentThread()!=ioThread)) return;
        checkModule(write.module);
        synchronized (pendingLock) {
            if (write.key == null) events.addLast(write);
            else checkpoints.put(write.session + "/" + write.module + "/" + write.key, write);
        }
    }
    private void ensureCurrent() throws IOException {
        if (fileLock == null) {
            Files.createDirectories(sessionPath(current.id));
            lockChannel = FileChannel.open(sessionPath(current.id).resolve(".active"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) throw new IOException("Session is already open");
        }
        if (!Files.exists(sessionPath(current.id).resolve("session.json"))) atomic(sessionPath(current.id).resolve("session.json"), JSON.toJson(current));
    }
    private void drain() {
        if (!writable) return;
        try {
            ensureCurrent();
            int budget = 2000;
            while (budget-- > 0) {
                Write write;
                synchronized (pendingLock) {
                    write = events.peekFirst();
                    if (write == null && !checkpoints.isEmpty()) write = checkpoints.values().iterator().next();
                }
                if (write == null) break;
                persist(write);
                synchronized (pendingLock) {
                    if (write.key == null) events.removeFirst();
                    else checkpoints.remove(write.session + "/" + write.module + "/" + write.key, write);
                }
            }
            error = "";
        } catch (Exception e) { error = "History could not be saved; pending data will retry. Check " + root; }
    }
    private void persist(Write write) throws IOException {
        Path session = sessionPath(write.session);
        String json = JSON.toJson(write.value);
        if (write.key == null) {
            Path file = session.resolve(write.module + ".jsonl");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                long before = channel.size(); channel.position(before);
                try {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(json + "\n");
                    while (bytes.hasRemaining()) channel.write(bytes);
                } catch (IOException failure) { channel.truncate(before); throw failure; }
            }
        } else {
            Path folder = session.resolve(write.module); Files.createDirectories(folder);
            String name = UUID.nameUUIDFromBytes(write.key.getBytes(StandardCharsets.UTF_8)).toString();
            atomic(folder.resolve(name + ".json"), json);
        }
    }
    private static void atomic(Path target, String value) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".history-", ".tmp");
        try {
            Files.write(temp, value.getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    /** Called by readers on a worker, never on Swing's event thread. */
    public List<Session> sessions() throws IOException {
        List<Session> result = new ArrayList<>();
        for (SessionEntry entry : catalog()) {
            if(!entry.readable())throw new IOException("Unreadable session "+entry.id+": "+entry.error);
            result.add(entry.session());
        }
        return result;
    }
    /** Metadata failures belong to one entry, not the entire library. No payloads are loaded. */
    public List<SessionEntry> catalog() throws IOException {
        return catalog(new tomato.history.archive.Cancellation());
    }
    public List<SessionEntry> catalog(tomato.history.archive.Cancellation cancel) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        if (Files.exists(root) && !Files.isDirectory(root)) throw new IOException("History location is not a directory");
        List<SessionEntry> result = new ArrayList<>();
        if (Files.isDirectory(root)) try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
                cancel.check();
                if (!validId(folder.getFileName().toString()) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = folder.getFileName().toString();
                try {
                    Path meta = folder.resolve("session.json");
                    if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.size(meta) > 1024 * 1024)
                        throw new IOException("Missing or oversized metadata");
                    Session session = JSON.fromJson(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8), Session.class);
                    if (session == null || session.schemaVersion!=1 || session.label==null || session.version==null
                            || !id.equals(session.id)) throw new IOException("Invalid metadata");
                    Set<String> modules = new TreeSet<>();
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                        for (Path file : files) {
                            String name = file.getFileName().toString();
                            if (name.endsWith(".jsonl") && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) modules.add(name.substring(0, name.length()-6));
                            else if (name.matches("[a-z][a-z-]*") && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) modules.add(name);
                        }
                    }
                    result.add(new SessionEntry(id, session, modules, "", true));
                } catch (IOException | RuntimeException failure) {
                    result.add(new SessionEntry(id, null, Collections.emptySet(),
                            "Session metadata could not be read (" + failure.getClass().getSimpleName() + ").", true));
                }
            }
        }
        if (result.stream().noneMatch(s -> s.id.equals(current.id)))
            result.add(new SessionEntry(current.id, current, Collections.emptySet(), "", false));
        result.sort(Comparator.comparingLong((SessionEntry s) -> s.metadata == null ? Long.MIN_VALUE : s.metadata.started)
                .reversed().thenComparing(s -> s.id));
        return Collections.unmodifiableList(result);
    }
    public tomato.history.archive.ReadSnapshot capture(List<tomato.history.archive.ReadSnapshot.Source> sources,
            Path scratch, tomato.history.archive.Cancellation cancel) throws IOException {
        return tomato.history.archive.ReadSnapshot.capture(this, sources, scratch, cancel);
    }
    public <T> void read(String scope, String module, Class<T> type, BiConsumer<Session,T> consumer) throws IOException {
        checkModule(module);
        List<Session> selected=new ArrayList<>();
        for(SessionEntry entry:catalog()) {
            if(!ALL.equals(scope)&&!entry.id.equals(scope))continue;
            if(!entry.readable())throw new IOException("Unreadable session "+entry.id+": "+entry.error);
            selected.add(entry.session());
        }
        for (Session session : selected) {
            Path directory = sessionPath(session.id), file = directory.resolve(module + ".jsonl");
            if (Files.isRegularFile(file)) try (BufferedReader reader = journalReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    T value;
                    try { value = JSON.fromJson(line, type); }
                    catch (JsonParseException e) {
                        if (reader.readLine() == null) break; // A crash may leave one unfinished final record.
                        throw new IOException("Unreadable history: " + file, e);
                    }
                    if (value != null) consumer.accept(session, finishSavedVisit(session,value));
                }
            }
            Path snapshots = directory.resolve(module);
            if (Files.isDirectory(snapshots)) try (DirectoryStream<Path> files = Files.newDirectoryStream(snapshots, "*.json")) {
                List<Path> ordered=new ArrayList<>();for(Path path:files)ordered.add(path);
                if("runs".equals(module)){
                    Map<Path,Long> starts=new HashMap<>();
                    for(Path path:ordered)try(com.google.gson.stream.JsonReader reader=new com.google.gson.stream.JsonReader(Files.newBufferedReader(path,StandardCharsets.UTF_8))){
                        reader.beginObject();long start=0;
                        while(reader.hasNext()){if("started".equals(reader.nextName())){start=reader.nextLong();break;}reader.skipValue();}
                        starts.put(path,start);
                    }
                    ordered.sort(Comparator.comparingLong((Path p)->starts.get(p)).reversed().thenComparing(Path::toString));
                }else ordered.sort(Comparator.comparing(Path::toString));
                for (Path path : ordered) {
                    T value = JSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), type);
                    if (value != null) consumer.accept(session, finishSavedVisit(session,value));
                }
            }
        }
    }
    public <T> List<T> read(String scope, String module, Class<T> type) throws IOException {
        List<T> result = new ArrayList<>(); read(scope, module, type, (s,v) -> result.add(v)); return result;
    }
    private static <T> T finishSavedVisit(Session session,T value) {
        if(value instanceof packets.packetcapture.logger.ActivityJournal.Visit){
            packets.packetcapture.logger.ActivityJournal.Visit visit=(packets.packetcapture.logger.ActivityJournal.Visit)value;
            if(visit.ended==0&&session.ended>0){visit.ended=visit.lastSeen;visit.endReason="App ended";}
        }
        return value;
    }
    /** Import under a deterministic ID so repeated builds cannot duplicate imported records. */
    public void importSnapshot(String source, String label, long started, String module, String key, Object value) throws IOException {
        if (!writable) return;
        String id = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
        String itemId=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        Path imports=root.resolve("imports").resolve(id);Path marker=imports.resolve(module+"-"+itemId+".json");
        if(Files.exists(marker))return;
        Path path = sessionPath(id); Files.createDirectories(path);
        if (!Files.exists(path.resolve("session.json"))) {
            Session imported = new Session(id, started, label, "Imported"); imported.ended = started;
            atomic(path.resolve("session.json"), JSON.toJson(imported));
        }
        Path item = path.resolve(module).resolve(itemId + ".json");
        if (!Files.exists(item)) persist(new Write(id, module, key, value));
        Files.createDirectories(imports);atomic(marker,"{\"imported\":true}");
    }
    public void delete(String id) throws IOException {
        if (!writable || id.equals(current.id)) throw new IOException("The current session is still recording.");
        Path path = sessionPath(id); if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (FileChannel channel = FileChannel.open(path.resolve(".active"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("This session is open in another RealmShark instance.");
            try (Stream<Path> files = Files.walk(path)) {
                Iterator<Path> iterator = files.sorted(Comparator.reverseOrder()).iterator();
                while (iterator.hasNext()) { Path file = iterator.next(); if (!file.equals(path) && !file.equals(path.resolve(".active"))) Files.delete(file); }
            }
        } catch (OverlappingFileLockException e) { throw new IOException("This session is still open.", e); }
        Files.deleteIfExists(path.resolve(".active")); Files.delete(path);
    }
    public void rename(String id, String label) throws IOException {
        if (!writable || id.equals(current.id)) throw new IOException("The current session is still recording.");
        if (label == null || label.length() > 200) throw new IllegalArgumentException("Session label must contain at most 200 characters");
        Path path=sessionPath(id);
        try (FileChannel channel=FileChannel.open(path.resolve(".active"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IOException("This session is open in another RealmShark instance.");
            Path meta=path.resolve("session.json");
            if(Files.size(meta)>1024*1024)throw new IOException("Oversized session metadata");
            Session session;JsonObject metadata;
            try{metadata=JsonParser.parseString(new String(Files.readAllBytes(meta),StandardCharsets.UTF_8)).getAsJsonObject();session=JSON.fromJson(metadata,Session.class);}
            catch(RuntimeException failure){throw new IOException("Unreadable session metadata",failure);}
            if(session==null||!id.equals(session.id)||session.schemaVersion!=1)throw new IOException("Invalid session metadata");
            metadata.addProperty("label",label);atomic(meta,metadata.toString());
        }catch(OverlappingFileLockException failure){throw new IOException("This session is still open.",failure);}
    }
    public void flush() throws Exception {
        if (!writable) return;
        worker.submit(() -> { collect(); do { drain(); } while (pending() && error.isEmpty()); }).get(10, TimeUnit.SECONDS);
        if (pending()) throw new IOException(error);
    }
    private boolean pending() { synchronized (pendingLock) { return !events.isEmpty() || !checkpoints.isEmpty(); } }
    private Path sessionPath(String id) { if (!validId(id)) throw new IllegalArgumentException("Invalid session ID"); return root.resolve(id); }
    private static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"); }
    private static void checkModule(String module) { if (!module.matches("[a-z][a-z-]*")) throw new IllegalArgumentException("Invalid history module"); }
    @Override public void close() {
        if (closing) return;
        closing = true;
        try {
            flush();
            worker.submit(()->{
                try {
                    if(writable){current.ended=System.currentTimeMillis();atomic(sessionPath(current.id).resolve("session.json"),JSON.toJson(current));}
                    if(fileLock!=null)fileLock.release();if(lockChannel!=null)lockChannel.close();
                }catch(IOException e){throw new UncheckedIOException(e);}
            }).get(5,TimeUnit.SECONDS);
        } catch (Exception e) { error = "History has unsaved data: " + root; }
        finally {
            try { worker.submit(()->{
                try{if(fileLock!=null&&fileLock.isValid())fileLock.release();if(lockChannel!=null&&lockChannel.isOpen())lockChannel.close();}
                catch(IOException ignored){ }
            }).get(3,TimeUnit.SECONDS); }catch(Exception ignored){ }
            worker.shutdown();
        }
    }
    public static final class Session {
        public int schemaVersion = 1;
        public String id, label, version; public long started, ended;
        /** Optional evidence; absent in schema-1 histories written before availability tracking. */
        public Map<String, ModuleAvailability> availability;
        Session(String id, long started, String label, String version) { this.id=id;this.started=started;this.label=label;this.version=version; }
        @Override public String toString() { return (label.isEmpty() ? "Session" : label) + " · " + tomato.gui.modern.DisplayFormat.formatTimestamp(started); }
    }
    public static final class ModuleAvailability {
        public enum State { UNKNOWN, PARTIAL, NOT_CAPTURED }
        public final int schemaVersion;
        public final State state;
        public final String reason;
        public final Long from, until;
        public ModuleAvailability(State state, String reason, Long from, Long until) {
            this.schemaVersion = 1;
            this.state = Objects.requireNonNull(state); this.reason = Objects.requireNonNull(reason);
            this.from = from; this.until = until;
        }
    }
    /** Future producers explicitly declare coverage. Merely writing a record never declares completeness. */
    public CompletionStage<Void> availability(String module, ModuleAvailability evidence) {
        checkModule(module); Objects.requireNonNull(evidence);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!writable || closing) { completion.completeExceptionally(new IOException("History is read-only or closed")); return completion; }
        worker.execute(() -> {
            try {
                ensureCurrent();
                Session next = JSON.fromJson(JSON.toJson(current), Session.class);
                if (next.availability == null) next.availability = new LinkedHashMap<>();
                next.availability.put(module, evidence);
                atomic(sessionPath(current.id).resolve("session.json"), JSON.toJson(next));
                current.availability = next.availability; completion.complete(null);
            } catch (Exception failure) { completion.completeExceptionally(failure); }
        });
        return completion;
    }
    public static final class SessionEntry {
        public final String id, error;
        public final Set<String> modules;
        public final boolean persisted;
        private final Session metadata;
        SessionEntry(String id, Session metadata, Set<String> modules, String error, boolean persisted) {
            this.id = id; this.metadata = metadata == null ? null : JSON.fromJson(JSON.toJson(metadata), Session.class);
            this.modules = Collections.unmodifiableSet(new TreeSet<>(modules)); this.error = error; this.persisted = persisted;
        }
        public boolean readable() { return metadata != null; }
        public Session session() { return metadata == null ? null : JSON.fromJson(JSON.toJson(metadata), Session.class); }
        public ModuleAvailability availability(String module) {
            ModuleAvailability value = metadata == null || metadata.availability == null ? null : metadata.availability.get(module);
            return value != null && value.schemaVersion == 1 && value.state != null && value.reason != null ? value
                : new ModuleAvailability(ModuleAvailability.State.UNKNOWN, "Recording coverage unknown", null, null);
        }
    }
    private static final class Write {
        final String session,module,key; final Object value;
        Write(String session,String module,String key,Object value) { this.session=session;this.module=module;this.key=key;this.value=value; }
    }
    private static BufferedReader journalReader(Path file)throws IOException{
        long length=Files.size(file);
        return new BufferedReader(new InputStreamReader(new JournalPrefix(Files.newInputStream(file),length),StandardCharsets.UTF_8));
    }
    /** A reader sees one journal prefix even while capture continues appending to it. */
    private static final class JournalPrefix extends FilterInputStream {
        private long remaining;
        JournalPrefix(InputStream stream,long length){super(stream);remaining=length;}
        @Override public int read()throws IOException{if(remaining<=0)return -1;int value=super.read();if(value>=0)remaining--;return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            if(length==0)return 0;if(remaining<=0)return -1;int count=super.read(bytes,offset,(int)Math.min(remaining,length));if(count>0)remaining-=count;return count;
        }
    }
}
