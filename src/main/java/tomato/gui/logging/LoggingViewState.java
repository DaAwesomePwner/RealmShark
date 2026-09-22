package tomato.gui.logging;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import tomato.gui.history.*;
import tomato.gui.modern.ContentStyle;
import tomato.history.archive.ArchiveQuery;
import util.PreferencesStore;

/** Logging-only live intent. The shared store owns version envelopes and asynchronous disk writes. */
final class LoggingViewState {
    static final String MODULE = "logging-live";
    enum Order { NONE }
    static final class Fields {
        int version=1;
        String tab="discovery";
        int divider=-1;
        Map<String,Tab> tabs=new LinkedHashMap<>();
    }
    static final class Tab {
        LoggingQuery query=new LoggingQuery();
        List<Sort> sort=new ArrayList<>();
        List<ViewState.Column> columns=new ArrayList<>();
        Selection selection;
    }
    static final class Sort {
        String column;
        SortOrder order;
        Sort(String column, SortOrder order) { this.column=column; this.order=order; }
    }
    static final class Selection {
        String run, key;
        long area;
        Selection(String run, long area, String key) { this.run=run; this.area=area; this.key=key; }
    }

    private final ViewStateStore store;
    private final Supplier<Fields> capture;
    private final Consumer<Fields> apply, validate;
    private final JPanel controls=new JPanel(new java.awt.BorderLayout());
    final JComboBox<String> names=new JComboBox<>();
    final JButton saveNamed=new JButton("Save"), loadNamed=new JButton("Load"), deleteNamed=new JButton("Delete"),
        reset=new JButton("Reset saved"), retry=new JButton("Retry save");
    final JLabel status=new JLabel();
    private boolean blocked, queued;
    private long generation;
    private String lastIntent;
    private CompletionStage<PreferencesStore.SaveResult> lastWrite;
    private Supplier<CompletionStage<PreferencesStore.SaveResult>> retryAction;

    LoggingViewState(ViewStateStore store, Supplier<Fields> capture, Consumer<Fields> apply, Consumer<Fields> validate) {
        this.store=store; this.capture=capture; this.apply=apply; this.validate=validate;
        names.setEditable(true); names.setPrototypeDisplayValue("Named view…"); names.setName("logging-named-view");
        names.getAccessibleContext().setAccessibleName("Logging named view name");
        names.setToolTipText("Saved views reopen on fresh diagnostics, unpaused. Collection, disk saving and sampling are not restored.");
        names.getAccessibleContext().setAccessibleDescription(names.getToolTipText());
        JPanel row=ContentStyle.controls(); JLabel label=new JLabel("Views"); label.setLabelFor(names); row.add(label); row.add(names);
        JButton[] buttons={saveNamed,loadNamed,deleteNamed,reset,retry};
        String[] ids={"save-named","load-named","delete-named","reset-state","retry-state"};
        String[] labels={"Save named Logging view", "Load named Logging view", "Delete named Logging view", "Reset all saved Logging state and named views", "Retry saving Logging view state"};
        for (int i=0;i<buttons.length;i++) {
            buttons[i].setName("logging-"+ids[i]); buttons[i].getAccessibleContext().setAccessibleName(labels[i]);
            buttons[i].setToolTipText(labels[i]); row.add(buttons[i]);
        }
        status.setName("logging-state-status"); status.getAccessibleContext().setAccessibleName("Logging view state save status");
        controls.add(row); controls.add(status,java.awt.BorderLayout.SOUTH);
        saveNamed.addActionListener(e -> saveNamed(name()));
        loadNamed.addActionListener(e -> loadNamed(name()));
        deleteNamed.addActionListener(e -> deleteNamed(name()));
        reset.addActionListener(e -> reset()); retry.addActionListener(e -> retry());
    }
    JPanel controls() { return controls; }
    private String name() { Object value=names.getEditor().getItem(); return value==null ? "" : value.toString().trim(); }
    static ViewState<Fields,Order> envelope(Fields fields) {
        return ViewState.initial(ArchiveQuery.of(ArchiveQuery.CURRENT,fields,Fields.class,Order.NONE));
    }
    private Fields read(ViewState<Fields,Order> saved) {
        if (saved.archive || !ArchiveQuery.CURRENT.equals(saved.query.scope())) throw new IllegalArgumentException("Not a live Logging view");
        Fields fields=saved.query.facets(); validate.accept(fields); return fields;
    }
    void restore() {
        try {
            // Validate named payloads too: a newer document must not be rewritten by automatic saving.
            for (String name:store.names(MODULE)) read(store.loadNamed(MODULE,name,envelope(new Fields())));
            Fields fields=read(store.load(MODULE,envelope(new Fields())));
            apply.accept(fields); refreshNames(""); queued=false;
            lastIntent=envelope(capture.get()).toJson().toString();
        } catch (RuntimeException failure) { block(); }
    }
    private void block() {
        blocked=true; queued=false; generation++; retryAction=null; retry.setEnabled(false);
        message("Saved Logging state unavailable; current controls still work. Reset saved to replace it.",true);
    }
    void changed() {
        if (blocked) return;
        if (queued) return;
        if (envelope(capture.get()).toJson().toString().equals(lastIntent)) return;
        queued=true;
        SwingUtilities.invokeLater(() -> { if (queued) save(); });
    }
    CompletionStage<PreferencesStore.SaveResult> save() {
        requireEdt(); queued=false;
        if (blocked) return rejected();
        Fields fields=capture.get(); validate.accept(fields);
        ViewState<Fields,Order> saved=envelope(fields);
        String intent=saved.toJson().toString();
        if (intent.equals(lastIntent) && lastWrite!=null) {
            CompletionStage<PreferencesStore.SaveResult> pending=lastWrite;
            return watch(() -> pending,false);
        }
        lastIntent=intent;
        return watch(() -> store.save(MODULE,saved));
    }
    CompletionStage<PreferencesStore.SaveResult> saveNamed(String name) {
        requireEdt();
        if (blocked) return rejected();
        if (name==null || name.trim().isEmpty() || name.length()>100) {
            message("Enter a view name (1–100 characters).",true); return rejected();
        }
        save(); Fields fields=capture.get(); validate.accept(fields); ViewState<Fields,Order> saved=envelope(fields);
        CompletionStage<PreferencesStore.SaveResult> result=watch(() -> store.saveNamed(MODULE,name,saved));
        refreshNames(name); return result;
    }
    void loadNamed(String name) {
        requireEdt(); if (blocked) return;
        try {
            if (!store.names(MODULE).contains(name)) { message("Choose an existing named view to load.",true); return; }
            Fields next=read(store.loadNamed(MODULE,name,envelope(new Fields())));
            queued=false; generation++; apply.accept(next); save();
        } catch (RuntimeException failure) { block(); }
    }
    CompletionStage<PreferencesStore.SaveResult> deleteNamed(String name) {
        requireEdt(); if (blocked) return rejected();
        CompletionStage<PreferencesStore.SaveResult> result=watch(() -> store.deleteNamed(MODULE,name)); refreshNames(""); return result;
    }
    CompletionStage<PreferencesStore.SaveResult> reset() {
        requireEdt(); queued=false; blocked=false; generation++;
        apply.accept(new Fields()); queued=false; lastIntent=null;
        CompletionStage<PreferencesStore.SaveResult> result=watch(() -> store.reset(MODULE)); refreshNames(""); return result;
    }
    CompletionStage<PreferencesStore.SaveResult> retry() {
        requireEdt(); if (blocked) return rejected();
        if (retryAction==null) lastIntent=null; // Loaded memory is not proof of disk durability.
        return queued || retryAction==null ? save() : watch(retryAction);
    }
    void detach() {
        if (queued) save();
        generation++; // Disk completion may continue; it must not act on a detached view.
    }
    private void refreshNames(String selected) {
        try {
            names.setModel(new DefaultComboBoxModel<>(store.names(MODULE).toArray(new String[0])));
            names.setSelectedItem(selected);
        } catch (RuntimeException failure) { block(); }
    }
    private CompletionStage<PreferencesStore.SaveResult> watch(Supplier<CompletionStage<PreferencesStore.SaveResult>> action) {
        return watch(action,true);
    }
    private CompletionStage<PreferencesStore.SaveResult> watch(Supplier<CompletionStage<PreferencesStore.SaveResult>> action, boolean remember) {
        long request=++generation; if (remember) retryAction=action; retry.setEnabled(false); message("Saving Logging view…",false);
        CompletionStage<PreferencesStore.SaveResult> completion;
        try { completion=action.get(); }
        catch (RuntimeException failure) { completion=CompletableFuture.completedFuture(PreferencesStore.SaveResult.failed(0,failure)); }
        lastWrite=completion;
        completion.whenComplete((result,failure) -> SwingUtilities.invokeLater(() -> {
            if (request!=generation || queued) return;
            boolean ok=failure==null && result!=null && result.isSuccess();
            retry.setEnabled(!ok); message(ok ? "Logging view saved." : "View save failed; working state retained. Retry save.",!ok);
        }));
        return completion;
    }
    private void message(String text, boolean visible) { status.setText(text); status.setVisible(visible); controls.revalidate(); }
    private static CompletionStage<PreferencesStore.SaveResult> rejected() {
        return CompletableFuture.completedFuture(PreferencesStore.SaveResult.failed(0,new IllegalStateException("Saved state unavailable or invalid name")));
    }
    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Logging view state belongs to the EDT"); }
}
