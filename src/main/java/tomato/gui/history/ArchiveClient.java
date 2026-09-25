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
    /** Runs off the EDT before confirmation, with the same lease later supplied to writeExport. */
    default String previewExport(ArchiveResult.Lease<R> lease,ExportSelection selection,Cancellation cancel)throws java.io.IOException {
        cancel.check();com.google.gson.JsonObject manifest=lease.manifest();
        if(selection.kind==ExportSelection.Kind.SELECTED)lease.stream(selection,row->{},cancel);
        return selection.expected(lease.matches())+" "+manifest.get("unit").getAsString()+" · "+selection.kind
                +"\nRevision "+manifest.get("revision").getAsString()
                +"\nSource sessions (including dependencies): "+manifest.getAsJsonArray("sessions").size()
                +"\nQuery, bounds and ordering: "+manifest.get("query")+"\nSource issues: "+manifest.get("issues");
    }
    /** Runs off the EDT. Caller owns the lease; custom exports must honor cancellation and clean failed output. */
    default Path writeExport(ArchiveResult.Lease<R> lease,ExportSelection selection,ArchiveExport.Format format,
            Path directory,String base,Cancellation cancel)throws java.io.IOException {
        return ArchiveExport.write(lease,selection,format,directory,base,exportColumns(),cancel);
    }
    interface Binding<F,S extends Enum<S>> {
        void queryChanged(ArchiveQuery<F,S> query);
        void viewChanged(ViewState<F,S> state);
        /** Policy/annotation changes need a new pin even when query intent did not change. */
        void refresh();
    }
}
