package tomato.gui.modern;

import realmshark.branding.FinIcon;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.*;

/** Small vector icons: crisp at any desktop scale and independent of game assets. */
public final class LineIcon implements Icon {
    public static final int INFO = 6, HOME = 15, SWORDS = 16, DIAMOND = 17, CHECKLIST = 18, GEAR = 19, FILTER = 20,
        DOTS = 21, CHEVRON_DOWN = 22, CHEVRON_RIGHT = 23, PIN = 24, STAR = 25, HOURGLASS = 26, REFRESH = 27,
        PENCIL = 28, CLOCK = 29, CLOSE = 30, SEARCH = 31, GRIP = 32;

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
            case HOME: path(g, 3, 11, 11, 4, 19, 11); path(g, 5, 10, 5, 19, 17, 19, 17, 10); path(g, 9, 19, 9, 14, 13, 14, 13, 19); break;
            case SWORDS: g.drawLine(4, 4, 18, 18); g.drawLine(18, 4, 4, 18); g.drawLine(3, 14, 8, 19); g.drawLine(14, 19, 19, 14); break;
            case DIAMOND: path(g, 7, 4, 15, 4, 19, 9, 11, 19, 3, 9, 7, 4); g.drawLine(3, 9, 19, 9); break;
            case CHECKLIST: for (int row : new int[]{6, 11, 16}) { path(g, 3, row, 5, row + 2, 8, row - 2); g.drawLine(11, row, 19, row); } break;
            case GEAR:
                g.drawOval(8, 8, 6, 6);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    g.drawLine((int) Math.round(11 + 5 * Math.cos(a)), (int) Math.round(11 + 5 * Math.sin(a)),
                        (int) Math.round(11 + 8 * Math.cos(a)), (int) Math.round(11 + 8 * Math.sin(a)));
                }
                break;
            case FILTER: path(g, 3, 4, 19, 4, 13, 11, 13, 18, 9, 16, 9, 11, 3, 4); break;
            case DOTS: g.fillOval(4, 10, 3, 3); g.fillOval(10, 10, 3, 3); g.fillOval(16, 10, 3, 3); break;
            case CHEVRON_DOWN: path(g, 6, 9, 11, 14, 16, 9); break;
            case CHEVRON_RIGHT: path(g, 9, 6, 14, 11, 9, 16); break;
            case PIN: g.drawOval(7, 3, 8, 8); g.drawLine(11, 11, 11, 19); break;
            case STAR: {
                Path2D star = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + Math.PI * i / 5, r = i % 2 == 0 ? 9 : 4;
                    if (i == 0) star.moveTo(11 + r * Math.cos(a), 11.5 + r * Math.sin(a));
                    else star.lineTo(11 + r * Math.cos(a), 11.5 + r * Math.sin(a));
                }
                star.closePath(); g.draw(star);
                break;
            }
            case HOURGLASS: path(g, 6, 3, 16, 3, 11, 11, 16, 19, 6, 19, 11, 11, 6, 3); break;
            case REFRESH: g.drawArc(4, 4, 14, 14, 30, 290); path(g, 15, 2, 17, 6, 13, 7); break;
            case PENCIL: path(g, 4, 18, 5, 14, 15, 4, 18, 7, 8, 17, 4, 18); g.drawLine(13, 6, 16, 9); break;
            case CLOCK: g.drawOval(3, 3, 16, 16); path(g, 11, 6, 11, 11, 15, 13); break;
            case CLOSE: g.drawLine(6, 6, 16, 16); g.drawLine(16, 6, 6, 16); break;
            case SEARCH: g.drawOval(4, 4, 11, 11); g.drawLine(13, 13, 18, 18); break;
            case GRIP: for (int gx : new int[]{8, 13}) for (int gy : new int[]{5, 10, 15}) g.fillOval(gx, gy, 2, 2); break;
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
