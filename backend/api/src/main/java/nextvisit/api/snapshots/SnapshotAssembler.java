package nextvisit.api.snapshots;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.common.ValidationException;
import nextvisit.engine.Axis;
import nextvisit.engine.Item;
import nextvisit.engine.Labels;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalKind;
import nextvisit.engine.TimeTag;
import org.springframework.stereotype.Component;

/**
 * 요청 → 스냅샷 본문. 설계 3절의 규칙:
 * 변경 항목은 적용되는 모든 축을 CONFIRMED로, 나머지는 직전 기록에서 CARRIED로 복사.
 * 기준선(previous == null)은 8항목 전부 있어야 한다.
 */
@Component
public class SnapshotAssembler {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String CARRIED = "CARRIED";
    public static final int FREE_NOTE_MAX = 500;

    public SnapshotBody build(CaseEntity kase, Map<String, ItemInput> confirmed, SnapshotBody previous,
                              Map<String, List<String>> painSignal, Integer sleep, FreeNoteInput freeNote) {
        ObservationSet set = ObservationSet.STROKE;
        Map<String, ItemInput> given = confirmed == null ? Map.of() : confirmed;
        for (String code : given.keySet()) {
            set.item(code); // 모르는 코드면 IllegalArgumentException → 400
        }

        Map<String, SnapshotBody.ItemValues> items = new LinkedHashMap<>();
        for (Item item : set.items()) {
            ItemInput in = given.get(item.code());
            if (in != null) {
                items.put(item.code(), confirmedValues(kase, item, in));
            } else if (previous != null && previous.items().get(item.code()) != null) {
                items.put(item.code(), carried(previous.items().get(item.code())));
            } else {
                throw new ValidationException(item.code() + ": 기준선에는 8항목이 전부 필요합니다");
            }
        }

        Map<String, List<String>> signals = validateSignals(kase, painSignal);
        if (sleep != null && (sleep < 0 || sleep > 2)) {
            throw new ValidationException("sleep은 0..2입니다");
        }
        SnapshotBody.FreeNote note = validateNote(freeNote);
        return new SnapshotBody(items, signals, sleep, note);
    }

    private SnapshotBody.ItemValues confirmedValues(CaseEntity kase, Item item, ItemInput in) {
        Map<Axis, SnapshotBody.Val> vals = new EnumMap<>(Axis.class);
        for (Axis axis : Axis.values()) {
            Integer v = in.axis(axis);
            boolean applies = item.axes().contains(axis) && (axis != Axis.HAND || kase.handEnabled());
            if (!applies) {
                if (v != null) {
                    throw new ValidationException(item.code() + ": " + axis + "는 이 항목에 적용되지 않습니다");
                }
                continue;
            }
            if (v == null) {
                throw new ValidationException(item.code() + ": " + axis + " 값이 필요합니다");
            }
            if (v < 0 || v > Labels.maxValue(axis)) {
                throw new ValidationException(item.code() + ": " + axis + " 값 " + v + "은 0.." + Labels.maxValue(axis) + " 범위 밖입니다");
            }
            vals.put(axis, new SnapshotBody.Val(v, CONFIRMED));
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        return new SnapshotBody.ItemValues(vals.get(Axis.LEVEL), vals.get(Axis.AID), vals.get(Axis.CONSISTENCY), vals.get(Axis.HAND), note);
    }

    private static SnapshotBody.ItemValues carried(SnapshotBody.ItemValues prev) {
        return new SnapshotBody.ItemValues(carry(prev.level()), carry(prev.aid()), carry(prev.consistency()), carry(prev.hand()), null);
    }

    private static SnapshotBody.Val carry(SnapshotBody.Val v) {
        return v == null ? null : new SnapshotBody.Val(v.value(), CARRIED);
    }

    private static Map<String, List<String>> validateSignals(CaseEntity kase, Map<String, List<String>> painSignal) {
        if (!kase.signalsEnabled()) {
            return null;
        }
        if (painSignal == null) {
            throw new ValidationException("painSignal은 매주 필수입니다. 신호가 없으면 {}");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : painSignal.entrySet()) {
            SignalAction action;
            try {
                action = SignalAction.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new ValidationException("painSignal: 모르는 동작 " + e.getKey());
            }
            List<String> kinds = new ArrayList<>();
            for (String k : e.getValue() == null ? List.<String>of() : e.getValue()) {
                try {
                    kinds.add(SignalKind.valueOf(k).name());
                } catch (IllegalArgumentException ex) {
                    throw new ValidationException("painSignal: 모르는 신호 " + k);
                }
            }
            if (!kinds.isEmpty()) {
                out.put(action.name(), kinds);
            }
        }
        return out;
    }

    private static SnapshotBody.FreeNote validateNote(FreeNoteInput in) {
        if (in == null || in.text() == null || in.text().isBlank()) {
            return null;
        }
        String text = in.text().trim();
        if (text.length() > FREE_NOTE_MAX) {
            throw new ValidationException("freeNote.text는 " + FREE_NOTE_MAX + "자까지입니다");
        }
        String tag = null;
        if (in.timeTag() != null && !in.timeTag().isBlank()) {
            try {
                tag = TimeTag.valueOf(in.timeTag()).name();
            } catch (IllegalArgumentException ex) {
                throw new ValidationException("freeNote.timeTag: 모르는 값 " + in.timeTag());
            }
        }
        return new SnapshotBody.FreeNote(text, tag);
    }
}
