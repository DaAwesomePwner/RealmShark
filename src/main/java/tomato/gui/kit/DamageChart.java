package tomato.gui.kit;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Damage over time as painted lines (the run recap's Damage section): per-bucket damage of saved series, drawn as damage per
 * second over {@code bucketSeconds}-wide buckets from the bucket origin. Simple draws your line ("You", accent) beside one muted
 * line summing every other saved series ("Top contributors"; "Others (top 12)" when the saved series leave some contributors
 * out, so it never claims everyone), or that single line without a verified local row; Analyst draws one named line per
 * series (yours in the accent, the others in a fixed categorical order, muted past its six hues). A caption names the unit and
 * the bucket width, a legend names two or more lines, and a note says how many hits before the first tick are counted in the
 * totals but not drawn. Fewer than two buckets draw a note instead of a plot. The accessible description states each line's peak
 * and total; hovering a bucket names its time and each line's damage. Axis labels use {@link KitFormat}. EDT only.
 */
public final class DamageChart extends JPanel {
    public static final String YOU = "You", TOP = "Top contributors", OTHERS_TOP = "Others (top 12)", UNNAMED = "Unnamed player";
    static final String NOT_ENOUGH = "Not enough data for a chart: fewer than two time buckets were recorded.";
    static final String NO_SERIES = "No damage over time was recorded.";

    /** One saved series: a player's damage per bucket; {@code name} null when none was captured. */
    public record Series(String name, boolean local, int[] values) {
        public Series { values = values == null ? new int[0] : values.clone(); }
        @Override public int[] values() { return values.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Series s && local == s.local && Objects.equals(name, s.name) && Arrays.equals(values, s.values);
        }
        @Override public int hashCode() { return Objects.hash(name, local, Arrays.hashCode(values)); }
    }

    /** What a drawn line stands for: your series, a sum of other series, or one other player's series (Analyst). */
    public enum Kind { LOCAL, OTHERS, PLAYER }

    /** One drawn line: its label, kind and damage per bucket. */
    public record Line(String label, Kind kind, long[] values) {
        public Line { values = values.clone(); }
        @Override public long[] values() { return values.clone(); }
        public long total() { long sum = 0; for (long v : values) sum += v; return sum; }
        /** The largest bucket's damage (0 without buckets). */
        public long peak() { long peak = 0; for (long v : values) peak = Math.max(peak, v); return peak; }
        /** The first bucket holding the peak, or -1 without buckets. */
        public int peakBucket() {
            int index = -1; long peak = Long.MIN_VALUE;
            for (int i = 0; i < values.length; i++) if (values[i] > peak) { peak = values[i]; index = i; }
            return index;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Line l && kind == l.kind && label.equals(l.label) && Arrays.equals(values, l.values);
        }
        @Override public int hashCode() { return Objects.hash(label, kind, Arrays.hashCode(values)); }
    }

    /**
     * Analyst hues for the other players' lines, in fixed order, light and dark steps: the dataviz reference palette's blue,
     * orange, aqua, yellow, magenta and green (violet is left out: it is the accent, your line; red is left out: it sits beside
     * green in that order and fails the colorblind separation check). Validated as a set in both modes; the meter table and the
     * legend's names carry identity as well. Players past these six are drawn muted.
     */
    private static final Color[] LIGHT = {new Color(0x2a78d6), new Color(0xeb6834), new Color(0x1baf7a), new Color(0xeda100), new Color(0xe87ba4), new Color(0x008300)};
    private static final Color[] DARK = {new Color(0x3987e5), new Color(0xd95926), new Color(0x199e70), new Color(0xc98500), new Color(0xd55181), new Color(0x008300)};

    private final Plot plot = new Plot();
    private final JLabel caption = new JLabel(" ");
    private final JPanel legend = ContentStyle.controls();
    private final JTextArea note = ContentStyle.wrappingText("");
    private List<Series> series = List.of();
    private List<Line> lines = List.of();
    private int bucketSeconds = 1, contributors, early, buckets;
    private boolean analyst;
    private String description = NO_SERIES;

    public DamageChart() { this(DisplayModeModel.application()); }

    public DamageChart(DisplayModeModel mode) {
        super(new BorderLayout(0, Tokens.XS));
        Objects.requireNonNull(mode, "mode");
        setOpaque(false);
        setName("damage-chart");
        caption.setName("damage-chart-caption");
        caption.putClientProperty("html.disable", Boolean.TRUE);
        ContentStyle.font(caption, Type.caption());
        legend.setName("damage-chart-legend");
        legend.setOpaque(false);
        note.setName("damage-chart-note");
        note.setVisible(false);
        add(caption, BorderLayout.NORTH);
        add(plot, BorderLayout.CENTER);
        add(KitLayouts.stack(Tokens.XS, legend, note), BorderLayout.SOUTH);
        getAccessibleContext().setAccessibleName("Damage over time");
        refreshColors();
        rebuild();
        // Simple and Analyst only change which lines are drawn; the data stays.
        mode.bind(this, value -> {
            boolean next = value == DisplayModeModel.Mode.ANALYST;
            if (next == analyst) return;
            analyst = next;
            rebuild();
        });
    }

    /**
     * EDT: the series to draw (in their saved order: rank, your row wherever it ranks), each a damage per bucket of
     * {@code bucketSeconds} s from {@code bucketOrigin} (epoch ms; the time axis counts from it). {@code contributors} counts every player with recorded damage,
     * so the sum of the other lines can say when it leaves players out; {@code hitsBeforeFirstTick} were counted in the totals
     * but not bucketed.
     */
    public void setData(List<Series> series, int bucketSeconds, long bucketOrigin, int contributors, int hitsBeforeFirstTick) {
        this.series = List.copyOf(series);
        this.bucketSeconds = Math.max(1, bucketSeconds);
        this.contributors = Math.max(0, contributors);
        this.early = Math.max(0, hitsBeforeFirstTick);
        rebuild();
    }

    /** The drawn lines in the current mode (no line without series). */
    public List<Line> lines() { return lines; }
    /** True when at least two buckets can be drawn. */
    public boolean enoughData() { return !lines.isEmpty() && buckets >= 2; }
    /** The accessible description: each line's peak and total, or why nothing is drawn, plus the early hits. */
    public String description() { return description; }

    /**
     * The lines of {@code series}. Simple: yours as {@link #YOU} and the others summed per bucket as {@link #TOP}, or as
     * {@link #OTHERS_TOP} when fewer series than {@code contributors} were saved; without your series, one {@link #TOP} line summing
     * them all. Analyst: one line per series, yours labeled with "(you)".
     */
    static List<Line> lines(List<Series> series, int contributors, boolean analyst) {
        int length = 0;
        for (Series s : series) length = Math.max(length, s.values.length);
        List<Line> result = new ArrayList<>();
        if (series.isEmpty()) return result;
        if (analyst) {
            for (Series s : series) {
                String name = s.name == null || s.name.isEmpty() ? null : s.name;
                String label = s.local ? name == null ? YOU : name + " (you)" : name == null ? UNNAMED : name;
                result.add(new Line(label, s.local ? Kind.LOCAL : Kind.PLAYER, widen(s.values, length)));
            }
            return result;
        }
        Series local = null;
        for (Series s : series) if (s.local && local == null) local = s;
        long[] others = new long[length];
        int summed = 0;
        for (Series s : series) {
            if (s == local) continue;
            summed++;
            for (int i = 0; i < s.values.length; i++) others[i] += s.values[i];
        }
        if (local != null) result.add(new Line(YOU, Kind.LOCAL, widen(local.values, length)));
        if (summed > 0) result.add(new Line(local != null && series.size() < contributors ? OTHERS_TOP : TOP, Kind.OTHERS, others));
        return result;
    }

    private static long[] widen(int[] values, int length) {
        long[] wide = new long[length];
        for (int i = 0; i < values.length; i++) wide[i] = values[i];
        return wide;
    }

    /** "Damage per second over 4s in 1 s buckets. You: peak 6k at 2s, 10.5k in total. …" plus the early-hits sentence. */
    static String description(List<Line> lines, int bucketSeconds, int buckets, int early) {
        StringBuilder text = new StringBuilder();
        if (lines.isEmpty()) text.append(NO_SERIES);
        else if (buckets < 2) text.append(NOT_ENOUGH);
        else {
            text.append("Damage per second over ").append(KitFormat.duration(buckets * bucketSeconds * 1000L)).append(" in ")
                .append(bucketSeconds).append(" s buckets.");
            for (Line line : lines)
                text.append(' ').append(line.label()).append(": peak ").append(KitFormat.compact(line.peak() / (double) bucketSeconds))
                    .append(" at ").append(KitFormat.duration(Math.max(0, line.peakBucket()) * bucketSeconds * 1000L)).append(", ")
                    .append(KitFormat.compact(line.total())).append(" in total.");
        }
        String hits = earlyHits(early);
        if (!hits.isEmpty()) text.append(' ').append(hits);
        return text.toString();
    }

    /** "2 early hits are in the totals, not the chart."; "" without any. */
    static String earlyHits(int early) {
        if (early <= 0) return "";
        return early == 1 ? "1 early hit is in the totals, not the chart."
            : DisplayFormat.formatInteger(early) + " early hits are in the totals, not the chart.";
    }

    /** "1s–2s: You 3,000 · Top contributors 1,000" for one bucket. */
    String tooltip(int bucket) {
        if (lines.isEmpty() || bucket < 0 || bucket >= buckets) return null;
        StringBuilder text = new StringBuilder(KitFormat.duration(bucket * (long) bucketSeconds * 1000L)).append('–')
            .append(KitFormat.duration((bucket + 1) * (long) bucketSeconds * 1000L)).append(':');
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            text.append(i == 0 ? " " : " · ").append(line.label()).append(' ').append(DisplayFormat.formatInteger(line.values[bucket]));
        }
        return text.toString();
    }

    private void rebuild() {
        List<Line> next = lines(series, contributors, analyst);
        int length = 0;
        for (Line line : next) length = Math.max(length, line.values.length);
        lines = next;
        buckets = length;
        description = description(lines, bucketSeconds, buckets, early);
        getAccessibleContext().setAccessibleDescription(description);
        plot.getAccessibleContext().setAccessibleDescription(description);
        String unit = "damage per second · " + bucketSeconds + " s buckets";
        caption.setText(lines.size() == 1 ? lines.get(0).label() + " · " + unit : Character.toUpperCase(unit.charAt(0)) + unit.substring(1));
        legend.removeAll();
        for (int i = 0; i < lines.size(); i++) {
            JLabel entry = new JLabel(lines.get(i).label(), new Swatch(i), SwingConstants.LEADING);
            entry.putClientProperty("html.disable", Boolean.TRUE);
            entry.setName("damage-chart-legend-entry");
            ContentStyle.font(entry, Type.caption());
            entry.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            legend.add(entry);
        }
        legend.setVisible(lines.size() > 1);
        String hits = earlyHits(early);
        note.setText(hits);
        note.setVisible(!hits.isEmpty());
        revalidate();
        repaint();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (caption != null) refreshColors();   // null while JPanel's constructor runs
    }

    private void refreshColors() {
        caption.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        for (Component entry : legend.getComponents()) entry.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

    /** The ink of line {@code index}: yours the accent; Simple's sum muted; Analyst's others in the fixed order, then muted. */
    private Color ink(int index) {
        Line line = lines.get(index);
        if (line.kind() == Kind.LOCAL) return Tokens.color(Tokens.Role.ACCENT);
        if (line.kind() == Kind.OTHERS) return Tokens.color(Tokens.Role.TEXT_MUTED);
        int slot = 0;
        for (int i = 0; i < index; i++) if (lines.get(i).kind() == Kind.PLAYER) slot++;
        Color[] palette = Tokens.dark() ? DARK : LIGHT;
        return slot < palette.length ? palette[slot] : Tokens.color(Tokens.Role.TEXT_MUTED);
    }

    /** A short line in a legend entry's color. */
    private final class Swatch implements Icon {
        private final int index;
        Swatch(int index) { this.index = index; }
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 10; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            if (index >= lines.size()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ink(index));
            g.setStroke(new BasicStroke(lines.get(index).kind() == Kind.LOCAL ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 1, y + 5, x + 13, y + 5);
            g.dispose();
        }
    }

    /** The painted plot: axis labels, a baseline and the lines; a note instead with fewer than two buckets. */
    private final class Plot extends JComponent implements Accessible {
        private int hover = -1;

        Plot() {
            setName("damage-chart-plot");
            setOpaque(false);
            setFocusable(false);
            ContentStyle.font(this, Type.caption());
            setToolTipText("");
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { hover(bucketAt(e.getX())); }
                @Override public void mouseExited(MouseEvent e) { hover(-1); }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            getAccessibleContext().setAccessibleName("Damage over time");
        }

        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
                @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
            };
            return accessibleContext;
        }

        private void hover(int bucket) { if (bucket != hover) { hover = bucket; repaint(); } }

        @Override public String getToolTipText(MouseEvent event) { return enoughData() ? tooltip(bucketAt(event.getX())) : null; }

        @Override public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) return super.getPreferredSize();
            return new Dimension(200, getFontMetrics(getFont()).getHeight() * 9);   // follows Edit › Font; the width follows the section
        }
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

        /** The plot's left edge: the widest y label plus a gap. */
        private int left(FontMetrics metrics) {
            return Math.max(metrics.stringWidth(yTop()), metrics.stringWidth("0")) + Tokens.S;
        }

        private String yTop() {
            long peak = 0;
            for (Line line : lines) peak = Math.max(peak, line.peak());
            return KitFormat.compact(peak / (double) bucketSeconds);
        }

        private int bucketAt(int x) {
            if (buckets < 2) return -1;
            FontMetrics metrics = getFontMetrics(getFont());
            int left = left(metrics), width = getWidth() - left - Tokens.S;
            if (width <= 0) return -1;
            return (int) Math.max(0, Math.min(buckets - 1, Math.round((x - left) * (buckets - 1) / (double) width)));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                FontMetrics metrics = g.getFontMetrics();
                if (!enoughData()) {
                    String text = lines.isEmpty() ? NO_SERIES : "Not enough data for a chart";
                    g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
                    g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
                    g.drawString(text, Math.max(Tokens.S, (getWidth() - metrics.stringWidth(text)) / 2), (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
                    return;
                }
                String top = yTop(), end = KitFormat.duration(buckets * (long) bucketSeconds * 1000L), start = KitFormat.duration(0);
                int left = left(metrics), right = Tokens.S;
                int plotTop = metrics.getAscent() / 2 + 2, plotBottom = getHeight() - metrics.getHeight() - Tokens.XS;
                int width = getWidth() - left - right, height = plotBottom - plotTop;
                if (width < 8 || height < 8) return;
                // Recessive axes: the top, middle and baseline hairlines, labels in muted text.
                g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
                g.drawLine(left, plotTop, left + width, plotTop);
                g.drawLine(left, plotTop + height / 2, left + width, plotTop + height / 2);
                g.setColor(Tokens.color(Tokens.Role.BORDER));
                g.drawLine(left, plotBottom, left + width, plotBottom);
                g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
                g.drawString(top, left - Tokens.S / 2 - metrics.stringWidth(top), plotTop + metrics.getAscent() / 2 - 1);
                g.drawString("0", left - Tokens.S / 2 - metrics.stringWidth("0"), plotBottom + metrics.getAscent() / 2 - 1);
                int labels = plotBottom + Tokens.XS + metrics.getAscent();
                g.drawString(start, left, labels);
                if (metrics.stringWidth(start) + Tokens.M + metrics.stringWidth(end) <= width) g.drawString(end, left + width - metrics.stringWidth(end), labels);
                long peak = 0;
                for (Line line : lines) peak = Math.max(peak, line.peak());
                double scale = peak <= 0 ? 0 : height / (double) peak;
                if (hover >= 0) {
                    int x = left + (int) Math.round(width * hover / (double) (buckets - 1));
                    g.setColor(Tokens.color(Tokens.Role.BORDER));
                    g.drawLine(x, plotTop, x, plotBottom);
                }
                // Others first, yours last (on top).
                for (int pass = 0; pass < 2; pass++) for (int i = 0; i < lines.size(); i++) {
                    Line line = lines.get(i);
                    if ((line.kind() == Kind.LOCAL) != (pass == 1)) continue;
                    Path2D path = new Path2D.Double();
                    for (int b = 0; b < buckets; b++) {
                        double x = left + width * b / (double) (buckets - 1), y = plotBottom - line.values[b] * scale;
                        if (b == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    g.setColor(ink(i));
                    g.setStroke(new BasicStroke(line.kind() == Kind.LOCAL ? 2.25f : 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
