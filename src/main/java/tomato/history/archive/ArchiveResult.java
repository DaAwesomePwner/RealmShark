package tomato.history.archive;

import com.google.gson.*;
import tomato.history.SessionStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disk-backed immutable matching population. A display and its exports lease the same bytes. */
public final class ArchiveResult<R> implements AutoCloseable {
    public static final int CHUNK_ROWS=1024, CHUNK_BYTES=4*1024*1024, MERGE_FAN_IN=16;
    public static final int MAX_ROW_BYTES=1024*1024, MAX_PAGE_BYTES=8*1024*1024;
    private static final ExecutorService CLEANUP=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Archive cleanup");t.setDaemon(true);return t;});
    private final Path directory, rows, index;
    private final ReadSnapshot pin;
    private final Class<R> type;
    private final JsonObject manifest;
    private final Map<String,ArchiveAdapter.Count> counts;
    public final String revision, unit;
    public final long matches, scanned;
    /** Measured builder high-water marks, useful in synthetic memory-bound checks. */
    public final int maximumChunkRows, maximumMergeReaders;
    private int leases;
    private boolean closed, deleted;

    private ArchiveResult(Path directory, ReadSnapshot pin, Class<R> type, JsonObject manifest,
            String unit,long matches,long scanned,int chunk,int readers,Map<String,ArchiveAdapter.Count> counts) {
        this.directory=directory; rows=directory.resolve("rows.bin");index=directory.resolve("offsets.bin");
        this.pin=pin;this.type=type;this.manifest=manifest;revision=pin.revision;this.unit=unit;
        this.matches=matches;this.scanned=scanned;maximumChunkRows=chunk;maximumMergeReaders=readers;
        this.counts=Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
    public static <R,F,S extends Enum<S>> ArchiveResult<R> open(SessionStore store, ArchiveQuery<F,S> query,
            ArchiveAdapter<R,F,S> adapter,Path scratch,Cancellation cancel) throws IOException {
        ArchiveIO.offEdt();cancel.check();adapter.validate(query);
        ReadSnapshot pin=store.capture(adapter.sources(store,query),scratch,cancel);
        return open(pin,query,adapter,scratch,cancel);
    }
    /** Ownership of pin transfers to this operation, including on failure. */
    public static <R,F,S extends Enum<S>> ArchiveResult<R> open(ReadSnapshot pin,ArchiveQuery<F,S> query,
            ArchiveAdapter<R,F,S> adapter,Path scratch,Cancellation cancel) throws IOException {
        ArchiveIO.offEdt(); Path directory=null;
        try {
            cancel.check();adapter.validate(query);pin.bindQuery(adapter,query);
            Files.createDirectories(scratch);directory=Files.createTempDirectory(scratch,"archive-result-");
            Comparator<ArchiveRow<R>> order=(a,b)->0;
            for(ArchiveQuery.Order<S> item:query.order()) {
                Comparator<R> values=Objects.requireNonNull(adapter.comparator(item.field),"Unsupported sort field");
                if(item.direction==ArchiveQuery.Direction.DESCENDING)values=values.reversed();
                Comparator<R> field=values;
                order=order.thenComparing((a,b)->field.compare(a.value,b.value));
            }
            order=order.thenComparing(row->row.ref);
            Builder<R> builder=new Builder<>(directory,adapter.rowType(),order,cancel);
            long[] scanned={0};
            adapter.scan(pin,query,row->{
                String scope=pin.resolveScope(query.scope());
                if(!SessionStore.ALL.equals(scope)&&row.ref.session.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")&&!scope.equals(row.ref.session))
                    throw new IOException("Adapter emitted a record outside the selected query session");
                cancel.check();scanned[0]++;
                if(adapter.inBounds(row,query) && adapter.matches(row,query))builder.add(row);
            },cancel);
            long count=builder.finish();
            JsonObject manifest=new JsonObject();manifest.addProperty("schemaVersion",1);manifest.addProperty("revision",pin.revision);
            manifest.addProperty("capturedFrom",pin.started);manifest.addProperty("capturedUntil",pin.finished());
            manifest.addProperty("captureSemantics","Fixed source cuts; saved data, not a cross-process transaction");
            manifest.add("query",query.toJson());manifest.addProperty("matchingCount",count);manifest.addProperty("unit",adapter.unit());
            manifest.addProperty("resolvedScope",pin.resolveScope(query.scope()));
            manifest.addProperty("scannedCount",scanned[0]);manifest.add("dependencies",SessionStore.JSON.toJsonTree(new TreeMap<>(adapter.dependencies())));
            manifest.add("issues",SessionStore.JSON.toJsonTree(pin.issues()));
            JsonArray sessions=new JsonArray();for(String id:pin.sessionIds())sessions.add(SessionStore.JSON.toJsonTree(pin.session(id)));
            manifest.add("sessions",sessions);
            manifest.addProperty("coverage","Missing module availability means recording coverage unknown; presence is not completeness");
            JsonArray cuts=new JsonArray();for(ReadSnapshot.Cut cut:pin.cuts()) {
                JsonObject c=new JsonObject();c.addProperty("session",cut.session);c.addProperty("module",cut.module);
                c.addProperty("locator",cut.locator);c.addProperty("bytes",cut.bytes);c.addProperty("sha256",cut.sha256);
                c.addProperty("incompleteTail",cut.incompleteTail);cuts.add(c);
            }
            manifest.add("sources",cuts);
            Map<String,ArchiveAdapter.Count> counts=new TreeMap<>(adapter.counts());
            for(Map.Entry<String,ArchiveAdapter.Count> entry:counts.entrySet())Objects.requireNonNull(entry.getValue(),"Unknown counts must be omitted");
            manifest.add("counts",SessionStore.JSON.toJsonTree(counts));
            return new ArchiveResult<>(directory,pin,adapter.rowType(),manifest,adapter.unit(),count,scanned[0],builder.maxChunk,builder.maxReaders,counts);
        } catch(IOException | RuntimeException | Error failure) {
            if(directory!=null)try{ArchiveIO.deleteTree(directory);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            try{pin.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    public JsonObject manifest() { return manifest.deepCopy(); }
    public ArchivePage<R> page(long page,int size,Cancellation cancel) throws IOException {
        ExportSelection selection=ExportSelection.page(page,size);List<ArchiveRow<R>> values=new ArrayList<>();
        long[] bytes={0};
        try(Lease<R> lease=lease()){lease.stream(selection,row->{
            bytes[0]+=encode(row).length;
            if(bytes[0]>MAX_PAGE_BYTES)throw new IOException("Page exceeds 8 MiB; use a smaller page or a lighter row projection");
            values.add(row);
        },cancel);}
        return new ArchivePage<>(values,page,size,matches,revision,unit,pin.issues(),counts,this);
    }
    public void stream(ExportSelection selection,ArchiveAdapter.Sink<R> sink,Cancellation cancel) throws IOException {
        try(Lease<R> lease=lease()){lease.stream(selection,sink,cancel);}
    }
    /** Resolves a retained reference outside the current page without loading all rows. */
    public long pageOf(ArchiveRow.Ref ref,int size,Cancellation cancel)throws IOException {
        ExportSelection.page(0,size);ArchiveIO.offEdt();
        try(Lease<R> lease=lease();DataInputStream input=new DataInputStream(new BufferedInputStream(Files.newInputStream(rows)))) {
            long position=0;byte[] bytes;
            while((bytes=ArchiveIO.read(input))!=null){cancel.check();if(decode(bytes,type).ref.equals(ref))return position/size;position++;}
            return -1;
        }
    }
    public synchronized Lease<R> lease() throws IOException {
        if(closed)throw new IOException("Archive result is closed");leases++;return new Lease<>(this);
    }
    private void streamPinned(ExportSelection selection,ArchiveAdapter.Sink<R> sink,Cancellation cancel) throws IOException {
        ArchiveIO.offEdt();cancel.check();long first=selection.kind==ExportSelection.Kind.PAGE?selection.page*selection.size:0;
        long expected=selection.expected(matches), delivered=0;
        if(expected==0)return;
        try(RandomAccessFile offsets=new RandomAccessFile(index.toFile(),"r");RandomAccessFile data=new RandomAccessFile(rows.toFile(),"r")) {
            if(first>=matches)throw new IOException("Selected records are not all present in this result revision");
            offsets.seek(Math.multiplyExact(first,8));data.seek(offsets.readLong());
            for(long n=first;n<matches;n++) {
                cancel.check();int length=data.readInt();
                if(length<0||length>ArchiveIO.MAX_RECORD)throw new IOException("Invalid result record");
                byte[] bytes=new byte[length];data.readFully(bytes);ArchiveRow<R> row=decode(bytes,type);
                if(selection.kind!=ExportSelection.Kind.SELECTED||selection.refs.contains(row.ref)){sink.accept(row);delivered++;}
                if(selection.kind==ExportSelection.Kind.PAGE && delivered==expected)break;
            }
        }
        if(delivered!=expected)throw new IOException("Selected records are not all present in this result revision");
    }
    @Override public void close() {
        Runnable cleanup;
        synchronized(this){if(closed)return;closed=true;cleanup=cleanupIfUnused();}
        clean(cleanup);
    }
    private void release() {
        Runnable cleanup;synchronized(this){leases--;cleanup=cleanupIfUnused();}clean(cleanup);
    }
    private Runnable cleanupIfUnused() {
        if(!closed||leases!=0||deleted)return null;deleted=true;
        return ()->{
            try { ArchiveIO.deleteTree(directory); } catch(IOException failure) { System.err.println("Could not remove archive result scratch files."); }
            try { pin.close(); } catch(IOException failure) { System.err.println("Could not remove archive snapshot scratch files."); }
        };
    }
    private static void clean(Runnable cleanup) {
        if(cleanup==null)return;
        // Never hold the lease monitor across filesystem cleanup: an EDT close/lease must not wait for disk.
        if(javax.swing.SwingUtilities.isEventDispatchThread())CLEANUP.execute(cleanup);else cleanup.run();
    }
    public static final class Lease<R> implements AutoCloseable {
        private final ArchiveResult<R> result;private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        private Lease(ArchiveResult<R> result){this.result=result;}
        public JsonObject manifest(){return result.manifest();}
        public long matches(){return result.matches;}
        public void stream(ExportSelection selection,ArchiveAdapter.Sink<R> sink,Cancellation cancel)throws IOException {
            if(closed.get())throw new IOException("Archive lease is closed");result.streamPinned(selection,sink,cancel);
        }
        /** Source must have been declared in the original pin. Used for exact saved details, not fresh history. */
        public <T> void readSource(String scope,String module,Class<T> type,ArchiveAdapter.Sink<T> sink,Cancellation cancel)throws IOException {
            if(closed.get())throw new IOException("Archive lease is closed");result.pin.read(scope,module,type,sink,cancel);
        }
        @Override public void close(){if(closed.compareAndSet(false,true))result.release();}
    }
    private static <R> byte[] encode(ArchiveRow<R> row) {
        JsonObject json=new JsonObject();json.add("ref",SessionStore.JSON.toJsonTree(row.ref));json.add("value",SessionStore.JSON.toJsonTree(row.value));
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static <R> ArchiveRow<R> decode(byte[] bytes,Class<R> type)throws IOException {
        try {
            JsonObject json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            return new ArchiveRow<>(SessionStore.JSON.fromJson(json.get("ref"),ArchiveRow.Ref.class),SessionStore.JSON.fromJson(json.get("value"),type));
        }catch(RuntimeException failure){throw new IOException("Unreadable archive result",failure);}
    }
    private static final class Encoded<R> {
        final ArchiveRow<R> row;final byte[] bytes;
        Encoded(ArchiveRow<R> row,byte[] bytes){this.row=row;this.bytes=bytes;}
    }
    private static final class Builder<R> {
        final Path directory;final Class<R> type;final Comparator<ArchiveRow<R>> order;final Cancellation cancel;
        final List<Encoded<R>> chunk=new ArrayList<>();final List<List<Path>> levels=new ArrayList<>();
        int bytes,maxChunk,maxReaders,sequence;
        Builder(Path directory,Class<R> type,Comparator<ArchiveRow<R>> order,Cancellation cancel){this.directory=directory;this.type=type;this.order=order;this.cancel=cancel;}
        void add(ArchiveRow<R> row)throws IOException {
            byte[] encoded=encode(row);if(encoded.length>MAX_ROW_BYTES)throw new IOException("Projected row exceeds 1 MiB; project a summary and load details separately");
            if(!chunk.isEmpty()&&(chunk.size()>=CHUNK_ROWS||bytes+encoded.length>CHUNK_BYTES))flush();
            // Decode the encoded copy: a projection callback may reuse or later mutate its input.
            chunk.add(new Encoded<>(decode(encoded,type),encoded));bytes+=encoded.length;maxChunk=Math.max(maxChunk,chunk.size());
        }
        Path next(){return directory.resolve("run-"+(sequence++)+".bin");}
        void flush()throws IOException {
            if(chunk.isEmpty())return;cancel.check();chunk.sort((a,b)->order.compare(a.row,b.row));Path path=next();
            try(DataOutputStream output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                for(Encoded<R> row:chunk){cancel.check();ArchiveIO.write(output,row.bytes);}
            }
            chunk.clear();bytes=0;carry(path,0);
        }
        void carry(Path path,int level)throws IOException {
            while(levels.size()<=level)levels.add(new ArrayList<>());
            List<Path> group=levels.get(level);group.add(path);
            if(group.size()==MERGE_FAN_IN){Path merged=merge(new ArrayList<>(group));group.clear();carry(merged,level+1);}
        }
        Path merge(List<Path> sources)throws IOException {
            maxReaders=Math.max(maxReaders,sources.size());Path target=next();List<Cursor<R>> opened=new ArrayList<>();
            PriorityQueue<Cursor<R>> queue=new PriorityQueue<>((a,b)->{int c=order.compare(a.row,b.row);return c!=0?c:Integer.compare(a.number,b.number);});
            try(DataOutputStream output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
                for(Path source:sources){Cursor<R> cursor=new Cursor<>(source,opened.size(),type);opened.add(cursor);if(cursor.advance())queue.add(cursor);}
                while(!queue.isEmpty()) {
                    cancel.check();Cursor<R> cursor=queue.remove();ArchiveIO.write(output,cursor.bytes);
                    if(cursor.advance())queue.add(cursor);
                }
            }finally{for(Cursor<R> cursor:opened)cursor.close();}
            for(Path source:sources)Files.delete(source);return target;
        }
        long finish()throws IOException {
            flush();List<Path> remaining=new ArrayList<>();
            for(List<Path> level:levels)for(Path path:level){remaining.add(path);if(remaining.size()==MERGE_FAN_IN){Path merged=merge(remaining);remaining=new ArrayList<>();remaining.add(merged);}}
            Path sorted=remaining.isEmpty()?next():remaining.size()==1?remaining.get(0):merge(remaining);
            if(remaining.isEmpty())Files.createFile(sorted);
            Files.move(sorted,directory.resolve("rows.bin"));long count=0,offset=0;
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("rows.bin"))));
                DataOutputStream index=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(directory.resolve("offsets.bin"))))) {
                byte[] row;while((row=ArchiveIO.read(input))!=null){cancel.check();index.writeLong(offset);offset+=4L+row.length;count++;}
            }
            return count;
        }
    }
    private static final class Cursor<R> implements AutoCloseable {
        final DataInputStream input;final int number;final Class<R> type;byte[] bytes;ArchiveRow<R> row;
        Cursor(Path path,int number,Class<R> type)throws IOException {input=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));this.number=number;this.type=type;}
        boolean advance()throws IOException {bytes=ArchiveIO.read(input);row=bytes==null?null:decode(bytes,type);return bytes!=null;}
        public void close()throws IOException {input.close();}
    }
}
