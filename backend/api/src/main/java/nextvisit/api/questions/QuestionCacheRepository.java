package nextvisit.api.questions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface QuestionCacheRepository extends JpaRepository<QuestionCache, UUID> {
    Optional<QuestionCache> findByCaseId(UUID caseId);

    boolean existsByCaseIdAndGenerationIdAndStatus(
        UUID caseId, UUID generationId, QuestionCacheStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update QuestionCache c
           set c.status = :done, c.body = :body, c.generatedAt = :generatedAt
         where c.caseId = :caseId
           and c.generationId = :generationId
           and c.status = :pending
        """)
    int completeGenerationIfPending(
        @Param("caseId") UUID caseId,
        @Param("generationId") UUID generationId,
        @Param("pending") QuestionCacheStatus pending,
        @Param("done") QuestionCacheStatus done,
        @Param("body") String body,
        @Param("generatedAt") Instant generatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update QuestionCache c
           set c.status = :failed, c.generatedAt = :generatedAt
         where c.caseId = :caseId
           and c.generationId = :generationId
           and c.status = :pending
        """)
    int failGenerationIfPending(
        @Param("caseId") UUID caseId,
        @Param("generationId") UUID generationId,
        @Param("pending") QuestionCacheStatus pending,
        @Param("failed") QuestionCacheStatus failed,
        @Param("generatedAt") Instant generatedAt);
}
