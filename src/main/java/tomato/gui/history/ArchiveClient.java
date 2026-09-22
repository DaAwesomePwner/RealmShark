package tomato.gui.history;

import tomato.history.archive.*;
import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.*;

/** Module-owned factory and renderer; construction/restoration of components occurs only on the EDT. */
public interface ArchiveClient<R,F,S extends Enum<S>> {
    ArchiveQuery<F,S> initialQuery();
    Path scratchDirectory();
    /** Called on the EDT once per new query revision. Return a fresh adapter with frozen dependencies. */
    ArchiveAdapter<R,F,S> adapter(ArchiveQuery<F,S> query);
    JComponent render(ArchivePage<R> page,ViewState<F,S> state,Binding<F,S> binding);
    default int pageSize(){return 1000;}
    default List<ArchiveExport.Column<R>> exportColumns(){return Collections.emptyList();}
    interface Binding<F,S extends Enum<S>> {
        void queryChanged(ArchiveQuery<F,S> query);
        void viewChanged(ViewState<F,S> state);
        /** Policy/annotation changes need a new pin even when query intent did not change. */
        void refresh();
    }
}
