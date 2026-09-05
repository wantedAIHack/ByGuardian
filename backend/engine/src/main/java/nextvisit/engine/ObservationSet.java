package nextvisit.engine;

import java.util.EnumSet;
import java.util.List;

/** 관찰 세트. MVP는 STROKE 하나. README §5. */
public record ObservationSet(String id, List<Item> items) {

    public ObservationSet {
        items = List.copyOf(items);
    }

    public static final ObservationSet STROKE = new ObservationSet("stroke", List.of(
        new Item("transfer",   "침대·의자에서 옮겨 앉기", "옮겨 앉기",     "mobility", EnumSet.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY)),
        new Item("ambulation", "집 안에서 걷기",          "집 안에서 걷기", "mobility", EnumSet.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY)),
        new Item("stairs",     "문턱·계단",               "문턱·계단",      "mobility", EnumSet.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY)),
        new Item("toilet",     "화장실 이용",             "화장실 이용",    "selfcare", EnumSet.of(Axis.LEVEL, Axis.CONSISTENCY)),
        new Item("dressing",   "옷 입기",                 "옷 입기",        "selfcare", EnumSet.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND)),
        new Item("grooming",   "세수·양치",               "세수·양치",      "selfcare", EnumSet.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND)),
        new Item("bathing",    "목욕",                    "목욕",           "selfcare", EnumSet.of(Axis.LEVEL, Axis.CONSISTENCY)),
        new Item("feeding",    "식사",                    "식사",           "selfcare", EnumSet.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND))
    ));

    public Item item(String code) {
        for (Item i : items) {
            if (i.code().equals(code)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown item code: " + code);
    }

    /** 항목 표 순서. 질문 선택의 마지막 tie-break. README §7 끝. */
    public int order(String code) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).code().equals(code)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown item code: " + code);
    }

    public List<String> codes() {
        return items.stream().map(Item::code).toList();
    }
}
