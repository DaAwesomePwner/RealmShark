package tomato.gui.history;

import tomato.history.SessionStore;
import javax.swing.*;
import java.util.function.Supplier;

/** Shared archive loader types and typed workspace factory. */
public final class SessionPanel {
    private SessionPanel() { }
    @FunctionalInterface public interface Loader { Loaded load(SessionStore store, String scope, int page, String query) throws Exception; }
    public static final class Loaded {
        final Supplier<JComponent> view; final boolean more; final String description;
        public Loaded(Supplier<JComponent> view, boolean more, String description) { this.view=view;this.more=more;this.description=description; }
        public JComponent createView() { return view.get(); }
    }
    /** Typed opt-in path; separate name preserves all existing four-argument loader lambdas. */
    public static <R,F,S extends Enum<S>> ArchiveWorkspace<R,F,S> queried(SessionStore store,String name,
            JComponent live,ArchiveClient<R,F,S> client,ViewStateStore states) {
        return new ArchiveWorkspace<>(store,name,live,client,states);
    }
}
