package tomato.gui.stats;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import javax.swing.*;
import tomato.gui.modern.DisplayFormat;

/**
 * Step chart shared by live fame tracking and saved sessions. A pinned interval is stored as two sample
 * timestamps, not indices, so new samples, range/measure changes and pointer movement never clear or shift it.
 * Keyboard: Left/Right/Home/End move the sample cursor, [ and ] (or Enter) set the start/end endpoints, Escape clears.
 * The same text as the painted summary is available from {@link #inspectionSummary()} and the accessible description.
 */
public class GraphPanel extends JPanel implements MouseMotionListener, MouseListener {
    public static final String SUMMARY_PROPERTY = "inspectionSummary";
    private ArrayList<Fame> originalScores = new ArrayList<>();
    private ArrayList<Fame> scores = new ArrayList<>();
    private final JComboBox<String> range;
    private Long pinStart, pinEnd, dragFrom, cursorTime;
    private int hover = -1;
    private String summary = "";
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
        setFocusable(true);
        getAccessibleContext().setAccessibleName("Fame graph");
        addMouseListener(this); addMouseMotionListener(this); keys(); setScores(scores);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { if (cursorTime == null && !GraphPanel.this.scores.isEmpty()) cursorTime = last().getTime(); repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }
    public static GraphPanel createMinimal() { return new GraphPanel(new ArrayList<>(), false); }
    public void setScores(ArrayList<Fame> values) {
        originalScores = values == null ? new ArrayList<>() : new ArrayList<>(values);
        originalScores.sort(Comparator.comparingLong(Fame::getTime)); applyRange();
    }
    public ArrayList<Fame> getScores() { return new ArrayList<>(scores); }
    /** Clearing the data also forgets the pinned interval; new or re-windowed samples do not. */
    public void clearData() { originalScores.clear(); pinStart = pinEnd = dragFrom = cursorTime = null; range.setSelectedIndex(0); applyRange(); }
    public JPanel createTimeRangeButtons() { JPanel panel = new JPanel(); panel.setOpaque(false); return panel; }
    private void applyRange() {
        long[] minutes = {0, 1, 5, 15, 30, 60};
        scores = window(originalScores, minutes[range.getSelectedIndex()] * 60000);
        hover = -1; if (cursorTime != null && index(cursorTime) < 0) cursorTime = scores.isEmpty() ? null : last().getTime();
        updateSummary(); repaint();
    }
    /** Keeps actual samples only; no gain is invented at the range boundary. */
    public static ArrayList<Fame> window(ArrayList<Fame> input, long duration) {
        ArrayList<Fame> result = new ArrayList<>();
        if (input == null || input.isEmpty()) return result;
        long latest = input.stream().mapToLong(Fame::getTime).max().orElse(0);
        for (Fame value : input) if (duration == 0 || value.getTime() >= latest - duration) result.add(value);
        result.sort(Comparator.comparingLong(Fame::getTime)); return result;
    }

    // --- Timestamp pins -------------------------------------------------------------------------

    public Long pinnedStart() { return pinStart; }
    public Long pinnedEnd() { return pinEnd; }
    public Long cursorTime() { return cursorTime; }
    /** Pins an interval by sample timestamps; order does not matter. A null end leaves the interval open. */
    public void pinInterval(Long start, Long end) {
        if (start == null) end = null;
        if (start != null && end != null && end < start) { Long swap = start; start = end; end = swap; }
        pinStart = start; pinEnd = end; updateSummary(); repaint();
    }
    public void clearPin() { pinInterval(null, null); }
    public void setPinStart(long time) { pinInterval(time, pinEnd != null && pinEnd >= time ? pinEnd : null); }
    public void setPinEnd(long time) { if (pinStart == null) pinInterval(time, null); else pinInterval(pinStart, time); }
    public String inspectionSummary() { return summary; }

    private int index(long time) { for (int i = 0; i < scores.size(); i++) if (scores.get(i).getTime() == time) return i; return -1; }
    private Fame last() { return scores.get(scores.size() - 1); }
    /** Indices of the pinned samples among the shown samples, or null when the pin is incomplete or outside them. */
    private int[] pinned() {
        if (pinStart == null || pinEnd == null) return null;
        int first = index(pinStart), last = index(pinEnd);
        if (first < 0 || last < 0) return null;
        // Duplicate timestamps: span from the first sample at the start time to the last at the end time.
        while (last + 1 < scores.size() && scores.get(last + 1).getTime() == pinEnd) last++;
        return new int[]{first, last};
    }
    private void updateSummary() {
        String next;
        if (scores.isEmpty()) next = pinStart == null ? "No samples shown." : "Pinned interval kept · no samples shown · press Esc to clear";
        else if (pinStart != null && pinEnd == null) next = "Start pinned at " + DisplayFormat.formatTimestamp(pinStart) + " · choose an end sample (] or Enter) · Esc clears";
        else if (pinStart != null) {
            int[] pin = pinned();
            next = pin == null ? "Pinned interval " + DisplayFormat.formatTimestamp(pinStart) + " → " + DisplayFormat.formatTimestamp(pinEnd)
                + " is not in the shown samples · widen the range or press Esc to clear"
                : "Pinned " + delta(pin[0], pin[1]);
        } else next = "Shown range " + delta(0, scores.size() - 1);
        if (!Objects.equals(next, summary)) {
            String old = summary; summary = next;
            getAccessibleContext().setAccessibleDescription(next); firePropertyChange(SUMMARY_PROPERTY, old, next);
        }
    }
    private String delta(int first, int last) {
        Fame a = scores.get(first), b = scores.get(last);
        double gain = b.getFame() - a.getFame(); long duration = b.getTime() - a.getTime();
        return DisplayFormat.formatTimestamp(a.getTime()) + " → " + DisplayFormat.formatTimestamp(b.getTime()) + " · "
            + (gain > 0 ? "+" : "") + DisplayFormat.formatNumber(gain, 1) + " fame · " + DisplayFormat.formatDurationHMS(duration) + " · "
            + (duration > 0 ? DisplayFormat.formatRate(gain * 3600000.0 / duration, 1) + " fame/h" : "rate unavailable (one timestamp)");
    }

    private void keys() {
        InputMap in = getInputMap(WHEN_FOCUSED); ActionMap act = getActionMap();
        bind(in, act, "LEFT", "fame-graph-previous", () -> move(-1));
        bind(in, act, "RIGHT", "fame-graph-next", () -> move(1));
        bind(in, act, "HOME", "fame-graph-first", () -> { if (!scores.isEmpty()) cursorTime = scores.get(0).getTime(); repaint(); });
        bind(in, act, "END", "fame-graph-last", () -> { if (!scores.isEmpty()) cursorTime = last().getTime(); repaint(); });
        bind(in, act, "OPEN_BRACKET", "fame-graph-pin-start", () -> { if (cursorTime != null) setPinStart(cursorTime); });
        bind(in, act, "CLOSE_BRACKET", "fame-graph-pin-end", () -> { if (cursorTime != null) setPinEnd(cursorTime); });
        bind(in, act, "ENTER", "fame-graph-pin-toggle", () -> { if (cursorTime == null) return; if (pinStart == null || pinEnd != null) pinInterval(cursorTime, null); else setPinEnd(cursorTime); });
        bind(in, act, "ESCAPE", "fame-graph-clear-pin", this::clearPin);
    }
    private static void bind(InputMap in, ActionMap act, String key, String name, Runnable action) {
        in.put(KeyStroke.getKeyStroke(key), name);
        act.put(name, new AbstractAction(name) { @Override public void actionPerformed(ActionEvent e) { action.run(); } });
    }
    private void move(int step) {
        if (scores.isEmpty()) return;
        int at = cursorTime == null ? -1 : index(cursorTime);
        at = at < 0 ? (step > 0 ? 0 : scores.size() - 1) : Math.max(0, Math.min(scores.size() - 1, at + step));
        cursorTime = scores.get(at).getTime();
        setToolTipText(DisplayFormat.formatTimestamp(cursorTime) + " · " + DisplayFormat.formatNumber(scores.get(at).getFame(), 1) + " fame");
        repaint();
    }

    // --- Painting ---------------------------------------------------------------------------------

    private int plotWidth() { return Math.max(1, getWidth() - LEFT - RIGHT); }
    private int plotHeight() { return Math.max(1, getHeight() - TOP - BOTTOM); }
    private double minimum() { return scores.stream().mapToDouble(Fame::getFame).min().orElse(0); }
    private double maximum() { return scores.stream().mapToDouble(Fame::getFame).max().orElse(1); }
    private long elapsed() { return scores.size() < 2 ? 0 : last().getTime() - scores.get(0).getTime(); }
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
            int[] pin = pinned();
            if (pin != null) {
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45));
                g.fillRect(x(pin[0]), TOP, Math.max(2, x(pin[1]) - x(pin[0])), plotHeight());
            } else if (pinStart != null && index(pinStart) >= 0) {
                g.setColor(accent); g.drawLine(x(index(pinStart)), TOP, x(index(pinStart)), TOP + plotHeight());
            }
            int marker = hover >= 0 && hover < scores.size() ? hover : isFocusOwner() && cursorTime != null ? index(cursorTime) : -1;
            if (marker >= 0) {
                g.setColor(accent); g.setStroke(new BasicStroke(1f)); g.drawLine(x(marker), TOP, x(marker), TOP + plotHeight());
                g.fillOval(x(marker) - 4, y(scores.get(marker).getFame(), low, high) - 4, 8, 8);
            }
            g.setColor(getForeground());
            g.drawString(summary, LEFT, TOP + plotHeight() + 45);
            g.setColor(muted); g.drawString("Drag to pin an interval · Keyboard: arrows, [ start, ] end, Esc clear", LEFT, TOP + plotHeight() + 65);
        } finally { g.dispose(); }
    }
    private void centered(Graphics2D g, String text, int y) { g.drawString(text, Math.max(8, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2), y); }
    private int closest(int px) {
        if (scores.isEmpty()) return -1;
        if (elapsed() <= 0) return 0;
        int best = 0; for (int i = 1; i < scores.size(); i++) if (Math.abs(x(i) - px) < Math.abs(x(best) - px)) best = i;
        return best;
    }
    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow(); int at = closest(e.getX()); if (at < 0) return;
        dragFrom = scores.get(at).getTime(); cursorTime = dragFrom; repaint();
    }
    @Override public void mouseDragged(MouseEvent e) {
        int at = closest(e.getX()); if (dragFrom == null || at < 0) return;
        hover = at; pinInterval(dragFrom, scores.get(at).getTime());
    }
    /** A click without dragging clears the pin; a drag leaves the pinned timestamps in place. */
    @Override public void mouseReleased(MouseEvent e) {
        int at = closest(e.getX());
        if (dragFrom != null && at >= 0 && scores.get(at).getTime() == dragFrom) clearPin();
        dragFrom = null; repaint();
    }
    /** Pointer movement only moves the hover marker; it never changes a pinned interval. */
    @Override public void mouseMoved(MouseEvent e) {
        hover = closest(e.getX());
        setToolTipText(hover < 0 ? null : Formatters.formatTimestamp(scores.get(hover).getTime()) + " · " + Formatters.formatNumber(scores.get(hover).getFame(), 1) + " fame");
        repaint();
    }
    @Override public void mouseExited(MouseEvent e) { if (dragFrom == null) hover = -1; repaint(); }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
}
