import { PageHeader } from '../ui/PageHeader';
import { Link } from 'react-router';
import { Notice } from '../ui/Notice';


/** 심사위원이 온보딩 없이 다섯 화면을 다 보게 하는 입구. */
export function Demo() {
  return (
    <main className="app-page"><div className="note-surface">
      <PageHeader title="데모" backTo="/" focusKey="demo" />
      <p className="pt-4">온보딩과 1주차 관찰부터 직접 체험합니다.</p>
      <p className="pt-3 text-ink-soft">기록을 마치면 데모 날짜를 한 주씩 진행해 다음 기록과 진료 준비 화면을 확인할 수 있습니다.</p>
      <Notice>데모 날짜만 바뀌며 실제 날짜와 일반 기록에는 영향을 주지 않습니다.</Notice>
      <div className="flex flex-col gap-3 pt-8">
        <Link className="btn" to="/demo/onboarding">온보딩부터 시작하기</Link>
      </div>
    </div></main>
  );
}
