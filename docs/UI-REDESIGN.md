# RealmShark desktop refresh

This folder now builds a complete Swing desktop application. The supplied download contained the RealmShark library branch, without the Tomato application described in its README. The Tomato GUI and its backend/resources were imported from `X-com/RealmShark`, branch `tomato`, commit `257a1c5` (2026-02-28). The supplied library sources remain in place. The upstream MIT license and credits are preserved.

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
