package nextvisit.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guardians")
public class Guardian {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private String relation;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Guardian() {}

    public Guardian(UUID caseId, String relation, String tokenHash, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.relation = relation;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public String getRelation() { return relation; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
}
