package nextvisit.api.cases;

import jakarta.validation.constraints.NotBlank;

public record RecoverRequest(@NotBlank String recoveryCode, @NotBlank String relation) {}
