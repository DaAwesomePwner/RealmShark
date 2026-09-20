package tomato.gui.modern;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import javax.swing.JLabel;
import javax.swing.JTable;

/** Access the actual components/renderers without replacing numeric models with formatted strings. */
public final class FormattingTestSupport {
    private FormattingTestSupport() {}

    public static String cell(JTable table, int row, int column) {
        return ((JLabel)table.prepareRenderer(table.getCellRenderer(row, column), row, column)).getText();
    }

    public static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (name == null || name.equals(child.getName()))) return type.cast(child);
            if (child instanceof Container) {
                T found = named((Container)child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
