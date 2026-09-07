import { describe, expect, it } from 'vitest';
import { initialRecord, previousValue, steps, toWeeklyRequest } from './record';
import { catalogFixture as c, me } from '../test/fixtures';
import type { Trajectory } from './types';

describe('steps', () => {
  it('보통 주는 달라진 게 있는지부터 묻는다', () => {
    const s = initialRecord();
    expect(steps(me(), c, s).map((x) => x.kind)).toEqual(['ask', 'sleep', 'note']);
  });

  it('"없어요"를 눌러도 수면은 묻는다', () => {
    const s = { ...initialRecord(), noChange: true };
    expect(steps(me(), c, s).map((x) => x.kind)).toEqual(['ask', 'sleep', 'note']);
  });

  it('"네"를 누르면 항목 고르기가 붙는다', () => {
    const s = { ...initialRecord(), noChange: false };
    expect(steps(me(), c, s).map((x) => x.kind)).toEqual(['ask', 'pick', 'sleep', 'note']);
  });

  it('고른 항목만큼 화면이 늘어난다', () => {
    const s = { ...initialRecord(), noChange: false, selected: ['toilet', 'ambulation'] };
    const got = steps(me(), c, s);
    expect(got.map((x) => x.kind)).toEqual(['ask', 'pick', 'item', 'item', 'sleep', 'note']);
    expect(got.filter((x) => x.kind === 'item').map((x: any) => x.code))
      .toEqual(['toilet', 'ambulation']);
  });

  it('말이 어려운 케이스는 통증 신호를 매주 묻는다', () => {
    const s = { ...initialRecord(), noChange: true };
    expect(steps(me({ signalsEnabled: true }), c, s).map((x) => x.kind))
      .toEqual(['ask', 'signal', 'sleep', 'note']);
  });

  it('전체 재확인 주는 묻지 않고 바로 8항목을 순서대로 낸다', () => {
    const s = initialRecord();
    const got = steps(me({ fullRecheck: true, week: 8 }), c, s);
    expect(got[0]!.kind).toBe('recheck-intro');
    expect(got.filter((x) => x.kind === 'item')).toHaveLength(8);
    expect(got.some((x) => x.kind === 'ask')).toBe(false);
    expect(got.some((x) => x.kind === 'pick')).toBe(false);
  });
});

describe('toWeeklyRequest', () => {
  it('"없어요"는 빈 changedItems로 나간다', () => {
    const s = { ...initialRecord(), noChange: true, sleep: 2 };
    const req = toWeeklyRequest(me(), c, s);
    expect(req.noChange).toBe(true);
    expect(req.changedItems).toEqual({});
    expect(req.sleep).toBe(2);
  });

  it('고른 항목만 담는다', () => {
    const s = {
      ...initialRecord(), noChange: false, selected: ['toilet'],
      items: {
        toilet: { level: 3, aid: null, consistency: 2, hand: null, note: '아침에 혼자 다녀오심' },
        dressing: { level: 1, aid: null, consistency: 1, hand: 1, note: null },
      },
    };
    const req = toWeeklyRequest(me(), c, s);
    expect(Object.keys(req.changedItems)).toEqual(['toilet']);
    expect(req.changedItems['toilet']!.note).toBe('아침에 혼자 다녀오심');
  });

  it('신호가 꺼진 케이스는 painSignal을 null로 보낸다', () => {
    const req = toWeeklyRequest(me({ signalsEnabled: false }), c, { ...initialRecord(), noChange: true });
    expect(req.painSignal).toBeNull();
  });

  it('신호가 켜졌는데 아무것도 못 봤으면 빈 객체로 보낸다', () => {
    // null이면 서버가 400을 낸다. "없었어요"는 관찰이지 공백이 아니다.
    const req = toWeeklyRequest(me({ signalsEnabled: true }), c, { ...initialRecord(), noChange: true });
    expect(req.painSignal).toEqual({});
  });

  it('빈 자유 기록은 보내지 않는다', () => {
    const s = { ...initialRecord(), noChange: true, freeNote: { text: '   ', timeTag: 'MORNING' } };
    expect(toWeeklyRequest(me(), c, s).freeNote).toBeNull();
  });

  it('전체 재확인 주는 noChange를 false로 두고 8항목을 전부 보낸다', () => {
    const s = initialRecord();
    for (const item of c.items) {
      s.items[item.code] = {
        level: 2, aid: item.axes.includes('AID') ? 2 : null,
        consistency: item.axes.includes('CONSISTENCY') ? 1 : null,
        hand: item.axes.includes('HAND') ? 1 : null, note: null,
      };
    }
    const req = toWeeklyRequest(me({ fullRecheck: true, week: 8, handEnabled: true }), c, s);
    expect(req.noChange).toBe(false);
    expect(Object.keys(req.changedItems)).toHaveLength(8);
  });
});

describe('previousValue', () => {
  const traj: Trajectory[] = [{
    code: 'toilet', label: '화장실 이용', changed: true,
    axes: [{
      axis: 'LEVEL', axisLabel: '도움 수준',
      values: [
        { week: 4, value: 1, label: '손 잡아드림', source: 'CONFIRMED' },
        { week: 5, value: 2, label: '지켜보면 됨', source: 'CONFIRMED' },
        { week: 8, value: 2, label: '지켜보면 됨', source: 'CARRIED' },
      ],
    }],
  }];

  it('이번 주 이전의 마지막 값을 준다', () => {
    expect(previousValue(traj, 'toilet', 'LEVEL', 8)?.label).toBe('지켜보면 됨');
    expect(previousValue(traj, 'toilet', 'LEVEL', 5)?.label).toBe('손 잡아드림');
  });

  it('없으면 undefined다', () => {
    expect(previousValue(traj, 'toilet', 'AID', 8)).toBeUndefined();
    expect(previousValue(traj, 'nope', 'LEVEL', 8)).toBeUndefined();
    expect(previousValue(traj, 'toilet', 'LEVEL', 4)).toBeUndefined();
  });
});
