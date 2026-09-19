package tomato.gui.modern;

import realmshark.branding.FinIcon;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;

/** Small vector icons: crisp at any desktop scale and independent of game assets. */
public final class LineIcon implements Icon {
    private final int type;
    private final int size;
    public LineIcon(int type) { this(type, 18); }
    public LineIcon(int type, int size) { this.type = type; this.size = size; }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.scale(size / 22.0, size / 22.0);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c.getForeground());
        switch (type) {
            case 0: g.drawRoundRect(3, 3, 16, 13, 5, 5); g.drawLine(6, 16, 6, 20); g.drawLine(6, 20, 11, 16); g.drawLine(7, 8, 15, 8); g.drawLine(7, 12, 12, 12); break;
            case 1: g.drawOval(3, 3, 8, 8); g.drawLine(10, 10, 19, 19); g.drawLine(14, 14, 17, 11); g.drawLine(17, 17, 20, 14); break;
            case 2: path(g, 11, 2, 19, 5, 18, 13, 15, 18, 11, 21, 7, 18, 4, 13, 3, 5, 11, 2); g.drawLine(8, 11, 10, 13); g.drawLine(10, 13, 15, 8); break;
            case 3: g.drawOval(7, 2, 8, 8); g.drawArc(3, 12, 16, 16, 0, 180); break;
            case 4: g.drawLine(3, 19, 20, 19); g.drawLine(5, 15, 5, 10); g.drawLine(11, 15, 11, 4); g.drawLine(17, 15, 17, 7); break;
            case 5: g.drawRoundRect(4, 4, 14, 16, 3, 3); g.drawRoundRect(8, 2, 6, 4, 2, 2); g.drawLine(8, 10, 14, 10); g.drawLine(8, 15, 14, 15); break;
            case 6: g.drawOval(3, 3, 16, 16); g.drawLine(11, 10, 11, 15); g.fillOval(10, 6, 2, 2); break;
            case 7: path(g, 2, 12, 6, 12, 9, 4, 13, 19, 16, 10, 20, 10); break;
            case 9: path(g, 7, 3, 15, 3, 13, 7, 18, 13, 18, 18, 15, 20, 7, 20, 4, 18, 4, 13, 9, 7, 7, 3); g.drawLine(8, 8, 14, 8); break;
            case 10: g.drawRoundRect(2, 3, 18, 16, 3, 3); path(g, 5, 7, 8, 10, 5, 13); g.drawLine(11, 14, 17, 14); break;
            case 11: g.drawOval(2, 3, 5, 5); path(g, 7, 5, 17, 5, 19, 10, 5, 14, 5, 19); g.drawOval(3, 17, 4, 4); break;
            case 12: g.drawLine(4, 2, 4, 20); for(int y2 : new int[]{5,11,17}) { g.fillOval(2,y2-2,4,4); g.drawLine(9,y2,19,y2); } break;
            case 13: g.drawLine(2,16,20,16); g.drawLine(5,5,5,20);g.drawLine(17,5,17,20);g.drawArc(5,2,12,18,180,180);break;
            case 14: path(g, 4, 8, 4, 16, 18, 16, 18, 8); g.drawArc(4, 2, 14, 13, 0, 180); g.drawArc(8, 16, 6, 5, 180, 180); break;
            case 8:
            default: FinIcon.paintOutline(g, 0, 0, 22, c.getForeground()); break;
        }
        g.dispose();
    }
    private static void path(Graphics2D g, int... xy) {
        Path2D p = new Path2D.Float(); p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        g.draw(p);
    }
}
