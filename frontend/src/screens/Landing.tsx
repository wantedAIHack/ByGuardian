import { Link } from 'react-router';
import { APP_NAME } from '../lib/constants';
import { Icon } from '../ui/Icon';

export function Landing() {
  return <main className="landing-shell">
    <header className="brand-header">
      <span className="brand-lockup"><span className="brand-mark"><Icon name="notebook" /></span>{APP_NAME}</span>
      <Link className="text-link" to="/recover">기존 기록 이어받기 <span aria-hidden="true">↗</span></Link>
    </header>
    <div className="landing-layout">
      <section className="landing-intro">
        <p className="eyebrow">보호자의 일상 관찰 노트</p>
        <h1>집에서의 관찰을,<br /><span className="text-accent">다음 진료의 질문으로</span></h1>
        <p className="landing-description">집에서 보신 것을 남겨두시면, 다음에 병원 가실 때 여쭤볼 것을 만들어 드립니다.</p>
        <div className="landing-actions">
          <Link className="btn" to="/onboarding">관찰 기록 시작하기 <span aria-hidden="true">→</span></Link>
          <Link className="text-link" to="/demo">먼저 둘러보기 <span aria-hidden="true">↗</span></Link>
        </div>
      </section>
      <section className="sample-notebook" aria-label="관찰 노트 예시">
        <div className="notebook-binding" aria-hidden="true" />
        <div className="flex items-center justify-between gap-4 border-b border-line pb-5"><span className="eyebrow">나의 관찰 노트</span><span className="sample-label">예시</span></div>
        <div className="py-8"><span className="icon-tile"><Icon name="notebook" /></span><h2 className="pt-5 text-[26px] font-semibold leading-snug">집에서 본 장면을<br />남겨요</h2><p className="pt-3 text-ink-soft">관찰한 내용을 모아 진료실에서 여쭤볼 질문을 준비합니다.</p></div>
        <div className="sample-question"><Icon name="question" /><span>기록에서 질문으로,<br />질문에서 대화로.</span></div>
        <div className="notebook-lines" aria-hidden="true"><span /><span /><span /></div>
      </section>
    </div>
    <ol className="landing-steps" aria-label="이용 방법">
      {['관찰 남기기', '질문 준비하기', '진료실에서 함께 보기'].map((label, i) => <li key={label}><span className="step-number" aria-hidden="true">0{i + 1}</span><span>{label}</span></li>)}
    </ol>
  </main>;
}
