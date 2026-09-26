package tomato.gui.history;

import org.junit.Test;
import tomato.history.archive.ArchiveQuery;

import static org.junit.Assert.assertEquals;

public class ArchiveSortLabelTest {
    private enum Sort { TIME, ITEM_NAME }

    @Test public void statusUsesReadableSortWordingInsteadOfEnumNames() {
        assertEquals("time (descending)", ArchiveWorkspace.sortLabel(new ArchiveQuery.Order<>(Sort.TIME, ArchiveQuery.Direction.DESCENDING)));
        assertEquals("item name (ascending)", ArchiveWorkspace.sortLabel(new ArchiveQuery.Order<>(Sort.ITEM_NAME, ArchiveQuery.Direction.ASCENDING)));
    }
}
