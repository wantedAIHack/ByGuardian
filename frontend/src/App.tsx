import { useEffect } from 'react';
import { Outlet, useNavigate } from 'react-router';
import { setUnauthorizedHandler } from './lib/api';
import { CatalogProvider } from './lib/catalog';

export function App() {
  const navigate = useNavigate();

  useEffect(() => {
    // 토큰이 죽었다. 이어받기로 보낸다. api 층은 라우터를 몰라도 된다.
    setUnauthorizedHandler(() => navigate('/recover', { replace: true }));
    return () => setUnauthorizedHandler(null);
  }, [navigate]);

  return (
    <CatalogProvider>
      <Outlet />
    </CatalogProvider>
  );
}
