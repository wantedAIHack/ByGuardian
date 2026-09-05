package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 제품 이름의 유래가 된 "침묵" 그 자체: 8개 항목 전부 6주 동안 값이 똑같으면
 * 감지도 질문도 하나도 나오지 않아야 한다. 파이프라인 수준 테스트가 지금까지 없었다.
 */
class PipelineSilentCaseTest {

    @Test
    void allItemsFlatProducesNoDetectionsAndNoSentences() {
        Map<String, Map<Axis, List<Observation>>> series = new LinkedHashMap<>();
        for (String code : ObservationSet.STROKE.codes()) {
            List<Observation> flat = new ArrayList<>();
            for (int week = 1; week <= 6; week++) {
                flat.add(new Observation(week, 2, Source.CONFIRMED));
            }
            series.put(code, Map.of(Axis.LEVEL, List.copyOf(flat)));
        }

        CaseInput input = new CaseInput(ObservationSet.STROKE, series, List.of(), List.of());
        PipelineResult r = Pipeline.run(input);

        assertTrue(r.detections().isEmpty(), r.detections().toString());
        assertTrue(r.sentences().isEmpty(), r.sentences().toString());
    }
}
