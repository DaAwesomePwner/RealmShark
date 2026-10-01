package tomato.gui.modern;

import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.formdev.flatlaf.ui.FlatBorder;
import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.plaf.UIResource;
import org.junit.*;
import static org.junit.Assert.*;

public class VioletControlsTest {
    private Font previousFont;
    private LookAndFeel previousLaf;
    private boolean previousContrast;

    @Before public void remember() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel();
            previousContrast = Themes.increaseContrast();
        });
    }

    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.setBodyFont(previousFont);
            Themes.install(new Themes.Choice(Themes.Variant.DARK, previousContrast));
            if (previousLaf != null) try { UIManager.setLookAndFeel(previousLaf); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
    }

    @Test public void defaultsUsePaletteRolesInBothVariantsAndContrastModes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) for (boolean contrast : new boolean[]{false, true}) {
                assertTrue(Themes.install(new Themes.Choice(variant, contrast)));
                VioletTheme.Palette p = variant == Themes.Variant.DARK ? VioletTheme.Palette.DARK : VioletTheme.Palette.LIGHT;
                assertEquals(new Color(contrast ? p.contrastBorder : p.fieldBorder), UIManager.getColor("Violet.fieldBorder"));
                assertEquals(contrast ? 3 : 2, UIManager.getInt("Component.focusWidth"));
                assertEquals(30, UIManager.getInt("Component.minimumHeight"));
                assertEquals(8, UIManager.getInt("Component.arc"));
                assertEquals(8, UIManager.getInt("TextComponent.arc"));
                assertEquals(32, UIManager.getInt("TabbedPane.tabHeight"));
                assertEquals(8, UIManager.getInt("TabbedPane.tabArc"));
                assertEquals(3, UIManager.getInt("TabbedPane.tabSelectionHeight"));
                assertEquals(3, UIManager.getInt("TabbedPane.tabSelectionArc"));
                assertEquals(new Insets(2, 2, 2, 2), UIManager.getInsets("TabbedPane.selectedInsets"));
                assertEquals(new Insets(-2, 14, 2, 14), UIManager.getInsets("TabbedPane.tabSelectionInsets"));
                assertEquals(new Insets(4, 14, 4, 14), UIManager.getInsets("TabbedPane.tabInsets"));
                assertEquals(new Color(p.selection), UIManager.getColor("TabbedPane.selectedBackground"));
                assertEquals(new Color(p.selectionText), UIManager.getColor("TabbedPane.selectedForeground"));
                assertEquals(new Color(p.accentBright), UIManager.getColor("TabbedPane.underlineColor"));
                assertEquals(new Color(p.accent), UIManager.getColor("TabbedPane.inactiveUnderlineColor"));
                assertNotEquals(UIManager.getColor("TabbedPane.hoverColor"), UIManager.getColor("TabbedPane.background"));
                assertNotEquals(UIManager.getColor("TabbedPane.hoverColor"), UIManager.getColor("TabbedPane.selectedBackground"));
                assertNotEquals(UIManager.getColor("TabbedPane.hoverColor"), UIManager.getColor("TabbedPane.focusColor"));
                for (String type : new String[]{"TextField", "FormattedTextField", "PasswordField", "ComboBox", "Spinner"}) {
                    Color focused = UIManager.getColor(type + ".focusedBackground");
                    assertNotEquals(UIManager.getColor(type + ".background"), focused);
                    assertTrue(type + " focused text contrast", ratio(UIManager.getColor(type + ".foreground"), focused) >= 4.5);
                    assertEquals(UIManager.getColor("TextField.focusedBackground"), focused);
                }
                assertEquals(UIManager.getColor("TextField.focusedBackground"), UIManager.getColor("ComboBox.buttonFocusedBackground"));
                for (String type : new String[]{"ComboBox", "Spinner"}) {
                    assertEquals(new Color(p.accent), UIManager.getColor(type + ".buttonArrowColor"));
                    assertEquals(new Color(p.accentBright), UIManager.getColor(type + ".buttonHoverArrowColor"));
                    assertEquals(new Insets(4, 10, 4, 10), UIManager.getInsets(type + ".padding"));
                }
                assertEquals(new Insets(4, 10, 4, 10), UIManager.getInsets("TextField.margin"));
                assertEquals(new Color(p.selection), UIManager.getColor("ComboBox.selectionBackground"));
                assertEquals(new Color(p.selectionText), UIManager.getColor("ComboBox.selectionForeground"));
                assertEquals(6, UIManager.getInt("ComboBox.selectionArc"));
                assertEquals(new Insets(1, 4, 1, 4), UIManager.getInsets("ComboBox.selectionInsets"));
                assertEquals(new Insets(4, 0, 4, 0), UIManager.getInsets("ComboBox.popupInsets"));
                assertEquals(8, UIManager.getInt("ComboBox.borderCornerRadius"));
            }
        });
    }

    @Test public void existingFieldsAndSearchIconsFollowLiveThemeSwitches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            JPanel root = new JPanel();
            JTextField search = new JTextField("Find me"); TextSearchBar.decorateSearch(search);
            JComponent[] fields = {search, new JFormattedTextField(), new JPasswordField(), new JComboBox<>(new String[]{"One"}), new JSpinner()};
            for (JComponent field : fields) root.add(field);
            for (JComponent field : fields)
                assertEquals(UIManager.getColor("Violet.fieldBorder"), ((FlatBorder) field.getBorder()).getStyleableValue("borderColor"));
            Icon icon = (Icon) search.getClientProperty("JTextField.leadingIcon");
            for (Themes.Variant variant : new Themes.Variant[]{Themes.Variant.DARK, Themes.Variant.LIGHT, Themes.Variant.DARK})
                for (boolean contrast : new boolean[]{false, true, false}) {
                    Themes.install(new Themes.Choice(variant, contrast));
                    SwingUtilities.updateComponentTreeUI(root);
                    Color edge = UIManager.getColor("Violet.fieldBorder");
                    if (!contrast) assertNotEquals(UIManager.getColor("Component.borderColor"), edge);
                    for (JComponent field : fields) {
                        assertTrue(field.getBorder() instanceof UIResource);
                        assertTrue(field.getBorder() instanceof FlatBorder);
                        assertEquals(edge, ((FlatBorder) field.getBorder()).getStyleableValue("borderColor"));
                    }
                    assertSame(icon, search.getClientProperty("JTextField.leadingIcon"));
                    BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics();
                    try { icon.paintIcon(search, g, 0, 0); } finally { g.dispose(); }
                    assertTrue("Search icon uses the current accent", contains(image, UIManager.getColor("SearchField.searchIconColor")));
                }
            search.putClientProperty("FlatLaf.style", "arc: 12");
            assertEquals(12, ((FlatBorder) search.getBorder()).getStyleableValue("arc"));
            assertEquals(8, ((FlatBorder) fields[1].getBorder()).getStyleableValue("arc"));
            final int[] changes = {0};
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { changes[0]++; }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { changes[0]++; }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { changes[0]++; }
            });
            AbstractButton clear = null;
            for (Component child : search.getComponents())
                if (child instanceof AbstractButton && "TextField.clearButton".equals(child.getName())) clear = (AbstractButton) child;
            assertNotNull("FlatLaf installs the clear button", clear);
            clear.doClick();
            assertEquals("", search.getText()); assertTrue(changes[0] > 0);
            JTextArea area = new JTextArea(); TextSearchBar chat = new TextSearchBar(() -> area, area);
            JTextField query = (JTextField) ((BorderLayout) chat.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            assertTrue(query.getClientProperty("JTextField.leadingIcon") instanceof FlatSearchIcon);
            assertEquals(true, query.getClientProperty("JTextField.showClearButton"));
        });
    }

    @Test public void tabFontSurvivesStartupRefreshScalingAndThemeSwitches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane tabs = null;
            for (int size : new int[]{13, 20, 26, 13}) for (Themes.Variant variant : Themes.Variant.values()) {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
                Themes.install(new Themes.Choice(variant, false));
                ContentStyle.applyFontDefaults();
                assertEquals(size * 1.08f, UIManager.getFont("TabbedPane.font").getSize2D(), .01f);
                assertEquals(size * 1.08f, new JTabbedPane().getFont().getSize2D(), .01f);
                if (tabs == null) { tabs = new JTabbedPane(); tabs.addTab("Explore", new JLabel("Body")); }
                SwingUtilities.updateComponentTreeUI(tabs);
                ContentStyle.refreshFonts(tabs); ContentStyle.refreshFonts(tabs);
                assertEquals(size * 1.08f, tabs.getFont().getSize2D(), .01f);
                assertTrue(tabs.getFont() instanceof UIResource);
                assertEquals(size, tabs.getComponentAt(0).getFont().getSize());
            }
        });
    }

    @Test public void tabDefaultsPaintAnInsetPillAndRoundedAccent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Explore", new JPanel());
                FocusTabs ui = new FocusTabs(); tabs.setUI(ui);
                tabs.setSize(240, 100); tabs.doLayout();
                BufferedImage image = new BufferedImage(240, 100, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try { tabs.paint(g); } finally { g.dispose(); }
                assertTrue(tabs.getUI() instanceof FlatTabbedPaneUI);
                assertTrue(contains(image, UIManager.getColor("TabbedPane.selectedBackground")));
                assertTrue(contains(image, UIManager.getColor("TabbedPane.inactiveUnderlineColor")));
                Rectangle bounds = tabs.getBoundsAt(0);
                assertEquals("Pill is inset from its bounds", tabs.getBackground().getRGB(), image.getRGB(bounds.x, bounds.y + bounds.height / 2));
                ui.hover(0); ui.focused = true;
                g = image.createGraphics();
                try { tabs.paint(g); } finally { g.dispose(); }
                assertTrue("Focused underline remains visible under hover", contains(image, UIManager.getColor("TabbedPane.underlineColor")));
                assertTrue(contains(image, UIManager.getColor("TabbedPane.hoverColor")));
            }
        });
    }

    private static final class FocusTabs extends FlatTabbedPaneUI {
        boolean focused;
        void hover(int index) { setRolloverTab(index); }
        @Override protected boolean isTabbedPaneOrChildFocused() { return focused; }
    }

    private static boolean contains(BufferedImage image, Color color) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if (image.getRGB(x, y) == color.getRGB()) return true;
        return false;
    }

    private static double ratio(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
    }

    private static double luminance(Color color) {
        double[] rgb = {color.getRed() / 255d, color.getGreen() / 255d, color.getBlue() / 255d};
        for (int i = 0; i < 3; i++) rgb[i] = rgb[i] <= .04045 ? rgb[i] / 12.92 : Math.pow((rgb[i] + .055) / 1.055, 2.4);
        return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2];
    }
}
