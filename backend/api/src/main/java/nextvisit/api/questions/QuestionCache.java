package nextvisit.api.questions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 케이스당 최신 질문 하나. body는 QuestionCacheBody의 JSON. 설계 2.3. */
@Entity
@Table(name = "question_cache")
public class QuestionCache {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private int week;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String body;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected QuestionCache() {}

    public QuestionCache(UUID caseId, int week, String status, String body, Instant generatedAt) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.week = week;
        this.status = status;
        this.body = body;
        this.generatedAt = generatedAt;
    }

    public void update(int week, String status, String body, Instant generatedAt) {
        this.week = week;
        this.status = status;
        this.body = body;
        this.generatedAt = generatedAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public int getWeek() { return week; }
    public String getStatus() { return status; }
    public String getBody() { return body; }
    public Instant getGeneratedAt() { return generatedAt; }
}
