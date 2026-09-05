package nextvisit.engine;

/** 한 항목·한 축의 한 주 관찰값. 결측 주는 목록에 아예 없다. README §5 주차 스냅샷. */
public record Observation(int week, int value, Source source) {

    public Observation {
        if (week < 1) {
            throw new IllegalArgumentException("week must be >= 1, was " + week);
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
    }
}
