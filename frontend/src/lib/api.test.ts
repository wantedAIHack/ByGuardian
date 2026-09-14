import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { ApiError, api, clearToken, getToken, setToken, setUnauthorizedHandler } from './api';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  setUnauthorizedHandler(null);
});

describe('api', () => {
  it('토큰이 있으면 X-Guardian-Token 헤더를 붙인다', async () => {
    let seen: string | null = null;
    server.use(http.get(`${BASE}/me`, ({ request }) => {
      seen = request.headers.get('X-Guardian-Token');
      return HttpResponse.json({ week: 3 });
    }));

    setToken('tok-1');
    await api.get('/me');
    expect(seen).toBe('tok-1');
  });

  it('토큰이 없으면 헤더를 붙이지 않는다', async () => {
    let has = true;
    server.use(http.get(`${BASE}/catalog`, ({ request }) => {
      has = request.headers.has('X-Guardian-Token');
      return HttpResponse.json({ set: 'stroke' });
    }));

    await api.get('/catalog');
    expect(has).toBe(false);
  });

  it('서버의 code와 message를 그대로 담은 ApiError를 던진다', async () => {
    server.use(http.put(`${BASE}/me/weeks/7`, () =>
      HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 8주차입니다' }, { status: 409 })));

    const err = (await api.put('/me/weeks/7', { noChange: true }).catch((e) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.code).toBe('WEEK_MISMATCH');
    expect(err.message).toBe('이번 주는 8주차입니다');
  });

  it('본문이 JSON이 아닌 오류도 ApiError로 만든다', async () => {
    server.use(http.get(`${BASE}/me`, () => new HttpResponse('nope', { status: 500 })));

    const err = (await api.get('/me').catch((e) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(500);
    expect(err.message.length).toBeGreaterThan(0);
  });

  it('401이면 토큰을 지우고 등록된 처리기를 부른다', async () => {
    server.use(http.get(`${BASE}/me`, () =>
      HttpResponse.json({ code: 'UNAUTHORIZED', message: '토큰이 필요합니다' }, { status: 401 })));

    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    setToken('stale');

    await api.get('/me').catch(() => undefined);

    expect(getToken()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('본문 없는 성공 응답은 undefined를 돌려준다', async () => {
    server.use(http.post(`${BASE}/thing`, () => new HttpResponse(null, { status: 204 })));
    await expect(api.post('/thing')).resolves.toBeUndefined();
  });

  it('clearToken이 저장된 토큰을 지운다', () => {
    setToken('t');
    clearToken();
    expect(getToken()).toBeNull();
  });
});
