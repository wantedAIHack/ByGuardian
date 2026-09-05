package nextvisit.engine;

/** 신호를 관찰한 동작. 검증된 질문 2의 "일어설 때"가 STANDING. */
public enum SignalAction {
    STANDING("일어설 때"),
    WALKING("걸을 때"),
    TRANSFER("옮겨 앉을 때"),
    DRESSING("옷 입을 때"),
    WASHING("세수할 때"),
    EATING("식사할 때");

    private final String phrase;

    SignalAction(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
