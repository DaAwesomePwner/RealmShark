# Characters

The **Characters → Roster** tab remembers your own characters as capture sees them. Start capture, then enter the game on a character. The existing character-list response, when available from the Pet Yard or Daily Quest Room, also adds characters. Nearby players are not added. Characters missing from a later response are retained.

The roster supports literal-text search (class, account, character ID, item name/ID, or notes), task filters and sortable columns. Equipment search accepts decimal and hexadecimal IDs as well as names. Select a character for:

- Base stats, class caps, maxed-stat count such as **6/8**, and remaining standard potions.
- Last observed weapon, ability, armor, ring, inventory and backpack, with local item names and icons where available.
- Account/class exalt levels, completion counts and the next threshold.
- Editable notes, first/last seen dates, level and fame.
- Field source/receipt evidence in stats and equipment, plus a **Snapshot evidence** tab for character metadata.

Combine account, class, manual life state and season with **Needs Life**, stat coverage, maxed-count range and snapshot age. For example, select an account, **Needs Life** and **All base stats captured** to find known deficits; use **Life need unknown** or **Missing cap definitions** to find records that need better evidence. **Maxed** and **Potions remaining** sort numerically. A total needs all eight base stats and cap definitions: unknown is not zero.

Age filters refer to **Last snapshot update**, not the last time every field was observed or proof that the character is alive. **Reset filters** clears display predicates without deleting records. Filters, sorting, columns and character selection are remembered; notes remain attached to the exact account/character. These filters cover the retained character journal, independently of app-session history pickers.

**Mark dead** preserves the character's last snapshot. **Restore alive** reverses the mark. Death is a manual annotation; disappearing from capture or losing a connection never marks a character dead. If the character is reported again, **Observed again—restore?** offers explicit restoration before accepting updates. Notes survive restoration. Pending new snapshot data is memory-only: after restarting, restore the character and capture it again to refresh its values. Exalts belong to the account/class and survive character death.

The **Exalts** tab shows saved progress for observed classes across accounts. See [Pets and feeding estimates](#pets-and-feeding-estimates) for the pet view.

The final cleanup retires five legacy panels that were no longer constructed by the application. The mounted Roster/Exalts/Pets views and live completion, vault, equipped-pet, fame, and journal tracking remain. This does not add replacements for the old unmounted collection grid, completion matrix, aggregate exalt rows, quickslot display, or multi-character/vault potion planner. See the [retirement disposition](STEP-4-CLEANUP.md#legacy-character-retirement).

At compact sizes or enlarged fonts, scroll the page to move between the roster and details. Their minimum sizes reserve usable data rows; detail tabs wrap instead of hiding part of the selected label. Keyboard focus reveals controls through nested scroll panes. Draft notes survive background roster refreshes and are saved against the selected character identity when selection changes. Fame uses the display locale, while IDs stay ungrouped and dates use the shared full timestamp format.

## Data and accuracy

Records save automatically to `Characters/journal.json` in the application's working directory, normally within two seconds and on normal shutdown. Copy this folder to back up or move the roster. Account identities are hashed to prevent character-ID collisions between accounts; display names remain local. Tokens, credentials and raw packets are not written to the journal. The folder is excluded from Git and portable release staging.

Missing stats and equipment remain unknown; an observed empty equipment slot is different from an unobserved slot. Capture needs an account ID and character ID before creating a record. Unknown base stats or missing class assets prevent an exact `x/8` claim. Base stats use the protocol's total minus boost values; caps come from the local extracted `players.xml`. Potion estimates clamp at zero and use +5 Life/Mana and +1 for the other stats. Below level 20, these are deficits against the final class cap; level-up gains can reduce them. Exalt thresholds use the application's existing 5/15/30/50/75 progression. These are snapshots, not a complete historical event log.

**Last observed alive**, **Roster received** and **Marked dead manually** describe different evidence. Snapshot age and known-field counts do not mean every field was refreshed together: omitted fields retain earlier values, and field details identify older observations. Times are local receipt times. Older journals keep their values with **Legacy / provenance unknown** rather than invented timestamps; absent seasonal metadata stays unknown.

Writes replace the journal through a temporary file. If saving fails, the status line shows the failure and the application retries. If an existing journal is corrupt or has an unsupported version, it is preserved and saving is disabled with a visible explanation. Preview mode reads saved records but does not start capture, make requests, or save roster edits.

## Pets and feeding estimates

Enter the Pet Yard during capture. **Pets** shows account/capture context, pet identity, equipped state and receipt evidence for feeding inputs. **Explicitly absent** is different from **Not captured**; incomplete pet metadata does not establish that no pet is equipped.

- Search local equipment, or enter a numeric/hex item ID and choose **Use item ID** (including food). You can always enter a positive **Feed power per item** manually and choose **Recalculate feeding costs**.
- Each ability explains captured level, points and maximum, the feed-power scenario and ability multiplier, with item/fame estimates for the next level and maximum. Missing inputs, locked abilities and inconsistent inputs are labeled separately. Missing points never mean **Fully fed**; unsupported fame-cost tiers can leave fame unavailable while item counts remain calculable.
- Estimates use local formulas. Choosing an item or feed-power scenario does not establish ownership, feed a pet or consume items. Scroll the page for long explanations and scenario controls in short windows.

## Validation

`scripts/character-validation.gradle` builds in a separate directory, so tests and packaging do not replace the active application JAR. Character tests cover persistence, partial stats, account separation, death/restore, missing roster entries, equipment changes, exalt updates, potion calculations, corrupt files, failed saves, and search/filter/sort controls. UI tests render desktop and compact screenshots. Full live gameplay capture remains a separate check.
