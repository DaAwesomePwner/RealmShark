# Discovery logging

Logging inspects the same passively decoded traffic used by DPS and loot tracking. It does not inject into the game, modify packets, or make game-server requests. Start capture, enable collection, and enter a fresh area to gather data.

Gameplay history now has dedicated views: **Runs** (Alt+R), **Timeline** (Alt+T), and **DPS Logger > Resources & buffs**. See [Activity modules](ACTIVITY.md) for their controls and interpretation.

## Diagnostic views

- **Discovery** summarizes decoded traffic, collection health, and fields that may support future features.
- **Re-entry trace** orders passive party join/action, reconnect, handshake, queue, and admission/failure evidence. It does not send packets or infer a queue bypass.
- **Packets** counts packet types, clean decodes, failures, and unread trailing bytes.
- **Stat explorer** groups observed stat fields and numeric ranges. Observations are not proof that an unknown field has a particular meaning.
- **Event samples** displays bounded examples and numeric changes for inspection.
- **Field catalog** explains existing consumers, candidate uses, and known coverage limits.

**Gameplay & diagnostics collection** controls the shared collector for Logging, Runs, Timeline, resource/buff history and recorded Inspect builds. The capture connection must also be running. Turning collection off does not stop the sniffer or independent Chat, loot and DPS processing. **Pause this view** holds displayed data while collection status remains current; saved views are labeled **Saved history**. Sampled mode limits routine examples; detailed mode retains more examples within the same bounds. **Clear data** resets diagnostic counters and examples while preserving gameplay history. **Export report** writes a local JSON report.

Logging export acquires **current capture data**, even while its view is paused. This differs from Activity's displayed-revision export. Snapshot acquisition and serialization run off the Swing event thread. Automatic Logging refreshes request diagnostics without copying activity timelines; unchanged revisions avoid replacement work, and hidden views catch up when shown.

## Storage and privacy

**Save diagnostic samples** controls diagnostic persistence under `logs/discovery/`, with manual exports in `logs/discovery/reports/`. Packet samples use a bounded asynchronous queue and rotating JSONL files; queued writes may finish after disabling saving. Queue drops and write failures are exposed in diagnostics. App-session runs and timeline events save automatically under `%LOCALAPPDATA%\RealmShark\history`, independently of diagnostic logging. Older `activity-history.json` files are imported into that archive; live diagnostic counts still start fresh. Preview does not overwrite saved activity. See [Session history](SESSION-HISTORY.md).

Only allowlisted decoded fields are retained. Numeric observations, canonical map names, bounded numeric party rosters, and reconnect-candidate counts are useful for analysis. Reconnect hosts/keys, chat, authentication data, raw payloads, party names/descriptions, and account identifiers are excluded from reports. Account identity is compared privately to avoid mixing progression baselines across accounts. Decode diagnostics retain structural context, such as field offsets and declared lengths, rather than arbitrary exception messages.

Inspect's run history additionally retains observed player and guild names, character mode, level, base stats, and equipped items/enchant data. Those loadouts are saved with their visits in activity history and included in full history exports; diagnostic packet samples still omit player string stats.

## Accuracy

Choose **Details…** in the toolbar to open **Diagnostic coverage**: retained sample interval, sampling limits, decode failures, trailing bytes, sampled-out events, omitted deltas, withheld observations, observer errors and disk drops/errors. The interval is **partial** evidence, not continuous coverage; collection-off intervals are unobserved, not zero activity. **Delta-cache evictions** count lost comparison baselines, not lost retained events. Diagnostic disk drops cover the writer's lifetime and are not reset by **Clear data**.

A clean decode confirms the reader consumed the expected bytes; it does not independently verify the meaning of every field. Unknown and partial packets remain diagnostic evidence. They do not automatically feed gameplay statistics. Pre-decode capture faults are also recorded in the capture-health logs.

Missing traffic cannot be reconstructed. A `PartyAction: TeleportTo` is a server instruction, and `CreateSuccess` is admission evidence; neither establishes a reusable queue bypass. Unsigned string lengths and UTF-8 bounds are checked by the readers, but unresolved vault, stasis, and tick layouts still require fresh capture evidence. New statistics must be based on verified fields with visible coverage limits.
