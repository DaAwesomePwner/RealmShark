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
