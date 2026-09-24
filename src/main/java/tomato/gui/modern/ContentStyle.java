package tomato.gui.modern;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.Document;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.View;
import javax.swing.tree.TreeCellRenderer;

/** Typography and semantic colors shared by the live content views. */
public final class ContentStyle {
    public static final String FONT_FAMILY = "Segoe UI";
    public static final int FONT_SIZE = 13;
    private static volatile Font contentFont = new Font(FONT_FAMILY, Font.PLAIN, FONT_SIZE);
    private static final String FONT_ROLE = "ContentStyle.fontRole";
    private static final String DENSITY = "ContentStyle.density";
    private static final String ROW_MINIMUM = "ContentStyle.rowMinimum";
    private static final String ROW_HEIGHT = "ContentStyle.rowHeight";
    private static final String HOVER_ROW = "ContentStyle.hoverRow";
    private static final String HOVER_INSTALLED = "ContentStyle.hoverInstalled";
    private static final String HOVER_VIEWPORT = "ContentStyle.hoverViewport";

    public enum Density {
        COMFORTABLE(28, 8), DENSE(24, 4);
        final int minimumHeight, padding;
        Density(int minimumHeight, int padding) { this.minimumHeight = minimumHeight; this.padding = padding; }
    }

    private ContentStyle() {}

    /** Sets the font used by newly built content. Refresh existing trees on the EDT with refreshFonts. */
    public static void setBodyFont(Font font) {
        Objects.requireNonNull(font, "font");
        if (font.getSize2D() <= 0) throw new IllegalArgumentException("Font size must be positive");
        contentFont = new Font(font.getAttributes());
    }

    /** LAF-local defaults also give subsequently created controls the chosen font. Call on the EDT. */
    public static void applyFontDefaults() {
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        FontUIResource body = new FontUIResource(body());
        for (Object key : defaults.keySet().toArray()) {
            if (!(key instanceof String)) continue;
            String name = (String) key;
            if (name.endsWith(".font")) defaults.put(key, body);
            else if (name.endsWith(".acceleratorFont")) defaults.put(key, new FontUIResource(metadata(body())));
        }
        defaults.put("defaultFont", body);
        defaults.put("TableHeader.font", new FontUIResource(emphasis(metadata(body()))));
        defaults.put("ToolTip.font", new FontUIResource(metadata(body())));
    }

    public static Font body() { return new RoleFont(contentFont, new FontRole(1f, Font.PLAIN, null)); }
    public static Font emphasis(Font base) {
        FontRole role = helperRole(base);
        return new RoleFont(base.deriveFont(base.getStyle() | Font.BOLD),
            new FontRole(role.scale, role.style | Font.BOLD, role.fixedFamily));
    }
    public static Font metadata(Font base) {
        FontRole role = helperRole(base);
        return new RoleFont(base.deriveFont(base.getSize2D() * 12f / 13f),
            new FontRole(role.scale * 12f / 13f, role.style, role.fixedFamily));
    }
    public static Font report(Font base) {
        Font font = new Font("Consolas", base.getStyle(), base.getSize());
        if ("Dialog".equals(font.getFamily())) font = new Font(Font.MONOSPACED, base.getStyle(), base.getSize());
        FontRole role = helperRole(base);
        return new RoleFont(font.deriveFont(base.getSize2D()), new FontRole(role.scale, role.style, font.getName()));
    }

    /**
     * Deliberately changes a component's font role, replacing any role cached by refreshFonts.
     * Helper fonts retain their metadata/report semantics; ordinary fonts use the current body size as their scale.
     */
    public static void font(JComponent component, Font font) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(font, "font");
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> font(component, font));
            return;
        }
        FontRole role = helperRole(font);
        component.putClientProperty(FONT_ROLE, role);
        component.setFont(new RoleFont(font, role));
    }

    /** Preserves semantic scale/style across font choices, including reports with a fixed-width family. */
    private static final class FontRole {
        final float scale;
        final int style;
        final String fixedFamily;
        FontRole(float scale, int style, String fixedFamily) {
            this.scale = scale; this.style = style; this.fixedFamily = fixedFamily;
        }
        Font resolve() {
            Font base = contentFont;
            return new RoleFont(new Font(fixedFamily == null ? base.getName() : fixedFamily,
                base.getStyle() | style, base.getSize()).deriveFont(Math.max(1f, base.getSize2D() * scale)), this);
        }
    }

    private static final class RoleFont extends Font {
        final FontRole role;
        RoleFont(Font font, FontRole role) { super(font.getAttributes()); this.role = role; }
        @Override public Font deriveFont(int style) {
            return new RoleFont(super.deriveFont(style), new FontRole(role.scale, style, role.fixedFamily));
        }
        @Override public Font deriveFont(float size) {
            return new RoleFont(super.deriveFont(size), new FontRole(role.scale * size / getSize2D(), role.style, role.fixedFamily));
        }
        @Override public Font deriveFont(int style, float size) {
            return new RoleFont(super.deriveFont(style, size),
                new FontRole(role.scale * size / getSize2D(), style, role.fixedFamily));
        }
    }

    private static FontRole roleOf(Font font) {
        if (font instanceof RoleFont) return ((RoleFont) font).role;
        String family = font.getFamily();
        boolean report = !(font instanceof UIResource) && (Font.MONOSPACED.equalsIgnoreCase(family)
            || Font.DIALOG_INPUT.equalsIgnoreCase(family) || "Consolas".equalsIgnoreCase(family) || "Courier New".equalsIgnoreCase(family));
        // LAF defaults are body text; explicit sizes describe headings and metadata in the design scale.
        float scale = font instanceof UIResource ? 1f : Math.max(11f, font.getSize2D()) / FONT_SIZE;
        return new FontRole(scale, font instanceof UIResource ? Font.PLAIN : font.getStyle(), report ? font.getName() : null);
    }

    private static FontRole helperRole(Font font) {
        if (font instanceof RoleFont) return ((RoleFont) font).role;
        FontRole role = roleOf(font);
        return new FontRole(font.getSize2D() / contentFont.getSize2D(), role.style, role.fixedFamily);
    }

    /** Updates real controls, including inactive cards and menu popups, without walking renderer internals. */
    public static void refreshFonts(Component root) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> refreshFonts(root));
            return;
        }
        refreshFontTree(root, Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>()));
        root.revalidate(); root.repaint();
    }

    private static void refreshFontTree(Component component, Set<Component> visited) {
        if (!visited.add(component)) return;
        if (component instanceof CellRendererPane || component instanceof TableCellRenderer
                || component instanceof ListCellRenderer || component instanceof TreeCellRenderer) return;
        if (component instanceof JTableHeader) {
            JTable table = ((JTableHeader) component).getTable();
            if (table != null) sizeHeader(table, density(table));
            return;
        }
        if (component instanceof JComponent && component.getFont() != null) {
            JComponent control = (JComponent) component;
            FontRole role = (FontRole) control.getClientProperty(FONT_ROLE);
            if (role == null) {
                role = roleOf(control.getFont());
                control.putClientProperty(FONT_ROLE, role);
            }
            Font font = role.resolve();
            if (!font.equals(control.getFont())) control.setFont(font);
            if (control instanceof JTable) {
                JTable table = (JTable) control;
                Object lastHeight = table.getClientProperty(ROW_HEIGHT);
                if (lastHeight == null || table.getRowHeight() != (Integer) lastHeight)
                    table.putClientProperty(ROW_MINIMUM, table.getRowHeight());
                sizeTable(table, density(table));
            } else if (control instanceof JList && ((JList<?>) control).getFixedCellHeight() > 0) {
                JList<?> list = (JList<?>) control;
                if (list.getClientProperty(ROW_MINIMUM) == null) list.putClientProperty(ROW_MINIMUM, list.getFixedCellHeight());
                list.setFixedCellHeight(Math.max((Integer) list.getClientProperty(ROW_MINIMUM), list.getFontMetrics(font).getHeight() + 6));
            } else if (control instanceof JTree && ((JTree) control).getRowHeight() > 0) {
                JTree tree = (JTree) control;
                if (tree.getClientProperty(ROW_MINIMUM) == null) tree.putClientProperty(ROW_MINIMUM, tree.getRowHeight());
                tree.setRowHeight(Math.max((Integer) tree.getClientProperty(ROW_MINIMUM), tree.getFontMetrics(font).getHeight() + 4));
            }
        }
        if (component instanceof JMenu) refreshFontTree(((JMenu) component).getPopupMenu(), visited);
        if (component instanceof JComponent) {
            JPopupMenu popup = ((JComponent) component).getComponentPopupMenu();
            if (popup != null) refreshFontTree(popup, visited);
        }
        if (component instanceof Container)
            for (Component child : ((Container) component).getComponents()) refreshFontTree(child, visited);
    }

    public static Color color(String role) {
        Color surface = UIManager.getColor("Table.background");
        boolean dark = surface == null || surface.getRed() * .2126 + surface.getGreen() * .7152 + surface.getBlue() * .0722 < 128;
        switch (role) {
            case "background": return themeColor("Panel.background", dark ? 0x131120 : 0xF5F4F9);
            case "surface": return themeColor("Table.background", dark ? 0x181627 : 0xFFFFFF);
            case "navigation": return themeColor("MenuBar.background", dark ? 0x0E0C18 : 0xEEECF4);
            case "border": return themeColor("Separator.foreground", dark ? 0x252236 : 0xE0DDE8);
            case "text": return themeColor("Label.foreground", dark ? 0xE9E6F7 : 0x24222E);
            case "selection": return themeColor("Table.selectionBackground", dark ? 0x3B2E5E : 0xE5DCF8);
            case "selectionText": return themeColor("Table.selectionForeground", dark ? 0xF4F0FF : 0x302048);
            // A raised surface for cards and inputs, one elevation step above "surface".
            case "surfaceRaised": {
                Color raised = themeColor("TextField.background", dark ? 0x201D33 : 0xFAF9FC);
                // Several light themes paint inputs and tables the same white. Step off the
                // surface there so cards and inputs still read as raised.
                return raised.equals(surface) ? elevate(raised, dark) : raised;
            }
            // The outline of an interactive control, deliberately stronger than "border".
            case "controlBorder": return themeColor("Component.borderColor", dark ? 0x38334F : 0xC9C5D6);
            // The pointer-over fill for rows and navigation items.
            case "hover": return new Color(dark ? 0x232038 : 0xEFEBF9);
            // A quiet violet wash for selected tabs and hovered accents.
            case "accentWash": return new Color(dark ? 0x2A2142 : 0xF0E9FD);
            case "violet": return new Color(dark ? 0xC4ADFF : 0x6241AA);
            case "blue": return new Color(dark ? 0x90C8F8 : 0x235E92);
            case "mint": return new Color(dark ? 0x86DBBA : 0x226D52);
            case "amber": return new Color(dark ? 0xEBC384 : 0x805510);
            case "rose": return new Color(dark ? 0xEEA6BA : 0x923553);
            default: return new Color(dark ? 0xAAA7BD : 0x626071);
        }
    }

    private static Color themeColor(String key, int fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? new Color(fallback) : new Color(color.getRGB(), true);
    }

    /** One elevation step away from a surface: lighter on dark themes, darker on light ones. */
    private static Color elevate(Color surface, boolean dark) {
        int step = dark ? 12 : -10;
        return new Color(channel(surface.getRed() + step), channel(surface.getGreen() + step),
            channel(surface.getBlue() + step));
    }

    private static int channel(int value) { return Math.max(0, Math.min(255, value)); }

    /** Short windows scroll the page instead of squeezing the data area out of view. */
    public static JScrollPane page(JComponent header, JComponent body, JComponent footer) {
        class Page extends JPanel implements Scrollable {
            Page() {
                super(new BorderLayout(0, 8));
                if (header != null) add(header, BorderLayout.NORTH);
                add(body, BorderLayout.CENTER);
                if (footer != null) add(footer, BorderLayout.SOUTH);
            }
            public Dimension getPreferredSize() {
                int height = body.getMinimumSize().height;
                if (header != null && header.isVisible()) height += header.getPreferredSize().height + 8;
                if (footer != null && footer.isVisible()) height += footer.getPreferredSize().height + 8;
                return new Dimension(800, height);
            }
            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() {
                return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
            }
            public int getScrollableUnitIncrement(Rectangle r, int axis, int direction) { return 32; }
            public int getScrollableBlockIncrement(Rectangle r, int axis, int direction) { return Math.max(32, r.height - 32); }
        }
        Page page = new Page();
        JScrollPane scroll = new JScrollPane(page) {
            @Override public Dimension getMinimumSize() {
                // Archive clients may themselves be pages inside a workspace page.
                // Preserve their content floor through the nested scroll pane so the
                // outer page scrolls instead of reducing the inner viewport to zero.
                Insets border = getInsets();
                return new Dimension(0, page.getPreferredSize().height + border.top + border.bottom);
            }
        };
        scroll.setBorder(null); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    /** Reveal a region through every enclosing viewport, including nested page/table scrolling. */
    public static void reveal(JComponent control, Rectangle region) {
        for (Container parent = control.getParent(); parent != null; parent = parent.getParent()) {
            if (!(parent instanceof JViewport)) continue;
            JComponent view = (JComponent)((JViewport)parent).getView();
            view.scrollRectToVisible(SwingUtilities.convertRectangle(control, region, view));
        }
    }

    public static JScrollPane tableScroll(JTable table, int minimumRows) {
        return new JScrollPane(table) {
            @Override public Dimension getMinimumSize() {
                Insets border = getInsets();
                int header = table.getTableHeader() == null ? 0 : table.getTableHeader().getPreferredSize().height;
                return new Dimension(0, Math.max(100, table.getRowHeight() * minimumRows) + header
                    + getHorizontalScrollBar().getPreferredSize().height + border.top + border.bottom);
            }
        };
    }

    /** Read-only metadata that wraps at its allocated width, including long tokens and explicit newlines. */
    public static JTextArea wrappingText(String text) { return wrappingText(text, 1); }

    /** minimumRows is a height floor, not a limit on the amount of visible text. Construct on the EDT. */
    public static JTextArea wrappingText(String text, int minimumRows) {
        if (minimumRows < 0) throw new IllegalArgumentException("minimumRows must not be negative");
        JTextArea area = new WrappingText(text, minimumRows);
        area.setEditable(false); area.setOpaque(false);
        area.setLineWrap(true); area.setWrapStyleWord(true);
        font(area, metadata(body()));
        return area;
    }

    private static final class WrappingText extends JTextArea {
        private boolean measuring;

        WrappingText(String text, int rows) {
            super(text, rows, 0);
            configureCaret();
            DocumentListener changes = new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { WidthRelayout.request(WrappingText.this, true); }
                public void removeUpdate(DocumentEvent e) { WidthRelayout.request(WrappingText.this, true); }
                public void changedUpdate(DocumentEvent e) { WidthRelayout.request(WrappingText.this, true); }
            };
            getDocument().addDocumentListener(changes);
            addPropertyChangeListener(e -> {
                String name = e.getPropertyName();
                if ("caret".equals(name)) configureCaret();
                if ("document".equals(name)) {
                    if (e.getOldValue() instanceof Document) ((Document) e.getOldValue()).removeDocumentListener(changes);
                    if (e.getNewValue() instanceof Document) ((Document) e.getNewValue()).addDocumentListener(changes);
                }
                if ("font".equals(name) || "UI".equals(name) || "border".equals(name) || "margin".equals(name)
                        || "document".equals(name) || "rows".equals(name) || "caretWidth".equals(name)) WidthRelayout.request(this, true);
            });
        }

        private void configureCaret() {
            // Background metadata publications must not scroll the page to this control. Explicit
            // keyboard caret movement still scrolls normally; theme changes may install a new caret.
            if (getCaret() instanceof DefaultCaret) ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }

        @Override public Dimension getPreferredSize() {
            Insets padding = getInsets();
            int height = getFontMetrics(getFont()).getHeight() * Math.max(1, getRows());
            int width = availableWidth(this) - padding.left - padding.right;
            if (width > 0 && getUI() != null) {
                measuring = true;
                try {
                    View view = getUI().getRootView(this);
                    if (getWidth() > 0 && getHeight() > 0) {
                        // Let the UI allocate its view, including the reserved caret margin. Using only
                        // component insets gives BasicTextUI one extra pixel and can hide an entire line.
                        getUI().modelToView(this, 0);
                    } else {
                        Object caret = getClientProperty("caretWidth");
                        if (!(caret instanceof Number)) caret = UIManager.get("Caret.width");
                        int caretWidth = caret instanceof Number && ((Number) caret).intValue() >= 0 ? ((Number) caret).intValue() : 1;
                        view.setSize(Math.max(1, width - caretWidth), Integer.MAX_VALUE);
                    }
                    height = Math.max(height, (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)));
                } catch (BadLocationException e) {
                    throw new IllegalStateException("Cannot measure the start of the text document", e);
                } finally { measuring = false; }
            }
            return new Dimension(0, height + padding.top + padding.bottom);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override public void revalidate() {
            // View.setSize can report a preference change while an ancestor BoxLayout is measuring us.
            // Its child-size arrays must survive that call; invalidate after the current layout instead.
            if (measuring) WidthRelayout.request(this, false);
            else super.revalidate();
        }
        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed) WidthRelayout.request(this, false);
        }
    }

    public static void table(JTable table) {
        table(table, Density.COMFORTABLE);
    }

    public static void table(JTable table, Density density) {
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new Cell());
        table.setDefaultRenderer(Number.class, new Cell());
        table.putClientProperty(DENSITY, density);
        tableFont(table, body(), density.minimumHeight);
        rowHover(table);
    }

    /**
     * Highlights the row under the pointer. Only a row change repaints, and it repaints the
     * two affected rows rather than the table, so pointer movement costs no more than a
     * selection change already does.
     */
    public static void rowHover(JTable table) {
        Objects.requireNonNull(table, "table");
        if (Boolean.TRUE.equals(table.getClientProperty(HOVER_INSTALLED))) return;
        table.putClientProperty(HOVER_INSTALLED, Boolean.TRUE);
        MouseAdapter tracker = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { track(table, e); }
            @Override public void mouseDragged(MouseEvent e) { track(table, e); }
            @Override public void mouseExited(MouseEvent e) { hover(table, -1); }
        };
        table.addMouseMotionListener(tracker);
        table.addMouseListener(tracker);
    }

    private static void track(JTable table, MouseEvent event) {
        watchScrolling(table);
        hover(table, table.rowAtPoint(event.getPoint()));
    }

    /**
     * Scrolling moves rows beneath a stationary pointer without sending a mouse event, which would
     * otherwise leave the highlight on the row the pointer has left. The viewport only exists once
     * the table is in a scroll pane, so this attaches on first use rather than at styling time.
     */
    private static void watchScrolling(JTable table) {
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, table);
        if (viewport == null || viewport == table.getClientProperty(HOVER_VIEWPORT)) return;
        viewport.addChangeListener(event -> resolveHover(table));
        table.putClientProperty(HOVER_VIEWPORT, viewport);
    }

    /** Re-reads the row under the pointer. An unresolvable pointer clears the highlight. */
    private static void resolveHover(JTable table) {
        if (!table.isShowing() || GraphicsEnvironment.isHeadless()) { hover(table, -1); return; }
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer == null) { hover(table, -1); return; }
        Point point = pointer.getLocation();
        SwingUtilities.convertPointFromScreen(point, table);
        hover(table, new Rectangle(table.getSize()).contains(point) ? table.rowAtPoint(point) : -1);
    }

    private static void hover(JTable table, int row) {
        Integer previous = (Integer) table.getClientProperty(HOVER_ROW);
        int was = previous == null ? -1 : previous;
        if (was == row) return;
        table.putClientProperty(HOVER_ROW, row < 0 ? null : row);
        repaintRow(table, was);
        repaintRow(table, row);
    }

    private static void repaintRow(JTable table, int row) {
        if (row < 0 || row >= table.getRowCount()) return;
        table.repaint(0, table.getCellRect(row, 0, true).y, table.getWidth(), table.getRowHeight(row));
    }

    /**
     * A rounded container for grouping content. It fills its shape and strokes one hairline
     * per paint, with no gradient or shadow, so it costs what the square border it replaces did.
     */
    public static JPanel card(LayoutManager layout) {
        return new RoundedPanel(layout);
    }

    /** A real button keeps summary explanations available to keyboard and assistive technology. */
    public static JButton detailsButton(String subject, Runnable open) {
        JButton button = new JButton("Details…");
        button.getAccessibleContext().setAccessibleName(subject + " details");
        button.addActionListener(e -> open.run());
        return button;
    }

    /** The caller supplies detached text, so an open explanation never follows a different record. */
    public static void showDetails(Component owner, String title, String text) {
        Window window = owner instanceof Window ? (Window) owner : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTextArea body = wrappingText(text, 12);
        body.setFocusable(true);
        body.getAccessibleContext().setAccessibleName(title);
        body.setCaretPosition(0);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane scroll = new JScrollPane(body);
        scroll.setPreferredSize(new Dimension(620, 360));
        content.add(scroll);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        content.add(close, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        refreshFonts(content);
        dialog.pack();
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(dialog.getWidth(), bounds.width), Math.min(dialog.getHeight(), bounds.height));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        body.requestFocusInWindow();
    }

    private static final class RoundedPanel extends JPanel {
        private static final int ARC = 10;
        /** Resolved once per look-and-feel rather than on every paint. */
        private Color outline;

        RoundedPanel(LayoutManager layout) { super(layout); setOpaque(false); }

        @Override public void updateUI() { outline = null; super.updateUI(); }

        @Override protected void paintComponent(Graphics graphics) {
            if (outline == null) outline = color("border");
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1, height = getHeight() - 1;
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, width, height, ARC, ARC);
            g.setColor(outline);
            g.drawRoundRect(0, 0, width, height, ARC, ARC);
            g.dispose();
        }
    }

    public static void tableFont(JTable table, Font font, int minimumHeight) {
        table.setFont(font);
        table.putClientProperty(FONT_ROLE, helperRole(font));
        table.putClientProperty(ROW_MINIMUM, minimumHeight);
        sizeTable(table, density(table));
    }

    public static void tableDensity(JTable table, Density density) {
        table.putClientProperty(DENSITY, Objects.requireNonNull(density, "density"));
        table.putClientProperty(ROW_MINIMUM, density.minimumHeight);
        sizeTable(table, density);
    }

    private static Density density(JTable table) {
        Density density = (Density) table.getClientProperty(DENSITY);
        if (density == null) {
            density = table.getRowHeight() <= 30 ? Density.DENSE : Density.COMFORTABLE;
            table.putClientProperty(DENSITY, density);
        }
        return density;
    }

    private static void sizeTable(JTable table, Density density) {
        Integer minimum = (Integer) table.getClientProperty(ROW_MINIMUM);
        int height = Math.max(Math.max(density.minimumHeight, minimum == null ? 0 : minimum),
            table.getFontMetrics(table.getFont()).getHeight() + density.padding);
        table.setRowHeight(height); table.putClientProperty(ROW_HEIGHT, height);
        sizeHeader(table, density);
    }

    private static void sizeHeader(JTable table, Density density) {
        JTableHeader header = table.getTableHeader();
        if (header == null) return;
        header.setFont(emphasis(metadata(table.getFont())));
        header.setPreferredSize(new Dimension(header.getPreferredSize().width,
            Math.max(24, header.getFontMetrics(header.getFont()).getHeight() + density.padding)));
    }

    /** A wrapping control row whose preferred height follows its available width. */
    public static JPanel controls() {
        return new WidthAwarePanel(new FlowLayout(FlowLayout.LEADING, 6, 2) {
            @Override public Dimension minimumLayoutSize(Container target) {
                // Page bodies are sized from their minimum height. Wrapped controls need
                // the same number of rows there as in their preferred layout.
                return new Dimension(0, preferredLayoutSize(target).height);
            }
            @Override public Dimension preferredLayoutSize(Container target) {
                synchronized (target.getTreeLock()) {
                    int width = availableWidth(target);
                    if (width <= 0) return super.preferredLayoutSize(target);
                    Insets insets = target.getInsets();
                    int available = Math.max(1, width - insets.left - insets.right - getHgap() * 2);
                    int rowWidth = 0, rowHeight = 0, height = getVgap() * 2, maxWidth = 0;
                    for (Component child : target.getComponents()) {
                        if (!child.isVisible()) continue;
                        Dimension size = child.getPreferredSize();
                        if (rowWidth > 0 && rowWidth + getHgap() + size.width > available) {
                            height += rowHeight + getVgap(); maxWidth = Math.max(maxWidth, rowWidth); rowWidth = rowHeight = 0;
                        }
                        rowWidth += (rowWidth == 0 ? 0 : getHgap()) + size.width;
                        rowHeight = Math.max(rowHeight, size.height);
                    }
                    return new Dimension(Math.max(maxWidth, rowWidth) + insets.left + insets.right + getHgap() * 2,
                        height + rowHeight + insets.top + insets.bottom);
                }
            }
        });
    }

    /** Equal-sized cells, dropping columns as the available width falls below the requested cell width. */
    public static JPanel responsiveGrid(int columns, int minimumCellWidth, int gap) {
        if (columns < 1 || minimumCellWidth < 1 || gap < 0) throw new IllegalArgumentException("Invalid grid dimensions");
        return new WidthAwarePanel(new GridLayout(0, columns, gap, gap) {
            private List<Component> visible(Container target) {
                List<Component> children = new ArrayList<>();
                for (Component child : target.getComponents()) if (child.isVisible()) children.add(child);
                return children;
            }
            private int columnCount(Container target, int count) {
                Insets insets = target.getInsets();
                int width = availableWidth(target) - insets.left - insets.right;
                int fitting = Math.max(1, Math.min(Math.min(columns, Math.max(1, count)),
                    width <= 0 ? columns : (width + gap) / (minimumCellWidth + gap)));
                int rows = Math.max(1, (count + fitting - 1) / fitting);
                // Keep the same row count while balancing cells (four cards become 4, 2, 1 columns).
                return Math.max(1, (count + rows - 1) / rows);
            }
            @Override public Dimension preferredLayoutSize(Container target) {
                synchronized (target.getTreeLock()) {
                    List<Component> children = visible(target);
                    Insets insets = target.getInsets();
                    int cols = columnCount(target, children.size()), rows = (children.size() + cols - 1) / cols;
                    int width = minimumCellWidth, height = 0;
                    for (Component child : children) {
                        Dimension size = child.getPreferredSize();
                        width = Math.max(width, size.width); height = Math.max(height, size.height);
                    }
                    return new Dimension(width * cols + gap * (cols - 1) + insets.left + insets.right,
                        height * rows + gap * Math.max(0, rows - 1) + insets.top + insets.bottom);
                }
            }
            @Override public Dimension minimumLayoutSize(Container target) {
                Insets insets = target.getInsets();
                // GridBagLayout may use minimum sizes when only the preferred width is too wide.
                // Cells can shrink horizontally, but still require their font-dependent height.
                return new Dimension(insets.left + insets.right, preferredLayoutSize(target).height);
            }
            @Override public void layoutContainer(Container target) {
                synchronized (target.getTreeLock()) {
                    List<Component> children = visible(target);
                    if (children.isEmpty()) return;
                    Insets insets = target.getInsets();
                    int cols = columnCount(target, children.size()), rows = (children.size() + cols - 1) / cols;
                    int width = Math.max(0, target.getWidth() - insets.left - insets.right - gap * (cols - 1));
                    int height = Math.max(0, target.getHeight() - insets.top - insets.bottom - gap * (rows - 1));
                    for (int i = 0; i < children.size(); i++) {
                        int col = i % cols, row = i / cols;
                        if (!target.getComponentOrientation().isLeftToRight()) col = cols - 1 - col;
                        int x = insets.left + col * width / cols + col * gap;
                        int y = insets.top + row * height / rows + row * gap;
                        children.get(i).setBounds(x, y, (col + 1) * width / cols - col * width / cols,
                            (row + 1) * height / rows - row * height / rows);
                    }
                }
            }
        });
    }

    private static int availableWidth(Container target) {
        if (target.getWidth() > 0) return target.getWidth();
        Container parent = target.getParent();
        if (parent == null) return 0;
        Insets insets = parent.getInsets();
        return parent.getWidth() - insets.left - insets.right;
    }

    private static final class WidthAwarePanel extends JPanel {
        WidthAwarePanel(LayoutManager layout) { super(layout); }
        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed) WidthRelayout.request(this, false);
        }
    }

    /** One post-layout invalidation per root, shared by wrapping text, controls and grids. */
    private static final class WidthRelayout {
        private static final String VALIDATED_WIDTH = "ContentStyle.validatedWidth";
        private static final java.util.Map<JComponent, Boolean> pending = new IdentityHashMap<>();

        static void request(JComponent component, boolean contentChanged) {
            if (component.getParent() == null) return;
            boolean schedule;
            synchronized (pending) {
                schedule = pending.isEmpty();
                pending.put(component, contentChanged || Boolean.TRUE.equals(pending.get(component)));
            }
            if (schedule) SwingUtilities.invokeLater(WidthRelayout::validateAfterLayout);
        }

        private static void validateAfterLayout() {
            java.util.Map<JComponent, Boolean> changed;
            synchronized (pending) {
                changed = new IdentityHashMap<>(pending);
                pending.clear();
            }
            Set<Container> roots = Collections.newSetFromMap(new IdentityHashMap<Container, Boolean>());
            for (java.util.Map.Entry<JComponent, Boolean> entry : changed.entrySet()) {
                JComponent panel = entry.getKey();
                if (panel.getParent() == null || (!entry.getValue()
                        && Integer.valueOf(panel.getWidth()).equals(panel.getClientProperty(VALIDATED_WIDTH)))) continue;
                panel.putClientProperty(VALIDATED_WIDTH, panel.getWidth());
                // Nested BorderLayouts may have measured their header before this panel received its new width.
                // Invalidation during that layout is lost; invalidate all ancestors after it has finished instead.
                Container root = panel;
                for (Container ancestor = panel; ancestor != null; ancestor = ancestor.getParent()) {
                    ancestor.invalidate(); root = ancestor;
                    if (ancestor instanceof Window) break;
                }
                roots.add(root);
            }
            for (Container root : roots) {
                if (root.isDisplayable()) root.validate();
                root.repaint();
            }
        }
    }

    /** Leaves values in the model untouched, preserving numeric sorting and exports. */
    public static class Cell extends DefaultTableCellRenderer {
        public Cell() { putClientProperty("html.disable", true); }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Border focusBorder = getBorder();
            setBorder(focus ? BorderFactory.createCompoundBorder(focusBorder, BorderFactory.createEmptyBorder(0, 6, 0, 6))
                : BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setFont(table.getFont());
            setHorizontalAlignment(Number.class.isAssignableFrom(table.getColumnClass(column)) ? RIGHT : LEFT);
            setIcon(null);
            if (!selected) {
                Integer hovered = (Integer) table.getClientProperty(HOVER_ROW);
                Color stripe = UIManager.getColor("Table.alternateRowColor");
                if (hovered != null && hovered == row) setBackground(color("hover"));
                else setBackground(row % 2 == 1 && stripe != null ? stripe : table.getBackground());
                setForeground(table.getForeground());
            }
            return this;
        }
    }

    public abstract static class Badge extends Cell {
        private Color ink;
        protected abstract Color badgeColor(Object value);

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            ink = selected ? table.getSelectionForeground() : badgeColor(value);
            setForeground(ink); setFont(emphasis(metadata(table.getFont())));
            setHorizontalAlignment(CENTER); setOpaque(false);
            return this;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = Math.max(0, Math.min(getWidth() - 6, getFontMetrics(getFont()).stringWidth(getText()) + 12));
            int height = Math.max(0, Math.min(getHeight() - 6, getFontMetrics(getFont()).getHeight() + 2));
            g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 24));
            g.fillRoundRect((getWidth() - width) / 2, (getHeight() - height) / 2, width, height, 4, 4);
            g.dispose(); super.paintComponent(graphics);
        }
    }
}
