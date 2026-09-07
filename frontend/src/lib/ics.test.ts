import { describe, expect, it } from 'vitest';
import { weeklyReminderIcs } from './ics';

const ics = weeklyReminderIcs({ startDate: '2026-09-06', appName: '집에서 본 것' });

describe('weeklyReminderIcs', () => {
  it('캘린더 파일의 뼈대를 갖춘다', () => {
    expect(ics.startsWith('BEGIN:VCALENDAR\r\n')).toBe(true);
    expect(ics.trimEnd().endsWith('END:VCALENDAR')).toBe(true);
    expect(ics).toContain('VERSION:2.0');
  });

  it('줄을 CRLF로 끊는다', () => {
    // RFC 5545. LF만 쓰면 일부 캘린더가 통째로 거부한다.
    expect(ics).toContain('\r\n');
    expect(ics.replace(/\r\n/g, '')).not.toContain('\n');
  });

  it('시작일부터 매주 반복한다', () => {
    expect(ics).toContain('DTSTART;VALUE=DATE:20260906');
    expect(ics).toContain('RRULE:FREQ=WEEKLY');
  });

  it('앱 이름을 제목에 넣는다', () => {
    expect(ics).toContain('집에서 본 것');
  });

  it('모든 줄이 75옥텟 이하다', () => {
    // 한글은 글자당 3바이트라 이름이 길어지면 쉽게 넘는다. 넘으면 접어야 한다.
    const enc = new TextEncoder();
    for (const line of ics.split('\r\n')) {
      expect(enc.encode(line).length).toBeLessThanOrEqual(75);
    }
  });

  it('긴 이름도 접어서 75옥텟을 지킨다', () => {
    const long = weeklyReminderIcs({ startDate: '2026-09-06', appName: '가'.repeat(60) });
    const enc = new TextEncoder();
    for (const line of long.split('\r\n')) {
      expect(enc.encode(line).length).toBeLessThanOrEqual(75);
    }
    // 접힌 줄은 공백으로 시작한다
    expect(long).toContain('\r\n ');
  });
});
