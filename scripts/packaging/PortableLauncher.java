import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;

/** Gives relative assets/settings paths a stable location, even from a shortcut. */
public final class PortableLauncher {
    public static void main(String[] args) {
        try {
            Path root = Paths.get(System.getProperty("jpackage.app-path")).toAbsolutePath().normalize().getParent();
            List<String> command = new ArrayList<>();
            command.add(root.resolve("runtime/bin/javaw.exe").toString());
            command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            command.add("-Drealmshark.launcher=" + root.resolve("RealmShark.exe"));
            command.add("-Drealmshark.icon=" + root.resolve("app/RealmShark.ico"));
            command.add("-jar");
            command.add(root.resolve("app/RealmShark-v1.2.3.jar").toString());
            command.addAll(Arrays.asList(args));
            Process child = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
            System.exit(child.waitFor());
        } catch (Exception error) {
            JOptionPane.showMessageDialog(null,
                "Unable to start RealmShark: " + error.getMessage()
                    + "\nExtract the entire ZIP to a writable folder and keep app and runtime beside RealmShark.exe.",
                "RealmShark startup", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
