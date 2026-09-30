package tomato.gui.modern;

import java.awt.Insets;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.UIResource;
import org.junit.After;
import org.junit.Test;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.runs.RunRecapView;
import ui.VisualEvidence;
import static org.junit.Assert.*;

/**
 * P6b Polish E: a scroll pane drawn without a box keeps none after a live theme switch. Each switch's updateUI puts the look and
 * feel's scroll-pane outline back in place of a null (or UIResource) border, which nested two to four frames on a page after dark →
 * light; an empty border is the app's own and survives. Party's and the character sheet's panes are checked in their packages
 * (PartyRestyleTest, SheetViewsTest) through {@link #assertBoxlessThroughThemeSwitch}.
 */
public class ThemeSwitchBordersTest {
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    /**
     * Builds the component in the dark theme, switches live to light and back to dark as Settings › Appearance does
     * ({@link Themes#install}, then the tree's updateUI), and asserts after the build and after each switch that the scroll pane
     * {@code pane} picks out has no box: a border that is not the look and feel's, with no insets. Restores the dark theme.
     * Called off the EDT.
     */
    public static void assertBoxlessThroughThemeSwitch(String what, Supplier<? extends JComponent> build,
                                                       Function<JComponent, JScrollPane> pane) throws Exception {
        onEdt(() -> {
            try {
                Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
                JComponent root = build.get();
                JScrollPane scroll = pane.apply(root);
                assertBoxless(what + ", built in the dark theme", scroll);
                for (Themes.Variant variant : new Themes.Variant[]{Themes.Variant.LIGHT, Themes.Variant.DARK}) {
                    assertTrue(Themes.install(new Themes.Choice(variant, false)));
                    SwingUtilities.updateComponentTreeUI(root);
                    assertSame(what + ": the same scroll pane after the switch", scroll, pane.apply(root));
                    assertBoxless(what + ", after a live switch to " + variant, scroll);
                }
            } finally {
                Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            }
        });
    }

    /** A null border shows no box too, until the next switch replaces it: the switches are what tell the two apart. */
    private static void assertBoxless(String where, JScrollPane scroll) {
        Border border = scroll.getBorder();
        assertFalse(where + ": not the look and feel's outline (" + (border == null ? null : border.getClass().getName()) + ", insets "
            + scroll.getInsets() + ")", border instanceof UIResource);
        assertEquals(where + ": no inset", new Insets(0, 0, 0, 0), scroll.getInsets());
    }

    private static void onEdt(Runnable body) throws Exception {
        try { SwingUtilities.invokeAndWait(body); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw failure;
        }
    }

    @Test public void aContentStylePageKeepsNoBoxAfterALiveThemeSwitch() throws Exception {
        assertBoxlessThroughThemeSwitch("ContentStyle.page", () -> ContentStyle.page(new JLabel("Header"), new JPanel(), new JLabel("Footer")),
            root -> (JScrollPane) root);
    }

    @Test public void buildsPageKeepsNoBoxAfterALiveThemeSwitch() throws Exception {
        Field instance = MyInfoGUI.class.getDeclaredField("INSTANCE");   // the constructor registers the live view; put it back
        instance.setAccessible(true);
        Object previous = instance.get(null);
        try {
            assertBoxlessThroughThemeSwitch("Build", () -> new MyInfoGUI(new TomatoData()),
                root -> VisualEvidence.named(root, "myinfo-page-scroll", JScrollPane.class));
        } finally { instance.set(null, previous); }
    }

    @Test public void theRunRecapKeepsNoBoxAfterALiveThemeSwitch() throws Exception {
        Map<String, String> modeStore = new HashMap<>();
        DisplayModeModel mode = new DisplayModeModel(modeStore::get, modeStore::put);   // never the application's ui.mode
        assertBoxlessThroughThemeSwitch("Run recap", () -> new RunRecapView(mode),
            root -> VisualEvidence.named(root, "run-recap-scroll", JScrollPane.class));
    }
}
