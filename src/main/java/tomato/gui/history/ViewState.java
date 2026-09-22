package tomato.gui.history;

import com.google.gson.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import java.util.*;

/** Independent workspace intent. No Swing component, model object, or open revision is persisted. */
public final class ViewState<F,S extends Enum<S>> {
    public final ArchiveQuery<F,S> query;
    public final boolean archive;
    public final long page;
    public final String tab;
    public final List<ArchiveRow.Ref> selected;
    public final ArchiveRow.Ref anchor;
    public final int anchorOffset;
    public final Map<String,Table> tables;
    public ViewState(ArchiveQuery<F,S> query,boolean archive,long page,String tab,List<ArchiveRow.Ref> selected,
            ArchiveRow.Ref anchor,int anchorOffset,Map<String,Table> tables) {
        if(page<0)throw new IllegalArgumentException("Negative page");
        this.query=Objects.requireNonNull(query);this.archive=archive;this.page=page;this.tab=Objects.requireNonNull(tab);
        List<ArchiveRow.Ref> refs=new ArrayList<>();
        for(ArchiveRow.Ref ref:selected)refs.add(ArchiveRow.Ref.copyOf(ref));
        this.selected=Collections.unmodifiableList(refs);this.anchor=anchor==null?null:ArchiveRow.Ref.copyOf(anchor);this.anchorOffset=anchorOffset;
        Map<String,Table> layouts=new LinkedHashMap<>();
        for(Map.Entry<String,Table> entry:tables.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null)throw new IllegalArgumentException("Null table state");
            layouts.put(entry.getKey(),new Table(entry.getValue().preset,entry.getValue().columns));
        }
        this.tables=Collections.unmodifiableMap(layouts);
    }
    public static <F,S extends Enum<S>> ViewState<F,S> initial(ArchiveQuery<F,S> query) {
        return new ViewState<>(query,false,0,"",Collections.emptyList(),null,0,Collections.emptyMap());
    }
    public ViewState<F,S> withQuery(ArchiveQuery<F,S> value) {
        return new ViewState<>(value,archive,0,tab,selected,anchor,anchorOffset,tables);
    }
    public ViewState<F,S> withPage(long value) { return new ViewState<>(query,archive,value,tab,selected,anchor,anchorOffset,tables); }
    public ViewState<F,S> withArchive(boolean value) { return new ViewState<>(query,value,page,tab,selected,anchor,anchorOffset,tables); }
    public ViewState<F,S> withPosition(String tab,List<ArchiveRow.Ref> selection,ArchiveRow.Ref anchor,int offset) {
        return new ViewState<>(query,archive,page,tab,selection,anchor,offset,tables);
    }
    public ViewState<F,S> withTable(String key,Table value) {
        Map<String,Table> next=new LinkedHashMap<>(tables);next.put(key,value);
        return new ViewState<>(query,archive,page,tab,selected,anchor,anchorOffset,next);
    }
    public JsonObject toJson() {
        // Also validate instances reconstructed by Gson, which bypasses constructors.
        for(ArchiveRow.Ref ref:selected)ArchiveRow.Ref.copyOf(ref);
        if(anchor!=null)ArchiveRow.Ref.copyOf(anchor);
        for(Map.Entry<String,Table> entry:tables.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null)throw new IllegalArgumentException("Null table state");
            new Table(entry.getValue().preset,entry.getValue().columns);
        }
        JsonObject json=new JsonObject();json.addProperty("version",1);json.add("query",query.toJson());
        json.addProperty("archive",archive);json.addProperty("page",page);json.addProperty("tab",tab);
        json.add("selected",SessionStore.JSON.toJsonTree(selected));json.add("anchor",SessionStore.JSON.toJsonTree(anchor));
        json.addProperty("anchorOffset",anchorOffset);json.add("tables",SessionStore.JSON.toJsonTree(tables));return json;
    }
    public ViewState<F,S> restore(JsonObject json) {
        if(json.get("version").getAsInt()!=1)throw new IllegalArgumentException("Unsupported workspace state version");
        List<ArchiveRow.Ref> selection=new ArrayList<>();
        for(JsonElement item:json.getAsJsonArray("selected"))selection.add(readRef(item));
        Map<String,Table> tables=new LinkedHashMap<>();
        for(Map.Entry<String,JsonElement> item:json.getAsJsonObject("tables").entrySet()) {
            Table value=SessionStore.JSON.fromJson(item.getValue(),Table.class);
            if(value==null)throw new IllegalArgumentException("Null table state");
            tables.put(item.getKey(),new Table(value.preset,value.columns));
        }
        return new ViewState<>(query.restore(json.getAsJsonObject("query")),json.get("archive").getAsBoolean(),
                json.get("page").getAsLong(),json.get("tab").getAsString(),selection,
                json.get("anchor").isJsonNull()?null:readRef(json.get("anchor")),json.get("anchorOffset").getAsInt(),tables);
    }
    private static ArchiveRow.Ref readRef(JsonElement value){
        if(value==null||!value.isJsonObject())throw new IllegalArgumentException("Invalid saved record reference");
        JsonObject ref=value.getAsJsonObject();String[] fields={"session","module","locator","child"};
        for(String field:fields){JsonElement part=ref.get(field);
            if(part==null||!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid saved record reference: "+field);}
        return new ArchiveRow.Ref(ref.get("session").getAsString(),ref.get("module").getAsString(),ref.get("locator").getAsString(),ref.get("child").getAsString());
    }
    public static final class Column {
        public final String id;public final int width;public final boolean visible;
        public Column(String id,int width,boolean visible) {
            if(width<16||width>10000)throw new IllegalArgumentException("Invalid column width");
            this.id=Objects.requireNonNull(id);this.width=width;this.visible=visible;
        }
    }
    public static final class Table {
        public final String preset;public final List<Column> columns;
        public Table(String preset,List<Column> columns) {
            this.preset=Objects.requireNonNull(preset);List<Column> copy=new ArrayList<>();Set<String> ids=new HashSet<>();
            if(columns==null)throw new IllegalArgumentException("Null columns");
            for(Column column:columns){if(column==null)throw new IllegalArgumentException("Null column state");if(!ids.add(column.id))throw new IllegalArgumentException("Duplicate column ID");copy.add(new Column(column.id,column.width,column.visible));}
            this.columns=Collections.unmodifiableList(copy);
        }
    }
}
