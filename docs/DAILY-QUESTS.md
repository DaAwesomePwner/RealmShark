# Daily Quests: Board and Planner

Enter the Daily Quest Room during capture to load the server's current quest list. The Quests page has two tabs: the **Board** (the captured quests) and the **Planner** (your saved quest plans and manual stock). The tabs can be reordered and hidden like other tabs; they keep their saved order.

The Board's header identifies the captured-for account, capture generation, receipt time and age. A retained list keeps its original context and becomes **Stale / unverified** when it no longer matches the current capture. Capture a fresh list after switching accounts or restarting capture; account pin changes require a current, identified snapshot.

Below it, the summary line counts the whole captured list, not only the quests your filters show: "14 quests · 3 pinned · captured 14 min ago". Its age updates every minute. A list that no longer matches the capture adds **· stale** and reads in the warning color; with nothing captured it reads **No quest list captured yet**.

## Board

The Board shows the quests as **cards** by default. Each card shows:

- the quest's name, with a ★ when you pinned it for this account;
- **↻ Repeatable** or **One-time**, and **✓ Done** once completed;
- your type label as a chip (see [Daily and Event labels](#daily-and-event-labels)); unlabeled quests show none;
- **You get** over up to four reward sprites (a repeated reward shows its count) and **+N** for more; a quest where you choose one reward says **Pick 1 of N** instead;
- **Bring** over up to four required items, each with its count (**×10**), and **+N** for more.

Rewards or requirements the captured list did not include read **Rewards not captured** / **Requirements not captured**; a list the server sent empty reads **None listed by the server**. Unknown is never shown as none. Each sprite's border shows the item's tier. Cards have a fixed size, so the full lists are in the details.

**Group by** arranges the cards:

- **Chest tier** (the default): by the best quest chest among the quest's rewards, from the reward names: **Mighty**, **Epic**, **Standard** and **Beginner quest chests**, then **Other quest chests** (a quest chest naming none of these tiers), **No quest chest** and **Rewards not captured**.
- **Type label**: by your own labels in alphabetical order, then **No type label**.
- **None**: one group, **All quests**.

Each group's heading shows how many quests it holds; empty groups are not shown. Chest tier and type label are independent: an Event quest can award a Standard chest. **Pinned first** (on by default) puts your pinned quests first within each group; after them, cards follow the **Sort by** order (pins, reward name, quest type, fewest required items or quest name). Arrow keys move between cards in a group and Tab moves to the next group.

Open a card (Enter, Space or double-click) for its **details**, shown above the cards: the badges, your type label (or where to add one), the description, and every reward and requirement with its count and name. **Pin for account** / **Unpin quest** changes the account pin; an older global pin shows **Remove global interest**; **Add to account plan** copies the quest to the Planner. In Analyst mode the details also show the stable quest ID, the server category and **Expiration (raw server value)** exactly as the server sent it (**Not supplied** when absent); Simple mode shows no expiration. **Close** or Escape returns to the card. The details close when their quest no longer shows (filtered out, or gone from a new list).

The existing quest table is the **Table view**: in Simple mode choose **Table view** or **Cards view** in the ⋯ menu; in Analyst mode use the Cards/Table switch in the filter row. The table keeps its behavior: click column headers to sort, select a quest for the split details (description, captured expiration, repeated-item quantities and all requirements, with **Choose one** rewards told apart from receiving every item) and use **Pin quest**, **Remove global interest** and **Add to account plan** below it. One search and one **Filters** drawer serve both views, and the view, the grouping and **Pinned first** are remembered.

- Search by quest, reward, mark, or token name.
- In **Filters**: quest type, any quest chest or one tier (Mighty, Epic, Standard, Beginner) or an exact captured reward, repeatability, reward mode, whether an expiration was supplied, and a required item name or ID with a minimum quantity; **Pinned only**, **Show completed** and **Name types…**.
- Select a reward and sort by **Fewest required items** to compare turn-in requirements. This counts items, not dungeon difficulty or farming time.
- Pin quests for the captured account, then enable **Pinned only**. Older global pins remain labeled **Global interest** and are also included; **Remove global interest** removes only that legacy interest, not an account pin.
- Completed one-time quests are hidden unless **Show completed** is enabled. Repeatable quests remain visible. Completion comes from fetched quest rows; a redemption-success message alone does not change it. Capture a fresh list to see server updates.

Compact windows and enlarged fonts show fewer cards per row, wrap the filter row and scroll the page; the table scrolls its columns sideways. Keyboard focus reveals controls through nested scroll panes. Selection and pins follow the quest ID when incoming data changes the order. The Board says when nothing is captured yet, when the server sent an empty list and when no quest matches the filters; in the Table view, obsolete details are cleared and Pin is disabled when no quests remain visible.

## Daily and Event labels

The captured packet contains a numeric category with no label, and this checkout has no verified mapping from category numbers to in-game tabs. **Name types…** (in the Filters drawer) lists each captured category with an example quest. Match those groups to Daily, Event, Utility, Epic, or a custom label once; labels are saved locally, show on the cards and become available for grouping, filtering and sorting. Unlabeled categories keep their original numbers and are grouped under **No type label**.

Quest type is never inferred from repeatability, chest name, or expiration. An event can award a standard chest.

The category form scrolls for long names or many categories, with keyboard-reachable editors and OK/Cancel. Cancel keeps the prior labels. Static explanatory labels are not extra keyboard stops; detailed text remains readable through the scrollable view. Category labels remain global local preferences; new pins are stored per account, separately from preserved legacy global interests.

## Planner

The **Planner** (formerly "Saved plans") keeps manual quest plans per account. Choose the **Planning account** explicitly: saved accounts, accounts from the character journal and the verified captured account are offered, and none is chosen for you. The status line says whether the account's list is the verified current capture (needed to add or reconfirm plans) or offline, where manual editing still works.

The Planner opens on **cards**:

- **All plans** combines every plan of the account: the readiness, then one row per required item with **need · reserved · covered · missing** and a bar of reserved (accent), covered (green) and missing (amber) out of the need. The combination counts your stock once.
- Below it, one card per plan: the quest's name, **Repeats: N** or **One-time**, its status (**Saved requirements; verify server**, **Changed / removed; reconfirm**, **Completed one-time** or **Requirements unknown**), its readiness, and up to four required items, each with its need, the same bar and **missing N** or **covered**; more items end in **+N more**. A card counts its own plan alone, as if no other plan wanted the unreserved stock; the All plans card is the combined view.
- **Covered** is the unreserved part of the need that confirmed stock covers: min(need, available) − reserved, so reserved + covered + missing = need. **Available** is the manually confirmed held stock minus other plans' reservations; captured inventory is never used.
- An item without a confirmed held quantity reads **Stock unconfirmed** and draws no bar (an empty bar would read as 0). A plan whose requirements were not captured says **Requirements not captured**; one that needs nothing says **No items required (observed empty)**.

Select a card to edit its plan: **Desired repeats** with **Set repeats**, **Refresh / reconfirm selected** and **Remove selected plans**. **Release affected reservations with this edit** stays visible beside these edits. The plan table is the **Table view** (the ⋯ menu in Simple mode, the Cards/Table switch in Analyst mode): select several rows for their combined demand and stock, with the selected plans' saved details and totals below, as before. The view is remembered.

**Manual stock** is a drawer below the edits, collapsed by default and remembered: enter an **Item ID**, **Quantity** and **Manual note**, then **Confirm held quantity**, **Set reservation for selected plan**, **Release selected reservations** or **Release all reservations**. It lists the account's held values in manual wording, for example "Mark of the Forgotten King (#1): 12 (manual) · unallocated 8 · confirmed <time> · note". Quantity 0 explicitly confirms zero held, or releases that reservation.

The rules are unchanged: reservations inside a selection are not subtracted twice; manual held values carry a timestamp and note, explicit zero is distinct from unknown, and no packet changes them; lowering stock or demand below reservations requires the release checkbox; completed one-time quests are unavailable, and covered requirements never assert server eligibility. Changed or absent quests keep their saved snapshots and show the reconfirmation status. **Save plan** publishes the account's draft (a failed save keeps it for retry); **Reload / discard draft** asks first when there are unsaved changes. Plans are saved to `Characters/plans.json`; preview mode keeps them in memory.

## Getting there

- **Home's Quests card** lists up to three pinned quests (open ones first) with their reward sprites, and **Rewards not captured** for a quest whose rewards the list did not include, with the pinned / repeatable / done counts and the list's age (**May be out of date** when stale). Clicking the card opens the **Board**, even when the Planner was the last tab shown, and Back returns to Home.
- The Settings search entry **Quest requirements and manual stock** opens the **Planner**, and Back returns to where you were.
- **Quests** is also in the sidebar (Alt+6).

## Not shown yet

The **expiry countdown** is not part of this version: the Board shows no countdown chip, the summary no "expiring today" count, and Home does not order pinned quests by how soon they expire. The server's expiration value has an unconfirmed format, so it is never parsed or guessed at: it shows only as the raw value, in the Analyst details (and the Table view's details). Home's Quests card says that expiry countdowns are not shown until the format is confirmed.

## Scope

This view does not submit quests, consume items, request account data, or check inventory ownership. Item names and art come from local assets, with explicit item-ID fallbacks when missing. Rewards are the captured turn-in rewards; the tool does not estimate chest loot value. Pins and category labels use local Java preferences.

Validation includes the functional regressions, exact in-shell logical sizes 1240×800 and 680×520, category dialog OK/Cancel, native realized geometry and posted-key traversal. The Board and Planner evidence (cards by chest tier and type label, the details in Simple and Analyst, the Table views, stale and empty lists, the All plans summary, the Manual stock drawer and unconfirmed stock) is recorded in the [P4 validation record](superpowers/plans/2026-09-28-p4-validation.md); earlier evidence is in [Phase 4 evidence](STEP-4-CLEANUP.md).
