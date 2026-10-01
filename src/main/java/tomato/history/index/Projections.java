package tomato.history.index;

import com.google.gson.*;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.InspectSnapshot;
import tomato.gui.activity.ActivitySummaries;
import tomato.history.RunOutcomeRule;
import tomato.history.SessionStore;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;

/** Pure, allowlisted saved-record projections. No assets, UI objects, file reads or account-name inference. */
public final class Projections {
    public record Context(Locator locator, long ended, boolean current, String version) {}
    public record Row(String table, Kind kind, Locator locator, Long time, String visitId,
                      String title, String body, String names, Map<String,Object> columns, Map<String,String> players) {
        public Row {
            title = clean(title); body = clean(body); names = clean(names); visitId = nullableClean(visitId);
            Map<String,Object> safe = new LinkedHashMap<>();
            columns.forEach((key, value) -> safe.put(key, value instanceof String && !(table.equals("fame") && key.equals("account"))
                    ? clean((String)value) : value));
            columns = Collections.unmodifiableMap(safe);
            Map<String,String> people = new LinkedHashMap<>();
            players.forEach((key, value) -> people.put(clean(key), clean(value)));
            players = Collections.unmodifiableMap(people);
        }
    }
    private static final Pattern HASH = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final Set<String> DIAGNOSTICS = Set.of("Resources", "Capture issue", "Ownership check");
    private static final Map<String,BiFunction<Context,JsonElement,List<Row>>> PROJECTORS;
    static {
        Map<String,BiFunction<Context,JsonElement,List<Row>>> p = new LinkedHashMap<>();
        p.put("session", Projections::session); p.put("runs", Projections::run); p.put("loot", Projections::loot);
        p.put("timeline", Projections::timeline); p.put("chat", Projections::chat); p.put("chat-stars", Projections::stars);
        p.put("keypops", Projections::keypops); p.put("fame", Projections::fame); p.put("fame-latest", Projections::fame);
        p.put("fame-snapshots", Projections::legacyFame); p.put("encounters", Projections::combat);
        p.put("dungeon-totals", Projections::dungeons); PROJECTORS = Collections.unmodifiableMap(p);
    }
    private Projections() {}
    public static Set<String> modules() { return PROJECTORS.keySet(); }
    public static List<Row> project(Context context, Object record) {
        BiFunction<Context,JsonElement,List<Row>> projector = PROJECTORS.get(context.locator.module());
        if (projector == null) return List.of();
        JsonElement json = record instanceof JsonElement ? (JsonElement)record : SessionStore.JSON.toJsonTree(record);
        if (json == null || json.isJsonNull()) throw new IllegalArgumentException("Missing record");
        return List.copyOf(projector.apply(context, json));
    }
    static String clean(String value) { return value == null ? "" : HASH.matcher(value).replaceAll("[redacted]"); }
    private static String nullableClean(String value) { return value == null ? null : clean(value); }
    private static Row row(Context c, String table, Kind kind, Long time, String visit, String title, String body,
                           String names, Map<String,Object> columns, Map<String,String> players) {
        return new Row(table, kind, c.locator, time, visit, title, body, names, columns, players);
    }
    private static List<Row> session(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject();
        return List.of(row(c, "sessions", Kind.SESSION, number(o,"started"), null, text(o,"label"), "", "",
                fields("label",text(o,"label"),"version",text(o,"version"),"started",number(o,"started"),"ended",number(o,"ended")), Map.of()));
    }
    private static List<Row> run(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject();
        ActivityJournal.Visit v = SessionStore.JSON.fromJson(o, ActivityJournal.Visit.class);
        if (v.id == null || v.id.isBlank()) throw new IllegalArgumentException("Run without visit ID");
        RunOutcomeRule outcome = RunOutcomeRule.of(v, c.ended > 0, c.ended <= 0 && (c.current || "Imported".equals(c.version)));
        Long start = positive(v.started), end = positive(v.lastSeen);
        // ActivityQueries.visit bounds duration by the last observation, not an inferred session duration.
        Long duration = start == null || end == null || end < start ? null : end - start;
        Map<String,String> people = new LinkedHashMap<>(); List<String> classes = new ArrayList<>();
        JsonObject inspected = object(o,"inspectedPlayers");
        for (Map.Entry<String,JsonElement> entry : inspected.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject player = entry.getValue().getAsJsonObject(); String name = "";
            for (JsonElement stat : array(player,"stats")) if (stat.isJsonObject()) {
                JsonObject s = stat.getAsJsonObject();
                if ("NAME_STAT".equals(text(s,"statType")) || Long.valueOf(31).equals(number(s,"statTypeNum"))) {
                    name = text(s,"stringStatValue"); break;
                }
            }
            if (name.isBlank() && entry.getKey().startsWith("player:")) name = entry.getKey().substring(7);
            player(people,name); classes.add(text(player,"className"));
        }
        for (String key : object(o,"playerDamage").keySet()) if (key.startsWith("player:")) player(people,key.substring(7));
        String roster = String.join(" ", people.values()); String map = text(o,"map");
        return List.of(row(c,"runs",Kind.RUN,start,v.id,map,join(outcome.label,v.status,v.completionEvidence,v.endReason,roster,String.join(" ",classes)),
                join(map,roster), fields("map",map,"started",start,"ended",positive(v.ended),"duration",duration,
                "outcome",outcome.label,"damage",v.damageTracked?v.totalDamage:null,"players",v.inspectedPlayerCount,
                "roster_size",v.rosterSize,"issues",v.issues,"gaps",v.timingGaps,"progress",v.exaltIncrease,
                "evidence",v.completionEvidence,"status",v.status),people));
    }
    private static List<Row> loot(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject();
        if (!o.has("items") || !o.get("items").isJsonArray()) throw new IllegalArgumentException("Bag contents not recorded");
        String bag = text(o,"bag"), map = area(text(o,"dungeon")), dropper = known(text(o,"dropper"));
        JsonObject visit = object(object(o,"context"),"visit");
        String visitId = exactVisit(c, visit,"sessionId"); Long time = positive(number(o,"time"));
        List<Row> rows = new ArrayList<>();
        rows.add(row(c,"loot_bags",Kind.LOOT,time,visitId,join(bag,"bag"),join(map,dropper),map,
                fields("dungeon",map,"bag",bag.isBlank()?null:bag,"dropper",dropper,"white",whiteBag(bag),"items",array(o,"items").size()),Map.of()));
        int position = 0;
        for (JsonElement item : array(o,"items")) {
            JsonObject i = item.getAsJsonObject(), enchants = object(i,"enchants");
            Long slots = nonnegative(number(enchants,"slots")), applied = nonnegative(number(enchants,"applied"));
            EnchantInfo enchant = EnchantInfo.ofSlotCount(slots==null?null:slots.intValue());
            if (Boolean.TRUE.equals(bool(i,"potion"))) enchant=EnchantInfo.notRecorded();
            else if (i.has("enchantEvidence") && i.get("enchantEvidence").isJsonObject()) {
                ParseEnchants.Evidence evidence=SessionStore.JSON.fromJson(i.get("enchantEvidence"),ParseEnchants.Evidence.class);
                if (evidence.state!=null && evidence.state!=ParseEnchants.EvidenceState.LEGACY_NOT_RECORDED) enchant=EnchantInfo.fromEvidence(evidence);
            }
            String tier = text(i,"tier"); if (tier.isBlank() || "—".equals(tier)) tier = null;
            String name = text(i,"name");
            Context at = new Context(c.locator.at(position++),c.ended,c.current,c.version);
            rows.add(row(at,"loot_items",Kind.LOOT,time,visitId,name,join(map,dropper,bag,tier),join(name,map,dropper),
                    fields("item_id",number(i,"id"),"name",name,"dungeon",map,"dropper",dropper,"bag",bag,
                    "tier",tier,"rarity",enchant.rarity().label,
                    "enchant_slots",slots,"enchant_applied",applied,"ut",bool(i,"ut"),"st",bool(i,"st"),
                    "high_tier",bool(i,"highTier"),"potion",bool(i,"potion")),Map.of()));
        }
        return rows;
    }
    private static List<Row> timeline(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject();
        if (DIAGNOSTICS.contains(text(o,"kind"))) return List.of();
        ActivityJournal.Entry e = SessionStore.JSON.fromJson(o,ActivityJournal.Entry.class);
        String kind = e.kind == null ? "Unknown activity" : e.kind;
        String summary = ActivitySummaries.event(e);
        return List.of(row(c,"timeline",Kind.TIMELINE,positive(e.time),blank(e.visitId),kind,
                join(summary,e.detail,safeValues(object(o,"values"))),e.map,
                fields("record_id",e.id,"map",e.map,"kind",kind,"summary",summary,"detail",e.detail),Map.of()));
    }
    private static List<Row> chat(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject(); String sender = text(o,"sender"), recipient = text(o,"recipient");
        String received = text(o,"received"); Map<String,String> people = new LinkedHashMap<>(); player(people,sender);
        // Chat has a local wall clock only. Keep it verbatim; UTC is a sortable encoding, not an inferred capture instant.
        Long time = received.isBlank()?null:LocalDateTime.parse(received).toInstant(ZoneOffset.UTC).toEpochMilli();
        return List.of(row(c,"chat",Kind.CHAT,time,null,sender,join(text(o,"text"),recipient),join(sender,recipient),
                fields("message_id",text(o,"id"),"received",received,"channel",text(o,"channel"),"sender",sender,
                "recipient",recipient,"own",bool(o,"ownMessage"),"ignored",bool(o,"gameIgnored")),people));
    }
    private static List<Row> stars(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject();
        return List.of(row(c,"chat_stars",null,number(o,"changed"),null,"","","",
                fields("message_id",text(o,"id"),"changed",number(o,"changed"),"starred",bool(o,"starred")),Map.of()));
    }
    private static List<Row> keypops(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject(); String name = text(o,"player"), item = text(o,"item");
        Map<String,String> people = new LinkedHashMap<>(); player(people,name);
        String time = text(o,"time");
        return List.of(row(c,"keypops",Kind.KEYPOP,time.isBlank()?null:Instant.parse(time).toEpochMilli(),null,item,name,join(item,name),
                fields("record_id",text(o,"id"),"kind",text(o,"kind"),"player",name,"item",item),people));
    }
    private static List<Row> fame(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject(); String account = text(o,"account");
        if (!account.matches("[0-9a-f]{64}")) account = null;
        String map = text(o,"map"), cls = text(o,"className");
        return List.of(row(c,"fame",Kind.FAME,positive(number(o,"time")),exactVisit(c,o,"visitSession"),cls,map,map,
                fields("account",account,"character",number(o,"character"),"fame",decimal(o,"fame"),"class_name",cls,"map",map),Map.of()));
    }
    private static List<Row> legacyFame(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject(); List<Row> rows = new ArrayList<>(); int position = 0;
        JsonObject classes = object(o,"characterClassNames");
        for (Map.Entry<String,JsonElement> character : object(o,"characterFameData").entrySet()) {
            for (JsonElement sample : character.getValue().getAsJsonArray()) {
                JsonObject s = sample.getAsJsonObject().deepCopy(); s.addProperty("character",Integer.parseInt(character.getKey()));
                if (classes.has(character.getKey())) s.add("className",classes.get(character.getKey()));
                rows.addAll(fame(new Context(c.locator.at(position++),c.ended,c.current,c.version),s));
            }
        }
        return rows;
    }
    private static List<Row> combat(Context c, JsonElement value) {
        JsonObject o = value.getAsJsonObject(); String id = text(o,"recordingId");
        if (id.isBlank()) throw new IllegalArgumentException("Combat without recording ID");
        Map<String,String> people = new LinkedHashMap<>(); List<String> bosses = new ArrayList<>();
        for (JsonElement p : array(o,"players")) if (p.isJsonObject()) player(people,text(p.getAsJsonObject(),"name"));
        for (JsonElement b : array(o,"bosses")) if (b.isJsonObject()) bosses.add(text(b.getAsJsonObject(),"name"));
        String map = text(o,"map"), names = join(map,text(o,"mapName"),String.join(" ",people.values()),String.join(" ",bosses));
        return List.of(row(c,"combat",Kind.COMBAT,positive(number(o,"startedAt")),exactVisit(c,o,"visitSession"),map,names,names,
                fields("recording_id",id,"map",map,"started",number(o,"startedAt"),"elapsed",number(o,"elapsedMs"),
                "damage",number(o,"totalDamage"),"deaths",number(o,"deaths"),"players",number(o,"contributors")),people));
    }
    private static List<Row> dungeons(Context c, JsonElement value) {
        List<Row> rows = new ArrayList<>(); int position = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject o = element.getAsJsonObject(); String name = text(o,"name");
            long hits = 0, items = 0;
            for (Map.Entry<String,JsonElement> n : object(o,"hits").entrySet()) hits += n.getValue().getAsLong();
            for (Map.Entry<String,JsonElement> enemy : object(o,"loot").entrySet())
                for (Map.Entry<String,JsonElement> n : enemy.getValue().getAsJsonObject().entrySet()) items += n.getValue().getAsLong();
            rows.add(row(new Context(c.locator.at(position++),c.ended,c.current,c.version),"dungeon_totals",Kind.DUNGEON,null,null,name,"",name,
                    fields("dungeon",name,"visits",number(o,"visits"),"duration",number(o,"time"),"hits",hits,"items",items),Map.of()));
        }
        return rows;
    }
    private static void player(Map<String,String> people, String name) {
        if (name == null || name.split(",",2)[0].isBlank() || HASH.matcher(name).find()) return;
        people.putIfAbsent(InspectSnapshot.playerKey(0,name),name);
    }
    private static String exactVisit(Context c, JsonObject o, String sessionField) {
        return c.locator.session().equals(text(o,sessionField)) ? blank(text(o,"visitId")) : null;
    }
    // Same saved-field rules as LootFacts; its bag reader requires package-private Swing dashboard record types.
    static boolean whiteBag(String bag) { return "White".equals(bag) || "B.White".equals(bag); }
    private static String known(String value) { return value == null || value.isBlank() || "Unknown".equals(value)?null:value; }
    private static String area(String value) { return "Unrecognized area".equals(value)?null:known(value); }
    private static String blank(String value) { return value == null || value.isBlank()?null:value; }
    private static String join(String... values) { return String.join(" ",Arrays.stream(values).filter(Objects::nonNull).filter(s->!s.isBlank()).toList()); }
    private static String safeValues(JsonObject values) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String,JsonElement> entry : values.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("account") || key.contains("token") || key.contains("password")) continue;
            if (entry.getValue().isJsonPrimitive()) result.add(clean(entry.getValue().getAsString()));
        }
        return String.join(" ",result);
    }
    static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    static String text(JsonObject o,String key) { return !o.has(key)||o.get(key).isJsonNull()?"":o.get(key).getAsString(); }
    static JsonObject object(JsonObject o,String key) { return o.has(key)&&o.get(key).isJsonObject()?o.getAsJsonObject(key):new JsonObject(); }
    private static JsonArray array(JsonObject o,String key) { return o.has(key)&&o.get(key).isJsonArray()?o.getAsJsonArray(key):new JsonArray(); }
    private static Long number(JsonObject o,String key) { return !o.has(key)||o.get(key).isJsonNull()?null:o.get(key).getAsLong(); }
    private static Double decimal(JsonObject o,String key) { return !o.has(key)||o.get(key).isJsonNull()?null:o.get(key).getAsDouble(); }
    private static Boolean bool(JsonObject o,String key) { return !o.has(key)||o.get(key).isJsonNull()?null:o.get(key).getAsBoolean(); }
    private static Long positive(Long value) { return value==null||value<=0?null:value; }
    private static Long nonnegative(Long value) { return value==null||value<0?null:value; }
}
