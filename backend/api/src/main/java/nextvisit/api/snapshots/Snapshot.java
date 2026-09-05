package nextvisit.api.snapshots;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 주차 스냅샷 한 행. body는 SnapshotBody의 JSON. README §4 층 1. */
@Entity
@Table(name = "snapshots")
public class Snapshot {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private int week;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SnapshotKind kind;

    @Column(name = "no_change", nullable = false)
    private boolean noChange;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String body;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected Snapshot() {}

    public Snapshot(UUID caseId, int week, SnapshotKind kind, boolean noChange, UUID authorId, String body, Instant recordedAt) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.week = week;
        this.kind = kind;
        this.noChange = noChange;
        this.authorId = authorId;
        this.body = body;
        this.recordedAt = recordedAt;
    }

    /** 같은 주 덮어쓰기. id와 case·week는 유지한다. */
    public void overwrite(SnapshotKind kind, boolean noChange, UUID authorId, String body, Instant recordedAt) {
        this.kind = kind;
        this.noChange = noChange;
        this.authorId = authorId;
        this.body = body;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public int getWeek() { return week; }
    public SnapshotKind getKind() { return kind; }
    public boolean isNoChange() { return noChange; }
    public UUID getAuthorId() { return authorId; }
    public String getBody() { return body; }
    public Instant getRecordedAt() { return recordedAt; }
}
