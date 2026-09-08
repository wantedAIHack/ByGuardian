package nextvisit.api.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.common.Json;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.engine.Axis;
import nextvisit.engine.CaseInput;
import nextvisit.engine.Item;
import nextvisit.engine.Observation;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.Pipeline;
import nextvisit.engine.PipelineResult;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalKey;
import nextvisit.engine.SignalKind;
import nextvisit.engine.Source;
import nextvisit.engine.TimeTag;
import nextvisit.engine.WeekRecord;
import org.springframework.stereotype.Component;

/** 스냅샷 행 ↔ 엔진 WeekRecord. 판정은 하지 않는다. 설계 6.1. */
@Component
public class EngineBridge {

    private final Json json;

    public EngineBridge(Json json) {
        this.json = json;
    }

    public WeekRecord toWeekRecord(CaseEntity kase, Snapshot s) {
        SnapshotBody body = json.fromJson(s.getBody(), SnapshotBody.class);
        Map<String, Map<Axis, Observation>> values = new LinkedHashMap<>();
        for (var e : body.items().entrySet()) {
            Map<Axis, Observation> byAxis = new EnumMap<>(Axis.class);
            for (Axis axis : Axis.values()) {
                SnapshotBody.Val v = e.getValue().axis(axis);
                if (v != null) {
                    byAxis.put(axis, new Observation(s.getWeek(), v.value(), Source.valueOf(v.source())));
                }
            }
            values.put(e.getKey(), byAxis);
        }
        Set<SignalKey> signals = null;
        if (kase.signalsEnabled()) {
            signals = new HashSet<>();
            if (body.painSignal() != null) {
                for (var e : body.painSignal().entrySet()) {
                    for (String kind : e.getValue()) {
                        signals.add(new SignalKey(SignalAction.valueOf(e.getKey()), SignalKind.valueOf(kind)));
                    }
                }
            }
        }
        TimeTag tag = body.freeNote() == null || body.freeNote().timeTag() == null
            ? null : TimeTag.valueOf(body.freeNote().timeTag());
        return new WeekRecord(s.getWeek(), values, signals, tag);
    }

    public CaseInput toCaseInput(CaseEntity kase, List<Snapshot> snapshots) {
        List<WeekRecord> weeks = new ArrayList<>();
        for (Snapshot s : snapshots) {
            weeks.add(toWeekRecord(kase, s));
        }
        return CaseInput.fromWeeks(ObservationSet.STROKE, weeks);
    }

    public PipelineResult run(CaseEntity kase, List<Snapshot> snapshots) {
        return Pipeline.run(toCaseInput(kase, snapshots));
    }

    /**
     * 역변환. 데모 시드처럼 엔진 쪽에서 만든 주차 기록을 저장 본문으로 바꾼다.
     * sleep은 WeekRecord에 없다(엔진 판정에 관여하지 않는 순수 표시용 관찰이라 CaseInput/
     * WeekRecord가 나르지 않는다) — 호출부가 SnapshotBody 3번째 필드로 직접 채워 넣는다.
     */
    public SnapshotBody toBody(WeekRecord w, Integer sleep, String freeNoteText) {
        Map<String, SnapshotBody.ItemValues> items = new LinkedHashMap<>();
        for (Item item : ObservationSet.STROKE.items()) {
            Map<Axis, Observation> byAxis = w.values().get(item.code());
            if (byAxis == null) {
                throw new IllegalArgumentException("week " + w.week() + " has no values for " + item.code());
            }
            items.put(item.code(), new SnapshotBody.ItemValues(
                val(byAxis.get(Axis.LEVEL)), val(byAxis.get(Axis.AID)), val(byAxis.get(Axis.CONSISTENCY)), val(byAxis.get(Axis.HAND)), null));
        }
        Map<String, List<String>> pain = null;
        if (w.signals() != null) {
            pain = new TreeMap<>();
            for (SignalKey k : w.signals()) {
                pain.computeIfAbsent(k.action().name(), x -> new ArrayList<>()).add(k.kind().name());
            }
            for (List<String> kinds : pain.values()) {
                kinds.sort(null);
            }
        }
        SnapshotBody.FreeNote note = freeNoteText == null ? null
            : new SnapshotBody.FreeNote(freeNoteText, w.noteTag() == null ? null : w.noteTag().name());
        return new SnapshotBody(items, pain, sleep, note);
    }

    private static SnapshotBody.Val val(Observation o) {
        return o == null ? null : new SnapshotBody.Val(o.value(), o.source().name());
    }
}
