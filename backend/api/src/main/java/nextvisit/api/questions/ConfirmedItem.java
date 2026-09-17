package nextvisit.api.questions;

import java.util.List;

/** 보호자에게 보이는 질문 한 줄. 확정 목록은 이 레코드의 JSON 배열로 cases에 저장된다. */
public record ConfirmedItem(String id, String sentence, String origin, boolean edited,
                            String type, List<String> items, QuestionCacheBody.SignalRef signal,
                            QuestionCacheBody.Basis basis) {

    public static ConfirmedItem caregiver(String id, String sentence) {
        return new ConfirmedItem(id, sentence, QuestionCacheBody.ORIGIN_CAREGIVER, false,
            null, List.of(), null, QuestionCacheBody.Basis.EMPTY);
    }

    public boolean isCaregiver() {
        return QuestionCacheBody.ORIGIN_CAREGIVER.equals(origin);
    }

    public ConfirmedItem withId(String replacementId) {
        return new ConfirmedItem(replacementId, sentence, origin, edited, type, items, signal, basis);
    }
}
