package nextvisit.api.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

    /**
     * 한 주의 보호자 원문을 줄로 만든다. 같은 글은 한 줄만 남긴다 — 전체 재확인 주는 항목마다
     * 메모칸이 있어 한 문장이 자유 기록까지 합쳐 여섯 번 들어올 수 있고, 그 반복을 그대로
     * 넘기면 LLM 정리가 세 번 다 실패해 보호자가 템플릿 질문만 보게 된다. 줄을 지우는 대신
     * 남은 줄이 시간대(자유 기록)와 항목(첫 항목 메모)을 함께 갖게 해 근거를 잃지 않는다.
     */
    public static List<SynthesisInput.NoteLine> noteLines(int week, SnapshotBody body) {
        List<SynthesisInput.NoteLine> lines = new ArrayList<>();
        if (body == null) {
            return lines;
        }
        Map<String, Integer> indexByText = new HashMap<>();
        if (body.freeNote() != null && hasText(body.freeNote().text())) {
            String text = body.freeNote().text().strip();
            indexByText.put(text, lines.size());
            lines.add(new SynthesisInput.NoteLine(week, timeTagLabel(body.freeNote().timeTag()), null, text));
        }
        if (body.items() != null) {
            for (Map.Entry<String, SnapshotBody.ItemValues> entry : body.items().entrySet()) {
                SnapshotBody.ItemValues values = entry.getValue();
                if (values == null || !hasText(values.note())) {
                    continue;
                }
                String text = values.note().strip();
                Integer at = indexByText.get(text);
                if (at == null) {
                    indexByText.put(text, lines.size());
                    lines.add(new SynthesisInput.NoteLine(week, null, itemLabel(entry.getKey()), text));
                    continue;
                }
                SynthesisInput.NoteLine kept = lines.get(at);
                if (kept.itemLabel() == null) {
                    lines.set(at, new SynthesisInput.NoteLine(
                        week, kept.timeTagLabel(), itemLabel(entry.getKey()), text));
                }
            }
        }
        return lines;
    }

    public static boolean hasNote(SnapshotBody body) {
        return !noteLines(0, body).isEmpty();
    }

    /**
     * 원문이 얇으면 LLM을 부르지 않는다 — 2026-09-20 운영 실측에서 한 주 원문이 10자 안팎일 때
     * ("혼자 하심.", "조금 나음.") qwen3:4b가 남은 자리를 지어낸 말로 메우고 비문을 냈다
     * ("조금 나는 이유가 무엇일까요?"). 26자 이상 표본 6개는 모두 멀쩡했다. 그 사이 구간은
     * 들쭉날쭉해 경계를 20자에 둔다. 부르지 않으면 캐시는 READY로 남고 보호자는 규칙 엔진
     * 템플릿을 받는다 — 템플릿은 언제나 근거에 붙어 있어 지어낸 문장보다 낫다.
     */
    static final int MIN_NOTE_CHARS = 20;

    /** 한 주의 원문 글자 수. 같은 글이 여러 항목에 들어갔으면 한 번만 센다. */
    public static int noteChars(SnapshotBody body) {
        return noteLines(0, body).stream().mapToInt(line -> line.text().length()).sum();
    }

    public static int noteChars(Collection<SnapshotBody> bodies) {
        return bodies.stream().mapToInt(SynthesisInputAssembler::noteChars).sum();
    }

    public static boolean worthSynthesizing(Collection<SnapshotBody> bodies) {
        return noteChars(bodies) >= MIN_NOTE_CHARS;
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
