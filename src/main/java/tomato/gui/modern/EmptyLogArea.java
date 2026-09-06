package tomato.gui.modern;

import java.awt.*;
import javax.swing.*;

/** A visual empty state, never inserted into the log or its exports. */
public final class EmptyLogArea extends JTextArea {
    private final String heading, detail;
    public EmptyLogArea(String heading, String detail) { this.heading = heading; this.detail = detail; }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getDocument().getLength() != 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle visible = getVisibleRect();
        int mid = visible.x + visible.width / 2, top = visible.y + Math.max(65, visible.height / 2 - 36);
        g.setColor(UIManager.getColor("Label.disabledForeground"));
        g.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g.drawString(heading, mid - g.getFontMetrics().stringWidth(heading) / 2, top);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        if (g.getFontMetrics().stringWidth(detail) < visible.width - 24)
            g.drawString(detail, mid - g.getFontMetrics().stringWidth(detail) / 2, top + 28);
        g.dispose();
    }
}
