package tomato.gui.modern;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
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
            case "background": return themeColor("Panel.background", dark ? 0x15151E : 0xF5F5F8);
            case "surface": return themeColor("Table.background", dark ? 0x191922 : 0xFFFFFF);
            case "navigation": return themeColor("MenuBar.background", dark ? 0x111118 : 0xEEEEF3);
            case "border": return themeColor("Separator.foreground", dark ? 0x313140 : 0xD5D3DE);
            case "text": return themeColor("Label.foreground", dark ? 0xEEEDF7 : 0x24222E);
            case "selection": return themeColor("Table.selectionBackground", dark ? 0x403258 : 0xE5DCF8);
            case "selectionText": return themeColor("Table.selectionForeground", dark ? 0xF4F0FF : 0x302048);
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
        JScrollPane scroll = new JScrollPane(new Page());
        scroll.setBorder(null); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
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
        private static final Set<WidthAwarePanel> pending = Collections.newSetFromMap(new IdentityHashMap<WidthAwarePanel, Boolean>());
        private int validatedWidth = -1;

        WidthAwarePanel(LayoutManager layout) { super(layout); }
        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (!changed || getParent() == null) return;
            boolean schedule;
            synchronized (pending) {
                schedule = pending.isEmpty();
                pending.add(this);
            }
            if (schedule) SwingUtilities.invokeLater(WidthAwarePanel::validateAfterLayout);
        }

        private static void validateAfterLayout() {
            List<WidthAwarePanel> changed;
            synchronized (pending) {
                changed = new ArrayList<>(pending);
                pending.clear();
            }
            Set<Container> roots = Collections.newSetFromMap(new IdentityHashMap<Container, Boolean>());
            for (WidthAwarePanel panel : changed) {
                if (panel.getParent() == null || panel.getWidth() == panel.validatedWidth) continue;
                panel.validatedWidth = panel.getWidth();
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
                Color stripe = UIManager.getColor("Table.alternateRowColor");
                setBackground(row % 2 == 1 && stripe != null ? stripe : table.getBackground());
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
