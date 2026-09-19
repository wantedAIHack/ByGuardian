package nextvisit.api.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 설계 3.1: LLM 정리 한 번의 입력. detections는 규칙 엔진 템플릿, notes는 주차별 보호자 원문. */
public record SynthesisInput(List<Detection> detections, List<NoteLine> notes) {

    public SynthesisInput {
        detections = List.copyOf(detections);
        notes = List.copyOf(notes);
    }

    public record Detection(String id, int rank, String sentence) {}

    /** itemLabel이 null이면 그 주의 자유 기록, 아니면 해당 항목의 메모다. */
    public record NoteLine(int week, String timeTagLabel, String itemLabel, String text) {}

    public Set<String> detectionIds() {
        Set<String> ids = new LinkedHashSet<>();
        detections.forEach(d -> ids.add(d.id()));
        return ids;
    }

    public Set<Integer> noteWeeks() {
        Set<Integer> weeks = new LinkedHashSet<>();
        notes.forEach(n -> weeks.add(n.week()));
        return weeks;
    }

    public boolean hasNotes() {
        return !notes.isEmpty();
    }
}
