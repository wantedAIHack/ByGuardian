package nextvisit.api.cases;

import java.util.UUID;

public record OnboardingResponse(UUID caseId, String guardianToken, String recoveryCode, int week) {}
