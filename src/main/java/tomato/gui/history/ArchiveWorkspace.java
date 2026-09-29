package tomato.gui.history;

import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.ContentStyle;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * EDT-owned typed SessionPanel path. Capture/live components remain independent of saved data. A saved-only workspace
 * ({@link #savedOnly(SessionStore,String,ArchiveClient,ViewStateStore)}, the Dungeons analysis) has no live card and no Live item
 * in its Scope menu: every state it takes, restores or loads reads saved history.
 * <p>
 * One {@link ScopeChip} ("Scope: … ▾") chooses live or saved history and the sessions. It sits in the bar the page shows: while
 * live, a live component that is a {@link LiveFilterHost} lends it its own filter row and this workspace's bar hides; while saved
 * (or without a host bar) it sits in this workspace's bar. Every scope change goes through {@code request()}, which places it.
 */
public final class ArchiveWorkspace<R,F,S extends Enum<S>> extends JPanel implements AutoCloseable {
    /** The property fired (old, new) from {@code request()} when the workspace switches between live and saved history. */
    public static final String ARCHIVE="archive";
    private final SessionStore store;private final String name;private final ArchiveClient<R,F,S> client;private final ViewStateStore states;
    private final boolean savedOnly;
    private final SnapshotRefresh<Update<R>> refresh=new SnapshotRefresh<>();
    private final SnapshotRefresh<List<SessionStore.SessionEntry>> catalog=new SnapshotRefresh<>();
    private final JTextField search=new JTextField(18);
    private final JPanel cards=new JPanel(new CardLayout()),saved=new JPanel(new BorderLayout()),footer=new JPanel(new BorderLayout(0,4));
    private final JTextArea status=ContentStyle.wrappingText("Current live view"),saveStatus=ContentStyle.wrappingText("");
    private final JButton previous=new JButton("Previous page"),next=new JButton("Next page");
    private final JButton stop=new JButton("Cancel read"),cancelExport=new JButton("Cancel export");
    private final ScopeChip chip;private final LiveFilterHost host;
    private final FilterBar filterBar;private final WrapRow searchRow=new WrapRow();
    private final JMenuItem refreshItem,exportAll,exportPage,exportSelected,openFolder;private final JMenu views;
    private final JPopupMenu.Separator refreshSeparator=new JPopupMenu.Separator();
    /** The ⋯ section holding the displayed table's column tools (last in the menu), and those tools; none while live. */
    private final OverflowMenu.Section toolsSection;private HistoryTables.ColumnTools tools;
    private final ComponentListener fit=new ComponentAdapter(){public void componentResized(ComponentEvent e){fitScope();}};
    /** The readable saved sessions the Scope menu lists, in the catalog's order. */
    private List<ScopeChip.SessionChoice> recent=Collections.emptyList();
    /** The bar whose scope slot holds the chip (null while it sits in a search slot), and the host bar watched for resizes. */
    private FilterBar scopeOwner,watched;
    private JComponent lead;
    /** The mode the last request() showed; null before the first. */
    private Boolean shownArchive;
    private Path exportFolder;
    /**
     * The displayed result's status caption for each mode (P6b): Analyst's rows, page, pinned revision, sort and coverage note;
     * Simple's only what asks for action or qualifies the rows (no matches, a partial read), often nothing, and then no line.
     */
    private String analystCaption,simpleCaption;
    private String librarySelection;
    private ViewState<F,S> state;private ArchiveResult<R> result;private ArchiveQuery<F,S> resultQuery;
    private ArchivePage<R> displayed;
    private Cancellation cancel=new Cancellation(),catalogCancel=new Cancellation(),exportCancel=new Cancellation();
    private boolean restoring,loading,exporting,closed,stateLoadFailed;
    private long viewGeneration,saveGeneration;
    // Weak keys retain cached controls' state without retaining every retired page.
    private final Map<Component,Boolean> disabledStates=new WeakHashMap<>();
    private JComponent activeView;
    private final HierarchyListener reuseListener=event->{
        Component component=event.getComponent();
        if((event.getChangeFlags()&HierarchyEvent.PARENT_CHANGED)!=0&&!closed&&!loading&&!restoring&&state.archive
                &&activeView!=null&&SwingUtilities.isDescendingFrom(activeView,saved)
                &&SwingUtilities.isDescendingFrom(component,activeView)&&disabledStates.containsKey(component))
            restoreAdoptedSubtree(component);
    };

    ArchiveWorkspace(SessionStore store,String name,JComponent live,ArchiveClient<R,F,S> client,ViewStateStore states){this(store,name,live,client,states,false);}
    /**
     * A workspace that only reads saved history: no live card and no Live item in its Scope menu. It starts a read of
     * its (saved or initial) state at once, so build it when it is first shown. EDT.
     */
    public static <R,F,S extends Enum<S>> ArchiveWorkspace<R,F,S> savedOnly(SessionStore store,String name,ArchiveClient<R,F,S> client,ViewStateStore states){
        return new ArchiveWorkspace<>(store,name,null,client,states,true);
    }
    private ArchiveWorkspace(SessionStore store,String name,JComponent live,ArchiveClient<R,F,S> client,ViewStateStore states,boolean savedOnly){
        super(new BorderLayout(0,6));requireEdt();this.store=store;this.name=name;this.client=client;this.states=states;this.savedOnly=savedOnly;
        if(client.pageSize()<1||client.pageSize()>1000)throw new IllegalArgumentException("Page size must be 1–1000");
        if(!savedOnly)Objects.requireNonNull(live,"live");
        state=admit(ViewState.initial(client.initialQuery()));
        try{state=admit(states.load(name,state));}catch(RuntimeException failure){stateLoadFailed=true;saveStatus.setText(failure.getMessage());}
        setName(name+"-session-view");
        host=live instanceof LiveFilterHost?(LiveFilterHost)live:null;
        chip=new ScopeChip(name,savedOnly,new ScopeChip.Actions(){
            public void live(){if(ArchiveWorkspace.this.savedOnly||closed)return;state=state.withArchive(false);persist();syncControls();request(false);}
            // "This session" is saved-current; selectSession(CURRENT) keeps going live for its callers.
            public void saved(String scope){if(closed)return;if(ArchiveQuery.CURRENT.equals(scope)||store.currentId().equals(scope))showSaved(ArchiveQuery.CURRENT);else selectSession(scope);}
            public void library(){if(!closed)openLibrary();}
            public void refreshList(){if(!closed)reloadCatalog();}
        });
        if(!savedOnly)cards.add(live,"live");
        cards.add(saved,"saved");if(savedOnly)status.setText("Saved history");
        search.setName(name+"-history-search");search.getAccessibleContext().setAccessibleName("Search all saved "+name+" records");
        search.putClientProperty("JTextField.placeholderText","Search entire scope; press Enter");
        filterBar=new FilterBar(name);OverflowMenu more=filterBar.overflow();
        // Saved history's results reload from ⋯ (the scope ⟳ is gone); the session list reloads from the Scope menu.
        refreshItem=more.add("Refresh",()->{if(state.archive)request(true);});refreshItem.setName(name+"-refresh");more.menu().add(refreshSeparator);
        views=more.submenu("Saved views");more.addSeparator();
        exportSelected=more.add("Export selected…",()->chooseExport(ExportSelection.selected(state.selected)));
        exportPage=more.add("Export page…",()->{if(displayed!=null)chooseExport(ExportSelection.page(displayed.page,displayed.size));});
        exportAll=more.add("Export all matches…",()->chooseExport(ExportSelection.all()));
        openFolder=more.add("Open export folder",()->{
            if(exportFolder==null||!Desktop.isDesktopSupported())return;
            try{Desktop.getDesktop().open(exportFolder.toFile());}catch(Exception failure){status.setText("Could not open the export folder: "+failure.getMessage());}
        });openFolder.setEnabled(false);
        // Each render's column tools replace the previous render's here (apply), instead of a button row under the table.
        toolsSection=more.section(HistoryTables.ColumnTools.SECTION);
        searchRow.add(search);filterBar.search(searchRow);
        JPanel paging=ContentStyle.controls();paging.add(previous);paging.add(next);paging.add(stop);paging.add(cancelExport);stop.setVisible(false);cancelExport.setVisible(false);
        JPanel texts=new JPanel();texts.setLayout(new BoxLayout(texts,BoxLayout.Y_AXIS));texts.add(status);texts.add(saveStatus);
        footer.setName(name+"-archive-footer");footer.add(paging,BorderLayout.NORTH);footer.add(texts,BorderLayout.CENTER);
        // The result's caption follows the mode while it is shown (other messages stay as they are); an empty status takes no line.
        DisplayModeModel.application().bind(this,mode->{String shown=status.getText();
            if(analystCaption!=null&&(shown.equals(analystCaption)||shown.equals(simpleCaption)))status.setText(mode==DisplayModeModel.Mode.ANALYST?analystCaption:simpleCaption);});
        status.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            private void fit(){status.setVisible(status.getDocument().getLength()>0);}
            public void insertUpdate(javax.swing.event.DocumentEvent e){fit();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){fit();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){fit();}
        });
        add(ContentStyle.page(filterBar,cards,footer));
        stop.addActionListener(e->{invalidateView();cancel.cancel();refresh.invalidate();loading=false;status.setText("Read cancelled. Refresh to retry.");updateActions();});
        search.addActionListener(e->changeQuery(state.query.withText(search.getText())));
        previous.addActionListener(e->selectPage(Math.max(0,state.page-1)));next.addActionListener(e->selectPage(state.page+1));
        cancelExport.addActionListener(e->exportCancel.cancel());
        filterBar.addComponentListener(fit);
        search.addPropertyChangeListener("font",e->fitScope());chip.addPropertyChangeListener("font",e->fitScope());
        if(host!=null)live.addPropertyChangeListener(LiveFilterHost.BAR,e->{if(!closed)place();});
        addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0){
            // A load started while hidden (for example an atomic restore before its page is shown) continues.
            if(isShowing()){if(!closed&&!loading)request(false);}else{invalidateView();cancel.cancel();refresh.invalidate();loading=false;updateActions();}
        }});
        reloadNames();syncControls();reloadCatalog();request(false);
    }
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Workspace changes require the EDT");}
    /** A saved-only workspace reads saved history in every state it takes; others take the state as it is. */
    private ViewState<F,S> admit(ViewState<F,S> value){return savedOnly&&!value.archive?value.withArchive(true):value;}
    /** Whether this workspace only reads saved history (no live card and no Live item in its Scope menu). */
    public boolean savedOnly(){return savedOnly;}
    public ViewState<F,S> state(){requireEdt();return state;}
    public ArchivePage<R> displayedPage(){requireEdt();return displayed;}
    public boolean loading(){requireEdt();return loading;}
    /** The workspace's filter row: search, the module's Filters drawer and chips, scope and the ⋯ menu. */
    public FilterBar filterBar(){requireEdt();return filterBar;}
    public void selectSession(String id){
        requireEdt();String scope=store.currentId().equals(id)?ArchiveQuery.CURRENT:id;
        state=admit(state.withQuery(state.query.withScope(scope)).withArchive(!ArchiveQuery.CURRENT.equals(scope)));persist();syncControls();request(false);
    }
    public void showSaved(){requireEdt();state=state.withArchive(true);persist();request(false);}
    /** Saved history of {@code scope} (the Scope menu's "This session" passes CURRENT, which stays saved, unlike selectSession). */
    public void showSaved(String scope){
        requireEdt();Objects.requireNonNull(scope,"scope");
        state=admit(state.withQuery(state.query.withScope(scope)).withArchive(true));persist();syncControls();request(false);
    }
    /** The live component's filter row that hosts the Scope chip while live; null without a {@link LiveFilterHost} or its bar. */
    public FilterBar liveFilterBar(){requireEdt();return host==null?null:host.liveFilterBar();}
    /**
     * Places {@code component} first in the visible bar's search slot (the live host's while live, this workspace's while saved),
     * moving it on every switch; null clears it (the old one is removed). Loot's one view selector uses it.
     */
    public void lead(JComponent component){
        requireEdt();if(lead==component)return;
        if(lead!=null)FilterChips.keepingFocus(()->detach(lead));
        lead=component;place();
    }
    public void changeQuery(ArchiveQuery<F,S> query){
        requireEdt();if(restoring||closed)return;state=state.withQuery(query).withArchive(true);persist();syncControls();request(false);
    }
    /**
     * Atomically applies a complete detached state once (Back and routed opens): scope, query, mode, page,
     * selection, scroll anchor and layouts. In-flight reads are invalidated so their completions are inert,
     * and exactly one load starts for the restored state. A query of other facet/sort types is rejected.
     */
    public void restore(ViewState<F,S> value){
        requireEdt();Objects.requireNonNull(value,"state");if(closed)return;
        ArchiveQuery<F,S> typed=client.initialQuery().restore(value.query.toJson());
        if(!typed.equals(value.query))throw new IllegalArgumentException("State belongs to a different workspace");
        state=admit(value);persist();syncControls();request(false);
    }
    public void selectPage(long page){requireEdt();if(page<0)throw new IllegalArgumentException("Negative page");state=state.withPage(page);persist();request(false);}
    public void refresh(){requireEdt();request(true);}
    public CompletionStage<PreferencesStore.SaveResult> saveNamed(String label){requireEdt();CompletionStage<PreferencesStore.SaveResult> save=states.saveNamed(name,label,state);watchSave(save);reloadNames();return save;}
    public void loadNamed(String label){
        requireEdt();try{state=admit(states.loadNamed(name,label,ViewState.initial(client.initialQuery())));persist();syncControls();request(false);}
        catch(RuntimeException failure){saveStatus.setText("Saved view was not applied: "+failure.getMessage());}
    }
    /** A resize or font change may move the chip between a bar's scope slot and its search slot. */
    private void fitScope(){if(!closed)SwingUtilities.invokeLater(()->{if(!closed)place();});}
    /**
     * Puts the chip (and the lead) in the bar the page shows: the live host's bar while live, else this workspace's bar, which
     * hides while the host's shows. The chip takes the bar's scope slot, or the end of its search slot when the bar is too narrow.
     * Only the state and the bars' current widths are read (0 before a first layout means the wide placement), so routes, Back and
     * restores while hidden place correctly and fitScope refines it once shown. A no-op when already placed, so resizes settle.
     */
    private void place(){
        if(closed)return;
        FilterBar hostBar=host==null?null:host.liveFilterBar();
        if(hostBar!=watched){if(watched!=null)watched.removeComponentListener(fit);watched=hostBar;if(hostBar!=null)hostBar.addComponentListener(fit);}
        FilterBar target=!state.archive&&hostBar!=null?hostBar:filterBar;
        JComponent slot=target==filterBar?searchRow:target.searchSlot();
        JPanel panel=slot instanceof JPanel?(JPanel)slot:null;
        boolean narrow=panel!=null&&narrow(target,panel);
        boolean chipPlaced=narrow?chip.getParent()==panel&&scopeOwner==null:scopeOwner==target&&chip.getParent()!=null&&SwingUtilities.isDescendingFrom(chip,target);
        boolean leadPlaced=lead==null||panel==null||(lead.getParent()==panel&&panel.getComponent(0)==lead);
        if(chipPlaced&&leadPlaced&&filterBar.isVisible()==(target==filterBar))return;
        FilterChips.keepingFocus(()->{
            if(scopeOwner!=null&&(narrow||scopeOwner!=target)){scopeOwner.scope(null);scopeOwner=null;}
            detach(chip);if(lead!=null)detach(lead);
            if(narrow)panel.add(chip);else{target.scope(chip);scopeOwner=target;}
            if(lead!=null&&panel!=null)panel.add(lead,0);
            filterBar.setVisible(target==filterBar);
            if(panel!=null){panel.revalidate();panel.repaint();}
            revalidate();repaint();
        });
    }
    /** R1's width rule on the target bar: its search slot on one line (without the chip), Filters, the chip, ⋯ and 14 em of slack. */
    private boolean narrow(FilterBar target,JPanel slot){
        int width=target.getWidth();if(width<=0)return false;
        int gap=slot.getLayout() instanceof FlowLayout?((FlowLayout)slot.getLayout()).getHgap():6;Insets insets=slot.getInsets();
        int needed=insets.left+insets.right+gap;
        for(Component child:slot.getComponents())if(child!=chip&&child!=lead&&child.isVisible())needed+=child.getPreferredSize().width+gap;
        if(lead!=null&&lead.isVisible())needed+=lead.getPreferredSize().width+gap;
        Component toggle=named(target,target.getName().replaceFirst("-filter-bar$","-filters"));if(toggle!=null)needed+=toggle.getPreferredSize().width;
        needed+=chip.getPreferredSize().width+target.overflow().getPreferredSize().width+14*chip.getFontMetrics(chip.getFont()).charWidth('m');
        return width<needed;
    }
    private static Component named(Container root,String wanted){
        for(Component child:root.getComponents()){
            if(wanted.equals(child.getName()))return child;
            if(child instanceof Container){Component found=named((Container)child,wanted);if(found!=null)return found;}
        }return null;
    }
    private static void detach(Component component){Container parent=component.getParent();if(parent!=null){parent.remove(component);parent.revalidate();parent.repaint();}}
    private void reloadNames(){
        List<String> names;
        try{names=states.names(name);}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());names=Collections.emptyList();}
        views.removeAll();JMenuItem save=new JMenuItem("Save current view…");save.addActionListener(e->promptSave());views.add(save);
        if(!names.isEmpty())views.addSeparator();
        for(String label:names){JMenuItem load=new JMenuItem("Load: "+label);load.addActionListener(e->loadNamed(label));views.add(load);}
        views.addSeparator();
        JMenuItem delete=new JMenuItem("Delete view…");delete.setEnabled(!names.isEmpty());delete.addActionListener(e->promptDelete());views.add(delete);
        JMenuItem reset=new JMenuItem("Reset saved state");reset.addActionListener(e->resetSavedState());views.add(reset);
    }
    private void promptSave(){
        String label=JOptionPane.showInputDialog(this,"Name for this view","Save view",JOptionPane.PLAIN_MESSAGE);
        if(label==null||closed)return;
        try{saveNamed(label);}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}
    }
    private void promptDelete(){
        List<String> names;try{names=states.names(name);}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());return;}
        if(names.isEmpty())return;
        Object label=JOptionPane.showInputDialog(this,"Delete which saved view?","Delete view",JOptionPane.PLAIN_MESSAGE,null,names.toArray(),names.get(0));
        if(label==null||closed)return;watchSave(states.deleteNamed(name,label.toString()));reloadNames();
    }
    private void resetSavedState(){watchSave(states.reset(name));stateLoadFailed=false;state=admit(ViewState.initial(client.initialQuery()));reloadNames();syncControls();request(true);}
    private void syncControls(){
        restoring=true;try{chip.show(state.archive,state.query.scope(),store.currentId(),recent);search.setText(state.query.text());}finally{restoring=false;}
    }
    private void reloadCatalog(){
        catalogCancel.cancel();catalogCancel=new Cancellation();Cancellation token=catalogCancel;
        catalog.request(new Object(),()->{try{return store.catalog(token);}catch(Exception failure){throw new IllegalStateException(failure);}},entries->{
            List<ScopeChip.SessionChoice> choices=new ArrayList<>();
            int count=0;for(SessionStore.SessionEntry entry:entries)if(entry.readable()&&!entry.id.equals(store.currentId())&&(count++<20||entry.id.equals(state.query.scope()))){
                SessionStore.Session session=entry.session();choices.add(new ScopeChip.SessionChoice(entry.id,session.toString(),shortLabel(session)));
            }
            recent=Collections.unmodifiableList(choices);syncControls();
        },failure->status.setText("Session list could not be read. Use Scope › Refresh session list or History library…"));
    }
    /** The chip's short text for a session: its own label, or its start as "MM-dd HH:mm" when it has none. */
    private static String shortLabel(SessionStore.Session session){
        if(session.label!=null&&!session.label.isEmpty())return session.label;
        return SHORT_START.format(java.time.Instant.ofEpochMilli(session.started).atZone(java.time.ZoneId.systemDefault()));
    }
    private static final java.time.format.DateTimeFormatter SHORT_START=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm",Locale.ROOT);
    /** The one live/saved switch: shows the card, places the chip, then reads saved history (or clears the saved row while live). */
    private void request(boolean fresh){
        requireEdt();if(closed)return;invalidateView();cancel.cancel();refresh.invalidate();cancel=new Cancellation();
        ((CardLayout)cards.getLayout()).show(cards,state.archive?"saved":"live");
        footer.setVisible(state.archive);search.setVisible(state.archive);refreshItem.setVisible(state.archive);refreshSeparator.setVisible(state.archive);
        chip.show(state.archive,state.query.scope(),store.currentId(),recent);place();
        if(!state.archive){
            FilterChips.keepingFocus(()->{filterBar.drawer(null);FilterChips.update(filterBar,Collections.<FilterBar.ActiveFilter>emptyList(),null,true);});
            toolsSection.clear();tools=null;   // the live page's table has its own tools
            loading=false;updateActions();
        }else read(fresh);
        announce();
    }
    /** Fires {@link #ARCHIVE} once per switch between live and saved history (not for the first request). */
    private void announce(){
        boolean now=state.archive;Boolean before=shownArchive;shownArchive=now;
        if(before!=null&&before!=now)firePropertyChange(ARCHIVE,before.booleanValue(),now);
    }
    private void read(boolean fresh){
        final ViewState<F,S> requested=state;final Cancellation token=cancel;
        final ArchiveResult<R> existing=!fresh&&result!=null&&state.query.equals(resultQuery)?result:null;
        final ArchiveAdapter<R,F,S> adapter;
        try{adapter=existing==null?client.adapter(requested.query):null;}catch(RuntimeException failure){status.setText("Query is unavailable: "+failure.getMessage());loading=false;updateActions();return;}
        loading=true;status.setText("Loading saved history… Previous rows, if shown, are not the pending query.");updateActions();
        refresh.request(new Object(),()->{
            ArchiveResult<R> next=existing;
            try {
                token.check();if(next==null)next=HistoryPage.open(store,requested.query,adapter,client.scratchDirectory(),token);
                long last=next.matches==0?0:(next.matches-1)/client.pageSize();long page=Math.min(requested.page,last);
                if(fresh&&requested.anchor!=null){long anchored=next.pageOf(requested.anchor,client.pageSize(),token);if(anchored>=0)page=anchored;}
                ArchivePage<R> values=next.page(page,client.pageSize(),token);token.check();return new Update<>(next,values,existing==null);
            }catch(Exception|Error failure){if(existing==null&&next!=null)next.close();throw failure instanceof RuntimeException?(RuntimeException)failure:new IllegalStateException(failure);}
        },update->apply(update,requested.query),failure->{loading=false;Throwable cause=failure;
            while(cause.getCause()!=null)cause=cause.getCause();
            status.setText("History read failed: "+cause.getMessage()+". Refresh to retry; no new revision was applied.");updateActions();},Update::discard);
    }
    private void apply(Update<R> update,ArchiveQuery<F,S> query){
        if(closed||!state.archive||!query.equals(state.query)){update.discard();return;}long ticket=++viewGeneration;
        ViewState<F,S> nextState=state.withPage(update.page.page);JComponent view;ArchiveFilters filters;
        ArchiveClient.Binding<F,S> binding=new ArchiveClient.Binding<F,S>(){
            private boolean current(){requireEdt();return !restoring&&!closed&&!loading&&isVisible()&&state.archive&&ticket==viewGeneration&&query.equals(state.query);}
            public void queryChanged(ArchiveQuery<F,S> value){if(current())changeQuery(value);}
            public void refresh(){if(current())request(true);}
            public void viewChanged(ViewState<F,S> value){if(current()&&value.query.equals(state.query)){
                state=new ViewState<>(state.query,state.archive,state.page,value.tab,value.selected,value.anchor,value.anchorOffset,value.tables);persist();updateActions();}}
        };
        restoring=true;
        try{view=client.render(update.page,nextState,binding);filters=client.filters(update.page,nextState,binding);}catch(RuntimeException failure){update.discard();throw failure;}finally{restoring=false;}
        ArchiveResult<R> old=result;result=update.result;resultQuery=query;displayed=update.page;state=nextState;
        saved.removeAll();activeView=view;saved.add(view);restoreEnabled(saved);saved.revalidate();saved.repaint();loading=false;
        FilterChips.keepingFocus(()->{
            filterBar.drawer(filters==null?null:filters.drawer);
            List<FilterBar.ActiveFilter> active=new ArrayList<>();
            if(!query.text().isEmpty()){
                // The field already exposes the full query; keep the remove action reachable at large fonts.
                active.add(new FilterBar.ActiveFilter("Search active",()->binding.queryChanged(query.withText(""))));
            }
            if(filters!=null)active.addAll(filters.active);
            FilterChips.update(filterBar,active,()->changeQuery(client.initialQuery().withScope(state.query.scope())),true);
            filterBar.setDrawerEnabled(true);
        });
        // Swap the previous render's column tools for this render's (or none) in one ⋯ section.
        tools=filters==null?null:filters.tools;
        if(tools==null)toolsSection.clear();else{tools.setEnabled(true);tools.addTo(filterBar.overflow());}
        List<String> ordering=new ArrayList<>();for(ArchiveQuery.Order<S> item:query.order())ordering.add(sortLabel(item));
        String empty=displayed.matches==0?(result.scanned==0?"No rows available in this saved query. ":"No matches; use Clear to reset filters. "):"";
        analystCaption=empty+displayed.description()+" · sorted by "+String.join(", ",ordering)+" · missing recording metadata means coverage unknown";
        simpleCaption=(empty+(displayed.issues.isEmpty()?"":"Partial: "+displayed.issues.size()+" saved source(s) could not be read; their rows are missing.")).trim();
        status.setText(DisplayModeModel.application().analyst()?analystCaption:simpleCaption);
        if(old!=null&&old!=result)old.close();persist();updateActions();
    }
    /** Readable sort wording, e.g. "time (descending)", instead of raw enum names. */
    static String sortLabel(ArchiveQuery.Order<?> item){
        String field=item.field.name().toLowerCase(java.util.Locale.ROOT).replace('_',' ');
        return field+" ("+(item.direction==ArchiveQuery.Direction.ASCENDING?"ascending":"descending")+")";
    }
    private void persist(){if(stateLoadFailed)return;try{watchSave(states.save(name,state));}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}}
    private void watchSave(CompletionStage<PreferencesStore.SaveResult> save){
        long generation=++saveGeneration;save.whenComplete((value,failure)->SwingUtilities.invokeLater(()->{
            if(closed||generation!=saveGeneration)return;
            saveStatus.setText(failure==null&&value!=null&&value.isSuccess()?"View state saved":"View state is active; save failed. Change or save the view again to retry.");
        }));
    }
    private boolean canExport(){return !closed&&!loading&&!exporting&&state.archive&&result!=null&&state.query.equals(resultQuery);}
    private void invalidateView(){viewGeneration++;activeView=null;disable(saved);filterBar.setDrawerEnabled(false);if(tools!=null)tools.setEnabled(false);}
    private void disable(Component component){
        if(!disabledStates.containsKey(component)){
            disabledStates.put(component,component.isEnabled());component.addHierarchyListener(reuseListener);
        }
        if(component instanceof Container)for(Component child:((Container)component).getComponents())disable(child);
        // UI delegates may disable children when their parent is disabled (e.g. combo arrows).
        component.setEnabled(false);
    }
    private void restoreAdoptedSubtree(Component component){
        // Container delivers child events first. Let the highest tracked ancestor's own
        // callback restore the subtree; consuming/removing child listeners earlier also
        // changes listener counts while the JDK is still traversing those descendants.
        for(Component ancestor=component;ancestor!=activeView;){
            ancestor=ancestor.getParent();
            if(ancestor==null||disabledStates.containsKey(ancestor))return;
        }
        restoreEnabled(component);
    }
    /** Only the accepted tree (including asynchronously reparented cached details) is reactivated. */
    private void restoreEnabled(Component component){
        boolean wasRestoring=restoring;restoring=true;
        try{
            Boolean enabled=disabledStates.remove(component);
            if(enabled!=null){component.removeHierarchyListener(reuseListener);component.setEnabled(enabled);}
            if(component instanceof Container)for(Component child:((Container)component).getComponents())restoreEnabled(child);
        }finally{restoring=wasRestoring;}
    }
    private void forgetDisabledStates(){
        for(Component component:new ArrayList<>(disabledStates.keySet()))component.removeHierarchyListener(reuseListener);
        disabledStates.clear();
    }
    private void updateActions(){boolean ready=canExport();exportAll.setEnabled(ready);exportPage.setEnabled(ready);exportSelected.setEnabled(ready&&!state.selected.isEmpty());
        previous.setEnabled(!loading&&state.page>0);next.setEnabled(!loading&&displayed!=null&&displayed.more());
        stop.setVisible(loading);cancelExport.setVisible(exporting);views.setEnabled(state.archive);}
    public SwingWorker<Path,Void> exportTo(Path directory,String base,ExportSelection selection,ArchiveExport.Format format)throws java.io.IOException {
        requireEdt();if(!canExport())throw new IllegalStateException("Wait for the displayed query revision before exporting");
        return startExport(result.lease(),directory,base,selection,format);
    }
    SwingWorker<Path,Void> startExport(ArchiveResult.Lease<R> lease,Path directory,String base,ExportSelection selection,
            ArchiveExport.Format format){
        requireEdt();
        String revision=lease.manifest().get("revision").getAsString();
        return launch(new ExportTask<Path>(lease,false){
            protected Path runExport()throws Exception{return client.writeExport(lease,selection,format,directory,base,token);}
            protected void discard(Path output)throws Exception{if(output!=null)java.nio.file.Files.deleteIfExists(output);}
            protected boolean completed(Path path){
                exportFolder=path.toAbsolutePath().getParent();openFolder.setEnabled(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
                status.setText("Export complete · revision "+revision.substring(0,8)+" · "+path.getFileName());return false;
            }
        });
    }
    /** The ⋯ export items and headless tests use the same asynchronous pre-confirmation path. */
    SwingWorker<String,Void> prepareExport(ExportSelection selection,java.util.function.BiPredicate<ArchiveResult.Lease<R>,String> confirm)throws java.io.IOException {
        requireEdt();if(!canExport())throw new IllegalStateException("Wait for the displayed query revision before exporting");
        ArchiveResult.Lease<R> lease=result.lease();
        return launch(new ExportTask<String>(lease,true){
            protected String runExport()throws Exception{return client.previewExport(lease,selection,token);}
            protected boolean completed(String text){return confirm.test(lease,text);}
        });
    }
    private <T> SwingWorker<T,Void> launch(ExportTask<T> worker){
        exporting=true;exportCancel=worker.token;updateActions();worker.execute();return worker;
    }
    /** Future completion and writer exit are separate when cancel(false) is used. */
    private abstract class ExportTask<T> extends SwingWorker<T,Void> {
        final ArchiveResult.Lease<R> lease;
        final Cancellation token=new Cancellation(this::isCancelled);
        private final boolean transferLease;
        private final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean(),exited=new java.util.concurrent.atomic.AtomicBoolean(),published=new java.util.concurrent.atomic.AtomicBoolean();
        private T value;private Throwable failure;
        ExportTask(ArchiveResult.Lease<R> lease,boolean transferLease){this.lease=lease;this.transferLease=transferLease;}
        protected abstract T runExport()throws Exception;
        protected abstract boolean completed(T value);
        protected void discard(T value)throws Exception { }
        protected T doInBackground()throws Exception {
            if(!claimed.compareAndSet(false,true))throw new java.util.concurrent.CancellationException();
            try{token.check();value=runExport();token.check();return value;}
            catch(Exception|Error error){failure=error;throw error;}
            finally{
                if(!transferLease||failure!=null||token.isCancelled())lease.close();
                exited.set(true);SwingUtilities.invokeLater(this::publishCompletion);
            }
        }
        protected void done(){
            if(isCancelled())token.cancel();
            if(claimed.compareAndSet(false,true))exited.set(true);
            publishCompletion();
        }
        private void publishCompletion(){
            if(!isDone()||!exited.get()||!published.compareAndSet(false,true))return;
            if(closed||token.isCancelled()){
                // A final cancellation can race with a writer returning its owned output path.
                java.util.concurrent.CompletableFuture.runAsync(()->{
                    try{discard(value);}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}finally{lease.close();}
                }).whenComplete((ignored,error)->SwingUtilities.invokeLater(()->{
                    exporting=false;status.setText(error==null?"Export cancelled; unfinished output cleaned":"Export cancelled; cleanup failed: "+error.getMessage());updateActions();
                }));return;
            }
            boolean transferred=false;
            try{
                if(failure!=null)status.setText("Export failed: "+failure.getMessage());
                else transferred=completed(value);
            }catch(RuntimeException error){status.setText("Export could not finish: "+error.getMessage());}
            finally{if(!transferred){lease.close();exporting=false;}updateActions();}
        }
    }
    private void chooseExport(ExportSelection selection){
        if(!canExport())return;
        try{prepareExport(selection,(held,message)->{
            JTextArea preview=ContentStyle.wrappingText(message);preview.getAccessibleContext().setAccessibleName("Export population and revision");
            JScrollPane previewScroll=new JScrollPane(preview);previewScroll.setPreferredSize(new Dimension(600,240));
            Object format=JOptionPane.showInputDialog(this,previewScroll,"Export pinned revision",JOptionPane.PLAIN_MESSAGE,null,ArchiveExport.Format.values(),ArchiveExport.Format.JSON);
            if(!(format instanceof ArchiveExport.Format)||closed||exportCancel.isCancelled())return false;JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION||closed||exportCancel.isCancelled())return false;
            // Modal dialogs run a nested EDT loop: the displayed result may have changed since preview.
            startExport(held,chooser.getSelectedFile().toPath(),name+"-"+System.currentTimeMillis(),selection,(ArchiveExport.Format)format);return true;
        });}catch(java.io.IOException|RuntimeException failure){status.setText("Export could not start: "+failure.getMessage());}
    }
    private void openLibrary(){
        Window parent=SwingUtilities.getWindowAncestor(this);JDialog dialog=new JDialog(parent,"History library",Dialog.ModalityType.MODELESS);
        HistoryLibrary library=new HistoryLibrary(store,id->{selectSession(id);showSaved();dialog.dispose();},librarySelection==null?state.query.resolvedScope(store):librarySelection);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){librarySelection=library.rememberedSelection();library.close();if(!closed)reloadCatalog();}});
        dialog.setContentPane(library);dialog.setSize(900,550);dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    @Override public void removeNotify(){if(!closed){invalidateView();cancel.cancel();refresh.invalidate();if(result!=null){result.close();result=null;displayed=null;}}super.removeNotify();}
    @Override public void close(){requireEdt();if(closed)return;closed=true;if(watched!=null){watched.removeComponentListener(fit);watched=null;}invalidateView();forgetDisabledStates();cancel.cancel();catalogCancel.cancel();exportCancel.cancel();refresh.invalidate();catalog.invalidate();if(result!=null){result.close();result=null;}}
    private static final class Update<R>{final ArchiveResult<R> result;final ArchivePage<R> page;final boolean owns;
        Update(ArchiveResult<R> result,ArchivePage<R> page,boolean owns){this.result=result;this.page=page;this.owns=owns;}
        void discard(){if(owns)result.close();}}
}
