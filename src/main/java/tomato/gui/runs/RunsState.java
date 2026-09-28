package tomato.gui.runs;

import tomato.history.link.VisitRef;

/**
 * The Runs page's detached Back state ({@link RunsRouteTarget}): whether the run recap showed, and then its exact run and the
 * recording its Damage section showed ({@code recordingId} null = the longest, the default), whether the feed showed its Table
 * view (behind the recap, or itself), and the Table view's own detached state ({@code workspace}: the archive workspace's view
 * state, null when the page has no saved-history table). Back restores exactly this: the recap, or the feed on Cards or on the
 * Table view with the workspace's query, page, selection and scroll.
 */
public record RunsState(boolean recap, VisitRef ref, String recordingId, boolean table, Object workspace) {
    public RunsState {
        if (recap && ref == null) throw new IllegalArgumentException("A recap state needs its run");
        if (!recap && (ref != null || recordingId != null)) throw new IllegalArgumentException("Only a recap state names a run");
    }

    /** A state without the Table view's own state (a page without a saved-history table). */
    public RunsState(boolean recap, VisitRef ref, String recordingId, boolean table) { this(recap, ref, recordingId, table, null); }

    /** The feed on Cards ({@code table} false) or on the Table view. */
    public static RunsState feed(boolean table, Object workspace) { return new RunsState(false, null, null, table, workspace); }
}
