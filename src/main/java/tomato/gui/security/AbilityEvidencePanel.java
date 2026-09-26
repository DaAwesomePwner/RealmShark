package tomato.gui.security;

import tomato.ability.*;
import tomato.gui.modern.ContentStyle;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.*;
import java.awt.*;
import java.util.*;

/** Search runs over retained evidence before the bounded page is rendered. */
public final class AbilityEvidencePanel extends JPanel {
    private final AbilityObservationStore store;
    private final JTextField search=new JTextField(16);
    private final JComboBox<String> kind=new JComboBox<>(new String[]{"All heuristics","stasis","decoy"});
    private final JComboBox<String> range=new JComboBox<>(new String[]{"All retained time","Last 5 minutes","Last hour"});
    private final JComboBox<String> order=new JComboBox<>(new String[]{"Newest first","Oldest first","Player A–Z","Ability A–Z"});
    private final JTextArea status=ContentStyle.wrappingText(""), details=ContentStyle.wrappingText("Select evidence for complete captured values.");
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Observed","Player","Ability","Heuristic","MP before → incoming"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);
    private java.util.List<AbilityObservation> rows=Collections.emptyList();
    private int page;
    private final javax.swing.Timer timer=new javax.swing.Timer(1000,e->refresh());
    public AbilityEvidencePanel(AbilityObservationStore store){
        super(new BorderLayout(6,6));this.store=store;
        JPanel controls=ContentStyle.controls();controls.add(new JLabel("Search evidence"));controls.add(search);controls.add(kind);controls.add(range);controls.add(order);
        JButton previous=new JButton("Previous"),next=new JButton("Next"),reset=new JButton("Reset evidence window"),clearFilters=new JButton("Clear filters");controls.add(previous);controls.add(next);controls.add(clearFilters);controls.add(reset);
        search.setName("ability-search");table.setName("ability-table");status.setName("ability-status");details.setName("ability-details");order.setName("ability-order");
        order.getAccessibleContext().setAccessibleName("Sort all retained matching ability evidence");order.addActionListener(e->{page=0;refresh();});
        clearFilters.addActionListener(e->{search.setText("");kind.setSelectedIndex(0);range.setSelectedIndex(0);order.setSelectedIndex(0);page=0;refresh();});
        search.getAccessibleContext().setAccessibleName("Search all retained ability evidence");kind.getAccessibleContext().setAccessibleName("Ability heuristic");range.getAccessibleContext().setAccessibleName("Ability time window");
        previous.addActionListener(e->{page=Math.max(0,page-1);refresh();});next.addActionListener(e->{page++;refresh();});
        reset.addActionListener(e->{store.reset();page=0;refresh();});kind.addActionListener(e->{page=0;refresh();});range.addActionListener(e->{page=0;refresh();});
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}private void changed(){page=0;refresh();}});
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getAccessibleContext().setAccessibleName("Inferred ability observations");
        table.getSelectionModel().addListSelectionListener(e->{int index=table.getSelectedRow();if(index>=0&&index<rows.size()){details.setText(rows.get(index).details());details.setCaretPosition(0);}});
        details.setEditable(false);details.getAccessibleContext().setAccessibleName("Complete ability evidence");status.setEditable(false);
        JScrollPane tableScroll=new JScrollPane(table),detailScroll=new JScrollPane(details);
        tableScroll.setMinimumSize(new Dimension(0,60));detailScroll.setMinimumSize(new Dimension(0,90));
        tableScroll.setPreferredSize(new Dimension(450,230));detailScroll.setPreferredSize(new Dimension(450,160));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,tableScroll,detailScroll);split.setResizeWeight(.5);
        add(controls,BorderLayout.NORTH);add(split);add(status,BorderLayout.SOUTH);refresh();
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
        for(int i=0;i<rows.size();i++){AbilityObservation r=rows.get(i);model.addRow(new Object[]{new Date(r.observedAt),r.player,r.ability,r.heuristic,r.previousMp+" → "+r.observedMp});if(r.id.equals(selected))table.setRowSelectionInterval(i,i);}
        if(table.getSelectedRow()<0)details.setText("Select evidence for complete captured values.");
        String resetTime=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(s.resetAt));
        // Separate the coverage warning from counters so its words cannot straddle a
        // clipped long line while Swing settles a narrow, enlarged-text layout.
        status.setText(s.rows.size()+" matches · Page "+(page+1)+" · Retained "+s.retained+" / "+s.limit
            +"\n"+s.evicted+" evicted · "+s.omitted+" omitted · Reset "+resetTime
            +"\nSession-only · Inferred observations; incomplete coverage."
            +"\nNo rows does not mean no abilities were used.");
        status.setToolTipText("Evidence window reset at "+new Date(s.resetAt));
    }
    @Override public void addNotify(){super.addNotify();timer.start();}
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
}
