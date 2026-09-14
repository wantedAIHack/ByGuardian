import type { ApiErrorBody } from './types';

const BASE: string = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
const TOKEN_KEY = 'guardianToken';
const HEADER = 'X-Guardian-Token';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

let onTokenChange: (() => void) | null = null;

/**
 * setToken·clearToken 양쪽 끝에서 부른다. 토큰이 바뀌는 모든 진입점(온보딩·이어받기·
 * 데모·401 처리)이 자동으로 이 신호를 타게 하려는 것이다 — 새 진입점을 추가하는
 * 사람이 "토큰 갈릴 때 캐시도 지워야 한다"를 따로 기억할 필요가 없다. api.ts는
 * React Query를 모르므로(레이어가 반대다 — queries.ts가 api.ts를 참조한다) 실제
 * 정리 로직은 main.tsx가 시작할 때 등록한다(queries.ts의 등록 헬퍼 경유).
 */
export function setTokenChangeHandler(fn: (() => void) | null): void {
  onTokenChange = fn;
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* 사파리 비공개 모드에서 던진다. 이 세션 동안만 못 쓰는 것이라 흐름을 멈추지 않는다. */
  }
  onTokenChange?.();
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* 위와 같다 */
  }
  onTokenChange?.();
}

let unauthorized: (() => void) | null = null;

/** App이 라우터를 잡은 뒤 등록한다. fetch 층이 라우팅을 몰라도 되게 하는 이음새다. */
export function setUnauthorizedHandler(fn: (() => void) | null): void {
  unauthorized = fn;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers[HEADER] = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!res.ok) {
    let code = 'UNKNOWN';
    let message = '잠시 후 다시 시도해 주세요.';
    try {
      const parsed = (await res.json()) as ApiErrorBody;
      if (parsed?.code) code = parsed.code;
      if (parsed?.message) message = parsed.message;
    } catch {
      /* 서버가 JSON을 못 준 경우. 위의 기본 문구를 쓴다. */
    }
    if (res.status === 401) {
      clearToken();
      unauthorized?.();
    }
    throw new ApiError(res.status, code, message);
  }

  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
};
