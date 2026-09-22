package tomato.gui.history;

import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.modern.ContentStyle;
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
    private final JComboBox<Choice> sessions=new JComboBox<>();private final JTextField search=new JTextField(18),viewName=new JTextField(12);
    private final JComboBox<String> named=new JComboBox<>();
    private final JPanel cards=new JPanel(new CardLayout()),saved=new JPanel(new BorderLayout()),archiveTools=new JPanel(new BorderLayout());
    private final JTextArea status=ContentStyle.wrappingText("Current live view"),saveStatus=ContentStyle.wrappingText("");
    private final JButton previous=new JButton("Previous page"),next=new JButton("Next page"),browse=new JButton("Browse saved");
    private final JButton exportAll=new JButton("Export all matches…"),exportPage=new JButton("Export page…"),exportSelected=new JButton("Export selected…");
    private final JButton openFolder=new JButton("Open export folder");
    private Path exportFolder;
    private String librarySelection;
    private ViewState<F,S> state;private ArchiveResult<R> result;private ArchiveQuery<F,S> resultQuery;
    private ArchivePage<R> displayed;
    private Cancellation cancel=new Cancellation(),catalogCancel=new Cancellation(),exportCancel=new Cancellation();
    private boolean restoring,loading,exporting,closed,stateLoadFailed;
    private long viewGeneration,saveGeneration;

    ArchiveWorkspace(SessionStore store,String name,JComponent live,ArchiveClient<R,F,S> client,ViewStateStore states){
        super(new BorderLayout(0,6));requireEdt();this.store=store;this.name=name;this.client=client;this.states=states;
        if(client.pageSize()<1||client.pageSize()>1000)throw new IllegalArgumentException("Page size must be 1–1000");
        state=ViewState.initial(client.initialQuery());
        try{state=states.load(name,state);}catch(RuntimeException failure){stateLoadFailed=true;saveStatus.setText(failure.getMessage());}
        setName(name+"-session-view");sessions.setName(name+"-session-picker");sessions.getAccessibleContext().setAccessibleName(name+" session");
        sessions.setPrototypeDisplayValue(new Choice("","Session · 2026-09-20 12:00:00"));
        DefaultListCellRenderer literal=new DefaultListCellRenderer();literal.putClientProperty("html.disable",true);sessions.setRenderer(literal);
        JPanel top=ContentStyle.controls();top.add(new JLabel("Session"));top.add(sessions);top.add(browse);
        JButton reload=new JButton("Refresh"),library=new JButton("History library…"),stop=new JButton("Cancel read");
        top.add(reload);top.add(library);top.add(stop);
        cards.add(live,"live");cards.add(saved,"saved");
        search.setName(name+"-history-search");search.getAccessibleContext().setAccessibleName("Search all saved "+name+" records");
        search.putClientProperty("JTextField.placeholderText","Search entire scope; press Enter");
        JPanel paging=ContentStyle.controls();paging.add(search);paging.add(previous);paging.add(next);
        JButton resetFilters=new JButton("Reset filters");paging.add(resetFilters);archiveTools.add(paging,BorderLayout.NORTH);
        JPanel tools=new JPanel();tools.setLayout(new BoxLayout(tools,BoxLayout.Y_AXIS));
        JPanel views=ContentStyle.controls();named.setName(name+"-named-views");named.getAccessibleContext().setAccessibleName("Named "+name+" views");
        viewName.getAccessibleContext().setAccessibleName("Saved view name");JButton saveView=new JButton("Save view"),loadView=new JButton("Load view"),deleteView=new JButton("Delete view"),resetState=new JButton("Reset saved state");
        views.add(named);views.add(loadView);views.add(viewName);views.add(saveView);views.add(deleteView);views.add(resetState);
        JPanel exports=ContentStyle.controls();exports.add(exportSelected);exports.add(exportPage);exports.add(exportAll);
        openFolder.setEnabled(false);exports.add(openFolder);openFolder.addActionListener(e->{
            if(exportFolder==null||!Desktop.isDesktopSupported())return;
            try{Desktop.getDesktop().open(exportFolder.toFile());}catch(Exception failure){status.setText("Could not open the export folder: "+failure.getMessage());}
        });
        JButton cancelExport=new JButton("Cancel export");exports.add(cancelExport);
        tools.add(views);tools.add(exports);tools.add(status);tools.add(saveStatus);archiveTools.add(tools,BorderLayout.SOUTH);
        add(ContentStyle.page(top,cards,archiveTools));
        sessions.addActionListener(e->{if(!restoring){Choice selected=(Choice)sessions.getSelectedItem();if(selected!=null)selectSession(selected.id);}});
        browse.addActionListener(e->{state=state.withArchive(!state.archive);persist();request(false);});
        reload.addActionListener(e->{reloadCatalog();request(true);});library.addActionListener(e->openLibrary());
        stop.addActionListener(e->{cancel.cancel();refresh.invalidate();loading=false;status.setText("Read cancelled. Refresh to retry.");updateActions();});
        search.addActionListener(e->changeQuery(state.query.withText(search.getText())));
        resetFilters.addActionListener(e->changeQuery(client.initialQuery().withScope(state.query.scope())));
        previous.addActionListener(e->selectPage(Math.max(0,state.page-1)));next.addActionListener(e->selectPage(state.page+1));
        saveView.addActionListener(e->{try{saveNamed(viewName.getText());reloadNames();}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}});
        loadView.addActionListener(e->{if(named.getSelectedItem()!=null)loadNamed(named.getSelectedItem().toString());});
        deleteView.addActionListener(e->{if(named.getSelectedItem()!=null){watchSave(states.deleteNamed(name,named.getSelectedItem().toString()));reloadNames();}});
        resetState.addActionListener(e->{watchSave(states.reset(name));stateLoadFailed=false;state=ViewState.initial(client.initialQuery());reloadNames();syncControls();request(true);});
        exportAll.addActionListener(e->chooseExport(ExportSelection.all()));
        exportPage.addActionListener(e->{if(displayed!=null)chooseExport(ExportSelection.page(displayed.page,displayed.size));});
        exportSelected.addActionListener(e->chooseExport(ExportSelection.selected(state.selected)));cancelExport.addActionListener(e->exportCancel.cancel());
        addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0){
            if(isShowing()){if(!closed)request(false);}else{cancel.cancel();refresh.invalidate();loading=false;updateActions();}
        }});
        reloadNames();syncControls();reloadCatalog();request(false);
    }
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Workspace changes require the EDT");}
    public ViewState<F,S> state(){requireEdt();return state;}
    public ArchivePage<R> displayedPage(){requireEdt();return displayed;}
    public boolean loading(){requireEdt();return loading;}
    public void selectSession(String id){
        requireEdt();String scope=store.currentId().equals(id)?ArchiveQuery.CURRENT:id;
        state=state.withQuery(state.query.withScope(scope)).withArchive(!ArchiveQuery.CURRENT.equals(scope));persist();syncControls();request(false);
    }
    public void showSaved(){requireEdt();state=state.withArchive(true);persist();request(false);}
    public void changeQuery(ArchiveQuery<F,S> query){
        requireEdt();if(restoring||closed)return;state=state.withQuery(query).withArchive(true);persist();syncControls();request(false);
    }
    public void selectPage(long page){requireEdt();if(page<0)throw new IllegalArgumentException("Negative page");state=state.withPage(page);persist();request(false);}
    public void refresh(){requireEdt();request(true);}
    public CompletionStage<PreferencesStore.SaveResult> saveNamed(String label){requireEdt();CompletionStage<PreferencesStore.SaveResult> save=states.saveNamed(name,label,state);watchSave(save);return save;}
    public void loadNamed(String label){
        requireEdt();try{state=states.loadNamed(name,label,ViewState.initial(client.initialQuery()));persist();syncControls();request(false);}
        catch(RuntimeException failure){saveStatus.setText("Saved view was not applied: "+failure.getMessage());}
    }
    private void reloadNames(){try{Object selected=named.getSelectedItem();named.removeAllItems();for(String value:states.names(name))named.addItem(value);if(selected!=null)named.setSelectedItem(selected);}
        catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}}
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
        requireEdt();if(closed)return;cancel.cancel();refresh.invalidate();cancel=new Cancellation();
        ((CardLayout)cards.getLayout()).show(cards,state.archive?"saved":"live");
        archiveTools.setVisible(state.archive);browse.setText(state.archive?"Current live view":"Browse saved");
        if(!state.archive){loading=false;updateActions();return;}
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
        if(closed){update.discard();return;}long ticket=++viewGeneration;
        ViewState<F,S> nextState=state.withPage(update.page.page);JComponent view;
        restoring=true;
        try{view=client.render(update.page,nextState,new ArchiveClient.Binding<F,S>(){
            public void queryChanged(ArchiveQuery<F,S> value){if(ticket==viewGeneration)changeQuery(value);}
            public void refresh(){requireEdt();if(!restoring&&!closed&&ticket==viewGeneration)request(true);}
            public void viewChanged(ViewState<F,S> value){requireEdt();if(!restoring&&!closed&&ticket==viewGeneration&&value.query.equals(state.query)){
                state=new ViewState<>(state.query,state.archive,state.page,value.tab,value.selected,value.anchor,value.anchorOffset,value.tables);persist();updateActions();}}
        });}catch(RuntimeException failure){update.discard();throw failure;}finally{restoring=false;}
        ArchiveResult<R> old=result;result=update.result;resultQuery=query;displayed=update.page;state=nextState;
        saved.removeAll();saved.add(view);saved.revalidate();saved.repaint();loading=false;
        List<String> ordering=new ArrayList<>();for(ArchiveQuery.Order<S> item:query.order())ordering.add(item.field.name()+" "+item.direction);
        String empty=displayed.matches==0?(result.scanned==0?"No rows available in this saved query. ":"No matches; try Reset filters. "):"";
        status.setText(empty+displayed.description()+" · sorted by "+String.join(", ",ordering)+" · missing recording metadata means coverage unknown");
        if(old!=null&&old!=result)old.close();persist();updateActions();
    }
    private void persist(){if(stateLoadFailed)return;try{watchSave(states.save(name,state));}catch(RuntimeException failure){saveStatus.setText(failure.getMessage());}}
    private void watchSave(CompletionStage<PreferencesStore.SaveResult> save){
        long generation=++saveGeneration;save.whenComplete((value,failure)->SwingUtilities.invokeLater(()->{
            if(closed||generation!=saveGeneration)return;
            saveStatus.setText(failure==null&&value!=null&&value.isSuccess()?"View state saved":"View state is active; save failed. Change or save the view again to retry.");
        }));
    }
    private boolean canExport(){return !closed&&!loading&&!exporting&&state.archive&&result!=null&&state.query.equals(resultQuery);}
    private void updateActions(){boolean ready=canExport();exportAll.setEnabled(ready);exportPage.setEnabled(ready);exportSelected.setEnabled(ready&&!state.selected.isEmpty());
        previous.setEnabled(!loading&&state.page>0);next.setEnabled(!loading&&displayed!=null&&displayed.more());}
    public SwingWorker<Path,Void> exportTo(Path directory,String base,ExportSelection selection,ArchiveExport.Format format)throws java.io.IOException {
        requireEdt();if(!canExport())throw new IllegalStateException("Wait for the displayed query revision before exporting");
        List<ArchiveExport.Column<R>> columns=new ArrayList<>(client.exportColumns());
        ArchiveResult.Lease<R> lease=result.lease();String exportedUnit=result.unit,exportedRevision=result.revision;
        return startExport(lease,directory,base,selection,format,columns,exportedUnit,exportedRevision);
    }
    private SwingWorker<Path,Void> startExport(ArchiveResult.Lease<R> lease,Path directory,String base,ExportSelection selection,
            ArchiveExport.Format format,List<ArchiveExport.Column<R>> columns,String exportedUnit,String exportedRevision){
        exportCancel=new Cancellation();Cancellation token=exportCancel;
        java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean();exporting=true;updateActions();
        SwingWorker<Path,Void> worker=new SwingWorker<Path,Void>(){
            protected Path doInBackground()throws Exception{
                if(!claimed.compareAndSet(false,true))throw new java.util.concurrent.CancellationException();
                try(ArchiveResult.Lease<R> held=lease){return ArchiveExport.write(held,selection,format,directory,base,columns,token);}
            }
            protected void done(){exporting=false;try{Path path=get();exportFolder=path.toAbsolutePath().getParent();openFolder.setEnabled(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
                status.setText("Exported "+selection.expected(lease.matches())+" "+exportedUnit+" · revision "+exportedRevision.substring(0,8)+" · "+path.getFileName());}
                catch(Exception failure){status.setText(token.isCancelled()||isCancelled()?"Export cancelled; unfinished output cleanup requested":"Export failed: "+failure.getMessage());}
                finally{if(claimed.compareAndSet(false,true))lease.close();updateActions();}}
        };worker.execute();return worker;
    }
    private void chooseExport(ExportSelection selection){
        if(!canExport())return;
        ArchiveResult.Lease<R> held=null;boolean handedOff=false;
        try {
            ArchiveResult<R> source=result;ViewState<F,S> intent=state;
            List<ArchiveExport.Column<R>> columns=new ArrayList<>(client.exportColumns());held=source.lease();
            String message=selection.expected(source.matches)+" "+source.unit+" · "+selection.kind+"\nRevision "+source.revision
                    +"\nSource sessions (including dependencies): "+held.manifest().getAsJsonArray("sessions").size()
                    +"\nResolved bounds: "+intent.query.bounds().from+" to "+intent.query.bounds().until+" (exclusive), "+intent.query.bounds().zone
                    +"\nQuery and ordering: "+intent.query.toJson();
            JTextArea preview=ContentStyle.wrappingText(message);preview.getAccessibleContext().setAccessibleName("Export population and revision");
            JScrollPane previewScroll=new JScrollPane(preview);previewScroll.setPreferredSize(new Dimension(600,240));
            Object format=JOptionPane.showInputDialog(this,previewScroll,"Export pinned revision",JOptionPane.PLAIN_MESSAGE,null,ArchiveExport.Format.values(),ArchiveExport.Format.JSON);
            if(!(format instanceof ArchiveExport.Format)||closed)return;JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION||closed)return;
            if(exporting)throw new IllegalStateException("Wait for the running export to finish");
            // Modal dialogs run a nested EDT loop: the displayed result may have changed since preview.
            startExport(held,chooser.getSelectedFile().toPath(),name+"-"+System.currentTimeMillis(),selection,(ArchiveExport.Format)format,columns,source.unit,source.revision);
            handedOff=true;
        }catch(java.io.IOException|RuntimeException failure){status.setText("Export could not start: "+failure.getMessage());}
        finally{if(!handedOff&&held!=null)held.close();}
    }
    private void openLibrary(){
        Window parent=SwingUtilities.getWindowAncestor(this);JDialog dialog=new JDialog(parent,"History library",Dialog.ModalityType.MODELESS);
        HistoryLibrary library=new HistoryLibrary(store,id->{selectSession(id);showSaved();dialog.dispose();},librarySelection==null?state.query.resolvedScope(store):librarySelection);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){librarySelection=library.rememberedSelection();library.close();if(!closed)reloadCatalog();}});
        dialog.setContentPane(library);dialog.setSize(900,550);dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    @Override public void removeNotify(){if(!closed){cancel.cancel();refresh.invalidate();if(result!=null){result.close();result=null;displayed=null;}}super.removeNotify();}
    @Override public void close(){requireEdt();if(closed)return;closed=true;cancel.cancel();catalogCancel.cancel();exportCancel.cancel();refresh.invalidate();catalog.invalidate();if(result!=null){result.close();result=null;}}
    private static final class Choice{final String id,label;Choice(String id,String label){this.id=id;this.label=label;}public String toString(){return label;}}
    private static final class Update<R>{final ArchiveResult<R> result;final ArchivePage<R> page;final boolean owns;
        Update(ArchiveResult<R> result,ArchivePage<R> page,boolean owns){this.result=result;this.page=page;this.owns=owns;}
        void discard(){if(owns)result.close();}}
}
