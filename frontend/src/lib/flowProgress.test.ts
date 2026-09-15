import { expect, it } from 'vitest';
import { onboardingProgress } from './flowProgress';

it('기본 정보, 실제 관찰 항목 수, 마무리의 경계를 구별한다', () => {
  expect(onboardingProgress(0, 8)).toEqual({ label: '시작 안내' });
  expect(onboardingProgress(6, 8)).toEqual({ label: '기본 정보' });
  expect(onboardingProgress(7, 8)).toEqual({ label: '생활 관찰', current: 1, total: 8 });
  expect(onboardingProgress(14, 8)).toEqual({ label: '생활 관찰', current: 8, total: 8 });
  expect(onboardingProgress(15, 8)).toEqual({ label: '마무리' });
  expect(onboardingProgress(7, 5)).toEqual({ label: '생활 관찰', current: 1, total: 5 });
  expect(onboardingProgress(12, 5)).toEqual({ label: '마무리' });
});
