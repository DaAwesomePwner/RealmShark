package tomato.history.archive;

import java.util.Objects;

/** Origin survives projection and paging. A storage locator is not a cross-module identity. */
public final class ArchiveRow<R> {
    public final Ref ref;
    public final R value;
    public ArchiveRow(Ref ref, R value) { this.ref = Objects.requireNonNull(ref); this.value = Objects.requireNonNull(value); }
    public <T> ArchiveRow<T> project(T projected) { return new ArchiveRow<>(ref, projected); }
    public <T> ArchiveRow<T> child(String key, T projected) { return new ArchiveRow<>(ref.child(key), projected); }

    public static final class Ref implements Comparable<Ref> {
        public final String session, module, locator, child;
        public Ref(String session, String module, String locator, String child) {
            if(session==null||session.isEmpty()||module==null||module.isEmpty()||locator==null||locator.isEmpty()||child==null)
                throw new IllegalArgumentException("Record reference requires session, module, locator and child");
            this.session=session;this.module=module;this.locator=locator;this.child=child;
        }
        public static Ref copyOf(Ref value) {
            if(value==null)throw new IllegalArgumentException("Null selected record reference");
            return new Ref(value.session,value.module,value.locator,value.child);
        }
        public Ref child(String key) { Objects.requireNonNull(key);return new Ref(session, module, locator, child + "/" + key.length() + ":" + key); }
        @Override public int compareTo(Ref other) {
            int c = session.compareTo(other.session); if (c != 0) return c;
            c = module.compareTo(other.module); if (c != 0) return c;
            c = locator.compareTo(other.locator); return c != 0 ? c : child.compareTo(other.child);
        }
        @Override public boolean equals(Object other) { return other instanceof Ref && compareTo((Ref) other) == 0; }
        @Override public int hashCode() { return Objects.hash(session, module, locator, child); }
        @Override public String toString() { return session + "/" + module + "/" + locator + child; }
    }
}
