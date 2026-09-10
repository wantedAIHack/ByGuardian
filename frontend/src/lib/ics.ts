/**
 * 주간 알림을 캘린더 반복 일정으로 만든다.
 * 브라우저 푸시는 iOS에서 홈 화면 설치를 요구해 대상 사용자 절반이 못 받고,
 * 스토어 앱은 개발자 계정이 없다. 캘린더는 이미 모든 폰에 있다.
 */
export function weeklyReminderIcs(opts: { startDate: string; appName: string }): string {
  const date = opts.startDate.replace(/-/g, '');
  const uid = `${date}-${Math.random().toString(36).slice(2, 10)}@nextvisit`;
  const body = '이번 주 관찰을 남겨주세요.';
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//nextvisit//weekly//KO',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:${uid}`,
    `DTSTAMP:${date}T090000Z`,
    // 주차는 시작일과 같은 요일에 넘어간다. 알림도 그날 울리게 맞춘다.
    `DTSTART;VALUE=DATE:${date}`,
    'RRULE:FREQ=WEEKLY',
    `SUMMARY:${escapeText(opts.appName)} 주간 기록`,
    `DESCRIPTION:${escapeText(body)}`,
    'BEGIN:VALARM',
    'TRIGGER:PT0S',
    'ACTION:DISPLAY',
    `DESCRIPTION:${escapeText(body)}`,
    'END:VALARM',
    'END:VEVENT',
    'END:VCALENDAR',
  ];
  return lines.map(fold).join('\r\n') + '\r\n';
}

/**
 * RFC 5545 §3.3.11. 쉼표·세미콜론·역슬래시·줄바꿈은 뜻이 있는 글자다.
 * 줄바꿈은 \r\n, 단독 \r, 단독 \n 세 가지로 들어올 수 있다 — 셋 다 같은 \n 이스케이프로
 * 모은다. 그대로 두면 날것 CR이 줄 안에 남아 캘린더 파서가 줄 경계로 오인할 수 있다.
 */
function escapeText(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,')
    .replace(/\r\n|\r|\n/g, '\\n');
}

/**
 * RFC 5545 §3.1. 한 줄은 75옥텟까지다. 넘으면 CRLF + 공백으로 잇는다.
 * 한글은 글자당 3바이트라 이름이 조금만 길어도 넘으므로, 글자 경계에서 끊는다.
 */
function fold(line: string): string {
  const enc = new TextEncoder();
  if (enc.encode(line).length <= 75) return line;

  const out: string[] = [];
  let current = '';
  let limit = 75;
  for (const ch of line) {
    if (enc.encode(current + ch).length > limit) {
      out.push(current);
      current = ch;
      limit = 74; // 이어지는 줄은 앞에 공백 한 칸이 붙는다
    } else {
      current += ch;
    }
  }
  out.push(current);
  return out[0] + out.slice(1).map((p) => `\r\n ${p}`).join('');
}

export function downloadIcs(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
