package tomato.gui.dps;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPInputStream;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.Packet;
import packets.PacketType;
import packets.data.*;
import packets.data.enums.*;
import packets.incoming.*;
import packets.outgoing.*;
import tomato.backend.data.*;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/**
 * Every {@code .dps} read goes through one allow-list {@link ObjectInputFilter} on a deep-stack reader thread
 * ({@link EncounterImport}). The allow-list is proven from real recordings, not guessed: the classes the streams of realistic
 * recordings resolve (with and without the debug packet log, the {@code getSaveFile(false)} copy autosave writes, and the
 * v1.2.3 baseline) are exactly its named entries plus RealmShark's packet data, and no admitted class has a deserialization
 * hook besides {@code DpsData.readObject}. Synthetic names only.
 */
public class EncounterImportFilterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long T = 1_700_000_000_000L;
    private static final VisitRef VISIT = new VisitRef("11111111-2222-3333-4444-555555555555", "journal:7");

    @After public void resetHook() { EncounterImport.beforeRead = () -> { }; }

    /** One class the filter was asked about, as a pattern filter names it: the class, and whether an array length came with it. */
    private record Seen(String name, boolean withLength) {}

    @Test public void theAllowListIsExactlyWhatRealRecordingsResolve() throws Exception {
        DpsData debug = realistic(12, 40, 30, true);
        Map<String, byte[]> streams = new LinkedHashMap<>();
        streams.put("with debug packets", bytes(debug));
        streams.put("getSaveFile(false), as autosave writes", bytes(debug.getSaveFile(false)));
        streams.put("getSaveFile(true), a Save Debug Data export", bytes(debug.getSaveFile(true)));
        streams.put("v1.2.3 baseline", baseline());
        Set<Seen> seen = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> stream : streams.entrySet()) {
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(stream.getValue()))) {
                input.setObjectInputFilter(info -> {
                    if (info.serialClass() != null) seen.add(new Seen(info.serialClass().getName(), info.arrayLength() >= 0));
                    return ObjectInputFilter.Status.UNDECIDED;
                });
                assertTrue(stream.getKey(), input.readObject() instanceof DpsData);
            }
        }
        Set<String> elements = new TreeSet<>();
        for (Seen value : seen) {
            Class<?> type = Class.forName(value.name(), false, getClass().getClassLoader());
            assertTrue("Resolved by a real recording but not allowed: " + value.name(), EncounterImport.allowed(type));
            while (type.isArray()) type = type.getComponentType();
            if (!type.isPrimitive()) elements.add(type.getName());
        }
        // How the filter reports arrays and primitives: an array class with its length (and its descriptor without one);
        // HashMap and ArrayList check the arrays they allocate (Map$Entry[] and Object[]) before reading their entries, so
        // Map$Entry and Object are allowed as element types though no such object is ever read; strings and primitive fields
        // are never reported, only String[] and primitive arrays.
        assertTrue(seen.contains(new Seen("[Ljava.util.Map$Entry;", true)));
        assertTrue(seen.contains(new Seen("[Ljava.lang.Object;", true)));
        assertTrue(seen.contains(new Seen("[I", true)) && seen.contains(new Seen("[I", false)));
        assertTrue(seen.contains(new Seen("[Ljava.lang.String;", true)));
        assertFalse("Strings are not reported themselves", seen.contains(new Seen("java.lang.String", false)));
        assertFalse("Object and Map$Entry never appear as objects", seen.contains(new Seen("java.lang.Object", false))
            || seen.contains(new Seen("java.util.Map$Entry", false)));
        Set<String> missing = new TreeSet<>(EncounterImport.ALLOWED); missing.removeAll(elements);
        assertEquals("Every named entry is resolved by a real recording (none is guessed)", Set.of(), missing);
        for (String element : elements) assertTrue("Named or packet data: " + element, EncounterImport.ALLOWED.contains(element)
            || element.startsWith("packets.incoming.") || element.startsWith("packets.outgoing.") || element.startsWith("packets.data."));

        // The same bytes read through EncounterImport, and the facts survive.
        for (Map.Entry<String, byte[]> stream : streams.entrySet()) {
            Path file = temp.getRoot().toPath().resolve(stream.getKey().replaceAll("[^a-z0-9]", "_") + ".dps");
            Files.write(file, stream.getValue());
            EncounterImport read = EncounterImport.read(file);
            assertEquals(stream.getKey(), file.getFileName().toString(), read.fileName);
            assertEquals(EncounterCatalog.Kind.IMPORTED, read.kind); assertNull(read.session);
        }
        EncounterImport exported = EncounterImport.read(write("debug.dps", debug.getSaveFile(true)));
        assertEquals(debug.getRecordingId(), exported.data.getRecordingId());
        assertEquals(debug.debugPackets.size(), exported.data.debugPackets.size());
        assertEquals(debug.hitList.size(), exported.data.hitList.size());
        assertEquals(VISIT, exported.data.getEncounterContext().visit);
        EncounterImport old = EncounterImport.read(write("baseline.dps", baseline()));
        assertEquals("Baseline encounter", old.data.map.name); assertNull(old.data.getRecordingId());
    }

    @Test public void everyPacketTypeAndWhatItHoldsIsAllowedAndNoAdmittedClassHasAHook() {
        Set<Class<?>> reachable = new LinkedHashSet<>();
        Deque<Class<?>> work = new ArrayDeque<>(List.of(DpsData.class, Packet.class));
        for (PacketType type : PacketType.values()) work.add(type.getPacketClass());
        while (!work.isEmpty()) {
            Class<?> type = work.poll();
            while (type.isArray()) type = type.getComponentType();
            if (type.isPrimitive() || type.getName().startsWith("java.") || !reachable.add(type)) continue;
            for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
                work.add(level);
                for (Field field : level.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    work.add(field.getType());
                    if (field.getGenericType() instanceof ParameterizedType)
                        for (Type argument : ((ParameterizedType) field.getGenericType()).getActualTypeArguments())
                            if (argument instanceof Class) work.add((Class<?>) argument);
                }
            }
        }
        assertTrue("Every packet type is walked", reachable.size() > PacketType.values().length / 2);
        for (Class<?> type : reachable) {
            if (!Serializable.class.isAssignableFrom(type)) continue;   // never in a stream (packets.data.ItemBuyData, StatsStateData)
            assertTrue("A recording can hold " + type.getName(), EncounterImport.allowed(type));
            for (String hook : List.of("readObject", "readObjectNoData", "readResolve", "readExternal"))
                for (Method method : type.getDeclaredMethods())
                    if (method.getName().equals(hook) && !(type == DpsData.class && hook.equals("readObject")))
                        fail(type.getName() + " declares " + hook + ": review it before allowing it in recordings");
        }
        assertFalse("Only RealmShark's packets", EncounterImport.allowed(packets.packetcapture.register.Register.class));
        assertFalse("Only packets in the packet packages", EncounterImport.allowed(PacketType.class));
        assertFalse(EncounterImport.allowed(java.util.PriorityQueue.class));
        assertFalse(EncounterImport.allowed(java.util.TreeMap.class));
        assertFalse(EncounterImport.allowed(java.util.HashSet.class));
        assertFalse(EncounterImport.allowed(java.net.URL.class));
        assertFalse(EncounterImport.allowed(Foreign[].class));
        assertTrue(EncounterImport.allowed(byte[].class));
    }

    /**
     * The package rules admit classes no field reaches (and classes added later): every class of {@code packets.data},
     * {@code packets.incoming} and {@code packets.outgoing} (and their subpackages) on the classpath that the filter admits and
     * that is serializable must have no deserialization hook, so admitting it by package needs no further review.
     */
    @Test public void everyClassThePackageRulesAdmitHasNoDeserializationHook() throws Exception {
        ClassLoader loader = EncounterImport.class.getClassLoader();
        Set<String> names = new TreeSet<>();
        for (String folder : List.of("packets/data", "packets/incoming", "packets/outgoing")) names.addAll(classNames(loader, folder));
        assertTrue("A plausible scan: " + names.size() + " classes", names.size() > PacketType.values().length / 2);
        assertTrue(names.contains(MapInfoPacket.class.getName()) && names.contains(StatData.class.getName())
            && names.contains(StatType.class.getName()) && names.contains(EnemyHitPacket.class.getName()));
        List<String> hooked = new ArrayList<>(), unloadable = new ArrayList<>();
        int admitted = 0;
        for (String name : names) {
            Class<?> type;
            try { type = Class.forName(name, false, loader); }
            catch (ClassNotFoundException | LinkageError failure) { unloadable.add(name + " (" + failure + ")"); continue; }
            if (!EncounterImport.allowed(type) || !Serializable.class.isAssignableFrom(type)) continue;
            admitted++;
            if (Externalizable.class.isAssignableFrom(type)) hooked.add(name + " implements Externalizable");
            try {
                for (Method method : type.getDeclaredMethods())
                    if (List.of("readObject", "readObjectNoData", "readResolve", "readExternal").contains(method.getName()))
                        hooked.add(name + " declares " + method.getName());
            } catch (LinkageError failure) { unloadable.add(name + " (" + failure + ")"); }
        }
        assertEquals("Classes that could not be inspected", List.of(), unloadable);
        assertEquals("Admitted by package but with a deserialization hook: review before allowing them in recordings", List.of(), hooked);
        assertTrue("Packet classes are admitted and inspected: " + admitted, admitted > PacketType.values().length / 2);
        System.out.println("Package scan: " + names.size() + " classes in packets.data, packets.incoming and packets.outgoing; "
            + admitted + " admitted and serializable, none with a deserialization hook");
    }

    /** Binary names of every class under {@code folder} (a package path, with its subpackages) in the loader's directories and jars. */
    private static Set<String> classNames(ClassLoader loader, String folder) throws Exception {
        Set<String> names = new TreeSet<>();
        for (java.net.URL url : Collections.list(loader.getResources(folder))) {
            if ("file".equals(url.getProtocol())) {
                Path root = Paths.get(url.toURI());
                try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                    files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                        String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                        names.add((folder + "/" + relative.substring(0, relative.length() - ".class".length())).replace('/', '.'));
                    });
                }
            } else if ("jar".equals(url.getProtocol())) {
                java.net.JarURLConnection connection = (java.net.JarURLConnection) url.openConnection();
                connection.setUseCaches(false);
                try (java.util.jar.JarFile jar = connection.getJarFile()) {
                    for (java.util.jar.JarEntry entry : Collections.list(jar.entries())) {
                        String name = entry.getName();
                        if (name.startsWith(folder + "/") && name.endsWith(".class"))
                            names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                    }
                }
            } else fail("Cannot list " + url.getProtocol() + " resources for " + folder);
        }
        names.removeIf(name -> name.endsWith("package-info") || name.endsWith("module-info"));
        return names;
    }

    @Test public void aForeignClassIsRejectedByNameBeforeAnyOfItsCodeRuns() throws Exception {
        Foreign.ran.set(false);
        DpsData data = realistic(2, 3, 5, false);
        ForeignMap map = new ForeignMap(); map.name = map.displayName = "Synthetic"; data.map = map;
        assertRejected(write("subclass.dps", data), ForeignMap.class.getName());
        assertFalse("Rejected before its readObject ran", Foreign.ran.get());

        DpsData arrays = realistic(2, 3, 5, false);
        @SuppressWarnings({"unchecked", "rawtypes"}) Map<Object, Object> raw = (Map) arrays.hitList;
        raw.put(-1, new Foreign[]{new Foreign()});
        assertRejected(write("array.dps", arrays), Foreign.class.getName() + "[]");

        DpsData jdk = realistic(2, 3, 5, false);
        @SuppressWarnings({"unchecked", "rawtypes"}) Map<Object, Object> other = (Map) jdk.hitList;
        other.put(-2, new java.util.PriorityQueue<>(List.of(1, 2)));
        assertRejected(write("jdk.dps", jdk), "java.util.PriorityQueue");
        assertRejected(write("date.dps", new java.util.Date(T)), "java.util.Date");

        // A class RealmShark does not have (same name length, so the stream stays well formed): named as well.
        Path missing = write("missing.dps", realistic(2, 3, 5, false).getSaveFile(false));
        Files.write(missing, replace(Files.readAllBytes(missing), "packets.incoming.MapInfoPacket".getBytes("UTF-8"),
            "packets.incoming.MapInfoPackeQ".getBytes("UTF-8")));
        assertRejected(missing, "packets.incoming.MapInfoPackeQ");
        Files.write(temp.getRoot().toPath().resolve("text.dps"), "not a recording".getBytes("UTF-8"));
        try { EncounterImport.read(temp.getRoot().toPath().resolve("text.dps")); fail("Not a Java stream"); }
        catch (IOException expected) { assertFalse(expected.getMessage(), expected.getMessage().contains(temp.getRoot().toString())); }
        try { EncounterImport.read(write("map.dps", new HashMap<>(Map.of(1, 2)))); fail("Allowed classes, but not a recording"); }
        catch (IOException expected) { assertEquals("This file is not a DPS encounter.", expected.getMessage()); }
    }

    @Test public void arraysLongerThanTheFileCanHoldAreRejectedBeforeAllocation() {
        EncounterImport.Filter filter = new EncounterImport.Filter(1_000);
        assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(info(int[].class, 8_064)));
        assertEquals(ObjectInputFilter.Status.REJECTED, filter.checkInput(info(int[].class, 8_065)));
        assertNotNull(filter.limit); assertNull(filter.rejected);
        EncounterImport.Filter huge = new EncounterImport.Filter(Long.MAX_VALUE / 16);
        assertEquals("A cap even for huge files", ObjectInputFilter.Status.REJECTED, huge.checkInput(info(Object[].class, Integer.MAX_VALUE)));
        EncounterImport.Filter refs = new EncounterImport.Filter(10);
        assertEquals("Back-references carry no class: only limits apply", ObjectInputFilter.Status.UNDECIDED, refs.checkInput(info(null, -1)));
        assertEquals(ObjectInputFilter.Status.REJECTED, refs.checkInput(info(Foreign.class, -1)));
        assertEquals(Foreign.class.getName(), refs.rejected);
    }

    /**
     * A long Realm recording: players come and go, so thousands of distinct players and enemies hit each other. Serialization
     * recurses through {@code Damage.owner}: the default 1 MB thread stack overflows reading it; the reader thread does not.
     */
    @Test public void aDeepRealmGraphReadsWithoutStackOverflow() throws Exception {
        DpsData realm = longRealm(1_500, 20);
        Path file = temp.getRoot().toPath().resolve("realm.dps");
        long written = System.nanoTime();
        onThread("writer", 64L << 20, () -> { try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) { out.writeObject(realm); } return null; });
        long writeMillis = (System.nanoTime() - written) / 1_000_000;
        List<String> threads = new CopyOnWriteArrayList<>();
        EncounterImport.beforeRead = () -> threads.add(Thread.currentThread().getName() + (Thread.currentThread().isDaemon() ? " (daemon)" : ""));
        long started = System.nanoTime();
        EncounterImport read = EncounterImport.read(file);
        long readMillis = (System.nanoTime() - started) / 1_000_000;
        // Then (every class already initialized by the read above) the same bytes on a default-size thread stack overflow.
        AtomicLong depth = new AtomicLong();
        Throwable plain = onThreadFailure("default stack", 1L << 20, () -> {
            try (ObjectInputStream input = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                input.setObjectInputFilter(info -> { depth.accumulateAndGet(info.depth(), Math::max); return ObjectInputFilter.Status.UNDECIDED; });
                return input.readObject();
            }
        });
        assertTrue("The graph is deeper than a default thread stack: " + plain, plain instanceof StackOverflowError);
        System.out.println("Deep Realm recording (" + realm.hitList.size() + " enemies, 1,500 players, " + Files.size(file) + " B): a 1 MB stack"
            + " overflowed at depth " + depth.get() + "; written in " + writeMillis + " ms, read on the reader thread in " + readMillis + " ms");
        assertEquals(List.of(EncounterImport.THREAD + " (daemon)"), threads);
        assertEquals(realm.hitList.size(), read.data.hitList.size());
        assertEquals(realm.getRecordingId(), read.data.getRecordingId());
        long hits = 0; for (Entity enemy : read.data.hitList.values()) hits += enemy.getDamageList().size();
        assertEquals(1_500L * 21, hits);
    }

    @Test public void theEventDispatchThreadIsRefused() throws Exception {
        Path file = write("edt.dps", realistic(1, 1, 1, false));
        AtomicReference<Throwable> read = new AtomicReference<>(), saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { EncounterImport.read(file); } catch (Throwable t) { read.set(t); }
            try { EncounterImport.readSaved(file); } catch (Throwable t) { saved.set(t); }
        });
        assertTrue(String.valueOf(read.get()), read.get() instanceof IllegalStateException);
        assertTrue(String.valueOf(saved.get()), saved.get() instanceof IllegalStateException);
    }

    @Test public void savedFullDetailKeepsItsKindAndSessionAndImportsKeepTheirs() throws Exception {
        DpsData data = realistic(2, 3, 5, false);
        String session = UUID.randomUUID().toString();
        Path file = CombatAutosave.fullDetailFile(temp.getRoot().toPath().resolve(session), data.getRecordingId());
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(data.getSaveFile(false)); }
        EncounterImport saved = EncounterImport.readSaved(file);
        assertEquals(EncounterCatalog.Kind.SAVED, saved.kind); assertEquals(session, saved.session);
        assertEquals(data.getRecordingId(), saved.data.getRecordingId());
        assertEquals(SessionStore.checkpointName(data.getRecordingId()) + ".dps", saved.fileName);
        EncounterImport imported = EncounterImport.read(file);
        assertEquals(EncounterCatalog.Kind.IMPORTED, imported.kind); assertNull(imported.session);
        assertEquals(saved.fingerprint, imported.fingerprint);
        Path loose = write("loose.dps", data.getSaveFile(false));
        assertNull("Only a combat-full folder names a session", EncounterImport.readSaved(loose).session);
    }

    @Test public void anInterruptedCallerStopsWaitingAndInterruptsTheReader() throws Exception {
        Path file = write("slow.dps", realistic(2, 3, 5, false));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean readerInterrupted = new AtomicBoolean();
        EncounterImport.beforeRead = () -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { readerInterrupted.set(true); Thread.currentThread().interrupt(); }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> { try { EncounterImport.read(file); } catch (Throwable t) { failure.set(t); } }, "synthetic caller");
        caller.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        caller.interrupt(); caller.join(10_000);
        assertFalse(caller.isAlive());
        assertTrue(String.valueOf(failure.get()), failure.get() instanceof InterruptedIOException);
        for (int i = 0; i < 100 && !readerInterrupted.get(); i++) Thread.sleep(20);
        assertTrue("The reader was interrupted too", readerInterrupted.get());
        release.countDown();
    }

    // ---- fixtures ----

    /** A recording of plain entities with every field capture fills (stats, positions, drops, hits taken) and, when {@code debug}, a log of every logged packet type. */
    static DpsData realistic(int players, int enemies, int seconds, boolean debug) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Synthetic Realm"; map.realmName = "Synthetic";
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < players; p++) {
            Entity player = new Entity(null, 1 + p, T); player.objectType = 768 + p % 8; player.markPlayerIdentity();
            player.stat.set(StatType.NAME_STAT, stat(StatType.NAME_STAT, 0, "Player" + p));
            player.stat.set(StatType.GUILD_NAME_STAT, stat(StatType.GUILD_NAME_STAT, 0, "Synthetic guild"));
            player.stat.set(StatType.MAX_HP_STAT, stat(StatType.MAX_HP_STAT, 700, null));
            ObjectStatusData status = new ObjectStatusData(); status.objectId = player.id; status.pos = pos(p, p);
            status.stats = new StatData[]{stat(StatType.HP_STAT, 700, null)};
            player.statUpdates.add(status); player.pos = status.pos; player.baseStats = new int[]{700, 100, 50};
            player.playerDropped.put(p, new PlayerRemoved(p, 600, 700, "Player" + p, T));
            if (p == 0) player.setUser(7);
            party.add(player);
        }
        HashMap<Integer, Entity> hits = new HashMap<>();
        List<Entity> foes = new ArrayList<>();
        for (int e = 0; e < enemies; e++) {
            Entity enemy = new Entity(null, 10_000 + e, T); enemy.objectType = 5000 + e % 7;
            enemy.stat.set(StatType.MAX_HP_STAT, stat(StatType.MAX_HP_STAT, 100_000, null));
            hits.put(enemy.id, enemy); foes.add(enemy);
        }
        DamageSource[] sources = DamageSource.values();
        for (int s = 0; s < seconds; s++) for (int p = 0; p < players; p++) {
            Entity foe = foes.get((s * players + p) % foes.size());
            Projectile projectile = new Projectile(100 + p); projectile.setSource(sources[(s + p) % sources.length], 10_000 + p);
            foe.genericDamageHit(party.get(p), projectile, T + s * 1000L + p);
            foe.updateDamageTaken(T + s * 1000L + p);
            if (s % 5 == 0) party.get(p).getDamageList().add(new Damage(foes.get((s + p * 7) % foes.size()), T + s * 1000L, 50));
        }
        for (Entity foe : foes) if (!foe.getDamageList().isEmpty()) foe.playerHits.put(foe.getDamageList().get(0).time, foe.getDamageList().get(0));
        ArrayList<NotificationPacket> deaths = new ArrayList<>();
        NotificationPacket death = new NotificationPacket(); death.effect = NotificationEffectType.PlayerDeath;
        death.message = "{\"k\":\"s.death\",\"t\":{\"player\":\"Player1\",\"level\":\"20\"}}"; deaths.add(death);
        ArrayList<Packet> log = null;
        if (debug) {   // TomatoPacketCapture's logged types
            log = new ArrayList<>();
            ObjectStatusData status = new ObjectStatusData(); status.pos = pos(1, 2); status.stats = new StatData[]{stat(StatType.HP_STAT, 5, null)};
            NewTickPacket tick = new NewTickPacket(); tick.status = new ObjectStatusData[]{status}; log.add(tick);
            UpdatePacket update = new UpdatePacket(); update.pos = pos(3, 4); update.tiles = new GroundTileData[]{new GroundTileData()};
            ObjectData object = new ObjectData(); object.objectType = 768; object.status = status;
            update.newObjects = new ObjectData[]{object}; update.drops = new int[]{1}; log.add(update);
            PlayerShootPacket shoot = new PlayerShootPacket(); shoot.startingPos = pos(1, 1); shoot.playerPosition = pos(2, 2); log.add(shoot);
            ServerPlayerShootPacket serverShoot = new ServerPlayerShootPacket(); serverShoot.startingPos = pos(1, 1); log.add(serverShoot);
            log.add(new EnemyHitPacket());
            DamagePacket damage = new DamagePacket(); damage.effects = new int[]{1}; log.add(damage);
            TextPacket text = new TextPacket(); text.name = "Synthetic"; text.text = "synthetic line"; log.add(text);
            log.add(map);
            CreateSuccessPacket created = new CreateSuccessPacket(); created.str = "synthetic"; log.add(created);
        }
        return new DpsData(map, hits, deaths, seconds * 1000L, T, log, party.get(0), new EncounterContext(VISIT, 1, T - 5_000));
    }

    /**
     * {@code players} distinct players and as many enemies: enemy i is first hit by player i, and player i is first hit by
     * enemy i + 1, so reading enemy 0 recurses through every one of them; each player also hits {@code extra} other enemies.
     */
    static DpsData longRealm(int players, int extra) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Synthetic Realm";
        List<Entity> party = new ArrayList<>(), foes = new ArrayList<>();
        HashMap<Integer, Entity> hits = new HashMap<>();
        for (int i = 0; i < players; i++) {
            Entity enemy = new Entity(null, i, T); enemy.objectType = 5000 + i % 50; hits.put(enemy.id, enemy); foes.add(enemy);
            Entity player = new Entity(null, 100_000 + i, T); player.objectType = 768 + i % 8; player.markPlayerIdentity();
            player.stat.set(StatType.NAME_STAT, stat(StatType.NAME_STAT, 0, "Player" + i)); party.add(player);
        }
        for (int i = 0; i < players; i++) {
            foes.get(i).genericDamageHit(party.get(i), new Projectile(100), T + i);
            if (i + 1 < players) party.get(i).getDamageList().add(new Damage(foes.get(i + 1), T + i, 40));
        }
        for (int i = 0; i < players; i++) for (int k = 1; k <= extra; k++) foes.get((i + k * 37) % players).genericDamageHit(party.get(i), new Projectile(50 + k), T + 1_000 + i * 10L + k);
        return new DpsData(map, hits, new ArrayList<>(), 3_600_000L, T, null, party.get(0), new EncounterContext(null, null, T - 5_000));
    }

    static StatData stat(StatType type, int value, String text) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; stat.stringStatValue = text; return stat;
    }
    static WorldPosData pos(float x, float y) { WorldPosData pos = new WorldPosData(); pos.x = x; pos.y = y; return pos; }

    private Path write(String name, Object value) throws IOException {
        Path file = temp.getRoot().toPath().resolve(name);
        if (value instanceof byte[]) { Files.write(file, (byte[]) value); return file; }
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(value); }
        return file;
    }
    private static byte[] bytes(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(value); }
        return bytes.toByteArray();
    }
    /** {@code DpsDataTest}'s v1.2.3 baseline stream (one source of truth for the old format). */
    static byte[] baseline() throws Exception {
        Field field = tomato.backend.data.DpsDataTest.class.getDeclaredField("BASELINE_GZIP"); field.setAccessible(true);
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode((String) field.get(null))))) {
            return input.readAllBytes();
        }
    }
    private static byte[] replace(byte[] bytes, byte[] from, byte[] to) {
        outer:
        for (int i = 0; i + from.length <= bytes.length; i++) {
            for (int j = 0; j < from.length; j++) if (bytes[i + j] != from[j]) continue outer;
            System.arraycopy(to, 0, bytes, i, to.length);
        }
        return bytes;
    }
    private static void assertRejected(Path file, String name) {
        try { EncounterImport.read(file); fail("Read " + name); }
        catch (IOException expected) { assertEquals("This file contains data RealmShark does not read: " + name, expected.getMessage()); }
    }
    private static ObjectInputFilter.FilterInfo info(Class<?> type, long length) {
        return new ObjectInputFilter.FilterInfo() {
            public Class<?> serialClass() { return type; }
            public long arrayLength() { return length; }
            public long depth() { return 1; }
            public long references() { return 1; }
            public long streamBytes() { return 1; }
        };
    }
    private static Object onThread(String name, long stack, Callable<Object> work) throws Exception {
        FutureTask<Object> task = new FutureTask<>(work);
        Thread thread = new Thread(null, task, name, stack); thread.start();
        try { return task.get(60, TimeUnit.SECONDS); } catch (ExecutionException e) { throw (Exception) e.getCause(); }
    }
    private static Throwable onThreadFailure(String name, long stack, Callable<Object> work) throws Exception {
        FutureTask<Object> task = new FutureTask<>(work);
        Thread thread = new Thread(null, task, name, stack); thread.start();
        try { task.get(60, TimeUnit.SECONDS); return null; } catch (ExecutionException e) { return e.getCause(); }
    }

    /** Test-only serializable class: outside RealmShark's recording graph. */
    static final class Foreign implements Serializable {
        static final AtomicBoolean ran = new AtomicBoolean();
        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException { ran.set(true); input.defaultReadObject(); }
    }
    /** A packet subclass outside the packet packages (as {@code DungeonListTest}'s blocking map was). */
    static final class ForeignMap extends MapInfoPacket {
        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException { Foreign.ran.set(true); input.defaultReadObject(); }
    }
}
