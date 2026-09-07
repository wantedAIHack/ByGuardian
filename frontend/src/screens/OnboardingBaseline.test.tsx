import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Onboarding } from './Onboarding';
import { catalogFixture } from '../test/fixtures';
import { ONBOARDING_DRAFT } from '../lib/draft';
import { getToken, setToken } from '../lib/api';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/** 기준선 직전(7단계)에서 시작하도록 초안을 심는다. */
function seed(over: Record<string, unknown> = {}) {
  localStorage.setItem(ONBOARDING_DRAFT, JSON.stringify({
    step: 7, relation: '딸', relationOther: '', diagnosis: 'STROKE',
    pareticSide: 'LEFT', verbalDifficulty: 'NONE', nextVisitDate: null, items: {},
    ...over,
  }));
}

function renderScreen() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <Onboarding catalog={catalogFixture} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('온보딩 기준선', () => {
  it('항목 이름과 도움 수준 넷을 보여준다', () => {
    seed();
    renderScreen();
    expect(screen.getByText('침대·의자에서 옮겨 앉기')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '혼자 하심' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '대부분 도움' })).toBeInTheDocument();
  });

  it('도움 수준을 고르기 전에는 축을 보여주지 않는다', async () => {
    const user = userEvent.setup();
    seed();
    renderScreen();

    expect(screen.queryByText('보조 도구')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '지켜보면 됨' }));
    expect(screen.getByText('보조 도구')).toBeInTheDocument();
    expect(screen.getByText('한 주 일관성')).toBeInTheDocument();
  });

  it('마비 쪽을 모르면 마비 쪽 손을 묻지 않는다', async () => {
    const user = userEvent.setup();
    // dressing은 5번째 항목(0-based 4)이라 11단계이고 HAND 축이 있다
    seed({ step: 11, pareticSide: 'UNKNOWN' });
    renderScreen();

    await user.click(screen.getByRole('button', { name: '지켜보면 됨' }));
    expect(screen.queryByText('마비 쪽 손')).not.toBeInTheDocument();
  });

  it('마비 쪽을 알면 마비 쪽 손을 묻는다', async () => {
    const user = userEvent.setup();
    seed({ step: 11, pareticSide: 'LEFT' });
    renderScreen();

    await user.click(screen.getByRole('button', { name: '지켜보면 됨' }));
    expect(screen.getByText('마비 쪽 손')).toBeInTheDocument();
  });

  it('마지막 항목을 넘기면 케이스를 만들고 복구 코드를 보여준다', async () => {
    const user = userEvent.setup();
    let sent: Record<string, unknown> | null = null;
    server.use(http.post(`${BASE}/cases`, async ({ request }) => {
      sent = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(
        { caseId: 'c1', guardianToken: 'tok-9', recoveryCode: 'K7M3P9RW', week: 1 },
        { status: 201 },
      );
    }));

    // 8항목을 다 채운 채로 마지막 항목 화면(14단계)에서 시작한다
    const items: Record<string, unknown> = {};
    for (const i of catalogFixture.items) {
      items[i.code] = {
        level: 2,
        aid: i.axes.includes('AID') ? 2 : null,
        consistency: i.axes.includes('CONSISTENCY') ? 1 : null,
        hand: i.axes.includes('HAND') ? 1 : null,
        note: null,
      };
    }
    seed({ step: 14, items });
    renderScreen();

    await user.click(screen.getByRole('button', { name: '다음' }));

    expect(await screen.findByText('K7M3P9RW')).toBeInTheDocument();
    expect(getToken()).toBe('tok-9');
    expect(sent).not.toBeNull();
    expect((sent as any).baseline.painSignal).toBeNull();
    expect(Object.keys((sent as any).baseline.items)).toHaveLength(8);
    // 케이스가 만들어졌으니 초안은 지운다
    expect(localStorage.getItem(ONBOARDING_DRAFT)).toBeNull();
  });

  it('저장이 실패하면 서버 문구를 그대로 보여주고 그 자리에 머문다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/cases`, () =>
      HttpResponse.json({ code: 'VALIDATION', message: '기준선에는 8항목이 전부 필요합니다' }, { status: 400 })));

    const items: Record<string, unknown> = {};
    for (const i of catalogFixture.items) {
      items[i.code] = { level: 2, aid: i.axes.includes('AID') ? 2 : null,
        consistency: i.axes.includes('CONSISTENCY') ? 1 : null,
        hand: i.axes.includes('HAND') ? 1 : null, note: null };
    }
    seed({ step: 14, items });
    renderScreen();

    await user.click(screen.getByRole('button', { name: '다음' }));

    expect(await screen.findByText('기준선에는 8항목이 전부 필요합니다')).toBeInTheDocument();
    // 초안이 살아 있어야 다시 시도할 수 있다
    expect(localStorage.getItem(ONBOARDING_DRAFT)).not.toBeNull();
  });

  it('이미 토큰이 있으면 온보딩을 다시 시작하지 않는다', async () => {
    // 온보딩을 마친 사람이 /onboarding에 다시 오거나, POST /cases 성공 직후 앱이 죽었다가
    // 다시 열린 경우. 그대로 두면 케이스가 하나 더 만들어지고 첫 번째는 닿을 수 없게 된다.
    setToken('already-onboarded');
    seed({ step: 3 });
    renderScreen();

    expect(screen.queryByText('마비되신 쪽이 어디인가요?')).not.toBeInTheDocument();
    expect(localStorage.getItem(ONBOARDING_DRAFT)).toBeNull();
  });

  it('복구 코드를 확인하기 전에는 넘어갈 수 없다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/cases`, () => HttpResponse.json(
      { caseId: 'c1', guardianToken: 'tok-9', recoveryCode: 'K7M3P9RW', week: 1 }, { status: 201 })));

    const items: Record<string, unknown> = {};
    for (const i of catalogFixture.items) {
      items[i.code] = { level: 2, aid: i.axes.includes('AID') ? 2 : null,
        consistency: i.axes.includes('CONSISTENCY') ? 1 : null,
        hand: i.axes.includes('HAND') ? 1 : null, note: null };
    }
    seed({ step: 14, items });
    renderScreen();
    await user.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('K7M3P9RW');

    expect(screen.getByRole('button', { name: '적어뒀습니다' })).toBeInTheDocument();
    expect(screen.getByText(/다시 보여드릴 수 없습니다/)).toBeInTheDocument();
  });
});
