package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.realmshark.ParseDungeon;
import java.io.IOException;
import java.util.*;

/** Global saved-activity queries. Roster/resource payloads stay in the pin, never in visit pages. */
public final class ActivityQueries {
    private ActivityQueries() { }
    public enum Sort { TIME, MAP, DURATION, OUTCOME, EVIDENCE, ISSUES, GAPS, PLAYERS, DAMAGE, KIND, SUMMARY }
    public enum Outcome {
        COMPLETED("Completed"), LEFT("Left · completion unconfirmed"), IN_PROGRESS("In progress"), UNKNOWN("Unknown outcome");
        final String label;
        Outcome(String label) { this.label=label; }
        public String toString() { return label; }
    }
    public enum Evidence {
        VICTORY("Server victory"), DIALOGUE("Final-boss dialogue"), COUNTER("Completion counter"),
        OTHER("Other recorded evidence"), NOT_OBSERVED("Not observed");
        final String label;
        Evidence(String label) { this.label=label; }
        public String toString() { return label; }
    }
    public enum Presence { ANY, PRESENT, ABSENT }
    public enum Assignment { ALL, ASSIGNED, UNASSIGNED }
    public static final class Filters {
        public Set<Outcome> outcomes=new LinkedHashSet<>();
        public Set<Evidence> evidence=new LinkedHashSet<>();
        /** Exact event kinds; empty means all, including future kinds. */
        public Set<String> kinds=new LinkedHashSet<>();
        public Long minimumDurationMillis, maximumDurationMillis;
        public Presence captureIssues=Presence.ANY, timingGaps=Presence.ANY;
        public Assignment assignment=Assignment.ALL;
        public String visitSession="", visitId="";
    }
    public static final class Row {
        public String visitId, recordId, map, summary, detail, kind, completionEvidence, status;
        public Long time, end, durationMillis, damage;
        public long issues, gaps, progress, omitted;
        public int players;
        public Outcome outcome;
        public Evidence evidence;
        public boolean assigned;
        public Map<String,Object> values;
        public String coverage() { return issues+" capture issues · "+gaps+" timing gaps · completeness unknown"; }
    }
    public static ArchiveQuery<Filters,Sort> initial() {
        return ArchiveQuery.of(ArchiveQuery.CURRENT,new Filters(),Filters.class,Sort.TIME)
                .withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.TIME,ArchiveQuery.Direction.DESCENDING)));
    }
    public static ArchiveAdapter<Row,Filters,Sort> adapter(ActivityPanel.Mode mode) {
        return new Adapter(mode);
    }
    private static final class Adapter implements ArchiveAdapter<Row,Filters,Sort> {
        private final ActivityPanel.Mode mode;
        private long assigned, unassigned;
        Adapter(ActivityPanel.Mode mode) { this.mode=Objects.requireNonNull(mode); }
        public Class<Row> rowType() { return Row.class; }
        public String unit() { return mode==ActivityPanel.Mode.TIMELINE?"events":"visits"; }
        public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Filters,Sort> q) {
            String scope=q.resolvedScope(store);
            // An exact visit-linked Timeline also pins its runs so the linked outcome is read from the same revision.
            return mode==ActivityPanel.Mode.TIMELINE&&!ActivityRoutes.exactVisit(q.facets())?Collections.singletonList(new ReadSnapshot.Source(scope,"timeline"))
                    :Arrays.asList(new ReadSnapshot.Source(scope,"runs"),new ReadSnapshot.Source(scope,"timeline"));
        }
        public void validate(ArchiveQuery<Filters,Sort> q) {
            Filters f=q.facets();
            if(f.outcomes==null||f.evidence==null||f.kinds==null||f.captureIssues==null||f.timingGaps==null||f.assignment==null
                    ||f.visitId==null||f.visitSession==null||f.outcomes.contains(null)||f.evidence.contains(null)||f.kinds.contains(null))
                throw new IllegalArgumentException("Unsupported activity filter");
            if((f.minimumDurationMillis!=null&&f.minimumDurationMillis<0)||(f.maximumDurationMillis!=null&&f.maximumDurationMillis<0)
                    ||(f.minimumDurationMillis!=null&&f.maximumDurationMillis!=null&&f.minimumDurationMillis>f.maximumDurationMillis))
                throw new IllegalArgumentException("Duration requires 0 ≤ minimum ≤ maximum");
            if(f.visitId.isEmpty()!=f.visitSession.isEmpty())throw new IllegalArgumentException("A visit link requires both session and visit ID");
            if(!f.visitSession.isEmpty()&&!f.visitSession.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("Invalid visit session");
        }
        public void scan(ReadSnapshot pin,ArchiveQuery<Filters,Sort> q,Sink<Row> sink,Cancellation cancel)throws IOException {
            assigned=unassigned=0;
            if(mode==ActivityPanel.Mode.TIMELINE)pin.read("timeline",ActivityJournal.Entry.class,source->{
                Row row=event(source.value);ArchiveRow<Row> projected=new ArchiveRow<>(source.ref,row);
                if(inBounds(projected,q)&&matches(projected,q)){if(row.assigned)assigned++;else unassigned++;}
                sink.accept(projected);
            },cancel);
            else pin.read("runs",ActivityJournal.Visit.class,source->{
                if(mode==ActivityPanel.Mode.COMBAT||ParseDungeon.isDungeon(source.value.map))
                    sink.accept(new ArchiveRow<>(source.ref,visit(source.value)));
            },cancel);
        }
        public Long time(ArchiveRow<Row> row) { return row.value.time; }
        public Long endTime(ArchiveRow<Row> row) { return row.value.end; }
        public boolean matches(ArchiveRow<Row> source,ArchiveQuery<Filters,Sort> q) {
            Row r=source.value;Filters f=q.facets();
            if(!f.visitId.isEmpty()&&(!f.visitId.equals(r.visitId)||!f.visitSession.equals(source.ref.session)))return false;
            if(mode==ActivityPanel.Mode.TIMELINE) {
                if(!f.kinds.isEmpty()&&!f.kinds.contains(r.kind))return false;
                if(f.assignment==Assignment.ASSIGNED&&!r.assigned||f.assignment==Assignment.UNASSIGNED&&r.assigned)return false;
            } else if(!matchesVisit(r,f))return false;
            String text=(r.map+" "+r.kind+" "+r.summary+" "+r.detail+" "+r.completionEvidence+" "+r.outcome
                    +(mode==ActivityPanel.Mode.TIMELINE?" "+SessionStore.JSON.toJson(r.values):"")).toLowerCase(Locale.ROOT);
            return text.contains(q.text().trim().toLowerCase(Locale.ROOT));
        }
        public Comparator<Row> comparator(Sort field) {
            switch(field) {
                case TIME:return Comparator.comparing(r->r.time,Comparator.nullsLast(Comparator.naturalOrder()));
                case DURATION:return Comparator.comparing(r->r.durationMillis,Comparator.nullsLast(Comparator.naturalOrder()));
                case DAMAGE:return Comparator.comparing(r->r.damage,Comparator.nullsLast(Comparator.naturalOrder()));
                case ISSUES:return Comparator.comparingLong(r->r.issues);
                case GAPS:return Comparator.comparingLong(r->r.gaps);
                case PLAYERS:return Comparator.comparingInt(r->r.players);
                default:return Comparator.comparing(r->field==Sort.MAP?r.map:field==Sort.OUTCOME?Objects.toString(r.outcome,"")
                        :field==Sort.EVIDENCE?Objects.toString(r.evidence,""):field==Sort.KIND?r.kind:r.summary,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            }
        }
        public Map<String,Count> counts() {
            if(mode!=ActivityPanel.Mode.TIMELINE)return Collections.emptyMap();
            Map<String,Count> counts=new LinkedHashMap<>();
            counts.put("assigned",new Count(assigned,"events","Matching events with a recorded visit ID"));
            counts.put("unassigned",new Count(unassigned,"events","Matching events without a recorded visit ID"));
            return counts;
        }
        public Map<String,String> dependencies() {
            return Collections.singletonMap("activityProjection","1; stored epoch times; nulls last ascending/first descending; no timestamp joins");
        }
    }
    private static boolean presence(Presence filter,long value) {
        return filter==Presence.ANY||filter==Presence.PRESENT&&value>0||filter==Presence.ABSENT&&value==0;
    }
    /** Shared pure facet predicate for both the complete saved query and the retained live snapshot. */
    public static boolean matchesVisit(Row r,Filters f) {
        return (f.outcomes.isEmpty()||f.outcomes.contains(r.outcome))&&(f.evidence.isEmpty()||f.evidence.contains(r.evidence))
                &&(f.minimumDurationMillis==null||r.durationMillis!=null&&r.durationMillis>=f.minimumDurationMillis)
                &&(f.maximumDurationMillis==null||r.durationMillis!=null&&r.durationMillis<=f.maximumDurationMillis)
                &&presence(f.captureIssues,r.issues)&&presence(f.timingGaps,r.gaps);
    }
    public static Row visit(ActivityJournal.Visit v) {
        Row r=new Row();r.visitId=v.id;r.recordId=v.id;r.map=v.map;r.time=knownTime(v.started);r.end=knownTime(v.lastSeen);
        r.durationMillis=r.time==null||r.end==null||r.end<r.time?null:r.end-r.time;
        r.completionEvidence=Objects.toString(v.completionEvidence,"");r.status=v.status;
        r.outcome=!r.completionEvidence.isEmpty()||"Completed".equals(v.status)?Outcome.COMPLETED
                :v.ended>0?Outcome.LEFT:r.time==null?Outcome.UNKNOWN:Outcome.IN_PROGRESS;
        String evidence=r.completionEvidence.toLowerCase(Locale.ROOT);
        r.evidence=evidence.isEmpty()?Evidence.NOT_OBSERVED:evidence.contains("counter")?Evidence.COUNTER
                :evidence.contains("victory")?Evidence.VICTORY:evidence.contains("dialogue")?Evidence.DIALOGUE:Evidence.OTHER;
        r.issues=v.issues;r.gaps=v.timingGaps;r.progress=v.exaltIncrease;r.players=v.inspectedPlayerCount;
        r.damage=v.damageTracked?v.totalDamage:null;r.omitted=v.timelineOmitted;
        r.summary=r.outcome+" · "+r.evidence;r.detail=Objects.toString(v.endReason,"");r.kind="Visit";
        r.assigned=v.id!=null&&!v.id.isEmpty();return r;
    }
    public static Row event(ActivityJournal.Entry e) {
        Row r=new Row();r.recordId=e.id;r.visitId=e.visitId;r.map=e.map;r.time=knownTime(e.time);
        r.kind=Objects.toString(e.kind,"Unknown activity");r.summary=ActivitySummaries.event(e);r.detail=Objects.toString(e.detail,"");
        r.values=e.values;r.assigned=e.visitId!=null&&!e.visitId.isEmpty();return r;
    }
    private static Long knownTime(long time) { return time==0?null:time; }
    /** The pinned visit with this exact session and recorded visit ID, or null when that revision lacks it. */
    public static ActivityJournal.Visit readLinkedVisit(ArchiveResult.Lease<Row> lease,String session,String visitId,Cancellation cancel)throws IOException {
        if(session==null||session.isEmpty()||visitId==null||visitId.isEmpty())return null;
        ActivityJournal.Visit[] found={null};
        // Read the whole pinned scope: a missing (deleted/imported) session is "absent", not a read failure.
        lease.readSource(SessionStore.ALL,"runs",ActivityJournal.Visit.class,row->{
            if(row.ref.session.equals(session)&&visitId.equals(row.value.id))found[0]=row.value;
        },cancel);
        return found[0];
    }
    /** Full details use the source Ref AND the recorded domain ID, never time/map guesses. */
    public static ActivityJournal.Visit readVisit(ArchiveResult.Lease<Row> lease,ArchiveRow<Row> selected,Cancellation cancel)throws IOException {
        ActivityJournal.Visit[] found={null};
        lease.readSource(selected.ref.session,"runs",ActivityJournal.Visit.class,row->{
            if(row.ref.equals(selected.ref)&&Objects.equals(row.value.id,selected.value.visitId)){
                row.value.normalizePlayers();found[0]=row.value;
            }
        },cancel);
        if(found[0]==null)throw new IOException("Selected visit is absent from this pinned revision");
        return found[0];
    }
}
