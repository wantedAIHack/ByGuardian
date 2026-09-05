package nextvisit.engine;

public record SignalKey(SignalAction action, SignalKind kind) implements Comparable<SignalKey> {

    @Override
    public int compareTo(SignalKey o) {
        int byAction = Integer.compare(action.ordinal(), o.action.ordinal());
        return byAction != 0 ? byAction : Integer.compare(kind.ordinal(), o.kind.ordinal());
    }
}
