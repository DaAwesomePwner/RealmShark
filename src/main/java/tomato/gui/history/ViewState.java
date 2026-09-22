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
        this.selected=Collections.unmodifiableList(new ArrayList<>(selected));this.anchor=anchor;this.anchorOffset=anchorOffset;
        this.tables=Collections.unmodifiableMap(new LinkedHashMap<>(tables));
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
        JsonObject json=new JsonObject();json.addProperty("version",1);json.add("query",query.toJson());
        json.addProperty("archive",archive);json.addProperty("page",page);json.addProperty("tab",tab);
        json.add("selected",SessionStore.JSON.toJsonTree(selected));json.add("anchor",SessionStore.JSON.toJsonTree(anchor));
        json.addProperty("anchorOffset",anchorOffset);json.add("tables",SessionStore.JSON.toJsonTree(tables));return json;
    }
    public ViewState<F,S> restore(JsonObject json) {
        if(json.get("version").getAsInt()!=1)throw new IllegalArgumentException("Unsupported workspace state version");
        List<ArchiveRow.Ref> selection=new ArrayList<>();
        for(JsonElement item:json.getAsJsonArray("selected"))selection.add(SessionStore.JSON.fromJson(item,ArchiveRow.Ref.class));
        Map<String,Table> tables=new LinkedHashMap<>();
        for(Map.Entry<String,JsonElement> item:json.getAsJsonObject("tables").entrySet()) {
            Table value=SessionStore.JSON.fromJson(item.getValue(),Table.class);
            tables.put(item.getKey(),new Table(value.preset,value.columns));
        }
        return new ViewState<>(query.restore(json.getAsJsonObject("query")),json.get("archive").getAsBoolean(),
                json.get("page").getAsLong(),json.get("tab").getAsString(),selection,
                SessionStore.JSON.fromJson(json.get("anchor"),ArchiveRow.Ref.class),json.get("anchorOffset").getAsInt(),tables);
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
            for(Column column:columns){if(!ids.add(column.id))throw new IllegalArgumentException("Duplicate column ID");copy.add(new Column(column.id,column.width,column.visible));}
            this.columns=Collections.unmodifiableList(copy);
        }
    }
}
