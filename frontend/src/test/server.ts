import { setupServer } from 'msw/node';

/** 핸들러는 테스트마다 server.use()로 준다. 등록되지 않은 요청은 실패하게 둔다. */
export const server = setupServer();
