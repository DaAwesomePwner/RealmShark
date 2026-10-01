package tomato.history.index;

/** Immutable, in-memory names for one projection generation. Unknown IDs return null. */
public interface IndexDictionary {
    IndexDictionary NONE = new IndexDictionary() {
        public String version() { return "none"; }
        public String objectName(int id) { return null; }
        public String className(int id) { return null; }
        public String enchantName(int id) { return null; }
    };
    String version();
    String objectName(int id);
    String className(int id);
    String enchantName(int id);
}
