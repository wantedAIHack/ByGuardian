package nextvisit.api.progress;

import java.util.List;

/** README §9 화면 3 / §6 층별 처리. */
public record ProgressDto(int week, boolean silent, List<Change> changes, List<Transition> transitions, List<Question> questions) {
    public record Change(String item, String label, String axis, String axisLabel, String status, int duration,
                         String from, String to, String message) {}
    public record Transition(String item, String label, String axis, String message) {}
    public record Question(int rank, String type, String sentence, String source) {}
}
