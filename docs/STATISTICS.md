# Statistics

The four Statistics tabs use the existing dark violet theme, sortable tables, summary cards, explicit filter scopes and empty states. Short windows scroll vertically; wide tables scroll horizontally so names and numeric columns remain readable.

## Fame Graph

- Follow the current character or pin a character using the selector.
- Show all samples or the latest 1, 5, 15, 30 or 60 minutes of that character's recorded history.
- Switch between total fame and gain within the selected range. Drag across the step chart to compare an interval.
- Cards report gain, sample span, fame/hour and sample count. The latest sample timestamp remains visible below the chart.

Ranges end at the selected character's latest sample, not the computer clock. Only actual samples are included: no fame is interpolated at a range boundary. Graph rates use elapsed sample time, which can include time spent on other characters or away from the game. They are not combat-uptime measurements. Flat series and duplicate timestamps are supported; rates with zero duration are unavailable.

## Fame Table

- Search class names or character IDs; show all characters, the current character, or characters with session gain.
- The Characters table shows status, initial/current fame, session gain, observed time and fame/hour. Loaded account characters are labeled **Not observed** until a live sample arrives.
- The Map breakdown supports map search, gain-only filtering, grouping by map, or individual visits with entry timestamps and open/closed status.
- Character filters also constrain map results. Map filters affect only the breakdown; the summary cards describe matching characters.
- The **Sessions** menu keeps New Session, View Saved Sessions and Show Map Fame available. Shift-clicking New Session retains the existing delete-current-file shortcut.

Character rates exclude intervals spent on another observed character. Map attribution updates only the active character, retains zero-gain visits and includes each open visit once. At a map transition, the previous character's known fame can seed the next visit only if the same character continues. A newly observed character starts from its first sample. Open visits end at their latest sample; closed visits also include the known map-exit time. Consequently, map durations and character sample spans can differ. These changes apply to new observations; saved historical sessions are not rewritten.

## Loot

**Explore loot** and the separate Loot workspace share session counters while retaining independent filters. Filter by bag type and dungeon; search narrows each table. Cards follow bag/dungeon scope before text search.

Views include All Items, Stat Potions, Whites, By Bag, Recent Drops, By Dungeon, **UTs**, **STs** and **Tiered**. UTs and STs include only items with the corresponding exact asset label, regardless of bag color. Tiered includes **T13+ weapons and armor** and **T6+ abilities**; UTs, STs, rings and consumables do not enter that view. Tiers come from the item's asset labels.

Every item table separates copies by item ID, unlocked enchant slots and applied enchant count. **Count** is the number of drops of that variant; **Tier**, **Rarity**, **Slots** and **Enchants** describe the variant. For example, an unenchanted UT and a two-slot Rare copy get separate rows. The breakdown below the table totals the visible drops by rarity and follows bag/dungeon filters, the selected tab and text search. Search can match rarity or tier as well as names.

Rarity is derived from unlocked slots: **Common / Unenchanted (0)**, **Uncommon (1)**, **Rare (2)**, **Legendary (3)**, **Divine (4)**. Empty unlocked slots count toward rarity but not applied enchants; locked and unused slots count toward neither. Missing or invalid enchant data stays **Unknown**, separate from confirmed zero-slot drops. Items with empty unlocked slots can therefore have a rarity while showing zero applied enchants. These are captured drop snapshots, not subsequent rerolls or inventory changes.

Recent Drops includes each item's tier, rarity, slots and applied count and offers 5-minute, 15-minute and one-hour ranges relative to the latest captured drop. Its 1,000-bag history limit does not truncate the aggregate session totals, including enchantment breakdowns. Dates sort chronologically. Whites means contents observed in white or boosted white bags, not an inferred item rarity.

**Live log** shows rarity, unlocked slots and applied enchant counts in item tooltips, alongside enchant descriptions. Icon glow follows unlocked slot count. Its bag visibility still follows **Edit > Filter Loot**. Explorer filters do not change capture, sounds, or sharing settings. Counts represent observed drops, not inventory pickups. Loot explorer totals are retained for the current app session only; restart the rebuilt app to begin recording the new breakdowns.

## Dungeon Stats

Search saved dungeon history and filter to dungeons with loot or recorded visits. Select a dungeon, then open Enemies or Loot by source (double-clicking a dungeon opens Enemies). Detail search and the dropper selector refine those views. Unknown sources and sources with loot but no hit counter remain visible.

Counters come from the existing `dungeon.stats` format. Hit events are not kills or proof of soulbound credit. Recorded visits and time finalize on exit, and maps without tracked activity can be absent. Ongoing item/hit counters may therefore be ahead of visit/time totals. This cumulative file has no dates or per-run outcomes, so date filters, completion rates and drop probabilities are deliberately unavailable. The UI reads copies of the counters under the capture lock; redraws are coalesced while visible.

## Validation

`LootEquipmentTest` covers exact-label UT/ST classification, tier thresholds, per-variant counts, slot-aligned capture snapshots, shared views, filtered rarity totals and retention beyond 1,000 bags. `ParseEnchantsSummaryTest` checks empty, locked, unused, unknown-ID and malformed enchant data without requiring assets. `StatisticsExplorerTest` covers literal searches, numeric/chronological sorting, character switches, graph ranges, map attribution, open/zero-gain visits, shared loot views, loot-only dungeon sources, and populated screenshots at desktop and compact sizes. Existing loot, workspace and capture regression tests also run.

This checkout provides `scripts/statistics-validation.gradle` to direct Gradle output to `build/statistics-validation` when older build outputs have Windows ownership conflicts. Build and test with JDK 17:

```powershell
$env:JAVA_HOME = Join-Path $PWD '.tools\jdk-17.0.20.1+1'
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools\gradle-home'
& .\.tools\gradle-7.6.4\bin\gradle.bat -I scripts/statistics-validation.gradle test shadowJar --offline --no-daemon --console=plain
```

Use `Launch-RealmShark.cmd` for normal use so the running app reads an immutable runtime copy. Preview mode exercises the UI without capture. Synthetic screenshot fixtures and automated tests do not establish live gameplay accuracy.
