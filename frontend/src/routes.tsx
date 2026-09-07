import { createBrowserRouter } from 'react-router';
import { App } from './App';

const Soon = ({ name }: { name: string }) => <p className="p-gutter">{name} — 준비 중</p>;

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <Soon name="홈" /> },
      { path: 'onboarding', element: <Soon name="온보딩" /> },
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
