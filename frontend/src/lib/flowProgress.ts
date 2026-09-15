import { FIRST_BASELINE_STEP, RECOVERY_STEP } from './onboarding';

export function onboardingProgress(step: number, itemCount: number): {
  label: string; current?: number; total?: number;
} {
  if (step === 0) return { label: '시작 안내' };
  if (step < FIRST_BASELINE_STEP) return { label: '기본 정보' };
  if (step < RECOVERY_STEP && step - FIRST_BASELINE_STEP < itemCount) {
    return { label: '생활 관찰', current: step - FIRST_BASELINE_STEP + 1, total: itemCount };
  }
  return { label: '마무리' };
}
