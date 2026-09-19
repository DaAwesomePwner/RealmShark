# RealmShark

A desktop companion for **Realm of the Mad God**, with read-only packet capture, chat, combat meters, loot tracking, character history and session statistics.

The application, launchers and Windows package use the **RealmShark fin logo** and the same product version. See [branding and desktop integration](docs/BRANDING.md).

## Run on Windows

### Portable package

1. Install [Npcap](https://npcap.com/#download), enabling **WinPcap API-compatible Mode**.
2. Extract the **entire** `RealmShark-Windows-x64.zip` into a writable folder.
3. Open **RealmShark.exe**. Java is included; keep the `app` and `runtime` directories beside the EXE.
4. Start capture and reconnect to the game so RealmShark sees a fresh connection.

Use **Preview-RealmShark.cmd** to inspect the UI without capture, startup API requests or game-asset extraction. See [Windows setup, updates and packaging](docs/WINDOWS-BUNDLE.md).

### Source checkout

- **Launch-RealmShark.cmd** starts the current built application through protected, immutable runtime-JAR staging.
- **Preview-RealmShark.cmd** opens preview mode.
- These launchers prefer the project-local JDK and otherwise use Java from PATH.

The current runnable artifact is `build/libs/RealmShark-v1.2.3.jar`. Use **JDK 17** for development and the included Gradle **7.6.4** wrapper.

## Workspaces

| Feature | Guide |
| --- | --- |
| Shared typography, themes, compact navigation and keyboard controls | [UI consistency](docs/UI-CONSISTENCY.md) |
| Searchable chat, channels, player filters, stars and exports | [Chat](docs/CHAT.md) |
| Key, rune, vial and inc openings | [Key-pops](docs/KEY-POPS.md) |
| Saved roster, death marks, equipment, stat maxing and exalts | [Characters](docs/CHARACTERS.md) |
| Combat meters, encounter history and damage details | [DPS meters](docs/DPS-METERS.md) |
| Fame, map breakdowns, loot and dungeon history | [Statistics](docs/STATISTICS.md) |
| Area visits, timelines and resource history | [Activity](docs/ACTIVITY.md) |
| Quest requirements, rewards and completion filters | [Daily Quests](docs/DAILY-QUESTS.md) |
| Sound choices and alert rules | [Notifications](docs/NOTIFICATIONS.md) |
| Guild exports, loot review and delivery diagnostics | [Bridge Review](docs/BRIDGE.md) |
| Packet coverage, stat changes and diagnostic exports | [Logging](docs/LOGGING.md) |

**Security** provides player/equipment inspection and ability activity. **My Info** shows the current character's captured stats, equipment and explicitly labeled local estimates. **Alt+M** opens labeled workspace navigation; **Ctrl+Shift+S** starts or stops capture.

## Build and validate

```powershell
.\gradlew.bat test shadowJar
```

The build generates multi-resolution fin PNGs and the Windows ICO from one vector source. The runnable JAR uses the `realmshark.RealmShark` entry point.

For compact-layout and display-scaling checks:

```powershell
.\gradlew.bat -I scripts/typography-validation.gradle test testUi150 testUi200
```

To build the complete Windows package, including its Java runtime:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-WindowsBundle.ps1
```

The latest verified download is published to `build/share/RealmShark-Windows-x64.zip`, with a SHA-256 sidecar. A timestamped archive is also retained. The bundle includes the public loot catalog and licenses, not personal settings, capture logs or saved sessions.

## Troubleshooting

- If the app opens without game data, check Npcap's compatibility-mode installation, start capture, and reconnect to the game.
- For a first-launch asset prompt, select `resources.assets` from your installed game, or pass `--path "full path to resources.assets"`.
- Check the capture status/footer and `logs/capture-health.log` for capture failures. The Logging workspace can export a diagnostic report.
- Rebuild and restart through the launcher after source updates. Avoid replacing an application's active JAR or runtime folder.
- Use one game connection at a time; multiple simultaneous game clients are not supported.

## Credits and license

Based on [X-com/RealmShark](https://github.com/X-com/RealmShark), with original work by Anon, [Cortex](https://github.com/MCRcortex), and upstream contributors. Inspired by [abrn/realmlib](https://github.com/abrn/realmlib) and [thomas-crane/realmlib-net](https://github.com/thomas-crane/realmlib-net).

Packet capture uses [ardikars/pcap](https://github.com/ardikars/pcap) and the native pcap/Npcap interface. Historical import and compatibility details are recorded in [UI refresh history](docs/UI-REDESIGN.md).

Distributed under the [MIT License](LICENSE.md). Runtime and dependency notices, and the separate [loot-catalog license](docs/LOOT-CATALOG-LICENSE.txt), remain included.

The Java resource extractor includes code adapted from [UnityPy](https://github.com/K0lb3/UnityPy); its [MIT notice](docs/UNITYPY-LICENSE.txt) is retained. The original imported revision was not recorded.
