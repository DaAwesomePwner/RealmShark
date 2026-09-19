package ui;

import java.awt.Component;
import java.awt.Container;

/** Completes width-dependent layout before synchronous screenshot assertions on the EDT. */
public final class UiTestLayout {
    private UiTestLayout() {}

    public static void settle(Container root) {
        for (int pass = 0; pass < 3; pass++) {
            invalidate(root);
            root.validate();
        }
    }

    private static void invalidate(Container root) {
        for (Component child : root.getComponents())
            if (child instanceof Container) invalidate((Container) child);
        root.invalidate();
    }
}
