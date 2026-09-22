package tomato.gui.stats;

import java.time.*;
import java.util.*;
import tomato.history.archive.*;

/** Module-owned query intent shared by saved projections and live loot facets. */
public final class LootQuery {
    private LootQuery() { }
    public enum View {
        OCCURRENCES("Item occurrences"), ITEMS("All Items"), POTIONS("Stat Potions"), WHITES("Whites"),
        BAGS("By Bag"), RECENT("Recent Drops"), DUNGEONS("By Dungeon"), UTS("UTs"), STS("STs"), TIERED("Tiered"),
        RATES("Dungeon loot profile"), SESSIONS("Session comparison"), FAME("Character fame"),
        COUNTERS("Dungeon statistics"), ENEMIES("Enemy hit events"), SOURCES("Loot by source");
        final String label; View(String label){this.label=label;} public String toString(){return label;}
        boolean loot(){return ordinal()<=TIERED.ordinal();}
        boolean counters(){return this==COUNTERS||this==ENEMIES||this==SOURCES;}
    }
    public enum Sort { TIME, NAME, DUNGEON, BAG, ITEM_ID, SLOTS, APPLIED, COUNT, BAGS, ITEMS, RUNS, MILLIS, RATE, GAIN, HITS,
        FIRST_FAME,LAST_FAME,PER_RUN,UT_HOUR,WHITE_RUN,UT_RUN,ST_RUN,POTION_RUN,WHITES,UTS,STS,POTIONS,COMPLETED,UNKNOWN_RUNS,IMPORTED_RUNS,DAMAGE,CHARACTER,ENEMY,TIER,RARITY,AVERAGE_MILLIS }
    public enum Kind { ANY, UT_EQUIPMENT, ST, STAT_POTION, HIGH_TIER }
    public enum Unknown { INCLUDE, EXCLUDE, ONLY }
    public static final class Range {
        public Integer min,max;
        public Unknown unknown=Unknown.INCLUDE;
        public boolean matches(Integer value){return value==null?unknown!=Unknown.EXCLUDE:unknown!=Unknown.ONLY&&(min==null||value>=min)&&(max==null||value<=max);}
        void validate(){if(unknown==null||min!=null&&min<0||max!=null&&max<0||min!=null&&max!=null&&min>max)throw new IllegalArgumentException("Invalid enchant range");}
    }
    public static final class Facets {
        public View view=View.OCCURRENCES;
        public Set<String> bags=new LinkedHashSet<>(),dungeons=new LinkedHashSet<>(),rarities=new LinkedHashSet<>(),tiers=new LinkedHashSet<>();
        public Kind kind=Kind.ANY;
        public Range slots=new Range(),applied=new Range();
        public String character="",enemy="";
        public void validate(){
            if(view==null||kind==null||bags==null||dungeons==null||rarities==null||tiers==null||slots==null||applied==null||character==null||enemy==null)throw new IllegalArgumentException("Incomplete loot query");
            slots.validate();applied.validate();
            for(Set<String> values:Arrays.asList(bags,dungeons,rarities,tiers)){if(values.size()>256||values.contains(null))throw new IllegalArgumentException("Invalid facet selection (maximum 256 values)");for(String value:values)if(value.length()>512)throw new IllegalArgumentException("Facet labels must not exceed 512 characters");}
        }
        boolean location(String bag,String dungeon){return (bags.isEmpty()||bags.contains(bag))&&(dungeons.isEmpty()||dungeons.contains(dungeon));}
        boolean dungeon(String dungeon){return dungeons.isEmpty()||dungeons.contains(dungeon);}
        boolean item(LootDashboard.Item item){
            Integer s=slots(item),a=applied(item);
            return slots.matches(s)&&applied.matches(a)&&(rarities.isEmpty()||rarities.contains(rarity(item)))
                &&(tiers.isEmpty()||tiers.contains(tier(item)))&&kind(item,kind)
                &&(view!=View.POTIONS||item.potion)&&(view!=View.UTS||item.ut)&&(view!=View.STS||item.st)&&(view!=View.TIERED||item.highTier);
        }
        boolean itemRestricted(){return kind!=Kind.ANY||!rarities.isEmpty()||!tiers.isEmpty()||slots.min!=null||slots.max!=null||slots.unknown!=Unknown.INCLUDE||applied.min!=null||applied.max!=null||applied.unknown!=Unknown.INCLUDE||view==View.POTIONS||view==View.UTS||view==View.STS||view==View.TIERED;}
    }
    static boolean kind(LootDashboard.Item i,Kind kind){return kind==Kind.ANY||kind==Kind.UT_EQUIPMENT&&i.ut||kind==Kind.ST&&i.st||kind==Kind.STAT_POTION&&i.potion||kind==Kind.HIGH_TIER&&i.highTier;}
    static Integer slots(LootDashboard.Item i){return i.enchants==null||i.enchants.slots<0?null:i.enchants.slots;}
    static Integer applied(LootDashboard.Item i){return i.enchants==null||i.enchants.applied<0?null:i.enchants.applied;}
    static String rarity(LootDashboard.Item i){Integer n=slots(i);return n==null?"Unknown":n==0?"Common / Unenchanted":i.enchants.rarity();}
    static String tier(LootDashboard.Item i){return i.tier==null||i.tier.equals("—")?"Unknown":i.tier;}
    static String canonical(String name){return tomato.backend.data.DungeonStatData.Snapshot.canonicalName(name);}
    static boolean contains(String value,String query){return value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));}
    static String variant(LootDashboard.Item i){return i.id+"/"+slots(i)+"/"+applied(i);}
    static Long time(long time){return time>0?time:null;}
    public static ArchiveQuery<Facets,Sort> initial(boolean statistics){
        Facets f=new Facets();if(statistics)f.view=View.SESSIONS;
        return ArchiveQuery.of(ArchiveQuery.CURRENT,f,Facets.class,Sort.TIME).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.TIME,ArchiveQuery.Direction.DESCENDING)));
    }
    /** Offsets are explicit; local input must resolve unambiguously in the supplied zone. */
    public static Long resolveTime(String input,ZoneId zone){
        if(input.trim().isEmpty())return null;
        try{return OffsetDateTime.parse(input.trim()).toInstant().toEpochMilli();}catch(java.time.format.DateTimeParseException ignored){ }
        LocalDateTime local=LocalDateTime.parse(input.trim());List<ZoneOffset> offsets=zone.getRules().getValidOffsets(local);
        if(offsets.size()!=1)throw new IllegalArgumentException("Ambiguous or missing local time; supply an explicit UTC offset");
        return local.toInstant(offsets.get(0)).toEpochMilli();
    }
    /** One lightweight row schema gives every view explicit analytical CSV columns. */
    public static final class Row {
        public String type,name="",dungeon="",bag="",dropper="",session="",visitId="",tier="",rarity="",evidence="",className="",build="";
        public Integer itemId,slots,applied,character,enemyId;
        public Long time,count,bags,items,runs,millis,averageMillis,whites,uts,sts,potions,completed,unknownRuns,importedRuns,hits,damage;
        public Double perRun,perHour,utPerHour,whitesPerRun,utPerRun,stPerRun,potionsPerRun,firstFame,lastFame,gain;
        public Boolean ongoingActivity;public long sourceSummaries;
        static Row item(String session,LootDashboard.Drop d,LootDashboard.Item i,String dungeon){Row r=new Row();r.type="occurrence";r.session=session;r.visitId=Objects.toString(d.visitId,"");r.time=time(d.time);r.name=i.name;r.itemId=i.id;r.dungeon=dungeon;r.bag=d.bag;r.dropper=d.dropper;r.tier=tier(i);r.rarity=rarity(i);r.slots=slots(i);r.applied=applied(i);r.count=1L;return r;}
    }
    static Comparator<Row> comparator(Sort sort){
        switch(sort){
            case NAME:return Comparator.comparing(r->r.name,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case DUNGEON:return Comparator.comparing(r->r.dungeon,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case BAG:return Comparator.comparing(r->r.bag,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case ITEM_ID:return Comparator.comparing(r->r.itemId,Comparator.nullsLast(Comparator.naturalOrder()));
            case SLOTS:return Comparator.comparing(r->r.slots,Comparator.nullsLast(Comparator.naturalOrder()));
            case APPLIED:return Comparator.comparing(r->r.applied,Comparator.nullsLast(Comparator.naturalOrder()));
            case RATE:return Comparator.comparing(r->r.perHour,Comparator.nullsLast(Comparator.naturalOrder()));
            case GAIN:return Comparator.comparing(r->r.gain,Comparator.nullsLast(Comparator.naturalOrder()));
            case TIER:return Comparator.comparing(r->r.tier,Comparator.nullsLast(Comparator.naturalOrder()));
            case RARITY:return Comparator.comparing(r->r.rarity,Comparator.nullsLast(Comparator.naturalOrder()));
            case CHARACTER:return Comparator.comparing(r->r.character,Comparator.nullsLast(Comparator.naturalOrder()));
            case ENEMY:return Comparator.comparing(r->r.enemyId,Comparator.nullsLast(Comparator.naturalOrder()));
            case AVERAGE_MILLIS:return Comparator.comparing(r->r.averageMillis,Comparator.nullsLast(Comparator.naturalOrder()));
            case FIRST_FAME:case LAST_FAME:case PER_RUN:case UT_HOUR:case WHITE_RUN:case UT_RUN:case ST_RUN:case POTION_RUN:
                return Comparator.comparing(r->sort==Sort.FIRST_FAME?r.firstFame:sort==Sort.LAST_FAME?r.lastFame:sort==Sort.PER_RUN?r.perRun:sort==Sort.UT_HOUR?r.utPerHour:sort==Sort.WHITE_RUN?r.whitesPerRun:sort==Sort.UT_RUN?r.utPerRun:sort==Sort.ST_RUN?r.stPerRun:r.potionsPerRun,Comparator.nullsLast(Comparator.naturalOrder()));
            default:return Comparator.comparing(r->sort==Sort.TIME?r.time:sort==Sort.COUNT?r.count:sort==Sort.BAGS?r.bags:sort==Sort.ITEMS?r.items:sort==Sort.RUNS?r.runs:sort==Sort.MILLIS?r.millis:sort==Sort.HITS?r.hits:sort==Sort.WHITES?r.whites:sort==Sort.UTS?r.uts:sort==Sort.STS?r.sts:sort==Sort.POTIONS?r.potions:sort==Sort.COMPLETED?r.completed:sort==Sort.UNKNOWN_RUNS?r.unknownRuns:sort==Sort.IMPORTED_RUNS?r.importedRuns:r.damage,Comparator.nullsLast(Comparator.naturalOrder()));
        }
    }
}
