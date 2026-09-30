# Equipment enchant rarity and rolled-enchant tooltips — design

Date: 2026-09-30 · Status: approved design, awaiting spec review

## Goal

Everywhere the program shows a rolled piece of equipment, show its enchant rarity — **Unenchanted, Uncommon, Rare,
Legendary or Divine** — and, on hover, the enchants it rolled. Today most surfaces show nothing, a few show only
"enchanted", and two different rarity rules are in use. After this work one item looks the same, and says the same
thing on hover, on every surface.

Success: the same item shows the same gem and the same tooltip on Loot, Runs, Characters, Party and DPS surfaces;
records captured before this work show what can honestly be derived and say "not recorded" otherwise, never a wrong
rarity.

## What the data gives us (research)

- **Raw form.** Stat `UNIQUE_DATA_STRING` (80) on player and loot-bag entities is a comma-separated list, one URL
  Base64 blob per slot (player: first four entries = weapon, ability, armor, ring; bag: entry *i* = bag slot *i*).
  `TradeItemData.uniqueData` and `VaultContentPacket` carry the same kind of blobs but no UI shows them.
- **Blob.** `00 | 02 04` (type 1026 LE) then up to four LE shorts: `-3` no slot, `-2` locked, `-1` unlocked but
  empty, `>= 0` enchantment type ID.
- **Rarity is the unlocked slot count** (`-1` or an ID; locked does not count): 0 Unenchanted, 1 Uncommon, 2 Rare,
  3 Legendary, 4 Divine. This already exists as `ParseEnchants.Summary.rarity()` (0 is currently labelled
  "Common") and is pinned by `ParseEnchantsSummaryTest`. An item with 2 unlocked slots and 1 applied enchant is Rare.
- **Definitions.** `assets/xml/enchantments.xml` (1016 entries) has per enchant: internal `id`, hex `type`,
  `<DisplayId>` ("Attack Bonus I"), `<Description>` ("Increases Attack by 1.4") and `<EnchantmentLabels>` with a
  `TIER1`–`TIER4` label. `ParseEnchants` keeps only `type → internal id` today.
- **Decoders.** `ParseEnchants.evidence()` / `summarize()` / `EquippedCapture` are strict and correct.
  `ParseEnchants.parse()` stops at the first empty or locked slot (drops later applied enchants) and appends an
  "empty"/"[locked]" line that callers then count as an enchant.

### Existing inconsistencies this work removes

| Rule in use | Where |
|---|---|
| Slot count (correct) | `LootDashboard`, `LootQuery`, `LootFacts.Item.enchanted()` (≥ 2 slots), `EnchantDots` |
| `parse()` line count (wrong: applied count, "empty" counts as one) | `IconDpsGUI` glow, `ParsePanelGUI` `GlowWell`, `BridgePayload.Item.enchantCount`/`rarity` |
| Raw blobs printed as text | `DamageEvents` detail |
| First blob wins per item id | `EquipmentUsageAggregator` (`computeIfAbsent`) |

### Where per-item enchant data exists

| Source | Has blob? | Notes |
|---|---|---|
| Live players (self, party, nearby) | yes | `Entity.stat[UNIQUE_DATA_STRING]` |
| Inspect snapshots | yes, persisted | `InspectSnapshot` whitelists stat 80; saved in `activity-history.json` `inspectedPlayers` |
| DPS hits | yes | `Damage.ownerEnchants`, four per-slot strings at hit time |
| Loot drops | yes, persisted | `LootDashboard.Item.enchantEvidence` keeps ordered slot IDs in the loot history JSONL |
| Own run gear (`ActivityJournal.Visit.equipment`) | **no** | slot → item id only |
| Character records (`CharacterJournal.CharacterRecord.equipment`) | **no** | item ids only |
| Bridge journal | **no** (derived text only) | stored rarity is the wrong line-count kind |

## Decisions (from brainstorming)

1. Rarity = unlocked slot count, everywhere. 0 slots is shown as **"Unenchanted"**.
2. **Start recording** enchant data for the user's own gear in run visits and character records. Older records show
   "Enchants not recorded".
3. Indicator: a **corner gem** — a small colored diamond in the top-right corner of the item tile. No gem means
   unenchanted. The tile border keeps its meaning (UT amber, ST rose).
4. Tooltip: **names plus effect text** — header with item name and tier, a rarity line, then one line per unlocked
   slot (display name + description) and "(empty slot)" for unlocked empty slots.
5. Approach: a shared foundation, then surfaces in four sequential PRs.

## Architecture

### `EnchantInfo` (new, `tomato.realmshark`)

Immutable value built from one item's blob, the single thing surfaces consume.

- `static EnchantInfo of(String blob)` — built on `ParseEnchants.evidence()`, never on `parse()`.
- `static EnchantInfo notRecorded()` — for records that never captured a blob.
- `static EnchantInfo fromEvidence(Evidence)` — for persisted loot evidence; legacy loot records without ordered IDs
  map to a rarity from their stored `slots` with no enchant list.
- `State state()` — `RECORDED`, `NOT_RECORDED`, `UNREADABLE`.
- `Rarity rarity()` — `UNENCHANTED, UNCOMMON, RARE, LEGENDARY, DIVINE, UNKNOWN` (`UNKNOWN` for not recorded or
  unreadable).
- `List<Slot> slots()` — the unlocked slots in order; `Slot` is `applied(short typeId)` or `empty()`. Locked slots
  are not listed.
- `String tooltipHtml(String itemName, String tier)` — the shared tooltip text (see below). Built lazily by callers,
  on hover, not per paint.

`Summary.rarity()` keeps its current strings for existing callers and tests; the display label "Unenchanted" comes
from `Rarity`.

### `EnchantCatalog` (extension of `ParseEnchants.prepareReload`)

Alongside the existing `type → id` map, keep `type → Definition(displayName, description, tier)` parsed from
`<DisplayId>`, `<Description>` and the `TIER1`–`TIER4` label. Lookups:

- known type → its `Definition`;
- unknown type → `Definition("Unknown enchant (0x5ff)", "", 0)`;
- catalog not yet loaded → the same fallback, so tooltips show IDs until assets load.

### Colors (`Tokens.rarity(Rarity)`)

Next to `Tokens.tier`: Uncommon green, Rare blue, Legendary purple, Divine gold, each with light and dark theme
values drawn from the existing `ContentStyle` palette; `UNKNOWN` (unreadable) is a neutral gray; `UNENCHANTED`
returns no color. This replaces `ParsePanelGUI`'s `GlowWell` role map and `IconDpsGUI`'s hard-coded RGB glows.

### Painting and tooltips

- **`ItemSlot.setEnchant(EnchantInfo)`** — the component paints the gem and uses the enchant tooltip.
- **`ItemSlot.icon(sprite, tier, state, size, EnchantInfo)`** — overload for table cells; the table supplies the
  tooltip text via its `getToolTipText(MouseEvent)` from the row's `EnchantInfo`.
- **`Sprites.paintGem(g, x, y, wellSize, Rarity)`** — one gem painter shared by `ItemSlot` and the custom-painted
  wells (`RunCardRenderer`, `NotableDropRenderer`). Gem size scales with the well: legible at 20, 24, 32 and 48 px,
  with a 1 px contrasting outline so it reads on any sprite.

### Tooltip content

Illustrative (text comes from `enchantments.xml`):

```
Doom Bow · UT
◆ Legendary · 3 slots
Attack Bonus II
   Increases Attack by 2.8
Critical Chance I
   …
(empty slot)
```

- Unenchanted: header plus "Unenchanted".
- Not recorded: header plus "Enchants not recorded".
- Unreadable: header plus "Enchant data unreadable"; gem painted gray with "?" semantics.
- Legacy loot (slot count known, IDs not): rarity line plus "Enchant names not recorded".
- Accessible name mirrors the rarity line.

## Rollout (four sequential PRs)

Each phase is its own branch and PR, independently reviewed and merged, with main verified before the next starts.

### Phase 1 — foundation and live surfaces

- `EnchantInfo`, `EnchantCatalog`, `Tokens.rarity`, `Sprites.paintGem`, `ItemSlot` enchant support.
- **Party roster** (`ParsePanelGUI`): gem replaces the `GlowWell` ring; hover uses the shared tooltip; inspect
  dialog lists display names instead of internal ids.
- **DPS icon view** (`IconDpsGUI`): gem replaces the colored glows; tooltip fixed (it currently embeds raw
  newlines in HTML).
- **DPS event detail** (`DamageEvents`): display names instead of raw blobs.
- **Character Gear tab** (`GearTab`, `SheetModel`, `SheetModelBuilder`): equipped slots carry `EnchantInfo`; gem
  and tooltip replace `EnchantDots`, which is deleted.
- **My Info** (`MyInfoGUI`): equipment icons get gem and tooltip.

### Phase 2 — loot

- `LootFacts.Item` keeps the ordered slot ids (it currently drops them) and exposes `EnchantInfo`.
- **Loot Highlights** (`HighlightsModel.Notable`, `NotableDropRenderer`): carry `EnchantInfo`; paint the gem.
  The "Enchanted" highlight kind keeps its meaning (Rare or better).
- **Run recap loot bags** (`RunRecapView`) and **run cards** (`RunCardModel.LootItem`, `RunCardRenderer`):
  carry `EnchantInfo`; gem and tooltip.
- **Loot Dashboard** tables: gem on the icon column, shared tooltip on hover.
- **Loot Archive detail** (`LootArchiveClient`): "Exact enchantment evidence" lists display names and descriptions
  instead of ids.
- Past loot records gain names automatically because their ids are already saved.

### Phase 3 — runs, characters and new recording

- **Run recap player gear** (`RunRecapBuilder`, `RunRecapModel.Players.Player`): stop discarding the persisted
  inspect `UNIQUE_DATA_STRING`; past runs gain rarity with no format change.
- **Record own gear enchants in run visits**: `ActivityJournal.Visit` gains an optional
  `Map<Integer, String> equipmentEnchants` (slot → blob, slots 0–3), written from the local player's stat 80 alongside
  `equipment`, merged the same way `equipment` is. `activity-history.json` stays `schemaVersion` 1; `ActivityStore`
  validation must not require the field (absent = not recorded).
- **Record own gear enchants on character records**: `CharacterJournal.CharacterRecord` gains an optional
  `String[] equipmentEnchants` (slots 0–3) updated whenever the character is observed live; absent = not recorded.
  The character list does not carry enchants, so only characters played after this change gain data.
- **Surfaces**: run recap players (own and inspected), Character Overview, Home hero card and the Gear Analyst table
  (`CharacterEquipmentPanel` — its "Not recorded in this character snapshot" text becomes the real enchant list when
  present).

### Phase 4 — Bridge and cleanup

- **Bridge** (`BridgePayload`): compute rarity from slot count for new entries and mark `raritySource` as the new
  slot-based source; old journal entries keep their stored value and are labelled "legacy count" in Bridge Review
  and the CSV export. Bridge Review's item cell gains the gem where a new-style rarity exists.
- **DPS equipment summary** (`EquipmentUsageAggregator`): track usage per item id *and* enchant blob and show the
  most-used variant instead of the first one seen.
- The HTTP bridge payload and `SendLoot` wire fields are unchanged.
- Remove remaining `parse()`-line-count callers; keep `parse()` only if something still needs its text.

## Compatibility rules

- **Do not add fields or methods to `Damage`, `StatData` or `Equipment`**: they have no pinned `serialVersionUID`,
  so changing them breaks existing `.dps` files. Rarity is derived at read time from `Damage.ownerEnchants` and
  `Entity.stat`.
- All new persisted fields are optional and null-tolerant under Gson; readers treat absence as "not recorded".
- No schema version bumps; no validation that rejects files lacking the new fields.
- Wire formats (HTTP bridge payload, `SendLoot`) are unchanged.

## Error handling

- Decoding never throws into UI code; every failure maps to `NOT_RECORDED` or `UNREADABLE`.
- Unknown enchant type → "Unknown enchant (0x…)". Catalog not loaded → same fallback until assets load.
- Tooltip text is computed on hover, never per paint or per row render.
- All Swing work stays on the EDT; capture-side recording follows the existing threading of `ActivityJournal` and
  `CharacterJournal`.

## Testing

Per the repo's current policy: focused local checks, a build and a launch smoke check, no routine full or scaled
suites.

- **Foundation unit tests**: `EnchantInfo` for each rarity; empty and locked slots before and after applied enchants
  (the case `parse()` gets wrong); malformed, missing and oversized input; `fromEvidence` for new and legacy loot
  evidence; catalog display name, description and tier; unknown type; unloaded catalog; tooltip text for every
  state.
- **Per phase**: model tests showing `EnchantInfo` reaches each surface model (Gear tab, party roster, Highlights,
  run cards, run recap players, Bridge rarity source, DPS most-used variant). Existing tests for touched surfaces
  (`GearTabTest`, `ParsePanelRefreshTest`, `LootDashboardTest`, `HighlightsModelTest`, `BridgeReportingTest`) are
  updated rather than bypassed.
- **Compatibility**: a synthetic old `activity-history.json` without `equipmentEnchants`, an old character record, a
  legacy loot record with no evidence and an old Bridge journal entry all load and show "not recorded" or "legacy".
- **Visual**: one gem check at 20, 24, 32 and 48 px in light and dark themes per phase.
- Known baseline: 22 window-size and evidence tests already fail on main on this workstation; compare against main
  before calling anything a regression.

## Out of scope

- Vault and trade enchant data (no UI surface shows those items).
- Quest reward and security-filter icons (item definitions, not rolled items).
- Enchant sprite icons in tooltips (`enchantments16x16` textures exist; can follow later).
- Changing what the "Enchanted" loot highlight or enchant notification rules match.
