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

**Collect** controls the shared collector used by the gameplay modules. **Freeze** pauses the display while collection continues. Sampled mode limits routine examples; detailed mode retains more examples within the same bounds. **Clear** resets diagnostic counters and examples while preserving gameplay history. Export writes a local JSON report.

## Storage and privacy

The existing **Save logs** setting controls automatic persistence. Local files are under `logs/discovery/`, with manual exports in `logs/discovery/reports/`. Packet samples use a bounded asynchronous queue and rotating JSONL files. Queue drops and write failures are exposed in diagnostics. Activity history is checkpointed to `activity-history.json`; loading it restores history, not live diagnostic counts. Preview does not overwrite saved activity.

Only allowlisted decoded fields are retained. Numeric observations, canonical map names, bounded numeric party rosters, and reconnect-candidate counts are useful for analysis. Reconnect hosts/keys, chat, authentication data, raw payloads, party names/descriptions, and account identifiers are excluded from reports. Account identity is compared privately to avoid mixing progression baselines across accounts. Decode diagnostics retain structural context, such as field offsets and declared lengths, rather than arbitrary exception messages.

## Accuracy

A clean decode confirms the reader consumed the expected bytes; it does not independently verify the meaning of every field. Unknown and partial packets remain diagnostic evidence. They do not automatically feed gameplay statistics. Pre-decode capture faults are also recorded in the capture-health logs.

Missing traffic cannot be reconstructed. A `PartyAction: TeleportTo` is a server instruction, and `CreateSuccess` is admission evidence; neither establishes a reusable queue bypass. Unsigned string lengths and UTF-8 bounds are checked by the readers, but unresolved vault, stasis, and tick layouts still require fresh capture evidence. New statistics must be based on verified fields with visible coverage limits.
