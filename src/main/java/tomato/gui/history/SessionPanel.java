package tomato.gui.history;

import tomato.history.SessionStore;
import tomato.history.AppHistory;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.modern.ContentStyle;
import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Independent session selection for a module. The live producer/view is never replaced by replay. */
public final class SessionPanel extends JPanel {
    @FunctionalInterface public interface Loader { Loaded load(SessionStore store, String scope, int page, String query) throws Exception; }
    public static final class Loaded {
        final Supplier<JComponent> view; final boolean more; final String description;
        public Loaded(Supplier<JComponent> view, boolean more, String description) { this.view=view;this.more=more;this.description=description; }
        public JComponent createView() { return view.get(); }
    }
    private final SessionStore store;
    private final Loader loader;
    private final JComboBox<Choice> sessions = new JComboBox<>();
    private final JPanel content = new JPanel(new CardLayout()) {
        @Override public Dimension getMinimumSize(){Dimension size=super.getMinimumSize();return new Dimension(0,Math.max(220,size.height));}
    }, saved = new JPanel(new BorderLayout()), archiveControls = new JPanel(new BorderLayout(0,4));
    private final JTextField search = new JTextField(18);
    private final JTextArea status = ContentStyle.wrappingText(" "), pageStatus = ContentStyle.wrappingText(" ");
    private final JButton browse = new JButton("Browse saved"), previous = new JButton("Previous page"), next = new JButton("Next page"), delete = new JButton("Delete session…");
    private final SnapshotRefresh<Result> refresh = new SnapshotRefresh<>();
    private final javax.swing.Timer timer;
    private boolean updating, archive;
    private String readError="";
    private int page;

    public static JComponent wrap(String name, JComponent live, Loader loader) {
        return AppHistory.store() == null ? live : new SessionPanel(AppHistory.store(), name, live, loader);
    }
    public SessionPanel(SessionStore store, String name, JComponent live, Loader loader) {
        super(new BorderLayout(0, 6)); this.store=store;this.loader=loader;
        setName(name + "-session-view");
        JPanel top = new JPanel(new BorderLayout()); JPanel controls = ContentStyle.controls();
        sessions.setName(name + "-session-picker"); sessions.getAccessibleContext().setAccessibleName(name + " session");
        sessions.setPrototypeDisplayValue(new Choice("", "Session · 2026-09-20 12:00:00"));
        DefaultListCellRenderer sessionRenderer=new DefaultListCellRenderer();sessionRenderer.putClientProperty("html.disable",true);sessions.setRenderer(sessionRenderer);
        sessions.addItem(new Choice(store.currentId(), "Current Session")); sessions.addItem(new Choice(SessionStore.ALL, "All Sessions"));
        JLabel label = new JLabel("Session"); label.setLabelFor(sessions); controls.add(label);controls.add(sessions);controls.add(browse);
        JButton reload = new JButton("Refresh"); controls.add(reload);controls.add(delete);
        JButton clear = new JButton("Clear past sessions…");controls.add(clear);
        JButton importHistory = new JButton("Import old folder…");controls.add(importHistory);importHistory.setEnabled(store.writable());
        importHistory.setToolTipText("Import saved runs and .fame sessions from a previous RealmShark folder; originals are kept.");
        importHistory.addActionListener(e -> importHistory());
        delete.setEnabled(false);clear.setEnabled(store.writable());
        status.setFont(ContentStyle.metadata(ContentStyle.body())); status.setToolTipText(store.directory().toString());
        top.add(controls, BorderLayout.NORTH);top.add(status, BorderLayout.SOUTH);
        content.add(live, "live");content.add(saved, "saved");
        search.setName(name + "-history-search");search.getAccessibleContext().setAccessibleName("Search all saved " + name + " records");
        search.putClientProperty("JTextField.placeholderText", "Search saved history; press Enter");
        JPanel paging=ContentStyle.controls();paging.add(search);paging.add(previous);paging.add(next);
        archiveControls.add(paging,BorderLayout.NORTH);archiveControls.add(pageStatus,BorderLayout.SOUTH);
        archiveControls.setVisible(false);
        JScrollPane scroll=ContentStyle.page(top,content,archiveControls);scroll.setName(name+"-session-scroll");add(scroll);
        sessions.addActionListener(e -> { if (!updating) { page=0;archive=!scope().equals(store.currentId());request(); } });
        browse.addActionListener(e -> { if (!scope().equals(store.currentId())) { selectSession(store.currentId());return; } archive=!archive;page=0;request(); });
        reload.addActionListener(e -> request());search.addActionListener(e -> {page=0;request();});
        previous.addActionListener(e -> { if(page>0){page--;request();} });next.addActionListener(e -> {page++;request();});
        delete.addActionListener(e -> delete(false));clear.addActionListener(e -> delete(true));
        timer = new javax.swing.Timer(1000, e -> updateStatus());
        addHierarchyListener(e -> { if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0){
            if(isShowing()){timer.start();request();}else{timer.stop();refresh.invalidate();}
        }});
        updateStatus();
    }
    public void selectSession(String id) { for(int i=0;i<sessions.getItemCount();i++)if(sessions.getItemAt(i).id.equals(id)){sessions.setSelectedIndex(i);return;} }
    private String scope(){Choice choice=(Choice)sessions.getSelectedItem();return choice==null?store.currentId():choice.id;}
    private void request() {
        readError="";
        String id=scope(), query=search.getText();int selectedPage=page;boolean historical=archive;
        ((CardLayout)content.getLayout()).show(content,historical?"saved":"live");
        archiveControls.setVisible(historical);browse.setText(historical?"Current live view":"Browse saved");
        delete.setEnabled(store.writable()&&!id.equals(store.currentId())&&!id.equals(SessionStore.ALL));
        if(historical){saved.removeAll();saved.add(new JLabel("Loading saved history…",SwingConstants.CENTER));saved.revalidate();}
        refresh.request(id+":"+selectedPage+":"+query+":"+historical,()->{
            try{
                if(historical&&store.writable()&&(id.equals(store.currentId())||id.equals(SessionStore.ALL)))store.flush();
                return new Result(store.sessions(),historical?loader.load(store,id,selectedPage,query):null);
            }catch(Exception e){throw new IllegalStateException(e);}
        },result->{
            updating=true;
            try{
                sessions.removeAllItems();sessions.addItem(new Choice(store.currentId(),"Current Session"));sessions.addItem(new Choice(SessionStore.ALL,"All Sessions"));
                for(SessionStore.Session session:result.sessions)if(!session.id.equals(store.currentId()))sessions.addItem(new Choice(session.id,session.toString()));
                selectSession(id);
            }finally{updating=false;}
            if(!scope().equals(id)){archive=false;page=0;request();return;}
            if(result.loaded!=null){saved.removeAll();saved.add(result.loaded.createView());saved.revalidate();saved.repaint();
                previous.setEnabled(page>0);next.setEnabled(result.loaded.more);pageStatus.setText(result.loaded.description);pageStatus.setToolTipText(result.loaded.description);}
            updateStatus();
        },failure->{saved.removeAll();saved.add(new JLabel("Could not read saved history. Use Refresh to retry.",SwingConstants.CENTER));saved.revalidate();
            readError="History read failed: "+failure.getMessage();updateStatus();});
    }
    private void updateStatus(){String error=store.error();if(error.isEmpty())error=readError;status.setText(error.isEmpty()?(store.writable()?"History saves automatically · retained until deleted":"Preview · saved history is read-only"):error);
        status.setForeground(ContentStyle.color(error.isEmpty()?"muted":"rose"));status.setToolTipText(error.isEmpty()?store.directory().toString():error);}
    private void delete(boolean all) {
        String id=scope();
        String question=all?"Delete every past session across all modules? The current session continues recording.":"Delete this saved session across all modules?";
        if(JOptionPane.showConfirmDialog(this,question,"Delete saved history",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        new SwingWorker<Void,Void>(){
            protected Void doInBackground()throws Exception{
                if(all){for(SessionStore.Session session:store.sessions())if(!session.id.equals(store.currentId()))store.delete(session.id);}
                else store.delete(id);return null;
            }
            protected void done(){try{get();selectSession(store.currentId());request();}catch(Exception e){JOptionPane.showMessageDialog(SessionPanel.this,"Could not delete history: "+e.getMessage());}}
        }.execute();
    }
    private void importHistory() {
        JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        java.nio.file.Path directory=chooser.getSelectedFile().toPath();
        new SwingWorker<Void,Void>(){
            protected Void doInBackground()throws Exception{AppHistory.importLegacy(store,directory);return null;}
            protected void done(){try{get();request();}catch(Exception e){JOptionPane.showMessageDialog(SessionPanel.this,"Import failed; original files were kept. "+e.getMessage());}}
        }.execute();
    }
    private static final class Choice { final String id,label;Choice(String id,String label){this.id=id;this.label=label;}public String toString(){return label;} }
    private static final class Result { final List<SessionStore.Session> sessions;final Loaded loaded;Result(List<SessionStore.Session>s,Loaded l){sessions=s;loaded=l;} }
}
