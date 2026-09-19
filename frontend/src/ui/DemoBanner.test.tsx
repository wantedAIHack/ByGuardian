import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { setDemoToken, setToken, getToken, hasDemoToken } from '../lib/api';
import type { Me } from '../lib/types';
import { DemoBanner } from './DemoBanner';

const BASE = 'http://localhost:8080';
const me: Me = {
  caseId: 'demo', relation: '딸', today: '2026-09-05', week: 1, fullRecheck: false,
  signalsEnabled: true, handEnabled: true, canRecordThisWeek: false, recordedThisWeek: true,
  lastRecordedWeek: 1, nextVisitDate: '2026-09-30', recordedWeeks: 1, totalWeeks: 1,
  demoMode: true, canAdvanceDemo: true,
};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function LocationProbe() {
  return <span data-testid="location">{useLocation().pathname}</span>;
}

function renderIt(value: Me = me) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/']}>
    <DemoBanner me={value} /><LocationProbe />
  </MemoryRouter></QueryClientProvider>);
}

describe('데모 진행 배너', () => {
  it('가상 날짜와 주차를 보여주고 중복 클릭 없이 다음 주로 이동한다', async () => {
    let calls = 0;
    server.use(http.post(`${BASE}/me/demo/advance`, () => {
      calls += 1;
      return HttpResponse.json({ ...me, today: '2026-09-12', week: 2,
        recordedThisWeek: false, canRecordThisWeek: true, canAdvanceDemo: false });
    }));
    renderIt();

    const button = screen.getByRole('button', { name: '다음 주차로 이동' });
    fireEvent.click(button);
    fireEvent.click(button);

    expect(await screen.findByText('2주차')).toBeInTheDocument();
    expect(screen.getByText(/2026\.09\.12/)).toBeInTheDocument();
    expect(calls).toBe(1);
    expect(screen.getByRole('button', { name: '다음 주차로 이동' })).toBeDisabled();
  });

  it('현재 주 기록이 없으면 먼저 기록하도록 안내한다', () => {
    renderIt({ ...me, week: 2, recordedThisWeek: false, canAdvanceDemo: false });

    expect(screen.getByText('현재 주차 기록을 남기면 다음 주로 이동할 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음 주차로 이동' })).toBeDisabled();
  });

  it('진행 실패를 보여주고 현재 날짜를 유지한다', async () => {
    server.use(http.post(`${BASE}/me/demo/advance`, () =>
      HttpResponse.json({ code: 'DEMO_WEEK_NOT_RECORDED', message: '현재 주차 기록을 먼저 남겨주세요' }, { status: 409 })));
    renderIt();

    await userEvent.setup().click(screen.getByRole('button', { name: '다음 주차로 이동' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('현재 주차 기록을 먼저 남겨주세요');
    expect(screen.getByText(/2026\.09\.05/)).toBeInTheDocument();
  });

  it('데모 종료 시 일반 토큰을 복원하고 홈으로 이동한다', async () => {
    setToken('ordinary');
    setDemoToken('demo');
    renderIt();

    await userEvent.setup().click(screen.getByRole('button', { name: '데모 종료' }));

    expect(hasDemoToken()).toBe(false);
    expect(getToken()).toBe('ordinary');
    expect(screen.getByTestId('location')).toHaveTextContent('/');
  });
});
