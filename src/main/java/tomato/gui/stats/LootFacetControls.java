package tomato.gui.stats;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootQuery.*;
import tomato.history.SessionStore;

/** Explicit multi-selection and numeric/unknown intent. Apply never filters a loaded page. */
final class LootFacetControls extends JPanel {
    private final JList<String> bags,dungeons,rarities,tiers;
    private final JComboBox<Kind> kind=new JComboBox<>(Kind.values());
    private final RangeControl slots,applied;
    private final Facets initial;
    LootFacetControls(Facets facets,Collection<String> bagChoices,Collection<String> dungeonChoices,Consumer<Facets> changed){
        super(new BorderLayout(0,4));initial=facets;
        JPanel grid=ContentStyle.responsiveGrid(4,150,6);
        bags=list("loot-bags-multi",bagChoices,facets.bags);dungeons=list("loot-dungeons-multi",dungeonChoices,facets.dungeons);
        rarities=list("loot-rarity-multi",Arrays.asList("Common / Unenchanted","Uncommon","Rare","Legendary","Divine","Unknown"),facets.rarities);
        List<String> ts=new ArrayList<>(Arrays.asList("UT","ST","Unknown"));for(int i=0;i<=99;i++)ts.add("T"+i);
        tiers=list("loot-tier-multi",ts,facets.tiers);
        grid.add(box("Bags (none = all)",bags));grid.add(box("Dungeons (none = all)",dungeons));grid.add(box("Rarity",rarities));grid.add(box("Tier",tiers));
        JPanel numbers=ContentStyle.controls();kind.setSelectedItem(facets.kind);kind.setName("loot-kind");kind.getAccessibleContext().setAccessibleName("Item category");numbers.add(kind);
        slots=new RangeControl("Slots",facets.slots);applied=new RangeControl("Applied",facets.applied);numbers.add(slots);numbers.add(applied);
        JLabel error=new JLabel(" ");JButton apply=new JButton("Apply facets"),clear=new JButton("Clear facets");apply.setName("loot-apply-facets");
        apply.addActionListener(e->{try{Facets next=value();next.validate();changed.accept(next);error.setText(" ");}catch(RuntimeException failure){error.setText(failure.getMessage());}});
        clear.addActionListener(e->{Facets next=new Facets();next.view=initial.view;changed.accept(next);});numbers.add(apply);numbers.add(clear);
        JPanel expanded=new JPanel(new BorderLayout(0,4));expanded.add(grid);expanded.add(numbers,BorderLayout.SOUTH);expanded.setVisible(false);
        JButton toggle=new JButton("Multi-select loot facets…");toggle.addActionListener(e->{expanded.setVisible(!expanded.isVisible());revalidate();});
        JPanel top=ContentStyle.controls();top.add(toggle);top.add(new JLabel(summary(facets)));top.add(error);add(top,BorderLayout.NORTH);add(expanded);
    }
    Facets value(){Facets next=SessionStore.JSON.fromJson(SessionStore.JSON.toJson(initial),Facets.class);next.bags=new LinkedHashSet<>(bags.getSelectedValuesList());next.dungeons=new LinkedHashSet<>(dungeons.getSelectedValuesList());next.rarities=new LinkedHashSet<>(rarities.getSelectedValuesList());next.tiers=new LinkedHashSet<>(tiers.getSelectedValuesList());next.kind=(Kind)kind.getSelectedItem();next.slots=slots.value();next.applied=applied.value();return next;}
    void updateChoices(Collection<String> bagChoices,Collection<String> dungeonChoices){update(bags,bagChoices);update(dungeons,dungeonChoices);}
    private static void update(JList<String> list,Collection<String> choices){
        Set<String> selected=new LinkedHashSet<>(list.getSelectedValuesList()),values=new TreeSet<>(choices);values.addAll(selected);
        List<String> current=new ArrayList<>();for(int i=0;i<list.getModel().getSize();i++)current.add(list.getModel().getElementAt(i));if(current.equals(new ArrayList<>(values)))return;
        list.setListData(values.toArray(new String[0]));for(int i=0;i<list.getModel().getSize();i++)if(selected.contains(list.getModel().getElementAt(i)))list.addSelectionInterval(i,i);
    }
    static String summary(Facets f){return "Bags "+f.bags+" · Dungeons "+f.dungeons+" · "+f.kind+" · Rarity "+f.rarities+" · Tier "+f.tiers+" · Slots "+range(f.slots)+" · Applied "+range(f.applied);}
    private static String range(Range r){return Objects.toString(r.min,"any")+"…"+Objects.toString(r.max,"any")+" / unknown "+r.unknown;}
    private static JList<String> list(String name,Collection<String> choices,Set<String> selected){
        Set<String> all=new TreeSet<>(choices);all.addAll(selected);JList<String> list=new JList<>(all.toArray(new String[0]));list.setName(name);list.getAccessibleContext().setAccessibleName(name.replace('-',' '));list.setVisibleRowCount(4);list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        DefaultListCellRenderer renderer=new DefaultListCellRenderer();renderer.putClientProperty("html.disable",true);list.setCellRenderer(renderer);
        for(int i=0;i<list.getModel().getSize();i++)if(selected.contains(list.getModel().getElementAt(i)))list.addSelectionInterval(i,i);return list;
    }
    private static JPanel box(String label,JList<String> list){JPanel p=new JPanel(new BorderLayout());JLabel l=new JLabel(label);l.setLabelFor(list);p.add(l,BorderLayout.NORTH);p.add(new JScrollPane(list));return p;}
    private static final class RangeControl extends JPanel {
        final JTextField min=new JTextField(3),max=new JTextField(3);final JComboBox<Unknown> unknown=new JComboBox<>(Unknown.values());
        RangeControl(String name,Range r){super(new FlowLayout(FlowLayout.LEFT,3,0));add(new JLabel(name+" ≥"));add(min);add(new JLabel("≤"));add(max);add(unknown);min.setText(Objects.toString(r.min,""));max.setText(Objects.toString(r.max,""));unknown.setSelectedItem(r.unknown);min.setName("loot-"+name.toLowerCase(Locale.ROOT)+"-min");max.setName("loot-"+name.toLowerCase(Locale.ROOT)+"-max");unknown.setName("loot-"+name.toLowerCase(Locale.ROOT)+"-unknown");min.getAccessibleContext().setAccessibleName(name+" minimum");max.getAccessibleContext().setAccessibleName(name+" maximum");unknown.getAccessibleContext().setAccessibleName(name+" unknown policy");}
        Range value(){Range r=new Range();r.min=min.getText().trim().isEmpty()?null:Integer.valueOf(min.getText().trim());r.max=max.getText().trim().isEmpty()?null:Integer.valueOf(max.getText().trim());r.unknown=(Unknown)unknown.getSelectedItem();return r;}
    }
}
