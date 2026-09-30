package ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/** P6b Polish E: a root-pane capture shows what the screen shows, including a table's selected row. */
public class VisualEvidenceTest {
    private static final String FOLDER = "p6b-polish-e";
    @Rule public final VisualEvidence evidence = new VisualEvidence(FOLDER);

    /**
     * Swing's JTable draws no selection while printing ({@code prepareRenderer} checks {@code isPaintingForPrint}), so a capture that
     * prints the root pane showed every selected row as unselected.
     */
    @Test public void aRootCaptureShowsTheSelectedTableRow() throws Exception {
        JTable[] table = new JTable[1];
        SwingUtilities.invokeAndWait(() -> {
            table[0] = new JTable(new Object[][]{{"Alpha", "1"}, {"Bravo", "2"}, {"Charlie", "3"}}, new Object[]{"Name", "Count"});
            table[0].setRowSelectionInterval(1, 1);
            JPanel content = new JPanel(new BorderLayout());
            content.add(new JScrollPane(table[0]), BorderLayout.CENTER);
            evidence.show(content, "Selection capture", 420, 240, 13);
        });
        evidence.settle();
        Point[] at = new Point[2];
        Color[] selection = new Color[2];
        SwingUtilities.invokeAndWait(() -> {
            evidence.captureRoot("capture-root-selection");
            JRootPane root = table[0].getRootPane();
            // A point near the right end of each row's first cell, clear of its left-aligned text.
            for (int row = 0; row < 2; row++) {
                Rectangle cell = table[0].getCellRect(row == 0 ? 1 : 0, 0, false);
                at[row] = SwingUtilities.convertPoint(table[0], new Point(cell.x + cell.width - 4, cell.y + cell.height / 2), root);
            }
            selection[0] = table[0].getSelectionBackground();
            selection[1] = UIManager.getColor("Table.selectionInactiveBackground");
        });
        BufferedImage image = ImageIO.read(new File(new File("screenshots", FOLDER), "capture-root-selection.png"));
        assertNotNull("The capture was written", image);
        int selected = image.getRGB(at[0].x, at[0].y) & 0xFFFFFF, unselected = image.getRGB(at[1].x, at[1].y) & 0xFFFFFF;
        assertNotEquals("The selected row differs from an unselected one", unselected, selected);
        assertTrue(String.format("The selected row shows a selection background (#%06X; active #%06X, inactive %s)", selected,
                selection[0].getRGB() & 0xFFFFFF, selection[1] == null ? "none" : String.format("#%06X", selection[1].getRGB() & 0xFFFFFF)),
            Arrays.asList(selection[0], selection[1]).stream().anyMatch(color -> color != null && (color.getRGB() & 0xFFFFFF) == selected));
    }
}
