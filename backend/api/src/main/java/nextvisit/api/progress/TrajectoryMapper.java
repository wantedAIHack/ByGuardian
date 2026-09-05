package nextvisit.api.progress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import nextvisit.api.common.Json;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.engine.Axis;
import nextvisit.engine.Item;
import nextvisit.engine.Labels;
import nextvisit.engine.ObservationSet;
import org.springframework.stereotype.Component;

/** 스냅샷 본문 → 궤적 DTO. 엔진을 거치지 않는다(판정을 붙이면 안 되므로). */
@Component
public class TrajectoryMapper {

    private final Json json;

    public TrajectoryMapper(Json json) {
        this.json = json;
    }

    public List<TrajectoryDto> items(List<Snapshot> snapshots) {
        List<TrajectoryDto> out = new ArrayList<>();
        for (Item item : ObservationSet.STROKE.items()) {
            List<TrajectoryDto.AxisSeries> axes = new ArrayList<>();
            boolean changed = false;
            for (Axis axis : AxisLabels.ORDER) {
                TrajectoryDto.AxisSeries s = series(snapshots, item.code(), axis);
                if (s.values().isEmpty()) {
                    continue;
                }
                axes.add(s);
                Set<Integer> distinct = new HashSet<>();
                s.values().forEach(p -> distinct.add(p.value()));
                if (distinct.size() > 1) {
                    changed = true;
                }
            }
            out.add(new TrajectoryDto(item.code(), item.label(), changed, axes));
        }
        return out;
    }

    public TrajectoryDto.AxisSeries series(List<Snapshot> snapshots, String code, Axis axis) {
        List<TrajectoryDto.Point> points = new ArrayList<>();
        for (Snapshot s : snapshots) {
            SnapshotBody body = json.fromJson(s.getBody(), SnapshotBody.class);
            SnapshotBody.ItemValues iv = body.items().get(code);
            if (iv == null) {
                continue;
            }
            SnapshotBody.Val v = iv.axis(axis);
            if (v != null) {
                points.add(new TrajectoryDto.Point(s.getWeek(), v.value(), Labels.of(axis, v.value()), v.source()));
            }
        }
        return new TrajectoryDto.AxisSeries(axis.name(), AxisLabels.of(axis), points);
    }
}
