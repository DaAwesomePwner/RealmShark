package tomato.gui.stats;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import javax.swing.*;

/** Step chart shared by live fame tracking and saved sessions. */
public class GraphPanel extends JPanel implements MouseMotionListener, MouseListener {
    private ArrayList<Fame> originalScores = new ArrayList<>();
    private ArrayList<Fame> scores = new ArrayList<>();
    private final JComboBox<String> range;
    private int anchor = -1, cursor = -1;
    private boolean dragging;
    private static final int LEFT = 78, RIGHT = 24, TOP = 24, BOTTOM = 100;

    public GraphPanel(ArrayList<Fame> scores) { this(scores, true); }
    public GraphPanel(ArrayList<Fame> scores, boolean showTimeRangeDropdown) {
        setLayout(new BorderLayout());
        range = new JComboBox<>(new String[]{"All samples", "1 min", "5 min", "15 min", "30 min", "60 min"});
        if (showTimeRangeDropdown) {
            JPanel controls = StatsUi.controls(); controls.add(new JLabel("Range ending at latest sample")); controls.add(range);
            add(controls, BorderLayout.SOUTH);
        }
        range.addActionListener(e -> applyRange());
        addMouseListener(this); addMouseMotionListener(this); setScores(scores);
    }
    public static GraphPanel createMinimal() { return new GraphPanel(new ArrayList<>(), false); }
    public void setScores(ArrayList<Fame> values) {
        originalScores = values == null ? new ArrayList<>() : new ArrayList<>(values);
        originalScores.sort(Comparator.comparingLong(Fame::getTime)); applyRange();
    }
    public ArrayList<Fame> getScores() { return new ArrayList<>(scores); }
    public void clearData() { originalScores.clear(); range.setSelectedIndex(0); applyRange(); }
    public JPanel createTimeRangeButtons() { JPanel panel = new JPanel(); panel.setOpaque(false); return panel; }
    private void applyRange() {
        long[] minutes = {0, 1, 5, 15, 30, 60};
        scores = window(originalScores, minutes[range.getSelectedIndex()] * 60000);
        anchor = cursor = -1; dragging = false; repaint();
    }
    /** Keeps actual samples only; no gain is invented at the range boundary. */
    static ArrayList<Fame> window(ArrayList<Fame> input, long duration) {
        ArrayList<Fame> result = new ArrayList<>();
        if (input == null || input.isEmpty()) return result;
        long latest = input.stream().mapToLong(Fame::getTime).max().orElse(0);
        for (Fame value : input) if (duration == 0 || value.getTime() >= latest - duration) result.add(value);
        result.sort(Comparator.comparingLong(Fame::getTime)); return result;
    }
    private int plotWidth() { return Math.max(1, getWidth() - LEFT - RIGHT); }
    private int plotHeight() { return Math.max(1, getHeight() - TOP - BOTTOM); }
    private double minimum() { return scores.stream().mapToDouble(Fame::getFame).min().orElse(0); }
    private double maximum() { return scores.stream().mapToDouble(Fame::getFame).max().orElse(1); }
    private long elapsed() { return scores.size() < 2 ? 0 : scores.get(scores.size() - 1).getTime() - scores.get(0).getTime(); }
    private int x(int index) { return LEFT + (int)((scores.get(index).getTime() - scores.get(0).getTime()) * (double)plotWidth() / Math.max(1, elapsed())); }
    private int y(double fame, double low, double high) { return TOP + (int)((high - fame) * plotHeight() / (high - low)); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color muted = UIManager.getColor("Label.disabledForeground");
            Color accent = UIManager.getColor("Component.accentColor"); if (accent == null) accent = new Color(0xAB86EF);
            Color grid = UIManager.getColor("Separator.foreground"); if (grid == null) grid = Color.GRAY;
            g.setFont(getFont().deriveFont(11f));
            if (scores.size() < 2 || elapsed() <= 0) {
                g.setColor(muted);
                centered(g, scores.isEmpty() ? "Waiting for fame samples" : "Waiting for another timestamp", getHeight() / 2 - 8);
                centered(g, "Earn fame with capture running, or choose a wider range.", getHeight() / 2 + 15); return;
            }
            double low = minimum(), high = maximum();
            double pad = Math.max(1, (high - low) * .08); low -= pad; high += pad;
            for (int i = 0; i <= 5; i++) {
                int py = TOP + plotHeight() * i / 5;
                g.setColor(grid); g.drawLine(LEFT, py, LEFT + plotWidth(), py);
                g.setColor(muted); String label = Formatters.formatNumber(high - (high - low) * i / 5, 0);
                g.drawString(label, LEFT - 10 - g.getFontMetrics().stringWidth(label), py + 4);
                int px = LEFT + plotWidth() * i / 5;
                String time = Formatters.formatDurationHMS(elapsed() * i / 5);
                g.drawString(time, px - g.getFontMetrics().stringWidth(time) / 2, TOP + plotHeight() + 21);
            }
            Path2D line = new Path2D.Double(); line.moveTo(x(0), y(scores.get(0).getFame(), low, high));
            for (int i = 1; i < scores.size(); i++) {
                line.lineTo(x(i), y(scores.get(i - 1).getFame(), low, high)); line.lineTo(x(i), y(scores.get(i).getFame(), low, high));
            }
            Path2D fill = (Path2D)line.clone(); fill.lineTo(x(scores.size() - 1), TOP + plotHeight()); fill.lineTo(x(0), TOP + plotHeight()); fill.closePath();
            g.setPaint(new GradientPaint(0, TOP, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 65), 0, TOP + plotHeight(), new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 4)));
            g.fill(fill); g.setColor(accent); g.setStroke(new BasicStroke(2f)); g.draw(line);
            int first = 0, last = scores.size() - 1;
            if (anchor >= 0 && cursor >= 0) {
                first = Math.min(anchor, cursor); last = Math.max(anchor, cursor);
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45));
                g.fillRect(x(first), TOP, Math.max(2, x(last) - x(first)), plotHeight());
            }
            if (cursor >= 0) {
                g.setColor(accent); g.setStroke(new BasicStroke(1f)); g.drawLine(x(cursor), TOP, x(cursor), TOP + plotHeight());
                g.fillOval(x(cursor) - 4, y(scores.get(cursor).getFame(), low, high) - 4, 8, 8);
            }
            double gain = scores.get(last).getFame() - scores.get(first).getFame();
            long duration = scores.get(last).getTime() - scores.get(first).getTime();
            g.setColor(getForeground());
            String summary = (anchor >= 0 ? "Selection" : "Shown range") + "   ·   " + Formatters.formatNumber(gain, 1) + " fame   ·   "
                + Formatters.formatDurationHMS(duration) + "   ·   " + (duration > 0 ? Formatters.formatNumber(gain * 3600000.0 / duration, 1) + " fame/h" : "Rate unavailable");
            g.drawString(summary, LEFT, TOP + plotHeight() + 45);
            g.setColor(muted); g.drawString("Elapsed time · Drag across the chart to compare an interval", LEFT, TOP + plotHeight() + 65);
        } finally { g.dispose(); }
    }
    private void centered(Graphics2D g, String text, int y) { g.drawString(text, Math.max(8, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2), y); }
    private int closest(int px) {
        if (scores.isEmpty()) return -1;
        int best = 0; for (int i = 1; i < scores.size(); i++) if (Math.abs(x(i) - px) < Math.abs(x(best) - px)) best = i;
        return best;
    }
    @Override public void mousePressed(MouseEvent e) { anchor = cursor = closest(e.getX()); dragging = true; repaint(); }
    @Override public void mouseDragged(MouseEvent e) { if (dragging) { cursor = closest(e.getX()); repaint(); } }
    @Override public void mouseReleased(MouseEvent e) { dragging = false; repaint(); }
    @Override public void mouseMoved(MouseEvent e) {
        cursor = closest(e.getX());
        setToolTipText(cursor < 0 ? null : Formatters.formatTimestamp(scores.get(cursor).getTime()) + " · " + Formatters.formatNumber(scores.get(cursor).getFame(), 1) + " fame");
        if (anchor >= 0) anchor = -1; repaint();
    }
    @Override public void mouseExited(MouseEvent e) { if (!dragging && anchor < 0) cursor = -1; repaint(); }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
}
