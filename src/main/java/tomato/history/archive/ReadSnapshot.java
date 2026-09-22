package tomato.history.archive;

import tomato.history.SessionStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** A private copy of fixed journal prefixes and checkpoint images. Not a cross-process transaction. */
public final class ReadSnapshot implements AutoCloseable {
    public static final class Source {
        public final String scope, module;
        public Source(String scope, String module) {
            if (!SessionStore.ALL.equals(scope) && (scope == null || !scope.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
                throw new IllegalArgumentException("Resolve source scope before capture");
            if (module == null || !module.matches("[a-z][a-z-]*")) throw new IllegalArgumentException("Invalid module");
            this.scope = scope; this.module = module;
        }
    }
    public static final class Cut {
        public final String session, module, locator, sha256;
        public final long bytes;
        public final boolean journal, incompleteTail;
        private final Path copy;
        Cut(String session, String module, String locator, Path copy, long bytes, String sha256, boolean journal, boolean tail) {
            this.session=session; this.module=module; this.locator=locator; this.copy=copy;
            this.bytes=bytes; this.sha256=sha256; this.journal=journal; this.incompleteTail=tail;
        }
    }
    public final String revision = UUID.randomUUID().toString();
    public final long started = System.currentTimeMillis();
    private long finished;
    private final Path directory;
    private final SessionStore origin;
    private final String currentId;
    private final List<Source> declarations;
    private final List<Cut> cuts = new ArrayList<>();
    private final Map<String, SessionStore.Session> sessions = new LinkedHashMap<>();
    private final Map<String, Set<String>> included = new LinkedHashMap<>();
    private final List<String> issues = new ArrayList<>();
    private boolean closed;
    private boolean bound;

    private ReadSnapshot(Path directory,SessionStore origin,List<Source> sources) {
        this.directory=directory;this.origin=origin;this.currentId=origin.currentId();
        this.declarations=Collections.unmodifiableList(new ArrayList<>(sources));
    }
    public static ReadSnapshot capture(SessionStore store, List<Source> sources, Path scratch, Cancellation cancel) throws IOException {
        ArchiveIO.offEdt(); cancel.check(); Files.createDirectories(scratch);
        ReadSnapshot result = new ReadSnapshot(Files.createTempDirectory(scratch, "archive-pin-"),store,sources);
        try {
            List<SessionStore.SessionEntry> catalog = store.catalog(cancel);
            Set<String> seen = new HashSet<>();
            for (Source source : sources) {
                Set<String> includedSessions=result.included.computeIfAbsent(source.module,key->new LinkedHashSet<>());
                boolean found = false;
                for (SessionStore.SessionEntry entry : catalog) {
                    cancel.check();
                    if (!SessionStore.ALL.equals(source.scope) && !entry.id.equals(source.scope)) continue;
                    found = true;
                    if (!entry.readable()) {
                        if (!SessionStore.ALL.equals(source.scope)) throw new IOException(entry.error);
                        String issue = entry.id + ": " + entry.error;
                        if (!result.issues.contains(issue)) result.issues.add(issue);
                        continue;
                    }
                    if (!seen.add(entry.id + "/" + source.module)) continue;
                    includedSessions.add(entry.id);
                    result.sessions.put(entry.id, entry.session());
                    Path base = store.directory().resolve(entry.id);
                    Path journal = base.resolve(source.module + ".jsonl");
                    if (Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS))
                        result.copy(entry.id, source.module, journal, true, cancel);
                    Path checkpoints = base.resolve(source.module);
                    if (Files.isDirectory(checkpoints, LinkOption.NOFOLLOW_LINKS)) {
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(checkpoints, "*.json")) {
                            for (Path file : files) {
                                cancel.check();
                                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) result.copy(entry.id, source.module, file, false, cancel);
                            }
                        }
                    }
                }
                if (!found && !SessionStore.ALL.equals(source.scope)) throw new IOException("Selected session is no longer available");
            }
            result.cuts.sort(Comparator.comparing((Cut c) -> c.session).thenComparing(c -> c.module).thenComparing(c -> c.locator));
            result.finished=System.currentTimeMillis(); return result;
        } catch (IOException | RuntimeException | Error failure) {
            try { result.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void copy(String session, String module, Path source, boolean journal, Cancellation cancel) throws IOException {
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!journal && before.size() > ArchiveIO.MAX_RECORD) throw new IOException("Checkpoint exceeds the 16 MiB record limit");
        Path target = directory.resolve(Integer.toString(cuts.size()) + ".data");
        long remaining = before.size(), position = 0, lastNewline = 0;
        try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                cancel.check(); int n = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (n < 0) throw new IOException("History source shortened during capture; retry");
                if (journal) for (int i=0;i<n;i++) if (buffer[i]=='\n') lastNewline=position+i+1;
                output.write(buffer,0,n); position+=n; remaining-=n;
            }
        }
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || after.size() < before.size()
                || (!journal && (!before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size()!=after.size())))
            throw new IOException("History source changed during capture; retry");
        boolean tail=journal && position!=lastNewline;
        if (tail) try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.truncate(lastNewline); }
        String locator=journal ? "journal" : "checkpoint:" + source.getFileName();
        cuts.add(new Cut(session,module,locator,target,journal?lastNewline:position,digest(target,cancel),journal,tail));
        if (tail) issues.add(session + "/" + module + ": unfinished journal tail excluded");
    }
    private static String digest(Path path, Cancellation cancel) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream input=Files.newInputStream(path)) {
                byte[] buffer=new byte[64*1024]; int n;
                while ((n=input.read(buffer))>=0) { cancel.check(); digest.update(buffer,0,n); }
            }
            StringBuilder text=new StringBuilder(); for(byte b:digest.digest()) text.append(String.format(Locale.ROOT,"%02x",b&255));
            return text.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public long finished() { return finished; }
    /** Current means the session of the store that captured this pin, not a later application launch. */
    public String resolveScope(String scope) { return ArchiveQuery.CURRENT.equals(scope)?currentId:scope; }
    /** Ownership has transferred to one result. Restrict even unscoped custom-adapter reads to its declarations. */
    <R,F,S extends Enum<S>> void bindQuery(ArchiveAdapter<R,F,S> adapter,ArchiveQuery<F,S> query)throws IOException {
        if(closed||bound)throw new IOException("Archive snapshot is closed or already bound to a result");
        String scope=resolveScope(query.scope());
        List<Source> requested=adapter.sources(origin,query.withScope(scope));
        Map<String,Set<String>> selected=new LinkedHashMap<>();boolean primary=SessionStore.ALL.equals(scope),all=false;
        for(Source source:requested) {
            Set<String> available=included.get(source.module);
            if(available==null)throw new IOException("Required module was not captured: "+source.module);
            Set<String> ids=selected.computeIfAbsent(source.module,key->new LinkedHashSet<>());
            if(SessionStore.ALL.equals(source.scope)) {
                if(declarations.stream().noneMatch(d->d.module.equals(source.module)&&SessionStore.ALL.equals(d.scope)))
                    throw new IOException("All-session source was not captured: "+source.module);
                ids.addAll(available);all=true;
            } else {
                if(!SessionStore.ALL.equals(scope)&&!scope.equals(source.scope))throw new IOException("Adapter source disagrees with query scope");
                if(!available.contains(source.scope))throw new IOException("Query session was not captured for "+source.module);
                ids.add(source.scope);if(scope.equals(source.scope))primary=true;
            }
        }
        if(!primary)throw new IOException("Adapter must declare the selected query session");
        Set<String> selectedSessions=new LinkedHashSet<>();for(Set<String> ids:selected.values())selectedSessions.addAll(ids);
        cuts.removeIf(c->!selected.containsKey(c.module)||!selected.get(c.module).contains(c.session));
        sessions.keySet().retainAll(selectedSessions);
        if(!all)issues.removeIf(issue->selectedSessions.stream().noneMatch(issue::startsWith));
        included.clear();included.putAll(selected);bound=true;
    }
    public List<Cut> cuts() { return Collections.unmodifiableList(cuts); }
    public List<String> issues() { return Collections.unmodifiableList(issues); }
    public Set<String> sessionIds() { return Collections.unmodifiableSet(sessions.keySet()); }
    public SessionStore.Session session(String id) {
        return SessionStore.JSON.fromJson(SessionStore.JSON.toJson(sessions.get(id)), SessionStore.Session.class);
    }
    public <T> void read(String module, Class<T> type, ArchiveAdapter.Sink<T> rows, Cancellation cancel) throws IOException {
        read(SessionStore.ALL,module,type,rows,cancel);
    }
    public <T> void read(String scope, String module, Class<T> type, ArchiveAdapter.Sink<T> rows, Cancellation cancel) throws IOException {
        ArchiveIO.offEdt(); if (closed) throw new IOException("Archive snapshot is closed");
        scope=resolveScope(scope);
        Set<String> allowed=included.get(module);
        if(allowed==null || (!SessionStore.ALL.equals(scope)&&!allowed.contains(scope)))
            throw new IOException("Requested source was not included in this archive snapshot");
        for (Cut cut : cuts) {
            if (!cut.module.equals(module) || !allowed.contains(cut.session) || (!SessionStore.ALL.equals(scope) && !cut.session.equals(scope))) continue;
            cancel.check();
            if (!cut.journal) {
                emit(cut,new String(Files.readAllBytes(cut.copy),StandardCharsets.UTF_8),cut.locator,type,rows);
            } else try (InputStream input=new BufferedInputStream(Files.newInputStream(cut.copy))) {
                ByteArrayOutputStream line=new ByteArrayOutputStream(); long offset=0,start=0; int value;
                while ((value=input.read())>=0) {
                    if ((offset & 4095)==0) cancel.check(); offset++;
                    if (value=='\n') {
                        emit(cut,new String(line.toByteArray(),StandardCharsets.UTF_8),"journal:"+String.format(Locale.ROOT,"%020d",start),type,rows);
                        line.reset(); start=offset;
                    } else {
                        if (line.size() >= ArchiveIO.MAX_RECORD) throw new IOException("Journal record exceeds the 16 MiB record limit");
                        line.write(value);
                    }
                }
            }
        }
    }
    private <T> void emit(Cut cut,String json,String locator,Class<T> type,ArchiveAdapter.Sink<T> rows) throws IOException {
        T value;
        try { value=SessionStore.JSON.fromJson(json,type); }
        catch (RuntimeException failure) { throw new IOException("Unreadable " + cut.module + " record in session " + cut.session, failure); }
        if (value==null) throw new IOException("Null archive record in " + cut.module);
        if (value instanceof packets.packetcapture.logger.ActivityJournal.Visit) {
            packets.packetcapture.logger.ActivityJournal.Visit visit=(packets.packetcapture.logger.ActivityJournal.Visit)value;
            if (visit.ended==0 && sessions.get(cut.session).ended>0) { visit.ended=visit.lastSeen; visit.endReason="App ended"; }
        }
        rows.accept(new ArchiveRow<>(new ArchiveRow.Ref(cut.session,cut.module,locator,""),value));
    }
    @Override public void close() throws IOException {
        if (closed) return; closed=true; ArchiveIO.deleteTree(directory);
    }
}
