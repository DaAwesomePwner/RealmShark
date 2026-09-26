package tomato.gui.kit;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.modern.DisplayFormat;

/** A tiny trend line with a tinted area. Fewer than two values draw nothing. */
public class Sparkline extends JComponent implements Accessible {
    private double[] values = new double[0];

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    public Sparkline() { setOpaque(false); }

    public void setValues(double[] input) {
        values = input == null ? new double[0] : input.clone();
        if (values.length > 1) {
            double low = values[0], high = values[0];
            for (double v : values) { low = Math.min(low, v); high = Math.max(high, v); }
            getAccessibleContext().setAccessibleDescription("From " + DisplayFormat.formatNumber(values[0], 0, 1) + " to "
                + DisplayFormat.formatNumber(values[values.length - 1], 0, 1) + ", low " + DisplayFormat.formatNumber(low, 0, 1)
                + ", high " + DisplayFormat.formatNumber(high, 0, 1));
        } else {
            getAccessibleContext().setAccessibleDescription(null);
        }
        repaint();
    }

    public double[] values() { return values.clone(); }

    @Override public Dimension getPreferredSize() { return new Dimension(60, 18); }

    @Override protected void paintComponent(Graphics graphics) {
        if (values.length < 2) return;
        double low = values[0], high = values[0];
        for (double v : values) { low = Math.min(low, v); high = Math.max(high, v); }
        double span = high - low == 0 ? 1 : high - low;
        int width = getWidth() - 2, height = getHeight() - 3;
        Path2D line = new Path2D.Double();
        for (int i = 0; i < values.length; i++) {
            double x = 1 + width * i / (double) (values.length - 1), y = 1 + height - height * (values[i] - low) / span;
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
        }
        Path2D area = new Path2D.Double(line);
        area.lineTo(1 + width, 1 + height);
        area.lineTo(1, 1 + height);
        area.closePath();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Tokens.tint(Tokens.color(Tokens.Role.ACCENT)));
        g.fill(area);
        g.setColor(Tokens.color(Tokens.Role.ACCENT));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
        g.dispose();
    }
}
