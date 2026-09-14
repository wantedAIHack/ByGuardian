import type {
  Axis, Catalog, CatalogItem, Diagnosis, ItemInput, OnboardingRequest,
  PareticSide, VerbalDifficulty,
} from './types';

export const FIRST_BASELINE_STEP = 7;
export const BASELINE_COUNT = 8;
export const RECOVERY_STEP = 15;
export const SECOND_GUARDIAN_STEP = 16;
export const LAST_STEP = 17;

export interface OnboardingState {
  step: number;
  relation: string | null;
  relationOther: string;
  diagnosis: Diagnosis | null;
  pareticSide: PareticSide | null;
  verbalDifficulty: VerbalDifficulty | null;
  nextVisitDate: string | null;
  items: Record<string, ItemInput>;
}

export function initialState(): OnboardingState {
  return {
    step: 0,
    relation: null,
    relationOther: '',
    diagnosis: null,
    pareticSide: null,
    verbalDifficulty: null,
    nextVisitDate: null,
    items: {},
  };
}

export function resolvedRelation(s: OnboardingState): string {
  if (s.relation === '기타') return s.relationOther.trim();
  return s.relation ?? '';
}

/** 서버의 CaseEntity.signalsEnabled()와 같은 규칙. 케이스가 아직 없어서 여기서 한 번 더 판단한다. */
export function signalsEnabledFor(v: VerbalDifficulty | null): boolean {
  return v === 'SOMETIMES' || v === 'OFTEN';
}

/** 서버의 CaseEntity.handEnabled()와 같은 규칙. */
export function handEnabledFor(side: PareticSide | null): boolean {
  return side === 'LEFT' || side === 'RIGHT';
}

export function itemForStep(c: Catalog, step: number): CatalogItem | undefined {
  const i = step - FIRST_BASELINE_STEP;
  if (i < 0 || i >= BASELINE_COUNT) return undefined;
  return c.items[i];
}

/**
 * 이 항목에서 실제로 물을 축. 도움 수준이 먼저이고, hand는 마비 쪽을 알 때만.
 * catalog는 axesForStep(catalog, s, item) 서명을 맞추려 받는다 — 축 목록은 item에 이미 있어
 * 본문에서는 안 쓴다. noUnusedParameters라 밑줄을 붙인다.
 */
export function axesForStep(_c: Catalog, s: OnboardingState, item: CatalogItem): Axis[] {
  return item.axes.filter((a) => (a === 'HAND' ? handEnabledFor(s.pareticSide) : true));
}

const EMPTY_ITEM: ItemInput = { level: null, aid: null, consistency: null, hand: null, note: null };

function axisValue(v: ItemInput, axis: Axis): number | null {
  if (axis === 'LEVEL') return v.level;
  if (axis === 'AID') return v.aid;
  if (axis === 'CONSISTENCY') return v.consistency;
  return v.hand;
}

export function canAdvance(c: Catalog, s: OnboardingState): boolean {
  switch (s.step) {
    case 1:
      return resolvedRelation(s).length > 0;
    case 2:
      return s.diagnosis !== null;
    case 3:
      return s.pareticSide !== null;
    case 4:
      return s.verbalDifficulty !== null;
    default: {
      const item = itemForStep(c, s.step);
      if (!item) return true; // 0·5·6·15~17단계는 막지 않는다 (외래일은 건너뛸 수 있다)
      const v = s.items[item.code] ?? EMPTY_ITEM;
      return axesForStep(c, s, item).every((a) => axisValue(v, a) !== null);
    }
  }
}

/**
 * 온보딩이 `POST /cases`로 보내는 몸통.
 * 통증 신호는 기준선에서 묻지 않지만, 활성 케이스에는 null이 아니라 {}를 보내야 한다.
 * 서버가 활성 케이스의 null painSignal에 400을 낸다.
 */
export function toOnboardingRequest(s: OnboardingState): OnboardingRequest {
  const items: Record<string, ItemInput> = {};
  for (const item of Object.keys(s.items)) {
    const v = s.items[item] ?? EMPTY_ITEM;
    items[item] = {
      level: v.level,
      aid: v.aid,
      consistency: v.consistency,
      hand: handEnabledFor(s.pareticSide) ? v.hand : null,
      note: v.note,
    };
  }
  return {
    relation: resolvedRelation(s),
    diagnosis: s.diagnosis!,
    pareticSide: s.pareticSide!,
    verbalDifficulty: s.verbalDifficulty!,
    nextVisitDate: s.nextVisitDate,
    baseline: {
      items,
      painSignal: signalsEnabledFor(s.verbalDifficulty) ? {} : null,
      sleep: null,
      freeNote: null,
    },
  };
}
