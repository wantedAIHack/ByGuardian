package nextvisit.api.cases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import nextvisit.api.snapshots.FreeNoteInput;
import nextvisit.api.snapshots.ItemInput;

public record OnboardingRequest(
    @NotBlank String relation,
    @NotNull Diagnosis diagnosis,
    @NotNull PareticSide pareticSide,
    @NotNull VerbalDifficulty verbalDifficulty,
    LocalDate nextVisitDate,
    @NotNull @Valid Baseline baseline
) {
    public record Baseline(
        @NotNull Map<String, ItemInput> items,
        Map<String, List<String>> painSignal,
        Integer sleep,
        FreeNoteInput freeNote
    ) {}
}
