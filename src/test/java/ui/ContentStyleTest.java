package ui;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import static org.junit.Assert.*;

public class ContentStyleTest {
    private Font previousFont;
    private LookAndFeel previousLaf;
    private JFrame layoutFrame;

    @Before public void installTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, ContentStyle.FONT_SIZE));
            setLaf(new VioletTheme());
        });
    }

    @After public void restoreTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (layoutFrame != null) layoutFrame.dispose();
            ContentStyle.setBodyFont(previousFont); setLaf(previousLaf);
        });
    }

    @Test public void refreshKeepsRolesAcrossRepeatedFontAndThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel();
            JLabel body = new JLabel("Body"), heading = new JLabel("Heading"), metadata = new JLabel("Metadata");
            heading.setFont(new Font(ContentStyle.FONT_FAMILY, Font.BOLD, 21));
            metadata.setFont(ContentStyle.metadata(ContentStyle.body()));
            JTextArea report = new JTextArea("aligned report"); report.setFont(ContentStyle.report(ContentStyle.body()));
            String reportFamily = report.getFont().getFamily();
            JPanel hidden = new JPanel(); hidden.setVisible(false);
            JTextField search = new JTextField("Search"); hidden.add(search);
            JMenu menu = new JMenu("Menu"); JMenuItem item = new JMenuItem("Destination"); menu.add(item);
            JButton popupButton = new JButton("Popup"); JPopupMenu popup = new JPopupMenu();
            JMenuItem popupItem = new JMenuItem("Popup destination"); popup.add(popupItem); popupButton.setComponentPopupMenu(popup);
            JTable table = new JTable(2, 2); ContentStyle.table(table);
            JList<String> list = new JList<>(new String[] {"One"}); list.setFixedCellHeight(24);
            JTree tree = new JTree(); tree.setRowHeight(20);
            CellRendererPane rendererPane = new CellRendererPane();
            JLabel rendererChild = new JLabel("Renderer internals");
            Font rendererFont = new Font(Font.MONOSPACED, Font.PLAIN, 11);
            rendererChild.setFont(rendererFont); rendererPane.add(rendererChild);
            for (Component c : new Component[] {body, heading, metadata, report, hidden, menu, popupButton, table, list, tree, rendererPane}) root.add(c);
            ContentStyle.refreshFonts(root);
            int initialRowHeight = table.getRowHeight();

            ContentStyle.setBodyFont(new Font(Font.SERIF, Font.ITALIC, 24));
            ContentStyle.applyFontDefaults(); ContentStyle.refreshFonts(root);
            assertEquals(24, body.getFont().getSize()); assertEquals(Font.SERIF, body.getFont().getFamily());
            assertEquals(21f * 24 / ContentStyle.FONT_SIZE, heading.getFont().getSize2D(), .01f); assertTrue(heading.getFont().isBold());
            assertTrue(heading.getFont().isItalic());
            assertEquals(24f * 12 / 13, metadata.getFont().getSize2D(), .01f);
            assertEquals(reportFamily, report.getFont().getFamily()); assertEquals(24, report.getFont().getSize());
            assertEquals(24, search.getFont().getSize()); assertEquals(24, item.getFont().getSize());
            assertEquals(24, popupItem.getFont().getSize());
            assertSame("Renderer children must not be restyled", rendererFont, rendererChild.getFont());
            assertTrue(table.getRowHeight() >= table.getFontMetrics(table.getFont()).getHeight() + 8);
            assertTrue(table.getTableHeader().getPreferredSize().height >= table.getTableHeader().getFontMetrics(table.getTableHeader().getFont()).getHeight() + 8);
            assertTrue(list.getFixedCellHeight() >= list.getFontMetrics(list.getFont()).getHeight() + 6);
            assertTrue(tree.getRowHeight() >= tree.getFontMetrics(tree.getFont()).getHeight() + 4);
            int largeRowHeight = table.getRowHeight();

            setLaf(new FlatLightLaf()); ContentStyle.applyFontDefaults();
            SwingUtilities.updateComponentTreeUI(root);
            ContentStyle.refreshFonts(root); ContentStyle.refreshFonts(root);
            assertEquals(21f * 24 / ContentStyle.FONT_SIZE, heading.getFont().getSize2D(), .01f);
            assertEquals(largeRowHeight, table.getRowHeight());
            assertEquals(Font.SERIF, new JLabel("New control").getFont().getFamily());
            assertEquals(24, new JLabel("New control").getFont().getSize());

            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, ContentStyle.FONT_SIZE));
            ContentStyle.refreshFonts(root);
            assertEquals(21f, heading.getFont().getSize2D(), .01f);
            assertFalse(heading.getFont().isItalic()); assertTrue(heading.getFont().isBold());
            assertEquals(12f, metadata.getFont().getSize2D(), .01f);
            assertEquals(reportFamily, report.getFont().getFamily());
            assertEquals("Rows must shrink again instead of retaining the previous zoom", initialRowHeight, table.getRowHeight());
        });
    }

    @Test public void constructionWithSavedFontDoesNotScaleHelperFontsTwice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.setBodyFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
            ContentStyle.applyFontDefaults();
            JPanel root = new JPanel(); JLabel body = new JLabel("Body");
            JLabel metadata = new JLabel("Metadata"); metadata.setFont(ContentStyle.metadata(ContentStyle.body()));
            JLabel heading = new JLabel("Heading"); heading.setFont(ContentStyle.emphasis(ContentStyle.body()).deriveFont(Font.BOLD, 36f));
            Font plainFontArgument = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
            JTextArea report = new JTextArea(); report.setFont(ContentStyle.report(plainFontArgument));
            JTable table = new JTable(1, 1); ContentStyle.tableFont(table, plainFontArgument, 36);
            root.add(body); root.add(metadata); root.add(heading); root.add(report); root.add(table);
            ContentStyle.refreshFonts(root); ContentStyle.refreshFonts(root);
            assertEquals(24, body.getFont().getSize());
            assertEquals(24f * 12 / 13, metadata.getFont().getSize2D(), .01f);
            assertEquals(36f, heading.getFont().getSize2D(), .01f);
            assertEquals(24, report.getFont().getSize()); assertEquals(24, table.getFont().getSize());
            ContentStyle.setBodyFont(new Font(Font.SERIF, Font.PLAIN, 18));
            ContentStyle.refreshFonts(root);
            assertEquals("A global monospace choice must not pin normal controls to monospace", Font.SERIF, body.getFont().getFamily());
            assertFalse(body.getFont().isBold());
            assertTrue("Heading emphasis survives removing global bold", heading.getFont().isBold());
        });
    }

    @Test public void denseTablesStayReadableAndFocusBorderSurvivesCellPadding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(1, 1); ContentStyle.table(table);
            int comfortable = table.getRowHeight();
            ContentStyle.tableDensity(table, ContentStyle.Density.DENSE);
            assertTrue(table.getRowHeight() < comfortable);
            ContentStyle.tableFont(table, ContentStyle.body().deriveFont(32f), 28);
            assertTrue(table.getRowHeight() >= table.getFontMetrics(table.getFont()).getHeight() + 4);
            Border focus = BorderFactory.createLineBorder(Color.MAGENTA, 2);
            UIManager.getLookAndFeelDefaults().put("Table.focusCellHighlightBorder", focus);
            ContentStyle.Cell cell = new ContentStyle.Cell();
            cell.getTableCellRendererComponent(table, "Focused", false, true, 0, 0);
            assertTrue(cell.getBorder() instanceof CompoundBorder);
            assertSame(focus, ((CompoundBorder) cell.getBorder()).getOutsideBorder());
            assertTrue(cell.getInsets().left >= 8);
            cell.getTableCellRendererComponent(table, "Selected", true, false, 0, 0);
            assertEquals(table.getSelectionForeground(), cell.getForeground());
            assertEquals(table.getSelectionBackground(), cell.getBackground());
        });
    }

    @Test public void explicitFontSetterReplacesCachedBodyReportAndMetadataRoles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(); JTextArea area = new JTextArea("Legacy report"); root.add(area);
            ContentStyle.refreshFonts(root); // Initially cached as ordinary body text.
            Font report = ContentStyle.report(ContentStyle.body());
            ContentStyle.font(area, report);
            ContentStyle.refreshFonts(root);
            assertEquals(report.getFamily(), area.getFont().getFamily());

            ContentStyle.setBodyFont(new Font(Font.SERIF, Font.PLAIN, 24));
            setLaf(new FlatLightLaf()); ContentStyle.applyFontDefaults();
            SwingUtilities.updateComponentTreeUI(root);
            ContentStyle.refreshFonts(root); ContentStyle.refreshFonts(root);
            assertEquals("An intentional report role survives later theme/font refreshes", report.getFamily(), area.getFont().getFamily());
            assertEquals(24, area.getFont().getSize());

            ContentStyle.font(area, ContentStyle.metadata(ContentStyle.body()));
            ContentStyle.refreshFonts(root);
            assertEquals(Font.SERIF, area.getFont().getFamily());
            assertEquals(24f * 12 / 13, area.getFont().getSize2D(), .01f);
            ContentStyle.setBodyFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            ContentStyle.refreshFonts(root);
            assertEquals(Font.SANS_SERIF, area.getFont().getFamily());
            assertEquals(18f * 12 / 13, area.getFont().getSize2D(), .01f);

            ContentStyle.setBodyFont(new Font(Font.MONOSPACED, Font.PLAIN, 24));
            ContentStyle.font(area, new Font(Font.MONOSPACED, Font.PLAIN, 24));
            ContentStyle.refreshFonts(root);
            Font fixedWidth = area.getFont();
            ContentStyle.font(area, ContentStyle.body());
            assertEquals("Equal fonts can still have different semantic roles", fixedWidth, area.getFont());
            ContentStyle.setBodyFont(new Font(Font.SERIF, Font.PLAIN, 18));
            ContentStyle.refreshFonts(root);
            assertEquals("Resetting to body must clear the old fixed-family role", Font.SERIF, area.getFont().getFamily());
            assertEquals(18, area.getFont().getSize());
        });
    }

    @Test public void nestedBorderLayoutHeaderRecomputesGridHeightAfterWindowResize() throws Exception {
        JPanel[] grids = new JPanel[2];
        JPanel[] header = new JPanel[1];
        int[] layouts = {0};
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(new BorderLayout()) {
                @Override public void doLayout() { layouts[0]++; super.doLayout(); }
            };
            JPanel sidebar = new JPanel(); sidebar.setPreferredSize(new Dimension(210, 0)); root.add(sidebar, BorderLayout.WEST);
            JPanel content = new JPanel(new BorderLayout()); content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            JPanel page = new JPanel(new BorderLayout(0, 12)); page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            header[0] = new JPanel(new BorderLayout(0, 12));
            header[0].add(new JLabel("Current character"), BorderLayout.NORTH);
            grids[0] = ContentStyle.responsiveGrid(4, 155, 10);
            grids[1] = ContentStyle.responsiveGrid(4, 155, 10);
            for (int i = 0; i < 4; i++) {
                JPanel card = new JPanel(new BorderLayout(0, 6));
                card.setBorder(BorderFactory.createEmptyBorder(13, 13, 13, 13));
                card.add(new JLabel("Metric " + i), BorderLayout.NORTH);
                JLabel value = new JLabel("123"); value.setFont(ContentStyle.body().deriveFont(Font.BOLD, 21f));
                card.add(value, BorderLayout.CENTER); grids[0].add(card);
                JPanel equipment = new JPanel(new BorderLayout(0, 3));
                equipment.add(new JLabel("Slot " + i), BorderLayout.NORTH);
                equipment.add(new JLabel("Awaiting capture"), BorderLayout.CENTER); grids[1].add(equipment);
            }
            // Both grids are measured through an enclosing header, matching real page composition.
            header[0].add(grids[0], BorderLayout.CENTER); header[0].add(grids[1], BorderLayout.SOUTH);
            page.add(header[0], BorderLayout.NORTH); page.add(new JScrollPane(new JTable(3, 2)), BorderLayout.CENTER);
            content.add(page, BorderLayout.CENTER); root.add(content, BorderLayout.CENTER);
            layoutFrame = new JFrame(); layoutFrame.setContentPane(root);
            layoutFrame.setSize(1080, 720); layoutFrame.setVisible(true);
        });
        int[] widths = {1080, 800, 680, 500, 1080};
        int[] columns = {4, 2, 2, 1, 4};
        for (int i = 0; i < widths.length; i++) {
            final int width = widths[i], expectedColumns = columns[i];
            SwingUtilities.invokeAndWait(() -> { layoutFrame.setSize(width, 720); layoutFrame.validate(); });
            SwingUtilities.invokeAndWait(() -> layoutFrame.validate());
            SwingUtilities.invokeAndWait(() -> layoutFrame.validate());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Header must reserve its children's new height at " + width,
                    header[0].getPreferredSize().height, header[0].getHeight());
                for (JPanel grid : grids) {
                    assertEquals("Grid height must be recomputed by its parent at " + width,
                        grid.getPreferredSize().height, grid.getHeight());
                    int firstRow = 0;
                    for (Component card : grid.getComponents()) {
                        if (card.getY() == grid.getComponent(0).getY()) firstRow++;
                        assertTrue("A card must not be compressed below its content at " + width,
                            card.getHeight() >= card.getPreferredSize().height);
                    }
                    assertEquals("Balanced columns at " + width, expectedColumns, firstRow);
                }
            });
        }
        int[] settled = {0};
        SwingUtilities.invokeAndWait(() -> settled[0] = layouts[0]);
        for (int i = 0; i < 4; i++) SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> assertEquals("Stable widths must not trigger a validation loop", settled[0], layouts[0]));
    }

    @Test public void controlsWrapAndGridDropsColumnsWithoutLosingChildren() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel controls = ContentStyle.controls();
            for (int i = 0; i < 4; i++) {
                JButton button = new JButton("Control " + i); button.setPreferredSize(new Dimension(110, 30)); controls.add(button);
            }
            controls.setSize(520, 200); int wideHeight = controls.getPreferredSize().height;
            controls.setSize(250, 200); int narrowHeight = controls.getPreferredSize().height;
            assertTrue(narrowHeight > wideHeight);
            controls.setSize(250, narrowHeight); controls.doLayout();
            for (Component child : controls.getComponents()) assertTrue(controls.getBounds().contains(child.getBounds()));

            JPanel grid = ContentStyle.responsiveGrid(3, 160, 10);
            for (int i = 0; i < 5; i++) {
                JLabel label = new JLabel("Metric " + i); label.setPreferredSize(new Dimension(160, 40)); grid.add(label);
            }
            grid.setSize(520, 300); int threeColumnHeight = grid.getPreferredSize().height;
            grid.setSize(350, 300); int twoColumnHeight = grid.getPreferredSize().height;
            assertTrue(twoColumnHeight > threeColumnHeight);
            grid.setSize(350, twoColumnHeight); grid.doLayout();
            assertEquals(grid.getComponent(0).getY(), grid.getComponent(1).getY());
            assertTrue(grid.getComponent(2).getY() > grid.getComponent(0).getY());
            for (Component child : grid.getComponents()) assertTrue(grid.getBounds().contains(child.getBounds()));
            grid.setSize(180, 300); assertTrue(grid.getPreferredSize().height > twoColumnHeight);
            grid.getComponent(4).setVisible(false); assertEquals(190, grid.getPreferredSize().height);
            grid.setSize(350, 200); grid.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT); grid.doLayout();
            assertTrue(grid.getComponent(0).getX() > grid.getComponent(1).getX());
        });
    }

    @Test public void captureColorsHaveAccessibleWhiteTextInEveryInteractiveState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String state : new String[] {"background", "hoverBackground", "focusedBackground", "pressedBackground"}) {
                Color color = UIManager.getColor("Button.default." + state);
                assertNotNull(state, color);
                assertTrue(state + " contrast was " + contrast(Color.WHITE, color), contrast(Color.WHITE, color) >= 4.5);
            }
            assertTrue(contrast(Color.WHITE, VioletTheme.CAPTURE_BACKGROUND) >= 4.5);
            assertTrue(contrast(Color.WHITE, VioletTheme.CAPTURE_HOVER) >= 4.5);
        });
    }

    private static double contrast(Color a, Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }
    private static double linear(int value) {
        double channel = value / 255.0;
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }
    private static void setLaf(LookAndFeel laf) {
        try { UIManager.setLookAndFeel(laf); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }
}
