package tomato.gui.history;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.history.archive.*;
import javax.swing.table.*;
import java.util.*;
import java.util.function.*;
import java.awt.event.*;

public final class HistoryTables {
    private HistoryTables() { }
    public static JTable table(String name, String[] columns, Class<?>[] types, List<Object[]> rows) {
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r,int c){return false;}
            public Class<?> getColumnClass(int c){return types[c];}
        };
        for(Object[] row:rows)model.addRow(row);
        JTable table=new JTable(model);table.setName(name);ContentStyle.table(table);table.setAutoCreateRowSorter(true);
        table.getAccessibleContext().setAccessibleName(name.replace('-', ' '));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Double.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(value==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatNumber(((Number)value).doubleValue(),0,2));}});
        table.setDefaultRenderer(Long.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(DisplayFormat.formatInteger((Long)value));}});
        table.setDefaultRenderer(Instant.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(DisplayFormat.formatTimestamp((Instant)value));}});
        for(int i=0;i<columns.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(i==0?240:145);
        return table;
    }
    public static JComponent page(JTable table,String note){
        JPanel panel=new JPanel(new BorderLayout(0,6));panel.add(ContentStyle.tableScroll(table,3));
        panel.add(ContentStyle.wrappingText(note),BorderLayout.SOUTH);return panel;
    }
    public static final class Column<R,V> {
        public final String id,label;public final Class<V> type;public final Function<R,V> value;
        public final TableCellRenderer renderer;
        public Column(String id,String label,Class<V> type,Function<R,V> value,TableCellRenderer renderer){
            this.id=Objects.requireNonNull(id);this.label=Objects.requireNonNull(label);this.type=Objects.requireNonNull(type);
            this.value=Objects.requireNonNull(value);this.renderer=renderer;
        }
    }
    /** The model is already globally ordered. Header/keyboard sorting sends query intent upstream. */
    public static <R,F,S extends Enum<S>> JTable queried(String name,List<Column<R,?>> columns,ArchivePage<R> page,
            Map<String,S> sorts,ArchiveQuery<F,S> query,Consumer<ArchiveQuery<F,S>> changed,Consumer<ArchiveRow<R>> detail) {
        String[] labels=new String[columns.size()];Class<?>[] types=new Class<?>[columns.size()];List<Object[]> rows=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(int c=0;c<columns.size();c++){Column<R,?> column=columns.get(c);if(!ids.add(column.id))throw new IllegalArgumentException("Duplicate column ID");labels[c]=column.label;types[c]=column.type;}
        for(ArchiveRow<R> row:page.rows){Object[] values=new Object[columns.size()];for(int c=0;c<columns.size();c++)values[c]=columns.get(c).value.apply(row.value);rows.add(values);}
        JTable table=table(name,labels,types,rows);table.setAutoCreateRowSorter(false);table.setRowSorter(null);
        for(int c=0;c<columns.size();c++){TableColumn column=table.getColumnModel().getColumn(c);column.setIdentifier(columns.get(c).id);if(columns.get(c).renderer!=null)column.setCellRenderer(columns.get(c).renderer);}
        Consumer<ArchiveQuery.Direction> sort=direction->{
            int view=table.getSelectedColumn();if(view<0)view=0;if(table.getColumnCount()==0)return;
            S field=sorts.get(table.getColumnModel().getColumn(view).getIdentifier().toString());
            if(field!=null)changed.accept(query.withOrder(Collections.singletonList(new ArchiveQuery.Order<>(field,direction))));
        };
        table.getTableHeader().addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){
            int view=table.columnAtPoint(event.getPoint());if(view<0)return;
            S field=sorts.get(table.getColumnModel().getColumn(view).getIdentifier().toString());if(field==null)return;
            ArchiveQuery.Direction next=!query.order().isEmpty()&&query.order().get(0).field==field
                    &&query.order().get(0).direction==ArchiveQuery.Direction.ASCENDING?ArchiveQuery.Direction.DESCENDING:ArchiveQuery.Direction.ASCENDING;
            changed.accept(query.withOrder(Collections.singletonList(new ArchiveQuery.Order<>(field,next))));
        }});
        action(table,"control shift UP","archive-sort-ascending",()->sort.accept(ArchiveQuery.Direction.ASCENDING));
        action(table,"control shift DOWN","archive-sort-descending",()->sort.accept(ArchiveQuery.Direction.DESCENDING));
        Runnable inspect=()->{int row=table.getSelectedRow();if(row>=0)detail.accept(page.rows.get(table.convertRowIndexToModel(row)));};
        action(table,"ENTER","archive-details",inspect);action(table,"control C","archive-copy",()->copy(table));
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(event.getClickCount()==2)inspect.run();}});
        table.getAccessibleContext().setAccessibleDescription(page.description()+". Enter: details; Ctrl+C: copy; Ctrl+Shift+Up/Down: sort selected column globally.");
        table.putClientProperty("archive.columns",allColumns(table));return table;
    }
    private static void action(JTable table,String stroke,String name,Runnable run){
        table.getInputMap().put(KeyStroke.getKeyStroke(stroke),name);table.getActionMap().put(name,new AbstractAction(){public void actionPerformed(ActionEvent e){run.run();}});
    }
    public static String selectedText(JTable table){
        StringBuilder result=new StringBuilder();for(int row:table.getSelectedRows()){
            if(result.length()>0)result.append('\n');for(int column=0;column<table.getColumnCount();column++){
                if(column>0)result.append('\t');result.append(Objects.toString(table.getValueAt(row,column),""));
            }
        }return result.toString();
    }
    public static <R,F,S extends Enum<S>> ViewState<F,S> position(JTable table,JScrollPane scroll,ArchivePage<R> page,ViewState<F,S> state){
        List<ArchiveRow.Ref> selected=new ArrayList<>();for(int view:table.getSelectedRows())selected.add(page.rows.get(table.convertRowIndexToModel(view)).ref);
        int row=table.rowAtPoint(scroll.getViewport().getViewPosition());ArchiveRow.Ref anchor=null;int offset=0;
        if(row>=0&&row<table.getRowCount()){anchor=page.rows.get(table.convertRowIndexToModel(row)).ref;offset=scroll.getViewport().getViewPosition().y-table.getCellRect(row,0,true).y;}
        return state.withPosition(state.tab,selected,anchor,offset);
    }
    public static <R> void restorePosition(JTable table,JScrollPane scroll,ArchivePage<R> page,ViewState<?,?> state){
        table.clearSelection();Set<ArchiveRow.Ref> selected=new HashSet<>(state.selected);
        for(int model=0;model<page.rows.size();model++){
            int view=table.convertRowIndexToView(model);if(view<0)continue;ArchiveRow.Ref ref=page.rows.get(model).ref;
            if(selected.contains(ref))table.addRowSelectionInterval(view,view);
            if(ref.equals(state.anchor)){final int row=view;SwingUtilities.invokeLater(()->{
                int y=Math.max(0,table.getCellRect(row,0,true).y+state.anchorOffset);
                scroll.getViewport().setViewPosition(new Point(scroll.getViewport().getViewPosition().x,y));
            });}
        }
    }
    private static void copy(JTable table){
        String text=selectedText(table);if(!text.isEmpty())Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);
    }
    private static List<TableColumn> allColumns(JTable table){return Collections.list(table.getColumnModel().getColumns());}
    @SuppressWarnings("unchecked") private static List<TableColumn> retainedColumns(JTable table){
        Object saved=table.getClientProperty("archive.columns");if(saved==null){saved=allColumns(table);table.putClientProperty("archive.columns",saved);}return (List<TableColumn>)saved;
    }
    public static ViewState.Table columnState(JTable table,String preset){
        List<ViewState.Column> columns=new ArrayList<>();Set<String> visible=new HashSet<>();
        for(TableColumn column:allColumns(table)){String id=column.getIdentifier().toString();visible.add(id);columns.add(new ViewState.Column(id,Math.max(16,Math.min(10000,column.getWidth())),true));}
        for(TableColumn column:retainedColumns(table))if(!visible.contains(column.getIdentifier().toString()))columns.add(new ViewState.Column(column.getIdentifier().toString(),Math.max(16,Math.min(10000,column.getWidth())),false));
        return new ViewState.Table(preset,columns);
    }
    public static void applyColumns(JTable table,ViewState.Table state){
        Object restoring=table.getClientProperty("archive.restoringColumns");table.putClientProperty("archive.restoringColumns",true);
        try {
        Map<String,TableColumn> columns=new LinkedHashMap<>();for(TableColumn column:retainedColumns(table))columns.put(column.getIdentifier().toString(),column);
        List<TableColumn> visible=new ArrayList<>();
        for(ViewState.Column saved:state.columns){TableColumn column=columns.remove(saved.id);if(column==null)continue;
            column.setPreferredWidth(saved.width);column.setWidth(saved.width);if(saved.visible)visible.add(column);}
        visible.addAll(columns.values());if(visible.isEmpty())throw new IllegalArgumentException("Keep at least one visible column");
        for(TableColumn column:allColumns(table))table.removeColumn(column);for(TableColumn column:visible)table.addColumn(column);
        } finally { table.putClientProperty("archive.restoringColumns",restoring); }
    }
    /** Reusable visible controls; defaults and presets use stable column IDs. */
    public static JComponent controls(JTable table,ViewState.Table defaults,Map<String,List<String>> presets,Consumer<ViewState.Table> save){
        JPanel controls=ContentStyle.controls();JButton copy=new JButton("Copy selected"),details=new JButton("Details…"),reset=new JButton("Reset columns"),columns=new JButton("Columns…");
        copy.addActionListener(e->copy(table));details.addActionListener(e->{Action action=table.getActionMap().get("archive-details");if(action!=null)action.actionPerformed(e);});
        reset.addActionListener(e->{applyColumns(table,defaults);save.accept(defaults);});
        columns.addActionListener(e->{JPopupMenu menu=new JPopupMenu();ViewState.Table state=columnState(table,"");
            for(ViewState.Column column:state.columns){JCheckBoxMenuItem item=new JCheckBoxMenuItem(column.id,column.visible);item.addActionListener(change->{
                List<ViewState.Column> next=new ArrayList<>();for(ViewState.Column old:state.columns)next.add(new ViewState.Column(old.id,old.width,old.id.equals(column.id)?item.isSelected():old.visible));
                if(next.stream().noneMatch(value->value.visible))return;ViewState.Table updated=new ViewState.Table("Custom",next);applyColumns(table,updated);save.accept(updated);
            });menu.add(item);}menu.show(columns,0,columns.getHeight());});
        JComboBox<String> preset=new JComboBox<>();preset.addItem("Column preset…");for(String name:presets.keySet())preset.addItem(name);
        preset.getAccessibleContext().setAccessibleName("Column preset");preset.addActionListener(e->{List<String> ids=presets.get(preset.getSelectedItem());if(ids==null)return;
            Map<String,ViewState.Column> known=new LinkedHashMap<>();for(ViewState.Column column:defaults.columns)known.put(column.id,column);
            List<ViewState.Column> next=new ArrayList<>();for(String id:ids){ViewState.Column c=known.remove(id);if(c!=null)next.add(new ViewState.Column(c.id,c.width,true));}
            for(ViewState.Column c:known.values())next.add(new ViewState.Column(c.id,c.width,false));
            ViewState.Table updated=new ViewState.Table(preset.getSelectedItem().toString(),next);applyColumns(table,updated);save.accept(updated);
        });
        table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener(){
            private boolean pending;
            private void remember(){if(Boolean.TRUE.equals(table.getClientProperty("archive.restoringColumns"))||pending)return;
                pending=true;SwingUtilities.invokeLater(()->{pending=false;save.accept(columnState(table,"Custom"));});}
            public void columnAdded(javax.swing.event.TableColumnModelEvent e){ }
            public void columnRemoved(javax.swing.event.TableColumnModelEvent e){ }
            public void columnMoved(javax.swing.event.TableColumnModelEvent e){if(e.getFromIndex()!=e.getToIndex())remember();}
            public void columnMarginChanged(javax.swing.event.ChangeEvent e){remember();}
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e){ }
        });
        controls.add(copy);controls.add(details);controls.add(preset);controls.add(columns);controls.add(reset);return controls;
    }
}
