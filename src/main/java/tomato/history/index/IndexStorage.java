package tomato.history.index;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import tomato.backend.data.InspectSnapshot;
import tomato.history.SessionStore;

/** Writer-confined compact storage. Prepared statements and intern caches live for one transaction. */
final class IndexStorage implements AutoCloseable {
    private final Connection db;
    private final Map<String,PreparedStatement> statements=new HashMap<>();
    private final Map<String,Long> sessionIds=new HashMap<>(), visitIds=new HashMap<>(), playerIds=new HashMap<>();
    private final Set<Long> dirtyPlayers=new LinkedHashSet<>();
    private final List<Document> documents=new ArrayList<>();
    private record Document(long id,String title,String body,String names) {}
    IndexStorage(Connection db) { this.db=db; }
    private PreparedStatement statement(String sql) throws SQLException {
        PreparedStatement p=statements.get(sql);
        if (p==null) { p=db.prepareStatement(sql); statements.put(sql,p); }
        p.clearParameters(); return p;
    }
    int execute(String sql,Object... args) throws SQLException {
        PreparedStatement p=statement(sql); HistoryIndex.bind(p,Arrays.asList(args)); return p.executeUpdate();
    }
    private Long scalar(String sql,Object... args) throws SQLException {
        PreparedStatement p=statement(sql); HistoryIndex.bind(p,Arrays.asList(args));
        try (ResultSet r=p.executeQuery()) { return r.next()?r.getLong(1):null; }
    }
    boolean hasSession(String session) throws SQLException { return scalar("SELECT 1 FROM sessions WHERE id=?",session)!=null; }
    long sid(String session) throws SQLException {
        Long id=sessionIds.get(session);
        if (id==null) {
            id=scalar("SELECT sid FROM sessions WHERE id=?",session);
            if (id==null) id=scalar("INSERT INTO sessions(id) VALUES(?) RETURNING sid",session);
            sessionIds.put(session,id);
        }
        return id;
    }
    private Long vid(long sid,String visit) throws SQLException {
        if (visit==null) return null;
        String key=sid+"/"+visit; Long id=visitIds.get(key);
        if (id==null) {
            id=scalar("SELECT vid FROM visits WHERE sid=? AND visit_id=?",sid,visit);
            if (id==null) id=scalar("INSERT INTO visits(sid,visit_id) VALUES(?,?) RETURNING vid",sid,visit);
            visitIds.put(key,id);
        }
        return id;
    }
    private long pid(String key,String name) throws SQLException {
        Long id=playerIds.get(key);
        if (id==null) {
            id=scalar("SELECT pid FROM players WHERE key=?",key);
            if (id==null) id=scalar("INSERT INTO players(key,name) VALUES(?,?) RETURNING pid",key,name);
            playerIds.put(key,id);
        }
        return id;
    }
    void insert(Projections.Row row) throws SQLException {
        Locator l=row.locator(); long sid=sid(l.session()), id;
        if (row.table().equals("sessions")) {
            execute("UPDATE sessions SET label=?,version=?,started=?,ended=? WHERE sid=?",row.columns().get("label"),row.columns().get("version"),row.columns().get("started"),row.columns().get("ended"),sid);
            id=sid;
        } else {
            Long vid=vid(sid,row.visitId());
            Map<String,Object> fields=Projections.fields("sid",sid,"vid",vid,"module",IndexSchema.module(l.module()),
                    "byte_offset",l.byteOffset()<0?null:l.byteOffset(),"checkpoint_key",l.checkpointKey(),"item_position",l.itemPosition(),"time",row.time());
            fields.putAll(row.columns());
            if (row.table().equals("runs") || row.table().equals("combat")) {
                fields.put("search_text",row.body());
                // Names belong to the source record once; occurrences contain integers only.
                fields.put("player_names",SessionStore.JSON.toJson(row.players().values()));
            }
            if (row.table().equals("chat")) fields.put("text",row.body());
            PreparedStatement p=statement("INSERT INTO "+row.table()+"("+String.join(",",fields.keySet())+") VALUES("+HistoryIndex.marks(fields.size())+") RETURNING id");
            HistoryIndex.bind(p,new ArrayList<>(fields.values()));
            try (ResultSet r=p.executeQuery()) { if (!r.next()) throw new SQLException("Missing inserted row ID"); id=r.getLong(1); }
            for (Map.Entry<String,String> player:row.players().entrySet()) {
                long pid=pid(player.getKey(),player.getValue()); dirtyPlayers.add(pid);
                execute("INSERT INTO player_occurrences(pid,sid,vid,table_code,source_rowid,time) VALUES(?,?,?,?,?,?)",pid,sid,vid,IndexSchema.code(row.table()),id,row.time());
            }
        }
        if (row.kind()!=null) documents.add(new Document(IndexSchema.docId(row.table(),id),row.title(),row.body(),row.names()));
    }
    void flushDocuments() throws SQLException {
        if (documents.isEmpty()) return;
        PreparedStatement docs=statement("INSERT INTO docs(rowid,title,body) VALUES(?,?,?)");
        PreparedStatement names=statement("INSERT INTO names(rowid,name) VALUES(?,?)");
        try {
            for (Document document:documents) {
                docs.setLong(1,document.id); docs.setString(2,document.title); docs.setString(3,document.body); docs.addBatch();
                if (!document.names.isBlank()) { names.setLong(1,document.id); names.setString(2,document.names); names.addBatch(); }
            }
            docs.executeBatch(); names.executeBatch(); documents.clear();
        } finally { docs.clearBatch(); names.clearBatch(); }
    }
    private void deleteDocument(long id) throws SQLException {
        execute("DELETE FROM docs WHERE rowid=?",id); execute("DELETE FROM names WHERE rowid=?",id);
    }
    private void dirty(String where,Object... args) throws SQLException {
        PreparedStatement p=statement("SELECT DISTINCT pid FROM player_occurrences WHERE "+where); HistoryIndex.bind(p,Arrays.asList(args));
        try (ResultSet r=p.executeQuery()) { while (r.next()) dirtyPlayers.add(r.getLong(1)); }
    }
    void clearSession(String session,boolean metadata) throws SQLException {
        flushDocuments(); Long sid=scalar("SELECT sid FROM sessions WHERE id=?",session); if (sid==null) return;
        dirty("sid=?",sid); execute("DELETE FROM player_occurrences WHERE sid=?",sid);
        for (String table:IndexSchema.sources()) deleteRows(table,"sid=?",sid);
        deleteDocument(IndexSchema.docId("sessions",sid));
        execute("DELETE FROM visits WHERE sid=?",sid); visitIds.clear();
        if (metadata) { execute("DELETE FROM sessions WHERE sid=?",sid); sessionIds.remove(session); }
    }
    private void deleteRows(String table,String where,Object... args) throws SQLException {
        if (IndexSchema.kind(table)!=null) for (String fts:List.of("docs","names")) {
            Object[] params=new Object[args.length+1]; params[0]=(long)IndexSchema.code(table)<<48; System.arraycopy(args,0,params,1,args.length);
            execute("DELETE FROM "+fts+" WHERE rowid IN (SELECT (? | id) FROM "+table+" WHERE "+where+")",params);
        }
        execute("DELETE FROM "+table+" WHERE "+where,args);
    }
    void deleteSource(Locator l) throws SQLException {
        flushDocuments(); Long sid=scalar("SELECT sid FROM sessions WHERE id=?",l.session()); if (sid==null) return;
        if (l.module().equals("session")) { deleteDocument(IndexSchema.docId("sessions",sid)); return; }
        for (String table:tablesFor(l.module())) {
            String where="sid=? AND module=? AND "+(l.checkpointKey()==null?"byte_offset=?":"checkpoint_key=?");
            Object[] args={sid,IndexSchema.module(l.module()),l.checkpointKey()==null?l.byteOffset():l.checkpointKey()};
            String occurrences="sid=? AND table_code="+IndexSchema.code(table)+" AND source_rowid IN (SELECT id FROM "+table+" WHERE "+where+")";
            Object[] withSession={sid,args[0],args[1],args[2]};
            dirty(occurrences,withSession); execute("DELETE FROM player_occurrences WHERE "+occurrences,withSession);
            deleteRows(table,where,args);
        }
    }
    private static List<String> tablesFor(String module) {
        return switch(module) {
            case "loot" -> List.of("loot_bags","loot_items"); case "fame","fame-latest","fame-snapshots" -> List.of("fame");
            case "encounters" -> List.of("combat"); case "chat-stars" -> List.of("chat_stars"); case "dungeon-totals" -> List.of("dungeon_totals");
            case "session" -> List.of(); default -> List.of(module);
        };
    }
    void deleteChat() throws SQLException {
        flushDocuments(); int code=IndexSchema.code("chat");
        dirty("table_code=?",code); execute("DELETE FROM player_occurrences WHERE table_code=?",code);
        deleteRows("chat","1=1"); deleteRows("chat_stars","1=1");
    }
    void refreshPlayers() throws SQLException {
        for (long pid:dirtyPlayers) {
            PreparedStatement p=statement("SELECT p.key,p.name,(SELECT count(*) FROM player_occurrences o WHERE o.pid=p.pid) AS n FROM players p WHERE pid=?"); p.setLong(1,pid);
            String key,name; long count;
            try (ResultSet r=p.executeQuery()) { if (!r.next()) continue; key=r.getString(1); name=r.getString(2); count=r.getLong(3); }
            deleteDocument(IndexSchema.docId("players",pid));
            if (count==0) { execute("DELETE FROM players WHERE pid=?",pid); playerIds.remove(key); continue; }
            p=statement("SELECT table_code,source_rowid FROM player_occurrences WHERE pid=? ORDER BY time DESC,id LIMIT 1"); p.setLong(1,pid);
            String table; long source;
            try (ResultSet r=p.executeQuery()) { if (!r.next()) throw new SQLException("Player occurrence disappeared"); table=IndexSchema.table(r.getInt(1)); source=r.getLong(2); }
            String actual=playerName(key,table,source); if (actual!=null) name=actual;
            execute("UPDATE players SET name=?,occurrences=? WHERE pid=?",name,count,pid);
            documents.add(new Document(IndexSchema.docId("players",pid),name,name,name));
        }
        dirtyPlayers.clear();
    }
    private String playerName(String key,String table,long id) throws SQLException {
        String field=switch(table) { case "chat" -> "sender"; case "keypops" -> "player"; default -> "player_names"; };
        PreparedStatement p=statement("SELECT "+field+" FROM "+table+" WHERE id=?"); p.setLong(1,id); String value;
        try (ResultSet r=p.executeQuery()) { value=r.next()?r.getString(1):null; }
        if (value==null) return null;
        if (!field.equals("player_names")) return InspectSnapshot.playerKey(0,value).equals(key)?value:null;
        for (JsonElement name:SessionStore.JSON.fromJson(value,JsonArray.class))
            if (InspectSnapshot.playerKey(0,name.getAsString()).equals(key)) return name.getAsString();
        return null;
    }
    static Map<String,Object> latestOccurrence(Connection c,long pid) throws SQLException {
        List<Map<String,Object>> rows=HistoryIndex.rows(c,"SELECT o.*,p.key,p.name,p.occurrences FROM player_occurrences o JOIN players p ON p.pid=o.pid WHERE o.pid=? ORDER BY o.time DESC,o.id LIMIT 1",List.of(pid));
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    /** Joined rows restore public source locators; no repeated session/visit strings are persisted in data rows. */
    static String select(String table) {
        return "SELECT t.*,s.id AS session,v.visit_id FROM "+table+" t JOIN sessions s ON s.sid=t.sid LEFT JOIN visits v ON v.vid=t.vid";
    }
    static Map<String,Object> source(Connection c,int code,long id) throws SQLException {
        String table=IndexSchema.table(code); List<Map<String,Object>> found;
        if (table.equals("sessions")) {
            found=HistoryIndex.rows(c,"SELECT *,id AS session,started AS time FROM sessions WHERE sid=?",List.of(id));
            if (found.isEmpty()) return Map.of();
            Map<String,Object> row=new LinkedHashMap<>(found.get(0));
            row.putAll(Projections.fields("module","session","byte_offset",null,"checkpoint_key","session","item_position",-1,"visit_id",null));
            return row;
        }
        if (table.equals("players")) {
            Map<String,Object> occurrence=latestOccurrence(c,id); if (occurrence.isEmpty()) return Map.of();
            Map<String,Object> origin=source(c,((Number)occurrence.get("table_code")).intValue(),((Number)occurrence.get("source_rowid")).longValue());
            if (origin.isEmpty()) return Map.of();
            Map<String,Object> row=new LinkedHashMap<>(origin);
            row.putAll(Projections.fields("key",occurrence.get("key"),"name",occurrence.get("name"),"occurrences",occurrence.get("occurrences")));
            return row;
        }
        found=HistoryIndex.rows(c,select(table)+" WHERE t.id=?",List.of(id));
        return found.isEmpty()?Map.of():external(found.get(0));
    }
    static Map<String,Object> external(Map<String,Object> stored) {
        Map<String,Object> row=new LinkedHashMap<>(stored);
        row.put("module",IndexSchema.MODULES.get(((Number)row.get("module")).intValue()));
        Locator locator=locator(row); row.put("id",locator.identity());
        row.remove("sid"); row.remove("vid"); return Collections.unmodifiableMap(row);
    }
    static Locator locator(Map<String,Object> row) {
        Object offset=row.get("byte_offset");
        return new Locator((String)row.get("session"),(String)row.get("module"),offset==null?-1:((Number)offset).longValue(),(String)row.get("checkpoint_key"),((Number)row.get("item_position")).intValue());
    }
    static String title(String table,Map<String,Object> row) {
        return switch(table) {
            case "sessions" -> text(row,"label"); case "runs","combat" -> text(row,"map");
            case "players","loot_items" -> text(row,"name"); case "loot_bags" -> (join(row,"bag")+" bag").trim();
            case "timeline" -> text(row,"kind"); case "chat" -> text(row,"sender"); case "keypops" -> text(row,"item");
            case "fame" -> text(row,"class_name"); case "dungeon_totals" -> text(row,"dungeon"); default -> "";
        };
    }
    static String body(String table,Map<String,Object> row) {
        return switch(table) {
            case "runs","combat" -> text(row,"search_text"); case "timeline" -> join(row,"summary","detail","map");
            case "chat" -> text(row,"text"); case "loot_items","loot_bags" -> join(row,"name","dungeon","dropper","bag","tier");
            case "keypops" -> join(row,"player","item"); case "fame" -> join(row,"class_name","map"); default -> title(table,row);
        };
    }
    private static String text(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
    private static String join(Map<String,Object> row,String... keys) { return String.join(" ",Arrays.stream(keys).map(key->text(row,key)).filter(s->!s.isBlank()).toList()); }
    @Override public void close() {
        for (PreparedStatement p:statements.values()) try { p.close(); } catch (SQLException ignored) { }
        statements.clear(); documents.clear(); dirtyPlayers.clear(); sessionIds.clear(); visitIds.clear(); playerIds.clear();
    }
}
