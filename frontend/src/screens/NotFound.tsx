import { PageHeader } from '../ui/PageHeader';
import { Link } from 'react-router';

/**
 * 없는 경로에 catch-all이 없어서 React Router의 영어 개발자 오류 화면이
 * 그대로 보였다. 오래된 북마크나 잘못 복사한 치료사 링크로 닿기 쉬운 자리인데,
 * 거기서 보호자가 보는 것이 영어 스택 안내였고 홈으로 돌아갈 길도 없었다.
 *
 * App 바깥에 둔다. 없는 주소를 여는 일에 인증이나 카탈로그가 필요할 이유가 없고,
 * 그 둘을 기다리다 또 빈 화면을 보여주게 된다.
 */
export function NotFound() {
  return (
    <main className="app-page">
      <div className="note-surface">
      <PageHeader title="이 주소에는 아무것도 없습니다" focusKey="not-found" />
      <p className="pt-4 text-ink-soft">
        주소가 바뀌었거나 잘못 복사된 것 같습니다.
      </p>
      <div className="pt-8">
        <Link className="btn" to="/">홈으로</Link>
      </div>
      </div>
    </main>
  );
}
