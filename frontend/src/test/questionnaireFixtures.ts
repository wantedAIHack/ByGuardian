import type { Questionnaire } from '../lib/types';
import { catalogFixture } from './fixtures';

export const feedingQuestionnaire: Questionnaire = {
  version: 2,
  questions: [
    { code: 'route', label: '어떤 방법으로 영양을 섭취하셨나요?', kind: 'single', required: true,
      options: [{ code: 'oral', label: '입으로 드셨어요' }, { code: 'tube', label: '영양관을 사용했어요' },
        { code: 'both', label: '두 방법을 함께 사용했어요' }, { code: 'unknown', label: '직접 보지 못했어요' }] },
    { code: 'assistance', label: '음식을 드실 때 어떤 도움이 필요했나요?', kind: 'single', required: true,
      when: { question: 'route', anyOf: ['oral', 'both'] },
      options: [{ code: '4', label: '혼자 드셨어요' }, { code: '2', label: '일부 동작을 도왔어요' },
        { code: 'unknown', label: '직접 보지 못했어요' }] },
    { code: 'parts', label: '어떤 동작을 도왔나요?', kind: 'multiple', required: false,
      when: { question: 'assistance', anyOf: ['2'] },
      options: [{ code: 'scoop', label: '음식 뜨기' }, { code: 'mouth', label: '입으로 가져가기' },
        { code: 'unknown', label: '잘 모르겠어요' }], exclusive: ['unknown'] },
  ],
};
export const revisedCatalog = {
  ...catalogFixture,
  items: catalogFixture.items.map((i) => i.code === 'feeding' ? { ...i, questionnaire: feedingQuestionnaire } : i),
};
