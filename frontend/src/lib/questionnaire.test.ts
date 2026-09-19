import { describe, it, expect } from 'vitest';
import { feedingQuestionnaire, revisedCatalog } from '../test/questionnaireFixtures';
import { me } from '../test/fixtures';
import { initialRecord, itemComplete, steps, toWeeklyRequest } from './record';
import { initialState, canAdvance, toOnboardingRequest } from './onboarding';
import { sanitizeAnswers, selectAnswer, questionnaireComplete, questionnaireInput } from './questionnaire';

const empty = { level: null, aid: null, consistency: null, hand: null, note: null };

describe('질문별 관찰 응답', () => {
  it('입으로 먹는 경우 도움 질문까지 답해야 하고 영양관만 사용하면 묻지 않는다', () => {
    expect(questionnaireComplete(feedingQuestionnaire, {})).toBe(false);
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['oral'] })).toBe(false);
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['tube'] })).toBe(true);
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['unknown'] })).toBe(true);
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['oral'], assistance: ['unknown'] })).toBe(true);
  });
  it('부모 답변이 바뀌면 여러 단계 아래 숨겨진 답도 제거한다', () => {
    expect(selectAnswer(feedingQuestionnaire,
      { route: ['oral'], assistance: ['2'], parts: ['scoop'] }, 'route', 'tube'))
      .toEqual({ route: ['tube'] });
  });
  it('복수 선택에서 모름과 실제 동작을 함께 남기지 않는다', () => {
    const a = { route: ['oral'], assistance: ['2'], parts: ['scoop', 'mouth'] };
    expect(selectAnswer(feedingQuestionnaire, a, 'parts', 'unknown').parts).toEqual(['unknown']);
    expect(selectAnswer(feedingQuestionnaire, { ...a, parts: ['unknown'] }, 'parts', 'scoop').parts).toEqual(['scoop']);
    expect(selectAnswer(feedingQuestionnaire, a, 'parts', 'scoop').parts).toEqual(['mouth']);
  });
  it('알 수 없는 코드와 잘못된 단일·배타 응답으로 다음 단계로 갈 수 없다', () => {
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['oral', 'tube'] })).toBe(false);
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['invalid'] })).toBe(false);
    expect(questionnaireComplete(feedingQuestionnaire, { route: ['oral'], assistance: ['2'], parts: ['unknown', 'scoop'] })).toBe(false);
    expect(sanitizeAnswers(feedingQuestionnaire, { route: ['tube'], injected: ['text'] })).toEqual({ route: ['tube'] });
  });
  it('과거 숫자 축을 새 답변으로 위장하지 않고 메모와 버전을 전송한다', () => {
    const input = questionnaireInput(feedingQuestionnaire, {
      level: 3, aid: null, consistency: 2, hand: 1, note: '  반찬만 도왔어요  ',
      questionnaireVersion: 2, answers: { route: ['tube'], assistance: ['2'] },
    });
    expect(input).toEqual({ ...empty, note: '반찬만 도왔어요', questionnaireVersion: 2, answers: { route: ['tube'] } });
  });
  it('기준선과 주간 기록에 새 답변이 빠지지 않는다', () => {
    const value = { ...empty, questionnaireVersion: 2, answers: { route: ['tube'] } };
    const state = { ...initialState(), step: 14, items: { feeding: value } };
    expect(canAdvance(revisedCatalog, state)).toBe(true);
    expect(toOnboardingRequest(state, revisedCatalog).baseline.items.feeding).toEqual(value);
    const record = { ...initialRecord(), selected: ['feeding'], noChange: false, items: { feeding: value } };
    expect(itemComplete(me(), revisedCatalog, record, 'feeding')).toBe(true);
    expect(toWeeklyRequest(me(), revisedCatalog, record).changedItems.feeding).toEqual(value);
    expect(itemComplete(me(), revisedCatalog, { ...record, items: { feeding: { ...empty, level: 3, consistency: 2, hand: 1 } } }, 'feeding')).toBe(false);
  });
  it('기존 사용자 문항 전환 때 달라진 것 없음으로 새 기준선을 건너뛰지 않는다', () => {
    const user = me({ questionnaireUpgradeRequired: true });
    const state = { ...initialRecord(), noChange: true, items: {} };
    expect(steps(user, revisedCatalog, state)[0]!.kind).toBe('recheck-intro');
    expect(steps(user, revisedCatalog, state).filter((s) => s.kind === 'item')).toHaveLength(8);
    expect(toWeeklyRequest(user, revisedCatalog, state).noChange).toBe(false);
  });
});

it('문항 변경 전 초안이 뒤쪽 단계에 있어도 새 질문 응답을 건너뛰지 않는다', async () => {
  const { resumeOnboarding } = await import('./onboarding');
  const { resumeRecord } = await import('./record');
  const old = { ...empty, level: 3, consistency: 2, hand: 1 };
  const onboarding = { ...initialState(), step: 14, items: { feeding: old } };
  expect(canAdvance(revisedCatalog, resumeOnboarding(revisedCatalog, onboarding))).toBe(false);
  const record = { ...initialRecord(), index: 4, noChange: false, selected: ['feeding'], items: { feeding: old } };
  expect(resumeRecord(me(), revisedCatalog, record).index).toBe(2);
  expect(resumeRecord(me({ questionnaireUpgradeRequired: true }), revisedCatalog,
    { ...record, noChange: true, selected: [] }).index).toBe(0);
});
