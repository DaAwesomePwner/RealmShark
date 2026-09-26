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
    private final JTextArea totals=ContentStyle.wrappingText("",2);
    private final JTextField search=new JTextField(22),endpoint=new JTextField(),guild=new JTextField(),csv=new JTextField(),audit=new JTextField();
    private final JPasswordField token=new JPasswordField();
    private final JCheckBox enabled=new JCheckBox("Enable bridge"),send=new JCheckBox("Send matching drops to bot"),debug=new JCheckBox("Debug logs");
    private final JCheckBox ut=new JCheckBox("UT"),st=new JCheckBox("ST"),shiny=new JCheckBox("Shiny"),enchanted=new JCheckBox("Enchanted"),other=new JCheckBox("Other CSV items");
    private final JComboBox<String> status=new JComboBox<>(new String[]{"All statuses","Queued","Logged","Accepted","Not logged","Not in CSV","Filtered","Local only","Rejected","Uncertain","Cancelled","Queue full"});
    private final JComboBox<Object> outcome=new JComboBox<>();
    private final JComboBox<String> character=new JComboBox<>(new String[]{"All characters"}), dungeon=new JComboBox<>(new String[]{"All dungeons"});
    private final JComboBox<String> enchantFilter=new JComboBox<>(new String[]{"All enchant states","Applied enchants","No applied enchants","Unknown enchants"});
    private final JComboBox<String> level=new JComboBox<>(new String[]{"All levels","INFO","DEBUG","ERROR"});
    private final Rows reviewModel=new Rows("Time (UTC)","Item","Rarity","Shiny","Character","Dungeon","Outcome","Delivery status");
    private final Rows logModel=new Rows("Time (UTC)","Level","Message");
    private final JTable review=new JTable(reviewModel),logs=new JTable(logModel);
    private final JTextArea details=note("Detected drops will appear here once the bridge and network capture are enabled. Select a row to inspect enchants and the outgoing fields.");
    private final JTextArea logDetails=note("Select a diagnostic entry to read its full message.");
    private final JTabbedPane tabs=new JTabbedPane();
    private final JButton save=new JButton("Save settings"),export=new JButton("Export review CSV"),exportLogs=new JButton("Export logs"),revert=new JButton("Revert to active");
    private final JButton alertDraft=new JButton("Item alert from this drop…");
    private final Rows savedModel=new Rows("Time (UTC)","Item","Outcome","Delivery status","Character","Dungeon","Session","Journal");
    private final JTable saved=new JTable(savedModel);
    private final JTextArea savedSummary=ContentStyle.wrappingText("No journal opened.",2),savedProblems=ContentStyle.wrappingText(""),savedDetails=note("Select a saved record to read its historical outcome.");
    private final JButton openConfigured=new JButton("Open configured review log"),openFile=new JButton("Open journal file…"),exportSaved=new JButton("Export saved review CSV");
    private List<BridgeJournal.Entry> savedEntries=Collections.emptyList();
    private BridgeJournal.Result savedResult;
    private long savedRequest;
    /** Opens a detached alert draft; replaced by tests. The Runnable restores the source row when the editor closes. */
    private java.util.function.BiConsumer<tomato.realmshark.AlertRules.Draft,Runnable> draftOpener=tomato.gui.maingui.AlertRuleEditor::openDraft;
    private final JTextArea activeSummary=ContentStyle.wrappingText(""),draftState=ContentStyle.wrappingText(""),validation=ContentStyle.wrappingText(""),confirmation=ContentStyle.wrappingText(""),saveResult=ContentStyle.wrappingText("");
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
        // The lifetime/shown counters describe the live Review table only, so they live on that tab instead of
        // taking height from Settings, Logs and Saved review in short windows.
        totals.setName("bridge-totals");totals.getAccessibleContext().setAccessibleName("Lifetime and shown delivery outcome counts");summary.add(state,BorderLayout.NORTH);add(summary,BorderLayout.NORTH);
        setupTable(review,ContentStyle.Density.COMFORTABLE);setupTable(logs,ContentStyle.Density.DENSE);review.setName("bridge-review-table");logs.setName("bridge-log-table");
        review.getColumnModel().getColumn(6).setCellRenderer(new ContentStyle.Badge(){
            @Override protected Color badgeColor(Object value){
                String status=String.valueOf(value);
                if(status.equals(BridgeService.Outcome.LOGGED.toString()))return ContentStyle.color("mint");
                if(status.equals(BridgeService.Outcome.RECEIVED.toString())||status.equals(BridgeService.Outcome.PENDING.toString()))return ContentStyle.color("amber");
                if(status.equals(BridgeService.Outcome.FAILED.toString()))return ContentStyle.color("rose");
                return ContentStyle.color("muted");
            }
        });
        review.getColumnModel().getColumn(1).setPreferredWidth(230);logs.getColumnModel().getColumn(2).setPreferredWidth(620);
        review.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={145,205,75,55,125,145,180,115};
        for(int i=0;i<widths.length;i++)review.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JPanel reviewPage=new JPanel(new BorderLayout(0,8));
        JPanel tools=ContentStyle.controls();search.setName("bridge-search");status.setName("bridge-status-filter");
        search.setColumns(18);search.getAccessibleContext().setAccessibleName("Search bridge review and logs");status.getAccessibleContext().setAccessibleName("Drop delivery status");
        outcome.addItem("All outcomes");for(BridgeService.Outcome bucket:BridgeService.Outcome.values())outcome.addItem(bucket);
        outcome.setName("bridge-outcome-filter");character.setName("bridge-character-filter");dungeon.setName("bridge-dungeon-filter");enchantFilter.setName("bridge-enchant-filter");
        outcome.getAccessibleContext().setAccessibleName("Delivery outcome bucket");character.getAccessibleContext().setAccessibleName("Observed character");dungeon.getAccessibleContext().setAccessibleName("Observed dungeon");enchantFilter.getAccessibleContext().setAccessibleName("Applied enchant state");
        search.setToolTipText("Search reasons, item IDs, names, enchant descriptions, characters and dungeons in retained review rows.");
        character.setPrototypeDisplayValue("All characters / Example #123");dungeon.setPrototypeDisplayValue("All dungeons / Lost Halls");
        JButton reset=new JButton("Reset filters");reset.addActionListener(e->{search.setText("");status.setSelectedIndex(0);outcome.setSelectedIndex(0);character.setSelectedIndex(0);dungeon.setSelectedIndex(0);enchantFilter.setSelectedIndex(0);});
        tools.add(labeled("Search",search));tools.add(outcome);tools.add(status);tools.add(character);tools.add(dungeon);tools.add(enchantFilter);tools.add(reset);tools.add(export);
        alertDraft.setName("bridge-alert-draft");alertDraft.setEnabled(false);alertDraft.setToolTipText("Draft an exact item-ID alert from the selected drop. Opens silently; nothing is saved, enabled or sent.");
        alertDraft.addActionListener(e->draftFromSelected());tools.add(alertDraft);
        JPanel reviewTop=new JPanel(new BorderLayout(0,6));reviewTop.add(totals,BorderLayout.NORTH);reviewTop.add(tools);
        details.setName("bridge-details");details.setOpaque(true);details.setFont(ContentStyle.report(ContentStyle.body()));details.setMargin(new Insets(6,8,6,8));
        details.getAccessibleContext().setAccessibleName("Selected drop delivery details");
        JScrollPane detailScroll=new JScrollPane(details);detailScroll.setMinimumSize(new Dimension(0,100));detailScroll.setPreferredSize(new Dimension(700,175));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ContentStyle.tableScroll(review,3),detailScroll);split.setResizeWeight(.68);split.setBorder(null);
        // Short windows scroll the tab content instead of squeezing the table and details to nothing.
        reviewPage.add(ContentStyle.page(reviewTop,split,note("CSV controls what can be sent. Drops are observed in bags; pickup is not verified. Review retains the latest 1,000 items.")));
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
        tabs.addTab("Logs",logPage);tabs.addTab("Saved review",savedReview());tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);add(tabs);feedback.setName("bridge-feedback");feedback.setFont(ContentStyle.metadata(ContentStyle.body()));add(feedback,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
        status.addActionListener(e->filter());level.addActionListener(e->filter());
        outcome.addActionListener(e->filter());character.addActionListener(e->{if(!rebuilding)filter();});dungeon.addActionListener(e->{if(!rebuilding)filter();});enchantFilter.addActionListener(e->filter());
        review.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!rebuilding)showDetails();});
        logs.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!rebuilding)showLogDetails();});
        clear.addActionListener(e->{bridge.clearLogs();refresh();});export.addActionListener(e->exportReview());exportLogs.addActionListener(e->exportLogs());save.addActionListener(e->save());
        state.setName("bridge-state");save.setName("bridge-save");
        load(bridge.config());trackEdits();if(bridge.isPreview())save.setToolTipText("Preview never saves settings or contacts the bot.");
        timer=new javax.swing.Timer(650,e->{if(isShowing())refresh();});refresh();
    }
    @Override public void addNotify(){super.addNotify();timer.start();}
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
    /** BRIDGE-4: historical evidence from local journals; no control here can send, retry or configure. */
    private JComponent savedReview(){
        JPanel page=new JPanel(new BorderLayout(0,8));page.setName("bridge-saved-panel");
        JTextArea explain=note("Saved review reopens local review journals as historical evidence. It never sends, retries or changes Bridge settings. "+BridgeJournal.UNRECOVERABLE);
        explain.setName("bridge-saved-note");
        openConfigured.setName("bridge-saved-open-configured");openFile.setName("bridge-saved-open-file");exportSaved.setName("bridge-saved-export");exportSaved.setEnabled(false);
        saved.setName("bridge-saved-table");savedSummary.setName("bridge-saved-summary");savedProblems.setName("bridge-saved-problems");savedDetails.setName("bridge-saved-details");
        savedProblems.setVisible(false);
        setupTable(saved,ContentStyle.Density.COMFORTABLE);saved.getAccessibleContext().setAccessibleName("Saved review records");
        savedDetails.setOpaque(true);savedDetails.setMargin(new Insets(6,8,6,8));savedDetails.setFont(ContentStyle.report(ContentStyle.body()));savedDetails.getAccessibleContext().setAccessibleName("Selected saved record details");
        JPanel tools=ContentStyle.controls();tools.add(openConfigured);tools.add(openFile);tools.add(exportSaved);
        JPanel top=new JPanel(new BorderLayout(0,6));top.add(explain,BorderLayout.NORTH);top.add(tools);
        JPanel status=new JPanel(new BorderLayout(0,4));status.add(savedSummary,BorderLayout.NORTH);status.add(savedProblems);top.add(status,BorderLayout.SOUTH);
        JScrollPane detailScroll=new JScrollPane(savedDetails);detailScroll.setMinimumSize(new Dimension(0,90));detailScroll.setPreferredSize(new Dimension(700,150));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,ContentStyle.tableScroll(saved,3),detailScroll);split.setResizeWeight(.68);split.setBorder(null);
        // Explanation, actions and skipped lines keep their full height; below that the records and details keep a
        // usable floor and the tab content scrolls when the window is short.
        JScrollPane scroll=ContentStyle.page(top,split,null);scroll.setName("bridge-saved-scroll");page.add(scroll);
        openConfigured.addActionListener(e->{List<Path> paths=BridgeJournal.configured(bridge.config());
            if(paths.isEmpty()){savedSummary.setText("No review log is set in the active settings. Choose a journal file instead.");return;}openJournals(paths);});
        openFile.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Open saved Bridge review journals");chooser.setMultiSelectionEnabled(true);
            if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;List<Path> paths=new ArrayList<>();for(java.io.File f:chooser.getSelectedFiles())paths.add(f.toPath());
            if(paths.isEmpty()&&chooser.getSelectedFile()!=null)paths.add(chooser.getSelectedFile().toPath());openJournals(paths);});
        exportSaved.addActionListener(e->chooseExport("bridge-saved-review.csv",savedCsv()));
        saved.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showSavedDetails();});
        return page;
    }
    /** Reads explicitly chosen journals off the EDT; a newer request replaces an older one. Never calls the service. */
    public void openJournals(List<Path> journals){
        long request=++savedRequest;List<Path> paths=new ArrayList<>(journals);savedSummary.setText("Reading "+paths.size()+(paths.size()==1?" journal…":" journals…"));openConfigured.setEnabled(false);openFile.setEnabled(false);
        new SwingWorker<BridgeJournal.Result,Void>(){
            protected BridgeJournal.Result doInBackground(){return BridgeJournal.read(paths);}
            protected void done(){if(request!=savedRequest)return;openConfigured.setEnabled(true);openFile.setEnabled(true);
                try{showSaved(get());}catch(Exception failure){savedSummary.setText("Could not read the selected journals ("+failure.getClass().getSimpleName()+").");}}
        }.execute();
    }
    private void showSaved(BridgeJournal.Result result){
        savedResult=result;savedEntries=new ArrayList<>(result.entries);Collections.reverse(savedEntries);savedModel.setRowCount(0);
        for(BridgeJournal.Entry e:savedEntries){BridgeService.Review r=e.review;savedModel.addRow(new Object[]{r.time,r.drop.item.rawName,r.outcome().toString(),r.status,characterLabel(r),dungeonLabel(r),e.session,e.journal});}
        savedSummary.setText(result.summary()+". Sources: "+String.join(", ",result.sources)+".");
        StringBuilder problems=new StringBuilder();for(BridgeJournal.Problem p:result.problems){if(problems.length()>0)problems.append('\n');problems.append(p);}
        if(result.malformed>result.problems.size())problems.append("\n…and ").append(result.malformed-result.problems.size()).append(" more unreadable lines.");
        savedProblems.setText(problems.length()==0?"":"Skipped lines:\n"+problems);savedProblems.setVisible(problems.length()>0);
        exportSaved.setEnabled(!savedEntries.isEmpty());showSavedDetails();
    }
    public BridgeJournal.Result savedResult(){return savedResult;}
    /** Export of the saved rows currently shown, with journal/session identity. */
    public String savedCsv(){List<BridgeJournal.Entry> shown=new ArrayList<>();for(int i=0;i<saved.getRowCount();i++)shown.add(savedEntries.get(saved.convertRowIndexToModel(i)));return BridgeJournal.csv(shown);}
    private void showSavedDetails(){
        int row=saved.getSelectedRow();
        if(row<0){savedDetails.setText(savedEntries.isEmpty()?"No saved records loaded. "+BridgeJournal.UNRECOVERABLE:"Select a saved record to read its historical outcome.");return;}
        BridgeJournal.Entry e=savedEntries.get(saved.convertRowIndexToModel(row));BridgeService.Review r=e.review;BridgePayload.Item i=r.drop.item;
        savedDetails.setText("Saved record (historical; nothing here is sent or retried)\nIdentity: "+e.identity()+" · line "+e.line+"\nFormat: "+(e.legacy?"legacy journal without a session; restarts are inferred when the review counter restarts":"version "+BridgeJournal.VERSION)
            +(e.appSession==null?"":"\nApp session: "+e.appSession)+"\n\nObservation: "+i.rawName+"  •  ID "+i.id+"\n"+r.time+" | "+characterLabel(r)+" | "+dungeonLabel(r)+" | Bag #"+r.drop.bagId+" slot "+r.drop.slot
            +"\nRarity: "+i.rarity+" | Enchants: "+(i.enchants==null||i.enchants.isEmpty()?"None decoded":i.enchants)+"\n\nLocal choice at observation: "+(r.localChoice==null?"Not recorded":r.localChoice)
            +"\nBot / delivery result: "+r.outcome()+" ["+r.status+"]\n"+r.detail);
        savedDetails.setCaretPosition(0);
    }
    private JComponent settings() {
        JPanel page=new JPanel(new BorderLayout(0,8));page.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JPanel status=new JPanel();status.setLayout(new BoxLayout(status,BoxLayout.Y_AXIS));saveResult.setVisible(false);validation.setVisible(false);
        activeSummary.setName("bridge-active");draftState.setName("bridge-draft-state");validation.setName("bridge-validation");confirmation.setName("bridge-confirmation");saveResult.setName("bridge-save-result");revert.setName("bridge-revert");
        activeSummary.setFont(ContentStyle.emphasis(ContentStyle.metadata(ContentStyle.body())));validation.setForeground(ContentStyle.color("rose"));
        saveResult.addPropertyChangeListener("UI",e->{if(saveResultIsError())saveResult.setForeground(ContentStyle.color("rose"));});
        for(JTextArea area:new JTextArea[]{activeSummary,draftState,validation,saveResult,confirmation}){area.setAlignmentX(Component.LEFT_ALIGNMENT);area.setBorder(BorderFactory.createEmptyBorder(2,0,2,0));status.add(area);}
        JPanel intro=new JPanel(new BorderLayout(0,6));intro.add(note("Use the endpoint, Guild ID and Link Token supplied by your guild. Enable capture with File > Start Sniffer. Save with Enable bridge and Send selected to submit the same confirmation ping as the public bridge. The form is a draft until Save; the active settings are shown below."),BorderLayout.NORTH);intro.add(status);
        page.add(intro,BorderLayout.NORTH);
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
        JPanel actions=ContentStyle.controls();actions.add(save);actions.add(revert);revert.addActionListener(e->revert());JButton included=new JButton("Use included CSV");actions.add(included);included.addActionListener(e->csv.setText("./rotmg_loot_drops_updated.csv"));
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
            private void changed(){if(!loadingFields)editedFields.add(field);updateDraftState();}
            public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}
        });
        for(JCheckBox box:new JCheckBox[]{enabled,send,debug,ut,st,shiny,enchanted,other})box.addItemListener(e->{if(!loadingFields)editedFields.add(box);updateDraftState();});
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
    /**
     * BRIDGE-3: the service validates, loads the CSV and saves before switching, so a failure leaves the
     * previous settings active. The draft stays in the form and the result is reported inline.
     */
    private void save(){if(saving||!save.isEnabled())return;BridgeConfig next=edited();saving=true;save.setEnabled(false);revert.setEnabled(false);feedback.setText("Validating CSV and saving…");saveResult.setText("Saving… the current settings stay active until this succeeds.");styleResult(false);new SwingWorker<Void,Void>(){
        protected Void doInBackground()throws Exception{bridge.configure(next,true,true);return null;}
        protected void done(){saving=false;try{get();feedback.setText(next.enabled&&next.send?"Saved and active. The confirmation result is shown in Settings.":"Settings saved and active.");saveResult.setText("Saved and active: "+next.modeLabel()+".");styleResult(false);}
            catch(Exception ex){Throwable cause=ex.getCause()==null?ex:ex.getCause();String message=cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage();if(!next.token.isEmpty())message=message.replace(next.token,"[redacted]");
                String previous=notApplied()?"Nothing is active; the saved settings were not applied.":"The previous settings remain active ("+bridge.config().modeLabel()+").";
                feedback.setText(notApplied()?"Not saved. Nothing is active; your draft is kept.":"Not saved. The previous settings remain active; your draft is kept.");
                saveResult.setText("Not saved: "+sentence(message)+" "+previous+" Your draft is still in the form; correct it and Save again, or Revert.");styleResult(true);}
            refresh();updateDraftState();}
    }.execute();}
    private void revert(){if(saving)return;BridgeConfig active=snapshot==null?bridge.config():snapshot.config;editedFields.clear();load(active);saveResult.setText(notApplied()?"Draft reverted to the saved settings, which are not in effect.":"Draft reverted to the active settings.");styleResult(false);updateDraftState();}
    /** A failed save uses the same error style as the invalid-draft message; other results use the default text colour. */
    private void styleResult(boolean error){saveResult.putClientProperty("bridge.error",error);saveResult.setForeground(error?ContentStyle.color("rose"):UIManager.getColor("TextArea.foreground"));}
    /** True while the save result reports a failure (shown in the error style). */
    public boolean saveResultIsError(){return Boolean.TRUE.equals(saveResult.getClientProperty("bridge.error"));}
    /** Ends a service message as a sentence so the explanation that follows reads correctly. */
    static String sentence(String message){String text=message.trim();if(text.isEmpty())return "Unknown error.";char last=text.charAt(text.length()-1);return last=='.'||last=='!'||last=='?'?text:text+".";}
    private void updateDraftState(){
        if(loadingFields)return;
        BridgeConfig active=snapshot==null?bridge.config():snapshot.config,draft=edited();
        java.util.List<String> changed=draft.differences(active);
        String basis=notApplied()?"saved settings":"active settings";
        text(draftState,changed.isEmpty()?"The form matches the "+basis+".":"Unsaved changes: "+String.join(", ",changed)+". Save applies them; Revert restores the "+basis+".");
        revert.setEnabled(!changed.isEmpty()&&!saving);
        String problem="";try{draft.validate();}catch(RuntimeException invalid){problem=invalid.getMessage()==null?"Check the paths and endpoint.":invalid.getMessage();}
        if(!draft.token.isEmpty())problem=problem.replace(draft.token,"[redacted]");
        text(validation,problem.isEmpty()?"":"Fix before saving: "+problem);validation.setVisible(!problem.isEmpty());
        boolean loading=snapshot==null||snapshot.loading;
        text(activeSummary,loading?"Active now: loading saved settings…":notApplied()?(bridge.isPreview()?"Active now: nothing. Preview shows the saved settings ("+active.modeLabel()+") but never applies or sends them.":"Active now: nothing. The saved settings ("+active.modeLabel()+") were not applied: "+snapshot.state+". Correct them and Save."):"Active now: "+active.modeLabel()+(active.enabled?" · CSV items: "+snapshot.catalogSize:"")+(active.enabled&&active.send?" · Endpoint: "+host(active.endpoint):"")+" · Review log: "+(active.reviewLog.isEmpty()?"off":"on"));
        text(confirmation,snapshot==null?"":snapshot.confirmation.label());
        saveResult.setVisible(!saveResult.getText().isEmpty());
    }
    /** Saved settings that failed validation at startup are shown as saved, never as active. */
    private boolean notApplied(){return snapshot!=null&&!snapshot.loading&&!snapshot.applied;}
    private static void text(JTextArea area,String value){if(!value.equals(area.getText()))area.setText(value);}
    private static String host(String endpoint){try{String host=java.net.URI.create(endpoint).getHost();return host==null?"not set":host;}catch(RuntimeException e){return "invalid";}}
    public void refresh(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
        snapshot=bridge.snapshot();
        if(!snapshot.loading&&!initialSettingsLoaded){load(snapshot.config);initialSettingsLoaded=true;}
        save.setEnabled(!snapshot.loading&&!snapshot.closed&&!bridge.isPreview()&&!saving);
        updateDraftState();
        if(snapshot.revision==revision)return;revision=snapshot.revision;
        long selected=selected()==null?-1:selected().id;
        String logSelection=logs.getSelectedRow()<0?null:String.valueOf(logs.getValueAt(logs.getSelectedRow(),0))+logs.getValueAt(logs.getSelectedRow(),2);
        rebuilding=true;
        state.setText(snapshot.state);
        rows=new ArrayList<>(snapshot.reviews);Collections.reverse(rows);reviewModel.setRowCount(0);
        for(BridgeService.Review r:rows){BridgePayload.Drop d=r.drop;reviewModel.addRow(new Object[]{r.time,d.item.rawName,d.item.rarity,d.item.shiny?"Yes":"",(d.characterName==null?"":d.characterName)+" #"+d.characterId,d.dungeon,r.outcome().toString(),r.status});}
        TreeSet<String> characters=new TreeSet<>(),dungeons=new TreeSet<>();for(BridgeService.Review r:rows){characters.add(characterLabel(r));dungeons.add(dungeonLabel(r));}
        updateFacet(character,"All characters",characters);updateFacet(dungeon,"All dungeons",dungeons);
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
        rf.add(new RowFilter<Rows,Integer>(){public boolean include(Entry<? extends Rows,? extends Integer> entry){
            int index=entry.getIdentifier();if(index>=rows.size())return false;BridgeService.Review r=rows.get(index);
            int enchants=r.drop.item.enchantCount;
            return r.matches(query)&&(status.getSelectedIndex()==0||r.status.equals(status.getSelectedItem()))
                &&(outcome.getSelectedIndex()==0||r.outcome()==outcome.getSelectedItem())
                &&(character.getSelectedIndex()==0||characterLabel(r).equals(character.getSelectedItem()))
                &&(dungeon.getSelectedIndex()==0||dungeonLabel(r).equals(dungeon.getSelectedItem()))
                &&(enchantFilter.getSelectedIndex()==0||enchantFilter.getSelectedIndex()==1&&enchants>0||enchantFilter.getSelectedIndex()==2&&enchants==0||enchantFilter.getSelectedIndex()==3&&enchants<0);
        }});
        if(!query.isEmpty())lf.add(RowFilter.regexFilter("(?iu)"+Pattern.quote(query)));
        if(level.getSelectedIndex()>0)lf.add(RowFilter.regexFilter("^"+Pattern.quote(String.valueOf(level.getSelectedItem()))+"$",1));
        rs.setRowFilter(rf.isEmpty()?null:RowFilter.andFilter(rf));ls.setRowFilter(lf.isEmpty()?null:RowFilter.andFilter(lf));
        updateTotals();if(!rebuilding)showDetails();
    }
    private static String characterLabel(BridgeService.Review r){return (r.drop.characterName==null?"Unknown":r.drop.characterName)+" #"+r.drop.characterId;}
    private static String dungeonLabel(BridgeService.Review r){return r.drop.dungeon==null||r.drop.dungeon.isEmpty()?"Unknown":r.drop.dungeon;}
    private static void updateFacet(JComboBox<String> combo,String all,Set<String> values){Object selected=combo.getSelectedItem();combo.removeAllItems();combo.addItem(all);for(String value:values)combo.addItem(value);if(selected!=null&&!all.equals(selected)&&!values.contains(selected))combo.addItem(selected.toString());combo.setSelectedItem(selected==null?all:selected);}
    private void updateTotals(){
        if(snapshot==null)return;
        Map<BridgeService.Outcome,Long> shown=new EnumMap<>(BridgeService.Outcome.class);
        for(int i=0;i<review.getRowCount();i++)shown.merge(rows.get(review.convertRowIndexToModel(i)).outcome(),1L,Long::sum);
        StringBuilder text=new StringBuilder("Lifetime (this service): ").append(snapshot.observed).append(" observed items");
        for(BridgeService.Outcome bucket:BridgeService.Outcome.values())text.append(" · ").append(bucket).append(' ').append(snapshot.count(bucket));
        text.append("\nShown: ").append(review.getRowCount()).append(" / ").append(rows.size()).append(" retained items");
        for(BridgeService.Outcome bucket:BridgeService.Outcome.values())text.append(" · ").append(bucket).append(' ').append(shown.getOrDefault(bucket,0L));
        totals.setText(text.toString());
    }
    private BridgeService.Review selected(){int row=review.getSelectedRow();if(row<0)return null;int model=review.convertRowIndexToModel(row);return model<rows.size()?rows.get(model):null;}
    /** Test seam for the draft opener. */
    public void useDraftOpener(java.util.function.BiConsumer<tomato.realmshark.AlertRules.Draft,Runnable> opener){draftOpener=opener;}
    /** Drafts from the selected retained review; returning reselects the same review by ID when it is still retained and shown. */
    public void draftFromSelected(){
        BridgeService.Review r=selected();if(r==null)return;long id=r.id;
        tomato.realmshark.AlertRules.Draft draft=tomato.realmshark.AlertRules.Draft.item(tomato.realmshark.AlertRules.Mode.ITEM_ID,Integer.toString(r.drop.item.id),r.drop.item.id,r.drop.item.rawName,
            "Bridge review · "+r.time+" · "+characterLabel(r));
        draftOpener.accept(draft,()->{for(int i=0;i<rows.size();i++)if(rows.get(i).id==id){int view=review.convertRowIndexToView(i);if(view>=0){tabs.setSelectedIndex(0);review.setRowSelectionInterval(view,view);review.scrollRectToVisible(review.getCellRect(view,0,true));review.requestFocusInWindow();return;}}
            feedback.setText("The drop used for the alert draft is no longer shown (filters changed or it left the retained review).");});
    }
    private void showDetails(){BridgeService.Review r=selected();alertDraft.setEnabled(r!=null&&r.drop.item.id>0);if(r==null){details.setText(rows.isEmpty()?"No retained observations. Bridge Review only records drops while enabled.":review.getRowCount()==0?"No matching retained observations. Reset filters to see other drops.":"Select a detected drop to inspect its enchants, character and delivery details.");return;}BridgePayload.Item i=r.drop.item;details.setText("Observation: "+i.rawName+"  •  ID "+i.id+"\n"+r.time+" | "+characterLabel(r)+" | "+dungeonLabel(r)+" | Bag #"+r.drop.bagId+" slot "+r.drop.slot+" (pickup not verified)\nRarity: "+i.rarity+" ("+i.raritySource+") | Enchant count: "+(i.enchantCount<0?"unknown":i.enchantCount)+" | Divine: "+i.divine+"\nEnchants: "+(i.enchants.isEmpty()?"None decoded":i.enchants)+"\n\nLocal choice at observation: "+(r.localChoice==null?"Not recorded":r.localChoice)+"\nBot / delivery result: "+r.outcome()+" ["+r.status+"]\n"+r.detail+"\nNext step: "+r.nextStep()+"\n\nOutgoing JSON (token redacted):\n"+(r.payload.isEmpty()?"No payload queued.":r.payload));details.setCaretPosition(0);}
    private void exportReview(){List<BridgeService.Review> visible=new ArrayList<>();for(int i=0;i<review.getRowCount();i++)visible.add(rows.get(review.convertRowIndexToModel(i)));chooseExport("bridge-review.csv",reviewCsv(visible));}
    private void exportLogs(){StringBuilder text=new StringBuilder();for(int i=0;i<logs.getRowCount();i++){int r=logs.convertRowIndexToModel(i);text.append(logModel.getValueAt(r,0)).append(" [").append(logModel.getValueAt(r,1)).append("] ").append(logModel.getValueAt(r,2)).append('\n');}chooseExport("bridge-diagnostics.log",text.toString());}
    private void chooseExport(String name,String content){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(name));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();if(Files.exists(path)&&JOptionPane.showConfirmDialog(this,"Replace the selected file?","Export",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{Files.write(path,content.getBytes(StandardCharsets.UTF_8));return null;}protected void done(){try{get();feedback.setText("Exported "+path.getFileName());}catch(Exception ex){feedback.setText("Export failed. Check the chosen folder and permissions.");}}}.execute();}
    public static String reviewCsv(List<BridgeService.Review> rows){StringBuilder out=new StringBuilder("Time (UTC),Item,Item ID,Rarity,Shiny,Divine,Enchant count,Enchants,Character ID,Character name,Class,Dungeon,Status,Details\r\n");for(BridgeService.Review r:rows){BridgePayload.Drop d=r.drop;Object[] cells={r.time,d.item.rawName,d.item.id,d.item.rarity,d.item.shiny,d.item.divine,d.item.enchantCount,d.item.enchants,d.characterId,d.characterName,d.characterClass,d.dungeon,r.status,r.detail};for(int i=0;i<cells.length;i++){if(i>0)out.append(',');out.append(csvCell(cells[i]));}out.append("\r\n");}return out.toString();}
    private static String csvCell(Object value){String s=value==null?"":String.valueOf(value);if(s.matches("(?s)^[=+@\\-\\t\\r].*"))s="'"+s;return '"'+s.replace("\"","\"\"")+'"';}
    private static final class Rows extends DefaultTableModel {Rows(String...headers){super(headers,0);}@Override public boolean isCellEditable(int r,int c){return false;}}
}
