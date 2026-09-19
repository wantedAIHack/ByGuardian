import type { GenerationStatus } from './types';

export const PREP_POLL_MS = 5_000;
export const PREP_POLL_LIMIT_MS = 5 * 60_000;

/** 정리 중일 때만 짧게 다시 부르고, 5분이 지나면 멈춘다. */
export function nextPrepPoll(
  status: GenerationStatus | undefined,
  pendingSince: number | null,
  now: number,
): { interval: number | false; pendingSince: number | null } {
  if (status !== 'PENDING') return { interval: false, pendingSince: null };
  const since = pendingSince ?? now;
  return {
    interval: now - since < PREP_POLL_LIMIT_MS ? PREP_POLL_MS : false,
    pendingSince: since,
  };
}
