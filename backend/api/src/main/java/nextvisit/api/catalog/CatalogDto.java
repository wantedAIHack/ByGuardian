package nextvisit.api.catalog;

import java.util.List;
import java.util.Map;

/** 설계 5절 GET /catalog. 프론트가 라벨 표를 따로 들고 있지 않도록. */
public record CatalogDto(String set, List<ItemDto> items, Map<String, List<ValueDto>> axes,
                         List<CodeLabel> signalActions, List<CodeLabel> signalKinds, List<CodeLabel> timeTags,
                         List<CodeLabel> sleepLevels, List<CodeLabel> axisLabels,
                         List<CodeLabel> axisQuestions) {
    public record ItemDto(String code, String label, String phrase, String group, List<String> axes) {}
    public record ValueDto(int value, String label) {}
    public record CodeLabel(String code, String label) {}
}
