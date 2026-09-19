# Consistent, responsive desktop UI

## Using the updated application

Restart with `Launch-RealmShark.cmd` to load the rebuilt `build/libs/RealmShark-v1.2.3.jar` through the protected runtime launcher. `Preview-RealmShark.cmd` opens the interface without starting capture or making startup API requests.

- **Edit > Font** applies the selected content font throughout the workspace. Heading, metadata and monospaced-report roles retain their hierarchy. Table rows and headers grow with their font metrics.
- **Edit > Font > Compact default (Segoe UI, 13)** restores the compact typography. Existing larger-font choices remain available.
- The full sidebar fits all 14 destinations at the default desktop size and standard font. Compact windows have a **menu icon** above the navigation rail: click it or press **Alt+M** for labeled destinations. Existing workspace shortcuts remain available.
- Small windows use wrapping controls, balanced summary grids and page scrolling where necessary. Tables retain usable row space instead of collapsing beneath filters and detail panels.
- **Chat > Actions > Chat filters** opens player-ignore, blocked-phrase and advertisement settings. The selected-message panel continues to show full message text.
- **Security > Options** exposes sorting and copy restrictions. **Actions** provides explicit exports and player/guild actions, with keyboard shortcuts shown in the menu. Copy names and Copy all remain directly available.
- **Characters > Pets** labels the feed-power input and provides visible positive-number validation and a **Recalculate feeding costs** action.
- The DPS encounter chooser supports keyboard selection and separate export checkboxes. Live legacy reports remain selectable and read-only. Its compact toolbar wraps as the available width changes.
- The resource chart has keyboard-accessible zoom/reset and sample inspection. Rule-editor add/remove controls have meaningful labels and keyboard focus.

## Shared presentation

`src/main/java/tomato/gui/modern/ContentStyle.java` provides:

- Segoe UI at 13 points by default, 12-point metadata, restrained 16–20-point headings and font-aware table/header sizing.
- Standard 28-pixel and dense 24-pixel table rows, consistent cell padding, numeric alignment and retained focus borders. Icon-heavy rows retain a 32-pixel minimum. All grow with larger text.
- Theme-aware surface, text, selection and status colors.
- Width-aware control rows, responsive grids, and short-window page/table scrolling helpers.

Use `ContentStyle.font(component, font)` when deliberately changing a component's font role after construction. `refreshFonts` preserves that role across user font choices and theme changes. Four-card groups use balanced 4/2/1-column layouts. Width changes coalesce ancestor layout invalidation; grid minimum heights preserve controls when GridBagLayout must compress their preferred width.

The violet capture button's normal, hover, focused and pressed colors meet a 4.5:1 white-text contrast target. Existing semantic labels and theme-dependent roster icons refresh when the theme changes. Light themes retain the same navigation structure.

### Compact desktop styling — 2026-09-19

The interface now uses shallow 4-pixel control arcs, slim button/input padding, 28-pixel tabs, 32-pixel navigation rows, smaller icons, and 12-pixel desktop / 8-pixel compact outer padding. Pill-shaped capture/navigation overrides have been removed. Metrics use 18-point figures and lighter captions. Chat channels are single-line tabs; on narrow layouts their counts stay available in tooltips and accessible names. The result prioritizes data space while retaining readable type and keyboard focus.

The compact pass passed **237 regression tests**, plus **30 UI checks at each of 150% and 200% Java2D scaling**. Desktop and populated compact screenshots were reviewed, and both the runnable JAR and verified Windows ZIP were rebuilt. Compact-review screenshots are under `build/compact-ui/ui-test`; the latest package is `build/share/RealmShark-Windows-x64.zip`.

## Responsiveness changes

- Optional version/Crucible requests follow initial window creation and have finite HTTP timeouts. Essential local dungeon history initializes before capture autostart.
- Account metadata requests run on a bounded background worker. Detached results are applied on capture updates after identity checks; connection/account changes invalidate stale requests. Pet Yard identification supports this asynchronous path.
- Capture Stop no longer sleeps on the Swing event-dispatch thread.
- Character-journal and dungeon-history persistence perform serialization and file I/O outside their model monitors. Initial dungeon-history loading merges concurrent observations. Failed optional history reads allow the window to open while preserving the original file and suspending history writes until successful recovery.
- Fame saves use detached, ordered background requests with coalescing and visible completion/failure status. Save/delete ordering and stale-completion guards preserve session state.
- DPS import/export and Key-pop CSV writes use background workers. DPS player filtering reuses encounter aggregates, and meter maxima are computed outside individual cell painting.
- Security roster updates use detached data, a renderer-based table and coalesced visible refreshes.
- The legacy Loot log retains at most 1,000 rendered entries. Shared Loot dashboards batch model updates and refresh hidden views when shown.
- My Info batches detached player/pet updates. Chat and Key-pop hidden views avoid recurring presentation work. Logging/Activity update relevant visible views and avoid unchanged table replacement.

These changes preserve existing capture, account and saved-file behavior, with regression checks for the affected paths. They do not constitute a measured prolonged-gameplay performance benchmark.

## Validation

Validated on 2026-09-18 using the project-local JDK 17 and Gradle 7.6.4:

- **216 tests passed**, with zero failures or ignored tests.
- **28 UI tests passed at 150%** and **28 at 200% Java2D display scaling**.
- The runnable JAR built successfully and its `--help` smoke check passed.
- Reviewed regenerated desktop/compact screenshots, including populated Chat, Key-pop, My Info and statistics views.
- Independent UI and persistence/concurrency reviews completed; reported blockers were corrected and verified.

Coverage includes blocked-EDT producers, blocked file stores, detached snapshots, hidden-view catch-up, retention bounds, keyboard actions, theme/font transitions, minimum-size layouts, failed optional-history startup and metadata account/connection changes. Testing uses preview and synthetic data; live capture and a prolonged gameplay soak were not performed in this pass.

Reproduce with JDK 17 available to Gradle:

```powershell
.\gradlew.bat -I scripts/typography-validation.gradle test testUi150 testUi200
.\gradlew.bat shadowJar
```

Reports: `build/typography-validation/reports/tests/{test,testUi150,testUi200}/index.html`.

Default screenshots: `build/typography-validation/ui-test/screenshots`. Scaled screenshots use `build/typography-validation/ui-Ui150` and `ui-Ui200`. Test preferences and generated session data stay inside their respective build working directories.
