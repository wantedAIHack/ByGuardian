import type { Catalog, Me } from '../lib/types';

export const catalogFixture: Catalog = {
  set: 'stroke',
  items: [
    { code: 'transfer', label: '침대·의자에서 옮겨 앉기', phrase: '옮겨 앉기', group: 'mobility', axes: ['LEVEL', 'AID', 'CONSISTENCY'] },
    { code: 'ambulation', label: '집 안에서 걷기', phrase: '집 안에서 걷기', group: 'mobility', axes: ['LEVEL', 'AID', 'CONSISTENCY'] },
    { code: 'stairs', label: '문턱·계단', phrase: '문턱·계단', group: 'mobility', axes: ['LEVEL', 'AID', 'CONSISTENCY'] },
    { code: 'toilet', label: '화장실 이용', phrase: '화장실 이용', group: 'selfcare', axes: ['LEVEL', 'CONSISTENCY'] },
    { code: 'dressing', label: '옷 입기', phrase: '옷 입기', group: 'selfcare', axes: ['LEVEL', 'CONSISTENCY', 'HAND'] },
    { code: 'grooming', label: '세수·양치', phrase: '세수·양치', group: 'selfcare', axes: ['LEVEL', 'CONSISTENCY', 'HAND'] },
    { code: 'bathing', label: '목욕', phrase: '목욕', group: 'selfcare', axes: ['LEVEL', 'CONSISTENCY'] },
    { code: 'feeding', label: '식사', phrase: '식사', group: 'selfcare', axes: ['LEVEL', 'CONSISTENCY', 'HAND'] },
  ],
  axes: {
    LEVEL: [
      { value: 0, label: '대부분 도움' }, { value: 1, label: '손 잡아드림' },
      { value: 2, label: '지켜보면 됨' }, { value: 3, label: '혼자 하심' },
    ],
    AID: [
      { value: 0, label: '휠체어' }, { value: 1, label: '워커' }, { value: 2, label: '지팡이' },
      { value: 3, label: '가구·난간 잡음' }, { value: 4, label: '아무것도 안 잡음' },
    ],
    CONSISTENCY: [
      { value: 0, label: '좋은 날만' }, { value: 1, label: '대체로' }, { value: 2, label: '매번' },
    ],
    HAND: [
      { value: 0, label: '안 씀' }, { value: 1, label: '거들기만' }, { value: 2, label: '주로 씀' },
    ],
  },
  signalActions: [
    { code: 'STANDING', label: '일어설 때' }, { code: 'WALKING', label: '걸을 때' },
    { code: 'TRANSFER', label: '옮겨 앉을 때' }, { code: 'DRESSING', label: '옷 입을 때' },
    { code: 'WASHING', label: '세수할 때' }, { code: 'EATING', label: '식사할 때' },
  ],
  signalKinds: [
    { code: 'GRIMACE', label: '찡그림' }, { code: 'VOCAL', label: '소리 냄' },
    { code: 'GUARDING', label: '팔을 감싸거나 피함' },
  ],
  timeTags: [
    { code: 'MORNING', label: '오전' }, { code: 'AFTERNOON', label: '오후' },
    { code: 'EVENING', label: '저녁' }, { code: 'ANY', label: '상관없음' },
  ],
  sleepLevels: [
    { code: '0', label: '자주 깨심' }, { code: '1', label: '가끔 깨심' }, { code: '2', label: '잘 주무심' },
  ],
  axisLabels: [
    { code: 'LEVEL', label: '도움 수준' }, { code: 'AID', label: '보조 도구' },
    { code: 'CONSISTENCY', label: '이번 주 빈도' }, { code: 'HAND', label: '마비 쪽 손' },
  ],
  axisQuestions: [
    { code: 'LEVEL', label: '도움 수준' }, { code: 'AID', label: '보조 도구' },
    { code: 'CONSISTENCY', label: '이번 주에 얼마나 자주 그러셨나요?' }, { code: 'HAND', label: '마비 쪽 손' },
  ],
};

export const meFixture: Me = {
  caseId: '11111111-1111-1111-1111-111111111111',
  relation: '딸',
  today: '2026-09-06',
  week: 6,
  fullRecheck: false,
  signalsEnabled: false,
  handEnabled: true,
  canRecordThisWeek: true,
  recordedThisWeek: false,
  lastRecordedWeek: 5,
  nextVisitDate: null,
  recordedWeeks: 5,
  totalWeeks: 6,
};

export function me(over: Partial<Me> = {}): Me {
  return { ...meFixture, ...over };
}
