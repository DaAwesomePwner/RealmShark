# Daily Quest planner

Enter the Daily Quest Room during capture to load the server's current quest list.

- Search by quest, reward, mark, or token name.
- Filter to any quest chest, Mighty, Epic, Standard, Beginner, or an exact captured reward.
- Select a reward and sort by **Fewest required items** to compare turn-in requirements. This counts items, not dungeon difficulty or farming time.
- Click column headers to sort, or use the sort selector for quest type, reward name, quest name, and pins.
- Pin desired quests locally and enable **Pinned only** to make a shortlist.
- Select a quest to see its description, captured expiration, repeated-item quantities, and all requirements. **Choose one** rewards are explicitly distinguished from receiving every listed item.
- Completed one-time quests are hidden unless **Show completed** is enabled. Repeatable quests remain visible.

Compact windows and enlarged fonts use wrapping filters and explanations, horizontal table scrolling, and an outer page scroll. The roster and details reserve usable content space, and keyboard focus reveals controls through nested viewports. Selection and Pin/Unpin follow the quest ID when incoming data changes row order. When no quests remain visible, obsolete details are cleared and Pin is disabled.

## Daily and Event labels

The captured packet contains a numeric category with no label, and this checkout has no verified mapping from category numbers to in-game tabs. **Name types…** lists each captured category with an example quest. Match those groups to Daily, Event, Utility, Epic, or a custom label once; labels are saved locally and become available for filtering and sorting. Unlabeled categories keep their original numbers.

Quest type is never inferred from repeatability, chest name, or expiration. An event can award a standard chest.

The category form scrolls for long names or many categories, with keyboard-reachable editors and OK/Cancel. Cancel keeps the prior labels. Static explanatory labels are not extra keyboard stops; detailed text remains readable through the scrollable view. Pins and labels retain the existing Java Preferences node and keys.

## Scope

This view does not submit quests, consume items, request account data, or check inventory ownership. Item names and art come from local assets, with explicit item-ID fallbacks when missing. Rewards are the captured turn-in rewards; the tool does not estimate chest loot value. Pins and category labels use local Java preferences.

Validation includes the existing functional regressions plus exact in-shell logical sizes 1240×800 and 680×520, fonts 13/16/24, complete long-text endpoints, row/detail minimums, sorted update/pin identity, category dialog OK/Cancel, native realized geometry, and posted-key traversal. The Quest consistency tests run in the local 150%/200% suites. Native host clamps are reported rather than treated as exact-size passes. See [Phase 4 evidence](STEP-4-CLEANUP.md).
