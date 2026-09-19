package util;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

/** Local application preferences. Writers are closed before subsequent updates. */
public class PropertiesManager {
    private static final Properties properties = new Properties();
    static {
        try (FileReader reader = new FileReader("realmShark.properties")) { properties.load(reader); }
        catch (IOException ignored) { }
    }
    public static synchronized void setProperties(String name, String value) {
        properties.setProperty(name, value);
        try (FileWriter writer = new FileWriter("realmShark.properties")) { properties.store(writer, "RealmShark properties"); }
        catch (IOException e) { System.err.println("Could not save application preferences: " + e.getMessage()); }
    }
    public static synchronized String getProperty(String name) { return properties.getProperty(name); }
}
