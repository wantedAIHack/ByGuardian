package nextvisit.api.therapist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "therapist_links")
public class TherapistLink {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TherapistLink() {}

    public TherapistLink(UUID caseId, String tokenHash, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.tokenHash = tokenHash;
        this.revoked = false;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public String getTokenHash() { return tokenHash; }
    public boolean isRevoked() { return revoked; }
    public Instant getCreatedAt() { return createdAt; }
    public void revoke() { this.revoked = true; }
}
