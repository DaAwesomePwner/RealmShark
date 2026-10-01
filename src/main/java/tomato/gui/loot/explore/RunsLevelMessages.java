package tomato.gui.loot.explore;

/** The words a failed read shows: the root cause's message, else its class name. */
final class RunsLevelMessages {
    private RunsLevelMessages() {}
    static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
