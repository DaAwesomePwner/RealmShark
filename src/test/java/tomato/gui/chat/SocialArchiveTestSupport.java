package tomato.gui.chat;

import java.awt.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;
import static org.junit.Assert.*;

public final class SocialArchiveTestSupport {
    private SocialArchiveTestSupport() { }
    public interface Task<T> { T get() throws Exception; }
    public static <T> T edt(Task<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(task.get()); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return result.get();
    }
    public static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < until) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for the queried workspace");
    }
    public static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c,name,type); if (found != null) return found; }
        } return null;
    }
    public static AbstractButton button(Container root,String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton)c).getText())) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c,text); if(found != null)return found; }
        }return null;
    }
}
