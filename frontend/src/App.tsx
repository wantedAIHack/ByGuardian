import { useEffect } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { getToken, setUnauthorizedHandler } from './lib/api';
import { CatalogProvider } from './lib/catalog';
import { useMe } from './lib/queries';
import { DemoBanner } from './ui/DemoBanner';

export function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const me = useMe(getToken() !== null);

  useEffect(() => {
    // 토큰이 죽었다. 이어받기로 보낸다. api 층은 라우터를 몰라도 된다.
    setUnauthorizedHandler((demoEnded) => navigate(demoEnded ? '/' : '/recover', { replace: true }));
    return () => setUnauthorizedHandler(null);
  }, [navigate]);

  return (
    <CatalogProvider>
      {me.data?.demoMode === true && location.pathname !== '/demo/onboarding'
        ? <DemoBanner me={me.data} /> : null}
      <Outlet />
    </CatalogProvider>
  );
}
