import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { setToken } from '../lib/api';
import { Trajectory } from './Trajectory';
import { Therapist } from './Therapist';
import type { Trajectory as Item } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
const item: Item = {
  code: 'grooming:v2', label: '세수·양치', changed: false, axes: [], questionnaireVersion: 2, versionStartWeek: 6,
  observations: [
    { week: 6, question: 'washing', label: '세수할 때 어떻게 하셨나요?', answers: ['직접 보지 못했어요'], source: 'CONFIRMED' },
    { week: 6, question: 'brushing', label: '양치할 때 어떻게 하셨나요?', answers: ['본인이 대부분 하고 일부 동작을 도왔어요'], source: 'CONFIRMED' },
    { week: 7, question: 'brushing', label: '양치할 때 어떻게 하셨나요?', answers: ['본인이 대부분 하고 일부 동작을 도왔어요'], source: 'CARRIED' },
    { week: 6, question: 'note', label: '추가 관찰', answers: ['치약을 짜는 것만 도왔어요.'], source: 'CONFIRMED' },
  ],
};
function show(component: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter>{component}</MemoryRouter></QueryClientProvider>);
}
describe('새 문항의 사실 기록', () => {
  it('전체 기록에서 변화 없음도 세부 응답과 출처를 숨기지 않는다', async () => {
    setToken('guardian');
    server.use(http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([item])));
    show(<Trajectory />);
    expect(await screen.findByText('직접 보지 못했어요')).toBeInTheDocument();
    expect(screen.getByText('치약을 짜는 것만 도왔어요.')).toBeInTheDocument();
    expect(screen.getByText(/6주차부터 새 질문/)).toBeInTheDocument();
    expect(screen.getAllByText('지난 값 유지').length).toBeGreaterThan(0);
  });
  it('의료진 리포트에도 새 응답을 임상 판정 없이 그대로 표시한다', async () => {
    sessionStorage.setItem('nextvisit.therapist-token', '123e4567-e89b-12d3-a456-426614174000');
    server.use(http.get(`${BASE}/t/123e4567-e89b-12d3-a456-426614174000`, () => HttpResponse.json({
      generatedAt: '2026-09-19T10:00:00+09:00', weeks: [6, 7], items: [item], signals: [], sleep: [],
      signalsEnabled: false, freeNotes: [], questions: [], extraQuestions: [], authorChanges: [],
      density: { totalWeeks: 7, recordedWeeks: 2, confirmedWeeks: 1, authors: ['딸'] }, disclaimer: '보호자 관찰 기록입니다.',
    })));
    show(<Therapist />);
    // 대표 문항 답은 주차별 개요 표와 아래 항목별 상세 두 군데에 나온다.
    expect(await screen.findAllByText('직접 보지 못했어요')).toHaveLength(2);
    expect(screen.getByText('치약을 짜는 것만 도왔어요.')).toBeInTheDocument();
    expect(screen.queryByText(/같은 기간 변화 없음 — 세수/)).not.toBeInTheDocument();
  });
});

it('문항 전환 전 기록을 전체 기간 변화 없음으로 잘못 요약하지 않는다', async () => {
  sessionStorage.setItem('nextvisit.therapist-token', '123e4567-e89b-12d3-a456-426614174000');
  const legacy: Item = { code: 'grooming', label: '세수·양치', changed: false,
    axes: [{ axis: 'LEVEL', axisLabel: '도움 수준', values: [{ week: 5, value: 2, label: '지켜보면 됨', source: 'CONFIRMED' }] }] };
  server.use(http.get(`${BASE}/t/123e4567-e89b-12d3-a456-426614174000`, () => HttpResponse.json({
    generatedAt: '2026-09-19T10:00:00+09:00', weeks: [5, 6, 7], items: [legacy, item], signals: [], sleep: [],
    signalsEnabled: false, freeNotes: [], questions: [], extraQuestions: [], authorChanges: [],
    density: { totalWeeks: 7, recordedWeeks: 3, confirmedWeeks: 2, authors: ['딸'] }, disclaimer: '보호자 관찰 기록입니다.',
  })));
  show(<Therapist />);
  expect(await screen.findAllByText('직접 보지 못했어요')).toHaveLength(2);
  expect(screen.queryByText(/같은 기간 변화 없음 — 세수/)).not.toBeInTheDocument();
  expect(screen.getByText('지켜보면 됨')).toBeInTheDocument();
  expect(screen.getByText('세수·양치 (이전 질문)')).toBeInTheDocument();
});
