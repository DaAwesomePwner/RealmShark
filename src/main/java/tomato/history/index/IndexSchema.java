package tomato.history.index;

import java.sql.*;
import java.util.*;

final class IndexSchema {
    static final int VERSION = 3;
    static final long ROW_MASK = (1L << 48) - 1;
    static final Map<String,String> TABLES = new LinkedHashMap<>();
    static final List<String> MODULES = List.of("session","runs","loot","timeline","chat","chat-stars","keypops","fame","fame-latest","fame-snapshots","encounters","dungeon-totals");
    static {
        TABLES.put("sessions","label TEXT,version TEXT,started INTEGER,ended INTEGER,stamp TEXT,index_state TEXT,skipped INTEGER NOT NULL DEFAULT 0,reason TEXT,unindexed TEXT");
        TABLES.put("runs","map TEXT,started INTEGER,ended INTEGER,duration INTEGER,outcome TEXT,damage INTEGER,players INTEGER,roster_size INTEGER,issues INTEGER,gaps INTEGER,progress INTEGER,evidence TEXT,status TEXT,search_text TEXT,player_names TEXT");
        TABLES.put("loot_bags","dungeon TEXT,bag TEXT,dropper TEXT,white INTEGER,items INTEGER");
        TABLES.put("loot_items","item_id INTEGER,name TEXT,dungeon TEXT,dropper TEXT,bag TEXT,tier TEXT,rarity TEXT,enchant_slots INTEGER,enchant_applied INTEGER,ut INTEGER,st INTEGER,high_tier INTEGER,potion INTEGER");
        TABLES.put("timeline","record_id TEXT,map TEXT,kind TEXT,summary TEXT,detail TEXT");
        TABLES.put("chat","message_id TEXT,received TEXT,channel TEXT,sender TEXT,recipient TEXT,own INTEGER,ignored INTEGER,text TEXT");
        TABLES.put("chat_stars","message_id TEXT,changed INTEGER,starred INTEGER");
        TABLES.put("keypops","record_id TEXT,kind TEXT,player TEXT,item TEXT");
        TABLES.put("fame","account TEXT CHECK(account IS NULL OR (length(account)=64 AND account NOT GLOB '*[^0-9a-f]*')),character INTEGER,fame REAL,class_name TEXT,map TEXT");
        TABLES.put("combat","recording_id TEXT,map TEXT,started INTEGER,elapsed INTEGER,damage INTEGER,deaths INTEGER,players INTEGER,search_text TEXT,player_names TEXT");
        TABLES.put("dungeon_totals","dungeon TEXT,visits INTEGER,duration INTEGER,hits INTEGER,items INTEGER");
        TABLES.put("players", ""); TABLES.put("player_occurrences", "");
    }
    private static final List<String> ORDER=List.copyOf(TABLES.keySet());
    private static final List<String> SOURCES=ORDER.stream().filter(t -> !Set.of("sessions","players","player_occurrences").contains(t)).toList();
    static int code(String table) { return ORDER.indexOf(table)+1; }
    static String table(int code) { return ORDER.get(code-1); }
    static int module(String module) { return MODULES.indexOf(module); }
    static long docId(String table,long row) {
        if (row<=0 || row>ROW_MASK) throw new IllegalArgumentException("Index row ID exceeds FTS address space");
        return ((long)code(table)<<48)|row;
    }
    static Kind kind(String table) {
        return switch(table) {
            case "sessions" -> Kind.SESSION; case "runs" -> Kind.RUN; case "players" -> Kind.PLAYER;
            case "loot_bags","loot_items" -> Kind.LOOT; case "timeline" -> Kind.TIMELINE; case "chat" -> Kind.CHAT;
            case "keypops" -> Kind.KEYPOP; case "fame" -> Kind.FAME; case "combat" -> Kind.COMBAT;
            case "dungeon_totals" -> Kind.DUNGEON; default -> null;
        };
    }
    static List<String> sources() { return SOURCES; }
    static void configure(Connection c) throws SQLException {
        exec(c,"PRAGMA busy_timeout=5000"); exec(c,"PRAGMA foreign_keys=ON"); exec(c,"PRAGMA secure_delete=ON");
    }
    static void create(Connection c) throws SQLException {
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            if (r.next() && Integer.toString(VERSION).equals(r.getString(1))) return;
        } catch (SQLException missing) {
            if (!missing.getMessage().contains("no such table")) throw missing;
        }
        exec(c,"CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT)");
        exec(c,"CREATE TABLE IF NOT EXISTS sessions(sid INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,"+TABLES.get("sessions")+")");
        exec(c,"CREATE TABLE IF NOT EXISTS visits(vid INTEGER PRIMARY KEY,sid INTEGER NOT NULL,visit_id TEXT NOT NULL,UNIQUE(sid,visit_id))");
        for (String table:sources()) {
            exec(c,"CREATE TABLE IF NOT EXISTS "+table+"(id INTEGER PRIMARY KEY,sid INTEGER NOT NULL,vid INTEGER,module INTEGER NOT NULL,byte_offset INTEGER,checkpoint_key TEXT,item_position INTEGER NOT NULL DEFAULT -1,time INTEGER,"
                    +TABLES.get(table)+",CHECK((byte_offset IS NOT NULL AND checkpoint_key IS NULL) OR (byte_offset IS NULL AND checkpoint_key IS NOT NULL)))");
            // Disjoint source identities; there is no second B-tree holding a composite text primary key.
            exec(c,"CREATE UNIQUE INDEX IF NOT EXISTS "+table+"_journal ON "+table+"(sid,module,byte_offset,item_position) WHERE byte_offset IS NOT NULL");
            exec(c,"CREATE UNIQUE INDEX IF NOT EXISTS "+table+"_checkpoint ON "+table+"(sid,module,checkpoint_key,item_position) WHERE checkpoint_key IS NOT NULL");
        }
        for (String table:List.of("runs","timeline","loot_bags","loot_items","fame","combat"))
            exec(c,"CREATE INDEX IF NOT EXISTS "+table+"_visit ON "+table+"(sid,vid)");
        for (String table:List.of("runs","loot_items"))
            exec(c,"CREATE INDEX IF NOT EXISTS "+table+"_time ON "+table+"("+(table.equals("runs")?"started":"time")+" DESC)");
        exec(c,"CREATE TABLE IF NOT EXISTS players(pid INTEGER PRIMARY KEY,key TEXT NOT NULL UNIQUE,name TEXT NOT NULL,occurrences INTEGER NOT NULL DEFAULT 0)");
        exec(c,"CREATE TABLE IF NOT EXISTS player_occurrences(id INTEGER PRIMARY KEY,pid INTEGER NOT NULL,sid INTEGER NOT NULL,vid INTEGER,table_code INTEGER NOT NULL,source_rowid INTEGER NOT NULL,time INTEGER)");
        exec(c,"CREATE INDEX IF NOT EXISTS player_occurrences_player ON player_occurrences(pid,time DESC)");
        exec(c,"CREATE INDEX IF NOT EXISTS player_occurrences_session ON player_occurrences(sid)");
        exec(c,"CREATE VIRTUAL TABLE IF NOT EXISTS docs USING fts5(title,body,content='',contentless_delete=1,tokenize='unicode61 remove_diacritics 2',prefix='2 3')");
        exec(c,"CREATE VIRTUAL TABLE IF NOT EXISTS names USING fts5(name,content='',contentless_delete=1,tokenize='trigram')");
        exec(c,"INSERT INTO docs(docs,rank) VALUES('secure-delete',1)");
        exec(c,"INSERT INTO names(names,rank) VALUES('secure-delete',1)");
        // Claims are control-plane leases, not duplicated per-record source identities.
        exec(c,"CREATE TABLE IF NOT EXISTS claims(session TEXT PRIMARY KEY,instance TEXT NOT NULL,heartbeat INTEGER NOT NULL)");
        try (PreparedStatement p=c.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version',?)")) {
            p.setString(1,Integer.toString(VERSION)); p.executeUpdate();
        }
    }
    static void drop(Connection c) throws SQLException {
        for (String table:List.of("docs","names","doc_ref","claims","visits")) exec(c,"DROP TABLE IF EXISTS "+table);
        for (String table:TABLES.keySet()) exec(c,"DROP TABLE IF EXISTS "+table);
        exec(c,"DROP TABLE IF EXISTS meta");
    }
    static void exec(Connection c,String sql) throws SQLException { try (Statement s=c.createStatement()) { s.execute(sql); } }
}
