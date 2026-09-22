package tomato.history.archive;

import java.util.*;

public final class ArchivePage<R> {
    public final List<ArchiveRow<R>> rows;
    public final long page, matches;
    public final int size;
    public final String revision, unit;
    public final List<String> issues;
    public final Map<String,ArchiveAdapter.Count> counts;
    private final ArchiveResult<R> owner;
    ArchivePage(List<ArchiveRow<R>> rows, long page, int size, long matches, String revision, String unit, List<String> issues,
            Map<String,ArchiveAdapter.Count> counts,ArchiveResult<R> owner) {
        this.rows=Collections.unmodifiableList(new ArrayList<>(rows)); this.page=page; this.size=size;
        this.matches=matches; this.revision=revision; this.unit=unit; this.issues=Collections.unmodifiableList(new ArrayList<>(issues));
        this.counts=Collections.unmodifiableMap(new LinkedHashMap<>(counts));this.owner=owner;
    }
    /** Acquire on the EDT before background summary/detail work, then close in that worker. */
    public ArchiveResult.Lease<R> lease() throws java.io.IOException { return owner.lease(); }
    public boolean more() { return page < (matches == 0 ? 0 : (matches-1)/size); }
    public String description() {
        return rows.size()+" displayed / "+matches+" matching "+unit+" · page "+(page+1)
                + " · pinned " + revision.substring(0,8) + (issues.isEmpty()?"":" · partial: "+issues.size()+" source issue(s)");
    }
}
