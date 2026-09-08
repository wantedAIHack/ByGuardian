import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Home } from './Home';
import { catalogFixture, me } from '../test/fixtures';
import { setToken } from '../lib/api';
import { APP_NAME } from '../lib/constants';
import type { Me, PrepCard, Progress } from '../lib/types';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function serve(opts: { me?: Me; progress?: Progress; prepCard?: PrepCard }) {
  const handlers = [];
  if (opts.me) handlers.push(http.get(`${BASE}/me`, () => HttpResponse.json(opts.me)));
  if (opts.progress) handlers.push(http.get(`${BASE}/me/progress`, () => HttpResponse.json(opts.progress)));
  if (opts.prepCard) handlers.push(http.get(`${BASE}/me/prep-card`, () => HttpResponse.json(opts.prepCard)));
  server.use(...handlers);
}

function renderHome() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 카탈로그는 라우트가 useCatalog()로 받아 prop으로 내린다(routes.tsx의 HomeRoute).
  // 화면 테스트는 CatalogProvider의 비동기 로딩을 떠안지 않는다 — Onboarding.test.tsx와 같다.
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Home catalog={catalogFixture} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

const silent: Progress = { week: 6, silent: true, changes: [], transitions: [], questions: [] };

describe('홈', () => {
  it('토큰이 없으면 시작하기·이어받기·둘러보기를 보여준다', () => {
    renderHome();
    expect(screen.getByRole('link', { name: '시작하기' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /이어받기/ })).toBeInTheDocument();
    // 심사위원이 온보딩 열여덟 화면을 거치지 않고 데이터 있는 화면을 볼 수 있는 유일한 통로.
    expect(screen.getByRole('link', { name: '둘러보기' })).toHaveAttribute('href', '/demo');
  });

  it('판정 문구를 만들지 않는다 — 토큰 없음', () => {
    const { container } = renderHome();
    // 금지어 나열이 아니라 전체를 화이트리스트로 건다(Recover.test.tsx·Settings.test.tsx와
    // 같은 방식). 둘러보기 링크를 더한 뒤로 이 문자열이 바뀌었다 — 그것이 이 assertion이
    // 실제로 일하고 있다는 증거지, 완화할 이유가 아니다.
    expect(container.textContent).toBe(
      [
        APP_NAME,
        '집에서 보신 것을 남겨두시면, 다음에 병원 가실 때 여쭤볼 것을 만들어 드립니다.',
        '시작하기',
        '이미 쓰고 계신가요? 이어받기',
        ' · ',
        '둘러보기',
      ].join(''),
    );
    // 눈에 띄는 행동은 하나뿐이어야 한다 — 둘러보기가 조용히 두 번째 .btn을 만들지 않았는지 확인한다.
    const btns = container.querySelectorAll('.btn');
    expect(btns).toHaveLength(1);
    expect(btns[0]?.textContent).toBe('시작하기');
  });

  it('다시 온 보호자에게 시작 화면을 번쩍이지 않는다', async () => {
    setToken('t');
    // /me 를 붙들어 두어 로딩 상태를 그대로 본다
    server.use(http.get(`${BASE}/me`, () => new Promise(() => {})));
    renderHome();

    expect(screen.getByText('불러오는 중입니다…')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '시작하기' })).not.toBeInTheDocument();
  });

  it('이번 주 기록이 없으면 기록 버튼 하나만 크게 둔다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 6 }) });
    renderHome();

    expect(await screen.findByRole('link', { name: '3분 기록하기' })).toBeInTheDocument();
    expect(screen.getByText(/6주차/)).toBeInTheDocument();
  });

  it('전체 재확인 주는 미리 알린다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 8, fullRecheck: true }) });
    renderHome();

    expect(await screen.findByText('이번 주는 8가지를 모두 여쭤봅니다')).toBeInTheDocument();
  });

  it('재확인 안내의 개수를 카탈로그에서 끌어온다', async () => {
    // 표본의 항목 수가 마침 8이라 상수로 박아도 이 스위트는 통과한다.
    // 다섯 항목 표본으로 카탈로그에서 끌어오는지를 고정한다.
    const short = { ...catalogFixture, items: catalogFixture.items.slice(0, 5) };
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 8, fullRecheck: true }) });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Home catalog={short} /></MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('이번 주는 5가지를 모두 여쭤봅니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '5가지 확인하기' })).toBeInTheDocument();
  });

  it('침묵 상태의 여백을 채우지 않는다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: true }), progress: silent });
    renderHome();

    const main = await screen.findByTestId('home-main');
    // 금지어 나열이 아니라 전체를 화이트리스트로 건다. 침묵 상태에 스트릭 문구든
    // 뭐든 한 글자라도 더 넣으면 이 assertion이 걸린다 — 그 여백이 이 제품의 주장이다.
    expect(main.textContent).toBe('이번 주 기록을 남기셨어요 ✓이번 기간에는 바뀐 항목이 없습니다.');
  });

  it('변화는 서버 문장을 그대로 보여준다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true }),
      progress: {
        week: 6, silent: false, transitions: [], questions: [],
        changes: [{
          item: 'toilet', label: '화장실 이용', axis: 'LEVEL', axisLabel: '도움 수준',
          status: 'SUSTAINED', duration: 4, from: '지켜보면 됨', to: '혼자 하심',
          message: '이 변화가 4주째 유지되고 있습니다.',
        }],
      },
    });
    renderHome();

    expect(await screen.findByText('지켜보고 있는 변화')).toBeInTheDocument();
    expect(screen.getByText('화장실 이용')).toBeInTheDocument();
    expect(screen.getByText('지켜보면 됨 → 혼자 하심')).toBeInTheDocument();
    expect(screen.getByText('이 변화가 4주째 유지되고 있습니다.')).toBeInTheDocument();
  });

  it('외래가 가까우면 준비 카드가 맨 위로 온다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      progress: silent,
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', extraQuestions: [], emptyMessage: null,
        therapistGlance: [],
        questions: [
          { rank: 1, type: 'PLATEAU', sentence: '왜 안 늘고 있을까요?', source: 'engine',
            evidence: { items: [], signal: null } },
          { rank: 2, type: 'PLATEAU', sentence: '더 여쭤볼 것', source: 'engine',
            evidence: { items: [], signal: null } },
        ],
      },
    });
    renderHome();

    expect(await screen.findByText('9월 8일 진료가 있습니다 (모레)')).toBeInTheDocument();
    // me → visitSoon → prep-card는 순서가 있는 요청이라(외래가 가까운지는 me가 있어야 안다),
    // d-day 문구가 뜬 시점과 준비 카드 문구가 뜨는 시점 사이에 렌더가 한 번 더 끼어든다.
    // 그래서 이 문구도 findByText로 기다린다. getByText로 동기 단정하면 브리프대로도 깨진다.
    expect(await screen.findByText('여쭤볼 것 2가지를 준비했습니다.')).toBeInTheDocument();
    // 질문 문장 자체는 홈에 내지 않는다. 근거와 함께 봐야 뜻이 산다.
    expect(screen.queryByText('왜 안 늘고 있을까요?')).not.toBeInTheDocument();
  });

  it('기록 전에는 준비 카드가 눈에 띄는 행동을 뺏지 않는다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: false, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', questions: [], extraQuestions: [],
        emptyMessage: '이번에는 특별히 여쭤볼 것이 없습니다.', therapistGlance: [],
      },
    });
    renderHome();

    const record = await screen.findByRole('link', { name: '3분 기록하기' });
    const prep = screen.getByRole('link', { name: '진료 준비 카드 보기' });
    expect(record.className).toContain('btn');
    expect(prep.className).not.toContain('btn');
  });

  it('질문이 없으면 서버의 빈 문구를 그대로 쓴다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      progress: silent,
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', questions: [], extraQuestions: [],
        emptyMessage: '이번에는 특별히 여쭤볼 것이 없습니다.', therapistGlance: [],
      },
    });
    renderHome();

    expect(await screen.findByText('이번에는 특별히 여쭤볼 것이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByText(/가지를 준비했습니다/)).not.toBeInTheDocument();
  });

  it('조용한 목록이 늘 아래에 있다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: true, recordedWeeks: 5, totalWeeks: 6 }), progress: silent });
    renderHome();

    expect(await screen.findByText('지금까지 6주 중 5주 기록')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '전체 기록 보기' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '설정' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '치료사에게 보여드리기' })).toBeInTheDocument();
  });
});
