package nextvisit.engine;

/**
 * 한 주의 자유 기록 태그. 기록된 주마다 항목 하나. 그 주에 태그를 고르지 않았으면 tag = null이지만
 * 목록에는 그대로 올라간다. 기록 자체가 없는 주만 목록에서 빠진다.
 * 이 목록의 길이가 곧 "기록된 주 수"이고, {@link CrossDetector} (e)가 생성하는 문장의 "N주 중"의 N이 된다.
 */
public record WeeklyNote(int week, TimeTag tag) {}
