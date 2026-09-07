import type { Diagnosis, PareticSide, VerbalDifficulty } from './types';

/**
 * 서비스 이름은 아직 미정이다. 이건 가칭이고, 정해지면 이 한 줄과
 * vite.config.ts의 PWA manifest, index.html의 title만 고친다.
 *
 * README §11이 "재활" "치료" "케어"를 금지하고, **"진료"도 같은 기준에 걸릴
 * 가능성이 있다**고 적어 두었다. 그래서 가칭에도 넣지 않았다. 어차피 바꿀
 * 이름이지만, 넷 중 하나를 임시로 쓰면 화면·manifest·스크린샷·발표자료에
 * 남아 마감 직전에 찾아 지우는 일이 된다.
 */
export const APP_NAME = '집에서 본 것';

export const RELATIONS = ['딸', '아들', '배우자', '며느리', '사위', '기타'] as const;

/**
 * 아래 세 표는 화면의 한국어를 서버 열거값으로 옮긴다.
 * 카탈로그에 없는 것들이라 여기가 유일한 출처다. 한국어를 그대로 보내면 400이다.
 */
export const DIAGNOSIS_CHOICES: { label: string; value: Diagnosis }[] = [
  { label: '뇌졸중', value: 'STROKE' },
  { label: '그 밖', value: 'OTHER' },
  { label: '모름', value: 'UNKNOWN' },
];

export const PARETIC_SIDE_CHOICES: { label: string; value: PareticSide }[] = [
  { label: '왼쪽', value: 'LEFT' },
  { label: '오른쪽', value: 'RIGHT' },
  { label: '없음', value: 'NONE' },
  { label: '모름', value: 'UNKNOWN' },
];

/**
 * 조심할 자리다. "잘 하심"이 NONE이다.
 * 이 축은 표현 능력이 아니라 **어려움의 정도**를 센다. 화면 순서와 열거값 이름이
 * 반대로 읽히므로 뒤집기 쉽다. 뒤집히면 서버가 signalsEnabled를 반대로 잡아
 * 비언어 통증 관찰이 엉뚱한 케이스에 켜지거나 필요한 케이스에서 꺼진다.
 */
export const VERBAL_DIFFICULTY_CHOICES: { label: string; value: VerbalDifficulty }[] = [
  { label: '잘 하심', value: 'NONE' },
  { label: '가끔 어려움', value: 'SOMETIMES' },
  { label: '거의 어려움', value: 'OFTEN' },
];

/** 통증 신호 화면에서 "없었어요"를 눌렀을 때 보내는 값. 빈 객체이지 null이 아니다. */
export const SIGNAL_NONE: Record<string, string[]> = {};
