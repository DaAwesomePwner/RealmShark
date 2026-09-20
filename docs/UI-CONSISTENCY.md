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

### Phase 3 consistency improvements

- Sidebar keyboard focus now has a visible look-and-feel outline, distinct from the selected destination. Desktop label width follows font metrics; compact navigation retains its labeled popup and shortcuts.
- Characters uses page scrolling and font-aware minimum content space. Detail tabs wrap at narrow widths; focusing a control reveals it through nested scroll panes.
- Chat Time/Channel widths follow their actual renderers and headers instead of fixed caps. Columns remain resizable, with horizontal scrolling for narrow tables. Chat filter descriptions wrap and its actions remain reachable in the minimum dialog.
- Notification messages use shared text-view measurement, including caret space, rather than word counting. Background changes to read-only metadata do not scroll the page away from the user's settings.
- **Security → Actions → Equipment details…**, or **Ctrl+E** with roster focus, opens full read-only equipment details from the displayed row. Equipment cells have plain accessible names and descriptions, including unknown and empty states.

Detailed evidence, layout limits, and commands: [Phase 3 validation](STEP-3-UI-CONSISTENCY.md).

## Shared presentation

`src/main/java/tomato/gui/modern/ContentStyle.java` provides:

- Segoe UI at 13 points by default, 12-point metadata, restrained 16–20-point headings and font-aware table/header sizing.
- Standard 28-pixel and dense 24-pixel table rows, consistent cell padding, numeric alignment and retained focus borders. Icon-heavy rows retain a 32-pixel minimum. All grow with larger text.
- Theme-aware surface, text, selection and status colors, including a raised surface for cards and inputs, a pointer-over fill, a quiet violet wash, and separate weights for structural dividers and interactive outlines.
- Width-aware control rows, responsive grids, and short-window page/table scrolling helpers.
- `card(layout)` for a rounded grouping surface, and `rowHover(table)` for the pointer-over row highlight that `table(...)` installs automatically.

Use `ContentStyle.font(component, font)` when deliberately changing a component's font role after construction. `refreshFonts` preserves that role across user font choices and theme changes. Four-card groups use balanced 4/2/1-column layouts. Width changes coalesce ancestor layout invalidation; grid minimum heights preserve controls when GridBagLayout must compress their preferred width.

Use `ContentStyle.wrappingText(text[, minimumRows])` for read-only wrapping metadata. It preserves the metadata font role, measures the actual Swing text allocation, and defers measurement-induced relayout safely across theme changes. Explicit keyboard caret movement still scrolls text into view.

`DisplayFormat` supplies human-readable counts, rates, percentages, durations, and timestamps. Numeric display uses the JVM's FORMAT locale (for example, US `1,234.5` and German `1.234,5`), with explicit domain precision. Missing/nonfinite values display **—**; confirmed zero remains numeric. Identifiers and machine exports retain their original representations. Full timestamps use `yyyy-MM-dd HH:mm:ss` in the current system zone; zone information is available in the relevant details/tooltips. Models retain numeric/time types for sorting, including DST transitions. Activity/Logging searches accept displayed values as well as raw values.

The violet capture button's normal, hover, focused and pressed colors meet a 4.5:1 white-text contrast target. Existing semantic labels and theme-dependent roster icons refresh when the theme changes. Light themes retain the same navigation structure.

### Compact desktop styling — 2026-09-19

The interface now uses shallow 4-pixel control arcs, slim button/input padding, 28-pixel tabs, 32-pixel navigation rows, smaller icons, and 12-pixel desktop / 8-pixel compact outer padding. Pill-shaped capture/navigation overrides have been removed. Metrics use 18-point figures and lighter captions. Chat channels are single-line tabs; on narrow layouts their counts stay available in tooltips and accessible names. The result prioritizes data space while retaining readable type and keyboard focus.

The compact pass passed **237 regression tests**, plus **30 UI checks at each of 150% and 200% Java2D scaling**. Desktop and populated compact screenshots were reviewed, and both the runnable JAR and verified Windows ZIP were rebuilt. Compact-review screenshots are under `build/compact-ui/ui-test`; the latest package is `build/share/RealmShark-Windows-x64.zip`.

### Violet surface and state pass — 2026-09-20

`RealmShark Violet` keeps its accent and its compact metrics, and rebuilds the neutrals beneath them. Surfaces now share the accent's hue instead of a cold charcoal, so depth reads as elevation rather than as a second color, and each step keeps the luminance of the step it replaces.

- **Two border weights.** Structural dividers are quiet so data reads first; the outline of an interactive control stays strong enough to find its edge. A test asserts that ordering.
- **Every control answers the pointer.** Buttons, toggles, menus, tabs and destinations carry explicit hover and pressed colors. Table rows highlight under the pointer, and selection still outranks hover.
- **Softer containers.** Control arcs move from 4 to 6 pixels, and the workspace sits in a rounded card instead of a square rectangle. Scroll bars lose their track and keep a rounded thumb that brightens under the pointer.
- **A readable navigation rail.** The selected destination carries a violet rail on its leading edge and keeps it in compact, icon-only mode. Resting outlines match their own fill, so destinations read as a rail rather than a stack of buttons, and keyboard focus still recolors the real border.
- **One accent everywhere.** The accent is declared before the defaults resolve, so sliders, progress bars, check boxes and radio buttons follow it instead of staying on the stock blue. A ticked box fills with the accent rather than showing a checkmark on a grey square.

Painting cost is unchanged: no animation, no timers and no shadows. The sidebar's one gradient is rebuilt only when its height or theme changes, and it reaches the base color inside the branding row. Row hover repaints the two rows that changed rather than the table.

Text keeps at least 4.5:1 contrast on every new surface, and the capture button keeps its 4.5:1 white-text target in all four states.

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

The surface and state pass was validated on 2026-09-20 using the project-local JDK 17 and Gradle 7.6.4:

- **452 tests passed**, with zero failures or ignored tests.
- **60 UI tests passed at 150%** and **60 at 200% Java2D display scaling**.
- The runnable JAR built successfully and its `--help` smoke check passed.
- Reviewed regenerated desktop and compact screenshots across all fourteen destinations, including populated Chat and DPS meters.
- New tests cover row hover against selection, pointer exit and scrolling, single listener installation, the raised-surface and hover roles under both a dark and a light look-and-feel, the divider-versus-outline weight ordering, the card's rounded corner, and the sidebar wash arriving at its base color before the destination list.
- An independent review of the change reported two defects, a banding seam where the sidebar wash met the destination list and a hover highlight stranded by scrolling. Both were fixed, and each fix is pinned by a test confirmed to fail against the previous behavior.

Screenshots for this pass are under `build/typography-validation/ui-test/screenshots`, with the scaled sets under `ui-Ui150` and `ui-Ui200`. The Windows package was not rebuilt in this pass.

An earlier validation on 2026-09-18 covered the same suites at their then-current size:

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
