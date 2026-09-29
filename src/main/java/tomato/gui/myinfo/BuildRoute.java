package tomato.gui.myinfo;

import java.util.Objects;
import java.util.function.Supplier;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * The Build route (Destination.MY_INFO). Build is a tab on the character sheet (spec §6.2) and has no page of its own (P6a
 * removed the pointer page), so a plain MY_INFO route redirects to the sheet's Build tab. The character is the one in game when
 * the journal has it, else the journal's most recent. With no character at all it redirects to the Characters list, whose
 * gallery says "No characters yet". EDT only.
 */
public final class BuildRoute implements RouteTarget {
    private final Supplier<String> key;

    public BuildRoute(Supplier<String> key) { this.key = Objects.requireNonNull(key, "key"); }

    /** The journal key whose Build to open, or null when there is no character at all. Any thread. */
    public static String key(TomatoData data) {
        if (data == null) return null;
        CharacterJournal journal = data.characterJournal();
        LiveCharacter.Snapshot live = data.liveCharacter.current();
        if (live != null && live.account() != null && live.characterId() >= 0) {
            String liveKey = live.account() + ":" + live.characterId();
            if (journal.characterCopy(liveKey) != null) return liveKey;
        }
        CharacterJournal.CharacterRecord recent = journal.mostRecentCharacter();
        return recent == null ? null : recent.key;
    }

    /** The character sheet of {@code key}, on its Build tab. */
    public static Route sheet(String key) { return Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, "build")); }

    @Override public Destination destination() { return Destination.MY_INFO; }

    @Override public boolean accepts(Route route) {
        return route.destination == Destination.MY_INFO && route.query == null && route.visit == null && route.record == null
            && route.recordingId == null && route.payload == null && route.from == null && route.until == null;
    }

    /** The sheet's Build tab for the current character, or the Characters list when there is none. */
    @Override public Route redirect(Route route) {
        String target = key.get();
        return target == null ? Route.to(Destination.CHARACTERS) : sheet(target);
    }

    // This target has no view: its route always redirects, so there is nothing to capture, open or restore.
    @Override public Object captureState() { return null; }
    @Override public void open(Route route) { }
    @Override public void restoreState(Object state) { }
}
