package nextvisit.api.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.TimeTag;
import org.springframework.stereotype.Component;

/**
 * 설계 3.1: 관찰 변화와 주차별 보호자 원문을 LLM 입력으로 조립한다.
 * 원문이 상한을 넘으면 최근 주부터 주 단위로 넣고, 넣지 못한 주는 근거로 댈 수 없다.
 * 나중에 원문을 먼저 구조화하는 단계(설계 접근법 B)를 이 앞에 끼울 수 있게 순수하게 둔다.
 */
@Component
public class SynthesisInputAssembler {

    private final LlmProperties properties;

    public SynthesisInputAssembler(LlmProperties properties) {
        this.properties = properties;
    }

    public SynthesisInput assemble(List<QuestionCacheBody.Q> templates, SortedMap<Integer, SnapshotBody> bodiesByWeek) {
        List<SynthesisInput.Detection> detections = templates.stream()
            .map(q -> new SynthesisInput.Detection("D" + q.rank(), q.rank(), q.templateSentence()))
            .toList();

        int budget = properties.synthesisMaxNoteChars();
        List<SynthesisInput.NoteLine> picked = new ArrayList<>();
        List<Integer> newestFirst = new ArrayList<>(bodiesByWeek.keySet());
        newestFirst.sort(Comparator.reverseOrder());
        for (int week : newestFirst) {
            List<SynthesisInput.NoteLine> lines = noteLines(week, bodiesByWeek.get(week));
            int size = lines.stream().mapToInt(line -> line.text().length()).sum();
            if (size == 0) {
                continue;
            }
            if (size > budget) {
                break;
            }
            budget -= size;
            picked.addAll(lines);
        }
        picked.sort(Comparator.comparingInt(SynthesisInput.NoteLine::week));
        return new SynthesisInput(detections, picked);
    }

    public static List<SynthesisInput.NoteLine> noteLines(int week, SnapshotBody body) {
        List<SynthesisInput.NoteLine> lines = new ArrayList<>();
        if (body == null) {
            return lines;
        }
        if (body.freeNote() != null && hasText(body.freeNote().text())) {
            lines.add(new SynthesisInput.NoteLine(week, timeTagLabel(body.freeNote().timeTag()), null,
                body.freeNote().text().strip()));
        }
        if (body.items() != null) {
            for (Map.Entry<String, SnapshotBody.ItemValues> entry : body.items().entrySet()) {
                SnapshotBody.ItemValues values = entry.getValue();
                if (values != null && hasText(values.note())) {
                    lines.add(new SynthesisInput.NoteLine(week, null, itemLabel(entry.getKey()), values.note().strip()));
                }
            }
        }
        return lines;
    }

    public static boolean hasNote(SnapshotBody body) {
        return !noteLines(0, body).isEmpty();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String timeTagLabel(String tag) {
        if (tag == null) {
            return null;
        }
        try {
            return TimeTag.valueOf(tag).phrase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String itemLabel(String code) {
        try {
            return ObservationSet.STROKE.item(code).label();
        } catch (RuntimeException e) {
            return code;
        }
    }
}
