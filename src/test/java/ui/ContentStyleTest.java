package ui;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test public void wrappingTextMeasuresLongTokensNewlinesAndUnicodeAtTheAllocatedWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = ContentStyle.wrappingText("", 2);
            JPanel parent = new JPanel(new BorderLayout()); parent.setSize(900, 600); parent.add(area);
            area.setBorder(BorderFactory.createEmptyBorder(4, 7, 6, 11));
            String filename = String.join("", java.util.Collections.nCopies(180, "W")) + ".wav";
            for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf()}) {
                setLaf(laf); SwingUtilities.updateComponentTreeUI(parent);
                for (int size : new int[] {13, 16, 24, 13}) {
                    ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
                    ContentStyle.refreshFonts(parent);
                    assertEquals(size * 12f / 13, area.getFont().getSize2D(), .01f);
                    for (String text : new String[] {"Saved short.wav", "Saved " + filename,
                            "Partial: identity unavailable.\n\nCould not open " + filename + "\nこんにちは e\u0301 ★ 😀\n"}) {
                        area.setText(text);
                        for (int width : new int[] {590, 230, 460, 590}) {
                            // The parent is deliberately wider: only the text's actual allocation is relevant.
                            area.setSize(width, 1);
                            Dimension preferred = area.getPreferredSize();
                            // Swing retains the document's bidi/complex-text mode after Unicode is removed.
                            // Compare independent views in the same mode, not a new plain-only document.
                            PlainDocument document = new PlainDocument();
                            document.putProperty("i18n", area.getDocument().getProperty("i18n"));
                            JTextArea reference = new JTextArea(document, text, 0, 0);
                            reference.setFont(area.getFont()); reference.setBorder(area.getBorder());
                            reference.setLineWrap(true); reference.setWrapStyleWord(true);
                            reference.setSize(width, 100000);
                            assertEquals("Equivalent Swing text-view modes", area.getUI().getRootView(area).getView(0).getClass(),
                                reference.getUI().getRootView(reference).getView(0).getClass());
                            int required = textViewHeight(reference);
                            int floor = area.getFontMetrics(area.getFont()).getHeight() * 2 + area.getInsets().top + area.getInsets().bottom;
                            assertEquals("Actual Swing view: font=" + size + ", width=" + width, Math.max(floor, required), preferred.height);
                            area.setSize(width, preferred.height);
                            try {
                                Rectangle end = area.modelToView(area.getDocument().getLength());
                                assertNotNull(end);
                                assertTrue("Final text line must fit", end.y + end.height <= area.getHeight() - area.getInsets().bottom);
                            } catch (BadLocationException e) { throw new AssertionError(e); }
                        }
                    }
                }
            }
            assertFalse(area.isEditable()); assertFalse(area.isOpaque());
            assertTrue(area.getLineWrap()); assertTrue(area.getWrapStyleWord());
        });
    }

    @Test public void wrappingTextRelayoutSettlesAfterWidthFontAndDocumentChanges() throws Exception {
        JTextArea[] text = new JTextArea[1]; JPanel[] header = new JPanel[1]; int[] layouts = {0};
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(new BorderLayout()) {
                @Override public void doLayout() { layouts[0]++; super.doLayout(); }
            };
            header[0] = new JPanel(new BorderLayout());
            header[0].setBorder(BorderFactory.createEmptyBorder(7, 23, 11, 31));
            text[0] = ContentStyle.wrappingText("Saved " + String.join("", java.util.Collections.nCopies(184, "W")) + ".wav", 2);
            header[0].add(text[0]); root.add(header[0], BorderLayout.NORTH); root.add(new JPanel());
            layoutFrame = new JFrame(); layoutFrame.setContentPane(root); layoutFrame.setSize(820, 600); layoutFrame.setVisible(true);
        });
        for (int width : new int[] {820, 460, 660, 460, 820}) {
            SwingUtilities.invokeAndWait(() -> {
                layoutFrame.setSize(width, 600);
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, width == 460 ? 24 : 13));
                ContentStyle.refreshFonts(layoutFrame);
                text[0].append("\nPartial: unavailable identity. 東京");
                layoutFrame.validate();
            });
            for (int turn = 0; turn < 8; turn++) SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(text[0].getPreferredSize().height, text[0].getHeight());
                assertEquals(header[0].getPreferredSize().height, header[0].getHeight());
                assertTrue(text[0].getHeight() >= textViewHeight(text[0]));
            });
        }
        int[] settled = {0}; SwingUtilities.invokeAndWait(() -> settled[0] = layouts[0]);
        for (int turn = 0; turn < 8; turn++) SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> assertEquals("No feedback loop after the width settles", settled[0], layouts[0]));
    }

    @Test public void wrappingTextReservesTheUiCaretMarginAtExactWrapBoundaries() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] texts = {"Detect advertisements with links in PM and World messages.",
                "Partial: no full ignore list captured. Remote whisper identities may be unavailable.\nFailed to read "
                    + String.join("", java.util.Collections.nCopies(184, "W")) + ".txt\n0 existing spam rules loaded. Lists below use one entry per line."};
            for (int font : new int[] {16, 24}) {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font)); ContentStyle.applyFontDefaults();
                for (int width : new int[] {414, 422}) for (int caret : new int[] {1, 4}) for (String text : texts) {
                    JTextArea area = ContentStyle.wrappingText(text);
                    area.putClientProperty("caretWidth", caret); area.setSize(width, 1);
                    Dimension preferred = area.getPreferredSize();
                    JTextArea reference = new JTextArea(text);
                    reference.setFont(area.getFont()); reference.setBorder(area.getBorder());
                    reference.putClientProperty("caretWidth", caret);
                    reference.setLineWrap(true); reference.setWrapStyleWord(true); reference.setSize(width, 100000);
                    assertEquals("Actual TextUI caret allocation: font=" + font + ", width=" + width + ", caret=" + caret,
                        reference.getUI().getPreferredSize(reference).height, preferred.height);
                    area.setSize(width, preferred.height);
                    try {
                        Rectangle endpoint = area.modelToView(area.getDocument().getLength()); assertNotNull(endpoint);
                        assertTrue("Complete endpoint fits the measured allocation", new Rectangle(0, 0, width, preferred.height).contains(endpoint));
                    } catch (BadLocationException e) { throw new AssertionError(e); }
                }
            }
        });
    }

    @Test public void unfocusedMetadataUpdatesPreserveViewportButKeyboardCaretNavigationStillScrolls() throws Exception {
        JTextArea[] footer = new JTextArea[1]; JScrollPane[] page = new JScrollPane[1]; JButton[] header = new JButton[1];
        SwingUtilities.invokeAndWait(() -> {
            header[0] = new JButton("Keep reading these controls");
            JPanel body = new JPanel(); body.setMinimumSize(new Dimension(0, 650));
            footer[0] = ContentStyle.wrappingText("Initial metadata\nSecond line", 2);
            page[0] = ContentStyle.page(header[0], body, footer[0]);
            layoutFrame = new JFrame(); layoutFrame.setContentPane(page[0]); layoutFrame.setSize(520, 420); layoutFrame.setVisible(true);
        });
        for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new VioletTheme()}) {
            SwingUtilities.invokeAndWait(() -> {
                setLaf(laf); ContentStyle.applyFontDefaults(); SwingUtilities.updateComponentTreeUI(layoutFrame); ContentStyle.refreshFonts(layoutFrame);
            });
            focus(header[0]);
            settleMetadataLayout();
            for (int position : new int[] {0, 90}) {
                SwingUtilities.invokeAndWait(() -> {
                    page[0].getViewport().setViewPosition(new Point(0, position));
                    assertFalse("Metadata must not own focus", footer[0].isFocusOwner());
                    footer[0].setText("Updated metadata\nAnother line");
                    footer[0].append("\nUnavailable identity: 東京 ★\nBackground capture update");
                });
                settleMetadataLayout();
                SwingUtilities.invokeAndWait(() -> assertEquals("Background document edits must preserve the reading position after " + laf.getName(),
                    new Point(0, position), page[0].getViewport().getViewPosition()));
            }
            focus(footer[0]);
            SwingUtilities.invokeAndWait(() -> {
                // Put the focused text offscreen again to distinguish explicit navigation from focus acquisition.
                footer[0].setCaretPosition(0);
            });
            settleMetadataLayout();
            SwingUtilities.invokeAndWait(() -> {
                page[0].getViewport().setViewPosition(new Point());
                Object key = footer[0].getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ctrl END"));
                assertNotNull("Ctrl+End remains bound", key);
                Action action = footer[0].getActionMap().get(key); assertNotNull(action);
                action.actionPerformed(new ActionEvent(footer[0], ActionEvent.ACTION_PERFORMED, key.toString()));
            });
            settleMetadataLayout();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(footer[0].getDocument().getLength(), footer[0].getCaretPosition());
                assertTrue("Explicit keyboard caret movement still reveals metadata", page[0].getViewport().getViewPosition().y > 0);
                try {
                    Rectangle endpoint = footer[0].modelToView(footer[0].getDocument().getLength()); assertNotNull(endpoint);
                    assertTrue("The complete keyboard endpoint is visible", footer[0].getVisibleRect().contains(endpoint));
                } catch (BadLocationException e) { throw new AssertionError(e); }
            });
        }
    }

    private void settleMetadataLayout() throws Exception {
        for (int turn = 0; turn < 12; turn++) SwingUtilities.invokeAndWait(() -> layoutFrame.validate());
    }

    private static void focus(JComponent component) throws Exception {
        CountDownLatch focused = new CountDownLatch(1);
        FocusAdapter listener = new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { focused.countDown(); }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                component.addFocusListener(listener); component.requestFocusInWindow();
                if (component.isFocusOwner()) focused.countDown();
            });
            assertTrue("Native component must receive keyboard focus", focused.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertTrue(component.isFocusOwner()));
        } finally { SwingUtilities.invokeAndWait(() -> component.removeFocusListener(listener)); }
    }

    @Test public void rowHoverFollowsThePointerYieldsToSelectionAndClearsOnExit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new Object[][] {{"a"}, {"b"}, {"c"}}, new Object[] {"Column"});
            ContentStyle.table(table);
            table.setSize(200, table.getRowHeight() * 3);
            // Rows alternate, so each row is compared against its own resting color.
            Color restingOne = renderedBackground(table, 1), restingTwo = renderedBackground(table, 2);
            Color hovered = ContentStyle.color("hover");
            assertNotEquals("Hover must be visible against a striped row", restingOne, hovered);
            assertNotEquals("Hover must be visible against a plain row", restingTwo, hovered);

            move(table, 1);
            assertEquals("The row under the pointer highlights", hovered, renderedBackground(table, 1));
            assertNotEquals("Neighbouring rows stay at rest", hovered, renderedBackground(table, 0));

            move(table, 2);
            assertEquals(hovered, renderedBackground(table, 2));
            assertEquals("Leaving a row restores it", restingOne, renderedBackground(table, 1));

            // Selection outranks hover, so the selected row keeps its own color under the pointer.
            table.setRowSelectionInterval(2, 2);
            assertEquals(table.getSelectionBackground(), renderedBackground(table, 2));
            table.clearSelection();

            table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, -1, -1, 0, false));
            assertEquals("Leaving the table clears the highlight", restingTwo, renderedBackground(table, 2));
        });
    }

    @Test public void scrollingDropsAHoverThePointerNoLongerBacks() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new Object[][] {{"a"}, {"b"}, {"c"}, {"d"}}, new Object[] {"Column"});
            ContentStyle.table(table);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setSize(200, table.getRowHeight() * 2);
            table.setSize(200, table.getRowHeight() * 4);
            scroll.doLayout();
            Color resting = renderedBackground(table, 1);

            // The first pointer event also subscribes to the viewport.
            move(table, 1);
            assertEquals(ContentStyle.color("hover"), renderedBackground(table, 1));

            // Scrolling sends no mouse event. Offscreen the pointer cannot be resolved, so rather
            // than leaving the highlight on a row the pointer has left, it is dropped.
            scroll.getViewport().setViewPosition(new Point(0, table.getRowHeight()));
            assertEquals("A scroll must not strand the highlight", resting, renderedBackground(table, 1));
        });
    }

    @Test public void repeatedStylingKeepsASingleHoverTracker() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new Object[][] {{"a"}}, new Object[] {"Column"});
            ContentStyle.table(table);
            int listeners = table.getMouseMotionListeners().length;
            ContentStyle.table(table);
            ContentStyle.rowHover(table);
            assertEquals("Re-styling a table must not stack pointer listeners",
                listeners, table.getMouseMotionListeners().length);
        });
    }

    @Test public void surfaceRolesSeparateElevationFromTheRestingBackground() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf()}) {
                setLaf(laf);
                String name = laf.getName();
                Color surface = ContentStyle.color("surface"), raised = ContentStyle.color("surfaceRaised");
                Color background = ContentStyle.color("background"), hover = ContentStyle.color("hover");
                for (String role : new String[] {"surfaceRaised", "controlBorder", "hover", "accentWash"})
                    assertNotNull(name + ": " + role, ContentStyle.color(role));
                assertNotEquals(name + ": a raised surface must read above the base surface", surface, raised);
                assertNotEquals(name + ": hover must differ from the resting background", background, hover);
                // Text stays legible on every surface the new roles introduce.
                for (Color under : new Color[] {raised, hover, ContentStyle.color("accentWash")})
                    assertTrue(name + ": text contrast on " + under + " was " + contrast(ContentStyle.color("text"), under),
                        contrast(ContentStyle.color("text"), under) >= 4.5);
            }
        });
    }

    @Test public void themeDividersStayQuieterThanInteractiveOutlines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            setLaf(new VioletTheme());
            Color surface = ContentStyle.color("surface");
            double divider = contrast(ContentStyle.color("border"), surface);
            double control = contrast(ContentStyle.color("controlBorder"), surface);
            assertTrue("Structural dividers " + divider + " must stay quieter than control outlines " + control,
                divider < control);
        });
    }

    @Test public void cardPaintsARoundedSurfaceInsteadOfASquareBorder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel card = ContentStyle.card(new BorderLayout());
            Color fill = new Color(0x203040);
            card.setBackground(fill);
            card.setSize(60, 40);
            BufferedImage image = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            card.paint(graphics);
            graphics.dispose();
            assertEquals("The corner is cut away, so the card reads as rounded", 0, image.getRGB(0, 0) >>> 24);
            assertEquals("The fill reaches the middle", fill.getRGB(), image.getRGB(30, 20));
        });
    }

    private static Color renderedBackground(JTable table, int row) {
        return table.prepareRenderer(table.getCellRenderer(row, 0), row, 0).getBackground();
    }

    private static void move(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, 0, true);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
            cell.x + 2, cell.y + cell.height / 2, 0, false));
    }

    private static int textViewHeight(JTextArea area) {
        // The UI reserves caret space in addition to component insets.
        return area.getUI().getPreferredSize(area).height;
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
