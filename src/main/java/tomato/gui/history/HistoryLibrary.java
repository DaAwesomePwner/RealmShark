package tomato.gui.history;

import tomato.history.*;
import tomato.history.archive.Cancellation;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.modern.ContentStyle;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Searchable metadata library. Opening a session changes only the requesting workspace. */
public final class HistoryLibrary extends JPanel implements AutoCloseable {
    private final SessionStore store;
    private final Consumer<String> open;
    private final SnapshotRefresh<List<SessionStore.SessionEntry>> refresh=new SnapshotRefresh<>();
    private final List<SessionStore.SessionEntry> entries=new ArrayList<>();
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Session","Started","Build","Available modules","Storage / coverage"},0){
        public boolean isCellEditable(int r,int c){return false;}
        public Class<?> getColumnClass(int c){return c==1?Instant.class:String.class;}
    };
    private final JTable table=new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter=new TableRowSorter<>(model);
    private final JTextArea status=ContentStyle.wrappingText("Loading session metadata…");
    private final JButton openButton=new JButton("Open session"),remove=new JButton("Delete…"),rename=new JButton("Rename…");
    private Cancellation cancel=new Cancellation();
    private String rememberedId;
    public HistoryLibrary(SessionStore store,Consumer<String> open){
        this(store,open,null);
    }
    public HistoryLibrary(SessionStore store,Consumer<String> open,String selectedId){
        super(new BorderLayout(0,6));this.store=store;this.open=open;setName("history-library");
        rememberedId=selectedId;
        table.setName("history-library-table");table.getAccessibleContext().setAccessibleName("Saved session library");
        ContentStyle.table(table);table.setRowSorter(sorter);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);for(int c=0;c<5;c++)table.getColumnModel().getColumn(c).setPreferredWidth(c==4?310:180);
        JTextField search=new JTextField(22);search.setName("history-library-search");search.getAccessibleContext().setAccessibleName("Search sessions, builds and modules");
        search.getDocument().addDocumentListener(new DocumentListener(){private void changed(){sorter.setRowFilter(search.getText().isEmpty()?null:RowFilter.regexFilter("(?i)"+Pattern.quote(search.getText())));updateActions();}
            public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}});
        JButton reload=new JButton("Refresh"),imports=new JButton("Import old folder…");JPanel tools=ContentStyle.controls();
        tools.add(new JLabel("Search"));tools.add(search);tools.add(reload);tools.add(openButton);tools.add(rename);tools.add(remove);tools.add(imports);
        add(tools,BorderLayout.NORTH);add(ContentStyle.tableScroll(table,5));add(status,BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){String id=selectedId();if(id!=null)rememberedId=id;updateActions();}});
        reload.addActionListener(e->reload());openButton.addActionListener(e->openSelected());
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"open-session");table.getActionMap().put("open-session",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){openSelected();}});
        remove.addActionListener(e->{String id=selectedId();if(id!=null&&JOptionPane.showConfirmDialog(this,"Delete this session across all modules?","Delete saved session",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)operation(()->store.delete(id));});
        rename.addActionListener(e->{String id=selectedId();if(id==null)return;String label=JOptionPane.showInputDialog(this,"Session label");if(label!=null)operation(()->store.rename(id,label));});
        imports.setEnabled(store.writable());imports.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)operation(()->AppHistory.importLegacy(store,chooser.getSelectedFile().toPath()));});
        updateActions();reload();
    }
    public void reload(){
        cancel.cancel();cancel=new Cancellation();Cancellation request=cancel;
        refresh.request(new Object(),()->{try{return store.catalog(request);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}},values->{
            entries.clear();entries.addAll(values);model.setRowCount(0);long failed=0;
            for(SessionStore.SessionEntry entry:values){SessionStore.Session session=entry.session();if(session==null)failed++;
                String health=!entry.readable()?entry.error:!entry.persisted?"Current session · no saved records yet":session.ended==0?"Open / interrupted session; coverage may be partial":"Saved; recording coverage unknown unless declared";
                if(session!=null&&session.availability!=null&&!session.availability.isEmpty()){
                    List<String> evidence=new ArrayList<>();for(String module:new TreeSet<>(session.availability.keySet())){SessionStore.ModuleAvailability a=entry.availability(module);evidence.add(module+": "+a.state+" · "+a.reason);}health=String.join("; ",evidence);
                }
                model.addRow(new Object[]{session==null?entry.id:session.toString(),session==null?null:Instant.ofEpochMilli(session.started),session==null?"—":session.version,String.join(", ",entry.modules),health});
            }
            select(rememberedId);status.setText(values.size()+" sessions · "+failed+" unreadable · module presence is not complete recording coverage");updateActions();
        },failure->status.setText("History library could not be read. Refresh to retry."));
    }
    public List<SessionStore.SessionEntry> entries(){return Collections.unmodifiableList(new ArrayList<>(entries));}
    public String selectedId(){int view=table.getSelectedRow();return view<0?null:entries.get(table.convertRowIndexToModel(view)).id;}
    public String rememberedSelection(){return rememberedId;}
    public void select(String id){if(id==null)return;rememberedId=id;for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(id)){int view=table.convertRowIndexToView(i);if(view>=0)table.setRowSelectionInterval(view,view);return;}}
    private SessionStore.SessionEntry selected(){int view=table.getSelectedRow();return view<0?null:entries.get(table.convertRowIndexToModel(view));}
    private void updateActions(){SessionStore.SessionEntry entry=selected();openButton.setEnabled(entry!=null&&entry.readable());boolean editable=entry!=null&&store.writable()&&!store.currentId().equals(entry.id);
        remove.setEnabled(editable);rename.setEnabled(editable&&entry.readable());}
    private void openSelected(){SessionStore.SessionEntry entry=selected();if(entry!=null&&entry.readable())open.accept(entry.id);}
    @FunctionalInterface private interface Operation{void run()throws Exception;}
    private void operation(Operation operation){status.setText("Updating history…");new SwingWorker<Void,Void>(){
        protected Void doInBackground()throws Exception{operation.run();return null;}
        protected void done(){try{get();reload();}catch(Exception failure){status.setText("History action failed; originals may still be in use. "+failure.getMessage());}}
    }.execute();}
    @Override public void close(){cancel.cancel();refresh.invalidate();}
}
