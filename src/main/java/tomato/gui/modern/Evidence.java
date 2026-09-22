package tomato.gui.modern;

/** Presentation vocabulary; provenance and completeness are independent. */
public final class Evidence {
    private Evidence() { }

    public enum Source {
        CAPTURED("Captured"), ESTIMATED("Estimated"), MANUAL("Manually recorded");
        private final String label;
        Source(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Coverage {
        NOT_CAPTURED("Not captured"), PARTIAL("Partial"), UNLINKED("Unlinked");
        private final String label;
        Coverage(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
