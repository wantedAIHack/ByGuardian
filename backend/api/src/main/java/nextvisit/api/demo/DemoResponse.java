package nextvisit.api.demo;

import java.util.UUID;

public record DemoResponse(UUID caseId, String guardianToken, String recoveryCode, String therapistUrl,
                           String therapistToken) {}
