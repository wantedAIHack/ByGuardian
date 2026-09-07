import { createBrowserRouter } from 'react-router';
import { App } from './App';
import { useCatalog } from './lib/catalog';
import { Home } from './screens/Home';
import { Onboarding } from './screens/Onboarding';

const Soon = ({ name }: { name: string }) => <p className="p-gutter">{name} — 준비 중</p>;

function OnboardingRoute() {
  return <Onboarding catalog={useCatalog()} />;
}

// Onboarding과 같은 이유다: 홈도 관찰 항목 개수(현재 8가지)를 카탈로그에서 받는다.
// 화면 스스로 useCatalog()를 부르면 화면 테스트가 CatalogProvider의 비동기 로딩까지
// 떠안는다. 라우트 쪽에서 한 번 받아 그냥 prop으로 내린다.
function HomeRoute() {
  return <Home catalog={useCatalog()} />;
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomeRoute /> },
      { path: 'onboarding', element: <OnboardingRoute /> },
      { path: 'record', element: <Soon name="주간 기록" /> },
      { path: 'trajectory', element: <Soon name="전체 궤적" /> },
      { path: 'prep-card', element: <Soon name="진료 준비 카드" /> },
      { path: 'settings', element: <Soon name="설정" /> },
      { path: 'recover', element: <Soon name="이어받기" /> },
      { path: 'demo', element: <Soon name="데모" /> },
    ],
  },
  // 치료사 화면은 App 밖이다. 보호자용 껍데기도 카탈로그도 쓰지 않는다.
  { path: '/t/:token', element: <Soon name="치료사용 요약" /> },
]);
