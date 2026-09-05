package nextvisit.api.therapist;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TherapistLinkRepository extends JpaRepository<TherapistLink, UUID> {
    Optional<TherapistLink> findByTokenHash(String tokenHash);
    Optional<TherapistLink> findFirstByCaseIdAndRevokedFalse(UUID caseId);
}
