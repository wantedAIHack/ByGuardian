package nextvisit.engine;

/** 비언어 통증 신호 종류. README §5. GUARDING은 편마비 어깨 통증용으로 v3에서 추가. */
public enum SignalKind {
    GRIMACE("얼굴을 찡그리시는 걸"),
    VOCAL("소리를 내시는 걸"),
    GUARDING("팔을 감싸거나 피하시는 걸");

    private final String phrase;

    SignalKind(String phrase) {
        this.phrase = phrase;
    }

    /** 문장 안에 "~을/를 봤습니다" 앞에 들어가는 구. */
    public String phrase() {
        return phrase;
    }
}
