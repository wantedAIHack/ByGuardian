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

    @Column(name = "demo_mode", nullable = false)
    private boolean demoMode;

    @Column(name = "demo_today")
    private LocalDate demoToday;

    @Column(name = "recovery_code_hash", nullable = false)
    private String recoveryCodeHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_questions", nullable = false)
    private String extraQuestions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirmed_questions")
    private String confirmedQuestions;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CaseEntity() {}

    public CaseEntity(String observationSet, LocalDate startDate, Diagnosis diagnosis, PareticSide pareticSide,
                      VerbalDifficulty verbalDifficulty, LocalDate nextVisitDate, String recoveryCodeHash, Instant createdAt) {
        this(observationSet, startDate, diagnosis, pareticSide, verbalDifficulty, nextVisitDate, recoveryCodeHash,
            createdAt, false, null);
    }

    private CaseEntity(String observationSet, LocalDate startDate, Diagnosis diagnosis, PareticSide pareticSide,
                       VerbalDifficulty verbalDifficulty, LocalDate nextVisitDate, String recoveryCodeHash,
                       Instant createdAt, boolean demoMode, LocalDate demoToday) {
        this.id = UUID.randomUUID();
        this.observationSet = observationSet;
        this.startDate = startDate;
        this.diagnosis = diagnosis;
        this.pareticSide = pareticSide;
        this.verbalDifficulty = verbalDifficulty;
        this.nextVisitDate = nextVisitDate;
        this.demoMode = demoMode;
        this.demoToday = demoToday;
        this.recoveryCodeHash = recoveryCodeHash;
        this.extraQuestions = "[]";
        this.createdAt = createdAt;
    }

    public static CaseEntity demo(String observationSet, LocalDate startDate, Diagnosis diagnosis,
                                  PareticSide pareticSide, VerbalDifficulty verbalDifficulty,
                                  LocalDate nextVisitDate, String recoveryCodeHash, Instant createdAt,
                                  LocalDate demoToday) {
        if (demoToday == null) throw new IllegalArgumentException("demoToday is required");
        return new CaseEntity(observationSet, startDate, diagnosis, pareticSide, verbalDifficulty, nextVisitDate,
            recoveryCodeHash, createdAt, true, demoToday);
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
    public boolean isDemoMode() { return demoMode; }
    public LocalDate getDemoToday() { return demoToday; }
    public String getRecoveryCodeHash() { return recoveryCodeHash; }
    public String getExtraQuestions() { return extraQuestions; }
    public String getConfirmedQuestions() { return confirmedQuestions; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setNextVisitDate(LocalDate nextVisitDate) { this.nextVisitDate = nextVisitDate; }
    public void setExtraQuestions(String extraQuestions) { this.extraQuestions = extraQuestions; }
    public void setRecoveryCodeHash(String recoveryCodeHash) { this.recoveryCodeHash = recoveryCodeHash; }

    public void advanceDemoWeek() {
        if (!demoMode || demoToday == null) throw new IllegalStateException("not a demo case");
        demoToday = demoToday.plusDays(7);
    }

    public void confirmQuestions(String json, Instant at) {
        this.confirmedQuestions = json;
        this.confirmedAt = at;
    }

    public void clearConfirmedQuestions() {
        this.confirmedQuestions = null;
        this.confirmedAt = null;
    }
}
