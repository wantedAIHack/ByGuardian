import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Recover } from './Recover';
import { getToken } from '../lib/api';
import { RELATIONS } from '../lib/constants';

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
      <MemoryRouter><Recover /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('이어받기', () => {
  it('코드와 관계를 함께 받는다', () => {
    renderIt();
    expect(screen.getByLabelText('이어받기 코드')).toBeInTheDocument();
    expect(screen.getByText('어떤 분이신가요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '딸' })).toBeInTheDocument();
  });

  it('둘 다 채워야 이어받을 수 있다', async () => {
    const user = userEvent.setup();
    renderIt();

    expect(screen.getByRole('button', { name: '이어받기' })).toBeDisabled();
    await user.type(screen.getByLabelText('이어받기 코드'), 'K7M3P9RW');
    expect(screen.getByRole('button', { name: '이어받기' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '딸' }));
    expect(screen.getByRole('button', { name: '이어받기' })).toBeEnabled();
  });

  it('성공하면 토큰을 저장한다', async () => {
    const user = userEvent.setup();
    let sent: any = null;
    server.use(http.post(`${BASE}/guardians/recover`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ guardianToken: 'tok-2', caseId: 'c1' });
    }));

    renderIt();
    await user.type(screen.getByLabelText('이어받기 코드'), 'k7m3p9rw');
    await user.click(screen.getByRole('button', { name: '아들' }));
    await user.click(screen.getByRole('button', { name: '이어받기' }));

    await screen.findByText('기록을 되찾았습니다.');
    expect(getToken()).toBe('tok-2');
    // 서버가 대문자로 맞춰 보지만 화면에서도 다듬어 보낸다
    expect(sent.recoveryCode).toBe('K7M3P9RW');
    expect(sent.relation).toBe('아들');
  });

  it('코드가 틀리면 다시 확인해 달라고 한다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/guardians/recover`, () =>
      HttpResponse.json({ code: 'NOT_FOUND', message: '복구 코드가 올바르지 않습니다' }, { status: 404 })));

    renderIt();
    await user.type(screen.getByLabelText('이어받기 코드'), 'XXXXXXXX');
    await user.click(screen.getByRole('button', { name: '딸' }));
    await user.click(screen.getByRole('button', { name: '이어받기' }));

    expect(await screen.findByText('코드를 다시 확인해 주세요.')).toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('판정 문구를 만들지 않는다', () => {
    const { container } = renderIt();

    // 금지어 나열이 아니라 전체를 화이트리스트로 건다(Home.test.tsx·Trajectory.test.tsx·
    // PrepCard.test.tsx와 같은 방식). 서버는 이 화면에 판정 필드를 전혀 보내지 않는다 —
    // 코드도 관계도 아직 사용자가 입력하지 않은, 처음 마주치는 바로 이 상태에 화면의
    // 고정 문구가 전부 나온다(모든 관계 선택지 6개 포함). 관계 선택지는 RELATIONS에서
    // 직접 끌어와 조립한다 — 통째로 다시 타이핑하면 오타로 스스로 속을 수 있다.
    expect(container.textContent).toBe(
      [
        '이어받기',
        '이어받기 코드',
        '어떤 분이신가요?',
        ...RELATIONS,
        '누가 남긴 기록인지 치료사용 요약에 함께 나갑니다.',
        '이어받기',
        '← 홈',
      ].join(''),
    );
  });
});
