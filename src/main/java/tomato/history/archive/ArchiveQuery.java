package tomato.history.archive;

import com.google.gson.*;
import tomato.history.SessionStore;
import java.time.ZoneId;
import java.util.*;

/** Serializable query intent. Facets are copied through their declared type at the boundary. */
public final class ArchiveQuery<F, S extends Enum<S>> {
    public static final String CURRENT = "@current";
    public enum Direction { ASCENDING, DESCENDING }
    public enum TimeMode { ENTRY, OVERLAP }

    public static final class Order<S extends Enum<S>> {
        public final S field;
        public final Direction direction;
        public Order(S field, Direction direction) {
            this.field = Objects.requireNonNull(field); this.direction = Objects.requireNonNull(direction);
        }
    }

    public static final class Bounds {
        public final Long from, until;
        public final String zone;
        public final TimeMode mode;
        public final boolean includeUnknown;
        public Bounds(Long from, Long until, ZoneId zone, TimeMode mode, boolean includeUnknown) {
            if (from != null && until != null && from >= until) throw new IllegalArgumentException("Expected from < until");
            this.from = from; this.until = until; this.zone = Objects.requireNonNull(zone).getId();
            this.mode = Objects.requireNonNull(mode); this.includeUnknown = includeUnknown;
        }
        public static Bounds all() { return new Bounds(null, null, ZoneId.systemDefault(), TimeMode.ENTRY, true); }
        public boolean contains(Long start, Long end) {
            if (from == null && until == null) return true;
            if (start == null) return includeUnknown;
            if (mode == TimeMode.OVERLAP && end != null && end > start)
                return (until == null || start < until) && (from == null || end > from);
            return (from == null || start >= from) && (until == null || start < until);
        }
    }

    private final String scope, text, facetsJson;
    private final Bounds bounds;
    private final Class<F> facetType;
    private final Class<S> sortType;
    private final List<Order<S>> order;

    public ArchiveQuery(String scope, Bounds bounds, String text, F facets, Class<F> facetType,
                        Class<S> sortType, List<Order<S>> order) {
        if (!CURRENT.equals(scope) && !SessionStore.ALL.equals(scope)
                && (scope == null || !scope.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
            throw new IllegalArgumentException("Invalid archive scope");
        this.scope = scope; this.bounds = Objects.requireNonNull(bounds); this.text = Objects.requireNonNull(text);
        this.facetType = Objects.requireNonNull(facetType); this.sortType = Objects.requireNonNull(sortType);
        facetsJson = SessionStore.JSON.toJson(Objects.requireNonNull(facets), facetType);
        List<Order<S>> copy = new ArrayList<>();
        for (Order<S> item : order) {
            if (item.field.getDeclaringClass() != sortType) throw new IllegalArgumentException("Wrong sort field type");
            copy.add(new Order<>(item.field, item.direction));
        }
        this.order = Collections.unmodifiableList(copy);
    }
    public static <F,S extends Enum<S>> ArchiveQuery<F,S> of(String scope, F facets, Class<F> type, S sort) {
        return new ArchiveQuery<>(scope, Bounds.all(), "", facets, type, sort.getDeclaringClass(),
                Collections.singletonList(new Order<>(sort, Direction.ASCENDING)));
    }
    public String scope() { return scope; }
    public String resolvedScope(SessionStore store) { return CURRENT.equals(scope) ? store.currentId() : scope; }
    public String text() { return text; }
    public Bounds bounds() { return bounds; }
    public F facets() { return SessionStore.JSON.fromJson(facetsJson, facetType); }
    public List<Order<S>> order() { return order; }
    public ArchiveQuery<F,S> withScope(String value) { return copy(value, bounds, text, facets(), order); }
    public ArchiveQuery<F,S> withText(String value) { return copy(scope, bounds, value, facets(), order); }
    public ArchiveQuery<F,S> withBounds(Bounds value) { return copy(scope, value, text, facets(), order); }
    public ArchiveQuery<F,S> withFacets(F value) { return copy(scope, bounds, text, value, order); }
    public ArchiveQuery<F,S> withOrder(List<Order<S>> value) { return copy(scope, bounds, text, facets(), value); }
    private ArchiveQuery<F,S> copy(String s, Bounds b, String t, F f, List<Order<S>> o) {
        return new ArchiveQuery<>(s, b, t, f, facetType, sortType, o);
    }
    public JsonObject toJson() {
        JsonObject json = new JsonObject(); json.addProperty("version", 1); json.addProperty("scope", scope);
        json.addProperty("text", text); json.add("bounds", SessionStore.JSON.toJsonTree(bounds));
        json.add("facets", JsonParser.parseString(facetsJson));
        JsonArray sorts = new JsonArray();
        for (Order<S> item : order) {
            JsonObject value = new JsonObject(); value.addProperty("field", item.field.name());
            value.addProperty("direction", item.direction.name()); sorts.add(value);
        }
        json.add("order", sorts); return json;
    }
    public ArchiveQuery<F,S> restore(JsonObject json) {
        if (json.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported saved query version");
        JsonObject b = json.getAsJsonObject("bounds");
        Bounds restored = new Bounds(nullableLong(b, "from"), nullableLong(b, "until"),
                ZoneId.of(b.get("zone").getAsString()), TimeMode.valueOf(b.get("mode").getAsString()),
                b.get("includeUnknown").getAsBoolean());
        List<Order<S>> sorts = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("order")) {
            JsonObject o = element.getAsJsonObject();
            sorts.add(new Order<>(Enum.valueOf(sortType, o.get("field").getAsString()),
                    Direction.valueOf(o.get("direction").getAsString())));
        }
        F facets=SessionStore.JSON.fromJson(json.get("facets"),facetType);
        if (!SessionStore.JSON.toJsonTree(facets,facetType).equals(json.get("facets")))
            throw new IllegalArgumentException("Saved facets require an explicit migration");
        return copy(json.get("scope").getAsString(), restored, json.get("text").getAsString(), facets, sorts);
    }
    private static Long nullableLong(JsonObject json, String name) {
        return !json.has(name) || json.get(name).isJsonNull() ? null : json.get(name).getAsLong();
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ArchiveQuery)) return false;
        ArchiveQuery<?,?> q = (ArchiveQuery<?,?>) other;
        return facetType.equals(q.facetType) && sortType.equals(q.sortType) && toJson().equals(q.toJson());
    }
    @Override public int hashCode() { return Objects.hash(facetType, sortType, toJson()); }
}
