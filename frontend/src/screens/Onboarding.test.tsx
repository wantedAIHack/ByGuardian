import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Onboarding } from './Onboarding';
import { catalogFixture } from '../test/fixtures';
import { ONBOARDING_DRAFT, loadDraft } from '../lib/draft';
import type { OnboardingState } from '../lib/onboarding';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderScreen() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 카탈로그는 컨텍스트로 주입한다 — 그것만으로는 네트워크가 필요 없다. 아래 화이트리스트
  // 중 이어받기 코드·두 번째 보호자·알림 화면 셋은 기준선 마지막 항목에서 POST /cases를
  // 실제로 타야 도달한다 — 그 셋만 server.use로 핸들러를 준다.
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <Onboarding catalog={catalogFixture} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('온보딩 신원 단계', () => {
  it('안내로 시작하고 걸리는 시간을 미리 알린다', () => {
    renderScreen();
    expect(screen.getByText(/5분 정도 걸립니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '시작' })).toBeInTheDocument();
  });

  it('한 화면에 한 가지만 묻는다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));

    expect(screen.getByRole('button', { name: '딸' })).toBeInTheDocument();
    // 다음 단계의 선택지가 미리 보이지 않는다
    expect(screen.queryByRole('button', { name: '뇌졸중' })).not.toBeInTheDocument();
  });

  it('답하기 전에는 다음 버튼이 눌리지 않는다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));

    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '딸' }));
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
  });

  it('기타를 고르면 직접 입력 칸이 나온다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await user.click(screen.getByRole('button', { name: '기타' }));

    const input = screen.getByLabelText('어떤 관계이신가요?');
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    await user.type(input, '손녀');
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
  });

  it('뒤로 가면 앞선 답이 남아 있다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await user.click(screen.getByRole('button', { name: '딸' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '← 뒤로' }));

    expect(screen.getByRole('button', { name: '딸' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('매 단계 초안을 남긴다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await user.click(screen.getByRole('button', { name: '딸' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    const draft = loadDraft<OnboardingState>(ONBOARDING_DRAFT);
    expect(draft?.relation).toBe('딸');
    expect(draft?.step).toBe(2);
  });

  it('초안이 있으면 그 단계에서 다시 연다', async () => {
    localStorage.setItem(ONBOARDING_DRAFT, JSON.stringify({
      step: 3, relation: '아들', relationOther: '', diagnosis: 'STROKE',
      pareticSide: null, verbalDifficulty: null, nextVisitDate: null, items: {},
    }));

    renderScreen();
    expect(screen.getByText('마비되신 쪽이 어디인가요?')).toBeInTheDocument();
  });

  it('외래일은 건너뛸 수 있다', async () => {
    const user = userEvent.setup();
    localStorage.setItem(ONBOARDING_DRAFT, JSON.stringify({
      step: 5, relation: '딸', relationOther: '', diagnosis: 'STROKE',
      pareticSide: 'LEFT', verbalDifficulty: 'NONE', nextVisitDate: null, items: {},
    }));

    renderScreen();
    await user.click(screen.getByRole('button', { name: '건너뛰기' }));
    expect(screen.getByText(/8가지로 한 번 여쭤보겠습니다/)).toBeInTheDocument();
  });
});

// 아래 세 헬퍼는 신원 네 단계 → 진료일 → 기준선 8항목 → 이어받기 코드로 이어지는 한 줄기
// 흐름을 단계별로 멈춰 세운다. pareticSide는 '없음'을 고른다 — HAND 축이 있는 항목
// (옷 입기·세수·양치·식사)에서도 HAND가 걸러져(handEnabledFor(NONE) === false) 여덟
// 항목 모두 같은 모양(LEVEL + 있으면 AID + CONSISTENCY)으로 채울 수 있다.
async function toDateStep(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '시작' }));
  await user.click(screen.getByRole('button', { name: '딸' }));
  await user.click(screen.getByRole('button', { name: '다음' }));
  await user.click(screen.getByRole('button', { name: '뇌졸중' }));
  await user.click(screen.getByRole('button', { name: '다음' }));
  await user.click(screen.getByRole('button', { name: '없음' }));
  await user.click(screen.getByRole('button', { name: '다음' }));
  await user.click(screen.getByRole('button', { name: '잘 하심' }));
  await user.click(screen.getByRole('button', { name: '다음' }));
  await screen.findByText('다음 진료일이 정해져 있나요?');
}

async function toBaselineIntro(user: ReturnType<typeof userEvent.setup>) {
  await toDateStep(user);
  await user.click(screen.getByRole('button', { name: '건너뛰기' }));
  await screen.findByText('지금 상태를 8가지로 한 번 여쭤보겠습니다.');
}

/** catalogFixture의 8항목 순서대로 (LEVEL 선택, [AID], CONSISTENCY 선택) → 다음. */
async function fillBaseline(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '시작' }));
  const perItem: string[][] = [
    ['휠체어', '좋은 날만'], // transfer: LEVEL,AID,CONSISTENCY
    ['휠체어', '좋은 날만'], // ambulation
    ['휠체어', '좋은 날만'], // stairs
    ['좋은 날만'],           // toilet: LEVEL,CONSISTENCY
    ['좋은 날만'],           // dressing: LEVEL,CONSISTENCY,HAND(걸러짐)
    ['좋은 날만'],           // grooming
    ['좋은 날만'],           // bathing
    ['좋은 날만'],           // feeding
  ];
  for (const rest of perItem) {
    await user.click(screen.getByRole('button', { name: '대부분 도움' }));
    for (const label of rest) {
      await user.click(screen.getByRole('button', { name: label }));
    }
    await user.click(screen.getByRole('button', { name: '다음' }));
  }
}

function mockCaseCreated() {
  server.use(http.post(`${BASE}/cases`, () => HttpResponse.json({
    caseId: 'c1', guardianToken: 'tok1', recoveryCode: 'AB23CD45', week: 1,
  })));
}

async function toRecoveryCode(user: ReturnType<typeof userEvent.setup>) {
  await toBaselineIntro(user);
  await fillBaseline(user);
  await screen.findByText('이어받기 코드');
}

// ------------------------------------------------------------------
// 판정 문구 화이트리스트 — 도달 가능한 화면 모양마다 하나(Home.test.tsx·Settings.test.tsx·
// Record.test.tsx와 같은 정책). Onboarding.tsx는 Record.tsx와 합쳐 764줄인데 화이트리스트가
// 전혀 없었다. 금지어 나열이 아니라 렌더된 전체를 화이트리스트로 건다 — 목록에 없는 말이
// 한 글자라도 끼어들면 아래 assertion들이 걸린다. Screen에는 landmark 롤이 없어
// container.textContent로 전체를 본다.
// ------------------------------------------------------------------
describe('판정 문구 화이트리스트', () => {
  it('안내 화면', () => {
    const { container } = renderScreen();
    expect(container.textContent).toBe(
      [
        '잠깐만 여쭤보겠습니다',
        '5분 정도 걸립니다. 8가지를 여쭤봅니다.',
        '한 번만 하시면 됩니다. 그 뒤로는 매주 3분이면 충분합니다.',
        '시작',
      ].join(''),
    );
  });

  it('선택 단계(관계)', async () => {
    const user = userEvent.setup();
    const { container } = renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await screen.findByText('어떤 분이신가요?');

    expect(container.textContent).toBe(
      ['← 뒤로', '어떤 분이신가요?', '딸', '아들', '배우자', '며느리', '사위', '기타', '다음'].join(''),
    );
  });

  it('진료일 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderScreen();
    await toDateStep(user);

    expect(container.textContent).toBe(
      [
        '← 뒤로',
        '다음 진료일이 정해져 있나요?',
        '모르시면 건너뛰셔도 됩니다. 나중에 설정에서 넣으실 수 있습니다.',
        '다음',
        '건너뛰기',
      ].join(''),
    );
  });

  it('기준선 안내 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderScreen();
    await toBaselineIntro(user);

    expect(container.textContent).toBe(
      [
        '← 뒤로',
        '지금 상태를 8가지로 한 번 여쭤보겠습니다.',
        '지금 어떠신지가 기준이 됩니다. 정답이 없으니 보이시는 대로 고르시면 됩니다.',
        '시작',
      ].join(''),
    );
  });

  it('기준선 항목 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderScreen();
    await toBaselineIntro(user);
    await user.click(screen.getByRole('button', { name: '시작' }));
    await screen.findByText('침대·의자에서 옮겨 앉기');
    // LEVEL을 고르기 전에는 AID·CONSISTENCY가 안 보인다(Onboarding.tsx: "축은 도움
    // 수준을 고른 뒤에 나타난다") — 이 화이트리스트는 고른 뒤의 전체 모양을 본다.
    await user.click(screen.getByRole('button', { name: '대부분 도움' }));

    expect(container.textContent).toBe(
      [
        '← 뒤로',
        '침대·의자에서 옮겨 앉기',
        '요즘 어떠신가요?',
        ...catalogFixture.axes.LEVEL!.map((v) => v.label),
        '보조 도구',
        ...catalogFixture.axes.AID!.map((v) => v.label),
        '이번 주에 얼마나 자주 그러셨나요?',
        ...catalogFixture.axes.CONSISTENCY!.map((v) => v.label),
        '다음',
      ].join(''),
    );
  });

  it('이어받기 코드 화면', async () => {
    const user = userEvent.setup();
    mockCaseCreated();
    const { container } = renderScreen();
    await toRecoveryCode(user);

    // 뒤로 가기가 없다 — 케이스가 이미 만들어진 뒤라 답을 되돌릴 수 없다.
    expect(container.textContent).toBe(
      [
        '이어받기 코드',
        'AB23CD45',
        '폰을 바꾸거나 앱을 지우면 이 코드로 기록을 되찾습니다.',
        '지금 적어두시거나 사진을 찍어두세요. 다시 보여드릴 수 없습니다.',
        '저희는 이 코드를 그대로 갖고 있지 않아 다시 알려드릴 방법이 없습니다.',
        '적어뒀습니다',
      ].join(''),
    );
  });

  it('두 번째 보호자 안내 화면', async () => {
    const user = userEvent.setup();
    mockCaseCreated();
    const { container } = renderScreen();
    await toRecoveryCode(user);
    await user.click(screen.getByRole('button', { name: '적어뒀습니다' }));
    await screen.findByText('다른 가족도 함께 기록하시겠어요?');

    expect(container.textContent).toBe(
      [
        '다른 가족도 함께 기록하시겠어요?',
        '방금 그 코드를 알려주시면 됩니다. 받으신 분이 ‘이어받기’에서 코드를 넣으면 같은 기록에 함께 남기실 수 있습니다.',
        '나중에 설정에서 다시 하실 수 있습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.',
        '알겠습니다',
      ].join(''),
    );
  });

  it('알림 화면', async () => {
    const user = userEvent.setup();
    mockCaseCreated();
    const { container } = renderScreen();
    await toRecoveryCode(user);
    await user.click(screen.getByRole('button', { name: '적어뒀습니다' }));
    await user.click(await screen.findByRole('button', { name: '알겠습니다' }));
    await screen.findByText('매주 알림을 받으시겠어요?');

    expect(container.textContent).toBe(
      [
        '매주 알림을 받으시겠어요?',
        '쓰시는 달력에 매주 같은 요일로 반복 일정을 넣어드립니다. 알림은 달력이 울립니다.',
        '설정에서 언제든 다시 받으실 수 있습니다.',
        '캘린더에 넣기',
        '나중에 하기',
      ].join(''),
    );
  });
});
