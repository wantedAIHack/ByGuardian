package nextvisit.api.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
        예시 문장의 낱말(어깨, 오후 등)을 베끼지 말고, 입력 notes에 실제로 적힌 말만 쓰세요.
        마크다운이나 설명 없이 정확히 {"questions":[{"sentence":"오후마다 어깨를 자주 만지시는데 어떤 점을 살펴보면 좋을까요?","detections":["D1"],"noteWeeks":[3]}]} 형태의 JSON 객체 하나만 반환하세요.""";

    /**
     * 재시도 프롬프트는 규칙 이름만으로는 쓸모가 없다. qwen3:4b에게 "QUESTION_MARK을
     * 위반했습니다"는 아무 뜻이 없어서, 운영 실측에서 같은 어미("...확인해 주시겠어요?")를
     * 세 번 반복하고 세 번 다 거부됐다(temperature 0.1, seed 0이라 재시도가 사실상 같은
     * 답을 낸다). 규칙마다 무엇을 어떻게 고칠지 한 문장으로 적어 재시도가 수렴하게 한다.
     */
    static final Map<String, String> RETRY_GUIDANCE = Map.ofEntries(
        Map.entry("JSON_OBJECT", "설명이나 마크다운 없이 JSON 객체 하나만 반환하세요."),
        Map.entry("ROOT_FIELDS", "최상위에는 questions 키 하나만 두세요."),
        Map.entry("QUESTIONS_ARRAY", "questions의 값은 배열이어야 합니다."),
        Map.entry("QUESTION_COUNT", "질문은 1개 이상 3개 이하로 넣으세요."),
        Map.entry("QUESTION_FIELDS", "질문마다 sentence, detections, noteWeeks 세 키만 두고,"
            + " detections는 문자열 배열, noteWeeks는 정수 배열이며 같은 값을 두 번 넣지 마세요."),
        Map.entry("SENTENCE_LENGTH", "질문 한 문장은 10자 이상 160자 이하여야 합니다."),
        Map.entry("MARKDOWN", "줄바꿈과 마크다운 기호(*, _, #, `, [, ], |, \\)를 쓰지 마세요."),
        Map.entry("QUESTION_MARK", "질문은 한 문장이고 마침표와 느낌표 없이 물음표 하나로 끝나야 하며,"
            + " 어미는 '~나요?', '~까요?', '~가요?', '~습니까?' 중 하나여야 합니다."
            + " '~주시겠어요?'나 '~어요?' 같은 다른 어미는 쓰지 마세요."),
        Map.entry("FORBIDDEN_WORD", "'개선', '악화', '호전', '회복', '위험', '정상', '재활', '치료',"
            + " '운동', '낙상', '진단', '점수', '처방', '때문', '약', '진통제', '복용'과"
            + " '좋아지다', '나빠지다', '나아지다'를 쓰지 마세요."),
        Map.entry("DIRECTIVE", "무엇을 하라고 시키는 말투를 쓰지 말고 보호자가 여쭤보는 질문으로만 쓰세요."),
        Map.entry("BASIS_EMPTY", "detections와 noteWeeks 중 적어도 하나는 비우지 마세요."),
        Map.entry("UNKNOWN_DETECTION", "detections에는 입력 detections에 있는 id만 넣으세요."),
        Map.entry("UNKNOWN_NOTE_WEEK", "noteWeeks에는 입력 notes에 있는 week만 넣으세요."),
        Map.entry("UNSUPPORTED_NUMBER", "근거로 댄 변화 문장과 보호자 기록에 있는 숫자만 쓰고,"
            + " 주차는 'N주' 형태로만 쓰세요."),
        Map.entry("DUPLICATE", "질문끼리 같은 문장을 쓰지 마세요."));

    private final ObjectMapper mapper;

    public QuestionSynthesisPrompt(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmPrompt build(SynthesisInput input, Optional<String> retryRule) {
        Payload payload = new Payload(
            input.detections().stream().map(d -> new DetectionPayload(d.id(), d.sentence())).toList(),
            input.notes().stream().map(n -> new NotePayload(n.week(), n.timeTagLabel(), n.itemLabel(), n.text())).toList());
        String base = input.detections().isEmpty() ? SYSTEM + NO_DETECTIONS : SYSTEM;
        String system = retryRule.map(rule -> retrySystem(base, rule)).orElse(base);
        return new LlmPrompt(system, json(payload));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("synthesis prompt serialization failed", e);
        }
    }

    /**
     * 규칙 엔진이 그 주에 아무 변화도 찾지 못하면 detections가 빈 배열로 간다. 그런데
     * 위 예시는 늘 {@code "detections":["D1"]}이라, 모델이 그 D1을 그대로 베껴
     * UNKNOWN_DETECTION으로 세 번 다 거부됐다. 비었을 때만 이 줄을 덧붙인다.
     */
    static final String NO_DETECTIONS =
        "\n이번 입력에는 detections가 없습니다. 각 질문의 detections는 반드시 빈 배열([])로 두고,"
            + " 근거는 noteWeeks로만 대세요.";

    private static String retrySystem(String base, String rule) {
        String guidance = RETRY_GUIDANCE.get(rule);
        return base + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. "
            + (guidance == null ? "" : guidance + " ") + "이 규칙을 지켜 다시 만드세요.";
    }

    record Payload(List<DetectionPayload> detections, List<NotePayload> notes) {}

    record DetectionPayload(String id, String sentence) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NotePayload(int week, String timeTag, String item, String text) {}
}
