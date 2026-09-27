package tomato.gui.history;

import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** EDT-owned typed SessionPanel path. Capture/live components remain independent of saved data. */
public final class ArchiveWorkspace<R,F,S extends Enum<S>> extends JPanel implements AutoCloseable {
    private final SessionStore store;private final String name;private final ArchiveClient<R,F,S> client;private final ViewStateStore states;
    private final SnapshotRefresh<Update<R>> refresh=new SnapshotRefresh<>();
    private final SnapshotRefresh<List<SessionStore.SessionEntry>> catalog=new SnapshotRefresh<>();
    private final JComboBox<Choice> sessions=new JComboBox<>();private final JTextField search=new JTextField(18);
    private final JPanel cards=new JPanel(new CardLayout()),saved=new JPanel(new BorderLayout()),footer=new JPanel(new BorderLayout(0,4));
    private final JTextArea status=ContentStyle.wrappingText("Current live view"),saveStatus=ContentStyle.wrappingText("");
    private final JButton previous=new JButton("Previous page"),next=new JButton("Next page"),browse=new JButton("Browse saved");
    private final JButton stop=new JButton("Cancel read"),cancelExport=new JButton("Cancel export");
    private final KitButton reload=KitButton.icon(new LineIcon(LineIcon.REFRESH,16),"Refresh");
    private final FilterBar filterBar;private final WrapRow searchRow=new WrapRow();private final JPanel scopeRow=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
    private final JMenuItem exportAll,exportPage,exportSelected,openFolder;private final JMenu views;
    private boolean narrowScope=true;
    private Path exportFolder;
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

    ArchiveWorkspace(SessionStore store,String name,JComponent live,ArchiveClient<R,F,S> client,ViewStateStore states){
        super(new BorderLayout(0,6));requireEdt();this.store=store;this.name=name;this.client=client;this.states=states;
        if(client.pageSize()<1||client.pageSize()>1000)throw new IllegalArgumentException("Page size must be 1–1000");
        state=ViewState.initial(client.initialQuery());
        try{state=states.load(name,state);}catch(RuntimeException failure){stateLoadFailed=true;saveStatus.setText(failure.getMessage());}
        setName(name+"-session-view");sessions.setName(name+"-session-picker");sessions.getAccessibleContext().setAccessibleName(name+" session");
        sessions.setToolTipText("Session scope");sessions.setPrototypeDisplayValue(new Choice("","Session · 2026-09-20 12:00:00"));
        DefaultListCellRenderer literal=new DefaultListCellRenderer();literal.putClientProperty("html.disable",true);sessions.setRenderer(literal);
        cards.add(live,"live");cards.add(saved,"saved");
        search.setName(name+"-history-search");search.getAccessibleContext().setAccessibleName("Search all saved "+name+" records");
        search.putClientProperty("JTextField.placeholderText","Search entire scope; press Enter");
        filterBar=new FilterBar(name);scopeRow.setOpaque(false);OverflowMenu more=filterBar.overflow();
        more.add("History library…",this::openLibrary);more.addSeparator();views=more.submenu("Saved views");more.addSeparator();
        exportSelected=more.add("Export selected…",()->chooseExport(ExportSelection.selected(state.selected)));
        exportPage=more.add("Export page…",()->{if(displayed!=null)chooseExport(ExportSelection.page(displayed.page,displayed.size));});
        exportAll=more.add("Export all matches…",()->chooseExport(ExportSelection.all()));
        openFolder=more.add("Open export folder",()->{
            if(exportFolder==null||!Desktop.isDesktopSupported())return;
            try{Desktop.getDesktop().open(exportFolder.toFile());}catch(Exception failure){status.setText("Could not open the export folder: "+failure.getMessage());}
        });openFolder.setEnabled(false);
        placeScope(false);
        JPanel paging=ContentStyle.controls();paging.add(previous);paging.add(next);paging.add(stop);paging.add(cancelExport);stop.setVisible(false);cancelExport.setVisible(false);
        JPanel texts=new JPanel();texts.setLayout(new BoxLayout(texts,BoxLayout.Y_AXIS));texts.add(status);texts.add(saveStatus);
        footer.setName(name+"-archive-footer");footer.add(paging,BorderLayout.NORTH);footer.add(texts,BorderLayout.CENTER);
        add(ContentStyle.page(filterBar,cards,footer));
        sessions.addActionListener(e->{if(!restoring){Choice selected=(Choice)sessions.getSelectedItem();if(selected!=null)selectSession(selected.id);}});
        browse.addActionListener(e->{state=state.withArchive(!state.archive);persist();request(false);});
        reload.addActionListener(e->{reloadCatalog();request(true);});
        stop.addActionListener(e->{invalidateView();cancel.cancel();refresh.invalidate();loading=false;status.setText("Read cancelled. Refresh to retry.");updateActions();});
        search.addActionListener(e->changeQuery(state.query.withText(search.getText())));
        previous.addActionListener(e->selectPage(Math.max(0,state.page-1)));next.addActionListener(e->selectPage(state.page+1));
        cancelExport.addActionListener(e->exportCancel.cancel());
        filterBar.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){fitScope();}});
        search.addPropertyChangeListener("font",e->fitScope());
        addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0){
            // A load started while hidden (for example an atomic restore before its page is shown) continues.
            if(isShowing()){if(!closed&&!loading)request(false);}else{invalidateView();cancel.cancel();refresh.invalidate();loading=false;updateActions();}
        }});
        reloadNames();syncControls();reloadCatalog();request(false);
    }
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Workspace changes require the EDT");}
    public ViewState<F,S> state(){requireEdt();return state;}
    public ArchivePage<R> displayedPage(){requireEdt();return displayed;}
    public boolean loading(){requireEdt();return loading;}
    /** The workspace's filter row: search, the module's Filters drawer and chips, scope and the ⋯ menu. */
    public FilterBar filterBar(){requireEdt();return filterBar;}
    public void selectSession(String id){
        requireEdt();String scope=store.currentId().equals(id)?ArchiveQuery.CURRENT:id;
        state=state.withQuery(state.query.withScope(scope)).withArchive(!ArchiveQuery.CURRENT.equals(scope));persist();syncControls();request(false);
    }
    public void showSaved(){requireEdt();state=state.withArchive(true);persist();request(false);}
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
        state=value;persist();syncControls();request(false);
    }
    public void selectPage(long page){requireEdt();if(page<0)throw new IllegalArgumentException("Negative page");state=state.withPage(page);persist();request(false);}
    public void refresh(){requireEdt();request(true);}
    public CompletionStage<PreferencesStore.SaveResult> saveNamed(String label){requireEdt();CompletionStage<PreferencesStore.SaveResult> save=states.saveNamed(name,label,state);watchSave(save);reloadNames();return save;}
    public void loadNamed(String label){
        requireEdt();try{state=states.loadNamed(name,label,ViewState.initial(client.initialQuery()));persist();syncControls();request(false);}
        catch(RuntimeException failure){saveStatus.setText("Saved view was not applied: "+failure.getMessage());}
    }
    /** Scope controls use the row's right slot when it has room; on narrow rows they wrap after the search field. */
    private void fitScope(){if(!closed)SwingUtilities.invokeLater(()->{if(!closed&&narrowFit()!=narrowScope)placeScope(!narrowScope);});}
    private boolean narrowFit(){
        int needed=search.getPreferredSize().width+browse.getPreferredSize().width+sessions.getPreferredSize().width+reload.getPreferredSize().width
            +filterBar.overflow().getPreferredSize().width+14*search.getFontMetrics(search.getFont()).charWidth('m');
        return filterBar.getWidth()>0&&filterBar.getWidth()<needed;
    }
    private void placeScope(boolean narrow){
        narrowScope=narrow;FilterChips.keepingFocus(()->{
            searchRow.removeAll();scopeRow.removeAll();searchRow.add(search);
            JPanel target=narrow?searchRow:scopeRow;target.add(browse);target.add(sessions);target.add(reload);
            filterBar.search(searchRow).scope(narrow?null:scopeRow);
        });
    }
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
    private void resetSavedState(){watchSave(states.reset(name));stateLoadFailed=false;state=ViewState.initial(client.initialQuery());reloadNames();syncControls();request(true);}
    private void syncControls(){
        restoring=true;try{
            if(sessions.getItemCount()==0){sessions.addItem(new Choice(ArchiveQuery.CURRENT,"Current Session"));sessions.addItem(new Choice(SessionStore.ALL,"All Sessions"));}
            boolean found=false;for(int i=0;i<sessions.getItemCount();i++)if(sessions.getItemAt(i).id.equals(state.query.scope())){sessions.setSelectedIndex(i);found=true;break;}
            if(!found){Choice pending=new Choice(state.query.scope(),"Selected session · "+state.query.scope());sessions.addItem(pending);sessions.setSelectedItem(pending);}
            search.setText(state.query.text());
        }finally{restoring=false;}
    }
    private void reloadCatalog(){
        catalogCancel.cancel();catalogCancel=new Cancellation();Cancellation token=catalogCancel;
        catalog.request(new Object(),()->{try{return store.catalog(token);}catch(Exception failure){throw new IllegalStateException(failure);}},entries->{
            restoring=true;try{sessions.removeAllItems();sessions.addItem(new Choice(ArchiveQuery.CURRENT,"Current Session"));sessions.addItem(new Choice(SessionStore.ALL,"All Sessions"));
                int recent=0;for(SessionStore.SessionEntry entry:entries)if(entry.readable()&&!entry.id.equals(store.currentId())&&(recent++<20||entry.id.equals(state.query.scope())))sessions.addItem(new Choice(entry.id,entry.session().toString()));
            }finally{restoring=false;}syncControls();
        },failure->status.setText("Session list could not be read. Open History library or Refresh."));
    }
    private void request(boolean fresh){
        requireEdt();if(closed)return;invalidateView();cancel.cancel();refresh.invalidate();cancel=new Cancellation();
        ((CardLayout)cards.getLayout()).show(cards,state.archive?"saved":"live");
        footer.setVisible(state.archive);search.setVisible(state.archive);browse.setText(state.archive?"Current live view":"Browse saved");
        if(!state.archive){
            FilterChips.keepingFocus(()->{filterBar.drawer(null);FilterChips.update(filterBar,Collections.<FilterBar.ActiveFilter>emptyList(),null,true);});
            loading=false;updateActions();return;
        }
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
            FilterChips.update(filterBar,filters==null?Collections.<FilterBar.ActiveFilter>emptyList():filters.active,()->changeQuery(client.initialQuery().withScope(state.query.scope())),true);
            filterBar.setDrawerEnabled(true);
        });
        List<String> ordering=new ArrayList<>();for(ArchiveQuery.Order<S> item:query.order())ordering.add(sortLabel(item));
        String empty=displayed.matches==0?(result.scanned==0?"No rows available in this saved query. ":"No matches; use Clear to reset filters. "):"";
        status.setText(empty+displayed.description()+" · sorted by "+String.join(", ",ordering)+" · missing recording metadata means coverage unknown");
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
    private void invalidateView(){viewGeneration++;activeView=null;disable(saved);filterBar.setDrawerEnabled(false);}
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
    @Override public void close(){requireEdt();if(closed)return;closed=true;invalidateView();forgetDisabledStates();cancel.cancel();catalogCancel.cancel();exportCancel.cancel();refresh.invalidate();catalog.invalidate();if(result!=null){result.close();result=null;}}
    private static final class Choice{final String id,label;Choice(String id,String label){this.id=id;this.label=label;}public String toString(){return label;}}
    private static final class Update<R>{final ArchiveResult<R> result;final ArchivePage<R> page;final boolean owns;
        Update(ArchiveResult<R> result,ArchivePage<R> page,boolean owns){this.result=result;this.page=page;this.owns=owns;}
        void discard(){if(owns)result.close();}}
}
