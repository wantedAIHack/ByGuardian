import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Demo } from './Demo';
import { getToken } from '../lib/api';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderIt() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Demo /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('데모', () => {
  it('누르기 전에는 아무것도 만들지 않는다', () => {
    renderIt();
    expect(screen.getByRole('button', { name: '데모 기록 만들기' })).toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('데모를 만들면 토큰을 넣고 치료사 링크도 알려준다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/demo`, () => HttpResponse.json({
      caseId: 'c9', guardianToken: 'demo-tok', recoveryCode: 'DEMO1234', therapistUrl: '/t/demo-token',
    })));

    renderIt();
    await user.click(screen.getByRole('button', { name: '데모 기록 만들기' }));

    await screen.findByText('데모 기록을 만들었습니다.');
    expect(getToken()).toBe('demo-tok');
    expect(screen.getByRole('link', { name: '치료사 화면 보기' }))
      .toHaveAttribute('href', '/t/demo-token');
  });

  it('실패하면 서버 문구를 그대로 보여준다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/demo`, () =>
      HttpResponse.json({ code: 'DISABLED', message: '데모가 꺼져 있습니다' }, { status: 403 })));

    renderIt();
    await user.click(screen.getByRole('button', { name: '데모 기록 만들기' }));
    expect(await screen.findByText('데모가 꺼져 있습니다')).toBeInTheDocument();
  });

  it('판정 문구를 만들지 않는다 — 만들기 전', () => {
    const { container } = renderIt();
    // 금지어 나열이 아니라 렌더된 내용 전체를 화이트리스트로 건다(Home.test.tsx·Recover.test.tsx와
    // 같은 방식). 데모 화면은 상태가 두 개뿐이고(만들기 전 / 만든 뒤) 둘 다 하드코딩 문구를
    // 직접 렌더한다 — else 쪽이 null이 아니라 글자를 내는 자리라 조작된 문구가 앉기 좋다.
    // 아래 '만든 뒤' 테스트가 그 반대쪽을 덮는다.
    expect(container.textContent).toBe(
      ['데모', '여섯 주치 관찰이 들어 있는 기록을 하나 만들어 드립니다.', '데모 기록 만들기'].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — 만든 뒤', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/demo`, () => HttpResponse.json({
      caseId: 'c9', guardianToken: 'demo-tok', recoveryCode: 'DEMO1234', therapistUrl: '/t/demo-token',
    })));

    const { container } = renderIt();
    await user.click(screen.getByRole('button', { name: '데모 기록 만들기' }));
    await screen.findByText('데모 기록을 만들었습니다.');

    // 만들기 전 상태만 화이트리스트로 걸면 이 분기는 assertion 없이 초록으로 남는다 —
    // 이 프로젝트에서 판정 문구가 숨었던 조건부 분기가 정확히 이 모양이었다(준비 카드
    // 두 번·설정 한 번·치료사 페이지 한 번). 두 상태 다 화이트리스트를 건다.
    expect(container.textContent).toBe(
      [
        '데모 기록을 만들었습니다.',
        '여섯 주치 관찰이 들어 있습니다.',
        '보호자 화면 보기',
        '치료사 화면 보기',
        '이어받기 코드 — DEMO1234',
      ].join(''),
    );
  });
});
