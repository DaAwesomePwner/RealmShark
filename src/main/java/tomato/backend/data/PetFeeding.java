package tomato.backend.data;

/** Local feeding estimate, with missing inputs separate from captured zero. */
public final class PetFeeding {
    private PetFeeding() {}
    private static final double[] MULTIPLIERS = {1, .65, .3};
    public static double multiplier(int ability) { return MULTIPLIERS[ability]; }
    public static final class Estimate {
        public final String reason;
        public final Long nextItems, maxItems, nextFame, maxFame;
        public final boolean fullyFed, locked;
        private Estimate(String reason, Long nextItems, Long maxItems, Long nextFame, Long maxFame, boolean fullyFed, boolean locked) {
            this.reason = reason; this.nextItems = nextItems; this.maxItems = maxItems;
            this.nextFame = nextFame; this.maxFame = maxFame; this.fullyFed = fullyFed; this.locked = locked;
        }
    }
    public static Estimate estimate(int ability, Integer level, Integer points, Integer cap, int feedPower) {
        if (ability < 0 || ability > 2 || feedPower <= 0) return unavailable("Enter a positive feed power", false);
        if (cap == null) return unavailable("Maximum ability level not captured", false);
        if (cap < 1 || cap > 100) return unavailable("Unsupported maximum ability level: " + cap, false);
        if (ability == 1 && cap < 50 || ability == 2 && cap < 90) return unavailable("Locked ability", true);
        if (level == null || points == null) return unavailable(level == null ? "Ability level not captured" : "Ability points not captured", false);
        if (level < 1 || level > cap || points < 0) return unavailable("Inconsistent captured level or points", false);
        double scaledPoints = points / MULTIPLIERS[ability];
        long max = items(required(ability, cap) - scaledPoints, feedPower);
        long next = items(required(ability, Math.min(level + 1, cap)) - scaledPoints, feedPower);
        // The level itself must agree with a completed estimate; do not silently call inconsistent inputs fully fed.
        if (max == 0 && level < cap) return unavailable("Points and level disagree; refresh pet observations", false);
        if (level.equals(cap)) { max = 0; next = 0; }
        Integer cost = cost(cap);
        return new Estimate(cost == null ? "Fame unavailable: unsupported cost tier " + cap : "Estimated using local feeding formula and cost table",
            next, max, cost == null ? null : next * cost, cost == null ? null : max * cost, level.equals(cap), false);
    }
    private static Estimate unavailable(String reason, boolean locked) { return new Estimate(reason, null, null, null, null, false, locked); }
    private static long items(double missing, int power) { return (long)Math.ceil(Math.max(0, missing) / power); }
    private static double required(int ability, int level) { return Math.floor(20 / MULTIPLIERS[ability] * Math.expm1((level - 1) * Math.log(1.08)) / .08); }
    private static Integer cost(int level) {
        switch (level) { case 30: return 15; case 50: return 50; case 70: return 175; case 90: return 625; case 100: return 1750; default: return null; }
    }
}
