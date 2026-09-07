package nextvisit.api.llm;

import java.util.UUID;

public record QuestionGenerationRequested(UUID caseId, UUID generationId) {}
