# Statistics

The four Statistics tabs use the existing dark violet theme, sortable tables, summary cards, explicit filter scopes and empty states. Short windows scroll vertically; wide tables scroll horizontally so names and numeric columns remain readable.

Statistics and Loot initially use **Current Session**, defined by the app launch, and remember their independent view state. Their session pickers expose past launches and All Sessions, with session comparisons, historical fame graphs, and per-run/per-hour dungeon loot profiles. History survives new build folders through the shared Windows user profile. See [Session history](SESSION-HISTORY.md) for whole-scope queries, named views and selected/page/all-match exports.

### Saved loot coverage and rates

Session comparisons distinguish **Not captured** loot in run-only imports, **coverage unknown** when a session has no saved bags, and **Partial** coverage when bags were saved. A captured empty bag can establish zero visible items; a missing journal cannot. Saved bags are observations, not proof of continuous recording or item ownership.

Select a dungeon profile to read its numerator units, eligible visits, visits with no linked bags, ongoing visits, observed duration and exclusions. Per-run rates divide observed counts by eligible visits; per-hour rates divide by their observed hours, including gaps. Eligibility requires at least one saved bag in the **same session**, even an empty or unassigned bag. Visits without linked bags still enter the denominator within that evidenced session; run-only imports and sessions without loot evidence are excluded, with counts and unknown-coverage duration shown. Ongoing eligible visits are included.

Rates are unavailable when drops cannot be linked to a matching session, visit and dungeon. Any eligible visit without positive observed duration makes hourly rates unavailable. Verified dungeon aliases are combined; unknown area names stay separate. These sample rates are not drop probabilities.

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

Captured fame updates change the graph and table models together relative to New Session and save boundaries. Tracking continues while Swing is busy or the views are hidden. Presentation updates coalesce and catch up when shown; the table no longer retains a redundant sample series. Graph/session samples and map visits remain available to persistence.

## Saved fame sessions

View Saved Sessions includes every character with saved fame or map records, including zero-gain, negative-gain, map-only, and single-sample histories. Character tables sort numeric values numerically; an em dash identifies missing fame samples.

The character selector controls the graph and map visits. **With fame gain** is an optional map-visit filter, alongside the dungeon selector; it does not remove characters or change graph samples. Switching to a character without fame samples clears the previous graph. Session Info separates unfiltered saved totals from current-view counts. Viewing and filtering do not rewrite the saved file.

## Loot

**Explore loot** and the separate Loot workspace share session counters while retaining independent filters. Open **Multi-select loot facets…**, combine bag types, dungeons, rarity and tier choices with item category and unlocked-slot/applied-enchant ranges, then choose **Apply facets**. Each numeric range has an explicit unknown-value policy. Literal search covers item name/ID, bag, dungeon, dropper, tier and rarity. Filtered counts follow the same item predicates; labels distinguish matching items, variants and bags.

Views include All Items, Stat Potions, Whites, By Bag, Recent Drops, By Dungeon, **UTs**, **STs** and **Tiered**. UTs requires the exact UT label plus a weapon, ability, armor, or ring label, excluding consumables and stat potions. Runes, tokens, skins and other non-tiered items do not enter UTs. STs uses the exact ST label, regardless of bag color. Tiered includes **T13+ weapons and armor** and **T6+ abilities**; UTs, STs, rings and consumables do not enter that view. Tiers come from the item's asset labels.

Every item table separates copies by item ID, unlocked enchant slots and applied enchant count. **Count** is the number of drops of that variant; **Tier**, **Rarity**, **Slots** and **Enchants** describe the variant. For example, an unenchanted UT and a two-slot Rare copy get separate rows. The breakdown below the table totals the visible drops by rarity and follows bag/dungeon filters, the selected tab and text search. Search can match rarity or tier as well as names.

Rarity is derived from unlocked slots: **Common / Unenchanted (0)**, **Uncommon (1)**, **Rare (2)**, **Legendary (3)**, **Divine (4)**. Empty unlocked slots count toward rarity but not applied enchants; locked and unused slots count toward neither. Missing or invalid enchant data stays **Unknown**, separate from confirmed zero-slot drops. Items with empty unlocked slots can therefore have a rarity while showing zero applied enchants. These are captured drop snapshots, not subsequent rerolls or inventory changes.

Live Recent Drops includes each item's tier, rarity, slots and applied count and offers 5-minute, 15-minute and one-hour ranges relative to the latest captured drop. It retains the newest 1,000 bags; recent filters search that window while item aggregates retain the full app session. Saved **Recent Drops** instead queries and pages all matching saved bags, with no 1,000-bag cutoff. Dates sort chronologically. Whites means contents observed in white or boosted white bags, not an inferred item rarity.

In saved Loot, **Item occurrences** shows individual saved item observations, including identical copies in the same bag. Search and facets apply before grouping and paging in every loot view. Counts separately identify occurrences, variants and bags; an explicitly empty bag can contribute to bag counts without inventing an item. Use **Apply dates** for resolved From-inclusive / Until-exclusive bounds, and **Export all matches…** to export beyond the loaded page.

Analytical views describe a different population: Dungeon loot profile and Session comparison use visit scope, dungeon and entry/overlap bounds; item/bag/enchant facets do not narrow their cohort. Character fame uses its own sample dates and character filter, not dungeon/item facets. Undated dungeon/enemy/source counters reject custom periods. **Open selected session's full fame graph** opens the entire pinned session, not just the summary's filtered dates. Undated fame samples remain separately disclosed rather than plotted at epoch zero or used to invent gain/elapsed time.

**Live log** shows rarity, unlocked slots and applied enchant counts in item tooltips, alongside enchant descriptions. Icon glow follows unlocked slot count. Its bag visibility still follows **Edit > Filter Loot**. Explorer filters do not change capture, sounds, or sharing settings. Counts represent observed drops, not inventory pickups. The live explorer shows current-session totals; saved views query recorded drops throughout their selected session scope.

The Statistics Loot panel includes a compact **legacy sharing** status and a Details action. Connection and delivery run on a bounded FIFO worker (256 waiting payloads and one active). **Sent to socket** is a completed local write, not server acceptance. Definite unsent failures/overflow count as dropped; a failure after enqueue is uncertain and is not retried. A definite pre-enqueue rejection permits one reconnect attempt. Opting out clears unsent queued/pending bags and prevents late connection completion from sending them. In-flight bytes cannot be recalled. Local alerts remain independent. This status is separate from Guild Bridge Review.

## Dungeon Stats

Search saved dungeon history and filter to dungeons with loot or **activity-recorded exits**. Select a dungeon, then open Enemies or Loot by source (double-clicking a dungeon opens Enemies). Detail search and the dropper selector refine those views. Unknown sources and sources with loot but no hit counter remain visible.

The default Dungeon Stats tab shows counters recorded during this app launch, excluding the loaded `dungeon.stats` baseline. Historical scopes load saved per-session counters. **Activity-recorded exits** and **Finalized time** count areas with tracked activity at exit; **Runs** instead counts observed visits, including ongoing and zero-activity visits. Hit events are not kills or proof of soulbound credit. Ongoing item/hit counters can precede finalized exit/time totals; the ongoing-activity column distinguishes this from **Not captured** in older snapshots. Legacy counters have no date bounds. The legacy cumulative file remains compatible; session/run records supply the historical comparisons and loot-rate denominators.

## Validation

`LootEquipmentTest` covers exact-label UT/ST classification, tier thresholds, per-variant counts, slot-aligned capture snapshots, shared views, filtered rarity totals and retention beyond 1,000 bags. `ParseEnchantsSummaryTest` checks empty, locked, unused, unknown-ID and malformed enchant data without requiring assets. `StatisticsExplorerTest` covers literal searches, numeric/chronological sorting, character switches, graph ranges, map attribution, open/zero-gain visits, shared loot views, loot-only dungeon sources, and populated screenshots at desktop and compact sizes. Existing loot, workspace and capture regression tests also run.

This checkout provides `scripts/statistics-validation.gradle` to direct Gradle output to `build/statistics-validation` when older build outputs have Windows ownership conflicts. Build and test with JDK 17:

```powershell
$env:JAVA_HOME = Join-Path $PWD '.tools\jdk-17.0.20.1+1'
$env:GRADLE_USER_HOME = Join-Path $PWD '.tools\gradle-home'
& .\.tools\gradle-7.6.4\bin\gradle.bat -I scripts/statistics-validation.gradle test shadowJar --offline --no-daemon --console=plain
```

Use `Launch-RealmShark.cmd` for normal use so the running app reads an immutable runtime copy. Preview mode exercises the UI without capture. Synthetic screenshot fixtures and automated tests do not establish live gameplay accuracy.
