import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { catalogFixture } from '../test/fixtures';
import { APP_NAME } from './constants';
import { CatalogProvider, useCatalog } from './catalog';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function CatalogConsumer() {
  const catalog = useCatalog();
  return <p>{catalog.items[0]!.label}</p>;
}

it('로딩 중 서비스 이름을 유지하고 연결 실패 후 다시 불러온다', async () => {
  server.use(http.get('http://localhost:8080/catalog', () =>
    HttpResponse.json({ code: 'FAIL', message: '연결 실패' }, { status: 500 })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>
    <CatalogProvider><CatalogConsumer /></CatalogProvider>
  </QueryClientProvider>);

  expect(screen.getByRole('heading', { level: 1, name: APP_NAME })).toBeVisible();
  expect(screen.getByRole('status')).toBeVisible();
  expect(await screen.findByRole('alert')).toBeVisible();
  expect(screen.getByRole('heading', { level: 1, name: APP_NAME })).toBeVisible();

  server.use(http.get('http://localhost:8080/catalog', () => HttpResponse.json(catalogFixture)));
  await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도하기' }));
  expect(await screen.findByText('침대·의자에서 옮겨 앉기')).toBeVisible();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
