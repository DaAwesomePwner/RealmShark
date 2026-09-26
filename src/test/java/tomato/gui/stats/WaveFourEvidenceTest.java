package tomato.gui.stats;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import javax.swing.*;
import java.util.*;
import tomato.ability.*;
import tomato.gui.security.AbilityEvidencePanel;
import tomato.gui.search.*;
import tomato.gui.history.*;
import tomato.history.*;
import tomato.history.archive.*;
import tomato.realmshark.ParseEnchants;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/** Synthetic native geometry/evidence only; no capture, account data or external delivery. */
public class WaveFourEvidenceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Rule public VisualEvidence evidence=new VisualEvidence("wave4");
    @Rule public FixtureZone zone=new FixtureZone();
    private void screens(JComponent panel,String name,Runnable check)throws Exception{
        for(int[] size:new int[][]{{1240,800,13},{680,520,13},{680,520,18}}){
            run(()->evidence.show(panel,name,size[0],size[1],size[2]));evidence.settle();
            run(()->{check.run();evidence.capture(name+"-"+size[0]+"-font"+size[2]);});
        }
    }
    @Test public void abilityPopulatedEmptyAndOmissions()throws Exception{
        AbilityObservationStore store=new AbilityObservationStore(10);
        for(int i=0;i<12;i++)store.add(new AbilityObservation("synthetic-session",null,BASE+i*1000,42,"Fixture player "+i,"Mystic",123,"Fixture Orb","stasis",10,12,"Duration candidate followed by non-decreasing MP; ambiguous, not a confirmed cast."));
        store.omit();AbilityEvidencePanel panel=edt(()->new AbilityEvidencePanel(store));
        screens(panel,"ability-populated",()->{
            JTable table=named(panel,"ability-table",JTable.class);assertEquals(10,table.getRowCount());table.setRowSelectionInterval(0,0);
            assertTrue(named(panel,"ability-details",JTextArea.class).getText().contains("not a confirmed cast"));
            assertTrue(named(panel,"ability-status",JTextArea.class).getText().contains("2 evicted · 1 omitted"));
            JTextArea footer=named(panel,"ability-status",JTextArea.class);
            try {
                for(int offset=0;offset<footer.getDocument().getLength();offset++){
                    java.awt.Rectangle glyph=footer.modelToView(offset);
                    assertNotNull(glyph);
                    assertTrue("Footer glyph must remain inside the allocated width",glyph.x+glyph.width<=footer.getWidth());
                    assertTrue("Footer glyph must remain inside the allocated height",glyph.y+glyph.height<=footer.getHeight());
                }
            } catch(javax.swing.text.BadLocationException failure){throw new AssertionError(failure);}
            assertTrue(table.getParent().getHeight()>40);
            assertTrue(named(panel,"ability-details",JTextArea.class).getParent().getHeight()>=80);
        });
        run(()->named(panel,"ability-search",JTextField.class).setText("absent-player"));
        screens(panel,"ability-empty-filter",()->assertEquals(0,named(panel,"ability-table",JTable.class).getRowCount()));
    }
    @Test public void searchPopulatedDisabledAndEmpty()throws Exception{
        ActionRegistry registry=new ActionRegistry();
        registry.register(new ActionDescriptor("font","Font size and family","appearance typography","Appearance → Font","Local realmShark.properties","Changes stay in preview",()->true,"",()->{}));
        registry.register(new ActionDescriptor("capture","Capture options","network","File → Capture options","Local realmShark.properties","Unavailable during preview",()->false,"Preview does not allow capture",()->fail("must not execute")));
        ActionSearchPanel panel=edt(()->new ActionSearchPanel(registry,()->{}));
        screens(panel,"settings-search",()->assertEquals(2,named(panel,"action-results",JList.class).getModel().getSize()));
        run(()->named(panel,"action-search",JTextField.class).setText("capture"));
        screens(panel,"settings-search-disabled",()->{assertFalse(named(panel,"action-open",JButton.class).isEnabled());assertTrue(named(panel,"action-details",JTextArea.class).getText().contains("Preview does not allow capture"));assertTrue(named(panel,"action-details",JTextArea.class).getParent().getHeight()>=120);});
        run(()->named(panel,"action-search",JTextField.class).setText("missing-control"));
        screens(panel,"settings-search-empty",()->assertEquals(0,named(panel,"action-results",JList.class).getModel().getSize()));
    }
    @Test public void lootExactAndLegacyOccurrenceDetails()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            LootDashboard.Item exact=new LootDashboard.Item(123,"Fixture exact-enchant blade","WEAPON,UT",ParseEnchants.evidence(LootEquipmentTest.encode(32767,-1,-2,-3)));
            LootDashboard.Item legacy=new LootDashboard.Item(124,"Fixture legacy item",false);
            store.append("loot",new LootDashboard.Drop("White","Ice Citadel","Fixture boss",BASE,Arrays.asList(exact,legacy),"",DropContext.capture(null,BASE,null)));store.flush();
            ArchiveQuery<LootQuery.Facets,LootQuery.Sort> q=LootQuery.initial(false).withScope(store.currentId());
            try(ArchiveResult<LootQuery.Row> result=ArchiveResult.open(store,q,new LootArchiveAdapter(q),temp.newFolder().toPath(),new Cancellation())){
                ArchivePage<LootQuery.Row> page=result.page(0,100,new Cancellation());
                LootArchiveClient client=new LootArchiveClient(temp.newFolder().toPath(),false);
                JComponent panel=edt(()->client.render(page,ViewState.initial(q),new ArchiveClient.Binding<LootQuery.Facets,LootQuery.Sort>(){
                    public void queryChanged(ArchiveQuery<LootQuery.Facets,LootQuery.Sort> q){}public void viewChanged(ViewState<LootQuery.Facets,LootQuery.Sort> state){}public void refresh(){}
                }));
                // ArchiveWorkspace supplies this scroll fallback in production.
                JComponent host=edt(()->tomato.gui.modern.ContentStyle.page(null,panel,null));
                screens(host,"loot-initial-top",()->{
                    JTabbedPane tabs=named(panel,"loot-archive-tabs",JTabbedPane.class);
                    assertTrue("Initial tab header remains reachable",tabs.getVisibleRect().height>0);
                    assertEquals("Initial archive view starts at its header",0,tabs.getVisibleRect().y);
                });
                screens(host,"loot-captured-exact",()->{JTable table=named(panel,"loot-archive-table",JTable.class);table.setRowSelectionInterval(0,0);JTextArea details=named(panel,"loot-archive-details",JTextArea.class);assertTrue(details.getText().contains("Exact enchantment evidence"));reveal(details);});
                screens(host,"loot-legacy",()->{JTable table=named(panel,"loot-archive-table",JTable.class);table.setRowSelectionInterval(1,1);JTextArea details=named(panel,"loot-archive-details",JTextArea.class);assertTrue(details.getText().contains("LEGACY_NOT_RECORDED"));reveal(details);});
            }
        }
    }
}
