package tomato.history.archive;

import java.util.*;

/** Explicit export population. A page is a range in the global order, never a fresh query. */
public final class ExportSelection {
    public enum Kind { SELECTED, PAGE, ALL_MATCHES }
    public final Kind kind;
    public final long page;
    public final int size;
    public final Set<ArchiveRow.Ref> refs;
    private ExportSelection(Kind kind,long page,int size,Collection<ArchiveRow.Ref> refs) {
        this.kind=kind; this.page=page; this.size=size; this.refs=Collections.unmodifiableSet(new HashSet<>(refs));
    }
    public static ExportSelection all() { return new ExportSelection(Kind.ALL_MATCHES,0,0,Collections.emptySet()); }
    public static ExportSelection page(long page,int size) {
        if(page<0 || size<1 || size>1000 || page>Long.MAX_VALUE/size) throw new IllegalArgumentException("Invalid page");
        return new ExportSelection(Kind.PAGE,page,size,Collections.emptySet());
    }
    public static ExportSelection selected(Collection<ArchiveRow.Ref> refs) {
        return new ExportSelection(Kind.SELECTED,0,0,refs);
    }
    public long expected(long matches) {
        if(kind==Kind.SELECTED)return refs.size();
        if(kind==Kind.ALL_MATCHES)return matches;
        return Math.min(size,Math.max(0,matches-page*size));
    }
}
