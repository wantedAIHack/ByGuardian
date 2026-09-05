package nextvisit.api.cases;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseEntity, UUID> {
    Optional<CaseEntity> findByRecoveryCodeHash(String recoveryCodeHash);
}
