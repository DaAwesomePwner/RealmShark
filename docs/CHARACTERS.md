# Characters

The **Characters → Roster** tab remembers your own characters as capture sees them. Start capture, then enter the game on a character. The existing character-list response, when available from the Pet Yard or Daily Quest Room, also adds characters. Nearby players are not added. Characters missing from a later response are retained.

The roster supports literal-text search (class, account, character ID, item name/ID, or notes), alive/dead and seasonal filters, and sortable columns. Select a character for:

- Base stats, class caps, maxed-stat count such as **6/8**, and remaining standard potions.
- Last observed weapon, ability, armor, ring, inventory and backpack, with local item names and icons where available.
- Account/class exalt levels, completion counts and the next threshold.
- Editable notes, first/last seen dates, level and fame.

**Mark dead** preserves the character's last snapshot. **Restore alive** reverses the mark. Death is a manual annotation; disappearing from capture or losing a connection never marks a character dead. Later observations do not automatically restore a marked character. Exalts belong to the account/class and survive character death.

The **Exalts** tab shows saved progress for observed classes across accounts. **Pets** retains the existing pet view.

The final cleanup retires five legacy panels that were no longer constructed by the application. The mounted Roster/Exalts/Pets views and live completion, vault, equipped-pet, fame, and journal tracking remain. This does not add replacements for the old unmounted collection grid, completion matrix, aggregate exalt rows, quickslot display, or multi-character/vault potion planner. See the [retirement disposition](STEP-4-CLEANUP.md#legacy-character-retirement).

At compact sizes or enlarged fonts, scroll the page to move between the roster and details. Their minimum sizes reserve usable data rows; detail tabs wrap instead of hiding part of the selected label. Keyboard focus reveals controls through nested scroll panes. Draft notes survive background roster refreshes and are saved against the selected character identity when selection changes. Fame uses the display locale, while IDs stay ungrouped and dates use the shared full timestamp format.

## Data and accuracy

Records save automatically to `Characters/journal.json` in the application's working directory, normally within two seconds and on normal shutdown. Copy this folder to back up or move the roster. Account identities are hashed to prevent character-ID collisions between accounts; display names remain local. Tokens, credentials and raw packets are not written to the journal. The folder is excluded from Git and portable release staging.

Missing stats and equipment remain unknown; an observed empty equipment slot is different from an unobserved slot. Capture needs an account ID and character ID before creating a record. Unknown base stats or missing class assets prevent an exact `x/8` claim. Base stats use the protocol's total minus boost values; caps come from the local extracted `players.xml`. Potion estimates clamp at zero and use +5 Life/Mana and +1 for the other stats. Below level 20, these are deficits against the final class cap; level-up gains can reduce them. Exalt thresholds use the application's existing 5/15/30/50/75 progression. These are snapshots, not a complete historical event log.

Writes replace the journal through a temporary file. If saving fails, the status line shows the failure and the application retries. If an existing journal is corrupt or has an unsupported version, it is preserved and saving is disabled with a visible explanation. Preview mode reads saved records but does not start capture, make requests, or save roster edits.

## Validation

`scripts/character-validation.gradle` builds in a separate directory, so tests and packaging do not replace the active application JAR. Character tests cover persistence, partial stats, account separation, death/restore, missing roster entries, equipment changes, exalt updates, potion calculations, corrupt files, failed saves, and search/filter/sort controls. UI tests render desktop and compact screenshots. Full live gameplay capture remains a separate check.
