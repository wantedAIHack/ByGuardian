import { createBrowserRouter } from 'react-router';
import { App } from './App';
import { useCatalog } from './lib/catalog';
import { Demo } from './screens/Demo';
import { Home } from './screens/Home';
import { Onboarding } from './screens/Onboarding';
import { PrepCard } from './screens/PrepCard';
import { Recover } from './screens/Recover';
import { Record } from './screens/Record';
import { Settings } from './screens/Settings';
import { Therapist } from './screens/Therapist';
import { Trajectory } from './screens/Trajectory';

function OnboardingRoute() {
  return <Onboarding catalog={useCatalog()} />;
}

// Onboarding과 같은 이유다: 홈도 관찰 항목 개수(현재 8가지)를 카탈로그에서 받는다.
// 화면 스스로 useCatalog()를 부르면 화면 테스트가 CatalogProvider의 비동기 로딩까지
// 떠안는다. 라우트 쪽에서 한 번 받아 그냥 prop으로 내린다.
function HomeRoute() {
  return <Home catalog={useCatalog()} />;
}

function RecordRoute() {
  return <Record catalog={useCatalog()} />;
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomeRoute /> },
      { path: 'onboarding', element: <OnboardingRoute /> },
      { path: 'record', element: <RecordRoute /> },
      { path: 'trajectory', element: <Trajectory /> },
      { path: 'prep-card', element: <PrepCard /> },
      { path: 'settings', element: <Settings /> },
      { path: 'recover', element: <Recover /> },
      { path: 'demo', element: <Demo /> },
    ],
  },
  // 치료사 화면은 App 밖이다. 보호자용 껍데기도 카탈로그도 쓰지 않는다.
  { path: '/t/:token', element: <Therapist /> },
]);
