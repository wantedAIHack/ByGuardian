import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HttpResponse, http } from 'msw';
import type { PropsWithChildren } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { server } from '../test/server';
import type { PrepCard } from './types';
import { QK, usePrepCard, useRegenerateQuestions, useSaveQuestions } from './queries';

const BASE = 'http://localhost:8080';
const card = (generationStatus: PrepCard['generationStatus']): PrepCard => ({
  week: 6,
  nextVisitDate: null,
  questions: [],
  extraQuestions: [],
  emptyMessage: null,
  therapistGlance: [],
  items: [],
  generationStatus,
  edited: false,
  suggestionAvailable: false,
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('prep-card queries', () => {
  it('PENDING 카드를 5초 뒤 다시 읽고 완료되면 멈춘다', async () => {
    let calls = 0;
    server.use(http.get(`${BASE}/me/prep-card`, () => {
      calls += 1;
      return HttpResponse.json(card(calls === 1 ? 'PENDING' : 'DONE'));
    }));
    const { wrapper } = setup();
    const { result } = renderHook(() => usePrepCard(), { wrapper });

    await waitFor(() => expect(result.current.data?.generationStatus).toBe('PENDING'));
    await waitFor(() => expect(result.current.data?.generationStatus).toBe('DONE'), { timeout: 7_000 });
    expect(calls).toBe(2);
  }, 8_000);

  it('저장과 다시 정리 응답으로 준비 카드 캐시를 교체한다', async () => {
    const saved = { ...card('DONE'), edited: true };
    const regenerated = { ...card('TEMPLATE_ONLY'), edited: false };
    server.use(
      http.put(`${BASE}/me/prep-card/questions`, async ({ request }) => {
        expect(await request.json()).toEqual({ items: [{ id: null, sentence: '새 질문인가요?' }] });
        return HttpResponse.json(saved);
      }),
      http.post(`${BASE}/me/prep-card/regenerate`, () => HttpResponse.json(regenerated)),
    );
    const { client, wrapper } = setup();
    const save = renderHook(() => useSaveQuestions(), { wrapper });
    const regenerate = renderHook(() => useRegenerateQuestions(), { wrapper });

    act(() => save.result.current.mutate([{ id: null, sentence: '새 질문인가요?' }]));
    await waitFor(() => expect(save.result.current.isSuccess).toBe(true));
    expect(client.getQueryData(QK.prepCard)).toEqual(saved);

    act(() => regenerate.result.current.mutate());
    await waitFor(() => expect(regenerate.result.current.isSuccess).toBe(true));
    expect(client.getQueryData(QK.prepCard)).toEqual(regenerated);
  });
});
