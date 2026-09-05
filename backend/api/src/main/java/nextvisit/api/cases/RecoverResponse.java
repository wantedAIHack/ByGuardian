package nextvisit.api.cases;

import java.util.UUID;

public record RecoverResponse(String guardianToken, UUID caseId) {}
