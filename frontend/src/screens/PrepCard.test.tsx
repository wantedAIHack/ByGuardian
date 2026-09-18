import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { PrepCard } from './PrepCard';
import { setToken } from '../lib/api';
import { QK } from '../lib/queries';
import type { PrepCard as Card, PrepItem } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const evidence = {
  items: [{
    code: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL', axisLabel: '도움 수준',
    values: [{ week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
  }],
  signal: null,
};

const base: Card = {
  week: 6, nextVisitDate: '2026-09-08',
  questions: [{
    rank: 1, type: 'SYNTHESIS', source: 'LLM',
    sentence: '합성 정리 질문인데 괜찮을까요?', evidence,
  }],
  extraQuestions: [], emptyMessage: null,
  therapistGlance: ['화장실 이용 · 도움 수준 4주째 유지'],
  generationStatus: 'DONE', edited: false, suggestionAvailable: false,
  items: [
    {
      id: 'q1-a', sentence: '합성 정리 질문인데 괜찮을까요?', origin: 'LLM', edited: false,
      basis: {
        evidence,
        notes: [{ week: 3, timeTagLabel: '오후', itemLabel: null, text: '합성 원문 메모' }],
      },
    },
    {
      id: 'x1-b', sentence: '직접 적은 합성 질문인데 괜찮을까요?', origin: 'CAREGIVER', edited: false,
      basis: { evidence: { items: [], signal: null }, notes: [] },
    },
  ],
};

const noBasis = { evidence: { items: [], signal: null }, notes: [] };

function item(id: string, sentence: string, origin: PrepItem['origin'] = 'CAREGIVER'): PrepItem {
  return { id, sentence, origin, edited: false, basis: noBasis };
}

function renderIt(card: Card) {
  setToken('t');
  server.use(http.get(`${BASE}/me/prep-card`, () => HttpResponse.json(card)));
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><PrepCard /></MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

function legacyCard(): Card {
  const {
    items: _items,
    generationStatus: _generationStatus,
    edited: _edited,
    suggestionAvailable: _suggestionAvailable,
    ...legacy
  } = base;
  return { ...legacy, extraQuestions: ['밤에 자주 깨시는데 괜찮은가요?'] };
}

describe('진료 준비 카드', () => {
  it('질문을 하나의 목록에서 출처와 함께 보여준다', async () => {
    renderIt(base);

    expect(await screen.findByRole('heading', { name: '진료실에서 여쭤볼 것' })).toBeInTheDocument();
    expect(screen.getByText('질문 1 · 기록에서 정리')).toBeInTheDocument();
    expect(screen.getByText('질문 2 · 직접 적은 질문')).toBeInTheDocument();
    expect(screen.getByText('합성 정리 질문인데 괜찮을까요?')).toBeInTheDocument();
    expect(screen.getByText('직접 적은 합성 질문인데 괜찮을까요?')).toBeInTheDocument();
  });

  it('질문 안에서 보호자 원문과 관찰 근거를 펼친다', async () => {
    const user = userEvent.setup();
    renderIt(base);
    const articles = await screen.findAllByRole('article');

    expect(within(articles[1]!).queryByRole('button', { name: '이 질문의 근거' })).not.toBeInTheDocument();
    await user.click(within(articles[0]!).getByRole('button', { name: '이 질문의 근거' }));

    expect(within(articles[0]!).getByText('3주 · 오후')).toBeInTheDocument();
    expect(within(articles[0]!).getByText('합성 원문 메모')).toBeInTheDocument();
    expect(within(articles[0]!).getByText('보호자 기록')).toBeInTheDocument();
    expect(within(articles[0]!).getByText('관찰 기록')).toBeInTheDocument();
    expect(within(articles[0]!).getByText('집 안에서 걷기 · 도움 수준')).toBeInTheDocument();
  });

  it('질문을 고치고 추가하고 지운 뒤 하나의 목록으로 저장한다', async () => {
    const user = userEvent.setup();
    let body: unknown;
    const savedItems = [
      { ...base.items![0]!, id: 'c-one', sentence: '고친 질문인데 괜찮을까요?', edited: true },
      item('c-two', '새 질문인데 괜찮을까요?'),
    ];
    server.use(http.put(`${BASE}/me/prep-card/questions`, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ ...base, edited: true, items: savedItems });
    }));
    renderIt(base);

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));
    await user.clear(screen.getByLabelText('질문 1'));
    await user.type(screen.getByLabelText('질문 1'), '고친 질문인데 괜찮을까요?');
    await user.click(screen.getByRole('button', { name: '+ 질문 추가' }));
    await user.type(screen.getByLabelText('질문 3'), '새 질문인데 괜찮을까요?');
    await user.click(screen.getByRole('button', { name: '질문 2 지우기' }));
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(body).toEqual({
      items: [
        { id: 'q1-a', sentence: '고친 질문인데 괜찮을까요?' },
        { id: null, sentence: '새 질문인데 괜찮을까요?' },
      ],
    }));
    expect(await screen.findByText('고친 질문인데 괜찮을까요?')).toBeInTheDocument();
    expect(screen.getByText('새 질문인데 괜찮을까요?')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('질문을 저장했습니다.');
  });

  it('저장 실패 뒤 편집한 질문을 그대로 둔다', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${BASE}/me/prep-card/questions`, () => new HttpResponse(null, { status: 500 })));
    renderIt(base);

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));
    await user.clear(screen.getByLabelText('질문 1'));
    await user.type(screen.getByLabelText('질문 1'), '실패해도 남는 질문인데 괜찮을까요?');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '질문을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 저장해 주세요.',
    );
    expect(screen.getByLabelText('질문 1')).toHaveValue('실패해도 남는 질문인데 괜찮을까요?');
  });

  it('취소하면 원래 목록으로 돌아가고 저장하지 않는다', async () => {
    const user = userEvent.setup();
    let puts = 0;
    server.use(http.put(`${BASE}/me/prep-card/questions`, () => {
      puts++;
      return HttpResponse.json(base);
    }));
    renderIt(base);

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));
    await user.clear(screen.getByLabelText('질문 1'));
    await user.type(screen.getByLabelText('질문 1'), '저장하지 않을 질문');
    await user.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.getByText('합성 정리 질문인데 괜찮을까요?')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('저장하지 않을 질문')).not.toBeInTheDocument();
    expect(puts).toBe(0);
  });

  it('정리 중에도 현재 질문 목록과 상태를 보여준다', async () => {
    renderIt({ ...base, generationStatus: 'PENDING' });

    expect(await screen.findByText('기록을 바탕으로 질문을 정리하고 있어요.')).toHaveAttribute('role', 'status');
    expect(screen.getByText('합성 정리 질문인데 괜찮을까요?')).toBeInTheDocument();
  });

  it('편집 중 정리가 끝나도 입력을 보존하고 새 정리안 도착을 알린다', async () => {
    const user = userEvent.setup();
    const pending = { ...base, generationStatus: 'PENDING' as const };
    const { queryClient } = renderIt(pending);

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));
    await user.clear(screen.getByLabelText('질문 1'));
    await user.type(screen.getByLabelText('질문 1'), '편집 중인 질문');
    act(() => queryClient.setQueryData(QK.prepCard, {
      ...base,
      items: [item('new-server-id', '서버가 새로 정리한 질문')],
    }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      '정리안이 준비됐어요. 취소하시면 정리안을 보여드려요.',
    );
    expect(screen.getByLabelText('질문 1')).toHaveValue('편집 중인 질문');
  });

  it('새 정리안을 다시 정리해 응답 목록으로 바꾼다', async () => {
    const user = userEvent.setup();
    let calls = 0;
    const regenerated = {
      ...base,
      edited: false,
      suggestionAvailable: false,
      items: [item('regenerated', '새 기록을 반영한 질문인데 괜찮을까요?', 'LLM')],
    };
    server.use(http.post(`${BASE}/me/prep-card/regenerate`, () => {
      calls++;
      return HttpResponse.json(regenerated);
    }));
    renderIt({ ...base, edited: true, suggestionAvailable: true });

    expect(await screen.findByText('새 기록이 반영된 정리안이 있어요.')).toBeInTheDocument();
    expect(screen.getByText('직접 적으신 질문은 그대로 남아요.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 정리하기' }));

    expect(await screen.findByText('새 기록을 반영한 질문인데 괜찮을까요?')).toBeInTheDocument();
    expect(calls).toBe(1);
  });

  it('다시 정리하지 못하면 재시도 안내를 보여준다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/me/prep-card/regenerate`, () => new HttpResponse(null, { status: 500 })));
    renderIt({ ...base, edited: true, suggestionAvailable: true });

    await user.click(await screen.findByRole('button', { name: '다시 정리하기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '다시 정리하지 못했습니다. 잠시 뒤 다시 눌러 주세요.',
    );
  });

  it('확정 목록이 없을 때만 템플릿 질문 안내를 보여준다', async () => {
    const first = renderIt({ ...base, generationStatus: 'TEMPLATE_ONLY', edited: false });
    expect(await screen.findByText('관찰 기록에서 나온 질문을 보여드려요.')).toBeInTheDocument();
    first.unmount();

    renderIt({ ...base, generationStatus: 'TEMPLATE_ONLY', edited: true });
    await screen.findByText('합성 정리 질문인데 괜찮을까요?');
    expect(screen.queryByText('관찰 기록에서 나온 질문을 보여드려요.')).not.toBeInTheDocument();
  });

  it('질문은 여덟 개까지 편집한다', async () => {
    const user = userEvent.setup();
    const items = Array.from({ length: 8 }, (_, i) => item(`id-${i}`, `${i + 1}번째 질문`));
    renderIt({ ...base, items });

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));

    expect(screen.queryByRole('button', { name: '+ 질문 추가' })).not.toBeInTheDocument();
    expect(screen.getByText('여덟 개까지 넣으실 수 있습니다.')).toBeInTheDocument();
  });

  it('옛 백엔드 질문과 추가 질문은 읽기 전용으로 보여준다', async () => {
    const card = legacyCard();
    renderIt(card);

    expect(await screen.findByText(card.questions[0]!.sentence)).toBeInTheDocument();
    expect(screen.getByText(card.extraQuestions[0]!)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '질문 고치기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('옛 백엔드의 같은 추가 질문을 경고 없이 모두 보여준다', async () => {
    const duplicate = '밤에 자주 깨시는데 괜찮은가요?';
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    try {
      renderIt({ ...legacyCard(), extraQuestions: [duplicate, duplicate] });

      expect(await screen.findAllByText(duplicate)).toHaveLength(2);
      expect(consoleError.mock.calls.flat().join(' ')).not.toContain('same key');
    } finally {
      consoleError.mockRestore();
    }
  });

  it('판정 문구를 만들지 않는다 — 빈 상태', async () => {
    renderIt({ ...base, nextVisitDate: null, questions: [], items: [], therapistGlance: [] });
    await screen.findByRole('heading', { name: '진료실에서 여쭤볼 것' });

    expect(screen.getByRole('main').textContent).toBe(
      '← 뒤로진료 준비진료실에서 여쭤볼 것+ 질문 적기치료사에게 보여드리기',
    );
  });

  it('판정 문구를 만들지 않는다 — 질문이 있는 상태', async () => {
    renderIt(base);
    await screen.findByRole('heading', { name: '진료실에서 여쭤볼 것' });

    const collapsed = [
      '← 뒤로',
      '9월 8일 진료',
      '진료실에서 여쭤볼 것',
      `질문 1 · 기록에서 정리${base.items![0]!.sentence}이 질문의 근거`,
      `질문 2 · 직접 적은 질문${base.items![1]!.sentence}`,
      '질문 고치기',
      '진료실에서 보여드릴 요약',
      base.therapistGlance[0]!,
      '치료사에게 보여드리기',
    ].join('');
    expect(screen.getByRole('main').textContent).toBe(collapsed);
  });

  it('저장 중 바뀐 편집은 유지하고 응답 id만 살아남은 초안에 이어 붙인다', async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const bodies: unknown[] = [];
    let call = 0;
    server.use(http.put(`${BASE}/me/prep-card/questions`, async ({ request }) => {
      const received = await request.json();
      bodies.push(received);
      call++;
      if (call === 1) {
        await gate;
        return HttpResponse.json({
          ...base,
          edited: true,
          items: [
            { ...base.items![0]!, id: 'c-q1' },
            { ...base.items![1]!, id: 'c-q2' },
            item('c-new', '첫 저장 질문인데 괜찮을까요?'),
          ],
        });
      }
      const second = received as { items: Array<{ sentence: string }> };
      return HttpResponse.json({
        ...base,
        edited: true,
        items: second.items.map((sent, i) => item(`final-${i}`, sent.sentence)),
      });
    }));
    renderIt(base);

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));
    await user.click(screen.getByRole('button', { name: '+ 질문 추가' }));
    await user.type(screen.getByLabelText('질문 3'), '첫 저장 질문인데 괜찮을까요?');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await user.clear(screen.getByLabelText('질문 3'));
    await user.type(screen.getByLabelText('질문 3'), '저장 중 고친 질문인데 괜찮을까요?');
    await user.click(screen.getByRole('button', { name: '질문 2 지우기' }));
    await user.click(screen.getByRole('button', { name: '+ 질문 추가' }));
    await user.type(screen.getByLabelText('질문 3'), '저장 뒤 추가한 질문인데 괜찮을까요?');
    release();

    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeEnabled());
    expect(screen.getByRole('heading', { name: '질문 고치기' })).toBeInTheDocument();
    expect(screen.getByLabelText('질문 2')).toHaveValue('저장 중 고친 질문인데 괜찮을까요?');
    expect(screen.getByLabelText('질문 3')).toHaveValue('저장 뒤 추가한 질문인데 괜찮을까요?');
    expect(screen.queryByDisplayValue('직접 적은 합성 질문인데 괜찮을까요?')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({
      items: [
        { id: 'c-q1', sentence: '합성 정리 질문인데 괜찮을까요?' },
        { id: 'c-new', sentence: '저장 중 고친 질문인데 괜찮을까요?' },
        { id: null, sentence: '저장 뒤 추가한 질문인데 괜찮을까요?' },
      ],
    });
  });

  it.each([
    {
      mismatch: '순서가 바뀐',
      responseItems: [
        { ...base.items![1]!, id: 'c-q2' },
        { ...base.items![0]!, id: 'c-q1' },
        item('c-new', '첫 저장 질문인데 괜찮을까요?'),
      ],
    },
    {
      mismatch: '항목이 빠진',
      responseItems: [
        { ...base.items![0]!, id: 'c-q1' },
        { ...base.items![1]!, id: 'c-q2' },
      ],
    },
  ])('$mismatch 저장 응답에서는 어떤 id도 초안에 이어 붙이지 않는다', async ({ responseItems }) => {
    const user = userEvent.setup();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const bodies: unknown[] = [];
    let call = 0;
    server.use(http.put(`${BASE}/me/prep-card/questions`, async ({ request }) => {
      const received = await request.json();
      bodies.push(received);
      call++;
      if (call === 1) {
        await gate;
        return HttpResponse.json({ ...base, edited: true, items: responseItems });
      }
      return HttpResponse.json(base);
    }));
    renderIt(base);

    await user.click(await screen.findByRole('button', { name: '질문 고치기' }));
    await user.click(screen.getByRole('button', { name: '+ 질문 추가' }));
    await user.type(screen.getByLabelText('질문 3'), '첫 저장 질문인데 괜찮을까요?');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await user.clear(screen.getByLabelText('질문 3'));
    await user.type(screen.getByLabelText('질문 3'), '저장 중 고친 질문인데 괜찮을까요?');
    release();

    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({
      items: [
        { id: 'q1-a', sentence: '합성 정리 질문인데 괜찮을까요?' },
        { id: 'x1-b', sentence: '직접 적은 합성 질문인데 괜찮을까요?' },
        { id: null, sentence: '저장 중 고친 질문인데 괜찮을까요?' },
      ],
    });
  });
});
