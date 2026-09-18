package nextvisit.api.therapist;

import java.util.List;
import nextvisit.api.progress.TrajectoryDto;

/** README §9 화면 5 / Layer 3. 판정 필드가 없다. */
public record TherapistSummaryDto(
    String generatedAt,
    List<Integer> weeks,
    List<TrajectoryDto> items,
    List<Signal> signals,
    List<Sleep> sleep,
    boolean signalsEnabled,
    List<FreeNote> freeNotes,
    List<String> questions,
    List<String> extraQuestions,
    Density density,
    List<AuthorChange> authorChanges,
    List<QuestionDetail> questionDetails,
    String disclaimer
) {
    public record Signal(String action, String actionLabel, String kind, String kindLabel, List<Integer> weeks) {}
    public record Sleep(int week, int value, String label) {}
    public record FreeNote(int week, String text, String timeTag, String timeTagLabel) {}
    public record Density(int totalWeeks, int recordedWeeks, int confirmedWeeks, List<String> authors) {}
    public record AuthorChange(int week, String from, String to) {}
    public record QuestionDetail(String sentence, String origin, List<Integer> noteWeeks) {}
}
