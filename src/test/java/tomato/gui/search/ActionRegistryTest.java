package tomato.gui.search;
import org.junit.Test;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
public class ActionRegistryTest {
    @Test public void searchNeverExecutesAndDisabledStateIsRechecked(){
        AtomicBoolean enabled=new AtomicBoolean(false);AtomicInteger opened=new AtomicInteger();ActionRegistry r=new ActionRegistry();
        ActionDescriptor font=new ActionDescriptor("appearance.font","Font","text typography scaling","Appearance","realmShark.properties","Preview only",enabled::get,"Preview restriction",opened::incrementAndGet);r.register(font);
        assertEquals(1,r.search("FONT appearance").size());assertEquals(0,opened.get());assertFalse(font.open());assertEquals(0,opened.get());
        enabled.set(true);assertTrue(font.open());assertEquals(1,opened.get());assertEquals(0,r.search("nonexistent").size());
    }
    @Test public void stableRegistrationReplacesWithoutDuplicatingAndDescriptionsRemainSpecific(){
        ActionRegistry r=new ActionRegistry();ActionDescriptor d=new ActionDescriptor("history.location","History location","folder storage","History","Configured history directory","Read-only preview",()->true,"",()->{});
        r.register(d);r.register(d);assertEquals(1,r.search("storage").size());assertTrue(d.details().contains("Configured history directory"));
    }
}
