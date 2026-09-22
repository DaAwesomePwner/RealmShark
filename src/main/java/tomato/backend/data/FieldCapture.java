package tomato.backend.data;

/** Presence and receipt time of one supplied field; zero time means legacy/unknown timing. */
public final class FieldCapture {
    public final long at;
    public final String source;

    public FieldCapture(long at, String source) {
        this.at = at;
        this.source = source;
    }
}
