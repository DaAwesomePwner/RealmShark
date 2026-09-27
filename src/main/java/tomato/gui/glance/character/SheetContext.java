package tomato.gui.glance.character;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.DisplayModeModel;
import tomato.planning.PlanningStore;

/**
 * What a character sheet reads:
 * - live data, for the character being played
 * - the journal, item and cap definitions, and the display mode
 * - the clock for snapshot ages, and the goals store
 * The four-argument form uses the system clock and the shared goals store.
 */
public record SheetContext(TomatoData data, CharacterJournal journal, Supplier<RosterDefinitions> definitions, DisplayModeModel mode,
                           LongSupplier clock, PlanningStore plans) {
    public SheetContext {
        Objects.requireNonNull(data, "data"); Objects.requireNonNull(journal, "journal"); Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(clock, "clock"); Objects.requireNonNull(plans, "plans");
    }

    public SheetContext(TomatoData data, CharacterJournal journal, Supplier<RosterDefinitions> definitions, DisplayModeModel mode) {
        this(data, journal, definitions, mode, System::currentTimeMillis, PlanningStore.shared());
    }
}
