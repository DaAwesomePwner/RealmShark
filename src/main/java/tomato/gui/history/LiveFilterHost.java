package tomato.gui.history;

import tomato.gui.kit.FilterBar;

/**
 * A live panel whose own {@link FilterBar} hosts its workspace's Scope chip while the workspace is live, so the page keeps one
 * filter row: {@link ArchiveWorkspace} lends the chip to this bar's scope slot (or, when the bar is narrow, to the end of its
 * search slot) and hides its own bar until saved history is shown. Implement it on the live component passed to the workspace.
 * <ul>
 *   <li>The bar must be inside the host, so the chip stays a descendant of the workspace.</li>
 *   <li>The bar's scope slot is the chip's while live: do not put other controls there.</li>
 *   <li>Pass a panel (a {@link WrapRow}) to {@link FilterBar#search}: the narrow fallback and
 *       {@link ArchiveWorkspace#lead} add to it. Calling {@code search(...)} again on the host bar with another component drops
 *       whatever the workspace lent to the old one; fire {@link #BAR} after such a change so the workspace places them again.</li>
 * </ul>
 * EDT only.
 */
public interface LiveFilterHost {
    /** The property the host (a JComponent) fires when {@link #liveFilterBar()} changes, for example on a tab switch. */
    String BAR = "liveFilterBar";

    /** The live filter row that hosts the chip; null when the current tab has none (the workspace shows its own row). */
    FilterBar liveFilterBar();
}
