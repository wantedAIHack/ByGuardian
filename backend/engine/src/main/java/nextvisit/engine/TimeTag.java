package nextvisit.engine;

/** 자유 기록 시간대 태그. README §5. */
public enum TimeTag {
    MORNING("오전"),
    AFTERNOON("오후"),
    EVENING("저녁"),
    ANY("상관없음");

    private final String phrase;

    TimeTag(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
