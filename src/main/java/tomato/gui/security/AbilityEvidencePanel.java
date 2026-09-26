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
    private final JTextArea status=ContentStyle.wrappingText(""), details=ContentStyle.wrappingText("Select evidence for complete captured values.");
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Observed","Player","Ability","Heuristic","MP before → incoming"},0){public boolean isCellEditable(int r,int c){return false;}};
    private final JTable table=new JTable(model);
    private java.util.List<AbilityObservation> rows=Collections.emptyList();
    private int page;
    private final javax.swing.Timer timer=new javax.swing.Timer(1000,e->refresh());
    public AbilityEvidencePanel(AbilityObservationStore store){
        super(new BorderLayout(6,6));this.store=store;
        JPanel controls=ContentStyle.controls();controls.add(new JLabel("Search evidence"));controls.add(search);controls.add(kind);controls.add(range);
        JButton previous=new JButton("Previous"),next=new JButton("Next"),reset=new JButton("Reset evidence window");controls.add(previous);controls.add(next);controls.add(reset);
        search.getAccessibleContext().setAccessibleName("Search all retained ability evidence");kind.getAccessibleContext().setAccessibleName("Ability heuristic");range.getAccessibleContext().setAccessibleName("Ability time window");
        previous.addActionListener(e->{page=Math.max(0,page-1);refresh();});next.addActionListener(e->{page++;refresh();});
        reset.addActionListener(e->{store.reset();page=0;refresh();});kind.addActionListener(e->{page=0;refresh();});range.addActionListener(e->{page=0;refresh();});
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}private void changed(){page=0;refresh();}});
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getAccessibleContext().setAccessibleName("Inferred ability observations");
        table.getSelectionModel().addListSelectionListener(e->{int index=table.getSelectedRow();if(index>=0&&index<rows.size()){details.setText(rows.get(index).details());details.setCaretPosition(0);}});
        details.setEditable(false);details.getAccessibleContext().setAccessibleName("Complete ability evidence");status.setEditable(false);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(table),new JScrollPane(details));split.setResizeWeight(.65);
        add(controls,BorderLayout.NORTH);add(split);add(status,BorderLayout.SOUTH);refresh();
    }
    public void refresh(){
        Long from=range.getSelectedIndex()==0?null:System.currentTimeMillis()-(range.getSelectedIndex()==1?300000:3600000);
        AbilityObservationStore.Snapshot s=store.snapshot(search.getText(),kind.getSelectedIndex()==0?null:(String)kind.getSelectedItem(),from,null);
        page=Math.min(page,Math.max(0,(s.rows.size()-1)/100));
        String selected=table.getSelectedRow()<0?null:rows.get(table.getSelectedRow()).id;
        rows=new ArrayList<>(s.rows.subList(page*100,Math.min(s.rows.size(),(page+1)*100)));model.setRowCount(0);
        for(int i=0;i<rows.size();i++){AbilityObservation r=rows.get(i);model.addRow(new Object[]{new Date(r.observedAt),r.player,r.ability,r.heuristic,r.previousMp+" → "+r.observedMp});if(r.id.equals(selected))table.setRowSelectionInterval(i,i);}
        if(table.getSelectedRow()<0)details.setText("Select evidence for complete captured values.");
        status.setText(s.rows.size()+" matching inferred observations · page "+(page+1)+" · "+s.retained+" / "+s.limit+" retained\n"+s.evicted+" evicted · "+s.omitted+" omitted · session-only since "+new Date(s.resetAt)+". No rows does not mean no abilities were used; collection is heuristic and incomplete.");
    }
    @Override public void addNotify(){super.addNotify();timer.start();}
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
}
