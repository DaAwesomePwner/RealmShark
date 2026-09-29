package tomato.gui.history;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.KitTables;
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
        // Integer IDs and counts: a missing value reads "—" (JTable's own number renderer drew a blank), and IDs are never grouped ("2591", not "2,591").
        table.setDefaultRenderer(Integer.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(value==null?DisplayFormat.UNAVAILABLE:value.toString());}});
        for(int i=0;i<columns.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(i==0?240:145);
        track(table);   // before any KitTables.analystOnly, so every column is known (see columnState)
        return table;
    }
    public static JComponent page(JTable table,String note){
        JPanel panel=new JPanel(new BorderLayout(0,6));panel.add(ContentStyle.tableScroll(table,3));
        panel.add(ContentStyle.wrappingText(note),BorderLayout.SOUTH);return panel;
    }
    public static final class Column<R,V> {
        public final String id,label;public final Class<V> type;public final Function<R,V> value;
        public final TableCellRenderer renderer;
        /** Shared width/alignment family (spec §5.5); null keeps the legacy 240/145 px default. */
        public final ColumnKind kind;
        public Column(String id,String label,Class<V> type,Function<R,V> value,TableCellRenderer renderer){this(id,label,type,value,renderer,null);}
        public Column(String id,String label,Class<V> type,Function<R,V> value,TableCellRenderer renderer,ColumnKind kind){
            this.id=Objects.requireNonNull(id);this.label=Objects.requireNonNull(label);this.type=Objects.requireNonNull(type);
            this.value=Objects.requireNonNull(value);this.renderer=renderer;this.kind=kind;
        }
    }
    /** Kinds whose kit renderer only lays text out (alignment, "—" for missing); numbers and times keep typed renderers. */
    private static final Set<ColumnKind> TEXT_KINDS=EnumSet.of(ColumnKind.TEXT,ColumnKind.PLAYER,ColumnKind.DUNGEON,ColumnKind.CLASS,ColumnKind.ITEM,ColumnKind.STATUS,ColumnKind.ID);
    /** The model is already globally ordered. Header/keyboard sorting sends query intent upstream. */
    public static <R,F,S extends Enum<S>> JTable queried(String name,List<Column<R,?>> columns,ArchivePage<R> page,
            Map<String,S> sorts,ArchiveQuery<F,S> query,Consumer<ArchiveQuery<F,S>> changed,Consumer<ArchiveRow<R>> detail) {
        String[] labels=new String[columns.size()];Class<?>[] types=new Class<?>[columns.size()];List<Object[]> rows=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(int c=0;c<columns.size();c++){Column<R,?> column=columns.get(c);if(!ids.add(column.id))throw new IllegalArgumentException("Duplicate column ID");labels[c]=column.label;types[c]=column.type;}
        for(ArchiveRow<R> row:page.rows){Object[] values=new Object[columns.size()];for(int c=0;c<columns.size();c++)values[c]=columns.get(c).value.apply(row.value);rows.add(values);}
        JTable table=table(name,labels,types,rows);table.setAutoCreateRowSorter(false);table.setRowSorter(null);
        for(int c=0;c<columns.size();c++){TableColumn column=table.getColumnModel().getColumn(c);Column<R,?> spec=columns.get(c);column.setIdentifier(spec.id);
            if(spec.renderer!=null)column.setCellRenderer(spec.renderer);
            else if(spec.kind!=null&&spec.type==String.class&&TEXT_KINDS.contains(spec.kind))column.setCellRenderer(KitTables.renderer(spec.kind));
            if(spec.kind!=null)KitTables.fitKind(table,column,spec.kind);}
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
        table.putClientProperty(RETAINED,allColumns(table));return table;
    }
    /**
     * Ad-hoc tables: ColumnKind widths by column identifier (the header text when none was set), following later font
     * changes (KitTables.fitKind). Renderers are left alone; they carry each table's units, zones and "Unknown" wording,
     * so displayed text, sorting and exports stay identical.
     */
    public static void kinds(JTable table,Map<String,ColumnKind> kinds){
        for(TableColumn column:allColumns(table)){ColumnKind kind=kinds.get(String.valueOf(column.getIdentifier()));if(kind!=null)KitTables.fitKind(table,column,kind);}
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
    /** Table client property: the name of the layout last applied or saved ("Custom" after a move or resize), for Column preset ▸. */
    private static final String PRESET="archive.preset";
    /** Table client property: TRUE while HistoryTables itself changes the columns (a restored layout, a preset or Reset, or the mode rules), so layout listeners ignore it. */
    public static final String RESTORING_COLUMNS="archive.restoringColumns";
    /** Table client properties: every column HistoryTables has seen (shown, hidden by the user or by the mode), and the mode bookkeeping. */
    private static final String RETAINED="archive.columns",MODE_LAYOUT="archive.modeLayout";
    private static List<TableColumn> allColumns(JTable table){return Collections.list(table.getColumnModel().getColumns());}
    @SuppressWarnings("unchecked") private static List<TableColumn> retainedColumns(JTable table){
        Object saved=table.getClientProperty(RETAINED);if(saved==null){saved=allColumns(table);table.putClientProperty(RETAINED,saved);}return (List<TableColumn>)saved;
    }
    private static void retain(JTable table,List<TableColumn> columns){
        List<TableColumn> retained=retainedColumns(table);for(TableColumn column:columns)if(!retained.contains(column))retained.add(column);
    }
    private static String id(TableColumn column){return column.getIdentifier().toString();}
    private static int width(TableColumn column){return Math.max(16,Math.min(10000,column.getWidth()));}
    /** The IDs {@code KitTables.analystOnly} hides right now (every Analyst-only column while Simple, none in Analyst). */
    private static Set<String> modeHidden(JTable table){Set<String> ids=new HashSet<>();for(Object id:KitTables.modeHidden(table))ids.add(String.valueOf(id));return ids;}
    private static void restoring(JTable table,Runnable change){
        Object before=table.getClientProperty(RESTORING_COLUMNS);table.putClientProperty(RESTORING_COLUMNS,true);
        try{change.run();}finally{table.putClientProperty(RESTORING_COLUMNS,before);}
    }
    /** The table's mode bookkeeping, installed once: it must see the table before the mode first hides a column. */
    private static ModeLayout track(JTable table){
        Object saved=table.getClientProperty(MODE_LAYOUT);if(saved instanceof ModeLayout)return (ModeLayout)saved;
        retainedColumns(table);ModeLayout layout=new ModeLayout(table);table.putClientProperty(MODE_LAYOUT,layout);
        table.addPropertyChangeListener(KitTables.MODE_CHANGING,layout);return layout;
    }
    /**
     * One table's mode-hidden columns (spec §3.2: Analyst-only columns are display only). {@code KitTables.analystOnly} removes a
     * column in Simple and puts it back in Analyst where it stood, flagging both with {@code KitTables.MODE_CHANGING}. Around each
     * change this remembers where the hidden columns stood; when one comes back it applies what a layout restored while it was
     * hidden: its width and place, or the user's hide (then it goes again). Its own changes are marked {@link #RESTORING_COLUMNS}.
     */
    private static final class ModeLayout implements java.beans.PropertyChangeListener {
        private final JTable table;
        /** The user's visibility by column ID, as the last applied layout set it. */
        final Map<String,Boolean> user=new HashMap<>();
        /** For columns a layout set while the mode hid them: the width, and (when shown) the view index among the layout's shown columns. */
        final Map<String,Integer> widths=new HashMap<>(),places=new HashMap<>();
        /** Where each column the mode hid stood in the view when it was hidden. */
        final Map<String,Integer> hiddenAt=new HashMap<>();
        private List<TableColumn> before;
        ModeLayout(JTable table){this.table=table;}
        /** A mode-hidden column is the user's to show or hide: the last layout's choice, else shown if the mode (not the user) removed it. */
        boolean userVisible(String id){Boolean chosen=user.get(id);return chosen!=null?chosen:hiddenAt.containsKey(id);}
        @Override public void propertyChange(java.beans.PropertyChangeEvent event){
            if(Boolean.TRUE.equals(event.getNewValue())){before=allColumns(table);retain(table,before);return;}
            List<TableColumn> was=before;before=null;if(was==null)return;
            List<TableColumn> now=allColumns(table);retain(table,now);
            for(int i=0;i<was.size();i++)if(!now.contains(was.get(i)))hiddenAt.put(id(was.get(i)),i);
            List<TableColumn> back=new ArrayList<>();for(TableColumn column:now)if(!was.contains(column))back.add(column);
            if(back.isEmpty())return;
            restoring(table,()->{
                SortedMap<Integer,List<TableColumn>> moves=new TreeMap<>();
                for(TableColumn column:back){
                    String id=id(column);hiddenAt.remove(id);Integer width=widths.remove(id),place=places.remove(id);
                    if(Boolean.FALSE.equals(user.get(id))){table.removeColumn(column);continue;}
                    if(width!=null){column.setPreferredWidth(width);column.setWidth(width);}
                    if(place!=null)moves.computeIfAbsent(place,key->new ArrayList<>()).add(column);
                }
                // Ascending, so every earlier place is filled first (as analystOnly restores).
                for(Map.Entry<Integer,List<TableColumn>> move:moves.entrySet())for(TableColumn column:move.getValue()){
                    int from=allColumns(table).indexOf(column),to=Math.min(move.getKey(),table.getColumnCount()-1);if(from>=0&&from!=to)table.moveColumn(from,to);
                }
            });
        }
    }
    /**
     * The table's layout: shown columns in view order with their widths, then every other known column. A column that
     * {@code KitTables.analystOnly} hides in Simple is saved as the user left it (shown unless a layout hid it), at its place, with
     * its width: never as hidden because of the mode. Tables with Analyst-only columns must reach HistoryTables ({@link #table},
     * {@link #queried}, {@link #rememberLayout}, {@link #columnTools} or this) before the mode first hides them; a hidden column
     * it never saw is written only when a restored layout gave it a width.
     */
    public static ViewState.Table columnState(JTable table,String preset){
        ModeLayout mode=track(table);Set<String> hidden=modeHidden(table);
        List<ViewState.Column> columns=new ArrayList<>();Set<String> listed=new HashSet<>();
        for(TableColumn column:allColumns(table)){listed.add(id(column));columns.add(new ViewState.Column(id(column),width(column),true));}
        Map<String,TableColumn> known=new LinkedHashMap<>();for(TableColumn column:retainedColumns(table))known.putIfAbsent(id(column),column);
        SortedMap<Integer,List<ViewState.Column>> placed=new TreeMap<>();
        List<String> order=new ArrayList<>(known.keySet());for(String id:hidden)if(!order.contains(id))order.add(id);
        for(String id:order){
            if(!hidden.contains(id)||listed.contains(id))continue;
            TableColumn column=known.get(id);Integer width=mode.widths.get(id);if(column==null&&width==null)continue;
            Integer place=mode.places.containsKey(id)?mode.places.get(id):mode.hiddenAt.get(id);
            placed.computeIfAbsent(place==null?Integer.MAX_VALUE:place,key->new ArrayList<>())
                .add(new ViewState.Column(id,width!=null?Math.max(16,Math.min(10000,width)):width(column),mode.userVisible(id)));listed.add(id);
        }
        for(Map.Entry<Integer,List<ViewState.Column>> entry:placed.entrySet())for(ViewState.Column column:entry.getValue())columns.add(Math.min(entry.getKey(),columns.size()),column);
        for(TableColumn column:known.values())if(listed.add(id(column)))columns.add(new ViewState.Column(id(column),width(column),false));
        return new ViewState.Table(preset,columns);
    }
    /**
     * Applies a layout: widths, order and the user's visibility. In Simple a column {@code KitTables.analystOnly} hides stays
     * hidden; its saved width, place and visibility are kept for Analyst (a column the layout hides does not come back there).
     *
     * @throws IllegalArgumentException when the layout shows no column
     */
    public static void applyColumns(JTable table,ViewState.Table state){
        ModeLayout mode=track(table);Set<String> hidden=modeHidden(table);
        Map<String,TableColumn> columns=new LinkedHashMap<>();for(TableColumn column:retainedColumns(table))columns.put(id(column),column);
        // Validate before changing anything: the layout must show a column (now, or in Analyst for a column the mode hides).
        Set<String> listed=new HashSet<>();boolean shows=false;
        for(ViewState.Column saved:state.columns){listed.add(saved.id);if(saved.visible&&(columns.containsKey(saved.id)||hidden.contains(saved.id)))shows=true;}
        for(String id:columns.keySet())if(!listed.contains(id))shows=true;   // columns the layout does not list are shown
        if(!shows)throw new IllegalArgumentException("Keep at least one visible column");
        restoring(table,()->{
            List<TableColumn> visible=new ArrayList<>();int place=0;
            for(ViewState.Column saved:state.columns){
                TableColumn column=columns.remove(saved.id);boolean later=hidden.contains(saved.id);
                if(column==null&&!later)continue;
                mode.user.put(saved.id,saved.visible);
                if(column!=null){column.setPreferredWidth(saved.width);column.setWidth(saved.width);}
                if(later){
                    // Simple hides it for now: keep the layout's width and place for Analyst.
                    mode.widths.put(saved.id,saved.width);if(saved.visible)mode.places.put(saved.id,place++);else mode.places.remove(saved.id);
                }else if(saved.visible){visible.add(column);place++;}
            }
            for(TableColumn column:columns.values()){mode.user.put(id(column),true);if(!hidden.contains(id(column)))visible.add(column);}
            // A layout that shows only Analyst-only columns still leaves Simple a column to show.
            if(visible.isEmpty())for(TableColumn column:retainedColumns(table))if(!hidden.contains(id(column)))visible.add(column);
            for(TableColumn column:allColumns(table))table.removeColumn(column);for(TableColumn column:visible)table.addColumn(column);
            table.putClientProperty(PRESET,state.preset);
        });
    }
    /**
     * Saves the table's layout ("Custom") one EDT turn after the user moves or resizes a column. Changes HistoryTables makes itself
     * (a restored layout, preset or Reset) and changes {@code KitTables.analystOnly} makes on a mode switch are ignored; the mode's
     * flag is read in the listener callback itself, because it is only set while the columns move.
     */
    public static void rememberLayout(JTable table,Consumer<ViewState.Table> save){
        Objects.requireNonNull(save,"save");track(table);
        table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener(){
            private boolean pending;
            private void remember(){
                if(Boolean.TRUE.equals(table.getClientProperty(RESTORING_COLUMNS))||Boolean.TRUE.equals(table.getClientProperty(KitTables.MODE_CHANGING))||pending)return;
                pending=true;SwingUtilities.invokeLater(()->{pending=false;table.putClientProperty(PRESET,"Custom");save.accept(columnState(table,"Custom"));});}
            public void columnAdded(javax.swing.event.TableColumnModelEvent e){ }
            public void columnRemoved(javax.swing.event.TableColumnModelEvent e){ }
            public void columnMoved(javax.swing.event.TableColumnModelEvent e){if(e.getFromIndex()!=e.getToIndex())remember();}
            public void columnMarginChanged(javax.swing.event.ChangeEvent e){remember();}
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e){ }
        });
    }
    /**
     * One ⋯ menu section of column tools for {@code table}: Columns ▸, Column preset ▸, Reset columns, then Copy selected rows and
     * Row details…. Defaults and presets use stable column IDs; every change is applied and handed to {@code save}. The tools do
     * not install the layout listener: pair them with {@link #rememberLayout} where moves and resizes should be saved too.
     */
    public static ColumnTools columnTools(JTable table,ViewState.Table defaults,Map<String,List<String>> presets,Consumer<ViewState.Table> save){
        return new ColumnTools(table,defaults,presets,save);
    }
    /**
     * A table's column tools as ⋯ menu items (see {@link #columnTools}): {@code <table>-columns}, {@code <table>-column-preset},
     * {@code <table>-reset-columns}, a separator, {@code <table>-copy-rows} and {@code <table>-row-details}. A page puts them in its
     * ⋯ with {@link #addTo}, which replaces the previous table's tools in the same section.
     */
    public static final class ColumnTools {
        /** The ⋯ section the tools occupy. */
        public static final String SECTION="column-tools";
        private final JTable table;private final ViewState.Table defaults;private final Map<String,List<String>> presets;private final Consumer<ViewState.Table> save;
        private final JMenu columns=new JMenu("Columns"),preset=new JMenu("Column preset");
        private final JMenuItem reset=new JMenuItem("Reset columns"),copy=new JMenuItem("Copy selected rows"),details=new JMenuItem("Row details…");
        private final List<Component> items;
        private boolean enabled=true;
        private ColumnTools(JTable table,ViewState.Table defaults,Map<String,List<String>> presets,Consumer<ViewState.Table> save){
            // Without defaults (a live caller that captured none), Reset returns to the layout the table has now.
            this.table=Objects.requireNonNull(table,"table");this.defaults=defaults!=null?defaults:columnState(table,"Default");
            this.presets=Collections.unmodifiableMap(new LinkedHashMap<>(presets));this.save=Objects.requireNonNull(save,"save");
            track(table);String name=table.getName()==null?"table":table.getName();
            columns.setName(name+"-columns");preset.setName(name+"-column-preset");reset.setName(name+"-reset-columns");
            copy.setName(name+"-copy-rows");details.setName(name+"-row-details");
            columns.putClientProperty(ColumnTools.class,this);
            copy.setToolTipText("Ctrl+C in the table");details.setToolTipText("Enter or double-click in the table");
            copy.getAccessibleContext().setAccessibleDescription("Copies the selected rows; Ctrl+C in the table");
            details.getAccessibleContext().setAccessibleDescription("Shows the selected row's details; Enter in the table");
            reset.addActionListener(e->resetColumns());
            copy.addActionListener(e->run("archive-copy"));details.addActionListener(e->run("archive-details"));
            // The menus relist when opened: the layout, the mode and the presets may have changed since.
            javax.swing.event.MenuListener relist=new javax.swing.event.MenuListener(){
                public void menuSelected(javax.swing.event.MenuEvent e){refresh();}
                public void menuDeselected(javax.swing.event.MenuEvent e){ }
                public void menuCanceled(javax.swing.event.MenuEvent e){ }
            };
            columns.addMenuListener(relist);preset.addMenuListener(relist);
            items=Collections.unmodifiableList(Arrays.asList(columns,preset,reset,new JPopupMenu.Separator(),copy,details));
            refresh();
        }
        public JMenu columns(){return columns;}
        public JMenu presets(){return preset;}
        public JMenuItem reset(){return reset;}
        public JMenuItem copy(){return copy;}
        public JMenuItem details(){return details;}
        /** The section's items in order. */
        public List<Component> items(){return items;}
        /** Puts these tools in {@code menu}'s {@link #SECTION}, replacing whatever tools were there. */
        public void addTo(tomato.gui.kit.OverflowMenu menu){menu.section(SECTION).replace(items);}
        /** Empties {@code menu}'s {@link #SECTION} if it holds these tools (a newer table's tools are left alone). */
        public void removeFrom(tomato.gui.kit.OverflowMenu menu){
            tomato.gui.kit.OverflowMenu.Section section=menu.section(SECTION);if(section.items().contains(columns))section.clear();
        }
        /** A stale view's tools are disabled until its replacement arrives. */
        public void setEnabled(boolean enabled){
            if(this.enabled==enabled)return;this.enabled=enabled;
            // The submenus relist (and follow this flag) when opened; only the section's own items change here.
            columns.setEnabled(enabled);preset.setEnabled(enabled&&!presets.isEmpty());reset.setEnabled(enabled);copy.setEnabled(enabled);details.setEnabled(enabled);
        }
        public boolean isEnabled(){return enabled;}
        /**
         * Relists Columns ▸ (each column's visibility; a column the mode hides shows the user's choice, disabled and marked
         * "(Analyst)") and Column preset ▸ (the applied preset is selected).
         */
        public void refresh(){
            columns.removeAll();String name=table.getName()==null?"table":table.getName();Set<String> hidden=modeHidden(table);
            Map<String,TableColumn> known=new HashMap<>();for(TableColumn column:retainedColumns(table))known.put(id(column),column);
            for(ViewState.Column column:columnState(table,"").columns){
                TableColumn source=known.get(column.id);String label=source==null||source.getHeaderValue()==null?column.id:String.valueOf(source.getHeaderValue());
                boolean analyst=hidden.contains(column.id);
                JCheckBoxMenuItem item=new JCheckBoxMenuItem(analyst?label+" (Analyst)":label,column.visible);item.setName(name+"-column-"+column.id);item.setEnabled(enabled&&!analyst);
                if(analyst)item.setToolTipText("Shown in Analyst mode");
                item.addActionListener(e->toggle(column.id,item.isSelected()));columns.add(item);
            }
            preset.removeAll();ButtonGroup group=new ButtonGroup();Object applied=table.getClientProperty(PRESET);
            for(String label:presets.keySet()){JRadioButtonMenuItem item=new JRadioButtonMenuItem(label,label.equals(applied));item.setEnabled(enabled);
                item.addActionListener(e->preset(label));group.add(item);preset.add(item);}
            columns.setEnabled(enabled);preset.setEnabled(enabled&&!presets.isEmpty());reset.setEnabled(enabled);copy.setEnabled(enabled);details.setEnabled(enabled);
        }
        private void run(String action){
            if(!enabled)return;Action target=table.getActionMap().get(action);
            if(target!=null)target.actionPerformed(new ActionEvent(table,ActionEvent.ACTION_PERFORMED,action));
            else if("archive-copy".equals(action))HistoryTables.copy(table);
        }
        private void toggle(String id,boolean show){
            Set<String> hidden=modeHidden(table);if(!enabled||hidden.contains(id)){refresh();return;}
            List<ViewState.Column> next=new ArrayList<>();
            for(ViewState.Column old:columnState(table,"").columns)next.add(new ViewState.Column(old.id,old.width,old.id.equals(id)?show:old.visible));
            // The last column the table shows stays: a column the mode hides does not count.
            if(next.stream().noneMatch(value->value.visible&&!hidden.contains(value.id))){refresh();return;}
            apply(new ViewState.Table("Custom",next));
        }
        private void preset(String label){
            List<String> ids=presets.get(label);if(!enabled||ids==null)return;
            Map<String,ViewState.Column> known=new LinkedHashMap<>();for(ViewState.Column column:defaults.columns)known.put(column.id,column);
            List<ViewState.Column> next=new ArrayList<>();for(String id:ids){ViewState.Column c=known.remove(id);if(c!=null)next.add(new ViewState.Column(c.id,c.width,true));}
            for(ViewState.Column c:known.values())next.add(new ViewState.Column(c.id,c.width,false));
            apply(keepModeHidden(new ViewState.Table(label,next)));
        }
        private void resetColumns(){if(enabled)apply(keepModeHidden(defaults));}
        /** A preset or Reset in Simple leaves the columns the mode hides as they are (width and the user's visibility). */
        private ViewState.Table keepModeHidden(ViewState.Table layout){
            Set<String> hidden=modeHidden(table);if(hidden.isEmpty())return layout;
            Map<String,ViewState.Column> now=new HashMap<>();for(ViewState.Column column:columnState(table,"").columns)now.put(column.id,column);
            List<ViewState.Column> next=new ArrayList<>();
            for(ViewState.Column column:layout.columns){ViewState.Column kept=hidden.contains(column.id)?now.get(column.id):null;next.add(kept==null?column:new ViewState.Column(column.id,kept.width,kept.visible));}
            return new ViewState.Table(layout.preset,next);
        }
        private void apply(ViewState.Table layout){applyColumns(table,layout);save.accept(layout);refresh();}
        /** The same preset as a Column preset ▸ radio, for the legacy {@link #controls} combo. */
        void choose(String label){preset(label);}
    }
    /** The retired button row (Copy selected, Details…, Column preset, Columns…, Reset columns), a thin wrapper over {@link ColumnTools} until the last live caller moves to ⋯. */
    public static JComponent controls(JTable table,ViewState.Table defaults,Map<String,List<String>> presets,Consumer<ViewState.Table> save){
        ColumnTools tools=columnTools(table,defaults,presets,save);rememberLayout(table,save);
        JPanel controls=ContentStyle.controls();JButton copy=new JButton("Copy selected"),details=new JButton("Details…"),reset=new JButton("Reset columns"),columns=new JButton("Columns…");
        copy.addActionListener(e->copy(table));details.addActionListener(e->tools.details().doClick());reset.addActionListener(e->tools.reset().doClick());
        columns.addActionListener(e->{tools.refresh();JPopupMenu menu=new JPopupMenu();for(Component item:tools.columns().getMenuComponents())menu.add(item);menu.show(columns,0,columns.getHeight());});
        JComboBox<String> preset=new JComboBox<>();preset.addItem("Column preset…");for(String name:presets.keySet())preset.addItem(name);
        preset.getAccessibleContext().setAccessibleName("Column preset");preset.addActionListener(e->{Object chosen=preset.getSelectedItem();if(chosen!=null&&presets.containsKey(chosen))tools.choose(chosen.toString());});
        controls.add(copy);controls.add(details);controls.add(preset);controls.add(columns);controls.add(reset);return controls;
    }
}
