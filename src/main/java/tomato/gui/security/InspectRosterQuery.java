package tomato.gui.security;

import java.util.*;

/** Display predicates only. Bulk copy/export applies its independent requirements policy. */
public final class InspectRosterQuery {
    public enum Guild { ANY, EXACT, NONE, UNKNOWN }
    public enum Mode { ANY, YES, NO, UNKNOWN }
    public final String text, guildName;
    public final Integer classId;
    public final Guild guild;
    public final Mode seasonal, crucible;
    public final RequirementResult.Verdict verdict;
    public final boolean knownRange, unknownMaxed;
    public final int minimum, maximum;
    public InspectRosterQuery(String text, Integer classId, Guild guild, String guildName, Mode seasonal, Mode crucible,
                              RequirementResult.Verdict verdict, boolean knownRange, boolean unknownMaxed, int min, int max) {
        this.text = text.trim().toLowerCase(Locale.ROOT); this.classId = classId; this.guild = guild; this.guildName = guildName;
        this.seasonal = seasonal; this.crucible = crucible; this.verdict = verdict; this.knownRange = knownRange; this.unknownMaxed = unknownMaxed; minimum = min; maximum = max;
    }
    public boolean matches(Player player, RequirementResult result, Integer maxed, String className) {
        if (classId != null && classId != player.playerEntity.objectType || verdict != null && verdict != result.verdict) return false;
        String actualGuild = player.playerEntity.getStatGuild();
        if (guild == Guild.UNKNOWN && actualGuild != null || guild == Guild.NONE && !"".equals(actualGuild)
            || guild == Guild.EXACT && (actualGuild == null || !actualGuild.equalsIgnoreCase(guildName))) return false;
        if (!mode(seasonal, Player.seasonal(player.playerEntity)) || !mode(crucible, Player.crucible(player.playerEntity))) return false;
        if (unknownMaxed && maxed != null || knownRange && (maxed == null || maxed < minimum || maxed > maximum)) return false;
        return (Objects.toString(player.playerEntity.name(), "") + " " + Objects.toString(actualGuild, "") + " " + Objects.toString(className, ""))
            .toLowerCase(Locale.ROOT).contains(text);
    }
    private static boolean mode(Mode mode, Boolean actual) {
        return mode == Mode.ANY || mode == Mode.UNKNOWN && actual == null || mode == Mode.YES && Boolean.TRUE.equals(actual) || mode == Mode.NO && Boolean.FALSE.equals(actual);
    }
}
