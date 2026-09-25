package tomato.gui.keypop;

import java.time.Instant;
import tomato.history.SessionStore;

/** Package-local event construction for the real cross-module native factory fixture. */
public final class KeyPopNativeFixtures {
    private KeyPopNativeFixtures() {}
    public static void seed(SessionStore source, Instant base, int session) {
        for (int row = 0; row < 560; row++) source.append("keypops",new KeyPopEvent(base.plusSeconds(session * 1000L + row),
            row < 550 ? "Ann" : "Anna",session == 0 ? "Lost Halls" : "Ice Citadel",row % 2 == 0 ? KeyPopEvent.Kind.KEY : KeyPopEvent.Kind.VIAL));
    }
}
