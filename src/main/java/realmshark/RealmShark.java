package realmshark;

import realmshark.branding.AppIdentity;

/** Public launcher; the implementation namespace remains compatible with persisted data. */
public final class RealmShark {
    private RealmShark() { }

    public static void main(String[] args) {
        AppIdentity.initialize();
        tomato.Tomato.main(args);
    }
}
