import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { server } from './server';

/**
 * 핸들러 없는 요청은 그 자체로는 테스트를 실패시키지 않는다 — server.listen의
 * onUnhandledRequest: 'error'는 stderr에 한 줄 찍을 뿐이고, fetch 실패는
 * react-query가 쿼리 에러 상태로 삼켜버려서 아무 assertion에도 안 걸린다.
 * enabled 게이팅이 깨져도 아무도 모르게 되는 걸 막으려고 여기서 모았다가
 * afterEach에서 던진다. 파일당 setup.ts가 한 번 로드될 때 한 번만 등록해서
 * 테스트마다 리스너가 쌓이는 걸 막는다 — server는 파일마다 새 모듈 인스턴스라
 * 파일 사이에 새지도 않는다.
 */
const unhandled: string[] = [];
server.events.on('request:unhandled', ({ request }) => {
  unhandled.push(`${request.method} ${request.url}`);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
  if (unhandled.length > 0) {
    const offenders = unhandled.slice();
    unhandled.length = 0;
    throw new Error(
      `처리되지 않은 요청이 있었다 — enabled 게이팅이나 이 테스트의 MSW 핸들러를 확인하라:\n${offenders.join('\n')}`,
    );
  }
});
