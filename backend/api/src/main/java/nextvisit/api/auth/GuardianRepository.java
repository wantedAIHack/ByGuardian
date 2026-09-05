package nextvisit.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardianRepository extends JpaRepository<Guardian, UUID> {
    Optional<Guardian> findByTokenHash(String tokenHash);
    List<Guardian> findByCaseId(UUID caseId);
}
