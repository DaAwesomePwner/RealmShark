# Characters

The **Characters → Roster** tab remembers your own characters as capture sees them. Start capture, then enter the game on a character. The existing character-list response, when available from the Pet Yard or Daily Quest Room, also adds characters. Nearby players are not added. Characters missing from a later response are retained.

The roster opens as a **gallery** of character cards: skin sprite, class, level and fame, an 8-pip maxed meter (**—** when unknown, never 0/8), a **Seasonal** chip, when the character last played, and a **Playing now** marker for the character in game. Characters marked dead are grouped in a collapsed **Graveyard** below. **Sort** orders the cards by last played, fame, class or maxed; unknown values sort last. The existing roster table is the **Table view**: in Simple mode choose **Table view** or **Gallery view** in the ⋯ menu; in Analyst mode use the Gallery/Table switch in the filter row, where cards also show character IDs. Both views show exactly the characters the one search and filter drawer select, and the view and sort are remembered. **Save view state** and **Reset saved view state** are in the ⋯ menu; the page warns only when saving fails.

The roster supports literal-text search (class, account, character ID, item name/ID, or notes), task filters and sortable table columns. Equipment search accepts decimal and hexadecimal IDs as well as names. Open a character (Enter or double-click on a card or table row, or click the Home hero) for its full-page **character sheet**; **‹ Characters** or Back returns to the list where you were. The sheet's tabs can be reordered and hidden:

- **Overview**: base stats against class caps (with the live boost while you play), what each stat still needs (with vault potions and how long ago they were counted, when known), equipped gear and a class exalt summary.
- **Gear**: equipped items, inventory and backpack; unknown and empty slots stay distinct.
- **Exalts**: this class's eight stats with tier, completions, the distance to the next tier and where to earn it.
- **Build**: weapon damage and recovery estimates for the character you are playing, or after capture stops the last one you played (formerly My Info). Other characters' sheets point to it.
- **Goals** and **Notes**, **Death annotation** for a character marked dead, and in Analyst mode **Snapshot evidence** (field source and receipt times, also shown under the header).

Combine account, class, manual life state and season with **Needs Life**, stat coverage, maxed-count range and snapshot age. For example, select an account, **Needs Life** and **All base stats captured** to find known deficits; use **Life need unknown** or **Missing cap definitions** to find records that need better evidence. **Maxed** and **Potions remaining** sort numerically. A total needs all eight base stats and cap definitions: unknown is not zero.

Age filters refer to **Last snapshot update**, not the last time every field was observed or proof that the character is alive. **Reset filters** clears display predicates without deleting records. Filters, sorting, columns and character selection are remembered; notes remain attached to the exact account/character. These filters cover the retained character journal, independently of app-session history pickers.

**Mark dead** preserves the character's last snapshot. **Restore alive** reverses the mark. Death is a manual annotation; disappearing from capture or losing a connection never marks a character dead. If the character is reported again, **Observed again—restore?** offers explicit restoration before accepting updates. Notes survive restoration. Pending new snapshot data is memory-only: after restarting, restore the character and capture it again to refresh its values. Exalts belong to the account/class and survive character death.

The **Exalts** tab shows saved progress for observed classes across accounts. See [Pets and feeding estimates](#pets-and-feeding-estimates) for the pet view.

The final cleanup retires five legacy panels that were no longer constructed by the application. The mounted Roster/Exalts/Pets views and live completion, vault, equipped-pet, fame, and journal tracking remain. This does not add replacements for the old unmounted collection grid, completion matrix, aggregate exalt rows, quickslot display, or multi-character/vault potion planner. See the [retirement disposition](STEP-4-CLEANUP.md#legacy-character-retirement).

At compact sizes or enlarged fonts the gallery wraps to fewer cards per row and the page scrolls; the table view keeps usable data rows, and sheet tabs wrap instead of hiding part of a label. Keyboard focus reveals controls through nested scroll panes. Draft notes survive background refreshes and are saved to their character when you leave the sheet or another character's sheet opens. Fame uses the display locale, while IDs stay ungrouped and dates use the shared full timestamp format.

## Data and accuracy

Records save automatically to `Characters/journal.json` in the application's working directory, normally within two seconds and on normal shutdown. Copy this folder to back up or move the roster. Account identities are hashed to prevent character-ID collisions between accounts; display names remain local. Tokens, credentials and raw packets are not written to the journal. The folder is excluded from Git and portable release staging.

Missing stats and equipment remain unknown; an observed empty equipment slot is different from an unobserved slot. Capture needs an account ID and character ID before creating a record. Unknown base stats or missing class assets prevent an exact `x/8` claim. Base stats use the protocol's total minus boost values; caps come from the local extracted `players.xml`. Potion estimates clamp at zero and use +5 Life/Mana and +1 for the other stats. Below level 20, these are deficits against the final class cap; level-up gains can reduce them. Exalt thresholds use the application's existing 5/15/30/50/75 progression. These are snapshots, not a complete historical event log.

**Last observed alive**, **Roster received** and **Marked dead manually** describe different evidence. Snapshot age and known-field counts do not mean every field was refreshed together: omitted fields retain earlier values, and field details identify older observations. Times are local receipt times. Older journals keep their values with **Legacy / provenance unknown** rather than invented timestamps; absent seasonal metadata stays unknown.

Writes replace the journal through a temporary file. If saving fails, the status line shows the failure and the application retries. If an existing journal is corrupt or has an unsupported version, it is preserved and saving is disabled with a visible explanation. Preview mode reads saved records but does not start capture, make requests, or save roster edits.

The journal is saved as **version 5** (pet, dungeon completions, experience, backpack, per-class exalt times and vault potions). The first save of an older file keeps a one-time copy beside it as `journal.v4.bak` before writing version 5, so a rollback is never left without the pre-upgrade data. Older RealmShark builds open the upgraded journal **read-only**: they cannot save new capture over version 5 fields they do not understand. To roll back manually, copy `journal.v4.bak` over `journal.json`; anything captured since the upgrade will not be in it.

## Pets and feeding estimates

Enter the Pet Yard during capture. **Pets** shows account/capture context, pet identity, equipped state and receipt evidence for feeding inputs. **Explicitly absent** is different from **Not captured**; incomplete pet metadata does not establish that no pet is equipped.

- Search local equipment, or enter a numeric/hex item ID and choose **Use item ID** (including food). You can always enter a positive **Feed power per item** manually and choose **Recalculate feeding costs**.
- Each ability explains captured level, points and maximum, the feed-power scenario and ability multiplier, with item/fame estimates for the next level and maximum. Missing inputs, locked abilities and inconsistent inputs are labeled separately. Missing points never mean **Fully fed**; unsupported fame-cost tiers can leave fame unavailable while item counts remain calculable.
- Estimates use local formulas. Choosing an item or feed-power scenario does not establish ownership, feed a pet or consume items. Scroll the page for long explanations and scenario controls in short windows.

## Validation

`scripts/character-validation.gradle` builds in a separate directory, so tests and packaging do not replace the active application JAR. Character tests cover persistence, partial stats, account separation, death/restore, missing roster entries, equipment changes, exalt updates, potion calculations, corrupt files, failed saves, and search/filter/sort controls. UI tests render desktop and compact screenshots. Full live gameplay capture remains a separate check.
