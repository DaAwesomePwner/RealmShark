# RealmShark

A desktop companion for **Realm of the Mad God**, with read-only packet capture, chat, combat meters, loot tracking, character history and saved session history.

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
- These launchers probe every `.tools/jdk-*` folder and select the newest Java 17+ runtime by version. They use Java from PATH only when no compatible local runtime exists.

The current runnable artifact is `build/libs/RealmShark-v1.2.3.jar`. Use **JDK 17** for development and the included Gradle **7.6.4** wrapper.

## Pages

| Page | What it is for | Guide |
| --- | --- | --- |
| **Home** (Alt+H) | Opens first: your current or last known character (maxed stats, pet rarity, gear, labeled estimates), what is happening now, today's or this session's runs, fame and notable loot, the last five runs and your pinned quests. Each card opens its page, and Back returns to Home. | [Interface](docs/UI-REDESIGN.md) |
| **Characters** (Alt+4) | A gallery of your characters (the roster table is its Table view), the **Exalts** grid, **Pets** with the feeding calculator and, in Analyst, **Fame history**. A card opens the character sheet: overview, gear, exalts, pet, fame, Build, goals, notes and death marks. | [Characters](docs/CHARACTERS.md) |
| **Runs & DPS** (Alt+R) | **Feed** (saved runs by day, each opening its run recap), **Dungeons** (one card per dungeon, with the Analyst session comparison and cohorts), **Live meter** (the damage meter), **Resources & buffs** and **Recordings**. Every fight is saved automatically. | [Runs & DPS](docs/ACTIVITY.md#runs--dps), [DPS meters](docs/DPS-METERS.md), [saved combat history](docs/DPS-METERS.md#saved-combat-history) |
| **Loot** (Alt+9) | **Highlights** (today's or this session's tiles, notable drops and bags by dungeon) and **Explore** (every live and saved loot view behind one selector) | [Loot](docs/LOOT.md), [where Statistics went](docs/STATISTICS.md) |
| **Quests** (Alt+6) | **Board** (quest cards by chest tier or your type labels, pins, details and filters) and **Planner** (plan cards and manual stock) | [Daily Quests](docs/DAILY-QUESTS.md) |
| **Chat** (Alt+1) | Searchable chat by channel, with player filters, stars, saved sessions and exports | [Chat](docs/CHAT.md) |
| **Party** (Advanced, Alt+3) | Players in the current area and in saved runs: builds, requirements, damage rankings and, in Analyst, ability activity | [Activity](docs/ACTIVITY.md) |
| **Key-pops** (Advanced, Alt+2) | Key, rune, vial and inc openings, live and saved | [Key-pops](docs/KEY-POPS.md) |
| **Timeline** (Advanced, Alt+T) | Party, equipment and progression events across your sessions | [Timeline](docs/ACTIVITY.md#timeline) |
| **Logging** (Advanced, Alt+0) | Packet coverage, stat changes and diagnostic exports | [Logging](docs/LOGGING.md) |
| **Bridge Review** (Advanced, Alt+B) | Guild exports, loot review and delivery diagnostics | [Bridge Review](docs/BRIDGE.md) |
| **Settings** (Alt+, or Alt+N) | **Notifications** (sounds and alert rules), **General** (combat history), **Appearance** (theme, contrast, motion, Simple or Analyst), **Loot filters**, **Chat** (Save chat and the chat filters) and **About** | [Notifications](docs/NOTIFICATIONS.md), [Loot](docs/LOOT.md), [Chat](docs/CHAT.md) |

Other guides: [session history](docs/SESSION-HISTORY.md) (saved-history search, named views, the History library, export scopes and session storage), [where Statistics went](docs/STATISTICS.md) (the page retired in P6a) and [UI consistency](docs/UI-CONSISTENCY.md) (typography, spacing and responsiveness history, 2026-09-18 to 2026-09-20).

## Navigation

The sidebar lists the six core pages and a collapsed **Advanced (5)** group, with **Settings** at the bottom. Every page has an Alt key; **Alt+M** opens the labeled page list and **Alt+Left** goes Back. Right-click a sidebar row (or press **Shift+F10**) to move, hide, pin or restore pages or to reset navigation; **Ctrl+Shift+Up/Down** or dragging moves a core row. Tabs move by dragging, from their right-click menu or with **Ctrl+Shift+Left/Right**, and hide from the same menu. Each page that filters has one filter row: search, **Filters** with removable chips, **Scope ▾** (live or saved history, and which sessions) on the archive pages, and **⋯** for views, exports and columns. The header's **Simple | Analyst** switch (**Ctrl+Shift+A**, also in Settings › Appearance) adds provenance, IDs and diagnostic tabs in Analyst; in Simple, recent times read "12 min ago". Saved history is kept under `%LOCALAPPDATA%\RealmShark\history` across app restarts and build folders. The [interface guide](docs/UI-REDESIGN.md) has the [pages and tabs](docs/UI-REDESIGN.md#information-architecture), [keyboard shortcuts](docs/UI-REDESIGN.md#keyboard-shortcuts), [customizing](docs/UI-REDESIGN.md#customizing), [the filter row](docs/UI-REDESIGN.md#the-filter-row), [Simple and Analyst](docs/UI-REDESIGN.md#simple-and-analyst) and [where the old pages went](docs/UI-REDESIGN.md#where-the-old-pages-went).

## Build and validate

```powershell
.\gradlew.bat test shadowJar
```

The build generates multi-resolution fin PNGs and the Windows ICO from one vector source. The runnable JAR uses the `realmshark.RealmShark` entry point.

Gradle pins a **Java 17 toolchain** for compilation, tests and Java execution. Main code, tests and build tools use **`--release 17`**. The Windows bundle ships a Java 17 runtime, and the source launcher refuses older runtimes. See the [Gradle 7.6.4 toolchain documentation](https://docs.gradle.org/7.6.4/userguide/toolchains.html) and [release/API targeting](https://docs.gradle.org/7.6.4/userguide/building_java_projects.html#sec:java_cross_compilation).

`generateSources` writes the product version only to `<buildDir>/generated/sources/version/main/realmshark/version/Version.java`. The main source set schedules generation automatically, including with `-PrealmSharkBuildDir=...`; use Gradle compilation for IDE launches too. The default product version is `v1.2.3`. The separate `tomato.version.Version` upstream baseline (`v1.9.2`) and asset-cache token (`v1.9.1`) retain their compatibility roles. `-PrealmSharkVersion=v1.2.3-build-contract` is a filename-safe override for isolated Gradle build-contract checks and requires `-PrealmSharkBuildDir=...`; Windows packaging and launchers use the default version.

For a nondefault version, the canonical output path must be a dedicated subdirectory of `build` (such as `build/contract`) or an external build directory. The normal `build` directory and its aliases, project/source locations and their ancestors, and filesystem roots are rejected during configuration. This restriction applies only to nondefault-version builds.

After the ordinary build above has populated the wrapper/dependency cache, run the focused build-contract check with JDK 17 selected and no concurrent source edits:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-BuildMaintenance.ps1 -JavaHome "$env:JAVA_HOME"
```

It first checks that configuration-only `help` calls reject unsafe override outputs, including relative, absolute and dot-normalized aliases of the normal build directory, without changing normal generated/classes/resources/JAR outputs. It then performs offline builds in fresh `build/build-maintenance-<id>` directories, checks repeated generation is up to date, changes the version input in the same output/cache directory, and verifies a fresh default-version JAR. The isolated JAR probe checks the generated and inlined identity, Java 17 class headers, compatibility versions and UnityPy notice without starting the application or making application network requests. Source path/hash/write-time snapshots and Gradle logs remain in that directory. CI runs this check after its normal build.

For compact-layout and display-scaling checks:

```powershell
.\gradlew.bat -I scripts/typography-validation.gradle test testUi150 testUi200
```

To build the complete Windows package, including its Java runtime:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-WindowsBundle.ps1
```

The latest verified download is published to `build/share/RealmShark-Windows-x64.zip`, with a SHA-256 sidecar. A timestamped archive is also retained. The bundle includes the public loot catalog and licenses, not personal settings, capture logs or saved sessions.

The 2026-09-19 code review's four implementation phases, their regression evidence and cleanup decisions are recorded in [review closure](docs/STEP-4-CLEANUP.md). That record is history: its Java 8 compilation target predates the Java 17 platform above.

## Troubleshooting

- If the app opens without game data, check Npcap's compatibility-mode installation, start capture, and reconnect to the game.
- The workspace and **Browse saved history** remain available when assets or Npcap are missing. Use **Choose assets…** to select the game's `resources.assets`, or **Retry assets** to recheck setup. You can also pass `--path "full path to resources.assets"`. Cancelling asset selection leaves the workspace open.
- Explicit Start/Stop retains your capture preference for the next launch; automatic start waits for successful initial asset readiness. Setup failures and manual retries preserve that preference, but a retry does not itself start capture. A failed asset replacement keeps a previously usable cache; a cache without its source file is labeled freshness unverified.
- Check the capture status/footer and `logs/capture-health.log` for capture failures. The Logging workspace can export a diagnostic report.
- Rebuild and restart through the launcher after source updates. Avoid replacing an application's active JAR or runtime folder.
- Use one game connection at a time; multiple simultaneous game clients are not supported.

## Credits and license

Based on [X-com/RealmShark](https://github.com/X-com/RealmShark), with original work by Anon, [Cortex](https://github.com/MCRcortex), and upstream contributors. Inspired by [abrn/realmlib](https://github.com/abrn/realmlib) and [thomas-crane/realmlib-net](https://github.com/thomas-crane/realmlib-net).

Packet capture uses [ardikars/pcap](https://github.com/ardikars/pcap) and the native pcap/Npcap interface. Historical import and compatibility details are recorded in the [interface guide's history](docs/UI-REDESIGN.md#history).

Distributed under the [MIT License](LICENSE.md). Runtime and dependency notices, and the separate [loot-catalog license](docs/LOOT-CATALOG-LICENSE.txt), remain included.

The Java resource extractor includes code adapted from [UnityPy](https://github.com/K0lb3/UnityPy); its [MIT notice](docs/UNITYPY-LICENSE.txt) is included as `META-INF/licenses/UNITYPY-LICENSE.txt` in the application JAR and `UNITYPY-LICENSE.txt` in the Windows bundle root. The original imported revision was not recorded.
