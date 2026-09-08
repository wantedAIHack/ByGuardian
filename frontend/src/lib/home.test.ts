import { describe, expect, it } from 'vitest';
import { densityPhrase, homeState, prepCardPhrase } from './home';
import { me } from '../test/fixtures';
import type { Progress } from './types';

const silent: Progress = { week: 6, silent: true, changes: [], transitions: [], questions: [] };
const changed: Progress = {
  week: 6, silent: false, transitions: [], questions: [],
  changes: [{
    item: 'toilet', label: '화장실 이용', axis: 'LEVEL', axisLabel: '도움 수준',
    status: 'SUSTAINED', duration: 4, from: '지켜보면 됨', to: '혼자 하심',
    message: '이 변화가 4주째 유지되고 있습니다.',
  }],
};

describe('homeState', () => {
  it('토큰이 없으면 온보딩 전이다', () => {
    expect(homeState(false, null, null)).toBe('ONBOARDING');
  });

  it('토큰은 있는데 아직 못 받았으면 불러오는 중이다', () => {
    // 온보딩과 묶으면 다시 온 보호자에게 시작 화면이 번쩍인다
    expect(homeState(true, null, null)).toBe('LOADING');
  });

  it('이번 주 기록이 없으면 기록을 청한다', () => {
    expect(homeState(true, me({ recordedThisWeek: false }), null)).toBe('NOT_RECORDED');
  });

  it('기록했고 바뀐 것이 없으면 침묵이다', () => {
    expect(homeState(true, me({ recordedThisWeek: true }), silent)).toBe('SILENT');
  });

  it('기록했고 변화가 있으면 변화를 보여준다', () => {
    expect(homeState(true, me({ recordedThisWeek: true }), changed)).toBe('CHANGES');
  });

  it('경과를 아직 못 받았으면 침묵으로 둔다', () => {
    // 없는 변화를 지어내는 것보다 조용한 쪽이 안전하다
    expect(homeState(true, me({ recordedThisWeek: true }), null)).toBe('SILENT');
  });

  it('changes와 transitions가 둘 다 비었으면 silent 플래그와 무관하게 침묵이다', () => {
    expect(homeState(true, me({ recordedThisWeek: true }),
      { ...changed, silent: false, changes: [], transitions: [] })).toBe('SILENT');
  });

  it('changes가 비어도 transitions가 있으면 침묵이 아니다', () => {
    // ProgressService는 changes와 transitions를 항목·축마다 배타적으로 채운다 — SUSTAINED가
    // FLUCTUATING으로 넘어가는 주는 changes가 아니라 transitions로만 간다(ProgressControllerTest.
    // transitionAppearsTheWeekSustainedTurnsFluctuatingThenDisappears, week 5). changes만
    // 보고 SILENT로 묶으면 서버가 준 문장을 프론트가 지우는 것이다 — README §4가 지적한,
    // v1이 reverted를 침묵 처리했던 것과 같은 정보 손실이다.
    const transitionsOnly: Progress = {
      week: 5, silent: false, questions: [], changes: [],
      transitions: [{
        item: 'toilet', label: '화장실 이용', axis: 'LEVEL',
        message: '2주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.',
      }],
    };
    expect(homeState(true, me({ recordedThisWeek: true }), transitionsOnly)).toBe('CHANGES');
  });
});

describe('densityPhrase', () => {
  it('몇 주 중 몇 주인지 적는다', () => {
    expect(densityPhrase(me({ recordedWeeks: 5, totalWeeks: 6 }))).toBe('지금까지 6주 중 5주 기록');
  });

  it('첫 주에는 적지 않는다', () => {
    expect(densityPhrase(me({ recordedWeeks: 1, totalWeeks: 1 }))).toBeNull();
  });
});

describe('prepCardPhrase', () => {
  it('개수를 서버가 준 배열 길이에서 가져온다', () => {
    expect(prepCardPhrase(2, null)).toBe('여쭤볼 것 2가지를 준비했습니다.');
    expect(prepCardPhrase(1, null)).toBe('여쭤볼 것 1가지를 준비했습니다.');
  });

  it('질문이 없으면 서버 문구를 그대로 쓴다', () => {
    // 개수를 상수로 박으면 조용한 주에 없는 것을 있다고 말하게 된다. §2 위반이다.
    expect(prepCardPhrase(0, '이번에는 특별히 여쭤볼 것이 없습니다.'))
      .toBe('이번에는 특별히 여쭤볼 것이 없습니다.');
  });

  it('질문도 없고 서버 문구도 없으면 아무 말도 하지 않는다', () => {
    expect(prepCardPhrase(0, null)).toBeNull();
  });
});
