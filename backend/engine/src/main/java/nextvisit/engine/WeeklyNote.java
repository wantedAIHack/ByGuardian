package nextvisit.engine;

/** 한 주의 자유 기록 태그. 기록은 했지만 태그가 없으면 tag = null. 기록이 없는 주는 목록에 없다. */
public record WeeklyNote(int week, TimeTag tag) {}
