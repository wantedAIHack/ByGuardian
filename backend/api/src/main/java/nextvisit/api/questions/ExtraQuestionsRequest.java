package nextvisit.api.questions;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ExtraQuestionsRequest(@NotNull List<String> questions) {}
