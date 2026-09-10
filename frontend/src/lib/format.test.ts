import { describe, expect, it } from 'vitest';
import { dDayPhrase, formatDate, relativeDay } from './format';

describe('format', () => {
  it('날짜를 월·일로 적는다', () => {
    expect(formatDate('2026-09-08')).toBe('9월 8일');
    expect(formatDate('2026-12-25')).toBe('12월 25일');
  });

  it('가까운 날은 말로 적는다', () => {
    expect(relativeDay('2026-09-06', '2026-09-06')).toBe('오늘');
    expect(relativeDay('2026-09-07', '2026-09-06')).toBe('내일');
    expect(relativeDay('2026-09-08', '2026-09-06')).toBe('모레');
    expect(relativeDay('2026-09-09', '2026-09-06')).toBe('3일 뒤');
  });

  it('먼 날과 지난 날은 말로 적지 않는다', () => {
    expect(relativeDay('2026-09-20', '2026-09-06')).toBeNull();
    expect(relativeDay('2026-09-05', '2026-09-06')).toBeNull();
  });

  it('진료일 문장을 만든다', () => {
    expect(dDayPhrase('2026-09-08', '2026-09-06')).toBe('9월 8일 진료가 있습니다 (모레)');
    expect(dDayPhrase('2026-09-20', '2026-09-06')).toBe('9월 20일 진료가 있습니다');
  });
});
