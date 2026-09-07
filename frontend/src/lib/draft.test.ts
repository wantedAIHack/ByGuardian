import { beforeEach, describe, expect, it } from 'vitest';
import { ONBOARDING_DRAFT, clearDraft, loadDraft, saveDraft, weeklyDraftKey } from './draft';

beforeEach(() => localStorage.clear());

describe('draft', () => {
  it('저장한 것을 그대로 돌려준다', () => {
    saveDraft(ONBOARDING_DRAFT, { step: 4, relation: '딸' });
    expect(loadDraft<{ step: number; relation: string }>(ONBOARDING_DRAFT))
      .toEqual({ step: 4, relation: '딸' });
  });

  it('없으면 null이다', () => {
    expect(loadDraft(ONBOARDING_DRAFT)).toBeNull();
  });

  it('망가진 값은 null로 돌려주고 지운다', () => {
    localStorage.setItem(ONBOARDING_DRAFT, '{망가짐');
    expect(loadDraft(ONBOARDING_DRAFT)).toBeNull();
    expect(localStorage.getItem(ONBOARDING_DRAFT)).toBeNull();
  });

  it('지우면 사라진다', () => {
    saveDraft(ONBOARDING_DRAFT, { step: 1 });
    clearDraft(ONBOARDING_DRAFT);
    expect(loadDraft(ONBOARDING_DRAFT)).toBeNull();
  });

  it('주간 초안 키가 케이스와 주차로 갈린다', () => {
    expect(weeklyDraftKey('abc', 7)).toBe('draft:abc:7');
    expect(weeklyDraftKey('abc', 8)).not.toBe(weeklyDraftKey('abc', 7));
  });
});
