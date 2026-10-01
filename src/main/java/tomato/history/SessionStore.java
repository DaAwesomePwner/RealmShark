package tomato.history;

import com.google.gson.*;
import util.AtomicFiles;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Stream;

/** Per-launch append journals and atomic run checkpoints. Producers never wait for disk. */
public final class SessionStore implements AutoCloseable {
    public static final String ALL = "*";
    public static final String SAVE_FAILED = "History could not be saved; pending data will retry. Check ";
    public static final String SNAPSHOT_FAILED = "A history snapshot could not be collected: ";
    public static final String UNSAVED_ON_CLOSE = "History has unsaved data: ";
    public static final String IMPORT_FAILED = "Some existing history could not be imported. Originals are kept; use Import old folder to retry.";
    public static final String RECORDS_SKIPPED = " history records could not be saved and were skipped";
    static final int EVENT_LIMIT = 100000;
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
    private final JournalChannels journalChannels;
    private final AtomicWriter atomicWriter;
    private final Set<Path> touchedJournals = new LinkedHashSet<>();
    private final Map<Path, Long> rollbacks = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong skipped = new java.util.concurrent.atomic.AtomicLong();
    private final Object pendingLock = new Object();
    private final ArrayDeque<Write> events = new ArrayDeque<>();
    private final Map<String, Write> checkpoints = new LinkedHashMap<>();
    private final Map<String, Runnable> collectors = new ConcurrentHashMap<>();
    private volatile String error = "";
    /** The last collection round's failure; kept until a round succeeds, since a successful drain follows each round. */
    private volatile String collectError = "";
    private volatile String importError = "";
    private volatile boolean closing;
    private volatile Thread ioThread;
    private volatile boolean currentMetadataPublished;
    private volatile PersistenceListener persistenceListener;
    private FileChannel lockChannel;
    private FileLock fileLock;

    public SessionStore(Path root, boolean writable, String version) {
        this(root, writable, version, file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
    }
    interface JournalChannels { FileChannel open(Path file) throws IOException; }
    interface AtomicWriter { void write(Path file, byte[] bytes, boolean sync) throws IOException; }
    SessionStore(Path root, boolean writable, String version, JournalChannels journalChannels) {
        this(root, writable, version, journalChannels, AtomicFiles::write);
    }
    SessionStore(Path root, boolean writable, String version, JournalChannels journalChannels, AtomicWriter atomicWriter) {
        this.root = root.toAbsolutePath().normalize(); this.writable = writable;
        this.journalChannels = journalChannels; this.atomicWriter = atomicWriter;
        current = new Session(UUID.randomUUID().toString(), System.currentTimeMillis(), "", version);
        worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "RealmShark session history"); t.setDaemon(true); ioThread=t;return t; });
        if (writable) {
            worker.scheduleWithFixedDelay(() -> { if (!closing) drain(); }, 0, 250, TimeUnit.MILLISECONDS);
            worker.scheduleWithFixedDelay(() -> { if (!closing) { collect(); drain(); } }, 2, 2, TimeUnit.SECONDS);
        }
    }
    public Path directory() { return root; }
    /** Called on the history worker after a successful live write. Implementations must not block. */
    @FunctionalInterface public interface PersistenceListener {
        void persisted(String session, String module, long offsetOrMinusOne, String keyOrNull, Object value);
    }
    public synchronized void setPersistenceListener(PersistenceListener listener) {
        if (persistenceListener != null) throw new IllegalStateException("Persistence listener already installed");
        persistenceListener = Objects.requireNonNull(listener);
    }
    private void persisted(Write write, long offset) {
        PersistenceListener listener = persistenceListener;
        if (listener != null) try { listener.persisted(write.session, write.module, offset, write.key, write.value); }
        catch (Throwable failure) {
            // A derived consumer must not cancel periodic saving; fatal VM failures still propagate.
            if (failure instanceof VirtualMachineError fatal && !(fatal instanceof StackOverflowError)) throw fatal;
        }
    }
    /**
     * The current session's folder for side files the store cannot write itself (binary full combat detail, written
     * atomically by their owner in a module-named subfolder); empty in preview and once closing. The folder may not exist
     * yet: callers create what they write in.
     */
    public Optional<Path> currentDirectory() {
        return writable && !closing ? Optional.of(sessionPath(current.id)) : Optional.empty();
    }
    public String currentId() { return current.id; }
    public long started() { return current.started; }
    public boolean writable() { return writable; }
    public String error() {
        String failure = !error.isEmpty() ? error : !collectError.isEmpty() ? collectError : importError;
        long count = skipped.get();
        return count == 0 ? failure : (failure.isEmpty() ? "" : failure + " · ") + count + RECORDS_SKIPPED;
    }
    boolean closing() { return closing; }
    public void importError(String message) { importError=message; }
    public void collect(String key, Runnable collector) { collectors.put(key, collector); }
    private void collect() {
        String failure = "";
        for (Runnable collector : collectors.values()) try { collector.run(); }
        catch (RuntimeException e) { failure = SNAPSHOT_FAILED + e.getClass().getSimpleName(); }
        collectError = failure;
    }
    public void append(String module, Object detached) { offer(new Write(current.id, module, null, detached)); }
    public void put(String module, String key, Object detached) { offer(new Write(current.id, module, key, detached)); }
    private void offer(Write write) {
        if (!writable || (closing && Thread.currentThread()!=ioThread)) return;
        checkModule(write.module);
        synchronized (pendingLock) {
            if (write.key == null) {
                if (events.size() >= EVENT_LIMIT) skipped.incrementAndGet();
                else events.addLast(write);
            }
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
        if (!Files.exists(sessionPath(current.id).resolve("session.json"))) writeAtomic(sessionPath(current.id).resolve("session.json"), JSON.toJson(current), false);
        currentMetadataPublished = true;
    }
    private void drain() {
        if (!writable) return;
        Set<Write> completed = new HashSet<>();
        List<Write> selected = new ArrayList<>();
        try {
            ensureCurrent();
            int budget = 2000;
            synchronized (pendingLock) {
                for (Write write : events) {
                    if (budget == 0) break;
                    selected.add(write); budget--;
                }
            }
            Map<Path, List<Write>> batches = new LinkedHashMap<>();
            for (Write write : selected)
                batches.computeIfAbsent(sessionPath(write.session).resolve(write.module + ".jsonl"), key -> new ArrayList<>()).add(write);
            for (Map.Entry<Path, List<Write>> batch : batches.entrySet()) appendBatch(batch.getKey(), batch.getValue(), completed);
            while (budget-- > 0) {
                Write write;
                synchronized (pendingLock) {
                    write = checkpoints.isEmpty() ? null : checkpoints.values().iterator().next();
                }
                if (write == null) break;
                String json = serialize(write);
                if (json != null) { persistCheckpoint(write, json); persisted(write, -1); }
                synchronized (pendingLock) {
                    checkpoints.remove(write.session + "/" + write.module + "/" + write.key, write);
                }
            }
            error = "";
        } catch (Exception e) { error = SAVE_FAILED + root; }
        finally {
            synchronized (pendingLock) {
                Iterator<Write> queued = events.iterator();
                for (int i = 0; i < selected.size() && queued.hasNext(); i++)
                    if (completed.contains(queued.next())) queued.remove();
            }
        }
    }
    private String serialize(Write write) {
        try { return JSON.toJson(write.value); }
        catch (RuntimeException | StackOverflowError failure) {
            skipped.incrementAndGet(); return null;
        }
    }
    private void appendBatch(Path file, List<Write> batch, Set<Write> completed) throws IOException {
        List<byte[]> lines = new ArrayList<>();
        List<Write> saved = new ArrayList<>();
        for (Write write : batch) {
            String json = serialize(write);
            if (json == null) completed.add(write);
            else { lines.add(json.getBytes(StandardCharsets.UTF_8)); saved.add(write); }
        }
        if (lines.isEmpty()) return;
        try (FileChannel channel = journalChannels.open(file)) {
            Long rollback = rollbacks.get(file);
            if (rollback != null) { channel.truncate(rollback); rollbacks.remove(file); }
            long before = channel.size(); channel.position(before);
            rollbacks.put(file, before);
            try {
                BufferedOutputStream output = new BufferedOutputStream(Channels.newOutputStream(channel));
                for (byte[] line : lines) { output.write(line); output.write('\n'); }
                output.flush();
            } catch (IOException failure) {
                try { channel.truncate(before); rollbacks.remove(file); }
                catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
            rollbacks.remove(file); completed.addAll(batch);
            touchedJournals.add(file);
            long offset = before;
            for (int i = 0; i < saved.size(); i++) {
                persisted(saved.get(i), offset);
                offset += lines.get(i).length + 1;
            }
        }
    }
    private void persist(Write write) throws IOException { persistCheckpoint(write, JSON.toJson(write.value)); }
    private void persistCheckpoint(Write write, String json) throws IOException {
        Path folder = sessionPath(write.session).resolve(write.module); Files.createDirectories(folder);
        writeAtomic(folder.resolve(checkpointName(write.key) + ".json"), json, closing);
    }
    private void writeAtomic(Path target, String value, boolean sync) throws IOException {
        atomicWriter.write(target, value.getBytes(StandardCharsets.UTF_8), sync);
    }
    private void forceJournals() throws IOException {
        IOException failure = null;
        for (Path file : touchedJournals) try (FileChannel channel = journalChannels.open(file)) {
            channel.force(true);
        } catch (IOException e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
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
                // Startup creates the directory before atomically publishing metadata. Until that
                // publication, retain the same in-memory current entry used before the directory exists.
                // After publication, missing/corrupt metadata must still be reported as an error.
                if (writable && id.equals(current.id) && !currentMetadataPublished) continue;
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
        read(catalog(), scope, module, type, consumer);
    }
    /**
     * As above over a catalog the caller already listed, so a reader of several modules and sessions lists the library
     * once. Off the EDT only.
     */
    public <T> void read(List<SessionEntry> catalog, String scope, String module, Class<T> type, BiConsumer<Session,T> consumer) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        checkModule(module);
        List<Session> selected=new ArrayList<>();
        for(SessionEntry entry:catalog) {
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
                    List<SavedCheckpoint<T>> parsed=new ArrayList<>();
                    for(Path path:ordered){
                        String saved=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
                        // Preserve beginObject's empty/non-object failures without parsing the checkpoint twice.
                        try(com.google.gson.stream.JsonReader reader=new com.google.gson.stream.JsonReader(new StringReader(saved))){
                            reader.beginObject();
                        }
                        JsonObject json=JsonParser.parseString(saved).getAsJsonObject();
                        long start=json.has("started")?json.get("started").getAsLong():0;
                        parsed.add(new SavedCheckpoint<>(path,start,JSON.fromJson(json,type)));
                    }
                    parsed.sort(Comparator.comparingLong((SavedCheckpoint<T> p)->p.started).reversed().thenComparing(p->p.path.toString()));
                    for(SavedCheckpoint<T> saved:parsed)if(saved.value!=null)consumer.accept(session,finishSavedVisit(session,saved.value));
                    continue;
                }
                ordered.sort(Comparator.comparing(Path::toString));
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
    private record SavedCheckpoint<T>(Path path,long started,T value) {}
    private static final int JOURNAL_WINDOW = 4096;

    /** Caller-owned position and identity of a journal prefix; null starts a new read at byte zero. */
    public static final class JournalCursor {
        private final Path file;
        private final long offset;
        private final BasicFileAttributes attributes;
        private final byte[] window;
        private JournalCursor(Path file,long offset,BasicFileAttributes attributes,byte[] window){
            this.file=file;this.offset=offset;this.attributes=attributes;this.window=window;
        }
        public long offset(){return offset;}
        public Object fileKey(){return attributes==null?null:attributes.fileKey();}
    }
    /** The caller must discard its accumulated records and restart from zero. */
    public static final class JournalChangedException extends IOException {
        JournalChangedException(){super("History journal changed; restart from zero");}
    }
    /**
     * Reads only newline-terminated records in the initial byte prefix, starting at the supplied cursor. Off the EDT only.
     * The returned cursor is committed only after a successful read; callers must also discard any emitted records on failure.
     * Shrinkage, a changed file key or changed bytes in the last 4096 consumed bytes invalidate the cursor. Checkpoints are
     * not read by this method. A replacement preserving that window cannot be distinguished from an append without a file key.
     */
    public <T> JournalCursor readJournalFrom(Session session,String module,JournalCursor cursor,Class<T> type,
            Consumer<T> consumer,tomato.history.archive.Cancellation cancel)throws IOException{
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Read history off the EDT");
        checkModule(module);cancel.check();
        Path file=sessionPath(session.id).resolve(module+".jsonl");
        if(cursor!=null&&!cursor.file.equals(file))throw new JournalChangedException();
        BasicFileAttributes before;
        try{before=Files.readAttributes(file,BasicFileAttributes.class);}
        catch(NoSuchFileException absent){
            if(cursor!=null&&cursor.attributes!=null)throw new JournalChangedException();
            return new JournalCursor(file,0,null,new byte[0]);
        }
        if(!before.isRegularFile())throw new IOException("History journal is not a regular file");
        if(cursor!=null&&cursor.attributes!=null)checkJournalIdentity(cursor.attributes,before);
        long offset=cursor==null?0:cursor.offset,consumed=offset;
        if(before.size()<offset)throw new JournalChangedException();
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        byte[] window;
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)){
            if(cursor!=null)checkJournalWindow(channel,cursor);
            channel.position(offset);
            try(InputStream input=new BufferedInputStream(new JournalPrefix(Channels.newInputStream(channel),before.size()-offset))){
                for(int value;(value=input.read())>=0;){
                    if((offset++&4095)==0)cancel.check();
                    if(value=='\n'){
                        T parsed;
                        try{parsed=JSON.fromJson(line.toString(StandardCharsets.UTF_8),type);}
                        catch(JsonParseException failure){throw new IOException("Unreadable history: "+file,failure);}
                        if(parsed!=null)consumer.accept(finishSavedVisit(session,parsed));
                        consumed=offset;line.reset();
                    }else line.write(value);
                }
                if(cursor!=null)checkJournalWindow(channel,cursor);
                window=journalWindow(channel,consumed);
            }
        }
        if(offset<before.size())throw new JournalChangedException();
        BasicFileAttributes after;
        try{after=Files.readAttributes(file,BasicFileAttributes.class);}
        catch(NoSuchFileException absent){throw new JournalChangedException();}
        checkJournalIdentity(before,after);cancel.check();
        return new JournalCursor(file,consumed,before,window);
    }
    private static void checkJournalIdentity(BasicFileAttributes before,BasicFileAttributes after)throws JournalChangedException{
        if(!after.isRegularFile()||after.size()<before.size()
                ||before.fileKey()!=null&&!Objects.equals(before.fileKey(),after.fileKey()))throw new JournalChangedException();
    }
    private static void checkJournalWindow(FileChannel channel,JournalCursor cursor)throws IOException{
        if(!Arrays.equals(cursor.window,journalWindow(channel,cursor.offset)))throw new JournalChangedException();
    }
    private static byte[] journalWindow(FileChannel channel,long offset)throws IOException{
        byte[] bytes=new byte[(int)Math.min(JOURNAL_WINDOW,offset)];
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);
        long start=offset-bytes.length;
        while(buffer.hasRemaining())
            if(channel.read(buffer,start+buffer.position())<0)throw new JournalChangedException();
        return bytes;
    }
    /**
     * One checkpoint of a session exactly as {@link #put} wrote it ({@code <session>/<module>/<uuid(key)>.json}), or empty
     * when that file does not exist. Off the EDT only. A damaged file is an IOException, never an empty result. Unlike
     * {@link #read} it applies no fix-up: a visit left open by a session that ended is returned as saved.
     */
    public <T> Optional<T> readCheckpoint(String session, String module, String key, Class<T> type) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        checkModule(module);
        Path file = sessionPath(session).resolve(module).resolve(checkpointName(key) + ".json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try { return Optional.ofNullable(JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), type)); }
        catch (JsonParseException e) { throw new IOException("Unreadable history: " + file, e); }
    }
    /** The file name (without extension) {@link #put} gives a key; side files keyed the same way use it too. */
    public static String checkpointName(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(); }
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
            writeAtomic(path.resolve("session.json"), JSON.toJson(imported), false);
        }
        Path item = path.resolve(module).resolve(itemId + ".json");
        if (!Files.exists(item)) persist(new Write(id, module, key, value));
        Files.createDirectories(imports);writeAtomic(marker,"{\"imported\":true}",false);
    }
    public void delete(String id) throws IOException {
        if (!writable || id.equals(current.id)) throw new IOException("The current session is still recording.");
        Path path = sessionPath(id); if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
        whileClosed(id, () -> {
            try (Stream<Path> files = Files.walk(path)) {
                Iterator<Path> iterator = files.sorted(Comparator.reverseOrder()).iterator();
                while (iterator.hasNext()) { Path file = iterator.next(); if (!file.equals(path) && !file.equals(path.resolve(".active"))) Files.delete(file); }
            }
            return null;
        });
        Files.deleteIfExists(path.resolve(".active")); Files.delete(path);
    }
    /**
     * Deletes the regular files directly in {@code <session>/<module>/} that {@code match} accepts (a checkpoint module or a
     * side-file folder such as full combat detail) and returns how many were deleted; a file that cannot be deleted is left
     * and not counted. Closed sessions only, as {@link #delete}: the current session and one open in another instance are
     * refused with an IOException. An absent module deletes nothing. Off the EDT (pruning).
     */
    public int deleteFiles(String session, String module, Predicate<Path> match) throws IOException {
        checkModule(module); Objects.requireNonNull(match, "match");
        if (!writable || session.equals(current.id)) throw new IOException("The current session is still recording.");
        Path folder = sessionPath(session).resolve(module);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return 0;
        return whileClosed(session, () -> {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder)) {
                for (Path file : listing) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file);
            }
            int deleted = 0;
            for (Path file : files) {
                if (!match.test(file)) continue;
                try { if (Files.deleteIfExists(file)) deleted++; }
                catch (IOException kept) { /* in use or protected: kept, not counted */ }
            }
            return deleted;
        });
    }
    private interface Locked<T> { T run() throws IOException; }
    /** Runs {@code action} holding a closed session's lock, refusing a session that this or another instance has open. */
    private <T> T whileClosed(String id, Locked<T> action) throws IOException {
        try (FileChannel channel = FileChannel.open(sessionPath(id).resolve(".active"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("This session is open in another RealmShark instance.");
            return action.run();
        } catch (OverlappingFileLockException e) { throw new IOException("This session is still open.", e); }
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
            metadata.addProperty("label",label);writeAtomic(meta,metadata.toString(),false);
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
                    if(writable){
                        IOException failure = null;
                        try { forceJournals(); } catch (IOException e) { failure = e; }
                        current.ended=System.currentTimeMillis();
                        try { writeAtomic(sessionPath(current.id).resolve("session.json"),JSON.toJson(current),true); }
                        catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
                        if (failure != null) throw failure;
                    }
                    if(fileLock!=null)fileLock.release();if(lockChannel!=null)lockChannel.close();
                }catch(IOException e){throw new UncheckedIOException(e);}
            }).get(5,TimeUnit.SECONDS);
        } catch (Exception e) { error = UNSAVED_ON_CLOSE + root; }
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
        public static final int INTERVAL_LIMIT = 256;
        public final int schemaVersion;
        public final State state;
        public final String reason;
        public final Long from, until;
        /** Optional producer recording intervals, oldest first; null in histories written before Wave 3. */
        public final List<Interval> intervals;
        /** Older intervals were dropped at {@link #INTERVAL_LIMIT}; times before the first are unknown. */
        public final boolean truncated;
        public ModuleAvailability(State state, String reason, Long from, Long until) { this(state, reason, from, until, null, false); }
        public ModuleAvailability(State state, String reason, Long from, Long until, List<Interval> intervals, boolean truncated) {
            this.schemaVersion = 1;
            this.state = Objects.requireNonNull(state); this.reason = Objects.requireNonNull(reason);
            this.from = from; this.until = until;
            this.intervals = intervals == null ? null : Collections.unmodifiableList(new ArrayList<>(intervals)); this.truncated = truncated;
        }
        /**
         * TRUE when a recording interval covers the time (recorded; an absent record means none happened),
         * FALSE when interval evidence exists and excludes it (not recorded), null when coverage is unknown.
         */
        public Boolean recordedAt(long time) {
            if (intervals == null || intervals.isEmpty()) return null;
            if (truncated && time < intervals.get(0).from) return null;
            for (Interval interval : intervals) if (interval.from <= time && time <= interval.until) return Boolean.TRUE;
            return Boolean.FALSE;
        }
        /** Whether this evidence is well formed; malformed evidence is treated as unknown coverage. */
        public boolean valid() {
            if (schemaVersion != 1 || state == null || reason == null) return false;
            if (intervals != null) for (Interval interval : intervals) if (interval == null || interval.until < interval.from || interval.end == null) return false;
            return true;
        }
    }
    /** A span in which the producer was actually collecting; {@code end} explains how it closed. */
    public static final class Interval {
        public final long from, until;
        public final String end;
        public Interval(long from, long until, String end) {
            if (until < from) throw new IllegalArgumentException("Interval ends before it starts");
            this.from = from; this.until = until; this.end = Objects.requireNonNull(end);
        }
    }
    /**
     * Merges one producer recording interval (same start extends it) into this session's module coverage,
     * asynchronously. Prior intervals are kept; beyond the limit the oldest are dropped and marked truncated.
     */
    public CompletionStage<Void> recordInterval(String module, long from, long until, String end) {
        Interval added = new Interval(from, until, end);
        return updateAvailability(module, prior -> {
            List<Interval> merged = new ArrayList<>();
            boolean truncated = prior != null && prior.truncated, replaced = false;
            if (prior != null && prior.intervals != null) for (Interval interval : prior.intervals) {
                if (interval.from == added.from) { merged.add(new Interval(from, Math.max(interval.until, until), end)); replaced = true; }
                else merged.add(interval);
            }
            if (!replaced) merged.add(added);
            merged.sort(Comparator.comparingLong(interval -> interval.from));
            while (merged.size() > ModuleAvailability.INTERVAL_LIMIT) { merged.remove(0); truncated = true; }
            return new ModuleAvailability(ModuleAvailability.State.PARTIAL,
                "Recorded only while collection was on; outside these intervals nothing was recorded",
                merged.get(0).from, merged.get(merged.size() - 1).until, merged, truncated);
        });
    }
    /** Future producers explicitly declare coverage. Merely writing a record never declares completeness. */
    public CompletionStage<Void> availability(String module, ModuleAvailability evidence) {
        Objects.requireNonNull(evidence);
        // Richer prior interval evidence is never replaced by a summary without intervals.
        return updateAvailability(module, prior -> evidence.intervals == null && prior != null && prior.intervals != null
            ? new ModuleAvailability(evidence.state, evidence.reason, evidence.from, evidence.until, prior.intervals, prior.truncated) : evidence);
    }
    private CompletionStage<Void> updateAvailability(String module, java.util.function.UnaryOperator<ModuleAvailability> update) {
        checkModule(module);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!writable || closing) { completion.completeExceptionally(new IOException("History is read-only or closed")); return completion; }
        worker.execute(() -> {
            try {
                ensureCurrent();
                Session next = JSON.fromJson(JSON.toJson(current), Session.class);
                if (next.availability == null) next.availability = new LinkedHashMap<>();
                ModuleAvailability prior = next.availability.get(module);
                next.availability.put(module, update.apply(prior != null && prior.valid() ? prior : null));
                writeAtomic(sessionPath(current.id).resolve("session.json"), JSON.toJson(next), false);
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
            return value != null && value.valid() ? value
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
