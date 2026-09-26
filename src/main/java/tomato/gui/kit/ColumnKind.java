package tomato.gui.kit;

import java.awt.Font;
import javax.swing.SwingConstants;

/** The same kind of data gets the same width and alignment in every table. Widths are in em of the table font. */
public enum ColumnKind {
    TIME_RELATIVE(7f, SwingConstants.LEFT),
    DATE_TIME(11f, SwingConstants.LEFT),
    DURATION(6f, SwingConstants.RIGHT),
    DUNGEON(13f, SwingConstants.LEFT),
    PLAYER(11f, SwingConstants.LEFT),
    CLASS(8f, SwingConstants.LEFT),
    ITEM(15f, SwingConstants.LEFT),
    COUNT(5f, SwingConstants.RIGHT),
    NUMBER(6.5f, SwingConstants.RIGHT),
    PERCENT(5f, SwingConstants.RIGHT),
    STATUS(9f, SwingConstants.CENTER),
    ID(8f, SwingConstants.LEFT),
    TEXT(20f, SwingConstants.LEFT);

    /** ContentStyle.Cell pads 8 px on each side. */
    static final int PADDING = 16;

    public final float em;
    public final int alignment;

    ColumnKind(float em, int alignment) { this.em = em; this.alignment = alignment; }

    public int width(Font font) { return Math.round(em * font.getSize2D()) + PADDING; }
}
