package nextvisit.api.snapshots;

public record WeeklyRecordResponse(int week, SnapshotKind kind, boolean questionsRefreshed) {}
