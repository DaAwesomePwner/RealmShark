package tomato.gui.bridge;

import tomato.bridge.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import tomato.gui.modern.ContentStyle;

/** The same Swing/FlatLaf surface as the surrounding workspace. */
public final class BridgeReviewGUI extends JPanel {
    private final BridgeService bridge;
    private final JLabel state=new JLabel(){@Override public void updateUI(){super.updateUI();setForeground(ContentStyle.color("violet"));}}, feedback=new JLabel(" ");
    private final JTextArea totals=note("");
    private final JTextField search=new JTextField(22),endpoint=new JTextField(),guild=new JTextField(),csv=new JTextField(),audit=new JTextField();
    private final JPasswordField token=new JPasswordField();
    private final JCheckBox enabled=new JCheckBox("Enable bridge"),send=new JCheckBox("Send matching drops to bot"),debug=new JCheckBox("Debug logs");
    private final JCheckBox ut=new JCheckBox("UT"),st=new JCheckBox("ST"),shiny=new JCheckBox("Shiny"),enchanted=new JCheckBox("Enchanted"),other=new JCheckBox("Other CSV items");
    private final JComboBox<String> status=new JComboBox<>(new String[]{"All statuses","Queued","Logged","Accepted","Not logged","Not in CSV","Filtered","Local only","Rejected","Uncertain","Cancelled","Queue full"});
    private final JComboBox<String> level=new JComboBox<>(new String[]{"All levels","INFO","DEBUG","ERROR"});
    private final Rows reviewModel=new Rows("Time (UTC)","Item","Rarity","Shiny","Character","Dungeon","Status");
    private final Rows logModel=new Rows("Time (UTC)","Level","Message");
    private final JTable review=new JTable(reviewModel),logs=new JTable(logModel);
    private final JTextArea details=note("Detected drops will appear here once the bridge and network capture are enabled. Select a row to inspect enchants and the outgoing fields.");
    private final JTextArea logDetails=note("Select a diagnostic entry to read its full message.");
    private final JTabbedPane tabs=new JTabbedPane();
    private final JButton save=new JButton("Save settings"),export=new JButton("Export review CSV"),exportLogs=new JButton("Export logs");
    private final javax.swing.Timer timer;
    private BridgeService.Snapshot snapshot;
    private List<BridgeService.Review> rows=Collections.emptyList();
    private long revision=-1;
    private boolean rebuilding;
    private boolean loadingFields,initialSettingsLoaded,saving;
    private final Set<JComponent> editedFields=Collections.newSetFromMap(new IdentityHashMap<>());

    public BridgeReviewGUI(BridgeService bridge) {
        super(new BorderLayout(0,8));this.bridge=bridge;setName("bridge-review-panel");
        JPanel summary=new JPanel(new BorderLayout(0,5));
        state.setFont(ContentStyle.emphasis(ContentStyle.body()));
        totals.setRows(2);summary.add(state,BorderLayout.NORTH);summary.add(totals);add(summary,BorderLayout.NORTH);
        setupTable(review,ContentStyle.Density.COMFORTABLE);setupTable(logs,ContentStyle.Density.DENSE);review.setName("bridge-review-table");logs.setName("bridge-log-table");
        review.getColumnModel().getColumn(6).setCellRenderer(new ContentStyle.Badge(){
            @Override protected Color badgeColor(Object value){
                String status=String.valueOf(value);
                if(status.equals("Accepted")||status.equals("Logged"))return ContentStyle.color("mint");
                if(status.equals("Queued")||status.equals("Uncertain")||status.equals("Queue full"))return ContentStyle.color("amber");
                if(status.equals("Rejected"))return ContentStyle.color("rose");
                return ContentStyle.color("muted");
            }
        });
        review.getColumnModel().getColumn(1).setPreferredWidth(230);logs.getColumnModel().getColumn(2).setPreferredWidth(620);
        review.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={145,205,75,55,125,145,115};
        for(int i=0;i<widths.length;i++)review.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JPanel reviewPage=new JPanel(new BorderLayout(0,8));
        JPanel tools=ContentStyle.controls();search.setName("bridge-search");status.setName("bridge-status-filter");
        search.setColumns(18);search.getAccessibleContext().setAccessibleName("Search bridge review and logs");status.getAccessibleContext().setAccessibleName("Drop delivery status");
        tools.add(labeled("Search",search));tools.add(status);tools.add(export);
        reviewPage.add(tools,BorderLayout.NORTH);
        details.setName("bridge-details");details.setOpaque(true);details.setFont(ContentStyle.report(ContentStyle.body()));details.setMargin(new Insets(6,8,6,8));
        details.getAccessibleContext().setAccessibleName("Selected drop delivery details");
        JScrollPane detailScroll=new JScrollPane(details);detailScroll.setMinimumSize(new Dimension(0,100));detailScroll.setPreferredSize(new Dimension(700,175));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ContentStyle.tableScroll(review,3),detailScroll);split.setResizeWeight(.68);split.setBorder(null);
        reviewPage.add(split);reviewPage.add(note("CSV controls what can be sent. Drops are observed in bags; pickup is not verified. Review retains the latest 1,000 items."),BorderLayout.SOUTH);
        tabs.addTab("Review",reviewPage);tabs.addTab("Settings",settings());
        JPanel logPage=new JPanel(new BorderLayout(0,8));JPanel logTools=ContentStyle.controls();
        JTextField logSearch=new JTextField(18);logSearch.setDocument(search.getDocument());logSearch.getAccessibleContext().setAccessibleName("Search bridge logs and review");
        level.getAccessibleContext().setAccessibleName("Bridge log level");
        logTools.add(labeled("Search",logSearch));logTools.add(level);
        logTools.add(exportLogs);JButton clear=new JButton("Clear logs");logTools.add(clear);
        logPage.add(logTools,BorderLayout.NORTH);
        logDetails.setName("bridge-log-details");logDetails.setOpaque(true);logDetails.setMargin(new Insets(6,8,6,8));logDetails.setFont(ContentStyle.report(ContentStyle.body()));
        logDetails.getAccessibleContext().setAccessibleName("Selected bridge log message");
        JScrollPane logDetailScroll=new JScrollPane(logDetails);logDetailScroll.setMinimumSize(new Dimension(0,90));logDetailScroll.setPreferredSize(new Dimension(700,120));
        JSplitPane logSplit=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ContentStyle.tableScroll(logs,3),logDetailScroll);logSplit.setResizeWeight(.75);logSplit.setBorder(null);logPage.add(logSplit);
        logPage.add(note("Latest 500 diagnostic entries. Tokens and raw server responses are excluded. Search is shared with Review. No automatic retry: the bot cannot deduplicate a repeated submission."),BorderLayout.SOUTH);
        tabs.addTab("Logs",logPage);tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);add(tabs);feedback.setName("bridge-feedback");feedback.setFont(ContentStyle.metadata(ContentStyle.body()));add(feedback,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
        status.addActionListener(e->filter());level.addActionListener(e->filter());
        review.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!rebuilding)showDetails();});
        logs.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!rebuilding)showLogDetails();});
        clear.addActionListener(e->{bridge.clearLogs();refresh();});export.addActionListener(e->exportReview());exportLogs.addActionListener(e->exportLogs());save.addActionListener(e->save());
        state.setName("bridge-state");save.setName("bridge-save");
        load(bridge.config());trackEdits();if(bridge.isPreview())save.setToolTipText("Preview never saves settings or contacts the bot.");
        timer=new javax.swing.Timer(650,e->{if(isShowing())refresh();});refresh();
    }
    @Override public void addNotify(){super.addNotify();timer.start();}
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
    private JComponent settings() {
        JPanel page=new JPanel(new BorderLayout(0,8));page.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        page.add(note("Use the endpoint, Guild ID and Link Token supplied by your guild. Enable capture with File > Start Sniffer. Save with Enable bridge and Send selected to submit the same confirmation ping as the public bridge."),BorderLayout.NORTH);
        JPanel form=new JPanel(new GridBagLayout());GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(3,0,5,0);g.fill=GridBagConstraints.HORIZONTAL;g.anchor=GridBagConstraints.NORTHWEST;
        field(form,g,0,"Endpoint",endpoint);field(form,g,1,"Guild ID",guild);field(form,g,2,"Link Token",token);
        endpoint.setName("bridge-endpoint");guild.setName("bridge-guild");token.setName("bridge-token");csv.setName("bridge-csv");audit.setName("bridge-audit");
        token.setToolTipText("Stored locally in bridge.properties. Do not share that file.");
        JPanel csvBox=new JPanel(new BorderLayout(7,0));csvBox.add(csv);JButton browse=new JButton("Browse…");csvBox.add(browse,BorderLayout.EAST);
        browse.getAccessibleContext().setAccessibleName("Browse for loot CSV");
        browse.addActionListener(e->{JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)csv.setText(chooser.getSelectedFile().getAbsolutePath());});
        field(form,g,3,"Loot CSV (input)",csvBox,csv);field(form,g,4,"Review log (optional)",audit);
        enabled.setName("bridge-enabled");send.setName("bridge-send");debug.setName("bridge-debug");
        JPanel switches=ContentStyle.controls();switches.add(enabled);switches.add(send);switches.add(debug);field(form,g,5,"Operation",switches);
        JPanel categories=ContentStyle.controls();for(JCheckBox box:new JCheckBox[]{ut,st,shiny,enchanted,other})categories.add(box);field(form,g,6,"Include categories",categories);
        JTextArea help=note("Categories are additive: any selected match qualifies, and the item must also be in the CSV to send. All categories selected matches the public bridge. Unlisted items remain visible for review. Turn off Send for local review only.\n\nCSV paths such as ./rotmg_loot_drops_updated.csv resolve from the application folder. The optional review log is a local JSONL file with one 5 MB backup. Relative and absolute paths are supported.\n\nKeep one sniffer instance running. New characters are configured in Discord with /mysniffer → Configure Character. Bridge enablement is independent of the original loot-sharing menu option.");
        field(form,g,7,"How it works",help);
        JPanel actions=ContentStyle.controls();actions.add(save);JButton included=new JButton("Use included CSV");actions.add(included);included.addActionListener(e->csv.setText("./rotmg_loot_drops_updated.csv"));
        g.gridy=16;g.weighty=1;form.add(Box.createVerticalGlue(),g);page.add(form);
        class ScrollPage extends JPanel implements Scrollable {
            ScrollPage(){super(new BorderLayout());add(page);}
            public Dimension getPreferredScrollableViewportSize(){return new Dimension(700,550);}
            public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 24;}
            public int getScrollableBlockIncrement(Rectangle r,int o,int d){return 150;}
            public boolean getScrollableTracksViewportWidth(){return true;}
            public boolean getScrollableTracksViewportHeight(){return false;}
        }
        JScrollPane scroll=new JScrollPane(new ScrollPage());scroll.setBorder(null);
        JPanel content=new JPanel(new BorderLayout(0,8));content.add(scroll);actions.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));content.add(actions,BorderLayout.SOUTH);return content;
    }
    private static JPanel labeled(String text,JComponent value){JPanel panel=new JPanel(new BorderLayout(6,0));JLabel label=new JLabel(text);label.setLabelFor(value);panel.add(label,BorderLayout.WEST);panel.add(value);return panel;}
    private static void field(JPanel p,GridBagConstraints g,int row,String title,JComponent value,JComponent... targets){
        JComponent target=targets.length==0?value:targets[0];JLabel label=new JLabel(title);label.setLabelFor(target);target.getAccessibleContext().setAccessibleName(title);
        label.setFont(ContentStyle.metadata(ContentStyle.body()));
        g.gridy=row*2;g.gridx=0;g.weightx=1;p.add(label,g);g.gridy++;p.add(value,g);
    }
    private static JTextArea note(String text){JTextArea area=new JTextArea(text);area.setEditable(false);area.setOpaque(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setFont(ContentStyle.metadata(ContentStyle.body()));return area;}
    private static void setupTable(JTable table,ContentStyle.Density density){
        table.setAutoCreateRowSorter(true);ContentStyle.table(table,density);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getTableHeader().setReorderingAllowed(false);
    }
    private void trackEdits(){
        for(JTextField field:new JTextField[]{endpoint,guild,token,csv,audit})field.getDocument().addDocumentListener(new DocumentListener(){
            private void changed(){if(!loadingFields)editedFields.add(field);}
            public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}
        });
        for(JCheckBox box:new JCheckBox[]{enabled,send,debug,ut,st,shiny,enchanted,other})box.addItemListener(e->{if(!loadingFields)editedFields.add(box);});
    }
    private void load(BridgeConfig c){
        loadingFields=true;
        try {
            JTextField[] fields={endpoint,guild,token,csv,audit};String[] values={c.endpoint,c.guildId,c.token,c.csvPath,c.reviewLog};
            for(int i=0;i<fields.length;i++)if(!editedFields.contains(fields[i]))fields[i].setText(values[i]);
            JCheckBox[] boxes={enabled,send,debug,ut,st,shiny,enchanted,other};boolean[] selected={c.enabled,c.send,c.debug,c.ut,c.st,c.shiny,c.enchanted,c.other};
            for(int i=0;i<boxes.length;i++)if(!editedFields.contains(boxes[i]))boxes[i].setSelected(selected[i]);
        } finally {loadingFields=false;}
    }
    private BridgeConfig edited(){Properties p=new Properties();String[] keys={"endpoint","guild_id","link_token","csv_path","local_review_log","enabled","send","debug","filter.ut","filter.st","filter.shiny","filter.enchanted","filter.other"};Object[] values={endpoint.getText(),guild.getText(),new String(token.getPassword()),csv.getText(),audit.getText(),enabled.isSelected(),send.isSelected(),debug.isSelected(),ut.isSelected(),st.isSelected(),shiny.isSelected(),enchanted.isSelected(),other.isSelected()};for(int i=0;i<keys.length;i++)p.setProperty(BridgeConfig.PREFIX+keys[i],String.valueOf(values[i]));return new BridgeConfig(p);}
    private void save(){if(saving||!save.isEnabled())return;BridgeConfig next=edited();saving=true;save.setEnabled(false);feedback.setText("Validating CSV and saving…");new SwingWorker<Void,Void>(){
        protected Void doInBackground()throws Exception{bridge.configure(next,true,true);return null;}
        protected void done(){saving=false;try{get();feedback.setText(next.enabled&&next.send?"Saved. Check Logs for the confirmation response.":"Settings saved.");}catch(Exception ex){Throwable cause=ex.getCause()==null?ex:ex.getCause();String message=cause.getMessage();if(!next.token.isEmpty()&&message!=null)message=message.replace(next.token,"[redacted]");feedback.setText("Not saved. Check Settings / CSV.");JOptionPane.showMessageDialog(BridgeReviewGUI.this,message,"Bridge settings",JOptionPane.ERROR_MESSAGE);}refresh();}
    }.execute();}
    public void refresh(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
        snapshot=bridge.snapshot();
        if(!snapshot.loading&&!initialSettingsLoaded){load(snapshot.config);initialSettingsLoaded=true;}
        save.setEnabled(!snapshot.loading&&!snapshot.closed&&!bridge.isPreview()&&!saving);
        if(snapshot.revision==revision)return;revision=snapshot.revision;
        long selected=selected()==null?-1:selected().id;
        String logSelection=logs.getSelectedRow()<0?null:String.valueOf(logs.getValueAt(logs.getSelectedRow(),0))+logs.getValueAt(logs.getSelectedRow(),2);
        rebuilding=true;
        state.setText(snapshot.state);totals.setText("Observed "+snapshot.observed+"   •   Accepted "+snapshot.accepted+"   •   Skipped / local "+snapshot.skipped+"   •   Failed / uncertain "+snapshot.failed+"   •   Waiting "+snapshot.queued+"   •   CSV "+snapshot.catalogSize);
        rows=new ArrayList<>(snapshot.reviews);Collections.reverse(rows);reviewModel.setRowCount(0);
        for(BridgeService.Review r:rows){BridgePayload.Drop d=r.drop;reviewModel.addRow(new Object[]{r.time,d.item.rawName,d.item.rarity,d.item.shiny?"Yes":"",(d.characterName==null?"":d.characterName)+" #"+d.characterId,d.dungeon,r.status});}
        logModel.setRowCount(0);for(BridgeService.Log l:snapshot.logs)logModel.addRow(new Object[]{l.time,l.level,l.message});filter();
        for(int i=0;i<rows.size();i++)if(rows.get(i).id==selected){int view=review.convertRowIndexToView(i);if(view>=0)review.setRowSelectionInterval(view,view);break;}
        if(logSelection!=null)for(int i=0;i<logModel.getRowCount();i++)if(logSelection.equals(String.valueOf(logModel.getValueAt(i,0))+logModel.getValueAt(i,2))){int view=logs.convertRowIndexToView(i);if(view>=0)logs.setRowSelectionInterval(view,view);break;}
        rebuilding=false;showDetails();showLogDetails();
    }
    private void showLogDetails(){int row=logs.getSelectedRow();String text=row<0?"Select a diagnostic entry to read its full message.":logs.getValueAt(row,0)+" ["+logs.getValueAt(row,1)+"]\n"+logs.getValueAt(row,2);if(!text.equals(logDetails.getText())){logDetails.setText(text);logDetails.setCaretPosition(0);}}
    @SuppressWarnings("unchecked") private void filter(){
        String query=search.getText().trim();
        TableRowSorter<Rows> rs=(TableRowSorter<Rows>)review.getRowSorter(),ls=(TableRowSorter<Rows>)logs.getRowSorter();
        List<RowFilter<Rows,Integer>> rf=new ArrayList<>(),lf=new ArrayList<>();
        if(!query.isEmpty()){rf.add(RowFilter.regexFilter("(?i)"+Pattern.quote(query)));lf.add(RowFilter.regexFilter("(?i)"+Pattern.quote(query)));}
        if(status.getSelectedIndex()>0)rf.add(RowFilter.regexFilter("^"+Pattern.quote(String.valueOf(status.getSelectedItem()))+"$",6));
        if(level.getSelectedIndex()>0)lf.add(RowFilter.regexFilter("^"+Pattern.quote(String.valueOf(level.getSelectedItem()))+"$",1));
        rs.setRowFilter(rf.isEmpty()?null:RowFilter.andFilter(rf));ls.setRowFilter(lf.isEmpty()?null:RowFilter.andFilter(lf));
    }
    private BridgeService.Review selected(){int row=review.getSelectedRow();if(row<0)return null;int model=review.convertRowIndexToModel(row);return model<rows.size()?rows.get(model):null;}
    private void showDetails(){BridgeService.Review r=selected();if(r==null){details.setText("Select a detected drop to inspect its enchants, character and delivery details.");return;}BridgePayload.Item i=r.drop.item;details.setText(i.rawName+"  •  ID "+i.id+"  •  "+r.status+"\n"+r.time+" | "+r.detail+"\nRarity: "+i.rarity+" ("+i.raritySource+") | Enchant count: "+(i.enchantCount<0?"unknown":i.enchantCount)+" | Divine: "+i.divine+"\nEnchants: "+(i.enchants.isEmpty()?"None decoded":i.enchants)+"\n\nOutgoing JSON (token redacted):\n"+(r.payload.isEmpty()?"No payload queued.":r.payload));details.setCaretPosition(0);}
    private void exportReview(){List<BridgeService.Review> visible=new ArrayList<>();for(int i=0;i<review.getRowCount();i++)visible.add(rows.get(review.convertRowIndexToModel(i)));chooseExport("bridge-review.csv",reviewCsv(visible));}
    private void exportLogs(){StringBuilder text=new StringBuilder();for(int i=0;i<logs.getRowCount();i++){int r=logs.convertRowIndexToModel(i);text.append(logModel.getValueAt(r,0)).append(" [").append(logModel.getValueAt(r,1)).append("] ").append(logModel.getValueAt(r,2)).append('\n');}chooseExport("bridge-diagnostics.log",text.toString());}
    private void chooseExport(String name,String content){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(name));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();if(Files.exists(path)&&JOptionPane.showConfirmDialog(this,"Replace the selected file?","Export",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{Files.write(path,content.getBytes(StandardCharsets.UTF_8));return null;}protected void done(){try{get();feedback.setText("Exported "+path.getFileName());}catch(Exception ex){feedback.setText("Export failed. Check the chosen folder and permissions.");}}}.execute();}
    public static String reviewCsv(List<BridgeService.Review> rows){StringBuilder out=new StringBuilder("Time (UTC),Item,Item ID,Rarity,Shiny,Divine,Enchant count,Enchants,Character ID,Character name,Class,Dungeon,Status,Details\r\n");for(BridgeService.Review r:rows){BridgePayload.Drop d=r.drop;Object[] cells={r.time,d.item.rawName,d.item.id,d.item.rarity,d.item.shiny,d.item.divine,d.item.enchantCount,d.item.enchants,d.characterId,d.characterName,d.characterClass,d.dungeon,r.status,r.detail};for(int i=0;i<cells.length;i++){if(i>0)out.append(',');out.append(csvCell(cells[i]));}out.append("\r\n");}return out.toString();}
    private static String csvCell(Object value){String s=value==null?"":String.valueOf(value);if(s.matches("(?s)^[=+@\\-\\t\\r].*"))s="'"+s;return '"'+s.replace("\"","\"\"")+'"';}
    private static final class Rows extends DefaultTableModel {Rows(String...headers){super(headers,0);}@Override public boolean isCellEditable(int r,int c){return false;}}
}
