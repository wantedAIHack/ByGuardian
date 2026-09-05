package nextvisit.api.cases;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cases")
public class CaseEntity {

    @Id
    private UUID id;

    @Column(name = "observation_set", nullable = false)
    private String observationSet;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Diagnosis diagnosis;

    @Enumerated(EnumType.STRING)
    @Column(name = "paretic_side", nullable = false)
    private PareticSide pareticSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "verbal_difficulty", nullable = false)
    private VerbalDifficulty verbalDifficulty;

    @Column(name = "next_visit_date")
    private LocalDate nextVisitDate;

    @Column(name = "recovery_code_hash", nullable = false)
    private String recoveryCodeHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_questions", nullable = false)
    private String extraQuestions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CaseEntity() {}

    public CaseEntity(String observationSet, LocalDate startDate, Diagnosis diagnosis, PareticSide pareticSide,
                      VerbalDifficulty verbalDifficulty, LocalDate nextVisitDate, String recoveryCodeHash, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.observationSet = observationSet;
        this.startDate = startDate;
        this.diagnosis = diagnosis;
        this.pareticSide = pareticSide;
        this.verbalDifficulty = verbalDifficulty;
        this.nextVisitDate = nextVisitDate;
        this.recoveryCodeHash = recoveryCodeHash;
        this.extraQuestions = "[]";
        this.createdAt = createdAt;
    }

    /** README §5: hand 축은 마비 쪽을 알 때만. */
    public boolean handEnabled() {
        return pareticSide == PareticSide.LEFT || pareticSide == PareticSide.RIGHT;
    }

    /** README §5: 비언어 신호는 말로 표현이 가끔/거의 어려울 때만. */
    public boolean signalsEnabled() {
        return verbalDifficulty == VerbalDifficulty.SOMETIMES || verbalDifficulty == VerbalDifficulty.OFTEN;
    }

    public UUID getId() { return id; }
    public String getObservationSet() { return observationSet; }
    public LocalDate getStartDate() { return startDate; }
    public Diagnosis getDiagnosis() { return diagnosis; }
    public PareticSide getPareticSide() { return pareticSide; }
    public VerbalDifficulty getVerbalDifficulty() { return verbalDifficulty; }
    public LocalDate getNextVisitDate() { return nextVisitDate; }
    public String getRecoveryCodeHash() { return recoveryCodeHash; }
    public String getExtraQuestions() { return extraQuestions; }
    public Instant getCreatedAt() { return createdAt; }

    public void setNextVisitDate(LocalDate nextVisitDate) { this.nextVisitDate = nextVisitDate; }
    public void setExtraQuestions(String extraQuestions) { this.extraQuestions = extraQuestions; }
}
