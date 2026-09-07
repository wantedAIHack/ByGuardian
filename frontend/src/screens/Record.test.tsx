import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Record } from './Record';
import { catalogFixture, me } from '../test/fixtures';
import { setToken } from '../lib/api';
import type { Me, Trajectory } from '../lib/types';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderRecord(m: Me, traj: Trajectory[] = []) {
  setToken('t');
  server.use(
    http.get(`${BASE}/me`, () => HttpResponse.json(m)),
    http.get(`${BASE}/me/trajectory`, () => HttpResponse.json(traj)),
  );
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Record catalog={catalogFixture} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('주간 기록', () => {
  it('달라진 게 있는지부터 묻는다', async () => {
    renderRecord(me());
    expect(await screen.findByText('지난주와 달라진 게 있나요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '네, 달라진 게 있어요' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '없어요' })).toBeInTheDocument();
  });

  it('"없어요"를 눌러도 수면을 묻는다', async () => {
    const user = userEvent.setup();
    renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));

    expect(screen.getByText('밤에 어떻게 주무셨나요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '잘 주무심' })).toBeInTheDocument();
  });

  it('신호가 켜진 케이스는 "없었어요"를 큰 버튼으로 먼저 낸다', async () => {
    const user = userEvent.setup();
    renderRecord(me({ signalsEnabled: true }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));

    expect(screen.getByRole('button', { name: '없었어요' })).toBeInTheDocument();
  });

  it('전체 재확인 주는 미리 알리고 8항목을 순서대로 낸다', async () => {
    const user = userEvent.setup();
    renderRecord(me({ fullRecheck: true, week: 8 }));

    expect(await screen.findByText('이번 주는 8가지를 모두 여쭤봅니다')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '시작' }));
    expect(screen.getByText('침대·의자에서 옮겨 앉기')).toBeInTheDocument();
  });

  it('재확인 안내의 개수를 카탈로그에서 끌어온다', async () => {
    // 표본의 항목 수가 마침 8이라 상수로 박아도 오늘은 통과한다. 그것을 못 하게 고정한다.
    const short = { ...catalogFixture, items: catalogFixture.items.slice(0, 5) };
    setToken('t');
    server.use(
      http.get(`${BASE}/me`, () => HttpResponse.json(me({ fullRecheck: true, week: 8 }))),
      http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([])),
    );
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Record catalog={short} /></MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('이번 주는 5가지를 모두 여쭤봅니다')).toBeInTheDocument();
  });

  it('전체 재확인 주에 지난달 답을 함께 보여준다', async () => {
    const user = userEvent.setup();
    renderRecord(me({ fullRecheck: true, week: 8 }), [{
      code: 'transfer', label: '침대·의자에서 옮겨 앉기', changed: true,
      axes: [{
        axis: 'LEVEL', axisLabel: '도움 수준',
        values: [{ week: 4, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
      }],
    }]);

    await user.click(await screen.findByRole('button', { name: '시작' }));
    expect(screen.getByText('지난번에는 손 잡아드림')).toBeInTheDocument();
  });

  it('자유 기록 칸이 처음부터 여러 줄이다', async () => {
    const user = userEvent.setup();
    renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    // 수면 선택은 화면을 넘기지 않는다. 마음을 바꿀 수 있어야 하므로 '다음'을 눌러야 간다.
    await user.click(screen.getByRole('button', { name: '다음' }));

    const box = screen.getByLabelText('말씀하시듯 편하게 적어주세요');
    expect(box.tagName).toBe('TEXTAREA');
    expect(Number(box.getAttribute('rows'))).toBeGreaterThanOrEqual(4);
  });

  it('저장하면 서버로 보내고 홈으로 돌아간다', async () => {
    const user = userEvent.setup();
    let sent: any = null;
    server.use(http.put(`${BASE}/me/weeks/6`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
    }));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '가끔 깨심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    await screen.findByText('기록을 남겼습니다.');
    expect(sent.noChange).toBe(true);
    expect(sent.sleep).toBe(1);
    expect(sent.painSignal).toBeNull();
    expect(sent.freeNote).toBeNull();
  });

  it('주차가 넘어가면 말없이 재시도하지 않고 물어본다', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${BASE}/me/weeks/6`, () =>
      HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 7주차입니다' }, { status: 409 })));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    // 지난주 관찰이 이번 주 기록이 되면 안 된다. 층 1은 버리지도 옮기지도 않는다.
    expect(await screen.findByText(/날짜가 바뀌었습니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이번 주 것입니다' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '지난주 것입니다' })).toBeInTheDocument();
  });

  it('저장에 실패하면 서버 문구를 그대로 보여준다', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${BASE}/me/weeks/6`, () =>
      HttpResponse.json({ code: 'VALIDATION', message: 'sleep은 0..2입니다' }, { status: 400 })));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    expect(await screen.findByText('sleep은 0..2입니다')).toBeInTheDocument();
  });
});
