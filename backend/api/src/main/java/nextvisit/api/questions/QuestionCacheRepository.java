package nextvisit.api.questions;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionCacheRepository extends JpaRepository<QuestionCache, UUID> {
    Optional<QuestionCache> findByCaseId(UUID caseId);
}
