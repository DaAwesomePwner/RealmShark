# RealmShark

A desktop companion for **Realm of the Mad God**, with read-only packet capture, chat, combat meters, loot tracking, character history and session statistics.

The application, launchers and Windows package use the **RealmShark fin logo** and the same product version. See [branding and desktop integration](docs/BRANDING.md).

## Run on Windows

### Portable package

1. Install [Npcap](https://npcap.com/#download), enabling **WinPcap API-compatible Mode**.
2. Extract the **entire** `RealmShark-Windows-x64.zip` into a writable folder.
3. Open **RealmShark.exe**. Java is included; keep the `app` and `runtime` directories beside the EXE.
4. Wait for asset readiness, start capture if it is stopped, and reconnect to the game so RealmShark sees a fresh connection.

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
| Saved-history search, named views, library, export scopes and session storage | [Session history](docs/SESSION-HISTORY.md) |
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

**Inspect** provides current-area and saved-run player/build inspection, damage rankings and ability activity. Chat, Key-pops, Loot, Statistics, Inspect, Runs, Timeline and Resources & buffs offer independent historical views with whole-scope saved queries, named views and explicit selected/page/all-match exports. Their history is retained under `%LOCALAPPDATA%\RealmShark\history` across app restarts and build folders. **My Info** shows the current character's captured stats, equipment and explicitly labeled local estimates. **Alt+M** opens labeled workspace navigation; **Ctrl+Shift+S** starts or stops capture.

## Build and validate

```powershell
.\gradlew.bat test shadowJar
```

The build generates multi-resolution fin PNGs and the Windows ICO from one vector source. The runnable JAR uses the `realmshark.RealmShark` entry point.

Gradle pins a **Java 17 toolchain** for compilation, tests and Java execution. Main code uses **`--release 8`** to retain Java 8 APIs and bytecode; tests and build tools use Java 17. Development and the Windows runtime remain on Java 17; the main compilation setting alone does not establish Java 8 compatibility for every dependency. See the [Gradle 7.6.4 toolchain documentation](https://docs.gradle.org/7.6.4/userguide/toolchains.html) and [release/API targeting](https://docs.gradle.org/7.6.4/userguide/building_java_projects.html#sec:java_cross_compilation).

`generateSources` writes the product version only to `<buildDir>/generated/sources/version/main/realmshark/version/Version.java`. The main source set schedules generation automatically, including with `-PrealmSharkBuildDir=...`; use Gradle compilation for IDE launches too. The default product version is `v1.2.3`. The separate `tomato.version.Version` upstream baseline (`v1.9.2`) and asset-cache token (`v1.9.1`) retain their compatibility roles. `-PrealmSharkVersion=v1.2.3-build-contract` is a filename-safe override for isolated Gradle build-contract checks and requires `-PrealmSharkBuildDir=...`; Windows packaging and launchers use the default version.

For a nondefault version, the canonical output path must be a dedicated subdirectory of `build` (such as `build/contract`) or an external build directory. The normal `build` directory and its aliases, project/source locations and their ancestors, and filesystem roots are rejected during configuration. This restriction applies only to nondefault-version builds.

After the ordinary build above has populated the wrapper/dependency cache, run the focused build-contract check with JDK 17 selected and no concurrent source edits:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-BuildMaintenance.ps1 -JavaHome "$env:JAVA_HOME"
```

It first checks that configuration-only `help` calls reject unsafe override outputs, including relative, absolute and dot-normalized aliases of the normal build directory, without changing normal generated/classes/resources/JAR outputs. It then performs offline builds in fresh `build/build-maintenance-<id>` directories, checks repeated generation is up to date, changes the version input in the same output/cache directory, and verifies a fresh default-version JAR. The isolated JAR probe checks the generated and inlined identity, Java 8 class headers, compatibility versions and UnityPy notice without starting the application or making application network requests. Source path/hash/write-time snapshots and Gradle logs remain in that directory. CI runs this check after its normal build.

For compact-layout and display-scaling checks:

```powershell
.\gradlew.bat -I scripts/typography-validation.gradle test testUi150 testUi200
```

To build the complete Windows package, including its Java runtime:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-WindowsBundle.ps1
```

The latest verified download is published to `build/share/RealmShark-Windows-x64.zip`, with a SHA-256 sidecar. A timestamped archive is also retained. The bundle includes the public loot catalog and licenses, not personal settings, capture logs or saved sessions.

The four-phase review implementation, retained regression evidence, and final cleanup decisions are summarized in [review closure](docs/STEP-4-CLEANUP.md).

## Troubleshooting

- If the app opens without game data, check Npcap's compatibility-mode installation, start capture, and reconnect to the game.
- The workspace and **Browse saved history** remain available when assets or Npcap are missing. Use **Choose assets…** to select the game's `resources.assets`, or **Retry assets** to recheck setup. You can also pass `--path "full path to resources.assets"`. Cancelling asset selection leaves the workspace open.
- Explicit Start/Stop retains your capture preference for the next launch; automatic start waits for successful initial asset readiness. Setup failures and manual retries preserve that preference, but a retry does not itself start capture. A failed asset replacement keeps a previously usable cache; a cache without its source file is labeled freshness unverified.
- Check the capture status/footer and `logs/capture-health.log` for capture failures. The Logging workspace can export a diagnostic report.
- Rebuild and restart through the launcher after source updates. Avoid replacing an application's active JAR or runtime folder.
- Use one game connection at a time; multiple simultaneous game clients are not supported.

## Credits and license

Based on [X-com/RealmShark](https://github.com/X-com/RealmShark), with original work by Anon, [Cortex](https://github.com/MCRcortex), and upstream contributors. Inspired by [abrn/realmlib](https://github.com/abrn/realmlib) and [thomas-crane/realmlib-net](https://github.com/thomas-crane/realmlib-net).

Packet capture uses [ardikars/pcap](https://github.com/ardikars/pcap) and the native pcap/Npcap interface. Historical import and compatibility details are recorded in [UI refresh history](docs/UI-REDESIGN.md).

Distributed under the [MIT License](LICENSE.md). Runtime and dependency notices, and the separate [loot-catalog license](docs/LOOT-CATALOG-LICENSE.txt), remain included.

The Java resource extractor includes code adapted from [UnityPy](https://github.com/K0lb3/UnityPy); its [MIT notice](docs/UNITYPY-LICENSE.txt) is included as `META-INF/licenses/UNITYPY-LICENSE.txt` in the application JAR and `UNITYPY-LICENSE.txt` in the Windows bundle root. The original imported revision was not recorded.
