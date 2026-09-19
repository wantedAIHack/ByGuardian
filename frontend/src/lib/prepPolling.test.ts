import { describe, expect, it } from 'vitest';
import { nextPrepPoll, PREP_POLL_LIMIT_MS, PREP_POLL_MS } from './prepPolling';

describe('nextPrepPoll', () => {
  it('정리 중이면 5초마다 다시 부르고 시작 시각을 기억한다', () => {
    expect(nextPrepPoll('PENDING', null, 1000)).toEqual({ interval: PREP_POLL_MS, pendingSince: 1000 });
    expect(nextPrepPoll('PENDING', 1000, 1000 + 60_000)).toEqual({ interval: PREP_POLL_MS, pendingSince: 1000 });
  });

  it('5분이 지나면 멈춘다', () => {
    expect(nextPrepPoll('PENDING', 0, PREP_POLL_LIMIT_MS)).toEqual({ interval: false, pendingSince: 0 });
  });

  it('정리 중이 아니면 멈추고 시작 시각을 지운다', () => {
    for (const status of ['DONE', 'FAILED', 'TEMPLATE_ONLY', undefined] as const) {
      expect(nextPrepPoll(status, 1000, 2000)).toEqual({ interval: false, pendingSince: null });
    }
  });
});
