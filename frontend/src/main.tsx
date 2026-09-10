import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import { clearTherapistLinkOnTokenChange } from './lib/queries';
import { router } from './routes';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 30_000 },
  },
});

// 토큰이 바뀌면(온보딩·이어받기·데모·401) 발급된 치료사 링크 캐시를 지운다 — 새
// 케이스로 넘어간 뒤에도 이전 케이스의 링크가 남아 보이는 것을 막는다.
clearTherapistLinkOnTokenChange(queryClient);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
