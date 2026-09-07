# RealmShark desktop refresh

This folder now builds a complete Swing desktop application. The supplied download contained the RealmShark library branch, without the Tomato application described in its README. The Tomato GUI and its backend/resources were imported from `X-com/RealmShark`, branch `tomato`, commit `257a1c5` (2026-02-28). The supplied library sources remain in place. The upstream MIT license and credits are preserved.

The imported Tomato baseline is v1.9.2. Its checked-in version constant still said v1.9.1 because upstream generated it during builds; this combined build generates the separate RealmShark artifact version. The Tomato constant now reflects v1.9.2, avoiding a false update popup. The asset-cache revision stays v1.9.1 because this metadata correction does not change asset extraction. Future upstream release notices explain that changes must be merged and rebuilt to preserve the customizations, rather than instructing users to replace the application with the stock JAR.

## Run

- Double-click `Launch-RealmShark.cmd` for the application.
- Double-click `Preview-RealmShark.cmd` to inspect the UI without starting capture, extracting game assets or making the startup API requests. The capture controls are disabled in preview. It uses empty real feature panels rather than invented account data.
- Or run `java -jar build/libs/RealmShark-v1.2.3.jar` (add `--preview` for preview mode). Existing `--path` and `--help` arguments remain supported.

The launchers use the downloaded project-local JDK when present, otherwise Java from PATH. The normal application retains the upstream game-asset extraction, Npcap requirement, API integrations, saved settings and capture auto-start preference. Preview mode bypasses that startup path, but settings changed through its menus are still saved in the current working directory.

## Design and controls

The default RealmShark Violet theme adds dark surfaces, violet accents, modern fonts for controls, larger click targets and clearer focus states. All six existing themes are still available under **Edit > Theme**, alongside the new theme. Saved log-font preferences remain supported; changes now apply to all four chat channels.

The sidebar automatically collapses to labeled-by-tooltip icons below 1000 pixels. At smaller widths the descriptive subtitle and footer hint yield space to the actual controls. The window supports sizes down to 680 by 520 logical pixels (or the available screen size). Legacy panels retain their scrolling and sub-tabs.

- **Alt+1** through **Alt+8**: navigate to a section.
- **Ctrl+Shift+S**: start or stop capture, using the same action as File > Start Sniffer.
- **Ctrl+F** in Chat: focus search in the active channel.
- **Enter / Shift+Enter** in search: next / previous match, wrapping at the end.
- **Escape** in search: clear the query.

Search is literal and case-insensitive. It highlights a match without filtering, deleting, or inserting log messages. Empty-state text is painted separately and is never part of copied/saved logs.

## Feature preservation

| Original section | Retained capabilities |
| --- | --- |
| Chat | All, PM, Party and Guild channels; spam filtering; chat saving/clearing; keyword pings; Umi responses |
| Key-pops | Event log, clear, dungeon notification choices and log-to-file setting |
| Security | Player parsing, filter controls, player/guild links and ability-use log |
| Characters | Existing Exalts and Pets tabs, pet controls and existing data hooks |
| Statistics | Fame graph, fame table, session management, loot and dungeon statistics |
| Daily Quests | Quest requirements/rewards and completion filtering |
| My Info | Existing player equipment, stats and damage display |
| DPS Logger | Live/saved encounters, previous/next navigation, dungeon list, filter editor/presets, freeze, icon/text modes and equipment/sort options |
| Menus | Capture/auto-start, loot sharing opt-out, chat settings, volume and all sound pings, item/entity/enchantment pings, loot filters, themes, fonts, borders, about, Java information and bandwidth |

Some character functions were already disabled upstream (`CharacterPanelGUI.ENABLED = false`, with additional tabs commented out). They were preserved as received, not silently enabled or represented as repaired. Current game-protocol compatibility and live data population require an in-game session; UI validation does not establish them.

## Build and validation

Use **JDK 17** with the included Gradle 7.6.4 wrapper:

```powershell
.\gradlew.bat test shadowJar
```

The combined build resolves the original dependencies from Maven rather than requiring a manually copied RealmShark JAR in `libs`. Output: `build/libs/RealmShark-v1.2.3.jar`. UI tests run in `build/ui-test`, keeping their settings separate from the user's preferences. They require a graphical desktop session.

Tests open the actual application in preview mode, exercise all eight navigation destinations at 1240, 760 and 680 pixel widths, inspect preserved menus, verify the capture guard and search behavior, and render all eight pages. Screenshots are written to `build/ui-test/screenshots`; HTML test results are in `build/reports/tests/test/index.html`.

Validated on 2026-09-06: the build and all five UI/search tests passed, including switching to the legacy high-contrast light theme and back. The packaged JAR's `--help` entry point also passed. All main views and their existing sub-tabs were rendered for review. Preview logs include an expected missing `assets/xml/players.xml` diagnostic because no game assets were extracted. Darklaf also logs a Windows cleanup warning when trying to delete its loaded native DLL; the theme-switch assertions passed.

No live packet-capture/gameplay validation has been performed. Packet parsing, damage calculations and the original library implementation were retained; this refresh is focused on presentation and the combined build.

## ProtonVPN capture fix (2026-09-06)

The later VPN fix supersedes the capture-validation limitation above for the tested capture path. On this machine ProtonVPN Smart mode selected a WireGuard adapter, which Npcap 1.88 opened successfully with `DLT_RAW` (12). The original sniffer interpreted every packet as Ethernet and could therefore open the tunnel without decoding its packets.

Capture now carries the actual adapter link type into packet decoding, supporting Ethernet (including the existing VLAN path), raw IPv4, and NULL/LOOP loopback headers. Original Ethernet packet factories remain compatible. IPv6, unsupported formats and truncated frames are ignored rather than selecting an unusable adapter. This follows [libpcap's per-handle link-type contract](https://www.tcpdump.org/manpages/pcap_datalink.3pcap.html).

Adapter selection and packet consumption now share a condition-guarded queue. This retains a first packet received before the consumer starts, prevents another adapter from overwriting the winner, and wakes waiting consumers on stop. Unused adapters are stopped with `breakLoop`; each native reader closes its own handle after the loop exits. The capture buffer timeout is 250 ms instead of 60 seconds. Packet timestamps use the handle's actual precision. The footer displays the selected adapter; its tooltip remains available in compact layouts.

Validation: all **13 tests passed** (8 capture/packet regressions and the existing 5 UI tests). An eight-second passive check of the existing game connection selected **WireGuard Tunnel (link type 12)** and delivered **105 incoming / 37 outgoing payload segments**, with **0 stream errors**. Shutdown left **0 capture reader threads**. The diagnostic printed only status and counts; packet/account contents were not saved or displayed. This validates adapter discovery, decoding and stream delivery, not every downstream gameplay feature.

Close and reopen RealmShark to load the rebuilt JAR. Connect ProtonVPN before starting capture. The subsequent recovery fix below adds automatic adapter rescanning. No VPN settings or network drivers were changed.

## Live capture recovery (2026-09-06)

Read-only diagnostics of the stalled running app confirmed `processorPresent=true`, `processorAlive=false`, `queueStopped=true`, and zero native capture readers. The window's event thread was responsive. Both Chat and DPS stopped because their shared capture worker had exited while the app retained its processor reference. The old build did not retain the triggering exception, so the specific historical trigger could not be recovered.

The capture worker now supervises individual capture attempts. A reader exit or processing exception closes the attempt and reopens adapters after one second. Fifteen seconds without game traffic refreshes adapter discovery, including VPN interfaces recreated by a reconnect. Explicit Stop cancels retries. Terminal failures reset the UI control through the event thread. The footer reports captured TCP packets, successfully decoded packets and game ticks; these counts restart on each capture attempt.

TCP reassembly now handles overlapping retransmissions, out-of-order first data after SYN, 32-bit sequence wrap, FIN payloads, and changed connection endpoints. ACK-only packets do not accumulate. The old gap handler could skip missing encrypted bytes and had an unreachable stop branch. Gaps exceeding the bounded reorder buffer now trigger recovery instead. Invalid game-frame lengths fail promptly rather than poisoning the framing buffer. Fresh handshakes clear stale framing/alignment history and preserve the initial game packets; capture begun mid-connection still uses the existing alignment mechanism.

Lifecycle failures are recorded in `logs/capture-health.log`, with a single rotated previous file (approximately 256 KiB each). These new logs contain event descriptions, exception classes and stack frames, not packet bodies, addresses or credentials. They are ignored by Git. The legacy optional packet/error logging features are unchanged.

Validation: **28 tests passed**, including the original UI and VPN tests plus recovery, cancellation, TCP, framing and handshake regressions. A passive 20-second check through WireGuard observed **3,686 decoded packets and 73 NEWTICK packets** by the last status update; shutdown left no capture readers. This verifies the real decoder path, not a prolonged gameplay soak or every DPS calculation. No packet contents were printed or saved by the diagnostic.

Restart RealmShark using `Launch-RealmShark.cmd` to load the update. If capture must recover mid-connection and ticks remain at zero, change areas or reconnect the game to provide a fresh handshake. Recovery cannot reconstruct packets already missed during an outage.

## Loot-triggered capture crash (2026-09-06)

The next user's run produced a concrete cause in `capture-health.log`: `LootGUI.displayDungeonIcon` initialized `ParseDungeon`, whose mandatory read of missing `assets/xml/mods2.xml` threw `FileNotFoundException` and then `ExceptionInInitializerError`. Later attempts in the same app failed with `NoClassDefFoundError` because Java retained the failed class initialization. The installed assets contain `mods.xml` and `portals.xml`; the second modifier file is absent. This explains why a decoder-only probe passed while gameplay with a loot entry stopped capture.

Dungeon metadata now loads files independently, treats `mods2.xml` as optional, closes input streams, preserves valid modifier and portal lookups, and tolerates missing/malformed metadata. Unknown modifier names remain in the map data but do not produce invented numeric IDs or null-unboxing exceptions. Dungeon grades and fallback portal icons remain available. The old public `ParseDungeon` methods remain compatible.

Terminal capture failures now carry the exception type and code location to a persistent, wrapping error message. It remains visible at compact widths where the ordinary footer hint is hidden. Restarting capture clears the message.

Validation: **35 tests passed**, including absent/optional/malformed metadata, known modifier and portal lookups, repeated loot dungeon rendering, and the compact error display. A standalone reproduction against the user's extracted assets failed with `FileNotFoundException: assets\\xml\\mods2.xml` using the previous JAR, then rendered three consecutive dungeon icons successfully using the rebuilt JAR and returned modifier IDs `[98, -15]` for `FEEBLEMINIONS_1;|D`. This reproduction did not capture traffic or submit loot. A full app restart is necessary to replace the previously failed Java class.

## Saved DPS encounters showing only elapsed time (2026-09-06)

Inspection of the running app confirmed all three encounters were retained: Tomb of the Ancients had 98 hit entities / 388 damage rows, Ice Citadel 108 / 246, and Deadwater Docks 64 / 76. Replaying the saved icon renderer reproduced a null-pointer exception for the latter two: `ImageBuffer.getOutlinedIcon` called `getScaledInstance` on the null returned for a nonpositive/unknown entity sprite ID. The renderer had already drawn the dungeon/time heading when it failed.

Both normal and glowing icons now use the existing empty placeholder when sprite lookup returns null, preserving damage rows rather than aborting the encounter. Font measurement also has a default when a custom font has not been initialized. Switching display panels revalidates their layout, and returning to Live renders immediately rather than leaving the previous saved encounter visible until another packet arrives.

Validation: **37 tests passed**, including unknown/empty sprite IDs and repeated navigation through three synthetic saved encounters with damage. All three actual encounters rendered successfully both offline and in the running app after reloading the three changed renderer classes. No restart was needed for this session. Local backups were saved with the existing `.dps` exporter format and debug packets excluded under `DPS-Recovered/2026-09-06`; this directory is ignored by Git. The rebuilt JAR includes the same changes for future launches.
