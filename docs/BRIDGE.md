# Guild Bridge Review

Open **Bridge Review** in the sidebar or press **Alt+B**. It uses the existing detected-loot stream: a bag becoming visible is a drop observation, not proof that the player picked up its contents.

## Setup

1. Restart the updated app using `Launch-RealmShark.cmd`.
2. Open **Bridge Review → Settings**. Enter your guild's **Endpoint**, **Guild ID** and **Link Token**. No guild or endpoint is preconfigured.
3. Choose the loot CSV with **Browse**, or select **Use included CSV** to enter `./rotmg_loot_drops_updated.csv`. The dot and slash are accepted; paths resolve from the application's working folder. Absolute paths work too. This CSV is an **input allowlist**, not an output file.
4. Select **Enable bridge** and **Send matching drops to bot**, then **Save settings**. Saving sends the upstream `bridge_settings_test` confirmation when sending is enabled. Inspect **Logs** for the response. Actual Discord announcement depends on the bot's configuration and permissions.
5. Turn network capture on using **File → Start Sniffer** or **Start capture**. Enter a fresh game connection if capture began mid-session. Keep only one sniffer instance running.
6. Configure unseen characters through the guild bot's `/mysniffer → Configure Character` flow.

All fields remain editable. **Debug logs** adds sanitized outgoing fields and skip explanations. **Enable bridge** controls this module independently of **File → Opt-out Loot Sharing**, which still controls the original RealmShark sharing service. Disabling the guild bridge does not change that legacy service.

The included CSV is an editable public starting catalog with 1,241 rows from the bot source below. Your guild may use a different or newer catalog; select the guild's file when provided. Server-side catalog and point settings remain authoritative. Changing the CSV on disk takes effect on the next **Save settings**.

## Selection, review and export

- All category boxes selected preserves the public bridge's CSV matching behavior. Names normalize apostrophes, dashes and whitespace; a shiny can match the base item's CSV row.
- Categories are additive: UT, ST, shiny or enchanted matches any selected category. **Other CSV items** covers items with none of those labels. A category match still needs a CSV match to send.
- Turn off **Send matching drops to bot** for local review without HTTP requests. Enable bridge and a valid CSV are still required.
- The sortable **Review** table shows the latest 1,000 observed items, including excluded and unlisted drops. Search and status filters control the rows included in **Export review CSV**.
- Select an item to inspect decoded enchants, rarity provenance, character ID, delivery result and outgoing JSON with the token redacted. Full enchant descriptions stay local because the public bridge's wire contract uses rarity instead.
- **Logs** retains the latest 500 diagnostics, supports level filtering, shares the Review search, and has **Export logs** and **Clear logs**.
- Set **Review log (optional)**, for example `./logs/bridge-review.jsonl`, for an ongoing local record beyond the session table. It records final processing outcomes and decoded item data, with the token redacted. At 5 MB the file rotates to a single `.1` backup. Prior logs are not automatically imported or resubmitted.

The local review CSV is a human-readable report with its own documented header, quoted UTF-8 fields, multiline enchant descriptions and spreadsheet-formula escaping. It is not an upload/replay file or a replacement for the input loot catalog. HTTP requests retain the upstream JSON format below.

## Delivery outcomes

**Queued** means waiting locally. **Accepted** means the HTTP request succeeded without a recognized positive loot result; it alone does not establish that loot was recorded. **Logged** reflects `result.logged=true`. **Not logged** reflects a successful response with `logged=false`, including an item missing from the bot's catalog. Response `reason` and `routing_reason` codes appear in details and logs; `unmapped_character` identifies the Discord configuration step.

**Not in CSV**, **Filtered**, **Local only**, **Cancelled** and **Queue full** do not submit the item. **Rejected** includes the HTTP status and safe bot error code. Check endpoint, Guild ID, token, guild-side enablement, and the bot's catalog. **Uncertain** means a network exception occurred; the bot might already have processed the request. Check Discord before manually adding loot.

There are no automatic retries: the verified bot protocol has no event ID for safe deduplication. The worker queue holds at most 256 tasks. Changing settings cancels requests that have not started; an HTTP request already underway may finish against its original endpoint and credentials. Queued work and the in-memory table do not survive application exit. The optional journal is a review record, not a persistent send queue.

A process lock prevents two instances in the **same application folder** from enabling the bridge. It cannot detect another sniffer installation or another computer; keep one instance running across those too.

## Compatibility reference

Verified against these pinned public sources on September 16, 2026:

- [LastEternity/RealmShark `tomato_integration`, commit 25db3791d96c7be2e400f2cbce7ba9b4063eb35a](https://github.com/LastEternity/RealmShark/blob/25db3791d96c7be2e400f2cbce7ba9b4063eb35a/Tomato/src/main/java/tomato/realmshark/SendLoot.java), and its `BridgeReviewGUI` / `BridgeLogGUI`. This is the bridge fork linked by the bot README, rather than the original X-com sharing socket. The wire-field and normalization rules are adapted under the repository's [MIT license](../LICENSE.md).
- [PPE bot receiver, commit 26daaa8024ad5dcd4a2eec50f411906941bf49f9](https://github.com/tseringgg/rotmgppebot/blob/26daaa8024ad5dcd4a2eec50f411906941bf49f9/utils/sniffer_helpers/realmshark_ingest.py) and [`realmshark_ingest_server.py`](https://github.com/tseringgg/rotmgppebot/blob/26daaa8024ad5dcd4a2eec50f411906941bf49f9/utils/sniffer_helpers/realmshark_ingest_server.py).
- [Included loot catalog](https://github.com/tseringgg/rotmgppebot/blob/26daaa8024ad5dcd4a2eec50f411906941bf49f9/rotmg_loot_drops_updated.csv), SHA-256 `EAB4E19C5268328007C5704A161155598C877F2DE7DB61D4662F8726308C5A69`. Its MIT notice is in [LOOT-CATALOG-LICENSE.txt](LOOT-CATALOG-LICENSE.txt).

Requests use `POST`, `Content-Type: application/json; charset=UTF-8`, and one JSON object per matching bag slot. Guild IDs are JSON integers backed by a Java `long`, not floating-point numbers. Loot fields match the source:

| Always included | Included when available |
| --- | --- |
| `guild_id`, `link_token`, `item_name`, `shiny`, `item_id`, `item_rarity`, `divine`, `is_seasonal`, `loot_drop_bonus`, `source: "tomato"` | positive `character_id`, `character_name`, `character_class`, `item_group`, `item_label`, nonempty `dungeon` |

The confirmation contains exactly `guild_id`, `link_token`, `event_type: "bridge_settings_test"`, `source: "tomato"`.

Rarity preserves upstream behavior: metadata tokens take precedence, then decoded enchant line counts map 0–4 to common/uncommon/rare/legendary/divine. Empty/locked-only strings count as zero. The public parser's mixed locked/empty line-count behavior is preserved for compatibility; this is the bridge's classification rule, not an independent claim about game rarity. Malformed enchant strings are isolated per item and reported as unknown when metadata cannot resolve rarity.

Improvements over upstream are local: accurate HTTP outcomes, bounded work/history, safer CSV parsing, masked token entry, no token fragments in logs, no automatic HTTP redirects, immutable destination settings for each request, a separate background sender, and explicit preview protection. The original loot dashboard, sharing, sound alerts and attribution remain in place. No new game connections or packet modifications are involved.

## Settings and validation

Settings are saved atomically to `bridge.properties`, using the upstream `realmshark.bridge.*` keys. If that file does not exist, bridge keys in `realmShark.properties` are read for migration. The link token is stored locally in plaintext, as with upstream; the settings file is git-ignored and excluded from portable builds. Treat it as private. Diagnostics, review exports and the optional journal redact it.

Startup settings and CSV loading run asynchronously, with a visible loading/error state. Fields edited during loading are preserved. Configuration changes use a separate serialized worker with at most eight waiting requests; slow CSV reads, saves, auditing, or folder-lock cleanup do not hold the capture/UI snapshot monitor. Failed configuration retains the previously active settings/catalog. Closing prevents late configuration publication; an already-started file write or HTTP request may finish, and folder ownership is released by its worker after outstanding configuration work completes.

`--preview` cannot send bridge events or save settings, even if the saved configuration is enabled. The bridge is disabled by default. Endpoint validation requires HTTPS, with HTTP allowed only for local test servers. Responses are bounded and diagnostics retain only selected structured result/error codes, never arbitrary response bodies.

Tests cover a golden payload and settings ping, shiny/rarity rules, slot alignment and detached capture data, CSV quoting and Unicode, configuration persistence, a real local HTTP fixture, redirect refusal, bot rejection/non-logging, disabled/preview/local/category gates, queue overflow, uncertain delivery without retries, settings changes, same-folder locking, token redaction, exports and desktop/compact Swing views. Live guild acceptance, Discord announcement and a fresh gameplay drop still require the user's configured bot and capture session.

Validation on September 16, 2026: the complete **131-test suite passed**. After the final layout and log-inspector adjustments, all **18 bridge/workspace checks passed** again. Desktop and compact renders were visually inspected. `Test-RuntimeJar.ps1` passed build isolation, same-build reuse, distinct paths and corruption detection. The tested JAR was copied to `build/libs/RealmShark-v1.2.3.jar` with matching SHA-256; no live guild requests were made.

Build/test with the project-local JDK and Gradle, using `-I scripts/bridge-validation.gradle test shadowJar --offline --no-daemon --console=plain` for isolated outputs. Use the normal launcher for the installed build, so later builds cannot replace the JAR used by the running JVM.
