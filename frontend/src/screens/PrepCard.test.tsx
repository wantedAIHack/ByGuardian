import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { PrepCard } from './PrepCard';
import { setToken } from '../lib/api';
import type { PrepCard as Card } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const full: Card = {
  week: 6, nextVisitDate: '2026-09-08',
  questions: [{
    rank: 1, type: 'PLATEAU', source: 'engine',
    sentence: '집 안에서 걷기는 왜 안 늘고 있을까요?',
    evidence: {
      items: [{
        code: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL', axisLabel: '도움 수준',
        values: [{ week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
      }],
      signal: null,
    },
  }],
  extraQuestions: ['밤에 자주 깨시는데 괜찮은가요?'],
  emptyMessage: null,
  therapistGlance: ['화장실 이용 · 도움 수준 4주째 유지'],
};

function renderIt(card: Card) {
  setToken('t');
  server.use(http.get(`${BASE}/me/prep-card`, () => HttpResponse.json(card)));
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><PrepCard /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('진료 준비 카드', () => {
  it('질문 문장을 서버 그대로 낸다', async () => {
    renderIt(full);
    expect(await screen.findByText('집 안에서 걷기는 왜 안 늘고 있을까요?')).toBeInTheDocument();
    expect(screen.getByText('9월 8일 진료')).toBeInTheDocument();
  });

  it('근거는 접혀 있다가 펴진다', async () => {
    const user = userEvent.setup();
    renderIt(full);
    await screen.findByText('집 안에서 걷기는 왜 안 늘고 있을까요?');

    expect(screen.queryByText('손 잡아드림')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '근거 보기' }));
    expect(screen.getByText('손 잡아드림')).toBeInTheDocument();
  });

  it('질문이 없으면 빈 칸을 만들지 않고 서버 문구만 낸다', async () => {
    renderIt({ ...full, questions: [], emptyMessage: '이번에는 특별히 여쭤볼 것이 없습니다.' });
    expect(await screen.findByText('이번에는 특별히 여쭤볼 것이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '근거 보기' })).not.toBeInTheDocument();
  });

  it('추가 질문을 더하고 저장한다', async () => {
    const user = userEvent.setup();
    let sent: any = null;
    renderIt(full);
    server.use(http.put(`${BASE}/me/prep-card/extra`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(['밤에 자주 깨시는데 괜찮은가요?', '약을 바꿔야 할까요?']);
    }));

    await screen.findByText('내가 더 여쭤보고 싶은 것');
    await user.click(screen.getByRole('button', { name: '+ 추가' }));
    // 기존 추가 질문과 새로 붙은 빈 칸이 같은 aria-label을 공유한다 — 방금 붙은
    // 마지막 칸(빈 칸)에 입력한다.
    const fields = screen.getAllByLabelText('여쭤보고 싶은 것');
    await user.type(fields[fields.length - 1]!, '약을 바꿔야 할까요?');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(sent.questions).toEqual(['밤에 자주 깨시는데 괜찮은가요?', '약을 바꿔야 할까요?']);
  });

  it('추가 질문은 다섯 개까지다', async () => {
    renderIt({ ...full, extraQuestions: ['1', '2', '3', '4', '5'] });
    await screen.findByText('내가 더 여쭤보고 싶은 것');
    expect(screen.queryByRole('button', { name: '+ 추가' })).not.toBeInTheDocument();
  });

  it('진료실에서 보여드릴 요약과 링크 발급이 아래에 있다', async () => {
    renderIt(full);
    expect(await screen.findByText('진료실에서 보여드릴 요약')).toBeInTheDocument();
    expect(screen.getByText('화장실 이용 · 도움 수준 4주째 유지')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '치료사에게 보여드리기' })).toBeInTheDocument();
  });
});
