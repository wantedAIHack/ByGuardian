import { describe, expect, it } from 'vitest';
import {
  APP_NAME, DIAGNOSIS_CHOICES, PARETIC_SIDE_CHOICES, RELATIONS, VERBAL_DIFFICULTY_CHOICES,
} from './constants';

describe('constants', () => {
  it('앱 이름이 금지 어휘를 담지 않는다', () => {
    // README §11. "진료"도 같은 기준에 걸릴 가능성이 있다고 적혀 있어 함께 막는다.
    for (const banned of ['재활', '치료', '케어', '진료']) {
      expect(APP_NAME).not.toContain(banned);
    }
  });

  it('관계 선택지가 온보딩과 이어받기에서 같다', () => {
    expect(RELATIONS).toEqual(['딸', '아들', '배우자', '며느리', '사위', '기타']);
  });

  it('진단명이 서버 열거값으로 간다', () => {
    expect(DIAGNOSIS_CHOICES.map((c) => c.value)).toEqual(['STROKE', 'OTHER', 'UNKNOWN']);
    expect(DIAGNOSIS_CHOICES.map((c) => c.label)).toEqual(['뇌졸중', '그 밖', '모름']);
  });

  it('마비 쪽이 서버 열거값으로 간다', () => {
    expect(PARETIC_SIDE_CHOICES.map((c) => c.value)).toEqual(['LEFT', 'RIGHT', 'NONE', 'UNKNOWN']);
    expect(PARETIC_SIDE_CHOICES.map((c) => c.label)).toEqual(['왼쪽', '오른쪽', '없음', '모름']);
  });

  it('표현 난이도에서 "잘 하심"이 NONE이다', () => {
    // 어려움의 정도를 세는 값이라 화면 순서와 이름이 반대로 읽힌다.
    // 뒤집으면 서버가 signalsEnabled를 반대로 잡아, 통증 신호 관찰이
    // 필요 없는 케이스에 켜지거나 정작 필요한 케이스에서 꺼진다.
    expect(VERBAL_DIFFICULTY_CHOICES).toEqual([
      { label: '잘 하심', value: 'NONE' },
      { label: '가끔 어려움', value: 'SOMETIMES' },
      { label: '거의 어려움', value: 'OFTEN' },
    ]);
  });
});
