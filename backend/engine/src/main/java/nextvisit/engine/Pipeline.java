package nextvisit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** api가 부르는 유일한 진입점. 순수 함수. */
public final class Pipeline {

    private Pipeline() {}

    public static PipelineResult run(CaseInput in) {
        List<ItemVerdicts> verdicts = new ArrayList<>();
        for (var e : in.series().entrySet()) {
            Map<Axis, Verdict> byAxis = new EnumMap<>(Axis.class);
            e.getValue().forEach((axis, trajectory) -> byAxis.put(axis, SilenceGate.judge(trajectory)));
            verdicts.add(new ItemVerdicts(e.getKey(), byAxis));
        }
        verdicts.sort(Comparator.comparingInt(v -> in.set().order(v.code())));

        List<SignalPattern> patterns = SignalDetector.judge(in.signals());
        List<Detection> detections = CrossDetector.detect(in.set(), verdicts, patterns, in.notes());
        List<Detection> selected = QuestionSelector.select(detections, in.set());

        Map<String, ItemVerdicts> byCode = verdicts.stream()
            .collect(Collectors.toMap(ItemVerdicts::code, Function.identity()));
        List<String> sentences = selected.stream()
            .map(d -> Templates.render(d, in.set(), byCode))
            .toList();

        return new PipelineResult(List.copyOf(verdicts), patterns, detections, selected, sentences);
    }
}
