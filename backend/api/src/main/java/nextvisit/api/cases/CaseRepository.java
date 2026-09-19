package nextvisit.api.cases;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseRepository extends JpaRepository<CaseEntity, UUID> {
    Optional<CaseEntity> findByRecoveryCodeHash(String recoveryCodeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CaseEntity c where c.id = :caseId")
    Optional<CaseEntity> findByIdForQuestionRefresh(@Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CaseEntity c where c.id = :caseId")
    Optional<CaseEntity> findByIdForUpdate(@Param("caseId") UUID caseId);
}
