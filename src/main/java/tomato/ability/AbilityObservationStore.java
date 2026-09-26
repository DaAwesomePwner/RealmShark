package tomato.ability;

import java.util.*;

/** Session-only bounded retention. Reset starts a fresh evidence window. */
public final class AbilityObservationStore {
    private static final AbilityObservationStore APPLICATION = new AbilityObservationStore(5000);
    public static AbilityObservationStore application() { return APPLICATION; }
    private final int limit;
    private final Deque<AbilityObservation> rows = new ArrayDeque<>();
    private long evicted, omitted, revision, resetAt = System.currentTimeMillis();
    public AbilityObservationStore(int limit) { if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Invalid retention limit"); this.limit = limit; }
    public synchronized void add(AbilityObservation row) { Objects.requireNonNull(row); if(rows.size()==limit){rows.removeFirst();evicted++;} rows.addLast(row);revision++; }
    public synchronized void omit() { omitted++;revision++; }
    public synchronized void reset() { rows.clear();evicted=omitted=0;resetAt=System.currentTimeMillis();revision++; }
    public synchronized Snapshot snapshot(String query, String heuristic, Long from, Long until) {
        List<AbilityObservation> matches = new ArrayList<>();
        for (Iterator<AbilityObservation> it=rows.descendingIterator();it.hasNext();) {
            AbilityObservation row=it.next();
            if(row.matches(query==null?"":query)&&(heuristic==null||heuristic.equals(row.heuristic))
                    &&(from==null||row.observedAt>=from)&&(until==null||row.observedAt<until)) matches.add(row);
        }
        return new Snapshot(matches,rows.size(),limit,evicted,omitted,revision,resetAt);
    }
    public static final class Snapshot {
        public final List<AbilityObservation> rows;
        public final int retained,limit;
        public final long evicted,omitted,revision,resetAt;
        Snapshot(List<AbilityObservation> rows,int retained,int limit,long evicted,long omitted,long revision,long resetAt){
            this.rows=Collections.unmodifiableList(rows);this.retained=retained;this.limit=limit;this.evicted=evicted;this.omitted=omitted;this.revision=revision;this.resetAt=resetAt;
        }
    }
}
