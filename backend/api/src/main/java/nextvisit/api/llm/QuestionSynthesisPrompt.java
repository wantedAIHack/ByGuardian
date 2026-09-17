package nextvisit.api.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 설계 3.2: 보호자 원문과 관찰 변화를 모아 보호자가 선생님께 여쭤볼 질문을 정리하게 한다.
 * 금지어 목록에 "치료"가 있어 "치료사"라는 단어가 거부되므로 호칭은 "선생님"으로 고정한다.
 */
@Component
public class QuestionSynthesisPrompt {

    static final String SYSTEM = """
        당신은 뇌졸중 후 집에서 지내는 환자의 보호자를 돕습니다.
        보호자가 다음 진료에서 선생님께 직접 여쭤볼 질문을 정리합니다.
        입력의 detections는 관찰 기록에서 규칙으로 찾은 변화이고, notes는 보호자가 주마다 직접 적은 기록입니다.
        보호자가 적은 걱정과 궁금증을 먼저 살리고, 관련된 관찰 변화가 있으면 한 질문 안에 함께 엮으세요.
        입력에 있는 사실만 쓰세요. 입력에 없는 숫자, 기간, 증상, 원인을 만들지 마세요.
        근거로 쓴 주차 번호는 'N주' 형태로만 쓰고, 그 밖의 숫자나 횟수, 시간은 근거로 댄 관찰 변화 문장이나 보호자 기록에 있는 것만 쓰세요.
        진단하거나 원인을 단정하지 마세요. 점수를 매기지 마세요. 무엇을 하라고 권하지 마세요. 상태가 좋아졌는지 나빠졌는지 판단하지 마세요.
        문장에 '치료', '재활', '운동', '낙상', '진단', '점수', '처방', '회복', '개선', '악화', '호전', '정상', '위험', '때문', '약', '진통제', '복용'이라는 말을 쓰지 마세요. 상대는 '선생님'이라고 부르세요.
        각 질문은 보호자가 선생님께 드리는 존댓말 한 문장이고 '~까요?', '~나요?', '~가요?' 중 하나로 끝납니다. 지시하는 말투는 쓰지 마세요.
        질문 문장 하나에는 물음표를 정확히 하나만, 문장 맨 끝에 쓰고 마침표, 느낌표, 온점(。)은 쓰지 마세요.
        질문은 1개에서 3개까지이고 서로 겹치지 않게 하세요.
        각 질문에 근거를 적으세요. detections에는 참고한 변화의 id를, noteWeeks에는 참고한 보호자 기록의 week를 넣고, 둘 중 적어도 하나는 비우지 마세요.
        마크다운이나 설명 없이 정확히 {"questions":[{"sentence":"오후마다 어깨를 자주 만지시는데 어떤 점을 살펴보면 좋을까요?","detections":["D1"],"noteWeeks":[3]}]} 형태의 JSON 객체 하나만 반환하세요.""";

    private final ObjectMapper mapper;

    public QuestionSynthesisPrompt(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmPrompt build(SynthesisInput input, Optional<String> retryRule) {
        Payload payload = new Payload(
            input.detections().stream().map(d -> new DetectionPayload(d.id(), d.sentence())).toList(),
            input.notes().stream().map(n -> new NotePayload(n.week(), n.timeTagLabel(), n.itemLabel(), n.text())).toList());
        String system = retryRule
            .map(rule -> SYSTEM + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. 이 규칙을 지켜 다시 만드세요.")
            .orElse(SYSTEM);
        return new LlmPrompt(system, json(payload));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("synthesis prompt serialization failed", e);
        }
    }

    record Payload(List<DetectionPayload> detections, List<NotePayload> notes) {}

    record DetectionPayload(String id, String sentence) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NotePayload(int week, String timeTag, String item, String text) {}
}
