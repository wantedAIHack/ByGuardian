package nextvisit.api.progress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import nextvisit.api.common.Json;
import nextvisit.api.catalog.QuestionnaireCatalog;
import java.util.Map;
import java.util.HashMap;
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
        List<SnapshotBody> bodies = parseAll(snapshots);
        List<TrajectoryDto> out = new ArrayList<>();
        for (Item item : ObservationSet.STROKE.items()) {
            List<TrajectoryDto.AxisSeries> axes = new ArrayList<>();
            boolean changed = false;
            for (Axis axis : AxisLabels.ORDER) {
                TrajectoryDto.AxisSeries s = series(snapshots, bodies, item.code(), axis);
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
            List<TrajectoryDto.Observation> observations = new ArrayList<>();
            Map<String, Set<List<String>>> seen = new HashMap<>();
            Integer start = null;
            var questionnaire = QuestionnaireCatalog.forItem(item.code());
            if (questionnaire != null) {
                for (int i = 0; i < snapshots.size(); i++) {
                    var values = bodies.get(i).items().get(item.code());
                    if (!QuestionnaireCatalog.isV2(values)) continue;
                    int week = snapshots.get(i).getWeek();
                    start = start == null ? week : Math.min(start, week);
                    if (values.note() != null && !values.note().isBlank()) {
                        observations.add(new TrajectoryDto.Observation(week, "note", "추가 관찰", List.of(values.note()), "CONFIRMED"));
                    }
                    for (var question : questionnaire.questions()) {
                        List<String> answer = values.answers().get(question.code());
                        if (answer == null || answer.isEmpty()) continue;
                        List<String> labels = question.options().stream().filter(o -> answer.contains(o.code())).map(o -> o.label()).toList();
                        observations.add(new TrajectoryDto.Observation(week, question.code(), question.label(), labels, values.answerSource()));
                        seen.computeIfAbsent(question.code(), key -> new HashSet<>()).add(labels);
                    }
                }
            }
            if (!axes.isEmpty() || start == null) out.add(new TrajectoryDto(item.code(), item.label(), changed, axes));
            if (start != null) out.add(new TrajectoryDto(item.code() + ":v2", item.label(),
                seen.values().stream().anyMatch(values -> values.size() > 1), List.of(), 2, start, observations));
        }
        return out;
    }

    public TrajectoryDto.AxisSeries series(List<Snapshot> snapshots, String code, Axis axis) {
        return series(snapshots, parseAll(snapshots), code, axis);
    }

    private TrajectoryDto.AxisSeries series(List<Snapshot> snapshots, List<SnapshotBody> bodies, String code, Axis axis) {
        List<TrajectoryDto.Point> points = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            Snapshot s = snapshots.get(i);
            SnapshotBody body = bodies.get(i);
            SnapshotBody.ItemValues iv = body.items().get(code);
            if (iv == null || QuestionnaireCatalog.isV2(iv)) {
                continue;
            }
            SnapshotBody.Val v = iv.axis(axis);
            if (v != null) {
                points.add(new TrajectoryDto.Point(s.getWeek(), v.value(), Labels.of(axis, v.value()), v.source()));
            }
        }
        return new TrajectoryDto.AxisSeries(axis.name(), AxisLabels.of(axis), points);
    }

    private List<SnapshotBody> parseAll(List<Snapshot> snapshots) {
        List<SnapshotBody> bodies = new ArrayList<>(snapshots.size());
        for (Snapshot s : snapshots) {
            bodies.add(json.fromJson(s.getBody(), SnapshotBody.class));
        }
        return bodies;
    }
}
