package tomato.gui.glance.character;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.List;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Sheet › Fame's chart (spec §6.2: fame over time), painted with Tokens: one step line per session, a reading holding its value
 * until the next (readings are saved only when fame changes). The x axis is reading time: sessions sit side by side in order,
 * a fixed gap apart, each as wide as its share of the reading time (with a small floor, so a short session stays visible), so
 * hours of play are not lost between days offline; the gaps stand for time between sessions, of unknown length. A session with
 * one reading is a dot. Labels: the first and last reading's date under the plot, the lowest and highest fame beside it, as
 * estimates ("≈": readings are estimated from captured experience). The accessible description says the same. EDT only.
 */
final class FameChart extends JComponent implements Accessible {
    private List<FameHistory.Session> sessions = List.of();

    FameChart() {
        setOpaque(false);
        setName("character-fame-chart");
        ContentStyle.font(this, Type.caption());
        setToolTipText("Fame estimated from captured experience at each reading. Sessions sit side by side; the gaps are time between sessions.");
        getAccessibleContext().setAccessibleName("Fame history");
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    /** EDT: the sessions to draw (FameModel.sessions, each with at least one reading, oldest first). */
    void setSessions(List<FameHistory.Session> value) {
        List<FameHistory.Session> next = List.copyOf(value);
        if (next.equals(sessions)) return;
        sessions = next;
        getAccessibleContext().setAccessibleDescription(description(sessions));
        repaint();
    }

    List<FameHistory.Session> sessions() { return sessions; }

    /** "Fame from ≈ 1,000 to ≈ 1,400 over 2 sessions, 2026-09-12 to 2026-09-27; estimated from captured experience"; null without readings. */
    static String description(List<FameHistory.Session> sessions) {
        if (sessions.isEmpty()) return null;
        FameHistory.Point first = sessions.get(0).points().get(0);
        List<FameHistory.Point> lastPoints = sessions.get(sessions.size() - 1).points();
        FameHistory.Point last = lastPoints.get(lastPoints.size() - 1);
        String from = FameModel.date(first.time()), to = FameModel.date(last.time());
        return "Fame from " + estimate(first.fame()) + " to " + estimate(last.fame()) + " over " + sessions.size()
            + (sessions.size() == 1 ? " session, " : " sessions, ") + (from.equals(to) ? from : from + " to " + to)
            + "; estimated from captured experience";
    }

    /** "≈ 1,234" ("~" where the font lacks "≈", as DisplayValue does). */
    private static String estimate(long fame) { return DisplayValue.estimate(DisplayFormat.formatInteger(fame), "").text(); }

    @Override public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) return super.getPreferredSize();
        return new Dimension(200, getFontMetrics(getFont()).getHeight() * 11);   // follows Edit › Font; the width follows the tab
    }

    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

    @Override protected void paintComponent(Graphics graphics) {
        if (sessions.isEmpty()) return;
        long low = Long.MAX_VALUE, high = Long.MIN_VALUE, reading = 0;
        for (FameHistory.Session session : sessions) {
            for (FameHistory.Point p : session.points()) { low = Math.min(low, p.fame()); high = Math.max(high, p.fame()); }
            reading += span(session);
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            String top = estimate(high), bottom = estimate(low);
            String first = FameModel.date(sessions.get(0).points().get(0).time());
            List<FameHistory.Point> lastPoints = sessions.get(sessions.size() - 1).points();
            String last = FameModel.date(lastPoints.get(lastPoints.size() - 1).time());
            int left = Math.max(metrics.stringWidth(top), metrics.stringWidth(bottom)) + Tokens.S, right = Tokens.S;
            int plotTop = metrics.getAscent() / 2 + 2, plotBottom = getHeight() - metrics.getHeight() - Tokens.XS;
            int width = getWidth() - left - right, height = plotBottom - plotTop;
            if (width < 8 || height < 8) return;
            g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
            g.drawLine(left, plotTop, left + width, plotTop);
            g.drawLine(left, plotBottom, left + width, plotBottom);
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            g.drawString(top, left - Tokens.S / 2 - metrics.stringWidth(top), plotTop + metrics.getAscent() / 2 - 1);
            g.drawString(bottom, left - Tokens.S / 2 - metrics.stringWidth(bottom), plotBottom + metrics.getAscent() / 2 - 1);
            int dates = plotBottom + Tokens.XS + metrics.getAscent();
            g.drawString(first, left, dates);
            if (!last.equals(first) && metrics.stringWidth(first) + Tokens.M + metrics.stringWidth(last) <= width)
                g.drawString(last, left + width - metrics.stringWidth(last), dates);
            // Reading time decides each session's width; a floor keeps a one-reading or brief session visible.
            int n = sessions.size();
            double gap = n > 1 ? Math.min(Tokens.S, width * 0.25 / (n - 1)) : 0, usable = width - gap * (n - 1);
            double floor = Math.max(60_000.0, reading / (10.0 * n)), weights = reading + floor * n;
            double range = high == low ? 2 : high - low, base = high == low ? low - 1 : low;
            g.setColor(Tokens.color(Tokens.Role.ACCENT));
            g.setStroke(new BasicStroke(1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double x = left;
            for (FameHistory.Session session : sessions) {
                double w = usable * (span(session) + floor) / weights;
                List<FameHistory.Point> points = session.points();
                long start = points.get(0).time(), span = span(session);
                if (points.size() == 1 || span == 0) {
                    FameHistory.Point p = points.get(points.size() - 1);
                    double cx = x + w / 2, cy = y(p.fame(), base, range, plotTop, height);
                    g.fill(new Ellipse2D.Double(cx - 2.5, cy - 2.5, 5, 5));
                } else {
                    Path2D line = new Path2D.Double();
                    for (int i = 0; i < points.size(); i++) {
                        double px = x + w * (points.get(i).time() - start) / span, py = y(points.get(i).fame(), base, range, plotTop, height);
                        if (i == 0) line.moveTo(px, py);
                        else { line.lineTo(px, y(points.get(i - 1).fame(), base, range, plotTop, height)); line.lineTo(px, py); }
                    }
                    g.draw(line);
                }
                x += w + gap;
            }
        } finally {
            g.dispose();
        }
    }

    private static long span(FameHistory.Session session) {
        List<FameHistory.Point> points = session.points();
        return points.get(points.size() - 1).time() - points.get(0).time();
    }

    private static double y(long fame, double base, double range, int top, int height) { return top + height - height * (fame - base) / range; }
}
