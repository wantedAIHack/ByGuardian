package nextvisit.api.progress;

import java.util.List;

/** 설계 7절. 값·라벨·출처만. 판정 없음. 층 2 궤적 탭과 Layer 3가 공유. */
public record TrajectoryDto(String code, String label, boolean changed, List<AxisSeries> axes) {
    public record AxisSeries(String axis, String axisLabel, List<Point> values) {}
    public record Point(int week, int value, String label, String source) {}
}
