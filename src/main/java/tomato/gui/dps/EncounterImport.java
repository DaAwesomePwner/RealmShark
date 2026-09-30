package tomato.gui.dps;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.Set;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import packets.Packet;
import tomato.backend.data.DpsData;
import tomato.history.encounter.CombatRetention;

/**
 * A recording read from a {@code .dps} file: a user's import, or saved full detail loaded back from history
 * ({@link #readSaved}). Import provenance is local file evidence, never an inferred gameplay/session join.
 *
 * <p>Every read is filtered and runs on its own deep stack. The {@link Filter} allows only the classes of RealmShark's
 * recording graph ({@link #ALLOWED}, proven by {@code EncounterImportFilterTest} from the streams of real recordings) plus
 * RealmShark's own packet and packet data classes, which the debug packet log of Save Debug Data exports and older files
 * holds (plain data: none has a deserialization hook). Anything else fails before any of its code runs, with an
 * {@link IOException} naming the class. Java serialization recurses through the hit graph ({@code Damage.owner}), so a long
 * Realm recording overflows a default thread stack: each read runs on a new daemon thread, {@value #THREAD}, with the stack
 * {@code CombatAutosave} writes with, while the caller (never the EDT) waits.
 */
public final class EncounterImport {
    public static final String THREAD = "RealmShark recording reader";
    /** As {@code CombatAutosave}'s writer: 64 MB reads Realm recordings far deeper than a default 1 MB stack. */
    static final long STACK_BYTES = 64L << 20;
    static final String NOT_READ = "This file contains data RealmShark does not read: ";
    /** The longest array any stream may declare, whatever its size (an element takes at least one byte of the file). */
    static final long MAX_ARRAY = 50_000_000;
    /**
     * Classes of a recording's graph, by name (array classes are checked by their element class): {@code DpsData} and what it
     * holds, the JDK containers, boxes and supertypes its fields use, and {@code Map$Entry} and {@code Object}, the element
     * types of the arrays {@code HashMap} and {@code ArrayList} check before allocating them. Packets are admitted by
     * {@link #allowed}. Adding a class here means reviewing it for deserialization hooks first.
     */
    static final Set<String> ALLOWED = Set.of(
        "tomato.backend.data.DpsData", "tomato.backend.data.DpsData$LocalPlayerContext", "tomato.backend.data.Entity",
        "tomato.backend.data.Stat", "tomato.backend.data.Damage", "tomato.backend.data.Projectile",
        "tomato.backend.data.DamageSource", "tomato.backend.data.PlayerRemoved", "packets.Packet",
        "tomato.backend.data.PresenceTimeline", "tomato.backend.data.PresenceTimeline$Player",
        "tomato.backend.data.PresenceTimeline$Change", "tomato.backend.data.PresenceTimeline$Death",
        "tomato.backend.data.PresenceTimeline$Mark",
        "java.util.HashMap", "java.util.ArrayList", "java.util.Map$Entry", "java.lang.Object", "java.lang.Number",
        "java.lang.Integer", "java.lang.Long", "java.lang.Enum", "java.lang.String");
    /** Test hook: runs on the reader thread before a file is opened. */
    static volatile Runnable beforeRead = () -> { };

    public final DpsData data;
    /** The file's name (never its folder or path). */
    public final String fileName;
    /** SHA-256 of the file's exact bytes. */
    public final String fingerprint;
    public final long importedAt;
    /** {@link EncounterCatalog.Kind#IMPORTED} for a user's file, {@link EncounterCatalog.Kind#SAVED} for saved full detail. */
    public final EncounterCatalog.Kind kind;
    /** Saved full detail only: the ID of the session folder holding its {@code combat-full} folder; else null. */
    public final String session;
    final EncounterSummary summary;

    private EncounterImport(DpsData data, String fileName, String fingerprint, long importedAt, EncounterCatalog.Kind kind, String session) {
        this.data = data; this.fileName = fileName; this.fingerprint = fingerprint; this.importedAt = importedAt;
        this.kind = kind; this.session = session;
        summary = new EncounterSummary(data);
    }

    /** A user's {@code .dps} file (kind imported). Off the EDT; the caller waits for the reader thread. */
    public static EncounterImport read(Path file) throws IOException {
        return read(file, EncounterCatalog.Kind.IMPORTED);
    }

    /**
     * A saved full-detail file ({@code <session>/combat-full/<name>.dps}, as {@code CombatAutosave.fullDetailFile} places it),
     * kind saved. Off the EDT; the caller waits for the reader thread.
     */
    public static EncounterImport readSaved(Path file) throws IOException {
        return read(file, EncounterCatalog.Kind.SAVED);
    }

    private static EncounterImport read(Path file, EncounterCatalog.Kind kind) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read recordings off the EDT");
        FutureTask<EncounterImport> task = new FutureTask<>(() -> deserialize(file, kind));
        Thread reader = new Thread(null, task, THREAD, STACK_BYTES);
        reader.setDaemon(true);
        reader.start();
        try { return task.get(); }
        catch (InterruptedException e) {
            task.cancel(true);   // interrupts the reader: its interruptible file channel closes
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Reading " + file.getFileName() + " was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException(cause);
        }
    }

    private static EncounterImport deserialize(Path file, EncounterCatalog.Kind kind) throws IOException {
        beforeRead.run();
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        DpsData data;
        Filter filter = new Filter(Files.size(file));
        try (DigestInputStream bytes = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), sha);
             ObjectInputStream input = new ObjectInputStream(bytes)) {
            input.setObjectInputFilter(filter);
            Object value;
            try { value = input.readObject(); }
            catch (InvalidClassException rejected) {
                if (filter.rejected != null) throw new IOException(NOT_READ + filter.rejected, rejected);
                if (filter.limit != null) throw new IOException("This file is not a readable DPS encounter: " + filter.limit, rejected);
                throw rejected;
            } catch (ClassNotFoundException missing) {   // a class RealmShark does not have
                throw new IOException(NOT_READ + missing.getMessage(), missing);
            } catch (StackOverflowError deep) {
                throw new IOException("This recording is nested too deeply to read.", deep);
            }
            if (!(value instanceof DpsData)) throw new IOException("This file is not a DPS encounter.");
            data = (DpsData)value;
            if (data.hitList == null || data.deathNotifications == null) throw new IOException("The encounter is incomplete.");
            byte[] rest = new byte[8192]; while (bytes.read(rest) != -1) { /* Include all original bytes in duplicate detection. */ }
        }
        StringBuilder digest = new StringBuilder(); for (byte b : sha.digest()) digest.append(String.format("%02x", b));
        return new EncounterImport(data, file.getFileName().toString(), digest.toString(), System.currentTimeMillis(), kind,
            kind == EncounterCatalog.Kind.SAVED ? session(file) : null);
    }

    /** The session ID of {@code <session>/combat-full/<file>}, or null when the file is not in a session's full-detail folder. */
    private static String session(Path file) {
        Path folder = file.toAbsolutePath().getParent(), session = folder == null ? null : folder.getParent();
        if (session == null || session.getFileName() == null || !CombatRetention.FULL_DETAIL.equals(folder.getFileName().toString())) return null;
        String id = session.getFileName().toString();
        return id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") ? id : null;   // SessionStore's session ID form
    }

    /**
     * Whether a recording's stream may hold {@code type}: array classes by their element class, primitive ones always, then
     * the {@link #ALLOWED} names, RealmShark's packet classes ({@code packets.Packet} subclasses in {@code packets.incoming}
     * and {@code packets.outgoing}) and packet data ({@code packets.data} and its enums).
     */
    static boolean allowed(Class<?> type) {
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive()) return true;
        String name = type.getName();
        if (ALLOWED.contains(name)) return true;
        if (name.startsWith("packets.incoming.") || name.startsWith("packets.outgoing.")) return Packet.class.isAssignableFrom(type);
        return name.startsWith("packets.data.");
    }

    /**
     * One read's filter: {@link #allowed} classes, and no array longer than the file can hold (an element takes at least one
     * byte; a {@code HashMap} table is at most eight slots per entry of two bytes or more), so a small file cannot make the
     * reader allocate a huge array. No depth limit: real Realm recordings are deep, and the reader's stack holds them. Records
     * the first rejection for the reader's message.
     */
    static final class Filter implements ObjectInputFilter {
        private final long maxArray;
        volatile String rejected, limit;

        Filter(long fileBytes) { maxArray = fileBytes >= MAX_ARRAY ? MAX_ARRAY : Math.min(MAX_ARRAY, 8 * fileBytes + 64); }

        @Override public Status checkInput(FilterInfo info) {
            Class<?> type = info.serialClass();
            if (type != null && !allowed(type)) {
                if (rejected == null) rejected = type.getTypeName();
                return Status.REJECTED;
            }
            if (info.arrayLength() > maxArray) {
                if (limit == null) limit = "it declares an array of " + info.arrayLength() + " elements, more than the file can hold";
                return Status.REJECTED;
            }
            return type == null ? Status.UNDECIDED : Status.ALLOWED;   // a back-reference carries no class: only limits apply
        }
    }
}
