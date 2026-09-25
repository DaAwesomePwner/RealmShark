package tomato.gui.stats;

import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import tomato.gui.history.*;
import tomato.history.archive.*;

/** Explicitly bound live presentation controls, independent of the workspace's saved-data query. */
final class StatisticsLiveState {
    enum Order { NONE }
    static final class Fields { Map<String,String> values=new LinkedHashMap<>(); }
    private final ViewStateStore store;private final String key;
    private ViewState<Fields,Order> state;private Fields fields;private boolean restoring;
    private boolean saveBlocked;private long saveGeneration;
    private final JTextArea status=tomato.gui.modern.ContentStyle.wrappingText("");
    StatisticsLiveState(ViewStateStore store,String key){
        this.store=store;this.key=key;state=ViewState.initial(ArchiveQuery.of(ArchiveQuery.CURRENT,new Fields(),Fields.class,Order.NONE));
        try{ViewState<Fields,Order> saved=store.load(key,state);if(saved.query.facets().values==null)throw new IllegalArgumentException("Missing live controls");state=saved;}catch(RuntimeException failure){reject(failure.getMessage());}fields=state.query.facets();
    }
    StatisticsLiveState attach(JPanel owner){
        JPanel feedback=new JPanel(new java.awt.BorderLayout(5,0));status.setName(key+"-state-status");status.getAccessibleContext().setAccessibleName("Live view state persistence");feedback.add(status);
        JButton reset=new JButton("Reset saved live view"),retry=new JButton("Retry view save");retry.setName(key+"-retry-state");retry.addActionListener(e->save());reset.addActionListener(e->{saveBlocked=false;watch(store.reset(key));});JPanel actions=tomato.gui.modern.ContentStyle.controls();actions.add(retry);actions.add(reset);feedback.add(actions,java.awt.BorderLayout.EAST);
        java.awt.BorderLayout layout=(java.awt.BorderLayout)owner.getLayout();java.awt.Component old=layout.getLayoutComponent(java.awt.BorderLayout.SOUTH);JPanel footer=new JPanel(new java.awt.BorderLayout(0,4));if(old!=null)footer.add(old);footer.add(feedback,java.awt.BorderLayout.SOUTH);owner.add(footer,java.awt.BorderLayout.SOUTH);return this;
    }
    void reject(String message){saveBlocked=true;status.setText("Saved live view unavailable: "+message+". Reset saved live view to enable saving; current controls remain usable.");}
    private void save(){if(!saveBlocked)try{watch(store.save(key,state));}catch(RuntimeException failure){status.setText("Live view active; state save failed: "+failure.getMessage());}}
    private void watch(java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> save){long generation=++saveGeneration;save.whenComplete((result,error)->SwingUtilities.invokeLater(()->{if(generation==saveGeneration)status.setText(error==null&&result!=null&&result.isSuccess()?"Live view state saved":"Live view active; state save failed. Retry view save keeps the current controls.");}));}
    String value(String key,String fallback){return fields.values.getOrDefault(key,fallback);}
    void put(String key,String value){if(restoring||Objects.equals(fields.values.get(key),value))return;fields.values.put(key,value);state=state.withQuery(state.query.withFacets(fields));save();}
    void tabs(JTabbedPane tabs){int index=parse(value(tabs.getName(),"0"),0);if(index>=0&&index<tabs.getTabCount())tabs.setSelectedIndex(index);tabs.addChangeListener(e->put(tabs.getName(),Integer.toString(tabs.getSelectedIndex())));}
    void text(JTextField field){field.setText(value(field.getName(),field.getText()));field.getDocument().addDocumentListener(new DocumentListener(){private void save(){put(field.getName(),field.getText());}public void insertUpdate(DocumentEvent e){save();}public void removeUpdate(DocumentEvent e){save();}public void changedUpdate(DocumentEvent e){save();}});}
    void combo(JComboBox<?> combo){String selected=value(combo.getName(),String.valueOf(combo.getSelectedItem()));combo.setSelectedItem(selected);combo.addActionListener(e->put(combo.getName(),String.valueOf(combo.getSelectedItem())));}
    void check(JCheckBox check){check.setSelected(Boolean.parseBoolean(value(check.getName(),Boolean.toString(check.isSelected()))));check.addActionListener(e->put(check.getName(),Boolean.toString(check.isSelected())));}
    void table(JTable table,JScrollPane scroll){
        table(table,scroll,row->Objects.toString(table.getModel().getValueAt(row,0),""));
    }
    void table(JTable table,JScrollPane scroll,java.util.function.IntFunction<String> rowKey){
        if(state.tables.containsKey(table.getName()))HistoryTables.applyColumns(table,state.tables.get(table.getName()));
        if(table.getRowSorter()!=null){String[] saved=value(table.getName()+".sort","").split(":");if(saved.length==2)try{int column=Integer.parseInt(saved[0]);if(column>=0&&column<table.getModel().getColumnCount())table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(column,SortOrder.valueOf(saved[1]))));}catch(IllegalArgumentException ignored){ }
            table.getRowSorter().addRowSorterListener(e->{List<? extends RowSorter.SortKey> order=table.getRowSorter().getSortKeys();if(!order.isEmpty())put(table.getName()+".sort",order.get(0).getColumn()+":"+order.get(0).getSortOrder());});}
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener(){private boolean pending;private void save(){if(restoring||pending)return;pending=true;SwingUtilities.invokeLater(()->{pending=false;state=state.withTable(table.getName(),HistoryTables.columnState(table,"Custom"));StatisticsLiveState.this.save();});}public void columnAdded(TableColumnModelEvent e){}public void columnRemoved(TableColumnModelEvent e){}public void columnMoved(TableColumnModelEvent e){save();}public void columnMarginChanged(ChangeEvent e){save();}public void columnSelectionChanged(ListSelectionEvent e){}});
        String selected=value(table.getName()+".selected","");
        Runnable restore=()->{restoring=true;try{for(int i=0;i<table.getRowCount();i++)if(rowKey.apply(table.convertRowIndexToModel(i)).equals(value(table.getName()+".selected",selected))){table.setRowSelectionInterval(i,i);break;}int y=parse(value(table.getName()+".scroll","0"),0);scroll.getViewport().setViewPosition(new java.awt.Point(0,Math.min(y,Math.max(0,table.getHeight()-scroll.getViewport().getHeight()))));}finally{restoring=false;}};
        boolean[] pendingRestore={false};table.getModel().addTableModelListener(e->{if(!pendingRestore[0]){pendingRestore[0]=true;SwingUtilities.invokeLater(()->{pendingRestore[0]=false;restore.run();});}});restore.run();
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&table.getSelectedRow()>=0)put(table.getName()+".selected",rowKey.apply(table.convertRowIndexToModel(table.getSelectedRow())));});
        scroll.getViewport().addChangeListener(e->{if(table.isShowing())put(table.getName()+".scroll",Integer.toString(scroll.getViewport().getViewPosition().y));});
    }
    private static int parse(String value,int fallback){try{return Integer.parseInt(value);}catch(NumberFormatException e){return fallback;}}
}
