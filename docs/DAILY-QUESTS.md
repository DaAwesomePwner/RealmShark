# Daily Quest planner

Enter the Daily Quest Room during capture to load the server's current quest list.

- Search by quest, reward, mark, or token name.
- Filter to any quest chest, Mighty, Epic, Standard, Beginner, or an exact captured reward.
- Select a reward and sort by **Fewest required items** to compare turn-in requirements. This counts items, not dungeon difficulty or farming time.
- Click column headers to sort, or use the sort selector for quest type, reward name, quest name, and pins.
- Pin desired quests locally and enable **Pinned only** to make a shortlist.
- Select a quest to see its description, captured expiration, repeated-item quantities, and all requirements. **Choose one** rewards are explicitly distinguished from receiving every listed item.
- Completed one-time quests are hidden unless **Show completed** is enabled. Repeatable quests remain visible.

## Daily and Event labels

The captured packet contains a numeric category with no label, and this checkout has no verified mapping from category numbers to in-game tabs. **Name types…** lists each captured category with an example quest. Match those groups to Daily, Event, Utility, Epic, or a custom label once; labels are saved locally and become available for filtering and sorting. Unlabeled categories keep their original numbers.

Quest type is never inferred from repeatability, chest name, or expiration. An event can award a standard chest.

## Scope

This view does not submit quests, consume items, request account data, or check inventory ownership. Item names and art come from local assets, with explicit item-ID fallbacks when missing. Rewards are the captured turn-in rewards; the tool does not estimate chest loot value. Pins and category labels use local Java preferences.

Validation: regression coverage includes repeated quantities, choice rewards, completed/repeatable states, numeric sorting, reward/type/search filters, pins, unknown assets, safe capture-thread snapshots, and compact/desktop rendering.
