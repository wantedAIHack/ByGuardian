package nextvisit.engine;

import java.util.List;

/** @param sentences selected 와 같은 순서의 템플릿 문장 */
public record PipelineResult(
    List<ItemVerdicts> verdicts,
    List<SignalPattern> patterns,
    List<Detection> detections,
    List<Detection> selected,
    List<String> sentences
) {}
