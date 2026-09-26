package tomato.gui.stats;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.LootBags;
import tomato.gui.history.ViewStateStore;

/** Session summaries shared by Statistics and the Loot workspace; filters are view-local. */
public final class LootDashboard extends JPanel {
    static final int RECENT_LIMIT = 1000;
    private final State state;
    private final boolean historical;
    private final JLabel[] metrics = new JLabel[4];
    private final JLabel results = new JLabel();
    private final JTextArea enchantTotals = StatsUi.note("");
    private final JTextArea scopeNote = StatsUi.note(scopeDescription());
    private final JTextField search = StatsUi.search("loot-search", "Search item ID/name, bag, dungeon or dropper", 22);
    private final JComboBox<String> bagFilter = new JComboBox<>(new String[]{"All bags"});
    private final JComboBox<String> dungeonFilter = new JComboBox<>(new String[]{"All dungeons"});
    private final JComboBox<String> recentRange = new JComboBox<>(new String[]{"All retained drops", "Last 5 minutes", "Last 15 minutes", "Last hour"});
    private final JTabbedPane views = new JTabbedPane();
    private static final String[] VIEW_NAMES = {"All Items", "Stat Potions", "Whites", "By Bag", "Recent Drops", "By Dungeon", "UTs", "STs", "Tiered"};
    private final BulkModel[] models = new BulkModel[VIEW_NAMES.length];
    private final List<TableRowSorter<DefaultTableModel>> sorters = new ArrayList<>();
    private boolean rebuilding;
    private long renderedVersion = -1;
    private final boolean[] dirty = new boolean[VIEW_NAMES.length];
    private Summary summary;
    private List<Drop> recent = Collections.emptyList();
    private LootQuery.Facets facets=new LootQuery.Facets();
    private final JPanel facetControls=new JPanel(new BorderLayout());
    private final JTable[] tables=new JTable[VIEW_NAMES.length];
    private final JScrollPane[] scrolls=new JScrollPane[VIEW_NAMES.length];
    private final List<List<String>> rowKeys=new ArrayList<>();
    private final Map<Drop,String> recentKeys=new IdentityHashMap<>();
    private StatisticsLiveState viewState;
    private LootFacetControls facetEditor;

    public LootDashboard() { this(new State()); }
    LootDashboard(LootDashboard shared) { this(shared.state); }
    private LootDashboard(State state) {
        this(state,false);
    }
    private LootDashboard(State state, boolean historical) {
        super(new BorderLayout(0, 8)); this.state = state;this.historical=historical;
        scopeNote.setText(scopeDescription());
        synchronized (state) { state.views.add(this); }
        bagFilter.setName("loot-bag-filter"); dungeonFilter.setName("loot-dungeon-filter"); recentRange.setName("loot-recent-range");
        dungeonFilter.setPrototypeDisplayValue("All dungeons / Lost Halls");
        JPanel controls = StatsUi.controls(); controls.add(search); controls.add(bagFilter); controls.add(dungeonFilter);
        JButton reset = new JButton("Reset filters"); controls.add(reset);
        add(StatsUi.stack(StatsUi.heading("Loot explorer", (historical ? "Saved drops in the selected session scope. " : "Observed drops this app session. ") + "Facets/search filter full aggregates; tab categories narrow the displayed rows."),
            StatsUi.metrics(metrics, "Matching bags", "Matching items", "Matching stat potions", "Matching white bags"), controls,facetControls), BorderLayout.NORTH);
        enchantTotals.setName("loot-enchant-totals");
        views.setName("loot-views");
        for (int i = 0; i < models.length; i++) {
            rowKeys.add(new ArrayList<>());
            String[] columns = i == 3 ? new String[]{"Bag type", "Bags", "Items"}
                : i == 4 ? new String[]{"Time", "Bag type", "Items", "Dungeon", "Dropper"}
                : i == 5 ? new String[]{"Dungeon", "Bags", "Items"}
                : new String[]{"Icon", "Item", "Count", "Last dungeon", "Tier", "Rarity", "Slots", "Enchants"};
            Class<?>[] types = i == 3 || i == 5 ? new Class<?>[]{String.class, Integer.class, Integer.class}
                : i == 4 ? new Class<?>[]{Long.class, String.class, String.class, String.class, String.class}
                : new Class<?>[]{Icon.class, String.class, Integer.class, String.class, String.class, String.class, Integer.class, Integer.class};
            models[i] = new BulkModel(columns, types);
            JTable table = StatsUi.table(models[i], "loot-view-" + i);
            tables[i]=table;scrolls[i]=StatsUi.tableScroll(table);
            @SuppressWarnings("unchecked") TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>)table.getRowSorter();
            sorters.add(sorter);
            if (itemView(i)) {
                table.getColumnModel().getColumn(0).setMaxWidth(40);
                table.getColumnModel().getColumn(1).setPreferredWidth(230);
                for (int col : new int[]{2, 4, 6, 7}) {
                    table.getColumnModel().getColumn(col).setMinWidth(65);
                    table.getColumnModel().getColumn(col).setPreferredWidth(75);
                }
                StatsUi.countColumns(table, 2, 6, 7);
                sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
            }
            if (i == 3 || i == 5) StatsUi.countColumns(table, 1, 2);
            if (i == 4) {
                table.getColumnModel().getColumn(0).setPreferredWidth(170);
                StatsUi.timestampColumn(table, 0);
                JPanel recent = new JPanel(new BorderLayout(0, 6));
                JPanel filter = StatsUi.controls(); filter.add(recentRange); filter.add(new JLabel("Relative to latest captured drop"));
                recent.add(filter, BorderLayout.NORTH); recent.add(scrolls[i], BorderLayout.CENTER); views.addTab(VIEW_NAMES[i], recent);
            } else views.addTab(VIEW_NAMES[i], scrolls[i]);
        }
        views.setToolTipTextAt(6, "UT weapons, abilities, armor and rings; excludes potions, runes and other consumables");
        views.setToolTipTextAt(7, "Only items labeled ST, regardless of bag color");
        views.setToolTipTextAt(8, "Tiered weapons and armor T13+; abilities T6+");
        views.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); add(views, BorderLayout.CENTER);
        results.setFont(ContentStyle.metadata(ContentStyle.body()));
        add(StatsUi.stack(results, enchantTotals, scopeNote), BorderLayout.SOUTH);
        StatsUi.onSearch(search, this::invalidateScope);
        bagFilter.addActionListener(e -> { if (!rebuilding){facets.bags.clear();if(bagFilter.getSelectedIndex()>0)facets.bags.add((String)bagFilter.getSelectedItem());rememberFacets();invalidateScope();rebuildFacetControls();} });
        dungeonFilter.addActionListener(e -> { if (!rebuilding){facets.dungeons.clear();if(dungeonFilter.getSelectedIndex()>0)facets.dungeons.add((String)dungeonFilter.getSelectedItem());rememberFacets();invalidateScope();rebuildFacetControls();} });
        recentRange.addActionListener(e -> { dirty[4] = true; refresh(); });
        views.addChangeListener(e -> {facets.view=liveView(views.getSelectedIndex());rememberFacets();invalidateScope();});
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
        });
        reset.addActionListener(e -> { search.setText(""); bagFilter.setSelectedIndex(0); dungeonFilter.setSelectedIndex(0); recentRange.setSelectedIndex(0); });
        reset.addActionListener(e -> {facets=new LootQuery.Facets();facets.view=liveView(views.getSelectedIndex());rememberFacets();invalidateScope();rebuildFacetControls();});
        rebuildFacetControls();
        refresh();
    }
    public void bindViewState(ViewStateStore store,String key){
        viewState=new StatisticsLiveState(store,key).attach(this);String saved=viewState.value("facets","");
        if(!saved.isEmpty())try{LootQuery.Facets restored=tomato.history.SessionStore.JSON.fromJson(saved,LootQuery.Facets.class);restored.validate();facets=restored;}catch(RuntimeException failure){viewState.reject("Invalid live loot facets");viewState.put("facets",tomato.history.SessionStore.JSON.toJson(facets));}
        viewState.text(search);viewState.tabs(views);viewState.combo(recentRange);
        facets.view=liveView(views.getSelectedIndex());
        for(int i=0;i<tables.length;i++){final int view=i;viewState.table(tables[i],scrolls[i],row->row<rowKeys.get(view).size()?rowKeys.get(view).get(row):"");}
        rebuildFacetControls();invalidateScope();
    }
    void bindSiblingViewState(ViewStateStore store,String key){List<LootDashboard> siblings;synchronized(state){siblings=new ArrayList<>(state.views);}for(LootDashboard sibling:siblings)if(sibling!=this)sibling.bindViewState(store,key);}
    private void rememberFacets(){if(viewState!=null)viewState.put("facets",tomato.history.SessionStore.JSON.toJson(facets));}
    private static LootQuery.View liveView(int index){LootQuery.View[] live={LootQuery.View.ITEMS,LootQuery.View.POTIONS,LootQuery.View.WHITES,LootQuery.View.BAGS,LootQuery.View.RECENT,LootQuery.View.DUNGEONS,LootQuery.View.UTS,LootQuery.View.STS,LootQuery.View.TIERED};return index<0?LootQuery.View.ITEMS:live[index];}
    void applyFacets(LootQuery.Facets next){
        next.validate();facets=tomato.history.SessionStore.JSON.fromJson(tomato.history.SessionStore.JSON.toJson(next),LootQuery.Facets.class);facets.view=liveView(views.getSelectedIndex());
        rebuilding=true;try{bagFilter.setSelectedIndex(0);dungeonFilter.setSelectedIndex(0);}finally{rebuilding=false;}
        rememberFacets();invalidateScope();rebuildFacetControls();
    }
    int[] matchingTotals(){synchronized(state){Summary s=summarize((String)bagFilter.getSelectedItem(),(String)dungeonFilter.getSelectedItem());return new int[]{s.bagsKnown?s.bags:-1,s.items,s.potions};}}
    private void rebuildFacetControls(){
        Set<String> bags=new TreeSet<>(),dungeons=new TreeSet<>();synchronized(state){dungeons.addAll(state.buckets.keySet());for(Map<String,Bucket> bucket:state.buckets.values())bags.addAll(bucket.keySet());}
        facetControls.removeAll();facetEditor=new LootFacetControls(facets,bags,dungeons,this::applyFacets);facetControls.add(facetEditor);facetControls.revalidate();
    }

    public void receive(MapInfoPacket map, Entity bag, Entity dropper, long time) {
        receive(map,bag,dropper,time,DropContext.capture(map,time,null));
    }
    public void receive(MapInfoPacket map, Entity bag, Entity dropper, long time, DropContext context) {
        List<Item> contents = new ArrayList<>();
        StatData unique = bag.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] encoded = unique == null || unique.stringStatValue == null ? new String[0] : unique.stringStatValue.split(",", -1);
        for (int slot = 0; slot < 8; slot++) {
            StatData stat = bag.stat.get(StatType.INVENTORY_0_STAT.get() + slot);
            if (stat != null && stat.statValue > 0) {
                int id = stat.statValue;
                contents.add(new Item(id, name(id), IdToAsset.getIdLabel(id),
                    ParseEnchants.evidence(slot < encoded.length ? encoded[slot] : null)));
            }
        }
        String type = LootBags.lootBagName(bag.objectType);
        Drop drop = new Drop(type == null ? "Unknown (" + bag.objectType + ")" : type,
            map == null ? "Unknown" : tomato.realmshark.ParseDungeon.canonicalMapName(map), dropper == null ? "Unknown" : name(dropper.objectType), time, contents,
            packets.packetcapture.logger.DiscoveryLog.INSTANCE.currentVisitId(),context);
        tomato.history.AppHistory.append("loot", drop);
        accept(drop);
    }
    private static String name(int id) { String value = IdToAsset.objectName(id); return value == null || value.isEmpty() ? "Item #" + id : value; }
    private static boolean white(String bag) { return bag.equals("White") || bag.equals("B.White"); }
    private static boolean itemView(int view) { return view < 3 || view >= 6; }
    private String scopeDescription() {
        return "Observed drops, not pickups · Tiered: weapons/armor T13+, abilities T6+ · Slots = unlocked enchant slots (including empty); Enchants = applied effects. Rarity follows slot count; unavailable counts are — and missing/invalid rarity is Unknown.\nWhites: contents of white / boosted white bags · Recent Drops searches the globally newest "
            + DisplayFormat.formatInteger(RECENT_LIMIT) + " bags by timestamp (ties: session and record order); item summaries retain " + (historical ? "the selected session scope." : "the full app session.");
    }

    void accept(Drop drop) {
        acceptAll(Collections.singletonList(drop));
    }

    /** Records detached drops immediately; one queued EDT refresh serves a burst and every shared view. */
    void acceptAll(Collection<Drop> drops) {
        synchronized (state) {
            if (drops.isEmpty()) return;
            for (Drop drop : drops) accumulate(state, drop, "", state.sequence++);
            state.version++;
            if (state.refreshQueued) return;
            state.refreshQueued = true;
        }
        SwingUtilities.invokeLater(() -> {
            List<LootDashboard> shared;
            synchronized (state) { state.refreshQueued = false; shared = new ArrayList<>(state.views); }
            for (LootDashboard view : shared) view.refresh();
        });
    }

    List<Drop> recentDrops() { synchronized (state) { return recentDrops(state); } }
    private static List<Drop> recentDrops(State state) {
        List<Drop> result = new ArrayList<>();
        for (Recent entry : state.recent.descendingSet()) result.add(entry.drop);
        return result;
    }
    private static void accumulate(State state, Drop drop, String session, long ordinal) {
        String name = tomato.backend.data.DungeonStatData.Snapshot.canonicalName(drop.dungeon);
        Map<String, Bucket> dungeon=state.buckets.computeIfAbsent(name,key->new LinkedHashMap<>());
        Bucket bucket=dungeon.computeIfAbsent(drop.bag,key->new Bucket());bucket.bags++;
        List<String> shapeKey=new ArrayList<>();shapeKey.add("dropper:"+drop.dropper);
        for(Item item:drop.items)shapeKey.add(tomato.history.SessionStore.JSON.toJson(itemContext(item,drop.dropper)));Collections.sort(shapeKey);
        BagShape shape=bucket.shapes.get(shapeKey);
        if(shape==null){
            if(state.shapeKeys<LootArchiveAdapter.MAX_KEYS){shape=new BagShape(drop.items,drop.dropper);bucket.shapes.put(shapeKey,shape);state.shapeKeys++;}
            else state.bagFacetsUnavailable=true;
        }
        if(shape!=null)shape.count++;
        state.totalBags++;state.totalItems+=drop.items.size();
        for(Item item:drop.items){bucket.items++;if(item.potion)bucket.potions++;
            ItemCount count=bucket.counts.computeIfAbsent(itemContext(item,drop.dropper),key->new ItemCount(item,drop.dropper));count.count++;count.lastTime=Math.max(count.lastTime,drop.time);}
        state.recent.add(new Recent(drop, session, ordinal));
        if(state.recent.size()>RECENT_LIMIT)state.recent.pollFirst();
    }
    static final class Archive {
        private final State state=new State();
        private final Map<String, Long> ordinals = new HashMap<>();
        void accept(Drop drop){accept("",drop);}
        void accept(String session, Drop drop){long ordinal=ordinals.getOrDefault(session,0L);ordinals.put(session,ordinal+1);accumulate(state,drop,session,ordinal);state.version++;}
        LootDashboard view(){return new LootDashboard(state,true);}
    }
    void searchHistory(String query){search.setText(query);}
    int[] sessionTotals() { synchronized (state) { return new int[]{state.totalBags, state.totalItems}; } }

    private void invalidateScope() { renderedVersion = -1; refresh(); }

    private void refresh() {
        if (!isShowing() || models[0] == null) return;
        String description = scopeDescription();
        if (!description.equals(scopeNote.getText())) scopeNote.setText(description);
        List<String> dungeons = null;
        Set<String> bags = new LinkedHashSet<>();
        List<String> key = Arrays.asList((String)bagFilter.getSelectedItem(), (String)dungeonFilter.getSelectedItem());
        synchronized (state) {
            if (renderedVersion != state.version) {
                dungeons = new ArrayList<>(state.buckets.keySet());
                for (Map<String, Bucket> dungeon : state.buckets.values()) bags.addAll(dungeon.keySet());
                summary = summarize(key.get(0), key.get(1));
                recent = recentDrops(state);
                recentKeys.clear();for(Recent entry:state.recent)recentKeys.put(entry.drop,entry.session+"/bag-"+entry.ordinal+"/"+entry.drop.time);
                renderedVersion = state.version;
                Arrays.fill(dirty, true);
            }
        }
        if (dungeons != null) {
            rebuilding = true;
            for (String dungeon : dungeons) addOption(dungeonFilter, dungeon);
            for (String bag : bags) addOption(bagFilter, bag);
            rebuilding = false;
            if(facetEditor!=null)facetEditor.updateChoices(bags,dungeons);
        }
        if (summary != null) {
            metrics[0].setText(summary.bagsKnown?DisplayFormat.formatInteger(summary.bags):DisplayFormat.UNAVAILABLE); metrics[1].setText(DisplayFormat.formatInteger(summary.items));
            metrics[2].setText(DisplayFormat.formatInteger(summary.potions)); metrics[3].setText(summary.bagsKnown?DisplayFormat.formatInteger(summary.whites):DisplayFormat.UNAVAILABLE);
        }
        int view = views.getSelectedIndex();
        if (view >= 0 && dirty[view]) {
            models[view].replaceRows(rows(view));
            dirty[view] = false;
        }
        filter();
    }

    private Summary summarize(String bagSelection, String dungeonSelection) {
        Summary result = new Summary();
        boolean itemQuery=facets.itemRestricted()||!search.getText().trim().isEmpty();
        result.bagsKnown=!itemQuery||!state.bagFacetsUnavailable;
        for (Map.Entry<String, Map<String, Bucket>> dungeon : state.buckets.entrySet()) {
            if (!"All dungeons".equals(dungeonSelection) && !dungeon.getKey().equals(dungeonSelection)) continue;
            if(!facets.dungeon(dungeon.getKey()))continue;
            for (Map.Entry<String, Bucket> bag : dungeon.getValue().entrySet()) {
                if (!"All bags".equals(bagSelection) && !bag.getKey().equals(bagSelection)) continue;
                if(!facets.bags.isEmpty()&&!facets.bags.contains(bag.getKey()))continue;
                if(facets.view==LootQuery.View.WHITES&&!white(bag.getKey()))continue;
                Bucket bucket = bag.getValue();int matchingBags=0;
                if(!itemQuery)matchingBags=bucket.bags;
                else for(BagShape shape:bucket.shapes.values()){
                    boolean match=shape.items.isEmpty()&&matchesEmptyBag(bag.getKey(),dungeon.getKey(),shape.dropper);
                    for(Item item:shape.items)if(matchesItem(item,bag.getKey(),dungeon.getKey(),shape.dropper)){match=true;break;}
                    if(match)matchingBags+=shape.count;
                }
                result.bags+=matchingBags;if(white(bag.getKey()))result.whites+=matchingBags;
                int matching=0;
                for (ItemCount item : bucket.counts.values()) {
                    if(!matchesItem(item.item,bag.getKey(),dungeon.getKey(),item.dropper))continue;
                    matching+=item.count;result.items+=item.count;if(item.item.potion)result.potions+=item.count;
                    result.allItems.computeIfAbsent(item.item.key, key -> new Aggregate(item.item)).add(item, dungeon.getKey());
                    if (white(bag.getKey())) result.whiteItems.computeIfAbsent(item.item.key, key -> new Aggregate(item.item)).add(item, dungeon.getKey());
                }
                if(matchingBags>0||matching>0){int[] byBag=result.byBag.computeIfAbsent(bag.getKey(),k->new int[2]),byDungeon=result.byDungeon.computeIfAbsent(dungeon.getKey(),k->new int[2]);
                    byBag[0]+=matchingBags;byBag[1]+=matching;byDungeon[0]+=matchingBags;byDungeon[1]+=matching;}
            }
        }
        return result;
    }
    private static List<Object> itemContext(Item item,String dropper){return Arrays.asList(item.key,item.name,item.tier,item.ut,item.st,item.potion,item.highTier,dropper);}
    /** Search the same explicit fields as saved occurrences, never display punctuation. */
    private boolean matchesItem(Item item,String bag,String dungeon,String dropper){
        return facets.item(item)&&LootQuery.contains(item.id+" "+item.name+" "+dungeon+" "+bag+" "+dropper+" "+LootQuery.tier(item)+" "+LootQuery.rarity(item),search.getText());
    }
    private boolean matchesEmptyBag(String bag,String dungeon,String dropper){return !facets.itemRestricted()&&LootQuery.contains(dungeon+" "+bag+" "+dropper,search.getText());}

    private List<Object[]> rows(int view) {
        List<Object[]> rows = new ArrayList<>();
        List<String> keys=new ArrayList<>();
        if (itemView(view)) {
            for (Aggregate item : (view == 2 ? summary.whiteItems : summary.allItems).values()) {
                if (view == 1 && !item.item.potion) continue;
                if (view == 6 && !item.item.ut || view == 7 && !item.item.st || view == 8 && !item.item.highTier) continue;
                Icon icon = iconForItem(item.item.id);
                ParseEnchants.Summary enchants = item.item.enchants;
                keys.add(item.item.key.toString());
                rows.add(new Object[]{icon, item.item.name, item.count, item.dungeon, item.item.tier,
                    enchants.slots == 0 ? "Common / Unenchanted" : enchants.rarity(),
                    enchants.slots < 0 ? null : enchants.slots, enchants.applied < 0 ? null : enchants.applied});
            }
        } else if (view == 3 || view == 5) {
            (view == 3 ? summary.byBag : summary.byDungeon).forEach((name, totals) -> {keys.add(name);rows.add(new Object[]{name, summary.bagsKnown?totals[0]:null, totals[1]});});
        } else {
            String bagSelection = (String)bagFilter.getSelectedItem(), dungeonSelection = (String)dungeonFilter.getSelectedItem();
            long[] windows = {0, 300000, 900000, 3600000}; long window = windows[recentRange.getSelectedIndex()];
            long latest = recent.stream().mapToLong(drop -> drop.time).max().orElse(0);
            for (Drop drop : recent) {
                if (!"All bags".equals(bagSelection) && !drop.bag.equals(bagSelection)) continue;
                String canonical = tomato.backend.data.DungeonStatData.Snapshot.canonicalName(drop.dungeon);
                if (!"All dungeons".equals(dungeonSelection) && !canonical.equals(dungeonSelection)) continue;
                if(!facets.location(drop.bag,canonical))continue;
                if (window > 0 && drop.time < latest - window) continue;
                StringJoiner names = new StringJoiner(", "); for (Item item : drop.items) if(matchesItem(item,drop.bag,canonical,drop.dropper))names.add(item.description());
                if(names.length()==0&&(!drop.items.isEmpty()||!matchesEmptyBag(drop.bag,canonical,drop.dropper)))continue;
                keys.add(recentKeys.get(drop));
                rows.add(new Object[]{drop.time, drop.bag,
                    drop.items.isEmpty() ? "No visible items" : names.toString(), drop.dungeon, drop.dropper});
            }
        }
        rowKeys.set(view,keys);return rows;
    }
    Icon iconForItem(int id) { return state.icons.computeIfAbsent(id, key -> ImageBuffer.liveOutlinedIcon(key, 24)); }
    private static void addOption(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) if (value.equals(combo.getItemAt(i))) return;
        combo.addItem(value);
    }
    private void filter() {
        if (!isShowing()) return;
        int view = views.getSelectedIndex();
        if (view >= 0) sorters.get(view).setRowFilter(null);
        updateResults();
    }
    private void updateResults() {
        int view = views.getSelectedIndex(); if (view < 0 || view >= sorters.size()) return;
        enchantTotals.setVisible(itemView(view));
        if (itemView(view)) {
            int[] counts = new int[6]; int total = 0;
            TableRowSorter<DefaultTableModel> sorter = sorters.get(view);
            for (int row = 0; row < sorter.getViewRowCount(); row++) {
                int modelRow = sorter.convertRowIndexToModel(row);
                Integer slots = (Integer)models[view].getValueAt(modelRow, 6);
                int count = (Integer)models[view].getValueAt(modelRow, 2);
                counts[slots == null ? 5 : slots] += count; total += count;
            }
            enchantTotals.setText(DisplayFormat.formatInteger(total) + " drops shown · Unenchanted (0 slots): " + DisplayFormat.formatInteger(counts[0])
                + " · Uncommon (1): " + DisplayFormat.formatInteger(counts[1]) + " · Rare (2): " + DisplayFormat.formatInteger(counts[2])
                + " · Legendary (3): " + DisplayFormat.formatInteger(counts[3]) + " · Divine (4): " + DisplayFormat.formatInteger(counts[4]) + " · Unknown: " + DisplayFormat.formatInteger(counts[5]));
        }
        results.setText(summary!=null&&!summary.bagsKnown?"Live filtered bag counts unavailable: more than 25,000 bag compositions. Item totals remain complete; query saved occurrences for exact bag counts."
            :summary == null || summary.bags == 0 ? (historical ? "No saved loot in this scope; recording coverage may be unknown. Adjust the filters or inspect Session comparison." : "No matching live loot. Adjust filters; capture records future observations.")
            : DisplayFormat.formatInteger(sorters.get(view).getViewRowCount()) + " rows shown · " + views.getTitleAt(view) + " · Tab/facets/search cover full live aggregates. Recent Drops rows/range use 1,000 retained bags; cards remain full-session facet totals.");
    }
    static final class Item {
        final int id; final String name, tier; final boolean potion, ut, st, highTier;
        final ParseEnchants.Summary enchants;
        final ParseEnchants.Evidence enchantEvidence;
        final List<Integer> key;
        Item(int id, String name, boolean potion) { this(id, name, potion ? "STATPOTION" : "", ParseEnchants.summarize(null)); }
        Item(int id, String name, String labels, ParseEnchants.Summary enchants) {
            this(id,name,labels,enchants,null);
        }
        Item(int id,String name,String labels,ParseEnchants.Evidence evidence){this(id,name,labels,evidence.summary(),evidence);}
        Item(int id, String name, String labels, ParseEnchants.Summary enchants,ParseEnchants.Evidence evidence) {
            this.enchantEvidence=evidence;
            this.id = id; this.name = name; this.enchants = enchants;
            List<String> tokens = Arrays.asList((labels == null ? "" : labels).toUpperCase(Locale.ROOT).split("\\s*,\\s*"));
            potion = tokens.contains("STATPOTION");
            boolean equipment = tokens.contains("WEAPON") || tokens.contains("ABILITY") || tokens.contains("ARMOR") || tokens.contains("RING");
            ut = tokens.contains("UT") && equipment && !tokens.contains("CONSUMABLE") && !potion;
            st = tokens.contains("ST");
            int level = -1;
            for (String token : tokens) if (token.matches("T[0-9]{1,2}")) { level = Integer.parseInt(token.substring(1)); break; }
            tier = tokens.contains("UT") ? "UT" : st ? "ST" : level >= 0 ? "T" + level : "—";
            highTier = !ut && !st && !tokens.contains("CONSUMABLE")
                && ((level >= 13 && (tokens.contains("WEAPON") || tokens.contains("ARMOR"))) || (level >= 6 && tokens.contains("ABILITY")));
            key = Collections.unmodifiableList(Arrays.asList(id, enchants.slots, enchants.applied));
        }
        String description() {
            String detail = enchants.slots < 0 ? "Unknown enchants" : (enchants.slots == 0 ? "Unenchanted" : enchants.rarity())
                + ", " + DisplayFormat.formatInteger(enchants.slots) + " slots, "
                + (enchants.applied < 0 ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(enchants.applied)) + " enchants";
            return name + " [" + (tier.equals("—") ? "" : tier + " · ") + detail + "]";
        }
    }
    static final class Drop {
        final DropContext context;
        final String visitId;
        final String bag, dungeon, dropper; final long time; final List<Item> items;
        Drop(String bag, String dungeon, String dropper, long time, List<Item> items) {
            this(bag,dungeon,dropper,time,items,"");
        }
        Drop(String bag, String dungeon, String dropper, long time, List<Item> items, String visitId) {
            this(bag,dungeon,dropper,time,items,visitId,null);
        }
        Drop(String bag, String dungeon, String dropper, long time, List<Item> items, String visitId,DropContext context) {
            this.context=context;
            this.visitId=visitId;
            this.bag = bag; this.dungeon = dungeon; this.dropper = dropper; this.time = time;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
    private static final class State {
        final Map<String, Map<String, Bucket>> buckets = new LinkedHashMap<>();
        final Map<Integer, Icon> icons = new HashMap<>();
        final NavigableSet<Recent> recent = new TreeSet<>(Comparator.comparingLong((Recent r) -> r.drop.time)
            .thenComparing(r -> r.session).thenComparingLong(r -> r.ordinal));
        final List<LootDashboard> views = new ArrayList<>();
        long version, sequence;
        int totalBags, totalItems,shapeKeys;
        boolean refreshQueued,bagFacetsUnavailable;
    }
    private static final class Recent {
        final Drop drop; final String session; final long ordinal;
        Recent(Drop drop, String session, long ordinal) { this.drop=drop;this.session=session;this.ordinal=ordinal; }
    }
    private static final class Bucket { int bags, items, potions; final Map<List<Object>, ItemCount> counts = new LinkedHashMap<>();final Map<List<String>,BagShape> shapes=new HashMap<>(); }
    private static final class BagShape {final List<Item> items;final String dropper;int count;BagShape(List<Item> items,String dropper){this.items=new ArrayList<>(items);this.dropper=dropper;}}
    private static final class ItemCount {
        final Item item; final String dropper; int count; long lastTime = Long.MIN_VALUE;
        ItemCount(Item item,String dropper) { this.item = item; this.dropper=dropper; }
    }
    private static final class Aggregate {
        final Item item; int count; long lastTime = Long.MIN_VALUE; String dungeon = "";
        Aggregate(Item item) { this.item = item; }
        void add(ItemCount item, String name) { count += item.count; if (item.lastTime >= lastTime) { lastTime = item.lastTime; dungeon = name; } }
    }
    private static final class Summary {
        int bags, items, potions, whites;
        boolean bagsKnown=true;
        final Map<List<Integer>, Aggregate> allItems = new LinkedHashMap<>(), whiteItems = new LinkedHashMap<>();
        final Map<String, int[]> byBag = new TreeMap<>(), byDungeon = new TreeMap<>();
    }
    private static final class BulkModel extends DefaultTableModel {
        private final Class<?>[] types;
        BulkModel(String[] columns, Class<?>[] types) { super(columns, 0); this.types = types; }
        public Class<?> getColumnClass(int column) { return types[column]; }
        public boolean isCellEditable(int row, int column) { return false; }
        void replaceRows(List<Object[]> rows) {
            dataVector.clear();
            for (Object[] row : rows) dataVector.add(convertToVector(row));
            fireTableDataChanged();
        }
    }
}
