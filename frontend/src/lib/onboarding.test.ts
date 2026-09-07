import { describe, expect, it } from 'vitest';
import {
  FIRST_BASELINE_STEP, axesForStep, canAdvance, handEnabledFor, initialState,
  itemForStep, resolvedRelation, signalsEnabledFor, toOnboardingRequest,
} from './onboarding';
import { catalogFixture as c } from '../test/fixtures';
import type { OnboardingState } from './onboarding';

function filled(): OnboardingState {
  const s = initialState();
  s.relation = '딸';
  s.diagnosis = 'STROKE';
  s.pareticSide = 'LEFT';
  s.verbalDifficulty = 'NONE';
  s.nextVisitDate = '2026-09-20';
  for (const item of c.items) {
    s.items[item.code] = {
      level: 2,
      aid: item.axes.includes('AID') ? 2 : null,
      consistency: item.axes.includes('CONSISTENCY') ? 1 : null,
      hand: item.axes.includes('HAND') ? 1 : null,
      note: null,
    };
  }
  return s;
}

describe('onboarding state', () => {
  it('기타를 고르면 직접 입력한 값이 관계가 된다', () => {
    const s = initialState();
    s.relation = '기타';
    s.relationOther = '손녀';
    expect(resolvedRelation(s)).toBe('손녀');

    s.relation = '아들';
    expect(resolvedRelation(s)).toBe('아들');
  });

  it('비언어 신호는 표현이 어려울 때만 켜진다', () => {
    // 서버의 CaseEntity.signalsEnabled()와 같은 규칙이어야 한다
    expect(signalsEnabledFor('NONE')).toBe(false);
    expect(signalsEnabledFor('SOMETIMES')).toBe(true);
    expect(signalsEnabledFor('OFTEN')).toBe(true);
  });

  it('hand 축은 마비 쪽을 알 때만 켜진다', () => {
    // 서버의 CaseEntity.handEnabled()와 같은 규칙이어야 한다
    expect(handEnabledFor('LEFT')).toBe(true);
    expect(handEnabledFor('RIGHT')).toBe(true);
    expect(handEnabledFor('NONE')).toBe(false);
    expect(handEnabledFor('UNKNOWN')).toBe(false);
  });

  it('7단계부터 항목이 카탈로그 순서로 하나씩 나온다', () => {
    expect(itemForStep(c, FIRST_BASELINE_STEP)?.code).toBe('transfer');
    expect(itemForStep(c, FIRST_BASELINE_STEP + 7)?.code).toBe('feeding');
    expect(itemForStep(c, FIRST_BASELINE_STEP + 8)).toBeUndefined();
    expect(itemForStep(c, 6)).toBeUndefined();
  });

  it('마비 쪽을 모르면 hand 축을 묻지 않는다', () => {
    const s = initialState();
    const dressing = c.items.find((i) => i.code === 'dressing')!;

    s.pareticSide = 'LEFT';
    expect(axesForStep(c, s, dressing)).toEqual(['LEVEL', 'CONSISTENCY', 'HAND']);

    s.pareticSide = 'UNKNOWN';
    expect(axesForStep(c, s, dressing)).toEqual(['LEVEL', 'CONSISTENCY']);
  });

  it('답하기 전에는 다음으로 못 간다', () => {
    const s = initialState();
    s.step = 1;
    expect(canAdvance(c, s)).toBe(false);
    s.relation = '딸';
    expect(canAdvance(c, s)).toBe(true);
  });

  it('기타를 고르고 비워두면 다음으로 못 간다', () => {
    const s = initialState();
    s.step = 1;
    s.relation = '기타';
    expect(canAdvance(c, s)).toBe(false);
    s.relationOther = '  ';
    expect(canAdvance(c, s)).toBe(false);
    s.relationOther = '손녀';
    expect(canAdvance(c, s)).toBe(true);
  });

  it('외래일은 건너뛸 수 있다', () => {
    const s = initialState();
    s.step = 5;
    expect(canAdvance(c, s)).toBe(true);
  });

  it('적용되는 축을 다 채워야 항목 화면을 넘어간다', () => {
    const s = initialState();
    s.pareticSide = 'LEFT';
    s.step = FIRST_BASELINE_STEP + 4; // dressing은 5번째 항목(0-based 4): LEVEL, CONSISTENCY, HAND
    expect(canAdvance(c, s)).toBe(false);
    s.items['dressing'] = { level: 2, aid: null, consistency: 1, hand: null, note: null };
    expect(canAdvance(c, s)).toBe(false);
    s.items['dressing'] = { level: 2, aid: null, consistency: 1, hand: 1, note: null };
    expect(canAdvance(c, s)).toBe(true);
  });

  it('말이 통하는 케이스는 painSignal을 null로 보낸다', () => {
    const s = filled();
    s.verbalDifficulty = 'NONE';
    const req = toOnboardingRequest(s);
    expect(req.baseline.painSignal).toBeNull();
  });

  it('말이 어려운 케이스는 painSignal을 빈 객체로 보낸다', () => {
    // 신호는 "이번 주에 무엇을 봤는가"라 기준선에서는 묻지 않는다.
    // 그래도 활성이면 null이 아니라 {}여야 한다. null이면 서버가 400을 낸다.
    const s = filled();
    s.verbalDifficulty = 'OFTEN';
    const req = toOnboardingRequest(s);
    expect(req.baseline.painSignal).toEqual({});
  });

  it('수면과 자유 기록은 온보딩에서 묻지 않는다', () => {
    const req = toOnboardingRequest(filled());
    expect(req.baseline.sleep).toBeNull();
    expect(req.baseline.freeNote).toBeNull();
  });

  it('요청이 8항목을 전부 담고 열거값으로 나간다', () => {
    const req = toOnboardingRequest(filled());
    expect(Object.keys(req.baseline.items)).toHaveLength(8);
    expect(req.diagnosis).toBe('STROKE');
    expect(req.pareticSide).toBe('LEFT');
    expect(req.verbalDifficulty).toBe('NONE');
    expect(req.relation).toBe('딸');
    expect(req.nextVisitDate).toBe('2026-09-20');
  });

  it('적용되지 않는 축은 null로 나간다', () => {
    const s = filled();
    s.pareticSide = 'UNKNOWN';
    const req = toOnboardingRequest(s);
    // toilet은 AID도 HAND도 없다
    expect(req.baseline.items['toilet']!.aid).toBeNull();
    expect(req.baseline.items['toilet']!.hand).toBeNull();
    // 마비 쪽을 모르므로 dressing의 hand도 안 나간다
    expect(req.baseline.items['dressing']!.hand).toBeNull();
  });
});
