package tomato.gui.search;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import tomato.gui.modern.ContentStyle;

/** Keyboard-first discovery; selecting a row never executes its callback. */
public final class ActionSearchPanel extends JPanel {
    private final ActionRegistry registry;
    private final JTextField query=new JTextField();
    private final DefaultListModel<ActionDescriptor> model=new DefaultListModel<>();
    private final JList<ActionDescriptor> results=new JList<>(model);
    private final JTextArea details=ContentStyle.wrappingText("Search for a setting or existing control.");
    private final JLabel status=new JLabel();
    private final JButton open=new JButton("Open selected control");
    private final Runnable beforeOpen;
    public ActionSearchPanel(ActionRegistry registry,Runnable beforeOpen){
        super(new BorderLayout(8,8));this.registry=registry;this.beforeOpen=beforeOpen;
        JPanel header=new JPanel(new BorderLayout(6,6));JLabel label=new JLabel("Find settings and actions");label.setLabelFor(query);header.add(label,BorderLayout.NORTH);header.add(query);
        query.getAccessibleContext().setAccessibleName("Find settings and actions");results.getAccessibleContext().setAccessibleName("Matching controls");
        details.setEditable(false);details.getAccessibleContext().setAccessibleName("Control location and persistence");results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(results),new JScrollPane(details));split.setResizeWeight(.55);
        JPanel footer=new JPanel(new BorderLayout());footer.add(status);footer.add(open,BorderLayout.EAST);
        add(header,BorderLayout.NORTH);add(split);add(footer,BorderLayout.SOUTH);setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        results.addListSelectionListener(e->selection());open.addActionListener(e->openSelected());query.addActionListener(e->openSelected());
        results.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"open-control");results.getActionMap().put("open-control",new AbstractAction(){public void actionPerformed(ActionEvent e){openSelected();}});
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN,0),"results");query.getActionMap().put("results",new AbstractAction(){public void actionPerformed(ActionEvent e){if(!model.isEmpty()){results.requestFocusInWindow();results.setSelectedIndex(Math.max(0,results.getSelectedIndex()));}}});
        query.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){refresh();}public void removeUpdate(DocumentEvent e){refresh();}public void changedUpdate(DocumentEvent e){refresh();}});refresh();
    }
    private void refresh(){model.clear();for(ActionDescriptor value:registry.search(query.getText()))model.addElement(value);
        status.setText(model.size()==0?"No matching controls. Try font, history or alerts.":model.size()+" matching controls");if(!model.isEmpty())results.setSelectedIndex(0);selection();}
    private void selection(){ActionDescriptor selected=results.getSelectedValue();open.setEnabled(selected!=null&&selected.enabled());details.setText(selected==null?"No control selected.":selected.details());details.setCaretPosition(0);}
    private void openSelected(){ActionDescriptor selected=results.getSelectedValue();if(selected==null||!selected.enabled()){selection();return;}beforeOpen.run();selected.open();}
    public void focusSearch(){query.requestFocusInWindow();}
    public static void show(Window owner){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->show(owner));return;}
        JDialog dialog=new JDialog(owner,"Find settings and actions",Dialog.ModalityType.MODELESS);
        ActionSearchPanel panel=new ActionSearchPanel(ActionRegistry.application(),dialog::dispose);dialog.setContentPane(panel);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setSize(620,460);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);panel.focusSearch();
    }
}
