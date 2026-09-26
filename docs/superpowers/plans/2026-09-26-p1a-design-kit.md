# P1a Design Kit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the reusable `tomato.gui.kit` design system (tokens, value display, column kinds, buttons, chips, cards, tiles, collapsibles, filter bar, customizable tabs, game widgets, Simple/Analyst mode, motion) with tests and a screenshot gallery, without changing any existing page yet.

**Architecture:** A new package `tomato.gui.kit` sits on top of `tomato.gui.modern` (`ContentStyle`, `Themes`, `LineIcon`). Components resolve colors from `Tokens` roles at paint time or in `updateUI()`, so they follow Violet Dark/Light and Increase contrast. Persistence goes through small `read`/`write` function pairs that default to `PropertiesManager`, so tests use in-memory maps. P1b adopts these components in the shell and pages.

**Tech Stack:** Java 17, Swing, FlatLaf 3.5.4, JNA 5.12.1 (core only), JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md` §3.2, §3.3, §4.4, §5 (all), §10.

**Depends on:** P0 merged (`Themes`, `VioletLightTheme`, Java 17 target).

## Global Constraints

- Branch from verified `main` (with P0 merged) as `feat/redesign-p1a-kit` in its own worktree.
- Gradle, from the worktree root in Git Bash:
  ```bash
  RS_TOOLS="C:/Users/dap/Downloads/RealmShark-realmshark/.tools"
  export JAVA_HOME="$RS_TOOLS/jdk-17.0.20.1+1" GRADLE_USER_HOME="$RS_TOOLS/gradle-home"
  ./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a <tasks>
  ```
  Use only `build/p1a` for this phase. UI tests open real windows: run one Gradle invocation at a time on the desktop. Never run `scripts/Set-CiDisplay.ps1`; never start capture.
- All kit code lives in `src/main/java/tomato/gui/kit/`; tests in `src/test/java/tomato/gui/kit/`. Do not modify existing pages in this phase (only `LineIcon` gains glyphs).
- Colors: never hard-code hex in kit components; use `Tokens.color(Role)`, `Tokens.tone(Tone)`, `Tokens.bag(...)`, `Tokens.tier(...)`. Reapply colors in `updateUI()` (guard fields that are still null during the superclass constructor).
- Fonts: set with `ContentStyle.font(component, font)` so `ContentStyle.refreshFonts` keeps the role.
- Motion: at most `Motion.MAX_MILLIS` (100 ms), only through `Motion.run`, and none when Reduce motion or the Windows animation setting is off.
- Text: sentence case; unknown values render as `DisplayFormat.UNAVAILABLE` ("—"); every icon-only control has a tooltip and accessible name.
- Persisted keys (exact): `ui.mode`, `ui.reduceMotion`, `ui.collapse.<id>`, `ui.filters.<name>.open`, `ui.tabs.<group>`. `PropertiesManager` rejects null values; restore missing values as `""` in tests.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File map

| File | Responsibility |
|---|---|
| `kit/Tokens.java` | Color roles, tones, bag/tier colors, spacing and arc constants |
| `kit/Type.java` | Font roles: caption, body, emphasis, title, page title, metric |
| `kit/KitFormat.java` | Relative time, short duration, compact numbers |
| `kit/DisplayValue.java` | Value + honesty state (known, zero, estimate, manual, partial, stale, unknown) |
| `kit/ColumnKind.java`, `kit/KitTables.java` | Shared column widths/alignment/renderers |
| `modern/LineIcon.java` (modify) | New glyph constants |
| `kit/Sprites.java` | Game sprites with placeholder fallback and cache |
| `kit/DisplayModeModel.java` | Simple/Analyst mode, persisted, lifecycle-safe listeners |
| `kit/Motion.java` | ≤100 ms animations with Reduce motion and Windows setting |
| `kit/KitButton.java`, `kit/Chip.java`, `kit/SegmentedControl.java`, `kit/OverflowMenu.java` | Controls |
| `kit/SectionHeader.java`, `kit/EvidenceNote.java`, `kit/Card.java`, `kit/StatTile.java`, `kit/EmptyState.java`, `kit/Collapsible.java` | Containers |
| `kit/FilterBar.java` | Search + Filters drawer + active chips + scope + overflow |
| `kit/CustomizableTabs.java` | Reorder/hide tabs with stable IDs |
| `kit/ItemSlot.java`, `kit/PipMeter.java`, `kit/StatBar.java`, `kit/Sparkline.java` | Game widgets |
| `test/.../kit/KitGallery.java`, `KitGalleryEvidenceTest.java` | Screenshot gallery in both variants |

---

### Task 1: Tokens, type roles, formatting and DisplayValue

**Files:**
- Create: `src/main/java/tomato/gui/kit/Tokens.java`, `Type.java`, `KitFormat.java`, `DisplayValue.java`
- Test: `src/test/java/tomato/gui/kit/TokensTest.java`, `KitFormatTest.java`, `DisplayValueTest.java`

**Interfaces:**
- Consumes: `tomato.gui.modern.ContentStyle.color(String)`, `ContentStyle.body/metadata/emphasis`, `tomato.gui.modern.VioletTheme.CAPTURE_BACKGROUND`, `tomato.gui.modern.DisplayFormat`, `tomato.gui.modern.Themes` (tests).
- Produces:
  - `enum Tokens.Role { CANVAS, NAV, SURFACE, SURFACE_ALT, RAISED, CONTROL, BORDER_SUBTLE, BORDER, ACCENT, ACCENT_TEXT, ACCENT_WASH, SELECTION, SELECTION_TEXT, TEXT, TEXT_MUTED, GOOD, WARN, BAD, INFO, PRIMARY }`
  - `enum Tokens.Tone { NEUTRAL, ACCENT, GOOD, WARN, BAD, INFO }`
  - `static Color Tokens.color(Role)`, `tone(Tone)`, `tint(Color)`, `blend(Color, Color, float)`, `bag(String)`, `tier(String)`; `static boolean Tokens.dark()`
  - constants `Tokens.XS=4, S=8, M=12, L=16, XL=24, ARC_CONTROL=6, ARC_CARD=10, ARC_CHIP=8`
  - `static Font Type.caption(), body(), emphasis(), title(), pageTitle(), metric()`
  - `static String KitFormat.relative(long epochMillis)`, `duration(long millis)`, `compact(double)`; package-private `static LongSupplier KitFormat.clock`
  - `final class DisplayValue` with `enum State`, factories `known, zero, estimate, manual, partial, stale, unknown, count`, and `text(), suffix(), display(), dimmed(), tooltip()`, public final fields `state`, `detail`

- [ ] **Step 1: Write the failing tests**

`src/test/java/tomato/gui/kit/TokensTest.java`:
```java
package tomato.gui.kit;

import java.awt.Color;
import javax.swing.SwingUtilities;
import org.junit.*;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

public class TokensTest {
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void rolesFollowTheInstalledVariant() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertTrue(Tokens.dark());
            assertEquals(new Color(0x181627), Tokens.color(Tokens.Role.SURFACE));
            assertEquals(new Color(0xAD8CFF), Tokens.color(Tokens.Role.ACCENT));
            assertEquals(new Color(0xC4ADFF), Tokens.color(Tokens.Role.ACCENT_TEXT));
            assertEquals(new Color(0x7041BD), Tokens.color(Tokens.Role.PRIMARY));
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            assertFalse(Tokens.dark());
            assertEquals(new Color(0x6241AA), Tokens.color(Tokens.Role.ACCENT_TEXT));
            assertEquals(new Color(0xFFFFFF), Tokens.color(Tokens.Role.SURFACE));
        });
    }

    @Test public void bagAndTierColorsReuseSemanticRoles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertEquals(Tokens.color(Tokens.Role.TEXT), Tokens.bag("White"));
            assertEquals(Tokens.color(Tokens.Role.TEXT), Tokens.bag("B.White"));
            assertEquals(Tokens.color(Tokens.Role.WARN), Tokens.bag("Orange"));
            assertEquals(Tokens.color(Tokens.Role.GOOD), Tokens.bag("Egg Basket"));
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), Tokens.bag("Soulbound"));
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), Tokens.bag(null));
            assertEquals(Tokens.color(Tokens.Role.WARN), Tokens.tier("UT"));
            assertEquals(Tokens.color(Tokens.Role.BAD), Tokens.tier("st"));
            assertEquals(Tokens.color(Tokens.Role.INFO), Tokens.tier("CONSUMABLE"));
            assertEquals(Tokens.color(Tokens.Role.BORDER), Tokens.tier("T13"));
        });
    }

    @Test public void tintSitsBetweenTheSurfaceAndTheInk() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            Color surface = Tokens.color(Tokens.Role.SURFACE), tint = Tokens.tint(Color.WHITE);
            assertTrue(tint.getRed() > surface.getRed() && tint.getRed() < 255);
            assertEquals(new Color(50, 100, 150), Tokens.blend(new Color(0, 100, 200), new Color(100, 100, 100), .5f));
        });
    }
}
```

`src/test/java/tomato/gui/kit/KitFormatTest.java`:
```java
package tomato.gui.kit;

import java.util.Locale;
import java.util.function.LongSupplier;
import org.junit.*;
import static org.junit.Assert.*;

public class KitFormatTest {
    private static final long NOW = 1_800_000_000_000L;
    private Locale previous;
    private LongSupplier previousClock;

    @Before public void fix() {
        previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        previousClock = KitFormat.clock;
        KitFormat.clock = () -> NOW;
    }
    @After public void restore() {
        Locale.setDefault(Locale.Category.FORMAT, previous);
        KitFormat.clock = previousClock;
    }

    @Test public void relativeTimeUsesShortPlayerFacingSteps() {
        assertEquals("just now", KitFormat.relative(NOW - 30_000));
        assertEquals("just now", KitFormat.relative(NOW + 60_000));
        assertEquals("12 min ago", KitFormat.relative(NOW - 12 * 60_000));
        assertEquals("3 h ago", KitFormat.relative(NOW - 3 * 3_600_000));
        assertEquals("yesterday", KitFormat.relative(NOW - 30 * 3_600_000L));
        assertEquals("4 days ago", KitFormat.relative(NOW - 4 * 86_400_000L));
        assertTrue(KitFormat.relative(NOW - 30 * 86_400_000L).matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test public void durationsAreCompact() {
        assertEquals("42s", KitFormat.duration(42_900));
        assertEquals("9m 51s", KitFormat.duration(591_000));
        assertEquals("1h 12m", KitFormat.duration(4_320_000));
        assertEquals("—", KitFormat.duration(-1));
    }

    @Test public void compactNumbersKeepOneDecimalAtMost() {
        assertEquals("25", KitFormat.compact(25));
        assertEquals("25.5", KitFormat.compact(25.5));
        assertEquals("999.9", KitFormat.compact(999.94));
        assertEquals("1.5k", KitFormat.compact(1500));
        assertEquals("41.2k", KitFormat.compact(41_234));
        assertEquals("2.3M", KitFormat.compact(2_300_000));
        assertEquals("1M", KitFormat.compact(999_960));
        assertEquals("-1.5k", KitFormat.compact(-1500));
        assertEquals("—", KitFormat.compact(Double.NaN));
    }
}
```

`src/test/java/tomato/gui/kit/DisplayValueTest.java`:
```java
package tomato.gui.kit;

import java.util.Locale;
import org.junit.*;
import static org.junit.Assert.*;

public class DisplayValueTest {
    private Locale previous;
    @Before public void fix() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void unknownIsNeverZero() {
        DisplayValue unknown = DisplayValue.unknown("Visit the Pet Yard with capture on to load pets");
        assertEquals("—", unknown.text());
        assertTrue(unknown.dimmed());
        assertEquals("Visit the Pet Yard with capture on to load pets", unknown.tooltip());
        DisplayValue zero = DisplayValue.zero("Counted from 3 saved runs");
        assertEquals("0", zero.display());
        assertFalse(zero.dimmed());
    }

    @Test public void statesCarryTheirMarkers() {
        String approximate = DisplayValue.estimate("1,240", "Weapon formula").text();
        assertTrue(approximate, approximate.equals("\u2248 1,240") || approximate.equals("~ 1,240"));
        assertEquals("5 (manual)", DisplayValue.manual("5", "Entered 2026-09-26").display());
        assertEquals("7 (partial)", DisplayValue.partial("7", "Backpack not captured").display());
        DisplayValue stale = DisplayValue.stale("1,240", "Captured 2 h ago");
        assertEquals("1,240 (stale)", stale.display());
        assertTrue(stale.dimmed());
        assertEquals("1,240", DisplayValue.known("1,240", "Live").display());
        assertNull(DisplayValue.known("1,240", "").tooltip());
    }

    @Test public void countMapsNullZeroAndValues() {
        assertEquals(DisplayValue.State.UNKNOWN, DisplayValue.count(null, "Saved runs", "Not recorded").state);
        assertEquals(DisplayValue.State.ZERO, DisplayValue.count(0L, "Saved runs", "Not recorded").state);
        assertEquals("12,345", DisplayValue.count(12_345L, "Saved runs", "Not recorded").display());
    }

    @Test public void equalityIsByValue() {
        assertEquals(DisplayValue.known("1", "a"), DisplayValue.known("1", "a"));
        assertNotEquals(DisplayValue.known("1", "a"), DisplayValue.estimate("1", "a"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.*"`
Expected: FAIL — compilation errors, `package tomato.gui.kit does not exist` / `cannot find symbol: class Tokens`.

- [ ] **Step 3: Implement `Tokens.java`**

```java
package tomato.gui.kit;

import java.awt.Color;
import java.util.Locale;
import javax.swing.UIManager;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;

/** Semantic colors and spacing for kit components. Resolve colors when painting or in updateUI; they follow the theme. */
public final class Tokens {
    public enum Role {
        CANVAS, NAV, SURFACE, SURFACE_ALT, RAISED, CONTROL, BORDER_SUBTLE, BORDER,
        ACCENT, ACCENT_TEXT, ACCENT_WASH, SELECTION, SELECTION_TEXT, TEXT, TEXT_MUTED,
        GOOD, WARN, BAD, INFO, PRIMARY
    }

    /** Status meaning only; game meaning (bags, tiers) has its own helpers. */
    public enum Tone { NEUTRAL, ACCENT, GOOD, WARN, BAD, INFO }

    public static final int XS = 4, S = 8, M = 12, L = 16, XL = 24;
    /** Graphics arc diameters: controls match FlatLaf's Component.arc, cards match ContentStyle.card. */
    public static final int ARC_CONTROL = 6, ARC_CARD = 10, ARC_CHIP = 8;

    private Tokens() {}

    public static Color color(Role role) {
        switch (role) {
            case CANVAS: return ContentStyle.color("background");
            case NAV: return ContentStyle.color("navigation");
            case SURFACE: return ContentStyle.color("surface");
            case SURFACE_ALT: return ui("Table.alternateRowColor", ContentStyle.color("surface"));
            case RAISED: return ContentStyle.color("surfaceRaised");
            case CONTROL: return ui("Button.background", ContentStyle.color("surfaceRaised"));
            case BORDER_SUBTLE: return ContentStyle.color("border");
            case BORDER: return ContentStyle.color("controlBorder");
            case ACCENT: return ui("Component.accentColor", ContentStyle.color("violet"));
            case ACCENT_TEXT: return ContentStyle.color("violet");
            case ACCENT_WASH: return ContentStyle.color("accentWash");
            case SELECTION: return ContentStyle.color("selection");
            case SELECTION_TEXT: return ContentStyle.color("selectionText");
            case TEXT: return ContentStyle.color("text");
            case TEXT_MUTED: return ContentStyle.color("muted");
            case GOOD: return ContentStyle.color("mint");
            case WARN: return ContentStyle.color("amber");
            case BAD: return ContentStyle.color("rose");
            case INFO: return ContentStyle.color("blue");
            case PRIMARY: return VioletTheme.CAPTURE_BACKGROUND;
            default: throw new IllegalArgumentException("Unknown role " + role);
        }
    }

    public static Color tone(Tone tone) {
        switch (tone) {
            case ACCENT: return color(Role.ACCENT_TEXT);
            case GOOD: return color(Role.GOOD);
            case WARN: return color(Role.WARN);
            case BAD: return color(Role.BAD);
            case INFO: return color(Role.INFO);
            default: return color(Role.TEXT_MUTED);
        }
    }

    public static boolean dark() {
        Color surface = color(Role.SURFACE);
        return surface.getRed() * .2126 + surface.getGreen() * .7152 + surface.getBlue() * .0722 < 128;
    }

    /** An opaque wash of the ink over the surface, for chip and badge backgrounds. */
    public static Color tint(Color ink) { return blend(color(Role.SURFACE), ink, dark() ? .18f : .14f); }

    public static Color blend(Color from, Color to, float weight) {
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * weight),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * weight),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * weight));
    }

    /** Bag color by LootBags display name ("White", "B.White", "Egg Basket"); boosted bags share their base color. */
    public static Color bag(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("b.")) key = key.substring(2).trim();
        if (key.endsWith(" bag")) key = key.substring(0, key.length() - 4);
        switch (key) {
            case "white": return color(Role.TEXT);
            case "orange": case "gold": return color(Role.WARN);
            case "red": case "pink": return color(Role.BAD);
            case "blue": case "teal": return color(Role.INFO);
            case "purple": return color(Role.ACCENT_TEXT);
            case "egg": case "egg basket": return color(Role.GOOD);
            default: return color(Role.TEXT_MUTED);
        }
    }

    /** Sprite border by item label (RosterDefinitions/ParseEquipment): UT, ST, CONSUMABLE, T0–T15. */
    public static Color tier(String label) {
        String key = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
        switch (key) {
            case "UT": return color(Role.WARN);
            case "ST": return color(Role.BAD);
            case "CONSUMABLE": return color(Role.INFO);
            default: return color(Role.BORDER);
        }
    }

    private static Color ui(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : new Color(value.getRGB(), true);
    }
}
```

- [ ] **Step 4: Implement `Type.java`**

```java
package tomato.gui.kit;

import java.awt.Font;
import tomato.gui.modern.ContentStyle;

/** Font roles in em of the body font, so every role follows Edit › Font. Apply with ContentStyle.font. */
public final class Type {
    private Type() {}
    public static Font caption() { return ContentStyle.metadata(ContentStyle.body()); }
    public static Font body() { return ContentStyle.body(); }
    public static Font emphasis() { return ContentStyle.emphasis(ContentStyle.body()); }
    public static Font title() { return scaled(1.15f); }
    public static Font pageTitle() { return scaled(1.4f); }
    public static Font metric() { return scaled(1.35f); }

    private static Font scaled(float scale) {
        Font body = ContentStyle.body();
        return ContentStyle.emphasis(body.deriveFont(body.getSize2D() * scale));
    }
}
```

- [ ] **Step 5: Implement `KitFormat.java`**

```java
package tomato.gui.kit;

import java.time.Instant;
import java.time.ZoneId;
import java.util.function.LongSupplier;
import tomato.gui.modern.DisplayFormat;

/** Short human-readable text for glance surfaces. Never use for persistence or exports. */
public final class KitFormat {
    private static final String[] SUFFIXES = {"", "k", "M", "B"};
    /** Replaced by tests only. */
    static LongSupplier clock = System::currentTimeMillis;

    private KitFormat() {}

    /** "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", then the date. */
    public static String relative(long epochMillis) {
        long minutes = Math.max(0, clock.getAsLong() - epochMillis) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        return DisplayFormat.DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** "42s", "9m 51s", "1h 12m". Negative durations are unknown. */
    public static String duration(long millis) {
        if (millis < 0) return DisplayFormat.UNAVAILABLE;
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + seconds % 60 + "s";
        return minutes / 60 + "h " + minutes % 60 + "m";
    }

    /** 25, 25.5, 41.2k, 2.3M: one decimal at most, promoted before it would round up to 1,000. */
    public static String compact(double value) {
        if (!Double.isFinite(value)) return DisplayFormat.UNAVAILABLE;
        int index = 0;
        double scaled = value;
        while (index < SUFFIXES.length - 1 && Math.abs(scaled) >= 999.95) { scaled /= 1000; index++; }
        return DisplayFormat.formatNumber(scaled, 0, 1) + SUFFIXES[index];
    }
}
```

- [ ] **Step 6: Implement `DisplayValue.java`**

```java
package tomato.gui.kit;

import java.util.Objects;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * A value to show plus how much it can be trusted. Unknown is never rendered as zero, estimates
 * carry "≈", and manual, partial and stale values say so; the detail becomes the tooltip.
 */
public final class DisplayValue {
    public enum State { KNOWN, ZERO, ESTIMATE, MANUAL, PARTIAL, STALE, UNKNOWN }

    public final State state;
    /** Source, method, missing part, age or reason, depending on the state. May be empty. */
    public final String detail;
    private final String text;

    private DisplayValue(State state, String text, String detail) {
        this.state = Objects.requireNonNull(state, "state");
        this.text = text;
        this.detail = detail == null ? "" : detail;
    }

    public static DisplayValue known(String text, String source) { return new DisplayValue(State.KNOWN, required(text), source); }
    public static DisplayValue zero(String source) { return new DisplayValue(State.ZERO, "0", source); }
    public static DisplayValue estimate(String text, String how) { return new DisplayValue(State.ESTIMATE, required(text), how); }
    public static DisplayValue manual(String text, String who) { return new DisplayValue(State.MANUAL, required(text), who); }
    public static DisplayValue partial(String text, String missing) { return new DisplayValue(State.PARTIAL, required(text), missing); }
    public static DisplayValue stale(String text, String age) { return new DisplayValue(State.STALE, required(text), age); }
    public static DisplayValue unknown(String why) { return new DisplayValue(State.UNKNOWN, null, why); }

    /** Counts: null is unknown, 0 is a real zero, anything else is known. */
    public static DisplayValue count(Long value, String source, String whyUnknown) {
        if (value == null) return unknown(whyUnknown);
        if (value == 0) return zero(source);
        return known(DisplayFormat.formatInteger(value.longValue()), source);
    }

    private static String required(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Use DisplayValue.unknown for missing values");
        return text;
    }

    /** The value itself: "—" when unknown, prefixed with "≈" when estimated. */
    public String text() {
        if (state == State.UNKNOWN) return DisplayFormat.UNAVAILABLE;
        if (state == State.ESTIMATE) return approximately() + " " + text;
        return text;
    }

    /** A short qualifier for plain-text contexts such as table cells; empty when none applies. */
    public String suffix() {
        switch (state) {
            case MANUAL: return "manual";
            case PARTIAL: return "partial";
            case STALE: return "stale";
            default: return "";
        }
    }

    public String display() {
        String suffix = suffix();
        return suffix.isEmpty() ? text() : text() + " (" + suffix + ")";
    }

    public boolean dimmed() { return state == State.UNKNOWN || state == State.STALE; }

    public String tooltip() { return detail.isEmpty() ? null : detail; }

    /** Segoe UI has "≈"; fall back to "~" for fonts that do not. */
    static String approximately() { return ContentStyle.body().canDisplay('\u2248') ? "\u2248" : "~"; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof DisplayValue)) return false;
        DisplayValue value = (DisplayValue) other;
        return state == value.state && Objects.equals(text, value.text) && detail.equals(value.detail);
    }
    @Override public int hashCode() { return Objects.hash(state, text, detail); }
    @Override public String toString() { return display(); }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.*"`
Expected: PASS (10 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/tomato/gui/kit src/test/java/tomato/gui/kit
git commit -m "Add kit tokens, type roles, formatting and DisplayValue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Column kinds and table renderers

**Files:**
- Create: `src/main/java/tomato/gui/kit/ColumnKind.java`, `KitTables.java`
- Test: `src/test/java/tomato/gui/kit/KitTablesTest.java`

**Interfaces:**
- Consumes: Task 1 (`Tokens`, `KitFormat`, `DisplayValue`), `ContentStyle.Cell`, `ContentStyle.Badge`, `ContentStyle.report`, `DisplayFormat`.
- Produces:
  - `enum ColumnKind { TIME_RELATIVE, DATE_TIME, DURATION, DUNGEON, PLAYER, CLASS, ITEM, COUNT, NUMBER, PERCENT, STATUS, ID, TEXT }` with `public final float em`, `public final int alignment`, `int width(Font)`
  - `static void KitTables.apply(JTable, ColumnKind...)` (by view position), `static void KitTables.apply(JTable, Map<String, ColumnKind>)` (by column identifier)
  - `static TableCellRenderer KitTables.renderer(ColumnKind)`, `static TableCellRenderer KitTables.status(Function<Object, Tokens.Tone>)`
  - `final class KitTables.IconText implements Comparable<IconText>` with `icon`, `text`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;

public class KitTablesTest {
    private static final long NOW = 1_800_000_000_000L;
    private LongSupplier previousClock;
    private Locale previous;

    @Before public void fix() {
        previousClock = KitFormat.clock; KitFormat.clock = () -> NOW;
        previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }
    @After public void restore() { KitFormat.clock = previousClock; Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void widthsScaleWithTheBodyFont() {
        Font small = new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13), large = new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 26);
        assertEquals(Math.round(11 * 13f) + 16, ColumnKind.DATE_TIME.width(small));
        assertTrue(ColumnKind.DATE_TIME.width(large) > ColumnKind.DATE_TIME.width(small));
        assertEquals(SwingConstants.RIGHT, ColumnKind.NUMBER.alignment);
    }

    @Test public void renderersFormatByKindAndKeepModelValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[]{"when", "damage", "left", "note"}, 0);
            model.addRow(new Object[]{NOW - 12 * 60_000L, 41_234.0, DisplayValue.unknown("Not captured"), null});
            JTable table = new JTable(model);
            ContentStyle.table(table);
            Map<String, ColumnKind> kinds = new HashMap<>();
            kinds.put("when", ColumnKind.TIME_RELATIVE); kinds.put("damage", ColumnKind.NUMBER);
            kinds.put("left", ColumnKind.COUNT); kinds.put("note", ColumnKind.TEXT);
            KitTables.apply(table, kinds);
            JLabel when = render(table, 0, 0), damage = render(table, 0, 1), left = render(table, 0, 2), note = render(table, 0, 3);
            assertEquals("12 min ago", when.getText());
            assertTrue(when.getToolTipText().matches("\\d{4}-\\d{2}-\\d{2} .*"));
            assertEquals("41.2k", damage.getText());
            assertEquals("41,234", damage.getToolTipText());
            assertEquals(SwingConstants.RIGHT, damage.getHorizontalAlignment());
            assertEquals("—", left.getText());
            assertEquals("Not captured", left.getToolTipText());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), left.getForeground());
            assertEquals("—", note.getText());
            assertEquals(41_234.0, model.getValueAt(0, 1));
            assertEquals(ColumnKind.NUMBER.width(table.getFont()), table.getColumnModel().getColumn(1).getPreferredWidth());
        });
    }

    @Test public void iconTextSortsByTextAndRendersItsIcon() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon icon = new ImageIcon(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB));
            KitTables.IconText a = new KitTables.IconText(icon, "abyss"), b = new KitTables.IconText(icon, "Lost Halls");
            assertTrue(a.compareTo(b) < 0);
            DefaultTableModel model = new DefaultTableModel(new Object[]{"dungeon"}, 0);
            model.addRow(new Object[]{b});
            JTable table = new JTable(model);
            KitTables.apply(table, ColumnKind.DUNGEON);
            JLabel cell = render(table, 0, 0);
            assertEquals("Lost Halls", cell.getText());
            assertSame(icon, cell.getIcon());
        });
    }

    private static JLabel render(JTable table, int row, int column) {
        return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.KitTablesTest"`
Expected: FAIL — `cannot find symbol: class ColumnKind`.

- [ ] **Step 3: Implement `ColumnKind.java`**

```java
package tomato.gui.kit;

import java.awt.Font;
import javax.swing.SwingConstants;

/** The same kind of data gets the same width and alignment in every table. Widths are in em of the table font. */
public enum ColumnKind {
    TIME_RELATIVE(7f, SwingConstants.LEFT),
    DATE_TIME(11f, SwingConstants.LEFT),
    DURATION(6f, SwingConstants.RIGHT),
    DUNGEON(13f, SwingConstants.LEFT),
    PLAYER(11f, SwingConstants.LEFT),
    CLASS(8f, SwingConstants.LEFT),
    ITEM(15f, SwingConstants.LEFT),
    COUNT(5f, SwingConstants.RIGHT),
    NUMBER(6.5f, SwingConstants.RIGHT),
    PERCENT(5f, SwingConstants.RIGHT),
    STATUS(9f, SwingConstants.CENTER),
    ID(8f, SwingConstants.LEFT),
    TEXT(20f, SwingConstants.LEFT);

    /** ContentStyle.Cell pads 8 px on each side. */
    static final int PADDING = 16;

    public final float em;
    public final int alignment;

    ColumnKind(float em, int alignment) { this.em = em; this.alignment = alignment; }

    public int width(Font font) { return Math.round(em * font.getSize2D()) + PADDING; }
}
```

- [ ] **Step 4: Implement `KitTables.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.table.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Applies ColumnKind widths and renderers. Model values stay raw, so sorting and exports are unchanged. */
public final class KitTables {
    private KitTables() {}

    /** A sortable icon + text cell for dungeon, class and item columns. */
    public static final class IconText implements Comparable<IconText> {
        public final Icon icon;
        public final String text;
        public IconText(Icon icon, String text) { this.icon = icon; this.text = text == null ? "" : text; }
        @Override public int compareTo(IconText other) { return String.CASE_INSENSITIVE_ORDER.compare(text, other.text); }
        @Override public String toString() { return text; }
    }

    /** Applies kinds by view position. Call right after creating the table, before user reordering. */
    public static void apply(JTable table, ColumnKind... kinds) {
        TableColumnModel columns = table.getColumnModel();
        for (int i = 0; i < kinds.length && i < columns.getColumnCount(); i++) apply(table, columns.getColumn(i), kinds[i]);
    }

    /** Applies kinds by column identifier (HistoryTables columns carry stable IDs; plain models use header text). */
    public static void apply(JTable table, Map<String, ColumnKind> kinds) {
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) {
            ColumnKind kind = kinds.get(String.valueOf(column.getIdentifier()));
            if (kind != null) apply(table, column, kind);
        }
    }

    private static void apply(JTable table, TableColumn column, ColumnKind kind) {
        int width = kind.width(table.getFont());
        column.setPreferredWidth(width);
        column.setWidth(width);
        column.setCellRenderer(renderer(kind));
    }

    public static TableCellRenderer renderer(ColumnKind kind) { return new KindRenderer(kind); }

    /** A tinted status badge whose tone depends on the cell value. */
    public static TableCellRenderer status(Function<Object, Tokens.Tone> tone) {
        return new ContentStyle.Badge() {
            @Override protected Color badgeColor(Object value) { return Tokens.tone(tone.apply(value)); }
        };
    }

    private static final class KindRenderer extends ContentStyle.Cell {
        private final ColumnKind kind;

        KindRenderer(ColumnKind kind) { this.kind = kind; }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setHorizontalAlignment(kind.alignment);
            setToolTipText(null);
            if (kind == ColumnKind.ID) setFont(ContentStyle.report(table.getFont()));
            if (value instanceof DisplayValue) {
                DisplayValue shown = (DisplayValue) value;
                setText(shown.display());
                setToolTipText(shown.tooltip());
                if (!selected && shown.dimmed()) setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            } else if (value instanceof IconText) {
                setIcon(((IconText) value).icon);
                setIconTextGap(6);
                setText(((IconText) value).text);
            } else if (value == null) {
                setText(DisplayFormat.UNAVAILABLE);
                if (!selected) setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            } else {
                setText(format(value));
                setToolTipText(tooltip(value));
            }
            return this;
        }

        private String format(Object value) {
            Number number = value instanceof Number ? (Number) value : null;
            switch (kind) {
                case TIME_RELATIVE: return epoch(value) == null ? String.valueOf(value) : KitFormat.relative(epoch(value));
                case DATE_TIME: return epoch(value) == null ? String.valueOf(value) : DisplayFormat.formatTimestamp(Instant.ofEpochMilli(epoch(value)));
                case DURATION: return number == null ? String.valueOf(value) : KitFormat.duration(number.longValue());
                case COUNT: return number == null ? String.valueOf(value) : DisplayFormat.formatInteger(number.longValue());
                case NUMBER: return number == null ? String.valueOf(value) : KitFormat.compact(number.doubleValue());
                case PERCENT: return number == null ? String.valueOf(value) : DisplayFormat.formatPercentage(number.doubleValue(), 1);
                default: return String.valueOf(value);
            }
        }

        private String tooltip(Object value) {
            if (kind == ColumnKind.TIME_RELATIVE && epoch(value) != null) return DisplayFormat.formatTimestamp(Instant.ofEpochMilli(epoch(value)));
            if (kind == ColumnKind.NUMBER && value instanceof Number) return DisplayFormat.formatNumber(((Number) value).doubleValue(), 0, 2);
            return null;
        }

        private static Long epoch(Object value) {
            if (value instanceof Instant) return ((Instant) value).toEpochMilli();
            if (value instanceof Number) return ((Number) value).longValue();
            return null;
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.KitTablesTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/kit/ColumnKind.java src/main/java/tomato/gui/kit/KitTables.java src/test/java/tomato/gui/kit/KitTablesTest.java
git commit -m "Add column kinds and shared table renderers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Icon glyphs and game sprites

**Files:**
- Modify: `src/main/java/tomato/gui/modern/LineIcon.java` (add constants and cases)
- Create: `src/main/java/tomato/gui/kit/Sprites.java`
- Test: `src/test/java/tomato/gui/kit/SpritesTest.java`

**Interfaces:**
- Consumes: `assets.ImageBuffer.getImage(int)`, `ImageBuffer.getOutlinedIcon(int,int)`, `assets.IdToAsset.objectName(int)`, `Tokens`.
- Produces:
  - `LineIcon` constants: `INFO = 6, HOME = 15, SWORDS = 16, DIAMOND = 17, CHECKLIST = 18, GEAR = 19, FILTER = 20, DOTS = 21, CHEVRON_DOWN = 22, CHEVRON_RIGHT = 23, PIN = 24, STAR = 25, HOURGLASS = 26, REFRESH = 27, PENCIL = 28, CLOCK = 29, CLOSE = 30, SEARCH = 31, GRIP = 32`
  - `static Icon Sprites.sprite(int objectId, int size)`, `static String Sprites.name(int objectId)`, `static void Sprites.clear()`, `static boolean Sprites.isPlaceholder(Icon)`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.modern.LineIcon;
import static org.junit.Assert.*;

public class SpritesTest {
    @Test public void emptyAndUnknownObjectsGetAPlaceholderNotABlank() {
        Icon empty = Sprites.sprite(0, 24), missing = Sprites.sprite(987_654_321, 24);
        assertTrue(Sprites.isPlaceholder(empty));
        assertTrue(Sprites.isPlaceholder(missing));
        assertEquals(24, missing.getIconWidth());
        assertSame("Cached per id and size", missing, Sprites.sprite(987_654_321, 24));
        Sprites.clear();
        assertNotSame(missing, Sprites.sprite(987_654_321, 24));
    }

    @Test public void namesFallBackToTheId() {
        assertEquals("Empty", Sprites.name(-1));
        assertEquals("Unknown item #987654321", Sprites.name(987_654_321));
    }

    @Test public void newGlyphsPaintWithoutErrors() {
        BufferedImage image = new BufferedImage(22, 22, BufferedImage.TYPE_INT_ARGB);
        JLabel host = new JLabel();
        for (int type : new int[]{LineIcon.HOME, LineIcon.SWORDS, LineIcon.DIAMOND, LineIcon.CHECKLIST, LineIcon.GEAR,
                LineIcon.FILTER, LineIcon.DOTS, LineIcon.CHEVRON_DOWN, LineIcon.CHEVRON_RIGHT, LineIcon.PIN, LineIcon.STAR,
                LineIcon.HOURGLASS, LineIcon.REFRESH, LineIcon.PENCIL, LineIcon.CLOCK, LineIcon.CLOSE, LineIcon.SEARCH, LineIcon.GRIP}) {
            java.awt.Graphics2D g = image.createGraphics();
            new LineIcon(type, 22).paintIcon(host, g, 0, 0);
            g.dispose();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.SpritesTest"`
Expected: FAIL — `cannot find symbol: variable HOME` / `class Sprites`.

- [ ] **Step 3: Add glyphs to `LineIcon.java`**

Add these constants after `private final int size;`:
```java
    public static final int INFO = 6, HOME = 15, SWORDS = 16, DIAMOND = 17, CHECKLIST = 18, GEAR = 19, FILTER = 20,
        DOTS = 21, CHEVRON_DOWN = 22, CHEVRON_RIGHT = 23, PIN = 24, STAR = 25, HOURGLASS = 26, REFRESH = 27,
        PENCIL = 28, CLOCK = 29, CLOSE = 30, SEARCH = 31, GRIP = 32;
```
Add these cases inside the `switch (type)`, immediately before `case 8:`:
```java
            case HOME: path(g, 3, 11, 11, 4, 19, 11); path(g, 5, 10, 5, 19, 17, 19, 17, 10); path(g, 9, 19, 9, 14, 13, 14, 13, 19); break;
            case SWORDS: g.drawLine(4, 4, 18, 18); g.drawLine(18, 4, 4, 18); g.drawLine(3, 14, 8, 19); g.drawLine(14, 19, 19, 14); break;
            case DIAMOND: path(g, 7, 4, 15, 4, 19, 9, 11, 19, 3, 9, 7, 4); g.drawLine(3, 9, 19, 9); break;
            case CHECKLIST: for (int row : new int[]{6, 11, 16}) { path(g, 3, row, 5, row + 2, 8, row - 2); g.drawLine(11, row, 19, row); } break;
            case GEAR:
                g.drawOval(8, 8, 6, 6);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    g.drawLine((int) Math.round(11 + 5 * Math.cos(a)), (int) Math.round(11 + 5 * Math.sin(a)),
                        (int) Math.round(11 + 8 * Math.cos(a)), (int) Math.round(11 + 8 * Math.sin(a)));
                }
                break;
            case FILTER: path(g, 3, 4, 19, 4, 13, 11, 13, 18, 9, 16, 9, 11, 3, 4); break;
            case DOTS: g.fillOval(4, 10, 3, 3); g.fillOval(10, 10, 3, 3); g.fillOval(16, 10, 3, 3); break;
            case CHEVRON_DOWN: path(g, 6, 9, 11, 14, 16, 9); break;
            case CHEVRON_RIGHT: path(g, 9, 6, 14, 11, 9, 16); break;
            case PIN: g.drawOval(7, 3, 8, 8); g.drawLine(11, 11, 11, 19); break;
            case STAR: {
                Path2D star = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + Math.PI * i / 5, r = i % 2 == 0 ? 9 : 4;
                    if (i == 0) star.moveTo(11 + r * Math.cos(a), 11.5 + r * Math.sin(a));
                    else star.lineTo(11 + r * Math.cos(a), 11.5 + r * Math.sin(a));
                }
                star.closePath(); g.draw(star);
                break;
            }
            case HOURGLASS: path(g, 6, 3, 16, 3, 11, 11, 16, 19, 6, 19, 11, 11, 6, 3); break;
            case REFRESH: g.drawArc(4, 4, 14, 14, 30, 290); path(g, 15, 2, 17, 6, 13, 7); break;
            case PENCIL: path(g, 4, 18, 5, 14, 15, 4, 18, 7, 8, 17, 4, 18); g.drawLine(13, 6, 16, 9); break;
            case CLOCK: g.drawOval(3, 3, 16, 16); path(g, 11, 6, 11, 11, 15, 13); break;
            case CLOSE: g.drawLine(6, 6, 16, 16); g.drawLine(16, 6, 6, 16); break;
            case SEARCH: g.drawOval(4, 4, 11, 11); g.drawLine(13, 13, 18, 18); break;
            case GRIP: for (int gx : new int[]{8, 13}) for (int gy : new int[]{5, 10, 15}) g.fillOval(gx, gy, 2, 2); break;
```

- [ ] **Step 4: Implement `Sprites.java`**

```java
package tomato.gui.kit;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/** Game art by object ID with a visible placeholder when assets are missing. Clear after an asset reload. */
public final class Sprites {
    private static final Map<Long, Icon> cache = new HashMap<>();

    private Sprites() {}

    public static synchronized Icon sprite(int objectId, int size) {
        long key = ((long) objectId << 12) ^ size;
        Icon cached = cache.get(key);
        if (cached == null) { cached = resolve(objectId, size); cache.put(key, cached); }
        return cached;
    }

    public static synchronized void clear() { cache.clear(); }

    public static boolean isPlaceholder(Icon icon) { return icon instanceof Placeholder; }

    /** Display name, "Empty" for empty slots, or "Unknown item #id" without assets. */
    public static String name(int objectId) {
        if (objectId <= 0) return "Empty";
        String name;
        try { name = IdToAsset.objectName(objectId); } catch (RuntimeException e) { name = null; }
        return name == null || name.isEmpty() ? "Unknown item #" + objectId : name;
    }

    private static Icon resolve(int objectId, int size) {
        if (objectId <= 0) return new Placeholder(size);
        try {
            // getImage returns null for id <= 0 and the shared transparent image for unknown IDs or missing
            // sprite data (ImageBuffer.getImage/getEmptyImg); either would otherwise draw a blank slot.
            BufferedImage image = ImageBuffer.getImage(objectId);
            if (image == null || image == ImageBuffer.getEmptyImg()) return new Placeholder(size);
            ImageIcon icon = ImageBuffer.getOutlinedIcon(objectId, size);
            return icon == null || icon.getIconWidth() <= 0 ? new Placeholder(size) : icon;
        } catch (IOException | RuntimeException e) {
            return new Placeholder(size);
        }
    }

    /** A dashed rounded square in the muted color. */
    private static final class Placeholder implements Icon {
        private final int size;
        Placeholder(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 2f}, 0f));
            float inset = Math.max(1f, size / 8f), side = size - 2 * inset - 1;
            g.draw(new RoundRectangle2D.Float(x + inset, y + inset, side, side, size / 4f, size / 4f));
            g.dispose();
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.SpritesTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/modern/LineIcon.java src/main/java/tomato/gui/kit/Sprites.java src/test/java/tomato/gui/kit/SpritesTest.java
git commit -m "Add navigation and action glyphs and a sprite facade

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Simple/Analyst mode and motion

**Files:**
- Create: `src/main/java/tomato/gui/kit/DisplayModeModel.java`, `Motion.java`
- Test: `src/test/java/tomato/gui/kit/DisplayModeModelTest.java`, `MotionTest.java`

**Interfaces:**
- Consumes: `util.PropertiesManager`, JNA core (`com.sun.jna.Native`, `Platform`, `ptr.IntByReference`, `win32.StdCallLibrary`).
- Produces:
  - `DisplayModeModel.Mode { SIMPLE, ANALYST }`, `DisplayModeModel.KEY = "ui.mode"`
  - `DisplayModeModel(Function<String,String> read, BiConsumer<String,String> write)`; `static DisplayModeModel application()`
  - `Mode mode()`, `boolean analyst()`, `void set(Mode)`, `void toggle()`, `void bind(JComponent owner, Consumer<Mode> listener)`, `int listenerCount()`
  - `Motion.MAX_MILLIS = 100`, `Motion.REDUCE_KEY = "ui.reduceMotion"`, `static boolean Motion.enabled()`, `static javax.swing.Timer Motion.run(int millis, DoubleConsumer frame, Runnable done)`; package-private `static Boolean Motion.systemOverride`

- [ ] **Step 1: Write the failing tests**

`DisplayModeModelTest.java`:
```java
package tomato.gui.kit;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayModeModelTest {
    @Test public void defaultsToSimpleAndPersistsChanges() {
        Map<String, String> store = new HashMap<>();
        DisplayModeModel model = new DisplayModeModel(store::get, store::put);
        assertEquals(DisplayModeModel.Mode.SIMPLE, model.mode());
        model.set(DisplayModeModel.Mode.ANALYST);
        assertEquals("analyst", store.get(DisplayModeModel.KEY));
        assertTrue(new DisplayModeModel(store::get, store::put).analyst());
        model.toggle();
        assertEquals("simple", store.get(DisplayModeModel.KEY));
    }

    @Test public void boundListenersFollowTheOwnersLifecycle() throws Exception {
        Map<String, String> store = new HashMap<>();
        DisplayModeModel model = new DisplayModeModel(store::get, store::put);
        List<DisplayModeModel.Mode> seen = new ArrayList<>();
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JPanel owner = new JPanel();
            model.bind(owner, seen::add);
            assertEquals(Collections.singletonList(DisplayModeModel.Mode.SIMPLE), seen);
            frame[0] = new JFrame(); frame[0].setContentPane(owner); frame[0].pack();
            model.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(DisplayModeModel.Mode.ANALYST, seen.get(seen.size() - 1));
            frame[0].dispose();
            int before = seen.size();
            assertEquals(0, model.listenerCount());
            model.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(before, seen.size());
        });
    }
}
```

`MotionTest.java`:
```java
package tomato.gui.kit;

import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.*;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class MotionTest {
    private String saved;
    @Before public void remember() { saved = PropertiesManager.getProperty(Motion.REDUCE_KEY); }
    @After public void restore() {
        Motion.systemOverride = null;
        PropertiesManager.setProperties(Motion.REDUCE_KEY, saved == null ? "" : saved);
    }

    @Test public void reduceMotionCompletesImmediately() throws Exception {
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "true");
        Motion.systemOverride = true;
        List<Double> frames = new ArrayList<>(); boolean[] done = {false};
        SwingUtilities.invokeAndWait(() -> assertNull(Motion.run(100, frames::add, () -> done[0] = true)));
        assertEquals(Collections.singletonList(1.0), frames);
        assertTrue(done[0]);
    }

    @Test public void windowsAnimationSettingAlsoDisablesMotion() {
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "");
        Motion.systemOverride = false;
        assertFalse(Motion.enabled());
        Motion.systemOverride = true;
        assertTrue(Motion.enabled());
    }

    @Test public void enabledMotionFinishesWithinTheCap() throws Exception {
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "");
        Motion.systemOverride = true;
        CountDownLatch finished = new CountDownLatch(1);
        List<Double> frames = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> assertNotNull(Motion.run(5_000, frames::add, finished::countDown)));
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue("Capped at 100 ms plus timer slack: " + elapsedMillis, elapsedMillis < 600);
        assertEquals(1.0, frames.get(frames.size() - 1), 1e-9);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.DisplayModeModelTest" --tests "tomato.gui.kit.MotionTest"`
Expected: FAIL — `cannot find symbol: class DisplayModeModel` / `class Motion`.

- [ ] **Step 3: Implement `DisplayModeModel.java`**

```java
package tomato.gui.kit;

import java.awt.event.HierarchyEvent;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JComponent;
import util.PropertiesManager;

/** Simple hides provenance, IDs and diagnostic tabs; Analyst shows them. Display only; never changes queries. */
public final class DisplayModeModel {
    public enum Mode { SIMPLE, ANALYST }
    public static final String KEY = "ui.mode";

    private static DisplayModeModel application;

    private final BiConsumer<String, String> write;
    /** Weak: the owning component holds each listener strongly, so discarded, never-shown views are not leaked. */
    private final List<WeakReference<Consumer<Mode>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Mode mode;

    public DisplayModeModel(Function<String, String> read, BiConsumer<String, String> write) {
        this.write = write;
        mode = "analyst".equals(read.apply(KEY)) ? Mode.ANALYST : Mode.SIMPLE;
    }

    public static synchronized DisplayModeModel application() {
        if (application == null) application = new DisplayModeModel(PropertiesManager::getProperty, PropertiesManager::setProperties);
        return application;
    }

    public Mode mode() { return mode; }
    public boolean analyst() { return mode == Mode.ANALYST; }

    /** Call on the EDT; listeners run synchronously. */
    public void set(Mode value) {
        if (value == null || value == mode) return;
        mode = value;
        write.accept(KEY, value == Mode.ANALYST ? "analyst" : "simple");
        for (WeakReference<Consumer<Mode>> reference : listeners) {
            Consumer<Mode> listener = reference.get();
            if (listener == null) listeners.remove(reference); else listener.accept(value);
        }
    }

    public void toggle() { set(analyst() ? Mode.SIMPLE : Mode.ANALYST); }

    /**
     * Applies the current mode now and on every change while the owner is alive and displayable. The owner
     * holds the listener; the model holds it weakly and drops it when the owner leaves a displayable hierarchy.
     */
    public void bind(JComponent owner, Consumer<Mode> listener) {
        owner.putClientProperty(listener, listener);
        listener.accept(mode);
        register(listener);
        owner.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) return;
            if (owner.isDisplayable()) {
                register(listener);
                listener.accept(mode);
            } else {
                listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
            }
        });
    }

    private void register(Consumer<Mode> listener) {
        for (WeakReference<Consumer<Mode>> reference : listeners) if (reference.get() == listener) return;
        listeners.add(new WeakReference<>(listener));
    }

    public int listenerCount() {
        listeners.removeIf(reference -> reference.get() == null);
        return listeners.size();
    }
}
```

- [ ] **Step 4: Implement `Motion.java`**

```java
package tomato.gui.kit;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.function.DoubleConsumer;
import javax.swing.Timer;
import util.PropertiesManager;

/** Short orientation motion only. Off with Settings › Reduce motion or Windows "Animate controls" off. */
public final class Motion {
    public static final int MAX_MILLIS = 100;
    public static final String REDUCE_KEY = "ui.reduceMotion";
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;
    /** Replaced by tests only; null reads the operating system. */
    static volatile Boolean systemOverride;

    private Motion() {}

    public static boolean enabled() {
        return !"true".equals(PropertiesManager.getProperty(REDUCE_KEY)) && systemAnimations();
    }

    /**
     * Calls frame with eased progress in (0, 1], then done, on the EDT. Durations are capped at
     * MAX_MILLIS. When motion is off, frame(1) and done run immediately and null is returned.
     */
    public static Timer run(int millis, DoubleConsumer frame, Runnable done) {
        int duration = Math.min(MAX_MILLIS, Math.max(0, millis));
        if (duration == 0 || !enabled()) {
            frame.accept(1);
            if (done != null) done.run();
            return null;
        }
        long start = System.nanoTime();
        Timer timer = new Timer(15, null);
        timer.addActionListener(event -> {
            double t = Math.min(1, (System.nanoTime() - start) / 1_000_000.0 / duration);
            frame.accept(1 - Math.pow(1 - t, 3));
            if (t >= 1) {
                timer.stop();
                if (done != null) done.run();
            }
        });
        timer.start();
        return timer;
    }

    private static volatile long systemCheckedAt;
    private static volatile boolean systemAllows = true;

    /** Reads the Windows setting at most every five seconds; animations are rare, but this avoids a JNA call per frame burst. */
    private static boolean systemAnimations() {
        Boolean override = systemOverride;
        if (override != null) return override;
        if (!Platform.isWindows()) return true;
        long now = System.nanoTime();
        if (systemCheckedAt != 0 && now - systemCheckedAt < 5_000_000_000L) return systemAllows;
        boolean allows;
        try {
            IntByReference value = new IntByReference(1);
            allows = !User32.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, value, 0) || value.getValue() != 0;
        } catch (Throwable unavailable) {
            allows = true;
        }
        systemAllows = allows;
        systemCheckedAt = now;
        return allows;
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        boolean SystemParametersInfoW(int action, int param, IntByReference value, int winIni);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.DisplayModeModelTest" --tests "tomato.gui.kit.MotionTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/kit/DisplayModeModel.java src/main/java/tomato/gui/kit/Motion.java src/test/java/tomato/gui/kit/DisplayModeModelTest.java src/test/java/tomato/gui/kit/MotionTest.java
git commit -m "Add Simple/Analyst display mode and capped motion

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Buttons, chips, segmented control and overflow menu

**Files:**
- Create: `src/main/java/tomato/gui/kit/KitButton.java`, `Chip.java`, `SegmentedControl.java`, `OverflowMenu.java`
- Test: `src/test/java/tomato/gui/kit/ControlsTest.java`

**Interfaces:**
- Consumes: `Tokens`, `Type`, `LineIcon`, `VioletTheme.CAPTURE_HOVER/CAPTURE_PRESSED`, `ContentStyle.font`.
- Produces:
  - `KitButton.Variant { PRIMARY, SECONDARY, GHOST, DANGER, ICON }`; `public KitButton(Variant, String text, Icon icon)`; factories `primary(String)`, `secondary(String)`, `ghost(String)`, `danger(String)`, `icon(Icon, String label)`; `Variant variant()`
  - `Chip(String text, Tokens.Tone)`; `Tokens.Tone tone()`; `void setTone(Tokens.Tone)`; `static JComponent Chip.removable(String text, Runnable remove)` (its remove button is named `remove-filter`, accessible name `Remove filter: <text>`)
  - `SegmentedControl(String name, String... options)`; `int selected()`; `void setSelected(int)` (no event); `void onChange(IntConsumer)`; buttons named `<name>-<index>`
  - `OverflowMenu(String name)` extends `KitButton`; `JMenuItem add(String label, Runnable action)`; `void addSeparator()`; `JMenu submenu(String label)`; `JPopupMenu menu()`; `JMenuItem item(String label)` (finds an item by exact text, searching submenus); hidden until it has an item

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.LineIcon;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

public class ControlsTest {
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void variantsSurviveThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            KitButton primary = KitButton.primary("Start capture"), ghost = KitButton.ghost("Clear");
            assertEquals(KitButton.Variant.PRIMARY, primary.variant());
            assertNotNull(primary.getClientProperty("FlatLaf.style"));
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(primary); SwingUtilities.updateComponentTreeUI(ghost);
            assertNotNull("Style reapplied after updateUI", primary.getClientProperty("FlatLaf.style"));
            assertEquals("borderless", ghost.getClientProperty("JButton.buttonType"));
        });
    }

    @Test public void iconButtonsAreNamedForAssistiveTechnology() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KitButton icon = KitButton.icon(new LineIcon(LineIcon.GEAR, 16), "Settings");
            assertEquals("Settings", icon.getToolTipText());
            assertEquals("Settings", icon.getAccessibleContext().getAccessibleName());
            assertTrue(icon.getText() == null || icon.getText().isEmpty());
        });
    }

    @Test public void removableChipRunsItsRemoveAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] removed = {0};
            JComponent chip = Chip.removable("Completed", () -> removed[0]++);
            AbstractButton remove = find(chip, "remove-filter");
            assertEquals("Remove filter: Completed", remove.getAccessibleContext().getAccessibleName());
            remove.doClick();
            assertEquals(1, removed[0]);
            Chip status = new Chip("Left", Tokens.Tone.NEUTRAL);
            assertEquals(Tokens.tone(Tokens.Tone.NEUTRAL), status.getForeground());
            status.setTone(Tokens.Tone.GOOD);
            assertEquals(Tokens.tone(Tokens.Tone.GOOD), status.getForeground());
        });
    }

    @Test public void segmentedControlReportsUserChangesOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SegmentedControl mode = new SegmentedControl("display-mode", "Simple", "Analyst");
            java.util.List<Integer> changes = new ArrayList<>();
            mode.onChange(changes::add);
            mode.setSelected(1);
            assertTrue("Programmatic selection is silent", changes.isEmpty());
            assertEquals(1, mode.selected());
            ((AbstractButton) find(mode, "display-mode-0")).doClick();
            assertEquals(Collections.singletonList(0), changes);
        });
    }

    @Test public void overflowMenuAppearsOnceItHasItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OverflowMenu more = new OverflowMenu("runs-more");
            assertFalse(more.isVisible());
            int[] ran = {0};
            more.add("Export page…", () -> ran[0]++);
            JMenu views = more.submenu("Saved views");
            JMenuItem nested = new JMenuItem("Reset saved state"); views.add(nested);
            assertTrue(more.isVisible());
            assertEquals("More actions", more.getAccessibleContext().getAccessibleName());
            more.item("Export page…").doClick();
            assertEquals(1, ran[0]);
            assertSame(nested, more.item("Reset saved state"));
            assertNull(more.item("Missing"));
        });
    }

    static AbstractButton find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && name.equals(child.getName())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.ControlsTest"`
Expected: FAIL — `cannot find symbol: class KitButton`.

- [ ] **Step 3: Implement `KitButton.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import tomato.gui.modern.VioletTheme;

/** Button hierarchy: one PRIMARY per view, SECONDARY by default, GHOST for tertiary, DANGER for destructive, ICON for compact tools. */
public class KitButton extends JButton {
    public enum Variant { PRIMARY, SECONDARY, GHOST, DANGER, ICON }

    private final Variant variant;

    public KitButton(Variant variant, String text, Icon icon) {
        super(text, icon);
        this.variant = variant;
        applyVariant();
    }

    public static KitButton primary(String text) { return new KitButton(Variant.PRIMARY, text, null); }
    public static KitButton secondary(String text) { return new KitButton(Variant.SECONDARY, text, null); }
    public static KitButton ghost(String text) { return new KitButton(Variant.GHOST, text, null); }
    public static KitButton danger(String text) { return new KitButton(Variant.DANGER, text, null); }

    /** Icon-only; the label becomes the tooltip and accessible name. */
    public static KitButton icon(Icon icon, String label) {
        KitButton button = new KitButton(Variant.ICON, null, icon);
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
        return button;
    }

    public Variant variant() { return variant; }

    @Override public void updateUI() {
        super.updateUI();
        if (variant != null) applyVariant(); // null while JButton's constructor runs
    }

    private void applyVariant() {
        Map<String, Object> style = new HashMap<>();
        putClientProperty("JButton.buttonType", null);
        switch (variant) {
            case PRIMARY:
                Color primary = Tokens.color(Tokens.Role.PRIMARY);
                style.put("background", primary);
                style.put("foreground", Color.WHITE);
                style.put("borderColor", primary);
                style.put("hoverBackground", VioletTheme.CAPTURE_HOVER);
                style.put("focusedBackground", VioletTheme.CAPTURE_HOVER);
                style.put("pressedBackground", VioletTheme.CAPTURE_PRESSED);
                style.put("hoverForeground", Color.WHITE);
                style.put("pressedForeground", Color.WHITE);
                break;
            case GHOST:
                putClientProperty("JButton.buttonType", "borderless");
                style.put("foreground", Tokens.color(Tokens.Role.ACCENT_TEXT));
                break;
            case DANGER:
                style.put("foreground", Tokens.color(Tokens.Role.BAD));
                style.put("borderColor", Tokens.color(Tokens.Role.BAD));
                break;
            case ICON:
                putClientProperty("JButton.buttonType", "toolBarButton");
                setMargin(new Insets(4, 4, 4, 4));
                break;
            default:
                break;
        }
        putClientProperty("FlatLaf.style", style.isEmpty() ? null : style);
    }
}
```

- [ ] **Step 4: Implement `Chip.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;

/** A tinted status pill. Use removable(...) for active filters. */
public class Chip extends JLabel {
    private Tokens.Tone tone;

    public Chip(String text, Tokens.Tone tone) {
        super(text);
        this.tone = tone;
        putClientProperty("html.disable", true);
        setBorder(new EmptyBorder(1, 7, 1, 7));
        ContentStyle.font(this, Type.caption());
        refreshColors();
    }

    public Tokens.Tone tone() { return tone; }
    public void setTone(Tokens.Tone value) { tone = value; refreshColors(); repaint(); }

    @Override public void updateUI() { super.updateUI(); if (tone != null) refreshColors(); }

    private void refreshColors() { setForeground(Tokens.tone(tone)); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Tokens.tint(Tokens.tone(tone)));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.dispose();
        super.paintComponent(graphics);
    }

    /** An accent chip with a keyboard-reachable remove button. */
    public static JComponent removable(String text, Runnable remove) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Tokens.tint(Tokens.tone(Tokens.Tone.ACCENT)));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CHIP, Tokens.ARC_CHIP);
                g.dispose();
            }
        };
        chip.setOpaque(false);
        chip.setBorder(new EmptyBorder(0, 6, 0, 1));
        JLabel label = new JLabel(text) {
            @Override public void updateUI() { super.updateUI(); setForeground(Tokens.tone(Tokens.Tone.ACCENT)); }
        };
        label.putClientProperty("html.disable", true);
        ContentStyle.font(label, Type.caption());
        KitButton close = KitButton.icon(new LineIcon(LineIcon.CLOSE, 12), "Remove filter: " + text);
        close.setName("remove-filter");
        close.addActionListener(e -> remove.run());
        chip.add(label);
        chip.add(close);
        chip.getAccessibleContext().setAccessibleName("Filter: " + text);
        return chip;
    }
}
```

- [ ] **Step 5: Implement `SegmentedControl.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.*;

/** A small exclusive choice (Simple/Analyst, Feed/Table). setSelected is silent; user clicks notify. */
public class SegmentedControl extends JPanel {
    private final List<JToggleButton> buttons = new ArrayList<>();
    private final List<IntConsumer> listeners = new ArrayList<>();

    public SegmentedControl(String name, String... options) {
        super(new GridLayout(1, options.length, 0, 0));
        if (options.length < 2) throw new IllegalArgumentException("A segmented control needs at least two options");
        setName(name);
        setOpaque(false);
        getAccessibleContext().setAccessibleName(name.replace('-', ' '));
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            JToggleButton button = new JToggleButton(options[i]);
            button.setName(name + "-" + i);
            button.putClientProperty("FlatLaf.style", "arc: 0");
            button.addActionListener(e -> { for (IntConsumer listener : listeners) listener.accept(index); });
            group.add(button);
            buttons.add(button);
            add(button);
        }
        buttons.get(0).setSelected(true);
    }

    public int selected() {
        for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).isSelected()) return i;
        return -1;
    }

    public void setSelected(int index) { buttons.get(index).setSelected(true); }

    public void onChange(IntConsumer listener) { listeners.add(listener); }
}
```

- [ ] **Step 6: Implement `OverflowMenu.java`**

```java
package tomato.gui.kit;

import java.awt.Component;
import javax.swing.*;
import tomato.gui.modern.LineIcon;

/** A "More actions" button that holds secondary actions instead of a wall of buttons. Hidden while empty. */
public class OverflowMenu extends KitButton {
    private final JPopupMenu menu = new JPopupMenu();

    public OverflowMenu(String name) {
        super(Variant.ICON, null, new LineIcon(LineIcon.DOTS, 16));
        setName(name);
        setToolTipText("More actions");
        getAccessibleContext().setAccessibleName("More actions");
        menu.setName(name + "-menu");
        addActionListener(e -> menu.show(this, 0, getHeight()));
        setVisible(false);
    }

    public JMenuItem add(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
        setVisible(true);
        return item;
    }

    public JMenu submenu(String label) {
        JMenu submenu = new JMenu(label);
        menu.add(submenu);
        setVisible(true);
        return submenu;
    }

    public void addSeparator() { menu.addSeparator(); }

    public JPopupMenu menu() { return menu; }

    /** The popup is not in the component tree until shown, so theme changes must reach it here. */
    @Override public void updateUI() {
        super.updateUI();
        if (menu != null) SwingUtilities.updateComponentTreeUI(menu); // null during KitButton's constructor
    }

    /** Finds a menu item by its exact text, including inside submenus; null when absent. */
    public JMenuItem item(String label) { return find(menu.getComponents(), label); }

    private static JMenuItem find(Component[] components, String label) {
        for (Component component : components) {
            if (component instanceof JMenu) {
                JMenu submenu = (JMenu) component;
                if (label.equals(submenu.getText())) return submenu;
                JMenuItem nested = find(submenu.getMenuComponents(), label);
                if (nested != null) return nested;
            } else if (component instanceof JMenuItem && label.equals(((JMenuItem) component).getText())) {
                return (JMenuItem) component;
            }
        }
        return null;
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.ControlsTest"`
Expected: PASS (5 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/tomato/gui/kit/KitButton.java src/main/java/tomato/gui/kit/Chip.java src/main/java/tomato/gui/kit/SegmentedControl.java src/main/java/tomato/gui/kit/OverflowMenu.java src/test/java/tomato/gui/kit/ControlsTest.java
git commit -m "Add kit buttons, chips, segmented control and overflow menu

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Cards, tiles, empty states and collapsibles

**Files:**
- Create: `src/main/java/tomato/gui/kit/SectionHeader.java`, `EvidenceNote.java`, `Card.java`, `StatTile.java`, `EmptyState.java`, `Collapsible.java`
- Test: `src/test/java/tomato/gui/kit/ContainersTest.java`

**Interfaces:**
- Consumes: Tasks 1, 4, 5; `ContentStyle.wrappingText`, `ContentStyle.font`, `LineIcon`; `Sparkline` from Task 9 is **not** used here (StatTile trend arrives in Task 9).
- Produces:
  - `SectionHeader(String title)`; `void setTitle(String)`, `String title()`, `void setCount(String)`, `JPanel actions()`
  - `EvidenceNote(String text)`; `void setText(String)`, `String getText()` (hidden by default)
  - `Card()`; `Card title(String)`, `SectionHeader header()`, `Card body(JComponent)`, `Card footer(JComponent)`, `Card onOpen(String accessibleName, Runnable)`, `Card evidence(String)`, `boolean evidenceShown()`; `Card(DisplayModeModel)` for tests
  - `StatTile(String label)`; `void setValue(DisplayValue value, String subline)`; `DisplayValue value()`; `JPanel trendSlot()` (empty panel Task 9 fills)
  - `EmptyState(String title, String body, KitButton action /* nullable */)`
  - `Collapsible(String id, String title, JComponent content, boolean expandedByDefault)` and a package-private constructor with `Function<String,String> read, BiConsumer<String,String> write`; `boolean expanded()`, `void setExpanded(boolean)`, `KitButton toggle()`; key `Collapsible.PREFIX = "ui.collapse."`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.awt.event.KeyEvent;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ContainersTest {
    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void cardOpensFromMouseAndKeyboard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] opened = {0};
            Card card = new Card(new DisplayModeModel(k -> null, (k, v) -> {})).title("Recent runs").body(new JLabel("Shatters"));
            card.onOpen("Open recent runs", () -> opened[0]++);
            assertTrue(card.isFocusable());
            assertEquals("Open recent runs", card.getAccessibleContext().getAccessibleName());
            card.getActionMap().get("open-card").actionPerformed(null);
            assertEquals(1, opened[0]);
            JLabel tipped = new JLabel("3.9k DPS");
            tipped.setToolTipText("Linked recording");
            card.footer(tipped);
            tipped.dispatchEvent(new java.awt.event.MouseEvent(tipped, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertEquals("A click on a child with a tooltip still opens the card", 2, opened[0]);
            assertNotNull(card.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)));
            assertEquals("Recent runs", card.header().title());
        });
    }

    @Test public void evidenceFollowsModeAndCanBeToggled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
            Card card = new Card(mode).title("Pet").evidence("Loaded from char/list at 12:04; family not supplied.");
            assertFalse(card.evidenceShown());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(card.evidenceShown());
            AbstractButton toggle = ControlsTest.find(card, "card-evidence");
            toggle.doClick();
            assertFalse(card.evidenceShown());
            assertEquals("Show evidence", toggle.getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void statTileShowsHonestStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatTile tile = new StatTile("Fame today");
            assertEquals("tile-fame-today", tile.getName());
            tile.setValue(DisplayValue.unknown("No fame samples yet today"), null);
            assertEquals("Fame today: —", tile.getAccessibleContext().getAccessibleName());
            tile.setValue(DisplayValue.known("+1,480", "Fame samples"), "1,050 / hour");
            assertEquals("Fame today: +1,480, 1,050 / hour", tile.getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void collapsibleRemembersItsState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            JLabel content = new JLabel("Timeline events");
            Collapsible section = new Collapsible("recap-timeline", "Timeline", content, false, store::get, store::put);
            assertFalse(section.expanded());
            assertFalse(content.isVisible());
            section.toggle().doClick();
            assertTrue(section.expanded());
            assertTrue(content.isVisible());
            assertEquals("true", store.get(Collapsible.PREFIX + "recap-timeline"));
            assertTrue(new Collapsible("recap-timeline", "Timeline", new JLabel(), false, store::get, store::put).expanded());
        });
    }

    @Test public void emptyStateOffersTheNextAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KitButton start = KitButton.primary("Start capture");
            EmptyState empty = new EmptyState("See your character here", "Start capture and enter the game.", start);
            assertEquals("empty-state", empty.getName());
            assertTrue(SwingUtilities.isDescendingFrom(start, empty));
            assertEquals("See your character here", empty.getAccessibleContext().getAccessibleName());
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.ContainersTest"`
Expected: FAIL — `cannot find symbol: class Card`.

- [ ] **Step 3: Implement `SectionHeader.java` and `EvidenceNote.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Title, optional count and right-aligned actions. */
public class SectionHeader extends JPanel {
    private final JLabel title = new JLabel(), count = new JLabel();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.XS, 0));

    public SectionHeader(String text) {
        super(new BorderLayout(Tokens.S, 0));
        setOpaque(false);
        actions.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.S, 0));
        left.setOpaque(false);
        title.putClientProperty("html.disable", true);
        count.putClientProperty("html.disable", true);
        ContentStyle.font(title, Type.title());
        ContentStyle.font(count, Type.caption());
        left.add(title);
        left.add(count);
        add(left, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
        setTitle(text);
        setCount(null);
        refreshColors();
    }

    public void setTitle(String text) { title.setText(text); title.setVisible(text != null && !text.isEmpty()); }
    public String title() { return title.getText(); }
    public void setCount(String text) { count.setText(text); count.setVisible(text != null && !text.isEmpty()); }
    public JPanel actions() { return actions; }

    @Override public void updateUI() { super.updateUI(); if (count != null) refreshColors(); }

    private void refreshColors() {
        title.setForeground(Tokens.color(Tokens.Role.TEXT));
        count.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }
}
```

```java
package tomato.gui.kit;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Full provenance text, muted and wrapping. Hidden until a card or mode shows it. */
public class EvidenceNote extends JPanel {
    private final JTextArea text;

    public EvidenceNote(String value) {
        super(new BorderLayout());
        setOpaque(false);
        text = ContentStyle.wrappingText(value);
        text.setName("evidence-note");
        text.getAccessibleContext().setAccessibleName("Evidence");
        add(text);
        setVisible(false);
        refreshColors();
    }

    public void setText(String value) { text.setText(value); }
    public String getText() { return text.getText(); }

    @Override public void updateUI() { super.updateUI(); if (text != null) refreshColors(); }

    private void refreshColors() { text.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)); }
}
```

- [ ] **Step 4: Implement `Card.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.LineIcon;

/** A rounded surface with optional header, footer, drill-down action and Evidence disclosure. */
public class Card extends JPanel {
    private final DisplayModeModel mode;
    private final JPanel top = new JPanel(new BorderLayout(Tokens.S, 0));
    private final JPanel center = new JPanel(new BorderLayout(0, Tokens.S));
    private SectionHeader header;
    private EvidenceNote evidence;
    private KitButton evidenceToggle;
    private Runnable open;
    private boolean hovered;
    /** Children with tooltips receive their own mouse events, so clicks are listened for on every non-button descendant. */
    private final MouseAdapter clicks = new MouseAdapter() {
        @Override public void mouseClicked(MouseEvent e) { if (open != null && SwingUtilities.isLeftMouseButton(e)) open.run(); }
        @Override public void mouseEntered(MouseEvent e) { setHovered(true); }
        @Override public void mouseExited(MouseEvent e) {
            setHovered(contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Card.this)));
        }
    };
    private final ContainerListener adoption = new ContainerAdapter() {
        @Override public void componentAdded(ContainerEvent e) { if (open != null) listen(e.getChild()); }
    };

    public Card() { this(DisplayModeModel.application()); }

    public Card(DisplayModeModel mode) {
        super(new BorderLayout(0, Tokens.S));
        this.mode = mode;
        setOpaque(false);
        setBorder(new EmptyBorder(Tokens.M, Tokens.M, Tokens.M, Tokens.M));
        top.setOpaque(false);
        center.setOpaque(false);
        top.setVisible(false);
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
    }

    public Card title(String title) {
        if (header == null) {
            header = new SectionHeader(title);
            top.add(header, BorderLayout.CENTER);
            top.setVisible(true);
        } else {
            header.setTitle(title);
        }
        return this;
    }

    public SectionHeader header() {
        if (header == null) title("");
        return header;
    }

    public Card body(JComponent body) { center.add(body, BorderLayout.CENTER); return this; }
    public Card footer(JComponent footer) { add(footer, BorderLayout.SOUTH); return this; }

    /**
     * Makes the whole card a drill-down target: click (including on labels, tiles and slots inside it),
     * Enter or Space. Buttons inside the card keep their own action.
     */
    public Card onOpen(String accessibleName, Runnable action) {
        open = Objects.requireNonNull(action, "action");
        setFocusable(true);
        getAccessibleContext().setAccessibleName(accessibleName);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        listen(this);
        InputMap keys = getInputMap(WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-card");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "open-card");
        getActionMap().put("open-card", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { open.run(); }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        return this;
    }

    /** Full provenance behind an info toggle; expanded by default in Analyst mode. */
    public Card evidence(String text) {
        if (evidence == null) {
            evidence = new EvidenceNote(text);
            evidenceToggle = KitButton.icon(new LineIcon(LineIcon.INFO, 16), "Show evidence");
            evidenceToggle.setName("card-evidence");
            evidenceToggle.addActionListener(e -> showEvidence(!evidence.isVisible()));
            header().actions().add(evidenceToggle);
            center.add(evidence, BorderLayout.SOUTH);
            mode.bind(this, value -> showEvidence(value == DisplayModeModel.Mode.ANALYST));
        } else {
            evidence.setText(text);
        }
        return this;
    }

    public boolean evidenceShown() { return evidence != null && evidence.isVisible(); }

    private void listen(Component component) {
        if (component instanceof AbstractButton) return;
        if (!Arrays.asList(component.getMouseListeners()).contains(clicks)) component.addMouseListener(clicks);
        if (component instanceof Container) {
            Container container = (Container) component;
            if (!Arrays.asList(container.getContainerListeners()).contains(adoption)) container.addContainerListener(adoption);
            for (Component child : container.getComponents()) listen(child);
        }
    }

    private void setHovered(boolean value) {
        if (hovered == value) return;
        hovered = value;
        repaint();
    }

    private void showEvidence(boolean shown) {
        evidence.setVisible(shown);
        String label = shown ? "Hide evidence" : "Show evidence";
        evidenceToggle.setToolTipText(label);
        evidenceToggle.getAccessibleContext().setAccessibleName(label);
        revalidate();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth() - 1, height = getHeight() - 1;
        Color surface = Tokens.color(Tokens.Role.SURFACE);
        g.setColor(hovered ? Tokens.blend(surface, Tokens.color(Tokens.Role.ACCENT_WASH), .6f) : surface);
        g.fillRoundRect(0, 0, width, height, Tokens.ARC_CARD, Tokens.ARC_CARD);
        if (open != null && isFocusOwner()) {
            g.setColor(Tokens.color(Tokens.Role.ACCENT));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, width - 2, height - 2, Tokens.ARC_CARD, Tokens.ARC_CARD);
        } else {
            g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
            g.drawRoundRect(0, 0, width, height, Tokens.ARC_CARD, Tokens.ARC_CARD);
        }
        g.dispose();
    }
}
```

- [ ] **Step 5: Implement `StatTile.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;

/** Label, metric and optional sub-line on a raised surface. Replaces StatsUi.metrics and ad-hoc KPI cards. */
public class StatTile extends JPanel {
    private final String label;
    private final JLabel name = new JLabel(), value = new JLabel(), sub = new JLabel();
    private final JPanel trend = new JPanel(new BorderLayout());
    private DisplayValue current = DisplayValue.unknown("");

    public StatTile(String label) {
        this.label = label;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(new EmptyBorder(Tokens.S + 2, Tokens.M, Tokens.S + 2, Tokens.M));
        setName("tile-" + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""));
        for (JLabel text : new JLabel[]{name, value, sub}) text.putClientProperty("html.disable", true);
        ContentStyle.font(name, Type.caption());
        ContentStyle.font(value, Type.metric());
        ContentStyle.font(sub, Type.caption());
        trend.setOpaque(false);
        for (JComponent part : new JComponent[]{name, value, sub, trend}) { part.setAlignmentX(LEFT_ALIGNMENT); add(part); }
        name.setText(label);
        setValue(current, null);
    }

    public void setValue(DisplayValue shown, String subline) {
        current = shown;
        value.setText(shown.display());
        value.setToolTipText(shown.tooltip());
        boolean hasSub = subline != null && !subline.isEmpty();
        sub.setText(hasSub ? subline : "");
        sub.setVisible(hasSub);
        getAccessibleContext().setAccessibleName(label + ": " + shown.display() + (hasSub ? ", " + subline : ""));
        refreshColors();
    }

    public DisplayValue value() { return current; }

    /** Holds an optional Sparkline (Task 9). */
    public JPanel trendSlot() { return trend; }

    @Override public void updateUI() { super.updateUI(); if (sub != null && current != null) refreshColors(); }

    private void refreshColors() {
        name.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        sub.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        value.setForeground(Tokens.color(current.dimmed() ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT));
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Tokens.color(Tokens.Role.RAISED));
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
        g.dispose();
    }
}
```

- [ ] **Step 6: Implement `EmptyState.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** An invitation, not an apology: what belongs here and the next action. */
public class EmptyState extends JPanel {
    private final JTextArea text;

    public EmptyState(String title, String body, KitButton action) {
        super(new GridBagLayout());
        setOpaque(false);
        setName("empty-state");
        JLabel heading = new JLabel(title) {
            @Override public void updateUI() { super.updateUI(); setForeground(Tokens.color(Tokens.Role.TEXT)); }
        };
        heading.putClientProperty("html.disable", true);
        ContentStyle.font(heading, Type.emphasis());
        text = ContentStyle.wrappingText(body);
        text.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.insets = new Insets(0, 0, Tokens.XS, 0);
        add(heading, c);
        c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.insets = new Insets(0, Tokens.XL, Tokens.S, Tokens.XL);
        add(text, c);
        if (action != null) { c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0; c.insets = new Insets(0, 0, 0, 0); add(action, c); }
        getAccessibleContext().setAccessibleName(title);
        getAccessibleContext().setAccessibleDescription(body);
    }

    @Override public void updateUI() {
        super.updateUI();
        if (text != null) text.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)); // null during JPanel's constructor
    }
}
```

- [ ] **Step 7: Implement `Collapsible.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import tomato.gui.modern.LineIcon;
import util.PropertiesManager;

/** A titled section that remembers whether it is open. Opening and closing use Motion (≤100 ms). */
public class Collapsible extends JPanel {
    public static final String PREFIX = "ui.collapse.";

    private final String id;
    private final JComponent content;
    private final JPanel holder;
    private final KitButton toggle;
    private final BiConsumer<String, String> write;
    private boolean expanded;
    private float fraction = 1f;
    private Timer animation;

    public Collapsible(String id, String title, JComponent content, boolean expandedByDefault) {
        this(id, title, content, expandedByDefault, PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    Collapsible(String id, String title, JComponent content, boolean expandedByDefault,
                Function<String, String> read, BiConsumer<String, String> write) {
        // No layout gap: the content holder carries its own padding, so a closed section leaves no space.
        super(new BorderLayout());
        this.id = Objects.requireNonNull(id, "id");
        this.content = Objects.requireNonNull(content, "content");
        this.write = write;
        setOpaque(false);
        toggle = KitButton.ghost(title);
        toggle.setName("collapsible-" + id);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.addActionListener(e -> setExpanded(!expanded));
        holder = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                if (!content.isVisible()) return new Dimension(0, 0);
                Dimension size = content.getPreferredSize();
                Insets padding = getInsets();
                return new Dimension(size.width + padding.left + padding.right,
                    Math.round((size.height + padding.top + padding.bottom) * fraction));
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(Tokens.XS, 0, 0, 0));
        holder.add(content);
        add(toggle, BorderLayout.NORTH);
        add(holder, BorderLayout.CENTER);
        String saved = read.apply(PREFIX + id);
        expanded = saved == null || saved.isEmpty() ? expandedByDefault : Boolean.parseBoolean(saved);
        content.setVisible(expanded);
        updateToggle();
    }

    public boolean expanded() { return expanded; }
    public KitButton toggle() { return toggle; }

    public void setExpanded(boolean value) {
        if (value == expanded) return;
        expanded = value;
        write.accept(PREFIX + id, Boolean.toString(value));
        updateToggle();
        if (animation != null) animation.stop();
        if (value) content.setVisible(true);
        animation = Motion.run(Motion.MAX_MILLIS,
            progress -> { fraction = (float) (value ? progress : 1 - progress); holder.revalidate(); repaint(); },
            () -> { fraction = 1f; content.setVisible(expanded); animation = null; holder.revalidate(); repaint(); });
    }

    private void updateToggle() {
        toggle.setIcon(new LineIcon(expanded ? LineIcon.CHEVRON_DOWN : LineIcon.CHEVRON_RIGHT, 14));
        toggle.getAccessibleContext().setAccessibleDescription(expanded ? "Expanded" : "Collapsed");
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.ContainersTest"`
Expected: PASS (5 tests).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/tomato/gui/kit/SectionHeader.java src/main/java/tomato/gui/kit/EvidenceNote.java src/main/java/tomato/gui/kit/Card.java src/main/java/tomato/gui/kit/StatTile.java src/main/java/tomato/gui/kit/EmptyState.java src/main/java/tomato/gui/kit/Collapsible.java src/test/java/tomato/gui/kit/ContainersTest.java
git commit -m "Add cards, stat tiles, empty states and collapsible sections

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Filter bar

**Files:**
- Create: `src/main/java/tomato/gui/kit/FilterBar.java`
- Test: `src/test/java/tomato/gui/kit/FilterBarTest.java`

**Interfaces:**
- Consumes: `KitButton`, `Chip.removable`, `OverflowMenu`, `Motion`, `LineIcon`, `ContentStyle.controls()`.
- Produces:
  - `FilterBar.ActiveFilter(String label, Runnable remove)` with public final fields
  - `FilterBar(String name)` and package-private `FilterBar(String name, Function<String,String> read, BiConsumer<String,String> write)`
  - `FilterBar search(JComponent)`, `FilterBar scope(JComponent)`, `FilterBar drawer(JComponent /* null hides Filters */)`, `OverflowMenu overflow()`
  - `void setActive(List<ActiveFilter> filters, Runnable clearAll /* nullable */)`, `int activeCount()`
  - `boolean drawerOpen()`, `void setDrawerOpen(boolean)`, `void setDrawerEnabled(boolean)`, `JComponent drawerContent()`
  - Component names: `<name>-filter-bar`, `<name>-filters` (toggle), `<name>-clear-filters`, `<name>-filter-drawer`, `<name>-more` (overflow); preference `ui.filters.<name>.open`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.util.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

public class FilterBarTest {
    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void activeFiltersBecomeRemovableChipsWithACount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            FilterBar bar = new FilterBar("runs", store::get, store::put);
            bar.search(new JTextField(12)).drawer(new JLabel("Outcome, evidence, dates"));
            List<String> removed = new ArrayList<>(); int[] cleared = {0};
            bar.setActive(Arrays.asList(new FilterBar.ActiveFilter("Completed", () -> removed.add("outcome")),
                new FilterBar.ActiveFilter("Last 7 days", () -> removed.add("dates"))), () -> cleared[0]++);
            AbstractButton filters = ControlsTest.find(bar, "runs-filters");
            assertEquals("Filters · 2", filters.getText());
            assertEquals(2, bar.activeCount());
            ControlsTest.find(bar, "remove-filter").doClick();
            assertEquals(Collections.singletonList("outcome"), removed);
            AbstractButton clear = ControlsTest.find(bar, "runs-clear-filters");
            assertTrue(clear.isVisible());
            clear.doClick();
            assertEquals(1, cleared[0]);
            bar.setActive(Collections.emptyList(), null);
            assertEquals("Filters", filters.getText());
            assertFalse(clear.isVisible());
        });
    }

    @Test public void drawerIsClosedByDefaultAndRemembered() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> store = new HashMap<>();
            JLabel facets = new JLabel("Facets");
            FilterBar bar = new FilterBar("loot", store::get, store::put).drawer(facets);
            assertFalse(bar.drawerOpen());
            assertFalse("The drawer panel is hidden while closed", facets.getParent().isVisible());
            ControlsTest.find(bar, "loot-filters").doClick();
            assertTrue(bar.drawerOpen());
            assertEquals("true", store.get("ui.filters.loot.open"));
            assertTrue(new FilterBar("loot", store::get, store::put).drawer(new JLabel()).drawerOpen());
        });
    }

    @Test public void withoutADrawerTheFiltersToggleIsHidden() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar("chat", k -> null, (k, v) -> {});
            assertFalse(ControlsTest.find(bar, "chat-filters").isVisible());
            bar.setDrawerOpen(true);
            assertFalse("Cannot open an empty drawer", bar.drawerOpen());
        });
    }

    @Test public void drawerContentCanBeDisabledWhileAQueryLoads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel facets = new JPanel(); JButton apply = new JButton("Apply"); facets.add(apply);
            FilterBar bar = new FilterBar("keypops", k -> null, (k, v) -> {}).drawer(facets);
            bar.setDrawerEnabled(false);
            assertFalse(apply.isEnabled());
            bar.setDrawerEnabled(true);
            assertTrue(apply.isEnabled());
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.FilterBarTest"`
Expected: FAIL — `cannot find symbol: class FilterBar`.

- [ ] **Step 3: Implement `FilterBar.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;
import util.PropertiesManager;

/**
 * One row: search, a Filters toggle with the active count, removable chips, Clear, then scope and
 * "More actions" on the right. The module's existing facet controls live in a drawer below, closed by default.
 */
public class FilterBar extends JPanel {
    public static final class ActiveFilter {
        public final String label;
        public final Runnable remove;
        public ActiveFilter(String label, Runnable remove) {
            this.label = Objects.requireNonNull(label, "label");
            this.remove = Objects.requireNonNull(remove, "remove");
        }
    }

    private final String name;
    private final BiConsumer<String, String> write;
    private final JPanel leading = ContentStyle.controls();
    private final JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.XS + 2, 2));
    private final KitButton filters = KitButton.secondary("Filters");
    private final KitButton clear = KitButton.ghost("Clear");
    private final OverflowMenu overflow;
    private final JPanel drawer = new JPanel(new BorderLayout());
    private final JPanel drawerHolder;
    /** Components disabled by setDrawerEnabled(false) and their previous state, restored exactly. */
    private final Map<Component, Boolean> disabledByLoad = new IdentityHashMap<>();
    private JComponent search, scope, drawerContent;
    private List<ActiveFilter> active = Collections.emptyList();
    private Runnable clearAll;
    private boolean open;
    private float fraction = 1f;
    private Timer animation;

    public FilterBar(String name) { this(name, PropertiesManager::getProperty, PropertiesManager::setProperties); }

    FilterBar(String name, Function<String, String> read, BiConsumer<String, String> write) {
        // No layout gap: the drawer carries its own top padding, so a closed drawer leaves no space.
        super(new BorderLayout());
        this.name = Objects.requireNonNull(name, "name");
        this.write = write;
        setOpaque(false);
        setName(name + "-filter-bar");
        overflow = new OverflowMenu(name + "-more");
        filters.setName(name + "-filters");
        filters.setIcon(new LineIcon(LineIcon.FILTER, 14));
        filters.addActionListener(e -> setDrawerOpen(!open));
        clear.setName(name + "-clear-filters");
        clear.addActionListener(e -> { if (clearAll != null) clearAll.run(); });
        leading.setOpaque(false);
        trailing.setOpaque(false);
        drawer.setOpaque(false);
        drawer.setBorder(new EmptyBorder(Tokens.S, 0, 0, 0));
        drawer.setName(name + "-filter-drawer");
        JPanel row = new JPanel(new BorderLayout(Tokens.S, 0));
        row.setOpaque(false);
        row.add(leading, BorderLayout.CENTER);
        row.add(trailing, BorderLayout.EAST);
        drawerHolder = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                if (!drawer.isVisible()) return new Dimension(0, 0);
                Dimension size = drawer.getPreferredSize();
                return new Dimension(size.width, Math.round(size.height * fraction));
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        drawerHolder.setOpaque(false);
        drawerHolder.add(drawer);
        add(row, BorderLayout.NORTH);
        add(drawerHolder, BorderLayout.CENTER);
        open = "true".equals(read.apply(key()));
        drawer.setVisible(false);
        rebuild();
    }

    private String key() { return "ui.filters." + name + ".open"; }

    /** Search and scope are added once and never re-parented by rebuild(), so typing keeps focus. */
    public FilterBar search(JComponent field) {
        if (search != null) leading.remove(search);
        search = field;
        if (field != null) leading.add(field, 0);
        rebuild();
        return this;
    }

    public FilterBar scope(JComponent value) {
        if (scope != null) trailing.remove(scope);
        scope = value;
        if (value != null) trailing.add(value, 0);
        rebuild();
        return this;
    }

    /** The module's facet controls; null removes the Filters toggle. Replacing content keeps the open state. */
    public FilterBar drawer(JComponent content) {
        drawer.removeAll();
        drawerContent = content;
        if (content != null) drawer.add(content);
        drawer.setVisible(content != null && open);
        rebuild();
        return this;
    }

    public JComponent drawerContent() { return drawerContent; }
    public OverflowMenu overflow() { return overflow; }

    public void setActive(List<ActiveFilter> filters, Runnable clearAll) {
        active = Collections.unmodifiableList(new ArrayList<>(filters));
        this.clearAll = clearAll;
        rebuild();
    }

    public int activeCount() { return active.size(); }
    public boolean drawerOpen() { return open && drawerContent != null; }

    public void setDrawerOpen(boolean value) {
        if (drawerContent == null) value = false;
        if (value == open && (drawer.isVisible() == value)) return;
        open = value;
        write.accept(key(), Boolean.toString(value));
        updateFiltersButton();
        if (animation != null) animation.stop();
        if (value) { drawer.setVisible(true); drawerHolder.setVisible(true); }
        final boolean opening = value;
        animation = Motion.run(Motion.MAX_MILLIS,
            progress -> { fraction = (float) (opening ? progress : 1 - progress); drawerHolder.revalidate(); repaint(); },
            () -> {
                fraction = 1f;
                drawer.setVisible(open && drawerContent != null);
                drawerHolder.setVisible(drawer.isVisible());
                animation = null;
                revalidate();
                repaint();
            });
    }

    /**
     * Disables the facet controls, chips and Clear while a query is loading, so edits cannot be silently
     * discarded; true restores each component's previous state. The Filters toggle stays usable.
     */
    public void setDrawerEnabled(boolean enabled) {
        if (enabled) {
            for (Map.Entry<Component, Boolean> entry : disabledByLoad.entrySet()) entry.getKey().setEnabled(entry.getValue());
            disabledByLoad.clear();
            return;
        }
        if (drawerContent != null) disableTree(drawerContent);
        for (Component child : leading.getComponents()) if (child != search && child != filters) disableTree(child);
    }

    private void disableTree(Component component) {
        if (!disabledByLoad.containsKey(component)) disabledByLoad.put(component, component.isEnabled());
        component.setEnabled(false);
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) disableTree(child);
    }

    private void rebuild() {
        for (Component child : leading.getComponents()) if (child != search) leading.remove(child);
        leading.add(filters);
        filters.setVisible(drawerContent != null);
        for (ActiveFilter filter : active) leading.add(Chip.removable(filter.label, filter.remove));
        leading.add(clear);
        clear.setVisible(!active.isEmpty() && clearAll != null);
        for (Component child : trailing.getComponents()) if (child != scope) trailing.remove(child);
        trailing.add(overflow);
        drawerHolder.setVisible(drawer.isVisible());
        updateFiltersButton();
        revalidate();
        repaint();
    }

    private void updateFiltersButton() {
        filters.setText(active.isEmpty() ? "Filters" : "Filters · " + active.size());
        filters.getAccessibleContext().setAccessibleDescription(drawerOpen() ? "Filters shown" : "Filters hidden");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.FilterBarTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/FilterBar.java src/test/java/tomato/gui/kit/FilterBarTest.java
git commit -m "Add the collapsible filter bar

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Customizable tabs

**Files:**
- Create: `src/main/java/tomato/gui/kit/CustomizableTabs.java`
- Test: `src/test/java/tomato/gui/kit/CustomizableTabsTest.java`

**Interfaces:**
- Consumes: `DisplayModeModel` (Task 4), `util.PropertiesManager`.
- Produces:
  - `CustomizableTabs(String group)`; package-private `CustomizableTabs(String group, DisplayModeModel mode, Function<String,String> read, BiConsumer<String,String> write)`
  - `JTabbedPane component()` (named `<group>-tabs`)
  - `CustomizableTabs add(String id, String title, Component)`, `addAnalyst(String id, String title, Component)`
  - `List<String> order()`, `List<String> visibleIds()`, `Set<String> hiddenIds()`, `String selectedId()`, `void select(String id)`
  - `void move(String id, int delta)`, `boolean hide(String id)`, `void show(String id)`, `void reset()`, `boolean isRebuilding()`
  - `void onSelect(Consumer<String> listener)` — called once per settled selection change (never for rebuild churn); use it instead of a raw `ChangeListener` when persisting the selected tab
  - Keyboard: `ctrl shift LEFT/RIGHT` moves the selected tab; `shift F10` and `CONTEXT_MENU` open the tab menu (`<group>-tab-menu`)
  - Preference `ui.tabs.<group>` = `id,id,…|hiddenId,…`; IDs match `[a-z0-9][a-z0-9-]*`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CustomizableTabsTest {
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);

    private CustomizableTabs tabs() {
        return new CustomizableTabs("character", mode, store::get, store::put)
            .add("overview", "Overview", new JPanel())
            .add("gear", "Gear", new JPanel())
            .add("exalts", "Exalts", new JPanel())
            .addAnalyst("evidence", "Snapshot evidence", new JPanel());
    }

    @Test public void analystOnlyTabsAppearOnlyInAnalystMode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals(3, tabs.component().getTabCount());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(Arrays.asList("overview", "gear", "exalts", "evidence"), tabs.visibleIds());
            assertEquals("Snapshot evidence", tabs.component().getTitleAt(3));
        });
    }

    @Test public void orderAndHiddenTabsPersistAndSelectionFollowsTheTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            tabs.select("exalts");
            tabs.move("exalts", -2);
            assertEquals(Arrays.asList("exalts", "overview", "gear"), tabs.visibleIds());
            assertEquals("exalts", tabs.selectedId());
            assertTrue(tabs.hide("gear"));
            assertEquals("exalts,overview,gear,evidence|gear", store.get("ui.tabs.character"));
            CustomizableTabs reopened = tabs();
            assertEquals(Arrays.asList("exalts", "overview"), reopened.visibleIds());
            assertEquals(Collections.singleton("gear"), reopened.hiddenIds());
            reopened.show("gear");
            assertEquals("gear", reopened.selectedId());
            reopened.reset();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), reopened.visibleIds());
        });
    }

    @Test public void theLastVisibleTabCannotBeHiddenAndNewTabsAppend() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            store.put("ui.tabs.character", "gear,overview|");
            CustomizableTabs tabs = tabs();
            assertEquals(Arrays.asList("gear", "overview", "exalts"), tabs.visibleIds());
            assertTrue(tabs.hide("gear"));
            assertTrue(tabs.hide("overview"));
            assertFalse(tabs.hide("exalts"));
            assertEquals(Collections.singletonList("exalts"), tabs.visibleIds());
        });
    }

    @Test public void selectionIsReportedOnceAfterHidingTheSelectedTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            List<String> seen = new ArrayList<>();
            tabs.onSelect(seen::add);
            tabs.select("gear");
            tabs.hide("gear");
            assertEquals(Arrays.asList("gear", "overview"), seen);
        });
    }

    @Test public void invalidIdsAreRejected() {
        try { new CustomizableTabs("Bad Group", mode, store::get, store::put); fail(); } catch (IllegalArgumentException expected) { }
        try { tabs().add("gear", "Duplicate", new JPanel()); fail(); } catch (IllegalArgumentException expected) { }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.CustomizableTabsTest"`
Expected: FAIL — `cannot find symbol: class CustomizableTabs`.

- [ ] **Step 3: Implement `CustomizableTabs.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.swing.*;
import util.PropertiesManager;

/**
 * A JTabbedPane whose tabs have stable IDs and can be reordered (drag, menu or Ctrl+Shift+Left/Right)
 * and hidden. Analyst-only tabs are skipped in Simple mode without changing the saved order.
 */
public class CustomizableTabs {
    public static final String PREFIX = "ui.tabs.";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final class Entry {
        final String id, title;
        final Component component;
        final boolean analystOnly;
        Entry(String id, String title, Component component, boolean analystOnly) {
            this.id = id; this.title = title; this.component = component; this.analystOnly = analystOnly;
        }
    }

    private final String group;
    private final DisplayModeModel mode;
    private final BiConsumer<String, String> write;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> savedOrder = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final List<Consumer<String>> selectionListeners = new ArrayList<>();
    private boolean rebuilding;
    private String dragging, lastSelected;

    public CustomizableTabs(String group) {
        this(group, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    CustomizableTabs(String group, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write) {
        if (!ID.matcher(group).matches()) throw new IllegalArgumentException("Tab group IDs use lowercase letters, digits and hyphens: " + group);
        this.group = group;
        this.mode = mode;
        this.write = write;
        tabs.setName(group + "-tabs");
        load(read.apply(PREFIX + group));
        installGestures();
        tabs.addChangeListener(e -> { if (!rebuilding) notifySelection(); });
        mode.bind(tabs, ignored -> rebuild());
    }

    public JTabbedPane component() { return tabs; }

    public CustomizableTabs add(String id, String title, Component component) { return add(id, title, component, false); }
    public CustomizableTabs addAnalyst(String id, String title, Component component) { return add(id, title, component, true); }

    private CustomizableTabs add(String id, String title, Component component, boolean analystOnly) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Tab IDs use lowercase letters, digits and hyphens: " + id);
        if (entries.containsKey(id)) throw new IllegalArgumentException("Duplicate tab ID " + id);
        entries.put(id, new Entry(id, title, component, analystOnly));
        rebuild();
        return this;
    }

    /** Every known tab in the user's order, including hidden and Analyst-only tabs; new tabs append. */
    public List<String> order() {
        List<String> result = new ArrayList<>();
        for (String id : savedOrder) if (entries.containsKey(id) && !result.contains(id)) result.add(id);
        for (String id : entries.keySet()) if (!result.contains(id)) result.add(id);
        return result;
    }

    public List<String> visibleIds() {
        List<String> result = new ArrayList<>();
        for (String id : order()) if (shown(entries.get(id))) result.add(id);
        return result;
    }

    public Set<String> hiddenIds() {
        Set<String> result = new LinkedHashSet<>();
        for (String id : order()) if (hidden.contains(id)) result.add(id);
        return Collections.unmodifiableSet(result);
    }

    public String selectedId() {
        int index = tabs.getSelectedIndex();
        return index < 0 ? null : idAt(index);
    }

    public void select(String id) {
        int index = visibleIds().indexOf(id);
        if (index >= 0) tabs.setSelectedIndex(index);
    }

    public boolean isRebuilding() { return rebuilding; }

    public void move(String id, int delta) {
        List<String> visible = visibleIds();
        int from = visible.indexOf(id), to = from + delta;
        if (from < 0 || delta == 0 || to < 0 || to >= visible.size()) return;
        List<String> all = order();
        String neighbor = visible.get(to);
        all.remove(id);
        int at = all.indexOf(neighbor);
        all.add(delta > 0 ? at + 1 : at, id);
        savedOrder.clear();
        savedOrder.addAll(all);
        save();
        rebuild();
        select(id);
    }

    public boolean hide(String id) {
        List<String> visible = visibleIds();
        if (!visible.contains(id) || visible.size() <= 1) return false;
        hidden.add(id);
        save();
        rebuild();
        return true;
    }

    public void show(String id) {
        if (!hidden.remove(id)) return;
        save();
        rebuild();
        select(id);
    }

    public void reset() {
        savedOrder.clear();
        hidden.clear();
        save();
        rebuild();
    }

    private boolean shown(Entry entry) { return !hidden.contains(entry.id) && (!entry.analystOnly || mode.analyst()); }

    private void load(String saved) {
        if (saved == null || saved.isEmpty()) return;
        String[] parts = saved.split("\\|", -1);
        for (String id : parts[0].split(",")) if (ID.matcher(id).matches()) savedOrder.add(id);
        if (parts.length > 1) for (String id : parts[1].split(",")) if (ID.matcher(id).matches()) hidden.add(id);
    }

    private void save() {
        hidden.retainAll(entries.keySet());
        write.accept(PREFIX + group, String.join(",", order()) + "|" + String.join(",", hiddenIds()));
    }

    /**
     * Brings the tab strip to the visible order touching only tabs that must change: hidden tabs are removed,
     * moved tabs are re-inserted, untouched tabs keep their content, focus and bound listeners. The final
     * selection is applied after rebuilding ends, so selection listeners see the settled tab once.
     */
    private void rebuild() {
        List<String> visible = visibleIds();
        if (visible.equals(currentIds())) return;
        String selected = selectedId();
        rebuilding = true;
        try {
            for (int i = tabs.getTabCount() - 1; i >= 0; i--) if (!visible.contains(idAt(i))) tabs.removeTabAt(i);
            for (int i = 0; i < visible.size(); i++) {
                String id = visible.get(i);
                List<String> current = currentIds();
                if (i < current.size() && id.equals(current.get(i))) continue;
                int existing = current.indexOf(id);
                if (existing >= 0) tabs.removeTabAt(existing);
                Entry entry = entries.get(id);
                tabs.insertTab(entry.title, null, entry.component, null, i);
            }
        } finally {
            rebuilding = false;
        }
        int index = selected == null ? -1 : visible.indexOf(selected);
        if (!visible.isEmpty() && tabs.getSelectedIndex() != Math.max(0, index)) tabs.setSelectedIndex(Math.max(0, index));
        notifySelection();
        tabs.revalidate();
        tabs.repaint();
    }

    /** Called once per settled selection change; rebuild churn is not reported. */
    public void onSelect(Consumer<String> listener) { selectionListeners.add(listener); }

    private void notifySelection() {
        String id = selectedId();
        if (Objects.equals(id, lastSelected)) return;
        lastSelected = id;
        for (Consumer<String> listener : selectionListeners) listener.accept(id);
    }

    private List<String> currentIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) ids.add(idAt(i));
        return ids;
    }

    private String idAt(int index) {
        Component component = tabs.getComponentAt(index);
        for (Entry entry : entries.values()) if (entry.component == component) return entry.id;
        return null;
    }

    private void installGestures() {
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { menu(e.getPoint()); return; }
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                dragging = index < 0 ? null : idAt(index);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) menu(e.getPoint());
                dragging = null;
            }
        });
        tabs.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging == null) return;
                int target = tabs.indexAtLocation(e.getX(), e.getY()), from = visibleIds().indexOf(dragging);
                if (target >= 0 && from >= 0 && target != from) move(dragging, target - from);
            }
        });
        InputMap keys = tabs.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke("ctrl shift LEFT"), "tab-move-left");
        keys.put(KeyStroke.getKeyStroke("ctrl shift RIGHT"), "tab-move-right");
        keys.put(KeyStroke.getKeyStroke("shift F10"), "tab-menu");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "tab-menu");
        tabs.getActionMap().put("tab-move-left", action(() -> { String id = selectedId(); if (id != null) move(id, -1); }));
        tabs.getActionMap().put("tab-move-right", action(() -> { String id = selectedId(); if (id != null) move(id, 1); }));
        tabs.getActionMap().put("tab-menu", action(() -> {
            int index = tabs.getSelectedIndex();
            Rectangle bounds = index < 0 ? new Rectangle() : tabs.getBoundsAt(index);
            menu(new Point(bounds.x, bounds.y + bounds.height));
        }));
    }

    private static Action action(Runnable run) {
        return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { run.run(); } };
    }

    private void menu(Point at) {
        int index = tabs.indexAtLocation(at.x, Math.max(0, at.y - 1));
        if (index < 0) index = tabs.getSelectedIndex();
        String id = index < 0 ? null : idAt(index);
        List<String> visible = visibleIds();
        JPopupMenu menu = new JPopupMenu();
        menu.setName(group + "-tab-menu");
        JMenuItem left = new JMenuItem("Move left"), right = new JMenuItem("Move right"), hide = new JMenuItem("Hide tab"),
            reset = new JMenuItem("Reset tabs");
        left.setEnabled(id != null && visible.indexOf(id) > 0);
        right.setEnabled(id != null && visible.indexOf(id) < visible.size() - 1);
        hide.setEnabled(id != null && visible.size() > 1);
        left.addActionListener(e -> move(id, -1));
        right.addActionListener(e -> move(id, 1));
        hide.addActionListener(e -> hide(id));
        reset.addActionListener(e -> reset());
        JMenu restore = new JMenu("Show hidden tab");
        for (String hiddenId : hiddenIds()) {
            JMenuItem item = new JMenuItem(entries.get(hiddenId).title);
            item.addActionListener(e -> show(hiddenId));
            restore.add(item);
        }
        restore.setEnabled(restore.getItemCount() > 0);
        menu.add(left); menu.add(right); menu.add(hide); menu.addSeparator(); menu.add(restore); menu.add(reset);
        menu.show(tabs, at.x, at.y);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.CustomizableTabsTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/kit/CustomizableTabs.java src/test/java/tomato/gui/kit/CustomizableTabsTest.java
git commit -m "Add customizable tabs with persisted order and hiding

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Game widgets

**Files:**
- Create: `src/main/java/tomato/gui/kit/ItemSlot.java`, `PipMeter.java`, `StatBar.java`, `Sparkline.java`
- Test: `src/test/java/tomato/gui/kit/GameWidgetsTest.java`

**Interfaces:**
- Consumes: `Tokens`, `Type`, `Sprites`, `ContentStyle.body()`.
- Produces:
  - `ItemSlot(int size)`; `ItemSlot.State { ITEM, EMPTY, UNKNOWN }`; `void setItem(int objectId, String tierLabel)`, `setEmpty()`, `setUnknown()`, `State state()`, `int itemId()`
  - `PipMeter(int total)`; `void setFilled(int)`, `int filled()`, `void setColor(Tokens.Role)`
  - `StatBar()`; `void set(Integer value, Integer cap)`, `boolean maxed()`
  - `Sparkline()`; `void setValues(double[])`, `double[] values()`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.kit;

import java.util.Locale;
import javax.swing.SwingUtilities;
import org.junit.*;
import static org.junit.Assert.*;

public class GameWidgetsTest {
    private Locale previous;
    @Before public void fix() { previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US); }
    @After public void restore() { Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void itemSlotDistinguishesEmptyUnknownAndItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ItemSlot slot = new ItemSlot(24);
            assertEquals(ItemSlot.State.UNKNOWN, slot.state());
            assertEquals("Slot not captured", slot.getToolTipText());
            slot.setItem(0, "UT");
            assertEquals(ItemSlot.State.EMPTY, slot.state());
            slot.setItem(987_654_321, "UT");
            assertEquals(ItemSlot.State.ITEM, slot.state());
            assertEquals("Unknown item #987654321 · UT", slot.getAccessibleContext().getAccessibleName());
            assertEquals(30, slot.getPreferredSize().width);
        });
    }

    @Test public void pipMeterClampsAndDescribesProgress() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PipMeter pips = new PipMeter(5);
            pips.setFilled(9);
            assertEquals(5, pips.filled());
            pips.setFilled(3);
            assertEquals("3 of 5", pips.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test public void statBarKnowsMaxedAndUnknown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatBar bar = new StatBar();
            assertFalse(bar.maxed());
            assertEquals("Not captured", bar.getToolTipText());
            bar.set(20, 25);
            assertEquals("20 of 25", bar.getToolTipText());
            bar.set(75, 75);
            assertTrue(bar.maxed());
            assertEquals("75 of 75, maxed", bar.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test public void sparklineCopiesItsValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            double[] fame = {10, 30, 20};
            Sparkline line = new Sparkline();
            line.setValues(fame);
            fame[0] = 99;
            assertEquals(10, line.values()[0], 0);
            assertEquals("From 10 to 20, low 10, high 30", line.getAccessibleContext().getAccessibleDescription());
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.GameWidgetsTest"`
Expected: FAIL — `cannot find symbol: class ItemSlot`.

- [ ] **Step 3: Implement `ItemSlot.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;

/** One equipment or inventory slot. Empty and not-captured are drawn differently; items show their sprite and tier edge. */
public class ItemSlot extends JComponent implements Accessible {
    public enum State { ITEM, EMPTY, UNKNOWN }

    /** Plain JComponent has no accessible context; without this, getAccessibleContext() returns null. */
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    private final int size;
    private State state = State.UNKNOWN;
    private int itemId = -1;
    private String tier = "";

    public ItemSlot(int size) {
        this.size = size;
        setOpaque(false);
        setUnknown();
    }

    public void setItem(int objectId, String tierLabel) {
        if (objectId <= 0) { setEmpty(); return; }
        state = State.ITEM;
        itemId = objectId;
        tier = tierLabel == null ? "" : tierLabel;
        String name = Sprites.name(objectId);
        describe(tier.isEmpty() ? name : name + " · " + tier);
    }

    public void setEmpty() { state = State.EMPTY; itemId = -1; tier = ""; describe("Empty slot"); }
    public void setUnknown() { state = State.UNKNOWN; itemId = -1; tier = ""; describe("Slot not captured"); }
    public State state() { return state; }
    public int itemId() { return itemId; }

    private void describe(String text) {
        setToolTipText(text);
        getAccessibleContext().setAccessibleName(text);
        repaint();
    }

    @Override public Dimension getPreferredSize() { return new Dimension(size + 6, size + 6); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int side = Math.min(getWidth(), getHeight()) - 1, x = (getWidth() - 1 - side) / 2, y = (getHeight() - 1 - side) / 2;
        g.setColor(Tokens.color(state == State.EMPTY ? Tokens.Role.SURFACE_ALT : Tokens.Role.RAISED));
        g.fillRoundRect(x, y, side, side, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(state == State.ITEM && !tier.isEmpty() ? Tokens.tier(tier) : Tokens.color(Tokens.Role.BORDER_SUBTLE));
        g.drawRoundRect(x, y, side, side, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        if (state == State.ITEM) {
            Icon icon = Sprites.sprite(itemId, size);
            icon.paintIcon(this, g, x + (side + 1 - icon.getIconWidth()) / 2, y + (side + 1 - icon.getIconHeight()) / 2);
        } else if (state == State.UNKNOWN) {
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            g.setFont(Type.caption());
            FontMetrics metrics = g.getFontMetrics();
            g.drawString("?", x + (side + 1 - metrics.stringWidth("?")) / 2, y + (side + 1 + metrics.getAscent() - metrics.getDescent()) / 2);
        }
        g.dispose();
    }
}
```

- [ ] **Step 4: Implement `PipMeter.java`, `StatBar.java` and `Sparkline.java`**

```java
package tomato.gui.kit;

import java.awt.*;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.modern.ContentStyle;

/** N small squares, the first `filled` in color: exalt tiers, maxed stats. Scales with the body font. */
public class PipMeter extends JComponent implements Accessible {
    private static final int GAP = 3;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PROGRESS_BAR; }
        };
        return accessibleContext;
    }
    private final int total;
    private int filled;
    private Tokens.Role color = Tokens.Role.ACCENT;

    public PipMeter(int total) {
        if (total < 1) throw new IllegalArgumentException("A pip meter needs at least one pip");
        this.total = total;
        setOpaque(false);
        setFilled(0);
    }

    public void setFilled(int value) {
        filled = Math.max(0, Math.min(total, value));
        String text = filled + " of " + total;
        setToolTipText(text);
        getAccessibleContext().setAccessibleDescription(text);
        repaint();
    }

    public int filled() { return filled; }
    public void setColor(Tokens.Role role) { color = role; repaint(); }

    private int pip() { return Math.max(7, Math.round(ContentStyle.body().getSize2D() * 0.7f)); }

    @Override public Dimension getPreferredSize() { int pip = pip(); return new Dimension(total * pip + (total - 1) * GAP, pip); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int pip = pip(), y = (getHeight() - pip) / 2;
        for (int i = 0; i < total; i++) {
            g.setColor(Tokens.color(i < filled ? color : Tokens.Role.CONTROL));
            g.fillRoundRect(i * (pip + GAP), y, pip, pip, 3, 3);
        }
        g.dispose();
    }
}
```

```java
package tomato.gui.kit;

import java.awt.*;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/** Value toward a cap: mint when maxed, amber while short, an empty track when unknown. */
public class StatBar extends JComponent implements Accessible {
    private Integer value, cap;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PROGRESS_BAR; }
        };
        return accessibleContext;
    }

    public StatBar() { setOpaque(false); set(null, null); }

    public void set(Integer value, Integer cap) {
        this.value = value;
        this.cap = cap;
        String text = value == null || cap == null ? "Not captured" : value + " of " + cap + (maxed() ? ", maxed" : "");
        setToolTipText(value == null || cap == null ? text : value + " of " + cap);
        getAccessibleContext().setAccessibleDescription(text);
        repaint();
    }

    public boolean maxed() { return value != null && cap != null && cap > 0 && value >= cap; }

    @Override public Dimension getPreferredSize() { return new Dimension(80, 6); }
    @Override public Dimension getMinimumSize() { return new Dimension(24, 6); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int height = Math.min(getHeight(), 6), y = (getHeight() - height) / 2;
        g.setColor(Tokens.color(Tokens.Role.CONTROL));
        g.fillRoundRect(0, y, getWidth(), height, height, height);
        if (value != null && cap != null && cap > 0) {
            int width = (int) Math.round(getWidth() * Math.min(1.0, Math.max(0, value) / (double) cap));
            g.setColor(Tokens.color(maxed() ? Tokens.Role.GOOD : Tokens.Role.WARN));
            g.fillRoundRect(0, y, width, height, height, height);
        }
        g.dispose();
    }
}
```

```java
package tomato.gui.kit;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.modern.DisplayFormat;

/** A tiny trend line with a tinted area. Fewer than two values draw nothing. */
public class Sparkline extends JComponent implements Accessible {
    private double[] values = new double[0];

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    public Sparkline() { setOpaque(false); }

    public void setValues(double[] input) {
        values = input == null ? new double[0] : input.clone();
        if (values.length > 1) {
            double low = values[0], high = values[0];
            for (double v : values) { low = Math.min(low, v); high = Math.max(high, v); }
            getAccessibleContext().setAccessibleDescription("From " + DisplayFormat.formatNumber(values[0], 0, 1) + " to "
                + DisplayFormat.formatNumber(values[values.length - 1], 0, 1) + ", low " + DisplayFormat.formatNumber(low, 0, 1)
                + ", high " + DisplayFormat.formatNumber(high, 0, 1));
        } else {
            getAccessibleContext().setAccessibleDescription(null);
        }
        repaint();
    }

    public double[] values() { return values.clone(); }

    @Override public Dimension getPreferredSize() { return new Dimension(60, 18); }

    @Override protected void paintComponent(Graphics graphics) {
        if (values.length < 2) return;
        double low = values[0], high = values[0];
        for (double v : values) { low = Math.min(low, v); high = Math.max(high, v); }
        double span = high - low == 0 ? 1 : high - low;
        int width = getWidth() - 2, height = getHeight() - 3;
        Path2D line = new Path2D.Double();
        for (int i = 0; i < values.length; i++) {
            double x = 1 + width * i / (double) (values.length - 1), y = 1 + height - height * (values[i] - low) / span;
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
        }
        Path2D area = new Path2D.Double(line);
        area.lineTo(1 + width, 1 + height);
        area.lineTo(1, 1 + height);
        area.closePath();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Tokens.tint(Tokens.color(Tokens.Role.ACCENT)));
        g.fill(area);
        g.setColor(Tokens.color(Tokens.Role.ACCENT));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
        g.dispose();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.GameWidgetsTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/kit/ItemSlot.java src/main/java/tomato/gui/kit/PipMeter.java src/main/java/tomato/gui/kit/StatBar.java src/main/java/tomato/gui/kit/Sparkline.java src/test/java/tomato/gui/kit/GameWidgetsTest.java
git commit -m "Add item slot, pip meter, stat bar and sparkline widgets

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Kit gallery evidence and phase verification

**Files:**
- Create: `src/test/java/tomato/gui/kit/KitGallery.java`, `src/test/java/tomato/gui/kit/KitGalleryEvidenceTest.java`

**Interfaces:**
- Consumes: every kit component; `ui.VisualEvidence` (`show`, `settle`, `capture`), `tomato.gui.modern.Themes`.
- Produces: screenshots under `build/p1a/ui-test/screenshots/redesign-kit/`; the P1a PR.

- [ ] **Step 1: Create the gallery**

`src/test/java/tomato/gui/kit/KitGallery.java`:
```java
package tomato.gui.kit;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Every kit component with representative synthetic values, for visual review. */
final class KitGallery {
    private KitGallery() {}

    static JComponent build() {
        DisplayModeModel mode = new DisplayModeModel(k -> null, (k, v) -> {});
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(Tokens.M, Tokens.M, Tokens.M, Tokens.M));

        JPanel buttons = ContentStyle.controls();
        buttons.add(KitButton.primary("Start capture"));
        buttons.add(KitButton.secondary("Refresh"));
        buttons.add(KitButton.ghost("Clear"));
        buttons.add(KitButton.danger("Delete view"));
        buttons.add(KitButton.icon(new tomato.gui.modern.LineIcon(tomato.gui.modern.LineIcon.GEAR, 16), "Settings"));
        buttons.add(new SegmentedControl("gallery-mode", "Simple", "Analyst"));
        page.add(section("Buttons", buttons));

        JPanel chips = ContentStyle.controls();
        for (Tokens.Tone tone : Tokens.Tone.values()) chips.add(new Chip(tone.name().toLowerCase(), tone));
        chips.add(Chip.removable("Last 7 days", () -> {}));
        page.add(section("Chips", chips));

        FilterBar bar = new FilterBar("gallery", k -> null, (k, v) -> {});
        JTextField search = new JTextField(16);
        search.putClientProperty("JTextField.placeholderText", "Search runs");
        bar.search(search).drawer(new JLabel("Outcome · Evidence · Duration · Dates"));
        bar.setActive(Arrays.asList(new FilterBar.ActiveFilter("Completed", () -> {}), new FilterBar.ActiveFilter("Last 7 days", () -> {})), () -> {});
        bar.scope(new JComboBox<>(new String[]{"All sessions", "Current session"}));
        bar.overflow().add("Export page…", () -> {});
        page.add(section("Filter bar", bar));

        JPanel tiles = ContentStyle.responsiveGrid(4, 140, Tokens.S);
        StatTile runs = new StatTile("Runs today"); runs.setValue(DisplayValue.known("7", "Saved runs"), "5 completed");
        StatTile fame = new StatTile("Fame"); fame.setValue(DisplayValue.known("+1,480", "Fame samples"), "1,050 / hour");
        Sparkline trend = new Sparkline(); trend.setValues(new double[]{2472, 2520, 2600, 2750, 2878}); fame.trendSlot().add(trend);
        StatTile dps = new StatTile("Weapon DPS"); dps.setValue(DisplayValue.estimate("1,240", "Weapon formula, 0 defense"), null);
        StatTile pets = new StatTile("Pet"); pets.setValue(DisplayValue.unknown("Visit the Pet Yard with capture on to load pets"), null);
        tiles.add(runs); tiles.add(fame); tiles.add(dps); tiles.add(pets);
        page.add(section("Tiles", tiles));

        JPanel gear = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.XS, 0));
        gear.setOpaque(false);
        ItemSlot weapon = new ItemSlot(32); weapon.setItem(987_654_321, "UT");
        ItemSlot empty = new ItemSlot(32); empty.setEmpty();
        gear.add(weapon); gear.add(empty); gear.add(new ItemSlot(32));
        PipMeter exalts = new PipMeter(5); exalts.setFilled(3);
        StatBar def = new StatBar(); def.set(20, 25);
        StatBar att = new StatBar(); att.set(75, 75);
        gear.add(exalts); gear.add(def); gear.add(att);
        Card card = new Card(mode).title("Sharkbait · Wizard").body(gear).evidence("Equipment from the live character; backpack not captured.");
        card.header().setCount("7/8 maxed");
        card.onOpen("Open character", () -> {});
        page.add(card);

        page.add(new Collapsible("gallery-timeline", "Timeline", new JLabel("Area entered · Equipment changed · Exalt gained"), true, k -> null, (k, v) -> {}));
        page.add(new EmptyState("See your character here", "Start capture and enter the game to load your character.", KitButton.primary("Start capture")));

        CustomizableTabs tabs = new CustomizableTabs("gallery", mode, k -> null, (k, v) -> {})
            .add("overview", "Overview", new JLabel("Overview")).add("gear", "Gear", new JLabel("Gear")).addAnalyst("evidence", "Snapshot evidence", new JLabel("Evidence"));
        page.add(tabs.component());
        return ContentStyle.page(null, page, null);
    }

    private static JComponent section(String title, JComponent content) {
        JPanel section = new JPanel(new BorderLayout(0, Tokens.XS));
        section.setOpaque(false);
        section.add(new SectionHeader(title), BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        section.setBorder(BorderFactory.createEmptyBorder(0, 0, Tokens.M, 0));
        return section;
    }
}
```

- [ ] **Step 2: Create the evidence test**

`src/test/java/tomato/gui/kit/KitGalleryEvidenceTest.java`:
```java
package tomato.gui.kit;

import java.awt.*;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.Themes;
import ui.VisualEvidence;
import static org.junit.Assert.*;

public class KitGalleryEvidenceTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-kit");

    @Before public void noMotion() { Motion.systemOverride = false; }
    @After public void restore() { Motion.systemOverride = null; }

    @Test public void galleryRendersInBothVariantsAtWideAndCompactSizes() throws Exception {
        for (Themes.Variant variant : Themes.Variant.values())
            for (int[] size : new int[][]{{1240, 800}, {680, 520}})
                for (int font : new int[]{13, 18}) {
                    JComponent[] gallery = new JComponent[1];
                    SwingUtilities.invokeAndWait(() -> {
                        Themes.install(new Themes.Choice(variant, false));
                        gallery[0] = KitGallery.build();
                        evidence.show(gallery[0], "Kit gallery", size[0], size[1], font);
                    });
                    evidence.settle();
                    SwingUtilities.invokeAndWait(() -> {
                        evidence.capture("kit-" + variant.name().toLowerCase(Locale.ROOT) + "-" + size[0] + "-" + font);
                        assertIconButtonsAreNamed(gallery[0]);
                    });
                }
    }

    /** Kit buttons only: Swing's own scroll-bar and combo arrow buttons are unnamed by design. */
    private static void assertIconButtonsAreNamed(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof KitButton) {
                KitButton button = (KitButton) child;
                String name = button.getAccessibleContext().getAccessibleName();
                if (button.getText() == null || button.getText().isEmpty())
                    assertFalse("Icon-only button needs an accessible name: " + button.getName(), name == null || name.isEmpty());
            }
            if (child instanceof Container) assertIconButtonsAreNamed((Container) child);
        }
    }
}
```

- [ ] **Step 3: Run the gallery and review screenshots**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test --tests "tomato.gui.kit.KitGalleryEvidenceTest"`
Expected: PASS. Open the 8 images in `build/p1a/ui-test/screenshots/redesign-kit/` and check: no clipped text at 680 × 520 and font 18; chips, tiles and cards readable in both variants; the unknown tile shows "—" in muted text; the estimate tile shows "≈ 1,240".

- [ ] **Step 4: Full build**

Run: `./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir build/p1a-cache -PrealmSharkBuildDir=build/p1a test shadowJar`
Expected: `BUILD SUCCESSFUL`; every `build/p1a/test-results/test/*.xml` has `failures="0" errors="0"`.

- [ ] **Step 5: Commit and open the PR**

```bash
git add src/test/java/tomato/gui/kit/KitGallery.java src/test/java/tomato/gui/kit/KitGalleryEvidenceTest.java
git commit -m "Add the kit gallery evidence test

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin feat/redesign-p1a-kit
gh pr create --title "Redesign P1a: design kit" --body "Implements P1a of docs/superpowers/specs/2026-09-26-ui-ux-redesign-design.md: the tomato.gui.kit package (tokens, DisplayValue, column kinds, buttons, chips, cards, tiles, collapsibles, filter bar, customizable tabs, game widgets, Simple/Analyst mode and capped motion) with unit tests and a gallery evidence test. No existing page changes yet; P1b adopts the kit.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
Request an independent review of the final PR head before merging.

---

## Self-review notes

- Spec §5.1 tokens → Task 1; §5.2 type → Task 1; §5.3 components → Tasks 5, 6, 7, 9; §5.4 icons/sprites → Task 3; §5.5 column kinds → Task 2; §5.6 filter bar → Task 7; §5.7 DisplayValue → Task 1; §5.8 motion → Task 4; §3.2 mode → Task 4; §4.4 tabs → Task 8; §10 accessibility → names/descriptions asserted in Tasks 5–10.
- Adoption in the shell and pages (FilterBar in `ArchiveWorkspace`, `CustomizableTabs` in modules, `KitTables` on existing tables, the mode switch in the header) is P1b.
