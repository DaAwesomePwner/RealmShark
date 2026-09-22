package tomato.realmshark;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.TomatoData;
import tomato.gui.maingui.*;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.button;

public class SilentRuleEditorTest {
    @Test public void editorConstructionAndSelectionAreSilentAndTestUsesRecordedSubmission() throws Exception {
        AtomicInteger audio = new AtomicInteger();
        Sound.playbackOverride = (sound, preview) -> { assertTrue(preview); audio.incrementAndGet(); };
        try { SwingUtilities.invokeAndWait(() -> {
            TomatoData data = new TomatoData();
            ItemPingGUI item = new ItemPingGUI(data); EntityPingGUI entity = new EntityPingGUI(data);
            EnchantPingGUI enchant = new EnchantPingGUI(null);
            assertEquals(0, audio.get());
            button(enchant, "Select shown").doClick(); assertEquals(0, audio.get());
            button(item, "Test sound").doClick(); button(entity, "Test sound").doClick(); button(enchant, "Test sound").doClick();
            assertEquals(3, audio.get());
        }); } finally { Sound.playbackOverride = null; }
    }
}
