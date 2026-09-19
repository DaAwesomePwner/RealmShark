import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

/** CI preflight: checks the real desktop without changing its display mode. */
public class CheckUiDisplay {
    private static final Dimension REQUIRED = new Dimension(1240, 800);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: CheckUiDisplay.java <expected-scale>");
        double scale = Double.parseDouble(args[0]);
        if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Expected scale must be positive and finite");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A visible desktop is required; AWT is headless");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle usable = usableBounds(gc);
        System.out.println("UI display: java=" + System.getProperty("java.version") + ", expectedScale=" + scale
            + ", requested=" + REQUIRED + ", " + describe(gc));
        if (!hasScale(gc, scale)) throw new IllegalStateException("AWT transform does not match the requested scale: " + describe(gc));
        if (usable.width < REQUIRED.width || usable.height < REQUIRED.height) {
            throw new IllegalStateException("Usable logical desktop cannot contain a 1240x800 window: " + usable);
        }

        JFrame[] window = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("RealmShark CI display probe", gc);
                window[0] = frame;
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                JMenuBar menu = new JMenuBar(); menu.add(new JMenu("File")); frame.setJMenuBar(menu);
                frame.setSize(REQUIRED);
                frame.setLocation(usable.x + (usable.width - REQUIRED.width) / 2,
                    usable.y + (usable.height - REQUIRED.height) / 2);
                frame.setVisible(true);
                frame.validate();
            });
            Robot robot = new Robot(gc.getDevice());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long stableSince = 0;
            boolean[] matches = {false};
            String[] evidence = {"No window geometry received"};
            while (System.nanoTime() < deadline) {
                robot.waitForIdle();
                SwingUtilities.invokeAndWait(() -> {
                    JFrame frame = window[0];
                    frame.validate();
                    GraphicsConfiguration actual = frame.getGraphicsConfiguration();
                    evidence[0] = "frame=" + frame.getBounds() + ", content=" + frame.getContentPane().getSize()
                        + ", frameInsets=" + frame.getInsets() + ", " + describe(actual);
                    matches[0] = frame.isShowing() && !frame.isUndecorated() && frame.isValid()
                        && frame.getSize().equals(REQUIRED) && hasScale(actual, scale)
                        && actual.getDevice().equals(gc.getDevice()) && usableBounds(actual).contains(frame.getBounds());
                });
                long now = System.nanoTime();
                if (!matches[0]) stableSince = 0;
                else if (stableSince == 0) stableSince = now;
                else if (now - stableSince >= TimeUnit.MILLISECONDS.toNanos(500)) {
                    System.out.println("PASS " + scale + "x: " + evidence[0]);
                    return;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("1240x800 decorated window did not settle inside the usable desktop at "
                + scale + "x: " + evidence[0]);
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (window[0] != null) window[0].dispose(); });
        }
    }

    private static boolean hasScale(GraphicsConfiguration gc, double expected) {
        AffineTransform transform = gc.getDefaultTransform();
        return Math.abs(transform.getScaleX() - expected) < .001 && Math.abs(transform.getScaleY() - expected) < .001;
    }

    private static Rectangle usableBounds(GraphicsConfiguration gc) {
        Rectangle bounds = new Rectangle(gc.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
    }

    private static String describe(GraphicsConfiguration gc) {
        return "device=" + gc.getDevice().getIDstring() + ", transform=" + gc.getDefaultTransform()
            + ", screen=" + gc.getBounds() + ", screenInsets=" + Toolkit.getDefaultToolkit().getScreenInsets(gc)
            + ", usable=" + usableBounds(gc);
    }
}
