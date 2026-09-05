package nextvisit.api.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nextvisit.api.progress.AxisLabels;
import nextvisit.api.questions.PrepCardService;
import nextvisit.engine.Axis;
import nextvisit.engine.Item;
import nextvisit.engine.Labels;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalKind;
import nextvisit.engine.TimeTag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    @GetMapping("/catalog")
    public CatalogDto catalog() {
        ObservationSet set = ObservationSet.STROKE;
        List<CatalogDto.ItemDto> items = new ArrayList<>();
        for (Item item : set.items()) {
            List<String> axes = new ArrayList<>();
            for (Axis a : AxisLabels.ORDER) {
                if (item.axes().contains(a)) {
                    axes.add(a.name());
                }
            }
            items.add(new CatalogDto.ItemDto(item.code(), item.label(), item.phrase(), item.group(), axes));
        }
        Map<String, List<CatalogDto.ValueDto>> axes = new LinkedHashMap<>();
        for (Axis a : AxisLabels.ORDER) {
            List<CatalogDto.ValueDto> values = new ArrayList<>();
            for (int v = 0; v <= Labels.maxValue(a); v++) {
                values.add(new CatalogDto.ValueDto(v, Labels.of(a, v)));
            }
            axes.put(a.name(), values);
        }
        List<CatalogDto.CodeLabel> actions = new ArrayList<>();
        for (SignalAction a : SignalAction.values()) {
            actions.add(new CatalogDto.CodeLabel(a.name(), a.phrase()));
        }
        List<CatalogDto.CodeLabel> kinds = new ArrayList<>();
        for (SignalKind k : SignalKind.values()) {
            kinds.add(new CatalogDto.CodeLabel(k.name(), PrepCardService.kindLabel(k)));
        }
        List<CatalogDto.CodeLabel> tags = new ArrayList<>();
        for (TimeTag t : TimeTag.values()) {
            tags.add(new CatalogDto.CodeLabel(t.name(), t.phrase()));
        }
        return new CatalogDto(set.id(), items, axes, actions, kinds, tags);
    }
}
