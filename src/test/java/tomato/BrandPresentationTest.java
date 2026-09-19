package tomato;

import org.junit.Test;
import realmshark.branding.AppIdentity;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/** Public product presentation must not change upstream compatibility or imply a stock-JAR upgrade. */
public class BrandPresentationTest {
    @Test public void helpNamesTheActualProductArtifact() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, "UTF-8")) {
            System.setOut(capture);
            Method usage = Tomato.class.getDeclaredMethod("usage");
            usage.setAccessible(true);
            usage.invoke(null);
        } finally { System.setOut(original); }
        String help = output.toString("UTF-8");
        assertTrue(help.contains("java -jar RealmShark-" + realmshark.version.Version.VERSION + ".jar"));
        assertTrue(help.contains("--preview"));
        assertTrue(help.contains("--path"));
        assertFalse(help.toLowerCase(Locale.ROOT).contains("tomato"));
    }

    @Test public void upstreamReleaseSelectionKeepsTheExistingBranchIdentifier() throws Exception {
        Method parse = CheckVersion.class.getDeclaredMethod("getTomatoVersion", String.class);
        parse.setAccessible(true);
        assertEquals("upstream-baseline", parse.invoke(null,
            "[{\"target_commitish\":\"realmshark\",\"tag_name\":\"custom-product\"},"
                + "{\"target_commitish\":\"tomato\",\"tag_name\":\"upstream-baseline\"}]"));
        assertEquals("", parse.invoke(null, "[{\"target_commitish\":\"main\",\"tag_name\":\"other\"}]"));
    }

    @Test public void independentUtilityWindowsUseFinIcons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Window> windows = new ArrayList<>();
            try {
                Window bandwidth = new tomato.gui.maingui.TomatoBandwidth();
                windows.add(bandwidth);
                assertFalse("Brand the traffic window before creating its native peer", bandwidth.isDisplayable());
                assertEquals(AppIdentity.icons(), bandwidth.getIconImages());
                windows.add(new tomato.gui.stats.session.FameSessionViewer(new tomato.gui.stats.session.FameSession()));
                windows.add(new tomato.gui.warnings.MissingNpcapGUI());
                windows.add(new packets.packetcapture.sniff.gui.MissingNpcapGUI());
                for (Window window : windows) {
                    assertFalse(window.getIconImages().isEmpty());
                    assertEquals(AppIdentity.icons(), window.getIconImages());
                }
            } finally { for (Window window : windows) window.dispose(); }
        });
    }

    @Test public void releaseNoticeDistinguishesCustomProductFromUpstreamBaseline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method show = CheckVersion.class.getDeclaredMethod("updateMessage");
                show.setAccessible(true);
                show.invoke(null);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            JDialog dialog = null;
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && window.isShowing()
                        && "RealmShark — Upstream Release Notice".equals(((JDialog)window).getTitle()))
                    dialog = (JDialog)window;
            }
            assertNotNull(dialog);
            try {
                assertFalse(dialog.isModal());
                assertEquals(AppIdentity.icons(), dialog.getIconImages());
                JEditorPane body = findBody(dialog);
                assertNotNull(body);
                String text = body.getText().replaceAll("\\s+", " ");
                assertTrue(text.contains(AppIdentity.title()));
                assertTrue(text.contains("Installed upstream baseline: " + tomato.version.Version.VERSION));
                assertTrue(text.contains("not an update to this custom RealmShark build"));
                assertTrue(text.contains("merged and rebuilt"));
                assertTrue(text.contains("https://github.com/X-com/RealmShark/releases"));
                assertFalse(text.toLowerCase(Locale.ROOT).contains("tomato"));
            } finally { dialog.dispose(); }
        });
    }

    private static JEditorPane findBody(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JEditorPane) return (JEditorPane)child;
            if (child instanceof Container) {
                JEditorPane found = findBody((Container)child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
