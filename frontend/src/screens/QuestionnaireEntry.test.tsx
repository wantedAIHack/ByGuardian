import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { revisedCatalog } from '../test/questionnaireFixtures';
import { me } from '../test/fixtures';
import { initialState } from '../lib/onboarding';
import { initialRecord } from '../lib/record';
import { ONBOARDING_DRAFT, weeklyDraftKey } from '../lib/draft';
import { setToken } from '../lib/api';
import { Onboarding } from './Onboarding';
import { Record } from './Record';
import type { OnboardingRequest, WeeklyRecordRequest } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
function show(component: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter>{component}</MemoryRouter></QueryClientProvider>);
}
it('온보딩은 식사 질문의 실제 답변을 버전과 함께 저장한다', async () => {
  const user = userEvent.setup();
  localStorage.setItem(ONBOARDING_DRAFT, JSON.stringify({ ...initialState(), step: 14, relation: '딸',
    diagnosis: 'STROKE', pareticSide: 'LEFT', verbalDifficulty: 'NONE' }));
  let body: OnboardingRequest | undefined;
  server.use(http.post(`${BASE}/cases`, async ({ request }) => {
    body = await request.json() as OnboardingRequest;
    return HttpResponse.json({ caseId: 'case', guardianToken: 'token', recoveryCode: 'ABCDEFGH', week: 1 }, { status: 201 });
  }));
  show(<Onboarding catalog={revisedCatalog} />);
  expect(screen.queryByRole('button', { name: '손 잡아드림' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '영양관을 사용했어요' }));
  await user.type(screen.getByRole('textbox', { name: /추가로 남길 관찰/ }), '저녁에 보호자가 도왔어요.');
  await user.click(screen.getByRole('button', { name: '다음' }));
  await waitFor(() => expect(body?.baseline.items.feeding).toMatchObject({ questionnaireVersion: 2,
    answers: { route: ['tube'] }, level: null, consistency: null, hand: null, note: '저녁에 보호자가 도왔어요.' }));
  expect(await screen.findByText('ABCDEFGH')).toBeInTheDocument();
});
it('주간 기록은 숨긴 응답을 버리고 새 선택지와 메모를 전송한다', async () => {
  const user = userEvent.setup();
  const current = me();
  setToken('guardian');
  localStorage.setItem(weeklyDraftKey(current.caseId, current.week), JSON.stringify({ ...initialRecord(), index: 2,
    noChange: false, selected: ['feeding'] }));
  let body: WeeklyRecordRequest | undefined;
  server.use(http.get(`${BASE}/me`, () => HttpResponse.json(current)),
    http.put(`${BASE}/me/weeks/6`, async ({ request }) => {
      body = await request.json() as WeeklyRecordRequest;
      return HttpResponse.json({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
    }));
  show(<Record catalog={revisedCatalog} />);
  await user.click(await screen.findByRole('button', { name: '입으로 드셨어요' }));
  await user.click(screen.getByRole('button', { name: '일부 동작을 도왔어요' }));
  await user.click(screen.getByRole('button', { name: '음식 뜨기' }));
  await user.click(screen.getByRole('button', { name: '영양관을 사용했어요' }));
  await user.click(screen.getByRole('button', { name: '다음' }));
  await user.click(screen.getByRole('button', { name: '잘 주무심' }));
  await user.click(screen.getByRole('button', { name: '다음' }));
  await user.click(screen.getByRole('button', { name: '저장하기' }));
  await waitFor(() => expect(body?.changedItems.feeding).toMatchObject({ questionnaireVersion: 2,
    answers: { route: ['tube'] }, level: null, consistency: null, hand: null }));
  expect(await screen.findByText('기록을 남겼습니다.')).toBeInTheDocument();
});
