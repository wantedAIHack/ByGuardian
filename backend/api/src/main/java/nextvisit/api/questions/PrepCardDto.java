package nextvisit.api.questions;

import java.time.LocalDate;
import java.util.List;
import nextvisit.api.progress.TrajectoryDto;

/** README §9 화면 4. */
public record PrepCardDto(int week, LocalDate nextVisitDate, List<Question> questions, List<String> extraQuestions,
                          String emptyMessage, List<String> therapistGlance) {
    public record Question(int rank, String type, String sentence, String source, Evidence evidence) {}
    public record Evidence(List<EvidenceItem> items, SignalEvidence signal) {}
    public record EvidenceItem(String code, String label, String axis, String axisLabel, List<TrajectoryDto.Point> values) {}
    public record SignalEvidence(String action, String actionLabel, String kind, String kindLabel, List<Integer> weeks, int window) {}
}
