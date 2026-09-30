package tomato.gui.security;

import tomato.ability.*;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.*;
import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * Search runs over retained evidence before the bounded page is rendered. One filter row: the search, with heuristic, time window
 * and order in its drawer (as chips while not the default) and "Reset evidence window…" in its ⋯; Previous and Next page below.
 */
public final class AbilityEvidencePanel extends JPanel {
    private final AbilityObservationStore store;
    private final JTextField search=new JTextField(16);
    private final JComboBox<String> kind=new JComboBox<>(new String[]{"All heuristics","stasis","decoy"});
    private final JComboBox<String> range=new JComboBox<>(new String[]{"All retained time","Last 5 minutes","Last hour"});
    private final JComboBox<String> order=new JComboBox<>(new String[]{"Newest first","Oldest first","Player A–Z","Ability A–Z"});
    private final JTextArea status=ContentStyle.wrappingText(""), details=ContentStyle.wrappingText("Select evidence for complete captured values.");
    // Observed holds the instant, shown as an absolute date-time (Ability Use is an Analyst tab).
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Observed","Player","Ability","Heuristic","MP before → incoming"},0){
        public boolean isCellEditable(int r,int c){return false;}
        public Class<?> getColumnClass(int c){return c==0?Instant.class:Object.class;}
    };
    private final JTable table=new JTable(model);
    private final FilterBar filterBar=new FilterBar("ability");
    private java.util.List<AbilityObservation> rows=Collections.emptyList();
    private int page;
    private boolean clearing;
    private final javax.swing.Timer timer=new javax.swing.Timer(1000,e->refresh());
    /** Asks before the Danger action; tests answer it. */
    java.util.function.Predicate<String> confirm=message->JOptionPane.showConfirmDialog(this,message,"Reset evidence window",
        JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.YES_OPTION;
    public AbilityEvidencePanel(AbilityObservationStore store){
        super(new BorderLayout(6,6));this.store=store;
        JLabel label=new JLabel("Search evidence");label.setLabelFor(search);
        JPanel facets=ContentStyle.controls();facets.add(kind);facets.add(range);facets.add(order);
        filterBar.search(new WrapRow(label,search)).drawer(facets);
        JMenuItem reset=new JMenuItem("Reset evidence window…"){
            @Override public void updateUI(){super.updateUI();setForeground(Tokens.color(Tokens.Role.BAD));}
        };
        reset.setName("ability-reset-window");reset.setToolTipText("Clear the retained ability evidence of this app run");
        reset.addActionListener(e->resetWindow());filterBar.overflow().menu().add(reset);filterBar.overflow().setVisible(true);
        KitButton previous=KitButton.secondary("Previous"),next=KitButton.secondary("Next");
        search.setName("ability-search");table.setName("ability-table");status.setName("ability-status");details.setName("ability-details");
        kind.setName("ability-kind");range.setName("ability-range");order.setName("ability-order");
        order.getAccessibleContext().setAccessibleName("Sort all retained matching ability evidence");
        search.getAccessibleContext().setAccessibleName("Search all retained ability evidence");kind.getAccessibleContext().setAccessibleName("Ability heuristic");range.getAccessibleContext().setAccessibleName("Ability time window");
        previous.addActionListener(e->{page=Math.max(0,page-1);refresh();});next.addActionListener(e->{page++;refresh();});
        for(JComboBox<String> facet:Arrays.asList(kind,range,order))facet.addActionListener(e->{if(clearing)return;page=0;updateChips();refresh();});
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}private void changed(){if(clearing)return;page=0;refresh();}});
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getAccessibleContext().setAccessibleName("Inferred ability observations");
        table.getColumnModel().getColumn(0).setCellRenderer(new ContentStyle.Cell(){
            @Override protected void setValue(Object value){setText(value instanceof Instant?DisplayFormat.formatTimestamp((Instant)value):DisplayFormat.UNAVAILABLE);}
        });
        KitTables.fitKind(table,table.getColumnModel().getColumn(0),ColumnKind.DATE_TIME);
        table.getSelectionModel().addListSelectionListener(e->{int index=table.getSelectedRow();if(index>=0&&index<rows.size()){details.setText(rows.get(index).details());details.setCaretPosition(0);}});
        details.setEditable(false);details.getAccessibleContext().setAccessibleName("Complete ability evidence");status.setEditable(false);
        JScrollPane tableScroll=new JScrollPane(table),detailScroll=new JScrollPane(details);
        tableScroll.setMinimumSize(new Dimension(0,60));detailScroll.setMinimumSize(new Dimension(0,90));
        tableScroll.setPreferredSize(new Dimension(450,230));detailScroll.setPreferredSize(new Dimension(450,160));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,tableScroll,detailScroll);split.setResizeWeight(.5);
        JPanel pager=ContentStyle.controls();pager.setName("ability-pager");pager.add(previous);pager.add(next);
        JPanel footer=new JPanel(new BorderLayout(0,4));footer.add(pager,BorderLayout.NORTH);footer.add(status,BorderLayout.CENTER);
        add(filterBar,BorderLayout.NORTH);add(split);add(footer,BorderLayout.SOUTH);updateChips();refresh();
    }
    /** The evidence filter row (Party lends it the Scope chip on this tab). */
    FilterBar filterBar(){return filterBar;}
    /** Non-default heuristic, window and order as removable chips; Clear resets them and the search, as Clear filters did. */
    private void updateChips(){
        List<FilterBar.ActiveFilter> chips=new ArrayList<>();
        if(kind.getSelectedIndex()>0)chips.add(new FilterBar.ActiveFilter("Heuristic: "+kind.getSelectedItem(),()->kind.setSelectedIndex(0)));
        if(range.getSelectedIndex()>0)chips.add(new FilterBar.ActiveFilter(String.valueOf(range.getSelectedItem()),()->range.setSelectedIndex(0)));
        if(order.getSelectedIndex()>0)chips.add(new FilterBar.ActiveFilter("Order: "+order.getSelectedItem(),()->order.setSelectedIndex(0)));
        FilterChips.update(filterBar,chips,chips.isEmpty()?null:this::clearFilters,false);
    }
    private void clearFilters(){
        clearing=true;
        try{search.setText("");kind.setSelectedIndex(0);range.setSelectedIndex(0);order.setSelectedIndex(0);}finally{clearing=false;}
        page=0;updateChips();refresh();
    }
    private void resetWindow(){
        if(!confirm.test("Clear the retained ability evidence of this app run?\nObservations from now on are kept."))return;
        store.reset();page=0;refresh();
    }
    public void refresh(){
        Long from=range.getSelectedIndex()==0?null:System.currentTimeMillis()-(range.getSelectedIndex()==1?300000:3600000);
        AbilityObservationStore.Snapshot s=store.snapshot(search.getText(),kind.getSelectedIndex()==0?null:(String)kind.getSelectedItem(),from,null);
        java.util.List<AbilityObservation> sorted=new ArrayList<>(s.rows);
        Comparator<AbilityObservation> comparator=Comparator.comparingLong(r->r.observedAt);
        if(order.getSelectedIndex()==0)comparator=comparator.reversed();
        else if(order.getSelectedIndex()==2)comparator=Comparator.comparing(r->r.player,String.CASE_INSENSITIVE_ORDER);
        else if(order.getSelectedIndex()==3)comparator=Comparator.comparing(r->r.ability,String.CASE_INSENSITIVE_ORDER);
        sorted.sort(comparator.thenComparing(r->r.id));
        page=Math.min(page,Math.max(0,(s.rows.size()-1)/100));
        String selected=table.getSelectedRow()<0?null:rows.get(table.getSelectedRow()).id;
        rows=new ArrayList<>(sorted.subList(page*100,Math.min(sorted.size(),(page+1)*100)));model.setRowCount(0);
        for(int i=0;i<rows.size();i++){AbilityObservation r=rows.get(i);model.addRow(new Object[]{Instant.ofEpochMilli(r.observedAt),r.player,r.ability,r.heuristic,r.previousMp+" → "+r.observedMp});if(r.id.equals(selected))table.setRowSelectionInterval(i,i);}
        if(table.getSelectedRow()<0)details.setText("Select evidence for complete captured values.");
        String resetTime=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(s.resetAt));
        // Separate the coverage warning from counters so its words cannot straddle a
        // clipped long line while Swing settles a narrow, enlarged-text layout.
        status.setText(s.rows.size()+" matches · Page "+(page+1)+" · Retained "+s.retained+" / "+s.limit
            +"\n"+s.evicted+" evicted · "+s.omitted+" omitted · Reset "+resetTime
            +"\nSession-only · Inferred observations; incomplete coverage."
            +"\nNo rows does not mean no abilities were used.");
        status.setToolTipText("Evidence window reset at "+DisplayFormat.formatTimestamp(s.resetAt));
    }
    @Override public void addNotify(){super.addNotify();timer.start();}
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
}
