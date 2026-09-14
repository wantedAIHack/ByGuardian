import type { Axis, Catalog, ItemInput, Me, Trajectory, WeeklyRecordRequest } from './types';

export interface RecordState {
  index: number;
  noChange: boolean | null;
  selected: string[];
  items: Record<string, ItemInput>;
  painSignal: Record<string, string[]> | null;
  sleep: number | null;
  freeNote: { text: string; timeTag: string | null };
}

export function initialRecord(): RecordState {
  return {
    index: 0,
    noChange: null,
    selected: [],
    items: {},
    painSignal: null,
    sleep: null,
    freeNote: { text: '', timeTag: null },
  };
}

export type Step =
  | { kind: 'ask' }
  | { kind: 'recheck-intro' }
  | { kind: 'pick' }
  | { kind: 'item'; code: string }
  | { kind: 'signal' }
  | { kind: 'sleep' }
  | { kind: 'note' };

/**
 * 화면 차례는 답에 따라 자란다. 상태에서 매번 다시 계산하고 index로 가리킨다.
 * 통증 신호와 수면은 "달라진 것 없음"을 눌러도 매주 묻는다. 값이 있는 관찰이기 때문이다.
 */
export function steps(me: Me, c: Catalog, s: RecordState): Step[] {
  const out: Step[] = [];
  if (me.fullRecheck) {
    out.push({ kind: 'recheck-intro' });
    for (const item of c.items) out.push({ kind: 'item', code: item.code });
  } else {
    out.push({ kind: 'ask' });
    if (s.noChange === false) {
      out.push({ kind: 'pick' });
      for (const code of s.selected) out.push({ kind: 'item', code });
    }
  }
  if (me.signalsEnabled) out.push({ kind: 'signal' });
  out.push({ kind: 'sleep' });
  out.push({ kind: 'note' });
  return out;
}

const EMPTY: ItemInput = { level: null, aid: null, consistency: null, hand: null, note: null };

export function axesFor(me: Me, c: Catalog, code: string): Axis[] {
  const item = c.items.find((i) => i.code === code);
  if (!item) return [];
  return item.axes.filter((a) => (a === 'HAND' ? me.handEnabled : true));
}

export function itemComplete(me: Me, c: Catalog, s: RecordState, code: string): boolean {
  const v = s.items[code] ?? EMPTY;
  return axesFor(me, c, code).every((a) => {
    if (a === 'LEVEL') return v.level !== null;
    if (a === 'AID') return v.aid !== null;
    if (a === 'CONSISTENCY') return v.consistency !== null;
    return v.hand !== null;
  });
}

export function toWeeklyRequest(me: Me, c: Catalog, s: RecordState): WeeklyRecordRequest {
  // 전체 재확인 주는 8항목 전부다. 서버가 noChange=true나 부족한 항목을 400으로 막는다.
  const codes = me.fullRecheck ? c.items.map((i) => i.code) : s.selected;
  const changedItems: Record<string, ItemInput> = {};
  for (const code of codes) {
    const v = s.items[code] ?? EMPTY;
    changedItems[code] = {
      level: v.level,
      aid: v.aid,
      consistency: v.consistency,
      hand: me.handEnabled ? v.hand : null,
      note: v.note && v.note.trim() ? v.note.trim() : null,
    };
  }
  const text = s.freeNote.text.trim();
  return {
    noChange: me.fullRecheck ? false : s.noChange === true,
    changedItems,
    // 신호가 켜진 케이스에 null을 보내면 서버가 400을 낸다. "없었어요"는 {}다.
    painSignal: me.signalsEnabled ? (s.painSignal ?? {}) : null,
    sleep: s.sleep,
    freeNote: text ? { text, timeTag: s.freeNote.timeTag } : null,
  };
}

/** 전체 재확인 주에 지난달 답을 보여주기 위한 조회. README §9. */
export function previousValue(
  trajectories: Trajectory[], code: string, axis: string, beforeWeek: number,
) {
  const series = trajectories.find((t) => t.code === code)?.axes.find((a) => a.axis === axis);
  if (!series) return undefined;
  const earlier = series.values.filter((p) => p.week < beforeWeek);
  return earlier[earlier.length - 1];
}
