package tomato.history.index;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

public class HistoryIndexTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path root, file;
    private SessionStore store;
    private final List<HistoryIndex> opened=new ArrayList<>();
    private String previousNative;
    private static final String A="00000000-0000-0000-0000-000000000001", B="00000000-0000-0000-0000-000000000002";
    private static final String HASH="a".repeat(64);
    @Before public void setup() throws Exception {
        previousNative=System.getProperty("realmshark.indexNativeDir");
        Assume.assumeTrue("Only Windows natives ship",System.getProperty("os.name").startsWith("Windows"));
        root=temp.newFolder("history").toPath(); file=temp.getRoot().toPath().resolve("index/search-v1.db");
        System.setProperty("realmshark.indexNativeDir",temp.getRoot().toPath().resolve("native").toString());
        store=new SessionStore(root,false,"test");
    }
    @After public void cleanup() throws Exception {
        for (HistoryIndex index:opened) index.closeAsync().get(30,TimeUnit.SECONDS);
        if (store!=null) store.close();
        if (previousNative==null) System.clearProperty("realmshark.indexNativeDir"); else System.setProperty("realmshark.indexNativeDir",previousNative);
    }
    private HistoryIndex index(boolean chat) throws Exception {
        HistoryIndex index=new HistoryIndex(store,file,chat); opened.add(index); ready(index); return index;
    }
    private static void ready(HistoryIndex index) throws Exception {
        HistoryIndex.State state=index.start().get(30,TimeUnit.SECONDS); assertEquals(state.reason(),HistoryIndex.Phase.READY,state.phase());
    }
    private static void flush(HistoryIndex index) throws Exception { index.flush().get(30,TimeUnit.SECONDS); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue("Background index work did not complete",condition.getAsBoolean());
    }
    private void session(String id,long started,long ended) throws Exception {
        Files.createDirectories(root.resolve(id));
        Files.writeString(root.resolve(id).resolve("session.json"),"{schemaVersion:1,id:'"+id+"',label:'Synthetic',version:'test',started:"+started+",ended:"+ended+"}");
    }
    private void checkpoint(String id,String module,String key,String json) throws Exception {
        Path folder=root.resolve(id).resolve(module); Files.createDirectories(folder);
        Files.writeString(folder.resolve(SessionStore.checkpointName(key)+".json"),json,StandardCharsets.UTF_8);
    }
    private void journal(String id,String module,String json) throws Exception { Files.writeString(root.resolve(id).resolve(module+".jsonl"),json,StandardCharsets.UTF_8); }
    private static long count(HistoryIndex index,String table) throws Exception { return index.rowCounts().get(table); }
    private static String scalar(HistoryIndex index,String sql) throws Exception {
        try (Connection c=index.readConnection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) { return r.next()?r.getString(1):null; }
    }
    private void writeSql(String sql) throws Exception {
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+file); Statement s=c.createStatement()) { s.execute(sql); }
    }
    private static void nativeLoad(Path directory) throws Exception {
        Files.createDirectories(directory); System.setProperty("org.sqlite.tmpdir",directory.toString()); Class.forName("org.sqlite.JDBC");
        try (Connection ignored=DriverManager.getConnection("jdbc:sqlite::memory:")) { }
    }
    private HistoryIndex index(AtomicReference<IndexDictionary> dictionary) throws Exception {
        HistoryIndex index=new HistoryIndex(store,file,false,8192,HistoryIndexTest::nativeLoad,id -> {},System::nanoTime,() -> {
            assertEquals("RealmShark search index",Thread.currentThread().getName());
            return dictionary.get();
        });
        opened.add(index); ready(index); return index;
    }
    @Test public void failingDictionaryDoesNotBlockIndexingAndLogsOnlyOnce() throws Exception {
        session(A,1,10); journal(A,"loot","{items:[{id:101,name:'Saved Blade'}]}\n");
        AtomicBoolean fail=new AtomicBoolean(true);
        HistoryIndex index=new HistoryIndex(store,file,false,8192,HistoryIndexTest::nativeLoad,id -> {},System::nanoTime,() -> {
            if (fail.get()) throw new IllegalStateException("Synthetic snapshot failure");
            return DictionaryProjectionsTest.dictionary("v1","Blessing");
        });
        opened.add(index);
        java.io.PrintStream previous=System.err;
        java.io.ByteArrayOutputStream errors=new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream captured=new java.io.PrintStream(errors,true,StandardCharsets.UTF_8)) {
            System.setErr(captured);
            ready(index); assertTrue(index.ready(A)); assertEquals("none",scalar(index,"SELECT dictionary FROM sessions"));
            index.dictionaryChanged(); flush(index);
            assertEquals(HistoryIndex.Phase.READY,index.state().phase());
            assertEquals("1",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'title:Saved'"));
            assertEquals("0",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'Moonblade'"));
            fail.set(false); index.dictionaryChanged(); flush(index);
            assertEquals("v1",scalar(index,"SELECT dictionary FROM sessions"));
            fail.set(true); index.dictionaryChanged(); flush(index);
            assertEquals(HistoryIndex.Phase.READY,index.state().phase());
            assertEquals(1,errors.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.equals("History index dictionary snapshot failed; enrichment unavailable.")).count());
        } finally { System.setErr(previous); }
    }
    @Test public void equipmentSlotAndLootTitlesRemainSearchableWithoutDuplicateBodyNames() throws Exception {
        session(A,1,10);
        journal(A,"timeline","{kind:'Equipment changed',values:{slot:2,before:101,after:102}}\n");
        journal(A,"loot","{items:[{id:101},{id:102,name:'Saved Shield'}]}\n");
        HistoryIndex index=index(new AtomicReference<>(DictionaryProjectionsTest.dictionary("v1","Blessing")));
        assertEquals("Armor: Moonblade → Sunshield",scalar(index,"SELECT summary FROM timeline"));
        assertEquals("1",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'Armor'"));
        for (String name:List.of("Moonblade","Saved")) {
            assertEquals("1",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'title:"+name+"'"));
        }
        assertEquals("0",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'body:Saved'"));
        assertEquals("1",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'body:Moonblade'"));
    }
    @Test public void dictionaryReadinessChangeAndReopenReprojectOnlyWhenVersionChanges() throws Exception {
        session(A,1,10);
        journal(A,"loot","{items:[{id:101,name:'Saved Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n");
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(DictionaryProjectionsTest.dictionary("none","Astral Blessing"));
        HistoryIndex first=index(dictionary);
        assertNull(scalar(first,"SELECT value FROM meta WHERE key='dictionary_version'"));
        assertEquals("none",scalar(first,"SELECT dictionary FROM sessions"));
        assertTrue(first.search("Astral",Set.of(Kind.LOOT),10).isEmpty());
        dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        first.dictionaryChanged(); flush(first);
        assertEquals("v1",scalar(first,"SELECT value FROM meta WHERE key='dictionary_version'"));
        assertEquals(2,first.replacementCount()); assertTrue(first.ready(A));
        assertEquals(1,first.search("Astral",Set.of(Kind.LOOT),10).size());
        assertEquals(1,first.search("tral Bles",Set.of(Kind.LOOT),10).size());
        first.dictionaryChanged(); flush(first); assertEquals(2,first.replacementCount());
        first.closeAsync().get(30,TimeUnit.SECONDS);
        HistoryIndex same=index(dictionary); assertEquals(0,same.replacementCount());
        assertEquals(1,same.search("Astral",Set.of(Kind.LOOT),10).size());
        same.closeAsync().get(30,TimeUnit.SECONDS);
        dictionary.set(DictionaryProjectionsTest.dictionary("v2","Solar Blessing"));
        HistoryIndex changed=index(dictionary); assertEquals(1,changed.replacementCount());
        assertTrue(changed.search("Astral",Set.of(Kind.LOOT),10).isEmpty());
        assertEquals(1,changed.search("Solar",Set.of(Kind.LOOT),10).size());
        dictionary.set(IndexDictionary.NONE); changed.dictionaryChanged(); flush(changed);
        assertEquals(1,changed.replacementCount()); assertEquals(1,changed.search("Solar",Set.of(Kind.LOOT),10).size());
        assertEquals("v2",scalar(changed,"SELECT value FROM meta WHERE key='dictionary_version'"));
        assertEquals("v2",scalar(changed,"SELECT dictionary FROM sessions"));
        assertEquals("Saved Blade",changed.search("Saved Blade",Set.of(Kind.LOOT),10).get(0).title());
    }
    @Test public void unavailableAssetsOnReopenPreserveEnrichmentAndSameVersionNeedsNoRefresh() throws Exception {
        session(A,1,10); journal(A,"loot","{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n");
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        HistoryIndex first=index(dictionary);
        String stamp=scalar(first,"SELECT stamp FROM sessions"), row=scalar(first,"SELECT id FROM loot_items");
        first.closeAsync().get(30,TimeUnit.SECONDS);
        dictionary.set(IndexDictionary.NONE);
        HistoryIndex reopened=index(dictionary); flush(reopened);
        assertEquals(0,reopened.replacementCount()); assertTrue(reopened.ready(A));
        assertEquals("v1",scalar(reopened,"SELECT value FROM meta WHERE key='dictionary_version'"));
        assertEquals("v1",scalar(reopened,"SELECT dictionary FROM sessions"));
        assertEquals(stamp,scalar(reopened,"SELECT stamp FROM sessions")); assertEquals(row,scalar(reopened,"SELECT id FROM loot_items"));
        assertEquals(1,reopened.search("Astral",Set.of(Kind.LOOT),10).size());
        dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        reopened.dictionaryChanged(); flush(reopened);
        assertEquals(0,reopened.replacementCount()); assertEquals(stamp,scalar(reopened,"SELECT stamp FROM sessions"));
        assertEquals(1,reopened.search("tral Bles",Set.of(Kind.LOOT),10).size());
    }
    @Test public void returningAssetsRefreshOnlyUnenrichedSessionsAndNewVersionsRefreshEachOutdatedSessionOnce() throws Exception {
        String loot="{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n";
        session(A,1,10); journal(A,"loot",loot);
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        HistoryIndex first=index(dictionary); first.closeAsync().get(30,TimeUnit.SECONDS);
        session(B,20,30); journal(B,"loot",loot);
        dictionary.set(IndexDictionary.NONE);
        HistoryIndex reopened=index(dictionary);
        assertEquals(1,reopened.replacementCount()); assertEquals(1,reopened.search("Astral",Set.of(Kind.LOOT),10).size());
        assertEquals("v1",scalar(reopened,"SELECT dictionary FROM sessions WHERE id='"+A+"'"));
        assertEquals("none",scalar(reopened,"SELECT dictionary FROM sessions WHERE id='"+B+"'"));
        dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        reopened.dictionaryChanged(); flush(reopened);
        assertEquals(2,reopened.replacementCount()); assertEquals(2,reopened.search("Astral",Set.of(Kind.LOOT),10).size());
        assertEquals("2",scalar(reopened,"SELECT count(*) FROM sessions WHERE dictionary='v1'"));
        reopened.dictionaryChanged(); flush(reopened); assertEquals(2,reopened.replacementCount());
        dictionary.set(DictionaryProjectionsTest.dictionary("v2","Solar Blessing"));
        reopened.dictionaryChanged(); flush(reopened);
        assertEquals(4,reopened.replacementCount()); assertEquals(2,reopened.search("Solar",Set.of(Kind.LOOT),10).size());
        assertTrue(reopened.search("Astral",Set.of(Kind.LOOT),10).isEmpty());
        assertEquals("2",scalar(reopened,"SELECT count(*) FROM sessions WHERE dictionary='v2'"));
        reopened.dictionaryChanged(); flush(reopened); assertEquals(4,reopened.replacementCount());
        reopened.closeAsync().get(30,TimeUnit.SECONDS);
        HistoryIndex same=index(dictionary); assertEquals(0,same.replacementCount());
    }
    @Test public void sourceChangesWhileAssetsAreMissingRecordNoneWithoutInvalidatingOtherSessions() throws Exception {
        String loot="{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n";
        session(A,1,10); journal(A,"loot",loot); session(B,20,30); journal(B,"loot",loot);
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        HistoryIndex first=index(dictionary); first.closeAsync().get(30,TimeUnit.SECONDS);
        journal(A,"loot",loot+"{items:[{name:'New item'}]}\n"); dictionary.set(IndexDictionary.NONE);
        HistoryIndex reopened=index(dictionary);
        assertEquals(1,reopened.replacementCount()); assertEquals(3,count(reopened,"loot_items"));
        assertEquals("none",scalar(reopened,"SELECT dictionary FROM sessions WHERE id='"+A+"'"));
        assertEquals("v1",scalar(reopened,"SELECT dictionary FROM sessions WHERE id='"+B+"'"));
        assertEquals("v1",scalar(reopened,"SELECT value FROM meta WHERE key='dictionary_version'"));
        assertEquals(1,reopened.search("Astral",Set.of(Kind.LOOT),10).size());
        dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        reopened.dictionaryChanged(); flush(reopened);
        assertEquals(2,reopened.replacementCount()); assertEquals(2,reopened.search("Astral",Set.of(Kind.LOOT),10).size());
    }
    @Test public void dictionaryChangesRefreshIndexedOpenSessionsAndLiveOffers() throws Exception {
        session(A,1,0); checkpoint(A,"runs","r","{id:'r',requestedItems:{101:1}}");
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(IndexDictionary.NONE);
        HistoryIndex index=index(dictionary); index.markSessionChanged(A); flush(index);
        assertTrue(index.ready(A)); assertTrue(index.search("Moonblade",Set.of(Kind.RUN),10).isEmpty());
        dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        index.dictionaryChanged(); flush(index);
        assertEquals(2,index.replacementCount()); assertEquals(1,index.search("Moonblade",Set.of(Kind.RUN),10).size());
        String loot="{items:[{name:'Live Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n";
        journal(A,"loot",loot); index.offer(A,"loot",0,null,ProjectionsTest.json(loot)); flush(index);
        assertEquals(1,index.search("Astral",Set.of(Kind.LOOT),10).size());
        index.dictionaryChanged(); flush(index); assertEquals(2,index.replacementCount());
        dictionary.set(IndexDictionary.NONE); index.dictionaryChanged(); flush(index);
        assertEquals(2,index.replacementCount()); assertEquals("v1",scalar(index,"SELECT dictionary FROM sessions"));
        index.offer(A,"loot",0,null,ProjectionsTest.json(loot)); flush(index);
        assertEquals("none",scalar(index,"SELECT dictionary FROM sessions"));
        assertTrue(index.search("Astral",Set.of(Kind.LOOT),10).isEmpty());
        assertEquals(1,index.search("Moonblade",Set.of(Kind.RUN),10).size());
        dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        index.dictionaryChanged(); flush(index);
        assertEquals(3,index.replacementCount()); assertEquals("v1",scalar(index,"SELECT dictionary FROM sessions"));
        assertEquals(1,index.search("Astral",Set.of(Kind.LOOT),10).size());
        index.dictionaryChanged(); flush(index); assertEquals(3,index.replacementCount());
    }
    @Test public void enrichedNamesAreInBothIndexesAndCannotExposeTheAccountHash() throws Exception {
        session(A,1,10);
        checkpoint(A,"runs","r","{id:'r',requestedItems:{103:1},inspectedPlayers:{'player:alice':{objectType:301,stats:[{statTypeNum:8,statValue:101}]}}}");
        checkpoint(A,"encounters","e","{recordingId:'e',players:[{name:'Alice',classType:301}],bosses:[{type:201}]}");
        checkpoint(A,"dungeon-totals","d","[{hits:{201:1},loot:{201:{102:1}}}]");
        journal(A,"timeline","{kind:'Equipment changed',values:{before:101,after:102}}\n");
        journal(A,"loot","{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n");
        journal(A,"fame","{account:'"+HASH+"',character:1,className:'Saved Mage'}\n");
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(DictionaryProjectionsTest.dictionary("v1",HASH));
        HistoryIndex index=index(dictionary);
        for (String fts:List.of("docs","names")) {
            for (String name:List.of("Moonblade","Starcloak","Spellweaver","Ancient Guardian","Sunshield"))
                assertNotEquals(name,"0",scalar(index,"SELECT count(*) FROM "+fts+" WHERE "+fts+" MATCH '\""+name+"\"'"));
        }
        assertEquals(HASH,scalar(index,"SELECT account FROM fame")); scanAccountLeak(index);
    }
    @Test public void interruptedDictionaryRefreshRemainsStaleAcrossReopen() throws Exception {
        session(A,1,10); journal(A,"loot","{items:[{name:'Blade',enchantEvidence:{orderedSlotIds:[401]}}]}\n");
        AtomicReference<IndexDictionary> dictionary=new AtomicReference<>(IndexDictionary.NONE);
        AtomicBoolean fail=new AtomicBoolean();
        HistoryIndex first=new HistoryIndex(store,file,false,8192,HistoryIndexTest::nativeLoad,id -> {
            if (fail.get()) throw new java.io.IOException("Synthetic interruption");
        },System::nanoTime,dictionary::get);
        opened.add(first); ready(first);
        fail.set(true); dictionary.set(DictionaryProjectionsTest.dictionary("v1","Astral Blessing"));
        first.dictionaryChanged(); flush(first);
        assertFalse(first.ready(A)); assertEquals("v1",scalar(first,"SELECT value FROM meta WHERE key='dictionary_version'"));
        assertTrue(first.search("Astral",Set.of(Kind.LOOT),10).isEmpty());
        first.closeAsync().get(30,TimeUnit.SECONDS);
        HistoryIndex recovered=index(dictionary); assertEquals(1,recovered.replacementCount()); assertTrue(recovered.ready(A));
        assertEquals(1,recovered.search("Astral",Set.of(Kind.LOOT),10).size());
    }
    @Test public void schemaReopenAndVersionRebuild() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Oryx',started:2,lastSeen:8}");
        HistoryIndex first=index(true); assertEquals(1,count(first,"runs"));
        assertEquals(Integer.toString(IndexSchema.VERSION),scalar(first,"SELECT value FROM meta WHERE key='schema_version'"));
        assertEquals("wal",scalar(first,"PRAGMA journal_mode")); assertEquals("1",scalar(first,"PRAGMA secure_delete"));
        assertEquals("5000",scalar(first,"PRAGMA busy_timeout")); assertEquals("1",scalar(first,"PRAGMA foreign_keys"));
        try (Connection c=first.readConnection(); Statement s=c.createStatement()) {
            for (String table:IndexSchema.TABLES.keySet()) try (ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
                Set<String> columns=new HashSet<>(); boolean integerPrimaryKey=false;
                while(r.next()) { columns.add(r.getString("name")); if(r.getInt("pk")==1) integerPrimaryKey="INTEGER".equals(r.getString("type")); }
                assertTrue(table,integerPrimaryKey);
                if (IndexSchema.sources().contains(table)) {
                    assertTrue(table,columns.containsAll(Set.of("sid","vid","module","byte_offset","checkpoint_key","item_position")));
                    assertFalse(table,columns.contains("session")); assertFalse(table,columns.contains("visit_id"));
                }
            }
            try (ResultSet r=s.executeQuery("PRAGMA table_info(player_occurrences)")) {
                while (r.next()) assertEquals(r.getString("name"),"INTEGER",r.getString("type"));
            }
            try { s.executeUpdate("DELETE FROM runs"); fail("read connection accepted a write"); } catch (SQLException expected) { }
        }
        assertEquals("0",scalar(first,"SELECT count(*) FROM sqlite_master WHERE name IN ('doc_ref','docs_content','names_content')"));
        assertEquals("0",scalar(first,"SELECT count(title) FROM docs"));
        assertEquals("0",scalar(first,"SELECT count(*) FROM sqlite_master WHERE name LIKE 'sqlite_autoindex_runs_%'"));
        assertEquals("0",scalar(first,"SELECT count(*) FROM sqlite_master WHERE name IN ('chat_time','timeline_time','keypops_time','fame_time','combat_time','loot_bags_time')"));
        assertEquals("2",scalar(first,"SELECT count(*) FROM sqlite_master WHERE name IN ('runs_time','loot_items_time')"));
        assertEquals(1,first.compactionCount());
        first.closeAsync().get(30,TimeUnit.SECONDS);
        HistoryIndex second=index(true); assertEquals(0,second.replacementCount()); assertTrue(second.ready(A)); assertEquals(0,second.compactionCount());
        second.closeAsync().get(30,TimeUnit.SECONDS);
        writeSql("UPDATE meta SET value='999' WHERE key='schema_version'");
        HistoryIndex third=index(true); assertEquals(1,third.replacementCount()); assertEquals(1,count(third,"runs"));
        assertEquals("App ended",scalar(third,"SELECT outcome FROM runs"));
    }
    @Test public void corruptFileIsQuarantined() throws Exception {
        Files.createDirectories(file.getParent()); Files.writeString(file,"not a SQLite database");
        HistoryIndex index=index(true); assertEquals(0,count(index,"runs"));
        try (var files=Files.list(file.getParent())) {
            Path corrupt=files.filter(p->p.getFileName().toString().startsWith("search-corrupt-")).findFirst().orElseThrow();
            assertEquals("not a SQLite database",Files.readString(corrupt));
        }
    }
    @Test public void versionOneTextKeysAndContentTablesAreRebuilt() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Rebuilt Sanctuary'}");
        Files.createDirectories(file.getParent()); nativeLoad(Path.of(System.getProperty("realmshark.indexNativeDir")));
        writeSql("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)"); writeSql("INSERT INTO meta VALUES('schema_version','1')");
        writeSql("CREATE TABLE sessions(id TEXT PRIMARY KEY,session TEXT)");
        writeSql("CREATE TABLE doc_ref(id INTEGER PRIMARY KEY,source_id TEXT UNIQUE,session TEXT)");
        writeSql("CREATE VIRTUAL TABLE docs USING fts5(kind UNINDEXED,title,body)");
        writeSql("CREATE VIRTUAL TABLE names USING fts5(name,tokenize='trigram')");
        writeSql("INSERT INTO docs(kind,title,body) VALUES('RUN','obsoleteword','obsoleteword')");
        writeSql("INSERT INTO names(name) VALUES('obsoleteword')");
        HistoryIndex index=index(true);
        assertEquals(Integer.toString(IndexSchema.VERSION),scalar(index,"SELECT value FROM meta WHERE key='schema_version'"));
        assertEquals("integer",scalar(index,"SELECT typeof(sid) FROM sessions"));
        assertEquals("0",scalar(index,"SELECT count(*) FROM sqlite_master WHERE name IN ('doc_ref','docs_content','names_content')"));
        assertEquals("Rebuilt Sanctuary",index.search("Rebuilt",Set.of(Kind.RUN),10).get(0).title());
        assertTrue(index.search("obsoleteword",EnumSet.allOf(Kind.class),10).isEmpty());
    }
    @Test public void closedNewestFirstCurrentExcludedAndReadOnlySources() throws Exception {
        session(A,1,10); session(B,20,30); session(store.currentId(),40,50);
        checkpoint(A,"runs","a","{id:'a',map:'Older'}"); checkpoint(B,"runs","b","{id:'b',map:'Newer'}");
        checkpoint(store.currentId(),"runs","current","{id:'current'}");
        String open="00000000-0000-0000-0000-000000000003"; session(open,60,0); checkpoint(open,"runs","open","{id:'open'}");
        Map<String,byte[]> before=sourceBytes(); List<String> order=new ArrayList<>();
        HistoryIndex index=new HistoryIndex(store,file,true,8,HistoryIndexTest::nativeLoad,order::add); opened.add(index); ready(index);
        assertEquals(List.of(B,A),order); assertEquals(2,count(index,"runs")); assertFalse(index.ready(store.currentId())); assertFalse(index.ready(open));
        Map<String,byte[]> after=sourceBytes(); assertEquals(before.keySet(),after.keySet());
        for (String name:before.keySet()) assertArrayEquals(name,before.get(name),after.get(name));
    }
    private Map<String,byte[]> sourceBytes() throws Exception {
        Map<String,byte[]> bytes=new TreeMap<>();
        try (var paths=Files.walk(root)) { for (Path path:paths.filter(Files::isRegularFile).toList()) bytes.put(root.relativize(path).toString(),Files.readAllBytes(path)); }
        return bytes;
    }
    @Test public void stampChangesMalformedRecordsAndPartialLines() throws Exception {
        session(A,1,10);
        String first="{kind:'Area entered',detail:'é',visitId:'r'}\n";
        String broken="this is not json\n", second="{kind:'Boss',detail:'Oryx',visitId:'r'}\n";
        journal(A,"timeline",first+broken+second+"{kind:'partial'");
        checkpoint(A,"timeline","legacy","{kind:'Legacy',detail:'imported'}");
        checkpoint(A,"runs","r","{id:'r',map:'Oryx'}");
        HistoryIndex index=index(false); assertEquals(3,count(index,"timeline")); assertEquals(1,index.sessionState(A).skipped());
        assertEquals(Long.toString((first+broken).getBytes(StandardCharsets.UTF_8).length),scalar(index,"SELECT byte_offset FROM timeline WHERE kind='Boss'"));
        assertEquals(SessionStore.checkpointName("legacy"),scalar(index,"SELECT checkpoint_key FROM timeline WHERE kind='Legacy'"));
        index.closeAsync().get(30,TimeUnit.SECONDS);
        journal(A,"timeline",first+second+"{kind:'Another event'}\n");
        HistoryIndex changed=index(false); assertEquals(1,changed.replacementCount()); assertEquals(4,count(changed,"timeline"));
        assertEquals(0,changed.sessionState(A).skipped());
    }
    @Test public void malformedLiveOfferDoesNotFailGoodOffersFromEitherSession() throws Exception {
        session(A,1,10); session(B,20,30);
        HistoryIndex index=index(true);
        assertTrue(index.ready(A)); assertTrue(index.ready(B));
        String goodA="{kind:'Boss',detail:'First boss'}\n", bad="[]\n", goodB="{kind:'Boss',detail:'Second boss'}\n";
        journal(A,"timeline",goodA+bad); journal(B,"timeline",goodB);
        index.offer(A,"timeline",0,null,ProjectionsTest.json(goodA));
        index.offer(A,"timeline",goodA.getBytes(StandardCharsets.UTF_8).length,null,ProjectionsTest.json(bad));
        index.offer(B,"timeline",0,null,ProjectionsTest.json(goodB));
        flush(index);
        assertTrue(index.ready(A)); assertTrue(index.ready(B));
        assertEquals(2,count(index,"timeline"));
        assertEquals(1,index.sessionState(A).skipped()); assertEquals(0,index.sessionState(B).skipped());
        assertEquals("1",scalar(index,"SELECT skipped FROM sessions WHERE id='"+A+"'"));
        assertEquals(2,index.search("boss",Set.of(Kind.TIMELINE),10).size());
    }
    @Test public void malformedLiveCheckpointReplacementRemovesItsPreviousRowsAndSearchHits() throws Exception {
        session(A,1,10);
        HistoryIndex index=index(true);
        String valid="{id:'visit',map:'Obsolete sanctuary',playerDamage:{'player:Oldplayer':12}}";
        checkpoint(A,"runs","visit",valid);
        index.offer(A,"runs",-1,"visit",ProjectionsTest.json(valid)); flush(index);
        assertEquals(1,count(index,"runs"));
        assertEquals(1,index.search("Obsolete",Set.of(Kind.RUN),10).size());
        assertEquals(1,index.search("Oldplayer",Set.of(Kind.PLAYER),10).size());
        checkpoint(A,"runs","visit","[]");
        index.offer(A,"runs",-1,"visit",ProjectionsTest.json("[]")); flush(index);
        assertTrue(index.ready(A)); assertEquals(1,index.sessionState(A).skipped());
        assertEquals(0,count(index,"runs")); assertEquals(0,count(index,"players"));
        assertTrue(index.search("Obsolete",Set.of(Kind.RUN),10).isEmpty());
        assertTrue(index.search("Oldplayer",Set.of(Kind.PLAYER),10).isEmpty());
        index.markSessionChanged(A); flush(index);
        assertEquals(0,count(index,"runs")); assertEquals(1,index.sessionState(A).skipped());
    }
    @Test public void nonSqlLiveStorageFailureRetriesEverySessionInTheBatchFromFiles() throws Exception {
        session(A,1,10); session(B,20,30);
        AtomicLong clock=new AtomicLong();
        HistoryIndex index=new HistoryIndex(store,file,true,8,HistoryIndexTest::nativeLoad,id -> {},clock::get);
        opened.add(index); ready(index);
        String first="{kind:'Boss',detail:'First saved boss'}\n", second="{kind:'Boss',detail:'Second saved boss'}\n";
        journal(A,"timeline",first); journal(B,"timeline",second);
        java.lang.reflect.Field writerField=HistoryIndex.class.getDeclaredField("writer"); writerField.setAccessible(true);
        java.lang.reflect.Field dbField=HistoryIndex.class.getDeclaredField("db"); dbField.setAccessible(true);
        AtomicBoolean fail=new AtomicBoolean(true);
        // Install the fault and queue both sessions together on the writer, without racing its periodic drain.
        ((ExecutorService)writerField.get(index)).submit(() -> {
            Connection connection=(Connection)dbField.get(index);
            Connection faulty=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},(proxy,method,args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && sql.startsWith("INSERT INTO timeline(") && fail.compareAndSet(true,false))
                            throw new IllegalStateException("Synthetic non-SQL storage failure");
                        try { return method.invoke(connection,args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            dbField.set(index,faulty);
            index.offer(A,"timeline",0,null,ProjectionsTest.json(first));
            index.offer(B,"timeline",0,null,ProjectionsTest.json(second));
            return null;
        }).get(10,TimeUnit.SECONDS);
        flush(index);
        assertFalse("Storage fault was exercised",fail.get());
        for (String id:List.of(A,B)) {
            assertEquals(HistoryIndex.Readiness.FAILED,index.sessionState(id).readiness());
            assertTrue(index.sessionState(id).reason().contains("IllegalStateException"));
        }
        assertEquals("Failed transaction rolls back the whole batch",0,count(index,"timeline"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); flush(index);
        assertTrue(index.ready(A)); assertTrue(index.ready(B)); assertEquals(2,count(index,"timeline"));
        assertEquals(2,index.search("saved",Set.of(Kind.TIMELINE),10).size());
    }
    @Test public void replacementFailureRollsBackOldRowsAndDocuments() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Old Oryx'}"); AtomicBoolean fail=new AtomicBoolean();
        HistoryIndex index=new HistoryIndex(store,file,true,8,HistoryIndexTest::nativeLoad,id -> { if (fail.get()) throw new java.io.IOException("Synthetic failure"); });
        opened.add(index); ready(index); fail.set(true);
        checkpoint(A,"runs","r","{id:'r',map:'New Oryx'}"); index.markSessionChanged(A); flush(index);
        assertEquals("Old Oryx",scalar(index,"SELECT map FROM runs")); assertEquals("Old Oryx",index.search("oryx",Set.of(Kind.RUN),5).get(0).title());
        // The old complete data survives, but the scope must use its fallback until a replacement succeeds.
        assertFalse(index.ready(A)); fail.set(false); await(() -> index.ready(A)); assertEquals("New Oryx",scalar(index,"SELECT map FROM runs"));
    }
    @Test public void failedReplacementsBackOffEvenWhenOffersContinue() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Saved'}");
        AtomicBoolean fail=new AtomicBoolean(); AtomicInteger attempts=new AtomicInteger();
        AtomicLong clock=new AtomicLong();
        HistoryIndex index=new HistoryIndex(store,file,false,8,HistoryIndexTest::nativeLoad,id -> {
            if (fail.get()) { attempts.incrementAndGet(); throw new java.io.IOException("Synthetic failure"); }
        },clock::get); opened.add(index); ready(index); fail.set(true); index.markSessionChanged(A); flush(index);
        assertEquals(1,attempts.get());
        long[] delays={1,2,4,8,16,32,60,60};
        for (int i=0;i<delays.length;i++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(delays[i])-1);
            index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Saved'}")); flush(index);
            assertEquals("Retry must wait for its deadline",i+1,attempts.get());
            clock.incrementAndGet(); flush(index); assertEquals(i+2,attempts.get());
        }
        fail.set(false); clock.addAndGet(TimeUnit.SECONDS.toNanos(60)); flush(index); assertTrue(index.ready(A));
    }
    @Test public void openSessionAppendDuringReplacementCommitsAndReplaysOffer() throws Exception {
        session(A,1,0); String first="{kind:'Boss',detail:'Initial'}\n";
        String appended="{kind:'Boss',detail:'Queued arrival'}\n"; journal(A,"timeline",first);
        AtomicReference<HistoryIndex> reference=new AtomicReference<>(); AtomicBoolean append=new AtomicBoolean(true);
        HistoryIndex index=new HistoryIndex(store,file,false,8,HistoryIndexTest::nativeLoad,id -> {
            if (append.getAndSet(false)) {
                Files.writeString(root.resolve(id).resolve("timeline.jsonl"),appended,StandardCharsets.UTF_8,StandardOpenOption.APPEND);
                reference.get().offer(id,"timeline",first.getBytes(StandardCharsets.UTF_8).length,null,ProjectionsTest.json(appended));
            }
        }); reference.set(index); opened.add(index); ready(index);
        assertEquals(0,count(index,"sessions")); index.markSessionChanged(A); flush(index);
        assertTrue(index.ready(A)); flush(index);
        assertTrue(index.ready(A)); assertEquals(1,index.replacementCount()); assertEquals(2,count(index,"timeline"));
        assertEquals("Queued arrival",scalar(index,"SELECT detail FROM timeline WHERE byte_offset="+first.getBytes(StandardCharsets.UTF_8).length));
        assertNull(scalar(index,"SELECT stamp FROM sessions")); // queued offer was actually applied after the snapshot
        noOrphanFts(index);
    }
    @Test public void busyWriterRecoversAndMatchingSchemaCanOpenWithoutWrites() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Old'}");
        HistoryIndex original=index(false); original.closeAsync().get(30,TimeUnit.SECONDS);
        HistoryIndex reopened;
        try (Connection lock=DriverManager.getConnection("jdbc:sqlite:"+file)) {
            lock.setAutoCommit(false);
            try (Statement s=lock.createStatement()) { s.executeUpdate("UPDATE meta SET value=value WHERE key='schema_version'"); }
            // Schema creation itself must also be a read-only no-op for the matching version.
            try (Connection other=DriverManager.getConnection("jdbc:sqlite:"+file)) { IndexSchema.create(other); }
            reopened=new HistoryIndex(store,file,false); opened.add(reopened);
            long before=System.nanoTime(); reopened.start().get(30,TimeUnit.SECONDS);
            assertTrue("Lock must outlast busy_timeout",System.nanoTime()-before>=TimeUnit.SECONDS.toNanos(5));
            assertNotEquals(HistoryIndex.Phase.UNAVAILABLE,reopened.state().phase());
            assertEquals(HistoryIndex.Readiness.STALE,reopened.sessionState(A).readiness());
            lock.rollback();
        }
        await(() -> reopened.ready(A));
        checkpoint(A,"runs","r","{id:'r',map:'Recovered'}");
        try (Connection lock=DriverManager.getConnection("jdbc:sqlite:"+file)) {
            lock.setAutoCommit(false);
            try (Statement s=lock.createStatement()) { s.executeUpdate("UPDATE meta SET value=value WHERE key='schema_version'"); }
            reopened.markSessionChanged(A); flush(reopened);
            assertNotEquals(HistoryIndex.Phase.UNAVAILABLE,reopened.state().phase());
            assertFalse(reopened.ready(A)); assertEquals("Old",scalar(reopened,"SELECT map FROM runs"));
            lock.rollback();
        }
        await(() -> reopened.ready(A)); assertEquals("Recovered",scalar(reopened,"SELECT map FROM runs"));
        assertEquals(0,reopened.compactionCount());
    }
    @Test public void closedSessionStillRejectsChangingSnapshotThenRetries() throws Exception {
        session(A,1,10); String first="{kind:'Boss',detail:'Original'}\n"; journal(A,"timeline",first);
        AtomicBoolean append=new AtomicBoolean();
        HistoryIndex index=new HistoryIndex(store,file,false,8,HistoryIndexTest::nativeLoad,id -> {
            if (append.getAndSet(false)) Files.writeString(root.resolve(id).resolve("timeline.jsonl"),
                    "{kind:'Boss',detail:'Later'}\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        }); opened.add(index); ready(index); append.set(true); index.markSessionChanged(A); flush(index);
        assertEquals(HistoryIndex.Readiness.FAILED,index.sessionState(A).readiness());
        assertEquals(1,count(index,"timeline")); assertEquals(1,index.replacementCount());
        await(() -> index.ready(A)); assertEquals(2,count(index,"timeline")); assertEquals(2,index.replacementCount());
    }
    @Test public void smallIncrementalStartupDoesNotCompactAndClosingSkipsCompaction() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Old'}");
        HistoryIndex first=index(false); first.closeAsync().get(30,TimeUnit.SECONDS);
        checkpoint(A,"runs","r","{id:'r',map:'Changed'}");
        HistoryIndex second=index(false); assertEquals(1,second.replacementCount()); assertEquals(0,second.compactionCount());
        second.closeAsync().get(30,TimeUnit.SECONDS);
        writeSql("UPDATE meta SET value='2' WHERE key='schema_version'");
        AtomicReference<HistoryIndex> reference=new AtomicReference<>();
        HistoryIndex closing=new HistoryIndex(store,file,false,8,HistoryIndexTest::nativeLoad,id -> reference.get().close());
        reference.set(closing); opened.add(closing); closing.start().get(30,TimeUnit.SECONDS); closing.closeAsync().get(30,TimeUnit.SECONDS);
        assertEquals(0,closing.compactionCount());
    }
    @Test public void diagnosticRowsNeverEnterAnySearchTable() throws Exception {
        session(A,1,10);
        journal(A,"timeline","{kind:'Resources',detail:'forbidden'}\n{kind:'Capture issue',detail:'forbidden'}\n{kind:'Ownership check',detail:'forbidden'}\n{kind:'Area entered',map:'Oryx'}\n");
        HistoryIndex index=index(true); assertEquals(1,count(index,"timeline"));
        assertTrue(index.search("forbidden",EnumSet.allOf(Kind.class),20).isEmpty());
        assertEquals("0",scalar(index,"SELECT count(*) FROM docs WHERE docs MATCH 'forbidden'"));
        assertEquals("0",scalar(index,"SELECT count(*) FROM names WHERE names MATCH 'forbidden'"));
    }
    @Test public void chatTogglePlayersPrivacyAndAllModuleBackfill() throws Exception {
        session(A,1,10);
        checkpoint(A,"runs","r","{id:'r',map:'Oryx',started:2,lastSeen:9,inspectedPlayers:{'player:roster':{stats:[{statType:'NAME_STAT',stringStatValue:'Roster'}]}},playerDamage:{'player:damage':2}}");
        checkpoint(A,"encounters","e","{recordingId:'e',map:'Oryx',visitSession:'"+A+"',visitId:'r',players:[{name:'Combat'}],bosses:[{name:'Oryx'}]}");
        journal(A,"keypops","{player:'Popper',item:'Oryx Key',time:'2026-01-01T00:00:00Z'}\n");
        journal(A,"chat","{sender:'ChatOnly',text:'privateword',received:'2026-01-01T00:00:00',channel:'PM'}\n");
        checkpoint(A,"chat-stars","message","{id:'message',changed:2,starred:true}");
        journal(A,"fame","{account:'"+HASH+"',character:1,className:'Wizard',fame:20,time:3,visitSession:'"+A+"',visitId:'r'}\n{account:'RAW ACCOUNT',character:2}\n");
        checkpoint(A,"fame-latest",HASH+":1","{account:'"+HASH+"',character:1,fame:22,time:4}");
        checkpoint(A,"fame-snapshots","legacy","{characterFameData:{'1':[{fame:2,time:1}]}}");
        checkpoint(A,"dungeon-totals","summary","[{name:'Oryx',visits:1,time:5}]");
        journal(A,"loot","{bag:'White',dungeon:'Oryx',time:3,context:{visit:{sessionId:'"+A+"',visitId:'r'}},items:[{id:1,name:'Ancient Stone Sword',tier:'UT'}]}\n");
        journal(A,"timeline","{kind:'Victory',map:'Oryx',visitId:'r'}\n");
        journal(A,"unknown-module","{ignored:true}\n");
        HistoryIndex index=index(true); assertEquals(5,count(index,"players")); assertEquals(5,count(index,"player_occurrences"));
        assertEquals(4,count(index,"fame")); assertTrue(index.sessionState(A).unindexed().contains("unknown-module"));
        assertEquals("2",scalar(index,"SELECT count(*) FROM fame WHERE account IS NOT NULL"));
        scanAccountLeak(index);
        HistoryIndex.Facts facts=index.visitFacts(A,"r"); assertEquals(1,facts.timeline().size()); assertEquals(1,facts.lootItems().size()); assertEquals(1,facts.fame().size()); assertEquals(1,facts.combat().size());
        index.setIncludeChat(false); flush(index);
        assertEquals(0,count(index,"chat")); assertEquals(0,count(index,"chat_stars")); assertEquals(4,count(index,"players"));
        assertEquals(4,count(index,"player_occurrences")); assertTrue(index.search("privateword",EnumSet.allOf(Kind.class),20).isEmpty());
        assertEquals("0",scalar(index,"SELECT count(*) FROM names WHERE names MATCH 'ChatOnly'"));
        index.setIncludeChat(true); flush(index); assertEquals(1,count(index,"chat")); assertEquals(5,count(index,"players")); scanAccountLeak(index);
    }
    private static void scanAccountLeak(HistoryIndex index) throws Exception {
        for (String fts:List.of("docs","names")) assertEquals("0",scalar(index,"SELECT count(*) FROM "+fts+" WHERE "+fts+" MATCH '"+HASH+"'"));
        try (Connection c=index.readConnection(); Statement s=c.createStatement()) {
            List<String> tables=new ArrayList<>();
            try (ResultSet r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) { while(r.next()) tables.add(r.getString(1)); }
            for (String table:tables) {
                List<String> columns=new ArrayList<>();
                try (ResultSet r=s.executeQuery("PRAGMA table_info('"+table.replace("'","''")+"')")) { while(r.next()) columns.add(r.getString("name")); }
                for (String column:columns) {
                    if (table.equals("fame") && column.equals("account")) continue;
                    String expression="\""+column.replace("\"","\"\"")+"\"";
                    try (PreparedStatement p=c.prepareStatement("SELECT count(*) FROM \""+table.replace("\"","\"\"")+"\" WHERE typeof("+expression+")='text' AND instr("+expression+",?)>0")) {
                        p.setString(1,HASH); try(ResultSet r=p.executeQuery()) { r.next(); assertEquals(table+"."+column,0,r.getLong(1)); }
                    }
                }
            }
        }
    }
    @Test public void wordPrefixPhraseSubstringAndDefaultKinds() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Oryx Sanctuary',started:2,lastSeen:9,playerDamage:{'player:oryx':2}}");
        journal(A,"loot","{items:[{name:'Ancient Stone Sword',id:1}],time:3}\n");
        journal(A,"chat","{sender:'Someone',text:'Oryx Sanctuary',received:'2026-01-01T00:00:00'}\n");
        HistoryIndex index=index(true);
        assertTrue(index.search("ory*",Set.of(),20).stream().allMatch(h->h.kind()==Kind.RUN||h.kind()==Kind.PLAYER));
        assertFalse(index.search("ory*",Set.of(),20).isEmpty());
        assertEquals("Oryx Sanctuary",index.search("\"Oryx Sanctuary\"",Set.of(Kind.RUN),10).get(0).title());
        assertEquals("Ancient Stone Sword",index.search("tone sw",Set.of(Kind.LOOT),10).get(0).title());
        assertTrue(index.search("nothingpresent",EnumSet.allOf(Kind.class),10).isEmpty());
        assertNotNull(index.search("\"unclosed",EnumSet.allOf(Kind.class),10));
        assertEquals(1,index.runsPage(new HistoryIndex.RunFilter(A,"Oryx Sanctuary",Set.of("App ended"),1L,5L,1L,20L,false),HistoryIndex.RunSort.DURATION,0,10).size());
        assertTrue(index.runsPage(HistoryIndex.RunFilter.all(),HistoryIndex.RunSort.NEWEST,1,10).isEmpty());
    }
    @Test public void equalRelevancePrefersRunThenPlayer() throws Exception {
        session(A,1,10); HistoryIndex index=index(true);
        // Equal-length test documents remove term-frequency effects and isolate the cross-kind tiebreak rule.
        index.closeAsync().get(30,TimeUnit.SECONDS);
        writeSql("INSERT INTO runs(sid,module,byte_offset,time,map) SELECT sid,"+IndexSchema.module("runs")+",0,1,'tie' FROM sessions WHERE id='"+A+"'");
        writeSql("INSERT INTO chat(sid,module,byte_offset,time,sender,text) SELECT sid,"+IndexSchema.module("chat")+",0,1,'tie','tie' FROM sessions WHERE id='"+A+"'");
        writeSql("INSERT INTO players(key,name,occurrences) VALUES('player:tie','tie',1)");
        writeSql("INSERT INTO player_occurrences(pid,sid,table_code,source_rowid,time) SELECT p.pid,r.sid,"+IndexSchema.code("runs")+",r.id,1 FROM players p,runs r");
        for (String table:List.of("chat","players","runs"))
            writeSql("INSERT INTO docs(rowid,title,body) SELECT ("+((long)IndexSchema.code(table)<<48)+" | "+(table.equals("players")?"pid":"id")+"),'tie','tie' FROM "+table);
        HistoryIndex reopened=index(true); List<HistoryIndex.Hit> hits=reopened.search("tie",EnumSet.allOf(Kind.class),10);
        assertEquals(List.of(Kind.RUN,Kind.PLAYER,Kind.CHAT),hits.stream().map(HistoryIndex.Hit::kind).toList());
    }
    @Test public void offersUpsertCheckpointsAndOverflowRecoversFromFiles() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Initial'}");
        HistoryIndex index=new HistoryIndex(store,file,true,1,HistoryIndexTest::nativeLoad,id -> {}); opened.add(index);
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Initial'}"));
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Overflow'}"));
        assertEquals(1,index.overflowCount()); assertEquals(HistoryIndex.Readiness.STALE,index.sessionState(A).readiness());
        ready(index); flush(index); assertTrue(index.ready(A)); assertEquals(1,count(index,"runs"));
        checkpoint(A,"runs","r","{id:'r',map:'Updated'}");
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Updated'}")); flush(index);
        assertEquals("Updated",scalar(index,"SELECT map FROM runs")); assertEquals(1,count(index,"runs"));
        assertEquals(1,index.search("Updated",Set.of(Kind.RUN),10).size()); assertTrue(index.search("Initial",Set.of(Kind.RUN),10).isEmpty());
    }
    @Test public void claimsSkipOtherLiveInstanceAndExpire() throws Exception {
        HistoryIndex initial=index(true); initial.closeAsync().get(30,TimeUnit.SECONDS);
        session(A,1,10); checkpoint(A,"runs","r","{id:'r'}");
        writeSql("INSERT INTO claims(session,instance,heartbeat) VALUES('"+A+"','another-instance',"+System.currentTimeMillis()+")");
        HistoryIndex index=index(true); assertEquals(0,count(index,"runs")); assertEquals(HistoryIndex.Readiness.CLAIMED,index.sessionState(A).readiness());
        writeSql("UPDATE claims SET heartbeat=0"); index.markSessionChanged(A); flush(index); assertEquals(1,count(index,"runs")); assertTrue(index.ready(A));
    }
    @Test public void overflowCatchupCannotBeOverwrittenByOlderQueuedCheckpoints() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Newest persisted value'}");
        HistoryIndex index=new HistoryIndex(store,file,true,2001,HistoryIndexTest::nativeLoad,id -> {}); opened.add(index);
        for (int i=0;i<2002;i++) index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Older queued value'}"));
        assertEquals(1,index.overflowCount()); ready(index); flush(index); flush(index);
        assertEquals("Newest persisted value",scalar(index,"SELECT map FROM runs")); assertTrue(index.ready(A));
    }
    @Test public void queuedOffersAreCommittedInBoundedBatches() throws Exception {
        session(A,1,10); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        StringBuilder journal=new StringBuilder(); List<Long> offsets=new ArrayList<>();
        for (int i=0;i<2001;i++) { offsets.add((long)journal.length()); journal.append("{kind:'Boss',detail:'event ").append(i).append("'}\n"); }
        journal(A,"timeline",journal.toString());
        HistoryIndex index=new HistoryIndex(store,file,true,4096,HistoryIndexTest::nativeLoad,id -> {
            entered.countDown(); if (!release.await(10,TimeUnit.SECONDS)) throw new java.io.IOException("Test writer not released");
        }); opened.add(index); index.start(); assertTrue(entered.await(10,TimeUnit.SECONDS));
        try {
            // Files are already persisted before the future feed calls offer. Their offsets are byte positions.
            for (int i=0;i<offsets.size();i++) index.offer(A,"timeline",offsets.get(i),null,ProjectionsTest.json("{kind:'Boss',detail:'event "+i+"'}"));
            assertEquals(0,index.overflowCount());
        } finally { release.countDown(); }
        index.start().get(30,TimeUnit.SECONDS); flush(index); flush(index);
        assertEquals(2001,count(index,"timeline"));
        assertEquals("2001",scalar(index,"SELECT count(DISTINCT byte_offset) FROM timeline"));
    }
    @Test public void sessionRemovedDuringBackfillDoesNotLeaveOldRows() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r'}"); AtomicBoolean disappear=new AtomicBoolean();
        HistoryIndex index=new HistoryIndex(store,file,true,4,HistoryIndexTest::nativeLoad,id -> {
            if (disappear.get()) Files.move(root.resolve(id),temp.getRoot().toPath().resolve("removed-session"));
        }); opened.add(index); ready(index); disappear.set(true); index.markSessionChanged(A); flush(index);
        assertEquals(0,count(index,"runs")); assertEquals(0,count(index,"sessions"));
    }
    @Test public void removeAndRebuildAndUnreadableSessionState() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Oryx'}"); session(B,2,10);
        Files.writeString(root.resolve(B).resolve("session.json"),"broken");
        HistoryIndex index=index(true); assertEquals(HistoryIndex.Readiness.FAILED,index.sessionState(B).readiness());
        index.rebuild(); flush(index); assertEquals(1,count(index,"runs"));
        index.removeSession(A); flush(index); assertEquals(0,count(index,"runs")); assertTrue(index.search("oryx",Set.of(),10).isEmpty());
    }
    @Test public void removedSessionRejectsQueuedOffersAndCanBeReimportedWithSameId() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Original'}"); HistoryIndex index=index(false);
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Queued before removal'}"));
        index.removeSession(A);
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Offered during removal'}"));
        flush(index); assertEquals(0,count(index,"sessions")); assertEquals(0,count(index,"runs")); noOrphanFts(index);
        AtomicInteger notifications=new AtomicInteger();
        try (AutoCloseable listener=index.listen(state -> notifications.incrementAndGet())) {
            flush(index); assertEquals("A completed removal must not keep publishing changes",0,notifications.get());
        }
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Reimported'}");
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Reimported'}")); flush(index);
        assertTrue(index.ready(A)); assertEquals(1,count(index,"runs")); assertEquals("Reimported",scalar(index,"SELECT map FROM runs"));
        assertTrue(index.search("Queued",EnumSet.allOf(Kind.class),10).isEmpty()); noOrphanFts(index);
    }
    @Test public void reimportChangeWhileRemovalIsPendingSurvivesTheTombstone() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Original'}");
        HistoryIndex initial=index(false); initial.closeAsync().get(30,TimeUnit.SECONDS);
        HistoryIndex index=new HistoryIndex(store,file,false); opened.add(index);
        // Queue both lifecycle events before starting the writer so deletion cannot win this race by chance.
        index.offer(A,"runs",-1,"r",ProjectionsTest.json("{id:'r',map:'Obsoletequeued'}"));
        index.removeSession(A);
        checkpoint(A,"runs","r","{id:'r',map:'Immediateimport'}");
        index.markSessionChanged(A);
        ready(index); flush(index);
        assertTrue(index.ready(A)); assertEquals(1,count(index,"runs"));
        assertEquals("Immediateimport",scalar(index,"SELECT map FROM runs"));
        assertTrue(index.search("Original",Set.of(Kind.RUN),10).isEmpty());
        assertTrue(index.search("Obsoletequeued",Set.of(Kind.RUN),10).isEmpty());
        assertEquals(1,index.search("Immediateimport",Set.of(Kind.RUN),10).size()); noOrphanFts(index);
        long replacements=index.replacementCount(); flush(index); assertEquals(replacements,index.replacementCount());
    }
    private static void noOrphanFts(HistoryIndex index) throws Exception {
        try (Connection c=index.readConnection(); Statement s=c.createStatement()) {
            for (String fts:List.of("docs","names")) {
                List<Long> docs=new ArrayList<>();
                try (ResultSet r=s.executeQuery("SELECT rowid FROM "+fts)) { while(r.next()) docs.add(r.getLong(1)); }
                for (long doc:docs) assertFalse(fts+" orphan "+doc,IndexStorage.source(c,(int)(doc>>>48),doc & IndexSchema.ROW_MASK).isEmpty());
            }
        }
    }
    @Test public void contentlessReplacementRemovesOldTokensAndKeepsOtherSession() throws Exception {
        session(A,1,10); session(B,2,10);
        checkpoint(A,"runs","r","{id:'r',map:'Ancient Sanctuary',playerDamage:{'player:oldperson':1}}");
        checkpoint(B,"runs","b","{id:'b',map:'Keep Sanctuary',playerDamage:{'player:keeper':1}}");
        HistoryIndex index=index(true);
        assertFalse(index.search("oldperson",Set.of(Kind.PLAYER),10).isEmpty());
        checkpoint(A,"runs","r","{id:'r',map:'New Sanctuary',playerDamage:{'player:newperson':1}}"); index.markSessionChanged(A); flush(index);
        for (String query:List.of("Ancient","oldperson")) assertTrue(index.search(query,EnumSet.allOf(Kind.class),20).isEmpty());
        assertFalse(index.search("keeper",Set.of(Kind.PLAYER),10).isEmpty());
        assertFalse(index.search("newperson",Set.of(Kind.PLAYER),10).isEmpty());
        noOrphanFts(index); index.removeSession(A); flush(index); noOrphanFts(index);
        assertTrue(index.search("newperson",EnumSet.allOf(Kind.class),20).isEmpty());
    }
    @Test public void contentlessChatOffDeletesPostingsAndChatOnlyPlayers() throws Exception {
        session(A,1,10); checkpoint(A,"runs","r","{id:'r',map:'Keep',playerDamage:{'player:shared':1}}");
        journal(A,"chat","{sender:'ChatOnly',text:'privatetoken',received:'2026-01-01T00:00:00'}\n{sender:'SHARED',text:'privateagain',received:'2026-01-01T00:00:01'}\n");
        HistoryIndex index=index(true);
        assertFalse(index.search("privatetoken",Set.of(Kind.CHAT),10).isEmpty());
        long replacements=index.replacementCount(); String stamp=scalar(index,"SELECT stamp FROM sessions");
        index.setIncludeChat(false); flush(index); noOrphanFts(index);
        assertEquals(replacements,index.replacementCount()); assertTrue(index.ready(A));
        assertEquals(stamp,scalar(index,"SELECT stamp FROM sessions"));
        for (String fts:List.of("docs","names")) {
            assertEquals("0",scalar(index,"SELECT count(*) FROM "+fts+" WHERE "+fts+" MATCH 'ChatOnly'"));
            assertEquals("0",scalar(index,"SELECT count(*) FROM "+fts+" WHERE (rowid >> 48)="+IndexSchema.code("chat")));
        }
        assertTrue(index.search("privatetoken",EnumSet.allOf(Kind.class),10).isEmpty());
        assertEquals("shared",index.search("shared",Set.of(Kind.PLAYER),10).get(0).title());
        index.setIncludeChat(true); flush(index); noOrphanFts(index);
        assertEquals(replacements+1,index.replacementCount());
        assertFalse(index.search("privatetoken",Set.of(Kind.CHAT),10).isEmpty());
    }
}
