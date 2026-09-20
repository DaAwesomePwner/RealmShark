import java.io.DataInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Reads a built fat JAR in isolation; never starts the application, capture, or a window. */
public final class BuildContractProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: BuildContractProbe.java <jar> <product-version> <upstream-version> <cache-version> <UnityPy-notice>");
        }
        require(Runtime.version().feature() == 17, "Build verification requires JDK 17");
        Path jarPath = Path.of(args[0]).toRealPath();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            require(jar.getManifest() != null && "realmshark.RealmShark".equals(
                    jar.getManifest().getMainAttributes().getValue("Main-Class")), "Incorrect JAR entrypoint");
            for (String name : List.of("realmshark/version/Version.class", "tomato/version/Version.class",
                    "realmshark/branding/AppIdentity.class", "realmshark/RealmShark.class")) {
                verifyJava8Class(jar, name);
            }
            String noticeName = "META-INF/licenses/UNITYPY-LICENSE.txt";
            JarEntry notice = uniqueEntry(jar, noticeName);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (var input = jar.getInputStream(notice)) {
                require(Arrays.equals(sha.digest(Files.readAllBytes(Path.of(args[4]))), sha.digest(input.readAllBytes())),
                        "Packaged UnityPy notice differs from its source");
            }
        }

        // A platform parent cannot supply project classes from the build/test classpath.
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> product = load(loader, jarPath, "realmshark.version.Version");
            equal(args[1], product.getField("VERSION").get(null), "Generated product version");
            Class<?> identity = load(loader, jarPath, "realmshark.branding.AppIdentity");
            equal(args[1], identity.getMethod("version").invoke(null), "Inlined AppIdentity version");
            equal("RealmShark " + args[1], identity.getMethod("title").invoke(null), "AppIdentity title");
            Class<?> upstream = load(loader, jarPath, "tomato.version.Version");
            equal(args[2], upstream.getField("VERSION").get(null), "Upstream baseline");
            equal(args[3], upstream.getField("ASSET_CACHE_VERSION").get(null), "Asset-cache version");
        }
        System.out.println("PASS: " + jarPath + " product=" + args[1] + ", upstream=" + args[2] + ", cache=" + args[3]
                + "; isolated identity, unique Java 8 classes, and UnityPy notice verified.");
    }

    private static JarEntry uniqueEntry(JarFile jar, String name) {
        List<JarEntry> entries = jar.stream().filter(entry -> entry.getName().equals(name)).toList();
        require(entries.size() == 1, "Expected exactly one JAR entry: " + name + ", found " + entries.size());
        return entries.get(0);
    }

    private static void verifyJava8Class(JarFile jar, String name) throws Exception {
        JarEntry entry = uniqueEntry(jar, name);
        require(jar.stream().noneMatch(candidate -> candidate.getName().matches("META-INF/versions/[0-9]+/" + Pattern.quote(name))),
                "Unexpected multi-release replacement for " + name);
        try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
            require(input.readInt() == 0xCAFEBABE, "Invalid class header: " + name);
            input.readUnsignedShort();
            require(input.readUnsignedShort() == 52, "Expected Java 8 class version 52: " + name);
        }
    }

    private static Class<?> load(ClassLoader loader, Path jar, String name) throws Exception {
        Class<?> type = Class.forName(name, true, loader);
        require(type.getClassLoader() == loader && Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),
                "Class did not come from the requested JAR: " + name);
        return type;
    }

    private static void equal(String expected, Object actual, String label) {
        require(expected.equals(actual), label + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
