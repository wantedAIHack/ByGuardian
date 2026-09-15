import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { APP_NAME } from '../lib/constants';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Onboarding } from './Onboarding';
import { catalogFixture } from '../test/fixtures';
import { ONBOARDING_DRAFT } from '../lib/draft';
import { getToken, setToken } from '../lib/api';
import * as ics from '../lib/ics';
import { LAST_STEP, SECOND_GUARDIAN_STEP } from '../lib/onboarding';

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
    expect(screen.getByText('이번 주에 얼마나 자주 그러셨나요?')).toBeInTheDocument();
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

  it('연속 클릭해도 케이스 생성 요청은 한 번만 보낸다', async () => {
    // create.isPending은 @tanstack/react-query가 실제 setTimeout(fn, 0)으로 미룬 뒤에야
    // 반영된다. 그 틈에 들어오는 두 번째 클릭(저가 기기의 중복·유령 터치 포함)이 두 번째
    // 케이스를 만들면 안 된다 — 첫 케이스는 닿을 수 없는 채로 남는다.
    let count = 0;
    server.use(http.post(`${BASE}/cases`, async () => {
      count += 1;
      return HttpResponse.json(
        { caseId: 'c1', guardianToken: 'tok-9', recoveryCode: 'K7M3P9RW', week: 1 },
        { status: 201 },
      );
    }));

    const items: Record<string, unknown> = {};
    for (const i of catalogFixture.items) {
      items[i.code] = { level: 2, aid: i.axes.includes('AID') ? 2 : null,
        consistency: i.axes.includes('CONSISTENCY') ? 1 : null,
        hand: i.axes.includes('HAND') ? 1 : null, note: null };
    }
    seed({ step: 14, items });
    renderScreen();

    // userEvent가 아니라 fireEvent를 쓴다 — 지연 없이 그대로 세 번 누른 상태를 만들어야 한다.
    const button = screen.getByRole('button', { name: '다음' });
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.click(button);

    expect(await screen.findByText('K7M3P9RW')).toBeInTheDocument();
    expect(count).toBe(1);
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

  it('재시도하면 지난 실패 문구가 응답을 기다리지 않고 바로 사라진다', async () => {
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

    // 재시도는 성공으로 바꾼다. fireEvent(동기)로 눌러, 응답이 오기 전 그 순간을 본다.
    server.use(http.post(`${BASE}/cases`, () => HttpResponse.json(
      { caseId: 'c1', guardianToken: 'tok-9', recoveryCode: 'K7M3P9RW', week: 1 }, { status: 201 })));
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    expect(screen.queryByText('기준선에는 8항목이 전부 필요합니다')).not.toBeInTheDocument();
    expect(await screen.findByText('K7M3P9RW')).toBeInTheDocument();
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

/** 현재 라우터 위치를 지켜본다. Onboarding은 Routes 없이 렌더되므로 navigate()가
 * 실제로 어디로 갔는지는 이렇게 형제로 심어야 확인할 수 있다. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderClosing() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/onboarding']}>
        <Onboarding catalog={catalogFixture} />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('온보딩 마무리 화면', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('두 번째 보호자 안내 화면을 보여주고 다음으로 넘어간다', async () => {
    const user = userEvent.setup();
    seed({ step: SECOND_GUARDIAN_STEP });
    renderScreen();

    expect(screen.getByText('다른 가족도 함께 기록하시겠어요?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '알겠습니다' }));
    expect(screen.getByText('매주 알림을 받으시겠어요?')).toBeInTheDocument();
  });

  it("'캘린더에 넣기'는 ics를 만들어 내려받고 홈으로 이동한다", async () => {
    const user = userEvent.setup();
    // jsdom은 URL.createObjectURL을 구현하지 않는다. 실제 Blob 대신 배선만 확인한다.
    const downloadSpy = vi.spyOn(ics, 'downloadIcs').mockImplementation(() => {});
    seed({ step: LAST_STEP });
    renderClosing();

    // toHaveTextContent(문자열)은 부분일치라 '/'는 '/onboarding'에도 걸린다 — 정확히
    // 비교하려면 앵커 붙인 정규식을 써야 한다.
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/onboarding$/);
    await user.click(screen.getByRole('button', { name: '캘린더에 넣기' }));

    expect(downloadSpy).toHaveBeenCalledTimes(1);
    const [filename, content] = downloadSpy.mock.calls[0] as [string, string];
    expect(filename).toBe('주간기록.ics');
    expect(content).toContain(APP_NAME);
    expect(content).toContain('RRULE:FREQ=WEEKLY');
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/);
  });

  it("'나중에 하기'는 ics 없이 홈으로 이동한다", async () => {
    const user = userEvent.setup();
    const downloadSpy = vi.spyOn(ics, 'downloadIcs').mockImplementation(() => {});
    seed({ step: LAST_STEP });
    renderClosing();

    await user.click(screen.getByRole('button', { name: '나중에 하기' }));

    expect(downloadSpy).not.toHaveBeenCalled();
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/);
  });
});
