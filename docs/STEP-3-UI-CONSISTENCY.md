# Step 3 — compact layouts, keyboard access, and formatting

Tracking: [issue #5](https://github.com/DaAwesomePwner/RealmShark/issues/5). Branch: `ui/step-3-consistency`, based on `main` merge `1abfb2d` of [Phase 2 PR #4](https://github.com/DaAwesomePwner/RealmShark/pull/4).

The [post-merge main CI run](https://github.com/DaAwesomePwner/RealmShark/actions/runs/35480377733) passed before Phase 3 began, satisfying the requested ordering gate. This phase addresses review findings 8, 9, 10, 11, and 13, plus shared display formatting.

## Implemented behavior

### Compact Characters

- Outer page scrolling preserves both roster and selected details when available height is small.
- Minimum heights include font-dependent headers, wrapped metadata, tab chrome, and at least three usable table/notes rows.
- Detail tabs wrap at narrow widths. Divider positions are constrained to usable child space after resizing/font changes.
- Focusing search, filters, or detail actions reveals them through nested viewports.
- Long names retain full tooltips; dates use full local timestamps and fame uses display-locale formatting. IDs remain raw.
- Notes tests verify drafts survive asynchronous roster refresh/reordering and persist against the correct character identity.

### Keyboard focus and equipment access

- Sidebar navigation retains the look-and-feel focus border and uses margins for spacing. Selection colors remain separate from focused/unfocused painting.
- Desktop sidebar width accommodates labels, icons, border padding, and scrollbar; compact navigation keeps its menu, accessible labels, and shortcuts.
- Security equipment renderers reset plain accessible names/descriptions on every reuse, including slot, item/ID, enchant details, and missing/empty/unrecognized states.
- **Actions → Equipment details… / Ctrl+E** opens a read-only wrapping dialog from the selected displayed-row snapshot. Subsequent capture changes do not mutate an already-open dialog.
- Existing Security column reordering is preserved; details and accessibility use model/view conversion correctly through sorting/filtering/reordering.

### Shared wrapping and Chat

- `ContentStyle.wrappingText` centralizes read-only metadata layout using the actual TextUI allocation, including caret margin, Unicode/complex-text view behavior, and explicit newlines.
- Measurement-induced invalidation is deferred/coalesced. This prevents nested BoxLayout measurement from invalidating its own arrays during theme transitions.
- Background metadata edits use a non-auto-updating caret policy, preserving scroll position. Explicit caret navigation still reveals the endpoint.
- Chat Time/Channel widths are measured from real cell/header renderers and font roles; the former fixed caps are removed. Columns can be widened and narrow tables scroll horizontally.
- Chat filter captions have wrapping scope descriptions. Editors retain usable rows and Save/Cancel remain reachable at a **460×360 outer dialog minimum**.
- Notification status height follows the complete text view, including a valid 184-character filename. It can increase page height instead of clipping text or collapsing settings.

### Display formatting

`DisplayFormat` provides the shared human-readable policy, with existing statistics `Formatters` delegating to it:

| Value | Policy |
| --- | --- |
| Counts | FORMAT-locale grouping; `long` values never pass through `double` |
| Exact values | Preserve the source decimal representation, including BigDecimal precision |
| Rates/decimals | Explicit domain precision with HALF_UP rounding |
| Percentages | Percentage-point input and explicit precision; e.g. German `10,5%` |
| Missing/nonfinite | `—`, distinct from numeric zero |
| IDs/protocol evidence | Raw identifiers, flags, and diagnostic data |
| Durations | Explicit whole-second, millisecond, or decimal-second forms; hours can exceed 24 |
| Timestamps | `yyyy-MM-dd HH:mm:ss` in the current system zone, with optional compact time/date modes |

The policy is used across live/saved fame, dungeon/loot statistics, Characters fame, My Info, DPS meter/legacy text/icon views, Key-pops, Activity, and Logging. Mutable NumberFormat instances are not shared between threads. Locale and zone are resolved at presentation time.

Activity/Logging use typed timestamps and numeric intervals. Search converters accept displayed localized text as well as raw values. Retained/frozen views reformat from existing data when the JVM's FORMAT locale or zone changes, without requiring a new capture snapshot. Unfrozen views continue normal revision polling; unchanged revisions avoid snapshot copies. Fame Entered values remain epoch numbers, preserving chronological sorting through DST fallback.

Machine CSV/JSON/serialized DPS values, identifiers, persisted key-pop logs, and filename timestamp formats remain outside the display-formatting policy. Tests compare exports across US and German formatting; diagnostic comparisons normalize only their freshly generated snapshot timestamps.

## Validation

JDK 17.0.20.1+1 and Gradle 7.6.4:

- **445 regular tests passed**, zero failed or ignored: **43 more than the Phase 2 baseline**.
- **53 selected UI tests passed at 150%** and **53 at 200%**, zero failed or ignored. The scaling suite now includes Characters layout, Chat consistency, and Notifications consistency tests.
- Runnable JAR build and `--help` smoke check passed.
- Independent reviews covered layout/focus, accessibility, locale/search/chronology, compatibility, and the final caret/scroll fix. Findings were corrected and re-reviewed.
- Fresh representative Characters, Chat filters, and Notifications screenshots were inspected after layout settling.

```powershell
.\gradlew.bat --offline --no-daemon --continue -I scripts/typography-validation.gradle -PrealmSharkBuildDir=build/step3-validation test testUi150 testUi200 shadowJar
java -jar build/step3-validation/libs/RealmShark-v1.2.3.jar --help
```

Reports: `build/step3-validation/reports/tests/{test,testUi150,testUi200}/index.html`. Screenshots are under the test working directories, including `ui-test/screenshots` and the Characters images directly under `ui-test`.

### Coverage and limits

- Exact **1240×800 and 680×520 logical client geometry** is tested in offscreen trees using the real shell and relevant feature/tab structure, with body fonts **13/16/24**.
- Native window tests exercise realized dimensions, focus, resizing, theme transitions, scrolling, and the minimum Chat filter dialog. Requested-versus-realized sizes are reported; host-clamped windows are not counted as exact-size native passes. No tests are skipped for these clamps.
- Cases include empty/populated/partial/filtered data, long names/notes/filenames, status errors, Unicode, exact wrapping boundaries, and US/German locales.
- Navigation focus-painting checks include an old-EmptyBorder negative control. Equipment keyboard integration posts AWT key events through KeyboardFocusManager and component bindings into the real owned dialog; it does not claim native hardware-event or screen-reader validation.
- Hosted Windows CI runs the entire regular suite and JAR/help checks. The 150%/200% executions are local; the hosted display limitation documented in Phase 1 remains applicable.

## Phase 2 responsiveness regression check

The existing opt-in benchmark was updated only for the new typed UI timestamps and display strings; its terminal-state and retention assertions remain intact. It was rerun with **100,000 updates per pipeline**, six hours of logical history, and the same isolation/limits documented in [Phase 2](STEP-2-RESPONSIVENESS.md).

```powershell
.\gradlew.bat --offline --no-daemon -I scripts/responsiveness-validation.gradle -PrealmSharkBuildDir=build/step3-validation responsivenessBenchmark
```

Functional/retention assertions passed in **16.33 seconds**. Measured visible-phase EDT p95 was **1.027 ms for fame** and **0.141 ms for discovery**; maximums were **10.476 ms** and **20.715 ms**. Discovery recorded one skipped heartbeat tick. All measured phases remained within the benchmark's report-only budgets. Warmup observations are excluded from the quoted visible-phase EDT statistics; the total harness wall time includes warmup.

This is synthetic tracking/decoded-observer evidence, not live network throughput, a real-time six-hour soak, or proof of a statistically significant performance change. No live capture or screen-reader session was performed.
