package tomato.gui.loot;

import java.util.Objects;

/**
 * Route payload for {@code Destination.LOOT}: the Loot tab a route brings forward ({@link LootPage#tabTarget()}), the way
 * {@code RunsFocus} names a Runs & DPS tab. Plain, visit and query routes stay the Loot workspace's own targets (they bring
 * Explore forward through {@link LootPage#routes}); the tab target takes only a focus with no other reference. Immutable and
 * detached.
 */
public record LootFocus(LootTab tab) {
    public LootFocus { Objects.requireNonNull(tab, "tab"); }
}
