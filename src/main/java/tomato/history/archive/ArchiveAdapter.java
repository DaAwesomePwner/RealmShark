package tomato.history.archive;

import tomato.history.SessionStore;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** A module supplies typed semantics; the engine owns global sorting, paging and export. */
public interface ArchiveAdapter<R,F,S extends Enum<S>> {
    @FunctionalInterface interface Sink<R> { void accept(ArchiveRow<R> row) throws IOException; }
    Class<R> rowType();
    String unit();
    List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<F,S> query);
    void scan(ReadSnapshot pin, ArchiveQuery<F,S> query, Sink<R> rows, Cancellation cancel) throws IOException;
    boolean matches(ArchiveRow<R> row, ArchiveQuery<F,S> query);
    Long time(ArchiveRow<R> row);
    default Long endTime(ArchiveRow<R> row) { return time(row); }
    /** Grouped adapters apply bounds to source records before reducing, then override this to true. */
    default boolean inBounds(ArchiveRow<R> row, ArchiveQuery<F,S> query) {
        return query.bounds().contains(time(row),endTime(row));
    }
    /** Reject unavailable operations, such as a custom period on counters without timestamps. */
    default void validate(ArchiveQuery<F,S> query) { }
    Comparator<R> comparator(S field);
    /** Freeze dependencies in the adapter instance before opening a result. */
    default Map<String,String> dependencies() { return Collections.emptyMap(); }
    /** Whole-scan counters for cards/denominators; omit unknown values instead of manufacturing zero. */
    default Map<String,Count> counts() { return Collections.emptyMap(); }
    final class Count {
        public final long value;
        public final String unit,population;
        public Count(long value,String unit,String population) {
            if(value<0)throw new IllegalArgumentException("Negative count");this.value=value;
            this.unit=Objects.requireNonNull(unit);this.population=Objects.requireNonNull(population);
        }
    }

    static <R,F,S extends Enum<S>> ArchiveAdapter<R,F,S> records(String module, Class<R> type, String unit,
            Function<R,Long> time, BiPredicate<R,ArchiveQuery<F,S>> matches, Function<S,Comparator<R>> order) {
        return new ArchiveAdapter<R,F,S>() {
            public Class<R> rowType() { return type; }
            public String unit() { return unit; }
            public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<F,S> query) {
                return Collections.singletonList(new ReadSnapshot.Source(query.resolvedScope(store), module));
            }
            public void scan(ReadSnapshot pin, ArchiveQuery<F,S> query, Sink<R> rows, Cancellation cancel) throws IOException {
                pin.read(pin.resolveScope(query.scope()), module, type, rows, cancel);
            }
            public boolean matches(ArchiveRow<R> row, ArchiveQuery<F,S> query) { return matches.test(row.value, query); }
            public Long time(ArchiveRow<R> row) { return time.apply(row.value); }
            public Comparator<R> comparator(S field) { return order.apply(field); }
        };
    }
}
