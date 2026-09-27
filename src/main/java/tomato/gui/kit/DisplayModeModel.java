package tomato.gui.kit;

import java.awt.event.HierarchyEvent;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JComponent;
import util.PropertiesManager;

/** Simple hides provenance, IDs and diagnostic tabs; Analyst shows them. Display only; never changes queries. */
public final class DisplayModeModel {
    public enum Mode { SIMPLE, ANALYST }
    public static final String KEY = "ui.mode";

    private static DisplayModeModel application;

    private final BiConsumer<String, String> write;
    /** Weak: the owning component holds each listener strongly, so discarded, never-shown views are not leaked. */
    private final List<WeakReference<Consumer<Mode>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Mode mode;

    public DisplayModeModel(Function<String, String> read, BiConsumer<String, String> write) {
        this.write = write;
        mode = "analyst".equals(read.apply(KEY)) ? Mode.ANALYST : Mode.SIMPLE;
    }

    public static synchronized DisplayModeModel application() {
        if (application == null) application = new DisplayModeModel(PropertiesManager::getProperty, PropertiesManager::setProperties);
        return application;
    }

    public Mode mode() { return mode; }
    public boolean analyst() { return mode == Mode.ANALYST; }

    /** Call on the EDT; listeners run synchronously. */
    public void set(Mode value) {
        if (value == null || value == mode) return;
        mode = value;
        write.accept(KEY, value == Mode.ANALYST ? "analyst" : "simple");
        for (WeakReference<Consumer<Mode>> reference : listeners) {
            Consumer<Mode> listener = reference.get();
            if (listener == null) listeners.remove(reference); else listener.accept(value);
        }
    }

    public void toggle() { set(analyst() ? Mode.SIMPLE : Mode.ANALYST); }

    /**
     * Applies the current mode now and on every change while the owner is alive and displayable. The owner
     * holds the listener; the model holds it weakly and drops it when the owner leaves a displayable hierarchy.
     */
    public void bind(JComponent owner, Consumer<Mode> listener) {
        owner.putClientProperty(listener, listener);
        listener.accept(mode);
        register(listener);
        owner.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) return;
            if (owner.isDisplayable()) {
                register(listener);
                listener.accept(mode);
            } else {
                listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
            }
        });
    }

    private void register(Consumer<Mode> listener) {
        for (WeakReference<Consumer<Mode>> reference : listeners) if (reference.get() == listener) return;
        listeners.add(new WeakReference<>(listener));
    }

    public int listenerCount() {
        listeners.removeIf(reference -> reference.get() == null);
        return listeners.size();
    }
}
