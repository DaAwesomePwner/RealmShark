package tomato.gui.activity;

import tomato.gui.modern.DisplayFormat;

/** Display-only units; saved visits and DPS windows retain their original millisecond timestamps. */
public enum RunDurationUnit {
    MINUTES("Minutes", 60000.0), SECONDS("Seconds", 1000.0);
    private final String label;
    private final double divisor;
    RunDurationUnit(String label, double divisor) { this.label = label; this.divisor = divisor; }
    public double value(long millis) { return Math.max(0, millis) / divisor; }
    public String format(double value) { return DisplayFormat.formatNumber(value, 0, 1); }
    public String column() { return "Observed " + label.toLowerCase(java.util.Locale.ROOT); }
    @Override public String toString() { return label; }
}
